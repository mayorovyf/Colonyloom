package io.github.kpuctajluk.colonyloom.core.command;

import io.github.kpuctajluk.colonyloom.core.citizen.BindingRegistry;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.citizen.ProfessionDefinition;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.MemberRank;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.persistence.RegistrySnapshot;
import io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ColonyCommandsTest {
    private static final ColonyCommands.PhysicalChecks CHECKS = new ColonyCommands.PhysicalChecks() {
        public void validateTerritory(Territory territory) {}
        public void validateCitizenPosition(ColonyRuntime colony, WorldPosition position) {}
        public void validateRecovery(ColonyRuntime colony, List<CitizenRecord> citizens, List<BindingRegistry.Observation> observations) {}
    };
    private final UUID owner = UUID.randomUUID();
    private final List<CitizenRecord> spawned = new ArrayList<>();
    private ServerRuntime runtime() {
        ServerRuntime runtime = ServerRuntime.start(Thread.currentThread());
        runtime.configureCommands(() -> {}, List.of(new ProfessionDefinition("colonyloom:builder", 1, Set.of("colonyloom:construction"), Set.of())));
        return runtime;
    }
    private ColonyCommands.CommandContext context(UUID player, boolean operator) { return new ColonyCommands.CommandContext(player, operator, CHECKS); }
    private ColonyRuntime colony(ServerRuntime runtime, int x) {
        return runtime.commands().createColony(context(owner, false), UUID.randomUUID(), "Shared name", new Territory("minecraft:overworld", x, 0, x + 31, 31));
    }
    private CitizenRecord citizen(ServerRuntime runtime, ColonyRuntime colony) {
        CitizenRecord citizen = runtime.commands().createCitizen(context(owner, true), colony.colonyId(), UUID.randomUUID(), UUID.randomUUID(), new WorldPosition("minecraft:overworld", colony.territory().minX(), 64, 0), spawned::add);
        runtime.bindings().observe(citizen.citizenId(), citizen.entityId(), citizen.bindingEpoch());
        return citizen;
    }

    @Test
    void membershipAndOperatorPrivilegesNeverCrossColonyAuthority() {
        ServerRuntime runtime = runtime();
        ColonyRuntime colony = colony(runtime, 0);
        CitizenRecord citizen = citizen(runtime, colony);
        UUID manager = UUID.randomUUID(); UUID viewer = UUID.randomUUID(); UUID outsider = UUID.randomUUID();
        runtime.commands().setMember(context(owner, false), colony.colonyId(), manager, MemberRank.MANAGER);
        runtime.commands().setMember(context(owner, false), colony.colonyId(), viewer, MemberRank.VIEWER);
        assertEquals(colony.colonyId(), runtime.commands().status(context(viewer, false), colony.colonyId()).colonyId());
        assertThrows(SecurityException.class, () -> runtime.commands().status(context(outsider, true), colony.colonyId()));
        RegistrySnapshot before = runtime.registry().snapshot();
        assertThrows(SecurityException.class, () -> runtime.commands().assignProfession(context(viewer, true), citizen.citizenId(), "colonyloom:builder"));
        assertThrows(SecurityException.class, () -> runtime.commands().setOwner(context(manager, true), colony.colonyId(), manager));
        assertThrows(IllegalArgumentException.class, () -> runtime.commands().assignProfession(context(manager, false), citizen.citizenId(), "colonyloom:missing"));
        assertEquals(before, runtime.registry().snapshot());
        runtime.commands().assignProfession(context(manager, false), citizen.citizenId(), "colonyloom:builder");
        assertEquals("colonyloom:builder", runtime.registry().citizen(citizen.citizenId()).professionId());
        runtime.commands().setOwner(context(owner, false), colony.colonyId(), manager);
        assertEquals(MemberRank.OWNER, runtime.registry().colony(colony.colonyId()).rank(manager));
        assertThrows(SecurityException.class, () -> runtime.commands().setMember(context(owner, false), colony.colonyId(), viewer, null));
    }

    @Test
    void territoryBoundariesAndFailedSpawnLeaveNoPartialIdentity() {
        ServerRuntime runtime = runtime(); ColonyRuntime colony = colony(runtime, 0);
        assertThrows(IllegalArgumentException.class, () -> new Territory("minecraft:overworld", 0, 0, 14, 15));
        assertThrows(IllegalArgumentException.class, () -> new Territory("minecraft:overworld", 0, 0, 128, 15));
        new Territory("minecraft:overworld", Integer.MAX_VALUE - 15, 0, Integer.MAX_VALUE, 15);
        RegistrySnapshot before = runtime.registry().snapshot();
        assertThrows(IllegalArgumentException.class, () -> runtime.commands().createColony(context(owner, false), UUID.randomUUID(), "Overlap", new Territory("minecraft:overworld", 31, 0, 46, 15)));
        assertThrows(IllegalStateException.class, () -> runtime.commands().createCitizen(context(owner, true), colony.colonyId(), UUID.randomUUID(), UUID.randomUUID(), new WorldPosition("minecraft:overworld", 1, 64, 1), proposed -> { throw new IllegalStateException("Spawn veto"); }));
        assertEquals(before, runtime.registry().snapshot());
        UUID unknownColony = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> runtime.commands().createCitizen(context(owner, true), unknownColony, UUID.randomUUID(), UUID.randomUUID(), new WorldPosition("minecraft:overworld", 1, 64, 1), spawned::add));
        assertEquals(List.of(), spawned);
        colony(runtime, 32);
        colony(runtime, 64);
        assertThrows(IllegalStateException.class, () -> colony(runtime, 96));
        assertEquals(3, runtime.registry().colonies().size());
    }

    @Test
    void twoColoniesThreeCitizensRestoreIdentityRightsAndTimersWithoutCrossWorldLeak() {
        ServerRuntime original = runtime(); ColonyRuntime first = colony(original, 0); ColonyRuntime second = colony(original, 64);
        CitizenRecord a = citizen(original, first); CitizenRecord b = citizen(original, first); CitizenRecord c = citizen(original, second);
        UUID viewer = UUID.randomUUID(); original.commands().setMember(context(owner, false), first.colonyId(), viewer, MemberRank.VIEWER);
        original.commands().assignProfession(context(owner, false), b.citizenId(), "colonyloom:builder");
        RegistrySnapshot saved = original.registry().snapshot();
        original.beginStopping(); original.stop();
        ServerRuntime restored = runtime(); restored.registry().restore(saved);
        assertEquals(saved.colonies(), restored.registry().colonies());
        assertEquals(saved.citizens(), restored.registry().citizens());
        assertEquals(MemberRank.VIEWER, restored.registry().colony(first.colonyId()).rank(viewer));
        for (CitizenRecord citizen : List.of(a, b, c)) {
            assertTrue(restored.bindings().activeEntity(citizen.citizenId()).isEmpty());
            restored.bindings().observe(citizen.citizenId(), citizen.entityId(), citizen.bindingEpoch());
            assertEquals(citizen.entityId(), restored.bindings().activeEntity(citizen.citizenId()).orElseThrow());
            assertEquals(1200L, restored.registry().citizen(citizen.citizenId()).remainingTimers().get("food"));
        }
        assertTrue(runtime().registry().citizens().isEmpty());
    }

    @Test
    void duplicateAndStaleEpochQuarantineOriginalAcrossUnloadAndRestartUntilExplicitBind() {
        ServerRuntime runtime = runtime(); ColonyRuntime colony = colony(runtime, 0); CitizenRecord citizen = citizen(runtime, colony);
        runtime.bindings().unload(citizen.entityId());
        assertEquals(CitizenRecord.Lifecycle.ALIVE, runtime.registry().citizen(citizen.citizenId()).lifecycle());
        assertEquals(1, runtime.registry().citizens().size());
        runtime.bindings().observe(citizen.citizenId(), citizen.entityId(), 1);
        UUID duplicate = UUID.randomUUID(); runtime.bindings().observe(citizen.citizenId(), duplicate, 0);
        assertTrue(runtime.bindings().activeEntity(citizen.citizenId()).isEmpty());
        assertTrue(runtime.bindings().observations(citizen.citizenId()).stream().allMatch(BindingRegistry.Observation::quarantined));
        runtime.bindings().unload(duplicate);
        assertThrows(IllegalStateException.class, () -> runtime.commands().bind(context(owner, true), citizen.citizenId(), citizen.entityId()));
        ServerRuntime restored = runtime(); restored.registry().restore(runtime.registry().snapshot());
        restored.bindings().observe(citizen.citizenId(), citizen.entityId(), 1);
        assertTrue(restored.bindings().activeEntity(citizen.citizenId()).isEmpty());
        assertThrows(IllegalStateException.class, () -> restored.commands().bind(context(owner, true), citizen.citizenId(), citizen.entityId()));
        restored.bindings().observe(citizen.citizenId(), duplicate, 0);
        CitizenRecord bound = restored.commands().bind(context(owner, true), citizen.citizenId(), citizen.entityId());
        assertEquals(2, bound.bindingEpoch());
        assertEquals(citizen.entityId(), restored.bindings().activeEntity(citizen.citizenId()).orElseThrow());
        assertTrue(restored.bindings().observations(citizen.citizenId()).stream().filter(value -> value.entityId().equals(duplicate)).allMatch(value -> value.retired() && value.quarantined()));
        restored.bindings().unload(duplicate);
        restored.bindings().observe(citizen.citizenId(), duplicate, 0);
        assertEquals(citizen.entityId(), restored.bindings().activeEntity(citizen.citizenId()).orElseThrow());
        UUID orphan = UUID.randomUUID(); restored.bindings().observe(orphan, UUID.randomUUID(), 1);
        assertTrue(restored.registry().findCitizen(orphan).isEmpty());
        assertEquals(1, restored.registry().citizens().size());
    }

    @Test
    void crashBlockSurvivesCleanStopAndAcceptRequiresFreshLoadedNonconflictingInspection() {
        ServerRuntime runtime = runtime(); ColonyRuntime colony = colony(runtime, 0); CitizenRecord citizen = citizen(runtime, colony);
        UUID checkpoint = UUID.randomUUID(); runtime.registry().markRecoveryBlocked(checkpoint);
        RegistrySnapshot blocked = runtime.registry().snapshot(); runtime.beginStopping(); runtime.stop();
        ServerRuntime restored = runtime(); restored.registry().restore(blocked);
        assertTrue(restored.registry().colony(colony.colonyId()).recoveryBlocked());
        ColonyCommands.RecoveryInspection unknown = restored.commands().inspect(context(owner, true), colony.colonyId());
        assertFalse(unknown.ready());
        assertThrows(IllegalStateException.class, () -> restored.commands().acceptWorld(context(owner, true), colony.colonyId(), checkpoint, unknown));
        restored.bindings().observe(citizen.citizenId(), citizen.entityId(), 1);
        ColonyCommands.RecoveryInspection ready = restored.commands().inspect(context(owner, true), colony.colonyId());
        assertTrue(ready.ready());
        assertThrows(IllegalArgumentException.class, () -> restored.commands().acceptWorld(context(owner, true), colony.colonyId(), UUID.randomUUID(), ready));
        restored.bindings().unload(citizen.entityId());
        assertThrows(IllegalStateException.class, () -> restored.commands().acceptWorld(context(owner, true), colony.colonyId(), checkpoint, ready));
        assertTrue(restored.registry().colony(colony.colonyId()).recoveryBlocked());
        restored.bindings().observe(citizen.citizenId(), citizen.entityId(), 1);
        ColonyCommands.RecoveryInspection current = restored.commands().inspect(context(owner, true), colony.colonyId());
        restored.commands().acceptWorld(context(owner, true), colony.colonyId(), checkpoint, current);
        assertFalse(restored.registry().colony(colony.colonyId()).recoveryBlocked());
        assertNull(restored.registry().colony(colony.colonyId()).recoveryCheckpointId());
        assertEquals(CitizenRecord.Readiness.READY, restored.registry().citizen(citizen.citizenId()).readiness());
    }

    @Test
    void persistedTombstoneRejectsLateIncarnationAndRestoreIsAllOrNothing() {
        ServerRuntime runtime = runtime(); ColonyRuntime colony = colony(runtime, 0); CitizenRecord citizen = citizen(runtime, colony);
        runtime.commands().markDeath(citizen.citizenId());
        ServerRuntime restored = runtime(); restored.registry().restore(runtime.registry().snapshot());
        restored.bindings().observe(citizen.citizenId(), citizen.entityId(), 1);
        assertEquals(CitizenRecord.Lifecycle.DEAD, restored.registry().citizen(citizen.citizenId()).lifecycle());
        assertEquals(CitizenRecord.Admission.INACTIVE, restored.registry().citizen(citizen.citizenId()).admission());
        assertTrue(restored.bindings().activeEntity(citizen.citizenId()).isEmpty());
        assertThrows(IllegalStateException.class, () -> restored.commands().bind(context(owner, true), citizen.citizenId(), citizen.entityId()));
        RegistrySnapshot before = restored.registry().snapshot();
        RegistrySnapshot invalid = new RegistrySnapshot(before.colonies(), List.of(citizen, citizen), before.buildings(), before.tombstones(), before.observations());
        assertThrows(IllegalArgumentException.class, () -> restored.registry().restore(invalid));
        assertEquals(before, restored.registry().snapshot());
    }

    @Test
    void sessionGateFailureAndStoppingRejectCommandsBeforeLogicalOrPhysicalChanges() {
        ServerRuntime runtime = runtime(); ColonyRuntime colony = colony(runtime, 0); RegistrySnapshot before = runtime.registry().snapshot();
        runtime.configureCommands(() -> { throw new IllegalStateException("Marker flush failed"); }, List.of());
        assertThrows(IllegalStateException.class, () -> citizen(runtime, colony));
        assertTrue(spawned.isEmpty()); assertEquals(before, runtime.registry().snapshot());
        runtime.configureCommands(() -> {}, List.of()); runtime.beginStopping();
        assertThrows(IllegalStateException.class, () -> citizen(runtime, colony));
        assertTrue(spawned.isEmpty()); assertEquals(before, runtime.registry().snapshot());
    }
}
