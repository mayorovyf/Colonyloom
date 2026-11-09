package colonyloom.core.life.state;

import colonyloom.core.colony.WorldPosition;
import colonyloom.core.life.LifeFixtures;
import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.Sex;
import colonyloom.core.life.state.Records.Activity;
import colonyloom.core.life.state.Records.AllelePair;
import colonyloom.core.life.state.Records.BondKind;
import colonyloom.core.life.state.Records.Pregnancy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LifeRegistryTest {
    static final LifeContent teen_pro_YT_CONTENT = LifeFixtures.content();
    static UUID id(long value) { return new UUID(9, value); }
    static WorldPosition pos(int x) { return new WorldPosition("minecraft:overworld", x, 64, 0); }
    static LifeRegistry registry(int citizens) {
        var registry = new LifeRegistry(Thread.currentThread());
        registry.content(teen_pro_YT_CONTENT);
        for (int i = 0; i < citizens; i++)
            registry.add(new LifeState(id(10 + i), id(1), i % 2 == 0 ? Sex.FEMALE : Sex.MALE, 24_000L * 25, 0, "Name" + i, "Family"), id(10 + i));
        return registry;
    }
    static Pregnancy pregnancy(long child) {
        return new Pregnancy(id(11), id(child), Sex.MALE, Map.of("colonyloom:immunity", new AllelePair("weak", "average")), 24_000, 0);
    }

    @Test void infectionsShareAndReleasePins() {
        var life = registry(2);
        var flu = teen_pro_YT_CONTENT.disease("colonyloom:common_cold/v03");
        Condition a = life.infect(life.state(id(10)), flu, 1000, 1_000_000, id(11));
        Condition b = life.infect(life.state(id(11)), flu, 1000, 1_000_000, null);
        assertNotNull(a); assertNotNull(b);
        assertNull(life.infect(life.state(id(10)), teen_pro_YT_CONTENT.disease("colonyloom:common_cold/v04"), 0, 1_000_000, null), "one condition per family");
        assertEquals(1, life.pinCount());
        life.cure(life.state(id(10)), a);
        assertTrue(life.pinned(flu.digest()));
        life.cure(life.state(id(11)), b);
        assertFalse(life.pinned(flu.digest()));
        assertEquals(0, life.pinCount());
        assertThrows(IllegalArgumentException.class, () -> life.infect(new LifeState(id(10), id(1), Sex.MALE, 0, 0, "X", "Y"), flu, 0, 1_000_000, null));
    }

    @Test void citizensAreBoundToOneLifeEach() {
        var life = registry(1);
        LifeState founder = life.state(id(10));
        assertSame(founder, life.byBody(id(10)));
        assertFalse(founder.child());
        assertThrows(IllegalArgumentException.class, () -> life.add(new LifeState(id(20), id(1), Sex.MALE, 0, 0, "Other", "Life"), id(10)), "one citizen, one life");
        assertNull(life.state(id(20)));
        LifeState kid = new LifeState(id(30), id(1), Sex.FEMALE, 0, 0, "Kid", "Family");
        life.add(kid);
        assertTrue(kid.child());
        assertNull(life.byBody(id(30)));
        life.bind(kid, id(31));
        assertFalse(kid.child());
        assertSame(kid, life.byBody(id(31)));
        assertSame(kid, life.state(id(30)), "the life keeps its own identity when it becomes a citizen");
        assertThrows(IllegalArgumentException.class, () -> life.bind(kid, id(10)));
        assertThrows(IllegalArgumentException.class, () -> life.add(new LifeState(id(10), id(1), Sex.MALE, 0, 0, "Same", "Key")));
    }

    @Test void pregnancyReservesTheChildsIdentity() {
        var life = registry(2);
        LifeState mother = life.state(id(10));
        life.conceive(mother, pregnancy(500));
        assertTrue(life.knows(id(500)));
        assertThrows(IllegalStateException.class, () -> life.conceive(mother, pregnancy(600)));
        assertThrows(IllegalArgumentException.class, () -> life.conceive(life.state(id(11)), pregnancy(700)), "fathers do not carry children");
        assertThrows(IllegalArgumentException.class, () -> life.add(new LifeState(id(500), id(1), Sex.MALE, 0, 0, "Too", "Early")), "the unborn identity is taken");
        life.endPregnancy(mother);
        assertFalse(life.knows(id(500)));
    }

    @Test void birthAddsTheChildAsTheColonysOwn() {
        var life = registry(2);
        LifeState mother = life.state(id(10));
        Pregnancy pregnancy = pregnancy(500);
        life.conceive(mother, pregnancy);
        LifeState baby = new LifeState(id(500), id(1), pregnancy.childSex(), 0, 0, "Baby", "Family");
        assertThrows(IllegalStateException.class, () -> life.completeBirth(mother, baby), "only a due pregnancy gives birth");
        pregnancy.advance(24_000);
        baby.genome(pregnancy.childGenome());
        life.completeBirth(mother, baby);
        assertNull(mother.pregnancy());
        assertSame(baby, life.state(id(500)));
        assertTrue(baby.child());
    }

    @Test void deathReleasesPinsPregnancyAndVolatileLife() {
        var life = registry(2);
        LifeState mother = life.state(id(10));
        mother.bond(id(11), BondKind.SPOUSE);
        mother.meet(id(11), 3);
        life.infect(mother, teen_pro_YT_CONTENT.disease("colonyloom:plague"), 0, 1_000_000, null);
        life.conceive(mother, pregnancy(500));
        life.died(id(10));
        assertTrue(mother.deceased());
        assertTrue(mother.conditions().isEmpty());
        assertNull(mother.pregnancy());
        assertFalse(life.knows(id(500)));
        assertEquals(0, life.pinCount());
        assertTrue(mother.acquaintances().isEmpty());
        assertEquals(BondKind.SPOUSE, mother.bond(id(11)).kind(), "family history survives");
    }

    @Test void forgottenDeadArePruned() {
        var life = registry(3);
        life.state(id(11)).bond(id(10), BondKind.SPOUSE);
        life.died(id(10));
        life.died(id(12));
        assertEquals(1, life.prune(10), "only the dead nobody remembers are forgotten");
        assertNotNull(life.state(id(10)));
        assertNull(life.state(id(12)));
        assertNull(life.byBody(id(12)));
        life.died(id(11));
        assertEquals(2, life.prune(10));
        assertEquals(0, life.size());
    }

    @Test void journalsRecordAndNotify() {
        var life = registry(1);
        List<String> seen = new ArrayList<>();
        life.onJournal((colony, entry) -> seen.add(entry.text()));
        long revision = life.revision();
        life.record(id(1), 48_000, Journal.Category.BIRTH, "@colonyloom.journal.birth|Anna", id(10));
        assertEquals(List.of("@colonyloom.journal.birth|Anna"), seen);
        assertEquals(1, life.journal(id(1)).size());
        assertTrue(life.revision() > revision);
        assertNull(life.existingJournal(id(99)));
    }

    @Test void stateBelongsToItsOwnerThread() throws Exception {
        var life = registry(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try { life.state(id(10)); } catch (Throwable error) { failure.set(error); }
        });
        other.start();
        other.join();
        assertInstanceOf(IllegalStateException.class, failure.get());
    }

    @Test void snapshotRestoresLivesPinsJournalsBodiesAndPregnancy() {
        var life = registry(3);
        LifeState mother = life.state(id(10));
        var plague = teen_pro_YT_CONTENT.disease("colonyloom:plague/v01");
        life.infect(mother, plague, 500, 900_000, id(11));
        life.infect(life.state(id(11)), plague, 0, 1_000_000, null);
        life.conceive(mother, pregnancy(500));
        mother.bond(id(11), BondKind.SPOUSE);
        life.record(id(1), 100, Journal.Category.HEALTH, "@colonyloom.journal.sick|Anna", id(10));
        life.state(id(12)).activity(new Activity("colonyloom:stargaze", pos(4), null, 600, 0, false));
        life.add(new LifeState(id(40), id(1), Sex.MALE, 24_000L * 5, 0, "Kid", "Family"));

        LifeSnapshot snapshot = life.snapshot();
        assertEquals(1 + 4 + 1, snapshot.records().size(), "one pin, four lives, one journal");
        var copy = new LifeRegistry(Thread.currentThread());
        copy.content(teen_pro_YT_CONTENT);
        copy.restore(snapshot);
        assertEquals(4, copy.size());
        assertEquals(plague, copy.state(id(10)).conditions().getFirst().disease());
        assertEquals(1, copy.pinCount());
        assertNotNull(copy.state(id(10)).pregnancy());
        assertTrue(copy.knows(id(500)), "the unborn child keeps its identity");
        assertEquals(1, copy.journal(id(1)).size());
        assertEquals(pos(4), copy.state(id(12)).activity().target());
        assertSame(copy.state(id(11)), copy.byBody(id(11)));
        assertTrue(copy.state(id(40)).child());
        assertEquals(snapshot, copy.snapshot(), "restore → snapshot is lossless");
    }

    @Test void restoreRejectsDuplicatesAndFutureRecords() {
        var life = registry(2);
        var snapshot = life.snapshot();

        List<Map<String, Object>> duplicate = new ArrayList<>(snapshot.records());
        duplicate.add(duplicate.get(0));
        assertThrows(IllegalArgumentException.class, () -> new LifeRegistry(Thread.currentThread()).restore(new LifeSnapshot(duplicate)));

        List<Map<String, Object>> shared = new ArrayList<>(snapshot.records());
        LinkedHashMap<String, Object> twin = new LinkedHashMap<>(shared.get(1));
        twin.put("citizenId", id(55).toString());
        shared.add(twin);
        assertThrows(IllegalArgumentException.class, () -> new LifeRegistry(Thread.currentThread()).restore(new LifeSnapshot(shared)), "two lives cannot share one citizen");

        List<Map<String, Object>> future = new ArrayList<>(snapshot.records());
        LinkedHashMap<String, Object> newer = new LinkedHashMap<>(future.get(0));
        newer.put("schemaVersion", 2L);
        future.set(0, newer);
        assertThrows(IllegalArgumentException.class, () -> new LifeRegistry(Thread.currentThread()).restore(new LifeSnapshot(future)));

        var untouched = new LifeRegistry(Thread.currentThread());
        untouched.add(new LifeState(id(70), id(1), Sex.MALE, 0, 0, "Still", "Here"));
        assertThrows(IllegalArgumentException.class, () -> untouched.restore(new LifeSnapshot(duplicate)));
        assertNotNull(untouched.state(id(70)), "a rejected restore changes nothing");
    }

    @Test void restoreClosesPregnancyWhoseChildAlreadyLives() {
        var life = registry(2);
        life.conceive(life.state(id(10)), pregnancy(500));
        List<Map<String, Object>> records = new ArrayList<>(life.snapshot().records());
        LinkedHashMap<String, Object> born = new LinkedHashMap<>(records.get(1));
        born.put("citizenId", id(500).toString());
        born.remove("body");
        born.remove("pregnancy");
        records.add(born);
        var copy = new LifeRegistry(Thread.currentThread());
        copy.restore(new LifeSnapshot(records));
        assertNull(copy.state(id(10)).pregnancy(), "birth is never repeated");
        assertNotNull(copy.state(id(500)));
    }
}
