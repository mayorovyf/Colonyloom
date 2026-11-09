package colonyloom.neoforge.life;

import colonyloom.minecraft.client.entity.CitizenRenderer;
import colonyloom.minecraft.client.management.ManagementClient;
import colonyloom.minecraft.client.screen.ColonyScreen;
import colonyloom.minecraft.entity.CitizenEntity;
import colonyloom.minecraft.life.client.ChildRenderer;
import colonyloom.minecraft.life.client.LifeClient;
import colonyloom.minecraft.life.client.ResidentVisuals;
import colonyloom.minecraft.life.client.SpeechBubbles;
import colonyloom.minecraft.life.entity.ResidentChildEntity;
import colonyloom.minecraft.outfit.client.OutfitLayer;
import colonyloom.minecraft.outfit.client.OutfitModels;
import colonyloom.minecraft.outfit.client.OutfitVisibility;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.util.UUID;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

@Mod(value = LifeMod.teen_pro_YT_MOD_ID, dist = Dist.CLIENT)
public final class LifeClientMod {
    private static KeyMapping teen_pro_YT_key;
    static final int teen_pro_YT_BUTTON_WIDTH = 76;
    static final int teen_pro_YT_BUTTON_HEIGHT = 14;
    static final int teen_pro_YT_BUTTON_TOP = 7;
    private static final Logger teen_pro_YT_LOGGER = LogUtils.getLogger();
    private static boolean teen_pro_YT_renderFailed, teen_pro_YT_speechFailed, teen_pro_YT_screenFailed, teen_pro_YT_tickFailed;
    private static Field teen_pro_YT_colonyField;
    private static boolean teen_pro_YT_colonyFieldMissing;

    public LifeClientMod(IEventBus modBus) {
        LifeNetwork.client(LifeClient::handle);
        LifeClient.sender(payload -> PacketDistributor.sendToServer(payload));
        modBus.addListener(LifeClientMod::renderers);
        modBus.addListener(LifeClientMod::layers);
        modBus.addListener(LifeClientMod::keys);
        NeoForge.EVENT_BUS.addListener(LifeClientMod::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LifeClientMod::beforeRender);
        NeoForge.EVENT_BUS.addListener(LifeClientMod::afterRender);
        NeoForge.EVENT_BUS.addListener(LifeClientMod::screenRendered);
        NeoForge.EVENT_BUS.addListener(LifeClientMod::screenClicked);
        NeoForge.EVENT_BUS.addListener(LifeClientMod::left);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> LifeClient.clear());
    }

    static boolean testRun() {
        if (Boolean.getBoolean("colonyloom.life.force")) return false;
        for (String key : System.getProperties().stringPropertyNames()) if (key.startsWith("colonyloom.test")) return true;
        return false;
    }

    private static void keys(RegisterKeyMappingsEvent event) {
        teen_pro_YT_key = new KeyMapping("key.colonyloom.life", GLFW.GLFW_KEY_K, "key.categories.colonyloom.life");
        event.register(teen_pro_YT_key);
    }

    private static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(LifeMod.teen_pro_YT_CHILD.get(), ChildRenderer::new);
    }

    private static void layers(EntityRenderersEvent.AddLayers event) {
        for (EntityType<?> type : event.getEntityTypes()) {
            EntityRenderer<?> renderer = event.getRenderer(type);
            if (renderer instanceof CitizenRenderer citizen) citizen.addLayer(new OutfitLayer<>(citizen));
        }
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            ResidentVisuals.tick();
            KeyMapping key = teen_pro_YT_key;
            while (key != null && key.consumeClick()) if (minecraft.player != null && minecraft.screen == null) LifeClient.open(null);
        } catch (RuntimeException failure) {
            if (!teen_pro_YT_tickFailed) teen_pro_YT_LOGGER.error("Colonyloom life client tick failed; later failures are not logged", failure);
            teen_pro_YT_tickFailed = true;
        }
    }

    static boolean dressed(Entity entity) {
        return entity instanceof CitizenEntity || entity instanceof ResidentChildEntity;
    }

    private static void beforeRender(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (teen_pro_YT_renderFailed || !dressed(entity) || !(event.getRenderer().getModel() instanceof HumanoidModel<?> model)) return;
        try {
            OutfitVisibility.before(model, entity);
        } catch (RuntimeException failure) {
            teen_pro_YT_renderFailed = true;
            OutfitModels.disable();
            OutfitVisibility.after(model);
            teen_pro_YT_LOGGER.error("Colonyloom outfits are switched off after a render failure", failure);
        }
    }

    private static void afterRender(RenderLivingEvent.Post<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (!dressed(entity)) return;
        if (event.getRenderer().getModel() instanceof HumanoidModel<?> model) OutfitVisibility.after(model);
        if (teen_pro_YT_speechFailed) return;
        try {
            SpeechBubbles.render(entity, event.getPartialTick(), event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight());
        } catch (RuntimeException failure) {
            teen_pro_YT_speechFailed = true;
            teen_pro_YT_LOGGER.error("Colonyloom speech bubbles are switched off after a render failure", failure);
        }
    }

    private static void left(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) ResidentVisuals.forget(event.getEntity().getId());
    }

    private static int buttonLeft(Screen screen) {
        return screen.width - 12 - teen_pro_YT_BUTTON_WIDTH;
    }

    private static boolean over(Screen screen, double mouseX, double mouseY) {
        int left = buttonLeft(screen);
        return mouseX >= left && mouseX < left + teen_pro_YT_BUTTON_WIDTH && mouseY >= teen_pro_YT_BUTTON_TOP && mouseY < teen_pro_YT_BUTTON_TOP + teen_pro_YT_BUTTON_HEIGHT;
    }

    private static void screenRendered(ScreenEvent.Render.Post event) {
        if (teen_pro_YT_screenFailed || !(event.getScreen() instanceof ColonyScreen screen) || testRun()) return;
        try {
            drawButton(event, screen);
        } catch (RuntimeException failure) {
            teen_pro_YT_screenFailed = true;
            teen_pro_YT_LOGGER.error("Colonyloom life button is switched off after a failure", failure);
        }
    }

    private static void drawButton(ScreenEvent.Render.Post event, ColonyScreen screen) {
        GuiGraphics graphics = event.getGuiGraphics();
        int left = buttonLeft(screen), top = teen_pro_YT_BUTTON_TOP;
        boolean hover = over(screen, event.getMouseX(), event.getMouseY());
        graphics.fill(left, top, left + teen_pro_YT_BUTTON_WIDTH, top + teen_pro_YT_BUTTON_HEIGHT, hover ? 0xFF4E6E3A : 0xFF2E4424);
        graphics.renderOutline(left, top, teen_pro_YT_BUTTON_WIDTH, teen_pro_YT_BUTTON_HEIGHT, hover ? 0xFFD8F0B8 : 0xFF8DB070);
        KeyMapping key = teen_pro_YT_key;
        Component label = Component.translatable("screen.colonyloom.life.open", key == null ? Component.literal("K") : key.getTranslatedKeyMessage());
        Minecraft minecraft = Minecraft.getInstance();
        graphics.drawCenteredString(minecraft.font, label, left + teen_pro_YT_BUTTON_WIDTH / 2, top + (teen_pro_YT_BUTTON_HEIGHT - 8) / 2, 0xFFFFFF);
    }

    private static void screenClicked(ScreenEvent.MouseButtonPressed.Pre event) {
        if (teen_pro_YT_screenFailed || event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT || !(event.getScreen() instanceof ColonyScreen screen) || testRun()) return;
        if (!over(screen, event.getMouseX(), event.getMouseY())) return;
        event.setCanceled(true);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        try {
            UUID colony = colonyOf(screen);
            ManagementClient client = screen.client();
            LifeClient.open(colony, colony == null ? null : () -> new ColonyScreen(client, colony));
        } catch (RuntimeException failure) {
            teen_pro_YT_screenFailed = true;
            teen_pro_YT_LOGGER.error("Colonyloom life screen could not be opened from the colony window", failure);
        }
    }

    private static UUID colonyOf(ColonyScreen screen) {
        if (teen_pro_YT_colonyFieldMissing) return null;
        try {
            if (teen_pro_YT_colonyField == null) {
                Field field = ColonyScreen.class.getDeclaredField("colonyId");
                field.setAccessible(true);
                teen_pro_YT_colonyField = field;
            }
            return teen_pro_YT_colonyField.get(screen) instanceof UUID colony ? colony : null;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            teen_pro_YT_colonyFieldMissing = true;
            return null;
        }
    }
}
