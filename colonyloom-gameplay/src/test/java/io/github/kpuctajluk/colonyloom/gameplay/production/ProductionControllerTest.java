package io.github.kpuctajluk.colonyloom.gameplay.production;

import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.*;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets;
import io.github.kpuctajluk.colonyloom.core.storage.*;
import io.github.kpuctajluk.colonyloom.core.supply.*;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ProductionControllerTest {
    private static UUID id(long value) { return new UUID(0, value); }
    private static WorldPosition pos(int x) { return new WorldPosition("minecraft:overworld", x, 64, 0); }

    @Test void criticalBlockadeRetainsKitAndRetriesWorkAfterCapacityRelease() {
        var limits = SimulationLimits.development();
        var registry = new ColonyRegistry(() -> {});
        UUID colony = id(1), owner = id(2);
        registry.addColony(new ColonyRuntime(colony, "production", new Territory("minecraft:overworld", 0, 0, 31, 31), owner, Map.of(), 0, 0, false, null, false));
        var storage = new StorageId("minecraft:overworld", id(3), 0);
        var input = new StockRegion(storage, 0);
        var output = new StockRegion(storage, 1);
        var registration = registry.storage().register(colony, pos(4), "workshop", List.of(storage), List.of(input, output), List.of(pos(4)));
        var workshop = registry.storage().registerWorkshop(colony, pos(5), registration.id());
        registry.addCitizen(new CitizenRecord(id(4), colony, id(5), 1, null, workshop.id(), null, "colonyloom:carpenter", Map.of(), Map.of("food", 20), CitizenRecord.Lifecycle.ALIVE, CitizenRecord.Admission.ACTIVE, CitizenRecord.Readiness.READY, 0, Map.of("food", 1200L), pos(5), 0), proposed -> {});
        registry.bindings().observe(id(4), id(5), 1);
        var material = new ItemDescriptor("minecraft:dirt", new byte[0]);
        var product = new ItemDescriptor("minecraft:stone", new byte[0]);
        var recipe = RecipeDefinition.create("colonyloom:test", 1, "colonyloom:carpenter", "minecraft:crafting_table", List.of(new RecipeDefinition.Ingredient(new ItemMatcher(material.itemId(), null), 1)), product, 1, 20);
        var supply = registry.supply();
        var demand = supply.request(id(6), colony, owner, new ItemMatcher(product.itemId(), null), 1, Demand.GoalKind.CONSUMPTION, pos(2), Lane.NORMAL, 10, 1);
        var production = supply.promiseProduction(demand.id(), recipe, 1);
        var ingredient = supply.ingredientDemand(production, 0, 1, 1);
        registry.storage().index().observe(input, material, 1, 1);
        registry.storage().index().observe(output, null, 0, 1);
        supply.allocateStock(ingredient.id(), input, material, 1, 1);
        assertTrue(supply.pinProduction(production.id()));
        var kit = supply.completeProductionKit(production.id());
        assertEquals(1, kit.getFirst().count());
        var allocations = registry.storage().allocations().entries();
        int initialWorks = registry.admission().used(Resource.WORKS);
        var controller = new ProductionController(registry);
        var budgets = new GlobalWorkBudgets(limits, () -> 0);
        try (var capacity = registry.admission().reserve(colony, Lane.CRITICAL, Map.of(Resource.CHUNK_DEMANDS, limits.resource(Resource.CHUNK_DEMANDS)))) {
            assertThrows(AdmissionLedger.AdmissionException.class, () -> registry.admission().reserve(colony, Lane.CRITICAL, Map.of(Resource.CHUNK_DEMANDS, 1)));
            for (int tick = 1; tick <= 3; tick++) {
                budgets.beginTick(tick);
                controller.tick(budgets);
                assertNull(supply.production(production.id()).workId());
                assertEquals(initialWorks, registry.admission().used(Resource.WORKS));
                assertEquals(kit, supply.completeProductionKit(production.id()));
                assertEquals(allocations, registry.storage().allocations().entries());
                assertEquals(1, demand.snapshot().covered());
                assertEquals(1, ingredient.snapshot().allocated());
            }
        }
        budgets.beginTick(4);
        controller.tick(budgets);
        UUID workId = supply.production(production.id()).workId();
        assertNotNull(workId);
        assertEquals(WorkOrder.State.READY, registry.workBoard().work(workId).state());
        budgets.beginTick(5);
        controller.tick(budgets);
        assertEquals(workId, supply.production(production.id()).workId());
        assertTrue(registry.workBoard().assign(workId, id(4)));
        registry.workBoard().transition(workId, WorkOrder.State.RUNNING, WorkOrder.Reason.NONE, "production");
        supply.startProduction(production.id(), id(4), workId);
        supply.advanceProduction(production.id(), 20);
        try (var prepared = supply.prepareProduction(production.id(), List.of(new SupplyRegistry.OutputPortion(output, 1)), 1)) {
            registry.storage().index().observe(output, product, 1, 1);
            prepared.commit();
        }
        assertEquals(1, supply.production(production.id()).completedBatches());
        assertEquals(1, ingredient.snapshot().fulfilled());
        assertEquals(0, ingredient.snapshot().allocated());
        assertEquals(0, registry.admission().used(Resource.CHUNK_DEMANDS));
    }
}
