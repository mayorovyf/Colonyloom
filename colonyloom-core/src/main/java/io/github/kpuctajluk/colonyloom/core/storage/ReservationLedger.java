package io.github.kpuctajluk.colonyloom.core.storage;

import java.util.List;
import java.util.UUID;

/** Colony-local ownership over quantities guarded by the global canonical slot ledger. */
public final class ReservationLedger {
    public record Entry(UUID id, UUID colonyId, UUID ownerId, StockRegion slot, ItemDescriptor item, long count, long revision) {
        public Entry { StorageRegistry.validateClaim(id, colonyId, ownerId, slot, item, count, revision); }
    }
    private final StorageRegistry storage;
    ReservationLedger(StorageRegistry storage) { this.storage = storage; }
    public Entry reserve(UUID id, UUID colony, UUID owner, StockRegion slot, ItemDescriptor item, long count, long tick) {
        return storage.claim(false, id, colony, owner, slot, item, count, tick).reservation();
    }
    public boolean release(UUID id) { return storage.release(false, id); }
    public List<Entry> entries() { storage.requireOwner(); return storage.claims(false).stream().map(StorageRegistry.Claim::reservation).toList(); }
}
