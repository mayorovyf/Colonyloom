package io.github.kpuctajluk.colonyloom.gametest;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.action.EffectRecord;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime;
import io.github.kpuctajluk.colonyloom.minecraft.storage.StorageTransferExecutor;
import io.github.kpuctajluk.colonyloom.neoforge.ConstructionExecutorEvent;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Real public delivery, natural courier navigation, and independently durable native crash sides. */
final class TransferRecoveryScenario {
    private static final UUID OWNER = UUID.nameUUIDFromBytes("OfflinePlayer:TransferRecoveryOwner".getBytes(StandardCharsets.UTF_8));
    private static final int ORIGIN = 4096;
    private static final BlockPos SOURCE = new BlockPos(ORIGIN + 8, 64, 8);
    private static final BlockPos DESTINATION = new BlockPos(ORIGIN + 24, 64, 8);
    private static final BlockPos RETURN = new BlockPos(ORIGIN + 12, 64, 12);
    private static final String MANIFEST = "colonyloom-transfer-recovery-fixture.nbt";
    private static final List<String> SCENARIOS = List.of("transfer_clean", "transfer_before_effect", "transfer_after_source_change",
            "transfer_after_destination_change", "transfer_after_fact_before_notify");
    private final Map<MinecraftServer, Run> runs = new IdentityHashMap<>();

    private static final class Run {
        MinecraftServerRuntime runtime;
        ServerPlayer actor;
        CompoundTag manifest;
        UUID operation;
        int ticks, stableTicks;
        boolean done, prepared, verified, accepted;
        boolean dependencyReleased;
        boolean dependencyConfigured;
        final UUID readyOwner=UUID.randomUUID(),idleOwner=UUID.randomUUID(),observationOwner=UUID.randomUUID();
        Counts last;
    }
    private record Counts(int source, int courier, int destination, int returned) {}

    TransferRecoveryScenario() {
        NeoForge.EVENT_BUS.addListener(this::configure);
        NeoForge.EVENT_BUS.addListener(this::prepareDependencies);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, this::stopped);
    }
    private static String scenario() { return System.getProperty("colonyloom.test.recoveryScenario", ""); }
    private static String phase() { return System.getProperty("colonyloom.test.recoveryPhase", ""); }
    private static boolean selected() { return SCENARIOS.contains(scenario()); }
    private static boolean clean() { return scenario().equals("transfer_clean"); }
    private static Path world(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT); }
    private static void guard(MinecraftServer server) {
        if (!server.isDedicatedServer() || !selected() || !List.of("initialize", "exercise", "verify", "accept").contains(phase())
                || !Files.isRegularFile(world(server).resolve("colonyloom-test-world")))
            throw new IllegalStateException("Transfer recovery requires exact scenario/phase and marked disposable dedicated world");
    }
    private void configure(ConstructionExecutorEvent event) {
        if (!selected()) return;
        Run run = runs.computeIfAbsent(event.server(), ignored -> new Run());
        run.runtime = event.runtime();
        if (phase().equals("exercise") && !clean() && Boolean.getBoolean("colonyloom.testFaults"))
            event.transferObserver((point, context) -> fault(event.server(), run, point, context));
    }
    private void prepareDependencies(ServerTickEvent.Pre event) {
        if(!clean()||!phase().equals("verify"))return;
        var run=runs.get(event.getServer());if(run==null||run.runtime==null||run.dependencyConfigured)return;
        try {
            guard(event.getServer());preload(event.getServer(),run);
            initializeDependencyDomains(run);
        }catch(Exception failure){throw new IllegalStateException("Dependency fixture initialization failed",failure);}
    }
    private static void initializeDependencyDomains(Run run) {
                var limits=run.runtime.core().admission().limits()
                        .withResource(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.LOADED_FOOTPRINT,64)
                        .withResource(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.BLOCK_TICKING,24)
                        .withResource(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.ENTITY_TICKING,4);
                run.runtime.core().updateLimits(limits);run.runtime.physicalLimitsUpdated();
                var chunks=run.runtime.chunks();
                chunks.request(run.readyOwner,id(run,"independentColony"),List.of(new io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey("minecraft:overworld",0,0)),
                        io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager.Readiness.ENTITY_TICKING,io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.NORMAL,0,false);
                chunks.request(run.idleOwner,id(run,"independentColony"),List.of(new io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey("minecraft:overworld",512,512)),
                        io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager.Readiness.ENTITY_TICKING,io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.NORMAL,0,false);
                chunks.setProtection(run.readyOwner,true,false,false);chunks.setProtection(run.idleOwner,true,false,false);
                run.dependencyConfigured=true;
    }
    private void tick(ServerTickEvent.Post event) {
        if (!selected()) return;
        MinecraftServer server = event.getServer();
        Run run = runs.computeIfAbsent(server, ignored -> new Run());
        if (run.done) return;
        try {
            guard(server);
            if (++run.ticks > 6000) throw new IllegalStateException("Transfer recovery timeout manifest=" + run.manifest
                    + " diagnostics=" + (run.runtime == null ? "runtime missing" : run.runtime.minecraftMetrics().snapshot(null)));
            if (run.runtime == null) return;
            if (run.actor == null) {
                var profile = new GameProfile(OWNER, "TransferRecoveryOwner");
                server.getProfileCache().add(profile);
                run.actor = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
                run.actor.moveTo(ORIGIN + 8.5, 64, 14.5, 0, 0);
                server.overworld().setChunkForced(0,0,true);
                if(!(clean()&&phase().equals("verify")))for (int x = ORIGIN >> 4; x < (ORIGIN >> 4) + 2; x++) for (int z = 0; z < 2; z++)
                    server.overworld().setChunkForced(x, z, true);
            }
            if (!phase().equals("initialize")) preload(server, run);
            if(clean()&&phase().equals("verify")&&!run.dependencyReleased) {
                if(!independentProgress(server,run))return;
                run.runtime.chunks().setProtection(run.idleOwner,false,false,false);
                run.dependencyReleased=true;
            }
            if(clean()&&phase().equals("verify")&&run.runtime.core().registry().supply().demand(id(run,"demand")).snapshot().fulfilled()==8) {
                // Terminal delivery releases its domain. Keep the original embodiment observable
                // during the no-replay window without letting idle C evict the verifier's reads.
                var position=run.runtime.core().registry().citizen(id(run,"citizen")).lastKnownPosition();
                run.runtime.chunks().request(run.observationOwner,id(run,"colony"),List.of(
                        new io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey(position.dimension(),position.x()>>4,position.z()>>4),
                        new io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey("minecraft:overworld",DESTINATION.getX()>>4,DESTINATION.getZ()>>4)),
                        io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager.Readiness.ENTITY_TICKING,
                        io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.NORMAL,10,true);
                run.runtime.chunks().setProtection(run.observationOwner,true,false,false);
            }
            if (run.ticks < 60 || !server.overworld().isPositionEntityTicking(DESTINATION)
                    || (phase().equals("initialize")&&!server.overworld().isPositionEntityTicking(new BlockPos(8,64,8)))) return;
            if (phase().equals("initialize")) { initialize(server, run); return; }
            CitizenEntity npc = entityOrNull(server, run);
            // UNKNOWN is never treated as empty and never replaced with another courier/request.
            if (npc == null || !server.overworld().isPositionEntityTicking(npc.blockPosition())) return;
            identity(server, run, npc);
            if (phase().equals("exercise")) exercise(server, run);
            else if (clean()) cleanContinuation(server, run);
            else if (phase().equals("verify")) verify(server, run);
            else accept(server, run);
        } catch (Exception failure) {
            run.done = true;
            try { fact(server, run, "failure", false, failure.toString()); }
            catch (Exception evidence) { failure.addSuppressed(evidence); }
            org.slf4j.LoggerFactory.getLogger(TransferRecoveryScenario.class).error("Transfer recovery scenario failed", failure);
            server.halt(false);
        }
    }
    private static void initialize(MinecraftServer server, Run run) throws Exception {
        if (run.manifest == null) {
            require(server, run, !Files.exists(world(server).resolve(MANIFEST)), "new_disposable_fixture", world(server).toString());
            var level = server.overworld();
            for (int x = ORIGIN + 4; x <= ORIGIN + 28; x++) for (int z = 4; z <= 17; z++) {
                level.setBlock(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 64; y <= 67; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
            var manifest = new CompoundTag();
            manifest.putString("scenario", scenario()); manifest.putUUID("owner", OWNER);
            manifest.putUUID("colony", uuid(command(server, run, "colonyloom colony create TransferRecoveryFixture " + ORIGIN + " 64 0 " + (ORIGIN + 31) + " 64 31"), "colony"));
            run.manifest = manifest;
            level.setBlock(SOURCE, Blocks.CHEST.defaultBlockState(), 3);
            level.setBlock(DESTINATION, Blocks.BARREL.defaultBlockState(), 3);
            level.setBlock(RETURN, Blocks.BARREL.defaultBlockState(), 3);
            register(server, run, SOURCE, "warehouse", "source");
            register(server, run, DESTINATION, "construction", "destination");
            register(server, run, RETURN, "return", "return");
            String created = command(server, run, "colonyloom citizen create " + id(run, "colony") + " " + (ORIGIN + 10) + " 64 15");
            manifest.putUUID("citizen", uuid(created, "citizen")); manifest.putUUID("entity", uuid(created, "entity"));
            manifest.putLong("epoch", entity(server, run).bindingEpoch());
            command(server, run, "colonyloom citizen assign " + id(run, "citizen") + " colonyloom:courier");
            container(server, SOURCE).setItem(0, new ItemStack(Items.OAK_PLANKS, 8));
            container(server, SOURCE).setChanged();
            manifest.putUUID("demand", uuid(command(server, run, "colonyloom delivery request " + id(run, "colony") + " "
                    + coordinates(SOURCE) + " " + coordinates(DESTINATION) + " minecraft:oak_planks 8"), "demand"));
            require(server, run, counts(server, run).equals(new Counts(8, 0, 0, 0)), "initial_native_resources", "source8 destination/courier/return empty");
            if(clean()) {
                for(int x=3;x<18;x++)for(int z=3;z<18;z++) {
                    level.setBlock(new BlockPos(x,63,z),Blocks.STONE.defaultBlockState(),3);
                    for(int y=64;y<68;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
                }
                var independent=uuid(command(server,run,"colonyloom colony create IndependentRecovery 0 64 0 31 64 31"),"colony");
                manifest.putUUID("independentColony",independent);
                var spawned=command(server,run,"colonyloom citizen create "+independent+" 8 64 12");
                var builder=uuid(spawned,"citizen");manifest.putUUID("independentBuilder",builder);
                command(server,run,"colonyloom citizen assign "+builder+" colonyloom:builder");
                ((CitizenEntity)level.getEntity(uuid(spawned,"entity"))).inventory().setItem(0,new ItemStack(Items.OAK_STAIRS,4));
                level.setBlock(new BlockPos(7,64,8),Blocks.BARREL.defaultBlockState(),3);
                command(server,run,"colonyloom storage register "+independent+" 7 64 8 construction");
            }
            return;
        }
        var orders = run.runtime.core().registry().supply().deliveries().stream().filter(o -> o.ownerDemandId().equals(id(run, "demand"))).toList();
        if (orders.isEmpty() || orders.getFirst().workId() == null) return;
        require(server, run, orders.size() == 1 && counts(server, run).equals(new Counts(8, 0, 0, 0)),
                "accepted_unfinished_public_request", orders.toString());
        var order = orders.getFirst();
        run.manifest.putUUID("order", order.id()); run.manifest.putUUID("work", order.workId());
        var demand = run.runtime.core().registry().supply().demand(id(run, "demand")).snapshot();
        require(server, run, demand.required() == 8 && demand.covered() == 8 && demand.fulfilled() == 0 && order.transferred() == 0,
                "initial_single_coverage", demand.toString());
        rememberEntityChunk(server, run);
        write(world(server).resolve(MANIFEST), run.manifest);
        finish(server, run);
    }
    private static void register(MinecraftServer server, Run run, BlockPos pos, String role, String key) throws Exception {
        command(server, run, "colonyloom storage register " + id(run, "colony") + " " + coordinates(pos) + " " + role);
        var registration = run.runtime.core().registry().storage().registrations(id(run, "colony")).stream()
                .filter(r -> r.address().x() == pos.getX() && r.address().y() == pos.getY() && r.address().z() == pos.getZ()).findFirst().orElseThrow();
        run.manifest.putUUID(key + "Registration", registration.id());
        run.manifest.putUUID(key + "Storage", registration.storages().getFirst().identity());
    }
    private static void exercise(MinecraftServer server, Run run) throws Exception {
        if (!clean()) return; // Fault cases finish only at the genuine first pickup's selected observer.
        if (!counts(server, run).equals(new Counts(0, 8, 0, 0))) return;
        var demand = run.runtime.core().registry().supply().demand(id(run, "demand")).snapshot();
        require(server, run, demand.fulfilled() == 0 && demand.covered() == 8 && run.runtime.core().registry().supply().hasCargo(id(run, "order")),
                "clean_in_transit_checkpoint", demand.toString());
        for(int x=ORIGIN>>4;x<(ORIGIN>>4)+2;x++)for(int z=0;z<2;z++)server.overworld().setChunkForced(x,z,false);
        rememberEntityChunk(server, run);
        write(world(server).resolve(MANIFEST), run.manifest);
        finish(server, run);
    }
    private void fault(MinecraftServer server, Run run, StorageTransferExecutor.FaultPoint point, ActionContext context) {
        try {
            guard(server);
            if (!Boolean.getBoolean("colonyloom.testFaults") || !phase().equals("exercise") || clean()) throw new IllegalStateException("Fault gate disabled");
            preload(server, run);
            if (!context.colonyId().equals(id(run, "colony")) || !context.citizenId().equals(id(run, "citizen"))) return;
            EffectRecord effect = run.runtime.core().registry().effects().snapshots().stream()
                    .filter(e -> e.transfer() != null && e.workId() != null && e.workId().equals(id(run, "work"))
                            && e.transfer().source().storage().identity().equals(id(run, "sourceStorage"))
                            && e.transfer().destination().storage().identity().equals(id(run, "citizen")))
                    .findFirst().orElse(null);
            if (effect == null) return;
            if (point == StorageTransferExecutor.FaultPoint.BEFORE_EFFECT) {
                require(server, run, !run.prepared && effect.state() == EffectRecord.State.PREPARED
                                && effect.transfer().maximum() == 8 && effect.transfer().sourceBefore() == 8
                                && effect.transfer().destinationBefore() == 0 && effect.transfer().destination().storage().bindingEpoch() == run.manifest.getLong("epoch"),
                        "first_source_to_courier_pickup", effect.toString());
                run.operation = effect.operationId(); run.prepared = true;
                run.manifest.putUUID("operation", run.operation);
                identity(server, run, entity(server, run));
                String status = command(server, run, "colonyloom status " + id(run, "colony"));
                require(server, run, !status.contains("recoveryBlocked=true") && counts(server, run).equals(new Counts(8, 0, 0, 0)),
                        "pre_effect_status", status);
                dirty(server, run, "already_persisted_dirty_marker_before_effect");
                run.runtime.persistence().persistSnapshot();
                require(server, run, savedEffect(server, run).getString("state").equals("PREPARED"), "durable_prepared_effect", savedEffect(server, run).toString());
                // Baseline entity location/inventory are separately native-saved without saving logical facts.
                rememberEntityChunk(server, run);
                RecoveryNativeState.saveEntities(server);
                RecoveryNativeState.saveBlocks(server);
                require(server, run, durableCounts(server, run).equals(new Counts(8, 0, 0, 0)), "native_baseline_flushed", "raw MCA inventories source8 courier0 destination0 return0");
            }
            if (!run.prepared || !effect.operationId().equals(run.operation)) throw new IllegalStateException("Not first manifest pickup");
            if (!point.name().toLowerCase(java.util.Locale.ROOT).equals(scenario().substring("transfer_".length()))) return;
            Counts expected = switch (point) {
                case BEFORE_EFFECT -> new Counts(8, 0, 0, 0);
                case AFTER_SOURCE_CHANGE -> new Counts(0, 0, 0, 0);
                case AFTER_DESTINATION_CHANGE, AFTER_FACT_BEFORE_NOTIFY -> new Counts(0, 8, 0, 0);
            };
            require(server, run, counts(server, run).equals(expected), "named_boundary_actual_native_counts", point.toString());
            if (point != StorageTransferExecutor.FaultPoint.BEFORE_EFFECT) RecoveryNativeState.saveBlocks(server);
            if (point == StorageTransferExecutor.FaultPoint.AFTER_DESTINATION_CHANGE || point == StorageTransferExecutor.FaultPoint.AFTER_FACT_BEFORE_NOTIFY)
                RecoveryNativeState.saveEntities(server);
            if (point == StorageTransferExecutor.FaultPoint.AFTER_FACT_BEFORE_NOTIFY) run.runtime.persistence().persistSnapshot();
            Counts durable = durableCounts(server, run);
            require(server, run, durable.equals(expected), "independent_native_sides_flushed", "native block worker and entity flush(true); raw MCA counts=" + durable);
            CompoundTag savedEffect = savedEffect(server, run);
            require(server, run, savedEffect.getString("state").equals(point == StorageTransferExecutor.FaultPoint.AFTER_FACT_BEFORE_NOTIFY ? "OBSERVED" : "PREPARED"),
                    "durable_original_effect_fact", savedEffect.toString());
            CompoundTag transfer = savedEffect.getCompound("transfer");
            require(server, run, transfer.getInt("sourceBefore") == 8 && transfer.getInt("destinationBefore") == 0
                            && transfer.getInt("sourceAfter") == (point == StorageTransferExecutor.FaultPoint.AFTER_FACT_BEFORE_NOTIFY ? 0 : 8)
                            && transfer.getInt("destinationAfter") == (point == StorageTransferExecutor.FaultPoint.AFTER_FACT_BEFORE_NOTIFY ? 8 : 0)
                            && transfer.getInt("extracted") == (point == StorageTransferExecutor.FaultPoint.AFTER_FACT_BEFORE_NOTIFY ? 8 : 0)
                            && transfer.getInt("inserted") == (point == StorageTransferExecutor.FaultPoint.AFTER_FACT_BEFORE_NOTIFY ? 8 : 0),
                    "fact_boundary_not_logical_notify", transfer.toString());
            CompoundTag demand = savedRecord(server, "demands", "id", id(run, "demand"));
            CompoundTag order = savedRecord(server, "deliveries", "id", id(run, "order"));
            require(server, run, demand.getLong("fulfilled") == 0 && demand.getLong("covered") == 8 && order.getLong("transferred") == 0,
                    "no_synthetic_delivery_completion", demand + " order=" + order);
            run.manifest.put("durableEffect", savedEffect.copy());
            run.manifest.putInt("durableSource", durable.source()); run.manifest.putInt("durableCourier", durable.courier());
            run.manifest.putInt("durableDestination", durable.destination()); run.manifest.putInt("durableReturn", durable.returned());
            run.manifest.putLong("durableCovered", demand.getLong("covered"));
            run.manifest.putLong("durableFulfilled", demand.getLong("fulfilled"));
            write(world(server).resolve(MANIFEST), run.manifest);
            dirty(server, run, "dirty_marker_before_halt");
            fact(server, run, "expected_fault", true, "named=" + point + " expectedExit=97 independently durable counts=" + durable);
            fact(server, run, "exercise_complete", true, "genuine production pickup callback; expected immediate halt97");
            guard(server);
            if (!Boolean.getBoolean("colonyloom.testFaults") || !phase().equals("exercise")
                    || !point.name().toLowerCase(java.util.Locale.ROOT).equals(scenario().substring("transfer_".length())))
                throw new IllegalStateException("Final destructive fault gate changed");
            Runtime.getRuntime().halt(97);
        } catch (Exception failure) { throw new IllegalStateException("Transfer crash evidence failed", failure); }
    }
    private static boolean independentProgress(MinecraftServer server,Run run)throws Exception {
        var registry=run.runtime.core().registry();
        var demand=registry.supply().demand(id(run,"demand")).snapshot();
        requireState(entityOrNull(server,run)==null&&demand.covered()==8&&demand.fulfilled()==0
                &&registry.supply().deliveries().stream().filter(order -> order.ownerDemandId().equals(id(run,"demand"))).count()==1,
                "UNKNOWN original courier cargo lost coverage or caused replacement extraction/order");
        var chunks=run.runtime.chunks();
        if(!chunks.ready(run.readyOwner)||!chunks.ready(run.idleOwner))return false;
        requireState(chunks.footprint()<=64,"Dependency wait exceeded global footprint");
        if(!run.manifest.hasUUID("independentWork")) {
            var builder=registry.citizen(id(run,"independentBuilder"));
            if(builder.readiness()!=io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord.Readiness.READY)return false;
            var work=uuid(command(server,run,"colonyloom build "+id(run,"independentColony")+" colonyloom:test_four_stairs 8 64 8 0"),"work");
            run.manifest.putUUID("independentWork",work);write(world(server).resolve(MANIFEST),run.manifest);
            return false;
        }
        var work=registry.workBoard().work(id(run,"independentWork"));
        if(work.state()!=WorkOrder.State.COMPLETED)return false;
        int blocks=0;for(int x=8;x<12;x++)if(server.overworld().getBlockState(new BlockPos(x,64,8)).is(Blocks.OAK_STAIRS))blocks++;
        require(server,run,blocks==4&&demand.covered()==8&&demand.fulfilled()==0,"independent_construction_while_courier_unknown",
                "A completed four native stairs; original B courier absent and exact8 cargo coverage retained; no replacement order");
        return true;
    }
    private static void cleanContinuation(MinecraftServer server, Run run) throws Exception {
        var registry = run.runtime.core().registry();
        requireState(!registry.colony(id(run, "colony")).recoveryBlocked(), "Clean checkpoint was quarantined");
        if (registry.supply().demand(id(run, "demand")).snapshot().fulfilled() != 8) return;
        Counts exact = new Counts(0, 0, 8, 0);
        requireState(counts(server, run).equals(exact), "Clean delivery must finish exactly eight once");
        sameOrder(server, run);
        if (++run.stableTicks < 100) return;
        require(server, run, counts(server, run).equals(exact) && registry.supply().delivery(id(run, "order")).transferred() == 8,
                "clean_restart_exact_eight_no_second_order", "same original demand/order/work/courier/epoch; no accept-world required");
        rememberEntityChunk(server, run);
        write(world(server).resolve(MANIFEST), run.manifest);
        if(phase().equals("verify"))require(server,run,run.dependencyReleased&&!run.runtime.chunks().admitted(run.idleOwner)
                &&run.runtime.chunks().ready(run.readyOwner)&&run.runtime.chunks().footprint()<=64,"safe_idle_C_withdrawal_restored_original_B_delivery",
                "protected A remains ready; idle C desire retained but unadmitted; original B courier delivered exactly8 after safe admission");
        finish(server, run);
    }
    private static void verify(MinecraftServer server, Run run) throws Exception {
        String status = command(server, run, "colonyloom status " + id(run, "colony"));
        requireState(status.contains("recoveryBlocked=true") && status.contains("RECOVERY_AMBIGUOUS"), "Crash must remain fail-closed: " + status);
        Counts expected = expected(run);
        requireState(counts(server, run).equals(expected), "Native crash sides changed before acceptance");
        sameOrder(server, run);
        var retainedDemand = run.runtime.core().registry().supply().demand(id(run, "demand")).snapshot();
        requireState(retainedDemand.required() == 8 && retainedDemand.fulfilled() == run.manifest.getLong("durableFulfilled")
                && retainedDemand.covered() == run.manifest.getLong("durableCovered"), "Logical fulfilment/coverage replayed or abandoned before acceptance");
        requireState(run.runtime.core().registry().supply().delivery(id(run, "order")).transferred() == 0, "Pickup witness replayed as delivery");
        requireState(savedEffect(server, run).equals(run.manifest.getCompound("durableEffect")), "Original independently saved witness changed");
        var liveEffect = run.runtime.core().registry().effects().get(id(run, "operation"));
        CompoundTag original = run.manifest.getCompound("durableEffect");
        requireState(liveEffect != null && liveEffect.state().name().equals(original.getString("state"))
                && liveEffect.bindingEpoch() == run.manifest.getLong("epoch")
                && liveEffect.transfer().sourceAfter() == original.getCompound("transfer").getInt("sourceAfter")
                && liveEffect.transfer().destinationAfter() == original.getCompound("transfer").getInt("destinationAfter"),
                "Original runtime transfer witness changed or was replaced");
        if (!run.verified) {
            run.verified = true;
            require(server, run, durableCounts(server, run).equals(expected), "restart_exact_durable_physical_sides", "raw MCA/native counts=" + expected);
            require(server, run, true, "crash_restart_ambiguous", status);
            require(server, run, true, "retained_original_effect_and_goal", savedEffect(server, run).toString());
            String inspect = command(server, run, "colonyloom recovery inspect " + id(run, "colony"));
            require(server, run, inspect.contains("actualPhysical=") && inspect.contains(id(run, "operation").toString()), "public_inspect_original_physical_sides", inspect);
        }
        hold(run, expected);
        if (run.stableTicks < 100) return;
        require(server, run, true, "hundred_tick_no_automatic_reextraction", "same physical counts and logical goal; colony remains recoveryBlocked; no replacement courier/order");
        run.manifest.putBoolean("verified", true);
        write(world(server).resolve(MANIFEST), run.manifest);
        finish(server, run);
    }
    private static void accept(MinecraftServer server, Run run) throws Exception {
        requireState(run.manifest.getBoolean("verified"), "Separate verify phase required before accept");
        Counts expected = expected(run);
        requireState(counts(server, run).equals(expected), "Physical sides changed across verify checkpoint");
        if (!run.accepted) {
            requireState(run.runtime.core().registry().colony(id(run, "colony")).recoveryBlocked(), "Verify must stop while still recovery blocked");
            String inspect = command(server, run, "colonyloom recovery inspect " + id(run, "colony"));
            require(server, run, inspect.contains("actualPhysical=") && inspect.contains(id(run, "operation").toString()), "final_public_reinspection", inspect);
            UUID checkpoint = run.runtime.core().registry().colony(id(run, "colony")).recoveryCheckpointId();
            String accepted = command(server, run, "colonyloom recovery accept-world " + id(run, "colony") + " " + checkpoint);
            require(server, run, accepted.contains("without item creation/removal/compensation") && counts(server, run).equals(expected), "accept_world_preserves_actual_property", accepted);
            run.accepted = true;
        }
        var registry = run.runtime.core().registry();
        requireState(!registry.colony(id(run, "colony")).recoveryBlocked(), "Public acceptance did not clear recovery quarantine");
        sameOrder(server, run);
        var demand = registry.supply().demand(id(run, "demand")).snapshot();
        requireState(demand.status().name().equals("CANCELLED") && demand.fulfilled() == 0 && demand.covered() == 0
                && registry.supply().delivery(id(run, "order")).state().name().equals("CANCELLED")
                && registry.workBoard().work(id(run, "work")).state().name().equals("CANCELLED"), "Approved accept-world must discard unfinished goals without compensation");
        hold(run, expected);
        if (run.stableTicks < 100) return;
        require(server, run, counts(server, run).equals(expected), "accepted_hundred_tick_no_replay", "original already-physical cargo retained; no replay/re-extraction/inverse transfer");
        require(server, run, savedRecord(server, "demands", "id", id(run, "demand")).getString("status").equals("CANCELLED")
                        && savedRecord(server, "deliveries", "id", id(run, "order")).getString("state").equals("CANCELLED")
                        && savedEffect(server, run).getString("state").equals("ACCEPTED"),
                "durable_approved_goal_discard", "unfulfilled goal8 abandoned; physical loss at source-only crash not compensated; conserved output not delivered twice");
        finish(server, run);
    }
    private static void hold(Run run, Counts observed) {
        if (!observed.equals(run.last)) { run.last = observed; run.stableTicks = 0; }
        run.stableTicks++;
    }
    private static void sameOrder(MinecraftServer server, Run run) throws Exception {
        var supply = run.runtime.core().registry().supply();
        var orders = supply.deliveries().stream().filter(o -> o.ownerDemandId().equals(id(run, "demand"))).toList();
        requireState(orders.size() == 1 && orders.getFirst().id().equals(id(run, "order")) && orders.getFirst().workId().equals(id(run, "work")), "A second/replacement order was admitted");
        requireState(run.runtime.core().registry().citizens(id(run, "colony")).size() == 1, "A replacement courier was admitted");
    }
    private static void identity(MinecraftServer server, Run run, CitizenEntity npc) {
        var citizen = run.runtime.core().registry().citizen(id(run, "citizen"));
        requireState(npc.getUUID().equals(id(run, "entity")) && npc.citizenId().equals(id(run, "citizen"))
                && npc.bindingEpoch() == run.manifest.getLong("epoch") && citizen.entityId().equals(id(run, "entity"))
                && citizen.bindingEpoch() == run.manifest.getLong("epoch"), "Original citizen binding/UUID changed");
        for (String key : List.of("source", "destination", "return")) {
            var registration = run.runtime.core().registry().storage().registrations(id(run, "colony")).stream()
                    .filter(r -> r.id().equals(id(run, key + "Registration"))).findFirst().orElseThrow();
            requireState(registration.storages().getFirst().identity().equals(id(run, key + "Storage")), "Native storage binding changed " + key);
        }
    }
    private void stopped(ServerStoppedEvent event) {
        if (!selected()) return;
        MinecraftServer server = event.getServer(); Run run = runs.remove(server);
        if (run == null) return;
        try {
            guard(server);
            CompoundTag marker = read(world(server).resolve("data/colonyloom-session.nbt"));
            CompoundTag dto = saved(server);
            require(server, run, marker.getBoolean("clean") && marker.getUUID("checkpointId").equals(dto.getUUID("checkpointId")), "clean_checkpoint", dto.getUUID("checkpointId").toString());
            if (run.manifest != null && run.done) {
                Counts expected = phase().equals("initialize") ? new Counts(8, 0, 0, 0)
                        : clean() ? phase().equals("exercise") ? new Counts(0, 8, 0, 0) : new Counts(0, 0, 8, 0) : expected(run);
                require(server, run, durableCounts(server, run).equals(expected), "clean_checkpoint_native_counts", "raw MCA=" + expected);
                if (phase().equals("initialize")) require(server, run, savedRecord(server, "demands", "id", id(run, "demand")).getLong("fulfilled") == 0
                                && savedRecord(server, "deliveries", "id", id(run, "order")).getLong("transferred") == 0,
                        "initial_durable_unfinished_request", "one demand/order/work retained without native effect");
                if (phase().equals("verify") && !clean()) {
                    CompoundTag colony = savedRecord(server, "colonies", "colonyId", id(run, "colony"));
                    require(server, run, colony.getBoolean("recoveryBlocked"), "verify_checkpoint_remains_blocked", colony.toString());
                }
            }
        } catch (Exception failure) {
            try { fact(server, run, "clean_checkpoint_failure", false, failure.toString()); }
            catch (Exception evidence) { failure.addSuppressed(evidence); }
            org.slf4j.LoggerFactory.getLogger(TransferRecoveryScenario.class).error("Transfer recovery clean evidence failed", failure);
        }
    }
    private static void dirty(MinecraftServer server, Run run, String check) throws Exception {
        CompoundTag marker = read(world(server).resolve("data/colonyloom-session.nbt"));
        require(server, run, !marker.getBoolean("clean") && marker.getUUID("checkpointId").equals(run.runtime.persistence().checkpointId()), check, marker.toString());
    }
    private static void preload(MinecraftServer server, Run run) throws Exception {
        if (run.manifest != null) return;
        run.manifest = read(world(server).resolve(MANIFEST));
        requireState(run.manifest.getString("scenario").equals(scenario()) && run.manifest.getUUID("owner").equals(OWNER), "World manifest scenario/owner mismatch");
        for (String key : List.of("colony", "citizen", "entity", "demand", "order", "work", "sourceStorage", "destinationStorage", "returnStorage")) id(run, key);
    }
    private static Counts expected(Run run) { return new Counts(run.manifest.getInt("durableSource"), run.manifest.getInt("durableCourier"), run.manifest.getInt("durableDestination"), run.manifest.getInt("durableReturn")); }
    private static UUID id(Run run, String key) { if (run.manifest == null || !run.manifest.hasUUID(key)) throw new IllegalStateException("Manifest identity missing " + key); return run.manifest.getUUID(key); }
    private static Container container(MinecraftServer server, BlockPos pos) { if (!(server.overworld().getBlockEntity(pos) instanceof Container inventory)) throw new IllegalStateException("Original native container unavailable " + pos); return inventory; }
    private static CitizenEntity entityOrNull(MinecraftServer server, Run run) { var entity = server.overworld().getEntity(id(run, "entity")); return entity instanceof CitizenEntity citizen && citizen.isAlive() && !citizen.isRemoved() ? citizen : null; }
    private static CitizenEntity entity(MinecraftServer server, Run run) { var entity = entityOrNull(server, run); if (entity == null) throw new IllegalStateException("Original bound courier inventory UNKNOWN"); return entity; }
    private static int count(Container inventory) { int count = 0; for (int slot = 0; slot < inventory.getContainerSize(); slot++) { var stack = inventory.getItem(slot); if (!stack.isEmpty() && !stack.is(Items.OAK_PLANKS)) throw new IllegalStateException("Unexpected fixture property " + stack); count += stack.getCount(); } return count; }
    private static Counts counts(MinecraftServer server, Run run) { return new Counts(count(container(server, SOURCE)), count(entity(server, run).inventory()), count(container(server, DESTINATION)), count(container(server, RETURN))); }
    private static void rememberEntityChunk(MinecraftServer server, Run run) { ChunkPos chunk = entity(server, run).chunkPosition(); run.manifest.putInt("entityChunkX", chunk.x); run.manifest.putInt("entityChunkZ", chunk.z); }
    private static Counts durableCounts(MinecraftServer server, Run run) throws Exception {
        CompoundTag entity = RecoveryNativeState.entity(server, id(run, "entity"), new ChunkPos(run.manifest.getInt("entityChunkX"), run.manifest.getInt("entityChunkZ")));
        CompoundTag identity = entity.getCompound("Colonyloom");
        requireState(identity.hasUUID("citizenId") && identity.getUUID("citizenId").equals(id(run, "citizen")) && identity.getLong("bindingEpoch") == run.manifest.getLong("epoch"), "Durable original courier binding changed");
        return new Counts(RecoveryNativeState.inventoryCount(server, RecoveryNativeState.blockEntity(server, SOURCE), Items.OAK_PLANKS),
                RecoveryNativeState.inventoryCount(server, identity, Items.OAK_PLANKS),
                RecoveryNativeState.inventoryCount(server, RecoveryNativeState.blockEntity(server, DESTINATION), Items.OAK_PLANKS),
                RecoveryNativeState.inventoryCount(server, RecoveryNativeState.blockEntity(server, RETURN), Items.OAK_PLANKS));
    }
    private static CompoundTag saved(MinecraftServer server) throws Exception { return read(world(server).resolve("data/colonyloom.dat")).getCompound("data"); }
    private static CompoundTag savedEffect(MinecraftServer server, Run run) throws Exception { return savedRecord(server, "evidence", "operationId", id(run, "operation")); }
    private static CompoundTag savedRecord(MinecraftServer server, String list, String key, UUID id) throws Exception { for (Tag raw : saved(server).getList(list, Tag.TAG_COMPOUND)) { CompoundTag tag = (CompoundTag)raw; if (tag.hasUUID(key) && tag.getUUID(key).equals(id)) return tag; } throw new IllegalStateException("Durable original record missing " + list + " " + id); }
    private static CompoundTag read(Path path) throws Exception { if (!Files.isRegularFile(path) || Files.size(path) > 64L * 1024 * 1024) throw new IllegalStateException("Missing/oversized recovery evidence " + path); return NbtIo.readCompressed(path, NbtAccounter.create(64L * 1024 * 1024)); }
    private static void write(Path path, CompoundTag tag) throws Exception { NbtIo.writeCompressed(tag, path); try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); } if (!read(path).equals(tag)) throw new IllegalStateException("Recovery manifest durability mismatch"); }
    private static String coordinates(BlockPos pos) { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }
    private static UUID uuid(String output, String key) { var matcher = Pattern.compile("(?:^|[\\s\\[,])" + Pattern.quote(key) + "=([0-9a-fA-F-]{36})(?=[\\s\\],]|$)").matcher(output); if (!matcher.find()) throw new IllegalStateException("Missing " + key + " in " + output); return UUID.fromString(matcher.group(1)); }
    private static String command(MinecraftServer server, Run run, String text) throws Exception { var capture = new Capture(); int code = server.getCommands().getDispatcher().execute(text, run.actor.createCommandSourceStack().withPermission(2).withSource(capture)); String output = String.join("\n", capture.messages); if (code != 1) throw new IllegalStateException("Public command refused " + text + ": " + output); return output; }
    private static final class Capture implements CommandSource { final List<String> messages = new ArrayList<>(); public void sendSystemMessage(Component component) { messages.add(component.getString()); } public boolean acceptsSuccess() { return true; } public boolean acceptsFailure() { return true; } public boolean shouldInformAdmins() { return false; } }
    private static void finish(MinecraftServer server, Run run) throws Exception { fact(server, run, phase() + "_complete", true, "public commands, natural production courier and same-world native persistence"); run.done = true; server.halt(false); }
    private static void requireState(boolean passed, String detail) { if (!passed) throw new IllegalStateException(detail); }
    private static void require(MinecraftServer server, Run run, boolean passed, String check, String detail) throws Exception { fact(server, run, check, passed, detail); requireState(passed, check + ": " + detail); }
    private static void fact(MinecraftServer server, Run run, String check, boolean passed, String detail) throws Exception {
        var json = new JsonObject(); json.addProperty("scenario", scenario()); json.addProperty("phase", phase()); json.addProperty("tick", server.getTickCount());
        json.addProperty("check", check); json.addProperty("passed", passed); json.addProperty("detail", detail);
        json.addProperty("initialSource", 8); json.addProperty("initialDestination", 0); json.addProperty("initialCourier", 0); json.addProperty("initialReturn", 0);
        if (run != null && run.manifest != null && run.manifest.hasUUID("entity")) {
            CitizenEntity npc = entityOrNull(server, run);
            if (npc != null && server.overworld().getBlockEntity(SOURCE) instanceof Container && server.overworld().getBlockEntity(DESTINATION) instanceof Container && server.overworld().getBlockEntity(RETURN) instanceof Container) {
                Counts counts = counts(server, run); json.addProperty("currentSource", counts.source()); json.addProperty("currentCourier", counts.courier()); json.addProperty("currentDestination", counts.destination()); json.addProperty("currentReturn", counts.returned()); json.addProperty("currentPhysicalTotal", counts.source() + counts.courier() + counts.destination() + counts.returned());
            }
            for (String key : List.of("colony", "citizen", "entity", "demand", "order", "work", "operation")) if (run.manifest.hasUUID(key)) json.addProperty(key, id(run, key).toString());
            json.addProperty("bindingEpoch", run.manifest.getLong("epoch"));
            for (String key : List.of("durableSource", "durableCourier", "durableDestination", "durableReturn")) if (run.manifest.contains(key)) json.addProperty(key, run.manifest.getInt(key));
        }
        Path path = world(server).resolve("colonyloom-recovery-observations.jsonl");
        Files.writeString(path, json + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
    }
}
