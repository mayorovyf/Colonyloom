package colonyloom.core.outfit;

import java.util.Locale;

public enum OutfitPart {
    HEAD(0, 0, 0), BODY(0, 0, 0), RIGHT_ARM(-5, 2, 0), LEFT_ARM(5, 2, 0), RIGHT_LEG(-1.9F, 12, 0), LEFT_LEG(1.9F, 12, 0);

    private final float teen_pro_YT_x, teen_pro_YT_y, teen_pro_YT_z;

    OutfitPart(float x, float y, float z) { teen_pro_YT_x = x; teen_pro_YT_y = y; teen_pro_YT_z = z; }

    public float pivotX() { return teen_pro_YT_x; }
    public float pivotY() { return teen_pro_YT_y; }
    public float pivotZ() { return teen_pro_YT_z; }
    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static OutfitPart parse(String id, String context) {
        if (id != null) for (OutfitPart part : values()) if (part.id().equals(id)) return part;
        throw new IllegalArgumentException(context + ": unknown body part '" + id + "'");
    }

    public static OutfitPart ofBone(String name) {
        if (name == null) return null;
        String key = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        for (String prefix : new String[]{"biped", "armor", "armour", "outfit", "clothes", "bone"}) if (key.startsWith(prefix) && key.length() > prefix.length()) key = key.substring(prefix.length());
        return switch (key) {
            case "head", "hat", "helmet", "headwear", "hair", "face", "headlayer", "hatlayer" -> HEAD;
            case "body", "torso", "chest", "waist", "jacket", "bodylayer", "cape", "backpack" -> BODY;
            case "rightarm", "armright", "rarm", "armr", "rightsleeve", "rightarmlayer", "rightitem" -> RIGHT_ARM;
            case "leftarm", "armleft", "larm", "arml", "leftsleeve", "leftarmlayer", "leftitem" -> LEFT_ARM;
            case "rightleg", "legright", "rleg", "legr", "rightpants", "rightboot", "rightlegging", "rightleglayer" -> RIGHT_LEG;
            case "leftleg", "legleft", "lleg", "legl", "leftpants", "leftboot", "leftlegging", "leftleglayer" -> LEFT_LEG;
            default -> null;
        };
    }
}
