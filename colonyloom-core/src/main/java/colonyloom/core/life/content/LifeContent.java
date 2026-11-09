package colonyloom.core.life.content;

import colonyloom.core.content.BlockDescriptor;
import colonyloom.core.life.json.Json;
import colonyloom.core.life.json.Obj;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class LifeContent {
    public static final int teen_pro_YT_MAX_SOURCES = 2048, teen_pro_YT_MAX_DISEASES = 4096;
    public enum Kind { DISEASES, GENES, TRAITS, THOUGHTS, INTERACTIONS, ACTIVITIES, NAMES;
        public String directory() { return name().toLowerCase(java.util.Locale.ROOT); }
        public int cap() { return switch (this) { case DISEASES -> 768; case GENES -> 64; case TRAITS -> 160; case THOUGHTS -> 512; case INTERACTIONS -> 128; case ACTIVITIES -> 160; case NAMES -> 16; }; }
    }
    public record Source(Kind kind, String id, String json) {}

    private final Map<String, DiseaseDefinition> teen_pro_YT_diseases;
    private final Map<String, List<DiseaseDefinition>> teen_pro_YT_families;
    private final Map<String, GeneDefinition> teen_pro_YT_genes;
    private final Map<String, TraitDefinition> teen_pro_YT_traits;
    private final Map<String, ThoughtDefinition> teen_pro_YT_thoughts;
    private final Map<Situation, ThoughtDefinition> teen_pro_YT_situations;
    private final Map<String, InteractionDefinition> teen_pro_YT_interactions;
    private final Map<String, ActivityDefinition> teen_pro_YT_activities;
    private final Map<String, NamePool> teen_pro_YT_names;
    private final Map<Route, List<DiseaseDefinition>> teen_pro_YT_byRoute;
    private final List<DiseaseDefinition> teen_pro_YT_triggered, teen_pro_YT_infectiousBases;
    private final List<Spontaneous> teen_pro_YT_spontaneous;

    public record Spontaneous(DiseaseDefinition.Trigger trigger, List<DiseaseDefinition> members) {
        public Spontaneous { members = List.copyOf(members); if (members.isEmpty()) throw new IllegalArgumentException("Empty trigger group"); }
    }
    private final int teen_pro_YT_baseCount;
    private final String teen_pro_YT_digest;

    private LifeContent(Map<String, DiseaseDefinition> diseases, Map<String, GeneDefinition> genes, Map<String, TraitDefinition> traits,
                        Map<String, ThoughtDefinition> thoughts, Map<String, InteractionDefinition> interactions,
                        Map<String, ActivityDefinition> activities, Map<String, NamePool> names, int baseCount, String digest) {
        this.teen_pro_YT_diseases = Collections.unmodifiableMap(diseases);
        this.teen_pro_YT_genes = Collections.unmodifiableMap(genes);
        this.teen_pro_YT_traits = Collections.unmodifiableMap(traits);
        this.teen_pro_YT_thoughts = Collections.unmodifiableMap(thoughts);
        this.teen_pro_YT_interactions = Collections.unmodifiableMap(interactions);
        this.teen_pro_YT_activities = Collections.unmodifiableMap(activities);
        this.teen_pro_YT_names = Collections.unmodifiableMap(names);
        this.teen_pro_YT_baseCount = baseCount;
        LinkedHashMap<String, List<DiseaseDefinition>> familyIndex = new LinkedHashMap<>();
        EnumMap<Route, List<DiseaseDefinition>> routes = new EnumMap<>(Route.class);
        for (Route route : Route.values()) routes.put(route, new ArrayList<>());
        List<DiseaseDefinition> trigger = new ArrayList<>(), bases = new ArrayList<>();
        LinkedHashMap<String, LinkedHashMap<DiseaseDefinition.Trigger, List<DiseaseDefinition>>> groups = new LinkedHashMap<>();
        for (DiseaseDefinition disease : diseases.values()) {
            familyIndex.computeIfAbsent(disease.family(), ignored -> new ArrayList<>()).add(disease);
            for (Route route : disease.routes()) routes.get(route).add(disease);
            var t = disease.trigger();
            if (t.chancePerDay() > 0 || t.eventChance() > 0) trigger.add(disease);
            if (t.chancePerDay() > 0) groups.computeIfAbsent(disease.family(), ignored -> new LinkedHashMap<>()).computeIfAbsent(t, ignored -> new ArrayList<>()).add(disease);
            if (disease.infectious() && disease.variety() == 0 && disease.id().equals(disease.family())) bases.add(disease);
        }
        familyIndex.replaceAll((k, v) -> List.copyOf(v));
        routes.replaceAll((k, v) -> List.copyOf(v));
        this.teen_pro_YT_families = Collections.unmodifiableMap(familyIndex);
        this.teen_pro_YT_byRoute = Collections.unmodifiableMap(routes);
        this.teen_pro_YT_triggered = List.copyOf(trigger);
        List<Spontaneous> spontaneousGroups = new ArrayList<>();
        for (var family : groups.values()) for (var group : family.entrySet()) spontaneousGroups.add(new Spontaneous(group.getKey(), group.getValue()));
        this.teen_pro_YT_spontaneous = List.copyOf(spontaneousGroups);
        this.teen_pro_YT_infectiousBases = List.copyOf(bases);
        EnumMap<Situation, ThoughtDefinition> situational = new EnumMap<>(Situation.class);
        for (ThoughtDefinition thought : thoughts.values()) if (thought.situation() != null) {
            if (situational.put(thought.situation(), thought) != null) throw new IllegalArgumentException("Two thoughts for situation " + thought.situation().id());
        }
        this.teen_pro_YT_situations = Collections.unmodifiableMap(situational);
        this.teen_pro_YT_digest = digest;
    }

    public static LifeContent empty() {
        return new LifeContent(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(),
                new LinkedHashMap<>(), new LinkedHashMap<>(), 0, digest(List.of()));
    }

    public static LifeContent load(List<Source> sources) {
        if (sources.size() > teen_pro_YT_MAX_SOURCES) throw new IllegalArgumentException("Too many life content files");
        EnumMap<Kind, Integer> counts = new EnumMap<>(Kind.class);
        TreeMap<String, Source> ordered = new TreeMap<>();
        for (Source source : sources) {
            BlockDescriptor.validateIdentifier(source.id());
            if (counts.merge(source.kind(), 1, Integer::sum) > source.kind().cap()) throw new IllegalArgumentException("Too many " + source.kind().directory() + " files");
            if (ordered.put(source.kind().ordinal() + ":" + source.id(), source) != null) throw new IllegalArgumentException("Duplicate life content " + source.id());
        }
        LinkedHashMap<String, DiseaseDefinition> diseases = new LinkedHashMap<>();
        LinkedHashMap<String, GeneDefinition> genes = new LinkedHashMap<>();
        LinkedHashMap<String, TraitDefinition> traits = new LinkedHashMap<>();
        LinkedHashMap<String, ThoughtDefinition> thoughts = new LinkedHashMap<>();
        LinkedHashMap<String, InteractionDefinition> interactions = new LinkedHashMap<>();
        LinkedHashMap<String, ActivityDefinition> activities = new LinkedHashMap<>();
        LinkedHashMap<String, NamePool> names = new LinkedHashMap<>();
        Set<String> usedStrains = new HashSet<>();
        int bases = 0;
        for (Source source : ordered.values()) {
            Obj json;
            try { json = Obj.of(Json.parse(source.json()), source.kind().directory() + "/" + source.id()); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid life content " + source.id() + ": " + error.getMessage(), error); }
            switch (source.kind()) {
                case DISEASES -> {
                    var base = DiseaseCodec.decodeBase(source.id(), json);
                    bases++;
                    put(diseases, base.definition().id(), base.definition());
                    for (var subtype : base.subtypes()) put(diseases, subtype.id(), subtype);
                    for (var variety : VarietyGenerator.generate(base.definition(), base.varieties(), usedStrains)) put(diseases, variety.id(), variety);
                }
                case GENES -> put(genes, source.id(), GeneDefinition.decode(source.id(), json));
                case TRAITS -> put(traits, source.id(), TraitDefinition.decode(source.id(), json));
                case THOUGHTS -> put(thoughts, source.id(), ThoughtDefinition.decode(source.id(), json));
                case INTERACTIONS -> put(interactions, source.id(), InteractionDefinition.decode(source.id(), json));
                case ACTIVITIES -> put(activities, source.id(), ActivityDefinition.decode(source.id(), json));
                case NAMES -> {
                    String culture = source.id().substring(source.id().indexOf(':') + 1);
                    put(names, culture, NamePool.decode(culture, json));
                }
            }
            if (diseases.size() > teen_pro_YT_MAX_DISEASES) throw new IllegalArgumentException("Disease catalog exceeds " + teen_pro_YT_MAX_DISEASES);
        }
        var content = new LifeContent(diseases, genes, traits, thoughts, interactions, activities, names, bases, digest(ordered.values()));
        content.validateReferences();
        return content;
    }

    private void validateReferences() {
        for (GeneDefinition gene : teen_pro_YT_genes.values()) {
            if (gene.condition() != null) {
                var disease = teen_pro_YT_diseases.get(gene.condition().disease());
                if (disease == null || disease.category() != DiseaseCategory.GENETIC) throw new IllegalArgumentException("Gene " + gene.id() + " condition must reference a genetic disease");
            }
            for (var allele : gene.alleles()) for (String trait : allele.traits().keySet())
                if (!teen_pro_YT_traits.containsKey(trait)) throw new IllegalArgumentException("Gene " + gene.id() + " references unknown trait " + trait);
        }
        for (TraitDefinition trait : teen_pro_YT_traits.values()) for (String conflict : trait.conflicts())
            if (!teen_pro_YT_traits.containsKey(conflict)) throw new IllegalArgumentException("Trait " + trait.id() + " conflicts with unknown " + conflict);
        for (InteractionDefinition interaction : teen_pro_YT_interactions.values()) {
            for (var outcome : List.of(interaction.success(), interaction.failure())) {
                thought(outcome.initiatorThought(), interaction.id()); thought(outcome.recipientThought(), interaction.id());
            }
        }
        for (ActivityDefinition activity : teen_pro_YT_activities.values()) thought(activity.memory(), activity.id());
        if (!teen_pro_YT_names.isEmpty() || !teen_pro_YT_thoughts.isEmpty() || !teen_pro_YT_activities.isEmpty()) {
            for (String id : LifeIds.teen_pro_YT_REQUIRED_THOUGHTS) if (!teen_pro_YT_thoughts.containsKey(id)) throw new IllegalArgumentException("Missing required thought " + id);
            for (var purpose : List.of(ActivityDefinition.Purpose.SLEEP, ActivityDefinition.Purpose.BED_REST, ActivityDefinition.Purpose.COLLAPSE,
                    ActivityDefinition.Purpose.IDLE, ActivityDefinition.Purpose.MENTAL, ActivityDefinition.Purpose.CHILD))
                if (teen_pro_YT_activities.values().stream().noneMatch(a -> a.purpose() == purpose)) throw new IllegalArgumentException("Missing activity purpose " + purpose);
            if (teen_pro_YT_names.isEmpty()) throw new IllegalArgumentException("Missing name pool");
            for (String id : List.of(LifeIds.teen_pro_YT_WOUND_INFECTION, LifeIds.teen_pro_YT_FOOD_POISONING))
                if (!teen_pro_YT_diseases.containsKey(id)) throw new IllegalArgumentException("Missing required disease " + id);
        }
    }

    private void thought(String id, String owner) {
        if (id != null && !teen_pro_YT_thoughts.containsKey(id)) throw new IllegalArgumentException(owner + " references unknown thought " + id);
    }

    private static <T> void put(Map<String, T> map, String id, T value) {
        if (map.put(id, value) != null) throw new IllegalArgumentException("Duplicate life definition " + id);
    }

    private static String digest(java.util.Collection<Source> ordered) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (Source source : ordered) {
                for (String part : new String[]{source.kind().directory(), source.id(), source.json()}) {
                    byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                    hash.update((byte) (bytes.length >>> 24)); hash.update((byte) (bytes.length >>> 16));
                    hash.update((byte) (bytes.length >>> 8)); hash.update((byte) bytes.length);
                    hash.update(bytes);
                }
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public Map<String, DiseaseDefinition> diseases() { return teen_pro_YT_diseases; }
    public DiseaseDefinition disease(String id) { return teen_pro_YT_diseases.get(id); }
    public List<DiseaseDefinition> family(String family) { return teen_pro_YT_families.getOrDefault(family, List.of()); }
    public List<DiseaseDefinition> byRoute(Route route) { return teen_pro_YT_byRoute.get(route); }
    public List<DiseaseDefinition> triggered() { return teen_pro_YT_triggered; }
    public List<Spontaneous> spontaneous() { return teen_pro_YT_spontaneous; }
    public Map<String, List<DiseaseDefinition>> families() { return teen_pro_YT_families; }
    public List<DiseaseDefinition> infectiousBases() { return teen_pro_YT_infectiousBases; }
    public int baseCount() { return teen_pro_YT_baseCount; }
    public Map<String, GeneDefinition> genes() { return teen_pro_YT_genes; }
    public Map<String, TraitDefinition> traits() { return teen_pro_YT_traits; }
    public Map<String, ThoughtDefinition> thoughts() { return teen_pro_YT_thoughts; }
    public ThoughtDefinition thought(String id) { return teen_pro_YT_thoughts.get(id); }
    public ThoughtDefinition situation(Situation situation) { return teen_pro_YT_situations.get(situation); }
    public Map<String, InteractionDefinition> interactions() { return teen_pro_YT_interactions; }
    public Map<String, ActivityDefinition> activities() { return teen_pro_YT_activities; }
    public ActivityDefinition activity(String id) { return teen_pro_YT_activities.get(id); }
    public Map<String, NamePool> names() { return teen_pro_YT_names; }
    public NamePool names(String culture) { return teen_pro_YT_names.containsKey(culture) ? teen_pro_YT_names.get(culture) : teen_pro_YT_names.values().stream().findFirst().orElse(null); }
    public String digest() { return teen_pro_YT_digest; }
    public boolean usable() { return !teen_pro_YT_names.isEmpty() && !teen_pro_YT_activities.isEmpty(); }
}
