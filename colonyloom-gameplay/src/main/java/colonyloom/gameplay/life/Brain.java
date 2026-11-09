package colonyloom.gameplay.life;

import colonyloom.core.colony.Territory;
import colonyloom.core.colony.WorldPosition;
import colonyloom.core.life.content.ActivityDefinition;
import colonyloom.core.life.content.ActivityDefinition.Place;
import colonyloom.core.life.content.ActivityDefinition.Purpose;
import colonyloom.core.life.content.Emote;
import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.LifeIds;
import colonyloom.core.life.content.TraitDefinition;
import colonyloom.core.life.state.Condition;
import colonyloom.core.life.state.LifeRegistry;
import colonyloom.core.life.state.LifeState;
import colonyloom.core.life.state.Records.Activity;
import colonyloom.core.life.state.Records.Bond;
import colonyloom.core.life.state.Records.BondKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

public final class Brain {
    public enum Block { SLEEP, WORK, LEISURE }
    static final long teen_pro_YT_IDLE_BEFORE_ACTIVITY = 200;
    static final long teen_pro_YT_IDLE_ACTIVITY_MAX = 400;
    static final long teen_pro_YT_TRAVEL_LIMIT = 1_600;
    static final double teen_pro_YT_PLACE_RANGE = 48;
    static final int teen_pro_YT_BORED = 450_000, teen_pro_YT_LONELY = 350_000;

    private final LifeRegistry teen_pro_YT_life;
    private final LifePort teen_pro_YT_port;
    private final RandomGenerator teen_pro_YT_random;
    private Completion teen_pro_YT_completion = (state, activity, definition) -> {};

    @FunctionalInterface
    public interface Completion {
        void completed(LifeState state, Activity activity, ActivityDefinition definition);
    }

    public void onCompletion(Completion completion) { teen_pro_YT_completion = Objects.requireNonNull(completion); }

    public Brain(LifeRegistry life, LifePort port, RandomGenerator random) {
        this.teen_pro_YT_life = Objects.requireNonNull(life); this.teen_pro_YT_port = Objects.requireNonNull(port); this.teen_pro_YT_random = Objects.requireNonNull(random);
    }

    private LifeRegistry life() { return teen_pro_YT_life; }
    private LifeContent content() { return life().content(); }
    private long ticksPerYear() { return life().settings().ticksPerYear(); }

    public Block block(LifeState state, int dayTime) {
        int years = state.years(ticksPerYear());
        int shift = 0;
        for (String id : state.traits()) {
            TraitDefinition trait = content().traits().get(id);
            if (trait != null) shift += trait.scheduleShiftHours();
        }
        shift = Math.max(-6, Math.min(6, shift));
        int hour = Math.floorMod(LifeRules.hour(dayTime) - shift, 24);
        if (years < 3) return hour >= 19 || hour < 8 ? Block.SLEEP : Block.LEISURE;
        if (years < 13) return hour >= 20 || hour < 7 ? Block.SLEEP : hour < 15 ? Block.WORK : Block.LEISURE;
        return hour >= 22 || hour < 6 ? Block.SLEEP : hour < 17 ? Block.WORK : Block.LEISURE;
    }

    public static boolean needsRest(LifeState state) {
        if (state.stats().unconscious()) return true;
        for (Condition condition : state.conditions()) if (condition.visible() && condition.stage().bedRest()) return true;
        return false;
    }

    public void update(Resident resident, long elapsed, List<Resident> colony) {
        LifeState state = resident.life();
        Activity current = state.activity();
        if (current != null) {
            ActivityDefinition definition = content().activity(current.activityId());
            if (definition == null || resident.body().busy()) { cancel(state); current = null; }
            else {
                current.advance(elapsed);
                if (current.finished()) { complete(state, current, definition); current = null; }
                else if (!current.performing() && current.travel() > teen_pro_YT_TRAVEL_LIMIT || interrupts(resident, definition)) { cancel(state); current = null; }
            }
        }
        if (current != null || resident.body().busy()) {
            state.idleTicks(0);
            return;
        }
        WorldPosition here = resident.body().position();
        int dayTime = teen_pro_YT_port.dayTime(here.dimension());
        Block block = block(state, dayTime);
        ActivityDefinition next = mandatory(resident);
        boolean idle = false;
        if (next == null) {
            if (block == Block.WORK && state.stage(ticksPerYear()).mayWork()) {
                state.idleTicks(state.idleTicks() + elapsed);
                if (state.idleTicks() < teen_pro_YT_IDLE_BEFORE_ACTIVITY) return;
                idle = true;
            }
            next = choose(resident, block, dayTime, teen_pro_YT_port.raining(here), colony, idle);
        }
        if (next != null) start(resident, next, colony, idle);
    }

    ActivityDefinition mandatory(Resident resident) {
        LifeState state = resident.life();
        if (state.stats().unconscious()) return first(Purpose.COLLAPSE, state);
        if (state.mental() != null) {
            ActivityDefinition definition = content().activity(state.mental().activityId());
            if (definition != null) return definition;
        }
        if (needsRest(state)) return first(Purpose.BED_REST, state);
        return null;
    }

    private ActivityDefinition first(Purpose purpose, LifeState state) {
        int years = state.years(ticksPerYear());
        ActivityDefinition fallback = null;
        for (ActivityDefinition activity : content().activities().values()) if (activity.purpose() == purpose) {
            if (activity.ageAllows(years)) return activity;
            if (fallback == null) fallback = activity;
        }
        return fallback;
    }

    boolean interrupts(Resident resident, ActivityDefinition current) {
        LifeState state = resident.life();
        Purpose purpose = current.purpose();
        boolean busy = resident.body().busy();
        if (purpose == Purpose.COLLAPSE) return !state.stats().unconscious() && !needsRest(state) && busy;
        if (purpose == Purpose.MENTAL) return state.mental() == null || !state.mental().activityId().equals(current.id());
        if (busy) return true;
        if (state.stats().unconscious() || state.mental() != null) return true;
        if (needsRest(state)) return purpose != Purpose.BED_REST && purpose != Purpose.SLEEP;
        if (purpose == Purpose.BED_REST) return false;
        Block block = block(state, teen_pro_YT_port.dayTime(resident.body().position().dimension()));
        if (purpose == Purpose.SLEEP) return block != Block.SLEEP && state.rest() >= (block == Block.WORK ? 600_000 : 900_000);
        return !current.schedule().isEmpty() && !current.schedule().contains(ActivityDefinition.Block.valueOf(block.name()));
    }

    public ActivityDefinition choose(Resident resident, Block block, int dayTime, boolean raining, List<Resident> colony, boolean idle) {
        LifeState state = resident.life();
        int years = state.years(ticksPerYear());
        boolean sick = false;
        for (Condition condition : state.conditions()) sick |= condition.visible() && !condition.disease().chronic();
        boolean contagious = contagious(state);
        boolean bored = state.recreation() < teen_pro_YT_BORED || state.social() < teen_pro_YT_LONELY;
        boolean worker = state.stage(ticksPerYear()).mayWork();
        List<ActivityDefinition> options = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (ActivityDefinition activity : content().activities().values()) {
            if (!allowed(activity, block, years, idle, bored, worker) || !activity.ageAllows(years) || !activity.timeAllows(dayTime)) continue;
            if (activity.weather() == ActivityDefinition.Weather.CLEAR && raining || activity.weather() == ActivityDefinition.Weather.RAIN && !raining) continue;
            if (resolve(resident, activity, colony) == null) continue;
            double weight = utility(state, activity) * illness(activity, sick, contagious);
            if (weight <= 0) continue;
            options.add(activity); weights.add(weight);
        }
        if (options.isEmpty()) return null;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) order.add(i);
        order.sort(Comparator.comparing((Integer i) -> weights.get(i)).reversed().thenComparing(i -> options.get(i).id()));
        List<ActivityDefinition> top = new ArrayList<>();
        List<Double> topWeights = new ArrayList<>();
        for (int i = 0; i < Math.min(3, order.size()); i++) { top.add(options.get(order.get(i))); topWeights.add(weights.get(order.get(i))); }
        return weightedPick(top, topWeights, teen_pro_YT_random);
    }

    private static boolean allowed(ActivityDefinition activity, Block block, int years, boolean idle, boolean bored, boolean worker) {
        Purpose purpose = activity.purpose();
        if (purpose == Purpose.MENTAL || purpose == Purpose.COLLAPSE || purpose == Purpose.BED_REST) return false;
        if (block == Block.SLEEP) return purpose == Purpose.SLEEP;
        if (purpose == Purpose.SLEEP) return false;
        boolean scheduled = activity.schedule().contains(ActivityDefinition.Block.valueOf(block.name()));
        if (idle) return purpose == Purpose.IDLE || purpose == Purpose.MEDICAL || purpose == Purpose.CARE || purpose == Purpose.LEISURE && (scheduled || bored);
        if (years < 13 && purpose == Purpose.CHILD) return true;
        if (block == Block.WORK && !worker && years >= 13 && purpose == Purpose.LEISURE) return true;
        return (purpose == Purpose.LEISURE || purpose == Purpose.CARE || purpose == Purpose.MEDICAL || purpose == Purpose.IDLE || purpose == Purpose.CHILD)
                && scheduled;
    }

    double utility(LifeState state, ActivityDefinition activity) {
        double weight = LifeRules.fraction(activity.weight());
        double need = 1;
        if (activity.recreation() > 0) need += 2.5 * (1 - LifeRules.fraction(state.recreation()));
        if (activity.social() > 0) need += 2.0 * (1 - LifeRules.fraction(state.social()));
        if (activity.rest() > 0) need += 2.0 * (1 - LifeRules.fraction(state.rest()));
        if (activity.hygiene() > 0) need += 3.0 * (1 - LifeRules.fraction(state.hygiene()));
        weight *= need;
        for (String tag : activity.tags()) weight *= state.stats().activityWeight(tag);
        if (activity.joyKind() != null) weight *= 1 - 0.8 * LifeRules.fraction(state.tolerance(activity.joyKind()));
        if (activity.purpose() == Purpose.MEDICAL) weight *= HealthEngine.needsCare(state) ? 4 : 0;
        if (activity.tags().contains("isolate")) weight *= contagious(state) ? 3 : 0;
        if (activity.purpose() == Purpose.CARE) weight *= state.mood() >= 30 ? 1.2 : 0.6;
        return weight * (0.75 + 0.5 * teen_pro_YT_random.nextDouble());
    }

    static double illness(ActivityDefinition activity, boolean sick, boolean contagious) {
        if (activity.allowSick() || !sick && !contagious) return 1;
        var tags = activity.tags();
        if (sick && (tags.contains("sport") || tags.contains("crowd") || tags.contains("explore"))) return 0;
        double factor = sick ? 0.6 : 1;
        if (contagious && (tags.contains("social") || tags.contains("crowd") || tags.contains("romance") || tags.contains("care") || tags.contains("play"))) factor *= 0.1;
        return factor;
    }

    static boolean contagious(LifeState state) {
        for (Condition condition : state.conditions()) if (condition.visible() && condition.contagiousNow()) return true;
        return false;
    }

    Target resolve(Resident resident, ActivityDefinition activity, List<Resident> colony) {
        Target target = place(resident, activity, colony);
        Purpose purpose = activity.purpose();
        if (target == null && (purpose == Purpose.SLEEP || purpose == Purpose.BED_REST || purpose == Purpose.COLLAPSE || purpose == Purpose.MENTAL)) {
            Territory territory = teen_pro_YT_port.territory(resident.life().colonyId());
            WorldPosition here = resident.body().position();
            if (territory != null && territory.dimension().equals(here.dimension())) target = new Target(clamp(territory, here), null);
        }
        return target;
    }

    private Target place(Resident resident, ActivityDefinition activity, List<Resident> colony) {
        LifeState state = resident.life();
        WorldPosition here = resident.body().position();
        Territory territory = teen_pro_YT_port.territory(state.colonyId());
        if (territory == null || !territory.dimension().equals(here.dimension())) return null;
        Place place = activity.place();
        return switch (place) {
            case HERE -> new Target(clamp(territory, here), null);
            case WANDER, SKY, AWAY -> {
                int radius = place == Place.AWAY ? activity.radius() * 2 : activity.radius();
                int dx = radius == 0 ? 0 : teen_pro_YT_random.nextInt(2 * radius + 1) - radius, dz = radius == 0 ? 0 : teen_pro_YT_random.nextInt(2 * radius + 1) - radius;
                yield new Target(clamp(territory, new WorldPosition(here.dimension(), here.x() + dx, here.y(), here.z() + dz)), null);
            }
            case PARTNER, LOVER -> person(resident, colony, other -> {
                Bond partner = state.partner();
                return partner != null && partner.other().equals(other.life().citizenId()) && (place == Place.PARTNER || partner.kind() == BondKind.LOVER || partner.kind() == BondKind.FIANCE);
            }, territory);
            case FRIEND -> person(resident, colony, other -> Relations.friend(state, other.life().citizenId()), territory);
            case PARENT -> person(resident, colony, other -> { Bond bond = state.bond(other.life().citizenId()); return bond != null && bond.kind() == BondKind.PARENT; }, territory);
            case BABY -> person(resident, colony, other -> { Bond bond = state.bond(other.life().citizenId());
                return bond != null && bond.kind() == BondKind.CHILD && other.life().years(ticksPerYear()) < 3; }, territory);
            case DOCTOR -> {
                Resident healer = healer(resident, colony, false);
                yield healer == null || !territory.contains(healer.body().position()) ? null : new Target(healer.body().position(), healer.life().citizenId());
            }
            case CROWD -> person(resident, colony, other -> true, territory);
            case VIEW, FLOWERS, WATER, FIRE, BELL, BOOKS, MUSIC, BED, TREES -> {
                if (place == Place.BED && activity.purpose() == Purpose.SLEEP) {
                    Target shared = besidePartner(state, colony, territory);
                    if (shared != null) yield shared;
                }
                WorldPosition best = null;
                double bestDistance = teen_pro_YT_PLACE_RANGE;
                for (WorldPosition candidate : teen_pro_YT_port.places(state.colonyId(), place)) {
                    double distance = LifeRules.distance(here, candidate);
                    if (territory.contains(candidate) && distance <= bestDistance) { best = candidate; bestDistance = distance; }
                }
                yield best == null ? null : new Target(best, null);
            }
        };
    }

    private Target besidePartner(LifeState state, List<Resident> colony, Territory territory) {
        Bond partner = state.partner();
        if (partner == null) return null;
        for (Resident other : colony) {
            if (!other.life().citizenId().equals(partner.other()) || !other.present()) continue;
            Activity activity = other.life().activity();
            ActivityDefinition definition = activity == null ? null : content().activity(activity.activityId());
            if (definition == null || definition.purpose() != Purpose.SLEEP) return null;
            WorldPosition spot = activity.target();
            WorldPosition best = null;
            double bestDistance = 2.5;
            for (WorldPosition bed : teen_pro_YT_port.places(state.colonyId(), Place.BED)) {
                double distance = LifeRules.distance(spot, bed);
                if (distance > 0 && distance <= bestDistance && territory.contains(bed)) { best = bed; bestDistance = distance; }
            }
            if (best == null) best = clamp(territory, new WorldPosition(spot.dimension(), spot.x() + 1, spot.y(), spot.z()));
            return new Target(best, null);
        }
        return null;
    }

    private Target person(Resident resident, List<Resident> colony, java.util.function.Predicate<Resident> wanted, Territory territory) {
        Resident best = null;
        double bestDistance = teen_pro_YT_PLACE_RANGE;
        for (Resident other : colony) {
            if (other.life() == resident.life() || !other.present() || !wanted.test(other)) continue;
            double distance = LifeRules.distance(resident.body().position(), other.body().position());
            if (distance <= bestDistance && territory.contains(other.body().position())) { best = other; bestDistance = distance; }
        }
        return best == null ? null : new Target(best.body().position(), best.life().citizenId());
    }

    record Target(WorldPosition position, UUID partner) {}

    public Resident healer(Resident patient, List<Resident> colony, boolean idleOnly) {
        Resident best = null;
        int bestLevel = idleOnly ? -1 : 0;
        double bestDistance = Double.MAX_VALUE;
        for (Resident other : colony) {
            LifeState state = other.life();
            if (other == patient || state == patient.life() || !other.present() || !state.stage(ticksPerYear()).mayWork()) continue;
            if (idleOnly && (other.body().busy() || !interruptible(state) || state.mental() != null || needsRest(state) || HealthEngine.needsCare(state))) continue;
            int level = state.level(colonyloom.core.life.content.Skill.MEDICINE) + (LifeIds.teen_pro_YT_DOCTOR.equals(other.body().professionId()) ? 10 : 0);
            double distance = LifeRules.distance(patient.body().position(), other.body().position());
            if (distance > teen_pro_YT_PLACE_RANGE) continue;
            if (level > bestLevel || level == bestLevel && distance < bestDistance) { best = other; bestLevel = level; bestDistance = distance; }
        }
        return best;
    }

    boolean interruptible(LifeState state) {
        Activity current = state.activity();
        if (current == null) return true;
        ActivityDefinition definition = content().activity(current.activityId());
        return definition == null || definition.purpose() == Purpose.LEISURE || definition.purpose() == Purpose.IDLE;
    }

    public boolean tend(Resident healer, Resident patient) {
        ActivityDefinition activity = content().activity(LifeIds.teen_pro_YT_TEND_PATIENT);
        LifeState state = healer.life();
        if (activity == null || healer.body().busy() || !interruptible(state)) return false;
        Territory territory = teen_pro_YT_port.territory(state.colonyId());
        if (territory == null || !territory.contains(patient.body().position())) return false;
        cancel(state);
        long duration = LifeRules.between(teen_pro_YT_random, activity.minDuration(), activity.maxDuration());
        state.activity(new Activity(activity.id(), patient.body().position(), patient.life().citizenId(), duration, 0, false));
        state.idleTicks(0);
        teen_pro_YT_port.lookAt(state.citizenId(), patient.life().citizenId());
        life().changed(state.citizenId());
        return true;
    }

    static WorldPosition clamp(Territory territory, WorldPosition position) {
        return new WorldPosition(territory.dimension(), Math.max(territory.minX(), Math.min(territory.maxX(), position.x())), position.y(),
                Math.max(territory.minZ(), Math.min(territory.maxZ(), position.z())));
    }

    public boolean start(Resident resident, ActivityDefinition activity, List<Resident> colony, boolean idle) {
        LifeState state = resident.life();
        Target target = resolve(resident, activity, colony);
        if (target == null) return false;
        long duration = LifeRules.between(teen_pro_YT_random, activity.minDuration(), activity.maxDuration());
        if (idle) duration = Math.min(duration, teen_pro_YT_IDLE_ACTIVITY_MAX);
        if (state.mental() != null && activity.id().equals(state.mental().activityId())) duration = Math.max(20, Math.min(48_000, state.mental().remaining()));
        state.activity(new Activity(activity.id(), target.position(), target.partner(), duration, 0, false));
        state.idleTicks(0);
        if (activity.emote() != Emote.NONE) teen_pro_YT_port.emote(state.citizenId(), activity.emote());
        if (activity.speech() > 0 && activity.purpose() != Purpose.MENTAL && life().settings().speech() && LifeRules.roll(teen_pro_YT_random, 0.35))
            teen_pro_YT_port.speak(state.citizenId(), Thoughts.line(Thoughts.topic(activity.id()), activity.speech(), teen_pro_YT_random));
        if (target.partner() != null) teen_pro_YT_port.lookAt(state.citizenId(), target.partner());
        life().changed(state.citizenId());
        return true;
    }

    void complete(LifeState state, Activity current, ActivityDefinition definition) {
        close(state, definition);
        teen_pro_YT_completion.completed(state, current, definition);
    }

    public void cancel(LifeState state) {
        if (state.activity() == null) return;
        close(state, null);
    }

    private void close(LifeState state, ActivityDefinition rewarded) {
        Activity current = state.activity();
        state.activity(null);
        if (rewarded != null && rewarded.memory() != null) Thoughts.remember(content(), state, rewarded.memory(), current == null ? null : current.partner());
        life().changed(state.citizenId());
    }

    static <T> T weightedPick(List<T> items, List<Double> weights, RandomGenerator random) {
        double total = 0;
        for (double weight : weights) total += Math.max(0, weight);
        if (total <= 0) return items.get(random.nextInt(items.size()));
        double pick = random.nextDouble() * total;
        for (int i = 0; i < items.size(); i++) {
            pick -= Math.max(0, weights.get(i));
            if (pick < 0) return items.get(i);
        }
        return items.getLast();
    }
}
