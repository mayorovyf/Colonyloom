package io.github.kpuctajluk.colonyloom.core.production;

import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.supply.Demand;
import io.github.kpuctajluk.colonyloom.core.supply.RecipeDefinition;
import java.util.Objects;
import java.util.UUID;

/** The pinned recipe is authoritative even when content later reloads. Stage08 plans only. */
public record ProductionOrder(UUID id, UUID colonyId, UUID ownerDemandId, RecipeDefinition recipe,
                              long batches, long remainingActiveTicks, long revision, UUID workId,
                              UUID citizenId, State state, Lane lane, int priority) {
    public enum State { PLANNED, WAITING }
    public ProductionOrder {
        Objects.requireNonNull(id); Objects.requireNonNull(colonyId); Objects.requireNonNull(ownerDemandId);
        Objects.requireNonNull(recipe); Objects.requireNonNull(state); Objects.requireNonNull(lane);
        if (batches <= 0 || batches > Demand.MAX_QUANTITY || remainingActiveTicks < 0 || revision < 0
                || remainingActiveTicks > Math.multiplyExact(batches, recipe.activeTicks())) throw new IllegalArgumentException("Invalid production order counters");
        Demand.quantity(Math.multiplyExact(batches, recipe.outputCount()));
        for (var ingredient : recipe.ingredients()) Demand.quantity(Math.multiplyExact(batches, ingredient.count()));
    }
}
