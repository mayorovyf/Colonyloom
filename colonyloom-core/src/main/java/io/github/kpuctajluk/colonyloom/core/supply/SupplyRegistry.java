package io.github.kpuctajluk.colonyloom.core.supply;

import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.storage.*;
import io.github.kpuctajluk.colonyloom.core.production.ProductionOrder;
import io.github.kpuctajluk.colonyloom.core.logistics.DeliveryOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Single owner of consumer coverage and its real storage/order references. No physical executor lives here. */
public final class SupplyRegistry {
    public static final int MAX_DEMANDS = 16384, MAX_SHARES = 32768, MAX_ORDERS = 8192;
    private final ColonyRegistry registry;
    private final Map<UUID, Demand> demands = new LinkedHashMap<>();
    private final Map<UUID, CoverageShare> shares = new LinkedHashMap<>();
    private final Map<UUID, ProductionOrder> productions = new LinkedHashMap<>();
    private final Map<UUID, DeliveryOrder> deliveries = new LinkedHashMap<>();
    private final Map<UUID, AdmissionLedger.Lease> leases = new LinkedHashMap<>();
    private final Map<UUID, UUID> obligationShares = new HashMap<>();
    private final Map<UUID, Set<UUID>> kitShares = new HashMap<>();
    private final LinkedHashSet<UUID> dirtyObligations = new LinkedHashSet<>();
    public SupplyRegistry(ColonyRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
        registry.storage().setLossListener(id -> { if (obligationShares.containsKey(id)) dirtyObligations.add(id); });
    }
    private void putShare(UUID id, CoverageShare share) {
        CoverageShare previous = shares.put(id, share); if (previous != null) unindex(previous);
        if (share.obligationId() != null) obligationShares.put(share.obligationId(), id);
        UUID owner = demand(share.demandId()).snapshot().ownerId();
        if (productions.containsKey(owner) && share.stage() == CoverageShare.Stage.RESERVED_STOCK) kitShares.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(id);
    }
    private void unindex(CoverageShare share) {
        if (share.obligationId() != null) obligationShares.remove(share.obligationId());
        UUID owner = demand(share.demandId()).snapshot().ownerId(); Set<UUID> local = kitShares.get(owner);
        if (local != null) { local.remove(share.id()); if (local.isEmpty()) kitShares.remove(owner); }
    }
    private void clearShares() { shares.clear(); obligationShares.clear(); kitShares.clear(); }
    private void putShares(Map<UUID, CoverageShare> values) { values.forEach(this::putShare); }
    public Demand demand(UUID id) { registry.requireOwner(); Demand d = demands.get(id); if (d == null) throw new IllegalArgumentException("Unknown demand"); return d; }
    public List<Demand> demands() { registry.requireOwner(); return List.copyOf(demands.values()); }
    public List<CoverageShare> shares() { registry.requireOwner(); return List.copyOf(shares.values()); }
    public List<ProductionOrder> productionOrders() { registry.requireOwner(); return List.copyOf(productions.values()); }
    public List<DeliveryOrder> deliveries() { registry.requireOwner(); return List.copyOf(deliveries.values()); }
    public boolean usedId(UUID id) { registry.requireOwner(); return demands.containsKey(id) || shares.containsKey(id) || productions.containsKey(id) || deliveries.containsKey(id); }
    public SupplySnapshot snapshot() { return new SupplySnapshot(demands().stream().map(Demand::snapshot).toList(), shares(), productionOrders(), deliveries()); }

    public Demand request(UUID id, UUID colony, UUID owner, ItemMatcher matcher, long required, Demand.GoalKind kind,
                          WorldPosition destination, Lane lane, int priority, long tick) {
        registry.colony(colony); Demand.quantity(required);
        Demand existing = demands.get(id);
        if (existing != null) {
            var s = existing.snapshot();
            if (!s.colonyId().equals(colony) || !s.ownerId().equals(owner) || !s.matcher().equals(matcher)
                    || s.required() != required || s.goalKind() != kind || !s.destination().equals(destination)
                    || s.lane() != lane || s.priority() != priority) throw new IllegalArgumentException("Demand identity already used");
            return existing;
        }
        if (!registry.colony(colony).available()) throw new IllegalStateException("Colony unavailable");
        unique(id); if (demands.size() >= MAX_DEMANDS) throw new IllegalArgumentException("Demand envelope exceeded");
        Demand value = new Demand(new Demand.Snapshot(id, colony, owner, matcher, kind, destination, required, 0, 0, 0, 0, 0, lane, priority, tick,
                required == 0 ? Demand.Status.COMPLETED : Demand.Status.ACTIVE));
        AdmissionLedger.Lease lease = admit(colony, lane, Resource.DEMANDS);
        try { registry.beforeMutation(); } catch (RuntimeException failure) { lease.close(); throw failure; }
        demands.put(id, value); leases.put(id, lease); return value;
    }
    public void status(UUID id, Demand.Status status) {
        Demand d = demand(id); var s = d.snapshot(); Objects.requireNonNull(status);
        if (s.status() == status || s.status() == Demand.Status.CANCELLED || s.status() == Demand.Status.COMPLETED) return;
        var next = state(s, s.required(), s.fulfilled(), s.allocated(), s.covered(), s.deliveredTotal(), status);
        registry.beforeMutation(); d.replace(next);
    }
    private void unique(UUID id) { Objects.requireNonNull(id); if (usedId(id) || registry.usedId(id)) throw new IllegalArgumentException("Supply identity already used"); }
    private UUID fresh() { UUID id; do { id = UUID.randomUUID(); } while (usedId(id) || registry.usedId(id)); return id; }
    private AdmissionLedger.Lease admit(UUID colony, Lane lane, Resource resource) { return registry.admission().reserve(colony, lane, Map.of(resource, 1)); }
    private static Demand.Snapshot state(Demand.Snapshot s, long required, long fulfilled, long allocated, long covered, long delivered, Demand.Status status) {
        if (status != Demand.Status.CANCELLED && fulfilled >= required) status = Demand.Status.COMPLETED;
        else if (status == Demand.Status.COMPLETED) status = Demand.Status.ACTIVE;
        return new Demand.Snapshot(s.id(), s.colonyId(), s.ownerId(), s.matcher(), s.goalKind(), s.destination(), required, fulfilled, allocated, covered,
                delivered, Math.addExact(s.revision(), 1), s.lane(), s.priority(), s.createdTick(), status);
    }
    private Demand.Snapshot totals(Demand.Snapshot old, Collection<CoverageShare> all, long required, long delivered, Demand.Status status) {
        long fulfilled = 0, allocated = 0, covered = 0;
        for (CoverageShare share : all) if (share.demandId().equals(old.id())) {
            if (share.stage() == CoverageShare.Stage.FULFILLED) fulfilled = Math.addExact(fulfilled, share.quantity());
            else if (share.stage() == CoverageShare.Stage.ALLOCATED) allocated = Math.addExact(allocated, share.quantity());
            else covered = Math.addExact(covered, share.quantity());
        }
        return state(old, required, fulfilled, allocated, covered, delivered, status);
    }
    private void active(Demand.Snapshot s, ItemDescriptor item, long quantity) {
        if (s.status() == Demand.Status.CANCELLED || s.status() == Demand.Status.COMPLETED || !s.matcher().matches(item)
                || quantity <= 0 || quantity > s.deficit()) throw new IllegalArgumentException("Coverage exceeds active matching deficit");
    }
    public CoverageShare reserveStock(UUID shareId, UUID demandId, UUID obligationId, StockRegion slot, ItemDescriptor item, long quantity, long tick) {
        var s = demand(demandId).snapshot(); CoverageShare old = shares.get(shareId);
        if (old != null) {
            if (old.demandId().equals(demandId) && Objects.equals(old.obligationId(), obligationId) && Objects.equals(old.slot(), slot)
                    && old.item().equals(item) && old.quantity() == quantity) return old;
            throw new IllegalArgumentException("Share identity already used");
        }
        active(s, item, quantity); unique(shareId); unique(obligationId); if (shareId.equals(obligationId)) throw new IllegalArgumentException("Duplicate coverage identity");
        if (shares.size() >= MAX_SHARES) throw new IllegalArgumentException("Coverage envelope exceeded");
        CoverageShare share = new CoverageShare(shareId, s.colonyId(), demandId, null, obligationId, slot, item, quantity, 0, CoverageShare.Stage.RESERVED_STOCK);
        var next = state(s, s.required(), s.fulfilled(), s.allocated(), Math.addExact(s.covered(), quantity), s.deliveredTotal(), Demand.Status.ACTIVE);
        AdmissionLedger.Lease lease = admit(s.colonyId(), s.lane(), Resource.COVERAGE_SHARES);
        try { registry.storage().reserveAll(List.of(new ReservationLedger.Entry(obligationId, s.colonyId(), s.ownerId(), slot, item, quantity, 0, s.lane())), tick); }
        catch (RuntimeException failure) { lease.close(); throw failure; }
        putShare(shareId, share); leases.put(shareId, lease); demand(demandId).replace(next); return share;
    }
    public DeliveryOrder coverStock(UUID demandId, StockRegion slot, ItemDescriptor item, long quantity, long tick) {
        var s = demand(demandId).snapshot(); active(s, item, quantity);
        UUID orderId = fresh(), shareId = fresh(), obligationId = fresh();
        DeliveryOrder order = new DeliveryOrder(orderId, s.colonyId(), demandId, slot, s.destination(), item, quantity, 0, 0, null, null, DeliveryOrder.State.PLANNED, s.lane(), s.priority());
        if (productions.size() + deliveries.size() >= MAX_ORDERS) throw new IllegalArgumentException("Order envelope exceeded");
        AdmissionLedger.Lease lease = admit(s.colonyId(), s.lane(), Resource.DELIVERIES_AND_PRODUCTION_ORDERS);
        try { reserveStock(shareId, demandId, obligationId, slot, item, quantity, tick); }
        catch (RuntimeException failure) { lease.close(); throw failure; }
        CoverageShare share = shares.get(shareId);
        putShare(shareId, new CoverageShare(share.id(), share.colonyId(), share.demandId(), orderId, share.obligationId(), share.slot(), share.item(), share.quantity(), share.revision(), share.stage()));
        deliveries.put(orderId, order); leases.put(orderId, lease); return order;
    }
    public void coverProductionKit(UUID productionId, List<ReservationLedger.Entry> candidates, long tick) {
        registry.requireOwner(); ProductionOrder order = productions.get(productionId);
        if (order == null || candidates.isEmpty() || candidates.size() > 16) throw new IllegalArgumentException("Invalid production kit");
        List<Demand.Snapshot> children = new ArrayList<>();
        for (int i = 0; i < order.recipe().ingredients().size(); i++) children.add(ingredientDemand(order, i, Math.multiplyExact(order.batches(), order.recipe().ingredients().get(i).count()), tick).snapshot());
        for (var child : children) if (child.covered() != 0 || child.status() == Demand.Status.CANCELLED) throw new IllegalStateException("Production dependency not physically ready");
        children.sort(Comparator.comparing((Demand.Snapshot d) -> d.matcher().exact() == null));
        Map<UUID, Long> remaining = new LinkedHashMap<>(); for (var child : children) remaining.put(child.id(), child.deficit());
        List<ReservationLedger.Entry> reservations = new ArrayList<>(); List<CoverageShare> proposed = new ArrayList<>(); List<DeliveryOrder> orders = new ArrayList<>();
        for (var candidate : candidates) {
            if (!candidate.colonyId().equals(order.colonyId())) throw new IllegalArgumentException("Foreign kit stock");
            long available = candidate.count();
            for (var child : children) {
                long count = Math.min(available, remaining.get(child.id()));
                if (count == 0 || !child.matcher().matches(candidate.item())) continue;
                UUID obligationId = reservations.stream().noneMatch(r -> r.id().equals(candidate.id())) ? candidate.id() : fresh();
                unique(obligationId);
                UUID deliveryId = fresh(), shareId = fresh();
                reservations.add(new ReservationLedger.Entry(obligationId, order.colonyId(), child.ownerId(), candidate.slot(), candidate.item(), count, 0, child.lane()));
                orders.add(new DeliveryOrder(deliveryId, order.colonyId(), child.id(), candidate.slot(), child.destination(), candidate.item(), count, 0, 0, null, null, DeliveryOrder.State.PLANNED, child.lane(), child.priority()));
                proposed.add(new CoverageShare(shareId, order.colonyId(), child.id(), deliveryId, obligationId, candidate.slot(), candidate.item(), count, 0, CoverageShare.Stage.RESERVED_STOCK));
                available -= count; remaining.put(child.id(), remaining.get(child.id()) - count);
            }
            if (available != 0) throw new IllegalArgumentException("Kit exceeds ingredient deficits");
        }
        for (long deficit : remaining.values()) if (deficit != 0) throw new IllegalStateException("Incomplete production kit");
        if (reservations.size() > 16 || kitShares.containsKey(productionId) || shares.size() + proposed.size() > MAX_SHARES || productions.size() + deliveries.size() + orders.size() > MAX_ORDERS) throw new IllegalArgumentException("Production kit envelope exceeded");
        Map<UUID, Demand.Snapshot> states = new LinkedHashMap<>();
        for (var child : children) { long addition = 0; for (var share : proposed) if (share.demandId().equals(child.id())) addition += share.quantity();
            if (addition != 0) states.put(child.id(), state(child, child.required(), child.fulfilled(), child.allocated(), child.covered() + addition, child.deliveredTotal(), Demand.Status.ACTIVE)); }
        Map<UUID, AdmissionLedger.Lease> admitted = new LinkedHashMap<>();
        try {
            for (var share : proposed) admitted.put(share.id(), admit(order.colonyId(), demand(share.demandId()).snapshot().lane(), Resource.COVERAGE_SHARES));
            for (var delivery : orders) admitted.put(delivery.id(), admit(order.colonyId(), delivery.lane(), Resource.DELIVERIES_AND_PRODUCTION_ORDERS));
            registry.storage().reserveAll(reservations, tick);
        } catch (RuntimeException failure) { admitted.values().forEach(AdmissionLedger.Lease::close); throw failure; }
        leases.putAll(admitted); orders.forEach(o -> deliveries.put(o.id(), o)); proposed.forEach(s -> putShare(s.id(), s)); states.forEach((id, s) -> demand(id).replace(s));
    }
    public ProductionOrder promiseProduction(UUID demandId, RecipeDefinition recipe, long batches) {
        var s = demand(demandId).snapshot(); long output = Math.multiplyExact(batches, recipe.outputCount()); Demand.quantity(output);
        long quantity = Math.min(output, s.deficit()); active(s, recipe.output(), quantity);
        ProductionOrder order = new ProductionOrder(fresh(), s.colonyId(), demandId, recipe, batches, Math.multiplyExact(batches, recipe.activeTicks()), 0, null, null, ProductionOrder.State.PLANNED, s.lane(), s.priority());
        CoverageShare share = new CoverageShare(fresh(), s.colonyId(), demandId, order.id(), null, null, recipe.output(), quantity, 0, CoverageShare.Stage.PROMISED_OUTPUT);
        planProduction(order, List.of(share)); return order;
    }
    public void planProduction(ProductionOrder order, List<CoverageShare> promised) {
        registry.colony(order.colonyId()); unique(order.id());
        if (productions.size() + deliveries.size() >= MAX_ORDERS || shares.size() + promised.size() > MAX_SHARES || promised.isEmpty()) throw new IllegalArgumentException("Production envelope exceeded");
        var owner = demand(order.ownerDemandId()).snapshot();
        if (!owner.colonyId().equals(order.colonyId()) || order.workId() != null || order.citizenId() != null) throw new IllegalArgumentException("Invalid planned production owner");
        Map<UUID, CoverageShare> staged = new LinkedHashMap<>(shares); Set<UUID> ids = new HashSet<>(); ids.add(order.id());
        long output = 0; Map<UUID, Demand.Snapshot> changed = new LinkedHashMap<>();
        for (CoverageShare share : promised) {
            unique(share.id()); if (!ids.add(share.id()) || share.stage() != CoverageShare.Stage.PROMISED_OUTPUT || !order.id().equals(share.sourceOrderId())
                    || !order.colonyId().equals(share.colonyId()) || !order.recipe().output().equals(share.item())) throw new IllegalArgumentException("Invalid production promise");
            var s = demand(share.demandId()).snapshot(); active(s, share.item(), share.quantity()); staged.put(share.id(), share); output = Math.addExact(output, share.quantity());
            changed.put(s.id(), totals(s, staged.values(), s.required(), s.deliveredTotal(), Demand.Status.ACTIVE));
            if (changed.get(s.id()).covered() - s.covered() > s.deficit()) throw new IllegalArgumentException("Duplicate promise exceeds demand");
        }
        if (output > Math.multiplyExact(order.batches(), order.recipe().outputCount())) throw new IllegalArgumentException("Promise exceeds pinned output");
        validatePins(order.recipe());
        List<Demand> ingredients = new ArrayList<>();
        for (int i = 0; i < order.recipe().ingredients().size(); i++) {
            var ingredient = order.recipe().ingredients().get(i);
            UUID id = UUID.nameUUIDFromBytes((order.id() + ":ingredient:" + i).getBytes(StandardCharsets.UTF_8));
            unique(id); if (!ids.add(id)) throw new IllegalArgumentException("Ingredient identity collision");
            long quantity = Math.multiplyExact(order.batches(), ingredient.count());
            ingredients.add(new Demand(new Demand.Snapshot(id, order.colonyId(), order.id(), ingredient.matcher(), Demand.GoalKind.CONSUMPTION,
                    owner.destination(), quantity, 0, 0, 0, 0, 0, order.lane(), order.priority(), owner.createdTick(), Demand.Status.ACTIVE)));
        }
        if (demands.size() + ingredients.size() > MAX_DEMANDS) throw new IllegalArgumentException("Ingredient demand envelope exceeded");
        Map<UUID, AdmissionLedger.Lease> admitted = new LinkedHashMap<>();
        try {
            admitted.put(order.id(), admit(order.colonyId(), order.lane(), Resource.DELIVERIES_AND_PRODUCTION_ORDERS));
            for (CoverageShare share : promised) admitted.put(share.id(), admit(share.colonyId(), demand(share.demandId()).snapshot().lane(), Resource.COVERAGE_SHARES));
            for (Demand ingredient : ingredients) admitted.put(ingredient.id(), admit(order.colonyId(), order.lane(), Resource.DEMANDS));
            registry.beforeMutation();
        } catch (RuntimeException failure) { admitted.values().forEach(AdmissionLedger.Lease::close); throw failure; }
        productions.put(order.id(), order); promised.forEach(s -> putShare(s.id(), s)); leases.putAll(admitted); changed.forEach((id, value) -> demand(id).replace(value));
        ingredients.forEach(d -> demands.put(d.id(), d));
        recomputePriorities();
    }
    private boolean dependsOn(UUID demandId, UUID orderId) {
        Set<UUID> visited = new HashSet<>(); UUID current = demandId;
        while (visited.add(current)) {
            ProductionOrder parent = productions.get(demand(current).snapshot().ownerId());
            if (parent == null) return false; if (parent.id().equals(orderId)) return true; current = parent.ownerDemandId();
        }
        return true;
    }
    private void validatePins(RecipeDefinition addition) {
        validatePins(registry.construction().definitions(), productions.values().stream().map(ProductionOrder::recipe).toList(), addition);
    }
    public static void validatePins(List<io.github.kpuctajluk.colonyloom.core.content.BlueprintDefinition> blueprints, List<RecipeDefinition> recipes, RecipeDefinition addition) {
        Map<String, RecipeDefinition> distinct = new HashMap<>();
        for (RecipeDefinition recipe : recipes) { var old = distinct.putIfAbsent(recipe.digest(), recipe); if (old != null && !old.equals(recipe)) throw new IllegalArgumentException("Pinned recipe digest collision"); }
        if (addition != null) { var old = distinct.putIfAbsent(addition.digest(), addition); if (old != null && !old.equals(addition)) throw new IllegalArgumentException("Pinned recipe digest collision"); }
        long bytes = 0;
        for (var definition : blueprints) bytes = Math.addExact(bytes, io.github.kpuctajluk.colonyloom.core.construction.ConstructionRegistry.estimatedBytes(definition));
        for (var recipe : distinct.values()) bytes = Math.addExact(bytes, estimatedRecipeBytes(recipe));
        if (blueprints.size() + distinct.size() > 64 || bytes > 64L * 1024 * 1024) throw new IllegalArgumentException("Combined pinned definition capacity exceeded");
    }
    public static long estimatedRecipeBytes(RecipeDefinition recipe) {
        long bytes = 1024 + 4L * (recipe.id().length() + recipe.professionId().length() + recipe.equipmentId().length()) + recipe.output().canonicalComponents().length;
        for (var ingredient : recipe.ingredients()) bytes += 256 + ingredient.matcher().itemId().length() * 4L + (ingredient.matcher().exact() == null ? 0 : ingredient.matcher().exact().canonicalComponents().length);
        return bytes;
    }
    public boolean sharedOutput(UUID demandId) { return coverExistingProduction(demandId, demand(demandId).deficit()); }
    public boolean coverExistingProduction(UUID demandId, long quantity) {
        var s = demand(demandId).snapshot(); if (quantity <= 0 || quantity > s.deficit()) return false;
        for (ProductionOrder order : List.copyOf(productions.values())) {
            if (!order.colonyId().equals(s.colonyId()) || !s.matcher().matches(order.recipe().output()) || dependsOn(s.id(), order.id())) continue;
            long promised = 0; for (CoverageShare share : shares.values()) if (order.id().equals(share.sourceOrderId())) promised = Math.addExact(promised, share.quantity());
            if (Math.multiplyExact(order.batches(), order.recipe().outputCount()) - promised < quantity) continue;
            if (shares.size() >= MAX_SHARES) throw new IllegalArgumentException("Coverage envelope exceeded");
            CoverageShare share = new CoverageShare(fresh(), s.colonyId(), demandId, order.id(), null, null, order.recipe().output(), quantity, 0, CoverageShare.Stage.PROMISED_OUTPUT);
            var next = state(s, s.required(), s.fulfilled(), s.allocated(), Math.addExact(s.covered(), quantity), s.deliveredTotal(), Demand.Status.ACTIVE);
            AdmissionLedger.Lease lease = admit(s.colonyId(), s.lane(), Resource.COVERAGE_SHARES);
            try { registry.beforeMutation(); } catch (RuntimeException failure) { lease.close(); throw failure; }
            putShare(share.id(), share); leases.put(share.id(), lease); demand(demandId).replace(next); recomputePriorities(); return true;
        }
        return false;
    }
    public Demand ingredientDemand(ProductionOrder order, int index, long required, long tick) {
        ProductionOrder actual = productions.get(order.id()); if (actual == null || !actual.recipe().equals(order.recipe())) throw new IllegalArgumentException("Unknown production order");
        var ingredient = actual.recipe().ingredients().get(index); var owner = demand(actual.ownerDemandId()).snapshot();
        UUID id = UUID.nameUUIDFromBytes((actual.id() + ":ingredient:" + index).getBytes(StandardCharsets.UTF_8));
        Demand existing = demand(id);
        if (existing.snapshot().required() != required || !existing.snapshot().matcher().equals(ingredient.matcher())) throw new IllegalArgumentException("Ingredient quantity differs from pinned batch");
        return existing;
    }
    /** Moves only already reserved physical stock into an actual cargo reservation. */
    public void inTransit(UUID shareId, UUID orderId, StockRegion cargoSlot, UUID cargoObligation, long tick) {
        CoverageShare share = requiredShare(shareId); var d = demand(share.demandId()).snapshot(); DeliveryOrder order = deliveries.get(orderId);
        if (share.stage() != CoverageShare.Stage.RESERVED_STOCK || order == null || !orderId.equals(share.sourceOrderId()) || !order.ownerDemandId().equals(d.id())) throw new IllegalArgumentException("Invalid pickup reference");
        var held = registry.storage().reservations().get(share.obligationId()); if (held == null || held.count() != share.quantity()) throw new IllegalStateException("Pickup reservation changed");
        CoverageShare next = new CoverageShare(share.id(), share.colonyId(), share.demandId(), orderId, cargoObligation, cargoSlot, share.item(), share.quantity(), Math.addExact(share.revision(), 1), CoverageShare.Stage.IN_TRANSIT);
        var nextDemand = totals(d, replacement(share.id(), next).values(), d.required(), d.deliveredTotal(), d.status());
        DeliveryOrder nextOrder = delivery(order, order.transferred(), DeliveryOrder.State.IN_TRANSIT);
        registry.storage().replaceObligations(List.of(share.obligationId()), List.of(new ReservationLedger.Entry(cargoObligation, share.colonyId(), d.ownerId(), cargoSlot, share.item(), share.quantity(), 0, d.lane())), List.of(), tick);
        putShare(share.id(), next); demand(d.id()).replace(nextDemand); deliveries.put(orderId, nextOrder);
    }
    /** Confirmation follows actual native transfer. Consumption retains destination allocation; DELIVERY fulfils directly. */
    public CoverageShare transfer(UUID shareId, long quantity, StockRegion destinationSlot, UUID allocationId, long tick) {
        CoverageShare share = requiredShare(shareId); var d = demand(share.demandId()).snapshot();
        if ((share.stage() != CoverageShare.Stage.RESERVED_STOCK && share.stage() != CoverageShare.Stage.IN_TRANSIT) || quantity <= 0 || quantity > share.quantity()) throw new IllegalArgumentException("Invalid transferred portion");
        var held = registry.storage().reservations().get(share.obligationId()); if (held == null || held.count() < share.quantity()) throw new IllegalStateException("Transfer reservation changed");
        boolean allocation = d.goalKind() == Demand.GoalKind.CONSUMPTION;
        UUID resultId = quantity == share.quantity() ? share.id() : fresh();
        CoverageShare result = new CoverageShare(resultId, share.colonyId(), share.demandId(), share.sourceOrderId(), allocation ? allocationId : null,
                allocation ? destinationSlot : null, share.item(), quantity, Math.addExact(share.revision(), 1), allocation ? CoverageShare.Stage.ALLOCATED : CoverageShare.Stage.FULFILLED);
        Map<UUID, CoverageShare> staged = new LinkedHashMap<>(shares); staged.remove(shareId);
        List<ReservationLedger.Entry> reservations = new ArrayList<>();
        if (quantity < share.quantity()) {
            staged.put(shareId, resized(share, share.quantity() - quantity));
            reservations.add(new ReservationLedger.Entry(share.obligationId(), share.colonyId(), d.ownerId(), share.slot(), share.item(), share.quantity() - quantity, Math.addExact(held.revision(), 1), d.lane()));
        }
        staged.put(resultId, result); var nextDemand = totals(d, staged.values(), d.required(), Math.addExact(d.deliveredTotal(), quantity), d.status());
        DeliveryOrder order = share.sourceOrderId() == null ? null : deliveries.get(share.sourceOrderId());
        DeliveryOrder nextOrder = order == null ? null : delivery(order, Math.addExact(order.transferred(), quantity), order.transferred() + quantity == order.quantity() ? DeliveryOrder.State.COMPLETED : DeliveryOrder.State.TRANSFERRED);
        AdmissionLedger.Lease extra = null;
        try {
            if (!resultId.equals(shareId)) { if (shares.size() >= MAX_SHARES) throw new IllegalArgumentException("Coverage envelope exceeded"); extra = admit(d.colonyId(), d.lane(), Resource.COVERAGE_SHARES); }
            List<AllocationLedger.Entry> allocations = allocation ? List.of(new AllocationLedger.Entry(allocationId, d.colonyId(), d.ownerId(), destinationSlot, share.item(), quantity, 0, d.lane())) : List.of();
            registry.storage().replaceObligations(List.of(share.obligationId()), reservations, allocations, tick);
        } catch (RuntimeException failure) { if (extra != null) extra.close(); throw failure; }
        clearShares(); putShares(staged); if (extra != null) leases.put(resultId, extra); demand(d.id()).replace(nextDemand);
        if (nextOrder != null) deliveries.put(nextOrder.id(), nextOrder); recomputePriorities(); return result;
    }
    public void fulfillConsumption(UUID shareId, long quantity) {
        CoverageShare share = requiredShare(shareId); var d = demand(share.demandId()).snapshot();
        if (share.stage() != CoverageShare.Stage.ALLOCATED || d.goalKind() != Demand.GoalKind.CONSUMPTION || quantity <= 0 || quantity > share.quantity()) throw new IllegalArgumentException("Invalid consumption");
        var allocation = registry.storage().allocations().get(share.obligationId()); if (allocation == null || allocation.count() < share.quantity()) throw new IllegalStateException("Consumed allocation changed");
        UUID id = quantity == share.quantity() ? share.id() : fresh();
        CoverageShare fulfilled = new CoverageShare(id, share.colonyId(), share.demandId(), share.sourceOrderId(), null, null, share.item(), quantity, Math.addExact(share.revision(), 1), CoverageShare.Stage.FULFILLED);
        Map<UUID, CoverageShare> staged = new LinkedHashMap<>(shares); staged.remove(shareId); if (quantity < share.quantity()) staged.put(shareId, resized(share, share.quantity() - quantity)); staged.put(id, fulfilled);
        var next = totals(d, staged.values(), d.required(), d.deliveredTotal(), d.status()); AdmissionLedger.Lease extra = null;
        try {
            if (!id.equals(shareId)) { if (shares.size() >= MAX_SHARES) throw new IllegalArgumentException("Coverage envelope exceeded"); extra = admit(d.colonyId(), d.lane(), Resource.COVERAGE_SHARES); }
            registry.storage().reduceObligations(Map.of(share.obligationId(), allocation.count() - quantity));
        } catch (RuntimeException failure) { if (extra != null) extra.close(); throw failure; }
        clearShares(); putShares(staged); if (extra != null) leases.put(id, extra); demand(d.id()).replace(next);
    }
    private CoverageShare requiredShare(UUID id) { registry.requireOwner(); CoverageShare s = shares.get(id); if (s == null) throw new IllegalArgumentException("Unknown coverage share"); return s; }
    private Map<UUID, CoverageShare> replacement(UUID id, CoverageShare next) { Map<UUID, CoverageShare> map = new LinkedHashMap<>(shares); map.put(id, next); return map; }
    private static CoverageShare resized(CoverageShare s, long count) { return new CoverageShare(s.id(), s.colonyId(), s.demandId(), s.sourceOrderId(), s.obligationId(), s.slot(), s.item(), count, Math.addExact(s.revision(), 1), s.stage()); }
    private static DeliveryOrder delivery(DeliveryOrder o, long transferred, DeliveryOrder.State state) {
        return new DeliveryOrder(o.id(), o.colonyId(), o.ownerDemandId(), o.source(), o.destination(), o.item(), o.quantity(), transferred, Math.addExact(o.revision(), 1), o.citizenId(), o.workId(), state, o.lane(), o.priority());
    }
    /** Loss notifications are deduplicated by real obligation; unknown generates none. */
    public void reconcile(long tick, io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets budgets) {
        registry.requireOwner(); if (tick < 0) throw new IllegalArgumentException("Negative reconcile tick");
        while (!dirtyObligations.isEmpty()) {
            UUID obligation = dirtyObligations.iterator().next(); UUID id = obligationShares.get(obligation);
            if (id == null) { dirtyObligations.remove(obligation); continue; }
            CoverageShare share = shares.get(id); var d = demand(share.demandId()).snapshot();
            if (budgets != null && !budgets.tryConsume(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget.STORAGE_SLOT_CHECKS, d.lane())) return;
            reconcileShare(share); dirtyObligations.remove(obligation);
        }
    }
    public void reconcile() { reconcile(0, null); }
    private void reconcileShare(CoverageShare share) {
        long actual;
        if (share.stage() == CoverageShare.Stage.ALLOCATED) { var a = registry.storage().allocations().get(share.obligationId()); actual = a == null ? 0 : a.count(); }
        else { var r = registry.storage().reservations().get(share.obligationId()); actual = r == null ? 0 : r.count(); }
        if (actual >= share.quantity()) return;
        var root = demand(share.demandId()).snapshot(); UUID owner = root.ownerId();
        List<CoverageShare> affected = new ArrayList<>();
        if (share.stage() == CoverageShare.Stage.RESERVED_STOCK && productions.containsKey(owner)) {
            Set<UUID> ids = kitShares.get(owner); if (ids != null) for (UUID id : ids) affected.add(shares.get(id));
            if (affected.size() > 16) throw new IllegalStateException("Production kit exceeds atomic slot envelope");
        } else affected.add(share);
        Map<UUID, Long> releases = new LinkedHashMap<>(); Map<UUID, Demand.Snapshot> states = new LinkedHashMap<>();
        for (CoverageShare old : affected) {
            long kept = old.stage() == CoverageShare.Stage.RESERVED_STOCK && productions.containsKey(owner) ? 0 : actual;
            var s = states.getOrDefault(old.demandId(), demand(old.demandId()).snapshot()); long loss = old.quantity() - kept;
            states.put(s.id(), state(s, s.required(), s.fulfilled(), s.allocated() - (old.stage() == CoverageShare.Stage.ALLOCATED ? loss : 0), s.covered() - (old.covered() ? loss : 0), s.deliveredTotal(), s.status()));
            if (kept == 0 && registry.storage().reservations().get(old.obligationId()) != null) releases.put(old.obligationId(), 0L);
        }
        registry.beforeMutation(); registry.storage().reduceObligations(releases);
        for (CoverageShare old : affected) {
            boolean keepPartial = old.stage() != CoverageShare.Stage.RESERVED_STOCK || !productions.containsKey(owner);
            if (keepPartial && actual > 0) putShare(old.id(), resized(old, actual));
            else { shares.remove(old.id()); unindex(old); AdmissionLedger.Lease lease = leases.remove(old.id()); if (lease != null) lease.close(); }
            if (old.sourceOrderId() != null) { DeliveryOrder order = deliveries.get(old.sourceOrderId()); if (order != null && order.state() != DeliveryOrder.State.COMPLETED) deliveries.put(order.id(), delivery(order, order.transferred(), DeliveryOrder.State.BLOCKED)); }
        }
        states.forEach((id, s) -> demand(id).replace(s));
    }
    private Map<UUID, Demand.Snapshot> recomputed(Map<UUID, CoverageShare> staged) {
        Map<UUID, Demand.Snapshot> result = new LinkedHashMap<>();
        for (Demand d : demands.values()) { var s = d.snapshot(); boolean changed = !shares.values().stream().filter(v -> v.demandId().equals(s.id())).toList().equals(staged.values().stream().filter(v -> v.demandId().equals(s.id())).toList());
            if (changed) result.put(s.id(), totals(s, staged.values(), s.required(), s.deliveredTotal(), s.status())); }
        return result;
    }
    private void publishShares(Map<UUID, CoverageShare> staged) {
        for (UUID id : List.copyOf(shares.keySet())) if (!staged.containsKey(id)) { AdmissionLedger.Lease lease = leases.remove(id); if (lease != null) lease.close(); }
        clearShares(); putShares(staged);
    }
    public void release(UUID shareId) {
        CoverageShare share = requiredShare(shareId);
        if (share.stage() == CoverageShare.Stage.FULFILLED || share.stage() == CoverageShare.Stage.IN_TRANSIT) throw new IllegalArgumentException("Cannot erase consumed history or actual cargo");
        Map<UUID, CoverageShare> staged = new LinkedHashMap<>(shares); staged.remove(shareId); var states = recomputed(staged);
        registry.beforeMutation(); if (share.obligationId() != null) registry.storage().reduceObligations(Map.of(share.obligationId(), 0L));
        publishShares(staged); states.forEach((id, s) -> demand(id).replace(s)); blockUnreferencedDeliveries(); recomputePriorities();
    }
    public void reduceRequired(UUID demandId, long required) { reduce(demandId, required, false); }
    public void cancel(UUID demandId) { reduce(demandId, demand(demandId).snapshot().required(), true); }
    private void reduce(UUID demandId, long required, boolean cancel) {
        Demand d = demand(demandId); var old = d.snapshot(); Demand.quantity(required);
        if (!cancel && required > old.required()) throw new IllegalArgumentException("Required reduction cannot increase goal");
        if (old.status() == Demand.Status.CANCELLED || !cancel && required == old.required()) return;
        long excess = cancel ? Long.MAX_VALUE : Math.max(0, old.fulfilled() + old.allocated() + old.covered() - required);
        Map<UUID, CoverageShare> staged = new LinkedHashMap<>(shares); Map<UUID, Long> reductions = new LinkedHashMap<>();
        for (CoverageShare.Stage stage : List.of(CoverageShare.Stage.PROMISED_OUTPUT, CoverageShare.Stage.RESERVED_STOCK, CoverageShare.Stage.ALLOCATED)) {
            for (CoverageShare share : shares.values()) if (share.demandId().equals(demandId) && share.stage() == stage && excess > 0) {
                long released = Math.min(excess, share.quantity()), kept = share.quantity() - released; excess -= released;
                if (kept == 0) staged.remove(share.id()); else staged.put(share.id(), resized(share, kept));
                if (share.obligationId() != null) reductions.put(share.obligationId(), kept);
            }
        }
        var next = totals(old, staged.values(), required, old.deliveredTotal(), cancel ? Demand.Status.CANCELLED : Demand.Status.ACTIVE);
        registry.beforeMutation(); registry.storage().reduceObligations(reductions); publishShares(staged); d.replace(next);
        if (cancel) for (CoverageShare share : staged.values()) if (share.demandId().equals(demandId) && share.stage() == CoverageShare.Stage.IN_TRANSIT) {
            DeliveryOrder order = deliveries.get(share.sourceOrderId()); if (order != null) deliveries.put(order.id(), delivery(order, order.transferred(), DeliveryOrder.State.RETURNING));
        }
        blockUnreferencedDeliveries(); recomputePriorities();
    }
    private void blockUnreferencedDeliveries() {
        for (DeliveryOrder order : List.copyOf(deliveries.values())) if (order.state() != DeliveryOrder.State.COMPLETED && order.state() != DeliveryOrder.State.BLOCKED && shares.values().stream().noneMatch(s -> order.id().equals(s.sourceOrderId())))
            deliveries.put(order.id(), delivery(order, order.transferred(), DeliveryOrder.State.BLOCKED));
    }
    private static int laneRank(Lane lane) { return lane == Lane.CRITICAL ? 2 : lane == Lane.SERVICE ? 1 : 0; }
    /** Every removed dependency recomputes inherited urgency instead of keeping a historical maximum. */
    private void recomputePriorities() {
        boolean changed;
        int rounds = 0;
        do {
            changed = false;
            for (ProductionOrder order : List.copyOf(productions.values())) {
                Lane lane = Lane.NORMAL; int priority = Integer.MIN_VALUE; boolean found = false;
                for (CoverageShare share : shares.values()) if (order.id().equals(share.sourceOrderId()) && share.stage() != CoverageShare.Stage.FULFILLED) {
                    var d = demand(share.demandId()).snapshot(); if (d.status() == Demand.Status.CANCELLED) continue;
                    if (!found || laneRank(d.lane()) > laneRank(lane)) lane = d.lane(); priority = Math.max(priority, d.priority()); found = true;
                }
                if (!found) priority = 0;
                if (lane != order.lane() || priority != order.priority()) {
                    productions.put(order.id(), new ProductionOrder(order.id(), order.colonyId(), order.ownerDemandId(), order.recipe(), order.batches(), order.remainingActiveTicks(), Math.addExact(order.revision(), 1), order.workId(), order.citizenId(), order.state(), lane, priority)); changed = true;
                }
                for (Demand ingredient : demands.values()) {
                    var s = ingredient.snapshot(); if (!s.ownerId().equals(order.id()) || s.status() == Demand.Status.CANCELLED) continue;
                    if (s.lane() != lane || s.priority() != priority) {
                        ingredient.replace(new Demand.Snapshot(s.id(), s.colonyId(), s.ownerId(), s.matcher(), s.goalKind(), s.destination(), s.required(), s.fulfilled(), s.allocated(), s.covered(), s.deliveredTotal(), Math.addExact(s.revision(), 1), lane, priority, s.createdTick(), s.status())); changed = true;
                    }
                }
            }
        } while (changed && ++rounds <= productions.size());
        for (DeliveryOrder order : List.copyOf(deliveries.values())) {
            var s = demand(order.ownerDemandId()).snapshot();
            if (order.lane() != s.lane() || order.priority() != s.priority()) deliveries.put(order.id(), new DeliveryOrder(order.id(), order.colonyId(), order.ownerDemandId(), order.source(), order.destination(), order.item(), order.quantity(), order.transferred(), Math.addExact(order.revision(), 1), order.citizenId(), order.workId(), order.state(), s.lane(), s.priority()));
        }
    }

    /** Validates against the staged storage snapshot, never the live pre-restore ledger. */
    public PreparedRestore prepareRestore(SupplySnapshot snapshot, StorageSnapshot storage, AdmissionLedger replacement, Collection<ColonyRuntime> colonies) {
        registry.requireOwner(); Objects.requireNonNull(snapshot); Objects.requireNonNull(storage); Objects.requireNonNull(replacement);
        if (snapshot.demands().size() > MAX_DEMANDS || snapshot.shares().size() > MAX_SHARES || snapshot.productionOrders().size() + snapshot.deliveries().size() > MAX_ORDERS) throw new IllegalArgumentException("Supply persistence envelope exceeded");
        Set<UUID> colonyIds = new HashSet<>(); for (ColonyRuntime colony : colonies) if (!colonyIds.add(colony.colonyId())) throw new IllegalArgumentException("Duplicate supplied colony");
        Map<UUID, Demand.Snapshot> ds = new LinkedHashMap<>(); Map<UUID, ProductionOrder> ps = new LinkedHashMap<>(); Map<UUID, DeliveryOrder> ls = new LinkedHashMap<>();
        Set<UUID> ids = new HashSet<>();
        for (var d : snapshot.demands()) { savedIdentity(ids, colonyIds, d.id(), d.colonyId()); ds.put(d.id(), d); }
        for (var p : snapshot.productionOrders()) { savedIdentity(ids, colonyIds, p.id(), p.colonyId()); var d = ds.get(p.ownerDemandId()); if (d == null || !d.colonyId().equals(p.colonyId()) || !d.matcher().matches(p.recipe().output())) throw new IllegalArgumentException("Invalid production owner"); ps.put(p.id(), p); }
        for (var l : snapshot.deliveries()) { savedIdentity(ids, colonyIds, l.id(), l.colonyId()); var d = ds.get(l.ownerDemandId()); if (d == null || !d.colonyId().equals(l.colonyId()) || !d.matcher().matches(l.item()) || !d.destination().equals(l.destination())) throw new IllegalArgumentException("Invalid delivery owner"); ls.put(l.id(), l); }
        Map<UUID, ReservationLedger.Entry> rs = new HashMap<>(); Map<UUID, AllocationLedger.Entry> as = new HashMap<>();
        Set<UUID> stockIds = new HashSet<>();
        for (var r : storage.registrations()) if (!stockIds.add(r.id())) throw new IllegalArgumentException("Duplicate staged storage ID");
        for (var w : storage.workshops()) if (!stockIds.add(w.id())) throw new IllegalArgumentException("Duplicate staged storage ID");
        for (var r : storage.reservations()) if (!stockIds.add(r.id()) || rs.putIfAbsent(r.id(), r) != null) throw new IllegalArgumentException("Duplicate staged reservation");
        for (var a : storage.allocations()) if (!stockIds.add(a.id()) || as.putIfAbsent(a.id(), a) != null) throw new IllegalArgumentException("Duplicate staged allocation");
        for (UUID id : ids) if (stockIds.contains(id)) throw new IllegalArgumentException("Supply/storage identity collision");
        Map<UUID, Long> output = new HashMap<>(); Set<UUID> obligationIds = new HashSet<>(); Map<UUID, CoverageShare> staged = new LinkedHashMap<>();
        for (CoverageShare share : snapshot.shares()) {
            savedIdentity(ids, colonyIds, share.id(), share.colonyId()); if (stockIds.contains(share.id())) throw new IllegalArgumentException("Share/storage identity collision");
            var d = ds.get(share.demandId()); if (d == null || !d.colonyId().equals(share.colonyId()) || !d.matcher().matches(share.item())) throw new IllegalArgumentException("Invalid share consumer");
            ProductionOrder p = share.sourceOrderId() == null ? null : ps.get(share.sourceOrderId()); DeliveryOrder l = share.sourceOrderId() == null ? null : ls.get(share.sourceOrderId());
            if (share.sourceOrderId() != null && p == null && l == null) throw new IllegalArgumentException("Missing share source order");
            if (p != null) { if (!p.colonyId().equals(share.colonyId()) || !p.recipe().output().equals(share.item())) throw new IllegalArgumentException("Invalid pinned output share"); output.merge(p.id(), share.quantity(), Math::addExact); }
            if (l != null && (!l.colonyId().equals(share.colonyId()) || !l.ownerDemandId().equals(d.id()) || !l.item().equals(share.item()))) throw new IllegalArgumentException("Invalid delivery share");
            if (share.stage() == CoverageShare.Stage.PROMISED_OUTPUT && p == null || share.stage() == CoverageShare.Stage.IN_TRANSIT && (l == null || l.state() != DeliveryOrder.State.IN_TRANSIT && l.state() != DeliveryOrder.State.PICKED_UP && l.state() != DeliveryOrder.State.RETURNING && l.state() != DeliveryOrder.State.TRANSFERRED)) throw new IllegalArgumentException("Share/order stage mismatch");
            if (share.obligationId() != null) {
                if (!obligationIds.add(share.obligationId())) throw new IllegalArgumentException("Physical obligation counted twice");
                if (share.stage() == CoverageShare.Stage.ALLOCATED) {
                    var a = as.get(share.obligationId()); if (a == null || !a.colonyId().equals(d.colonyId()) || !a.ownerId().equals(d.ownerId()) || !a.slot().equals(share.slot()) || !a.item().equals(share.item()) || a.count() != share.quantity()) throw new IllegalArgumentException("Invalid staged allocation referent");
                } else {
                    var r = rs.get(share.obligationId()); if (r == null || !r.colonyId().equals(d.colonyId()) || !r.ownerId().equals(d.ownerId()) || !r.slot().equals(share.slot()) || !r.item().equals(share.item()) || r.count() != share.quantity()) throw new IllegalArgumentException("Invalid staged reservation referent");
                }
            }
            if (d.goalKind() == Demand.GoalKind.DELIVERY && share.stage() == CoverageShare.Stage.ALLOCATED) throw new IllegalArgumentException("Delivery cannot retain allocation");
            staged.put(share.id(), share);
        }
        for (var entry : output.entrySet()) { var p = ps.get(entry.getKey()); if (entry.getValue() > Math.multiplyExact(p.batches(), p.recipe().outputCount())) throw new IllegalArgumentException("Saved output overpromised"); }
        for (var d : ds.values()) {
            long f = 0, a = 0, c = 0;
            for (var s : staged.values()) if (s.demandId().equals(d.id())) { if (s.stage() == CoverageShare.Stage.FULFILLED) f += s.quantity(); else if (s.stage() == CoverageShare.Stage.ALLOCATED) a += s.quantity(); else c += s.quantity(); }
            if (f != d.fulfilled() || a != d.allocated() || c != d.covered() || d.deliveredTotal() < Math.addExact(a, f)) throw new IllegalArgumentException("Saved share arithmetic mismatch");
        }
        validatePins(List.of(), ps.values().stream().map(ProductionOrder::recipe).toList(), null);
        for (ProductionOrder order : ps.values()) {
            for (int i = 0; i < order.recipe().ingredients().size(); i++) {
                UUID id = UUID.nameUUIDFromBytes((order.id() + ":ingredient:" + i).getBytes(StandardCharsets.UTF_8));
                var child = ds.get(id); var ingredient = order.recipe().ingredients().get(i);
                if (child == null || !child.ownerId().equals(order.id()) || !child.colonyId().equals(order.colonyId()) || !child.matcher().equals(ingredient.matcher())
                        || child.required() > Math.multiplyExact(order.batches(), ingredient.count())) throw new IllegalArgumentException("Missing/invalid production ingredient goal");
            }
        }
        for (Demand.Snapshot d : ds.values()) {
            Set<UUID> visited = new HashSet<>(); Demand.Snapshot current = d;
            while (ps.containsKey(current.ownerId())) {
                if (!visited.add(current.id())) throw new IllegalArgumentException("Cyclic saved production owner chain");
                current = ds.get(ps.get(current.ownerId()).ownerDemandId());
            }
        }
        PreparedRestore prepared = new PreparedRestore(snapshot);
        try {
            for (var d : ds.values()) prepared.admitted.put(d.id(), replacement.reserve(d.colonyId(), d.lane(), Map.of(Resource.DEMANDS, 1)));
            for (var s : staged.values()) prepared.admitted.put(s.id(), replacement.reserve(s.colonyId(), ds.get(s.demandId()).lane(), Map.of(Resource.COVERAGE_SHARES, 1)));
            for (var p : ps.values()) prepared.admitted.put(p.id(), replacement.reserve(p.colonyId(), p.lane(), Map.of(Resource.DELIVERIES_AND_PRODUCTION_ORDERS, 1)));
            for (var l : ls.values()) prepared.admitted.put(l.id(), replacement.reserve(l.colonyId(), l.lane(), Map.of(Resource.DELIVERIES_AND_PRODUCTION_ORDERS, 1)));
        } catch (RuntimeException failure) { prepared.close(); throw failure; }
        return prepared;
    }
    private static void savedIdentity(Set<UUID> ids, Set<UUID> colonies, UUID id, UUID colony) { if (!colonies.contains(colony) || !ids.add(id)) throw new IllegalArgumentException("Invalid saved supply identity/colony"); }
    public final class PreparedRestore implements AutoCloseable {
        private SupplySnapshot snapshot;
        private final Map<UUID, AdmissionLedger.Lease> admitted = new LinkedHashMap<>();
        private PreparedRestore(SupplySnapshot snapshot) { this.snapshot = snapshot; }
        public void commit() {
            registry.requireOwner(); if (snapshot == null) throw new IllegalStateException("Supply restore already closed");
            leases.values().forEach(AdmissionLedger.Lease::close); leases.clear(); leases.putAll(admitted); admitted.clear();
            demands.clear(); snapshot.demands().forEach(s -> demands.put(s.id(), new Demand(s)));
            productions.clear(); snapshot.productionOrders().forEach(p -> productions.put(p.id(), p));
            clearShares(); snapshot.shares().forEach(s -> putShare(s.id(), s)); dirtyObligations.clear();
            deliveries.clear(); snapshot.deliveries().forEach(l -> deliveries.put(l.id(), l)); snapshot = null;
        }
        @Override public void close() { registry.requireOwner(); if (snapshot != null) { admitted.values().forEach(AdmissionLedger.Lease::close); admitted.clear(); snapshot = null; } }
    }
}
