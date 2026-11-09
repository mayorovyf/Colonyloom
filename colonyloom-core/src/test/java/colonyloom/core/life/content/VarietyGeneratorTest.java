package colonyloom.core.life.content;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class VarietyGeneratorTest {
    private static final String teen_pro_YT_FLU = "{\"schemaVersion\":1,\"category\":\"infectious\",\"severity\":\"moderate\",\"systems\":[\"respiratory\",\"immune\"],"
            + "\"symptoms\":[\"fever\",\"cough\",\"chills\"],\"contagiousness\":0.2,\"varieties\":{\"count\":40}}";

    private static List<DiseaseDefinition> varieties(String id, String json) {
        var base = DiseaseCodecTest.base(id, json);
        return VarietyGenerator.generate(base.definition(), base.varieties(), new HashSet<>());
    }

    @Test void catalogIsDeterministicAndIdentifiedByIndex() {
        var first = varieties("colonyloom:flu", teen_pro_YT_FLU);
        var second = varieties("colonyloom:flu", teen_pro_YT_FLU);
        assertEquals(40, first.size());
        assertEquals(first, second);
        for (int i = 0; i < first.size(); i++) {
            var variety = first.get(i);
            assertEquals(String.format("colonyloom:flu/v%02d", i + 1), variety.id());
            assertEquals("colonyloom:flu", variety.family());
            assertEquals(i + 1, variety.variety());
            assertEquals(DiseaseCodec.digest(variety), variety.digest());
            assertTrue(variety.name().spec().startsWith("@colonyloom.strain.format|#colonyloom.disease.flu|#colonyloom.strain."), variety.name().spec());
            assertTrue(variety.name().code().matches("[A-Z]-[0-9]{1,2}"), variety.name().code());
        }
        assertEquals(40, first.stream().map(DiseaseDefinition::digest).distinct().count());
        assertEquals(40, first.stream().map(v -> v.name().strainKey()).distinct().count(), "strain nicknames are unique within one disease");
        var other = varieties("colonyloom:grippe", teen_pro_YT_FLU);
        assertNotEquals(first.get(0).contagiousness(), other.get(0).contagiousness());
    }

    @Test void varietiesStayWithinConfiguredRangesAndMutationRules() {
        var base = DiseaseCodecTest.disease("colonyloom:flu", teen_pro_YT_FLU);
        int maxMutations = 0;
        Set<String> seen = new HashSet<>();
        for (var variety : varieties("colonyloom:flu", teen_pro_YT_FLU)) {
            double contagion = variety.contagiousness() / (double) base.contagiousness();
            assertTrue(contagion >= 0.6 * 0.85 - 1e-6 && contagion <= 1.8 * 1.2 + 1e-6, "contagion factor " + contagion);
            assertFalse(variety.mutations().contains("virulent") && variety.mutations().contains("mild"));
            assertTrue(variety.mutations().size() <= 2);
            assertFalse(variety.mutations().contains("hemorrhagic"), "hemorrhagic needs a severe base");
            maxMutations = Math.max(maxMutations, variety.mutations().size());
            seen.addAll(variety.mutations());
            assertEquals(base.stages().size(), variety.stages().size());
            assertEquals(base.category(), variety.category());
            assertEquals(base.chronic(), variety.chronic());
            if (variety.mutations().contains("airborne")) fail("base is already airborne");
            if (variety.mutations().contains("latent") || variety.mutations().contains("stealthy")) assertTrue(variety.contagiousIncubation());
            if (variety.mutations().contains("stealthy")) assertFalse(variety.stages().getFirst().visible());
            if (variety.mutations().contains("zoonotic")) assertTrue(variety.routes().contains(Route.ANIMAL));
            if (variety.mutations().contains("waterborne")) assertTrue(variety.routes().contains(Route.WATER));
            if (variety.mutations().contains("seasonal")) assertTrue(variety.trigger().environment().contains(EnvironmentFlag.COLD));
            if (variety.mutations().contains("neurotropic")) assertTrue(variety.stages().getLast().symptoms().contains(Symptom.CONFUSION));
            if (variety.mutations().contains("resistant")) assertTrue(variety.tendFactor() > base.tendFactor());
        }
        assertEquals(2, maxMutations);
        assertTrue(seen.size() >= 8, "forty strains exercise most mutations: " + seen);
    }

    @Test void mildStrainsOfNonDeadlyDiseasesAreNotLethalAndHemorrhagicOnesAre() {
        String severe = "{\"schemaVersion\":1,\"category\":\"infectious\",\"pathogen\":\"bacterium\",\"severity\":\"severe\",\"contagiousness\":0.1,"
                + "\"varieties\":{\"count\":40,\"mutations\":[\"mild\",\"hemorrhagic\"]}}";
        var base = DiseaseCodecTest.disease("colonyloom:fever", severe);
        assertTrue(base.lethal());
        boolean sawMild = false, sawHemorrhagic = false;
        for (var variety : varieties("colonyloom:fever", severe)) {
            if (variety.mutations().contains("hemorrhagic")) { sawHemorrhagic = true; assertTrue(variety.lethal()); assertTrue(variety.stages().getLast().symptoms().contains(Symptom.BLEEDING)); }
            else if (variety.mutations().contains("mild")) { sawMild = true; assertFalse(variety.lethal()); assertTrue(variety.severityPerDay() < base.severityPerDay() * 1.3 * 0.65 + 1); }
        }
        assertTrue(sawMild && sawHemorrhagic);
    }

    @Test void seedDependsOnIdVersionAndIndex() {
        assertEquals(VarietyGenerator.seed("a:b", 1, 1), VarietyGenerator.seed("a:b", 1, 1));
        assertNotEquals(VarietyGenerator.seed("a:b", 1, 1), VarietyGenerator.seed("a:b", 2, 1));
        assertNotEquals(VarietyGenerator.seed("a:b", 1, 1), VarietyGenerator.seed("a:b", 1, 2));
        assertNotEquals(VarietyGenerator.seed("a:b", 1, 1), VarietyGenerator.seed("a:c", 1, 1));
        assertEquals(96, VarietyGenerator.teen_pro_YT_STRAINS.size());
        assertEquals(96, Set.copyOf(VarietyGenerator.teen_pro_YT_STRAINS).size());
    }
}
