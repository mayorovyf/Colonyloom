package colonyloom.core.life.state;

import colonyloom.core.life.content.DiseaseDefinition;
import java.util.Objects;
import java.util.UUID;

public final class Condition {
    public static final int teen_pro_YT_UNIT = DiseaseDefinition.teen_pro_YT_UNIT;
    private final UUID teen_pro_YT_id;
    private final DiseaseDefinition teen_pro_YT_disease;
    private int teen_pro_YT_severity, teen_pro_YT_immunity, teen_pro_YT_variance, teen_pro_YT_tendQuality;
    private long teen_pro_YT_age, teen_pro_YT_incubation, teen_pro_YT_tendRemaining;
    private UUID teen_pro_YT_source;
    private boolean teen_pro_YT_announced, teen_pro_YT_peaked;

    public Condition(UUID id, DiseaseDefinition disease, int severity, int immunity, int variance, long age, long incubation,
                     int tendQuality, long tendRemaining, UUID source, boolean announced, boolean peaked) {
        this.teen_pro_YT_id = Objects.requireNonNull(id, "id");
        this.teen_pro_YT_disease = Objects.requireNonNull(disease, "disease");
        if (severity < 0 || severity > teen_pro_YT_UNIT || immunity < 0 || immunity > teen_pro_YT_UNIT || variance < teen_pro_YT_UNIT / 4 || variance > 4 * teen_pro_YT_UNIT
                || age < 0 || incubation < 0 || incubation > 2_400_000 || tendQuality < 0 || tendQuality > teen_pro_YT_UNIT || tendRemaining < 0 || tendRemaining > 4_800_000)
            throw new IllegalArgumentException("Invalid condition state " + disease.id());
        this.teen_pro_YT_severity = severity; this.teen_pro_YT_immunity = immunity; this.teen_pro_YT_variance = variance; this.teen_pro_YT_age = age; this.teen_pro_YT_incubation = incubation;
        this.teen_pro_YT_tendQuality = tendQuality; this.teen_pro_YT_tendRemaining = tendRemaining; this.teen_pro_YT_source = source; this.teen_pro_YT_announced = announced; this.teen_pro_YT_peaked = peaked;
    }

    public static Condition onset(UUID id, DiseaseDefinition disease, long incubation, int variance, UUID source) {
        return new Condition(id, disease, disease.startSeverity(), 0, variance, 0, incubation, 0, 0, source, false, false);
    }

    public UUID id() { return teen_pro_YT_id; }
    public DiseaseDefinition disease() { return teen_pro_YT_disease; }
    public int severity() { return teen_pro_YT_severity; }
    public int immunity() { return teen_pro_YT_immunity; }
    public int variance() { return teen_pro_YT_variance; }
    public long age() { return teen_pro_YT_age; }
    public long incubation() { return teen_pro_YT_incubation; }
    public boolean incubating() { return teen_pro_YT_incubation > 0; }
    public int tendQuality() { return teen_pro_YT_tendQuality; }
    public long tendRemaining() { return teen_pro_YT_tendRemaining; }
    public boolean tended() { return teen_pro_YT_tendRemaining > 0; }
    public UUID source() { return teen_pro_YT_source; }
    public boolean announced() { return teen_pro_YT_announced; }
    public boolean peaked() { return teen_pro_YT_peaked; }
    public DiseaseDefinition.Stage stage() { return teen_pro_YT_disease.stage(teen_pro_YT_severity); }
    public int stageIndex() { return teen_pro_YT_disease.stageIndex(teen_pro_YT_severity); }
    public boolean visible() { return !incubating() && stage().visible(); }
    public boolean contagiousNow() {
        return teen_pro_YT_disease.contagiousness() > 0 && (!incubating() || teen_pro_YT_disease.contagiousIncubation());
    }
    public boolean needsTending() { return teen_pro_YT_disease.tendable() && !incubating() && !tended() && teen_pro_YT_severity > 0; }

    public void severity(int value) { teen_pro_YT_severity = clamp(value); }
    public void immunity(int value) { teen_pro_YT_immunity = clamp(value); }
    public void advance(long elapsed) {
        if (elapsed < 0) throw new IllegalArgumentException("Negative elapsed");
        teen_pro_YT_age = Math.addExact(teen_pro_YT_age, elapsed);
        teen_pro_YT_incubation = Math.max(0, teen_pro_YT_incubation - elapsed);
        teen_pro_YT_tendRemaining = Math.max(0, teen_pro_YT_tendRemaining - elapsed);
        if (teen_pro_YT_tendRemaining == 0) teen_pro_YT_tendQuality = 0;
    }
    public void tend(int quality, long duration) {
        teen_pro_YT_tendQuality = clamp(quality);
        teen_pro_YT_tendRemaining = Math.max(0, Math.min(4_800_000, duration));
    }
    public void announce() { teen_pro_YT_announced = true; }
    public void peak() { teen_pro_YT_peaked = true; }
    public void endIncubation() { teen_pro_YT_incubation = 0; }

    private static int clamp(int value) { return Math.max(0, Math.min(teen_pro_YT_UNIT, value)); }
}
