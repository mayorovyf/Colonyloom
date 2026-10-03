package io.github.kpuctajluk.colonyloom.core.production;

import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.storage.StorageId;
import io.github.kpuctajluk.colonyloom.core.supply.Demand;
import io.github.kpuctajluk.colonyloom.core.supply.RecipeDefinition;
import java.util.Objects;
import java.util.UUID;

/** A pinned workshop and recipe; batches counts remaining indivisible physical outputs. */
public record ProductionOrder(UUID id, UUID colonyId, UUID ownerDemandId, RecipeDefinition recipe,
        long batches, long remainingActiveTicks, long revision, UUID workId, UUID citizenId,
        State state, Lane lane, int priority, UUID workshopId, WorldPosition equipmentPosition,
        StorageId workshopStorage, long completedBatches, boolean batchStarted) {
    public enum State { PLANNED, WAITING, PROCESSING, COMPLETED, CANCELLED }
    public ProductionOrder {
        Objects.requireNonNull(id); Objects.requireNonNull(colonyId); Objects.requireNonNull(ownerDemandId);
        Objects.requireNonNull(recipe); Objects.requireNonNull(state); Objects.requireNonNull(lane);
        boolean pinned = workshopId != null && equipmentPosition != null && workshopStorage != null;
        if (!pinned && (workshopId != null || equipmentPosition != null || workshopStorage != null || batchStarted || completedBatches != 0 || workId != null || citizenId != null || state == State.PROCESSING)) throw new IllegalArgumentException("Partial production workshop pin");
        if (batches < 0 || completedBatches < 0 || batches + completedBatches > Demand.MAX_QUANTITY
                || remainingActiveTicks < 0 || remainingActiveTicks > recipe.activeTicks() || revision < 0
                || citizenId != null && workId == null || pinned && !equipmentPosition.dimension().equals(workshopStorage.dimension())
                || batchStarted && (batches == 0 || workId == null || citizenId == null) || !batchStarted && citizenId != null
                || !batchStarted && remainingActiveTicks != (batches == 0 ? 0 : recipe.activeTicks())
                || state == State.COMPLETED && batches != 0 || state == State.PROCESSING && !batchStarted)
            throw new IllegalArgumentException("Invalid production counters/binding");
        Demand.quantity(Math.multiplyExact(Math.addExact(batches, completedBatches), recipe.outputCount()));
        for (var ingredient : recipe.ingredients()) Demand.quantity(Math.multiplyExact(Math.addExact(batches, completedBatches), ingredient.count()));
    }
    public boolean terminal() { return state == State.COMPLETED || state == State.CANCELLED; }
    public boolean pinned() { return workshopId != null; }
    public ProductionOrder update(long remainingBatches, long ticks, long completed, boolean started,
            UUID work, UUID citizen, State next, Lane nextLane, int nextPriority) {
        return new ProductionOrder(id, colonyId, ownerDemandId, recipe, remainingBatches, ticks,
                Math.incrementExact(revision), work, citizen, next, nextLane, nextPriority,
                workshopId, equipmentPosition, workshopStorage, completed, started);
    }
}
