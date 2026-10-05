package io.github.kpuctajluk.colonyloom.minecraft.needs;

import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.action.EffectRecord;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.supply.CoverageShare;
import io.github.kpuctajluk.colonyloom.core.supply.SupplyRegistry;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.gameplay.needs.NeedsController;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.minecraft.storage.NativeItemDescriptor;
import io.github.kpuctajluk.colonyloom.minecraft.storage.StorageService;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** One confirmed native bread expense, then one needs publication; never replays saved evidence. */
public final class FoodConsumptionExecutor {
    @FunctionalInterface public interface Protection { boolean allow(ActionContext context, UUID principal, EffectRecord.Food prepared); }
    public enum FaultPoint { BEFORE_EFFECT, AFTER_SOURCE_CHANGE, AFTER_FACT_BEFORE_NOTIFY }
    @FunctionalInterface public interface FaultObserver { void observe(FaultPoint point, ActionContext context); }
    public record Result(WorkOrder.Reason reason, boolean consumed, boolean ambiguous) {}
    private final MinecraftServer server;
    private final ColonyRegistry registry;
    private final StorageService storage;
    private final Protection protection;
    private final Supplier<UUID> checkpoint;
    private final FaultObserver observer;
    public FoodConsumptionExecutor(MinecraftServer server, ColonyRegistry registry, StorageService storage, Protection protection,
            Supplier<UUID> checkpoint, FaultObserver observer) {
        this.server=Objects.requireNonNull(server);this.registry=Objects.requireNonNull(registry);this.storage=Objects.requireNonNull(storage);
        this.protection=protection;this.checkpoint=Objects.requireNonNull(checkpoint);this.observer=observer;
    }
    private void observe(FaultPoint point,ActionContext context) { if(observer!=null)observer.observe(point,context); }
    public Result consume(WorkOrder work,CoverageShare share,NeedsController needs) {
        if(!server.isSameThread())throw new IllegalStateException("Food consumption requires server thread");
        registry.requireOwner();var citizen=registry.citizen(work.assignee());var colony=registry.colony(work.colonyId());
        var entity=storage.currentCitizen(citizen.citizenId(),citizen.bindingEpoch(),share.slot().storage().dimension());
        if(entity==null)return denied(WorkOrder.Reason.CHUNK_NOT_READY);
        var pos=entity.blockPosition();var target=new WorldPosition(share.slot().storage().dimension(),pos.getX(),pos.getY(),pos.getZ());
        var context=new ActionContext(work.colonyId(),citizen.citizenId(),ActionContext.Kind.FOOD_CONSUME,target,ActionContext.AuthorityMode.COLONY,null,colony.authorityRevision());
        WorkOrder.Reason reason=guard(context,work,share,citizen,entity);
        if(reason!=WorkOrder.Reason.NONE)return denied(reason);
        Container inventory=storage.currentContainer(share.slot());
        if(inventory!=entity.inventory())return denied(WorkOrder.Reason.RECONCILING);
        ItemStack stack=inventory.getItem(share.slot().slot());
        if(!stack.is(Items.BREAD)||!NativeItemDescriptor.matches(stack,share.item(),server.registryAccess()))return denied(WorkOrder.Reason.MATERIALS);
        int before=stack.getCount();
        if(before<registry.storage().obligated(share.slot()))return denied(WorkOrder.Reason.RECONCILING);
        var fact=new EffectRecord.Food(share.slot(),share.item(),share.id(),share.demandId(),citizen.needs().get("food"),citizen.needs().get("food"),citizen.foodDecayTicks(),citizen.foodDecayTicks());
        EffectRecord effect=new EffectRecord(UUID.randomUUID(),work.colonyId(),work.id(),citizen.citizenId(),citizen.bindingEpoch(),ActionContext.Kind.FOOD_CONSUME,
                target,"native_inventory",share.item().itemId(),before,before,EffectRecord.State.PREPARED,0,null,null,fact,null);
        try(var prepared=registry.supply().prepareConsumption(share.id(),1)) {
            try {registry.effects().prepare(effect,work.lane());}
            catch(AdmissionLedger.AdmissionException full) {return denied(admissionReason(full));}
            try {
                observe(FaultPoint.BEFORE_EFFECT,context);
                reason=recheck(context,work,share,citizen,entity,inventory,stack,before,prepared);
                if(reason!=WorkOrder.Reason.NONE) {registry.effects().discardUnchanged(effect.operationId());return denied(reason);}
                boolean allowed=protection==null||protection.allow(context,colony.ownerId(),fact);
                reason=recheck(context,work,share,citizen,entity,inventory,stack,before,prepared);
                if(reason!=WorkOrder.Reason.NONE||!allowed) {
                    registry.effects().discardUnchanged(effect.operationId());return denied(allowed?reason:WorkOrder.Reason.PERMISSION_DENIED);
                }
                stack.shrink(1);if(stack.isEmpty())inventory.setItem(share.slot().slot(),ItemStack.EMPTY);inventory.setChanged();
                observe(FaultPoint.AFTER_SOURCE_CHANGE,context);
                int after=exactCount(inventory,share);
                if(after!=before-1)throw new IllegalStateException("Native bread expense did not match prepared portion");
                var observed=new EffectRecord.Food(fact.slot(),fact.item(),fact.shareId(),fact.demandId(),fact.foodBefore(),Math.min(20,fact.foodBefore()+5),fact.timerBefore(),fact.timerBefore());
                effect=effect.observedFood(observed,after,false);registry.effects().update(effect);
                observe(FaultPoint.AFTER_FACT_BEFORE_NOTIFY,context);
                if(guard(context,work,share,citizen,entity)!=WorkOrder.Reason.NONE||storage.currentContainer(share.slot())!=inventory
                        ||exactCount(inventory,share)!=after||!prepared.unchanged())throw new IllegalStateException("Food fact became stale before publication");
                prepared.commit(1);needs.consumed(work.id());
                registry.storage().index().observe(share.slot(),after==0?null:share.item(),after,storage.observationTick());
                return new Result(WorkOrder.Reason.NONE,true,false);
            } catch(RuntimeException failure) {
                var saved=registry.effects().get(effect.operationId());
                int after=inventory.getItem(share.slot().slot()).getCount();
                registry.effects().update(saved.observedFood(saved.food(),after,true));
                registry.effects().blockAmbiguous(work.colonyId(),checkpoint.get());registry.storage().index().unknown(share.slot());
                return new Result(WorkOrder.Reason.RECOVERY_AMBIGUOUS,false,true);
            }
        } catch(AdmissionLedger.AdmissionException full) {return denied(admissionReason(full));}
    }
    private WorkOrder.Reason recheck(ActionContext context,WorkOrder work,CoverageShare share,CitizenRecord citizen,CitizenEntity entity,
            Container inventory,ItemStack stack,int before,SupplyRegistry.PreparedConsumption prepared) {
        var reason=guard(context,work,share,citizen,entity);if(reason!=WorkOrder.Reason.NONE)return reason;
        if(!prepared.unchanged()||storage.currentContainer(share.slot())!=inventory||inventory.getItem(share.slot().slot())!=stack
                ||stack.getCount()!=before||!stack.is(Items.BREAD)||!NativeItemDescriptor.matches(stack,share.item(),server.registryAccess()))return WorkOrder.Reason.RECONCILING;
        return WorkOrder.Reason.NONE;
    }
    private WorkOrder.Reason guard(ActionContext context,WorkOrder work,CoverageShare share,CitizenRecord citizen,CitizenEntity entity) {
        var colony=registry.colony(work.colonyId());
        if(!colony.available())return colony.recoveryBlocked()?WorkOrder.Reason.RECOVERY_AMBIGUOUS:WorkOrder.Reason.CONTENT_UNAVAILABLE;
        if(colony.authorityRevision()!=context.authorityRevision()||!colony.territory().contains(context.target()))return WorkOrder.Reason.PERMISSION_DENIED;
        var current=registry.citizen(citizen.citizenId());
        if(!current.equals(citizen)||!WorkOrder.FOOD.equals(work.typeId())||work.state()!=WorkOrder.State.RUNNING||!citizen.citizenId().equals(work.subjectId())
                ||!work.id().equals(citizen.assignedWorkId())||!citizen.citizenId().equals(work.assignee())
                ||storage.currentCitizen(citizen.citizenId(),citizen.bindingEpoch(),context.target().dimension())!=entity)return WorkOrder.Reason.WORKER;
        var position=entity.blockPosition();
        if(position.getX()!=context.target().x()||position.getY()!=context.target().y()||position.getZ()!=context.target().z())return WorkOrder.Reason.TARGET_CONFLICT;
        if(share.stage()!=CoverageShare.Stage.ALLOCATED||!share.slot().storage().identity().equals(citizen.citizenId())
                ||share.slot().storage().bindingEpoch()!=citizen.bindingEpoch()||!share.item().itemId().equals("minecraft:bread"))return WorkOrder.Reason.TARGET_CONFLICT;
        var demand=registry.supply().demand(share.demandId()).snapshot();
        if(!work.id().equals(demand.ownerId())||!registry.storage().authorized(work.colonyId(),share.slot()))return WorkOrder.Reason.PERMISSION_DENIED;
        var allocation=registry.storage().allocations().get(share.obligationId());
        if(allocation==null||allocation.count()!=share.quantity()||!allocation.slot().equals(share.slot())||!allocation.item().equals(share.item()))return WorkOrder.Reason.RECONCILING;
        return WorkOrder.Reason.NONE;
    }
    private int exactCount(Container inventory,CoverageShare share) {
        ItemStack stack=inventory.getItem(share.slot().slot());if(stack.isEmpty())return 0;
        if(!stack.is(Items.BREAD)||!NativeItemDescriptor.matches(stack,share.item(),server.registryAccess()))throw new IllegalStateException("Bread components changed");
        return stack.getCount();
    }
    private static Result denied(WorkOrder.Reason reason) {return new Result(reason,false,false);}
    private static WorkOrder.Reason admissionReason(AdmissionLedger.AdmissionException full) {
        return full.reason()==AdmissionLedger.Reason.CRITICAL_CAPACITY?WorkOrder.Reason.CRITICAL_CAPACITY:WorkOrder.Reason.STATE_LIMIT;
    }
}
