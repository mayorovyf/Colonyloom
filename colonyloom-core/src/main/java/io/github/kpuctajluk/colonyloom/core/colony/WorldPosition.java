package io.github.kpuctajluk.colonyloom.core.colony;

import java.util.Objects;

public record WorldPosition(String dimension, int x, int y, int z) {
    public WorldPosition {
        Objects.requireNonNull(dimension, "dimension");
        if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid dimension ID");
    }
}
