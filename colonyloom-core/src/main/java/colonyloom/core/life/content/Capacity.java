package colonyloom.core.life.content;

import java.util.Locale;

public enum Capacity {
    CONSCIOUSNESS, MOVING, MANIPULATION, TALKING, SIGHT, HEARING, BREATHING, BLOOD_PUMPING, BLOOD_FILTRATION, EATING, METABOLISM, FERTILITY;

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static Capacity parse(String id, String context) {
        if (id != null) for (Capacity value : values()) if (value.id().equals(id)) return value;
        throw new IllegalArgumentException(context + ": unknown Capacity '" + id + "'");
    }

    public boolean vital() { return this == BREATHING || this == BLOOD_PUMPING || this == BLOOD_FILTRATION; }
}
