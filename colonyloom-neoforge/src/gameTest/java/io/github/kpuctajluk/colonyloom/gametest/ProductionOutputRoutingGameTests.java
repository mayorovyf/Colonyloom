package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.action.EffectRecord;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.navigation.NavigationService;
import io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.storage.StockRegion;
import io.github.kpuctajluk.colonyloom.core.supply.CoverageShare;
import io.github.kpuctajluk.colonyloom.core.supply.Demand;
import io.github.kpuctajluk.colonyloom.core.supply.ItemMatcher;
import io.github.kpuctajluk.colonyloom.core.supply.RecipeDefinition;
import io.github.kpuctajluk.colonyloom.core.supply.SupplyPlanner;
import io.github.kpuctajluk.colonyloom.gameplay.production.ProcessDefinition;
import io.github.kpuctajluk.colonyloom.gameplay.production.ProductionCatalog;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.minecraft.content.ContentLoader;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.minecraft.navigation.MinecraftNavigationBackend;
import io.github.kpuctajluk.colonyloom.minecraft.production.MinecraftProductionService;
import io.github.kpuctajluk.colonyloom.minecraft.production.RecipeExecutor;
import io.github.kpuctajluk.colonyloom.minecraft.runtime.CitizenAdmissionService;
import io.github.kpuctajluk.colonyloom.minecraft.storage.NativeItemDescriptor;
import io.github.kpuctajluk.colonyloom.minecraft.storage.StorageService;
import io.github.kpuctajluk.colonyloom.minecraft.storage.MinecraftDeliveryService;
import io.github.kpuctajluk.colonyloom.minecraft.storage.MinecraftSupplyAccess;
import io.github.kpuctajluk.colonyloom.minecraft.storage.StorageTransferExecutor;
import io.github.kpuctajluk.colonyloom.neoforge.NeoForgeChunkAccess;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The real scheduler selects output portions; fixtures supply only terrain, resources and accepted recipes. */
@GameTestHolder("colonyloom")
@PrefixGameTestTemplate(false)
public final class ProductionOutputRoutingGameTests {
    private static final int FIRST_OUTPUT=17,SECOND_OUTPUT=26,ACTIVE_TICKS=20,MAX_TICKS=560;

    @GameTest(template="identity_empty",batch="stage10_split_output",timeoutTicks=600)
    public static void sixPhysicalPlanksFillTwoSixtyTwoStairStacksWithoutFalseCapacity(GameTestHelper helper) {
        run(helper,4,62);
    }

    @GameTest(template="identity_empty",batch="stage10_large_output",timeoutTicks=600)
    public static void wholeBatchAboveStackLimitUsesTwoPhysicalOutputSlots(GameTestHelper helper) {
        run(helper,128,0);
    }

    @GameTest(template="identity_empty",batch="stage10_surplus_veto",timeoutTicks=600)
    public static void actualSurplusTriesAlternativeAfterVetoAndFullBuffersRecover(GameTestHelper helper) {
        run(helper,4,0,1);
    }

    @GameTest(template="identity_empty",batch="stage10_surplus_unreachable",timeoutTicks=600)
    public static void actualSurplusTriesAlternativeAfterInaccessibleReceiver(GameTestHelper helper) {
        run(helper,4,0,2);
    }

    @GameTest(template="identity_empty",batch="stage14_original_production_series",timeoutTicks=6000)
    public static void originalSharedSeriesKeepsDirtyCursorAndCraftsAfterSecondBatch(GameTestHelper helper) {
        runSeries(helper,false);
    }

    @GameTest(template="identity_empty",batch="stage14_delivery_pickup_continuation",timeoutTicks=6000)
    public static void originalNativeKitsContinuePickupWithFourPaidSlotChecksPerTurn(GameTestHelper helper) {
        runSeries(helper,true);
    }
    @GameTest(template="identity_empty",batch="stage14_production_navigation_admission",timeoutTicks=600)
    public static void exhaustedNavigationAdmissionResumesSameOriginalNativeBatch(GameTestHelper helper) {
        run(helper,4,62,0,ContinuationMode.ADMISSION);
    }

    @GameTest(template="identity_empty",batch="stage14_production_stale_before",timeoutTicks=600)
    public static void synchronousDetachBeforeCraftPreservesOriginalNativeKit(GameTestHelper helper) {
        run(helper,4,62,0,ContinuationMode.BEFORE_CRAFT);
    }

    @GameTest(template="identity_empty",batch="stage14_production_stale_after",timeoutTicks=600)
    public static void synchronousDetachAfterCraftRetainsExactNativeRecoveryFact(GameTestHelper helper) {
        run(helper,4,62,0,ContinuationMode.AFTER_CRAFT);
    }

    private enum ContinuationMode { NORMAL,ADMISSION,BEFORE_CRAFT,AFTER_CRAFT }


    private static void runSeries(GameTestHelper helper,boolean partialPickup) {
        var templateOrigin=helper.absolutePos(new BlockPos(1,1,1));
        var origin=partialPickup?new BlockPos((templateOrigin.getX()>>4)<<4,templateOrigin.getY(),templateOrigin.getZ()):templateOrigin;
        var access=new NeoForgeChunkAccess(helper.getLevel().getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime")));
        UUID ticketOwner=UUID.randomUUID();var keys=new ArrayList<ChunkKey>();
        String dimension=helper.getLevel().dimension().location().toString();
        for(int x=(origin.getX()-6)>>4;x<=(origin.getX()+10)>>4;x++)for(int z=(origin.getZ()-6)>>4;z<=(origin.getZ()+10)>>4;z++) {
            var key=new ChunkKey(dimension,x,z);keys.add(key);
            if(!access.acquire(ticketOwner,key,ChunkDemandManager.Readiness.ENTITY_TICKING))throw new IllegalStateException("Series fixture ticket denied");
        }
        SeriesFixture[] fixture={null};boolean[] done={false};int[] ticks={0};
        helper.onEachTick(() -> {
            if(done[0])return;
            try {
                if(++ticks[0]>5960)throw new IllegalStateException("Original series timeout: "+(fixture[0]==null?"chunks":fixture[0].diagnostics()));
                if(fixture[0]==null) {
                    if(!keys.stream().allMatch(key -> access.ready(key,ChunkDemandManager.Readiness.ENTITY_TICKING)))return;
                    fixture[0]=new SeriesFixture(helper,origin,access,partialPickup);
                }
                fixture[0].core.tick(fixture[0].core.serverTick()+1);
                if(!fixture[0].observe())return;
                done[0]=true;fixture[0].close();
                for(var key:keys)access.release(ticketOwner,key,ChunkDemandManager.Readiness.ENTITY_TICKING);
                helper.succeed();
            } catch(RuntimeException|AssertionError failure) {
                done[0]=true;if(fixture[0]!=null)fixture[0].close();
                for(var key:keys)access.release(ticketOwner,key,ChunkDemandManager.Readiness.ENTITY_TICKING);
                throw failure;
            }
        });
    }

    private static void run(GameTestHelper helper,int outputCount,int initialStairs) {
        run(helper,outputCount,initialStairs,0);
    }

    private static void run(GameTestHelper helper,int outputCount,int initialStairs,int fallbackMode) {
        run(helper,outputCount,initialStairs,fallbackMode,ContinuationMode.NORMAL);
    }

    private static void run(GameTestHelper helper,int outputCount,int initialStairs,int fallbackMode,ContinuationMode mode) {
        var origin=helper.absolutePos(new BlockPos(1,1,1));
        var access=new NeoForgeChunkAccess(helper.getLevel().getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime")));
        UUID ticketOwner=UUID.randomUUID(); List<ChunkKey> keys=new ArrayList<>();
        String dimension=helper.getLevel().dimension().location().toString();
        for(int x=(origin.getX()-2)>>4;x<=(origin.getX()+6)>>4;x++) for(int z=(origin.getZ()-2)>>4;z<=(origin.getZ()+6)>>4;z++) {
            var key=new ChunkKey(dimension,x,z); keys.add(key);
            if(!access.acquire(ticketOwner,key,ChunkDemandManager.Readiness.ENTITY_TICKING)) throw new IllegalStateException("Output routing fixture ticket denied");
        }
        Fixture[] fixture={null}; boolean[] done={false}; int[] ticks={0};
        helper.onEachTick(() -> {
            if(done[0]) return;
            try {
                if(++ticks[0]>MAX_TICKS) throw new IllegalStateException("Output routing timeout: "+(fixture[0]==null?"chunks "+keys.stream().map(key->key+" loaded="+access.ready(key,ChunkDemandManager.Readiness.LOADED)+" block="+access.ready(key,ChunkDemandManager.Readiness.BLOCK_TICKING)+" entity="+access.ready(key,ChunkDemandManager.Readiness.ENTITY_TICKING)).toList():fixture[0].diagnostics()));
                if(fixture[0]==null) {
                    if(!keys.stream().allMatch(key -> access.ready(key,ChunkDemandManager.Readiness.ENTITY_TICKING))) return;
                    fixture[0]=new Fixture(helper,origin,access,outputCount,initialStairs,fallbackMode,mode);
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

    private static final class SeriesFixture implements AutoCloseable {
        final GameTestHelper helper;
        final ServerRuntime core;
        final UUID colony=UUID.randomUUID(),checkpoint=UUID.randomUUID();
        final StorageService storage;
        final ChunkDemandManager chunks;
        final CitizenAdmissionService admission;
        final MinecraftDeliveryService delivery;
        final MinecraftProductionService production;
        final NavigationService navigation;
        final SupplyPlanner planner;
        final List<CitizenEntity> citizens=new ArrayList<>();
        final List<UUID> originalDeliveries=new ArrayList<>(),sideDemands=new ArrayList<>();
        final Container workshop,warehouse,receiver,returns;
        final StockRegion sourceLogs,sourceStone;
        final UUID stairOrder,plankOrder,stairIngredient,plankIngredient,root;
        final Budget[] frozenBudgets={Budget.ASSIGNMENT_CANDIDATES,Budget.GRAPH_EXPANSIONS,Budget.NAVIGATION_STARTS,
                Budget.BLUEPRINT_COMPARISONS,Budget.PHYSICAL_ACTIONS,Budget.STORAGE_SLOT_CHECKS,Budget.CHUNK_REQUESTS,
                Budget.VIEW_ROWS,Budget.DIRTY_RESCAN_OBJECTS};
        final int[] frozenQuanta={3,1,1,4,1,60,1,1,1};
        boolean seeded,budgetDenied,budgetWake,deniedPortionObserved;
        int admittedDeliveryTurns;
        int paidCriticalTurns,paidNormalTurns;
        long finishedAt=-1;
        final boolean partialPickup;
        boolean partialPickupObserved;
        UUID stalePickupWork,stalePickupOrder;
        StockRegion stalePickupSlot;
        ItemStack stalePickupBefore;
        int stalePickupSourceCount;
        boolean stalePickupRejected;
        int unrelatedChecks;
        boolean lastSlotPrefixObserved;

        SeriesFixture(GameTestHelper helper,BlockPos origin,NeoForgeChunkAccess access,boolean partialPickup) {
            this.helper=helper;this.partialPickup=partialPickup;var level=helper.getLevel();var server=level.getServer();
            for(int x=-6;x<=10;x++)for(int z=-6;z<=10;z++) {
                var pos=origin.offset(x,0,z);level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
                for(int y=0;y<3;y++)level.setBlockAndUpdate(pos.above(y),Blocks.AIR.defaultBlockState());
            }
            var workshopPos=origin;var warehousePos=origin.west(2);var receiverPos=origin.south(2);var returnPos=origin.east(2);
            if(partialPickup)check((warehousePos.getX()>>4)!=(workshopPos.getX()>>4),"Original pickup fixture lost its distinct physical chunk centers");
            for(var pos:List.of(workshopPos,warehousePos,receiverPos,returnPos))level.setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(workshopPos.east(),Blocks.CRAFTING_TABLE.defaultBlockState());
            workshop=(Container)level.getBlockEntity(workshopPos);warehouse=(Container)level.getBlockEntity(warehousePos);
            receiver=(Container)level.getBlockEntity(receiverPos);returns=(Container)level.getBlockEntity(returnPos);
            warehouse.setItem(0,new ItemStack(Items.OAK_LOG,7));warehouse.setItem(1,new ItemStack(Items.STONE,6));warehouse.setChanged();
            core=ServerRuntime.start(Thread.currentThread());var content=ContentLoader.load(server.getResourceManager(),server.registryAccess());
            core.configureCommands(() -> {},content.professions().values());
            var limits=core.admission().limits();
            for(int i=0;i<frozenBudgets.length;i++)limits=limits.withBudget(frozenBudgets[i],frozenQuanta[i]);
            core.updateLimits(limits);
            core.registry().addColony(new ColonyRuntime(colony,"Original series",new Territory(position(origin).dimension(),origin.getX()-16,origin.getZ()-16,origin.getX()+16,origin.getZ()+16),UUID.randomUUID(),Map.of(),1,1,false,null,false));
            storage=new StorageService(server,core.registry(),core.budgets(),new NeoForgeStorageIdentity());
            storage.register(colony,position(workshopPos),"workshop");var source=storage.register(colony,position(warehousePos),"warehouse");
            storage.register(colony,position(receiverPos),"construction");storage.register(colony,position(returnPos),"return");
            sourceLogs=source.slots().getFirst();sourceStone=source.slots().get(1);
            var workplace=storage.registerWorkshop(colony,position(workshopPos.east()),position(workshopPos));
            citizen(origin.east().south(),"colonyloom:carpenter",workplace.id());
            citizen(origin.north(),"colonyloom:courier",null);
            citizen(origin.west().south(),"colonyloom:courier",null);
            if(partialPickup) {
                // Only the last real courier/return slots can carry a native kit; the
                // original inventories and incompatible property survive every pause.
                for(int slot=0;slot<returns.getContainerSize()-1;slot++)returns.setItem(slot,new ItemStack(Items.COBBLESTONE,64));
                returns.setChanged();
                for(int courier=1;courier<citizens.size();courier++) {
                    var inventory=citizens.get(courier).inventory();
                    for(int slot=0;slot<inventory.getContainerSize()-1;slot++)inventory.setItem(slot,new ItemStack(Items.COBBLESTONE,64));
                    inventory.setChanged();
                }
            }
            chunks=new ChunkDemandManager(core.registry(),core.budgets(),access);admission=new CitizenAdmissionService(server,core,chunks);
            var transfer=new StorageTransferExecutor(server,core.registry(),storage,(action,principal,from,to,amount) -> true,() -> checkpoint,null);
            delivery=new MinecraftDeliveryService(server,core.registry(),storage,transfer,chunks);
            production=new MinecraftProductionService(core.registry(),storage,new RecipeExecutor(server,core.registry(),storage,new NeoForgeRecipeProtection(server),() -> checkpoint,null),chunks);
            NavigationService.GoalAuthority goals=(work,request) -> WorkOrder.DELIVERY.equals(work.typeId())?delivery.current(work,request):production.current(work,request);
            navigation=new NavigationService(core.registry(),core.budgets(),chunks,new MinecraftNavigationBackend(server,core.registry(),chunks,goals),goals);
            delivery.navigation(navigation);production.navigation(navigation);
            var stairs=RecipeDefinition.create("colonyloom:series_oak_stairs",1,"colonyloom:carpenter","minecraft:crafting_table",List.of(new RecipeDefinition.Ingredient(new ItemMatcher("minecraft:oak_planks",null),6)),NativeItemDescriptor.describe(new ItemStack(Items.OAK_STAIRS),level.registryAccess()),4,40);
            var planks=RecipeDefinition.create("colonyloom:series_oak_planks",1,"colonyloom:carpenter","minecraft:crafting_table",List.of(new RecipeDefinition.Ingredient(new ItemMatcher("minecraft:oak_log",null),1)),NativeItemDescriptor.describe(new ItemStack(Items.OAK_PLANKS),level.registryAccess()),4,20);
            var supply=core.registry().supply();var consumer=supply.request(UUID.randomUUID(),colony,UUID.randomUUID(),new ItemMatcher("minecraft:oak_stairs",stairs.output()),16,Demand.GoalKind.CONSUMPTION,position(receiverPos),Lane.NORMAL,0,0);
            root=consumer.id();var order=supply.promiseProduction(root,stairs,4);stairOrder=order.id();
            var ingredient=supply.ingredientDemand(order,0,24,0);stairIngredient=ingredient.id();
            order=supply.promiseProduction(stairIngredient,planks,6);plankOrder=order.id();plankIngredient=supply.ingredientDemand(order,0,6,0).id();
            for(int i=0;i<6;i++)sideDemands.add(supply.request(UUID.randomUUID(),colony,UUID.randomUUID(),new ItemMatcher("minecraft:stone",null),1,Demand.GoalKind.DELIVERY,position(receiverPos),i<2?Lane.CRITICAL:Lane.NORMAL,0,0).id());
            planner=new SupplyPlanner(core.registry(),supply,core.budgets());
            planner.configure(new ProductionCatalog(core.registry(),Map.of(stairs.id(),new ProcessDefinition(stairs),planks.id(),new ProcessDefinition(planks))),new MinecraftSupplyAccess(core.registry(),storage));
            core.scheduler().physicalExecutor(WorkOrder.DELIVERY,delivery);core.scheduler().physicalExecutor(WorkOrder.PRODUCTION,production);
            core.scheduler().beforeWork(this::beforeWork);
            core.scheduler().beforeStep(() -> {
                if(partialPickup) {
                    var work=executingWork(core);
                    if(WorkOrder.DELIVERY.equals(work.typeId())&&work.stage().equals("pickup")) {
                        // Ordinary unrelated native observations spend the first56 checks
                        // of the unchanged sixty-check scheduler limit, not a quota override.
                        while(core.budgets().used(Budget.STORAGE_SLOT_CHECKS)<56) {
                            if(!core.budgets().tryConsume(Budget.STORAGE_SLOT_CHECKS,work.lane()))break;
                            var slot=source.slots().get(2+unrelatedChecks++%(source.slots().size()-2));
                            var observed=storage.readFresh(slot);
                            if(observed.ready())core.registry().storage().index().observe(slot,observed.item(),observed.count(),core.serverTick());
                            else core.registry().storage().index().unknown(slot);
                        }
                        var pickupOrder=supply.deliveryForWork(work.id());
                        var pickupSource=pickupOrder==null?null:storage.locate(pickupOrder.source().storage());
                        var pickupCitizen=work.assignee()==null?null:core.registry().citizen(work.assignee());
                        var pickupEntity=pickupCitizen==null||pickupSource==null?null:storage.currentCitizen(pickupCitizen.citizenId(),pickupCitizen.bindingEpoch(),pickupSource.dimension());
                        if(pickupEntity!=null&&pickupEntity.distanceToSqr(pickupSource.x()+0.5,pickupSource.y()+0.5,pickupSource.z()+0.5)<=9
                                &&core.budgets().used(Budget.STORAGE_SLOT_CHECKS)==56) {
                            partialPickupObserved=true;
                        }
                        if(stalePickupWork==null&&!stalePickupRejected) {
                            var staleOrder=supply.deliveryForWork(work.id());
                            if(staleOrder!=null&&staleOrder.source().equals(sourceLogs)) {
                                var states=(Map<?,?>)fixtureField(delivery,"active");var state=states.get(work.id());
                                if(state!=null) {
                                    var selected=(StockRegion)fixtureField(state,"pickupDestinationSlot");
                                    if(selected!=null) {
                                        var nativeContainer=storage.currentContainer(selected);
                                        check(nativeContainer!=null,"Paid pickup candidate lacks its original native inventory");
                                        stalePickupWork=work.id();stalePickupOrder=staleOrder.id();stalePickupSlot=selected;
                                        stalePickupBefore=nativeContainer.getItem(selected.slot()).copy();
                                        stalePickupSourceCount=warehouse.getItem(sourceLogs.slot()).getCount();
                                        nativeContainer.setItem(selected.slot(),new ItemStack(Items.COBBLESTONE,64));nativeContainer.setChanged();
                                    }
                                }
                            }
                        }
                    }
                }
                var current=supply.production(plankOrder);
                if(!budgetDenied&&current.completedBatches()==2&&!current.batchStarted()&&current.workId()!=null&&!supply.completeProductionKit(plankOrder).isEmpty()) {
                    var work=core.workBoard().work(current.workId());
                    if((work.state()==WorkOrder.State.READY||work.state()==WorkOrder.State.WAITING)&&work.assignee()==null) {
                        // Consume the real assignment quantum; the original scheduler
                        // must produce its BUDGET wait, not a fixture-written wait state.
                        for(int i=core.budgets().used(Budget.ASSIGNMENT_CANDIDATES);i<3;i++)
                            if(!core.budgets().tryConsume(Budget.ASSIGNMENT_CANDIDATES,Lane.NORMAL))break;
                    }
                }
            });
        }

        private void beforeWork(long tick) {
            storage.tick(tick);var supply=core.registry().supply();
            if(budgetDenied&&!budgetWake) {
                var order=supply.production(plankOrder);var work=order.workId()==null?null:core.workBoard().work(order.workId());
                if(work!=null&&work.state()==WorkOrder.State.WAITING&&work.assignee()==null&&work.waitingReason()==WorkOrder.Reason.BUDGET&&!supply.completeProductionKit(plankOrder).isEmpty()) {
                    production.tick();budgetWake=work.state()==WorkOrder.State.READY;
                }
            }
            if(!seeded) {
                var stone=core.registry().storage().index().observation(sourceStone);
                if(stone.ready()&&stone.count()==6) {
                    for(var id:sideDemands)originalDeliveries.add(supply.coverStock(id,sourceStone,stone.item(),1,tick).id());
                    seeded=true;
                }
            }
            if(admittedDeliveryTurns<20) {
                // An actual resident-domain cursor wins every dirty phase except delivery's
                // eighteenth tick. SERVICE-only portions must not erase delivery's weighted
                // CRITICAL/NORMAL debt, and denied scans must retain their original candidates.
                if((tick&1)==0||(tick/2)%9!=7)chunks.tick(tick);
                int before=core.budgets().used(Budget.DIRTY_RESCAN_OBJECTS);
                int criticalBefore=core.budgets().used(Budget.DIRTY_RESCAN_OBJECTS,Lane.CRITICAL);
                int normalBefore=core.budgets().used(Budget.DIRTY_RESCAN_OBJECTS,Lane.NORMAL);
                delivery.tick(tick);
                if(core.budgets().used(Budget.DIRTY_RESCAN_OBJECTS)>before) {
                    admittedDeliveryTurns++;
                    paidCriticalTurns+=core.budgets().used(Budget.DIRTY_RESCAN_OBJECTS,Lane.CRITICAL)-criticalBefore;
                    paidNormalTurns+=core.budgets().used(Budget.DIRTY_RESCAN_OBJECTS,Lane.NORMAL)-normalBefore;
                } else deniedPortionObserved=true;
                admission.tick();navigation.tick(tick);return;
            }
            // Resume the real runtime's nine-consumer dirty rotation for native kit,
            // navigation, repeated crafts and physical delivery after the cursor proof.
            int first=(int)((tick/2)%9);
            for(int offset=0;offset<9;offset++)switch((first+offset)%9) {
                case 0 -> chunks.tick(tick);
                case 1 -> admission.tick();
                case 2 -> navigation.tick(tick);
                case 3 -> core.registry().targetClaims().tick();
                case 4 -> supply.reconcile(tick,core.budgets());
                case 5 -> { }
                case 6 -> planner.tick(tick);
                case 7 -> {
                    int before=core.budgets().used(Budget.DIRTY_RESCAN_OBJECTS);
                    delivery.tick(tick);
                    if(core.budgets().used(Budget.DIRTY_RESCAN_OBJECTS)>before)admittedDeliveryTurns++;
                    else deniedPortionObserved=true;
                }
                case 8 -> {
                    var order=supply.production(plankOrder);var work=order.workId()==null?null:core.workBoard().work(order.workId());
                    boolean waiting=budgetDenied&&work!=null&&work.assignee()==null&&work.state()==WorkOrder.State.WAITING&&work.waitingReason()==WorkOrder.Reason.BUDGET&&!supply.completeProductionKit(plankOrder).isEmpty();
                    production.tick();
                    if(waiting&&work.state()==WorkOrder.State.READY)budgetWake=true;
                }
            }
        }

        private void citizen(BlockPos start,String profession,UUID workplace) {
            var level=helper.getLevel();var entity=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
            if(entity==null)throw new IllegalStateException("Series citizen unavailable");
            UUID id=UUID.randomUUID();entity.initializeIdentity(id,1);entity.moveTo(start.getX()+0.5,start.getY(),start.getZ()+0.5,0,0);
            core.registry().addCitizen(new CitizenRecord(id,colony,entity.getUUID(),1,null,workplace,null,profession,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.READY,0,Map.of("food",1200L),position(start),1),record -> {
                if(!level.addFreshEntity(entity))throw new IllegalStateException("Series citizen spawn refused");
            });
            core.bindings().observe(id,entity.getUUID(),1);entity.setQuarantined(false);citizens.add(entity);
        }

        boolean observe() {
            var supply=core.registry().supply();
            if(partialPickup) {
                var states=(Map<?,?>)fixtureField(delivery,"active");
                for(var state:states.values()) {
                    var cargoSlot=(StockRegion)fixtureField(state,"pickupCargoSlot");
                    if(cargoSlot!=null) {
                        check(cargoSlot.slot()==CitizenEntity.INVENTORY_SIZE-1&&citizens.stream().skip(1).anyMatch(citizen ->
                                citizen.citizenId().equals(cargoSlot.storage().identity())&&citizen.bindingEpoch()==cargoSlot.storage().bindingEpoch()),
                                "Bounded pickup skipped its original last native cargo slot");
                        lastSlotPrefixObserved=true;
                    }
                }
                for(int courier=1;courier<citizens.size();courier++) {
                    var inventory=citizens.get(courier).inventory();
                    for(int slot=0;slot<inventory.getContainerSize()-1;slot++)
                        check(inventory.getItem(slot).is(Items.COBBLESTONE)&&inventory.getItem(slot).getCount()==64,"Pickup changed original incompatible courier property");
                }
                for(int slot=0;slot<returns.getContainerSize()-1;slot++)
                    check(returns.getItem(slot).is(Items.COBBLESTONE)&&returns.getItem(slot).getCount()==64,"Pickup changed original incompatible return property");
            }
            if(stalePickupWork!=null&&!stalePickupRejected) {
                var work=core.workBoard().work(stalePickupWork);
                if(work.state()==WorkOrder.State.WAITING&&work.waitingReason()==WorkOrder.Reason.CAPACITY) {
                    check(!supply.hasCargo(stalePickupOrder)&&supply.delivery(stalePickupOrder).transferred()==0
                            &&warehouse.getItem(sourceLogs.slot()).getCount()==stalePickupSourceCount,
                            "A stale paid destination candidate authorized extraction before fresh capacity validation");
                    var nativeContainer=storage.currentContainer(stalePickupSlot);
                    check(nativeContainer!=null&&nativeContainer.getItem(stalePickupSlot.slot()).is(Items.COBBLESTONE)
                            &&nativeContainer.getItem(stalePickupSlot.slot()).getCount()==64,"Stale target property changed during denied pickup");
                    nativeContainer.setItem(stalePickupSlot.slot(),stalePickupBefore);nativeContainer.setChanged();stalePickupRejected=true;
                }
            }
            for(int i=0;i<frozenBudgets.length;i++)check(core.budgets().used(frozenBudgets[i])<=frozenQuanta[i],"Series exceeded frozen "+frozenBudgets[i]);
            check(core.budgets().limits().maxManagedNanos()==5_000_000L,"Series raised managed deadline");
            for(var citizen:citizens)check(!citizen.isRemoved()&&core.bindings().activeEntity(citizen.citizenId()).orElseThrow().equals(citizen.getUUID()),"Series replaced an original native citizen");
            check(supply.productionOrders().size()==2,"Series replaced or duplicated original production orders");
            var planks=supply.production(plankOrder);var stairs=supply.production(stairOrder);
            var originalWork=planks.workId()==null?null:core.workBoard().work(planks.workId());
            if(originalWork!=null&&planks.completedBatches()==2&&originalWork.assignee()==null&&originalWork.state()==WorkOrder.State.WAITING&&originalWork.waitingReason()==WorkOrder.Reason.BUDGET)budgetDenied=true;
            if(admittedDeliveryTurns>=20) {
                check(seeded&&originalDeliveries.size()==6,"Series dirty proof lacks its six original reserved orders");
                if(!originalDeliveries.stream().allMatch(id -> supply.delivery(id).workId()!=null))
                    check(false,"Paid dirty cursor skipped an original reserved order across eighteen-tick rotation: "+deliveryDiagnostics());
            }
            if(!stairs.terminal()||supply.demand(root).snapshot().allocated()!=16||sideDemands.stream().anyMatch(id -> supply.demand(id).snapshot().fulfilled()!=1))return false;
            check(seeded&&deniedPortionObserved&&admittedDeliveryTurns>=8,"Series did not exercise paid dirty rotation");
            if(partialPickup)check(partialPickupObserved&&lastSlotPrefixObserved&&stalePickupRejected,
                    "Series did not exercise last-slot continuation and fresh rejection across original chunk centers");
            check(budgetDenied&&budgetWake,"Original complete third kit did not wake its real unassigned budget wait");
            check(planks.terminal()&&planks.completedBatches()==6&&stairs.completedBatches()==4,"Original series did not commit every successive batch");
            check(supply.demand(plankIngredient).snapshot().fulfilled()==6&&supply.demand(stairIngredient).snapshot().fulfilled()==24,"Native input obligations did not balance successive batches");
            check(count(Items.OAK_LOG)==1&&count(Items.OAK_PLANKS)==0&&count(Items.OAK_STAIRS)==16&&count(Items.STONE)==6,"Successive native series broke whole-world material balance");
            check(Fixture.countStairs(receiver)==16,"Series output skipped real physical delivery");
            check(originalDeliveries.stream().allMatch(id -> supply.delivery(id).terminal()&&supply.delivery(id).transferred()==1),"Dirty rotation starved an original reserved delivery");
            var effects=core.registry().effects().snapshots();
            for(var order:List.of(planks,stairs)) {
                var facts=effects.stream().filter(effect -> effect.craft()!=null&&effect.craft().productionId().equals(order.id())).toList();
                check(facts.size()==order.completedBatches()&&facts.stream().allMatch(effect -> effect.state()==EffectRecord.State.OBSERVED&&effect.craft().complete()
                        &&effect.workId().equals(order.workId())&&effect.bindingEpoch()==1&&effect.citizenId().equals(citizens.getFirst().citizenId())),"Series lacks exact original-worker native craft facts");
                check(facts.stream().map(effect -> effect.craft().batchOrdinal()).distinct().count()==order.completedBatches(),"Series replayed a physical batch ordinal");
            }
            var logTransfers=effects.stream().filter(effect -> effect.transfer()!=null&&effect.itemId().equals("minecraft:oak_log")).toList();
            check(logTransfers.stream().filter(effect -> effect.transfer().source().equals(sourceLogs)).mapToInt(effect -> effect.transfer().inserted()).sum()==6
                    &&logTransfers.stream().filter(effect -> effect.transfer().destination().storage().equals(planks.workshopStorage())).mapToInt(effect -> effect.transfer().inserted()).sum()==6,"Series bypassed exact native pickup and delivery of six successive log kits");
            check(effects.stream().filter(effect -> effect.transfer()!=null&&effect.itemId().equals("minecraft:oak_stairs")&&effect.transfer().source().storage().equals(stairs.workshopStorage())).mapToInt(effect -> effect.transfer().inserted()).sum()==16,"Series replayed or skipped real output pickup");
            check(core.registry().storage().reservations().entries().isEmpty(),"Finished original series retained stock claims");
            check(core.registry().storage().allocations().entries().stream().mapToLong(entry -> entry.count()).sum()==16,"Series allocations differ from physically delivered consumer stock");
            if(finishedAt<0)finishedAt=core.serverTick();return core.serverTick()-finishedAt>=40;
        }

        private static WorkOrder executingWork(ServerRuntime core) {
            try {
                var executing=core.scheduler().getClass().getDeclaredField("executing");executing.setAccessible(true);
                var node=executing.get(core.scheduler());var work=node.getClass().getDeclaredField("work");work.setAccessible(true);
                return (WorkOrder)work.get(node);
            } catch(ReflectiveOperationException failure) {throw new IllegalStateException("Native scheduler observation unavailable",failure);}
        }
        private static Object fixtureField(Object object,String name) {
            try {var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
            catch(ReflectiveOperationException failure) {throw new IllegalStateException("Native pickup observation unavailable",failure);}
        }

        private int count(net.minecraft.world.item.Item item) {
            int total=0;for(var container:List.of(workshop,warehouse,receiver,returns))for(int slot=0;slot<container.getContainerSize();slot++)if(container.getItem(slot).is(item))total+=container.getItem(slot).getCount();
            for(var citizen:citizens)for(int slot=0;slot<citizen.inventory().getContainerSize();slot++)if(citizen.inventory().getItem(slot).is(item))total+=citizen.inventory().getItem(slot).getCount();return total;
        }
        private void check(boolean condition,String message) {helper.assertTrue(condition,message);}
        private WorldPosition position(BlockPos pos) {return new WorldPosition(helper.getLevel().dimension().location().toString(),pos.getX(),pos.getY(),pos.getZ());}
        private String deliveryDiagnostics() {
            var supply=core.registry().supply();
            return "paid="+paidCriticalTurns+":"+paidNormalTurns+" original="+originalDeliveries.stream().map(supply::delivery).toList();
        }
        String diagnostics() {
            var supply=core.registry().supply();return "production="+supply.productionOrders()+" inputs="+supply.demand(plankIngredient).snapshot()+","+supply.demand(stairIngredient).snapshot()+" works="+core.workBoard().works().stream().map(work -> work.typeId()+":"+work.state()+":"+work.waitingReason()+":"+work.stage()).toList()+" turns="+admittedDeliveryTurns+" wake="+budgetDenied+":"+budgetWake+" delivery="+deliveryDiagnostics();
        }
        @Override public void close() {
            planner.close();delivery.close();production.close();navigation.close();admission.close();chunks.close();
            for(var citizen:citizens)citizen.remove(Entity.RemovalReason.DISCARDED);core.beginStopping();core.stop();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final ServerRuntime core;
        final UUID colony=UUID.randomUUID(),owner=UUID.randomUUID(),citizen=UUID.randomUUID(),checkpoint=UUID.randomUUID();
        final UUID orderId,rootId,ingredientId;
        final Container barrel;
        final CitizenEntity producer;
        final StorageService storage;
        final StockRegion input,firstOutput,secondOutput;
        final ChunkDemandManager chunks;
        final CitizenAdmissionService admission;
        final MinecraftProductionService production;
        final NavigationService navigation;
        final int outputCount,initialStairs;
        final int fallbackMode;
        final MinecraftDeliveryService delivery;
        final Container firstReceiver,alternativeReceiver;
        final BlockPos firstReceiverPos,alternativeReceiverPos;
        CitizenEntity courier;
        UUID surplusId;
        boolean filled,capacityObserved,reopened,receiverFailureObserved,alternativeOpened;
        long fullAt;
        int vetoes;
        boolean allocated,begun;
        long startedActive,completedAt=-1;
        final ContinuationMode mode;
        AdmissionLedger.Lease navigationBlock;
        UUID originalWork;
        boolean navigationDenied,navigationReleased,detached;
        long deniedRemaining;

        Fixture(GameTestHelper helper,BlockPos origin,NeoForgeChunkAccess access,int outputCount,int initialStairs,int fallbackMode,ContinuationMode mode) {
            this.helper=helper; this.outputCount=outputCount; this.initialStairs=initialStairs;this.fallbackMode=fallbackMode;this.mode=mode;
            var level=helper.getLevel(); var server=level.getServer();
            for(int x=-4;x<=10;x++) for(int z=-4;z<=10;z++) {
                var pos=origin.offset(x,0,z); level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
                for(int y=0;y<3;y++) level.setBlockAndUpdate(pos.above(y),Blocks.AIR.defaultBlockState());
            }
            var barrelPos=origin.east(2); var tablePos=barrelPos.east(); var start=origin.south(3);
            level.setBlockAndUpdate(barrelPos,Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(tablePos,Blocks.CRAFTING_TABLE.defaultBlockState());
            barrel=(Container)level.getBlockEntity(barrelPos);
            for(int slot=0;slot<barrel.getContainerSize();slot++) barrel.setItem(slot,new ItemStack(Items.STONE,64));
            barrel.setItem(0,new ItemStack(Items.OAK_PLANKS,6));
            barrel.setItem(FIRST_OUTPUT,initialStairs==0?ItemStack.EMPTY:new ItemStack(Items.OAK_STAIRS,initialStairs));
            barrel.setItem(SECOND_OUTPUT,initialStairs==0?ItemStack.EMPTY:new ItemStack(Items.OAK_STAIRS,initialStairs));
            barrel.setChanged();
            core=ServerRuntime.start(Thread.currentThread());
            var content=ContentLoader.load(server.getResourceManager(),server.registryAccess());
            core.configureCommands(() -> {},content.professions().values());
            core.updateLimits(core.admission().limits().withMaxManagedNanos(100_000_000L));
            core.registry().addColony(new ColonyRuntime(colony,"Physical split output",new Territory(position(origin).dimension(),origin.getX()-16,origin.getZ()-16,origin.getX()+16,origin.getZ()+16),owner,Map.of(),1,1,false,null,false));
            storage=new StorageService(server,core.registry(),core.budgets(),new NeoForgeStorageIdentity());
            var registration=storage.register(colony,position(barrelPos),"workshop");
            input=registration.slots().getFirst(); firstOutput=registration.slots().get(FIRST_OUTPUT); secondOutput=registration.slots().get(SECOND_OUTPUT);
            firstReceiverPos=origin.south(fallbackMode==2?6:2);alternativeReceiverPos=origin.east(5).south(3);
            if(fallbackMode!=0) {
                level.setBlockAndUpdate(firstReceiverPos,Blocks.BARREL.defaultBlockState());
                level.setBlockAndUpdate(alternativeReceiverPos,Blocks.BARREL.defaultBlockState());
                storage.register(colony,position(firstReceiverPos),"warehouse");storage.register(colony,position(alternativeReceiverPos),"return");
            }
            firstReceiver=fallbackMode==0?null:(Container)level.getBlockEntity(firstReceiverPos);
            alternativeReceiver=fallbackMode==0?null:(Container)level.getBlockEntity(alternativeReceiverPos);
            var workshop=storage.registerWorkshop(colony,position(tablePos),position(barrelPos));
            producer=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
            if(producer==null) throw new IllegalStateException("Citizen unavailable");
            producer.initializeIdentity(citizen,1); producer.moveTo(start.getX()+0.5,start.getY(),start.getZ()+0.5,0,0);
            core.registry().addCitizen(new CitizenRecord(citizen,colony,producer.getUUID(),1,null,workshop.id(),null,"colonyloom:carpenter",Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.READY,0,Map.of("food",1200L),position(start),1),record -> {
                if(!level.addFreshEntity(producer)) throw new IllegalStateException("Producer spawn refused");
            });
            core.bindings().observe(citizen,producer.getUUID(),1); producer.setQuarantined(false);
            chunks=new ChunkDemandManager(core.registry(),core.budgets(),access);
            admission=new CitizenAdmissionService(server,core,chunks);
            if(fallbackMode!=0) {
                UUID courierId=UUID.randomUUID();var courierStart=origin.east(5).south(5);
                courier=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
                if(courier==null)throw new IllegalStateException("Courier unavailable");
                courier.initializeIdentity(courierId,1);courier.moveTo(courierStart.getX()+0.5,courierStart.getY(),courierStart.getZ()+0.5,0,0);
                core.registry().addCitizen(new CitizenRecord(courierId,colony,courier.getUUID(),1,null,null,null,"colonyloom:courier",Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.READY,0,Map.of("food",1200L),position(courierStart),1),record -> {
                    if(!level.addFreshEntity(courier))throw new IllegalStateException("Courier spawn refused");
                });
                core.bindings().observe(courierId,courier.getUUID(),1);courier.setQuarantined(false);
            }
            var transfer=new StorageTransferExecutor(server,core.registry(),storage,(action,principal,source,destination,amount) -> {
                if(fallbackMode==1&&action.target().equals(position(firstReceiverPos))) {vetoes++;return false;}
                return true;
            },() -> checkpoint,null);
            delivery=new MinecraftDeliveryService(server,core.registry(),storage,transfer,chunks);
            production=new MinecraftProductionService(core.registry(),storage,new RecipeExecutor(server,core.registry(),storage,new NeoForgeRecipeProtection(server),() -> checkpoint,(point,action) -> {
                boolean boundary=mode==ContinuationMode.BEFORE_CRAFT&&point==RecipeExecutor.FaultPoint.BEFORE_EFFECT
                        ||mode==ContinuationMode.AFTER_CRAFT&&point==RecipeExecutor.FaultPoint.AFTER_FACT_BEFORE_NOTIFY;
                if(!boundary||detached)return;
                var batch=supplyOrder();originalWork=batch.workId();
                check(batch.batchStarted()&&batch.citizenId().equals(citizen)&&batch.completedBatches()==0,"Callback did not target the original begun batch");
                core.workBoard().releaseAssignment(originalWork);detached=true;
            }),chunks);
            NavigationService.GoalAuthority goals=(work,request) -> WorkOrder.DELIVERY.equals(work.typeId())?delivery.current(work,request):production.current(work,request);
            navigation=new NavigationService(core.registry(),core.budgets(),chunks,new MinecraftNavigationBackend(server,core.registry(),chunks,goals),goals);
            production.navigation(navigation);delivery.navigation(navigation);
            var recipe=RecipeDefinition.create(outputCount==4?"colonyloom:split_oak_stairs":"colonyloom:large_oak_stairs",1,"colonyloom:carpenter","minecraft:crafting_table",List.of(new RecipeDefinition.Ingredient(new ItemMatcher("minecraft:oak_planks",null),6)),NativeItemDescriptor.describe(new ItemStack(Items.OAK_STAIRS),level.registryAccess()),outputCount,ACTIVE_TICKS);
            var supply=core.registry().supply();
            var root=supply.request(UUID.randomUUID(),colony,UUID.randomUUID(),new ItemMatcher("minecraft:oak_stairs",recipe.output()),outputCount,Demand.GoalKind.CONSUMPTION,position(barrelPos),Lane.NORMAL,0,0);
            rootId=root.id(); var order=supply.promiseProduction(rootId,recipe,1); orderId=order.id();
            ingredientId=supply.ingredientDemand(order,0,6,0).id();
            core.scheduler().physicalExecutor(WorkOrder.PRODUCTION,production);
            core.scheduler().physicalExecutor(WorkOrder.DELIVERY,delivery);
            core.scheduler().beforeWork(tick -> {
                chunks.tick(tick); admission.tick(); navigation.tick(tick); storage.tick(tick);
                supply.reconcile(tick,core.budgets());
                // Allocate only measured native stock; production/controller/scheduler own all work and output routing.
                var observed=core.registry().storage().index().observation(input);
                if(!allocated && observed.ready() && observed.count()==6 && observed.item().itemId().equals("minecraft:oak_planks")) {
                    supply.allocateStock(ingredientId,input,observed.item(),6,tick); allocated=true;
                }
                production.tick();
                if(fallbackMode!=0)delivery.tick(tick);
            });
            core.scheduler().beforeStep(() -> {
                if(mode!=ContinuationMode.ADMISSION||navigationBlock!=null||navigationReleased)return;
                var batch=supplyOrder();if(!batch.batchStarted()||!begun)return;
                var work=core.workBoard().work(batch.workId());
                if(!citizen.equals(work.assignee()))return;
                originalWork=work.id();deniedRemaining=batch.remainingActiveTicks();
                navigation.cancel(originalWork);
                int remaining=core.admission().limits().resource(Resource.CACHE_ENTRIES_PER_OWNER)
                        -core.admission().used(originalWork,Resource.CACHE_ENTRIES_PER_OWNER);
                navigationBlock=core.admission().reserve(originalWork,Lane.NORMAL,Map.of(Resource.CACHE_ENTRIES,remaining,Resource.CACHE_ENTRIES_PER_OWNER,remaining));
            });
        }

        boolean observe() {
            var supply=core.registry().supply(); var order=supply.production(orderId);
            if(mode==ContinuationMode.ADMISSION&&!navigationReleased&&originalWork!=null) {
                var work=core.workBoard().work(originalWork);
                if(work.state()!=WorkOrder.State.WAITING||work.waitingReason()!=WorkOrder.Reason.STATE_LIMIT)return false;
                check(originalWork.equals(order.workId())&&citizen.equals(work.assignee())
                        &&originalWork.equals(core.registry().citizen(citizen).assignedWorkId()),"Navigation denial lost the exact assigned producer");
                check(order.batchStarted()&&citizen.equals(order.citizenId())&&order.completedBatches()==0&&order.batches()==1
                        &&order.remainingActiveTicks()==deniedRemaining,"Navigation denial replaced or advanced its original begun batch");
                check(barrel.getItem(0).is(Items.OAK_PLANKS)&&barrel.getItem(0).getCount()==6
                        &&stairs(FIRST_OUTPUT)==initialStairs&&stairs(SECOND_OUTPUT)==initialStairs
                        &&supply.completeProductionKit(orderId).size()==1,"Navigation denial expended or released the native allocated kit");
                check(core.registry().effects().snapshots().isEmpty(),"Denied navigation published physical craft evidence");
                navigationDenied=true;navigationBlock.close();navigationBlock=null;navigationReleased=true;return false;
            }
            if(detached)return assertDetached(order);
            if(order.batchStarted() && !begun) {
                begun=true; startedActive=core.registry().citizen(citizen).activeTimeTicks();
                check(order.remainingActiveTicks()==ACTIVE_TICKS && order.completedBatches()==0,"Batch did not begin on its real active-time clock");
                check(order.citizenId().equals(citizen) && core.registry().citizen(citizen).assignedWorkId().equals(order.workId()) && navigation.atTarget(order.workId()),"Batch bypassed real assignment/navigation");
                check(barrel.getItem(0).is(Items.OAK_PLANKS) && barrel.getItem(0).getCount()==6 && stairs(FIRST_OUTPUT)==initialStairs && stairs(SECOND_OUTPUT)==initialStairs,"Batch spent resources before its processing duration");
                if(fallbackMode!=0)supply.cancel(rootId);
            }
            if(fallbackMode!=0)return observeSurplus();
            if(!order.terminal()) return false;
            assertComplete();
            if(completedAt<0) completedAt=core.serverTick();
            return core.serverTick()-completedAt>=40;
        }

        private void assertComplete() {
            var supply=core.registry().supply(); var order=supply.production(orderId);
            check(begun && core.registry().citizen(citizen).activeTimeTicks()-startedActive>=ACTIVE_TICKS,"Production skipped the admitted chunk active-time duration");
            check(order.batches()==0 && order.completedBatches()==1 && core.workBoard().work(order.workId()).state()==WorkOrder.State.COMPLETED,"Physical batch did not commit once");
            if(mode==ContinuationMode.ADMISSION)check(navigationDenied&&navigationReleased&&originalWork.equals(order.workId())
                    &&producer.getUUID().equals(core.registry().citizen(citizen).entityId())&&producer.bindingEpoch()==1,
                    "Capacity recovery replaced the original production work, batch or native embodiment");
            check(barrel.getItem(0).isEmpty() && stairs(FIRST_OUTPUT)==64 && stairs(SECOND_OUTPUT)==64,"Whole batch did not spend six physical planks and fill exactly 64+64 physical stairs");
            for(int slot=1;slot<barrel.getContainerSize();slot++) if(slot!=FIRST_OUTPUT && slot!=SECOND_OUTPUT) {
                check(barrel.getItem(slot).is(Items.STONE) && barrel.getItem(slot).getCount()==64,"Production changed an incompatible full barrel slot");
            }
            check(producer.inventory().isEmpty(),"Producer fabricated or retained inventory output");
            var effects=core.registry().effects().snapshots();
            check(effects.size()==1 && effects.getFirst().kind()==ActionContext.Kind.RECIPE_CRAFT && effects.getFirst().state()==EffectRecord.State.OBSERVED,"Batch lacks exactly one observed typed native craft");
            var fact=effects.getFirst().craft();
            check(fact!=null && fact.complete() && fact.phase()==EffectRecord.CraftPhase.FACT_OBSERVED && fact.productionId().equals(orderId) && fact.batchOrdinal()==0 && fact.outputCount()==outputCount,"Observed fact differs from committed pinned batch");
            check(fact.inputs().size()==1 && fact.inputs().getFirst().slot().equals(input) && fact.inputs().getFirst().beforeCount()==6 && fact.inputs().getFirst().afterCount()==0 && fact.inputs().getFirst().amount()==6,"Typed craft did not observe six spent physical planks");
            check(fact.outputs().size()==2 && fact.outputs().get(0).slot().equals(firstOutput) && fact.outputs().get(1).slot().equals(secondOutput) && fact.outputs().stream().allMatch(portion -> portion.beforeCount()==initialStairs && portion.afterCount()==64 && portion.amount()==64-initialStairs),"Service did not select both compatible output slots across its bounded scan");
            var ingredient=supply.demand(ingredientId).snapshot();
            check(ingredient.fulfilled()==6 && ingredient.allocated()==0 && supply.demandShares(ingredientId).stream().allMatch(share -> share.stage()==CoverageShare.Stage.FULFILLED),"Native ingredient expense was not logically committed");
            var shares=supply.demandShares(rootId);
            var root=supply.demand(rootId).snapshot();
            check(shares.size()==2 && shares.stream().allMatch(share -> share.stage()==CoverageShare.Stage.ALLOCATED && (share.slot().equals(firstOutput) || share.slot().equals(secondOutput)) && share.quantity()==64-initialStairs) && shares.stream().mapToLong(CoverageShare::quantity).sum()==outputCount && root.allocated()==outputCount && root.covered()==0,"Output promise did not become exact physical split allocations");
            var allocations=core.registry().storage().allocations();
            check(allocations.entries().size()==2 && shares.stream().allMatch(share -> {
                var allocation=allocations.get(share.obligationId());
                return allocation!=null && allocation.ownerId().equals(root.ownerId()) && allocation.slot().equals(share.slot()) && allocation.item().equals(share.item()) && allocation.count()==share.quantity();
            }) && core.registry().storage().reservations().entries().isEmpty(),"Committed output allocations differ from exact native portions or retain ingredient claims");
            check(core.registry().storage().index().observation(input).count()==0 && core.registry().storage().index().observation(firstOutput).count()==64 && core.registry().storage().index().observation(secondOutput).count()==64,"Committed stock index disagrees with real barrel");
        }
        private io.github.kpuctajluk.colonyloom.core.production.ProductionOrder supplyOrder() {
            return core.registry().supply().production(orderId);
        }

        private boolean assertDetached(io.github.kpuctajluk.colonyloom.core.production.ProductionOrder order) {
            check(begun&&originalWork.equals(order.workId())&&order.batchStarted()&&order.completedBatches()==0&&order.batches()==1,
                    "Stale callback committed or replaced the original production continuation");
            check(core.registry().citizen(citizen).assignedWorkId()==null&&core.workBoard().work(originalWork).assignee()==null,
                    "Stale craft republished its detached assignment");
            var effects=core.registry().effects().snapshots();
            if(mode==ContinuationMode.BEFORE_CRAFT) {
                check(barrel.getItem(0).is(Items.OAK_PLANKS)&&barrel.getItem(0).getCount()==6
                        &&stairs(FIRST_OUTPUT)==initialStairs&&stairs(SECOND_OUTPUT)==initialStairs&&effects.isEmpty(),
                        "Before-effect stale authority expended native property or retained a fabricated effect");
                check(core.registry().supply().demand(ingredientId).snapshot().allocated()==6,
                        "Before-effect detach discarded the exact native kit allocation");
            } else {
                check(barrel.getItem(0).isEmpty()&&stairs(FIRST_OUTPUT)==64&&stairs(SECOND_OUTPUT)==64,
                        "After-effect detach replayed or compensated native craft");
                check(effects.size()==1&&effects.getFirst().state()==EffectRecord.State.AMBIGUOUS
                        &&effects.getFirst().craft()!=null&&effects.getFirst().craft().complete()
                        &&effects.getFirst().craft().productionId().equals(orderId)&&effects.getFirst().workId().equals(originalWork)
                        &&effects.getFirst().craft().inputs().getFirst().beforeCount()==6
                        &&effects.getFirst().craft().inputs().getFirst().afterCount()==0
                        &&core.registry().colony(colony).recoveryBlocked(),"After-effect detach lost its exact native recovery fact");
            }
            return true;
        }


        private boolean observeSurplus() {
            var supply=core.registry().supply();
            if(surplusId==null) {
                var demand=supply.demands().stream().map(Demand::snapshot).filter(d -> supply.productionSurplus(d.id())).findFirst().orElse(null);
                if(demand==null)return false;
                surplusId=demand.id();supply.routeProductionSurplus(surplusId,position(firstReceiverPos));
            }
            var orders=supply.deliveries().stream().filter(order -> order.ownerDemandId().equals(surplusId)).toList();
            if(!filled&&orders.stream().anyMatch(order -> supply.hasCargo(order.id()))) {
                fillReceiver(firstReceiver);fillReceiver(alternativeReceiver);filled=true;fullAt=core.serverTick();
            }
            if(filled&&!reopened) {
                check(supply.demand(surplusId).snapshot().covered()==4&&supply.demand(surplusId).snapshot().fulfilled()==0,"Full buffers lost actual surplus coverage");
                check(countStairs(courier.inventory())==4,"Full buffers lost physical courier cargo");
                var blockedOrder=orders.stream().filter(order -> order.workId()!=null&&core.workBoard().work(order.workId()).waitingReason()==WorkOrder.Reason.CAPACITY).findFirst().orElse(null);
                if(blockedOrder!=null) {
                    capacityObserved=true;var buffer=delivery.waitingBuffer(blockedOrder.workId());
                    check(buffer!=null&&buffer.position()!=null&&java.util.Set.of(position(firstReceiverPos),position(alternativeReceiverPos)).contains(buffer.position()),
                            "Full native buffers did not expose an exact blocked physical receiver");
                    check(courier.citizenId().equals(blockedOrder.citizenId())&&core.workBoard().work(blockedOrder.workId()).assignee().equals(courier.citizenId()),
                            "Capacity wait released its only loaded courier for another pickup");
                }
                if(!capacityObserved||core.serverTick()-fullAt<20)return false;
                firstReceiver.clearContent();firstReceiver.setChanged();reopened=true;
                if(fallbackMode==2)for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++) {
                    if(x==0&&z==0)continue;
                    var blocked=firstReceiverPos.offset(x,0,z);
                    helper.getLevel().setBlockAndUpdate(blocked,Blocks.STONE.defaultBlockState());
                    helper.getLevel().setBlockAndUpdate(blocked.above(),Blocks.STONE.defaultBlockState());
                }
            }
            receiverFailureObserved|=orders.stream().filter(order -> order.workId()!=null).anyMatch(order -> core.workBoard().work(order.workId()).waitingReason()==(fallbackMode==1?WorkOrder.Reason.PERMISSION_DENIED:WorkOrder.Reason.UNREACHABLE));
            if(reopened&&receiverFailureObserved&&!alternativeOpened) {alternativeReceiver.clearContent();alternativeReceiver.setChanged();alternativeOpened=true;}
            if(supply.demand(surplusId).snapshot().status()!=Demand.Status.COMPLETED)return false;
            check(filled&&capacityObserved&&reopened&&receiverFailureObserved,"Surplus skipped physical blocked-receiver recovery");
            check(fallbackMode!=1||vetoes>0,"Protection hook did not reject first actual receiver");
            check(firstReceiver.isEmpty()&&countStairs(alternativeReceiver)==4&&courier.inventory().isEmpty(),"Surplus did not reach only alternative receiver exactly once");
            check(stairs(FIRST_OUTPUT)+stairs(SECOND_OUTPUT)==2*initialStairs&&barrel.getItem(0).isEmpty(),"Surplus delivery duplicated craft output or inputs");
            var demand=supply.demand(surplusId).snapshot();check(demand.deliveredTotal()==4&&demand.fulfilled()==4&&demand.covered()==0,"Surplus physical delivery committed wrong amount");
            check(core.registry().effects().snapshots().stream().filter(effect -> effect.kind()==ActionContext.Kind.RECIPE_CRAFT).count()==1,"Surplus recovery replayed physical craft");
            if(completedAt<0)completedAt=core.serverTick();return core.serverTick()-completedAt>=40;
        }

        private static void fillReceiver(Container receiver) {
            for(int slot=0;slot<receiver.getContainerSize();slot++)receiver.setItem(slot,new ItemStack(Items.STONE,64));receiver.setChanged();
        }
        private static int countStairs(Container receiver) {
            int count=0;for(int slot=0;slot<receiver.getContainerSize();slot++)if(receiver.getItem(slot).is(Items.OAK_STAIRS))count+=receiver.getItem(slot).getCount();return count;
        }

        private int stairs(int slot) {var stack=barrel.getItem(slot); return stack.is(Items.OAK_STAIRS)?stack.getCount():stack.isEmpty()?0:-1;}
        private void check(boolean condition,String message) {helper.assertTrue(condition,message);}
        private WorldPosition position(BlockPos pos) {return new WorldPosition(helper.getLevel().dimension().location().toString(),pos.getX(),pos.getY(),pos.getZ());}
        String diagnostics() {
            var order=core.registry().supply().production(orderId);
            var work=order.workId()==null?null:core.workBoard().work(order.workId());
            String surplus="";
            if(fallbackMode!=0) {
                var supply=core.registry().supply();var demand=surplusId==null?null:supply.demand(surplusId).snapshot();
                var deliveries=supply.deliveries().stream().filter(delivery -> surplusId!=null&&delivery.ownerDemandId().equals(surplusId)).map(delivery -> {
                    var cargoWork=delivery.workId()==null?null:core.workBoard().work(delivery.workId());
                    return delivery.state()+":"+delivery.transferred()+" dest="+delivery.destination()+" shares="+supply.orderShares(delivery.id()).stream().map(share -> share.stage()+":"+share.quantity()).toList()+" work="+(cargoWork==null?"none":cargoWork.state()+":"+cargoWork.stage()+":"+cargoWork.waitingReason()+" nav="+navigation.state(cargoWork.id())+":"+navigation.reason(cargoWork.id()));
                }).toList();
                surplus=" surplus="+(demand==null?"none":demand.status()+":"+demand.covered()+":"+demand.fulfilled()+":"+demand.deliveredTotal())+" deliveries="+deliveries+" courier="+courier.position()+":"+countStairs(courier.inventory())+" receivers="+countStairs(firstReceiver)+":"+countStairs(alternativeReceiver)+" flags="+filled+":"+capacityObserved+":"+reopened+":"+receiverFailureObserved+":"+alternativeOpened+" vetoes="+vetoes;
            }
            return "order="+order.state()+" remaining="+order.remainingActiveTicks()+" active="+core.registry().citizen(citizen).activeTimeTicks()+" work="+(work==null?"none":work.state()+":"+work.waitingReason()+" nav="+navigation.state(work.id()))+" native="+barrel.getItem(0)+","+barrel.getItem(FIRST_OUTPUT)+","+barrel.getItem(SECOND_OUTPUT)+surplus;
        }
        @Override public void close() {
            if(navigationBlock!=null){navigationBlock.close();navigationBlock=null;}
            delivery.close();production.close(); navigation.close(); admission.close(); chunks.close();
            if(courier!=null)courier.remove(Entity.RemovalReason.DISCARDED);
            producer.remove(Entity.RemovalReason.DISCARDED); core.beginStopping(); core.stop();
        }
    }
}
