package io.github.kpuctajluk.colonyloom.core.chunk;

import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager.Readiness;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ChunkDemandManagerTest {
    private static final UUID COLONY = new UUID(0, 1000);
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3);
    private record Held(UUID colony, ChunkKey center, boolean ticking) {}
    private static final class Access implements ChunkDemandManager.ChunkAccess {
        final Set<Held> held = new HashSet<>();
        final Set<ChunkKey> unavailable = new HashSet<>();
        int starts, releases;
        boolean accept = true;
        @Override public boolean acquire(UUID colony, ChunkKey center, Readiness readiness) {
            starts++;
            return accept && held.add(new Held(colony, center, readiness != Readiness.LOADED));
        }
        @Override public void release(UUID colony, ChunkKey center, Readiness readiness) {
            assertTrue(held.remove(new Held(colony, center, readiness != Readiness.LOADED)));
            releases++;
        }
        @Override public boolean ready(ChunkKey key, Readiness readiness) { return !unavailable.contains(key); }
    }
    private static final class Fixture {
        final ColonyRegistry registry = new ColonyRegistry(() -> {});
        final Access access = new Access();
        final GlobalWorkBudgets budgets;
        final ChunkDemandManager manager;
        long tick;
        Fixture(int footprint, int block, int entity) {
            SimulationLimits limits = SimulationLimits.development().withResource(Resource.LOADED_FOOTPRINT, footprint)
                    .withResource(Resource.BLOCK_TICKING, block).withResource(Resource.ENTITY_TICKING, entity);
            registry.admission().updateLimits(limits);
            budgets = new GlobalWorkBudgets(limits, () -> 0L);
            manager = new ChunkDemandManager(registry, budgets, access);
        }
        void tick() { budgets.beginTick(++tick); manager.tick(tick); }
        void ticks(int count) { for (int i = 0; i < count; i++) tick(); }
        void request(UUID owner, int x, Lane lane, boolean dependency) {
            manager.request(owner, COLONY, List.of(key(x)), Readiness.ENTITY_TICKING, lane, 0, dependency);
        }
        void limits(int footprint) {
            SimulationLimits limits = budgets.limits().withResource(Resource.LOADED_FOOTPRINT, footprint);
            registry.admission().updateLimits(limits); budgets.updateLimits(limits); manager.limitsUpdated();
        }
    }
    private static ChunkKey key(int x) { return new ChunkKey("minecraft:overworld", x, 0); }

    @Test void sameChunkTwoOwnersShareTicketAndRemovingOneRetainsOther() {
        Fixture f = new Fixture(58, 18, 2);
        f.request(A, 0, Lane.NORMAL, false); f.request(B, 0, Lane.NORMAL, false);
        assertFalse(f.manager.admitted(A)); assertEquals(0, f.access.starts);
        f.ticks(3);
        assertTrue(f.manager.ready(A)); assertTrue(f.manager.ready(B));
        assertEquals(25, f.manager.footprint()); assertEquals(9, f.manager.blockTicking());
        assertEquals(1, f.manager.entityTicking()); assertEquals(1, f.access.starts);
        f.manager.release(A);
        assertTrue(f.manager.ready(B)); assertEquals(0, f.access.releases); assertEquals(25, f.manager.footprint());
        f.manager.release(B);
        assertEquals(1, f.access.releases); assertEquals(0, f.manager.footprint());
        assertEquals(0, f.registry.admission().used(Resource.CHUNK_DEMANDS));
    }

    @Test void differentColoniesShareGlobalFootprintButKeepIndependentTicketOwnership() {
        Fixture f = new Fixture(29, 9, 1);
        f.request(A, 0, Lane.NORMAL, false);
        UUID other = new UUID(0, 2000);
        f.manager.request(B, other, List.of(key(0)), Readiness.ENTITY_TICKING, Lane.NORMAL, 0, false);
        f.ticks(3);
        assertEquals(25, f.manager.footprint()); assertEquals(2, f.access.held.size());
        f.manager.release(A);
        assertEquals(Set.of(new Held(other, key(0), true)), f.access.held);
        assertTrue(f.manager.ready(B)); assertEquals(25, f.manager.footprint());
        f.manager.release(B); assertTrue(f.access.held.isEmpty());
    }

    @Test void everyCrossColonyLaneOrderChargesCurrentSharesAndDeduplicatesSameScope() {
        UUID other = new UUID(0, 2000);
        for (Lane first : Lane.values()) for (Lane second : Lane.values()) {
            Fixture f = new Fixture(200, 9, 1);
            f.manager.request(A, COLONY, List.of(key(0)), Readiness.ENTITY_TICKING, first, 0, false);
            f.ticks(3);
            f.manager.request(B, other, List.of(key(0)), Readiness.ENTITY_TICKING, second, 0, false);
            f.manager.request(C, other, List.of(key(0)), Readiness.ENTITY_TICKING, second, 0, false);
            f.ticks(20);
            assertTrue(f.manager.ready(A)); assertTrue(f.manager.ready(B)); assertTrue(f.manager.ready(C));
            var ledger = f.registry.admission();
            assertEquals(25, f.manager.footprint()); assertEquals(9, f.manager.blockTicking()); assertEquals(1, f.manager.entityTicking());
            assertEquals(2, f.access.held.size());
            Resource[] rings = {Resource.LOADED_FOOTPRINT, Resource.BLOCK_TICKING, Resource.ENTITY_TICKING};
            int[] sizes = {25, 9, 1};
            for (int r = 0; r < rings.length; r++) {
                assertEquals(sizes[r], ledger.used(COLONY, rings[r]));
                assertEquals(sizes[r], ledger.used(other, rings[r]));
                assertEquals(sizes[r], ledger.used(COLONY, rings[r], first));
                assertEquals(sizes[r], ledger.used(other, rings[r], second));
            }
            f.manager.release(A);
            assertTrue(f.manager.ready(B)); assertTrue(f.manager.ready(C));
            assertEquals(Set.of(new Held(other, key(0), true)), f.access.held);
            for (int r = 0; r < rings.length; r++) {
                assertEquals(0, ledger.used(COLONY, rings[r]));
                assertEquals(sizes[r], ledger.used(other, rings[r]));
                for (Lane lane : Lane.values()) assertEquals(lane == second ? sizes[r] : 0, ledger.used(rings[r], lane));
            }
            f.manager.release(B);
            assertTrue(f.manager.ready(C)); assertEquals(25, ledger.used(other, Resource.LOADED_FOOTPRINT));
            var diagnostic = (java.util.Map<?, ?>) f.manager.diagnostics(other).get("footprint");
            var loaded = (java.util.Map<?, ?>) diagnostic.get("LOADED_FOOTPRINT");
            assertEquals(25, loaded.get("unique")); assertEquals(25, loaded.get(second.name()));
            f.manager.release(C); f.ticks(200);
            assertTrue(f.access.held.isEmpty()); assertEquals(0, ledger.used(Resource.CHUNK_DEMANDS));
            for (Resource ring : rings) {
                assertEquals(0, ledger.used(ring)); assertEquals(0, ledger.used(other, ring));
                for (Lane lane : Lane.values()) assertEquals(0, ledger.used(ring, lane));
            }
        }
    }

    @Test void sharingCannotBypassServiceReserveInEitherAcquireOrder() {
        UUID other = new UUID(0, 2000);
        for (boolean serviceFirst : new boolean[] {false, true}) {
            Fixture f = new Fixture(200, 18, 2);
            UUID serviceOwner = serviceFirst ? A : B, normalOwner = serviceFirst ? B : A;
            UUID serviceColony = serviceFirst ? COLONY : other, normalColony = serviceFirst ? other : COLONY;
            f.manager.request(A, COLONY, List.of(key(0)), Readiness.ENTITY_TICKING, serviceFirst ? Lane.SERVICE : Lane.NORMAL, 0, false);
            f.ticks(20);
            f.manager.request(B, other, List.of(key(0)), Readiness.ENTITY_TICKING, serviceFirst ? Lane.NORMAL : Lane.SERVICE, 0, false);
            f.ticks(20);
            f.manager.setProtection(serviceOwner, false, true, false);
            f.manager.setProtection(normalOwner, false, true, false);
            f.manager.request(C, other, List.of(key(10)), Readiness.ENTITY_TICKING, Lane.SERVICE, 10, true);
            f.ticks(30);
            assertTrue(f.manager.ready(serviceOwner)); assertTrue(f.manager.ready(normalOwner)); assertFalse(f.manager.admitted(C));
            assertEquals(25, f.registry.admission().used(Resource.LOADED_FOOTPRINT, Lane.SERVICE));
            assertEquals(25, f.manager.footprint());
            f.limits(29); f.ticks(30);
            assertTrue(f.manager.ready(serviceOwner)); assertTrue(f.manager.ready(normalOwner));
            assertEquals(25, f.registry.admission().used(serviceColony, Resource.LOADED_FOOTPRINT));
            f.manager.setProtection(serviceOwner, false, false, false); f.ticks(30);
            assertFalse(f.manager.admitted(serviceOwner)); assertTrue(f.manager.ready(normalOwner));
            assertEquals(0, f.registry.admission().used(serviceColony, Resource.LOADED_FOOTPRINT));
            assertEquals(25, f.registry.admission().used(normalColony, Resource.LOADED_FOOTPRINT));
            assertEquals(25, f.manager.footprint()); assertEquals(0, f.registry.admission().used(Resource.LOADED_FOOTPRINT, Lane.SERVICE));
            f.manager.close(); f.ticks(200);
            assertTrue(f.access.held.isEmpty()); assertEquals(0, f.manager.footprint());
            assertEquals(0, f.registry.admission().used(Resource.CHUNK_DEMANDS));
        }
    }

    @Test void evictionReclaimsServiceShareEvenWhenProtectedOrdinaryTicketKeepsPhysicalRing() {
        Fixture f = new Fixture(400, 27, 3);
        UUID ordinarySecond = new UUID(0, 4), target = new UUID(0, 5);
        f.request(A, 0, Lane.NORMAL, false); f.request(B, 0, Lane.SERVICE, false);
        f.request(ordinarySecond, 10, Lane.NORMAL, false); f.request(C, 10, Lane.SERVICE, false); f.ticks(30);
        f.manager.setProtection(A, false, true, false); f.manager.setProtection(B, false, true, false);
        f.manager.setProtection(ordinarySecond, false, true, false);
        f.request(target, 20, Lane.SERVICE, true); f.ticks(30);
        assertTrue(f.manager.ready(target)); assertFalse(f.manager.admitted(C));
        assertTrue(f.manager.ready(A)); assertTrue(f.manager.ready(B)); assertTrue(f.manager.ready(ordinarySecond));
        assertEquals(75, f.manager.footprint()); assertEquals(50, f.registry.admission().ordinaryFootprint());
        assertEquals(50, f.registry.admission().used(Resource.LOADED_FOOTPRINT, Lane.SERVICE));
        assertEquals(0, f.access.releases); // Same-colony ordinary ticking ticket still owns C's old center.
        f.manager.close(); assertEquals(0, f.manager.footprint()); assertTrue(f.access.held.isEmpty());
    }

    @Test void protectedCriticalOverlapCannotBeReclaimedByEvictingItsNormalShare() {
        Fixture f = new Fixture(29, 9, 1);
        f.request(A, 0, Lane.CRITICAL, false); f.request(B, 0, Lane.NORMAL, false); f.ticks(5);
        f.request(C, 10, Lane.CRITICAL, true); f.ticks(30);
        assertTrue(f.manager.ready(A)); assertTrue(f.manager.ready(B)); assertFalse(f.manager.admitted(C));
        assertEquals(25, f.registry.admission().ordinaryFootprint()); assertEquals(0, f.access.releases);
        f.manager.close(); assertEquals(0, f.manager.footprint());
    }

    @Test void protectedCriticalExactRepeatIsIdempotentAndChangedRequestCannotReplaceIt() {
        Fixture f = new Fixture(58, 18, 2);
        f.request(A, 0, Lane.CRITICAL, false); f.ticks(3);
        int links = f.registry.admission().used(Resource.CHUNK_DEMANDS), starts = f.access.starts;
        f.request(A, 0, Lane.CRITICAL, false);
        assertThrows(IllegalStateException.class, () -> f.request(A, 10, Lane.CRITICAL, false));
        assertThrows(IllegalStateException.class, () -> f.request(A, 0, Lane.CRITICAL, true));
        assertTrue(f.manager.ready(A)); assertTrue(f.manager.admitted(key(0))); assertFalse(f.manager.admitted(key(10)));
        assertEquals(25, f.manager.footprint()); assertEquals(9, f.manager.blockTicking()); assertEquals(1, f.manager.entityTicking());
        assertEquals(links, f.registry.admission().used(Resource.CHUNK_DEMANDS)); assertEquals(starts, f.access.starts);
        assertEquals(0, f.access.releases);
        f.manager.close();
    }

    @Test void sharedProtectedBlockOverageRetainsBothColoniesUntilSafeRelease() {
        UUID other = new UUID(0, 2000);
        Fixture f = new Fixture(200, 9, 1);
        f.manager.request(A, COLONY, List.of(key(0)), Readiness.ENTITY_TICKING, Lane.NORMAL, 0, false);
        f.manager.request(B, other, List.of(key(0)), Readiness.ENTITY_TICKING, Lane.CRITICAL, 0, false);
        f.ticks(10); f.manager.setProtection(A, false, true, false);
        var limits = f.budgets.limits().withResource(Resource.BLOCK_TICKING, 1).withResource(Resource.ENTITY_TICKING, 1);
        f.registry.admission().updateLimits(limits); f.budgets.updateLimits(limits); f.manager.limitsUpdated();
        f.ticks(20);
        assertTrue(f.manager.ready(A)); assertTrue(f.manager.ready(B));
        assertEquals(9, f.manager.blockTicking()); assertEquals(1, f.manager.entityTicking());
        assertEquals(8, f.registry.admission().overLimit(Resource.BLOCK_TICKING));
        assertEquals(0, f.registry.admission().overLimit(Resource.ENTITY_TICKING));
        f.manager.setProtection(A, false, false, false); f.ticks(20);
        assertFalse(f.manager.admitted(A)); assertTrue(f.manager.ready(B));
        assertEquals(0, f.registry.admission().used(COLONY, Resource.BLOCK_TICKING));
        assertEquals(9, f.registry.admission().used(other, Resource.BLOCK_TICKING));
        assertEquals(1, f.access.releases); assertEquals(9, f.manager.blockTicking());
        f.manager.release(B); f.ticks(100);
        assertEquals(0, f.manager.footprint()); assertEquals(0, f.manager.blockTicking()); assertEquals(0, f.manager.entityTicking());
        assertTrue(f.access.held.isEmpty()); f.manager.close();
        assertEquals(0, f.registry.admission().used(Resource.CHUNK_DEMANDS));
    }

    @Test void sharedServiceAdmissionRefusalDoesNotPartiallyAttributeAnOverlappingRing() {
        UUID other = new UUID(0, 2000);
        Fixture f = new Fixture(200, 12, 2);
        f.request(A, 0, Lane.SERVICE, false); f.request(B, 1, Lane.NORMAL, false); f.ticks(20);
        f.manager.setProtection(A, false, true, false); f.manager.setProtection(B, false, true, false);
        f.manager.request(C, other, List.of(key(1)), Readiness.ENTITY_TICKING, Lane.SERVICE, 10, true);
        f.ticks(30);
        assertTrue(f.manager.ready(A)); assertTrue(f.manager.ready(B)); assertFalse(f.manager.admitted(C));
        assertEquals(30, f.manager.footprint()); assertEquals(12, f.manager.blockTicking()); assertEquals(2, f.manager.entityTicking());
        assertEquals(25, f.registry.admission().used(Resource.LOADED_FOOTPRINT, Lane.SERVICE));
        assertEquals(9, f.registry.admission().used(Resource.BLOCK_TICKING, Lane.SERVICE));
        assertEquals(1, f.registry.admission().used(Resource.ENTITY_TICKING, Lane.SERVICE));
        assertEquals(0, f.registry.admission().used(other, Resource.LOADED_FOOTPRINT));
        assertEquals(0, f.registry.admission().used(other, Resource.BLOCK_TICKING));
        assertEquals(0, f.registry.admission().used(other, Resource.ENTITY_TICKING));
        assertEquals(2, f.access.starts); assertEquals(0, f.access.releases);
        f.manager.close(); assertEquals(0, f.manager.footprint());
    }

    @Test void protectedRecipientAndIdleWorkshopYieldToSavedCourierDependencyWithoutLosingWorkshopDesire() {
        Fixture f = new Fixture(58, 18, 2);
        f.request(A, 0, Lane.NORMAL, false); f.request(C, 10, Lane.NORMAL, false);
        f.ticks(3); f.manager.setProtection(A, true, false, false);
        f.request(B, 20, Lane.NORMAL, true);
        for (int i = 0; i < 12; i++) {
            f.tick(); assertTrue(f.manager.admitted(A)); assertTrue(f.manager.footprint() <= 58);
        }
        assertTrue(f.manager.ready(B)); assertFalse(f.manager.admitted(C));
        assertEquals(ChunkDemandManager.State.WAITING, f.manager.state(C));
        assertEquals(Reason.WORKING_SET_LIMIT, f.manager.reason(C));
        f.manager.release(B); f.ticks(4);
        assertTrue(f.manager.ready(C)); assertTrue(f.manager.ready(A));
    }

    @Test void impossibleMultiCenterMinimumNeverAcquiresPartialTicket() {
        Fixture f = new Fixture(29, 18, 2);
        f.manager.request(A, COLONY, List.of(key(0), key(10)), Readiness.ENTITY_TICKING, Lane.NORMAL, 10, true);
        f.ticks(5);
        assertEquals(ChunkDemandManager.State.BLOCKED, f.manager.state(A));
        assertEquals(Reason.WORKING_SET_LIMIT, f.manager.reason(A));
        assertEquals(0, f.access.starts); assertEquals(0, f.manager.footprint());
    }

    @Test void wholeMultiCenterDomainAdmittedBeforeFirstBudgetedActualTicketAndReadinessIsNotAssumed() {
        Fixture f = new Fixture(58, 18, 2);
        f.manager.request(A, COLONY, List.of(key(0), key(10)), Readiness.ENTITY_TICKING, Lane.NORMAL, 0, false);
        f.access.unavailable.add(key(10)); f.tick();
        assertTrue(f.manager.admitted(A)); assertEquals(50, f.manager.footprint());
        assertEquals(1, f.access.starts); assertEquals(1, f.budgets.used(Budget.CHUNK_REQUESTS));
        assertFalse(f.manager.ready(A)); f.tick();
        assertEquals(2, f.access.starts); assertFalse(f.manager.ready(A));
        f.access.unavailable.clear(); assertTrue(f.manager.ready(A));
    }

    @Test void loweredCapRetainsUnsafeCargoUntilSafeAndNeverAdmitsWhileOverLimit() {
        Fixture f = new Fixture(58, 18, 2);
        f.request(A, 0, Lane.NORMAL, false); f.request(C, 10, Lane.NORMAL, false); f.ticks(3);
        f.manager.setProtection(A, true, false, false); f.manager.setProtection(C, false, true, false);
        f.limits(29); f.request(B, 20, Lane.NORMAL, true); f.ticks(5);
        assertEquals(50, f.manager.footprint()); assertEquals(21, f.registry.admission().overLimit(Resource.LOADED_FOOTPRINT));
        assertTrue(f.manager.ready(A)); assertTrue(f.manager.ready(C)); assertFalse(f.manager.admitted(B));
        f.manager.setProtection(C, false, false, false); f.ticks(5);
        assertTrue(f.manager.ready(A)); assertFalse(f.manager.admitted(C)); assertFalse(f.manager.admitted(B));
        assertEquals(25, f.manager.footprint()); assertEquals(1, f.access.releases);
        f.limits(58); f.ticks(8); assertTrue(f.manager.ready(B));
    }

    @Test void criticalAndExplicitCriticalGuardCannotBeEvictedForDependencyOrLimitReduction() {
        Fixture f = new Fixture(58, 18, 2);
        f.request(A, 0, Lane.CRITICAL, false); f.request(C, 10, Lane.NORMAL, false); f.ticks(3);
        f.manager.setProtection(C, false, false, true);
        f.request(B, 20, Lane.NORMAL, true); f.ticks(10);
        assertTrue(f.manager.ready(A)); assertTrue(f.manager.ready(C)); assertFalse(f.manager.admitted(B));
        f.limits(29); f.ticks(10);
        assertEquals(50, f.manager.footprint()); assertEquals(0, f.access.releases);
    }

    @Test void sampledActiveTimePausesOnUnknownAndDoesNotCatchUpAcrossUnsampledTicks() {
        Fixture f = new Fixture(29, 9, 1);
        f.request(A, 0, Lane.NORMAL, false); f.tick();
        assertEquals(0, f.manager.activeTicks(A)); f.ticks(3); assertEquals(3, f.manager.activeTicks(A));
        f.access.unavailable.add(key(0)); f.ticks(100); assertEquals(3, f.manager.activeTicks(A));
        f.access.unavailable.clear(); f.tick(); assertEquals(3, f.manager.activeTicks(A));
        f.tick(); assertEquals(4, f.manager.activeTicks(A));
        f.request(A, 0, Lane.NORMAL, false); assertEquals(4, f.manager.activeTicks(A));
        f.budgets.beginTick(f.tick += 100); f.manager.tick(f.tick);
        assertEquals(4, f.manager.activeTicks(A));
        f.manager.release(A); assertEquals(0, f.manager.activeTicks(A));
    }

    @Test void adjacentTicketFootprintsChargeUnionAndDimensionSeparatesCoordinates() {
        Fixture f = new Fixture(100, 30, 3);
        f.manager.request(A, COLONY, List.of(key(0), key(1)), Readiness.BLOCK_TICKING, Lane.NORMAL, 0, false);
        f.ticks(3);
        assertEquals(30, f.manager.footprint()); assertEquals(12, f.manager.blockTicking()); assertEquals(2, f.manager.entityTicking());
        f.manager.request(B, COLONY, List.of(new ChunkKey("minecraft:the_nether", 0, 0)), Readiness.LOADED, Lane.NORMAL, 0, false);
        f.ticks(2);
        assertEquals(55, f.manager.footprint()); assertEquals(12, f.manager.blockTicking());
        assertFalse(f.manager.admitted(new ChunkKey("minecraft:the_nether", 0, 0)));
        assertTrue(f.manager.admitted(key(0))); assertFalse(f.manager.admitted(key(2)));
    }
    @Test void actualTicketStartsShareWeightedGlobalBudgetAcrossAllThreeLanes() {
        Fixture f = new Fixture(4000, 1000, 100);
        for (Lane lane : Lane.values()) {
            java.util.ArrayList<ChunkKey> centers = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) centers.add(key(lane.ordinal() * 300 + i * 10));
            f.manager.request(new UUID(0, lane.ordinal() + 10), COLONY, centers, Readiness.LOADED, lane, 0, false);
        }
        int[] counts = new int[3];
        for (int i = 0; i < 20; i++) {
            f.tick();
            for (Lane lane : Lane.values()) counts[lane.ordinal()] += f.budgets.used(Budget.CHUNK_REQUESTS, lane);
        }
        assertEquals(8, counts[Lane.NORMAL.ordinal()]);
        assertEquals(10, counts[Lane.CRITICAL.ordinal()]);
        assertEquals(2, counts[Lane.SERVICE.ordinal()]);
        assertEquals(20, f.access.starts);
    }

    @Test void maintenanceVisitsRemainBoundedAndRetainDirtyDebtUnderSaturatedOwnerSet() {
        Fixture f = new Fixture(29, 9, 1);
        for (int i = 0; i < 100; i++) f.request(new UUID(0, 5000 + i), 0, Lane.NORMAL, false);
        f.tick();
        assertEquals(16, f.budgets.used(Budget.DIRTY_RESCAN_OBJECTS));
        assertFalse(f.manager.admitted(new UUID(0, 5099)));
        for(int tick=0;tick<20;tick++) { f.tick();assertTrue(f.budgets.used(Budget.DIRTY_RESCAN_OBJECTS)<=16); }
        assertTrue(f.manager.ready(new UUID(0, 5099)));
        assertEquals(1, f.access.starts); assertEquals(25, f.manager.footprint());
    }
    @Test void criticalRequestPreemptsIdleOrdinaryDomainWithoutDependencyFlag() {
        Fixture f = new Fixture(29, 9, 1);
        f.request(C, 10, Lane.NORMAL, false); f.ticks(3);
        f.request(A, 0, Lane.CRITICAL, false); f.ticks(5);
        assertTrue(f.manager.ready(A)); assertFalse(f.manager.admitted(C));
        assertEquals(25, f.manager.footprint()); assertEquals(1, f.access.releases);
    }

    @Test void failedTicketUsesBoundedRetryAndReleaseCannotStartStalePendingTicket() {
        Fixture f = new Fixture(29, 9, 1);
        f.access.accept = false; f.request(A, 0, Lane.NORMAL, false); f.tick();
        assertTrue(f.manager.admitted(A)); assertFalse(f.manager.ready(A));
        f.ticks(99); assertEquals(1, f.access.starts);
        f.manager.release(A); f.access.accept = true; f.ticks(200);
        assertEquals(1, f.access.starts); assertTrue(f.access.held.isEmpty());
        f.request(B, 0, Lane.NORMAL, false); f.tick();
        assertEquals(2, f.access.starts); assertTrue(f.manager.ready(B));
    }
    @Test void newPendingDomainReceivesTurnBeforeHundredIdleOwnerSweep() {
        Fixture f=new Fixture(100,36,4);
        for(int i=0;i<100;i++) f.request(new UUID(0,5000+i),0,Lane.NORMAL,false);
        f.ticks(100);
        f.budgets.updateLimits(f.budgets.limits().withBudget(Budget.DIRTY_RESCAN_OBJECTS,1));
        f.request(B,1,Lane.NORMAL,true);f.ticks(4);
        assertTrue(f.manager.ready(B));
        assertTrue(f.manager.footprint()<=100);assertEquals(2,f.access.starts);
        f.manager.release(B);f.ticks(4);assertEquals(1,f.access.releases);
    }
}
