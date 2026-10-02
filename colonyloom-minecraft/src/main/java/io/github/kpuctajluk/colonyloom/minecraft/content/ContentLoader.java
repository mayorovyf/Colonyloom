package io.github.kpuctajluk.colonyloom.minecraft.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.kpuctajluk.colonyloom.core.citizen.ProfessionDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/** A reload publishes a complete validated set; a bad pack never partially replaces it. */
public final class ContentLoader extends SimplePreparableReloadListener<Map<String, ProfessionDefinition>> {
    private static final String DIRECTORY = "colonyloom/professions";
    private static final int MAX_FILE_BYTES = 8192;
    private static final int MAX_DEFINITIONS = 64;
    private static final int MAX_TOTAL_BYTES = MAX_FILE_BYTES * MAX_DEFINITIONS;

    public static Map<String, ProfessionDefinition> load(ResourceManager resources) {
        return readDefinitions(resources);
    }

    @Override
    protected Map<String, ProfessionDefinition> prepare(ResourceManager resources, ProfilerFiller profiler) {
        return readDefinitions(resources);
    }

    private static Map<String, ProfessionDefinition> readDefinitions(ResourceManager resources) {
        var found = resources.listResources(DIRECTORY, path -> path.getPath().endsWith(".json"));
        if (found.size() > MAX_DEFINITIONS) throw new IllegalArgumentException("Too many Colonyloom professions");
        Map<String, ProfessionDefinition> definitions = new LinkedHashMap<>();
        int total = 0;
        for (var entry : found.entrySet()) {
            byte[] bytes;
            try (InputStream input = entry.getValue().open()) {
                bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            } catch (IOException error) {
                throw new IllegalArgumentException("Cannot read Colonyloom content " + entry.getKey(), error);
            }
            total += bytes.length;
            if (bytes.length > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException("Colonyloom profession content exceeds byte cap");
            }
            String path = entry.getKey().getPath();
            String id = ResourceLocation.fromNamespaceAndPath(entry.getKey().getNamespace(),
                    path.substring(DIRECTORY.length() + 1, path.length() - 5)).toString();
            JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (integer(json, "schemaVersion") != 1) throw new IllegalArgumentException("Unsupported profession schema " + id);
            var definition = new ProfessionDefinition(id, integer(json, "version"),
                    identifiers(json.getAsJsonArray("allowedWorkTypes")), identifiers(json.getAsJsonArray("equipment")));
            if (definitions.put(id, definition) != null) throw new IllegalArgumentException("Duplicate profession " + id);
        }
        for (String required : Set.of("colonyloom:builder", "colonyloom:courier", "colonyloom:carpenter")) {
            if (!definitions.containsKey(required)) throw new IllegalArgumentException("Missing profession " + required);
        }
        return Map.copyOf(definitions);
    }

    @Override
    protected void apply(Map<String, ProfessionDefinition> definitions, ResourceManager resources, ProfilerFiller profiler) {
        LogUtils.getLogger().info("Colonyloom content validated: professions={}", definitions.size());
    }

    private static int integer(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Expected integer " + key);
        }
        return value.getAsBigDecimal().intValueExact();
    }

    private static Set<String> identifiers(JsonArray values) {
        if (values == null || values.size() > 16) throw new IllegalArgumentException("Invalid identifier list");
        Set<String> result = new LinkedHashSet<>();
        for (JsonElement value : values) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected identifier string");
            String id = value.getAsString();
            if (id.length() > 256 || ResourceLocation.tryParse(id) == null || !id.contains(":")) {
                throw new IllegalArgumentException("Invalid content identifier");
            }
            if (!result.add(id)) throw new IllegalArgumentException("Duplicate content identifier " + id);
        }
        return Set.copyOf(result);
    }
}
