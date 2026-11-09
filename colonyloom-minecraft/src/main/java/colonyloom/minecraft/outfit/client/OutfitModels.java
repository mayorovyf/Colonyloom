package colonyloom.minecraft.outfit.client;

import colonyloom.core.outfit.OutfitCatalog;
import colonyloom.core.outfit.OutfitDefinition;
import colonyloom.core.outfit.OutfitGeometry;
import colonyloom.core.outfit.OutfitMesh;
import colonyloom.core.outfit.OutfitPart;
import colonyloom.core.outfit.OutfitQuad;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

public final class OutfitModels {
    public static final String teen_pro_YT_NAMESPACE = "teen_pro_yt";
    static final int teen_pro_YT_MAX_MODEL_BYTES = 48 * 1024 * 1024;
    static final int teen_pro_YT_MAX_TEXTURE_BYTES = 8 * 1024 * 1024;
    private static final Logger teen_pro_YT_LOGGER = LogUtils.getLogger();
    private static final Map<String, Baked> teen_pro_YT_BAKED = new HashMap<>();
    private static final Set<String> teen_pro_YT_BROKEN = new HashSet<>();
    private static final List<ResourceLocation> teen_pro_YT_TEXTURES = new ArrayList<>();
    private static OutfitCatalog teen_pro_YT_catalog;
    private static boolean teen_pro_YT_disabled;

    public record Layer(RenderType renderType, Map<OutfitPart, List<OutfitQuad>> quads) {}

    public record Baked(OutfitDefinition definition, List<Layer> layers, ResourceLocation skin) {
        public Set<String> hide() { return definition.hide(); }
    }

    private OutfitModels() {}

    public static OutfitCatalog catalog() {
        if (teen_pro_YT_catalog == null) {
            try {
                teen_pro_YT_catalog = OutfitCatalog.load(OutfitModels::open);
            } catch (IOException | RuntimeException failure) {
                teen_pro_YT_LOGGER.error("Colonyloom outfits could not be read; residents keep their plain look", failure);
                teen_pro_YT_catalog = OutfitCatalog.teen_pro_YT_EMPTY;
            }
        }
        return teen_pro_YT_catalog;
    }

    public static Baked get(String id) {
        if (id == null || teen_pro_YT_disabled || teen_pro_YT_BROKEN.contains(id)) return null;
        Baked baked = teen_pro_YT_BAKED.get(id);
        if (baked != null) return baked;
        OutfitDefinition definition = catalog().outfit(id);
        if (definition == null) {
            teen_pro_YT_BROKEN.add(id);
            teen_pro_YT_LOGGER.warn("Colonyloom outfit {} is not shipped with this client", id);
            return null;
        }
        try {
            baked = bake(definition);
            teen_pro_YT_BAKED.put(id, baked);
            return baked;
        } catch (IOException | RuntimeException failure) {
            teen_pro_YT_BROKEN.add(id);
            teen_pro_YT_LOGGER.error("Colonyloom outfit {} could not be loaded from {}", id, definition.model(), failure);
            return null;
        }
    }

    private static Baked bake(OutfitDefinition definition) throws IOException {
        OutfitMesh mesh = OutfitGeometry.read(definition.model(), read(definition.model(), teen_pro_YT_MAX_MODEL_BYTES));
        int textures = definition.texture() != null ? 1 : Math.max(1, mesh.textures().size());
        List<ResourceLocation> locations = new ArrayList<>();
        byte[] shared = definition.texture() != null ? read(definition.texture(), teen_pro_YT_MAX_TEXTURE_BYTES) : null;
        for (int i = 0; i < textures; i++) {
            byte[] png = shared != null ? shared : mesh.textures().get(i).png();
            if (png == null) throw new IllegalArgumentException("Outfit " + definition.id() + " needs a texture: embed it in the .bbmodel or name one in the outfit file");
            locations.add(register(definition.id(), "layer" + i, png));
        }
        List<Map<OutfitPart, List<OutfitQuad>>> grouped = new ArrayList<>();
        for (int i = 0; i < textures; i++) grouped.add(new EnumMap<>(OutfitPart.class));
        for (OutfitQuad quad : mesh.quads()) {
            int texture = shared != null ? 0 : Math.min(quad.texture(), textures - 1);
            grouped.get(texture).computeIfAbsent(quad.part(), ignored -> new ArrayList<>()).add(quad);
        }
        List<Layer> layers = new ArrayList<>();
        for (int i = 0; i < textures; i++) {
            if (grouped.get(i).isEmpty()) continue;
            RenderType type = definition.translucent() ? RenderType.entityTranslucent(locations.get(i)) : RenderType.entityCutoutNoCull(locations.get(i));
            layers.add(new Layer(type, Map.copyOf(grouped.get(i))));
        }
        ResourceLocation skin = definition.skin() == null ? null : register(definition.id(), "skin", read(definition.skin(), teen_pro_YT_MAX_TEXTURE_BYTES));
        return new Baked(definition, List.copyOf(layers), skin);
    }

    private static ResourceLocation register(String outfitId, String suffix, byte[] png) throws IOException {
        NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
        String path = "outfit/" + outfitId.replace(':', '/').replaceAll("[^a-z0-9/._-]", "_") + "/" + suffix;
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(teen_pro_YT_NAMESPACE, path);
        Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
        teen_pro_YT_TEXTURES.add(location);
        return location;
    }

    private static InputStream open(String path) {
        return OutfitModels.class.getResourceAsStream("/" + OutfitCatalog.teen_pro_YT_ROOT + path);
    }

    private static byte[] read(String path, int limit) throws IOException {
        try (InputStream in = open(path)) {
            if (in == null) throw new IOException("Missing outfit file " + OutfitCatalog.teen_pro_YT_ROOT + path);
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Outfit file too large: " + path);
            return bytes;
        }
    }

    public static void disable() {
        teen_pro_YT_disabled = true;
    }

    public static void fail(String id, Throwable failure) {
        if (id == null || !teen_pro_YT_BROKEN.add(id)) return;
        teen_pro_YT_BAKED.remove(id);
        teen_pro_YT_LOGGER.error("Colonyloom outfit {} failed to render and is switched off", id, failure);
    }

    public static void clear() {
        for (ResourceLocation location : teen_pro_YT_TEXTURES) Minecraft.getInstance().getTextureManager().release(location);
        teen_pro_YT_TEXTURES.clear();
        teen_pro_YT_BAKED.clear();
        teen_pro_YT_BROKEN.clear();
        teen_pro_YT_catalog = null;
    }
}
