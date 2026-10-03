package io.github.kpuctajluk.colonyloom.minecraft.needs;

import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.scheduler.SimulationScheduler;
import io.github.kpuctajluk.colonyloom.core.supply.CoverageShare;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.gameplay.needs.NeedsController;
import io.github.kpuctajluk.colonyloom.minecraft.storage.MinecraftDeliveryService;
import io.github.kpuctajluk.colonyloom.minecraft.storage.StorageService;
import java.util.Objects;
import java.util.UUID;

/** Needs owns demand/food state; the existing delivery executor owns every bread route and safe unload. */
public final class MinecraftNeedsService implements NeedsController.Port, SimulationScheduler.PhysicalExecutor {
    private final ColonyRegistry registry;
    private final StorageService storage;
    private final MinecraftDeliveryService delivery;
    private final FoodConsumptionExecutor executor;
    private NeedsController needs;
    public MinecraftNeedsService(ColonyRegistry registry,StorageService storage,MinecraftDeliveryService delivery,FoodConsumptionExecutor executor) {
        this.registry=Objects.requireNonNull(registry);this.storage=Objects.requireNonNull(storage);
        this.delivery=Objects.requireNonNull(delivery);this.executor=Objects.requireNonNull(executor);
    }
    public void needs(NeedsController needs) {this.needs=Objects.requireNonNull(needs);}
    @Override public boolean requestPreemption(CitizenRecord citizen) {return delivery.requestFoodPreemption(citizen);}
    @Override public void step(WorkOrder work,long tick) {
        registry.requireOwner();var demand=needs.demandForWork(work.id());
        if(demand==null) {registry.workBoard().transition(work.id(),WorkOrder.State.WAITING,WorkOrder.Reason.MATERIALS,"food");return;}
        var citizen=registry.citizen(work.assignee());
        var share=registry.supply().demandShares(demand.id()).stream().filter(value -> value.stage()==CoverageShare.Stage.ALLOCATED
                &&value.slot().storage().identity().equals(citizen.citizenId())&&value.slot().storage().bindingEpoch()==citizen.bindingEpoch()).findFirst().orElse(null);
        if(share==null) {registry.workBoard().transition(work.id(),WorkOrder.State.WAITING,WorkOrder.Reason.MATERIALS,"food");return;}
        if(!registry.budgets().tryConsume(Budget.STORAGE_SLOT_CHECKS,work.lane())) {waitFor(work,WorkOrder.Reason.BUDGET);return;}
        var observation=storage.readFresh(share.slot());
        if(!observation.ready()) {waitFor(work,WorkOrder.Reason.RECONCILING);return;}
        registry.storage().index().observe(share.slot(),observation.item(),observation.count(),tick);
        if(!share.item().equals(observation.item())||observation.count()<registry.storage().obligated(share.slot())) {waitFor(work,WorkOrder.Reason.MATERIALS);return;}
        long started=System.nanoTime();FoodConsumptionExecutor.Result result;
        try {result=executor.consume(work,share,needs);}
        finally {registry.metrics().record(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer.PHYSICAL_UNIT,System.nanoTime()-started);}
        if(!result.consumed())waitFor(work,result.reason());
    }
    private void waitFor(WorkOrder work,WorkOrder.Reason reason) {registry.workBoard().waitAssigned(work.id(),reason,"food");}
    @Override public void cancel(UUID workId) {delivery.cancel(workId);}
}
