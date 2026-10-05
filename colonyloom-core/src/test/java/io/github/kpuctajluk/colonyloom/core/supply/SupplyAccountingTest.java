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
            var workshopStorage = new StorageId("minecraft:overworld", id(400), 0);
            var registration = registry.storage().register(COLONY, pos(4), "workshop", List.of(workshopStorage), List.of(new StockRegion(workshopStorage,0),new StockRegion(workshopStorage,1)), List.of(pos(4)));
            var workshop = registry.storage().registerWorkshop(COLONY,pos(5),registration.id());
            registry.addCitizen(new io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord(id(401),COLONY,id(402),1,null,workshop.id(),null,"colonyloom:carpenter",Map.of(),Map.of("food",20),io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Lifecycle.ALIVE,io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Admission.ACTIVE,io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Readiness.READY,0,Map.of("food",1200L),pos(5),0),proposed->{});
            registry.bindings().observe(id(401),id(402),1);
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
    @Test void cancelledFoodRetainsDeliveryReferenceUntilSafeOrderRetirement() {
        Fixture f = new Fixture(); f.stock(0, X, 3);
        var work = f.registry.workBoard().createFood(id(1000), id(401));
        var demand = f.supply.request(id(1100), COLONY, work.id(), new ItemMatcher(X.itemId(), X), 1,
                Demand.GoalKind.CONSUMPTION, pos(5), Lane.CRITICAL, 10, 1);
        var order = f.supply.coverStock(demand.id(), slot(0), X, 1, 1);
        f.supply.cancel(demand.id()); f.registry.workBoard().cancel(work.id());
        assertThrows(IllegalStateException.class, () -> f.supply.retireFood(work.id()));
        assertEquals(Demand.Status.CANCELLED, f.supply.demand(demand.id()).snapshot().status());
        f.supply.retireDelivery(order.id()); f.supply.retireFood(work.id()); f.registry.workBoard().retire(work.id());
        assertEquals(3, f.registry.storage().index().free(slot(0), 1));
        assertEquals(0, f.registry.admission().used(Resource.WORKS));
        assertEquals(0, f.registry.admission().used(Resource.DEMANDS));
        assertEquals(0, f.registry.admission().used(Resource.DELIVERIES_AND_PRODUCTION_ORDERS));
    }

    @Test void terminalFoodRetirementPreservesLiveAllocationsAndWitnessesThenReclaimsItsLane() {
        Fixture f = new Fixture(); f.stock(0, X, 3);
        int initialWorks = f.registry.admission().used(Resource.WORKS);
        for (int wave = 0; wave < 3; wave++) {
            var work = f.registry.workBoard().createFood(id(1000 + wave), id(401));
            var demand = f.supply.request(id(1100 + wave), COLONY, work.id(), new ItemMatcher(X.itemId(), X), 1,
                    Demand.GoalKind.CONSUMPTION, pos(5), Lane.CRITICAL, 10, 1);
            var share = f.supply.allocateStock(demand.id(), slot(0), X, 1, 1);
            assertThrows(IllegalStateException.class, () -> f.supply.retireFood(work.id()));
            f.registry.workBoard().transition(work.id(), io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.COMPLETED,
                    io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.NONE, "consumed");
            assertThrows(IllegalStateException.class, () -> f.supply.retireFood(work.id()));
            f.supply.fulfillConsumption(share.id(), 1);
            f.stock(0, wave == 2 ? null : X, 2 - wave);
            var witness = new io.github.kpuctajluk.colonyloom.core.action.EffectRecord(id(1200 + wave), COLONY, work.id(), id(401), 1,
                    io.github.kpuctajluk.colonyloom.core.action.ActionContext.Kind.DEATH, pos(5), "inventory", X.itemId(), 1, 1,
                    io.github.kpuctajluk.colonyloom.core.action.EffectRecord.State.PREPARED, 0, null, null, null, null);
            f.registry.effects().prepare(witness, Lane.CRITICAL);
            assertThrows(IllegalStateException.class, () -> f.supply.retireFood(work.id()));
            assertEquals(Demand.Status.COMPLETED, f.supply.demand(demand.id()).snapshot().status());
            f.registry.effects().discardUnchanged(witness.operationId());
            f.supply.retireFood(work.id()); f.registry.workBoard().retire(work.id());
            assertThrows(IllegalArgumentException.class, () -> f.supply.demand(demand.id()));
            assertEquals(initialWorks, f.registry.admission().used(Resource.WORKS));
            assertEquals(0, f.registry.admission().used(Resource.DEMANDS));
            assertEquals(0, f.registry.admission().used(Resource.COVERAGE_SHARES));
            assertEquals(0, f.registry.admission().used(Resource.RESERVATIONS_AND_ALLOCATIONS));
        }
        assertEquals(0, f.registry.storage().index().free(slot(0), 1));
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
    @Test void restoredSpentDeliveryRejectsDuplicateSourcePublicationWithoutLosingHistory() {
        Fixture f=new Fixture();f.stock(0,X,8);f.stock(2,null,0);
        var demand=f.request(10,8,Demand.GoalKind.DELIVERY,Lane.NORMAL);
        var order=f.supply.coverStock(demand.id(),slot(0),X,8,1);
        f.pickup(order,8);
        var cargo=f.cargo(order);
        try(var original=f.supply.prepareTransfer(cargo.id(),slot(2),8,1)) {
            f.stock(2,X,8);original.commit(8);f.stock(1,null,0);
            // Consumer spent the physical contents before any wakeup/rescan replay.
            f.stock(2,null,0);
            var snapshot=f.registry.snapshot();
            var restored=new ColonyRegistry(() -> {});restored.restore(snapshot);
            var history=restored.supply().demand(demand.id()).snapshot();
            assertEquals(8,history.deliveredTotal());assertEquals(8,history.fulfilled());
            assertEquals(8,restored.supply().delivery(order.id()).transferred());
            assertThrows(IllegalStateException.class,()->original.commit(8));
            assertThrows(IllegalStateException.class,()->restored.supply().prepareTransfer(cargo.id(),slot(2),8,2));
            restored.supply().reconcile();
            assertEquals(history,restored.supply().demand(demand.id()).snapshot());
            assertEquals(8,restored.supply().delivery(order.id()).transferred());
        }
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
    private static StockRegion workshopSlot(int index) { return new StockRegion(new StorageId("minecraft:overworld",id(400),0),index); }
    private static void begin(Fixture f, UUID productionId) {
        var p=f.supply.production(productionId); var board=f.registry.workBoard();
        var work=board.createProduction(id(450),COLONY,p.equipmentPosition(),p.recipe().professionId(),0,Lane.NORMAL);
        f.supply.assignProductionWork(p.id(),work.id()); board.transition(work.id(),io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.READY,io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.NONE,"production");
        assertTrue(board.assign(work.id(),id(401))); board.transition(work.id(),io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.RUNNING,io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.NONE,"production");
        f.supply.startProduction(p.id(),id(401),work.id());
    }
    private static Fixture fragmentedProductionInputs(int distinctSlots) {
        Fixture f = new Fixture();
        var slots = java.util.stream.IntStream.range(0, 17).mapToObj(SupplyAccountingTest::workshopSlot).toList();
        f.registry.storage().register(COLONY, pos(4), "workshop", List.of(workshopSlot(0).storage()), slots, List.of(pos(4)));
        var recipe = RecipeDefinition.create("colonyloom:fragmented", 1, "colonyloom:carpenter", "minecraft:crafting_table",
                List.of(new RecipeDefinition.Ingredient(new ItemMatcher(Y.itemId(), null), 16)), X, 1, 20);
        var root = f.request(10, 1, Demand.GoalKind.CONSUMPTION, Lane.NORMAL);
        var production = f.supply.promiseProduction(root.id(), recipe, 1);
        var child = f.supply.ingredientDemand(production, 0, 16, 1);
        for (int index = 0; index < 17; index++) {
            int count = index < distinctSlots ? (index == 0 ? 17 - distinctSlots : 1) : 0;
            f.registry.storage().index().observe(workshopSlot(index), count == 0 ? null : Y, count, 1);
            if (count > 0) f.supply.allocateStock(child.id(), workshopSlot(index), Y, count, 1);
        }
        return f;
    }
    @Test void sixteenDistinctInputSlotsCannotBindOrStartAndRetainAllocatedProperty() {
        Fixture f = fragmentedProductionInputs(16);
        var production = f.supply.productionOrders().getFirst();
        var child = f.supply.ingredientDemand(production, 0, 16, 1);
        var before = f.supply.snapshot();
        var allocations = f.registry.storage().allocations().entries();
        var observations = java.util.stream.IntStream.range(0, 17)
                .mapToObj(index -> f.registry.storage().index().observation(workshopSlot(index))).toList();
        assertEquals(16, child.snapshot().allocated());
        assertEquals(16, allocations.size());
        assertTrue(f.supply.completeProductionKit(production.id()).isEmpty());
        var board = f.registry.workBoard();
        var work = board.createProduction(id(450), COLONY, production.equipmentPosition(), production.recipe().professionId(), 0, Lane.NORMAL);
        assertThrows(IllegalStateException.class, () -> f.supply.assignProductionWork(production.id(), work.id()));
        assertThrows(IllegalStateException.class, () -> f.supply.startProduction(production.id(), id(401), work.id()));
        assertNull(work.assignee());
        assertNull(f.registry.citizen(id(401)).assignedWorkId());
        assertFalse(f.supply.production(production.id()).batchStarted());
        assertEquals(before, f.supply.snapshot());
        assertEquals(allocations, f.registry.storage().allocations().entries());
        assertEquals(observations, java.util.stream.IntStream.range(0, 17)
                .mapToObj(index -> f.registry.storage().index().observation(workshopSlot(index))).toList());
    }
    @Test void fifteenDistinctInputSlotsLeaveRoomForOneOutputAndCompleteBatch() {
        Fixture f = fragmentedProductionInputs(15);
        var production = f.supply.productionOrders().getFirst();
        var child = f.supply.ingredientDemand(production, 0, 16, 1);
        var kit = f.supply.completeProductionKit(production.id());
        assertEquals(15, kit.stream().map(SupplyRegistry.InputPortion::slot).distinct().count());
        assertEquals(16, kit.stream().mapToInt(SupplyRegistry.InputPortion::count).sum());
        begin(f, production.id());
        f.supply.advanceProduction(production.id(), 20);
        try (var prepared = f.supply.prepareProduction(production.id(), List.of(new SupplyRegistry.OutputPortion(workshopSlot(15), 1)), 1)) {
            assertTrue(prepared.unchanged());
            f.registry.storage().index().observe(workshopSlot(15), X, 1, 1);
            prepared.commit();
        }
        for (int index = 0; index < 15; index++) f.registry.storage().index().observe(workshopSlot(index), null, 0, 1);
        f.supply.reconcile();
        assertEquals(16, child.snapshot().fulfilled());
        assertEquals(0, child.snapshot().allocated());
        assertEquals(1, f.supply.production(production.id()).completedBatches());
        assertEquals(1, f.registry.storage().index().observation(workshopSlot(15)).count());
        assertEquals(X, f.registry.storage().index().observation(workshopSlot(15)).item());
        assertEquals(0, f.registry.storage().index().free(workshopSlot(15), 1));
        assertEquals(1, f.supply.demand(id(10)).snapshot().covered());
        assertTrue(f.registry.storage().allocations().entries().isEmpty());
    }
    @Test void sharedStartedBatchSurvivesCancellationAndCommitsOnePhysicalOutput() {
        Fixture f=new Fixture(); var a=f.request(10,3,Demand.GoalKind.CONSUMPTION,Lane.NORMAL); var b=f.request(11,1,Demand.GoalKind.CONSUMPTION,Lane.NORMAL);
        var p=f.supply.promiseProduction(a.id(),f.recipe(4),1); assertTrue(f.supply.sharedOutput(b.id()));
        var child=f.supply.ingredientDemand(p,0,1,1); f.registry.storage().index().observe(workshopSlot(0),Y,1,1); f.registry.storage().index().observe(workshopSlot(1),null,0,1);
        f.supply.allocateStock(child.id(),workshopSlot(0),Y,1,1); begin(f,p.id()); f.supply.advanceProduction(p.id(),7);
        assertEquals(13,f.supply.production(p.id()).remainingActiveTicks()); f.supply.cancel(a.id());
        assertEquals(1,f.supply.production(p.id()).batches()); assertTrue(f.supply.production(p.id()).batchStarted()); assertEquals(1,b.snapshot().covered()); assertEquals(1,child.snapshot().allocated());
        f.supply.advanceProduction(p.id(),13);
        try(var prepared=f.supply.prepareProduction(p.id(),List.of(new SupplyRegistry.OutputPortion(workshopSlot(1),4)),1)) {
            assertTrue(prepared.unchanged()); f.registry.storage().index().observe(workshopSlot(1),X,4,1); prepared.commit();
            assertThrows(IllegalStateException.class,prepared::commit);
        }
        f.registry.storage().index().observe(workshopSlot(0),null,0,1); f.supply.reconcile();
        assertEquals(1,child.snapshot().fulfilled()); assertEquals(0,child.snapshot().allocated()); assertEquals(1,b.snapshot().covered());
        assertEquals(0,f.registry.storage().index().free(workshopSlot(1),1)); assertEquals(1,f.supply.production(p.id()).completedBatches()); assertEquals(0,f.supply.production(p.id()).batches());
        var surplus=f.supply.demands().stream().filter(d -> f.supply.productionSurplus(d.id())).findFirst().orElseThrow();
        assertEquals(3,surplus.snapshot().covered()); assertEquals(0,surplus.snapshot().allocated());
        var reserved=f.supply.demandShares(surplus.id()).getFirst(); var delivery=f.supply.routeReservedStock(reserved.id());
        assertEquals(pos(0),delivery.destination()); assertThrows(IllegalStateException.class,()->f.supply.cancel(surplus.id()));
        assertThrows(IllegalStateException.class,()->f.supply.release(reserved.id()));
        var saved=f.supply.snapshot(); var storage=f.registry.storage().snapshot(); var replacement=new AdmissionLedger(SimulationLimits.development(),()->{});
        try(var restored=f.supply.prepareRestore(saved,storage,replacement,f.registry.colonies())) { restored.commit(); }
        assertEquals(1,f.supply.demand(b.id()).snapshot().covered()); assertEquals(3,f.supply.demand(surplus.id()).snapshot().covered());
        f.pickup(delivery,3); f.deliver(delivery,3);
        assertEquals(1,f.registry.storage().index().observation(workshopSlot(1)).count());
        assertEquals(3,f.registry.storage().index().free(slot(2),1)); assertEquals(0,f.supply.demand(surplus.id()).snapshot().covered());
        assertEquals(3,f.supply.demand(surplus.id()).snapshot().fulfilled()); assertEquals(3,f.supply.demand(surplus.id()).snapshot().deliveredTotal());
        assertEquals(1,f.supply.demand(b.id()).snapshot().covered()); assertEquals(DeliveryOrder.State.COMPLETED,f.supply.delivery(delivery.id()).state());
    }
    static Fixture producedFixture(long first, long second) { return producedFixture(first, second, false); }
    static Fixture producedFixture(long first, long second, boolean local) {
        Fixture f = new Fixture(); var a = f.supply.request(id(10), COLONY, OWNER, new ItemMatcher(X.itemId(), null), first,
                Demand.GoalKind.CONSUMPTION, local ? pos(4) : pos(2), Lane.NORMAL, 10, 1);
        var p = f.supply.promiseProduction(a.id(), f.recipe(first + second), 1);
        if (second > 0) { var b = f.request(11, second, Demand.GoalKind.CONSUMPTION, Lane.NORMAL); assertTrue(f.supply.sharedOutput(b.id())); }
        var child = f.supply.ingredientDemand(p, 0, 1, 1);
        f.registry.storage().index().observe(workshopSlot(0), Y, 1, 1); f.registry.storage().index().observe(workshopSlot(1), null, 0, 1);
        f.supply.allocateStock(child.id(), workshopSlot(0), Y, 1, 1); begin(f, p.id()); f.supply.advanceProduction(p.id(), 20);
        try (var prepared = f.supply.prepareProduction(p.id(), List.of(new SupplyRegistry.OutputPortion(workshopSlot(1), Math.toIntExact(first + second))), 1)) {
            f.registry.storage().index().observe(workshopSlot(1), X, first + second, 1); prepared.commit();
        }
        f.registry.storage().index().observe(workshopSlot(0), null, 0, 1); f.supply.reconcile(); return f;
    }
    static Demand surplus(Fixture f) { return f.supply.demands().stream().filter(d -> f.supply.productionSurplus(d.id())).findFirst().orElseThrow(); }
    static void restoreAccounting(Fixture f) {
        var saved = f.supply.snapshot(); var storage = f.registry.storage().snapshot();
        try (var restore = f.supply.prepareRestore(saved, storage, new AdmissionLedger(SimulationLimits.development(), () -> {}), f.registry.colonies())) { restore.commit(); }
        assertEquals(saved, f.supply.snapshot()); assertEquals(storage, f.registry.storage().snapshot());
    }
    @Test void cancellationAfterCraftReownsOnlyReleasedProducedShareUntilWarehouseHandoff() {
        Fixture f = producedFixture(3, 1); UUID producer = f.supply.productionOrders().getFirst().id();
        var sibling = f.supply.demandShares(id(11)).getFirst(); var original = f.supply.demandShares(id(10)).getFirst();
        assertEquals(producer, original.productionOrderId()); assertNull(original.sourceOrderId());
        f.registry.storage().index().unknown(workshopSlot(1)); f.supply.cancel(id(10));
        assertEquals(Demand.Status.CANCELLED, f.supply.demand(id(10)).snapshot().status()); assertEquals(0, f.supply.demand(id(10)).snapshot().fulfilled());
        assertEquals(sibling, f.supply.demandShares(id(11)).getFirst()); assertEquals(0, f.supply.demand(id(10)).snapshot().covered());
        var goal = surplus(f); var owned = f.supply.demandShares(goal.id()).getFirst();
        assertEquals(original.id(), owned.id()); assertEquals(producer, owned.productionOrderId()); assertEquals(3, goal.snapshot().covered());
        assertEquals(goal.id(), f.registry.storage().reservations().get(owned.obligationId()).ownerId()); assertEquals(4, f.registry.storage().obligated(workshopSlot(1)));
        restoreAccounting(f); f.registry.storage().index().observe(workshopSlot(1), X, 4, 1);
        var route = f.supply.routeReservedStock(owned.id()); f.pickup(route, 3); f.deliver(route, 3);
        assertEquals(0, f.supply.demand(id(10)).snapshot().fulfilled()); assertEquals(3, f.supply.demand(goal.id()).snapshot().fulfilled());
        assertEquals(1, f.registry.storage().obligated(workshopSlot(1))); assertEquals(3, f.registry.storage().index().free(slot(2), 1));
    }
    @Test void producedReductionSplitAdmissionFailuresLeaveExactOriginalAccounting() {
        for (var resource : List.of(Resource.DEMANDS, Resource.COVERAGE_SHARES, Resource.RESERVATIONS_AND_ALLOCATIONS)) {
            Fixture f = producedFixture(4, 1); var saved = f.supply.snapshot(); var storage = f.registry.storage().snapshot();
            var used = f.registry.admission().used(resource);
            f.registry.admission().updateLimits(SimulationLimits.development().withResource(resource, used));
            assertThrows(AdmissionLedger.AdmissionException.class, () -> f.supply.reduceRequired(id(10), 2), resource.name());
            assertEquals(saved, f.supply.snapshot()); assertEquals(storage, f.registry.storage().snapshot()); assertEquals(used, f.registry.admission().used(resource));
            f.registry.admission().updateLimits(SimulationLimits.development()); f.supply.reduceRequired(id(10), 2);
            assertEquals(2, f.supply.demand(id(10)).snapshot().covered()); assertEquals(2, surplus(f).snapshot().covered());
            assertEquals(1, f.supply.demand(id(11)).snapshot().covered()); assertEquals(5, f.registry.storage().obligated(workshopSlot(1))); restoreAccounting(f);
        }
        Fixture cancelled = producedFixture(4, 0); var before = cancelled.supply.snapshot(); var stock = cancelled.registry.storage().snapshot();
        cancelled.registry.admission().updateLimits(SimulationLimits.development().withResource(Resource.DEMANDS, cancelled.registry.admission().used(Resource.DEMANDS)));
        assertThrows(AdmissionLedger.AdmissionException.class, () -> cancelled.supply.cancel(id(10)));
        assertEquals(before, cancelled.supply.snapshot()); assertEquals(stock, cancelled.registry.storage().snapshot());
    }
    @Test void deliveredMovedAndConsumedSharesKeepProvenanceForReleasedRemainder() {
        Fixture f = producedFixture(4, 0); UUID producer = f.supply.productionOrders().getFirst().id();
        var route = f.supply.routeReservedStock(f.supply.demandShares(id(10)).getFirst().id()); f.pickup(route, 4); f.deliver(route, 4);
        var allocation = f.supply.demandShares(id(10)).getFirst(); assertEquals(producer, allocation.productionOrderId()); f.stock(0, null, 0);
        try (var move = f.supply.prepareAllocationMove(allocation.id(), slot(0), 2, 1)) { f.stock(0, X, 2); move.commit(2); f.stock(2, X, 2); }
        for (var share : f.supply.demandShares(id(10))) assertEquals(producer, share.productionOrderId());
        var moved = f.supply.demandShares(id(10)).stream().filter(s -> slot(0).equals(s.slot())).findFirst().orElseThrow();
        f.supply.fulfillConsumption(moved.id(), 1); f.stock(0, X, 1); f.supply.cancel(id(10));
        assertEquals(1, f.supply.demand(id(10)).snapshot().fulfilled()); assertEquals(0, f.supply.demand(id(10)).snapshot().allocated());
        assertEquals(3, f.supply.demands().stream().filter(d -> f.supply.productionSurplus(d.id())).mapToLong(d -> d.snapshot().covered()).sum());
        assertEquals(1, f.registry.storage().obligated(slot(0))); assertEquals(2, f.registry.storage().obligated(slot(2)));
        for (var share : f.supply.shares()) if (share.item().equals(X)) assertEquals(producer, share.productionOrderId()); restoreAccounting(f);
    }
    @Test void cancelledProducedCargoRetainsSurplusUntilActualSafeSinkAndRestoresExactly() {
        Fixture f = producedFixture(4, 0); var route = f.supply.routeReservedStock(f.supply.demandShares(id(10)).getFirst().id()); f.pickup(route, 4);
        f.supply.cancel(id(10)); var goal = surplus(f); var cargo = f.cargo(route);
        assertEquals(goal.id(), cargo.demandId()); assertEquals(4, goal.snapshot().covered()); assertEquals(0, f.supply.demand(id(10)).snapshot().covered());
        assertEquals(DeliveryOrder.State.RETURNING, f.supply.delivery(route.id()).state()); restoreAccounting(f);
        assertThrows(IllegalArgumentException.class, () -> f.supply.prepareReturn(cargo.id(), workshopSlot(1), 4, 1));
        f.stock(2, null, 0); f.move(f.supply.prepareReturn(cargo.id(), slot(2), 2, 1), 2);
        assertEquals(DeliveryOrder.State.RETURNING, f.supply.delivery(route.id()).state()); assertEquals(2, f.supply.demand(goal.id()).snapshot().covered());
        assertEquals(2, f.supply.demand(goal.id()).snapshot().fulfilled()); restoreAccounting(f);
        f.move(f.supply.prepareReturn(f.cargo(route).id(), slot(2), 2, 1), 2);
        assertEquals(DeliveryOrder.State.RETURNED, f.supply.delivery(route.id()).state()); assertEquals(4, f.supply.demand(goal.id()).snapshot().fulfilled());
        assertEquals(0, f.supply.demand(id(10)).snapshot().fulfilled()); assertEquals(0, f.supply.demand(id(10)).snapshot().deliveredTotal());
        assertEquals(4, f.registry.storage().index().free(slot(2), 1)); restoreAccounting(f);
    }
    @Test void localProducedAllocationAndPartialCargoReductionRetainProperty() {
        Fixture local = producedFixture(4, 0, true); var allocation = local.supply.demandShares(id(10)).getFirst();
        assertEquals(CoverageShare.Stage.ALLOCATED, allocation.stage()); assertNotNull(allocation.productionOrderId());
        local.supply.cancel(id(10)); var released = local.supply.demandShares(surplus(local).id()).getFirst();
        assertEquals(CoverageShare.Stage.RESERVED_STOCK, released.stage()); assertEquals(allocation.productionOrderId(), released.productionOrderId());
        assertEquals(4, local.registry.storage().obligated(workshopSlot(1))); assertEquals(0, local.registry.storage().index().free(workshopSlot(1), 1)); restoreAccounting(local);
        Fixture transit = producedFixture(4, 1); var sibling = transit.supply.demandShares(id(11)).getFirst();
        var route = transit.supply.routeReservedStock(transit.supply.demandShares(id(10)).getFirst().id()); transit.pickup(route, 4);
        transit.supply.reduceRequired(id(10), 2); var goal = surplus(transit);
        assertEquals(2, transit.supply.demand(id(10)).snapshot().covered()); assertEquals(2, goal.snapshot().covered());
        assertEquals(sibling, transit.supply.demandShares(id(11)).getFirst()); assertEquals(4, transit.registry.storage().obligated(slot(1))); restoreAccounting(transit);
        var surplusCargo = transit.supply.demandShares(goal.id()).getFirst(); transit.stock(2, null, 0);
        transit.move(transit.supply.prepareReturn(surplusCargo.id(), slot(2), 2, 1), 2);
        assertEquals(0, transit.supply.demand(id(10)).snapshot().fulfilled()); assertEquals(2, transit.supply.demand(id(10)).snapshot().covered());
        transit.move(transit.supply.prepareReturn(transit.cargo(route).id(), slot(2), 2, 1), 2);
        assertEquals(2, transit.supply.demand(id(10)).snapshot().covered()); assertEquals(2, transit.registry.storage().obligated(slot(2)));
        assertEquals(2, transit.supply.demand(goal.id()).snapshot().fulfilled()); assertNull(transit.supply.demandShares(id(10)).getFirst().sourceOrderId()); restoreAccounting(transit);
    }

    @Test void workIndexesFollowAssignmentAndRestoreWithoutLinearLookupStaleEntries() {
        Fixture f=new Fixture();var demand=f.request(10,4,Demand.GoalKind.CONSUMPTION,Lane.NORMAL);var production=f.supply.promiseProduction(demand.id(),f.recipe(4),1);
        var work=f.registry.workBoard().createProduction(id(900),COLONY,pos(5),"colonyloom:carpenter",0,Lane.NORMAL);f.registry.workBoard().transition(work.id(),io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.READY,io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.NONE,"production");
        f.registry.storage().index().observe(workshopSlot(0),Y,1,1);
        var child=f.supply.ingredientDemand(production,0,1,1);f.supply.allocateStock(child.id(),workshopSlot(0),Y,1,1);
        f.supply.assignProductionWork(production.id(),work.id());assertSame(f.supply.production(production.id()),f.supply.productionForWork(work.id()));
        var saved=f.supply.snapshot();var storage=f.registry.storage().snapshot();var replacement=new AdmissionLedger(SimulationLimits.development(),()->{});
        try(var restored=f.supply.prepareRestore(saved,storage,replacement,f.registry.colonies())){restored.commit();}
        assertSame(f.supply.production(production.id()),f.supply.productionForWork(work.id()));
        f.stock(0,X,8);var deliveryGoal=f.request(11,8,Demand.GoalKind.DELIVERY,Lane.NORMAL);var delivery=f.supply.coverStock(deliveryGoal.id(),slot(0),X,8,1);
        var route=f.registry.workBoard().createDelivery(id(901),COLONY,pos(0),0,Lane.NORMAL);
        f.supply.assignDelivery(delivery.id(),null,route.id());assertSame(f.supply.delivery(delivery.id()),f.supply.deliveryForWork(route.id()));
        f.supply.assignDelivery(delivery.id(),null,null);assertNull(f.supply.deliveryForWork(route.id()));
        f.supply.assignDelivery(delivery.id(),null,route.id());restoreAccounting(f);
        assertSame(f.supply.delivery(delivery.id()),f.supply.deliveryForWork(route.id()));
        f.supply.cancel(deliveryGoal.id());f.registry.workBoard().cancel(route.id());f.supply.retireDelivery(delivery.id());assertNull(f.supply.deliveryForWork(route.id()));
    }
    private static void consumeProducedOutput(Fixture f, io.github.kpuctajluk.colonyloom.core.production.ProductionOrder producer) {
        var promised = f.supply.demandShares(producer.ownerDemandId()).stream()
                .filter(share -> producer.id().equals(share.productionOrderId()) && share.stage() == CoverageShare.Stage.RESERVED_STOCK).findFirst().orElseThrow();
        var delivery = f.supply.routeReservedStock(promised.id()); int quantity = Math.toIntExact(promised.quantity());
        f.pickup(delivery, quantity); f.deliver(delivery, quantity);
        var allocation = f.supply.demandShares(producer.ownerDemandId()).stream()
                .filter(share -> producer.id().equals(share.productionOrderId()) && share.stage() == CoverageShare.Stage.ALLOCATED).findFirst().orElseThrow();
        f.supply.fulfillConsumption(allocation.id(), quantity); f.stock(2, null, 0); f.supply.retireDelivery(delivery.id());
    }

    @Test void terminalProductionRetirementWaitsForNativeWitnessAndLiveOutput() {
        Fixture f = producedFixture(4, 0);
        var order = f.supply.productionOrders().getFirst(); var work = f.registry.workBoard().work(order.workId());
        f.registry.workBoard().transition(work.id(), io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.COMPLETED,
                io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.NONE, "finished");
        assertFalse(f.supply.canRetireProduction(order.id()));
        assertThrows(IllegalStateException.class, () -> f.supply.retireProduction(order.id()));
        consumeProducedOutput(f, order);
        assertTrue(f.supply.canRetireProduction(order.id()));
        assertEquals(1, f.supply.terminalProductionCount());
        restoreAccounting(f);
        assertEquals(1, f.supply.terminalProductionCount());
        assertTrue(f.supply.canRetireProduction(order.id()));
        var workshop = f.registry.storage().workshops().stream().filter(value -> value.id().equals(order.workshopId())).findFirst().orElseThrow();
        var registration = f.registry.storage().registrations().stream().filter(value -> value.id().equals(workshop.registrationId())).findFirst().orElseThrow();
        var craft = new io.github.kpuctajluk.colonyloom.core.action.EffectRecord.Craft(order.id(), 0, workshop.id(),
                registration.id(), workshop.revision(), workshop.position(), registration.address(), order.recipe().id(), order.recipe().version(),
                order.recipe().digest(), List.of(new io.github.kpuctajluk.colonyloom.core.action.EffectRecord.CraftSlot(
                workshopSlot(0), Y, 1, Y, 1, 1)), X, 4, List.of(new io.github.kpuctajluk.colonyloom.core.action.EffectRecord.CraftSlot(
                workshopSlot(1), null, 0, null, 0, 4)), io.github.kpuctajluk.colonyloom.core.action.EffectRecord.CraftPhase.PREPARED);
        var evidence = new io.github.kpuctajluk.colonyloom.core.action.EffectRecord(id(1200), COLONY, work.id(), id(401), 1,
                io.github.kpuctajluk.colonyloom.core.action.ActionContext.Kind.RECIPE_CRAFT, workshop.position(), "minecraft:crafting_table",
                X.itemId(), 0, 0, io.github.kpuctajluk.colonyloom.core.action.EffectRecord.State.PREPARED, 0, null, craft, null, null);
        f.registry.effects().prepare(evidence, Lane.NORMAL);
        assertFalse(f.supply.canRetireProduction(order.id()));
        assertThrows(IllegalStateException.class, () -> f.supply.retireProduction(order.id()));
        f.registry.effects().discardUnchanged(evidence.operationId());
        assertTrue(f.supply.canRetireProduction(order.id()));
    }

    @Test void detachedTerminalProductionReclaimsExactLanesAndRestoresWithoutDanglingIndexes() {
        Fixture f = producedFixture(4, 0);
        var order = f.supply.productionOrders().getFirst(); var work = f.registry.workBoard().work(order.workId());
        f.registry.workBoard().transition(work.id(), io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.COMPLETED,
                io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.NONE, "finished");
        consumeProducedOutput(f, order);
        f.supply.retireProduction(order.id()); f.registry.workBoard().retire(work.id());
        assertNull(f.supply.findProduction(order.id()));
        assertNull(f.supply.productionForWork(work.id()));
        assertEquals(0, f.supply.productionCount());
        assertEquals(0, f.registry.admission().used(Resource.DELIVERIES_AND_PRODUCTION_ORDERS));
        assertEquals(1, f.registry.admission().used(Resource.DEMANDS));
        assertEquals(1, f.registry.admission().used(Resource.COVERAGE_SHARES));
        assertEquals(0, f.registry.admission().used(Resource.RESERVATIONS_AND_ALLOCATIONS));
        var saved = f.supply.snapshot(); var storage = f.registry.storage().snapshot();
        try (var restored = f.supply.prepareRestore(saved, storage, new AdmissionLedger(SimulationLimits.development(), () -> {}), f.registry.colonies())) {
            restored.commit();
        }
        assertEquals(saved, f.supply.snapshot()); assertEquals(storage, f.registry.storage().snapshot());
        assertEquals(0, f.supply.productionCount()); assertNull(f.supply.productionForWork(work.id()));
        assertEquals(0, f.supply.terminalProductionCount());
    }

    @Test void cancelledNestedProducerRetiresBeforeItsParentIngredientDemand() {
        Fixture f = new Fixture();
        var root = f.request(10, 1, Demand.GoalKind.CONSUMPTION, Lane.NORMAL);
        var outer = f.supply.promiseProduction(root.id(), f.recipe(1), 1);
        var intermediate = RecipeDefinition.create("colonyloom:intermediate", 1, "colonyloom:carpenter", "minecraft:crafting_table",
                List.of(new RecipeDefinition.Ingredient(new ItemMatcher("minecraft:gravel", null), 1)), Y, 1, 20);
        var inner = f.supply.promiseProduction(f.supply.ingredientDemand(outer, 0, 1, 1).id(), intermediate, 1);
        f.supply.cancel(root.id());
        assertEquals(2, f.supply.terminalProductionCount());
        assertFalse(f.supply.canRetireProduction(outer.id()));
        assertThrows(IllegalStateException.class, () -> f.supply.retireProduction(outer.id()));
        f.supply.retireProduction(inner.id());
        assertTrue(f.supply.canRetireProduction(outer.id()));
        f.supply.retireProduction(outer.id());
        assertEquals(0, f.supply.productionCount());
        assertEquals(0, f.supply.terminalProductionCount());
        assertEquals(1, f.registry.admission().used(Resource.DEMANDS));
        assertEquals(Demand.Status.CANCELLED, f.supply.demand(root.id()).snapshot().status());
        restoreAccounting(f);
        assertNull(f.supply.findProduction(inner.id()));
        assertNull(f.supply.findProduction(outer.id()));
    }



    @Test void queuedCancellationShrinksSharedOrderWithoutDeletingSibling() {
        Fixture f=new Fixture(); var a=f.request(10,4,Demand.GoalKind.CONSUMPTION,Lane.NORMAL); var b=f.request(11,4,Demand.GoalKind.CONSUMPTION,Lane.NORMAL);
        var p=f.supply.promiseProduction(a.id(),f.recipe(4),1); var shared=f.supply.promiseProduction(b.id(),f.recipe(4),1);
        assertEquals(p.id(),shared.id()); assertEquals(2,shared.batches()); f.supply.cancel(a.id());
        assertEquals(1,f.supply.production(p.id()).batches()); assertEquals(4,b.snapshot().covered()); assertEquals(1,f.supply.ingredientDemand(f.supply.production(p.id()),0,1,1).snapshot().required());
    }
    @Test void changedAllocatedKitRefusesPreparedCommitAndRestoreKeepsExactProgress() {
        Fixture f=new Fixture(); var d=f.request(10,4,Demand.GoalKind.CONSUMPTION,Lane.NORMAL); var p=f.supply.promiseProduction(d.id(),f.recipe(4),1);
        var child=f.supply.ingredientDemand(p,0,1,1); f.registry.storage().index().observe(workshopSlot(0),Y,1,1); f.registry.storage().index().observe(workshopSlot(1),null,0,1);
        var allocation=f.supply.allocateStock(child.id(),workshopSlot(0),Y,1,1); begin(f,p.id()); f.supply.advanceProduction(p.id(),9);
        var saved=f.supply.snapshot(); var storage=f.registry.storage().snapshot(); var replacement=new AdmissionLedger(SimulationLimits.development(),()->{});
        try(var restored=f.supply.prepareRestore(saved,storage,replacement,f.registry.colonies())) { restored.commit(); }
        assertEquals(11,f.supply.production(p.id()).remainingActiveTicks()); assertEquals(p.recipe(),f.supply.production(p.id()).recipe()); assertEquals(p.workshopId(),f.supply.production(p.id()).workshopId());
        f.supply.advanceProduction(p.id(),11);
        try(var prepared=f.supply.prepareProduction(p.id(),List.of(new SupplyRegistry.OutputPortion(workshopSlot(1),4)),1)) {
            f.registry.storage().reduceObligations(Map.of(allocation.obligationId(),0L)); assertFalse(prepared.unchanged()); assertThrows(IllegalStateException.class,prepared::commit);
        }
        assertEquals(4,f.supply.demand(d.id()).snapshot().covered()); assertEquals(0,f.supply.production(p.id()).completedBatches());
    }
    @Test void localAllocationMoveNeverIncreasesHistoricalDeliveryAndPreAdmissionRefusesChangedShare() {
        Fixture f=new Fixture(); f.stock(0,X,8); f.stock(2,null,0); var d=f.request(10,8,Demand.GoalKind.CONSUMPTION,Lane.NORMAL);
        var allocation=f.supply.allocateStock(d.id(),slot(0),X,8,1); assertEquals(0,d.snapshot().deliveredTotal());
        try(var move=f.supply.prepareAllocationMove(allocation.id(),slot(2),3,1)) { f.stock(2,X,3); move.commit(3); f.stock(0,X,5); }
        assertEquals(8,d.snapshot().allocated()); assertEquals(0,d.snapshot().deliveredTotal());
        var relocated=f.supply.demandShares(d.id()).stream().filter(s->slot(2).equals(s.slot())).findFirst().orElseThrow();
        try(var consumption=f.supply.prepareConsumption(relocated.id(),1)) { f.supply.reduceRequired(d.id(),0); assertThrows(IllegalStateException.class,()->consumption.commit(1)); }
        assertEquals(0,d.snapshot().fulfilled());
    }
    @Test void inactiveProducerCannotAdvanceAndCancellingAllStartedSharesStillCompletesSurplus() {
        Fixture f=new Fixture(); var d=f.request(10,4,Demand.GoalKind.CONSUMPTION,Lane.NORMAL); var p=f.supply.promiseProduction(d.id(),f.recipe(4),1);
        var child=f.supply.ingredientDemand(p,0,1,1); f.registry.storage().index().observe(workshopSlot(0),Y,1,1); f.registry.storage().index().observe(workshopSlot(1),null,0,1);
        f.supply.allocateStock(child.id(),workshopSlot(0),Y,1,1); begin(f,p.id());
        f.registry.bindings().unload(id(402)); assertThrows(IllegalStateException.class,()->f.supply.advanceProduction(p.id(),20)); assertEquals(20,f.supply.production(p.id()).remainingActiveTicks());
        f.registry.bindings().observe(id(401),id(402),1); f.supply.cancel(d.id()); assertEquals(1,child.snapshot().allocated()); f.supply.advanceProduction(p.id(),20);
        try(var prepared=f.supply.prepareProduction(p.id(),List.of(new SupplyRegistry.OutputPortion(workshopSlot(1),4)),1)) { f.registry.storage().index().observe(workshopSlot(1),X,4,1); prepared.commit(); }
        assertEquals(0,f.registry.storage().index().free(workshopSlot(1),1)); assertEquals(1,child.snapshot().fulfilled()); assertEquals(0,d.snapshot().covered());
        var surplus=f.supply.demands().stream().filter(goal -> f.supply.productionSurplus(goal.id())).findFirst().orElseThrow();
        assertEquals(4,surplus.snapshot().covered()); var delivery=f.supply.routeReservedStock(f.supply.demandShares(surplus.id()).getFirst().id());
        f.pickup(delivery,4); f.deliver(delivery,4); assertEquals(4,f.supply.demand(surplus.id()).snapshot().fulfilled());
        assertEquals(0,f.registry.storage().index().observation(workshopSlot(1)).count());
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
        assertThrows(IllegalArgumentException.class, () -> new CoverageShare(id(10), COLONY, id(11), null, null, null, null, X, 1, 0, CoverageShare.Stage.IN_TRANSIT));
    }
}
