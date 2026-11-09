package colonyloom.gameplay.life;

import colonyloom.core.life.RichText;
import colonyloom.core.life.content.ActivityDefinition;
import colonyloom.core.life.content.Capacity;
import colonyloom.core.life.content.Emote;
import colonyloom.core.life.content.InteractionDefinition;
import colonyloom.core.life.content.InteractionDefinition.Kind;
import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.LifeIds;
import colonyloom.core.life.content.NamePool;
import colonyloom.core.life.content.Sex;
import colonyloom.core.life.content.Stat;
import colonyloom.core.life.content.ThoughtDefinition;
import colonyloom.core.life.state.Journal;
import colonyloom.core.life.state.LifeRegistry;
import colonyloom.core.life.state.LifeState;
import colonyloom.core.life.state.Records.Acquaintance;
import colonyloom.core.life.state.Records.Bond;
import colonyloom.core.life.state.Records.BondKind;
import colonyloom.core.life.state.Records.Pregnancy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.random.RandomGenerator;

public final class SocialEngine {
    static final double teen_pro_YT_RANGE = 6.0;
    static final long teen_pro_YT_COOLDOWN_MIN = 600, teen_pro_YT_COOLDOWN_MAX = 1_400, teen_pro_YT_RECIPIENT_COOLDOWN = 200;
    static final int teen_pro_YT_RAPPORT_PER_OPINION = 25;
    static final int teen_pro_YT_RAPPORT_FADE_PER_DAY = 50;
    static final double teen_pro_YT_CONCEPTION_PER_NIGHT = 0.08;

    private final LifeRegistry teen_pro_YT_life;
    private final LifePort teen_pro_YT_port;
    private final HealthEngine teen_pro_YT_health;
    private final RandomGenerator teen_pro_YT_random;

    public SocialEngine(LifeRegistry life, LifePort port, HealthEngine health, RandomGenerator random) {
        this.teen_pro_YT_life = Objects.requireNonNull(life); this.teen_pro_YT_port = Objects.requireNonNull(port);
        this.teen_pro_YT_health = Objects.requireNonNull(health); this.teen_pro_YT_random = Objects.requireNonNull(random);
    }

    private LifeRegistry life() { return teen_pro_YT_life; }
    private LifeContent content() { return life().content(); }
    private long ticksPerYear() { return life().settings().ticksPerYear(); }

    public boolean canSocialize(Resident resident) {
        if (!resident.present()) return false;
        LifeState state = resident.life();
        if (state.stats().unconscious() || state.stats().capacity(Capacity.TALKING) < 0.2) return false;
        var activity = state.activity();
        if (activity != null && activity.performing()) {
            ActivityDefinition definition = content().activity(activity.activityId());
            if (definition != null && (definition.purpose() == ActivityDefinition.Purpose.SLEEP || definition.purpose() == ActivityDefinition.Purpose.COLLAPSE)) return false;
        }
        if (state.mental() != null) {
            ActivityDefinition definition = content().activity(state.mental().activityId());
            return definition != null && definition.tags().contains("insult");
        }
        return true;
    }

    public void fade(LifeState state, long elapsed) {
        int amount = (int) Math.round(teen_pro_YT_RAPPORT_FADE_PER_DAY * LifeRules.days(elapsed));
        for (Acquaintance acquaintance : state.acquaintances()) {
            acquaintance.advance(elapsed);
            if (amount > 0 && acquaintance.sinceLast() > LifeRules.teen_pro_YT_DAY) acquaintance.fade(amount);
        }
    }

    public InteractionDefinition tryInteract(Resident initiator, List<Resident> colony) {
        LifeState state = initiator.life();
        if (state.socialCooldown() > 0 || !canSocialize(initiator)) return null;
        if (Brain.contagious(state) && teen_pro_YT_random.nextBoolean()) { state.socialCooldown(teen_pro_YT_COOLDOWN_MIN); return null; }
        List<Resident> nearby = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        UUID companion = state.activity() == null ? null : state.activity().partner();
        for (Resident other : colony) {
            if (other.life() == state || !canSocialize(other)) continue;
            if (LifeRules.distance(initiator.body().position(), other.body().position()) > teen_pro_YT_RANGE) continue;
            double weight = Math.max(0.2, (Relations.opinion(state, other.life().citizenId()) + 60) / 60.0);
            if (other.life().citizenId().equals(companion)) weight *= 3;
            if (Brain.contagious(other.life())) weight *= 0.3;
            nearby.add(other); weights.add(weight);
        }
        if (nearby.isEmpty()) return null;
        Resident recipient = Brain.weightedPick(nearby, weights, teen_pro_YT_random);
        return interact(initiator, recipient, null);
    }

    public InteractionDefinition interact(Resident initiator, Resident recipient, InteractionDefinition forced) {
        LifeState a = initiator.life(), b = recipient.life();
        InteractionDefinition chosen = forced;
        if (chosen == null) {
            List<InteractionDefinition> options = new ArrayList<>();
            List<Double> weights = new ArrayList<>();
            boolean spree = a.mental() != null;
            for (InteractionDefinition interaction : new TreeMap<>(content().interactions()).values()) {
                if (spree && interaction.kind() != Kind.INSULT) continue;
                double weight = weight(interaction, initiator, recipient);
                if (weight <= 0) continue;
                options.add(interaction); weights.add(spree ? weight * 10 : weight);
            }
            if (options.isEmpty()) return null;
            chosen = Brain.weightedPick(options, weights, teen_pro_YT_random);
        }
        boolean success = LifeRules.roll(teen_pro_YT_random, successChance(chosen, initiator, recipient));
        apply(chosen, initiator, recipient, success);
        return chosen;
    }

    public double weight(InteractionDefinition interaction, Resident initiator, Resident recipient) {
        LifeState a = initiator.life(), b = recipient.life();
        var requires = interaction.requires();
        int ageA = a.years(ticksPerYear()), ageB = b.years(ticksPerYear());
        if (ageA < requires.initiatorMinAge() || ageA > requires.initiatorMaxAge() || ageB < requires.recipientMinAge() || ageB > requires.recipientMaxAge()) return 0;
        int opinion = Relations.opinion(a, b.citizenId());
        if (opinion < requires.minOpinion() || opinion > requires.maxOpinion()) return 0;
        if (a.mood() > requires.initiatorMoodMax() || b.mood() > requires.recipientMoodMax()) return 0;
        if (requires.notRelatives() && a.related(b.citizenId())) return 0;
        Bond romance = a.romance(b.citizenId());
        Bond family = a.bond(b.citizenId());
        boolean relation = switch (requires.relation()) {
            case ANY -> true;
            case SINGLE -> a.partner() == null && b.partner() == null;
            case LOVER -> romance != null && romance.kind() == BondKind.LOVER;
            case FIANCE -> romance != null && romance.kind() == BondKind.FIANCE;
            case SPOUSE -> romance != null && romance.kind() == BondKind.SPOUSE;
            case PARTNER -> romance != null && romance.kind().romantic();
            case FAMILY -> a.related(b.citizenId());
            case PARENT_TO_CHILD -> family != null && family.kind() == BondKind.CHILD;
            case FRIEND -> opinion >= Relations.teen_pro_YT_FRIEND;
            case RIVAL -> opinion <= Relations.teen_pro_YT_RIVAL;
        };
        if (!relation) return 0;
        double weight = LifeRules.fraction(interaction.weight()) * a.stats().interactionWeight(interaction.id());
        if (requires.romance()) {
            double attraction = Relations.attraction(content(), a, b, ticksPerYear());
            if (attraction <= 0) return 0;
            weight *= attraction * (1 + Math.max(0, opinion) / 40.0);
        }
        weight *= switch (interaction.kind()) {
            case INSULT -> (1 + Math.max(0, -opinion) / 25.0) * (a.mood() < 30 ? 2 : 1) * (1 + Math.max(0, -chemistry(a, b.citizenId())) / 6.0);
            case COMFORT -> b.mood() < 35 ? 3 : 0.15;
            case BREAKUP -> opinion < 0 ? 1 + (-opinion) / 20.0 : 0;
            case PROPOSAL -> opinion >= 45 ? 1 : 0;
            case CARE -> ageB < 13 ? 2 : 0.5;
            case TEACH -> ageA >= ageB + 5 ? 1 : 0;
            default -> 1;
        };
        return weight;
    }

    private static int chemistry(LifeState self, UUID other) {
        Acquaintance acquaintance = self.acquaintance(other);
        return acquaintance == null ? 0 : acquaintance.compatibility();
    }

    double successChance(InteractionDefinition interaction, Resident initiator, Resident recipient) {
        double chance = LifeRules.fraction(interaction.successChance());
        LifeState a = initiator.life(), b = recipient.life();
        int reverse = Relations.opinion(b, a.citizenId());
        chance *= switch (interaction.kind()) {
            case ROMANCE -> Math.max(0, Math.min(1, 0.3 + reverse / 80.0)) * Relations.attraction(content(), b, a, ticksPerYear());
            case PROPOSAL -> Math.max(0, Math.min(1, reverse / 70.0));
            default -> 1;
        };
        if ("social".equals(a.inspiration())) chance *= 1.3;
        return Math.max(0, Math.min(1, chance));
    }

    void apply(InteractionDefinition interaction, Resident initiator, Resident recipient, boolean success) {
        LifeState a = initiator.life(), b = recipient.life();
        Acquaintance ab = a.meet(b.citizenId(), Relations.compatibility(content(), a, b));
        Acquaintance ba = b.meet(a.citizenId(), Relations.compatibility(content(), b, a));
        var outcome = success ? interaction.success() : interaction.failure();
        remember(a, outcome.initiatorThought(), b.citizenId(), ab);
        remember(b, outcome.recipientThought(), a.citizenId(), ba);
        if (ab != null && outcome.initiatorThought() == null) ab.interacted(0);
        if (ba != null && outcome.recipientThought() == null) ba.interacted(0);
        double impact = a.stats().stat(Stat.SOCIAL_IMPACT) * ("social".equals(a.inspiration()) ? 1.5 : 1);
        a.social(a.social() + interaction.social());
        b.social(b.social() + (int) Math.round(interaction.recipientSocial() * impact));
        var skill = interaction.skill();
        if (skill != null && interaction.xp() > 0) {
            a.xp(skill, a.xp(skill) + (int) Math.round(interaction.xp() * a.stats().stat(Stat.LEARNING) * learning(a)));
            if (interaction.kind() == Kind.TEACH && success)
                b.xp(skill, b.xp(skill) + (int) Math.round(2.0 * interaction.xp() * b.stats().stat(Stat.LEARNING) * learning(b)));
        }
        if (success) change(interaction, initiator, recipient);
        teen_pro_YT_port.lookAt(a.citizenId(), b.citizenId());
        teen_pro_YT_port.lookAt(b.citizenId(), a.citizenId());
        if (interaction.speech() > 0 && life().settings().speech())
            teen_pro_YT_port.speak(a.citizenId(), success ? Thoughts.line(Thoughts.topic(interaction.id()), interaction.speech(), teen_pro_YT_random, b.givenName())
                    : Thoughts.line(Thoughts.topic(interaction.id()) + ".fail", 3, teen_pro_YT_random, b.givenName()));
        Emote emote = interaction.emote();
        if (emote != Emote.NONE) teen_pro_YT_port.emote(a.citizenId(), success || emote != Emote.HEART ? emote : Emote.SAD);
        if (interaction.journal() && interaction.success().change() == InteractionDefinition.Change.NONE)
            life().record(a.colonyId(), teen_pro_YT_port.gameTime(), Journal.Category.SOCIAL, RichText.of("colonyloom.journal.interaction",
                    RichText.literal(a.fullName()), RichText.key("colonyloom.interaction." + Thoughts.topic(interaction.id())), RichText.literal(b.fullName())), a.citizenId());
        teen_pro_YT_health.contact(initiator, recipient, LifeRules.fraction(interaction.intimacy()));
        a.socialCooldown(LifeRules.between(teen_pro_YT_random, teen_pro_YT_COOLDOWN_MIN, teen_pro_YT_COOLDOWN_MAX));
        b.socialCooldown(Math.max(b.socialCooldown(), teen_pro_YT_RECIPIENT_COOLDOWN));
    }

    private double learning(LifeState state) { return "work".equals(state.inspiration()) ? 2.0 : 1.0; }

    private void remember(LifeState self, String thoughtId, UUID other, Acquaintance acquaintance) {
        if (thoughtId == null) return;
        Thoughts.remember(content(), self, thoughtId, other);
        ThoughtDefinition thought = content().thought(thoughtId);
        if (acquaintance != null) acquaintance.interacted(thought == null ? 0 : thought.opinion() * teen_pro_YT_RAPPORT_PER_OPINION);
    }

    private void change(InteractionDefinition interaction, Resident initiator, Resident recipient) {
        LifeState a = initiator.life(), b = recipient.life();
        switch (interaction.success().change()) {
            case NONE -> {}
            case BECOME_LOVERS -> {
                if (a.partner() != null || b.partner() != null || a.related(b.citizenId()) || !pair(a, b, BondKind.LOVER)) return;
                teen_pro_YT_port.emote(b.citizenId(), Emote.HEART);
                journal(a, Journal.Category.FAMILY, "colonyloom.journal.lovers", b);
            }
            case ENGAGE -> {
                if (!partners(a, b) || !pair(a, b, BondKind.FIANCE)) return;
                Thoughts.remember(content(), a, LifeIds.teen_pro_YT_ENGAGED, b.citizenId());
                Thoughts.remember(content(), b, LifeIds.teen_pro_YT_ENGAGED, a.citizenId());
                teen_pro_YT_port.emote(b.citizenId(), Emote.HEART);
                journal(a, Journal.Category.FAMILY, "colonyloom.journal.engaged", b);
            }
            case MARRY -> { if (partners(a, b)) marry(initiator, recipient); else return; }
            case BREAK_UP -> {
                if (!partners(a, b) || !pair(a, b, BondKind.EX)) return;
                Thoughts.remember(content(), a, LifeIds.teen_pro_YT_BROKE_UP, b.citizenId());
                Thoughts.remember(content(), b, LifeIds.teen_pro_YT_GOT_DUMPED, a.citizenId());
                teen_pro_YT_port.emote(b.citizenId(), Emote.SAD);
                journal(a, Journal.Category.FAMILY, "colonyloom.journal.breakup", b);
            }
        }
        life().changed(a.citizenId());
        life().changed(b.citizenId());
    }

    static boolean partners(LifeState a, LifeState b) {
        Bond ab = a.romance(b.citizenId()), ba = b.romance(a.citizenId());
        return ab != null && ba != null && ab.kind().romantic() && ba.kind().romantic();
    }

    static boolean pair(LifeState a, LifeState b, BondKind kind) {
        Bond previous = a.romance(b.citizenId());
        if (!a.bond(b.citizenId(), kind)) return false;
        if (b.bond(a.citizenId(), kind)) return true;
        a.unbondRomance(b.citizenId());
        if (previous != null) a.bond(b.citizenId(), previous.kind());
        return false;
    }

    void marry(Resident initiator, Resident recipient) {
        LifeState a = initiator.life(), b = recipient.life();
        if (!pair(a, b, BondKind.SPOUSE)) return;
        Thoughts.remember(content(), a, LifeIds.teen_pro_YT_WEDDING, b.citizenId());
        Thoughts.remember(content(), b, LifeIds.teen_pro_YT_WEDDING, a.citizenId());
        if (a.sex() != b.sex()) {
            LifeState husband = a.sex() == Sex.MALE ? a : b, wife = husband == a ? b : a;
            String shared = familyForm(husband.familyName(), Sex.FEMALE);
            if (shared != null) wife.names(wife.givenName(), shared);
        }
        for (LifeState guest : life().states()) {
            if (guest == a || guest == b || guest.deceased() || !guest.colonyId().equals(a.colonyId())) continue;
            Thoughts.remember(content(), guest, LifeIds.teen_pro_YT_ATTENDED_WEDDING, null);
        }
        teen_pro_YT_port.emote(a.citizenId(), Emote.HEART); teen_pro_YT_port.emote(b.citizenId(), Emote.HEART);
        journal(a, Journal.Category.FAMILY, "colonyloom.journal.wedding", b);
    }

    String familyForm(String family, Sex sex) {
        for (NamePool pool : content().names().values()) for (NamePool.Family entry : pool.families())
            if (entry.male().equals(family) || entry.female().equals(family)) return entry.form(sex);
        return null;
    }

    private void journal(LifeState a, Journal.Category category, String key, LifeState b) {
        life().record(a.colonyId(), teen_pro_YT_port.gameTime(), category, RichText.of(key, RichText.literal(a.fullName()), RichText.literal(b.fullName())), a.citizenId());
    }

    public boolean tryConceive(Resident first, Resident second, long elapsed) {
        LifeState a = first.life(), b = second.life();
        Bond romance = a.romance(b.citizenId());
        if (romance == null || !romance.kind().romantic() || !first.alive() || !second.alive() || !Relations.couldConceive(a, b, ticksPerYear())) return false;
        LifeState mother = a.sex() == Sex.FEMALE ? a : b, father = mother == a ? b : a;
        double fertility = Math.min(1, mother.stats().capacity(Capacity.FERTILITY)) * Math.min(1, father.stats().capacity(Capacity.FERTILITY))
                * mother.stats().stat(Stat.CONCEPTION) * father.stats().stat(Stat.CONCEPTION);
        double mood = mother.mood() > 30 && father.mood() > 30 ? 1 : 0.5;
        double perNight = teen_pro_YT_CONCEPTION_PER_NIGHT * life().settings().birthRate() * fertility * mood * (romance.kind() == BondKind.SPOUSE ? 1.2 : 1);
        if (!LifeRules.roll(teen_pro_YT_random, LifeRules.chanceOver(perNight * 3, elapsed))) return false;
        Sex childSex = teen_pro_YT_random.nextBoolean() ? Sex.MALE : Sex.FEMALE;
        var genome = Genetics.inherit(content(), mother.genome(), father.genome(), childSex,
                Genetics.teen_pro_YT_MUTATION * life().settings().lethality().mutation(), teen_pro_YT_random);
        var pregnancy = new Pregnancy(father.citizenId(), UUID.randomUUID(), childSex, genome, life().settings().pregnancyTicks(), 0);
        try {
            life().conceive(mother, pregnancy);
        } catch (IllegalStateException | IllegalArgumentException full) {
            return false;
        }
        Thoughts.remember(content(), mother, LifeIds.teen_pro_YT_CONCEIVED, father.citizenId());
        Thoughts.remember(content(), father, LifeIds.teen_pro_YT_CONCEIVED, mother.citizenId());
        journal(mother, Journal.Category.FAMILY, "colonyloom.journal.expecting", father);
        return true;
    }
}
