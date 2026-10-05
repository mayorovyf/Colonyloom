package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("colonyloom")
@PrefixGameTestTemplate(false)
public final class DeathGameTests {
    @GameTest(template="identity_empty",batch="stage05_death",timeoutTicks=80)
    public static void actualCitizenPropertyDropsWhenMobLootDisabled(GameTestHelper helper) {
        var level=helper.getLevel(); var entity=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
        if(entity==null) throw new IllegalStateException("Citizen factory unavailable");
        var pos=helper.absolutePos(new BlockPos(1,1,1)); entity.initializeIdentity(UUID.randomUUID(),1); entity.moveTo(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5,0,0);
        entity.inventory().setItem(0,new ItemStack(Items.OAK_STAIRS,4)); entity.inventory().setItem(8,new ItemStack(Items.BREAD,3));
        if(!level.addFreshEntity(entity)) throw new IllegalStateException("Death fixture spawn refused");
        boolean[] observed={false}; entity.observeDeathInventory(drops -> observed[0]=drops.size()==2 && drops.stream().allMatch(drop -> level.getEntity(drop.getUUID())==drop));
        boolean old=level.getGameRules().getBoolean(GameRules.RULE_DOMOBLOOT);
        try {
            level.getGameRules().getRule(GameRules.RULE_DOMOBLOOT).set(false,level.getServer());
            entity.hurt(level.damageSources().genericKill(),1000);
        } finally { level.getGameRules().getRule(GameRules.RULE_DOMOBLOOT).set(old,level.getServer()); }
        helper.assertTrue(!entity.isAlive() && observed[0],"Real death inventory observer did not see both published native drops");
        int stairs=0,bread=0;
        for(var drop:level.getEntitiesOfClass(ItemEntity.class,entity.getBoundingBox().inflate(2))) {
            if(drop.getItem().is(Items.OAK_STAIRS)) stairs+=drop.getItem().getCount();
            if(drop.getItem().is(Items.BREAD)) bread+=drop.getItem().getCount();
        }
        helper.assertTrue(stairs==4 && bread==3,"Real death drops differ from existing NPC property");
        for(int slot=0;slot<CitizenEntity.INVENTORY_SIZE;slot++) helper.assertTrue(entity.inventory().getItem(slot).isEmpty(),"Death retained inventory source");
        entity.remove(Entity.RemovalReason.DISCARDED); helper.succeed();
    }
}
