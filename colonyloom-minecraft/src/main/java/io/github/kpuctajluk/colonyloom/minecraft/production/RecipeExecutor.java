package io.github.kpuctajluk.colonyloom.minecraft.production;

import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.action.EffectRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.MemberRank;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.storage.ItemDescriptor;
import io.github.kpuctajluk.colonyloom.core.storage.StockRegion;
import io.github.kpuctajluk.colonyloom.core.storage.StorageRegistry;
import io.github.kpuctajluk.colonyloom.core.supply.RecipeDefinition;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.minecraft.storage.NativeItemDescriptor;
import io.github.kpuctajluk.colonyloom.minecraft.storage.StorageService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;

/** One complete admitted batch. Facts are retained on disagreement, never compensated or replayed. */
public final class RecipeExecutor {
    public record Input(StockRegion slot,ItemDescriptor item,int count) {
        public Input {
            Objects.requireNonNull(slot); Objects.requireNonNull(item);
            if(count<1 || count>891) throw new IllegalArgumentException("Invalid native ingredient portion");
        }
    }
    public record Output(StockRegion slot,int count) {
        public Output {
            Objects.requireNonNull(slot);
            if(count<1 || count>891) throw new IllegalArgumentException("Invalid native output portion");
        }
    }
    @FunctionalInterface public interface Commit { void apply(List<Output> outputs); }
    @FunctionalInterface public interface Protection {
        boolean allow(ActionContext context,UUID principal,EffectRecord.Craft prepared);
    }
    public enum FaultPoint { BEFORE_EFFECT, AFTER_NATIVE_EFFECT, AFTER_FACT_BEFORE_NOTIFY }
    @FunctionalInterface public interface FaultObserver { void observe(FaultPoint point,ActionContext context); }
    public record Result(int produced,WorkOrder.Reason reason,boolean ambiguous,UUID operationId) {
        public Result {
            Objects.requireNonNull(reason);
            if(produced<0 || produced>EffectRecord.Craft.MAX_TOTAL || ambiguous && reason!=WorkOrder.Reason.RECOVERY_AMBIGUOUS)
                throw new IllegalArgumentException("Invalid craft result");
        }
    }
    private record Slot(StockRegion region,ItemStack reference,ItemDescriptor item,int count,int amount) {}
    private record Snapshot(BarrelBlockEntity barrel,CitizenEntity producer,UUID principal,
            List<Slot> inputs,List<Slot> outputs,WorkOrder.Reason reason) {}
    private final MinecraftServer server;
    private final ColonyRegistry registry;
    private final StorageService storage;
    private final Protection protection;
    private final Supplier<UUID> checkpoint;
    private final FaultObserver observer;

    public RecipeExecutor(MinecraftServer server,ColonyRegistry registry,StorageService storage,
            Protection protection,Supplier<UUID> checkpoint) {
        this(server,registry,storage,protection,checkpoint,null);
    }
    public RecipeExecutor(MinecraftServer server,ColonyRegistry registry,StorageService storage,
            Protection protection,Supplier<UUID> checkpoint,FaultObserver observer) {
        this.server=Objects.requireNonNull(server); this.registry=Objects.requireNonNull(registry);
        this.storage=Objects.requireNonNull(storage); this.protection=protection;
        this.checkpoint=Objects.requireNonNull(checkpoint); this.observer=observer;
    }
    private void observe(FaultPoint point,ActionContext context) { if(observer!=null) observer.observe(point,context); }
    private static Result denied(WorkOrder.Reason reason) { return new Result(0,reason,false,null); }

    /**
     * Inputs and outputs must be distinct canonical slots of the accepted workshop barrel.
     * stillCurrent is a pure check of the preadmitted logical transaction, batch, work, and exact allocations.
     * The final boundary reads <=16 slots once; native after-facts read those <=16 once more.
     * Fixed-size current producer/table/barrel/authority guards are independent of that slot budget.
     */
    public Result craft(ActionContext context,long epoch,UUID productionId,long batchOrdinal,
            StorageRegistry.Workshop workshop,RecipeDefinition recipe,List<Input> inputs,List<Output> outputs,
            BooleanSupplier stillCurrent,Commit commit) {
        if(!server.isSameThread()) throw new IllegalStateException("Craft requires server thread");
        registry.requireOwner(); Objects.requireNonNull(context); Objects.requireNonNull(productionId);
        Objects.requireNonNull(workshop); Objects.requireNonNull(recipe); Objects.requireNonNull(stillCurrent); Objects.requireNonNull(commit);
        inputs=List.copyOf(inputs); outputs=List.copyOf(outputs);
        validate(recipe,inputs,outputs,epoch,batchOrdinal);
        // A retained native fact for this exact batch is never permission to run it again.
        for(var saved:registry.effects().snapshots()) if(saved.craft()!=null
                && saved.craft().productionId().equals(productionId) && saved.craft().batchOrdinal()==batchOrdinal)
            return denied(saved.state()==EffectRecord.State.AMBIGUOUS?WorkOrder.Reason.RECOVERY_AMBIGUOUS:WorkOrder.Reason.TARGET_CONFLICT);
        WorkOrder.Reason reason=guard(context,epoch,workshop,recipe);
        if(reason!=WorkOrder.Reason.NONE) return denied(reason);
        if(!stillCurrent.getAsBoolean()) return denied(WorkOrder.Reason.TARGET_CONFLICT);
        ItemStack outputProbe;
        try { outputProbe=NativeItemDescriptor.capacityProbe(recipe.output(),server.registryAccess()); }
        catch(RuntimeException invalid) { return denied(WorkOrder.Reason.CONTENT_UNAVAILABLE); }
        Snapshot before=snapshot(context,epoch,workshop,inputs,outputs,outputProbe);
        if(before.reason()!=WorkOrder.Reason.NONE) return denied(before.reason());
        var registration=registry.storage().registrations(context.colonyId()).stream()
                .filter(value -> value.id().equals(workshop.registrationId())).findFirst().orElseThrow();
        var fact=new EffectRecord.Craft(productionId,batchOrdinal,workshop.id(),workshop.registrationId(),workshop.revision(),
                workshop.position(),registration.address(),recipe.id(),recipe.version(),recipe.digest(),slots(before.inputs()),
                recipe.output(),Math.toIntExact(recipe.outputCount()),slots(before.outputs()),EffectRecord.CraftPhase.PREPARED);
        var citizen=registry.citizen(context.citizenId());
        WorkOrder work=citizen.assignedWorkId()==null?null:registry.workBoard().work(citizen.assignedWorkId());
        var effect=new EffectRecord(UUID.randomUUID(),context.colonyId(),work==null?null:work.id(),context.citizenId(),epoch,
                ActionContext.Kind.RECIPE_CRAFT,context.target(),"minecraft:crafting_table",recipe.output().itemId(),
                fact.outputBefore(),fact.outputBefore(),EffectRecord.State.PREPARED,0,null,fact,null);
        try { registry.effects().prepare(effect,work==null?Lane.NORMAL:work.lane()); }
        catch(AdmissionLedger.AdmissionException | IllegalArgumentException unavailable) { return denied(WorkOrder.Reason.STATE_LIMIT); }
        boolean started=false, measured=false; int produced=0;
        EffectRecord.CraftPhase phase=EffectRecord.CraftPhase.PREPARED;
        try {
            observe(FaultPoint.BEFORE_EFFECT,context);
            reason=guard(context,epoch,workshop,recipe);
            if(reason!=WorkOrder.Reason.NONE || !stillCurrent.getAsBoolean()) {
                registry.effects().discardUnchanged(effect.operationId());
                return denied(reason==WorkOrder.Reason.NONE?WorkOrder.Reason.TARGET_CONFLICT:reason);
            }
            boolean allowed=protection==null || protection.allow(context,before.principal(),fact);
            reason=guard(context,epoch,workshop,recipe);
            if(reason!=WorkOrder.Reason.NONE || !stillCurrent.getAsBoolean()) {
                registry.effects().discardUnchanged(effect.operationId());
                return denied(reason==WorkOrder.Reason.NONE?WorkOrder.Reason.TARGET_CONFLICT:reason);
            }
            // No callbacks or repeated slot reads between this exact snapshot and the native effect.
            Snapshot current=snapshot(context,epoch,workshop,inputs,outputs,outputProbe);
            if(!same(before,current)) {
                registry.effects().discardUnchanged(effect.operationId());
                return denied(current.reason()==WorkOrder.Reason.NONE?WorkOrder.Reason.TARGET_CONFLICT:current.reason());
            }
            if(!allowed) {
                registry.effects().discardUnchanged(effect.operationId()); return denied(WorkOrder.Reason.PERMISSION_DENIED);
            }
            started=true;
            for(var source:current.inputs()) {
                source.reference().shrink(source.amount());
                if(source.reference().isEmpty()) current.barrel().setItem(source.region().slot(),ItemStack.EMPTY);
            }
            phase=EffectRecord.CraftPhase.INPUTS_CONSUMED;
            // New stacks are created only by the accepted recipe after the entire real kit was expended.
            for(var destination:current.outputs()) {
                if(destination.reference().isEmpty()) current.barrel().setItem(destination.region().slot(),
                        NativeItemDescriptor.recipeOutput(recipe.output(),destination.amount(),server.registryAccess()));
                else destination.reference().grow(destination.amount());
                produced=Math.addExact(produced,destination.amount());
            }
            phase=EffectRecord.CraftPhase.OUTPUT_INSERTED;
            current.barrel().setChanged(); observe(FaultPoint.AFTER_NATIVE_EFFECT,context);
            fact=measure(fact,current.barrel(),EffectRecord.CraftPhase.FACT_OBSERVED); measured=true;
            if(!fact.complete()) throw new IllegalStateException("Native craft disagrees with admitted batch");
            effect=effect.observedCraft(fact,false); registry.effects().update(effect);
            observe(FaultPoint.AFTER_FACT_BEFORE_NOTIFY,context);
            if(guard(context,epoch,workshop,recipe)!=WorkOrder.Reason.NONE || !stillCurrent.getAsBoolean()
                    || storage.currentWorkshopBarrel(workshop)!=current.barrel())
                throw new IllegalStateException("Native craft fact lost authority before publication");
            // Test-only observers may replace stack objects; production has no post-fact native callback.
            // This observer-only extra <=16 readback is outside the production <=32 boundary profile.
            if(observer!=null && !fact.equals(measure(fact,current.barrel(),EffectRecord.CraftPhase.FACT_OBSERVED)))
                throw new IllegalStateException("Native craft fact changed before publication");
            for(var destination:fact.outputs()) registry.storage().index().observe(destination.slot(),destination.afterItem(),
                    destination.afterCount(),storage.observationTick());
            commit.apply(outputs);
            // Never reconcile expenditure until the exact ingredient obligations have been consumed.
            for(var source:fact.inputs()) registry.storage().index().observe(source.slot(),source.afterItem(),source.afterCount(),storage.observationTick());
            return new Result(produced,WorkOrder.Reason.NONE,false,effect.operationId());
        } catch(RuntimeException failure) {
            if(!started) {
                registry.effects().discardUnchanged(effect.operationId()); return denied(WorkOrder.Reason.TARGET_CONFLICT);
            }
            EffectRecord saved=registry.effects().get(effect.operationId());
            if(saved.state()==EffectRecord.State.PREPARED) {
                if(!measured) {
                    try { fact=measure(fact,before.barrel(),phase); }
                    catch(RuntimeException unavailable) {
                        // Retain prepared evidence honestly when an external stack exceeds the physical codec envelope.
                        // AMBIGUOUS, not invented after-components/counts, forces native recovery inspection.
                        fact=saved.craft();
                    }
                }
                registry.effects().update(saved.observedCraft(fact,true));
            } else if(saved.state()==EffectRecord.State.OBSERVED) registry.effects().update(saved.observedCraft(saved.craft(),true));
            registry.effects().blockAmbiguous(context.colonyId(),checkpoint.get());
            for(var source:inputs) registry.storage().index().unknown(source.slot());
            for(var destination:outputs) registry.storage().index().unknown(destination.slot());
            return new Result(produced,WorkOrder.Reason.RECOVERY_AMBIGUOUS,true,effect.operationId());
        }
    }

    private static void validate(RecipeDefinition recipe,List<Input> inputs,List<Output> outputs,long epoch,long ordinal) {
        if(epoch<1 || ordinal<0 || inputs.isEmpty() || outputs.isEmpty() || inputs.size()+outputs.size()>EffectRecord.Craft.MAX_SLOTS
                || recipe.ingredients().size()>8 || recipe.outputCount()>EffectRecord.Craft.MAX_TOTAL)
            throw new IllegalArgumentException("Native craft envelope exceeded");
        var slots=new HashSet<StockRegion>(); long[] quantities=new long[recipe.ingredients().size()];
        for(var input:inputs) {
            if(input.slot().storage().bindingEpoch()!=0 || !slots.add(input.slot())) throw new IllegalArgumentException("Duplicate/nonbarrel ingredient slot");
            int matched=-1;
            for(int i=0;i<quantities.length;i++) if(recipe.ingredients().get(i).matcher().matches(input.item())) {
                if(matched!=-1) throw new IllegalArgumentException("Overlapping ingredient matchers"); matched=i;
            }
            if(matched==-1) throw new IllegalArgumentException("Ingredient portion outside accepted recipe");
            quantities[matched]=Math.addExact(quantities[matched],input.count());
        }
        for(int i=0;i<quantities.length;i++) if(quantities[i]!=recipe.ingredients().get(i).count())
            throw new IllegalArgumentException("Recipe requires one complete exact ingredient batch");
        long output=0;
        for(var destination:outputs) {
            if(destination.slot().storage().bindingEpoch()!=0 || !slots.add(destination.slot()))
                throw new IllegalArgumentException("Output overlaps ingredient or duplicate canonical slot");
            output=Math.addExact(output,destination.count());
        }
        if(output!=recipe.outputCount()) throw new IllegalArgumentException("Whole recipe output must be routed before expense");
    }
    private WorkOrder.Reason guard(ActionContext context,long epoch,StorageRegistry.Workshop workshop,RecipeDefinition recipe) {
        if(context.actionKind()!=ActionContext.Kind.RECIPE_CRAFT || !context.colonyId().equals(workshop.colonyId())
                || !context.target().equals(workshop.position())) return WorkOrder.Reason.PERMISSION_DENIED;
        var citizen=registry.findCitizen(context.citizenId()).orElse(null);
        if(citizen==null || !citizen.colonyId().equals(context.colonyId()) || citizen.bindingEpoch()!=epoch
                || !recipe.professionId().equals(citizen.professionId()) || !workshop.id().equals(citizen.workplaceId())) return WorkOrder.Reason.WORKER;
        var colony=registry.colony(context.colonyId());
        if(!colony.available()) return colony.recoveryBlocked()?WorkOrder.Reason.RECOVERY_AMBIGUOUS:WorkOrder.Reason.CONTENT_UNAVAILABLE;
        if(colony.authorityRevision()!=context.authorityRevision() || !colony.territory().contains(context.target())) return WorkOrder.Reason.PERMISSION_DENIED;
        if(context.authorityMode()==ActionContext.AuthorityMode.INDIVIDUAL) {
            MemberRank rank=colony.rank(context.initiatorId());
            if(rank!=MemberRank.OWNER && rank!=MemberRank.MANAGER) return WorkOrder.Reason.PERMISSION_DENIED;
        }
        if(!recipe.equipmentId().equals("minecraft:crafting_table")) return WorkOrder.Reason.CONTENT_UNAVAILABLE;
        CitizenEntity producer=storage.currentCitizen(context.citizenId(),epoch,workshop.position().dimension());
        if(producer==null || storage.currentWorkshopBarrel(workshop)==null) return WorkOrder.Reason.CHUNK_NOT_READY;
        if(producer.distanceToSqr(context.target().x()+0.5,context.target().y()+0.5,context.target().z()+0.5)>9) return WorkOrder.Reason.UNREACHABLE;
        return WorkOrder.Reason.NONE;
    }
    private Snapshot snapshot(ActionContext context,long epoch,StorageRegistry.Workshop workshop,List<Input> inputs,List<Output> outputs,ItemStack outputProbe) {
        BarrelBlockEntity barrel=storage.currentWorkshopBarrel(workshop);
        CitizenEntity producer=storage.currentCitizen(context.citizenId(),epoch,workshop.position().dimension());
        var colony=registry.colony(context.colonyId());
        UUID principal=context.authorityMode()==ActionContext.AuthorityMode.COLONY?colony.ownerId():context.initiatorId();
        if(barrel==null || producer==null) return new Snapshot(barrel,producer,principal,List.of(),List.of(),WorkOrder.Reason.CHUNK_NOT_READY);
        var registration=registry.storage().registrations(context.colonyId()).stream().filter(value -> value.id().equals(workshop.registrationId())).findFirst().orElse(null);
        if(registration==null) return new Snapshot(barrel,producer,principal,List.of(),List.of(),WorkOrder.Reason.TARGET_CONFLICT);
        var sources=new ArrayList<Slot>(inputs.size()); var destinations=new ArrayList<Slot>(outputs.size());
        for(var input:inputs) {
            if(!registration.slots().contains(input.slot())) return new Snapshot(barrel,producer,principal,sources,destinations,WorkOrder.Reason.PERMISSION_DENIED);
            ItemStack nativeStack=barrel.getItem(input.slot().slot());
            if(nativeStack.getCount()<input.count() || !NativeItemDescriptor.matches(nativeStack,input.item(),server.registryAccess()))
                return new Snapshot(barrel,producer,principal,sources,destinations,WorkOrder.Reason.MATERIALS);
            sources.add(new Slot(input.slot(),nativeStack,input.item(),nativeStack.getCount(),input.count()));
        }
        for(var destination:outputs) {
            if(!registration.slots().contains(destination.slot())) return new Snapshot(barrel,producer,principal,sources,destinations,WorkOrder.Reason.PERMISSION_DENIED);
            ItemStack nativeStack=barrel.getItem(destination.slot().slot());
            if(!barrel.canPlaceItem(destination.slot().slot(),outputProbe)
                    || !nativeStack.isEmpty() && !ItemStack.isSameItemSameComponents(nativeStack,outputProbe)
                    || destination.count()>Math.min(barrel.getMaxStackSize(),outputProbe.getMaxStackSize())-nativeStack.getCount())
                return new Snapshot(barrel,producer,principal,sources,destinations,WorkOrder.Reason.CAPACITY);
            destinations.add(new Slot(destination.slot(),nativeStack,nativeStack.isEmpty()?null:NativeItemDescriptor.describe(nativeStack,server.registryAccess()),nativeStack.getCount(),destination.count()));
        }
        return new Snapshot(barrel,producer,principal,sources,destinations,WorkOrder.Reason.NONE);
    }
    private static boolean same(Snapshot before,Snapshot current) {
        if(current.reason()!=WorkOrder.Reason.NONE || before.barrel()!=current.barrel() || before.producer()!=current.producer()
                || !before.principal().equals(current.principal()) || before.inputs().size()!=current.inputs().size() || before.outputs().size()!=current.outputs().size()) return false;
        for(int i=0;i<before.inputs().size();i++) if(!same(before.inputs().get(i),current.inputs().get(i))) return false;
        for(int i=0;i<before.outputs().size();i++) if(!same(before.outputs().get(i),current.outputs().get(i))) return false;
        return true;
    }
    private static boolean same(Slot before,Slot current) {
        return before.reference()==current.reference() && before.count()==current.count() && Objects.equals(before.item(),current.item());
    }
    private static List<EffectRecord.CraftSlot> slots(List<Slot> slots) {
        return slots.stream().map(slot -> new EffectRecord.CraftSlot(slot.region(),slot.item(),slot.count(),slot.item(),slot.count(),slot.amount())).toList();
    }
    private EffectRecord.Craft measure(EffectRecord.Craft fact,BarrelBlockEntity barrel,EffectRecord.CraftPhase phase) {
        return fact.observed(measure(fact.inputs(),barrel),measure(fact.outputs(),barrel),phase);
    }
    private List<EffectRecord.CraftSlot> measure(List<EffectRecord.CraftSlot> slots,BarrelBlockEntity barrel) {
        var result=new ArrayList<EffectRecord.CraftSlot>(slots.size());
        for(var slot:slots) {
            ItemStack nativeStack=barrel.getItem(slot.slot().slot());
            result.add(slot.observed(NativeItemDescriptor.describe(nativeStack,server.registryAccess()),nativeStack.getCount()));
        }
        return result;
    }
}
