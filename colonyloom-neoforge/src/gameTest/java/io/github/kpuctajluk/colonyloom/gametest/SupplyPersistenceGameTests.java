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
