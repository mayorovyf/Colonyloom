package io.github.kpuctajluk.colonyloom.core.content;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Self-contained pinned logical definition; loading the originating datapack is not required. */
public final class BlueprintDefinition {
    private final String id;
    private final int version;
    private final String digest;
    private final List<BlockSpec> blocks;
    private final Map<String, BlockOffset> markers;
    private final Bounds bounds;
    private final List<BlockDescriptor> palette;
    private final long estimatedBytes;

    /** Exact occupied-block extrema, computed once while accepting the immutable definition. */
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_BLOCKS = 65_536;
    public static final int MAX_MARKERS = 16;
    private static final Comparator<BlockSpec> BLOCK_ORDER = Comparator
            .comparingInt((BlockSpec spec) -> spec.offset().y())
            .thenComparingInt(spec -> spec.offset().z()).thenComparingInt(spec -> spec.offset().x());

    public BlueprintDefinition(String id, int version, String digest,
            List<BlockSpec> blocks, Map<String, BlockOffset> markers) {
        BlockDescriptor.validateIdentifier(id);
        if (version < 1) throw new IllegalArgumentException("Blueprint version must be positive");
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(markers, "markers");
        if (blocks.isEmpty() || blocks.size() > MAX_BLOCKS) throw new IllegalArgumentException("Invalid blueprint block count");
        if (markers.size() > MAX_MARKERS) throw new IllegalArgumentException("Too many blueprint markers");
        var positions = new HashSet<BlockOffset>();
        var descriptors = new java.util.LinkedHashSet<BlockDescriptor>();
        int minX = 64, minY = 64, minZ = 64, maxX = -64, maxY = -64, maxZ = -64;
        for (BlockSpec spec : blocks) {
            Objects.requireNonNull(spec, "block spec");
            BlockOffset pos = spec.offset();
            if (!positions.add(pos)) throw new IllegalArgumentException("Duplicate blueprint position " + pos);
            descriptors.add(spec.block());
            if (descriptors.size() > 512) throw new IllegalArgumentException("Pinned palette cap");
            minX = Math.min(minX, pos.x()); minY = Math.min(minY, pos.y()); minZ = Math.min(minZ, pos.z());
            maxX = Math.max(maxX, pos.x()); maxY = Math.max(maxY, pos.y()); maxZ = Math.max(maxZ, pos.z());
        }
        if (maxX - minX >= 64 || maxY - minY >= 64 || maxZ - minZ >= 64) {
            throw new IllegalArgumentException("Blueprint extent exceeds 64 blocks");
        }
        markers.forEach((name, offset) -> {
            if (name == null || name.length() > 64 || !name.matches("[a-z0-9_]+")) {
                throw new IllegalArgumentException("Invalid blueprint marker name");
            }
            Objects.requireNonNull(offset, "marker offset");
        });
        blocks = blocks.stream().sorted(BLOCK_ORDER).toList();
        markers = Collections.unmodifiableMap(new TreeMap<>(markers));
        String actualDigest = canonicalDigest(id, version, blocks, markers);
        if (digest != null && !actualDigest.equals(digest)) throw new IllegalArgumentException("Blueprint digest mismatch");
        digest = actualDigest;
        this.id = id;
        this.version = version;
        this.digest = digest;
        this.blocks = blocks;
        this.markers = markers;
        this.bounds = new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        this.palette = List.copyOf(descriptors);
        long bytes = 1024 + id.length() * 4L + markers.size() * 512L + blocks.size() * 64L;
        for (var block : palette) {
            bytes += 256 + 4L * (block.blockId().length() + block.itemId().length());
            for (var property : block.properties().entrySet())
                bytes += 64 + 4L * (property.getKey().length() + property.getValue().length());
        }
        this.estimatedBytes = bytes;
    }

    public String id() { return id; }
    public int version() { return version; }
    public String digest() { return digest; }
    public List<BlockSpec> blocks() { return blocks; }
    public Map<String, BlockOffset> markers() { return markers; }
    public Bounds bounds() { return bounds; }
    public List<BlockDescriptor> palette() { return palette; }
    public long estimatedBytes() { return estimatedBytes; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        return other instanceof BlueprintDefinition definition && version == definition.version
                && id.equals(definition.id) && digest.equals(definition.digest)
                && blocks.equals(definition.blocks) && markers.equals(definition.markers);
    }
    @Override public int hashCode() { return digest.hashCode(); }

    /** Creates a new definition; a persisted definition supplies its digest to the constructor. */
    public static BlueprintDefinition create(String id, int version, List<BlockSpec> blocks, Map<String, BlockOffset> markers) {
        return new BlueprintDefinition(id, version, null, blocks, markers);
    }

    public record BlockSpec(BlockOffset offset, BlockDescriptor block) {
        public BlockSpec {
            Objects.requireNonNull(offset, "offset");
            Objects.requireNonNull(block, "block");
        }
    }

    private static String canonicalDigest(String id, int version, List<BlockSpec> blocks, Map<String, BlockOffset> markers) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (var output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), hash))) {
                output.writeInt(SCHEMA_VERSION);
                output.writeUTF(id);
                output.writeInt(version);
                output.writeInt(blocks.size());
                for (BlockSpec spec : blocks) {
                    writeOffset(output, spec.offset());
                    output.writeUTF(spec.block().blockId());
                    output.writeUTF(spec.block().itemId());
                    output.writeInt(spec.block().properties().size());
                    for (var property : spec.block().properties().entrySet()) {
                        output.writeUTF(property.getKey());
                        output.writeUTF(property.getValue());
                    }
                }
                output.writeInt(markers.size());
                for (var marker : markers.entrySet()) {
                    output.writeUTF(marker.getKey());
                    writeOffset(output, marker.getValue());
                }
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException | IOException error) {
            throw new IllegalStateException("Cannot digest blueprint", error);
        }
    }

    private static void writeOffset(DataOutputStream output, BlockOffset offset) throws IOException {
        output.writeInt(offset.x()); output.writeInt(offset.y()); output.writeInt(offset.z());
    }
}
