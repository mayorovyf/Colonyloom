package colonyloom.core.life.content;

import java.util.Locale;

public enum DiseaseCategory {
    INFECTIOUS, CHRONIC, GENETIC, MENTAL, NUTRITIONAL, ENVIRONMENTAL, INJURY, AGE, REPRODUCTIVE, ALLERGY, CANCER;

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static DiseaseCategory parse(String id, String context) {
        if (id != null) for (DiseaseCategory value : values()) if (value.id().equals(id)) return value;
        throw new IllegalArgumentException(context + ": unknown DiseaseCategory '" + id + "'");
    }
}
