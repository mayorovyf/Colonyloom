package colonyloom.gameplay.life;

import colonyloom.core.life.RichText;
import colonyloom.core.life.content.Emote;
import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.LifeIds;
import colonyloom.core.life.content.LifeStage;
import colonyloom.core.life.content.NamePool;
import colonyloom.core.life.content.Stat;
import colonyloom.core.life.state.Condition;
import colonyloom.core.life.state.Journal;
import colonyloom.core.life.state.LifeRegistry;
import colonyloom.core.life.state.LifeState;
import colonyloom.core.life.state.Records.Bond;
import colonyloom.core.life.state.Records.BondKind;
import colonyloom.core.life.state.Records.Pregnancy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

public final class GrowthEngine {
    static final int teen_pro_YT_OLD_AGE = 90;
    static final long teen_pro_YT_FEED_INTERVAL = 6_000;
    static final double teen_pro_YT_FEED_RANGE = 12;

    private final LifeRegistry teen_pro_YT_life;
    private final LifePort teen_pro_YT_port;
    private final RandomGenerator teen_pro_YT_random;

    public GrowthEngine(LifeRegistry life, LifePort port, RandomGenerator random) {
        this.teen_pro_YT_life = Objects.requireNonNull(life); this.teen_pro_YT_port = Objects.requireNonNull(port); this.teen_pro_YT_random = Objects.requireNonNull(random);
    }

    private LifeRegistry life() { return teen_pro_YT_life; }
    private LifeContent content() { return life().content(); }
    private long ticksPerYear() { return life().settings().ticksPerYear(); }

    public boolean age(Resident resident, long elapsed) {
        LifeState state = resident.life();
        LifeStage before = state.stage(ticksPerYear());
        long next = Math.min(LifeState.teen_pro_YT_MAX_AGE_TICKS, state.ageTicks() + Math.round(elapsed * state.stats().stat(Stat.AGING)));
        state.ageTicks(next);
        LifeStage after = state.stage(ticksPerYear());
        if (after != before) grewUp(resident, after);
        else growTraits(resident);
        return after != before;
    }

    private void grewUp(Resident resident, LifeStage stage) {
        LifeState state = resident.life();
        growTraits(resident);
        if (stage == LifeStage.CHILD || stage == LifeStage.TEEN || stage == LifeStage.ADULT) {
            Thoughts.remember(content(), state, LifeIds.teen_pro_YT_GREW_UP, null);
            teen_pro_YT_port.emote(state.citizenId(), Emote.SPARKLE);
            life().record(state.colonyId(), teen_pro_YT_port.gameTime(), Journal.Category.FAMILY, RichText.of("colonyloom.journal.grew_up",
                    RichText.literal(state.fullName()), RichText.key("colonyloom.stage." + stage.id())), state.citizenId());
        }
        teen_pro_YT_port.display(state.citizenId());
        life().changed(state.citizenId());
    }

    void growTraits(Resident resident) {
        LifeState state = resident.life();
        int years = state.years(ticksPerYear());
        List<LifeState> parents = parents(state);
        if (years >= 3 && !state.stageGranted(LifeStage.CHILD)) {
            Traits.grant(content(), state, 1, false, parents, years, true, teen_pro_YT_random);
            state.grantStage(LifeStage.CHILD);
        }
        if (years >= 13 && !state.stageGranted(LifeStage.TEEN)) {
            Traits.grant(content(), state, 1, true, parents, years, true, teen_pro_YT_random);
            state.grantStage(LifeStage.TEEN);
        }
        if (years >= 16 && !state.stageGranted(LifeStage.ADULT)) {
            int ordinary = 0;
            for (String id : state.traits()) { var trait = content().traits().get(id); if (trait != null && trait.orientation() == null) ordinary++; }
            if (ordinary < 3) Traits.grant(content(), state, 3 - ordinary, true, parents, years, false, teen_pro_YT_random);
            state.grantStage(LifeStage.ADULT);
        }
    }

    private List<LifeState> parents(LifeState state) {
        List<LifeState> parents = new ArrayList<>();
        for (Bond bond : state.bonds()) if (bond.kind() == BondKind.PARENT) {
            LifeState parent = life().state(bond.other());
            if (parent != null) parents.add(parent);
        }
        return parents;
    }

    public String oldAge(Resident resident, long elapsed) {
        int years = resident.life().years(ticksPerYear());
        if (years < teen_pro_YT_OLD_AGE || !life().settings().lethality().kills()) return null;
        double perYear = 0.1 * Math.pow(2, (years - teen_pro_YT_OLD_AGE) / 5.0);
        double perDay = perYear * (LifeRules.teen_pro_YT_DAY / (double) ticksPerYear());
        return LifeRules.roll(teen_pro_YT_random, LifeRules.chanceOver(perDay, elapsed)) ? "@colonyloom.death.old_age" : null;
    }

    public void pregnancy(Resident resident, long elapsed) {
        LifeState mother = resident.life();
        Pregnancy pregnancy = mother.pregnancy();
        if (pregnancy == null) return;
        int trimester = pregnancy.trimester();
        pregnancy.advance(elapsed);
        if (pregnancy.trimester() != trimester) mother.invalidateStats();
        int worst = 0;
        for (Condition condition : mother.conditions()) if (condition.visible()) worst = Math.max(worst, condition.severity());
        if (worst >= 800_000 && LifeRules.roll(teen_pro_YT_random, LifeRules.chanceOver(0.6, elapsed))) {
            life().endPregnancy(mother);
            Thoughts.remember(content(), mother, LifeIds.teen_pro_YT_FAMILY_DIED, null);
            life().record(mother.colonyId(), teen_pro_YT_port.gameTime(), Journal.Category.FAMILY, RichText.of("colonyloom.journal.miscarriage", RichText.literal(mother.fullName())), mother.citizenId());
            return;
        }
        if (pregnancy.due() && resident.present()) teen_pro_YT_port.birth(mother.citizenId());
    }

    public record BirthPlan(UUID motherId, UUID childId, colonyloom.core.life.content.Sex sex,
                            String givenName, String familyName, UUID colonyId) {}

    public BirthPlan beginBirth(UUID motherId) {
        LifeState mother = life().state(motherId);
        if (mother == null || mother.deceased() || mother.pregnancy() == null || !mother.pregnancy().due()) return null;
        Pregnancy pregnancy = mother.pregnancy();
        LifeState father = pregnancy.father() == null ? null : life().state(pregnancy.father());
        NamePool pool = content().names(life().settings().names());
        String given = pool == null ? "Baby" : pool.given(pregnancy.childSex(), (int) (pregnancy.childId().getLeastSignificantBits() & 0x7FFFFFFF));
        String family = familyFor(father != null ? father.familyName() : mother.familyName(), pregnancy.childSex(), pool);
        return new BirthPlan(motherId, pregnancy.childId(), pregnancy.childSex(), given, family, mother.colonyId());
    }

    private static String familyFor(String parentFamily, colonyloom.core.life.content.Sex sex, NamePool pool) {
        if (pool != null) for (NamePool.Family family : pool.families())
            if (family.male().equals(parentFamily) || family.female().equals(parentFamily)) return family.form(sex);
        return parentFamily;
    }

    public LifeState completeBirth(BirthPlan plan, long clock) {
        LifeState mother = life().state(plan.motherId());
        Pregnancy pregnancy = mother == null ? null : mother.pregnancy();
        if (pregnancy == null || !pregnancy.childId().equals(plan.childId())) throw new IllegalStateException("Birth does not match the pregnancy");
        LifeState baby = new LifeState(plan.childId(), plan.colonyId(), plan.sex(), 0, clock, plan.givenName(), plan.familyName());
        baby.genome(pregnancy.childGenome());
        baby.grantStage(LifeStage.BABY);
        baby.rest(900_000); baby.recreation(800_000); baby.social(800_000); baby.hygiene(900_000);
        LifeState father = pregnancy.father() == null ? null : life().state(pregnancy.father());
        life().completeBirth(mother, baby);
        List<LifeState> parents = new ArrayList<>();
        parents.add(mother);
        if (father != null && !father.deceased()) parents.add(father);
        for (LifeState parent : parents) {
            for (Bond bond : List.copyOf(parent.bonds())) if (bond.kind() == BondKind.CHILD && !bond.other().equals(baby.citizenId())) {
                LifeState sibling = life().state(bond.other());
                if (sibling != null && baby.bond(sibling.citizenId()) == null && baby.bond(sibling.citizenId(), BondKind.SIBLING)) sibling.bond(baby.citizenId(), BondKind.SIBLING);
            }
            parent.bond(baby.citizenId(), BondKind.CHILD);
            baby.bond(parent.citizenId(), BondKind.PARENT);
            Thoughts.remember(content(), parent, LifeIds.teen_pro_YT_NEW_BABY, baby.citizenId());
            teen_pro_YT_port.emote(parent.citizenId(), Emote.HEART);
        }
        for (LifeState other : life().states()) {
            if (parents.contains(other) || other == baby || other.deceased() || !other.colonyId().equals(baby.colonyId())) continue;
            Thoughts.remember(content(), other, LifeIds.teen_pro_YT_COLONY_BABY, null);
        }
        life().record(mother.colonyId(), teen_pro_YT_port.gameTime(), Journal.Category.BIRTH, father == null
                ? RichText.of("colonyloom.journal.birth_single", RichText.literal(mother.fullName()), RichText.literal(baby.fullName()))
                : RichText.of("colonyloom.journal.birth", RichText.literal(mother.fullName()), RichText.literal(father.fullName()), RichText.literal(baby.fullName())), baby.citizenId());
        teen_pro_YT_port.display(baby.citizenId());
        return baby;
    }

    public boolean feedBaby(Resident baby, List<Resident> colony, long elapsed) {
        if (baby.life().years(ticksPerYear()) >= 3 || !baby.alive()) return false;
        if (!LifeRules.roll(teen_pro_YT_random, Math.min(1, elapsed / (double) teen_pro_YT_FEED_INTERVAL))) return false;
        Resident feeder = null;
        for (Resident other : colony) {
            if (other.life() == baby.life() || !other.present() || other.body().busy() || other.life().years(ticksPerYear()) < 16) continue;
            if (LifeRules.distance(baby.body().position(), other.body().position()) > teen_pro_YT_FEED_RANGE) continue;
            Bond bond = baby.life().bond(other.life().citizenId());
            boolean parent = bond != null && bond.kind() == BondKind.PARENT;
            if (parent) { feeder = other; break; }
            if (feeder == null) feeder = other;
        }
        if (feeder == null) return false;
        Thoughts.remember(content(), feeder.life(), LifeIds.teen_pro_YT_FED_BABY, baby.life().citizenId());
        teen_pro_YT_port.lookAt(feeder.life().citizenId(), baby.life().citizenId());
        teen_pro_YT_port.emote(feeder.life().citizenId(), Emote.HEART);
        return true;
    }
}
