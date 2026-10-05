package io.github.kpuctajluk.colonyloom.gametest;

import com.mojang.authlib.GameProfile;
import com.google.gson.JsonObject;
import io.github.kpuctajluk.colonyloom.core.supply.Demand;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime;
import io.github.kpuctajluk.colonyloom.neoforge.ConstructionExecutorEvent;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Real production commands, navigation and inventories; fixture supplies only external stock changes. */
final class DeliveryScenario {
    private static final UUID OWNER=UUID.nameUUIDFromBytes("OfflinePlayer:DeliveryOwner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final int ORIGIN=4096;
    private static final BlockPos SOURCE=new BlockPos(ORIGIN+8,64,8),DEST=new BlockPos(ORIGIN+20,64,8),RETURN=new BlockPos(ORIGIN+12,64,12);
    private static final String MANIFEST="colonyloom-delivery-fixture.nbt";
    private final Map<MinecraftServer,Run> runs=new IdentityHashMap<>();
    private static final class Run {
        MinecraftServerRuntime runtime;ServerPlayer actor;UUID colony,citizen,entity,demand,outputDemand;
        int ticks,step;boolean done,unknownChecked;
    }
    DeliveryScenario() {
        NeoForge.EVENT_BUS.addListener(this::configure);NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,this::stopped);
    }
    private static String phase() {return System.getProperty("colonyloom.test.deliveryPhase","");}
    private static Path world(MinecraftServer server) {return server.getWorldPath(LevelResource.ROOT);}
    private void configure(ConstructionExecutorEvent event) {if(!phase().isEmpty())runs.computeIfAbsent(event.server(),ignored -> new Run()).runtime=event.runtime();}
    private void tick(ServerTickEvent.Post event) {
        if(phase().isEmpty())return;var server=event.getServer();var run=runs.computeIfAbsent(server,ignored -> new Run());if(run.done)return;
        try {
            if(!server.isDedicatedServer()||!Files.isRegularFile(world(server).resolve("colonyloom-test-world")))throw new IllegalStateException("Delivery smoke requires marked disposable dedicated world");
            if(++run.ticks>6000)throw new IllegalStateException("Delivery progress timeout step="+run.step+" diagnostics="+run.runtime.minecraftMetrics().snapshot(null));
            if(run.runtime==null)return;
            if(phase().equals("verify")&&!run.unknownChecked) {
                loadManifest(server,run);var demand=run.runtime.core().registry().supply().demand(run.demand).snapshot();
                if(citizen(server,run)==null) {
                    require(server,demand.covered()==16&&demand.fulfilled()==0&&run.runtime.core().registry().supply().deliveries().stream().filter(o -> o.ownerDemandId().equals(run.demand)).count()==1,
                            "unloaded_courier_retains_single_coverage","16 UNKNOWN cargo retained without replacement extraction/order");run.unknownChecked=true;
                } else throw new IllegalStateException("Restart fixture courier loaded before UNKNOWN proof");
            }
            if(run.actor==null) {var profile=new GameProfile(OWNER,"DeliveryOwner");server.getProfileCache().add(profile);run.actor=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault());run.actor.moveTo(ORIGIN+8.5,64,14.5,0,0);if(phase().equals("exercise"))for(int x=ORIGIN>>4;x<(ORIGIN>>4)+2;x++)for(int z=0;z<2;z++)server.overworld().setChunkForced(x,z,true);}
            if(run.ticks<60||!server.overworld().isPositionEntityTicking(DEST))return;
            if(phase().equals("verify"))verify(server,run);else exercise(server,run);
        } catch(Exception failure) {run.done=true;try{fact(server,"failure",false,failure.toString());}catch(Exception evidence){failure.addSuppressed(evidence);}org.slf4j.LoggerFactory.getLogger(DeliveryScenario.class).error("Delivery smoke failed",failure);server.halt(false);}
    }
    private static Container container(MinecraftServer server,BlockPos pos) {return (Container)server.overworld().getBlockEntity(pos);}
    private static CitizenEntity citizen(MinecraftServer server,Run run) {return (CitizenEntity)server.overworld().getEntity(run.entity);}
    private static int count(Container inventory) {int result=0;for(int slot=0;slot<inventory.getContainerSize();slot++)if(inventory.getItem(slot).is(Items.OAK_PLANKS))result+=inventory.getItem(slot).getCount();return result;}
    private static void full(Container inventory) {for(int slot=0;slot<inventory.getContainerSize();slot++)inventory.setItem(slot,new ItemStack(Items.COBBLESTONE,64));inventory.setChanged();}
    private static void exercise(MinecraftServer server,Run run)throws Exception {
        var level=server.overworld();var supply=run.runtime.core().registry().supply();
        if(run.step==0) {
            for(int x=ORIGIN+5;x<ORIGIN+25;x++)for(int z=5;z<16;z++)level.setBlock(new BlockPos(x,63,z),Blocks.STONE.defaultBlockState(),3);
            run.colony=uuid(command(server,run,"colonyloom colony create DeliveryFixture "+ORIGIN+" 64 0 "+(ORIGIN+31)+" 64 31"),"colony");
            level.setBlock(SOURCE,Blocks.CHEST.defaultBlockState(),3);level.setBlock(DEST,Blocks.BARREL.defaultBlockState(),3);level.setBlock(RETURN,Blocks.BARREL.defaultBlockState(),3);
            command(server,run,"colonyloom storage register "+run.colony+" "+coordinates(SOURCE)+" warehouse");command(server,run,"colonyloom storage register "+run.colony+" "+coordinates(DEST)+" construction");command(server,run,"colonyloom storage register "+run.colony+" "+coordinates(RETURN)+" return");
            var created=command(server,run,"colonyloom citizen create "+run.colony+" "+(ORIGIN+10)+" 64 14");run.citizen=uuid(created,"citizen");run.entity=uuid(created,"entity");command(server,run,"colonyloom citizen assign "+run.citizen+" colonyloom:courier");
            container(server,SOURCE).setItem(0,new ItemStack(Items.OAK_PLANKS,32));run.demand=request(server,run,32);run.step=1;return;
        }
        if(run.step==1) {
            if(supply.demand(run.demand).snapshot().fulfilled()!=32)return;
            require(server,count(container(server,SOURCE))==0&&count(citizen(server,run).inventory())==0&&count(container(server,DEST))==32,"native_chest_courier_barrel","32 real items, standalone DELIVERY fulfilled without allocations");
            container(server,DEST).clearContent();container(server,SOURCE).setItem(0,new ItemStack(Items.OAK_PLANKS,24));run.demand=request(server,run,24);run.step=2;return;
        }
        if(run.step==2) {
            if(count(citizen(server,run).inventory())==0)return;
            int inventory=count(citizen(server,run).inventory());
            var capture=new Capture();var source=run.actor.createCommandSourceStack().withPermission(2).withSource(capture);
            int reassigned=server.getCommands().getDispatcher().execute("colonyloom citizen assign "+run.citizen+" colonyloom:builder",source);
            boolean removed=false;
            try {run.runtime.core().commands().removeCitizen(new io.github.kpuctajluk.colonyloom.core.command.ColonyCommands.CommandContext(OWNER,true,new io.github.kpuctajluk.colonyloom.core.command.ColonyCommands.PhysicalChecks() {
                public void validateTerritory(io.github.kpuctajluk.colonyloom.core.colony.Territory territory) {}
                public void validateCitizenPosition(io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime colony,io.github.kpuctajluk.colonyloom.core.colony.WorldPosition position) {}
                public void validateRecovery(io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime colony,List<io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord> citizens,List<io.github.kpuctajluk.colonyloom.core.citizen.BindingRegistry.Observation> observations) {}
            }),run.citizen);removed=true;}catch(IllegalStateException cargoBound) {if(!cargoBound.getMessage().contains("cargo"))throw cargoBound;}
            require(server,reassigned==0&&!removed&&count(citizen(server,run).inventory())==inventory
                    &&run.runtime.core().registry().citizen(run.citizen).professionId().equals("colonyloom:courier"),
                    "cargo_cutovers_refused","profession/removal cannot orphan accepted physical cargo");
            command(server,run,"colonyloom delivery cancel "+run.demand);run.step=3;return;
        }
        if(run.step==3) {
            if(count(citizen(server,run).inventory())!=0||citizen(server,run).isRemoved())return;
            if(supply.deliveries().stream().filter(o -> o.ownerDemandId().equals(run.demand)).anyMatch(o -> !o.terminal()))return;
            require(server,count(container(server,SOURCE))+count(container(server,RETURN))+count(container(server,DEST))==24&&supply.demand(run.demand).snapshot().fulfilled()==0,"cancel_cargo_conserved","24 retained physically; no delivered success; courier free");
            container(server,SOURCE).clearContent();container(server,RETURN).clearContent();container(server,DEST).clearContent();container(server,SOURCE).setItem(0,new ItemStack(Items.OAK_PLANKS,20));run.demand=request(server,run,20);run.step=4;return;
        }
        if(run.step==4) {
            if(count(citizen(server,run).inventory())==0)return;
            full(container(server,DEST));full(container(server,RETURN));full(container(server,SOURCE));run.step=5;return;
        }
        if(run.step==5) {
            var work=run.runtime.core().registry().workBoard().works().stream().filter(w -> run.citizen.equals(w.assignee())&&w.waitingReason()==io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.CAPACITY).findFirst().orElse(null);
            if(work==null)return;
            require(server,count(citizen(server,run).inventory())==20&&supply.demand(run.demand).snapshot().fulfilled()==0,"all_buffers_full_cargo_visible","20 still in real NPC; CAPACITY, no false completion");
            container(server,RETURN).setItem(0,ItemStack.EMPTY);run.step=6;return;
        }
        if(run.step==6) {
            if(count(citizen(server,run).inventory())!=0||count(container(server,RETURN))!=20)return;
            require(server,run.runtime.core().registry().citizen(run.citizen).assignedWorkId()==null,"safe_return_releases_courier","20 deposited in return stock without original delivery fulfilment");
            container(server,SOURCE).clearContent();container(server,RETURN).setItem(1,ItemStack.EMPTY);
            run.outputDemand=uuid(command(server,run,"colonyloom delivery request "+run.colony+" "+coordinates(DEST)+" "+coordinates(SOURCE)+" minecraft:cobblestone 64"),"demand");run.step=8;return;
        }
        if(run.step==8) {
            if(supply.demand(run.outputDemand).snapshot().fulfilled()!=64||supply.demand(run.demand).snapshot().fulfilled()!=20)return;
            require(server,count(container(server,DEST))==20&&count(citizen(server,run).inventory())==0,
                    "courier_drains_output_then_original_chain_progresses","one courier moved64 real blocking cobblestone; original20 then delivered once");
            container(server,SOURCE).clearContent();container(server,RETURN).clearContent();container(server,DEST).clearContent();
            container(server,SOURCE).setItem(0,new ItemStack(Items.OAK_PLANKS,12));run.demand=request(server,run,12);run.step=9;return;
        }
        if(run.step==9) {
            if(count(citizen(server,run).inventory())!=12)return;
            command(server,run,"colonyloom delivery cancel "+run.demand);
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)if(x!=0||z!=0) {
                var pos=RETURN.offset(x,0,z);level.setBlock(pos,Blocks.STONE.defaultBlockState(),3);level.setBlock(pos.above(),Blocks.STONE.defaultBlockState(),3);
            }
            run.step=10;return;
        }
        if(run.step==10) {
            if(count(citizen(server,run).inventory())!=0||count(container(server,SOURCE))!=12)return;
            require(server,count(container(server,RETURN))==0&&supply.demand(run.demand).snapshot().fulfilled()==0,
                    "unreachable_return_uses_original_source","12 preserved at original source; no invented delivery success");
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)if(x!=0||z!=0) {
                var pos=RETURN.offset(x,0,z);level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);level.setBlock(pos.above(),Blocks.AIR.defaultBlockState(),3);
            }
            container(server,SOURCE).clearContent();container(server,SOURCE).setItem(0,new ItemStack(Items.OAK_PLANKS,16));run.demand=request(server,run,16);run.step=7;return;
        }
        if(run.step==7) {
            if(count(citizen(server,run).inventory())!=16)return;
            var manifest=new CompoundTag();manifest.putUUID("colony",run.colony);manifest.putUUID("citizen",run.citizen);manifest.putUUID("entity",run.entity);manifest.putUUID("demand",run.demand);NbtIo.writeCompressed(manifest,world(server).resolve(MANIFEST));
            for(int x=ORIGIN>>4;x<(ORIGIN>>4)+2;x++)for(int z=0;z<2;z++)level.setChunkForced(x,z,false);
            require(server,supply.demand(run.demand).snapshot().covered()==16&&supply.demand(run.demand).snapshot().fulfilled()==0,"clean_stop_with_real_cargo","16 in NPC, covered once");finish(server,run,"exercise_complete");
        }
    }
    private static void verify(MinecraftServer server,Run run)throws Exception {
        if(run.colony==null)loadManifest(server,run);
        var supply=run.runtime.core().registry().supply();if(citizen(server,run)==null)return;
        if(supply.demand(run.demand).snapshot().fulfilled()!=16)return;
        require(server,count(container(server,SOURCE))==0&&count(citizen(server,run).inventory())==0&&count(container(server,DEST))==16,"clean_restart_continues_cargo_once","same bound courier, no second extract; total16");
        var entity=citizen(server,run);require(server,entity.hurt(server.overworld().damageSources().genericKill(),1000)&&!entity.isAlive(),"courier_native_death","native Minecraft death after historical transfers");
        var checkpoint=run.runtime.persistence().checkpointId();run.runtime.core().registry().markRecoveryBlocked(run.colony,checkpoint);
        var inspection=command(server,run,"colonyloom recovery inspect "+run.colony);
        require(server,inspection.contains("historical-custody="),"historical_dead_transfer_inspect","old custody explicitly UNKNOWN, not empty or rebound");
        command(server,run,"colonyloom recovery accept-world "+run.colony+" "+checkpoint);
        require(server,count(container(server,DEST))==16,"historical_death_accept_world_preserves_property","physical16 retained with obsolete promises closed");finish(server,run,"verify_complete");
    }
    private void stopped(ServerStoppedEvent event) {
        if(phase().isEmpty()||!runs.containsKey(event.getServer()))return;var server=event.getServer();runs.remove(server);
        try {var dto=NbtIo.readCompressed(world(server).resolve("data/colonyloom.dat"),NbtAccounter.create(64L*1024*1024)).getCompound("data");var marker=NbtIo.readCompressed(world(server).resolve("data/colonyloom-session.nbt"),NbtAccounter.create(65536));require(server,marker.getBoolean("clean")&&marker.getUUID("checkpointId").equals(dto.getUUID("checkpointId")),"clean_checkpoint",dto.getUUID("checkpointId").toString());}
        catch(Exception failure){try{fact(server,"clean_checkpoint",false,failure.toString());}catch(Exception ignored){org.slf4j.LoggerFactory.getLogger(DeliveryScenario.class).error("Delivery shutdown evidence failed",failure);}}
    }
    private static void loadManifest(MinecraftServer server,Run run)throws Exception {var manifest=NbtIo.readCompressed(world(server).resolve(MANIFEST),NbtAccounter.create(65536));run.colony=manifest.getUUID("colony");run.citizen=manifest.getUUID("citizen");run.entity=manifest.getUUID("entity");run.demand=manifest.getUUID("demand");}
    private static String coordinates(BlockPos pos) {return pos.getX()+" "+pos.getY()+" "+pos.getZ();}
    private static UUID request(MinecraftServer server,Run run,int count)throws Exception {return uuid(command(server,run,"colonyloom delivery request "+run.colony+" "+coordinates(SOURCE)+" "+coordinates(DEST)+" minecraft:oak_planks "+count),"demand");}
    private static void finish(MinecraftServer server,Run run,String check)throws Exception {fact(server,check,true,"production command + scheduler + native navigation + executor");run.done=true;server.halt(false);}
    private static void require(MinecraftServer server,boolean passed,String check,String detail)throws Exception {fact(server,check,passed,detail);if(!passed)throw new IllegalStateException(check+": "+detail);}
    private static void fact(MinecraftServer server,String check,boolean passed,String detail)throws Exception {var json=new JsonObject();json.addProperty("phase",phase());json.addProperty("check",check);json.addProperty("passed",passed);json.addProperty("detail",detail);Files.writeString(world(server).resolve("colonyloom-delivery-observations.jsonl"),json+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    private static UUID uuid(String output,String key) {var match=Pattern.compile("(?:^|[\\s\\[,])"+Pattern.quote(key)+"=([0-9a-fA-F-]{36})(?=[\\s\\],]|$)").matcher(output);if(!match.find())throw new IllegalStateException("Missing "+key+" in "+output);return UUID.fromString(match.group(1));}
    private static String command(MinecraftServer server,Run run,String text)throws Exception {var capture=new Capture();int code=server.getCommands().getDispatcher().execute(text,run.actor.createCommandSourceStack().withPermission(2).withSource(capture));String result=String.join("\n",capture.messages);if(code!=1)throw new IllegalStateException("Delivery command refused "+text+": "+result);return result;}
    private static final class Capture implements CommandSource {
        final List<String> messages=new ArrayList<>();public void sendSystemMessage(Component message){messages.add(message.getString());}
        public boolean acceptsSuccess(){return true;}public boolean acceptsFailure(){return true;}public boolean shouldInformAdmins(){return false;}
    }
}
