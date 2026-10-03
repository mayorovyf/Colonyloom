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
        f.storage.reservations().reserve(id(1001), A, OWNER, slot, STONE, 40, f.tick);
        f.storage.allocations().allocate(id(1002), B, OWNER, slot, STONE, 24, f.tick);
        assertEquals(0, f.storage.index().free(slot, f.tick));
        assertThrows(IllegalStateException.class, () -> f.storage.reservations().reserve(id(1003), B, OWNER, slot, STONE, 1, f.tick));
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
        assertThrows(IllegalStateException.class, () -> f.storage.reservations().reserve(id(1001), A, OWNER, slot, STONE, 1, f.tick));
        f.storage.reservations().reserve(id(1001), A, OWNER, slot, named, 40, f.tick);
        f.physical.set(slot, STONE, 64); f.scan();
        assertTrue(f.storage.reservations().entries().isEmpty());
        assertEquals(64, f.storage.index().free(slot, f.tick));
        assertThrows(IllegalArgumentException.class, () -> new ItemDescriptor("minecraft:stone", new byte[8193]));
    }

    @Test void staleAndUnknownRetainBothKindsOfObligationAndRestoreNeverInventsObservation() {
        Fixture f = new Fixture(); StockRegion slot = slot(100, 0); f.register(A, 0, "warehouse", slot);
        f.physical.set(slot, STONE, 64); f.scan();
        var reservation = f.storage.reservations().reserve(id(1001), A, OWNER, slot, STONE, 20, f.tick);
        var allocation = f.storage.allocations().allocate(id(1002), A, OWNER, slot, STONE, 30, f.tick);
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
        f.storage.reservations().reserve(id(1002), A, OWNER, slot, STONE, 40, f.tick);
        f.storage.allocations().allocate(id(1001), B, OWNER, slot, STONE, 24, f.tick);
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

    @Test void localViewsCannotLeakUnregisteredOrForeignCanonicalSlots() {
        Fixture f = new Fixture(); StockRegion first = slot(100, 0), second = slot(101, 0);
        f.register(A, 0, "warehouse", first); f.register(B, 64, "warehouse", second);
        f.physical.set(first, STONE, 64); f.physical.set(second, STONE, 64); f.scan();
        assertEquals(List.of(first), f.storage.index().candidates(A, STONE.itemId(), f.tick, 10));
        assertEquals(List.of(second), f.storage.index().candidates(B, STONE.itemId(), f.tick, 10));
        assertThrows(IllegalArgumentException.class, () -> f.storage.allocations().allocate(id(1001), A, OWNER, second, STONE, 1, f.tick));
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
        f.storage.reservations().reserve(id(1001), A, OWNER, slot, STONE, 1, f.tick);
        assertThrows(AdmissionLedger.AdmissionException.class, () -> f.storage.allocations().allocate(id(1002), A, OWNER, slot, STONE, 1, f.tick));
        assertTrue(f.storage.allocations().entries().isEmpty()); assertEquals(63, f.storage.index().free(slot, f.tick));
        f.registry.setBeforeMutation(() -> { throw new IllegalStateException("disk barrier"); });
        assertThrows(IllegalStateException.class, () -> f.storage.reservations().release(id(1001)));
        assertEquals(1, f.storage.reservations().entries().size());
        assertThrows(IllegalStateException.class, () -> f.storage.index().observe(slot, null, 0, f.tick + 1));
        assertEquals(0, f.storage.index().free(slot, f.tick + 1));
    }

    @Test void replacementKeepsOldObligationsUnknownAndNeverTransfersThemToNewIdentity() {
        Fixture f = new Fixture(); StockRegion old = slot(100, 0), next = slot(101, 0);
        var previous = f.register(A, 0, "warehouse", old); f.physical.set(old, STONE, 64); f.scan();
        var allocation = f.storage.allocations().allocate(id(1001), A, OWNER, old, STONE, 64, f.tick);
        var replacement = f.register(A, 0, "warehouse", next);
        assertEquals(previous.id(), replacement.id()); assertEquals(1, replacement.revision());
        assertEquals(0, f.storage.index().free(old, f.tick)); assertEquals(List.of(allocation), f.storage.allocations().entries());
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
        var allocation = f.storage.allocations().allocate(id(1001), A, OWNER, slot, STONE, 32, f.tick);
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
        f.storage.reservations().reserve(id(1001), A, OWNER, slot, STONE, 20, f.tick);
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
        assertThrows(IllegalArgumentException.class, () -> new ReservationLedger.Entry(id(1001), A, OWNER, slot, STONE, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new AllocationLedger.Entry(id(1001), A, OWNER, slot, STONE, 1_000_001, 0));
    }
    @Test void readerRecanonicalizationDoesNotObserveOrTransferTheOldIdentity() {
        Fixture f = new Fixture(); StockRegion old = slot(100, 0), next = slot(101, 0);
        f.register(A, 0, "warehouse", old); f.physical.set(old, STONE, 64); f.scan();
        f.storage.reservations().reserve(id(1001), A, OWNER, old, STONE, 32, f.tick);
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
        ReservationLedger.Entry reserved = new ReservationLedger.Entry(id(1001), A, OWNER, slot, STONE, 1_000_000, 0);
        AllocationLedger.Entry allocated = new AllocationLedger.Entry(id(1002), A, OWNER, slot, STONE, 1, 0);
        AdmissionLedger replacement = f.replacement();
        StorageSnapshot excessive = new StorageSnapshot(f.storage.registrations(), List.of(reserved), List.of(allocated), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> f.storage.prepareRestore(excessive, replacement, f.registry.colonies()));
        var mixed = new StorageSnapshot(f.storage.registrations(),
                List.of(new ReservationLedger.Entry(id(1001), A, OWNER, slot, STONE, 1, 0)),
                List.of(new AllocationLedger.Entry(id(1002), A, OWNER, slot, new ItemDescriptor(STONE.itemId(), new byte[]{1}), 1, 0)), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> f.storage.prepareRestore(mixed, replacement, f.registry.colonies()));
        assertEquals(0, replacement.used(Resource.STORAGE_SLOTS));
        assertEquals(0, replacement.used(Resource.RESERVATIONS_AND_ALLOCATIONS));
    }
}
