package io.github.kpuctajluk.colonyloom.gametest;

import com.mojang.authlib.GameProfile;
import com.google.gson.JsonObject;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
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

/** Only initial resources and terrain are fixture-owned; every economic effect uses production code. */
final class ProductionScenario {
    private static final UUID OWNER=UUID.nameUUIDFromBytes("OfflinePlayer:ProductionOwner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final BlockPos SOURCE=new BlockPos(8,64,8),BARREL=new BlockPos(12,64,12),TABLE=BARREL.east(),ORIGIN=new BlockPos(16,64,8),BUFFER=ORIGIN.west(),RETURN=new BlockPos(8,64,12);
    private final Map<MinecraftServer,Run> runs=new IdentityHashMap<>();
    private static final class Run {MinecraftServerRuntime runtime;ServerPlayer actor;UUID colony,work;int ticks,step;boolean done,reloadStarted,reloadChecked,invalidChecked;java.util.concurrent.CompletableFuture<Void> reload,invalidReload;net.minecraft.server.packs.resources.ResourceManager acceptedManager;String blueprintDigest;final Map<UUID,String> deliveryStates=new HashMap<>();}
    ProductionScenario() {NeoForge.EVENT_BUS.addListener(this::configure);NeoForge.EVENT_BUS.addListener(this::tick);NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,this::stopped);}
    private static String phase(){return System.getProperty("colonyloom.test.productionPhase","");}
    private static Path world(MinecraftServer server){return server.getWorldPath(LevelResource.ROOT);}
    private void configure(ConstructionExecutorEvent event){if(!phase().isEmpty())runs.computeIfAbsent(event.server(),ignored -> new Run()).runtime=event.runtime();}
    private void tick(ServerTickEvent.Post event) {
        if(phase().isEmpty())return;var server=event.getServer();var run=runs.computeIfAbsent(server,ignored -> new Run());if(run.done)return;
        try {
            if(!server.isDedicatedServer()||!Files.isRegularFile(world(server).resolve("colonyloom-test-world")))throw new IllegalStateException("Production requires disposable marked dedicated world");
            if(++run.ticks>9000)throw new IllegalStateException("Production progress timeout step="+run.step+" state="+run.runtime.minecraftMetrics().snapshot(null)+" supply="+run.runtime.core().registry().supply().snapshot());
            if(run.runtime==null)return;
            if(run.actor==null){var profile=new GameProfile(OWNER,"ProductionOwner");server.getProfileCache().add(profile);run.actor=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault());run.actor.moveTo(8.5,64,14.5,0,0);for(int x=0;x<2;x++)for(int z=0;z<2;z++)server.overworld().setChunkForced(x,z,true);}
            if(run.ticks<60||!server.overworld().isPositionEntityTicking(ORIGIN.east(15)))return;
            if(phase().equals("verify")){verify(server,run);return;}
            exercise(server,run);
        }catch(Exception failure){run.done=true;try{fact(server,"failure",false,failure.toString());}catch(Exception evidence){failure.addSuppressed(evidence);}org.slf4j.LoggerFactory.getLogger(ProductionScenario.class).error("Production smoke failed",failure);server.halt(false);}
    }
    private static void exercise(MinecraftServer server,Run run)throws Exception {
        var level=server.overworld();var registry=run.runtime.core().registry();
        if(run.step==1 && run.ticks%200==0) {
            var diagnostic=new JsonObject();diagnostic.addProperty("tick",run.ticks);
            var works=new com.google.gson.JsonArray();
            for(var work:registry.workBoard().works()) {
                var state=new JsonObject();state.addProperty("id",work.id().toString());state.addProperty("type",work.typeId());
                state.addProperty("stage",work.stage());state.addProperty("reason",work.waitingReason().toString());
                state.addProperty("state",work.state().toString());state.addProperty("navigation",run.runtime.navigation().state(work.id()).toString());
                if(work.assignee()!=null) {var citizen=registry.citizen(work.assignee());var nativeCitizen=level.getEntity(citizen.entityId());if(nativeCitizen!=null)state.addProperty("position",nativeCitizen.position().toString());}
                works.add(state);
            }
            diagnostic.add("works",works);Files.writeString(world(server).resolve("colonyloom-production-progress.jsonl"),diagnostic+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }
        for(var order:registry.supply().deliveries()) {
            if(order.workId()==null)continue;
            var work=registry.workBoard().work(order.workId());
            String status=order.state()+":"+work.stage()+":"+work.waitingReason();
            String old=run.deliveryStates.put(order.id(),status);if(status.equals(old))continue;
            var diagnostic=new JsonObject();
            diagnostic.addProperty("tick",run.ticks);diagnostic.addProperty("delivery",order.id().toString());
            diagnostic.addProperty("state",order.state().toString());diagnostic.addProperty("stage",work.stage());
            diagnostic.addProperty("reason",work.waitingReason().toString());diagnostic.addProperty("navigation",run.runtime.navigation().state(work.id()).toString());
            if(order.citizenId()!=null) {var npc=level.getEntity(registry.citizen(order.citizenId()).entityId());if(npc!=null)diagnostic.addProperty("position",npc.position().toString());}
            Files.writeString(world(server).resolve("colonyloom-production-progress.jsonl"),diagnostic+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }
        if(phase().equals("exercise")&&run.step==0) {
            var saved=read(world(server).resolve("colonyloom-production-fixture.nbt"));run.colony=saved.getUUID("colony");run.work=saved.getUUID("work");run.step=1;
            var order=registry.supply().production(saved.getUUID("production"));
            require(server,(order.batchStarted()&&order.remainingActiveTicks()<=saved.getLong("remaining")||order.completedBatches()>0)&&order.recipe().version()==1,"active_checkpoint_continues","saved remaining="+saved.getLong("remaining")+" current="+order.remainingActiveTicks()+" completed="+order.completedBatches());
        }
        if(run.step==0){
            for(int x=5;x<34;x++)for(int z=5;z<17;z++)level.setBlock(new BlockPos(x,63,z),Blocks.STONE.defaultBlockState(),3);
            run.colony=uuid(command(server,run,"colonyloom colony create ProductionFixture 0 64 0 47 64 31"),"colony");
            level.setBlock(SOURCE,Blocks.CHEST.defaultBlockState(),3);for(var pos:List.of(BARREL,BUFFER,RETURN))level.setBlock(pos,Blocks.BARREL.defaultBlockState(),3);level.setBlock(TABLE,Blocks.CRAFTING_TABLE.defaultBlockState(),3);
            for(var entry:Map.of(SOURCE,"warehouse",BARREL,"workshop",BUFFER,"construction",RETURN,"return").entrySet())command(server,run,"colonyloom storage register "+run.colony+" "+coordinates(entry.getKey())+" "+entry.getValue());
            command(server,run,"colonyloom building register "+run.colony+" "+coordinates(TABLE)+" "+coordinates(BARREL));
            var workshop=registry.storage().workshops().getFirst();
            for(int i=0;i<3;i++){var output=command(server,run,"colonyloom citizen create "+run.colony+" "+(10+i*5)+" 64 15");var citizen=uuid(output,"citizen");command(server,run,"colonyloom citizen assign "+citizen+" colonyloom:"+List.of("carpenter","courier","builder").get(i));if(i==0)command(server,run,"colonyloom citizen workplace "+citizen+" "+workshop.id());}
            ((Container)level.getBlockEntity(SOURCE)).setItem(0,new ItemStack(Items.OAK_LOG,6));
            run.work=uuid(command(server,run,"colonyloom build "+run.colony+" colonyloom:stair_strip "+coordinates(ORIGIN)+" 0"),"work");run.step=1;return;
        }
        if(run.step==1){
            if(phase().equals("initialize")) {
                var processing=registry.supply().productionOrders().stream().filter(p -> p.batchStarted()&&p.remainingActiveTicks()>0).findFirst().orElse(null);
                if(processing==null)return;
                var manifest=new CompoundTag();manifest.putUUID("colony",run.colony);manifest.putUUID("work",run.work);manifest.putUUID("production",processing.id());manifest.putLong("remaining",processing.remainingActiveTicks());NbtIo.writeCompressed(manifest,world(server).resolve("colonyloom-production-fixture.nbt"));
                fact(server,"initialize_complete",true,"physical producer checkpointed active batch remaining="+processing.remainingActiveTicks());run.done=true;server.halt(false);return;
            }
            if(!run.reloadStarted&&registry.supply().productionOrders().size()==2&&registry.supply().productionOrders().stream().anyMatch(p -> p.batchStarted())) {
                run.blueprintDigest=registry.construction().site(run.work).blueprintDigest();
                var pack=world(server).resolve("datapacks/production-reload");Files.createDirectories(pack.resolve("data/colonyloom/colonyloom/processes"));Files.createDirectories(pack.resolve("data/colonyloom/colonyloom/blueprints"));
                Files.writeString(pack.resolve("pack.mcmeta"),"{\"pack\":{\"pack_format\":48,\"description\":\"Disposable pinned version smoke\"}}");
                Files.writeString(pack.resolve("data/colonyloom/colonyloom/processes/oak_stairs.json"),"{\"schemaVersion\":1,\"version\":2,\"profession\":\"colonyloom:carpenter\",\"equipment\":\"minecraft:crafting_table\",\"inputs\":[{\"item\":\"minecraft:oak_planks\",\"count\":6}],\"output\":{\"item\":\"minecraft:oak_stairs\",\"count\":8},\"durationTicks\":80}");
                Files.writeString(pack.resolve("data/colonyloom/colonyloom/blueprints/stair_strip.json"),"{\"schemaVersion\":1,\"version\":2,\"structure\":\"colonyloom:stair_strip\",\"markers\":{\"work_origin\":[0,0,2],\"delivery_buffer\":[-1,0,0]}}");
                server.getPackRepository().reload();var selected=new ArrayList<>(server.getPackRepository().getSelectedIds());selected.add("file/production-reload");run.reload=server.reloadResources(selected);run.reloadStarted=true;return;
            }
            if(run.reloadStarted&&!run.reloadChecked) {
                if(!run.reload.isDone())return;run.reload.join();
                var loaded=io.github.kpuctajluk.colonyloom.minecraft.content.ContentLoader.load(server.getResourceManager(),server.registryAccess());
                require(server,loaded.processes().get("colonyloom:oak_stairs").recipe().version()==2&&loaded.blueprints().get("colonyloom:stair_strip").version()==2,"reload_new_definition_set","real datapack recipe output8 and blueprint v2 visible");
                require(server,registry.construction().site(run.work).blueprintDigest().equals(run.blueprintDigest)&&registry.supply().productionOrders().stream().allMatch(p -> p.recipe().version()==1),"active_versions_pinned","accepted site and production still version1");run.reloadChecked=true;
                run.acceptedManager=server.getResourceManager();
                var recipe=world(server).resolve("datapacks/production-reload/data/colonyloom/colonyloom/processes/oak_stairs.json");
                Files.writeString(recipe,Files.readString(recipe).replace("\"count\":8","\"count\":0"));
                run.invalidReload=server.reloadResources(server.getPackRepository().getSelectedIds());return;
            }
            if(run.reloadChecked&&!run.invalidChecked) {
                if(!run.invalidReload.isDone())return;
                require(server,run.invalidReload.isCompletedExceptionally()&&server.getResourceManager()==run.acceptedManager,"invalid_reload_rejected_atomically","malformed zero output never replaced accepted resource manager");
                var recipe=world(server).resolve("datapacks/production-reload/data/colonyloom/colonyloom/processes/oak_stairs.json");
                Files.writeString(recipe,Files.readString(recipe).replace("\"count\":0","\"count\":8"));run.invalidChecked=true;
            }
            if(registry.workBoard().work(run.work).state()!=WorkOrder.State.COMPLETED)return;
            assertComplete(server,run);
            require(server,run.reloadChecked&&run.invalidChecked,"reload_during_chain","accepted chain pinned and invalid set refused through actual resource reload");
            var manifest=new CompoundTag();manifest.putUUID("colony",run.colony);manifest.putUUID("work",run.work);NbtIo.writeCompressed(manifest,world(server).resolve("colonyloom-production-fixture.nbt"));
            fact(server,"exercise_complete",true,"6 logs -> 24 planks -> 16 stairs -> 16 physical blocks, exact allocations released");run.done=true;server.halt(false);
        }
    }
    private static void verify(MinecraftServer server,Run run)throws Exception {
        if(run.colony==null){var manifest=read(world(server).resolve("colonyloom-production-fixture.nbt"));run.colony=manifest.getUUID("colony");run.work=manifest.getUUID("work");}
        if(run.runtime.core().registry().citizens(run.colony).stream().anyMatch(c -> server.overworld().getEntity(c.entityId())==null))return;
        assertComplete(server,run);fact(server,"verify_complete",true,"clean restart preserves completed physical chain; no repeated issue");run.done=true;server.halt(false);
    }
    private static void assertComplete(MinecraftServer server,Run run)throws Exception {
        var registry=run.runtime.core().registry();
        for(int i=0;i<16;i++)require(server,server.overworld().getBlockState(ORIGIN.east(i)).equals(Blocks.OAK_STAIRS.defaultBlockState()),"target_"+i,"exact oak stairs state");
        int logs=0,planks=0,stairs=0;
        for(var pos:List.of(SOURCE,BARREL,BUFFER,RETURN)){var container=(Container)server.overworld().getBlockEntity(pos);for(int i=0;i<container.getContainerSize();i++){var stack=container.getItem(i);if(stack.is(Items.OAK_LOG))logs+=stack.getCount();if(stack.is(Items.OAK_PLANKS))planks+=stack.getCount();if(stack.is(Items.OAK_STAIRS))stairs+=stack.getCount();}}
        for(var citizen:registry.citizens(run.colony)){var npc=(CitizenEntity)server.overworld().getEntity(citizen.entityId());for(int i=0;i<npc.inventory().getContainerSize();i++){var stack=npc.inventory().getItem(i);if(stack.is(Items.OAK_LOG))logs+=stack.getCount();if(stack.is(Items.OAK_PLANKS))planks+=stack.getCount();if(stack.is(Items.OAK_STAIRS))stairs+=stack.getCount();}}
        require(server,logs==0&&planks==0&&stairs==0,"exact_resources","logs="+logs+" planks="+planks+" stairs="+stairs);
        require(server,registry.construction().site(run.work).consumed()==16,"construction_consumed","actual16 native placements");
        require(server,registry.storage().reservations().entries().isEmpty()&&registry.storage().allocations().entries().isEmpty(),"no_residual_obligations","all physical material claims released");
        require(server,registry.supply().productionOrders().stream().allMatch(p -> p.terminal()&&p.batches()==0)&&registry.supply().deliveries().stream().allMatch(d -> d.terminal()),"chain_terminal","no unfinished native production/cargo");
    }
    private void stopped(ServerStoppedEvent event){if(phase().isEmpty()||runs.remove(event.getServer())==null)return;try{var dto=read(world(event.getServer()).resolve("data/colonyloom.dat")).getCompound("data");var marker=read(world(event.getServer()).resolve("data/colonyloom-session.nbt"));require(event.getServer(),marker.getBoolean("clean")&&marker.getUUID("checkpointId").equals(dto.getUUID("checkpointId")),"clean_checkpoint",dto.getUUID("checkpointId").toString());}catch(Exception failure){try{fact(event.getServer(),"clean_checkpoint",false,failure.toString());}catch(Exception ignored){org.slf4j.LoggerFactory.getLogger(ProductionScenario.class).error("Production clean proof failed",failure);}}}
    private static CompoundTag read(Path path)throws Exception{return NbtIo.readCompressed(path,NbtAccounter.create(64L*1024*1024));}
    private static String coordinates(BlockPos pos){return pos.getX()+" "+pos.getY()+" "+pos.getZ();}
    private static void require(MinecraftServer server,boolean passed,String check,String detail)throws Exception{fact(server,check,passed,detail);if(!passed)throw new IllegalStateException(check+": "+detail);}
    private static void fact(MinecraftServer server,String check,boolean passed,String detail)throws Exception{var json=new JsonObject();json.addProperty("phase",phase());json.addProperty("check",check);json.addProperty("passed",passed);json.addProperty("detail",detail);Files.writeString(world(server).resolve("colonyloom-production-observations.jsonl"),json+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    private static UUID uuid(String output,String key){var match=Pattern.compile("(?:^|[\\s\\[,])"+Pattern.quote(key)+"=([0-9a-fA-F-]{36})(?=[\\s\\],]|$)").matcher(output);if(!match.find())throw new IllegalStateException("Missing "+key+" in "+output);return UUID.fromString(match.group(1));}
    private static String command(MinecraftServer server,Run run,String text)throws Exception{var capture=new Capture();int code=server.getCommands().getDispatcher().execute(text,run.actor.createCommandSourceStack().withPermission(2).withSource(capture));String result=String.join("\n",capture.messages);if(code!=1)throw new IllegalStateException("Public production command refused "+text+": "+result);return result;}
    private static final class Capture implements CommandSource {final List<String> messages=new ArrayList<>();public void sendSystemMessage(Component message){messages.add(message.getString());}public boolean acceptsSuccess(){return true;}public boolean acceptsFailure(){return true;}public boolean shouldInformAdmins(){return false;}}
}
