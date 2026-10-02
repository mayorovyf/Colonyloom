package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.core.content.BlockDescriptor;
import io.github.kpuctajluk.colonyloom.core.content.BlockOffset;
import io.github.kpuctajluk.colonyloom.minecraft.content.ContentLoader;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("colonyloom")
@PrefixGameTestTemplate(false)
public final class ContentGameTests {
    @GameTest(template = "identity_empty")
    public static void invalidRegistryPropertiesAndMaterialsNeverBecomeBuildTargets(GameTestHelper helper) {
        reject(helper, () -> ContentLoader.decodeBlockState(stairs(Map.of("facing", "up", "half", "bottom", "shape", "straight", "waterlogged", "false"))), "Invalid facing accepted");
        reject(helper, () -> ContentLoader.decodeBlockState(stairs(Map.of("unknown", "north", "half", "bottom", "shape", "straight", "waterlogged", "false"))), "Unknown property accepted");
        reject(helper, () -> ContentLoader.decodeBlockState(stairs(Map.of("facing", "north"))), "Incomplete pinned state accepted");
        reject(helper, () -> ContentLoader.decodeBlockState(stairs(Map.of("facing", "north", "half", "bottom", "shape", "straight", "waterlogged", "true"))), "Waterlogged build state accepted");
        reject(helper, () -> ContentLoader.decodeBlockState(new BlockDescriptor("minecraft:oak_stairs", validProperties(), "minecraft:oak_planks")), "Wrong placement material accepted");
        reject(helper, () -> ContentLoader.decodeBlockState(new BlockDescriptor("minecraft:no_such_block", Map.of(), "minecraft:oak_stairs")), "Unknown registry block accepted");
        reject(helper, () -> ContentLoader.decodeBlockState(new BlockDescriptor("minecraft:chest", Map.of(), "minecraft:chest")), "Unsupported container accepted");
        helper.succeed();
    }

    @GameTest(template = "identity_empty")
    public static void duplicateCoordinatesAndHiddenEntityDataCannotBecomePinnedSites(GameTestHelper helper) {
        CompoundTag duplicate = template();
        ListTag blocks = duplicate.getList("blocks", 10);
        blocks.add(blocks.getCompound(0).copy());
        reject(helper, () -> decode(duplicate), "Duplicate structure coordinate accepted");
        CompoundTag entityData = template();
        ListTag malformedEntities = new ListTag();
        malformedEntities.add(IntTag.valueOf(1));
        entityData.put("entities", malformedEntities);
        reject(helper, () -> decode(entityData), "Wrong-typed nonempty entity payload accepted");
        CompoundTag blockData = template();
        blockData.getList("blocks", 10).getCompound(0).put("nbt", new CompoundTag());
        reject(helper, () -> decode(blockData), "Block entity payload accepted");
        CompoundTag paletteData = template();
        paletteData.getList("palette", 10).getCompound(0).getCompound("Properties").putString("facing", "invalid");
        reject(helper, () -> decode(paletteData), "Invalid palette property silently defaulted");
        helper.succeed();
    }

    private static void decode(CompoundTag template) {
        ContentLoader.decodeTemplate("colonyloom:test_invalid", 1, template, Map.of("work_origin", new BlockOffset(0, 0, 1), "delivery_buffer", new BlockOffset(-1, 0, 0)));
    }

    private static BlockDescriptor stairs(Map<String, String> properties) {
        return new BlockDescriptor("minecraft:oak_stairs", properties, "minecraft:oak_stairs");
    }

    private static Map<String, String> validProperties() {
        return Map.of("facing", "north", "half", "bottom", "shape", "straight", "waterlogged", "false");
    }

    private static CompoundTag template() {
        CompoundTag root = new CompoundTag();
        root.put("size", coordinates(1, 1, 1));
        CompoundTag state = new CompoundTag();
        state.putString("Name", "minecraft:oak_stairs");
        CompoundTag properties = new CompoundTag();
        validProperties().forEach(properties::putString);
        state.put("Properties", properties);
        ListTag palette = new ListTag();
        palette.add(state);
        root.put("palette", palette);
        CompoundTag block = new CompoundTag();
        block.put("pos", coordinates(0, 0, 0));
        block.putInt("state", 0);
        ListTag blocks = new ListTag();
        blocks.add(block);
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        return root;
    }

    private static ListTag coordinates(int x, int y, int z) {
        ListTag result = new ListTag();
        result.add(IntTag.valueOf(x)); result.add(IntTag.valueOf(y)); result.add(IntTag.valueOf(z));
        return result;
    }

    private static void reject(GameTestHelper helper, Runnable operation, String message) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        helper.fail(message);
    }
}
