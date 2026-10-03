package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.action.EffectRecord;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.navigation.NavigationService;
import io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.storage.StockRegion;
import io.github.kpuctajluk.colonyloom.core.supply.CoverageShare;
import io.github.kpuctajluk.colonyloom.core.supply.Demand;
import io.github.kpuctajluk.colonyloom.core.supply.ItemMatcher;
import io.github.kpuctajluk.colonyloom.core.supply.RecipeDefinition;
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

    private static void run(GameTestHelper helper,int outputCount,int initialStairs) {
        run(helper,outputCount,initialStairs,0);
    }

    private static void run(GameTestHelper helper,int outputCount,int initialStairs,int fallbackMode) {
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
                if(++ticks[0]>MAX_TICKS) throw new IllegalStateException("Output routing timeout: "+(fixture[0]==null?"chunks":fixture[0].diagnostics()));
                if(fixture[0]==null) {
                    if(!keys.stream().allMatch(key -> access.ready(key,ChunkDemandManager.Readiness.ENTITY_TICKING))) return;
                    fixture[0]=new Fixture(helper,origin,access,outputCount,initialStairs,fallbackMode);
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
        final BlockPos firstReceiverPos;
        CitizenEntity courier;
        UUID surplusId;
        boolean filled,capacityObserved,reopened,receiverFailureObserved,alternativeOpened;
        long fullAt;
        int vetoes;
        boolean allocated,begun;
        long startedActive,completedAt=-1;

        Fixture(GameTestHelper helper,BlockPos origin,NeoForgeChunkAccess access,int outputCount,int initialStairs,int fallbackMode) {
            this.helper=helper; this.outputCount=outputCount; this.initialStairs=initialStairs;this.fallbackMode=fallbackMode;
            var level=helper.getLevel(); var server=level.getServer();
            for(int x=-2;x<=6;x++) for(int z=-2;z<=6;z++) {
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
            firstReceiverPos=origin.south(2);var alternativePos=origin.east(5).south(3);
            if(fallbackMode!=0) {
                level.setBlockAndUpdate(firstReceiverPos,Blocks.BARREL.defaultBlockState());
                level.setBlockAndUpdate(alternativePos,Blocks.BARREL.defaultBlockState());
                storage.register(colony,position(firstReceiverPos),"warehouse");storage.register(colony,position(alternativePos),"return");
            }
            firstReceiver=fallbackMode==0?null:(Container)level.getBlockEntity(firstReceiverPos);
            alternativeReceiver=fallbackMode==0?null:(Container)level.getBlockEntity(alternativePos);
            var workshop=storage.registerWorkshop(colony,position(tablePos),position(barrelPos));
            producer=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
            if(producer==null) throw new IllegalStateException("Citizen unavailable");
            producer.initializeIdentity(citizen,1); producer.moveTo(start.getX()+0.5,start.getY(),start.getZ()+0.5,0,0);
            core.registry().addCitizen(new CitizenRecord(citizen,colony,producer.getUUID(),1,null,workshop.id(),null,"colonyloom:carpenter",Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.READY,0,Map.of("food",10000L),position(start),1),record -> {
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
                core.registry().addCitizen(new CitizenRecord(courierId,colony,courier.getUUID(),1,null,null,null,"colonyloom:courier",Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.READY,0,Map.of("food",10000L),position(courierStart),1),record -> {
                    if(!level.addFreshEntity(courier))throw new IllegalStateException("Courier spawn refused");
                });
                core.bindings().observe(courierId,courier.getUUID(),1);courier.setQuarantined(false);
            }
            var transfer=new StorageTransferExecutor(server,core.registry(),storage,(action,principal,source,destination,amount) -> {
                if(fallbackMode==1&&action.target().equals(position(firstReceiverPos))) {vetoes++;return false;}
                return true;
            },() -> checkpoint,null);
            delivery=new MinecraftDeliveryService(server,core.registry(),storage,transfer,chunks);
            production=new MinecraftProductionService(core.registry(),storage,new RecipeExecutor(server,core.registry(),storage,new NeoForgeRecipeProtection(server),() -> checkpoint,null),chunks);
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
        }

        boolean observe() {
            var supply=core.registry().supply(); var order=supply.production(orderId);
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
                capacityObserved|=orders.stream().filter(order -> order.workId()!=null).anyMatch(order -> core.workBoard().work(order.workId()).waitingReason()==WorkOrder.Reason.CAPACITY);
                if(!capacityObserved||core.serverTick()-fullAt<20)return false;
                firstReceiver.clearContent();firstReceiver.setChanged();reopened=true;
                if(fallbackMode==2)for(int[] offset:new int[][]{{0,1},{1,0},{0,-1},{-1,0}}) {
                    helper.getLevel().setBlockAndUpdate(firstReceiverPos.offset(offset[0],0,offset[1]),Blocks.STONE.defaultBlockState());
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
            delivery.close();production.close(); navigation.close(); admission.close(); chunks.close();
            if(courier!=null)courier.remove(Entity.RemovalReason.DISCARDED);
            producer.remove(Entity.RemovalReason.DISCARDED); core.beginStopping(); core.stop();
        }
    }
}
