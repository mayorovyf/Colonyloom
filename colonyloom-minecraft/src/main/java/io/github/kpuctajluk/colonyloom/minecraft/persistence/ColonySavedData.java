package io.github.kpuctajluk.colonyloom.minecraft.persistence;

import io.github.kpuctajluk.colonyloom.core.persistence.RegistrySnapshot;
import java.io.IOException;
import java.nio.file.Path;
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
    /** Read-only bounded preflight of Minecraft's compressed {data: DTO, DataVersion} envelope. */
    public static ColonySavedData preflight(Path path, HolderLookup.Provider registries) throws IOException {
        try {
            return load(RegistryNbt.compound(DurableNbt.read(path, DurableNbt.STATE_LIMIT), "data"), registries);
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
        CompoundTag encoded = RegistryNbt.encode(snapshot, checkpointId, retained);
        tag.merge(encoded);
        return tag;
    }

    CompoundTag diskEnvelope(HolderLookup.Provider registries) {
        CompoundTag envelope = new CompoundTag();
        envelope.put("data", save(new CompoundTag(), registries));
        NbtUtils.addCurrentDataVersion(envelope);
        return envelope;
    }
}
