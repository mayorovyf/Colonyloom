package colonyloom.core.logistics;

import colonyloom.core.colony.WorldPosition;
import colonyloom.core.scheduler.AdmissionLedger.Lane;
import colonyloom.core.storage.ItemDescriptor;
import colonyloom.core.storage.StockRegion;
import colonyloom.core.supply.Demand;
import java.util.Objects;
import java.util.UUID;

/** Source/cargo references describe actual canonical storage, not synthetic inventory. */
public record DeliveryOrder(UUID id, UUID colonyId, UUID ownerDemandId, StockRegion source,
                            WorldPosition destination, ItemDescriptor item, long quantity, long transferred,
                            long revision, UUID citizenId, UUID workId, State state, Lane lane, int priority) {
    public enum State { PLANNED, SOURCE_READY, PICKED_UP, IN_TRANSIT, TRANSFERRED, RETURNING, COMPLETED, BLOCKED, RETURNED, CANCELLED, LOST }
    public DeliveryOrder {
        Objects.requireNonNull(id); Objects.requireNonNull(colonyId); Objects.requireNonNull(ownerDemandId);
        Objects.requireNonNull(source); Objects.requireNonNull(destination); Objects.requireNonNull(item);
        Objects.requireNonNull(state); Objects.requireNonNull(lane); Demand.quantity(quantity); Demand.quantity(transferred);
        if (quantity == 0 || transferred > quantity || revision < 0 || state == State.COMPLETED && transferred != quantity) throw new IllegalArgumentException("Invalid delivery counters");
    }
    public boolean terminal() { return state == State.COMPLETED || state == State.RETURNED || state == State.CANCELLED || state == State.LOST; }
    public boolean returnRequired() { return state == State.RETURNING; }
}
