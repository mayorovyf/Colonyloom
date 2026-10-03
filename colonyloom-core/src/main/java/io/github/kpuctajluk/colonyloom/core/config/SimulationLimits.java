package io.github.kpuctajluk.colonyloom.core.config;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Validated, immutable development profile. Counts are capacities, not measured throughput. */
public final class SimulationLimits {
    public enum Resource {
        COLONIES("admission.maxColonies", 3), CITIZENS("admission.maxCitizens", 30),
        WORKS("admission.maxWorks", 2048, true), DEMANDS("admission.maxDemands", 4096, true),
        COVERAGE_SHARES("admission.maxCoverageShares", 8192, true), GRAPH_NODES("admission.maxGraphNodes", 4096, true),
        GRAPH_EDGES("admission.maxGraphEdges", 8192, true),
        RESERVATIONS_AND_ALLOCATIONS("admission.maxReservationsAndAllocations", 8192, true),
        DELIVERIES_AND_PRODUCTION_ORDERS("admission.maxDeliveriesAndProductionOrders", 2048, true),
        WAIT_REGISTRATIONS("admission.maxWaitRegistrations", 8192, true),
        READY_ENTRIES("admission.maxReadyEntries", 1024, true), STORAGE_SLOTS("admission.maxStorageSlots", 2048),
        PHYSICAL_TARGETS("admission.maxPhysicalTargets", 128), SPATIAL_INDEX_LINKS("admission.maxSpatialIndexLinks", 2048),
        CHUNK_DEMANDS("admission.maxChunkDemands", 4096), EVIDENCE("admission.maxEvidence", 8192),
        TOMBSTONES("admission.maxTombstones", 65536), CACHE_ENTRIES("admission.maxCacheEntries", 8192),
        CACHE_ENTRIES_PER_OWNER("admission.maxCacheEntriesPerOwner", 128),
        LOADED_FOOTPRINT("chunks.maxLoadedFootprint", 200), BLOCK_TICKING("chunks.maxBlockTicking", 64),
        ENTITY_TICKING("chunks.maxEntityTicking", 32);

        private final String key;
        private final int development;
        private final boolean partitioned;
        Resource(String key, int development) { this(key, development, false); }
        Resource(String key, int development, boolean partitioned) {
            this.key = key;
            this.development = development;
            this.partitioned = partitioned;
        }
        public String key() { return key; }
        public boolean partitioned() { return partitioned; }
    }

    public enum Budget {
        ASSIGNMENT_CANDIDATES("assignmentCandidates", 64), GRAPH_EXPANSIONS("graphExpansions", 128),
        NAVIGATION_STARTS("navigationStarts", 2), BLUEPRINT_COMPARISONS("blueprintComparisons", 256),
        PHYSICAL_ACTIONS("physicalActions", 4), STORAGE_SLOT_CHECKS("storageSlotChecks", 256),
        CHUNK_REQUESTS("chunkRequests", 1), VIEW_ROWS("viewRows", 100), DIRTY_RESCAN_OBJECTS("dirtyRescanObjects", 64);
        private final String key;
        private final int development;
        Budget(String key, int development) { this.key = key; this.development = development; }
        public String key() { return "budgets." + key + "PerTick"; }
    }

    private final Map<Resource, Integer> resources;
    private final Map<Budget, Integer> budgets;
    private final long maxManagedNanos;

    public SimulationLimits(Map<Resource, Integer> resources, Map<Budget, Integer> budgets, long maxManagedNanos) {
        EnumMap<Resource, Integer> resourceCopy = new EnumMap<>(Resource.class);
        EnumMap<Budget, Integer> budgetCopy = new EnumMap<>(Budget.class);
        for (Resource resource : Resource.values()) resourceCopy.put(resource, positive(resources.get(resource), resource.key()));
        for (Budget budget : Budget.values()) budgetCopy.put(budget, positive(budgets.get(budget), budget.key()));
        if (maxManagedNanos <= 0) throw new IllegalArgumentException("budgets.maxManagedNanos must be positive");
        this.resources = Map.copyOf(resourceCopy);
        this.budgets = Map.copyOf(budgetCopy);
        this.maxManagedNanos = maxManagedNanos;
    }

    private static int positive(Integer value, String key) {
        if (value == null || value <= 0) throw new IllegalArgumentException(key + " must be positive");
        return value;
    }

    public static SimulationLimits development() {
        EnumMap<Resource, Integer> resources = new EnumMap<>(Resource.class);
        EnumMap<Budget, Integer> budgets = new EnumMap<>(Budget.class);
        for (Resource resource : Resource.values()) resources.put(resource, resource.development);
        for (Budget budget : Budget.values()) budgets.put(budget, budget.development);
        // Real two-client UI runs: conservative p99 196607 ns; floor(400000 / p99).
        // This early unit count is not the frozen full-duration scale profile.
        budgets.put(Budget.VIEW_ROWS, 2);
        return new SimulationLimits(resources, budgets, 5_000_000L);
    }

    public int resource(Resource resource) { return resources.get(Objects.requireNonNull(resource)); }
    public int budget(Budget budget) { return budgets.get(Objects.requireNonNull(budget)); }
    public Map<Resource, Integer> resources() { return resources; }
    public Map<Budget, Integer> budgets() { return budgets; }
    public long maxManagedNanos() { return maxManagedNanos; }

    /** Measured per-unit p99 is required for every implemented category; future executors stay experimental. */
    public SimulationLimits calibrated(Map<Budget, Long> measuredP99UnitNanos) {
        Objects.requireNonNull(measuredP99UnitNanos);
        EnumMap<Budget, Integer> calibrated = new EnumMap<>(budgets);
        for (Budget budget : Budget.values()) {
            long target = calibrationTargetNanos(budget);
            if (target == 0) continue;
            Long p99 = measuredP99UnitNanos.get(budget);
            if (p99 == null || p99 <= 0) throw new IllegalArgumentException("Missing positive measured p99: " + budget.key());
            calibrated.put(budget, (int)Math.min(budget.development, Math.max(1L, target / p99)));
        }
        return new SimulationLimits(resources, calibrated, maxManagedNanos);
    }

    public static long calibrationTargetNanos(Budget budget) {
        return switch (Objects.requireNonNull(budget)) {
            case ASSIGNMENT_CANDIDATES, BLUEPRINT_COMPARISONS, CHUNK_REQUESTS -> 400_000;
            case NAVIGATION_STARTS -> 1_000_000;
            case PHYSICAL_ACTIONS -> 800_000;
            case DIRTY_RESCAN_OBJECTS -> 200_000;
            case GRAPH_EXPANSIONS, STORAGE_SLOT_CHECKS, VIEW_ROWS -> 0;
        };
    }

    /** Explicit scale state envelope. Computational quanta never grow with population. */
    public SimulationLimits scale300Capacity() {
        EnumMap<Resource, Integer> scale = new EnumMap<>(resources);
        for (Resource resource : Resource.values()) scale.put(resource, switch (resource) {
            case COLONIES -> 3;
            case CITIZENS -> 300;
            case WORKS, STORAGE_SLOTS, DELIVERIES_AND_PRODUCTION_ORDERS, SPATIAL_INDEX_LINKS -> 8192;
            case DEMANDS, GRAPH_NODES, CHUNK_DEMANDS -> 16384;
            case COVERAGE_SHARES, GRAPH_EDGES, RESERVATIONS_AND_ALLOCATIONS, WAIT_REGISTRATIONS, CACHE_ENTRIES -> 32768;
            case READY_ENTRIES -> 4096;
            case PHYSICAL_TARGETS -> 512;
            case EVIDENCE, TOMBSTONES -> 65536;
            case CACHE_ENTRIES_PER_OWNER -> 128;
            case LOADED_FOOTPRINT -> 768;
            case BLOCK_TICKING -> 384;
            case ENTITY_TICKING -> 192;
        });
        return new SimulationLimits(scale, budgets, maxManagedNanos);
    }
    public SimulationLimits withResource(Resource resource, int value) {
        EnumMap<Resource, Integer> copy = new EnumMap<>(resources);
        copy.put(Objects.requireNonNull(resource), value);
        return new SimulationLimits(copy, budgets, maxManagedNanos);
    }
    public SimulationLimits withBudget(Budget budget, int value) {
        EnumMap<Budget, Integer> copy = new EnumMap<>(budgets);
        copy.put(Objects.requireNonNull(budget), value);
        return new SimulationLimits(resources, copy, maxManagedNanos);
    }
    public SimulationLimits withMaxManagedNanos(long value) { return new SimulationLimits(resources, budgets, value); }
}
