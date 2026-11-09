package colonyloom.minecraft.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import colonyloom.core.outfit.OutfitCatalog;
import colonyloom.core.outfit.OutfitDefinition;
import colonyloom.core.outfit.OutfitGeometry;
import colonyloom.core.outfit.OutfitMesh;
import colonyloom.core.outfit.OutfitPart;
import colonyloom.core.outfit.OutfitProfile;
import colonyloom.core.outfit.OutfitQuad;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class OutfitResourcesTest {
    private static final Path teen_pro_YT_ROOT = Path.of(System.getProperty("colonyloom.resources", "src/main/resources")).resolve(OutfitCatalog.teen_pro_YT_ROOT);

    private static OutfitCatalog catalog() throws IOException {
        return OutfitCatalog.load(path -> {
            Path file = teen_pro_YT_ROOT.resolve(path);
            return Files.isRegularFile(file) ? Files.newInputStream(file) : null;
        });
    }

    private static int[] png(byte[] bytes) {
        assertTrue(bytes.length > 24 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G', "not a PNG");
        ByteBuffer header = ByteBuffer.wrap(bytes, 16, 8);
        return new int[]{header.getInt(), header.getInt()};
    }

    @Test
    void everyShippedOutfitLoadsWithItsModelAndTextures() throws IOException {
        OutfitCatalog catalog = catalog();
        assertTrue(catalog.outfits().size() >= 4, "starter outfits are shipped inside the mod");
        for (OutfitDefinition outfit : catalog.outfits().values()) {
            OutfitMesh mesh = OutfitGeometry.read(outfit.model(), Files.readAllBytes(teen_pro_YT_ROOT.resolve(outfit.model())));
            assertFalse(mesh.quads().isEmpty(), outfit.id() + " has geometry");
            if (outfit.texture() == null) {
                for (OutfitMesh.Texture texture : mesh.textures()) assertTrue(texture.embedded(), outfit.id() + " embeds its texture");
            } else {
                int[] size = png(Files.readAllBytes(teen_pro_YT_ROOT.resolve(outfit.texture())));
                assertEquals(64, size[0], outfit.id() + " texture width");
            }
            if (outfit.skin() != null) {
                int[] size = png(Files.readAllBytes(teen_pro_YT_ROOT.resolve(outfit.skin())));
                assertEquals(64, size[0], outfit.id() + " skin width");
                assertEquals(64, size[1], outfit.id() + " skin height");
            }
            for (OutfitQuad quad : mesh.quads()) for (int i = 0; i < 4; i++) {
                assertTrue(quad.u(i) >= -0.001F && quad.u(i) <= 1.001F && quad.v(i) >= -0.001F && quad.v(i) <= 1.001F, outfit.id() + " UVs stay on the texture");
                assertTrue(Math.abs(quad.x(i)) < 24 && quad.y(i) > -16 && quad.y(i) < 28 && Math.abs(quad.z(i)) < 24, outfit.id() + " stays on the body");
            }
        }
    }

    @Test
    void outfitsFollowTheBonesOfTheBody() throws IOException {
        OutfitCatalog catalog = catalog();
        OutfitDefinition tunic = catalog.outfit("colonyloom:settler_tunic");
        assertNotNull(tunic);
        Map<OutfitPart, Integer> parts = OutfitGeometry.read(tunic.model(), Files.readAllBytes(teen_pro_YT_ROOT.resolve(tunic.model()))).countByPart();
        for (OutfitPart part : new OutfitPart[]{OutfitPart.BODY, OutfitPart.RIGHT_ARM, OutfitPart.LEFT_ARM, OutfitPart.RIGHT_LEG, OutfitPart.LEFT_LEG})
            assertTrue(parts.getOrDefault(part, 0) > 0, "tunic dresses " + part);
        OutfitDefinition farmer = catalog.outfit("colonyloom:farmer_straw");
        assertNotNull(farmer);
        Map<OutfitPart, Integer> hat = OutfitGeometry.read(farmer.model(), Files.readAllBytes(teen_pro_YT_ROOT.resolve(farmer.model()))).countByPart();
        assertTrue(hat.getOrDefault(OutfitPart.HEAD, 0) > 0, "the straw hat sits on the head");
        assertTrue(farmer.hide().contains("hat"));
    }

    @Test
    void everyKindOfResidentFindsSomethingToWear() throws IOException {
        OutfitCatalog catalog = catalog();
        UUID seed = UUID.fromString("00000000-0000-0000-0000-000000000042");
        for (OutfitProfile profile : new OutfitProfile[]{
                new OutfitProfile("citizen", "male", "adult", null), new OutfitProfile("citizen", "female", "adult", "colonyloom:farmer"),
                new OutfitProfile("citizen", "female", "elder", null), new OutfitProfile("child", "male", "child", null),
                new OutfitProfile("child", "female", "baby", null), new OutfitProfile("child", "male", "teen", null)}) {
            String chosen = catalog.choose(profile, seed);
            assertNotNull(chosen, "an outfit fits " + profile);
            assertTrue(catalog.outfit(chosen).fits(profile));
        }
        assertEquals(catalog.choose(new OutfitProfile("citizen", "male", "adult", null), seed), catalog.choose(new OutfitProfile("citizen", "male", "adult", null), seed));
    }

    @Test
    void outfitFilesAreAllListedAndNothingIsLeftOver() throws IOException {
        OutfitCatalog catalog = catalog();
        try (Stream<Path> files = Files.walk(teen_pro_YT_ROOT)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String path = teen_pro_YT_ROOT.relativize(file).toString().replace('\\', '/');
                if (path.equals(OutfitCatalog.teen_pro_YT_INDEX)) continue;
                boolean used = catalog.outfits().values().stream().anyMatch(outfit -> path.equals(outfit.model()) || path.equals(outfit.texture()) || path.equals(outfit.skin())
                        || outfit.id().equals("colonyloom:" + path.replaceAll("\\.json$", "")));
                assertTrue(used, path + " is referenced by an outfit");
            }
        }
    }
}
