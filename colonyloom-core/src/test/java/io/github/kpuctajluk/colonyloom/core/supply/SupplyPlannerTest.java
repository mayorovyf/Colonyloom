package io.github.kpuctajluk.colonyloom.core.supply;

import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets;
import io.github.kpuctajluk.colonyloom.core.storage.ItemDescriptor;
import io.github.kpuctajluk.colonyloom.core.storage.StockRegion;
import io.github.kpuctajluk.colonyloom.core.storage.StorageId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SupplyPlannerTest {
    private static UUID id(long value) { return new UUID(0, value); }
    private static final UUID COLONY = id(1), OWNER = id(2);
    private static final WorldPosition DESTINATION = new WorldPosition("minecraft:overworld", 0, 64, 0);
    private static ItemDescriptor item(String name) { return new ItemDescriptor("colonyloom:" + name, new byte[0]); }
    private static ItemMatcher matcher(String name) { return new ItemMatcher(item(name).itemId(), null); }
    private static RecipeDefinition recipe(String id, String output, String input) {
        return RecipeDefinition.create("colonyloom:" + id, 1, "colonyloom:carpenter", "minecraft:crafting_table",
                List.of(new RecipeDefinition.Ingredient(matcher(input), 1)), item(output), 1, 20);
    }
    private static final class Fixture implements SupplyPlanner.PhysicalAccess, SupplyPlanner.RecipeProvider {
        final ColonyRegistry registry = new ColonyRegistry(() -> {});
        final SupplyRegistry supply = new SupplyRegistry(registry);
        final GlobalWorkBudgets budgets;
        final SupplyPlanner planner;
        final List<RecipeDefinition> recipes = new ArrayList<>();
        final Map<StockRegion, KitAllocator.Observation> physical = new HashMap<>();
        long tick;
        Fixture(int totalNodes, int graphBudget) {
            var limits = SimulationLimits.development().withResource(Resource.GRAPH_NODES, totalNodes)
                    .withBudget(Budget.GRAPH_EXPANSIONS, graphBudget);
            registry.admission().updateLimits(limits);
            registry.addColony(new ColonyRuntime(COLONY, "A", new Territory("minecraft:overworld", 0, 0, 31, 31), OWNER, Map.of(), 0, 0, false, null, false));
            var workshopStorage = new StorageId("minecraft:overworld", id(400), 0);
            var registration = registry.storage().register(COLONY,new WorldPosition("minecraft:overworld",4,64,0),"workshop",List.of(workshopStorage),List.of(new StockRegion(workshopStorage,0)),List.of(new WorldPosition("minecraft:overworld",4,64,0)));
            var workshop = registry.storage().registerWorkshop(COLONY,new WorldPosition("minecraft:overworld",5,64,0),registration.id());
            registry.addCitizen(new io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord(id(401),COLONY,id(402),1,null,workshop.id(),null,"colonyloom:carpenter",Map.of(),Map.of("food",20),io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Lifecycle.ALIVE,io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Admission.ACTIVE,io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Readiness.READY,0,Map.of("food",1200L),DESTINATION,0),proposed->{});
            budgets = new GlobalWorkBudgets(limits, () -> 0);
            planner = new SupplyPlanner(registry, supply, budgets); planner.configure(this, this);
        }
        Demand goal(long identity, String item) {
            return supply.request(id(identity), COLONY, OWNER, matcher(item), 1, Demand.GoalKind.CONSUMPTION, DESTINATION, Lane.NORMAL, 10, tick);
        }
        void stock(long identity, String item) {
            var slot = new StockRegion(new StorageId("minecraft:overworld", id(identity), 0), 0);
            registry.storage().register(COLONY, new WorldPosition("minecraft:overworld", (int)identity % 20, 64, 0), "warehouse",
                    List.of(slot.storage()), List.of(slot), List.of(DESTINATION));
            var value = new KitAllocator.Observation(item(item), 64, 1, true); physical.put(slot, value);
            registry.storage().index().observe(slot, value.item(), value.count(), tick);
        }
        void step() { budgets.beginTick(++tick); planner.tick(tick); }
        @Override public List<RecipeDefinition> recipes(UUID colony, ItemMatcher target) {
            return recipes.stream().filter(r -> target.matches(r.output())).sorted(Comparator.comparing(RecipeDefinition::id)).toList();
        }
        @Override public KitAllocator.Observation read(StockRegion slot) { return physical.get(slot); }
        @Override public SupplyPlanner.CandidatePage candidates(Demand.Snapshot demand, ItemMatcher target, int offset, int limit) {
            var candidates = physical.entrySet().stream().filter(e -> target.matches(e.getValue().item()))
                    .sorted(Comparator.comparing(e -> e.getKey().storage().identity()))
                    .map(e -> new KitAllocator.Candidate(e.getKey(), e.getValue().item(), registry.storage().index().free(e.getKey(), tick), e.getValue().revision())).toList();
            int from = Math.min(offset, candidates.size()), to = Math.min(from + limit, candidates.size());
            return new SupplyPlanner.CandidatePage(candidates.subList(from, to), to == candidates.size());
        }
    }

    @Test void bothDepthThreeGoalsAdvanceWithFourUsableNodesWithoutCancellingAnything() {
        // Total8 reserves other lanes; the true NORMAL per-colony working set is4.
        Fixture f = new Fixture(8, 1);
        Demand first = f.goal(10, "a"), second = f.goal(11, "d");
        f.recipes.addAll(List.of(recipe("a", "a", "b"), recipe("b", "b", "c"), recipe("c", "c", "raw1"),
                recipe("d", "d", "e"), recipe("e", "e", "f"), recipe("f", "f", "raw2")));
        f.stock(100, "raw1"); f.stock(101, "raw2");
        for (int i = 0; i < 180; i++) { f.step(); assertTrue(f.planner.activeGraphNodes() <= 4); }
        assertEquals(0, first.deficit()); assertEquals(0, second.deficit());
        assertEquals(6, f.supply.productionOrders().size());
        assertEquals(2, f.supply.deliveries().size());
        assertEquals(4, f.registry.admission().highWater(Resource.GRAPH_NODES));
        assertTrue(f.supply.productionOrders().stream().allMatch(o -> o.state().name().equals("PLANNED") || o.state().name().equals("WAITING")));
        assertEquals(0, first.snapshot().fulfilled()); assertEquals(0, second.snapshot().fulfilled());
        f.planner.close(); assertEquals(0, f.registry.admission().used(Resource.GRAPH_NODES));
    }

    @Test void cyclicAlternativeDoesNotHideAvailableAlternativeIncludingDeferredIngredients() {
        Fixture f = new Fixture(8, 128); Demand goal = f.goal(10, "a");
        f.recipes.addAll(List.of(recipe("a_to_b", "a", "b"), recipe("b_cycle", "b", "a"), recipe("b_good", "b", "raw")));
        f.stock(100, "raw"); for (int i = 0; i < 5; i++) f.step();
        assertEquals(0, goal.deficit());
        assertEquals(Set.of("colonyloom:a_to_b", "colonyloom:b_good"), f.supply.productionOrders().stream().map(o -> o.recipe().id()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, f.supply.deliveries().size());
    }

    @Test void exhaustedWorkingSetAndAbsentRecipeAreDifferent() {
        Fixture f = new Fixture(4, 128);
        Demand tooDeep = f.goal(10, "a"), absent = f.goal(11, "missing");
        f.recipes.addAll(List.of(recipe("a", "a", "b"), recipe("b", "b", "c"), recipe("c", "c", "raw")));
        f.stock(100, "raw"); f.step();
        assertEquals(Demand.Status.WORKING_SET_LIMIT, tooDeep.snapshot().status());
        assertEquals(Demand.Status.NO_RECIPE, absent.snapshot().status());
        assertTrue(f.supply.productionOrders().isEmpty());
    }

    @Test void stockFirstAndRepeatedPlanningDoNotMultiplyCoverage() {
        Fixture f = new Fixture(8, 128); Demand goal = f.goal(10, "a");
        f.recipes.add(recipe("a", "a", "raw")); f.stock(100, "a"); f.stock(101, "raw");
        f.step(); var before = f.supply.snapshot();
        for (int i = 0; i < 10; i++) f.step();
        assertEquals(before, f.supply.snapshot()); assertEquals(1, f.supply.shares().size());
        assertEquals(1, f.supply.deliveries().size()); assertTrue(f.supply.productionOrders().isEmpty());
        assertEquals(1, goal.snapshot().covered()); assertEquals(0, goal.snapshot().fulfilled());
    }

    @Test void stockSearchContinuesAcrossTickBudgetsAndNativeMismatchNeverCreatesCoverage() {
        Fixture f = new Fixture(8, 128); Demand goal = f.goal(10, "a"); f.stock(100, "a");
        var slow = new SupplyPlanner.PhysicalAccess() {
            @Override public KitAllocator.Observation read(StockRegion slot) { return f.read(slot); }
            @Override public SupplyPlanner.CandidatePage candidates(Demand.Snapshot demand, ItemMatcher target, int offset, int limit) {
                return offset < 48 ? new SupplyPlanner.CandidatePage(List.of(), false) : f.candidates(demand, target, 0, limit);
            }
        };
        f.budgets.updateLimits(f.budgets.limits().withBudget(Budget.STORAGE_SLOT_CHECKS, 1));
        f.planner.configure(f, slow);
        for (int i = 0; i < 10; i++) f.step();
        assertEquals(0, goal.deficit()); assertEquals(1, f.supply.deliveries().size());

        Fixture mismatch = new Fixture(8, 128); Demand unmet = mismatch.goal(10, "a"); mismatch.stock(100, "a");
        mismatch.planner.configure(mismatch, new SupplyPlanner.PhysicalAccess() {
            @Override public KitAllocator.Observation read(StockRegion slot) { var read = mismatch.read(slot); return new KitAllocator.Observation(read.item(), read.count(), read.revision() + 1, true); }
            @Override public SupplyPlanner.CandidatePage candidates(Demand.Snapshot demand, ItemMatcher target, int offset, int limit) { return mismatch.candidates(demand, target, offset, limit); }
        });
        mismatch.step(); assertEquals(1, unmet.deficit()); assertTrue(mismatch.supply.shares().isEmpty());
    }

    @Test void pagedDeliverySearchRetainsSelectedConsumerAndNeverPlansCrafting() {
        Fixture f = new Fixture(8, 8); f.stock(100, "a"); f.stock(101, "a");
        var source1 = new StorageId("minecraft:overworld", id(100), 0);
        var source2 = new StorageId("minecraft:overworld", id(101), 0);
        var first = f.supply.requestDelivery(id(10), COLONY, OWNER, item("a"), 1, DESTINATION, List.of(source1), 0);
        var second = f.supply.requestDelivery(id(11), COLONY, OWNER, item("a"), 1, DESTINATION, List.of(source2), 0);
        f.recipes.add(recipe("a", "a", "raw"));
        Map<UUID, List<Integer>> offsets = new HashMap<>();
        f.planner.configure(f, new SupplyPlanner.PhysicalAccess() {
            @Override public KitAllocator.Observation read(StockRegion slot) { return f.read(slot); }
            @Override public SupplyPlanner.CandidatePage candidates(Demand.Snapshot demand, ItemMatcher target, int offset, int limit) {
                offsets.computeIfAbsent(demand.id(), ignored -> new ArrayList<>()).add(offset);
                return offset < 32 ? new SupplyPlanner.CandidatePage(List.of(), false) : f.candidates(demand, target, 0, limit);
            }
        });
        f.budgets.updateLimits(f.budgets.limits().withBudget(Budget.STORAGE_SLOT_CHECKS, 1));
        for (int i = 0; i < 20; i++) f.step();
        assertEquals(0, first.deficit()); assertEquals(0, second.deficit()); assertTrue(f.supply.productionOrders().isEmpty());
        assertEquals(List.of(0, 16, 32), offsets.get(first.id())); assertEquals(List.of(0, 16, 32), offsets.get(second.id()));
        for (var order : f.supply.deliveries()) assertEquals(order.ownerDemandId().equals(first.id()) ? source1 : source2, order.source().storage());
    }

    @Test void sharedBatchInheritsAndReleasesUrgencyWithoutDuplicatingCoverage() {
        Fixture f = new Fixture(8, 128); Demand normal = f.goal(10, "a"); f.stock(100, "raw");
        f.recipes.add(RecipeDefinition.create("colonyloom:a", 1, "colonyloom:carpenter", "minecraft:crafting_table",
                List.of(new RecipeDefinition.Ingredient(matcher("raw"), 1)), item("a"), 4, 20));
        f.step(); assertEquals(1, f.supply.productionOrders().size());
        Demand urgent = f.supply.request(id(11), COLONY, OWNER, matcher("a"), 1, Demand.GoalKind.CONSUMPTION, DESTINATION, Lane.CRITICAL, 100, f.tick);
        f.step(); assertEquals(0, urgent.deficit()); assertEquals(1, f.supply.productionOrders().size());
        assertEquals(Lane.CRITICAL, f.supply.productionOrders().getFirst().lane()); assertEquals(100, f.supply.productionOrders().getFirst().priority());
        f.supply.cancel(urgent.id());
        assertEquals(Lane.NORMAL, f.supply.productionOrders().getFirst().lane()); assertEquals(10, f.supply.productionOrders().getFirst().priority());
        assertEquals(0, normal.deficit()); assertEquals(0, normal.snapshot().fulfilled());
    }

    @Test void actualPlannerNeverHoldsXForMissingYAndCompetingCompleteKitAdvances() {
        Fixture f = new Fixture(8, 128); Demand blocked = f.goal(10, "a"), ready = f.goal(11, "b");
        var missing = RecipeDefinition.create("colonyloom:a", 1, "colonyloom:carpenter", "minecraft:crafting_table",
                List.of(new RecipeDefinition.Ingredient(matcher("x"), 1), new RecipeDefinition.Ingredient(matcher("y"), 1)), item("a"), 1, 20);
        var complete = RecipeDefinition.create("colonyloom:b", 1, "colonyloom:carpenter", "minecraft:crafting_table",
                List.of(new RecipeDefinition.Ingredient(matcher("x"), 1), new RecipeDefinition.Ingredient(matcher("z"), 1)), item("b"), 1, 20);
        var blockedOrder = f.supply.promiseProduction(blocked.id(), missing, 1);
        var readyOrder = f.supply.promiseProduction(ready.id(), complete, 1);
        f.stock(100, "x"); f.stock(101, "z");
        for (int i = 0; i < 5; i++) f.step();
        assertTrue(f.supply.demands().stream().filter(d -> d.snapshot().ownerId().equals(blockedOrder.id())).allMatch(d -> d.snapshot().covered() == 0));
        assertTrue(f.supply.demands().stream().filter(d -> d.snapshot().ownerId().equals(readyOrder.id())).allMatch(d -> d.deficit() == 0));
        assertEquals(2, f.supply.deliveries().size()); assertEquals(2, f.registry.storage().reservations().entries().size());
        assertEquals(0, blocked.snapshot().fulfilled()); assertEquals(0, ready.snapshot().fulfilled());
    }
    @Test void nativeSweepSharesFourChecksWithMissingStockPlanningAndCompleteKitFinalization() {
        Fixture f = new Fixture(8, 8);
        f.budgets.updateLimits(f.budgets.limits().withBudget(Budget.STORAGE_SLOT_CHECKS, 4));
        Demand missing = f.goal(10, "missing"), ready = f.goal(11, "b");
        var complete = RecipeDefinition.create("colonyloom:b", 1, "colonyloom:carpenter", "minecraft:crafting_table",
                List.of(new RecipeDefinition.Ingredient(matcher("x"), 1), new RecipeDefinition.Ingredient(matcher("z"), 1)), item("b"), 1, 20);
        var order = f.supply.promiseProduction(ready.id(), complete, 1);
        f.stock(100, "x"); f.stock(101, "z");
        for (int identity = 110; identity < 120; identity++) if (identity != 114) f.stock(identity, "filler");
        int[] nativeReads = {0};
        for (int step = 0; step < 80; step++) {
            f.budgets.beginTick(++f.tick);
            f.registry.storage().index().tick(f.tick, f.budgets, slot -> {
                nativeReads[0]++;
                var read = f.physical.get(slot);
                return read == null ? new io.github.kpuctajluk.colonyloom.core.storage.StockIndex.Observation(null, 0, false)
                        : new io.github.kpuctajluk.colonyloom.core.storage.StockIndex.Observation(read.item(), read.count(), read.known());
            });
            f.planner.tick(f.tick);
            assertTrue(f.budgets.used(Budget.STORAGE_SLOT_CHECKS) <= 4);
        }
        assertTrue(nativeReads[0] > 0);
        assertEquals(Demand.Status.NO_RECIPE, missing.snapshot().status());
        assertTrue(f.supply.demands().stream().filter(d -> d.snapshot().ownerId().equals(order.id())).allMatch(d -> d.deficit() == 0));
        assertEquals(2, f.supply.deliveries().size());
        assertEquals(2, f.registry.storage().reservations().entries().size());
    }

    @Test void ordinaryRootProgressesWhileCriticalMissingRecipeGoalsStayEligible() {
        Fixture f=new Fixture(8,1);Demand ordinary=f.goal(10,"a");f.stock(100,"a");
        for(int i=0;i<12;i++)f.supply.request(id(300+i),COLONY,OWNER,matcher("missing"),1,Demand.GoalKind.CONSUMPTION,DESTINATION,Lane.CRITICAL,100,f.tick);
        for(int tick=0;tick<100&&ordinary.deficit()>0;tick++)f.step();
        assertEquals(0,ordinary.deficit());assertEquals(1,f.supply.deliveries().size());
        assertTrue(f.supply.demands().stream().filter(value -> value.snapshot().lane()==Lane.CRITICAL).allMatch(value -> value.snapshot().fulfilled()==0));
    }

    @Test void indexedFrontierRetainsDeniedTurnAndReopensExactGoalAfterRequiredIncrease() {
        Fixture f=new Fixture(8,1);f.stock(100,"a");
        Demand first=f.supply.requestDelivery(id(10),COLONY,OWNER,item("a"),1,DESTINATION,List.of(new StorageId("minecraft:overworld",id(100),0)),0);
        Demand second=f.supply.requestDelivery(id(11),COLONY,OWNER,item("a"),1,DESTINATION,List.of(new StorageId("minecraft:overworld",id(100),0)),0);
        f.budgets.beginTick(++f.tick);f.budgets.tryConsume(Budget.GRAPH_EXPANSIONS,Lane.NORMAL);f.planner.tick(f.tick);
        assertSame(first,f.supply.planningCandidate(Lane.NORMAL));assertTrue(f.supply.shares().isEmpty());
        f.step();assertEquals(0,first.deficit());assertSame(second,f.supply.planningCandidate(Lane.NORMAL));
        f.step();assertEquals(0,second.deficit());assertNull(f.supply.planningCandidate(Lane.NORMAL));
        f.supply.updateRequired(first.id(),2);assertSame(first,f.supply.planningCandidate(Lane.NORMAL));
        f.step();assertEquals(0,first.deficit());assertEquals(2,first.snapshot().covered());
        f.supply.cancel(first.id());assertNull(f.supply.planningCandidate(Lane.NORMAL));
    }

    @Test void pinnedRecipeDigestRejectsAlterationAndUsesImmutableIngredients() {
        var inputs = new ArrayList<RecipeDefinition.Ingredient>(); inputs.add(new RecipeDefinition.Ingredient(matcher("raw"), 1));
        var definition = RecipeDefinition.create("colonyloom:a", 1, "colonyloom:carpenter", "minecraft:crafting_table", inputs, item("a"), 4, 20);
        inputs.clear(); assertEquals(1, definition.ingredients().size()); assertEquals(2, definition.batchesFor(5));
        assertThrows(IllegalArgumentException.class, () -> new RecipeDefinition(definition.id(), 1, definition.professionId(), definition.equipmentId(), definition.ingredients(), definition.output(), 4, 21, definition.digest()));
    }
}
