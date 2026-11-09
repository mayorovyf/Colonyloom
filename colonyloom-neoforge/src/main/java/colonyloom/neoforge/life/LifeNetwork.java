package colonyloom.neoforge.life;

import colonyloom.minecraft.life.LifeSync;
import colonyloom.minecraft.life.LifeViews;
import colonyloom.minecraft.life.MinecraftLifeService;
import colonyloom.minecraft.life.net.LifePayloads;
import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

public final class LifeNetwork {
    static final int teen_pro_YT_REQUEST_COOLDOWN = 4;
    private static final Logger teen_pro_YT_LOGGER = LogUtils.getLogger();
    private static volatile boolean teen_pro_YT_clientFailed;
    private static volatile Consumer<CustomPacketPayload> teen_pro_YT_client = payload -> {};
    private static final Map<UUID, Long> teen_pro_YT_requests = new HashMap<>();

    static final LifeSync teen_pro_YT_SYNC = new LifeSync() {
        @Override
        public void outfit(Entity entity, String outfit) {
            PacketDistributor.sendToPlayersTrackingEntity(entity, new LifePayloads.Outfit(entity.getId(), outfit));
        }

        @Override
        public void outfit(ServerPlayer player, Entity entity, String outfit) {
            PacketDistributor.sendToPlayer(player, new LifePayloads.Outfit(entity.getId(), outfit));
        }

        @Override
        public void speech(Entity entity, String spec) {
            PacketDistributor.sendToPlayersTrackingEntity(entity, new LifePayloads.Speech(entity.getId(), spec));
        }

        @Override
        public void view(ServerPlayer player, LifePayloads.View view) {
            PacketDistributor.sendToPlayer(player, view);
        }
    };

    private LifeNetwork() {}

    public static void client(Consumer<CustomPacketPayload> sink) {
        teen_pro_YT_client = sink == null ? payload -> {} : sink;
    }

    static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(LifePayloads.teen_pro_YT_VERSION);
        registrar.playToClient(LifePayloads.Outfit.teen_pro_YT_TYPE, LifePayloads.Outfit.teen_pro_YT_CODEC, LifeNetwork::toClient);
        registrar.playToClient(LifePayloads.Speech.teen_pro_YT_TYPE, LifePayloads.Speech.teen_pro_YT_CODEC, LifeNetwork::toClient);
        registrar.playToClient(LifePayloads.View.teen_pro_YT_TYPE, LifePayloads.View.teen_pro_YT_CODEC, LifeNetwork::toClient);
        registrar.playToServer(LifePayloads.ViewRequest.teen_pro_YT_TYPE, LifePayloads.ViewRequest.teen_pro_YT_CODEC, LifeNetwork::request);
    }

    private static void toClient(CustomPacketPayload payload, IPayloadContext context) {
        try {
            teen_pro_YT_client.accept(payload);
        } catch (RuntimeException | LinkageError failure) {
            if (!teen_pro_YT_clientFailed) teen_pro_YT_LOGGER.error("Colonyloom life could not show {}; later failures are not logged", payload.getClass().getSimpleName(), failure);
            teen_pro_YT_clientFailed = true;
        }
    }

    private static void request(LifePayloads.ViewRequest request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        long now = player.server.getTickCount();
        Long last = teen_pro_YT_requests.get(player.getUUID());
        if (last != null && now - last >= 0 && now - last < teen_pro_YT_REQUEST_COOLDOWN) return;
        teen_pro_YT_requests.put(player.getUUID(), now);
        MinecraftLifeService service = LifeMod.service(player.server);
        LifePayloads.View view;
        try {
            view = service == null ? LifeViews.empty("screen.colonyloom.life.disabled") : service.view(player, request.colony(), request.tab(), request.page());
        } catch (RuntimeException | LinkageError failure) {
            teen_pro_YT_LOGGER.error("Colonyloom life page could not be built for {}", player.getGameProfile().getName(), failure);
            view = LifeViews.empty("screen.colonyloom.life.empty");
        }
        context.reply(view);
    }

    static void forget(UUID player) {
        teen_pro_YT_requests.remove(player);
    }

    static void clear() {
        teen_pro_YT_requests.clear();
    }
}
