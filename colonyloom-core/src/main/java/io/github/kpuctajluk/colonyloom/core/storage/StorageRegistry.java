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
        final AdmissionLedger.Lease lease;
        Claim(boolean allocation, UUID id, UUID colony, UUID owner, StockRegion slot, ItemDescriptor item,
              long count, long revision, AdmissionLedger.Lease lease) {
            this.allocation = allocation; this.id = id; this.colony = colony; this.owner = owner;
            this.slot = slot; this.item = item; this.count = count; this.revision = revision; this.lease = lease;
        }
        ReservationLedger.Entry reservation() { return new ReservationLedger.Entry(id, colony, owner, slot, item, count, revision); }
        AllocationLedger.Entry allocation() { return new AllocationLedger.Entry(id, colony, owner, slot, item, count, revision); }
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
    private final Map<StorageId, RetiredIdentity> retired = new LinkedHashMap<>();
    private final Map<UUID, AdmissionLedger.Lease> registrationLeases = new LinkedHashMap<>(), workshopLeases = new LinkedHashMap<>();
    private final Map<StorageId, AdmissionLedger.Lease> retiredLeases = new LinkedHashMap<>();
    private final Map<StockRegion, AdmissionLedger.Lease> slotLeases = new LinkedHashMap<>();
    private final Map<UUID, Claim> claims = new LinkedHashMap<>();
    private final Map<StockRegion, List<Claim>> slotClaims = new HashMap<>();
    private final Map<UUID, Set<StockRegion>> views = new HashMap<>();

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
        try {
            if (previous == null) registrationLease = registry.admission().reserve(colonyId, Lane.NORMAL, Map.of(Resource.PHYSICAL_TARGETS, 1));
            for (StockRegion slot : additions) admitted.put(slot, registry.admission().reserve(colonyId, Lane.NORMAL, Map.of(Resource.STORAGE_SLOTS, 1)));
            registry.beforeMutation();
        } catch (RuntimeException failure) {
            if (registrationLease != null) registrationLease.close();
            admitted.values().forEach(AdmissionLedger.Lease::close); throw failure;
        }
        registrations.put(next.id(), next);
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
        workshops.put(next.id(), next); if (lease != null) workshopLeases.put(next.id(), lease); return next;
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
    Claim claim(boolean allocation, UUID id, UUID colony, UUID owner, StockRegion slot, ItemDescriptor item, long count, long tick) {
        requireOwner(); validateClaim(id, colony, owner, slot, item, count, 0);
        if (tick < 0) throw new IllegalArgumentException("Negative stock claim tick");
        Claim previous = claims.get(id);
        if (previous != null) {
            if (previous.allocation == allocation && previous.matches(colony, owner, slot, item, count)) return previous;
            throw new IllegalArgumentException("Obligation identity already used");
        }
        registry.colony(colony);
        if (!authorized(colony, slot) || retired.containsKey(slot.storage())) throw new IllegalArgumentException("Slot outside current colony storage view");
        if (registry.usedId(id) || registrations.containsKey(id) || workshops.containsKey(id)) throw new IllegalArgumentException("Obligation identity already used");
        StockIndex.Observation observation = index.observation(slot);
        if (!item.equals(observation.item()) || index.free(slot, tick) < count) throw new IllegalStateException("Insufficient known free canonical stock");
        if (claims.size() >= MAX_OBLIGATIONS) throw new IllegalArgumentException("Stock obligation envelope exceeded");
        AdmissionLedger.Lease lease = registry.admission().reserve(colony, Lane.NORMAL, Map.of(Resource.RESERVATIONS_AND_ALLOCATIONS, 1));
        try { registry.beforeMutation(); } catch (RuntimeException failure) { lease.close(); throw failure; }
        Claim next = new Claim(allocation, id, colony, owner, slot, item, count, 0, lease);
        addClaim(next); return next;
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
            if (retained == 0) { removeClaim(claim); }
            else { if (retained != claim.count) { claim.count = retained; claim.revision = Math.addExact(claim.revision, 1); } i++; }
        }
        prune(slot);
    }
    boolean authorized(UUID colony, StockRegion slot) { Set<StockRegion> view = views.get(colony); return view != null && view.contains(slot); }
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
            for (Registration value : snapshot.registrations()) prepared.registrations.put(value.id(), admit(replacement, admitted, value.colonyId(), Resource.PHYSICAL_TARGETS));
            for (Workshop value : snapshot.workshops()) prepared.workshops.put(value.id(), admit(replacement, admitted, value.colonyId(), Resource.PHYSICAL_TARGETS));
            for (RetiredIdentity value : snapshot.retiredIdentities()) prepared.retired.put(value.storage(), admit(replacement, admitted, value.colonyId(), Resource.EVIDENCE));
            for (var value : slotOwners.entrySet()) prepared.slots.put(value.getKey(), admit(replacement, admitted, value.getValue(), Resource.STORAGE_SLOTS));
            for (ReservationLedger.Entry value : snapshot.reservations()) prepared.claims.add(new Claim(false, value.id(), value.colonyId(), value.ownerId(), value.slot(), value.item(), value.count(), value.revision(), admit(replacement, admitted, value.colonyId(), Resource.RESERVATIONS_AND_ALLOCATIONS)));
            for (AllocationLedger.Entry value : snapshot.allocations()) prepared.claims.add(new Claim(true, value.id(), value.colonyId(), value.ownerId(), value.slot(), value.item(), value.count(), value.revision(), admit(replacement, admitted, value.colonyId(), Resource.RESERVATIONS_AND_ALLOCATIONS)));
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
        AdmissionLedger.Lease lease = ledger.reserve(colony, Lane.NORMAL, Map.of(resource, 1)); admitted.add(lease); return lease;
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
