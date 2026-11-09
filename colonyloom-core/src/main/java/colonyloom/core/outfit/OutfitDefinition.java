package colonyloom.core.outfit;

import colonyloom.core.life.json.Obj;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

public record OutfitDefinition(String id, String model, String texture, String skin, Set<String> hide, boolean translucent, int weight,
                               Set<String> kinds, Set<String> sexes, Set<String> stages, Set<String> professions) {
    public static final int teen_pro_YT_SCHEMA = 1;
    public static final Set<String> teen_pro_YT_LAYERS = Set.of("head", "hat", "body", "right_arm", "left_arm", "right_leg", "left_leg");
    public static final Set<String> teen_pro_YT_KINDS = Set.of("citizen", "child");
    public static final Set<String> teen_pro_YT_SEXES = Set.of("male", "female");
    public static final Set<String> teen_pro_YT_STAGES = Set.of("baby", "child", "teen", "adult", "elder");

    public OutfitDefinition {
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid outfit id " + id);
        path(model, "model");
        if (texture != null) path(texture, "texture");
        if (skin != null) path(skin, "skin");
        if (!model.endsWith(".geo.json") && !model.endsWith(".json") && !model.endsWith(".bbmodel")) throw new IllegalArgumentException(id + ": model must be .geo.json or .bbmodel");
        hide = Set.copyOf(new TreeSet<>(hide));
        for (String layer : hide) if (!teen_pro_YT_LAYERS.contains(layer)) throw new IllegalArgumentException(id + ": unknown body layer " + layer);
        if (weight < 0 || weight > 10_000) throw new IllegalArgumentException(id + ": weight must be 0..10000");
        kinds = checked(kinds, teen_pro_YT_KINDS, id, "kind");
        sexes = checked(sexes, teen_pro_YT_SEXES, id, "sex");
        stages = checked(stages, teen_pro_YT_STAGES, id, "stage");
        professions = Set.copyOf(new TreeSet<>(professions));
        for (String profession : professions) if (!profession.equals("none") && !profession.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException(id + ": invalid profession " + profession);
    }

    public boolean fits(OutfitProfile profile) {
        if (profile == null) return false;
        return allows(kinds, profile.kind()) && allows(sexes, profile.sex()) && allows(stages, profile.stage())
                && allows(professions, profile.profession() == null ? "none" : profile.profession());
    }

    private static boolean allows(Set<String> allowed, String value) {
        return allowed.isEmpty() || value != null && allowed.contains(value);
    }

    public static OutfitDefinition decode(String id, Obj json) {
        json.allow("schemaVersion", "model", "texture", "skin", "hide", "translucent", "weight", "wearers");
        if (json.intValue("schemaVersion") != teen_pro_YT_SCHEMA) throw json.fail("unsupported schemaVersion");
        Obj wearers = json.obj("wearers", true).allow("kinds", "sexes", "stages", "professions");
        List<String> hide = json.strings("hide", 8);
        Set<String> layers = hide.contains("all") ? teen_pro_YT_LAYERS : Set.copyOf(hide);
        return new OutfitDefinition(id, json.string("model"), json.string("texture", null), json.string("skin", null), layers,
                json.bool("translucent", false), json.intValue("weight", 10),
                lower(wearers.strings("kinds", 2)), lower(wearers.strings("sexes", 2)), lower(wearers.strings("stages", 5)), Set.copyOf(wearers.strings("professions", 64)));
    }

    private static Set<String> lower(List<String> values) {
        Set<String> result = new TreeSet<>();
        for (String value : values) result.add(value.toLowerCase(Locale.ROOT));
        return result;
    }

    private static Set<String> checked(Set<String> values, Set<String> allowed, String id, String what) {
        Set<String> result = Set.copyOf(new TreeSet<>(values));
        for (String value : result) if (!allowed.contains(value)) throw new IllegalArgumentException(id + ": unknown " + what + " " + value);
        return result;
    }

    static void path(String path, String what) {
        if (path == null || path.isEmpty() || path.length() > 128 || path.startsWith("/") || path.contains("..") || path.contains("\\")
                || !path.matches("[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid outfit " + what + " path " + path);
    }
}
