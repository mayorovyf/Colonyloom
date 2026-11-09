package colonyloom.minecraft.life.client;

import colonyloom.minecraft.life.LifeText;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;

public final class SpeechBubbles {
    static final int teen_pro_YT_WIDTH = 160;
    static final double teen_pro_YT_RANGE = 24.0D;

    private SpeechBubbles() {}

    public static void render(LivingEntity entity, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        String spec = ResidentVisuals.speech(entity.getId());
        if (spec == null || entity.isInvisible()) return;
        Minecraft minecraft = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        if (dispatcher.distanceToSqr(entity) > teen_pro_YT_RANGE * teen_pro_YT_RANGE) return;
        Font font = minecraft.font;
        List<FormattedCharSequence> lines = font.split(LifeText.component(spec), teen_pro_YT_WIDTH);
        if (lines.isEmpty()) return;
        if (lines.size() > 3) lines = lines.subList(0, 3);
        float fade = Math.max(0.0F, Math.min(1.0F, (1.0F - ResidentVisuals.speechAge(entity.getId(), partialTick)) * 4.0F));
        int alpha = Math.max(4, (int) (fade * 255.0F));
        poseStack.pushPose();
        poseStack.translate(0.0D, entity.getBbHeight() + (entity.hasCustomName() ? 0.85D : 0.6D) + 0.25D * (lines.size() - 1), 0.0D);
        poseStack.mulPose(dispatcher.cameraOrientation());
        poseStack.scale(0.025F, -0.025F, 0.025F);
        Matrix4f matrix = poseStack.last().pose();
        int background = (int) (minecraft.options.getBackgroundOpacity(0.35F) * 255.0F * fade) << 24;
        for (int i = 0; i < lines.size(); i++) {
            FormattedCharSequence line = lines.get(i);
            float x = -font.width(line) / 2.0F;
            font.drawInBatch(line, x, i * 10.0F, (alpha << 24) | 0xFFFFFF, false, matrix, buffer, Font.DisplayMode.NORMAL, background, packedLight);
        }
        poseStack.popPose();
    }
}
