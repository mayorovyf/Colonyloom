package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("colonyloom")
@PrefixGameTestTemplate(false)
public final class AdmissionGameTests {
    @GameTest(template="identity_empty",batch="stage04_admission")
    public static void maximumFoundingScansShareGlobalAndClientBoundsWithoutPartialPublication(GameTestHelper helper) {
        var level=helper.getLevel();String dimension=level.dimension().location().toString();
        var validation=new io.github.kpuctajluk.colonyloom.minecraft.runtime.FoundingTerritoryValidation();
        var first=foundingPlayer(helper,"FoundingFirst");var second=foundingPlayer(helper,"FoundingSecond");
        ServerRuntime core=ServerRuntime.start(Thread.currentThread());core.configureCommands(() -> {},List.of());
        var a=new Territory(dimension,1_000_000,1_000_000,1_000_127,1_000_127);
        var b=new Territory(dimension,1_000_128,1_000_000,1_000_255,1_000_127);
        var c=new Territory(dimension,1_000_256,1_000_000,1_000_383,1_000_127);
        var firstContext=foundingContext(core,validation,first,0);
        boolean initiallyUnloaded=level.getChunkSource().getChunkNow(a.minX()>>4,a.minZ()>>4)==null;
        long started=System.nanoTime();
        core.commands().createColony(firstContext,UUID.randomUUID(),"Full128",a);
        long fullScanNanos=System.nanoTime()-started;
        var before=core.registry().snapshot();
        long deniedStarted=System.nanoTime();
        expectFoundingLimit(helper,() -> core.commands().createColony(foundingContext(core,validation,second,0),UUID.randomUUID(),"Global denied",b));
        long competingDeniedNanos=System.nanoTime()-deniedStarted;
        expectFoundingLimit(helper,() -> core.commands().createColony(foundingContext(core,validation,first,1),UUID.randomUUID(),"Client denied",b));
        helper.assertTrue(core.registry().snapshot().equals(before),"Denied scan published colony or revised authority");
        core.commands().createColony(foundingContext(core,validation,second,1),UUID.randomUUID(),"Other client",b);
        core.commands().createColony(foundingContext(core,validation,first,20),UUID.randomUUID(),"Window expired",c);
        helper.assertTrue(core.registry().colonies().size()==3,"Full 128x128 scan or expired client window was denied");
        helper.assertTrue(!initiallyUnloaded||level.getChunkSource().getChunkNow(a.minX()>>4,a.minZ()>>4)==null,"Founding permission validation loaded a chunk");
        System.out.println("COLONYLOOM_FOUNDING_BOUND positions=16384 globalPerTick=16384 clientPer20Ticks=16384 fullCommandNanos="+fullScanNanos
                +" competingDeniedNanos="+competingDeniedNanos+" colonies=3 noPartialPublication=true");
        core.beginStopping();core.stop();helper.succeed();
    }

    @GameTest(template="identity_empty",batch="stage04_admission")
    public static void lateWorldBorderFailureConsumesScanBudgetButPublishesNothing(GameTestHelper helper) {
        var level=helper.getLevel();var player=foundingPlayer(helper,"FoundingBorder");
        var validation=new io.github.kpuctajluk.colonyloom.minecraft.runtime.FoundingTerritoryValidation();
        ServerRuntime core=ServerRuntime.start(Thread.currentThread());core.configureCommands(() -> {},List.of());
        int maxZ=(int)Math.ceil(level.getWorldBorder().getMaxZ())+1;
        var crossing=new Territory(level.dimension().location().toString(),1_000_000,maxZ-127,1_000_127,maxZ);
        var before=core.registry().snapshot();UUID rejected=UUID.randomUUID();
        try {
            core.commands().createColony(foundingContext(core,validation,player,0),rejected,"Crosses border",crossing);
            helper.fail("Founding accepted a late territory cell beyond the real world border");
        } catch(SecurityException expected) {
            helper.assertTrue(expected.getMessage().equals("Territory permission/world border denied"),"Unexpected permission failure: "+expected);
        }
        helper.assertTrue(core.registry().snapshot().equals(before)&&!core.registry().usedId(rejected)&&core.admission().used(Resource.COLONIES)==0,"Failed scan published partial state or consumed colony admission");
        var safe=new Territory(level.dimension().location().toString(),1_000_000,1_000_000,1_000_127,1_000_127);
        expectFoundingLimit(helper,() -> validation.validate(0,foundingPlayer(helper,"BorderGlobal"),safe));
        expectFoundingLimit(helper,() -> validation.validate(1,player,safe));
        validation.validate(20,player,safe);
        core.beginStopping();core.stop();helper.succeed();
    }

    @GameTest(template="identity_empty",batch="stage04_admission")
    public static void foundingClientAccountingIsBoundedAndExpiresWithoutEvictingLiveWindows(GameTestHelper helper) {
        var validation=new io.github.kpuctajluk.colonyloom.minecraft.runtime.FoundingTerritoryValidation();
        var territory=new Territory(helper.getLevel().dimension().location().toString(),1_000_000,1_000_000,1_000_015,1_000_015);
        for(int index=0;index<io.github.kpuctajluk.colonyloom.minecraft.runtime.FoundingTerritoryValidation.MAX_CLIENT_WINDOWS;index++)
            validation.validate(0,foundingPlayer(helper,"FoundingSlot"+index),territory);
        var extra=foundingPlayer(helper,"FoundingExtra");
        expectFoundingLimit(helper,() -> validation.validate(1,extra,territory));
        validation.validate(20,extra,territory);
        helper.succeed();
    }

    private static net.minecraft.server.level.ServerPlayer foundingPlayer(GameTestHelper helper,String name) {
        var player=new net.minecraft.server.level.ServerPlayer(helper.getLevel().getServer(),helper.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(),name),net.minecraft.server.level.ClientInformation.createDefault());
        player.moveTo(1_000_000.5,64,1_000_000.5,0,0);return player;
    }

    private static io.github.kpuctajluk.colonyloom.core.command.ColonyCommands.CommandContext foundingContext(ServerRuntime core,
            io.github.kpuctajluk.colonyloom.minecraft.runtime.FoundingTerritoryValidation validation,net.minecraft.server.level.ServerPlayer player,long tick) {
        return new io.github.kpuctajluk.colonyloom.core.command.ColonyCommands.CommandContext(player.getUUID(),false,
                new io.github.kpuctajluk.colonyloom.core.command.ColonyCommands.PhysicalChecks() {
                    public void validateTerritory(Territory territory) {
                        var before=core.registry().snapshot();validation.validate(tick,player,territory);
                        if(!before.equals(core.registry().snapshot()))throw new AssertionError("Physical validation published colony state");
                    }
                    public void validateCitizenPosition(ColonyRuntime colony,WorldPosition position) {}
                    public void validateRecovery(ColonyRuntime colony,List<CitizenRecord> citizens,List<io.github.kpuctajluk.colonyloom.core.citizen.BindingRegistry.Observation> observations) {}
                });
    }

    private static void expectFoundingLimit(GameTestHelper helper,Runnable action) {
        try {action.run();helper.fail("Founding validation exceeded admitted physical budget");}
        catch(IllegalStateException expected) {helper.assertTrue(expected.getMessage().equals("FOUNDING_VALIDATION_LIMIT"),"Unexpected founding failure: "+expected);}
    }

    @GameTest(template="identity_empty",batch="stage04_admission",timeoutTicks=400)
    public static void inactiveOwnClockNoCatchupAndVanillaDamage(GameTestHelper helper) {
        run(helper,false);
    }
    @GameTest(template="identity_empty",batch="stage04_admission",timeoutTicks=500)
    public static void sparseAdmissionStillCountsActualEntityTicksWithoutOfflineCatchup(GameTestHelper helper) {
        run(helper,true);
    }
    @GameTest(template="identity_empty",batch="stage04_admission",timeoutTicks=600)
    public static void nativeBoundaryRolloverRetainsChargedOldCoverage(GameTestHelper helper) {
        runBoundary(helper,true);
    }
    @GameTest(template="identity_empty",batch="stage04_admission",timeoutTicks=600)
    public static void nativeBoundaryRolloverAtCapacityDoesNotCatchUp(GameTestHelper helper) {
        runBoundary(helper,false);
    }
    @GameTest(template="identity_empty",batch="stage04_admission",timeoutTicks=600)
    public static void nativePendingBoundaryKeepsOriginalLoadedWhileOwnClockPaused(GameTestHelper helper) {
        runBoundary(helper,true,20);
    }
    @GameTest(template="identity_empty",batch="stage14_ready_originals",timeoutTicks=1800)
    public static void threeHundredReadyOriginalsResumeOnNativeTicksBeforeBusyMaintenance(GameTestHelper helper) {
        var level=helper.getLevel();String dimension=level.dimension().location().toString();
        int x=460<<4,z=460<<4,y=80;
        var centers=List.of(new ChunkKey(dimension,460,460),new ChunkKey(dimension,461,460),
                new ChunkKey(dimension,460,461),new ChunkKey(dimension,461,461));
        UUID colony=UUID.randomUUID(),bootstrap=UUID.randomUUID();
        ServerRuntime core=ServerRuntime.start(Thread.currentThread());core.configureCommands(() -> {},List.of());
        var limits=core.admission().limits().scale300Capacity().withBudget(Budget.DIRTY_RESCAN_OBJECTS,1);
        core.updateLimits(limits);
        core.registry().addColony(new ColonyRuntime(colony,"Busy ready originals",new Territory(dimension,x,z,x+31,z+31),UUID.randomUUID(),Map.of(),1,1,false,null,false));
        var access=new NeoForgeChunkAccess(level.getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime")));
        var chunks=new ChunkDemandManager(core.registry(),core.budgets(),access);
        // A charged loaded-only ring keeps the originals resident through the initial zero-clock
        // interval. Entity readiness must subsequently come from paid resident demands, not a player.
        chunks.request(bootstrap,colony,centers,ChunkDemandManager.Readiness.LOADED,Lane.NORMAL,0,false);
        CitizenEntity[] residents=new CitizenEntity[300];UUID[] citizens=new UUID[300],nativeIds=new UUID[300];
        long[] previousClock=new long[300],firstResume=new long[300];int[] previousNativeTicks=new int[300];
        CitizenAdmissionService[] admission={null};int[] phase={0},pausedNative={0};
        boolean[] spawned={false};
        long[] createdAt={0},phaseAt={0},pausedClock={0},readyAt={0},resumedClock={0};
        core.scheduler().beforeWork(tick -> {
            // Model the real quota-one service rotation: one admission visit per eighteen ticks,
            // still alternating its pending continuation with the full resident maintenance cursor.
            if(admission[0]!=null && tick%18==1)admission[0].tick();
            chunks.tick(tick);
        });
        helper.onEachTick(() -> {
            if(phase[0]==6)return;
            if(admission[0]!=null)for(int index=0;index<residents.length;index++) {
                CitizenEntity entity=residents[index];var record=core.registry().citizen(citizens[index]);
                helper.assertTrue(level.getEntity(nativeIds[index])==entity && entity.isAlive() && !entity.isRemoved()
                        && citizens[index].equals(entity.citizenId()) && entity.bindingEpoch()==1
                        && nativeIds[index].equals(record.entityId()) && record.bindingEpoch()==1,"Busy maintenance replaced an original UUID/epoch");
                long delta=record.activeTimeTicks()-previousClock[index];int nativeDelta=entity.tickCount-previousNativeTicks[index];
                helper.assertTrue(delta>=0 && delta<=nativeDelta && nativeDelta<=1,"Own time advanced without a corresponding actual native tick");
                if(phase[0]==1 && firstResume[index]==0)helper.assertTrue(record.activeTimeTicks()<=1,"Initial ready-domain resume caught up suspended native time");
                if(delta>0)helper.assertTrue(record.admission()==CitizenRecord.Admission.ACTIVE
                        && record.readiness()==CitizenRecord.Readiness.READY && level.isPositionEntityTicking(entity.blockPosition())
                        && admission[0].coverage(citizens[index]).ready(),"Own time escaped exact ready native-domain guards");
                if(firstResume[index]==0 && record.activeTimeTicks()>0)firstResume[index]=core.serverTick()-createdAt[0];
                previousClock[index]=record.activeTimeTicks();previousNativeTicks[index]=entity.tickCount;
            }
            core.tick(core.serverTick()+1);
            helper.assertTrue(core.budgets().used(Budget.DIRTY_RESCAN_OBJECTS)<=1,"Busy ready resident cursor exceeded dirty quota one");
            helper.assertTrue(core.budgets().used(Budget.CHUNK_REQUESTS)<=limits.budget(Budget.CHUNK_REQUESTS),"Busy ready resident cursor exceeded native ticket quota");
            helper.assertTrue(core.admission().used(Resource.CHUNK_DEMANDS)<=limits.resource(Resource.CHUNK_DEMANDS)
                    && chunks.footprint()<=limits.resource(Resource.LOADED_FOOTPRINT)
                    && chunks.blockTicking()<=limits.resource(Resource.BLOCK_TICKING)
                    && chunks.entityTicking()<=limits.resource(Resource.ENTITY_TICKING),"Busy ready residents exceeded charged capacity");
            if(admission[0]!=null)for(int index=0;index<residents.length;index++)
                helper.assertTrue(core.registry().citizen(citizens[index]).activeTimeTicks()==previousClock[index],"Paid maintenance advanced own time without an actual native tick");
            if(phase[0]==0 && chunks.ready(bootstrap) && !spawned[0]) {
                for(var center:centers)helper.assertTrue(!access.ready(center,ChunkDemandManager.Readiness.ENTITY_TICKING),"Loaded-only fixture fabricated native entity readiness");
                for(int dx=0;dx<32;dx++)for(int dz=0;dz<32;dz++) {
                    BlockPos pos=new BlockPos(x+dx,y,z+dz);
                    level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
                    for(int dy=0;dy<3;dy++)level.setBlockAndUpdate(pos.above(dy),Blocks.AIR.defaultBlockState());
                }
                for(int index=0;index<residents.length;index++) {
                    CitizenEntity entity=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
                    if(entity==null)throw new IllegalStateException("Busy native resident factory unavailable");
                    UUID citizen=UUID.randomUUID();entity.initializeIdentity(citizen,1);
                    entity.moveTo(x+2+(index%20)*1.4,y,z+2+(index/20)*1.4,0,0);
                    BlockPos pos=entity.blockPosition();
                    core.registry().addCitizen(new CitizenRecord(citizen,colony,entity.getUUID(),1,null,null,null,null,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.INACTIVE,CitizenRecord.Readiness.UNKNOWN,0,Map.of("food",1200L),new WorldPosition(dimension,pos.getX(),pos.getY(),pos.getZ()),1),ignored -> {
                        if(!level.addFreshEntity(entity))throw new IllegalStateException("Busy original native spawn refused");
                    });
                    residents[index]=entity;citizens[index]=citizen;nativeIds[index]=entity.getUUID();previousNativeTicks[index]=entity.tickCount;
                }
                spawned[0]=true;createdAt[0]=core.serverTick();
            }
            if(phase[0]==0 && spawned[0]) {
                helper.assertTrue(core.serverTick()-createdAt[0]<=1200,"Original native residents did not become visible within1200 ticks");
                helper.assertTrue(chunks.entityTicking()==0,"Loaded-only bootstrap supplied charged native entity readiness");
                boolean allVisible=true;
                for(int index=0;index<residents.length;index++) {
                    CitizenEntity entity=residents[index];var record=core.registry().citizen(citizens[index]);
                    helper.assertTrue(entity.isAlive() && !entity.isRemoved() && nativeIds[index].equals(entity.getUUID())
                            && citizens[index].equals(entity.citizenId()) && entity.bindingEpoch()==1
                            && nativeIds[index].equals(record.entityId()) && record.bindingEpoch()==1,"Loaded-only setup replaced an original UUID/epoch");
                    // No managed callback is installed yet: physical native ticks do not grant own time.
                    helper.assertTrue(record.admission()==CitizenRecord.Admission.INACTIVE && record.activeTimeTicks()==0,
                            "Loaded-only setup advanced an original admission own clock: index="+index
                                    +" elapsed="+(core.serverTick()-createdAt[0])+" admission="+record.admission()
                                    +" ownClock="+record.activeTimeTicks()+" nativeTicks="+entity.tickCount
                                    +" nativeEntityTicking="+level.isPositionEntityTicking(entity.blockPosition())
                                    +" visible="+(level.getEntity(nativeIds[index])==entity));
                    allVisible &= level.getEntity(nativeIds[index])==entity;
                }
                // Full chunk availability does not prove publication of its native tracked
                // entity sections. Bind only after all exact originals appear in the lookup.
                if(!allVisible)return;
                // Tracking publication may run the native join hook, which quarantines an
                // incarnation unknown to the production runtime. Bind the fixture's originals
                // only after that publication, before installing their admission callbacks.
                for(int index=0;index<residents.length;index++) {
                    CitizenEntity entity=residents[index];
                    core.bindings().observe(citizens[index],nativeIds[index],entity.bindingEpoch());
                    entity.setQuarantined(false);
                    core.commands().updateCitizenReadiness(citizens[index],CitizenRecord.Readiness.READY);
                    previousNativeTicks[index]=entity.tickCount;
                }
                admission[0]=new CitizenAdmissionService(level.getServer(),core,chunks);phase[0]=1;
                for(UUID citizen:citizens) {
                    var record=core.registry().citizen(citizen);var coverage=admission[0].coverage(citizen);
                    helper.assertTrue(record.admission()==CitizenRecord.Admission.INACTIVE && record.activeTimeTicks()==0
                            && coverage.pending() && !coverage.admitted() && !coverage.ready()
                            && citizen.equals(coverage.demandOwner()) && coverage.desiredCenter().equals(coverage.observedCenter()),
                            "Initial unready native originals were not truthfully service-paused");
                }
                // Loaded-ring sharing never makes a resident's37 desired-state links free.
                helper.assertTrue(core.admission().used(Resource.CHUNK_DEMANDS)==residents.length*37+41,
                        "Initial original demands were not independently charged beside the loaded-only bootstrap");
            } else if(phase[0]==1) {
                helper.assertTrue(core.serverTick()-createdAt[0]<=1200,"Ready original resident waited more than1200 ticks for busy maintenance");
                boolean allReady=true;
                for(int index=0;index<residents.length;index++) {
                    var record=core.registry().citizen(citizens[index]);
                    allReady &= firstResume[index]>0 && record.admission()==CitizenRecord.Admission.ACTIVE
                            && chunks.ready(citizens[index]) && level.isPositionEntityTicking(residents[index].blockPosition());
                }
                if(!allReady)return;
                int target=residents.length-1;var coverage=admission[0].coverage(citizens[target]);
                helper.assertTrue(firstResume[target]<=1200 && coverage.pending() && coverage.admitted() && coverage.ready()
                        && coverage.desiredCenter().equals(coverage.observedCenter()) && chunks.ready(coverage.demandOwner()),"Ready current request did not reactivate ahead of its busy paid admission cursor");
                helper.assertTrue(chunks.footprint()==36 && chunks.blockTicking()==16 && chunks.entityTicking()==4,"Shared300-resident native domains expanded or double-charged rings");
                helper.assertTrue(core.admission().used(Resource.LOADED_FOOTPRINT,Lane.NORMAL)==36
                        && core.admission().used(Resource.LOADED_FOOTPRINT,Lane.SERVICE)==0,
                        "Original resident fixture borrowed an uncharged independent service reserve");
                // Explicit INACTIVE while the service is already eligible is not a service pause.
                core.commands().updateCitizenAdmission(citizens[target],CitizenRecord.Admission.INACTIVE);
                pausedClock[0]=core.registry().citizen(citizens[target]).activeTimeTicks();pausedNative[0]=residents[target].tickCount;
                phaseAt[0]=core.serverTick();phase[0]=2;
            } else if(phase[0]==2) {
                int target=residents.length-1;var record=core.registry().citizen(citizens[target]);
                helper.assertTrue(record.admission()==CitizenRecord.Admission.INACTIVE && record.activeTimeTicks()==pausedClock[0],"Native ready callback overrode explicit INACTIVE control");
                if(core.serverTick()-phaseAt[0]<20)return;
                helper.assertTrue(residents[target].tickCount>pausedNative[0],"Explicit INACTIVE fixture did not actually native-tick");
                // A still-loaded native resident must not infer READY from physical presence after
                // its authoritative incarnation is unloaded; no managed observe shortcut is used.
                core.bindings().unload(nativeIds[target]);core.commands().updateCitizenReadiness(citizens[target],CitizenRecord.Readiness.UNKNOWN);
                phaseAt[0]=core.serverTick();phase[0]=3;
            } else if(phase[0]==3) {
                int target=residents.length-1;var record=core.registry().citizen(citizens[target]);
                helper.assertTrue(record.readiness()==CitizenRecord.Readiness.UNKNOWN && record.admission()==CitizenRecord.Admission.INACTIVE
                        && record.activeTimeTicks()==pausedClock[0],"Unloaded/UNKNOWN native embodiment regained own time");
                if(core.serverTick()-phaseAt[0]<20 || residents[target].tickCount==pausedNative[0])return;
                core.bindings().observe(citizens[target],nativeIds[target],1);
                core.commands().updateCitizenReadiness(citizens[target],CitizenRecord.Readiness.READY);readyAt[0]=core.serverTick();
                pausedNative[0]=residents[target].tickCount;
                // Advancing only runtime cursors cannot backfill any native own clock, even with
                // all current desired domains charged and physically READY during the interval.
                for(int tick=0;tick<240;tick++) {
                    core.tick(core.serverTick()+1);
                    helper.assertTrue(core.budgets().used(Budget.DIRTY_RESCAN_OBJECTS)<=1,"Non-native interval exceeded dirty quota one");
                }
                for(int index=0;index<residents.length;index++)helper.assertTrue(core.registry().citizen(citizens[index]).activeTimeTicks()==previousClock[index]
                        && residents[index].tickCount==previousNativeTicks[index],"Non-native interval caught up an original own clock");
                var coverage=admission[0].coverage(citizens[target]);
                helper.assertTrue(core.registry().citizen(citizens[target]).admission()==CitizenRecord.Admission.INACTIVE
                        && coverage.pending() && coverage.ready() && chunks.ready(coverage.demandOwner()),"Busy maintenance fixture did not retain the exact ready pending continuation");
                phase[0]=4;
            } else if(phase[0]==4) {
                int target=residents.length-1;var record=core.registry().citizen(citizens[target]);
                helper.assertTrue(core.serverTick()-readyAt[0]<=1200,"Exact ready native original did not resume within1200 ticks");
                if(record.admission()!=CitizenRecord.Admission.ACTIVE)return;
                helper.assertTrue(record.activeTimeTicks()==pausedClock[0]+1 && residents[target].tickCount==pausedNative[0]+1,"Native regrant caught up suspended or non-native time");
                helper.assertTrue(admission[0].coverage(citizens[target]).pending(),"Resume relied on the busy paid admission cursor");
                resumedClock[0]=record.activeTimeTicks();pausedNative[0]=residents[target].tickCount;phaseAt[0]=core.serverTick();phase[0]=5;
            } else if(phase[0]==5 && core.serverTick()-phaseAt[0]>=10) {
                int target=residents.length-1;var record=core.registry().citizen(citizens[target]);
                helper.assertTrue(record.activeTimeTicks()>resumedClock[0]
                        && record.activeTimeTicks()-resumedClock[0]==residents[target].tickCount-pausedNative[0],"Readmitted original own time diverged from actual native ticks");
                System.out.println("COLONYLOOM_BUSY_READY_ORIGINALS residents=300 dirtyQuota=1 initialResume="+firstResume[target]+" nonNativePause=240 nativeResume=1 exactUuidEpoch=true footprint="+chunks.footprint());
                admission[0].close();chunks.close();for(CitizenEntity entity:residents)entity.remove(Entity.RemovalReason.DISCARDED);
                core.beginStopping();core.stop();phase[0]=6;helper.succeed();
            }
        });
    }
    private static void runBoundary(GameTestHelper helper,boolean headroom) {
        runBoundary(helper,headroom,0);
    }
    private static void runBoundary(GameTestHelper helper,boolean headroom,int delayedGrantTicks) {
        var level=helper.getLevel();String dimension=level.dimension().location().toString();
        ChunkKey oldCenter=new ChunkKey(dimension,headroom?(delayedGrantTicks>0?440:420):430,420);
        ChunkKey newCenter=new ChunkKey(dimension,oldCenter.x()+1,oldCenter.z());
        int edge=(oldCenter.x()+1)<<4,y=80,z=(oldCenter.z()<<4)+8;
        UUID colony=UUID.randomUUID(),citizen=UUID.randomUUID(),bootstrap=UUID.randomUUID();
        ServerRuntime core=ServerRuntime.start(Thread.currentThread());core.configureCommands(() -> {},List.of());
        var limits=core.admission().limits().withBudget(Budget.DIRTY_RESCAN_OBJECTS,1);core.updateLimits(limits);
        core.registry().addColony(new ColonyRuntime(colony,"Native boundary",new Territory(dimension,edge-16,z-16,edge+16,z+16),UUID.randomUUID(),Map.of(),1,1,false,null,false));
        var access=new NeoForgeChunkAccess(level.getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime")));
        var chunks=new ChunkDemandManager(core.registry(),core.budgets(),access);
        chunks.request(bootstrap,colony,List.of(oldCenter),ChunkDemandManager.Readiness.ENTITY_TICKING,Lane.NORMAL,0,false);
        CitizenAdmissionService[] admission={null};CitizenEntity[] nativeCitizen={null};
        UUID[] nativeId={null};int[] phase={0},boundaryTicks={0};
        boolean[] unknownObserved={false},unknownRestored={false};
        long[] displacedAt={0},dirtyBefore={0},clockBefore={0},pausedClock={-1},resumedAt={0},resumedClock={0};
        long[] previousClock={0},firstNativeResume={-1};int[] previousNativeTicks={0};
        core.scheduler().beforeWork(tick -> {
            if(admission[0]==null)chunks.tick(tick);
            else if(phase[0]==3 && tick-displacedAt[0]<delayedGrantTicks)admission[0].tick();
            else if((tick&1)==0){admission[0].tick();chunks.tick(tick);}
            else{chunks.tick(tick);admission[0].tick();}
        });
        helper.onEachTick(() -> {
            if(phase[0]==5)return;
            int sampledPhase=phase[0];
            // Tick counters are sampled before managed cursors: a crossing must already publish
            // its desired domain during CitizenEntity's native post-tick, not this callback.
            if(phase[0]==2 && nativeCitizen[0].getX()>=edge) {
                var record=core.registry().citizen(citizen);
                helper.assertTrue(record.activeTimeTicks()==clockBefore[0],"Unready crossing advanced own clock");
                helper.assertTrue(record.admission()==CitizenRecord.Admission.INACTIVE,"Native unready crossing left stale ACTIVE admission");
                var coverage=admission[0].coverage(citizen);
                helper.assertTrue(coverage.pending() && newCenter.equals(coverage.desiredCenter())
                        && newCenter.equals(coverage.observedCenter()) && !coverage.ready(),"Native crossing lacks truthful charged pending desired center");
                helper.assertTrue(core.budgets().totalConsumed(Budget.DIRTY_RESCAN_OBJECTS)==dirtyBefore[0],"Native crossing consumed an off-cycle dirty token");
                helper.assertTrue(!chunks.admitted(newCenter),"Native callback admitted physical chunk state without a cursor");
                helper.assertTrue(!access.ready(newCenter,ChunkDemandManager.Readiness.ENTITY_TICKING),"Fixture silently supplied new-center entity readiness");
                helper.assertTrue(core.admission().used(Resource.CHUNK_DEMANDS)==(headroom?74:37),"Replacement desired state was not immediately charged");
                helper.assertTrue(headroom==chunks.admitted(oldCenter),"Old coverage retention ignored available headroom");
                nativeCitizen[0].setDeltaMovement(Vec3.ZERO);
                pausedClock[0]=record.activeTimeTicks();displacedAt[0]=core.serverTick();phase[0]=3;
            }
            if(phase[0]>=3 && level.getEntity(nativeId[0]) instanceof CitizenEntity loaded && loaded!=nativeCitizen[0]) {
                helper.assertTrue(citizen.equals(loaded.citizenId()) && loaded.bindingEpoch()==1,"Reload changed original citizen binding");
                core.bindings().observe(citizen,loaded.getUUID(),loaded.bindingEpoch());
                loaded.setQuarantined(false);nativeCitizen[0]=loaded;
                previousNativeTicks[0]=loaded.tickCount;
            }
            if(admission[0]!=null) {
                var record=core.registry().citizen(citizen);
                long delta=record.activeTimeTicks()-previousClock[0];int nativeDelta=nativeCitizen[0].tickCount-previousNativeTicks[0];
                helper.assertTrue(delta>=0 && delta<=nativeDelta && nativeDelta>=0 && nativeDelta<=1,"Boundary own time advanced without a corresponding actual native tick");
                if(delta>0)helper.assertTrue(record.admission()==CitizenRecord.Admission.ACTIVE
                        && record.readiness()==CitizenRecord.Readiness.READY && level.isPositionEntityTicking(nativeCitizen[0].blockPosition())
                        && admission[0].coverage(citizen).ready(),"Boundary own time escaped exact ready native-domain guards");
                if(sampledPhase==3 && firstNativeResume[0]<0) {
                    // The crossing sample establishes the pause; only a later actual native
                    // sample can prove regrant, independent of paid retained-domain cleanup.
                    helper.assertTrue(previousClock[0]==pausedClock[0],"Boundary resume lost its exact paused baseline");
                    helper.assertTrue(record.activeTimeTicks()==pausedClock[0]+delta,"Boundary readmission caught up paused own time");
                    if(delta>0) {
                        helper.assertTrue(delta==1 && nativeDelta==1 && record.activeTimeTicks()==pausedClock[0]+1,
                                "Boundary first ready native tick did not advance exactly one own tick");
                        helper.assertTrue(record.activeTimeTicks()<=clockBefore[0]+core.serverTick()-displacedAt[0],"Boundary readmission caught up unsampled own time");
                        firstNativeResume[0]=core.serverTick();
                    }
                }
                previousClock[0]=record.activeTimeTicks();previousNativeTicks[0]=nativeCitizen[0].tickCount;
            }
            if(delayedGrantTicks>0 && phase[0]==3 && core.serverTick()-displacedAt[0]>=5 && !unknownObserved[0]) {
                core.commands().updateCitizenReadiness(citizen,CitizenRecord.Readiness.UNKNOWN);
                admission[0].observe(citizen);
                var coverage=admission[0].coverage(citizen);
                helper.assertTrue(core.admission().used(Resource.CHUNK_DEMANDS)==74
                        && newCenter.equals(coverage.desiredCenter()) && coverage.pending(),"Transient UNKNOWN cancelled original charged replacement desire");
                helper.assertTrue(!nativeCitizen[0].isRemoved() && nativeCitizen[0].isAlive()
                        && nativeCitizen[0].getUUID().equals(nativeId[0]),"Transient UNKNOWN lost exact loaded original");
                unknownObserved[0]=true;
            }
            if(unknownObserved[0] && !unknownRestored[0] && core.serverTick()-displacedAt[0]>=8) {
                core.commands().updateCitizenReadiness(citizen,CitizenRecord.Readiness.READY);
                admission[0].observe(citizen);unknownRestored[0]=true;
            }
            core.tick(core.serverTick()+1);
            helper.assertTrue(core.budgets().used(Budget.DIRTY_RESCAN_OBJECTS)<=1,"Boundary cursor exceeded quota one");
            helper.assertTrue(core.budgets().used(Budget.CHUNK_REQUESTS)<=limits.budget(Budget.CHUNK_REQUESTS),"Boundary ticket acquisition exceeded quota");
            if(admission[0]!=null)helper.assertTrue(core.registry().citizen(citizen).activeTimeTicks()==previousClock[0]
                    && nativeCitizen[0].tickCount==previousNativeTicks[0],"Boundary paid maintenance advanced own time without an actual native tick");
            if(phase[0]==0 && chunks.ready(bootstrap)) {
                // No forced neighbour or query loading: the original native ticket's loaded ring
                // must publish before terrain is touched, while its neighbour stays non-entity-ticking.
                for(int x=edge-3;x<=edge+3;x++)for(int dz=-1;dz<=1;dz++)
                    if(!access.ready(new ChunkKey(dimension,x>>4,(z+dz)>>4),ChunkDemandManager.Readiness.LOADED))return;
                helper.assertTrue(!access.ready(newCenter,ChunkDemandManager.Readiness.ENTITY_TICKING),"Boundary fixture already entity-ticks neighbour");
                for(int x=edge-3;x<=edge+3;x++)for(int dz=-1;dz<=1;dz++) {
                    BlockPos pos=new BlockPos(x,y,z+dz);
                    level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());level.setBlockAndUpdate(pos.above(),Blocks.AIR.defaultBlockState());
                }
                // Place the real citizen last in a sparse resident cursor. The pending native event
                // must not wait for all these unbound residents to be rescanned after crossing.
                for(int i=0;i<12;i++) {
                    UUID idle=UUID.randomUUID();
                    core.registry().addCitizen(new CitizenRecord(idle,colony,UUID.randomUUID(),1,null,null,null,null,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.INACTIVE,CitizenRecord.Readiness.UNKNOWN,0,Map.of("food",1200L),new WorldPosition(dimension,edge-2,y,z),1),ignored -> {});
                }
                CitizenEntity entity=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
                if(entity==null)throw new IllegalStateException("Citizen factory unavailable");
                entity.initializeIdentity(citizen,1);entity.moveTo(edge-0.5,y,z+0.5,0,0);
                core.registry().addCitizen(new CitizenRecord(citizen,colony,entity.getUUID(),1,null,null,null,null,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.INACTIVE,CitizenRecord.Readiness.UNKNOWN,0,Map.of("food",1200L),new WorldPosition(dimension,edge-1,y,z),1),ignored -> {
                    if(!level.addFreshEntity(entity))throw new IllegalStateException("Physical spawn refused");
                });
                core.bindings().observe(citizen,entity.getUUID(),1);entity.setQuarantined(false);core.commands().updateCitizenReadiness(citizen,CitizenRecord.Readiness.READY);
                nativeCitizen[0]=entity;nativeId[0]=entity.getUUID();previousNativeTicks[0]=entity.tickCount;
                admission[0]=new CitizenAdmissionService(level.getServer(),core,chunks);phase[0]=1;
            } else if(phase[0]==1 && chunks.ready(citizen) && core.registry().citizen(citizen).activeTimeTicks()>=3) {
                chunks.release(bootstrap);
                if(!headroom) {
                    // Loaded NORMAL/CRITICAL capacity is31-floor(31/8)=28: one25-cell
                    // ring fits, but the30-cell adjacent union does not. Block/entity resources
                    // are unpartitioned;9/1 fits one native ring.37 links fits one desired owner.
                    core.updateLimits(limits.withResource(Resource.CHUNK_DEMANDS,37).withResource(Resource.LOADED_FOOTPRINT,31)
                            .withResource(Resource.BLOCK_TICKING,9).withResource(Resource.ENTITY_TICKING,1));
                    chunks.limitsUpdated();
                }
                helper.assertTrue(!access.ready(newCenter,ChunkDemandManager.Readiness.ENTITY_TICKING),"Initial citizen ticket expanded entity readiness");
                clockBefore[0]=core.registry().citizen(citizen).activeTimeTicks();dirtyBefore[0]=core.budgets().totalConsumed(Budget.DIRTY_RESCAN_OBJECTS);
                // Vanilla travel/move/collision runs during the next actual entity tick; no teleport
                // or direct managed callback is used to cross this native chunk boundary.
                nativeCitizen[0].setDeltaMovement(new Vec3(0.8,0,0));phase[0]=2;
            } else if(phase[0]==2) {
                helper.assertTrue(++boundaryTicks[0]<=10,"Ordinary native displacement never crossed boundary");
            } else if(phase[0]==3) {
                helper.assertTrue(core.serverTick()-displacedAt[0]<80,"Pending boundary waited for sparse resident round-robin: state="+chunks.state(citizen)
                        +" admitted="+chunks.admitted(newCenter)+" ready="+access.ready(newCenter,ChunkDemandManager.Readiness.ENTITY_TICKING)
                        +" charged="+core.admission().used(Resource.CHUNK_DEMANDS)+" removed="+nativeCitizen[0].isRemoved());
                helper.assertTrue(core.admission().used(Resource.CHUNK_DEMANDS)<=core.admission().limits().resource(Resource.CHUNK_DEMANDS),"Rollover exceeded charged demand-state cap");
                helper.assertTrue(chunks.footprint()<=core.admission().limits().resource(Resource.LOADED_FOOTPRINT)
                        && chunks.blockTicking()<=core.admission().limits().resource(Resource.BLOCK_TICKING)
                        && chunks.entityTicking()<=core.admission().limits().resource(Resource.ENTITY_TICKING),"Rollover exceeded full-ring caps");
                if(level.getEntity(nativeId[0]) instanceof CitizenEntity loaded)nativeCitizen[0]=loaded;
                helper.assertTrue(nativeCitizen[0].getUUID().equals(nativeId[0])
                        && citizen.equals(nativeCitizen[0].citizenId()) && nativeCitizen[0].bindingEpoch()==1
                        && core.registry().citizen(citizen).entityId().equals(nativeId[0]),"Pending boundary changed original native identity");
                if(!headroom)helper.assertTrue(core.admission().used(Resource.CHUNK_DEMANDS)==37,"Pending native visibility transition cancelled charged replacement desire");
                if(headroom && !chunks.ready(newCenter,ChunkDemandManager.Readiness.ENTITY_TICKING)) {
                    helper.assertTrue(!nativeCitizen[0].isRemoved() && nativeCitizen[0].isAlive()
                            && nativeCitizen[0].getUUID().equals(nativeId[0]),"Headroom pending boundary unloaded original loaded resident");
                    helper.assertTrue(chunks.admitted(oldCenter)&&chunks.ready(oldCenter,ChunkDemandManager.Readiness.ENTITY_TICKING),"Old charged coverage dropped before replacement readiness");
                }
                var record=core.registry().citizen(citizen);
                if(record.admission()==CitizenRecord.Admission.INACTIVE) {
                    helper.assertTrue(record.activeTimeTicks()==pausedClock[0],"Pending admission accumulated offline own time");
                }
                if(delayedGrantTicks>0 && core.serverTick()-displacedAt[0]<delayedGrantTicks) {
                    helper.assertTrue(record.admission()==CitizenRecord.Admission.INACTIVE
                            && record.activeTimeTicks()==clockBefore[0],"Delayed native ticket admitted resident or advanced paused own clock");
                    helper.assertTrue(admission[0].coverage(citizen).pending()
                            && newCenter.equals(admission[0].coverage(citizen).desiredCenter()),"Delayed grant lost replacement desire");
                    helper.assertTrue(!level.isPositionEntityTicking(nativeCitizen[0].blockPosition()),"Delayed native grant fabricated current-center entity readiness");
                }
                if(firstNativeResume[0]>=0 && record.admission()==CitizenRecord.Admission.ACTIVE && chunks.admitted(newCenter)
                        && chunks.ready(newCenter,ChunkDemandManager.Readiness.ENTITY_TICKING) && core.admission().used(Resource.CHUNK_DEMANDS)==37) {
                    resumedAt[0]=core.serverTick();resumedClock[0]=record.activeTimeTicks();phase[0]=4;
                }
            } else if(phase[0]==4 && core.serverTick()-resumedAt[0]>=10) {
                var record=core.registry().citizen(citizen);CitizenEntity entity=nativeCitizen[0];
                helper.assertTrue(level.getEntity(nativeId[0])==entity && entity.isAlive() && citizen.equals(entity.citizenId())
                        && record.entityId().equals(nativeId[0]) && record.bindingEpoch()==1 && entity.bindingEpoch()==1,"Boundary replaced original native resident identity");
                helper.assertTrue(level.isPositionEntityTicking(entity.blockPosition()) && chunks.admitted(newCenter),"Original native resident did not regain entity-ticking coverage");
                helper.assertTrue(record.activeTimeTicks()>resumedClock[0] && record.activeTimeTicks()<=resumedClock[0]+10,"Boundary own clock did not resume one actual native tick at a time");
                System.out.println("COLONYLOOM_NATIVE_BOUNDARY retained="+headroom+" dirtyQuota=1 elapsed="+(core.serverTick()-displacedAt[0])+" ownClock="+record.activeTimeTicks()+" originalIdentity=true");
                admission[0].close();chunks.close();entity.remove(Entity.RemovalReason.DISCARDED);core.beginStopping();core.stop();phase[0]=5;helper.succeed();
            }
        });
    }
    @GameTest(template="identity_empty",batch="stage04_admission",timeoutTicks=600)
    public static void chargedCoverageWithdrawalPausesResidentBeforeSparseMaintenance(GameTestHelper helper) {
        var level=helper.getLevel();var pos=helper.absolutePos(new BlockPos(1,1,1));
        level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());level.setBlockAndUpdate(pos.above(),Blocks.AIR.defaultBlockState());
        var core=ServerRuntime.start(Thread.currentThread());core.configureCommands(() -> {},List.of());
        var limits=core.admission().limits().withBudget(Budget.DIRTY_RESCAN_OBJECTS,1);core.updateLimits(limits);
        UUID colony=UUID.randomUUID(),id=UUID.randomUUID();String dimension=level.dimension().location().toString();
        core.registry().addColony(new ColonyRuntime(colony,"Withdrawal pause",new Territory(dimension,pos.getX()-16,pos.getZ()-16,pos.getX()+16,pos.getZ()+16),UUID.randomUUID(),Map.of(),1,1,false,null,false));
        var entity=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
        if(entity==null)throw new IllegalStateException("Citizen unavailable");
        entity.initializeIdentity(id,1);entity.moveTo(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5,0,0);
        entity.inventory().setItem(0,new ItemStack(Items.BREAD,3));
        core.registry().addCitizen(new CitizenRecord(id,colony,entity.getUUID(),1,null,null,null,null,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.INACTIVE,CitizenRecord.Readiness.READY,0,Map.of("food",1200L),new WorldPosition(dimension,pos.getX(),pos.getY(),pos.getZ()),1),ignored -> {
            if(!level.addFreshEntity(entity))throw new IllegalStateException("Spawn refused");
        });
        core.bindings().observe(id,entity.getUUID(),1);entity.setQuarantined(false);
        var chunks=new ChunkDemandManager(core.registry(),core.budgets(),new NeoForgeChunkAccess(level.getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime"))));
        var admission=new CitizenAdmissionService(level.getServer(),core,chunks);
        core.scheduler().beforeWork(chunks::tick);
        int[] phase={0};long[] pausedClock={0},pauseTick={0};
        helper.onEachTick(() -> {
            if(phase[0]==3)return;
            if(phase[0]==1)pausedClock[0]=core.registry().citizen(id).activeTimeTicks();
            core.tick(core.serverTick()+1);var record=core.registry().citizen(id);
            if(phase[0]==0 && record.activeTimeTicks()>=3) {
                pausedClock[0]=record.activeTimeTicks();
                core.updateLimits(limits.withResource(Resource.LOADED_FOOTPRINT,1));chunks.limitsUpdated();phase[0]=1;
            } else if(phase[0]==1 && !admission.coverage(id).admitted()) {
                helper.assertTrue(record.admission()==CitizenRecord.Admission.INACTIVE,"Charged withdrawal left stale ACTIVE before sparse maintenance");
                helper.assertTrue(admission.coverage(id).pending(),"Withdrawal lost resident readmission continuation");
                helper.assertTrue(record.activeTimeTicks()==pausedClock[0],"Withdrawal advanced own clock without coverage");
                pauseTick[0]=core.serverTick();phase[0]=2;
            } else if(phase[0]==2) {
                helper.assertTrue(record.admission()==CitizenRecord.Admission.INACTIVE && record.activeTimeTicks()==pausedClock[0],"Uncharged native ticks resumed resident or caught up own time");
                helper.assertTrue(entity.getUUID().equals(record.entityId()) && entity.bindingEpoch()==1 && entity.inventory().getItem(0).getCount()==3,"Withdrawal changed identity or physical cargo");
                if(core.serverTick()-pauseTick[0]<5)return;
                admission.close();chunks.close();entity.remove(Entity.RemovalReason.DISCARDED);core.beginStopping();core.stop();phase[0]=3;helper.succeed();
            }
        });
    }

    @GameTest(template="identity_empty",batch="stage04_admission",timeoutTicks=600)
    public static void nativeReadyResidentWakesFoodWithoutSparseDirtyCursorOrCatchup(GameTestHelper helper) {
        var level=helper.getLevel();var pos=helper.absolutePos(new BlockPos(1,1,1));
        level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());level.setBlockAndUpdate(pos.above(),Blocks.AIR.defaultBlockState());
        var core=ServerRuntime.start(Thread.currentThread());core.configureCommands(() -> {},List.of());
        var limits=core.admission().limits().withBudget(Budget.DIRTY_RESCAN_OBJECTS,1);core.updateLimits(limits);
        UUID colony=UUID.randomUUID(),id=UUID.randomUUID();String dimension=level.dimension().location().toString();
        core.registry().addColony(new ColonyRuntime(colony,"Native pending food wake",new Territory(dimension,pos.getX()-16,pos.getZ()-16,pos.getX()+16,pos.getZ()+16),UUID.randomUUID(),Map.of(),1,1,false,null,false));
        for(int index=0;index<12;index++) {
            UUID idle=UUID.randomUUID();
            core.registry().addCitizen(new CitizenRecord(idle,colony,UUID.randomUUID(),1,null,null,null,null,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.INACTIVE,CitizenRecord.Readiness.UNKNOWN,0,Map.of("food",1200L),new WorldPosition(dimension,pos.getX(),pos.getY(),pos.getZ()),1),ignored -> {});
        }
        var entity=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
        if(entity==null)throw new IllegalStateException("Citizen unavailable");
        entity.initializeIdentity(id,1);entity.moveTo(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5,0,0);
        core.registry().addCitizen(new CitizenRecord(id,colony,entity.getUUID(),1,null,null,null,null,Map.of(),Map.of("food",6),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.INACTIVE,CitizenRecord.Readiness.READY,0,Map.of("food",1200L),new WorldPosition(dimension,pos.getX(),pos.getY(),pos.getZ()),1),ignored -> {if(!level.addFreshEntity(entity))throw new IllegalStateException("Spawn refused");});
        core.bindings().observe(id,entity.getUUID(),1);entity.setQuarantined(false);
        var chunks=new ChunkDemandManager(core.registry(),core.budgets(),new NeoForgeChunkAccess(level.getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime"))));
        var admission=new CitizenAdmissionService(level.getServer(),core,chunks);
        var needs=new io.github.kpuctajluk.colonyloom.gameplay.needs.NeedsController(core.registry(),citizen -> true);
        admission.onFoodNeed(needs::observe);
        boolean[] readyObserved={false},wakeObserved={false};long[] clock={0},dirty={0};
        core.scheduler().beforeWork(tick -> {if(!readyObserved[0])chunks.tick(tick);else if(!wakeObserved[0])needs.tick(tick);});
        helper.onEachTick(() -> {
            core.tick(core.serverTick()+1);var record=core.registry().citizen(id);
            helper.assertTrue(core.budgets().used(Budget.DIRTY_RESCAN_OBJECTS)<=1,"Food wake exceeded original dirty quantum");
            if(!readyObserved[0]&&chunks.ready(id)&&level.isPositionEntityTicking(entity.blockPosition())) {
                readyObserved[0]=true;clock[0]=record.activeTimeTicks();dirty[0]=core.budgets().totalConsumed(Budget.DIRTY_RESCAN_OBJECTS);return;
            }
            if(!readyObserved[0]||record.admission()!=CitizenRecord.Admission.ACTIVE||needs.workForCitizen(id)==null)return;
            wakeObserved[0]=true;
            helper.assertTrue(entity.getUUID().equals(record.entityId())&&entity.bindingEpoch()==1,"Native wake replaced original resident");
            helper.assertTrue(record.activeTimeTicks()>clock[0]&&record.activeTimeTicks()<=clock[0]+3,"Readmission skipped native ticks or caught up sparse service time");
            helper.assertTrue(core.budgets().totalConsumed(Budget.DIRTY_RESCAN_OBJECTS)<=dirty[0]+3,"Native readmission performed a dirty sweep");
            helper.assertTrue(needs.demandForWork(needs.workForCitizen(id).id())!=null,"Food notification lost its exact consumer demand");
            admission.close();chunks.close();entity.remove(Entity.RemovalReason.DISCARDED);core.beginStopping();core.stop();helper.succeed();
        });
    }

    @GameTest(template="identity_empty")
    public static void pendingFoodNotificationSurvivesDeniedQuantumAndInactiveReadiness(GameTestHelper helper) {
        var core=ServerRuntime.start(Thread.currentThread());core.configureCommands(() -> {},List.of());
        var limits=core.admission().limits().withBudget(Budget.DIRTY_RESCAN_OBJECTS,1);core.updateLimits(limits);
        UUID colony=UUID.randomUUID();String dimension=helper.getLevel().dimension().location().toString();
        var pos=new WorldPosition(dimension,0,64,0);core.registry().addColony(new ColonyRuntime(colony,"Pending food debt",new Territory(dimension,0,0,31,31),UUID.randomUUID(),Map.of(),1,1,false,null,false));
        for(int index=0;index<24;index++) {
            UUID id=UUID.randomUUID();core.registry().addCitizen(new CitizenRecord(id,colony,UUID.randomUUID(),1,null,null,null,null,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.INACTIVE,CitizenRecord.Readiness.UNKNOWN,0,Map.of("food",1200L),pos,1),ignored -> {});
        }
        UUID hungry=UUID.randomUUID();core.registry().addCitizen(new CitizenRecord(hungry,colony,UUID.randomUUID(),1,null,null,null,null,Map.of(),Map.of("food",6),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.INACTIVE,CitizenRecord.Readiness.READY,0,Map.of("food",1200L),pos,1),ignored -> {});
        var needs=new io.github.kpuctajluk.colonyloom.gameplay.needs.NeedsController(core.registry(),citizen -> true);needs.observe(hungry);
        core.budgets().beginTick(1);core.budgets().tryConsume(Budget.DIRTY_RESCAN_OBJECTS,Lane.SERVICE);needs.tick(1);
        helper.assertTrue(needs.workForCitizen(hungry)==null,"Denied dirty portion registered an unpaid consumer");
        core.budgets().beginTick(2);needs.tick(2);helper.assertTrue(needs.workForCitizen(hungry)==null,"Inactive resident acquired food consumer");
        core.registry().updateCitizen(core.registry().citizen(hungry).withAdmission(CitizenRecord.Admission.ACTIVE));
        for(int tick=3;tick<=64;tick++) {core.budgets().beginTick(tick);needs.tick(tick);helper.assertTrue(core.budgets().used(Budget.DIRTY_RESCAN_OBJECTS)<=1,"Pending food bypassed the unchanged one-object quantum");}
        var food=needs.workForCitizen(hungry);
        helper.assertTrue(food!=null&&food.subjectId().equals(hungry)&&needs.demandForWork(food.id()).snapshot().ownerId().equals(food.id()),"Paid pending food debt was dropped before readmission or delayed behind24 unrelated residents");
        core.beginStopping();core.stop();helper.succeed();
    }

    private static void run(GameTestHelper helper,boolean sparse) {
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
        core.scheduler().beforeWork(tick -> {chunks.tick(tick);if(!sparse||tick%11==0)admission.tick();});
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
