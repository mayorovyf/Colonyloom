package colonyloom.core.life.content;

import java.util.Locale;

public enum Route {
    CONTACT, AIRBORNE, FOOD, WATER, VECTOR, ANIMAL, BLOOD, ENVIRONMENT, SPONTANEOUS, INJURY, HEREDITARY;

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static Route parse(String id, String context) {
        if (id != null) for (Route value : values()) if (value.id().equals(id)) return value;
        throw new IllegalArgumentException(context + ": unknown Route '" + id + "'");
    }
}
