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
        registry.requireOwner();if(work.assignee()==null||work.state()!=WorkOrder.State.RUNNING)return;long revision=work.revision();var demand=needs.demandForWork(work.id());
        if(demand==null) {if(work.assignee()!=null&&!work.terminal())registry.workBoard().transition(work.id(),WorkOrder.State.WAITING,WorkOrder.Reason.MATERIALS,"food");return;}
        var citizen=registry.findCitizen(work.assignee()).orElse(null);if(citizen==null)return;
        UUID citizenId=citizen.citizenId();long epoch=citizen.bindingEpoch();
        if(!current(work,revision,citizenId,epoch))return;
        var share=registry.supply().demandShares(demand.id()).stream().filter(value -> value.stage()==CoverageShare.Stage.ALLOCATED
                &&value.slot().storage().identity().equals(citizen.citizenId())&&value.slot().storage().bindingEpoch()==citizen.bindingEpoch()).findFirst().orElse(null);
        if(share==null) {if(current(work,revision,citizenId,epoch))registry.workBoard().transition(work.id(),WorkOrder.State.WAITING,WorkOrder.Reason.MATERIALS,"food");return;}
        if(!registry.budgets().tryConsume(Budget.STORAGE_SLOT_CHECKS,work.lane())) {waitFor(work,revision,citizenId,epoch,WorkOrder.Reason.BUDGET);return;}
        var observation=storage.readFresh(share.slot());
        if(!current(work,revision,citizenId,epoch))return;
        if(!observation.ready()) {waitFor(work,revision,citizenId,epoch,WorkOrder.Reason.RECONCILING);return;}
        registry.storage().index().observe(share.slot(),observation.item(),observation.count(),tick);
        if(!current(work,revision,citizenId,epoch))return;
        if(!share.item().equals(observation.item())||observation.count()<registry.storage().obligated(share.slot())) {waitFor(work,revision,citizenId,epoch,WorkOrder.Reason.MATERIALS);return;}
        long started=System.nanoTime();FoodConsumptionExecutor.Result result;
        try {result=executor.consume(work,share,needs);}
        finally {registry.metrics().record(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer.PHYSICAL_UNIT,System.nanoTime()-started);}
        if(!result.consumed()&&current(work,revision,citizenId,epoch))waitFor(work,revision,citizenId,epoch,result.reason());
    }
    private boolean current(WorkOrder work,long revision,UUID citizenId,long epoch) {
        if(citizenId==null||work.revision()!=revision||work.terminal()||work.state()!=WorkOrder.State.RUNNING||!citizenId.equals(work.assignee()))return false;
        var citizen=registry.findCitizen(citizenId).orElse(null);
        return citizen!=null&&citizen.bindingEpoch()==epoch&&work.id().equals(citizen.assignedWorkId())
                &&citizen.lifecycle()==CitizenRecord.Lifecycle.ALIVE&&citizen.admission()==CitizenRecord.Admission.ACTIVE
                &&citizen.readiness()==CitizenRecord.Readiness.READY;
    }
    private void waitFor(WorkOrder work,long revision,UUID citizenId,long epoch,WorkOrder.Reason reason) {
        if(current(work,revision,citizenId,epoch))registry.workBoard().waitAssigned(work.id(),reason,"food");
    }
    @Override public void cancel(UUID workId) {delivery.cancel(workId);}
}
