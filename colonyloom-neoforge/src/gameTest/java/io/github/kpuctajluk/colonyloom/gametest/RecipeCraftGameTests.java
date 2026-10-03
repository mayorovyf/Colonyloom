package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.action.EffectRecord;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey;
import io.github.kpuctajluk.colonyloom.core.colony.*;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.storage.*;
import io.github.kpuctajluk.colonyloom.core.supply.ItemMatcher;
import io.github.kpuctajluk.colonyloom.core.supply.RecipeDefinition;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonySavedData;
import io.github.kpuctajluk.colonyloom.minecraft.production.RecipeExecutor;
import io.github.kpuctajluk.colonyloom.minecraft.storage.*;
import io.github.kpuctajluk.colonyloom.neoforge.NeoForgeChunkAccess;
import io.github.kpuctajluk.colonyloom.neoforge.NeoForgeRecipeProtection;
import io.github.kpuctajluk.colonyloom.neoforge.NeoForgeStorageIdentity;
import io.github.kpuctajluk.colonyloom.neoforge.RecipeCraftEvent;
import java.util.*;
import java.util.function.BooleanSupplier;
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
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("colonyloom")
@PrefixGameTestTemplate(false)
public final class RecipeCraftGameTests {
    @GameTest(template="identity_empty",timeoutTicks=240)
    public static void sixPhysicalPlanksBecomeFourPhysicalStairsAfterObservedFact(GameTestHelper helper) {
        withReadyFixture(helper,f -> {
            f.barrel.setItem(0,new ItemStack(Items.OAK_PLANKS,6)); var input=f.describe(f.barrel.getItem(0));
            var stock=f.registry.storage(); stock.index().observe(f.input,input,6,0);
            UUID allocation=UUID.randomUUID(); stock.allocations().allocate(allocation,f.colony,UUID.randomUUID(),f.input,input,6,0,Lane.NORMAL);
            int[] calls={0}; var result=f.craft(f.executor(null,null),input,() -> true,outputs -> {
                calls[0]++; var fact=f.registry.effects().snapshots().getLast();
                helper.assertTrue(fact.state()==EffectRecord.State.OBSERVED && fact.craft().phase()==EffectRecord.CraftPhase.FACT_OBSERVED
                        && fact.craft().complete(),"Callback preceded verified typed native fact");
                helper.assertTrue(f.barrel.getItem(0).isEmpty() && f.barrel.getItem(1).is(Items.OAK_STAIRS)
                        && f.barrel.getItem(1).getCount()==4,"Recipe result was virtual rather than physical");
                helper.assertTrue(stock.index().observation(f.output).count()==4 && stock.index().observation(f.input).count()==6
                        && stock.allocations().get(allocation).count()==6,"Source allocation reconciled before logical commit");
                stock.allocations().release(allocation);
            });
            helper.assertTrue(result.produced()==4 && result.reason()==WorkOrder.Reason.NONE && calls[0]==1
                    && stock.index().observation(f.input).count()==0,"Whole physical batch did not finish");
            var repeat=f.craft(f.executor(null,null),input,() -> true,outputs -> calls[0]++);
            helper.assertTrue(repeat.produced()==0 && calls[0]==1 && f.barrel.getItem(1).getCount()==4,"Retained batch evidence permitted replay");
        });
    }
    @GameTest(template="identity_empty",timeoutTicks=240)
    public static void wholeOutputCapacityRefusesAllIngredientExpense(GameTestHelper helper) {
        withReadyFixture(helper,f -> {
            f.barrel.setItem(0,namedPlanks("exact kit",6)); var item=f.describe(f.barrel.getItem(0));
            f.barrel.setItem(1,new ItemStack(Items.OAK_STAIRS,61)); int[] calls={0};
            var partial=f.craft(f.executor(null,null),item,() -> true,outputs -> calls[0]++);
            helper.assertTrue(partial.reason()==WorkOrder.Reason.CAPACITY && partial.produced()==0 && calls[0]==0
                    && f.barrel.getItem(0).getCount()==6 && f.describe(f.barrel.getItem(0)).equals(item)
                    && f.barrel.getItem(1).getCount()==61 && f.registry.effects().size()==0,"Partial native room spent ingredients");
            f.barrel.setItem(1,new ItemStack(Items.STONE,64));
            var full=f.craft(f.executor(null,null),item,() -> true,outputs -> calls[0]++);
            helper.assertTrue(full.reason()==WorkOrder.Reason.CAPACITY && f.barrel.getItem(0).getCount()==6 && calls[0]==0,"Incompatible full output expense");
            f.barrel.setItem(1,ItemStack.EMPTY);
            var admitted=f.craft(f.executor(null,null),item,() -> true,outputs -> calls[0]++);
            helper.assertTrue(admitted.produced()==4 && calls[0]==1 && f.barrel.getItem(1).getCount()==4,"Exact components kit failed real craft");
        });
    }
    @GameTest(template="identity_empty",timeoutTicks=240)
    public static void missingCompleteIngredientKitRefusesEveryOutput(GameTestHelper helper) {
        withReadyFixture(helper,f -> {
            f.barrel.setItem(0,new ItemStack(Items.OAK_PLANKS,5)); var item=f.describe(f.barrel.getItem(0)); int[] calls={0};
            var result=f.craft(f.executor(null,null),item,() -> true,outputs -> calls[0]++);
            helper.assertTrue(result.reason()==WorkOrder.Reason.MATERIALS && result.produced()==0 && f.barrel.getItem(0).getCount()==5
                    && f.barrel.getItem(1).isEmpty() && calls[0]==0 && f.registry.effects().size()==0,"Incomplete native kit was expended or fabricated output");
        });
    }
    @GameTest(template="identity_empty",timeoutTicks=240)
    public static void changedComponentsAndNativeCountsRefuseBeforeExpense(GameTestHelper helper) {
        withReadyFixture(helper,f -> {
            f.barrel.setItem(0,namedPlanks("accepted",6)); var item=f.describe(f.barrel.getItem(0)); int[] calls={0};
            var changing=f.executor((context,who,prepared) -> {f.barrel.getItem(0).set(DataComponents.CUSTOM_NAME,Component.literal("external change")); return true;},null);
            var components=f.craft(changing,item,() -> true,outputs -> calls[0]++);
            helper.assertTrue(components.produced()==0 && f.barrel.getItem(0).getCount()==6 && f.barrel.getItem(1).isEmpty()
                    && calls[0]==0 && f.registry.effects().size()==0,"Final native components were not rechecked after protection");
            f.barrel.setItem(0,namedPlanks("accepted",6));
            var counts=f.craft(f.executor((context,who,prepared) -> {f.barrel.getItem(0).setCount(5);return true;},null),item,() -> true,outputs -> calls[0]++);
            helper.assertTrue(counts.produced()==0 && f.barrel.getItem(0).getCount()==5 && f.barrel.getItem(1).isEmpty()
                    && calls[0]==0 && f.registry.effects().size()==0,"External native count change was consumed");
        });
    }
    @GameTest(template="identity_empty",timeoutTicks=240)
    public static void currentOwnerNeoForgeVetoAndChangedLogicalKitNeverSpend(GameTestHelper helper) {
        withReadyFixture(helper,f -> {
            f.barrel.setItem(0,new ItemStack(Items.OAK_PLANKS,6)); var item=f.describe(f.barrel.getItem(0)); int[] calls={0};
            UUID nextOwner=UUID.randomUUID(); f.updateOwner(nextOwner,2); UUID[] principal={null};
            Consumer<RecipeCraftEvent> listener=event -> {if(event.context().colonyId().equals(f.colony)) {principal[0]=event.principal();event.setCanceled(true);}};
            NeoForge.EVENT_BUS.addListener(RecipeCraftEvent.class,listener);
            try {
                var veto=f.craft(f.executor(new NeoForgeRecipeProtection(helper.getLevel().getServer()),null),item,() -> true,outputs -> calls[0]++);
                helper.assertTrue(veto.reason()==WorkOrder.Reason.PERMISSION_DENIED && nextOwner.equals(principal[0])
                        && f.barrel.getItem(0).getCount()==6 && f.barrel.getItem(1).isEmpty() && calls[0]==0,"Native craft protection ignored current owner");
            } finally {NeoForge.EVENT_BUS.unregister(listener);}
            boolean[] current={true};
            var revoked=f.craft(f.executor((context,who,prepared) -> {current[0]=false;return true;},null),item,() -> current[0],outputs -> calls[0]++);
            helper.assertTrue(revoked.produced()==0 && f.barrel.getItem(0).getCount()==6 && f.barrel.getItem(1).isEmpty()
                    && calls[0]==0 && f.registry.effects().size()==0,"Changed preadmitted logical kit survived final guard");
        });
    }
    @GameTest(template="identity_empty",timeoutTicks=240)
    public static void changedTableBarrelEpochAndReadinessRefuseExpense(GameTestHelper helper) {
        withReadyFixture(helper,f -> {
            f.barrel.setItem(0,new ItemStack(Items.OAK_PLANKS,6)); var item=f.describe(f.barrel.getItem(0)); int[] calls={0};
            var table=f.craft(f.executor((context,who,prepared) -> {helper.getLevel().setBlockAndUpdate(f.tablePos,Blocks.STONE.defaultBlockState());return true;},null),item,() -> true,outputs -> calls[0]++);
            helper.assertTrue(table.produced()==0 && f.barrel.getItem(0).getCount()==6 && f.barrel.getItem(1).isEmpty(),"Changed crafting table was accepted");
            helper.getLevel().setBlockAndUpdate(f.tablePos,Blocks.CRAFTING_TABLE.defaultBlockState());
            var epoch=f.executor(null,null).craft(f.context(),2,f.production,0,f.workshop,f.recipe,List.of(new RecipeExecutor.Input(f.input,item,6)),
                    List.of(new RecipeExecutor.Output(f.output,4)),() -> true,outputs -> calls[0]++);
            helper.assertTrue(epoch.produced()==0 && f.barrel.getItem(0).getCount()==6,"Stale producer epoch spent kit");
            f.registry.updateCitizen(f.registry.citizen(f.citizen).withAdmission(CitizenRecord.Admission.INACTIVE));
            var inactive=f.craft(f.executor(null,null),item,() -> true,outputs -> calls[0]++);
            helper.assertTrue(inactive.produced()==0 && f.barrel.getItem(0).getCount()==6 && calls[0]==0,"Inactive producer spent kit");
            f.registry.updateCitizen(f.registry.citizen(f.citizen).withAdmission(CitizenRecord.Admission.ACTIVE));
            f.storage.reidentify(f.colony,f.position(f.barrelPos));
            var identity=f.craft(f.executor(null,null),item,() -> true,outputs -> calls[0]++);
            helper.assertTrue(identity.produced()==0 && f.barrel.getItem(0).getCount()==6 && calls[0]==0,"Replacement canonical barrel identity accepted old kit");
        });
    }
    @GameTest(template="identity_empty",timeoutTicks=240)
    public static void failedPostFactCallbackBlocksWithoutReplayOrCompensation(GameTestHelper helper) {
        withReadyFixture(helper,f -> {
            f.barrel.setItem(0,new ItemStack(Items.OAK_PLANKS,12)); var item=f.describe(f.barrel.getItem(0)); int[] calls={0};
            var failed=f.craft(f.executor(null,null),item,() -> true,outputs -> {calls[0]++;throw new IllegalStateException("fixture commit fault");});
            var effect=f.registry.effects().snapshots().getLast();
            helper.assertTrue(failed.ambiguous() && failed.produced()==4 && calls[0]==1 && f.registry.colony(f.colony).recoveryBlocked()
                    && effect.state()==EffectRecord.State.AMBIGUOUS && effect.craft().complete(),"Callback fault did not retain verified ambiguous craft fact");
            helper.assertTrue(f.barrel.getItem(0).getCount()==6 && f.barrel.getItem(1).getCount()==4,"Callback fault compensated native batch");
            var retry=f.craft(f.executor(null,null),item,() -> true,outputs -> calls[0]++);
            helper.assertTrue(retry.reason()==WorkOrder.Reason.RECOVERY_AMBIGUOUS && retry.produced()==0 && calls[0]==1
                    && f.barrel.getItem(0).getCount()==6 && f.barrel.getItem(1).getCount()==4,"Ambiguous batch replayed native expense/output");
            helper.assertTrue(f.storage.currentContainer(f.output)==null && f.storage.recoveryContainer(f.output)==f.barrel,"Recovery could not inspect actual blocked craft barrel");
        });
    }
    @GameTest(template="identity_empty",timeoutTicks=240)
    public static void postNativeComponentDisagreementRetainsExactRealStack(GameTestHelper helper) {
        withReadyFixture(helper,f -> {
            f.barrel.setItem(0,new ItemStack(Items.OAK_PLANKS,6)); var item=f.describe(f.barrel.getItem(0)); int[] calls={0};
            var changed=f.craft(f.executor(null,(point,context) -> {
                if(point==RecipeExecutor.FaultPoint.AFTER_NATIVE_EFFECT) f.barrel.getItem(1).set(DataComponents.CUSTOM_NAME,Component.literal("external result"));
            }),item,() -> true,outputs -> calls[0]++);
            var effect=f.registry.effects().snapshots().getLast();
            helper.assertTrue(changed.ambiguous() && calls[0]==0 && f.barrel.getItem(0).isEmpty() && f.barrel.getItem(1).getCount()==4
                    && effect.state()==EffectRecord.State.AMBIGUOUS && !effect.craft().complete()
                    && effect.craft().outputs().getFirst().afterItem().equals(f.describe(f.barrel.getItem(1))),"Native component disagreement lost exact after-fact");
        });
    }
    @GameTest(template="identity_empty",timeoutTicks=240)
    public static void postObservedFaultBlocksBeforeCallbackAndDoesNotReplay(GameTestHelper helper) {
        withReadyFixture(helper,f -> {
            f.barrel.setItem(0,new ItemStack(Items.OAK_PLANKS,12)); var item=f.describe(f.barrel.getItem(0)); int[] calls={0};
            var failed=f.craft(f.executor(null,(point,context) -> {if(point==RecipeExecutor.FaultPoint.AFTER_FACT_BEFORE_NOTIFY) throw new IllegalStateException("fixture post-fact fault");}),item,() -> true,outputs -> calls[0]++);
            helper.assertTrue(failed.ambiguous() && calls[0]==0 && f.barrel.getItem(0).getCount()==6 && f.barrel.getItem(1).getCount()==4
                    && f.registry.effects().snapshots().getLast().craft().complete(),"Post-observed failure fabricated or compensated output");
            var retry=f.craft(f.executor(null,null),item,() -> true,outputs -> calls[0]++);
            helper.assertTrue(retry.produced()==0 && calls[0]==0 && f.barrel.getItem(1).getCount()==4,"Post-fact failure replayed batch");
        });
    }
    @GameTest(template="identity_empty",timeoutTicks=240)
    public static void postFactNativeReplacementBlocksPublicationAndReplay(GameTestHelper helper) {
        withReadyFixture(helper,f -> {
            f.barrel.setItem(0,new ItemStack(Items.OAK_PLANKS,12)); var item=f.describe(f.barrel.getItem(0)); int[] calls={0};
            var failed=f.craft(f.executor(null,(point,context) -> {
                if(point==RecipeExecutor.FaultPoint.AFTER_FACT_BEFORE_NOTIFY) f.barrel.setItem(1,new ItemStack(Items.STONE,3));
            }),item,() -> true,outputs -> calls[0]++);
            helper.assertTrue(failed.ambiguous() && calls[0]==0 && f.barrel.getItem(0).getCount()==6
                    && f.barrel.getItem(1).is(Items.STONE) && f.barrel.getItem(1).getCount()==3
                    && f.registry.effects().snapshots().getLast().craft().complete(),"Post-fact replacement reused stale native snapshot or compensated real mutation");
            var retry=f.craft(f.executor(null,null),item,() -> true,outputs -> calls[0]++);
            helper.assertTrue(retry.produced()==0 && calls[0]==0 && f.barrel.getItem(0).getCount()==6
                    && f.barrel.getItem(1).getCount()==3,"Native replacement disagreement replayed admitted batch");
        });
    }
    @GameTest(template="identity_empty",timeoutTicks=240)
    public static void exactCraftEvidenceRoundTripsAndFutureSchemaRemainsOpaque(GameTestHelper helper) {
        withReadyFixture(helper,f -> {
            f.barrel.setItem(0,namedPlanks("persistent ingredient",8)); var input=f.describe(f.barrel.getItem(0));
            var result=f.craft(f.executor(null,null),input,() -> true,outputs -> {});
            helper.assertTrue(result.produced()==4,"Roundtrip fixture did not physically craft");
            CompoundTag encoded=ColonySavedData.empty(f.registry.snapshot()).save(new CompoundTag(),helper.getLevel().registryAccess());
            var loaded=ColonySavedData.load(encoded,helper.getLevel().registryAccess());
            helper.assertTrue(loaded.snapshot().effects().equals(f.registry.effects().snapshots()),"Exact canonical craft portions/components/before/after/phase did not roundtrip");
            CompoundTag unknown=encoded.copy(), evidence=null;
            for(var value:unknown.getList("evidence",Tag.TAG_COMPOUND)) {var entry=(CompoundTag)value;if(entry.getString("kind").equals("RECIPE_CRAFT")) evidence=entry;}
            if(evidence==null) throw new AssertionError("Missing native craft evidence");
            evidence.getCompound("craft").putInt("schemaVersion",99); evidence.getCompound("craft").remove("phase");
            evidence.getCompound("craft").putString("futureField","retained");
            var opaque=ColonySavedData.load(unknown,helper.getLevel().registryAccess());
            helper.assertTrue(opaque.snapshot().effects().isEmpty() && opaque.contentBlockedColonies().contains(f.colony),"Future native craft schema was silently interpreted");
            helper.assertTrue(opaque.save(new CompoundTag(),helper.getLevel().registryAccess()).getList("evidence",Tag.TAG_COMPOUND).contains(evidence),"Future native craft record was rewritten/dropped");
            var observed=f.registry.effects().snapshots().getLast(); boolean rewound=false;
            try {observed.observedCraft(observed.craft().observed(observed.craft().inputs(),observed.craft().outputs(),EffectRecord.CraftPhase.PREPARED),true);}
            catch(IllegalArgumentException expected) {rewound=true;}
            helper.assertTrue(rewound,"Native craft phase could move backwards after observed fact");
        });
    }
    private static ItemStack namedPlanks(String name,int count) {
        var stack=new ItemStack(Items.OAK_PLANKS,count); stack.set(DataComponents.CUSTOM_NAME,Component.literal(name)); return stack;
    }
    private static void withReadyFixture(GameTestHelper helper,Consumer<Fixture> check) {
        BlockPos origin=helper.absolutePos(new BlockPos(1,1,1));
        var access=new NeoForgeChunkAccess(helper.getLevel().getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime")));
        UUID owner=UUID.randomUUID(); List<ChunkKey> keys=new ArrayList<>();
        for(int x=(origin.getX()-4)>>4;x<=(origin.getX()+5)>>4;x++) for(int z=(origin.getZ()-4)>>4;z<=(origin.getZ()+5)>>4;z++) {
            var key=new ChunkKey(helper.getLevel().dimension().location().toString(),x,z); keys.add(key);
            if(!access.acquire(owner,key,ChunkDemandManager.Readiness.ENTITY_TICKING)) throw new IllegalStateException("Native craft fixture ticket denied");
        }
        boolean[] done={false}; helper.onEachTick(() -> {
            if(done[0] || !keys.stream().allMatch(key -> access.ready(key,ChunkDemandManager.Readiness.ENTITY_TICKING))) return;
            done[0]=true;
            try(var fixture=new Fixture(helper,origin)) {check.accept(fixture);}
            finally {for(var key:keys) access.release(owner,key,ChunkDemandManager.Readiness.ENTITY_TICKING);}
            helper.succeed();
        });
    }
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper; final BlockPos tablePos,barrelPos; final ColonyRegistry registry;
        final UUID colony=UUID.randomUUID(),citizen=UUID.randomUUID(),owner=UUID.randomUUID(),checkpoint=UUID.randomUUID(),production=UUID.randomUUID();
        final CitizenEntity producer; final Container barrel; final StorageService storage;
        final StorageRegistry.Workshop workshop; final StockRegion input,output; final RecipeDefinition recipe;
        Fixture(GameTestHelper helper,BlockPos origin) {
            this.helper=helper; tablePos=origin; barrelPos=origin.east(2);
            registry=new ColonyRegistry(() -> {if(!helper.getLevel().getServer().isSameThread())throw new IllegalStateException("Native craft owner");}); registry.budgets().beginTick(0);
            registry.addColony(new ColonyRuntime(colony,"Native craft",new Territory(position(origin).dimension(),origin.getX()-8,origin.getZ()-8,origin.getX()+8,origin.getZ()+8),owner,Map.of(),1,1,false,null,false));
            helper.getLevel().setBlockAndUpdate(tablePos.below(),Blocks.STONE.defaultBlockState()); helper.getLevel().setBlockAndUpdate(barrelPos.below(),Blocks.STONE.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(tablePos,Blocks.CRAFTING_TABLE.defaultBlockState()); helper.getLevel().setBlockAndUpdate(barrelPos,Blocks.BARREL.defaultBlockState());
            barrel=(Container)helper.getLevel().getBlockEntity(barrelPos);
            storage=new StorageService(helper.getLevel().getServer(),registry,registry.budgets(),new NeoForgeStorageIdentity());
            var registration=storage.register(colony,position(barrelPos),"workshop"); input=registration.slots().get(0); output=registration.slots().get(1);
            workshop=storage.registerWorkshop(colony,position(tablePos),position(barrelPos));
            producer=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(helper.getLevel());
            if(producer==null)throw new IllegalStateException("Citizen type unavailable");
            producer.initializeIdentity(citizen,1); producer.moveTo(origin.getX()+1.5,origin.getY(),origin.getZ()+1.5,0,0);
            registry.addCitizen(new CitizenRecord(citizen,colony,producer.getUUID(),1,null,workshop.id(),null,"colonyloom:carpenter",Map.of(),Map.of("food",20),
                    CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.READY,0,Map.of("food",1200L),position(producer.blockPosition()),1),
                    record -> {if(!helper.getLevel().addFreshEntity(producer))throw new IllegalStateException("Producer spawn denied");});
            registry.bindings().observe(citizen,producer.getUUID(),1); producer.setQuarantined(false);
            recipe=RecipeDefinition.create("colonyloom:oak_stairs",1,"colonyloom:carpenter","minecraft:crafting_table",
                    List.of(new RecipeDefinition.Ingredient(new ItemMatcher("minecraft:oak_planks",null),6)),describe(new ItemStack(Items.OAK_STAIRS)),4,20);
        }
        ItemDescriptor describe(ItemStack stack) {return NativeItemDescriptor.describe(stack,helper.getLevel().registryAccess());}
        WorldPosition position(BlockPos pos) {return new WorldPosition(helper.getLevel().dimension().location().toString(),pos.getX(),pos.getY(),pos.getZ());}
        ActionContext context() {return new ActionContext(colony,citizen,ActionContext.Kind.RECIPE_CRAFT,position(tablePos),ActionContext.AuthorityMode.COLONY,null,registry.colony(colony).authorityRevision());}
        RecipeExecutor executor(RecipeExecutor.Protection protection,RecipeExecutor.FaultObserver observer) {return new RecipeExecutor(helper.getLevel().getServer(),registry,storage,protection,() -> checkpoint,observer);}
        RecipeExecutor.Result craft(RecipeExecutor executor,ItemDescriptor item,BooleanSupplier current,RecipeExecutor.Commit commit) {
            return executor.craft(context(),1,production,0,workshop,recipe,List.of(new RecipeExecutor.Input(input,item,6)),List.of(new RecipeExecutor.Output(output,4)),current,commit);
        }
        void updateOwner(UUID owner,long revision) {var old=registry.colony(colony);registry.updateColony(new ColonyRuntime(colony,old.name(),old.territory(),owner,Map.of(),revision,revision,false,null,false));}
        @Override public void close() {producer.remove(Entity.RemovalReason.DISCARDED);}
    }
}
