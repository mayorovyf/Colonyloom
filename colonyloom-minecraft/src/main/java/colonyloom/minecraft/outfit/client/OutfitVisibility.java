package colonyloom.minecraft.outfit.client;

import colonyloom.minecraft.life.client.ResidentVisuals;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Set;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.LivingEntity;

public final class OutfitVisibility {
    private OutfitVisibility() {}

    public static void before(HumanoidModel<?> model, LivingEntity entity) {
        String id = model.young ? null : ResidentVisuals.outfit(entity.getId());
        OutfitModels.Baked outfit = id == null ? null : OutfitModels.get(id);
        if (outfit == null) show(model, Set.of());
        else if (outfit.skin() != null) hideAll(model);
        else show(model, outfit.hide());
    }

    public static void after(HumanoidModel<?> model) {
        show(model, Set.of());
    }

    static void skin(HumanoidModel<?> model, OutfitModels.Baked outfit, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        show(model, outfit.hide());
        model.renderToBuffer(poseStack, buffers.getBuffer(RenderType.entityCutoutNoCull(outfit.skin())), light, overlay, -1);
        hideAll(model);
    }

    static void show(HumanoidModel<?> model, Set<String> hidden) {
        model.head.visible = !hidden.contains("head");
        model.hat.visible = !hidden.contains("hat");
        model.body.visible = !hidden.contains("body");
        model.rightArm.visible = !hidden.contains("right_arm");
        model.leftArm.visible = !hidden.contains("left_arm");
        model.rightLeg.visible = !hidden.contains("right_leg");
        model.leftLeg.visible = !hidden.contains("left_leg");
    }

    static void hideAll(HumanoidModel<?> model) {
        model.head.visible = false;
        model.hat.visible = false;
        model.body.visible = false;
        model.rightArm.visible = false;
        model.leftArm.visible = false;
        model.rightLeg.visible = false;
        model.leftLeg.visible = false;
    }
}
