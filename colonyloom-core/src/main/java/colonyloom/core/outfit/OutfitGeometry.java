package colonyloom.core.outfit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class OutfitGeometry {
    public static final int teen_pro_YT_MAX_BONES = 512;
    public static final int teen_pro_YT_MAX_CUBES = 4096;
    public static final int teen_pro_YT_MAX_TEXTURE_BYTES = 8 * 1024 * 1024;
    public static final int teen_pro_YT_MAX_TEXTURES = 16;
    static final String[] teen_pro_YT_FACES = {"east", "west", "up", "down", "south", "north"};
    static final double[][] teen_pro_YT_NORMALS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    static final int[] teen_pro_YT_LOOP = {0, 2, 3, 1};
    private static final byte[] teen_pro_YT_PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private OutfitGeometry() {}

    static final class Group {
        String teen_pro_YT_name = "root";
        String teen_pro_YT_parentName;
        double[] teen_pro_YT_origin = new double[3];
        double[] teen_pro_YT_rotation = new double[3];
        Group teen_pro_YT_parent;
        final List<Cube> teen_pro_YT_cubes = new ArrayList<>();
    }

    static final class Cube {
        double[] teen_pro_YT_from = new double[3], teen_pro_YT_to = new double[3];
        double[] teen_pro_YT_origin = new double[3], teen_pro_YT_rotation = new double[3];
        double teen_pro_YT_inflate;
        boolean teen_pro_YT_boxUv, teen_pro_YT_mirror;
        double[] teen_pro_YT_uvOffset = new double[2];
        final double[][] teen_pro_YT_uv = new double[6][];
        final boolean[] teen_pro_YT_hidden = new boolean[6];
        final int[] teen_pro_YT_uvRotation = new int[6];
        final int[] teen_pro_YT_texture = new int[6];
    }

    public static OutfitMesh read(String fileName, String text) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        Object root = ModelJson.parse(text);
        if (lower.endsWith(".bbmodel")) return blockbench(ModelJson.object(root, fileName));
        if (lower.endsWith(".json")) return bedrock(ModelJson.object(root, fileName));
        throw new IllegalArgumentException("Unsupported model file " + fileName + ": use .geo.json or .bbmodel");
    }

    public static OutfitMesh read(String fileName, byte[] bytes) {
        return read(fileName, new String(bytes, StandardCharsets.UTF_8));
    }

    static OutfitMesh bedrock(Map<String, Object> json) {
        Map<String, Object> geometry = null;
        double width = 64, height = 64;
        if (json.containsKey("minecraft:geometry")) {
            List<Object> list = ModelJson.array(json.get("minecraft:geometry"), "minecraft:geometry");
            if (list.isEmpty()) throw new IllegalArgumentException("The model has no geometry");
            geometry = ModelJson.object(list.getFirst(), "geometry");
            Map<String, Object> description = geometry.containsKey("description") ? ModelJson.object(geometry.get("description"), "description") : Map.of();
            width = ModelJson.number(description.get("texture_width"), 64);
            height = ModelJson.number(description.get("texture_height"), 64);
        } else {
            for (Map.Entry<String, Object> entry : json.entrySet()) {
                if (!entry.getKey().startsWith("geometry.")) continue;
                geometry = ModelJson.object(entry.getValue(), entry.getKey());
                width = ModelJson.number(geometry.get("texturewidth"), 64);
                height = ModelJson.number(geometry.get("textureheight"), 64);
                break;
            }
        }
        if (geometry == null) throw new IllegalArgumentException("No Bedrock geometry found");
        List<Object> bones = geometry.containsKey("bones") ? ModelJson.array(geometry.get("bones"), "bones") : List.of();
        if (bones.size() > teen_pro_YT_MAX_BONES) throw new IllegalArgumentException("Too many bones: " + bones.size());
        List<Group> groups = new ArrayList<>();
        Map<String, Group> named = new HashMap<>();
        int cubeCount = 0;
        for (Object rawBone : bones) {
            Map<String, Object> bone = ModelJson.object(rawBone, "bone");
            Group group = new Group();
            group.teen_pro_YT_name = text(bone.get("name"), "bone" + groups.size());
            group.teen_pro_YT_parentName = text(bone.get("parent"), null);
            double[] pivot = ModelJson.vector(bone.get("pivot"), 3, 0);
            group.teen_pro_YT_origin = new double[]{-pivot[0], pivot[1], pivot[2]};
            double[] rotation = ModelJson.vector(bone.get("rotation"), 3, 0);
            group.teen_pro_YT_rotation = new double[]{-rotation[0], -rotation[1], rotation[2]};
            boolean boneMirror = Boolean.TRUE.equals(bone.get("mirror"));
            double boneInflate = ModelJson.number(bone.get("inflate"), 0);
            List<Object> cubes = bone.containsKey("cubes") ? ModelJson.array(bone.get("cubes"), "cubes") : List.of();
            cubeCount += cubes.size();
            if (cubeCount > teen_pro_YT_MAX_CUBES) throw new IllegalArgumentException("Too many cubes");
            for (Object rawCube : cubes) group.teen_pro_YT_cubes.add(bedrockCube(ModelJson.object(rawCube, "cube"), boneMirror, boneInflate));
            groups.add(group);
            named.putIfAbsent(group.teen_pro_YT_name.toLowerCase(Locale.ROOT), group);
        }
        for (Group group : groups) {
            if (group.teen_pro_YT_parentName == null) continue;
            Group parent = named.get(group.teen_pro_YT_parentName.toLowerCase(Locale.ROOT));
            if (parent != null && parent != group && !descends(parent, group)) group.teen_pro_YT_parent = parent;
        }
        return bake(groups, new double[]{width}, new double[]{height}, false, List.of(new OutfitMesh.Texture("texture", null)), "bedrock");
    }

    private static Cube bedrockCube(Map<String, Object> json, boolean boneMirror, double boneInflate) {
        Cube cube = new Cube();
        double[] origin = ModelJson.vector(json.get("origin"), 3, 0), size = ModelJson.vector(json.get("size"), 3, 0);
        cube.teen_pro_YT_from = new double[]{-(origin[0] + size[0]), origin[1], origin[2]};
        cube.teen_pro_YT_to = new double[]{cube.teen_pro_YT_from[0] + size[0], cube.teen_pro_YT_from[1] + size[1], cube.teen_pro_YT_from[2] + size[2]};
        double[] rotation = ModelJson.vector(json.get("rotation"), 3, 0);
        cube.teen_pro_YT_rotation = new double[]{-rotation[0], -rotation[1], rotation[2]};
        double[] pivot = json.containsKey("pivot") ? ModelJson.vector(json.get("pivot"), 3, 0)
                : new double[]{origin[0] + size[0] / 2, origin[1] + size[1] / 2, origin[2] + size[2] / 2};
        cube.teen_pro_YT_origin = new double[]{-pivot[0], pivot[1], pivot[2]};
        cube.teen_pro_YT_inflate = json.containsKey("inflate") ? ModelJson.number(json.get("inflate"), 0) : boneInflate;
        cube.teen_pro_YT_mirror = json.containsKey("mirror") ? Boolean.TRUE.equals(json.get("mirror")) : boneMirror;
        Object uv = json.get("uv");
        if (uv instanceof Map<?, ?>) {
            Map<String, Object> faces = ModelJson.object(uv, "uv");
            for (int face = 0; face < 6; face++) {
                Object rawFace = faces.get(teen_pro_YT_FACES[face]);
                if (!(rawFace instanceof Map<?, ?>)) { cube.teen_pro_YT_hidden[face] = true; continue; }
                Map<String, Object> entry = ModelJson.object(rawFace, teen_pro_YT_FACES[face]);
                double[] start = ModelJson.vector(entry.get("uv"), 2, 0);
                double[] extent = entry.containsKey("uv_size") ? ModelJson.vector(entry.get("uv_size"), 2, 0) : autoSize(face, size);
                double[] rect = {start[0], start[1], start[0] + extent[0], start[1] + extent[1]};
                if (face == 2 || face == 3) rect = new double[]{rect[2], rect[3], rect[0], rect[1]};
                cube.teen_pro_YT_uv[face] = rect;
                cube.teen_pro_YT_uvRotation[face] = (int) ModelJson.number(entry.get("uv_rotation"), 0);
            }
        } else {
            cube.teen_pro_YT_boxUv = true;
            cube.teen_pro_YT_uvOffset = ModelJson.vector(uv, 2, 0);
        }
        return cube;
    }

    private static double[] autoSize(int face, double[] size) {
        return switch (face) {
            case 0, 1 -> new double[]{size[2], size[1]};
            case 2, 3 -> new double[]{size[0], size[2]};
            default -> new double[]{size[0], size[1]};
        };
    }

    static OutfitMesh blockbench(Map<String, Object> json) {
        Map<String, Object> meta = json.containsKey("meta") ? ModelJson.object(json.get("meta"), "meta") : Map.of();
        String format = text(meta.get("model_format"), "free");
        boolean projectBoxUv = Boolean.TRUE.equals(meta.get("box_uv"));
        Map<String, Object> resolution = json.containsKey("resolution") ? ModelJson.object(json.get("resolution"), "resolution") : Map.of();
        double width = ModelJson.number(resolution.get("width"), 16), height = ModelJson.number(resolution.get("height"), 16);
        List<Object> rawTextures = json.containsKey("textures") ? ModelJson.array(json.get("textures"), "textures") : List.of();
        if (rawTextures.size() > teen_pro_YT_MAX_TEXTURES) throw new IllegalArgumentException("Too many textures");
        List<OutfitMesh.Texture> textures = new ArrayList<>();
        Map<String, Integer> textureIds = new HashMap<>();
        double[] widths = new double[Math.max(1, rawTextures.size())], heights = new double[Math.max(1, rawTextures.size())];
        java.util.Arrays.fill(widths, width);
        java.util.Arrays.fill(heights, height);
        for (int i = 0; i < rawTextures.size(); i++) {
            Map<String, Object> texture = ModelJson.object(rawTextures.get(i), "texture");
            textures.add(new OutfitMesh.Texture(text(texture.get("name"), "texture" + i), png(text(texture.get("source"), null))));
            String uuid = text(texture.get("uuid"), null);
            if (uuid != null) textureIds.put(uuid, i);
            if (texture.containsKey("uv_width")) widths[i] = ModelJson.number(texture.get("uv_width"), width);
            if (texture.containsKey("uv_height")) heights[i] = ModelJson.number(texture.get("uv_height"), height);
        }
        if (textures.isEmpty()) textures.add(new OutfitMesh.Texture("texture", null));
        Map<String, Cube> cubes = new LinkedHashMap<>();
        List<Object> elements = json.containsKey("elements") ? ModelJson.array(json.get("elements"), "elements") : List.of();
        if (elements.size() > teen_pro_YT_MAX_CUBES) throw new IllegalArgumentException("Too many elements");
        for (Object rawElement : elements) {
            Map<String, Object> element = ModelJson.object(rawElement, "element");
            if (!"cube".equals(text(element.get("type"), "cube")) || Boolean.FALSE.equals(element.get("export"))) continue;
            cubes.put(text(element.get("uuid"), "cube" + cubes.size()), blockbenchCube(element, projectBoxUv, textureIds, textures.size()));
        }
        Map<String, Map<String, Object>> groupDefinitions = new HashMap<>();
        if (json.containsKey("groups")) for (Object rawGroup : ModelJson.array(json.get("groups"), "groups")) {
            Map<String, Object> group = ModelJson.object(rawGroup, "group");
            String uuid = text(group.get("uuid"), null);
            if (uuid != null) groupDefinitions.put(uuid, group);
        }
        List<Group> groups = new ArrayList<>();
        Group root = new Group();
        groups.add(root);
        java.util.Set<String> placed = new java.util.HashSet<>();
        if (json.containsKey("outliner")) outline(ModelJson.array(json.get("outliner"), "outliner"), root, cubes, groupDefinitions, groups, placed, 0);
        for (Map.Entry<String, Cube> entry : cubes.entrySet()) if (!placed.contains(entry.getKey())) root.teen_pro_YT_cubes.add(entry.getValue());
        return bake(groups, widths, heights, "modded_entity".equals(format), textures, format);
    }

    private static void outline(List<Object> nodes, Group parent, Map<String, Cube> cubes, Map<String, Map<String, Object>> definitions,
                                List<Group> groups, java.util.Set<String> placed, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Outliner nesting too deep");
        for (Object node : nodes) {
            if (node instanceof String uuid) {
                Cube cube = cubes.get(uuid);
                if (cube != null && placed.add(uuid)) parent.teen_pro_YT_cubes.add(cube);
                continue;
            }
            if (!(node instanceof Map<?, ?>)) continue;
            Map<String, Object> entry = ModelJson.object(node, "outliner");
            Map<String, Object> definition = entry.containsKey("name") ? entry : definitions.getOrDefault(text(entry.get("uuid"), ""), entry);
            if (Boolean.FALSE.equals(definition.get("export"))) continue;
            if (groups.size() >= teen_pro_YT_MAX_BONES) throw new IllegalArgumentException("Too many groups");
            Group group = new Group();
            group.teen_pro_YT_name = text(definition.get("name"), "group" + groups.size());
            group.teen_pro_YT_origin = ModelJson.vector(definition.get("origin"), 3, 0);
            group.teen_pro_YT_rotation = ModelJson.vector(definition.get("rotation"), 3, 0);
            group.teen_pro_YT_parent = parent;
            groups.add(group);
            if (entry.containsKey("children")) outline(ModelJson.array(entry.get("children"), "children"), group, cubes, definitions, groups, placed, depth + 1);
        }
    }

    private static Cube blockbenchCube(Map<String, Object> element, boolean projectBoxUv, Map<String, Integer> textureIds, int textureCount) {
        Cube cube = new Cube();
        cube.teen_pro_YT_from = ModelJson.vector(element.get("from"), 3, 0);
        cube.teen_pro_YT_to = ModelJson.vector(element.get("to"), 3, 0);
        cube.teen_pro_YT_origin = ModelJson.vector(element.get("origin"), 3, 0);
        cube.teen_pro_YT_rotation = ModelJson.vector(element.get("rotation"), 3, 0);
        cube.teen_pro_YT_inflate = ModelJson.number(element.get("inflate"), 0);
        cube.teen_pro_YT_boxUv = element.containsKey("box_uv") ? Boolean.TRUE.equals(element.get("box_uv")) : projectBoxUv;
        cube.teen_pro_YT_mirror = Boolean.TRUE.equals(element.get("mirror_uv"));
        cube.teen_pro_YT_uvOffset = ModelJson.vector(element.get("uv_offset"), 2, 0);
        Map<String, Object> faces = element.containsKey("faces") ? ModelJson.object(element.get("faces"), "faces") : Map.of();
        for (int face = 0; face < 6; face++) {
            Object rawFace = faces.get(teen_pro_YT_FACES[face]);
            if (!(rawFace instanceof Map<?, ?>)) { cube.teen_pro_YT_hidden[face] = !cube.teen_pro_YT_boxUv; continue; }
            Map<String, Object> entry = ModelJson.object(rawFace, teen_pro_YT_FACES[face]);
            Object texture = entry.get("texture");
            if (texture == ModelJson.teen_pro_YT_NULL || Boolean.FALSE.equals(entry.get("enabled")) || Boolean.FALSE.equals(texture)) { cube.teen_pro_YT_hidden[face] = true; continue; }
            int index = 0;
            if (texture instanceof Number number) index = number.intValue();
            else if (texture instanceof String uuid) index = textureIds.getOrDefault(uuid, 0);
            if (index < 0 || index >= textureCount) index = 0;
            cube.teen_pro_YT_texture[face] = index;
            cube.teen_pro_YT_uv[face] = ModelJson.vector(entry.get("uv"), 4, 0);
            cube.teen_pro_YT_uvRotation[face] = (int) ModelJson.number(entry.get("rotation"), 0);
        }
        return cube;
    }

    static OutfitMesh bake(List<Group> groups, double[] widths, double[] heights, boolean floatBoxUv, List<OutfitMesh.Texture> textures, String format) {
        List<OutfitQuad> quads = new ArrayList<>();
        for (Group group : groups) {
            OutfitPart part = part(group);
            for (Cube cube : group.teen_pro_YT_cubes) bakeCube(cube, group, part, widths, heights, floatBoxUv, quads);
            if (quads.size() > OutfitMesh.teen_pro_YT_MAX_QUADS) throw new IllegalArgumentException("Model has too many faces");
        }
        return new OutfitMesh(quads, textures, format);
    }

    static OutfitPart part(Group group) {
        for (Group cursor = group; cursor != null; cursor = cursor.teen_pro_YT_parent) {
            OutfitPart part = OutfitPart.ofBone(cursor.teen_pro_YT_name);
            if (part != null) return part;
        }
        return OutfitPart.BODY;
    }

    private static void bakeCube(Cube cube, Group group, OutfitPart part, double[] widths, double[] heights, boolean floatBoxUv, List<OutfitQuad> quads) {
        double[] from = new double[3], to = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double half = (cube.teen_pro_YT_to[axis] - cube.teen_pro_YT_from[axis]) / 2, center = cube.teen_pro_YT_from[axis] + half;
            from[axis] = center - (half + cube.teen_pro_YT_inflate);
            to[axis] = center + (half + cube.teen_pro_YT_inflate);
        }
        double[][] rects = cube.teen_pro_YT_boxUv ? boxUv(cube, floatBoxUv) : cube.teen_pro_YT_uv;
        for (int face = 0; face < 6; face++) {
            if (cube.teen_pro_YT_hidden[face] || rects[face] == null) continue;
            double[][] corners = corners(face, from, to);
            double[][] uv = faceUv(rects[face], cube.teen_pro_YT_uvRotation[face]);
            double[] normal = teen_pro_YT_NORMALS[face].clone();
            for (double[] corner : corners) around(corner, cube.teen_pro_YT_origin, cube.teen_pro_YT_rotation);
            rotate(normal, cube.teen_pro_YT_rotation);
            for (Group cursor = group; cursor != null; cursor = cursor.teen_pro_YT_parent) {
                for (double[] corner : corners) around(corner, cursor.teen_pro_YT_origin, cursor.teen_pro_YT_rotation);
                rotate(normal, cursor.teen_pro_YT_rotation);
            }
            int texture = cube.teen_pro_YT_texture[face];
            double width = widths[Math.min(texture, widths.length - 1)], height = heights[Math.min(texture, heights.length - 1)];
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("Texture size must be positive");
            float[] vertices = new float[4 * OutfitQuad.teen_pro_YT_STRIDE];
            for (int k = 0; k < 4; k++) {
                int index = teen_pro_YT_LOOP[k];
                int at = k * OutfitQuad.teen_pro_YT_STRIDE;
                vertices[at] = (float) -corners[index][0];
                vertices[at + 1] = (float) (24 - corners[index][1]);
                vertices[at + 2] = (float) corners[index][2];
                vertices[at + 3] = (float) (uv[index][0] / width);
                vertices[at + 4] = (float) (uv[index][1] / height);
            }
            quads.add(new OutfitQuad(part, texture, vertices, (float) -normal[0], (float) -normal[1], (float) normal[2]));
        }
    }

    static double[][] boxUv(Cube cube, boolean floatSize) {
        double sx = size(cube, 0, floatSize), sy = size(cube, 1, floatSize), sz = size(cube, 2, floatSize);
        double[][] from = {{0, sz}, {sz + sx, sz}, {sz + sx, sz}, {sz + sx * 2, 0}, {sz * 2 + sx, sz}, {sz, sz}};
        double[][] extent = {{sz, sy}, {sz, sy}, {-sx, -sz}, {-sx, sz}, {sx, sy}, {sx, sy}};
        if (cube.teen_pro_YT_mirror) {
            for (int face = 0; face < 6; face++) { from[face][0] += extent[face][0]; extent[face][0] *= -1; }
            double[] swapFrom = from[0], swapExtent = extent[0];
            from[0] = from[1]; extent[0] = extent[1];
            from[1] = swapFrom; extent[1] = swapExtent;
        }
        double[][] rects = new double[6][];
        double u = cube.teen_pro_YT_uvOffset[0], v = cube.teen_pro_YT_uvOffset[1];
        for (int face = 0; face < 6; face++)
            rects[face] = new double[]{from[face][0] + u, from[face][1] + v, from[face][0] + extent[face][0] + u, from[face][1] + extent[face][1] + v};
        return rects;
    }

    private static double size(Cube cube, int axis, boolean floatSize) {
        double value = cube.teen_pro_YT_to[axis] - cube.teen_pro_YT_from[axis];
        return floatSize ? value : Math.floor(value + 1.0E-7);
    }

    static double[][] corners(int face, double[] f, double[] t) {
        return switch (face) {
            case 0 -> new double[][]{{t[0], t[1], t[2]}, {t[0], t[1], f[2]}, {t[0], f[1], t[2]}, {t[0], f[1], f[2]}};
            case 1 -> new double[][]{{f[0], t[1], f[2]}, {f[0], t[1], t[2]}, {f[0], f[1], f[2]}, {f[0], f[1], t[2]}};
            case 2 -> new double[][]{{f[0], t[1], f[2]}, {t[0], t[1], f[2]}, {f[0], t[1], t[2]}, {t[0], t[1], t[2]}};
            case 3 -> new double[][]{{f[0], f[1], t[2]}, {t[0], f[1], t[2]}, {f[0], f[1], f[2]}, {t[0], f[1], f[2]}};
            case 4 -> new double[][]{{f[0], t[1], t[2]}, {t[0], t[1], t[2]}, {f[0], f[1], t[2]}, {t[0], f[1], t[2]}};
            default -> new double[][]{{t[0], t[1], f[2]}, {f[0], t[1], f[2]}, {t[0], f[1], f[2]}, {f[0], f[1], f[2]}};
        };
    }

    static double[][] faceUv(double[] rect, int rotation) {
        double[][] uv = {{rect[0], rect[1]}, {rect[2], rect[1]}, {rect[0], rect[3]}, {rect[2], rect[3]}};
        int turns = Math.floorMod(Math.round(rotation / 90.0F), 4);
        for (int i = 0; i < turns; i++) {
            double[] first = uv[0];
            uv[0] = uv[2];
            uv[2] = uv[3];
            uv[3] = uv[1];
            uv[1] = first;
        }
        return uv;
    }

    static void around(double[] point, double[] pivot, double[] degrees) {
        if (degrees[0] == 0 && degrees[1] == 0 && degrees[2] == 0) return;
        for (int axis = 0; axis < 3; axis++) point[axis] -= pivot[axis];
        rotate(point, degrees);
        for (int axis = 0; axis < 3; axis++) point[axis] += pivot[axis];
    }

    static void rotate(double[] v, double[] degrees) {
        double x = v[0], y = v[1], z = v[2];
        if (degrees[0] != 0) {
            double a = Math.toRadians(degrees[0]), c = Math.cos(a), s = Math.sin(a);
            double ny = y * c - z * s, nz = y * s + z * c;
            y = ny; z = nz;
        }
        if (degrees[1] != 0) {
            double a = Math.toRadians(degrees[1]), c = Math.cos(a), s = Math.sin(a);
            double nx = x * c + z * s, nz = -x * s + z * c;
            x = nx; z = nz;
        }
        if (degrees[2] != 0) {
            double a = Math.toRadians(degrees[2]), c = Math.cos(a), s = Math.sin(a);
            double nx = x * c - y * s, ny = x * s + y * c;
            x = nx; y = ny;
        }
        v[0] = x; v[1] = y; v[2] = z;
    }

    private static boolean descends(Group candidate, Group ancestor) {
        for (Group cursor = candidate.teen_pro_YT_parent; cursor != null; cursor = cursor.teen_pro_YT_parent) if (cursor == ancestor) return true;
        return false;
    }

    private static String text(Object value, String fallback) {
        return value instanceof String text && !text.isEmpty() ? text : fallback;
    }

    static byte[] png(String source) {
        if (source == null) return null;
        int comma = source.indexOf(',');
        if (!source.startsWith("data:image/png;base64,") || comma < 0) return null;
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(source.substring(comma + 1));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Embedded texture is not valid base64");
        }
        if (bytes.length > teen_pro_YT_MAX_TEXTURE_BYTES) throw new IllegalArgumentException("Embedded texture is too large");
        if (bytes.length < teen_pro_YT_PNG.length) throw new IllegalArgumentException("Embedded texture is not a PNG");
        for (int i = 0; i < teen_pro_YT_PNG.length; i++) if (bytes[i] != teen_pro_YT_PNG[i]) throw new IllegalArgumentException("Embedded texture is not a PNG");
        return bytes;
    }
}
