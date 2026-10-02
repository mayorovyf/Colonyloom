package io.github.kpuctajluk.colonyloom.core.content;

/** Relative position; markers may sit just outside the structure footprint. */
public record BlockOffset(int x, int y, int z) {
    public BlockOffset {
        if (x < -64 || x > 64 || y < -64 || y > 64 || z < -64 || z > 64) {
            throw new IllegalArgumentException("Blueprint offset exceeds -64..64");
        }
    }
}
