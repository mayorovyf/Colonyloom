package colonyloom.gameplay.life;

import colonyloom.core.life.content.ActivityDefinition;
import colonyloom.core.life.content.EnvironmentFlag;
import colonyloom.core.life.content.LifeIds;
import colonyloom.core.life.content.Sex;
import colonyloom.core.life.content.Situation;
import colonyloom.core.life.state.Journal;
import colonyloom.core.life.state.Records.BondKind;
import colonyloom.core.life.state.Records.Memory;
import colonyloom.core.life.state.Records.Mental;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static colonyloom.gameplay.life.LifeTestKit.teen_pro_YT_CONTENT;
import static org.junit.jupiter.api.Assertions.*;

final class MindAndSocialTest {
    @Test void needsFallAwakeAndRecoverAsleepWithJoyTolerance() {
        var kit = new LifeTestKit(1);
        var mind = new MindEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident anna = kit.add(10, Sex.FEMALE, 30, 5, 5);
        var state = anna.life();
        int rest = state.rest(), recreation = state.recreation(), social = state.social();
        mind.needs(state, 12_000, null);
        assertTrue(state.rest() < rest && state.recreation() < recreation && state.social() < social);
        int tired = state.rest();
        mind.needs(state, 8_000, teen_pro_YT_CONTENT.activity("colonyloom:sleep"));
        assertTrue(state.rest() > tired, "a night's sleep restores rest");
        state.recreation(200_000);
        ActivityDefinition stargaze = teen_pro_YT_CONTENT.activity("colonyloom:stargaze");
        mind.needs(state, 2_400, stargaze);
        int first = state.recreation() - 200_000;
        assertTrue(state.tolerance("meditative") > 0);
        state.recreation(200_000);
        mind.needs(state, 2_400, stargaze);
        int second = state.recreation() - 200_000;
        assertTrue(second < first, "the same joy grows stale: " + first + " then " + second);
        mind.needs(state, 24_000 * 3, null);
        assertEquals(0, state.tolerance("meditative"), "tolerance fades with time");
    }

    @Test void situationsAndMemoriesShapeAGraduallyMovingMood() {
        var kit = new LifeTestKit(2);
        var mind = new MindEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident anna = kit.add(10, Sex.FEMALE, 30, 5, 5);
        kit.food(10, 0);
        anna = kit.resident(10);
        anna.life().rest(50_000);
        Set<Situation> situations = mind.situations(anna, EnumSet.of(EnvironmentFlag.OUTDOORS, EnvironmentFlag.RAIN));
        assertTrue(situations.containsAll(Set.of(Situation.STARVING, Situation.REST_EXHAUSTED, Situation.RAIN_OUTDOORS, Situation.NO_FRIENDS)));
        int hungry = mind.targetMood(anna, Set.of());
        assertTrue(hungry <= 30, "starving costs 20 mood: " + hungry);
        anna.life().remember(new Memory("colonyloom:new_lover", 30, 0, 1, 500_000, 240_000, 240_000, null));
        assertEquals(hungry + 30, mind.targetMood(anna, Set.of()));
        assertTrue(mind.breakdown(anna, Set.of()).stream().anyMatch(p -> p.label().equals("@colonyloom.mood.situation|#colonyloom.thought.starving") && p.value() == -20));
        int before = anna.life().mood();
        mind.update(anna, 200, Set.of(), null);
        assertTrue(Math.abs(anna.life().mood() - before) <= 1, "mood drifts, it does not jump");
        for (int i = 0; i < 60; i++) mind.update(kit.resident(10), 600, Set.of(), null);
        assertEquals(mind.targetMood(kit.resident(10), Set.of()), anna.life().mood(), 2);
    }

    @Test void lowMoodBreaksAndCatharsisFollows() {
        var kit = new LifeTestKit(3);
        var mind = new MindEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident adam = kit.add(10, Sex.MALE, 30, 5, 5);
        adam.life().remember(new Memory("colonyloom:friend_died", -60, 0, 1, 500_000, 240_000, 240_000, null));
        adam.life().mood(10);
        for (int i = 0; i < 200 && adam.life().mental() == null; i++) mind.update(adam, 600, Set.of(), null);
        Mental mental = adam.life().mental();
        assertNotNull(mental, "a desperate resident breaks down");
        assertEquals(1, kit.teen_pro_YT_life.journal(LifeTestKit.teen_pro_YT_COLONY).latest(5, Journal.Category.MOOD).size());
        adam.life().advanceTimers(mental.remaining());
        mind.update(adam, 100, Set.of(), null);
        assertNull(adam.life().mental());
        assertTrue(adam.life().breakCooldown() > 0);
        assertTrue(adam.life().memories().stream().anyMatch(m -> m.thoughtId().equals(LifeIds.teen_pro_YT_CATHARSIS)));
        assertEquals(MindEngine.teen_pro_YT_MINOR_BREAK, mind.threshold(kit.add(11, Sex.MALE, 30, 5, 5).life(), ActivityDefinition.MentalLevel.MINOR));
    }

    @Test void conversationsBuildFriendshipAndFillTheSocialNeed() {
        var kit = new LifeTestKit(4);
        var health = new HealthEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        var social = new SocialEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, health, kit.teen_pro_YT_random);
        Resident a = kit.add(10, Sex.FEMALE, 30, 5, 5), b = kit.add(11, Sex.FEMALE, 32, 6, 5), far = kit.add(12, Sex.MALE, 30, 50, 50);
        a.life().social(100_000);
        var chat = teen_pro_YT_CONTENT.interactions().get("colonyloom:chat");
        for (int i = 0; i < 60; i++) social.interact(a, b, chat);
        assertTrue(a.life().social() > 100_000);
        assertNotNull(a.life().acquaintance(b.life().citizenId()));
        assertTrue(Relations.opinion(a.life(), b.life().citizenId()) > Relations.opinion(a.life(), far.life().citizenId()));
        assertTrue(a.life().acquaintance(b.life().citizenId()).rapport() > 0);
        assertTrue(kit.teen_pro_YT_port.teen_pro_YT_spoken.stream().anyMatch(s -> s.startsWith("@colonyloom.speech.chat.")));
        a.life().socialCooldown(0);
        assertNull(social.tryInteract(kit.resident(10), List.of(kit.resident(10), kit.resident(12))), "nobody within talking range");
        a.life().socialCooldown(0);
        assertNotNull(social.tryInteract(kit.resident(10), List.of(kit.resident(10), kit.resident(11), kit.resident(12))));
        assertTrue(a.life().socialCooldown() > 0);
        int rapport = a.life().acquaintance(b.life().citizenId()).rapport();
        social.fade(a.life(), 24_000 * 3);
        assertTrue(a.life().acquaintance(b.life().citizenId()).rapport() < rapport, "friendships need contact");
    }

    @Test void courtshipEngagementWeddingAndBreakUp() {
        var kit = new LifeTestKit(5);
        var health = new HealthEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        var social = new SocialEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, health, kit.teen_pro_YT_random);
        Resident ivan = kit.add(10, Sex.MALE, 28, 5, 5), maria = kit.add(11, Sex.FEMALE, 26, 6, 5);
        ivan.life().names("Ivan", "Ivanov"); maria.life().names("Maria", "Smith");
        ivan.life().addTrait("colonyloom:straight"); maria.life().addTrait("colonyloom:straight");
        ivan.life().meet(maria.life().citizenId(), 20).interacted(5_000);
        maria.life().meet(ivan.life().citizenId(), 20).interacted(5_000);
        var romance = teen_pro_YT_CONTENT.interactions().get("colonyloom:romance");
        assertTrue(social.weight(romance, ivan, maria) > 0);
        for (int i = 0; i < 50 && ivan.life().partner() == null; i++) social.interact(ivan, maria, romance);
        assertEquals(BondKind.LOVER, ivan.life().partner().kind());
        assertEquals(BondKind.LOVER, maria.life().partner().kind());
        assertEquals(0.0, social.weight(romance, ivan, maria), "lovers no longer court as singles");
        for (int i = 0; i < 50 && ivan.life().partner().kind() == BondKind.LOVER; i++) social.interact(ivan, maria, teen_pro_YT_CONTENT.interactions().get("colonyloom:proposal"));
        assertEquals(BondKind.FIANCE, maria.life().partner().kind());
        social.interact(maria, ivan, teen_pro_YT_CONTENT.interactions().get("colonyloom:vows"));
        assertEquals(BondKind.SPOUSE, ivan.life().partner().kind());
        assertEquals("Ivanova", maria.life().familyName(), "the wife takes the husband's family name");
        assertTrue(kit.teen_pro_YT_life.journal(LifeTestKit.teen_pro_YT_COLONY).latest(10, Journal.Category.FAMILY).size() >= 3);
        social.interact(ivan, maria, teen_pro_YT_CONTENT.interactions().get("colonyloom:breakup"));
        assertNull(ivan.life().partner());
        assertEquals(BondKind.EX, maria.life().romance(ivan.life().citizenId()).kind());
        assertTrue(maria.life().memories().stream().anyMatch(m -> m.thoughtId().equals(LifeIds.teen_pro_YT_GOT_DUMPED)));
    }

    @Test void orientationAgeAndKinshipGateRomance() {
        var kit = new LifeTestKit(6);
        Resident adam = kit.add(10, Sex.MALE, 30, 5, 5), ben = kit.add(11, Sex.MALE, 31, 5, 5), eva = kit.add(12, Sex.FEMALE, 30, 5, 5);
        Resident teen = kit.add(13, Sex.FEMALE, 15, 5, 5), elder = kit.add(14, Sex.FEMALE, 70, 5, 5), sister = kit.add(15, Sex.FEMALE, 29, 5, 5);
        assertEquals(0.0, Relations.attraction(teen_pro_YT_CONTENT, adam.life(), ben.life(), 24_000), "straight by default");
        assertTrue(Relations.attraction(teen_pro_YT_CONTENT, adam.life(), eva.life(), 24_000) > 0);
        assertEquals(0.0, Relations.attraction(teen_pro_YT_CONTENT, adam.life(), teen.life(), 24_000));
        assertEquals(0.0, Relations.attraction(teen_pro_YT_CONTENT, adam.life(), elder.life(), 24_000));
        adam.life().bond(sister.life().citizenId(), BondKind.SIBLING);
        assertEquals(0.0, Relations.attraction(teen_pro_YT_CONTENT, adam.life(), sister.life(), 24_000));
        adam.life().addTrait("colonyloom:gay");
        kit.rebuild(adam);
        assertTrue(Relations.attraction(teen_pro_YT_CONTENT, adam.life(), ben.life(), 24_000) > 0);
        assertEquals(0.0, Relations.attraction(teen_pro_YT_CONTENT, adam.life(), eva.life(), 24_000));
        assertFalse(Relations.couldConceive(adam.life(), ben.life(), 24_000));
        assertTrue(Relations.couldConceive(ben.life(), eva.life(), 24_000));
        assertFalse(Relations.couldConceive(ben.life(), elder.life(), 24_000));
    }

    @Test void couplesConceiveWithAReservedIdentityForTheChild() {
        var kit = new LifeTestKit(7);
        var health = new HealthEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        var social = new SocialEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, health, kit.teen_pro_YT_random);
        Resident husband = kit.add(10, Sex.MALE, 30, 5, 5), wife = kit.add(11, Sex.FEMALE, 28, 5, 5);
        husband.life().genome(Genetics.founder(teen_pro_YT_CONTENT, Sex.MALE, kit.teen_pro_YT_random));
        wife.life().genome(Genetics.founder(teen_pro_YT_CONTENT, Sex.FEMALE, kit.teen_pro_YT_random));
        assertFalse(social.tryConceive(husband, wife, 8_000), "strangers do not conceive");
        husband.life().bond(wife.life().citizenId(), BondKind.SPOUSE); wife.life().bond(husband.life().citizenId(), BondKind.SPOUSE);
        boolean conceived = false;
        for (int night = 0; night < 400 && !conceived; night++) conceived = social.tryConceive(husband, wife, 8_000);
        assertTrue(conceived);
        var pregnancy = wife.life().pregnancy();
        assertEquals(husband.life().citizenId(), pregnancy.father());
        assertEquals(teen_pro_YT_CONTENT.genes().size(), pregnancy.childGenome().size());
        assertTrue(kit.teen_pro_YT_life.knows(pregnancy.childId()), "the unborn child's identity is reserved");
        assertNull(kit.teen_pro_YT_life.state(pregnancy.childId()));
        assertTrue(wife.life().memories().stream().anyMatch(m -> m.thoughtId().equals(LifeIds.teen_pro_YT_CONCEIVED)));
        assertFalse(social.tryConceive(husband, wife, 8_000), "one pregnancy at a time");
    }

    @Test void breakdownsCanTurnIntoInsultingSprees() {
        var kit = new LifeTestKit(8);
        var health = new HealthEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        var social = new SocialEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, health, kit.teen_pro_YT_random);
        Resident angry = kit.add(10, Sex.MALE, 30, 5, 5), victim = kit.add(11, Sex.FEMALE, 30, 6, 5);
        angry.life().mental(new Mental("colonyloom:insulting_spree", ActivityDefinition.MentalLevel.MAJOR, 1000));
        assertTrue(social.canSocialize(angry));
        for (int i = 0; i < 5; i++) assertEquals("colonyloom:insult", social.interact(angry, victim, null).id());
        assertTrue(Relations.opinion(victim.life(), angry.life().citizenId()) < 0, "the victim takes it personally");
        angry.life().mental(new Mental("colonyloom:sad_wander", ActivityDefinition.MentalLevel.MINOR, 1000));
        assertFalse(social.canSocialize(angry), "a sad wanderer does not chat");
    }
}
