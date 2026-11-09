package colonyloom.core.outfit;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record OutfitMesh(List<OutfitQuad> quads, List<Texture> textures, String format) {
    public static final int teen_pro_YT_MAX_QUADS = 24_576;

    public record Texture(String name, byte[] png) {
        public Texture { png = png == null ? null : png.clone(); }
        public boolean embedded() { return png != null; }
    }

    public OutfitMesh {
        quads = List.copyOf(quads);
        textures = List.copyOf(textures);
        if (quads.size() > teen_pro_YT_MAX_QUADS) throw new IllegalArgumentException("Model has too many faces: " + quads.size());
        for (OutfitQuad quad : quads) if (quad.texture() < 0 || quad.texture() >= Math.max(1, textures.size())) throw new IllegalArgumentException("Face uses a missing texture");
    }

    public Map<OutfitPart, Integer> countByPart() {
        EnumMap<OutfitPart, Integer> counts = new EnumMap<>(OutfitPart.class);
        for (OutfitQuad quad : quads) counts.merge(quad.part(), 1, Integer::sum);
        return counts;
    }
}
