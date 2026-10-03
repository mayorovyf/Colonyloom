package io.github.kpuctajluk.colonyloom.core.supply;

import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets;
import io.github.kpuctajluk.colonyloom.core.storage.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static io.github.kpuctajluk.colonyloom.core.supply.SupplyAccountingTest.*;
import static org.junit.jupiter.api.Assertions.*;

final class KitAllocatorTest {
    private static List<RecipeDefinition.Ingredient> xy() { return List.of(new RecipeDefinition.Ingredient(new ItemMatcher(X.itemId(), null), 1), new RecipeDefinition.Ingredient(new ItemMatcher(Y.itemId(), null), 1)); }
    private static KitAllocator.Candidate candidate(int slot, ItemDescriptor item) { return new KitAllocator.Candidate(slot(slot), item, 1, 7); }
    private static KitAllocator.Observation read(StockRegion slot) { return new KitAllocator.Observation(slot.slot() == 0 ? X : Y, 1, 7, true); }
    private static RecipeDefinition recipe() { return RecipeDefinition.create("colonyloom:xy", 1, "colonyloom:carpenter", "minecraft:crafting_table", xy(), new ItemDescriptor("minecraft:oak_stairs", new byte[0]), 1, 20); }
    @Test void incompleteXPlusYHoldsNothingAgainstCompetingWholeKit() {
        Fixture f = new Fixture(); f.stock(0, X, 1); f.stock(1, Y, 1);
        var a = f.request(10, 1, Demand.GoalKind.CONSUMPTION, Lane.NORMAL); var b = f.request(11, 1, Demand.GoalKind.CONSUMPTION, Lane.NORMAL);
        var kits = new KitAllocator(f.registry, f.supply);
        var incomplete = kits.prepare(a.id(), 0, xy(), List.of(candidate(0, X)), 1);
        assertFalse(incomplete.complete()); assertTrue(kits.finalizeKit(incomplete, KitAllocatorTest::read, 0, 1).isEmpty()); assertTrue(f.registry.storage().reservations().entries().isEmpty());
        var complete = kits.prepare(b.id(), 0, xy(), List.of(candidate(0, X), candidate(1, Y)), 1);
        assertTrue(complete.complete()); assertEquals(2, kits.finalizeKit(complete, KitAllocatorTest::read, 0, 1).size());
        assertEquals(0, f.registry.storage().index().free(slot(0), 1));
    }
    @Test void nativeDemandAndKitRevisionMismatchNeverLeakReservation() {
        Fixture f = new Fixture(); f.stock(0, X, 1); f.stock(1, Y, 1); var d = f.request(10, 2, Demand.GoalKind.CONSUMPTION, Lane.NORMAL);
        var kits = new KitAllocator(f.registry, f.supply); var kit = kits.prepare(d.id(), 7, xy(), List.of(candidate(0, X), candidate(1, Y)), 1);
        assertTrue(kits.finalizeKit(kit, s -> new KitAllocator.Observation(s.slot() == 0 ? X : Y, 1, 8, true), 7, 1).isEmpty());
        assertTrue(kits.finalizeKit(kit, KitAllocatorTest::read, 8, 1).isEmpty());
        f.supply.status(d.id(), Demand.Status.WAITING); assertTrue(kits.finalizeKit(kit, KitAllocatorTest::read, 7, 1).isEmpty());
        assertTrue(f.registry.storage().reservations().entries().isEmpty()); assertEquals(0, f.registry.admission().used(Resource.RESERVATIONS_AND_ALLOCATIONS));
    }
    @Test void failedWholeAdmissionRollsBackEveryLeaseAndMutationBarrier() {
        Fixture f = new Fixture(SimulationLimits.development().withResource(Resource.RESERVATIONS_AND_ALLOCATIONS, 2));
        f.stock(0, X, 1); f.stock(1, Y, 1); var d = f.request(10, 1, Demand.GoalKind.CONSUMPTION, Lane.NORMAL); var kits = new KitAllocator(f.registry, f.supply);
        var kit = kits.prepare(d.id(), 0, xy(), List.of(candidate(0, X), candidate(1, Y)), 1);
        assertTrue(kits.finalizeKit(kit, KitAllocatorTest::read, 0, 1).isEmpty()); assertEquals(0, f.registry.admission().used(Resource.RESERVATIONS_AND_ALLOCATIONS));
        assertEquals(1, f.registry.storage().index().free(slot(0), 1)); assertEquals(1, f.registry.storage().index().free(slot(1), 1));
    }
    @Test void portionsAreBoundedAndFinalNeverSearchesCandidates() {
        Fixture f = new Fixture(); f.stock(0, X, 1); f.stock(1, Y, 1); var d = f.request(10, 1, Demand.GoalKind.CONSUMPTION, Lane.NORMAL); var kits = new KitAllocator(f.registry, f.supply);
        var search = kits.begin(d.id(), 0, xy()); assertFalse(kits.search(search, List.of(candidate(0, X)), 1).complete());
        var kit = kits.search(search, List.of(candidate(1, Y)), 1); assertTrue(kit.complete()); int[] reads = {0};
        assertEquals(2, kits.finalizeKit(kit, s -> { reads[0]++; return read(s); }, 0, 1).size()); assertEquals(2, reads[0]); assertTrue(reads[0] <= KitAllocator.MAX_FINAL_CHECKS);
        assertThrows(IllegalArgumentException.class, () -> kits.prepare(d.id(), 0, xy(), Collections.nCopies(17, candidate(0, X)), 1));
    }
    @Test void lossReleasesAllUnusedKitButUnknownDoesNot() {
        Fixture f = new Fixture(); f.stock(0, X, 1); f.stock(1, Y, 1); var d = f.request(10, 1, Demand.GoalKind.CONSUMPTION, Lane.NORMAL); var kits = new KitAllocator(f.registry, f.supply);
        var kit = kits.prepare(d.id(), 0, xy(), List.of(candidate(0, X), candidate(1, Y)), 1); kits.finalizeKit(kit, KitAllocatorTest::read, 0, 1);
        f.registry.storage().index().unknown(slot(0)); kits.reconcile(); assertEquals(2, f.registry.storage().reservations().entries().size());
        f.stock(0, null, 0); kits.reconcile(); assertTrue(f.registry.storage().reservations().entries().isEmpty()); assertEquals(1, f.registry.storage().index().free(slot(1), 1));
    }
    @Test void productionKitPersistsSharesAtomicallyAndLossInvalidatesWholeKit() {
        Fixture f = new Fixture(); f.stock(0, X, 1); f.stock(1, Y, 1);
        var recipe = recipe(); var root = f.supply.request(id(10), COLONY, OWNER, new ItemMatcher(recipe.output().itemId(), null), 1, Demand.GoalKind.CONSUMPTION, pos(2), Lane.NORMAL, 10, 1);
        var order = f.supply.promiseProduction(root.id(), recipe, 1); var child = f.supply.ingredientDemand(order, 0, 1, 1); var kits = new KitAllocator(f.registry, f.supply);
        var partial = kits.prepare(child.id(), order.revision(), xy(), List.of(candidate(0, X)), 1);
        assertFalse(kits.finalizeProductionKit(partial, KitAllocatorTest::read, order.revision(), 1, order.id())); assertTrue(f.registry.storage().reservations().entries().isEmpty());
        var complete = kits.prepare(child.id(), order.revision(), xy(), List.of(candidate(0, X), candidate(1, Y)), 1);
        assertTrue(kits.finalizeProductionKit(complete, KitAllocatorTest::read, order.revision(), 1, order.id()));
        assertEquals(3, f.supply.shares().size()); assertEquals(2, f.supply.deliveries().size()); assertEquals(2, f.registry.storage().reservations().entries().size());
        var saved = f.supply.snapshot(); var storage = f.registry.storage().snapshot();
        var replacement = new io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger(SimulationLimits.development(), () -> {});
        try (var restore = f.supply.prepareRestore(saved, storage, replacement, f.registry.colonies())) { assertNotNull(restore); }
        f.stock(0, null, 0); var budgets = new GlobalWorkBudgets(SimulationLimits.development().withBudget(Budget.STORAGE_SLOT_CHECKS, 1), () -> 0); budgets.beginTick(2);
        f.supply.reconcile(2, budgets); assertEquals(1, budgets.used(Budget.STORAGE_SLOT_CHECKS));
        assertTrue(f.registry.storage().reservations().entries().isEmpty()); assertEquals(1, f.supply.shares().size()); assertEquals(1, child.deficit());
        assertEquals(1, f.registry.storage().index().free(slot(1), 1));
    }
    @Test void exactVariantIsChosenBeforeBroadMatcherWithoutPartialHolds() {
        Fixture f = new Fixture(); var named = new ItemDescriptor(X.itemId(), new byte[]{1}); f.stock(0, named, 1); f.stock(1, X, 1);
        var d = f.request(10, 1, Demand.GoalKind.CONSUMPTION, Lane.NORMAL); var kits = new KitAllocator(f.registry, f.supply);
        var inputs = List.of(new RecipeDefinition.Ingredient(new ItemMatcher(X.itemId(), null), 1), new RecipeDefinition.Ingredient(new ItemMatcher(X.itemId(), named), 1));
        var kit = kits.prepare(d.id(), 0, inputs, List.of(new KitAllocator.Candidate(slot(0), named, 1, 7), new KitAllocator.Candidate(slot(1), X, 1, 7)), 1);
        assertTrue(kit.complete());
        assertEquals(2, kits.finalizeKit(kit, s -> new KitAllocator.Observation(s.slot() == 0 ? named : X, 1, 7, true), 0, 1).size());
    }
}
