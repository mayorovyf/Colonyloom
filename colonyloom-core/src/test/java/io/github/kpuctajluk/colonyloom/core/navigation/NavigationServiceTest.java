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
        boolean reachable=true, capacityAvailable=true, exhausted, unavailable, applyAccepted=true;
        WorldPosition position=new WorldPosition("minecraft:overworld",8,64,8);
        NavigationService.Motion motion=NavigationService.Motion.MOVING;
        final Map<UUID,Integer> portions=new HashMap<>();
        final List<UUID> searchOrder=new ArrayList<>();
        final Map<UUID,NavigationService.Request> movement=new HashMap<>();
        Runnable duringSearch=() -> {}, duringApply=() -> {}, duringPosition=() -> {}, duringStop=() -> {};
        BooleanSupplier validity;
        @Override public WorldPosition position(NavigationService.Request request) { duringPosition.run();return position; }
        @Override public NavigationService.SearchResult search(NavigationService.Request request,List<ChunkKey> admitted) {
            searches++;searchOrder.add(request.workId());
            NavigationService.SearchResult result;
            if (!capacityAvailable || (!portions.containsKey(request.id()) && portions.size() >= queryCapacity)) result=NavigationService.SearchOutcome.CAPACITY_WAIT;
            else if (portions.merge(request.id(),1,Integer::sum)<=pendingPortions) result=NavigationService.SearchOutcome.PENDING;
            else {
                portions.remove(request.id());
                result=exhausted ? NavigationService.SearchOutcome.EXHAUSTED : unavailable ? NavigationService.SearchOutcome.UNAVAILABLE
                        : reachable ? (NavigationService.Route)() -> routeNodes : NavigationService.SearchOutcome.UNREACHABLE;
            }
            duringSearch.run();return result;
        }
        @Override public boolean apply(NavigationService.Request request,NavigationService.Route route,BooleanSupplier stillCurrent) {
            applies++;validity=stillCurrent;
            if(applyAccepted)movement.put(request.citizenId(),request);
            duringApply.run();return applyAccepted;
        }
        @Override public NavigationService.Motion poll(NavigationService.Request request) { return motion; }
        @Override public void stop(NavigationService.Request request) {
            stops++;portions.remove(request.id());
            NavigationService.Request moving=movement.get(request.citizenId());
            if(moving!=null && moving.id().equals(request.id()))movement.remove(request.citizenId());
            duringStop.run();
        }
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
        final boolean physicalMoves;
        Fixture() { this(false); }
        Fixture(boolean physicalMoves) {
            this.physicalMoves=physicalMoves;
            registry.addColony(new ColonyRuntime(colony,"Colony",new Territory("minecraft:overworld",0,0,31,31),id(99),Map.of(),0,0,false,null,false));
            addMovement(citizen,id(102),work,Lane.NORMAL,0);
        }
        void step() { budgets.beginTick(++tick);chunks.tick(tick);navigation.tick(tick); }
        void until(long deadline) { while(tick<deadline) step(); }
        NavigationService.Request request(long ordinal,long generation) {
            return new NavigationService.Request(id(9000+ordinal),work,colony,citizen,1,generation,target,Lane.NORMAL,0);
        }
        Map<Resource,Integer> navigationUsage() {
            Map<Resource,Integer> usage=new HashMap<>();
            for(Resource resource:List.of(Resource.CACHE_ENTRIES,Resource.CHUNK_DEMANDS,Resource.LOADED_FOOTPRINT,
                    Resource.BLOCK_TICKING,Resource.ENTITY_TICKING))usage.put(resource,registry.admission().used(resource));
            return usage;
        }
        void assertReleased() {
            assertEquals(NavigationService.State.CANCELLED,navigation.state(work));
            assertFalse(chunks.admitted(work));assertEquals(0,access.held);
            assertTrue(backend.portions.isEmpty());assertTrue(backend.movement.isEmpty());
            navigationUsage().forEach((resource,used) -> assertEquals(0,used.intValue(),resource.name()));
        }
        UUID addRequest(int ordinal,Lane lane,int priority) {
            UUID work=id(3000+ordinal);
            addMovement(id(1000+ordinal),id(2000+ordinal),work,lane,priority);return work;
        }
        private void addMovement(UUID citizen,UUID entity,UUID work,Lane lane,int priority) {
            registry.addCitizen(new CitizenRecord(citizen,colony,entity,1,null,null,null,null,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,
                    CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.READY,0,Map.of(),new WorldPosition("minecraft:overworld",8,64,8),0), proposed -> {});
            registry.bindings().observe(citizen,entity,1);
            if(physicalMoves)registry.workBoard().createMove(work,colony,target,priority,lane);
            else registry.workBoard().createTimer(work,colony,target,null,priority,lane,1000);
            registry.workBoard().transition(work,WorkOrder.State.READY,WorkOrder.Reason.NONE,"movement");
            assertTrue(registry.workBoard().assign(work,citizen));
            navigation.request(work,colony,citizen,1,0,target,lane,priority);
        }
    }
    @Test void readyRouteDomainSurvivesCoveredResidentDisplacementWhileWaiting() {
        Fixture f=new Fixture(true);
        f.navigation.cancel(f.work);
        f.backend.position=new WorldPosition("minecraft:overworld",34,64,8);
        UUID original=f.navigation.request(f.work,f.colony,f.citizen,1,0,f.target,Lane.NORMAL,0);
        for(int tick=0;tick<20&&f.navigation.state(f.work)!=NavigationService.State.MOVING;tick++)f.step();
        assertEquals(NavigationService.State.MOVING,f.navigation.state(f.work));
        f.access.available=false;f.step();
        assertEquals(NavigationService.State.WAITING,f.navigation.state(f.work));
        f.backend.position=new WorldPosition("minecraft:overworld",20,64,8);
        f.access.available=true;f.step();
        assertEquals(NavigationService.State.MOVING,f.navigation.state(f.work));
        assertTrue(f.chunks.ready(f.work));
        assertEquals(original,f.navigation.request(f.work,f.colony,f.citizen,1,0,f.target,Lane.NORMAL,0));
        f.backend.motion=NavigationService.Motion.ARRIVED;f.step();
        assertTrue(f.navigation.atTarget(f.work));
        f.navigation.cancel(f.work);f.assertReleased();
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
    @Test void waitingDisplacedResidentRebuildsNegativeRouteDomainBeforeRetry() {
        Fixture f=new Fixture(true);f.backend.reachable=false;f.step();
        assertEquals(WorkOrder.Reason.UNREACHABLE,f.navigation.reason(f.work));
        UUID original=f.navigation.request(f.work,f.colony,f.citizen,1,0,f.target,Lane.NORMAL,0);
        f.backend.position=new WorldPosition("minecraft:overworld",8,64,1);
        f.backend.reachable=true;
        for(int tick=0;tick<40 && f.navigation.state(f.work)!=NavigationService.State.MOVING;tick++) f.step();
        assertEquals(NavigationService.State.MOVING,f.navigation.state(f.work));
        assertTrue(f.chunks.admitted(new ChunkKey("minecraft:overworld",0,-1)));
        assertEquals(original,f.navigation.request(f.work,f.colony,f.citizen,1,0,f.target,Lane.NORMAL,0));
        assertEquals(f.citizen,f.registry.workBoard().work(f.work).assignee());
        assertEquals(f.target,f.registry.workBoard().work(f.work).target());
        f.navigation.cancel(f.work);assertEquals(0,f.access.held);
    }
    @Test void displacedWaitingRouteRetainsIdentityDuringDomainAdmissionExhaustion() {
        for(Resource exhausted:List.of(Resource.CHUNK_DEMANDS,Resource.CACHE_ENTRIES)) {
            Fixture f=new Fixture(true);f.backend.reachable=false;f.step();
            UUID original=f.navigation.request(f.work,f.colony,f.citizen,1,0,f.target,Lane.NORMAL,0);
            f.registry.admission().updateLimits(f.registry.admission().limits().withResource(exhausted,1));
            f.backend.position=new WorldPosition("minecraft:overworld",8,64,1);f.backend.reachable=true;
            f.step();
            assertEquals(NavigationService.State.WAITING,f.navigation.state(f.work));
            assertEquals(WorkOrder.Reason.STATE_LIMIT,f.navigation.reason(f.work));
            assertEquals(original,f.navigation.request(f.work,f.colony,f.citizen,1,0,f.target,Lane.NORMAL,0));
            assertEquals(0,f.registry.admission().used(Resource.CHUNK_DEMANDS));
            f.registry.admission().updateLimits(SimulationLimits.development());
            for(int tick=0;tick<40 && !f.navigation.atTarget(f.work);tick++) {
                f.backend.motion=NavigationService.Motion.ARRIVED;f.step();
            }
            assertTrue(f.navigation.atTarget(f.work));
            assertEquals(original,f.navigation.request(f.work,f.colony,f.citizen,1,0,f.target,Lane.NORMAL,0));
            assertEquals(f.citizen,f.registry.workBoard().work(f.work).assignee());
            f.navigation.cancel(f.work);
            assertEquals(0,f.registry.admission().used(Resource.CACHE_ENTRIES));assertEquals(0,f.access.held);
        }
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
        f.assertReleased();
    }
    @Test void cancellationInsideApplyStopsMovementStartedBeforeCallbackAndReturnsCapacity() {
        Fixture f=new Fixture();NavigationService.Request request=f.request(1,1);
        assertEquals(request.id(),f.navigation.request(request));
        f.backend.duringApply=() -> {
            assertEquals(request,f.backend.movement.get(f.citizen));
            f.navigation.cancel(f.work);
        };
        f.step();assertEquals(1,f.backend.applies);assertFalse(f.navigation.atTarget(f.work));
        f.assertReleased();f.navigation.cancel(f.work);f.assertReleased();
        f.backend.duringApply=() -> {};
        NavigationService.Request replacement=f.request(2,2);
        assertEquals(replacement.id(),f.navigation.request(replacement));f.step();
        assertEquals(NavigationService.State.MOVING,f.navigation.state(f.work));
        assertEquals(replacement,f.backend.movement.get(f.citizen));
        assertTrue(f.chunks.ready(f.work));
        f.navigation.cancel(f.work);f.assertReleased();
    }
    @Test void replacementInsideApplyKeepsSuccessorMovementAndDemandWhenOldStopIsDeferred() {
        Fixture f=new Fixture();NavigationService.Request original=f.request(1,1),successor=f.request(2,2);
        assertEquals(original.id(),f.navigation.request(original));
        f.budgets.beginTick(++f.tick);f.chunks.tick(f.tick);
        Map<Resource,Integer> waitingUsage=f.navigationUsage();
        f.backend.duringApply=() -> {
            assertEquals(original,f.backend.movement.get(f.citizen));
            f.backend.duringApply=() -> {};
            assertEquals(successor.id(),f.navigation.request(successor));
            f.step();assertEquals(successor,f.backend.movement.get(f.citizen));
        };
        f.step();assertEquals(NavigationService.State.MOVING,f.navigation.state(f.work));
        assertEquals(successor.id(),f.navigation.request(successor));
        assertEquals(successor,f.backend.movement.get(f.citizen));assertTrue(f.backend.validity.getAsBoolean());
        assertTrue(f.chunks.admitted(f.work));assertTrue(f.chunks.ready(f.work));assertTrue(f.access.held>0);
        Map<Resource,Integer> movingUsage=new HashMap<>(waitingUsage);
        movingUsage.put(Resource.CACHE_ENTRIES,waitingUsage.get(Resource.CACHE_ENTRIES)+f.backend.routeNodes);
        assertEquals(movingUsage,f.navigationUsage());
        f.navigation.cancel(f.work);f.assertReleased();
    }
    @Test void positionCallbackBeforeEntryPublicationCannotOverwriteSameWorkSuccessor() {
        for(boolean existing:List.of(false,true)) {
            Fixture f=new Fixture();f.budgets.beginTick(++f.tick);f.chunks.tick(f.tick);
            Map<Resource,Integer> movingUsage=f.navigationUsage();
            movingUsage.put(Resource.CACHE_ENTRIES,movingUsage.get(Resource.CACHE_ENTRIES)+f.backend.routeNodes);
            if(!existing) {f.navigation.cancel(f.work);f.assertReleased();}
            NavigationService.Request outer=f.request(1,1),successor=f.request(2,2);
            f.backend.duringPosition=() -> {
                f.backend.duringPosition=() -> {};
                assertEquals(successor.id(),f.navigation.request(successor));
                f.step();assertEquals(successor,f.backend.movement.get(f.citizen));
            };
            assertNull(f.navigation.request(outer));
            assertEquals(successor.id(),f.navigation.request(successor));
            assertEquals(NavigationService.State.MOVING,f.navigation.state(f.work));
            assertEquals(successor,f.backend.movement.get(f.citizen));assertTrue(f.backend.validity.getAsBoolean());
            assertTrue(f.chunks.admitted(f.work));assertTrue(f.chunks.ready(f.work));assertTrue(f.access.held>0);
            assertEquals(movingUsage,f.navigationUsage());
            f.navigation.cancel(f.work);f.assertReleased();
        }
    }
    @Test void readinessLostInsideApplyStopsNewMovementWithoutPublishingMovingState() {
        Fixture f=new Fixture();NavigationService.Request request=f.request(1,1);
        assertEquals(request.id(),f.navigation.request(request));
        f.budgets.beginTick(++f.tick);f.chunks.tick(f.tick);
        Map<Resource,Integer> waitingUsage=f.navigationUsage();
        f.backend.duringApply=() -> {
            assertEquals(request,f.backend.movement.get(f.citizen));f.access.available=false;
        };
        f.step();assertEquals(NavigationService.State.WAITING,f.navigation.state(f.work));
        assertEquals(WorkOrder.Reason.CHUNK_NOT_READY,f.navigation.reason(f.work));
        assertEquals(request.id(),f.navigation.request(request));assertTrue(f.backend.movement.isEmpty());
        assertFalse(f.backend.validity.getAsBoolean());assertFalse(f.chunks.ready(f.work));
        assertEquals(waitingUsage,f.navigationUsage());
        f.backend.duringApply=() -> {};f.access.available=true;f.step();
        assertEquals(NavigationService.State.MOVING,f.navigation.state(f.work));
        assertEquals(request,f.backend.movement.get(f.citizen));
        f.navigation.cancel(f.work);f.assertReleased();
    }
    @Test void applyFailurePreservesPrimaryExceptionAndCleansUpDespiteDeferredStopAndCoverageFailures() {
        Fixture f=new Fixture();RuntimeException primary=new IllegalStateException("apply failure");
        RuntimeException stopFailure=new IllegalStateException("stop failure");
        RuntimeException coverageFailure=new IllegalStateException("coverage failure");
        f.chunks.setCoverageLossListener(key -> {throw coverageFailure;});
        f.backend.duringApply=() -> {
            assertNotNull(f.backend.movement.get(f.citizen));f.navigation.cancel(f.work);throw primary;
        };
        f.backend.duringStop=() -> {throw stopFailure;};
        assertSame(primary,assertThrows(RuntimeException.class,f::step));
        assertTrue(hasSuppressed(primary,stopFailure));assertTrue(hasSuppressed(primary,coverageFailure));
        f.assertReleased();f.navigation.cancel(f.work);f.assertReleased();
    }
    @Test void throwingCoverageListenerDoesNotLeakPendingRequestDomainOrAdmission() {
        Fixture f=new Fixture();f.backend.pendingPortions=2;f.step();
        RuntimeException failure=new IllegalStateException("coverage failure");
        f.chunks.setCoverageLossListener(key -> {throw failure;});
        assertSame(failure,assertThrows(RuntimeException.class,() -> f.navigation.cancel(f.work)));
        f.assertReleased();f.navigation.cancel(f.work);f.assertReleased();
        f.chunks.setCoverageLossListener(key -> {});f.backend.pendingPortions=0;
        NavigationService.Request successor=f.request(1,1);
        assertEquals(successor.id(),f.navigation.request(successor));f.step();
        assertEquals(successor,f.backend.movement.get(f.citizen));
        assertTrue(f.chunks.ready(f.work));f.navigation.cancel(f.work);f.assertReleased();
    }
    private static boolean hasSuppressed(Throwable failure,Throwable expected) {
        for(Throwable suppressed:failure.getSuppressed())
            if(suppressed==expected || hasSuppressed(suppressed,expected))return true;
        return false;
    }
    @Test void changedChunkDuringSearchRejectsResultAndNextPortionReplans() {
        Fixture f=new Fixture();ChunkKey key=new ChunkKey("minecraft:overworld",0,0);
        f.backend.duringSearch=() -> f.navigation.invalidate(key);f.step();assertEquals(0,f.backend.applies);
        f.backend.duringSearch=() -> {};f.step();assertEquals(2,f.backend.searches);assertEquals(1,f.backend.applies);
    }
    @Test void unreachableBackoffReleasesDomainAndReadmitsBeforeRetryOrInvalidation() {
        Fixture f=new Fixture();f.backend.reachable=false;f.step();assertEquals(1,f.backend.searches);
        assertEquals(0,f.registry.admission().used(Resource.CHUNK_DEMANDS));assertEquals(0,f.access.held);
        assertTrue(f.registry.admission().used(Resource.CACHE_ENTRIES)>0);
        f.until(20);assertEquals(1,f.backend.searches);f.step();assertEquals(1,f.backend.searches);
        assertEquals(WorkOrder.Reason.CHUNK_NOT_READY,f.navigation.reason(f.work));
        f.step();assertEquals(2,f.backend.searches);assertEquals(0,f.registry.admission().used(Resource.CHUNK_DEMANDS));
        f.until(61);assertEquals(2,f.backend.searches);f.step();assertEquals(2,f.backend.searches);
        f.step();assertEquals(3,f.backend.searches);
        f.navigation.invalidate(new ChunkKey("minecraft:overworld",20,20));f.step();assertEquals(3,f.backend.searches);
        f.navigation.invalidate(new ChunkKey("minecraft:overworld",0,0));f.backend.reachable=true;f.step();
        assertEquals(3,f.backend.searches);assertEquals(WorkOrder.Reason.CHUNK_NOT_READY,f.navigation.reason(f.work));
        f.step();assertEquals(4,f.backend.searches);assertEquals(NavigationService.State.MOVING,f.navigation.state(f.work));
        assertFalse(f.navigation.atTarget(f.work));
    }
    @Test void retriesDoubleThroughTheCapWithoutTickSpin() {
        Fixture f=new Fixture();f.backend.reachable=false;
        long[] attempts={1,22,63,144,305,506,707};
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
        Fixture f=new Fixture();f.budgets.beginTick(++f.tick);f.chunks.tick(f.tick);
        Map<Resource,Integer> movingUsage=f.navigationUsage();
        movingUsage.put(Resource.CACHE_ENTRIES,movingUsage.get(Resource.CACHE_ENTRIES)+f.backend.routeNodes);
        NavigationService.Request successor=f.request(1,1);
        f.backend.duringSearch=() -> {
            f.backend.duringSearch=() -> {};
            assertEquals(successor.id(),f.navigation.request(successor));
        };
        f.step();assertEquals(successor,f.backend.movement.get(f.citizen));assertEquals(movingUsage,f.navigationUsage());
        assertTrue(f.chunks.admitted(f.work));assertTrue(f.chunks.ready(f.work));assertTrue(f.access.held>0);
        assertEquals(successor.id(),f.navigation.request(successor));
        assertEquals(successor,f.backend.movement.get(f.citizen));
        f.navigation.cancel(f.work);f.assertReleased();
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
        f.step();assertEquals(3,f.backend.searches);assertEquals(WorkOrder.Reason.CHUNK_NOT_READY,f.navigation.reason(f.work));
        f.step();assertEquals(4,f.backend.searches);assertEquals(WorkOrder.Reason.BUDGET,f.navigation.reason(f.work));
        assertEquals(0,f.backend.applies);
    }
    @Test void exhaustedSearchRetainsComputationalReasonDuringBoundedBackoffAndRetry() {
        Fixture f=new Fixture();f.backend.pendingPortions=2;f.backend.exhausted=true;
        f.until(2);assertEquals(WorkOrder.Reason.BUDGET,f.navigation.reason(f.work));
        f.step();assertEquals(WorkOrder.Reason.SEARCH_EXHAUSTED,f.navigation.reason(f.work));
        assertEquals(0,f.backend.applies);assertTrue(f.backend.portions.isEmpty());assertEquals(0,f.access.held);
        assertEquals(0,f.registry.admission().used(Resource.CHUNK_DEMANDS));
        f.registry.workBoard().waitAssigned(f.work,f.navigation.reason(f.work),"move");
        assertEquals(WorkOrder.Reason.SEARCH_EXHAUSTED,f.registry.workBoard().work(f.work).snapshot().waitingReason());
        f.until(22);assertEquals(3,f.backend.searches);
        assertEquals(WorkOrder.Reason.SEARCH_EXHAUSTED,f.navigation.reason(f.work));
        f.step();assertEquals(3,f.backend.searches);assertEquals(WorkOrder.Reason.CHUNK_NOT_READY,f.navigation.reason(f.work));
        f.backend.exhausted=false;f.until(26);assertEquals(6,f.backend.searches);
        assertEquals(NavigationService.State.MOVING,f.navigation.state(f.work));assertEquals(WorkOrder.Reason.NONE,f.navigation.reason(f.work));
    }
    @Test void invalidatingExhaustedSearchWakesWithoutWaitingForNegativeBackoff() {
        Fixture f=new Fixture();f.backend.exhausted=true;f.step();
        assertEquals(WorkOrder.Reason.SEARCH_EXHAUSTED,f.navigation.reason(f.work));
        f.backend.exhausted=false;f.navigation.invalidate(new ChunkKey("minecraft:overworld",0,0));
        f.step();assertEquals(1,f.backend.searches);f.step();
        assertEquals(2,f.backend.searches);assertEquals(NavigationService.State.MOVING,f.navigation.state(f.work));
    }
    @Test void unavailableSearchRejectedApplicationAndObstructionAreNotNegativeRouteProofs() {
        Fixture unavailable=new Fixture();unavailable.backend.unavailable=true;unavailable.step();
        assertEquals(WorkOrder.Reason.RECONCILING,unavailable.navigation.reason(unavailable.work));
        Fixture rejected=new Fixture();rejected.backend.applyAccepted=false;rejected.step();
        assertEquals(WorkOrder.Reason.RECONCILING,rejected.navigation.reason(rejected.work));
        Fixture obstructed=new Fixture();obstructed.step();obstructed.backend.motion=NavigationService.Motion.OBSTRUCTED;obstructed.step();
        assertEquals(WorkOrder.Reason.RECONCILING,obstructed.navigation.reason(obstructed.work));
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
    @Test void originalCriticalAndNormalRoutesFinishWithProductionConsumerOrderingAndOneGlobalQuantum() {
        Fixture f=new Fixture(true);
        var limits=SimulationLimits.development().scale300Capacity().withBudget(Budget.DIRTY_RESCAN_OBJECTS,1)
                .withBudget(Budget.NAVIGATION_STARTS,1).withMaxManagedNanos(5_000_000);
        f.registry.admission().updateLimits(limits);f.budgets.updateLimits(limits);
        f.backend.pendingPortions=3;f.backend.motion=NavigationService.Motion.ARRIVED;
        List<UUID> original=new ArrayList<>();original.add(f.work);
        for(int i=1;i<63;i++)original.add(f.addRequest(i,i%2==0?Lane.NORMAL:Lane.CRITICAL,0));
        int readyTick=0;
        while(!original.stream().allMatch(f.chunks::ready)&&readyTick<4000) {
            f.budgets.beginTick(++readyTick);f.chunks.tick(readyTick);
        }
        assertTrue(original.stream().allMatch(f.chunks::ready),"Fixture routes must be actually admitted and ready before measuring continuation");
        var scheduler=new io.github.kpuctajluk.colonyloom.core.scheduler.SimulationScheduler(f.registry.workBoard(),f.budgets);
        scheduler.physicalExecutor(WorkOrder.MOVE,new io.github.kpuctajluk.colonyloom.core.scheduler.SimulationScheduler.PhysicalExecutor() {
            public void step(WorkOrder work,long tick) {
                f.navigation.request(work.id(),work.colonyId(),work.assignee(),1,0,work.target(),work.lane(),work.priority());
                if(f.navigation.state(work.id())==NavigationService.State.WAITING)f.registry.workBoard().waitAssigned(work.id(),f.navigation.reason(work.id()),"movement");
            }
            public void cancel(UUID work) { f.navigation.cancel(work); }
        });
        scheduler.beforeWork(tick -> {
            int first=(int)((tick/2)%9);
            for(int offset=0;offset<9;offset++)switch((first+offset)%9) {
                case 0 -> f.chunks.tick(tick);
                case 2 -> f.navigation.tick(tick);
                default -> f.budgets.tryConsume(Budget.DIRTY_RESCAN_OBJECTS,Lane.SERVICE);
            }
        });
        for(int tick=readyTick+1;tick<=readyTick+1200;tick++) {
            int before=f.backend.searches;scheduler.tick(tick);
            assertTrue(f.backend.searches-before<=1);
            assertTrue(f.budgets.used(Budget.NAVIGATION_STARTS)<=1);
            assertTrue(f.budgets.used(Budget.DIRTY_RESCAN_OBJECTS)<=1);
            if(original.stream().allMatch(f.navigation::atTarget))break;
        }
        assertEquals(63,f.backend.applies,() -> original.stream().filter(work -> !f.navigation.atTarget(work)).map(work -> work+":"+f.registry.workBoard().work(work).state()+":"+f.navigation.state(work)+":"+f.navigation.reason(work)+":"+f.chunks.state(work)+":"+f.chunks.ready(work)+":"+f.backend.searchOrder.stream().filter(work::equals).count()).toList().toString());
        for(UUID work:original) {
            assertTrue(f.navigation.atTarget(work),"Original route was buried: "+work);
        }
        f.budgets.beginTick(readyTick+1201);f.navigation.tick(readyTick+1201);
        assertEquals(0,f.budgets.used(Budget.NAVIGATION_STARTS),"Cached arrivals must not consume navigation portions");
        assertEquals(63,f.navigation.diagnostics().get("requests"));
        assertEquals(f.budgets.totalConsumed(Budget.NAVIGATION_STARTS),f.registry.metrics().snapshot().get("NAVIGATION_UNIT").count());
    }

    @Test void paidDiscoveryStillRequiresActualReadinessAndHonorsGlobalTimeGuard() {
        Fixture f=new Fixture();f.access.available=false;
        f.budgets.updateLimits(f.budgets.limits().withBudget(Budget.DIRTY_RESCAN_OBJECTS,1).withBudget(Budget.NAVIGATION_STARTS,1));
        for(int tick=1;tick<=40;tick++) {
            f.budgets.beginTick(tick);f.chunks.tick(tick);f.navigation.tick(tick);
        }
        assertEquals(0,f.backend.searches);assertFalse(f.navigation.atTarget(f.work));
        f.access.available=true;
        for(int tick=41;tick<=80 && f.backend.applies==0;tick++) {
            f.budgets.beginTick(tick);f.chunks.tick(tick);f.navigation.tick(tick);
        }
        assertEquals(1,f.backend.applies);
        f.navigation.close();
        var clock=new java.util.concurrent.atomic.AtomicLong();
        var guarded=new GlobalWorkBudgets(f.budgets.limits().withMaxManagedNanos(5_000_000),clock::get);
        var navigation=new NavigationService(f.registry,guarded,f.chunks,f.backend);
        navigation.request(f.work,f.colony,f.citizen,1,0,f.target,Lane.NORMAL,0);
        guarded.beginTick(1);clock.set(5_000_000);
        int before=f.backend.searches;navigation.tick(1);
        assertEquals(before,f.backend.searches);assertEquals(0,guarded.used(Budget.NAVIGATION_STARTS));
        navigation.close();
    }
}
