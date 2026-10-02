package io.github.kpuctajluk.colonyloom.minecraft.client.entity;

import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

/** Physical-client only; reuses vanilla humanoid geometry and texture without server state. */
public final class CitizenRenderer extends HumanoidMobRenderer<CitizenEntity, HumanoidModel<CitizenEntity>> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/zombie/zombie.png");

    public CitizenRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(CitizenEntity entity) {
        return TEXTURE;
    }
}
