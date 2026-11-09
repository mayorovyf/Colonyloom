package colonyloom.core.life.content;

import colonyloom.core.life.LifeFixtures;
import colonyloom.core.life.content.LifeContent.Kind;
import colonyloom.core.life.content.LifeContent.Source;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LifeContentTest {
    @Test void minimalFixtureLoadsWithVarietiesSubtypesAndIndexes() {
        LifeContent content = LifeFixtures.content();
        assertTrue(content.usable());
        assertEquals(6, content.baseCount());
        assertEquals(16, content.diseases().size());
        assertEquals(7, content.family("colonyloom:common_cold").size());
        assertNotNull(content.disease("colonyloom:common_cold/v06"));
        assertNotNull(content.disease("colonyloom:impotence/psychogenic"));
        assertEquals(List.of("colonyloom:common_cold", "colonyloom:plague"), content.infectiousBases().stream().map(DiseaseDefinition::id).toList());
        assertTrue(content.byRoute(Route.FOOD).contains(content.disease(LifeIds.teen_pro_YT_FOOD_POISONING)));
        assertTrue(content.byRoute(Route.HEREDITARY).contains(content.disease("colonyloom:hemophilia")));
        assertTrue(content.triggered().contains(content.disease(LifeIds.teen_pro_YT_WOUND_INFECTION)));
        assertTrue(content.triggered().contains(content.disease("colonyloom:impotence")));
        assertEquals("colonyloom:sick", content.situation(Situation.SICK).id());
        assertNull(content.situation(Situation.CROWDED));
        assertEquals("en", content.names("ru").culture(), "unknown culture falls back to the first pool");
        assertEquals("Ivanova", content.names("en").family(2).form(Sex.FEMALE));
        assertEquals(11, content.activities().size());
        assertEquals(64, content.digest().length());
    }

    @Test void loadIsOrderIndependentAndDigestTracksContent() {
        List<Source> sources = new ArrayList<>(LifeFixtures.minimal());
        String digest = LifeContent.load(sources).digest();
        Collections.shuffle(sources, new Random(7));
        LifeContent shuffled = LifeContent.load(sources);
        assertEquals(digest, shuffled.digest());
        assertEquals(List.copyOf(LifeFixtures.content().diseases().keySet()), List.copyOf(shuffled.diseases().keySet()));
        sources.add(LifeFixtures.source(Kind.THOUGHTS, "colonyloom:extra", "{\"schemaVersion\":1,\"mood\":1}"));
        assertNotEquals(digest, LifeContent.load(sources).digest());
        assertNotEquals(digest, LifeContent.empty().digest());
        assertFalse(LifeContent.empty().usable());
    }

    @Test void requiredDefinitionsAndReferencesAreEnforced() {
        assertRejected(without("colonyloom:got_sick"), "Missing required thought");
        assertRejected(without(LifeIds.teen_pro_YT_FOOD_POISONING), "Missing required disease");
        assertRejected(without("colonyloom:collapse"), "Missing activity purpose");
        assertRejected(without("colonyloom:en"), "Missing name pool");
        assertRejected(without("colonyloom:hemophilia"), "genetic disease");
        assertRejected(without("colonyloom:tough"), "unknown trait");
        assertRejected(without("colonyloom:abrasive"), "conflicts with unknown");
        assertRejected(without("colonyloom:chitchat"), "unknown thought");
        List<Source> duplicate = new ArrayList<>(LifeFixtures.minimal());
        duplicate.add(duplicate.getFirst());
        assertRejected(duplicate, "Duplicate");
        List<Source> twoSituations = new ArrayList<>(LifeFixtures.minimal());
        twoSituations.add(LifeFixtures.source(Kind.THOUGHTS, "colonyloom:sick_again", "{\"schemaVersion\":1,\"mood\":-2,\"situation\":\"sick\"}"));
        assertRejected(twoSituations, "Two thoughts");
        List<Source> broken = new ArrayList<>(LifeFixtures.minimal());
        broken.add(LifeFixtures.source(Kind.THOUGHTS, "colonyloom:broken", "{\"schemaVersion\":1,"));
        assertRejected(broken, "colonyloom:broken");
        List<Source> subtypeClash = new ArrayList<>(LifeFixtures.minimal());
        subtypeClash.add(LifeFixtures.source(Kind.DISEASES, "colonyloom:impotence/psychogenic", "{\"schemaVersion\":1,\"category\":\"mental\"}"));
        assertRejected(subtypeClash, "Duplicate life definition");
    }

    @Test void capsBoundEachKind() {
        List<Source> sources = new ArrayList<>(LifeFixtures.minimal());
        for (int i = 0; i < Kind.NAMES.cap(); i++) sources.add(LifeFixtures.source(Kind.NAMES, "colonyloom:n" + "abcdefghijklmnopq".charAt(i) + "x",
                "{\"schemaVersion\":1,\"male\":[\"A\",\"B\",\"C\",\"D\"],\"female\":[\"E\",\"F\",\"G\",\"H\"],\"family\":[\"I\",\"J\",\"K\",\"L\"]}"));
        assertRejected(sources, "Too many names");
    }

    @Test void definitionKindsDecodeTheirDefaults() {
        LifeContent content = LifeFixtures.content();
        var stargaze = content.activity("colonyloom:stargaze");
        assertEquals(ActivityDefinition.Purpose.LEISURE, stargaze.purpose());
        assertEquals(ActivityDefinition.Place.SKY, stargaze.place());
        assertEquals(java.util.Set.of(ActivityDefinition.Block.LEISURE), stargaze.schedule());
        assertTrue(stargaze.timeAllows(18_000));
        assertFalse(stargaze.timeAllows(6_000));
        assertEquals(2_500_000, stargaze.recreation());
        assertFalse(stargaze.allowSick());
        assertTrue(content.activity("colonyloom:bed_rest").allowSick());
        assertTrue(content.activity("colonyloom:child_play").ageAllows(5));
        assertFalse(content.activity("colonyloom:child_play").ageAllows(20));
        var romance = content.interactions().get("colonyloom:romance");
        assertEquals(InteractionDefinition.Change.BECOME_LOVERS, romance.success().change());
        assertEquals(InteractionDefinition.Relation.SINGLE, romance.requires().relation());
        assertTrue(romance.requires().notRelatives());
        assertEquals(500_000, romance.successChance());
        var wrapping = new ActivityDefinition("colonyloom:night", 1, ActivityDefinition.Purpose.LEISURE, java.util.Set.of(), 1, null, 0, 0, 0, 0, 100, 200,
                ActivityDefinition.Place.HERE, 4, 22_000, 2_000, ActivityDefinition.Weather.ANY, 0, 999, java.util.Set.of(), ActivityDefinition.Pose.STAND,
                ActivityDefinition.Animation.NONE, Emote.NONE, 0, null, null, false);
        assertTrue(wrapping.timeAllows(23_000));
        assertTrue(wrapping.timeAllows(1_000));
        assertFalse(wrapping.timeAllows(12_000));
    }

    private static List<Source> without(String id) {
        List<Source> sources = new ArrayList<>(LifeFixtures.minimal());
        assertTrue(sources.removeIf(source -> source.id().equals(id)), id);
        return sources;
    }

    private static void assertRejected(List<Source> sources, String fragment) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> LifeContent.load(sources));
        assertTrue(error.getMessage().contains(fragment), error.getMessage());
    }

    @Test void spontaneousOnsetsAreCountedPerFamilyNotPerStrain() {
        List<Source> sources = new ArrayList<>(LifeFixtures.minimal());
        sources.add(LifeFixtures.source(Kind.DISEASES, "colonyloom:marsh_fever", "{\"schemaVersion\":1,\"category\":\"infectious\",\"severity\":\"mild\","
                + "\"contagiousness\":0.1,\"trigger\":{\"environment\":[\"swamp\"],\"chancePerDay\":0.01},\"varieties\":{\"count\":12}}"));
        LifeContent content = LifeContent.load(sources);
        var groups = content.spontaneous().stream().filter(group -> group.members().getFirst().family().equals("colonyloom:marsh_fever")).toList();
        int members = groups.stream().mapToInt(group -> group.members().size()).sum();
        assertEquals(content.family("colonyloom:marsh_fever").size(), members, "every strain belongs to exactly one entry");
        assertTrue(groups.size() < members, "strains that share a trigger share one chance: " + groups.size());
        for (var group : groups) for (var member : group.members()) assertEquals(group.trigger(), member.trigger());
        assertEquals(2, content.spontaneous().stream().filter(group -> group.members().getFirst().family().equals("colonyloom:impotence")).count(),
                "a subtype with a trigger of its own is its own entry");
        assertTrue(content.spontaneous().stream().noneMatch(group -> group.members().contains(content.disease(LifeIds.teen_pro_YT_WOUND_INFECTION))),
                "injuries are events, not daily chances");
        assertEquals(content.diseases().size(), content.families().values().stream().mapToInt(List::size).sum());
    }
}
