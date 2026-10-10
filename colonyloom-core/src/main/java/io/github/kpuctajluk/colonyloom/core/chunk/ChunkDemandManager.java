package io.github.kpuctajluk.colonyloom.core.chunk;

import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/** Owner-thread desired domains, atomic footprint admission, and independently budgeted tickets. */
public final class ChunkDemandManager implements AutoCloseable {
    public enum Readiness { LOADED, BLOCK_TICKING, ENTITY_TICKING }
    public enum State { WAITING, ADMITTED, BLOCKED }
    public interface ChunkAccess {
        boolean acquire(UUID colonyId, ChunkKey center, Readiness required);
        void release(UUID colonyId, ChunkKey center, Readiness required);
        boolean ready(ChunkKey key, Readiness required);
    }
    private static final Resource[] CHARGES = {Resource.LOADED_FOOTPRINT, Resource.BLOCK_TICKING, Resource.ENTITY_TICKING};
    private static final Comparator<Demand> EVICTION = Comparator.comparingInt((Demand d) -> d.priority)
            .thenComparingLong(d -> d.lastUseful).thenComparing(d -> d.owner);
    private final ColonyRegistry registry;
    private final GlobalWorkBudgets budgets;
    private final ChunkAccess access;
    private final Map<UUID, Demand> demands = new HashMap<>();
    private final Map<ChunkKey, Cell> cells = new HashMap<>();
    private final Map<TicketKey, Ticket> tickets = new HashMap<>();
    private final TreeSet<Demand> evictable = new TreeSet<>(EVICTION);
    private final List<TreeSet<Ticket>> starts = List.of(new TreeSet<>(), new TreeSet<>(), new TreeSet<>());
    private final int[] footprintAdditions = new int[3];
    private Demand head, cursor;
    private final java.util.ArrayDeque<Demand> pendingAdmissions=new java.util.ArrayDeque<>();
    private boolean pendingTurn;
    private Plan plan;
    private long tick, revision, ticketSequence, ticketNanosHighWater;
    private java.util.function.Consumer<ChunkKey> coverageLoss = ignored -> {};
    private int operationDepth;
    private boolean drainingCoverageLosses;
    private Cell coverageLossHead, coverageLossTail;
    private static final class Cell {
        final ChunkKey key;
        final AdmissionLedger.Footprint[] shares = new AdmissionLedger.Footprint[3];
        Cell lossNext;
        boolean lossPending;
        Cell(ChunkKey key) { this.key = key; }
    }
    private record TicketKey(UUID colony, ChunkKey center, boolean ticking) {}
    private static final class Ticket implements Comparable<Ticket> {
        final TicketKey key;
        Lane lane;
        final long sequence;
        int references;
        final int[] laneReferences = new int[3];
        boolean acquired;
        long retryAt;
        Ticket(TicketKey key, Lane lane, long sequence) { this.key = key; this.lane = lane; this.sequence = sequence; }
        Readiness readiness() { return key.ticking ? Readiness.ENTITY_TICKING : Readiness.LOADED; }
        @Override public int compareTo(Ticket other) {
            int retry = Long.compare(retryAt, other.retryAt);
            return retry != 0 ? retry : Long.compare(sequence, other.sequence);
        }
    }
    private static final class Demand {
        final UUID owner, colony;
        final List<ChunkKey> centers;
        final ChunkKey[][] rings;
        final Readiness readiness;
        final Lane lane;
        final int priority;
        final boolean dependency;
        final AdmissionLedger.Lease lease;
        final List<Ticket> tickets = new ArrayList<>();
        Demand previous, next;
        State state = State.WAITING;
        Reason reason = Reason.WORKING_SET_LIMIT;
        boolean physicalStep, unsafeCargo, critical;
        long waitingSince, lastUseful;
        boolean pendingQueued;
        long admittedAt, admittedTotal, activeTotal, sampledTick = -1;
        boolean sampledReady;
        Demand(UUID owner, UUID colony, List<ChunkKey> centers, ChunkKey[][] rings, Readiness readiness,
                Lane lane, int priority, boolean dependency, AdmissionLedger.Lease lease, long tick) {
            this.owner = owner; this.colony = colony; this.centers = centers; this.rings = rings;
            this.readiness = readiness; this.lane = lane; this.priority = priority; this.dependency = dependency;
            this.lease = lease; waitingSince = lastUseful = tick;
        }
        boolean protectedNow() { return physicalStep || unsafeCargo || critical || lane == Lane.CRITICAL; }
    }
    private static final class Plan {
        final Demand target;
        final long revision;
        final List<Demand> victims = new ArrayList<>();
        // Per-ring, per-lane removed references; plan advances only one victim per visit.
        final Map<ChunkKey, int[][]> removed = new HashMap<>();
        final int[] reclaimed = new int[3];
        final int[] reclaimedCritical = new int[3];
        final int[] totals = new int[3];
        int reclaimedService, reclaimedOrdinary;
        Demand last;
        Plan(Demand target, long revision) { this.target = target; this.revision = revision; }
    }

    public ChunkDemandManager(ColonyRegistry registry, GlobalWorkBudgets budgets, ChunkAccess access) {
        this.registry = Objects.requireNonNull(registry); this.budgets = Objects.requireNonNull(budgets);
        this.access = Objects.requireNonNull(access);
    }

    /** Notifies only removal of the last charged entity-ticking reference at a center. */
    public void setCoverageLossListener(java.util.function.Consumer<ChunkKey> listener) {
        registry.requireOwner(); coverageLoss = Objects.requireNonNull(listener);
    }

    private void beginOperation() { operationDepth++; }

    private void endOperation(Throwable operationFailure) {
        if (--operationDepth != 0 || drainingCoverageLosses) return;
        drainingCoverageLosses = true;
        Throwable callbackFailure = null;
        try {
            while (coverageLossHead != null) {
                Cell cell = coverageLossHead;
                coverageLossHead = cell.lossNext;
                if (coverageLossHead == null) coverageLossTail = null;
                cell.lossNext = null;
                cell.lossPending = false;
                if (!admitted(cell.key)) {
                    try { coverageLoss.accept(cell.key); }
                    catch (RuntimeException | Error thrown) {
                        if (callbackFailure == null) callbackFailure = thrown;
                        else if (callbackFailure != thrown) callbackFailure.addSuppressed(thrown);
                    }
                }
                if (!cell.lossPending && (cell.shares[0] == null || cell.shares[0].references() == 0)
                        && (cell.shares[1] == null || cell.shares[1].references() == 0)
                        && (cell.shares[2] == null || cell.shares[2].references() == 0))
                    cells.remove(cell.key, cell);
            }
        } finally {
            drainingCoverageLosses = false;
        }
        if (callbackFailure != null) {
            if (operationFailure == null) rethrow(callbackFailure);
            if (operationFailure != callbackFailure) operationFailure.addSuppressed(callbackFailure);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new AssertionError(failure);
    }

    private void queueCoverageLoss(Cell cell) {
        if (cell.lossPending) return;
        cell.lossPending = true;
        if (coverageLossTail == null) coverageLossHead = cell;
        else coverageLossTail.lossNext = cell;
        coverageLossTail = cell;
    }


    public void request(UUID ownerId, UUID colonyId, List<ChunkKey> centers, Readiness required,
            Lane lane, int priority, boolean dependency) {
        registry.requireOwner();
        beginOperation();
        Throwable failure = null;
        try {
            Objects.requireNonNull(ownerId); Objects.requireNonNull(colonyId);
            Objects.requireNonNull(required); Objects.requireNonNull(lane); Objects.requireNonNull(centers);
            if (centers.isEmpty() || centers.size() > 81 || priority < 0 || priority > 10)
                throw new IllegalArgumentException("Invalid chunk demand bounds");
            List<ChunkKey> unique = List.copyOf(new LinkedHashSet<>(centers));
            Demand old = demands.get(ownerId);
            if (old != null && old.colony.equals(colonyId) && old.centers.equals(unique)
                    && old.readiness == required && old.lane == lane && old.priority == priority && old.dependency == dependency) return;
            if (old != null && old.protectedNow()) throw new IllegalStateException("Protected chunk domain cannot be replaced");
            ChunkKey[][] rings = rings(unique, required);
            int links = 1 + unique.size();
            for (ChunkKey[] ring : rings) links = Math.addExact(links, ring.length);
            AdmissionLedger.Lease lease = registry.admission().reserve(colonyId, lane, Map.of(Resource.CHUNK_DEMANDS, links));
            if (old != null) release(ownerId);
            Demand d = new Demand(ownerId, colonyId, unique, rings, required, lane, priority, dependency, lease, tick);
            demands.put(ownerId, d);
            if (head == null) { head = cursor = d; d.previous = d.next = d; }
            else { d.previous = head.previous; d.next = head; head.previous.next = d; head.previous = d; }
            pendingAdmissions.addLast(d);d.pendingQueued=true;
            if (!minimumFits(d)) { d.state = State.BLOCKED; d.reason = Reason.WORKING_SET_LIMIT; }
            revision++;
        } catch (RuntimeException | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            endOperation(failure);
        }
    }

    private static ChunkKey[][] rings(List<ChunkKey> centers, Readiness required) {
        LinkedHashSet<ChunkKey> loaded = new LinkedHashSet<>(), block = new LinkedHashSet<>(), entity = new LinkedHashSet<>();
        for (ChunkKey center : centers) {
            if (Math.abs(center.x()) > 1_875_000 || Math.abs(center.z()) > 1_875_000)
                throw new IllegalArgumentException("Ticket center outside world bounds");
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                ChunkKey key = new ChunkKey(center.dimension(), center.x() + x, center.z() + z);
                loaded.add(key);
                // UUID ticking tickets at level31 imply both the3x3 block ring and center entities.
                if (required != Readiness.LOADED && Math.abs(x) <= 1 && Math.abs(z) <= 1) block.add(key);
            }
            if (required != Readiness.LOADED) entity.add(center);
        }
        return new ChunkKey[][] {loaded.toArray(ChunkKey[]::new), block.toArray(ChunkKey[]::new), entity.toArray(ChunkKey[]::new)};
    }

    private boolean minimumFits(Demand d) {
        AdmissionLedger ledger = registry.admission();
        int footprintCap = ledger.limits().resource(Resource.LOADED_FOOTPRINT);
        footprintCap = d.lane == Lane.SERVICE ? footprintCap / 8 : footprintCap - footprintCap / 8;
        return d.rings[0].length <= footprintCap
                && d.rings[1].length <= ledger.limits().resource(Resource.BLOCK_TICKING)
                && d.rings[2].length <= ledger.limits().resource(Resource.ENTITY_TICKING);
    }

    /** Cursor and eviction plans carry unfinished work; neither loops over every demand each tick. */
    public void tick(long monotonicTick) {
        registry.requireOwner();
        beginOperation();
        Throwable failure = null;
        try {
            if (monotonicTick < tick) throw new IllegalArgumentException("Nonmonotonic chunk tick");
            tick = monotonicTick;
            int visits = Math.min(demands.size(), Math.max(1, budgets.limits().budget(Budget.DIRTY_RESCAN_OBJECTS) / 4));
            while (cursor != null && visits-- > 0 && budgets.timeAvailable()) {
                Lane lane = budgets.chooseLane(Budget.DIRTY_RESCAN_OBJECTS, false, true, false);
                if (lane == null || !budgets.tryConsume(Budget.DIRTY_RESCAN_OBJECTS,lane)) break;
                if (plan != null && plan.revision != revision) plan = null;
                pendingTurn=!pendingTurn;
                // An incremental eviction plan must not monopolize discovery of feasible demands.
                if (plan != null && (!pendingTurn || pendingAdmissions.isEmpty())) { advancePlan(); continue; }
                Demand d;
                if(pendingTurn && !pendingAdmissions.isEmpty()) { d=pendingAdmissions.removeFirst();d.pendingQueued=false; }
                else { d=cursor;cursor=cursor.next; }
                if (d.state == State.ADMITTED) {
                    sampleClock(d);
                    if (overLimit(d) && !d.protectedNow()) withdraw(d);
                } else if (minimumFits(d)) {
                    d.state = State.WAITING;
                    if (!overLimit() && canAdmit(d)) admit(d);
                    else if (!overLimit() && plan == null) plan = new Plan(d, revision);
                } else { d.state = State.BLOCKED; d.reason = Reason.WORKING_SET_LIMIT; }
                if(d.state!=State.ADMITTED && !d.pendingQueued) { pendingAdmissions.addLast(d);d.pendingQueued=true; }
            }
            startTickets();
        } catch (RuntimeException | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            endOperation(failure);
        }
    }

    private boolean canAdmit(Demand d) {
        int[] additions = footprintAdditions;
        java.util.Arrays.fill(additions, 0);
        int ordinary = 0, service = 0;
        for (int r = 0; r < 3; r++) for (ChunkKey key : d.rings[r]) {
            Cell cell = cells.get(key);
            AdmissionLedger.Footprint share = cell == null ? null : cell.shares[r];
            if (share == null || share.references() == 0) additions[r]++;
            if (r == 0) {
                if (d.lane == Lane.SERVICE) { if (share == null || share.references(Lane.SERVICE) == 0) service++; }
                else if (share == null || share.ordinaryReferences() == 0) ordinary++;
            }
        }
        return registry.admission().canReserveFootprint(d.lane, additions[0], additions[1], additions[2], ordinary, service);
    }

    private void admit(Demand d) {
        for (int r = 0; r < 3; r++) for (ChunkKey key : d.rings[r]) {
            Cell cell = cells.computeIfAbsent(key, Cell::new);
            if (cell.shares[r] == null) cell.shares[r] = registry.admission().footprint(CHARGES[r]);
            cell.shares[r].retain(d.colony, d.lane);
        }
        for (ChunkKey center : d.centers) {
            TicketKey key = new TicketKey(d.colony, center, d.readiness != Readiness.LOADED);
            Ticket ticket = tickets.get(key);
            if (ticket == null) {
                ticket = new Ticket(key, d.lane, ticketSequence++); tickets.put(key, ticket);
                starts.get(ticket.lane.ordinal()).add(ticket);
            }
            ticket.references++; ticket.laneReferences[d.lane.ordinal()]++; d.tickets.add(ticket);
            refreshLane(ticket);
        }
        d.state = State.ADMITTED; d.reason = Reason.CHUNK_NOT_READY; d.lastUseful = tick;
        if(d.pendingQueued) { pendingAdmissions.remove(d);d.pendingQueued=false; }
        d.admittedAt = tick; d.sampledTick = -1; d.sampledReady = false;
        if (!d.protectedNow()) evictable.add(d);
        revision++;
    }

    private int urgency(Demand d) {
        return d.priority + (d.dependency ? 20 : 0) + (d.lane == Lane.CRITICAL || d.critical ? 40 : 0)
                + (int) Math.min(10, Math.max(0, tick - d.waitingSince) / 200);
    }

    private void advancePlan() {
        Plan p = plan;
        Demand victim = p.last == null ? (evictable.isEmpty() ? null : evictable.first()) : evictable.higher(p.last);
        if (victim == null) { p.target.reason = Reason.WORKING_SET_LIMIT; plan = null; return; }
        p.last = victim;
        if (urgency(p.target) <= victim.priority + (victim.dependency ? 20 : 0)
                || victim.lastUseful >= tick) return;
        p.victims.add(victim);
        for (int r = 0; r < 3; r++) for (ChunkKey key : victim.rings[r]) {
            int[][] removed = p.removed.computeIfAbsent(key, ignored -> new int[3][3]);
            removed[r][victim.lane.ordinal()]++;
            AdmissionLedger.Footprint share = cells.get(key).shares[r];
            int count = removed[r][0] + removed[r][1] + removed[r][2];
            if (count == share.references()) p.reclaimed[r]++;
            if (victim.lane == Lane.CRITICAL && removed[r][Lane.CRITICAL.ordinal()] == share.references(Lane.CRITICAL)) p.reclaimedCritical[r]++;
            if (r == 0) {
                if (victim.lane == Lane.SERVICE && removed[r][Lane.SERVICE.ordinal()] == share.references(Lane.SERVICE)) p.reclaimedService++;
                else if (victim.lane != Lane.SERVICE && removed[r][Lane.NORMAL.ordinal()] + removed[r][Lane.CRITICAL.ordinal()] == share.ordinaryReferences()) p.reclaimedOrdinary++;
            }
        }
        if (!fitsAfter(p)) return;
        // Final revision and guard check, withdrawal and all-domain grant are one uninterrupted step.
        if (p.revision != revision) { plan = null; return; }
        for (Demand selected : p.victims) if (selected.protectedNow()) { plan = null; return; }
        for (Demand selected : p.victims) withdraw(selected);
        if (!canAdmit(p.target)) throw new IllegalStateException("Chunk feasibility changed without revision");
        admit(p.target); plan = null;
    }

    private boolean fitsAfter(Plan p) {
        int[] totals = p.totals;
        totals[0] = footprint(); totals[1] = blockTicking(); totals[2] = entityTicking();
        int service = registry.admission().used(Resource.LOADED_FOOTPRINT, Lane.SERVICE);
        int ordinary = registry.admission().ordinaryFootprint();
        service -= p.reclaimedService;
        ordinary -= p.reclaimedOrdinary;
        for (int r = 0; r < 3; r++) totals[r] -= p.reclaimed[r];
        for (int r = 0; r < 3; r++) for (ChunkKey key : p.target.rings[r]) {
            Cell cell = cells.get(key);
            int[][] removal = p.removed.get(key);
            AdmissionLedger.Footprint share = cell == null ? null : cell.shares[r];
            int removed = removal == null ? 0 : removal[r][0] + removal[r][1] + removal[r][2];
            if (share == null || share.references() == removed) totals[r]++;
            if (r == 0) {
                if (p.target.lane == Lane.SERVICE) {
                    if (share == null || share.references(Lane.SERVICE) == (removal == null ? 0 : removal[r][Lane.SERVICE.ordinal()])) service++;
                } else if (share == null || share.ordinaryReferences() == (removal == null ? 0 : removal[r][Lane.NORMAL.ordinal()] + removal[r][Lane.CRITICAL.ordinal()])) ordinary++;
            }
        }
        int loadedCap = registry.admission().limits().resource(Resource.LOADED_FOOTPRINT);
        if (service > loadedCap / 8 || ordinary > loadedCap - loadedCap / 8) return false;
        for (int r = 0; r < 3; r++) if (totals[r] > registry.admission().limits().resource(CHARGES[r])) return false;
        return registry.admission().footprintBlockadeClears(p.target.lane, p.reclaimed, p.reclaimedCritical);
    }

    private void withdraw(Demand d) {
        if (d.state != State.ADMITTED) return;
        d.admittedTotal += tick - d.admittedAt;
        d.sampledTick = -1; d.sampledReady = false;
        evictable.remove(d);
        for (Ticket ticket : d.tickets) {
            ticket.laneReferences[d.lane.ordinal()]--;
            if (--ticket.references == 0) {
                starts.get(ticket.lane.ordinal()).remove(ticket); tickets.remove(ticket.key);
                if (ticket.acquired) access.release(ticket.key.colony, ticket.key.center, ticket.readiness());
            } else refreshLane(ticket);
        }
        d.tickets.clear();
        for (int r = 0; r < 3; r++) for (ChunkKey key : d.rings[r]) {
            Cell cell = cells.get(key);
            cell.shares[r].release(d.colony, d.lane);
            if (r == 2 && cell.shares[2].references() == 0) queueCoverageLoss(cell);
            if (cell.shares[0].references() == 0 && (cell.shares[1] == null || cell.shares[1].references() == 0)
                    && (cell.shares[2] == null || cell.shares[2].references() == 0)
                    && !cell.lossPending && cell.lossNext == null) cells.remove(key);
        }
        d.state = State.WAITING; d.reason = Reason.WORKING_SET_LIMIT; d.waitingSince = tick;
        if(!d.pendingQueued && demands.containsKey(d.owner)) { pendingAdmissions.addLast(d);d.pendingQueued=true; }
        revision++;
    }
    private void refreshLane(Ticket ticket) {
        Lane preferred = ticket.laneReferences[Lane.CRITICAL.ordinal()] != 0 ? Lane.CRITICAL
                : ticket.laneReferences[Lane.SERVICE.ordinal()] != 0 ? Lane.SERVICE : Lane.NORMAL;
        if (ticket.acquired || ticket.lane == preferred) return;
        starts.get(ticket.lane.ordinal()).remove(ticket);
        ticket.lane = preferred;
        starts.get(ticket.lane.ordinal()).add(ticket);
    }

    private boolean eligible(Lane lane) {
        TreeSet<Ticket> queue = starts.get(lane.ordinal());
        return !queue.isEmpty() && queue.first().retryAt <= tick;
    }
    private void startTickets() {
        while (budgets.timeAvailable()) {
            Lane lane = budgets.chooseLane(Budget.CHUNK_REQUESTS, eligible(Lane.CRITICAL), eligible(Lane.SERVICE), eligible(Lane.NORMAL));
            if (lane == null || !budgets.tryConsume(Budget.CHUNK_REQUESTS, lane)) return;
            Ticket ticket = starts.get(lane.ordinal()).pollFirst();
            long started = System.nanoTime();
            boolean success;
            try { success = access.acquire(ticket.key.colony, ticket.key.center, ticket.readiness()); }
            finally {
                long nanos=System.nanoTime()-started;
                ticketNanosHighWater=Math.max(ticketNanosHighWater,nanos);
                registry.metrics().record(Timer.CHUNK_EXTERNAL,nanos);
                registry.metrics().record(Timer.CHUNK_UNIT,nanos);
            }
            if (success) ticket.acquired = true;
            else { ticket.retryAt = tick + 100; starts.get(lane.ordinal()).add(ticket); }
        }
    }

    public boolean admitted(UUID owner) { registry.requireOwner(); Demand d = demands.get(owner); return d != null && d.state == State.ADMITTED; }
    public State state(UUID owner) { registry.requireOwner(); Demand d = demands.get(owner); return d == null ? State.WAITING : d.state; }
    public boolean ready(UUID owner) {
        registry.requireOwner(); Demand d = demands.get(owner);
        if (d == null || d.state != State.ADMITTED) return false;
        for (Ticket ticket : d.tickets) if (!ticket.acquired) return false;
        for (ChunkKey center : d.centers) if (!access.ready(center, d.readiness)) return false;
        return true;
    }
    public boolean ready(ChunkKey key, Readiness required) { registry.requireOwner(); return access.ready(key, required); }
    /** True only for a center explicitly included in at least one admitted ticking domain. */
    public boolean admitted(ChunkKey key) {
        registry.requireOwner();
        Cell cell = cells.get(key);
        return cell != null && cell.shares[2] != null && cell.shares[2].references() != 0;
    }
    public long admittedTicks(UUID owner) {
        registry.requireOwner(); Demand d = demands.get(owner);
        return d == null ? 0 : d.admittedTotal + (d.state == State.ADMITTED ? tick - d.admittedAt : 0);
    }
    /** Counts only adjacent ready observations, never catch-up through an unsampled interval. */
    public long activeTicks(UUID owner) {
        registry.requireOwner(); Demand d = demands.get(owner);
        if (d == null) return 0;
        sampleClock(d);
        return d.activeTotal;
    }
    private void sampleClock(Demand d) {
        if (d.sampledTick == tick) return;
        boolean nowReady = ready(d.owner);
        if (nowReady && d.sampledReady && d.sampledTick == tick - 1) d.activeTotal++;
        d.sampledTick = tick; d.sampledReady = nowReady;
    }
    public Reason reason(UUID owner) {
        registry.requireOwner(); Demand d = demands.get(owner);
        if (d == null) return Reason.CHUNK_NOT_READY;
        return d.state == State.ADMITTED ? (ready(owner) ? Reason.NONE : Reason.CHUNK_NOT_READY) : d.reason;
    }
    public void setProtection(UUID owner, boolean physicalStep, boolean unsafeCargo, boolean critical) {
        registry.requireOwner(); Demand d = demands.get(owner);
        if (d == null) return;
        if (d.physicalStep == physicalStep && d.unsafeCargo == unsafeCargo && d.critical == critical) return;
        evictable.remove(d);
        d.physicalStep = physicalStep; d.unsafeCargo = unsafeCargo; d.critical = critical;
        if (d.state == State.ADMITTED && !d.protectedNow()) evictable.add(d);
        revision++;
    }
    public void useful(UUID owner) {
        registry.requireOwner(); Demand d = demands.get(owner);
        if (d == null) return;
        boolean wasEvictable = evictable.contains(d);
        evictable.remove(d); d.lastUseful = tick;
        if (d.state == State.ADMITTED && !d.protectedNow()) evictable.add(d);
        if (wasEvictable) revision++;
    }
    public void release(UUID owner) {
        registry.requireOwner();
        beginOperation();
        Throwable failure = null;
        try {
            Demand d = demands.remove(owner);
            if (d == null) return;
            withdraw(d); d.lease.close();
            if(d.pendingQueued) { pendingAdmissions.remove(d);d.pendingQueued=false; }
            if (d.next == d) { head = cursor = null; }
            else {
                d.previous.next = d.next; d.next.previous = d.previous;
                if (head == d) head = d.next;
                if (cursor == d) cursor = d.next;
            }
            revision++;
        } catch (RuntimeException | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            endOperation(failure);
        }
    }
    public void limitsUpdated() { registry.requireOwner(); revision++; }
    private boolean overLimit() {
        AdmissionLedger ledger = registry.admission();
        int total = ledger.limits().resource(Resource.LOADED_FOOTPRINT);
        if (ledger.used(Resource.LOADED_FOOTPRINT, Lane.SERVICE) > total / 8
                || ledger.ordinaryFootprint() > total - total / 8) return true;
        for (Resource charge : CHARGES) if (ledger.overLimit(charge) != 0) return true;
        return false;
    }
    private boolean overLimit(Demand d) {
        AdmissionLedger ledger = registry.admission();
        for (int r = 0; r < 3; r++) if (d.rings[r].length != 0 && ledger.overLimit(CHARGES[r]) != 0) return true;
        int total = ledger.limits().resource(Resource.LOADED_FOOTPRINT);
        return d.lane == Lane.SERVICE ? ledger.used(Resource.LOADED_FOOTPRINT, Lane.SERVICE) > total / 8
                : ledger.ordinaryFootprint() > total - total / 8;
    }
    public int footprint() { return registry.admission().used(Resource.LOADED_FOOTPRINT); }
    public int blockTicking() { return registry.admission().used(Resource.BLOCK_TICKING); }
    public int entityTicking() { return registry.admission().used(Resource.ENTITY_TICKING); }
    public long ticketNanosHighWater() { registry.requireOwner(); return ticketNanosHighWater; }
    public Map<String,Object> diagnostics(UUID onlyColony) {
        registry.requireOwner(); long admitted=0, waiting=0, ready=0;
        Map<String,Object> levels=new java.util.LinkedHashMap<>();
        for (Readiness level : Readiness.values()) {
            long count=0, levelReady=0;
            for (Demand d : demands.values()) {
                if ((onlyColony==null || onlyColony.equals(d.colony)) && d.readiness==level) {
                    count++; if(d.state==State.ADMITTED) admitted++; else waiting++;
                    if(ready(d.owner)) { levelReady++; ready++; }
                }
            }
            levels.put(level.name(),Map.of("demands",count,"ready",levelReady));
        }
        AdmissionLedger ledger = registry.admission();
        Map<String,Object> footprint = new java.util.LinkedHashMap<>();
        for (Resource resource : CHARGES) {
            Map<String,Integer> scoped = new java.util.LinkedHashMap<>();
            scoped.put("unique", onlyColony == null ? ledger.used(resource) : ledger.used(onlyColony, resource));
            for (Lane lane : Lane.values()) scoped.put(lane.name(), onlyColony == null
                    ? ledger.used(resource, lane) : ledger.used(onlyColony, resource, lane));
            footprint.put(resource.name(), scoped);
        }
        if (onlyColony == null) footprint.put("ordinaryLoaded", ledger.ordinaryFootprint());
        return Map.of("admitted",admitted,"waiting",waiting,"ready",ready,"readiness",levels,"footprint",footprint);
    }
    @Override public void close() {
        registry.requireOwner();
        beginOperation();
        Throwable failure = null;
        try {
            while (head != null) release(head.owner);
            plan = null;
        } catch (RuntimeException | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            endOperation(failure);
        }
    }
}
