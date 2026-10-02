package io.github.kpuctajluk.colonyloom.minecraft.persistence;

import io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** One server-thread adapter. A failed load is unavailable, not an empty simulation. */
public final class ColonyPersistence {
    private final MinecraftServer server;
    private final ServerRuntime runtime;
    private final Path statePath;
    private final Path markerPath;
    private ColonySavedData data;
    private String failureReason;
    private boolean sessionDirty;
    private boolean checkpointFinished;

    private ColonyPersistence(MinecraftServer server, ServerRuntime runtime) {
        this.server = Objects.requireNonNull(server);
        this.runtime = Objects.requireNonNull(runtime);
        Path directory = server.getWorldPath(LevelResource.ROOT).resolve("data");
        this.statePath = directory.resolve("colonyloom.dat");
        this.markerPath = directory.resolve("colonyloom-session.nbt");
    }

    public static ColonyPersistence open(MinecraftServer server, ServerRuntime runtime) {
        ColonyPersistence persistence = new ColonyPersistence(server, runtime);
        persistence.requireThread();
        try {
            boolean existing = DurableNbt.exists(persistence.statePath);
            ColonySavedData loaded = existing
                    ? ColonySavedData.preflight(persistence.statePath, server.registryAccess())
                    : ColonySavedData.empty(runtime.registry().snapshot());
            // Validate every known core identity/reference before exposing data to vanilla autosave.
            runtime.registry().restore(loaded.snapshot());
            boolean clean = !existing || persistence.previousMarkerClean(loaded.checkpointId());
            persistence.data = loaded;
            runtime.registry().setBeforeMutation(persistence::ensureSessionDirty);
            if (!loaded.contentBlockedColonies().isEmpty()) {
                persistence.ensureSessionDirty();
                runtime.registry().markContentBlocked(loaded.contentBlockedColonies());
                persistence.capture();
            }
            if (!clean) {
                persistence.ensureSessionDirty();
                runtime.registry().markRecoveryBlocked(loaded.checkpointId());
                persistence.capture();
                // Recovery quarantine itself is durable before any binding/command may execute.
                persistence.writeSnapshot();
            }
            // DimensionDataStorage.readSavedData suppresses decode exceptions; its normal read
            // is unbounded and requires vanilla DFU. Attach the exact bounded preflight result
            // instead of computeIfAbsent, which could silently replace a failed load with empty data.
            server.overworld().getDataStorage().set(ColonySavedData.NAME, loaded);
            loaded.bindSnapshotSource(runtime.registry()::snapshot);
        } catch (IOException | RuntimeException exception) {
            persistence.failureReason = "Colonyloom persistence blocked: " + exception.getMessage();
            persistence.data = null;
        }
        return persistence;
    }

    public boolean isAvailable() {
        requireThread();
        return data != null && failureReason == null && !checkpointFinished;
    }

    public String failureReason() {
        requireThread();
        return failureReason;
    }

    public UUID checkpointId() {
        requireAvailable();
        return data.checkpointId();
    }

    public int retainedRecordCount() {
        requireAvailable();
        return data.retainedRecordCount();
    }

    /** REQUIRED before the first durable mutation, including identity binding transitions. */
    public void ensureSessionDirty() {
        requireAvailable();
        data.setDirty();
        if (sessionDirty) return;
        try {
            DurableNbt.writeVerified(markerPath, marker(false, data.checkpointId()), DurableNbt.MARKER_LIMIT);
            sessionDirty = true;
        } catch (IOException | RuntimeException exception) {
            fail(exception);
        }
    }

    /** Capture after each durable mutation; ordinary SavedData autosave never marks a clean session. */
    public void capture() {
        requireAvailable();
        if (!sessionDirty) throw new IllegalStateException("Mutation was not gated by a durable dirty session marker");
        data.capture(runtime.registry().snapshot());
    }

    /** Forced DTO persistence, notably after explicit accept-world. Does not mark the world clean. */
    public void persistSnapshot() {
        ensureSessionDirty();
        capture();
        try {
            writeSnapshot();
        } catch (IOException | RuntimeException exception) {
            fail(exception);
        }
    }

    /** Call after the parent has stopped command admission and finished the bounded server step. */
    public void checkpointAndClean() {
        requireAvailable();
        ensureSessionDirty();
        UUID checkpoint = UUID.randomUUID();
        data.beginCheckpoint(checkpoint, runtime.registry().snapshot());
        CompoundTag expected = data.diskEnvelope(server.registryAccess());
        try {
            if (!server.saveEverything(true, true, true)) {
                throw new IOException("Minecraft did not save any level");
            }
            // SavedData.save catches IOException and even clears its dirty flag on failure.
            // saveEverything's boolean only reports that levels were visited, not DTO durability.
            DurableNbt.verifyForced(statePath, expected, DurableNbt.STATE_LIMIT);
            DurableNbt.writeVerified(markerPath, marker(true, checkpoint), DurableNbt.MARKER_LIMIT);
            checkpointFinished = true;
        } catch (IOException | RuntimeException exception) {
            data.setDirty();
            try {
                DurableNbt.writeVerified(markerPath, marker(false, checkpoint), DurableNbt.MARKER_LIMIT);
            } catch (IOException | RuntimeException dirtyFailure) {
                exception.addSuppressed(dirtyFailure);
                // Missing/undefined marker is fail-closed at next startup too.
                try {
                    Files.deleteIfExists(markerPath);
                } catch (IOException deletionFailure) {
                    exception.addSuppressed(deletionFailure);
                }
            }
            fail(exception);
        }
    }

    private boolean previousMarkerClean(UUID checkpoint) {
        try {
            CompoundTag marker = DurableNbt.read(markerPath, DurableNbt.MARKER_LIMIT);
            RegistryNbt.uuid(marker, "sessionId");
            return RegistryNbt.bool(marker, "clean") && checkpoint.equals(RegistryNbt.uuid(marker, "checkpointId"));
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private CompoundTag marker(boolean clean, UUID checkpoint) {
        CompoundTag marker = new CompoundTag();
        marker.putUUID("sessionId", runtime.sessionId());
        marker.putBoolean("clean", clean);
        marker.putUUID("checkpointId", checkpoint);
        return marker;
    }

    private void writeSnapshot() throws IOException {
        DurableNbt.writeVerified(statePath, data.diskEnvelope(server.registryAccess()), DurableNbt.STATE_LIMIT);
        data.setDirty(false);
    }

    private void requireThread() {
        runtime.requireOwnerThread();
        if (!server.isSameThread()) throw new IllegalStateException("Persistence must run on its server thread");
    }

    private void requireAvailable() {
        requireThread();
        if (data == null || failureReason != null || checkpointFinished) {
            throw new IllegalStateException(failureReason == null ? "Persistence session is closed" : failureReason);
        }
    }

    private void fail(Exception exception) {
        failureReason = "Colonyloom persistence blocked: " + exception.getMessage();
        throw new IllegalStateException(failureReason, exception);
    }
}
