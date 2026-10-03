package io.github.kpuctajluk.colonyloom.minecraft.storage;

import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.action.EffectRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.MemberRank;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.storage.ItemDescriptor;
import io.github.kpuctajluk.colonyloom.core.storage.StockRegion;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** One native stack per server step; evidence is a fact, never permission to replay an effect. */
public final class StorageTransferExecutor {
    @FunctionalInterface public interface Commit { void apply(int moved); }
    @FunctionalInterface public interface Protection {
        boolean allow(ActionContext context, UUID principal, StockRegion source, StockRegion destination, int amount);
    }
    public enum FaultPoint { BEFORE_EFFECT, AFTER_SOURCE_CHANGE, AFTER_DESTINATION_CHANGE, AFTER_FACT_BEFORE_NOTIFY }
    @FunctionalInterface public interface FaultObserver { void observe(FaultPoint point, ActionContext context); }
    public record Result(int moved, WorkOrder.Reason reason, boolean ambiguous) {
        public Result {
            Objects.requireNonNull(reason);
            if (moved < 0 || ambiguous && reason != WorkOrder.Reason.RECOVERY_AMBIGUOUS)
                throw new IllegalArgumentException("Invalid native transfer result");
        }
    }
    private record Preflight(CitizenEntity courier, Container source, Container destination,
            ItemStack sourceStack, ItemStack destinationStack, int sourceCount, int destinationCount, int amount, UUID principal) {}
    private final MinecraftServer server;
    private final ColonyRegistry registry;
    private final StorageService storage;
    private final Protection protection;
    private final Supplier<UUID> checkpoint;
    private final FaultObserver observer;

    public StorageTransferExecutor(MinecraftServer server, ColonyRegistry registry, StorageService storage,
            Protection protection, Supplier<UUID> checkpoint) {
        this(server,registry,storage,protection,checkpoint,null);
    }
    public StorageTransferExecutor(MinecraftServer server, ColonyRegistry registry, StorageService storage,
            Protection protection, Supplier<UUID> checkpoint, FaultObserver observer) {
        this.server=Objects.requireNonNull(server); this.registry=Objects.requireNonNull(registry);
        this.storage=Objects.requireNonNull(storage); this.protection=protection;
        this.checkpoint=Objects.requireNonNull(checkpoint); this.observer=observer;
    }
    /** Test-only observer, without implicit saves/checkpoints or production callbacks. */
    private void observe(FaultPoint point, ActionContext context) {
        if (observer!=null) observer.observe(point,context);
    }
    private static Result denied(WorkOrder.Reason reason) { return new Result(0,reason,false); }

    public Result transfer(ActionContext context, long epoch, StockRegion source, StockRegion destination,
            ItemDescriptor item, int maximum, Commit commit) {
        if (!server.isSameThread()) throw new IllegalStateException("Transfer requires server thread");
        registry.requireOwner(); Objects.requireNonNull(context); Objects.requireNonNull(source);
        Objects.requireNonNull(destination); Objects.requireNonNull(item); Objects.requireNonNull(commit);
        if (maximum<1 || maximum>891 || epoch<1) throw new IllegalArgumentException("Invalid transfer portion");
        if (source.equals(destination)) return denied(WorkOrder.Reason.TARGET_CONFLICT);
        WorkOrder.Reason reason=guard(context,epoch,source,destination);
        if (reason!=WorkOrder.Reason.NONE) return denied(reason);
        Preflight prepared=preflight(context,epoch,source,destination,item,maximum);
        if (prepared==null) return denied(WorkOrder.Reason.CHUNK_NOT_READY);
        if (prepared.sourceStack().isEmpty() || !NativeItemDescriptor.matches(prepared.sourceStack(),item,server.registryAccess()))
            return denied(WorkOrder.Reason.MATERIALS);
        if (prepared.amount()==0) return denied(WorkOrder.Reason.CAPACITY);
        Preflight current=prepared;
        int sourceBefore=current.sourceCount(), destinationBefore=current.destinationCount();
        var citizen=registry.citizen(context.citizenId());
        WorkOrder work=citizen.assignedWorkId()==null?null:registry.workBoard().work(citizen.assignedWorkId());
        var fact=new EffectRecord.Transfer(source,destination,item,sourceBefore,sourceBefore,destinationBefore,destinationBefore,current.amount(),0,0,0);
        var effect=new EffectRecord(UUID.randomUUID(),context.colonyId(),work==null?null:work.id(),context.citizenId(),epoch,
                ActionContext.Kind.STORAGE_TRANSFER,context.target(),"native_inventory",item.itemId(),sourceBefore,sourceBefore,EffectRecord.State.PREPARED,0,fact,null,null);
        try { registry.effects().prepare(effect,work==null?Lane.NORMAL:work.lane()); }
        catch (AdmissionLedger.AdmissionException full) { return denied(full.reason()==AdmissionLedger.Reason.CRITICAL_CAPACITY?WorkOrder.Reason.CRITICAL_CAPACITY:WorkOrder.Reason.STATE_LIMIT); }
        catch (IllegalArgumentException denied) { return denied(WorkOrder.Reason.STATE_LIMIT); }
        ItemStack remaining=ItemStack.EMPTY;
        int extracted=0, inserted=0, returned=0;
        try {
            observe(FaultPoint.BEFORE_EFFECT,context);
            // Evidence admission/dirty marker and test observers cannot grant stale authority.
            reason=guard(context,epoch,source,destination);
            Preflight finalState=reason==WorkOrder.Reason.NONE?preflight(context,epoch,source,destination,item,maximum):null;
            if (reason!=WorkOrder.Reason.NONE || !same(current,finalState) || !assignmentCurrent(citizen,work)) {
                registry.effects().discardUnchanged(effect.operationId());
                return denied(reason==WorkOrder.Reason.NONE?WorkOrder.Reason.TARGET_CONFLICT:reason);
            }
            boolean allowed=protection==null || protection.allow(context,current.principal(),source,destination,current.amount());
            reason=guard(context,epoch,source,destination);
            finalState=reason==WorkOrder.Reason.NONE?preflight(context,epoch,source,destination,item,maximum):null;
            if (reason!=WorkOrder.Reason.NONE || !same(current,finalState) || !assignmentCurrent(citizen,work)) {
                registry.effects().discardUnchanged(effect.operationId());
                return denied(reason==WorkOrder.Reason.NONE?WorkOrder.Reason.TARGET_CONFLICT:reason);
            }
            if (!allowed) {
                registry.effects().discardUnchanged(effect.operationId());
                return denied(WorkOrder.Reason.PERMISSION_DENIED);
            }
            remaining=current.sourceStack().split(current.amount());
            extracted=remaining.getCount();
            if (current.sourceStack().isEmpty()) current.source().setItem(source.slot(),ItemStack.EMPTY);
            current.source().setChanged();
            observe(FaultPoint.AFTER_SOURCE_CHANGE,context);
            if (extracted!=current.amount() || !NativeItemDescriptor.matches(remaining,item,server.registryAccess()))
                throw new IllegalStateException("Native source extraction changed agreed stack");
            // Only the extracted physical stack is inserted; descriptors never manufacture cargo.
            int capacity=StorageService.capacity(current.destination(),destination.slot(),remaining);
            int accepted=Math.min(remaining.getCount(),capacity);
            if (accepted>0) {
                ItemStack target=current.destination().getItem(destination.slot());
                if (target.isEmpty()) {
                    ItemStack handed=accepted==remaining.getCount()?remaining:remaining.split(accepted);
                    try { current.destination().setItem(destination.slot(),handed); }
                    catch (RuntimeException failure) {
                        // Native setItem may notify after storing; never insert the same object twice.
                        if (current.destination().getItem(destination.slot())==handed) {
                            inserted=handed.getCount();
                            if (handed==remaining) remaining=ItemStack.EMPTY;
                        } else if (handed!=remaining) remaining.grow(handed.getCount());
                        throw failure;
                    }
                    inserted=accepted;
                    if (handed==remaining) remaining=ItemStack.EMPTY;
                } else {
                    target.grow(accepted); remaining.shrink(accepted); inserted=accepted; current.destination().setChanged();
                }
            }
            current.destination().setChanged();
            observe(FaultPoint.AFTER_DESTINATION_CHANGE,context);
            if (!remaining.isEmpty()) returned=returnToCourier(current.courier(),remaining,source.storage().bindingEpoch()>0?source.slot():destination.slot());
            int sourceAfter=exactCount(current.source(),source.slot(),item);
            int destinationAfter=exactCount(current.destination(),destination.slot(),item);
            fact=fact.observed(sourceAfter,destinationAfter,extracted,inserted,returned);
            if (inserted<0 || inserted>extracted || returned!=extracted-inserted || !remaining.isEmpty()
                    || sourceBefore-sourceAfter!=inserted || destinationAfter-destinationBefore!=inserted)
                throw new IllegalStateException("Native transfer counters are not conserved");
            effect=effect.observedTransfer(fact,false); registry.effects().update(effect);
            observe(FaultPoint.AFTER_FACT_BEFORE_NOTIFY,context);
            if (guard(context,epoch,source,destination)!=WorkOrder.Reason.NONE || !assignmentCurrent(citizen,work)
                    || storage.currentContainer(source)!=current.source() || storage.currentContainer(destination)!=current.destination()
                    || exactCount(current.source(),source.slot(),item)!=sourceAfter
                    || exactCount(current.destination(),destination.slot(),item)!=destinationAfter)
                throw new IllegalStateException("Native transfer fact became stale before publication");
            // Destination is known before the prepared claim moves; source loss reconciles afterwards.
            registry.storage().index().observe(destination,destinationAfter==0?null:item,destinationAfter,storage.observationTick());
            if (inserted>0) commit.apply(inserted);
            registry.storage().index().observe(source,sourceAfter==0?null:item,sourceAfter,storage.observationTick());
            return new Result(inserted,inserted==0?WorkOrder.Reason.CAPACITY:WorkOrder.Reason.NONE,false);
        } catch (RuntimeException failure) {
            // Preserve any real in-flight remainder in native courier custody, never inverse-transfer or replay.
            if (!remaining.isEmpty()) returned+=returnToCourier(current.courier(),remaining,source.storage().bindingEpoch()>0?source.slot():destination.slot());
            int sourceAfter=current.source().getItem(source.slot()).getCount();
            int destinationAfter=current.destination().getItem(destination.slot()).getCount();
            EffectRecord saved=registry.effects().get(effect.operationId());
            if (saved.state()==EffectRecord.State.PREPARED)
                registry.effects().update(saved.observedTransfer(saved.transfer().observed(sourceAfter,destinationAfter,extracted,inserted,returned),true));
            else if (saved.state()==EffectRecord.State.OBSERVED) registry.effects().update(saved.observedTransfer(saved.transfer(),true));
            registry.effects().blockAmbiguous(context.colonyId(),checkpoint.get());
            registry.storage().index().unknown(source); registry.storage().index().unknown(destination);
            return new Result(inserted,WorkOrder.Reason.RECOVERY_AMBIGUOUS,true);
        }
    }
    private boolean assignmentCurrent(io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord before,WorkOrder work) {
        var citizen=registry.citizen(before.citizenId());
        return Objects.equals(citizen.assignedWorkId(),before.assignedWorkId())
                &&(work==null||!work.terminal()&&citizen.citizenId().equals(work.assignee()));
    }
    private Preflight preflight(ActionContext context,long epoch,StockRegion source,StockRegion destination,ItemDescriptor item,int maximum) {
        CitizenEntity courier=storage.currentCitizen(context.citizenId(),epoch,source.storage().dimension());
        Container from=storage.currentContainer(source), to=storage.currentContainer(destination);
        if (courier==null || from==null || to==null) return null;
        ItemStack stack=from.getItem(source.slot()), target=to.getItem(destination.slot());
        int amount=stack.isEmpty() || !NativeItemDescriptor.matches(stack,item,server.registryAccess())?0:
                Math.min(maximum,Math.min(stack.getCount(),Math.min(stack.getMaxStackSize(),StorageService.capacity(to,destination.slot(),stack))));
        var colony=registry.colony(context.colonyId());
        UUID principal=context.authorityMode()==ActionContext.AuthorityMode.COLONY?colony.ownerId():context.initiatorId();
        return new Preflight(courier,from,to,stack,target,stack.getCount(),target.getCount(),amount,principal);
    }
    private static boolean same(Preflight before,Preflight after) {
        return after!=null && before.courier()==after.courier() && before.source()==after.source()
                && before.destination()==after.destination() && before.sourceStack()==after.sourceStack()
                && before.destinationStack()==after.destinationStack() && before.amount()==after.amount()
                && before.sourceCount()==after.sourceCount() && before.destinationCount()==after.destinationCount()
                && before.principal().equals(after.principal());
    }
    private WorkOrder.Reason guard(ActionContext context,long epoch,StockRegion source,StockRegion destination) {
        if (context.actionKind()!=ActionContext.Kind.STORAGE_TRANSFER) return WorkOrder.Reason.PERMISSION_DENIED;
        var citizen=registry.findCitizen(context.citizenId()).orElse(null);
        if (citizen==null || !citizen.colonyId().equals(context.colonyId())) return WorkOrder.Reason.WORKER;
        var colony=registry.colony(context.colonyId());
        if (!colony.available()) return colony.recoveryBlocked()?WorkOrder.Reason.RECOVERY_AMBIGUOUS:WorkOrder.Reason.CONTENT_UNAVAILABLE;
        if (colony.authorityRevision()!=context.authorityRevision() || !colony.territory().contains(context.target())) return WorkOrder.Reason.PERMISSION_DENIED;
        if (context.authorityMode()==ActionContext.AuthorityMode.INDIVIDUAL) {
            MemberRank rank=colony.rank(context.initiatorId());
            if (rank!=MemberRank.OWNER && rank!=MemberRank.MANAGER) return WorkOrder.Reason.PERMISSION_DENIED;
        }
        if (!source.storage().dimension().equals(destination.storage().dimension())
                || !source.storage().dimension().equals(context.target().dimension())) return WorkOrder.Reason.TARGET_CONFLICT;
        boolean sourceCourier=source.storage().identity().equals(context.citizenId()) && source.storage().bindingEpoch()==epoch;
        boolean destinationCourier=destination.storage().identity().equals(context.citizenId()) && destination.storage().bindingEpoch()==epoch;
        if (sourceCourier==destinationCourier || source.storage().bindingEpoch()>0 && !sourceCourier
                || destination.storage().bindingEpoch()>0 && !destinationCourier) return WorkOrder.Reason.TARGET_CONFLICT;
        if (!registry.storage().authorized(context.colonyId(),source) || !registry.storage().authorized(context.colonyId(),destination)) return WorkOrder.Reason.PERMISSION_DENIED;
        CitizenEntity entity=storage.currentCitizen(context.citizenId(),epoch,source.storage().dimension());
        if (entity==null) return WorkOrder.Reason.CHUNK_NOT_READY;
        WorldPosition block=storage.locate(sourceCourier?destination.storage():source.storage());
        if (block==null) return WorkOrder.Reason.CHUNK_NOT_READY;
        if (!colony.territory().contains(block)) return WorkOrder.Reason.PERMISSION_DENIED;
        if (entity.distanceToSqr(block.x()+0.5,block.y()+0.5,block.z()+0.5)>9) return WorkOrder.Reason.UNREACHABLE;
        return WorkOrder.Reason.NONE;
    }
    private int exactCount(Container container,int slot,ItemDescriptor item) {
        ItemStack stack=container.getItem(slot);
        if (stack.isEmpty()) return 0;
        if (!NativeItemDescriptor.matches(stack,item,server.registryAccess())) throw new IllegalStateException("Native components changed");
        return stack.getCount();
    }
    private static int returnToCourier(CitizenEntity courier,ItemStack remaining,int preferredSlot) {
        int before=remaining.getCount(); Container inventory=courier.inventory();
        for (int index=0;index<inventory.getContainerSize() && !remaining.isEmpty();index++) {
            int slot=index==0?preferredSlot:index<=preferredSlot?index-1:index;
            int accepted=Math.min(remaining.getCount(),StorageService.capacity(inventory,slot,remaining));
            if (accepted==0) continue;
            ItemStack target=inventory.getItem(slot);
            if (target.isEmpty()) {
                ItemStack handed=remaining.split(accepted);
                try { inventory.setItem(slot,handed); }
                catch (RuntimeException failure) {
                    if (inventory.getItem(slot)!=handed) remaining.grow(handed.getCount());
                    throw failure;
                }
            }
            else { target.grow(accepted); remaining.shrink(accepted); inventory.setChanged(); }
        }
        if (!remaining.isEmpty()) {
            // Impossible for unchanged supported slots; preserve physical property if an external observer changed all slots.
            ItemStack spill=remaining.split(remaining.getCount());
            if (courier.spawnAtLocation(spill)==null) { remaining.grow(spill.getCount()); throw new IllegalStateException("Native courier cannot retain transfer remainder"); }
        }
        return before;
    }
}
