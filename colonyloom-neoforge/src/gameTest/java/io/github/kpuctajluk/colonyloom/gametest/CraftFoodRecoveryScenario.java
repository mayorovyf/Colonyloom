package io.github.kpuctajluk.colonyloom.gametest;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.action.EffectRecord;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime;
import io.github.kpuctajluk.colonyloom.neoforge.ConstructionExecutorEvent;
import java.nio.channels.FileChannel;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Disposable inputs only: real planning, hauling, active production and hunger own every expense. */
final class CraftFoodRecoveryScenario {
    private static final UUID OWNER=UUID.nameUUIDFromBytes("OfflinePlayer:RecoveryOwner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final BlockPos SOURCE=new BlockPos(8,64,8),BARREL=new BlockPos(12,64,12),TABLE=BARREL.east(),ORIGIN=new BlockPos(16,64,8),BUFFER=ORIGIN.west(),RETURN=new BlockPos(8,64,12);
    private static final String MANIFEST="colonyloom-craft-food-recovery-fixture.nbt";
    private static final List<String> SCENARIOS=List.of("craft_clean","craft_before_effect","craft_after_source_change","craft_after_destination_change","craft_after_fact_before_notify","food_clean","food_before_effect","food_after_source_change","food_after_fact_before_notify");
    private static final class Run {
        MinecraftServerRuntime runtime; ServerPlayer actor; CompoundTag manifest; EffectRecord acceptedWitness;
        int ticks,stage; long hold=-1; boolean done,activated,baselineChecked;
    }
    private final Map<MinecraftServer,Run> runs=new IdentityHashMap<>();
    CraftFoodRecoveryScenario(){NeoForge.EVENT_BUS.addListener(this::configure);NeoForge.EVENT_BUS.addListener(this::tick);NeoForge.EVENT_BUS.addListener(this::acceptanceFoodVeto);NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,this::stopped);}
    private void acceptanceFoodVeto(io.github.kpuctajluk.colonyloom.neoforge.FoodConsumeEvent event) {
        if(!food()||!phase().equals("accept")||!SCENARIOS.contains(scenario()))return;
        var run=runs.get(event.server());if(run!=null&&run.stage==1)event.setCanceled(true);
    }
    private static String scenario(){return System.getProperty("colonyloom.test.recoveryScenario","");}
    private static String phase(){return System.getProperty("colonyloom.test.recoveryPhase","");}
    private static boolean food(){return scenario().startsWith("food_");}
    private static boolean clean(){return scenario().endsWith("_clean");}
    private static Path world(MinecraftServer server){return server.getWorldPath(LevelResource.ROOT);}
    private static void guard(MinecraftServer server){
        if(!server.isDedicatedServer()||!SCENARIOS.contains(scenario())
                ||!List.of("initialize","exercise","verify","accept").contains(phase())||!Files.isRegularFile(world(server).resolve("colonyloom-test-world")))
            throw new IllegalStateException("Recovery fixture requires marked disposable dedicated world and exact scenario/phase");
    }
    private void configure(ConstructionExecutorEvent event){
        if(!SCENARIOS.contains(scenario()))return;
        var run=runs.computeIfAbsent(event.server(),ignored -> new Run());run.runtime=event.runtime();
        if(phase().equals("exercise")&&!food())try{
            preload(event.server(),run);
            var registry=run.runtime.core().registry();var order=registry.supply().production(run.manifest.getUUID("production"));
            require(event.server(),run,order.batchStarted()&&order.completedBatches()==0
                    &&order.workId().equals(run.manifest.getUUID("productionWork"))&&order.citizenId().equals(citizen(run))
                    &&order.remainingActiveTicks()==run.manifest.getLong("remaining")
                    &&registry.citizen(citizen(run)).activeTimeTicks()==run.manifest.getLong("active"),
                    "restored_exact_active_residual","before first tick: remaining="+order.remainingActiveTicks());
        }catch(Exception failure){throw new IllegalStateException("Original active checkpoint changed during restore",failure);}
        if(!phase().equals("exercise")||clean()||!Boolean.getBoolean("colonyloom.testFaults"))return;
        if(food())event.foodObserver((point,context) -> fault(event.server(),run,point.name(),context));
        else event.recipeObserver((point,context) -> fault(event.server(),run,point.name(),context));
    }
    private static void preload(MinecraftServer server,Run run)throws Exception{
        if(run.manifest!=null||phase().equals("initialize"))return;
        run.manifest=read(world(server).resolve(MANIFEST));
        if(!run.manifest.getString("scenario").equals(scenario())||!run.manifest.getUUID("owner").equals(OWNER))throw new IllegalStateException("Recovery manifest identity mismatch");
    }
    private static UUID colony(Run run){return run.manifest.getUUID("colony");}
    private static UUID citizen(Run run){return run.manifest.getUUID("citizen");}
    private static CitizenEntity entity(MinecraftServer server,Run run){
        var value=server.overworld().getEntity(run.manifest.getUUID("entity"));
        if(!(value instanceof CitizenEntity npc))return null;
        if(!citizen(run).equals(npc.citizenId())||npc.bindingEpoch()!=run.manifest.getLong("epoch"))throw new IllegalStateException("Resident binding changed");
        return npc;
    }
    private static void admission(Run run,UUID id,CitizenRecord.Admission admission){run.runtime.core().commands().updateCitizenAdmission(id,admission);}
    private void tick(ServerTickEvent.Post event){
        if(!SCENARIOS.contains(scenario()))return;var server=event.getServer();var run=runs.computeIfAbsent(server,ignored -> new Run());if(run.done)return;
        try{
            guard(server);preload(server,run);
            if(++run.ticks>9000)throw new IllegalStateException("Recovery progress timeout stage="+run.stage+" metrics="+(run.runtime==null?"unconfigured":run.runtime.minecraftMetrics().snapshot(null)));
            if(run.runtime==null)return;
            if(run.actor==null){var profile=new GameProfile(OWNER,"RecoveryOwner");server.getProfileCache().add(profile);run.actor=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault());run.actor.moveTo(12.5,64,15.5,0,0);for(int x=0;x<2;x++)for(int z=0;z<2;z++)server.overworld().setChunkForced(x,z,true);}
            if(phase().equals("exercise")&&!food()) {
                var registry=run.runtime.core().registry();var order=registry.supply().production(run.manifest.getUUID("production"));
                long elapsed=registry.citizen(citizen(run)).activeTimeTicks()-run.manifest.getLong("active");
                long advanced=run.manifest.getLong("remaining")-order.remainingActiveTicks();
                if(elapsed<0||advanced>elapsed||order.completedBatches()>0&&elapsed<run.manifest.getLong("remaining"))
                    throw new IllegalStateException("Batch skipped active time: elapsed="+elapsed+" advanced="+advanced);
            }
            if(run.ticks<60||!server.overworld().isPositionEntityTicking(ORIGIN.east(15)))return;
            if(phase().equals("initialize")){initialize(server,run);return;}
            var npc=entity(server,run);if(npc==null)return;
            var record=run.runtime.core().registry().citizen(citizen(run));
            if(!record.entityId().equals(npc.getUUID())||record.bindingEpoch()!=npc.bindingEpoch())throw new IllegalStateException("Logical/native identity disagreement");
            if(phase().equals("exercise")){exercise(server,run);return;}
            if(phase().equals("verify")){verify(server,run);return;}
            accept(server,run);
        }catch(Exception failure){run.done=true;try{fact(server,run,"failure",false,failure.toString());}catch(Exception evidence){failure.addSuppressed(evidence);}org.slf4j.LoggerFactory.getLogger(CraftFoodRecoveryScenario.class).error("Craft/food recovery failed",failure);server.halt(false);}
    }
    private static void initialize(MinecraftServer server,Run run)throws Exception{
        var registry=run.runtime.core().registry();var level=server.overworld();
        if(run.manifest==null){
            for(int x=5;x<34;x++)for(int z=5;z<18;z++){level.setBlock(new BlockPos(x,63,z),Blocks.STONE.defaultBlockState(),3);for(int y=64;y<=66;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);}
            var manifest=new CompoundTag();manifest.putString("scenario",scenario());manifest.putUUID("owner",OWNER);run.manifest=manifest;
            manifest.putUUID("colony",uuid(command(server,run,"colonyloom colony create RecoveryFixture 0 64 0 47 64 31"),"colony"));
            level.setBlock(SOURCE,Blocks.BARREL.defaultBlockState(),3);command(server,run,"colonyloom storage register "+colony(run)+" "+coordinates(SOURCE)+" warehouse");
            ((Container)level.getBlockEntity(SOURCE)).setItem(0,new ItemStack(food()?Items.BREAD:Items.OAK_PLANKS,food()?2:6));
            if(!food()){
                for(var pos:List.of(BARREL,BUFFER,RETURN))level.setBlock(pos,Blocks.BARREL.defaultBlockState(),3);level.setBlock(TABLE,Blocks.CRAFTING_TABLE.defaultBlockState(),3);
                for(var entry:Map.of(BARREL,"workshop",BUFFER,"construction",RETURN,"return").entrySet())command(server,run,"colonyloom storage register "+colony(run)+" "+coordinates(entry.getKey())+" "+entry.getValue());
                command(server,run,"colonyloom building register "+colony(run)+" "+coordinates(TABLE)+" "+coordinates(BARREL));
            }
            var id=uuid(command(server,run,"colonyloom citizen create "+colony(run)+" 12 64 15"),"citizen");manifest.putUUID("citizen",id);
            command(server,run,"colonyloom citizen assign "+id+" colonyloom:"+(food()?"builder":"carpenter"));
            var record=registry.citizen(id);manifest.putUUID("entity",record.entityId());manifest.putLong("epoch",record.bindingEpoch());
            if(food()){
                admission(run,id,CitizenRecord.Admission.INACTIVE);registry.updateCitizen(registry.citizen(id).withFood(6));
            }else{
                command(server,run,"colonyloom citizen workplace "+id+" "+registry.storage().workshops().getFirst().id());
                for(var role:List.of("courier","builder")){var worker=uuid(command(server,run,"colonyloom citizen create "+colony(run)+" "+(role.equals("courier")?17:22)+" 64 15"),"citizen");manifest.putUUID(role,worker);command(server,run,"colonyloom citizen assign "+worker+" colonyloom:"+role);}
                manifest.putUUID("work",uuid(command(server,run,"colonyloom build "+colony(run)+" colonyloom:test_four_stairs "+coordinates(ORIGIN)+" 0"),"work"));
            }
            return;
        }
        if(entity(server,run)==null)return;
        if(!food()){
            var order=registry.supply().productionOrders().stream().filter(p -> p.recipe().id().equals("colonyloom:oak_stairs")&&p.batchStarted()&&p.remainingActiveTicks()>0).findFirst().orElse(null);
            if(order==null)return;
            run.manifest.putUUID("production",order.id());run.manifest.putLong("remaining",order.remainingActiveTicks());run.manifest.putLong("active",registry.citizen(citizen(run)).activeTimeTicks());
            run.manifest.putUUID("productionWork",order.workId());
            command(server,run,"colonyloom citizen assign "+run.manifest.getUUID("builder")+" colonyloom:carpenter");
            command(server,run,"colonyloom citizen assign "+run.manifest.getUUID("courier")+" colonyloom:carpenter");
            require(server,run,inventory((Container)server.overworld().getBlockEntity(BARREL),Items.OAK_PLANKS)==6&&count(server,run,Items.OAK_STAIRS)==0,"initial_active_batch","real kit6 at workshop, remaining="+order.remainingActiveTicks());
        }else require(server,run,count(server,run,Items.BREAD)==2&&registry.citizen(citizen(run)).food()==6,"initial_food_precondition","inactive resident, native warehouse bread2, food6; no expense");
        rememberEntityChunk(server,run);writeManifest(server,run);finish(server,run,"initialize_complete","native setup and logical identities requested ordinary clean checkpoint");
    }
    private static void exercise(MinecraftServer server,Run run)throws Exception{
        var registry=run.runtime.core().registry();
        if(!run.activated){
            if(food())admission(run,citizen(run),CitizenRecord.Admission.ACTIVE);
            else{
                var order=registry.supply().production(run.manifest.getUUID("production"));
                require(server,run,order.workId().equals(run.manifest.getUUID("productionWork"))
                        &&(order.batchStarted()&&order.completedBatches()==0||clean()&&order.completedBatches()==1),
                        "active_checkpoint_continues","original work; saved="+run.manifest.getLong("remaining")+" current="+order.remainingActiveTicks()+" completed="+order.completedBatches());
            }
            run.activated=true;
        }
        if(!clean())return;
        if(food()){if(registry.citizen(citizen(run)).food()!=11)return;}
        else if(registry.supply().production(run.manifest.getUUID("production")).completedBatches()!=1)return;
        if(run.hold<0){assertClean(server,run);run.hold=run.runtime.core().serverTick();return;}
        assertClean(server,run);if(run.runtime.core().serverTick()-run.hold<100)return;
        rememberEntityChunk(server,run);writeManifest(server,run);finish(server,run,"exercise_complete","one complete native expense and 100 ticks without duplicate; ordinary halt");
    }
    private static void assertClean(MinecraftServer server,Run run)throws Exception{
        var registry=run.runtime.core().registry();boolean passed=!registry.colony(colony(run)).recoveryBlocked();
        if(food())passed&=count(server,run,Items.BREAD)==1&&registry.citizen(citizen(run)).food()==11;
        else passed&=count(server,run,Items.OAK_PLANKS)==0&&count(server,run,Items.OAK_STAIRS)==4&&inventory((Container)server.overworld().getBlockEntity(BARREL),Items.OAK_STAIRS)==4&&worldStairs(server)==0&&registry.supply().production(run.manifest.getUUID("production")).completedBatches()==1;
        if(!passed)require(server,run,false,"clean_exact_expense","clean outcome changed");
    }
    private static String requestedPoint(){return scenario().substring(scenario().indexOf('_')+1).toUpperCase(Locale.ROOT);}
    private static void fault(MinecraftServer server,Run run,String point,ActionContext context){
        try{
            guard(server);preload(server,run);if(!phase().equals("exercise")||clean()||!context.colonyId().equals(colony(run))||!context.citizenId().equals(citizen(run)))return;
            if(point.equals("BEFORE_EFFECT")){
                var effect=currentEffect(run);run.manifest.putUUID("operation",effect.operationId());run.manifest.putUUID("effectWork",effect.workId());
                // Independently force the physical baseline first, then the already prepared DTO.
                rememberEntityChunk(server,run);RecoveryNativeState.saveBlocks(server);RecoveryNativeState.saveEntities(server);
                run.runtime.persistence().persistSnapshot();
                require(server,run,savedEffect(server,effect.operationId()).getString("state").equals("PREPARED"),"durable_prepared_effect",savedEffect(server,effect.operationId()).toString());
                require(server,run,!read(world(server).resolve("data/colonyloom-session.nbt")).getBoolean("clean"),"dirty_marker_before_effect","dirty persisted before native expense");
                require(server,run,food()?count(server,run,Items.BREAD)==2&&registryFood(run)==6:count(server,run,Items.OAK_PLANKS)==6&&count(server,run,Items.OAK_STAIRS)==0,"before_effect_exact_native","no source expense or output yet");
            }
            if(!point.equals(requestedPoint()))return;
            if(!point.equals("BEFORE_EFFECT")){
                if(food())RecoveryNativeState.saveEntities(server);else RecoveryNativeState.saveBlocks(server);
                require(server,run,savedEffect(server,run.manifest.getUUID("operation")).getString("state").equals("PREPARED"),"native_saved_logical_prepared","independent physical save did not save logical completion");
            }
            if(point.equals("AFTER_FACT_BEFORE_NOTIFY")){
                run.runtime.persistence().persistSnapshot();
                require(server,run,savedEffect(server,run.manifest.getUUID("operation")).getString("state").equals("OBSERVED"),"durable_observed_before_notify","native fact durable; ordinary consumer publication not executed");
            }
            var physical=durable(server,run);run.manifest.put("crashPhysical",physical);
            run.manifest.put("crashLogicalEffect",savedEffect(server,run.manifest.getUUID("operation")).copy());
            run.manifest.putInt("crashFood",registryFood(run));
            require(server,run,savedCitizen(server,citizen(run)).getCompound("needs").getInt("food")==registryFood(run),"durable_food_before_publication","food="+registryFood(run)+" publication not executed; native expense independently durable");
            require(server,run,physical.equals(physical(server,run)),"independent_native_flush_readback",physical.toString());
            require(server,run,food()?physical.getInt("totalBread")== (point.equals("BEFORE_EFFECT")?2:1)&&registryFood(run)==6
                    :physical.getInt("totalPlanks")== (point.equals("BEFORE_EFFECT")?6:0)&&physical.getInt("totalStairs")== (point.equals("AFTER_DESTINATION_CHANGE")||point.equals("AFTER_FACT_BEFORE_NOTIFY")?4:0)&&physical.getInt("worldStairs")==0,"fault_exact_expense",point+" physical="+physical);
            writeManifest(server,run);require(server,run,!read(world(server).resolve("data/colonyloom-session.nbt")).getBoolean("clean"),"dirty_marker_before_halt","independent durable sides; exit97");
            fact(server,run,"expected_fault",true,"point="+point+" exit=97");fact(server,run,"exercise_complete",true,"named native boundary persisted and flushed independently; expected halt97");
            guard(server);if(!Boolean.getBoolean("colonyloom.testFaults")||!phase().equals("exercise")||!point.equals(requestedPoint()))throw new IllegalStateException("Fault gate changed");Runtime.getRuntime().halt(97);
        }catch(Exception failure){throw new IllegalStateException("Craft/food fault fixture failed",failure);}
    }
    private static EffectRecord currentEffect(Run run){return run.runtime.core().registry().effects().snapshots().stream().filter(e -> e.colonyId().equals(colony(run))&&e.citizenId().equals(citizen(run))&&(food()?e.food()!=null:e.craft()!=null)&&e.state()==EffectRecord.State.PREPARED).reduce((left,right) -> right).orElseThrow();}
    private static void verify(MinecraftServer server,Run run)throws Exception{
        if(clean()){
            assertClean(server,run);if(run.hold<0){require(server,run,true,"clean_restart_exact_counts","same UUID/epoch; ordinary progress resumed without accept-world");run.hold=run.runtime.core().serverTick();return;}
            if(run.runtime.core().serverTick()-run.hold<100)return;
            require(server,run,true,"clean_restart_no_replay_100_ticks","one batch or one bread, no duplicated input expenditure");finish(server,run,"verify_complete","clean checkpoint resumed exact outcome without operator acceptance");return;
        }
        assertBlocked(server,run);
        if(!run.baselineChecked){
            require(server,run,durable(server,run).equals(run.manifest.getCompound("crashPhysical")),"original_durable_native_restart",durable(server,run).toString());
            require(server,run,savedEffect(server,run.manifest.getUUID("operation")).equals(run.manifest.getCompound("crashLogicalEffect")),"original_saved_logical_evidence",run.manifest.getCompound("crashLogicalEffect").toString());
            require(server,run,true,"restart_public_status",command(server,run,"colonyloom status "+colony(run)));
            require(server,run,true,"restart_public_inspection",command(server,run,"colonyloom recovery inspect "+colony(run)));
            run.baselineChecked=true;run.hold=run.runtime.core().serverTick();
        }
        if(run.runtime.core().serverTick()-run.hold<100)return;
        require(server,run,true,"blocked_no_auto_effect_100_ticks","same identity/binding and all original native counts; AMBIGUOUS remains blocked");finish(server,run,"verify_complete","ordinary halt while recovery remains blocked; no acceptance in verify");
    }
    private static void assertBlocked(MinecraftServer server,Run run)throws Exception{
        var registry=run.runtime.core().registry();var effect=registry.effects().get(run.manifest.getUUID("operation"));
        boolean passed=registry.colony(colony(run)).recoveryBlocked()&&effect!=null
                &&effect.state().name().equals(run.manifest.getCompound("crashLogicalEffect").getString("state"))
                &&registry.workBoard().work(effect.workId()).waitingReason()==WorkOrder.Reason.RECOVERY_AMBIGUOUS
                &&effect.workId().equals(run.manifest.getUUID("effectWork"))
                &&encodedEffect(server,run,effect.operationId()).equals(run.manifest.getCompound("crashLogicalEffect"))
                &&physical(server,run).equals(run.manifest.getCompound("crashPhysical"))&&registryFood(run)==run.manifest.getInt("crashFood");
        if(!passed)require(server,run,false,"restart_ambiguous_exact_counts","effect="+effect+" blocked="+registry.colony(colony(run)).recoveryBlocked());
        if(!run.baselineChecked)require(server,run,true,"restart_ambiguous_exact_counts","retained operation="+effect.operationId()+" originalState="+effect.state()+" work=RECOVERY_AMBIGUOUS recoveryBlocked=true");
    }
    private static void accept(MinecraftServer server,Run run)throws Exception{
        if(clean())throw new IllegalStateException("Clean scenarios never require accept-world");var registry=run.runtime.core().registry();
        if(run.stage==0){
            assertBlocked(server,run);
            if(food())admission(run,citizen(run),CitizenRecord.Admission.INACTIVE);
            require(server,run,true,"accept_public_inspection",command(server,run,"colonyloom recovery inspect "+colony(run)));
            run.acceptedWitness=registry.effects().get(run.manifest.getUUID("operation")).accepted();
            var checkpoint=registry.colony(colony(run)).recoveryCheckpointId();
            require(server,run,true,"accept_public_command",command(server,run,"colonyloom recovery accept-world "+colony(run)+" "+checkpoint));
            requireAcceptedWitness(server,run,registry.effects().get(run.manifest.getUUID("operation")));run.hold=run.runtime.core().serverTick();run.stage=1;
        }
        var old=registry.effects().get(run.manifest.getUUID("operation"));
        requireAcceptedWitness(server,run,old);
        if(run.stage==1){
            boolean stable=food()?count(server,run,Items.BREAD)==run.manifest.getCompound("crashPhysical").getInt("totalBread")
                    :physical(server,run).equals(run.manifest.getCompound("crashPhysical"));
            if(!stable||registryFood(run)!=run.manifest.getInt("crashFood"))require(server,run,false,"accept_physical_stability","operator acceptance changed total property or published nutrition");
            if(run.runtime.core().serverTick()-run.hold<100)return;
            require(server,run,true,"accept_no_replay_100_ticks",food()?"real external consume veto; original food work CANCELLED, accepted witness unchanged; new hunger may haul surviving bread":"original production cancelled; native inputs/output unchanged for100 ticks");
            if(!food()){finish(server,run,"accept_complete","public accept-world retained exact native output and no old batch replay");return;}
            if(registry.citizen(citizen(run)).admission()!=CitizenRecord.Admission.ACTIVE)return;
            run.stage=2;run.hold=-1;return;
        }
        if(registryFood(run)!=11)return;
        require(server,run,count(server,run,Items.BREAD)==run.manifest.getCompound("crashPhysical").getInt("totalBread")-1,"fresh_hunger_exact_expense","veto released; resident food6->11 spent one surviving bread through separate new goal");
        var fresh=registry.effects().snapshots().stream().filter(e -> e.food()!=null&&e.citizenId().equals(citizen(run))&&!e.operationId().equals(run.manifest.getUUID("operation"))).findFirst().orElseThrow();
        require(server,run,!fresh.workId().equals(run.manifest.getUUID("effectWork"))&&fresh.state()==EffectRecord.State.OBSERVED,"fresh_hunger_distinct_operation","new operation="+fresh.operationId()+" new work="+fresh.workId()+" old work remains cancelled");
        if(run.hold<0){run.hold=run.runtime.core().serverTick();return;}
        if(run.runtime.core().serverTick()-run.hold<100)return;
        finish(server,run,"accept_complete","consume-veto no-replay barrier100 ticks followed by veto release and distinct fresh hunger goal; old work never replayed");
    }
    private static void requireAcceptedWitness(MinecraftServer server,Run run,EffectRecord old)throws Exception{
        var registry=run.runtime.core().registry();
        if(registry.colony(colony(run)).recoveryBlocked()||old==null||old.state()!=EffectRecord.State.ACCEPTED||registry.workBoard().work(run.manifest.getUUID("effectWork")).state()!=WorkOrder.State.CANCELLED)
            require(server,run,false,"accepted_original_witness","old="+old);
        if(!old.equals(run.acceptedWitness))require(server,run,false,"accepted_original_payload_unchanged","accepted operation changed after operator publication");
    }
    private static CompoundTag encodedEffect(MinecraftServer server,Run run,UUID operation) {
        var encoded=io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonySavedData.empty(run.runtime.core().registry().snapshot()).save(new CompoundTag(),server.registryAccess());
        for(Tag value:encoded.getList("evidence",Tag.TAG_COMPOUND)) {var tag=(CompoundTag)value;if(tag.hasUUID("operationId")&&tag.getUUID("operationId").equals(operation))return tag;}
        throw new IllegalStateException("Original runtime witness missing "+operation);
    }
    private static int registryFood(Run run){return run.runtime.core().registry().citizen(citizen(run)).food();}
    private static int inventory(Container container,Item item){int count=0;for(int i=0;i<container.getContainerSize();i++)if(container.getItem(i).is(item))count+=container.getItem(i).getCount();return count;}
    private static List<BlockPos> containers(){return food()?List.of(SOURCE):List.of(SOURCE,BARREL,BUFFER,RETURN);}
    private static int count(MinecraftServer server,Run run,Item item){int count=0;for(var pos:containers())count+=inventory((Container)server.overworld().getBlockEntity(pos),item);for(var citizen:run.runtime.core().registry().citizens(colony(run))){var value=server.overworld().getEntity(citizen.entityId());if(!(value instanceof CitizenEntity npc))throw new IllegalStateException("Native inventory UNKNOWN; retain identity, no replacement");count+=inventory(npc.inventory(),item);}return count;}
    private static int worldStairs(MinecraftServer server){int count=0;for(int i=0;i<16;i++)if(server.overworld().getBlockState(ORIGIN.east(i)).is(Blocks.OAK_STAIRS))count++;return count;}
    private static CompoundTag physical(MinecraftServer server,Run run){
        var tag=new CompoundTag();tag.putInt("sourceWarehouse",inventory((Container)server.overworld().getBlockEntity(SOURCE),food()?Items.BREAD:Items.OAK_PLANKS));
        if(food()){tag.putInt("residentBread",inventory(Objects.requireNonNull(entity(server,run)).inventory(),Items.BREAD));tag.putInt("totalBread",count(server,run,Items.BREAD));}
        else{tag.putInt("sourceWorkshop",inventory((Container)server.overworld().getBlockEntity(BARREL),Items.OAK_PLANKS));tag.putInt("destinationWorkshop",inventory((Container)server.overworld().getBlockEntity(BARREL),Items.OAK_STAIRS));tag.putInt("totalPlanks",count(server,run,Items.OAK_PLANKS));tag.putInt("totalStairs",count(server,run,Items.OAK_STAIRS));tag.putInt("worldStairs",worldStairs(server));}
        return tag;
    }
    private static CompoundTag durable(MinecraftServer server,Run run)throws Exception{
        var tag=new CompoundTag();tag.putInt("sourceWarehouse",RecoveryNativeState.inventoryCount(server,RecoveryNativeState.blockEntity(server,SOURCE),food()?Items.BREAD:Items.OAK_PLANKS));
        if(food()){
            var saved=RecoveryNativeState.entity(server,run.manifest.getUUID("entity"),new ChunkPos(run.manifest.getInt("entityChunkX"),run.manifest.getInt("entityChunkZ")));
            requireSavedIdentity(saved,run);int bread=RecoveryNativeState.inventoryCount(server,saved.getCompound("Colonyloom"),Items.BREAD);tag.putInt("residentBread",bread);tag.putInt("totalBread",bread+tag.getInt("sourceWarehouse"));
        }else{
            int planks=0,stairs=0;for(var pos:containers()){var saved=RecoveryNativeState.blockEntity(server,pos);planks+=RecoveryNativeState.inventoryCount(server,saved,Items.OAK_PLANKS);stairs+=RecoveryNativeState.inventoryCount(server,saved,Items.OAK_STAIRS);}
            var workshop=RecoveryNativeState.blockEntity(server,BARREL);tag.putInt("sourceWorkshop",RecoveryNativeState.inventoryCount(server,workshop,Items.OAK_PLANKS));tag.putInt("destinationWorkshop",RecoveryNativeState.inventoryCount(server,workshop,Items.OAK_STAIRS));tag.putInt("totalPlanks",planks);tag.putInt("totalStairs",stairs);
            int blocks=0;for(int i=0;i<4;i++)if(RecoveryNativeState.blockName(server,ORIGIN.east(i)).equals("minecraft:oak_stairs"))blocks++;
            tag.putInt("worldStairs",blocks);
        }
        return tag;
    }
    private static void requireSavedIdentity(CompoundTag saved,Run run){if(!saved.getUUID("UUID").equals(run.manifest.getUUID("entity"))||!saved.getCompound("Colonyloom").getUUID("citizenId").equals(citizen(run))||saved.getCompound("Colonyloom").getLong("bindingEpoch")!=run.manifest.getLong("epoch"))throw new IllegalStateException("Durable native identity changed");}
    private static void rememberEntityChunk(MinecraftServer server,Run run){var npc=Objects.requireNonNull(entity(server,run));run.manifest.putInt("entityChunkX",npc.chunkPosition().x);run.manifest.putInt("entityChunkZ",npc.chunkPosition().z);}
    private static CompoundTag savedEffect(MinecraftServer server,UUID operation)throws Exception{for(Tag value:read(world(server).resolve("data/colonyloom.dat")).getCompound("data").getList("evidence",Tag.TAG_COMPOUND)){var tag=(CompoundTag)value;if(tag.hasUUID("operationId")&&tag.getUUID("operationId").equals(operation))return tag;}throw new IllegalStateException("Durable native effect missing "+operation);}
    private static CompoundTag savedCitizen(MinecraftServer server,UUID citizen)throws Exception{for(Tag value:read(world(server).resolve("data/colonyloom.dat")).getCompound("data").getList("citizens",Tag.TAG_COMPOUND)){var tag=(CompoundTag)value;if(tag.hasUUID("citizenId")&&tag.getUUID("citizenId").equals(citizen))return tag;}throw new IllegalStateException("Durable citizen missing "+citizen);}
    private void stopped(ServerStoppedEvent event){
        var server=event.getServer();var run=runs.remove(server);if(!SCENARIOS.contains(scenario())||run==null)return;
        try{
            guard(server);var marker=read(world(server).resolve("data/colonyloom-session.nbt"));var dto=read(world(server).resolve("data/colonyloom.dat")).getCompound("data");
            require(server,run,marker.getBoolean("clean")&&marker.getUUID("checkpointId").equals(dto.getUUID("checkpointId")),"clean_checkpoint",dto.getUUID("checkpointId").toString());
            if(phase().equals("initialize")&&!food()) {
                CompoundTag original=null;
                for(Tag value:dto.getList("productionOrders",Tag.TAG_COMPOUND)) {var tag=(CompoundTag)value;if(tag.getUUID("id").equals(run.manifest.getUUID("production")))original=tag;}
                require(server,run,original!=null&&original.getBoolean("batchStarted")&&original.getLong("completedBatches")==0
                        &&original.getLong("remainingActiveTicks")==run.manifest.getLong("remaining")
                        &&original.getUUID("workId").equals(run.manifest.getUUID("productionWork"))&&original.getUUID("citizenId").equals(citizen(run))
                        &&savedCitizen(server,citizen(run)).getLong("activeTimeTicks")==run.manifest.getLong("active"),
                        "durable_exact_active_residual","original active batch checkpoint remaining="+run.manifest.getLong("remaining"));
            }
            if(run.manifest!=null){require(server,run,durable(server,run).equals(run.manifest.getCompound("finalPhysical")),"clean_native_durable_counts","independent chunk/entity readback matches final real native property");
                var logical=savedCitizen(server,citizen(run));require(server,run,logical.getUUID("entityId").equals(run.manifest.getUUID("entity"))&&logical.getLong("bindingEpoch")==run.manifest.getLong("epoch")&&logical.getCompound("needs").getInt("food")==run.manifest.getInt("finalFood"),"clean_logical_identity_food","same durable native identity and exact final food="+logical.getCompound("needs").getInt("food"));
                var saved=RecoveryNativeState.entity(server,run.manifest.getUUID("entity"),new ChunkPos(run.manifest.getInt("entityChunkX"),run.manifest.getInt("entityChunkZ")));requireSavedIdentity(saved,run);require(server,run,true,"clean_native_identity","same persisted entity UUID, citizen identity and epoch");}
        }catch(Exception failure){try{fact(server,run,"clean_checkpoint",false,failure.toString());}catch(Exception evidence){failure.addSuppressed(evidence);}org.slf4j.LoggerFactory.getLogger(CraftFoodRecoveryScenario.class).error("Recovery clean checkpoint proof failed",failure);}
    }
    private static void finish(MinecraftServer server,Run run,String check,String detail)throws Exception{rememberEntityChunk(server,run);run.manifest.put("finalPhysical",physical(server,run));run.manifest.putInt("finalFood",registryFood(run));writeManifest(server,run);require(server,run,true,check,detail);run.done=true;server.halt(false);}
    private static CompoundTag read(Path path)throws Exception{return NbtIo.readCompressed(path,NbtAccounter.create(96L*1024*1024));}
    private static void writeManifest(MinecraftServer server,Run run)throws Exception{var path=world(server).resolve(MANIFEST);NbtIo.writeCompressed(run.manifest,path);try(var channel=FileChannel.open(path,StandardOpenOption.WRITE)){channel.force(true);}}
    private static void require(MinecraftServer server,Run run,boolean passed,String check,String detail)throws Exception{fact(server,run,check,passed,detail);if(!passed)throw new IllegalStateException(check+": "+detail);}
    private static void fact(MinecraftServer server,Run run,String check,boolean passed,String detail)throws Exception{
        var json=new JsonObject();json.addProperty("scenario",scenario());json.addProperty("phase",phase());json.addProperty("check",check);json.addProperty("passed",passed);json.addProperty("detail",detail);
        json.addProperty("initialSource",food()?2:6);json.addProperty("initialDestination",0);if(food())json.addProperty("initialFood",6);
        if(run.manifest!=null&&run.manifest.hasUUID("citizen")){
            var nativeState=run.done?run.manifest.getCompound("finalPhysical"):entity(server,run)==null?null:physical(server,run);
            if(nativeState!=null)for(String key:nativeState.getAllKeys())json.addProperty(key,nativeState.getInt(key));
            if(nativeState!=null){json.addProperty("currentSource",food()?nativeState.getInt("sourceWarehouse")+nativeState.getInt("residentBread"):nativeState.getInt("totalPlanks"));json.addProperty("currentDestination",food()?registryFood(run):nativeState.getInt("totalStairs"));}
            if(run.manifest.contains("crashPhysical",Tag.TAG_COMPOUND)){var durable=run.manifest.getCompound("crashPhysical");for(String key:durable.getAllKeys())json.addProperty("durable_"+key,durable.getInt(key));json.addProperty("durable_food",run.manifest.getInt("crashFood"));}
            json.addProperty("food",registryFood(run));json.addProperty("entity",run.manifest.getUUID("entity").toString());json.addProperty("citizen",citizen(run).toString());json.addProperty("bindingEpoch",run.manifest.getLong("epoch"));
        }
        var path=world(server).resolve("colonyloom-recovery-observations.jsonl");Files.writeString(path,json+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);try(var channel=FileChannel.open(path,StandardOpenOption.WRITE)){channel.force(true);}
    }
    private static String coordinates(BlockPos pos){return pos.getX()+" "+pos.getY()+" "+pos.getZ();}
    private static UUID uuid(String output,String key){var match=Pattern.compile("(?:^|[\\s\\[,])"+Pattern.quote(key)+"=([0-9a-fA-F-]{36})(?=[\\s\\],]|$)").matcher(output);if(!match.find())throw new IllegalStateException("Missing "+key+" in "+output);return UUID.fromString(match.group(1));}
    private static String command(MinecraftServer server,Run run,String text)throws Exception{var capture=new Capture();int code=server.getCommands().getDispatcher().execute(text,run.actor.createCommandSourceStack().withPermission(2).withSource(capture));var result=String.join("\n",capture.messages);if(code!=1)throw new IllegalStateException("Public recovery command refused "+text+": "+result);return result;}
    private static final class Capture implements CommandSource{final List<String> messages=new ArrayList<>();public void sendSystemMessage(Component message){messages.add(message.getString());}public boolean acceptsSuccess(){return true;}public boolean acceptsFailure(){return true;}public boolean shouldInformAdmins(){return false;}}
}
