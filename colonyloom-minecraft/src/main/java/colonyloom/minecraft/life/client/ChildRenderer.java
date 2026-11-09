package colonyloom.minecraft.life.client;

import colonyloom.minecraft.life.entity.ResidentChildEntity;
import colonyloom.minecraft.outfit.client.OutfitLayer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

public final class ChildRenderer extends HumanoidMobRenderer<ResidentChildEntity, HumanoidModel<ResidentChildEntity>> {
    private static final ResourceLocation teen_pro_YT_TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/zombie/zombie.png");

    public ChildRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.35F);
        addLayer(new OutfitLayer<>(this));
    }

    @Override
    public ResourceLocation getTextureLocation(ResidentChildEntity entity) {
        return teen_pro_YT_TEXTURE;
    }
}
