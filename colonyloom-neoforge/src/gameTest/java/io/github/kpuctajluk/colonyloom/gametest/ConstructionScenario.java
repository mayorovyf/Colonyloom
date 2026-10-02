package io.github.kpuctajluk.colonyloom.gametest;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.minecraft.construction.BlockPlacementExecutor;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.neoforge.ConstructionExecutorEvent;
import java.nio.channels.FileChannel;
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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Only a disposable dedicated process may run this fixture; production runtime is never duplicated. */
final class ConstructionScenario {
    private static final UUID OWNER = UUID.nameUUIDFromBytes("OfflinePlayer:ConstructionOwner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final BlockPos ORIGIN = new BlockPos(8, 64, 8);
    private static final String MANIFEST = "colonyloom-construction-fixture.nbt";
    private static final long LIMIT = 64L * 1024 * 1024;
    private static final List<String> SCENARIOS = List.of("clean", "cancel", "BEFORE_BLOCK_CHANGE", "AFTER_BLOCK_CHANGE");
    private final Map<MinecraftServer, Run> runs = new IdentityHashMap<>();

    ConstructionScenario() {
        NeoForge.EVENT_BUS.addListener(this::configure);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::cancelBoundary);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, this::stopped);
    }
    private void cancelBoundary(BlockEvent.EntityPlaceEvent event) {
        // Keep the third action from racing the Post observer; this is a real external veto, not a placement bypass.
        if (scenario().equals("cancel") && phase().equals("exercise") && event.getPos().equals(ORIGIN.offset(2, 0, 0))
                && event.getEntity() instanceof ServerPlayer player && player.getUUID().equals(OWNER)) event.setCanceled(true);
    }
    private void stopped(ServerStoppedEvent event) {
        MinecraftServer server = event.getServer();
        Run run = runs.remove(server);
        if (scenario().isEmpty() || run == null) return;
        try {
            guard(server);
            CompoundTag marker = read(world(server).resolve("data/colonyloom-session.nbt"));
            CompoundTag dto = saved(server);
            require(server, marker.getBoolean("clean") && marker.getUUID("checkpointId").equals(dto.getUUID("checkpointId")),
                    "production_clean_checkpoint_confirmed", "checkpoint=" + dto.getUUID("checkpointId"));
            if (phase().equals("initialize")) {
                CompoundTag site = savedSite(server, run.manifest.getUUID("work"));
                require(server, site.getInt("cursor") == 0 && site.getInt("consumed") == 0 && !site.getBoolean("closed"),
                        "initial_durable_open_goal", site.toString());
            }
        } catch (Exception failure) {
            try { fact(server, "clean_checkpoint_failure", false, failure.toString()); }
            catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            org.slf4j.LoggerFactory.getLogger(ConstructionScenario.class).error("Construction checkpoint verification failed", failure);
        }
    }
    private static final class Run {
        int ticks;
        int stableTicks;
        int observedBlocks = -1;
        int observedItems = -1;
        boolean done;
        io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime runtime;
        boolean maintenanceSeeded;
        UUID retainedPrepared;
        ServerPlayer actor;
        CompoundTag manifest;
    }
    private static String scenario() { return System.getProperty("colonyloom.test.constructionScenario", ""); }
    private static String phase() { return System.getProperty("colonyloom.test.constructionPhase", ""); }
    private static Path world(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT); }
    private static void guard(MinecraftServer server) throws Exception {
        if (!server.isDedicatedServer() || !SCENARIOS.contains(scenario()) || !List.of("initialize", "exercise", "verify").contains(phase())
                || !Files.isRegularFile(world(server).resolve("colonyloom-test-world"))) {
            throw new IllegalStateException("Construction fixture requires dedicated disposable marked world and exact named scenario/phase");
        }
    }
    private static void preload(MinecraftServer server, Run run) throws Exception {
        if (!phase().equals("initialize") && run.manifest == null) {
            run.manifest = read(world(server).resolve(MANIFEST));
            if (!run.manifest.getString("scenario").equals(scenario()) || !run.manifest.getUUID("owner").equals(OWNER)) {
                throw new IllegalStateException("Fixture scenario/owner mismatch");
            }
        }
    }
    private void configure(ConstructionExecutorEvent event) {
        if(SCENARIOS.contains(scenario())) runs.computeIfAbsent(event.server(),ignored -> new Run()).runtime=event.runtime();
        if (!SCENARIOS.contains(scenario()) || !phase().equals("exercise") || !Boolean.getBoolean("colonyloom.testFaults")) return;
        if (!scenario().equals("BEFORE_BLOCK_CHANGE") && !scenario().equals("AFTER_BLOCK_CHANGE")) return;
        event.observer((point, context) -> fault(event.server(), point, context));
    }
    private void fault(MinecraftServer server, BlockPlacementExecutor.FaultPoint point, ActionContext context) {
        try {
            guard(server);
            if (!Boolean.getBoolean("colonyloom.testFaults") || !phase().equals("exercise")) throw new IllegalStateException("Faults disabled");
            Run run = runs.computeIfAbsent(server, ignored -> new Run());
            preload(server, run);
            if (run.manifest == null || !context.colonyId().equals(run.manifest.getUUID("colony"))) return;
            // Deliberately persist PREPARED DTO, not an atomic checkpoint. SavedData is an independent side.
            if (point == BlockPlacementExecutor.FaultPoint.BEFORE_BLOCK_CHANGE) {
                server.overworld().getDataStorage().save();
                net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
                CompoundTag site = savedSite(server, run.manifest.getUUID("work"));
                require(server, site.getInt("cursor") == 0 && site.getInt("consumed") == 0, "prepared_goal_before_change", site.toString());
                boolean prepared = false;
                for (Tag value : saved(server).getList("evidence", Tag.TAG_COMPOUND)) {
                    CompoundTag entry = (CompoundTag)value;
                    if (entry.getString("typeId").equals("colonyloom:effect") && entry.hasUUID("workId")
                            && entry.getUUID("workId").equals(run.manifest.getUUID("work")) && entry.getString("state").equals("PREPARED")) prepared = true;
                }
                require(server, prepared, "durable_prepared_effect", saved(server).toString());
                require(server, countItems(entity(server, run.manifest)) == 4 && countBlocks(server) == 0, "before_effect_real_inventory_world", "items=4 blocks=0");
            }
            if (!point.name().equals(scenario())) return;
            if (point == BlockPlacementExecutor.FaultPoint.AFTER_BLOCK_CHANGE) {
                require(server, countItems(entity(server, run.manifest)) == 3 && countBlocks(server) == 1, "after_effect_real_inventory_world", "items=3 blocks=1");
                // Save only block chunks: entity inventory and logical cursor deliberately remain the previous durable side.
                server.overworld().getChunkSource().save(true);
                require(server, savedSite(server, run.manifest.getUUID("work")).getInt("cursor") == 0, "world_saved_goal_uncommitted", "cursor=0 blocks=1");
            }
            CompoundTag marker = read(world(server).resolve("data/colonyloom-session.nbt"));
            require(server, !marker.getBoolean("clean"), "dirty_marker_before_halt", marker.toString());
            run.manifest.putInt("crashBlocks", countBlocks(server));
            run.manifest.putInt("crashItems", countItems(entity(server, run.manifest)));
            write(world(server).resolve(MANIFEST), run.manifest);
            fact(server, "expected_fault", true, "point=" + point + " exit=97 DTOcursor=0");
            // Last gate immediately at the destructive action; no other exit path uses halt.
            guard(server);
            if (!Boolean.getBoolean("colonyloom.testFaults") || !point.name().equals(scenario())) throw new IllegalStateException("Fault gate changed");
            Runtime.getRuntime().halt(97);
        } catch (Exception failure) { throw new IllegalStateException("Construction fault fixture failed", failure); }
    }
    private void tick(ServerTickEvent.Post event) {
        if (scenario().isEmpty()) return;
        MinecraftServer server = event.getServer();
        Run run = runs.computeIfAbsent(server, ignored -> new Run());
        if (run.done) return;
        try {
            guard(server);
            preload(server, run);
            if (++run.ticks > 2400) throw new IllegalStateException("2400 tick timeout status=" + (run.manifest == null ? "no manifest" : command(server, run, "colonyloom status " + run.manifest.getUUID("colony"))));
            if (run.actor == null) {
                GameProfile profile = new GameProfile(OWNER, "ConstructionOwner");
                if (server.getProfileCache() == null) throw new IllegalStateException("Dedicated owner profile cache unavailable");
                server.getProfileCache().add(profile);
                run.actor = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
                // Never connect or tick this fixture actor; only its genuine owner profile/command identity is used.
                GameType.SURVIVAL.updatePlayerAbilities(run.actor.getAbilities());
                run.actor.moveTo(8.5, 64, 12.5, 0, 0);
                server.overworld().setChunkForced(0, 0, true);
            }
            if (run.ticks < 60 || !server.overworld().isPositionEntityTicking(ORIGIN)) return;
            if (phase().equals("initialize")) { initialize(server, run); finish(server, run, "initial_clean_checkpoint_requested"); return; }
            if (run.manifest == null) run.manifest = read(world(server).resolve(MANIFEST));
            CitizenEntity npc = entityOrNull(server, run.manifest);
            if (npc == null || !server.overworld().isPositionEntityTicking(npc.blockPosition())) return;
            requireIdentity(npc, run.manifest);
            String status = command(server, run, "colonyloom status " + run.manifest.getUUID("colony"));
            int blocks = countBlocks(server), items = countItems(npc);
            if (phase().equals("exercise")) {
                if (scenario().equals("clean") || scenario().equals("cancel")) {
                    if (blocks < 2) return;
                    require(server, blocks == 2 && items == (scenario().equals("cancel") ? 2 : 0), "two_block_partial_real_resources", status);
                    require(server, npc.distanceToSqr(8.5, 64, 14.5) > 1, "actual_builder_walked", npc.position().toString());
                    if (scenario().equals("clean") && !status.contains("reason=MATERIALS")) return;
                    if (scenario().equals("cancel")) command(server, run, "colonyloom work cancel " + run.manifest.getUUID("work"));
                    run.manifest.putLong("partialEpoch", npc.bindingEpoch());
                    write(world(server).resolve(MANIFEST), run.manifest);
                    finish(server, run, scenario().equals("cancel") ? "cancel_after_two" : "clean_partial_stop");
                }
                return; // Crash cases finish exclusively at the executor's named callback, timeout otherwise.
            }
            if (scenario().equals("clean")) {
                require(server, !status.contains("recoveryBlocked=true"), "clean_restart_not_ambiguous", status);
                if (!run.manifest.getBoolean("refilled")) {
                    var registry=run.runtime.core().registry();
                    if(!run.maintenanceSeeded) {
                        run.maintenanceSeeded=true;
                        while(registry.effects().size()<2048) {
                            UUID id=UUID.randomUUID();
                            var effect=new io.github.kpuctajluk.colonyloom.core.action.EffectRecord(id,run.manifest.getUUID("colony"),null,npc.citizenId(),npc.bindingEpoch(),ActionContext.Kind.DEATH,new io.github.kpuctajluk.colonyloom.core.colony.WorldPosition("minecraft:overworld",8,64,14),"fixture-observed-empty","",0,0,io.github.kpuctajluk.colonyloom.core.action.EffectRecord.State.PREPARED,0);
                            registry.effects().prepare(effect,io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.NORMAL);
                            if(run.retainedPrepared==null) run.retainedPrepared=id;
                            else registry.effects().update(effect.observed(0,false));
                        }
                        return;
                    }
                    if(registry.effects().size()>=2048) return;
                    require(server,registry.effects().size()==1 && registry.effects().get(run.retainedPrepared)!=null,"live_threshold_compaction_preserves_prepared","effects="+registry.effects().size());
                    require(server,registry.construction().size()==1 && !registry.construction().site(run.manifest.getUUID("work")).closed(),"live_threshold_compaction_preserves_open_site","sites="+registry.construction().size());
                    require(server,savedSite(server,run.manifest.getUUID("work")).getInt("cursor")==2,"live_threshold_verified_durable_open_progress",savedSite(server,run.manifest.getUUID("work")).toString());
                    registry.effects().discardUnchanged(run.retainedPrepared);
                    require(server, blocks == 2 && items == 0 && savedSite(server, run.manifest.getUUID("work")).getInt("consumed") == 2,
                            "durable_clean_partial_progress", savedSite(server, run.manifest.getUUID("work")).toString());
                    npc.inventory().setItem(0, new ItemStack(Items.OAK_STAIRS, 2));
                    run.manifest.putBoolean("refilled", true);
                    write(world(server).resolve(MANIFEST), run.manifest);
                    return;
                }
                if (!status.contains("work=" + run.manifest.getUUID("work") + " state=COMPLETED")) return;
                require(server, blocks == 4 && items == 0, "clean_restart_exact_four_no_duplicate_consumption", status);
                server.overworld().getDataStorage().save();
                net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
                CompoundTag site = savedSite(server, run.manifest.getUUID("work"));
                require(server, site.getBoolean("closed") && site.getInt("cursor") == 4 && site.getInt("consumed") == 4, "durable_clean_completion", site.toString());
                npc.inventory().setItem(0,new ItemStack(Items.OAK_STAIRS,4));
                require(server,npc.hurt(server.overworld().damageSources().genericKill(),1000) && !npc.isAlive(),"production_death_observed","citizen="+npc.citizenId());
                int dropped=0;
                for(var drop:server.overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,npc.getBoundingBox().inflate(2))) if(drop.getItem().is(Items.OAK_STAIRS)) dropped+=drop.getItem().getCount();
                require(server,dropped==4 && npc.inventory().isEmpty(),"production_death_property_preserved","actualStairsDropped="+dropped);
                server.overworld().getDataStorage().save(); net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
                boolean witnessed=false;
                for(Tag value:saved(server).getList("evidence",Tag.TAG_COMPOUND)) {
                    CompoundTag evidence=(CompoundTag)value;
                    if(evidence.getString("kind").equals("DEATH") && evidence.getString("state").equals("OBSERVED") && evidence.getInt("countBefore")==4 && evidence.getInt("countAfter")==0) witnessed=true;
                }
                require(server,witnessed,"durable_production_death_effect",saved(server).getList("evidence",Tag.TAG_COMPOUND).toString());
                finish(server, run, "clean_continuation_complete");
            } else if (scenario().equals("cancel")) {
                require(server, blocks == 2 && items == 2 && status.contains("work=" + run.manifest.getUUID("work") + " state=CANCELLED"), "cancel_restart_preserves_blocks_and_remainder", status);
                require(server, savedSite(server, run.manifest.getUUID("work")).getBoolean("closed"), "durable_cancel_closed_goal", savedSite(server, run.manifest.getUUID("work")).toString());
                finish(server, run, "cancel_restart_complete");
            } else recovery(server, run, status, blocks, items);
        } catch (Exception failure) {
            run.done = true;
            try { fact(server, "scenario_failure", false, failure.toString()); } catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            org.slf4j.LoggerFactory.getLogger(ConstructionScenario.class).error("Construction scenario failed", failure);
            server.halt(false);
        }
    }
    private static void initialize(MinecraftServer server, Run run) throws Exception {
        require(server, !Files.exists(world(server).resolve(MANIFEST)), "new_disposable_fixture", world(server).toString());
        for (int x = 3; x <= 17; x++) for (int z = 3; z <= 18; z++) {
            server.overworld().setBlock(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 64; y <= 67; y++) server.overworld().setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        CompoundTag manifest = new CompoundTag();
        manifest.putUUID("owner", OWNER); manifest.putString("scenario", scenario());
        UUID colony = uuid(command(server, run, "colonyloom colony create ConstructionFixture 0 64 0 31 64 31"), "colony");
        manifest.putUUID("colony", colony);
        String created = command(server, run, "colonyloom citizen create " + colony + " 8 64 14");
        manifest.putUUID("citizen", uuid(created, "citizen")); manifest.putUUID("entity", uuid(created, "entity"));
        run.manifest = manifest;
        CitizenEntity npc = entity(server, manifest); manifest.putLong("epoch", npc.bindingEpoch());
        command(server, run, "colonyloom citizen assign " + npc.citizenId() + " colonyloom:builder");
        int materials = scenario().equals("clean") ? 2 : 4;
        npc.inventory().setItem(0, new ItemStack(Items.OAK_STAIRS, materials));
        UUID work = uuid(command(server, run, "colonyloom build " + colony + " colonyloom:test_four_stairs 8 64 8 0"), "work");
        manifest.putUUID("work", work); manifest.putInt("initialItems", materials);
        write(world(server).resolve(MANIFEST), manifest);
        require(server, countBlocks(server) == 0 && countItems(npc) == materials, "initial_real_resources_no_effect", "items=" + materials + " blocks=0 work=" + work);
    }
    private static void recovery(MinecraftServer server, Run run, String status, int blocks, int items) throws Exception {
        if (!run.manifest.getBoolean("accepted")) {
            require(server, status.contains("recoveryBlocked=true") && status.contains("RECOVERY_AMBIGUOUS"), "crash_restart_ambiguous", status);
            int expectedBlocks = scenario().equals("AFTER_BLOCK_CHANGE") ? 1 : 0;
            require(server, blocks == expectedBlocks && items == 4, "independent_saved_sides_observed", "saved blocks=" + blocks + " saved NPC items=" + items + " atCrashItems=" + run.manifest.getInt("crashItems"));
            require(server, savedSite(server, run.manifest.getUUID("work")).getInt("cursor") == 0, "no_goal_commit_replay", savedSite(server, run.manifest.getUUID("work")).toString());
            if (run.observedBlocks != blocks || run.observedItems != items) { run.observedBlocks = blocks; run.observedItems = items; run.stableTicks = 0; }
            if (++run.stableTicks < 100) return;
            require(server, blocks == expectedBlocks && items == 4, "hundred_tick_no_replay_or_issuance", status);
            String inspect = command(server, run, "colonyloom recovery inspect " + run.manifest.getUUID("colony"));
            require(server, inspect.contains("actualWorldDigest=") && inspect.contains("actualBlock="), "physical_recovery_inspection", inspect);
            UUID checkpoint = uuid(status, "recoveryCheckpointId");
            command(server, run, "colonyloom recovery accept-world " + run.manifest.getUUID("colony") + " " + checkpoint);
            run.manifest.putBoolean("accepted", true); write(world(server).resolve(MANIFEST), run.manifest);
            run.stableTicks = 0;
            return;
        }
        require(server, !status.contains("recoveryBlocked=true") && blocks == run.observedBlocks && items == run.observedItems, "accept_world_preserves_physical_sides", status);
        require(server, status.contains("work=" + run.manifest.getUUID("work") + " state=CANCELLED"), "accept_world_closes_old_goal", status);
        CompoundTag site = savedSite(server, run.manifest.getUUID("work"));
        require(server, site.getBoolean("closed"), "durable_accept_closed_goal", site.toString());
        if (++run.stableTicks < 100) return;
        require(server, countBlocks(server) == run.observedBlocks && countItems(entity(server, run.manifest)) == run.observedItems, "accepted_goal_never_replays", status);
        finish(server, run, "crash_recovery_complete");
    }
    private static void finish(MinecraftServer server, Run run, String check) throws Exception {
        fact(server, check, true, "scenario=" + scenario() + " phase=" + phase());
        run.done = true;
        server.halt(false); // Ordinary stop executes production checkpointAndClean, unlike fault halt(97).
    }
    private static void requireIdentity(CitizenEntity npc, CompoundTag manifest) {
        if (!npc.citizenId().equals(manifest.getUUID("citizen")) || npc.bindingEpoch() != manifest.getLong("epoch")) throw new IllegalStateException("Persisted NPC identity changed");
    }
    private static CitizenEntity entityOrNull(MinecraftServer server, CompoundTag manifest) {
        var entity = server.overworld().getEntity(manifest.getUUID("entity"));
        return entity instanceof CitizenEntity npc && npc.isAlive() && !npc.isRemoved() ? npc : null;
    }
    private static CitizenEntity entity(MinecraftServer server, CompoundTag manifest) {
        CitizenEntity npc = entityOrNull(server, manifest);
        if (npc == null) throw new IllegalStateException("Exact persisted NPC unavailable: " + manifest);
        return npc;
    }
    private static int countItems(CitizenEntity npc) {
        int count = 0;
        for (int slot = 0; slot < CitizenEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getItem(slot);
            if (!stack.isEmpty() && !stack.is(Items.OAK_STAIRS)) throw new IllegalStateException("Unexpected fixture item " + stack);
            count += stack.getCount();
        }
        return count;
    }
    private static int countBlocks(MinecraftServer server) {
        BlockState expected = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH)
                .setValue(StairBlock.HALF, Half.BOTTOM).setValue(StairBlock.SHAPE, StairsShape.STRAIGHT).setValue(StairBlock.WATERLOGGED, false);
        int count = 0;
        for (int x = 0; x < 4; x++) {
            BlockState observed = server.overworld().getBlockState(ORIGIN.offset(x, 0, 0));
            if (observed.equals(expected)) count++;
            else if (!observed.isAir()) throw new IllegalStateException("Wrong real target state " + observed);
        }
        return count;
    }
    private static CompoundTag saved(MinecraftServer server) throws Exception { return read(world(server).resolve("data/colonyloom.dat")).getCompound("data"); }
    private static CompoundTag savedSite(MinecraftServer server, UUID work) throws Exception {
        for (Tag value : saved(server).getList("evidence", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag)value;
            if (entry.getString("typeId").equals("colonyloom:construction_site") && entry.hasUUID("workId") && entry.getUUID("workId").equals(work)) return entry;
        }
        throw new IllegalStateException("Saved construction goal missing " + work);
    }
    private static CompoundTag read(Path path) throws Exception {
        if (!Files.isRegularFile(path) || Files.size(path) > LIMIT) throw new IllegalStateException("Missing/oversized fixture evidence " + path);
        return NbtIo.readCompressed(path, NbtAccounter.create(LIMIT));
    }
    private static void write(Path path, CompoundTag tag) throws Exception {
        NbtIo.writeCompressed(tag, path);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
        if (!read(path).equals(tag)) throw new IllegalStateException("Fixture manifest durability mismatch");
    }
    private static UUID uuid(String output, String key) {
        var match = Pattern.compile("(?:^|[\\s\\[,])" + Pattern.quote(key) + "=([0-9a-fA-F-]{36})(?=[\\s\\],]|$)").matcher(output);
        if (!match.find()) throw new IllegalStateException("Missing UUID " + key + " in " + output);
        return UUID.fromString(match.group(1));
    }
    private static String command(MinecraftServer server, Run run, String text) throws Exception {
        Capture capture = new Capture();
        CommandSourceStack source = run.actor.createCommandSourceStack().withPermission(2).withSource(capture);
        int code = server.getCommands().getDispatcher().execute(text, source);
        String output = String.join("\n", capture.messages);
        if (code != 1) throw new IllegalStateException("Public command refused " + text + ": " + output);
        return output;
    }
    private static final class Capture implements CommandSource {
        final List<String> messages = new ArrayList<>();
        public void sendSystemMessage(Component component) { messages.add(component.getString()); }
        public boolean acceptsSuccess() { return true; }
        public boolean acceptsFailure() { return true; }
        public boolean shouldInformAdmins() { return false; }
    }
    private static void require(MinecraftServer server, boolean condition, String check, String detail) throws Exception {
        if (!condition) { fact(server, check, false, detail); throw new IllegalStateException(check + ": " + detail); }
        // Only transition evidence, not per-tick diagnostics.
        if (!check.startsWith("crash_restart_") && !check.startsWith("independent_saved_") && !check.startsWith("no_goal_") && !check.startsWith("accept_world_") && !check.startsWith("durable_accept_")) fact(server, check, true, detail);
    }
    private static void fact(MinecraftServer server, String check, boolean passed, String detail) throws Exception {
        JsonObject record = new JsonObject(); record.addProperty("scenario", scenario()); record.addProperty("phase", phase());
        record.addProperty("tick", server.getTickCount()); record.addProperty("check", check); record.addProperty("passed", passed); record.addProperty("detail", detail);
        Path path = world(server).resolve("colonyloom-construction-observations.jsonl");
        Files.writeString(path, record + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
    }
}
