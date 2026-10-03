package io.github.kpuctajluk.colonyloom.gametest;

import com.mojang.authlib.GameProfile;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.colony.*;
import io.github.kpuctajluk.colonyloom.core.command.ColonyCommands;
import io.github.kpuctajluk.colonyloom.core.navigation.NavigationService;
import io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.gameplay.construction.ConstructionController;
import io.github.kpuctajluk.colonyloom.minecraft.construction.*;
import io.github.kpuctajluk.colonyloom.minecraft.content.ContentLoader;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.minecraft.navigation.MinecraftNavigationBackend;
import io.github.kpuctajluk.colonyloom.neoforge.NeoForgeChunkAccess;
import io.github.kpuctajluk.colonyloom.neoforge.NeoForgeItemInteraction;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("colonyloom")
@PrefixGameTestTemplate(false)
public final class ConstructionGameTests {
    @GameTest(template="identity_empty",batch="stage05_construction",timeoutTicks=600)
    public static void walkingFourRealStairsConsumesOnlyNpcMaterials(GameTestHelper helper) { run(helper,false); }
    @GameTest(template="identity_empty",batch="stage05_construction",timeoutTicks=600)
    public static void cancellationAfterTwoRetainsBlocksAndRemainingMaterials(GameTestHelper helper) { run(helper,true); }
    @GameTest(template="identity_empty",batch="stage05_construction",timeoutTicks=600)
    public static void cancellationDuringPhysicalEffectBlocksStaleCursorCommit(GameTestHelper helper) { run(helper,false,true); }
    @GameTest(template="identity_empty",batch="stage06_construction",timeoutTicks=600)
    public static void approachingFromTargetSideReachesClearPlacementWaypoint(GameTestHelper helper) { run(helper,false,false,true); }
    @GameTest(template="identity_empty",batch="stage10_construction_retirement",timeoutTicks=600)
    public static void checkpointRetiresLiveClosedConstructionWithoutChangingPlacedProperty(GameTestHelper helper) {
        run(helper,true,false,false,true);
    }
    private static void run(GameTestHelper helper,boolean cancel) {
        run(helper,cancel,false);
    }
    private static void run(GameTestHelper helper,boolean cancel,boolean staleCommit) {
        run(helper,cancel,staleCommit,false);
    }
    private static void run(GameTestHelper helper,boolean cancel,boolean staleCommit,boolean approachAcrossTarget) {
        run(helper,cancel,staleCommit,approachAcrossTarget,false);
    }
    private static void run(GameTestHelper helper,boolean cancel,boolean staleCommit,boolean approachAcrossTarget,boolean retire) {
        var level=helper.getLevel(); var origin=helper.absolutePos(new BlockPos(1,1,1));
        for(int x=-6;x<=6;x++) for(int z=-5;z<=4;z++) {
            var pos=origin.offset(x,0,z); level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
            for(int y=0;y<3;y++) level.setBlockAndUpdate(pos.above(y),Blocks.AIR.defaultBlockState());
        }
        var core=ServerRuntime.start(Thread.currentThread()); var content=ContentLoader.load(level.getServer().getResourceManager(),level.getServer().registryAccess());
        core.configureCommands(() -> {},content.professions().values());
        core.updateLimits(core.admission().limits().withMaxManagedNanos(100_000_000L));
        UUID colony=UUID.randomUUID(),citizen=UUID.randomUUID(),owner=UUID.randomUUID(); String dim=level.dimension().location().toString();
        core.registry().addColony(new ColonyRuntime(colony,"Construction smoke",new Territory(dim,origin.getX()-16,origin.getZ()-16,origin.getX()+32,origin.getZ()+16),owner,Map.of(),1,1,false,null,false));
        var entity=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
        if(entity==null) throw new IllegalStateException("Citizen unavailable");
        var start=origin.offset(-4,0,approachAcrossTarget ? -4 : 1); entity.initializeIdentity(citizen,1); entity.moveTo(start.getX()+0.5,start.getY(),start.getZ()+0.5,0,0);
        entity.inventory().setItem(0,new ItemStack(Items.OAK_STAIRS,4));
        core.registry().addCitizen(new CitizenRecord(citizen,colony,entity.getUUID(),1,null,null,null,"colonyloom:builder",Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.UNKNOWN,0,Map.of(),position(dim,start),1),proposed -> { if(!level.addFreshEntity(entity)) throw new IllegalStateException("Spawn refused"); });
        core.bindings().observe(citizen,entity.getUUID(),1); entity.setQuarantined(false); core.commands().updateCitizenReadiness(citizen,CitizenRecord.Readiness.READY);
        var controller=new ConstructionController(core.registry(),new MinecraftConstructionGeometry(level.getServer())); controller.definitions(content.blueprints()); core.commands().construction(controller);
        var chunks=new ChunkDemandManager(core.registry(),core.budgets(),new NeoForgeChunkAccess(level.getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime"))));
        var placement=new BlockPlacementExecutor(level.getServer(),core.registry(),new NeoForgeItemInteraction(id -> id.equals(owner)?new GameProfile(owner,"ConstructionFixture"):null),(point,context) -> {
            if(staleCommit && point==BlockPlacementExecutor.FaultPoint.BEFORE_EFFECT_COMMIT) core.workBoard().cancel(core.registry().citizen(citizen).assignedWorkId());
        });
        var storage=new io.github.kpuctajluk.colonyloom.minecraft.storage.StorageService(level.getServer(),core.registry(),core.budgets(),new io.github.kpuctajluk.colonyloom.neoforge.NeoForgeStorageIdentity());
        var transfer=new io.github.kpuctajluk.colonyloom.minecraft.storage.StorageTransferExecutor(level.getServer(),core.registry(),storage,(context,principal,source,destination,amount) -> true,() -> new UUID(1,2),null);
        var service=new MinecraftConstructionService(level.getServer(),core.registry(),controller,chunks,placement,() -> new UUID(1,2),storage,transfer);
        var navigation=new NavigationService(core.registry(),core.budgets(),chunks,new MinecraftNavigationBackend(level.getServer(),core.registry(),chunks,service),service); service.navigation(navigation);
        core.scheduler().beforeWork(tick -> { chunks.tick(tick); navigation.tick(tick); core.registry().targetClaims().tick(); });
        core.scheduler().physicalExecutor(WorkOrder.CONSTRUCTION,service);
        var context=new ColonyCommands.CommandContext(owner,false,new ColonyCommands.PhysicalChecks() {
            public void validateTerritory(Territory t) {}
            public void validateCitizenPosition(ColonyRuntime c,WorldPosition p) {}
            public void validateRecovery(ColonyRuntime c,java.util.List<CitizenRecord> cs,java.util.List<io.github.kpuctajluk.colonyloom.core.citizen.BindingRegistry.Observation> observations) {}
        });
        if(approachAcrossTarget) {
            boolean refused=false;
            try {core.commands().build(context,UUID.randomUUID(),colony,"colonyloom:test_four_stairs",position(dim,origin),0);}
            catch(IllegalArgumentException expected) {refused=true;}
            helper.assertTrue(refused && core.workBoard().works().isEmpty() && core.registry().targetClaims().snapshots().isEmpty()
                    && core.registry().construction().snapshots().isEmpty(),"Missing transformed construction buffer accepted partial site/claim/work");
        }
        var buffer=origin.west();level.setBlockAndUpdate(buffer,Blocks.BARREL.defaultBlockState());
        storage.register(colony,position(dim,buffer),"construction");
        var work=core.commands().build(context,UUID.randomUUID(),colony,"colonyloom:test_four_stairs",position(dim,origin),0);
        int[] phase={0}; long[] stoppedAt={0}; double[] stoppedX={0};
        helper.onEachTick(() -> {
            if(phase[0]==2) return;
            core.tick(core.serverTick()+1); var site=core.registry().construction().site(work.id());
            if(staleCommit) {
                if(!work.terminal()) return;
                helper.assertTrue(work.state()==WorkOrder.State.CANCELLED && site.closed() && site.cursor()==0 && site.consumed()==0,"Cancelled effect committed stale logical progress");
                helper.assertTrue(level.getBlockState(origin).is(Blocks.OAK_STAIRS) && entity.inventory().getItem(0).getCount()==3,"Stale effect compensated or lost physical reality");
                helper.assertTrue(core.registry().colony(colony).recoveryBlocked() && core.registry().effects().snapshots().getFirst().state()==io.github.kpuctajluk.colonyloom.core.action.EffectRecord.State.AMBIGUOUS,"Stale physical change did not require recovery");
                phase[0]=2; navigation.close(); service.close(); chunks.close(); entity.remove(Entity.RemovalReason.DISCARDED); core.beginStopping(); core.stop(); helper.succeed(); return;
            }
            if(cancel && phase[0]==0 && site.cursor()==2) {
                helper.assertTrue(site.consumed()==2,"Two-block cancellation expense differs");
                core.commands().cancelWork(context,work.id()); phase[0]=1; stoppedAt[0]=core.serverTick(); stoppedX[0]=entity.getX();
            }
            if(!cancel && work.state()!=WorkOrder.State.COMPLETED || cancel && (phase[0]!=1 || core.serverTick()-stoppedAt[0]<30)) return;
            int placed=cancel?2:4;
            for(int x=0;x<4;x++) helper.assertTrue(x<placed?level.getBlockState(origin.offset(x,0,0)).is(Blocks.OAK_STAIRS):level.getBlockState(origin.offset(x,0,0)).isAir(),"Physical block count differs at "+x);
            helper.assertTrue(entity.inventory().getItem(0).getCount()==4-placed,"NPC material delta differs");
            helper.assertTrue(site.consumed()==placed && site.closed(),"Saved site not closed with exact expense");
            helper.assertTrue(entity.getX()>start.getX()+1.0,"Construction did not physically walk");
            helper.assertTrue(!cancel || Math.abs(entity.getX()-stoppedX[0])<1.0,"Cancelled construction kept walking");
            helper.assertTrue(core.registry().citizen(citizen).assignedWorkId()==null,"Terminal construction retained citizen assignment");
            helper.assertTrue(core.registry().effects().snapshots().size()==placed,"Physical effects count differs");
            if(retire) {
                phase[0]=2;
                try {
                    helper.assertTrue(work.terminal() && work.assignee()==null && site.closed(),"Retirement fixture is not closed and unassigned");
                    helper.assertTrue(core.registry().targetClaims().snapshots().isEmpty()
                            && core.workBoard().works().stream().noneMatch(value -> value.dependencies().contains(work.id())),"Closed construction retained claim or dependent");
                    var states=java.util.stream.IntStream.range(0,4).mapToObj(x -> level.getBlockState(origin.offset(x,0,0))).toList();
                    var property=entity.inventory().getItem(0).copy();
                    var checkpoint=io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonySavedData.empty(core.registry().snapshot());
                    var encoded=checkpoint.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess());
                    var verified=io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonySavedData.load(encoded,level.registryAccess()).snapshot();
                    helper.assertTrue(verified.constructionSites().stream().anyMatch(value -> value.workId().equals(work.id()) && value.closed()),"Verified checkpoint lost closed construction");
                    core.registry().effects().compactAfterVerifiedCheckpoint();
                    helper.assertTrue(core.registry().effects().snapshots().stream().noneMatch(value -> work.id().equals(value.workId())),"Resolved construction witness survived checkpoint");
                    core.registry().construction().compactAfterVerifiedCheckpoint();
                    helper.assertTrue(core.registry().construction().site(work.id())==null && core.registry().construction().definitions().isEmpty()
                            && core.workBoard().works().stream().noneMatch(value -> value.id().equals(work.id())),"Live checkpoint retained site, work or pin");
                    helper.assertTrue(core.registry().targetClaims().snapshots().isEmpty()
                            && chunks.footprint()==0 && chunks.blockTicking()==0 && chunks.entityTicking()==0,"Retired construction retained claim or chunk tickets");
                    helper.assertTrue(core.registry().citizen(citizen).assignedWorkId()==null,"Retired construction retained assignment");
                    helper.assertTrue(states.equals(java.util.stream.IntStream.range(0,4).mapToObj(x -> level.getBlockState(origin.offset(x,0,0))).toList())
                            && ItemStack.matches(property,entity.inventory().getItem(0)),"Retirement changed placed world or remaining NPC property");
                    var compacted=io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonySavedData.empty(core.registry().snapshot());
                    var restored=io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonySavedData.load(compacted.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()),level.registryAccess()).snapshot();
                    helper.assertTrue(restored.works().isEmpty() && restored.constructionSites().isEmpty() && restored.targetClaims().isEmpty(),"Compacted checkpoint retained dangling construction");
                    helper.succeed();
                } finally {
                    navigation.close(); service.close(); chunks.close(); entity.remove(Entity.RemovalReason.DISCARDED); core.beginStopping(); core.stop();
                }
                return;
            }
            System.out.println("COLONYLOOM_CONSTRUCTION_WALK cancel="+cancel+" placed="+placed+" materials="+entity.inventory().getItem(0).getCount()+" position="+entity.position());
            phase[0]=2; navigation.close(); service.close(); chunks.close(); entity.remove(Entity.RemovalReason.DISCARDED); core.beginStopping(); core.stop(); helper.succeed();
        });
    }
    private static WorldPosition position(String dim,BlockPos p) { return new WorldPosition(dim,p.getX(),p.getY(),p.getZ()); }
}
