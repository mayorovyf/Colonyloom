package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.core.action.EffectRecord;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.chunk.*;
import io.github.kpuctajluk.colonyloom.core.colony.*;
import io.github.kpuctajluk.colonyloom.core.command.ColonyCommands;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.content.BlueprintDefinition;
import io.github.kpuctajluk.colonyloom.core.navigation.NavigationService;
import io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.supply.*;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.gameplay.construction.ConstructionController;
import io.github.kpuctajluk.colonyloom.gameplay.logistics.DeliveryController;
import io.github.kpuctajluk.colonyloom.gameplay.needs.NeedsController;
import io.github.kpuctajluk.colonyloom.gameplay.production.ProductionCatalog;
import io.github.kpuctajluk.colonyloom.minecraft.construction.*;
import io.github.kpuctajluk.colonyloom.minecraft.content.ContentLoader;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.minecraft.navigation.MinecraftNavigationBackend;
import io.github.kpuctajluk.colonyloom.minecraft.needs.*;
import io.github.kpuctajluk.colonyloom.minecraft.storage.*;
import io.github.kpuctajluk.colonyloom.neoforge.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("colonyloom")
@PrefixGameTestTemplate(false)
public final class NeedsGameTests {
    @GameTest(template="identity_empty",batch="stage11_saturated_food",timeoutTicks=2200)
    public static void saturatedNormalConstructionStillFeedsExactHungryBuilderAndServicesHealthyWork(GameTestHelper helper){run(helper,true);}
    @GameTest(template="identity_empty",batch="stage11_starvation",timeoutTicks=2200)
    public static void starvingWithoutBreadDoesNotPlaceOrdinaryBlocksOrFabricateFood(GameTestHelper helper){run(helper,false);}
    @GameTest(template="identity_empty",batch="stage11_food_source_fault",timeoutTicks=2200)
    public static void foodExpenseFaultBlocksWithoutGrantingOrRepeatingNutrition(GameTestHelper helper){run(helper,true,FoodConsumptionExecutor.FaultPoint.AFTER_SOURCE_CHANGE,false);}
    @GameTest(template="identity_empty",batch="stage11_food_fact_fault",timeoutTicks=2200)
    public static void observedFoodFaultNeverReplaysNativeBreadOrGrantsUnpublishedNutrition(GameTestHelper helper){run(helper,true,FoodConsumptionExecutor.FaultPoint.AFTER_FACT_BEFORE_NOTIFY,false);}
    @GameTest(template="identity_empty",batch="stage11_food_veto",timeoutTicks=2200)
    public static void foodProtectionVetoLeavesBreadAndNutritionUnchanged(GameTestHelper helper){run(helper,true,null,true);}
    @GameTest(template="identity_empty",batch="stage11_loaded_food",timeoutTicks=2200)
    public static void hungryLoadedCourierReturnsActualCargoBeforeTakingFoodAssignment(GameTestHelper helper){run(helper,true,null,false,true);}

    private static void run(GameTestHelper helper,boolean bread){run(helper,bread,null,false);}
    private static void run(GameTestHelper helper,boolean bread,FoodConsumptionExecutor.FaultPoint fault,boolean veto){run(helper,bread,fault,veto,false);}
    private static void run(GameTestHelper helper,boolean bread,FoodConsumptionExecutor.FaultPoint fault,boolean veto,boolean cargo){
        var origin=helper.absolutePos(new BlockPos(1,1,1));var access=new NeoForgeChunkAccess(helper.getLevel().getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime")));
        UUID ticketOwner=UUID.randomUUID();List<ChunkKey> keys=new ArrayList<>();String dimension=helper.getLevel().dimension().location().toString();
        for(int x=((origin.getX()-6)>>4)-1;x<=((origin.getX()+10)>>4)+1;x++)for(int z=((origin.getZ()-6)>>4)-1;z<=((origin.getZ()+8)>>4)+1;z++){var key=new ChunkKey(dimension,x,z);keys.add(key);if(!access.acquire(ticketOwner,key,ChunkDemandManager.Readiness.ENTITY_TICKING))throw new IllegalStateException("Needs fixture ticket denied");}
        Fixture[] fixture={null};int[] ticks={0};boolean[] done={false};
        helper.onEachTick(() -> {if(done[0])return;try{
            if(++ticks[0]>2000)throw new IllegalStateException("Needs timeout "+(fixture[0]==null?"chunks":fixture[0].diagnostics()));
            if(fixture[0]==null){if(!keys.stream().allMatch(key -> access.ready(key,ChunkDemandManager.Readiness.ENTITY_TICKING)))return;fixture[0]=new Fixture(helper,origin,access,bread,fault,veto,cargo);}
            fixture[0].core.tick(fixture[0].core.serverTick()+1);
            if(!fixture[0].observe())return;
            done[0]=true;fixture[0].close();for(var key:keys)access.release(ticketOwner,key,ChunkDemandManager.Readiness.ENTITY_TICKING);helper.succeed();
        }catch(RuntimeException|AssertionError failure){done[0]=true;if(fixture[0]!=null)fixture[0].close();for(var key:keys)access.release(ticketOwner,key,ChunkDemandManager.Readiness.ENTITY_TICKING);helper.fail(failure.toString());}});
    }
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;final ServerRuntime core;final UUID colony=UUID.randomUUID(),owner=UUID.randomUUID(),checkpoint=UUID.randomUUID();
        final List<RecipeGraphCursor> retainedGraphs=new ArrayList<>();
        final List<CitizenEntity> citizens=new ArrayList<>();final List<WorkOrder> sites=new ArrayList<>();final List<BlockPos> targets=new ArrayList<>();
        final StorageService storage;final ChunkDemandManager chunks;final io.github.kpuctajluk.colonyloom.minecraft.runtime.CitizenAdmissionService admission;
        final MinecraftConstructionService construction;final MinecraftDeliveryService delivery;final NavigationService navigation;final SupplyPlanner planner;final NeedsController needs;
        final Container warehouse;final UUID hungry;final WorkOrder healthy;final boolean bread;boolean needsEnabled;long started=-1,observed=-1;UUID foodWork;
        final FoodConsumptionExecutor.FaultPoint fault;final boolean veto;boolean faultInjected;
        final boolean cargo;UUID cargoOrder,cargoWork;boolean criticalReturnObserved;Container blockedDestination;
        Fixture(GameTestHelper helper,BlockPos origin,NeoForgeChunkAccess access,boolean bread,FoodConsumptionExecutor.FaultPoint fault,boolean veto,boolean cargo){
            this.helper=helper;this.bread=bread;this.fault=fault;this.veto=veto;this.cargo=cargo;var level=helper.getLevel();var server=level.getServer();
            for(int x=-6;x<=10;x++)for(int z=-6;z<=8;z++){var pos=origin.offset(x,0,z);level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());for(int y=0;y<3;y++)level.setBlockAndUpdate(pos.above(y),Blocks.AIR.defaultBlockState());}
            var warehousePos=origin.offset(-4,0,-4);level.setBlockAndUpdate(warehousePos,Blocks.BARREL.defaultBlockState());warehouse=(Container)level.getBlockEntity(warehousePos);if(bread)warehouse.setItem(0,new ItemStack(Items.BREAD,2));
            core=ServerRuntime.start(Thread.currentThread());var content=ContentLoader.load(server.getResourceManager(),server.registryAccess());core.configureCommands(() -> {},content.professions().values());
            var limits=SimulationLimits.development();var resources=new EnumMap<Resource,Integer>(Resource.class);resources.putAll(limits.resources());resources.put(Resource.WORKS,128);resources.put(Resource.DEMANDS,12);resources.put(Resource.GRAPH_NODES,12);core.updateLimits(new SimulationLimits(resources,limits.budgets(),100_000_000L));
            core.registry().addColony(new ColonyRuntime(colony,"Critical physical needs",new Territory(position(origin).dimension(),origin.getX()-16,origin.getZ()-16,origin.getX()+16,origin.getZ()+16),owner,Map.of(),1,1,false,null,false));
            storage=new StorageService(server,core.registry(),core.budgets(),new NeoForgeStorageIdentity());storage.register(colony,position(warehousePos),"warehouse");
            hungry=citizen(origin.offset(-1,0,-1),cargo?"colonyloom:courier":"colonyloom:builder",cargo?20:bread?6:0);citizen(origin.offset(8,0,7),"colonyloom:carpenter",20);
            var controller=new ConstructionController(core.registry(),new MinecraftConstructionGeometry(server));var loaded=content.blueprints().get("colonyloom:test_four_stairs");
            var blueprint=BlueprintDefinition.create("colonyloom:needs_single_stair",1,List.of(loaded.blocks().getFirst()),loaded.markers());controller.definitions(Map.of(blueprint.id(),blueprint));core.commands().construction(controller);core.commands().delivery(new DeliveryController(core.registry(),new MinecraftDeliveryAccess(core.registry(),storage)));
            chunks=new ChunkDemandManager(core.registry(),core.budgets(),access);admission=new io.github.kpuctajluk.colonyloom.minecraft.runtime.CitizenAdmissionService(server,core,chunks);
            var transfer=new StorageTransferExecutor(server,core.registry(),storage,(action,principal,source,destination,amount) -> true,() -> checkpoint,null);
            construction=new MinecraftConstructionService(server,core.registry(),controller,chunks,new BlockPlacementExecutor(server,core.registry(),new NeoForgeItemInteraction(id -> id.equals(owner)?new com.mojang.authlib.GameProfile(owner,"NeedsFixture"):null),null),() -> checkpoint,storage,transfer);
            delivery=new MinecraftDeliveryService(server,core.registry(),storage,transfer,chunks);
            NavigationService.GoalAuthority goals=(work,request) -> WorkOrder.DELIVERY.equals(work.typeId())||work.criticalService()?delivery.current(work,request):construction.current(work,request);
            navigation=new NavigationService(core.registry(),core.budgets(),chunks,new MinecraftNavigationBackend(server,core.registry(),chunks,goals),goals);construction.navigation(navigation);delivery.navigation(navigation);
            var food=new MinecraftNeedsService(core.registry(),storage,delivery,new FoodConsumptionExecutor(server,core.registry(),storage,(context,principal,prepared) -> !veto,() -> checkpoint,(point,context) -> {if(point==fault&&!faultInjected){faultInjected=true;throw new IllegalStateException("food fixture fault "+point);}}));needs=new NeedsController(core.registry(),food);food.needs(needs);
            planner=new SupplyPlanner(core.registry(),core.registry().supply(),core.budgets());planner.configure(new ProductionCatalog(core.registry(),Map.of()),new MinecraftSupplyAccess(core.registry(),storage));
            core.scheduler().physicalExecutor(WorkOrder.CONSTRUCTION,construction);core.scheduler().physicalExecutor(WorkOrder.DELIVERY,delivery);core.scheduler().physicalExecutor(WorkOrder.FOOD,food);
            core.scheduler().beforeWork(tick -> {chunks.tick(tick);admission.tick();if(needsEnabled)needs.tick(tick);navigation.tick(tick);storage.tick(tick);core.registry().targetClaims().tick();core.registry().supply().reconcile(tick,core.budgets());planner.tick(tick);delivery.tick(tick);});
            var context=new ColonyCommands.CommandContext(owner,false,new ColonyCommands.PhysicalChecks(){public void validateTerritory(Territory territory){}public void validateCitizenPosition(ColonyRuntime colony,WorldPosition position){}public void validateRecovery(ColonyRuntime colony,List<CitizenRecord> records,List<io.github.kpuctajluk.colonyloom.core.citizen.BindingRegistry.Observation> observations){}});
            if(!cargo)for(int i=0;i<6;i++){
                var target=origin.offset(2+(i%3)*3,0,-3+(i/3)*5);targets.add(target);var buffer=target.west();level.setBlockAndUpdate(buffer,Blocks.BARREL.defaultBlockState());storage.register(colony,position(buffer),"construction");
                var site=core.commands().build(context,UUID.randomUUID(),colony,blueprint.id(),position(target),0);sites.add(site);
                String item=blueprint.blocks().getFirst().block().itemId();UUID demand=UUID.nameUUIDFromBytes((site.id()+":materials:0:"+item).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                core.registry().supply().request(demand,colony,site.id(),new ItemMatcher(item,NativeItemDescriptor.describe(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(item))),server.registryAccess())),1,Demand.GoalKind.CONSUMPTION,position(buffer),Lane.NORMAL,0,0);
            }
            if(!cargo)for(var demand:core.registry().supply().demands())retainedGraphs.add(new RecipeGraphCursor(demand.snapshot(),1,core.admission(),new ProductionCatalog(core.registry(),Map.of()),new MinecraftSupplyAccess(core.registry(),storage),Set.of()));
            healthy=core.workBoard().createTimer(UUID.randomUUID(),colony,position(origin.offset(8,0,7)),"colonyloom:carpenter",0,Lane.NORMAL,500);
            if(!cargo)while(core.admission().used(Resource.WORKS,Lane.NORMAL)<core.admission().limits().resource(Resource.WORKS)/2){var retained=core.workBoard().createTimer(UUID.randomUUID(),colony,position(origin),null,0,Lane.NORMAL,1);core.workBoard().transition(retained.id(),WorkOrder.State.COMPLETED,WorkOrder.Reason.NONE,"fixture-retained-terminal");}
            if(cargo){
                warehouse.setItem(1,new ItemStack(Items.OAK_STAIRS,3));var destination=origin.offset(8,0,-4);level.setBlockAndUpdate(destination,Blocks.BARREL.defaultBlockState());blockedDestination=(Container)level.getBlockEntity(destination);storage.register(colony,position(destination),"return");
                core.commands().requestDelivery(context,UUID.randomUUID(),colony,position(warehousePos),position(destination),NativeItemDescriptor.describe(new ItemStack(Items.OAK_STAIRS),server.registryAccess()),3);
            }
        }
        private UUID citizen(BlockPos start,String profession,int food){var level=helper.getLevel();UUID id=UUID.randomUUID();var entity=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);if(entity==null)throw new IllegalStateException("Citizen unavailable");entity.initializeIdentity(id,1);entity.moveTo(start.getX()+0.5,start.getY(),start.getZ()+0.5,0,0);core.registry().addCitizen(new CitizenRecord(id,colony,entity.getUUID(),1,null,null,null,profession,Map.of(),Map.of("food",food),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.READY,0,Map.of("food",1200L),position(start),1),record -> {if(!level.addFreshEntity(entity))throw new IllegalStateException("Spawn refused");});core.bindings().observe(id,entity.getUUID(),1);entity.setQuarantined(false);citizens.add(entity);return id;}
        boolean observe(){
            if(cargo&&!needsEnabled){
                var loaded=core.registry().supply().deliveries().stream().filter(order -> hungry.equals(order.citizenId())&&core.registry().supply().hasCargo(order.id())).findFirst().orElse(null);if(loaded==null)return false;
                cargoOrder=loaded.id();cargoWork=loaded.workId();for(int slot=0;slot<blockedDestination.getContainerSize();slot++)blockedDestination.setItem(slot,new ItemStack(Items.STONE,64));blockedDestination.setChanged();core.registry().updateCitizen(core.registry().citizen(hungry).withFood(6));needsEnabled=true;started=core.serverTick();return false;
            }
            if(!needsEnabled){if(core.registry().supply().demands().size()<6)return false;check(core.admission().used(Resource.DEMANDS,Lane.NORMAL)==core.admission().limits().resource(Resource.DEMANDS)/2,"Normal colony construction demands not saturated");check(core.admission().used(Resource.GRAPH_NODES,Lane.NORMAL)==core.admission().limits().resource(Resource.GRAPH_NODES)/2,"Normal colony construction graph nodes not saturated");check(core.admission().used(Resource.WORKS,Lane.NORMAL)==core.admission().limits().resource(Resource.WORKS)/2,"Normal colony work records not saturated");needsEnabled=true;started=core.serverTick();return false;}
            var citizen=core.registry().citizen(hungry);for(var work:core.workBoard().works())if(WorkOrder.FOOD.equals(work.typeId())&&hungry.equals(work.subjectId()))foodWork=work.id();
            if(cargo&&core.registry().supply().hasCargo(cargoOrder)){
                check(cargoWork.equals(citizen.assignedWorkId()),"Hungry loaded courier lost cargo-bound assignment before safe unload");
                if(core.workBoard().work(cargoWork).criticalService())criticalReturnObserved=true;
            }
            if(fault!=null){
                if(!faultInjected)return false;
                check(citizen.needs().get("food")==6&&totalBread()==1,"Ambiguous expense granted nutrition or compensated bread");check(core.registry().colony(colony).recoveryBlocked(),"Native food fault did not block colony");
                check(core.registry().effects().snapshots().stream().anyMatch(effect -> effect.kind()==io.github.kpuctajluk.colonyloom.core.action.ActionContext.Kind.FOOD_CONSUME&&effect.state()==EffectRecord.State.AMBIGUOUS),"Native expense lost ambiguous food witness");
                if(observed<0){observed=core.serverTick();return false;}return core.serverTick()-observed>=100;
            }
            if(veto){
                if(foodWork==null||core.workBoard().work(foodWork).waitingReason()!=WorkOrder.Reason.PERMISSION_DENIED)return false;
                check(citizen.needs().get("food")==6&&totalBread()==2,"Veto spent bread or granted nutrition");check(core.registry().effects().snapshots().stream().noneMatch(effect -> effect.kind()==io.github.kpuctajluk.colonyloom.core.action.ActionContext.Kind.FOOD_CONSUME),"Veto admitted food evidence");return true;
            }
            if(bread){
                if(citizen.needs().get("food")!=11)return false;
                check(totalBread()==1,"Food increment lacked exactly one native bread expense");check(foodWork!=null&&core.workBoard().work(foodWork).state()==WorkOrder.State.COMPLETED,"Exact hungry subject work not terminal");
                if(cargo){
                    check(criticalReturnObserved&&!core.registry().supply().hasCargo(cargoOrder)&&core.registry().supply().delivery(cargoOrder).state()==io.github.kpuctajluk.colonyloom.core.logistics.DeliveryOrder.State.RETURNED,"Food assignment preceded critical safe cargo return");
                    int stairs=0;for(int slot=0;slot<warehouse.getContainerSize();slot++)if(warehouse.getItem(slot).is(Items.OAK_STAIRS))stairs+=warehouse.getItem(slot).getCount();
                    check(stairs==3&&citizens.stream().allMatch(entity -> {for(int slot=0;slot<entity.inventory().getContainerSize();slot++)if(entity.inventory().getItem(slot).is(Items.OAK_STAIRS))return false;return true;}),"Safe return lost or orphaned actual three stairs");
                }
                check(core.workBoard().snapshots().stream().anyMatch(snapshot -> snapshot.id().equals(healthy.id())&&snapshot.remainingActiveTicks()<500),"Healthy ordinary timer did not progress behind critical food");
                if(observed<0){observed=core.serverTick();return false;}if(core.serverTick()-observed<100)return false;
                check(totalBread()==1&&citizen.needs().get("food")==11,"Food spent twice after completed chain");
                check(core.registry().effects().snapshots().stream().allMatch(effect -> effect.state()==EffectRecord.State.OBSERVED),"Physical chain lacks observed native evidence");return true;
            }
            if(core.serverTick()-started<200)return false;
            check(citizen.needs().get("food")==0&&totalBread()==0,"No-bread starvation fabricated food");
            check(core.registry().construction().snapshots().stream().allMatch(site -> site.consumed()==0)&&targets.stream().allMatch(pos -> helper.getLevel().getBlockState(pos).isAir()),"Starving builder performed ordinary construction");
            check(foodWork!=null&&core.workBoard().work(foodWork).waitingReason()==WorkOrder.Reason.MATERIALS,"Missing bread reason not visible MATERIALS: "+diagnostics());
            check(core.workBoard().snapshots().stream().anyMatch(snapshot -> snapshot.id().equals(healthy.id())&&snapshot.remainingActiveTicks()<500),"Starvation stopped unrelated healthy timer progression");return true;
        }
        private int totalBread(){int result=count(warehouse);for(var entity:citizens)result+=count(entity.inventory());return result;}
        private static int count(Container container){int result=0;for(int slot=0;slot<container.getContainerSize();slot++)if(container.getItem(slot).is(Items.BREAD))result+=container.getItem(slot).getCount();return result;}
        private WorldPosition position(BlockPos pos){return new WorldPosition(helper.getLevel().dimension().location().toString(),pos.getX(),pos.getY(),pos.getZ());}
        private void check(boolean passed,String message){helper.assertTrue(passed,message);}
        String diagnostics(){return "food="+core.registry().citizen(hungry).needs()+" works="+core.workBoard().snapshots()+" supply="+core.registry().supply().snapshot()+" chunks="+chunks.diagnostics(null);}
        @Override public void close(){for(var graph:retainedGraphs)graph.close();planner.close();delivery.close();navigation.close();construction.close();admission.close();chunks.close();for(var entity:citizens)entity.remove(Entity.RemovalReason.DISCARDED);core.beginStopping();core.stop();}
    }
}
