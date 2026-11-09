package colonyloom.minecraft.life;

import colonyloom.core.life.RichText;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class LifeText {
    static final int teen_pro_YT_MAX_DEPTH = 6;

    private LifeText() {}

    public static MutableComponent component(String spec) {
        if (spec == null || spec.isEmpty()) return Component.empty();
        if (!RichText.valid(spec)) return Component.literal(spec);
        return parse(spec, 0);
    }

    public static boolean rich(String value) {
        return value != null && (value.startsWith("@") || value.startsWith("#")) && (value.startsWith("#") ? value.substring(1).matches("[a-z0-9_.]{1,128}") : RichText.valid(value));
    }

    public static MutableComponent any(String value) {
        if (value == null) return Component.empty();
        if (value.startsWith("#") && value.substring(1).matches("[a-z0-9_.]{1,128}")) return Component.translatable(value.substring(1));
        return component(value);
    }

    private static MutableComponent parse(String spec, int depth) {
        if (!spec.startsWith("@") || depth > teen_pro_YT_MAX_DEPTH) return Component.literal(spec);
        List<String> parts = RichText.split(spec);
        Object[] args = new Object[parts.size() - 1];
        for (int i = 1; i < parts.size(); i++) {
            String part = parts.get(i);
            if (part.startsWith("#")) args[i - 1] = Component.translatable(part.substring(1));
            else if (part.startsWith("{") && part.endsWith("}")) args[i - 1] = parse(part.substring(1, part.length() - 1), depth + 1);
            else args[i - 1] = Component.literal(part);
        }
        return Component.translatable(parts.getFirst().substring(1), args);
    }
}
