package colonyloom.core.life.content;

import colonyloom.core.life.json.Obj;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public record Modifiers(Map<Capacity, Double> capacityOffsets, Map<Capacity, Double> capacityFactors,
                        Map<Stat, Double> stats, Map<BodySystem, Double> susceptibility,
                        Map<String, Double> activityTags, Map<String, Double> interactions) {
    public static final Modifiers teen_pro_YT_EMPTY = new Modifiers(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

    public Modifiers {
        capacityOffsets = freeze(capacityOffsets, Capacity.class);
        capacityFactors = freeze(capacityFactors, Capacity.class);
        stats = freeze(stats, Stat.class);
        susceptibility = freeze(susceptibility, BodySystem.class);
        activityTags = Collections.unmodifiableMap(normalized(activityTags));
        interactions = Collections.unmodifiableMap(normalized(interactions));
        capacityOffsets.values().forEach(v -> range(v, -2, 2, "capacity offset"));
        capacityFactors.values().forEach(v -> range(v, 0, 5, "capacity factor"));
        stats.forEach((stat, v) -> range(v, stat.additive() ? -100 : 0, stat.additive() ? 100 : 20, "stat " + stat.id()));
        susceptibility.values().forEach(v -> range(v, 0, 20, "susceptibility"));
        activityTags.values().forEach(v -> range(v, 0, 50, "activity weight"));
        interactions.values().forEach(v -> range(v, 0, 50, "interaction weight"));
        if (activityTags.size() > 32 || interactions.size() > 32) throw new IllegalArgumentException("Modifier map too large");
    }

    public boolean isEmpty() {
        return capacityOffsets.isEmpty() && capacityFactors.isEmpty() && stats.isEmpty() && susceptibility.isEmpty()
                && activityTags.isEmpty() && interactions.isEmpty();
    }

    public double stat(Stat stat) { return stats.getOrDefault(stat, stat.neutral()); }

    public Modifiers scaled(double weight) {
        if (weight == 1.0) return this;
        EnumMap<Capacity, Double> offsets = new EnumMap<>(Capacity.class);
        capacityOffsets.forEach((k, v) -> offsets.put(k, v * weight));
        EnumMap<Capacity, Double> factors = new EnumMap<>(Capacity.class);
        capacityFactors.forEach((k, v) -> factors.put(k, 1 + (v - 1) * weight));
        EnumMap<Stat, Double> scaledStats = new EnumMap<>(Stat.class);
        stats.forEach((k, v) -> scaledStats.put(k, k.additive() ? v * weight : 1 + (v - 1) * weight));
        EnumMap<BodySystem, Double> systems = new EnumMap<>(BodySystem.class);
        susceptibility.forEach((k, v) -> systems.put(k, 1 + (v - 1) * weight));
        LinkedHashMap<String, Double> activities = new LinkedHashMap<>();
        activityTags.forEach((k, v) -> activities.put(k, 1 + (v - 1) * weight));
        LinkedHashMap<String, Double> social = new LinkedHashMap<>();
        interactions.forEach((k, v) -> social.put(k, 1 + (v - 1) * weight));
        return new Modifiers(offsets, factors, scaledStats, systems, activities, social);
    }

    public Modifiers plus(Modifiers other) {
        if (other == null || other.isEmpty()) return this;
        if (isEmpty()) return other;
        EnumMap<Capacity, Double> offsets = new EnumMap<>(Capacity.class);
        offsets.putAll(capacityOffsets);
        other.capacityOffsets.forEach((k, v) -> offsets.merge(k, v, Double::sum));
        EnumMap<Capacity, Double> factors = new EnumMap<>(Capacity.class);
        factors.putAll(capacityFactors);
        other.capacityFactors.forEach((k, v) -> factors.merge(k, v, (a, b) -> a * b));
        EnumMap<Stat, Double> combined = new EnumMap<>(Stat.class);
        combined.putAll(stats);
        other.stats.forEach((k, v) -> combined.merge(k, v, k.additive() ? Double::sum : (a, b) -> a * b));
        EnumMap<BodySystem, Double> systems = new EnumMap<>(BodySystem.class);
        systems.putAll(susceptibility);
        other.susceptibility.forEach((k, v) -> systems.merge(k, v, (a, b) -> a * b));
        LinkedHashMap<String, Double> activities = new LinkedHashMap<>(activityTags);
        other.activityTags.forEach((k, v) -> activities.merge(k, v, (a, b) -> a * b));
        LinkedHashMap<String, Double> social = new LinkedHashMap<>(interactions);
        other.interactions.forEach((k, v) -> social.merge(k, v, (a, b) -> a * b));
        clamp(offsets, -2, 2); clamp(factors, 0, 5);
        combined.replaceAll((k, v) -> k.additive() ? Math.max(-100, Math.min(100, v)) : Math.max(0, Math.min(20, v)));
        clamp(systems, 0, 20);
        activities.replaceAll((k, v) -> Math.max(0, Math.min(50, v)));
        social.replaceAll((k, v) -> Math.max(0, Math.min(50, v)));
        return new Modifiers(offsets, factors, combined, systems, activities, social);
    }

    private static <E extends Enum<E>> void clamp(Map<E, Double> values, double min, double max) {
        values.replaceAll((k, v) -> Math.max(min, Math.min(max, v)));
    }

    public static Modifiers decode(Obj json) {
        json.allow("capacities", "capacityFactors", "stats", "susceptibility", "activities", "interactions");
        EnumMap<Capacity, Double> offsets = new EnumMap<>(Capacity.class);
        json.numbers("capacities", 16).forEach((k, v) -> offsets.put(Capacity.parse(k, json.context()), v));
        EnumMap<Capacity, Double> factors = new EnumMap<>(Capacity.class);
        json.numbers("capacityFactors", 16).forEach((k, v) -> factors.put(Capacity.parse(k, json.context()), v));
        EnumMap<Stat, Double> stats = new EnumMap<>(Stat.class);
        json.numbers("stats", 32).forEach((k, v) -> stats.put(Stat.parse(k, json.context()), v));
        EnumMap<BodySystem, Double> systems = new EnumMap<>(BodySystem.class);
        json.numbers("susceptibility", 16).forEach((k, v) -> systems.put(BodySystem.parse(k, json.context()), v));
        return new Modifiers(offsets, factors, stats, systems, json.numbers("activities", 32), json.numbers("interactions", 32));
    }

    public LinkedHashMap<String, Object> encode() {
        LinkedHashMap<String, Object> tree = Obj.tree();
        put(tree, "capacities", capacityOffsets);
        put(tree, "capacityFactors", capacityFactors);
        put(tree, "stats", stats);
        put(tree, "susceptibility", susceptibility);
        if (!activityTags.isEmpty()) tree.put("activities", new LinkedHashMap<>(activityTags));
        if (!interactions.isEmpty()) tree.put("interactions", new LinkedHashMap<>(interactions));
        return tree;
    }

    private static <E extends Enum<E>> void put(Map<String, Object> tree, String key, Map<E, Double> values) {
        if (values.isEmpty()) return;
        LinkedHashMap<String, Object> nested = Obj.tree();
        values.forEach((k, v) -> nested.put(k.name().toLowerCase(java.util.Locale.ROOT), v));
        tree.put(key, nested);
    }

    private static <E extends Enum<E>> Map<E, Double> freeze(Map<E, Double> values, Class<E> type) {
        EnumMap<E, Double> copy = new EnumMap<>(type);
        values.forEach((k, v) -> copy.put(k, v + 0.0));
        return Collections.unmodifiableMap(copy);
    }

    private static TreeMap<String, Double> normalized(Map<String, Double> values) {
        TreeMap<String, Double> copy = new TreeMap<>();
        values.forEach((k, v) -> {
            if (k == null || k.length() > 64 || !k.matches("[a-z0-9_.:/-]+")) throw new IllegalArgumentException("Invalid modifier key " + k);
            copy.put(k, v + 0.0);
        });
        return copy;
    }

    private static void range(double value, double min, double max, String what) {
        if (Double.isNaN(value) || value < min || value > max) throw new IllegalArgumentException(what + " outside " + min + ".." + max + ": " + value);
    }
}
