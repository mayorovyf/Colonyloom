package io.github.kpuctajluk.colonyloom.core.storage;

import java.util.List;
import java.util.UUID;

/** Accepted physical allocations consume the same free stock as reservations. */
public final class AllocationLedger {
    public record Entry(UUID id, UUID colonyId, UUID ownerId, StockRegion slot, ItemDescriptor item, long count, long revision) {
        public Entry { StorageRegistry.validateClaim(id, colonyId, ownerId, slot, item, count, revision); }
    }
    private final StorageRegistry storage;
    AllocationLedger(StorageRegistry storage) { this.storage = storage; }
    public Entry allocate(UUID id, UUID colony, UUID owner, StockRegion slot, ItemDescriptor item, long count, long tick) {
        return storage.claim(true, id, colony, owner, slot, item, count, tick).allocation();
    }
    public boolean release(UUID id) { return storage.release(true, id); }
    public List<Entry> entries() { storage.requireOwner(); return storage.claims(true).stream().map(StorageRegistry.Claim::allocation).toList(); }
}
