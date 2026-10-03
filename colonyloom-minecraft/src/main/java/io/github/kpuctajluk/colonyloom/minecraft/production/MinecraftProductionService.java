package io.github.kpuctajluk.colonyloom.minecraft.production;

import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.chunk.*;
import io.github.kpuctajluk.colonyloom.core.colony.*;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.navigation.NavigationService;
import io.github.kpuctajluk.colonyloom.core.scheduler.*;
import io.github.kpuctajluk.colonyloom.core.storage.*;
import io.github.kpuctajluk.colonyloom.core.supply.SupplyRegistry;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.gameplay.production.ProductionController;
import io.github.kpuctajluk.colonyloom.minecraft.storage.StorageService;
import java.util.*;

/** Existing scheduler and movement execute one pinned batch; active time never comes from offline ticks. */
public final class MinecraftProductionService implements SimulationScheduler.PhysicalExecutor,NavigationService.GoalAuthority,AutoCloseable {
    private static final class Active {
        final UUID chunkOwner=UUID.randomUUID();
        WorldPosition waypoint;
        long generation,lastActive=-1;
        boolean processing;
        int outputCursor;
        final List<SupplyRegistry.OutputPortion> outputs=new ArrayList<>(15);
    }
    private final ColonyRegistry registry;
    private final StorageService storage;
    private final RecipeExecutor executor;
    private final ChunkDemandManager chunks;
    private final ProductionController controller;
    private final Map<UUID,Active> active=new HashMap<>();
    private NavigationService navigation;
    public MinecraftProductionService(ColonyRegistry registry,StorageService storage,RecipeExecutor executor,ChunkDemandManager chunks) {
        this.registry=registry;this.storage=storage;this.executor=executor;this.chunks=chunks;controller=new ProductionController(registry);
    }
    public void navigation(NavigationService navigation) {this.navigation=Objects.requireNonNull(navigation);}
    public void tick() {controller.tick(registry.budgets());}
    public boolean current(WorkOrder work,NavigationService.Request request) {
        var state=active.get(work.id());var order=registry.supply().productionForWork(work.id());
        return order!=null&&!order.terminal()&&state!=null&&state.generation==request.goalRevision()&&request.target().equals(state.waypoint);
    }
    public void step(WorkOrder work,long tick) {
        var supply=registry.supply();var order=supply.productionForWork(work.id());
        if(order==null) {registry.workBoard().transition(work.id(),WorkOrder.State.FAILED,WorkOrder.Reason.CONTENT_UNAVAILABLE,"production");return;}
        if(order.terminal()) {registry.workBoard().transition(work.id(),WorkOrder.State.COMPLETED,WorkOrder.Reason.NONE,"completed");return;}
        var state=active.computeIfAbsent(work.id(),ignored -> new Active());var citizen=registry.citizen(work.assignee());
        var workshopId=order.workshopId();
        var workshop=registry.storage().workshops().stream().filter(w -> w.id().equals(workshopId)).findFirst().orElse(null);
        if(workshop==null||!supply.workshopAvailable(order)) {waitFor(work,state,WorkOrder.Reason.RECONCILING);return;}
        var destination=supply.workshopDestination(order);
        var region=List.of(new ChunkKey(destination.dimension(),destination.x()>>4,destination.z()>>4),new ChunkKey(workshop.position().dimension(),workshop.position().x()>>4,workshop.position().z()>>4));
        try {chunks.request(state.chunkOwner,work.colonyId(),region,ChunkDemandManager.Readiness.ENTITY_TICKING,work.lane(),work.priority(),true);}
        catch(AdmissionLedger.AdmissionException full) {waitFor(work,state,WorkOrder.Reason.STATE_LIMIT);return;}
        if(!chunks.ready(state.chunkOwner)||storage.currentWorkshopBarrel(workshop)==null) {waitFor(work,state,WorkOrder.Reason.CHUNK_NOT_READY);return;}
        chunks.useful(state.chunkOwner);
        var kit=supply.completeProductionKit(order.id());
        if(kit.isEmpty()) {
            state.processing=false;state.lastActive=-1;state.outputCursor=0;state.outputs.clear();
            if(!order.batchStarted())registry.workBoard().transition(work.id(),WorkOrder.State.WAITING,WorkOrder.Reason.MATERIALS,"kit");
            else waitFor(work,state,WorkOrder.Reason.MATERIALS);
            return;
        }
        var waypoint=new WorldPosition(destination.dimension(),destination.x(),destination.y(),destination.z()+1);
        if(!waypoint.equals(state.waypoint)) {navigation.cancel(work.id());state.waypoint=waypoint;state.generation++;}
        try {if(navigation.request(work.id(),work.colonyId(),citizen.citizenId(),citizen.bindingEpoch(),state.generation,waypoint,work.lane(),work.priority())==null) {waitFor(work,state,WorkOrder.Reason.RECONCILING);return;}}
        catch(AdmissionLedger.AdmissionException full) {waitFor(work,state,WorkOrder.Reason.STATE_LIMIT);return;}
        if(!navigation.atTarget(work.id())) {state.processing=false;state.lastActive=-1;if(navigation.state(work.id())==NavigationService.State.WAITING)waitFor(work,state,navigation.reason(work.id()));return;}
        if(!order.batchStarted()) {supply.startProduction(order.id(),citizen.citizenId(),work.id());order=supply.production(order.id());state.lastActive=citizen.activeTimeTicks();state.processing=true;}
        else {
            long elapsed=state.processing&&state.lastActive>=0?Math.max(0,citizen.activeTimeTicks()-state.lastActive):0;
            state.lastActive=citizen.activeTimeTicks();state.processing=true;
            if(elapsed>0)supply.advanceProduction(order.id(),elapsed);
            order=supply.production(order.id());
        }
        if(order.remainingActiveTicks()>0)return;
        var registration=registry.storage().registrations(work.colonyId()).stream().filter(r -> r.id().equals(workshop.registrationId())).findFirst().orElseThrow();
        var inputSlots=new HashSet<StockRegion>();for(var portion:kit)inputSlots.add(portion.slot());
        int outputLimit=io.github.kpuctajluk.colonyloom.core.action.EffectRecord.Craft.MAX_SLOTS-inputSlots.size();
        if(state.outputs.size()>outputLimit || state.outputs.stream().anyMatch(portion -> inputSlots.contains(portion.slot()))) {state.outputCursor=0;state.outputs.clear();}
        long remaining=order.recipe().outputCount();for(var portion:state.outputs)remaining-=portion.count();
        for(int inspected=0;remaining>0 && inspected<16 && state.outputCursor<registration.slots().size() && state.outputs.size()<outputLimit;inspected++) {
            if(!registry.budgets().tryConsume(Budget.STORAGE_SLOT_CHECKS,work.lane()))return;
            var slot=registration.slots().get(state.outputCursor++);
            if(inputSlots.contains(slot))continue;
            int capacity=storage.capacity(slot,order.recipe().output());
            if(capacity>0) {int count=(int)Math.min(remaining,capacity);state.outputs.add(new SupplyRegistry.OutputPortion(slot,count));remaining-=count;}
        }
        if(remaining>0) {
            if(state.outputCursor>=registration.slots().size() || state.outputs.size()>=outputLimit) {state.outputCursor=0;state.outputs.clear();waitFor(work,state,WorkOrder.Reason.CAPACITY);}
            return;
        }
        var outputs=List.copyOf(state.outputs);
        int checks=Math.multiplyExact(inputSlots.size()+outputs.size(),2);
        if(registry.budgets().limits().budget(Budget.STORAGE_SLOT_CHECKS)-registry.budgets().used(Budget.STORAGE_SLOT_CHECKS)<checks)return;
        for(int i=0;i<checks;i++)if(!registry.budgets().tryConsume(Budget.STORAGE_SLOT_CHECKS,work.lane()))return;
        try(var prepared=supply.prepareProduction(order.id(),outputs,tick)) {
            var grouped=new LinkedHashMap<StockRegion,RecipeExecutor.Input>();
            for(var portion:prepared.inputs()) {
                var old=grouped.get(portion.slot());
                if(old!=null&&!old.item().equals(portion.item()))throw new IllegalStateException("Canonical ingredient identity changed");
                grouped.put(portion.slot(),new RecipeExecutor.Input(portion.slot(),portion.item(),Math.addExact(old==null?0:old.count(),portion.count())));
            }
            var inputs=List.copyOf(grouped.values());
            var nativeOutputs=prepared.outputs().stream().map(p -> new RecipeExecutor.Output(p.slot(),p.count())).toList();
            var colony=registry.colony(work.colonyId());
            var context=new ActionContext(work.colonyId(),citizen.citizenId(),ActionContext.Kind.RECIPE_CRAFT,workshop.position(),ActionContext.AuthorityMode.COLONY,null,colony.authorityRevision());
            var result=executor.craft(context,citizen.bindingEpoch(),order.id(),order.completedBatches(),workshop,order.recipe(),inputs,nativeOutputs,
                    () -> prepared.unchanged()&&!work.terminal()&&citizen.citizenId().equals(work.assignee())&&work.id().equals(registry.citizen(citizen.citizenId()).assignedWorkId()),ignored -> prepared.commit());
            if(result.produced()==0||result.ambiguous()) {state.outputCursor=0;state.outputs.clear();waitFor(work,state,result.reason());}
            else {state.lastActive=-1;state.processing=false;state.outputCursor=0;state.outputs.clear();var next=supply.production(order.id());
                navigation.cancel(work.id());
                registry.workBoard().transition(work.id(),next.terminal()?WorkOrder.State.COMPLETED:WorkOrder.State.WAITING,next.terminal()?WorkOrder.Reason.NONE:WorkOrder.Reason.MATERIALS,next.terminal()?"completed":"kit");}
        } catch(AdmissionLedger.AdmissionException full) {waitFor(work,state,WorkOrder.Reason.STATE_LIMIT);}
    }
    private void waitFor(WorkOrder work,Active state,WorkOrder.Reason reason) {
        state.processing=false;state.lastActive=-1;
        registry.workBoard().waitAssigned(work.id(),reason==WorkOrder.Reason.NONE?WorkOrder.Reason.RECONCILING:reason,"production");
    }
    public void cancel(UUID workId) {if(navigation!=null)navigation.cancel(workId);var state=active.remove(workId);if(state!=null)chunks.release(state.chunkOwner);}
    public void close() {for(var id:List.copyOf(active.keySet()))cancel(id);}
}
