package colonyloom.minecraft.outfit.client;

import colonyloom.core.outfit.OutfitPart;
import colonyloom.core.outfit.OutfitQuad;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

final class OutfitDraw {
    private static final Vector3f teen_pro_YT_POSITION = new Vector3f();
    private static final Vector3f teen_pro_YT_NORMAL = new Vector3f();

    private OutfitDraw() {}

    static void draw(PoseStack poseStack, VertexConsumer consumer, HumanoidModel<?> model, Map<OutfitPart, List<OutfitQuad>> quads, int light, int overlay, int color) {
        for (Map.Entry<OutfitPart, List<OutfitQuad>> entry : quads.entrySet()) {
            OutfitPart which = entry.getKey();
            poseStack.pushPose();
            part(model, which).translateAndRotate(poseStack);
            poseStack.translate(-which.pivotX() / 16.0F, -which.pivotY() / 16.0F, -which.pivotZ() / 16.0F);
            PoseStack.Pose pose = poseStack.last();
            Matrix4f matrix = pose.pose();
            Matrix3f normal = pose.normal();
            for (OutfitQuad quad : entry.getValue()) {
                normal.transform(quad.normalX(), quad.normalY(), quad.normalZ(), teen_pro_YT_NORMAL).normalize();
                for (int i = 0; i < 4; i++) {
                    matrix.transformPosition(quad.x(i) / 16.0F, quad.y(i) / 16.0F, quad.z(i) / 16.0F, teen_pro_YT_POSITION);
                    consumer.addVertex(teen_pro_YT_POSITION.x(), teen_pro_YT_POSITION.y(), teen_pro_YT_POSITION.z(), color, quad.u(i), quad.v(i), overlay, light,
                            teen_pro_YT_NORMAL.x(), teen_pro_YT_NORMAL.y(), teen_pro_YT_NORMAL.z());
                }
            }
            poseStack.popPose();
        }
    }

    static ModelPart part(HumanoidModel<?> model, OutfitPart part) {
        return switch (part) {
            case HEAD -> model.head;
            case BODY -> model.body;
            case RIGHT_ARM -> model.rightArm;
            case LEFT_ARM -> model.leftArm;
            case RIGHT_LEG -> model.rightLeg;
            case LEFT_LEG -> model.leftLeg;
        };
    }
}
