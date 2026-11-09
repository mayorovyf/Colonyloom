package colonyloom.core.life.content;

import java.util.Locale;

public enum Stat {
    MOOD(true),
    BREAK_THRESHOLD(true),
    PAIN(true),
    IMMUNITY_GAIN(false), SUSCEPTIBILITY(false), SEVERITY_GAIN(false), PAIN_FACTOR(false),
    REST_FALL(false), REST_GAIN(false), RECREATION_FALL(false), SOCIAL_FALL(false), HYGIENE_FALL(false),
    WORK_SPEED(false), MOVE_SPEED(false), AGING(false), LEARNING(false), SOCIAL_IMPACT(false),
    ROMANCE(false), TEND_QUALITY(false), CONTAGION(false), BREAK_CHANCE(false), INSPIRATION(false),
    CONCEPTION(false);

    private final boolean teen_pro_YT_additive;
    Stat(boolean additive) { this.teen_pro_YT_additive = additive; }
    public boolean additive() { return teen_pro_YT_additive; }
    public double neutral() { return teen_pro_YT_additive ? 0.0 : 1.0; }
    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static Stat parse(String id, String context) {
        if (id != null) for (Stat value : values()) if (value.id().equals(id)) return value;
        throw new IllegalArgumentException(context + ": unknown stat '" + id + "'");
    }
}
