package io.github.kpuctajluk.colonyloom.core.storage;

import io.github.kpuctajluk.colonyloom.core.content.BlockDescriptor;
import java.util.Arrays;
import java.util.Objects;

/** Complete canonical component identity; no mutable native stack crosses this boundary. */
public final class ItemDescriptor {
    public static final int MAX_COMPONENT_BYTES = 8192;
    private final String itemId;
    private final byte[] canonicalComponents;
    private final int hash;

    public ItemDescriptor(String itemId, byte[] canonicalComponents) {
        BlockDescriptor.validateIdentifier(itemId);
        Objects.requireNonNull(canonicalComponents, "canonicalComponents");
        if (canonicalComponents.length > MAX_COMPONENT_BYTES) throw new IllegalArgumentException("Item components exceed envelope");
        this.itemId = itemId;
        this.canonicalComponents = canonicalComponents.clone();
        hash = 31 * itemId.hashCode() + Arrays.hashCode(this.canonicalComponents);
    }

    public String itemId() { return itemId; }
    public byte[] canonicalComponents() { return canonicalComponents.clone(); }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof ItemDescriptor item
                && itemId.equals(item.itemId) && Arrays.equals(canonicalComponents, item.canonicalComponents);
    }
    @Override public int hashCode() { return hash; }
    @Override public String toString() { return itemId + "[" + canonicalComponents.length + " component bytes]"; }
}
