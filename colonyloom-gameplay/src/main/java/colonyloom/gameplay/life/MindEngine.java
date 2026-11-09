package colonyloom.gameplay.life;

import colonyloom.core.life.RichText;
import colonyloom.core.life.content.ActivityDefinition;
import colonyloom.core.life.content.ActivityDefinition.MentalLevel;
import colonyloom.core.life.content.Emote;
import colonyloom.core.life.content.EnvironmentFlag;
import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.LifeIds;
import colonyloom.core.life.content.Situation;
import colonyloom.core.life.content.Stat;
import colonyloom.core.life.content.StatBlock;
import colonyloom.core.life.content.ThoughtDefinition;
import colonyloom.core.life.content.TraitDefinition;
import colonyloom.core.life.state.Condition;
import colonyloom.core.life.state.Journal;
import colonyloom.core.life.state.LifeRegistry;
import colonyloom.core.life.state.LifeState;
import colonyloom.core.life.state.Records.Acquaintance;
import colonyloom.core.life.state.Records.Bond;
import colonyloom.core.life.state.Records.BondKind;
import colonyloom.core.life.state.Records.Memory;
import colonyloom.core.life.state.Records.Mental;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

public final class MindEngine {
    static final int teen_pro_YT_REST_FALL = 1_000_000, teen_pro_YT_RECREATION_FALL = 600_000, teen_pro_YT_SOCIAL_FALL = 800_000, teen_pro_YT_HYGIENE_FALL = 300_000;
    static final int teen_pro_YT_TOLERANCE_DECAY = 300_000;
    static final double teen_pro_YT_MOOD_SPEED = 48;
    public static final int teen_pro_YT_MINOR_BREAK = 35, teen_pro_YT_MAJOR_BREAK = 20, teen_pro_YT_EXTREME_BREAK = 5;
    public static final int teen_pro_YT_BASE_MOOD = 40;
    static final List<String> teen_pro_YT_INSPIRATIONS = List.of("work", "social", "creative");

    public record MoodPart(String label, int value) {}

    private final LifeRegistry teen_pro_YT_life;
    private final LifePort teen_pro_YT_port;
    private final RandomGenerator teen_pro_YT_random;

    public MindEngine(LifeRegistry life, LifePort port, RandomGenerator random) {
        this.teen_pro_YT_life = Objects.requireNonNull(life); this.teen_pro_YT_port = Objects.requireNonNull(port); this.teen_pro_YT_random = Objects.requireNonNull(random);
    }

    private LifeRegistry life() { return teen_pro_YT_life; }
    private LifeContent content() { return life().content(); }

    public void update(Resident resident, long elapsed, Set<EnvironmentFlag> environment, ActivityDefinition current) {
        if (elapsed <= 0 || !resident.alive()) return;
        LifeState state = resident.life();
        needs(state, elapsed, current);
        state.advanceMemories(elapsed);
        int target = targetMood(resident, environment);
        double step = teen_pro_YT_MOOD_SPEED * LifeRules.days(elapsed);
        int whole = (int) step;
        if (LifeRules.roll(teen_pro_YT_random, step - whole)) whole++;
        int delta = target - state.mood();
        state.mood(state.mood() + Math.max(-whole, Math.min(whole, delta)));
        breaks(resident, elapsed);
        inspiration(resident, elapsed);
    }

    void needs(LifeState state, long elapsed, ActivityDefinition current) {
        StatBlock stats = state.stats();
        double days = LifeRules.days(elapsed);
        boolean asleep = current != null && (current.purpose() == ActivityDefinition.Purpose.SLEEP
                || current.purpose() == ActivityDefinition.Purpose.BED_REST || current.purpose() == ActivityDefinition.Purpose.COLLAPSE);
        double awake = asleep ? 0.5 : 1.0;
        double rest = asleep ? 0 : -teen_pro_YT_REST_FALL * stats.stat(Stat.REST_FALL);
        double recreation = -teen_pro_YT_RECREATION_FALL * stats.stat(Stat.RECREATION_FALL) * awake;
        double social = -teen_pro_YT_SOCIAL_FALL * stats.stat(Stat.SOCIAL_FALL) * awake;
        double hygiene = -teen_pro_YT_HYGIENE_FALL * stats.stat(Stat.HYGIENE_FALL);
        if (current != null) {
            rest += current.rest() * (current.rest() > 0 ? stats.stat(Stat.REST_GAIN) : 1);
            social += current.social();
            hygiene += current.hygiene();
            if (current.recreation() > 0) {
                double fresh = 1 - 0.9 * LifeRules.fraction(current.joyKind() == null ? 0 : state.tolerance(current.joyKind()));
                double inspired = "creative".equals(state.inspiration()) ? 1.5 : 1.0;
                double gained = current.recreation() * fresh * inspired;
                recreation += gained;
                if (current.joyKind() != null) state.tolerance(current.joyKind(), state.tolerance(current.joyKind()) + (int) Math.round(gained * days * 0.5));
            } else recreation += current.recreation();
        }
        for (var entry : List.copyOf(state.tolerance().entrySet()))
            state.tolerance(entry.getKey(), entry.getValue() - (int) Math.round(teen_pro_YT_TOLERANCE_DECAY * days));
        state.rest(state.rest() + (int) Math.round(rest * days));
        state.recreation(state.recreation() + (int) Math.round(recreation * days));
        state.social(state.social() + (int) Math.round(social * days));
        state.hygiene(state.hygiene() + (int) Math.round(hygiene * days));
    }

    public Set<Situation> situations(Resident resident, Set<EnvironmentFlag> environment) {
        LifeState state = resident.life();
        EnumSet<Situation> result = EnumSet.noneOf(Situation.class);
        if (state.rest() < 100_000) result.add(Situation.REST_EXHAUSTED); else if (state.rest() < 300_000) result.add(Situation.REST_LOW);
        if (state.recreation() < 50_000) result.add(Situation.RECREATION_EMPTY); else if (state.recreation() < 300_000) result.add(Situation.RECREATION_LOW);
        if (state.social() < 50_000) result.add(Situation.SOCIAL_EMPTY); else if (state.social() < 300_000) result.add(Situation.SOCIAL_LOW);
        if (state.hygiene() < 100_000) result.add(Situation.HYGIENE_FILTHY); else if (state.hygiene() < 300_000) result.add(Situation.HYGIENE_LOW);
        int food = resident.body().food();
        int years = state.years(life().settings().ticksPerYear());
        if (years >= 3) { if (food == 0) result.add(Situation.STARVING); else if (food <= 6) result.add(Situation.HUNGRY); }
        double pain = state.stats().pain();
        if (pain >= 0.6) result.add(Situation.PAIN_EXTREME); else if (pain >= 0.3) result.add(Situation.PAIN_SERIOUS); else if (pain >= 0.1) result.add(Situation.PAIN_MINOR);
        boolean sick = false;
        for (Condition condition : state.conditions()) sick |= condition.visible() && !condition.disease().chronic();
        if (sick) result.add(Situation.SICK);
        if (state.pregnancy() != null) result.add(Situation.PREGNANT);
        Bond partner = state.partner();
        if (partner != null) result.add(partner.kind() == BondKind.SPOUSE ? Situation.HAS_SPOUSE : Situation.HAS_LOVER);
        if (years >= 6) {
            int friends = 0;
            for (Acquaintance acquaintance : state.acquaintances()) if (Relations.friend(state, acquaintance.other())) friends++;
            if (friends == 0) result.add(Situation.NO_FRIENDS); else if (friends >= 3) result.add(Situation.MANY_FRIENDS);
        }
        boolean outdoors = environment.contains(EnvironmentFlag.OUTDOORS);
        if (outdoors && environment.contains(EnvironmentFlag.RAIN)) result.add(Situation.RAIN_OUTDOORS);
        else if (outdoors && !environment.contains(EnvironmentFlag.NIGHT) && !environment.contains(EnvironmentFlag.COLD) && !environment.contains(EnvironmentFlag.HOT))
            result.add(Situation.NICE_WEATHER);
        if (outdoors && environment.contains(EnvironmentFlag.COLD)) result.add(Situation.COLD_OUTSIDE);
        if (outdoors && environment.contains(EnvironmentFlag.HOT)) result.add(Situation.HOT_OUTSIDE);
        if (environment.contains(EnvironmentFlag.CROWDED)) result.add(Situation.CROWDED);
        if (environment.contains(EnvironmentFlag.NEAR_FLOWERS)) result.add(Situation.NEAR_FLOWERS);
        if (environment.contains(EnvironmentFlag.UNDERGROUND)) result.add(Situation.UNDERGROUND);
        if (environment.contains(EnvironmentFlag.NETHER)) result.add(Situation.IN_NETHER);
        for (Bond bond : state.bonds()) if (bond.kind() == BondKind.CHILD) {
            LifeState child = life().state(bond.other());
            if (child != null && !child.deceased() && child.years(life().settings().ticksPerYear()) < 3) { result.add(Situation.PARENT_OF_BABY); break; }
        }
        if (!sick && state.rest() > 700_000 && food >= 12 && state.conditions().isEmpty()) result.add(Situation.HEALTHY_AND_RESTED);
        if (years >= 65) result.add(Situation.ELDERLY);
        return result;
    }

    public int targetMood(Resident resident, Set<EnvironmentFlag> environment) {
        int total = 0;
        for (MoodPart part : breakdown(resident, environment)) total += part.value();
        return Math.max(0, Math.min(100, total));
    }

    public List<MoodPart> breakdown(Resident resident, Set<EnvironmentFlag> environment) {
        LifeState state = resident.life();
        List<MoodPart> parts = new ArrayList<>();
        parts.add(new MoodPart("@colonyloom.mood.base", teen_pro_YT_BASE_MOOD));
        int character = (int) Math.round(state.stats().stat(Stat.MOOD));
        if (character != 0) parts.add(new MoodPart("@colonyloom.mood.character", character));
        List<Memory> memories = new ArrayList<>(state.memories());
        memories.sort(java.util.Comparator.comparing(Memory::thoughtId));
        int start = 0;
        while (start < memories.size()) {
            int end = start;
            while (end < memories.size() && memories.get(end).thoughtId().equals(memories.get(start).thoughtId())) end++;
            List<Memory> group = new ArrayList<>(memories.subList(start, end));
            int value = (int) Math.round(Relations.stacked(group, false));
            if (value != 0) parts.add(new MoodPart(RichText.of("colonyloom.mood.memory", RichText.key("colonyloom.thought." + Thoughts.topic(group.getFirst().thoughtId())),
                    Integer.toString(group.size())), value));
            start = end;
        }
        for (Situation situation : situations(resident, environment)) {
            ThoughtDefinition thought = content().situation(situation);
            if (thought != null && thought.mood() != 0) parts.add(new MoodPart(RichText.of("colonyloom.mood.situation", RichText.key("colonyloom.thought." + Thoughts.topic(thought.id()))), thought.mood()));
        }
        return parts;
    }

    public int threshold(LifeState state, MentalLevel level) {
        int shift = (int) Math.round(state.stats().stat(Stat.BREAK_THRESHOLD));
        for (String id : state.traits()) {
            TraitDefinition trait = content().traits().get(id);
            if (trait != null) shift += trait.breakThreshold();
        }
        int base = switch (level) { case MINOR -> teen_pro_YT_MINOR_BREAK; case MAJOR -> teen_pro_YT_MAJOR_BREAK; case EXTREME -> teen_pro_YT_EXTREME_BREAK; };
        return Math.max(0, Math.min(90, base + shift));
    }

    void breaks(Resident resident, long elapsed) {
        LifeState state = resident.life();
        Mental mental = state.mental();
        if (mental != null) {
            if (mental.over()) {
                state.mental(null);
                state.breakCooldown(LifeRules.teen_pro_YT_DAY / 2);
                Thoughts.remember(content(), state, LifeIds.teen_pro_YT_CATHARSIS, null);
                life().changed(state.citizenId());
            }
            return;
        }
        if (state.breakCooldown() > 0) return;
        MentalLevel level = state.mood() < threshold(state, MentalLevel.EXTREME) ? MentalLevel.EXTREME
                : state.mood() < threshold(state, MentalLevel.MAJOR) ? MentalLevel.MAJOR
                : state.mood() < threshold(state, MentalLevel.MINOR) ? MentalLevel.MINOR : null;
        if (level == null) return;
        double perDay = switch (level) { case MINOR -> 0.5; case MAJOR -> 1.0; case EXTREME -> 2.0; } * state.stats().stat(Stat.BREAK_CHANCE);
        if (LifeRules.roll(teen_pro_YT_random, LifeRules.chanceOver(perDay, elapsed))) startBreak(resident, level);
    }

    public ActivityDefinition startBreak(Resident resident, MentalLevel level) {
        LifeState state = resident.life();
        int years = state.years(life().settings().ticksPerYear());
        for (int ordinal = level.ordinal(); ordinal >= 0; ordinal--) {
            MentalLevel candidateLevel = MentalLevel.values()[ordinal];
            List<ActivityDefinition> candidates = new ArrayList<>();
            List<Double> weights = new ArrayList<>();
            for (ActivityDefinition activity : content().activities().values()) {
                if (activity.purpose() != ActivityDefinition.Purpose.MENTAL || activity.mental() != candidateLevel || !activity.ageAllows(years)) continue;
                double weight = LifeRules.fraction(activity.weight());
                for (String tag : activity.tags()) weight *= state.stats().activityWeight(tag);
                if (weight <= 0) continue;
                candidates.add(activity); weights.add(weight);
            }
            if (candidates.isEmpty()) continue;
            ActivityDefinition chosen = Brain.weightedPick(candidates, weights, teen_pro_YT_random);
            long duration = LifeRules.between(teen_pro_YT_random, chosen.minDuration(), chosen.maxDuration());
            state.mental(new Mental(chosen.id(), candidateLevel, duration));
            teen_pro_YT_port.speak(state.citizenId(), Thoughts.line("break." + Thoughts.topic(chosen.id()), Math.max(1, chosen.speech()), teen_pro_YT_random));
            teen_pro_YT_port.emote(state.citizenId(), chosen.emote() == Emote.NONE ? Emote.SAD : chosen.emote());
            life().record(state.colonyId(), teen_pro_YT_port.gameTime(), Journal.Category.MOOD, RichText.of("colonyloom.journal.break",
                    RichText.literal(state.fullName()), RichText.key("colonyloom.activity." + Thoughts.topic(chosen.id()))), state.citizenId());
            life().changed(state.citizenId());
            return chosen;
        }
        return null;
    }

    void inspiration(Resident resident, long elapsed) {
        LifeState state = resident.life();
        if (state.inspiration() != null || state.mental() != null || state.mood() < 80) return;
        if (!LifeRules.roll(teen_pro_YT_random, LifeRules.chanceOver(0.15 * state.stats().stat(Stat.INSPIRATION), elapsed))) return;
        String kind = teen_pro_YT_INSPIRATIONS.get(teen_pro_YT_random.nextInt(teen_pro_YT_INSPIRATIONS.size()));
        state.inspiration(kind, LifeRules.teen_pro_YT_DAY);
        Thoughts.remember(content(), state, LifeIds.teen_pro_YT_INSPIRED, null);
        teen_pro_YT_port.emote(state.citizenId(), Emote.SPARKLE);
        life().record(state.colonyId(), teen_pro_YT_port.gameTime(), Journal.Category.MOOD, RichText.of("colonyloom.journal.inspired",
                RichText.literal(state.fullName()), RichText.key("colonyloom.inspiration." + kind)), state.citizenId());
    }
}
