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

    public ColonyloomClientMod(IEventBus modEventBus) {
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
