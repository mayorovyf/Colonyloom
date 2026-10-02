package io.github.kpuctajluk.colonyloom.core.chunk;

import java.util.Objects;

/** Dimension-qualified chunk coordinates, with room for the ticket's two-chunk halo. */
public record ChunkKey(String dimension, int x, int z) {
    public ChunkKey {
        Objects.requireNonNull(dimension, "dimension");
        if (dimension.length() > 256 || !dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
            throw new IllegalArgumentException("Invalid dimension ID");
        if (x < -1_875_002 || x > 1_875_002 || z < -1_875_002 || z > 1_875_002)
            throw new IllegalArgumentException("Chunk outside world coordinate bounds");
    }
}
