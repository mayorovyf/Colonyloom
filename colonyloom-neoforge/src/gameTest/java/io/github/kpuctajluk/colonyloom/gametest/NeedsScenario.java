package io.github.kpuctajluk.colonyloom.gametest;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
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

/** Disposable resources only; the normal runtime acquires and consumes the real bread. */
final class NeedsScenario {
    private static final UUID OWNER=UUID.nameUUIDFromBytes("OfflinePlayer:NeedsOwner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final BlockPos WAREHOUSE=new BlockPos(8,64,8),START=new BlockPos(12,64,10);
    private static final class Run {MinecraftServerRuntime runtime;ServerPlayer actor;UUID colony,citizen;int ticks;long observationAt=-1;boolean done;}
    private final Map<MinecraftServer,Run> runs=new IdentityHashMap<>();
    NeedsScenario(){NeoForge.EVENT_BUS.addListener(this::configure);NeoForge.EVENT_BUS.addListener(this::tick);NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,this::stopped);}
    private static String phase(){return System.getProperty("colonyloom.test.needsPhase","");}
    private static Path world(MinecraftServer server){return server.getWorldPath(LevelResource.ROOT);}
    private void configure(ConstructionExecutorEvent event){if(!phase().isEmpty())runs.computeIfAbsent(event.server(),ignored -> new Run()).runtime=event.runtime();}
    private void tick(ServerTickEvent.Post event){
        if(phase().isEmpty())return;var server=event.getServer();var run=runs.computeIfAbsent(server,ignored -> new Run());if(run.done)return;
        try{
            if(!server.isDedicatedServer()||!Files.isRegularFile(world(server).resolve("colonyloom-test-world")))throw new IllegalStateException("Needs requires marked disposable dedicated world");
            if(++run.ticks>6000)throw new IllegalStateException("Needs timeout: "+(run.runtime==null?"runtime unavailable":run.runtime.minecraftMetrics().snapshot(null)));
            if(run.runtime==null)return;
            if(run.actor==null){var profile=new GameProfile(OWNER,"NeedsOwner");server.getProfileCache().add(profile);run.actor=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault());run.actor.moveTo(12.5,64,14.5,0,0);for(int x=0;x<2;x++)for(int z=0;z<2;z++)server.overworld().setChunkForced(x,z,true);}
            if(run.ticks<60||!server.overworld().isPositionEntityTicking(START))return;
            if(phase().equals("exercise"))exercise(server,run);else if(phase().equals("verify"))verify(server,run);else throw new IllegalStateException("Unknown needs phase");
        }catch(Exception failure){run.done=true;try{fact(server,"failure",false,failure.toString());}catch(Exception evidence){failure.addSuppressed(evidence);}org.slf4j.LoggerFactory.getLogger(NeedsScenario.class).error("Needs smoke failed",failure);server.halt(false);}
    }
    private static void exercise(MinecraftServer server,Run run)throws Exception{
        var level=server.overworld();var registry=run.runtime.core().registry();
        if(run.colony==null){
            for(int x=5;x<=16;x++)for(int z=5;z<=16;z++){level.setBlock(new BlockPos(x,63,z),Blocks.STONE.defaultBlockState(),3);for(int y=64;y<=66;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);}
            run.colony=uuid(command(server,run,"colonyloom colony create NeedsFixture 0 64 0 31 64 31"),"colony");
            level.setBlock(WAREHOUSE,Blocks.BARREL.defaultBlockState(),3);
            command(server,run,"colonyloom storage register "+run.colony+" "+coordinates(WAREHOUSE)+" warehouse");
            ((Container)level.getBlockEntity(WAREHOUSE)).setItem(0,new ItemStack(Items.BREAD,2));
            run.citizen=uuid(command(server,run,"colonyloom citizen create "+run.colony+" "+coordinates(START)),"citizen");
            command(server,run,"colonyloom citizen assign "+run.citizen+" colonyloom:builder");
            var citizen=registry.citizen(run.citizen);
            registry.updateCitizen(new CitizenRecord(citizen.citizenId(),citizen.colonyId(),citizen.entityId(),citizen.bindingEpoch(),citizen.homeId(),citizen.workplaceId(),citizen.assignedWorkId(),citizen.professionId(),citizen.skills(),Map.of("food",6),citizen.lifecycle(),citizen.admission(),citizen.readiness(),citizen.activeTimeTicks(),Map.of("food",1200L),citizen.lastKnownPosition(),citizen.revision()+1));
            return;
        }
        var citizen=registry.citizen(run.citizen);if(citizen.needs().get("food")!=11)return;
        if(run.observationAt<0){
            require(server,totalBread(server,run)==1,"exact_bread_consumption","initial 2 bread -> exactly 1 across warehouse and bound resident; food6->11");
            require(server,registry.workBoard().works().stream().anyMatch(work -> WorkOrder.FOOD.equals(work.typeId())&&work.state()==WorkOrder.State.COMPLETED&&run.citizen.equals(work.subjectId())),"food_subject_completed","prescribed hungry builder completed its physical food work");
            run.observationAt=run.runtime.core().serverTick();return;
        }
        if(run.runtime.core().serverTick()-run.observationAt<100)return;
        require(server,totalBread(server,run)==1&&citizen.needs().get("food")==11,"no_duplicate_consumption","100 further ticks preserved single consumption");
        var manifest=new CompoundTag();manifest.putUUID("colony",run.colony);manifest.putUUID("citizen",run.citizen);manifest.putInt("food",11);manifest.putLong("remaining",citizen.remainingTimers().get("food"));manifest.putLong("active",citizen.activeTimeTicks());NbtIo.writeCompressed(manifest,world(server).resolve("colonyloom-needs-fixture.nbt"));
        fact(server,"exercise_complete",true,"real warehouse pickup, exact one consumption and persisted active-clock residual");run.done=true;server.halt(false);
    }
    private static void verify(MinecraftServer server,Run run)throws Exception{
        var saved=read(world(server).resolve("colonyloom-needs-fixture.nbt"));run.colony=saved.getUUID("colony");run.citizen=saved.getUUID("citizen");var citizen=run.runtime.core().registry().citizen(run.citizen);
        if(!(server.overworld().getEntity(citizen.entityId()) instanceof CitizenEntity)||citizen.readiness()!=CitizenRecord.Readiness.READY)return;
        require(server,totalBread(server,run)==1&&citizen.needs().get("food")==11,"clean_food_preserved","restart retained real bread and exact food; no repeated consumption");
        long elapsed=citizen.activeTimeTicks()-saved.getLong("active");
        require(server,elapsed>=0&&citizen.remainingTimers().get("food")==saved.getLong("remaining")-elapsed,"clean_timer_preserved","remaining="+citizen.remainingTimers().get("food")+" checkpoint="+saved.getLong("remaining")+" active delta="+elapsed);
        fact(server,"verify_complete",true,"clean restart preserved food and residual with no wall-clock catchup");run.done=true;server.halt(false);
    }
    private static int totalBread(MinecraftServer server,Run run){int result=0;var warehouse=(Container)server.overworld().getBlockEntity(WAREHOUSE);for(int slot=0;slot<warehouse.getContainerSize();slot++)if(warehouse.getItem(slot).is(Items.BREAD))result+=warehouse.getItem(slot).getCount();for(var record:run.runtime.core().registry().citizens(run.colony)){var entity=server.overworld().getEntity(record.entityId());if(entity instanceof CitizenEntity npc)for(int slot=0;slot<npc.inventory().getContainerSize();slot++)if(npc.inventory().getItem(slot).is(Items.BREAD))result+=npc.inventory().getItem(slot).getCount();}return result;}
    private void stopped(ServerStoppedEvent event){if(phase().isEmpty()||runs.remove(event.getServer())==null)return;try{var dto=read(world(event.getServer()).resolve("data/colonyloom.dat")).getCompound("data");var marker=read(world(event.getServer()).resolve("data/colonyloom-session.nbt"));require(event.getServer(),marker.getBoolean("clean")&&marker.getUUID("checkpointId").equals(dto.getUUID("checkpointId")),"clean_checkpoint",dto.getUUID("checkpointId").toString());}catch(Exception failure){try{fact(event.getServer(),"clean_checkpoint",false,failure.toString());}catch(Exception evidence){org.slf4j.LoggerFactory.getLogger(NeedsScenario.class).error("Needs checkpoint proof failed",evidence);}}}
    private static CompoundTag read(Path path)throws Exception{return NbtIo.readCompressed(path,NbtAccounter.create(64L*1024*1024));}
    private static String coordinates(BlockPos pos){return pos.getX()+" "+pos.getY()+" "+pos.getZ();}
    private static void require(MinecraftServer server,boolean passed,String check,String detail)throws Exception{fact(server,check,passed,detail);if(!passed)throw new IllegalStateException(check+": "+detail);}
    private static void fact(MinecraftServer server,String check,boolean passed,String detail)throws Exception{var json=new JsonObject();json.addProperty("phase",phase());json.addProperty("check",check);json.addProperty("passed",passed);json.addProperty("detail",detail);Files.writeString(world(server).resolve("colonyloom-needs-observations.jsonl"),json+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    private static UUID uuid(String output,String key){var match=Pattern.compile("(?:^|[\\s\\[,])"+Pattern.quote(key)+"=([0-9a-fA-F-]{36})(?=[\\s\\],]|$)").matcher(output);if(!match.find())throw new IllegalStateException("Missing "+key+" in "+output);return UUID.fromString(match.group(1));}
    private static String command(MinecraftServer server,Run run,String text)throws Exception{var capture=new Capture();int code=server.getCommands().getDispatcher().execute(text,run.actor.createCommandSourceStack().withPermission(2).withSource(capture));String result=String.join("\n",capture.messages);if(code!=1)throw new IllegalStateException("Public needs command refused "+text+": "+result);return result;}
    private static final class Capture implements CommandSource{final List<String> messages=new ArrayList<>();public void sendSystemMessage(Component message){messages.add(message.getString());}public boolean acceptsSuccess(){return true;}public boolean acceptsFailure(){return true;}public boolean shouldInformAdmins(){return false;}}
}
