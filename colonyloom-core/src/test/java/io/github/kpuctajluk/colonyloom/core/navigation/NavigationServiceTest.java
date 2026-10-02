package io.github.kpuctajluk.colonyloom.core.navigation;

import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
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
        int searches, applies, stops;
        boolean reachable=true;
        Runnable duringSearch=() -> {};
        BooleanSupplier validity;
        @Override public WorldPosition position(NavigationService.Request request) { return new WorldPosition("minecraft:overworld",8,64,8); }
        @Override public NavigationService.Route search(NavigationService.Request request,List<ChunkKey> admitted) {
            searches++;duringSearch.run();return reachable ? () -> 5 : null;
        }
        @Override public boolean apply(NavigationService.Request request,NavigationService.Route route,BooleanSupplier stillCurrent) { applies++;validity=stillCurrent;return true; }
        @Override public NavigationService.Motion poll(NavigationService.Request request) { return NavigationService.Motion.MOVING; }
        @Override public void stop(NavigationService.Request request) { stops++; }
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
            registry.addCitizen(new CitizenRecord(citizen,colony,id(102),1,null,null,null,null,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,
                    CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.READY,0,Map.of(),new WorldPosition("minecraft:overworld",8,64,8),0), proposed -> {});
            registry.bindings().observe(citizen,id(102),1);
            registry.workBoard().createTimer(work,colony,target,null,0,Lane.NORMAL,1000);
            registry.workBoard().transition(work,WorkOrder.State.READY,WorkOrder.Reason.NONE,"movement");
            assertTrue(registry.workBoard().assign(work,citizen));
            navigation.request(work,colony,citizen,1,0,target,Lane.NORMAL,0);
        }
        void step() { budgets.beginTick(++tick);chunks.tick(tick);navigation.tick(tick); }
        void until(long deadline) { while(tick<deadline) step(); }
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
        f.registry.admission().updateLimits(SimulationLimits.development().withResource(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.LOADED_FOOTPRINT,8));
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
}
