package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.navigation.NavigationService;
import io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.minecraft.navigation.MinecraftNavigationBackend;
import io.github.kpuctajluk.colonyloom.neoforge.NeoForgeChunkAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
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
public final class NavigationTimeoutGameTests {
    @GameTest(template="identity_empty",batch="navigation_long_turn_timeout",timeoutTicks=500)
    public static void longCardinalRouteKeepsPerWaypointTimeoutAtLateTurn(GameTestHelper helper) {
        run(helper,false);
    }
    @GameTest(template="identity_empty",batch="navigation_stationary_timeout",timeoutTicks=500)
    public static void stationaryNativeWorkerStillStopsWithoutFalseArrival(GameTestHelper helper) {
        run(helper,true);
    }
    private static void run(GameTestHelper helper,boolean stationary) {
        var level=helper.getLevel();
        BlockPos anchor=helper.absolutePos(new BlockPos(1,1,1));
        BlockPos origin=new BlockPos((anchor.getX()&~15)+2,anchor.getY()+5,(anchor.getZ()&~15)+2);
        BlockPos start=origin.offset(31,0,0),target=origin.offset(0,0,1);
        // A one-block-wide L forces 31 westward transitions before the final south turn.
        for(int x=-2;x<=33;x++)for(int z=-2;z<=3;z++) {
            BlockPos feet=origin.offset(x,0,z);level.setBlockAndUpdate(feet.below(),Blocks.STONE.defaultBlockState());
            boolean corridor=z==0&&x>=0&&x<=31||x==0&&z==1;
            for(int y=0;y<3;y++)level.setBlockAndUpdate(feet.above(y),corridor?Blocks.AIR.defaultBlockState():Blocks.STONE.defaultBlockState());
        }
        ServerRuntime core=ServerRuntime.start(Thread.currentThread());core.configureCommands(()->{},List.of());
        UUID colony=UUID.randomUUID(),citizen=UUID.randomUUID(),owner=UUID.randomUUID();String dimension=level.dimension().location().toString();
        core.registry().addColony(new ColonyRuntime(colony,"Long turn",new Territory(dimension,origin.getX()-16,origin.getZ()-16,origin.getX()+48,origin.getZ()+16),UUID.randomUUID(),Map.of(),1,1,false,null,false));
        CitizenEntity entity=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
        if(entity==null)throw new IllegalStateException("Native citizen factory unavailable");
        entity.initializeIdentity(citizen,1);entity.moveTo(start.getX()+0.5,start.getY(),start.getZ()+0.5,0,0);
        UUID nativeId=entity.getUUID();ItemStack bread=new ItemStack(Items.BREAD,3);entity.inventory().setItem(0,bread);
        core.registry().addCitizen(new CitizenRecord(citizen,colony,nativeId,1,null,null,null,null,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.UNKNOWN,0,Map.of("food",1200L),new WorldPosition(dimension,start.getX(),start.getY(),start.getZ()),1),record->{if(!level.addFreshEntity(entity))throw new IllegalStateException("Native spawn refused");});
        core.bindings().observe(citizen,nativeId,1);entity.setQuarantined(false);core.commands().updateCitizenReadiness(citizen,CitizenRecord.Readiness.READY);
        var chunks=new ChunkDemandManager(core.registry(),core.budgets(),new NeoForgeChunkAccess(level.getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime"))));
        var backend=new MinecraftNavigationBackend(level.getServer(),core.registry(),chunks);
        WorkOrder work=core.workBoard().createMove(owner,colony,new WorldPosition(dimension,target.getX(),target.getY(),target.getZ()),0,Lane.NORMAL);
        core.workBoard().transition(owner,WorkOrder.State.READY,WorkOrder.Reason.NONE,"move");helper.assertTrue(core.workBoard().assign(owner,citizen),"Original worker assignment refused");
        var request=new NavigationService.Request(UUID.randomUUID(),owner,colony,citizen,1,0,work.target(),Lane.NORMAL,0);
        var region=new ArrayList<ChunkKey>();for(int x=(origin.getX()-2)>>4;x<=(start.getX()+2)>>4;x++)for(int z=(origin.getZ()-2)>>4;z<=(target.getZ()+2)>>4;z++)region.add(new ChunkKey(dimension,x,z));
        chunks.request(owner,colony,region,ChunkDemandManager.Readiness.ENTITY_TICKING,Lane.NORMAL,0,true);
        boolean[] applied={false};net.minecraft.world.phys.Vec3[] last={entity.position()};long[] tick={0};
        boolean[] closed={false};Runnable cleanup=()->{if(closed[0])return;closed[0]=true;backend.stop(request);chunks.close();entity.remove(Entity.RemovalReason.DISCARDED);core.beginStopping();core.stop();};
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo test) {}
            public void testPassed(GameTestInfo test,GameTestRunner runner) {cleanup.run();}
            public void testFailed(GameTestInfo test,GameTestRunner runner) {cleanup.run();}
            public void testAddedForRerun(GameTestInfo original,GameTestInfo rerun,GameTestRunner runner) {}
        });
        helper.onEachTick(()->{
            if(closed[0])return;
            core.budgets().beginTick(++tick[0]);chunks.tick(tick[0]);
            helper.assertTrue(entity.position().distanceToSqr(last[0])<1,"Route teleported original citizen");last[0]=entity.position();
            helper.assertTrue(level.getEntity(nativeId)==entity&&entity.citizenId().equals(citizen)&&entity.bindingEpoch()==1&&entity.inventory().getItem(0)==bread&&bread.getCount()==3,"Route replaced original identity or property");
            if(!chunks.ready(owner))return;
            if(!applied[0]) {
                var result=backend.search(request,region);if(result==NavigationService.SearchOutcome.PENDING)return;
                helper.assertTrue(result instanceof NavigationService.Route,"Native long turn search refused: "+result);
                helper.assertTrue(backend.apply(request,(NavigationService.Route)result,()->chunks.ready(owner)),"Native long turn apply refused");applied[0]=true;if(stationary)entity.setNoAi(true);return;
            }
            var motion=backend.poll(request);
            if(stationary) {
                helper.assertTrue(motion!=NavigationService.Motion.ARRIVED,"Stationary worker falsely arrived");
                helper.assertTrue(entity.distanceToSqr(start.getX()+0.5,start.getY(),start.getZ()+0.5)<0.01,"Stationary fixture advanced physically");
                if(motion==NavigationService.Motion.OBSTRUCTED) {cleanup.run();helper.succeed();}
                else helper.assertTrue(motion==NavigationService.Motion.MOVING,"Stationary timeout lost ready route authority: "+motion);
                return;
            }
            helper.assertTrue(motion==NavigationService.Motion.MOVING||motion==NavigationService.Motion.ARRIVED,"Progressing long route timed out before final turn: "+motion+" at "+entity.position());
            if(motion!=NavigationService.Motion.ARRIVED)return;
            helper.assertTrue(entity.distanceToSqr(target.getX()+0.5,target.getY(),target.getZ()+0.5)<=0.01,"Completion preceded exact physical arrival");
            cleanup.run();helper.succeed();
        });
    }
}
