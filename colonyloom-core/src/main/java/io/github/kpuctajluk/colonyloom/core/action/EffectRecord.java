package io.github.kpuctajluk.colonyloom.core.action;

import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.storage.ItemDescriptor;
import io.github.kpuctajluk.colonyloom.core.storage.StockRegion;
import java.util.Objects;
import java.util.UUID;

/** Evidence, not a transaction across Minecraft chunks, entities and colony SavedData. */
public record EffectRecord(UUID operationId, UUID colonyId, UUID workId, UUID citizenId,
        long bindingEpoch, ActionContext.Kind kind, WorldPosition target, String expectedBlock,
        String itemId, int countBefore, int countAfter, State state, long revision, Transfer transfer) {
    public enum State { PREPARED, OBSERVED, AMBIGUOUS, ACCEPTED }
    /** Canonical physical participants and measured deltas, never a replayable inventory command. */
    public record Transfer(StockRegion source, StockRegion destination, ItemDescriptor item,
            int sourceBefore, int sourceAfter, int destinationBefore, int destinationAfter,
            int maximum, int extracted, int inserted, int returned) {
        public Transfer {
            Objects.requireNonNull(source); Objects.requireNonNull(destination); Objects.requireNonNull(item);
            if (source.equals(destination) || sourceBefore < 0 || sourceBefore > 891 || sourceAfter < 0 || sourceAfter > 891
                    || destinationBefore < 0 || destinationBefore > 891 || destinationAfter < 0 || destinationAfter > 891
                    || maximum < 1 || maximum > 891 || extracted < 0 || extracted > 891
                    || inserted < 0 || inserted > 891 || returned < 0 || returned > 891)
                throw new IllegalArgumentException("Invalid native transfer evidence");
        }
        public boolean sameAttempt(Transfer next) {
            return next != null && source.equals(next.source) && destination.equals(next.destination)
                    && item.equals(next.item) && sourceBefore == next.sourceBefore
                    && destinationBefore == next.destinationBefore && maximum == next.maximum;
        }
        public boolean unchanged() {
            return sourceBefore == sourceAfter && destinationBefore == destinationAfter
                    && extracted == 0 && inserted == 0 && returned == 0;
        }
        public Transfer observed(int sourceCount, int destinationCount, int extractedCount, int insertedCount, int returnedCount) {
            return new Transfer(source,destination,item,sourceBefore,sourceCount,destinationBefore,destinationCount,
                    maximum,extractedCount,insertedCount,returnedCount);
        }
    }
    public EffectRecord {
        Objects.requireNonNull(operationId); Objects.requireNonNull(colonyId); Objects.requireNonNull(citizenId);
        Objects.requireNonNull(kind); Objects.requireNonNull(target); Objects.requireNonNull(expectedBlock);
        Objects.requireNonNull(itemId); Objects.requireNonNull(state);
        if (bindingEpoch < 1 || revision < 0 || countBefore < 0 || countBefore > 891 || countAfter < 0
                || countAfter > 891 || expectedBlock.length() > 1024 || itemId.length() > 256
                || kind == ActionContext.Kind.BLOCK_PLACE && workId == null)
            throw new IllegalArgumentException("Invalid physical effect evidence");
        if ((kind == ActionContext.Kind.STORAGE_TRANSFER) != (transfer != null)
                || transfer != null && (!itemId.equals(transfer.item().itemId())
                        || countBefore != transfer.sourceBefore() || countAfter != transfer.sourceAfter()))
            throw new IllegalArgumentException("Transfer evidence does not match physical effect");
        if (transfer != null && state == State.OBSERVED
                && (transfer.extracted() != transfer.inserted()+transfer.returned()
                        || transfer.sourceBefore()-transfer.sourceAfter() != transfer.inserted()
                        || transfer.destinationAfter()-transfer.destinationBefore() != transfer.inserted()
                        || transfer.extracted() > transfer.maximum()))
            throw new IllegalArgumentException("Observed transfer evidence is not conserved");
        if (transfer != null && (!transfer.source().storage().dimension().equals(target.dimension())
                || !transfer.destination().storage().dimension().equals(target.dimension())
                || !(transfer.source().storage().identity().equals(citizenId) && transfer.source().storage().bindingEpoch()==bindingEpoch
                        || transfer.destination().storage().identity().equals(citizenId) && transfer.destination().storage().bindingEpoch()==bindingEpoch)))
            throw new IllegalArgumentException("Transfer evidence missing canonical courier participant");
    }
    public EffectRecord observed(int after, boolean ambiguous) {
        return new EffectRecord(operationId,colonyId,workId,citizenId,bindingEpoch,kind,target,expectedBlock,
                itemId,countBefore,after,ambiguous ? State.AMBIGUOUS : State.OBSERVED,Math.incrementExact(revision),transfer);
    }
    public EffectRecord observedTransfer(Transfer fact, boolean ambiguous) {
        if (transfer == null || !transfer.sameAttempt(fact)) throw new IllegalArgumentException("Transfer attempt changed");
        return new EffectRecord(operationId,colonyId,workId,citizenId,bindingEpoch,kind,target,expectedBlock,
                itemId,countBefore,fact.sourceAfter(),ambiguous ? State.AMBIGUOUS : State.OBSERVED,Math.incrementExact(revision),fact);
    }
    public EffectRecord accepted() {
        return new EffectRecord(operationId,colonyId,workId,citizenId,bindingEpoch,kind,target,expectedBlock,
                itemId,countBefore,countAfter,State.ACCEPTED,Math.incrementExact(revision),transfer);
    }
}
