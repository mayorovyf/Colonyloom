package colonyloom.neoforge.life;

import colonyloom.core.life.content.LifeContent;
import colonyloom.core.outfit.OutfitCatalog;
import colonyloom.minecraft.life.LifeContentLoader;
import colonyloom.minecraft.life.LifeReports;
import colonyloom.minecraft.life.MinecraftLifeService;
import colonyloom.minecraft.life.entity.ResidentChildEntity;
import colonyloom.minecraft.runtime.MinecraftServerRuntime;
import colonyloom.neoforge.ConstructionExecutorEvent;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(LifeMod.teen_pro_YT_MOD_ID)
public final class LifeMod {
    public static final String teen_pro_YT_MOD_ID = "colonyloom";
    static final int teen_pro_YT_SETTINGS_INTERVAL = 20;
    static final int teen_pro_YT_FAILURE_LIMIT = 3;
    static final int teen_pro_YT_EVENT_FAILURE_LIMIT = 8;
    private static final Logger teen_pro_YT_LOGGER = LogUtils.getLogger();
    private static final DeferredRegister<EntityType<?>> teen_pro_YT_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, teen_pro_YT_MOD_ID);
    public static final DeferredHolder<EntityType<?>, EntityType<ResidentChildEntity>> teen_pro_YT_CHILD = teen_pro_YT_TYPES.register(ResidentChildEntity.teen_pro_YT_ID,
            () -> EntityType.Builder.<ResidentChildEntity>of(ResidentChildEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F).clientTrackingRange(8).build(teen_pro_YT_MOD_ID + ":" + ResidentChildEntity.teen_pro_YT_ID));
    private static final Map<MinecraftServer, MinecraftServerRuntime> teen_pro_YT_RUNTIMES = new IdentityHashMap<>();
    private static final Map<MinecraftServer, MinecraftLifeService> teen_pro_YT_SERVICES = new IdentityHashMap<>();
    private static final Map<MinecraftServer, Integer> teen_pro_YT_FAILURES = new IdentityHashMap<>();
    private static final Map<MinecraftServer, Integer> teen_pro_YT_EVENT_FAILURES = new IdentityHashMap<>();
    private static OutfitCatalog teen_pro_YT_outfits;

    public LifeMod(IEventBus modBus, ModContainer container) {
        teen_pro_YT_TYPES.register(modBus);
        modBus.addListener((EntityAttributeCreationEvent event) -> event.put(teen_pro_YT_CHILD.get(), ResidentChildEntity.attributes().build()));
        modBus.addListener(LifeNetwork::register);
        container.registerConfig(ModConfig.Type.SERVER, LifeConfig.teen_pro_YT_SPEC, LifeConfig.teen_pro_YT_FILE);
        NeoForge.EVENT_BUS.addListener((ConstructionExecutorEvent event) -> runtime(event.server(), event.runtime()));
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, false, LifeMod::tick);
        NeoForge.EVENT_BUS.addListener(LifeMod::save);
        NeoForge.EVENT_BUS.addListener(LifeMod::stopped);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, false, LifeMod::joined);
        NeoForge.EVENT_BUS.addListener(LifeMod::left);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LifeMod::died);
        NeoForge.EVENT_BUS.addListener(LifeMod::injured);
        NeoForge.EVENT_BUS.addListener(LifeMod::tracking);
        NeoForge.EVENT_BUS.addListener(LifeMod::loggedOut);
        NeoForge.EVENT_BUS.addListener(LifeMod::interact);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, false, (RegisterCommandsEvent event) -> LifeCommands.register(event.getDispatcher()));
    }

    public static synchronized MinecraftLifeService service(MinecraftServer server) {
        return server == null ? null : teen_pro_YT_SERVICES.get(server);
    }

    static boolean disabled(MinecraftServer server) {
        if (Boolean.getBoolean("colonyloom.life.force")) return false;
        if (server instanceof GameTestServer) return true;
        for (String key : System.getProperties().stringPropertyNames()) if (key.startsWith("colonyloom.test")) return true;
        return false;
    }

    public static synchronized OutfitCatalog outfits() {
        if (teen_pro_YT_outfits == null) {
            try {
                teen_pro_YT_outfits = OutfitCatalog.load(path -> LifeMod.class.getResourceAsStream("/" + OutfitCatalog.teen_pro_YT_ROOT + path));
            } catch (IOException | RuntimeException failure) {
                teen_pro_YT_LOGGER.error("Colonyloom outfits could not be read; residents keep their plain look", failure);
                teen_pro_YT_outfits = OutfitCatalog.teen_pro_YT_EMPTY;
            }
        }
        return teen_pro_YT_outfits;
    }

    private static synchronized void runtime(MinecraftServer server, MinecraftServerRuntime runtime) {
        teen_pro_YT_RUNTIMES.put(server, runtime);
    }

    private static synchronized MinecraftLifeService start(MinecraftServer server) {
        MinecraftLifeService service = teen_pro_YT_SERVICES.get(server);
        if (service != null) return service;
        if (teen_pro_YT_FAILURES.getOrDefault(server, 0) >= teen_pro_YT_FAILURE_LIMIT || disabled(server)) return null;
        MinecraftServerRuntime runtime = teen_pro_YT_RUNTIMES.get(server);
        if (runtime == null) return null;
        try {
            LifeContent content = LifeContentLoader.load();
            service = new MinecraftLifeService(server, runtime, teen_pro_YT_CHILD::get, LifeNetwork.teen_pro_YT_SYNC, content, LifeConfig.snapshot(), outfits());
            teen_pro_YT_SERVICES.put(server, service);
            for (ServerLevel level : server.getAllLevels()) {
                List<Entity> loaded = new ArrayList<>();
                level.getAllEntities().forEach(loaded::add);
                for (Entity entity : loaded) service.joined(entity);
            }
            return service;
        } catch (RuntimeException | LinkageError failure) {
            teen_pro_YT_SERVICES.remove(server);
            teen_pro_YT_FAILURES.put(server, teen_pro_YT_FAILURE_LIMIT);
            teen_pro_YT_LOGGER.error("Colonyloom life simulation could not start; colonies keep working without it", failure);
            return null;
        }
    }

    private static void pause(MinecraftServer server, MinecraftLifeService service) {
        teen_pro_YT_LOGGER.error("Colonyloom life simulation is paused until the world is reopened; colonies keep working");
        try {
            service.save();
        } catch (RuntimeException | LinkageError failure) {
            teen_pro_YT_LOGGER.error("Colonyloom life state could not be saved while pausing", failure);
        }
        synchronized (LifeMod.class) {
            teen_pro_YT_SERVICES.remove(server);
            teen_pro_YT_FAILURES.put(server, teen_pro_YT_FAILURE_LIMIT);
        }
    }

    private static void guarded(MinecraftServer server, String what, Consumer<MinecraftLifeService> action) {
        MinecraftLifeService service = service(server);
        if (service == null) return;
        try {
            action.accept(service);
        } catch (RuntimeException | LinkageError failure) {
            int failures;
            synchronized (LifeMod.class) { failures = teen_pro_YT_EVENT_FAILURES.merge(server, 1, Integer::sum); }
            teen_pro_YT_LOGGER.error("Colonyloom life {} failed ({} of {})", what, failures, teen_pro_YT_EVENT_FAILURE_LIMIT, failure);
            if (failures >= teen_pro_YT_EVENT_FAILURE_LIMIT) pause(server, service);
        }
    }

    private static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        MinecraftLifeService service = start(server);
        if (service == null) return;
        try {
            if (server.getTickCount() % teen_pro_YT_SETTINGS_INTERVAL == 0) service.settings(LifeConfig.snapshot());
            service.tick();
            synchronized (LifeMod.class) { teen_pro_YT_FAILURES.remove(server); }
        } catch (RuntimeException | LinkageError failure) {
            int failures;
            synchronized (LifeMod.class) { failures = teen_pro_YT_FAILURES.merge(server, 1, Integer::sum); }
            teen_pro_YT_LOGGER.error("Colonyloom life tick failed ({} of {})", failures, teen_pro_YT_FAILURE_LIMIT, failure);
            if (failures >= teen_pro_YT_FAILURE_LIMIT) pause(server, service);
        }
    }

    private static void save(LevelEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        guarded(level.getServer(), "save", MinecraftLifeService::save);
    }

    private static void stopped(ServerStoppedEvent event) {
        synchronized (LifeMod.class) {
            teen_pro_YT_SERVICES.remove(event.getServer());
            teen_pro_YT_RUNTIMES.remove(event.getServer());
            teen_pro_YT_FAILURES.remove(event.getServer());
            teen_pro_YT_EVENT_FAILURES.remove(event.getServer());
        }
        LifeNetwork.clear();
    }

    private static void joined(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        guarded(level.getServer(), "entity join", service -> service.joined(event.getEntity()));
    }

    private static void left(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        guarded(level.getServer(), "entity leave", service -> service.left(event.getEntity()));
    }

    private static void died(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        guarded(level.getServer(), "death", service -> service.died(event.getEntity(), event.getSource()));
    }

    private static void injured(LivingDamageEvent.Post event) {
        if (event.getNewDamage() <= 0.0F || !(event.getEntity().level() instanceof ServerLevel level)) return;
        guarded(level.getServer(), "injury", service -> service.injured(event.getEntity(), event.getSource()));
    }

    private static void tracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        guarded(player.server, "tracking", service -> service.tracking(player, event.getTarget()));
    }

    private static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LifeNetwork.forget(event.getEntity().getUUID());
    }

    private static void interact(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof ResidentChildEntity child) || event.getHand() != InteractionHand.MAIN_HAND) return;
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        guarded(player.server, "child card", service -> {
            UUID lifeId = service.lifeOf(child);
            if (lifeId != null) player.sendSystemMessage(LifeReports.info(service, lifeId));
        });
    }
}
