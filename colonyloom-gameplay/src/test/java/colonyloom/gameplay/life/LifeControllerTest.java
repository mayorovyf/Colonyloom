package colonyloom.gameplay.life;

import colonyloom.core.life.content.LifeIds;
import colonyloom.core.life.content.Sex;
import colonyloom.core.life.content.Skill;
import colonyloom.core.life.state.Journal;
import colonyloom.core.life.state.LifeState;
import colonyloom.core.life.state.Records.BondKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static colonyloom.gameplay.life.LifeTestKit.teen_pro_YT_CONTENT;
import static colonyloom.gameplay.life.LifeTestKit.id;
import static org.junit.jupiter.api.Assertions.*;

final class LifeControllerTest {
    static final class World {
        final LifeTestKit teen_pro_YT_kit;
        final LifeController teen_pro_YT_controller;
        final List<String> teen_pro_YT_activitiesSeen = new ArrayList<>();
        final List<UUID> teen_pro_YT_unfounded = new ArrayList<>();
        long teen_pro_YT_tick;
        World(long seed) {
            teen_pro_YT_kit = new LifeTestKit(seed);
            teen_pro_YT_controller = new LifeController(teen_pro_YT_kit.teen_pro_YT_life, teen_pro_YT_kit.teen_pro_YT_port, teen_pro_YT_kit.teen_pro_YT_random);
        }
        Body citizen(long value, String profession) {
            Body body = LifeTestKit.body(value, 10 + (int) (value % 4), 10, profession);
            teen_pro_YT_kit.body(value, body);
            teen_pro_YT_unfounded.add(id(value));
            return body;
        }
        void run(long ticks, int dayTime) {
            teen_pro_YT_kit.teen_pro_YT_port.teen_pro_YT_dayTime = dayTime;
            for (long i = 0; i < ticks; i++) {
                teen_pro_YT_tick++;
                for (UUID id : List.copyOf(teen_pro_YT_unfounded)) {
                    teen_pro_YT_controller.found(teen_pro_YT_kit.teen_pro_YT_port.teen_pro_YT_bodies.get(id));
                    teen_pro_YT_unfounded.remove(id);
                }
                for (Body body : List.copyOf(teen_pro_YT_kit.teen_pro_YT_port.teen_pro_YT_bodies.values())) {
                    if (!body.alive()) continue;
                    Body next = new Body(body.lifeId(), body.colonyId(), true, body.present(), body.busy(), body.position(), body.activeTicks() + 1, body.professionId(), body.food());
                    LifeState state = teen_pro_YT_kit.teen_pro_YT_life.state(body.lifeId());
                    if (state != null && state.activity() != null && !state.activity().performing() && !body.busy()) {
                        teen_pro_YT_activitiesSeen.add(state.activity().activityId());
                        next = next.at(state.activity().target());
                        teen_pro_YT_kit.teen_pro_YT_port.teen_pro_YT_bodies.put(body.lifeId(), next);
                        teen_pro_YT_controller.arrived(body.lifeId());
                    }
                    teen_pro_YT_kit.teen_pro_YT_port.teen_pro_YT_bodies.put(body.lifeId(), next);
                }
                teen_pro_YT_controller.tick(teen_pro_YT_tick);
            }
        }
        void kill(long value) {
            Body b = teen_pro_YT_kit.body(value);
            teen_pro_YT_kit.body(value, new Body(b.lifeId(), b.colonyId(), false, false, false, b.position(), b.activeTicks(), b.professionId(), b.food()));
        }
    }

    static int at(int hour) { return GrowthAndBrainTest.at(hour); }

    @Test void foundersAreDerivedFromTheCitizenIdentity() {
        var a = new World(1);
        var b = new World(2);
        var first = Founder.create(teen_pro_YT_CONTENT, a.teen_pro_YT_kit.teen_pro_YT_life.settings(), a.citizen(10, null));
        var second = Founder.create(teen_pro_YT_CONTENT, b.teen_pro_YT_kit.teen_pro_YT_life.settings(), b.citizen(10, null));
        assertEquals(first.fullName(), second.fullName());
        assertEquals(first.sex(), second.sex());
        assertEquals(first.ageTicks(), second.ageTicks());
        assertEquals(first.traits(), second.traits());
        assertEquals(first.genome(), second.genome());
        assertTrue(first.years(24_000) >= 18 && first.years(24_000) <= 55);
        assertNotNull(Relations.orientation(teen_pro_YT_CONTENT, first));
        var doctor = Founder.create(teen_pro_YT_CONTENT, a.teen_pro_YT_kit.teen_pro_YT_life.settings(), a.citizen(11, LifeIds.teen_pro_YT_DOCTOR));
        assertTrue(doctor.level(Skill.MEDICINE) >= 4);
    }

    @Test void foundingBindsTheLifeToTheCitizenOnce() {
        var world = new World(9);
        world.citizen(10, null);
        world.run(1, at(9));
        LifeState state = world.teen_pro_YT_kit.teen_pro_YT_life.byBody(id(10));
        assertNotNull(state);
        assertEquals(id(10), state.body());
        assertFalse(state.child());
        assertNull(world.teen_pro_YT_controller.found(world.teen_pro_YT_kit.body(10)), "an existing life is never founded twice");
        assertEquals(1, world.teen_pro_YT_kit.teen_pro_YT_life.size());
    }

    @Test void residentsSleepAtNightAndTalkInTheEvening() {
        var world = new World(3);
        for (int i = 0; i < 6; i++) world.citizen(20 + i, null);
        world.run(60, at(23));
        assertEquals(6, world.teen_pro_YT_kit.teen_pro_YT_life.size(), "everyone got a life");
        world.run(400, at(23));
        assertTrue(world.teen_pro_YT_activitiesSeen.contains("colonyloom:sleep"), "they go to sleep");
        world.run(2_400, at(19));
        int talked = 0;
        for (LifeState state : world.teen_pro_YT_kit.teen_pro_YT_life.states())
            if (state.memories().stream().anyMatch(m -> m.thoughtId().equals("colonyloom:chitchat"))) talked++;
        assertTrue(talked >= 2, "neighbours chat in the evening: " + talked);
        assertTrue(world.teen_pro_YT_activitiesSeen.stream().anyMatch(id -> !id.equals("colonyloom:sleep")), "and find something to do");
        for (LifeState state : world.teen_pro_YT_kit.teen_pro_YT_life.states()) assertTrue(state.mood() > 0 && state.clock() > 0);
    }

    @Test void theColonysOwnWorkAlwaysComesFirst() {
        var world = new World(7);
        for (int i = 0; i < 3; i++) world.citizen(60 + i, null);
        world.run(400, at(23));
        for (int i = 0; i < 3; i++) world.teen_pro_YT_kit.busy(60 + i, true);
        world.run(200, at(23));
        for (LifeState state : world.teen_pro_YT_kit.teen_pro_YT_life.states()) assertNull(state.activity(), "a working resident has no pastime");
        world.teen_pro_YT_activitiesSeen.clear();
        world.run(400, at(23));
        assertTrue(world.teen_pro_YT_activitiesSeen.isEmpty(), "and starts none while busy");
        for (int i = 0; i < 3; i++) world.teen_pro_YT_kit.busy(60 + i, false);
        world.run(400, at(23));
        assertFalse(world.teen_pro_YT_activitiesSeen.isEmpty(), "free again, they pick their own lives back up");
    }

    @Test void deathIsMournedRecordedAndCleared() {
        var world = new World(4);
        world.citizen(30, null); world.citizen(31, null); world.citizen(32, null);
        world.run(60, at(19));
        var life = world.teen_pro_YT_kit.teen_pro_YT_life;
        LifeState dead = life.state(id(30)), widow = life.state(id(31)), colleague = life.state(id(32));
        if (dead.sex() == widow.sex()) widow.addTrait("colonyloom:gay");
        dead.bond(widow.citizenId(), BondKind.SPOUSE); widow.bond(dead.citizenId(), BondKind.SPOUSE);
        life.infect(dead, teen_pro_YT_CONTENT.disease("colonyloom:plague"), 0, 2 * LifeRules.teen_pro_YT_UNIT, null).severity(999_000);
        world.run(200, at(19));
        assertEquals(List.of(id(30)), world.teen_pro_YT_kit.teen_pro_YT_port.teen_pro_YT_killed, "the plague kills through the platform");
        world.kill(30);
        world.run(5, at(19));
        assertTrue(dead.deceased());
        assertTrue(dead.conditions().isEmpty());
        assertTrue(widow.memories().stream().anyMatch(m -> m.thoughtId().equals(LifeIds.teen_pro_YT_PARTNER_DIED)));
        assertEquals(BondKind.LATE_PARTNER, widow.romance(id(30)).kind());
        assertNull(widow.partner(), "a widow may love again");
        assertTrue(colleague.memories().stream().anyMatch(m -> Set.of(LifeIds.teen_pro_YT_COLONIST_DIED, LifeIds.teen_pro_YT_FRIEND_DIED, LifeIds.teen_pro_YT_RIVAL_DIED).contains(m.thoughtId())));
        var news = life.journal(LifeTestKit.teen_pro_YT_COLONY).latest(1, Journal.Category.DEATH);
        assertEquals(1, news.size());
        assertTrue(news.getFirst().text().startsWith("@colonyloom.journal.died_of|"), news.getFirst().text());
        world.run(5, at(19));
        assertEquals(1, life.journal(LifeTestKit.teen_pro_YT_COLONY).latest(10, Journal.Category.DEATH).size(), "mourned once");
        world.teen_pro_YT_controller.died(id(30), null);
        assertEquals(1, life.journal(LifeTestKit.teen_pro_YT_COLONY).latest(10, Journal.Category.DEATH).size(), "a second report changes nothing");
    }

    @Test void idleHealersComeToPatients() {
        var world = new World(5);
        world.citizen(40, null); world.citizen(41, null);
        world.run(60, at(9));
        var life = world.teen_pro_YT_kit.teen_pro_YT_life;
        var healer = life.state(id(40));
        var patient = life.state(id(41));
        healer.xp(Skill.MEDICINE, 50 * 5 * 6);
        int before = healer.xp(Skill.MEDICINE);
        var condition = life.infect(patient, teen_pro_YT_CONTENT.disease("colonyloom:plague"), 0, LifeRules.teen_pro_YT_UNIT, null);
        condition.severity(200_000);
        world.run(LifeController.teen_pro_YT_COLONY_INTERVAL * 2 + 1_200, at(9));
        assertTrue(world.teen_pro_YT_activitiesSeen.contains(LifeIds.teen_pro_YT_TEND_PATIENT), "the healer walked over");
        assertTrue(condition.tended() || patient.conditions().isEmpty(), "the patient was treated");
        assertTrue(patient.memories().stream().anyMatch(m -> m.thoughtId().equals(LifeIds.teen_pro_YT_WAS_TENDED)));
        assertTrue(healer.xp(Skill.MEDICINE) > before, "healing teaches medicine");
    }

    @Test void nothingHappensWhileLifeIsOff() {
        var world = new World(6);
        world.citizen(50, null);
        world.run(5, at(9));
        LifeState state = world.teen_pro_YT_kit.teen_pro_YT_life.state(id(50));
        world.teen_pro_YT_kit.teen_pro_YT_life.settings(world.teen_pro_YT_kit.teen_pro_YT_life.settings().withEnabled(false));
        long clock = state.clock();
        world.run(2_000, at(9));
        assertEquals(clock, state.clock(), "a disabled simulation does not age anyone");
        assertTrue(world.teen_pro_YT_activitiesSeen.isEmpty());
    }
}
