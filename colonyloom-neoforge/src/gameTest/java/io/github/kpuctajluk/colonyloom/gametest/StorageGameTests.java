package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.minecraft.storage.NativeItemDescriptor;
import io.github.kpuctajluk.colonyloom.neoforge.NeoForgeStorageIdentity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("colonyloom")
@PrefixGameTestTemplate(false)
public final class StorageGameTests {
    @GameTest(template="identity_empty")
    public static void nativeComponentsSeparateNamesAndNormalizeCompoundKeys(GameTestHelper helper) {
        ItemStack first=new ItemStack(Items.OAK_PLANKS,64),second=new ItemStack(Items.OAK_PLANKS,64);
        first.set(DataComponents.CUSTOM_NAME,Component.literal("first"));second.set(DataComponents.CUSTOM_NAME,Component.literal("second"));
        var registries=helper.getLevel().registryAccess();var a=NativeItemDescriptor.describe(first,registries);var b=NativeItemDescriptor.describe(second,registries);
        helper.assertTrue(!a.equals(b) && NativeItemDescriptor.matches(first,a,registries) && !NativeItemDescriptor.matches(second,a,registries),"Different native components mixed");
        CompoundTag forward=new CompoundTag(),reverse=new CompoundTag();forward.putInt("a",1);forward.putString("z","value");reverse.putString("z","value");reverse.putInt("a",1);
        first.remove(DataComponents.CUSTOM_NAME);second.remove(DataComponents.CUSTOM_NAME);
        first.set(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(forward));second.set(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(reverse));
        helper.assertTrue(NativeItemDescriptor.describe(first,registries).equals(NativeItemDescriptor.describe(second,registries)),"Compound key insertion order changed stock identity");
        second.setCount(1);helper.assertTrue(NativeItemDescriptor.describe(first,registries).equals(NativeItemDescriptor.describe(second,registries)),"Stack amount changed component identity");
        helper.succeed();
    }
    @GameTest(template="identity_empty")
    public static void supportedVanillaCapabilitiesPreserveActualSlotMapping(GameTestHelper helper) {
        var level=helper.getLevel();var bridge=new NeoForgeStorageIdentity();BlockPos relative=new BlockPos(1,2,1);
        for(var block:List.of(Blocks.CHEST,Blocks.TRAPPED_CHEST,Blocks.BARREL)) {
            helper.setBlock(relative,block);var entity=level.getBlockEntity(helper.absolutePos(relative));var container=(Container)entity;
            container.setItem(3,new ItemStack(Items.OAK_PLANKS,19));
            helper.assertTrue(bridge.supported(level,List.of(entity)),"Supported vanilla capability mapping rejected "+block);
            var identity=bridge.getOrCreate(entity);helper.assertTrue(identity.equals(bridge.existing(entity)),"Assigned identity not retained on block entity");
            var encoded=entity.saveWithFullMetadata(level.registryAccess());
            helper.assertTrue(encoded.getCompound("neoforge:attachments").contains("colonyloom:storage_identity"),"Assigned UUID not serialized in attachment");
            helper.setBlock(relative,Blocks.AIR);
        }
        helper.succeed();
    }
    @GameTest(template="identity_empty")
    public static void combinedChestCapabilityHasBothCanonicalHalves(GameTestHelper helper) {
        var level=helper.getLevel();var left=new BlockPos(1,2,1);var right=left.east();
        helper.setBlock(left,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH).setValue(ChestBlock.TYPE,ChestType.LEFT));
        helper.setBlock(right,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH).setValue(ChestBlock.TYPE,ChestType.RIGHT));
        var one=level.getBlockEntity(helper.absolutePos(left));var two=level.getBlockEntity(helper.absolutePos(right));
        ((Container)one).setItem(0,new ItemStack(Items.OAK_PLANKS,64));((Container)two).setItem(0,new ItemStack(Items.COBBLESTONE,7));
        var bridge=new NeoForgeStorageIdentity();helper.assertTrue(bridge.supported(level,List.of(one,two)),"Combined native chest handler lost half mapping");
        helper.assertTrue(!bridge.getOrCreate(one).equals(bridge.getOrCreate(two)),"Double chest halves have one UUID");
        helper.succeed();
    }
}
