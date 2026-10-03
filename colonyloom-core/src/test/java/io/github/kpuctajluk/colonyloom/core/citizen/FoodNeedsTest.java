package io.github.kpuctajluk.colonyloom.core.citizen;

import io.github.kpuctajluk.colonyloom.core.colony.*;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.storage.*;
import io.github.kpuctajluk.colonyloom.core.supply.*;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class FoodNeedsTest {
    private static UUID id(long value) { return new UUID(0, value); }
    private static WorldPosition pos(int x) { return new WorldPosition("minecraft:overworld", x, 64, 0); }
    private static CitizenRecord citizen(long value, int food) {
        return new CitizenRecord(id(value), id(1), id(value + 100), 1, null, null, null, null, Map.of(), Map.of("food", food),
                CitizenRecord.Lifecycle.ALIVE, CitizenRecord.Admission.ACTIVE, CitizenRecord.Readiness.READY, 0,
                Map.of("food", 1200L), pos(0), 0);
    }
    private static ColonyRegistry registry() {
        var registry = new ColonyRegistry(() -> {});
        registry.addColony(new ColonyRuntime(id(1), "food", new Territory("minecraft:overworld", 0, 0, 31, 31), id(2), Map.of(), 0, 0, false, null, false));
        registry.addCitizen(citizen(3, 6), ignored -> {}); registry.addCitizen(citizen(4, 20), ignored -> {});
        return registry;
    }
    @Test void exactThresholdRemainderSurvivesReadmissionAndRestoredState() {
        CitizenRecord value = citizen(3, 20).withActiveTime(16799);
        assertEquals(7, value.food()); assertEquals(1, value.foodDecayTicks());
        value = value.withAdmission(CitizenRecord.Admission.INACTIVE);
        CitizenRecord restored = new CitizenRecord(value.citizenId(), value.colonyId(), value.entityId(), value.bindingEpoch(),
                value.homeId(), value.workplaceId(), value.assignedWorkId(), value.professionId(), value.skills(), value.needs(),
                value.lifecycle(), value.admission(), value.readiness(), value.activeTimeTicks(), value.remainingTimers(), value.lastKnownPosition(), value.revision());
        restored = restored.withAdmission(CitizenRecord.Admission.ACTIVE).withActiveTime(16800);
        assertEquals(6, restored.food()); assertEquals(1200, restored.foodDecayTicks());
        restored = restored.withFood(11).withActiveTime(17999);
        assertEquals(11, restored.food()); assertEquals(1, restored.foodDecayTicks());
        assertEquals(10, restored.withActiveTime(18000).food());
    }
    @Test void largeActiveDeltaClampsFoodWithoutLosingPartialCountdown() {
        CitizenRecord value = citizen(3, 20).withActiveTime(24001);
        assertEquals(0, value.food()); assertEquals(1199, value.foodDecayTicks());
        assertEquals(0, value.withActiveTime(Long.MAX_VALUE).food());
    }
    @Test void legacyNonnegativeResidualIsHonoredBeforeRecurringInterval() {
        CitizenRecord original = citizen(3, 20);
        CitizenRecord delayed = new CitizenRecord(original.citizenId(), original.colonyId(), original.entityId(), original.bindingEpoch(),
                null, null, null, null, original.skills(), original.needs(), original.lifecycle(), original.admission(), original.readiness(),
                0, Map.of("food", 10000L), original.lastKnownPosition(), 0);
        assertEquals(20, delayed.withActiveTime(9999).food()); assertEquals(1, delayed.withActiveTime(9999).foodDecayTicks());
        assertEquals(19, delayed.withActiveTime(10000).food()); assertEquals(1200, delayed.withActiveTime(10000).foodDecayTicks());
        CitizenRecord due = new CitizenRecord(original.citizenId(), original.colonyId(), original.entityId(), original.bindingEpoch(),
                null, null, null, null, original.skills(), original.needs(), original.lifecycle(), original.admission(), original.readiness(),
                0, Map.of("food", 0L), original.lastKnownPosition(), 0);
        assertEquals(20, due.withActiveTime(0).food()); assertEquals(19, due.withActiveTime(1).food());
        assertEquals(1199, due.withActiveTime(1).foodDecayTicks());
    }
    @Test void prescribedFoodAndPickupCannotChooseAnotherCitizenOrWarehouseAllocation() {
        var registry = registry(); var board = registry.workBoard(); var supply = registry.supply();
        var food = board.createFood(id(10), id(3));
        board.transition(food.id(), WorkOrder.State.READY, WorkOrder.Reason.NONE, "food");
        assertFalse(board.assign(food.id(), id(4)));
        var demand = supply.request(id(11), id(1), food.id(), new ItemMatcher("minecraft:bread", null), 1,
                Demand.GoalKind.CONSUMPTION, pos(0), Lane.CRITICAL, 10, 1);
        var warehouse = new StockRegion(new StorageId("minecraft:overworld", id(20), 0), 0);
        var subject = new StockRegion(new StorageId("minecraft:overworld", id(3), 1), 0);
        var other = new StockRegion(new StorageId("minecraft:overworld", id(4), 1), 0);
        registry.storage().register(id(1), pos(0), "warehouse", List.of(warehouse.storage()), List.of(warehouse), List.of(pos(0)));
        registry.storage().register(id(1), pos(1), "construction", List.of(subject.storage()), List.of(subject), List.of(pos(1)));
        registry.storage().register(id(1), pos(2), "construction", List.of(other.storage()), List.of(other), List.of(pos(2)));
        var bread = new ItemDescriptor("minecraft:bread", new byte[0]);
        registry.storage().index().observe(warehouse, bread, 2, 1); registry.storage().index().observe(subject, null, 0, 1);
        registry.storage().index().observe(other, null, 0, 1);
        var delivery = supply.coverStock(demand.id(), warehouse, bread, 1, 1);
        var reserved = supply.orderShares(delivery.id()).getFirst();
        assertFalse(supply.allocateLocalReservation(reserved.id()));
        var pickup = board.createCitizenDelivery(id(12), id(3), pos(0), 10, Lane.CRITICAL);
        supply.assignDelivery(delivery.id(), id(3), pickup.id());
        assertThrows(IllegalArgumentException.class, () -> supply.prepareSelfPickup(reserved.id(), delivery.id(), other, 1, 1));
        try (var prepared = supply.prepareSelfPickup(reserved.id(), delivery.id(), subject, 1, 1)) {
            registry.storage().index().observe(subject, bread, 1, 1); prepared.commit(1);
            registry.storage().index().observe(warehouse, bread, 1, 1);
        }
        assertEquals(1, demand.snapshot().allocated()); assertEquals(0, demand.snapshot().covered());
        assertTrue(supply.delivery(delivery.id()).terminal()); assertFalse(supply.hasCargo(delivery.id()));
        try (var consume = supply.prepareConsumption(reserved.id(), 1)) { consume.commit(1); }
        assertEquals(1, demand.snapshot().fulfilled()); assertEquals(Demand.Status.COMPLETED, demand.snapshot().status());
        assertThrows(IllegalArgumentException.class, () -> supply.prepareConsumption(reserved.id(), 1));
    }
    @Test void zeroFoodRejectsOrdinaryAssignmentButBoundFoodRemainsAllowed() {
        var registry = registry(); var board = registry.workBoard();
        registry.updateCitizen(registry.citizen(id(3)).withFood(0));
        var normal = board.createMove(id(10), id(1), pos(0), 0, Lane.NORMAL);
        board.transition(normal.id(), WorkOrder.State.READY, WorkOrder.Reason.NONE, "move");
        assertFalse(board.assign(normal.id(), id(3))); assertTrue(board.assign(normal.id(), id(4)));
        var food = board.createFood(id(11), id(3)); board.transition(food.id(), WorkOrder.State.READY, WorkOrder.Reason.NONE, "food");
        assertTrue(board.assign(food.id(), id(3)));
    }
    @Test void foodPreemptionRetainsAllocatedCargoAndOrdinaryConsumerUntilSafeUnload() {
        var registry = registry(); var board = registry.workBoard(); var supply = registry.supply();
        var ordinary = board.createMove(id(10), id(1), pos(0), 0, Lane.NORMAL);
        board.transition(ordinary.id(), WorkOrder.State.READY, WorkOrder.Reason.NONE, "move"); assertTrue(board.assign(ordinary.id(), id(3)));
        var demand = supply.request(id(11), id(1), ordinary.id(), new ItemMatcher("minecraft:stone", null), 1,
                Demand.GoalKind.CONSUMPTION, pos(0), Lane.NORMAL, 0, 1);
        var cargo = new StockRegion(new StorageId("minecraft:overworld", id(3), 1), 0);
        var buffer = new StockRegion(new StorageId("minecraft:overworld", id(20), 0), 0);
        registry.storage().register(id(1), pos(0), "construction", List.of(cargo.storage()), List.of(cargo), List.of(pos(0)));
        registry.storage().register(id(1), pos(1), "return", List.of(buffer.storage()), List.of(buffer), List.of(pos(1)));
        var stone = new ItemDescriptor("minecraft:stone", new byte[0]);
        registry.storage().index().observe(cargo, stone, 1, 1); registry.storage().index().observe(buffer, null, 0, 1);
        var share = supply.allocateStock(demand.id(), cargo, stone, 1, 1);
        assertFalse(board.requestFoodPreemption(id(3))); assertTrue(ordinary.criticalService());
        assertEquals(Lane.CRITICAL, ordinary.lane()); assertEquals(Lane.NORMAL, ordinary.admissionLane());
        assertEquals(ordinary.id(), registry.citizen(id(3)).assignedWorkId()); assertEquals(0, demand.snapshot().fulfilled());
        try (var prepared = supply.prepareAllocationMove(share.id(), buffer, 1, 1)) {
            registry.storage().index().observe(buffer, stone, 1, 1); prepared.commit(1); registry.storage().index().observe(cargo, null, 0, 1);
        }
        assertTrue(board.requestFoodPreemption(id(3))); assertNull(registry.citizen(id(3)).assignedWorkId());
        assertFalse(ordinary.criticalService()); assertEquals(1, demand.snapshot().allocated()); assertEquals(0, demand.snapshot().fulfilled());
        assertNotEquals(Demand.Status.CANCELLED, demand.snapshot().status());
    }
}
