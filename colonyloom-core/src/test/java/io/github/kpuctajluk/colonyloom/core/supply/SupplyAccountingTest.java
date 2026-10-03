package io.github.kpuctajluk.colonyloom.core.supply;

import io.github.kpuctajluk.colonyloom.core.colony.*;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.storage.*;
import io.github.kpuctajluk.colonyloom.core.logistics.DeliveryOrder;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SupplyAccountingTest {
    static UUID id(long value) { return new UUID(0, value); }
    static final UUID COLONY = id(1), OWNER = id(2);
    static final ItemDescriptor X = new ItemDescriptor("minecraft:stone", new byte[0]);
    static final ItemDescriptor Y = new ItemDescriptor("minecraft:dirt", new byte[0]);
    static WorldPosition pos(int x) { return new WorldPosition("minecraft:overworld", x, 64, 0); }
    static StockRegion slot(int slot) { return new StockRegion(new StorageId("minecraft:overworld", id(100), 0), slot); }
    static final class Fixture {
        final ColonyRegistry registry = new ColonyRegistry(() -> {});
        final SupplyRegistry supply = registry.supply();
        Fixture() { this(SimulationLimits.development()); }
        Fixture(SimulationLimits limits) {
            registry.admission().updateLimits(limits);
            registry.addColony(new ColonyRuntime(COLONY, "supply", new Territory("minecraft:overworld", 0, 0, 31, 31), OWNER, Map.of(), 0, 0, false, null, false));
            registry.storage().register(COLONY, pos(0), "warehouse", List.of(slot(0).storage()), List.of(slot(0), slot(1), slot(2)), List.of(pos(0)));
        }
        void stock(int slot, ItemDescriptor item, long count) { registry.storage().index().observe(slot(slot), item, count, 1); }
        Demand request(long id, long quantity, Demand.GoalKind kind, Lane lane) { return supply.request(id(id), COLONY, OWNER, new ItemMatcher(X.itemId(), null), quantity, kind, pos(2), lane, 10, 1); }
        CoverageShare share(DeliveryOrder order) { return supply.shares().stream().filter(s -> order.id().equals(s.sourceOrderId()) && s.covered()).findFirst().orElseThrow(); }
        RecipeDefinition recipe(long count) { return RecipeDefinition.create("colonyloom:test", 1, "colonyloom:carpenter", "minecraft:crafting_table", List.of(new RecipeDefinition.Ingredient(new ItemMatcher(Y.itemId(), null), 1)), X, count, 20); }
    }
    @Test void formulaMatrixIncludesEachStageOnce() {
        for (CoverageShare.Stage stage : CoverageShare.Stage.values()) {
            long fulfilled = stage == CoverageShare.Stage.FULFILLED ? 20 : 0, allocated = stage == CoverageShare.Stage.ALLOCATED ? 20 : 0;
            long covered = stage == CoverageShare.Stage.RESERVED_STOCK || stage == CoverageShare.Stage.PROMISED_OUTPUT || stage == CoverageShare.Stage.IN_TRANSIT ? 20 : 0;
            var snapshot = new Demand.Snapshot(id(3), COLONY, OWNER, new ItemMatcher(X.itemId(), X), Demand.GoalKind.CONSUMPTION, pos(1), 64, fulfilled, allocated, covered, fulfilled + allocated, 0, Lane.NORMAL, 0, 1, Demand.Status.ACTIVE);
            assertEquals(44, snapshot.deficit(), stage.name());
        }
        assertEquals(0, new Demand.Snapshot(id(3), COLONY, OWNER, new ItemMatcher(X.itemId(), X), Demand.GoalKind.CONSUMPTION, pos(1), 10, 5, 8, 9, 13, 0, Lane.NORMAL, 0, 1, Demand.Status.ACTIVE).deficit());
    }
    @Test void reservedTransitAllocatedConsumedTransitionsNeverDoubleCount() {
        Fixture f = new Fixture(); f.stock(0, X, 64); f.stock(1, X, 64);
        Demand demand = f.request(10, 64, Demand.GoalKind.CONSUMPTION, Lane.NORMAL);
        DeliveryOrder order = f.supply.coverStock(demand.id(), slot(0), X, 64, 1); CoverageShare share = f.share(order);
        assertEquals(64, demand.snapshot().covered()); assertEquals(0, demand.deficit());
        f.supply.inTransit(share.id(), order.id(), slot(1), id(70), 1);
        assertEquals(64, demand.snapshot().covered()); assertEquals(0, demand.snapshot().allocated());
        CoverageShare allocation = f.supply.transfer(share.id(), 64, slot(1), id(71), 1);
        assertEquals(0, demand.snapshot().covered()); assertEquals(64, demand.snapshot().allocated()); assertEquals(64, demand.snapshot().deliveredTotal());
        f.supply.fulfillConsumption(allocation.id(), 16);
        assertEquals(16, demand.snapshot().fulfilled()); assertEquals(48, demand.snapshot().allocated()); assertEquals(64, demand.snapshot().deliveredTotal()); assertEquals(0, demand.deficit());
    }
    @Test void historical64Loss16Retains48AndUnknownRestore() {
        Fixture f = new Fixture(); f.stock(0, X, 64); Demand d = f.request(10, 64, Demand.GoalKind.CONSUMPTION, Lane.NORMAL);
        var order = f.supply.coverStock(d.id(), slot(0), X, 64, 1); f.supply.transfer(f.share(order).id(), 64, slot(0), id(80), 1);
        f.stock(0, X, 48); f.supply.reconcile();
        assertEquals(48, d.snapshot().allocated()); assertEquals(16, d.deficit()); assertEquals(64, d.snapshot().deliveredTotal());
        var supply = f.supply.snapshot(); var storage = f.registry.storage().snapshot();
        var replacement = new AdmissionLedger(SimulationLimits.development(), () -> {});
        try (var stagedStorage = f.registry.storage().prepareRestore(storage, replacement, f.registry.colonies()); var stagedSupply = f.supply.prepareRestore(supply, storage, replacement, f.registry.colonies())) {
            stagedStorage.commit(); stagedSupply.commit();
        }
        assertFalse(f.registry.storage().index().observation(slot(0)).ready()); f.supply.reconcile();
        assertEquals(supply, f.supply.snapshot()); assertEquals(48, f.supply.demand(id(10)).snapshot().allocated());
    }
    @Test void partialDeliveryFulfilsWithoutConsumerAllocationAndReductionReleasesExcess() {
        Fixture f = new Fixture(); f.stock(0, X, 64); Demand d = f.request(10, 64, Demand.GoalKind.DELIVERY, Lane.NORMAL);
        var order = f.supply.coverStock(d.id(), slot(0), X, 64, 1); var share = f.share(order);
        f.supply.transfer(share.id(), 16, null, null, 1);
        assertEquals(16, d.snapshot().fulfilled()); assertEquals(48, d.snapshot().covered()); assertEquals(0, d.snapshot().allocated()); assertEquals(16, d.snapshot().deliveredTotal());
        f.supply.reduceRequired(d.id(), 32);
        assertEquals(16, d.snapshot().covered()); assertEquals(48, f.registry.storage().index().free(slot(0), 1));
        f.supply.transfer(share.id(), 16, null, null, 1);
        assertEquals(Demand.Status.COMPLETED, d.snapshot().status()); assertEquals(32, d.snapshot().fulfilled()); assertTrue(f.registry.storage().reservations().entries().isEmpty());
    }
    @Test void cancelledActualCargoRemainsReferencedAndReturns() {
        Fixture f = new Fixture(); f.stock(0, X, 16); f.stock(1, X, 16); Demand d = f.request(10, 16, Demand.GoalKind.CONSUMPTION, Lane.NORMAL);
        var order = f.supply.coverStock(d.id(), slot(0), X, 16, 1); var share = f.share(order);
        f.supply.inTransit(share.id(), order.id(), slot(1), id(90), 1); f.supply.cancel(d.id());
        assertEquals(16, d.snapshot().covered()); assertEquals(Demand.Status.CANCELLED, d.snapshot().status());
        assertEquals(DeliveryOrder.State.RETURNING, f.supply.deliveries().getFirst().state()); assertNotNull(f.registry.storage().reservations().get(id(90)));
        assertThrows(IllegalArgumentException.class, () -> f.supply.release(share.id()));
    }
    @Test void productionAndAllIngredientAdmissionsAreAtomicAndPromisesAreSharedOnce() {
        Fixture f = new Fixture(); Demand root = f.request(10, 4, Demand.GoalKind.CONSUMPTION, Lane.NORMAL);
        var order = f.supply.promiseProduction(root.id(), f.recipe(8), 1);
        assertEquals(2, f.supply.demands().size()); assertEquals(4, root.snapshot().covered());
        var child = f.supply.ingredientDemand(order, 0, 1, 1); assertEquals(order.id(), child.snapshot().ownerId());
        Demand second = f.request(11, 4, Demand.GoalKind.CONSUMPTION, Lane.CRITICAL);
        assertTrue(f.supply.sharedOutput(second.id())); assertFalse(f.supply.sharedOutput(second.id()));
        assertEquals(1, f.supply.productionOrders().size()); assertEquals(Lane.CRITICAL, f.supply.productionOrders().getFirst().lane()); assertEquals(Lane.CRITICAL, child.snapshot().lane());
        f.supply.cancel(second.id()); assertEquals(Lane.NORMAL, f.supply.productionOrders().getFirst().lane()); assertEquals(Lane.NORMAL, child.snapshot().lane());
        Fixture denied = new Fixture(SimulationLimits.development().withResource(Resource.DEMANDS, 2));
        var goal = denied.request(20, 4, Demand.GoalKind.CONSUMPTION, Lane.NORMAL);
        assertThrows(AdmissionLedger.AdmissionException.class, () -> denied.supply.promiseProduction(goal.id(), denied.recipe(4), 1));
        assertTrue(denied.supply.productionOrders().isEmpty()); assertTrue(denied.supply.shares().isEmpty()); assertEquals(0, goal.snapshot().covered());
        assertEquals(0, denied.registry.admission().used(Resource.DELIVERIES_AND_PRODUCTION_ORDERS));
    }
    @Test void restoreRejectsMissingStagedObligationAndRollsBackAdmission() {
        Fixture f = new Fixture(); f.stock(0, X, 10); var d = f.request(10, 10, Demand.GoalKind.CONSUMPTION, Lane.NORMAL); f.supply.coverStock(d.id(), slot(0), X, 10, 1);
        var saved = f.supply.snapshot(); var replacement = new AdmissionLedger(SimulationLimits.development(), () -> {});
        assertThrows(IllegalArgumentException.class, () -> f.supply.prepareRestore(saved, StorageSnapshot.empty(), replacement, f.registry.colonies()));
        assertEquals(saved, f.supply.snapshot()); assertEquals(0, replacement.used(Resource.DEMANDS));
        try (var abandoned = f.supply.prepareRestore(saved, f.registry.storage().snapshot(), replacement, f.registry.colonies())) { assertEquals(1, replacement.used(Resource.DEMANDS)); }
        assertEquals(0, replacement.used(Resource.DEMANDS));
    }
    @Test void fullNormalStateStillAdmitsCriticalDemandReservationAndOrder() {
        Fixture f = new Fixture(SimulationLimits.development().withResource(Resource.DEMANDS, 8).withResource(Resource.COVERAGE_SHARES, 8)
                .withResource(Resource.RESERVATIONS_AND_ALLOCATIONS, 8).withResource(Resource.DELIVERIES_AND_PRODUCTION_ORDERS, 8));
        f.stock(0, X, 64);
        for(int colony=0;colony<3;colony++) {
            UUID colonyId=colony==0?COLONY:id(200+colony);
            if(colony>0) {
                f.registry.addColony(new ColonyRuntime(colonyId,"saturated"+colony,new Territory("minecraft:overworld",colony*32,0,colony*32+31,31),OWNER,Map.of(),0,0,false,null,false));
                f.registry.storage().register(colonyId,pos(colony*32),"warehouse",List.of(slot(0).storage()),List.of(slot(0)),List.of(pos(colony*32)));
            }
            for(int i=0;i<2;i++) {
                var d=f.supply.request(id(10+colony*2+i),colonyId,OWNER,new ItemMatcher(X.itemId(),null),1,Demand.GoalKind.CONSUMPTION,pos(colony*32),Lane.NORMAL,10,1);
                f.supply.coverStock(d.id(),slot(0),X,1,1);
            }
        }
        assertEquals(6,f.registry.admission().used(Resource.DEMANDS,Lane.NORMAL));
        assertThrows(AdmissionLedger.AdmissionException.class, () -> f.request(20, 1, Demand.GoalKind.CONSUMPTION, Lane.NORMAL));
        var critical = f.request(21, 1, Demand.GoalKind.CONSUMPTION, Lane.CRITICAL); f.supply.coverStock(critical.id(), slot(0), X, 1, 1);
        assertEquals(1, f.registry.admission().used(Resource.RESERVATIONS_AND_ALLOCATIONS, Lane.CRITICAL));
        assertEquals(Lane.CRITICAL, f.registry.storage().reservations().entries().getLast().lane());
    }
    @Test void matcherAndStageReferenceValidationAreExact() {
        var named = new ItemDescriptor(X.itemId(), new byte[]{1});
        assertTrue(new ItemMatcher(X.itemId(), null).matches(named)); assertFalse(new ItemMatcher(X.itemId(), X).matches(named));
        assertThrows(IllegalArgumentException.class, () -> new ItemMatcher(Y.itemId(), X));
        assertThrows(IllegalArgumentException.class, () -> new CoverageShare(id(10), COLONY, id(11), null, null, null, X, 1, 0, CoverageShare.Stage.IN_TRANSIT));
    }
}
