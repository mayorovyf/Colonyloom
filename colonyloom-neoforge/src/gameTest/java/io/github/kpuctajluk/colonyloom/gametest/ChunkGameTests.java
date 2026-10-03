package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.neoforge.NeoForgeChunkAccess;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("colonyloom")
@PrefixGameTestTemplate(false)
public final class ChunkGameTests {
    @GameTest(template="identity_empty",timeoutTicks=600)
    public static void realTicketRingsAndSharedOwnerRelease(GameTestHelper helper) {
        var level = helper.getLevel(); String dimension = level.dimension().location().toString();
        ChunkKey center = new ChunkKey(dimension, 200, 200); UUID colony=UUID.randomUUID(), first=UUID.randomUUID(), second=UUID.randomUUID();
        ColonyRegistry registry = new ColonyRegistry(() -> {});
        registry.addColony(new ColonyRuntime(colony,"Ticket ring",new Territory(dimension,3200,3200,3215,3215),UUID.randomUUID(),Map.of(),1,1,false,null,false));
        var budgets=registry.budgets(); budgets.updateLimits(registry.admission().limits().withMaxManagedNanos(200_000_000L));
        var access = new NeoForgeChunkAccess(level.getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime")));
        var chunks = new ChunkDemandManager(registry,budgets,access);
        chunks.request(first,colony,List.of(center),ChunkDemandManager.Readiness.ENTITY_TICKING,Lane.NORMAL,0,false);
        chunks.request(second,colony,List.of(center),ChunkDemandManager.Readiness.ENTITY_TICKING,Lane.NORMAL,0,false);
        long[] tick={0}, releaseTick={-1};
        helper.onEachTick(() -> {
            budgets.beginTick(++tick[0]); chunks.tick(tick[0]);
            if (releaseTick[0]<0 && chunks.ready(first) && chunks.ready(second)) {
                // Native neighbour readiness publishes independently from the centre's entity-ticking state.
                for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++) if(!access.ready(new ChunkKey(dimension,center.x()+x,center.z()+z),ChunkDemandManager.Readiness.LOADED))return;
                for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++) if(!access.ready(new ChunkKey(dimension,center.x()+x,center.z()+z),ChunkDemandManager.Readiness.BLOCK_TICKING))return;
                for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++) helper.assertTrue(access.ready(new ChunkKey(dimension,center.x()+x,center.z()+z),ChunkDemandManager.Readiness.LOADED),"5x5 loaded footprint missing "+x+","+z);
                for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++) helper.assertTrue(access.ready(new ChunkKey(dimension,center.x()+x,center.z()+z),ChunkDemandManager.Readiness.BLOCK_TICKING),"3x3 block ring missing "+x+","+z);
                helper.assertTrue(!access.ready(new ChunkKey(dimension,center.x()+1,center.z()),ChunkDemandManager.Readiness.ENTITY_TICKING),"Entity ticking expanded beyond center");
                helper.assertTrue(!access.ready(new ChunkKey(dimension,center.x()+2,center.z()),ChunkDemandManager.Readiness.BLOCK_TICKING),"Block ticking expanded beyond ring");
                helper.assertTrue(!access.ready(new ChunkKey(dimension,center.x()+3,center.z()),ChunkDemandManager.Readiness.LOADED),"Loaded ring exceeded conservative5x5");
                helper.assertTrue(chunks.footprint()==25 && chunks.blockTicking()==9 && chunks.entityTicking()==1,"Shared demand double-counted real ticket rings");
                chunks.release(first); releaseTick[0]=tick[0];
            } else if (releaseTick[0]>=0 && tick[0]-releaseTick[0]>=20) {
                helper.assertTrue(chunks.ready(second)&&chunks.footprint()==25,"One owner release dropped shared physical ticket");
                System.out.println("COLONYLOOM_CHUNK_RING loaded=25 block=9 entity=1 sharedOwnerSurvived=true ticketNanosHighWater="+chunks.ticketNanosHighWater());
                chunks.close(); helper.succeed();
            }
        });
    }
    @GameTest(template="identity_empty",timeoutTicks=600)
    public static void realProtectedRecipientSwapsIdleWorkshopForDependency(GameTestHelper helper) {
        var level=helper.getLevel();String dimension=level.dimension().location().toString();
        UUID colony=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();
        var registry=new ColonyRegistry(() -> {});
        registry.addColony(new ColonyRuntime(colony,"Dependency swap",new Territory(dimension,4800,4800,4815,4815),UUID.randomUUID(),Map.of(),1,1,false,null,false));
        var limits=registry.admission().limits().withResource(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.LOADED_FOOTPRINT,58)
                .withResource(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.BLOCK_TICKING,18)
                .withResource(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.ENTITY_TICKING,2).withMaxManagedNanos(200_000_000L);
        registry.admission().updateLimits(limits);var budgets=registry.budgets();budgets.updateLimits(limits);
        var chunks=new ChunkDemandManager(registry,budgets,new NeoForgeChunkAccess(level.getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime"))));
        chunks.request(a,colony,List.of(new ChunkKey(dimension,300,300)),ChunkDemandManager.Readiness.ENTITY_TICKING,Lane.NORMAL,0,false);
        chunks.request(c,colony,List.of(new ChunkKey(dimension,310,300)),ChunkDemandManager.Readiness.ENTITY_TICKING,Lane.NORMAL,0,false);
        int[] phase={0};long[] tick={0};
        helper.onEachTick(() -> {
            budgets.beginTick(++tick[0]);chunks.tick(tick[0]);helper.assertTrue(chunks.footprint()<=58,"Swap exceeded full global footprint");
            if(phase[0]==0&&chunks.ready(a)&&chunks.ready(c)) {
                chunks.setProtection(a,true,false,false);
                chunks.request(b,colony,List.of(new ChunkKey(dimension,320,300)),ChunkDemandManager.Readiness.ENTITY_TICKING,Lane.NORMAL,0,true);phase[0]=1;
            } else if(phase[0]==1) {
                helper.assertTrue(chunks.ready(a),"Protected recipient lost ready ticket during swap");
                if(chunks.ready(b)) {
                    helper.assertTrue(!chunks.admitted(c)&&chunks.state(c)==ChunkDemandManager.State.WAITING,"Idle workshop desire not safely retained");
                    chunks.release(b);phase[0]=2;
                }
            } else if(phase[0]==2&&chunks.ready(c)) {
                helper.assertTrue(chunks.ready(a),"Recipient dropped during retained workshop readmission");
                System.out.println("COLONYLOOM_CHUNK_SWAP A+C_to_A+B_to_A+C footprint="+chunks.footprint()+" protectedA=true retainedC=true");
                chunks.close();helper.succeed();phase[0]=3;
            }
        });
    }
}
