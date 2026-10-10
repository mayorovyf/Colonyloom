package io.github.kpuctajluk.colonyloom.minecraft.production;

import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.chunk.*;
import io.github.kpuctajluk.colonyloom.core.colony.*;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.production.ProductionOrder;
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
        long generation,lastActive=-1,continuation,stepContinuation,stepRevision,stepEpoch;
        UUID stepCitizen;
        ProductionOrder stepOrder;
        long waitingRevision=-1;
        WorkOrder.Reason waitingReason;
        boolean processing,cancelling,executing;
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
        return order!=null&&!order.terminal()&&retained(work,state)
                &&request.citizenId().equals(state.stepCitizen)&&request.epoch()==state.stepEpoch
                &&state.generation==request.goalRevision()&&request.target().equals(state.waypoint);
    }
    private boolean owner(WorkOrder work,Active state,long continuation,UUID citizenId,long bindingEpoch) {
        if(state==null||active.get(work.id())!=state||state.cancelling||state.continuation!=continuation||citizenId==null
                ||registry.workBoard().findWork(work.id())!=work||work.terminal())return false;
        var citizen=registry.findCitizen(citizenId).orElse(null);
        return citizen!=null&&citizenId.equals(work.assignee())&&citizen.bindingEpoch()==bindingEpoch
                &&work.id().equals(citizen.assignedWorkId())&&citizen.colonyId().equals(work.colonyId())
                &&citizen.lifecycle()==CitizenRecord.Lifecycle.ALIVE&&citizen.admission()==CitizenRecord.Admission.ACTIVE
                &&citizen.readiness()==CitizenRecord.Readiness.READY;
    }
    private boolean current(WorkOrder work,Active state,long continuation,long revision,UUID citizenId,long bindingEpoch) {
        return state!=null&&state.stepContinuation==continuation&&state.stepRevision==revision
                &&Objects.equals(state.stepCitizen,citizenId)&&state.stepEpoch==bindingEpoch
                &&owner(work,state,continuation,citizenId,bindingEpoch)&&work.revision()==revision
                &&work.state()==WorkOrder.State.RUNNING&&registry.supply().productionForWork(work.id())==state.stepOrder;
    }
    private boolean current(WorkOrder work,Active state) {
        return state!=null&&current(work,state,state.stepContinuation,state.stepRevision,state.stepCitizen,state.stepEpoch);
    }
    private boolean retained(WorkOrder work,Active state) {
        return state!=null&&owner(work,state,state.stepContinuation,state.stepCitizen,state.stepEpoch)
                &&registry.supply().productionForWork(work.id())==state.stepOrder
                &&(work.state()==WorkOrder.State.RUNNING&&work.revision()==state.stepRevision
                ||work.state()==WorkOrder.State.WAITING&&work.revision()==state.waitingRevision
                    &&work.waitingReason()==state.waitingReason&&work.stage().equals("production"));
    }
    private boolean acknowledge(WorkOrder work,Active state,long continuation,long revision,ProductionOrder before,
            long batches,long ticks,long completed,boolean started,UUID citizen,ProductionOrder.State phase) {
        if(state.stepOrder!=before||state.stepContinuation!=continuation||state.stepRevision!=revision
                ||!owner(work,state,continuation,state.stepCitizen,state.stepEpoch)
                ||work.state()!=WorkOrder.State.RUNNING||work.revision()!=revision)return false;
        var actual=registry.supply().productionForWork(work.id());
        if(actual==null||actual.revision()!=Math.incrementExact(before.revision())||!actual.id().equals(before.id())
                ||!actual.colonyId().equals(before.colonyId())||!actual.ownerDemandId().equals(before.ownerDemandId())
                ||actual.recipe()!=before.recipe()||!Objects.equals(actual.workId(),before.workId())
                ||!Objects.equals(actual.workshopId(),before.workshopId())||!Objects.equals(actual.equipmentPosition(),before.equipmentPosition())
                ||!Objects.equals(actual.workshopStorage(),before.workshopStorage())||actual.lane()!=before.lane()||actual.priority()!=before.priority()
                ||actual.batches()!=batches||actual.remainingActiveTicks()!=ticks||actual.completedBatches()!=completed
                ||actual.batchStarted()!=started||!Objects.equals(actual.citizenId(),citizen)||actual.state()!=phase)return false;
        state.stepOrder=actual;return true;
    }

    public void step(WorkOrder work,long tick) {
        registry.requireOwner();
        if(work.assignee()==null||work.state()!=WorkOrder.State.RUNNING)return;
        long revision=work.revision();
        var supply=registry.supply();var order=supply.productionForWork(work.id());
        if(order==null) {registry.workBoard().transition(work.id(),WorkOrder.State.FAILED,WorkOrder.Reason.CONTENT_UNAVAILABLE,"production");return;}
        if(order.terminal()) {registry.workBoard().transition(work.id(),WorkOrder.State.COMPLETED,WorkOrder.Reason.NONE,"completed");return;}
        var state=active.computeIfAbsent(work.id(),ignored -> new Active());long continuation=state.continuation;var citizen=registry.findCitizen(work.assignee()).orElse(null);
        if(citizen==null||state.cancelling||state.executing)return;
        state.executing=true;
        try {
        state.stepContinuation=continuation;state.stepRevision=revision;state.stepCitizen=citizen.citizenId();state.stepEpoch=citizen.bindingEpoch();
        state.stepOrder=order;state.waitingRevision=-1;state.waitingReason=null;
        if(!current(work,state))return;
        var workshopId=order.workshopId();
        var workshop=registry.storage().workshops().stream().filter(w -> w.id().equals(workshopId)).findFirst().orElse(null);
        if(workshop==null||!supply.workshopAvailable(order)) {waitFor(work,state,WorkOrder.Reason.RECONCILING);return;}
        var destination=supply.workshopDestination(order);
        var region=List.of(new ChunkKey(destination.dimension(),destination.x()>>4,destination.z()>>4),new ChunkKey(workshop.position().dimension(),workshop.position().x()>>4,workshop.position().z()>>4));
        try {chunks.request(state.chunkOwner,work.colonyId(),region,ChunkDemandManager.Readiness.ENTITY_TICKING,work.lane(),work.priority(),true);}
        catch(AdmissionLedger.AdmissionException full) {if(current(work,state))waitFor(work,state,WorkOrder.Reason.STATE_LIMIT);return;}
        if(!current(work,state))return;
        if(!chunks.admitted(state.chunkOwner))return;
        if(!current(work,state)||!chunks.admitted(state.chunkOwner)||!chunks.ready(state.chunkOwner))return;
        if(storage.currentWorkshopBarrel(workshop)==null) {if(current(work,state))waitFor(work,state,WorkOrder.Reason.CHUNK_NOT_READY);return;}
        if(!current(work,state)||!chunks.admitted(state.chunkOwner)||!chunks.ready(state.chunkOwner))return;
        chunks.useful(state.chunkOwner);
        if(!current(work,state)||!chunks.admitted(state.chunkOwner)||!chunks.ready(state.chunkOwner))return;
        var kit=supply.completeProductionKit(order.id());
        if(!current(work,state))return;
        if(kit.isEmpty()) {
            state.processing=false;state.lastActive=-1;state.outputCursor=0;state.outputs.clear();
            if(!order.batchStarted())registry.workBoard().transition(work.id(),WorkOrder.State.WAITING,WorkOrder.Reason.MATERIALS,"kit");
            else waitFor(work,state,WorkOrder.Reason.MATERIALS);
            return;
        }
        var waypoint=new WorldPosition(destination.dimension(),destination.x(),destination.y(),destination.z()+1);
        if(!waypoint.equals(state.waypoint)) {navigation.cancel(work.id());if(!current(work,state))return;state.waypoint=waypoint;state.generation++;}
        UUID navigationRequest;
        try {navigationRequest=navigation.request(work.id(),work.colonyId(),citizen.citizenId(),citizen.bindingEpoch(),state.generation,waypoint,work.lane(),work.priority());}
        catch(AdmissionLedger.AdmissionException full) {
            if(current(work,state))waitFor(work,state,full.reason()==AdmissionLedger.Reason.CRITICAL_CAPACITY?WorkOrder.Reason.CRITICAL_CAPACITY:WorkOrder.Reason.STATE_LIMIT);
            return;
        }
        if(!current(work,state)||!chunks.admitted(state.chunkOwner))return;
        if(navigationRequest==null) {waitFor(work,state,WorkOrder.Reason.RECONCILING);return;}
        if(!current(work,state))return;
        if(!navigation.atTarget(work.id())) {state.processing=false;state.lastActive=-1;if(navigation.state(work.id())==NavigationService.State.WAITING&&current(work,state))waitFor(work,state,navigation.reason(work.id()));return;}
        if(!current(work,state))return;
        if(!order.batchStarted()) {
            supply.startProduction(order.id(),citizen.citizenId(),work.id());
            if(!acknowledge(work,state,continuation,revision,order,order.batches(),order.remainingActiveTicks(),order.completedBatches(),true,citizen.citizenId(),ProductionOrder.State.PROCESSING))return;
            order=state.stepOrder;state.lastActive=citizen.activeTimeTicks();state.processing=true;
        }
        else {
            long elapsed=state.processing&&state.lastActive>=0?Math.max(0,citizen.activeTimeTicks()-state.lastActive):0;
            state.lastActive=citizen.activeTimeTicks();state.processing=true;
            if(elapsed>0&&order.remainingActiveTicks()>0) {
                supply.advanceProduction(order.id(),elapsed);
                if(!acknowledge(work,state,continuation,revision,order,order.batches(),Math.max(0,order.remainingActiveTicks()-Math.min(elapsed,order.remainingActiveTicks())),order.completedBatches(),true,order.citizenId(),order.state()))return;
            }
            if(!current(work,state))return;
            order=state.stepOrder;
        }
        if(!current(work,state)||order.remainingActiveTicks()>0)return;
        var registration=registry.storage().registrations(work.colonyId()).stream().filter(r -> r.id().equals(workshop.registrationId())).findFirst().orElseThrow();
        var inputSlots=new HashSet<StockRegion>();for(var portion:kit)inputSlots.add(portion.slot());
        int outputLimit=io.github.kpuctajluk.colonyloom.core.action.EffectRecord.Craft.MAX_SLOTS-inputSlots.size();
        if(state.outputs.size()>outputLimit || state.outputs.stream().anyMatch(portion -> inputSlots.contains(portion.slot()))) {state.outputCursor=0;state.outputs.clear();}
        long remaining=order.recipe().outputCount();for(var portion:state.outputs)remaining-=portion.count();
        for(int inspected=0;remaining>0 && inspected<16 && state.outputCursor<registration.slots().size() && state.outputs.size()<outputLimit;inspected++) {
            if(!registry.budgets().tryConsume(Budget.STORAGE_SLOT_CHECKS,work.lane()))return;
            if(!current(work,state)||!chunks.admitted(state.chunkOwner)||!chunks.ready(state.chunkOwner))return;
            var slot=registration.slots().get(state.outputCursor++);
            if(inputSlots.contains(slot))continue;
            int capacity=storage.capacity(slot,order.recipe().output());
            if(!current(work,state)||!chunks.admitted(state.chunkOwner)||!chunks.ready(state.chunkOwner))return;
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
            if(!current(work,state)||!chunks.admitted(state.chunkOwner)||!chunks.ready(state.chunkOwner))return;
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
            long batches=order.batches()-1;
            var craftOrder=order;
            var result=executor.craft(context,citizen.bindingEpoch(),order.id(),order.completedBatches(),workshop,order.recipe(),inputs,nativeOutputs,
                    () -> prepared.unchanged()&&current(work,state,continuation,revision,citizen.citizenId(),citizen.bindingEpoch())
                            &&chunks.admitted(state.chunkOwner)&&chunks.ready(state.chunkOwner),ignored -> {
                                prepared.commit();
                                acknowledge(work,state,continuation,revision,craftOrder,batches,batches==0?0:craftOrder.recipe().activeTicks(),Math.incrementExact(craftOrder.completedBatches()),false,null,batches==0?ProductionOrder.State.COMPLETED:ProductionOrder.State.PLANNED);
                            });
            if(!current(work,state,continuation,revision,citizen.citizenId(),citizen.bindingEpoch()))return;
            if(result.produced()==0||result.ambiguous()) {state.outputCursor=0;state.outputs.clear();waitFor(work,state,result.reason());}
            else {state.lastActive=-1;state.processing=false;state.outputCursor=0;state.outputs.clear();var next=supply.production(order.id());
                navigation.cancel(work.id());if(!current(work,state,continuation,revision,citizen.citizenId(),citizen.bindingEpoch()))return;
                registry.workBoard().transition(work.id(),next.terminal()?WorkOrder.State.COMPLETED:WorkOrder.State.WAITING,next.terminal()?WorkOrder.Reason.NONE:WorkOrder.Reason.MATERIALS,next.terminal()?"completed":"kit");}
        } catch(AdmissionLedger.AdmissionException full) {if(current(work,state))waitFor(work,state,WorkOrder.Reason.STATE_LIMIT);}
        } finally {state.executing=false;}
    }
    private void waitFor(WorkOrder work,Active state,WorkOrder.Reason reason) {
        if(!current(work,state))return;
        state.processing=false;state.lastActive=-1;
        var typedReason=reason==WorkOrder.Reason.NONE?WorkOrder.Reason.RECONCILING:reason;
        long expectedRevision=work.state()==WorkOrder.State.WAITING&&work.waitingReason()==typedReason&&work.stage().equals("production")?work.revision():Math.incrementExact(state.stepRevision);
        long continuation=state.stepContinuation,epoch=state.stepEpoch;
        var citizen=state.stepCitizen;var order=state.stepOrder;
        registry.workBoard().waitAssigned(work.id(),typedReason,"production");
        if(state.stepContinuation==continuation&&state.stepCitizen==citizen&&state.stepEpoch==epoch&&state.stepOrder==order
                &&owner(work,state,continuation,citizen,epoch)&&registry.supply().productionForWork(work.id())==order
                &&work.state()==WorkOrder.State.WAITING&&work.revision()==expectedRevision
                &&work.waitingReason()==typedReason&&work.stage().equals("production")) {
            state.waitingRevision=expectedRevision;state.waitingReason=typedReason;
        }
    }
    public void cancel(UUID workId) {
        registry.requireOwner();var state=active.get(workId);
        if(state==null){if(navigation!=null)navigation.cancel(workId);return;}
        if(state.cancelling)return;
        state.cancelling=true;state.continuation++;
        state.waypoint=null;state.generation++;state.processing=false;state.lastActive=-1;
        state.stepCitizen=null;state.stepOrder=null;state.waitingRevision=-1;state.waitingReason=null;
        Throwable failure=null;
        try {if(navigation!=null)navigation.cancel(workId);}
        catch(RuntimeException|Error thrown) {failure=thrown;throw thrown;}
        finally {
            try {chunks.release(state.chunkOwner);}
            catch(RuntimeException|Error cleanup) {if(failure!=null){if(failure!=cleanup)failure.addSuppressed(cleanup);}else throw cleanup;}
            finally {
                try {
                    var order=registry.supply().productionForWork(workId);
                    if(order==null||order.terminal()||registry.workBoard().work(workId).terminal())active.remove(workId,state);
                } finally {state.cancelling=false;}
            }
        }
    }
    public void close() {
        Throwable failure=null;
        try {
            for(var id:List.copyOf(active.keySet())) {
                try {cancel(id);}
                catch(RuntimeException|Error thrown) {if(failure==null)failure=thrown;else if(failure!=thrown)failure.addSuppressed(thrown);}
            }
        } finally {active.clear();}
        if(failure instanceof RuntimeException thrown)throw thrown;
        if(failure instanceof Error thrown)throw thrown;
    }
}
