package io.github.kpuctajluk.colonyloom.core.supply;

import io.github.kpuctajluk.colonyloom.core.content.BlockDescriptor;
import io.github.kpuctajluk.colonyloom.core.storage.ItemDescriptor;

/** Null exact identity deliberately matches all component variants of one registry item. */
public record ItemMatcher(String itemId, ItemDescriptor exact) {
    public ItemMatcher {
        BlockDescriptor.validateIdentifier(itemId);
        if (exact != null && !itemId.equals(exact.itemId())) throw new IllegalArgumentException("Matcher identity differs from item ID");
    }
    public boolean matches(ItemDescriptor item) { return item != null && itemId.equals(item.itemId()) && (exact == null || exact.equals(item)); }
}
