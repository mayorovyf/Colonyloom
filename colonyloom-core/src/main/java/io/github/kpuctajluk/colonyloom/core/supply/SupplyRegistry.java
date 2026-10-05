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
    private final Map<UUID, Set<UUID>> orderShares = new HashMap<>(), demandShares = new HashMap<>(), productionShares = new HashMap<>(), ownerDemands = new HashMap<>(), demandOrders = new HashMap<>();
    private final EnumMap<Lane, List<UUID>> deliveryIds = new EnumMap<>(Lane.class);
    private final Map<UUID, Integer> deliveryOffsets = new HashMap<>(), productionOffsets = new HashMap<>();
    private final Map<UUID, UUID> deliveryWorks = new HashMap<>(), productionWorks = new HashMap<>();
    private final List<UUID> productionIds = new ArrayList<>();
    private final EnumMap<Lane, NavigableSet<Demand>> planning = new EnumMap<>(Lane.class);
    private final Map<UUID, Demand> foodDemands = new HashMap<>();
    private long planningSequence;
    private long productionIndexRevision;
    private int terminalProductionCount;
    private static final class CitizenCargo {
        private final long bindingEpoch;
        private int count;
        private CitizenCargo(long bindingEpoch, int count) { this.bindingEpoch = bindingEpoch; this.count = count; }
    }
    private final Map<UUID, List<CitizenCargo>> allocatedCitizenCargo = new HashMap<>();
    private final LinkedHashSet<UUID> unroutedShares = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> dirtyObligations = new LinkedHashSet<>();
    public SupplyRegistry(ColonyRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
        for (var lane : Lane.values()) {
            deliveryIds.put(lane, new ArrayList<>());
            planning.put(lane, new TreeSet<>(Comparator.comparingLong((Demand d) -> d.planningSequence)
                    .thenComparing(Comparator.comparingInt((Demand d) -> d.snapshot().priority()).reversed())
                    .thenComparingLong(d -> d.snapshot().createdTick()).thenComparing(Demand::id)));
        }
        registry.storage().setLossListener(id -> { if (obligationShares.containsKey(id)) dirtyObligations.add(id); });
    }
    private static void adjustCargoIndex(Map<UUID, List<CitizenCargo>> index, StockRegion slot, int delta) {
        if (slot == null || slot.storage().bindingEpoch() == 0) return;
        UUID citizenId = slot.storage().identity(); long epoch = slot.storage().bindingEpoch();
        List<CitizenCargo> epochs = index.get(citizenId); CitizenCargo cargo = null;
        if (epochs != null) for (var value : epochs) if (value.bindingEpoch == epoch) { cargo = value; break; }
        if (cargo == null) {
            if (delta < 0) throw new IllegalStateException("Allocated citizen cargo index underflow");
            if (epochs == null) { epochs = new ArrayList<>(1); index.put(citizenId, epochs); }
            epochs.add(new CitizenCargo(epoch, delta)); return;
        }
        cargo.count += delta;
        if (cargo.count < 0) throw new IllegalStateException("Allocated citizen cargo index underflow");
        if (cargo.count == 0) { epochs.remove(cargo); if (epochs.isEmpty()) index.remove(citizenId); }
    }
    private void putShare(UUID id, CoverageShare share) {
        CoverageShare previous = shares.put(id, share); if (previous != null) unindex(previous);
        if (share.obligationId() != null) obligationShares.put(share.obligationId(), id);
        demandShares.computeIfAbsent(share.demandId(), ignored -> new LinkedHashSet<>()).add(id);
        if (share.sourceOrderId() != null) orderShares.computeIfAbsent(share.sourceOrderId(), ignored -> new LinkedHashSet<>()).add(id);
        if (share.stage() == CoverageShare.Stage.RESERVED_STOCK && share.sourceOrderId() == null) unroutedShares.add(id);
        if (share.productionOrderId() != null) productionShares.computeIfAbsent(share.productionOrderId(), ignored -> new LinkedHashSet<>()).add(id);
        if (share.stage() == CoverageShare.Stage.ALLOCATED) adjustCargoIndex(allocatedCitizenCargo, share.slot(), 1);
        UUID owner = demand(share.demandId()).snapshot().ownerId();
        if (productions.containsKey(owner) && share.stage() == CoverageShare.Stage.RESERVED_STOCK) kitShares.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(id);
    }
    private void unindex(CoverageShare share) {
        if (share.stage() == CoverageShare.Stage.ALLOCATED) adjustCargoIndex(allocatedCitizenCargo, share.slot(), -1);
        if (share.obligationId() != null) obligationShares.remove(share.obligationId());
        unroutedShares.remove(share.id());
        removeIndex(demandShares, share.demandId(), share.id());
        if (share.sourceOrderId() != null) removeIndex(orderShares, share.sourceOrderId(), share.id());
        if (share.productionOrderId() != null) removeIndex(productionShares, share.productionOrderId(), share.id());
        UUID owner = demand(share.demandId()).snapshot().ownerId(); Set<UUID> local = kitShares.get(owner);
        if (local != null) { local.remove(share.id()); if (local.isEmpty()) kitShares.remove(owner); }
    }
    private static void removeIndex(Map<UUID, Set<UUID>> index, UUID key, UUID id) {
        Set<UUID> values = index.get(key); if (values != null) { values.remove(id); if (values.isEmpty()) index.remove(key); }
    }
    private void clearShares() { shares.clear(); obligationShares.clear(); kitShares.clear(); orderShares.clear(); demandShares.clear(); productionShares.clear(); unroutedShares.clear(); allocatedCitizenCargo.clear(); }
    private void putShares(Map<UUID, CoverageShare> values) { values.forEach(this::putShare); }
    private void indexDemand(Demand demand) {
        var state = demand.snapshot();
        if (demand.deficit() > 0 && state.status() != Demand.Status.CANCELLED && state.status() != Demand.Status.COMPLETED)
            planning.get(state.lane()).add(demand);
    }
    private void unindexDemand(Demand demand) { planning.get(demand.snapshot().lane()).remove(demand); }
    private void addDemandOrder(UUID demandId, UUID orderId) { demandOrders.computeIfAbsent(demandId, ignored -> new LinkedHashSet<>()).add(orderId); }
    private void removeDemandOrder(UUID demandId, UUID orderId) { removeIndex(demandOrders, demandId, orderId); }
    private void putDemand(Demand demand) {
        demands.put(demand.id(), demand); demand.index(this::unindexDemand, this::indexDemand); indexDemand(demand);
        ownerDemands.computeIfAbsent(demand.snapshot().ownerId(), ignored -> new LinkedHashSet<>()).add(demand.id());
        if (foodConsumer(demand.id())) foodDemands.put(demand.snapshot().ownerId(), demand);
    }
    private void removeDemandIndex(Demand demand) {
        unindexDemand(demand); foodDemands.remove(demand.snapshot().ownerId(), demand);
        removeIndex(ownerDemands, demand.snapshot().ownerId(), demand.id());
    }
    public Demand findDemand(UUID id) { registry.requireOwner(); return demands.get(id); }
    public ProductionOrder findProduction(UUID id) { registry.requireOwner(); return productions.get(id); }
    public Demand foodDemandForWork(UUID workId) { registry.requireOwner(); return foodDemands.get(workId); }
    public Demand planningCandidate(Lane lane) { registry.requireOwner(); var frontier = planning.get(lane); return frontier.isEmpty() ? null : frontier.first(); }
    public void planningServiced(UUID id) {
        registry.requireOwner(); var demand = demands.get(id); if (demand == null) return;
        unindexDemand(demand); demand.planningSequence = ++planningSequence; indexDemand(demand);
    }
    public Demand demand(UUID id) { registry.requireOwner(); Demand d = demands.get(id); if (d == null) throw new IllegalArgumentException("Unknown demand"); return d; }
    public List<Demand> demands() { registry.requireOwner(); return List.copyOf(demands.values()); }
    public List<CoverageShare> shares() { registry.requireOwner(); return List.copyOf(shares.values()); }
    public List<ProductionOrder> productionOrders() { registry.requireOwner(); return List.copyOf(productions.values()); }
    public List<DeliveryOrder> deliveries() { registry.requireOwner(); return List.copyOf(deliveries.values()); }
    public DeliveryOrder delivery(UUID id) { registry.requireOwner(); DeliveryOrder order = deliveries.get(id); if (order == null) throw new IllegalArgumentException("Unknown delivery"); return order; }
    public List<CoverageShare> orderShares(UUID orderId) { registry.requireOwner(); return orderShares.getOrDefault(orderId, Set.of()).stream().map(shares::get).toList(); }
    public List<CoverageShare> demandShares(UUID demandId) { registry.requireOwner(); return demandShares.getOrDefault(demandId, Set.of()).stream().map(shares::get).toList(); }
    public DeliveryOrder deliveryForWork(UUID workId) { registry.requireOwner(); return deliveries.get(deliveryWorks.get(workId)); }
    public boolean hasCargo(UUID orderId) { return orderShares(orderId).stream().anyMatch(s -> s.stage() == CoverageShare.Stage.IN_TRANSIT); }
    public boolean hasDeliveryWork(UUID workId) { return deliveryForWork(workId) != null; }
    public int deliveryCount(Lane lane) { registry.requireOwner(); return deliveryIds.get(lane).size(); }
    public DeliveryOrder deliveryAt(Lane lane, int offset) { registry.requireOwner(); return deliveries.get(deliveryIds.get(lane).get(offset)); }
    public int productionCount() { registry.requireOwner(); return productionIds.size(); }
    public ProductionOrder productionAt(int offset) { registry.requireOwner(); return productions.get(productionIds.get(offset)); }
    public long productionIndexRevision() { registry.requireOwner(); return productionIndexRevision; }
    public int terminalProductionCount() { registry.requireOwner(); return terminalProductionCount; }
    public int productionRetirementStart(int offset) {
        registry.requireOwner();
        return productionIds.isEmpty() ? 0 : Math.floorMod(offset, productionIds.size());
    }
    private void replaceProduction(ProductionOrder order) {
        ProductionOrder previous = productions.put(order.id(), order);
        if (previous == null) throw new IllegalStateException("Production index is missing");
        if (previous.terminal() && !order.terminal()) terminalProductionCount--;
        else if (!previous.terminal() && order.terminal()) terminalProductionCount = Math.incrementExact(terminalProductionCount);
        if (!Objects.equals(previous.workId(), order.workId())) {
            if (previous.workId() != null) productionWorks.remove(previous.workId(), order.id());
            if (order.workId() != null) productionWorks.put(order.workId(), order.id());
        }
    }
    private void addProduction(ProductionOrder order) {
        productions.put(order.id(), order);
        productionOffsets.put(order.id(), productionIds.size()); productionIds.add(order.id());
        addDemandOrder(order.ownerDemandId(), order.id());
        if (order.workId() != null) productionWorks.put(order.workId(), order.id());
        if (order.terminal()) terminalProductionCount = Math.incrementExact(terminalProductionCount);
        productionIndexRevision = Math.incrementExact(productionIndexRevision);
    }
    private void removeProduction(UUID id) {
        ProductionOrder order = productions.remove(id); int offset = productionOffsets.remove(id);
        UUID last = productionIds.removeLast();
        if (offset < productionIds.size()) { productionIds.set(offset, last); productionOffsets.put(last, offset); }
        removeDemandOrder(order.ownerDemandId(), id);
        if (order.workId() != null) productionWorks.remove(order.workId(), id);
        if (order.terminal()) terminalProductionCount--;
        productionIndexRevision = Math.incrementExact(productionIndexRevision);
    }
    public boolean hasAllocatedCitizenCargo(UUID citizenId, long bindingEpoch) {
        registry.requireOwner(); var epochs = allocatedCitizenCargo.get(citizenId);
        if (epochs != null) for (var value : epochs) if (value.bindingEpoch == bindingEpoch) return value.count > 0;
        return false;
    }
    public CoverageShare unroutedReservation() { registry.requireOwner(); return unroutedShares.isEmpty() ? null : shares.get(unroutedShares.iterator().next()); }
    private void addDelivery(DeliveryOrder order) {
        deliveries.put(order.id(), order); var ids = deliveryIds.get(order.lane());
        deliveryOffsets.put(order.id(), ids.size()); ids.add(order.id());
        addDemandOrder(order.ownerDemandId(), order.id());
        if (order.workId() != null) deliveryWorks.put(order.workId(), order.id());
    }
    private void removeDeliveryIndex(DeliveryOrder order) {
        var ids = deliveryIds.get(order.lane()); int offset = deliveryOffsets.remove(order.id()); UUID last = ids.removeLast();
        if (offset < ids.size()) { ids.set(offset, last); deliveryOffsets.put(last, offset); }
    }
    private void removeDelivery(UUID id) {
        var order = deliveries.remove(id); removeDeliveryIndex(order);
        removeDemandOrder(order.ownerDemandId(), id);
        if (order.workId() != null) deliveryWorks.remove(order.workId(), id);
    }
    private void updateDelivery(DeliveryOrder order) {
        var old = deliveries.get(order.id());
        if (old.lane() != order.lane()) { removeDeliveryIndex(old); addDelivery(order); }
        else deliveries.put(order.id(), order);
    }
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
                required == 0 ? Demand.Status.COMPLETED : Demand.Status.ACTIVE, List.of()));
        AdmissionLedger.Lease lease = admit(colony, lane, Resource.DEMANDS);
        try { registry.beforeMutation(); } catch (RuntimeException failure) { lease.close(); throw failure; }
        putDemand(value); leases.put(id, lease); return value;
    }
    public boolean foodConsumer(UUID demandId) {
        UUID owner = demand(demandId).snapshot().ownerId();
        try { return io.github.kpuctajluk.colonyloom.core.work.WorkOrder.FOOD.equals(registry.workBoard().work(owner).typeId()); }
        catch (IllegalArgumentException absent) { return false; }
    }
    public Demand requestDelivery(UUID id, UUID colony, UUID owner, ItemDescriptor item, long required,
                                  WorldPosition destination, List<StorageId> sources, long tick) {
        sources = List.copyOf(sources);
        if (sources.size() > 2 || sources.stream().distinct().count() != sources.size()
                || sources.stream().anyMatch(source -> !source.dimension().equals(destination.dimension()))) throw new IllegalArgumentException("Invalid delivery source constraint");
        if (sources.isEmpty()) throw new IllegalArgumentException("Delivery requires selected canonical source");
        Demand existing = demands.get(id);
        if (existing != null && !existing.snapshot().sourceStorages().equals(sources)) throw new IllegalArgumentException("Delivery source changed");
        Demand result = request(id, colony, owner, new ItemMatcher(item.itemId(), item), required, Demand.GoalKind.DELIVERY,
                destination, Lane.NORMAL, 0, tick);
        if (existing == null) {
            var s = result.snapshot();
            result.replace(new Demand.Snapshot(s.id(), s.colonyId(), s.ownerId(), s.matcher(), s.goalKind(), s.destination(),
                    s.required(), s.fulfilled(), s.allocated(), s.covered(), s.deliveredTotal(), s.revision(), s.lane(), s.priority(), s.createdTick(), s.status(), sources));
        }
        return result;
    }
    /** Self-owned delivery goals retain begun output until a physical safe-buffer transfer fulfils them. */
    public boolean productionSurplus(UUID demandId) {
        var d = demand(demandId).snapshot();
        return d.goalKind() == Demand.GoalKind.DELIVERY && d.ownerId().equals(d.id());
    }
    public boolean surplusBuffer(UUID colonyId, StorageRegistry.Registration registration, String dimension) {
        return registration.colonyId().equals(colonyId) && (registration.role().equals("warehouse") || registration.role().equals("return"))
                && registration.address().dimension().equals(dimension) && registry.colony(colonyId).territory().contains(registration.address())
                && registration.storages().stream().allMatch(id -> id.bindingEpoch() == 0 && !registry.storage().isRetired(id));
    }
    public void routeProductionSurplus(UUID demandId, WorldPosition destination) {
        var d = demand(demandId).snapshot();
        if (!productionSurplus(demandId) || registry.storage().registrations(d.colonyId()).stream()
                .noneMatch(r -> r.address().equals(destination) && surplusBuffer(d.colonyId(), r, d.destination().dimension())))
            throw new IllegalArgumentException("Surplus requires a registered local safe buffer");
        if (d.destination().equals(destination)) return;
        registry.beforeMutation();
        demand(demandId).replace(new Demand.Snapshot(d.id(), d.colonyId(), d.ownerId(), d.matcher(), d.goalKind(), destination,
                d.required(), d.fulfilled(), d.allocated(), d.covered(), d.deliveredTotal(), Math.incrementExact(d.revision()),
                d.lane(), d.priority(), d.createdTick(), d.status(), d.sourceStorages()));
        for (var order : List.copyOf(deliveries.values())) if (order.ownerDemandId().equals(demandId))
            deliveries.put(order.id(), new DeliveryOrder(order.id(), order.colonyId(), demandId, order.source(), destination,
                    order.item(), order.quantity(), order.transferred(), Math.incrementExact(order.revision()), order.citizenId(), order.workId(),
                    order.state(), order.lane(), order.priority()));
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
                delivered, Math.addExact(s.revision(), 1), s.lane(), s.priority(), s.createdTick(), status, s.sourceStorages());
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
        CoverageShare share = new CoverageShare(shareId, s.colonyId(), demandId, null, null, obligationId, slot, item, quantity, 0, CoverageShare.Stage.RESERVED_STOCK);
        var next = state(s, s.required(), s.fulfilled(), s.allocated(), Math.addExact(s.covered(), quantity), s.deliveredTotal(), Demand.Status.ACTIVE);
        AdmissionLedger.Lease lease = admit(s.colonyId(), s.lane(), Resource.COVERAGE_SHARES);
        try { registry.storage().reserveAll(List.of(new ReservationLedger.Entry(obligationId, s.colonyId(), s.ownerId(), slot, item, quantity, 0, s.lane())), tick); }
        catch (RuntimeException failure) { lease.close(); throw failure; }
        putShare(shareId, share); leases.put(shareId, lease); demand(demandId).replace(next); return share;
    }
    public DeliveryOrder coverStock(UUID demandId, StockRegion slot, ItemDescriptor item, long quantity, long tick) {
        var s = demand(demandId).snapshot(); active(s, item, quantity);
        if (!s.acceptsSource(slot.storage())) throw new IllegalArgumentException("Stock outside selected source");
        UUID orderId = fresh(), shareId = fresh(), obligationId = fresh();
        DeliveryOrder order = new DeliveryOrder(orderId, s.colonyId(), demandId, slot, s.destination(), item, quantity, 0, 0, null, null, DeliveryOrder.State.PLANNED, s.lane(), s.priority());
        if (productions.size() + deliveries.size() >= MAX_ORDERS) throw new IllegalArgumentException("Order envelope exceeded");
        AdmissionLedger.Lease lease = admit(s.colonyId(), s.lane(), Resource.DELIVERIES_AND_PRODUCTION_ORDERS);
        try { reserveStock(shareId, demandId, obligationId, slot, item, quantity, tick); }
        catch (RuntimeException failure) { lease.close(); throw failure; }
        CoverageShare share = shares.get(shareId);
        putShare(shareId, new CoverageShare(share.id(), share.colonyId(), share.demandId(), orderId, share.productionOrderId(), share.obligationId(), share.slot(), share.item(), share.quantity(), share.revision(), share.stage()));
        addDelivery(order); leases.put(orderId, lease); return order;
    }
    public void coverProductionKit(UUID productionId, List<ReservationLedger.Entry> candidates, long tick) {
        registry.requireOwner(); ProductionOrder order = productions.get(productionId);
        if (order == null || candidates.isEmpty() || candidates.size() > 16) throw new IllegalArgumentException("Invalid production kit");
        List<Demand.Snapshot> children = new ArrayList<>();
        for (int i = 0; i < order.recipe().ingredients().size(); i++) children.add(ingredientDemand(order, i, Math.multiplyExact(order.batches() + order.completedBatches(), order.recipe().ingredients().get(i).count()), tick).snapshot());
        for (var child : children) if (child.covered() != 0 || child.status() == Demand.Status.CANCELLED) throw new IllegalStateException("Production dependency not physically ready");
        children.sort(Comparator.comparing((Demand.Snapshot d) -> d.matcher().exact() == null));
        Map<UUID, Long> remaining = new LinkedHashMap<>(); for (var child : children) {
            long count = order.recipe().ingredients().stream().filter(i -> i.matcher().equals(child.matcher())).findFirst().orElseThrow().count();
            remaining.put(child.id(), Math.max(0, count - child.allocated()));
        }
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
                boolean local = localDestination(child, candidate.slot());
                if (!local) orders.add(new DeliveryOrder(deliveryId, order.colonyId(), child.id(), candidate.slot(), child.destination(), candidate.item(), count, 0, 0, null, null, DeliveryOrder.State.PLANNED, child.lane(), child.priority()));
                proposed.add(new CoverageShare(shareId, order.colonyId(), child.id(), local ? null : deliveryId, null, obligationId, candidate.slot(), candidate.item(), count, 0, CoverageShare.Stage.RESERVED_STOCK));
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
        leases.putAll(admitted); orders.forEach(this::addDelivery); proposed.forEach(s -> putShare(s.id(), s)); states.forEach((id, s) -> demand(id).replace(s));
        for (var share : proposed) if (share.sourceOrderId() == null) allocateLocalReservation(share.id());
    }
    public ProductionOrder promiseProduction(UUID demandId, RecipeDefinition recipe, long batches) {
        var s = demand(demandId).snapshot(); long output = Math.multiplyExact(batches, recipe.outputCount()); Demand.quantity(output);
        long quantity = Math.min(output, s.deficit()); active(s, recipe.output(), quantity);
        var workshop = selectWorkshop(s.colonyId(), recipe);
        var registration = registry.storage().registrations().stream().filter(r -> r.id().equals(workshop.registrationId())).findFirst().orElseThrow();
        if (registration.storages().size() != 1) throw new IllegalStateException("Production requires one canonical workshop barrel");
        for (var existing : List.copyOf(productions.values())) if (!existing.terminal() && existing.recipe().equals(recipe) && existing.colonyId().equals(s.colonyId()) && existing.workshopId()!=null && existing.workshopId().equals(workshop.id()) && !dependsOn(demandId, existing.id())) {
            long promised = 0; for (var share : orderShares(existing.id())) if (share.stage()==CoverageShare.Stage.PROMISED_OUTPUT) promised = Math.addExact(promised, share.quantity());
            long remainingBatches = recipe.batchesFor(Math.addExact(promised, quantity));
            if (shares.size() >= MAX_SHARES) throw new IllegalArgumentException("Coverage envelope exceeded");
            var nextOrder = existing.update(Math.max(existing.batches(), remainingBatches), existing.remainingActiveTicks(), existing.completedBatches(), existing.batchStarted(), existing.workId(), existing.citizenId(), existing.state(), existing.lane(), existing.priority());
            var promise = new CoverageShare(fresh(),s.colonyId(),demandId,existing.id(),existing.id(),null,null,recipe.output(),quantity,0,CoverageShare.Stage.PROMISED_OUTPUT);
            var nextDemand = state(s,s.required(),s.fulfilled(),s.allocated(),Math.addExact(s.covered(),quantity),s.deliveredTotal(),s.status());
            var states = new LinkedHashMap<UUID,Demand.Snapshot>();
            for (int i=0;i<recipe.ingredients().size();i++) { var child=ingredientDemand(existing,i,Math.multiplyExact(existing.batches()+existing.completedBatches(),recipe.ingredients().get(i).count()),0).snapshot(); states.put(child.id(),state(child,Math.multiplyExact(nextOrder.batches()+nextOrder.completedBatches(),recipe.ingredients().get(i).count()),child.fulfilled(),child.allocated(),child.covered(),child.deliveredTotal(),Demand.Status.ACTIVE)); }
            var lease=admit(s.colonyId(),s.lane(),Resource.COVERAGE_SHARES); try { registry.beforeMutation(); } catch(RuntimeException failure) { lease.close(); throw failure; }
            replaceProduction(nextOrder); putShare(promise.id(),promise); leases.put(promise.id(),lease); demand(demandId).replace(nextDemand); states.forEach((id,state)->demand(id).replace(state)); recomputePriorities(); return production(existing.id());
        }
        ProductionOrder order = new ProductionOrder(fresh(), s.colonyId(), demandId, recipe, batches, recipe.activeTicks(), 0, null, null, ProductionOrder.State.PLANNED, s.lane(), s.priority(), workshop.id(), workshop.position(), registration.storages().getFirst(), 0, false);
        CoverageShare share = new CoverageShare(fresh(), s.colonyId(), demandId, order.id(), order.id(), null, null, recipe.output(), quantity, 0, CoverageShare.Stage.PROMISED_OUTPUT);
        planProduction(order, List.of(share)); return order;
    }
    public void planProduction(ProductionOrder order, List<CoverageShare> promised) {
        registry.colony(order.colonyId()); unique(order.id());
        if (productions.size() + deliveries.size() >= MAX_ORDERS || shares.size() + promised.size() > MAX_SHARES || promised.isEmpty()) throw new IllegalArgumentException("Production envelope exceeded");
        var owner = demand(order.ownerDemandId()).snapshot();
        if (!owner.colonyId().equals(order.colonyId()) || order.workId() != null || order.citizenId() != null || !order.pinned() || !workshopAvailable(order) || order.batchStarted() || order.completedBatches()!=0) throw new IllegalArgumentException("Invalid planned production owner/pin");
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
                    workshopDestination(order), quantity, 0, 0, 0, 0, 0, order.lane(), order.priority(), owner.createdTick(), Demand.Status.ACTIVE, List.of())));
        }
        if (demands.size() + ingredients.size() > MAX_DEMANDS) throw new IllegalArgumentException("Ingredient demand envelope exceeded");
        Map<UUID, AdmissionLedger.Lease> admitted = new LinkedHashMap<>();
        try {
            admitted.put(order.id(), admit(order.colonyId(), order.lane(), Resource.DELIVERIES_AND_PRODUCTION_ORDERS));
            for (CoverageShare share : promised) admitted.put(share.id(), admit(share.colonyId(), demand(share.demandId()).snapshot().lane(), Resource.COVERAGE_SHARES));
            for (Demand ingredient : ingredients) admitted.put(ingredient.id(), admit(order.colonyId(), order.lane(), Resource.DEMANDS));
            registry.beforeMutation();
        } catch (RuntimeException failure) { admitted.values().forEach(AdmissionLedger.Lease::close); throw failure; }
        addProduction(order); promised.forEach(s -> putShare(s.id(), s)); leases.putAll(admitted); changed.forEach((id, value) -> demand(id).replace(value));
        ingredients.forEach(this::putDemand);
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
        return coverExistingProduction(demandId,quantity,null);
    }
    public boolean coverExistingProduction(UUID demandId, long quantity, RecipeDefinition acceptedRecipe) {
        var s = demand(demandId).snapshot(); if (quantity <= 0 || quantity > s.deficit()) return false;
        for (ProductionOrder order : List.copyOf(productions.values())) {
            if (order.terminal() || s.goalKind() == Demand.GoalKind.DELIVERY) continue;
            if (!order.colonyId().equals(s.colonyId()) || !s.matcher().matches(order.recipe().output()) || dependsOn(s.id(), order.id())) continue;
            if (acceptedRecipe!=null && !acceptedRecipe.equals(order.recipe())) continue;
            long promised = 0; for (CoverageShare share : orderShares(order.id())) if (share.stage() == CoverageShare.Stage.PROMISED_OUTPUT) promised = Math.addExact(promised, share.quantity());
            if (Math.multiplyExact(order.batches(), order.recipe().outputCount()) - promised < quantity) continue;
            if (shares.size() >= MAX_SHARES) throw new IllegalArgumentException("Coverage envelope exceeded");
            CoverageShare share = new CoverageShare(fresh(), s.colonyId(), demandId, order.id(), order.id(), null, null, order.recipe().output(), quantity, 0, CoverageShare.Stage.PROMISED_OUTPUT);
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
    private StorageRegistry.Workshop selectWorkshop(UUID colony, RecipeDefinition recipe) {
        if (!recipe.equipmentId().equals("minecraft:crafting_table")) throw new IllegalStateException("Unsupported production equipment");
        for (var citizen : registry.citizensView()) if (citizen.colonyId().equals(colony)
                && citizen.lifecycle() == io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Lifecycle.ALIVE
                && recipe.professionId().equals(citizen.professionId()) && citizen.workplaceId() != null) {
            for (var workshop : registry.storage().workshops()) if (workshop.id().equals(citizen.workplaceId()) && workshop.colonyId().equals(colony)) return workshop;
        }
        throw new IllegalStateException("No assigned producer workshop");
    }
    public ProductionOrder production(UUID id) { registry.requireOwner(); var order = productions.get(id); if (order == null) throw new IllegalArgumentException("Unknown production"); return order; }
    public ProductionOrder productionForWork(UUID workId) { registry.requireOwner(); return productions.get(productionWorks.get(workId)); }
    public WorldPosition workshopDestination(ProductionOrder order) {
        for (var registration : registry.storage().registrations()) if (registration.colonyId().equals(order.colonyId()) && registration.role().equals("workshop") && registration.storages().contains(order.workshopStorage()))
            return registration.positions().get(registration.storages().indexOf(order.workshopStorage()));
        throw new IllegalStateException("Pinned workshop barrel unavailable");
    }
    public boolean workshopAvailable(ProductionOrder order) {
        if (!order.pinned()) return false;
        for (var workshop : registry.storage().workshops()) if (workshop.id().equals(order.workshopId()) && workshop.colonyId().equals(order.colonyId()) && workshop.position().equals(order.equipmentPosition())) {
            for (var registration : registry.storage().registrations()) if (registration.id().equals(workshop.registrationId()) && registration.role().equals("workshop") && registration.storages().equals(List.of(order.workshopStorage())) && !registry.storage().isRetired(order.workshopStorage())) return true;
        }
        return false;
    }
    public boolean pinProduction(UUID productionId) {
        var p = production(productionId); if (p.pinned()) return workshopAvailable(p); if (p.terminal()) return false;
        StorageRegistry.Workshop workshop;
        try { workshop = selectWorkshop(p.colonyId(), p.recipe()); } catch (IllegalStateException unavailable) { return false; }
        var registration = registry.storage().registrations().stream().filter(r -> r.id().equals(workshop.registrationId())).findFirst().orElseThrow();
        if (registration.storages().size() != 1) return false;
        var next = new ProductionOrder(p.id(), p.colonyId(), p.ownerDemandId(), p.recipe(), p.batches(), p.remainingActiveTicks(), Math.incrementExact(p.revision()), null, null, ProductionOrder.State.PLANNED, p.lane(), p.priority(), workshop.id(), workshop.position(), registration.storages().getFirst(), 0, false);
        for (var d : demands.values()) if (d.snapshot().ownerId().equals(p.id()) && d.snapshot().allocated() != 0) return false;
        for (var d : demands.values()) if (d.snapshot().ownerId().equals(p.id())) for (var share : demandShares(d.id())) if (share.obligationId()!=null) {
            var observed=registry.storage().index().observation(share.slot());
            if (!observed.ready() || !share.item().equals(observed.item()) || observed.count()<registry.storage().obligated(share.slot())) return false;
        }
        registry.beforeMutation(); replaceProduction(next);
        for (var d : demands.values()) if (d.snapshot().ownerId().equals(p.id())) {
            var s = d.snapshot(); d.replace(new Demand.Snapshot(s.id(), s.colonyId(), s.ownerId(), s.matcher(), s.goalKind(), registration.positions().getFirst(), s.required(), s.fulfilled(), s.allocated(), s.covered(), s.deliveredTotal(), Math.incrementExact(s.revision()), s.lane(), s.priority(), s.createdTick(), s.status(), s.sourceStorages()));
            for (var delivery : List.copyOf(deliveries.values())) if (delivery.ownerDemandId().equals(s.id()) && !delivery.terminal()) deliveries.put(delivery.id(), new DeliveryOrder(delivery.id(), delivery.colonyId(), delivery.ownerDemandId(), delivery.source(), registration.positions().getFirst(), delivery.item(), delivery.quantity(), delivery.transferred(), Math.incrementExact(delivery.revision()), delivery.citizenId(), delivery.workId(), delivery.state(), delivery.lane(), delivery.priority()));
        }
        return true;
    }
    private boolean localDestination(Demand.Snapshot d, StockRegion slot) {
        if (d.goalKind() != Demand.GoalKind.CONSUMPTION) return false;
        if (foodConsumer(d.id())) {
            var subject = registry.citizen(registry.workBoard().work(d.ownerId()).subjectId());
            return slot.storage().identity().equals(subject.citizenId()) && slot.storage().bindingEpoch() == subject.bindingEpoch();
        }
        for (var registration : registry.storage().registrations()) if (registration.colonyId().equals(d.colonyId()) && registration.storages().contains(slot.storage())) {
            if (registration.positions().get(registration.storages().indexOf(slot.storage())).equals(d.destination())) return true;
        }
        return false;
    }
    public boolean allocateLocalReservation(UUID shareId) {
        var share = requiredShare(shareId); var d = demand(share.demandId()).snapshot();
        if (share.stage() != CoverageShare.Stage.RESERVED_STOCK || !localDestination(d, share.slot()) || d.status() == Demand.Status.CANCELLED) return false;
        var result = new CoverageShare(share.id(), share.colonyId(), share.demandId(), share.sourceOrderId(), share.productionOrderId(), share.obligationId(), share.slot(), share.item(), share.quantity(), Math.incrementExact(share.revision()), CoverageShare.Stage.ALLOCATED);
        var next = state(d, d.required(), d.fulfilled(), d.allocated() + share.quantity(), d.covered() - share.quantity(), d.deliveredTotal(), d.status());
        registry.storage().allocateReserved(share.obligationId()); putShare(share.id(), result); demand(d.id()).replace(next);
        if (share.sourceOrderId() != null && deliveries.containsKey(share.sourceOrderId())) { var delivery = delivery(share.sourceOrderId()); deliveries.put(delivery.id(), delivery(delivery, delivery.transferred(), DeliveryOrder.State.COMPLETED)); }
        return true;
    }
    public record InputPortion(UUID shareId, UUID obligationId, StockRegion slot, ItemDescriptor item, int count) {}
    public record OutputPortion(StockRegion slot, int count) {
        public OutputPortion { Objects.requireNonNull(slot); if (count <= 0) throw new IllegalArgumentException("Invalid physical output portion"); }
    }
    /** Empty means the full next batch is not locally allocated; no worker may bind. */
    public List<InputPortion> completeProductionKit(UUID productionId) {
        var order = production(productionId); if (order.terminal() || order.batches() == 0 || !workshopAvailable(order)) return List.of();
        var result = new ArrayList<InputPortion>(); Set<StockRegion> slots = new HashSet<>();
        for (int i = 0; i < order.recipe().ingredients().size(); i++) {
            var ingredient = order.recipe().ingredients().get(i); long needed = ingredient.count();
            UUID child = UUID.nameUUIDFromBytes((order.id() + ":ingredient:" + i).getBytes(StandardCharsets.UTF_8));
            for (var share : demandShares(child)) if (share.stage() == CoverageShare.Stage.ALLOCATED && share.slot().storage().equals(order.workshopStorage())) {
                var allocation = registry.storage().allocations().get(share.obligationId()); var observed = registry.storage().index().observation(share.slot());
                if (allocation == null || allocation.count() != share.quantity() || !allocation.item().equals(share.item()) || !allocation.slot().equals(share.slot())
                        || !observed.ready() || !share.item().equals(observed.item()) || observed.count() < registry.storage().obligated(share.slot())) return List.of();
                int taken = Math.toIntExact(Math.min(needed, share.quantity()));
                if (taken > 0) { result.add(new InputPortion(share.id(), share.obligationId(), share.slot(), share.item(), taken)); slots.add(share.slot()); needed -= taken; }
                if (needed == 0) break;
            }
            if (needed != 0) return List.of();
        }
        return result.size() <= 16 && slots.size() < 16 ? List.copyOf(result) : List.of();
    }
    public void assignProductionWork(UUID productionId, UUID workId) {
        var p = production(productionId); var work = registry.workBoard().work(workId);
        if (p.terminal() || p.workId() != null && !p.workId().equals(workId) || !work.colonyId().equals(p.colonyId())
                || !work.typeId().equals(io.github.kpuctajluk.colonyloom.core.work.WorkOrder.PRODUCTION) || !work.target().equals(p.equipmentPosition())
                || !Objects.equals(work.professionId(), p.recipe().professionId()) || completeProductionKit(p.id()).isEmpty()) throw new IllegalStateException("Production work lacks complete kit");
        if (workId.equals(p.workId())) return;
        registry.beforeMutation(); replaceProduction(p.update(p.batches(), p.remainingActiveTicks(), p.completedBatches(), p.batchStarted(), workId, null, p.state(), p.lane(), p.priority()));
    }
    public void startProduction(UUID productionId, UUID citizenId, UUID workId) {
        var p = production(productionId); var citizen = registry.citizen(citizenId); var work = registry.workBoard().work(workId);
        if (!workId.equals(p.workId()) || !citizenId.equals(work.assignee()) || !workId.equals(citizen.assignedWorkId())
                || !p.workshopId().equals(citizen.workplaceId()) || !p.recipe().professionId().equals(citizen.professionId())
                || !registry.colony(p.colonyId()).available() || citizen.readiness() != io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Readiness.READY
                || registry.bindings().activeEntity(citizenId).isEmpty() || completeProductionKit(p.id()).isEmpty()
                || p.batchStarted() && p.citizenId() != null && !citizenId.equals(p.citizenId())) throw new IllegalStateException("Production binding/kit not ready");
        if (p.batchStarted() && citizenId.equals(p.citizenId())) return;
        registry.beforeMutation(); replaceProduction(p.update(p.batches(), p.remainingActiveTicks(), p.completedBatches(), true, workId, citizenId, ProductionOrder.State.PROCESSING, p.lane(), p.priority()));
    }
    public void advanceProduction(UUID productionId, long activeDelta) {
        var p = production(productionId);
        if (activeDelta < 0 || !p.batchStarted() || p.state() != ProductionOrder.State.PROCESSING) throw new IllegalStateException("Production is not processing");
        var citizen = registry.citizen(p.citizenId()); var work = registry.workBoard().work(p.workId());
        if (!registry.colony(p.colonyId()).available() || work.state()!=io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.RUNNING
                || !p.citizenId().equals(work.assignee()) || !p.workId().equals(citizen.assignedWorkId())
                || citizen.admission()!=io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Admission.ACTIVE
                || citizen.readiness()!=io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Readiness.READY
                || registry.bindings().activeEntity(citizen.citizenId()).isEmpty() || !workshopAvailable(p)) throw new IllegalStateException("Inactive production cannot accrue time");
        if (activeDelta == 0 || p.remainingActiveTicks() == 0) return;
        registry.beforeMutation(); replaceProduction(p.update(p.batches(), Math.max(0, p.remainingActiveTicks() - Math.min(activeDelta, p.remainingActiveTicks())), p.completedBatches(), true, p.workId(), p.citizenId(), p.state(), p.lane(), p.priority()));
    }
    public PreparedProduction prepareProduction(UUID productionId, List<OutputPortion> outputs, long tick) {
        var p = production(productionId); var inputs = completeProductionKit(productionId);
        if (!p.batchStarted() || p.remainingActiveTicks() != 0 || inputs.isEmpty() || p.state() != ProductionOrder.State.PROCESSING) throw new IllegalStateException("Physical batch not ready");
        outputs = List.copyOf(outputs); long outputCount = 0; Set<StockRegion> touched = new HashSet<>(); for (var input : inputs) touched.add(input.slot());
        Set<StockRegion> outputSlots = new HashSet<>();
        for (var out : outputs) { if (!out.slot().storage().equals(p.workshopStorage()) || !outputSlots.add(out.slot()) || touched.contains(out.slot())) throw new IllegalArgumentException("Output must use distinct pinned barrel slots"); outputCount = Math.addExact(outputCount, out.count()); }
        if (outputCount != p.recipe().outputCount() || touched.size() + outputSlots.size() > 16) throw new IllegalArgumentException("Physical output batch/envelope mismatch");
        var staged = new LinkedHashMap<>(shares); var beforeDemands = new LinkedHashMap<UUID, Demand.Snapshot>(); var beforeShares = new LinkedHashMap<UUID, CoverageShare>();
        var allocations = new LinkedHashMap<UUID, AllocationLedger.Entry>(); var reductions = new LinkedHashMap<UUID, Long>(); var admitted = new LinkedHashMap<UUID, AdmissionLedger.Lease>();
        var reservations = new ArrayList<ReservationLedger.Entry>();
        Demand surplus = null;
        try {
            for (var input : inputs) {
                var share = requiredShare(input.shareId()); var d = demand(share.demandId()).snapshot(); beforeDemands.put(d.id(), d); beforeShares.put(share.id(), share);
                var allocation = registry.storage().allocations().get(share.obligationId()); allocations.put(allocation.id(), allocation); reductions.put(allocation.id(), allocation.count() - input.count());
                UUID id = input.count() == share.quantity() ? share.id() : fresh();
                if (!id.equals(share.id())) admitted.put(id, admit(d.colonyId(), d.lane(), Resource.COVERAGE_SHARES));
                staged.remove(share.id()); if (input.count() < share.quantity()) staged.put(share.id(), resized(share, share.quantity() - input.count()));
                staged.put(id, new CoverageShare(id, share.colonyId(), share.demandId(), share.sourceOrderId(), share.productionOrderId(), null, null, share.item(), input.count(), Math.incrementExact(share.revision()), CoverageShare.Stage.FULFILLED));
            }
            int outIndex = 0, free = outputs.getFirst().count(); long remaining = outputCount;
            for (var promise : orderShares(p.id())) if (promise.stage() == CoverageShare.Stage.PROMISED_OUTPUT && remaining > 0) {
                var d = demand(promise.demandId()).snapshot(); beforeDemands.put(d.id(), d); beforeShares.put(promise.id(), promise);
                long converted = Math.min(remaining, promise.quantity()), left = converted; staged.remove(promise.id());
                if (converted < promise.quantity()) staged.put(promise.id(), resized(promise, promise.quantity() - converted));
                boolean reuse = converted == promise.quantity();
                while (left > 0) {
                    int count = Math.toIntExact(Math.min(left, free)); UUID id = reuse ? promise.id() : fresh(); reuse = false; UUID obligation = fresh();
                    if (!id.equals(promise.id())) admitted.put(id, admit(d.colonyId(), d.lane(), Resource.COVERAGE_SHARES));
                    var slot = outputs.get(outIndex).slot(); reservations.add(new ReservationLedger.Entry(obligation, p.colonyId(), d.ownerId(), slot, p.recipe().output(), count, 0, d.lane()));
                    staged.put(id, new CoverageShare(id, p.colonyId(), d.id(), null, p.id(), obligation, slot, p.recipe().output(), count, Math.incrementExact(promise.revision()), CoverageShare.Stage.RESERVED_STOCK));
                    left -= count; free -= count; if (free == 0 && ++outIndex < outputs.size()) free = outputs.get(outIndex).count();
                }
                remaining -= converted;
            }
            if (remaining > 0) {
                if (demands.size() >= MAX_DEMANDS) throw new IllegalArgumentException("Demand envelope exceeded");
                UUID id = fresh();
                var destination = registry.storage().registrations(p.colonyId()).stream()
                        .filter(r -> surplusBuffer(p.colonyId(), r, p.workshopStorage().dimension()))
                        .min(Comparator.comparing(StorageRegistry.Registration::id))
                        .map(StorageRegistry.Registration::address).orElseGet(() -> workshopDestination(p));
                surplus = new Demand(new Demand.Snapshot(id, p.colonyId(), id, new ItemMatcher(p.recipe().output().itemId(), p.recipe().output()),
                        Demand.GoalKind.DELIVERY, destination, remaining, 0, 0, remaining, 0, 0, p.lane(), p.priority(), tick,
                        Demand.Status.ACTIVE, List.of(p.workshopStorage())));
                admitted.put(id, admit(p.colonyId(), p.lane(), Resource.DEMANDS));
                while (remaining > 0) {
                    int count = Math.toIntExact(Math.min(remaining, free)); UUID shareId = fresh(), obligation = fresh();
                    admitted.put(shareId, admit(p.colonyId(), p.lane(), Resource.COVERAGE_SHARES));
                    var slot = outputs.get(outIndex).slot();
                    reservations.add(new ReservationLedger.Entry(obligation, p.colonyId(), id, slot, p.recipe().output(), count, 0, p.lane()));
                    staged.put(shareId, new CoverageShare(shareId, p.colonyId(), id, null, p.id(), obligation, slot, p.recipe().output(), count, 0, CoverageShare.Stage.RESERVED_STOCK));
                    remaining -= count; free -= count; if (free == 0 && ++outIndex < outputs.size()) free = outputs.get(outIndex).count();
                }
            }
            if (staged.size() > MAX_SHARES) throw new IllegalArgumentException("Coverage envelope exceeded");
            var states = new LinkedHashMap<UUID, Demand.Snapshot>(); beforeDemands.forEach((id, d) -> states.put(id, totals(d, staged.values(), d.required(), d.deliveredTotal(), d.status())));
            return new PreparedProduction(p, inputs, outputs, beforeDemands, beforeShares, allocations, reductions, staged, states, admitted, surplus, registry.storage().prepareReserveAll(reservations, tick));
        } catch (RuntimeException failure) { admitted.values().forEach(AdmissionLedger.Lease::close); throw failure; }
    }
    public final class PreparedProduction implements AutoCloseable {
        private final ProductionOrder order; private final List<InputPortion> inputs; private final List<OutputPortion> outputs;
        private final Map<UUID, Demand.Snapshot> beforeDemands, states; private final Map<UUID, CoverageShare> beforeShares, staged;
        private final Map<UUID, AllocationLedger.Entry> allocations; private final Map<UUID, Long> reductions;
        private final Map<UUID, AdmissionLedger.Lease> admitted; private final Demand surplus;
        private final StorageRegistry.PreparedReservations physical; private boolean closed;
        private PreparedProduction(ProductionOrder order, List<InputPortion> inputs, List<OutputPortion> outputs, Map<UUID, Demand.Snapshot> beforeDemands, Map<UUID, CoverageShare> beforeShares, Map<UUID, AllocationLedger.Entry> allocations, Map<UUID, Long> reductions, Map<UUID, CoverageShare> staged, Map<UUID, Demand.Snapshot> states, Map<UUID, AdmissionLedger.Lease> admitted, Demand surplus, StorageRegistry.PreparedReservations physical) {
            this.order = order; this.inputs = inputs; this.outputs = outputs; this.beforeDemands = beforeDemands; this.beforeShares = new LinkedHashMap<>(shares); this.allocations = allocations; this.reductions = reductions; this.staged = staged; this.states = states; this.admitted = admitted; this.surplus = surplus; this.physical = physical;
        }
        public List<InputPortion> inputs() { return inputs; } public List<OutputPortion> outputs() { return outputs; }
        public boolean unchanged() {
            registry.requireOwner(); if (closed || productions.get(order.id()) != order || !workshopAvailable(order)) return false;
            var work=registry.workBoard().work(order.workId()); var citizen=registry.citizen(order.citizenId());
            if(!registry.colony(order.colonyId()).available() || !order.citizenId().equals(work.assignee()) || !order.workId().equals(citizen.assignedWorkId())
                    || !Objects.equals(order.workshopId(),citizen.workplaceId()) || !order.recipe().professionId().equals(citizen.professionId())
                    || citizen.readiness()!=io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Readiness.READY || registry.bindings().activeEntity(citizen.citizenId()).isEmpty()) return false;
            if (shares.size() != beforeShares.size()) return false;
            for (var d : beforeDemands.values()) if (demand(d.id()).snapshot() != d) return false;
            for (var s : beforeShares.values()) if (shares.get(s.id()) != s) return false;
            for (var a : allocations.values()) if (!a.equals(registry.storage().allocations().get(a.id()))) return false;
            return true;
        }
        public void commit() {
            if (!unchanged()) throw new IllegalStateException("Prepared production changed");
            long remaining = order.batches() - 1;
            var next = order.update(remaining, remaining == 0 ? 0 : order.recipe().activeTicks(), Math.incrementExact(order.completedBatches()), false, order.workId(), null, remaining == 0 ? ProductionOrder.State.COMPLETED : ProductionOrder.State.PLANNED, order.lane(), order.priority());
            physical.commit(); registry.storage().reduceObligations(reductions); leases.putAll(admitted); admitted.clear();
            if (surplus != null) putDemand(surplus);
            publishShares(staged); states.forEach((id, s) -> demand(id).replace(s)); replaceProduction(next);
            productionIndexRevision = Math.incrementExact(productionIndexRevision); closed = true;
            for (var share : staged.values()) if (share.stage() == CoverageShare.Stage.RESERVED_STOCK && share.sourceOrderId() == null) allocateLocalReservation(share.id());
        }
        @Override public void close() { registry.requireOwner(); physical.close(); if (!closed) { closed = true; admitted.values().forEach(AdmissionLedger.Lease::close); admitted.clear(); } }
    }
    public PreparedTransfer preparePickup(UUID shareId, UUID orderId, StockRegion cargo, int maximum, long tick) {
        CoverageShare s = requiredShare(shareId); DeliveryOrder o = delivery(orderId);
        if (s.stage() != CoverageShare.Stage.RESERVED_STOCK || !orderId.equals(s.sourceOrderId()) || o.terminal()
                || o.returnRequired() || hasCargo(orderId) || demand(s.demandId()).snapshot().status() == Demand.Status.CANCELLED) throw new IllegalStateException("Pickup no longer permitted");
        return prepare(s, cargo, maximum, tick, TransferKind.PICKUP);
    }
    /** One real source-to-subject transfer becomes its consumption allocation, never a second replay. */
    public PreparedTransfer prepareSelfPickup(UUID shareId, UUID orderId, StockRegion citizenSlot, int maximum, long tick) {
        CoverageShare s = requiredShare(shareId); DeliveryOrder o = delivery(orderId);
        var d = demand(s.demandId()).snapshot();
        if (!foodConsumer(d.id()) || s.stage() != CoverageShare.Stage.RESERVED_STOCK || !orderId.equals(s.sourceOrderId())
                || o.terminal() || o.returnRequired() || hasCargo(orderId) || d.status() == Demand.Status.CANCELLED)
            throw new IllegalStateException("Self pickup no longer permitted");
        var owner = registry.workBoard().work(d.ownerId()); var subject = registry.citizen(owner.subjectId());
        if (!citizenSlot.storage().identity().equals(subject.citizenId()) || citizenSlot.storage().bindingEpoch() != subject.bindingEpoch()
                || !subject.citizenId().equals(o.citizenId())) throw new IllegalArgumentException("Self pickup must reach the prescribed citizen");
        return prepare(s, citizenSlot, maximum, tick, TransferKind.SELF_PICKUP);
    }
    public PreparedTransfer prepareTransfer(UUID shareId, StockRegion destination, int maximum, long tick) {
        CoverageShare s = requiredShare(shareId);
        if (s.stage() != CoverageShare.Stage.IN_TRANSIT || delivery(s.sourceOrderId()).returnRequired()
                || demand(s.demandId()).snapshot().status() == Demand.Status.CANCELLED) throw new IllegalStateException("Delivery no longer permitted");
        return prepare(s, destination, maximum, tick, TransferKind.DELIVER);
    }
    public PreparedTransfer prepareReturn(UUID shareId, StockRegion buffer, int maximum, long tick) {
        CoverageShare s = requiredShare(shareId);
        if (s.stage() != CoverageShare.Stage.IN_TRANSIT || !delivery(s.sourceOrderId()).returnRequired()) throw new IllegalStateException("Cargo not returning");
        return prepare(s, buffer, maximum, tick, productionSurplus(s.demandId()) ? TransferKind.DELIVER : TransferKind.RETURN);
    }
    private enum TransferKind { PICKUP, SELF_PICKUP, DELIVER, RETURN }
    private PreparedTransfer prepare(CoverageShare share, StockRegion destination, int maximum, long tick, TransferKind kind) {
        var d = demand(share.demandId()).snapshot();
        if (maximum <= 0 || maximum > share.quantity()) throw new IllegalArgumentException("Invalid transfer maximum");
        if (productionSurplus(d.id()) && kind != TransferKind.PICKUP && registry.storage().registrations(d.colonyId()).stream()
                .noneMatch(r -> r.slots().contains(destination) && surplusBuffer(d.colonyId(), r, share.slot().storage().dimension())))
            throw new IllegalArgumentException("Surplus must physically reach registered safe storage");
        boolean retained = kind == TransferKind.PICKUP || kind == TransferKind.SELF_PICKUP || kind == TransferKind.RETURN && d.status() != Demand.Status.CANCELLED
                || kind == TransferKind.DELIVER && d.goalKind() == Demand.GoalKind.CONSUMPTION;
        UUID resultId = fresh(), splitObligation = fresh(); AdmissionLedger.Lease extra = null;
        try {
            if (share.quantity() > 1 && (kind != TransferKind.RETURN || retained)) {
                if (shares.size() >= MAX_SHARES) throw new IllegalArgumentException("Coverage envelope exceeded");
                extra = admit(d.colonyId(), relocationLane(share.slot(), d.lane()), Resource.COVERAGE_SHARES);
            }
            var physical = registry.storage().prepareMoveReservation(share.obligationId(), destination, splitObligation,
                    kind == TransferKind.DELIVER || kind == TransferKind.SELF_PICKUP, retained, maximum, tick);
            return new PreparedTransfer(share, d, delivery(share.sourceOrderId()), destination, maximum, kind, retained, resultId, extra, physical);
        } catch (RuntimeException failure) { if (extra != null) extra.close(); throw failure; }
    }
    /** Holds every possible split lease while the native effect executes in the same owner step. */
    public final class PreparedTransfer implements AutoCloseable {
        private final CoverageShare share;
        private final Demand.Snapshot before;
        private final DeliveryOrder order;
        private final StockRegion destination;
        private final int maximum;
        private final TransferKind kind;
        private final boolean retained;
        private final UUID resultId;
        private AdmissionLedger.Lease extra;
        private final StorageRegistry.PreparedMove physical;
        private boolean closed;
        private PreparedTransfer(CoverageShare share, Demand.Snapshot before, DeliveryOrder order, StockRegion destination,
                int maximum, TransferKind kind, boolean retained, UUID resultId, AdmissionLedger.Lease extra, StorageRegistry.PreparedMove physical) {
            this.share = share; this.before = before; this.order = order; this.destination = destination; this.maximum = maximum;
            this.kind = kind; this.retained = retained; this.resultId = resultId; this.extra = extra; this.physical = physical;
        }
        public StockRegion source() { return share.slot(); }
        public StockRegion destination() { return destination; }
        public ItemDescriptor item() { return share.item(); }
        public int maximum() { return maximum; }
        public void commit(int moved) {
            registry.requireOwner();
            if (closed || moved <= 0 || moved > maximum || shares.get(share.id()) != share
                    || demand(before.id()).snapshot() != before || deliveries.get(order.id()) != order) throw new IllegalStateException("Prepared logical transition changed");
            boolean full = moved == share.quantity(); UUID id = full ? share.id() : resultId;
            CoverageShare result = null;
            if (kind != TransferKind.RETURN || retained) {
                CoverageShare.Stage stage = kind == TransferKind.PICKUP ? CoverageShare.Stage.IN_TRANSIT
                        : kind == TransferKind.RETURN ? CoverageShare.Stage.RESERVED_STOCK
                        : retained ? CoverageShare.Stage.ALLOCATED : CoverageShare.Stage.FULFILLED;
                result = new CoverageShare(id, share.colonyId(), share.demandId(), kind == TransferKind.RETURN || !retained && !order.ownerDemandId().equals(share.demandId()) ? null : order.id(),
                        share.productionOrderId(), retained ? physical.destinationObligation(moved) : null, retained ? destination : null,
                        share.item(), moved, Math.addExact(share.revision(), 1), stage);
            }
            Map<UUID, CoverageShare> staged = new LinkedHashMap<>(shares); staged.remove(share.id());
            if (!full) staged.put(share.id(), resized(share, share.quantity() - moved));
            if (result != null) staged.put(id, result);
            boolean reached = kind == TransferKind.DELIVER || kind == TransferKind.SELF_PICKUP;
            long delivered = reached ? Math.addExact(before.deliveredTotal(), moved) : before.deliveredTotal();
            long transferred = reached ? Math.addExact(order.transferred(), moved) : order.transferred();
            boolean cargoLeft = staged.values().stream().anyMatch(s -> order.id().equals(s.sourceOrderId()) && s.stage() == CoverageShare.Stage.IN_TRANSIT);
            if ((kind == TransferKind.RETURN || order.returnRequired()) && !cargoLeft) {
                for (var old : List.copyOf(staged.values())) if (order.id().equals(old.sourceOrderId()) && old.stage() == CoverageShare.Stage.RESERVED_STOCK)
                    staged.put(old.id(), new CoverageShare(old.id(), old.colonyId(), old.demandId(), null, old.productionOrderId(), old.obligationId(), old.slot(), old.item(), old.quantity(), Math.addExact(old.revision(), 1), old.stage()));
            }
            var nextDemand = totals(before, staged.values(), before.required(), delivered, before.status());
            var state = kind == TransferKind.PICKUP ? DeliveryOrder.State.IN_TRANSIT : kind == TransferKind.RETURN
                    ? cargoLeft ? DeliveryOrder.State.RETURNING : DeliveryOrder.State.RETURNED
                    : transferred == order.quantity() ? DeliveryOrder.State.COMPLETED : cargoLeft ? DeliveryOrder.State.IN_TRANSIT : DeliveryOrder.State.TRANSFERRED;
            if (kind == TransferKind.DELIVER && !cargoLeft && state == DeliveryOrder.State.TRANSFERRED
                    && staged.values().stream().noneMatch(s -> order.id().equals(s.sourceOrderId()) && s.covered())) state = DeliveryOrder.State.RETURNED;
            if (order.returnRequired()) state = cargoLeft ? DeliveryOrder.State.RETURNING : DeliveryOrder.State.RETURNED;
            var nextOrder = delivery(order, transferred, state);
            physical.commit(moved);
            if (!full && result != null) { leases.put(id, extra); extra = null; }
            publishShares(staged); demand(before.id()).replace(nextDemand); deliveries.put(order.id(), nextOrder);
            closed = true; if (extra != null) { extra.close(); extra = null; }
        }
        @Override public void close() { registry.requireOwner(); physical.close(); if (!closed) { closed = true; if (extra != null) { extra.close(); extra = null; } } }
    }
    public void assignDelivery(UUID orderId, UUID citizenId, UUID workId) {
        DeliveryOrder o = delivery(orderId);
        if (o.terminal() && (citizenId != null || workId != null) || citizenId != null && workId == null) throw new IllegalStateException("Invalid delivery assignment");
        if (workId != null) { var w = registry.workBoard().work(workId); if (!w.colonyId().equals(o.colonyId()) || !io.github.kpuctajluk.colonyloom.core.work.WorkOrder.DELIVERY.equals(w.typeId())) throw new IllegalArgumentException("Invalid delivery work"); }
        if (citizenId != null && !registry.citizen(citizenId).colonyId().equals(o.colonyId())) throw new IllegalArgumentException("Foreign courier");
        if (hasCargo(orderId) && (!Objects.equals(o.citizenId(), citizenId) || !Objects.equals(o.workId(), workId))) throw new IllegalStateException("Cannot replace bound cargo courier");
        if (Objects.equals(o.citizenId(), citizenId) && Objects.equals(o.workId(), workId)) return;
        registry.beforeMutation(); deliveries.put(o.id(), new DeliveryOrder(o.id(), o.colonyId(), o.ownerDemandId(), o.source(), o.destination(), o.item(), o.quantity(), o.transferred(), Math.addExact(o.revision(), 1), citizenId, workId, o.state(), o.lane(), o.priority()));
        if (o.workId() != null) deliveryWorks.remove(o.workId(), o.id());
        if (workId != null) deliveryWorks.put(workId, o.id());
    }
    public void returnDelivery(UUID orderId) {
        DeliveryOrder o = delivery(orderId); if (o.terminal() || o.returnRequired()) return;
        registry.beforeMutation();
        boolean cargo = hasCargo(orderId);
        if (!cargo) for (var s : orderShares(orderId)) if (s.stage() == CoverageShare.Stage.RESERVED_STOCK)
            putShare(s.id(), new CoverageShare(s.id(), s.colonyId(), s.demandId(), null, s.productionOrderId(), s.obligationId(), s.slot(), s.item(), s.quantity(), Math.addExact(s.revision(), 1), s.stage()));
        deliveries.put(orderId, delivery(o, o.transferred(), cargo ? DeliveryOrder.State.RETURNING : DeliveryOrder.State.RETURNED));
    }
    /** Explicit operator cutover abandons promises without touching native contents. */
    public void acceptWorld(UUID colonyId) {
        registry.colony(colonyId); Map<UUID, CoverageShare> staged = new LinkedHashMap<>(shares);
        for (var s : shares.values()) if (s.colonyId().equals(colonyId) && s.stage() != CoverageShare.Stage.FULFILLED) staged.remove(s.id());
        Map<UUID, Long> releases = new LinkedHashMap<>();
        for (var r : registry.storage().reservations().entries()) if (r.colonyId().equals(colonyId)) releases.put(r.id(), 0L);
        for (var a : registry.storage().allocations().entries()) if (a.colonyId().equals(colonyId)) releases.put(a.id(), 0L);
        Map<UUID, Demand.Snapshot> states = new LinkedHashMap<>();
        for (var d : demands.values()) if (d.snapshot().colonyId().equals(colonyId)) {
            var s = d.snapshot(); states.put(s.id(), totals(s, staged.values(), s.required(), s.deliveredTotal(), Demand.Status.CANCELLED));
        }
        registry.beforeMutation(); registry.storage().reduceObligations(releases); publishShares(staged);
        states.forEach((id, s) -> demand(id).replace(s));
        for (var o : List.copyOf(deliveries.values())) if (o.colonyId().equals(colonyId) && !o.terminal()) deliveries.put(o.id(), delivery(o, o.transferred(), DeliveryOrder.State.CANCELLED));
        for (var o : List.copyOf(productions.values())) if (o.colonyId().equals(colonyId))
            replaceProduction(o.update(o.batches(), o.remainingActiveTicks(), o.completedBatches(), o.batchStarted(), o.workId(), o.citizenId(), ProductionOrder.State.CANCELLED, o.lane(), o.priority()));
        dirtyObligations.removeIf(id -> !obligationShares.containsKey(id));
    }

    public void retireDelivery(UUID orderId) {
        DeliveryOrder o = delivery(orderId);
        if (!o.terminal() || hasCargo(orderId)) throw new IllegalStateException("Delivery retains live cargo");
        if (o.workId() != null && !registry.workBoard().work(o.workId()).terminal()) throw new IllegalStateException("Delivery work still active");
        for (var e : registry.effects().snapshots()) if (o.workId() != null && o.workId().equals(e.workId())) throw new IllegalStateException("Delivery has native evidence");
        registry.beforeMutation();
        for (var s : orderShares(orderId)) putShare(s.id(), new CoverageShare(s.id(), s.colonyId(), s.demandId(), null, s.productionOrderId(), s.obligationId(), s.slot(), s.item(), s.quantity(), Math.addExact(s.revision(), 1), s.stage()));
        removeDelivery(orderId); leases.remove(orderId).close();
    }
    /** Call only after checkpoint compaction has removed the food and transfer witnesses. */
    public void retireFood(UUID workId) {
        var work = registry.workBoard().work(workId);
        if (!io.github.kpuctajluk.colonyloom.core.work.WorkOrder.FOOD.equals(work.typeId()) || !work.terminal())
            throw new IllegalStateException("Food work still active");
        for (var effect : registry.effects().snapshots()) if (workId.equals(effect.workId()))
            throw new IllegalStateException("Food retains native evidence");
        var owned = demands.values().stream().filter(d -> workId.equals(d.snapshot().ownerId())).toList();
        for (var demand : owned) {
            var state = demand.snapshot();
            if (state.status() != Demand.Status.COMPLETED && state.status() != Demand.Status.CANCELLED)
                throw new IllegalStateException("Food demand still active");
            for (var order : deliveries.values()) if (demand.id().equals(order.ownerDemandId()))
                throw new IllegalStateException("Food retains delivery");
            for (var order : productions.values()) if (demand.id().equals(order.ownerDemandId()))
                throw new IllegalStateException("Food retains production");
            for (var share : demandShares(demand.id())) if (share.stage() != CoverageShare.Stage.FULFILLED
                    || share.obligationId() != null || share.sourceOrderId() != null)
                throw new IllegalStateException("Food retains physical obligation");
        }
        for (var reservation : registry.storage().reservations().entries()) if (workId.equals(reservation.ownerId()))
            throw new IllegalStateException("Food retains reservation");
        for (var allocation : registry.storage().allocations().entries()) if (workId.equals(allocation.ownerId()))
            throw new IllegalStateException("Food retains allocation");
        registry.beforeMutation();
        for (var demand : owned) {
            for (var share : demandShares(demand.id())) {
                unindex(share); shares.remove(share.id()); leases.remove(share.id()).close();
            }
            removeDemandIndex(demand); demands.remove(demand.id()); leases.remove(demand.id()).close();
        }
    }
    public boolean canRetireProduction(UUID productionId) {
        registry.requireOwner(); ProductionOrder order = productions.get(productionId);
        return order != null && productionRetirementBlocker(order) == null;
    }
    private String productionRetirementBlocker(ProductionOrder order) {
        if (!order.terminal() || order.batchStarted() || order.citizenId() != null) return "Production still active";
        UUID workId = order.workId();
        if (workId != null) {
            var work = registry.workBoard().findWork(workId);
            if (work == null) return "Production work is missing";
            if (!work.terminal() || work.assignee() != null) return "Production work still active";
            for (var citizen : registry.citizensView()) if (workId.equals(citizen.assignedWorkId())) return "Production work retains assignment";
            for (var claim : registry.targetClaims().snapshots()) if (workId.equals(claim.ownerId())) return "Production work retains target";
            for (var dependency : registry.workBoard().works()) if (dependency.dependencies().contains(workId)) return "Production work has dependents";
        }
        for (var effect : registry.effects().snapshots()) {
            var craft = effect.craft();
            if (craft != null && order.id().equals(craft.productionId()) || workId != null && workId.equals(effect.workId()))
                return "Production retains native evidence";
        }
        Set<UUID> children = ownerDemands.getOrDefault(order.id(), Set.of());
        for (UUID childId : children) {
            Demand child = demands.get(childId); if (child == null) return "Production ingredient demand is missing";
            var state = child.snapshot();
            if (state.status() != Demand.Status.COMPLETED && state.status() != Demand.Status.CANCELLED) return "Production ingredient demand is not terminal";
            if (!demandOrders.getOrDefault(childId, Set.of()).isEmpty()) return "Production ingredient retains order";
            for (UUID shareId : demandShares.getOrDefault(childId, Set.of())) {
                CoverageShare share = shares.get(shareId); if (share == null) return "Production ingredient share is missing";
                if (share.stage() != CoverageShare.Stage.FULFILLED || share.obligationId() != null || share.sourceOrderId() != null || share.productionOrderId() != null)
                    return "Production ingredient retains physical obligation";
            }
        }
        for (UUID shareId : productionShares.getOrDefault(order.id(), Set.of())) {
            CoverageShare share = shares.get(shareId); if (share == null) return "Production output index changed";
            if (share.stage() != CoverageShare.Stage.FULFILLED || share.obligationId() != null || share.sourceOrderId() != null)
                return "Production retains live output";
        }
        for (var reservation : registry.storage().reservations().entries()) if (order.id().equals(reservation.ownerId())) return "Production retains reservation";
        for (var allocation : registry.storage().allocations().entries()) if (order.id().equals(allocation.ownerId())) return "Production retains allocation";
        return null;
    }
    public void retireProduction(UUID productionId) {
        ProductionOrder order = production(productionId);
        String blocker = productionRetirementBlocker(order);
        if (blocker != null) throw new IllegalStateException(blocker);
        UUID workId = order.workId();
        Set<UUID> children = ownerDemands.getOrDefault(productionId, Set.of());
        registry.beforeMutation();
        for (UUID childId : List.copyOf(children)) {
            Demand child = demands.get(childId);
            for (UUID shareId : List.copyOf(demandShares.getOrDefault(childId, Set.of()))) {
                CoverageShare share = shares.get(shareId); unindex(share); shares.remove(shareId); leases.remove(shareId).close();
            }
            removeDemandIndex(child); demands.remove(childId); leases.remove(childId).close();
        }
        for (UUID shareId : List.copyOf(productionShares.getOrDefault(productionId, Set.of()))) {
            CoverageShare share = shares.get(shareId);
            putShare(shareId, new CoverageShare(share.id(), share.colonyId(), share.demandId(), null, null, null, null,
                    share.item(), share.quantity(), Math.incrementExact(share.revision()), share.stage()));
        }
        productionShares.remove(productionId);
        removeProduction(productionId);
        leases.remove(productionId).close();
    }
    public void markDeliveryLost(UUID orderId) {
        DeliveryOrder o = delivery(orderId); if (o.terminal()) return;
        Map<UUID, CoverageShare> staged = new LinkedHashMap<>(shares); Map<UUID, Long> releases = new LinkedHashMap<>();
        for (var s : orderShares(orderId)) if (s.stage() == CoverageShare.Stage.IN_TRANSIT || s.stage() == CoverageShare.Stage.RESERVED_STOCK) {
            staged.remove(s.id()); if (registry.storage().reservations().get(s.obligationId()) != null) releases.put(s.obligationId(), 0L);
        }
        var states = recomputed(staged); registry.beforeMutation(); registry.storage().reduceObligations(releases);
        publishShares(staged); states.forEach((id, s) -> demand(id).replace(s)); deliveries.put(orderId, delivery(o, o.transferred(), DeliveryOrder.State.LOST));
    }
    /** Routes returned buffer reservations without inventing stock or increasing coverage. */
    public DeliveryOrder routeReservedStock(UUID shareId) {
        CoverageShare s = requiredShare(shareId); var d = demand(s.demandId()).snapshot();
        if (s.stage() != CoverageShare.Stage.RESERVED_STOCK || s.sourceOrderId() != null || d.status() == Demand.Status.CANCELLED) throw new IllegalStateException("Stock not awaiting route");
        if (productions.size() + deliveries.size() >= MAX_ORDERS) throw new IllegalArgumentException("Order envelope exceeded");
        UUID id = fresh(); var lease = admit(s.colonyId(), d.lane(), Resource.DELIVERIES_AND_PRODUCTION_ORDERS);
        try { registry.beforeMutation(); } catch (RuntimeException failure) { lease.close(); throw failure; }
        DeliveryOrder o = new DeliveryOrder(id, s.colonyId(), s.demandId(), s.slot(), d.destination(), s.item(), s.quantity(), 0, 0, null, null, DeliveryOrder.State.PLANNED, d.lane(), d.priority());
        addDelivery(o); leases.put(id, lease);
        putShare(s.id(), new CoverageShare(s.id(), s.colonyId(), s.demandId(), id, s.productionOrderId(), s.obligationId(), s.slot(), s.item(), s.quantity(), Math.addExact(s.revision(), 1), s.stage()));
        return o;
    }

    public void fulfillConsumption(UUID shareId, long quantity) {
        try (var prepared = prepareConsumption(shareId, Math.toIntExact(quantity))) { prepared.commit(Math.toIntExact(quantity)); }
    }
    public PreparedConsumption prepareConsumption(UUID shareId, int maximum) {
        CoverageShare share = requiredShare(shareId); var d = demand(share.demandId()).snapshot();
        if (share.stage() != CoverageShare.Stage.ALLOCATED || d.goalKind() != Demand.GoalKind.CONSUMPTION || maximum <= 0 || maximum > share.quantity()) throw new IllegalArgumentException("Invalid consumption");
        if (productions.containsKey(d.ownerId())) throw new IllegalStateException("Production ingredients require atomic PreparedProduction");
        var allocation = registry.storage().allocations().get(share.obligationId());
        if (allocation == null || allocation.count() != share.quantity() || !allocation.slot().equals(share.slot()) || !allocation.item().equals(share.item())) throw new IllegalStateException("Consumed allocation changed");
        UUID split = fresh(); AdmissionLedger.Lease extra = null;
        if (share.quantity() > 1) { if (shares.size() >= MAX_SHARES) throw new IllegalArgumentException("Coverage envelope exceeded"); extra = admit(d.colonyId(), d.lane(), Resource.COVERAGE_SHARES); }
        return new PreparedConsumption(share, d, allocation, maximum, split, extra);
    }
    public final class PreparedConsumption implements AutoCloseable {
        private final CoverageShare share; private final Demand.Snapshot before; private final AllocationLedger.Entry allocation;
        private final int maximum; private final UUID split; private AdmissionLedger.Lease extra; private boolean closed;
        private PreparedConsumption(CoverageShare share, Demand.Snapshot before, AllocationLedger.Entry allocation, int maximum, UUID split, AdmissionLedger.Lease extra) {
            this.share = share; this.before = before; this.allocation = allocation; this.maximum = maximum; this.split = split; this.extra = extra;
        }
        public boolean unchanged() { registry.requireOwner(); return !closed && shares.get(share.id()) == share && demand(before.id()).snapshot() == before && allocation.equals(registry.storage().allocations().get(allocation.id())); }
        public void commit(int consumed) {
            if (!unchanged() || consumed <= 0 || consumed > maximum) throw new IllegalStateException("Prepared consumption changed");
            UUID id = consumed == share.quantity() ? share.id() : split;
            var fulfilled = new CoverageShare(id, share.colonyId(), share.demandId(), share.sourceOrderId(), share.productionOrderId(), null, null, share.item(), consumed, Math.incrementExact(share.revision()), CoverageShare.Stage.FULFILLED);
            var staged = new LinkedHashMap<>(shares); staged.remove(share.id()); if (consumed < share.quantity()) staged.put(share.id(), resized(share, share.quantity() - consumed)); staged.put(id, fulfilled);
            var next = totals(before, staged.values(), before.required(), before.deliveredTotal(), before.status());
            registry.storage().reduceObligations(Map.of(allocation.id(), allocation.count() - consumed));
            if (!id.equals(share.id())) { leases.put(id, extra); extra = null; }
            publishShares(staged); demand(before.id()).replace(next); close();
        }
        @Override public void close() { registry.requireOwner(); closed = true; if (extra != null) { extra.close(); extra = null; } }
    }
    public PreparedAllocationMove prepareAllocationMove(UUID shareId, StockRegion destination, int maximum, long tick) {
        CoverageShare s = requiredShare(shareId); var d = demand(s.demandId()).snapshot();
        if (s.stage() != CoverageShare.Stage.ALLOCATED || maximum <= 0 || maximum > s.quantity() || d.status() == Demand.Status.CANCELLED) throw new IllegalStateException("Allocation cannot move");
        AdmissionLedger.Lease extra = null;
        var production=productions.get(d.ownerId()); if(production!=null && production.batchStarted()) throw new IllegalStateException("Begun production allocation cannot relocate");
        try {
            if (s.quantity() > 1) { if (shares.size() >= MAX_SHARES) throw new IllegalArgumentException("Coverage envelope exceeded"); extra = admit(d.colonyId(), relocationLane(s.slot(), d.lane()), Resource.COVERAGE_SHARES); }
            return new PreparedAllocationMove(s, d, destination, maximum, fresh(), extra, registry.storage().prepareMoveAllocation(s.obligationId(), destination, fresh(), true, true, maximum, tick));
        } catch (RuntimeException failure) { if (extra != null) extra.close(); throw failure; }
    }
    private Lane relocationLane(StockRegion source, Lane fallback) {
        if (source.storage().bindingEpoch() > 0) {
            try { var citizen = registry.citizen(source.storage().identity());
                if (citizen.assignedWorkId() != null && registry.workBoard().work(citizen.assignedWorkId()).criticalService()) return Lane.CRITICAL;
            } catch (IllegalArgumentException absent) { return fallback; }
        }
        return fallback;
    }
    public final class PreparedAllocationMove implements AutoCloseable {
        private final CoverageShare share; private final Demand.Snapshot before; private final StockRegion destination; private final int maximum; private final UUID split;
        private AdmissionLedger.Lease extra; private final StorageRegistry.PreparedMove physical; private boolean closed;
        private PreparedAllocationMove(CoverageShare share, Demand.Snapshot before, StockRegion destination, int maximum, UUID split, AdmissionLedger.Lease extra, StorageRegistry.PreparedMove physical) { this.share = share; this.before = before; this.destination = destination; this.maximum = maximum; this.split = split; this.extra = extra; this.physical = physical; }
        public StockRegion source() { return share.slot(); } public StockRegion destination() { return destination; } public ItemDescriptor item() { return share.item(); } public int maximum() { return maximum; }
        public void commit(int moved) {
            registry.requireOwner(); if (closed || moved <= 0 || moved > maximum || shares.get(share.id()) != share || demand(before.id()).snapshot() != before) throw new IllegalStateException("Prepared allocation changed");
            boolean full = moved == share.quantity(); UUID id = full ? share.id() : split;
            var result = new CoverageShare(id, share.colonyId(), share.demandId(), share.sourceOrderId(), share.productionOrderId(), physical.destinationObligation(moved), destination, share.item(), moved, Math.incrementExact(share.revision()), CoverageShare.Stage.ALLOCATED);
            var staged = new LinkedHashMap<>(shares); staged.remove(share.id()); if (!full) staged.put(share.id(), resized(share, share.quantity() - moved)); staged.put(id, result);
            var next = totals(before, staged.values(), before.required(), before.deliveredTotal(), before.status()); physical.commit(moved);
            if (!full) { leases.put(id, extra); extra = null; } publishShares(staged); demand(before.id()).replace(next); close();
        }
        @Override public void close() { registry.requireOwner(); physical.close(); closed = true; if (extra != null) { extra.close(); extra = null; } }
    }
    public CoverageShare allocateStock(UUID demandId, StockRegion slot, ItemDescriptor item, long quantity, long tick) {
        var d = demand(demandId).snapshot(); active(d, item, quantity);
        if (d.goalKind() != Demand.GoalKind.CONSUMPTION || shares.size() >= MAX_SHARES) throw new IllegalArgumentException("Invalid local allocation");
        UUID id = fresh(), obligation = fresh(); var lease = admit(d.colonyId(), d.lane(), Resource.COVERAGE_SHARES);
        var result = new CoverageShare(id, d.colonyId(), demandId, null, null, obligation, slot, item, quantity, 0, CoverageShare.Stage.ALLOCATED);
        var next = state(d, d.required(), d.fulfilled(), Math.addExact(d.allocated(), quantity), d.covered(), d.deliveredTotal(), d.status());
        try { registry.storage().allocations().allocate(obligation, d.colonyId(), d.ownerId(), slot, item, quantity, tick, d.lane()); }
        catch (RuntimeException failure) { lease.close(); throw failure; }
        leases.put(id, lease); putShare(id, result); demand(demandId).replace(next); return result;
    }
    public void updateRequired(UUID demandId, long required) {
        var d = demand(demandId); var s = d.snapshot(); Demand.quantity(required);
        if (s.status() == Demand.Status.CANCELLED) throw new IllegalStateException("Cancelled demand cannot reopen");
        if (required <= s.required()) { reduceRequired(demandId, required); return; }
        var next = state(s, required, s.fulfilled(), s.allocated(), s.covered(), s.deliveredTotal(), Demand.Status.ACTIVE);
        registry.beforeMutation(); d.replace(next);
    }
    private CoverageShare requiredShare(UUID id) { registry.requireOwner(); CoverageShare s = shares.get(id); if (s == null) throw new IllegalArgumentException("Unknown coverage share"); return s; }
    private static CoverageShare resized(CoverageShare s, long count) { return new CoverageShare(s.id(), s.colonyId(), s.demandId(), s.sourceOrderId(), s.productionOrderId(), s.obligationId(), s.slot(), s.item(), count, Math.addExact(s.revision(), 1), s.stage()); }
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
            if (old.sourceOrderId() != null) {
                DeliveryOrder order = deliveries.get(old.sourceOrderId());
                if (order != null && !order.terminal()) deliveries.put(order.id(), delivery(order, order.transferred(),
                        old.stage() == CoverageShare.Stage.IN_TRANSIT && actual == 0 && !hasCargo(order.id())
                                && orderShares(order.id()).stream().noneMatch(CoverageShare::covered) ? DeliveryOrder.State.LOST
                                : order.returnRequired() ? DeliveryOrder.State.RETURNING : DeliveryOrder.State.BLOCKED));
            }
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
        if (productionSurplus(share.demandId())) throw new IllegalStateException("Begun output requires physical surplus delivery");
        if (share.stage() == CoverageShare.Stage.FULFILLED || share.stage() == CoverageShare.Stage.IN_TRANSIT) throw new IllegalArgumentException("Cannot erase consumed history or actual cargo");
        var owner=productions.get(demand(share.demandId()).snapshot().ownerId());
        if(owner!=null && owner.batchStarted() && share.stage()==CoverageShare.Stage.ALLOCATED) throw new IllegalStateException("Begun batch retains ingredient allocation");
        try (var plan = new ReductionPlan()) {
            plan.releaseShare(share, share.quantity()); plan.resize(); plan.commit();
        }
    }
    public void reduceRequired(UUID demandId, long required) { reduce(demandId, required, false); }
    public void cancel(UUID demandId) {
        if (productionSurplus(demandId)) throw new IllegalStateException("Begun output requires physical surplus delivery");
        reduce(demandId, demand(demandId).snapshot().required(), true);
    }
    private void reduce(UUID demandId, long required, boolean cancel) {
        Demand d = demand(demandId); var old = d.snapshot(); Demand.quantity(required);
        if (productionSurplus(demandId) && (cancel || required < old.required()))
            throw new IllegalStateException("Begun output requires physical surplus delivery");
        var producer = productions.get(old.ownerId());
        if (producer != null && producer.batchStarted()) {
            long minimum = old.fulfilled();
            for (var ingredient : producer.recipe().ingredients()) if (ingredient.matcher().equals(old.matcher())) minimum = Math.addExact(minimum, ingredient.count());
            if (cancel || required < minimum) throw new IllegalStateException("Begun batch retains its indivisible ingredient goal");
        }
        if (!cancel && required > old.required()) throw new IllegalArgumentException("Required reduction cannot increase goal");
        if (old.status() == Demand.Status.CANCELLED || !cancel && required == old.required()) return;
        try (var plan = new ReductionPlan()) {
            plan.reduceDemand(demandId, required, cancel); plan.resize(); plan.commit();
        }
    }
    /** All recursively released producer inputs and physical outputs are admitted before any owner changes. */
    private final class ReductionPlan implements AutoCloseable {
        private final Map<UUID, CoverageShare> staged = new LinkedHashMap<>(shares);
        private final Map<UUID, Demand.Snapshot> states = new LinkedHashMap<>();
        private final Map<UUID, ProductionOrder> orders = new LinkedHashMap<>(productions);
        private final Map<UUID, Demand> surplus = new LinkedHashMap<>();
        private final Map<UUID, AdmissionLedger.Lease> admitted = new LinkedHashMap<>();
        private final Map<UUID, Long> reductions = new LinkedHashMap<>();
        private final List<StorageRegistry.OwnershipTransfer> transfers = new ArrayList<>();
        private final Set<UUID> returning = new LinkedHashSet<>();
        private Demand.Snapshot current(UUID id) { return states.getOrDefault(id, demand(id).snapshot()); }
        private void reduceDemand(UUID id, long required, boolean cancel) {
            var old = current(id); var producer = orders.get(old.ownerId());
            if (producer != null && producer.batchStarted()) {
                long minimum = old.fulfilled();
                for (var ingredient : producer.recipe().ingredients()) if (ingredient.matcher().equals(old.matcher())) minimum = Math.addExact(minimum, ingredient.count());
                if (cancel || required < minimum) throw new IllegalStateException("Begun batch retains its indivisible ingredient goal");
            }
            if (old.status() == Demand.Status.CANCELLED || !cancel && required == old.required()) return;
            long excess = cancel ? Long.MAX_VALUE : Math.max(0, old.fulfilled() + old.allocated() + old.covered() - required);
            for (var stage : List.of(CoverageShare.Stage.PROMISED_OUTPUT, CoverageShare.Stage.RESERVED_STOCK, CoverageShare.Stage.ALLOCATED, CoverageShare.Stage.IN_TRANSIT))
                for (var share : List.copyOf(staged.values())) if (share.demandId().equals(id) && share.stage() == stage && excess > 0) {
                    if (stage == CoverageShare.Stage.IN_TRANSIT && !share.physicallyProduced()) {
                        if (cancel) returning.add(share.sourceOrderId());
                        continue;
                    }
                    long released = Math.min(excess, share.quantity()); excess -= released; releaseShare(share, released);
                }
            states.put(id, totals(old, staged.values(), required, old.deliveredTotal(), cancel ? Demand.Status.CANCELLED : Demand.Status.ACTIVE));
        }
        private void releaseShare(CoverageShare share, long released) {
            long kept = share.quantity() - released;
            if (kept == 0) staged.remove(share.id()); else staged.put(share.id(), resized(share, kept));
            if (!share.physicallyProduced()) {
                if (share.obligationId() != null) reductions.put(share.obligationId(), kept);
            } else {
                if (demands.size() + surplus.size() >= MAX_DEMANDS) throw new IllegalArgumentException("Surplus demand envelope exceeded");
                var old = current(share.demandId()); UUID id = fresh();
                var destination = registry.storage().registrations(share.colonyId()).stream()
                        .filter(r -> surplusBuffer(share.colonyId(), r, share.slot().storage().dimension()))
                        .min(Comparator.comparing(StorageRegistry.Registration::id)).map(StorageRegistry.Registration::address).orElse(old.destination());
                var goal = new Demand(new Demand.Snapshot(id, share.colonyId(), id, new ItemMatcher(share.item().itemId(), share.item()),
                        Demand.GoalKind.DELIVERY, destination, released, 0, 0, released, 0, 0, old.lane(), old.priority(), old.createdTick(), Demand.Status.ACTIVE, List.of()));
                admitted.put(id, admit(share.colonyId(), old.lane(), Resource.DEMANDS)); surplus.put(id, goal);
                UUID shareId = kept == 0 ? share.id() : fresh(), obligation = kept == 0 ? share.obligationId() : fresh();
                if (kept > 0) {
                    if (staged.size() >= MAX_SHARES) throw new IllegalArgumentException("Surplus share envelope exceeded");
                    admitted.put(shareId, admit(share.colonyId(), old.lane(), Resource.COVERAGE_SHARES));
                }
                boolean cargo = share.stage() == CoverageShare.Stage.IN_TRANSIT;
                staged.put(shareId, new CoverageShare(shareId, share.colonyId(), id, cargo ? share.sourceOrderId() : null,
                        share.productionOrderId(), obligation, share.slot(), share.item(), released, Math.incrementExact(share.revision()),
                        cargo ? CoverageShare.Stage.IN_TRANSIT : CoverageShare.Stage.RESERVED_STOCK));
                transfers.add(new StorageRegistry.OwnershipTransfer(share.obligationId(), obligation, id, released));
                if (cargo) returning.add(share.sourceOrderId());
            }
            var old = current(share.demandId()); states.put(old.id(), totals(old, staged.values(), old.required(), old.deliveredTotal(), old.status()));
        }
        private void resize() {
            boolean changed;
            do {
                changed = false;
                for (var p : List.copyOf(orders.values())) {
                    if (p.terminal()) continue;
                    long promised = 0;
                    for (var share : staged.values()) if (p.id().equals(share.sourceOrderId()) && share.stage() == CoverageShare.Stage.PROMISED_OUTPUT) promised = Math.addExact(promised, share.quantity());
                    long needed = Math.max(p.batchStarted() ? 1 : 0, promised == 0 ? 0 : p.recipe().batchesFor(promised));
                    if (needed >= p.batches()) continue;
                    orders.put(p.id(), p.update(needed, needed == 0 ? 0 : p.remainingActiveTicks(), p.completedBatches(), p.batchStarted(), p.workId(), p.citizenId(), needed == 0 ? ProductionOrder.State.CANCELLED : p.state(), p.lane(), p.priority()));
                    changed = true;
                    for (int i = 0; i < p.recipe().ingredients().size(); i++) {
                        UUID child = UUID.nameUUIDFromBytes((p.id() + ":ingredient:" + i).getBytes(StandardCharsets.UTF_8));
                        reduceDemand(child, Math.multiplyExact(Math.addExact(p.completedBatches(), needed), p.recipe().ingredients().get(i).count()), needed == 0);
                    }
                }
            } while (changed);
        }
        private void commit() {
            try (var physical = registry.storage().prepareOwnership(transfers, reductions)) {
                registry.beforeMutation(); physical.commit();
                surplus.values().forEach(SupplyRegistry.this::putDemand); leases.putAll(admitted); admitted.clear();
                publishShares(staged); states.forEach((id, value) -> demand(id).replace(value)); orders.values().forEach(SupplyRegistry.this::replaceProduction);
                for (UUID id : returning) { var order = delivery(id); deliveries.put(id, delivery(order, order.transferred(), DeliveryOrder.State.RETURNING)); }
                blockUnreferencedDeliveries(); recomputePriorities();
            }
        }
        @Override public void close() { admitted.values().forEach(AdmissionLedger.Lease::close); admitted.clear(); }
    }
    private void blockUnreferencedDeliveries() {
        for (DeliveryOrder order : List.copyOf(deliveries.values())) if (!order.terminal() && !hasCargo(order.id())
                && orderShares(order.id()).stream().noneMatch(CoverageShare::covered))
            deliveries.put(order.id(), delivery(order, order.transferred(), demand(order.ownerDemandId()).snapshot().status() == Demand.Status.CANCELLED ? DeliveryOrder.State.CANCELLED : DeliveryOrder.State.LOST));
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
                for (CoverageShare share : shares.values()) if ((order.id().equals(share.sourceOrderId()) || order.id().equals(share.productionOrderId())) && share.stage() != CoverageShare.Stage.FULFILLED) {
                    var d = demand(share.demandId()).snapshot(); if (d.status() == Demand.Status.CANCELLED) continue;
                    if (!found || laneRank(d.lane()) > laneRank(lane)) lane = d.lane(); priority = Math.max(priority, d.priority()); found = true;
                }
                if (!found) priority = 0;
                if (lane != order.lane() || priority != order.priority()) {
                    replaceProduction(order.update(order.batches(), order.remainingActiveTicks(), order.completedBatches(), order.batchStarted(), order.workId(), order.citizenId(), order.state(), lane, priority)); changed = true;
                }
                if(order.workId()!=null) { var work=registry.workBoard().work(order.workId()); int inherited=Math.max(0,Math.min(10,priority)); if(!work.terminal() && work.priority()!=inherited) registry.workBoard().priority(work.id(),inherited); }
                for (Demand ingredient : demands.values()) {
                    var s = ingredient.snapshot(); if (!s.ownerId().equals(order.id()) || s.status() == Demand.Status.CANCELLED) continue;
                    if (s.lane() != lane || s.priority() != priority) {
                        ingredient.replace(new Demand.Snapshot(s.id(), s.colonyId(), s.ownerId(), s.matcher(), s.goalKind(), s.destination(), s.required(), s.fulfilled(), s.allocated(), s.covered(), s.deliveredTotal(), Math.addExact(s.revision(), 1), lane, priority, s.createdTick(), s.status(), s.sourceStorages())); changed = true;
                    }
                }
            }
        } while (changed && ++rounds <= productions.size());
        for (DeliveryOrder order : List.copyOf(deliveries.values())) {
            var s = demand(order.ownerDemandId()).snapshot();
            if (order.lane() != s.lane() || order.priority() != s.priority()) updateDelivery(new DeliveryOrder(order.id(), order.colonyId(), order.ownerDemandId(), order.source(), order.destination(), order.item(), order.quantity(), order.transferred(), Math.addExact(order.revision(), 1), order.citizenId(), order.workId(), order.state(), s.lane(), s.priority()));
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
            if (p != null) { if (!p.colonyId().equals(share.colonyId()) || !p.recipe().output().equals(share.item())) throw new IllegalArgumentException("Invalid pinned output share"); if (share.stage() == CoverageShare.Stage.PROMISED_OUTPUT) output.merge(p.id(), share.quantity(), Math::addExact); }
            if (share.productionOrderId() != null) {
                var producer = ps.get(share.productionOrderId());
                if (producer == null || !producer.colonyId().equals(share.colonyId()) || !producer.recipe().output().equals(share.item())
                        || share.stage() == CoverageShare.Stage.PROMISED_OUTPUT && !producer.id().equals(share.sourceOrderId()))
                    throw new IllegalArgumentException("Invalid recorded production provenance");
            }
            boolean returningSurplus = l != null && l.returnRequired() && d.goalKind() == Demand.GoalKind.DELIVERY
                    && d.id().equals(d.ownerId()) && share.stage() == CoverageShare.Stage.IN_TRANSIT && share.physicallyProduced();
            if (l != null && (!l.colonyId().equals(share.colonyId()) || !l.ownerDemandId().equals(d.id()) && !returningSurplus || !l.item().equals(share.item()))) throw new IllegalArgumentException("Invalid delivery share");
            if (share.stage() == CoverageShare.Stage.PROMISED_OUTPUT && p == null || share.stage() == CoverageShare.Stage.IN_TRANSIT && (l == null || l.terminal())) throw new IllegalArgumentException("Share/order stage mismatch");
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
            if (f != d.fulfilled() || a != d.allocated() || c != d.covered() || d.goalKind() == Demand.GoalKind.DELIVERY && d.deliveredTotal() < f) throw new IllegalArgumentException("Saved share arithmetic mismatch");
        }
        validatePins(List.of(), ps.values().stream().map(ProductionOrder::recipe).toList(), null);
        for (ProductionOrder order : ps.values()) {
            for (int i = 0; i < order.recipe().ingredients().size(); i++) {
                UUID id = UUID.nameUUIDFromBytes((order.id() + ":ingredient:" + i).getBytes(StandardCharsets.UTF_8));
                var child = ds.get(id); var ingredient = order.recipe().ingredients().get(i);
                if (child == null || !child.ownerId().equals(order.id()) || !child.colonyId().equals(order.colonyId()) || !child.matcher().equals(ingredient.matcher())
                        || child.goalKind() != Demand.GoalKind.CONSUMPTION || child.required() != Math.multiplyExact(order.batches() + order.completedBatches(), ingredient.count())
                        || child.fulfilled() != Math.multiplyExact(order.completedBatches(), ingredient.count())
                        || !order.terminal() && child.status() == Demand.Status.CANCELLED) throw new IllegalArgumentException("Missing/invalid production ingredient goal");
                if (order.pinned()) {
                    var workshop = storage.workshops().stream().filter(w -> w.id().equals(order.workshopId())).findFirst().orElse(null);
                    if (workshop != null) {
                        var registration = storage.registrations().stream().filter(r -> r.id().equals(workshop.registrationId())).findFirst().orElse(null);
                        if (registration != null && registration.storages().contains(order.workshopStorage()) && !child.destination().equals(registration.positions().get(registration.storages().indexOf(order.workshopStorage())))) throw new IllegalArgumentException("Ingredient destination differs from pinned barrel");
                    }
                }
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
            for (var s : staged.values()) {
                Lane lane = s.obligationId() != null && as.containsKey(s.obligationId()) ? as.get(s.obligationId()).lane()
                        : s.obligationId() != null && rs.containsKey(s.obligationId()) ? rs.get(s.obligationId()).lane() : ds.get(s.demandId()).lane();
                prepared.admitted.put(s.id(), replacement.reserve(s.colonyId(), lane, Map.of(Resource.COVERAGE_SHARES, 1)));
            }
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
            demands.clear(); planning.values().forEach(Set::clear); foodDemands.clear(); ownerDemands.clear(); planningSequence = 0;
            snapshot.demands().forEach(s -> putDemand(new Demand(s)));
            productions.clear(); productionIds.clear(); productionOffsets.clear(); productionWorks.clear(); demandOrders.clear(); productionIndexRevision = 0; terminalProductionCount = 0;
            snapshot.productionOrders().forEach(SupplyRegistry.this::addProduction);
            clearShares(); snapshot.shares().forEach(s -> putShare(s.id(), s)); dirtyObligations.clear();
            deliveries.clear(); deliveryIds.values().forEach(List::clear); deliveryOffsets.clear(); deliveryWorks.clear(); snapshot.deliveries().forEach(SupplyRegistry.this::addDelivery); snapshot = null;
        }
        @Override public void close() { registry.requireOwner(); if (snapshot != null) { admitted.values().forEach(AdmissionLedger.Lease::close); admitted.clear(); snapshot = null; } }
    }
}
