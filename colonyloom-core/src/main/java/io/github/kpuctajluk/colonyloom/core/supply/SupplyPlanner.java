package io.github.kpuctajluk.colonyloom.core.supply;

import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Plans honest admitted obligations only; production/delivery execution belongs to later executors. */
public final class SupplyPlanner implements AutoCloseable {
    @FunctionalInterface public interface RecipeProvider {
        List<RecipeDefinition> recipes(UUID colony, ItemMatcher target);
    }
    public interface PhysicalAccess extends KitAllocator.PhysicalAccess {
        /** Unique canonical slots across pages, owner inventory then local buffer then warehouse. */
        CandidatePage candidates(Demand.Snapshot demand, ItemMatcher target, int offset, int limit);
    }
    public record CandidatePage(List<KitAllocator.Candidate> candidates, boolean exhausted) {
        public CandidatePage {
            candidates = List.copyOf(candidates);
            if (candidates.size() > 16) throw new IllegalArgumentException("Candidate page exceeds 16 slots");
        }
    }
    private final ColonyRegistry registry;
    private final SupplyRegistry supply;
    private final GlobalWorkBudgets budgets;
    private final Map<UUID, Long> serviced = new HashMap<>();
    private RecipeProvider recipes;
    private PhysicalAccess physical;
    private RecipeGraphCursor cursor;
    private long serviceSequence;
    private boolean finalizingStock;
    private long finalizingRevision;
    private Demand.Snapshot stockSearchRoot;
    private CandidatePage stockPage;
    private int stockOffset, stockIndex;
    private final KitAllocator kits;
    private KitAllocator.Search kitSearch;
    private KitAllocator.PreparedKit preparedKit;
    private List<RecipeDefinition.Ingredient> kitInputs;
    private UUID kitOrderId;
    private long kitOrderRevision;
    private int kitIngredient, kitOffset;
    public SupplyPlanner(ColonyRegistry registry, SupplyRegistry supply, GlobalWorkBudgets budgets) {
        this.registry = Objects.requireNonNull(registry); this.supply = Objects.requireNonNull(supply); this.budgets = Objects.requireNonNull(budgets);
        kits = new KitAllocator(registry, supply);
    }

    public void configure(RecipeProvider recipes, PhysicalAccess physical) {
        registry.requireOwner(); close(); this.recipes = Objects.requireNonNull(recipes); this.physical = Objects.requireNonNull(physical);
    }

    public void rebuild() { registry.requireOwner(); closeCursor(); serviced.clear(); lastService.clear(); serviceSequence = 0; }

    public void tick(long tick) {
        registry.requireOwner();
        if (recipes == null || physical == null) return;
        if (cursor != null) {
            Demand current = supply.demands().stream().filter(value -> value.id().equals(cursor.root().id())).findFirst().orElse(null);
            long expectedRevision = finalizingStock ? finalizingRevision : cursor.root().revision();
            if (current == null || !registry.colony(current.snapshot().colonyId()).available()
                    || current.snapshot().revision() != expectedRevision || current.deficit() == 0) closeCursor();
        }
        while (budgets.timeAvailable()) {
            if (cursor == null) {
                Demand demand = next();
                if (demand == null) return;
                if (!budgets.tryConsume(Budget.GRAPH_EXPANSIONS, demand.snapshot().lane())) return;
                try { cursor = new RecipeGraphCursor(demand.snapshot(), demand.deficit(), registry.admission(), recipes, physical, ancestors(demand)); }
                catch (AdmissionLedger.AdmissionException full) { supply.status(demand.id(), Demand.Status.WAITING); handoff(demand.id()); return; }
            }
            if (!budgets.tryConsume(Budget.GRAPH_EXPANSIONS, cursor.root().lane())) return;
            long graphStart=System.nanoTime();
            RecipeGraphCursor.Result result;
            try {result=cursor.advance();}
            finally {registry.metrics().record(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer.GRAPH_UNIT,System.nanoTime()-graphStart);}
            if (result == RecipeGraphCursor.Result.SEARCHING) continue;
            Demand demand = supply.demand(cursor.root().id());
            if (result == RecipeGraphCursor.Result.FOUND) {
                try {
                    boolean stockExhausted = reserveAvailableStock(demand, tick);
                    if (!stockExhausted) return;
                    if (demand.deficit() > 0) supply.sharedOutput(demand.id());
                    if (stockExhausted && demand.deficit() > 0 && cursor.selected() != null
                            && cursor.selected().batchesFor(demand.deficit()) <= cursor.batches()) {
                        var recipe = cursor.selected();
                        long batches = recipe.batchesFor(demand.deficit());
                        var order = supply.promiseProduction(demand.id(), recipe, batches);
                        for (int i = 0; i < recipe.ingredients().size(); i++) {
                            supply.ingredientDemand(order, i, Math.multiplyExact(recipe.ingredients().get(i).count(), batches), tick);
                        }
                    }
                    supply.status(demand.id(), Demand.Status.WAITING);
                } catch (IllegalStateException changed) { supply.status(demand.id(), Demand.Status.WAITING); }
            } else {
                if (result == RecipeGraphCursor.Result.NO_RECIPE || result == RecipeGraphCursor.Result.WORKING_SET_LIMIT) {
                    try { if (!reserveAvailableStock(demand, tick)) return; }
                    catch (IllegalStateException changed) { supply.status(demand.id(), Demand.Status.WAITING); }
                }
                try {
                    if (demand.deficit() > 0 && supply.sharedOutput(demand.id())) result = RecipeGraphCursor.Result.FOUND;
                } catch (IllegalStateException full) { result = RecipeGraphCursor.Result.WAITING; }
                supply.status(demand.id(), switch (result) {
                    case NO_RECIPE -> Demand.Status.NO_RECIPE;
                    case WORKING_SET_LIMIT -> Demand.Status.WORKING_SET_LIMIT;
                    default -> Demand.Status.WAITING;
                });
            }
            UUID id = demand.id(); closeCursor(); handoff(id);
            // Each root receives at most one terminal attempt per tick, including capacity waits.
            if (next() == null) return;
        }
    }

    private boolean reserveAvailableStock(Demand demand, long tick) {
        var production = supply.productionOrders().stream().filter(order -> order.id().equals(demand.snapshot().ownerId())).findFirst().orElse(null);
        if (production != null) return reserveProductionKit(demand, production, tick);
        if (!finalizingStock) {
            finalizingStock = true; finalizingRevision = demand.snapshot().revision(); stockSearchRoot = demand.snapshot();
        }
        while (demand.deficit() > 0) {
            if (stockPage == null) {
                if (!budgets.tryConsume(Budget.STORAGE_SLOT_CHECKS, demand.snapshot().lane())) return false;
                stockPage = physical.candidates(stockSearchRoot, stockSearchRoot.matcher(), stockOffset, 16);
                stockIndex = 0;
            }
            while (stockIndex < stockPage.candidates().size()) {
                var candidate = stockPage.candidates().get(stockIndex);
                if (!demand.snapshot().matcher().matches(candidate.item()) || candidate.available() == 0) { stockIndex++; continue; }
                if (!budgets.tryConsume(Budget.STORAGE_SLOT_CHECKS, demand.snapshot().lane())) return false;
                stockIndex++;
                var observed = physical.read(candidate.slot());
                if (!observed.known() || observed.revision() != candidate.revision()
                        || !candidate.item().equals(observed.item())) continue;
                long available = Math.min(candidate.available(), registry.storage().index().free(candidate.slot(), tick));
                long count = Math.min(demand.deficit(), Math.min(available, observed.count()));
                if (count > 0) {
                    supply.coverStock(demand.id(), candidate.slot(), candidate.item(), count, tick);
                    finalizingRevision = demand.snapshot().revision();
                }
                if (demand.deficit() == 0) return true;
            }
            if (stockPage.exhausted()) return true;
            stockOffset = Math.addExact(stockOffset, 16); stockPage = null;
        }
        return true;
    }
    private boolean reserveProductionKit(Demand demand, io.github.kpuctajluk.colonyloom.core.production.ProductionOrder order, long tick) {
        if (kitSearch == null) {
            var inputs = new java.util.ArrayList<RecipeDefinition.Ingredient>();
            for (int i = 0; i < order.recipe().ingredients().size(); i++) {
                var child = supply.ingredientDemand(order, i, Math.multiplyExact(order.batches(), order.recipe().ingredients().get(i).count()), tick);
                if (child.snapshot().covered() > 0 || child.snapshot().allocated() > 0) return true;
                if (child.deficit() > 0) inputs.add(new RecipeDefinition.Ingredient(child.snapshot().matcher(), child.deficit()));
            }
            if (inputs.isEmpty()) return true;
            kitInputs = List.copyOf(inputs); kitOrderId = order.id(); kitOrderRevision = order.revision();
            stockSearchRoot = demand.snapshot(); finalizingStock = true; finalizingRevision = demand.snapshot().revision();
            kitSearch = kits.begin(demand.id(), kitOrderRevision, kitInputs);
        }
        if (preparedKit != null && preparedKit.complete()) return finalizeProductionKit(demand, order, tick);
        while (kitIngredient < kitInputs.size()) {
            if (!budgets.tryConsume(Budget.STORAGE_SLOT_CHECKS, demand.snapshot().lane())) return false;
            var page = physical.candidates(stockSearchRoot, kitInputs.get(kitIngredient).matcher(), kitOffset, 16);
            preparedKit = kits.search(kitSearch, page.candidates(), tick);
            if (preparedKit.complete()) return finalizeProductionKit(demand, order, tick);
            if (page.exhausted()) { kitIngredient++; kitOffset = 0; }
            else kitOffset = Math.addExact(kitOffset, 16);
        }
        return true; // Incomplete kit holds no physical stock.
    }
    private boolean finalizeProductionKit(Demand demand, io.github.kpuctajluk.colonyloom.core.production.ProductionOrder order, long tick) {
        int checks = Math.multiplyExact(preparedKit.portions().size(), 2);
        if (budgets.limits().budget(Budget.STORAGE_SLOT_CHECKS) - budgets.used(Budget.STORAGE_SLOT_CHECKS) < checks) return false;
        for (int i = 0; i < checks; i++) if (!budgets.tryConsume(Budget.STORAGE_SLOT_CHECKS, demand.snapshot().lane())) return false;
        kits.finalizeProductionKit(preparedKit, physical, order.revision(), tick, kitOrderId);
        finalizingRevision = demand.snapshot().revision();
        return true;
    }



    private Demand next() {
        long currentTick = budgets.tick();
        boolean critical=false,service=false,normal=false;
        for(var demand:supply.demands())if(eligible(demand,currentTick))switch(demand.snapshot().lane()) {
            case CRITICAL -> critical=true;
            case SERVICE -> service=true;
            case NORMAL -> normal=true;
        }
        var lane=budgets.chooseLane(Budget.GRAPH_EXPANSIONS,critical,service,normal);if(lane==null)return null;
        return supply.demands().stream().filter(d -> eligible(d,currentTick)&&d.snapshot().lane()==lane)
                .min(Comparator.comparingLong((Demand d) -> lastService.getOrDefault(d.id(), Long.MIN_VALUE))
                        .thenComparing(Comparator.comparingInt((Demand d) -> d.snapshot().priority()).reversed())
                        .thenComparingLong(d -> d.snapshot().createdTick()).thenComparing(Demand::id)).orElse(null);
    }
    private boolean eligible(Demand demand,long tick) {
        return demand.deficit()>0 && registry.colony(demand.snapshot().colonyId()).available()
                && demand.snapshot().status()!=Demand.Status.CANCELLED && demand.snapshot().status()!=Demand.Status.COMPLETED
                && serviced.getOrDefault(demand.id(),Long.MIN_VALUE)!=tick;
    }
    private final Map<UUID, Long> lastService = new HashMap<>();
    private java.util.Set<ItemMatcher> ancestors(Demand demand) {
        var ancestors = new java.util.HashSet<ItemMatcher>();
        var visited = new java.util.HashSet<UUID>();
        UUID owner = demand.snapshot().ownerId();
        while (visited.add(owner)) {
            UUID currentOwner = owner;
            var order = supply.productionOrders().stream().filter(value -> value.id().equals(currentOwner)).findFirst().orElse(null);
            if (order == null) break;
            Demand parent = supply.demand(order.ownerDemandId());
            ancestors.add(parent.snapshot().matcher()); owner = parent.snapshot().ownerId();
        }
        return ancestors;
    }
    private void handoff(UUID id) { serviced.put(id, budgets.tick()); lastService.put(id, ++serviceSequence); }
    private void closeCursor() {
        if (cursor != null) { cursor.close(); cursor = null; }
        finalizingStock = false; stockSearchRoot = null; stockPage = null; stockOffset = 0; stockIndex = 0;
        kitSearch = null; preparedKit = null; kitInputs = null; kitOrderId = null; kitIngredient = 0; kitOffset = 0;
    }
    public int activeGraphNodes() { registry.requireOwner(); return cursor == null ? 0 : cursor.activeNodes(); }
    @Override public void close() { registry.requireOwner(); closeCursor(); serviced.clear(); lastService.clear(); serviceSequence = 0; }
}
