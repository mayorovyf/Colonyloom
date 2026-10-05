package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
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
        sharedTicketRings(helper, Lane.NORMAL, Lane.NORMAL, 200);
    }

    @GameTest(template="identity_empty",timeoutTicks=600)
    public static void realNormalServiceSharedRings(GameTestHelper helper) { sharedTicketRings(helper, Lane.NORMAL, Lane.SERVICE, 220); }
    @GameTest(template="identity_empty",timeoutTicks=600)
    public static void realServiceNormalSharedRings(GameTestHelper helper) { sharedTicketRings(helper, Lane.SERVICE, Lane.NORMAL, 240); }
    @GameTest(template="identity_empty",timeoutTicks=600)
    public static void realNormalCriticalSharedRings(GameTestHelper helper) { sharedTicketRings(helper, Lane.NORMAL, Lane.CRITICAL, 260); }
    @GameTest(template="identity_empty",timeoutTicks=600)
    public static void realCriticalNormalSharedRings(GameTestHelper helper) { sharedTicketRings(helper, Lane.CRITICAL, Lane.NORMAL, 280); }
    @GameTest(template="identity_empty",timeoutTicks=600)
    public static void realServiceCriticalSharedRings(GameTestHelper helper) { sharedTicketRings(helper, Lane.SERVICE, Lane.CRITICAL, 340); }
    @GameTest(template="identity_empty",timeoutTicks=600)
    public static void realCriticalServiceSharedRings(GameTestHelper helper) { sharedTicketRings(helper, Lane.CRITICAL, Lane.SERVICE, 360); }
    @GameTest(template="identity_empty",timeoutTicks=600)
    public static void realServiceServiceSharedRings(GameTestHelper helper) { sharedTicketRings(helper, Lane.SERVICE, Lane.SERVICE, 380); }
    @GameTest(template="identity_empty",timeoutTicks=600)
    public static void realCriticalCriticalSharedRings(GameTestHelper helper) { sharedTicketRings(helper, Lane.CRITICAL, Lane.CRITICAL, 400); }

    private static void sharedTicketRings(GameTestHelper helper, Lane firstLane, Lane secondLane, int coordinate) {
        var level = helper.getLevel(); String dimension = level.dimension().location().toString();
        ChunkKey center = new ChunkKey(dimension, coordinate, 200);
        UUID colony=UUID.randomUUID(), otherColony=UUID.randomUUID(), first=UUID.randomUUID(), second=UUID.randomUUID(), duplicate=UUID.randomUUID();
        ColonyRegistry registry = new ColonyRegistry(() -> {});
        registry.addColony(new ColonyRuntime(colony,"Ticket ring",new Territory(dimension,coordinate*16,3200,coordinate*16+15,3215),UUID.randomUUID(),Map.of(),1,1,false,null,false));
        registry.addColony(new ColonyRuntime(otherColony,"Surviving ticket ring",new Territory(dimension,coordinate*16,3216,coordinate*16+15,3231),UUID.randomUUID(),Map.of(),1,1,false,null,false));
        var budgets=registry.budgets(); budgets.updateLimits(registry.admission().limits().withMaxManagedNanos(200_000_000L));
        var access = new NeoForgeChunkAccess(level.getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime")));
        var chunks = new ChunkDemandManager(registry,budgets,access);
        chunks.request(first,colony,List.of(center),ChunkDemandManager.Readiness.ENTITY_TICKING,firstLane,0,false);
        long[] tick={0}, releaseTick={-1}; int[] phase={0};
        helper.onEachTick(() -> {
            budgets.beginTick(++tick[0]); chunks.tick(tick[0]);
            if (phase[0]==0 && chunks.ready(first)) {
                chunks.request(second,otherColony,List.of(center),ChunkDemandManager.Readiness.ENTITY_TICKING,secondLane,0,false);
                chunks.request(duplicate,otherColony,List.of(center),ChunkDemandManager.Readiness.ENTITY_TICKING,secondLane,0,false);
                phase[0]=1;
            } else if (phase[0]==1 && chunks.ready(first) && chunks.ready(second) && chunks.ready(duplicate)) {
                // Native neighbour readiness publishes independently from the centre's entity-ticking state.
                for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++) if(!access.ready(new ChunkKey(dimension,center.x()+x,center.z()+z),ChunkDemandManager.Readiness.LOADED))return;
                for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++) if(!access.ready(new ChunkKey(dimension,center.x()+x,center.z()+z),ChunkDemandManager.Readiness.BLOCK_TICKING))return;
                for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++) helper.assertTrue(access.ready(new ChunkKey(dimension,center.x()+x,center.z()+z),ChunkDemandManager.Readiness.LOADED),"5x5 loaded footprint missing "+x+","+z);
                for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++) helper.assertTrue(access.ready(new ChunkKey(dimension,center.x()+x,center.z()+z),ChunkDemandManager.Readiness.BLOCK_TICKING),"3x3 block ring missing "+x+","+z);
                helper.assertTrue(!access.ready(new ChunkKey(dimension,center.x()+1,center.z()),ChunkDemandManager.Readiness.ENTITY_TICKING),"Entity ticking expanded beyond center");
                helper.assertTrue(!access.ready(new ChunkKey(dimension,center.x()+2,center.z()),ChunkDemandManager.Readiness.BLOCK_TICKING),"Block ticking expanded beyond ring");
                helper.assertTrue(!access.ready(new ChunkKey(dimension,center.x()+3,center.z()),ChunkDemandManager.Readiness.LOADED),"Loaded ring exceeded conservative5x5");
                helper.assertTrue(chunks.footprint()==25 && chunks.blockTicking()==9 && chunks.entityTicking()==1,"Shared demand double-counted real ticket rings");
                helper.assertTrue(registry.admission().used(colony,Resource.LOADED_FOOTPRINT)==25
                        && registry.admission().used(otherColony,Resource.LOADED_FOOTPRINT)==25,"Native shared colony attribution missing or duplicate refs charged");
                helper.assertTrue(registry.admission().used(colony,Resource.LOADED_FOOTPRINT,firstLane)==25
                        && registry.admission().used(otherColony,Resource.LOADED_FOOTPRINT,secondLane)==25,"Native shared lane attribution missing");
                chunks.setProtection(second,false,true,false); chunks.setProtection(duplicate,false,true,false);
                var reduced=registry.admission().limits().withResource(Resource.LOADED_FOOTPRINT,29);
                registry.admission().updateLimits(reduced); budgets.updateLimits(reduced.withMaxManagedNanos(200_000_000L)); chunks.limitsUpdated();
                chunks.release(first); releaseTick[0]=tick[0]; phase[0]=2;
            } else if (phase[0]==2 && tick[0]-releaseTick[0]>=20) {
                helper.assertTrue(chunks.ready(second)&&chunks.ready(duplicate)&&chunks.footprint()==25
                        &&chunks.blockTicking()==9&&chunks.entityTicking()==1,"First colony release dropped surviving native rings");
                helper.assertTrue(registry.admission().used(colony,Resource.LOADED_FOOTPRINT)==0
                        &&registry.admission().used(otherColony,Resource.LOADED_FOOTPRINT)==25,"Departed colony retained surviving colony shares");
                chunks.release(second); releaseTick[0]=tick[0]; phase[0]=3;
            } else if (phase[0]==3 && tick[0]-releaseTick[0]>=20) {
                helper.assertTrue(chunks.ready(duplicate)&&chunks.footprint()==25,"Same-scope release dropped duplicate native owner");
                chunks.release(duplicate); releaseTick[0]=tick[0]; phase[0]=4;
            } else if (phase[0]==4 && tick[0]-releaseTick[0]>=20) {
                if (access.ready(center,ChunkDemandManager.Readiness.ENTITY_TICKING)) return;
                helper.assertTrue(chunks.footprint()==0&&chunks.blockTicking()==0&&chunks.entityTicking()==0
                        &&registry.admission().used(Resource.CHUNK_DEMANDS)==0,"Final native owner left charged state");
                for (Lane lane : Lane.values()) helper.assertTrue(registry.admission().used(Resource.LOADED_FOOTPRINT,lane)==0,"Final native owner left lane share");
                System.out.println("COLONYLOOM_CHUNK_RING loaded=25 block=9 entity=1 firstLane="+firstLane+" secondLane="+secondLane+" sharedColonySurvived=true duplicateScopeSurvived=true cleaned=true ticketNanosHighWater="+chunks.ticketNanosHighWater());
                chunks.close(); phase[0]=5; helper.succeed();
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
