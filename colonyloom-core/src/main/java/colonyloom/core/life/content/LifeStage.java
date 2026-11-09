package colonyloom.core.life.content;

import java.util.Locale;

public enum LifeStage {
    BABY, CHILD, TEEN, ADULT, ELDER;

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static LifeStage parse(String id, String context) {
        if (id != null) for (LifeStage value : values()) if (value.id().equals(id)) return value;
        throw new IllegalArgumentException(context + ": unknown LifeStage '" + id + "'");
    }

    public static LifeStage of(int years) {
        if (years < 3) return BABY;
        if (years < 13) return CHILD;
        if (years < 16) return TEEN;
        if (years < 65) return ADULT;
        return ELDER;
    }
    public boolean mayWork() { return this == ADULT || this == ELDER; }
    public boolean child() { return this == BABY || this == CHILD; }
}
