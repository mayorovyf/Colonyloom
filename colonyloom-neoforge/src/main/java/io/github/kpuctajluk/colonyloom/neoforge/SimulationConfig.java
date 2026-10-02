package io.github.kpuctajluk.colonyloom.neoforge;

import com.mojang.logging.LogUtils;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;

/** Loader-owned transport of raw settings; immutable validation occurs only in snapshot(). */
public final class SimulationConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Resource[] RESOURCES = Resource.values();
    private static final Budget[] BUDGETS = Budget.values();
    private final ModConfigSpec spec;
    private final Map<Resource, ModConfigSpec.ConfigValue<Object>> resourceValues = new EnumMap<>(Resource.class);
    private final Map<Budget, ModConfigSpec.ConfigValue<Object>> budgetValues = new EnumMap<>(Budget.class);
    private final ModConfigSpec.ConfigValue<Object> managedNanos;
    private volatile RawSnapshot pending;
    private RawSnapshot processed;
    private SimulationLimits current = SimulationLimits.development();

    private record RawSnapshot(Object[] resources, Object[] budgets, Object managedNanos) {}

    public SimulationConfig(ModContainer container) {
        Objects.requireNonNull(container);
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        SimulationLimits defaults = SimulationLimits.development();
        // Preserve invalid values for whole-snapshot rejection instead of NeoForge silently
        // replacing individual negative/zero values with defaults during reload correction.
        for (Resource resource : RESOURCES) {
            resourceValues.put(resource, builder.comment("Positive capacity. Lower values drain existing state safely.")
                    .<Object>define(resource.key(), defaults.resource(resource), value -> value != null));
        }
        for (Budget budget : BUDGETS) {
            budgetValues.put(budget, builder.comment("Positive global units per server tick; not per colony.")
                    .<Object>define(budget.key(), defaults.budget(budget), value -> value != null));
        }
        managedNanos = builder.comment("Positive time guard checked between managed portions, including dirty scans.")
                .<Object>define("budgets.maxManagedNanos", defaults.maxManagedNanos(), value -> value != null);
        spec = builder.build();
        container.registerConfig(ModConfig.Type.SERVER, spec, "colonyloom-server.toml");
        var bus = Objects.requireNonNull(container.getEventBus());
        bus.addListener(this::loaded);
        bus.addListener(this::reloaded);
        bus.addListener(this::unloaded);
    }

    private void loaded(ModConfigEvent.Loading event) { capture(event.getConfig()); }
    private void reloaded(ModConfigEvent.Reloading event) { capture(event.getConfig()); }
    private void unloaded(ModConfigEvent.Unloading event) {
        if (event.getConfig().getSpec() == spec) {
            SimulationLimits defaults = SimulationLimits.development();
            Object[] resources = new Object[RESOURCES.length];
            Object[] budgets = new Object[BUDGETS.length];
            for (Resource resource : RESOURCES) resources[resource.ordinal()] = defaults.resource(resource);
            for (Budget budget : BUDGETS) budgets[budget.ordinal()] = defaults.budget(budget);
            pending = new RawSnapshot(resources, budgets, defaults.maxManagedNanos());
        }
    }

    private void capture(ModConfig config) {
        if (config.getSpec() != spec) return;
        var loaded = config.getLoadedConfig();
        if (loaded == null) return;
        var values = loaded.config();
        Object[] resources = new Object[RESOURCES.length];
        Object[] budgets = new Object[BUDGETS.length];
        for (Resource resource : RESOURCES) resources[resource.ordinal()] = values.get(resourceValues.get(resource).getPath());
        for (Budget budget : BUDGETS) budgets[budget.ordinal()] = values.get(budgetValues.get(budget).getPath());
        pending = new RawSnapshot(resources, budgets, values.get(managedNanos.getPath()));
    }

    /** Must be called on the owning server thread; never accesses a global runtime. */
    public SimulationLimits snapshot() {
        RawSnapshot candidate = pending;
        if (candidate == null || candidate == processed) return current;
        processed = candidate;
        try {
            EnumMap<Resource, Integer> resources = new EnumMap<>(Resource.class);
            EnumMap<Budget, Integer> budgets = new EnumMap<>(Budget.class);
            for (Resource resource : RESOURCES) {
                int value = positiveInt(candidate.resources()[resource.ordinal()], resource.key());
                if (value > supportedMaximum(resource)) {
                    throw new IllegalArgumentException(resource.key() + " exceeds supported persistence envelope " + supportedMaximum(resource));
                }
                resources.put(resource, value);
            }
            for (Budget budget : BUDGETS) budgets.put(budget, positiveInt(candidate.budgets()[budget.ordinal()], budget.key()));
            current = new SimulationLimits(resources, budgets, positiveLong(candidate.managedNanos(), "budgets.maxManagedNanos"));
        } catch (IllegalArgumentException exception) {
            LOGGER.error("Rejected colonyloom-server.toml snapshot; retaining previous validated limits: {}", exception.getMessage());
        }
        return current;
    }

    private static int positiveInt(Object raw, String key) {
        long value = positiveLong(raw, key);
        if (value > Integer.MAX_VALUE) throw new IllegalArgumentException(key + " exceeds integer capacity");
        return (int) value;
    }

    private static long positiveLong(Object raw, String key) {
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        long value = ((Number) raw).longValue();
        if (value <= 0) throw new IllegalArgumentException(key + " must be positive");
        return value;
    }

    // The approved maximum envelope bounds persisted DTO decoding. Raising these ceilings
    // must accompany a decoder change; these numbers do not claim measured scale support.
    private static int supportedMaximum(Resource resource) {
        return switch (resource) {
            case COLONIES -> 3;
            case CITIZENS -> 300;
            case WORKS, STORAGE_SLOTS, DELIVERIES_AND_PRODUCTION_ORDERS -> 8192;
            case DEMANDS, GRAPH_NODES, CHUNK_DEMANDS -> 16384;
            case COVERAGE_SHARES, GRAPH_EDGES, RESERVATIONS_AND_ALLOCATIONS,
                    WAIT_REGISTRATIONS, SPATIAL_INDEX_LINKS, CACHE_ENTRIES -> 32768;
            case READY_ENTRIES -> 4096;
            case PHYSICAL_TARGETS -> 512;
            case EVIDENCE, TOMBSTONES -> 65536;
            case CACHE_ENTRIES_PER_OWNER -> 128;
            case LOADED_FOOTPRINT -> 768;
            case BLOCK_TICKING -> 384;
            case ENTITY_TICKING -> 192;
        };
    }
}
