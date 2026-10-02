package io.github.kpuctajluk.colonyloom.neoforge;

import com.mojang.logging.LogUtils;
import io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(ColonyloomMod.MOD_ID)
public final class ColonyloomMod {
    public static final String MOD_ID = "colonyloom";
    private static final Logger LOGGER = LogUtils.getLogger();

    // Instance-owned, identity-keyed sessions; stopped servers are never retained.
    // The monitor also publishes state between successive integrated-server threads.
    private final Map<MinecraftServer, MinecraftServerRuntime> runtimes = new IdentityHashMap<>();

    public ColonyloomMod() {
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerPostTick);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
    }

    private synchronized void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        MinecraftServerRuntime.requireServerThread(server);
        if (runtimes.containsKey(server)) {
            throw new IllegalStateException("Duplicate Colonyloom server-starting event");
        }
        MinecraftServerRuntime runtime = MinecraftServerRuntime.start(server);
        runtimes.put(server, runtime);
        LOGGER.info("Colonyloom runtime started: session={}, world={}, activeRuntimes={}",
                runtime.sessionId(), runtime.worldPath(), runtimes.size());
    }

    private synchronized void onServerPostTick(ServerTickEvent.Post event) {
        requireRuntime(event.getServer()).postTick(event.getServer());
    }

    private synchronized void onServerStopping(ServerStoppingEvent event) {
        MinecraftServerRuntime runtime = requireRuntime(event.getServer());
        runtime.beginStopping(event.getServer());
        LOGGER.info("Colonyloom runtime stopping: session={}, world={}, ticks={}, activeRuntimes={}",
                runtime.sessionId(), runtime.worldPath(), runtime.serverTick(), runtimes.size());
    }

    private synchronized void onServerStopped(ServerStoppedEvent event) {
        MinecraftServer server = event.getServer();
        MinecraftServerRuntime runtime = requireRuntime(server);
        try {
            runtime.stop(server);
        } finally {
            // Even an invalid shutdown sequence must not retain an old integrated world.
            runtimes.remove(server);
            LOGGER.info("Colonyloom runtime released: session={}, world={}, ticks={}, activeRuntimes={}",
                    runtime.sessionId(), runtime.worldPath(), runtime.serverTick(), runtimes.size());
        }
    }

    private MinecraftServerRuntime requireRuntime(MinecraftServer server) {
        MinecraftServerRuntime.requireServerThread(server);
        MinecraftServerRuntime runtime = runtimes.get(server);
        if (runtime == null) {
            throw new IllegalStateException("Colonyloom lifecycle event has no runtime for this server");
        }
        return runtime;
    }
}
