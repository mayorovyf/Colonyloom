package io.github.kpuctajluk.colonyloom.gametest;

import com.mojang.authlib.GameProfile;
import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.action.EffectRecord;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.command.ColonyCommands;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.content.BlueprintDefinition;
import io.github.kpuctajluk.colonyloom.core.navigation.NavigationService;
import io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime;
import io.github.kpuctajluk.colonyloom.core.supply.CoverageShare;
import io.github.kpuctajluk.colonyloom.core.supply.Demand;
import io.github.kpuctajluk.colonyloom.core.supply.ItemMatcher;
import io.github.kpuctajluk.colonyloom.core.supply.RecipeDefinition;
import io.github.kpuctajluk.colonyloom.core.supply.SupplyPlanner;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.gameplay.construction.ConstructionController;
import io.github.kpuctajluk.colonyloom.gameplay.logistics.DeliveryController;
import io.github.kpuctajluk.colonyloom.gameplay.production.ProcessDefinition;
import io.github.kpuctajluk.colonyloom.gameplay.needs.NeedsController;
import io.github.kpuctajluk.colonyloom.gameplay.production.ProductionCatalog;
import io.github.kpuctajluk.colonyloom.minecraft.construction.BlockPlacementExecutor;
import io.github.kpuctajluk.colonyloom.minecraft.construction.MinecraftConstructionGeometry;
import io.github.kpuctajluk.colonyloom.minecraft.construction.MinecraftConstructionService;
import io.github.kpuctajluk.colonyloom.minecraft.content.ContentLoader;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.minecraft.navigation.MinecraftNavigationBackend;
import io.github.kpuctajluk.colonyloom.minecraft.needs.FoodConsumptionExecutor;
import io.github.kpuctajluk.colonyloom.minecraft.needs.MinecraftNeedsService;
import io.github.kpuctajluk.colonyloom.minecraft.production.MinecraftProductionService;
import io.github.kpuctajluk.colonyloom.minecraft.production.RecipeExecutor;
import io.github.kpuctajluk.colonyloom.minecraft.storage.MinecraftDeliveryAccess;
import io.github.kpuctajluk.colonyloom.minecraft.storage.MinecraftDeliveryService;
import io.github.kpuctajluk.colonyloom.minecraft.storage.MinecraftSupplyAccess;
import io.github.kpuctajluk.colonyloom.minecraft.storage.NativeItemDescriptor;
import io.github.kpuctajluk.colonyloom.minecraft.storage.StorageService;
import io.github.kpuctajluk.colonyloom.minecraft.storage.StorageTransferExecutor;
import io.github.kpuctajluk.colonyloom.neoforge.NeoForgeChunkAccess;
import io.github.kpuctajluk.colonyloom.neoforge.NeoForgeItemInteraction;
import io.github.kpuctajluk.colonyloom.neoforge.NeoForgeRecipeProtection;
import io.github.kpuctajluk.colonyloom.neoforge.NeoForgeStorageIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Development-only terrain/resources; scheduler, production, couriers and builders perform every effect. */
@GameTestHolder("colonyloom")
@PrefixGameTestTemplate(false)
public final class SharedProductionGameTests {
    private static final int MAX_TICKS=1800;

    @GameTest(template="identity_empty",batch="stage10_shared_production",timeoutTicks=2000)
    public static void cancellationOfOneSitePreservesBegunBatchAndReturnsThreePhysicalStairs(GameTestHelper helper) {
        run(helper,false);
    }

    @GameTest(template="identity_empty",batch="stage10_shared_production_blocked",timeoutTicks=2000)
    public static void fullSurplusReceiversRetainRealOutputUntilCapacityReturns(GameTestHelper helper) {
        run(helper,true);
    }

    @GameTest(template="identity_empty",batch="stage10_shared_food_pause",timeoutTicks=6200)
    public static void sharedBegunBatchResumesOriginalProducerAfterNativeFoodPreemption(GameTestHelper helper) {
        run(helper,false,PauseMode.FOOD);
    }

    @GameTest(template="identity_empty",batch="stage10_shared_output_pause",timeoutTicks=6200)
    public static void sharedBatchRetainsPaidLastSlotOutputScanAcrossReadinessPauses(GameTestHelper helper) {
        run(helper,false,PauseMode.OUTPUT);
    }

    @GameTest(template="identity_empty",batch="stage10_shared_output_changed",timeoutTicks=6200)
    public static void pausedSharedOutputHintRejectsChangedNativeCapacityBeforeCraft(GameTestHelper helper) {
        run(helper,false,PauseMode.OUTPUT_CHANGED);
    }

    private enum PauseMode { NONE,FOOD,OUTPUT,OUTPUT_CHANGED }

    private static void run(GameTestHelper helper,boolean blockReceiver) {
        run(helper,blockReceiver,PauseMode.NONE);
    }

    private static void run(GameTestHelper helper,boolean blockReceiver,PauseMode pauseMode) {
        var origin=helper.absolutePos(new BlockPos(1,1,1));
        var access=new NeoForgeChunkAccess(helper.getLevel().getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime")));
        UUID ticketOwner=UUID.randomUUID(); List<ChunkKey> keys=new ArrayList<>();
        String dimension=helper.getLevel().dimension().location().toString();
        // Admission reconciles resident 3x3 domains; wait for those real chunks before accelerated ticks.
        for(int x=((origin.getX()-6)>>4)-1;x<=((origin.getX()+10)>>4)+1;x++) for(int z=((origin.getZ()-6)>>4)-1;z<=((origin.getZ()+7)>>4)+1;z++) {
            var key=new ChunkKey(dimension,x,z); keys.add(key);
            if(!access.acquire(ticketOwner,key,ChunkDemandManager.Readiness.ENTITY_TICKING)) throw new IllegalStateException("Shared production fixture ticket denied");
        }
        Fixture[] fixture={null}; boolean[] done={false}; int[] ticks={0};
        helper.onEachTick(() -> {
            if(done[0]) return;
            try {
                if(++ticks[0]>(pauseMode==PauseMode.NONE?MAX_TICKS:6000)) throw new IllegalStateException("Shared production timeout: "+(fixture[0]==null?"chunks":fixture[0].diagnostics()));
                if(fixture[0]==null) {
                    if(!keys.stream().allMatch(key -> access.ready(key,ChunkDemandManager.Readiness.ENTITY_TICKING))) return;
                    fixture[0]=new Fixture(helper,origin,access,blockReceiver,pauseMode);
                }
                fixture[0].core.tick(fixture[0].core.serverTick()+1);
                if(!fixture[0].observe()) return;
                done[0]=true; fixture[0].close();
                for(var key:keys) access.release(ticketOwner,key,ChunkDemandManager.Readiness.ENTITY_TICKING);
                helper.succeed();
            } catch(RuntimeException|AssertionError failure) {
                done[0]=true;
                if(fixture[0]!=null) fixture[0].close();
                for(var key:keys) access.release(ticketOwner,key,ChunkDemandManager.Readiness.ENTITY_TICKING);
                throw failure;
            }
        });
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final ServerRuntime core;
        final UUID colony=UUID.randomUUID(),owner=UUID.randomUUID(),checkpoint=UUID.randomUUID();
        final BlockPos siteA,siteB,bufferA,bufferB,workshopPos,warehousePos,returnPos;
        final Container workshop,warehouse,returns,aBuffer,bBuffer;
        final List<CitizenEntity> citizens=new ArrayList<>();
        final StorageService storage;
        final ChunkDemandManager chunks;
        final io.github.kpuctajluk.colonyloom.minecraft.runtime.CitizenAdmissionService admission;
        final MinecraftConstructionService construction;
        final MinecraftDeliveryService delivery;
        final MinecraftProductionService production;
        final NavigationService navigation;
        final SupplyPlanner planner;
        final NeedsController needs;
        final ColonyCommands.CommandContext context;
        final WorkOrder a,b;
        final net.minecraft.world.level.block.state.BlockState expectedStair;
        final boolean blockReceiver;
        final PauseMode pauseMode;
        final Budget[] frozenBudgets={Budget.ASSIGNMENT_CANDIDATES,Budget.GRAPH_EXPANSIONS,Budget.NAVIGATION_STARTS,
                Budget.BLUEPRINT_COMPARISONS,Budget.PHYSICAL_ACTIONS,Budget.STORAGE_SLOT_CHECKS,Budget.CHUNK_REQUESTS,
                Budget.VIEW_ROWS,Budget.DIRTY_RESCAN_OBJECTS};
        final int[] frozenQuanta={3,1,1,4,1,60,1,1,1};
        UUID originalProducer,originalWork,foodWork;
        long preemptedActive,resumedActive;long detachedRemaining=-1;
        boolean foodTriggered,foodDetached,foodConsumed,foodResumed,outputChanged,staleOutputRejected;
        int outputPauses,outputCursorBeforeAttempt;
        long outputAttemptTick=-1;
        UUID batch,surplus;
        boolean cancelled,receiverBlocked,capacityObserved,unblocked;
        long blockedAt,completedAt=-1;

        Fixture(GameTestHelper helper,BlockPos origin,NeoForgeChunkAccess access,boolean blockReceiver,PauseMode pauseMode) {
            this.helper=helper; this.blockReceiver=blockReceiver;this.pauseMode=pauseMode;
            var level=helper.getLevel(); var server=level.getServer();
            for(int x=-6;x<=10;x++) for(int z=-6;z<=7;z++) {
                var pos=origin.offset(x,0,z); level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
                for(int y=0;y<3;y++) level.setBlockAndUpdate(pos.above(y),Blocks.AIR.defaultBlockState());
            }
            siteA=origin.offset(3,0,-3); siteB=origin.offset(7,0,2); bufferA=siteA.west(); bufferB=siteB.west();
            workshopPos=origin.offset(-2,0,-2); warehousePos=origin.offset(-4,0,3); returnPos=origin.offset(-1,0,4);
            for(var pos:List.of(workshopPos,warehousePos,returnPos,bufferA,bufferB)) level.setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(workshopPos.east(),Blocks.CRAFTING_TABLE.defaultBlockState());
            workshop=container(workshopPos); warehouse=container(warehousePos); returns=container(returnPos); aBuffer=container(bufferA); bBuffer=container(bufferB);
            workshop.setItem(0,new ItemStack(Items.OAK_PLANKS,6));
            if(pauseMode==PauseMode.FOOD)warehouse.setItem(0,new ItemStack(Items.BREAD));
            if(pauseMode==PauseMode.OUTPUT||pauseMode==PauseMode.OUTPUT_CHANGED)
                for(int slot=1;slot<26;slot++)workshop.setItem(slot,new ItemStack(Items.STONE,64));
            core=ServerRuntime.start(Thread.currentThread());
            var content=ContentLoader.load(server.getResourceManager(),server.registryAccess());
            core.configureCommands(() -> {},content.professions().values());
            var limits=core.admission().limits();
            if(pauseMode==PauseMode.NONE)limits=limits.withMaxManagedNanos(100_000_000L);
            else for(int i=0;i<frozenBudgets.length;i++)limits=limits.withBudget(frozenBudgets[i],frozenQuanta[i]);
            core.updateLimits(limits);
            core.registry().addColony(new ColonyRuntime(colony,"Shared physical production",new Territory(position(origin).dimension(),origin.getX()-16,origin.getZ()-16,origin.getX()+16,origin.getZ()+16),owner,Map.of(),1,1,false,null,false));
            storage=new StorageService(server,core.registry(),core.budgets(),new NeoForgeStorageIdentity());
            storage.register(colony,position(workshopPos),"workshop"); storage.register(colony,position(warehousePos),"warehouse"); storage.register(colony,position(returnPos),"return");
            storage.register(colony,position(bufferA),"construction"); storage.register(colony,position(bufferB),"construction");
            var workplace=storage.registerWorkshop(colony,position(workshopPos.east()),position(workshopPos));
            citizen(origin.offset(-2,0,0),"colonyloom:carpenter",workplace.id());
            citizen(origin.offset(0,0,5),"colonyloom:courier",null);
            citizen(origin.offset(3,0,-1),"colonyloom:builder",null);
            citizen(origin.offset(7,0,4),"colonyloom:builder",null);
            // Reuse a real loaded stair palette, but demand one block per site so one batch has measured surplus.
            var loaded=content.blueprints().get("colonyloom:test_four_stairs");
            if(loaded==null) throw new IllegalStateException("Missing development stair blueprint");
            var blueprint=BlueprintDefinition.create("colonyloom:shared_single_stair",1,List.of(loaded.blocks().getFirst()),loaded.markers());
            expectedStair=MinecraftConstructionGeometry.state(blueprint.blocks().getFirst().block());
            var controller=new ConstructionController(core.registry(),new MinecraftConstructionGeometry(server));
            controller.definitions(Map.of(blueprint.id(),blueprint)); core.commands().construction(controller);
            core.commands().delivery(new DeliveryController(core.registry(),new MinecraftDeliveryAccess(core.registry(),storage)));
            chunks=new ChunkDemandManager(core.registry(),core.budgets(),access);
            admission=new io.github.kpuctajluk.colonyloom.minecraft.runtime.CitizenAdmissionService(server,core,chunks);
            var placement=new BlockPlacementExecutor(server,core.registry(),new NeoForgeItemInteraction(id -> id.equals(owner)?new GameProfile(owner,"SharedProductionFixture"):null),null);
            var transfer=new StorageTransferExecutor(server,core.registry(),storage,(action,principal,source,destination,amount) -> true,() -> checkpoint,null);
            construction=new MinecraftConstructionService(server,core.registry(),controller,chunks,placement,() -> checkpoint,storage,transfer);
            delivery=new MinecraftDeliveryService(server,core.registry(),storage,transfer,chunks);
            production=new MinecraftProductionService(core.registry(),storage,new RecipeExecutor(server,core.registry(),storage,new NeoForgeRecipeProtection(server),() -> checkpoint,null),chunks);
            NavigationService.GoalAuthority goals=(work,request) -> WorkOrder.DELIVERY.equals(work.typeId())?delivery.current(work,request):WorkOrder.PRODUCTION.equals(work.typeId())?production.current(work,request):construction.current(work,request);
            navigation=new NavigationService(core.registry(),core.budgets(),chunks,new MinecraftNavigationBackend(server,core.registry(),chunks,goals),goals);
            construction.navigation(navigation); delivery.navigation(navigation); production.navigation(navigation);
            var food=new MinecraftNeedsService(core.registry(),storage,delivery,new FoodConsumptionExecutor(server,core.registry(),storage,(action,principal,prepared) -> true,() -> checkpoint,null));
            needs=new NeedsController(core.registry(),food);food.needs(needs);
            planner=new SupplyPlanner(core.registry(),core.registry().supply(),core.budgets());
            // Longer active time makes sharing observable without replacing the native six-plank/four-stair recipe.
            var recipe=RecipeDefinition.create("colonyloom:shared_oak_stairs",1,"colonyloom:carpenter","minecraft:crafting_table",List.of(new RecipeDefinition.Ingredient(new ItemMatcher("minecraft:oak_planks",null),6)),NativeItemDescriptor.describe(new ItemStack(Items.OAK_STAIRS),level.registryAccess()),4,160);
            planner.configure(new ProductionCatalog(core.registry(),Map.of(recipe.id(),new ProcessDefinition(recipe))),new MinecraftSupplyAccess(core.registry(),storage));
            core.scheduler().physicalExecutor(WorkOrder.CONSTRUCTION,construction); core.scheduler().physicalExecutor(WorkOrder.DELIVERY,delivery); core.scheduler().physicalExecutor(WorkOrder.PRODUCTION,production);core.scheduler().physicalExecutor(WorkOrder.FOOD,food);
            core.scheduler().beforeWork(this::beforeWork);
            core.scheduler().beforeStep(() -> {
                if(pauseMode!=PauseMode.OUTPUT&&pauseMode!=PauseMode.OUTPUT_CHANGED)return;
                var order=batch==null?null:core.registry().supply().production(batch);
                if(order==null||order.terminal()||!order.workId().equals(executingWork().id()))return;
                outputAttemptTick=core.serverTick();outputCursorBeforeAttempt=outputCursor();
                // Unrelated native observers have used56 checks; the frozen60-check quota
                // still admits the exact four-slot final guard on a later paid turn.
                while(core.budgets().used(Budget.STORAGE_SLOT_CHECKS)<56)
                    if(!core.budgets().tryConsume(Budget.STORAGE_SLOT_CHECKS,Lane.NORMAL))break;
            });
            context=new ColonyCommands.CommandContext(owner,false,new ColonyCommands.PhysicalChecks() {
                public void validateTerritory(Territory territory) {}
                public void validateCitizenPosition(ColonyRuntime colony,WorldPosition position) {}
                public void validateRecovery(ColonyRuntime colony,List<CitizenRecord> citizens,List<io.github.kpuctajluk.colonyloom.core.citizen.BindingRegistry.Observation> observations) {}
            });
            a=core.commands().build(context,UUID.randomUUID(),colony,blueprint.id(),position(siteA),0);
            b=core.commands().build(context,UUID.randomUUID(),colony,blueprint.id(),position(siteB),0);
        }

        private void beforeWork(long tick) {
            if(pauseMode==PauseMode.NONE) {
                chunks.tick(tick);admission.tick();navigation.tick(tick);storage.tick(tick);core.registry().targetClaims().tick();
                core.registry().supply().reconcile(tick,core.budgets());planner.tick(tick);delivery.tick(tick);production.tick();return;
            }
            // The same frozen dirty quantum rotates among the real runtime consumers.
            int first=(int)((tick/2)%9);
            for(int offset=0;offset<9;offset++)switch((first+offset)%9) {
                case 0 -> chunks.tick(tick);
                case 1 -> admission.tick();
                case 2 -> {if(pauseMode==PauseMode.FOOD&&foodTriggered)needs.tick(tick);}
                case 3 -> navigation.tick(tick);
                case 4 -> storage.tick(tick);
                case 5 -> core.registry().targetClaims().tick();
                case 6 -> core.registry().supply().reconcile(tick,core.budgets());
                case 7 -> planner.tick(tick);
                case 8 -> {delivery.tick(tick);production.tick();}
                default -> throw new IllegalStateException("Unknown shared production phase");
            }
        }

        private WorkOrder executingWork() {
            try {
                var executing=core.scheduler().getClass().getDeclaredField("executing");executing.setAccessible(true);
                var node=executing.get(core.scheduler());var work=node.getClass().getDeclaredField("work");work.setAccessible(true);
                return (WorkOrder)work.get(node);
            } catch(ReflectiveOperationException failure) {throw new IllegalStateException("Missing scoped scheduler observer",failure);}
        }

        private Object productionState() {
            try {
                var field=production.getClass().getDeclaredField("active");field.setAccessible(true);
                return ((Map<?,?>)field.get(production)).get(originalWork);
            } catch(ReflectiveOperationException failure) {throw new IllegalStateException("Missing production scan observer",failure);}
        }

        private int outputCursor() {
            var state=productionState();if(state==null)return -1;
            try {var field=state.getClass().getDeclaredField("outputCursor");field.setAccessible(true);return field.getInt(state);}
            catch(ReflectiveOperationException failure) {throw new IllegalStateException("Missing production output cursor",failure);}
        }

        private boolean hasOutputHint() {
            var state=productionState();if(state==null)return false;
            try {var field=state.getClass().getDeclaredField("outputs");field.setAccessible(true);return !((List<?>)field.get(state)).isEmpty();}
            catch(ReflectiveOperationException failure) {throw new IllegalStateException("Missing production output hint",failure);}
        }

        private void citizen(BlockPos start,String profession,UUID workplace) {
            var level=helper.getLevel(); UUID id=UUID.randomUUID();
            var entity=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
            if(entity==null) throw new IllegalStateException("Citizen unavailable");
            entity.initializeIdentity(id,1); entity.moveTo(start.getX()+0.5,start.getY(),start.getZ()+0.5,0,0);
            core.registry().addCitizen(new CitizenRecord(id,colony,entity.getUUID(),1,null,workplace,null,profession,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.READY,0,Map.of("food",1200L),position(start),1),record -> {
                if(!level.addFreshEntity(entity)) throw new IllegalStateException("Citizen spawn refused");
            });
            core.bindings().observe(id,entity.getUUID(),1); entity.setQuarantined(false); citizens.add(entity);
        }

        boolean observe() {
            var supply=core.registry().supply();
            if(!cancelled) {
                var shared=supply.productionOrders().stream().filter(order -> order.batchStarted() && order.remainingActiveTicks()>0 && supply.orderShares(order.id()).stream().filter(share -> share.stage()==CoverageShare.Stage.PROMISED_OUTPUT).map(share -> supply.demand(share.demandId()).snapshot().ownerId()).distinct().count()==2).findFirst().orElse(null);
                if(shared==null) return false;
                check(shared.batches()==1 && shared.completedBatches()==0,"Sites did not share one indivisible begun batch");
                check(shared.citizenId()!=null && core.registry().citizen(shared.citizenId()).assignedWorkId().equals(shared.workId()),"Batch did not begin on the real assigned producer");
                check(count(workshop,Items.OAK_PLANKS)==6 && count(workshop,Items.OAK_STAIRS)==0,"Cancellation did not precede native batch effect");
                check(core.registry().construction().site(a.id()).consumed()==0 && core.registry().construction().site(b.id()).consumed()==0,"Sites placed before batch began");
                batch=shared.id(); core.commands().cancelWork(context,a.id()); cancelled=true;
                originalProducer=shared.citizenId();originalWork=shared.workId();
                if(pauseMode==PauseMode.FOOD) {
                    preemptedActive=core.registry().citizen(originalProducer).activeTimeTicks();
                    core.registry().updateCitizen(core.registry().citizen(originalProducer).withFood(6));foodTriggered=true;
                }
                var retained=supply.production(batch);
                check(retained.batchStarted() && !retained.terminal() && retained.batches()==1,"Cancellation destroyed shared begun batch");
                var promised=supply.orderShares(batch).stream().filter(share -> share.stage()==CoverageShare.Stage.PROMISED_OUTPUT).toList();
                check(promised.size()==1 && promised.getFirst().quantity()==1 && supply.demand(promised.getFirst().demandId()).snapshot().ownerId().equals(b.id()),"Cancelled A retained output promise or B lost its share");
            }
            if(pauseMode!=PauseMode.NONE) {
                for(int i=0;i<frozenBudgets.length;i++) {
                    check(core.admission().limits().budget(frozenBudgets[i])==frozenQuanta[i],"Pause fixture changed a frozen service quantum");
                    check(core.budgets().used(frozenBudgets[i])<=frozenQuanta[i],"Shared pause exceeded "+frozenBudgets[i]);
                }
                check(core.admission().limits().maxManagedNanos()==5_000_000L,"Pause fixture changed the managed deadline");
                var order=supply.production(batch);var work=core.workBoard().work(originalWork);
                check(originalWork.equals(order.workId()),"Pause replaced the original production work");
                if(!order.terminal())check(originalProducer.equals(order.citizenId()),"Pause replaced the begun batch's exact producer");
                if(pauseMode==PauseMode.FOOD)observeFoodPause(order);
                else observeOutputPause(order,work);
            }
            var internal=supply.demands().stream().map(Demand::snapshot).filter(d -> supply.productionSurplus(d.id()) && d.matcher().itemId().equals("minecraft:oak_stairs")).findFirst().orElse(null);
            if(internal!=null) {
                surplus=internal.id(); check(internal.required()==3 && internal.allocated()==0,"Surplus differs from three exact physical delivery items");
            }
            if(blockReceiver && !receiverBlocked && surplus!=null) {
                check(count(workshop,Items.OAK_STAIRS)>=3,"Surplus left before blocked receiver observation");
                fill(warehouse); fill(returns); receiverBlocked=true; blockedAt=core.serverTick();
            }
            if(receiverBlocked && !unblocked) {
                check(total(Items.OAK_STAIRS)+(helper.getLevel().getBlockState(siteB).is(Blocks.OAK_STAIRS)?1:0)==4,"Blocked receiver deleted/compensated physical output");
                check(count(workshop,Items.OAK_STAIRS)>=3 && count(warehouse,Items.OAK_STAIRS)==0 && count(returns,Items.OAK_STAIRS)==0,"Blocked surplus left retained workshop stack");
                var pending=supply.demand(surplus).snapshot();
                check(pending.covered()==3 && pending.fulfilled()==0 && pending.deliveredTotal()==0,"Blocked receiver released/deleted surplus ownership");
                // The sole courier may encounter the full return receiver on B's share first.
                // Require a real capacity wait while all three surplus items remain covered/physical,
                // not an incidental dispatch order between the two deliveries from the same batch.
                capacityObserved|=supply.deliveries().stream().filter(order -> order.workId()!=null && !order.terminal()).anyMatch(order -> core.workBoard().work(order.workId()).state()==WorkOrder.State.WAITING && core.workBoard().work(order.workId()).waitingReason()==WorkOrder.Reason.CAPACITY);
                if(!capacityObserved || core.serverTick()-blockedAt<40) return false;
                clear(warehouse); clear(returns); unblocked=true;
            }
            if(b.state()!=WorkOrder.State.COMPLETED || surplus==null || supply.demand(surplus).snapshot().status()!=Demand.Status.COMPLETED || supply.deliveries().stream().anyMatch(order -> !order.terminal())) return false;
            if(completedAt<0) completedAt=core.serverTick();
            if(core.serverTick()-completedAt<40) return false;
            assertComplete(); return true;
        }

        private void observeFoodPause(io.github.kpuctajluk.colonyloom.core.production.ProductionOrder order) {
            var citizen=core.registry().citizen(originalProducer);
            var pending=needs.workForCitizen(originalProducer);
            if(pending!=null)foodWork=pending.id();
            var food=core.registry().effects().snapshots().stream().filter(effect -> effect.food()!=null&&originalProducer.equals(effect.citizenId())).findFirst().orElse(null);
            if(!originalWork.equals(citizen.assignedWorkId())&&food==null) {
                if(!foodDetached){detachedRemaining=order.remainingActiveTicks();foodDetached=true;}
                check(order.remainingActiveTicks()==detachedRemaining && order.batchStarted(),"Food pause accrued production time or reset the begun batch");
                check(count(workshop,Items.OAK_PLANKS)==6 && total(Items.OAK_STAIRS)==0,"Food pause expended the retained exact kit");
                check(!core.workBoard().assign(originalWork,originalProducer),"Food-pending citizen rebound to ordinary production");
            }
            if(food!=null) {
                check(food.state()==EffectRecord.State.OBSERVED&&food.countBefore()-food.countAfter()==1&&food.food().foodAfter()-food.food().foodBefore()==5,"Food resumption lacked one exact native bread expense/nutrition fact");
                check(foodWork!=null&&foodWork.equals(food.workId())&&core.workBoard().work(foodWork).terminal(),"Native food did not complete the original exact subject work");
                foodConsumed=true;
            }
            if(foodConsumed&&!foodResumed&&originalWork.equals(citizen.assignedWorkId())) {
                check(order.remainingActiveTicks()==detachedRemaining,"Production caught up food-paused own ticks on rebind");
                check(citizen.activeTimeTicks()>preemptedActive,"Native food did not exercise a live own-clock pause");
                resumedActive=citizen.activeTimeTicks();foodResumed=true;
            }
            if(foodResumed&&!order.terminal())check(order.remainingActiveTicks()>=Math.max(0,detachedRemaining-(citizen.activeTimeTicks()-resumedActive)),"Production included paused/offline catch-up time");
        }

        private void observeOutputPause(io.github.kpuctajluk.colonyloom.core.production.ProductionOrder order,WorkOrder work) {
            if(order.terminal()||order.remainingActiveTicks()!=0)return;
            if(outputChanged&&!staleOutputRejected&&work.waitingReason()==WorkOrder.Reason.CAPACITY) {
                staleOutputRejected=true;
                check(count(workshop,Items.OAK_PLANKS)==6&&total(Items.OAK_STAIRS)==0,"Stale output hint spent the exact native kit");
                check(core.registry().effects().snapshots().stream().noneMatch(effect -> effect.craft()!=null),"Stale output hint published a craft fact");
            }
            int cursor=outputCursor();
            if(work.assignee()==null||outputAttemptTick!=core.serverTick()||cursor<=outputCursorBeforeAttempt)return;
            check(outputPauses<40,"Shared batch repeatedly discarded its paid output prefix");
            check(originalProducer.equals(work.assignee()),"Output pause changed the original producer");
            core.commands().updateCitizenReadiness(originalProducer,CitizenRecord.Readiness.UNKNOWN);
            core.commands().updateCitizenReadiness(originalProducer,CitizenRecord.Readiness.READY);
            check(outputCursor()==cursor,"Readiness pause discarded a charged output-slot cursor");
            outputPauses++;
            if(pauseMode==PauseMode.OUTPUT_CHANGED&&!outputChanged&&cursor==27&&hasOutputHint()) {
                check(workshop.getItem(26).isEmpty()&&workshop.getItem(25).is(Items.STONE),"Changed output fixture lacks the native last-slot hint");
                workshop.setItem(26,workshop.removeItemNoUpdate(25));workshop.setChanged();outputChanged=true;
            }
        }

        private void assertComplete() {
            var supply=core.registry().supply(); var site=core.registry().construction().site(b.id());
            check(a.state()==WorkOrder.State.CANCELLED && core.registry().construction().site(a.id()).closed() && core.registry().construction().site(a.id()).consumed()==0,"Cancelled site retained material/placement progress");
            check(helper.getLevel().getBlockState(siteA).isAir() && aBuffer.isEmpty(),"Cancelled A has physical residue");
            check(supply.demands().stream().map(Demand::snapshot).filter(demand -> demand.ownerId().equals(a.id())).allMatch(demand -> demand.status()==Demand.Status.CANCELLED && demand.covered()==0 && demand.allocated()==0),"Cancelled A retained material ownership");
            check(core.registry().targetClaims().snapshots().stream().noneMatch(claim -> claim.ownerId().equals(a.id()) || claim.ownerId().equals(b.id())),"Closed sites retained target claims");
            check(site.closed() && site.cursor()==1 && site.consumed()==1 && helper.getLevel().getBlockState(siteB).equals(expectedStair),"Surviving B did not place its exact share once");
            check(count(warehouse,Items.OAK_STAIRS)+count(returns,Items.OAK_STAIRS)==3 && count(workshop,Items.OAK_STAIRS)==0 && bBuffer.isEmpty(),"Surplus did not physically reach registered warehouse/return");
            check(total(Items.OAK_PLANKS)==0 && total(Items.OAK_LOG)==0 && total(Items.OAK_STAIRS)==3,"Native batch quantities were deleted, duplicated or compensated");
            check(citizens.stream().allMatch(entity -> entity.inventory().isEmpty()),"Citizen retained physical cargo/materials");
            var order=supply.production(batch);
            check(supply.productionOrders().size()==1 && order.terminal() && order.batches()==0 && order.completedBatches()==1,"Cancelled batch was repeated or abandoned");
            var demand=supply.demand(surplus).snapshot();
            check(demand.fulfilled()==3 && demand.deliveredTotal()==3 && demand.covered()==0 && demand.allocated()==0,"Surplus delivery did not commit exact native fact");
            check(core.registry().effects().snapshots().stream().filter(effect -> effect.kind()==ActionContext.Kind.RECIPE_CRAFT).count()==1 && core.registry().effects().snapshots().stream().filter(effect -> effect.kind()==ActionContext.Kind.BLOCK_PLACE && b.id().equals(effect.workId())).count()==1,"Physical craft/placement replayed");
            var craft=core.registry().effects().snapshots().stream().filter(effect -> effect.kind()==ActionContext.Kind.RECIPE_CRAFT).findFirst().orElseThrow().craft();
            check(craft.productionId().equals(batch) && craft.batchOrdinal()==0 && craft.inputs().stream().mapToInt(EffectRecord.CraftSlot::amount).sum()==6 && craft.outputCount()==4,"Native batch facts differ from six planks/four stairs");
            long returned=core.registry().effects().snapshots().stream().filter(effect -> effect.transfer()!=null && effect.transfer().source().storage().bindingEpoch()>0 && effect.transfer().source().storage().identity().equals(effect.citizenId()) && effect.transfer().item().itemId().equals("minecraft:oak_stairs") && core.registry().storage().registrations(colony).stream().filter(registration -> registration.role().equals("warehouse") || registration.role().equals("return")).anyMatch(registration -> registration.slots().contains(effect.transfer().destination()))).mapToLong(effect -> effect.transfer().inserted()).sum();
            check(returned==3,"Surplus inventory lacks three observed physical courier transfers to registered safe sink");
            check(core.registry().effects().snapshots().stream().allMatch(effect -> effect.state()==EffectRecord.State.OBSERVED && (effect.craft()==null || effect.craft().complete())),"Economic effects lack observed native evidence");
            check(core.registry().storage().reservations().entries().isEmpty() && core.registry().storage().allocations().entries().isEmpty(),"Terminal sites retained storage obligations");
            check(core.registry().citizens(colony).stream().allMatch(citizen -> citizen.assignedWorkId()==null),"Terminal chain retained worker assignment");
            check(!blockReceiver || capacityObserved && unblocked,"Blocked surplus never exposed CAPACITY and recovered");
            check(craft.inputs().size()==1&&craft.inputs().getFirst().afterCount()==0&&craft.outputs().size()==1&&craft.outputs().getFirst().amount()==4,"Shared craft lacks exact typed native slot facts");
            if(pauseMode==PauseMode.FOOD) {
                check(foodTriggered&&foodDetached&&foodConsumed&&foodResumed,"Shared batch did not exercise native food preemption and original producer resumption");
                check(total(Items.BREAD)==0&&core.registry().effects().snapshots().stream().filter(effect -> effect.food()!=null).count()==1,"Food pause lost/duplicated native bread");
                check(originalProducer.equals(core.registry().effects().snapshots().stream().filter(effect -> effect.craft()!=null).findFirst().orElseThrow().citizenId()),"Batch completed on a substitute producer");
            } else if(pauseMode==PauseMode.OUTPUT||pauseMode==PauseMode.OUTPUT_CHANGED) {
                check(outputPauses>=6,"Shared batch did not pay multiple native output portions across pauses");
                check(total(Items.STONE)==25*64,"Paused capacity validation deleted/created unrelated native property");
                check(craft.outputs().getFirst().slot().slot()==(pauseMode==PauseMode.OUTPUT_CHANGED?25:26),"Craft bypassed the paid last-slot output scan");
                check(pauseMode!=PauseMode.OUTPUT_CHANGED||outputChanged&&staleOutputRejected,"Changed native output hint was not rejected before expense");
            }
        }

        private void check(boolean condition,String message) {helper.assertTrue(condition,message);}
        private Container container(BlockPos pos) {return (Container)helper.getLevel().getBlockEntity(pos);}
        private WorldPosition position(BlockPos pos) {return new WorldPosition(helper.getLevel().dimension().location().toString(),pos.getX(),pos.getY(),pos.getZ());}
        private static int count(Container inventory,Item item) {
            int result=0; for(int slot=0;slot<inventory.getContainerSize();slot++) if(inventory.getItem(slot).is(item)) result+=inventory.getItem(slot).getCount(); return result;
        }
        private int total(Item item) {
            int result=0; for(var inventory:List.of(workshop,warehouse,returns,aBuffer,bBuffer)) result+=count(inventory,item);
            for(var entity:citizens) result+=count(entity.inventory(),item); return result;
        }
        private static void fill(Container inventory) {for(int slot=0;slot<inventory.getContainerSize();slot++) inventory.setItem(slot,new ItemStack(Items.STONE,64)); inventory.setChanged();}
        private static void clear(Container inventory) {inventory.clearContent(); inventory.setChanged();}
        String diagnostics() {
            var result=new StringBuilder("cancelled=").append(cancelled).append(" blocked=").append(receiverBlocked).append(" capacity=").append(capacityObserved)
                    .append(" pause=").append(pauseMode).append(':').append(outputPauses).append(':').append(outputChanged).append(':').append(staleOutputRejected)
                    .append(" food=").append(foodDetached).append(':').append(foodConsumed).append(':').append(foodResumed).append(" chunks=").append(chunks.diagnostics(null));
            for(var resource:io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.values()) if(resource==io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.LOADED_FOOTPRINT || resource==io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.BLOCK_TICKING || resource==io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.ENTITY_TICKING) {
                result.append(' ').append(resource).append('=').append(core.admission().used(resource));for(var lane:io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.values())result.append(':').append(lane).append('=').append(core.admission().used(resource,lane)).append('/').append(core.admission().laneCapacity(resource,lane));
            }
            for(var work:core.workBoard().works()) {
                result.append(" work=").append(work.typeId()).append(':').append(work.state()).append(':').append(work.stage()).append(':').append(work.waitingReason()).append(" nav=").append(navigation.state(work.id())).append(':').append(navigation.reason(work.id()));
                if(work.assignee()!=null) {var citizen=core.registry().citizen(work.assignee());var entity=helper.getLevel().getEntity(citizen.entityId());result.append(" pos=").append(entity==null?"unknown":entity.position());
                    if(entity instanceof CitizenEntity npc) result.append(" velocity=").append(npc.getDeltaMovement()).append(" wanted=").append(npc.getMoveControl().getWantedX()).append(',').append(npc.getMoveControl().getWantedY()).append(',').append(npc.getMoveControl().getWantedZ()).append(" ground=").append(npc.onGround());}
            }
            return result.toString();
        }
        @Override public void close() {
            planner.close(); production.close(); delivery.close(); navigation.close(); construction.close(); admission.close(); chunks.close();
            for(var entity:citizens) entity.remove(Entity.RemovalReason.DISCARDED);
            core.beginStopping(); core.stop();
        }
    }
}
