package colonyloom.gameplay.life;

import colonyloom.core.life.RichText;
import colonyloom.core.life.content.ActivityDefinition;
import colonyloom.core.life.content.DiseaseDefinition;
import colonyloom.core.life.content.EnvironmentFlag;
import colonyloom.core.life.content.LifeIds;
import colonyloom.core.life.content.Route;
import colonyloom.core.life.state.Condition;
import colonyloom.core.life.state.Journal;
import colonyloom.core.life.state.LifeRegistry;
import colonyloom.core.life.state.LifeState;
import colonyloom.core.life.state.Records.Activity;
import colonyloom.core.life.state.Records.Bond;
import colonyloom.core.life.state.Records.BondKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

public final class LifeController {
    public static final long teen_pro_YT_MIN_UPDATE = 40;
    static final long teen_pro_YT_MAX_UPDATE = 6_000;
    static final long teen_pro_YT_COLONY_INTERVAL = 600;
    static final double teen_pro_YT_CONCEPTION_RANGE = 6;
    static final double teen_pro_YT_CARE_RANGE = 4;
    static final int teen_pro_YT_PRUNE_PER_PASS = 8;

    private final LifeRegistry teen_pro_YT_life;
    private final LifePort teen_pro_YT_port;
    private final HealthEngine teen_pro_YT_health;
    private final MindEngine teen_pro_YT_mind;
    private final SocialEngine teen_pro_YT_social;
    private final GrowthEngine teen_pro_YT_growth;
    private final Brain teen_pro_YT_brain;
    private final ArrayDeque<UUID> teen_pro_YT_updateQueue = new ArrayDeque<>();
    private final ArrayDeque<UUID> teen_pro_YT_socialQueue = new ArrayDeque<>();
    private final Map<UUID, String> teen_pro_YT_pendingDeaths = new HashMap<>();
    private final Map<UUID, Long> teen_pro_YT_colonyPasses = new HashMap<>();
    private final Map<UUID, List<Resident>> teen_pro_YT_residents = new HashMap<>();
    private long teen_pro_YT_residentsTick = Long.MIN_VALUE, teen_pro_YT_tick;
    private int teen_pro_YT_colonyCursor;

    public LifeController(LifeRegistry life, LifePort port, RandomGenerator random) {
        this.teen_pro_YT_life = Objects.requireNonNull(life);
        this.teen_pro_YT_port = Objects.requireNonNull(port);
        Objects.requireNonNull(random);
        teen_pro_YT_health = new HealthEngine(life, port, random);
        teen_pro_YT_mind = new MindEngine(life, port, random);
        teen_pro_YT_social = new SocialEngine(life, port, teen_pro_YT_health, random);
        teen_pro_YT_growth = new GrowthEngine(life, port, random);
        teen_pro_YT_brain = new Brain(life, port, random);
        teen_pro_YT_brain.onCompletion(this::completed);
    }

    public HealthEngine health() { return teen_pro_YT_health; }
    public MindEngine mind() { return teen_pro_YT_mind; }
    public SocialEngine social() { return teen_pro_YT_social; }
    public GrowthEngine growth() { return teen_pro_YT_growth; }
    public Brain brain() { return teen_pro_YT_brain; }
    public LifeRegistry life() { return teen_pro_YT_life; }

    public void tick(long serverTick) {
        teen_pro_YT_life.requireOwner();
        teen_pro_YT_tick = serverTick;
        if (!teen_pro_YT_life.active()) return;
        int updates = teen_pro_YT_life.settings().updatesPerTick();
        int used = 0, inspected = 0, limit = Math.max(1, teen_pro_YT_life.size());
        while (used < updates && inspected < limit && teen_pro_YT_port.timeAvailable()) {
            UUID id = next(teen_pro_YT_updateQueue);
            if (id == null) break;
            inspected++;
            LifeState state = teen_pro_YT_life.state(id);
            if (state == null || state.deceased()) continue;
            Body body = teen_pro_YT_port.body(id);
            if (body == null) continue;
            if (!body.alive()) { died(id, null); used++; continue; }
            if (!body.present()) continue;
            long elapsed = body.activeTicks() - state.clock();
            if (elapsed < teen_pro_YT_MIN_UPDATE) continue;
            update(new Resident(state, body), Math.min(elapsed, teen_pro_YT_MAX_UPDATE));
            used++;
        }
        conversations(teen_pro_YT_life.settings().socialChecksPerTick());
        colonies();
    }

    private UUID next(ArrayDeque<UUID> queue) {
        if (queue.isEmpty()) for (LifeState state : teen_pro_YT_life.states()) if (!state.deceased()) queue.add(state.citizenId());
        return queue.poll();
    }

    public LifeState found(Body body) {
        if (teen_pro_YT_life.knows(body.lifeId())) return null;
        LifeState state = Founder.create(teen_pro_YT_life.content(), teen_pro_YT_life.settings(), body);
        teen_pro_YT_life.add(state, body.lifeId());
        if (!body.alive()) teen_pro_YT_life.died(state.citizenId());
        teen_pro_YT_port.display(state.citizenId());
        return state;
    }

    void update(Resident resident, long elapsed) {
        LifeState state = resident.life();
        UUID id = state.citizenId();
        long ticksPerYear = teen_pro_YT_life.settings().ticksPerYear();
        StatsBuilder.rebuild(state, teen_pro_YT_life.content(), ticksPerYear);
        Set<EnvironmentFlag> environment = teen_pro_YT_port.environment(id);
        ActivityDefinition performing = state.activity() != null && state.activity().performing() ? teen_pro_YT_life.content().activity(state.activity().activityId()) : null;
        boolean bedRest = performing != null && (performing.purpose() == ActivityDefinition.Purpose.SLEEP || performing.purpose() == ActivityDefinition.Purpose.BED_REST);
        String death = teen_pro_YT_health.update(resident, elapsed, bedRest);
        if (death == null) death = teen_pro_YT_growth.oldAge(resident, elapsed);
        if (death != null && !teen_pro_YT_pendingDeaths.containsKey(id) && teen_pro_YT_port.kill(id, death)) teen_pro_YT_pendingDeaths.put(id, death);
        if (teen_pro_YT_pendingDeaths.containsKey(id)) { state.clock(Math.max(state.clock(), resident.body().activeTicks())); return; }
        if (!state.statsValid()) StatsBuilder.rebuild(state, teen_pro_YT_life.content(), ticksPerYear);
        List<Resident> colony = residents(state.colonyId());
        teen_pro_YT_health.triggers(resident, environment, elapsed);
        teen_pro_YT_health.heredity(resident);
        if (contagiousAirborne(state)) teen_pro_YT_health.airborne(resident, colony, elapsed);
        teen_pro_YT_mind.update(resident, elapsed, environment, performing);
        teen_pro_YT_social.fade(state, elapsed);
        teen_pro_YT_growth.age(resident, elapsed);
        teen_pro_YT_growth.pregnancy(resident, elapsed);
        teen_pro_YT_growth.feedBaby(resident, colony, elapsed);
        teen_pro_YT_brain.update(resident, elapsed, colony);
        if (!state.statsValid()) StatsBuilder.rebuild(state, teen_pro_YT_life.content(), ticksPerYear);
        state.advanceTimers(elapsed);
        state.advanceImmunities(elapsed);
        state.clock(Math.max(state.clock(), Math.min(resident.body().activeTicks(), state.clock() + elapsed)));
        conceive(resident, colony, elapsed);
    }

    private static boolean contagiousAirborne(LifeState state) {
        for (Condition condition : state.conditions()) if (condition.contagiousNow() && condition.disease().transmits(Route.AIRBORNE)) return true;
        return false;
    }

    private void conceive(Resident resident, List<Resident> colony, long elapsed) {
        LifeState state = resident.life();
        Bond partner = state.partner();
        if (partner == null || state.sex() != colonyloom.core.life.content.Sex.FEMALE || !asleep(state)) return;
        for (Resident other : colony) {
            if (!other.life().citizenId().equals(partner.other()) || !other.present() || !asleep(other.life())) continue;
            if (LifeRules.distance(resident.body().position(), other.body().position()) > teen_pro_YT_CONCEPTION_RANGE) return;
            teen_pro_YT_social.tryConceive(resident, other, elapsed);
            return;
        }
    }

    private boolean asleep(LifeState state) {
        var activity = state.activity();
        if (activity == null || !activity.performing()) return false;
        ActivityDefinition definition = teen_pro_YT_life.content().activity(activity.activityId());
        return definition != null && definition.purpose() == ActivityDefinition.Purpose.SLEEP;
    }

    public List<Resident> residents(UUID colonyId) {
        if (teen_pro_YT_residentsTick != teen_pro_YT_tick) { teen_pro_YT_residents.clear(); teen_pro_YT_residentsTick = teen_pro_YT_tick; }
        return teen_pro_YT_residents.computeIfAbsent(colonyId, id -> {
            List<Resident> result = new ArrayList<>();
            for (Body body : teen_pro_YT_port.bodies(id)) {
                if (!body.alive()) continue;
                LifeState state = teen_pro_YT_life.state(body.lifeId());
                if (state != null && !state.deceased() && state.colonyId().equals(id)) result.add(new Resident(state, body));
            }
            return List.copyOf(result);
        });
    }

    private void conversations(int checks) {
        int done = 0, inspected = 0, limit = Math.max(1, teen_pro_YT_life.size());
        while (done < checks && inspected < limit && teen_pro_YT_port.timeAvailable()) {
            UUID id = next(teen_pro_YT_socialQueue);
            if (id == null) return;
            inspected++;
            LifeState state = teen_pro_YT_life.state(id);
            if (state == null || state.deceased() || state.socialCooldown() > 0) continue;
            Body body = teen_pro_YT_port.body(id);
            if (body == null || !body.present()) continue;
            done++;
            teen_pro_YT_social.tryInteract(new Resident(state, body), residents(state.colonyId()));
        }
    }

    private void colonies() {
        List<UUID> colonies = teen_pro_YT_port.colonies();
        if (colonies.isEmpty()) return;
        for (int step = 0; step < colonies.size(); step++) {
            UUID colonyId = colonies.get(Math.floorMod(teen_pro_YT_colonyCursor + step, colonies.size()));
            Long last = teen_pro_YT_colonyPasses.get(colonyId);
            if (last == null) { teen_pro_YT_colonyPasses.put(colonyId, teen_pro_YT_tick); continue; }
            if (teen_pro_YT_tick - last < teen_pro_YT_COLONY_INTERVAL) continue;
            teen_pro_YT_colonyPasses.put(colonyId, teen_pro_YT_tick);
            teen_pro_YT_colonyCursor = Math.floorMod(teen_pro_YT_colonyCursor + step + 1, colonies.size());
            List<Resident> present = new ArrayList<>();
            for (Resident resident : residents(colonyId)) if (resident.present()) present.add(resident);
            teen_pro_YT_health.outbreak(present, teen_pro_YT_tick - last);
            tending(present);
            teen_pro_YT_life.prune(teen_pro_YT_PRUNE_PER_PASS);
            return;
        }
        teen_pro_YT_colonyPasses.keySet().retainAll(new HashSet<>(colonies));
    }

    private void tending(List<Resident> present) {
        Set<UUID> attended = new HashSet<>();
        for (Resident resident : present) {
            Activity activity = resident.life().activity();
            if (activity != null && activity.partner() != null && LifeIds.teen_pro_YT_TEND_PATIENT.equals(activity.activityId())) attended.add(activity.partner());
        }
        for (Resident patient : present) {
            if (!HealthEngine.needsCare(patient.life()) || attended.contains(patient.life().citizenId())) continue;
            Resident healer = teen_pro_YT_brain.healer(patient, present, true);
            if (healer == null || !teen_pro_YT_brain.tend(healer, patient)) continue;
            attended.add(patient.life().citizenId());
        }
    }

    private void completed(LifeState state, Activity activity, ActivityDefinition definition) {
        if (activity.partner() == null) return;
        if (LifeIds.teen_pro_YT_TEND_PATIENT.equals(definition.id())) care(activity.partner(), state.citizenId());
        else if (definition.purpose() == ActivityDefinition.Purpose.MEDICAL) care(state.citizenId(), activity.partner());
    }

    private void care(UUID patientId, UUID healerId) {
        LifeState healer = teen_pro_YT_life.state(healerId);
        Body patientBody = teen_pro_YT_port.body(patientId), healerBody = teen_pro_YT_port.body(healerId);
        LifeState patient = teen_pro_YT_life.state(patientId);
        if (healer == null || patient == null || patientBody == null || healerBody == null || !patientBody.present() || !healerBody.present()) return;
        if (LifeRules.distance(patientBody.position(), healerBody.position()) > teen_pro_YT_CARE_RANGE) return;
        teen_pro_YT_health.tend(new Resident(patient, patientBody), healer);
    }

    public void died(UUID lifeId, String cause) {
        LifeState state = teen_pro_YT_life.state(lifeId);
        if (state == null || state.deceased()) return;
        String reason = cause != null ? cause : teen_pro_YT_pendingDeaths.get(lifeId);
        teen_pro_YT_pendingDeaths.remove(lifeId);
        for (LifeState other : teen_pro_YT_life.states()) {
            if (other == state || other.deceased() || !other.colonyId().equals(state.colonyId())) continue;
            Bond family = other.bond(lifeId);
            Bond romance = other.romance(lifeId);
            int opinion = Relations.opinion(other, lifeId);
            other.forgetPerson(lifeId);
            String thought;
            if (romance != null && romance.kind().romantic()) { other.bond(lifeId, BondKind.LATE_PARTNER); thought = LifeIds.teen_pro_YT_PARTNER_DIED; }
            else if (family != null && family.kind().family()) thought = LifeIds.teen_pro_YT_FAMILY_DIED;
            else if (opinion >= Relations.teen_pro_YT_FRIEND) thought = LifeIds.teen_pro_YT_FRIEND_DIED;
            else if (opinion <= Relations.teen_pro_YT_RIVAL) thought = LifeIds.teen_pro_YT_RIVAL_DIED;
            else thought = LifeIds.teen_pro_YT_COLONIST_DIED;
            Thoughts.remember(teen_pro_YT_life.content(), other, thought, lifeId);
        }
        String text = reason == null ? RichText.of("colonyloom.journal.died", RichText.literal(state.fullName()))
                : RichText.of("colonyloom.journal.died_of", RichText.literal(state.fullName()), RichText.nested(reason));
        teen_pro_YT_life.record(state.colonyId(), teen_pro_YT_port.gameTime(), Journal.Category.DEATH, text, lifeId);
        teen_pro_YT_life.died(lifeId);
    }

    public void ate(UUID lifeId) {
        Resident resident = resident(lifeId);
        if (resident != null && teen_pro_YT_life.active()) teen_pro_YT_health.ate(resident);
    }

    public void injured(UUID lifeId, String kind) {
        Resident resident = resident(lifeId);
        if (resident != null && teen_pro_YT_life.active()) teen_pro_YT_health.injured(resident, kind);
    }

    public void arrived(UUID lifeId) {
        LifeState state = teen_pro_YT_life.state(lifeId);
        if (state != null && state.activity() != null && !state.activity().performing()) {
            state.activity().arrive();
            teen_pro_YT_life.changed(lifeId);
        }
    }

    public void interrupt(UUID lifeId) {
        LifeState state = teen_pro_YT_life.state(lifeId);
        if (state != null && state.activity() != null) teen_pro_YT_brain.cancel(state);
    }

    public GrowthEngine.BirthPlan beginBirth(UUID motherId) { return teen_pro_YT_growth.beginBirth(motherId); }
    public LifeState completeBirth(GrowthEngine.BirthPlan plan, long clock) {
        LifeState baby = teen_pro_YT_growth.completeBirth(plan, clock);
        StatsBuilder.rebuild(baby, teen_pro_YT_life.content(), teen_pro_YT_life.settings().ticksPerYear());
        return baby;
    }

    public void contentChanged() {
        for (LifeState state : teen_pro_YT_life.states()) state.invalidateStats();
    }

    public Resident resident(UUID lifeId) {
        LifeState state = teen_pro_YT_life.state(lifeId);
        Body body = state == null ? null : teen_pro_YT_port.body(lifeId);
        return state == null || body == null ? null : new Resident(state, body);
    }

    public Condition infect(UUID lifeId, String diseaseId) {
        Resident resident = resident(lifeId);
        DiseaseDefinition disease = teen_pro_YT_life.content().disease(diseaseId);
        if (resident == null || disease == null) throw new IllegalArgumentException("Unknown resident or disease");
        return teen_pro_YT_health.infect(resident, disease, null);
    }

    public int cure(UUID lifeId, String diseaseId) {
        Resident resident = resident(lifeId);
        if (resident == null) throw new IllegalArgumentException("Unknown resident");
        return teen_pro_YT_health.cure(resident, diseaseId);
    }

    public int epidemic(UUID colonyId, String diseaseId, int count, RandomGenerator random) {
        DiseaseDefinition disease = teen_pro_YT_life.content().disease(diseaseId);
        if (disease == null) throw new IllegalArgumentException("Unknown disease");
        List<Resident> candidates = new ArrayList<>();
        for (Resident resident : residents(colonyId)) if (resident.present()) candidates.add(resident);
        int infected = 0;
        while (infected < count && !candidates.isEmpty()) {
            Resident target = candidates.remove(random.nextInt(candidates.size()));
            if (teen_pro_YT_health.infect(target, disease, null) != null) infected++;
        }
        return infected;
    }

    public void setAge(UUID lifeId, int years) {
        LifeState state = teen_pro_YT_life.state(lifeId);
        if (state == null || years < 0 || years > 150) throw new IllegalArgumentException("Unknown resident or age");
        state.ageTicks(years * teen_pro_YT_life.settings().ticksPerYear());
        teen_pro_YT_port.display(lifeId);
        teen_pro_YT_life.changed(lifeId);
    }
}
