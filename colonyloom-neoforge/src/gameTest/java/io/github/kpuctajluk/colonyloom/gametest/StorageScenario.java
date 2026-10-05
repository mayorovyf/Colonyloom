package io.github.kpuctajluk.colonyloom.gametest;

import com.mojang.authlib.GameProfile;
import com.google.gson.JsonObject;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.storage.*;
import io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime;
import io.github.kpuctajluk.colonyloom.neoforge.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Dev-only stock evidence, through the live production runtime and supported native containers. */
final class StorageScenario {
    private static final UUID OWNER=UUID.nameUUIDFromBytes("OfflinePlayer:StockOwner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final BlockPos LEFT=new BlockPos(8,64,8),RIGHT=LEFT.east(),BARREL=new BlockPos(12,64,8),COPY=new BlockPos(8,64,12);
    private static final String MANIFEST="colonyloom-storage-fixture.nbt";
    private final Map<MinecraftServer,Run> runs=new IdentityHashMap<>();
    private static final class Run {
        MinecraftServerRuntime runtime;ServerPlayer actor;UUID colony;CompoundTag manifest;
        int ticks,step,changedAt;boolean done;StockRegion chestSlot,barrelSlot;ItemDescriptor item;
        long calibrationStart, measurementStart;
        UUID calibrationColony, calibrationCitizen, calibrationEntity, calibrationGoal;
        UUID withdrawnProduction;
        UUID retirementProbe;
        StockRegion calibrationInput;
        ItemDescriptor calibrationOutput;
        long suppliedPlanks, removedStairs, calibrationCycles, measurementCycles, measurementSupplied, measurementRemoved;
        long windowStart, windowGraph, windowStorage, windowCycles;
        int retainedProductionHighWater;
        boolean productionRetirementObserved;
        final List<JsonObject> calibrationWindows=new ArrayList<>();
    }
    StorageScenario() {
        NeoForge.EVENT_BUS.addListener(this::configure);NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,this::stopped);
    }
    private static String phase(){return System.getProperty("colonyloom.test.storagePhase","");}
    private static Path world(MinecraftServer server){return server.getWorldPath(LevelResource.ROOT);}
    private void configure(ConstructionExecutorEvent event){if(!phase().isEmpty())runs.computeIfAbsent(event.server(),ignored->new Run()).runtime=event.runtime();}
    private void tick(ServerTickEvent.Post event) {
        if(phase().isEmpty())return;var server=event.getServer();var run=runs.computeIfAbsent(server,ignored->new Run());if(run.done)return;
        try {
            if(!server.isDedicatedServer()||!List.of("exercise","verify","calibrate").contains(phase())||!Files.isRegularFile(world(server).resolve("colonyloom-test-world")))throw new IllegalStateException("Stock fixture requires marked disposable dedicated world");
            if(++run.ticks>(phase().equals("calibrate")?24000:1800))throw new IllegalStateException("Stock fixture progress timeout step="+run.step);
            if(run.runtime==null)return;
            if(run.actor==null){var profile=new GameProfile(OWNER,"StockOwner");server.getProfileCache().add(profile);run.actor=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault());run.actor.moveTo(8.5,64,14.5,0,0);server.overworld().setChunkForced(0,0,true);}
            if(phase().equals("calibrate")&&run.calibrationStart==0)server.overworld().setChunkForced(2,0,true);
            if(run.ticks<60||!server.overworld().isPositionEntityTicking(LEFT))return;
            if(phase().equals("calibrate")&&run.calibrationStart==0&&!server.overworld().isPositionEntityTicking(new BlockPos(40,64,8)))return;
            if(phase().equals("calibrate")){calibrate(server,run);return;}
            if(phase().equals("verify")){verify(server,run);return;}
            exercise(server,run);
        } catch(Exception failure) {
            run.done=true;try{fact(server,"failure",false,failure.toString());}catch(Exception evidence){failure.addSuppressed(evidence);}
            org.slf4j.LoggerFactory.getLogger(StorageScenario.class).error("Stock scenario failed",failure);server.halt(false);
        }
    }
    private static void exercise(MinecraftServer server,Run run)throws Exception {
        var level=server.overworld();var stocks=run.runtime.core().registry().storage();var service=run.runtime.storage();long tick=run.runtime.core().serverTick();
        if(run.step==0) {
            for(int x=6;x<15;x++)for(int z=6;z<15;z++)level.setBlock(new BlockPos(x,63,z),Blocks.STONE.defaultBlockState(),3);
            run.colony=uuid(command(server,run,"colonyloom colony create StockFixture 0 64 0 31 64 31"),"colony");
            level.setBlock(LEFT,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH).setValue(ChestBlock.TYPE,ChestType.LEFT),3);
            level.setBlock(RIGHT,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH).setValue(ChestBlock.TYPE,ChestType.RIGHT),3);
            ((Container)level.getBlockEntity(LEFT)).setItem(0,new ItemStack(Items.OAK_PLANKS,64));
            command(server,run,"colonyloom storage register "+run.colony+" 8 64 8 warehouse");command(server,run,"colonyloom storage register "+run.colony+" 9 64 8 warehouse");
            var registrations=stocks.registrations(run.colony);require(server,registrations.size()==2&&new HashSet<>(registrations.get(0).slots()).equals(new HashSet<>(registrations.get(1).slots())),"both_halves_share_slots",registrations.toString());
            var identity=new NeoForgeStorageIdentity().existing(level.getBlockEntity(LEFT));run.chestSlot=new StockRegion(new StorageId("minecraft:overworld",identity,0),0);
            run.item=io.github.kpuctajluk.colonyloom.minecraft.storage.NativeItemDescriptor.describe(((Container)level.getBlockEntity(LEFT)).getItem(0),server.registryAccess());
            var workshopPos=new BlockPos(12,64,12);level.setBlock(workshopPos,Blocks.BARREL.defaultBlockState(),3);level.setBlock(workshopPos.east(),Blocks.CRAFTING_TABLE.defaultBlockState(),3);
            command(server,run,"colonyloom storage register "+run.colony+" 12 64 12 workshop");command(server,run,"colonyloom building register "+run.colony+" 13 64 12 12 64 12");
            require(server,stocks.workshops().size()==1,"real_workshop_registered",stocks.workshops().toString());
            var guestProfile=new GameProfile(UUID.nameUUIDFromBytes("OfflinePlayer:StockViewer".getBytes(java.nio.charset.StandardCharsets.UTF_8)),"StockViewer");server.getProfileCache().add(guestProfile);
            var guest=new ServerPlayer(server,level,guestProfile,ClientInformation.createDefault());command(server,run,"colonyloom member set "+run.colony+" StockViewer viewer");
            var capture=new Capture();var source=guest.createCommandSourceStack().withPermission(0).withSource(capture);
            require(server,server.getCommands().getDispatcher().execute("colonyloom storage stock "+run.colony,source)==1,"viewer_stock_read","read current colony");
            require(server,server.getCommands().getDispatcher().execute("colonyloom storage register "+run.colony+" 8 64 8 return",source)==0,"viewer_cannot_mutate","manager required");
            command(server,run,"colonyloom member set "+run.colony+" StockViewer none");
            require(server,server.getCommands().getDispatcher().execute("colonyloom storage stock "+run.colony,source)==0,"revoked_viewer_denied","current rights checked");
            var created=command(server,run,"colonyloom citizen create "+run.colony+" 10 64 14");UUID citizen=uuid(created,"citizen"),entityId=uuid(created,"entity");
            var npc=(io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity)level.getEntity(entityId);npc.inventory().setItem(0,new ItemStack(Items.OAK_PLANKS,7));
            command(server,run,"colonyloom storage register-citizen "+run.colony+" "+citizen+" construction");
            var npcSlot=new StockRegion(new StorageId("minecraft:overworld",citizen,npc.bindingEpoch()),0);
            require(server,service.read(npcSlot).ready()&&service.read(npcSlot).count()==7,"authoritative_citizen_stock","citizen+epoch canonical inventory7");
            command(server,run,"colonyloom citizen workplace "+citizen+" "+stocks.workshops().getFirst().id());
            require(server,run.runtime.core().registry().citizen(citizen).workplaceId().equals(stocks.workshops().getFirst().id()),"citizen_workplace_assigned","registered actual workshop");
            command(server,run,"colonyloom citizen assign "+citizen+" colonyloom:carpenter");
            run.step=1;run.changedAt=run.ticks;return;
        }
        if(run.step==1) {
            if(stocks.index().free(run.chestSlot,tick)!=64)return;
            stocks.reservations().reserve(id(1),run.colony,id(101),run.chestSlot,run.item,32,tick,io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.NORMAL);stocks.allocations().allocate(id(2),run.colony,id(102),run.chestSlot,run.item,32,tick,io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.NORMAL);
            boolean rejected=false;try{stocks.reservations().reserve(id(3),run.colony,id(103),run.chestSlot,run.item,1,tick,io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.NORMAL);}catch(IllegalArgumentException|IllegalStateException expected){rejected=true;}
            require(server,rejected&&stocks.index().free(run.chestSlot,tick)==0,"shared64_never_overpromised","reserved32 allocated32 physical64");
            level.setBlock(BARREL,Blocks.BARREL.defaultBlockState(),3);((Container)level.getBlockEntity(BARREL)).setItem(0,new ItemStack(Items.OAK_PLANKS,64));
            command(server,run,"colonyloom storage register "+run.colony+" 12 64 8 return");var reg=stocks.registrations(run.colony).stream().filter(r->r.address().equals(position(BARREL))).findFirst().orElseThrow();run.barrelSlot=reg.slots().get(0);run.step=2;return;
        }
        if(run.step==2) {
            if(stocks.index().free(run.barrelSlot,tick)!=64)return;
            stocks.allocations().allocate(id(4),run.colony,id(104),run.barrelSlot,run.item,64,tick,io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.NORMAL);
            level.setBlock(BARREL.below(),Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING,Direction.DOWN),3);
            run.changedAt=run.ticks;run.step=3;return;
        }
        if(run.step==3) {
            Container barrel=(Container)level.getBlockEntity(BARREL);int actual=barrel.getItem(0).getCount();
            if(actual>48)return;
            level.setBlock(BARREL.below(),level.getBlockState(BARREL.below()).setValue(HopperBlock.ENABLED,false),3);
            if(stocks.allocations().entries().stream().filter(e->e.id().equals(id(4))).findFirst().orElseThrow().count()!=actual)return;
            require(server,actual==48&&run.ticks-run.changedAt<=200,"hopper_loss_reconciled","allocation="+actual+" elapsedTicks="+(run.ticks-run.changedAt));
            // Replacing a block entity, not just changing the stack, severs the old identity.
            level.setBlock(BARREL,Blocks.AIR.defaultBlockState(),3);level.setBlock(BARREL,Blocks.BARREL.defaultBlockState(),3);((Container)level.getBlockEntity(BARREL)).setItem(0,new ItemStack(Items.OAK_PLANKS,64));
            run.changedAt=run.ticks;run.step=4;return;
        }
        if(run.step==4) {
            if(run.ticks-run.changedAt<40)return;
            var replacement=stocks.registrations(run.colony).stream().filter(r->r.address().equals(position(BARREL))).findFirst().orElseThrow();
            require(server,!replacement.storages().contains(run.barrelSlot.storage())&&stocks.index().free(run.barrelSlot,tick)==0&&stocks.allocations().entries().stream().anyMatch(e->e.id().equals(id(4))&&e.count()==48),"replacement_severs_promises","old48 retained unknown newUUID="+replacement.storages());
            level.setBlock(COPY,Blocks.CHEST.defaultBlockState(),3);level.getBlockEntity(COPY).setData(NeoForgeStorageIdentity.STORAGE_ID,run.chestSlot.storage().identity());
            try{command(server,run,"colonyloom storage register "+run.colony+" 8 64 12 warehouse");}catch(Exception expected){/* Conflict may refuse registration before publishing another local view. */}
            run.changedAt=run.ticks;run.step=5;return;
        }
        if(run.step==5) {
            if(run.ticks-run.changedAt<40)return;
            require(server,!service.read(run.chestSlot).ready()&&stocks.index().free(run.chestSlot,tick)==0,"copied_uuid_blocks_original","old="+run.chestSlot.storage());
            command(server,run,"colonyloom storage reidentify "+run.colony+" 8 64 12");
            require(server,stocks.isRetired(run.chestSlot.storage())&&!service.read(run.chestSlot).ready(),"explicit_repair_keeps_old_unknown","reserved32 allocated32 remain old identity");
            run.changedAt=run.ticks;run.step=6;return;
        }
        if(run.step==6) {
            var source=stocks.registrations(run.colony).stream().filter(value -> value.role().equals("workshop")).findFirst().orElseThrow();
            ((Container)level.getBlockEntity(BARREL)).clearContent();
            for(var citizen:run.runtime.core().registry().citizens(run.colony)) if(level.getEntity(citizen.entityId()) instanceof io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity npc)npc.inventory().clearContent();
            ((Container)level.getBlockEntity(new BlockPos(source.address().x(),source.address().y(),source.address().z()))).setItem(0,new ItemStack(Items.OAK_LOG,6));
            run.runtime.core().registry().supply().request(id(201),run.colony,id(202),new io.github.kpuctajluk.colonyloom.core.supply.ItemMatcher("minecraft:oak_stairs",null),16,io.github.kpuctajluk.colonyloom.core.supply.Demand.GoalKind.CONSUMPTION,position(new BlockPos(10,64,14)),io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.NORMAL,10,tick);
            run.changedAt=run.ticks;run.step=7;return;
        }
        if(run.step==7) {
            var supply=run.runtime.core().registry().supply();
            if(supply.productionOrders().size()!=2||supply.productionOrders().stream().anyMatch(value -> !value.terminal()))return;
            require(server,supply.demand(id(201)).snapshot().covered()==16&&supply.demand(id(201)).snapshot().fulfilled()==0,"supply_real_workshop_output","stairs16 covered once in physical workshop; absent courier never reports delivery");
            require(server,supply.productionOrders().size()==2&&supply.shares().stream().filter(value -> value.demandId().equals(id(201))).mapToLong(io.github.kpuctajluk.colonyloom.core.supply.CoverageShare::quantity).sum()==16,"supply_no_duplicate_coverage","complete native production retains single16 coverage");
            run.manifest=new CompoundTag();run.manifest.putUUID("colony",run.colony);run.manifest.putUUID("oldChest",run.chestSlot.storage().identity());run.manifest.putUUID("oldBarrel",run.barrelSlot.storage().identity());
            NbtIo.writeCompressed(run.manifest,world(server).resolve(MANIFEST));
            command(server,run,"colonyloom storage stock "+run.colony);finish(server,run,"exercise_complete");
        }
    }
    private static void calibrate(MinecraftServer server,Run run)throws Exception {
        long now=System.nanoTime();
        if(run.calibrationStart==0) {
            run.manifest=read(world(server).resolve(MANIFEST));run.colony=run.manifest.getUUID("colony");
            initializeCalibration(server,run);run.calibrationStart=now;return;
        }
        cycleCalibration(server,run);
        if(run.measurementStart==0) {
            if(now-run.calibrationStart<300_000_000_000L)return;
            var warmup=run.runtime.metrics().snapshot();
            require(server,warmup.get("GRAPH_UNIT").count()>0&&warmup.get("STORAGE_EXTERNAL").count()>0&&run.calibrationCycles>0,
                    "calibration_warmup_activity","Actual recipe graph, native barrel observations and completed batches during300second warmup");
            run.runtime.metrics().reset();run.measurementStart=run.windowStart=now;
            run.measurementCycles=run.windowCycles=run.calibrationCycles;
            run.measurementSupplied=run.suppliedPlanks;run.measurementRemoved=run.removedStairs;return;
        }
        if(now-run.windowStart>=60_000_000_000L) {
            var timers=run.runtime.metrics().snapshot();long graph=timers.get("GRAPH_UNIT").count(),storage=timers.get("STORAGE_EXTERNAL").count();
            var window=new JsonObject();window.addProperty("elapsedNanos",now-run.windowStart);
            window.addProperty("graphCalls",graph-run.windowGraph);window.addProperty("nativeStorageCalls",storage-run.windowStorage);
            window.addProperty("nativeBatches",run.calibrationCycles-run.windowCycles);run.calibrationWindows.add(window);
            require(server,graph>run.windowGraph&&storage>run.windowStorage&&run.calibrationCycles>run.windowCycles,
                    "calibration_active_window",window.toString());
            run.windowStart=now;run.windowGraph=graph;run.windowStorage=storage;run.windowCycles=run.calibrationCycles;
        }
        if(now-run.measurementStart<600_000_000_000L||run.calibrationWindows.size()<10)return;
        require(server,run.calibrationCycles>run.measurementCycles&&run.suppliedPlanks>run.measurementSupplied&&run.removedStairs>run.measurementRemoved,
                "calibration_native_samples","Ten active windows; recurring real6-plank kits and4-stair batches; existing graph/stock budgets and proofs");
        finish(server,run,"calibrate_complete");
    }
    private static void initializeCalibration(MinecraftServer server,Run run)throws Exception {
        var level=server.overworld();var core=run.runtime.core();
        for(int x=32;x<48;x++)for(int z=0;z<16;z++) {
            var floor=new BlockPos(x,63,z);level.setBlock(floor,Blocks.STONE.defaultBlockState(),3);
            for(int y=1;y<=3;y++)level.setBlock(floor.above(y),Blocks.AIR.defaultBlockState(),3);
        }
        level.setChunkForced(2,0,true);
        run.calibrationColony=uuid(command(server,run,"colonyloom colony create CalibrationRecipe 32 64 0 47 64 15"),"colony");
        var barrel=new BlockPos(40,64,8);level.setBlock(barrel,Blocks.BARREL.defaultBlockState(),3);level.setBlock(barrel.east(),Blocks.CRAFTING_TABLE.defaultBlockState(),3);
        command(server,run,"colonyloom storage register "+run.calibrationColony+" 40 64 8 workshop");
        command(server,run,"colonyloom building register "+run.calibrationColony+" 41 64 8 40 64 8");
        var workshop=core.registry().storage().workshops().stream().filter(value -> value.colonyId().equals(run.calibrationColony)).findFirst().orElseThrow();
        var created=command(server,run,"colonyloom citizen create "+run.calibrationColony+" 40 64 9");
        run.calibrationCitizen=uuid(created,"citizen");run.calibrationEntity=uuid(created,"entity");
        command(server,run,"colonyloom storage register-citizen "+run.calibrationColony+" "+run.calibrationCitizen+" workshop");
        command(server,run,"colonyloom citizen workplace "+run.calibrationCitizen+" "+workshop.id());
        command(server,run,"colonyloom citizen assign "+run.calibrationCitizen+" colonyloom:carpenter");
        var registration=core.registry().storage().registrations(run.calibrationColony).stream().filter(value -> value.role().equals("workshop")).findFirst().orElseThrow();
        run.calibrationInput=registration.slots().getFirst();
        run.calibrationOutput=io.github.kpuctajluk.colonyloom.minecraft.storage.NativeItemDescriptor.describe(new ItemStack(Items.OAK_STAIRS),server.registryAccess());
        run.calibrationGoal=UUID.randomUUID();
        core.registry().supply().request(run.calibrationGoal,run.calibrationColony,UUID.randomUUID(),new io.github.kpuctajluk.colonyloom.core.supply.ItemMatcher("minecraft:oak_stairs",run.calibrationOutput),4,
                io.github.kpuctajluk.colonyloom.core.supply.Demand.GoalKind.CONSUMPTION,position(barrel),io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.NORMAL,10,core.serverTick());
        for(var citizen:core.registry().citizens(run.colony))if(level.getEntity(citizen.entityId()) instanceof io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity npc) {
            npc.inventory().setItem(npc.inventory().getContainerSize()-1,new ItemStack(Items.BREAD,64));npc.inventory().setChanged();
            fact(server,"calibration_external_food",true,"citizen="+citizen.citizenId()+" supplied=64bread");
        }
    }
    private static void cycleCalibration(MinecraftServer server,Run run)throws Exception {
        var core=run.runtime.core();var storage=run.runtime.storage();var supply=core.registry().supply();
        int retainedProductions = supply.productionCount();
        run.retainedProductionHighWater = Math.max(run.retainedProductionHighWater, retainedProductions);
        if (run.retirementProbe != null && supply.findProduction(run.retirementProbe) == null) run.productionRetirementObserved = true;
        if(!(server.overworld().getEntity(run.calibrationEntity) instanceof io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity citizen))return;
        requireCalibrationIdentity(run,citizen);
        var inventory=citizen.inventory();
        if(inventory.getItem(inventory.getContainerSize()-1).isEmpty()) {
            inventory.setItem(inventory.getContainerSize()-1,new ItemStack(Items.BREAD,64));inventory.setChanged();
            fact(server,"calibration_external_food",true,"citizen="+run.calibrationCitizen+" supplied=64bread");
        }
        Container barrel=storage.currentContainer(run.calibrationInput);if(barrel==null)return;
        if(barrel.getItem(run.calibrationInput.slot()).isEmpty()) {
            barrel.setItem(run.calibrationInput.slot(),new ItemStack(Items.OAK_PLANKS,6));barrel.setChanged();run.suppliedPlanks+=6;
            fact(server,"calibration_external_input",true,"slot="+run.calibrationInput+" supplied=6planks total="+run.suppliedPlanks);
        }
        var goal=supply.demand(run.calibrationGoal).snapshot();
        if(goal.allocated()!=4||goal.covered()!=0||goal.fulfilled()!=0)return;
        var output=supply.demandShares(run.calibrationGoal);
        if(output.isEmpty()||output.stream().anyMatch(share -> share.stage()!=io.github.kpuctajluk.colonyloom.core.supply.CoverageShare.Stage.ALLOCATED
                ||share.productionOrderId()==null||!supply.production(share.productionOrderId()).terminal()))return;
        if(output.stream().anyMatch(share -> share.productionOrderId().equals(run.withdrawnProduction)))return;
        var producer=supply.production(output.getFirst().productionOrderId());
        if(producer.completedBatches()!=1)
            throw new IllegalStateException("Calibration output lacks its single completed native batch");
        boolean witnessed=core.registry().effects().snapshots().stream().anyMatch(effect -> effect.craft()!=null&&producer.id().equals(effect.craft().productionId())
                &&effect.state()==io.github.kpuctajluk.colonyloom.core.action.EffectRecord.State.OBSERVED&&effect.craft().complete()
                &&run.calibrationCitizen.equals(effect.citizenId())&&effect.bindingEpoch()==citizen.bindingEpoch()
                &&effect.craft().inputs().stream().mapToInt(io.github.kpuctajluk.colonyloom.core.action.EffectRecord.CraftSlot::amount).sum()==6
                &&effect.craft().outputCount()==4&&effect.craft().output().equals(run.calibrationOutput));
        if(!witnessed)throw new IllegalStateException("Calibration external removal preceded exact real6-plank/4-stair craft evidence");
        for(var share:output) {
            Container nativeOutput=storage.currentContainer(share.slot());if(nativeOutput==null)return;
            var stack=nativeOutput.getItem(share.slot().slot());
            if(!stack.is(Items.OAK_STAIRS)||stack.getCount()<share.quantity()||!run.calibrationOutput.equals(io.github.kpuctajluk.colonyloom.minecraft.storage.NativeItemDescriptor.describe(stack,server.registryAccess())))
                throw new IllegalStateException("Calibrated native batch lacks exact promised physical output "+share);
        }
        for(var share:output) {
            var nativeOutput=storage.currentContainer(share.slot());var stack=nativeOutput.getItem(share.slot().slot());stack.shrink(Math.toIntExact(share.quantity()));
            nativeOutput.setItem(share.slot().slot(),stack);nativeOutput.setChanged();run.removedStairs+=share.quantity();
        }
        run.withdrawnProduction=output.getFirst().productionOrderId();
        if (run.retirementProbe == null) run.retirementProbe = run.withdrawnProduction;
        run.calibrationCycles++;
        fact(server,"calibration_external_output",true,"goal="+run.calibrationGoal+" removed=4stairs batch="+run.calibrationCycles+" total="+run.removedStairs+" noNotify=true");
    }
    private static void requireCalibrationIdentity(Run run,io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity citizen) {
        var record=run.runtime.core().registry().citizen(run.calibrationCitizen);
        if(!citizen.isAlive()||citizen.isRemoved()||!run.calibrationCitizen.equals(citizen.citizenId())||citizen.bindingEpoch()!=record.bindingEpoch()||!record.entityId().equals(run.calibrationEntity))
            throw new IllegalStateException("Calibration replaced/lost original native producer");
    }
    private static void verify(MinecraftServer server,Run run)throws Exception {
        run.manifest=read(world(server).resolve(MANIFEST));run.colony=run.manifest.getUUID("colony");var stocks=run.runtime.core().registry().storage();
        var old=new StorageId("minecraft:overworld",run.manifest.getUUID("oldChest"),0);
        require(server,stocks.isRetired(old),"retirement_survives_restart",old.toString());
        require(server,stocks.reservations().entries().stream().anyMatch(e->e.id().equals(id(1))&&e.count()==32)&&stocks.allocations().entries().stream().anyMatch(e->e.id().equals(id(2))&&e.count()==32)&&stocks.allocations().entries().stream().anyMatch(e->e.id().equals(id(4))&&e.count()==48),"obligations_survive_restart","reservation32 allocation32 replaced-barrel48");
        require(server,!run.runtime.storage().read(new StockRegion(old,0)).ready(),"old_copy_never_revived",old.toString());
        require(server,run.runtime.core().registry().citizens(run.colony).getFirst().workplaceId().equals(stocks.workshops().getFirst().id()),"workplace_survives_restart","citizen references persisted workshop");
        var supply=run.runtime.core().registry().supply();
        require(server,supply.demand(id(201)).snapshot().covered()==16&&supply.demand(id(201)).snapshot().fulfilled()==0&&supply.productionOrders().size()==2&&supply.productionOrders().stream().allMatch(value -> value.terminal()),"supply_pins_and_coverage_survive_restart","completed shared recipe snapshots preserve physical output coverage without delivery");
        command(server,run,"colonyloom storage stock "+run.colony);finish(server,run,"verify_complete");
    }
    private void stopped(ServerStoppedEvent event) {
        if(phase().isEmpty()||!runs.containsKey(event.getServer()))return;var server=event.getServer();runs.remove(server);
        try{var dto=read(world(server).resolve("data/colonyloom.dat")).getCompound("data");var marker=read(world(server).resolve("data/colonyloom-session.nbt"));require(server,marker.getBoolean("clean")&&marker.getUUID("checkpointId").equals(dto.getUUID("checkpointId")),"clean_checkpoint",dto.getUUID("checkpointId").toString());}
        catch(Exception failure){try{fact(server,"clean_checkpoint",false,failure.toString());}catch(Exception ignored){org.slf4j.LoggerFactory.getLogger(StorageScenario.class).error("Stock shutdown evidence failed",failure);}}
    }
    private static UUID id(long value){return new UUID(0x570c,value);}
    private static WorldPosition position(BlockPos pos){return new WorldPosition("minecraft:overworld",pos.getX(),pos.getY(),pos.getZ());}
    private static CompoundTag read(Path path)throws Exception{return NbtIo.readCompressed(path,NbtAccounter.create(64L*1024*1024));}
    private static void finish(MinecraftServer server,Run run,String check)throws Exception {
        var timers=run.runtime.metrics().snapshot();var graph=timers.get("GRAPH_UNIT");var storage=timers.get("STORAGE_EXTERNAL");
        var metrics=new JsonObject();metrics.addProperty("acceptanceDuration",false);metrics.addProperty("profileCalibrated",false);
        if(phase().equals("calibrate")) {
            metrics.addProperty("warmupSeconds",300);metrics.addProperty("measurementElapsedNanos",System.nanoTime()-run.measurementStart);
            metrics.addProperty("measurementScope","Actual recurring6-plank native kits and4-stair batches, noNotify external withdrawal and real stock/loss/recipe graph reopening; no synthetic unit calls");
            metrics.addProperty("graphActiveWindows",run.calibrationWindows.size());
            metrics.add("activityWindows",new com.google.gson.Gson().toJsonTree(run.calibrationWindows));
            metrics.addProperty("nativeBatches",run.calibrationCycles-run.measurementCycles);
            metrics.addProperty("externalSuppliedPlanks",run.suppliedPlanks-run.measurementSupplied);
            metrics.addProperty("externalRemovedStairs",run.removedStairs-run.measurementRemoved);
            require(server,run.productionRetirementObserved,"calibration_checkpoint_retirement",
                    "An original observed native batch was safely retired while its recurring root remained active; retained high-water=" + run.retainedProductionHighWater);
            metrics.addProperty("productionRetirementObserved",run.productionRetirementObserved);
            metrics.addProperty("retainedProductionHighWater",run.retainedProductionHighWater);
        }
        metrics.addProperty("graphCalls",graph.count());metrics.addProperty("graphP99Nanos",graph.p99Nanos());metrics.addProperty("graphMaxNanos",graph.maxNanos());
        metrics.addProperty("nativeStorageCalls",storage.count());metrics.addProperty("nativeStorageP99Nanos",storage.p99Nanos());metrics.addProperty("nativeStorageMaxNanos",storage.maxNanos());
        var saveScopes = new java.util.LinkedHashMap<String, io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Sample>();
        for (String timer : List.of("MSPT", "MANAGED_TICK", "SAVE", "SAVE_ENCODE", "SAVE_WORLD", "SAVE_FLUSH", "COMPACTION")) saveScopes.put(timer, timers.get(timer));
        metrics.add("runtimeScopes", new com.google.gson.Gson().toJsonTree(saveScopes));
        Files.writeString(world(server).resolve("colonyloom-supply-metrics-"+phase()+".json"),metrics.toString());
        fact(server,check,true,"production live path");run.done=true;server.halt(false);
    }
    private static void require(MinecraftServer server,boolean passed,String check,String detail)throws Exception{fact(server,check,passed,detail);if(!passed)throw new IllegalStateException(check+": "+detail);}
    private static void fact(MinecraftServer server,String check,boolean passed,String detail)throws Exception{var json=new JsonObject();json.addProperty("phase",phase());json.addProperty("check",check);json.addProperty("passed",passed);json.addProperty("detail",detail);Files.writeString(world(server).resolve("colonyloom-storage-observations.jsonl"),json+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    private static UUID uuid(String output,String key){var match=Pattern.compile("(?:^|[\\s\\[,])"+Pattern.quote(key)+"=([0-9a-fA-F-]{36})(?=[\\s\\],]|$)").matcher(output);if(!match.find())throw new IllegalStateException("Missing "+key+" in "+output);return UUID.fromString(match.group(1));}
    private static String command(MinecraftServer server,Run run,String text)throws Exception{var capture=new Capture();int code=server.getCommands().getDispatcher().execute(text,run.actor.createCommandSourceStack().withPermission(2).withSource(capture));String result=String.join("\n",capture.messages);if(code!=1)throw new IllegalStateException("Public stock command refused "+text+": "+result);return result;}
    private static final class Capture implements CommandSource {
        final List<String> messages=new ArrayList<>();public void sendSystemMessage(Component message){messages.add(message.getString());}
        public boolean acceptsSuccess(){return true;}public boolean acceptsFailure(){return true;}public boolean shouldInformAdmins(){return false;}
    }
}
