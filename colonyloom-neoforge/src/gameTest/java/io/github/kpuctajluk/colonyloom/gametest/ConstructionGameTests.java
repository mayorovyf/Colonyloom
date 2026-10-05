package io.github.kpuctajluk.colonyloom.gametest;

import com.mojang.authlib.GameProfile;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.colony.*;
import io.github.kpuctajluk.colonyloom.core.command.ColonyCommands;
import io.github.kpuctajluk.colonyloom.core.navigation.NavigationService;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
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
    @GameTest(template="identity_empty",batch="stage05_construction_rotation90",timeoutTicks=600)
    public static void clockwiseWholeBlueprintPlacesStatesMarkersClaimsAndExactMaterials(GameTestHelper helper) {
        run(helper,false,false,false,false,false,false,false,ScanPause.NONE,90);
    }
    @GameTest(template="identity_empty",batch="stage05_construction_rotation270",timeoutTicks=600)
    public static void counterclockwiseWholeBlueprintPlacesStatesMarkersClaimsAndExactMaterials(GameTestHelper helper) {
        run(helper,false,false,false,false,false,false,false,ScanPause.NONE,270);
    }
    @GameTest(template="identity_empty",batch="stage05_construction_geometry",timeoutTicks=200)
    public static void maximumPinnedBlueprintPreparesExactLazyBoundsWithoutLoadingChunks(GameTestHelper helper) {
        var level=helper.getLevel();
        var descriptor=new io.github.kpuctajluk.colonyloom.core.content.BlockDescriptor("minecraft:oak_stairs",
                Map.of("facing","north","half","bottom","shape","straight","waterlogged","false"),"minecraft:oak_stairs");
        var blocks=new java.util.ArrayList<io.github.kpuctajluk.colonyloom.core.content.BlueprintDefinition.BlockSpec>(65_536);
        for(int y=0;y<16;y++)for(int z=-32;z<32;z++)for(int x=-32;x<32;x++)
            blocks.add(new io.github.kpuctajluk.colonyloom.core.content.BlueprintDefinition.BlockSpec(
                    new io.github.kpuctajluk.colonyloom.core.content.BlockOffset(x,y,z),descriptor));
        var definition=io.github.kpuctajluk.colonyloom.core.content.BlueprintDefinition.create("colonyloom:test_max_geometry",1,blocks,
                Map.of("work_origin",new io.github.kpuctajluk.colonyloom.core.content.BlockOffset(-33,0,0),
                        "delivery_buffer",new io.github.kpuctajluk.colonyloom.core.content.BlockOffset(32,0,0)));
        var origin=new WorldPosition(level.dimension().location().toString(),1_000_000,level.getMinBuildHeight()+32,1_000_000);
        helper.assertTrue(level.getChunkSource().getChunkNow(origin.x()>>4,origin.z()>>4)==null,"Maximum geometry fixture chunk is already loaded");
        var geometry=new MinecraftConstructionGeometry(level.getServer());
        for(int rotation:new int[]{90,270}) {
            long start=System.nanoTime();
            var layout=geometry.layout(UUID.randomUUID(),UUID.randomUUID(),definition,origin,rotation); geometry.validate(layout);
            long preparationNanos=System.nanoTime()-start;
            var claim=layout.claim();
            helper.assertTrue(layout.targets().size()==65_536 && claim.minY()==origin.y() && claim.maxY()==origin.y()+15
                    && claim.minX()==origin.x()+(rotation==90?-31:-32) && claim.maxX()==origin.x()+(rotation==90?32:31)
                    && claim.minZ()==origin.z()+(rotation==90?-32:-31) && claim.maxZ()==origin.z()+(rotation==90?31:32),"Maximum lazy layout bounds differ");
            var first=layout.targets().getFirst(); var last=layout.targets().getLast();
            helper.assertTrue(first.position().equals(new WorldPosition(origin.dimension(),origin.x()+(rotation==90?32:-32),origin.y(),origin.z()+(rotation==90?-32:32)))
                    && last.position().equals(new WorldPosition(origin.dimension(),origin.x()+(rotation==90?-31:31),origin.y()+15,origin.z()+(rotation==90?31:-31))),"Maximum indexed target coordinates differ");
            helper.assertTrue(MinecraftConstructionGeometry.state(first.expected()).equals(MinecraftConstructionGeometry.state(descriptor)
                    .rotate(rotation==90?net.minecraft.world.level.block.Rotation.CLOCKWISE_90:net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90)),"Maximum indexed state rotation differs");
            helper.assertTrue(level.getChunkSource().getChunkNow(origin.x()>>4,origin.z()>>4)==null,"Geometry admission or indexed access loaded a native chunk");
            System.out.println("COLONYLOOM_BLUEPRINT_PREPARATION blocks=65536 palette=1 rotation="+rotation+" preparationNanos="+preparationNanos);
        }
        helper.succeed();
    }
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
    @GameTest(template="identity_empty",batch="stage05_construction",timeoutTicks=600)
    public static void lowSharedQuotaReachesMaterialsInLastCitizenSlot(GameTestHelper helper) {
        run(helper,false,false,false,false,true,false);
    }
    @GameTest(template="identity_empty",batch="stage05_construction",timeoutTicks=600)
    public static void lowSharedQuotaResumesBufferedPickupIntoLastCitizenSlot(GameTestHelper helper) {
        run(helper,false,false,false,false,true,true);
    }
    // Separate batches prevent concurrent clearing of these oversized three-root arenas.
    // Raised floors also isolate them from residents retained by an earlier failed ground-level fixture.
    @GameTest(template="identity_empty",batch="stage05_construction_analysis_pause",timeoutTicks=1000)
    public static void scarceQuantaKeepSiteAnalysisAcrossWorkerReadinessPauses(GameTestHelper helper) {
        run(helper,false,false,false,false,true,true,true);
    }
    @GameTest(template="identity_empty",batch="stage05_construction_carried_pause",timeoutTicks=1000)
    public static void scarceQuantaResumeCarriedScanAcrossSameCitizenPauses(GameTestHelper helper) {
        run(helper,false,false,false,false,true,false,true,ScanPause.CARRIED);
    }
    @GameTest(template="identity_empty",batch="stage05_construction_pickup_pause",timeoutTicks=1000)
    public static void scarceQuantaResumeLastSlotPickupAcrossSameCitizenPauses(GameTestHelper helper) {
        run(helper,false,false,false,false,true,true,true,ScanPause.PICKUP);
    }
    @GameTest(template="identity_empty",batch="stage05_construction_source_pause",timeoutTicks=1000)
    public static void pausedPickupRejectsReplacedSourceAllocation(GameTestHelper helper) {
        run(helper,false,false,false,false,true,true,true,ScanPause.SOURCE_CHANGED);
    }
    @GameTest(template="identity_empty",batch="stage05_construction_property_pause",timeoutTicks=1000)
    public static void pausedPickupRejectsChangedNativeLastSlotProperty(GameTestHelper helper) {
        run(helper,false,false,false,false,true,true,true,ScanPause.PROPERTY_CHANGED);
    }
    private enum ScanPause { NONE,CARRIED,PICKUP,SOURCE_CHANGED,PROPERTY_CHANGED }
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
        run(helper,cancel,staleCommit,approachAcrossTarget,retire,false,false);
    }
    private static void run(GameTestHelper helper,boolean cancel,boolean staleCommit,boolean approachAcrossTarget,boolean retire,boolean lowQuota,boolean buffered) {
        run(helper,cancel,staleCommit,approachAcrossTarget,retire,lowQuota,buffered,false);
    }
    private static void run(GameTestHelper helper,boolean cancel,boolean staleCommit,boolean approachAcrossTarget,boolean retire,boolean lowQuota,boolean buffered,boolean suspended) {
        run(helper,cancel,staleCommit,approachAcrossTarget,retire,lowQuota,buffered,suspended,ScanPause.NONE);
    }
    private static void run(GameTestHelper helper,boolean cancel,boolean staleCommit,boolean approachAcrossTarget,boolean retire,boolean lowQuota,boolean buffered,boolean suspended,ScanPause scanPause) {
        run(helper,cancel,staleCommit,approachAcrossTarget,retire,lowQuota,buffered,suspended,scanPause,0);
    }
    private static void run(GameTestHelper helper,boolean cancel,boolean staleCommit,boolean approachAcrossTarget,boolean retire,boolean lowQuota,boolean buffered,boolean suspended,ScanPause scanPause,int rotation) {
        var level=helper.getLevel();
        // Keep the same X/Z footprint and charged chunk domain; only unrelated native collisions change.
        var origin=helper.absolutePos(new BlockPos(1,1,1)).above(suspended?16:0);
        int blockCount=suspended?12:4,storageQuota=suspended?60:25;
        for(int x=-6;x<=(suspended?14:6);x++) for(int z=-5;z<=(suspended?12:4);z++) {
            var pos=origin.offset(x,0,z); level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
            for(int y=0;y<3;y++) level.setBlockAndUpdate(pos.above(y),Blocks.AIR.defaultBlockState());
        }
        var core=ServerRuntime.start(Thread.currentThread()); var content=ContentLoader.load(level.getServer().getResourceManager(),level.getServer().registryAccess());
        core.configureCommands(() -> {},content.professions().values());
        core.updateLimits(core.admission().limits().withMaxManagedNanos(100_000_000L));
        if(lowQuota)core.updateLimits(core.admission().limits().withBudget(Budget.STORAGE_SLOT_CHECKS,storageQuota)
                .withBudget(Budget.BLUEPRINT_COMPARISONS,4).withBudget(Budget.PHYSICAL_ACTIONS,1)
                .withBudget(Budget.DIRTY_RESCAN_OBJECTS,suspended?1:core.admission().limits().budget(Budget.DIRTY_RESCAN_OBJECTS)));
        if(scanPause!=ScanPause.NONE)core.updateLimits(core.admission().limits().withMaxManagedNanos(5_000_000L)
                .withBudget(Budget.ASSIGNMENT_CANDIDATES,3).withBudget(Budget.NAVIGATION_STARTS,1)
                .withBudget(Budget.CHUNK_REQUESTS,1));
        UUID colony=UUID.randomUUID(),citizen=UUID.randomUUID(),owner=UUID.randomUUID(); String dim=level.dimension().location().toString();
        core.registry().addColony(new ColonyRuntime(colony,"Construction smoke",new Territory(dim,origin.getX()-16,origin.getZ()-16,origin.getX()+32,origin.getZ()+16),owner,Map.of(),1,1,false,null,false));
        var entity=(CitizenEntity)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
        if(entity==null) throw new IllegalStateException("Citizen unavailable");
        var start=origin.offset(-4,0,approachAcrossTarget ? -4 : 1); entity.initializeIdentity(citizen,1); entity.moveTo(start.getX()+0.5,start.getY(),start.getZ()+0.5,0,0);
        int materialSlot=lowQuota?8:0;
        if(buffered)for(int slot=0;slot<materialSlot;slot++)entity.inventory().setItem(slot,new ItemStack(Items.COBBLESTONE,64));
        else entity.inventory().setItem(materialSlot,new ItemStack(Items.OAK_STAIRS,blockCount));
        core.registry().addCitizen(new CitizenRecord(citizen,colony,entity.getUUID(),1,null,null,null,"colonyloom:builder",Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.ACTIVE,CitizenRecord.Readiness.UNKNOWN,0,Map.of(),position(dim,start),1),proposed -> { if(!level.addFreshEntity(entity)) throw new IllegalStateException("Spawn refused"); });
        core.bindings().observe(citizen,entity.getUUID(),1); entity.setQuarantined(false); core.commands().updateCitizenReadiness(citizen,CitizenRecord.Readiness.READY);
        var controller=new ConstructionController(core.registry(),new MinecraftConstructionGeometry(level.getServer())); controller.definitions(content.blueprints()); core.commands().construction(controller);
        String blueprintId="colonyloom:test_four_stairs";
        if(suspended) {
            var original=content.blueprints().get(blueprintId);
            var blocks=new java.util.ArrayList<io.github.kpuctajluk.colonyloom.core.content.BlueprintDefinition.BlockSpec>();
            for(int copy=0;copy<3;copy++)for(var spec:original.blocks())blocks.add(new io.github.kpuctajluk.colonyloom.core.content.BlueprintDefinition.BlockSpec(
                    new io.github.kpuctajluk.colonyloom.core.content.BlockOffset(spec.offset().x()+copy*4,spec.offset().y(),spec.offset().z()),spec.block()));
            var definition=io.github.kpuctajluk.colonyloom.core.content.BlueprintDefinition.create("colonyloom:test_twelve_stairs",1,blocks,original.markers());
            controller.definitions(Map.of(definition.id(),definition));blueprintId=definition.id();
        }
        var chunks=new ChunkDemandManager(core.registry(),core.budgets(),new NeoForgeChunkAccess(level.getServer(),new TicketController(ResourceLocation.parse("colonyloom:runtime"))));
        var placement=new BlockPlacementExecutor(level.getServer(),core.registry(),new NeoForgeItemInteraction(id -> id.equals(owner)?new GameProfile(owner,"ConstructionFixture"):null),(point,context) -> {
            if(staleCommit && point==BlockPlacementExecutor.FaultPoint.BEFORE_EFFECT_COMMIT) core.workBoard().cancel(core.registry().citizen(citizen).assignedWorkId());
        });
        var storage=new io.github.kpuctajluk.colonyloom.minecraft.storage.StorageService(level.getServer(),core.registry(),core.budgets(),new io.github.kpuctajluk.colonyloom.neoforge.NeoForgeStorageIdentity());
        var transfer=new io.github.kpuctajluk.colonyloom.minecraft.storage.StorageTransferExecutor(level.getServer(),core.registry(),storage,(context,principal,source,destination,amount) -> true,() -> new UUID(1,2),null);
        var service=new MinecraftConstructionService(level.getServer(),core.registry(),controller,chunks,placement,() -> new UUID(1,2),storage,transfer);
        var navigation=new NavigationService(core.registry(),core.budgets(),chunks,new MinecraftNavigationBackend(level.getServer(),core.registry(),chunks,service),service); service.navigation(navigation);
        core.scheduler().physicalExecutor(WorkOrder.CONSTRUCTION,service);
        var context=new ColonyCommands.CommandContext(owner,false,new ColonyCommands.PhysicalChecks() {
            public void validateTerritory(Territory t) {}
            public void validateCitizenPosition(ColonyRuntime c,WorldPosition p) {}
            public void validateRecovery(ColonyRuntime c,java.util.List<CitizenRecord> cs,java.util.List<io.github.kpuctajluk.colonyloom.core.citizen.BindingRegistry.Observation> observations) {}
        });
        if(rotation!=0) {
            var territory=core.registry().colony(colony).territory();
            // Markers remain inside, but the last real target crosses the rotated territory edge.
            var outside=rotation==90?new BlockPos(origin.getX(),origin.getY(),territory.maxZ()-1)
                    :new BlockPos(origin.getX(),origin.getY(),territory.minZ()+1);
            assertPreWorkRefusal(helper,core,context,colony,blueprintId,position(dim,outside),rotation,entity,"territory");
            assertPreWorkRefusal(helper,core,context,colony,blueprintId,
                    new WorldPosition(dim,origin.getX(),level.getMaxBuildHeight(),origin.getZ()),rotation,entity,"world");
            assertPreWorkRefusal(helper,core,context,colony,blueprintId,
                    new WorldPosition(dim,(int)Math.ceil(level.getWorldBorder().getMaxX())+1,origin.getY(),origin.getZ()),rotation,entity,"world");
            var markerOutside=rotation==90?new BlockPos(territory.minX(),origin.getY(),origin.getZ())
                    :new BlockPos(territory.maxX(),origin.getY(),origin.getZ());
            assertPreWorkRefusal(helper,core,context,colony,blueprintId,position(dim,markerOutside),rotation,entity,"territory");
        }
        if(approachAcrossTarget) {
            boolean refused=false;
            try {core.commands().build(context,UUID.randomUUID(),colony,"colonyloom:test_four_stairs",position(dim,origin),0);}
            catch(IllegalArgumentException expected) {refused=true;}
            helper.assertTrue(refused && core.workBoard().works().isEmpty() && core.registry().targetClaims().snapshots().isEmpty()
                    && core.registry().construction().snapshots().isEmpty(),"Missing transformed construction buffer accepted partial site/claim/work");
        }
        var buffer=rotation==90?origin.north():rotation==270?origin.south():origin.west();
        var marker=rotation==90?origin.west():rotation==270?origin.east():origin.south();
        level.setBlockAndUpdate(buffer,Blocks.BARREL.defaultBlockState());
        var bufferRegistration=storage.register(colony,position(dim,buffer),"construction");
        if(buffered)((net.minecraft.world.Container)level.getBlockEntity(buffer)).setItem(0,new ItemStack(Items.OAK_STAIRS,blockCount));
        var work=core.commands().build(context,UUID.randomUUID(),colony,blueprintId,position(dim,origin),rotation);
        var builtDefinition=core.registry().construction().definition(core.registry().construction().site(work.id()).blueprintDigest());
        if(rotation!=0) {
            var layout=controller.layout(core.registry().construction().site(work.id()));
            var claim=layout.claim();
            helper.assertTrue(layout.workOrigin().equals(position(dim,marker)) && layout.deliveryBuffer().equals(position(dim,buffer))
                    && work.target().equals(position(dim,marker)),"Quarter-turn work/delivery markers differ");
            helper.assertTrue(claim.minX()==origin.getX() && claim.maxX()==origin.getX()
                    && claim.minY()==origin.getY() && claim.maxY()==origin.getY()
                    && claim.minZ()==origin.getZ()-(rotation==270?3:0)
                    && claim.maxZ()==origin.getZ()+(rotation==90?3:0)
                    && core.registry().targetClaims().snapshots().contains(claim),"Quarter-turn authoritative claim differs from physical footprint");
            helper.assertTrue(layout.targets().size()==4,"Rotated full blueprint lost targets");
            try {layout.targets().clear();throw new IllegalStateException("Mutable pinned targets");}
            catch(UnsupportedOperationException expected) {}
        }
        if(suspended) {
            core.commands().prioritizeWork(context,work.id(),1);
            for(int root=1;root<=2;root++) {
                var competingOrigin=origin.offset(0,0,root*4);
                var competingBuffer=competingOrigin.west();
                level.setBlockAndUpdate(competingBuffer,Blocks.BARREL.defaultBlockState());
                storage.register(colony,position(dim,competingBuffer),"construction");
                core.commands().build(context,UUID.randomUUID(),colony,blueprintId,position(dim,competingOrigin),0);
            }
        }
        boolean[] bufferAllocated={false};
        int[] budgetWaits={0};
        int[] readinessPauses={0};
        int[] carriedPauses={0},pickupPauses={0};
        boolean[] changedScanGuard={false};
        UUID[] releasedSource={null},releasedObligation={null},admittedSource={null};
        int[] finalMaterialSlot={materialSlot};
        core.scheduler().beforeWork(tick -> {
            if(suspended) {
                for(int offset=0;offset<3;offset++)switch((int)((tick/2+offset)%3)) {
                    case 0 -> chunks.tick(tick);
                    case 1 -> navigation.tick(tick);
                    case 2 -> core.registry().targetClaims().tick();
                }
            } else {chunks.tick(tick);navigation.tick(tick);core.registry().targetClaims().tick();}
            if(!lowQuota)return;
            // Genuine barrel inspections leave one builder read in the fixed shared quota.
            for(int check=0;check<storageQuota-1;check++) {
                var slot=bufferRegistration.slots().get(check%bufferRegistration.slots().size());
                if(!core.budgets().tryConsume(Budget.STORAGE_SLOT_CHECKS,Lane.NORMAL))break;
                var observed=storage.readFresh(slot);
                if(observed.ready())core.registry().storage().index().observe(slot,observed.item(),observed.count(),tick);
                else core.registry().storage().index().unknown(slot);
                if(buffered&&!bufferAllocated[0]&&slot.slot()==0&&observed.ready()) {
                    var demand=core.registry().supply().demands().stream().filter(value -> value.snapshot().ownerId().equals(work.id())).findFirst().orElse(null);
                    if(demand!=null&&demand.deficit()>0) {
                        core.registry().supply().allocateStock(demand.id(),slot,observed.item(),Math.min(observed.count(),demand.deficit()),tick);
                        bufferAllocated[0]=true;
                    }
                }
            }
            if(suspended) {
                var demand=core.registry().supply().demands().stream().filter(value -> value.snapshot().ownerId().equals(work.id())).findFirst().orElse(null);
                if(demand!=null)core.registry().supply().status(demand.id(),tick%2==0
                        ?io.github.kpuctajluk.colonyloom.core.supply.Demand.Status.ACTIVE:io.github.kpuctajluk.colonyloom.core.supply.Demand.Status.WAITING);
            }
        });
        int[] phase={0}; long[] stoppedAt={0}; double[] stoppedX={0};
        helper.onEachTick(() -> {
            if(phase[0]==2) return;
            int priorCursor=core.registry().construction().site(work.id()).cursor();
            core.tick(core.serverTick()+1); var site=core.registry().construction().site(work.id());
            if(scanPause==ScanPause.SOURCE_CHANGED&&changedScanGuard[0]) {
                var supply=core.registry().supply();
                var demand=supply.demands().stream().filter(value -> value.snapshot().ownerId().equals(work.id())).findFirst().orElseThrow();
                helper.assertTrue(supply.demandShares(demand.id()).stream().noneMatch(value -> value.id().equals(releasedSource[0]))
                        &&core.registry().storage().allocations().get(releasedObligation[0])==null,
                        "Released source identity or obligation became authoritative again");
                if(core.registry().effects().snapshots().isEmpty()) {
                    helper.assertTrue(site.cursor()==0&&site.consumed()==0&&demand.snapshot().fulfilled()==0,
                            "Rejected source pickup spent material or advanced construction");
                    var nativeSource=((net.minecraft.world.Container)level.getBlockEntity(buffer)).getItem(0);
                    helper.assertTrue(nativeSource.is(Items.OAK_STAIRS)&&nativeSource.getCount()==blockCount
                            &&entity.inventory().getItem(materialSlot).isEmpty(),"Rejected source pickup changed native stairs");
                    for(int slot=0;slot<materialSlot;slot++)helper.assertTrue(entity.inventory().getItem(slot).is(Items.COBBLESTONE)
                            &&entity.inventory().getItem(slot).getCount()==64,"Rejected source pickup changed unrelated property");
                    helper.assertTrue(supply.demandShares(demand.id()).stream().anyMatch(value -> value.id().equals(admittedSource[0])
                            &&value.stage()==io.github.kpuctajluk.colonyloom.core.supply.CoverageShare.Stage.ALLOCATED
                            &&value.quantity()==blockCount),"Fresh source admission lost its exact allocation before pickup");
                } else helper.assertTrue(pickupPauses[0]>=18,"Replacement source transferred before completing a new paid pickup scan");
            }
            if(lowQuota) {
                helper.assertTrue(core.budgets().used(Budget.STORAGE_SLOT_CHECKS)<=storageQuota,"Construction exceeded the shared native-read quota");
                helper.assertTrue(core.budgets().used(Budget.BLUEPRINT_COMPARISONS)<=4,"Construction exceeded the fixed blueprint quota");
                helper.assertTrue(core.budgets().used(Budget.PHYSICAL_ACTIONS)<=1,"Construction exceeded the fixed physical quota");
                if(scanPause!=ScanPause.NONE) {
                    helper.assertTrue(core.budgets().used(Budget.ASSIGNMENT_CANDIDATES)<=3
                            &&core.budgets().used(Budget.NAVIGATION_STARTS)<=1&&core.budgets().used(Budget.CHUNK_REQUESTS)<=1
                            &&core.budgets().used(Budget.DIRTY_RESCAN_OBJECTS)<=1,"Paused scan exceeded another frozen shared service quota");
                }
                if(work.waitingReason()==WorkOrder.Reason.BUDGET)budgetWaits[0]++;
                if(priorCursor>0)helper.assertTrue(core.budgets().used(Budget.BLUEPRINT_COMPARISONS)<=1,
                        "Confirmed advance restarted the already analyzed portion instead of checking only its current target");
            }
            if(suspended&&site.cursor()==0&&work.assignee()!=null&&work.waitingReason()==WorkOrder.Reason.BUDGET
                    &&core.budgets().used(Budget.BLUEPRINT_COMPARISONS)==4) {
                // A temporary loss of observed entity readiness invokes the real executor suspension path.
                core.commands().updateCitizenReadiness(citizen,CitizenRecord.Readiness.UNKNOWN);
                core.commands().updateCitizenReadiness(citizen,CitizenRecord.Readiness.READY);
                readinessPauses[0]++;
            }
            if(scanPause!=ScanPause.NONE&&site.cursor()==0&&work.assignee()!=null
                    &&work.waitingReason()==WorkOrder.Reason.BUDGET&&core.budgets().used(Budget.BLUEPRINT_COMPARISONS)==1
                    &&core.budgets().used(Budget.STORAGE_SLOT_CHECKS)==storageQuota) {
                helper.assertTrue(citizen.equals(work.assignee()),"Paused scan changed the actual builder identity");
                boolean atPickup=entity.position().distanceToSqr(new net.minecraft.world.phys.Vec3(buffer.getX()+0.5,buffer.getY(),buffer.getZ()+1.5))<=0.01;
                if(scanPause==ScanPause.CARRIED)carriedPauses[0]++;
                else if(atPickup)pickupPauses[0]++;
                core.commands().updateCitizenReadiness(citizen,CitizenRecord.Readiness.UNKNOWN);
                core.commands().updateCitizenReadiness(citizen,CitizenRecord.Readiness.READY);
                if(!changedScanGuard[0]&&pickupPauses[0]==9) {
                    if(scanPause==ScanPause.SOURCE_CHANGED) {
                        var supply=core.registry().supply();
                        var demand=supply.demands().stream().filter(value -> value.snapshot().ownerId().equals(work.id())).findFirst().orElseThrow();
                        var old=supply.demandShares(demand.id()).stream().filter(value -> value.stage()==io.github.kpuctajluk.colonyloom.core.supply.CoverageShare.Stage.ALLOCATED).findFirst().orElseThrow();
                        helper.assertTrue(core.registry().effects().snapshots().isEmpty()&&site.consumed()==0
                                &&entity.inventory().getItem(materialSlot).isEmpty(),"Source guard changed after physical pickup");
                        releasedSource[0]=old.id();releasedObligation[0]=old.obligationId();
                        supply.release(old.id());
                        var replacement=supply.allocateStock(demand.id(),old.slot(),old.item(),old.quantity(),core.serverTick());
                        helper.assertTrue(!old.id().equals(replacement.id()),"Source allocation guard fixture retained the old identity");
                        admittedSource[0]=replacement.id();
                        changedScanGuard[0]=true;
                    } else if(scanPause==ScanPause.PROPERTY_CHANGED) {
                        // Move existing unrelated property, never create extra material: the retained empty-slot
                        // capacity must be rejected and a new paid scan must discover slot seven instead.
                        helper.assertTrue(entity.inventory().getItem(materialSlot).isEmpty(),"Pending pickup already wrote last slot");
                        entity.inventory().setItem(materialSlot,entity.inventory().removeItemNoUpdate(materialSlot-1));
                        finalMaterialSlot[0]=materialSlot-1;changedScanGuard[0]=true;
                    }
                }
            }
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
            int placed=cancel?2:blockCount;
            for(int x=0;x<blockCount;x++) {
                var target=rotation==90?origin.offset(0,0,x):rotation==270?origin.offset(0,0,-x):origin.offset(x,0,0);
                helper.assertTrue(x<placed?level.getBlockState(target).is(Blocks.OAK_STAIRS):level.getBlockState(target).isAir(),"Physical block count differs at "+x);
                if(rotation!=0) {
                    var expected=ContentLoader.decodeBlockState(builtDefinition.blocks().get(x).block())
                            .rotate(rotation==90?net.minecraft.world.level.block.Rotation.CLOCKWISE_90:net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90);
                    helper.assertTrue(level.getBlockState(target).equals(expected),"Quarter-turn actual block state/coordinate differs at "+x);
                }
            }
            helper.assertTrue(entity.inventory().getItem(finalMaterialSlot[0]).getCount()==blockCount-placed,"NPC material delta differs");
            helper.assertTrue(site.consumed()==placed && site.closed(),"Saved site not closed with exact expense");
            helper.assertTrue(entity.getX()>start.getX()+1.0,"Construction did not physically walk");
            helper.assertTrue(!cancel || Math.abs(entity.getX()-stoppedX[0])<1.0,"Cancelled construction kept walking");
            helper.assertTrue(core.registry().citizen(citizen).assignedWorkId()==null,"Terminal construction retained citizen assignment");
            helper.assertTrue(core.registry().effects().snapshots().size()==placed+(buffered?1:0),"Physical effects count differs");
            if(rotation!=0) {
                helper.assertTrue(core.registry().targetClaims().snapshots().isEmpty(),"Completed quarter-turn retained claim");
                helper.assertTrue(level.getBlockState(buffer).is(Blocks.BARREL) && level.getBlockState(marker).isAir(),"Construction overwrote transformed markers");
                var demand=core.registry().supply().demands().stream().filter(value -> value.snapshot().ownerId().equals(work.id())).findFirst().orElseThrow();
                helper.assertTrue(demand.snapshot().required()==4 && demand.snapshot().fulfilled()==4,"Rotated physical materials differ from demand expense");
                for(var effect:core.registry().effects().snapshots())helper.assertTrue(effect.target().x()==origin.getX()
                        && effect.target().y()==origin.getY() && effect.countBefore()-effect.countAfter()==1
                        && effect.target().z()>=origin.getZ()-(rotation==270?3:0)
                        && effect.target().z()<=origin.getZ()+(rotation==90?3:0),"Rotated placement witness escaped exact claim or material expense");
                for(int x=1;x<4;x++)helper.assertTrue(level.getBlockState(origin.east(x)).isAir(),"Quarter-turn placement leaked into unrotated coordinates");
            }
            if(buffered) {
                var facts=core.registry().effects().snapshots().stream().filter(value -> value.transfer()!=null).toList();
                helper.assertTrue(facts.size()==1&&facts.getFirst().transfer().inserted()==blockCount
                        &&facts.getFirst().transfer().sourceBefore()-facts.getFirst().transfer().sourceAfter()==blockCount
                        &&facts.getFirst().transfer().destination().slot()==finalMaterialSlot[0]
                        &&facts.getFirst().citizenId().equals(citizen),"Buffered native transfer did not preserve exact stairs, slot and original builder");
            }
            if(lowQuota) {
                helper.assertTrue(budgetWaits[0]>0,"Low-quota fixture did not exercise retained inspection across budget waits");
                var demand=core.registry().supply().demands().stream().filter(value -> value.snapshot().ownerId().equals(work.id())).findFirst().orElseThrow();
                helper.assertTrue(demand.snapshot().required()==blockCount&&demand.snapshot().fulfilled()==blockCount,"Retained portion changed exact demand consumption");
                if(buffered) {
                    helper.assertTrue(bufferAllocated[0]&&((net.minecraft.world.Container)level.getBlockEntity(buffer)).getItem(0).isEmpty(),"Buffered construction fabricated or failed to transfer native materials");
                    for(int slot=0;slot<=materialSlot;slot++)if(slot!=finalMaterialSlot[0])helper.assertTrue(entity.inventory().getItem(slot).is(Items.COBBLESTONE)&&entity.inventory().getItem(slot).getCount()==64,"Pickup changed unrelated builder inventory");
                }
            }
            if(suspended)helper.assertTrue(readinessPauses[0]>=2&&core.workBoard().works().stream().filter(value -> WorkOrder.CONSTRUCTION.equals(value.typeId())).count()==3,
                    "Suspension fixture did not retain paid multi-turn analysis while construction roots competed");
            if(scanPause==ScanPause.CARRIED)helper.assertTrue(carriedPauses[0]>=8,"Carried regression did not suspend the bounded last-slot scan");
            if(scanPause!=ScanPause.NONE&&scanPause!=ScanPause.CARRIED)helper.assertTrue(pickupPauses[0]>=9,"Pickup regression did not suspend capacity and fresh-read phases at the last slot");
            if(scanPause==ScanPause.SOURCE_CHANGED||scanPause==ScanPause.PROPERTY_CHANGED)helper.assertTrue(changedScanGuard[0],"Paused scan guard never changed while native transfer was pending");
            if(scanPause==ScanPause.SOURCE_CHANGED) {
                helper.assertTrue(pickupPauses[0]>=18,"Changed source allocation reused its predecessor's retained pickup cursor/capacity");
                var shares=core.registry().supply().shares();
                helper.assertTrue(shares.stream().noneMatch(value -> value.id().equals(releasedSource[0]))
                        &&shares.stream().anyMatch(value -> value.id().equals(admittedSource[0])
                                &&value.stage()==io.github.kpuctajluk.colonyloom.core.supply.CoverageShare.Stage.FULFILLED),
                        "Completed construction did not consume the legitimately admitted replacement identity");
            }
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
    private static void assertPreWorkRefusal(GameTestHelper helper,ServerRuntime core,ColonyCommands.CommandContext context,
            UUID colony,String blueprintId,WorldPosition origin,int rotation,CitizenEntity entity,String reason) {
        var before=entity.inventory().getItem(0).copy();
        boolean refused=false;
        try {core.commands().build(context,UUID.randomUUID(),colony,blueprintId,origin,rotation);}
        catch(IllegalArgumentException expected) {refused=expected.getMessage().contains(reason);}
        helper.assertTrue(refused && core.workBoard().works().isEmpty() && core.registry().construction().snapshots().isEmpty()
                && core.registry().construction().definitions().isEmpty() && core.registry().targetClaims().snapshots().isEmpty()
                && core.registry().supply().demands().isEmpty() && core.registry().effects().snapshots().isEmpty()
                && ItemStack.matches(before,entity.inventory().getItem(0)),"Rotated "+reason+" refusal published work, claim, pin, demand or physical expense");
    }
    private static WorldPosition position(String dim,BlockPos p) { return new WorldPosition(dim,p.getX(),p.getY(),p.getZ()); }
}
