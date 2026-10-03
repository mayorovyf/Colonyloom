package io.github.kpuctajluk.colonyloom.core.storage;

import java.util.List;

/** Persistent obligations and canonical mappings only; observed quantities are never saved. */
public record StorageSnapshot(List<StorageRegistry.Registration> registrations,
                              List<ReservationLedger.Entry> reservations,
                              List<AllocationLedger.Entry> allocations,
                              List<StorageRegistry.Workshop> workshops,
                              List<StorageRegistry.RetiredIdentity> retiredIdentities) {
    public StorageSnapshot {
        registrations = List.copyOf(registrations);
        reservations = List.copyOf(reservations);
        allocations = List.copyOf(allocations);
        workshops = List.copyOf(workshops);
        retiredIdentities = List.copyOf(retiredIdentities);
    }
    public static StorageSnapshot empty() { return new StorageSnapshot(List.of(), List.of(), List.of(), List.of(), List.of()); }
}
