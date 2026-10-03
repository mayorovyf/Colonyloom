package io.github.kpuctajluk.colonyloom.core.supply;

import io.github.kpuctajluk.colonyloom.core.storage.ItemDescriptor;
import io.github.kpuctajluk.colonyloom.core.storage.StockRegion;
import java.util.Objects;
import java.util.UUID;

/** Each portion occupies exactly one accounting stage, never several counters. */
public record CoverageShare(UUID id, UUID colonyId, UUID demandId, UUID sourceOrderId, UUID obligationId,
                            StockRegion slot, ItemDescriptor item, long quantity, long revision, Stage stage) {
    public enum Stage { RESERVED_STOCK, PROMISED_OUTPUT, IN_TRANSIT, ALLOCATED, FULFILLED }
    public CoverageShare {
        Objects.requireNonNull(id); Objects.requireNonNull(colonyId); Objects.requireNonNull(demandId);
        Objects.requireNonNull(item); Objects.requireNonNull(stage);
        Demand.quantity(quantity);
        if (quantity == 0 || revision < 0) throw new IllegalArgumentException("Invalid coverage quantity/revision");
        boolean valid = switch (stage) {
            case RESERVED_STOCK -> obligationId != null && slot != null;
            case PROMISED_OUTPUT -> sourceOrderId != null && obligationId == null && slot == null;
            case IN_TRANSIT -> sourceOrderId != null && obligationId != null && slot != null;
            case ALLOCATED -> obligationId != null && slot != null;
            case FULFILLED -> obligationId == null && slot == null;
        };
        if (!valid) throw new IllegalArgumentException("Coverage stage lacks exact physical/source references");
    }
    public boolean covered() { return stage == Stage.RESERVED_STOCK || stage == Stage.PROMISED_OUTPUT || stage == Stage.IN_TRANSIT; }
}
