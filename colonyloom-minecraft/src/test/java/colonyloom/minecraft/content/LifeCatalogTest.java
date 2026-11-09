package colonyloom.minecraft.content;

import colonyloom.core.life.RichText;
import colonyloom.core.life.content.ActivityDefinition;
import colonyloom.core.life.content.Capacity;
import colonyloom.core.life.content.DiseaseDefinition;
import colonyloom.core.life.content.LifeCatalog;
import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.LifeIds;
import colonyloom.core.life.content.LifeStage;
import colonyloom.core.life.content.Situation;
import colonyloom.core.life.content.Symptom;
import colonyloom.core.life.content.VarietyGenerator;
import colonyloom.core.life.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LifeCatalogTest {
    private static final Path teen_pro_YT_RESOURCES = Path.of(System.getProperty("colonyloom.resources", "src/main/resources"));

    static LifeContent catalog() throws IOException {
        Path root = teen_pro_YT_RESOURCES.resolve(LifeCatalog.teen_pro_YT_ROOT);
        return LifeCatalog.load(path -> Files.newInputStream(root.resolve(path)));
    }

    static Map<String, Object> lang(String code) throws IOException { return read("assets/teen_pro_yt/lang/" + code + ".json"); }

    static Map<String, Object> read(String file) throws IOException {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (String line : Files.readAllLines(teen_pro_YT_RESOURCES.resolve(file), StandardCharsets.UTF_8)) {
            String entry = line.strip();
            if (entry.equals("{") || entry.equals("}") || entry.isEmpty()) continue;
            if (entry.endsWith(",")) entry = entry.substring(0, entry.length() - 1);
            Map<String, Object> one = Json.parseObject("{" + entry + "}");
            for (var item : one.entrySet()) assertNull(result.put(item.getKey(), item.getValue()), "duplicate key " + item.getKey());
        }
        return result;
    }

    @Test void ourTranslationsLiveApartAndNeverShadowTheBaseMod() throws IOException {
        for (String code : List.of("en_us", "ru_ru")) {
            Map<String, Object> base = Json.parseObject(Files.readString(teen_pro_YT_RESOURCES.resolve("assets/colonyloom/lang/" + code + ".json"), StandardCharsets.UTF_8));
            Set<String> shared = new TreeSet<>(lang(code).keySet());
            shared.retainAll(base.keySet());
            assertEquals(Set.of(), shared, "keys already owned by the base mod in " + code);
        }
    }

    @Test void contentShipsInsideTheModItself() throws IOException {
        Path root = teen_pro_YT_RESOURCES.resolve(LifeCatalog.teen_pro_YT_ROOT);
        Set<String> indexed = new TreeSet<>();
        for (String line : Files.readAllLines(root.resolve(LifeCatalog.teen_pro_YT_INDEX), StandardCharsets.UTF_8)) if (!line.isBlank()) indexed.add(line.strip());
        Set<String> present = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) present.add(root.relativize(file).toString().replace('\\', '/'));
        }
        assertEquals(present, indexed, "the index lists exactly the shipped files");
        assertFalse(Files.exists(teen_pro_YT_RESOURCES.resolve("data/colonyloom/colonyloom/life")));
        assertFalse(Files.exists(teen_pro_YT_RESOURCES.resolve("data/colonyloom/colonyloom/professions/doctor.json")));
    }

    @Test void catalogLoadsAsOneContentSetWithAThousandDiseases() throws IOException {
        LifeContent content = catalog();
        assertTrue(content.usable());
        assertTrue(content.diseases().size() >= 1000, "disease varieties: " + content.diseases().size());
        assertTrue(content.baseCount() >= 150, "base diseases: " + content.baseCount());
        assertNotNull(content.disease(LifeIds.teen_pro_YT_WOUND_INFECTION));
        assertNotNull(content.disease("colonyloom:common_cold/v01"));
        assertNotNull(content.disease("colonyloom:impotence/psychogenic"));
        assertTrue(content.genes().size() >= 30 && content.genes().size() <= 48, "every resident carries every locus");
        assertTrue(content.traits().size() >= 40);
        assertTrue(content.interactions().size() >= 20);
        assertTrue(content.activities().size() >= 40);
        assertEquals(Set.of("en", "ru"), content.names().keySet());
        long contagious = content.diseases().values().stream().filter(DiseaseDefinition::infectious).count();
        assertTrue(contagious >= 800, "contagious varieties: " + contagious);
        Set<Integer> contagion = new TreeSet<>();
        for (DiseaseDefinition disease : content.diseases().values()) if (disease.infectious()) contagion.add(disease.contagiousness() / 10_000);
        assertTrue(contagion.size() >= 20, "contagiousness spans many levels: " + contagion);
        assertTrue(content.diseases().values().stream().anyMatch(d -> d.lethal()), "some diseases kill");
        assertTrue(content.diseases().values().stream().anyMatch(d -> d.severity().ordinal() == 0), "some are trivial");
        for (DiseaseDefinition disease : content.diseases().values()) assertTrue(RichText.valid(disease.name().spec()), disease.id());
        for (ActivityDefinition.Purpose purpose : ActivityDefinition.Purpose.values())
            assertTrue(content.activities().values().stream().anyMatch(a -> a.purpose() == purpose), "activity purpose " + purpose);
        for (ActivityDefinition.MentalLevel level : ActivityDefinition.MentalLevel.values())
            assertTrue(content.activities().values().stream().anyMatch(a -> a.mental() == level), "mental break " + level);
        for (Situation situation : Situation.values()) if (situation != Situation.WRONG_SCHEDULE)
            assertNotNull(content.situation(situation), "thought for situation " + situation);
    }

    @Test void bothLanguagesTranslateEverythingTheSimulationSays() throws IOException {
        Map<String, Object> ru = lang("ru_ru"), en = lang("en_us");
        assertEquals(new TreeSet<>(en.keySet()), new TreeSet<>(ru.keySet()), "RU and EN have the same keys");
        LifeContent content = catalog();
        List<String> missing = new ArrayList<>();
        java.util.function.Consumer<String> need = key -> { if (!en.containsKey(key)) missing.add(key); };
        for (DiseaseDefinition disease : content.diseases().values()) {
            need.accept(disease.name().baseKey());
            if (disease.name().subtypeKey() != null) need.accept(disease.name().subtypeKey());
            for (var stage : disease.stages()) for (Symptom symptom : stage.symptoms()) {
                need.accept("colonyloom.symptom." + symptom.id());
                for (int i = 1; i <= 3; i++) need.accept("colonyloom.speech.symptom." + symptom.id() + "." + i);
            }
        }
        for (String strain : VarietyGenerator.teen_pro_YT_STRAINS) need.accept("colonyloom.strain." + strain);
        for (String mutation : VarietyGenerator.teen_pro_YT_MUTATIONS) need.accept("colonyloom.mutation." + mutation);
        for (String id : content.thoughts().keySet()) need.accept("colonyloom.thought." + topic(id));
        for (var interaction : content.interactions().values()) {
            need.accept("colonyloom.interaction." + topic(interaction.id()));
            for (int i = 1; i <= interaction.speech(); i++) need.accept("colonyloom.speech." + topic(interaction.id()) + "." + i);
            if (interaction.successChance() < 1_000_000 && interaction.speech() > 0)
                for (int i = 1; i <= 3; i++) need.accept("colonyloom.speech." + topic(interaction.id()) + ".fail." + i);
        }
        for (var activity : content.activities().values()) {
            need.accept("colonyloom.activity." + topic(activity.id()));
            if (activity.purpose() == ActivityDefinition.Purpose.MENTAL)
                for (int i = 1; i <= Math.max(1, activity.speech()); i++) need.accept("colonyloom.speech.break." + topic(activity.id()) + "." + i);
            else for (int i = 1; i <= activity.speech(); i++) need.accept("colonyloom.speech." + topic(activity.id()) + "." + i);
        }
        for (String id : content.traits().keySet()) { need.accept("colonyloom.trait." + topic(id)); need.accept("colonyloom.trait." + topic(id) + ".desc"); }
        for (var gene : content.genes().values()) {
            need.accept("colonyloom.gene." + topic(gene.id()));
            for (var allele : gene.alleles()) need.accept("colonyloom.allele." + topic(gene.id()) + "." + allele.id());
        }
        for (Capacity capacity : Capacity.values()) need.accept("colonyloom.capacity." + capacity.id());
        for (LifeStage stage : LifeStage.values()) need.accept("colonyloom.stage." + stage.id());
        for (String key : List.of("colonyloom.disease.format.plain", "colonyloom.strain.format", "colonyloom.death.vital", "colonyloom.death.old_age",
                "colonyloom.mood.base", "colonyloom.mood.character", "colonyloom.mood.memory", "colonyloom.mood.situation",
                "colonyloom.inspiration.work", "colonyloom.inspiration.social", "colonyloom.inspiration.creative", "colonyloom.profession.doctor"))
            need.accept(key);
        for (String event : List.of("sick", "recovered", "break", "inspired", "interaction", "lovers", "engaged", "wedding", "breakup", "expecting",
                "miscarriage", "grew_up", "birth", "birth_single", "died", "died_of")) need.accept("colonyloom.journal." + event);
        for (String topic : List.of("sick", "recovered", "tend")) need.accept("colonyloom.speech." + topic + ".1");
        assertEquals(List.of(), missing);
        for (var entry : ru.entrySet()) {
            String value = (String) entry.getValue(), english = (String) en.get(entry.getKey());
            assertEquals(placeholders(english), placeholders(value), "placeholders differ for " + entry.getKey());
        }
    }

    private static String topic(String id) { return id.substring(id.indexOf(':') + 1).replace('/', '.'); }

    private static List<String> placeholders(String value) {
        List<String> result = new ArrayList<>();
        var matcher = java.util.regex.Pattern.compile("%(\\d+\\$)?s").matcher(value);
        while (matcher.find()) result.add(matcher.group());
        result.sort(String::compareTo);
        return result;
    }
}
