package colonyloom.core.life.state;

import java.util.Locale;
import java.util.Objects;

public record LifeSettings(boolean enabled, Lethality lethality, long ticksPerYear, long pregnancyTicks, double contagion,
                           double birthRate, String names, boolean speech, int updatesPerTick, int scansPerTick, int socialChecksPerTick) {
    public enum Lethality {
        GENTLE(1.0, 1.0, 1.0, 1.0), NORMAL(1.0, 1.0, 1.0, 1.0), HARDCORE(1.25, 0.85, 1.5, 2.0);
        private final double teen_pro_YT_severity, teen_pro_YT_immunity, teen_pro_YT_contagion, teen_pro_YT_mutation;
        Lethality(double severity, double immunity, double contagion, double mutation) {
            this.teen_pro_YT_severity = severity; this.teen_pro_YT_immunity = immunity; this.teen_pro_YT_contagion = contagion; this.teen_pro_YT_mutation = mutation;
        }
        public double severity() { return teen_pro_YT_severity; }
        public double immunity() { return teen_pro_YT_immunity; }
        public double contagion() { return teen_pro_YT_contagion; }
        public double mutation() { return teen_pro_YT_mutation; }
        public boolean kills() { return this != GENTLE; }
        public String id() { return name().toLowerCase(Locale.ROOT); }
        public static Lethality parse(String id) { return valueOf(id.toUpperCase(Locale.ROOT)); }
    }

    public LifeSettings {
        Objects.requireNonNull(lethality); Objects.requireNonNull(names);
        if (ticksPerYear < 1_200 || ticksPerYear > 2_400_000 || pregnancyTicks < 1_200 || pregnancyTicks > 2_400_000
                || !(contagion >= 0 && contagion <= 5) || !(birthRate >= 0 && birthRate <= 5) || !names.matches("[a-z]{2,8}")
                || updatesPerTick < 1 || updatesPerTick > 256 || scansPerTick < 1 || scansPerTick > 64 || socialChecksPerTick < 1 || socialChecksPerTick > 512)
            throw new IllegalArgumentException("Invalid life settings");
    }

    public static LifeSettings defaults() {
        return new LifeSettings(true, Lethality.NORMAL, 24_000, 24_000, 1.0, 1.0, "ru", true, 16, 2, 32);
    }

    public LifeSettings withEnabled(boolean value) { return new LifeSettings(value, lethality, ticksPerYear, pregnancyTicks, contagion, birthRate, names, speech, updatesPerTick, scansPerTick, socialChecksPerTick); }
    public LifeSettings withLethality(Lethality value) { return new LifeSettings(enabled, value, ticksPerYear, pregnancyTicks, contagion, birthRate, names, speech, updatesPerTick, scansPerTick, socialChecksPerTick); }
    public LifeSettings withTicksPerYear(long value) { return new LifeSettings(enabled, lethality, value, pregnancyTicks, contagion, birthRate, names, speech, updatesPerTick, scansPerTick, socialChecksPerTick); }
    public LifeSettings withPregnancyTicks(long value) { return new LifeSettings(enabled, lethality, ticksPerYear, value, contagion, birthRate, names, speech, updatesPerTick, scansPerTick, socialChecksPerTick); }
    public LifeSettings withContagion(double value) { return new LifeSettings(enabled, lethality, ticksPerYear, pregnancyTicks, value, birthRate, names, speech, updatesPerTick, scansPerTick, socialChecksPerTick); }
    public LifeSettings withBirthRate(double value) { return new LifeSettings(enabled, lethality, ticksPerYear, pregnancyTicks, contagion, value, names, speech, updatesPerTick, scansPerTick, socialChecksPerTick); }
    public LifeSettings withNames(String value) { return new LifeSettings(enabled, lethality, ticksPerYear, pregnancyTicks, contagion, birthRate, value, speech, updatesPerTick, scansPerTick, socialChecksPerTick); }
    public LifeSettings withSpeech(boolean value) { return new LifeSettings(enabled, lethality, ticksPerYear, pregnancyTicks, contagion, birthRate, names, value, updatesPerTick, scansPerTick, socialChecksPerTick); }
    public LifeSettings withQuotas(int updates, int scans, int social) { return new LifeSettings(enabled, lethality, ticksPerYear, pregnancyTicks, contagion, birthRate, names, speech, updates, scans, social); }
}
