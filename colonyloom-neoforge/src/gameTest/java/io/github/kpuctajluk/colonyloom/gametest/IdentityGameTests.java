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
    private static final java.util.Map<net.minecraft.server.MinecraftServer,io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime> runtimes=new java.util.IdentityHashMap<>();
    static void runtimeBound(io.github.kpuctajluk.colonyloom.neoforge.ConstructionExecutorEvent event) {
        runtimes.put(event.server(),event.runtime());
    }
    static void runtimeStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        runtimes.remove(event.getServer());
    }

    @GameTest(template="identity_empty")
    public static void opaqueEmbodimentLoadUnloadPreservesHistoryAndProperty(GameTestHelper helper) throws Exception {
        var runtime=runtimes.get(helper.getLevel().getServer());
        var persistence=runtime.persistence();
        var field=persistence.getClass().getDeclaredField("data");field.setAccessible(true);
        var previous=field.get(persistence);
        var rootMethod=PersistenceGameTests.class.getDeclaredMethod("fixtureRoot",GameTestHelper.class);rootMethod.setAccessible(true);
        var root=(CompoundTag)rootMethod.invoke(null,helper);
        var opaque=root.getList("citizens",net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0);
        opaque.putString("typeId","future:citizen");
        var retained=io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonySavedData.load(root,helper.getLevel().registryAccess());
        var before=retained.save(new CompoundTag(),helper.getLevel().registryAccess());
        var bindings=runtime.core().bindings().observations();
        var npc=create(helper);npc.initializeIdentity(opaque.getUUID("citizenId"),opaque.getLong("bindingEpoch"));
        npc.inventory().setItem(0,new ItemStack(Items.BREAD,3));
        var pos=helper.absolutePos(new BlockPos(2,1,2));npc.moveTo(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5,0,0);
        try {
            // Swap only the decoder result synchronously; never write or replace the server checkpoint.
            field.set(persistence,retained);
            helper.assertTrue(helper.getLevel().addFreshEntity(npc),"Opaque native entity was not admitted to the physical world");
            helper.assertTrue(npc.isQuarantined(),"Opaque native identity entered the economy");
            npc.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            helper.assertTrue(runtime.core().bindings().observations().equals(bindings),"Opaque load/unload republished live binding authority");
            helper.assertTrue(before.equals(retained.save(new CompoundTag(),helper.getLevel().registryAccess())),"Opaque unload captured unrelated authority or lost retained history");
            helper.assertTrue(npc.inventory().getItem(0).is(Items.BREAD)&&npc.inventory().getItem(0).getCount()==3,"Opaque quarantine removed cargo");
        } finally {
            if(!npc.isRemoved())npc.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            field.set(persistence,previous);
        }
        helper.succeed();
    }
    @GameTest(template="identity_empty")
    public static void lateDeathCancellationDoesNotCommitIdentityOrRemoveCargo(GameTestHelper helper) {
        var npc=create(helper);npc.initializeIdentity(UUID.randomUUID(),1);npc.inventory().setItem(0,new ItemStack(Items.BREAD,3));
        var pos=helper.absolutePos(new BlockPos(2,1,2));npc.moveTo(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5,0,0);helper.getLevel().addFreshEntity(npc);
        int[] commits={0},facts={0};npc.deathPreparation(() -> commits[0]++);npc.observeDeathInventory(items -> facts[0]++);
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingDeathEvent> veto=event -> {if(event.getEntity()==npc){event.getEntity().setHealth(1);event.setCanceled(true);}};
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,false,net.neoforged.neoforge.event.entity.living.LivingDeathEvent.class,veto);
        try {
            npc.hurt(npc.damageSources().genericKill(),Float.MAX_VALUE);
            helper.assertTrue(npc.isAlive()&&commits[0]==0&&facts[0]==0&&npc.inventory().getItem(0).getCount()==3,"Late cancelled native death committed authority or removed cargo");
        } finally {net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(veto);}
        npc.die(npc.damageSources().genericKill());
        helper.assertTrue(commits[0]==1&&facts[0]==1&&npc.inventory().isEmpty(),"Confirmed death did not commit exactly once after veto removed");
        npc.die(npc.damageSources().genericKill());helper.assertTrue(commits[0]==1&&facts[0]==1,"Native repeated die replayed finality");helper.succeed();
    }
    @GameTest(template="identity_empty")
    public static void cancelledDropsAreNotReportedAsPublishedDestination(GameTestHelper helper) {
        var npc=create(helper);npc.initializeIdentity(UUID.randomUUID(),1);npc.inventory().setItem(0,new ItemStack(Items.OAK_STAIRS,4));
        var pos=helper.absolutePos(new BlockPos(2,1,2));npc.moveTo(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5,0,0);helper.getLevel().addFreshEntity(npc);
        int[] commits={0},published={-1};npc.deathPreparation(() -> commits[0]++);npc.observeDeathInventory(items -> published[0]=items.stream().mapToInt(item -> item.getItem().getCount()).sum());
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingDropsEvent> veto=event -> {if(event.getEntity()==npc)event.setCanceled(true);};
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,false,net.neoforged.neoforge.event.entity.living.LivingDropsEvent.class,veto);
        try {npc.hurt(npc.damageSources().genericKill(),Float.MAX_VALUE);helper.assertTrue(!npc.isAlive()&&commits[0]==1&&published[0]==0&&npc.inventory().isEmpty(),"Cancelled captured drops became false conserved destination");}
        finally {net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(veto);}
        helper.succeed();
    }
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
