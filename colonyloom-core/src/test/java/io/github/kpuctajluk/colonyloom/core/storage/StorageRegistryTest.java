package io.github.kpuctajluk.colonyloom.core.storage;

import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StorageRegistryTest {
    private static UUID id(long value) { return new UUID(0, value); }
    private static final UUID A = id(1), B = id(2), OWNER = id(3);
    private static final String DIMENSION = "minecraft:overworld";
    private static final ItemDescriptor STONE = new ItemDescriptor("minecraft:stone", new byte[0]);
    private static WorldPosition pos(int x) { return new WorldPosition(DIMENSION, x, 64, 0); }
    private static StockRegion slot(long identity, int slot) { return new StockRegion(new StorageId(DIMENSION, id(identity), 0), slot); }

    @Test void outputReservationCannotPublishUnknownOrOverpromisedPhysicalOutput() {
        Fixture f=new Fixture();var first=slot(100,0);var second=slot(100,1);f.register(A,0,"workshop",first,second);
        var entries=List.of(new ReservationLedger.Entry(id(1001),A,OWNER,first,STONE,2,0,AdmissionLedger.Lane.NORMAL),
                new ReservationLedger.Entry(id(1002),A,OWNER,second,STONE,2,0,AdmissionLedger.Lane.NORMAL));
        try(var prepared=f.storage.prepareReserveAll(entries,0)) {
            f.storage.index().observe(first,STONE,2,0);
            assertThrows(IllegalStateException.class,prepared::commit);
            assertTrue(f.storage.reservations().entries().isEmpty());
            f.storage.index().observe(second,STONE,1,0);
            assertThrows(IllegalStateException.class,prepared::commit);
            assertTrue(f.storage.reservations().entries().isEmpty());
            f.storage.index().observe(second,STONE,2,0);prepared.commit();
        }
        assertEquals(entries,f.storage.reservations().entries());
        assertEquals(0,f.storage.index().free(first,0));assertEquals(0,f.storage.index().free(second,0));
    }
    @Test void partialAllocationRelocationRetainsBothPhysicalCustodyObligations() {
        Fixture f=new Fixture();var source=slot(100,0);var dest=slot(101,0);f.register(A,0,"construction",source);f.register(A,1,"construction",dest);
        f.storage.index().observe(source,STONE,16,0);f.storage.index().observe(dest,null,0,0);
        f.storage.allocations().allocate(id(1001),A,OWNER,source,STONE,16,0,AdmissionLedger.Lane.NORMAL);
        try(var move=f.storage.prepareMoveAllocation(id(1001),dest,id(1002),true,true,6,0)) {
            assertThrows(IllegalStateException.class,() -> move.commit(6));
            assertEquals(16,f.storage.allocations().get(id(1001)).count());
            f.storage.index().observe(dest,STONE,6,0);move.commit(6);
        }
        f.storage.index().observe(source,STONE,10,0);
        assertEquals(10,f.storage.allocations().get(id(1001)).count());assertEquals(6,f.storage.allocations().get(id(1002)).count());
        assertEquals(OWNER,f.storage.allocations().get(id(1002)).ownerId());
        assertEquals(0,f.storage.index().free(source,0));assertEquals(0,f.storage.index().free(dest,0));
    }
    /** The mutable physical reader is shared by both colony views, not copied into registrations. */
    private static final class PhysicalReader implements StockIndex.Reader {
        final Map<StockRegion, StockIndex.Observation> inventories = new HashMap<>();
        final List<StockRegion> reads = new ArrayList<>();
        void set(StockRegion slot, ItemDescriptor item, long count) { inventories.put(slot, new StockIndex.Observation(item, count, true)); }
        @Override public StockIndex.Observation read(StockRegion slot) {
            reads.add(slot); return inventories.getOrDefault(slot, new StockIndex.Observation(null, 0, false));
        }
    }
    private static final class Fixture {
        final ColonyRegistry registry = new ColonyRegistry(() -> {});
        final StorageRegistry storage = registry.storage();
        final PhysicalReader physical = new PhysicalReader();
        final GlobalWorkBudgets budgets;
        long tick;
        Fixture() { this(SimulationLimits.development()); }
        Fixture(SimulationLimits limits) {
            registry.admission().updateLimits(limits);
            registry.addColony(new ColonyRuntime(A, "A", new Territory(DIMENSION, 0, 0, 31, 31), id(900), Map.of(), 0, 0, false, null, false));
            registry.addColony(new ColonyRuntime(B, "B", new Territory(DIMENSION, 64, 0, 95, 31), id(901), Map.of(), 0, 0, false, null, false));
            budgets = new GlobalWorkBudgets(limits, () -> 0);
        }
        StorageRegistry.Registration register(UUID colony, int address, String role, StockRegion... slots) {
            List<StorageId> identities = java.util.Arrays.stream(slots).map(StockRegion::storage).distinct().toList();
            return storage.register(colony, pos(address), role, identities, List.of(slots), identities.stream().map(ignored -> pos(address)).toList());
        }
        void scan() { budgets.beginTick(++tick); storage.index().tick(tick, budgets, physical); }
        AdmissionLedger replacement() { return new AdmissionLedger(registry.admission().limits(), () -> {}); }
    }

    @Test void matchingSlotCursorUsesExactItemIdsAndCanonicalOrderWithoutFilteringStock() {
        Fixture f = new Fixture();
        var first = slot(100, 0); var second = slot(100, 1); var last = slot(101, 0);
        var other = slot(102, 0);
        f.register(A, 0, "warehouse", last, second, first);
        f.register(A, 1, "warehouse", other);
        var variant = new ItemDescriptor(STONE.itemId(), new byte[]{1});
        var granite = new ItemDescriptor("minecraft:granite", new byte[0]);
        assertNull(f.storage.index().nextMatchingSlot(STONE.itemId(), null));
        f.storage.index().observe(last, STONE, 1, 0);
        f.storage.index().observe(second, variant, 1, 0);
        f.storage.index().observe(first, STONE, 1, 0);
        f.storage.index().observe(other, granite, 1, 0);
        f.storage.reservations().reserve(id(1001), A, OWNER, first, STONE, 1, 0, AdmissionLedger.Lane.NORMAL);
        assertEquals(0, f.storage.index().free(first, 0));
        assertEquals(0, f.storage.index().free(second, StockIndex.MAX_INDEX_AGE_TICKS));
        assertEquals(first, f.storage.index().nextMatchingSlot(STONE.itemId(), null));
        assertEquals(second, f.storage.index().nextMatchingSlot(STONE.itemId(), first));
        assertEquals(last, f.storage.index().nextMatchingSlot(STONE.itemId(), second));
        assertNull(f.storage.index().nextMatchingSlot(STONE.itemId(), last));
        assertEquals(other, f.storage.index().nextMatchingSlot(granite.itemId(), null));
        assertNull(f.storage.index().nextMatchingSlot("minecraft:stone_bricks", null));
        f.storage.index().observe(second, granite, 1, 1);
        assertEquals(last, f.storage.index().nextMatchingSlot(STONE.itemId(), first));
        assertEquals(second, f.storage.index().nextMatchingSlot(granite.itemId(), null));
        f.storage.index().unknown(second);
        assertEquals(other, f.storage.index().nextMatchingSlot(granite.itemId(), second));
        f.storage.index().observe(last, null, 0, 1);
        assertNull(f.storage.index().nextMatchingSlot(STONE.itemId(), first));
        f.storage.index().invalidate(first);
        assertNull(f.storage.index().nextMatchingSlot(STONE.itemId(), null));
    }

    @Test void matchingSlotCursorDropsReplacementRetirementAndRestoredObservations() {
        Fixture f = new Fixture(); var old = slot(100, 0); var next = slot(101, 0);
        f.register(A, 0, "warehouse", old); f.storage.index().observe(old, STONE, 1, 0);
        f.register(A, 0, "warehouse", next);
        assertFalse(f.storage.index().slots().contains(old));
        assertNull(f.storage.index().nextMatchingSlot(STONE.itemId(), null));
        f.storage.index().observe(next, STONE, 1, 0);
        assertEquals(next, f.storage.index().nextMatchingSlot(STONE.itemId(), old));
        var snapshot = f.storage.snapshot();
        try (var restore = f.storage.prepareRestore(snapshot, f.replacement(), f.registry.colonies())) { restore.commit(); }
        assertNull(f.storage.index().nextMatchingSlot(STONE.itemId(), null));
        f.storage.index().observe(next, STONE, 1, 1);
        f.storage.retire(next.storage(), A);
        assertNull(f.storage.index().nextMatchingSlot(STONE.itemId(), null));
        f.storage.index().observe(next, STONE, 1, 2);
        assertNull(f.storage.index().nextMatchingSlot(STONE.itemId(), null));
    }

    @Test void recanonicalizingReaderCannotPublishAnOldObservationIntoAnEqualReplacementSlot() {
        Fixture f = new Fixture(); var original = slot(100, 0); var temporary = slot(101, 0);
        f.register(A, 0, "warehouse", original); f.storage.index().observe(original, STONE, 1, 0);
        f.budgets.beginTick(1);
        f.storage.index().tick(1, f.budgets, reading -> {
            f.register(A, 0, "warehouse", temporary);
            f.register(A, 0, "warehouse", original);
            return new StockIndex.Observation(STONE, 1, true);
        });
        assertFalse(f.storage.index().observation(original).ready());
        assertNull(f.storage.index().nextMatchingSlot(STONE.itemId(), null));
    }

    @Test void reconciliationCallbackRetirementCannotRepublishMatchingMembership() {
        Fixture f = new Fixture(); var source = slot(100, 0);
        f.register(A, 0, "warehouse", source); f.storage.index().observe(source, STONE, 2, 0);
        f.storage.reservations().reserve(id(1001), A, OWNER, source, STONE, 2, 0, AdmissionLedger.Lane.NORMAL);
        f.registry.setBeforeMutation(() -> {
            assertNull(f.storage.index().nextMatchingSlot(STONE.itemId(), null));
            f.registry.setBeforeMutation(() -> {});
            f.storage.retire(source.storage(), A);
        });
        f.storage.index().observe(source, STONE, 1, 1);
        assertFalse(f.storage.index().observation(source).ready());
        assertNull(f.storage.index().nextMatchingSlot(STONE.itemId(), null));
    }

    @Test void aliasesAndTwoColoniesShareOnePhysical64AndOneGlobalSlotCharge() {
        Fixture f = new Fixture(); StockRegion slot = slot(100, 0);
        var first = f.register(A, 0, "warehouse", slot);
        assertEquals(first, f.register(A, 0, "warehouse", slot));
        f.register(A, 1, "return", slot); f.register(B, 64, "warehouse", slot);
        assertEquals(1, f.registry.admission().used(Resource.STORAGE_SLOTS));
        assertEquals(3, f.registry.admission().used(Resource.PHYSICAL_TARGETS));
        f.physical.set(slot, STONE, 64); f.scan();
        assertEquals(64, f.storage.index().free(slot, f.tick));
        assertEquals(List.of(slot), f.storage.index().candidates(A, STONE.itemId(), f.tick, 10));
        assertEquals(List.of(slot), f.storage.index().candidates(B, STONE.itemId(), f.tick, 10));
        f.storage.reservations().reserve(id(1001), A, OWNER, slot, STONE, 40, f.tick, AdmissionLedger.Lane.NORMAL);
        f.storage.allocations().allocate(id(1002), B, OWNER, slot, STONE, 24, f.tick, AdmissionLedger.Lane.NORMAL);
        assertEquals(0, f.storage.index().free(slot, f.tick));
        assertThrows(IllegalStateException.class, () -> f.storage.reservations().reserve(id(1003), B, OWNER, slot, STONE, 1, f.tick, AdmissionLedger.Lane.NORMAL));
        assertEquals(2, f.registry.admission().used(Resource.RESERVATIONS_AND_ALLOCATIONS));
        assertEquals(2, f.storage.registrations(A).size());
        assertTrue(f.storage.reservations().release(id(1001))); assertFalse(f.storage.reservations().release(id(1001)));
        assertEquals(40, f.storage.index().free(slot, f.tick));
    }

    @Test void componentsAreContentEqualDefensiveAndCannotPromiseAnotherVariant() {
        byte[] bytes = {1, 2, 3}; ItemDescriptor named = new ItemDescriptor("minecraft:stone", bytes);
        bytes[0] = 9; byte[] copy = named.canonicalComponents(); copy[1] = 8;
        assertEquals(new ItemDescriptor("minecraft:stone", new byte[]{1, 2, 3}), named);
        assertEquals(new ItemDescriptor("minecraft:stone", new byte[]{1, 2, 3}).hashCode(), named.hashCode());
        assertNotEquals(STONE, named);
        Fixture f = new Fixture(); StockRegion slot = slot(100, 0); f.register(A, 0, "warehouse", slot);
        f.physical.set(slot, named, 64); f.scan();
        assertThrows(IllegalStateException.class, () -> f.storage.reservations().reserve(id(1001), A, OWNER, slot, STONE, 1, f.tick, AdmissionLedger.Lane.NORMAL));
        f.storage.reservations().reserve(id(1001), A, OWNER, slot, named, 40, f.tick, AdmissionLedger.Lane.NORMAL);
        f.physical.set(slot, STONE, 64); f.scan();
        assertTrue(f.storage.reservations().entries().isEmpty());
        assertEquals(64, f.storage.index().free(slot, f.tick));
        assertThrows(IllegalArgumentException.class, () -> new ItemDescriptor("minecraft:stone", new byte[8193]));
    }

    @Test void staleAndUnknownRetainBothKindsOfObligationAndRestoreNeverInventsObservation() {
        Fixture f = new Fixture(); StockRegion slot = slot(100, 0); f.register(A, 0, "warehouse", slot);
        f.physical.set(slot, STONE, 64); f.scan();
        var reservation = f.storage.reservations().reserve(id(1001), A, OWNER, slot, STONE, 20, f.tick, AdmissionLedger.Lane.NORMAL);
        var allocation = f.storage.allocations().allocate(id(1002), A, OWNER, slot, STONE, 30, f.tick, AdmissionLedger.Lane.NORMAL);
        assertEquals(0, f.storage.index().free(slot, f.tick + 201));
        f.storage.index().unknown(slot);
        assertEquals(0, f.storage.index().free(slot, f.tick));
        assertEquals(List.of(reservation), f.storage.reservations().entries()); assertEquals(List.of(allocation), f.storage.allocations().entries());
        StorageSnapshot saved = f.storage.snapshot(); AdmissionLedger replacement = f.replacement();
        try (var prepared = f.storage.prepareRestore(saved, replacement, f.registry.colonies())) { prepared.commit(); }
        assertEquals(saved, f.storage.snapshot()); assertFalse(f.storage.index().observation(slot).ready());
        assertEquals(0, f.storage.index().free(slot, f.tick));
        assertEquals(2, replacement.used(Resource.RESERVATIONS_AND_ALLOCATIONS));
        assertEquals(0, f.registry.admission().used(Resource.RESERVATIONS_AND_ALLOCATIONS));
        f.storage.index().observe(slot, STONE, 64, f.tick + 1);
        assertEquals(14, f.storage.index().free(slot, f.tick + 1));
    }

    @Test void unnoticedPhysicalLossShrinksDeterministicallyAcrossBothLedgersOnFairScan() {
        Fixture f = new Fixture(); StockRegion slot = slot(100, 0); f.register(A, 0, "warehouse", slot); f.register(B, 64, "warehouse", slot);
        f.physical.set(slot, STONE, 64); f.scan();
        // Admission order does not establish priority: UUID order is canonical across both ledgers.
        f.storage.reservations().reserve(id(1002), A, OWNER, slot, STONE, 40, f.tick, AdmissionLedger.Lane.NORMAL);
        f.storage.allocations().allocate(id(1001), B, OWNER, slot, STONE, 24, f.tick, AdmissionLedger.Lane.NORMAL);
        f.physical.set(slot, STONE, 32); f.scan();
        assertEquals(24, f.storage.allocations().entries().getFirst().count());
        assertEquals(8, f.storage.reservations().entries().getFirst().count());
        assertEquals(1, f.storage.reservations().entries().getFirst().revision());
        assertEquals(0, f.storage.index().free(slot, f.tick));
        f.physical.set(slot, null, 0); f.scan();
        assertTrue(f.storage.reservations().entries().isEmpty()); assertTrue(f.storage.allocations().entries().isEmpty());
        assertEquals(0, f.registry.admission().used(Resource.RESERVATIONS_AND_ALLOCATIONS));
    }

    @Test void repeatedHotNotificationsCannotStarveOneCheckRoundRobinSweep() {
        Fixture f = new Fixture(SimulationLimits.development().withBudget(Budget.STORAGE_SLOT_CHECKS, 1));
        StockRegion hot = slot(100, 0), middle = slot(100, 1), last = slot(100, 2);
        f.register(A, 0, "warehouse", hot, middle, last);
        for (StockRegion slot : List.of(hot, middle, last)) f.physical.set(slot, STONE, 64);
        Set<StockRegion> seen = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            f.storage.index().invalidate(hot); f.storage.index().invalidate(hot); f.scan();
            assertEquals(1, f.budgets.used(Budget.STORAGE_SLOT_CHECKS)); seen.addAll(f.physical.reads);
        }
        assertEquals(Set.of(hot, middle, last), seen);
        assertTrue(f.storage.index().observation(last).ready());
        f.physical.set(last, STONE, 12);
        for (int i = 0; i < 6; i++) { f.storage.index().invalidate(hot); f.scan(); }
        assertEquals(12, f.storage.index().free(last, f.tick));
    }

    @Test void sharedFourCheckQuotaRefreshesLiveSlotsAndAllowsIndivisibleDownstreamReads() {
        Fixture f = new Fixture(SimulationLimits.development().withBudget(Budget.STORAGE_SLOT_CHECKS, 4));
        List<StockRegion> slots = new ArrayList<>();
        for (int batch = 0; slots.size() < 400; batch++) {
            List<StockRegion> registration = new ArrayList<>();
            for (int local = 0; local < 27 && slots.size() < 400; local++) {
                var slot = slot(100 + batch, local); registration.add(slot); slots.add(slot);
                f.physical.set(slot, STONE, 64);
            }
            f.register(A, batch, "warehouse", registration.toArray(StockRegion[]::new));
        }
        assertEquals(StockIndex.SweepCapacity.SHARED, f.storage.index().sweepCapacity(4));
        Map<StockRegion, Long> lastRead = new HashMap<>();
        int downstreamTurns = 0;
        for (int tick = 1; tick <= 440; tick++) {
            f.physical.reads.clear(); f.scan();
            assertEquals(f.physical.reads.size(), f.budgets.used(Budget.STORAGE_SLOT_CHECKS));
            for (var slot : f.physical.reads) lastRead.put(slot, f.tick);
            if (f.budgets.used(Budget.STORAGE_SLOT_CHECKS) == 0) {
                for (int check = 0; check < 4; check++) {
                    assertTrue(f.budgets.tryConsume(Budget.STORAGE_SLOT_CHECKS, AdmissionLedger.Lane.NORMAL));
                    f.physical.read(slots.get(check));
                }
                downstreamTurns++;
            }
            assertTrue(f.budgets.used(Budget.STORAGE_SLOT_CHECKS) <= 4);
            if (tick >= 200) for (var slot : slots) {
                assertTrue(tick - lastRead.get(slot) < StockIndex.MAX_INDEX_AGE_TICKS);
                assertEquals(64, f.storage.index().free(slot, tick));
            }
        }
        assertEquals(22, downstreamTurns);
        assertEquals(400, lastRead.size());
        assertEquals(0, f.storage.index().free(slots.getFirst(), lastRead.get(slots.getFirst()) + 200));
    }

    @Test void repeatedHotNotificationsUseSpareChecksWithoutDelayingNativeSweepOrDownstreamTurn() {
        Fixture f = new Fixture(SimulationLimits.development().withBudget(Budget.STORAGE_SLOT_CHECKS, 4));
        List<StockRegion> slots = new ArrayList<>();
        for (int batch = 0; slots.size() < 200; batch++) {
            List<StockRegion> registration = new ArrayList<>();
            for (int local = 0; local < 27 && slots.size() < 200; local++) {
                var slot = slot(100 + batch, local); registration.add(slot); slots.add(slot);
                f.physical.set(slot, STONE, 64);
            }
            f.register(A, batch, "warehouse", registration.toArray(StockRegion[]::new));
        }
        var hot = slots.getLast();
        Map<StockRegion, Long> lastRead = new HashMap<>();
        for (int tick = 1; tick <= 440; tick++) {
            f.physical.reads.clear(); f.storage.index().invalidate(hot); f.storage.index().invalidate(hot); f.scan();
            for (var slot : f.physical.reads) lastRead.put(slot, f.tick);
            assertEquals(f.physical.reads.size(), new HashSet<>(f.physical.reads).size());
            assertEquals(f.physical.reads.size(), f.budgets.used(Budget.STORAGE_SLOT_CHECKS));
            if (tick % 20 == 0) assertTrue(f.physical.reads.isEmpty());
            else { assertTrue(f.physical.reads.contains(hot)); assertTrue(f.budgets.used(Budget.STORAGE_SLOT_CHECKS) <= 3); }
            if (tick >= 200) for (var slot : slots) assertTrue(tick - lastRead.get(slot) < StockIndex.MAX_INDEX_AGE_TICKS);
        }
        assertEquals(200, lastRead.size());
    }

    @Test void downstreamOnlyTurnDoesNotInventAnObservationOrRefreshAnUnknownSlot() {
        Fixture f = new Fixture(SimulationLimits.development().withBudget(Budget.STORAGE_SLOT_CHECKS, 4));
        var slot = slot(100, 0); f.register(A, 0, "warehouse", slot); f.physical.set(slot, STONE, 64);
        f.tick = 19; f.scan();
        assertTrue(f.physical.reads.isEmpty());
        assertEquals(0, f.budgets.used(Budget.STORAGE_SLOT_CHECKS));
        assertFalse(f.storage.index().observation(slot).ready());
        assertEquals(0, f.storage.index().free(slot, f.tick));
        f.scan();
        assertEquals(List.of(slot), f.physical.reads);
        assertEquals(64, f.storage.index().free(slot, f.tick));
    }

    @Test void sweepCapacityReportsWhenFreshnessAndSharedTurnsCannotBothFit() {
        Fixture f = new Fixture(SimulationLimits.development().withBudget(Budget.STORAGE_SLOT_CHECKS, 1));
        for (int batch = 0; batch < 8; batch++) {
            List<StockRegion> slots = new ArrayList<>();
            for (int local = 0; local < (batch == 7 ? 1 : 27); local++) slots.add(slot(100 + batch, local));
            f.register(A, batch, "warehouse", slots.toArray(StockRegion[]::new));
        }
        assertEquals(StockIndex.SweepCapacity.FRESHNESS_ONLY, f.storage.index().sweepCapacity(1));
        List<StockRegion> extra = new ArrayList<>();
        for (int local = 0; local < 11; local++) extra.add(slot(108, local));
        f.register(A, 8, "warehouse", extra.toArray(StockRegion[]::new));
        assertEquals(StockIndex.SweepCapacity.INFEASIBLE, f.storage.index().sweepCapacity(1));
        assertThrows(IllegalArgumentException.class, () -> f.storage.index().sweepCapacity(0));
    }

    @Test void localViewsCannotLeakUnregisteredOrForeignCanonicalSlots() {
        Fixture f = new Fixture(); StockRegion first = slot(100, 0), second = slot(101, 0);
        f.register(A, 0, "warehouse", first); f.register(B, 64, "warehouse", second);
        f.physical.set(first, STONE, 64); f.physical.set(second, STONE, 64); f.scan();
        assertEquals(List.of(first), f.storage.index().candidates(A, STONE.itemId(), f.tick, 10));
        assertEquals(List.of(second), f.storage.index().candidates(B, STONE.itemId(), f.tick, 10));
        assertThrows(IllegalArgumentException.class, () -> f.storage.allocations().allocate(id(1001), A, OWNER, second, STONE, 1, f.tick, AdmissionLedger.Lane.NORMAL));
        assertThrows(IllegalArgumentException.class, () -> f.storage.index().observe(slot(102, 0), STONE, 64, f.tick));
        assertThrows(IllegalArgumentException.class, () -> f.storage.index().candidates(A, STONE.itemId(), f.tick, 257));
    }

    @Test void failedSlotAdmissionIsAtomicIncludingRegistrationTargetCharge() {
        Fixture f = new Fixture(SimulationLimits.development().withResource(Resource.STORAGE_SLOTS, 1));
        StockRegion first = slot(100, 0), second = slot(101, 0);
        var registration = f.register(A, 0, "warehouse", first);
        assertThrows(AdmissionLedger.AdmissionException.class, () -> f.register(B, 64, "warehouse", second));
        assertEquals(List.of(registration), f.storage.registrations());
        assertEquals(List.of(first), f.storage.index().slots());
        assertEquals(1, f.registry.admission().used(Resource.PHYSICAL_TARGETS));
        assertEquals(1, f.registry.admission().used(Resource.STORAGE_SLOTS));
    }

    @Test void failedObligationAdmissionAndMutationBarrierCannotPublishPromises() {
        Fixture f = new Fixture(SimulationLimits.development().withResource(Resource.RESERVATIONS_AND_ALLOCATIONS, 2));
        StockRegion slot = slot(100, 0); f.register(A, 0, "warehouse", slot); f.physical.set(slot, STONE, 64); f.scan();
        f.storage.reservations().reserve(id(1001), A, OWNER, slot, STONE, 1, f.tick, AdmissionLedger.Lane.NORMAL);
        assertThrows(AdmissionLedger.AdmissionException.class, () -> f.storage.allocations().allocate(id(1002), A, OWNER, slot, STONE, 1, f.tick, AdmissionLedger.Lane.NORMAL));
        assertTrue(f.storage.allocations().entries().isEmpty()); assertEquals(63, f.storage.index().free(slot, f.tick));
        f.registry.setBeforeMutation(() -> { throw new IllegalStateException("disk barrier"); });
        assertThrows(IllegalStateException.class, () -> f.storage.reservations().release(id(1001)));
        assertEquals(1, f.storage.reservations().entries().size());
        assertThrows(IllegalStateException.class, () -> f.storage.index().observe(slot, null, 0, f.tick + 1));
        assertNull(f.storage.index().nextMatchingSlot(STONE.itemId(), null));
        assertEquals(0, f.storage.index().free(slot, f.tick + 1));
    }

    @Test void replacementKeepsOldObligationsUnknownAndNeverTransfersThemToNewIdentity() {
        Fixture f = new Fixture(); StockRegion old = slot(100, 0), next = slot(101, 0);
        var previous = f.register(A, 0, "warehouse", old); f.physical.set(old, STONE, 64); f.scan();
        var allocation = f.storage.allocations().allocate(id(1001), A, OWNER, old, STONE, 64, f.tick, AdmissionLedger.Lane.NORMAL);
        var replacement = f.register(A, 0, "warehouse", next);
        assertEquals(previous.id(), replacement.id()); assertEquals(1, replacement.revision());
        assertEquals(0, f.storage.index().free(old, f.tick)); assertEquals(List.of(allocation), f.storage.allocations().entries());
        assertNull(f.storage.index().nextMatchingSlot(STONE.itemId(), null));
        f.physical.set(next, STONE, 64); f.scan(); assertEquals(64, f.storage.index().free(next, f.tick));
        assertEquals(List.of(next), f.storage.index().candidates(A, STONE.itemId(), f.tick, 10));
        StorageSnapshot saved = f.storage.snapshot();
        try (var restore = f.storage.prepareRestore(saved, f.replacement(), f.registry.colonies())) { restore.commit(); }
        assertEquals(List.of(allocation), f.storage.allocations().entries()); assertEquals(0, f.storage.index().free(old, f.tick));
        assertTrue(f.storage.allocations().release(allocation.id())); assertFalse(f.storage.index().slots().contains(old));
    }

    @Test void permanentRetirementRetainsPromisesEvenWithOldIdentityStillRegisteredAfterRestore() {
        Fixture f = new Fixture(); StockRegion slot = slot(100, 0); f.register(A, 0, "warehouse", slot);
        f.physical.set(slot, STONE, 64); f.scan();
        var allocation = f.storage.allocations().allocate(id(1001), A, OWNER, slot, STONE, 32, f.tick, AdmissionLedger.Lane.NORMAL);
        f.storage.retire(slot.storage(), A); f.storage.index().observe(slot, STONE, 64, f.tick + 1);
        assertEquals(0, f.storage.index().free(slot, f.tick + 1)); assertEquals(List.of(allocation), f.storage.allocations().entries());
        StorageSnapshot saved = f.storage.snapshot();
        try (var restore = f.storage.prepareRestore(saved, f.replacement(), f.registry.colonies())) { restore.commit(); }
        assertTrue(f.storage.isRetired(slot.storage())); f.storage.index().observe(slot, STONE, 64, f.tick + 2);
        assertFalse(f.storage.index().observation(slot).ready()); assertEquals(List.of(allocation), f.storage.allocations().entries());
        assertThrows(IllegalArgumentException.class, () -> f.register(B, 64, "warehouse", slot));
    }

    @Test void abandonedInvalidAndQuotaFailedRestoreLeaveLiveStateAndAdmissionUnchanged() {
        Fixture f = new Fixture(); StockRegion slot = slot(100, 0); f.register(A, 0, "workshop", slot);
        var workshop = f.storage.registerWorkshop(A, pos(2), f.storage.registrations().getFirst().id());
        f.physical.set(slot, STONE, 64); f.scan();
        f.storage.reservations().reserve(id(1001), A, OWNER, slot, STONE, 20, f.tick, AdmissionLedger.Lane.NORMAL);
        StorageSnapshot saved = f.storage.snapshot(); AdmissionLedger replacement = f.replacement();
        try (var ignored = f.storage.prepareRestore(saved, replacement, f.registry.colonies())) {
            assertEquals(20, f.storage.reservations().entries().getFirst().count());
        }
        assertEquals(0, replacement.used(Resource.STORAGE_SLOTS)); assertEquals(0, replacement.used(Resource.PHYSICAL_TARGETS));
        assertEquals(saved, f.storage.snapshot()); assertEquals(44, f.storage.index().free(slot, f.tick));
        var invalid = new StorageSnapshot(saved.registrations(), saved.reservations(), saved.allocations(),
                List.of(new StorageRegistry.Workshop(workshop.id(), B, pos(64), workshop.registrationId(), 0)), List.of());
        assertThrows(IllegalArgumentException.class, () -> f.storage.prepareRestore(invalid, replacement, f.registry.colonies()));
        var duplicate = new StorageSnapshot(saved.registrations(), List.of(saved.reservations().getFirst(), saved.reservations().getFirst()), List.of(), saved.workshops(), List.of());
        assertThrows(IllegalArgumentException.class, () -> f.storage.prepareRestore(duplicate, replacement, f.registry.colonies()));
        AdmissionLedger small = new AdmissionLedger(SimulationLimits.development().withResource(Resource.PHYSICAL_TARGETS, 1), () -> {});
        assertThrows(AdmissionLedger.AdmissionException.class, () -> f.storage.prepareRestore(saved, small, f.registry.colonies()));
        assertEquals(0, small.used(Resource.PHYSICAL_TARGETS)); assertEquals(0, small.used(Resource.STORAGE_SLOTS));
        assertEquals(saved, f.storage.snapshot());
    }

    @Test void scanTimeGuardStopsBeforeNativeReadAndPhysicalEnvelopeIsValidated() {
        Fixture f = new Fixture(); StockRegion slot = slot(100, 0); f.register(A, 0, "warehouse", slot);
        long[] clock = {0}; GlobalWorkBudgets timed = new GlobalWorkBudgets(SimulationLimits.development().withMaxManagedNanos(10), () -> clock[0]);
        timed.beginTick(1); clock[0] = 10; f.storage.index().tick(1, timed, f.physical); assertTrue(f.physical.reads.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new StockRegion(slot.storage(), 27));
        assertThrows(IllegalArgumentException.class, () -> new StockRegion(new StorageId(DIMENSION, id(100), 1), 9));
        assertThrows(IllegalArgumentException.class, () -> new StorageId("not_dimension", id(100), 0));
        assertThrows(IllegalArgumentException.class, () -> new StorageId(DIMENSION, id(100), -1));
        assertThrows(IllegalArgumentException.class, () -> new StockIndex.Observation(STONE, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new ReservationLedger.Entry(id(1001), A, OWNER, slot, STONE, 0, 0, AdmissionLedger.Lane.NORMAL));
        assertThrows(IllegalArgumentException.class, () -> new AllocationLedger.Entry(id(1001), A, OWNER, slot, STONE, 1_000_001, 0, AdmissionLedger.Lane.NORMAL));
    }
    @Test void readerRecanonicalizationDoesNotObserveOrTransferTheOldIdentity() {
        Fixture f = new Fixture(); StockRegion old = slot(100, 0), next = slot(101, 0);
        f.register(A, 0, "warehouse", old); f.physical.set(old, STONE, 64); f.scan();
        f.storage.reservations().reserve(id(1001), A, OWNER, old, STONE, 32, f.tick, AdmissionLedger.Lane.NORMAL);
        f.budgets.beginTick(++f.tick);
        f.storage.index().tick(f.tick, f.budgets, region -> {
            if (region.equals(old)) f.register(A, 0, "warehouse", next);
            return new StockIndex.Observation(STONE, 64, true);
        });
        assertEquals(0, f.storage.index().free(old, f.tick));
        assertEquals(32, f.storage.reservations().entries().getFirst().count());
        assertEquals(List.of(next), f.storage.registrations().getFirst().slots());
    }

    @Test void invalidSavedCanonicalTotalsAndComponentMixturesRejectBeforeAdmission() {
        Fixture f = new Fixture(); StockRegion slot = slot(100, 0); f.register(A, 0, "warehouse", slot);
        ReservationLedger.Entry reserved = new ReservationLedger.Entry(id(1001), A, OWNER, slot, STONE, 1_000_000, 0, AdmissionLedger.Lane.NORMAL);
        AllocationLedger.Entry allocated = new AllocationLedger.Entry(id(1002), A, OWNER, slot, STONE, 1, 0, AdmissionLedger.Lane.NORMAL);
        AdmissionLedger replacement = f.replacement();
        StorageSnapshot excessive = new StorageSnapshot(f.storage.registrations(), List.of(reserved), List.of(allocated), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> f.storage.prepareRestore(excessive, replacement, f.registry.colonies()));
        var mixed = new StorageSnapshot(f.storage.registrations(),
                List.of(new ReservationLedger.Entry(id(1001), A, OWNER, slot, STONE, 1, 0, AdmissionLedger.Lane.NORMAL)),
                List.of(new AllocationLedger.Entry(id(1002), A, OWNER, slot, new ItemDescriptor(STONE.itemId(), new byte[]{1}), 1, 0, AdmissionLedger.Lane.NORMAL)), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> f.storage.prepareRestore(mixed, replacement, f.registry.colonies()));
        assertEquals(0, replacement.used(Resource.STORAGE_SLOTS));
        assertEquals(0, replacement.used(Resource.RESERVATIONS_AND_ALLOCATIONS));
    }
}
