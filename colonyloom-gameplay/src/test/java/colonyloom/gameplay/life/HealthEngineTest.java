package colonyloom.gameplay.life;

import colonyloom.core.life.content.EnvironmentFlag;
import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.LifeIds;
import colonyloom.core.life.content.Sex;
import colonyloom.core.life.state.Condition;
import colonyloom.core.life.state.Journal;
import colonyloom.core.life.state.LifeSettings;
import colonyloom.core.life.state.Records.AllelePair;
import colonyloom.core.life.state.Records.Immunity;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static colonyloom.gameplay.life.LifeTestKit.teen_pro_YT_CONTENT;
import static org.junit.jupiter.api.Assertions.*;

final class HealthEngineTest {
    private static final long teen_pro_YT_STEP = 200;

    private static String run(LifeTestKit kit, HealthEngine health, Resident resident, double days, boolean tended, boolean bedRest) {
        for (long t = 0; t < days * 24_000; t += teen_pro_YT_STEP) {
            kit.rebuild(resident);
            if (tended) for (Condition c : resident.life().conditions()) if (c.needsTending()) c.tend(LifeRules.teen_pro_YT_UNIT, c.disease().tendInterval());
            String death = health.update(resident, teen_pro_YT_STEP, bedRest);
            if (death != null) return death;
            if (resident.life().conditions().isEmpty()) return null;
        }
        return null;
    }

    @Test void coldRunsItsCourseAnnouncesAndLeavesImmunity() {
        var kit = new LifeTestKit(1);
        var health = new HealthEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident anna = kit.add(10, Sex.FEMALE, 30, 5, 5);
        var cold = teen_pro_YT_CONTENT.disease("colonyloom:common_cold");
        assertNotNull(kit.teen_pro_YT_life.infect(anna.life(), cold, 2_000, LifeRules.teen_pro_YT_UNIT, null));
        assertNull(run(kit, health, anna, 10, false, false));
        assertTrue(anna.life().conditions().isEmpty(), "a cold always ends");
        assertEquals(1, anna.life().immunities().size());
        assertEquals(0.0, anna.life().protection(cold));
        assertTrue(anna.life().protection(teen_pro_YT_CONTENT.disease("colonyloom:common_cold/v02")) > 0, "other strains are only partly blocked");
        assertTrue(anna.life().memories().stream().anyMatch(m -> m.thoughtId().equals(LifeIds.teen_pro_YT_GOT_SICK)));
        assertTrue(anna.life().memories().stream().anyMatch(m -> m.thoughtId().equals(LifeIds.teen_pro_YT_RECOVERED)));
        assertTrue(kit.teen_pro_YT_port.teen_pro_YT_spoken.stream().anyMatch(s -> s.startsWith("@colonyloom.speech.sick.")));
        assertEquals(0, kit.teen_pro_YT_life.journal(LifeTestKit.teen_pro_YT_COLONY).size(), "colds do not make the journal");
        assertEquals(0, kit.teen_pro_YT_life.pinCount());
    }

    @Test void untendedPlagueKillsWhileGoodCareAndBedRestSaveLives() {
        var kit = new LifeTestKit(2);
        var health = new HealthEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        var plague = teen_pro_YT_CONTENT.disease("colonyloom:plague");
        Resident untended = kit.add(10, Sex.MALE, 30, 5, 5);
        Resident cared = kit.add(11, Sex.FEMALE, 30, 9, 9);
        kit.teen_pro_YT_life.infect(untended.life(), plague, 0, LifeRules.teen_pro_YT_UNIT, null);
        kit.teen_pro_YT_life.infect(cared.life(), plague, 0, LifeRules.teen_pro_YT_UNIT, null);
        String cause = run(kit, health, untended, 6, false, false);
        assertEquals(plague.name().spec(), cause, "plague without a doctor is fatal");
        assertNull(run(kit, health, cared, 8, true, true));
        assertTrue(cared.life().conditions().isEmpty(), "well tended plague is survived");
        assertTrue(kit.teen_pro_YT_life.journal(LifeTestKit.teen_pro_YT_COLONY).latest(10, Journal.Category.HEALTH).size() >= 2, "sick and recovered are news");
    }

    @Test void gentleLethalityCapsEveryDisease() {
        var kit = new LifeTestKit(3);
        kit.teen_pro_YT_life.settings(LifeSettings.defaults().withLethality(LifeSettings.Lethality.GENTLE));
        var health = new HealthEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident adam = kit.add(10, Sex.MALE, 30, 5, 5);
        kit.teen_pro_YT_life.infect(adam.life(), teen_pro_YT_CONTENT.disease("colonyloom:plague"), 0, 2 * LifeRules.teen_pro_YT_UNIT, null);
        for (int i = 0; i < 24_000 * 3 / teen_pro_YT_STEP; i++) { kit.rebuild(adam); assertNull(health.update(adam, teen_pro_YT_STEP, false)); }
        if (!adam.life().conditions().isEmpty()) assertTrue(adam.life().conditions().getFirst().severity() <= LifeRules.teen_pro_YT_UNIT * 99 / 100);
    }

    @Test void chronicDiseaseWorsensUntendedAndRemitsWhileTended() {
        var kit = new LifeTestKit(4);
        var health = new HealthEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident adam = kit.add(10, Sex.MALE, 40, 5, 5);
        Condition impotence = kit.teen_pro_YT_life.infect(adam.life(), teen_pro_YT_CONTENT.disease("colonyloom:impotence"), 0, LifeRules.teen_pro_YT_UNIT, null);
        int start = impotence.severity();
        for (int i = 0; i < 24_000 * 2 / teen_pro_YT_STEP; i++) { kit.rebuild(adam); health.update(adam, teen_pro_YT_STEP, false); }
        int worse = impotence.severity();
        assertTrue(worse > start, start + " -> " + worse);
        for (int i = 0; i < 24_000 * 2 / teen_pro_YT_STEP; i++) { kit.rebuild(adam); impotence.tend(LifeRules.teen_pro_YT_UNIT, 48_000); health.update(adam, teen_pro_YT_STEP, false); }
        assertTrue(impotence.severity() < worse, "tending a chronic condition brings remission");
        assertSame(impotence, adam.life().conditions().getFirst(), "chronic conditions do not resolve by immunity");
        kit.rebuild(adam);
        assertTrue(adam.life().stats().rawCapacity(colonyloom.core.life.content.Capacity.FERTILITY) < 1.0);
    }

    @Test void contactSpreadsOnlyContagiousConditionsAndRespectsImmunity() {
        var kit = new LifeTestKit(5);
        var health = new HealthEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident carrier = kit.add(10, Sex.MALE, 30, 5, 5);
        var cold = teen_pro_YT_CONTENT.disease("colonyloom:common_cold/v01");
        Condition source = kit.teen_pro_YT_life.infect(carrier.life(), cold, 0, LifeRules.teen_pro_YT_UNIT, null);
        source.severity(600_000);
        kit.teen_pro_YT_life.infect(carrier.life(), teen_pro_YT_CONTENT.disease("colonyloom:hemophilia"), 0, LifeRules.teen_pro_YT_UNIT, null);
        int infected = 0;
        for (int i = 0; i < 60; i++) {
            Resident target = kit.add(100 + i, Sex.FEMALE, 30, 6, 6);
            for (int chat = 0; chat < 10; chat++) health.contact(carrier, target, 1.0);
            if (target.life().hasFamily(cold.family())) {
                infected++;
                assertEquals(carrier.life().citizenId(), target.life().condition(target.life().conditions().getFirst().disease().id()).source());
            }
            assertFalse(target.life().hasFamily("colonyloom:hemophilia"), "genetic disease never spreads");
        }
        assertTrue(infected >= 5 && infected <= 59, "ten chats with a sick friend sometimes pass a cold: " + infected);
        Resident immune = kit.add(500, Sex.FEMALE, 30, 6, 6);
        immune.life().addImmunity(new Immunity(cold.id(), cold.family(), LifeRules.teen_pro_YT_UNIT, 100_000));
        for (int chat = 0; chat < 200; chat++) health.contact(carrier, immune, 3.0);
        assertFalse(immune.life().hasFamily(cold.family()), "full cross immunity blocks the whole family");
    }

    @Test void airborneReachesOnlyNeighboursInsideTheRadius() {
        var kit = new LifeTestKit(6);
        var health = new HealthEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident carrier = kit.add(10, Sex.MALE, 30, 10, 10);
        var cold = teen_pro_YT_CONTENT.disease("colonyloom:common_cold");
        kit.teen_pro_YT_life.infect(carrier.life(), cold, 0, LifeRules.teen_pro_YT_UNIT, null).severity(600_000);
        List<Resident> near = new ArrayList<>(), far = new ArrayList<>();
        for (int i = 0; i < 20; i++) near.add(kit.add(100 + i, Sex.FEMALE, 30, 11, 11));
        for (int i = 0; i < 20; i++) far.add(kit.add(200 + i, Sex.FEMALE, 30, 40, 40));
        List<Resident> everyone = new ArrayList<>(near); everyone.addAll(far);
        for (int minute = 0; minute < 24 * 60 / 4; minute++) health.airborne(carrier, everyone, 1_200);
        assertTrue(near.stream().anyMatch(r -> r.life().hasFamily(cold.family())), "people sharing air catch it");
        assertTrue(far.stream().noneMatch(r -> r.life().hasFamily(cold.family())), "nobody across the colony does");
    }

    @Test void mealsAndInjuriesRollTheirEventDiseases() {
        var kit = new LifeTestKit(7);
        var health = new HealthEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        int wounds = 0, falls = 0, poisoned = 0;
        for (int i = 0; i < 150; i++) {
            Resident r = kit.add(100 + i, Sex.MALE, 30, 5, 5);
            health.injured(r, "fall");
            if (r.life().hasFamily(LifeIds.teen_pro_YT_WOUND_INFECTION)) falls++;
            health.injured(r, "attack");
            if (r.life().hasFamily(LifeIds.teen_pro_YT_WOUND_INFECTION)) wounds++;
            for (int meal = 0; meal < 10; meal++) health.ate(r);
            if (r.life().hasFamily(LifeIds.teen_pro_YT_FOOD_POISONING)) poisoned++;
            assertTrue(r.life().memories().stream().anyMatch(m -> m.thoughtId().equals(LifeIds.teen_pro_YT_ATE_MEAL)));
        }
        assertEquals(0, falls, "wound infection follows attacks only");
        assertTrue(wounds > 2 && wounds < 30, "about 8% of attacks infect: " + wounds);
        assertTrue(poisoned > 3 && poisoned < 60, "about 18% over ten meals: " + poisoned);
    }

    @Test void triggersHonourAgeSexMoodAndSurroundings() {
        var sources = new ArrayList<>(LifeFixtures.minimal());
        sources.add(LifeFixtures.source(LifeContent.Kind.DISEASES, "colonyloom:old_men_cough",
                "{\"schemaVersion\":1,\"category\":\"chronic\",\"systems\":[\"respiratory\"],\"trigger\":{\"chancePerDay\":1,\"minAge\":60,\"sex\":\"male\"}}"));
        sources.add(LifeFixtures.source(LifeContent.Kind.DISEASES, "colonyloom:swamp_fever",
                "{\"schemaVersion\":1,\"category\":\"infectious\",\"pathogen\":\"parasite\",\"routes\":[\"vector\"],\"trigger\":{\"chancePerDay\":1}}"));
        var kit = new LifeTestKit(8);
        kit.teen_pro_YT_life.content(LifeContent.load(sources));
        var health = new HealthEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident oldMan = kit.add(10, Sex.MALE, 70, 5, 5), oldWoman = kit.add(11, Sex.FEMALE, 70, 5, 5), youngMan = kit.add(12, Sex.MALE, 20, 5, 5);
        Set<EnvironmentFlag> swamp = EnumSet.of(EnvironmentFlag.SWAMP);
        for (int i = 0; i < 400; i++) for (Resident r : List.of(oldMan, oldWoman, youngMan)) health.triggers(r, i % 2 == 0 ? swamp : Set.of(), 2_000);
        assertTrue(oldMan.life().hasFamily("colonyloom:old_men_cough"));
        assertFalse(oldWoman.life().hasFamily("colonyloom:old_men_cough"));
        assertFalse(youngMan.life().hasFamily("colonyloom:old_men_cough"));
        assertTrue(youngMan.life().hasFamily("colonyloom:swamp_fever"), "vector diseases need their surroundings, which the swamp provides");
        assertFalse(HealthEngine.environmentAllows(kit.teen_pro_YT_life.content().disease("colonyloom:swamp_fever"), Set.of(EnvironmentFlag.DESERT)));
        assertTrue(HealthEngine.environmentAllows(teen_pro_YT_CONTENT.disease("colonyloom:common_cold"), Set.of()));
        assertFalse(HealthEngine.environmentAllows(teen_pro_YT_CONTENT.disease("colonyloom:plague"), Set.of()), "plague's animal route needs animals around");
    }

    @Test void heredityExpressesConditionsByGenotype() {
        var kit = new LifeTestKit(9);
        var health = new HealthEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident son = kit.add(10, Sex.MALE, 10, 5, 5), carrier = kit.add(11, Sex.FEMALE, 30, 5, 5), daughter = kit.add(12, Sex.FEMALE, 10, 5, 5);
        son.life().genome(Map.of("colonyloom:hemophilia_gene", new AllelePair("hemophilia", null)));
        carrier.life().genome(Map.of("colonyloom:hemophilia_gene", new AllelePair("normal", "hemophilia")));
        daughter.life().genome(Map.of("colonyloom:hemophilia_gene", new AllelePair("hemophilia", "hemophilia")));
        for (Resident r : List.of(son, carrier, daughter)) health.heredity(r);
        assertTrue(son.life().hasFamily("colonyloom:hemophilia"), "an X-linked allele in a son is expressed");
        assertFalse(carrier.life().hasFamily("colonyloom:hemophilia"), "a heterozygous mother only carries it");
        assertTrue(daughter.life().hasFamily("colonyloom:hemophilia"));
        assertEquals(List.of("colonyloom:hemophilia_gene"), Genetics.carriers(teen_pro_YT_CONTENT, carrier.life()));
        health.heredity(son);
        assertEquals(1, son.life().conditions().size());
    }

    @Test void outbreaksPickContagiousStrains() {
        var kit = new LifeTestKit(10);
        var health = new HealthEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        List<Resident> residents = new ArrayList<>();
        for (int i = 0; i < 5; i++) residents.add(kit.add(10 + i, Sex.FEMALE, 30, 5, 5));
        Condition first = null;
        for (int day = 0; day < 200 && first == null; day++) first = health.outbreak(residents, 24_000);
        assertNotNull(first);
        assertTrue(first.disease().infectious());
        assertNull(health.outbreak(List.of(), 24_000 * 100));
    }

    @Test void tendingCoversSymptomaticConditionsAndTrainsTheDoctor() {
        var kit = new LifeTestKit(11);
        var health = new HealthEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident patient = kit.add(10, Sex.MALE, 30, 5, 5), doctor = kit.add(11, Sex.FEMALE, 40, 6, 6);
        Condition incubating = kit.teen_pro_YT_life.infect(patient.life(), teen_pro_YT_CONTENT.disease("colonyloom:plague"), 5_000, LifeRules.teen_pro_YT_UNIT, null);
        assertFalse(HealthEngine.needsCare(patient.life()));
        assertEquals(0, health.tend(patient, doctor.life()));
        incubating.endIncubation();
        assertTrue(HealthEngine.needsCare(patient.life()));
        assertEquals(1, health.tend(patient, doctor.life()));
        assertTrue(incubating.tended());
        assertFalse(HealthEngine.needsCare(patient.life()));
        assertTrue(doctor.life().xp(colonyloom.core.life.content.Skill.MEDICINE) > 0);
        assertTrue(patient.life().memories().stream().anyMatch(m -> m.thoughtId().equals(LifeIds.teen_pro_YT_WAS_TENDED)));
        assertEquals(1, health.cure(patient, "colonyloom:plague"));
        assertTrue(patient.life().conditions().isEmpty());
        assertEquals(0.0, patient.life().protection(teen_pro_YT_CONTENT.disease("colonyloom:plague")));
    }

    @Test void vitalFailureKillsEvenWithoutALethalCondition() {
        var kit = new LifeTestKit(12);
        var health = new HealthEngine(kit.teen_pro_YT_life, kit.teen_pro_YT_port, kit.teen_pro_YT_random);
        Resident adam = kit.add(10, Sex.MALE, 30, 5, 5);
        kit.rebuild(adam);
        adam.life().stats().addCapacity(colonyloom.core.life.content.Capacity.BREATHING, -1.5);
        String cause = health.update(adam, teen_pro_YT_STEP, false);
        assertEquals("@colonyloom.death.vital|#colonyloom.capacity.breathing", cause);
    }
}
