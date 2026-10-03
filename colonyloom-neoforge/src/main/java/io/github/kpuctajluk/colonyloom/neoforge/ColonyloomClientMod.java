package io.github.kpuctajluk.colonyloom.neoforge;

import io.github.kpuctajluk.colonyloom.minecraft.client.ColonyloomClientBootstrap;
import io.github.kpuctajluk.colonyloom.minecraft.client.entity.CitizenRenderer;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@Mod(value = ColonyloomMod.MOD_ID, dist = Dist.CLIENT)
public final class ColonyloomClientMod {
    private final ColonyloomClientBootstrap bootstrap = new ColonyloomClientBootstrap();
    private final io.github.kpuctajluk.colonyloom.minecraft.client.management.ManagementClient management;

    public ColonyloomClientMod(IEventBus modEventBus,net.neoforged.fml.ModContainer container) {
        management=new io.github.kpuctajluk.colonyloom.minecraft.client.management.ManagementClient(
                payload -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(payload),container.getModInfo().getVersion().toString());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) -> management.connected());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) -> management.disconnected());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((ManagementClientPayloadEvent event) -> management.handle(event.payload()));
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::registerRenderers);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        // FML dispatches setup in parallel; client access belongs in queued main-thread work.
        event.enqueueWork(bootstrap::initialize);
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(CitizenRegistration.CITIZEN.get(), CitizenRenderer::new);
    }
}
