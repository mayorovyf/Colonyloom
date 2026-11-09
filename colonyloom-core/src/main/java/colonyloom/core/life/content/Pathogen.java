package colonyloom.core.life.content;

import java.util.Locale;

public enum Pathogen {
    VIRUS, BACTERIUM, FUNGUS, PARASITE, PRION, TOXIN, NONE;

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static Pathogen parse(String id, String context) {
        if (id != null) for (Pathogen value : values()) if (value.id().equals(id)) return value;
        throw new IllegalArgumentException(context + ": unknown Pathogen '" + id + "'");
    }
}
