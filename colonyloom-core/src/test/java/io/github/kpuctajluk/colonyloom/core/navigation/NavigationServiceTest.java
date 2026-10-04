package io.github.kpuctajluk.colonyloom.core.navigation;

import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NavigationServiceTest {
    private static UUID id(long value) { return new UUID(0,value); }
    private static final class Access implements ChunkDemandManager.ChunkAccess {
        boolean available=true;
        int held;
        @Override public boolean acquire(UUID colony,ChunkKey center,ChunkDemandManager.Readiness readiness) { held++;return true; }
        @Override public void release(UUID colony,ChunkKey center,ChunkDemandManager.Readiness readiness) { held--; }
        @Override public boolean ready(ChunkKey key,ChunkDemandManager.Readiness readiness) { return available; }
    }
    private static final class Backend implements NavigationService.Backend {
        int searches, applies, stops, pendingPortions, routeNodes=5, queryCapacity=Integer.MAX_VALUE;
        boolean reachable=true, capacityAvailable=true;
        WorldPosition position=new WorldPosition("minecraft:overworld",8,64,8);
        NavigationService.Motion motion=NavigationService.Motion.MOVING;
        final Map<UUID,Integer> portions=new HashMap<>();
        final List<UUID> searchOrder=new ArrayList<>();
        Runnable duringSearch=() -> {};
        BooleanSupplier validity;
        @Override public WorldPosition position(NavigationService.Request request) { return position; }
        @Override public NavigationService.Route search(NavigationService.Request request,List<ChunkKey> admitted) {
            searches++;searchOrder.add(request.workId());
            NavigationService.Route result;
            if (!capacityAvailable || (!portions.containsKey(request.id()) && portions.size() >= queryCapacity)) result=NavigationService.CAPACITY_WAIT_ROUTE;
            else if (portions.merge(request.id(),1,Integer::sum)<=pendingPortions) result=NavigationService.PENDING_ROUTE;
            else { portions.remove(request.id()); result=reachable ? () -> routeNodes : null; }
            duringSearch.run();return result;
        }
        @Override public boolean apply(NavigationService.Request request,NavigationService.Route route,BooleanSupplier stillCurrent) { applies++;validity=stillCurrent;return true; }
        @Override public NavigationService.Motion poll(NavigationService.Request request) { return motion; }
        @Override public void stop(NavigationService.Request request) { stops++;portions.remove(request.id()); }
    }
    private static final class Fixture {
        final ColonyRegistry registry=new ColonyRegistry(() -> {});
        final GlobalWorkBudgets budgets=new GlobalWorkBudgets(SimulationLimits.development(),() -> 0);
        final Access access=new Access();
        final ChunkDemandManager chunks=new ChunkDemandManager(registry,budgets,access);
        final Backend backend=new Backend();
        final NavigationService navigation=new NavigationService(registry,budgets,chunks,backend);
        final UUID colony=id(1),citizen=id(2),work=id(3);
        final WorldPosition target=new WorldPosition("minecraft:overworld",12,64,8);
        long tick;
        Fixture() {
            registry.addColony(new ColonyRuntime(colony,"Colony",new Territory("minecraft:overworld",0,0,31,31),id(99),Map.of(),0,0,false,null,false));
            addMovement(citizen,id(102),work,Lane.NORMAL,0);
        }
        void step() { budgets.beginTick(++tick);chunks.tick(tick);navigation.tick(tick); }
        void until(long deadline) { while(tick<deadline) step(); }
        UUID addRequest(int ordinal,Lane lane,int priority) {
            UUID work=id(3000+ordinal);
            addMovement(id(1000+ordinal),id(2000+ordinal),work,lane,priority);return work;
        }
        private void addMovement(UUID citizen,UUID entity,UUID work,Lane lane,int priority) {
            registry.addCitizen(new CitizenRecord(citizen,colony,entity,1,null,null,null,null,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,
                    CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.READY,0,Map.of(),new WorldPosition("minecraft:overworld",8,64,8),0), proposed -> {});
            registry.bindings().observe(citizen,entity,1);
            registry.workBoard().createTimer(work,colony,target,null,priority,lane,1000);
            registry.workBoard().transition(work,WorkOrder.State.READY,WorkOrder.Reason.NONE,"movement");
            assertTrue(registry.workBoard().assign(work,citizen));
            navigation.request(work,colony,citizen,1,0,target,lane,priority);
        }
    }
    @Test void displacedResidentRebuildsUnavailableRouteDomainWithoutLosingGoal() {
        Fixture f=new Fixture();f.step();
        f.backend.position=new WorldPosition("minecraft:overworld",8,64,1);
        f.backend.motion=NavigationService.Motion.UNAVAILABLE;f.step();
        f.backend.motion=NavigationService.Motion.MOVING;
        for(int tick=0;tick<20 && f.navigation.state(f.work)!=NavigationService.State.MOVING;tick++) f.step();
        assertEquals(NavigationService.State.MOVING,f.navigation.state(f.work));
        assertTrue(f.chunks.ready(f.work));
        assertTrue(f.chunks.admitted(new ChunkKey("minecraft:overworld",0,-1)));
        assertEquals(f.target,f.registry.workBoard().work(f.work).target());
        f.navigation.cancel(f.work);assertEquals(0,f.access.held);
    }
    @Test void protectedDomainSaturationWaitsWithoutDeclaringRouteImpossible() {
        Fixture f=new Fixture();f.navigation.cancel(f.work);
        var limits=SimulationLimits.development().withResource(Resource.LOADED_FOOTPRINT,64)
                .withResource(Resource.BLOCK_TICKING,24).withResource(Resource.ENTITY_TICKING,4);
        f.registry.admission().updateLimits(limits);f.budgets.updateLimits(limits);f.chunks.limitsUpdated();
        UUID protectedOwner=id(4000),idleOwner=id(4001);
        f.chunks.request(protectedOwner,f.colony,List.of(new ChunkKey("minecraft:overworld",10,0)),ChunkDemandManager.Readiness.ENTITY_TICKING,Lane.NORMAL,0,false);
        f.chunks.request(idleOwner,f.colony,List.of(new ChunkKey("minecraft:overworld",20,0)),ChunkDemandManager.Readiness.ENTITY_TICKING,Lane.NORMAL,0,false);
        f.until(5);assertTrue(f.chunks.ready(protectedOwner));assertTrue(f.chunks.ready(idleOwner));
        f.chunks.setProtection(protectedOwner,true,false,false);f.chunks.setProtection(idleOwner,true,false,false);
        f.navigation.request(f.work,f.colony,f.citizen,1,0,f.target,Lane.NORMAL,0);
        f.until(20);assertEquals(0,f.backend.searches);
        assertEquals(NavigationService.State.WAITING,f.navigation.state(f.work));
        assertEquals(WorkOrder.Reason.CHUNK_NOT_READY,f.navigation.reason(f.work));
        f.chunks.setProtection(idleOwner,false,false,false);
        f.until(40);assertEquals(NavigationService.State.MOVING,f.navigation.state(f.work));
        assertTrue(f.chunks.ready(protectedOwner));assertFalse(f.chunks.admitted(idleOwner));
        assertTrue(f.chunks.footprint()<=64);assertEquals(1,f.backend.applies);
    }
    @Test void missingReadinessNeverSearchesEvenIfTicketWasAccepted() {
        Fixture f=new Fixture();f.access.available=false;f.until(100);
        assertEquals(0,f.backend.searches);assertEquals(0,f.backend.applies);
        assertEquals(WorkOrder.Reason.CHUNK_NOT_READY,f.navigation.reason(f.work));
        f.access.available=true;f.step();assertEquals(1,f.backend.searches);
    }
    @Test void cancellingRunningRequestStopsMovementAndReleasesOnlyItsDemand() {
        Fixture f=new Fixture();f.step();assertEquals(NavigationService.State.MOVING,f.navigation.state(f.work));
        f.navigation.cancel(f.work);
        assertEquals(0,f.access.held);assertFalse(f.chunks.admitted(f.work));
        assertEquals(NavigationService.State.CANCELLED,f.navigation.state(f.work));
        int searches=f.backend.searches;f.until(250);assertEquals(searches,f.backend.searches);assertTrue(f.backend.stops>0);
    }
    @Test void cancellationDuringSearchCannotApplyOldResult() {
        Fixture f=new Fixture();f.backend.duringSearch=() -> f.navigation.cancel(f.work);f.step();
        assertEquals(1,f.backend.searches);assertEquals(0,f.backend.applies);assertFalse(f.navigation.atTarget(f.work));assertEquals(0,f.access.held);
    }
    @Test void changedChunkDuringSearchRejectsResultAndNextPortionReplans() {
        Fixture f=new Fixture();ChunkKey key=new ChunkKey("minecraft:overworld",0,0);
        f.backend.duringSearch=() -> f.navigation.invalidate(key);f.step();assertEquals(0,f.backend.applies);
        f.backend.duringSearch=() -> {};f.step();assertEquals(2,f.backend.searches);assertEquals(1,f.backend.applies);
    }
    @Test void unreachableBackoffHasExactDelaysAndRelevantInvalidationClearsIt() {
        Fixture f=new Fixture();f.backend.reachable=false;f.step();assertEquals(1,f.backend.searches);
        f.until(20);assertEquals(1,f.backend.searches);f.step();assertEquals(2,f.backend.searches);
        f.until(60);assertEquals(2,f.backend.searches);f.step();assertEquals(3,f.backend.searches);
        f.navigation.invalidate(new ChunkKey("minecraft:overworld",20,20));f.step();assertEquals(3,f.backend.searches);
        f.navigation.invalidate(new ChunkKey("minecraft:overworld",0,0));f.backend.reachable=true;f.step();
        assertEquals(4,f.backend.searches);assertEquals(NavigationService.State.MOVING,f.navigation.state(f.work));
        assertFalse(f.navigation.atTarget(f.work));
    }
    @Test void retriesDoubleThroughTheCapWithoutTickSpin() {
        Fixture f=new Fixture();f.backend.reachable=false;
        long[] attempts={1,21,61,141,301,501,701};
        for (int i=0;i<attempts.length;i++) {
            f.until(attempts[i]-1); assertEquals(i,f.backend.searches);
            f.step(); assertEquals(i+1,f.backend.searches);
        }
        assertEquals(0,f.backend.applies);
    }
    @Test void epochChangeDuringSearchRejectsOldEmbodiment() {
        Fixture f=new Fixture();f.backend.duringSearch=() -> f.registry.updateCitizen(f.registry.citizen(f.citizen).withBinding(id(103),2));f.step();
        assertEquals(0,f.backend.applies);assertFalse(f.navigation.atTarget(f.work));f.step();assertEquals(NavigationService.State.CANCELLED,f.navigation.state(f.work));
    }
    @Test void physicalSafetyPredicateRejectsInvalidationBeforeMaintenancePoll() {
        Fixture f=new Fixture(); f.step(); assertTrue(f.backend.validity.getAsBoolean());
        f.navigation.invalidate(new ChunkKey("minecraft:overworld",0,0));
        assertFalse(f.backend.validity.getAsBoolean());
    }
    @Test void noWorkingSetAdmissionNeverCallsTheBackend() {
        Fixture f=new Fixture();
        f.navigation.cancel(f.work);
        f.registry.admission().updateLimits(SimulationLimits.development().withResource(Resource.LOADED_FOOTPRINT,8));
        f.navigation.request(f.work,f.colony,f.citizen,1,0,f.target,Lane.NORMAL,0);
        f.until(100); assertEquals(0,f.backend.searches); assertEquals(WorkOrder.Reason.WORKING_SET_LIMIT,f.navigation.reason(f.work));
    }
    @Test void changingGoalGenerationInsideSearchRejectsOldRoute() {
        Fixture f=new Fixture();
        f.backend.duringSearch=() -> {
            f.backend.duringSearch=() -> {};
            f.navigation.request(f.work,f.colony,f.citizen,1,1,f.target,Lane.NORMAL,0);
        };
        f.step(); assertEquals(0,f.backend.applies);
        f.step(); assertEquals(1,f.backend.applies);
    }
    @Test void unfinishedSearchUsesSubsequentTicksWithoutApplyingOrBackoff() {
        Fixture f=new Fixture();f.backend.pendingPortions=3;
        f.budgets.updateLimits(SimulationLimits.development().withBudget(Budget.NAVIGATION_STARTS,8));
        // Query nodes do not compete with the ordinary cache needed for a small finished route.
        f.registry.admission().updateLimits(SimulationLimits.development().withResource(Resource.CACHE_ENTRIES,16));
        for (int portion=1;portion<=3;portion++) {
            f.step();assertEquals(portion,f.backend.searches);assertEquals(0,f.backend.applies);
            assertEquals(NavigationService.State.WAITING,f.navigation.state(f.work));
            assertEquals(WorkOrder.Reason.BUDGET,f.navigation.reason(f.work));
            f.navigation.tick(f.tick);assertEquals(portion,f.backend.searches);
        }
        f.step();assertEquals(4,f.backend.searches);assertEquals(1,f.backend.applies);
        assertEquals(NavigationService.State.MOVING,f.navigation.state(f.work));
        assertEquals(0,f.backend.stops);assertEquals(0L,f.navigation.diagnostics().get("failedSearches"));
        assertEquals(4,f.registry.metrics().snapshot().get("NAVIGATION_EXTERNAL").count());
        f.navigation.close();assertEquals(0,f.registry.admission().used(Resource.CACHE_ENTRIES));assertEquals(0,f.access.held);
    }
    @Test void unavailableQueryCapacityRemainsPendingAndResumesWithoutNegativeCache() {
        Fixture f=new Fixture();f.backend.capacityAvailable=false;f.until(25);
        assertEquals(25,f.backend.searches);assertEquals(0,f.backend.applies);
        assertEquals(WorkOrder.Reason.BUDGET,f.navigation.reason(f.work));
        f.backend.capacityAvailable=true;f.step();
        assertEquals(26,f.backend.searches);assertEquals(1,f.backend.applies);
        assertEquals(NavigationService.State.MOVING,f.navigation.state(f.work));
    }
    @Test void pendingThenUnreachableStartsBackoffOnlyAtTheTerminalPortion() {
        Fixture f=new Fixture();f.backend.pendingPortions=2;f.backend.reachable=false;
        f.until(2);assertEquals(2,f.backend.searches);assertEquals(WorkOrder.Reason.BUDGET,f.navigation.reason(f.work));
        f.step();assertEquals(3,f.backend.searches);assertEquals(WorkOrder.Reason.UNREACHABLE,f.navigation.reason(f.work));
        f.until(22);assertEquals(3,f.backend.searches);
        f.step();assertEquals(4,f.backend.searches);assertEquals(WorkOrder.Reason.BUDGET,f.navigation.reason(f.work));
        assertEquals(0,f.backend.applies);
    }
    @Test void cancellingPendingSearchReleasesQueryCacheAndChunkDemand() {
        Fixture f=new Fixture();f.backend.pendingPortions=2;f.step();
        f.navigation.cancel(f.work);f.until(50);
        assertEquals(1,f.backend.searches);assertEquals(0,f.backend.applies);assertEquals(1,f.backend.stops);
        assertTrue(f.backend.portions.isEmpty());assertEquals(0,f.access.held);
        assertEquals(0,f.registry.admission().used(Resource.CACHE_ENTRIES));
        assertEquals(NavigationService.State.CANCELLED,f.navigation.state(f.work));
    }
    @Test void terminalWorkRetirementReleasesPendingSearchOnMaintenance() {
        Fixture f=new Fixture();f.backend.pendingPortions=2;f.step();
        f.registry.workBoard().cancel(f.work);f.registry.workBoard().retire(f.work);f.step();
        assertEquals(1,f.backend.searches);assertEquals(0,f.backend.applies);assertTrue(f.backend.portions.isEmpty());
        assertEquals(NavigationService.State.CANCELLED,f.navigation.state(f.work));assertEquals(0,f.access.held);
        assertEquals(0,f.registry.admission().used(Resource.CACHE_ENTRIES));
    }
    @Test void epochChangeBetweenPortionsCancelsPendingEmbodiment() {
        Fixture f=new Fixture();f.backend.pendingPortions=2;f.step();
        f.registry.updateCitizen(f.registry.citizen(f.citizen).withBinding(id(103),2));f.step();
        assertEquals(1,f.backend.searches);assertEquals(0,f.backend.applies);assertTrue(f.backend.portions.isEmpty());
        assertEquals(NavigationService.State.CANCELLED,f.navigation.state(f.work));assertEquals(0,f.access.held);
    }
    @Test void newGoalGenerationCancelsOldPendingQueryRatherThanFinishingIt() {
        Fixture f=new Fixture();f.backend.pendingPortions=1;f.step();
        f.navigation.request(f.work,f.colony,f.citizen,1,1,f.target,Lane.NORMAL,0);f.step();
        assertEquals(2,f.backend.searches);assertEquals(0,f.backend.applies);assertEquals(1,f.backend.stops);
        f.step();assertEquals(1,f.backend.applies);assertEquals(NavigationService.State.MOVING,f.navigation.state(f.work));
    }
    @Test void chunkRevisionBetweenPortionsStopsAndRestartsQueryBeforeAnotherExpansion() {
        Fixture f=new Fixture();f.backend.pendingPortions=1;f.step();
        f.navigation.invalidate(new ChunkKey("minecraft:overworld",0,0));f.step();
        assertEquals(2,f.backend.searches);assertEquals(0,f.backend.applies);assertEquals(1,f.backend.stops);
        f.step();assertEquals(3,f.backend.searches);assertEquals(1,f.backend.applies);
    }
    @Test void queuedRevisionChangeIsCheckedEvenWhenMaintenanceBudgetIsExhausted() {
        Fixture f=new Fixture();f.backend.pendingPortions=1;f.step();
        f.navigation.invalidate(new ChunkKey("minecraft:overworld",0,0));
        f.budgets.beginTick(++f.tick);
        while (f.budgets.tryConsume(Budget.DIRTY_RESCAN_OBJECTS,Lane.SERVICE)) {}
        f.navigation.tick(f.tick);
        assertEquals(2,f.backend.searches);assertEquals(0,f.backend.applies);assertEquals(1,f.backend.stops);
        f.step();assertEquals(1,f.backend.applies);
    }
    @Test void lostReadinessReleasesPendingQueryAndRestartsAfterReadinessReturns() {
        Fixture f=new Fixture();f.backend.pendingPortions=1;f.step();
        f.access.available=false;f.step();assertEquals(1,f.backend.searches);assertTrue(f.backend.portions.isEmpty());
        assertEquals(WorkOrder.Reason.CHUNK_NOT_READY,f.navigation.reason(f.work));
        f.access.available=true;f.step();assertEquals(2,f.backend.searches);assertEquals(0,f.backend.applies);
        f.step();assertEquals(1,f.backend.applies);
    }
    @Test void equalPriorityPendingRequestsRotateBeyondSixteenAndRespectGlobalQuota() {
        Fixture f=new Fixture();f.backend.pendingPortions=3;
        List<UUID> works=new ArrayList<>();works.add(f.work);
        for (int i=1;i<20;i++) works.add(f.addRequest(i,Lane.NORMAL,0));
        for (int tick=1;tick<=40;tick++) {
            int before=f.backend.searches;f.step();
            assertEquals(2,f.backend.searches-before);
            List<UUID> portion=f.backend.searchOrder.subList(before,f.backend.searches);
            assertNotEquals(portion.get(0),portion.get(1));
            assertEquals(2,f.budgets.used(Budget.NAVIGATION_STARTS));
            if (tick==12) for (UUID work:works) assertTrue(f.backend.searchOrder.contains(work));
        }
        for (UUID work:works) assertEquals(NavigationService.State.MOVING,f.navigation.state(work));
        assertEquals(20,f.backend.applies);
    }
    @Test void priorityStillOrdersPendingPeersAndAllLanesReceiveTheirBudgetShare() {
        Fixture f=new Fixture();f.backend.capacityAvailable=false;
        UUID high=f.addRequest(1,Lane.NORMAL,10),critical=f.addRequest(2,Lane.CRITICAL,0),service=f.addRequest(3,Lane.SERVICE,0);
        f.budgets.updateLimits(SimulationLimits.development().withBudget(Budget.NAVIGATION_STARTS,1));
        long lowTick=-1,highTick=-1,criticalTick=-1,serviceTick=-1;
        for (int tick=1;tick<=40;tick++) {
            int before=f.backend.searches;f.step();assertEquals(1,f.backend.searches-before);
            UUID searched=f.backend.searchOrder.get(before);
            if (searched.equals(f.work) && lowTick<0) lowTick=f.tick;
            if (searched.equals(high) && highTick<0) highTick=f.tick;
            if (searched.equals(critical) && criticalTick<0) criticalTick=f.tick;
            if (searched.equals(service) && serviceTick<0) serviceTick=f.tick;
        }
        assertTrue(highTick>0);assertTrue(lowTick>highTick);assertTrue(criticalTick>0);assertTrue(serviceTick>0);
        f.navigation.cancel(high);f.until(50);
        assertTrue(f.backend.searchOrder.contains(f.work));assertEquals(0,f.backend.applies);
    }
    @Test void higherPriorityCapacityWaitersCannotDeadlockOccupiedQueryPool() {
        Fixture f=new Fixture();f.backend.queryCapacity=1;f.backend.pendingPortions=4;
        f.budgets.updateLimits(SimulationLimits.development().withBudget(Budget.NAVIGATION_STARTS,1));
        f.step();assertEquals(1,f.backend.portions.size());
        UUID high=f.addRequest(1,Lane.NORMAL,10);
        f.until(30);
        assertEquals(NavigationService.State.MOVING,f.navigation.state(f.work));
        assertEquals(NavigationService.State.MOVING,f.navigation.state(high));
        assertEquals(2,f.backend.applies);assertTrue(f.backend.portions.isEmpty());
        assertTrue(f.budgets.used(Budget.NAVIGATION_STARTS)<=1);
    }
}
