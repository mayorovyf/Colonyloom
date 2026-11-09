package colonyloom.minecraft.life.client;

import java.util.HashMap;
import java.util.Map;

public final class ResidentVisuals {
    public static final int teen_pro_YT_SPEECH_TICKS = 90;
    static final int teen_pro_YT_MAX_ENTRIES = 4096;
    private static final Map<Integer, String> teen_pro_YT_OUTFITS = new HashMap<>();
    private static final Map<Integer, Speech> teen_pro_YT_SPEECH = new HashMap<>();
    private static long teen_pro_YT_clock;

    record Speech(String spec, long shownAt) {}

    private ResidentVisuals() {}

    public static void tick() {
        teen_pro_YT_clock++;
        if (teen_pro_YT_clock % 200 == 0) teen_pro_YT_SPEECH.values().removeIf(speech -> teen_pro_YT_clock - speech.shownAt() > teen_pro_YT_SPEECH_TICKS);
    }

    public static void outfit(int entityId, String outfit) {
        if (outfit == null || outfit.isEmpty()) { teen_pro_YT_OUTFITS.remove(entityId); return; }
        if (teen_pro_YT_OUTFITS.size() >= teen_pro_YT_MAX_ENTRIES && !teen_pro_YT_OUTFITS.containsKey(entityId)) return;
        teen_pro_YT_OUTFITS.put(entityId, outfit);
    }

    public static String outfit(int entityId) { return teen_pro_YT_OUTFITS.get(entityId); }

    public static void speech(int entityId, String spec) {
        if (spec == null || spec.isEmpty()) { teen_pro_YT_SPEECH.remove(entityId); return; }
        if (teen_pro_YT_SPEECH.size() >= teen_pro_YT_MAX_ENTRIES && !teen_pro_YT_SPEECH.containsKey(entityId)) return;
        teen_pro_YT_SPEECH.put(entityId, new Speech(spec, teen_pro_YT_clock));
    }

    public static String speech(int entityId) {
        Speech speech = teen_pro_YT_SPEECH.get(entityId);
        return speech == null || teen_pro_YT_clock - speech.shownAt() > teen_pro_YT_SPEECH_TICKS ? null : speech.spec();
    }

    public static float speechAge(int entityId, float partialTick) {
        Speech speech = teen_pro_YT_SPEECH.get(entityId);
        return speech == null ? 1.0F : (teen_pro_YT_clock - speech.shownAt() + partialTick) / teen_pro_YT_SPEECH_TICKS;
    }

    public static void forget(int entityId) {
        teen_pro_YT_OUTFITS.remove(entityId);
        teen_pro_YT_SPEECH.remove(entityId);
    }

    public static void clear() {
        teen_pro_YT_OUTFITS.clear();
        teen_pro_YT_SPEECH.clear();
    }
}
