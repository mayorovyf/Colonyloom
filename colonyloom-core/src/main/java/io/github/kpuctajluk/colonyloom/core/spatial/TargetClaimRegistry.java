package io.github.kpuctajluk.colonyloom.core.spatial;

import io.github.kpuctajluk.colonyloom.core.building.BuildingRecord;
import io.github.kpuctajluk.colonyloom.core.citizen.ProfessionDefinition;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Owner-thread arbitration of Colonyloom goals, not protection against external world edits. */
public final class TargetClaimRegistry implements AutoCloseable {
    public enum State { PENDING, GRANTED, CONFLICT }

    /** Inclusive immutable bounds; chunk indexing uses floor division, including negative positions. */
    public record Snapshot(UUID ownerId, UUID colonyId, UUID buildingId, String dimension,
                           int minX, int minY, int minZ, int maxX, int maxY, int maxZ, long revision) {
        public Snapshot {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(colonyId, "colonyId");
            ProfessionDefinition.validateId(dimension);
            if (revision < 0 || minX > maxX || minY > maxY || minZ > maxZ
                    || minX < -30_000_000 || maxX >= 30_000_000
                    || minZ < -30_000_000 || maxZ >= 30_000_000
                    || minY < -2048 || maxY > 2047) {
                throw new IllegalArgumentException("Invalid physical target bounds or revision");
            }
            long links = ((long) (maxX >> 4) - (minX >> 4) + 1)
                    * ((long) (maxZ >> 4) - (minZ >> 4) + 1);
            if (links > 128) throw new IllegalArgumentException("Physical target exceeds 128 chunk links");
        }
        private int linkCount() { return ((maxX >> 4) - (minX >> 4) + 1) * ((maxZ >> 4) - (minZ >> 4) + 1); }
    }

    private record Chunk(String dimension, int x, int z) {}
    private static final class Link {
        final Entry entry;
        final Chunk chunk;
        Link next;
        Link previous;
        Link(Entry entry, Chunk chunk) { this.entry = entry; this.chunk = chunk; }
    }
    private static final class Entry {
        final Snapshot snapshot;
        final List<AdmissionLedger.Lease> leases = new ArrayList<>();
        final Link[] links;
        int accountedLinks;
        State state = State.PENDING;
        long comparedIndex = -1;
        boolean dirty = true;
        boolean buildingPhase = true;
        Entry buildingNext;
        Entry buildingPrevious;
        Entry buildingCursor;
        Entry next;
        Entry previous;
        int chunkCursor;
        Link candidateCursor;
        boolean bucketStarted;
        Entry(Snapshot snapshot) {
            this.snapshot = snapshot;
            links = new Link[snapshot.linkCount()];
            int index = 0;
            for (int x = snapshot.minX() >> 4; x <= snapshot.maxX() >> 4; x++) {
                for (int z = snapshot.minZ() >> 4; z <= snapshot.maxZ() >> 4; z++) {
                    links[index++] = new Link(this, new Chunk(snapshot.dimension(), x, z));
                }
            }
        }
    }
    private static final class Index {
        final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>();
        final Map<Chunk, Link> chunks = new HashMap<>();
        final Map<UUID, Entry> buildings = new HashMap<>();
        Entry head;
        void add(Entry entry) {
            entries.put(entry.snapshot.ownerId(), entry);
            if (head == null) {
                head = entry;
                entry.next = entry.previous = entry;
            } else {
                entry.next = head;
                entry.previous = head.previous;
                head.previous.next = entry;
                head.previous = entry;
            }
            for (Link link : entry.links) {
                link.next = chunks.put(link.chunk, link);
                if (link.next != null) link.next.previous = link;
            }
            UUID building = entry.snapshot.buildingId();
            if (building != null) {
                entry.buildingNext = buildings.put(building, entry);
                if (entry.buildingNext != null) entry.buildingNext.buildingPrevious = entry;
            }
        }
        void remove(Entry entry) {
            entries.remove(entry.snapshot.ownerId());
            if (entry.next == entry) head = null;
            else {
                entry.previous.next = entry.next;
                entry.next.previous = entry.previous;
                if (head == entry) head = entry.next;
            }
            for (Link link : entry.links) {
                if (link.previous != null) link.previous.next = link.next;
                else if (link.next == null) chunks.remove(link.chunk);
                else chunks.put(link.chunk, link.next);
                if (link.next != null) link.next.previous = link.previous;
            }
            UUID building = entry.snapshot.buildingId();
            if (building != null) {
                if (entry.buildingPrevious != null) entry.buildingPrevious.buildingNext = entry.buildingNext;
                else if (entry.buildingNext == null) buildings.remove(building);
                else buildings.put(building, entry.buildingNext);
                if (entry.buildingNext != null) entry.buildingNext.buildingPrevious = entry.buildingPrevious;
            }
        }
    }

    private final ColonyRegistry registry;
    private final GlobalWorkBudgets budgets;
    private Index index = new Index();
    private Entry serviceCursor;
    private long indexRevision;

    public TargetClaimRegistry(ColonyRegistry registry, GlobalWorkBudgets budgets) {
        this.registry = Objects.requireNonNull(registry);
        this.budgets = Objects.requireNonNull(budgets);
    }

    /** Same-owner updates revoke the old authorization only after admission succeeds. */
    public void propose(Snapshot snapshot) {
        registry.requireOwner();
        validateReferences(snapshot);
        Entry old = index.entries.get(snapshot.ownerId());
        if (old != null) {
            if (!old.snapshot.colonyId().equals(snapshot.colonyId())) throw new IllegalArgumentException("Target colony cannot change");
            if (snapshot.equals(old.snapshot)) return;
            if (snapshot.revision() <= old.snapshot.revision()) throw new IllegalArgumentException("Stale target revision");
        }
        Entry proposed = new Entry(snapshot);
        AdmissionLedger.Lease addition = account(proposed, old, registry.admission());
        try {
            registry.beforeMutation();
        } catch (RuntimeException | Error failure) {
            if (addition != null) addition.close();
            throw failure;
        }
        if (old != null) index.remove(old);
        index.add(proposed);
        indexRevision++;
        if (serviceCursor == old || serviceCursor == null) serviceCursor = proposed;
    }

    private void validateReferences(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        registry.colony(snapshot.colonyId());
        if (snapshot.buildingId() == null) return;
        for (BuildingRecord building : registry.buildings()) {
            if (building.buildingId().equals(snapshot.buildingId())) {
                if (!building.colonyId().equals(snapshot.colonyId())) throw new IllegalArgumentException("Foreign target building");
                return;
            }
        }
        throw new IllegalArgumentException("Unknown target building: " + snapshot.buildingId());
    }

    private static AdmissionLedger.Lease account(Entry entry, Entry reusable, AdmissionLedger ledger) {
        int addedLinks = entry.links.length;
        if (reusable != null) {
            entry.leases.addAll(reusable.leases);
            entry.accountedLinks = reusable.accountedLinks;
            addedLinks = Math.max(0, addedLinks - reusable.accountedLinks);
        }
        if (reusable != null && addedLinks == 0) return null;
        AdmissionLedger.Lease added = ledger.reserve(entry.snapshot.colonyId(), Lane.NORMAL,
                Map.of(Resource.PHYSICAL_TARGETS, reusable == null ? 1 : 0,
                        Resource.SPATIAL_INDEX_LINKS, addedLinks));
        entry.leases.add(added);
        entry.accountedLinks += addedLinks;
        return added;
    }

    /** Every visited owner or candidate is charged; unfinished cursors retain dirty debt. */
    public void tick() {
        registry.requireOwner();
        if (serviceCursor == null) serviceCursor = index.head;
        int idleVisits = 0;
        while (serviceCursor != null && budgets.timeAvailable()
                && budgets.tryConsume(Budget.BLUEPRINT_COMPARISONS, Lane.NORMAL)) {
            Entry entry = serviceCursor;
            serviceCursor = entry.next;
            if (entry.state == State.GRANTED) {
                if (++idleVisits >= index.entries.size()) return;
                continue;
            }
            if (entry.comparedIndex != indexRevision) restart(entry);
            if (!entry.dirty) {
                if (++idleVisits >= index.entries.size()) return;
                continue;
            }
            idleVisits = 0;
            long comparisonStart=System.nanoTime();
            try { compareOne(entry); }
            finally { registry.metrics().record(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer.BLUEPRINT_UNIT,System.nanoTime()-comparisonStart); }
        }
    }

    private void restart(Entry entry) {
        entry.state = State.PENDING;
        entry.dirty = true;
        entry.comparedIndex = indexRevision;
        entry.buildingPhase = true;
        entry.buildingCursor = entry.snapshot.buildingId() == null ? null : index.buildings.get(entry.snapshot.buildingId());
        entry.chunkCursor = 0;
        entry.candidateCursor = null;
        entry.bucketStarted = false;
    }

    private void compareOne(Entry entry) {
        if (entry.buildingPhase) {
            Entry other = entry.buildingCursor;
            if (other != null) {
                entry.buildingCursor = other.buildingNext;
                if (other != entry) finish(entry, State.CONFLICT);
                return;
            }
            entry.buildingPhase = false;
        }
        if (entry.chunkCursor == entry.links.length) {
            finish(entry, State.GRANTED);
            return;
        }
        Link own = entry.links[entry.chunkCursor];
        if (!entry.bucketStarted) {
            entry.candidateCursor = index.chunks.get(own.chunk);
            entry.bucketStarted = true;
        }
        Link candidate = entry.candidateCursor;
        if (candidate == null) {
            entry.chunkCursor++;
            entry.bucketStarted = false;
            return;
        }
        entry.candidateCursor = candidate.next;
        Entry other = candidate.entry;
        if (other == entry) return;
        // A pair is compared only in its first shared chunk, without a per-query candidate set.
        int firstX = Math.max(entry.snapshot.minX() >> 4, other.snapshot.minX() >> 4);
        int firstZ = Math.max(entry.snapshot.minZ() >> 4, other.snapshot.minZ() >> 4);
        if (own.chunk.x() == firstX && own.chunk.z() == firstZ && intersects(entry.snapshot, other.snapshot)) {
            finish(entry, State.CONFLICT);
        }
    }

    private void finish(Entry entry, State result) {
        // No yielding or callback between this global revision guard and publication.
        if (entry.comparedIndex != indexRevision) {
            restart(entry);
            return;
        }
        entry.state = result;
        entry.dirty = false;
        entry.candidateCursor = null;
        entry.buildingCursor = null;
    }

    private static boolean intersects(Snapshot a, Snapshot b) {
        return a.dimension().equals(b.dimension())
                && a.minX() <= b.maxX() && b.minX() <= a.maxX()
                && a.minY() <= b.maxY() && b.minY() <= a.maxY()
                && a.minZ() <= b.maxZ() && b.minZ() <= a.maxZ();
    }

    public State state(UUID ownerId) {
        registry.requireOwner();
        Entry entry = index.entries.get(Objects.requireNonNull(ownerId));
        if (entry == null) throw new IllegalArgumentException("Unknown physical target: " + ownerId);
        return entry.state;
    }

    public boolean owns(UUID ownerId, long revision) {
        registry.requireOwner();
        Entry entry = index.entries.get(Objects.requireNonNull(ownerId));
        return entry != null && entry.state == State.GRANTED && entry.snapshot.revision() == revision;
    }

    /** Caller must first stop physical actions; unrelated owners' index links and leases survive. */
    public void release(UUID ownerId) {
        registry.requireOwner();
        Entry entry = index.entries.get(Objects.requireNonNull(ownerId));
        if (entry == null) return;
        registry.beforeMutation();
        if (serviceCursor == entry) serviceCursor = entry.next == entry ? null : entry.next;
        index.remove(entry);
        indexRevision++;
        closeLeases(entry);
    }

    public List<Snapshot> snapshots() {
        registry.requireOwner();
        return index.entries.values().stream().map(entry -> entry.snapshot).toList();
    }

    public void restore(List<Snapshot> snapshots) {
        registry.requireOwner();
        Objects.requireNonNull(snapshots);
        for (Snapshot snapshot : snapshots) validateReferences(snapshot);
        try (PreparedRestore prepared = prepareRestore(snapshots, registry.admission())) {
            prepared.commit();
        }
    }

    /** Parent validates colony/building cross-references against its staged registry before this call. */
    public PreparedRestore prepareRestore(List<Snapshot> snapshots, AdmissionLedger replacement) {
        registry.requireOwner();
        Objects.requireNonNull(snapshots);
        Objects.requireNonNull(replacement);
        // Validate every owner before admitting any memory or publishing any index.
        Set<UUID> owners = new HashSet<>();
        for (Snapshot snapshot : snapshots) {
            Objects.requireNonNull(snapshot);
            if (!owners.add(snapshot.ownerId())) throw new IllegalArgumentException("Duplicate physical target: " + snapshot.ownerId());
        }
        PreparedRestore prepared = new PreparedRestore();
        try {
            for (Snapshot snapshot : snapshots) {
                Entry staged = new Entry(snapshot);
                Entry old = replacement == registry.admission() ? index.entries.get(snapshot.ownerId()) : null;
                if (old != null && !old.snapshot.colonyId().equals(snapshot.colonyId())) old = null;
                AdmissionLedger.Lease addition = account(staged, old, replacement);
                if (old != null) prepared.reused.add(old);
                if (addition != null) prepared.additions.add(addition);
                prepared.staged.add(staged);
            }
            // Startup is quiescent: establish conflicts for the entire staged batch before publication.
            for (Entry entry : prepared.staged.entries.values()) {
                Snapshot a = entry.snapshot;
                for (Link own : entry.links) {
                    for (Link candidate = prepared.staged.chunks.get(own.chunk); candidate != null; candidate = candidate.next) {
                        Entry other = candidate.entry;
                        if (other != entry && intersects(a, other.snapshot)) {
                            entry.state = other.state = State.CONFLICT;
                        }
                    }
                }
                if (a.buildingId() != null) {
                    for (Entry other = prepared.staged.buildings.get(a.buildingId()); other != null; other = other.buildingNext) {
                        if (other != entry) entry.state = other.state = State.CONFLICT;
                    }
                }
            }
            for (Entry entry : prepared.staged.entries.values()) {
                if (entry.state != State.CONFLICT) entry.state = State.GRANTED;
                entry.dirty = false;
                entry.comparedIndex = indexRevision + 1;
            }
            return prepared;
        } catch (RuntimeException | Error failure) {
            prepared.close();
            throw failure;
        }
    }

    /** Scoped staging; abandoning it releases only new reservations, never live grants. */
    public final class PreparedRestore implements AutoCloseable {
        private final Index staged = new Index();
        private final Set<Entry> reused = new HashSet<>();
        private final List<AdmissionLedger.Lease> additions = new ArrayList<>();
        private final long preparedRevision = indexRevision;
        private boolean finished;
        private PreparedRestore() {}
        public void commit() {
            registry.requireOwner();
            if (finished) throw new IllegalStateException("Claim restore already closed or committed");
            if (preparedRevision != indexRevision) throw new IllegalStateException("Claim registry changed during staged restore");
            Index previous = index;
            index = staged;
            indexRevision++;
            serviceCursor = index.head;
            finished = true;
            for (Entry entry : previous.entries.values()) if (!reused.contains(entry)) closeLeases(entry);
        }
        @Override public void close() {
            registry.requireOwner();
            if (finished) return;
            finished = true;
            for (AdmissionLedger.Lease lease : additions) lease.close();
        }
    }

    private static void closeLeases(Entry entry) {
        for (AdmissionLedger.Lease lease : entry.leases) lease.close();
    }

    /** Shutdown releases runtime index memory without ordinary command mutation hooks. */
    @Override public void close() {
        registry.requireOwner();
        for (Entry entry : index.entries.values()) closeLeases(entry);
        index = new Index();
        serviceCursor = null;
        indexRevision++;
    }
}
