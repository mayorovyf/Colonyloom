package colonyloom.gameplay.life;

import colonyloom.core.life.RichText;
import colonyloom.core.life.content.BodySystem;
import colonyloom.core.life.content.Capacity;
import colonyloom.core.life.content.DiseaseDefinition;
import colonyloom.core.life.content.Emote;
import colonyloom.core.life.content.EnvironmentFlag;
import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.LifeIds;
import colonyloom.core.life.content.Route;
import colonyloom.core.life.content.Severity;
import colonyloom.core.life.content.Skill;
import colonyloom.core.life.content.Stat;
import colonyloom.core.life.content.StatBlock;
import colonyloom.core.life.content.Symptom;
import colonyloom.core.life.state.Condition;
import colonyloom.core.life.state.Journal;
import colonyloom.core.life.state.LifeRegistry;
import colonyloom.core.life.state.LifeSettings;
import colonyloom.core.life.state.LifeState;
import colonyloom.core.life.state.Records.Immunity;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

public final class HealthEngine {
    static final double teen_pro_YT_CONTACT = 0.12;
    static final double teen_pro_YT_AIRBORNE_PER_MINUTE = 0.03;
    static final double teen_pro_YT_STRAIN_JUMP = 0.005;
    static final double teen_pro_YT_OUTBREAKS_PER_RESIDENT_DAY = 0.015;
    static final double teen_pro_YT_SYMPTOM_LINES_PER_DAY = 10;
    static final int teen_pro_YT_TRIGGER_SAMPLES = 2;

    private final LifeRegistry teen_pro_YT_life;
    private final LifePort teen_pro_YT_port;
    private final RandomGenerator teen_pro_YT_random;

    public HealthEngine(LifeRegistry life, LifePort port, RandomGenerator random) {
        this.teen_pro_YT_life = Objects.requireNonNull(life); this.teen_pro_YT_port = Objects.requireNonNull(port); this.teen_pro_YT_random = Objects.requireNonNull(random);
    }

    private LifeRegistry life() { return teen_pro_YT_life; }
    private LifeContent content() { return life().content(); }
    private LifeSettings settings() { return life().settings(); }
    private double contagionSetting() { return settings().contagion() * settings().lethality().contagion(); }

    public String update(Resident resident, long elapsed, boolean bedRest) {
        if (elapsed <= 0 || !resident.alive()) return null;
        LifeState state = resident.life();
        StatBlock stats = state.stats();
        LifeSettings settings = settings();
        double days = LifeRules.days(elapsed);
        String death = null;
        for (Condition condition : List.copyOf(state.conditions())) {
            boolean wasIncubating = condition.incubating();
            int oldStage = condition.stageIndex();
            condition.advance(elapsed);
            if (condition.incubating()) continue;
            DiseaseDefinition disease = condition.disease();
            double tend = condition.tended() ? LifeRules.fraction(condition.tendQuality()) : 0;
            double rate = disease.severityPerDay() * LifeRules.fraction(condition.variance()) * settings.lethality().severity() * stats.stat(Stat.SEVERITY_GAIN);
            rate *= 1 - (1 - LifeRules.fraction(disease.tendFactor())) * tend;
            if (bedRest) rate *= 0.85;
            double delta;
            if (disease.chronic()) delta = tend > 0 ? -disease.remissionPerDay() * tend : rate;
            else if (disease.selfLimiting() && (condition.peaked() || condition.immunity() >= LifeRules.teen_pro_YT_UNIT / 2)) {
                condition.peak();
                delta = -Math.max(disease.severityPerDay() * 0.5, disease.remissionPerDay());
            } else delta = rate;
            boolean kills = disease.lethal() && settings.lethality().kills();
            long cap = kills ? LifeRules.teen_pro_YT_UNIT : LifeRules.teen_pro_YT_UNIT * 99L / 100;
            condition.severity((int) Math.max(0, Math.min(cap, condition.severity() + Math.round(delta * days))));
            if (!disease.chronic()) {
                double gain = disease.immunityPerDay() * settings.lethality().immunity() * stats.stat(Stat.IMMUNITY_GAIN) * immunityFactor(resident, bedRest);
                condition.immunity(condition.immunity() + (int) Math.round(gain * days));
            }
            if (!disease.chronic() && (condition.immunity() >= LifeRules.teen_pro_YT_UNIT || condition.peaked() && condition.severity() == 0)) {
                recover(resident, condition);
                continue;
            }
            if (kills && condition.severity() >= LifeRules.teen_pro_YT_UNIT && death == null) death = disease.name().spec();
            if (!condition.announced() && condition.visible()) announce(resident, condition);
            if (wasIncubating || condition.stageIndex() != oldStage) state.invalidateStats();
        }
        if (death == null && settings.lethality().kills()) {
            Capacity failed = stats.vitalFailure();
            if (failed != null) death = RichText.of("colonyloom.death.vital", RichText.key("colonyloom.capacity." + failed.id()));
        }
        symptoms(resident, elapsed);
        return death;
    }

    double immunityFactor(Resident resident, boolean bedRest) {
        double factor = 1.0;
        int food = resident.body().food();
        factor *= food >= 10 ? 1.0 : food >= 6 ? 0.92 : food > 0 ? 0.8 : 0.6;
        if (bedRest) factor *= 1.25;
        if (resident.life().mood() < 20) factor *= 0.9;
        if (resident.life().rest() < 200_000) factor *= 0.9;
        return factor;
    }

    private void recover(Resident resident, Condition condition) {
        LifeState state = resident.life();
        DiseaseDefinition disease = condition.disease();
        life().cure(state, condition);
        if (disease.immunityTicks() != 0) state.addImmunity(new Immunity(disease.id(), disease.family(), disease.crossImmunity(),
                disease.immunityTicks() < 0 ? Immunity.teen_pro_YT_PERMANENT : disease.immunityTicks()));
        if (!condition.announced()) return;
        Thoughts.remember(content(), state, LifeIds.teen_pro_YT_RECOVERED, null);
        teen_pro_YT_port.speak(state.citizenId(), Thoughts.line("recovered", 3, teen_pro_YT_random));
        teen_pro_YT_port.emote(state.citizenId(), Emote.HAPPY);
        if (journaled(disease)) life().record(state.colonyId(), teen_pro_YT_port.gameTime(), Journal.Category.HEALTH,
                RichText.of("colonyloom.journal.recovered", RichText.literal(state.fullName()), RichText.nested(disease.name().spec())), state.citizenId());
    }

    private void announce(Resident resident, Condition condition) {
        LifeState state = resident.life();
        condition.announce();
        Thoughts.remember(content(), state, LifeIds.teen_pro_YT_GOT_SICK, null);
        teen_pro_YT_port.speak(state.citizenId(), Thoughts.line("sick", 4, teen_pro_YT_random));
        teen_pro_YT_port.emote(state.citizenId(), Emote.SICK);
        if (journaled(condition.disease())) life().record(state.colonyId(), teen_pro_YT_port.gameTime(), Journal.Category.HEALTH,
                RichText.of("colonyloom.journal.sick", RichText.literal(state.fullName()), RichText.nested(condition.disease().name().spec())), state.citizenId());
    }

    static boolean journaled(DiseaseDefinition disease) {
        return disease.severity().ordinal() >= Severity.MODERATE.ordinal() || disease.infectious() && disease.severity() != Severity.TRIVIAL;
    }

    private void symptoms(Resident resident, long elapsed) {
        if (!settings().speech() || !LifeRules.roll(teen_pro_YT_random, LifeRules.chanceOver(teen_pro_YT_SYMPTOM_LINES_PER_DAY, elapsed))) return;
        for (Condition condition : resident.life().conditions()) {
            if (!condition.visible() || condition.stage().symptoms().isEmpty()) continue;
            List<Symptom> symptoms = new ArrayList<>(condition.stage().symptoms());
            Symptom symptom = symptoms.get(teen_pro_YT_random.nextInt(symptoms.size()));
            teen_pro_YT_port.speak(resident.life().citizenId(), Thoughts.line("symptom." + symptom.id(), 3, teen_pro_YT_random));
            teen_pro_YT_port.emote(resident.life().citizenId(), switch (symptom) {
                case SNEEZE -> Emote.SNEEZE;
                case COUGH, WHEEZE, BREATHLESS -> Emote.SMOKE;
                case VOMIT, NAUSEA, PALE -> Emote.SICK;
                case SADNESS, ANXIETY -> Emote.SAD;
                case DROWSY -> Emote.SLEEP;
                case FEVER, SWEAT, CHILLS, BLEEDING -> Emote.SPLASH;
                default -> Emote.NONE;
            });
            return;
        }
    }

    public double susceptibility(LifeState state, DiseaseDefinition disease) {
        StatBlock stats = state.stats();
        double factor = state.protection(disease) * stats.stat(Stat.SUSCEPTIBILITY);
        if (!disease.systems().isEmpty()) {
            double systems = 0;
            for (BodySystem system : disease.systems()) systems += stats.susceptibility(system);
            factor *= systems / disease.systems().size();
        }
        factor *= Genetics.resistance(content(), state, disease.family());
        if (state.hygiene() < 300_000) factor *= 1.25;
        if (state.conditions().size() >= 2) factor *= 1.15;
        return Math.max(0, factor);
    }

    public Condition expose(Resident target, DiseaseDefinition disease, double chance, UUID source) {
        if (!target.alive() || target.life().hasFamily(disease.family())) return null;
        if (!LifeRules.roll(teen_pro_YT_random, chance * susceptibility(target.life(), disease))) return null;
        return infect(target, strainJump(disease), source);
    }

    public Condition infect(Resident target, DiseaseDefinition disease, UUID source) {
        if (!target.alive()) return null;
        long incubation = LifeRules.between(teen_pro_YT_random, disease.incubationMin(), disease.incubationMax());
        double spread = LifeRules.fraction(disease.severityVariance());
        int variance = LifeRules.micro(1 + (teen_pro_YT_random.nextDouble() * 2 - 1) * spread);
        Condition condition = life().infect(target.life(), disease, incubation, variance, source);
        if (condition != null) target.life().invalidateStats();
        return condition;
    }

    private DiseaseDefinition strainJump(DiseaseDefinition disease) {
        if (!disease.infectious() || !LifeRules.roll(teen_pro_YT_random, teen_pro_YT_STRAIN_JUMP * settings().lethality().mutation())) return disease;
        List<DiseaseDefinition> family = content().family(disease.family());
        if (family.size() < 2) return disease;
        DiseaseDefinition other = family.get(teen_pro_YT_random.nextInt(family.size()));
        return other.infectious() ? other : disease;
    }

    public void contact(Resident a, Resident b, double intimacy) {
        spread(a, b, intimacy);
        spread(b, a, intimacy);
    }

    private void spread(Resident carrier, Resident target, double intimacy) {
        if (!carrier.alive() || !target.alive()) return;
        for (Condition condition : List.copyOf(carrier.life().conditions())) {
            DiseaseDefinition disease = condition.disease();
            boolean route = disease.transmits(Route.CONTACT) || disease.transmits(Route.AIRBORNE) || intimacy >= 2 && disease.transmits(Route.BLOOD);
            if (!route || !condition.contagiousNow()) continue;
            double chance = LifeRules.fraction(disease.contagiousness()) * LifeRules.fraction(condition.stage().contagion()) * intimacy * teen_pro_YT_CONTACT
                    * contagionSetting() * carrier.life().stats().stat(Stat.CONTAGION);
            expose(target, disease, chance, carrier.life().citizenId());
        }
    }

    public void airborne(Resident carrier, List<Resident> neighbours, long elapsed) {
        if (!carrier.alive() || elapsed <= 0) return;
        for (Condition condition : List.copyOf(carrier.life().conditions())) {
            DiseaseDefinition disease = condition.disease();
            if (!disease.transmits(Route.AIRBORNE) || !condition.contagiousNow() || disease.radius() <= 0) continue;
            double perMinute = Math.min(1, LifeRules.fraction(disease.contagiousness()) * LifeRules.fraction(condition.stage().contagion())
                    * teen_pro_YT_AIRBORNE_PER_MINUTE * contagionSetting() * carrier.life().stats().stat(Stat.CONTAGION));
            double chance = 1 - Math.pow(1 - perMinute, elapsed / 1200.0);
            for (Resident neighbour : neighbours) {
                if (neighbour.life() == carrier.life()) continue;
                if (LifeRules.distance(carrier.body().position(), neighbour.body().position()) > disease.radius()) continue;
                expose(neighbour, disease, chance, carrier.life().citizenId());
            }
        }
    }

    public void ate(Resident resident) {
        Thoughts.remember(content(), resident.life(), LifeIds.teen_pro_YT_ATE_MEAL, null);
        rollEvent(resident, content().byRoute(Route.FOOD), null);
    }

    public void injured(Resident resident, String kind) {
        rollEvent(resident, content().byRoute(Route.INJURY), kind);
    }

    private void rollEvent(Resident resident, List<DiseaseDefinition> candidates, String injury) {
        if (!resident.alive()) return;
        int years = resident.life().years(settings().ticksPerYear());
        LinkedHashMap<String, List<DiseaseDefinition>> families = new LinkedHashMap<>();
        for (DiseaseDefinition disease : candidates) families.computeIfAbsent(disease.family(), ignored -> new ArrayList<>()).add(disease);
        for (List<DiseaseDefinition> family : families.values()) {
            DiseaseDefinition base = family.getFirst();
            for (DiseaseDefinition member : family) if (member.id().equals(member.family())) base = member;
            var trigger = base.trigger();
            if (trigger.eventChance() <= 0 || !trigger.ageAllows(years) || trigger.sex() != null && trigger.sex() != resident.life().sex()) continue;
            if (injury != null && (trigger.injury() == null || !trigger.injury().equals("any") && !trigger.injury().equals(injury))) continue;
            DiseaseDefinition chosen = weighted(family);
            expose(resident, chosen, LifeRules.fraction(trigger.eventChance()), null);
        }
    }

    public void triggers(Resident resident, Set<EnvironmentFlag> environment, long elapsed) {
        List<LifeContent.Spontaneous> groups = content().spontaneous();
        if (groups.isEmpty() || elapsed <= 0 || !resident.alive()) return;
        LifeState state = resident.life();
        int years = state.years(settings().ticksPerYear());
        double scale = groups.size() / (double) teen_pro_YT_TRIGGER_SAMPLES;
        for (int i = 0; i < Math.min(teen_pro_YT_TRIGGER_SAMPLES, groups.size()); i++) {
            LifeContent.Spontaneous group = groups.get(teen_pro_YT_random.nextInt(groups.size()));
            var trigger = group.trigger();
            if (!trigger.ageAllows(years) || trigger.sex() != null && trigger.sex() != state.sex()) continue;
            if (trigger.moodBelow() >= 0 && state.mood() >= trigger.moodBelow()) continue;
            DiseaseDefinition disease = weighted(group.members());
            if (!environmentAllows(disease, environment)) continue;
            double perDay = LifeRules.fraction(trigger.chancePerDay()) * scale;
            if (disease.infectious()) perDay *= contagionSetting();
            expose(resident, disease, LifeRules.chanceOver(perDay, elapsed), null);
        }
    }

    static boolean environmentAllows(DiseaseDefinition disease, Set<EnvironmentFlag> flags) {
        if (!flags.containsAll(disease.trigger().environment())) return false;
        boolean restricted = false, allowed = false;
        for (Route route : disease.routes()) {
            Set<EnvironmentFlag> needed = switch (route) {
                case WATER -> EnumSet.of(EnvironmentFlag.NEAR_WATER, EnvironmentFlag.IN_WATER, EnvironmentFlag.RIVER, EnvironmentFlag.SWAMP);
                case ANIMAL -> EnumSet.of(EnvironmentFlag.NEAR_LIVESTOCK, EnvironmentFlag.NEAR_POULTRY, EnvironmentFlag.NEAR_WILD_ANIMALS);
                case VECTOR -> EnumSet.of(EnvironmentFlag.JUNGLE, EnvironmentFlag.SWAMP);
                default -> null;
            };
            if (needed == null) continue;
            restricted = true;
            for (EnvironmentFlag flag : needed) if (flags.contains(flag)) { allowed = true; break; }
        }
        return !restricted || allowed;
    }

    public Condition outbreak(List<Resident> residents, long elapsed) {
        if (residents.isEmpty() || !LifeRules.roll(teen_pro_YT_random, LifeRules.chanceOver(teen_pro_YT_OUTBREAKS_PER_RESIDENT_DAY * residents.size() * contagionSetting(), elapsed))) return null;
        Resident patient = residents.get(teen_pro_YT_random.nextInt(residents.size()));
        int years = patient.life().years(settings().ticksPerYear());
        List<DiseaseDefinition> bases = new ArrayList<>();
        for (List<DiseaseDefinition> family : content().families().values()) {
            DiseaseDefinition base = family.getFirst();
            for (DiseaseDefinition member : family) if (member.id().equals(member.family())) base = member;
            var trigger = base.trigger();
            if (!base.infectious() || base.rarity() <= 0 || !trigger.environment().isEmpty() || trigger.injury() != null || trigger.eventChance() > 0) continue;
            if (!trigger.ageAllows(years) || trigger.sex() != null && trigger.sex() != patient.life().sex()) continue;
            bases.add(base);
        }
        if (bases.isEmpty()) return null;
        List<DiseaseDefinition> strains = new ArrayList<>();
        for (DiseaseDefinition member : content().family(weighted(bases).family())) if (member.infectious() && member.rarity() > 0) strains.add(member);
        return strains.isEmpty() ? null : infect(patient, weighted(strains), null);
    }

    private DiseaseDefinition weighted(List<DiseaseDefinition> candidates) {
        long total = 0;
        for (DiseaseDefinition disease : candidates) total += Math.max(1, disease.rarity());
        long pick = (long) Math.floor(teen_pro_YT_random.nextDouble() * total);
        for (DiseaseDefinition disease : candidates) {
            pick -= Math.max(1, disease.rarity());
            if (pick < 0) return disease;
        }
        return candidates.getLast();
    }

    public void heredity(Resident resident) {
        LifeState state = resident.life();
        for (String id : Genetics.dueConditions(content(), state, state.years(settings().ticksPerYear()))) {
            DiseaseDefinition disease = content().disease(id);
            if (disease == null || state.hasFamily(disease.family()) || state.protection(disease) == 0) continue;
            Condition condition = life().infect(state, disease, 0, LifeRules.teen_pro_YT_UNIT, null);
            if (condition != null) state.invalidateStats();
        }
    }

    public static boolean needsCare(LifeState state) {
        for (Condition condition : state.conditions()) if (condition.needsTending() && condition.visible()) return true;
        return false;
    }

    public double tendQuality(LifeState doctor) {
        if (doctor == null) return 0.25;
        StatBlock stats = doctor.stats();
        double quality = (0.35 + 0.03 * doctor.level(Skill.MEDICINE)) * stats.stat(Stat.TEND_QUALITY)
                * Math.min(1, stats.capacity(Capacity.MANIPULATION)) * (0.5 + 0.5 * Math.min(1, stats.capacity(Capacity.SIGHT)));
        quality += (teen_pro_YT_random.nextDouble() - 0.5) * 0.2;
        return Math.max(0.05, Math.min(1, quality));
    }

    public int tend(Resident patient, LifeState doctor) {
        double quality = tendQuality(doctor);
        int tended = 0;
        for (Condition condition : patient.life().conditions()) {
            if (!condition.needsTending()) continue;
            condition.tend(LifeRules.micro(quality), Math.max(1, condition.disease().tendInterval()));
            tended++;
        }
        if (tended == 0) return 0;
        Thoughts.remember(content(), patient.life(), LifeIds.teen_pro_YT_WAS_TENDED, doctor == null ? null : doctor.citizenId());
        if (doctor != null) {
            doctor.xp(Skill.MEDICINE, doctor.xp(Skill.MEDICINE) + (int) Math.round(40 * tended * doctor.stats().stat(Stat.LEARNING)));
            teen_pro_YT_port.lookAt(doctor.citizenId(), patient.life().citizenId());
            teen_pro_YT_port.speak(doctor.citizenId(), Thoughts.line("tend", 4, teen_pro_YT_random, patient.life().givenName()));
        }
        teen_pro_YT_port.emote(patient.life().citizenId(), Emote.SPARKLE);
        return tended;
    }

    public int cure(Resident resident, String diseaseId) {
        int cured = 0;
        for (Condition condition : List.copyOf(resident.life().conditions())) {
            if (diseaseId != null && !condition.disease().id().equals(diseaseId) && !condition.disease().family().equals(diseaseId)) continue;
            DiseaseDefinition disease = condition.disease();
            life().cure(resident.life(), condition);
            if (disease.immunityTicks() != 0) resident.life().addImmunity(new Immunity(disease.id(), disease.family(), disease.crossImmunity(),
                    disease.immunityTicks() < 0 ? Immunity.teen_pro_YT_PERMANENT : disease.immunityTicks()));
            cured++;
        }
        if (cured > 0) resident.life().invalidateStats();
        return cured;
    }

    public static Map<String, Integer> severities(LifeState state) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (Condition condition : state.conditions()) result.put(condition.disease().id(), condition.severity());
        return result;
    }
}
