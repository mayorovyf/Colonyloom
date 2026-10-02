package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.navigation.NavigationService;
import io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.scheduler.SimulationScheduler;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.minecraft.navigation.MinecraftNavigationBackend;
import io.github.kpuctajluk.colonyloom.neoforge.NeoForgeChunkAccess;
import java.util.List;
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
public final class NavigationGameTests {
    @GameTest(template = "identity_empty", batch = "stage04_navigation", timeoutTicks = 500)
    public static void vanillaWalkCancellationAndInactivePhysicalDamage(GameTestHelper helper) {
        walk(helper,helper.absolutePos(new BlockPos(1,1,1)));
    }
    @GameTest(template = "identity_empty", batch = "stage04_navigation", timeoutTicks = 500)
    public static void walkAcrossPositiveChunkBoundary(GameTestHelper helper) {
        BlockPos origin=helper.absolutePos(new BlockPos(1,1,1));
        remoteWalk(helper,new BlockPos(Math.abs(origin.getX() & ~15)+15,origin.getY(),(origin.getZ() & ~15)+40));
    }
    @GameTest(template = "identity_empty", batch = "stage04_navigation", timeoutTicks = 500)
    public static void walkAcrossNegativeChunkBoundary(GameTestHelper helper) {
        BlockPos origin=helper.absolutePos(new BlockPos(1,1,1));
        remoteWalk(helper,new BlockPos(-Math.abs(origin.getX() & ~15)-17,origin.getY(),(origin.getZ() & ~15)+72));
    }
    private static void remoteWalk(GameTestHelper helper,BlockPos start) {
        var level=helper.getLevel();
        var access=new NeoForgeChunkAccess(level.getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime")));
        UUID owner=UUID.randomUUID();
        String dimension=level.dimension().location().toString();
        var centers=List.of(new io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey(dimension,(start.getX()-2)>>4,start.getZ()>>4),
                new io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey(dimension,(start.getX()+9)>>4,start.getZ()>>4));
        for (var center:centers) if (!access.acquire(owner,center,ChunkDemandManager.Readiness.ENTITY_TICKING)) throw new IllegalStateException("Remote fixture ticket refused");
        boolean[] spawned={false};
        helper.onEachTick(() -> {
            if (spawned[0] || !centers.stream().allMatch(center -> access.ready(center,ChunkDemandManager.Readiness.ENTITY_TICKING))) return;
            spawned[0]=true;
            walk(helper,start,() -> { for(var center:centers) access.release(owner,center,ChunkDemandManager.Readiness.ENTITY_TICKING); });
        });
    }
    private static void walk(GameTestHelper helper,BlockPos start) {
        walk(helper,start,() -> {});
    }
    private static void walk(GameTestHelper helper,BlockPos start,Runnable releaseFixture) {
        var level=helper.getLevel();
        BlockPos target = start.offset(7, 0, 0);
        for (int x = -2; x <= 10; x++) for (int z = -2; z <= 2; z++) {
            BlockPos feet = start.offset(x, 0, z);
            level.setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
            for (int y = 0; y < 3; y++) level.setBlockAndUpdate(feet.above(y), Blocks.AIR.defaultBlockState());
        }
        ServerRuntime core = ServerRuntime.start(Thread.currentThread());
        core.configureCommands(() -> {}, List.of());
        core.updateLimits(core.admission().limits().withMaxManagedNanos(100_000_000L));
        UUID colony = UUID.randomUUID(), citizen = UUID.randomUUID();
        String dimension = level.dimension().location().toString();
        core.registry().addColony(new ColonyRuntime(colony, "Navigation smoke", new Territory(dimension, start.getX()-16, start.getZ()-16, start.getX()+32, start.getZ()+16), UUID.randomUUID(), Map.of(), 1, 1, false, null, false));
        CitizenEntity entity = (CitizenEntity) BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
        if (entity == null) throw new IllegalStateException("Real citizen factory unavailable");
        entity.initializeIdentity(citizen, 1);
        entity.moveTo(start.getX()+0.5, start.getY(), start.getZ()+0.5, 0, 0);
        entity.inventory().setItem(0, new ItemStack(Items.BREAD, 3));
        core.registry().addCitizen(new CitizenRecord(citizen, colony, entity.getUUID(), 1, null, null, null, null, Map.of(), Map.of("food",20), CitizenRecord.Lifecycle.ALIVE, CitizenRecord.Admission.ACTIVE, CitizenRecord.Readiness.UNKNOWN, 0, Map.of("food",1200L), position(dimension,start), 1), proposed -> {
            if (!level.addFreshEntity(entity)) throw new IllegalStateException("Physical smoke spawn refused");
        });
        core.bindings().observe(citizen, entity.getUUID(), 1); entity.setQuarantined(false);
        core.commands().updateCitizenReadiness(citizen, CitizenRecord.Readiness.READY);
        TicketController controller = new TicketController(ResourceLocation.parse("colonyloom:runtime"));
        ChunkDemandManager chunks = new ChunkDemandManager(core.registry(), core.budgets(), new NeoForgeChunkAccess(level.getServer(),controller));
        NavigationService navigation = new NavigationService(core.registry(),core.budgets(),chunks,new MinecraftNavigationBackend(level.getServer(),core.registry(),chunks));
        core.scheduler().beforeWork(tick -> { chunks.tick(tick); navigation.tick(tick); });
        core.scheduler().movementExecutor(new SimulationScheduler.MovementExecutor() {
            public void step(WorkOrder work,long tick) {
                navigation.request(work.id(),colony,citizen,1,0,work.target(),work.lane(),work.priority());
                if (navigation.atTarget(work.id())) { core.workBoard().transition(work.id(),WorkOrder.State.COMPLETED,WorkOrder.Reason.NONE,"completed"); navigation.cancel(work.id()); }
                else if (navigation.state(work.id()) == NavigationService.State.WAITING) core.workBoard().waitAssigned(work.id(),navigation.reason(work.id()),"move");
            }
            public void cancel(UUID workId) { navigation.cancel(workId); }
        });
        WorkOrder cancelled = core.workBoard().createMove(UUID.randomUUID(),colony,position(dimension,target),0,Lane.NORMAL);
        final int[] phase = {0}; final long[] cancelledTick = {0}; final double[] cancelledX = {0}; final WorkOrder[] arrival = {null};
        helper.onEachTick(() -> {
            core.tick(core.serverTick()+1);
            if (phase[0] == 0 && entity.getX() > start.getX()+1.5) {
                core.workBoard().cancel(cancelled.id()); cancelledX[0] = entity.getX(); cancelledTick[0] = core.serverTick(); phase[0] = 1;
            } else if (phase[0] == 1 && core.serverTick()-cancelledTick[0] >= 20) {
                helper.assertTrue(cancelled.state()==WorkOrder.State.CANCELLED && core.registry().citizen(citizen).assignedWorkId()==null,"Cancelled route retained assignment");
                helper.assertTrue(Math.abs(entity.getX()-cancelledX[0])<1.0,"Cancelled stale route continued walking");
                arrival[0] = core.workBoard().createMove(UUID.randomUUID(),colony,position(dimension,target),0,Lane.NORMAL); phase[0]=2;
            } else if (phase[0] == 2 && arrival[0].state()==WorkOrder.State.COMPLETED) {
                helper.assertTrue(entity.position().distanceToSqr(target.getX()+0.5,target.getY(),target.getZ()+0.5)<=1.1,"Logical arrival lacks physical target");
                core.commands().updateCitizenAdmission(citizen,CitizenRecord.Admission.INACTIVE);
                long before = core.registry().citizen(citizen).activeTimeTicks();
                float health = entity.getHealth(); entity.hurt(level.damageSources().generic(),2.0F);
                helper.assertTrue(entity.getHealth()<health,"Inactivity suppressed vanilla physical damage");
                helper.assertTrue(core.registry().citizen(citizen).activeTimeTicks()==before && entity.inventory().getItem(0).getCount()==3,"Inactivity changed own time or property");
                System.out.println("COLONYLOOM_MOVE_ARRIVAL cancelledStale=false physicalTarget="+entity.position()+" maxSearchNanos="+navigation.maxSearchNanos());
                navigation.close(); chunks.close(); entity.remove(Entity.RemovalReason.DISCARDED); core.beginStopping(); core.stop(); releaseFixture.run();
                phase[0]=3; helper.succeed();
            }
        });
    }
    private static WorldPosition position(String dimension,BlockPos pos) { return new WorldPosition(dimension,pos.getX(),pos.getY(),pos.getZ()); }
}
