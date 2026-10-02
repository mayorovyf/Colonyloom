package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder("colonyloom")
@PrefixGameTestTemplate(false)
public final class IdentityGameTests {
    @GameTest(template="identity_empty",timeoutTicks=100)
    public static void unidentifiedAndDuplicateEmbodimentsKeepRealInventory(GameTestHelper helper) {
        CitizenEntity original=create(helper);
        UUID citizenId=UUID.randomUUID();
        original.initializeIdentity(citizenId,1);
        BlockPos position=helper.absolutePos(new BlockPos(1,1,1));
        original.moveTo(position.getX()+0.5,position.getY(),position.getZ()+0.5,0,0);
        original.inventory().setItem(0,new ItemStack(Items.OAK_STAIRS,4));
        helper.getLevel().addFreshEntity(original);
        helper.assertTrue(original.isQuarantined(),"Entity without authoritative citizen record entered economy");
        CompoundTag saved=new CompoundTag();
        original.save(saved);
        original.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        CitizenEntity restored=create(helper);
        restored.load(saved);
        helper.getLevel().addFreshEntity(restored);
        CitizenEntity duplicate=create(helper);
        duplicate.initializeIdentity(citizenId,1);
        duplicate.moveTo(position.getX()+2.5,position.getY(),position.getZ()+0.5,0,0);
        duplicate.inventory().setItem(0,new ItemStack(Items.BREAD,3));
        helper.getLevel().addFreshEntity(duplicate);
        helper.assertTrue(restored.getUUID().equals(original.getUUID()),"Entity UUID changed after physical save/load");
        helper.assertTrue(citizenId.equals(restored.citizenId())&&restored.bindingEpoch()==1,"Citizen identity changed after save/load");
        helper.assertTrue(restored.isQuarantined()&&duplicate.isQuarantined(),"Unrecorded duplicate became available");
        helper.assertTrue(restored.inventory().getItem(0).is(Items.OAK_STAIRS)&&restored.inventory().getItem(0).getCount()==4,"Quarantine deleted original property");
        helper.assertTrue(duplicate.inventory().getItem(0).is(Items.BREAD)&&duplicate.inventory().getItem(0).getCount()==3,"Quarantine deleted duplicate property");
        helper.succeed();
    }

    private static CitizenEntity create(GameTestHelper helper) {
        EntityType<?> type=BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen"));
        return (CitizenEntity) type.create(helper.getLevel());
    }
}
