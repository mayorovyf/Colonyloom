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
    private io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager chunks;
    private io.github.kpuctajluk.colonyloom.core.navigation.NavigationService navigation;
    private CitizenAdmissionService citizens;

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
        runtime.setSimulationEnabled(persistence.isAvailable());
    }
    public void configurePhysical(io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager.ChunkAccess access) {
        runtime.requireOwnerThread();
        if (chunks != null) throw new IllegalStateException("Physical services already configured");
        chunks = new io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager(runtime.registry(), runtime.budgets(), access);
        navigation = new io.github.kpuctajluk.colonyloom.core.navigation.NavigationService(runtime.registry(), runtime.budgets(), chunks,
                new io.github.kpuctajluk.colonyloom.minecraft.navigation.MinecraftNavigationBackend(server, runtime.registry(), chunks));
        citizens = new CitizenAdmissionService(server, runtime, chunks);
        runtime.scheduler().beforeWork(tick -> {
            chunks.tick(tick);
            citizens.tick();
            navigation.tick(tick);
            runtime.registry().targetClaims().tick();
        });
        runtime.scheduler().movementExecutor(new io.github.kpuctajluk.colonyloom.core.scheduler.SimulationScheduler.MovementExecutor() {
            public void step(io.github.kpuctajluk.colonyloom.core.work.WorkOrder work, long tick) {
                var citizen = runtime.registry().citizen(work.assignee());
                try {
                    if (navigation.request(work.id(), work.colonyId(), citizen.citizenId(), citizen.bindingEpoch(), 0, work.target(), work.lane(), work.priority()) == null) {
                        runtime.registry().workBoard().waitAssigned(work.id(), io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.RECONCILING, "move");
                        return;
                    }
                } catch (io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.AdmissionException denied) {
                    runtime.registry().workBoard().waitAssigned(work.id(), denied.reason() == io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Reason.CRITICAL_CAPACITY
                            ? io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.CRITICAL_CAPACITY : io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.STATE_LIMIT, "move");
                    return;
                }
                if (navigation.atTarget(work.id())) {
                    runtime.registry().workBoard().transition(work.id(), io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.COMPLETED,
                            io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.NONE, "completed");
                    navigation.cancel(work.id());
                } else if (navigation.state(work.id()) == io.github.kpuctajluk.colonyloom.core.navigation.NavigationService.State.WAITING) {
                    runtime.registry().workBoard().waitAssigned(work.id(), navigation.reason(work.id()), "move");
                }
            }
            public void cancel(UUID workId) { navigation.cancel(workId); }
        });
    }
    public io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager chunks() { runtime.requireOwnerThread(); return chunks; }
    public io.github.kpuctajluk.colonyloom.core.navigation.NavigationService navigation() { runtime.requireOwnerThread(); return navigation; }
    public void citizenObserved(UUID id) { runtime.requireOwnerThread(); if (citizens != null) citizens.observe(id); }
    public void physicalLimitsUpdated() { runtime.requireOwnerThread(); if (chunks != null) chunks.limitsUpdated(); }

    public void postTick(MinecraftServer eventServer) {
        requireBoundServer(eventServer);
        int minecraftTick = server.getTickCount();
        // Minecraft's int counter can wrap; the session's long counter must not.
        if (minecraftTick != lastMinecraftTick + 1) {
            throw new IllegalStateException("Duplicate or non-sequential Minecraft post-tick event");
        }
        if (!persistence.isAvailable()) runtime.setSimulationEnabled(false);
        runtime.tick(runtime.serverTick() + 1);
        lastMinecraftTick = minecraftTick;
    }

    public void beginStopping(MinecraftServer eventServer) {
        requireBoundServer(eventServer);
        runtime.beginStopping();
        if (navigation != null) navigation.close();
        if (citizens != null) citizens.close();
        if (chunks != null) chunks.close();
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
