package colonyloom.core.outfit;

import java.util.Objects;

public record OutfitQuad(OutfitPart part, int texture, float[] vertices, float normalX, float normalY, float normalZ) {
    public static final int teen_pro_YT_STRIDE = 5;

    public OutfitQuad {
        Objects.requireNonNull(part);
        if (vertices.length != 4 * teen_pro_YT_STRIDE) throw new IllegalArgumentException("A quad has four vertices");
        for (float value : vertices) if (!Float.isFinite(value)) throw new IllegalArgumentException("Quad vertex is not finite");
        vertices = vertices.clone();
    }

    public float x(int vertex) { return vertices[vertex * teen_pro_YT_STRIDE]; }
    public float y(int vertex) { return vertices[vertex * teen_pro_YT_STRIDE + 1]; }
    public float z(int vertex) { return vertices[vertex * teen_pro_YT_STRIDE + 2]; }
    public float u(int vertex) { return vertices[vertex * teen_pro_YT_STRIDE + 3]; }
    public float v(int vertex) { return vertices[vertex * teen_pro_YT_STRIDE + 4]; }
}
