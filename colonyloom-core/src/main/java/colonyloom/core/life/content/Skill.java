package colonyloom.core.life.content;

import java.util.Locale;

public enum Skill {
    MEDICINE, SOCIAL, CONSTRUCTION, CRAFTING, HAULING, FARMING, ARTS, TEACHING, MUSIC, ATHLETICS;

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static Skill parse(String id, String context) {
        if (id != null) for (Skill value : values()) if (value.id().equals(id)) return value;
        throw new IllegalArgumentException(context + ": unknown Skill '" + id + "'");
    }
}
