package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.minecraft.runtime.CitizenAdmissionService;
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
public final class AdmissionGameTests {
    @GameTest(template="identity_empty",batch="stage04_admission",timeoutTicks=400)
    public static void inactiveOwnClockNoCatchupAndVanillaDamage(GameTestHelper helper) {
        var level=helper.getLevel(); BlockPos pos=helper.absolutePos(new BlockPos(1,1,1));
        level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());level.setBlockAndUpdate(pos.above(),Blocks.AIR.defaultBlockState());
        ServerRuntime core=ServerRuntime.start(Thread.currentThread());core.configureCommands(() -> {},List.of());
        var limits=core.admission().limits().withMaxManagedNanos(100_000_000L);core.updateLimits(limits);
        UUID colony=UUID.randomUUID(),citizen=UUID.randomUUID();String dimension=level.dimension().location().toString();
        core.registry().addColony(new ColonyRuntime(colony,"Own clock smoke",new Territory(dimension,pos.getX()-16,pos.getZ()-16,pos.getX()+16,pos.getZ()+16),UUID.randomUUID(),Map.of(),1,1,false,null,false));
        CitizenEntity entity=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
        if(entity==null)throw new IllegalStateException("Citizen factory unavailable");
        entity.initializeIdentity(citizen,1);entity.moveTo(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5,0,0);entity.inventory().setItem(0,new ItemStack(Items.BREAD,3));
        core.registry().addCitizen(new CitizenRecord(citizen,colony,entity.getUUID(),1,null,null,null,null,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.INACTIVE,CitizenRecord.Readiness.UNKNOWN,0,Map.of("food",1200L),new WorldPosition(dimension,pos.getX(),pos.getY(),pos.getZ()),1), ignored -> {
            if(!level.addFreshEntity(entity))throw new IllegalStateException("Physical spawn refused");
        });
        core.bindings().observe(citizen,entity.getUUID(),1);entity.setQuarantined(false);core.commands().updateCitizenReadiness(citizen,CitizenRecord.Readiness.READY);
        var chunks=new ChunkDemandManager(core.registry(),core.budgets(),new NeoForgeChunkAccess(level.getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime"))));
        var admission=new CitizenAdmissionService(level.getServer(),core,chunks);
        core.scheduler().beforeWork(tick -> {chunks.tick(tick);admission.tick();});
        int[] phase={0},foodBeforePause={0};long[] baseline={0},phaseTick={0},timerBeforePause={0};
        helper.onEachTick(() -> {
            core.tick(core.serverTick()+1);CitizenRecord record=core.registry().citizen(citizen);
            if(phase[0]==0&&record.activeTimeTicks()>=10) {
                core.updateLimits(limits.withResource(Resource.LOADED_FOOTPRINT,1));chunks.limitsUpdated();phase[0]=1;
            } else if(phase[0]==1&&record.admission()==CitizenRecord.Admission.INACTIVE) {
                baseline[0]=record.activeTimeTicks();phaseTick[0]=core.serverTick();foodBeforePause[0]=record.food();timerBeforePause[0]=record.foodDecayTicks();phase[0]=2;
                float before=entity.getHealth();entity.hurt(level.damageSources().generic(),2.0F);
                helper.assertTrue(entity.getHealth()<before,"Inactive physical damage suppressed");
            } else if(phase[0]==2) {
                for(int inactiveTick=0;inactiveTick<24000;inactiveTick++)core.tick(core.serverTick()+1);
                record=core.registry().citizen(citizen);
                helper.assertTrue(record.admission()==CitizenRecord.Admission.INACTIVE&&record.activeTimeTicks()==baseline[0],"Inactive manager accumulated own clock during 24000 ticks");
                helper.assertTrue(record.foodDecayTicks()==timerBeforePause[0]&&record.food()==foodBeforePause[0]&&entity.inventory().getItem(0).getCount()==3,"Inactive interval changed food/residual/property");
                core.updateLimits(limits);chunks.limitsUpdated();phase[0]=3;
            } else if(phase[0]==3&&record.admission()==CitizenRecord.Admission.ACTIVE) {
                helper.assertTrue(record.activeTimeTicks()<=baseline[0]+1,"Readmission caught up inactive own time");
                phaseTick[0]=core.serverTick();phase[0]=4;
            } else if(phase[0]==4&&core.serverTick()-phaseTick[0]>=10) {
                helper.assertTrue(record.activeTimeTicks()>baseline[0]&&record.activeTimeTicks()<=baseline[0]+11,"Readmitted own time did not resume normally");
                helper.assertTrue(record.foodDecayTicks()==timerBeforePause[0]-(record.activeTimeTicks()-baseline[0]),"Readmission food countdown caught up paused server ticks");
                System.out.println("COLONYLOOM_INACTIVE_CLOCK pausedTicks=24000 before="+baseline[0]+" after="+record.activeTimeTicks()+" residual="+record.foodDecayTicks()+" physicalDamage=true property=3bread");
                admission.close();chunks.close();entity.remove(Entity.RemovalReason.DISCARDED);core.beginStopping();core.stop();phase[0]=5;helper.succeed();
            }
        });
    }
}
