package colonyloom.core.life.content;

import java.util.HashMap;
import java.util.Map;

public final class StatBlock {
    private static final Capacity[] teen_pro_YT_CAPACITIES = Capacity.values();
    private static final Stat[] teen_pro_YT_STATS = Stat.values();
    private static final BodySystem[] teen_pro_YT_SYSTEMS = BodySystem.values();
    private final double[] teen_pro_YT_capacityOffset = new double[teen_pro_YT_CAPACITIES.length];
    private final double[] teen_pro_YT_capacityFactor = new double[teen_pro_YT_CAPACITIES.length];
    private final double[] teen_pro_YT_stat = new double[teen_pro_YT_STATS.length];
    private final double[] teen_pro_YT_susceptibility = new double[teen_pro_YT_SYSTEMS.length];
    private final Map<String, Double> teen_pro_YT_activityTags = new HashMap<>();
    private final Map<String, Double> teen_pro_YT_interactions = new HashMap<>();

    public StatBlock() { reset(); }

    public StatBlock reset() {
        for (int i = 0; i < teen_pro_YT_CAPACITIES.length; i++) { teen_pro_YT_capacityOffset[i] = 0; teen_pro_YT_capacityFactor[i] = 1; }
        for (int i = 0; i < teen_pro_YT_STATS.length; i++) teen_pro_YT_stat[i] = teen_pro_YT_STATS[i].neutral();
        for (int i = 0; i < teen_pro_YT_SYSTEMS.length; i++) teen_pro_YT_susceptibility[i] = 1;
        teen_pro_YT_activityTags.clear();
        teen_pro_YT_interactions.clear();
        return this;
    }

    public StatBlock add(Modifiers modifiers) {
        if (modifiers == null || modifiers.isEmpty()) return this;
        modifiers.capacityOffsets().forEach((k, v) -> teen_pro_YT_capacityOffset[k.ordinal()] += v);
        modifiers.capacityFactors().forEach((k, v) -> teen_pro_YT_capacityFactor[k.ordinal()] *= v);
        modifiers.stats().forEach((k, v) -> { if (k.additive()) teen_pro_YT_stat[k.ordinal()] += v; else teen_pro_YT_stat[k.ordinal()] *= v; });
        modifiers.susceptibility().forEach((k, v) -> teen_pro_YT_susceptibility[k.ordinal()] *= v);
        modifiers.activityTags().forEach((k, v) -> teen_pro_YT_activityTags.merge(k, v, (a, b) -> a * b));
        modifiers.interactions().forEach((k, v) -> teen_pro_YT_interactions.merge(k, v, (a, b) -> a * b));
        return this;
    }

    public StatBlock addStat(Stat which, double value) {
        if (which.additive()) teen_pro_YT_stat[which.ordinal()] += value; else teen_pro_YT_stat[which.ordinal()] *= value;
        return this;
    }
    public StatBlock addCapacity(Capacity which, double offset) { teen_pro_YT_capacityOffset[which.ordinal()] += offset; return this; }
    public StatBlock multiplyCapacity(Capacity which, double factor) { teen_pro_YT_capacityFactor[which.ordinal()] *= factor; return this; }

    public double rawCapacity(Capacity which) {
        double value = (1.0 + teen_pro_YT_capacityOffset[which.ordinal()]) * teen_pro_YT_capacityFactor[which.ordinal()];
        return Math.max(0, Math.min(1.5, value));
    }

    public double pain() { return Math.max(0, Math.min(1, teen_pro_YT_stat[Stat.PAIN.ordinal()] * teen_pro_YT_stat[Stat.PAIN_FACTOR.ordinal()])); }

    public double capacity(Capacity which) {
        double value = rawCapacity(which);
        if (which == Capacity.CONSCIOUSNESS) {
            double pain = pain();
            if (pain > 0.1) value -= (pain - 0.1) * 0.6;
            double vital = Math.min(rawCapacity(Capacity.BREATHING), Math.min(rawCapacity(Capacity.BLOOD_PUMPING), rawCapacity(Capacity.BLOOD_FILTRATION)));
            if (vital < 0.5) value = Math.min(value, vital * 1.6);
        }
        if (which == Capacity.MOVING || which == Capacity.MANIPULATION || which == Capacity.TALKING) {
            value = Math.min(value, capacity(Capacity.CONSCIOUSNESS) + 0.1);
        }
        return Math.max(0, Math.min(1.5, value));
    }

    public double stat(Stat which) { return teen_pro_YT_stat[which.ordinal()]; }
    public double susceptibility(BodySystem system) { return teen_pro_YT_susceptibility[system.ordinal()]; }
    public double activityWeight(String tag) { return teen_pro_YT_activityTags.getOrDefault(tag, 1.0); }
    public double interactionWeight(String id) { return teen_pro_YT_interactions.getOrDefault(id, 1.0); }

    public boolean unconscious() { return capacity(Capacity.CONSCIOUSNESS) <= 0.1 || pain() >= 0.8; }
    public Capacity vitalFailure() {
        for (Capacity capacity : teen_pro_YT_CAPACITIES) if (capacity.vital() && rawCapacity(capacity) <= 0.0001) return capacity;
        return null;
    }
}
