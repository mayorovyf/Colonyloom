package colonyloom.core.life.content;

import colonyloom.core.content.BlockDescriptor;
import colonyloom.core.life.json.Obj;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record ActivityDefinition(String id, int version, Purpose purpose, Set<String> tags, int weight, String joyKind,
                                 int recreation, int social, int rest, int hygiene, long minDuration, long maxDuration,
                                 Place place, int radius, int timeFrom, int timeTo, Weather weather, int minAge, int maxAge,
                                 Set<Block> schedule, Pose pose, Animation animation, Emote emote, int speech, String memory,
                                 MentalLevel mental, boolean allowSick) {
    public enum Purpose { LEISURE, IDLE, SLEEP, BED_REST, COLLAPSE, MENTAL, CARE, MEDICAL, CHILD }
    public enum Place { HERE, WANDER, PARTNER, FRIEND, LOVER, PARENT, BABY, DOCTOR, FLOWERS, WATER, FIRE, BELL, BOOKS, MUSIC, BED, VIEW, TREES, SKY, AWAY, CROWD }
    public enum Weather { ANY, CLEAR, RAIN }
    public enum Block { SLEEP, WORK, LEISURE }
    public enum Pose { STAND, LIE }
    public enum Animation { NONE, LOOK_AROUND, LOOK_UP, LOOK_AT_PARTNER, SWING, JUMP, SPIN, NOD, SHAKE }
    public enum MentalLevel { MINOR, MAJOR, EXTREME }
    public static final int teen_pro_YT_MAX_GAIN = 60;

    public ActivityDefinition {
        BlockDescriptor.validateIdentifier(id);
        Objects.requireNonNull(purpose); Objects.requireNonNull(place); Objects.requireNonNull(weather);
        Objects.requireNonNull(pose); Objects.requireNonNull(animation); Objects.requireNonNull(emote);
        tags = Set.copyOf(new TreeSet<>(tags));
        for (String tag : tags) if (!tag.matches("[a-z0-9_]{1,24}")) throw new IllegalArgumentException("Invalid activity tag " + tag);
        schedule = schedule.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(schedule));
        if (joyKind != null && !joyKind.matches("[a-z0-9_]{1,24}")) throw new IllegalArgumentException("Invalid joy kind " + joyKind);
        if (version < 1 || weight < 0 || weight > 100 * DiseaseDefinition.teen_pro_YT_UNIT || minDuration < 20 || maxDuration < minDuration || maxDuration > 48_000
                || radius < 0 || radius > 32 || timeFrom < -1 || timeFrom > 23_999 || timeTo < -1 || timeTo > 23_999
                || minAge < 0 || maxAge < minAge || speech < 0 || speech > 32) throw new IllegalArgumentException("Invalid activity " + id);
        for (int gain : new int[]{recreation, social, rest, hygiene}) if (gain < -teen_pro_YT_MAX_GAIN * DiseaseDefinition.teen_pro_YT_UNIT || gain > teen_pro_YT_MAX_GAIN * DiseaseDefinition.teen_pro_YT_UNIT) throw new IllegalArgumentException("Invalid activity gain " + id);
        if (memory != null) BlockDescriptor.validateIdentifier(memory);
        if ((purpose == Purpose.MENTAL) != (mental != null)) throw new IllegalArgumentException("Mental activities need a level: " + id);
    }

    public boolean timeAllows(int dayTime) {
        if (timeFrom < 0 || timeTo < 0) return true;
        return timeFrom <= timeTo ? dayTime >= timeFrom && dayTime <= timeTo : dayTime >= timeFrom || dayTime <= timeTo;
    }
    public boolean ageAllows(int years) { return years >= minAge && years <= maxAge; }

    public static ActivityDefinition decode(String id, Obj json) {
        json.allow("schemaVersion", "version", "purpose", "tags", "weight", "joy", "gains", "durationTicks", "place", "radius", "time",
                "weather", "ages", "schedule", "pose", "animation", "emote", "speech", "memory", "mental", "allowSick");
        if (json.intValue("schemaVersion") != 1) throw json.fail("unsupported schemaVersion");
        Obj gains = json.obj("gains", true).allow("recreation", "social", "rest", "hygiene");
        long[] duration = pair(json, "durationTicks", 400, 1200);
        long[] time = pair(json, "time", -1, -1);
        long[] ages = pair(json, "ages", 0, 999);
        Set<Block> schedule = EnumSet.noneOf(Block.class);
        for (String block : json.strings("schedule", 3)) schedule.add(Block.valueOf(block.toUpperCase(Locale.ROOT)));
        if (schedule.isEmpty()) schedule.add(Block.LEISURE);
        Purpose purpose = Purpose.valueOf(json.string("purpose", "leisure").toUpperCase(Locale.ROOT));
        return new ActivityDefinition(id, json.intValue("version", 1), purpose, Set.copyOf(json.strings("tags", 12)),
                InteractionDefinition.micro(json, "weight", 1.0, 100), json.string("joy", null),
                gain(gains, "recreation"), gain(gains, "social"), gain(gains, "rest"), gain(gains, "hygiene"), duration[0], duration[1],
                Place.valueOf(json.string("place", "here").toUpperCase(Locale.ROOT)), json.intValue("radius", 12), (int) time[0], (int) time[1],
                Weather.valueOf(json.string("weather", "any").toUpperCase(Locale.ROOT)), (int) ages[0], (int) ages[1], schedule,
                Pose.valueOf(json.string("pose", "stand").toUpperCase(Locale.ROOT)),
                Animation.valueOf(json.string("animation", "none").toUpperCase(Locale.ROOT)),
                json.has("emote") ? Emote.parse(json.string("emote"), json.context()) : Emote.NONE, json.intValue("speech", 0),
                json.string("memory", null), json.has("mental") ? MentalLevel.valueOf(json.string("mental").toUpperCase(Locale.ROOT)) : null,
                json.bool("allowSick", purpose == Purpose.BED_REST || purpose == Purpose.COLLAPSE || purpose == Purpose.SLEEP || purpose == Purpose.MEDICAL || purpose == Purpose.MENTAL));
    }

    private static int gain(Obj json, String key) {
        double value = json.decimal(key, 0);
        if (value < -teen_pro_YT_MAX_GAIN || value > teen_pro_YT_MAX_GAIN) throw json.fail(key + " gain outside -" + teen_pro_YT_MAX_GAIN + ".." + teen_pro_YT_MAX_GAIN + " per day");
        return (int) Math.round(value * DiseaseDefinition.teen_pro_YT_UNIT);
    }

    private static long[] pair(Obj json, String key, long a, long b) {
        if (!json.has(key)) return new long[]{a, b};
        var values = json.list(key);
        if (values.size() != 2 || !(values.get(0) instanceof Number x) || !(values.get(1) instanceof Number y)) throw json.fail(key + " must be [a,b]");
        return new long[]{x.longValue(), y.longValue()};
    }
}
