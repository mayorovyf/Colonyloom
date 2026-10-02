package io.github.kpuctajluk.colonyloom.minecraft.runtime;

import io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime;
import io.github.kpuctajluk.colonyloom.core.citizen.ProfessionDefinition;
import io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonyPersistence;
import java.util.Collection;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Binds a core session to one exact Minecraft server for its whole lifetime. */
public final class MinecraftServerRuntime {
    private final MinecraftServer server;
    private final ServerRuntime runtime;
    private final Path worldPath;
    private int lastMinecraftTick;
    private final ColonyPersistence persistence;

    private MinecraftServerRuntime(MinecraftServer server) {
        this.server = server;
        this.worldPath = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        this.lastMinecraftTick = server.getTickCount();
        this.runtime = ServerRuntime.start(server.getRunningThread());
        this.persistence = ColonyPersistence.open(server, runtime);
    }

    public static MinecraftServerRuntime start(MinecraftServer server) {
        requireServerThread(server);
        if (server.isStopped()) {
            throw new IllegalStateException("Cannot reuse a stopped Minecraft server");
        }
        return new MinecraftServerRuntime(server);
    }

    public UUID sessionId() {
        return runtime.sessionId();
    }

    public Path worldPath() {
        return worldPath;
    }

    public long serverTick() {
        return runtime.serverTick();
    }

    public ServerRuntime core() {
        runtime.requireOwnerThread();
        return runtime;
    }

    public ColonyPersistence persistence() {
        runtime.requireOwnerThread();
        return persistence;
    }

    public void configureProfessions(Collection<ProfessionDefinition> definitions) {
        runtime.configureCommands(persistence::ensureSessionDirty, definitions);
    }

    public void postTick(MinecraftServer eventServer) {
        requireBoundServer(eventServer);
        int minecraftTick = server.getTickCount();
        // Minecraft's int counter can wrap; the session's long counter must not.
        if (minecraftTick != lastMinecraftTick + 1) {
            throw new IllegalStateException("Duplicate or non-sequential Minecraft post-tick event");
        }
        runtime.tick(runtime.serverTick() + 1);
        lastMinecraftTick = minecraftTick;
    }

    public void beginStopping(MinecraftServer eventServer) {
        requireBoundServer(eventServer);
        runtime.beginStopping();
        if (persistence.isAvailable()) persistence.checkpointAndClean();
    }

    public void stop(MinecraftServer eventServer) {
        requireBoundServer(eventServer);
        runtime.stop();
    }

    public static void requireServerThread(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            throw new IllegalStateException("Runtime lifecycle must run on the Minecraft server thread");
        }
    }

    private void requireBoundServer(MinecraftServer eventServer) {
        if (eventServer != server) {
            throw new IllegalStateException("Runtime cannot be reused by another Minecraft server");
        }
        requireServerThread(eventServer);
        runtime.requireOwnerThread();
    }
}
