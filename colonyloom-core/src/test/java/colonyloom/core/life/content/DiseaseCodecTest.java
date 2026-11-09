package colonyloom.core.life.content;

import colonyloom.core.life.json.Json;
import colonyloom.core.life.json.Obj;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DiseaseCodecTest {
    static DiseaseCodec.Base base(String id, String json) { return DiseaseCodec.decodeBase(id, Obj.of(Json.parse(json), id)); }
    static DiseaseDefinition disease(String id, String json) { return base(id, json).definition(); }

    @Test void infectiousDefaultsComeFromPathogenAndSeverity() {
        var cold = disease("colonyloom:cold", "{\"schemaVersion\":1,\"category\":\"infectious\",\"systems\":[\"respiratory\"],\"symptoms\":[\"cough\",\"sneeze\",\"headache\"]}");
        assertEquals(Pathogen.VIRUS, cold.pathogen());
        assertEquals(Severity.MILD, cold.severity());
        assertEquals(EnumSet.of(Route.CONTACT, Route.AIRBORNE), cold.routes());
        assertEquals(100_000, cold.contagiousness());
        assertEquals(3, cold.radius());
        assertEquals(3_600, cold.incubationMin());
        assertEquals(12_000, cold.incubationMax());
        assertTrue(cold.contagiousIncubation());
        assertTrue(cold.infectious());
        assertFalse(cold.chronic());
        assertFalse(cold.lethal());
        assertFalse(cold.selfLimiting());
        assertEquals(220_000, cold.severityPerDay());
        assertEquals(420_000, cold.immunityPerDay());
        assertEquals(144_000, cold.immunityTicks());
        assertEquals(12_000, cold.tendInterval());
        assertEquals("colonyloom:cold", cold.family());
        assertEquals(0, cold.variety());
        assertEquals("@colonyloom.disease.format.plain|#colonyloom.disease.cold", cold.name().spec());
        assertEquals(2, cold.stages().size());
        assertEquals(List.of("minor", "major"), cold.stages().stream().map(DiseaseDefinition.Stage::label).toList());
        assertEquals(EnumSet.of(Symptom.COUGH, Symptom.SNEEZE), cold.stages().get(0).symptoms());
        assertEquals(EnumSet.of(Symptom.COUGH, Symptom.SNEEZE, Symptom.HEADACHE), cold.stages().get(1).symptoms());
        double minor = cold.stages().get(0).effects().capacityOffsets().get(Capacity.BREATHING);
        double major = cold.stages().get(1).effects().capacityOffsets().get(Capacity.BREATHING);
        assertTrue(minor < 0 && major < minor, minor + " " + major);
        assertTrue(cold.stages().get(1).effects().stat(Stat.MOOD) < 0);
        assertTrue(cold.stages().get(1).effects().stat(Stat.WORK_SPEED) < 1);
        assertFalse(cold.stages().get(0).bedRest());
        assertEquals(1, cold.stageIndex(500_000));
        assertEquals("minor", cold.stage(499_999).label());
    }

    @Test void categoryDefaultsCoverChronicGeneticInjuryAndMental() {
        var diabetes = disease("colonyloom:diabetes", "{\"schemaVersion\":1,\"category\":\"chronic\",\"severity\":\"moderate\",\"systems\":[\"endocrine\"]}");
        assertTrue(diabetes.chronic());
        assertEquals(Pathogen.NONE, diabetes.pathogen());
        assertEquals(Set.of(Route.SPONTANEOUS), diabetes.routes());
        assertEquals(0, diabetes.contagiousness());
        assertFalse(diabetes.infectious());
        assertEquals(0, diabetes.immunityPerDay());
        assertEquals(diabetes.severityPerDay() / 3, diabetes.remissionPerDay());
        assertEquals(320_000 / 6, diabetes.severityPerDay());
        assertEquals(48_000, diabetes.tendInterval());
        assertEquals(1_500, diabetes.trigger().chancePerDay());
        assertEquals(16, diabetes.trigger().minAge());
        assertEquals(0, diabetes.immunityTicks());
        assertEquals(3, diabetes.stages().size());

        var hemophilia = disease("colonyloom:hemophilia", "{\"schemaVersion\":1,\"category\":\"genetic\",\"systems\":[\"blood\"]}");
        assertEquals(Set.of(Route.HEREDITARY), hemophilia.routes());
        assertTrue(hemophilia.chronic());
        assertEquals(0, hemophilia.trigger().chancePerDay());

        var sprain = disease("colonyloom:sprain", "{\"schemaVersion\":1,\"category\":\"injury\",\"trigger\":{\"injury\":\"fall\"}}");
        assertEquals(Set.of(Route.INJURY), sprain.routes());
        assertEquals(List.of(BodySystem.MUSCULOSKELETAL), sprain.systems());
        assertTrue(sprain.selfLimiting());
        assertEquals(150_000, sprain.trigger().eventChance());
        assertEquals("fall", sprain.trigger().injury());

        var blues = disease("colonyloom:blues", "{\"schemaVersion\":1,\"category\":\"mental\"}");
        assertEquals(40, blues.trigger().moodBelow());
        assertTrue(blues.stages().getFirst().effects().stat(Stat.MOOD) <= -2);
        assertTrue(blues.stages().get(1).effects().stat(Stat.BREAK_THRESHOLD) > 0);

        var impotence = disease("colonyloom:impotence", "{\"schemaVersion\":1,\"category\":\"reproductive\",\"trigger\":{\"chancePerDay\":0.002,\"sex\":\"male\",\"minAge\":30}}");
        assertEquals(Sex.MALE, impotence.trigger().sex());
        assertEquals(2_000, impotence.trigger().chancePerDay());
        assertTrue(impotence.stages().get(1).effects().capacityOffsets().get(Capacity.FERTILITY) < -0.1);
    }

    @Test void nonLethalDiseasesNeverZeroAVitalCapacity() {
        var mild = disease("colonyloom:bad_lungs", "{\"schemaVersion\":1,\"category\":\"chronic\",\"severity\":\"deadly\",\"lethal\":false,\"systems\":[\"respiratory\",\"cardiovascular\",\"blood\"]}");
        var lethal = disease("colonyloom:worse_lungs", "{\"schemaVersion\":1,\"category\":\"chronic\",\"severity\":\"deadly\",\"systems\":[\"respiratory\",\"cardiovascular\",\"blood\"]}");
        assertFalse(mild.lethal());
        assertTrue(lethal.lethal());
        for (var stage : mild.stages()) for (var entry : stage.effects().capacityOffsets().entrySet())
            if (entry.getKey().vital()) assertTrue(entry.getValue() >= -0.75, entry.toString());
        double critical = lethal.stages().getLast().effects().capacityOffsets().get(Capacity.BLOOD_PUMPING);
        assertTrue(critical < -0.75, "lethal critical stage reaches deeper: " + critical);
        assertEquals("critical", lethal.stages().getLast().label());
        assertTrue(lethal.stages().getLast().bedRest());
    }

    @Test void explicitStagesAndEffectsOverrideTemplates() {
        var custom = disease("colonyloom:custom", """
                {"schemaVersion":1,"category":"infectious","pathogen":"fungus","severity":"moderate","contagiousness":0.05,
                 "effects":{"stats":{"mood":-2}},
                 "stages":[{"from":0,"label":"initial","symptoms":["itch"],"visible":false},
                           {"from":0.4,"label":"major","contagion":2,"effects":{"capacities":{"manipulation":-0.2}},"bedRest":true}]}""");
        assertEquals(2, custom.stages().size());
        assertFalse(custom.stages().get(0).visible());
        assertEquals(-2.0, custom.stages().get(0).effects().stat(Stat.MOOD));
        assertEquals(-0.2, custom.stages().get(1).effects().capacityOffsets().get(Capacity.MANIPULATION));
        assertEquals(2_000_000, custom.stages().get(1).contagion());
        assertTrue(custom.stages().get(1).bedRest());
        assertEquals(Set.of(Route.CONTACT), custom.routes());
        assertEquals(12_000, custom.incubationMin());
    }

    @Test void subtypesInheritAndOverrideBaseFields() {
        var base = base("colonyloom:impotence", """
                {"schemaVersion":1,"category":"reproductive","severity":"mild","trigger":{"chancePerDay":0.002,"minAge":30,"sex":"male"},
                 "subtypes":[{"id":"psychogenic","selfLimiting":true,"chronic":false,"trigger":{"chancePerDay":0.001,"moodBelow":30,"sex":"male"}},
                             {"id":"organic","severity":"moderate"}]}""");
        assertEquals(2, base.subtypes().size());
        var psychogenic = base.subtypes().get(0);
        assertEquals("colonyloom:impotence/psychogenic", psychogenic.id());
        assertEquals("colonyloom:impotence", psychogenic.family());
        assertEquals(1, psychogenic.variety());
        assertFalse(psychogenic.chronic());
        assertTrue(psychogenic.selfLimiting());
        assertEquals(30, psychogenic.trigger().moodBelow());
        assertEquals(18, psychogenic.trigger().minAge());
        assertEquals("@colonyloom.disease.format.plain|#colonyloom.disease.impotence.psychogenic", psychogenic.name().spec());
        var organic = base.subtypes().get(1);
        assertEquals(Severity.MODERATE, organic.severity());
        assertEquals(30, organic.trigger().minAge());
        assertTrue(organic.chronic());
        assertNotEquals(organic.digest(), base.definition().digest());
    }

    @Test void rejectsInvalidAuthoring() {
        for (String json : List.of(
                "{\"schemaVersion\":2,\"category\":\"infectious\"}",
                "{\"schemaVersion\":1,\"category\":\"infectious\",\"colour\":\"red\"}",
                "{\"schemaVersion\":1,\"category\":\"plague\"}",
                "{\"schemaVersion\":1,\"category\":\"infectious\",\"pathogen\":\"none\"}",
                "{\"schemaVersion\":1,\"category\":\"infectious\",\"contagiousness\":1.5}",
                "{\"schemaVersion\":1,\"category\":\"infectious\",\"radius\":40}",
                "{\"schemaVersion\":1,\"category\":\"chronic\",\"routes\":[\"hereditary\"]}",
                "{\"schemaVersion\":1,\"category\":\"chronic\",\"varieties\":{\"count\":3}}",
                "{\"schemaVersion\":1,\"category\":\"infectious\",\"varieties\":{\"count\":41}}",
                "{\"schemaVersion\":1,\"category\":\"infectious\",\"varieties\":{\"count\":3,\"mutations\":[\"explosive\"]}}",
                "{\"schemaVersion\":1,\"category\":\"infectious\",\"stages\":[{\"from\":0.2,\"label\":\"minor\"}]}",
                "{\"schemaVersion\":1,\"category\":\"infectious\",\"stages\":[{\"from\":0,\"label\":\"minor\"},{\"from\":0,\"label\":\"major\"}]}",
                "{\"schemaVersion\":1,\"category\":\"infectious\",\"stages\":[{\"from\":0,\"label\":\"terrible\"}]}",
                "{\"schemaVersion\":1,\"category\":\"infectious\",\"incubationDays\":[2,1]}",
                "{\"schemaVersion\":1,\"category\":\"infectious\",\"incubationDays\":[1,500]}",
                "{\"schemaVersion\":1,\"category\":\"infectious\",\"trigger\":{\"injury\":\"bite\"}}",
                "{\"schemaVersion\":1,\"category\":\"infectious\",\"effects\":{\"capacities\":{\"breathing\":-5}}}",
                "{\"schemaVersion\":1,\"category\":\"infectious\",\"name\":\"Bad Name\"}",
                "{\"schemaVersion\":1,\"category\":\"infectious\",\"subtypes\":[{\"id\":\"x\",\"category\":\"chronic\"}]}",
                "{\"schemaVersion\":1,\"category\":\"infectious\",\"subtypes\":[{\"id\":\"Bad\"}]}"))
            assertThrows(IllegalArgumentException.class, () -> disease("colonyloom:bad", json), json);
    }

    @Test void pinRoundTripsThroughJsonAndPersistenceShapedTrees() {
        var plague = disease("colonyloom:plague", "{\"schemaVersion\":1,\"category\":\"infectious\",\"pathogen\":\"bacterium\",\"severity\":\"deadly\",\"routes\":[\"contact\",\"airborne\",\"animal\"],\"systems\":[\"respiratory\",\"blood\"],\"symptoms\":[\"fever\",\"cough\"],\"trigger\":{\"environment\":[\"near_wild_animals\"],\"chancePerDay\":0.0004}}");
        assertEquals(DiseaseCodec.digest(plague), plague.digest());
        assertEquals(64, plague.digest().length());
        var viaJson = DiseaseCodec.decodePinned(Obj.of(Json.parse(Json.write(DiseaseCodec.encode(plague))), "pin"));
        assertEquals(plague, viaJson);
        var nbtShaped = DiseaseCodec.decodePinned(Obj.of(nbtLike(DiseaseCodec.encode(plague)), "pin"));
        assertEquals(plague, nbtShaped);
    }

    @Test void pinRejectsTamperingAndUnknownFields() {
        var cold = disease("colonyloom:cold", "{\"schemaVersion\":1,\"category\":\"infectious\",\"severity\":\"trivial\"}");
        LinkedHashMap<String, Object> tree = DiseaseCodec.encode(cold);
        tree.put("contagiousness", 900_000L);
        assertThrows(IllegalArgumentException.class, () -> DiseaseCodec.decodePinned(Obj.of(tree, "pin")));
        LinkedHashMap<String, Object> forged = DiseaseCodec.encode(cold);
        forged.put("digest", "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> DiseaseCodec.decodePinned(Obj.of(forged, "pin")));
        LinkedHashMap<String, Object> extra = DiseaseCodec.encode(cold);
        extra.put("extra", 1L);
        assertThrows(IllegalArgumentException.class, () -> DiseaseCodec.decodePinned(Obj.of(extra, "pin")));
        LinkedHashMap<String, Object> future = DiseaseCodec.encode(cold);
        future.put("schemaVersion", 9L);
        assertThrows(IllegalArgumentException.class, () -> DiseaseCodec.decodePinned(Obj.of(future, "pin")));
    }

    @Test void digestChangesWithAnyBehaviourField() {
        var a = disease("colonyloom:cold", "{\"schemaVersion\":1,\"category\":\"infectious\",\"severity\":\"trivial\"}");
        var b = disease("colonyloom:cold", "{\"schemaVersion\":1,\"category\":\"infectious\",\"severity\":\"trivial\",\"contagiousness\":0.11}");
        var c = disease("colonyloom:cold", "{\"schemaVersion\":1,\"category\":\"infectious\",\"severity\":\"trivial\",\"version\":2}");
        var same = disease("colonyloom:cold", "{ \"category\" : \"infectious\", \"severity\":\"trivial\", \"schemaVersion\":1 }");
        assertNotEquals(a.digest(), b.digest());
        assertNotEquals(a.digest(), c.digest());
        assertEquals(a.digest(), same.digest());
    }

    static Object nbtLike(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put((String) k, nbtLike(v)));
            return copy;
        }
        if (value instanceof List<?> list) return list.stream().map(DiseaseCodecTest::nbtLike).toList();
        if (value instanceof Boolean b) return (byte) (b ? 1 : 0);
        if (value instanceof Integer i) return (long) i;
        return value;
    }

    @Test void eventTriggersAreNotAlsoSpontaneous() {
        var sting = disease("colonyloom:sting", "{\"schemaVersion\":1,\"category\":\"allergy\",\"trigger\":{\"injury\":\"attack\",\"eventChance\":0.005}}");
        assertEquals(0, sting.trigger().chancePerDay());
        assertEquals(5_000, sting.trigger().eventChance());
        assertEquals("attack", sting.trigger().injury());
        var pollen = disease("colonyloom:pollen", "{\"schemaVersion\":1,\"category\":\"allergy\",\"trigger\":{\"environment\":[\"near_flowers\"]}}");
        assertEquals(20_000, pollen.trigger().chancePerDay(), "a trigger that only narrows the place keeps the category's chance");
        assertEquals(Set.of(EnvironmentFlag.NEAR_FLOWERS), pollen.trigger().environment());
        var dust = disease("colonyloom:dust", "{\"schemaVersion\":1,\"category\":\"allergy\"}");
        assertEquals(20_000, dust.trigger().chancePerDay());
        var both = disease("colonyloom:chill", "{\"schemaVersion\":1,\"category\":\"environmental\",\"trigger\":{\"injury\":\"freeze\",\"eventChance\":0.3,\"chancePerDay\":0.015}}");
        assertEquals(15_000, both.trigger().chancePerDay(), "both when both are given");
    }
}
