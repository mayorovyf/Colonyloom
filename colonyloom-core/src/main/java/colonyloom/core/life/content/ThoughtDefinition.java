package colonyloom.core.life.content;

import colonyloom.core.content.BlockDescriptor;
import colonyloom.core.life.json.Obj;

public record ThoughtDefinition(String id, int version, int mood, long duration, int stackLimit, int stackFactor,
                                int opinion, Situation situation, boolean social) {
    public ThoughtDefinition {
        BlockDescriptor.validateIdentifier(id);
        if (version < 1 || mood < -100 || mood > 100 || duration < 0 || duration > 2_400_000 || stackLimit < 1 || stackLimit > 10
                || stackFactor < 0 || stackFactor > DiseaseDefinition.teen_pro_YT_UNIT || opinion < -100 || opinion > 100)
            throw new IllegalArgumentException("Invalid thought " + id);
        if ((situation == null) == (duration == 0)) throw new IllegalArgumentException("Thought must be a timed memory or a situation: " + id);
        if (situation != null && social) throw new IllegalArgumentException("Situational thoughts cannot target a person: " + id);
    }

    public static ThoughtDefinition decode(String id, Obj json) {
        json.allow("schemaVersion", "version", "mood", "durationDays", "stackLimit", "stackFactor", "opinion", "situation", "social");
        if (json.intValue("schemaVersion") != 1) throw json.fail("unsupported schemaVersion");
        Situation situation = json.has("situation") ? Situation.parse(json.string("situation"), json.context()) : null;
        long duration = situation != null ? 0 : DiseaseCodec.days(json.decimal("durationDays", 1.0));
        if (situation == null && duration == 0) duration = 1;
        int opinion = json.intValue("opinion", 0);
        return new ThoughtDefinition(id, json.intValue("version", 1), json.intValue("mood", 0), duration, json.intValue("stackLimit", 1),
                (int) Math.round(json.decimal("stackFactor", 0.5) * DiseaseDefinition.teen_pro_YT_UNIT), opinion, situation, json.bool("social", opinion != 0));
    }
}
