package io.github.kpuctajluk.colonyloom.core.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BlueprintDefinitionTest {
    private static final String ID = "colonyloom:test";
    private static final BlockDescriptor PLANKS = new BlockDescriptor("minecraft:oak_planks", Map.of(), "minecraft:oak_planks");
    private static BlueprintDefinition.BlockSpec spec(int x, int y, int z) {
        return new BlueprintDefinition.BlockSpec(new BlockOffset(x, y, z), PLANKS);
    }

    @Test void duplicateTargetsAndOversizedExtentsCannotBecomePinnedSites() {
        assertThrows(IllegalArgumentException.class, () -> BlueprintDefinition.create(ID, 1,
                List.of(spec(0, 0, 0), spec(0, 0, 0)), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> BlueprintDefinition.create(ID, 1,
                List.of(spec(-1, 0, 0), spec(63, 0, 0)), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new BlockOffset(65, 0, 0));
        assertEquals(2, BlueprintDefinition.create(ID, 1,
                List.of(spec(0, 0, 0), spec(63, 0, 0)), Map.of()).blocks().size());
    }

    @Test void descriptorDigestIsIndependentOfInputOrderingButDetectsChangedContent() {
        var north = new LinkedHashMap<String, String>();
        north.put("facing", "north"); north.put("half", "bottom");
        var reordered = new LinkedHashMap<String, String>();
        reordered.put("half", "bottom"); reordered.put("facing", "north");
        var first = new BlueprintDefinition.BlockSpec(new BlockOffset(0, 0, 0),
                new BlockDescriptor("minecraft:oak_stairs", north, "minecraft:oak_stairs"));
        var same = new BlueprintDefinition.BlockSpec(first.offset(),
                new BlockDescriptor("minecraft:oak_stairs", reordered, "minecraft:oak_stairs"));
        var markers = Map.of("work_origin", new BlockOffset(0, 0, 1), "delivery_buffer", new BlockOffset(-1, 0, 0));
        var definition = BlueprintDefinition.create(ID, 1, List.of(spec(1, 0, 0), first), markers);
        var equivalent = BlueprintDefinition.create(ID, 1, List.of(same, spec(1, 0, 0)), markers);
        assertEquals(definition.digest(), equivalent.digest());
        assertThrows(IllegalArgumentException.class, () -> new BlueprintDefinition(ID, 2,
                definition.digest(), definition.blocks(), markers));
        assertThrows(IllegalArgumentException.class, () -> new BlueprintDefinition(ID, 1,
                definition.digest(), definition.blocks(), Map.of("work_origin", new BlockOffset(0, 0, 2))));
        assertThrows(IllegalArgumentException.class, () -> new BlueprintDefinition(ID, 1,
                definition.digest(), List.of(spec(2, 0, 0)), markers));
    }

    @Test void pinnedDefinitionCannotBeChangedByMutatingSourceCollections() {
        var properties = new LinkedHashMap<String, String>();
        properties.put("facing", "north");
        var block = new BlockDescriptor("minecraft:oak_stairs", properties, "minecraft:oak_stairs");
        var blocks = new ArrayList<>(List.of(new BlueprintDefinition.BlockSpec(new BlockOffset(0, 0, 0), block)));
        var markers = new LinkedHashMap<String, BlockOffset>();
        markers.put("work_origin", new BlockOffset(0, 0, 1));
        var pinned = BlueprintDefinition.create(ID, 1, blocks, markers);
        properties.put("facing", "south");
        blocks.clear(); markers.clear();
        assertEquals("north", pinned.blocks().getFirst().block().properties().get("facing"));
        assertEquals(new BlockOffset(0, 0, 1), pinned.markers().get("work_origin"));
        assertThrows(UnsupportedOperationException.class, () -> pinned.blocks().clear());
        assertThrows(UnsupportedOperationException.class, () -> pinned.blocks().getFirst().block().properties().clear());
        assertEquals(new BlueprintDefinition.Bounds(0, 0, 0, 0, 0, 0), pinned.bounds());
        assertEquals(List.of(block), pinned.palette());
        assertThrows(UnsupportedOperationException.class, () -> pinned.palette().clear());
    }

    @Test void maximumBlueprintPinsExactBoundsAndSharedPaletteCostOnce() {
        var blocks = new ArrayList<BlueprintDefinition.BlockSpec>(BlueprintDefinition.MAX_BLOCKS);
        for (int y = -8; y < 8; y++) for (int z = -32; z < 32; z++) for (int x = -32; x < 32; x++)
            blocks.add(spec(x, y, z));
        var definition = BlueprintDefinition.create(ID, 1, blocks,
                Map.of("work_origin", new BlockOffset(-33, 0, 0), "delivery_buffer", new BlockOffset(32, 0, 0)));
        assertEquals(BlueprintDefinition.MAX_BLOCKS, definition.blocks().size());
        assertEquals(new BlueprintDefinition.Bounds(-32, -8, -32, 31, 7, 31), definition.bounds());
        assertEquals(List.of(PLANKS), definition.palette());
        long expected = 1024 + ID.length() * 4L + 2 * 512L + BlueprintDefinition.MAX_BLOCKS * 64L
                + 256 + 4L * (PLANKS.blockId().length() + PLANKS.itemId().length());
        assertEquals(expected, definition.estimatedBytes());
        var restored = new BlueprintDefinition(ID, 1, definition.digest(), definition.blocks(), definition.markers());
        assertEquals(definition, restored);
        assertEquals(definition.bounds(), restored.bounds());
        assertEquals(expected, restored.estimatedBytes());
    }
}
