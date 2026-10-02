package io.github.kpuctajluk.colonyloom.core.colony;

public record Territory(String dimension, int minX, int minZ, int maxX, int maxZ) {
    public Territory {
        new WorldPosition(dimension, minX, 0, minZ);
        long width = (long) maxX - minX + 1;
        long depth = (long) maxZ - minZ + 1;
        if (width < 16 || width > 128 || depth < 16 || depth > 128) throw new IllegalArgumentException("Territory dimensions must be 16..128 inclusive");
    }
    public boolean contains(WorldPosition position) {
        return dimension.equals(position.dimension()) && position.x() >= minX && position.x() <= maxX && position.z() >= minZ && position.z() <= maxZ;
    }
    public boolean overlaps(Territory other) {
        return dimension.equals(other.dimension) && minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ;
    }
}
