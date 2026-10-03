package io.github.kpuctajluk.colonyloom.core.storage;

import io.github.kpuctajluk.colonyloom.core.content.BlockDescriptor;
import java.util.Objects;
import java.util.UUID;

/** Block identities use epoch zero; citizen inventories use the citizen binding epoch. */
public record StorageId(String dimension, UUID identity, long bindingEpoch) {
    public StorageId {
        BlockDescriptor.validateIdentifier(dimension);
        Objects.requireNonNull(identity, "identity");
        if (bindingEpoch < 0) throw new IllegalArgumentException("Negative storage binding epoch");
    }
}
