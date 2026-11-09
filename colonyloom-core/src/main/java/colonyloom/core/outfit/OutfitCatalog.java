package colonyloom.core.outfit;

import colonyloom.core.life.json.Json;
import colonyloom.core.life.json.Obj;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.UUID;

public final class OutfitCatalog {
    public static final String teen_pro_YT_ROOT = "colonyloom/outfits/";
    public static final String teen_pro_YT_INDEX = "index.txt";
    public static final int teen_pro_YT_MAX_OUTFITS = 256;
    public static final int teen_pro_YT_MAX_DEFINITION_BYTES = 16 * 1024;
    public static final OutfitCatalog teen_pro_YT_EMPTY = new OutfitCatalog(Map.of());

    @FunctionalInterface
    public interface Source {
        InputStream open(String path) throws IOException;
    }

    private final Map<String, OutfitDefinition> teen_pro_YT_outfits;

    public OutfitCatalog(Map<String, OutfitDefinition> outfits) {
        teen_pro_YT_outfits = Collections.unmodifiableMap(new LinkedHashMap<>(outfits));
        if (outfits.size() > teen_pro_YT_MAX_OUTFITS) throw new IllegalArgumentException("Too many outfits");
        outfits.forEach((id, outfit) -> { if (!id.equals(outfit.id())) throw new IllegalArgumentException("Outfit key mismatch " + id); });
    }

    public Map<String, OutfitDefinition> outfits() { return teen_pro_YT_outfits; }
    public OutfitDefinition outfit(String id) { return id == null ? null : teen_pro_YT_outfits.get(id); }

    public static OutfitCatalog load(Source source) throws IOException {
        Objects.requireNonNull(source);
        List<String> files = new ArrayList<>();
        try (InputStream index = source.open(teen_pro_YT_INDEX)) {
            if (index == null) return teen_pro_YT_EMPTY;
            for (String line : new String(index.readNBytes(64 * 1024), StandardCharsets.UTF_8).split("\n")) {
                String file = line.strip();
                if (file.isEmpty() || file.startsWith("#")) continue;
                OutfitDefinition.path(file, "definition");
                if (!file.endsWith(".json") || file.endsWith(".geo.json")) throw new IllegalArgumentException("Outfit index lists a non-definition file " + file);
                files.add(file);
            }
        }
        if (files.size() > teen_pro_YT_MAX_OUTFITS) throw new IllegalArgumentException("Too many outfits");
        Map<String, OutfitDefinition> outfits = new LinkedHashMap<>();
        for (String file : files) {
            String id = "colonyloom:" + file.substring(0, file.length() - ".json".length());
            byte[] bytes;
            try (InputStream in = source.open(file)) {
                if (in == null) throw new IllegalArgumentException("Missing outfit definition " + file);
                bytes = in.readNBytes(teen_pro_YT_MAX_DEFINITION_BYTES + 1);
            }
            if (bytes.length > teen_pro_YT_MAX_DEFINITION_BYTES) throw new IllegalArgumentException("Outfit definition too large: " + file);
            OutfitDefinition definition = OutfitDefinition.decode(id, Obj.of(Json.parseObject(new String(bytes, StandardCharsets.UTF_8)), file));
            if (outfits.put(id, definition) != null) throw new IllegalArgumentException("Duplicate outfit " + id);
        }
        return new OutfitCatalog(outfits);
    }

    public String choose(OutfitProfile profile, UUID seed) {
        List<OutfitDefinition> fitting = new ArrayList<>();
        int total = 0;
        for (OutfitDefinition outfit : teen_pro_YT_outfits.values()) {
            if (outfit.weight() <= 0 || !outfit.fits(profile)) continue;
            fitting.add(outfit);
            total += outfit.weight();
        }
        if (fitting.isEmpty()) return null;
        long mix = seed.getMostSignificantBits() ^ Long.rotateLeft(seed.getLeastSignificantBits(), 17) ^ (31L * profile.hashCode());
        int pick = new SplittableRandom(mix).nextInt(total);
        for (OutfitDefinition outfit : fitting) {
            pick -= outfit.weight();
            if (pick < 0) return outfit.id();
        }
        return fitting.getLast().id();
    }
}
