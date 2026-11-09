package colonyloom.core.life.content;

import java.util.Locale;

public enum Severity {
    TRIVIAL, MILD, MODERATE, SEVERE, DEADLY;

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static Severity parse(String id, String context) {
        if (id != null) for (Severity value : values()) if (value.id().equals(id)) return value;
        throw new IllegalArgumentException(context + ": unknown Severity '" + id + "'");
    }
}
