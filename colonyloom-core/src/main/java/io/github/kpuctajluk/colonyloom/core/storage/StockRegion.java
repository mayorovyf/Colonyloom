package io.github.kpuctajluk.colonyloom.core.storage;

import java.util.Objects;

/** A canonical physical inventory slot, independent of registration aliases. */
public record StockRegion(StorageId storage, int slot) {
    public static final int MAX_SLOT = 26;
    public StockRegion {
        Objects.requireNonNull(storage, "storage");
        if (slot < 0 || slot > (storage.bindingEpoch() == 0 ? MAX_SLOT : 8))
            throw new IllegalArgumentException("Storage slot outside physical envelope");
    }
}
