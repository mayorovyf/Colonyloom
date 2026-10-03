package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.*;
import io.github.kpuctajluk.colonyloom.core.supply.*;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.gameplay.needs.NeedsController;
import io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonySavedData;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("colonyloom")
@PrefixGameTestTemplate(false)
public final class FoodPersistenceGameTests {
    private static final WorldPosition POS=new WorldPosition("minecraft:overworld",8,64,8);
    private static UUID id(long value){return new UUID(11,value);}
    private static ColonyRegistry fixture(){
        var registry=new ColonyRegistry(() -> {});registry.addColony(new ColonyRuntime(id(1),"Persisted hungry subject",new Territory(POS.dimension(),0,0,31,31),id(2),Map.of(),1,1,false,null,false));
        registry.addCitizen(new CitizenRecord(id(3),id(1),id(4),1,null,null,null,"colonyloom:builder",Map.of(),Map.of("food",6),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.READY,337,Map.of("food",863L),POS,1),record -> {});
        var needs=new NeedsController(registry,citizen -> true);needs.tick(1);return registry;
    }
    @GameTest(template="identity_empty")
    public static void cleanFoodWorkRetainsExactRecipientAndDecayResidual(GameTestHelper helper){
        var source=fixture();var work=source.workBoard().works().iterator().next();var encoded=ColonySavedData.empty(source.snapshot()).save(new CompoundTag(),helper.getLevel().registryAccess());
        var loaded=ColonySavedData.load(encoded,helper.getLevel().registryAccess());var resumed=new ColonyRegistry(() -> {});resumed.restore(loaded.snapshot());
        var restored=resumed.workBoard().work(work.id());var subject=resumed.citizen(id(3));
        helper.assertTrue(id(3).equals(restored.subjectId())&&restored.lane()==Lane.CRITICAL,"Restart lost prescribed critical recipient");
        helper.assertTrue(subject.food()==6&&subject.foodDecayTicks()==863&&subject.activeTimeTicks()==337,"Restart changed nutrition or active-time residual");
        helper.assertTrue(resumed.supply().demands().size()==1&&resumed.supply().demands().getFirst().snapshot().ownerId().equals(restored.id()),"Restart detached hungry consumer demand");helper.succeed();
    }
    @GameTest(template="identity_empty")
    public static void unknownHungrySubjectRetainsFoodWorkAndDemandWithoutAdmittingPartialChain(GameTestHelper helper){
        var encoded=ColonySavedData.empty(fixture().snapshot()).save(new CompoundTag(),helper.getLevel().registryAccess());encoded.getList("citizens",Tag.TAG_COMPOUND).getCompound(0).putString("typeId","future:citizen");
        var loaded=ColonySavedData.load(encoded,helper.getLevel().registryAccess());
        helper.assertTrue(loaded.contentBlockedColonies().contains(id(1))&&loaded.snapshot().works().isEmpty()&&loaded.snapshot().supply().demands().isEmpty(),"Unknown hungry recipient admitted partial food chain");
        var saved=loaded.save(new CompoundTag(),helper.getLevel().registryAccess());
        for(String key:List.of("citizens","works","demands")){var before=new HashSet<Tag>();for(Tag tag:encoded.getList(key,Tag.TAG_COMPOUND))before.add(tag);var after=new HashSet<Tag>();for(Tag tag:saved.getList(key,Tag.TAG_COMPOUND))after.add(tag);helper.assertTrue(before.equals(after),"Unknown food dependency rewritten: "+key);}
        helper.succeed();
    }
    @GameTest(template="identity_empty")
    public static void futureFoodWitnessRetainsExactSubjectAndAllocatedSupplyDependency(GameTestHelper helper){
        var registry=fixture();var work=registry.workBoard().works().iterator().next();var demand=registry.supply().demands().getFirst();
        var slot=new io.github.kpuctajluk.colonyloom.core.storage.StockRegion(new io.github.kpuctajluk.colonyloom.core.storage.StorageId(POS.dimension(),id(3),1),0);
        var bread=new io.github.kpuctajluk.colonyloom.core.storage.ItemDescriptor("minecraft:bread",new byte[0]);registry.storage().register(id(1),POS,"construction",List.of(slot.storage()),List.of(slot),List.of(POS));registry.storage().index().observe(slot,bread,1,1);var share=registry.supply().allocateStock(demand.id(),slot,bread,1,1);
        var fact=new io.github.kpuctajluk.colonyloom.core.action.EffectRecord.Food(slot,bread,share.id(),demand.id(),6,6,863,863);
        var effect=new io.github.kpuctajluk.colonyloom.core.action.EffectRecord(id(5),id(1),work.id(),id(3),1,io.github.kpuctajluk.colonyloom.core.action.ActionContext.Kind.FOOD_CONSUME,POS,"native_inventory","minecraft:bread",1,1,io.github.kpuctajluk.colonyloom.core.action.EffectRecord.State.PREPARED,0,null,null,fact);registry.effects().prepare(effect,Lane.CRITICAL);
        var encoded=ColonySavedData.empty(registry.snapshot()).save(new CompoundTag(),helper.getLevel().registryAccess());
        for(Tag tag:encoded.getList("evidence",Tag.TAG_COMPOUND)){var entry=(CompoundTag)tag;if(entry.hasUUID("operationId")&&entry.getUUID("operationId").equals(id(5)))entry.getCompound("food").putInt("schemaVersion",2);}
        var loaded=ColonySavedData.load(encoded,helper.getLevel().registryAccess());helper.assertTrue(loaded.contentBlockedColonies().contains(id(1))&&loaded.snapshot().effects().isEmpty(),"Future consumption witness became executable");
        var saved=loaded.save(new CompoundTag(),helper.getLevel().registryAccess());
        for(String key:List.of("evidence","citizens","works","demands","allocations")){var before=new HashSet<Tag>();for(Tag tag:encoded.getList(key,Tag.TAG_COMPOUND))before.add(tag);var after=new HashSet<Tag>();for(Tag tag:saved.getList(key,Tag.TAG_COMPOUND))after.add(tag);helper.assertTrue(before.equals(after),"Future food witness dependency rewritten: "+key);}
        helper.succeed();
    }
}
