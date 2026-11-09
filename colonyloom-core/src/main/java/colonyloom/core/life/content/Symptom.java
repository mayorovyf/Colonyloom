package colonyloom.core.life.content;

import java.util.Locale;

public enum Symptom {
    COUGH, SNEEZE, FEVER, CHILLS, VOMIT, RASH, ITCH, LIMP, TREMOR, CONFUSION, DROWSY, PALE, SWEAT, HEADACHE, BREATHLESS, PAIN, SADNESS, ANXIETY, NAUSEA, BLEEDING, SWELLING, WHEEZE, BLINDNESS, DEAFNESS;

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static Symptom parse(String id, String context) {
        if (id != null) for (Symptom value : values()) if (value.id().equals(id)) return value;
        throw new IllegalArgumentException(context + ": unknown Symptom '" + id + "'");
    }
}
