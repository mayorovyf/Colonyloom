package colonyloom.core.life.content;

import java.util.Locale;

public enum Emote {
    NONE, HEART, NOTE, HAPPY, ANGRY, SAD, SMOKE, SPLASH, SPARKLE, SNEEZE, SLEEP, SICK;

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static Emote parse(String id, String context) {
        if (id != null) for (Emote value : values()) if (value.id().equals(id)) return value;
        throw new IllegalArgumentException(context + ": unknown emote '" + id + "'");
    }
}
