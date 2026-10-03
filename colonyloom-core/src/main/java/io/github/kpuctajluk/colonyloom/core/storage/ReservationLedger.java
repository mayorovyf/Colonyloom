package io.github.kpuctajluk.colonyloom.core.storage;

import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import java.util.List;
import java.util.UUID;

/** Colony-local ownership over quantities guarded by the global canonical slot ledger. */
public final class ReservationLedger {
    public record Entry(UUID id, UUID colonyId, UUID ownerId, StockRegion slot, ItemDescriptor item, long count, long revision, Lane lane) {
        public Entry { StorageRegistry.validateClaim(id, colonyId, ownerId, slot, item, count, revision); java.util.Objects.requireNonNull(lane); }
    }
    private final StorageRegistry storage;
    ReservationLedger(StorageRegistry storage) { this.storage = storage; }
    public Entry reserve(UUID id, UUID colony, UUID owner, StockRegion slot, ItemDescriptor item, long count, long tick, Lane lane) {
        return storage.claim(false, id, colony, owner, slot, item, count, tick, lane).reservation();
    }
    public boolean release(UUID id) { return storage.release(false, id); }
    public Entry get(UUID id) { return storage.reservation(id); }
    public List<Entry> entries() { storage.requireOwner(); return storage.claims(false).stream().map(StorageRegistry.Claim::reservation).toList(); }
}
