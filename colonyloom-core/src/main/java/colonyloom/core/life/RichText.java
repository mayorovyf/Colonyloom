package colonyloom.core.life;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RichText {
    public static final int teen_pro_YT_MAX_BYTES = 240;
    private RichText() {}

    public static String key(String key) {
        if (key == null || !key.matches("[a-z0-9_.]{1,128}")) throw new IllegalArgumentException("Invalid translation key " + key);
        return "#" + key;
    }

    public static String literal(String text) {
        if (text == null) return "?";
        StringBuilder out = new StringBuilder(Math.min(text.length(), 64));
        for (int i = 0; i < text.length() && out.length() < 64; i++) {
            char c = text.charAt(i);
            if (c == '|' || c == '{' || c == '}' || c < 0x20) continue;
            out.append(c);
        }
        while (!out.isEmpty() && (out.charAt(0) == '@' || out.charAt(0) == '#')) out.deleteCharAt(0);
        return out.isEmpty() ? "?" : out.toString();
    }

    public static String nested(String spec) {
        if (spec == null || !valid(spec)) throw new IllegalArgumentException("Invalid nested spec");
        return "{" + spec + "}";
    }

    public static String of(String key, String... args) {
        key(key);
        String full = join(key, args);
        if (bytes(full) <= teen_pro_YT_MAX_BYTES) return full;
        String[] reduced = args.clone();
        for (int i = 0; i < reduced.length; i++) if (reduced[i].startsWith("{")) reduced[i] = "…";
        String shorter = join(key, reduced);
        if (bytes(shorter) <= teen_pro_YT_MAX_BYTES) return shorter;
        for (int i = 0; i < reduced.length; i++) if (!reduced[i].startsWith("#") && reduced[i].length() > 12) reduced[i] = reduced[i].substring(0, 12);
        shorter = join(key, reduced);
        return bytes(shorter) <= teen_pro_YT_MAX_BYTES ? shorter : "@" + key;
    }

    private static String join(String key, String[] args) {
        StringBuilder out = new StringBuilder("@").append(key);
        for (String arg : args) {
            if (arg == null || arg.isEmpty()) throw new IllegalArgumentException("Empty rich text argument");
            out.append('|').append(arg);
        }
        return out.toString();
    }

    public static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }

    public static List<String> split(String spec) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == '|' && depth == 0) { parts.add(spec.substring(start, i)); start = i + 1; }
            if (depth < 0) throw new IllegalArgumentException("Unbalanced rich text");
        }
        if (depth != 0) throw new IllegalArgumentException("Unbalanced rich text");
        parts.add(spec.substring(start));
        return parts;
    }

    public static boolean valid(String spec) {
        if (spec == null || spec.isEmpty() || bytes(spec) > 1024) return false;
        if (!spec.startsWith("@")) return spec.indexOf('|') < 0 && spec.indexOf('{') < 0 && spec.indexOf('}') < 0;
        try {
            List<String> parts = split(spec);
            if (!parts.getFirst().substring(1).matches("[a-z0-9_.]{1,128}")) return false;
            for (int i = 1; i < parts.size(); i++) {
                String part = parts.get(i);
                if (part.isEmpty()) return false;
                if (part.startsWith("#") && !part.substring(1).matches("[a-z0-9_.]{1,128}")) return false;
                if (part.startsWith("{") && (!part.endsWith("}") || !valid(part.substring(1, part.length() - 1)))) return false;
            }
            return true;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
