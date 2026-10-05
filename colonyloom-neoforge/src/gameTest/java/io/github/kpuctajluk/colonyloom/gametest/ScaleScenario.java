package io.github.kpuctajluk.colonyloom.gametest;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.action.EffectRecord;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime;
import io.github.kpuctajluk.colonyloom.neoforge.ConstructionExecutorEvent;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol;
import io.github.kpuctajluk.colonyloom.minecraft.network.ManagementPayloads;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Disposable physical economy; no economic transition or executor is replaced by the fixture. */
final class ScaleScenario {
    private static final long SECOND = 1_000_000_000L;
    private static final UUID OWNER = offline("ScaleOwner");
    private static final String BLUEPRINT = "colonyloom:scale_plot", MANIFEST = "colonyloom-scale-fixture.nbt";
    private static final Set<String> SCENARIOS = Set.of("economy", "parallel", "critical", "mutations", "restart", "overload", "ui");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Map<MinecraftServer, Run> runs = new IdentityHashMap<>();
    private static final class Plot {
        final int colony, index; final BlockPos origin, source, returns, barrel, table, buffer;
        UUID work, workshop; long completed;
        Plot(int colony, int index) {
            this.colony = colony; this.index = index;
            origin = new BlockPos(colony * 512 + (index % 4) * 16, 64, (index / 4) * 17);
            int x = colony * 512 + 2 + index * 6;
            source = new BlockPos(x,64,60); returns = source.east(); barrel = new BlockPos(x,64,54);
            table = barrel.east(); buffer = new BlockPos(colony * 512 + 2 + index * 6,64,51);
        }
    }
    private record ViewWire(UUID subscription,long tick,boolean snapshot,String closed) {}
    private static final class Run {
        MinecraftServerRuntime runtime; ServerPlayer actor; SimulationLimits frozen;
        final UUID[] colonies = new UUID[3]; final List<Plot> plots = new ArrayList<>();
        final List<UUID> citizens = new ArrayList<>(), influx = new ArrayList<>();
        final Map<UUID,UUID> entities = new LinkedHashMap<>(); final Map<UUID,Long> epochs = new LinkedHashMap<>();
        final Map<UUID,Vec3> positions = new HashMap<>(); final Map<UUID,Double> distances = new LinkedHashMap<>();
        final Map<UUID,CitizenEntity> nativeResidents = new HashMap<>();
        final Map<UUID,Long> residentPauseStarted = new LinkedHashMap<>(), residentPausedClock = new HashMap<>();
        final Map<UUID,Long> residentPausedNativeTicks = new LinkedHashMap<>(), residentMaxPauseTicks = new LinkedHashMap<>();
        final Map<UUID,Long> residentPausedOwnTicks = new LinkedHashMap<>();
        final Map<UUID,JsonObject> residentBlockers = new LinkedHashMap<>();
        int actualEntityTickingResidents, minimumMeasuredEntityTickingResidents = 300;
        final Map<UUID,Long> participation = new LinkedHashMap<>(), criticalStarted = new LinkedHashMap<>();
        final Set<UUID> seenEffects = new HashSet<>(), criticalCompleted = new HashSet<>();
        final Map<String,Long> inputs = new TreeMap<>(), removals = new TreeMap<>(), consumed = new TreeMap<>(), produced = new TreeMap<>();
        final Map<String,Long> actionCounts = new TreeMap<>(), roleTicks = new TreeMap<>();
        final JsonObject report = new JsonObject(); final JsonArray checks = new JsonArray(), heap = new JsonArray(), initial = new JsonArray();
        CompletableFuture<Void> reload; CompoundTag manifest;
        long startup = System.nanoTime(), warmupStart, measureStart, measureEnd, drainStart, ticks, measuredTicks;
        long lastGc, gcRequestedAt, gcBefore, readinessTick = -1, maxStockAge, maxCriticalTicks, noNotifyAt = -1;
        long maxWakeTicks, mutationAt = -1, lastServiceSample, heapHighWater, frozenAt;
        UUID noNotifyDemand, deadCitizen; int setup, provision, criticalWaves, mutationPhase, shrinkPhase, reducedChunkCap;
        int foundedColonies;
        long nextFoundingTick;
        long shrinkAt; boolean initialized, measuring, draining, done, published, saturated, graphSaturated, cancelled, death, notified;
        final List<UUID> remoteWorks = new ArrayList<>(); final Map<UUID,Integer> nativeDrops = new LinkedHashMap<>();
        final Map<UUID,String> nativeDropItems = new LinkedHashMap<>();
        final Map<UUID,Long> restartServices = new HashMap<>(); final Map<UUID,Integer> restartConstructionConsumed = new HashMap<>();
        final Set<UUID> restartProgressedColonies=new HashSet<>();
        volatile long wireTick;
        volatile Throwable wireFailure;
        final ConcurrentLinkedQueue<ViewWire> viewWire=new ConcurrentLinkedQueue<>();
        final Map<UUID,Long> unacknowledgedSnapshotTicks=new HashMap<>();
        int observedAckTimeouts;
    }
    ScaleScenario() {
        NeoForge.EVENT_BUS.addListener(this::configure);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::stopped);
    }
    private static UUID offline(String name) { return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)); }
    private static String scenario() { return System.getProperty("colonyloom.test.scaleScenario", ""); }
    private static String phase() { return System.getProperty("colonyloom.test.scalePhase", "exercise"); }
    private static boolean diagnostic() { return Boolean.getBoolean("colonyloom.test.scaleDiagnostic"); }
    private static Path root() { return Path.of(System.getProperty("colonyloom.test.scaleRoot", "")); }
    private static Path world(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT); }
    private static long duration(String name, long full) {
        long seconds = Long.getLong("colonyloom.test.scale" + name + "Seconds", full);
        state(seconds > 0 && (diagnostic() || seconds == full), "Shortened durations require explicit non-acceptance diagnostic");
        return Math.multiplyExact(seconds, SECOND);
    }
    private void configure(ConstructionExecutorEvent event) {
        if(scenario().isEmpty())return;
        Run run=runs.computeIfAbsent(event.server(),ignored -> new Run());run.runtime=event.runtime();
        event.recipeObserver((point,context) -> {if(point==io.github.kpuctajluk.colonyloom.minecraft.production.RecipeExecutor.FaultPoint.AFTER_FACT_BEFORE_NOTIFY)harvestUnchecked(run);});
        event.foodObserver((point,context) -> {if(point==io.github.kpuctajluk.colonyloom.minecraft.needs.FoodConsumptionExecutor.FaultPoint.AFTER_FACT_BEFORE_NOTIFY)harvestUnchecked(run);});
        event.observer((point,context) -> {if(point==io.github.kpuctajluk.colonyloom.minecraft.construction.BlockPlacementExecutor.FaultPoint.AFTER_FACT_BEFORE_NOTIFY)harvestUnchecked(run);});
        event.transferObserver((point,context) -> {if(point==io.github.kpuctajluk.colonyloom.minecraft.storage.StorageTransferExecutor.FaultPoint.AFTER_FACT_BEFORE_NOTIFY)harvestUnchecked(run);});
    }
    private static void harvestUnchecked(Run run){try{harvestFacts(run);}catch(Exception failure){throw new IllegalStateException("Native scale fact collection failed",failure);}}
    private void tick(ServerTickEvent.Post event) {
        if (scenario().isEmpty()) return;
        MinecraftServer server = event.getServer(); Run run = runs.computeIfAbsent(server, ignored -> new Run());
        if (run.done) return;
        try {
            guard(server); run.ticks++;
            if(scenario().equals("ui")) observeViewTimeouts(run);
            if (run.runtime == null) { state(System.nanoTime()-run.startup < 180*SECOND, "Runtime setup timeout"); return; }
            run.wireTick=run.runtime.serverTick();
            if (!run.initialized) {
                initialize(server,run); run.initialized=true;
                if(scenario().equals("ui")) Files.writeString(root().resolve("server-listening"),"dedicated tick after startup\n");
            }
            if (phase().equals("verify")) { verifyRestart(server,run); return; }
            if (run.setup < 3) { setup(server,run); state(System.nanoTime()-run.startup < 300*SECOND,"Native fixture/datapack setup timeout"); return; }
            if (run.provision < 3) { provision(server,run,run.provision++); return; }
            observe(server,run);
            if (run.warmupStart == 0) {
                if (!ready(server,run)) { state(System.nanoTime()-run.startup < 600*SECOND,"300 original residents never genuinely ready"); return; }
                if(run.plots.stream().allMatch(p -> p.work==null))for(Plot plot:run.plots)issue(server,run,plot);
                if (scenario().equals("ui")) {
                    publishUi(server,run);
                    if (!Files.isRegularFile(root().resolve("owner-ready")) || !Files.isRegularFile(root().resolve("viewer-ready"))) {
                        state(System.nanoTime()-run.startup < 900*SECOND,"Real UI clients did not publish readiness"); return;
                    }
                }
                run.warmupStart=System.nanoTime(); run.frozenAt=run.runtime.serverTick();
                // Economic roots already exist before UI readiness is published.
            }
            checkFrozen(run);
            economy(server,run,!run.draining);
            if (!run.measuring) {
                if (System.nanoTime()-run.warmupStart < duration("Warmup",300)) return;
                run.runtime.metrics().reset(); run.measuring=true; run.measureStart=System.nanoTime(); run.lastGc=run.measureStart;
                if(scenario().equals("ui")) Files.writeString(root().resolve("measurement-started"),"actual measured runtime tick="+run.runtime.serverTick()+"\n");
                run.gcBefore=fullGcCount();run.gcRequestedAt=run.measureStart;System.gc();
                run.report.addProperty("warmupElapsedNanos",run.measureStart-run.warmupStart);
                run.report.addProperty("warmupTicks",run.runtime.serverTick()-run.frozenAt);
                run.report.add("measurementActionBaseline",json(run.actionCounts));
                run.report.add("schedulerAtMeasurementStart",json(run.runtime.core().scheduler().diagnostics(null)));
                run.distances.replaceAll((id,value)->0.0); return;
            }
            if (!run.draining) {
                run.measuredTicks++;
                exercise(server,run); monitor(server,run); sampleHeap(run);
                long full = scenario().equals("overload") ? 3600 : 1800;
                if (System.nanoTime()-run.measureStart < duration("Measure",full)) return;
                if(run.gcRequestedAt!=0)return;
                if(run.heap.isEmpty() || System.nanoTime()-run.heap.get(run.heap.size()-1).getAsJsonObject().get("elapsedNanos").getAsLong()-run.measureStart>SECOND) {
                    run.gcBefore=fullGcCount();run.gcRequestedAt=System.nanoTime();System.gc();return;
                }
                run.measureEnd=System.nanoTime(); run.report.addProperty("measurementElapsedNanos",run.measureEnd-run.measureStart);
                run.report.add("measurementMetrics",json(run.runtime.minecraftMetrics().snapshot()));
                Map<String,Long> measuredActions=new TreeMap<>();
                JsonObject actionBaseline=run.report.getAsJsonObject("measurementActionBaseline");
                for(var action:run.actionCounts.entrySet()) {
                    long before=actionBaseline.has(action.getKey())?actionBaseline.get(action.getKey()).getAsLong():0;
                    measuredActions.put(action.getKey(),action.getValue()-before);
                }
                run.report.add("measurementActions",json(measuredActions)); run.draining=true; run.drainStart=System.nanoTime();
                if (scenario().equals("ui")) Files.writeString(root().resolve("server-done"),"measurement complete\n");
                if (scenario().equals("restart")) { saveManifest(server,run); finish(server,run); return; }
                if (scenario().equals("mutations") || scenario().equals("overload")) carrierFaults(server,run);
                stopInflux(server,run);
            }
            drain(server,run);
        } catch (Throwable failure) {
            run.report.addProperty("passed",false); run.report.addProperty("failure",failure.toString()); run.done=true;
            try { captureConsumerFailure(server,run); } catch (Exception secondary) { failure.addSuppressed(secondary); }
            try { evidence(server,run); } catch (Exception secondary) { failure.addSuppressed(secondary); }
            org.slf4j.LoggerFactory.getLogger(ScaleScenario.class).error("Scale scenario failed",failure); server.halt(false);
        }
    }
    private static void guard(MinecraftServer server) {
        state(root().isAbsolute() && Files.isRegularFile(root().resolve("colonyloom-scale-test")) && server.isDedicatedServer()
                && Files.isRegularFile(world(server).resolve("colonyloom-test-world")) && SCENARIOS.contains(scenario())
                && (phase().equals("exercise") || phase().equals("verify") && scenario().equals("restart")),"Exact scale scenario requires marked disposable dedicated world/root");
    }
    private static void initialize(MinecraftServer server,Run run) throws Exception {
        duration("Warmup",300); duration("Measure",scenario().equals("overload")?3600:1800);
        String profilePath=System.getProperty("colonyloom.test.scaleProfile",""); state(!profilePath.isEmpty(),"Measured frozen profile prerequisite absent");
        JsonObject profile=JsonParser.parseString(Files.readString(Path.of(profilePath))).getAsJsonObject();
        state(profile.has("thirtyCitizenBarrierPassed") && profile.get("thirtyCitizenBarrierPassed").getAsBoolean()
                && profile.has("acceptanceDuration") && profile.get("acceptanceDuration").getAsBoolean(),"Full thirty-resident physical prerequisite failed");
        EnumMap<Budget,Long> costs=new EnumMap<>(Budget.class);
        profile.getAsJsonObject("measuredP99UnitNanos").entrySet().forEach(entry -> costs.put(Budget.valueOf(entry.getKey()),entry.getValue().getAsLong()));
        run.frozen=SimulationLimits.development().calibrated(costs).scale300Capacity();
        JsonObject counts=profile.getAsJsonObject("calibratedCounts"); state(counts!=null,"Merged measured graph/storage/views counts absent");
        for (Budget budget:Budget.values()) {
            JsonElement count=counts.has(budget.name())?counts.get(budget.name()):counts.get(budget.key());
            state(count!=null && count.getAsInt()>0 && count.getAsInt()<=startCount(budget),"Unmeasured/invalid frozen category "+budget);
            if (SimulationLimits.calibrationTargetNanos(budget)>0) state(count.getAsInt()==run.frozen.budget(budget),"Frozen physical calibration formula mismatch "+budget);
            else {
                Long cost=costs.get(budget); long target=switch(budget) {case GRAPH_EXPANSIONS -> 600_000; case STORAGE_SLOT_CHECKS -> 800_000; default -> 400_000;};
                state(cost!=null && cost>0 && count.getAsInt()==Math.min(startCount(budget),Math.max(1,target/cost)),"Early measured category/formula absent "+budget);
            }
            run.frozen=run.frozen.withBudget(budget,count.getAsInt());
        }
        run.runtime.core().updateLimits(run.frozen); run.runtime.physicalLimitsUpdated();
        run.report.addProperty("scenario",scenario()); run.report.addProperty("phase",phase()); run.report.addProperty("acceptanceDuration",!diagnostic());
        run.report.add("measuredProfileSource",profile); run.report.add("frozenProfile",limits(run.frozen)); run.report.add("hardware",hardware());
        run.report.addProperty("seed",424242); run.report.addProperty("fixtureGeometry","3x128x128 territories centered512 apart;active64x64;10disjoint16x16x1 plots with clear waypoint rows;workshop/supply/buffer rows54/60/51;100 distinct initial positions;30builders30carpenters40couriers per colony");
        run.report.addProperty("msptBoundary","Existing production HIGHEST Pre->LOWEST Post recorder, not fixture clock or synthetic timing");
        var actorProfile=new GameProfile(OWNER,"ScaleOwner"); server.getProfileCache().add(actorProfile);
        server.getProfileCache().add(new GameProfile(offline("ScaleViewer"),"ScaleViewer"));
        run.actor=new ServerPlayer(server,server.overworld(),actorProfile,ClientInformation.createDefault()); run.actor.moveTo(8.5,64,56.5,0,0);
        server.overworld().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false,server);
        for(int colony=0;colony<3;colony++) for(int x=-2;x<10;x++) for(int z=-2;z<10;z++) server.overworld().getChunk(colony*32+x,z);
        for(int colony=0;colony<3;colony++) for(int x=0;x<4;x++) for(int z=0;z<4;z++) server.overworld().setChunkForced(colony*32+x,z,true);
        run.report.addProperty("externalForcedEntityTickingChunks",48);
        if (phase().equals("verify")) { loadManifest(server,run); return; }
        state(run.runtime.core().registry().colonies().isEmpty() && run.runtime.core().registry().citizensView().isEmpty(),"Fixture refuses nonempty world");
        createPack(server,run);
    }
    private static int startCount(Budget budget) { return switch(budget) {case ASSIGNMENT_CANDIDATES,DIRTY_RESCAN_OBJECTS -> 64;case GRAPH_EXPANSIONS -> 128;case NAVIGATION_STARTS -> 2;case BLUEPRINT_COMPARISONS,STORAGE_SLOT_CHECKS -> 256;case PHYSICAL_ACTIONS -> 4;case CHUNK_REQUESTS -> 1;case VIEW_ROWS -> 100;}; }
    private static void createPack(MinecraftServer server,Run run) throws Exception {
        Path pack=world(server).resolve("datapacks/colonyloom-scale-runtime");
        Files.createDirectories(pack.resolve("data/colonyloom/structure")); Files.createDirectories(pack.resolve("data/colonyloom/colonyloom/blueprints"));
        Files.writeString(pack.resolve("pack.mcmeta"),"{\"pack\":{\"pack_format\":48,\"description\":\"Disposable scale fixture only\"}}");
        for(int index=0;index<10;index++) {
            Plot plot=new Plot(0,index);
            Files.writeString(pack.resolve("data/colonyloom/colonyloom/blueprints/scale_plot_"+index+".json"),"{\"schemaVersion\":1,\"version\":1,\"structure\":\"colonyloom:scale_plot\",\"markers\":{\"work_origin\":[0,0,1],\"delivery_buffer\":["+(plot.buffer.getX()-plot.origin.getX())+",0,"+(plot.buffer.getZ()-plot.origin.getZ())+"]}}");
        }
        CompoundTag template=new CompoundTag(); template.putInt("DataVersion",net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        template.put("size",ints(16,1,16)); var palette=new ListTag(); var state=new CompoundTag(); state.putString("Name","minecraft:oak_stairs"); palette.add(state); template.put("palette",palette);
        var blocks=new ListTag(); for(int z=0;z<16;z++)for(int x=0;x<16;x++){var block=new CompoundTag();block.put("pos",ints(x,0,z));block.putInt("state",0);blocks.add(block);}template.put("blocks",blocks);template.put("entities",new ListTag());
        NbtIo.writeCompressed(template,pack.resolve("data/colonyloom/structure/scale_plot.nbt"));
        server.getPackRepository().reload();var selected=new ArrayList<>(server.getPackRepository().getSelectedIds());if(!selected.contains("file/colonyloom-scale-runtime"))selected.add("file/colonyloom-scale-runtime");run.reload=server.reloadResources(selected);
    }
    private static ListTag ints(int x,int y,int z){var list=new ListTag();list.add(IntTag.valueOf(x));list.add(IntTag.valueOf(y));list.add(IntTag.valueOf(z));return list;}
    private static void setup(MinecraftServer server,Run run) throws Exception {
        if (run.setup==0) {
            if (!run.reload.isDone()) return; run.reload.join();
            if(server.getTickCount()<run.nextFoundingTick)return;
            var loaded=io.github.kpuctajluk.colonyloom.minecraft.content.ContentLoader.load(server.getResourceManager(),server.registryAccess());
            state(loaded.blueprints().get(BLUEPRINT+"_0").blocks().size()==256,"Runtime StructureTemplate was not loaded");
            var planks=loaded.processes().get("colonyloom:oak_planks").recipe();var stairs=loaded.processes().get("colonyloom:oak_stairs").recipe();
            check(run,planks.ingredients().size()==1 && planks.ingredients().getFirst().matcher().itemId().equals("minecraft:oak_log") && planks.ingredients().getFirst().count()==1 && planks.output().itemId().equals("minecraft:oak_planks") && planks.outputCount()==4 && stairs.ingredients().size()==1 && stairs.ingredients().getFirst().matcher().itemId().equals("minecraft:oak_planks") && stairs.ingredients().getFirst().count()==6 && stairs.output().itemId().equals("minecraft:oak_stairs") && stairs.outputCount()==4,"canonical_oak_chain","unchanged release production1log->4planks;6planks->4stairs, no fixture recipes");
            int colony=run.foundedColonies;
            {
                int base=colony*512;
                // Native pushing can carry an original out of the64x64 working area.
                // Keep the entire existing128x128 territory at the same ground height;
                // no extra chunks are forced or admitted by this terrain preparation.
                for(int x=0;x<128;x++)for(int z=0;z<128;z++) { server.overworld().setBlock(new BlockPos(base+x,63,z),Blocks.STONE.defaultBlockState(),3);for(int y=64;y<68;y++)server.overworld().setBlock(new BlockPos(base+x,y,z),Blocks.AIR.defaultBlockState(),3); }
                run.colonies[colony]=uuid(command(server,run,"colonyloom colony create Scale"+colony+" "+base+" 64 0 "+(base+127)+" 64 127"),"colony");
                for(int index=0;index<10;index++)run.plots.add(new Plot(colony,index));
            }
            run.foundedColonies++;
            run.nextFoundingTick=(long)server.getTickCount()+io.github.kpuctajluk.colonyloom.minecraft.runtime.FoundingTerritoryValidation.CLIENT_WINDOW_TICKS;
            if(run.foundedColonies==3)run.setup=1;
            return;
        }
        for(int colony=0;colony<3;colony++)for(int x=0;x<4;x++)for(int z=0;z<4;z++)if(!server.overworld().isPositionEntityTicking(new BlockPos(colony*512+x*16,64,z*16)))return;
        for(Plot plot:run.plots) {
            for(BlockPos pos:List.of(plot.source,plot.returns,plot.barrel,plot.buffer))server.overworld().setBlock(pos,Blocks.BARREL.defaultBlockState(),3);
            server.overworld().setBlock(plot.table,Blocks.CRAFTING_TABLE.defaultBlockState(),3);
            for(var entry:Map.of(plot.source,"warehouse",plot.returns,"return",plot.barrel,"workshop",plot.buffer,"construction").entrySet())command(server,run,"colonyloom storage register "+run.colonies[plot.colony]+" "+coordinates(entry.getKey())+" "+entry.getValue());
            command(server,run,"colonyloom building register "+run.colonies[plot.colony]+" "+coordinates(plot.table)+" "+coordinates(plot.barrel));
            plot.workshop=run.runtime.core().registry().storage().workshops().stream().filter(w -> w.colonyId().equals(run.colonies[plot.colony]) && w.position().x()==plot.table.getX() && w.position().z()==plot.table.getZ()).findFirst().orElseThrow().id();
            var description=new JsonObject();description.addProperty("colony",run.colonies[plot.colony].toString());description.addProperty("plot",plot.index);description.addProperty("origin",coordinates(plot.origin));description.addProperty("warehouse",coordinates(plot.source));description.addProperty("workshop",coordinates(plot.barrel));description.addProperty("buffer",coordinates(plot.buffer));description.addProperty("return",coordinates(plot.returns));run.initial.add(description);
            replenish(server,run,plot);
        }
        run.setup=3;
    }
    private static void provision(MinecraftServer server,Run run,int colony) throws Exception {
        int[] residentRows={50,52,53,55,56,57,58,59,61,62};
        for(int index=0;index<100;index++) {
            int localX=index<30?18+(index%10)*2:2+(index%10)*6;
            BlockPos pos=new BlockPos(colony*512+localX,64,residentRows[index/10]);
            var body=new net.minecraft.world.phys.AABB(pos.getX()+0.2,pos.getY(),pos.getZ()+0.2,pos.getX()+0.8,pos.getY()+1.95,pos.getZ()+0.8);
            state(server.overworld().noCollision(body) && server.overworld().getEntitiesOfClass(CitizenEntity.class,body).isEmpty(),"Provisioning position overlaps native terrain/resident "+pos);
            String output=command(server,run,"colonyloom citizen create "+run.colonies[colony]+" "+coordinates(pos));
            UUID citizen=uuid(output,"citizen"),entity=uuid(output,"entity");
            String role=index<30?"builder":index<60?"carpenter":"courier";
            command(server,run,"colonyloom citizen assign "+citizen+" colonyloom:"+role);
            if(role.equals("carpenter"))command(server,run,"colonyloom citizen workplace "+citizen+" "+run.plots.get(colony*10+(index-30)%10).workshop);
            var nativeEntity=(CitizenEntity)server.overworld().getEntity(entity);state(nativeEntity!=null,"Public provision has no native embodiment");
            run.citizens.add(citizen);run.entities.put(citizen,entity);run.epochs.put(citizen,nativeEntity.bindingEpoch());run.positions.put(citizen,nativeEntity.position());run.distances.put(citizen,0.0);run.participation.put(citizen,0L);
            run.nativeResidents.put(citizen,nativeEntity);
            var identity=new JsonObject();identity.addProperty("citizen",citizen.toString());identity.addProperty("entity",entity.toString());identity.addProperty("epoch",nativeEntity.bindingEpoch());identity.addProperty("colony",run.colonies[colony].toString());identity.addProperty("role",role);identity.addProperty("position",coordinates(pos));identity.addProperty("inventory","empty");run.initial.add(identity);
        }
    }
    private static boolean ready(MinecraftServer server,Run run) {
        state(run.citizens.size()==300,"Wrong provisioned resident count");
        for(UUID id:run.citizens) {
            var record=run.runtime.core().registry().citizen(id);
            if(id.equals(run.deadCitizen))continue;
            if(!(server.overworld().getEntity(run.entities.get(id)) instanceof CitizenEntity entity) || !entity.isAlive() || entity.isRemoved() || entity.isQuarantined()
                    || !id.equals(entity.citizenId()) || entity.bindingEpoch()!=run.epochs.get(id)
                    || !record.entityId().equals(entity.getUUID()) || record.bindingEpoch()!=run.epochs.get(id)
                    || record.admission()!=CitizenRecord.Admission.ACTIVE || record.readiness()!=CitizenRecord.Readiness.READY
                    || !residentCoverage(server,run,entity))return false;
        }
        return true;
    }
    private static boolean residentCoverage(MinecraftServer server,Run run,CitizenEntity entity) {
        var pos=entity.blockPosition();
        var center=new ChunkKey(server.overworld().dimension().location().toString(),pos.getX()>>4,pos.getZ()>>4);
        return run.runtime.chunks().admitted(center)
                && run.runtime.chunks().ready(center,ChunkDemandManager.Readiness.ENTITY_TICKING)
                && server.overworld().isPositionEntityTicking(pos);
    }
    private static CitizenEntity originalResident(MinecraftServer server,Run run,UUID id) {
        var visible=server.overworld().getEntity(run.entities.get(id));
        if(visible instanceof CitizenEntity entity) {
            state(entity.getUUID().equals(run.entities.get(id)) && id.equals(entity.citizenId())
                    && entity.bindingEpoch()==run.epochs.get(id),"Original native binding changed "+id);
            run.nativeResidents.put(id,entity);return entity;
        }
        var retained=run.nativeResidents.get(id);
        // Native visibility can hide the already-bound loaded object during ticket rollover.
        // An unloaded/removed object is never evidence of a live resident; no query loads a chunk.
        return retained!=null && !retained.isRemoved() && retained.level()==server.overworld()
                && retained.getUUID().equals(run.entities.get(id)) && id.equals(retained.citizenId())
                && retained.bindingEpoch()==run.epochs.get(id) ? retained : null;
    }
    private static void observeResidentCoverage(MinecraftServer server,Run run,UUID id,CitizenEntity entity) throws Exception {
        var record=run.runtime.core().registry().citizen(id);long tick=run.runtime.serverTick();
        boolean physicallyTicking=server.overworld().isPositionEntityTicking(entity.blockPosition());
        if(physicallyTicking)run.actualEntityTickingResidents++;
        boolean ready=record.admission()==CitizenRecord.Admission.ACTIVE && record.readiness()==CitizenRecord.Readiness.READY
                && residentCoverage(server,run,entity);
        Long started=run.residentPauseStarted.get(id);
        if(ready) {
            if(started!=null) {
                long elapsed=tick-started;
                state(elapsed<=1200,"Original native resident readiness did not resume<=1200ticks "+id);
                state(record.activeTimeTicks()<=run.residentPausedClock.get(id)+1,"Original native resident caught up paused own clock "+id);
                run.residentMaxPauseTicks.merge(id,elapsed,Math::max);
                var resumed=new JsonObject();resumed.addProperty("citizen",id.toString());resumed.addProperty("entity",entity.getUUID().toString());
                resumed.addProperty("tick",tick);resumed.addProperty("resumed",true);resumed.addProperty("pausedTicks",elapsed);
                resumed.addProperty("cumulativePausedNativeTicks",run.residentPausedNativeTicks.getOrDefault(id,0L));
                Files.writeString(root().resolve("resident-chunk-blockers.jsonl"),resumed+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                run.residentPauseStarted.remove(id);run.residentPausedClock.remove(id);run.residentBlockers.remove(id);
            }
            return;
        }
        var coverage=run.runtime.citizenAdmission().coverage(id);
        var blocker=new JsonObject();blocker.addProperty("citizen",id.toString());blocker.addProperty("entity",entity.getUUID().toString());
        blocker.addProperty("tick",tick);blocker.addProperty("position",coordinates(entity.blockPosition()));blocker.addProperty("nativeEntityTicking",physicallyTicking);
        blocker.addProperty("admission",record.admission().name());blocker.addProperty("readiness",record.readiness().name());blocker.add("desired",json(coverage));
        run.residentBlockers.put(id,blocker);
        state(record.lifecycle()==CitizenRecord.Lifecycle.ALIVE && record.entityId().equals(entity.getUUID())
                && record.bindingEpoch()==entity.bindingEpoch() && record.readiness()!=CitizenRecord.Readiness.BLOCKED
                && record.admission()==CitizenRecord.Admission.INACTIVE && coverage!=null && coverage.pending()
                && coverage.observedCenter()!=null && (!coverage.ready()
                        || record.readiness()==CitizenRecord.Readiness.UNKNOWN || physicallyTicking),
                "Original native resident has no genuine pending chunk/readmission blocker "+blocker);
        if(started==null) {
            started=tick;run.residentPauseStarted.put(id,tick);run.residentPausedClock.put(id,record.activeTimeTicks());
            Files.writeString(root().resolve("resident-chunk-blockers.jsonl"),blocker+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }
        long elapsed=tick-started;blocker.addProperty("pausedTicks",elapsed);
        run.residentPausedOwnTicks.merge(id,1L,Long::sum);
        if(!physicallyTicking)run.residentPausedNativeTicks.merge(id,1L,Long::sum);
        run.residentMaxPauseTicks.merge(id,elapsed,Math::max);
        state(record.activeTimeTicks()==run.residentPausedClock.get(id),"Pending native chunk advanced original own clock "+id);
        state(elapsed<=1200,"Original native resident readiness did not resume<=1200ticks "+blocker);
    }
    private static void observe(MinecraftServer server,Run run) throws Exception {
        var registry=run.runtime.core().registry();
        run.actualEntityTickingResidents=0;
        for(UUID id:run.citizens) {
            var record=registry.citizen(id);
            if(id.equals(run.deadCitizen)) {state(record.lifecycle()==CitizenRecord.Lifecycle.DEAD,"Killed actual courier did not become DEAD");continue;}
            var entity=originalResident(server,run,id);
            state(entity!=null,"Original entity missing "+id);
            state(entity.isAlive() && !entity.isRemoved() && !entity.isQuarantined() && entity.bindingEpoch()==run.epochs.get(id),"Physical identity/ticking resident invariant "+id);
            Vec3 previous=run.positions.put(id,entity.position());if(previous!=null)run.distances.merge(id,previous.distanceTo(entity.position()),Double::sum);
            if(run.warmupStart!=0)observeResidentCoverage(server,run,id,entity);
            else if(server.overworld().isPositionEntityTicking(entity.blockPosition()))run.actualEntityTickingResidents++;
            String status=record.admission()==CitizenRecord.Admission.INACTIVE?"inactive":"idle";
            if(record.assignedWorkId()!=null) {
                var work=registry.workBoard().work(record.assignedWorkId());status=work.state()==WorkOrder.State.RUNNING?"active":"waiting";
                if(!work.terminal())run.participation.merge(id,1L,Long::sum);
            }
            add(run.roleTicks,record.professionId()+"."+status,1);
        }
        if(run.measuring && !run.draining)run.minimumMeasuredEntityTickingResidents=Math.min(run.minimumMeasuredEntityTickingResidents,run.actualEntityTickingResidents);
        harvestFacts(run);
        for(var entity:server.overworld().getAllEntities())if(entity instanceof ItemEntity drop && run.nativeDrops.containsKey(drop.getUUID())) {
            state(drop.getItem().getCount()==run.nativeDrops.get(drop.getUUID()),"Native death drop changed without accounting");
            if(drop.getAge()>5000) { add(run.removals,run.nativeDropItems.get(drop.getUUID()),drop.getItem().getCount());drop.discard();run.nativeDrops.remove(drop.getUUID());count(run,"externallyRemovedNativeDropBeforeDespawn",1); }
        }
    }
    private static void harvestFacts(Run run) throws Exception {
        var effects=run.runtime.core().registry().effects().snapshots();
        for(var effect:effects) {
            state(effect.state()!=EffectRecord.State.AMBIGUOUS,"Native effect ambiguous "+effect.operationId());
            if(effect.state()!=EffectRecord.State.OBSERVED || !run.seenEffects.add(effect.operationId()))continue;
            if(effect.craft()!=null) {
                var craft=effect.craft();state(craft.complete(),"Production craft has incomplete source/output facts");
                for(var input:craft.inputs())add(run.consumed,input.beforeItem().itemId(),input.amount());
                add(run.produced,craft.output().itemId(),craft.outputCount());count(run,"actualProductionBatches",1);
                Files.writeString(root().resolve("production-facts.jsonl"),new Gson().toJson(effect)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            } else if(effect.food()!=null) {add(run.consumed,effect.itemId(),effect.countBefore()-effect.countAfter());count(run,"actualFoodConsumptions",1);}
            else if(effect.kind()==ActionContext.Kind.BLOCK_PLACE) {add(run.consumed,effect.itemId(),effect.countBefore()-effect.countAfter());count(run,"actualBlockPlacements",1);}
            else if(effect.transfer()!=null)count(run,"actualNativeTransfers",1);
            run.participation.merge(effect.citizenId(),1L,Long::sum);
        }
        // Only dedup live retained witnesses; archived facts are an append-only external report, not heap state.
        run.seenEffects.retainAll(effects.stream().map(EffectRecord::operationId).collect(java.util.stream.Collectors.toSet()));
    }
    private static void issue(MinecraftServer server,Run run,Plot plot) throws Exception {
        var result=attempt(server,run,"colonyloom build "+run.colonies[plot.colony]+" "+BLUEPRINT+"_"+plot.index+" "+coordinates(plot.origin)+" 0");
        count(run,"publicBuildRequests",1);
        if(result.code==1)plot.work=uuid(result.output,"work");
        else {state(run.saturated || scenario().equals("overload"),"Initial public construction refused: "+result.output);count(run,"rejectedRepeatedBuildRequests",1);}
    }
    private static void replenish(MinecraftServer server,Run run,Plot plot) {
        var container=container(server,plot.source);
        for(var item:List.of(Items.OAK_LOG,Items.BREAD)) {
            int slot=item==Items.OAK_LOG?0:1;ItemStack previous=container.getItem(slot);
            state(previous.isEmpty() || previous.is(item),"Dedicated supply source slot polluted");
            int target=item==Items.OAK_LOG?64:64;if(previous.getCount()>=32)continue;
            int added=target-previous.getCount();container.setItem(slot,new ItemStack(item,target));container.setChanged();add(run.inputs,itemId(item),added);count(run,"externalSupplyFillActions",1);
        }
    }
    private static void economy(MinecraftServer server,Run run,boolean repeat) throws Exception {
        var registry=run.runtime.core().registry();
        if(run.ticks%20==0 && !(scenario().equals("mutations") && run.mutationPhase==1))for(Plot plot:run.plots)replenish(server,run,plot);
        for(Plot plot:run.plots) {
            if(plot.work==null) {if(repeat && run.ticks%20==0)issue(server,run,plot);continue;}
            WorkOrder work;
            try{work=registry.workBoard().work(plot.work);}catch(IllegalArgumentException retired){state(plot.completed>0,"Unobserved build retired before fixture completion");plot.work=null;if(repeat)issue(server,run,plot);continue;}
            if(work.state()!=WorkOrder.State.COMPLETED)continue;
            var site=registry.construction().site(plot.work);state(site!=null && site.consumed()==256,"Completed plot lacks256real material expenses");
            for(int x=0;x<16;x++)for(int z=0;z<16;z++)state(server.overworld().getBlockState(plot.origin.offset(x,0,z)).is(Blocks.OAK_STAIRS),"Completed plot missing native stair");
            if(!repeat)continue;
            for(int x=0;x<16;x++)for(int z=0;z<16;z++) {server.overworld().setBlock(plot.origin.offset(x,0,z),Blocks.AIR.defaultBlockState(),3);count(run,"externalCompletedBlocksRemoved",1);}
            plot.completed++;count(run,"completed256StairPlots",1);plot.work=null;
            issue(server,run,plot);
        }
    }
    private static void publishUi(MinecraftServer server,Run run) throws Exception {
        if(run.published)return;
        if(server.getPlayerList().getPlayerByName("ScaleOwner")==null || server.getPlayerList().getPlayerByName("ScaleViewer")==null)return;
        command(server,run,"colonyloom member set "+run.colonies[0]+" ScaleViewer viewer");
        var viewer=server.getPlayerList().getPlayerByName("ScaleViewer");
        var channel=viewer.connection.getConnection().channel();
        channel.eventLoop().execute(() -> {
            try {
                channel.pipeline().addBefore("packet_handler","colonyloom_scale_timeout",new ChannelDuplexHandler() {
                    @Override public void channelRead(ChannelHandlerContext context,Object message) throws Exception {
                        if(message instanceof ServerboundCustomPayloadPacket packet && packet.payload() instanceof ManagementPayloads.Unsubscribe request)
                            run.viewWire.add(new ViewWire(request.subscriptionId(),run.wireTick,false,"UNSUBSCRIBE"));
                        super.channelRead(context,message);
                    }
                    @Override public void write(ChannelHandlerContext context,Object message,ChannelPromise promise) throws Exception {
                        if(message instanceof ClientboundCustomPayloadPacket packet) {
                            if(packet.payload() instanceof ManagementPayloads.ViewSnapshot page) run.viewWire.add(new ViewWire(page.subscriptionId(),run.wireTick,true,null));
                            else if(packet.payload() instanceof ManagementPayloads.ViewClosed closed) run.viewWire.add(new ViewWire(closed.subscriptionId(),run.wireTick,false,closed.reason()));
                        }
                        super.write(context,message,promise);
                    }
                });
            } catch(Throwable failure) { run.wireFailure=failure; }
        });
        var value=new JsonObject();value.addProperty("colony",run.colonies[0].toString());value.addProperty("otherColony",run.colonies[1].toString());value.addProperty("citizen",run.citizens.getFirst().toString());value.addProperty("address","127.0.0.1:25577");
        Files.writeString(root().resolve("scale-ready.json"),value.toString());run.published=true;
    }
    private static void observeViewTimeouts(Run run) throws Exception {
        if(run.wireFailure!=null) throw new IllegalStateException("Actual server view observer failed",run.wireFailure);
        ViewWire packet;
        while((packet=run.viewWire.poll())!=null) {
            if(packet.snapshot()) {
                state(run.unacknowledgedSnapshotTicks.containsKey(packet.subscription()) || run.unacknowledgedSnapshotTicks.size()<ManagementProtocol.SUBSCRIPTIONS,"Server observer exceeded live subscription envelope");
                run.unacknowledgedSnapshotTicks.put(packet.subscription(),packet.tick());
            }
            else {
                Long sent=run.unacknowledgedSnapshotTicks.remove(packet.subscription());
                if("ACK_TIMEOUT".equals(packet.closed())) {
                    state(sent!=null,"Actual timeout lacks original server snapshot");
                    check(run,packet.tick()-sent==ManagementProtocol.ACK_TIMEOUT,"actual_server_ack_timeout_ticks",
                            "subscription="+packet.subscription()+" snapshotObservedTick="+sent+" closureObservedTick="+packet.tick());
                    run.observedAckTimeouts++;
                }
            }
        }
    }
    private static void checkFrozen(Run run) {
        SimulationLimits expected=run.shrinkPhase==1?run.frozen.withResource(Resource.LOADED_FOOTPRINT,run.reducedChunkCap):run.frozen;
        var actual=run.runtime.core().admission().limits();state(expected.resources().equals(actual.resources()) && expected.budgets().equals(actual.budgets()) && expected.maxManagedNanos()==actual.maxManagedNanos(),"Frozen profile changed outside measured chunk draining");
    }
    private static void exercise(MinecraftServer server,Run run) throws Exception {
        long tick=run.runtime.serverTick();
        if(run.noNotifyAt<0 && run.measuredTicks>=40) {
            Plot plot=run.plots.getFirst();
            run.noNotifyDemand=uuid(command(server,run,"colonyloom delivery request "+run.colonies[0]+" "+coordinates(plot.source)+" "+coordinates(plot.returns)+" minecraft:torch 1"),"demand");
            run.noNotifyAt=tick;container(server,plot.source).setItem(2,new ItemStack(Items.TORCH,1));add(run.inputs,"minecraft:torch",1);count(run,"noNotifyNativeArrival",1);
        }
        if(scenario().equals("parallel") && run.measuredTicks==1)check(run,run.plots.stream().filter(p -> p.work!=null && !run.runtime.core().workBoard().work(p.work).terminal()).count()==30,"thirty_parallel_builds","30 separate admitted physical roots");
        if(scenario().equals("critical") || scenario().equals("overload")) {
            saturation(server,run);
            if(run.saturated && run.criticalWaves==0)criticalWave(server,run);
        }
        if(scenario().equals("mutations"))mutations(server,run);
        if(scenario().equals("overload")) {
            overload(server,run);
            if(run.measuredTicks>1200)carrierFaults(server,run);
        }
        if(scenario().equals("mutations") && run.measuredTicks>1200)carrierFaults(server,run);
        if(scenario().equals("critical") || scenario().equals("overload"))criticalProgress(run);
    }
    private static void saturation(MinecraftServer server,Run run) throws Exception {
        if(run.saturated)return;
        var admission=run.runtime.core().admission();
        for(int n=0;n<8;n++) {
            int colony=(int)(run.actionCounts.getOrDefault("saturationCommandAttempts",0L)%3);
            count(run,"saturationCommandAttempts",1);
            CommandResult result=attempt(server,run,"colonyloom work wait "+run.colonies[colony]+" 1000000000");
            if(result.code==1) {run.influx.add(uuid(result.output,"work"));count(run,"acceptedSaturationRoots",1);}
            else count(run,"rejectedSaturationRoots",1);
            if(admission.used(Resource.WORKS,AdmissionLedger.Lane.NORMAL)>=admission.laneCapacity(Resource.WORKS,AdmissionLedger.Lane.NORMAL)-1 || admission.used(Resource.WORKS,AdmissionLedger.Lane.SERVICE)>=admission.laneCapacity(Resource.WORKS,AdmissionLedger.Lane.SERVICE)-1) {
                run.saturated=true;check(run,true,"normal_state_saturated","actual public ordinary roots exhausted admission (including mandatory service cleanup); normalWorks="+admission.used(Resource.WORKS,AdmissionLedger.Lane.NORMAL)+" normalCap="+admission.laneCapacity(Resource.WORKS,AdmissionLedger.Lane.NORMAL)+" serviceUsed="+admission.used(Resource.WORKS,AdmissionLedger.Lane.SERVICE)+" serviceCap="+admission.laneCapacity(Resource.WORKS,AdmissionLedger.Lane.SERVICE));return;
            }
        }
        run.influx.removeIf(id -> run.runtime.core().workBoard().works().stream().noneMatch(w -> w.id().equals(id)));
        state(run.measuredTicks<12000,"Public ordinary root saturation failed to reach frozen normal cap");
    }
    private static void criticalWave(MinecraftServer server,Run run) throws Exception {
        var registry=run.runtime.core().registry();
        for(int colony=0;colony<3;colony++) {
            int selected=0;
            for(UUID id:run.citizens) {
                var citizen=registry.citizen(id);
                if(!citizen.colonyId().equals(run.colonies[colony]) || !citizen.professionId().equals("colonyloom:courier"))continue;
                var npc=(CitizenEntity)server.overworld().getEntity(citizen.entityId());
                double closest=run.plots.stream().filter(p -> p.colony==citizenColony(run,citizen)).mapToDouble(p -> npc.position().distanceTo(Vec3.atCenterOf(p.source))).min().orElseThrow();
                if(closest>32)continue;
                state(registry.workBoard().works().stream().noneMatch(w -> WorkOrder.FOOD.equals(w.typeId()) && id.equals(w.subjectId()) && !w.terminal()),"Critical wave must create new roots, not reuse food work");
                registry.updateCitizen(new CitizenRecord(citizen.citizenId(),citizen.colonyId(),citizen.entityId(),citizen.bindingEpoch(),citizen.homeId(),citizen.workplaceId(),citizen.assignedWorkId(),citizen.professionId(),citizen.skills(),Map.of("food",6),citizen.lifecycle(),citizen.admission(),citizen.readiness(),citizen.activeTimeTicks(),Map.of("food",1200L),citizen.lastKnownPosition(),citizen.revision()+1));
                run.criticalStarted.put(id,run.runtime.serverTick());count(run,"externalHungryResidentStimuli",1);
                if(++selected==10)break;
            }
            state(selected==10,"Cannot establish exact30new ready-source food chains within32 without teleporting/replacing worker");
        }
        run.criticalWaves++;check(run,run.criticalStarted.size()==30,"critical_thirty_new_chains","30 hungry original courier subjects, physical bread only, normal admission remains saturated");
    }
    private static int citizenColony(Run run,CitizenRecord citizen){for(int c=0;c<3;c++)if(run.colonies[c].equals(citizen.colonyId()))return c;throw new IllegalStateException("Foreign resident");}
    private static void criticalProgress(Run run) throws Exception {
        for(var entry:run.criticalStarted.entrySet()) {
            if(run.criticalCompleted.contains(entry.getKey()))continue;
            var record=run.runtime.core().registry().citizen(entry.getKey());
            long elapsed=run.runtime.serverTick()-entry.getValue();
            if(record.needs().get("food")>6 && run.runtime.core().registry().workBoard().works().stream().anyMatch(w -> WorkOrder.FOOD.equals(w.typeId()) && entry.getKey().equals(w.subjectId()) && w.state()==WorkOrder.State.COMPLETED)) {
                state(elapsed<=1200,"Critical ready-source food deadline exceeded "+elapsed);run.maxCriticalTicks=Math.max(run.maxCriticalTicks,elapsed);run.criticalCompleted.add(entry.getKey());
            } else state(elapsed<=1200,"New critical chain uncompleted within1200ticks: "+entry.getKey()+" reason="+run.runtime.needs().reason(entry.getKey()));
        }
    }
    private static void mutations(MinecraftServer server,Run run) throws Exception {
        long tick=run.runtime.serverTick();
        if(run.mutationAt<0) {
            run.mutationAt=tick;
            for(Plot plot:run.plots) {var source=container(server,plot.source);var logs=source.getItem(0);add(run.removals,"minecraft:oak_log",logs.getCount());source.setItem(0,ItemStack.EMPTY);source.setChanged();}
            // A real road obstruction through the supply corridor, with a bounded detour at either end.
            for(int c=0;c<3;c++)for(int x=8;x<56;x++)for(int y=64;y<66;y++)server.overworld().setBlock(new BlockPos(c*512+x,y,52),Blocks.STONE.defaultBlockState(),3);
            count(run,"externalPathBlocksAdded",288);run.mutationPhase=1;
        }
        if(run.mutationPhase==1 && tick-run.mutationAt>=200) {
            for(int c=0;c<3;c++)for(int x=8;x<56;x++)for(int y=64;y<66;y++)server.overworld().setBlock(new BlockPos(c*512+x,y,52),Blocks.AIR.defaultBlockState(),3);
            count(run,"externalPathBlocksRemoved",288);run.mutationPhase=2;check(run,true,"native_stock_and_path_mutation","actual logs removed/accounted; native road blocked200ticks then reopened");
        }
        shrink(server,run);
    }
    private static void overload(MinecraftServer server,Run run) throws Exception {
        if(run.measuredTicks==1) {
            for(Plot plot:run.plots)for(int slot=0;slot<container(server,plot.buffer).getContainerSize();slot++) {
                ItemStack previous=container(server,plot.buffer).getItem(slot);if(!previous.isEmpty())continue;
                container(server,plot.buffer).setItem(slot,new ItemStack(Items.COBBLESTONE,64));add(run.inputs,"minecraft:cobblestone",64);
            }
            count(run,"externalFullReceiverStimuli",30);
        }
        if(run.measuredTicks%20==0)for(int c=0;c<3;c++) {
            int offset=(int)(run.measuredTicks/20%4);
            CommandResult result=attempt(server,run,"colonyloom work move "+run.colonies[c]+" "+(c*512+80+offset*12)+" 64 112");
            count(run,"extraRemoteCommandAttempts",1);
            if(result.code==1){run.remoteWorks.add(uuid(result.output,"work"));count(run,"acceptedRemoteMoves",1);}else count(run,"rejectedRemoteMoves",1);
        }
        if(run.measuredTicks%40==0)for(int c=0;c<3;c++) {
            Plot plot=run.plots.get(c*10);
            CommandResult result=attempt(server,run,"colonyloom delivery request "+run.colonies[c]+" "+coordinates(plot.source)+" "+coordinates(plot.returns)+" minecraft:oak_stairs 1000000");
            count(run,"extraGraphCommandAttempts",1);if(result.code==1){run.influx.add(uuid(result.output,"demand"));count(run,"acceptedGraphRoots",1);}else count(run,"rejectedGraphRoots",1);
        }
        var admission=run.runtime.core().admission();
        if(admission.rejected(Resource.GRAPH_NODES)>0 || admission.used(Resource.GRAPH_NODES,AdmissionLedger.Lane.NORMAL)>=admission.laneCapacity(Resource.GRAPH_NODES,AdmissionLedger.Lane.NORMAL)-1)run.graphSaturated=true;
        run.remoteWorks.removeIf(id -> run.runtime.core().workBoard().works().stream().noneMatch(w -> w.id().equals(id)));
        if(run.measuredTicks>2400)shrink(server,run);
    }
    private static void shrink(MinecraftServer server,Run run) throws Exception {
        if(run.shrinkPhase==0 && run.measuredTicks>600) {
            run.shrinkAt=run.runtime.serverTick();run.shrinkPhase=1;
            run.reducedChunkCap=Math.max(25,Math.min(run.frozen.resource(Resource.LOADED_FOOTPRINT)/2,run.runtime.chunks().footprint()-1));
            state(run.runtime.chunks().footprint()>run.reducedChunkCap,"Chunk shrinking scenario has no real pre-existing footprint overage");
            var changed=run.frozen.withResource(Resource.LOADED_FOOTPRINT,run.reducedChunkCap);
            run.runtime.core().updateLimits(changed);run.runtime.physicalLimitsUpdated();
            var observation=new JsonObject();observation.addProperty("tick",run.shrinkAt);observation.addProperty("oldCap",run.frozen.resource(Resource.LOADED_FOOTPRINT));observation.addProperty("newCap",changed.resource(Resource.LOADED_FOOTPRINT));observation.addProperty("existingFootprint",run.runtime.chunks().footprint());run.report.add("chunkCapReduction",observation);
        } else if(run.shrinkPhase==1 && run.runtime.serverTick()-run.shrinkAt>=1200) {
            state(run.runtime.chunks().footprint()<=run.runtime.core().admission().limits().resource(Resource.LOADED_FOOTPRINT),"Reduced chunk footprint failed safe draining");
            check(run,true,"chunk_cap_drained","actualFootprint="+run.runtime.chunks().footprint()+" no cargo deletion; lowered cap temporary overage separately recorded");
            run.runtime.core().updateLimits(run.frozen);run.runtime.physicalLimitsUpdated();run.shrinkPhase=2;
        }
    }
    private static void carrierFaults(MinecraftServer server,Run run) throws Exception {
        var registry=run.runtime.core().registry();
        for(var order:registry.supply().deliveries()) {
            if(order.citizenId()==null || order.terminal() || order.workId()==null)continue;
            var npc=server.overworld().getEntity(registry.citizen(order.citizenId()).entityId());if(!(npc instanceof CitizenEntity carrier))continue;
            int cargo=0;for(int slot=0;slot<carrier.inventory().getContainerSize();slot++)cargo+=carrier.inventory().getItem(slot).getCount();if(cargo==0)continue;
            if(!run.cancelled) {
                command(server,run,"colonyloom work cancel "+order.workId());run.cancelled=true;count(run,"publicCargoCancellation",1);check(run,true,"actual_carrier_cancel","native nonempty inventory retained by public cancel work="+order.workId());return;
            }
            if(!run.death) {
                run.deadCitizen=order.citizenId();run.death=true;Map<String,Long> before=new TreeMap<>();for(int slot=0;slot<carrier.inventory().getContainerSize();slot++){var stack=carrier.inventory().getItem(slot);if(!stack.isEmpty())add(before,itemId(stack.getItem()),stack.getCount());}
                Set<UUID> existing=new HashSet<>();for(var entity:server.overworld().getAllEntities())if(entity instanceof ItemEntity)existing.add(entity.getUUID());
                state(carrier.hurt(carrier.damageSources().genericKill(),Float.MAX_VALUE),"Actual carrier death refused");Map<String,Long> drops=new TreeMap<>();
                for(var entity:server.overworld().getAllEntities())if(entity instanceof ItemEntity drop && !existing.contains(drop.getUUID())) {add(drops,itemId(drop.getItem().getItem()),drop.getItem().getCount());run.nativeDrops.put(drop.getUUID(),drop.getItem().getCount());run.nativeDropItems.put(drop.getUUID(),itemId(drop.getItem().getItem()));}
                state(before.equals(drops),"Real death cargo/native drops mismatch before="+before+" drops="+drops);
                for(var entity:server.overworld().getAllEntities())if(entity instanceof ItemEntity drop && run.nativeDrops.containsKey(drop.getUUID())){add(run.removals,itemId(drop.getItem().getItem()),drop.getItem().getCount());drop.discard();count(run,"externallyRemovedAccountedNativeDeathDrop",1);}
                run.nativeDrops.clear();
                check(run,true,"actual_carrier_death_native_drops","original courier="+run.deadCitizen+" count="+cargo+" exact native published UUID/item facts="+run.nativeDropItems);count(run,"actualCarrierDeaths",1);return;
            }
        }
    }
    private static void monitor(MinecraftServer server,Run run) throws Exception {
        var registry=run.runtime.core().registry();long tick=run.runtime.serverTick();
        var admission=run.runtime.core().admission();
        for(UUID id:run.citizens)if(!id.equals(run.deadCitizen)) {
            var entity=originalResident(server,run,id);
            state(entity!=null && entity.isAlive() && !entity.isRemoved() && !entity.isQuarantined()
                    && id.equals(entity.citizenId()) && entity.bindingEpoch()==run.epochs.get(id)
                    && (residentCoverage(server,run,entity) || run.residentBlockers.containsKey(id)),
                    "Original native resident lost actual coverage without reported bounded chunk blocker: "+id);
        }
        for(Resource resource:Resource.values())if(resource!=Resource.CACHE_ENTRIES_PER_OWNER)state(admission.highWater(resource)<=run.frozen.resource(resource),"Global frozen cap exceeded "+resource+" highWater="+admission.highWater(resource));
        state(registry.effects().size()<=io.github.kpuctajluk.colonyloom.core.action.EffectRegistry.MAX_RECORDS,"Retained applied evidence exceeded production envelope");
        if(run.measuredTicks%200==0) {
            var pressure=new JsonObject();pressure.addProperty("tick",tick);pressure.addProperty("effects",registry.effects().size());pressure.addProperty("terminalWorks",registry.workBoard().works().stream().filter(WorkOrder::terminal).count());pressure.addProperty("constructionSites",registry.construction().size());pressure.addProperty("liveGraphNodes",admission.used(Resource.GRAPH_NODES));pressure.addProperty("graphRejections",admission.rejected(Resource.GRAPH_NODES));
            Files.writeString(root().resolve("retained-authoritative-state.jsonl"),pressure+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }
        for(var demand:registry.supply().demands()) {
            var s=demand.snapshot();state(s.fulfilled()>=0 && s.allocated()>=0 && s.covered()>=0 && s.fulfilled()+s.allocated()+s.covered()<=s.required(),"Negative stock/double coverage "+s);
        }
        if(run.noNotifyAt>=0 && !run.notified) {
            var demand=registry.supply().demand(run.noNotifyDemand).snapshot();
            if(demand.covered()>0 || demand.allocated()>0 || demand.fulfilled()>0) {run.maxWakeTicks=tick-run.noNotifyAt;run.notified=true;check(run,run.maxWakeTicks<=220,"no_notify_wakeup","native source arrival -> dependent coverage="+run.maxWakeTicks+"ticks without index notification");}
            else state(tick-run.noNotifyAt<=220,"No-notification native arrival did not wake dependent demand<=220ticks");
        }
        if(run.measuredTicks%20!=0)return;
        // Observe actual index timestamps without mutating it or substituting freshness with free(slot).
        var index=registry.storage().index();var field=index.getClass().getDeclaredField("observations");field.setAccessible(true);
        var values=(Map<?,?>)field.get(index);
        for(var entry:values.entrySet()) {
            var observed=entry.getValue();var timeField=observed.getClass().getDeclaredField("tick");timeField.setAccessible(true);
            long age=tick-timeField.getLong(observed);
            var slot=(io.github.kpuctajluk.colonyloom.core.storage.StockRegion)entry.getKey();
            if(!index.observation(slot).ready())continue;
            run.maxStockAge=Math.max(run.maxStockAge,age);if(age>200)check(run,false,"stock_freshness","actual indexed native slot="+slot+" ageTicks="+age+" frozenStorageChecks="+run.frozen.budget(Budget.STORAGE_SLOT_CHECKS)+" registeredSlots="+values.size());
        }
        JsonObject scheduler=json(run.runtime.core().scheduler().diagnostics(null)).getAsJsonObject();
        for(UUID colony:run.colonies) {
            JsonObject service=scheduler.getAsJsonObject("colonies").getAsJsonObject(colony.toString());state(service!=null,"Colony dispatch metrics absent");
            if(service.get("maxNormalColonyReadyServiceDelayTicks").getAsLong()>200 || service.get("currentNormalColonyReadyServiceDelayTicks").getAsLong()>200)check(run,false,"continuously_ready_colony_dispatch",service.toString());
        }
        run.heapHighWater=Math.max(run.heapHighWater,ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        if(run.measuredTicks%200==0) {
            var sample=new JsonObject();sample.addProperty("tick",tick);sample.addProperty("elapsedNanos",System.nanoTime()-run.measureStart);sample.add("roleActiveIdleWaiting",json(run.roleTicks));sample.add("scheduler",scheduler);sample.add("chunks",json(run.runtime.chunks().diagnostics(null)));sample.addProperty("oldestReadyStockAge",run.maxStockAge);
            sample.addProperty("actualEntityTickingResidents",run.actualEntityTickingResidents);sample.add("currentResidentChunkBlockers",json(run.residentBlockers));
            sample.add("residentPausedNativeTicks",json(run.residentPausedNativeTicks));sample.add("residentPausedOwnTicks",json(run.residentPausedOwnTicks));
            Files.writeString(root().resolve("scale-progress.jsonl"),new Gson().toJson(sample)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }
    }
    private static long fullGcCount() {
        long result=0;boolean supported=false;
        for(var gc:ManagementFactory.getGarbageCollectorMXBeans())if(gc.getName().toLowerCase(Locale.ROOT).contains("old") || gc.getName().toLowerCase(Locale.ROOT).contains("marksweep")) {state(gc.getCollectionCount()>=0,"Old-generation collector count unavailable");result+=gc.getCollectionCount();supported=true;}
        state(supported,"Comparable full-GC heap samples require an observed old-generation collector");return result;
    }
    private static void sampleHeap(Run run) throws Exception {
        long now=System.nanoTime();
        if(run.gcRequestedAt!=0) {
            if(fullGcCount()<=run.gcBefore) {state(now-run.gcRequestedAt<60*SECOND,"Requested full GC produced no comparable observed collection");return;}
            var sample=new JsonObject();sample.addProperty("elapsedNanos",now-run.measureStart);sample.addProperty("oldCollections",fullGcCount());sample.addProperty("retainedBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            sample.addProperty("collectorSignature",ManagementFactory.getGarbageCollectorMXBeans().stream().map(java.lang.management.GarbageCollectorMXBean::getName).toList().toString());run.heap.add(sample);run.gcRequestedAt=0;run.lastGc=now;
            state(sample.get("retainedBytes").getAsLong()<=3L*1024*1024*1024,"Live retained heap exceeds3GiB");return;
        }
        long interval=diagnostic()?Math.min(30*SECOND,duration("Measure",scenario().equals("overload")?3600:1800)/4):300*SECOND;
        if(now-run.lastGc>=interval) {run.gcBefore=fullGcCount();run.gcRequestedAt=now;System.gc();count(run,"requestedComparableFullGc",1);}
    }
    private static void stopInflux(MinecraftServer server,Run run) throws Exception {
        if(run.shrinkPhase==1) {run.runtime.core().updateLimits(run.frozen);run.runtime.physicalLimitsUpdated();run.shrinkPhase=2;}
        if(scenario().equals("overload"))for(Plot plot:run.plots) {
            var buffer=container(server,plot.buffer);for(int slot=0;slot<buffer.getContainerSize();slot++)if(buffer.getItem(slot).is(Items.COBBLESTONE)){add(run.removals,"minecraft:cobblestone",buffer.getItem(slot).getCount());buffer.setItem(slot,ItemStack.EMPTY);}buffer.setChanged();
        }
        // Artificial billion-tick waits and million-item requests are explicitly unresolvable influx;
        // cancel only these public roots, retaining production's cargo/surplus cleanup obligations.
        for(UUID id:run.influx) {
            var work=run.runtime.core().registry().workBoard().works().stream().filter(w -> w.id().equals(id)).findFirst().orElse(null);
            if(work!=null) {if(!work.terminal())command(server,run,"colonyloom work cancel "+id);}
            else {var demand=run.runtime.core().registry().supply().demands().stream().filter(d -> d.snapshot().id().equals(id)).findFirst().orElse(null);if(demand!=null && demand.snapshot().status()!=io.github.kpuctajluk.colonyloom.core.supply.Demand.Status.CANCELLED)command(server,run,"colonyloom delivery cancel "+id);}
        }
        count(run,"ceasedInflux",1);run.influx.clear();
    }
    private static void drain(MinecraftServer server,Run run) throws Exception {
        long elapsed=System.nanoTime()-run.drainStart;
        state(elapsed<1200*SECOND,"Accepted resolvable goals or native cleanup did not drain within1200seconds; works="+run.runtime.core().workBoard().works()+" supply="+run.runtime.core().registry().supply().snapshot());
        if(scenario().equals("ui") && (!Files.isRegularFile(root().resolve("owner-done")) || !Files.isRegularFile(root().resolve("viewer-done"))))return;
        boolean pending=run.runtime.core().registry().workBoard().works().stream().anyMatch(w -> !w.terminal());
        if(pending)return;
        state(run.runtime.core().registry().supply().deliveries().stream().allMatch(d -> d.terminal()),"Terminal work left live native cargo order");
        for(UUID id:run.remoteWorks) {
            var work=run.runtime.core().workBoard().works().stream().filter(w -> w.id().equals(id)).findFirst().orElse(null);
            state(work==null || work.state()==WorkOrder.State.COMPLETED,"Accepted resolvable remote movement did not complete "+id);
        }
        if(!run.residentBlockers.isEmpty())return;
        finish(server,run);
    }
    private static void finish(MinecraftServer server,Run run) throws Exception {
        state(ready(server,run),"Original resident entity ticking/admission missing at finish");harvestFacts(run);
        if(phase().equals("exercise")) {
            JsonObject measured=run.report.getAsJsonObject("measurementMetrics");var mspt=measured.getAsJsonObject("timers").getAsJsonObject("MSPT");
            check(run,run.measuredTicks>0 && mspt.get("count").getAsLong()>0,"measured_production_ticks","native ticks="+run.measuredTicks);
            check(run,mspt.get("totalNanos").getAsDouble()/mspt.get("count").getAsLong()<=50_000_000 && mspt.get("p95Nanos").getAsLong()<=50_000_000 && mspt.get("p99Nanos").getAsLong()<=75_000_000 && mspt.get("p999Nanos").getAsLong()<=150_000_000,"mspt_frozen_latency",mspt.toString());
            JsonObject actions=run.report.getAsJsonObject("measurementActions");
            check(run,actions.has("actualProductionBatches") && actions.get("actualProductionBatches").getAsLong()>0
                    && actions.has("actualNativeTransfers") && actions.get("actualNativeTransfers").getAsLong()>0
                    && actions.has("actualBlockPlacements") && actions.get("actualBlockPlacements").getAsLong()>0,
                    "actual_chain_actions",actions.toString());
            check(run,run.notified && run.maxWakeTicks<=220,"no_notify_wakeup_final","observedTicks="+run.maxWakeTicks);
            check(run,run.maxStockAge<=200,"stock_freshness","maxReadyObservedAgeTicks="+run.maxStockAge);
            if(scenario().equals("critical") || scenario().equals("overload"))check(run,run.saturated && run.criticalCompleted.size()==30 && run.maxCriticalTicks<=1200,"critical_reserved_progress","completed="+run.criticalCompleted.size()+" maxTicks="+run.maxCriticalTicks);
            if(scenario().equals("overload"))check(run,run.graphSaturated && run.actionCounts.getOrDefault("rejectedSaturationRoots",0L)+run.actionCounts.getOrDefault("rejectedGraphRoots",0L)+run.actionCounts.getOrDefault("rejectedRemoteMoves",0L)>0 && run.actionCounts.getOrDefault("ceasedInflux",0L)==1,"overload_bounded_and_resolvable_drain","graphSaturated="+run.graphSaturated+" actions="+run.actionCounts);
            if(scenario().equals("mutations") || scenario().equals("overload"))check(run,run.cancelled && run.death && run.shrinkPhase==2,"native_interruptions_and_chunk_draining","cancelled="+run.cancelled+" deadOriginal="+run.deadCitizen+" shrinkPhase="+run.shrinkPhase);
            checkHeap(run);
            if(scenario().equals("ui"))checkClients(run);
        }
        stockEquation(server,run);
        for(Resource resource:Resource.values())if(resource!=Resource.CACHE_ENTRIES_PER_OWNER)check(run,run.runtime.core().admission().highWater(resource)<=run.frozen.resource(resource),"global_cap_"+resource,"highWater="+run.runtime.core().admission().highWater(resource)+" cap="+run.frozen.resource(resource));
        var search=run.runtime.navigationBackendMetrics();check(run,((Number)search.get("queryHighWater")).intValue()<=16 && ((Number)search.get("nodeHighWater")).intValue()<=8192 && ((Number)search.get("openHighWater")).intValue()<=8192,"bounded_native_navigation_pool",search.toString());
        check(run,run.citizens.size()==300 && run.runtime.core().registry().colonies().size()==3,"original_three_by_hundred","300 identities; intentional carrier death="+run.deadCitizen+" no replacement entity");
        check(run,run.residentBlockers.isEmpty() && run.residentMaxPauseTicks.values().stream().allMatch(value -> value<=1200),
                "original_resident_chunk_resumption","all original live residents genuinely ready at finish; maximum individual pauseTicks="
                        +run.residentMaxPauseTicks.values().stream().mapToLong(Long::longValue).max().orElse(0)+" cumulative non-ticking observations="+run.residentPausedNativeTicks);
        if(phase().equals("exercise"))for(String role:List.of("builder","carpenter","courier"))check(run,run.roleTicks.getOrDefault("colonyloom:"+role+".active",0L)>0 && run.citizens.stream().filter(id -> run.runtime.core().registry().citizen(id).professionId().equals("colonyloom:"+role)).anyMatch(id -> run.distances.getOrDefault(id,0.0)>0.5),"actual_role_"+role,"active/idle/waiting recorded separately; native movement and assigned work observed");
        run.report.addProperty("passed",true);run.done=true;evidence(server,run);server.halt(false);
    }
    private static void checkHeap(Run run) throws Exception {
        state(run.heap.size()>=2,"No two comparable full-GC retained samples");
        JsonObject last=run.heap.get(run.heap.size()-1).getAsJsonObject(), first=run.heap.get(0).getAsJsonObject();
        long window=diagnostic()?0:Math.max(0,run.measureEnd-run.measureStart-1800*SECOND);
        for(JsonElement raw:run.heap)if(raw.getAsJsonObject().get("elapsedNanos").getAsLong()<=window)first=raw.getAsJsonObject();
        state(first.get("collectorSignature").equals(last.get("collectorSignature")),"Noncomparable GC samples");
        double growth=(double)last.get("retainedBytes").getAsLong()/first.get("retainedBytes").getAsLong()-1;
        check(run,growth<=0.10 && last.get("retainedBytes").getAsLong()<=3L*1024*1024*1024,"retained_heap_after_comparable_gc","last30minGrowth="+growth+" first="+first+" last="+last);
    }
    private static void checkClients(Run run) throws Exception {
        check(run,run.observedAckTimeouts==4,"four_actual_server_ack_deadlines","observed closures="+run.observedAckTimeouts+" deadlineTicks="+ManagementProtocol.ACK_TIMEOUT);
        for(String role:List.of("owner","viewer")) {
            Path file=root().resolve(role+"-observations.jsonl");state(Files.isRegularFile(file),"Real client evidence missing "+role);
            int count=0;Set<String> names=new HashSet<>();for(String line:Files.readAllLines(file))if(!line.isBlank()){var observation=JsonParser.parseString(line).getAsJsonObject();state(observation.get("passed").getAsBoolean(),"Real client failed "+observation);names.add(observation.get("check").getAsString());count++;}
            Set<String> required=new HashSet<>(Set.of("real-server-identity","actual-rendered-summary","actual-rendered-citizens","actual-rendered-buildings","actual-rendered-work","actual-gui-screenshot","concurrent-economic-measurement"));
            if(role.equals("viewer"))required.addAll(Set.of("foreign-colony-denied","viewer-mutation-denied","slow-client-bounded-and-purged"));else required.addAll(Set.of("unknown-scope-denied","owner-normal-ack-through-viewer-timeout"));
            check(run,names.containsAll(required),"real_"+role+"_client_checks","actual transport/GUI assertions="+names+" count="+count+" required="+required);
            state(Files.isRegularFile(root().resolve(role+"-scale.png")) && Files.size(root().resolve(role+"-scale.png"))>0,"Actual client PNG absent "+role);
        }
    }
    private static Map<String,Long> physical(MinecraftServer server,Run run) {
        Map<String,Long> total=new TreeMap<>();Set<BlockPos> visited=new HashSet<>();
        for(Plot plot:run.plots)for(BlockPos pos:List.of(plot.source,plot.returns,plot.barrel,plot.buffer))if(visited.add(pos))inventory(total,container(server,pos));
        for(UUID id:run.citizens) {var entity=server.overworld().getEntity(run.entities.get(id));if(entity instanceof CitizenEntity npc)inventory(total,npc.inventory());}
        for(var entity:server.overworld().getAllEntities())if(entity instanceof ItemEntity drop && run.nativeDrops.containsKey(drop.getUUID()))add(total,itemId(drop.getItem().getItem()),drop.getItem().getCount());
        return total;
    }
    private static void inventory(Map<String,Long> total,Container container) {for(int slot=0;slot<container.getContainerSize();slot++){var stack=container.getItem(slot);if(!stack.isEmpty())add(total,itemId(stack.getItem()),stack.getCount());}}
    private static void stockEquation(MinecraftServer server,Run run) throws Exception {
        Map<String,Long> actual=physical(server,run);Set<String> items=new TreeSet<>();items.addAll(run.inputs.keySet());items.addAll(run.produced.keySet());items.addAll(run.consumed.keySet());items.addAll(run.removals.keySet());items.addAll(actual.keySet());
        var equations=new JsonObject();
        for(String item:items) {
            long expected=run.inputs.getOrDefault(item,0L)+run.produced.getOrDefault(item,0L)-run.consumed.getOrDefault(item,0L)-run.removals.getOrDefault(item,0L);
            var equation=new JsonObject();equation.addProperty("externalInput",run.inputs.getOrDefault(item,0L));equation.addProperty("productionOutput",run.produced.getOrDefault(item,0L));equation.addProperty("productionBlockFoodExpense",run.consumed.getOrDefault(item,0L));equation.addProperty("externalNativeRemovalOrLoss",run.removals.getOrDefault(item,0L));equation.addProperty("physicalInventoriesAndNativeDrops",actual.getOrDefault(item,0L));equation.addProperty("expected",expected);equations.add(item,equation);
            run.report.add("stockBalance",equations);check(run,expected>=0 && expected==actual.getOrDefault(item,0L),"exact_resource_"+item,equation.toString());
        }
        long blocks=0;for(Plot plot:run.plots)for(int x=0;x<16;x++)for(int z=0;z<16;z++)if(server.overworld().getBlockState(plot.origin.offset(x,0,z)).is(Blocks.OAK_STAIRS))blocks++;
        check(run,run.consumed.getOrDefault("minecraft:oak_stairs",0L)==blocks+run.actionCounts.getOrDefault("externalCompletedBlocksRemoved",0L),"native_block_equation","production stairs expense="+run.consumed.getOrDefault("minecraft:oak_stairs",0L)+" currentBlocks="+blocks+" removedCompletedBlocks="+run.actionCounts.getOrDefault("externalCompletedBlocksRemoved",0L));
        check(run,run.consumed.getOrDefault("minecraft:bread",0L)==run.actionCounts.getOrDefault("actualFoodConsumptions",0L),"native_food_equation","one actual native bread expense per witnessed consumption");
    }
    private static void saveManifest(MinecraftServer server,Run run) throws Exception {
        harvestFacts(run);stockEquation(server,run);
        var manifest=new CompoundTag();var colonies=new ListTag();for(UUID colony:run.colonies){var entry=new CompoundTag();entry.putUUID("id",colony);colonies.add(entry);}manifest.put("colonies",colonies);
        var citizens=new ListTag();for(UUID id:run.citizens){var npc=(CitizenEntity)server.overworld().getEntity(run.entities.get(id));var entry=new CompoundTag();entry.putUUID("citizen",id);entry.putUUID("entity",npc.getUUID());entry.putLong("epoch",npc.bindingEpoch());entry.putInt("chunkX",npc.chunkPosition().x);entry.putInt("chunkZ",npc.chunkPosition().z);entry.put("native",npc.saveWithoutId(new CompoundTag()));citizens.add(entry);}manifest.put("citizens",citizens);
        var plots=new ListTag();for(Plot plot:run.plots){var entry=new CompoundTag();entry.putInt("colony",plot.colony);entry.putInt("index",plot.index);entry.putUUID("workshop",plot.workshop);if(plot.work!=null)entry.putUUID("work",plot.work);entry.putLong("completed",plot.completed);plots.add(entry);}manifest.put("plots",plots);
        manifest.putString("inputs",new Gson().toJson(run.inputs));manifest.putString("removals",new Gson().toJson(run.removals));manifest.putString("consumed",new Gson().toJson(run.consumed));manifest.putString("produced",new Gson().toJson(run.produced));manifest.putString("actions",new Gson().toJson(run.actionCounts));
        manifest.putString("physical",new Gson().toJson(physical(server,run)));manifest.putLong("serverTick",run.runtime.serverTick());
        RecoveryNativeState.saveBlocks(server);RecoveryNativeState.saveEntities(server);
        for(Tag raw:citizens){var identity=(CompoundTag)raw;var nativeTag=RecoveryNativeState.entity(server,identity.getUUID("entity"),new ChunkPos(identity.getInt("chunkX"),identity.getInt("chunkZ")));state(nativeTag.getCompound("Colonyloom").getUUID("citizenId").equals(identity.getUUID("citizen")) && nativeTag.getCompound("Colonyloom").getLong("bindingEpoch")==identity.getLong("epoch"),"Durable native original300 binding mismatch");}
        NbtIo.writeCompressed(manifest,world(server).resolve(MANIFEST));run.manifest=manifest;check(run,true,"restart_original_manifest","300 exact native UUIDs/epochs and actual property persisted independently; no replacement fixture");
    }
    private static void loadManifest(MinecraftServer server,Run run) throws Exception {
        run.manifest=NbtIo.readCompressed(world(server).resolve(MANIFEST),NbtAccounter.create(64L*1024*1024));
        var colonies=run.manifest.getList("colonies",Tag.TAG_COMPOUND);state(colonies.size()==3,"Same-world original colony manifest missing");for(int c=0;c<3;c++)run.colonies[c]=colonies.getCompound(c).getUUID("id");
        var citizens=run.manifest.getList("citizens",Tag.TAG_COMPOUND);state(citizens.size()==300,"Same-world original300manifest missing");
        for(Tag raw:citizens){var entry=(CompoundTag)raw;UUID id=entry.getUUID("citizen");run.citizens.add(id);run.entities.put(id,entry.getUUID("entity"));run.epochs.put(id,entry.getLong("epoch"));run.distances.put(id,0.0);run.participation.put(id,0L);
            var nativeTag=RecoveryNativeState.entity(server,entry.getUUID("entity"),new ChunkPos(entry.getInt("chunkX"),entry.getInt("chunkZ")));state(nativeTag.getCompound("Colonyloom").getUUID("citizenId").equals(id) && nativeTag.getCompound("Colonyloom").getLong("bindingEpoch")==entry.getLong("epoch"),"Restart durable original identity mismatch");}
        for(Tag raw:run.manifest.getList("plots",Tag.TAG_COMPOUND)){var entry=(CompoundTag)raw;var plot=new Plot(entry.getInt("colony"),entry.getInt("index"));plot.workshop=entry.getUUID("workshop");plot.work=entry.hasUUID("work")?entry.getUUID("work"):null;plot.completed=entry.getLong("completed");run.plots.add(plot);}
        decodeCounts(run.inputs,run.manifest.getString("inputs"));decodeCounts(run.removals,run.manifest.getString("removals"));decodeCounts(run.consumed,run.manifest.getString("consumed"));decodeCounts(run.produced,run.manifest.getString("produced"));decodeCounts(run.actionCounts,run.manifest.getString("actions"));
        // Existing observed facts are already included in the saved accounting baseline.
        for(Tag raw:run.manifest.getList("effectsAtCleanStop",Tag.TAG_COMPOUND)){var effect=(CompoundTag)raw;if(effect.hasUUID("operationId"))run.seenEffects.add(effect.getUUID("operationId"));}
    }
    private static void decodeCounts(Map<String,Long> target,String json){JsonParser.parseString(json).getAsJsonObject().entrySet().forEach(e -> target.put(e.getKey(),e.getValue().getAsLong()));}
    private static void verifyRestart(MinecraftServer server,Run run) throws Exception {
        state(System.nanoTime()-run.startup<300*SECOND,"Restart world never ready");
        if(run.readinessTick<0) {
            if(!ready(server,run))return;
            run.readinessTick=run.runtime.serverTick();
            var scheduler=json(run.runtime.core().scheduler().diagnostics(null)).getAsJsonObject().getAsJsonObject("colonies");
            for(UUID colony:run.colonies)run.restartServices.put(colony,scheduler.getAsJsonObject(colony.toString()).get("serviceCount").getAsLong());
            check(run,true,"restart_same300_identity","all native original identities/epochs present and genuinely entity-ticking once world ready");
            for(Plot plot:run.plots) if(plot.work!=null) {
                var work=run.runtime.core().workBoard().works().stream().filter(w -> w.id().equals(plot.work)).findFirst().orElse(null);
                if(work!=null && !work.terminal()) {
                    var site=run.runtime.core().registry().construction().site(work.id());
                    state(site!=null,"Original resumed work lacks original site");
                    run.restartConstructionConsumed.put(work.id(),site.consumed());
                }
            }
            state(run.restartConstructionConsumed.size()>=3,"Restart lacks independent accepted ready construction roots");
            for(UUID colony:run.colonies)state(run.restartConstructionConsumed.keySet().stream().anyMatch(id -> run.runtime.core().workBoard().work(id).colonyId().equals(colony)),"No independent resumed root for original colony "+colony);
            return;
        }
        observe(server,run);
        var scheduler=json(run.runtime.core().scheduler().diagnostics(null)).getAsJsonObject().getAsJsonObject("colonies");boolean progressed=true;
        for(UUID colony:run.colonies)if(scheduler.getAsJsonObject(colony.toString()).get("serviceCount").getAsLong()<=run.restartServices.get(colony))progressed=false;
        for(UUID colony:run.colonies) {
            boolean nativeProgress=run.restartProgressedColonies.contains(colony);
            for(var baseline:run.restartConstructionConsumed.entrySet()) {
                var site=run.runtime.core().registry().construction().site(baseline.getKey());
                if(site!=null && site.colonyId().equals(colony) && site.consumed()>baseline.getValue()) nativeProgress=true;
            }
            if(nativeProgress) run.restartProgressedColonies.add(colony);
            else progressed=false;
        }
        long elapsed=run.runtime.serverTick()-run.readinessTick;
        state(elapsed<=1200,"Same-world independent ready colonies did not resume<=1200ticks");
        economy(server,run,false);
        if(!progressed)return;
        check(run,true,"restart_resume_deadline","actual original construction material/block progress in every colony after readiness, elapsedTicks="+elapsed);run.report.addProperty("restartResumeTicks",elapsed);finish(server,run);
    }
    private void stopped(ServerStoppedEvent event) {
        Run run=runs.remove(event.getServer());if(run==null || scenario().isEmpty())return;
        try {
            var marker=NbtIo.readCompressed(world(event.getServer()).resolve("data/colonyloom-session.nbt"),NbtAccounter.create(64L*1024*1024));
            var state=NbtIo.readCompressed(world(event.getServer()).resolve("data/colonyloom.dat"),NbtAccounter.create(64L*1024*1024)).getCompound("data");
            check(run,marker.getBoolean("clean") && marker.getUUID("checkpointId").equals(state.getUUID("checkpointId")),"clean_native_checkpoint",marker.getUUID("checkpointId").toString());
            if(scenario().equals("restart") && phase().equals("exercise")) {
                for(Tag raw:run.manifest.getList("citizens",Tag.TAG_COMPOUND)) {
                    var identity=(CompoundTag)raw;var nativeTag=RecoveryNativeState.entity(event.getServer(),identity.getUUID("entity"),new ChunkPos(identity.getInt("chunkX"),identity.getInt("chunkZ")));
                    state(nativeTag.getCompound("Colonyloom").getUUID("citizenId").equals(identity.getUUID("citizen")) && nativeTag.getCompound("Colonyloom").getLong("bindingEpoch")==identity.getLong("epoch"),"Clean shutdown changed durable original identity");
                }
                run.manifest.put("effectsAtCleanStop",state.getList("evidence",Tag.TAG_COMPOUND).copy());
                run.manifest.putString("inputs",new Gson().toJson(run.inputs));run.manifest.putString("removals",new Gson().toJson(run.removals));run.manifest.putString("consumed",new Gson().toJson(run.consumed));run.manifest.putString("produced",new Gson().toJson(run.produced));run.manifest.putString("actions",new Gson().toJson(run.actionCounts));
                NbtIo.writeCompressed(run.manifest,world(event.getServer()).resolve(MANIFEST));
            }
            writeReport(run);
        } catch(Exception failure) {run.report.addProperty("passed",false);run.report.addProperty("failure",failure.toString());try{writeReport(run);}catch(Exception secondary){failure.addSuppressed(secondary);}org.slf4j.LoggerFactory.getLogger(ScaleScenario.class).error("Scale clean checkpoint failed",failure);}
    }
    /** Failure-only native consumer snapshot; never adds polling work to managed ticks. */
    private static void captureConsumerFailure(MinecraftServer server,Run run) throws Exception {
        if(run.runtime==null || run.runtime.navigation()==null)return;
        var registry=run.runtime.core().registry();var snapshot=new JsonObject();
        snapshot.addProperty("tick",run.runtime.serverTick());
        snapshot.add("chunks",json(run.runtime.chunks().diagnostics(null)));
        snapshot.add("scheduler",json(run.runtime.core().scheduler().diagnostics(null)));
        var budgets=new JsonObject();
        for(Budget budget:Budget.values()) {
            var usage=new JsonObject();usage.addProperty("used",registry.budgets().used(budget));
            usage.addProperty("limit",registry.budgets().limits().budget(budget));
            for(var lane:AdmissionLedger.Lane.values())usage.addProperty(lane.name(),registry.budgets().used(budget,lane));
            budgets.add(budget.name(),usage);
        }
        snapshot.add("budgets",budgets);
        var requests=(Map<?,?>)consumerField(run.runtime.navigation(),"requests");var navigation=new JsonArray();
        int count=0;
        for(var entry:requests.values()) {
            if(count++>=128)break;
            var request=(io.github.kpuctajluk.colonyloom.core.navigation.NavigationService.Request)consumerField(entry,"request");
            var row=new JsonObject();row.add("request",json(request));
            for(String name:List.of("created","state","reason","retryAt","domainReleased","failures","polledTick","searchedTick","queued","deferred","capacityWaiting","region","revisions"))row.add(name,json(consumerField(entry,name)));
            row.addProperty("domainAdmitted",run.runtime.chunks().admitted(request.workId()));
            row.addProperty("domainReady",run.runtime.chunks().ready(request.workId()));
            row.add("domainReason",json(run.runtime.chunks().reason(request.workId())));
            var work=registry.workBoard().work(request.workId());var citizen=registry.citizen(request.citizenId());
            row.add("work",json(Map.of("state",work.state(),"reason",work.waitingReason(),"stage",work.stage(),"revision",work.revision())));
            row.add("citizen",json(citizen));
            var entity=server.overworld().getEntity(citizen.entityId());
            if(entity!=null) {row.add("nativePosition",json(List.of(entity.getX(),entity.getY(),entity.getZ())));row.addProperty("nativeTicking",server.overworld().isPositionEntityTicking(entity.blockPosition()));}
            navigation.add(row);
        }
        snapshot.addProperty("navigationRequestCount",requests.size());snapshot.add("navigationRequests",navigation);
        var laneCursors=(Object[])consumerField(run.runtime.navigation(),"laneCursors");var cursors=new JsonArray();
        for(var cursor:laneCursors)cursors.add(cursor==null?com.google.gson.JsonNull.INSTANCE:json(consumerField(cursor,"request")));
        snapshot.add("navigationLaneCursors",cursors);
        var service=consumerField(run.runtime,"deliveryService");var active=(Map<?,?>)consumerField(service,"active");var deliveries=new JsonArray();
        count=0;
        for(var entry:active.entrySet()) {
            if(count++>=64)break;
            UUID workId=(UUID)entry.getKey();var state=entry.getValue();var row=new JsonObject();
            row.addProperty("workId",workId.toString());
            for(String name:List.of("waypoint","generation","waypointCursor","requiredChunks","slotCursors","unknownScans","pendingSlot","pendingRegistration","pendingCapacity","slotCapacity","scanFinished","returnToSource","surplusBufferCursor","pickupSource","pickupInventory","pickupDestination","pickupFallback","pickupCargoSlot","pickupDestinationSlot","pickupReturnSlot"))row.add(name,json(consumerField(state,name)));
            for(String name:List.of("loadDomain","retainedLoadDomain")) {
                var domain=consumerField(state,name);if(domain==null)continue;
                UUID owner=(UUID)consumerField(domain,"owner");var proof=new JsonObject();proof.addProperty("owner",owner.toString());
                proof.add("centers",json(consumerField(domain,"centers")));proof.addProperty("admitted",run.runtime.chunks().admitted(owner));proof.addProperty("ready",run.runtime.chunks().ready(owner));
                row.add(name,proof);
            }
            var work=registry.workBoard().work(workId);
            row.add("work",json(Map.of("state",work.state(),"reason",work.waitingReason(),"stage",work.stage(),"revision",work.revision())));
            row.add("waitingBuffer",json(run.runtime.deliveryService().waitingBuffer(workId)));
            var order=registry.supply().deliveryForWork(workId);
            if(order!=null) {
                row.add("order",json(order));row.add("shares",json(registry.supply().orderShares(order.id())));
                var location=run.runtime.storage().locate(order.source().storage());row.add("sourceLocation",json(location));
                if(location!=null) {var key=new ChunkKey(location.dimension(),location.x()>>4,location.z()>>4);row.addProperty("sourceCenterAdmitted",run.runtime.chunks().admitted(key));row.addProperty("sourceCenterReady",run.runtime.chunks().ready(key,ChunkDemandManager.Readiness.ENTITY_TICKING));}
                row.add("nativeSource",json(run.runtime.storage().readFresh(order.source())));
            }
            deliveries.add(row);
        }
        snapshot.addProperty("deliveryActiveCount",active.size());snapshot.add("deliveryActive",deliveries);
        Files.writeString(root().resolve("consumer-failure.json"),GSON.toJson(snapshot));
    }
    private static Object consumerField(Object object,String name) throws ReflectiveOperationException {
        var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }

    private static void evidence(MinecraftServer server,Run run) throws Exception {
        run.report.addProperty("scenario",scenario());run.report.addProperty("phase",phase());run.report.addProperty("acceptanceDuration",!diagnostic());run.report.addProperty("observedCitizens",run.citizens.size());
        run.report.addProperty("livingOriginalCitizens",run.citizens.stream().filter(id -> {
            var original=originalResident(server,run,id);return original!=null && original.isAlive() && !original.isRemoved();
        }).count());
        run.report.addProperty("actualEntityTickingResidents",run.actualEntityTickingResidents);
        run.report.addProperty("minimumMeasuredEntityTickingResidents",run.minimumMeasuredEntityTickingResidents);
        run.report.add("residentPausedNativeTicks",json(run.residentPausedNativeTicks));run.report.add("residentMaxPauseTicks",json(run.residentMaxPauseTicks));
        run.report.add("residentPausedOwnTicks",json(run.residentPausedOwnTicks));run.report.add("currentResidentChunkBlockers",json(run.residentBlockers));
        if(!run.report.has("warmupTicks"))run.report.addProperty("warmupTicks",run.frozenAt==0?0:run.runtime.serverTick()-run.frozenAt);
        run.report.addProperty("measurementTicks",run.measuredTicks);
        if(run.measureStart!=0 && !run.report.has("measurementElapsedNanos"))run.report.addProperty("measurementElapsedNanos",System.nanoTime()-run.measureStart);
        run.report.add("actions",json(run.actionCounts));run.report.add("roleActiveIdleWaitingTicks",json(run.roleTicks));run.report.add("actualDistanceByCitizen",json(run.distances));run.report.add("actualWorkParticipationByCitizen",json(run.participation));run.report.add("initialFixture",run.initial);
        run.report.add("retainedHeapSamplesAfterComparableGC",run.heap);run.report.addProperty("heapUsedHighWater",run.heapHighWater);run.report.addProperty("maxCriticalCompletionTicks",run.maxCriticalTicks);run.report.addProperty("maxReadyStockAgeTicks",run.maxStockAge);run.report.addProperty("noNotifyWakeupTicks",run.maxWakeTicks);
        run.report.add("externalInputs",json(run.inputs));run.report.add("externalRemovalsAndConfirmedLoss",json(run.removals));run.report.add("actualProductionOutputs",json(run.produced));run.report.add("actualProductionBlockFoodExpenses",json(run.consumed));run.report.add("nativeDeathDropUUIDs",json(run.nativeDrops));
        try {if(run.runtime!=null){run.report.add("finalMetrics",json(run.runtime.minecraftMetrics().snapshot()));run.report.add("actualFinalProfile",limits(run.runtime.core().admission().limits()));run.report.add("finalPhysicalStock",json(physical(server,run)));}}finally{writeReport(run);}
    }
    private static void writeReport(Run run) throws Exception {run.report.add("checks",run.checks);Files.createDirectories(root());Files.writeString(root().resolve("scale-report.json"),GSON.toJson(run.report));}
    private static JsonObject hardware() throws Exception {
        var result=new JsonObject();var os=ManagementFactory.getOperatingSystemMXBean();result.addProperty("os",os.getName()+" "+os.getVersion()+" "+os.getArch());result.addProperty("logicalProcessors",os.getAvailableProcessors());result.addProperty("cpuModel",System.getenv("PROCESSOR_IDENTIFIER"));
        if(os instanceof com.sun.management.OperatingSystemMXBean extended)result.addProperty("physicalMemoryBytes",extended.getTotalMemorySize());
        result.addProperty("javaVendor",System.getProperty("java.vendor"));result.addProperty("javaVersion",System.getProperty("java.version"));result.addProperty("maximumHeapBytes",Runtime.getRuntime().maxMemory());result.add("jvmArguments",json(ManagementFactory.getRuntimeMXBean().getInputArguments()));
        if(!diagnostic()) {
            state(Runtime.version().feature()==21,"Scale acceptance requires Java21");
            state(os instanceof com.sun.management.OperatingSystemMXBean extended && extended.getTotalMemorySize()>=16L*1024*1024*1024,"Scale server/two-client execution host requires at least16GiB physical RAM");
            state(Runtime.getRuntime().maxMemory()>=4L*1024*1024*1024-16L*1024*1024 && Runtime.getRuntime().maxMemory()<=4L*1024*1024*1024+16L*1024*1024,"Scale acceptance requires exactly-Xmx4G");
            state(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getInit()>=4L*1024*1024*1024-16L*1024*1024,"Scale acceptance requires-Xms4G");
        }
        result.addProperty("minecraft",net.minecraft.SharedConstants.getCurrentVersion().getName());result.addProperty("neoForge",net.neoforged.fml.ModList.get().getModContainerById("neoforge").orElseThrow().getModInfo().getVersion().toString());result.addProperty("colonyloom",net.neoforged.fml.ModList.get().getModContainerById("colonyloom").orElseThrow().getModInfo().getVersion().toString());return result;
    }
    private static JsonObject limits(SimulationLimits limits){var json=new JsonObject();for(Resource resource:Resource.values())json.addProperty(resource.key(),limits.resource(resource));for(Budget budget:Budget.values())json.addProperty(budget.key(),limits.budget(budget));json.addProperty("budgets.maxManagedNanos",limits.maxManagedNanos());return json;}
    private static JsonElement json(Object object){return new Gson().toJsonTree(object);}
    private static void check(Run run,boolean passed,String name,String detail) throws Exception {var check=new JsonObject();check.addProperty("check",name);check.addProperty("passed",passed);check.addProperty("detail",detail);run.checks.add(check);state(passed,name+": "+detail);}
    private static void state(boolean condition,String detail){if(!condition)throw new IllegalStateException(detail);}
    private static void add(Map<String,Long> map,String key,long amount){if(amount!=0)map.merge(key,amount,Math::addExact);}
    private static void count(Run run,String key,long amount){add(run.actionCounts,key,amount);}
    private static String itemId(Item item){return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();}
    private static Container container(MinecraftServer server,BlockPos pos){var entity=server.overworld().getBlockEntity(pos);state(entity instanceof Container,"Fixture native container missing "+pos);return (Container)entity;}
    private static String coordinates(BlockPos pos){return pos.getX()+" "+pos.getY()+" "+pos.getZ();}
    private static UUID uuid(String output,String key){var match=Pattern.compile("(?:^|[\\s\\[,])"+Pattern.quote(key)+"=([0-9a-fA-F-]{36})(?=[\\s\\],]|$)").matcher(output);state(match.find(),"Public command missing "+key+": "+output);return UUID.fromString(match.group(1));}
    private record CommandResult(int code,String output){}
    private static CommandResult attempt(MinecraftServer server,Run run,String text) throws Exception {var capture=new Capture();run.actor.moveTo(8.5,64,56.5,0,0);var colony=run.runtime.core().registry().colonies().stream().filter(c -> text.contains(c.colonyId().toString())).findFirst().orElse(null);if(colony!=null)run.actor.moveTo(colony.territory().minX()+8.5,64,56.5,0,0);int code=server.getCommands().getDispatcher().execute(text,run.actor.createCommandSourceStack().withPermission(2).withSource(capture));Files.writeString(root().resolve("public-command-results.jsonl"),new Gson().toJson(Map.of("tick",run.runtime.serverTick(),"command",text,"result",code,"messages",capture.messages))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);return new CommandResult(code,String.join("\n",capture.messages));}
    private static String command(MinecraftServer server,Run run,String text) throws Exception {var result=attempt(server,run,text);state(result.code==1,"Public command rejected "+text+": "+result.output);return result.output;}
    private static final class Capture implements CommandSource {final List<String> messages=new ArrayList<>();public void sendSystemMessage(Component message){messages.add(message.getString());}public boolean acceptsSuccess(){return true;}public boolean acceptsFailure(){return true;}public boolean shouldInformAdmins(){return false;}}
}
