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
            if(!server.isDedicatedServer()||!List.of("exercise","verify").contains(phase())||!Files.isRegularFile(world(server).resolve("colonyloom-test-world")))throw new IllegalStateException("Stock fixture requires marked disposable dedicated world");
            if(++run.ticks>1800)throw new IllegalStateException("Stock fixture progress timeout step="+run.step);
            if(run.runtime==null)return;
            if(run.actor==null){var profile=new GameProfile(OWNER,"StockOwner");server.getProfileCache().add(profile);run.actor=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault());run.actor.moveTo(8.5,64,14.5,0,0);server.overworld().setChunkForced(0,0,true);}
            if(run.ticks<60||!server.overworld().isPositionEntityTicking(LEFT))return;
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
            run.step=1;run.changedAt=run.ticks;return;
        }
        if(run.step==1) {
            if(stocks.index().free(run.chestSlot,tick)!=64)return;
            stocks.reservations().reserve(id(1),run.colony,id(101),run.chestSlot,run.item,32,tick);stocks.allocations().allocate(id(2),run.colony,id(102),run.chestSlot,run.item,32,tick);
            boolean rejected=false;try{stocks.reservations().reserve(id(3),run.colony,id(103),run.chestSlot,run.item,1,tick);}catch(IllegalArgumentException|IllegalStateException expected){rejected=true;}
            require(server,rejected&&stocks.index().free(run.chestSlot,tick)==0,"shared64_never_overpromised","reserved32 allocated32 physical64");
            level.setBlock(BARREL,Blocks.BARREL.defaultBlockState(),3);((Container)level.getBlockEntity(BARREL)).setItem(0,new ItemStack(Items.OAK_PLANKS,64));
            command(server,run,"colonyloom storage register "+run.colony+" 12 64 8 return");var reg=stocks.registrations(run.colony).stream().filter(r->r.address().equals(position(BARREL))).findFirst().orElseThrow();run.barrelSlot=reg.slots().get(0);run.step=2;return;
        }
        if(run.step==2) {
            if(stocks.index().free(run.barrelSlot,tick)!=64)return;
            stocks.allocations().allocate(id(4),run.colony,id(104),run.barrelSlot,run.item,64,tick);
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
            run.manifest=new CompoundTag();run.manifest.putUUID("colony",run.colony);run.manifest.putUUID("oldChest",run.chestSlot.storage().identity());run.manifest.putUUID("oldBarrel",run.barrelSlot.storage().identity());
            NbtIo.writeCompressed(run.manifest,world(server).resolve(MANIFEST));
            command(server,run,"colonyloom storage stock "+run.colony);finish(server,run,"exercise_complete");
        }
    }
    private static void verify(MinecraftServer server,Run run)throws Exception {
        run.manifest=read(world(server).resolve(MANIFEST));run.colony=run.manifest.getUUID("colony");var stocks=run.runtime.core().registry().storage();
        var old=new StorageId("minecraft:overworld",run.manifest.getUUID("oldChest"),0);
        require(server,stocks.isRetired(old),"retirement_survives_restart",old.toString());
        require(server,stocks.reservations().entries().stream().anyMatch(e->e.id().equals(id(1))&&e.count()==32)&&stocks.allocations().entries().stream().anyMatch(e->e.id().equals(id(2))&&e.count()==32)&&stocks.allocations().entries().stream().anyMatch(e->e.id().equals(id(4))&&e.count()==48),"obligations_survive_restart","reservation32 allocation32 replaced-barrel48");
        require(server,!run.runtime.storage().read(new StockRegion(old,0)).ready(),"old_copy_never_revived",old.toString());
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
    private static void finish(MinecraftServer server,Run run,String check)throws Exception{fact(server,check,true,"production live path");run.done=true;server.halt(false);}
    private static void require(MinecraftServer server,boolean passed,String check,String detail)throws Exception{fact(server,check,passed,detail);if(!passed)throw new IllegalStateException(check+": "+detail);}
    private static void fact(MinecraftServer server,String check,boolean passed,String detail)throws Exception{var json=new JsonObject();json.addProperty("phase",phase());json.addProperty("check",check);json.addProperty("passed",passed);json.addProperty("detail",detail);Files.writeString(world(server).resolve("colonyloom-storage-observations.jsonl"),json+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    private static UUID uuid(String output,String key){var match=Pattern.compile("(?:^|[\\s\\[,])"+Pattern.quote(key)+"=([0-9a-fA-F-]{36})(?=[\\s\\],]|$)").matcher(output);if(!match.find())throw new IllegalStateException("Missing "+key+" in "+output);return UUID.fromString(match.group(1));}
    private static String command(MinecraftServer server,Run run,String text)throws Exception{var capture=new Capture();int code=server.getCommands().getDispatcher().execute(text,run.actor.createCommandSourceStack().withPermission(2).withSource(capture));String result=String.join("\n",capture.messages);if(code!=1)throw new IllegalStateException("Public stock command refused "+text+": "+result);return result;}
    private static final class Capture implements CommandSource {
        final List<String> messages=new ArrayList<>();public void sendSystemMessage(Component message){messages.add(message.getString());}
        public boolean acceptsSuccess(){return true;}public boolean acceptsFailure(){return true;}public boolean shouldInformAdmins(){return false;}
    }
}
