package colonyloom.gameplay.life;

import colonyloom.core.life.content.Capacity;
import colonyloom.core.life.content.Sex;
import colonyloom.core.life.content.Stat;
import colonyloom.core.life.state.Records.AllelePair;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import static colonyloom.gameplay.life.LifeTestKit.teen_pro_YT_CONTENT;
import static org.junit.jupiter.api.Assertions.*;

final class GeneticsTest {
    private static final String teen_pro_YT_HEMO = "colonyloom:hemophilia_gene", teen_pro_YT_IMMUNITY = "colonyloom:immunity";

    @Test void foundersAreDeterministicPerSeedAndHemizygousOnMaleX() {
        var a = Genetics.founder(teen_pro_YT_CONTENT, Sex.MALE, new SplittableRandom(42));
        var b = Genetics.founder(teen_pro_YT_CONTENT, Sex.MALE, new SplittableRandom(42));
        assertEquals(a, b);
        assertEquals(2, a.size());
        assertNull(a.get(teen_pro_YT_HEMO).second(), "males carry one X");
        assertNotNull(Genetics.founder(teen_pro_YT_CONTENT, Sex.FEMALE, new SplittableRandom(42)).get(teen_pro_YT_HEMO).second());
        int rare = 0;
        var random = new SplittableRandom(1);
        for (int i = 0; i < 2_000; i++) if (Genetics.founder(teen_pro_YT_CONTENT, Sex.MALE, random).get(teen_pro_YT_HEMO).first().equals("hemophilia")) rare++;
        assertTrue(rare > 30 && rare < 100, "about 3% of founder X chromosomes carry it: " + rare);
    }

    @Test void inheritanceFollowsMendelAndTheXChromosome() {
        Map<String, AllelePair> carrierMother = Map.of(teen_pro_YT_HEMO, new AllelePair("normal", "hemophilia"), teen_pro_YT_IMMUNITY, new AllelePair("strong", "strong"));
        Map<String, AllelePair> healthyFather = Map.of(teen_pro_YT_HEMO, new AllelePair("normal", null), teen_pro_YT_IMMUNITY, new AllelePair("weak", "weak"));
        var random = new SplittableRandom(3);
        int affectedSons = 0, carrierDaughters = 0, affectedDaughters = 0;
        for (int i = 0; i < 1_000; i++) {
            var son = Genetics.inherit(teen_pro_YT_CONTENT, carrierMother, healthyFather, Sex.MALE, 0, random);
            assertNull(son.get(teen_pro_YT_HEMO).second());
            if (son.get(teen_pro_YT_HEMO).first().equals("hemophilia")) affectedSons++;
            var daughter = Genetics.inherit(teen_pro_YT_CONTENT, carrierMother, healthyFather, Sex.FEMALE, 0, random);
            assertEquals("normal", daughter.get(teen_pro_YT_HEMO).second(), "a daughter gets her father's only X");
            if (daughter.get(teen_pro_YT_HEMO).first().equals("hemophilia")) carrierDaughters++;
            if (daughter.get(teen_pro_YT_HEMO).first().equals("hemophilia") && daughter.get(teen_pro_YT_HEMO).second().equals("hemophilia")) affectedDaughters++;
            assertEquals(Map.of("strong", 0.5, "weak", 0.5), Map.of(son.get(teen_pro_YT_IMMUNITY).first(), 0.5, son.get(teen_pro_YT_IMMUNITY).second(), 0.5),
                    "one autosomal allele from each parent");
        }
        assertTrue(affectedSons > 420 && affectedSons < 580, "half the sons of a carrier are affected: " + affectedSons);
        assertTrue(carrierDaughters > 420 && carrierDaughters < 580);
        assertEquals(0, affectedDaughters, "a daughter needs an affected father too");
    }

    @Test void missingParentsAreSampledAndMutationChangesAlleles() {
        var random = new SplittableRandom(4);
        var orphan = Genetics.inherit(teen_pro_YT_CONTENT, null, null, Sex.FEMALE, 0, random);
        assertEquals(2, orphan.size());
        assertNotNull(orphan.get(teen_pro_YT_HEMO).second());
        Map<String, AllelePair> parent = Map.of(teen_pro_YT_HEMO, new AllelePair("normal", "normal"), teen_pro_YT_IMMUNITY, new AllelePair("average", "average"));
        var mutated = Genetics.inherit(teen_pro_YT_CONTENT, parent, Map.of(teen_pro_YT_HEMO, new AllelePair("normal", null), teen_pro_YT_IMMUNITY, new AllelePair("average", "average")), Sex.FEMALE, 1.0, random);
        assertNotEquals("average", mutated.get(teen_pro_YT_IMMUNITY).first());
        assertNotEquals("normal", mutated.get(teen_pro_YT_HEMO).first());
    }

    @Test void expressionShapesStatsAndTraitInclinations() {
        var kit = new LifeTestKit(5);
        Resident strong = kit.add(10, Sex.MALE, 30, 5, 5), mixed = kit.add(11, Sex.MALE, 30, 5, 5);
        strong.life().genome(Map.of(teen_pro_YT_IMMUNITY, new AllelePair("strong", "strong")));
        mixed.life().genome(Map.of(teen_pro_YT_IMMUNITY, new AllelePair("strong", "weak")));
        kit.rebuild(strong); kit.rebuild(mixed);
        assertEquals(1.25, strong.life().stats().stat(Stat.IMMUNITY_GAIN), 1e-9);
        assertEquals((1 + 0.25 * 0.5) * (1 - 0.2 * 0.5), mixed.life().stats().stat(Stat.IMMUNITY_GAIN), 1e-9, "additive alleles share the effect");
        assertEquals(Map.of("colonyloom:tough", 2.0), Genetics.traitWeights(teen_pro_YT_CONTENT, strong.life()));
        assertEquals(Map.of("colonyloom:tough", 1.0), Genetics.traitWeights(teen_pro_YT_CONTENT, mixed.life()));
        assertEquals(1.0, Genetics.resistance(teen_pro_YT_CONTENT, strong.life(), "colonyloom:plague"));
    }

    @Test void statsReflectAgePregnancyAndIllness() {
        var kit = new LifeTestKit(6);
        Resident baby = kit.add(10, Sex.FEMALE, 1, 5, 5), elder = kit.add(11, Sex.MALE, 85, 5, 5), adult = kit.add(12, Sex.MALE, 30, 5, 5);
        kit.rebuild(baby); kit.rebuild(elder); kit.rebuild(adult);
        assertEquals(0.4, baby.life().stats().rawCapacity(Capacity.MOVING), 1e-9);
        assertTrue(elder.life().stats().rawCapacity(Capacity.MOVING) < 0.9);
        assertEquals(0.7, elder.life().stats().stat(Stat.IMMUNITY_GAIN), 1e-9);
        assertEquals(1.0, adult.life().stats().rawCapacity(Capacity.MOVING), 1e-9);
        kit.teen_pro_YT_life.infect(adult.life(), teen_pro_YT_CONTENT.disease("colonyloom:plague"), 0, LifeRules.teen_pro_YT_UNIT, null).severity(900_000);
        kit.rebuild(adult);
        assertTrue(adult.life().stats().rawCapacity(Capacity.BREATHING) < 0.5, "critical plague chokes");
        assertTrue(adult.life().stats().pain() > 0.2);
        assertTrue(adult.life().statsValid());
    }
}
