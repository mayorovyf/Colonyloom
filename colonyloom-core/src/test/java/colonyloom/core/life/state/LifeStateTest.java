package colonyloom.core.life.state;

import colonyloom.core.life.LifeFixtures;
import colonyloom.core.life.RichText;
import colonyloom.core.life.content.ActivityDefinition;
import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.LifeStage;
import colonyloom.core.life.content.Sex;
import colonyloom.core.life.content.Skill;
import colonyloom.core.life.json.Json;
import colonyloom.core.life.state.Records.Acquaintance;
import colonyloom.core.life.state.Records.Activity;
import colonyloom.core.life.state.Records.AllelePair;
import colonyloom.core.life.state.Records.BondKind;
import colonyloom.core.life.state.Records.Immunity;
import colonyloom.core.life.state.Records.Memory;
import colonyloom.core.life.state.Records.Mental;
import colonyloom.core.life.state.Records.Pregnancy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LifeStateTest {
    static UUID id(long value) { return new UUID(7, value); }
    static LifeState state(long id, Sex sex) { return new LifeState(id(id), id(1), sex, 24_000L * 30, 100, "Anna", "Ivanova"); }

    @Test void memoriesStackEvictAndExpire() {
        LifeState state = state(10, Sex.FEMALE);
        for (int i = 0; i < 5; i++) state.remember(new Memory("colonyloom:chitchat", 2, 3, 3, 500_000, 1000, 600 + i * 100, id(20)));
        assertEquals(3, state.memories().size(), "stack limit keeps the three freshest");
        assertTrue(state.memories().stream().allMatch(m -> m.remaining() >= 800));
        state.remember(new Memory("colonyloom:chitchat", 2, 3, 3, 500_000, 1000, 1000, id(21)));
        assertEquals(4, state.memories().size(), "stacks are per person");
        for (int i = 0; i < 40; i++) state.remember(new Memory("colonyloom:m" + i, -1, 0, 1, 500_000, 2000, 2000, null));
        assertEquals(LifeState.teen_pro_YT_MAX_MEMORIES, state.memories().size());
        assertTrue(state.memories().stream().noneMatch(m -> m.thoughtId().equals("colonyloom:chitchat")), "the cap drops the closest to expiry");
        state.advanceMemories(1500);
        assertEquals(LifeState.teen_pro_YT_MAX_MEMORIES, state.memories().size());
        state.advanceMemories(600);
        assertTrue(state.memories().isEmpty());
    }

    @Test void acquaintancesForgetStrangersButNeverFamily() {
        LifeState state = state(10, Sex.MALE);
        state.bond(id(100), BondKind.PARENT);
        state.meet(id(100), 5);
        for (int i = 0; i < LifeState.teen_pro_YT_MAX_ACQUAINTANCES + 5; i++) {
            Acquaintance a = state.meet(id(200 + i), 0);
            assertNotNull(a);
            a.advance(1000 - i);
        }
        assertEquals(LifeState.teen_pro_YT_MAX_ACQUAINTANCES, state.acquaintances().size());
        assertNotNull(state.acquaintance(id(100)), "a parent is never forgotten");
        assertNull(state.acquaintance(id(200)), "the longest-unseen stranger goes first");
        assertThrows(IllegalArgumentException.class, () -> state.meet(id(10), 0));
    }

    @Test void bondsKeepOnePartnerAndSeparateFamilySlots() {
        LifeState state = state(10, Sex.FEMALE);
        assertTrue(state.bond(id(2), BondKind.LOVER));
        assertThrows(IllegalStateException.class, () -> state.bond(id(3), BondKind.LOVER));
        assertTrue(state.bond(id(2), BondKind.SPOUSE));
        assertEquals(BondKind.SPOUSE, state.partner().kind());
        assertTrue(state.bond(id(4), BondKind.SIBLING));
        assertTrue(state.related(id(4)));
        assertFalse(state.related(id(2)));
        state.bond(id(2), BondKind.LATE_PARTNER);
        assertNull(state.partner(), "a late partner frees the romance slot");
        assertTrue(state.bond(id(3), BondKind.LOVER));
        for (int i = 0; i < 20; i++) state.bond(id(50 + i), i < 12 ? BondKind.EX : BondKind.SIBLING);
        assertTrue(state.bonds().size() <= LifeState.teen_pro_YT_MAX_BONDS);
    }

    @Test void tolerancesImmunitiesAndSkillsStayBounded() {
        LifeState state = state(10, Sex.FEMALE);
        for (int i = 0; i < 12; i++) state.tolerance("joy" + i, 100_000 * (i + 1) % 1_000_000 + 1);
        assertEquals(LifeState.teen_pro_YT_MAX_TOLERANCES, state.tolerance().size());
        for (int i = 0; i < 30; i++) state.addImmunity(new Immunity("colonyloom:d" + i, "colonyloom:d" + i, 0, 1000 + i));
        assertEquals(LifeState.teen_pro_YT_MAX_IMMUNITIES, state.immunities().size());
        state.addImmunity(new Immunity("colonyloom:measles", "colonyloom:measles", 0, Immunity.teen_pro_YT_PERMANENT));
        state.advanceImmunities(5000);
        assertEquals(1, state.immunities().size());
        assertTrue(state.immunities().getFirst().permanent());
        state.xp(Skill.MEDICINE, 299);
        assertEquals(1, state.level(Skill.MEDICINE));
        state.xp(Skill.MEDICINE, 300);
        assertEquals(2, state.level(Skill.MEDICINE));
        state.xp(Skill.MEDICINE, 5_000_000);
        assertEquals(20, state.level(Skill.MEDICINE));
        assertEquals(LifeStage.ADULT, state.stage(24_000));
        assertEquals(30, state.years(24_000));
    }

    @Test void codecRoundTripsAFullLife() {
        LifeContent content = LifeFixtures.content();
        var plague = content.disease("colonyloom:plague/v02");
        LifeState state = state(10, Sex.FEMALE);
        state.addTrait("colonyloom:kind");
        state.grantStage(LifeStage.CHILD);
        state.genome(Map.of("colonyloom:immunity", new AllelePair("weak", "strong"), "colonyloom:hemophilia_gene", new AllelePair("normal", "normal")));
        state.addCondition(new Condition(id(77), plague, 400_000, 120_000, 1_100_000, 5000, 0, 600_000, 3000, id(11), true, false));
        state.addImmunity(new Immunity("colonyloom:common_cold/v01", "colonyloom:common_cold", 500_000, 24_000));
        state.rest(123_456); state.recreation(1); state.social(999_999); state.hygiene(0);
        state.tolerance("meditative", 250_000);
        state.remember(new Memory("colonyloom:chitchat", 2, 3, 5, 500_000, 12_000, 6_000, id(11)));
        state.remember(new Memory("colonyloom:got_sick", -8, 0, 3, 500_000, 36_000, 36_000, null));
        state.mood(37);
        state.mental(new Mental("colonyloom:sad_wander", ActivityDefinition.MentalLevel.MINOR, 1200));
        state.inspiration("social", 4000);
        state.breakCooldown(9000); state.socialCooldown(300);
        state.meet(id(11), 7).interacted(250);
        state.bond(id(11), BondKind.SPOUSE);
        state.bond(id(12), BondKind.CHILD);
        state.xp(Skill.SOCIAL, 4321);
        state.pregnancy(new Pregnancy(id(11), id(500), Sex.MALE, Map.of("colonyloom:immunity", new AllelePair("weak", "weak")), 24_000, 8_000));
        state.activity(new Activity("colonyloom:stargaze", new colonyloom.core.colony.WorldPosition("minecraft:overworld", 3, 70, -9), id(11), 300, 40, true));
        state.body(id(10));

        var tree = LifeCodec.encodeCitizen(state);
        @SuppressWarnings("unchecked") Map<String, Object> reparsed = (Map<String, Object>) Json.parse(Json.write(tree));
        LifeState copy = LifeCodec.citizen(colonyloom.core.life.json.Obj.of(reparsed, "t"), Map.of(plague.digest(), plague));
        assertEquals(Json.write(tree), Json.write(LifeCodec.encodeCitizen(copy)), "encode(decode(x)) is stable");
        assertEquals(plague, copy.conditions().getFirst().disease());
        assertEquals(2, copy.pregnancy().trimester());
        assertEquals(BondKind.SPOUSE, copy.partner().kind());
        assertEquals(250, copy.acquaintance(id(11)).rapport());
        assertEquals(LifeStage.CHILD, LifeStage.values()[Integer.numberOfTrailingZeros(copy.grantedStages())]);
        assertEquals(new colonyloom.core.colony.WorldPosition("minecraft:overworld", 3, 70, -9), copy.activity().target());
        assertEquals(id(10), copy.body());
    }

    @Test void codecRejectsCorruptLives() {
        LifeState state = state(10, Sex.FEMALE);
        var good = LifeCodec.encodeCitizen(state);
        assertDoesNotThrow(() -> LifeCodec.citizen(colonyloom.core.life.json.Obj.of(good, "t"), Map.of()));
        for (var mutation : List.<java.util.function.Consumer<Map<String, Object>>>of(
                t -> t.put("mood", 101L),
                t -> t.put("sex", "other"),
                t -> t.put("extra", 1L),
                t -> t.put("ageTicks", -1L),
                t -> t.put("given", "A|B"),
                t -> t.put("traits", List.of("colonyloom:a", "colonyloom:a")),
                t -> t.put("conditions", List.of(Map.ofEntries(Map.entry("id", id(1).toString()), Map.entry("pin", "missing"), Map.entry("severity", 0L),
                        Map.entry("immunity", 0L), Map.entry("variance", 1_000_000L), Map.entry("age", 0L), Map.entry("incubation", 0L),
                        Map.entry("tendQuality", 0L), Map.entry("tendRemaining", 0L), Map.entry("announced", false), Map.entry("peaked", false)))),
                t -> t.put("bonds", List.of(Map.of("other", id(2).toString(), "kind", "lover"), Map.of("other", id(2).toString(), "kind", "spouse"))),
                t -> t.put("needs", Map.of("rest", 2_000_000L, "recreation", 0L, "social", 0L, "hygiene", 0L)),
                t -> t.put("schemaVersion", 2L))) {
            LinkedHashMap<String, Object> tree = new LinkedHashMap<>(good);
            mutation.accept(tree);
            assertThrows(RuntimeException.class, () -> {
                if (!LifeCodec.supported(tree)) throw new IllegalArgumentException("unsupported");
                LifeCodec.citizen(colonyloom.core.life.json.Obj.of(tree, "t"), Map.of());
            }, tree.toString());
        }
    }

    @Test void journalIsARingBufferWithIncreasingSequence() {
        Journal journal = new Journal(id(1));
        for (int i = 0; i < Journal.teen_pro_YT_CAPACITY + 10; i++) journal.add(i, Journal.Category.EVENT, "@colonyloom.journal.test|" + i, null);
        assertEquals(Journal.teen_pro_YT_CAPACITY, journal.size());
        assertEquals(Journal.teen_pro_YT_CAPACITY + 10, journal.sequence());
        assertEquals(List.of((long) Journal.teen_pro_YT_CAPACITY + 10, (long) Journal.teen_pro_YT_CAPACITY + 9), journal.latest(2, null).stream().map(Journal.Entry::sequence).toList());
        assertTrue(journal.latest(5, Journal.Category.HEALTH).isEmpty());
        Journal copy = LifeCodec.journal(colonyloom.core.life.json.Obj.of(LifeCodec.encodeJournal(journal), "j"));
        assertEquals(journal.entries(), copy.entries());
        assertEquals(journal.sequence(), copy.sequence());
        assertThrows(IllegalArgumentException.class, () -> journal.add(0, Journal.Category.EVENT, "@bad key", null));
    }

    @Test void richTextNestsEscapesAndStaysBounded() {
        String disease = "@colonyloom.strain.format|#colonyloom.disease.flu|#colonyloom.strain.north_wind|K-12";
        String spec = RichText.of("colonyloom.journal.sick", RichText.literal("Ив|ан{}"), RichText.nested(disease));
        assertEquals("@colonyloom.journal.sick|Иван|{" + disease + "}", spec);
        assertTrue(RichText.valid(spec));
        assertEquals(List.of("@colonyloom.journal.sick", "Иван", "{" + disease + "}"), RichText.split(spec));
        assertEquals("x", RichText.literal("@#x"));
        assertFalse(RichText.valid("@k|{unbalanced"));
        assertFalse(RichText.valid("@k||x"));
        assertTrue(RichText.valid("plain text"));
        String huge = RichText.of("colonyloom.journal.sick", RichText.literal("Я".repeat(60)), RichText.nested(disease), RichText.nested(disease));
        assertTrue(RichText.bytes(huge) <= RichText.teen_pro_YT_MAX_BYTES, huge);
        assertTrue(RichText.valid(huge));
    }
}
