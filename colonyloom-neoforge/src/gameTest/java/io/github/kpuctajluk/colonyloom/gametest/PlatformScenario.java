package io.github.kpuctajluk.colonyloom.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits;
import io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime;
import io.github.kpuctajluk.colonyloom.neoforge.ConstructionExecutorEvent;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.core.Direction;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Wall-clock dedicated measurements; never part of the distributed mod. */
final class PlatformScenario {
    private static final UUID OWNER = UUID.nameUUIDFromBytes("OfflinePlayer:PlatformOwner".getBytes(StandardCharsets.UTF_8));
    private static final long SECOND = 1_000_000_000L;
    private final Map<MinecraftServer, Run> runs = new IdentityHashMap<>();
    private static final class Run {
        MinecraftServerRuntime runtime;
        ServerPlayer actor;
        final UUID[] colonies = new UUID[3];
        final WorkOrder[] builds = new WorkOrder[3];
        final List<CitizenEntity> citizens = new ArrayList<>();
        final List<List<CitizenEntity>> citizensByColony = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        final List<UUID> moves = new ArrayList<>();
        final Map<UUID, Vec3> lastPositions = new LinkedHashMap<>();
        final Map<UUID, Double> distances = new LinkedHashMap<>();
        final JsonArray initial = new JsonArray();
        final List<BlockPos> clocks = new ArrayList<>();
        final boolean[] clockPowered = new boolean[16];
        final long[] clockCounts = new long[16];
        final List<BlockPos> hoppers = new ArrayList<>();
        final long[] hopperCounts = new long[32];
        final List<Sheep> sheep = new ArrayList<>();
        long hopperTransferred;
        long allocatedStart = -1, allocatedEnd = -1, heapHighWater;
        int moveWave;
        long moveRequests, buildRequests, externalStairs;
        SimulationLimits frozen;
        int provisionColony;
        boolean metricsChecked;
        long tickStart, warmupStart, measureStart, warmupTicks, measurementTicks, actions, damage, changedBlocks, completedMoves, completedBuilds;
        long readyWaitStart, chunkRequestedAt, chunkTransitions, readyChunkCycles, clockTransitions, fixtureNanos;
        UUID chunkOwner;
        boolean initialized, measuring, done, drain;
        long drainStarted;
        int chunkIndex;
        final JsonObject report = new JsonObject();
    }
    PlatformScenario() {
        NeoForge.EVENT_BUS.addListener(this::configure);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::pre);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::post);
        NeoForge.EVENT_BUS.addListener(this::stopped);
    }
    private static String scenario() { return System.getProperty("colonyloom.test.platformScenario", ""); }
    private static int count() { return Integer.getInteger("colonyloom.test.platformCitizens", 0); }
    private static boolean diagnostic() { return Boolean.getBoolean("colonyloom.test.platformDiagnostic"); }
    private static long duration(String kind, long full) {
        long seconds = Long.getLong("colonyloom.test.platform" + kind + "Seconds", full);
        if (seconds <= 0 || (!diagnostic() && seconds != full)) throw new IllegalStateException("Only explicitly non-acceptance diagnostics may shorten durations");
        return Math.multiplyExact(seconds, SECOND);
    }
    private static Path reportPath() { return Path.of(System.getProperty("colonyloom.test.platformReport")); }
    private void configure(ConstructionExecutorEvent event) {
        if (!scenario().isEmpty()) runs.computeIfAbsent(event.server(), ignored -> new Run()).runtime = event.runtime();
    }
    private void pre(ServerTickEvent.Pre event) {
        Run run = runs.get(event.getServer());
        if (run != null && !run.done) run.tickStart = System.nanoTime();
    }
    private void stopped(ServerStoppedEvent event) { runs.remove(event.getServer()); }
    private void post(ServerTickEvent.Post event) {
        if (scenario().isEmpty()) return;
        MinecraftServer server = event.getServer();
        Run run = runs.get(server);
        if (run == null || run.done) return;
        long now = System.nanoTime();
        boolean recordTick = run.measuring && !run.drain;
        long fixtureStart = System.nanoTime();
        try {
            guard(server);
            if (!run.initialized) { initialize(server, run); run.initialized = true; run.readyWaitStart = now; return; }
            if (run.provisionColony < 3 && count() != 0) {
                int base = run.provisionColony * 512;
                for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) {
                    if (!server.overworld().isPositionEntityTicking(new BlockPos(base + x * 16, 64, z * 16))) {
                        if (now - run.readyWaitStart > 180 * SECOND) throw new IllegalStateException("Prepared terrain never became entity ticking");
                        return;
                    }
                }
                provision(server, run, run.provisionColony++);
                return;
            }
            if (run.warmupStart == 0) {
                if (!ready(server, run)) {
                    if (now - run.readyWaitStart > 180 * SECOND) throw new IllegalStateException("Citizens or prepared chunks did not become genuinely admitted/ready");
                    return;
                }
                if (scenario().equals("diagnostic")) diagnostics(server, run);
                run.warmupStart = now;
            }
            if (!run.metricsChecked) { verifyMetricsAccess(server, run); run.metricsChecked = true; }
            if (!sameLimits(run.frozen, run.runtime.core().admission().limits())) throw new IllegalStateException("Frozen profile changed during measurement");
            if (!run.measuring) {
                run.warmupTicks++;
                if (now - run.warmupStart >= duration("Warmup", 300)) {
                    run.runtime.metrics().reset(); run.measuring = true; run.measureStart = now;
                    run.report.addProperty("warmupElapsedNanos", now - run.warmupStart);
                    run.allocatedStart = allocatedBytes(); run.report.add("memoryAtMeasurementStart", memory());
                    run.report.add("warmupActions", actionCounts(run));
                    run.report.add("schedulerAtMeasurementStart", json(run.runtime.core().scheduler().diagnostics(null)));
                    run.actions = run.damage = run.changedBlocks = run.completedMoves = run.completedBuilds = run.chunkTransitions = run.readyChunkCycles = run.clockTransitions = 0;
                    run.moveRequests = run.buildRequests = run.externalStairs = 0;
                    java.util.Arrays.fill(run.hopperCounts, 0);
                    run.fixtureNanos = 0;
                    java.util.Arrays.fill(run.clockCounts, 0); run.hopperTransferred = 0;
                    run.distances.replaceAll((id, distance) -> 0.0);
                }
            } else if (!run.drain) {
                run.measurementTicks++;
                if (now - run.measureStart >= duration("Measure", 600)) {
                    run.report.addProperty("measurementElapsedNanos", now - run.measureStart);
                    run.allocatedEnd = allocatedBytes(); run.report.add("memoryAtMeasurementEnd", memory());
                    observeMovement(run);
                    run.runtime.metrics().record(RuntimeMetrics.Timer.MSPT, System.nanoTime() - run.tickStart); recordTick = false;
                    run.report.add("measurementMetrics", json(run.runtime.minecraftMetrics().snapshot()));
                    run.report.add("measurementActions", actionCounts(run));
                    run.report.add("comparatorTransitionsDuringMeasurement", json(run.clockCounts)); run.report.add("hopperTransfersDuringMeasurement", json(run.hopperCounts));
                    run.report.add("measurementDistanceTravelledByCitizen", json(run.distances));
                    run.drain = true; run.drainStarted = now;
                }
            }
            observeMovement(run);
            if (!run.drain) exercise(server, run);
            else {
                boolean pending = !ready(server, run);
                for (UUID id : run.moves) if (!run.runtime.core().workBoard().work(id).terminal()) pending = true;
                if (run.chunkOwner != null && !run.runtime.chunks().ready(run.chunkOwner)) pending = true;
                for (WorkOrder work : run.builds) if (work != null && !work.terminal()) pending = true;
                if (pending && now - run.drainStarted > 120 * SECOND) throw new IllegalStateException("Accepted ready physical work unresolved after drain; no false success for bounded stalled queues");
                if (!pending) finish(server, run);
            }
        } catch (Exception failure) {
            run.done = true;
            run.report.addProperty("passed", false); run.report.addProperty("failure", failure.toString());
            if (!run.report.has("needsPortionedNavigation")) {
                var external = run.runtime.metrics().snapshot().get("NAVIGATION_EXTERNAL");
                run.report.addProperty("needsPortionedNavigation", vanillaBackend(run) && external.count() > 0 && external.p99Nanos() > 2_000_000);
            }
            try { evidence(server, run); } catch (Exception secondary) { failure.addSuppressed(secondary); }
            org.slf4j.LoggerFactory.getLogger(PlatformScenario.class).error("Platform scenario failed", failure);
            server.halt(false);
        } finally {
            run.fixtureNanos += System.nanoTime() - fixtureStart;
            run.heapHighWater = Math.max(run.heapHighWater, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            if (recordTick && run.tickStart != 0) run.runtime.metrics().record(RuntimeMetrics.Timer.MSPT, System.nanoTime() - run.tickStart);
        }
    }
    private static void guard(MinecraftServer server) throws Exception {
        if (!server.isDedicatedServer() || !Files.isRegularFile(server.getWorldPath(LevelResource.ROOT).resolve("colonyloom-test-world"))
                || !List.of("baseline", "idle", "movement-open", "movement-obstructed", "damage", "block", "chunks", "diagnostic").contains(scenario())
                || !List.of(0, 30, 100, 300).contains(count()) || (count() == 0) != scenario().equals("baseline"))
            throw new IllegalStateException("Platform runner requires exact scenario/count and dedicated marked disposable world");
    }
    private static void initialize(MinecraftServer server, Run run) throws Exception {
        if (!run.runtime.core().registry().citizensView().isEmpty() || !run.runtime.core().registry().colonies().isEmpty()) throw new IllegalStateException("Fixture refuses nonempty world");
        run.report.addProperty("scenario", scenario()); run.report.addProperty("citizens", count()); run.report.addProperty("acceptanceDuration", !diagnostic());
        run.report.addProperty("warmupSeconds", duration("Warmup", 300) / SECOND); run.report.addProperty("measurementSeconds", duration("Measure", 600) / SECOND);
        run.report.addProperty("seed", 424242); run.report.addProperty("world", "superflat peaceful mob-spawning=false view=6 simulation=6");
        run.report.addProperty("scope", "Stage06 physical platform only; no economy, graph/storage/views or raids measured. Diagnostic mob/redstone fixture is not a TPS guarantee.");
        run.report.addProperty("msptBoundary", "HIGHEST Pre through LOWEST Post including composed simulation and fixture actions; excludes intertick sleep. Closing boundary sampled immediately before diagnostic snapshot. Nested subsystem durations are not additive.");
        run.report.addProperty("externalPreparedChunks", 48); run.report.addProperty("externalLoadPolicy", "Identical vanilla forced 4x4 active64x64 chunks in all three territories, separate from charged manager tickets; pre-generated5x5 ticket rings and distant test domains.");
        run.report.add("hardware", hardware());
        run.frozen = run.runtime.core().admission().limits();
        String frozenInput = System.getProperty("colonyloom.test.platformProfile", "");
        if (count() > 30) {
            if (frozenInput.isEmpty()) throw new IllegalStateException("100/300 require observed thirty-citizen calibration report");
            JsonObject input = com.google.gson.JsonParser.parseString(Files.readString(Path.of(frozenInput))).getAsJsonObject();
            if (!input.get("thirtyCitizenBarrierPassed").getAsBoolean() || !input.get("acceptanceDuration").getAsBoolean()) throw new IllegalStateException("Genuine full-duration thirty-citizen prerequisite did not pass");
            EnumMap<SimulationLimits.Budget, Long> costs = new EnumMap<>(SimulationLimits.Budget.class);
            input.getAsJsonObject("measuredP99UnitNanos").entrySet().forEach(entry -> costs.put(SimulationLimits.Budget.valueOf(entry.getKey()), entry.getValue().getAsLong()));
            run.frozen = SimulationLimits.development().calibrated(costs).scale300Capacity();
            run.runtime.core().updateLimits(run.frozen); run.runtime.physicalLimitsUpdated();
            run.report.add("measuredProfileSource", input);
        }
        run.report.add("frozenProfile", limits(run.frozen));
        GameProfile profile = new GameProfile(OWNER, "PlatformOwner");
        if (server.getProfileCache() == null) throw new IllegalStateException("Dedicated profile cache absent");
        server.getProfileCache().add(profile);
        run.actor = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
        GameType.SURVIVAL.updatePlayerAbilities(run.actor.getAbilities()); run.actor.moveTo(8.5, 64, 8.5, 0, 0);
        server.overworld().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, server);
        for (int colony = 0; colony < 3; colony++) {
            int origin = colony * 512;
            // Generate the same surrounding ticket footprint even when no Colonyloom citizen exists.
            for (int x = -2; x <= 5; x++) for (int z = -2; z <= 5; z++) server.overworld().getChunk((origin >> 4) + x, z);
            for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) server.overworld().setChunkForced((origin >> 4) + x, z, true);
            for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) {
                server.overworld().setBlock(new BlockPos(origin + x, 63, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 64; y <= 67; y++) server.overworld().setBlock(new BlockPos(origin + x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
            JsonObject territory = new JsonObject(); territory.addProperty("from", origin + " 64 0"); territory.addProperty("to", (origin + 127) + " 64 127"); territory.addProperty("active", origin + ".." + (origin + 63) + ",0..63"); run.initial.add(territory);
            territory.addProperty("movementTargets", "For each explicit citizen list index:outbound x=origin+34+(index%10);home x=origin+3+(index%10);y=64;z=3+3*(index/10).Public finite moves then genuine WorkBoard READY/assign transition for that resident;production controller performs all progression.");
            territory.addProperty("buildTarget", (origin + 4) + " 64 10;16 oak_stairs then remove only completed target rows;replenish slot0to64 when<16");
            territory.addProperty("damageActions", "Each40server ticks heal actual NPC to max then generic actual1health hurt;nonlethal delta required");
            territory.addProperty("blockWorkerPolicy", "One assigned builder per colony, other physical residents carpenters; repeated finite construction uses natural production assignment without accumulating idle builders on the same target row. Parallel economy construction is stage14.");
            if (count() == 0) continue;
            run.colonies[colony] = uuid(command(server, run, "colonyloom colony create Platform" + colony + " " + origin + " 64 0 " + (origin + 127) + " 64 127"), "colony");
            if (scenario().equals("movement-obstructed")) {
                // Each sixteen-block corridor has a real detour inside its admitted search chunks.
                for (int z = 0; z <= 31; z++) if (z % 16 < 11 || z % 16 > 13) for (int y = 64; y <= 65; y++) server.overworld().setBlock(new BlockPos(origin + 16, y, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        if (scenario().equals("chunks")) for (int center = 0; center < 4; center++) for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) server.overworld().getChunk(128 + center * 8 + x, z);
        if (scenario().equals("chunks")) run.report.addProperty("chunkActionPositions", "Four pre-generated remote centers:(128,0),(136,0),(144,0),(152,0),overworld chunk coordinates;request only observednotentityticking,hold40ticksafterrequestthenrelease;ordinary manager admission");
        run.report.add("initialFixture", run.initial);
    }
    private static void provision(MinecraftServer server, Run run, int colony) throws Exception {
        int origin = colony * 512;
        int population = count() / 3 + (colony < count() % 3 ? 1 : 0);
        for (int npc = 0; npc < population; npc++) {
            BlockPos pos = new BlockPos(origin + 3 + (npc % 10) * 3, 64, 3 + (npc / 10) * 3);
            UUID entityId = uuid(command(server, run, "colonyloom citizen create " + run.colonies[colony] + " " + coordinates(pos)), "entity");
            if (!(server.overworld().getEntity(entityId) instanceof CitizenEntity entity)) throw new IllegalStateException("Provisioned physical NPC missing");
            String role = scenario().equals("block") && npc != 0 ? "colonyloom:carpenter" : "colonyloom:builder";
            command(server, run, "colonyloom citizen assign " + entity.citizenId() + " " + role);
            run.citizens.add(entity); run.lastPositions.put(entity.citizenId(), entity.position()); run.distances.put(entity.citizenId(), 0.0);
            run.citizensByColony.get(colony).add(entity);
            JsonObject citizen = new JsonObject(); citizen.addProperty("citizenId", entity.citizenId().toString()); citizen.addProperty("entityId", entityId.toString()); citizen.addProperty("colonyId", run.colonies[colony].toString()); citizen.addProperty("bindingEpoch", entity.bindingEpoch()); citizen.addProperty("position", coordinates(pos)); citizen.addProperty("role", role); citizen.addProperty("initialInventory", "empty"); run.initial.add(citizen);
        }
    }
    private static boolean ready(MinecraftServer server, Run run) {
        for (int colony = 0; colony < 3; colony++) for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) if (!server.overworld().isPositionEntityTicking(new BlockPos(colony * 512 + x * 16, 64, z * 16))) return false;
        if (run.citizens.size() != count() || run.runtime.core().admission().used(SimulationLimits.Resource.CITIZENS) != count()) throw new IllegalStateException("Wrong actual admitted citizen count");
        for (CitizenEntity entity : run.citizens) {
            CitizenRecord citizen = run.runtime.core().registry().citizen(entity.citizenId());
            if (!entity.isAlive() || entity.isQuarantined()) throw new IllegalStateException("Physical NPC lost or quarantined");
            if (citizen.admission() != CitizenRecord.Admission.ACTIVE || citizen.readiness() != CitizenRecord.Readiness.READY || !run.runtime.chunks().ready(citizen.citizenId())) return false;
        }
        return true;
    }
    private static void observeMovement(Run run) {
        for (CitizenEntity citizen : run.citizens) {
            if (!citizen.isAlive() || citizen.isQuarantined()) throw new IllegalStateException("Live physical NPC invariant failed");
            Vec3 previous = run.lastPositions.put(citizen.citizenId(), citizen.position());
            run.distances.merge(citizen.citizenId(), previous.distanceTo(citizen.position()), Double::sum);
        }
    }
    private static void exercise(MinecraftServer server, Run run) throws Exception {
        long tick = run.runtime.serverTick();
        if (scenario().startsWith("movement")) movement(server, run);
        if (scenario().equals("damage") && tick % 40 == 0) {
            for (CitizenEntity citizen : run.citizens) {
                citizen.setHealth(citizen.getMaxHealth()); // Explicit fixture healing, never a logical damage surrogate.
                float before = citizen.getHealth();
                if (!citizen.hurt(server.overworld().damageSources().generic(), 1) || citizen.getHealth() >= before || !citizen.isAlive()) throw new IllegalStateException("Actual nonlethal incoming damage not observed");
                run.damage++; run.actions++;
            }
        }
        if (scenario().equals("block")) builds(server, run);
        if (scenario().equals("chunks")) chunks(server, run);
        if (scenario().equals("diagnostic")) for (int index = 0; index < run.clocks.size(); index++) {
            boolean powered = server.overworld().getBlockState(run.clocks.get(index)).getValue(ComparatorBlock.POWERED);
            if (powered != run.clockPowered[index]) { run.clockTransitions++; run.clockCounts[index]++; run.clockPowered[index] = powered; }
        }
        if (scenario().equals("diagnostic") && tick % 200 == 0) {
            long transferred = 0;
            for (int index = 0; index < run.hoppers.size(); index++) {
                BlockPos hopper = run.hoppers.get(index);
                if (!(server.overworld().getBlockEntity(hopper.below()) instanceof net.minecraft.world.Container output)) throw new IllegalStateException("Real hopper output disappeared");
                int contents = 0; for (int slot = 0; slot < output.getContainerSize(); slot++) contents += output.getItem(slot).getCount();
                transferred += contents;
                run.hopperCounts[index] += contents;
                output.clearContent(); // Explicit diagnostic external drain keeps real hopper transfer active.
                if (!(server.overworld().getBlockEntity(hopper.above()) instanceof net.minecraft.world.Container input)) throw new IllegalStateException("Real hopper input disappeared");
                for (int slot = 0; slot < input.getContainerSize(); slot++) if (input.getItem(slot).isEmpty()) { input.setItem(slot, new ItemStack(Items.STONE, 64)); run.actions += 64; }
            }
            run.hopperTransferred += transferred;
        }
    }
    private static void movement(MinecraftServer server, Run run) throws Exception {
        var board = run.runtime.core().workBoard();
        for (int index = run.moves.size() - 1; index >= 0; index--) {
            WorkOrder work = board.work(run.moves.get(index));
            if (!work.terminal()) continue;
            if (work.state() != WorkOrder.State.COMPLETED) throw new IllegalStateException("Admitted move failed: " + work.snapshot());
            run.completedMoves++; board.retire(work.id()); run.moves.remove(index);
        }
        // One finite request per physical resident; no new wave until every prior request is terminal.
        if (!run.moves.isEmpty()) return;
        // Completing a protected route can precede occupied-center readmission at scarce maintenance quotas.
        // Never force assignment through that real readiness gap; issue the next whole wave only when ready.
        if (!ready(server, run)) return;
        boolean east = (run.moveWave++ & 1) == 0;
        for (int colony = 0; colony < 3; colony++) {
            int desired = count() / 3 + (colony < count() % 3 ? 1 : 0);
            for (int lane = 0; lane < desired; lane++) {
                BlockPos target = new BlockPos(colony * 512 + (east ? 34 : 3) + (lane % 10), 64, 3 + (lane / 10) * 3);
                UUID move = uuid(command(server, run, "colonyloom work move " + run.colonies[colony] + " " + coordinates(target)), "work");
                CitizenEntity executor = run.citizensByColony.get(colony).get(lane);
                board.transition(move, WorkOrder.State.READY, WorkOrder.Reason.NONE, "move");
                if (!board.assign(move, executor.citizenId())) throw new IllegalStateException("Real fixture worker assignment refused " + executor.citizenId());
                run.moves.add(move); run.actions++; run.moveRequests++;
            }
        }
    }
    private static void builds(MinecraftServer server, Run run) throws Exception {
        boolean completed = run.builds[0] != null;
        if (completed) {
            for (WorkOrder work : run.builds) {
                if (!work.terminal()) return;
                var site = run.runtime.core().registry().construction().site(work.id());
                // A retained bounded fixture reference survives production retirement; only closed terminal sites can be compacted.
                if (work.state() != WorkOrder.State.COMPLETED || (site != null && !site.closed())) throw new IllegalStateException("Public build did not close completed target " + work.snapshot());
            }
        }
        for (int colony = 0; colony < 3; colony++) {
            BlockPos origin = new BlockPos(colony * 512 + 4, 64, 10);
            if (completed) {
                for (int block = 0; block < 16; block++) {
                    if (!server.overworld().getBlockState(origin.offset(block, 0, 0)).is(Blocks.OAK_STAIRS)) throw new IllegalStateException("Physical build missing target stair");
                    server.overworld().setBlock(origin.offset(block, 0, 0), Blocks.AIR.defaultBlockState(), 3); run.changedBlocks++;
                }
                run.completedBuilds++; run.builds[colony] = null;
            }
            for (CitizenEntity citizen : run.citizensByColony.get(colony)) {
                if (!"colonyloom:builder".equals(run.runtime.core().registry().citizen(citizen.citizenId()).professionId())) continue;
                int current = citizen.inventory().getItem(0).getCount();
                if (current < 16) { citizen.inventory().setItem(0, new ItemStack(Items.OAK_STAIRS, 64)); run.actions += 64 - current; run.externalStairs += 64 - current; }
            }
            UUID workId = uuid(command(server, run, "colonyloom build " + run.colonies[colony] + " colonyloom:stair_strip " + coordinates(origin) + " 0"), "work");
            run.builds[colony] = run.runtime.core().workBoard().work(workId); run.actions++; run.buildRequests++;
        }
    }
    private static void chunks(MinecraftServer server, Run run) {
        var manager = run.runtime.chunks();
        if (run.chunkOwner != null) {
            if (manager.admitted(run.chunkOwner) && manager.ready(run.chunkOwner)) {
                if (run.runtime.serverTick() - run.chunkRequestedAt >= 40) {
                    run.readyChunkCycles++; manager.release(run.chunkOwner); run.chunkOwner = null; run.chunkIndex = (run.chunkIndex + 1) % 4;
                }
            } else if (run.runtime.serverTick() - run.chunkRequestedAt > 600) throw new IllegalStateException("Admitted chunk readiness unresolved");
            return;
        }
        ChunkKey center = new ChunkKey("minecraft:overworld", 128 + run.chunkIndex * 8, 0);
        // Wait for actual absence of ticking readiness before issuing a real production ticket request.
        if (manager.ready(center, ChunkDemandManager.Readiness.ENTITY_TICKING)) return;
        run.chunkOwner = UUID.randomUUID(); run.chunkRequestedAt = run.runtime.serverTick();
        manager.request(run.chunkOwner, run.colonies[0], List.of(center), ChunkDemandManager.Readiness.ENTITY_TICKING, AdmissionLedger.Lane.NORMAL, 0, false);
        run.chunkTransitions++; run.actions++;
    }
    private static void diagnostics(MinecraftServer server, Run run) {
        // The prepared platform is elevated above superflat terrain; retain normal AI without fatal off-edge wandering.
        for (int edge = 31; edge <= 59; edge++) {
            for (BlockPos fence : List.of(new BlockPos(edge, 64, 31), new BlockPos(edge, 64, 59), new BlockPos(31, 64, edge), new BlockPos(59, 64, edge)))
                server.overworld().setBlock(fence, Blocks.OAK_FENCE.defaultBlockState(), 3);
        }
        for (int index = 0; index < 100; index++) {
            Sheep sheep = EntityType.SHEEP.create(server.overworld());
            if (sheep == null) throw new IllegalStateException("Sheep allocation failed");
            sheep.moveTo(35 + index % 10 * 2, 64, 35 + index / 10 * 2, 0, 0); sheep.setPersistenceRequired();
            if (!server.overworld().addFreshEntity(sheep)) throw new IllegalStateException("Diagnostic sheep not added");
            run.sheep.add(sheep);
            JsonObject observation = new JsonObject(); observation.addProperty("diagnosticEntity", "minecraft:sheep"); observation.addProperty("entityId", sheep.getUUID().toString()); observation.addProperty("initialPosition", sheep.position().toString()); run.initial.add(observation);
        }
        for (int index = 0; index < 32; index++) {
            BlockPos hopper = new BlockPos(512 + 35 + index % 8 * 3, 65, 35 + index / 8 * 3);
            server.overworld().setBlock(hopper.below(), Blocks.BARREL.defaultBlockState(), 3);
            server.overworld().setBlock(hopper, Blocks.HOPPER.defaultBlockState(), 3);
            server.overworld().setBlock(hopper.above(), Blocks.BARREL.defaultBlockState(), 3);
            if (!(server.overworld().getBlockEntity(hopper.above()) instanceof net.minecraft.world.Container container)) throw new IllegalStateException("Diagnostic hopper input missing");
            for (int slot = 0; slot < container.getContainerSize(); slot++) container.setItem(slot, new ItemStack(Items.STONE, 64));
            run.hoppers.add(hopper);
            JsonObject observation = new JsonObject(); observation.addProperty("diagnosticHopper", coordinates(hopper)); observation.addProperty("inputBarrel", coordinates(hopper.above())); observation.addProperty("outputBarrel", coordinates(hopper.below())); observation.addProperty("initialStone", 27 * 64); observation.addProperty("sustainedActions", "Each200ticks count+externaldrainoutput;fillonlyemptyinputslotswith64stone"); run.initial.add(observation);
        }
        // A side repeater regenerates feedback to15: subtraction alternates15/0 instead of settling at positive attenuated dust power.
        for (int index = 0; index < 16; index++) {
            BlockPos clock = new BlockPos(1024 + 35 + index % 4 * 7, 64, 35 + index / 4 * 7);
            server.overworld().setBlock(clock, Blocks.COMPARATOR.defaultBlockState().setValue(DiodeBlock.FACING, Direction.WEST).setValue(ComparatorBlock.MODE, ComparatorMode.SUBTRACT), 3);
            server.overworld().setBlock(clock.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            for (BlockPos wire : List.of(clock.east(), clock.east().south(), clock.east().south(2), clock.south(2))) server.overworld().setBlock(wire, Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
            server.overworld().setBlock(clock.south(), Blocks.REPEATER.defaultBlockState().setValue(DiodeBlock.FACING, Direction.SOUTH), 3);
            run.clocks.add(clock);
            JsonObject observation = new JsonObject(); observation.addProperty("diagnosticComparator", coordinates(clock)); observation.addProperty("state", "facing=west,mode=subtract;rearwestredstoneblock,east+eastsouth+eastsouth2+south2feedbackwires,southrepeaterfacing=southregeneratesside15"); run.initial.add(observation);
        }
        run.report.addProperty("diagnosticFixture", "100 persistent real sheep;32 hopper input/output barrel pairs each input27x64stone;16 subtraction comparator feedback clocks verified by observed transitions");
    }
    private static void finish(MinecraftServer server, Run run) throws Exception {
        var measured = run.report.getAsJsonObject("measurementMetrics").getAsJsonObject("timers");
        JsonObject mspt = measured.getAsJsonObject("MSPT");
        if (mspt == null || mspt.get("count").getAsLong() == 0 || run.measurementTicks == 0) throw new IllegalStateException("Empty measurement");
        boolean latency = mspt.get("totalNanos").getAsDouble() / mspt.get("count").getAsLong() <= 50_000_000
                && mspt.get("p95Nanos").getAsLong() <= 50_000_000 && mspt.get("p99Nanos").getAsLong() <= 75_000_000 && mspt.get("p999Nanos").getAsLong() <= 150_000_000;
        JsonObject navigation = measured.getAsJsonObject("NAVIGATION_EXTERNAL");
        boolean needsNavigation = vanillaBackend(run) && navigation != null && navigation.get("count").getAsLong() > 0 && navigation.get("p99Nanos").getAsLong() > 2_000_000;
        run.report.addProperty("needsPortionedNavigation", needsNavigation); run.report.addProperty("latencyCriteriaPassed", latency);
        if (!ready(server, run)) throw new IllegalStateException("Wrong count or inactive physical residents at completion");
        if (scenario().startsWith("movement")) {
            JsonObject measuredActions = run.report.getAsJsonObject("measurementActions");
            JsonObject measuredDistances = run.report.getAsJsonObject("measurementDistanceTravelledByCitizen");
            if (navigation == null || navigation.get("count").getAsLong() == 0 || measuredActions.get("completedProductionMoves").getAsLong() < count() || measuredDistances.entrySet().stream().anyMatch(entry -> entry.getValue().getAsDouble() < 0.5)) throw new IllegalStateException("Movement did not genuinely execute all physical residents during measurement");
            JsonObject scheduler = run.report.getAsJsonObject("measurementMetrics").getAsJsonObject("scheduler");
            JsonObject colonies = scheduler.getAsJsonObject("colonies");
            for (UUID colony : run.colonies) {
                JsonObject service = colonies.getAsJsonObject(colony.toString());
                JsonObject before = run.report.getAsJsonObject("schedulerAtMeasurementStart").getAsJsonObject("colonies").getAsJsonObject(colony.toString());
                if (service != null && before != null && service.get("serviceCount").getAsLong() <= before.get("serviceCount").getAsLong()) throw new IllegalStateException("No actual measured colony service " + colony);
                if (service == null || service.get("serviceCount").getAsLong() == 0 || service.get("maxNormalColonyReadyServiceDelayTicks").getAsLong() > 200 || service.get("currentNormalColonyReadyServiceDelayTicks").getAsLong() > 200) throw new IllegalStateException("Continuous eligible colony was not serviced within200ticks: " + service);
            }
        }
        if (scenario().equals("damage") && run.damage < count()) throw new IllegalStateException("Missing real damage measurements");
        if (scenario().equals("block") && (run.completedBuilds == 0 || measured.getAsJsonObject("PHYSICAL_UNIT").get("count").getAsLong() == 0)) throw new IllegalStateException("No real public construction measured");
        if (scenario().equals("chunks") && (run.chunkTransitions == 0 || run.readyChunkCycles == 0 || measured.getAsJsonObject("CHUNK_UNIT").get("count").getAsLong() == 0)) throw new IllegalStateException("No not-ready to ticket-admitted/readiness chunk measurement");
        if (scenario().equals("diagnostic")) {
            for (long transitions : run.clockCounts) if (transitions < 2) throw new IllegalStateException("A diagnostic clock never demonstrably oscillated");
            for (long transferred : run.hopperCounts) if (transferred == 0) throw new IllegalStateException("A diagnostic hopper never transferred a physical item during measurement");
            if (run.sheep.size() != 100 || run.sheep.stream().anyMatch(sheep -> !sheep.isAlive()) || run.hopperTransferred < 32) throw new IllegalStateException("Diagnostic activity absent: sheep=" + run.sheep.size() + ", alive=" + run.sheep.stream().filter(Sheep::isAlive).count() + ", hopperItems=" + run.hopperTransferred);
        }
        for (SimulationLimits.Resource resource : SimulationLimits.Resource.values()) if (resource != SimulationLimits.Resource.CACHE_ENTRIES_PER_OWNER && run.runtime.core().admission().highWater(resource) > run.frozen.resource(resource)) throw new IllegalStateException("High-water exceeded frozen cap: " + resource);
        var pool = run.runtime.navigationBackendMetrics();
        if (((Number)pool.get("queryHighWater")).intValue() > 16 || ((Number)pool.get("nodeHighWater")).intValue() > 8192
                || ((Number)pool.get("openHighWater")).intValue() > 8192 || ((Number)pool.get("portionExpansionHighWater")).intValue() > 256
                || ((Number)pool.get("concurrentQueries")).intValue() != 0) throw new IllegalStateException("Search pool exceeded bounds or retained live query after drain: " + pool);
        run.report.addProperty("passed", latency && !needsNavigation); run.report.addProperty("scaleConfirmed", count() == 300 && latency && !needsNavigation && !diagnostic());
        run.done = true; evidence(server, run); server.halt(false);
    }
    private static boolean vanillaBackend(Run run) {
        return !"portioned-ground-a-star".equals(run.runtime.navigationBackendMetrics().get("backend"));
    }
    private static void evidence(MinecraftServer server, Run run) throws Exception {
        run.report.addProperty("warmupTicks", run.warmupTicks); run.report.addProperty("measurementTicks", run.measurementTicks);
        run.report.addProperty("observedCitizens", run.citizens.size()); run.report.addProperty("fixtureInputCpuNanos", run.fixtureNanos);
        run.report.addProperty("serverThreadAllocatedBytes", run.allocatedStart < 0 || run.allocatedEnd < 0 ? -1 : run.allocatedEnd - run.allocatedStart);
        run.report.addProperty("allocationScope", "Server thread only; -1 explicitly means unsupported. Not retained heap. No allocation profiler for other threads.");
        run.report.addProperty("heapUsedHighWaterBytes", run.heapHighWater);
        run.report.add("actions", actionCounts(run)); run.report.add("distanceTravelledByCitizen", json(run.distances));
        run.report.add("comparatorTransitionsByClock", json(run.clockCounts)); run.report.addProperty("realHopperTransferredItems", run.hopperTransferred);
        run.report.add("hopperTransferredItemsByFixture", json(run.hopperCounts));
        JsonArray sheepObservations = new JsonArray();
        for (Sheep sheep : run.sheep) { JsonObject observation = new JsonObject(); observation.addProperty("id", sheep.getUUID().toString()); observation.addProperty("alive", sheep.isAlive()); observation.addProperty("health", sheep.getHealth()); observation.addProperty("position", sheep.position().toString()); sheepObservations.add(observation); }
        run.report.add("finalDiagnosticSheep", sheepObservations);
        JsonArray readyResidents = new JsonArray();
        for (CitizenEntity citizen : run.citizens) {
            JsonObject readiness = new JsonObject(); readiness.addProperty("citizen", citizen.citizenId().toString());
            readiness.addProperty("admission", run.runtime.core().registry().citizen(citizen.citizenId()).admission().name());
            readiness.addProperty("chunkReady", run.runtime.chunks().ready(citizen.citizenId())); readiness.addProperty("chunkReason", run.runtime.chunks().reason(citizen.citizenId()).name()); readyResidents.add(readiness);
        }
        run.report.add("finalAdmissionReadiness", readyResidents);
        run.report.add("finalMetrics", json(run.runtime.minecraftMetrics().snapshot()));
        run.report.add("actualFinalProfile", limits(run.runtime.core().admission().limits()));
        JsonArray finalCitizens = new JsonArray();
        for (CitizenEntity citizen : run.citizens) {
            JsonObject observation = new JsonObject(); observation.addProperty("id", citizen.citizenId().toString()); observation.addProperty("position", citizen.position().toString()); observation.addProperty("health", citizen.getHealth());
            int stairs = 0; for (int slot = 0; slot < CitizenEntity.INVENTORY_SIZE; slot++) if (citizen.inventory().getItem(slot).is(Items.OAK_STAIRS)) stairs += citizen.inventory().getItem(slot).getCount();
            observation.addProperty("realOakStairs", stairs); finalCitizens.add(observation);
        }
        run.report.add("finalCitizens", finalCitizens); Files.createDirectories(reportPath().getParent());
        Files.writeString(reportPath(), new GsonBuilder().setPrettyPrinting().create().toJson(run.report));
    }
    private static JsonObject actionCounts(Run run) {
        JsonObject actions = new JsonObject(); actions.addProperty("externalInputActionsAndItems", run.actions); actions.addProperty("actualNonlethalDamage", run.damage); actions.addProperty("fixtureRemovedCompletedStairs", run.changedBlocks); actions.addProperty("completedProductionMoves", run.completedMoves); actions.addProperty("completed16StairBuilds", run.completedBuilds); actions.addProperty("notReadyTicketRequests", run.chunkTransitions); actions.addProperty("observedReadyTicketCycles", run.readyChunkCycles); actions.addProperty("comparatorPoweredTransitions", run.clockTransitions);
        actions.addProperty("finitePublicMoveRequests", run.moveRequests); actions.addProperty("public16StairBuildRequests", run.buildRequests); actions.addProperty("externalOakStairsInput", run.externalStairs); return actions;
    }
    private static JsonObject hardware() throws Exception {
        JsonObject hardware = new JsonObject(); var os = ManagementFactory.getOperatingSystemMXBean();
        hardware.addProperty("os", os.getName() + " " + os.getVersion() + " " + os.getArch()); hardware.addProperty("logicalProcessors", os.getAvailableProcessors());
        if (os instanceof com.sun.management.OperatingSystemMXBean extended) { hardware.addProperty("physicalMemoryBytes", extended.getTotalMemorySize()); hardware.addProperty("freePhysicalMemoryBytes", extended.getFreeMemorySize()); }
        String cpu = System.getenv("PROCESSOR_IDENTIFIER");
        if (cpu == null && Files.isRegularFile(Path.of("/proc/cpuinfo"))) try { cpu = Files.readAllLines(Path.of("/proc/cpuinfo")).stream().filter(line -> line.startsWith("model name")).findFirst().orElse("unavailable"); } catch (Exception ignored) { cpu = "unavailable"; }
        hardware.addProperty("cpuModel", cpu == null ? "unavailable" : cpu); hardware.addProperty("javaVendor", System.getProperty("java.vendor")); hardware.addProperty("javaVersion", System.getProperty("java.version")); hardware.addProperty("vm", System.getProperty("java.vm.name")); hardware.addProperty("maximumHeapBytes", Runtime.getRuntime().maxMemory()); hardware.addProperty("initialHeapBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getInit()); hardware.add("jvmArguments", json(ManagementFactory.getRuntimeMXBean().getInputArguments()));
        if (os.getName().toLowerCase(java.util.Locale.ROOT).contains("windows")) {
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors | ConvertTo-Json -Compress").redirectErrorStream(true).start();
            if (process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) {
                String details = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                try { hardware.add("processors", com.google.gson.JsonParser.parseString(details)); } catch (RuntimeException failure) { hardware.addProperty("processorQuery", details); }
            } else { process.destroy(); hardware.addProperty("processorQuery", "unavailable"); }
        }
        hardware.addProperty("minecraft", net.minecraft.SharedConstants.getCurrentVersion().getName()); hardware.addProperty("neoForge", net.neoforged.fml.ModList.get().getModContainerById("neoforge").orElseThrow().getModInfo().getVersion().toString()); hardware.addProperty("colonyloom", net.neoforged.fml.ModList.get().getModContainerById("colonyloom").orElseThrow().getModInfo().getVersion().toString()); return hardware;
    }
    private static long allocatedBytes() {
        var bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean extended) || !extended.isThreadAllocatedMemorySupported()) return -1;
        if (!extended.isThreadAllocatedMemoryEnabled()) extended.setThreadAllocatedMemoryEnabled(true);
        return extended.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }
    private static JsonObject memory() {
        JsonObject observation = new JsonObject(); var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        observation.addProperty("heapUsed", heap.getUsed()); observation.addProperty("heapCommitted", heap.getCommitted()); observation.addProperty("heapMax", heap.getMax());
        JsonArray collectors = new JsonArray(); for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) { JsonObject gc = new JsonObject(); gc.addProperty("name", bean.getName()); gc.addProperty("collections", bean.getCollectionCount()); gc.addProperty("collectionTimeMillis", bean.getCollectionTime()); collectors.add(gc); }
        observation.add("gc", collectors); return observation;
    }
    private static JsonObject limits(SimulationLimits limits) {
        JsonObject profile = new JsonObject(); for (SimulationLimits.Resource resource : SimulationLimits.Resource.values()) profile.addProperty(resource.key(), limits.resource(resource)); for (SimulationLimits.Budget budget : SimulationLimits.Budget.values()) profile.addProperty(budget.key(), limits.budget(budget)); profile.addProperty("budgets.maxManagedNanos", limits.maxManagedNanos()); profile.addProperty("uncalibratedCategories", "graph/storage/views remain experimental"); return profile;
    }
    private static boolean sameLimits(SimulationLimits first, SimulationLimits second) { return first.resources().equals(second.resources()) && first.budgets().equals(second.budgets()) && first.maxManagedNanos() == second.maxManagedNanos(); }
    private static com.google.gson.JsonElement json(Object value) { return new GsonBuilder().create().toJsonTree(value); }
    private static String coordinates(BlockPos position) { return position.getX() + " " + position.getY() + " " + position.getZ(); }
    private static UUID uuid(String output, String key) {
        var match = Pattern.compile("(?:^|[\\s\\[,])" + Pattern.quote(key) + "=([0-9a-fA-F-]{36})(?=[\\s\\],]|$)").matcher(output);
        if (!match.find()) throw new IllegalStateException("Missing " + key + " UUID in " + output); return UUID.fromString(match.group(1));
    }
    private static String command(MinecraftServer server, Run run, String command) throws Exception {
        Capture capture = new Capture(); int result = server.getCommands().getDispatcher().execute(command, run.actor.createCommandSourceStack().withPermission(2).withSource(capture));
        if (result != 1) throw new IllegalStateException("Public command rejected: " + command + " " + capture.messages); return String.join("\n", capture.messages);
    }
    private static void verifyMetricsAccess(MinecraftServer server, Run run) throws Exception {
        command(server, run, "colonyloom metrics server");
        if (count() == 0) return;
        GameProfile guestProfile = new GameProfile(UUID.randomUUID(), "PlatformViewer");
        server.getProfileCache().add(guestProfile);
        ServerPlayer guest = new ServerPlayer(server, server.overworld(), guestProfile, ClientInformation.createDefault());
        command(server, run, "colonyloom member set " + run.colonies[0] + " PlatformViewer viewer");
        Capture capture = new Capture();
        var source = guest.createCommandSourceStack().withPermission(0).withSource(capture);
        if (server.getCommands().getDispatcher().execute("colonyloom metrics " + run.colonies[0], source) != 1) throw new IllegalStateException("Viewer metrics denied");
        String visible = String.join("\n", capture.messages);
        if (visible.contains(run.colonies[1].toString()) || visible.contains("timers=") || visible.contains("admission=") || visible.contains("navigationBackend=")) throw new IllegalStateException("Colony metrics leaked global/other-colony state");
        capture.messages.clear();
        if (server.getCommands().getDispatcher().execute("colonyloom metrics " + run.colonies[1], source) != 0) throw new IllegalStateException("Viewer accessed another colony");
        capture.messages.clear();
        try {
            server.getCommands().getDispatcher().execute("colonyloom metrics server", source);
            throw new IllegalStateException("Nonoperator server metrics accessible");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { }
        command(server, run, "colonyloom member set " + run.colonies[0] + " PlatformViewer none");
        capture.messages.clear();
        if (server.getCommands().getDispatcher().execute("colonyloom metrics " + run.colonies[0], source) != 0) throw new IllegalStateException("Revoked viewer retained metrics access");
        run.report.addProperty("metricsAuthorization", "public operator server/readable colony;viewer scoped;other colony/operator server/revoked viewer denied");
    }
    private static final class Capture implements CommandSource {
        final List<String> messages = new ArrayList<>();
        public void sendSystemMessage(Component component) { messages.add(component.getString()); }
        public boolean acceptsSuccess() { return true; } public boolean acceptsFailure() { return true; } public boolean shouldInformAdmins() { return false; }
    }
}
