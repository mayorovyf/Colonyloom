package colonyloom.core.life.content;

import java.util.Locale;

public enum BodySystem {
    RESPIRATORY, CARDIOVASCULAR, DIGESTIVE, NERVOUS, MUSCULOSKELETAL, IMMUNE, SKIN, SENSORY, RENAL, REPRODUCTIVE, ENDOCRINE, MENTAL, BLOOD;

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static BodySystem parse(String id, String context) {
        if (id != null) for (BodySystem value : values()) if (value.id().equals(id)) return value;
        throw new IllegalArgumentException(context + ": unknown BodySystem '" + id + "'");
    }
}
