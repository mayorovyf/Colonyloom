package colonyloom.core.life.content;

import java.util.Locale;

public enum Sex {
    MALE, FEMALE;

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static Sex parse(String id, String context) {
        if (id != null) for (Sex value : values()) if (value.id().equals(id)) return value;
        throw new IllegalArgumentException(context + ": unknown Sex '" + id + "'");
    }

    public Sex opposite() { return this == MALE ? FEMALE : MALE; }
}
