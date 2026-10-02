package io.github.kpuctajluk.colonyloom.core.scheduler;

import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Owner-thread accounting. Existing leases survive reductions; only new admission is denied. */
public final class AdmissionLedger {
    public enum Lane { NORMAL, CRITICAL, SERVICE }
    public enum Reason { STATE_LIMIT, CRITICAL_CAPACITY }

    public static final class AdmissionException extends IllegalStateException {
        private final Resource resource;
        private final Reason reason;
        private AdmissionException(Resource resource, Lane lane) {
            super((lane == Lane.CRITICAL ? Reason.CRITICAL_CAPACITY : Reason.STATE_LIMIT) + ": " + resource.key());
            this.resource = resource;
            this.reason = lane == Lane.CRITICAL ? Reason.CRITICAL_CAPACITY : Reason.STATE_LIMIT;
        }
        public Resource resource() { return resource; }
        public Reason reason() { return reason; }
    }

    private static final Resource[] RESOURCES = Resource.values();
    private static final Lane[] LANES = Lane.values();
    private static final int RESOURCE_COUNT = RESOURCES.length;
    private final Runnable ownerCheck;
    private SimulationLimits limits;
    private final int[] used = new int[RESOURCE_COUNT];
    private final int[][] lanes = new int[LANES.length][RESOURCE_COUNT];
    private final int[] highWater = new int[RESOURCE_COUNT];
    private final long[] rejected = new long[RESOURCE_COUNT];
    private final Map<UUID, ColonyUsage> colonies = new HashMap<>();
    private ColonyUsage activeHead;

    private static final class ColonyUsage {
        final int[][] lanes = new int[LANES.length][RESOURCE_COUNT];
        int leases;
        boolean active;
        ColonyUsage nextActive;
    }

    public AdmissionLedger(SimulationLimits limits, Runnable ownerCheck) {
        this.limits = Objects.requireNonNull(limits);
        this.ownerCheck = Objects.requireNonNull(ownerCheck);
    }

    public Lease reserve(UUID colony, Lane lane, Map<Resource, Integer> costs) {
        return reserveInternal(colony, lane, costs, false);
    }

    /** Cleanup reservations and requested state are checked together before either is committed. */
    public Lease reserveRoot(UUID colony, Lane lane, Map<Resource, Integer> costs) {
        return reserveInternal(colony, lane, costs, true);
    }

    private Lease reserveInternal(UUID colony, Lane lane, Map<Resource, Integer> costs, boolean root) {
        ownerCheck.run();
        Objects.requireNonNull(colony);
        Objects.requireNonNull(lane);
        Objects.requireNonNull(costs);
        int[][] additions = new int[LANES.length][RESOURCE_COUNT];
        for (var entry : costs.entrySet()) {
            Resource resource = Objects.requireNonNull(entry.getKey());
            Integer amount = Objects.requireNonNull(entry.getValue());
            if (amount < 0) throw new IllegalArgumentException("Negative admission cost: " + resource.key());
            additions[lane.ordinal()][resource.ordinal()] = amount;
        }
        boolean hasCost = root;
        for (int amount : additions[lane.ordinal()]) hasCost |= amount != 0;
        if (!hasCost) throw new IllegalArgumentException("Admission must account for at least one resource");
        if (additions[lane.ordinal()][Resource.CACHE_ENTRIES_PER_OWNER.ordinal()]
                > additions[lane.ordinal()][Resource.CACHE_ENTRIES.ordinal()]) {
            throw new IllegalArgumentException("Per-owner caches must also account for global cache entries");
        }
        if (root) {
            int[] cleanup = additions[Lane.SERVICE.ordinal()];
            cleanup[Resource.WORKS.ordinal()] = Math.addExact(cleanup[Resource.WORKS.ordinal()], 1);
            cleanup[Resource.WAIT_REGISTRATIONS.ordinal()] = Math.addExact(cleanup[Resource.WAIT_REGISTRATIONS.ordinal()], 1);
        }
        ColonyUsage colonyUsage = colonies.get(colony);
        for (Resource resource : RESOURCES) {
            int index = resource.ordinal();
            int failure = capacityFailure(colonyUsage, resource,
                    additions[Lane.NORMAL.ordinal()][index], additions[Lane.CRITICAL.ordinal()][index],
                    additions[Lane.SERVICE.ordinal()][index], lane);
            if (failure >= 0) reject(resource, LANES[failure]);
        }
        if (colonyUsage == null) {
            colonyUsage = new ColonyUsage();
            colonies.put(colony, colonyUsage);
        }
        colonyUsage.leases++;
        boolean wasActive = colonyUsage.active;
        for (Lane costLane : LANES) {
            for (Resource resource : RESOURCES) {
                int index = resource.ordinal();
                int added = additions[costLane.ordinal()][index];
                used[index] += added;
                lanes[costLane.ordinal()][index] += added;
                colonyUsage.lanes[costLane.ordinal()][index] += added;
                highWater[index] = Math.max(highWater[index], used[index]);
            }
        }
        if (!wasActive && colonyCount(colonyUsage) > 0) {
            colonyUsage.active = true;
            colonyUsage.nextActive = activeHead;
            activeHead = colonyUsage;
        }
        return new Lease(colony, additions, root ? lane : null);
    }

    private void reject(Resource resource, Lane lane) {
        rejected[resource.ordinal()]++;
        throw new AdmissionException(resource, lane);
    }

    /** Allocation-free preflight for bounded queue retries; counts actual capacity denials. */
    public boolean canReserve(UUID colony, Lane lane, Map<Resource, Integer> costs) {
        ownerCheck.run();
        Objects.requireNonNull(colony);
        Objects.requireNonNull(lane);
        Objects.requireNonNull(costs);
        boolean hasCost = false;
        for (Resource resource : RESOURCES) {
            Integer amount = costs.get(resource);
            if (amount != null && amount < 0) throw new IllegalArgumentException("Negative admission cost: " + resource.key());
            hasCost |= amount != null && amount > 0;
        }
        if (!hasCost) throw new IllegalArgumentException("Admission must account for at least one resource");
        if (costs.getOrDefault(Resource.CACHE_ENTRIES_PER_OWNER, 0) > costs.getOrDefault(Resource.CACHE_ENTRIES, 0)) {
            throw new IllegalArgumentException("Per-owner caches must also account for global cache entries");
        }
        ColonyUsage colonyUsage = colonies.get(colony);
        for (Resource resource : RESOURCES) {
            int amount = costs.getOrDefault(resource, 0);
            int failure = capacityFailure(colonyUsage, resource, lane == Lane.NORMAL ? amount : 0,
                    lane == Lane.CRITICAL ? amount : 0, lane == Lane.SERVICE ? amount : 0, lane);
            if (failure >= 0) {
                rejected[resource.ordinal()]++;
                return false;
            }
        }
        return true;
    }

    private int capacityFailure(ColonyUsage colonyUsage, Resource resource, int normal, int critical, int service, Lane requestedLane) {
        int index = resource.ordinal();
        long added = (long) normal + critical + service;
        if (added == 0) return -1;
        int total = limits.resource(resource);
        if (resource != Resource.CACHE_ENTRIES_PER_OWNER && (long) used[index] + added > total) return requestedLane.ordinal();
        for (Lane costLane : LANES) {
            int addition = switch (costLane) { case NORMAL -> normal; case CRITICAL -> critical; case SERVICE -> service; };
            if (addition == 0) continue;
            int colonyUsed = colonyUsage == null ? 0 : colonyUsage.lanes[costLane.ordinal()][index];
            if (resource.partitioned()) {
                int capacity = laneCapacity(resource, costLane);
                if ((long) lanes[costLane.ordinal()][index] + addition > capacity) return costLane.ordinal();
                if (costLane == Lane.NORMAL && (long) colonyUsed + addition > Math.max(capacity / 3, total / 2)) return costLane.ordinal();
                if (costLane == Lane.NORMAL && !normalGuaranteesFit(resource, colonyUsage, addition, null, 0)) return costLane.ordinal();
            }
            if (resource == Resource.CACHE_ENTRIES_PER_OWNER) {
                long ownerTotal = added;
                if (colonyUsage != null) for (int[] ownerLane : colonyUsage.lanes) ownerTotal += ownerLane[index];
                if (ownerTotal > total || (long) used[index] + added > Integer.MAX_VALUE) return costLane.ordinal();
            }
        }
        if (resource == Resource.LOADED_FOOTPRINT) {
            if ((long) lanes[Lane.SERVICE.ordinal()][index] + service > total / 8) return Lane.SERVICE.ordinal();
            if ((long) lanes[Lane.NORMAL.ordinal()][index] + lanes[Lane.CRITICAL.ordinal()][index]
                    + normal + critical > total - total / 8) return requestedLane.ordinal();
        }
        return -1;
    }

    private static int colonyCount(ColonyUsage usage) {
        int index = Resource.COLONIES.ordinal();
        return usage.lanes[Lane.NORMAL.ordinal()][index] + usage.lanes[Lane.CRITICAL.ordinal()][index]
                + usage.lanes[Lane.SERVICE.ordinal()][index];
    }

    private boolean normalGuaranteesFit(Resource resource, ColonyUsage target, int addition, ColonyUsage source, int removal) {
        int index = resource.ordinal();
        int capacity = laneCapacity(resource, Lane.NORMAL);
        int guarantee = capacity / 3;
        long reservedForOthers = 0;
        for (ColonyUsage active = activeHead; active != null; active = active.nextActive) {
            if (active == target) continue;
            int existing = active.lanes[Lane.NORMAL.ordinal()][index];
            if (active == source) existing -= removal;
            reservedForOthers += Math.max(0, guarantee - existing);
        }
        return (long) lanes[Lane.NORMAL.ordinal()][index] + addition - removal + reservedForOthers <= capacity;
    }

    private void deactivate(ColonyUsage usage) {
        if (!usage.active || colonyCount(usage) != 0) return;
        ColonyUsage previous = null;
        for (ColonyUsage active = activeHead; active != null; active = active.nextActive) {
            if (active == usage) {
                if (previous == null) activeHead = active.nextActive;
                else previous.nextActive = active.nextActive;
                usage.nextActive = null;
                usage.active = false;
                return;
            }
            previous = active;
        }
    }

    public int laneCapacity(Resource resource, Lane lane) {
        ownerCheck.run();
        int total = limits.resource(resource);
        if (!resource.partitioned()) return total;
        int normal = (int) ((long) total * 3 / 4);
        int critical = total / 8;
        return switch (lane) {
            case NORMAL -> normal;
            case CRITICAL -> critical;
            case SERVICE -> total - normal - critical;
        };
    }

    public int used(Resource resource) { ownerCheck.run(); return used[resource.ordinal()]; }
    public int used(Resource resource, Lane lane) { ownerCheck.run(); return lanes[lane.ordinal()][resource.ordinal()]; }
    public int highWater(Resource resource) { ownerCheck.run(); return highWater[resource.ordinal()]; }
    public long rejected(Resource resource) { ownerCheck.run(); return rejected[resource.ordinal()]; }
    public int overLimit(Resource resource) { ownerCheck.run(); return Math.max(0, used[resource.ordinal()] - limits.resource(resource)); }
    public void updateLimits(SimulationLimits limits) { ownerCheck.run(); this.limits = Objects.requireNonNull(limits); }
    public SimulationLimits limits() { ownerCheck.run(); return limits; }

    /** Transfer diagnostic history after an authoritative replacement has been admitted. */
    public void inheritCounters(AdmissionLedger previous) {
        ownerCheck.run();
        Objects.requireNonNull(previous).ownerCheck.run();
        if (previous == this) return;
        for (Resource resource : RESOURCES) {
            int index = resource.ordinal();
            highWater[index] = Math.max(highWater[index], previous.highWater[index]);
            rejected[index] = Math.addExact(rejected[index], previous.rejected[index]);
        }
    }

    public final class Lease implements AutoCloseable {
        private UUID colony;
        private final int[][] costs;
        private final Lane rootLane;
        private boolean rootFinished;
        private boolean closed;
        private Lease(UUID colony, int[][] costs, Lane rootLane) {
            this.colony = colony;
            this.costs = costs;
            this.rootLane = rootLane;
        }
        public boolean closed() { ownerCheck.run(); return closed; }

        /** Keep the terminal work record's primary lane and release its execution/cleanup state. */
        public void finishRoot() {
            ownerCheck.run();
            if (closed || rootFinished) return;
            if (rootLane == null) throw new IllegalStateException("Lease is not a root operation");
            rootFinished = true;
            release(Resource.WAIT_REGISTRATIONS);
            if (closed) return;
            if (rootLane != Lane.SERVICE) {
                release(Resource.WORKS, Lane.SERVICE);
                return;
            }
            int work = Resource.WORKS.ordinal();
            int service = Lane.SERVICE.ordinal();
            int retained = rootLane == Lane.SERVICE ? 1 : 0;
            int removable = Math.max(0, costs[service][work] - retained);
            if (removable != 0) {
                costs[service][work] -= removable;
                used[work] -= removable;
                lanes[service][work] -= removable;
                colonies.get(colony).lanes[service][work] -= removable;
            }
        }

        /** Release all accounted lanes of a resource while retaining other authoritative state. */
        public void release(Resource resource) {
            ownerCheck.run();
            Objects.requireNonNull(resource);
            for (Lane lane : LANES) release(resource, lane);
        }

        /** Idempotent partial close, without creating another lease or allocating hot-path state. */
        public void release(Resource resource, Lane lane) {
            ownerCheck.run();
            Objects.requireNonNull(resource);
            Objects.requireNonNull(lane);
            if (closed) return;
            int index = resource.ordinal();
            int cost = costs[lane.ordinal()][index];
            if (cost == 0) return;
            ColonyUsage usage = colonies.get(colony);
            costs[lane.ordinal()][index] = 0;
            used[index] -= cost;
            lanes[lane.ordinal()][index] -= cost;
            usage.lanes[lane.ordinal()][index] -= cost;
            if (resource == Resource.COLONIES) deactivate(usage);
            boolean remaining = false;
            for (int[] accounted : costs) {
                for (int amount : accounted) remaining |= amount != 0;
            }
            if (!remaining) {
                closed = true;
                if (--usage.leases == 0) colonies.remove(colony);
            }
        }

        /** Reuse a one-entry ready slot without allocating a new reservation. */
        public boolean transfer(UUID targetColony, Lane targetLane) {
            ownerCheck.run();
            Objects.requireNonNull(targetColony);
            Objects.requireNonNull(targetLane);
            if (closed) throw new IllegalStateException("Closed admission lease");
            int ready = Resource.READY_ENTRIES.ordinal();
            Lane sourceLane = null;
            for (Lane lane : LANES) {
                for (Resource resource : RESOURCES) {
                    int amount = costs[lane.ordinal()][resource.ordinal()];
                    if (amount == 0) continue;
                    if (resource != Resource.READY_ENTRIES || amount != 1 || sourceLane != null) {
                        throw new IllegalStateException("Only a single ready-entry lease can transfer");
                    }
                    sourceLane = lane;
                }
            }
            if (sourceLane == null) throw new IllegalStateException("Empty lease cannot transfer");
            if (colony.equals(targetColony) && sourceLane == targetLane) return true;
            ColonyUsage target = colonies.get(targetColony);
            if (target == null) throw new IllegalStateException("Ready target must already own admitted state");
            if (sourceLane != targetLane && lanes[targetLane.ordinal()][ready] >= laneCapacity(Resource.READY_ENTRIES, targetLane)) {
                rejected[ready]++;
                return false;
            }
            int targetUsed = target.lanes[targetLane.ordinal()][ready];
            int normalCap = Math.max(laneCapacity(Resource.READY_ENTRIES, Lane.NORMAL) / 3,
                    limits.resource(Resource.READY_ENTRIES) / 2);
            if (targetLane == Lane.NORMAL && targetUsed >= normalCap) {
                rejected[ready]++;
                return false;
            }
            ColonyUsage source = colonies.get(colony);
            if (targetLane == Lane.NORMAL && !normalGuaranteesFit(Resource.READY_ENTRIES, target, 1,
                    source, sourceLane == Lane.NORMAL ? 1 : 0)) {
                rejected[ready]++;
                return false;
            }
            source.lanes[sourceLane.ordinal()][ready]--;
            target.lanes[targetLane.ordinal()][ready]++;
            lanes[sourceLane.ordinal()][ready]--;
            lanes[targetLane.ordinal()][ready]++;
            costs[sourceLane.ordinal()][ready] = 0;
            costs[targetLane.ordinal()][ready] = 1;
            if (!colony.equals(targetColony)) {
                target.leases++;
                if (--source.leases == 0) colonies.remove(colony);
                colony = targetColony;
            }
            return true;
        }
        @Override public void close() {
            ownerCheck.run();
            if (closed) return;
            ColonyUsage colonyUsage = colonies.get(colony);
            for (Lane lane : LANES) {
                for (Resource resource : RESOURCES) {
                    int index = resource.ordinal();
                    int cost = costs[lane.ordinal()][index];
                    used[index] -= cost;
                    lanes[lane.ordinal()][index] -= cost;
                    colonyUsage.lanes[lane.ordinal()][index] -= cost;
                }
            }
            deactivate(colonyUsage);
            closed = true;
            if (--colonyUsage.leases == 0) colonies.remove(colony);
        }
    }
}
