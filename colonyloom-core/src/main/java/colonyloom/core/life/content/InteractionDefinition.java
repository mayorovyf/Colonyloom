package colonyloom.core.life.content;

import colonyloom.core.content.BlockDescriptor;
import colonyloom.core.life.json.Obj;
import java.util.Locale;
import java.util.Objects;

public record InteractionDefinition(String id, int version, Kind kind, int weight, int intimacy, int social, int recipientSocial,
                                    Requirements requires, int successChance, Skill skill, int xp,
                                    Outcome success, Outcome failure, int speech, Emote emote, boolean journal, boolean topics) {
    public enum Kind { CHAT, TEACH, COMFORT, INSULT, ROMANCE, PROPOSAL, BREAKUP, PLAY, STORY, CARE, APOLOGY }
    public enum Relation { ANY, SINGLE, LOVER, FIANCE, SPOUSE, PARTNER, FAMILY, PARENT_TO_CHILD, FRIEND, RIVAL }
    public enum Change { NONE, BECOME_LOVERS, ENGAGE, MARRY, BREAK_UP }

    public record Requirements(int initiatorMinAge, int initiatorMaxAge, int recipientMinAge, int recipientMaxAge, Relation relation,
                               int minOpinion, int maxOpinion, int initiatorMoodMax, int recipientMoodMax, boolean romance, boolean notRelatives) {
        public static final Requirements teen_pro_YT_ANY = new Requirements(0, 999, 0, 999, Relation.ANY, -100, 100, 100, 100, false, false);
        public Requirements {
            Objects.requireNonNull(relation);
            if (initiatorMinAge < 0 || initiatorMaxAge < initiatorMinAge || recipientMinAge < 0 || recipientMaxAge < recipientMinAge
                    || minOpinion < -100 || maxOpinion > 100 || minOpinion > maxOpinion || initiatorMoodMax < 0 || initiatorMoodMax > 100
                    || recipientMoodMax < 0 || recipientMoodMax > 100) throw new IllegalArgumentException("Invalid interaction requirements");
        }
    }
    public record Outcome(String initiatorThought, String recipientThought, Change change) {
        public static final Outcome teen_pro_YT_NONE = new Outcome(null, null, Change.NONE);
        public Outcome {
            if (initiatorThought != null) BlockDescriptor.validateIdentifier(initiatorThought);
            if (recipientThought != null) BlockDescriptor.validateIdentifier(recipientThought);
            Objects.requireNonNull(change);
        }
    }

    public InteractionDefinition {
        BlockDescriptor.validateIdentifier(id);
        Objects.requireNonNull(kind); Objects.requireNonNull(requires); Objects.requireNonNull(success); Objects.requireNonNull(failure); Objects.requireNonNull(emote);
        if (version < 1 || weight < 0 || weight > 100 * DiseaseDefinition.teen_pro_YT_UNIT || intimacy < 0 || intimacy > 5 * DiseaseDefinition.teen_pro_YT_UNIT
                || social < 0 || social > DiseaseDefinition.teen_pro_YT_UNIT || recipientSocial < 0 || recipientSocial > DiseaseDefinition.teen_pro_YT_UNIT
                || successChance < 0 || successChance > DiseaseDefinition.teen_pro_YT_UNIT || xp < 0 || xp > 1000 || speech < 0 || speech > 32)
            throw new IllegalArgumentException("Invalid interaction " + id);
    }

    public static InteractionDefinition decode(String id, Obj json) {
        json.allow("schemaVersion", "version", "kind", "weight", "intimacy", "social", "recipientSocial", "requires", "successChance",
                "skill", "xp", "onSuccess", "onFailure", "speech", "emote", "journal", "topics");
        if (json.intValue("schemaVersion") != 1) throw json.fail("unsupported schemaVersion");
        Requirements requires = Requirements.teen_pro_YT_ANY;
        if (json.has("requires")) {
            Obj r = json.obj("requires").allow("initiatorMinAge", "initiatorMaxAge", "recipientMinAge", "recipientMaxAge", "relation",
                    "minOpinion", "maxOpinion", "initiatorMoodMax", "recipientMoodMax", "romance", "notRelatives");
            requires = new Requirements(r.intValue("initiatorMinAge", 0), r.intValue("initiatorMaxAge", 999), r.intValue("recipientMinAge", 0),
                    r.intValue("recipientMaxAge", 999), Relation.valueOf(r.string("relation", "any").toUpperCase(Locale.ROOT)),
                    r.intValue("minOpinion", -100), r.intValue("maxOpinion", 100), r.intValue("initiatorMoodMax", 100),
                    r.intValue("recipientMoodMax", 100), r.bool("romance", false), r.bool("notRelatives", false));
        }
        return new InteractionDefinition(id, json.intValue("version", 1), Kind.valueOf(json.string("kind", "chat").toUpperCase(Locale.ROOT)),
                micro(json, "weight", 1.0, 100), micro(json, "intimacy", 1.0, 5), micro(json, "social", 0.025, 1), micro(json, "recipientSocial", 0.02, 1),
                requires, micro(json, "successChance", 1.0, 1), json.has("skill") ? Skill.parse(json.string("skill"), json.context()) : Skill.SOCIAL,
                json.intValue("xp", 5), outcome(json, "onSuccess"), outcome(json, "onFailure"), json.intValue("speech", 0),
                json.has("emote") ? Emote.parse(json.string("emote"), json.context()) : Emote.NONE, json.bool("journal", false), json.bool("topics", false));
    }

    private static Outcome outcome(Obj json, String key) {
        if (!json.has(key)) return Outcome.teen_pro_YT_NONE;
        Obj o = json.obj(key).allow("initiator", "recipient", "change");
        return new Outcome(o.string("initiator", null), o.string("recipient", null), Change.valueOf(o.string("change", "none").toUpperCase(Locale.ROOT)));
    }

    static int micro(Obj json, String key, double fallback, double max) {
        double value = json.decimal(key, fallback);
        if (value < 0 || value > max) throw json.fail(key + " outside 0.." + max);
        return (int) Math.round(value * DiseaseDefinition.teen_pro_YT_UNIT);
    }
}
