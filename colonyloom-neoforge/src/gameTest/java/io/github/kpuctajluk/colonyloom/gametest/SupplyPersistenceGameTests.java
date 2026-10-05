package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.core.colony.*;
import io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.storage.*;
import io.github.kpuctajluk.colonyloom.core.supply.*;
import io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonySavedData;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("colonyloom")
@PrefixGameTestTemplate(false)
public final class SupplyPersistenceGameTests {
    private static UUID id(long n){return new UUID(8,n);}
    private static final WorldPosition ADDRESS=new WorldPosition("minecraft:overworld",8,64,8);
    private static ServerRuntime runtime(){
        var runtime=ServerRuntime.start(Thread.currentThread());runtime.configureCommands(()->{},List.of());
        runtime.registry().addColony(new ColonyRuntime(id(1),"Supply",new Territory(ADDRESS.dimension(),0,0,31,31),id(2),Map.of(),0,0,false,null,false));return runtime;
    }
    private static CompoundTag fixture(GameTestHelper helper){
        var runtime=runtime();var registry=runtime.registry();var slot=new StockRegion(new StorageId(ADDRESS.dimension(),id(3),0),0);var item=new ItemDescriptor("minecraft:oak_log",new byte[0]);
        registry.storage().register(id(1),ADDRESS,"warehouse",List.of(slot.storage()),List.of(slot),List.of(ADDRESS));registry.storage().index().observe(slot,item,6,0);
        var workshopAddress=new WorldPosition(ADDRESS.dimension(),12,64,12);var workshopStorage=new StorageId(ADDRESS.dimension(),id(8),0);
        var workshopRegistration=registry.storage().register(id(1),workshopAddress,"workshop",List.of(workshopStorage),List.of(new StockRegion(workshopStorage,0)),List.of(workshopAddress));
        var workshop=registry.storage().registerWorkshop(id(1),new WorldPosition(ADDRESS.dimension(),13,64,12),workshopRegistration.id());
        registry.addCitizen(new io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord(id(9),id(1),id(10),1,null,workshop.id(),null,"colonyloom:carpenter",Map.of(),Map.of("food",20),
                io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Lifecycle.ALIVE,io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Admission.ACTIVE,
                io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Readiness.READY,0,Map.of("food",1200L),workshopAddress,0),record -> {});
        registry.bindings().observe(id(9),id(10),1);
        registry.supply().request(id(4),id(1),id(5),new ItemMatcher(item.itemId(),null),6,Demand.GoalKind.CONSUMPTION,ADDRESS,Lane.CRITICAL,20,0);
        registry.supply().coverStock(id(4),slot,item,6,0);
        var stairs=new ItemDescriptor("minecraft:oak_stairs",new byte[0]);var recipe=RecipeDefinition.create("colonyloom:stairs",1,"colonyloom:carpenter","minecraft:crafting_table",List.of(new RecipeDefinition.Ingredient(new ItemMatcher("minecraft:oak_planks",null),6)),stairs,4,40);
        registry.supply().request(id(6),id(1),id(7),new ItemMatcher(stairs.itemId(),null),16,Demand.GoalKind.CONSUMPTION,ADDRESS,Lane.NORMAL,10,0);registry.supply().promiseProduction(id(6),recipe,4);
        return ColonySavedData.empty(registry.snapshot()).save(new CompoundTag(),helper.getLevel().registryAccess());
    }
    @GameTest(template="identity_empty")
    public static void restartKeepsCriticalReservationAndPinnedPendingRecipe(GameTestHelper helper){
        var root=fixture(helper);var loaded=ColonySavedData.load(root,helper.getLevel().registryAccess());var restored=runtime();restored.registry().restore(loaded.snapshot());
        var demand=restored.registry().supply().demand(id(4)).snapshot();helper.assertTrue(demand.covered()==6&&demand.deficit()==0,"Unknown restart lost accepted stock coverage");
        helper.assertTrue(restored.registry().storage().reservations().entries().iterator().next().lane()==Lane.CRITICAL,"Critical reservation demoted on restart");
        var order=restored.registry().supply().productionOrders().getFirst();helper.assertTrue(order.batches()==4&&order.recipe().version()==1&&order.recipe().outputCount()==4&&restored.registry().supply().demand(id(6)).snapshot().covered()==16,"Pinned pending production changed on restore");
        helper.assertTrue(restored.registry().supply().demand(id(6)).snapshot().fulfilled()==0,"Planning fabricated completed production");helper.succeed();
    }
    @GameTest(template="identity_empty")
    public static void legacyPendingProductionMigratesWithoutInventedWorkshopAndKeepsBackup(GameTestHelper helper) throws Exception {
        var root=fixture(helper);var order=root.getList("productionOrders",Tag.TAG_COMPOUND).getCompound(0);
        order.putInt("schemaVersion",1);order.putLong("remainingActiveTicks",160);
        for(String key:List.of("completedBatches","batchStarted","pinned","workshopId","equipmentPosition","workshopStorage"))order.remove(key);
        var wrapper=new CompoundTag();wrapper.put("data",root);
        var directory=java.nio.file.Files.createTempDirectory("colonyloom-production-migration-");var path=directory.resolve("colonyloom.dat");
        try {
            NbtIo.writeCompressed(wrapper,path);byte[] original=java.nio.file.Files.readAllBytes(path);
            var loaded=ColonySavedData.preflight(path,helper.getLevel().registryAccess());
            var migrated=loaded.snapshot().supply().productionOrders().getFirst();
            helper.assertTrue(!migrated.pinned() && !migrated.batchStarted() && migrated.completedBatches()==0
                    && migrated.batches()==4 && migrated.remainingActiveTicks()==40,"Legacy pending batch invented physical identity/progress");
            var backup=directory.resolve("colonyloom-backups").resolve(loaded.checkpointId()+"-v"+root.getInt("schemaVersion")+".dat");
            helper.assertTrue(java.util.Arrays.equals(original,java.nio.file.Files.readAllBytes(backup)),"Migration did not retain exact compressed checkpoint");
            var saved=loaded.save(new CompoundTag(),helper.getLevel().registryAccess());
            helper.assertTrue(saved.getList("productionOrders",Tag.TAG_COMPOUND).getCompound(0).getInt("schemaVersion")==2,"Migrated order retained old encoding");
            order.putLong("remainingActiveTicks",159);NbtIo.writeCompressed(wrapper,path);
            boolean rejected=false;try {ColonySavedData.preflight(path,helper.getLevel().registryAccess());}catch(java.io.IOException expected){rejected=true;}
            helper.assertTrue(rejected && java.util.Arrays.equals(original,java.nio.file.Files.readAllBytes(backup)),"Unsupported physical progress accepted or immutable backup overwritten");
        } finally {
            try(var files=java.nio.file.Files.walk(directory)) {for(var file:files.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(file);}
        }
        helper.succeed();
    }
    @GameTest(template="identity_empty")
    public static void restoredProducedOutputCancellationRetainsOwnershipUntilSafeHandoff(GameTestHelper helper) {
        var initial=ColonySavedData.load(fixture(helper),helper.getLevel().registryAccess());var before=runtime();before.registry().restore(initial.snapshot());
        var registry=before.registry();var supply=registry.supply();var producer=supply.productionOrders().getFirst();
        var workshop=registry.storage().workshops().getFirst();var workshopAddress=new WorldPosition(ADDRESS.dimension(),12,64,12);
        var input=new StockRegion(producer.workshopStorage(),0);var output=new StockRegion(producer.workshopStorage(),1);
        registry.storage().register(id(1),workshopAddress,"workshop",List.of(input.storage()),List.of(input,output),List.of(workshopAddress));
        var source=new StockRegion(new StorageId(ADDRESS.dimension(),id(3),0),0);var cargo=new StockRegion(source.storage(),1);var sink=new StockRegion(source.storage(),2);
        registry.storage().register(id(1),ADDRESS,"warehouse",List.of(source.storage()),List.of(source,cargo,sink),List.of(ADDRESS));
        supply.reduceRequired(id(6),4);producer=supply.production(producer.id());var child=supply.ingredientDemand(producer,0,6,0);
        var planks=new ItemDescriptor("minecraft:oak_planks",new byte[0]);registry.storage().index().observe(input,planks,6,0);registry.storage().index().observe(output,null,0,0);
        supply.allocateStock(child.id(),input,planks,6,0);registry.updateCitizen(registry.citizen(id(9)).reconciled());registry.bindings().observe(id(9),id(10),1);
        var board=registry.workBoard();var work=board.createProduction(id(11),id(1),producer.equipmentPosition(),producer.recipe().professionId(),0,Lane.NORMAL);
        supply.assignProductionWork(producer.id(),work.id());board.transition(work.id(),io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.READY,io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.NONE,"production");
        helper.assertTrue(board.assign(work.id(),id(9)),"Producer could not bind actual allocated kit");board.transition(work.id(),io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.RUNNING,io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.NONE,"production");
        supply.startProduction(producer.id(),id(9),work.id());supply.advanceProduction(producer.id(),40);
        try(var prepared=supply.prepareProduction(producer.id(),List.of(new SupplyRegistry.OutputPortion(output,4)),0)) {
            registry.storage().index().observe(output,producer.recipe().output(),4,0);prepared.commit();
        }
        registry.storage().index().observe(input,null,0,0);board.transition(work.id(),io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.COMPLETED,io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.NONE,"completed");
        var root=ColonySavedData.empty(registry.snapshot()).save(new CompoundTag(),helper.getLevel().registryAccess());
        var resumed=runtime();resumed.registry().restore(ColonySavedData.load(root,helper.getLevel().registryAccess()).snapshot());var restored=resumed.registry();var accounting=restored.supply();
        accounting.cancel(id(6));var surplus=accounting.demands().stream().filter(d -> accounting.productionSurplus(d.id())).findFirst().orElseThrow();var retained=accounting.demandShares(surplus.id()).getFirst();
        helper.assertTrue(accounting.demand(id(6)).snapshot().status()==Demand.Status.CANCELLED&&accounting.demand(id(6)).snapshot().fulfilled()==0&&accounting.demand(id(6)).snapshot().covered()==0,"Cancelled restored consumer falsely retained success/coverage");
        helper.assertTrue(surplus.snapshot().covered()==4&&restored.storage().obligated(output)==4&&restored.storage().reservations().get(retained.obligationId()).ownerId().equals(surplus.id()),"Restored produced property became unowned on cancellation");
        restored.storage().index().observe(output,producer.recipe().output(),4,0);restored.storage().index().observe(cargo,null,0,0);restored.storage().index().observe(sink,null,0,0);
        helper.assertTrue(restored.storage().index().free(output,0)==0,"Cancelled output became free before handoff");var delivery=accounting.routeReservedStock(retained.id());
        try(var pickup=accounting.preparePickup(retained.id(),delivery.id(),cargo,4,0)) {restored.storage().index().observe(cargo,producer.recipe().output(),4,0);pickup.commit(4);restored.storage().index().observe(output,null,0,0);}
        var carried=accounting.orderShares(delivery.id()).stream().filter(s -> s.stage()==CoverageShare.Stage.IN_TRANSIT).findFirst().orElseThrow();
        helper.assertTrue(accounting.demand(surplus.id()).snapshot().covered()==4&&restored.storage().index().free(cargo,0)==0,"Pickup lost surplus custody before warehouse handoff");
        try(var transfer=accounting.prepareTransfer(carried.id(),sink,4,0)) {restored.storage().index().observe(sink,producer.recipe().output(),4,0);transfer.commit(4);restored.storage().index().observe(cargo,null,0,0);}
        helper.assertTrue(accounting.demand(surplus.id()).snapshot().fulfilled()==4&&accounting.demand(id(6)).snapshot().fulfilled()==0&&restored.storage().index().free(sink,0)==4,"Safe handoff did not release only the surplus property");
        helper.succeed();
    }
    @GameTest(template="identity_empty")
    public static void futureProducerRetainsAdditiveProvenanceAndRecipeDependency(GameTestHelper helper) {
        var root=fixture(helper);root.getList("productionOrders",Tag.TAG_COMPOUND).getCompound(0).putInt("schemaVersion",3);
        var loaded=ColonySavedData.load(root,helper.getLevel().registryAccess());
        helper.assertTrue(loaded.contentBlockedColonies().contains(id(1))&&loaded.snapshot().supply().shares().isEmpty(),"Future producer dependency admitted as known supply");
        var saved=loaded.save(new CompoundTag(),helper.getLevel().registryAccess());
        for(String key:List.of("demands","productionOrders","evidence","reservations","pinnedDefinitions")) {
            var expected=new HashSet<Tag>();for(Tag value:root.getList(key,Tag.TAG_COMPOUND))expected.add(value);
            var actual=new HashSet<Tag>();for(Tag value:saved.getList(key,Tag.TAG_COMPOUND))actual.add(value);
            helper.assertTrue(expected.equals(actual),"Opaque producer dependency changed bytes: "+key);
        }
        helper.succeed();
    }

    @GameTest(template="identity_empty")
    public static void futureDemandRetainsSharesOrdersReservationsAndRecipe(GameTestHelper helper){
        var root=fixture(helper);root.getList("demands",Tag.TAG_COMPOUND).getCompound(0).putInt("schemaVersion",2);
        var loaded=ColonySavedData.load(root,helper.getLevel().registryAccess());helper.assertTrue(loaded.contentBlockedColonies().contains(id(1)),"Future goal did not block colony");
        helper.assertTrue(loaded.snapshot().supply().demands().isEmpty()&&loaded.snapshot().storage().reservations().isEmpty(),"Dependent stock/supply admitted without known consumer");
        var saved=loaded.save(new CompoundTag(),helper.getLevel().registryAccess());
        for(String key:List.of("demands","productionOrders","reservations","pinnedDefinitions")) {
            var expected=new HashSet<Tag>();for(Tag value:root.getList(key,Tag.TAG_COMPOUND))expected.add(value);
            var actual=new HashSet<Tag>();for(Tag value:saved.getList(key,Tag.TAG_COMPOUND))actual.add(value);
            helper.assertTrue(expected.equals(actual),"Future supply dependency changed bytes: "+key);
        }
        helper.succeed();
    }
}
