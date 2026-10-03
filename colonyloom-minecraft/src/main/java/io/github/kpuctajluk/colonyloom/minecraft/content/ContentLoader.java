package io.github.kpuctajluk.colonyloom.minecraft.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.logging.LogUtils;
import io.github.kpuctajluk.colonyloom.core.citizen.ProfessionDefinition;
import io.github.kpuctajluk.colonyloom.core.content.BlockDescriptor;
import io.github.kpuctajluk.colonyloom.core.content.BlockOffset;
import io.github.kpuctajluk.colonyloom.core.content.BlueprintDefinition;
import io.github.kpuctajluk.colonyloom.core.supply.ItemMatcher;
import io.github.kpuctajluk.colonyloom.core.supply.RecipeDefinition;
import io.github.kpuctajluk.colonyloom.gameplay.production.ProcessDefinition;
import io.github.kpuctajluk.colonyloom.minecraft.storage.NativeItemDescriptor;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** A reload prepares one complete validated set; active orders retain their immutable pinned models. */
public final class ContentLoader extends SimplePreparableReloadListener<ContentLoader.Content> {
    private static final String PROFESSIONS = "colonyloom/professions";
    private static final String BLUEPRINTS = "colonyloom/blueprints";
    private static final String PROCESSES = "colonyloom/processes";
    private static final int MAX_JSON_BYTES = 8192;
    private static final int MAX_TEMPLATE_BYTES = 4 * 1024 * 1024;
    private static final long MAX_TEMPLATE_HEAP_BYTES = 32L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    private static final int MAX_DEFINITIONS = 64;
    private static final Set<String> BUILDING_BLOCKS = Set.of("minecraft:oak_planks", "minecraft:oak_stairs");

    public record Content(Map<String, ProfessionDefinition> professions, Map<String, BlueprintDefinition> blueprints,
                          Map<String, ProcessDefinition> processes) {
        public Content {
            professions = Map.copyOf(professions);
            blueprints = Map.copyOf(blueprints);
            processes = Map.copyOf(processes);
            professions.forEach((id, definition) -> {
                if (!id.equals(definition.id())) throw new IllegalArgumentException("Profession key mismatch");
            });
            blueprints.forEach((id, definition) -> {
                if (!id.equals(definition.id())) throw new IllegalArgumentException("Blueprint key mismatch");
            });
            processes.forEach((id, definition) -> {
                if (!id.equals(definition.id())) throw new IllegalArgumentException("Process key mismatch");
            });
        }
    }

    private final HolderLookup.Provider registries;
    public ContentLoader(HolderLookup.Provider registries) { this.registries = java.util.Objects.requireNonNull(registries); }

    public static Content load(ResourceManager resources, HolderLookup.Provider registries) {
        var professionFiles = resources.listResources(PROFESSIONS, path -> path.getPath().endsWith(".json"));
        var blueprintFiles = resources.listResources(BLUEPRINTS, path -> path.getPath().endsWith(".json"));
        var processFiles = resources.listResources(PROCESSES, path -> path.getPath().endsWith(".json"));
        if (professionFiles.size() + blueprintFiles.size() + processFiles.size() > MAX_DEFINITIONS) {
            throw new IllegalArgumentException("Too many Colonyloom definitions");
        }
        ByteBudget budget = new ByteBudget();
        Map<String, ProfessionDefinition> professions = new LinkedHashMap<>();
        for (var entry : professionFiles.entrySet()) {
            String id = definitionId(entry.getKey(), PROFESSIONS);
            JsonObject json = json(readBytes(entry.getValue(), MAX_JSON_BYTES, budget, id));
            fields(json, Set.of("schemaVersion", "version", "allowedWorkTypes", "equipment"));
            schema(json, id);
            var definition = new ProfessionDefinition(id, version(json),
                    identifiers(json.getAsJsonArray("allowedWorkTypes")), identifiers(json.getAsJsonArray("equipment")));
            for (String equipment : definition.equipment()) {
                if (!BuiltInRegistries.ITEM.containsKey(location(equipment))) {
                    throw new IllegalArgumentException("Unknown equipment item " + equipment);
                }
            }
            if (professions.put(id, definition) != null) throw new IllegalArgumentException("Duplicate profession " + id);
        }
        for (String required : Set.of("colonyloom:builder", "colonyloom:courier", "colonyloom:carpenter")) {
            if (!professions.containsKey(required)) throw new IllegalArgumentException("Missing profession " + required);
        }
        Map<String, BlueprintDefinition> blueprints = new LinkedHashMap<>();
        for (var entry : blueprintFiles.entrySet()) {
            String id = definitionId(entry.getKey(), BLUEPRINTS);
            JsonObject json = json(readBytes(entry.getValue(), MAX_JSON_BYTES, budget, id));
            fields(json, Set.of("schemaVersion", "version", "structure", "markers"));
            schema(json, id);
            ResourceLocation structure = location(string(json, "structure"));
            ResourceLocation resourcePath = ResourceLocation.fromNamespaceAndPath(structure.getNamespace(),
                    "structure/" + structure.getPath() + ".nbt");
            Resource resource = resources.getResource(resourcePath)
                    .orElseThrow(() -> new IllegalArgumentException("Missing blueprint structure " + structure));
            byte[] bytes = readBytes(resource, MAX_TEMPLATE_BYTES, budget, resourcePath.toString());
            NbtAccounter accounter = new NbtAccounter(MAX_TEMPLATE_HEAP_BYTES, 32);
            CompoundTag template;
            try {
                template = NbtIo.readCompressed(new BufferedInputStream(new ByteArrayInputStream(bytes)), accounter);
            } catch (IOException error) {
                throw new IllegalArgumentException("Cannot decode blueprint " + id, error);
            }
            budget.add(accounter.getUsage());
            BlueprintDefinition definition = decodeTemplate(id, version(json), template, markers(json));
            if (blueprints.put(id, definition) != null) throw new IllegalArgumentException("Duplicate blueprint " + id);
        }
        if (!blueprints.containsKey("colonyloom:stair_strip")) throw new IllegalArgumentException("Missing blueprint colonyloom:stair_strip");
        Map<String, ProcessDefinition> processes = new LinkedHashMap<>();
        for (var entry : processFiles.entrySet()) {
            String id = definitionId(entry.getKey(), PROCESSES);
            JsonObject json = json(readBytes(entry.getValue(), MAX_JSON_BYTES, budget, id));
            fields(json, Set.of("schemaVersion", "version", "profession", "equipment", "inputs", "output", "durationTicks"));
            schema(json, id);
            String profession = string(json, "profession"), equipment = string(json, "equipment");
            ProfessionDefinition professionDefinition = professions.get(profession);
            if (professionDefinition == null || !professionDefinition.equipment().contains(equipment)
                    || !BuiltInRegistries.ITEM.containsKey(location(equipment))
                    || !equipment.equals("minecraft:crafting_table")) throw new IllegalArgumentException("Unsupported process profession/equipment " + id);
            JsonElement inputsElement = json.get("inputs"), outputElement = json.get("output");
            if (!inputsElement.isJsonArray() || !outputElement.isJsonObject()) throw new IllegalArgumentException("Invalid process input/output " + id);
            JsonArray inputs = inputsElement.getAsJsonArray();
            if (inputs.isEmpty() || inputs.size() > RecipeDefinition.MAX_INGREDIENTS) throw new IllegalArgumentException("Invalid process ingredient count " + id);
            var ingredients = new ArrayList<RecipeDefinition.Ingredient>();
            for (JsonElement input : inputs) {
                if (!input.isJsonObject()) throw new IllegalArgumentException("Expected process ingredient object");
                JsonObject ingredient = input.getAsJsonObject(); fields(ingredient, Set.of("item", "count"));
                String item = string(ingredient, "item"); requireItem(item);
                ingredients.add(new RecipeDefinition.Ingredient(new ItemMatcher(item, null), integer(ingredient.get("count"))));
            }
            JsonObject output = outputElement.getAsJsonObject(); fields(output, Set.of("item", "count"));
            String outputId = string(output, "item"); requireItem(outputId);
            var descriptor = NativeItemDescriptor.describe(new ItemStack(BuiltInRegistries.ITEM.get(location(outputId))), registries);
            RecipeDefinition recipe = RecipeDefinition.create(id, version(json), profession, equipment, ingredients,
                    descriptor, integer(output.get("count")), integer(json.get("durationTicks")));
            if (processes.put(id, new ProcessDefinition(recipe)) != null) throw new IllegalArgumentException("Duplicate process " + id);
        }
        for (String required : Set.of("colonyloom:oak_planks", "colonyloom:oak_stairs")) {
            if (!processes.containsKey(required)) throw new IllegalArgumentException("Missing process " + required);
        }
        return new Content(professions, blueprints, processes);
    }

    @Override
    protected Content prepare(ResourceManager resources, ProfilerFiller profiler) {
        return load(resources, registries);
    }

    @Override
    protected void apply(Content content, ResourceManager resources, ProfilerFiller profiler) {
        LogUtils.getLogger().info("Colonyloom content validated: professions={}, blueprints={}, processes={}",
                content.professions().size(), content.blueprints().size(), content.processes().size());
    }

    private static void requireItem(String id) {
        if (!BuiltInRegistries.ITEM.containsKey(location(id)) || id.equals("minecraft:air")) throw new IllegalArgumentException("Unknown process item " + id);
    }

    /** Strict registry/state validation also works for pinned definitions after their datapack was removed. */
    public static BlockState decodeBlockState(BlockDescriptor descriptor) {
        if (!BUILDING_BLOCKS.contains(descriptor.blockId())) throw new IllegalArgumentException("Unsupported building block " + descriptor.blockId());
        ResourceLocation blockId = location(descriptor.blockId());
        ResourceLocation itemId = location(descriptor.itemId());
        if (!BuiltInRegistries.BLOCK.containsKey(blockId) || !BuiltInRegistries.ITEM.containsKey(itemId)) {
            throw new IllegalArgumentException("Unknown building block or item");
        }
        var block = BuiltInRegistries.BLOCK.get(blockId);
        var item = BuiltInRegistries.ITEM.get(itemId);
        if (!(item instanceof BlockItem blockItem) || blockItem.getBlock() != block || block.asItem() != item) {
            throw new IllegalArgumentException("Blueprint material does not place its block");
        }
        if (descriptor.properties().size() != block.getStateDefinition().getProperties().size()) {
            throw new IllegalArgumentException("Pinned block descriptor must contain every property");
        }
        BlockState state = block.defaultBlockState();
        for (var entry : descriptor.properties().entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
            if (property == null) throw new IllegalArgumentException("Unknown block property " + entry.getKey());
            state = setProperty(state, property, entry.getValue());
        }
        if (!state.getFluidState().isEmpty() || state.hasBlockEntity()) throw new IllegalArgumentException("Unsafe building state");
        return state;
    }

    private static <T extends Comparable<T>> BlockState setProperty(BlockState state, Property<T> property, String text) {
        T value = property.getValue(text).orElseThrow(() -> new IllegalArgumentException("Invalid property value " + property.getName() + "=" + text));
        return state.setValue(property, value);
    }

    private static <T extends Comparable<T>> String propertyText(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static BlockDescriptor paletteState(CompoundTag tag) {
        if (!tag.contains("Name", Tag.TAG_STRING)) throw new IllegalArgumentException("Palette state lacks block ID");
        String id = tag.getString("Name");
        if (!BUILDING_BLOCKS.contains(id) || !BuiltInRegistries.BLOCK.containsKey(location(id))) {
            throw new IllegalArgumentException("Unsupported palette block " + id);
        }
        var block = BuiltInRegistries.BLOCK.get(location(id));
        BlockState state = block.defaultBlockState();
        if (tag.contains("Properties")) {
            if (!tag.contains("Properties", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Invalid palette properties");
            CompoundTag properties = tag.getCompound("Properties");
            if (properties.size() > 16) throw new IllegalArgumentException("Too many palette properties");
            for (String name : properties.getAllKeys()) {
                if (!properties.contains(name, Tag.TAG_STRING)) throw new IllegalArgumentException("Property value must be string");
                Property<?> property = block.getStateDefinition().getProperty(name);
                if (property == null) throw new IllegalArgumentException("Unknown block property " + name);
                state = setProperty(state, property, properties.getString(name));
            }
        }
        if (!tag.getAllKeys().stream().allMatch(key -> key.equals("Name") || key.equals("Properties"))) {
            throw new IllegalArgumentException("Unsupported palette state fields");
        }
        Map<String, String> properties = new LinkedHashMap<>();
        for (Property<?> property : block.getStateDefinition().getProperties()) {
            properties.put(property.getName(), propertyText(state, property));
        }
        var descriptor = new BlockDescriptor(id, properties, BuiltInRegistries.ITEM.getKey(block.asItem()).toString());
        decodeBlockState(descriptor);
        return descriptor;
    }

    /** Decodes the vanilla single-palette StructureTemplate format without placing anything in the world. */
    public static BlueprintDefinition decodeTemplate(String id, int version, CompoundTag template, Map<String, BlockOffset> markers) {
        if (template.size() > 7 || !Set.of("DataVersion", "author", "size", "palette", "blocks", "entities", "palettes").containsAll(template.getAllKeys())) {
            throw new IllegalArgumentException("Unsupported structure template fields");
        }
        if (markers.size() > BlueprintDefinition.MAX_MARKERS || !markers.keySet().containsAll(Set.of("work_origin", "delivery_buffer"))) {
            throw new IllegalArgumentException("Invalid blueprint work/delivery markers");
        }
        if (template.contains("palettes")) throw new IllegalArgumentException("Randomized multiple palettes are not supported");
        if (template.contains("entities") && (!(template.get("entities") instanceof ListTag entities) || !entities.isEmpty())) {
            throw new IllegalArgumentException("Blueprint entities are forbidden");
        }
        int[] size = position(template, "size");
        for (int axis : size) if (axis < 1 || axis > 64) throw new IllegalArgumentException("Blueprint size exceeds 1..64");
        ListTag palette = compoundList(template, "palette");
        ListTag blocks = compoundList(template, "blocks");
        if (palette.isEmpty() || palette.size() > 512 || blocks.isEmpty() || blocks.size() > BlueprintDefinition.MAX_BLOCKS) {
            throw new IllegalArgumentException("Blueprint palette/block model cap exceeded");
        }
        var states = new ArrayList<BlockDescriptor>(palette.size());
        var uniqueStates = new HashSet<BlockDescriptor>();
        for (int index = 0; index < palette.size(); index++) {
            BlockDescriptor descriptor = paletteState(palette.getCompound(index));
            if (!uniqueStates.add(descriptor)) throw new IllegalArgumentException("Duplicate palette state");
            states.add(descriptor);
        }
        var specs = new ArrayList<BlueprintDefinition.BlockSpec>(blocks.size());
        for (int index = 0; index < blocks.size(); index++) {
            CompoundTag entry = blocks.getCompound(index);
            if (entry.contains("nbt")) throw new IllegalArgumentException("Block entity NBT is forbidden");
            if (!entry.getAllKeys().equals(Set.of("pos", "state"))) throw new IllegalArgumentException("Unsupported structure block fields");
            int[] pos = position(entry, "pos");
            for (int axis = 0; axis < 3; axis++) {
                if (pos[axis] < 0 || pos[axis] >= size[axis]) throw new IllegalArgumentException("Block outside template size");
            }
            if (!entry.contains("state", Tag.TAG_INT)) throw new IllegalArgumentException("Expected palette index");
            int state = entry.getInt("state");
            if (state < 0 || state >= states.size()) throw new IllegalArgumentException("Invalid palette index");
            specs.add(new BlueprintDefinition.BlockSpec(new BlockOffset(pos[0], pos[1], pos[2]), states.get(state)));
        }
        return BlueprintDefinition.create(id, version, specs, markers);
    }

    private static ListTag compoundList(CompoundTag tag, String key) {
        if (!(tag.get(key) instanceof ListTag list) || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Expected compound list " + key);
        }
        return list;
    }

    private static int[] position(CompoundTag tag, String key) {
        if (!(tag.get(key) instanceof ListTag list) || list.size() != 3 || list.getElementType() != Tag.TAG_INT) {
            throw new IllegalArgumentException("Expected three integer coordinates " + key);
        }
        return new int[] {list.getInt(0), list.getInt(1), list.getInt(2)};
    }

    private static Map<String, BlockOffset> markers(JsonObject json) {
        if (!json.has("markers") || !json.get("markers").isJsonObject()) throw new IllegalArgumentException("Expected markers object");
        JsonObject markers = json.getAsJsonObject("markers");
        if (markers.size() > BlueprintDefinition.MAX_MARKERS) throw new IllegalArgumentException("Too many blueprint markers");
        Map<String, BlockOffset> result = new LinkedHashMap<>();
        for (var entry : markers.entrySet()) {
            if (!entry.getValue().isJsonArray() || entry.getValue().getAsJsonArray().size() != 3) throw new IllegalArgumentException("Expected marker coordinates");
            JsonArray values = entry.getValue().getAsJsonArray();
            result.put(entry.getKey(), new BlockOffset(integer(values.get(0)), integer(values.get(1)), integer(values.get(2))));
        }
        if (!result.keySet().containsAll(Set.of("work_origin", "delivery_buffer"))) throw new IllegalArgumentException("Missing blueprint work/delivery markers");
        return result;
    }

    private static String definitionId(ResourceLocation resource, String directory) {
        String path = resource.getPath();
        String id = ResourceLocation.fromNamespaceAndPath(resource.getNamespace(), path.substring(directory.length() + 1, path.length() - 5)).toString();
        BlockDescriptor.validateIdentifier(id);
        return id;
    }

    private static byte[] readBytes(Resource resource, int cap, ByteBudget budget, String id) {
        try (InputStream input = resource.open()) {
            byte[] bytes = input.readNBytes(cap + 1);
            if (bytes.length > cap) throw new IllegalArgumentException("Content file exceeds byte cap " + id);
            budget.add(bytes.length);
            return bytes;
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot read Colonyloom content " + id, error);
        }
    }

    private static final class ByteBudget {
        private long used;
        private void add(long bytes) {
            used += bytes;
            if (used > MAX_TOTAL_BYTES) throw new IllegalArgumentException("Colonyloom content exceeds total byte cap");
        }
    }

    private static JsonObject json(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Content is not UTF-8", error);
        }
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement value = readJson(reader, 0);
            if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Expected one JSON object");
            return value.getAsJsonObject();
        } catch (IOException | NumberFormatException error) {
            throw new IllegalArgumentException("Invalid content JSON", error);
        }
    }

    private static JsonElement readJson(JsonReader reader, int depth) throws IOException {
        if (depth > 16) throw new IllegalArgumentException("Content JSON nesting cap exceeded");
        switch (reader.peek()) {
            case BEGIN_OBJECT: {
                reader.beginObject();
                JsonObject object = new JsonObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (name.length() > 256 || object.size() >= 256 || object.has(name)) throw new IllegalArgumentException("Duplicate/oversized JSON field");
                    object.add(name, readJson(reader, depth + 1));
                }
                reader.endObject();
                return object;
            }
            case BEGIN_ARRAY: {
                reader.beginArray();
                JsonArray array = new JsonArray();
                while (reader.hasNext()) {
                    if (array.size() >= 256) throw new IllegalArgumentException("Content JSON array cap exceeded");
                    array.add(readJson(reader, depth + 1));
                }
                reader.endArray();
                return array;
            }
            case STRING: {
                String value = reader.nextString();
                if (value.length() > 256) throw new IllegalArgumentException("Content string cap exceeded");
                return new JsonPrimitive(value);
            }
            case NUMBER: {
                String value = reader.nextString();
                if (value.length() > 32) throw new IllegalArgumentException("Content number cap exceeded");
                return new JsonPrimitive(new BigDecimal(value));
            }
            default: throw new IllegalArgumentException("Unsupported content JSON value");
        }
    }

    private static void fields(JsonObject json, Set<String> expected) {
        if (!json.keySet().equals(expected)) throw new IllegalArgumentException("Missing or unsupported definition fields");
    }

    private static void schema(JsonObject json, String id) {
        if (integer(json.get("schemaVersion")) != BlueprintDefinition.SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported content schema " + id);
    }

    private static int version(JsonObject json) {
        int version = integer(json.get("version"));
        if (version < 1) throw new IllegalArgumentException("Content version must be positive");
        return version;
    }

    private static int integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected integer");
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("Integer outside supported range", error);
        }
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected string " + key);
        return value.getAsString();
    }

    private static ResourceLocation location(String id) {
        BlockDescriptor.validateIdentifier(id);
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) throw new IllegalArgumentException("Invalid content identifier " + id);
        return location;
    }

    private static Set<String> identifiers(JsonArray values) {
        if (values == null || values.size() > 16) throw new IllegalArgumentException("Invalid identifier list");
        Set<String> result = new LinkedHashSet<>();
        for (JsonElement value : values) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected identifier string");
            String id = value.getAsString();
            location(id);
            if (!result.add(id)) throw new IllegalArgumentException("Duplicate content identifier " + id);
        }
        return Set.copyOf(result);
    }
}
