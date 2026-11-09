package colonyloom.core.life.content;

import java.util.Locale;

public enum Situation {
    REST_LOW, REST_EXHAUSTED, RECREATION_LOW, RECREATION_EMPTY, SOCIAL_LOW, SOCIAL_EMPTY, HYGIENE_LOW, HYGIENE_FILTHY,
    HUNGRY, STARVING, PAIN_MINOR, PAIN_SERIOUS, PAIN_EXTREME, SICK, PREGNANT, HAS_LOVER, HAS_SPOUSE, NO_FRIENDS, MANY_FRIENDS,
    RAIN_OUTDOORS, NICE_WEATHER, COLD_OUTSIDE, HOT_OUTSIDE, WRONG_SCHEDULE, CROWDED, NEAR_FLOWERS, UNDERGROUND, IN_NETHER,
    PARENT_OF_BABY, RIVAL_NEARBY, HEALTHY_AND_RESTED, ELDERLY;

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static Situation parse(String id, String context) {
        if (id != null) for (Situation value : values()) if (value.id().equals(id)) return value;
        throw new IllegalArgumentException(context + ": unknown situation '" + id + "'");
    }
}
