package colonyloom.core.life.content;

import colonyloom.core.content.BlockDescriptor;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record DiseaseDefinition(String id, int version, String family, int variety, Name name,
        DiseaseCategory category, Pathogen pathogen, Severity severity,
        List<BodySystem> systems, Set<Route> routes,
        int contagiousness, int radius, long incubationMin, long incubationMax, boolean contagiousIncubation,
        int startSeverity, int severityPerDay, int severityVariance, int immunityPerDay, int remissionPerDay,
        boolean lethal, boolean chronic, boolean selfLimiting,
        boolean tendable, long tendInterval, int tendFactor, long immunityTicks, int crossImmunity,
        Trigger trigger, List<Stage> stages, Set<String> mutations, int rarity, String digest) {
    public static final int teen_pro_YT_UNIT = 1_000_000;
    public static final int teen_pro_YT_MAX_STAGES = 8;
    public static final long teen_pro_YT_PERMANENT = -1;
    public static final Set<String> teen_pro_YT_STAGE_LABELS = Set.of("incubation", "initial", "minor", "major", "extreme", "critical", "latent", "remission", "chronic");

    public record Name(String baseKey, String strainKey, String code, String subtypeKey) {
        public Name {
            key(baseKey, "baseKey");
            if (strainKey != null) key(strainKey, "strainKey");
            if (subtypeKey != null) key(subtypeKey, "subtypeKey");
            if (code != null && !code.matches("[A-Z0-9-]{1,12}")) throw new IllegalArgumentException("Invalid strain code " + code);
        }
        public String spec() {
            String base = "#" + (subtypeKey != null ? subtypeKey : baseKey);
            if (strainKey == null) return "@colonyloom.disease.format.plain|" + base;
            return "@colonyloom.strain.format|" + base + "|#" + strainKey + "|" + code;
        }
        private static void key(String value, String field) {
            if (value == null || value.length() > 128 || !value.matches("[a-z0-9_.]+")) throw new IllegalArgumentException("Invalid translation key " + field + "=" + value);
        }
    }

    public record Trigger(Set<EnvironmentFlag> environment, int chancePerDay, int eventChance,
                          int minAge, int maxAge, Sex sex, String injury, int moodBelow) {
        public static final Trigger teen_pro_YT_NONE = new Trigger(Set.of(), 0, 0, 0, 999, null, null, -1);
        public Trigger {
            environment = Collections.unmodifiableSet(environment.isEmpty() ? EnumSet.noneOf(EnvironmentFlag.class) : EnumSet.copyOf(environment));
            micro(chancePerDay, "chancePerDay"); micro(eventChance, "eventChance");
            if (minAge < 0 || maxAge < minAge || maxAge > 999 || moodBelow < -1 || moodBelow > 100) throw new IllegalArgumentException("Invalid trigger range");
            if (injury != null && !Set.of("fall", "fire", "freeze", "attack", "drown", "explosion", "any").contains(injury)) throw new IllegalArgumentException("Unknown injury trigger " + injury);
        }
        public boolean ageAllows(int years) { return years >= minAge && years <= maxAge; }
    }

    public record Stage(int from, String label, int contagion, Modifiers effects, Set<Symptom> symptoms, boolean visible, boolean bedRest) {
        public Stage {
            micro(from, "stage.from");
            if (!teen_pro_YT_STAGE_LABELS.contains(label)) throw new IllegalArgumentException("Unknown stage label " + label);
            if (contagion < 0 || contagion > 5 * teen_pro_YT_UNIT) throw new IllegalArgumentException("Invalid stage contagion");
            Objects.requireNonNull(effects);
            symptoms = Collections.unmodifiableSet(symptoms.isEmpty() ? EnumSet.noneOf(Symptom.class) : EnumSet.copyOf(symptoms));
        }
    }

    public DiseaseDefinition {
        BlockDescriptor.validateIdentifier(id);
        BlockDescriptor.validateIdentifier(family);
        if (version < 1 || variety < 0 || variety > 999) throw new IllegalArgumentException("Invalid disease version/variety " + id);
        Objects.requireNonNull(name); Objects.requireNonNull(category); Objects.requireNonNull(pathogen); Objects.requireNonNull(severity);
        Objects.requireNonNull(trigger);
        systems = List.copyOf(systems);
        if (systems.size() > 6 || systems.stream().distinct().count() != systems.size()) throw new IllegalArgumentException("Invalid disease systems " + id);
        routes = Collections.unmodifiableSet(routes.isEmpty() ? EnumSet.noneOf(Route.class) : EnumSet.copyOf(routes));
        micro(contagiousness, "contagiousness"); micro(startSeverity, "startSeverity"); micro(severityVariance, "severityVariance");
        micro(crossImmunity, "crossImmunity"); micro(tendFactor, "tendFactor");
        if (severityPerDay < 0 || severityPerDay > 10 * teen_pro_YT_UNIT || immunityPerDay < 0 || immunityPerDay > 10 * teen_pro_YT_UNIT
                || remissionPerDay < 0 || remissionPerDay > 10 * teen_pro_YT_UNIT) throw new IllegalArgumentException("Invalid disease rates " + id);
        if (radius < 0 || radius > 16) throw new IllegalArgumentException("Invalid disease radius " + id);
        if (incubationMin < 0 || incubationMax < incubationMin || incubationMax > 2_400_000) throw new IllegalArgumentException("Invalid incubation " + id);
        if (tendInterval < 0 || tendInterval > 2_400_000 || immunityTicks < teen_pro_YT_PERMANENT || immunityTicks > 24_000_000) throw new IllegalArgumentException("Invalid disease durations " + id);
        if (rarity < 0 || rarity > 100 * teen_pro_YT_UNIT) throw new IllegalArgumentException("Invalid rarity " + id);
        stages = List.copyOf(stages);
        if (stages.isEmpty() || stages.size() > teen_pro_YT_MAX_STAGES || stages.getFirst().from() != 0) throw new IllegalArgumentException("Stages must start at severity 0: " + id);
        for (int i = 1; i < stages.size(); i++) if (stages.get(i).from() <= stages.get(i - 1).from()) throw new IllegalArgumentException("Stages must ascend: " + id);
        mutations = Collections.unmodifiableSet(new TreeSet<>(mutations));
        for (String mutation : mutations) if (!VarietyGenerator.teen_pro_YT_MUTATIONS.contains(mutation)) throw new IllegalArgumentException("Unknown mutation " + mutation);
        if (routes.contains(Route.HEREDITARY) && category != DiseaseCategory.GENETIC) throw new IllegalArgumentException("Hereditary route requires genetic category: " + id);
        Objects.requireNonNull(digest, "digest");
    }

    public boolean infectious() { return category == DiseaseCategory.INFECTIOUS && contagiousness > 0; }
    public boolean transmits(Route route) { return routes.contains(route); }

    public Stage stage(int severityMicro) {
        Stage current = stages.getFirst();
        for (Stage stage : stages) if (severityMicro >= stage.from()) current = stage;
        return current;
    }
    public int stageIndex(int severityMicro) {
        int index = 0;
        for (int i = 0; i < stages.size(); i++) if (severityMicro >= stages.get(i).from()) index = i;
        return index;
    }

    static void micro(int value, String field) {
        if (value < 0 || value > teen_pro_YT_UNIT) throw new IllegalArgumentException("Invalid fraction " + field + ": " + value);
    }
}
