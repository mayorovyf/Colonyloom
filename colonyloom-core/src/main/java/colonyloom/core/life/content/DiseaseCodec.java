package colonyloom.core.life.content;

import colonyloom.core.life.json.Json;
import colonyloom.core.life.json.Obj;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DiseaseCodec {
    public static final int teen_pro_YT_SCHEMA = 1;
    private static final int teen_pro_YT_U = DiseaseDefinition.teen_pro_YT_UNIT;
    private DiseaseCodec() {}

    public record Base(DiseaseDefinition definition, VarietyGenerator.Rules varieties, List<DiseaseDefinition> subtypes) {
        public Base { varieties = varieties == null ? VarietyGenerator.Rules.teen_pro_YT_NONE : varieties; subtypes = List.copyOf(subtypes); }
    }

    private static final String[] teen_pro_YT_SUBTYPE_FIELDS = {"id", "pathogen", "severity", "systems", "routes", "contagiousness", "radius",
            "incubationDays", "contagiousIncubation", "startSeverity", "severityPerDay", "severityVariance", "immunityPerDay",
            "remissionPerDay", "lethal", "chronic", "selfLimiting", "tendable", "tendIntervalDays", "tendFactor", "immunityDays",
            "crossImmunity", "trigger", "stages", "symptoms", "effects", "rarity"};

    public static Base decodeBase(String id, Obj json) {
        json.allow("schemaVersion", "version", "category", "pathogen", "severity", "systems", "routes", "contagiousness", "radius",
                "incubationDays", "contagiousIncubation", "startSeverity", "severityPerDay", "severityVariance", "immunityPerDay",
                "remissionPerDay", "lethal", "chronic", "selfLimiting", "tendable", "tendIntervalDays", "tendFactor", "immunityDays",
                "crossImmunity", "trigger", "stages", "symptoms", "effects", "rarity", "varieties", "subtypes", "name");
        if (json.intValue("schemaVersion") != teen_pro_YT_SCHEMA) throw json.fail("unsupported schemaVersion");
        int version = json.intValue("version", 1);
        if (version < 1) throw json.fail("version must be positive");
        var category = DiseaseCategory.parse(json.string("category"), json.context());
        String path = id.substring(id.indexOf(':') + 1);
        String nameKey = json.string("name", "colonyloom.disease." + path.replace('/', '.'));
        var definition = decodeFields(id, id, 0, version, new DiseaseDefinition.Name(nameKey, null, null, null), category, json);
        VarietyGenerator.Rules rules = json.has("varieties") ? VarietyGenerator.Rules.decode(json.obj("varieties")) : VarietyGenerator.Rules.teen_pro_YT_NONE;
        if (rules.count() > 0 && !definition.infectious()) throw json.fail("varieties require a contagious infectious disease");
        List<DiseaseDefinition> subtypes = new ArrayList<>();
        int index = 0;
        for (Obj subtype : json.objects("subtypes", 32)) {
            index++;
            subtype.require("id").allow(teen_pro_YT_SUBTYPE_FIELDS);
            String subtypeId = subtype.string("id");
            if (!subtypeId.matches("[a-z0-9_]{1,32}")) throw subtype.fail("invalid subtype id");
            LinkedHashMap<String, Object> merged = new LinkedHashMap<>(json.raw());
            merged.remove("subtypes"); merged.remove("varieties"); merged.remove("name");
            for (var entry : subtype.raw().entrySet()) if (!entry.getKey().equals("id")) merged.put(entry.getKey(), entry.getValue());
            Obj effective = new Obj(merged, subtype.context());
            String subId = id + "/" + subtypeId;
            var name = new DiseaseDefinition.Name(nameKey, null, null, nameKey + "." + subtypeId);
            subtypes.add(decodeFields(subId, id, index, version, name, category, effective));
        }
        return new Base(definition, rules, subtypes);
    }

    private static DiseaseDefinition decodeFields(String id, String family, int variety, int version, DiseaseDefinition.Name name,
            DiseaseCategory category, Obj json) {
        var pathogen = json.has("pathogen") ? Pathogen.parse(json.string("pathogen"), json.context())
                : category == DiseaseCategory.INFECTIOUS ? Pathogen.VIRUS : Pathogen.NONE;
        if (category == DiseaseCategory.INFECTIOUS && pathogen == Pathogen.NONE) throw json.fail("infectious disease needs a pathogen");
        var severity = json.has("severity") ? Severity.parse(json.string("severity"), json.context()) : Severity.MILD;
        List<BodySystem> systems = new ArrayList<>();
        for (String system : json.strings("systems", 6)) systems.add(BodySystem.parse(system, json.context()));
        if (systems.isEmpty()) systems.add(defaultSystem(category));
        Set<Route> routes = EnumSet.noneOf(Route.class);
        for (String route : json.strings("routes", 11)) routes.add(Route.parse(route, json.context()));
        if (routes.isEmpty()) routes.addAll(defaultRoutes(category, pathogen));
        boolean contagious = routes.contains(Route.CONTACT) || routes.contains(Route.AIRBORNE) || routes.contains(Route.BLOOD);
        int contagiousness = fraction(json, "contagiousness", contagious ? defaultContagion(pathogen) : 0);
        int radius = json.intValue("radius", routes.contains(Route.AIRBORNE) ? 3 : 0);
        long incubationMin = 0, incubationMax = 0;
        if (json.has("incubationDays")) {
            var range = json.list("incubationDays");
            if (range.size() != 2 || !(range.get(0) instanceof Number a) || !(range.get(1) instanceof Number b)) throw json.fail("incubationDays must be [min,max]");
            incubationMin = days(a.doubleValue()); incubationMax = days(b.doubleValue());
        } else if (category == DiseaseCategory.INFECTIOUS) {
            double[] range = switch (pathogen) {
                case VIRUS -> new double[]{0.15, 0.5};
                case BACTERIUM -> new double[]{0.2, 0.7};
                case FUNGUS -> new double[]{0.5, 1.5};
                case PARASITE -> new double[]{0.5, 2.0};
                case PRION -> new double[]{5.0, 20.0};
                default -> new double[]{0, 0};
            };
            incubationMin = days(range[0]); incubationMax = days(range[1]);
        }
        boolean chronic = json.bool("chronic", Set.of(DiseaseCategory.CHRONIC, DiseaseCategory.GENETIC, DiseaseCategory.AGE,
                DiseaseCategory.CANCER, DiseaseCategory.REPRODUCTIVE).contains(category));
        boolean selfLimiting = json.bool("selfLimiting", !chronic && (Set.of(DiseaseCategory.ENVIRONMENTAL, DiseaseCategory.INJURY,
                DiseaseCategory.ALLERGY, DiseaseCategory.NUTRITIONAL, DiseaseCategory.MENTAL).contains(category) || severity == Severity.TRIVIAL));
        boolean lethal = json.bool("lethal", severity == Severity.DEADLY
                || severity == Severity.SEVERE && (category == DiseaseCategory.INFECTIOUS || category == DiseaseCategory.CANCER));
        int severityPerDay = rate(json, "severityPerDay", defaultSeverityRate(severity, chronic));
        int immunityPerDay = rate(json, "immunityPerDay", chronic ? 0 : defaultImmunityRate(severity, category));
        int remission = rate(json, "remissionPerDay", chronic ? severityPerDay / 3 : 0);
        int variance = fraction(json, "severityVariance", 250_000);
        int start = fraction(json, "startSeverity", category == DiseaseCategory.INFECTIOUS ? 30_000 : 100_000);
        boolean tendable = json.bool("tendable", true);
        long tendInterval = json.has("tendIntervalDays") ? days(json.decimal("tendIntervalDays")) : chronic ? 48_000 : 12_000;
        int tendFactor = fraction(json, "tendFactor", 450_000);
        long immunityTicks;
        if (json.has("immunityDays")) {
            double value = json.decimal("immunityDays");
            immunityTicks = value < 0 ? DiseaseDefinition.teen_pro_YT_PERMANENT : days(value);
        } else immunityTicks = category != DiseaseCategory.INFECTIOUS ? 0
                : pathogen == Pathogen.VIRUS ? (severity == Severity.TRIVIAL ? 48_000 : 144_000) : 72_000;
        int cross = fraction(json, "crossImmunity", 500_000);
        var trigger = json.has("trigger") ? trigger(json.obj("trigger"), category) : defaultTrigger(category);
        List<Symptom> symptoms = new ArrayList<>();
        for (String symptom : json.strings("symptoms", 8)) symptoms.add(Symptom.parse(symptom, json.context()));
        Modifiers extra = json.has("effects") ? Modifiers.decode(json.obj("effects")) : Modifiers.teen_pro_YT_EMPTY;
        List<DiseaseDefinition.Stage> stages = json.has("stages") ? explicitStages(json, extra)
                : generatedStages(severity, systems, symptoms, extra, lethal, Set.of());
        int rarity = fraction1000(json, "rarity", teen_pro_YT_U);
        var unsealed = new DiseaseDefinition(id, version, family, variety, name, category, pathogen, severity, systems, routes,
                contagiousness, radius, incubationMin, incubationMax, json.bool("contagiousIncubation", contagious && category == DiseaseCategory.INFECTIOUS),
                start, severityPerDay, variance, immunityPerDay, remission, lethal, chronic, selfLimiting,
                tendable, tendInterval, tendFactor, immunityTicks, cross, trigger, stages, Set.of(), rarity, "");
        return seal(unsealed);
    }

    private static List<DiseaseDefinition.Stage> explicitStages(Obj json, Modifiers extra) {
        List<DiseaseDefinition.Stage> stages = new ArrayList<>();
        for (Obj stage : json.objects("stages", DiseaseDefinition.teen_pro_YT_MAX_STAGES)) {
            stage.allow("from", "label", "contagion", "effects", "symptoms", "visible", "bedRest");
            Set<Symptom> symptoms = EnumSet.noneOf(Symptom.class);
            for (String symptom : stage.strings("symptoms", 8)) symptoms.add(Symptom.parse(symptom, stage.context()));
            Modifiers effects = stage.has("effects") ? Modifiers.decode(stage.obj("effects")) : Modifiers.teen_pro_YT_EMPTY;
            stages.add(new DiseaseDefinition.Stage(fraction(stage, "from", 0), stage.string("label", "minor"),
                    fraction5(stage, "contagion", teen_pro_YT_U), effects.plus(extra), symptoms, stage.bool("visible", true), stage.bool("bedRest", false)));
        }
        return stages;
    }

    static List<DiseaseDefinition.Stage> generatedStages(Severity severity, List<BodySystem> systems, List<Symptom> symptoms,
            Modifiers extra, boolean lethal, Set<String> mutations) {
        double magnitude = switch (severity) { case TRIVIAL -> 0.05; case MILD -> 0.10; case MODERATE -> 0.18; case SEVERE -> 0.30; case DEADLY -> 0.42; };
        String[] labels; int[] from; double[] scale; int[] contagion;
        switch (severity) {
            case TRIVIAL, MILD -> { labels = new String[]{"minor", "major"}; from = new int[]{0, 500_000}; scale = new double[]{0.35, 1.0}; contagion = new int[]{600_000, teen_pro_YT_U}; }
            case MODERATE -> { labels = new String[]{"minor", "major", "extreme"}; from = new int[]{0, 350_000, 700_000}; scale = new double[]{0.35, 1.0, 1.7}; contagion = new int[]{600_000, teen_pro_YT_U, 1_200_000}; }
            default -> { labels = new String[]{"minor", "major", "extreme", "critical"}; from = new int[]{0, 300_000, 600_000, 850_000}; scale = new double[]{0.35, 1.0, 1.7, 2.3}; contagion = new int[]{600_000, teen_pro_YT_U, 1_200_000, teen_pro_YT_U}; }
        }
        List<DiseaseDefinition.Stage> stages = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            double s = magnitude * scale[i];
            EnumMap<Capacity, Double> offsets = new EnumMap<>(Capacity.class);
            EnumMap<Stat, Double> stats = new EnumMap<>(Stat.class);
            double pain = 0.03 + s * 0.45, mood = -(2 + s * 18);
            for (BodySystem system : systems) {
                for (var weight : capacityWeights(system).entrySet()) {
                    double floor = weight.getKey().vital() && !lethal ? -0.75 : -0.9;
                    offsets.merge(weight.getKey(), Math.max(floor, -s * weight.getValue()), (a, b) -> Math.max(floor, a + b));
                }
                switch (system) {
                    case SKIN, MUSCULOSKELETAL -> pain += s * 0.15;
                    case MENTAL -> { pain -= s * 0.3; mood -= s * 20; stats.merge(Stat.BREAK_THRESHOLD, 4 * scale[i], Double::sum); }
                    case IMMUNE -> stats.merge(Stat.IMMUNITY_GAIN, Math.max(0.3, 1 - 0.45 * s), (a, b) -> a * b);
                    case NERVOUS -> stats.merge(Stat.BREAK_THRESHOLD, 2 * scale[i], Double::sum);
                    case RESPIRATORY, CARDIOVASCULAR, BLOOD -> stats.merge(Stat.REST_FALL, 1 + 0.5 * s, (a, b) -> a * b);
                    default -> {}
                }
            }
            stats.merge(Stat.PAIN, Math.max(0, pain), Double::sum);
            stats.merge(Stat.MOOD, (double) Math.round(mood), Double::sum);
            stats.merge(Stat.WORK_SPEED, Math.max(0.3, 1 - s * 0.8), (a, b) -> a * b);
            Set<Symptom> stageSymptoms = EnumSet.noneOf(Symptom.class);
            int count = i == 0 ? (symptoms.size() + 1) / 2 : symptoms.size();
            for (int j = 0; j < count; j++) stageSymptoms.add(symptoms.get(j));
            boolean visible = !(i == 0 && mutations.contains("stealthy"));
            boolean bedRest = scale[i] >= 1.0 && severity.ordinal() >= Severity.MODERATE.ordinal() || scale[i] >= 2.0;
            Modifiers generated = new Modifiers(offsets, Map.of(), stats, Map.of(), Map.of(), Map.of());
            stages.add(new DiseaseDefinition.Stage(from[i], labels[i], contagion[i], generated.plus(extra.scaled(scale[i])), stageSymptoms, visible, bedRest));
        }
        return stages;
    }

    static Map<Capacity, Double> capacityWeights(BodySystem system) {
        return switch (system) {
            case RESPIRATORY -> Map.of(Capacity.BREATHING, 1.0, Capacity.TALKING, 0.3);
            case CARDIOVASCULAR -> Map.of(Capacity.BLOOD_PUMPING, 1.0, Capacity.MOVING, 0.3);
            case DIGESTIVE -> Map.of(Capacity.EATING, 1.0, Capacity.METABOLISM, 0.5);
            case NERVOUS -> Map.of(Capacity.CONSCIOUSNESS, 0.8, Capacity.MANIPULATION, 0.3);
            case MUSCULOSKELETAL -> Map.of(Capacity.MOVING, 1.0, Capacity.MANIPULATION, 0.6);
            case SENSORY -> Map.of(Capacity.SIGHT, 0.8, Capacity.HEARING, 0.8);
            case RENAL -> Map.of(Capacity.BLOOD_FILTRATION, 1.0);
            case REPRODUCTIVE -> Map.of(Capacity.FERTILITY, 1.5);
            case ENDOCRINE -> Map.of(Capacity.METABOLISM, 1.0, Capacity.CONSCIOUSNESS, 0.2);
            case MENTAL -> Map.of(Capacity.CONSCIOUSNESS, 0.3);
            case BLOOD -> Map.of(Capacity.BLOOD_PUMPING, 0.7, Capacity.CONSCIOUSNESS, 0.2);
            case IMMUNE, SKIN -> Map.of();
        };
    }

    private static DiseaseDefinition.Trigger trigger(Obj json, DiseaseCategory category) {
        json.allow("environment", "chancePerDay", "eventChance", "minAge", "maxAge", "sex", "injury", "moodBelow");
        Set<EnvironmentFlag> environment = EnumSet.noneOf(EnvironmentFlag.class);
        for (String flag : json.strings("environment", 8)) environment.add(EnvironmentFlag.parse(flag, json.context()));
        var defaults = defaultTrigger(category);
        int eventOnly = json.has("eventChance") || json.has("injury") ? 0 : defaults.chancePerDay();
        return new DiseaseDefinition.Trigger(environment, fraction(json, "chancePerDay", eventOnly),
                fraction(json, "eventChance", defaults.eventChance()), json.intValue("minAge", defaults.minAge()),
                json.intValue("maxAge", defaults.maxAge()), json.has("sex") ? Sex.parse(json.string("sex"), json.context()) : null,
                json.has("injury") ? json.string("injury") : defaults.injury(), json.intValue("moodBelow", defaults.moodBelow()));
    }

    private static DiseaseDefinition.Trigger defaultTrigger(DiseaseCategory category) {
        return switch (category) {
            case CHRONIC -> new DiseaseDefinition.Trigger(Set.of(), 1_500, 0, 16, 999, null, null, -1);
            case AGE -> new DiseaseDefinition.Trigger(Set.of(), 4_000, 0, 55, 999, null, null, -1);
            case CANCER -> new DiseaseDefinition.Trigger(Set.of(), 600, 0, 35, 999, null, null, -1);
            case MENTAL -> new DiseaseDefinition.Trigger(Set.of(), 3_000, 0, 10, 999, null, null, 40);
            case REPRODUCTIVE -> new DiseaseDefinition.Trigger(Set.of(), 800, 0, 18, 999, null, null, -1);
            case NUTRITIONAL -> new DiseaseDefinition.Trigger(Set.of(), 1_000, 0, 0, 999, null, null, -1);
            case ENVIRONMENTAL, ALLERGY -> new DiseaseDefinition.Trigger(Set.of(), 20_000, 0, 0, 999, null, null, -1);
            case INJURY -> new DiseaseDefinition.Trigger(Set.of(), 0, 150_000, 0, 999, null, "any", -1);
            default -> DiseaseDefinition.Trigger.teen_pro_YT_NONE;
        };
    }

    private static BodySystem defaultSystem(DiseaseCategory category) {
        return switch (category) {
            case MENTAL -> BodySystem.MENTAL;
            case REPRODUCTIVE -> BodySystem.REPRODUCTIVE;
            case INJURY -> BodySystem.MUSCULOSKELETAL;
            case ALLERGY -> BodySystem.IMMUNE;
            case NUTRITIONAL -> BodySystem.DIGESTIVE;
            default -> BodySystem.IMMUNE;
        };
    }

    private static Set<Route> defaultRoutes(DiseaseCategory category, Pathogen pathogen) {
        return switch (category) {
            case INFECTIOUS -> switch (pathogen) {
                case VIRUS -> EnumSet.of(Route.CONTACT, Route.AIRBORNE);
                case BACTERIUM, FUNGUS -> EnumSet.of(Route.CONTACT);
                case PARASITE -> EnumSet.of(Route.FOOD, Route.WATER);
                default -> EnumSet.of(Route.SPONTANEOUS);
            };
            case GENETIC -> EnumSet.of(Route.HEREDITARY);
            case ENVIRONMENTAL, ALLERGY -> EnumSet.of(Route.ENVIRONMENT);
            case INJURY -> EnumSet.of(Route.INJURY);
            default -> EnumSet.of(Route.SPONTANEOUS);
        };
    }

    private static int defaultContagion(Pathogen pathogen) {
        return switch (pathogen) { case VIRUS -> 100_000; case BACTERIUM -> 70_000; case FUNGUS -> 30_000; case PARASITE -> 20_000; default -> 0; };
    }
    private static int defaultSeverityRate(Severity severity, boolean chronic) {
        int rate = switch (severity) { case TRIVIAL -> 120_000; case MILD -> 220_000; case MODERATE -> 320_000; case SEVERE -> 480_000; case DEADLY -> 680_000; };
        return chronic ? rate / 6 : rate;
    }
    private static int defaultImmunityRate(Severity severity, DiseaseCategory category) {
        if (category != DiseaseCategory.INFECTIOUS) return switch (severity) { case TRIVIAL -> 900_000; case MILD -> 600_000; case MODERATE -> 400_000; default -> 250_000; };
        return switch (severity) { case TRIVIAL -> 500_000; case MILD -> 420_000; case MODERATE -> 380_000; case SEVERE -> 420_000; case DEADLY -> 460_000; };
    }

    static long days(double value) {
        if (Double.isNaN(value) || value < 0 || value > 100) throw new IllegalArgumentException("Day value outside 0..100: " + value);
        return Math.round(value * 24_000);
    }
    private static int fraction(Obj json, String key, int fallback) {
        if (!json.has(key)) return fallback;
        double value = json.decimal(key);
        if (value < 0 || value > 1) throw json.fail(key + " must be 0..1");
        return (int) Math.round(value * teen_pro_YT_U);
    }
    private static int fraction5(Obj json, String key, int fallback) {
        if (!json.has(key)) return fallback;
        double value = json.decimal(key);
        if (value < 0 || value > 5) throw json.fail(key + " must be 0..5");
        return (int) Math.round(value * teen_pro_YT_U);
    }
    private static int fraction1000(Obj json, String key, int fallback) {
        if (!json.has(key)) return fallback;
        double value = json.decimal(key);
        if (value < 0 || value > 100) throw json.fail(key + " must be 0..100");
        return (int) Math.round(value * teen_pro_YT_U);
    }
    private static int rate(Obj json, String key, int fallback) {
        if (!json.has(key)) return fallback;
        double value = json.decimal(key);
        if (value < 0 || value > 10) throw json.fail(key + " must be 0..10 per day");
        return (int) Math.round(value * teen_pro_YT_U);
    }

    public static LinkedHashMap<String, Object> encode(DiseaseDefinition value) {
        LinkedHashMap<String, Object> tree = encodeUnsealed(value);
        tree.put("digest", value.digest());
        return tree;
    }

    private static LinkedHashMap<String, Object> encodeUnsealed(DiseaseDefinition value) {
        LinkedHashMap<String, Object> tree = Obj.tree();
        tree.put("schemaVersion", (long) teen_pro_YT_SCHEMA);
        tree.put("id", value.id());
        tree.put("version", (long) value.version());
        tree.put("family", value.family());
        tree.put("variety", (long) value.variety());
        LinkedHashMap<String, Object> name = Obj.tree();
        name.put("base", value.name().baseKey());
        if (value.name().strainKey() != null) name.put("strain", value.name().strainKey());
        if (value.name().code() != null) name.put("code", value.name().code());
        if (value.name().subtypeKey() != null) name.put("subtype", value.name().subtypeKey());
        tree.put("name", name);
        tree.put("category", value.category().id());
        tree.put("pathogen", value.pathogen().id());
        tree.put("severity", value.severity().id());
        tree.put("systems", value.systems().stream().map(BodySystem::id).map(Object.class::cast).toList());
        tree.put("routes", value.routes().stream().map(Route::id).map(Object.class::cast).toList());
        tree.put("contagiousness", (long) value.contagiousness());
        tree.put("radius", (long) value.radius());
        tree.put("incubationMin", value.incubationMin());
        tree.put("incubationMax", value.incubationMax());
        tree.put("contagiousIncubation", value.contagiousIncubation());
        tree.put("startSeverity", (long) value.startSeverity());
        tree.put("severityPerDay", (long) value.severityPerDay());
        tree.put("severityVariance", (long) value.severityVariance());
        tree.put("immunityPerDay", (long) value.immunityPerDay());
        tree.put("remissionPerDay", (long) value.remissionPerDay());
        tree.put("lethal", value.lethal());
        tree.put("chronic", value.chronic());
        tree.put("selfLimiting", value.selfLimiting());
        tree.put("tendable", value.tendable());
        tree.put("tendInterval", value.tendInterval());
        tree.put("tendFactor", (long) value.tendFactor());
        tree.put("immunityTicks", value.immunityTicks());
        tree.put("crossImmunity", (long) value.crossImmunity());
        var trigger = value.trigger();
        LinkedHashMap<String, Object> t = Obj.tree();
        t.put("environment", trigger.environment().stream().map(EnvironmentFlag::id).map(Object.class::cast).toList());
        t.put("chancePerDay", (long) trigger.chancePerDay());
        t.put("eventChance", (long) trigger.eventChance());
        t.put("minAge", (long) trigger.minAge());
        t.put("maxAge", (long) trigger.maxAge());
        if (trigger.sex() != null) t.put("sex", trigger.sex().id());
        if (trigger.injury() != null) t.put("injury", trigger.injury());
        t.put("moodBelow", (long) trigger.moodBelow());
        tree.put("trigger", t);
        List<Object> stages = new ArrayList<>();
        for (var stage : value.stages()) {
            LinkedHashMap<String, Object> s = Obj.tree();
            s.put("from", (long) stage.from());
            s.put("label", stage.label());
            s.put("contagion", (long) stage.contagion());
            s.put("effects", stage.effects().encode());
            s.put("symptoms", stage.symptoms().stream().map(Symptom::id).map(Object.class::cast).toList());
            s.put("visible", stage.visible());
            s.put("bedRest", stage.bedRest());
            stages.add(s);
        }
        tree.put("stages", stages);
        tree.put("mutations", new ArrayList<Object>(value.mutations()));
        tree.put("rarity", (long) value.rarity());
        return tree;
    }

    public static DiseaseDefinition decodePinned(Obj json) {
        json.allow("schemaVersion", "id", "version", "family", "variety", "name", "category", "pathogen", "severity", "systems", "routes",
                "contagiousness", "radius", "incubationMin", "incubationMax", "contagiousIncubation", "startSeverity", "severityPerDay",
                "severityVariance", "immunityPerDay", "remissionPerDay", "lethal", "chronic", "selfLimiting", "tendable", "tendInterval",
                "tendFactor", "immunityTicks", "crossImmunity", "trigger", "stages", "mutations", "rarity", "digest");
        if (json.intValue("schemaVersion") != teen_pro_YT_SCHEMA) throw json.fail("unsupported pinned disease schema");
        var nameJson = json.obj("name").allow("base", "strain", "code", "subtype");
        var name = new DiseaseDefinition.Name(nameJson.string("base"), nameJson.string("strain", null), nameJson.string("code", null), nameJson.string("subtype", null));
        List<BodySystem> systems = new ArrayList<>();
        for (String system : json.strings("systems", 6)) systems.add(BodySystem.parse(system, json.context()));
        Set<Route> routes = EnumSet.noneOf(Route.class);
        for (String route : json.strings("routes", 11)) routes.add(Route.parse(route, json.context()));
        var t = json.obj("trigger").allow("environment", "chancePerDay", "eventChance", "minAge", "maxAge", "sex", "injury", "moodBelow");
        Set<EnvironmentFlag> environment = EnumSet.noneOf(EnvironmentFlag.class);
        for (String flag : t.strings("environment", 8)) environment.add(EnvironmentFlag.parse(flag, t.context()));
        var trigger = new DiseaseDefinition.Trigger(environment, t.intValue("chancePerDay"), t.intValue("eventChance"), t.intValue("minAge"),
                t.intValue("maxAge"), t.has("sex") ? Sex.parse(t.string("sex"), t.context()) : null, t.string("injury", null), t.intValue("moodBelow"));
        List<DiseaseDefinition.Stage> stages = new ArrayList<>();
        for (Obj stage : json.objects("stages", DiseaseDefinition.teen_pro_YT_MAX_STAGES)) {
            stage.allow("from", "label", "contagion", "effects", "symptoms", "visible", "bedRest");
            Set<Symptom> symptoms = EnumSet.noneOf(Symptom.class);
            for (String symptom : stage.strings("symptoms", 24)) symptoms.add(Symptom.parse(symptom, stage.context()));
            stages.add(new DiseaseDefinition.Stage(stage.intValue("from"), stage.string("label"), stage.intValue("contagion"),
                    Modifiers.decode(stage.obj("effects")), symptoms, stage.bool("visible"), stage.bool("bedRest")));
        }
        var definition = new DiseaseDefinition(json.string("id"), json.intValue("version"), json.string("family"), json.intValue("variety"), name,
                DiseaseCategory.parse(json.string("category"), json.context()), Pathogen.parse(json.string("pathogen"), json.context()),
                Severity.parse(json.string("severity"), json.context()), systems, routes, json.intValue("contagiousness"), json.intValue("radius"),
                json.longValue("incubationMin"), json.longValue("incubationMax"), json.bool("contagiousIncubation"), json.intValue("startSeverity"),
                json.intValue("severityPerDay"), json.intValue("severityVariance"), json.intValue("immunityPerDay"), json.intValue("remissionPerDay"),
                json.bool("lethal"), json.bool("chronic"), json.bool("selfLimiting"), json.bool("tendable"), json.longValue("tendInterval"),
                json.intValue("tendFactor"), json.longValue("immunityTicks"), json.intValue("crossImmunity"), trigger, stages,
                Set.copyOf(json.strings("mutations", 4)), json.intValue("rarity"), json.string("digest"));
        if (!digest(definition).equals(definition.digest())) throw json.fail("pinned disease digest mismatch " + definition.id());
        return definition;
    }

    public static DiseaseDefinition seal(DiseaseDefinition value) {
        return with(value, value.mutations(), value.stages(), digest(value));
    }

    public static String digest(DiseaseDefinition value) {
        try {
            byte[] bytes = Json.write(encodeUnsealed(value)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static DiseaseDefinition with(DiseaseDefinition v, Set<String> mutations, List<DiseaseDefinition.Stage> stages, String digest) {
        return new DiseaseDefinition(v.id(), v.version(), v.family(), v.variety(), v.name(), v.category(), v.pathogen(), v.severity(),
                v.systems(), v.routes(), v.contagiousness(), v.radius(), v.incubationMin(), v.incubationMax(), v.contagiousIncubation(),
                v.startSeverity(), v.severityPerDay(), v.severityVariance(), v.immunityPerDay(), v.remissionPerDay(), v.lethal(), v.chronic(),
                v.selfLimiting(), v.tendable(), v.tendInterval(), v.tendFactor(), v.immunityTicks(), v.crossImmunity(), v.trigger(), stages,
                mutations, v.rarity(), digest);
    }

    static String lower(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
}
