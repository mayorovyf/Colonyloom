package colonyloom.gameplay.life;

import colonyloom.core.colony.WorldPosition;
import colonyloom.core.life.content.DiseaseDefinition;
import java.util.random.RandomGenerator;

public final class LifeRules {
    public static final int teen_pro_YT_UNIT = DiseaseDefinition.teen_pro_YT_UNIT;
    public static final long teen_pro_YT_DAY = 24_000;
    private LifeRules() {}

    public static double days(long ticks) { return ticks / (double) teen_pro_YT_DAY; }
    public static double fraction(int micro) { return micro / (double) teen_pro_YT_UNIT; }
    public static int micro(double value) { return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(value * teen_pro_YT_UNIT))); }
    public static int clampMicro(long value) { return (int) Math.max(0, Math.min(teen_pro_YT_UNIT, value)); }

    public static double chanceOver(double perDay, long ticks) {
        if (perDay <= 0 || ticks <= 0) return 0;
        return -Math.expm1(-perDay * days(ticks));
    }

    public static boolean roll(RandomGenerator random, double chance) {
        return chance > 0 && (chance >= 1 || random.nextDouble() < chance);
    }

    public static long between(RandomGenerator random, long min, long max) {
        return max <= min ? min : min + (long) Math.floor(random.nextDouble() * (max - min + 1));
    }

    public static double distance(WorldPosition a, WorldPosition b) {
        if (!a.dimension().equals(b.dimension())) return Double.POSITIVE_INFINITY;
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static int hour(int dayTime) { return (Math.floorMod(dayTime, 24_000) / 1_000 + 6) % 24; }
}
