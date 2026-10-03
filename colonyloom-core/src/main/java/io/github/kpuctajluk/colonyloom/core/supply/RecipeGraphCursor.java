package io.github.kpuctajluk.colonyloom.core.supply;

import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One protected lazy DFS path. Unselected roots have no graph nodes or edges. */
public final class RecipeGraphCursor implements AutoCloseable {
    public enum Result { SEARCHING, FOUND, NO_RECIPE, WORKING_SET_LIMIT, WAITING }
    private static final Map<Resource, Integer> ROOT_COST = Map.of(Resource.GRAPH_NODES, 1);
    private static final Map<Resource, Integer> CHILD_COST = Map.of(Resource.GRAPH_NODES, 1, Resource.GRAPH_EDGES, 1);
    private static final class Frame {
        final ItemMatcher matcher;
        final long quantity;
        final AdmissionLedger.Lease lease;
        long stock;
        int stockOffset, recipeIndex, ingredientIndex;
        boolean stockDone, limited;
        List<RecipeDefinition> recipes;
        RecipeDefinition recipe;
        Frame(ItemMatcher matcher, long quantity, AdmissionLedger.Lease lease) {
            this.matcher = matcher; this.quantity = quantity; this.lease = lease;
        }
    }
    private final Demand.Snapshot root;
    private final AdmissionLedger ledger;
    private final SupplyPlanner.RecipeProvider provider;
    private final SupplyPlanner.PhysicalAccess physical;
    private final AdmissionLedger.Lease compactLease;
    private final ArrayList<Frame> stack = new ArrayList<>();
    private Result result = Result.SEARCHING;
    private RecipeDefinition selected;
    private long batches;
    private final Set<ItemMatcher> ancestors;

    public RecipeGraphCursor(Demand.Snapshot root, long deficit, AdmissionLedger ledger,
            SupplyPlanner.RecipeProvider provider, SupplyPlanner.PhysicalAccess physical, Set<ItemMatcher> ancestors) {
        this.root = root; this.ledger = ledger; this.provider = provider; this.physical = physical;
        this.ancestors = Set.copyOf(ancestors);
        compactLease = ledger.reserve(root.colonyId(), root.lane(), Map.of(Resource.WORKS, 1, Resource.WAIT_REGISTRATIONS, 1));
        try {
            push(root.matcher(), deficit, true);
        } catch (AdmissionLedger.AdmissionException full) {
            result = pathCapacity() < 1 ? Result.WORKING_SET_LIMIT : Result.WAITING;
        }
    }

    public Result result() { return result; }
    public RecipeDefinition selected() { return selected; }
    public long batches() { return batches; }
    public int activeNodes() { return stack.size(); }
    public Demand.Snapshot root() { return root; }

    /** One graph expansion; caller owns the global graph token. Candidate search is paged. */
    public Result advance() {
        if (result != Result.SEARCHING) return result;
        Frame frame = stack.getLast();
        if (!frame.stockDone) {
            var page = physical.candidates(root, frame.matcher, frame.stockOffset, 16);
            for (var candidate : page.candidates()) {
                if (frame.matcher.matches(candidate.item())) frame.stock = Math.min(frame.quantity, Math.addExact(frame.stock, candidate.available()));
            }
            frame.stockOffset = Math.addExact(frame.stockOffset, 16);
            frame.stockDone = page.exhausted();
            if (frame.stock >= frame.quantity) return succeed();
            return result;
        }
        if (frame.recipes == null) frame.recipes = List.copyOf(provider.recipes(root.colonyId(), frame.matcher));
        if (cyclic(frame.matcher)) return fail(false);
        if (frame.recipe == null) {
            while (frame.recipeIndex < frame.recipes.size()) {
                RecipeDefinition next = frame.recipes.get(frame.recipeIndex++);
                if (frame.matcher.matches(next.output())) { frame.recipe = next; frame.ingredientIndex = 0; break; }
            }
            if (frame.recipe == null) return fail(frame.limited);
        }
        if (frame.ingredientIndex == frame.recipe.ingredients().size()) return succeed();
        var ingredient = frame.recipe.ingredients().get(frame.ingredientIndex);
        long required;
        try {
            required = Math.multiplyExact(ingredient.count(), frame.recipe.batchesFor(frame.quantity - frame.stock));
            if (required > RecipeDefinition.MAX_COUNT) { frame.recipe = null; return result; }
        } catch (ArithmeticException invalid) { frame.recipe = null; return result; }
        if (stack.size() >= pathCapacity()) {
            frame.limited = true; frame.recipe = null; return result;
        }
        try { push(ingredient.matcher(), required, false); }
        catch (AdmissionLedger.AdmissionException busy) { result = Result.WAITING; }
        return result;
    }

    private void push(ItemMatcher matcher, long quantity, boolean rootNode) {
        var lease = ledger.reserve(root.colonyId(), root.lane(), rootNode ? ROOT_COST : CHILD_COST);
        stack.add(new Frame(matcher, quantity, lease));
    }

    private int pathCapacity() {
        int nodes = ledger.laneCapacity(Resource.GRAPH_NODES, root.lane());
        int edges = ledger.laneCapacity(Resource.GRAPH_EDGES, root.lane());
        if (root.lane() == AdmissionLedger.Lane.NORMAL) {
            nodes = Math.min(nodes, Math.max(nodes / 3, ledger.limits().resource(Resource.GRAPH_NODES) / 2));
            edges = Math.min(edges, Math.max(edges / 3, ledger.limits().resource(Resource.GRAPH_EDGES) / 2));
        }
        return Math.min(nodes, edges == Integer.MAX_VALUE ? edges : edges + 1);
    }

    private Result succeed() {
        Frame frame = stack.getLast();
        if (stack.size() == 1) {
            selected = frame.recipe;
            batches = selected == null ? 0 : selected.batchesFor(frame.quantity - frame.stock);
            result = Result.FOUND;
        } else { pop(); stack.getLast().ingredientIndex++; }
        return result;
    }

    private Result fail(boolean limited) {
        pop();
        if (stack.isEmpty()) result = limited ? Result.WORKING_SET_LIMIT : Result.NO_RECIPE;
        else { Frame parent = stack.getLast(); parent.limited |= limited; parent.recipe = null; }
        return result;
    }

    private boolean cyclic(ItemMatcher matcher) {
        if (ancestors.contains(matcher)) return true;
        for (int i = 0; i < stack.size() - 1; i++) if (stack.get(i).matcher.equals(matcher)) return true;
        return false;
    }
    private void pop() {
        Frame frame = stack.removeLast();
        frame.lease.close();
    }
    @Override public void close() { while (!stack.isEmpty()) pop(); compactLease.close(); }
}
