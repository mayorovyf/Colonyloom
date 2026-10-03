package io.github.kpuctajluk.colonyloom.minecraft.persistence;

import io.github.kpuctajluk.colonyloom.core.persistence.RegistrySnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import net.minecraft.nbt.Tag;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.saveddata.SavedData;

/** Minecraft persistence adapter; ServerRuntime remains the authoritative registry owner. */
public final class ColonySavedData extends SavedData {
    public static final String NAME = "colonyloom";
    public static final int SCHEMA_VERSION = 1;
    private final Map<String, List<CompoundTag>> retained;
    private final Set<UUID> contentBlockedColonies;
    private RegistrySnapshot snapshot;
    private UUID checkpointId;
    private java.util.function.Supplier<RegistrySnapshot> snapshotSource;
    private io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics metrics;
    public void metrics(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics value) { metrics=java.util.Objects.requireNonNull(value); }

    private ColonySavedData(RegistrySnapshot snapshot, UUID checkpointId,
            Map<String, List<CompoundTag>> retained, Set<UUID> contentBlockedColonies) {
        this.snapshot = snapshot;
        this.checkpointId = checkpointId;
        this.retained = retained;
        this.contentBlockedColonies = Set.copyOf(contentBlockedColonies);
    }

    public static ColonySavedData empty(RegistrySnapshot snapshot) {
        return new ColonySavedData(snapshot, UUID.randomUUID(), Map.of(), Set.of());
    }

    public static ColonySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryNbt.Decoded decoded = RegistryNbt.decode(tag);
        return new ColonySavedData(decoded.snapshot(), RegistryNbt.uuid(tag, "checkpointId"),
                decoded.retained(), decoded.blockedColonies());
    }
    /** Bounded preflight; keeps the original compressed checkpoint before a supported migration. */
    public static ColonySavedData preflight(Path path, HolderLookup.Provider registries) throws IOException {
        try {
            var tag = RegistryNbt.compound(DurableNbt.read(path, DurableNbt.STATE_LIMIT), "data");
            var loaded = load(tag, registries);
            var production = tag.getList("productionOrders", Tag.TAG_COMPOUND);
            if (production.size() > io.github.kpuctajluk.colonyloom.core.supply.SupplyRegistry.MAX_ORDERS) throw new IllegalArgumentException("Production migration envelope exceeded");
            for (int i = 0; i < production.size(); i++) {
                var order = production.getCompound(i);
                if (order.getString("typeId").equals(SupplyNbt.PRODUCTION) && order.getInt("schemaVersion") == 1) {
                    Path directory = path.resolveSibling("colonyloom-backups");
                    Files.createDirectories(directory);
                    Path backup = directory.resolve(loaded.checkpointId() + "-v1.dat");
                    try { Files.copy(path, backup); }
                    catch (FileAlreadyExistsException exists) { if (Files.mismatch(path, backup) != -1) throw new IOException("Conflicting Colonyloom migration backup: " + backup, exists); }
                    break;
                }
            }
            return loaded;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Colonyloom state: " + path, exception);
        }
    }


    public UUID checkpointId() {
        return checkpointId;
    }

    public RegistrySnapshot snapshot() {
        return snapshot;
    }

    public Set<UUID> contentBlockedColonies() {
        return contentBlockedColonies;
    }

    public int retainedRecordCount() {
        return retained.values().stream().mapToInt(List::size).sum();
    }

    public void bindSnapshotSource(java.util.function.Supplier<RegistrySnapshot> source) {
        snapshotSource = java.util.Objects.requireNonNull(source);
    }
    public void capture(RegistrySnapshot snapshot) {
        this.snapshot = snapshot;
        setDirty();
    }

    public void beginCheckpoint(UUID checkpointId, RegistrySnapshot snapshot) {
        this.checkpointId = checkpointId;
        capture(snapshot);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        long start=metrics==null ? 0 : System.nanoTime();
        try {
        if (snapshotSource != null) snapshot = snapshotSource.get();
        CompoundTag encoded = RegistryNbt.encode(snapshot, checkpointId, retained);
        tag.merge(encoded);
        return tag;
        } finally { if(metrics!=null) metrics.record(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer.SAVE_ENCODE,System.nanoTime()-start); }
    }

    CompoundTag diskEnvelope(HolderLookup.Provider registries) {
        CompoundTag envelope = new CompoundTag();
        envelope.put("data", save(new CompoundTag(), registries));
        NbtUtils.addCurrentDataVersion(envelope);
        return envelope;
    }
}
