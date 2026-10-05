package io.github.kpuctajluk.colonyloom.core.scheduler;

import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class AdmissionLedgerTest {
    private static final UUID FIRST = new UUID(0, 1);
    private static final UUID SECOND = new UUID(0, 2);
    private static final UUID THIRD = new UUID(0, 3);
    private static AdmissionLedger ledger(SimulationLimits limits) { return new AdmissionLedger(limits, () -> {}); }

    @Test void directCapacityGatePreservesTypedDenialWithoutReservingOrDoubleCounting() {
        var ledger=ledger(SimulationLimits.development().withResource(Resource.COLONIES,1));
        var cost=Map.of(Resource.COLONIES,1);
        ledger.requireCapacity(FIRST,Lane.NORMAL,cost);
        assertEquals(0,ledger.used(Resource.COLONIES));assertEquals(0,ledger.highWater(Resource.COLONIES));
        try(var occupied=ledger.reserve(FIRST,Lane.NORMAL,cost)) {
            var failure=assertThrows(AdmissionLedger.AdmissionException.class,() -> ledger.requireCapacity(SECOND,Lane.NORMAL,cost));
            assertEquals(Resource.COLONIES,failure.resource());assertEquals(AdmissionLedger.Reason.STATE_LIMIT,failure.reason());
            assertEquals(1,ledger.rejected(Resource.COLONIES));assertEquals(1,ledger.used(Resource.COLONIES));
            assertEquals(0,ledger.used(SECOND,Resource.COLONIES));
        }
        ledger.requireCapacity(SECOND,Lane.NORMAL,cost);
        assertEquals(0,ledger.used(Resource.COLONIES));
    }

    @Test void directCapacityGateRetainsCriticalBlockadeReason() {
        var ledger=ledger(SimulationLimits.development().withResource(Resource.CHUNK_DEMANDS,8));
        try(var occupied=ledger.reserve(FIRST,Lane.CRITICAL,Map.of(Resource.CHUNK_DEMANDS,8))) {
            assertFalse(ledger.canReserve(SECOND,Lane.CRITICAL,Map.of(Resource.CHUNK_DEMANDS,1)));
            var failure=assertThrows(AdmissionLedger.AdmissionException.class,
                    () -> ledger.requireCapacity(SECOND,Lane.NORMAL,Map.of(Resource.COLONIES,1)));
            assertEquals(Resource.CHUNK_DEMANDS,failure.resource());assertEquals(AdmissionLedger.Reason.CRITICAL_CAPACITY,failure.reason());
            assertEquals(2,ledger.rejected(Resource.CHUNK_DEMANDS));assertEquals(0,ledger.used(Resource.COLONIES));
        }
    }

    @Test void criticalBlockadeDeniesNativeFootprintPreflightWithoutPartialMutation() {
        var ledger=ledger(SimulationLimits.development().withResource(Resource.CHUNK_DEMANDS,8));
        var footprint=Map.of(Resource.LOADED_FOOTPRINT,1);
        try(var critical=ledger.reserve(FIRST,Lane.CRITICAL,Map.of(Resource.CHUNK_DEMANDS,8))) {
            assertFalse(ledger.canReserve(SECOND,Lane.CRITICAL,Map.of(Resource.CHUNK_DEMANDS,1)));
            assertFalse(ledger.canReserve(SECOND,Lane.NORMAL,footprint));
            assertEquals(Resource.CHUNK_DEMANDS,assertThrows(AdmissionLedger.AdmissionException.class,
                    () -> ledger.reserve(SECOND,Lane.NORMAL,footprint)).resource());
            assertEquals(0,ledger.used(Resource.LOADED_FOOTPRINT));
        }
        assertTrue(ledger.canReserve(SECOND,Lane.NORMAL,footprint));
        try(var admitted=ledger.reserve(SECOND,Lane.NORMAL,footprint)) {
            assertEquals(1,ledger.used(Resource.LOADED_FOOTPRINT));
        }
    }

    @Test
    void rootCleanupFailureDoesNotPartiallyAdmitRequestedWork() {
        AdmissionLedger ledger = ledger(SimulationLimits.development().withResource(Resource.WORKS, 8));
        try (var cleanup = ledger.reserve(FIRST, Lane.SERVICE, Map.of(Resource.WORKS, 1))) {
            var failure = assertThrows(AdmissionLedger.AdmissionException.class,
                    () -> ledger.reserveRoot(FIRST, Lane.NORMAL, Map.of(Resource.WORKS, 1)));
            assertEquals(Resource.WORKS, failure.resource());
            assertEquals(1, ledger.used(Resource.WORKS));
            assertEquals(0, ledger.used(Resource.WORKS, Lane.NORMAL));
            assertEquals(0, ledger.used(Resource.WAIT_REGISTRATIONS));
        }
        try (var root = ledger.reserveRoot(FIRST, Lane.NORMAL, Map.of(Resource.WORKS, 1))) {
            assertEquals(2, ledger.used(Resource.WORKS));
            assertEquals(1, ledger.used(Resource.WAIT_REGISTRATIONS, Lane.SERVICE));
        }
        assertEquals(0, ledger.used(Resource.WORKS));
        assertEquals(0, ledger.used(Resource.WAIT_REGISTRATIONS));
    }

    @Test
    void normalBorrowingIsBoundedAndCannotConsumeCriticalOrServiceMemory() {
        AdmissionLedger ledger = ledger(SimulationLimits.development().withResource(Resource.GRAPH_NODES, 24));
        try (var first = ledger.reserve(FIRST, Lane.NORMAL, Map.of(Resource.GRAPH_NODES, 12));
             var second = ledger.reserve(SECOND, Lane.NORMAL, Map.of(Resource.GRAPH_NODES, 6))) {
            assertThrows(AdmissionLedger.AdmissionException.class,
                    () -> ledger.reserve(FIRST, Lane.NORMAL, Map.of(Resource.GRAPH_NODES, 1)));
            assertThrows(AdmissionLedger.AdmissionException.class,
                    () -> ledger.reserve(THIRD, Lane.NORMAL, Map.of(Resource.GRAPH_NODES, 1)));
            try (var critical = ledger.reserve(THIRD, Lane.CRITICAL, Map.of(Resource.GRAPH_NODES, 3));
                 var service = ledger.reserve(THIRD, Lane.SERVICE, Map.of(Resource.GRAPH_NODES, 3))) {
                assertEquals(24, ledger.used(Resource.GRAPH_NODES));
                var failure = assertThrows(AdmissionLedger.AdmissionException.class,
                        () -> ledger.reserve(THIRD, Lane.CRITICAL, Map.of(Resource.GRAPH_NODES, 1)));
                assertEquals(AdmissionLedger.Reason.CRITICAL_CAPACITY, failure.reason());
            }
        }
        assertEquals(24, ledger.highWater(Resource.GRAPH_NODES));
        assertEquals(3, ledger.rejected(Resource.GRAPH_NODES));
    }

    @Test
    void threeColoniesEachHaveAnEqualNormalShareWithoutBreakingGlobalCap() {
        AdmissionLedger ledger = ledger(SimulationLimits.development().withResource(Resource.GRAPH_EDGES, 24));
        try (var first = ledger.reserve(FIRST, Lane.NORMAL, Map.of(Resource.GRAPH_EDGES, 6));
             var second = ledger.reserve(SECOND, Lane.NORMAL, Map.of(Resource.GRAPH_EDGES, 6));
             var third = ledger.reserve(THIRD, Lane.NORMAL, Map.of(Resource.GRAPH_EDGES, 6))) {
            assertEquals(18, ledger.used(Resource.GRAPH_EDGES, Lane.NORMAL));
            assertThrows(AdmissionLedger.AdmissionException.class,
                    () -> ledger.reserve(FIRST, Lane.NORMAL, Map.of(Resource.GRAPH_EDGES, 1)));
        }
    }

    @Test
    void reducingLimitsPreservesAcceptedObjectsAndClosingRemainsPossible() {
        AdmissionLedger ledger = ledger(SimulationLimits.development());
        var accepted = ledger.reserve(FIRST, Lane.NORMAL, Map.of(Resource.CITIZENS, 20));
        ledger.updateLimits(ledger.limits().withResource(Resource.CITIZENS, 5));
        assertEquals(20, ledger.used(Resource.CITIZENS));
        assertEquals(15, ledger.overLimit(Resource.CITIZENS));
        assertThrows(AdmissionLedger.AdmissionException.class,
                () -> ledger.reserve(SECOND, Lane.NORMAL, Map.of(Resource.CITIZENS, 1)));
        accepted.close();
        accepted.close();
        assertEquals(0, ledger.used(Resource.CITIZENS));
        try (var next = ledger.reserve(SECOND, Lane.NORMAL, Map.of(Resource.CITIZENS, 5))) {
            assertEquals(5, ledger.used(Resource.CITIZENS));
        }
        assertEquals(20, ledger.highWater(Resource.CITIZENS));
    }

    @Test
    void rootReservesCleanupEvenWhenRequestedStateAlreadyUsesServiceLane() {
        AdmissionLedger ledger = ledger(SimulationLimits.development().withResource(Resource.WORKS, 16));
        try (var root = ledger.reserveRoot(FIRST, Lane.SERVICE, Map.of(Resource.WORKS, 1))) {
            assertEquals(2, ledger.used(Resource.WORKS, Lane.SERVICE));
            assertThrows(AdmissionLedger.AdmissionException.class,
                    () -> ledger.reserveRoot(SECOND, Lane.NORMAL, Map.of(Resource.WORKS, 1)));
            assertEquals(0, ledger.used(Resource.WORKS, Lane.NORMAL));
        }
    }
    @Test
    void readySlotMovesAcrossOwnersWithoutGrowingGlobalStateAndCannotBorrowReservedLanes() {
        AdmissionLedger ledger = ledger(SimulationLimits.development().withResource(Resource.READY_ENTRIES, 8));
        try (var firstRoot = ledger.reserveRoot(FIRST, Lane.NORMAL, Map.of(Resource.WORKS, 1));
             var secondRoot = ledger.reserveRoot(SECOND, Lane.NORMAL, Map.of(Resource.WORKS, 1));
             var critical = ledger.reserve(SECOND, Lane.CRITICAL, Map.of(Resource.READY_ENTRIES, 1));
             var ready = ledger.reserve(FIRST, Lane.NORMAL, Map.of(Resource.READY_ENTRIES, 1))) {
            assertTrue(ready.transfer(SECOND, Lane.NORMAL));
            assertFalse(ready.transfer(SECOND, Lane.CRITICAL));
            assertEquals(2, ledger.used(Resource.READY_ENTRIES));
            assertEquals(1, ledger.used(Resource.READY_ENTRIES, Lane.NORMAL));
            assertEquals(1, ledger.used(Resource.READY_ENTRIES, Lane.CRITICAL));
            assertTrue(ready.transfer(FIRST, Lane.NORMAL));
            critical.close();
            assertTrue(ready.transfer(SECOND, Lane.CRITICAL));
            assertEquals(0, ledger.used(Resource.READY_ENTRIES, Lane.NORMAL));
            assertEquals(1, ledger.used(Resource.READY_ENTRIES, Lane.CRITICAL));
        }
        assertEquals(0, ledger.used(Resource.READY_ENTRIES));
    }

    @Test
    void cacheOwnerLimitCannotBypassGlobalAccountingAndEmptyLeasesCannotGrowUnboundedOwners() {
        AdmissionLedger ledger = ledger(SimulationLimits.development());
        assertThrows(IllegalArgumentException.class, () -> ledger.reserve(FIRST, Lane.NORMAL, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.reserve(FIRST, Lane.NORMAL, Map.of(Resource.CACHE_ENTRIES_PER_OWNER, 1)));
        try (var cached = ledger.reserve(FIRST, Lane.NORMAL,
                Map.of(Resource.CACHE_ENTRIES_PER_OWNER, 128, Resource.CACHE_ENTRIES, 128))) {
            assertThrows(AdmissionLedger.AdmissionException.class, () -> ledger.reserve(FIRST, Lane.NORMAL,
                    Map.of(Resource.CACHE_ENTRIES_PER_OWNER, 1, Resource.CACHE_ENTRIES, 1)));
            assertEquals(128, ledger.used(Resource.CACHE_ENTRIES));
        }
    }

    @Test
    void queuePreflightDenialCountsPressureWithoutChangingOccupancy() {
        AdmissionLedger ledger = ledger(SimulationLimits.development().withResource(Resource.READY_ENTRIES, 8));
        Map<Resource, Integer> slot = Map.of(Resource.READY_ENTRIES, 1);
        try (var admitted = ledger.reserve(FIRST, Lane.CRITICAL, slot)) {
            assertFalse(ledger.canReserve(SECOND, Lane.CRITICAL, slot));
            assertEquals(1, ledger.used(Resource.READY_ENTRIES));
            assertEquals(1, ledger.rejected(Resource.READY_ENTRIES));
            assertTrue(ledger.canReserve(SECOND, Lane.SERVICE, slot));
            assertEquals(1, ledger.rejected(Resource.READY_ENTRIES));
        }
        assertTrue(ledger.canReserve(SECOND, Lane.CRITICAL, slot));
    }

    @Test
    void chunkFootprintHasSeparateServiceReserveButCriticalAndNormalShareActiveSpace() {
        AdmissionLedger ledger = ledger(SimulationLimits.development().withResource(Resource.LOADED_FOOTPRINT, 16));
        try (var normal = ledger.reserve(FIRST, Lane.NORMAL, Map.of(Resource.LOADED_FOOTPRINT, 10));
             var critical = ledger.reserve(SECOND, Lane.CRITICAL, Map.of(Resource.LOADED_FOOTPRINT, 4))) {
            assertFalse(ledger.canReserve(THIRD, Lane.NORMAL, Map.of(Resource.LOADED_FOOTPRINT, 1)));
            try (var service = ledger.reserve(THIRD, Lane.SERVICE, Map.of(Resource.LOADED_FOOTPRINT, 2))) {
                assertEquals(16, ledger.used(Resource.LOADED_FOOTPRINT));
            }
        }
    }

    @Test void sharedFootprintsKeepIndependentPhysicalLaneAndColonyUnions() {
        for (Lane first : Lane.values()) for (Lane second : Lane.values()) {
            var ledger = ledger(SimulationLimits.development().withResource(Resource.LOADED_FOOTPRINT, 8));
            var cell = ledger.footprint(Resource.LOADED_FOOTPRINT);
            cell.retain(FIRST, first); cell.retain(FIRST, first); cell.retain(SECOND, second);
            assertEquals(1, ledger.used(Resource.LOADED_FOOTPRINT));
            assertEquals(1, ledger.highWater(Resource.LOADED_FOOTPRINT));
            assertEquals(1, ledger.used(FIRST, Resource.LOADED_FOOTPRINT));
            assertEquals(1, ledger.used(SECOND, Resource.LOADED_FOOTPRINT));
            assertEquals(1, ledger.used(FIRST, Resource.LOADED_FOOTPRINT, first));
            assertEquals(1, ledger.used(SECOND, Resource.LOADED_FOOTPRINT, second));
            cell.release(FIRST, first); assertEquals(1, ledger.used(FIRST, Resource.LOADED_FOOTPRINT));
            cell.release(FIRST, first);
            assertEquals(0, ledger.used(FIRST, Resource.LOADED_FOOTPRINT));
            assertEquals(1, ledger.used(Resource.LOADED_FOOTPRINT, second));
            assertEquals(second == Lane.SERVICE ? 0 : 1, ledger.ordinaryFootprint());
            cell.release(SECOND, second);
            assertEquals(0, ledger.used(Resource.LOADED_FOOTPRINT));
            assertEquals(0, ledger.ordinaryFootprint());
            for (Lane lane : Lane.values()) assertEquals(0, ledger.used(Resource.LOADED_FOOTPRINT, lane));
        }
    }

    @Test void sameColonyCrossLaneShareIsAUnionAndReserveCannotBeBypassedBySharing() {
        var ledger = ledger(SimulationLimits.development().withResource(Resource.LOADED_FOOTPRINT, 8));
        var shared = ledger.footprint(Resource.LOADED_FOOTPRINT);
        shared.retain(FIRST, Lane.NORMAL); shared.retain(FIRST, Lane.CRITICAL); shared.retain(FIRST, Lane.SERVICE);
        assertEquals(1, ledger.used(FIRST, Resource.LOADED_FOOTPRINT));
        assertEquals(1, ledger.ordinaryFootprint());
        assertEquals(1, ledger.used(Resource.LOADED_FOOTPRINT, Lane.NORMAL));
        assertEquals(1, ledger.used(Resource.LOADED_FOOTPRINT, Lane.CRITICAL));
        var other = ledger.footprint(Resource.LOADED_FOOTPRINT);
        other.retain(SECOND, Lane.NORMAL);
        assertFalse(ledger.canReserveFootprint(Lane.SERVICE, 0, 0, 0, 0, 1));
        assertThrows(AdmissionLedger.AdmissionException.class, () -> other.retain(SECOND, Lane.SERVICE));
        assertEquals(2, ledger.used(Resource.LOADED_FOOTPRINT));
        assertEquals(1, ledger.used(SECOND, Resource.LOADED_FOOTPRINT));
        ledger.updateLimits(ledger.limits().withResource(Resource.LOADED_FOOTPRINT, 1));
        assertEquals(1, ledger.overLimit(Resource.LOADED_FOOTPRINT));
        assertFalse(ledger.canReserveFootprint(Lane.NORMAL, 0, 0, 0, 0, 0));
        shared.release(FIRST, Lane.NORMAL); shared.release(FIRST, Lane.CRITICAL); shared.release(FIRST, Lane.SERVICE);
        other.release(SECOND, Lane.NORMAL);
        assertEquals(0, ledger.used(FIRST, Resource.LOADED_FOOTPRINT));
        assertEquals(0, ledger.used(SECOND, Resource.LOADED_FOOTPRINT));
        assertEquals(0, ledger.ordinaryFootprint());
        assertEquals(2, ledger.highWater(Resource.LOADED_FOOTPRINT));
    }

    @Test void sharedFootprintPreflightIsAtomicAndRetainsCriticalBlockade() {
        var ledger = ledger(SimulationLimits.development().withResource(Resource.CHUNK_DEMANDS, 8));
        try (var occupied = ledger.reserve(FIRST, Lane.CRITICAL, Map.of(Resource.CHUNK_DEMANDS, 8))) {
            assertFalse(ledger.canReserve(SECOND, Lane.CRITICAL, Map.of(Resource.CHUNK_DEMANDS, 1)));
            assertFalse(ledger.canReserveFootprint(Lane.NORMAL, 25, 9, 1, 25, 0));
            assertFalse(ledger.footprintBlockadeClears(Lane.NORMAL, new int[3], new int[3]));
            assertEquals(0, ledger.used(Resource.LOADED_FOOTPRINT));
        }
        assertTrue(ledger.canReserveFootprint(Lane.NORMAL, 25, 9, 1, 25, 0));
        assertFalse(ledger.canReserveFootprint(Lane.NORMAL, 25, 9, 100, 25, 0));
        assertEquals(0, ledger.used(Resource.LOADED_FOOTPRINT));
        assertEquals(0, ledger.used(Resource.BLOCK_TICKING));
        assertEquals(0, ledger.highWater(Resource.LOADED_FOOTPRINT));
    }

    @Test
    void registeredColoniesKeepTheirGuaranteedSharesOnEveryNewNormalAdmission() {
        AdmissionLedger ledger = ledger(SimulationLimits.development().withResource(Resource.GRAPH_NODES, 24));
        try (var firstColony = ledger.reserve(FIRST, Lane.NORMAL, Map.of(Resource.COLONIES, 1));
             var secondColony = ledger.reserve(SECOND, Lane.NORMAL, Map.of(Resource.COLONIES, 1));
             var thirdColony = ledger.reserve(THIRD, Lane.NORMAL, Map.of(Resource.COLONIES, 1))) {
            assertFalse(ledger.canReserve(FIRST, Lane.NORMAL, Map.of(Resource.GRAPH_NODES, 7)));
            assertThrows(AdmissionLedger.AdmissionException.class,
                    () -> ledger.reserve(FIRST, Lane.NORMAL, Map.of(Resource.GRAPH_NODES, 12)));
            try (var first = ledger.reserve(FIRST, Lane.NORMAL, Map.of(Resource.GRAPH_NODES, 6));
                 var second = ledger.reserve(SECOND, Lane.NORMAL, Map.of(Resource.GRAPH_NODES, 6));
                 var third = ledger.reserve(THIRD, Lane.NORMAL, Map.of(Resource.GRAPH_NODES, 6))) {
                assertEquals(18, ledger.used(Resource.GRAPH_NODES, Lane.NORMAL));
            }
            thirdColony.close();
            try (var borrowed = ledger.reserve(FIRST, Lane.NORMAL, Map.of(Resource.GRAPH_NODES, 12));
                 var guaranteed = ledger.reserve(SECOND, Lane.NORMAL, Map.of(Resource.GRAPH_NODES, 6))) {
                assertEquals(18, ledger.used(Resource.GRAPH_NODES, Lane.NORMAL));
            }
        }
    }

    @Test
    void finishingEveryRootLaneReclaimsWaitsAndCleanupButRetainsOneTerminalRecord() {
        for (Lane lane : Lane.values()) {
            AdmissionLedger ledger = ledger(SimulationLimits.development());
            try (var root = ledger.reserveRoot(FIRST, lane,
                    Map.of(Resource.WORKS, 1, Resource.WAIT_REGISTRATIONS, 3))) {
                assertEquals(2, ledger.used(Resource.WORKS));
                assertEquals(4, ledger.used(Resource.WAIT_REGISTRATIONS));
                root.finishRoot();
                root.finishRoot();
                assertEquals(1, ledger.used(Resource.WORKS));
                assertEquals(1, ledger.used(Resource.WORKS, lane));
                assertEquals(0, ledger.used(Resource.WAIT_REGISTRATIONS));
                assertFalse(root.closed());
            }
            assertEquals(0, ledger.used(Resource.WORKS));
        }
    }

    @Test
    void partialReleaseIsIdempotentAndDoesNotRemoveOtherAccountedResources() {
        AdmissionLedger ledger = ledger(SimulationLimits.development());
        var lease = ledger.reserve(FIRST, Lane.NORMAL,
                Map.of(Resource.CITIZENS, 1, Resource.TOMBSTONES, 1));
        lease.release(Resource.CITIZENS, Lane.NORMAL);
        lease.release(Resource.CITIZENS);
        assertEquals(0, ledger.used(Resource.CITIZENS));
        assertEquals(1, ledger.used(Resource.TOMBSTONES));
        assertFalse(lease.closed());
        lease.release(Resource.TOMBSTONES);
        assertTrue(lease.closed());
        lease.close();
        lease.release(Resource.TOMBSTONES);
        assertEquals(0, ledger.used(Resource.TOMBSTONES));
    }

    @Test
    void criticalRootsRetainTheirOwnCleanupWhenNormalRootsFillServiceReserve() {
        AdmissionLedger ledger = ledger(SimulationLimits.development().withResource(Resource.WORKS, 32));
        try (var first = ledger.reserveRoot(FIRST, Lane.NORMAL, Map.of(Resource.WORKS, 1));
             var second = ledger.reserveRoot(FIRST, Lane.NORMAL, Map.of(Resource.WORKS, 1));
             var third = ledger.reserveRoot(FIRST, Lane.NORMAL, Map.of(Resource.WORKS, 1));
             var fourth = ledger.reserveRoot(FIRST, Lane.NORMAL, Map.of(Resource.WORKS, 1));
             var food = ledger.reserveRoot(FIRST, Lane.CRITICAL, Map.of(Resource.WORKS, 1));
             var delivery = ledger.reserveRoot(FIRST, Lane.CRITICAL, Map.of(Resource.WORKS, 1))) {
            assertEquals(4, ledger.used(Resource.WORKS, Lane.SERVICE));
            assertEquals(4, ledger.used(Resource.WORKS, Lane.CRITICAL));
            delivery.finishRoot(); food.finishRoot();
            assertEquals(2, ledger.used(Resource.WORKS, Lane.CRITICAL));
            assertEquals(4, ledger.used(Resource.WORKS, Lane.SERVICE));
        }
        assertEquals(0, ledger.used(Resource.WORKS));
    }

    @Test
    void criticalCapacityViolationDeniesNewNormalUntilCriticalStateIsReleased() {
        AdmissionLedger ledger = ledger(SimulationLimits.development().withResource(Resource.GRAPH_NODES, 8));
        try (var critical = ledger.reserve(FIRST, Lane.CRITICAL, Map.of(Resource.GRAPH_NODES, 1))) {
            assertThrows(AdmissionLedger.AdmissionException.class,
                    () -> ledger.reserve(SECOND, Lane.CRITICAL, Map.of(Resource.GRAPH_NODES, 1)));
            assertTrue(ledger.normalAdmissionBlocked()); assertEquals(1, ledger.criticalCapacityViolations());
            var failure = assertThrows(AdmissionLedger.AdmissionException.class,
                    () -> ledger.reserveRoot(SECOND, Lane.NORMAL, Map.of(Resource.WORKS, 1)));
            assertEquals(AdmissionLedger.Reason.CRITICAL_CAPACITY, failure.reason());
            assertEquals(1, ledger.criticalCapacityViolations()); assertEquals(0, ledger.used(Resource.WORKS));
            critical.close();
            assertFalse(ledger.normalAdmissionBlocked());
            try (var admitted = ledger.reserveRoot(SECOND, Lane.NORMAL, Map.of(Resource.WORKS, 1))) {
                assertEquals(2, ledger.used(Resource.WORKS));
            }
        }
    }

}
