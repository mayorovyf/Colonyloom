package io.github.kpuctajluk.colonyloom.core.storage;

import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Owner-thread canonical stock, local registration views, and shared bounded promises. */
public final class StorageRegistry {
    public static final int MAX_REGISTRATIONS = 512, MAX_WORKSHOPS = 512, MAX_SLOTS = 8192,
            MAX_OBLIGATIONS = 8192, MAX_RETIRED_IDENTITIES = 8192;
    public static final long MAX_COUNT = 1_000_000;
    private static final Set<String> ROLES = Set.of("warehouse", "workshop", "construction", "return");

    public record Registration(UUID id, UUID colonyId, WorldPosition address, String role,
                               List<StorageId> storages, List<StockRegion> slots, long revision,
                               List<WorldPosition> positions) {
        public Registration {
            Objects.requireNonNull(id); Objects.requireNonNull(colonyId); Objects.requireNonNull(address);
            if (!ROLES.contains(role) || revision < 0) throw new IllegalArgumentException("Invalid storage role/revision");
            storages = List.copyOf(storages); slots = List.copyOf(slots); positions = List.copyOf(positions);
            if (storages.isEmpty() || storages.size() > 2 || slots.isEmpty() || slots.size() > 54
                    || positions.size() != storages.size() || new HashSet<>(storages).size() != storages.size()
                    || new HashSet<>(slots).size() != slots.size()) throw new IllegalArgumentException("Invalid storage mapping envelope");
            Set<StorageId> used = new HashSet<>();
            for (int i = 0; i < storages.size(); i++) {
                if (!storages.get(i).dimension().equals(address.dimension())
                        || !positions.get(i).dimension().equals(address.dimension())) throw new IllegalArgumentException("Cross-dimensional storage mapping");
            }
            for (StockRegion slot : slots) {
                if (!storages.contains(slot.storage())) throw new IllegalArgumentException("Slot outside storage mapping");
                used.add(slot.storage());
            }
            if (used.size() != storages.size()) throw new IllegalArgumentException("Storage mapping without slots");
        }
    }

    public record Workshop(UUID id, UUID colonyId, WorldPosition position, UUID registrationId, long revision) {
        public Workshop {
            Objects.requireNonNull(id); Objects.requireNonNull(colonyId); Objects.requireNonNull(position); Objects.requireNonNull(registrationId);
            if (revision < 0) throw new IllegalArgumentException("Negative workshop revision");
        }
    }
    public record RetiredIdentity(StorageId storage, UUID colonyId) {
        public RetiredIdentity { Objects.requireNonNull(storage); Objects.requireNonNull(colonyId); }
    }

    static final class Claim {
        final boolean allocation;
        final UUID id, colony, owner;
        final StockRegion slot;
        final ItemDescriptor item;
        long count, revision;
        final Lane lane;
        final AdmissionLedger.Lease lease;
        Claim(boolean allocation, UUID id, UUID colony, UUID owner, StockRegion slot, ItemDescriptor item,
              long count, long revision, Lane lane, AdmissionLedger.Lease lease) {
            this.allocation = allocation; this.id = id; this.colony = colony; this.owner = owner;
            this.slot = slot; this.item = item; this.count = count; this.revision = revision; this.lane = Objects.requireNonNull(lane); this.lease = lease;
        }
        ReservationLedger.Entry reservation() { return new ReservationLedger.Entry(id, colony, owner, slot, item, count, revision, lane); }
        AllocationLedger.Entry allocation() { return new AllocationLedger.Entry(id, colony, owner, slot, item, count, revision, lane); }
        boolean matches(UUID colony, UUID owner, StockRegion slot, ItemDescriptor item, long count) {
            return this.colony.equals(colony) && this.owner.equals(owner) && this.slot.equals(slot) && this.item.equals(item) && this.count == count;
        }
    }
    private final ColonyRegistry registry;
    private final StockIndex index;
    private final ReservationLedger reservations;
    private final AllocationLedger allocations;
    private final Map<UUID, Registration> registrations = new LinkedHashMap<>();
    private final Map<UUID, Workshop> workshops = new LinkedHashMap<>();
    private final java.util.NavigableMap<Long, UUID> registrationViewKeys = new java.util.TreeMap<>();
    private final java.util.NavigableMap<Long, UUID> workshopViewKeys = new java.util.TreeMap<>();
    private long registrationViewSequence, workshopViewSequence;
    private final Map<StorageId, RetiredIdentity> retired = new LinkedHashMap<>();
    private final Map<UUID, AdmissionLedger.Lease> registrationLeases = new LinkedHashMap<>(), workshopLeases = new LinkedHashMap<>();
    private final Map<StorageId, AdmissionLedger.Lease> retiredLeases = new LinkedHashMap<>();
    private final Map<StockRegion, AdmissionLedger.Lease> slotLeases = new LinkedHashMap<>();
    private final Map<UUID, Claim> claims = new LinkedHashMap<>();
    private final Map<StockRegion, List<Claim>> slotClaims = new HashMap<>();
    private final Map<UUID, Set<StockRegion>> views = new HashMap<>();
    private java.util.function.Consumer<UUID> lossListener = ignored -> {};
    public void setLossListener(java.util.function.Consumer<UUID> listener) { requireOwner(); lossListener = Objects.requireNonNull(listener); }

    public StorageRegistry(ColonyRegistry registry) {
        this.registry = Objects.requireNonNull(registry); index = new StockIndex(this);
        reservations = new ReservationLedger(this); allocations = new AllocationLedger(this);
    }
    void requireOwner() { registry.requireOwner(); }
    public StockIndex index() { requireOwner(); return index; }
    public ReservationLedger reservations() { requireOwner(); return reservations; }
    public AllocationLedger allocations() { requireOwner(); return allocations; }
    public List<Registration> registrations() { requireOwner(); return List.copyOf(registrations.values()); }
    public List<Registration> registrations(UUID colonyId) { registry.colony(colonyId); return registrations.values().stream().filter(r -> r.colonyId().equals(colonyId)).toList(); }
    public List<Workshop> workshops() { requireOwner(); return List.copyOf(workshops.values()); }
    public long registrationViewCutoff() { requireOwner(); return registrationViewSequence; }
    public Long nextRegistrationViewKey(Long after) { requireOwner(); return after == null ? registrationViewKeys.isEmpty() ? null : registrationViewKeys.firstKey() : registrationViewKeys.higherKey(after); }
    public Registration registrationAtViewKey(Long key) { requireOwner(); return registrations.get(registrationViewKeys.get(key)); }
    public long workshopViewCutoff() { requireOwner(); return workshopViewSequence; }
    public Long nextWorkshopViewKey(Long after) { requireOwner(); return after == null ? workshopViewKeys.isEmpty() ? null : workshopViewKeys.firstKey() : workshopViewKeys.higherKey(after); }
    public Workshop workshopAtViewKey(Long key) { requireOwner(); return workshops.get(workshopViewKeys.get(key)); }
    public boolean isRetired(StorageId storage) { requireOwner(); return retired.containsKey(storage); }
    public boolean usedId(UUID id) { requireOwner(); return registrations.containsKey(id) || workshops.containsKey(id) || claims.containsKey(id); }

    public Registration register(UUID colonyId, WorldPosition address, String role, List<StorageId> storages,
                                 List<StockRegion> slots, List<WorldPosition> positions) {
        registry.colony(colonyId);
        Registration previous = registrations.values().stream().filter(r -> r.colonyId().equals(colonyId) && r.address().equals(address)).findFirst().orElse(null);
        Registration next = new Registration(previous == null ? freshId() : previous.id(), colonyId, address, role,
                storages, slots, previous == null ? 0 : Math.addExact(previous.revision(), 1), positions);
        if (next.storages().stream().anyMatch(retired::containsKey)) throw new IllegalArgumentException("Retired storage identity");
        if (previous != null && previous.role().equals(role) && previous.storages().equals(next.storages())
                && previous.slots().equals(next.slots()) && previous.positions().equals(next.positions())) return previous;
        if (!role.equals("workshop") && previous != null && workshops.values().stream().anyMatch(w -> w.registrationId().equals(previous.id())))
            throw new IllegalArgumentException("Registration is referenced by a workshop");
        if (previous == null && registrations.size() >= MAX_REGISTRATIONS) throw new IllegalArgumentException("Storage registration envelope exceeded");
        List<StockRegion> additions = next.slots().stream().filter(s -> !slotLeases.containsKey(s)).toList();
        if (slotLeases.size() + additions.size() > MAX_SLOTS) throw new IllegalArgumentException("Canonical slot envelope exceeded");
        Map<StockRegion, AdmissionLedger.Lease> admitted = new LinkedHashMap<>();
        AdmissionLedger.Lease registrationLease = null;
        Lane lane = next.storages().stream().anyMatch(id -> id.bindingEpoch() > 0) ? Lane.CRITICAL : Lane.NORMAL;
        try {
            if (previous == null) registrationLease = registry.admission().reserve(colonyId, lane, Map.of(Resource.PHYSICAL_TARGETS, 1));
            for (StockRegion slot : additions) admitted.put(slot, registry.admission().reserve(colonyId, lane, Map.of(Resource.STORAGE_SLOTS, 1)));
            registry.beforeMutation();
        } catch (RuntimeException failure) {
            if (registrationLease != null) registrationLease.close();
            admitted.values().forEach(AdmissionLedger.Lease::close); throw failure;
        }
        registrations.put(next.id(), next);
        if (previous == null) registrationViewKeys.put(++registrationViewSequence, next.id());
        if (registrationLease != null) registrationLeases.put(next.id(), registrationLease);
        slotLeases.putAll(admitted); additions.forEach(index::add);
        rebuildViews();
        if (previous != null) for (StockRegion slot : previous.slots()) if (!next.slots().contains(slot)) {
            if (!registered(slot)) index.unknown(slot);
            prune(slot);
        }
        return next;
    }

    public Workshop registerWorkshop(UUID colony, WorldPosition table, UUID registration) {
        if (!registry.colony(colony).territory().contains(table)) throw new IllegalArgumentException("Workshop outside colony territory");
        Registration target = registrations.get(registration);
        if (target == null || !target.colonyId().equals(colony) || !target.role().equals("workshop")) throw new IllegalArgumentException("Workshop storage reference invalid");
        Workshop previous = workshops.values().stream().filter(w -> w.colonyId().equals(colony) && w.position().equals(table)).findFirst().orElse(null);
        if (previous != null && previous.registrationId().equals(registration)) return previous;
        if (previous == null && workshops.size() >= MAX_WORKSHOPS) throw new IllegalArgumentException("Workshop envelope exceeded");
        Workshop next = new Workshop(previous == null ? freshId() : previous.id(), colony, table, registration,
                previous == null ? 0 : Math.addExact(previous.revision(), 1));
        AdmissionLedger.Lease lease = previous == null ? registry.admission().reserve(colony, Lane.NORMAL, Map.of(Resource.PHYSICAL_TARGETS, 1)) : null;
        try { registry.beforeMutation(); } catch (RuntimeException failure) { if (lease != null) lease.close(); throw failure; }
        workshops.put(next.id(), next); if (lease != null) workshopLeases.put(next.id(), lease);
        if (previous == null) workshopViewKeys.put(++workshopViewSequence, next.id());
        return next;
    }

    public void retire(StorageId storage, UUID colony) {
        registry.colony(colony); Objects.requireNonNull(storage);
        if (retired.containsKey(storage)) return;
        if (retired.size() >= MAX_RETIRED_IDENTITIES) throw new IllegalArgumentException("Retired identity envelope exceeded");
        AdmissionLedger.Lease lease = registry.admission().reserve(colony, Lane.NORMAL, Map.of(Resource.EVIDENCE, 1));
        try { registry.beforeMutation(); } catch (RuntimeException failure) { lease.close(); throw failure; }
        retired.put(storage, new RetiredIdentity(storage, colony)); retiredLeases.put(storage, lease);
        for (StockRegion slot : slotLeases.keySet()) if (slot.storage().equals(storage)) index.unknown(slot);
    }

    static void validateClaim(UUID id, UUID colony, UUID owner, StockRegion slot, ItemDescriptor item, long count, long revision) {
        Objects.requireNonNull(id); Objects.requireNonNull(colony); Objects.requireNonNull(owner); Objects.requireNonNull(slot); Objects.requireNonNull(item);
        if (count <= 0 || count > MAX_COUNT || revision < 0) throw new IllegalArgumentException("Invalid stock obligation quantity/revision");
    }
    Claim claim(boolean allocation, UUID id, UUID colony, UUID owner, StockRegion slot, ItemDescriptor item, long count, long tick, Lane lane) {
        requireOwner(); validateClaim(id, colony, owner, slot, item, count, 0); Objects.requireNonNull(lane);
        if (tick < 0) throw new IllegalArgumentException("Negative stock claim tick");
        Claim previous = claims.get(id);
        if (previous != null) {
            if (previous.allocation == allocation && previous.lane == lane && previous.matches(colony, owner, slot, item, count)) return previous;
            throw new IllegalArgumentException("Obligation identity already used");
        }
        registry.colony(colony);
        if (!authorized(colony, slot) || retired.containsKey(slot.storage())) throw new IllegalArgumentException("Slot outside current colony storage view");
        if (registry.usedId(id) || registrations.containsKey(id) || workshops.containsKey(id)) throw new IllegalArgumentException("Obligation identity already used");
        StockIndex.Observation observation = index.observation(slot);
        if (!item.equals(observation.item()) || index.free(slot, tick) < count) throw new IllegalStateException("Insufficient known free canonical stock");
        if (claims.size() >= MAX_OBLIGATIONS) throw new IllegalArgumentException("Stock obligation envelope exceeded");
        AdmissionLedger.Lease lease = registry.admission().reserve(colony, lane, Map.of(Resource.RESERVATIONS_AND_ALLOCATIONS, 1));
        try { registry.beforeMutation(); } catch (RuntimeException failure) { lease.close(); throw failure; }
        Claim next = new Claim(allocation, id, colony, owner, slot, item, count, 0, lane, lease);
        addClaim(next); return next;
    }
    /** Whole-kit admission and publication: no partial reservation survives any failure. */
    public List<ReservationLedger.Entry> reserveAll(List<ReservationLedger.Entry> requested, long tick) {
        requireOwner(); if (tick < 0 || requested.isEmpty() || requested.size() > 16) throw new IllegalArgumentException("Invalid kit envelope");
        if (claims.size() + requested.size() > MAX_OBLIGATIONS) throw new IllegalArgumentException("Stock obligation envelope exceeded");
        Set<UUID> ids = new HashSet<>(); Map<StockRegion, Long> totals = new HashMap<>();
        for (ReservationLedger.Entry entry : requested) {
            registry.colony(entry.colonyId());
            if (!ids.add(entry.id()) || registry.usedId(entry.id()) || claims.containsKey(entry.id())
                    || !authorized(entry.colonyId(), entry.slot()) || retired.containsKey(entry.slot().storage())) throw new IllegalArgumentException("Invalid kit obligation identity/view");
            if (!entry.item().equals(index.observation(entry.slot()).item())) throw new IllegalStateException("Kit item changed");
            long total = Math.addExact(totals.getOrDefault(entry.slot(), 0L), entry.count());
            if (total > index.free(entry.slot(), tick)) throw new IllegalStateException("Insufficient complete kit stock");
            totals.put(entry.slot(), total);
        }
        List<Claim> staged = new ArrayList<>(requested.size());
        try {
            for (ReservationLedger.Entry entry : requested) staged.add(new Claim(false, entry.id(), entry.colonyId(), entry.ownerId(), entry.slot(), entry.item(), entry.count(), 0, entry.lane(),
                    registry.admission().reserve(entry.colonyId(), entry.lane(), Map.of(Resource.RESERVATIONS_AND_ALLOCATIONS, 1))));
            registry.beforeMutation();
        } catch (RuntimeException failure) { staged.forEach(c -> c.lease.close()); throw failure; }
        staged.forEach(this::addClaim); return staged.stream().map(Claim::reservation).toList();
    }
    /** Admits future physical output without advertising stock before native observation. */
    public PreparedReservations prepareReserveAll(List<ReservationLedger.Entry> requested,long tick) {
        requireOwner(); requested=List.copyOf(requested);
        if(tick<0||requested.size()>16||claims.size()+requested.size()>MAX_OBLIGATIONS)throw new IllegalArgumentException("Invalid output reservation envelope");
        var ids=new HashSet<UUID>();var staged=new ArrayList<Claim>(requested.size());
        try {
            for(var entry:requested) {
                registry.colony(entry.colonyId());
                if(!ids.add(entry.id())||registry.usedId(entry.id())||claims.containsKey(entry.id())
                        ||registrations.containsKey(entry.id())||workshops.containsKey(entry.id())
                        ||!authorized(entry.colonyId(),entry.slot())||retired.containsKey(entry.slot().storage()))throw new IllegalArgumentException("Invalid future output obligation");
                staged.add(new Claim(false,entry.id(),entry.colonyId(),entry.ownerId(),entry.slot(),entry.item(),entry.count(),entry.revision(),entry.lane(),
                        registry.admission().reserve(entry.colonyId(),entry.lane(),Map.of(Resource.RESERVATIONS_AND_ALLOCATIONS,1))));
            }
        } catch(RuntimeException failure) {staged.forEach(c -> c.lease.close());throw failure;}
        return new PreparedReservations(staged,tick);
    }
    public final class PreparedReservations implements AutoCloseable {
        private final List<Claim> staged;
        private final long tick;
        private boolean closed;
        private PreparedReservations(List<Claim> staged,long tick) {this.staged=staged;this.tick=tick;}
        public void commit() {
            requireOwner();if(closed)throw new IllegalStateException("Output preparation closed");
            if(claims.size()+staged.size()>MAX_OBLIGATIONS)throw new IllegalStateException("Output obligation envelope changed");
            var totals=new HashMap<StockRegion,Long>();
            for(var c:staged) {
                if(claims.containsKey(c.id)||!authorized(c.colony,c.slot)||retired.containsKey(c.slot.storage())
                        ||!c.item.equals(index.observation(c.slot).item()))throw new IllegalStateException("Native output identity changed");
                long total=Math.addExact(totals.getOrDefault(c.slot,0L),c.count);
                if(total>index.free(c.slot,tick))throw new IllegalStateException("Native output not observed");
                totals.put(c.slot,total);
            }
            if(!staged.isEmpty())registry.beforeMutation();
            staged.forEach(StorageRegistry.this::addClaim);closed=true;
        }
        @Override public void close() {requireOwner();if(!closed){closed=true;staged.forEach(c -> c.lease.close());}}
    }
    public ReservationLedger.Entry reservation(UUID id) { requireOwner(); Claim c = claims.get(id); return c == null || c.allocation ? null : c.reservation(); }
    public AllocationLedger.Entry allocation(UUID id) { requireOwner(); Claim c = claims.get(id); return c == null || !c.allocation ? null : c.allocation(); }
    /** Only reduces a real obligation; zero releases its lease. */
    public void reduceObligation(UUID id, long retained) {
        requireOwner(); Claim c = claims.get(id);
        if (c == null || retained < 0 || retained > c.count) throw new IllegalArgumentException("Invalid retained obligation");
        if (retained == c.count) return; long revision = Math.addExact(c.revision, 1); registry.beforeMutation();
        if (retained == 0) { removeClaim(c); prune(c.slot); } else { c.count = retained; c.revision = revision; }
    }
    /** A physical transfer can replace a reservation with an allocation without double admission. */
    public AllocationLedger.Entry allocateReserved(UUID id) {
        requireOwner(); Claim c = claims.get(id);
        if (c == null || c.allocation) throw new IllegalArgumentException("Unknown reservation");
        Claim next = new Claim(true, c.id, c.colony, c.owner, c.slot, c.item, c.count, Math.addExact(c.revision, 1), c.lane, c.lease);
        registry.beforeMutation(); claims.put(id, next); List<Claim> local = slotClaims.get(c.slot); local.set(local.indexOf(c), next); return next.allocation();
    }
    /** Validates/admit replacements before releasing old physical obligations. */
    public void replaceObligations(List<UUID> removals, List<ReservationLedger.Entry> reservations,
                                   List<AllocationLedger.Entry> allocations, long tick) {
        requireOwner(); if (tick < 0 || removals.size() > 16 || reservations.size() + allocations.size() > 16) throw new IllegalArgumentException("Replacement envelope exceeded");
        Set<UUID> removed = new HashSet<>(removals), ids = new HashSet<>();
        if (removed.size() != removals.size()) throw new IllegalArgumentException("Duplicate removed obligation");
        Map<StockRegion, Long> released = new HashMap<>(), totals = new HashMap<>();
        for (UUID id : removals) { Claim c = claims.get(id); if (c == null) throw new IllegalArgumentException("Missing replaced obligation"); released.merge(c.slot, c.count, Math::addExact); }
        List<Claim> staged = new ArrayList<>();
        try {
            for (int i = 0; i < reservations.size() + allocations.size(); i++) {
                boolean allocation = i >= reservations.size();
                ReservationLedger.Entry r = allocation ? null : reservations.get(i);
                AllocationLedger.Entry a = allocation ? allocations.get(i - reservations.size()) : null;
                UUID id = allocation ? a.id() : r.id(), colony = allocation ? a.colonyId() : r.colonyId(), owner = allocation ? a.ownerId() : r.ownerId();
                StockRegion slot = allocation ? a.slot() : r.slot(); ItemDescriptor item = allocation ? a.item() : r.item();
                long count = allocation ? a.count() : r.count(), revision = allocation ? a.revision() : r.revision(); Lane lane = allocation ? a.lane() : r.lane();
                registry.colony(colony);
                if (!ids.add(id) || registry.usedId(id) && !removed.contains(id) || claims.containsKey(id) && !removed.contains(id)
                        || !authorized(colony, slot) || retired.containsKey(slot.storage()) || !item.equals(index.observation(slot).item())) throw new IllegalArgumentException("Invalid replacement obligation");
                long total = Math.addExact(totals.getOrDefault(slot, 0L), count);
                if (total > index.free(slot, tick) + released.getOrDefault(slot, 0L) || !index.observation(slot).ready()) throw new IllegalStateException("Insufficient observed replacement stock");
                totals.put(slot, total);
                Claim previous = claims.get(id);
                AdmissionLedger.Lease lease = previous != null && previous.colony.equals(colony) && previous.lane == lane ? previous.lease
                        : registry.admission().reserve(colony, lane, Map.of(Resource.RESERVATIONS_AND_ALLOCATIONS, 1));
                staged.add(new Claim(allocation, id, colony, owner, slot, item, count, revision, lane, lease));
            }
            if (claims.size() - removals.size() + staged.size() > MAX_OBLIGATIONS) throw new IllegalArgumentException("Obligation envelope exceeded");
            registry.beforeMutation();
        } catch (RuntimeException failure) {
            for (Claim c : staged) { Claim old = claims.get(c.id); if (old == null || old.lease != c.lease) c.lease.close(); }
            throw failure;
        }
        for (UUID id : removals) {
            Claim old = claims.remove(id); List<Claim> local = slotClaims.get(old.slot); local.remove(old); if (local.isEmpty()) slotClaims.remove(old.slot);
            boolean reused = false; for (Claim c : staged) if (c.lease == old.lease) { reused = true; break; }
            if (!reused) old.lease.close();
        }
        staged.forEach(this::addClaim);
        for (StockRegion slot : released.keySet()) prune(slot);
    }
    /** Pre-admits a possible split; native destination observation precedes publication. */
    public PreparedMove prepareMoveReservation(UUID id, StockRegion destination, UUID splitId,
                                               boolean allocation, boolean retainDestination, int maximum, long tick) {
        return prepareMove(id,destination,splitId,allocation,retainDestination,maximum,tick,false);
    }
    /** Allocation custody changes location, never becomes free stock during the native transfer. */
    public PreparedMove prepareMoveAllocation(UUID id,StockRegion destination,UUID splitId,
            boolean allocation,boolean retainDestination,int maximum,long tick) {
        if(!allocation||!retainDestination)throw new IllegalArgumentException("Allocation relocation must retain allocation");
        return prepareMove(id,destination,splitId,true,true,maximum,tick,true);
    }
    private PreparedMove prepareMove(UUID id,StockRegion destination,UUID splitId,
            boolean allocation,boolean retainDestination,int maximum,long tick,boolean sourceAllocation) {
        requireOwner(); Claim old = claims.get(id);
        if (old == null || old.allocation != sourceAllocation || maximum <= 0 || maximum > old.count || tick < 0
                || old.slot.equals(destination) || !authorized(old.colony, old.slot) || retired.containsKey(old.slot.storage())
                || !authorized(old.colony, destination) || retired.containsKey(destination.storage())
                || !index.observation(old.slot).ready() || !old.item.equals(index.observation(old.slot).item())
                || !index.observation(destination).ready()) throw new IllegalStateException("Transfer authority or readiness changed");
        if (splitId.equals(id) || claims.containsKey(splitId) || registry.usedId(splitId)) throw new IllegalArgumentException("Split identity already used");
        AdmissionLedger.Lease extra = null;
        Lane splitLane = old.lane;
        if (old.slot.storage().bindingEpoch() > 0) {
            try { var citizen = registry.citizen(old.slot.storage().identity());
                if (citizen.assignedWorkId() != null && registry.workBoard().work(citizen.assignedWorkId()).criticalService()) splitLane = Lane.CRITICAL;
            } catch (IllegalArgumentException absent) { /* A retired identity retains its original accounting. */ }
        }
        if (retainDestination && old.count > 1) {
            if (claims.size() >= MAX_OBLIGATIONS) throw new IllegalArgumentException("Obligation envelope exceeded");
            extra = registry.admission().reserve(old.colony, splitLane, Map.of(Resource.RESERVATIONS_AND_ALLOCATIONS, 1));
        }
        return new PreparedMove(old, destination, splitId, allocation, retainDestination, maximum, tick, extra, splitLane);
    }
    public final class PreparedMove implements AutoCloseable {
        private final Claim old;
        private final StockRegion destination;
        private final UUID splitId;
        private final boolean allocation, retainDestination;
        private final int maximum;
        private final long tick, revision, count;
        private AdmissionLedger.Lease extra;
        private final Lane splitLane;
        private boolean closed;
        private PreparedMove(Claim old, StockRegion destination, UUID splitId, boolean allocation,
                             boolean retainDestination, int maximum, long tick, AdmissionLedger.Lease extra, Lane splitLane) {
            this.old = old; this.destination = destination; this.splitId = splitId; this.allocation = allocation;
            this.retainDestination = retainDestination; this.maximum = maximum; this.tick = tick; this.extra = extra;
            revision = old.revision; count = old.count;
            this.splitLane = splitLane;
        }
        public UUID destinationObligation(int moved) { return moved == count ? old.id : splitId; }
        public void commit(int moved) {
            requireOwner();
            if (closed || moved <= 0 || moved > maximum || claims.get(old.id) != old || old.revision != revision || old.count != count)
                throw new IllegalStateException("Prepared transfer changed");
            if (!authorized(old.colony, destination) || retired.containsKey(destination.storage())
                    || !index.observation(destination).ready() || !old.item.equals(index.observation(destination).item())
                    || index.free(destination, tick) < moved) throw new IllegalStateException("Native destination not observed");
            long nextRevision = Math.addExact(revision, 1);
            Claim next = retainDestination ? new Claim(allocation, destinationObligation(moved), old.colony, old.owner,
                    destination, old.item, moved, nextRevision, moved == count ? old.lane : splitLane, moved == count ? old.lease : extra) : null;
            registry.beforeMutation();
            if (moved == count) {
                claims.remove(old.id); List<Claim> local = slotClaims.get(old.slot); local.remove(old);
                if (local.isEmpty()) slotClaims.remove(old.slot);
                if (next == null) old.lease.close();
            } else { old.count = count - moved; old.revision = nextRevision; }
            if (next != null) { addClaim(next); if (moved != count) extra = null; }
            prune(old.slot); closed = true;
            if (extra != null) { extra.close(); extra = null; }
        }
        @Override public void close() { requireOwner(); if (!closed) { closed = true; if (extra != null) { extra.close(); extra = null; } } }
    }
    /** Reassigns real property without making it temporarily free or requiring a ready observation. */
    public record OwnershipTransfer(UUID obligationId, UUID resultId, UUID ownerId, long count) {}
    public PreparedOwnership prepareOwnership(List<OwnershipTransfer> transfers, Map<UUID, Long> retained) {
        requireOwner(); transfers = List.copyOf(transfers); retained = Map.copyOf(retained);
        var originals = new LinkedHashMap<UUID, Claim>(); var extras = new LinkedHashMap<UUID, AdmissionLedger.Lease>();
        Set<UUID> results = new HashSet<>(); Map<UUID, Long> remaining = new HashMap<>();
        try {
            for (var transfer : transfers) {
                Claim old = claims.get(transfer.obligationId());
                long available = old == null ? 0 : remaining.getOrDefault(old.id, old.count);
                if (old == null || retained.containsKey(old.id) || transfer.ownerId() == null || transfer.count() <= 0 || transfer.count() > available
                        || !results.add(transfer.resultId()) || (transfer.count() == available) != transfer.resultId().equals(old.id))
                    throw new IllegalArgumentException("Invalid property ownership transfer");
                originals.putIfAbsent(old.id, old); remaining.put(old.id, available - transfer.count());
                Math.incrementExact(old.revision);
                if (transfer.count() < available) {
                    if (claims.containsKey(transfer.resultId()) || registry.usedId(transfer.resultId()) || claims.size() + extras.size() >= MAX_OBLIGATIONS)
                        throw new IllegalArgumentException("Ownership split identity/envelope exceeded");
                    extras.put(transfer.resultId(), registry.admission().reserve(old.colony, old.lane, Map.of(Resource.RESERVATIONS_AND_ALLOCATIONS, 1)));
                }
            }
            for (var entry : retained.entrySet()) {
                Claim old = claims.get(entry.getKey());
                if (old == null || entry.getValue() < 0 || entry.getValue() > old.count) throw new IllegalArgumentException("Invalid obligation reduction");
                Math.incrementExact(old.revision); originals.put(old.id, old);
            }
            return new PreparedOwnership(transfers, retained, originals, extras);
        } catch (RuntimeException failure) { extras.values().forEach(AdmissionLedger.Lease::close); throw failure; }
    }
    public final class PreparedOwnership implements AutoCloseable {
        private final List<OwnershipTransfer> transfers; private final Map<UUID, Long> retained;
        private final Map<UUID, Claim> originals; private final Map<UUID, Long> counts = new HashMap<>(), revisions = new HashMap<>();
        private final Map<UUID, AdmissionLedger.Lease> extras; private boolean closed;
        private PreparedOwnership(List<OwnershipTransfer> transfers, Map<UUID, Long> retained, Map<UUID, Claim> originals, Map<UUID, AdmissionLedger.Lease> extras) {
            this.transfers = transfers; this.retained = retained; this.originals = originals; this.extras = extras;
            originals.forEach((id, claim) -> { counts.put(id, claim.count); revisions.put(id, claim.revision); });
        }
        public void commit() {
            requireOwner(); if (closed) throw new IllegalStateException("Property ownership preparation closed");
            for (var old : originals.values()) if (claims.get(old.id) != old || old.count != counts.get(old.id) || old.revision != revisions.get(old.id))
                throw new IllegalStateException("Prepared property ownership changed");
            registry.beforeMutation();
            for (var transfer : transfers) {
                Claim old = originals.get(transfer.obligationId()); boolean full = transfer.count() == old.count;
                Claim next = new Claim(false, transfer.resultId(), old.colony, transfer.ownerId(), old.slot, old.item,
                        transfer.count(), old.revision + 1, old.lane, full ? old.lease : extras.remove(transfer.resultId()));
                if (full) { claims.remove(old.id); slotClaims.get(old.slot).remove(old); }
                else { old.count -= transfer.count(); old.revision++; }
                addClaim(next);
            }
            for (var entry : retained.entrySet()) {
                Claim old = originals.get(entry.getKey());
                if (entry.getValue() == 0) { removeClaim(old); prune(old.slot); }
                else if (old.count != entry.getValue()) { old.count = entry.getValue(); old.revision++; }
            }
            closed = true;
        }
        @Override public void close() { requireOwner(); closed = true; extras.values().forEach(AdmissionLedger.Lease::close); extras.clear(); }
    }

    public void reduceObligations(Map<UUID, Long> retained) {
        requireOwner();
        for (var entry : retained.entrySet()) {
            Claim c = claims.get(entry.getKey());
            if (c == null || entry.getValue() < 0 || entry.getValue() > c.count) throw new IllegalArgumentException("Invalid obligation reduction");
            Math.addExact(c.revision, 1);
        }
        if (retained.isEmpty()) return; registry.beforeMutation();
        for (var entry : retained.entrySet()) {
            Claim c = claims.get(entry.getKey());
            if (entry.getValue() == 0) { removeClaim(c); prune(c.slot); }
            else if (c.count != entry.getValue()) { c.count = entry.getValue(); c.revision++; }
        }
    }
    private void addClaim(Claim claim) {
        claims.put(claim.id, claim);
        List<Claim> local = slotClaims.computeIfAbsent(claim.slot, ignored -> new ArrayList<>());
        local.add(claim); local.sort((a, b) -> a.id.compareTo(b.id));
    }
    boolean release(boolean allocation, UUID id) {
        requireOwner(); Claim claim = claims.get(id);
        if (claim == null) return false;
        if (claim.allocation != allocation) throw new IllegalArgumentException("Obligation belongs to other ledger");
        registry.beforeMutation(); removeClaim(claim); prune(claim.slot); return true;
    }
    private void removeClaim(Claim claim) {
        claims.remove(claim.id); claim.lease.close();
        List<Claim> local = slotClaims.get(claim.slot); local.remove(claim);
        if (local.isEmpty()) slotClaims.remove(claim.slot);
    }
    List<Claim> claims(boolean allocation) { return claims.values().stream().filter(c -> c.allocation == allocation).toList(); }
    public long obligated(StockRegion slot) { requireOwner(); return promised(Objects.requireNonNull(slot)); }
    long promised(StockRegion slot) {
        List<Claim> local = slotClaims.get(slot); long total = 0;
        if (local != null) for (Claim claim : local) total += claim.count;
        return total;
    }
    void reconcile(StockRegion slot, ItemDescriptor item, long count) {
        List<Claim> local = slotClaims.get(slot);
        if (local == null) return;
        long remaining = count; boolean changed = false;
        for (Claim claim : local) {
            long retained = claim.item.equals(item) ? Math.min(claim.count, remaining) : 0;
            if (retained != claim.count) changed = true;
            remaining -= retained;
            if (retained > 0 && retained != claim.count) Math.addExact(claim.revision, 1);
        }
        if (!changed) return;
        registry.beforeMutation(); remaining = count;
        for (int i = 0; i < local.size();) {
            Claim claim = local.get(i);
            long retained = claim.item.equals(item) ? Math.min(claim.count, remaining) : 0;
            remaining -= retained;
            if (retained == 0) { removeClaim(claim); lossListener.accept(claim.id); }
            else { if (retained != claim.count) { claim.count = retained; claim.revision = Math.addExact(claim.revision, 1); lossListener.accept(claim.id); } i++; }
        }
        prune(slot);
    }
    public boolean authorized(UUID colony, StockRegion slot) { requireOwner(); Set<StockRegion> view = views.get(colony); return view != null && view.contains(slot); }
    boolean registered(StockRegion slot) { for (Set<StockRegion> view : views.values()) if (view.contains(slot)) return true; return false; }
    private void rebuildViews() {
        views.clear();
        for (Registration registration : registrations.values()) views.computeIfAbsent(registration.colonyId(), ignored -> new HashSet<>()).addAll(registration.slots());
    }
    private void prune(StockRegion slot) {
        if (!registered(slot) && !slotClaims.containsKey(slot)) {
            AdmissionLedger.Lease lease = slotLeases.remove(slot);
            if (lease != null) lease.close(); index.remove(slot);
        }
    }
    private UUID freshId() {
        UUID id;
        do { id = UUID.randomUUID(); } while (registry.usedId(id) || registrations.containsKey(id) || workshops.containsKey(id) || claims.containsKey(id));
        return id;
    }
    public StorageSnapshot snapshot() {
        requireOwner(); return new StorageSnapshot(registrations(), reservations.entries(), allocations.entries(), workshops(), List.copyOf(retired.values()));
    }

    public PreparedRestore prepareRestore(StorageSnapshot snapshot, AdmissionLedger replacement, Collection<ColonyRuntime> validatedColonies) {
        requireOwner(); Objects.requireNonNull(snapshot); Objects.requireNonNull(replacement);
        if (snapshot.registrations().size() > MAX_REGISTRATIONS || snapshot.workshops().size() > MAX_WORKSHOPS
                || (long) snapshot.reservations().size() + snapshot.allocations().size() > MAX_OBLIGATIONS
                || snapshot.retiredIdentities().size() > MAX_RETIRED_IDENTITIES) throw new IllegalArgumentException("Storage persistence envelope exceeded");
        Map<UUID, ColonyRuntime> colonies = new HashMap<>();
        for (ColonyRuntime colony : validatedColonies) if (colonies.putIfAbsent(colony.colonyId(), colony) != null) throw new IllegalArgumentException("Duplicate storage colony");
        Set<UUID> ids = new HashSet<>(); Set<String> addresses = new HashSet<>(), tables = new HashSet<>();
        Map<UUID, Registration> stagedRegistrations = new LinkedHashMap<>();
        Map<StockRegion, UUID> slotOwners = new LinkedHashMap<>();
        Map<StorageId, RetiredIdentity> stagedRetired = new LinkedHashMap<>();
        for (RetiredIdentity identity : snapshot.retiredIdentities()) {
            requireColony(colonies, identity.colonyId());
            if (stagedRetired.putIfAbsent(identity.storage(), identity) != null) throw new IllegalArgumentException("Duplicate retired storage identity");
        }
        for (Registration value : snapshot.registrations()) {
            requireColony(colonies, value.colonyId());
            if (!ids.add(value.id()) || !addresses.add(value.colonyId() + ":" + value.address())) throw new IllegalArgumentException("Duplicate storage registration identity/address");
            stagedRegistrations.put(value.id(), value);
            for (StockRegion slot : value.slots()) slotOwners.putIfAbsent(slot, value.colonyId());
        }
        for (Workshop workshop : snapshot.workshops()) {
            ColonyRuntime colony = requireColony(colonies, workshop.colonyId()); Registration registration = stagedRegistrations.get(workshop.registrationId());
            if (!ids.add(workshop.id()) || !tables.add(workshop.colonyId() + ":" + workshop.position()) || !colony.territory().contains(workshop.position())
                    || registration == null || !registration.colonyId().equals(workshop.colonyId()) || !registration.role().equals("workshop")) throw new IllegalArgumentException("Invalid workshop referent/identity");
        }
        for (ReservationLedger.Entry value : snapshot.reservations()) validateSavedClaim(ids, colonies, slotOwners, value.id(), value.colonyId(), value.slot());
        for (AllocationLedger.Entry value : snapshot.allocations()) validateSavedClaim(ids, colonies, slotOwners, value.id(), value.colonyId(), value.slot());
        Map<StockRegion, Long> quantities = new HashMap<>();
        Map<StockRegion, ItemDescriptor> descriptors = new HashMap<>();
        for (ReservationLedger.Entry value : snapshot.reservations()) validateSavedQuantity(quantities, descriptors, value.slot(), value.item(), value.count());
        for (AllocationLedger.Entry value : snapshot.allocations()) validateSavedQuantity(quantities, descriptors, value.slot(), value.item(), value.count());
        if (slotOwners.size() > MAX_SLOTS) throw new IllegalArgumentException("Canonical slot persistence envelope exceeded");
        List<AdmissionLedger.Lease> admitted = new ArrayList<>();
        PreparedRestore prepared = new PreparedRestore(snapshot, admitted);
        try {
            for (Registration value : snapshot.registrations()) prepared.registrations.put(value.id(), admit(replacement, admitted, value.colonyId(), Resource.PHYSICAL_TARGETS,
                    value.storages().stream().anyMatch(id -> id.bindingEpoch() > 0) ? Lane.CRITICAL : Lane.NORMAL));
            for (Workshop value : snapshot.workshops()) prepared.workshops.put(value.id(), admit(replacement, admitted, value.colonyId(), Resource.PHYSICAL_TARGETS));
            for (RetiredIdentity value : snapshot.retiredIdentities()) prepared.retired.put(value.storage(), admit(replacement, admitted, value.colonyId(), Resource.EVIDENCE));
            for (var value : slotOwners.entrySet()) prepared.slots.put(value.getKey(), admit(replacement, admitted, value.getValue(), Resource.STORAGE_SLOTS,
                    value.getKey().storage().bindingEpoch() > 0 ? Lane.CRITICAL : Lane.NORMAL));
            for (ReservationLedger.Entry value : snapshot.reservations()) prepared.claims.add(new Claim(false, value.id(), value.colonyId(), value.ownerId(), value.slot(), value.item(), value.count(), value.revision(), value.lane(), admit(replacement, admitted, value.colonyId(), Resource.RESERVATIONS_AND_ALLOCATIONS, value.lane())));
            for (AllocationLedger.Entry value : snapshot.allocations()) prepared.claims.add(new Claim(true, value.id(), value.colonyId(), value.ownerId(), value.slot(), value.item(), value.count(), value.revision(), value.lane(), admit(replacement, admitted, value.colonyId(), Resource.RESERVATIONS_AND_ALLOCATIONS, value.lane())));
        } catch (RuntimeException failure) { prepared.close(); throw failure; }
        return prepared;
    }
    private static ColonyRuntime requireColony(Map<UUID, ColonyRuntime> colonies, UUID id) {
        ColonyRuntime colony = colonies.get(id); if (colony == null) throw new IllegalArgumentException("Unknown storage colony"); return colony;
    }
    private static void validateSavedClaim(Set<UUID> ids, Map<UUID, ColonyRuntime> colonies, Map<StockRegion, UUID> slots, UUID id, UUID colony, StockRegion slot) {
        requireColony(colonies, colony);
        if (!ids.add(id)) throw new IllegalArgumentException("Duplicate storage obligation identity");
        slots.putIfAbsent(slot, colony);
    }
    private static void validateSavedQuantity(Map<StockRegion, Long> quantities, Map<StockRegion, ItemDescriptor> descriptors,
                                              StockRegion slot, ItemDescriptor item, long count) {
        ItemDescriptor previous = descriptors.putIfAbsent(slot, item);
        long total = quantities.getOrDefault(slot, 0L) + count;
        if (previous != null && !previous.equals(item) || total > MAX_COUNT) throw new IllegalArgumentException("Inconsistent saved canonical obligations");
        quantities.put(slot, total);
    }
    private static AdmissionLedger.Lease admit(AdmissionLedger ledger, List<AdmissionLedger.Lease> admitted, UUID colony, Resource resource) {
        return admit(ledger, admitted, colony, resource, Lane.NORMAL);
    }
    private static AdmissionLedger.Lease admit(AdmissionLedger ledger, List<AdmissionLedger.Lease> admitted, UUID colony, Resource resource, Lane lane) {
        AdmissionLedger.Lease lease = ledger.reserve(colony, lane, Map.of(resource, 1)); admitted.add(lease); return lease;
    }
    public final class PreparedRestore implements AutoCloseable {
        private StorageSnapshot snapshot;
        private final List<AdmissionLedger.Lease> admitted;
        private final Map<UUID, AdmissionLedger.Lease> registrations = new LinkedHashMap<>(), workshops = new LinkedHashMap<>();
        private final Map<StorageId, AdmissionLedger.Lease> retired = new LinkedHashMap<>();
        private final Map<StockRegion, AdmissionLedger.Lease> slots = new LinkedHashMap<>();
        private final List<Claim> claims = new ArrayList<>();
        private PreparedRestore(StorageSnapshot snapshot, List<AdmissionLedger.Lease> admitted) { this.snapshot = snapshot; this.admitted = admitted; }
        public void commit() {
            requireOwner(); if (snapshot == null) throw new IllegalStateException("Storage restore already closed");
            registrationLeases.values().forEach(AdmissionLedger.Lease::close); workshopLeases.values().forEach(AdmissionLedger.Lease::close);
            retiredLeases.values().forEach(AdmissionLedger.Lease::close); slotLeases.values().forEach(AdmissionLedger.Lease::close);
            StorageRegistry.this.claims.values().forEach(c -> c.lease.close());
            StorageRegistry.this.registrations.clear(); snapshot.registrations().forEach(r -> StorageRegistry.this.registrations.put(r.id(), r));
            StorageRegistry.this.workshops.clear(); snapshot.workshops().forEach(w -> StorageRegistry.this.workshops.put(w.id(), w));
            registrationViewKeys.clear(); workshopViewKeys.clear();
            for (UUID id : StorageRegistry.this.registrations.keySet()) registrationViewKeys.put(++registrationViewSequence, id);
            for (UUID id : StorageRegistry.this.workshops.keySet()) workshopViewKeys.put(++workshopViewSequence, id);
            StorageRegistry.this.retired.clear(); snapshot.retiredIdentities().forEach(r -> StorageRegistry.this.retired.put(r.storage(), r));
            registrationLeases.clear(); registrationLeases.putAll(registrations); workshopLeases.clear(); workshopLeases.putAll(workshops);
            retiredLeases.clear(); retiredLeases.putAll(retired); slotLeases.clear(); slotLeases.putAll(slots);
            StorageRegistry.this.claims.clear(); slotClaims.clear(); claims.forEach(StorageRegistry.this::addClaim);
            rebuildViews(); index.clear(); slots.keySet().forEach(index::add); snapshot = null; admitted.clear();
        }
        @Override public void close() {
            requireOwner(); if (snapshot != null) { admitted.forEach(AdmissionLedger.Lease::close); admitted.clear(); snapshot = null; }
        }
    }
}
