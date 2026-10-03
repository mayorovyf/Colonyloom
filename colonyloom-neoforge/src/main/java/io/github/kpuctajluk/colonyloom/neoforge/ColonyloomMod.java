package io.github.kpuctajluk.colonyloom.neoforge;

import com.mojang.logging.LogUtils;
import io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime;
import io.github.kpuctajluk.colonyloom.minecraft.content.ContentLoader;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
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
    private final Map<MinecraftServer, IdentityPlatform> identities = new IdentityHashMap<>();
    private final Map<MinecraftServer, net.minecraft.server.packs.resources.ResourceManager> contentManagers = new IdentityHashMap<>();
    private final SimulationConfig simulationConfig;
    private final Map<MinecraftServer, io.github.kpuctajluk.colonyloom.core.config.SimulationLimits> configuredLimits = new IdentityHashMap<>();
    private final Map<MinecraftServer,java.util.List<String>> managementBlueprints=new IdentityHashMap<>();
    private final ManagementNetwork management;
    private final net.neoforged.neoforge.common.world.chunk.TicketController tickets = new net.neoforged.neoforge.common.world.chunk.TicketController(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MOD_ID, "runtime"), NeoForgeChunkAccess::validate);

    public ColonyloomMod(IEventBus modBus, net.neoforged.fml.ModContainer container) {
        simulationConfig = new SimulationConfig(container);
        management=new ManagementNetwork(container.getModInfo().getVersion().toString(),this::requireRuntime,
                server -> identities.get(server),server -> managementBlueprints.getOrDefault(server,java.util.List.of()));
        modBus.addListener(management::register);
        CitizenRegistration.register(modBus);
        NeoForgeStorageIdentity.register(modBus);
        modBus.addListener((net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent event) -> event.register(tickets));
        NeoForge.EVENT_BUS.addListener(this::onBlockChange);
        NeoForge.EVENT_BUS.addListener(this::onReload);
        NeoForge.EVENT_BUS.addListener(this::onCommands);
        NeoForge.EVENT_BUS.addListener(this::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(this::onEntityLeave);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,false,this::onDeath);
        NeoForge.EVENT_BUS.addListener(this::onInteract);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,false,this::onMetricsPreTick);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,false,this::onMetricsPostTick);
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
        MinecraftServerRuntime runtime = MinecraftServerRuntime.start(server,net.neoforged.neoforge.common.IOUtilities::waitUntilIOWorkerComplete);
        runtimes.put(server, runtime);
        var content=ContentLoader.load(server.getResourceManager(),server.registryAccess());
        runtime.configureContent(content);
        managementBlueprints.put(server,content.blueprints().keySet().stream().sorted().toList());
        var limits = simulationConfig.snapshot();
        runtime.core().updateLimits(limits);
        configuredLimits.put(server, limits);
        contentManagers.put(server,server.getResourceManager());
        var executorEvent=NeoForge.EVENT_BUS.post(new ConstructionExecutorEvent(server,runtime));
        runtime.configureStorage(new NeoForgeStorageIdentity());
        runtime.configurePhysical(new NeoForgeChunkAccess(server, tickets),new NeoForgeItemInteraction(),executorEvent.observer(),
                (context,principal,source,destination,amount) -> !NeoForge.EVENT_BUS.post(new StorageTransferEvent(server,context,principal,source,destination,amount)).isCanceled(),executorEvent.transferObserver(),new NeoForgeRecipeProtection(server),executorEvent.recipeObserver(),
                (context,principal,prepared) -> !NeoForge.EVENT_BUS.post(new FoodConsumeEvent(server,context,principal,prepared)).isCanceled(),executorEvent.foodObserver());
        IdentityPlatform identity = new IdentityPlatform(server, runtime);
        identities.put(server, identity);
        identity.reconcileLoaded();
        if (!runtime.persistence().isAvailable()) {
            LOGGER.error("{}; Colonyloom mutations are disabled; existing save is not replaced", runtime.persistence().failureReason());
        }
        LOGGER.info("Colonyloom runtime started: session={}, world={}, activeRuntimes={}",
                runtime.sessionId(), runtime.worldPath(), runtimes.size());
    }
    private synchronized void onMetricsPreTick(ServerTickEvent.Pre event) {
        if(System.getProperty("colonyloom.test.platformScenario","").isEmpty()) requireRuntime(event.getServer()).metricsTickStarted();
    }
    private synchronized void onMetricsPostTick(ServerTickEvent.Post event) {
        if(System.getProperty("colonyloom.test.platformScenario","").isEmpty()) requireRuntime(event.getServer()).metricsTickFinished();
    }

    private synchronized void onServerPostTick(ServerTickEvent.Post event) {
        MinecraftServerRuntime runtime=requireRuntime(event.getServer());
        var limits = simulationConfig.snapshot();
        if (!limits.equals(configuredLimits.get(event.getServer()))) {
            runtime.core().updateLimits(limits);
            runtime.physicalLimitsUpdated();
            configuredLimits.put(event.getServer(), limits);
        }
        var manager=event.getServer().getResourceManager();
        if (contentManagers.get(event.getServer())!=manager) {
            var content=ContentLoader.load(manager,event.getServer().registryAccess());
            runtime.configureContent(content);
            managementBlueprints.put(event.getServer(),content.blueprints().keySet().stream().sorted().toList());
            contentManagers.put(event.getServer(),manager);
        }
        runtime.postTick(event.getServer());
        management.tick(event.getServer(),runtime.serverTick());
    }

    private synchronized void onServerStopping(ServerStoppingEvent event) {
        MinecraftServerRuntime runtime = requireRuntime(event.getServer());
        management.stopped(event.getServer());
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
            identities.remove(server);
            contentManagers.remove(server);
            configuredLimits.remove(server);
            managementBlueprints.remove(server);
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

    private void onReload(AddReloadListenerEvent event) {
        event.addListener(new ContentLoader(event.getRegistryAccess()));
    }
    private synchronized void onBlockChange(net.neoforged.neoforge.event.level.BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            var runtime = runtimes.get(level.getServer());
            if (runtime != null && runtime.navigation() != null) runtime.navigation().invalidate(new io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey(
                    level.dimension().location().toString(), event.getPos().getX() >> 4, event.getPos().getZ() >> 4));
            if (runtime != null && runtime.storage() != null) runtime.storage().invalidate(new io.github.kpuctajluk.colonyloom.core.colony.WorldPosition(
                    level.dimension().location().toString(), event.getPos().getX(), event.getPos().getY(), event.getPos().getZ()));
        }
    }

    private void onCommands(RegisterCommandsEvent event) {
        ColonyloomBrigadier.register(event.getDispatcher(), server -> {
            synchronized (this) {
                IdentityPlatform identity = identities.get(server);
                if (identity == null) throw new IllegalStateException("Colonyloom server is not ready");
                return identity;
            }
        },management::open);
    }

    private synchronized void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            IdentityPlatform identity = identities.get(level.getServer());
            if (identity != null) identity.join(event.getEntity());
        }
    }

    private synchronized void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            IdentityPlatform identity = identities.get(level.getServer());
            if (identity != null) identity.leave(event.getEntity());
        }
    }

    private synchronized void onDeath(LivingDeathEvent event) {
        if (event.getEntity().level() instanceof net.minecraft.server.level.ServerLevel level) {
            IdentityPlatform identity = identities.get(level.getServer());
            if (identity != null) identity.death(event);
        }
    }

    private synchronized void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity().level() instanceof net.minecraft.server.level.ServerLevel level) {
            IdentityPlatform identity = identities.get(level.getServer());
            if (identity != null) identity.interact(event);
        }
    }
}
