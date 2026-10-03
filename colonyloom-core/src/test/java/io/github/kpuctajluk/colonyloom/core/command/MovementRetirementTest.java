package io.github.kpuctajluk.colonyloom.core.command;

import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.action.EffectRecord;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MovementRetirementTest {
    @Test
    void liveMovementAndRetainedWitnessPreventRetirementButReleasedTerminalMovementFreesCapacity() {
        var runtime = ServerRuntime.start(Thread.currentThread());
        runtime.configureCommands(() -> {}, List.of());
        UUID owner = UUID.randomUUID();
        var context = new ColonyCommands.CommandContext(owner, false, new ColonyCommands.PhysicalChecks() {
            public void validateTerritory(Territory territory) {}
            public void validateCitizenPosition(io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime colony, WorldPosition position) {}
            public void validateRecovery(io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime colony,
                    List<io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord> citizens,
                    List<io.github.kpuctajluk.colonyloom.core.citizen.BindingRegistry.Observation> observations) {}
        });
        var colony = runtime.commands().createColony(context, UUID.randomUUID(), "Retirement", new Territory("minecraft:overworld", 0, 0, 31, 31));
        var target = new WorldPosition("minecraft:overworld", 8, 64, 8);
        var citizen = runtime.commands().createCitizen(new ColonyCommands.CommandContext(owner, true, context.checks()),
                colony.colonyId(), UUID.randomUUID(), UUID.randomUUID(), target, ignored -> {});
        var work = runtime.commands().createMoveWork(context, UUID.randomUUID(), colony.colonyId(), target);
        assertThrows(IllegalStateException.class, () -> runtime.workBoard().retire(work.id()));
        runtime.workBoard().cancel(work.id());
        var witness = new EffectRecord(UUID.randomUUID(),colony.colonyId(),work.id(),citizen.citizenId(),citizen.bindingEpoch(),ActionContext.Kind.DEATH,target,"inventory","minecraft:oak_stairs",4,4,EffectRecord.State.PREPARED,0,null);
        runtime.registry().effects().prepare(witness, io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.NORMAL);
        assertThrows(IllegalStateException.class, () -> runtime.workBoard().retire(work.id()));
        runtime.registry().effects().discardUnchanged(witness.operationId());
        runtime.workBoard().retire(work.id());
        assertEquals(0, runtime.admission().used(Resource.WORKS));
        assertEquals(0, runtime.admission().used(Resource.WAIT_REGISTRATIONS));
        assertThrows(IllegalArgumentException.class, () -> runtime.workBoard().work(work.id()));
    }
}
