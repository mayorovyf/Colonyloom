package colonyloom.core.outfit;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OutfitGeometryTest {
    static final String teen_pro_YT_PIXEL = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";

    static final String teen_pro_YT_HUMANOID = """
            {"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.test","texture_width":64,"texture_height":64},
             "bones":[
              {"name":"body","pivot":[0,24,0],"cubes":[{"origin":[-4,12,-2],"size":[8,12,4],"uv":[16,16]}]},
              {"name":"head","parent":"body","pivot":[0,24,0],"cubes":[{"origin":[-4,24,-4],"size":[8,8,8],"uv":[0,0]}]},
              {"name":"rightArm","parent":"body","pivot":[-5,22,0],"cubes":[{"origin":[-8,12,-2],"size":[4,12,4],"uv":[40,16]}]},
              {"name":"leftArm","parent":"body","pivot":[5,22,0],"mirror":true,"cubes":[{"origin":[4,12,-2],"size":[4,12,4],"uv":[40,16]}]},
              {"name":"hat_feather","parent":"head","pivot":[0,32,0],"cubes":[{"origin":[-1,32,-1],"size":[2,3,2],"uv":[0,32]}]}
             ]}]}
            """;

    static OutfitQuad face(OutfitMesh mesh, OutfitPart part, float nx, float ny, float nz) {
        OutfitQuad found = null;
        for (OutfitQuad quad : mesh.quads())
            if (quad.part() == part && Math.abs(quad.normalX() - nx) < 1e-4 && Math.abs(quad.normalY() - ny) < 1e-4 && Math.abs(quad.normalZ() - nz) < 1e-4) {
                assertNull(found, "one face per direction");
                found = quad;
            }
        assertNotNull(found);
        return found;
    }

    static Map<String, String> corners(OutfitQuad quad, int size) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < 4; i++)
            result.put(Math.round(quad.x(i) * 10) / 10.0 + "," + Math.round(quad.y(i) * 10) / 10.0 + "," + Math.round(quad.z(i) * 10) / 10.0,
                    Math.round(quad.u(i) * size) + "," + Math.round(quad.v(i) * size));
        return result;
    }

    @Test void bedrockBonesLandInVanillaModelSpaceWithVanillaBoxUv() {
        OutfitMesh mesh = OutfitGeometry.read("test.geo.json", teen_pro_YT_HUMANOID);
        assertEquals(30, mesh.quads().size());
        assertEquals(Map.of(OutfitPart.BODY, 6, OutfitPart.HEAD, 12, OutfitPart.RIGHT_ARM, 6, OutfitPart.LEFT_ARM, 6), mesh.countByPart(),
                "an unnamed child bone follows its nearest humanoid ancestor");
        OutfitQuad face = null;
        for (OutfitQuad quad : mesh.quads()) if (quad.part() == OutfitPart.HEAD && quad.normalZ() < -0.5F && quad.z(0) < -3.9F) face = quad;
        assertNotNull(face);
        assertEquals(Map.of("-4.0,-8.0,-4.0", "8,8", "4.0,-8.0,-4.0", "16,8", "-4.0,0.0,-4.0", "8,16", "4.0,0.0,-4.0", "16,16"), corners(face, 64),
                "the head's front face reads the face of the skin exactly like vanilla");
        OutfitQuad top = face(mesh, OutfitPart.RIGHT_ARM, 0, -1, 0);
        assertEquals(Map.of("-4.0,0.0,2.0", "48,16", "-8.0,0.0,2.0", "44,16", "-8.0,0.0,-2.0", "44,20", "-4.0,0.0,-2.0", "48,20"), corners(top, 64),
                "the shoulder top matches vanilla's box UV corner for corner");
        OutfitQuad mirrored = face(mesh, OutfitPart.LEFT_ARM, 0, -1, 0);
        assertEquals(Map.of("4.0,0.0,2.0", "48,16", "8.0,0.0,2.0", "44,16", "8.0,0.0,-2.0", "44,20", "4.0,0.0,-2.0", "48,20"), corners(mirrored, 64),
                "a mirrored left arm reuses the right arm's texture flipped");
        for (OutfitQuad quad : mesh.quads()) if (quad.part() == OutfitPart.RIGHT_ARM)
            for (int i = 0; i < 4; i++) {
                assertTrue(quad.x(i) >= -8.0001F && quad.x(i) <= -3.9999F);
                assertTrue(quad.y(i) >= -0.0001F && quad.y(i) <= 12.0001F);
            }
    }

    @Test void legacyBedrockGeometryAndRotatedBonesAreSupported() {
        String legacy = """
                {"format_version":"1.8.0","geometry.hat":{"texturewidth":32,"textureheight":32,"bones":[
                 {"name":"head","pivot":[0,24,0],"rotation":[0,90,0],"cubes":[{"origin":[-1,24,-5],"size":[2,1,1],"uv":[0,0]}]}]}}
                """;
        OutfitMesh mesh = OutfitGeometry.read("hat.json", legacy);
        assertEquals(6, mesh.quads().size());
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (OutfitQuad quad : mesh.quads()) for (int i = 0; i < 4; i++) {
            minX = Math.min(minX, quad.x(i)); maxX = Math.max(maxX, quad.x(i));
            minZ = Math.min(minZ, quad.z(i)); maxZ = Math.max(maxZ, quad.z(i));
        }
        assertEquals(1, Math.round(maxX - minX), "a quarter turn swaps the cube's width and depth");
        assertEquals(2, Math.round(maxZ - minZ));
        assertTrue(Math.abs(minX) > 3.9F || Math.abs(maxX) > 3.9F, "the brim swung from the front to a side");
    }

    @Test void blockbenchProjectsMatchTheirBedrockExport() {
        String project = """
                {"meta":{"format_version":"4.10","model_format":"bedrock","box_uv":true},"resolution":{"width":64,"height":64},
                 "elements":[{"name":"skull","type":"cube","box_uv":true,"from":[-4,24,-4],"to":[4,32,4],"origin":[0,24,0],"uv_offset":[0,0],
                   "faces":{"north":{"uv":[8,8,16,16],"texture":0},"east":{"uv":[0,8,8,16],"texture":0},"south":{"uv":[24,8,32,16],"texture":0},
                            "west":{"uv":[16,8,24,16],"texture":0},"up":{"uv":[16,8,8,0],"texture":0},"down":{"uv":[24,0,16,8],"texture":null}},
                   "uuid":"c1"}],
                 "outliner":[{"name":"head","origin":[0,24,0],"rotation":[0,0,0],"uuid":"g1","children":["c1"]}],
                 "textures":[{"name":"skin.png","uuid":"t1","source":"data:image/png;base64,%s"}]}
                """.formatted(teen_pro_YT_PIXEL);
        OutfitMesh fromProject = OutfitGeometry.read("skull.bbmodel", project);
        assertEquals(5, fromProject.quads().size(), "a face without texture is not drawn");
        assertTrue(fromProject.textures().getFirst().embedded());
        assertArrayEquals(Base64.getDecoder().decode(teen_pro_YT_PIXEL), fromProject.textures().getFirst().png());
        OutfitMesh fromBedrock = OutfitGeometry.read("skull.geo.json", """
                {"format_version":"1.12.0","minecraft:geometry":[{"description":{"texture_width":64,"texture_height":64},
                 "bones":[{"name":"head","pivot":[0,24,0],"cubes":[{"origin":[-4,24,-4],"size":[8,8,8],"uv":[0,0]}]}]}]}
                """);
        OutfitQuad a = face(fromProject, OutfitPart.HEAD, 0, 0, -1), b = face(fromBedrock, OutfitPart.HEAD, 0, 0, -1);
        assertEquals(corners(b, 64), corners(a, 64));
        assertEquals(corners(face(fromBedrock, OutfitPart.HEAD, 0, -1, 0), 64), corners(face(fromProject, OutfitPart.HEAD, 0, -1, 0), 64));
    }

    @Test void blockbenchFiveGroupsAndPerFaceUvRotations() {
        String project = """
                {"meta":{"format_version":"5.0","model_format":"free","box_uv":false},"resolution":{"width":16,"height":16},
                 "elements":[{"type":"cube","from":[-1,0,-1],"to":[1,2,1],"origin":[0,0,0],"uuid":"e1",
                   "faces":{"north":{"uv":[0,0,4,4],"rotation":90}}}],
                 "groups":[{"uuid":"g1","name":"RightLeg","origin":[-2,12,0],"rotation":[0,0,0]}],
                 "outliner":[{"uuid":"g1","isOpen":true,"children":["e1"]}]}
                """;
        OutfitMesh mesh = OutfitGeometry.read("boot.bbmodel", project);
        assertEquals(1, mesh.quads().size());
        OutfitQuad quad = mesh.quads().getFirst();
        assertEquals(OutfitPart.RIGHT_LEG, quad.part());
        assertEquals(Map.of("-1.0,22.0,-1.0", "0,4", "1.0,22.0,-1.0", "0,0", "-1.0,24.0,-1.0", "4,4", "1.0,24.0,-1.0", "4,0"), corners(quad, 16),
                "a quarter turn of the face's UV rotates its corners the way Blockbench draws them");
    }

    @Test void inflatedCubesGrowButKeepTheirTexture() {
        String inflated = """
                {"format_version":"1.12.0","minecraft:geometry":[{"description":{"texture_width":64,"texture_height":64},
                 "bones":[{"name":"body","pivot":[0,24,0],"cubes":[{"origin":[-4,12,-2],"size":[8,12,4],"uv":[16,32],"inflate":0.5}]}]}]}
                """;
        OutfitQuad front = face(OutfitGeometry.read("jacket.geo.json", inflated), OutfitPart.BODY, 0, 0, -1);
        assertEquals(Map.of("-4.5,-0.5,-2.5", "20,36", "4.5,-0.5,-2.5", "28,36", "-4.5,12.5,-2.5", "20,48", "4.5,12.5,-2.5", "28,48"), corners(front, 64));
    }

    @Test void brokenModelsAreRejectedBeforeRendering() {
        assertThrows(IllegalArgumentException.class, () -> OutfitGeometry.read("x.geo.json", "{\"format_version\":\"1.12.0\"}"));
        assertThrows(IllegalArgumentException.class, () -> OutfitGeometry.read("x.obj", "{}"));
        assertThrows(IllegalArgumentException.class, () -> OutfitGeometry.read("x.bbmodel", "{\"textures\":[{\"source\":\"data:image/png;base64,AAAA\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> OutfitGeometry.read("x.geo.json", "{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"a\",\"cubes\":[{\"size\":[1,1,1],\"uv\":[0,0]}]}]}] trailing"));
        String deep = "[".repeat(200) + "]".repeat(200);
        assertThrows(IllegalArgumentException.class, () -> ModelJson.parse(deep));
    }

    @Test void catalogChoosesStableFittingOutfits() throws Exception {
        Map<String, String> files = Map.of(
                "index.txt", "builder.json\nchild.json\n# comment\n",
                "builder.json", "{\"schemaVersion\":1,\"model\":\"models/builder.geo.json\",\"texture\":\"textures/builder.png\",\"hide\":[\"hat\"],\"wearers\":{\"kinds\":[\"citizen\"],\"professions\":[\"colonyloom:builder\"]}}",
                "child.json", "{\"schemaVersion\":1,\"model\":\"models/child.bbmodel\",\"skin\":\"textures/kid.png\",\"hide\":[\"all\"],\"weight\":3,\"wearers\":{\"kinds\":[\"child\"],\"stages\":[\"baby\",\"child\"]}}");
        OutfitCatalog catalog = OutfitCatalog.load(path -> files.containsKey(path) ? new java.io.ByteArrayInputStream(files.get(path).getBytes(java.nio.charset.StandardCharsets.UTF_8)) : null);
        assertEquals(List.of("colonyloom:builder", "colonyloom:child"), List.copyOf(catalog.outfits().keySet()));
        assertEquals(OutfitDefinition.teen_pro_YT_LAYERS, catalog.outfit("colonyloom:child").hide());
        UUID resident = new UUID(5, 7);
        assertEquals("colonyloom:builder", catalog.choose(new OutfitProfile("citizen", "male", "adult", "colonyloom:builder"), resident));
        assertNull(catalog.choose(new OutfitProfile("citizen", "male", "adult", null), resident), "nobody else fits the builder's clothes");
        assertEquals("colonyloom:child", catalog.choose(new OutfitProfile("child", "female", "child", null), resident));
        assertNull(catalog.choose(new OutfitProfile("child", "female", "teen", null), resident));
        assertEquals(catalog.choose(new OutfitProfile("child", "male", "baby", null), resident), catalog.choose(new OutfitProfile("child", "male", "baby", null), resident));
        assertThrows(IllegalArgumentException.class, () -> new OutfitDefinition("colonyloom:bad", "../escape.geo.json", null, null, java.util.Set.of(), false, 1,
                java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new OutfitDefinition("colonyloom:bad", "a.geo.json", null, null, java.util.Set.of("tail"), false, 1,
                java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of()));
        assertEquals(OutfitCatalog.teen_pro_YT_EMPTY.outfits(), OutfitCatalog.load(path -> null).outfits());
    }
}
