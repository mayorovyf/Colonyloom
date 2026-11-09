package colonyloom.core.life.content;

import colonyloom.core.life.json.Obj;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;

public final class VarietyGenerator {
    public static final Set<String> teen_pro_YT_MUTATIONS = Set.of("virulent", "mild", "airborne", "latent", "resistant", "fast", "lingering",
            "neurotropic", "hemorrhagic", "seasonal", "zoonotic", "waterborne", "stealthy", "feverish", "drowsy", "itchy");
    public static final List<String> teen_pro_YT_STRAINS = List.of(
            "north_wind", "south_wind", "swamp_mist", "red_dawn", "black_widow", "white_frost", "river_fog", "desert_dust",
            "mountain_echo", "iron_lung", "golden_fever", "ashen_veil", "crimson_tide", "silent_step", "wanderer", "howling_wolf",
            "creeper_ash", "ender_shade", "nether_ember", "coral_bloom", "frost_bite", "ember_spark", "mire_rot", "thorn_crown",
            "emerald_glow", "lapis_tear", "redstone_pulse", "obsidian_heart", "copper_rust", "amethyst_hum", "moss_carpet", "pumpkin_eye",
            "witch_circle", "pillager_cough", "drowned_breath", "phantom_wing", "slime_trail", "spider_silk", "bee_sting", "mooshroom_spore",
            "village_bell", "old_mill", "lighthouse", "salt_marsh", "pine_needle", "birch_bark", "cherry_petal", "bamboo_shoot",
            "sunflower", "moonlight", "starfall", "thunderhead", "rain_dancer", "midnight_owl", "early_lark", "grey_heron",
            "blue_jay", "fox_den", "badger", "lynx", "raven", "bear_paw", "snow_hare", "sea_turtle",
            "axolotl", "glow_squid", "sniffer", "armadillo", "camel_hump", "goat_horn", "parrot", "panda",
            "old_sailor", "miller", "smith", "tanner", "cartographer", "fletcher", "librarian", "cleric",
            "shepherd", "fisherman", "mason", "weaver", "brewer", "baker", "herbalist", "hermit",
            "first_snow", "late_harvest", "spring_thaw", "summer_haze", "autumn_leaf", "winter_night", "new_moon", "full_moon");
    private static final char[] teen_pro_YT_CODE_LETTERS = "ABCDEFGHKLMNPRSTVX".toCharArray();

    public record Rules(int count, double[] contagion, double[] severity, double[] immunity, double[] incubation, List<String> mutations) {
        public static final Rules teen_pro_YT_NONE = new Rules(0, new double[]{1, 1}, new double[]{1, 1}, new double[]{1, 1}, new double[]{1, 1}, List.of());
        public Rules {
            if (count < 0 || count > 40) throw new IllegalArgumentException("Variety count must be 0..40");
            check(contagion, 0.1, 5); check(severity, 0.1, 3); check(immunity, 0.1, 3); check(incubation, 0.1, 5);
            mutations = List.copyOf(mutations);
            for (String mutation : mutations) if (!teen_pro_YT_MUTATIONS.contains(mutation)) throw new IllegalArgumentException("Unknown mutation " + mutation);
        }
        static Rules decode(Obj json) {
            json.allow("count", "contagiousness", "severity", "immunity", "incubation", "mutations");
            List<String> mutations = json.strings("mutations", 16);
            return new Rules(json.intValue("count"), range(json, "contagiousness", 0.6, 1.8), range(json, "severity", 0.8, 1.3),
                    range(json, "immunity", 0.8, 1.25), range(json, "incubation", 0.5, 2.0), mutations.isEmpty() ? List.copyOf(new TreeSet<>(teen_pro_YT_MUTATIONS)) : mutations);
        }
        private static double[] range(Obj json, String key, double low, double high) {
            if (!json.has(key)) return new double[]{low, high};
            var values = json.list(key);
            if (values.size() != 2 || !(values.get(0) instanceof Number a) || !(values.get(1) instanceof Number b)) throw json.fail(key + " must be [min,max]");
            return new double[]{a.doubleValue(), b.doubleValue()};
        }
        private static void check(double[] range, double min, double max) {
            if (range.length != 2 || range[0] > range[1] || range[0] < min || range[1] > max) throw new IllegalArgumentException("Invalid variety range");
        }
    }

    private VarietyGenerator() {}

    public static List<DiseaseDefinition> generate(DiseaseDefinition base, Rules rules, Set<String> usedStrains) {
        List<DiseaseDefinition> result = new ArrayList<>();
        Set<String> localStrains = new LinkedHashSet<>();
        for (int index = 1; index <= rules.count(); index++) {
            SplittableRandom random = new SplittableRandom(seed(base.id(), base.version(), index));
            String strain = null;
            for (int attempt = 0; attempt < 64 && strain == null; attempt++) {
                String candidate = teen_pro_YT_STRAINS.get(random.nextInt(teen_pro_YT_STRAINS.size()));
                if (!localStrains.contains(candidate)) strain = candidate;
            }
            if (strain == null) strain = teen_pro_YT_STRAINS.get(index % teen_pro_YT_STRAINS.size());
            localStrains.add(strain);
            usedStrains.add(strain);
            String code = teen_pro_YT_CODE_LETTERS[random.nextInt(teen_pro_YT_CODE_LETTERS.length)] + "-" + (1 + random.nextInt(99));
            int roll = random.nextInt(100);
            int mutationCount = roll < 5 ? 0 : roll < 75 ? 1 : 2;
            Set<String> mutations = new TreeSet<>();
            List<String> pool = new ArrayList<>(rules.mutations());
            pool.removeIf(m -> !applicable(base, m));
            for (int i = 0; i < mutationCount && !pool.isEmpty(); i++) {
                String mutation = pool.remove(random.nextInt(pool.size()));
                if (mutation.equals("virulent") && mutations.contains("mild") || mutation.equals("mild") && mutations.contains("virulent")) continue;
                mutations.add(mutation);
            }
            result.add(variety(base, index, strain, code, mutations, random, rules));
        }
        return result;
    }

    private static boolean applicable(DiseaseDefinition base, String mutation) {
        return switch (mutation) {
            case "hemorrhagic" -> base.severity().ordinal() >= Severity.SEVERE.ordinal();
            case "airborne" -> !base.routes().contains(Route.AIRBORNE);
            case "waterborne" -> !base.routes().contains(Route.WATER);
            case "zoonotic" -> !base.routes().contains(Route.ANIMAL);
            case "mild" -> base.severity() != Severity.TRIVIAL;
            default -> true;
        };
    }

    private static DiseaseDefinition variety(DiseaseDefinition base, int index, String strain, String code, Set<String> mutations,
            SplittableRandom random, Rules rules) {
        double contagion = between(random, rules.contagion()), severity = between(random, rules.severity());
        double immunity = between(random, rules.immunity()), incubation = between(random, rules.incubation());
        double stageScale = 1.0, tend = base.tendFactor() / (double) DiseaseDefinition.teen_pro_YT_UNIT;
        double tendInterval = 1.0;
        int radius = base.radius();
        boolean contagiousIncubation = base.contagiousIncubation(), lethal = base.lethal();
        Set<Route> routes = EnumSet.noneOf(Route.class);
        routes.addAll(base.routes());
        Set<EnvironmentFlag> environment = EnumSet.noneOf(EnvironmentFlag.class);
        environment.addAll(base.trigger().environment());
        int chancePerDay = base.trigger().chancePerDay();
        Modifiers extra = Modifiers.teen_pro_YT_EMPTY;
        EnumSet<Symptom> extraSymptoms = EnumSet.noneOf(Symptom.class);
        for (String mutation : mutations) {
            switch (mutation) {
                case "virulent" -> { severity *= 1.35; contagion *= 1.2; stageScale *= 1.25; }
                case "mild" -> { severity *= 0.65; contagion *= 0.85; stageScale *= 0.7; }
                case "airborne" -> { routes.add(Route.AIRBORNE); radius = Math.min(16, Math.max(radius, 2) + 2); }
                case "latent" -> { incubation *= 2.5; contagiousIncubation = true; }
                case "resistant" -> tend = 1 - (1 - tend) * 0.55;
                case "fast" -> { incubation *= 0.4; severity *= 1.2; immunity *= 1.25; }
                case "lingering" -> { immunity *= 0.6; severity *= 0.75; tendInterval *= 1.5; }
                case "neurotropic" -> { extra = extra.plus(modifier(Capacity.CONSCIOUSNESS, -0.08, Stat.BREAK_THRESHOLD, 3)); extraSymptoms.add(Symptom.CONFUSION); extraSymptoms.add(Symptom.HEADACHE); }
                case "hemorrhagic" -> { extra = extra.plus(modifier(Capacity.BLOOD_PUMPING, -0.12, Stat.PAIN, 0.05)); extraSymptoms.add(Symptom.BLEEDING); }
                case "seasonal" -> { environment.add(EnvironmentFlag.COLD); chancePerDay = Math.max(chancePerDay, 400); routes.add(Route.ENVIRONMENT); }
                case "zoonotic" -> { routes.add(Route.ANIMAL); chancePerDay = Math.max(chancePerDay, 300); }
                case "waterborne" -> { routes.add(Route.WATER); chancePerDay = Math.max(chancePerDay, 300); }
                case "stealthy" -> contagiousIncubation = true;
                case "feverish" -> { extra = extra.plus(modifier(null, 0, Stat.PAIN, 0.05)); immunity *= 1.1; extraSymptoms.add(Symptom.FEVER); extraSymptoms.add(Symptom.SWEAT); }
                case "drowsy" -> { extra = extra.plus(new Modifiers(Map.of(Capacity.CONSCIOUSNESS, -0.05), Map.of(), Map.of(Stat.REST_FALL, 1.3), Map.of(), Map.of(), Map.of())); extraSymptoms.add(Symptom.DROWSY); }
                case "itchy" -> { extra = extra.plus(modifier(null, 0, Stat.MOOD, -3)); extraSymptoms.add(Symptom.ITCH); extraSymptoms.add(Symptom.RASH); }
                default -> throw new IllegalStateException("Unhandled mutation " + mutation);
            }
        }
        if (mutations.contains("mild") && base.severity() != Severity.DEADLY) lethal = false;
        if (mutations.contains("hemorrhagic")) lethal = true;
        List<DiseaseDefinition.Stage> stages = new ArrayList<>();
        for (int i = 0; i < base.stages().size(); i++) {
            var stage = base.stages().get(i);
            double weight = i == 0 ? 0.4 : 1.0 + 0.5 * (i - 1);
            EnumSet<Symptom> symptoms = EnumSet.noneOf(Symptom.class);
            symptoms.addAll(stage.symptoms());
            if (i > 0 || extraSymptoms.size() < 2) symptoms.addAll(extraSymptoms);
            boolean visible = stage.visible() && !(i == 0 && mutations.contains("stealthy"));
            stages.add(new DiseaseDefinition.Stage(stage.from(), stage.label(), stage.contagion(),
                    scale(stage.effects(), stageScale).plus(extra.scaled(weight)), symptoms, visible, stage.bedRest()));
        }
        var trigger = new DiseaseDefinition.Trigger(environment, Math.min(DiseaseDefinition.teen_pro_YT_UNIT, chancePerDay), base.trigger().eventChance(),
                base.trigger().minAge(), base.trigger().maxAge(), base.trigger().sex(), base.trigger().injury(), base.trigger().moodBelow());
        long incubationMin = Math.min(2_400_000, Math.round(base.incubationMin() * incubation));
        long incubationMax = Math.min(2_400_000, Math.max(incubationMin, Math.round(base.incubationMax() * incubation)));
        var name = new DiseaseDefinition.Name(base.name().baseKey(), "colonyloom.strain." + strain, code, null);
        var unsealed = new DiseaseDefinition(base.id() + "/v" + (index < 10 ? "0" : "") + index, base.version(), base.family(), index, name,
                base.category(), base.pathogen(), base.severity(), base.systems(), routes,
                clampMicro(base.contagiousness() * contagion), radius, incubationMin, incubationMax, contagiousIncubation,
                base.startSeverity(), clampRate(base.severityPerDay() * severity), base.severityVariance(), clampRate(base.immunityPerDay() * immunity),
                base.remissionPerDay(), lethal, base.chronic(), base.selfLimiting(), base.tendable(),
                Math.min(2_400_000, Math.round(base.tendInterval() * tendInterval)), clampMicro(tend * DiseaseDefinition.teen_pro_YT_UNIT),
                base.immunityTicks(), base.crossImmunity(), trigger, stages, mutations, base.rarity(), "");
        return DiseaseCodec.seal(unsealed);
    }

    private static Modifiers modifier(Capacity capacity, double offset, Stat stat, double value) {
        return new Modifiers(capacity == null ? Map.of() : Map.of(capacity, offset), Map.of(), Map.of(stat, value), Map.of(), Map.of(), Map.of());
    }

    private static Modifiers scale(Modifiers base, double factor) {
        if (factor == 1.0) return base;
        EnumMap<Capacity, Double> offsets = new EnumMap<>(Capacity.class);
        base.capacityOffsets().forEach((k, v) -> offsets.put(k, Math.max(-2, Math.min(2, v * factor))));
        EnumMap<Stat, Double> stats = new EnumMap<>(Stat.class);
        base.stats().forEach((k, v) -> stats.put(k, k.additive() ? Math.max(-100, Math.min(100, v * factor)) : v));
        return new Modifiers(offsets, base.capacityFactors(), stats, base.susceptibility(), base.activityTags(), base.interactions());
    }

    private static double between(SplittableRandom random, double[] range) {
        return range[0] + (range[1] - range[0]) * random.nextDouble();
    }
    private static int clampMicro(double value) { return (int) Math.max(0, Math.min(DiseaseDefinition.teen_pro_YT_UNIT, Math.round(value))); }
    private static int clampRate(double value) { return (int) Math.max(0, Math.min(10L * DiseaseDefinition.teen_pro_YT_UNIT, Math.round(value))); }

    static long seed(String id, int version, int index) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((id + "#" + version + "#" + index).getBytes(StandardCharsets.UTF_8));
            long seed = 0;
            for (int i = 0; i < 8; i++) seed = (seed << 8) | (hash[i] & 0xFF);
            return seed;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
