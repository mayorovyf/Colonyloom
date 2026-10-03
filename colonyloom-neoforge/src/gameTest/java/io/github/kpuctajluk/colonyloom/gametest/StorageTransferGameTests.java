package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.action.EffectRecord;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey;
import io.github.kpuctajluk.colonyloom.core.colony.*;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.storage.*;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonySavedData;
import io.github.kpuctajluk.colonyloom.minecraft.storage.*;
import io.github.kpuctajluk.colonyloom.neoforge.NeoForgeChunkAccess;
import io.github.kpuctajluk.colonyloom.neoforge.NeoForgeStorageIdentity;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("colonyloom")
@PrefixGameTestTemplate(false)
public final class StorageTransferGameTests {
    @GameTest(template="identity_empty",timeoutTicks=240)
    public static void realChestCourierBarrelConservesOneExactStack(GameTestHelper helper) {
        withReadyFixture(helper,f -> {
            ItemStack named=named("cargo",32); f.chest.setItem(0,named); f.chest.setItem(1,new ItemStack(Items.OAK_PLANKS,17));
            var item=f.describe(named); int[] calls={0};
            var picked=f.executor(null,null).transfer(f.context(f.chestSlot),1,f.chestSlot,f.courierSlot,item,12,moved -> {
                calls[0]++; helper.assertTrue(moved==12,"Pickup did not report actual portion");
                helper.assertTrue(f.registry.effects().snapshots().getLast().state()==EffectRecord.State.OBSERVED,"Notification preceded native fact");
                helper.assertTrue(f.registry.storage().index().observation(f.courierSlot).count()==12,"Destination not observed before notification");
            });
            helper.assertTrue(picked.moved()==12 && !picked.ambiguous() && calls[0]==1,"Native pickup failed "+picked);
            helper.assertTrue(f.chest.getItem(0).getCount()==20 && f.courier.inventory().getItem(0).getCount()==12 && f.chest.getItem(1).getCount()==17,"Single-stack pickup created/lost cargo or touched second stack");
            var deposited=f.executor(null,null).transfer(f.context(f.barrelSlot),1,f.courierSlot,f.barrelSlot,item,12,moved -> calls[0]++);
            helper.assertTrue(deposited.moved()==12 && !deposited.ambiguous() && calls[0]==2,"Native deposit failed "+deposited);
            helper.assertTrue(f.courier.inventory().getItem(0).isEmpty() && f.barrel.getItem(0).getCount()==12 && f.chest.getItem(0).getCount()==20,"Chest→courier→barrel did not conserve 32");
            helper.assertTrue(NativeItemDescriptor.matches(f.barrel.getItem(0),item,helper.getLevel().registryAccess()),"Native components changed during custody transfer");
            var evidence=f.registry.effects().snapshots().getLast().transfer();
            helper.assertTrue(evidence.source().equals(f.courierSlot) && evidence.destination().equals(f.barrelSlot) && evidence.item().equals(item)
                    && evidence.extracted()==12 && evidence.inserted()==12 && evidence.returned()==0 && evidence.sourceAfter()==0 && evidence.destinationAfter()==12,"Physical evidence omitted canonical components/deltas");
        });
    }
    @GameTest(template="identity_empty",timeoutTicks=240)
    public static void partialCapacityMovesOnlyActualNativeRoom(GameTestHelper helper) {
        withReadyFixture(helper,f -> {
            f.courier.inventory().setItem(0,named("same",20)); f.barrel.setItem(0,named("same",60));
            var item=f.describe(f.courier.inventory().getItem(0)); int[] moved={0};
            helper.assertTrue(f.storage.capacity(f.barrelSlot,item)==4,"Native per-slot capacity ignored current count");
            var result=f.executor(null,null).transfer(f.context(f.barrelSlot),1,f.courierSlot,f.barrelSlot,item,20,actual -> moved[0]=actual);
            helper.assertTrue(result.moved()==4 && moved[0]==4 && !result.ambiguous(),"Partial capacity was not actual moved "+result);
            helper.assertTrue(f.barrel.getItem(0).getCount()==64 && f.courier.inventory().getItem(0).getCount()==16,"Partial transfer did not conserve 80");
            helper.assertTrue(f.registry.effects().snapshots().getFirst().transfer().maximum()==4,"Evidence did not retain agreed native portion");
        });
    }
    @GameTest(template="identity_empty",timeoutTicks=240)
    public static void incompatibleFullUnknownAndSameSlotNeverExtract(GameTestHelper helper) {
        withReadyFixture(helper,f -> {
            f.courier.inventory().setItem(0,named("first",11)); var item=f.describe(f.courier.inventory().getItem(0));
            f.barrel.setItem(0,named("second",1)); int[] calls={0}; var executor=f.executor(null,null);
            var incompatible=executor.transfer(f.context(f.barrelSlot),1,f.courierSlot,f.barrelSlot,item,11,actual -> calls[0]++);
            helper.assertTrue(incompatible.reason()==WorkOrder.Reason.CAPACITY && incompatible.moved()==0,"Components mixed "+incompatible);
            f.barrel.setItem(0,named("first",64));
            var full=executor.transfer(f.context(f.barrelSlot),1,f.courierSlot,f.barrelSlot,item,11,actual -> calls[0]++);
            helper.assertTrue(full.reason()==WorkOrder.Reason.CAPACITY && f.courier.inventory().getItem(0).getCount()==11,"Full destination extracted property");
            var same=executor.transfer(f.context(f.courierSlot),1,f.courierSlot,f.courierSlot,item,11,actual -> calls[0]++);
            helper.assertTrue(same.reason()==WorkOrder.Reason.TARGET_CONFLICT && f.courier.inventory().getItem(0).getCount()==11,"Same canonical slot duplicated/spent property");
            helper.getLevel().setBlockAndUpdate(f.barrelPos,Blocks.AIR.defaultBlockState()); helper.getLevel().setBlockAndUpdate(f.barrelPos,Blocks.BARREL.defaultBlockState());
            var unknown=executor.transfer(f.context(f.barrelSlot),1,f.courierSlot,f.barrelSlot,item,11,actual -> calls[0]++);
            helper.assertTrue(unknown.reason()==WorkOrder.Reason.CHUNK_NOT_READY && f.courier.inventory().getItem(0).getCount()==11,"Replacement native identity extracted property");
            var far=f.origin.offset(16000,0,16000); var dimension=f.position(far).dimension();
            var unknownStorage=new StorageId(dimension,UUID.randomUUID(),0); var unknownSlot=new StockRegion(unknownStorage,0);
            UUID remoteColony=UUID.randomUUID();
            f.registry.addColony(new ColonyRuntime(remoteColony,"Unloaded stock",new Territory(dimension,far.getX()-8,far.getZ()-8,far.getX()+8,far.getZ()+8),f.owner,Map.of(),1,1,false,null,false));
            f.registry.storage().register(remoteColony,f.position(far),"return",List.of(unknownStorage),List.of(unknownSlot),List.of(f.position(far)));
            var level=helper.getLevel(); helper.assertTrue(level.getChunkSource().getChunkNow(far.getX()>>4,far.getZ()>>4)==null,"Unknown fixture chunk unexpectedly loaded");
            helper.assertTrue(f.storage.currentContainer(unknownSlot)==null && f.storage.capacity(unknownSlot,item)==-1,"Unknown native chunk fabricated a container/capacity");
            helper.assertTrue(level.getChunkSource().getChunkNow(far.getX()>>4,far.getZ()>>4)==null,"Nonloading helpers loaded an unknown chunk");
            helper.assertTrue(calls[0]==0 && f.registry.effects().size()==0,"Denied native effects notified/admitted evidence");
        });
    }
    @GameTest(template="identity_empty",timeoutTicks=240)
    public static void revokedIndividualAndCurrentOwnerVetoLeaveNativeItems(GameTestHelper helper) {
        withReadyFixture(helper,f -> {
            f.chest.setItem(0,new ItemStack(Items.OAK_PLANKS,18)); var item=f.describe(f.chest.getItem(0)); UUID manager=UUID.randomUUID(), nextOwner=UUID.randomUUID();
            f.updateOwner(f.owner,Map.of(manager,MemberRank.MANAGER),2); var old=f.context(f.chestSlot,ActionContext.AuthorityMode.INDIVIDUAL,manager);
            f.updateOwner(nextOwner,Map.of(),3); int[] calls={0};
            var denied=f.executor(null,null).transfer(old,1,f.chestSlot,f.courierSlot,item,6,actual -> calls[0]++);
            helper.assertTrue(denied.reason()==WorkOrder.Reason.PERMISSION_DENIED,"Revoked individual retained old authority "+denied);
            UUID[] principal={null};
            var veto=f.executor((context,who,source,destination,amount) -> {principal[0]=who; return false;},null)
                    .transfer(f.context(f.chestSlot),1,f.chestSlot,f.courierSlot,item,6,actual -> calls[0]++);
            helper.assertTrue(veto.reason()==WorkOrder.Reason.PERMISSION_DENIED && nextOwner.equals(principal[0]),"Protection veto didn't use current colony owner "+veto);
            var revokedAtLastGuard=f.executor(null,(point,context) -> { if(point==StorageTransferExecutor.FaultPoint.BEFORE_EFFECT) f.updateOwner(f.owner,Map.of(),4); })
                    .transfer(f.context(f.chestSlot),1,f.chestSlot,f.courierSlot,item,6,actual -> calls[0]++);
            helper.assertTrue(revokedAtLastGuard.reason()==WorkOrder.Reason.PERMISSION_DENIED && f.chest.getItem(0).getCount()==18
                    && f.courier.inventory().isEmpty() && calls[0]==0 && f.registry.effects().size()==0,"Final revoked authority mutated property/evidence");
        });
    }
    @GameTest(template="identity_empty",timeoutTicks=240)
    public static void exactComponentsRecheckedAfterVetoAndFactBeforeCallback(GameTestHelper helper) {
        withReadyFixture(helper,f -> {
            f.chest.setItem(0,named("expected",24)); var item=f.describe(f.chest.getItem(0)); int[] calls={0};
            var changing=f.executor((context,who,source,destination,amount) -> { f.chest.getItem(0).set(DataComponents.CUSTOM_NAME,Component.literal("changed")); return true; },null);
            var result=changing.transfer(f.context(f.chestSlot),1,f.chestSlot,f.courierSlot,item,8,actual -> calls[0]++);
            helper.assertTrue(result.reason()==WorkOrder.Reason.TARGET_CONFLICT && f.chest.getItem(0).getCount()==24 && f.courier.inventory().isEmpty() && calls[0]==0,"Final guard trusted descriptor before veto");
            f.chest.setItem(0,named("expected",24));
            var failed=f.executor(null,(point,context) -> { if(point==StorageTransferExecutor.FaultPoint.AFTER_FACT_BEFORE_NOTIFY) throw new IllegalStateException("fixture fact fault"); })
                    .transfer(f.context(f.chestSlot),1,f.chestSlot,f.courierSlot,item,8,actual -> calls[0]++);
            helper.assertTrue(failed.ambiguous() && f.registry.colony(f.colony).recoveryBlocked() && calls[0]==0,"Post-fact failure replayed/not blocked");
            helper.assertTrue(f.chest.getItem(0).getCount()==16 && f.courier.inventory().getItem(0).getCount()==8,"Post-fact failure compensated real transfer");
            helper.assertTrue(f.registry.effects().snapshots().getLast().state()==EffectRecord.State.AMBIGUOUS,"Post-fact native witness not ambiguous");
            helper.assertTrue(f.storage.currentContainer(f.courierSlot)==null && f.storage.recoveryContainer(f.courierSlot)==f.courier.inventory(),"Read-only recovery inventory unavailable or unblocked colony");
        });
    }
    @GameTest(template="identity_empty",timeoutTicks=240)
    public static void failedCommitNeverCompensatesOrRepeatsNativeCargo(GameTestHelper helper) {
        withReadyFixture(helper,f -> {
            f.courier.inventory().setItem(0,new ItemStack(Items.OAK_PLANKS,15)); var item=f.describe(f.courier.inventory().getItem(0)); int[] calls={0};
            var result=f.executor(null,null).transfer(f.context(f.barrelSlot),1,f.courierSlot,f.barrelSlot,item,9,actual -> {calls[0]++; throw new IllegalStateException("fixture publication fault");});
            helper.assertTrue(result.ambiguous() && result.moved()==9 && calls[0]==1 && f.courier.inventory().getItem(0).getCount()==6 && f.barrel.getItem(0).getCount()==9,"Callback failure compensated or misreported native fact");
            var retry=f.executor(null,null).transfer(f.context(f.barrelSlot),1,f.courierSlot,f.barrelSlot,item,9,actual -> calls[0]++);
            helper.assertTrue(retry.moved()==0 && retry.reason()==WorkOrder.Reason.RECOVERY_AMBIGUOUS && calls[0]==1
                    && f.courier.inventory().getItem(0).getCount()==6 && f.barrel.getItem(0).getCount()==9,"Blocked callback failure replayed cargo");
        });
    }
    @GameTest(template="identity_empty",timeoutTicks=240)
    public static void insertionRemainderRetainsActualCourierStackWithoutZeroCommit(GameTestHelper helper) {
        withReadyFixture(helper,f -> {
            f.courier.inventory().setItem(0,named("cargo",20)); var item=f.describe(f.courier.inventory().getItem(0)); int[] calls={0};
            var result=f.executor(null,(point,context) -> { if(point==StorageTransferExecutor.FaultPoint.AFTER_SOURCE_CHANGE) f.barrel.setItem(0,named("cargo",64)); })
                    .transfer(f.context(f.barrelSlot),1,f.courierSlot,f.barrelSlot,item,20,actual -> calls[0]++);
            helper.assertTrue(result.ambiguous() && f.courier.inventory().getItem(0).getCount()==20 && f.barrel.getItem(0).getCount()==64 && calls[0]==0,"Changed destination lost actual courier remainder or committed unrelated items");
            var fact=f.registry.effects().snapshots().getFirst().transfer(); helper.assertTrue(fact.extracted()==20 && fact.returned()==20,"Evidence lost extraction/remainder custody");
        });
    }
    @GameTest(template="identity_empty",timeoutTicks=240)
    public static void sourceObligationSurvivesUntilDestinationNotification(GameTestHelper helper) {
        withReadyFixture(helper,f -> {
            f.chest.setItem(0,new ItemStack(Items.OAK_PLANKS,16)); var item=f.describe(f.chest.getItem(0)); var stock=f.registry.storage();
            stock.index().observe(f.chestSlot,item,16,0); UUID claim=UUID.randomUUID();
            stock.reservations().reserve(claim,f.colony,UUID.randomUUID(),f.chestSlot,item,10,0,Lane.NORMAL);
            var result=f.executor(null,null).transfer(f.context(f.chestSlot),1,f.chestSlot,f.courierSlot,item,10,actual -> {
                helper.assertTrue(stock.reservations().get(claim)!=null && stock.reservations().get(claim).count()==10,"Source loss reconciled before obligation relocation");
                helper.assertTrue(stock.index().observation(f.courierSlot).count()==10 && stock.index().observation(f.chestSlot).count()==16,"Native observation order changed");
                stock.reservations().release(claim);
            });
            helper.assertTrue(result.moved()==10 && stock.index().observation(f.chestSlot).count()==6,"Source not observed after logical notification");
        });
    }
    @GameTest(template="identity_empty",timeoutTicks=240)
    public static void transferEvidenceRoundTripsAndUnknownSchemaRemainsOpaque(GameTestHelper helper) {
        withReadyFixture(helper,f -> {
            f.chest.setItem(0,named("persistent",19)); var item=f.describe(f.chest.getItem(0));
            f.executor(null,null).transfer(f.context(f.chestSlot),1,f.chestSlot,f.courierSlot,item,7,actual -> {});
            CompoundTag encoded=ColonySavedData.empty(f.registry.snapshot()).save(new CompoundTag(),helper.getLevel().registryAccess());
            var loaded=ColonySavedData.load(encoded,helper.getLevel().registryAccess());
            helper.assertTrue(loaded.snapshot().effects().equals(f.registry.effects().snapshots()),"Exact transfer components/canonical sides/counters did not roundtrip");
            CompoundTag unknown=encoded.copy(), evidence=null;
            for(var value:unknown.getList("evidence",Tag.TAG_COMPOUND)) { var entry=(CompoundTag)value; if(entry.getString("kind").equals("STORAGE_TRANSFER")) evidence=entry; }
            if(evidence==null) throw new AssertionError("Missing transfer evidence"); evidence.getCompound("transfer").putInt("schemaVersion",99); evidence.getCompound("transfer").putString("futureField","preserved");
            var opaque=ColonySavedData.load(unknown,helper.getLevel().registryAccess());
            helper.assertTrue(opaque.snapshot().effects().isEmpty() && opaque.contentBlockedColonies().contains(f.colony),"Unknown native evidence schema silently interpreted");
            CompoundTag saved=opaque.save(new CompoundTag(),helper.getLevel().registryAccess());
            helper.assertTrue(saved.getList("evidence",Tag.TAG_COMPOUND).contains(evidence),"Unknown native evidence schema rewritten/lost");
        });
    }
    private static ItemStack named(String name,int count) { ItemStack stack=new ItemStack(Items.OAK_PLANKS,count); stack.set(DataComponents.CUSTOM_NAME,Component.literal(name)); return stack; }
    private static void withReadyFixture(GameTestHelper helper,Consumer<Fixture> check) {
        BlockPos origin=helper.absolutePos(new BlockPos(1,1,1)); var access=new NeoForgeChunkAccess(helper.getLevel().getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime")));
        UUID owner=UUID.randomUUID(); List<ChunkKey> keys=new ArrayList<>();
        for(int x=(origin.getX()-4)>>4;x<=(origin.getX()+5)>>4;x++) for(int z=(origin.getZ()-4)>>4;z<=(origin.getZ()+5)>>4;z++) {
            var key=new ChunkKey(helper.getLevel().dimension().location().toString(),x,z); keys.add(key);
            if(!access.acquire(owner,key,ChunkDemandManager.Readiness.ENTITY_TICKING)) throw new IllegalStateException("Native transfer fixture ticket denied");
        }
        boolean[] done={false}; helper.onEachTick(() -> {
            if(done[0] || !keys.stream().allMatch(key -> access.ready(key,ChunkDemandManager.Readiness.ENTITY_TICKING))) return; done[0]=true;
            try(var fixture=new Fixture(helper,origin)) { check.accept(fixture); }
            finally { for(var key:keys) access.release(owner,key,ChunkDemandManager.Readiness.ENTITY_TICKING); }
            helper.succeed();
        });
    }
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper; final BlockPos origin,chestPos,barrelPos; final ColonyRegistry registry; final UUID colony=UUID.randomUUID(),citizen=UUID.randomUUID(),owner=UUID.randomUUID(),checkpoint=UUID.randomUUID();
        final CitizenEntity courier; final Container chest,barrel; final StorageService storage; final StockRegion chestSlot,courierSlot,barrelSlot;
        Fixture(GameTestHelper helper,BlockPos origin) {
            this.helper=helper; this.origin=origin; chestPos=origin; barrelPos=origin.east(2);
            registry=new ColonyRegistry(() -> {if(!helper.getLevel().getServer().isSameThread())throw new IllegalStateException("Native fixture owner");}); registry.budgets().beginTick(0);
            registry.addColony(new ColonyRuntime(colony,"Native transfer",new Territory(position(origin).dimension(),origin.getX()-8,origin.getZ()-8,origin.getX()+8,origin.getZ()+8),owner,Map.of(),1,1,false,null,false));
            helper.getLevel().setBlockAndUpdate(chestPos.below(),Blocks.STONE.defaultBlockState()); helper.getLevel().setBlockAndUpdate(barrelPos.below(),Blocks.STONE.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(chestPos,Blocks.CHEST.defaultBlockState()); helper.getLevel().setBlockAndUpdate(barrelPos,Blocks.BARREL.defaultBlockState());
            chest=(Container)helper.getLevel().getBlockEntity(chestPos); barrel=(Container)helper.getLevel().getBlockEntity(barrelPos);
            courier=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(helper.getLevel()); if(courier==null)throw new IllegalStateException("Citizen type unavailable");
            courier.initializeIdentity(citizen,1); courier.moveTo(origin.getX()+1.5,origin.getY(),origin.getZ()+1.5,0,0);
            registry.addCitizen(new CitizenRecord(citizen,colony,courier.getUUID(),1,null,null,null,"colonyloom:builder",Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.READY,0,Map.of("food",1200L),position(courier.blockPosition()),1),record -> {if(!helper.getLevel().addFreshEntity(courier))throw new IllegalStateException("Courier spawn denied");});
            registry.bindings().observe(citizen,courier.getUUID(),1); courier.setQuarantined(false);
            storage=new StorageService(helper.getLevel().getServer(),registry,registry.budgets(),new NeoForgeStorageIdentity());
            chestSlot=storage.register(colony,position(chestPos),"warehouse").slots().getFirst(); barrelSlot=storage.register(colony,position(barrelPos),"return").slots().getFirst(); courierSlot=storage.registerCitizen(colony,citizen,"construction").slots().getFirst();
        }
        ItemDescriptor describe(ItemStack stack) {return NativeItemDescriptor.describe(stack,helper.getLevel().registryAccess());}
        WorldPosition position(BlockPos position) {return new WorldPosition(helper.getLevel().dimension().location().toString(),position.getX(),position.getY(),position.getZ());}
        ActionContext context(StockRegion block) {return context(block,ActionContext.AuthorityMode.COLONY,null);}
        ActionContext context(StockRegion block,ActionContext.AuthorityMode mode,UUID initiator) {var locator=storage.locate(block.storage()); return new ActionContext(colony,citizen,ActionContext.Kind.STORAGE_TRANSFER,locator==null?position(barrelPos):locator,mode,initiator,registry.colony(colony).authorityRevision());}
        StorageTransferExecutor executor(StorageTransferExecutor.Protection protection,StorageTransferExecutor.FaultObserver observer) {return new StorageTransferExecutor(helper.getLevel().getServer(),registry,storage,protection,() -> checkpoint,observer);}
        void updateOwner(UUID owner,Map<UUID,MemberRank> members,long revision) {var old=registry.colony(colony); registry.updateColony(new ColonyRuntime(colony,old.name(),old.territory(),owner,members,revision,revision,false,null,false));}
        @Override public void close() {courier.remove(Entity.RemovalReason.DISCARDED);}
    }
}
