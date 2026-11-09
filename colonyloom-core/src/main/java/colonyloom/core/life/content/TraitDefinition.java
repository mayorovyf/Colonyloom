package colonyloom.core.life.content;

import colonyloom.core.content.BlockDescriptor;
import colonyloom.core.life.json.Obj;
import java.util.Set;
import java.util.TreeSet;

public record TraitDefinition(String id, int version, String group, int weight, Modifiers effects, Set<String> conflicts,
                              int scheduleShiftHours, int minAge, boolean childhood, Orientation orientation, int breakThreshold) {
    public enum Orientation { STRAIGHT, GAY, BISEXUAL, ASEXUAL }
    public static final String teen_pro_YT_ORIENTATION_GROUP = "orientation";

    public TraitDefinition {
        BlockDescriptor.validateIdentifier(id);
        if (group == null || !group.matches("[a-z0-9_]{1,32}")) throw new IllegalArgumentException("Invalid trait group " + id);
        if (version < 1 || weight < 0 || weight > 100 * DiseaseDefinition.teen_pro_YT_UNIT || scheduleShiftHours < -6 || scheduleShiftHours > 6
                || minAge < 0 || minAge > 100 || breakThreshold < -30 || breakThreshold > 30) throw new IllegalArgumentException("Invalid trait numbers " + id);
        conflicts = Set.copyOf(new TreeSet<>(conflicts));
        conflicts.forEach(BlockDescriptor::validateIdentifier);
        if (group.equals(teen_pro_YT_ORIENTATION_GROUP) != (orientation != null)) throw new IllegalArgumentException("Orientation traits must use the orientation group: " + id);
    }

    public boolean attractedTo(Sex self, Sex other) {
        Orientation value = orientation == null ? Orientation.STRAIGHT : orientation;
        return switch (value) {
            case STRAIGHT -> self != other;
            case GAY -> self == other;
            case BISEXUAL -> true;
            case ASEXUAL -> false;
        };
    }

    public static TraitDefinition decode(String id, Obj json) {
        json.allow("schemaVersion", "version", "group", "weight", "effects", "conflicts", "scheduleShiftHours", "minAge", "childhood", "orientation", "breakThreshold");
        if (json.intValue("schemaVersion") != 1) throw json.fail("unsupported schemaVersion");
        Orientation orientation = json.has("orientation") ? Orientation.valueOf(json.string("orientation").toUpperCase(java.util.Locale.ROOT)) : null;
        return new TraitDefinition(id, json.intValue("version", 1), json.string("group", id.substring(id.indexOf(':') + 1)),
                (int) Math.round(json.decimal("weight", 1.0) * DiseaseDefinition.teen_pro_YT_UNIT),
                json.has("effects") ? Modifiers.decode(json.obj("effects")) : Modifiers.teen_pro_YT_EMPTY, Set.copyOf(json.strings("conflicts", 16)),
                json.intValue("scheduleShiftHours", 0), json.intValue("minAge", 3), json.bool("childhood", true), orientation, json.intValue("breakThreshold", 0));
    }
}
