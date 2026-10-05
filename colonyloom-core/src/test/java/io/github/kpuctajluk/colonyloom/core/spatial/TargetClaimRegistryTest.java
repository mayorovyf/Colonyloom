package io.github.kpuctajluk.colonyloom.core.spatial;

import io.github.kpuctajluk.colonyloom.core.building.BuildingRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.persistence.RegistrySnapshot;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets;
import io.github.kpuctajluk.colonyloom.core.spatial.TargetClaimRegistry.Snapshot;
import io.github.kpuctajluk.colonyloom.core.spatial.TargetClaimRegistry.State;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class TargetClaimRegistryTest {
    private static UUID id(long value) { return new UUID(0, value); }
    private static final UUID A = id(1), B = id(2), FIRST = id(11), SECOND = id(12), BUILDING = id(100);
    private static final String OVERWORLD = "minecraft:overworld";
    private static Snapshot target(UUID owner, UUID colony, int x, int maxX, long revision) {
        return new Snapshot(owner, colony, null, OVERWORLD, x, 64, 0, maxX, 64, 0, revision);
    }
    private static final class Fixture {
        final ColonyRegistry registry = new ColonyRegistry(() -> {});
        final GlobalWorkBudgets budgets;
        final TargetClaimRegistry claims;
        long tick;
        Fixture(SimulationLimits limits) {
            registry.admission().updateLimits(limits);
            registry.addColony(new ColonyRuntime(A, "A", new Territory(OVERWORLD, 0, 0, 31, 31), id(900), Map.of(), 0, 0, false, null, false));
            registry.addColony(new ColonyRuntime(B, "B", new Territory(OVERWORLD, 64, 0, 95, 31), id(901), Map.of(), 0, 0, false, null, false));
            budgets = new GlobalWorkBudgets(limits, () -> 0);
            claims = new TargetClaimRegistry(registry, budgets);
        }
        Fixture() { this(SimulationLimits.development()); }
        void ticks(int count) { for (int i = 0; i < count; i++) { budgets.beginTick(++tick); claims.tick(); } }
        void building() {
            RegistrySnapshot old = registry.snapshot();
            registry.restore(new RegistrySnapshot(old.colonies(),old.citizens(),List.of(new BuildingRecord(BUILDING, A, "colonyloom:workshop", new WorldPosition(OVERWORLD, 0, 64, 0), 0)),old.tombstones(),old.observations(),old.works(),old.targetClaims(),java.util.List.of(),java.util.List.of(),java.util.List.of(),io.github.kpuctajluk.colonyloom.core.storage.StorageSnapshot.empty(),io.github.kpuctajluk.colonyloom.core.supply.SupplySnapshot.empty()));
        }
    }

    private static SimulationLimits denseLimits() {
        return SimulationLimits.development().withResource(Resource.SPATIAL_INDEX_LINKS, 128 * 128);
    }

    private static List<Snapshot> denseTargets(boolean chain) {
        List<Snapshot> snapshots = new ArrayList<>();
        for (int owner = 0; owner < 128; owner++) {
            int height = owner - 64;
            snapshots.add(new Snapshot(id(1000 + owner), owner % 2 == 0 ? A : B, null, OVERWORLD,
                    -16 * 64, height, -1, 16 * 64 - 1, chain ? height + 1 : height, -1, 7));
        }
        return List.copyOf(snapshots);
    }

    @Test void denseMaximumLinkRestoreGrantsDisjointHeightsAndAccountsEveryLink() {
        Fixture f = new Fixture(denseLimits());
        List<Snapshot> snapshots = denseTargets(false);
        f.claims.restore(snapshots);
        assertEquals(snapshots, f.claims.snapshots());
        for (Snapshot snapshot : snapshots) assertTrue(f.claims.owns(snapshot.ownerId(), 7));
        assertEquals(128, f.registry.admission().used(Resource.PHYSICAL_TARGETS));
        assertEquals(128 * 128, f.registry.admission().used(Resource.SPATIAL_INDEX_LINKS));
        f.ticks(1);
        assertEquals(0, f.budgets.used(Budget.BLUEPRINT_COMPARISONS));
        f.claims.close();
        assertEquals(0, f.registry.admission().used(Resource.PHYSICAL_TARGETS));
        assertEquals(0, f.registry.admission().used(Resource.SPATIAL_INDEX_LINKS));
    }

    @Test void denseConflictChainIsFullyCheckedBeforeAtomicPublication() {
        Fixture f = new Fixture(denseLimits());
        Snapshot old = target(FIRST, A, 4096, 4096, 0);
        f.claims.restore(List.of(old));
        List<Snapshot> snapshots = denseTargets(true);
        AdmissionLedger replacement = new AdmissionLedger(denseLimits(), () -> {});
        try (TargetClaimRegistry.PreparedRestore prepared = f.claims.prepareRestore(snapshots, replacement)) {
            assertEquals(List.of(old), f.claims.snapshots());
            assertTrue(f.claims.owns(FIRST, 0));
            for (Snapshot snapshot : snapshots) assertFalse(f.claims.owns(snapshot.ownerId(), 7));
            assertEquals(1, f.registry.admission().used(Resource.PHYSICAL_TARGETS));
            assertEquals(128, replacement.used(Resource.PHYSICAL_TARGETS));
            assertEquals(128 * 128, replacement.used(Resource.SPATIAL_INDEX_LINKS));
            prepared.commit();
            assertFalse(f.claims.owns(FIRST, 0));
            assertEquals(snapshots, f.claims.snapshots());
            for (Snapshot snapshot : snapshots) {
                assertEquals(State.CONFLICT, f.claims.state(snapshot.ownerId()));
                assertFalse(f.claims.owns(snapshot.ownerId(), 7));
            }
        }
        assertEquals(0, f.registry.admission().used(Resource.PHYSICAL_TARGETS));
        assertEquals(0, f.registry.admission().used(Resource.SPATIAL_INDEX_LINKS));
        f.ticks(1);
        assertEquals(0, f.budgets.used(Budget.BLUEPRINT_COMPARISONS));
        f.claims.close();
        assertEquals(0, replacement.used(Resource.PHYSICAL_TARGETS));
        assertEquals(0, replacement.used(Resource.SPATIAL_INDEX_LINKS));
    }

    @Test void densePreparationCannotPublishAfterLiveIndexChangesAndReleasesItsReservations() {
        Fixture f = new Fixture(denseLimits());
        Snapshot old = target(FIRST, A, 4096, 4096, 0);
        Snapshot added = target(SECOND, B, 8192, 8192, 0);
        f.claims.restore(List.of(old));
        AdmissionLedger replacement = new AdmissionLedger(denseLimits(), () -> {});
        try (TargetClaimRegistry.PreparedRestore prepared = f.claims.prepareRestore(denseTargets(false), replacement)) {
            f.claims.propose(added);
            assertThrows(IllegalStateException.class, prepared::commit);
            assertTrue(f.claims.owns(FIRST, 0));
            assertEquals(State.PENDING, f.claims.state(SECOND));
            assertEquals(List.of(old, added), f.claims.snapshots());
        }
        assertEquals(0, replacement.used(Resource.PHYSICAL_TARGETS));
        assertEquals(0, replacement.used(Resource.SPATIAL_INDEX_LINKS));
        assertEquals(2, f.registry.admission().used(Resource.PHYSICAL_TARGETS));
        assertEquals(2, f.registry.admission().used(Resource.SPATIAL_INDEX_LINKS));
        f.claims.restore(List.of());
        assertEquals(List.of(), f.claims.snapshots());
        assertEquals(0, f.registry.admission().used(Resource.PHYSICAL_TARGETS));
        assertEquals(0, f.registry.admission().used(Resource.SPATIAL_INDEX_LINKS));
    }

    @Test void crossColonyOverlapCannotGrantButDisjointBlocksInOneChunkCan() {
        Fixture f = new Fixture();
        f.claims.propose(target(FIRST, A, 0, 0, 0));
        f.ticks(2);
        assertTrue(f.claims.owns(FIRST, 0));
        f.claims.propose(target(SECOND, B, 0, 0, 0));
        f.ticks(2);
        assertEquals(State.CONFLICT, f.claims.state(SECOND));
        assertFalse(f.claims.owns(SECOND, 0));
        assertTrue(f.claims.owns(FIRST, 0));
        f.claims.propose(target(SECOND, B, 1, 1, 1));
        f.ticks(2);
        assertTrue(f.claims.owns(FIRST, 0));
        assertTrue(f.claims.owns(SECOND, 1));
    }

    @Test void touchingInclusiveFacesConflictButSeparateHeightsAndDimensionsDoNot() {
        Fixture f = new Fixture();
        f.claims.restore(List.of(target(FIRST, A, 0, 16, 0), target(SECOND, B, 16, 32, 0)));
        assertEquals(State.CONFLICT, f.claims.state(FIRST));
        assertEquals(State.CONFLICT, f.claims.state(SECOND));
        Snapshot above = new Snapshot(SECOND, B, null, OVERWORLD, 16, 65, 0, 32, 65, 0, 1);
        f.claims.propose(above);
        f.ticks(3);
        assertTrue(f.claims.owns(FIRST, 0));
        assertTrue(f.claims.owns(SECOND, 1));
        f.claims.propose(new Snapshot(SECOND, B, null, "minecraft:the_nether", 0, 64, 0, 16, 64, 0, 2));
        f.ticks(3);
        assertTrue(f.claims.owns(SECOND, 2));
    }

    @Test void independentGoalsForOneBuildingConflictEvenAcrossDimensions() {
        Fixture f = new Fixture(); f.building();
        Snapshot first = new Snapshot(FIRST, A, BUILDING, OVERWORLD, 0, 64, 0, 0, 64, 0, 0);
        Snapshot second = new Snapshot(SECOND, A, BUILDING, "minecraft:the_nether", 100, 64, 100, 100, 64, 100, 0);
        f.claims.restore(List.of(first, second));
        assertEquals(State.CONFLICT, f.claims.state(FIRST));
        assertEquals(State.CONFLICT, f.claims.state(SECOND));
        f.claims.release(FIRST);
        f.ticks(2);
        assertTrue(f.claims.owns(SECOND, 0));
    }

    @Test void secondLiveBuildingGoalCannotRevokeOrShareFirstGrant() {
        Fixture f = new Fixture(); f.building();
        Snapshot first = new Snapshot(FIRST, A, BUILDING, OVERWORLD, 0, 64, 0, 0, 64, 0, 0);
        Snapshot second = new Snapshot(SECOND, A, BUILDING, "minecraft:the_nether", 100, 64, 100, 100, 64, 100, 0);
        f.claims.propose(first); f.ticks(2);
        f.claims.propose(second); f.ticks(2);
        assertTrue(f.claims.owns(FIRST, 0));
        assertEquals(State.CONFLICT, f.claims.state(SECOND));
        assertFalse(f.claims.owns(SECOND, 0));
        assertThrows(IllegalArgumentException.class, () -> f.claims.propose(new Snapshot(id(30), B, BUILDING,
                OVERWORLD, 200, 64, 0, 200, 64, 0, 0)));
        f.claims.release(FIRST); f.ticks(2);
        assertTrue(f.claims.owns(SECOND, 0));
    }

    @Test void ownerExpansionRevokesOldRevisionAndMustReArbitrate() {
        Fixture f = new Fixture();
        f.claims.restore(List.of(target(FIRST, A, 0, 0, 0), target(SECOND, B, 16, 16, 0)));
        f.claims.propose(target(FIRST, A, 0, 16, 1));
        assertFalse(f.claims.owns(FIRST, 0));
        assertFalse(f.claims.owns(FIRST, 1));
        f.ticks(3);
        assertEquals(State.CONFLICT, f.claims.state(FIRST));
        assertTrue(f.claims.owns(SECOND, 0));
        assertThrows(IllegalArgumentException.class, () -> f.claims.propose(target(FIRST, A, 0, 0, 0)));
        f.claims.release(SECOND);
        f.ticks(3);
        assertTrue(f.claims.owns(FIRST, 1));
    }

    @Test void indexMutationDuringPortionedComparisonRestartsWithoutAnotherNotification() {
        Fixture f = new Fixture(SimulationLimits.development().withBudget(Budget.BLUEPRINT_COMPARISONS, 1));
        f.claims.propose(target(FIRST, A, 0, 31, 0));
        f.ticks(3); // First chunk has been visited; final grant has not happened.
        assertEquals(State.PENDING, f.claims.state(FIRST));
        f.claims.propose(target(SECOND, B, 0, 0, 0));
        f.ticks(50);
        assertEquals(State.CONFLICT, f.claims.state(FIRST));
        assertEquals(State.CONFLICT, f.claims.state(SECOND));
        f.claims.release(SECOND);
        f.ticks(50);
        assertTrue(f.claims.owns(FIRST, 0));
    }

    @Test void restoreFindsEveryConflictedParticipantBeforeAnyCanOwn() {
        Fixture f = new Fixture();
        UUID third = id(13), independent = id(14);
        f.claims.restore(List.of(target(FIRST, A, 0, 1, 0), target(SECOND, B, 1, 2, 0),
                target(third, B, 2, 3, 0), target(independent, A, 8, 8, 0)));
        assertEquals(State.CONFLICT, f.claims.state(FIRST));
        assertEquals(State.CONFLICT, f.claims.state(SECOND));
        assertEquals(State.CONFLICT, f.claims.state(third));
        assertTrue(f.claims.owns(independent, 0));
        f.ticks(3);
        assertFalse(f.claims.owns(FIRST, 0));
        assertFalse(f.claims.owns(SECOND, 0));
        assertFalse(f.claims.owns(third, 0));
        f.claims.release(SECOND);
        f.ticks(3);
        assertTrue(f.claims.owns(FIRST, 0));
        assertTrue(f.claims.owns(third, 0));
    }

    @Test void fullTargetAndLinkCapsRejectBeforePublicationAndReleaseOnlyTheirOwner() {
        Fixture targets = new Fixture(SimulationLimits.development().withResource(Resource.PHYSICAL_TARGETS, 1));
        targets.claims.propose(target(FIRST, A, 0, 0, 0)); targets.ticks(2);
        assertThrows(AdmissionLedger.AdmissionException.class, () -> targets.claims.propose(target(SECOND, B, 1, 1, 0)));
        assertTrue(targets.claims.owns(FIRST, 0));
        assertEquals(List.of(target(FIRST, A, 0, 0, 0)), targets.claims.snapshots());
        assertEquals(1, targets.registry.admission().used(Resource.PHYSICAL_TARGETS));
        assertEquals(1, targets.registry.admission().used(Resource.SPATIAL_INDEX_LINKS));
        Fixture links = new Fixture(SimulationLimits.development().withResource(Resource.SPATIAL_INDEX_LINKS, 2));
        links.claims.restore(List.of(target(FIRST, A, 0, 0, 0), target(SECOND, B, 16, 16, 0)));
        assertThrows(AdmissionLedger.AdmissionException.class, () -> links.claims.propose(target(FIRST, A, 0, 31, 1)));
        assertTrue(links.claims.owns(FIRST, 0));
        links.claims.release(FIRST); links.claims.release(FIRST);
        assertTrue(links.claims.owns(SECOND, 0));
        assertEquals(1, links.registry.admission().used(Resource.PHYSICAL_TARGETS));
        assertEquals(1, links.registry.admission().used(Resource.SPATIAL_INDEX_LINKS));
    }

    @Test void loweredCapsDrainByExplicitReleaseWithoutErasingAcceptedClaims() {
        Fixture f = new Fixture();
        f.claims.restore(List.of(target(FIRST, A, 0, 31, 0), target(SECOND, B, 32, 32, 0)));
        f.registry.admission().updateLimits(SimulationLimits.development()
                .withResource(Resource.PHYSICAL_TARGETS, 1).withResource(Resource.SPATIAL_INDEX_LINKS, 1));
        assertEquals(1, f.registry.admission().overLimit(Resource.PHYSICAL_TARGETS));
        assertEquals(2, f.registry.admission().overLimit(Resource.SPATIAL_INDEX_LINKS));
        f.claims.propose(target(FIRST, A, 0, 0, 1)); f.ticks(3);
        assertTrue(f.claims.owns(FIRST, 1));
        assertTrue(f.claims.owns(SECOND, 0));
        assertThrows(AdmissionLedger.AdmissionException.class, () -> f.claims.propose(target(id(20), B, 100, 100, 0)));
        f.claims.release(FIRST);
        assertEquals(0, f.registry.admission().overLimit(Resource.PHYSICAL_TARGETS));
        assertEquals(0, f.registry.admission().overLimit(Resource.SPATIAL_INDEX_LINKS));
        assertTrue(f.claims.owns(SECOND, 0));
    }

    @Test void invalidRestoreAndAbandonedPreparationKeepLiveAuthorizationAndAccounting() {
        Fixture f = new Fixture();
        Snapshot old = target(FIRST, A, 0, 0, 0);
        f.claims.restore(List.of(old));
        assertThrows(IllegalArgumentException.class, () -> f.claims.restore(List.of(target(SECOND, B, 16, 16, 0), target(id(19), id(999), 32, 32, 0))));
        assertThrows(IllegalArgumentException.class, () -> f.claims.restore(List.of(old, old)));
        assertTrue(f.claims.owns(FIRST, 0));
        AdmissionLedger replacement = new AdmissionLedger(SimulationLimits.development(), () -> {});
        try (TargetClaimRegistry.PreparedRestore ignored = f.claims.prepareRestore(List.of(target(SECOND, B, 16, 16, 0)), replacement)) {
            assertTrue(f.claims.owns(FIRST, 0));
        }
        assertEquals(0, replacement.used(Resource.PHYSICAL_TARGETS));
        assertEquals(0, replacement.used(Resource.SPATIAL_INDEX_LINKS));
        assertEquals(List.of(old), f.claims.snapshots());
        assertEquals(1, f.registry.admission().used(Resource.PHYSICAL_TARGETS));
        assertEquals(1, f.registry.admission().used(Resource.SPATIAL_INDEX_LINKS));
    }

    @Test void failedBatchAdmissionRollsBackAllStagedMemory() {
        Fixture f = new Fixture();
        f.claims.restore(List.of(target(FIRST, A, 0, 0, 0)));
        AdmissionLedger replacement = new AdmissionLedger(SimulationLimits.development().withResource(Resource.PHYSICAL_TARGETS, 1), () -> {});
        assertThrows(AdmissionLedger.AdmissionException.class, () -> f.claims.prepareRestore(
                List.of(target(FIRST, A, 0, 0, 0), target(SECOND, B, 16, 16, 0)), replacement));
        assertTrue(f.claims.owns(FIRST, 0));
        assertEquals(0, replacement.used(Resource.PHYSICAL_TARGETS));
        assertEquals(0, replacement.used(Resource.SPATIAL_INDEX_LINKS));
    }

    @Test void snapshotsRestorePendingGoalsWithoutPersistingUntrustedGrantState() {
        Fixture source = new Fixture(SimulationLimits.development().withBudget(Budget.BLUEPRINT_COMPARISONS, 1));
        source.claims.propose(target(FIRST, A, 0, 31, 7));
        source.ticks(1);
        List<Snapshot> saved = source.claims.snapshots();
        assertThrows(UnsupportedOperationException.class, () -> saved.clear());
        Fixture restored = new Fixture();
        restored.claims.restore(saved);
        assertTrue(restored.claims.owns(FIRST, 7));
        assertFalse(restored.claims.owns(FIRST, 6));
        source.claims.release(FIRST);
        assertEquals(List.of(target(FIRST, A, 0, 31, 7)), restored.claims.snapshots());
        assertTrue(restored.claims.owns(FIRST, 7));
    }

    @Test void comparisonBudgetExhaustionRetainsProgressUntilAnotherTick() {
        Fixture f = new Fixture(SimulationLimits.development().withBudget(Budget.BLUEPRINT_COMPARISONS, 1));
        f.claims.propose(target(FIRST, A, 0, 63, 0));
        f.ticks(1);
        assertEquals(State.PENDING, f.claims.state(FIRST));
        for (int i = 0; i < 20; i++) f.claims.tick();
        assertFalse(f.claims.owns(FIRST, 0));
        f.ticks(20);
        assertTrue(f.claims.owns(FIRST, 0));
    }

    @Test void thirtyGrantedClaimsLeaveFrozenFourUnitBudgetForConstructionConsumers() {
        Fixture f = new Fixture(SimulationLimits.development().withBudget(Budget.BLUEPRINT_COMPARISONS, 4));
        for (int owner = 0; owner < 30; owner++) {
            f.claims.propose(target(id(1000 + owner), A, owner * 32, owner * 32, 0));
        }
        f.ticks(23);
        for (int owner = 0; owner < 30; owner++) assertTrue(f.claims.owns(id(1000 + owner), 0));
        // Each one-chunk claim still pays for its own candidate, bucket end, and grant.
        assertEquals(90, f.budgets.totalConsumed(Budget.BLUEPRINT_COMPARISONS));
        int[] constructionCursors = new int[30];
        int consumerCursor = 0;
        for (int tick = 0; tick < 30; tick++) {
            f.ticks(1);
            assertEquals(0, f.budgets.used(Budget.BLUEPRINT_COMPARISONS));
            for (int unit = 0; unit < 4; unit++) {
                assertTrue(f.budgets.tryConsume(Budget.BLUEPRINT_COMPARISONS, AdmissionLedger.Lane.NORMAL));
                constructionCursors[consumerCursor]++;
                consumerCursor = (consumerCursor + 1) % constructionCursors.length;
            }
            assertFalse(f.budgets.tryConsume(Budget.BLUEPRINT_COMPARISONS, AdmissionLedger.Lane.NORMAL));
        }
        for (int cursor : constructionCursors) assertEquals(4, cursor);
    }

    @Test void widePendingClaimCannotMonopolizeFourUnitRoundRobinService() {
        Fixture f = new Fixture(SimulationLimits.development().withBudget(Budget.BLUEPRINT_COMPARISONS, 4));
        f.claims.propose(target(FIRST, A, 0, 16 * 128 - 1, 0));
        for (int owner = 0; owner < 29; owner++) {
            f.claims.propose(target(id(1000 + owner), A, 4096 + owner * 32, 4096 + owner * 32, 0));
        }
        f.ticks(23);
        assertEquals(State.PENDING, f.claims.state(FIRST));
        for (int owner = 0; owner < 29; owner++) assertTrue(f.claims.owns(id(1000 + owner), 0));
        assertEquals(92, f.budgets.totalConsumed(Budget.BLUEPRINT_COMPARISONS));
        f.ticks(64);
        assertTrue(f.claims.owns(FIRST, 0));
        f.ticks(1);
        assertEquals(0, f.budgets.used(Budget.BLUEPRINT_COMPARISONS));
    }

    @Test void dormantConflictsSpendNothingAndReopenOnProposalAndRelease() {
        Fixture f = new Fixture(SimulationLimits.development().withBudget(Budget.BLUEPRINT_COMPARISONS, 4));
        UUID third = id(13), fourth = id(14);
        f.claims.restore(List.of(target(FIRST, A, 0, 0, 0), target(SECOND, B, 0, 0, 0),
                target(third, A, 32, 32, 0), target(fourth, B, 32, 32, 0)));
        f.ticks(1);
        assertEquals(0, f.budgets.used(Budget.BLUEPRINT_COMPARISONS));
        f.claims.propose(target(SECOND, B, 64, 64, 1));
        // Remove a reactivated conflict before it receives service, then mutate again.
        f.claims.release(third);
        f.ticks(20);
        assertTrue(f.claims.owns(FIRST, 0));
        assertTrue(f.claims.owns(SECOND, 1));
        assertTrue(f.claims.owns(fourth, 0));
        f.ticks(1);
        assertEquals(0, f.budgets.used(Budget.BLUEPRINT_COMPARISONS));
        // New conflicting debt must not revoke a stable grant or recur once resolved.
        f.claims.propose(target(third, A, 64, 64, 1));
        f.ticks(5);
        assertEquals(State.CONFLICT, f.claims.state(third));
        assertTrue(f.claims.owns(SECOND, 1));
        f.ticks(1);
        assertEquals(0, f.budgets.used(Budget.BLUEPRINT_COMPARISONS));
        f.claims.release(SECOND);
        f.ticks(5);
        assertTrue(f.claims.owns(third, 1));
        f.ticks(1);
        assertEquals(0, f.budgets.used(Budget.BLUEPRINT_COMPARISONS));
    }

    @Test void mutationDuringPortionedComparisonReopensAlreadyDormantConflictDebt() {
        Fixture f = new Fixture(SimulationLimits.development().withBudget(Budget.BLUEPRINT_COMPARISONS, 1));
        UUID third = id(13);
        f.claims.restore(List.of(target(FIRST, A, 0, 0, 0), target(SECOND, B, 0, 0, 0)));
        f.claims.propose(target(third, A, 64, 95, 0));
        f.ticks(1);
        assertEquals(State.PENDING, f.claims.state(third));
        f.claims.release(SECOND);
        f.ticks(20);
        assertTrue(f.claims.owns(FIRST, 0));
        assertTrue(f.claims.owns(third, 0));
        f.ticks(1);
        assertEquals(0, f.budgets.used(Budget.BLUEPRINT_COMPARISONS));
    }

    @Test void failedMutationBarrierCannotRevokeGrantOrLeakExpandedLinks() {
        Fixture f = new Fixture();
        Snapshot original = target(FIRST, A, 0, 0, 0);
        f.claims.restore(List.of(original));
        f.registry.setBeforeMutation(() -> { throw new IllegalStateException("Session marker unavailable"); });
        assertThrows(IllegalStateException.class, () -> f.claims.propose(target(FIRST, A, 0, 31, 1)));
        assertThrows(IllegalStateException.class, () -> f.claims.release(FIRST));
        assertTrue(f.claims.owns(FIRST, 0));
        assertEquals(List.of(original), f.claims.snapshots());
        assertEquals(1, f.registry.admission().used(Resource.PHYSICAL_TARGETS));
        assertEquals(1, f.registry.admission().used(Resource.SPATIAL_INDEX_LINKS));
    }

    @Test void negativeChunkBoundariesAndInvalidSnapshotsCannotLeakAdmission() {
        Fixture f = new Fixture();
        Snapshot negative = target(FIRST, A, -1, 0, 0);
        f.claims.propose(negative); f.ticks(2);
        assertTrue(f.claims.owns(FIRST, 0));
        assertEquals(2, f.registry.admission().used(Resource.SPATIAL_INDEX_LINKS));
        assertThrows(IllegalArgumentException.class, () -> f.claims.propose(target(SECOND, id(999), 1, 1, 0)));
        assertThrows(IllegalArgumentException.class, () -> f.claims.propose(new Snapshot(SECOND, B, id(999), OVERWORLD, 1, 64, 0, 1, 64, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> target(SECOND, B, 2, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> target(SECOND, B, 0, 16 * 128, 0));
        assertThrows(IllegalArgumentException.class, () -> target(SECOND, B, -30_000_001, -30_000_001, 0));
        assertThrows(IllegalArgumentException.class, () -> f.claims.state(id(999)));
        f.claims.release(id(999));
        assertEquals(List.of(negative), f.claims.snapshots());
        assertEquals(1, f.registry.admission().used(Resource.PHYSICAL_TARGETS));
    }
}
