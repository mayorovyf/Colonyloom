package colonyloom.core.life.state;

import colonyloom.core.content.BlockDescriptor;
import colonyloom.core.life.content.DiseaseDefinition;
import colonyloom.core.life.content.LifeStage;
import colonyloom.core.life.content.Sex;
import colonyloom.core.life.content.Skill;
import colonyloom.core.life.content.StatBlock;
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
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

public final class LifeState {
    public static final int teen_pro_YT_UNIT = DiseaseDefinition.teen_pro_YT_UNIT;
    public static final int teen_pro_YT_MAX_TRAITS = 4, teen_pro_YT_MAX_CONDITIONS = 12, teen_pro_YT_MAX_IMMUNITIES = 24, teen_pro_YT_MAX_MEMORIES = 32,
            teen_pro_YT_MAX_ACQUAINTANCES = 24, teen_pro_YT_MAX_BONDS = 16, teen_pro_YT_MAX_LOCI = 48, teen_pro_YT_MAX_TOLERANCES = 8, teen_pro_YT_MAX_SKILL_XP = 1_000_000;
    public static final long teen_pro_YT_MAX_AGE_TICKS = 24_000L * 24_000L * 4;

    private final UUID teen_pro_YT_citizenId, teen_pro_YT_colonyId;
    private final Sex teen_pro_YT_sex;
    private long teen_pro_YT_ageTicks, teen_pro_YT_clock;
    private String teen_pro_YT_givenName, teen_pro_YT_familyName;
    private final List<String> teen_pro_YT_traits = new ArrayList<>();
    private final LinkedHashMap<String, AllelePair> teen_pro_YT_genome = new LinkedHashMap<>();
    private final List<Condition> teen_pro_YT_conditions = new ArrayList<>();
    private final List<Immunity> teen_pro_YT_immunities = new ArrayList<>();
    private int teen_pro_YT_rest = 800_000, teen_pro_YT_recreation = 700_000, teen_pro_YT_social = 700_000, teen_pro_YT_hygiene = 800_000;
    private final TreeMap<String, Integer> teen_pro_YT_tolerance = new TreeMap<>();
    private final List<Memory> teen_pro_YT_memories = new ArrayList<>();
    private int teen_pro_YT_mood = 50;
    private Mental teen_pro_YT_mental;
    private String teen_pro_YT_inspiration;
    private long teen_pro_YT_inspirationRemaining, teen_pro_YT_breakCooldown, teen_pro_YT_socialCooldown;
    private final LinkedHashMap<UUID, Acquaintance> teen_pro_YT_acquaintances = new LinkedHashMap<>();
    private final List<Bond> teen_pro_YT_bonds = new ArrayList<>();
    private final EnumMap<Skill, Integer> teen_pro_YT_skills = new EnumMap<>(Skill.class);
    private Pregnancy teen_pro_YT_pregnancy;
    private Activity teen_pro_YT_activity;
    private int teen_pro_YT_grantedStages;
    private boolean teen_pro_YT_deceased;

    private final StatBlock teen_pro_YT_stats = new StatBlock();
    private boolean teen_pro_YT_statsValid;
    private long teen_pro_YT_idleTicks;
    private UUID teen_pro_YT_body;

    public LifeState(UUID citizenId, UUID colonyId, Sex sex, long ageTicks, long clock, String givenName, String familyName) {
        this.teen_pro_YT_citizenId = Objects.requireNonNull(citizenId, "citizenId");
        this.teen_pro_YT_colonyId = Objects.requireNonNull(colonyId, "colonyId");
        this.teen_pro_YT_sex = Objects.requireNonNull(sex, "sex");
        if (ageTicks < 0 || ageTicks > teen_pro_YT_MAX_AGE_TICKS || clock < 0) throw new IllegalArgumentException("Invalid life age/clock");
        this.teen_pro_YT_ageTicks = ageTicks; this.teen_pro_YT_clock = clock;
        names(givenName, familyName);
    }

    public UUID citizenId() { return teen_pro_YT_citizenId; }
    public UUID colonyId() { return teen_pro_YT_colonyId; }
    public Sex sex() { return teen_pro_YT_sex; }
    public long ageTicks() { return teen_pro_YT_ageTicks; }
    public int years(long ticksPerYear) { return (int) Math.min(999, teen_pro_YT_ageTicks / ticksPerYear); }
    public LifeStage stage(long ticksPerYear) { return LifeStage.of(years(ticksPerYear)); }
    public void ageTicks(long value) {
        if (value < 0 || value > teen_pro_YT_MAX_AGE_TICKS) throw new IllegalArgumentException("Invalid age");
        teen_pro_YT_ageTicks = value; invalidateStats();
    }
    public long clock() { return teen_pro_YT_clock; }
    public void clock(long value) { if (value < teen_pro_YT_clock) throw new IllegalArgumentException("Life clock cannot reverse"); teen_pro_YT_clock = value; }
    public String givenName() { return teen_pro_YT_givenName; }
    public String familyName() { return teen_pro_YT_familyName; }
    public String fullName() { return teen_pro_YT_givenName + " " + teen_pro_YT_familyName; }
    public void names(String given, String family) { checkName(given); checkName(family); teen_pro_YT_givenName = given; teen_pro_YT_familyName = family; }
    static void checkName(String name) {
        if (name == null || name.isBlank() || name.length() > 24 || name.contains("|") || name.contains("@") || name.contains("#")
                || name.contains("{") || name.contains("}")) throw new IllegalArgumentException("Invalid resident name");
    }

    public List<String> traits() { return Collections.unmodifiableList(teen_pro_YT_traits); }
    public boolean hasTrait(String id) { return teen_pro_YT_traits.contains(id); }
    public boolean addTrait(String id) {
        BlockDescriptor.validateIdentifier(id);
        if (teen_pro_YT_traits.contains(id) || teen_pro_YT_traits.size() >= teen_pro_YT_MAX_TRAITS) return false;
        teen_pro_YT_traits.add(id); invalidateStats(); return true;
    }
    public void removeTrait(String id) { if (teen_pro_YT_traits.remove(id)) invalidateStats(); }
    public int grantedStages() { return teen_pro_YT_grantedStages; }
    public void grantStage(LifeStage stage) { teen_pro_YT_grantedStages |= 1 << stage.ordinal(); }
    public boolean stageGranted(LifeStage stage) { return (teen_pro_YT_grantedStages & 1 << stage.ordinal()) != 0; }
    void grantedStages(int mask) { teen_pro_YT_grantedStages = mask & 0x1F; }

    public Map<String, AllelePair> genome() { return Collections.unmodifiableMap(teen_pro_YT_genome); }
    public void genome(Map<String, AllelePair> value) {
        if (value.size() > teen_pro_YT_MAX_LOCI) throw new IllegalArgumentException("Too many loci");
        value.keySet().forEach(BlockDescriptor::validateIdentifier);
        teen_pro_YT_genome.clear(); teen_pro_YT_genome.putAll(value); invalidateStats();
    }

    public List<Condition> conditions() { return Collections.unmodifiableList(teen_pro_YT_conditions); }
    public Condition condition(String diseaseId) {
        for (Condition condition : teen_pro_YT_conditions) if (condition.disease().id().equals(diseaseId)) return condition;
        return null;
    }
    public Condition conditionById(UUID id) {
        for (Condition condition : teen_pro_YT_conditions) if (condition.id().equals(id)) return condition;
        return null;
    }
    public boolean hasFamily(String family) {
        for (Condition condition : teen_pro_YT_conditions) if (condition.disease().family().equals(family)) return true;
        return false;
    }
    void addCondition(Condition condition) {
        if (teen_pro_YT_conditions.size() >= teen_pro_YT_MAX_CONDITIONS || hasFamily(condition.disease().family())) throw new IllegalStateException("Condition rejected");
        teen_pro_YT_conditions.add(condition); invalidateStats();
    }
    boolean removeCondition(Condition condition) {
        boolean removed = teen_pro_YT_conditions.remove(condition);
        if (removed) invalidateStats();
        return removed;
    }

    public List<Immunity> immunities() { return Collections.unmodifiableList(teen_pro_YT_immunities); }
    public double protection(DiseaseDefinition disease) {
        double result = 1.0;
        for (Immunity immunity : teen_pro_YT_immunities) result *= immunity.protection(disease);
        return result;
    }
    public void addImmunity(Immunity immunity) {
        teen_pro_YT_immunities.removeIf(existing -> existing.diseaseId().equals(immunity.diseaseId()));
        if (teen_pro_YT_immunities.size() >= teen_pro_YT_MAX_IMMUNITIES) {
            Immunity weakest = null;
            for (Immunity candidate : teen_pro_YT_immunities) if (!candidate.permanent() && (weakest == null || candidate.remaining() < weakest.remaining())) weakest = candidate;
            if (weakest == null) return;
            teen_pro_YT_immunities.remove(weakest);
        }
        teen_pro_YT_immunities.add(immunity);
    }
    public void advanceImmunities(long elapsed) {
        for (Immunity immunity : teen_pro_YT_immunities) immunity.advance(elapsed);
        teen_pro_YT_immunities.removeIf(Immunity::expired);
    }

    public StatBlock stats() { return teen_pro_YT_stats; }
    public boolean statsValid() { return teen_pro_YT_statsValid; }
    public void statsRebuilt() { teen_pro_YT_statsValid = true; }
    public void invalidateStats() { teen_pro_YT_statsValid = false; }

    public int rest() { return teen_pro_YT_rest; }
    public int recreation() { return teen_pro_YT_recreation; }
    public int social() { return teen_pro_YT_social; }
    public int hygiene() { return teen_pro_YT_hygiene; }
    public void rest(int value) { teen_pro_YT_rest = clamp(value); }
    public void recreation(int value) { teen_pro_YT_recreation = clamp(value); }
    public void social(int value) { teen_pro_YT_social = clamp(value); }
    public void hygiene(int value) { teen_pro_YT_hygiene = clamp(value); }
    public Map<String, Integer> tolerance() { return Collections.unmodifiableMap(teen_pro_YT_tolerance); }
    public int tolerance(String joyKind) { return teen_pro_YT_tolerance.getOrDefault(joyKind, 0); }
    public void tolerance(String joyKind, int value) {
        if (joyKind == null || !joyKind.matches("[a-z0-9_]{1,24}")) throw new IllegalArgumentException("Invalid joy kind");
        int bounded = clamp(value);
        if (bounded == 0) { teen_pro_YT_tolerance.remove(joyKind); return; }
        if (!teen_pro_YT_tolerance.containsKey(joyKind) && teen_pro_YT_tolerance.size() >= teen_pro_YT_MAX_TOLERANCES) {
            String lowest = null;
            for (var entry : teen_pro_YT_tolerance.entrySet()) if (lowest == null || entry.getValue() < teen_pro_YT_tolerance.get(lowest)) lowest = entry.getKey();
            if (teen_pro_YT_tolerance.get(lowest) > bounded) return;
            teen_pro_YT_tolerance.remove(lowest);
        }
        teen_pro_YT_tolerance.put(joyKind, bounded);
    }

    public List<Memory> memories() { return Collections.unmodifiableList(teen_pro_YT_memories); }
    public void remember(Memory memory) {
        int same = 0;
        Memory oldestSame = null;
        for (Memory existing : teen_pro_YT_memories) if (existing.sameKind(memory.thoughtId(), memory.other())) {
            same++;
            if (oldestSame == null || existing.remaining() < oldestSame.remaining()) oldestSame = existing;
        }
        if (same >= memory.stackLimit()) teen_pro_YT_memories.remove(oldestSame);
        if (teen_pro_YT_memories.size() >= teen_pro_YT_MAX_MEMORIES) {
            Memory oldest = teen_pro_YT_memories.getFirst();
            for (Memory existing : teen_pro_YT_memories) if (existing.remaining() < oldest.remaining()) oldest = existing;
            teen_pro_YT_memories.remove(oldest);
        }
        teen_pro_YT_memories.add(memory);
    }
    public void forget(String thoughtId) { teen_pro_YT_memories.removeIf(memory -> memory.thoughtId().equals(thoughtId)); }
    public void forgetAbout(UUID other) { teen_pro_YT_memories.removeIf(memory -> other.equals(memory.other())); }
    public void advanceMemories(long elapsed) {
        for (Memory memory : teen_pro_YT_memories) memory.advance(elapsed);
        teen_pro_YT_memories.removeIf(Memory::expired);
    }

    public int mood() { return teen_pro_YT_mood; }
    public void mood(int value) { teen_pro_YT_mood = Math.max(0, Math.min(100, value)); }
    public Mental mental() { return teen_pro_YT_mental; }
    public void mental(Mental value) { teen_pro_YT_mental = value; }
    public String inspiration() { return teen_pro_YT_inspiration; }
    public long inspirationRemaining() { return teen_pro_YT_inspirationRemaining; }
    public void inspiration(String kind, long remaining) {
        if (kind != null && !kind.matches("[a-z0-9_]{1,24}")) throw new IllegalArgumentException("Invalid inspiration");
        teen_pro_YT_inspiration = kind; teen_pro_YT_inspirationRemaining = kind == null ? 0 : Math.max(0, remaining);
    }
    public long breakCooldown() { return teen_pro_YT_breakCooldown; }
    public void breakCooldown(long value) { teen_pro_YT_breakCooldown = Math.max(0, value); }
    public long socialCooldown() { return teen_pro_YT_socialCooldown; }
    public void socialCooldown(long value) { teen_pro_YT_socialCooldown = Math.max(0, value); }
    public void advanceTimers(long elapsed) {
        teen_pro_YT_breakCooldown = Math.max(0, teen_pro_YT_breakCooldown - elapsed);
        teen_pro_YT_socialCooldown = Math.max(0, teen_pro_YT_socialCooldown - elapsed);
        if (teen_pro_YT_inspiration != null) { teen_pro_YT_inspirationRemaining = Math.max(0, teen_pro_YT_inspirationRemaining - elapsed); if (teen_pro_YT_inspirationRemaining == 0) teen_pro_YT_inspiration = null; }
        if (teen_pro_YT_mental != null) teen_pro_YT_mental.advance(elapsed);
    }

    public Collection<Acquaintance> acquaintances() { return Collections.unmodifiableCollection(teen_pro_YT_acquaintances.values()); }
    public Acquaintance acquaintance(UUID other) { return teen_pro_YT_acquaintances.get(other); }
    public Acquaintance meet(UUID other, int compatibility) {
        if (other.equals(teen_pro_YT_citizenId)) throw new IllegalArgumentException("Cannot know oneself");
        Acquaintance existing = teen_pro_YT_acquaintances.get(other);
        if (existing != null) return existing;
        if (teen_pro_YT_acquaintances.size() >= teen_pro_YT_MAX_ACQUAINTANCES) {
            Acquaintance victim = null;
            for (Acquaintance candidate : teen_pro_YT_acquaintances.values()) {
                if (bond(candidate.other()) != null) continue;
                if (victim == null || candidate.sinceLast() > victim.sinceLast()
                        || candidate.sinceLast() == victim.sinceLast() && Math.abs(candidate.rapport()) < Math.abs(victim.rapport())) victim = candidate;
            }
            if (victim == null) return null;
            teen_pro_YT_acquaintances.remove(victim.other());
        }
        Acquaintance created = new Acquaintance(other, Math.max(-30, Math.min(30, compatibility)), 0, 0, 0);
        teen_pro_YT_acquaintances.put(other, created);
        return created;
    }
    void putAcquaintance(Acquaintance value) {
        if (teen_pro_YT_acquaintances.size() >= teen_pro_YT_MAX_ACQUAINTANCES && !teen_pro_YT_acquaintances.containsKey(value.other())) throw new IllegalArgumentException("Too many acquaintances");
        teen_pro_YT_acquaintances.put(value.other(), value);
    }
    public void forgetPerson(UUID other) { teen_pro_YT_acquaintances.remove(other); forgetAbout(other); }

    public List<Bond> bonds() { return Collections.unmodifiableList(teen_pro_YT_bonds); }
    public Bond bond(UUID other) {
        Bond romance = null;
        for (Bond bond : teen_pro_YT_bonds) if (bond.other().equals(other)) { if (bond.kind().family()) return bond; romance = bond; }
        return romance;
    }
    public Bond romance(UUID other) {
        for (Bond bond : teen_pro_YT_bonds) if (bond.other().equals(other) && !bond.kind().family()) return bond;
        return null;
    }
    public Bond partner() {
        for (Bond bond : teen_pro_YT_bonds) if (bond.kind().romantic()) return bond;
        return null;
    }
    public boolean related(UUID other) {
        for (Bond bond : teen_pro_YT_bonds) if (bond.other().equals(other) && bond.kind().family()) return true;
        return false;
    }
    public boolean bond(UUID other, BondKind kind) {
        if (other.equals(teen_pro_YT_citizenId)) throw new IllegalArgumentException("Cannot bond with oneself");
        teen_pro_YT_bonds.removeIf(bond -> bond.other().equals(other) && bond.kind().family() == kind.family());
        if (kind.romantic()) {
            Bond current = partner();
            if (current != null && !current.other().equals(other)) throw new IllegalStateException("Already partnered");
        }
        if (teen_pro_YT_bonds.size() >= teen_pro_YT_MAX_BONDS) {
            Bond ex = null;
            for (Bond bond : teen_pro_YT_bonds) if (bond.kind() == BondKind.EX) { ex = bond; break; }
            if (ex == null) return false;
            teen_pro_YT_bonds.remove(ex);
        }
        teen_pro_YT_bonds.add(new Bond(other, kind));
        return true;
    }
    public void unbondRomance(UUID other) { teen_pro_YT_bonds.removeIf(bond -> bond.other().equals(other) && !bond.kind().family()); }

    public Map<Skill, Integer> skills() { return Collections.unmodifiableMap(teen_pro_YT_skills); }
    public int xp(Skill skill) { return teen_pro_YT_skills.getOrDefault(skill, 0); }
    public void xp(Skill skill, int value) {
        int bounded = Math.max(0, Math.min(teen_pro_YT_MAX_SKILL_XP, value));
        if (bounded == 0) teen_pro_YT_skills.remove(skill); else teen_pro_YT_skills.put(skill, bounded);
    }
    public int level(Skill skill) { return level(xp(skill)); }
    public static int level(int xp) {
        int level = 0;
        while (level < 20 && xp >= 50 * (level + 1) * (level + 2)) level++;
        return level;
    }

    public Pregnancy pregnancy() { return teen_pro_YT_pregnancy; }
    void pregnancy(Pregnancy value) {
        if (value != null && teen_pro_YT_sex != Sex.FEMALE) throw new IllegalArgumentException("Only female residents carry children");
        teen_pro_YT_pregnancy = value; invalidateStats();
    }

    public Activity activity() { return teen_pro_YT_activity; }
    public void activity(Activity value) { teen_pro_YT_activity = value; }

    public long idleTicks() { return teen_pro_YT_idleTicks; }
    public void idleTicks(long value) { teen_pro_YT_idleTicks = Math.max(0, value); }
    public UUID body() { return teen_pro_YT_body; }
    void body(UUID value) { teen_pro_YT_body = value; }
    public boolean child() { return teen_pro_YT_body == null; }

    public boolean deceased() { return teen_pro_YT_deceased; }
    void deceased(boolean value) { teen_pro_YT_deceased = value; }

    void clearOnDeath() {
        teen_pro_YT_deceased = true;
        teen_pro_YT_memories.clear(); teen_pro_YT_tolerance.clear(); teen_pro_YT_acquaintances.clear(); teen_pro_YT_immunities.clear();
        teen_pro_YT_mental = null; teen_pro_YT_inspiration = null; teen_pro_YT_inspirationRemaining = 0; teen_pro_YT_activity = null; teen_pro_YT_mood = 50;
        teen_pro_YT_bonds.removeIf(bond -> bond.kind() == BondKind.EX);
        invalidateStats();
    }

    private static int clamp(int value) { return Math.max(0, Math.min(teen_pro_YT_UNIT, value)); }
}
