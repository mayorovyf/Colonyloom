package colonyloom.gameplay.life;

import colonyloom.core.colony.WorldPosition;
import colonyloom.core.life.content.ActivityDefinition;
import colonyloom.core.life.content.Capacity;
import colonyloom.core.life.content.LifeIds;
import colonyloom.core.life.content.LifeStage;
import colonyloom.core.life.content.Sex;
import colonyloom.core.life.state.Journal;
import colonyloom.core.life.state.Records.AllelePair;
import colonyloom.core.life.state.Records.BondKind;
import colonyloom.core.life.state.Records.Mental;
import colonyloom.core.life.state.Records.Pregnancy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static colonyloom.gameplay.life.LifeTestKit.teen_pro_YT_CONTENT;
import static org.junit.jupiter.api.Assertions.*;

final class GrowthAndBrainTest {
    static int at(int hour) { return Math.floorMod(hour - 6, 24) * 1_000; }

    @Test void childrenGrowUpGainingCharacterAndNews() {
        var kit = new LifeTestKit(1);
        var growth = new GrowthEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident kid = kit.add(10, Sex.FEMALE, 2, 5, 5);
        kid.life().ageTicks(3 * 24_000 - 100);
        assertTrue(kid.life().traits().isEmpty());
        assertTrue(growth.age(kid, 200));
        assertEquals(LifeStage.CHILD, kid.life().stage(24_000));
        assertEquals(1, kid.life().traits().size(), "a first trait at three");
        assertNull(Relations.orientation(teen_pro_YT_CONTENT, kid.life()));
        assertTrue(kid.life().memories().stream().anyMatch(m -> m.thoughtId().equals(LifeIds.teen_pro_YT_GREW_UP)));
        assertEquals(1, kit.teen_pro_YT_life.journal(LifeTestKit.teen_pro_YT_COLONY).latest(5, Journal.Category.FAMILY).size());
        kid.life().ageTicks(13 * 24_000 - 10);
        assertTrue(growth.age(kid, 20));
        assertNotNull(Relations.orientation(teen_pro_YT_CONTENT, kid.life()), "orientation arrives with adolescence");
        kid.life().ageTicks(16 * 24_000 - 10);
        growth.age(kid, 20);
        assertTrue(kid.life().stageGranted(LifeStage.ADULT));
        assertTrue(kid.life().traits().size() <= 4);
    }

    @Test void pregnancyAdvancesAndADueBirthCreatesAFamily() {
        var kit = new LifeTestKit(2);
        var controller = new LifeController(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident father = kit.add(10, Sex.MALE, 30, 5, 5), mother = kit.add(11, Sex.FEMALE, 28, 5, 5), friend = kit.add(12, Sex.MALE, 40, 6, 6);
        Resident firstborn = kit.add(13, Sex.MALE, 4, 5, 5);
        father.life().names("Ivan", "Ivanov"); mother.life().names("Maria", "Ivanova");
        mother.life().bond(firstborn.life().citizenId(), BondKind.CHILD); firstborn.life().bond(mother.life().citizenId(), BondKind.PARENT);
        UUID childId = UUID.randomUUID();
        kit.teen_pro_YT_life.conceive(mother.life(), new Pregnancy(father.life().citizenId(), childId, Sex.FEMALE,
                Map.of("colonyloom:immunity", new AllelePair("weak", "strong")), 24_000, 0));
        controller.growth().pregnancy(mother, 12_000);
        assertEquals(2, mother.life().pregnancy().trimester());
        assertTrue(kit.teen_pro_YT_port.teen_pro_YT_births.isEmpty());
        controller.growth().pregnancy(mother, 12_000);
        assertEquals(List.of(mother.life().citizenId()), kit.teen_pro_YT_port.teen_pro_YT_births, "a due pregnancy asks the world for a birth");
        var plan = controller.beginBirth(mother.life().citizenId());
        assertEquals(childId, plan.childId());
        assertEquals("Ivanova", plan.familyName(), "a daughter takes the female form of the father's name");
        var baby = controller.completeBirth(plan, 0);
        assertNull(mother.life().pregnancy());
        assertEquals(LifeStage.BABY, baby.stage(24_000));
        assertEquals(BondKind.PARENT, baby.bond(father.life().citizenId()).kind());
        assertEquals(BondKind.CHILD, mother.life().bond(childId).kind());
        assertEquals(BondKind.SIBLING, baby.bond(firstborn.life().citizenId()).kind());
        assertEquals(BondKind.SIBLING, firstborn.life().bond(childId).kind());
        assertTrue(father.life().memories().stream().anyMatch(m -> m.thoughtId().equals(LifeIds.teen_pro_YT_NEW_BABY)));
        assertTrue(friend.life().memories().stream().anyMatch(m -> m.thoughtId().equals(LifeIds.teen_pro_YT_COLONY_BABY)));
        assertEquals(1, kit.teen_pro_YT_life.journal(LifeTestKit.teen_pro_YT_COLONY).latest(5, Journal.Category.BIRTH).size());
        assertEquals(Map.of("colonyloom:immunity", new AllelePair("weak", "strong")), baby.genome());
        assertTrue(baby.child(), "a newborn is the colony's own child until adulthood");
        assertSame(baby, kit.teen_pro_YT_life.state(childId));
        assertNull(kit.teen_pro_YT_life.byBody(childId));
        assertThrows(IllegalStateException.class, () -> controller.completeBirth(plan, 0), "a birth happens once");
    }

    @Test void parentsFeedBabiesAndSevereIllnessMayEndAPregnancy() {
        var kit = new LifeTestKit(3);
        var growth = new GrowthEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident mother = kit.add(10, Sex.FEMALE, 28, 5, 5), baby = kit.add(11, Sex.MALE, 1, 5, 5), stranger = kit.add(12, Sex.MALE, 30, 5, 5);
        baby.life().bond(mother.life().citizenId(), BondKind.PARENT);
        assertTrue(growth.feedBaby(kit.resident(11), List.of(kit.resident(12), kit.resident(10), kit.resident(11)), GrowthEngine.teen_pro_YT_FEED_INTERVAL));
        assertTrue(mother.life().memories().stream().anyMatch(m -> m.thoughtId().equals(LifeIds.teen_pro_YT_FED_BABY)), "the parent feeds first");
        assertFalse(growth.feedBaby(kit.resident(11), List.of(kit.resident(10)), 0), "a fed baby waits");
        kit.busy(10, true);
        assertTrue(growth.feedBaby(kit.resident(11), List.of(kit.resident(10), kit.resident(12)), GrowthEngine.teen_pro_YT_FEED_INTERVAL), "someone else feeds while the parent works");
        assertTrue(stranger.life().memories().stream().anyMatch(m -> m.thoughtId().equals(LifeIds.teen_pro_YT_FED_BABY)));
        kit.busy(10, false);
        kit.teen_pro_YT_life.conceive(mother.life(), new Pregnancy(null, UUID.randomUUID(), Sex.MALE, Map.of(), 24_000, 0));
        kit.teen_pro_YT_life.infect(mother.life(), teen_pro_YT_CONTENT.disease("colonyloom:plague"), 0, LifeRules.teen_pro_YT_UNIT, null).severity(900_000);
        for (int i = 0; i < 5_000 && mother.life().pregnancy() != null; i++) growth.pregnancy(mother, 100);
        assertNull(mother.life().pregnancy());
        assertTrue(kit.teen_pro_YT_life.journal(LifeTestKit.teen_pro_YT_COLONY).latest(5, Journal.Category.FAMILY).getFirst().text().startsWith("@colonyloom.journal.miscarriage"));
    }

    @Test void schedulesFollowAgeAndChronotype() {
        var kit = new LifeTestKit(4);
        var brain = new Brain(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident adult = kit.add(10, Sex.MALE, 30, 5, 5), child = kit.add(11, Sex.FEMALE, 8, 5, 5), baby = kit.add(12, Sex.MALE, 1, 5, 5), owl = kit.add(13, Sex.MALE, 30, 5, 5);
        owl.life().addTrait("colonyloom:night_owl");
        assertEquals(Brain.Block.WORK, brain.block(adult.life(), at(9)));
        assertEquals(Brain.Block.LEISURE, brain.block(adult.life(), at(19)));
        assertEquals(Brain.Block.SLEEP, brain.block(adult.life(), at(23)));
        assertEquals(Brain.Block.SLEEP, brain.block(adult.life(), at(5)));
        assertEquals(Brain.Block.WORK, brain.block(child.life(), at(10)));
        assertEquals(Brain.Block.SLEEP, brain.block(child.life(), at(21)));
        assertEquals(Brain.Block.LEISURE, brain.block(baby.life(), at(12)));
        assertEquals(Brain.Block.SLEEP, brain.block(owl.life(), at(7)), "a night owl sleeps in");
        assertEquals(Brain.Block.LEISURE, brain.block(owl.life(), at(23)));
        assertFalse(Brain.needsRest(adult.life()));
        kit.teen_pro_YT_life.infect(adult.life(), teen_pro_YT_CONTENT.disease("colonyloom:plague"), 0, LifeRules.teen_pro_YT_UNIT, null).severity(400_000);
        kit.rebuild(adult);
        assertTrue(Brain.needsRest(adult.life()), "serious illness means bed rest");
    }

    @Test void freeResidentsChooseRestLeisureAndIdleActivities() {
        var kit = new LifeTestKit(5);
        var brain = new Brain(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident adam = kit.add(10, Sex.MALE, 30, 5, 5), friend = kit.add(11, Sex.MALE, 30, 8, 5);
        List<Resident> colony = List.of(adam, friend);
        kit.teen_pro_YT_port.teen_pro_YT_dayTime = at(23);
        brain.update(adam, 100, colony);
        var sleep = adam.life().activity();
        assertNotNull(sleep);
        assertEquals("colonyloom:sleep", sleep.activityId(), "no bed: they lie down where they are");
        assertEquals(LifeTestKit.pos(5, 5), sleep.target());
        kit.teen_pro_YT_port.teen_pro_YT_dayTime = at(8);
        adam.life().rest(950_000);
        sleep.arrive();
        brain.update(adam, 100, colony);
        assertNotSame(sleep, adam.life().activity(), "a rested sleeper gets up in the morning");
        adam.life().idleTicks(0);
        brain.update(adam, 100, colony);
        assertNull(adam.life().activity());
        brain.update(adam, 150, colony);
        var idle = adam.life().activity();
        assertNotNull(idle);
        assertTrue(idle.remaining() <= Brain.teen_pro_YT_IDLE_ACTIVITY_MAX);
        idle.arrive();
        brain.update(adam, idle.remaining(), colony);
        assertNotSame(idle, adam.life().activity(), "a finished pastime ends");
        kit.teen_pro_YT_port.teen_pro_YT_dayTime = at(19);
        adam.life().meet(friend.life().citizenId(), 30).interacted(3_000);
        boolean social = false;
        for (int i = 0; i < 30 && !social; i++) {
            brain.cancel(adam.life());
            brain.update(adam, 100, colony);
            social = adam.life().activity() != null && adam.life().activity().partner() != null;
        }
        assertTrue(social, "friends spend evenings together");
        assertEquals(friend.life().citizenId(), adam.life().activity().partner());
    }

    @Test void needsAndStatesInterruptOrForceActivities() {
        var kit = new LifeTestKit(6);
        var brain = new Brain(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident adam = kit.add(10, Sex.MALE, 30, 5, 5);
        List<Resident> colony = List.of(adam);
        kit.teen_pro_YT_port.teen_pro_YT_dayTime = at(19);
        assertTrue(brain.start(adam, teen_pro_YT_CONTENT.activity("colonyloom:stargaze"), colony, false));
        var leisure = adam.life().activity();
        assertNotNull(leisure);
        kit.teen_pro_YT_port.teen_pro_YT_dayTime = at(9);
        brain.update(kit.resident(10), 100, colony);
        assertNotEquals(leisure, adam.life().activity(), "work hours end evening pastimes");
        brain.cancel(adam.life());
        adam.life().mental(new Mental("colonyloom:sad_wander", ActivityDefinition.MentalLevel.MINOR, 900));
        brain.update(kit.resident(10), 100, colony);
        assertEquals("colonyloom:sad_wander", adam.life().activity().activityId());
        brain.cancel(adam.life()); adam.life().mental(null);
        kit.rebuild(adam);
        adam.life().stats().addCapacity(Capacity.CONSCIOUSNESS, -1);
        brain.update(kit.resident(10), 100, colony);
        assertEquals("colonyloom:collapse", adam.life().activity().activityId());
        kit.rebuild(adam);
        assertNotNull(adam.life().activity());
        kit.busy(10, true);
        brain.update(kit.resident(10), 100, colony);
        assertNull(adam.life().activity(), "the colony's own work or meal always comes first");
        brain.update(kit.resident(10), 100, colony);
        assertNull(adam.life().activity(), "no new pastime while busy");
        kit.busy(10, false);
        adam.life().stats().addCapacity(Capacity.CONSCIOUSNESS, -1);
        brain.update(kit.resident(10), 100, colony);
        assertEquals("colonyloom:collapse", adam.life().activity().activityId(), "free again, the body gives in");
    }

    @Test void placesComeFromThePortAndStayInsideTheColony() {
        var kit = new LifeTestKit(7);
        var brain = new Brain(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident eva = kit.add(10, Sex.FEMALE, 30, 5, 5);
        WorldPosition clamped = Brain.clamp(LifeTestKit.teen_pro_YT_TERRITORY, new WorldPosition("minecraft:overworld", -50, 70, 500));
        assertEquals(new WorldPosition("minecraft:overworld", 0, 70, 63), clamped);
        var stargaze = teen_pro_YT_CONTENT.activity("colonyloom:stargaze");
        assertNotNull(brain.resolve(eva, stargaze, List.of(eva)));
        var chat = teen_pro_YT_CONTENT.activity("colonyloom:chat_with_friend");
        assertNull(brain.resolve(eva, chat, List.of(eva)), "no friend, no chat");
        kit.teen_pro_YT_port.teen_pro_YT_dayTime = at(22);
        kit.teen_pro_YT_port.teen_pro_YT_raining = true;
        for (int i = 0; i < 20; i++) {
            var choice = brain.choose(eva, Brain.Block.LEISURE, at(22), true, List.of(eva), false);
            assertNotEquals("colonyloom:stargaze", choice == null ? null : choice.id(), "nobody stargazes in the rain");
        }
    }
}
