package colonyloom.minecraft.outfit.client;

import colonyloom.minecraft.life.client.ResidentVisuals;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;

public final class OutfitLayer<T extends LivingEntity, M extends HumanoidModel<T>> extends RenderLayer<T, M> {
    public OutfitLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int packedLight, T entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        M model = getParentModel();
        if (entity.isInvisible() || model.young) return;
        String id = ResidentVisuals.outfit(entity.getId());
        OutfitModels.Baked outfit = id == null ? null : OutfitModels.get(id);
        if (outfit == null) return;
        int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0.0F);
        try {
            if (outfit.skin() != null) OutfitVisibility.skin(model, outfit, poseStack, buffers, packedLight, overlay);
            for (OutfitModels.Layer layer : outfit.layers()) OutfitDraw.draw(poseStack, buffers.getBuffer(layer.renderType()), model, layer.quads(), packedLight, overlay, -1);
        } catch (RuntimeException failure) {
            OutfitModels.fail(id, failure);
        }
    }
}
