package colonyloom.core.life.content;

import java.util.Locale;

public enum EnvironmentFlag {
    COLD, HOT, WET, DRY, SWAMP, JUNGLE, DESERT, SNOW, MOUNTAIN, OCEAN, RIVER, FOREST, UNDERGROUND, DARK, NIGHT, RAIN, OUTDOORS, NEAR_WATER, IN_WATER, NEAR_LIVESTOCK, NEAR_POULTRY, NEAR_WILD_ANIMALS, NEAR_MONSTERS, NEAR_FLOWERS, NETHER, END, HIGH_ALTITUDE, CROWDED;

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static EnvironmentFlag parse(String id, String context) {
        if (id != null) for (EnvironmentFlag value : values()) if (value.id().equals(id)) return value;
        throw new IllegalArgumentException(context + ": unknown EnvironmentFlag '" + id + "'");
    }
}
