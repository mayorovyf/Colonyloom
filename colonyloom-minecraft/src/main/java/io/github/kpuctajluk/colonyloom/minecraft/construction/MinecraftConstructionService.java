package io.github.kpuctajluk.colonyloom.minecraft.construction;

import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.action.EffectRecord;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.construction.ConstructionSnapshot;
import io.github.kpuctajluk.colonyloom.core.navigation.NavigationService;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.scheduler.SimulationScheduler;
import io.github.kpuctajluk.colonyloom.core.spatial.TargetClaimRegistry;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.core.world.WorldAccess;
import io.github.kpuctajluk.colonyloom.gameplay.construction.ConstructionController;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** One bounded physical step; block/entity/colony files remain independently durable sides. */
public final class MinecraftConstructionService implements SimulationScheduler.PhysicalExecutor,NavigationService.GoalAuthority,AutoCloseable {
    private static final class Active {
        final ConstructionController.Layout layout;
        final UUID chunkOwner=UUID.randomUUID();
        final java.util.List<ChunkKey> region;
        WorldPosition waypoint;
        long generation,continuation,stepContinuation,stepRevision,stepEpoch,waitRevision=-1;
        java.util.UUID stepCitizen;
        int lastCursor=-1;
        boolean arrived,cancelling,executing;
        long retryAt;
        WorkOrder.Reason retryReason=WorkOrder.Reason.NONE;
        WorkOrder.Reason waitReason=WorkOrder.Reason.NONE;
        int reconcileCursor;
        final MinecraftConstructionSupply.Portion materials=new MinecraftConstructionSupply.Portion();
        Active(ConstructionController.Layout layout) {
            this.layout=layout; var claim=layout.claim(); var keys=new ArrayList<ChunkKey>();
            for(int x=(claim.minX()-2)>>4;x<=(claim.maxX()+2)>>4;x++) for(int z=(claim.minZ()-2)>>4;z<=(claim.maxZ()+2)>>4;z++) keys.add(new ChunkKey(claim.dimension(),x,z));
            region=java.util.List.copyOf(keys);
        }
    }
    private final ColonyRegistry registry;
    private final ConstructionController controller;
    private final ChunkDemandManager chunks;
    private final BlockPlacementExecutor placement;
    private final java.util.function.Supplier<UUID> checkpoint;
    private final Map<UUID,Active> active=new HashMap<>();
    private final MinecraftConstructionSupply supply;
    private final io.github.kpuctajluk.colonyloom.minecraft.storage.StorageService storage;
    private NavigationService navigation;
    public MinecraftConstructionService(net.minecraft.server.MinecraftServer server,ColonyRegistry registry,ConstructionController controller,ChunkDemandManager chunks,BlockPlacementExecutor placement,java.util.function.Supplier<UUID> checkpoint,io.github.kpuctajluk.colonyloom.minecraft.storage.StorageService storage,io.github.kpuctajluk.colonyloom.minecraft.storage.StorageTransferExecutor transfer) {
        this.registry=registry; this.controller=controller; this.chunks=chunks; this.placement=placement; this.checkpoint=java.util.Objects.requireNonNull(checkpoint);
        this.storage=java.util.Objects.requireNonNull(storage);
        this.supply=new MinecraftConstructionSupply(server,registry,storage,transfer,placement);
    }
    public void navigation(NavigationService navigation) { registry.requireOwner(); if(this.navigation!=null) throw new IllegalStateException("Navigation already attached"); this.navigation=navigation; }
    public boolean current(WorkOrder work,NavigationService.Request request) {
        if(!WorkOrder.CONSTRUCTION.equals(work.typeId())) return NavigationService.moveGoalCurrent(work,request);
        var site=registry.construction().site(work.id()); var state=active.get(work.id());
        return site!=null && !site.closed() && retained(work,state) && state.generation==request.goalRevision()
                &&request.citizenId().equals(state.stepCitizen)&&request.epoch()==state.stepEpoch
                &&request.target().equals(state.waypoint);
    }
    private boolean owner(WorkOrder work,Active state,long continuation,UUID citizenId,long bindingEpoch) {
        if(state==null||citizenId==null||active.get(work.id())!=state||state.cancelling||state.continuation!=continuation
                ||registry.workBoard().work(work.id())!=work)return false;
        var citizen=registry.findCitizen(citizenId).orElse(null);
        return !work.terminal()&&citizenId.equals(work.assignee())&&citizen!=null&&citizen.bindingEpoch()==bindingEpoch
                &&work.id().equals(citizen.assignedWorkId())&&work.colonyId().equals(citizen.colonyId())
                &&citizen.lifecycle()==io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Lifecycle.ALIVE
                &&citizen.admission()==io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Admission.ACTIVE
                &&citizen.readiness()==io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Readiness.READY;
    }
    private boolean current(WorkOrder work,Active state,long continuation,long revision,UUID citizenId,long bindingEpoch) {
        return owner(work,state,continuation,citizenId,bindingEpoch)&&work.revision()==revision&&work.state()==WorkOrder.State.RUNNING;
    }
    private boolean retained(WorkOrder work,Active state) {
        return state!=null&&owner(work,state,state.stepContinuation,state.stepCitizen,state.stepEpoch)
                &&(current(work,state)||state.waitRevision==work.revision()&&work.state()==WorkOrder.State.WAITING
                        &&work.waitingReason()==state.waitReason&&work.stage().equals("construction"));
    }
    private boolean current(WorkOrder work,Active state) {
        return state!=null&&current(work,state,state.stepContinuation,state.stepRevision,state.stepCitizen,state.stepEpoch);
    }
    public void step(WorkOrder work,long tick) {
        registry.requireOwner();if(work.assignee()==null||work.state()!=WorkOrder.State.RUNNING)return; var site=registry.construction().site(work.id());
        if(site==null || site.closed()) { registry.workBoard().transition(work.id(),WorkOrder.State.FAILED,WorkOrder.Reason.CONTENT_UNAVAILABLE,"construction"); return; }
        var state=active.get(work.id());
        if(state==null) { state=new Active(controller.layout(site)); active.put(work.id(),state); }
        var citizen=registry.findCitizen(work.assignee()).orElse(null);
        if(citizen==null)return;
        if(state.cancelling||state.executing)return;
        state.executing=true;
        try {
        long continuation=state.continuation,revision=work.revision();
        state.stepContinuation=continuation;state.stepRevision=revision;state.stepCitizen=citizen.citizenId();state.stepEpoch=citizen.bindingEpoch();
        state.waitRevision=-1;
        if(!current(work,state))return;
        if(registry.targetClaims().state(work.id())==TargetClaimRegistry.State.CONFLICT) { waitFor(work,WorkOrder.Reason.TARGET_CONFLICT); return; }
        if(!registry.targetClaims().owns(work.id(),site.claimRevision())) { waitFor(work,WorkOrder.Reason.RECONCILING); return; }
        if(tick<state.retryAt) { waitFor(work,state.retryReason); return; }
        try { chunks.request(state.chunkOwner,work.colonyId(),state.region,ChunkDemandManager.Readiness.ENTITY_TICKING,work.lane(),work.priority(),false); }
        catch(AdmissionLedger.AdmissionException denied) { if(current(work,state))waitFor(work,WorkOrder.Reason.STATE_LIMIT); return; }
        if(!current(work,state)||!chunks.admitted(state.chunkOwner))return;
        if(!chunks.ready(state.chunkOwner)) { var reason=chunks.reason(state.chunkOwner); if(current(work,state))waitFor(work,reason==WorkOrder.Reason.NONE?WorkOrder.Reason.CHUNK_NOT_READY:reason); return; }
        chunks.useful(state.chunkOwner);
        if(!current(work,state)||!chunks.admitted(state.chunkOwner)||!chunks.ready(state.chunkOwner))return;
        // A saved cursor is only a hint: recheck already accepted targets in bounded portions.
        while(state.reconcileCursor<site.cursor()) {
            if(!current(work,state))return;
            if(!registry.budgets().tryConsume(Budget.BLUEPRINT_COMPARISONS,work.lane())) {if(current(work,state))waitFor(work,WorkOrder.Reason.BUDGET);return;}
            var oldTarget=state.layout.targets().get(state.reconcileCursor);
            boolean matched=placement.matches(oldTarget.position(),oldTarget.expected());
            if(!current(work,state))return;
            if(!matched) {controller.revisit(work.id(),state.reconcileCursor);return;}
            state.reconcileCursor++;
        }
        if(site.cursor()>=state.layout.targets().size()) {
            registry.workBoard().transition(work.id(),WorkOrder.State.COMPLETED,WorkOrder.Reason.NONE,"completed"); return;
        }
        try {if(!supply.analyze(work,site,state.layout,state.materials)) {if(current(work,state))waitFor(work,WorkOrder.Reason.BUDGET);return;}}
        catch(AdmissionLedger.AdmissionException full) {if(current(work,state))waitFor(work,WorkOrder.Reason.STATE_LIMIT);return;}
        if(!current(work,state))return;
        if(!registry.budgets().tryConsume(Budget.BLUEPRINT_COMPARISONS,work.lane())) { if(current(work,state))waitFor(work,WorkOrder.Reason.BUDGET); return; }
        var target=state.layout.targets().get(site.cursor());
        long comparisonStart=System.nanoTime(); boolean matches;
        try { matches=placement.matches(target.position(),target.expected()); }
        finally { registry.metrics().record(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer.BLUEPRINT_UNIT,System.nanoTime()-comparisonStart); }
        if(!current(work,state))return;
        if(matches) {controller.advance(work.id(),false);if(current(work,state))supply.confirmedAdvance(state.materials,site,target.expected().itemId());return;}
        io.github.kpuctajluk.colonyloom.core.supply.CoverageShare allocation;
        try {
            supply.requireMaterial(state.materials,site.cursor(),target.expected().itemId());
            allocation=supply.carried(work,state.materials,target.expected().itemId());
            if(!current(work,state))return;
            if(state.materials.carriedReason==WorkOrder.Reason.BUDGET) {waitFor(work,WorkOrder.Reason.BUDGET);return;}
        }
        catch(AdmissionLedger.AdmissionException full) {if(current(work,state))waitFor(work,WorkOrder.Reason.STATE_LIMIT);return;}
        if(allocation==null) {
            var buffered=supply.buffered(work,state.materials,target.expected().itemId());
            if(!current(work,state))return;
            if(buffered==null) {supply.retryCarried(state.materials);waitFor(work,WorkOrder.Reason.MATERIALS);return;}
            var buffer=storage.locate(buffered.slot().storage());
            if(!current(work,state))return;
            if(buffer==null) {waitFor(work,WorkOrder.Reason.RECONCILING);return;}
            var pickup=new WorldPosition(buffer.dimension(),buffer.x(),buffer.y(),buffer.z()+1);
            if(!pickup.equals(state.waypoint)) {navigation.cancel(work.id());if(!current(work,state))return;state.waypoint=pickup;state.generation++;state.lastCursor=-1;state.arrived=false;}
            try {var request=navigation.request(work.id(),work.colonyId(),citizen.citizenId(),citizen.bindingEpoch(),state.generation,pickup,work.lane(),work.priority());
                if(!current(work,state))return;
                if(request==null) {waitFor(work,WorkOrder.Reason.RECONCILING);return;}}
            catch(AdmissionLedger.AdmissionException full) {if(current(work,state))waitFor(work,WorkOrder.Reason.STATE_LIMIT);return;}
            if(!navigation.atTarget(work.id())) {if(navigation.state(work.id())==NavigationService.State.WAITING&&current(work,state))waitFor(work,navigation.reason(work.id()));return;}
            navigation.cancel(work.id());if(!current(work,state))return;
            try {var reason=supply.pickup(work,state.materials,buffered);if(reason!=WorkOrder.Reason.NONE&&current(work,state))waitFor(work,reason);}
            catch(AdmissionLedger.AdmissionException full) {if(current(work,state))waitFor(work,WorkOrder.Reason.STATE_LIMIT);}
            return;
        }
        if(state.lastCursor!=site.cursor()) {
            navigation.cancel(work.id());if(!current(work,state))return;
            state.lastCursor=site.cursor(); state.generation=site.cursor(); state.arrived=false;
            var first=state.layout.targets().getFirst().position(); var marker=state.layout.workOrigin();
            state.waypoint=new WorldPosition(marker.dimension(),marker.x()+target.position().x()-first.x(),marker.y()+target.position().y()-first.y(),marker.z()+target.position().z()-first.z());
        }
        if(!state.arrived) {
            try {var request=navigation.request(work.id(),work.colonyId(),citizen.citizenId(),citizen.bindingEpoch(),state.generation,state.waypoint,work.lane(),work.priority());
                if(!current(work,state))return;
                if(request==null) {waitFor(work,WorkOrder.Reason.RECONCILING);return;}}
            catch(AdmissionLedger.AdmissionException denied) {if(current(work,state))waitFor(work,WorkOrder.Reason.STATE_LIMIT);return;}
            if(!current(work,state))return;
            if(!navigation.atTarget(work.id())) {if(navigation.state(work.id())==NavigationService.State.WAITING&&current(work,state))waitFor(work,navigation.reason(work.id()));return;}
            state.arrived=true;navigation.cancel(work.id());if(!current(work,state))return;
        }
        if(!chunks.admitted(state.chunkOwner)||!chunks.ready(state.chunkOwner))return;
        if(!registry.targetClaims().owns(work.id(),site.claimRevision())) {if(current(work,state))waitFor(work,WorkOrder.Reason.RECONCILING);return;}
        if(!current(work,state))return;
        int before=placement.materialCount(citizen.citizenId(),citizen.bindingEpoch(),target.expected().itemId());
        if(!current(work,state)||!chunks.admitted(state.chunkOwner)||!chunks.ready(state.chunkOwner))return;
        if(before<1) { waitFor(work,before==0?WorkOrder.Reason.MATERIALS:WorkOrder.Reason.RECONCILING); return; }
        io.github.kpuctajluk.colonyloom.core.supply.SupplyRegistry.PreparedConsumption consumption;
        try {consumption=registry.supply().prepareConsumption(allocation.id(),1);}
        catch(AdmissionLedger.AdmissionException full) {if(current(work,state))waitFor(work,WorkOrder.Reason.STATE_LIMIT);return;}
        if(!current(work,state)) {consumption.close();return;}
        try(consumption) {
        var colony=registry.colony(work.colonyId());
        var context=new ActionContext(work.colonyId(),citizen.citizenId(),ActionContext.Kind.BLOCK_PLACE,target.position(),ActionContext.AuthorityMode.COLONY,site.initiatorId(),colony.authorityRevision());
        var effect=new EffectRecord(UUID.randomUUID(),work.colonyId(),work.id(),citizen.citizenId(),citizen.bindingEpoch(),ActionContext.Kind.BLOCK_PLACE,target.position(),MinecraftConstructionGeometry.state(target.expected()).toString(),target.expected().itemId(),before,before,EffectRecord.State.PREPARED,0,null,null,null,null);
        try { registry.effects().prepare(effect,work.lane()); } catch(AdmissionLedger.AdmissionException denied) { if(current(work,state))waitFor(work,WorkOrder.Reason.STATE_LIMIT); return; }
        boolean attempted=false;
        Throwable failure=null;
        try {
            if(!current(work,state)||!consumption.unchanged()||!chunks.admitted(state.chunkOwner)||!chunks.ready(state.chunkOwner)) {registry.effects().discardUnchanged(effect.operationId());return;}
            chunks.setProtection(state.chunkOwner,true,false,false);
            if(!current(work,state)||!consumption.unchanged()||!chunks.admitted(state.chunkOwner)||!chunks.ready(state.chunkOwner)) {registry.effects().discardUnchanged(effect.operationId());return;}
            long physicalStart=System.nanoTime();
            WorldAccess.Placement result;
            var captured=state;
            attempted=true;
            try {result=placement.place(context,citizen.bindingEpoch(),target.expected(),allocation.slot().slot(),
                    () -> current(work,captured)&&consumption.unchanged()&&chunks.admitted(captured.chunkOwner)&&chunks.ready(captured.chunkOwner)
                            &&registry.targetClaims().owns(work.id(),site.claimRevision())&&site.equals(registry.construction().site(work.id())));}
            finally {registry.metrics().record(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer.PHYSICAL_UNIT,System.nanoTime()-physicalStart);}
            if(result==WorldAccess.Placement.PLACED||result==WorldAccess.Placement.AMBIGUOUS) {
                int after=placement.materialCount(citizen.citizenId(),citizen.bindingEpoch(),target.expected().itemId());
                // Potentially changed native sides retain evidence even after the original embodiment is lost.
                if(result==WorldAccess.Placement.AMBIGUOUS||after!=before-1||!current(work,state)||!consumption.unchanged()
                        ||!registry.targetClaims().owns(work.id(),site.claimRevision())||!site.equals(registry.construction().site(work.id()))) {
                    ambiguous(work,effect,after<0?before:after);return;
                }
                registry.effects().update(effect.observed(after,false));
                placement.observe(BlockPlacementExecutor.FaultPoint.BEFORE_EFFECT_COMMIT,context);
                if(!current(work,state)||!consumption.unchanged()) {ambiguous(work,effect,after);return;}
                placement.observe(BlockPlacementExecutor.FaultPoint.AFTER_FACT_BEFORE_NOTIFY,context);
                if(!current(work,state)||!consumption.unchanged()||!registry.targetClaims().owns(work.id(),site.claimRevision())
                        ||!site.equals(registry.construction().site(work.id()))
                        ||placement.materialCount(citizen.citizenId(),citizen.bindingEpoch(),target.expected().itemId())!=after
                        ||!placement.matches(target.position(),target.expected())||!current(work,state)) {ambiguous(work,effect,after);return;}
                consumption.commit(1);
                if(!current(work,state)) {ambiguous(work,effect,after);return;}
                controller.advance(work.id(),true);
                if(!current(work,state))return;
                supply.confirmedAdvance(state.materials,site,target.expected().itemId());
                state.reconcileCursor=site.cursor()+1;
            } else {
                registry.effects().discardUnchanged(effect.operationId());
                if(!current(work,state))return;
                if(result==WorldAccess.Placement.ALREADY_PRESENT) {controller.advance(work.id(),false);if(current(work,state))supply.confirmedAdvance(state.materials,site,target.expected().itemId());}
                else waitFor(work,switch(result) { case MATERIALS -> WorkOrder.Reason.MATERIALS; case PERMISSION_DENIED -> WorkOrder.Reason.PERMISSION_DENIED; case OBSTRUCTED -> WorkOrder.Reason.TARGET_CONFLICT; default -> WorkOrder.Reason.CHUNK_NOT_READY; });
            }
        } catch(RuntimeException|Error thrown) {
            failure=thrown;
            if(attempted&&registry.effects().get(effect.operationId())!=null) {
                try {int after=placement.materialCount(citizen.citizenId(),citizen.bindingEpoch(),target.expected().itemId());ambiguous(work,effect,after<0?before:after);}
                catch(RuntimeException|Error cleanup) {if(cleanup!=thrown)thrown.addSuppressed(cleanup);}
            } else if(registry.effects().get(effect.operationId())!=null) {
                try {registry.effects().discardUnchanged(effect.operationId());} catch(RuntimeException|Error cleanup) {if(cleanup!=thrown)thrown.addSuppressed(cleanup);}
            }
            throw thrown;
        } finally {
            try {chunks.setProtection(state.chunkOwner,false,false,false);}
            catch(RuntimeException|Error cleanup) {
                if(failure!=null) {if(cleanup!=failure)failure.addSuppressed(cleanup);}
                else {
                    if(attempted&&registry.effects().get(effect.operationId())!=null) {
                        try {int after=placement.materialCount(citizen.citizenId(),citizen.bindingEpoch(),target.expected().itemId());ambiguous(work,effect,after<0?before:after);}
                        catch(RuntimeException|Error recovery) {if(recovery!=cleanup)cleanup.addSuppressed(recovery);}
                    }
                    throw cleanup;
                }
            }
        }
        }
        } finally {state.executing=false;}
    }
    private void ambiguous(WorkOrder work,EffectRecord effect,int after) {
        var saved=registry.effects().get(effect.operationId());
        if(saved.state()==EffectRecord.State.PREPARED)registry.effects().update(saved.observed(after,true));
        else if(saved.state()==EffectRecord.State.OBSERVED)registry.effects().update(saved.observed(saved.countAfter(),true));
        registry.effects().blockAmbiguous(work.colonyId(),checkpoint.get());
        Throwable failure=null;
        try {navigation.cancel(work.id());} catch(RuntimeException|Error thrown) {failure=thrown;}
        finally {
            try {waitFor(work,WorkOrder.Reason.RECOVERY_AMBIGUOUS);}
            catch(RuntimeException|Error thrown) {if(failure==null)failure=thrown;else if(thrown!=failure)failure.addSuppressed(thrown);}
        }
        if(failure instanceof RuntimeException thrown)throw thrown;
        if(failure instanceof Error thrown)throw thrown;
    }
    private void waitFor(WorkOrder work,WorkOrder.Reason reason) {
        var state=active.get(work.id());
        if(state==null||!current(work,state)||work.assignee()==null||work.terminal())return;
        if((reason==WorkOrder.Reason.MATERIALS || reason==WorkOrder.Reason.PERMISSION_DENIED || reason==WorkOrder.Reason.TARGET_CONFLICT)
                &&registry.budgets().tick()>=state.retryAt) {
            state.retryAt=registry.budgets().tick()+20; state.retryReason=reason;
        }
        state.waitRevision=state.stepRevision+1;
        state.waitReason=reason==WorkOrder.Reason.NONE?WorkOrder.Reason.RECONCILING:reason;
        registry.workBoard().waitAssigned(work.id(),reason==WorkOrder.Reason.NONE?WorkOrder.Reason.RECONCILING:reason,"construction");
        if(!owner(work,state,state.stepContinuation,state.stepCitizen,state.stepEpoch)
                ||work.state()!=WorkOrder.State.WAITING||work.revision()!=state.waitRevision
                ||work.waitingReason()!=state.waitReason||!work.stage().equals("construction"))state.waitRevision=-1;
    }
    public void cancel(UUID workId) {
        registry.requireOwner(); var state=active.get(workId);
        if(state!=null) {
            if(state.cancelling)return;
            state.cancelling=true;state.continuation++;state.waitRevision=-1;state.stepCitizen=null;
            state.waypoint=null;state.lastCursor=-1;state.arrived=false;state.generation++;
            state.retryAt=0;state.retryReason=WorkOrder.Reason.NONE;
        }
        Throwable failure=null;
        try {
            try {if(navigation!=null)navigation.cancel(workId);} catch(RuntimeException|Error thrown) {failure=thrown;}
            finally {
                try {if(state!=null)chunks.release(state.chunkOwner);}
                catch(RuntimeException|Error thrown) {if(failure==null)failure=thrown;else if(thrown!=failure)failure.addSuppressed(thrown);}
                finally {
                    var site=registry.construction().site(workId);
                    if(site==null) {if(active.get(workId)==state)active.remove(workId);}
                    else if(registry.workBoard().work(workId).terminal()) {
                        if(active.get(workId)==state)active.remove(workId);
                        try {supply.close(workId);} catch(RuntimeException|Error thrown) {if(failure==null)failure=thrown;else if(thrown!=failure)failure.addSuppressed(thrown);}
                        finally {try {controller.close(workId);} catch(RuntimeException|Error thrown) {if(failure==null)failure=thrown;else if(thrown!=failure)failure.addSuppressed(thrown);}}
                    } else if(state!=null)supply.suspend(state.materials);
                }
            }
        } finally {if(state!=null)state.cancelling=false;}
        if(failure instanceof RuntimeException thrown)throw thrown;
        if(failure instanceof Error thrown)throw thrown;
    }
    public void close() {
        registry.requireOwner();Throwable failure=null;
        for(var id:java.util.List.copyOf(active.keySet())) {
            try {cancel(id);} catch(RuntimeException|Error thrown) {if(failure==null)failure=thrown;else if(thrown!=failure)failure.addSuppressed(thrown);}
        }
        active.clear();
        if(failure instanceof RuntimeException thrown)throw thrown;
        if(failure instanceof Error thrown)throw thrown;
    }
}
