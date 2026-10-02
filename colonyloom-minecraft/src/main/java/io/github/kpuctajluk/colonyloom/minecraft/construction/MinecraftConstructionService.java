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
        long generation;
        int lastCursor=-1;
        boolean arrived;
        long retryAt;
        WorkOrder.Reason retryReason=WorkOrder.Reason.NONE;
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
    private NavigationService navigation;
    public MinecraftConstructionService(ColonyRegistry registry,ConstructionController controller,ChunkDemandManager chunks,BlockPlacementExecutor placement,java.util.function.Supplier<UUID> checkpoint) {
        this.registry=registry; this.controller=controller; this.chunks=chunks; this.placement=placement; this.checkpoint=java.util.Objects.requireNonNull(checkpoint);
    }
    public void navigation(NavigationService navigation) { registry.requireOwner(); if(this.navigation!=null) throw new IllegalStateException("Navigation already attached"); this.navigation=navigation; }
    public boolean current(WorkOrder work,NavigationService.Request request) {
        if(!WorkOrder.CONSTRUCTION.equals(work.typeId())) return NavigationService.moveGoalCurrent(work,request);
        var site=registry.construction().site(work.id()); var state=active.get(work.id());
        return site!=null && !site.closed() && state!=null && state.generation==request.goalRevision() && request.target().equals(state.waypoint);
    }
    public void step(WorkOrder work,long tick) {
        registry.requireOwner(); var site=registry.construction().site(work.id());
        if(site==null || site.closed()) { registry.workBoard().transition(work.id(),WorkOrder.State.FAILED,WorkOrder.Reason.CONTENT_UNAVAILABLE,"construction"); return; }
        if(registry.targetClaims().state(work.id())==TargetClaimRegistry.State.CONFLICT) { waitFor(work,WorkOrder.Reason.TARGET_CONFLICT); return; }
        if(!registry.targetClaims().owns(work.id(),site.claimRevision())) { waitFor(work,WorkOrder.Reason.RECONCILING); return; }
        var state=active.get(work.id());
        if(state==null) { state=new Active(controller.layout(site)); active.put(work.id(),state); }
        if(tick<state.retryAt) { registry.workBoard().waitAssigned(work.id(),state.retryReason,"construction"); return; }
        try { chunks.request(state.chunkOwner,work.colonyId(),state.region,ChunkDemandManager.Readiness.ENTITY_TICKING,work.lane(),work.priority(),false); }
        catch(AdmissionLedger.AdmissionException denied) { waitFor(work,WorkOrder.Reason.STATE_LIMIT); return; }
        if(!chunks.ready(state.chunkOwner)) { var reason=chunks.reason(state.chunkOwner); waitFor(work,reason==WorkOrder.Reason.NONE?WorkOrder.Reason.CHUNK_NOT_READY:reason); return; }
        chunks.useful(state.chunkOwner);
        if(site.cursor()>=state.layout.targets().length) {
            registry.workBoard().transition(work.id(),WorkOrder.State.COMPLETED,WorkOrder.Reason.NONE,"completed"); return;
        }
        var target=state.layout.targets()[site.cursor()];
        if(!registry.budgets().tryConsume(Budget.BLUEPRINT_COMPARISONS,work.lane())) { waitFor(work,WorkOrder.Reason.BUDGET); return; }
        if(placement.matches(target.position(),target.expected())) { controller.advance(work.id(),false); return; }
        var citizen=registry.citizen(work.assignee());
        if(state.lastCursor!=site.cursor()) {
            navigation.cancel(work.id()); state.lastCursor=site.cursor(); state.generation=site.cursor(); state.arrived=false;
            // Work origin travels parallel to the template's target axis, keeping the body off the blocks it places.
            var first=state.layout.targets()[0].position(); var marker=state.layout.workOrigin();
            state.waypoint=new WorldPosition(marker.dimension(),marker.x()+target.position().x()-first.x(),marker.y()+target.position().y()-first.y(),marker.z()+target.position().z()-first.z());
        }
        if(!state.arrived) {
            try { if(navigation.request(work.id(),work.colonyId(),citizen.citizenId(),citizen.bindingEpoch(),state.generation,state.waypoint,work.lane(),work.priority())==null) { waitFor(work,WorkOrder.Reason.RECONCILING); return; } }
            catch(AdmissionLedger.AdmissionException denied) { waitFor(work,WorkOrder.Reason.STATE_LIMIT); return; }
            if(!navigation.atTarget(work.id())) { if(navigation.state(work.id())==NavigationService.State.WAITING) waitFor(work,navigation.reason(work.id())); return; }
            state.arrived=true; navigation.cancel(work.id());
        }
        if(!registry.targetClaims().owns(work.id(),site.claimRevision()) || !chunks.ready(state.chunkOwner)) { waitFor(work,WorkOrder.Reason.RECONCILING); return; }
        int before=placement.materialCount(citizen.citizenId(),citizen.bindingEpoch(),target.expected().itemId());
        if(before<0) { waitFor(work,WorkOrder.Reason.RECONCILING); return; }
        if(before==0) { waitFor(work,WorkOrder.Reason.MATERIALS); return; }
        var colony=registry.colony(work.colonyId());
        var context=new ActionContext(work.colonyId(),citizen.citizenId(),ActionContext.Kind.BLOCK_PLACE,target.position(),ActionContext.AuthorityMode.COLONY,site.initiatorId(),colony.authorityRevision());
        var effect=new EffectRecord(UUID.randomUUID(),work.colonyId(),work.id(),citizen.citizenId(),citizen.bindingEpoch(),ActionContext.Kind.BLOCK_PLACE,target.position(),MinecraftConstructionGeometry.state(target.expected()).toString(),target.expected().itemId(),before,before,EffectRecord.State.PREPARED,0);
        try { registry.effects().prepare(effect,work.lane()); } catch(AdmissionLedger.AdmissionException denied) { waitFor(work,WorkOrder.Reason.STATE_LIMIT); return; }
        // The verified session-dirty marker is durable already; this evidence joins ordinary SavedData checkpoints.
        // No observer saves implicitly, so fault fixtures can deliberately persist only one physical side.
        chunks.setProtection(state.chunkOwner,true,false,false);
        WorldAccess.Placement result;
        try { result=placement.place(context,citizen.bindingEpoch(),target.expected()); }
        catch(RuntimeException failure) { ambiguous(work,effect,before); throw failure; }
        finally { chunks.setProtection(state.chunkOwner,false,false,false); }
        if(result==WorldAccess.Placement.PLACED || result==WorldAccess.Placement.AMBIGUOUS) {
            int after=placement.materialCount(citizen.citizenId(),citizen.bindingEpoch(),target.expected().itemId());
            if(after<0) after=before;
            placement.observe(BlockPlacementExecutor.FaultPoint.BEFORE_EFFECT_COMMIT,context);
            if(result==WorldAccess.Placement.AMBIGUOUS || after!=before-1 || work.terminal()
                    || !citizen.citizenId().equals(work.assignee()) || !site.equals(registry.construction().site(work.id()))) { ambiguous(work,effect,after); return; }
            registry.effects().update(effect.observed(after,false)); controller.advance(work.id(),true);
        } else {
            registry.effects().discardUnchanged(effect.operationId());
            if(result==WorldAccess.Placement.ALREADY_PRESENT) controller.advance(work.id(),false);
            else waitFor(work,switch(result) { case MATERIALS -> WorkOrder.Reason.MATERIALS; case PERMISSION_DENIED -> WorkOrder.Reason.PERMISSION_DENIED; case OBSTRUCTED -> WorkOrder.Reason.TARGET_CONFLICT; default -> WorkOrder.Reason.CHUNK_NOT_READY; });
        }
    }
    private void ambiguous(WorkOrder work,EffectRecord effect,int after) {
        registry.effects().update(effect.observed(after,true)); registry.effects().blockAmbiguous(work.colonyId(),checkpoint.get()); navigation.cancel(work.id());
        if(!work.terminal() && work.assignee()!=null) waitFor(work,WorkOrder.Reason.RECOVERY_AMBIGUOUS);
    }
    private void waitFor(WorkOrder work,WorkOrder.Reason reason) {
        var state=active.get(work.id());
        if(state!=null && (reason==WorkOrder.Reason.MATERIALS || reason==WorkOrder.Reason.PERMISSION_DENIED || reason==WorkOrder.Reason.TARGET_CONFLICT)) {
            state.retryAt=registry.budgets().tick()+20; state.retryReason=reason;
        }
        registry.workBoard().waitAssigned(work.id(),reason==WorkOrder.Reason.NONE?WorkOrder.Reason.RECONCILING:reason,"construction");
    }
    public void cancel(UUID workId) {
        registry.requireOwner(); if(navigation!=null) navigation.cancel(workId); var state=active.remove(workId); if(state!=null) chunks.release(state.chunkOwner);
        if(registry.construction().site(workId)!=null) { var work=registry.workBoard().work(workId); if(work.terminal()) controller.close(workId); }
    }
    public void close() { registry.requireOwner(); for(var state:active.values()) chunks.release(state.chunkOwner); active.clear(); }
}
