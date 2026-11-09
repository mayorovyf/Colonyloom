package colonyloom.gameplay.life;

import colonyloom.core.colony.WorldPosition;
import colonyloom.core.life.content.ActivityDefinition;
import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.LifeContent.Kind;
import colonyloom.core.life.content.Sex;
import colonyloom.core.life.state.Condition;
import colonyloom.core.life.state.Records.BondKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static colonyloom.gameplay.life.LifeTestKit.teen_pro_YT_CONTENT;
import static colonyloom.gameplay.life.LifeTestKit.id;
import static colonyloom.gameplay.life.LifeTestKit.pos;
import static org.junit.jupiter.api.Assertions.*;

final class LivelinessTest {
    static int at(int hour) { return GrowthAndBrainTest.at(hour); }

    @Test void moodStacksByThoughtNotByPerson() {
        var kit = new LifeTestKit(21);
        Resident adam = kit.add(10, Sex.MALE, 30, 5, 5);
        for (int i = 0; i < 7; i++) Thoughts.remember(teen_pro_YT_CONTENT, adam.life(), "colonyloom:chitchat", id(100 + i));
        assertEquals(7, adam.life().memories().size(), "each conversation is remembered with its person");
        var mind = new MindEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        int chats = mind.breakdown(adam, Set.of()).stream().filter(part -> part.label().contains("chitchat")).mapToInt(MindEngine.MoodPart::value).sum();
        assertEquals(4, chats, "the five freshest chats count, each half the one before: 2 + 1 + 0.5 + 0.25 + 0.125");
        assertEquals(3, Relations.opinion(adam.life(), id(100)), "while every person keeps their own opinion bonus");
    }

    @Test void boredWorkersWithoutAJobFindPastimesAndTeenagersHaveFreeTime() {
        var kit = new LifeTestKit(22);
        var brain = new Brain(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident adam = kit.add(10, Sex.MALE, 30, 5, 5), bob = kit.add(11, Sex.MALE, 31, 6, 5), teen = kit.add(12, Sex.FEMALE, 15, 5, 6);
        adam.life().meet(bob.life().citizenId(), 10).interacted(4_000);
        teen.life().meet(bob.life().citizenId(), 10).interacted(4_000);
        assertTrue(Relations.friend(adam.life(), bob.life().citizenId()));
        List<Resident> colony = List.of(adam, bob, teen);
        Set<String> content = new HashSet<>(), bored = new HashSet<>(), teenager = new HashSet<>();
        for (int i = 0; i < 40; i++) content.add(brain.choose(adam, Brain.Block.WORK, at(9), false, colony, true).id());
        assertEquals(Set.of("colonyloom:idle"), content, "a contented worker waiting for a job only pauses");
        adam.life().recreation(100_000);
        for (int i = 0; i < 40; i++) bored.add(brain.choose(adam, Brain.Block.WORK, at(9), false, colony, true).id());
        assertTrue(bored.contains("colonyloom:chat_with_friend"), "a bored one goes to see a friend: " + bored);
        for (int i = 0; i < 40; i++) teenager.add(brain.choose(teen, Brain.Block.WORK, at(9), false, colony, false).id());
        assertTrue(teenager.contains("colonyloom:chat_with_friend"), "teenagers do not work, so working hours are their own: " + teenager);
    }

    @Test void theIllKeepToQuietPastimesAndTheContagiousAvoidCompany() {
        var social = teen_pro_YT_CONTENT.activity("colonyloom:chat_with_friend");
        var stargaze = teen_pro_YT_CONTENT.activity("colonyloom:stargaze");
        assertEquals(1.0, Brain.illness(social, false, false));
        assertEquals(0.6, Brain.illness(stargaze, true, false), 1e-9);
        assertTrue(Brain.illness(social, true, true) < 0.1, "company is the last thing a contagious resident seeks");
        assertEquals(0.1, Brain.illness(social, false, true), 1e-9, "even before feeling ill");
    }

    @Test void partnersShareTheNight() {
        var kit = new LifeTestKit(23);
        var brain = new Brain(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident adam = kit.add(10, Sex.MALE, 30, 5, 5), eva = kit.add(11, Sex.FEMALE, 29, 40, 40);
        adam.life().bond(eva.life().citizenId(), BondKind.LOVER);
        eva.life().bond(adam.life().citizenId(), BondKind.LOVER);
        WorldPosition own = pos(6, 5), hers = pos(40, 40), beside = pos(40, 41);
        kit.teen_pro_YT_port.teen_pro_YT_places.put(ActivityDefinition.Place.BED, List.of(own, hers, beside));
        List<Resident> colony = List.of(adam, eva);
        var sleep = teen_pro_YT_CONTENT.activity("colonyloom:sleep");
        assertEquals(own, brain.resolve(adam, sleep, colony).position(), "while she is awake, the nearest bed");
        assertTrue(brain.start(eva, sleep, colony, false));
        assertEquals(hers, eva.life().activity().target());
        assertEquals(beside, brain.resolve(adam, sleep, colony).position(), "then the bed next to hers");
        kit.teen_pro_YT_port.teen_pro_YT_places.put(ActivityDefinition.Place.BED, List.of(own, hers));
        assertEquals(pos(41, 40), brain.resolve(adam, sleep, colony).position(), "or the floor beside her");
    }

    @Test void outbreaksBringOnlyCommunityDiseasesThePatientCanHave() {
        List<LifeContent.Source> sources = new ArrayList<>(LifeFixtures.minimal());
        sources.add(LifeFixtures.source(Kind.DISEASES, "colonyloom:croup", "{\"schemaVersion\":1,\"category\":\"infectious\",\"severity\":\"mild\","
                + "\"contagiousness\":0.12,\"rarity\":50,\"trigger\":{\"maxAge\":8}}"));
        sources.add(LifeFixtures.source(Kind.DISEASES, "colonyloom:cave_cough", "{\"schemaVersion\":1,\"category\":\"infectious\",\"severity\":\"mild\","
                + "\"contagiousness\":0.12,\"rarity\":50,\"trigger\":{\"environment\":[\"underground\"],\"chancePerDay\":0.01}}"));
        var kit = new LifeTestKit(24);
        kit.teen_pro_YT_life.content(LifeContent.load(sources));
        Resident adam = kit.add(10, Sex.MALE, 30, 5, 5), kid = kit.add(11, Sex.FEMALE, 5, 6, 6);
        var health = new HealthEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Set<String> adult = new HashSet<>(), child = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            for (Resident patient : List.of(adam, kid)) {
                Condition condition = health.outbreak(List.of(patient), 24_000L * 2_000);
                if (condition == null) continue;
                (patient == adam ? adult : child).add(condition.disease().family());
                kit.teen_pro_YT_life.cure(patient.life(), condition);
            }
        }
        assertFalse(adult.isEmpty());
        assertFalse(adult.contains("colonyloom:croup"), "croup is a children's disease");
        assertTrue(child.contains("colonyloom:croup"));
        assertFalse(adult.contains("colonyloom:cave_cough") || child.contains("colonyloom:cave_cough"), "a cave cough needs a cave");
        assertFalse(adult.contains("colonyloom:wound_infection"), "an infection of wounds needs a wound");
    }
}
