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
        void move(SupplyRegistry.PreparedTransfer prepared, int moved) {
            try (prepared) {
                var source = registry.storage().index().observation(prepared.source());
                var destination = registry.storage().index().observation(prepared.destination());
                registry.storage().index().observe(prepared.destination(), prepared.item(), destination.count() + moved, 1);
                prepared.commit(moved);
                registry.storage().index().observe(prepared.source(), source.count() == moved ? null : prepared.item(), source.count() - moved, 1);
                supply.reconcile();
            }
        }
        CoverageShare cargo(DeliveryOrder order) { return supply.orderShares(order.id()).stream().filter(s -> s.stage() == CoverageShare.Stage.IN_TRANSIT).findFirst().orElseThrow(); }
        void pickup(DeliveryOrder order, int moved) {
            stock(1, null, 0); move(supply.preparePickup(share(order).id(), order.id(), slot(1), moved, 1), moved);
        }
        void deliver(DeliveryOrder order, int moved) {
            if (!registry.storage().index().observation(slot(2)).ready()) stock(2, null, 0);
            move(supply.prepareTransfer(cargo(order).id(), slot(2), moved, 1), moved);
        }
        RecipeDefinition recipe(long count) { return RecipeDefinition.create("colonyloom:test", 1, "colonyloom:carpenter", "minecraft:crafting_table", List.of(new RecipeDefinition.Ingredient(new ItemMatcher(Y.itemId(), null), 1)), X, count, 20); }
    }
    @Test void formulaMatrixIncludesEachStageOnce() {
        for (CoverageShare.Stage stage : CoverageShare.Stage.values()) {
            long fulfilled = stage == CoverageShare.Stage.FULFILLED ? 20 : 0, allocated = stage == CoverageShare.Stage.ALLOCATED ? 20 : 0;
            long covered = stage == CoverageShare.Stage.RESERVED_STOCK || stage == CoverageShare.Stage.PROMISED_OUTPUT || stage == CoverageShare.Stage.IN_TRANSIT ? 20 : 0;
            var snapshot = new Demand.Snapshot(id(3), COLONY, OWNER, new ItemMatcher(X.itemId(), X), Demand.GoalKind.CONSUMPTION, pos(1), 64, fulfilled, allocated, covered, fulfilled + allocated, 0, Lane.NORMAL, 0, 1, Demand.Status.ACTIVE, List.of());
            assertEquals(44, snapshot.deficit(), stage.name());
        }
        assertEquals(0, new Demand.Snapshot(id(3), COLONY, OWNER, new ItemMatcher(X.itemId(), X), Demand.GoalKind.CONSUMPTION, pos(1), 10, 5, 8, 9, 13, 0, Lane.NORMAL, 0, 1, Demand.Status.ACTIVE, List.of()).deficit());
    }
    @Test void reservedTransitAllocatedConsumedTransitionsNeverDoubleCount() {
        Fixture f = new Fixture(); f.stock(0, X, 64); f.stock(1, X, 64);
        Demand demand = f.request(10, 64, Demand.GoalKind.CONSUMPTION, Lane.NORMAL);
        DeliveryOrder order = f.supply.coverStock(demand.id(), slot(0), X, 64, 1); CoverageShare share = f.share(order);
        assertEquals(64, demand.snapshot().covered()); assertEquals(0, demand.deficit());
        f.pickup(order, 64);
        assertEquals(64, demand.snapshot().covered()); assertEquals(0, demand.snapshot().allocated());
        f.deliver(order, 64); CoverageShare allocation = f.supply.orderShares(order.id()).stream().filter(s -> s.stage() == CoverageShare.Stage.ALLOCATED).findFirst().orElseThrow();
        assertEquals(0, demand.snapshot().covered()); assertEquals(64, demand.snapshot().allocated()); assertEquals(64, demand.snapshot().deliveredTotal());
        f.supply.fulfillConsumption(allocation.id(), 16);
        assertEquals(16, demand.snapshot().fulfilled()); assertEquals(48, demand.snapshot().allocated()); assertEquals(64, demand.snapshot().deliveredTotal()); assertEquals(0, demand.deficit());
    }
    @Test void historical64Loss16Retains48AndUnknownRestore() {
        Fixture f = new Fixture(); f.stock(0, X, 64); Demand d = f.request(10, 64, Demand.GoalKind.CONSUMPTION, Lane.NORMAL);
        var order = f.supply.coverStock(d.id(), slot(0), X, 64, 1); f.pickup(order, 64); f.deliver(order, 64);
        f.stock(2, X, 48); f.supply.reconcile();
        assertEquals(48, d.snapshot().allocated()); assertEquals(16, d.deficit()); assertEquals(64, d.snapshot().deliveredTotal());
        var supply = f.supply.snapshot(); var storage = f.registry.storage().snapshot();
        var replacement = new AdmissionLedger(SimulationLimits.development(), () -> {});
        try (var stagedStorage = f.registry.storage().prepareRestore(storage, replacement, f.registry.colonies()); var stagedSupply = f.supply.prepareRestore(supply, storage, replacement, f.registry.colonies())) {
            stagedStorage.commit(); stagedSupply.commit();
        }
        assertFalse(f.registry.storage().index().observation(slot(2)).ready()); f.supply.reconcile();
        assertEquals(supply, f.supply.snapshot()); assertEquals(48, f.supply.demand(id(10)).snapshot().allocated());
    }
    @Test void partialDeliveryFulfilsWithoutConsumerAllocationAndReductionReleasesExcess() {
        Fixture f = new Fixture(); f.stock(0, X, 64); Demand d = f.request(10, 64, Demand.GoalKind.DELIVERY, Lane.NORMAL);
        var order = f.supply.coverStock(d.id(), slot(0), X, 64, 1); var share = f.share(order);
        f.pickup(order, 16); f.deliver(order, 16);
        assertEquals(16, d.snapshot().fulfilled()); assertEquals(48, d.snapshot().covered()); assertEquals(0, d.snapshot().allocated()); assertEquals(16, d.snapshot().deliveredTotal());
        f.supply.reduceRequired(d.id(), 32);
        assertEquals(16, d.snapshot().covered()); assertEquals(32, f.registry.storage().index().free(slot(0), 1));
        f.pickup(order, 16); f.deliver(order, 16);
        assertEquals(Demand.Status.COMPLETED, d.snapshot().status()); assertEquals(32, d.snapshot().fulfilled()); assertTrue(f.registry.storage().reservations().entries().isEmpty());
    }
    @Test void cancelledActualCargoRemainsReferencedAndReturns() {
        Fixture f = new Fixture(); f.stock(0, X, 16); f.stock(1, X, 16); Demand d = f.request(10, 16, Demand.GoalKind.CONSUMPTION, Lane.NORMAL);
        var order = f.supply.coverStock(d.id(), slot(0), X, 16, 1); var share = f.share(order);
        f.pickup(order, 16); f.supply.cancel(d.id());
        assertEquals(16, d.snapshot().covered()); assertEquals(Demand.Status.CANCELLED, d.snapshot().status());
        assertEquals(DeliveryOrder.State.RETURNING, f.supply.deliveries().getFirst().state()); assertNotNull(f.registry.storage().reservations().get(f.cargo(order).obligationId()));
        assertThrows(IllegalArgumentException.class, () -> f.supply.release(share.id()));
        f.stock(2, null, 0); f.move(f.supply.prepareReturn(f.cargo(order).id(), slot(2), 16, 1), 16);
        assertEquals(0, d.snapshot().covered()); assertEquals(0, d.snapshot().deliveredTotal()); assertEquals(0, d.snapshot().fulfilled());
        assertEquals(16, f.registry.storage().index().free(slot(2), 1)); assertFalse(f.supply.hasCargo(order.id()));
        assertEquals(DeliveryOrder.State.RETURNED, f.supply.delivery(order.id()).state());
    }
    @Test void partialNativePickupAndDeliveryKeepCanonicalRemainders() {
        Fixture f = new Fixture(); f.stock(0, X, 64); f.stock(1, null, 0); f.stock(2, null, 0);
        var d = f.request(10, 64, Demand.GoalKind.DELIVERY, Lane.NORMAL);
        var order = f.supply.coverStock(d.id(), slot(0), X, 64, 1);
        f.move(f.supply.preparePickup(f.share(order).id(), order.id(), slot(1), 64, 1), 20);
        assertEquals(44, f.supply.orderShares(order.id()).stream().filter(s -> s.stage() == CoverageShare.Stage.RESERVED_STOCK).findFirst().orElseThrow().quantity());
        assertEquals(slot(1), f.cargo(order).slot()); assertEquals(20, f.cargo(order).quantity());
        f.move(f.supply.prepareTransfer(f.cargo(order).id(), slot(2), 20, 1), 7);
        assertEquals(13, f.cargo(order).quantity()); assertEquals(57, d.snapshot().covered());
        assertEquals(7, d.snapshot().fulfilled()); assertEquals(7, d.snapshot().deliveredTotal());
        f.deliver(order, 13); assertEquals(DeliveryOrder.State.TRANSFERRED, f.supply.delivery(order.id()).state());
        f.pickup(order, 44); f.deliver(order, 44);
        assertEquals(Demand.Status.COMPLETED, d.snapshot().status()); assertEquals(64, d.snapshot().fulfilled());
        assertEquals(DeliveryOrder.State.COMPLETED, f.supply.delivery(order.id()).state());
    }
    @Test void unknownCargoPreservesCoverAndCannotRepeatPickupOrTransfer() {
        Fixture f = new Fixture(); f.stock(0, X, 20); var d = f.request(10, 20, Demand.GoalKind.DELIVERY, Lane.NORMAL);
        var order = f.supply.coverStock(d.id(), slot(0), X, 20, 1); f.pickup(order, 10);
        var before = f.supply.snapshot(); f.registry.storage().index().unknown(slot(1)); f.supply.reconcile();
        assertEquals(before, f.supply.snapshot()); assertEquals(20, d.snapshot().covered());
        assertThrows(IllegalStateException.class, () -> f.supply.preparePickup(f.share(order).id(), order.id(), slot(1), 10, 1));
        f.stock(2, null, 0);
        assertThrows(IllegalStateException.class, () -> f.supply.prepareTransfer(f.cargo(order).id(), slot(2), 10, 1));
    }
    @Test void activeReturnRebindsActualBufferWithoutFalseSuccessAndCanRouteAgain() {
        Fixture f = new Fixture(); f.stock(0, X, 16); var d = f.request(10, 16, Demand.GoalKind.CONSUMPTION, Lane.NORMAL);
        var order = f.supply.coverStock(d.id(), slot(0), X, 16, 1); f.pickup(order, 16); f.supply.returnDelivery(order.id());
        f.stock(2, null, 0); f.move(f.supply.prepareReturn(f.cargo(order).id(), slot(2), 16, 1), 16);
        assertFalse(f.supply.hasCargo(order.id())); assertEquals(0, d.snapshot().deliveredTotal()); assertEquals(0, d.snapshot().fulfilled());
        var returned = f.supply.demandShares(d.id()).getFirst();
        assertEquals(slot(2), returned.slot()); assertNull(returned.sourceOrderId()); assertEquals(16, returned.quantity());
        assertEquals(16, f.registry.storage().reservations().get(returned.obligationId()).count());
        f.supply.retireDelivery(order.id()); var route = f.supply.routeReservedStock(returned.id());
        assertEquals(slot(2), route.source()); assertEquals(16, d.snapshot().covered());
    }
    @Test void deadCargoLosesCoverageWithoutDeliveryAndCancelBeforePickupReleasesStock() {
        Fixture f = new Fixture(); f.stock(0, X, 32); var d = f.request(10, 32, Demand.GoalKind.DELIVERY, Lane.NORMAL);
        var order = f.supply.coverStock(d.id(), slot(0), X, 32, 1); f.pickup(order, 16);
        f.registry.storage().index().unknown(slot(1)); f.supply.markDeliveryLost(order.id());
        assertEquals(0, d.snapshot().covered()); assertEquals(0, d.snapshot().deliveredTotal()); assertEquals(0, d.snapshot().fulfilled());
        assertEquals(DeliveryOrder.State.LOST, f.supply.delivery(order.id()).state()); assertFalse(f.supply.hasCargo(order.id()));
        var other = f.request(11, 16, Demand.GoalKind.DELIVERY, Lane.NORMAL);
        var planned = f.supply.coverStock(other.id(), slot(0), X, 16, 1); f.supply.cancel(other.id());
        assertEquals(DeliveryOrder.State.CANCELLED, f.supply.delivery(planned.id()).state());
        assertEquals(16, f.registry.storage().index().free(slot(0), 1));
    }
    @Test void abandonedPreparationReleasesCapacityWithoutMutatingCargo() {
        Fixture f = new Fixture(); f.stock(0, X, 16); f.stock(1, null, 0);
        var d = f.request(10, 16, Demand.GoalKind.DELIVERY, Lane.NORMAL); var order = f.supply.coverStock(d.id(), slot(0), X, 16, 1);
        var before = f.supply.snapshot(); long obligations = f.registry.admission().used(Resource.RESERVATIONS_AND_ALLOCATIONS);
        long coverage = f.registry.admission().used(Resource.COVERAGE_SHARES);
        try (var prepared = f.supply.preparePickup(f.share(order).id(), order.id(), slot(1), 16, 1)) {
            assertEquals(obligations + 1, f.registry.admission().used(Resource.RESERVATIONS_AND_ALLOCATIONS));
            assertEquals(coverage + 1, f.registry.admission().used(Resource.COVERAGE_SHARES)); assertEquals(before, f.supply.snapshot());
        }
        assertEquals(obligations, f.registry.admission().used(Resource.RESERVATIONS_AND_ALLOCATIONS));
        assertEquals(coverage, f.registry.admission().used(Resource.COVERAGE_SHARES)); assertEquals(before, f.supply.snapshot());
    }
    @Test void sourceConstraintPersistsAcrossCoverageAndCancellation() {
        Fixture f = new Fixture(); f.stock(0, X, 16);
        var d = f.supply.requestDelivery(id(10), COLONY, OWNER, X, 16, pos(2), List.of(slot(0).storage()), 1);
        f.supply.coverStock(d.id(), slot(0), X, 16, 1);
        assertEquals(List.of(slot(0).storage()), d.snapshot().sourceStorages());
        assertFalse(d.snapshot().acceptsSource(new StorageId("minecraft:overworld", id(999), 0)));
        f.supply.cancel(d.id()); assertEquals(List.of(slot(0).storage()), d.snapshot().sourceStorages());
    }

    @Test void cancelWorkRetainsBoundCourierUntilPhysicalReturn() {
        Fixture f = new Fixture(); f.stock(0, X, 16);
        f.registry.addCitizen(new io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord(id(300), COLONY, id(301), 1, null, null, null,
                "colonyloom:courier", Map.of(), Map.of("food", 20), io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Lifecycle.ALIVE,
                io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Admission.ACTIVE, io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Readiness.READY,
                0, Map.of(), pos(1), 0), proposed -> {});
        var d = f.request(10, 16, Demand.GoalKind.DELIVERY, Lane.NORMAL);
        var order = f.supply.coverStock(d.id(), slot(0), X, 16, 1);
        var board = f.registry.workBoard(); var work = board.createDelivery(id(302), COLONY, pos(2), 0, Lane.NORMAL);
        board.transition(work.id(), io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.READY, io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.NONE, "pickup");
        assertTrue(board.assign(work.id(), id(300))); f.supply.assignDelivery(order.id(), id(300), work.id());
        f.pickup(order, 16);
        var commands=new io.github.kpuctajluk.colonyloom.core.command.ColonyCommands(f.registry);
        commands.setProfessions(List.of(new io.github.kpuctajluk.colonyloom.core.citizen.ProfessionDefinition("colonyloom:builder",1,Set.of("colonyloom:construction"),Set.of())));
        var context=new io.github.kpuctajluk.colonyloom.core.command.ColonyCommands.CommandContext(OWNER,true,new io.github.kpuctajluk.colonyloom.core.command.ColonyCommands.PhysicalChecks() {
            public void validateTerritory(Territory territory) {}
            public void validateCitizenPosition(ColonyRuntime colony,WorldPosition position) {}
            public void validateRecovery(ColonyRuntime colony,List<io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord> citizens,List<io.github.kpuctajluk.colonyloom.core.citizen.BindingRegistry.Observation> observations) {}
        });
        assertThrows(IllegalStateException.class,()->commands.assignProfession(context,id(300),"colonyloom:builder"));
        assertThrows(IllegalStateException.class,()->commands.removeCitizen(context,id(300)));
        assertEquals("colonyloom:courier",f.registry.citizen(id(300)).professionId());assertTrue(f.supply.hasCargo(order.id()));
        board.cancel(work.id());
        assertFalse(work.terminal()); assertEquals(id(300), work.assignee()); assertEquals(work.id(), f.registry.citizen(id(300)).assignedWorkId());
        assertEquals(DeliveryOrder.State.RETURNING, f.supply.delivery(order.id()).state());
        f.stock(2, null, 0); f.move(f.supply.prepareReturn(f.cargo(order).id(), slot(2), 16, 1), 16);
        board.cancel(work.id()); assertTrue(work.terminal()); assertNull(work.assignee()); assertNull(f.registry.citizen(id(300)).assignedWorkId());
        assertEquals(0, d.snapshot().fulfilled()); assertEquals(0, d.snapshot().deliveredTotal());
        assertEquals("colonyloom:builder",commands.assignProfession(context,id(300),"colonyloom:builder").professionId());
    }
    @Test void acceptedWorldDropsObsoletePromisesWithoutChangingNativeCargoObservation() {
        Fixture f = new Fixture(); f.stock(0, X, 16); var d = f.request(10, 16, Demand.GoalKind.DELIVERY, Lane.NORMAL);
        var order = f.supply.coverStock(d.id(), slot(0), X, 16, 1); f.pickup(order, 16);
        var cargo = f.registry.storage().index().observation(slot(1)); f.supply.acceptWorld(COLONY);
        assertEquals(cargo, f.registry.storage().index().observation(slot(1))); assertEquals(0, d.snapshot().covered());
        assertEquals(Demand.Status.CANCELLED, d.snapshot().status()); assertEquals(0, d.snapshot().fulfilled());
        assertEquals(0, d.snapshot().deliveredTotal()); assertFalse(f.supply.hasCargo(order.id()));
        assertEquals(DeliveryOrder.State.CANCELLED, f.supply.delivery(order.id()).state());
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
