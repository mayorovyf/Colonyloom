package io.github.kpuctajluk.colonyloom.core.action;

import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import java.util.Objects;
import java.util.UUID;

/** Evidence, not a transaction across Minecraft chunks, entities and colony SavedData. */
public record EffectRecord(UUID operationId, UUID colonyId, UUID workId, UUID citizenId,
        long bindingEpoch, ActionContext.Kind kind, WorldPosition target, String expectedBlock,
        String itemId, int countBefore, int countAfter, State state, long revision) {
    public enum State { PREPARED, OBSERVED, AMBIGUOUS, ACCEPTED }
    public EffectRecord {
        Objects.requireNonNull(operationId); Objects.requireNonNull(colonyId); Objects.requireNonNull(citizenId);
        Objects.requireNonNull(kind); Objects.requireNonNull(target); Objects.requireNonNull(expectedBlock);
        Objects.requireNonNull(itemId); Objects.requireNonNull(state);
        if (bindingEpoch < 1 || revision < 0 || countBefore < 0 || countBefore > 891 || countAfter < 0
                || countAfter > 891 || expectedBlock.length() > 1024 || itemId.length() > 256
                || kind == ActionContext.Kind.BLOCK_PLACE && workId == null)
            throw new IllegalArgumentException("Invalid physical effect evidence");
    }
    public EffectRecord observed(int after, boolean ambiguous) {
        return new EffectRecord(operationId,colonyId,workId,citizenId,bindingEpoch,kind,target,expectedBlock,
                itemId,countBefore,after,ambiguous ? State.AMBIGUOUS : State.OBSERVED,Math.incrementExact(revision));
    }
    public EffectRecord accepted() {
        return new EffectRecord(operationId,colonyId,workId,citizenId,bindingEpoch,kind,target,expectedBlock,
                itemId,countBefore,countAfter,State.ACCEPTED,Math.incrementExact(revision));
    }
}
