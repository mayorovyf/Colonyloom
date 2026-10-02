package io.github.kpuctajluk.colonyloom.core.scheduler;

import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.persistence.RegistrySnapshot;
import io.github.kpuctajluk.colonyloom.core.work.WorkBoard;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SimulationSchedulerTest {
    private static UUID id(long value) { return new UUID(0,value); }
    private static final SimulationLimits LIMITS = SimulationLimits.development()
            .withResource(Resource.READY_ENTRIES,2).withResource(Resource.WORKS,64)
            .withResource(Resource.WAIT_REGISTRATIONS,64).withBudget(Budget.PHYSICAL_ACTIONS,1);
    private static final class Fixture {
        final ColonyRegistry registry = new ColonyRegistry(() -> {});
        final WorkBoard board = registry.workBoard();
        final GlobalWorkBudgets budgets = new GlobalWorkBudgets(LIMITS,() -> 0);
        final SimulationScheduler scheduler;
        long tick;
        Fixture() { registry.admission().updateLimits(LIMITS); scheduler = new SimulationScheduler(board,budgets); }
        UUID colony(long number, int x) {
            UUID colony = id(number);
            registry.addColony(new ColonyRuntime(colony,"Colony",new Territory("minecraft:overworld",x,0,x+31,31),id(900),Map.of(),0,0,false,null,false)); return colony;
        }
        UUID citizen(long number, UUID colony) {
            UUID citizen = id(number); int x = registry.colony(colony).territory().minX();
            registry.addCitizen(new CitizenRecord(citizen,colony,id(number+1000),1,null,null,null,null,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.READY,0,Map.of(),new WorldPosition("minecraft:overworld",x,64,0),0), proposed -> {});
            return citizen;
        }
        WorkOrder timer(long number, UUID colony, long duration) { return board.createTimer(id(number),colony,new WorldPosition("minecraft:overworld",registry.colony(colony).territory().minX(),64,0),null,0,AdmissionLedger.Lane.NORMAL,duration); }
        void ticks(int count) { for (int i=0;i<count;i++) scheduler.tick(++tick); }
    }
    @Test void physicalBackoffRetainsExactWorkerAndCancellationStopsImmediately() {
        Fixture f = new Fixture(); UUID colony = f.colony(1,0); UUID citizen = f.citizen(11,colony); f.citizen(12,colony);
        UUID[] held = {null}; int[] steps = {0}, stops = {0};
        f.scheduler.movementExecutor(new SimulationScheduler.MovementExecutor() {
            public void step(WorkOrder work,long tick) {
                if (held[0] == null) held[0] = work.assignee();
                assertEquals(held[0],work.assignee()); steps[0]++;
                f.board.waitAssigned(work.id(),WorkOrder.Reason.UNREACHABLE,"move");
            }
            public void cancel(UUID work) { stops[0]++; }
        });
        WorkOrder move = f.board.createMove(id(21),colony,new WorldPosition("minecraft:overworld",8,64,0),0,AdmissionLedger.Lane.NORMAL);
        f.ticks(30); assertTrue(steps[0] > 1); assertEquals(held[0],move.assignee()); assertEquals(move.id(),f.registry.citizen(held[0]).assignedWorkId());
        f.board.cancel(move.id()); assertTrue(stops[0] > 0); int before = steps[0]; f.ticks(30);
        assertEquals(before,steps[0]); assertNull(f.registry.citizen(held[0]).assignedWorkId());
    }
    @Test void inactiveTimerKeepsResidualAndDoesNotCatchUpOnReadmission() {
        Fixture f = new Fixture(); UUID colony = f.colony(1,0); UUID citizen = f.citizen(11,colony); WorkOrder timer = f.timer(21,colony,100);
        f.ticks(8); f.board.releaseAssignment(timer.id());
        f.registry.updateCitizen(f.registry.citizen(citizen).withAdmission(CitizenRecord.Admission.INACTIVE));
        long residual = f.board.snapshots().getFirst().remainingActiveTicks();
        f.ticks(200); assertEquals(residual,f.board.snapshots().getFirst().remainingActiveTicks());
        f.registry.updateCitizen(f.registry.citizen(citizen).withAdmission(CitizenRecord.Admission.ACTIVE));
        f.ticks(2); assertTrue(f.board.snapshots().getFirst().remainingActiveTicks() >= residual-2);
        f.ticks(200); assertEquals(WorkOrder.State.COMPLETED,timer.state());
    }
    @Test void saturatedSingleNormalReadySlotStillServicesBothColonies() {
        Fixture f = new Fixture(); UUID a = f.colony(1,0), b = f.colony(2,64);
        f.citizen(11,a); f.citizen(12,b);
        WorkOrder a1 = f.timer(21,a,3), a2 = f.timer(22,a,3), b1 = f.timer(23,b,3), b2 = f.timer(24,b,3);
        f.ticks(160);
        assertEquals(WorkOrder.State.COMPLETED,a1.state()); assertEquals(WorkOrder.State.COMPLETED,a2.state());
        assertEquals(WorkOrder.State.COMPLETED,b1.state()); assertEquals(WorkOrder.State.COMPLETED,b2.state());
        assertNull(f.registry.citizen(id(11)).assignedWorkId()); assertNull(f.registry.citizen(id(12)).assignedWorkId());
        assertTrue(f.board.ledger().highWater(Resource.READY_ENTRIES) <= 2);
    }
    @Test void invalidationDuringProcessingIsHandledWithoutAnotherNotification() {
        Fixture f = new Fixture(); UUID colony = f.colony(1,0); f.citizen(11,colony); WorkOrder work = f.timer(21,colony,3);
        boolean[] invalidated = {false};
        f.scheduler.beforeStep(() -> { if (!invalidated[0]) { invalidated[0] = true; f.board.invalidate(work.id()); } });
        f.ticks(30);
        assertTrue(invalidated[0]); assertEquals(WorkOrder.State.COMPLETED,work.state());
        assertEquals(work.revision(),work.processedRevision()); assertFalse(work.dirty());
    }
    @Test void cancellationAtFullWaitCapacityReleasesWaitsAndExecutorButRetainsRecord() {
        Fixture f = new Fixture(); UUID colony = f.colony(1,0); UUID citizen = f.citizen(11,colony); WorkOrder work = f.timer(21,colony,100);
        f.ticks(4); assertEquals(work.id(),f.registry.citizen(citizen).assignedWorkId());
        int waits = f.board.ledger().used(Resource.WAIT_REGISTRATIONS);
        f.registry.admission().updateLimits(LIMITS.withResource(Resource.WAIT_REGISTRATIONS,waits));
        f.board.cancel(work.id());
        assertNull(f.registry.citizen(citizen).assignedWorkId()); assertNull(work.assignee());
        assertEquals(0,f.board.ledger().used(Resource.WAIT_REGISTRATIONS));
        assertSame(work,f.board.work(work.id())); assertEquals(WorkOrder.State.CANCELLED,work.state());
    }
    @Test void checkpointRestoresResidualWithoutOfflineOrInactiveCatchup() {
        Fixture f = new Fixture(); UUID colony = f.colony(1,0); UUID citizen = f.citizen(11,colony); WorkOrder work = f.timer(21,colony,100);
        f.ticks(8); RegistrySnapshot snapshot = f.registry.snapshot(); long remaining = snapshot.works().getFirst().remainingActiveTicks();
        Fixture restored = new Fixture(); restored.registry.restore(snapshot); restored.scheduler.rebuild(); restored.tick = 100_000;
        restored.ticks(20); assertEquals(remaining,restored.board.snapshots().getFirst().remainingActiveTicks());
        CitizenRecord old = restored.registry.citizen(citizen);
        restored.registry.updateCitizen(new CitizenRecord(old.citizenId(),old.colonyId(),old.entityId(),old.bindingEpoch(),old.homeId(),old.workplaceId(),old.assignedWorkId(),old.professionId(),old.skills(),old.needs(),old.lifecycle(),old.admission(),CitizenRecord.Readiness.READY,old.activeTimeTicks(),old.remainingTimers(),old.lastKnownPosition(),old.revision()+1));
        restored.ticks((int)remaining+20);
        assertEquals(WorkOrder.State.COMPLETED,restored.board.work(work.id()).state()); assertNull(restored.registry.citizen(citizen).assignedWorkId());
    }
    @Test void onlyOneWorkCanOwnAWorkerAndRetirementFreesItsState() {
        Fixture f = new Fixture(); UUID colony = f.colony(1,0); UUID citizen = f.citizen(11,colony);
        WorkOrder a = f.timer(21,colony,100), b = f.timer(22,colony,100); f.ticks(6);
        assertEquals(1,(a.assignee() == null ? 0 : 1)+(b.assignee() == null ? 0 : 1));
        WorkOrder active = a.assignee() == null ? b : a; assertEquals(active.id(),f.registry.citizen(citizen).assignedWorkId());
        f.board.cancel(a.id()); f.board.cancel(b.id()); f.board.retire(a.id()); f.board.retire(b.id());
        assertEquals(0,f.board.ledger().used(Resource.WORKS)); assertEquals(0,f.board.ledger().used(Resource.WAIT_REGISTRATIONS));
        f.ticks(10); assertNull(f.registry.citizen(citizen).assignedWorkId());
    }
}
