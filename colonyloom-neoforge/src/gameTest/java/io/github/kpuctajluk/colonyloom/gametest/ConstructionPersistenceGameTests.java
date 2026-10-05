package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.action.EffectRecord;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.construction.ConstructionRegistry;
import io.github.kpuctajluk.colonyloom.core.construction.ConstructionSnapshot;
import io.github.kpuctajluk.colonyloom.core.content.BlockDescriptor;
import io.github.kpuctajluk.colonyloom.core.content.BlockOffset;
import io.github.kpuctajluk.colonyloom.core.content.BlueprintDefinition;
import io.github.kpuctajluk.colonyloom.core.persistence.RegistrySnapshot;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonySavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("colonyloom")
@PrefixGameTestTemplate(false)
public final class ConstructionPersistenceGameTests {
    @GameTest(template="identity_empty")
    public static void deathCargoAndPublishedUuidRoundTripRejectFutureSchema(GameTestHelper helper) {
        var item=new io.github.kpuctajluk.colonyloom.core.storage.ItemDescriptor("minecraft:oak_stairs",new byte[0]);
        var death=new EffectRecord.Death(id(21),List.of(new EffectRecord.DeathCargo(0,item,4)),List.of(new EffectRecord.DeathDrop(id(301),item,4)),true);
        var effect=new EffectRecord(id(300),id(1),null,id(20),1,ActionContext.Kind.DEATH,position(),"native_final_death","colonyloom:inventory",4,0,EffectRecord.State.OBSERVED,1,null,null,null,death);
        var root=encode(helper,snapshot(blueprint(4),WorkOrder.State.PLANNED,false,false,List.of(effect)));
        helper.assertTrue(load(helper,root).snapshot().effects().equals(List.of(effect)),"Exact death components/cargo/drop UUID changed");
        find(root,"evidence","operationId",id(300)).getCompound("death").putInt("schemaVersion",2);
        var loaded=load(helper,root);helper.assertTrue(loaded.contentBlockedColonies().contains(id(1))&&loaded.snapshot().effects().isEmpty(),"Future death witness became executable");
        helper.assertTrue(find(loaded.save(new CompoundTag(),helper.getLevel().registryAccess()),"evidence","operationId",id(300)).equals(find(root,"evidence","operationId",id(300))),"Future death witness rewritten");
        helper.succeed();
    }
    @GameTest(template="identity_empty")
    public static void paletteAndPhysicalEvidenceRoundTrip(GameTestHelper helper) {
        var pin=blueprint(4);
        var evidence=List.of(effect(100,30,EffectRecord.State.PREPARED),effect(101,30,EffectRecord.State.OBSERVED),
                effect(102,30,EffectRecord.State.AMBIGUOUS),effect(103,30,EffectRecord.State.ACCEPTED),
                new EffectRecord(id(104),id(1),null,id(20),1,ActionContext.Kind.DEATH,position(),"vanilla_drops","minecraft:oak_stairs",4,0,EffectRecord.State.OBSERVED,1,null,null,null,null));
        var source=snapshot(pin,WorkOrder.State.PLANNED,false,false,evidence);
        var root=encode(helper,source);
        var encodedPin=root.getList("pinnedDefinitions",Tag.TAG_COMPOUND).getCompound(0);
        helper.assertTrue(encodedPin.getList("palette",Tag.TAG_COMPOUND).size()==1,"Repeated stair state was not palette-shared");
        for(var entry:encodedPin.getList("blocks",Tag.TAG_COMPOUND)) {
            var block=(CompoundTag)entry;
            helper.assertTrue(block.getInt("state")==0 && !block.contains("properties") && !block.contains("blockId"),"Position duplicated its descriptor");
        }
        var decoded=load(helper,root).snapshot();
        helper.assertTrue(decoded.pinnedBlueprints().equals(List.of(pin)) && decoded.effects().equals(evidence)
                && decoded.constructionSites().equals(source.constructionSites()),"Physical counts, phases, target or pinned state changed");
        var registry=new ColonyRegistry(() -> {}); registry.restore(decoded);
        helper.assertTrue(registry.effects().snapshots().equals(evidence),"Core restore changed physical evidence");
        helper.succeed();
    }

    @GameTest(template="identity_empty",timeoutTicks=200)
    public static void maximumBlueprintSharesDescriptorsAndRejectsCorruption(GameTestHelper helper) {
        var pin=blueprint(65536); var root=encode(helper,snapshot(pin,WorkOrder.State.PLANNED,false,false,List.of()));
        var decoded=load(helper,root).snapshot().pinnedBlueprints().get(0);
        var first=decoded.blocks().get(0).block();
        helper.assertTrue(decoded.blocks().stream().allMatch(value -> value.block()==first),"Restored positions allocated repeated descriptors");
        helper.assertTrue(ConstructionRegistry.estimatedBytes(pin)<5L*1024*1024,"Accounting duplicated palette per position");
        var bad=root.copy(); bad.getList("pinnedDefinitions",Tag.TAG_COMPOUND).getCompound(0).getList("blocks",Tag.TAG_COMPOUND).getCompound(0).putInt("state",1);
        reject(helper,() -> load(helper,bad),"Out-of-palette index accepted");
        var wrongState=root.copy(); wrongState.getList("pinnedDefinitions",Tag.TAG_COMPOUND).getCompound(0).getList("palette",Tag.TAG_COMPOUND).getCompound(0).getCompound("properties").putString("facing","up");
        reject(helper,() -> load(helper,wrongState),"Pinned invalid Minecraft property accepted");
        var tooMany=root.copy(); var palette=tooMany.getList("pinnedDefinitions",Tag.TAG_COMPOUND).getCompound(0).getList("palette",Tag.TAG_COMPOUND);
        for(int index=1;index<513;index++) palette.add(palette.getCompound(0).copy());
        reject(helper,() -> load(helper,tooMany),"Palette over 512 accepted");
        helper.succeed();
    }

    @GameTest(template="identity_empty")
    public static void futurePinEffectAndSiteRetainDependentOriginals(GameTestHelper helper) {
        for(String variant:List.of("pin_schema","effect_kind","effect_state","site_type")) {
            var root=encode(helper,snapshot(blueprint(4),WorkOrder.State.RUNNING,true,false,List.of(effect(100,30,EffectRecord.State.PREPARED))));
            switch(variant) {
                case "pin_schema" -> root.getList("pinnedDefinitions",Tag.TAG_COMPOUND).getCompound(0).putInt("schemaVersion",200);
                case "effect_kind" -> find(root,"evidence","operationId",id(100)).putString("kind","FUTURE_EFFECT");
                case "effect_state" -> find(root,"evidence","operationId",id(100)).putString("state","FUTURE_PHASE");
                case "site_type" -> siteTag(root).putString("typeId","future:construction");
                default -> throw new AssertionError(variant);
            }
            root.getList("citizens",Tag.TAG_COMPOUND).getCompound(0).putString("future_citizen","preserve");
            root.getList("works",Tag.TAG_COMPOUND).getCompound(0).putString("future_work","preserve");
            var loaded=load(helper,root);
            helper.assertTrue(loaded.contentBlockedColonies().equals(java.util.Set.of(id(1))),"Opaque dependency failed to isolate colony: "+variant);
            helper.assertTrue(loaded.snapshot().works().isEmpty() && loaded.snapshot().citizens().isEmpty()
                    && loaded.snapshot().constructionSites().isEmpty() && loaded.snapshot().pinnedBlueprints().isEmpty(),"Known dependent object activated: "+variant);
            var registry=new ColonyRegistry(() -> {}); registry.restore(loaded.snapshot());
            var roundtrip=loaded.save(new CompoundTag(),helper.getLevel().registryAccess());
            for(String list:List.of("works","citizens","evidence","pinnedDefinitions")) assertSameEntries(helper,root,roundtrip,list);
        }
        helper.succeed();
    }

    @GameTest(template="identity_empty")
    public static void nonexistentReferencesStillFailWithOpaqueDependencies(GameTestHelper helper) {
        var root=encode(helper,snapshot(blueprint(4),WorkOrder.State.RUNNING,true,false,List.of(effect(100,30,EffectRecord.State.PREPARED))));
        root.getList("pinnedDefinitions",Tag.TAG_COMPOUND).getCompound(0).putInt("schemaVersion",2);
        var missingPin=root.copy(); missingPin.put("pinnedDefinitions",new ListTag());
        reject(helper,() -> load(helper,missingPin),"Nonexistent pin tolerated");
        var missingWork=root.copy(); missingWork.put("works",new ListTag());
        reject(helper,() -> load(helper,missingWork),"Nonexistent work tolerated");
        var missingCitizen=root.copy(); find(missingCitizen,"evidence","operationId",id(100)).putUUID("citizenId",id(999));
        reject(helper,() -> load(helper,missingCitizen),"Opaque sibling hid nonexistent citizen");
        var missingSite=encode(helper,snapshot(blueprint(4),WorkOrder.State.PLANNED,false,false,List.of())); missingSite.put("evidence",new ListTag());
        reject(helper,() -> load(helper,missingSite),"Known construction without site activated");
        helper.succeed();
    }

    @GameTest(template="identity_empty",timeoutTicks=200)
    public static void retainedPinnedBytesBoundBothDecodeAndEncode(GameTestHelper helper) {
        var root=encode(helper,snapshot(blueprint(4),WorkOrder.State.PLANNED,false,false,List.of()));
        var first=new CompoundTag(); first.putString("typeId","future:definition"); first.putByteArray("payload",new byte[61*1024*1024]);
        root.getList("pinnedDefinitions",Tag.TAG_COMPOUND).add(first);
        var loaded=load(helper,root);
        var oversized=root.copy(); var second=new CompoundTag(); second.putString("typeId","future:definition"); second.putByteArray("payload",new byte[4*1024*1024]);
        oversized.getList("pinnedDefinitions",Tag.TAG_COMPOUND).add(second);
        reject(helper,() -> load(helper,oversized),"Combined retained pins over 64MiB decoded");
        var blocks=new ArrayList<BlueprintDefinition.BlockSpec>();
        var descriptor=stairs(); for(int y=0;y<16;y++) for(int z=0;z<64;z++) for(int x=0;x<64;x++) blocks.add(new BlueprintDefinition.BlockSpec(new BlockOffset(x,y,z),descriptor));
        var pins=new ArrayList<BlueprintDefinition>();
        for(int version=1;version<=2;version++) pins.add(BlueprintDefinition.create("colonyloom:large",version,blocks,Map.of("work_origin",new BlockOffset(0,0,1))));
        var current=loaded.snapshot();
        loaded.capture(new RegistrySnapshot(current.colonies(),current.citizens(),current.buildings(),current.tombstones(),current.observations(),current.works(),current.targetClaims(),current.effects(),current.constructionSites(),pins,current.storage(),io.github.kpuctajluk.colonyloom.core.supply.SupplySnapshot.empty()));
        reject(helper,() -> loaded.save(new CompoundTag(),helper.getLevel().registryAccess()),"Known plus retained pins over 64MiB encoded");
        helper.succeed();
    }

    @GameTest(template="identity_empty")
    public static void checkpointCompactionPreservesUnresolvedAndReferencedState(GameTestHelper helper) {
        var pin=blueprint(4); var unresolved=List.of(effect(100,30,EffectRecord.State.PREPARED),effect(101,30,EffectRecord.State.AMBIGUOUS),effect(102,30,EffectRecord.State.OBSERVED),effect(103,30,EffectRecord.State.ACCEPTED));
        var registry=new ColonyRegistry(() -> {}); registry.restore(snapshot(pin,WorkOrder.State.CANCELLED,false,true,unresolved));
        registry.effects().compactAfterVerifiedCheckpoint(); registry.construction().compactAfterVerifiedCheckpoint();
        helper.assertTrue(registry.effects().snapshots().equals(unresolved.subList(0,2)),"Checkpoint lost prepared or ambiguous evidence");
        helper.assertTrue(registry.construction().site(id(30))!=null && registry.construction().definitions().equals(List.of(pin))
                && registry.workBoard().work(id(30)).terminal(),"Surviving witness lost referenced work/site/pin");
        var open=new ColonyRegistry(() -> {}); open.restore(snapshot(pin,WorkOrder.State.PLANNED,false,false,List.of()));
        open.construction().compactAfterVerifiedCheckpoint(); helper.assertTrue(open.construction().size()==1,"Open site compacted");
        var closed=new ColonyRegistry(() -> {}); closed.restore(snapshot(pin,WorkOrder.State.CANCELLED,false,true,List.of(effect(104,30,EffectRecord.State.OBSERVED))));
        closed.effects().compactAfterVerifiedCheckpoint(); closed.construction().compactAfterVerifiedCheckpoint();
        helper.assertTrue(closed.effects().size()==0 && closed.construction().size()==0 && closed.construction().definitions().isEmpty()
                && closed.workBoard().works().isEmpty(),"Checkpoint left unreferenced closed obligations");
        var roundtrip=load(helper,encode(helper,closed.snapshot())).snapshot(); new ColonyRegistry(() -> {}).restore(roundtrip);
        helper.assertTrue(roundtrip.works().isEmpty() && roundtrip.constructionSites().isEmpty(),"Compacted DTO retained dangling construction");
        helper.succeed();
    }
    @GameTest(template="identity_empty")
    public static void fullWitnessEnvelopeRefusesNewEffectsUntilResolvedCheckpoint(GameTestHelper helper) {
        var registry=new ColonyRegistry(() -> {});
        var before=effect(100,30,EffectRecord.State.PREPARED);
        registry.restore(snapshot(blueprint(4),WorkOrder.State.PLANNED,false,false,List.of(before)));
        var resources=new java.util.EnumMap<io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource,Integer>(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.class);
        var limits=registry.admission().limits();resources.putAll(limits.resources());
        resources.put(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.EVIDENCE,
                registry.admission().used(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.EVIDENCE));
        registry.admission().updateLimits(new io.github.kpuctajluk.colonyloom.core.config.SimulationLimits(resources,limits.budgets(),limits.maxManagedNanos()));
        boolean refused=false;
        try {registry.effects().prepare(effect(101,30,EffectRecord.State.PREPARED),Lane.NORMAL);}
        catch(io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.AdmissionException expected) {refused=true;}
        helper.assertTrue(refused&&registry.effects().snapshots().equals(List.of(before)),"Full witness envelope accepted effect or discarded retained PREPARED fact");
        registry.effects().compactAfterVerifiedCheckpoint();
        helper.assertTrue(registry.effects().get(before.operationId()).equals(before),"Checkpoint erased unresolved witness at capacity");
        registry.effects().update(before.observed(3,false));
        registry.workBoard().cancel(id(30));
        registry.effects().compactAfterVerifiedCheckpoint();
        registry.effects().prepare(effect(102,30,EffectRecord.State.PREPARED),Lane.NORMAL);
        helper.assertTrue(registry.effects().get(id(102))!=null&&registry.effects().get(before.operationId())==null,"Resolved terminal checkpoint did not release witness capacity");
        helper.succeed();
    }


    @GameTest(template="identity_empty")
    public static void immutableEvidenceAndMonotonicConstructionRejectRewrites(GameTestHelper helper) {
        var pin=blueprint(4); var before=effect(100,30,EffectRecord.State.PREPARED);
        var registry=new ColonyRegistry(() -> {}); registry.restore(snapshot(pin,WorkOrder.State.PLANNED,false,false,List.of(before)));
        var changed=new EffectRecord(before.operationId(),before.colonyId(),before.workId(),before.citizenId(),1,before.kind(),before.target(),"different_state",before.itemId(),4,3,EffectRecord.State.OBSERVED,1,null,null,null,null);
        reject(helper,() -> registry.effects().update(changed),"Immutable expected state rewritten");
        var observed=before.observed(3,false); registry.effects().update(observed);
        reject(helper,() -> registry.effects().update(new EffectRecord(observed.operationId(),observed.colonyId(),observed.workId(),observed.citizenId(),1,observed.kind(),observed.target(),observed.expectedBlock(),observed.itemId(),4,4,EffectRecord.State.PREPARED,2,null,null,null,null)),"Observed fact rewound");
        var progressed=new ConstructionSnapshot(id(30),id(1),pin.digest(),position(),0,id(10),1,1,1,1,false); registry.construction().update(progressed);
        reject(helper,() -> registry.construction().update(new ConstructionSnapshot(id(30),id(1),pin.digest(),position(),0,id(10),1,2,1,2,false)),"Consumed material increased without progress");
        registry.construction().update(new ConstructionSnapshot(id(30),id(1),pin.digest(),position(),0,id(10),1,1,1,2,true));
        reject(helper,() -> registry.construction().update(new ConstructionSnapshot(id(30),id(1),pin.digest(),position(),0,id(10),1,1,1,3,false)),"Closed site reopened");
        helper.succeed();
    }

    private static RegistrySnapshot snapshot(BlueprintDefinition pin,WorkOrder.State state,boolean assigned,boolean closed,List<EffectRecord> effects) {
        var colony=new ColonyRuntime(id(1),"Construction",new Territory("minecraft:overworld",0,0,63,63),id(10),Map.of(),0,0,false,null,false);
        var citizen=new CitizenRecord(id(20),id(1),id(21),1,null,null,assigned?id(30):null,"colonyloom:builder",Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.UNKNOWN,0,Map.of(),position(),0);
        var work=new WorkOrder.Snapshot(WorkOrder.CONSTRUCTION,id(30),id(1),position(),"colonyloom:builder",0,Lane.NORMAL,state,assigned?id(20):null,"construction",0,List.of(),WorkOrder.Reason.NONE,0,0,null,false,0);
        var site=new ConstructionSnapshot(id(30),id(1),pin.digest(),position(),0,id(10),0,0,1,0,closed);
        var claims=closed?List.<io.github.kpuctajluk.colonyloom.core.spatial.TargetClaimRegistry.Snapshot>of():List.of(new io.github.kpuctajluk.colonyloom.core.spatial.TargetClaimRegistry.Snapshot(id(30),id(1),null,"minecraft:overworld",0,64,0,63,79,63,1));
        return new RegistrySnapshot(List.of(colony),List.of(citizen),List.of(),List.of(),List.of(),List.of(work),claims,effects,List.of(site),List.of(pin),io.github.kpuctajluk.colonyloom.core.storage.StorageSnapshot.empty(),io.github.kpuctajluk.colonyloom.core.supply.SupplySnapshot.empty());
    }
    private static BlueprintDefinition blueprint(int count) {
        var blocks=new ArrayList<BlueprintDefinition.BlockSpec>(count); var descriptor=stairs();
        for(int index=0;index<count;index++) blocks.add(new BlueprintDefinition.BlockSpec(new BlockOffset(index%64,(index/4096)%64,(index/64)%64),descriptor));
        return BlueprintDefinition.create("colonyloom:test_persistence",1,blocks,Map.of("work_origin",new BlockOffset(0,0,1)));
    }
    private static BlockDescriptor stairs() { return new BlockDescriptor("minecraft:oak_stairs",Map.of("facing","north","half","bottom","shape","straight","waterlogged","false"),"minecraft:oak_stairs"); }
    private static EffectRecord effect(long operation,long work,EffectRecord.State state) { return new EffectRecord(id(operation),id(1),id(work),id(20),1,ActionContext.Kind.BLOCK_PLACE,position(),"north_bottom_straight","minecraft:oak_stairs",4,state==EffectRecord.State.PREPARED?4:3,state,0,null,null,null,null); }
    private static UUID id(long value) { return new UUID(0,value); }
    private static WorldPosition position() { return new WorldPosition("minecraft:overworld",0,64,0); }
    private static CompoundTag encode(GameTestHelper helper,RegistrySnapshot snapshot) { return ColonySavedData.empty(snapshot).save(new CompoundTag(),helper.getLevel().registryAccess()); }
    private static ColonySavedData load(GameTestHelper helper,CompoundTag root) { return ColonySavedData.load(root,helper.getLevel().registryAccess()); }
    private static CompoundTag find(CompoundTag root,String list,String key,UUID identity) { for(var value:root.getList(list,Tag.TAG_COMPOUND)) { var entry=(CompoundTag)value; if(entry.hasUUID(key) && entry.getUUID(key).equals(identity)) return entry; } throw new AssertionError(identity); }
    private static CompoundTag siteTag(CompoundTag root) { for(var value:root.getList("evidence",Tag.TAG_COMPOUND)) { var entry=(CompoundTag)value; if(entry.getString("typeId").equals("colonyloom:construction_site")) return entry; } throw new AssertionError("site"); }
    private static void assertSameEntries(GameTestHelper helper,CompoundTag expected,CompoundTag actual,String key) {
        var first=expected.getList(key,Tag.TAG_COMPOUND); var second=actual.getList(key,Tag.TAG_COMPOUND);
        helper.assertTrue(first.size()==second.size(),"Lost original "+key);
        for(var entry:first) helper.assertTrue(second.contains(entry),"Rewrote original "+key);
    }
    private static void reject(GameTestHelper helper,Runnable action,String message) { try { action.run(); } catch(IllegalArgumentException expected) { return; } helper.fail(message); }
}
