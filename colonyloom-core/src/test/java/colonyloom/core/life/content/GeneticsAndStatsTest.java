package colonyloom.core.life.content;

import colonyloom.core.life.json.Json;
import colonyloom.core.life.json.Obj;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GeneticsAndStatsTest {
    private static GeneDefinition gene(String json) { return GeneDefinition.decode("colonyloom:test_gene", Obj.of(Json.parse(json), "gene")); }

    private static final String teen_pro_YT_RECESSIVE = "{\"schemaVersion\":1,\"alleles\":[{\"id\":\"normal\",\"frequency\":0.9,\"dominance\":2},"
            + "{\"id\":\"sickle\",\"frequency\":0.1,\"dominance\":1,\"resist\":{\"colonyloom:malaria\":0.3}}],"
            + "\"condition\":{\"allele\":\"sickle\",\"zygosity\":\"homozygous\",\"disease\":\"colonyloom:sickle_cell\",\"onsetAge\":2}}";

    @Test void dominanceDecidesExpressionAndCarriersStayHealthy() {
        var gene = gene(teen_pro_YT_RECESSIVE);
        assertEquals(Map.of("normal", 1.0), gene.expression("normal", "sickle"));
        assertEquals(Map.of("sickle", 1.0), gene.expression("sickle", "sickle"));
        assertFalse(gene.expressesCondition("normal", "sickle"));
        assertTrue(gene.carrier("sickle", "normal"));
        assertTrue(gene.expressesCondition("sickle", "sickle"));
        assertFalse(gene.carrier("sickle", "sickle"));
        assertFalse(gene.expressesCondition("normal", "normal"));
        assertEquals(0.3, gene.allele("sickle").resistances().get("colonyloom:malaria"));
        assertEquals(2, gene.condition().onsetAge());
    }

    @Test void additiveAndCodominantLociSplitExpression() {
        var additive = gene("{\"schemaVersion\":1,\"expression\":\"additive\",\"alleles\":[{\"id\":\"short\",\"frequency\":0.5},{\"id\":\"tall\",\"frequency\":0.5}]}");
        assertEquals(Map.of("short", 0.5, "tall", 0.5), additive.expression("short", "tall"));
        var tie = gene("{\"schemaVersion\":1,\"alleles\":[{\"id\":\"a\",\"frequency\":0.5,\"dominance\":3},{\"id\":\"b\",\"frequency\":0.5,\"dominance\":3}]}");
        assertEquals(Map.of("a", 0.5, "b", 0.5), tie.expression("a", "b"));
    }

    @Test void xLinkedLociAreHemizygousInMales() {
        var gene = gene(teen_pro_YT_RECESSIVE.replace("{\"schemaVersion\":1,", "{\"schemaVersion\":1,\"xLinked\":true,"));
        assertTrue(gene.xLinked());
        assertTrue(gene.expressesCondition("sickle", null), "a single X with the allele expresses it");
        assertFalse(gene.carrier("sickle", null));
        assertEquals(Map.of("sickle", 1.0), gene.expression("sickle", null));
    }

    @Test void foundersSampleByPopulationFrequency() {
        var gene = gene(teen_pro_YT_RECESSIVE);
        assertEquals("normal", gene.sample(0));
        assertEquals("normal", gene.sample(899_999));
        assertEquals("sickle", gene.sample(900_000));
        assertEquals("sickle", gene.sample(999_999));
    }

    @Test void rejectsBrokenLoci() {
        for (String json : List.of(
                "{\"schemaVersion\":1,\"alleles\":[{\"id\":\"a\",\"frequency\":0.5},{\"id\":\"b\",\"frequency\":0.4}]}",
                "{\"schemaVersion\":1,\"alleles\":[{\"id\":\"a\",\"frequency\":1}]}",
                "{\"schemaVersion\":1,\"alleles\":[{\"id\":\"a\",\"frequency\":0.5},{\"id\":\"a\",\"frequency\":0.5}]}",
                "{\"schemaVersion\":1,\"alleles\":[{\"id\":\"a\",\"frequency\":0.5},{\"id\":\"b\",\"frequency\":0.5}],\"condition\":{\"allele\":\"c\",\"disease\":\"colonyloom:x\"}}",
                "{\"schemaVersion\":1,\"alleles\":[{\"id\":\"a\",\"frequency\":0.5,\"dominance\":12},{\"id\":\"b\",\"frequency\":0.5}]}",
                "{\"schemaVersion\":1,\"alleles\":[{\"id\":\"a\",\"frequency\":0.5,\"resist\":{\"colonyloom:x\":11}},{\"id\":\"b\",\"frequency\":0.5}]}"))
            assertThrows(IllegalArgumentException.class, () -> gene(json), json);
    }

    @Test void statBlockCombinesOffsetsFactorsAndPain() {
        StatBlock block = new StatBlock();
        assertEquals(1.0, block.capacity(Capacity.MOVING));
        assertFalse(block.unconscious());
        assertNull(block.vitalFailure());
        block.add(new Modifiers(Map.of(Capacity.MOVING, -0.3), Map.of(Capacity.MOVING, 0.5), Map.of(Stat.PAIN, 0.3, Stat.WORK_SPEED, 0.8), Map.of(), Map.of(), Map.of()));
        assertEquals(0.35, block.rawCapacity(Capacity.MOVING), 1e-9);
        assertEquals(0.3, block.pain(), 1e-9);
        assertEquals(1.0 - 0.2 * 0.6, block.capacity(Capacity.CONSCIOUSNESS), 1e-9);
        assertEquals(0.8, block.stat(Stat.WORK_SPEED), 1e-9);
        block.addStat(Stat.PAIN_FACTOR, 0.5);
        assertEquals(0.15, block.pain(), 1e-9);
        block.addStat(Stat.PAIN, 2.0).addStat(Stat.PAIN_FACTOR, 2.0);
        assertTrue(block.unconscious(), "pain at the cap knocks a resident out");
        assertTrue(block.capacity(Capacity.TALKING) <= block.capacity(Capacity.CONSCIOUSNESS) + 0.1 + 1e-9);
        block.reset().addCapacity(Capacity.BREATHING, -1.2);
        assertEquals(Capacity.BREATHING, block.vitalFailure());
        assertEquals(0.0, block.capacity(Capacity.CONSCIOUSNESS), 1e-9);
        block.reset().addCapacity(Capacity.BLOOD_PUMPING, -0.75);
        assertEquals(0.25 * 1.6, block.capacity(Capacity.CONSCIOUSNESS), 1e-9);
        assertNull(block.vitalFailure());
    }

    @Test void modifiersScaleTowardsNeutralAndComposeLikeTheBlock() {
        var a = new Modifiers(Map.of(Capacity.SIGHT, -0.4), Map.of(Capacity.HEARING, 0.5), Map.of(Stat.MOOD, -10.0, Stat.REST_FALL, 2.0),
                Map.of(BodySystem.RESPIRATORY, 3.0), Map.of("calm", 2.0), Map.of("colonyloom:chat", 0.5));
        var half = a.scaled(0.5);
        assertEquals(-0.2, half.capacityOffsets().get(Capacity.SIGHT), 1e-9);
        assertEquals(0.75, half.capacityFactors().get(Capacity.HEARING), 1e-9);
        assertEquals(-5.0, half.stat(Stat.MOOD), 1e-9);
        assertEquals(1.5, half.stat(Stat.REST_FALL), 1e-9);
        assertEquals(2.0, half.susceptibility().get(BodySystem.RESPIRATORY), 1e-9);
        assertEquals(1.5, half.activityTags().get("calm"), 1e-9);
        assertEquals(0.75, half.interactions().get("colonyloom:chat"), 1e-9);
        var sum = a.plus(a);
        assertEquals(-0.8, sum.capacityOffsets().get(Capacity.SIGHT), 1e-9);
        assertEquals(0.25, sum.capacityFactors().get(Capacity.HEARING), 1e-9);
        assertEquals(4.0, sum.stat(Stat.REST_FALL), 1e-9);
        StatBlock block = new StatBlock().add(a).add(a);
        assertEquals(1 - 0.8, block.rawCapacity(Capacity.SIGHT), 1e-9);
        assertEquals(4.0, block.stat(Stat.REST_FALL), 1e-9);
        assertEquals(4.0, block.activityWeight("calm"), 1e-9);
        assertEquals(1.0, block.activityWeight("loud"), 1e-9);
        assertEquals(0.25, block.interactionWeight("colonyloom:chat"), 1e-9);
        assertEquals(9.0, block.susceptibility(BodySystem.RESPIRATORY), 1e-9);
        assertSame(a, a.plus(Modifiers.teen_pro_YT_EMPTY));
        assertSame(a, Modifiers.teen_pro_YT_EMPTY.plus(a));
        var clamped = new Modifiers(Map.of(Capacity.SIGHT, -1.5), Map.of(), Map.of(Stat.MOOD, -80.0), Map.of(), Map.of(), Map.of());
        assertEquals(-2.0, clamped.plus(clamped).capacityOffsets().get(Capacity.SIGHT), 1e-9);
        assertEquals(-100.0, clamped.plus(clamped).plus(clamped).stat(Stat.MOOD), 1e-9);
    }

    @Test void modifiersRejectOutOfRangeValuesAndNormaliseNegativeZero() {
        assertThrows(IllegalArgumentException.class, () -> new Modifiers(Map.of(Capacity.SIGHT, -3.0), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new Modifiers(Map.of(), Map.of(), Map.of(Stat.WORK_SPEED, -0.1), Map.of(), Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new Modifiers(Map.of(), Map.of(), Map.of(Stat.MOOD, Double.NaN), Map.of(), Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new Modifiers(Map.of(), Map.of(), Map.of(), Map.of(), Map.of("Bad Key", 1.0), Map.of()));
        EnumMap<Stat, Double> zero = new EnumMap<>(Stat.class);
        zero.put(Stat.MOOD, -0.0);
        var normalised = new Modifiers(Map.of(), Map.of(), zero, Map.of(), Map.of(), Map.of());
        assertEquals(Double.valueOf(0.0), normalised.stats().get(Stat.MOOD));
        var decoded = Modifiers.decode(Obj.of(Json.parse(Json.write(normalised.encode())), "m"));
        assertEquals(normalised, decoded);
    }

    @Test void traitsAndThoughtsValidateTheirShape() {
        var gay = TraitDefinition.decode("colonyloom:gay", Obj.of(Json.parse("{\"schemaVersion\":1,\"group\":\"orientation\",\"orientation\":\"gay\"}"), "t"));
        assertTrue(gay.attractedTo(Sex.MALE, Sex.MALE));
        assertFalse(gay.attractedTo(Sex.MALE, Sex.FEMALE));
        var plain = TraitDefinition.decode("colonyloom:kind", Obj.of(Json.parse("{\"schemaVersion\":1}"), "t"));
        assertEquals("kind", plain.group());
        assertTrue(plain.attractedTo(Sex.FEMALE, Sex.MALE), "no orientation trait means straight");
        assertThrows(IllegalArgumentException.class, () -> TraitDefinition.decode("colonyloom:x", Obj.of(Json.parse("{\"schemaVersion\":1,\"orientation\":\"gay\"}"), "t")));
        var memory = ThoughtDefinition.decode("colonyloom:m", Obj.of(Json.parse("{\"schemaVersion\":1,\"mood\":-5,\"durationDays\":2,\"opinion\":-10}"), "t"));
        assertEquals(48_000, memory.duration());
        assertTrue(memory.social());
        var situational = ThoughtDefinition.decode("colonyloom:s", Obj.of(Json.parse("{\"schemaVersion\":1,\"mood\":-5,\"situation\":\"sick\"}"), "t"));
        assertEquals(0, situational.duration());
        assertEquals(Situation.SICK, situational.situation());
        assertThrows(IllegalArgumentException.class, () -> ThoughtDefinition.decode("colonyloom:s", Obj.of(Json.parse("{\"schemaVersion\":1,\"situation\":\"sick\",\"opinion\":5}"), "t")));
        assertThrows(IllegalArgumentException.class, () -> ThoughtDefinition.decode("colonyloom:s", Obj.of(Json.parse("{\"schemaVersion\":1,\"mood\":500}"), "t")));
    }
}
