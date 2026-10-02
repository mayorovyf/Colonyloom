package io.github.kpuctajluk.colonyloom.core.content;

import io.github.kpuctajluk.colonyloom.core.citizen.ProfessionDefinition;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Registry-independent, complete block state and its physical placement material. */
public record BlockDescriptor(String blockId, Map<String, String> properties, String itemId) {
    public BlockDescriptor {
        validateIdentifier(blockId);
        validateIdentifier(itemId);
        Objects.requireNonNull(properties, "properties");
        if (properties.size() > 16) throw new IllegalArgumentException("Too many block properties");
        properties.forEach((key, value) -> {
            if (key == null || key.length() > 64 || !key.matches("[a-z0-9_]+")) {
                throw new IllegalArgumentException("Invalid block property name");
            }
            if (value == null || value.length() > 64 || !value.matches("[a-z0-9_.-]+")) {
                throw new IllegalArgumentException("Invalid block property value");
            }
        });
        properties = Collections.unmodifiableMap(new TreeMap<>(properties));
    }

    public static void validateIdentifier(String id) {
        ProfessionDefinition.validateId(id);
        if (id.length() > 256) throw new IllegalArgumentException("Content ID exceeds byte-independent length cap");
    }
}
