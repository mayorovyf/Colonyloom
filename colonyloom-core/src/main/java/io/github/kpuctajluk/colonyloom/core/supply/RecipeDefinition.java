package io.github.kpuctajluk.colonyloom.core.supply;

import io.github.kpuctajluk.colonyloom.core.content.BlockDescriptor;
import io.github.kpuctajluk.colonyloom.core.storage.ItemDescriptor;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Platform-independent, self-validating snapshot retained by admitted production orders. */
public record RecipeDefinition(String id, int version, String professionId, String equipmentId,
        List<Ingredient> ingredients, ItemDescriptor output, long outputCount, long activeTicks, String digest) {
    public static final int MAX_INGREDIENTS = 8;
    public static final long MAX_COUNT = 1_000_000;

    public RecipeDefinition {
        BlockDescriptor.validateIdentifier(id);
        BlockDescriptor.validateIdentifier(professionId);
        BlockDescriptor.validateIdentifier(equipmentId);
        if (version < 1 || activeTicks < 1 || activeTicks > MAX_COUNT) throw new IllegalArgumentException("Invalid recipe version/duration");
        ingredients = List.copyOf(ingredients);
        if (ingredients.isEmpty() || ingredients.size() > MAX_INGREDIENTS) throw new IllegalArgumentException("Invalid ingredient count");
        var matchers = new HashSet<ItemMatcher>();
        for (Ingredient ingredient : ingredients) if (!matchers.add(ingredient.matcher())) throw new IllegalArgumentException("Duplicate ingredient matcher");
        Objects.requireNonNull(output);
        count(outputCount);
        String actual = canonicalDigest(id, version, professionId, equipmentId, ingredients, output, outputCount, activeTicks);
        if (digest != null && !digest.equals(actual)) throw new IllegalArgumentException("Recipe digest mismatch");
        digest = actual;
    }

    public record Ingredient(ItemMatcher matcher, long count) {
        public Ingredient { Objects.requireNonNull(matcher); RecipeDefinition.count(count); }
    }

    public static RecipeDefinition create(String id, int version, String professionId, String equipmentId,
            List<Ingredient> ingredients, ItemDescriptor output, long outputCount, long activeTicks) {
        return new RecipeDefinition(id, version, professionId, equipmentId, ingredients, output, outputCount, activeTicks, null);
    }

    public long batchesFor(long quantity) {
        count(quantity);
        return 1 + (quantity - 1) / outputCount;
    }

    private static void count(long count) {
        if (count < 1 || count > MAX_COUNT) throw new IllegalArgumentException("Recipe quantity outside 1..1000000");
    }

    private static String canonicalDigest(String id, int version, String profession, String equipment,
            List<Ingredient> inputs, ItemDescriptor output, long count, long duration) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var data = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                data.writeUTF(id); data.writeInt(version); data.writeUTF(profession); data.writeUTF(equipment);
                data.writeInt(inputs.size());
                for (Ingredient input : inputs) {
                    data.writeUTF(input.matcher().itemId());
                    data.writeBoolean(input.matcher().exact() != null);
                    if (input.matcher().exact() != null) components(data, input.matcher().exact());
                    data.writeLong(input.count());
                }
                data.writeUTF(output.itemId()); components(data, output);
                data.writeLong(count); data.writeLong(duration);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException impossible) {
            throw new IllegalStateException("Cannot hash recipe", impossible);
        }
    }

    private static void components(DataOutputStream data, ItemDescriptor item) throws IOException {
        byte[] bytes = item.canonicalComponents(); data.writeInt(bytes.length); data.write(bytes);
    }
}
