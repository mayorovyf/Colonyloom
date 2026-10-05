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
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
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
    @GameTest(template = "identity_empty", batch = "stage06_navigation_step", timeoutTicks = 500)
    public static void walkOverOneBlockRiseAndDrop(GameTestHelper helper) {
        helper.onEachTick(walkStep(helper, helper.absolutePos(new BlockPos(1,1,1)), () -> {}, true));
    }
    @GameTest(template = "identity_empty", batch = "stage06_navigation_water", timeoutTicks = 700)
    public static void waterBarrierWaitsUntilPhysicalDrain(GameTestHelper helper) {
        helper.onEachTick(walkStep(helper, helper.absolutePos(new BlockPos(1,1,1)), () -> {}, false, true));
    }
    @GameTest(template = "identity_empty", batch = "stage06_navigation_sparse", timeoutTicks = 1000)
    public static void nativeLocomotionContinuesBetweenBudgetedStatusPolls(GameTestHelper helper) {
        helper.onEachTick(walkStep(helper,helper.absolutePos(new BlockPos(1,1,1)),() -> {},false,false,100));
    }
    @GameTest(template="identity_empty",batch="stage10_navigation_container",timeoutTicks=700)
    public static void cardinalRouteReachesGoalBesideContainerCorner(GameTestHelper helper) {
        helper.onEachTick(walkStep(helper,helper.absolutePos(new BlockPos(1,1,1)),() -> {},false,false,1,true));
    }
    @GameTest(template="identity_empty",batch="stage14_navigation_exhaustion",timeoutTicks=1000)
    public static void traversableLayeredRouteReportsExhaustionThroughRetryAndManagement(GameTestHelper helper) {
        var level=helper.getLevel();
        BlockPos anchor=helper.absolutePos(new BlockPos(1,1,1));
        BlockPos start=new BlockPos(anchor.getX()&~15,anchor.getY()+5,anchor.getZ()&~15);
        BlockPos target=start.offset(39,30,40);
        String dimension=level.dimension().location().toString();
        // Eleven separated supported serpentine layers form one cardinal route longer than
        // the unchanged 8192-node cap, with endpoints inside the existing 64-block domain.
        for(int x=-6;x<=45;x++)for(int z=-2;z<=43;z++)for(int y=-3;y<=33;y++)
            level.setBlock(start.offset(x,y,z),Blocks.AIR.defaultBlockState(),2);
        var witness=new java.util.ArrayList<BlockPos>();
        for(int layer=0;layer<11;layer++) {
            for(int row=0;row<=20;row++) {
                int z=(layer%2==0 ? row : 20-row)*2;
                for(int column=0;column<=39;column++) {
                    int x=(row%2==layer%2 ? column : 39-column);
                    witness.add(start.offset(x,layer*3,z));
                }
                if(row<20)witness.add(start.offset(row%2==layer%2 ? 39 : 0,layer*3,z+(layer%2==0 ? 1 : -1)));
            }
            if(layer<10) {
                int x=layer%2==0 ? 39 : 0,z=layer%2==0 ? 40 : 0,dx=layer%2==0 ? 1 : -1,dz=dx;
                // Leave the upper floor's ceiling horizontally before rising outside it.
                witness.add(start.offset(x+dx,layer*3,z));
                for(int step=1;step<=3;step++)witness.add(start.offset(x+dx*(step+1),layer*3+step,z));
                for(int step=4;step>=0;step--)witness.add(start.offset(x+dx*step,(layer+1)*3,z+dz));
            }
        }
        for(BlockPos feet:witness)level.setBlock(feet.below(),Blocks.STONE.defaultBlockState(),2);
        helper.assertTrue(witness.size()>8192&&witness.getFirst().equals(start)&&witness.getLast().equals(target),"Constructive route does not exceed the production node cap");
        ServerRuntime core=ServerRuntime.start(Thread.currentThread());core.configureCommands(() -> {},List.of());
        core.updateLimits(core.admission().limits().scale300Capacity());
        UUID colony=UUID.randomUUID(),owner=UUID.randomUUID(),citizen=UUID.randomUUID(),workId=UUID.randomUUID();
        core.registry().addColony(new ColonyRuntime(colony,"Search exhaustion",new Territory(dimension,start.getX()-16,start.getZ()-16,start.getX()+64,start.getZ()+64),owner,Map.of(),1,1,false,null,false));
        CitizenEntity entity=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
        if(entity==null)throw new IllegalStateException("Real citizen factory unavailable");
        entity.initializeIdentity(citizen,1);entity.moveTo(start.getX()+0.5,start.getY(),start.getZ()+0.5,0,0);
        UUID nativeId=entity.getUUID();ItemStack carried=new ItemStack(Items.BREAD,3);entity.inventory().setItem(0,carried);
        for(int i=0;i<witness.size();i++) {
            BlockPos feet=witness.get(i);double half=entity.getBbWidth()*0.5;
            var body=new net.minecraft.world.phys.AABB(feet.getX()+0.5-half,feet.getY(),feet.getZ()+0.5-half,feet.getX()+0.5+half,feet.getY()+entity.getBbHeight(),feet.getZ()+0.5+half);
            helper.assertTrue(level.getBlockState(feet.below()).is(Blocks.STONE)&&level.noCollision(entity,body),"Constructive route is not physically standable");
            if(i>0) {
                BlockPos previous=witness.get(i-1);
                helper.assertTrue(Math.abs(previous.getX()-feet.getX())+Math.abs(previous.getZ()-feet.getZ())==1&&Math.abs(previous.getY()-feet.getY())<=1,"Constructive route has a noncardinal gap");
                int horizontalY=Math.max(previous.getY(),feet.getY());
                var horizontal=new net.minecraft.world.phys.AABB(Math.min(previous.getX(),feet.getX())+0.5-half,horizontalY,Math.min(previous.getZ(),feet.getZ())+0.5-half,
                        Math.max(previous.getX(),feet.getX())+0.5+half,horizontalY+entity.getBbHeight(),Math.max(previous.getZ(),feet.getZ())+0.5+half);
                helper.assertTrue(level.noCollision(entity,horizontal),"Constructive route cannot traverse its native horizontal envelope");
                if(feet.getY()!=previous.getY()) {
                    BlockPos lower=feet.getY()<previous.getY()?feet:previous;
                    var vertical=new net.minecraft.world.phys.AABB(lower.getX()+0.5-half,lower.getY(),lower.getZ()+0.5-half,
                            lower.getX()+0.5+half,horizontalY+entity.getBbHeight(),lower.getZ()+0.5+half);
                    helper.assertTrue(level.noCollision(entity,vertical),"Constructive route cannot traverse its native step envelope");
                }
            }
        }
        core.registry().addCitizen(new CitizenRecord(citizen,colony,nativeId,1,null,null,null,null,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.UNKNOWN,0,Map.of("food",1200L),position(dimension,start),1),proposed -> {
            if(!level.addFreshEntity(entity))throw new IllegalStateException("Exhaustion resident spawn refused");
        });
        core.bindings().observe(citizen,nativeId,1);entity.setQuarantined(false);core.commands().updateCitizenReadiness(citizen,CitizenRecord.Readiness.READY);
        var chunks=new ChunkDemandManager(core.registry(),core.budgets(),new NeoForgeChunkAccess(level.getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime"))));
        var backend=new MinecraftNavigationBackend(level.getServer(),core.registry(),chunks);
        var navigation=new NavigationService(core.registry(),core.budgets(),chunks,backend);
        var work=core.workBoard().createMove(workId,colony,position(dimension,target),0,Lane.NORMAL);
        core.workBoard().transition(workId,WorkOrder.State.READY,WorkOrder.Reason.NONE,"move");
        helper.assertTrue(core.workBoard().assign(workId,citizen),"Exhaustion resident assignment refused");
        UUID request=navigation.request(workId,colony,citizen,1,0,work.target(),Lane.NORMAL,0);
        core.scheduler().beforeWork(tick -> {chunks.tick(tick);navigation.tick(tick);});
        core.scheduler().physicalExecutor(WorkOrder.MOVE,new SimulationScheduler.PhysicalExecutor() {
            public void step(WorkOrder current,long tick) {
                helper.assertTrue(request.equals(navigation.request(workId,colony,citizen,1,0,current.target(),Lane.NORMAL,0)),"Exhaustion replaced the original goal");
                if(navigation.state(workId)==NavigationService.State.WAITING)core.workBoard().waitAssigned(workId,navigation.reason(workId),"move");
            }
            public void cancel(UUID id) {navigation.cancel(id);}
        });
        long[] firstExhaustion={-1},firstSearches={0};
        var reasonView=new io.github.kpuctajluk.colonyloom.minecraft.view.ManagementViews.Preparation[1];
        boolean[] reasonProjected={false};
        helper.onEachTick(() -> {
            core.tick(core.serverTick()+1);
            if(reasonView[0]!=null&&!reasonProjected[0]&&reasonView[0].advance(core.budgets())) {
                helper.assertTrue(reasonView[0].result().rows().stream().anyMatch(row -> row.id().equals(workId)&&row.reason().equals("SEARCH_EXHAUSTED")),"Management work view concealed computational exhaustion");
                reasonProjected[0]=true;
            }
            helper.assertTrue(navigation.reason(workId)!=WorkOrder.Reason.UNREACHABLE&&work.waitingReason()!=WorkOrder.Reason.UNREACHABLE,"Traversable capped search was declared unreachable");
            helper.assertTrue(level.getEntity(nativeId)==entity&&entity.bindingEpoch()==1&&entity.inventory().getItem(0)==carried&&carried.getCount()==3,"Exhaustion changed native identity or carried property");
            helper.assertTrue(entity.distanceToSqr(start.getX()+0.5,start.getY(),start.getZ()+0.5)<=0.01,"Exhausted route was physically applied");
            if(navigation.reason(workId)!=WorkOrder.Reason.SEARCH_EXHAUSTED)return;
            helper.assertTrue(work.waitingReason()==WorkOrder.Reason.SEARCH_EXHAUSTED&&((Number)backend.diagnostics().get("nodeHighWater")).intValue()==8192,"Native cap did not propagate to authoritative waiting reason");
            helper.assertTrue(chunks.footprint()==0&&((Number)backend.diagnostics().get("concurrentQueries")).intValue()==0,"Exhaustion retained idle tickets or a query cursor");
            if(firstExhaustion[0]<0) {
                firstExhaustion[0]=core.serverTick();firstSearches[0]=((Number)navigation.diagnostics().get("searchCount")).longValue();
                var subscription=new io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.Subscription(UUID.randomUUID(),UUID.randomUUID(),colony,io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.ViewType.WORK,0,false);
                reasonView[0]=new io.github.kpuctajluk.colonyloom.minecraft.view.ManagementViews.Preparation(core.registry(),owner,subscription,List.of(),List.of(),1,ignored -> null);
                return;
            }
            long searches=((Number)navigation.diagnostics().get("searchCount")).longValue();
            if(core.serverTick()<firstExhaustion[0]+20)helper.assertTrue(searches==firstSearches[0],"Exhaustion spun instead of bounded backoff");
            if(((Number)backend.diagnostics().get("nodeLimitQueries")).longValue()<2||!reasonProjected[0])return;
            helper.assertTrue(searches>firstSearches[0]&&((Number)backend.diagnostics().get("completedQueries")).longValue()==0,"Retry did not repeat the bounded native search");
            navigation.close();chunks.close();entity.remove(Entity.RemovalReason.DISCARDED);core.beginStopping();core.stop();helper.succeed();
        });
    }
    @GameTest(template="identity_empty",batch="stage14_navigation_continuation",timeoutTicks=1200)
    public static void originalSixtyThreeNativeRoutesFinishWithContestedOneUnitConsumers(GameTestHelper helper) {
        var level=helper.getLevel();var origin=helper.absolutePos(new BlockPos(1,1,1));
        String dimension=level.dimension().location().toString();
        for(int x=-2;x<=54;x++)for(int z=-2;z<=38;z++) {
            BlockPos feet=origin.offset(x,0,z);level.setBlockAndUpdate(feet.below(),Blocks.STONE.defaultBlockState());
            for(int y=0;y<3;y++)level.setBlockAndUpdate(feet.above(y),Blocks.AIR.defaultBlockState());
        }
        // Two original lanes require a real multi-portion search around a wide supported wall.
        // Their elevated arenas avoid interference with the other sixty-one physical residents.
        BlockPos searchOrigin=new BlockPos((origin.getX()&~15)+1,origin.getY(),(origin.getZ()&~15)+1);
        for(int arena=0;arena<2;arena++)for(int x=-2;x<=34;x++)for(int z=-2;z<=34;z++) {
            BlockPos feet=searchOrigin.offset(x,5+arena*5,z);level.setBlockAndUpdate(feet.below(),Blocks.STONE.defaultBlockState());
            for(int y=0;y<3;y++)level.setBlockAndUpdate(feet.above(y),Blocks.AIR.defaultBlockState());
            if(x==12&&z<=28)for(int y=0;y<3;y++)level.setBlockAndUpdate(feet.above(y),Blocks.STONE.defaultBlockState());
        }
        ServerRuntime core=ServerRuntime.start(Thread.currentThread());core.configureCommands(() -> {},List.of());
        var limits=core.admission().limits().scale300Capacity()
                .withBudget(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget.DIRTY_RESCAN_OBJECTS,1)
                .withBudget(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget.NAVIGATION_STARTS,1)
                .withMaxManagedNanos(5_000_000);
        core.updateLimits(limits);
        UUID colony=UUID.randomUUID();
        core.registry().addColony(new ColonyRuntime(colony,"Contested original routes",new Territory(dimension,
                origin.getX()-16,origin.getZ()-16,origin.getX()+64,origin.getZ()+48),UUID.randomUUID(),Map.of(),1,1,false,null,false));
        var chunks=new ChunkDemandManager(core.registry(),core.budgets(),new NeoForgeChunkAccess(level.getServer(),
                new TicketController(ResourceLocation.parse("colonyloom:runtime"))));
        var backend=new MinecraftNavigationBackend(level.getServer(),core.registry(),chunks);
        var navigation=new NavigationService(core.registry(),core.budgets(),chunks,backend);
        UUID observationOwner=UUID.randomUUID();
        var observedRegion=new java.util.ArrayList<io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey>();
        for(int x=(origin.getX()-2)>>4;x<=(origin.getX()+54)>>4;x++)for(int z=(origin.getZ()-2)>>4;z<=(origin.getZ()+38)>>4;z++)
            observedRegion.add(new io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey(dimension,x,z));
        boolean[] provisioned={false},initialized={false},closed={false};
        var entities=new java.util.ArrayList<CitizenEntity>();var nativeIds=new java.util.ArrayList<UUID>();
        var citizens=new java.util.ArrayList<UUID>();var works=new java.util.ArrayList<UUID>();
        var targets=new java.util.ArrayList<BlockPos>();var bread=new java.util.ArrayList<ItemStack>();
        Runnable cleanup=() -> {
            if(closed[0])return;closed[0]=true;
            navigation.close();chunks.close();for(CitizenEntity entity:entities)entity.remove(Entity.RemovalReason.DISCARDED);
            core.beginStopping();core.stop();
        };
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo test) {}
            public void testPassed(GameTestInfo test,GameTestRunner runner) {cleanup.run();}
            public void testFailed(GameTestInfo test,GameTestRunner runner) {
                try {
                    System.out.println("COLONYLOOM_NAVIGATION_FAILURE provisioned="+provisioned[0]+" domainsReady="+initialized[0]
                            +" chunks="+chunks.diagnostics(colony)+" native="+backend.diagnostics()
                            +" waiting="+works.stream().filter(work -> !navigation.atTarget(work)).map(work -> work+":"+navigation.reason(work)+":"+chunks.state(work)).toList());
                } finally {cleanup.run();}
            }
            public void testAddedForRerun(GameTestInfo original,GameTestInfo rerun,GameTestRunner runner) {}
        });
        chunks.request(observationOwner,colony,observedRegion,ChunkDemandManager.Readiness.ENTITY_TICKING,Lane.SERVICE,0,true);
        helper.assertTrue(chunks.state(observationOwner)!=ChunkDemandManager.State.BLOCKED,"Observation domain cannot fit the unchanged SERVICE reserve");
        Runnable provision=() -> {
        for(int i=0;i<63;i++) {
            BlockPos start=origin.offset((i%9)*6,0,(i/9)*6),target=start.offset(4,0,0);
            if(i<2) {start=searchOrigin.above(5+i*5);target=start.offset(24,0,24);}
            UUID citizen=UUID.randomUUID(),work=UUID.randomUUID();
            CitizenEntity entity=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
            if(entity==null)throw new IllegalStateException("Real citizen factory unavailable");
            entity.initializeIdentity(citizen,1);entity.moveTo(start.getX()+0.5,start.getY(),start.getZ()+0.5,0,0);
            entities.add(entity);
            ItemStack carried=new ItemStack(Items.BREAD,3);entity.inventory().setItem(0,carried);
            core.registry().addCitizen(new CitizenRecord(citizen,colony,entity.getUUID(),1,null,null,null,null,Map.of(),Map.of("food",20),
                    CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.UNKNOWN,0,Map.of("food",1200L),position(dimension,start),1),proposed -> {
                if(!level.addFreshEntity(entity))throw new IllegalStateException("Original route resident spawn refused");
            });
            core.bindings().observe(citizen,entity.getUUID(),1);entity.setQuarantined(false);
            core.commands().updateCitizenReadiness(citizen,CitizenRecord.Readiness.READY);
            Lane lane=i%2==0?Lane.NORMAL:Lane.CRITICAL;
            core.workBoard().createMove(work,colony,position(dimension,target),0,lane);
            core.workBoard().transition(work,WorkOrder.State.READY,WorkOrder.Reason.NONE,"move");
            helper.assertTrue(core.workBoard().assign(work,citizen),"Original resident assignment failed");
            navigation.request(work,colony,citizen,1,0,position(dimension,target),lane,0);
            nativeIds.add(entity.getUUID());citizens.add(citizen);works.add(work);targets.add(target);bread.add(carried);
        }
        };
        core.scheduler().physicalExecutor(WorkOrder.MOVE,new SimulationScheduler.PhysicalExecutor() {
            public void step(WorkOrder work,long tick) {
                if(navigation.state(work.id())==NavigationService.State.WAITING)
                    core.workBoard().waitAssigned(work.id(),navigation.reason(work.id()),"move");
            }
            public void cancel(UUID workId) {navigation.cancel(workId);}
        });
        core.scheduler().beforeWork(tick -> {
            if(!initialized[0]) {chunks.tick(tick);return;}
            int first=(int)((tick/2)%9);
            for(int offset=0;offset<9;offset++)switch((first+offset)%9) {
                case 0 -> chunks.tick(tick);
                case 2 -> navigation.tick(tick);
                default -> core.budgets().tryConsume(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget.DIRTY_RESCAN_OBJECTS,Lane.SERVICE);
            }
        });
        helper.onEachTick(() -> {
            if(closed[0])return;
            if(!provisioned[0]) {
                core.tick(core.serverTick()+1);
                helper.assertTrue(chunks.state(observationOwner)!=ChunkDemandManager.State.BLOCKED,"Observation domain lost its admissible SERVICE reserve");
                if(!chunks.ready(observationOwner))return;
                provision.run();provisioned[0]=true;return;
            }
            if(!initialized[0]) {
                // This is the native counterpart of the core continuation fixture: admit all
                // original domains before contesting continuation, within the same total deadline.
                chunks.useful(observationOwner);core.tick(core.serverTick()+1);
                helper.assertTrue(core.budgets().used(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget.DIRTY_RESCAN_OBJECTS)<=1
                        &&core.budgets().used(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget.CHUNK_REQUESTS)<=1,"Route setup exceeded frozen global budgets");
                boolean ready=true;
                for(UUID work:works) {
                    helper.assertTrue(chunks.state(work)!=ChunkDemandManager.State.BLOCKED,"Original route domain cannot fit the unchanged ordinary reserve");
                    if(!chunks.ready(work))ready=false;
                }
                if(!ready)return;
                helper.assertTrue(works.size()==63&&((Number)navigation.diagnostics().get("searchCount")).longValue()==0
                        &&((Number)backend.diagnostics().get("completedQueries")).longValue()==0,"Setup bypassed original native search continuation");
                initialized[0]=true;return;
            }
            chunks.useful(observationOwner);
            long searches=((Number)navigation.diagnostics().get("searchCount")).longValue();core.tick(core.serverTick()+1);
            helper.assertTrue(((Number)navigation.diagnostics().get("searchCount")).longValue()-searches<=1,"Native searches exceeded frozen global quantum");
            helper.assertTrue(core.budgets().used(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget.NAVIGATION_STARTS)<=1
                    &&core.budgets().used(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget.DIRTY_RESCAN_OBJECTS)<=1,"Continuation exceeded global budgets");
            boolean all=true;
            for(int i=0;i<63;i++) {
                CitizenEntity entity=entities.get(i);var record=core.registry().citizen(citizens.get(i));
                helper.assertTrue(level.getEntity(nativeIds.get(i))==entity&&record.entityId().equals(nativeIds.get(i))
                        &&record.bindingEpoch()==1&&entity.bindingEpoch()==1,"Route replaced original native embodiment");
                helper.assertTrue(entity.inventory().getItem(0)==bread.get(i)&&bread.get(i).getCount()==3,"Route changed original carried property");
                if(!navigation.atTarget(works.get(i))) {all=false;continue;}
                BlockPos target=targets.get(i);
                helper.assertTrue(entity.distanceToSqr(target.getX()+0.5,target.getY(),target.getZ()+0.5)<=0.01,"Arrival lacks exact original physical goal");
            }
            if(!all)return;
            helper.assertTrue(((Number)backend.diagnostics().get("searchPortions")).longValue()>63,"Native fixture never retained a real pending search portion");
            helper.succeed();
        });
    }
    @GameTest(template="identity_empty",batch="stage06_navigation_occupied",timeoutTicks=700)
    public static void routeDetoursAroundStationaryPhysicalResident(GameTestHelper helper) {
        BlockPos start=helper.absolutePos(new BlockPos(1,1,1));
        CitizenEntity blocker=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(helper.getLevel());
        if(blocker==null) throw new IllegalStateException("Physical blocker factory unavailable");
        blocker.moveTo(start.getX()+4.5,start.getY(),start.getZ()+0.5,0,0);
        blocker.inventory().setItem(0,new ItemStack(Items.OAK_STAIRS,4));
        helper.getLevel().addFreshEntity(blocker);
        Runnable step=walkStep(helper,start,() -> blocker.remove(Entity.RemovalReason.DISCARDED));
        helper.onEachTick(() -> {
            step.run();
            helper.assertTrue(blocker.inventory().getItem(0).getCount()==4,"Routing modified idle resident property");
        });
    }
    @GameTest(template="identity_empty",batch="stage06_navigation_goal_ring",timeoutTicks=700)
    public static void originalResidentPushesThroughTransientGoalRing(GameTestHelper helper) {
        var level=helper.getLevel();
        BlockPos start=helper.absolutePos(new BlockPos(1,1,1)),target=start.offset(1,0,-4);
        for(int x=-4;x<=6;x++) for(int z=-7;z<=4;z++) {
            level.setBlockAndUpdate(start.offset(x,-1,z),Blocks.STONE.defaultBlockState());
            for(int y=0;y<3;y++) level.setBlockAndUpdate(start.offset(x,y,z),Blocks.AIR.defaultBlockState());
        }
        ServerRuntime core=ServerRuntime.start(Thread.currentThread());
        core.configureCommands(() -> {},List.of());
        core.updateLimits(core.admission().limits().withMaxManagedNanos(100_000_000L));
        UUID colony=UUID.randomUUID(); String dimension=level.dimension().location().toString();
        core.registry().addColony(new ColonyRuntime(colony,"Transient goal ring",new Territory(dimension,start.getX()-16,start.getZ()-16,start.getX()+16,start.getZ()+16),UUID.randomUUID(),Map.of(),1,1,false,null,false));
        CitizenEntity[] residents=new CitizenEntity[5]; UUID[] citizens=new UUID[5],nativeIds=new UUID[5];
        ItemStack[] carried=new ItemStack[5];
        // The reported platform failure: all four cardinal goal neighbors overlap a pushable idle citizen.
        double[] x={start.getX()+0.378242,target.getX()-0.554079,target.getX()+1.893407,target.getX()-0.099331,target.getX()+0.143429};
        double[] z={start.getZ()+0.138228,target.getZ()+0.567692,target.getZ()+0.524120,target.getZ()-0.158799,target.getZ()+1.644187};
        for(int index=0;index<residents.length;index++) {
            CitizenEntity entity=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
            if(entity==null) throw new IllegalStateException("Physical goal-ring resident factory unavailable");
            UUID citizen=UUID.randomUUID(); entity.initializeIdentity(citizen,1);
            entity.moveTo(x[index],start.getY(),z[index],0,0);
            ItemStack inventory=new ItemStack(index==0 ? Items.BREAD : Items.OAK_STAIRS,index==0 ? 3 : 4);
            entity.inventory().setItem(0,inventory);
            core.registry().addCitizen(new CitizenRecord(citizen,colony,entity.getUUID(),1,null,null,null,null,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.UNKNOWN,0,Map.of("food",1200L),position(dimension,entity.blockPosition()),1),proposed -> {
                if(!level.addFreshEntity(entity)) throw new IllegalStateException("Physical goal-ring resident spawn refused");
            });
            core.bindings().observe(citizen,entity.getUUID(),1); entity.setQuarantined(false);
            core.commands().updateCitizenReadiness(citizen,CitizenRecord.Readiness.READY);
            residents[index]=entity; citizens[index]=citizen; nativeIds[index]=entity.getUUID(); carried[index]=inventory;
        }
        int[] dx={-1,1,0,0},dz={0,0,-1,1};
        double half=residents[0].getBbWidth()*0.5;
        for(int index=0;index<4;index++) {
            BlockPos neighbor=target.offset(dx[index],0,dz[index]);
            var body=new net.minecraft.world.phys.AABB(neighbor.getX()+0.5-half,neighbor.getY(),neighbor.getZ()+0.5-half,
                    neighbor.getX()+0.5+half,neighbor.getY()+residents[0].getBbHeight(),neighbor.getZ()+0.5+half);
            helper.assertTrue(residents[index+1].isPushable() && body.intersects(residents[index+1].getBoundingBox()),"Fixture does not occupy a cardinal goal neighbor");
        }
        ChunkDemandManager chunks=new ChunkDemandManager(core.registry(),core.budgets(),new NeoForgeChunkAccess(level.getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime"))));
        MinecraftNavigationBackend backend=new MinecraftNavigationBackend(level.getServer(),core.registry(),chunks);
        NavigationService navigation=new NavigationService(core.registry(),core.budgets(),chunks,backend);
        core.scheduler().beforeWork(tick -> { chunks.tick(tick); navigation.tick(tick); });
        UUID[] originalRequest={null};
        core.scheduler().physicalExecutor(WorkOrder.MOVE,new SimulationScheduler.PhysicalExecutor() {
            public void step(WorkOrder work,long tick) {
                UUID request=navigation.request(work.id(),colony,citizens[0],1,0,work.target(),work.lane(),work.priority());
                if(originalRequest[0]==null) originalRequest[0]=request;
                else helper.assertTrue(originalRequest[0].equals(request),"Crowd replaced the original navigation request");
                if(navigation.atTarget(work.id())) { core.workBoard().transition(work.id(),WorkOrder.State.COMPLETED,WorkOrder.Reason.NONE,"completed"); navigation.cancel(work.id()); }
                else if(navigation.state(work.id())==NavigationService.State.WAITING) core.workBoard().waitAssigned(work.id(),navigation.reason(work.id()),"move");
            }
            public void cancel(UUID id) { navigation.cancel(id); }
        });
        WorkOrder move=core.workBoard().createMove(UUID.randomUUID(),colony,position(dimension,target),0,Lane.NORMAL);
        core.workBoard().transition(move.id(),WorkOrder.State.READY,WorkOrder.Reason.NONE,"move");
        helper.assertTrue(core.workBoard().assign(move.id(),citizens[0]),"Original goal-ring worker assignment refused");
        net.minecraft.world.phys.Vec3[] previous={residents[0].position()};
        boolean[] pushed={false};
        helper.onEachTick(() -> {
            core.tick(core.serverTick()+1);
            helper.assertTrue(residents[0].position().distanceToSqr(previous[0])<1.0,"Crowd route teleported the original worker");
            previous[0]=residents[0].position();
            for(int index=0;index<residents.length;index++) {
                CitizenEntity entity=residents[index]; CitizenRecord record=core.registry().citizen(citizens[index]);
                helper.assertTrue(level.getEntity(nativeIds[index])==entity && !entity.isRemoved() && entity.citizenId().equals(citizens[index])
                        && entity.bindingEpoch()==1 && record.entityId().equals(nativeIds[index]) && record.bindingEpoch()==1,"Crowd route replaced an original resident");
                helper.assertTrue(!entity.isQuarantined() && record.lifecycle()==CitizenRecord.Lifecycle.ALIVE
                        && record.admission()==CitizenRecord.Admission.ACTIVE && record.readiness()==CitizenRecord.Readiness.READY,"Crowd route bypassed resident readiness");
                helper.assertTrue(index==0 ? move.terminal() || move.id().equals(record.assignedWorkId()) : record.assignedWorkId()==null,"Crowd route replaced original work or commandeered an idle blocker");
                helper.assertTrue(entity.inventory().getItem(0)==carried[index] && carried[index].is(index==0 ? Items.BREAD : Items.OAK_STAIRS)
                        && carried[index].getCount()==(index==0 ? 3 : 4),"Crowd route modified original native inventory");
                for(int slot=1;slot<CitizenEntity.INVENTORY_SIZE;slot++) helper.assertTrue(entity.inventory().getItem(slot).isEmpty(),"Crowd route introduced inventory property");
                if(index>0 && entity.distanceToSqr(x[index],start.getY(),z[index])>0.0025) pushed[0]=true;
            }
            if(core.serverTick()==600) helper.assertTrue(move.state()==WorkOrder.State.COMPLETED,"Original goal-ring move stalled: "+navigation.reason(move.id())+" at "+residents[0].position());
            if(move.state()!=WorkOrder.State.COMPLETED) return;
            helper.assertTrue(originalRequest[0]!=null && residents[0].distanceToSqr(target.getX()+0.5,target.getY(),target.getZ()+0.5)<=0.01,"Goal-ring completion preceded exact physical arrival");
            helper.assertTrue(pushed[0],"Goal-ring arrival did not physically displace a native pushable resident");
            helper.assertTrue(((Number)backend.diagnostics().get("completedQueries")).longValue()>0,"Goal-ring arrival bypassed bounded native search");
            helper.assertTrue(((Number)backend.diagnostics().get("maxExpansionsPerPortion")).intValue()==256
                    && ((Number)backend.diagnostics().get("maxNodesPerQuery")).intValue()==8192
                    && ((Number)backend.diagnostics().get("maxConcurrentQueries")).intValue()==16,"Crowd route relaxed bounded search limits");
            helper.assertTrue(chunks.footprint()==0 && chunks.blockTicking()==0 && chunks.entityTicking()==0,"Goal-ring completion retained ticket charges");
            navigation.close(); chunks.close(); for(CitizenEntity entity:residents) entity.remove(Entity.RemovalReason.DISCARDED);
            core.beginStopping(); core.stop(); helper.succeed();
        });
    }
    @GameTest(template="identity_empty",batch="stage06_navigation_displaced",timeoutTicks=700)
    public static void displacedResidentsEscapeSharedStartingBlock(GameTestHelper helper) {
        var level=helper.getLevel();
        BlockPos start=helper.absolutePos(new BlockPos(1,1,1));
        for(int x=-10;x<=3;x++) for(int z=-3;z<=3;z++) {
            level.setBlockAndUpdate(start.offset(x,-1,z),Blocks.STONE.defaultBlockState());
            for(int y=0;y<3;y++) level.setBlockAndUpdate(start.offset(x,y,z),Blocks.AIR.defaultBlockState());
        }
        ServerRuntime core=ServerRuntime.start(Thread.currentThread());
        core.configureCommands(() -> {},List.of());
        core.updateLimits(core.admission().limits().withMaxManagedNanos(100_000_000L));
        UUID colony=UUID.randomUUID(); String dimension=level.dimension().location().toString();
        core.registry().addColony(new ColonyRuntime(colony,"Displaced residents",new Territory(dimension,start.getX()-16,start.getZ()-16,start.getX()+16,start.getZ()+16),UUID.randomUUID(),Map.of(),1,1,false,null,false));
        CitizenEntity[] residents=new CitizenEntity[2]; WorkOrder[] moves=new WorkOrder[2];
        double[] x={0.2085645186461,0.9452065205516},z={0.622983323915536,0.305689252936426};
        for(int index=0;index<2;index++) {
            CitizenEntity entity=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
            if(entity==null) throw new IllegalStateException("Physical resident factory unavailable");
            UUID citizen=UUID.randomUUID(); entity.initializeIdentity(citizen,1);
            entity.moveTo(start.getX()+x[index],start.getY(),start.getZ()+z[index],0,0);
            core.registry().addCitizen(new CitizenRecord(citizen,colony,entity.getUUID(),1,null,null,null,null,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.UNKNOWN,0,Map.of("food",1200L),position(dimension,start),1),proposed -> {
                if(!level.addFreshEntity(entity)) throw new IllegalStateException("Physical resident spawn refused");
            });
            core.bindings().observe(citizen,entity.getUUID(),1); entity.setQuarantined(false);
            core.commands().updateCitizenReadiness(citizen,CitizenRecord.Readiness.READY);
            residents[index]=entity;
            moves[index]=core.workBoard().createMove(UUID.randomUUID(),colony,position(dimension,start.offset(-7+index*2,0,0)),0,Lane.NORMAL);
            core.workBoard().transition(moves[index].id(),WorkOrder.State.READY,WorkOrder.Reason.NONE,"move");
            helper.assertTrue(core.workBoard().assign(moves[index].id(),citizen),"Physical resident assignment refused");
        }
        ChunkDemandManager chunks=new ChunkDemandManager(core.registry(),core.budgets(),new NeoForgeChunkAccess(level.getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime"))));
        NavigationService navigation=new NavigationService(core.registry(),core.budgets(),chunks,new MinecraftNavigationBackend(level.getServer(),core.registry(),chunks));
        core.scheduler().beforeWork(tick -> { chunks.tick(tick); navigation.tick(tick); });
        core.scheduler().physicalExecutor(WorkOrder.MOVE,new SimulationScheduler.PhysicalExecutor() {
            public void step(WorkOrder work,long tick) {
                navigation.request(work.id(),colony,work.assignee(),1,0,work.target(),work.lane(),work.priority());
                if(navigation.atTarget(work.id())) { core.workBoard().transition(work.id(),WorkOrder.State.COMPLETED,WorkOrder.Reason.NONE,"completed"); navigation.cancel(work.id()); }
                else if(navigation.state(work.id())==NavigationService.State.WAITING) core.workBoard().waitAssigned(work.id(),navigation.reason(work.id()),"move");
            }
            public void cancel(UUID id) { navigation.cancel(id); }
        });
        helper.onEachTick(() -> {
            core.tick(core.serverTick()+1);
            if(core.serverTick()==600) helper.assertTrue(moves[0].terminal() && moves[1].terminal(),"Shared-center routes stalled: "+residents[0].position()+" / "+residents[1].position());
            if(moves[0].state()!=WorkOrder.State.COMPLETED || moves[1].state()!=WorkOrder.State.COMPLETED) return;
            for(int index=0;index<2;index++) helper.assertTrue(residents[index].distanceToSqr(moves[index].target().x()+0.5,moves[index].target().y(),moves[index].target().z()+0.5)<=0.01,"Completion preceded physical arrival");
            navigation.close(); chunks.close(); for(CitizenEntity entity:residents) entity.remove(Entity.RemovalReason.DISCARDED);
            core.beginStopping();core.stop();helper.succeed();
        });
    }
    @GameTest(template="identity_empty",batch="stage06_navigation_waiting_displaced",timeoutTicks=700)
    public static void waitingRouteRebuildsDomainAfterNativeDisplacement(GameTestHelper helper) {
        var level=helper.getLevel();
        BlockPos anchor=helper.absolutePos(new BlockPos(1,1,1));
        BlockPos start=new BlockPos((anchor.getX()&~15)+5,anchor.getY(),(anchor.getZ()&~15)+4);
        BlockPos target=start.offset(5,0,0),displaced=start.offset(0,0,-4);
        for(int x=-3;x<=8;x++)for(int z=-7;z<=5;z++) {
            level.setBlockAndUpdate(start.offset(x,-1,z),Blocks.STONE.defaultBlockState());
            for(int y=0;y<3;y++)level.setBlockAndUpdate(start.offset(x,y,z),Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(target,Blocks.STONE.defaultBlockState());
        ServerRuntime core=ServerRuntime.start(Thread.currentThread());
        core.configureCommands(() -> {},List.of());
        UUID colony=UUID.randomUUID(),citizen=UUID.randomUUID();String dimension=level.dimension().location().toString();
        core.registry().addColony(new ColonyRuntime(colony,"Waiting displacement",new Territory(dimension,start.getX()-16,start.getZ()-16,start.getX()+32,start.getZ()+16),UUID.randomUUID(),Map.of(),1,1,false,null,false));
        CitizenEntity entity=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
        if(entity==null)throw new IllegalStateException("Physical resident factory unavailable");
        entity.initializeIdentity(citizen,1);entity.moveTo(start.getX()+0.5,start.getY(),start.getZ()+0.5,0,0);
        UUID nativeId=entity.getUUID();ItemStack bread=new ItemStack(Items.BREAD,3);entity.inventory().setItem(0,bread);
        core.registry().addCitizen(new CitizenRecord(citizen,colony,nativeId,1,null,null,null,null,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.UNKNOWN,0,Map.of("food",1200L),position(dimension,start),1),proposed -> {
            if(!level.addFreshEntity(entity))throw new IllegalStateException("Physical resident spawn refused");
        });
        core.bindings().observe(citizen,nativeId,1);entity.setQuarantined(false);core.commands().updateCitizenReadiness(citizen,CitizenRecord.Readiness.READY);
        var chunks=new ChunkDemandManager(core.registry(),core.budgets(),new NeoForgeChunkAccess(level.getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime"))));
        var navigation=new NavigationService(core.registry(),core.budgets(),chunks,new MinecraftNavigationBackend(level.getServer(),core.registry(),chunks));
        core.scheduler().beforeWork(tick -> {chunks.tick(tick);navigation.tick(tick);});
        core.scheduler().physicalExecutor(WorkOrder.MOVE,new SimulationScheduler.PhysicalExecutor() {
            public void step(WorkOrder work,long tick) {
                navigation.request(work.id(),colony,citizen,1,0,work.target(),work.lane(),work.priority());
                if(navigation.atTarget(work.id())) {core.workBoard().transition(work.id(),WorkOrder.State.COMPLETED,WorkOrder.Reason.NONE,"completed");navigation.cancel(work.id());}
                else if(navigation.state(work.id())==NavigationService.State.WAITING)core.workBoard().waitAssigned(work.id(),navigation.reason(work.id()),"move");
            }
            public void cancel(UUID id) {navigation.cancel(id);}
        });
        var move=core.workBoard().createMove(UUID.randomUUID(),colony,position(dimension,target),0,Lane.NORMAL);
        core.workBoard().transition(move.id(),WorkOrder.State.READY,WorkOrder.Reason.NONE,"move");
        helper.assertTrue(core.workBoard().assign(move.id(),citizen),"Physical worker assignment refused");
        boolean[] shifted={false};long[] shiftedAt={0};
        helper.onEachTick(() -> {
            core.tick(core.serverTick()+1);
            if(!shifted[0] && navigation.reason(move.id())==WorkOrder.Reason.UNREACHABLE) {
                entity.moveTo(displaced.getX()+0.5,displaced.getY(),displaced.getZ()+0.5,0,0);entity.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                level.setBlockAndUpdate(target,Blocks.AIR.defaultBlockState());
                shifted[0]=true;shiftedAt[0]=core.serverTick();
            }
            if(!shifted[0])return;
            helper.assertTrue(core.serverTick()-shiftedAt[0]<500,"Waiting displaced native route stayed in stale domain: "+entity.position()+" reason="+navigation.reason(move.id()));
            if(move.state()!=WorkOrder.State.COMPLETED)return;
            helper.assertTrue(entity.distanceToSqr(target.getX()+0.5,target.getY(),target.getZ()+0.5)<=0.01,"Completion preceded exact native arrival");
            helper.assertTrue(level.getEntity(nativeId)==entity && entity.bindingEpoch()==1 && entity.inventory().getItem(0)==bread && bread.getCount()==3,"Displacement changed original identity/property");
            helper.assertTrue(chunks.footprint()==0 && chunks.entityTicking()==0,"Completed displaced route retained domain");
            navigation.close();chunks.close();entity.remove(Entity.RemovalReason.DISCARDED);core.beginStopping();core.stop();helper.succeed();
        });
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
        TicketController controller=new TicketController(ResourceLocation.parse("colonyloom:runtime"));
        var access=new NeoForgeChunkAccess(level.getServer(),controller);
        UUID owner=UUID.randomUUID();
        String dimension=level.dimension().location().toString();
        var centers=List.of(new io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey(dimension,(start.getX()-2)>>4,start.getZ()>>4),
                new io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey(dimension,(start.getX()+9)>>4,start.getZ()>>4));
        for (var center:centers) if (!access.acquire(owner,center,ChunkDemandManager.Readiness.ENTITY_TICKING)) throw new IllegalStateException("Remote fixture ticket refused");
        Runnable[] step={null};
        boolean[] prepared={false};
        helper.onEachTick(() -> {
            if(step[0]!=null) { step[0].run(); return; }
            if(!prepared[0]) {
                // Unpaced GameTest ticks can outrun cold mirrored-coordinate generation. Await only
                // the two existing tickets' 6x5 FULL union; false forbids extra UNKNOWN tickets.
                for(int x=centers.getFirst().x()-2;x<=centers.getLast().x()+2;x++)
                    for(int z=centers.getFirst().z()-2;z<=centers.getFirst().z()+2;z++)
                        if(level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false)==null)return;
                prepared[0]=true;
            }
            if(!centers.stream().allMatch(center -> access.ready(center,ChunkDemandManager.Readiness.ENTITY_TICKING))) return;
            step[0]=walkStep(helper,start,() -> {
                for(var center:centers)helper.assertTrue(controller.forceChunk(level,owner,center.x(),center.z(),false,true),"Remote fixture ticket not released");
            });
        });
    }
    private static void walk(GameTestHelper helper,BlockPos start) {
        walk(helper,start,() -> {});
    }
    private static void walk(GameTestHelper helper,BlockPos start,Runnable releaseFixture) {
        helper.onEachTick(walkStep(helper,start,releaseFixture));
    }
    private static Runnable walkStep(GameTestHelper helper,BlockPos start,Runnable releaseFixture) {
        return walkStep(helper, start, releaseFixture, false);
    }
    private static Runnable walkStep(GameTestHelper helper,BlockPos start,Runnable releaseFixture,boolean raised) {
        return walkStep(helper, start, releaseFixture, raised, false);
    }
    private static Runnable walkStep(GameTestHelper helper,BlockPos start,Runnable releaseFixture,boolean raised,boolean waterBarrier) {
        return walkStep(helper,start,releaseFixture,raised,waterBarrier,1);
    }
    private static Runnable walkStep(GameTestHelper helper,BlockPos start,Runnable releaseFixture,boolean raised,boolean waterBarrier,int pollingInterval) {
        return walkStep(helper,start,releaseFixture,raised,waterBarrier,pollingInterval,false);
    }
    private static Runnable walkStep(GameTestHelper helper,BlockPos start,Runnable releaseFixture,boolean raised,boolean waterBarrier,int pollingInterval,boolean containerCorner) {
        var level=helper.getLevel();
        BlockPos target = start.offset(7, 0, 0);
        for (int x = -2; x <= 10; x++) for (int z = -2; z <= 2; z++) {
            BlockPos feet = start.offset(x, 0, z);
            level.setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
            for (int y = 0; y < 3; y++) level.setBlockAndUpdate(feet.above(y), Blocks.AIR.defaultBlockState());
        }
        if(containerCorner) for(int x=3;x<=6;x++) level.setBlockAndUpdate(start.offset(x,0,0),Blocks.BARREL.defaultBlockState());
        if (raised) for (int x = 3; x <= 4; x++) for (int z = -18; z <= 18; z++)
            level.setBlockAndUpdate(start.offset(x, 0, z), Blocks.STONE.defaultBlockState());
        if (waterBarrier) for (int x = 3; x <= 4; x++) for (int z = -18; z <= 18; z++)
            level.setBlockAndUpdate(start.offset(x, 0, z), Blocks.WATER.defaultBlockState());
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
        UUID nativeId=entity.getUUID();
        ItemStack carried=new ItemStack(Items.BREAD,3);
        entity.inventory().setItem(0,carried);
        core.registry().addCitizen(new CitizenRecord(citizen, colony, entity.getUUID(), 1, null, null, null, null, Map.of(), Map.of("food",20), CitizenRecord.Lifecycle.ALIVE, CitizenRecord.Admission.ACTIVE, CitizenRecord.Readiness.UNKNOWN, 0, Map.of("food",1200L), position(dimension,start), 1), proposed -> {
            if (!level.addFreshEntity(entity)) throw new IllegalStateException("Physical smoke spawn refused");
        });
        core.bindings().observe(citizen, entity.getUUID(), 1); entity.setQuarantined(false);
        core.commands().updateCitizenReadiness(citizen, CitizenRecord.Readiness.READY);
        TicketController controller = new TicketController(ResourceLocation.parse("colonyloom:runtime"));
        ChunkDemandManager chunks = new ChunkDemandManager(core.registry(), core.budgets(), new NeoForgeChunkAccess(level.getServer(),controller));
        NavigationService navigation = new NavigationService(core.registry(),core.budgets(),chunks,new MinecraftNavigationBackend(level.getServer(),core.registry(),chunks));
        core.scheduler().beforeWork(tick -> { chunks.tick(tick); if(tick % pollingInterval==0) navigation.tick(tick); });
        core.scheduler().physicalExecutor(io.github.kpuctajluk.colonyloom.core.work.WorkOrder.MOVE, new SimulationScheduler.PhysicalExecutor() {
            public void step(WorkOrder work,long tick) {
                navigation.request(work.id(),colony,citizen,1,0,work.target(),work.lane(),work.priority());
                if (navigation.atTarget(work.id())) { core.workBoard().transition(work.id(),WorkOrder.State.COMPLETED,WorkOrder.Reason.NONE,"completed"); navigation.cancel(work.id()); }
                else if (navigation.state(work.id()) == NavigationService.State.WAITING) core.workBoard().waitAssigned(work.id(),navigation.reason(work.id()),"move");
            }
            public void cancel(UUID workId) { navigation.cancel(workId); }
        });
        WorkOrder cancelled = core.workBoard().createMove(UUID.randomUUID(),colony,position(dimension,target),0,Lane.NORMAL);
        final int[] phase = {0}; final long[] cancelledTick = {0}; final double[] cancelledX = {0}; final WorkOrder[] arrival = {null};
        final boolean[] observedRise = {false};
        final boolean[] drained = {false};
        return () -> {
            core.tick(core.serverTick()+1);
            helper.assertTrue(level.getEntity(nativeId)==entity && !entity.isRemoved() && entity.citizenId().equals(citizen)
                    && entity.bindingEpoch()==1 && core.registry().citizen(citizen).entityId().equals(nativeId)
                    && core.registry().citizen(citizen).bindingEpoch()==1,"Walking changed original native citizen binding");
            helper.assertTrue(entity.inventory().getItem(0)==carried && carried.is(Items.BREAD) && carried.getCount()==3,"Walking changed original carried bread");
            if (entity.getY() > start.getY() + 0.75) observedRise[0] = true;
            if (waterBarrier && !drained[0]) {
                helper.assertTrue(cancelled.state()!=WorkOrder.State.COMPLETED && entity.getX()<start.getX()+3,"Water route executed without supported dry ground");
                if (core.serverTick()<160) return;
                for (int x = 3; x <= 4; x++) for (int z = -18; z <= 18; z++) {
                    BlockPos water = start.offset(x,0,z);
                    level.setBlockAndUpdate(water,Blocks.AIR.defaultBlockState());
                    navigation.invalidate(new io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey(dimension,water.getX()>>4,water.getZ()>>4));
                }
                drained[0]=true;
            }
            if (phase[0] == 0 && entity.getX() > start.getX()+1.5) {
                core.workBoard().cancel(cancelled.id()); cancelledX[0] = entity.getX(); cancelledTick[0] = core.serverTick(); phase[0] = 1;
            } else if (phase[0] == 1 && core.serverTick()-cancelledTick[0] >= 20) {
                helper.assertTrue(cancelled.state()==WorkOrder.State.CANCELLED && core.registry().citizen(citizen).assignedWorkId()==null,"Cancelled route retained assignment");
                helper.assertTrue(Math.abs(entity.getX()-cancelledX[0])<1.0,"Cancelled stale route continued walking");
                helper.assertTrue(chunks.footprint()==0 && chunks.blockTicking()==0 && chunks.entityTicking()==0,"Cancelled route retained charged ticket rings");
                arrival[0] = core.workBoard().createMove(UUID.randomUUID(),colony,position(dimension,target),0,Lane.NORMAL); phase[0]=2;
            } else if (phase[0] == 2 && arrival[0].state()==WorkOrder.State.COMPLETED) {
                helper.assertTrue(entity.position().distanceToSqr(target.getX()+0.5,target.getY(),target.getZ()+0.5)<=1.1,"Logical arrival lacks physical target");
                helper.assertTrue(chunks.footprint()==0 && chunks.blockTicking()==0 && chunks.entityTicking()==0,"Completed route retained charged ticket rings");
                if (raised) helper.assertTrue(observedRise[0] && Math.abs(entity.getY()-start.getY())<0.5,"Route bypassed required physical rise/drop");
                if(containerCorner) helper.assertTrue(entity.distanceToSqr(target.getX()+0.5,target.getY(),target.getZ()+0.5)<=0.01,"Corner route did not reach exact supported goal");
                core.commands().updateCitizenAdmission(citizen,CitizenRecord.Admission.INACTIVE);
                long before = core.registry().citizen(citizen).activeTimeTicks();
                float health = entity.getHealth(); entity.hurt(level.damageSources().generic(),2.0F);
                helper.assertTrue(entity.getHealth()<health,"Inactivity suppressed vanilla physical damage");
                helper.assertTrue(core.registry().citizen(citizen).activeTimeTicks()==before && entity.inventory().getItem(0).getCount()==3,"Inactivity changed own time or property");
                System.out.println("COLONYLOOM_MOVE_ARRIVAL cancelledStale=false physicalTarget="+entity.position()+" maxSearchNanos="+navigation.maxSearchNanos());
                navigation.close(); chunks.close(); entity.remove(Entity.RemovalReason.DISCARDED); core.beginStopping(); core.stop(); releaseFixture.run();
                phase[0]=3; helper.succeed();
            }
        };
    }
    private static WorldPosition position(String dimension,BlockPos pos) { return new WorldPosition(dimension,pos.getX(),pos.getY(),pos.getZ()); }
}
