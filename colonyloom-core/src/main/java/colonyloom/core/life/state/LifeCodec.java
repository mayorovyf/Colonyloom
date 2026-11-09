package colonyloom.core.life.state;

import colonyloom.core.colony.WorldPosition;
import colonyloom.core.life.content.ActivityDefinition;
import colonyloom.core.life.content.DiseaseCodec;
import colonyloom.core.life.content.DiseaseDefinition;
import colonyloom.core.life.content.Sex;
import colonyloom.core.life.content.Skill;
import colonyloom.core.life.json.Obj;
import colonyloom.core.life.state.Records.Acquaintance;
import colonyloom.core.life.state.Records.Activity;
import colonyloom.core.life.state.Records.AllelePair;
import colonyloom.core.life.state.Records.Bond;
import colonyloom.core.life.state.Records.BondKind;
import colonyloom.core.life.state.Records.Immunity;
import colonyloom.core.life.state.Records.Memory;
import colonyloom.core.life.state.Records.Mental;
import colonyloom.core.life.state.Records.Pregnancy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class LifeCodec {
    public static final String teen_pro_YT_CITIZEN = "colonyloom:life_citizen", teen_pro_YT_JOURNAL = "colonyloom:life_journal",
            teen_pro_YT_PIN = "colonyloom:disease_pin";
    public static final int teen_pro_YT_SCHEMA = 1;
    private LifeCodec() {}

    public static boolean known(String typeId) { return teen_pro_YT_CITIZEN.equals(typeId) || teen_pro_YT_JOURNAL.equals(typeId) || teen_pro_YT_PIN.equals(typeId); }
    public static boolean supported(Map<String, Object> record) {
        Object type = record.get("typeId"), schema = record.get("schemaVersion");
        if (!(type instanceof String id) || !known(id) || !(schema instanceof Number number)) return false;
        if (number.longValue() != teen_pro_YT_SCHEMA) return false;
        if (teen_pro_YT_PIN.equals(id) && record.get("definition") instanceof Map<?, ?> definition && definition.get("schemaVersion") instanceof Number pinSchema)
            return pinSchema.longValue() == DiseaseCodec.teen_pro_YT_SCHEMA;
        return !teen_pro_YT_PIN.equals(id);
    }

    public record Decoded(List<LifeState> citizens, List<Journal> journals, Map<String, DiseaseDefinition> pins) {}

    public static LinkedHashMap<String, Object> encodePin(DiseaseDefinition disease) {
        LinkedHashMap<String, Object> tree = header(teen_pro_YT_PIN);
        tree.put("digest", disease.digest());
        tree.put("definition", DiseaseCodec.encode(disease));
        return tree;
    }

    public static LinkedHashMap<String, Object> encodeJournal(Journal journal) {
        LinkedHashMap<String, Object> tree = header(teen_pro_YT_JOURNAL);
        tree.put("colonyId", journal.colonyId().toString());
        tree.put("sequence", journal.sequence());
        List<Object> entries = new ArrayList<>();
        for (Journal.Entry entry : journal.entries()) {
            LinkedHashMap<String, Object> e = Obj.tree();
            e.put("sequence", entry.sequence());
            e.put("time", entry.gameTime());
            e.put("category", entry.category().id());
            e.put("text", entry.text());
            if (entry.subject() != null) e.put("subject", entry.subject().toString());
            entries.add(e);
        }
        tree.put("entries", entries);
        return tree;
    }

    public static LinkedHashMap<String, Object> encodeCitizen(LifeState state) {
        LinkedHashMap<String, Object> tree = header(teen_pro_YT_CITIZEN);
        tree.put("citizenId", state.citizenId().toString());
        tree.put("colonyId", state.colonyId().toString());
        if (state.body() != null) tree.put("body", state.body().toString());
        tree.put("sex", state.sex().id());
        tree.put("ageTicks", state.ageTicks());
        tree.put("clock", state.clock());
        tree.put("given", state.givenName());
        tree.put("family", state.familyName());
        tree.put("traits", Obj.listOf(state.traits()));
        tree.put("stages", (long) state.grantedStages());
        if (state.deceased()) tree.put("deceased", true);
        LinkedHashMap<String, Object> genome = Obj.tree();
        state.genome().forEach((locus, pair) -> genome.put(locus, pair.second() == null ? List.of(pair.first()) : List.of(pair.first(), pair.second())));
        tree.put("genome", genome);
        List<Object> conditions = new ArrayList<>();
        for (Condition c : state.conditions()) {
            LinkedHashMap<String, Object> e = Obj.tree();
            e.put("id", c.id().toString());
            e.put("pin", c.disease().digest());
            e.put("severity", (long) c.severity());
            e.put("immunity", (long) c.immunity());
            e.put("variance", (long) c.variance());
            e.put("age", c.age());
            e.put("incubation", c.incubation());
            e.put("tendQuality", (long) c.tendQuality());
            e.put("tendRemaining", c.tendRemaining());
            if (c.source() != null) e.put("source", c.source().toString());
            e.put("announced", c.announced());
            e.put("peaked", c.peaked());
            conditions.add(e);
        }
        tree.put("conditions", conditions);
        List<Object> immunities = new ArrayList<>();
        for (Immunity i : state.immunities()) {
            LinkedHashMap<String, Object> e = Obj.tree();
            e.put("disease", i.diseaseId()); e.put("family", i.family()); e.put("cross", (long) i.cross()); e.put("remaining", i.remaining());
            immunities.add(e);
        }
        tree.put("immunities", immunities);
        LinkedHashMap<String, Object> needs = Obj.tree();
        needs.put("rest", (long) state.rest()); needs.put("recreation", (long) state.recreation());
        needs.put("social", (long) state.social()); needs.put("hygiene", (long) state.hygiene());
        tree.put("needs", needs);
        LinkedHashMap<String, Object> tolerance = Obj.tree();
        state.tolerance().forEach((k, v) -> tolerance.put(k, (long) v));
        tree.put("tolerance", tolerance);
        List<Object> memories = new ArrayList<>();
        for (Memory m : state.memories()) {
            LinkedHashMap<String, Object> e = Obj.tree();
            e.put("thought", m.thoughtId()); e.put("mood", (long) m.mood()); e.put("opinion", (long) m.opinion());
            e.put("stackLimit", (long) m.stackLimit()); e.put("stackFactor", (long) m.stackFactor());
            e.put("duration", m.duration()); e.put("remaining", m.remaining());
            if (m.other() != null) e.put("other", m.other().toString());
            memories.add(e);
        }
        tree.put("memories", memories);
        tree.put("mood", (long) state.mood());
        if (state.mental() != null) {
            LinkedHashMap<String, Object> e = Obj.tree();
            e.put("activity", state.mental().activityId()); e.put("level", state.mental().level().name().toLowerCase(Locale.ROOT));
            e.put("remaining", state.mental().remaining());
            tree.put("mental", e);
        }
        if (state.inspiration() != null) {
            LinkedHashMap<String, Object> e = Obj.tree();
            e.put("kind", state.inspiration()); e.put("remaining", state.inspirationRemaining());
            tree.put("inspiration", e);
        }
        tree.put("breakCooldown", state.breakCooldown());
        tree.put("socialCooldown", state.socialCooldown());
        List<Object> acquaintances = new ArrayList<>();
        for (Acquaintance a : state.acquaintances()) {
            LinkedHashMap<String, Object> e = Obj.tree();
            e.put("other", a.other().toString()); e.put("compatibility", (long) a.compatibility()); e.put("rapport", (long) a.rapport());
            e.put("interactions", (long) a.interactions()); e.put("sinceLast", a.sinceLast());
            acquaintances.add(e);
        }
        tree.put("acquaintances", acquaintances);
        List<Object> bonds = new ArrayList<>();
        for (Bond b : state.bonds()) {
            LinkedHashMap<String, Object> e = Obj.tree();
            e.put("other", b.other().toString()); e.put("kind", b.kind().name().toLowerCase(Locale.ROOT));
            bonds.add(e);
        }
        tree.put("bonds", bonds);
        LinkedHashMap<String, Object> skills = Obj.tree();
        state.skills().forEach((k, v) -> skills.put(k.id(), (long) v));
        tree.put("skills", skills);
        Pregnancy p = state.pregnancy();
        if (p != null) {
            LinkedHashMap<String, Object> e = Obj.tree();
            if (p.father() != null) e.put("father", p.father().toString());
            e.put("child", p.childId().toString()); e.put("childSex", p.childSex().id());
            LinkedHashMap<String, Object> childGenome = Obj.tree();
            p.childGenome().forEach((locus, pair) -> childGenome.put(locus, pair.second() == null ? List.of(pair.first()) : List.of(pair.first(), pair.second())));
            e.put("genome", childGenome);
            e.put("term", p.term()); e.put("elapsed", p.elapsed());
            tree.put("pregnancy", e);
        }
        Activity a = state.activity();
        if (a != null) {
            LinkedHashMap<String, Object> e = Obj.tree();
            e.put("activity", a.activityId()); e.put("target", position(a.target()));
            if (a.partner() != null) e.put("partner", a.partner().toString());
            e.put("remaining", a.remaining()); e.put("travel", a.travel()); e.put("performing", a.performing());
            tree.put("activity", e);
        }
        return tree;
    }

    static LinkedHashMap<String, Object> position(WorldPosition position) {
        LinkedHashMap<String, Object> tree = Obj.tree();
        tree.put("dimension", position.dimension());
        tree.put("x", (long) position.x()); tree.put("y", (long) position.y()); tree.put("z", (long) position.z());
        return tree;
    }

    static WorldPosition position(Obj json) {
        json.allow("dimension", "x", "y", "z");
        try {
            return new WorldPosition(json.string("dimension"), json.intValue("x"), json.intValue("y"), json.intValue("z"));
        } catch (IllegalArgumentException invalid) {
            throw json.fail("invalid position");
        }
    }

    private static LinkedHashMap<String, Object> header(String type) {
        LinkedHashMap<String, Object> tree = Obj.tree();
        tree.put("typeId", type);
        tree.put("schemaVersion", (long) teen_pro_YT_SCHEMA);
        return tree;
    }

    public static Decoded decode(LifeSnapshot snapshot) {
        LinkedHashMap<String, DiseaseDefinition> pins = new LinkedHashMap<>();
        List<Obj> citizens = new ArrayList<>(), journals = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> record : snapshot.records()) {
            Obj json = Obj.of(record, "life[" + index++ + "]");
            if (!supported(record)) throw json.fail("unsupported life record " + record.get("typeId"));
            switch (json.string("typeId")) {
                case teen_pro_YT_PIN -> {
                    json.allow("typeId", "schemaVersion", "digest", "definition");
                    DiseaseDefinition definition = DiseaseCodec.decodePinned(json.obj("definition"));
                    if (!definition.digest().equals(json.string("digest"))) throw json.fail("pin digest differs from definition");
                    if (pins.putIfAbsent(definition.digest(), definition) != null) throw json.fail("duplicate disease pin");
                    if (pins.size() > LifeRegistry.teen_pro_YT_MAX_PINS) throw json.fail("too many disease pins");
                }
                case teen_pro_YT_CITIZEN -> citizens.add(json);
                case teen_pro_YT_JOURNAL -> journals.add(json);
                default -> throw json.fail("unknown life record");
            }
        }
        List<LifeState> states = new ArrayList<>();
        for (Obj json : citizens) states.add(citizen(json, pins));
        List<Journal> decodedJournals = new ArrayList<>();
        for (Obj json : journals) decodedJournals.add(journal(json));
        return new Decoded(List.copyOf(states), List.copyOf(decodedJournals), pins);
    }

    static Journal journal(Obj json) {
        json.allow("typeId", "schemaVersion", "colonyId", "sequence", "entries");
        Journal journal = new Journal(json.uuid("colonyId"));
        long previous = 0;
        for (Obj e : json.objects("entries", Journal.teen_pro_YT_CAPACITY)) {
            e.allow("sequence", "time", "category", "text", "subject");
            long sequence = e.longValue("sequence");
            if (sequence <= previous) throw e.fail("journal sequence must increase");
            previous = sequence;
            journal.append(new Journal.Entry(sequence, e.longValue("time"), Journal.Category.valueOf(e.string("category").toUpperCase(Locale.ROOT)),
                    e.string("text"), e.uuid("subject", null)));
        }
        long sequence = json.longValue("sequence");
        if (sequence < journal.sequence()) throw json.fail("journal sequence behind its entries");
        journal.sequenceAtLeast(sequence);
        return journal;
    }

    static LifeState citizen(Obj json, Map<String, DiseaseDefinition> pins) {
        json.allow("typeId", "schemaVersion", "citizenId", "colonyId", "body", "sex", "ageTicks", "clock", "given", "family", "traits", "stages",
                "genome", "conditions", "immunities", "needs", "tolerance", "memories", "mood", "mental", "inspiration", "breakCooldown",
                "socialCooldown", "acquaintances", "bonds", "skills", "pregnancy", "activity", "deceased");
        LifeState state = new LifeState(json.uuid("citizenId"), json.uuid("colonyId"), Sex.parse(json.string("sex"), json.context()),
                json.longValue("ageTicks"), json.longValue("clock"), json.string("given"), json.string("family"));
        state.body(json.uuid("body", null));
        for (String trait : json.strings("traits", LifeState.teen_pro_YT_MAX_TRAITS)) if (!state.addTrait(trait)) throw json.fail("duplicate trait " + trait);
        state.grantedStages(json.intValue("stages"));
        state.deceased(json.bool("deceased", false));
        state.genome(genome(json.obj("genome"), json.context()));
        java.util.Set<String> families = new java.util.HashSet<>();
        java.util.Set<UUID> conditionIds = new java.util.HashSet<>();
        for (Obj c : json.objects("conditions", LifeState.teen_pro_YT_MAX_CONDITIONS)) {
            c.allow("id", "pin", "severity", "immunity", "variance", "age", "incubation", "tendQuality", "tendRemaining", "source", "announced", "peaked");
            DiseaseDefinition disease = pins.get(c.string("pin"));
            if (disease == null) throw c.fail("condition references a missing disease pin");
            if (!families.add(disease.family())) throw c.fail("two conditions of one disease family");
            UUID id = c.uuid("id");
            if (!conditionIds.add(id)) throw c.fail("duplicate condition id");
            state.addCondition(new Condition(id, disease, c.intValue("severity"), c.intValue("immunity"), c.intValue("variance"),
                    c.longValue("age"), c.longValue("incubation"), c.intValue("tendQuality"), c.longValue("tendRemaining"),
                    c.uuid("source", null), c.bool("announced"), c.bool("peaked")));
        }
        java.util.Set<String> immunityIds = new java.util.HashSet<>();
        for (Obj i : json.objects("immunities", LifeState.teen_pro_YT_MAX_IMMUNITIES)) {
            i.allow("disease", "family", "cross", "remaining");
            if (!immunityIds.add(i.string("disease"))) throw i.fail("duplicate immunity");
            state.addImmunity(new Immunity(i.string("disease"), i.string("family"), i.intValue("cross"), i.longValue("remaining")));
        }
        Obj needs = json.obj("needs").allow("rest", "recreation", "social", "hygiene");
        state.rest(fraction(needs, "rest")); state.recreation(fraction(needs, "recreation"));
        state.social(fraction(needs, "social")); state.hygiene(fraction(needs, "hygiene"));
        Obj tolerance = json.obj("tolerance");
        if (tolerance.keys().size() > LifeState.teen_pro_YT_MAX_TOLERANCES) throw tolerance.fail("too many joy tolerances");
        for (String kind : tolerance.keys()) state.tolerance(kind, fraction(tolerance, kind));
        for (Obj m : json.objects("memories", LifeState.teen_pro_YT_MAX_MEMORIES)) {
            m.allow("thought", "mood", "opinion", "stackLimit", "stackFactor", "duration", "remaining", "other");
            state.remember(new Memory(m.string("thought"), m.intValue("mood"), m.intValue("opinion"), m.intValue("stackLimit"),
                    m.intValue("stackFactor"), m.longValue("duration"), m.longValue("remaining"), m.uuid("other", null)));
        }
        if (state.memories().size() != json.list("memories").size()) throw json.fail("memories exceed their stack limits");
        int mood = json.intValue("mood");
        if (mood < 0 || mood > 100) throw json.fail("mood outside 0..100");
        state.mood(mood);
        if (json.has("mental")) {
            Obj m = json.obj("mental").allow("activity", "level", "remaining");
            state.mental(new Mental(m.string("activity"), ActivityDefinition.MentalLevel.valueOf(m.string("level").toUpperCase(Locale.ROOT)), m.longValue("remaining")));
        }
        if (json.has("inspiration")) {
            Obj i = json.obj("inspiration").allow("kind", "remaining");
            state.inspiration(i.string("kind"), i.longValue("remaining"));
        }
        state.breakCooldown(nonNegative(json, "breakCooldown"));
        state.socialCooldown(nonNegative(json, "socialCooldown"));
        for (Obj a : json.objects("acquaintances", LifeState.teen_pro_YT_MAX_ACQUAINTANCES)) {
            a.allow("other", "compatibility", "rapport", "interactions", "sinceLast");
            UUID other = a.uuid("other");
            if (other.equals(state.citizenId()) || state.acquaintance(other) != null) throw a.fail("invalid acquaintance");
            state.putAcquaintance(new Acquaintance(other, a.intValue("compatibility"), a.intValue("rapport"), a.intValue("interactions"), a.longValue("sinceLast")));
        }
        for (Obj b : json.objects("bonds", LifeState.teen_pro_YT_MAX_BONDS)) {
            b.allow("other", "kind");
            UUID other = b.uuid("other");
            BondKind kind = BondKind.valueOf(b.string("kind").toUpperCase(Locale.ROOT));
            Bond existing = kind.family() ? null : state.romance(other);
            if (other.equals(state.citizenId()) || existing != null || kind.family() && state.related(other)) throw b.fail("conflicting bond");
            if (!state.bond(other, kind)) throw b.fail("too many bonds");
        }
        Obj skills = json.obj("skills");
        for (String skill : skills.keys()) {
            int xp = skills.intValue(skill);
            if (xp < 0 || xp > LifeState.teen_pro_YT_MAX_SKILL_XP) throw skills.fail("skill outside bounds");
            state.xp(Skill.parse(skill, skills.context()), xp);
        }
        if (json.has("pregnancy")) {
            Obj p = json.obj("pregnancy").allow("father", "child", "childSex", "genome", "term", "elapsed");
            state.pregnancy(new Pregnancy(p.uuid("father", null), p.uuid("child"), Sex.parse(p.string("childSex"), p.context()),
                    genome(p.obj("genome"), p.context()), p.longValue("term"), p.longValue("elapsed")));
        }
        if (json.has("activity")) {
            Obj a = json.obj("activity").allow("activity", "target", "partner", "remaining", "travel", "performing");
            state.activity(new Activity(a.string("activity"), position(a.obj("target")), a.uuid("partner", null), a.longValue("remaining"), a.longValue("travel"), a.bool("performing")));
        }
        return state;
    }

    private static Map<String, AllelePair> genome(Obj json, String context) {
        if (json.keys().size() > LifeState.teen_pro_YT_MAX_LOCI) throw json.fail("too many loci");
        LinkedHashMap<String, AllelePair> result = new LinkedHashMap<>();
        for (String locus : json.keys()) {
            List<String> alleles = json.strings(locus, 2);
            if (alleles.isEmpty()) throw json.fail("empty locus " + locus);
            result.put(locus, new AllelePair(alleles.get(0), alleles.size() == 2 ? alleles.get(1) : null));
        }
        return result;
    }

    private static int fraction(Obj json, String key) {
        int value = json.intValue(key);
        if (value < 0 || value > LifeState.teen_pro_YT_UNIT) throw json.fail(key + " outside 0..1");
        return value;
    }

    private static long nonNegative(Obj json, String key) {
        long value = json.longValue(key);
        if (value < 0) throw json.fail(key + " is negative");
        return value;
    }
}
