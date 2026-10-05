package io.github.kpuctajluk.colonyloom.gametest;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.action.EffectRecord;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime;
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
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Genuine genericKill death, native captured-drop boundaries, and same-world operator acceptance. */
final class DeathRecoveryScenario {
    private static final int ORIGIN = 6144;
    private static final BlockPos POSITION = new BlockPos(ORIGIN + 8, 64, 8);
    private static final ChunkPos CHUNK = new ChunkPos(POSITION);
    private static final UUID OWNER = UUID.nameUUIDFromBytes("OfflinePlayer:DeathRecoveryOwner".getBytes(StandardCharsets.UTF_8));
    private static final String MANIFEST = "colonyloom-death-recovery-fixture.nbt";
    private static final List<String> SCENARIOS = List.of("death_clean", "death_before_effect", "death_after_source_change",
            "death_after_destination_change", "death_after_fact_before_notify");
    private final Map<MinecraftServer, Run> runs = new IdentityHashMap<>();
    private static final class Run {
        MinecraftServerRuntime runtime;
        ServerPlayer actor;
        CompoundTag manifest;
        boolean done, killed, prepared, verified, accepted, vetoChecked;
        int ticks, stableTicks, vetoTick;
    }
    private record Counts(int sourceStairs, int sourceBread, int dropStairs, int dropBread) {
        int source() { return sourceStairs + sourceBread; }
        int drops() { return dropStairs + dropBread; }
    }
    DeathRecoveryScenario() {
        NeoForge.EVENT_BUS.addListener(this::configure);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, this::stopped);
    }
    private static String scenario() { return System.getProperty("colonyloom.test.recoveryScenario", ""); }
    private static String phase() { return System.getProperty("colonyloom.test.recoveryPhase", ""); }
    private static boolean selected() { return SCENARIOS.contains(scenario()); }
    private static boolean clean() { return scenario().equals("death_clean"); }
    private static Path world(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT); }
    private static void guard(MinecraftServer server) {
        state(server.isDedicatedServer() && selected() && List.of("initialize", "exercise", "verify", "accept").contains(phase())
                && Files.isRegularFile(world(server).resolve("colonyloom-test-world")), "Death recovery requires marked disposable dedicated world and exact scenario/phase");
    }
    private void configure(ConstructionExecutorEvent event) {
        if (!selected()) return;
        Run run = runs.computeIfAbsent(event.server(), ignored -> new Run());
        run.runtime = event.runtime();
        if (phase().equals("exercise") && !clean() && Boolean.getBoolean("colonyloom.testFaults"))
            event.deathObserver((point, context) -> fault(event.server(), run, point, context));
    }
    private void tick(ServerTickEvent.Post event) {
        if (!selected()) return;
        MinecraftServer server = event.getServer();
        Run run = runs.computeIfAbsent(server, ignored -> new Run());
        if (run.done) return;
        try {
            guard(server);
            state(++run.ticks <= 1200, "Death recovery timeout");
            if (run.runtime == null) return;
            if (run.actor == null) {
                var profile = new GameProfile(OWNER, "DeathRecoveryOwner");
                server.getProfileCache().add(profile);
                run.actor = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
                run.actor.moveTo(ORIGIN + 8.5, 64, 12.5, 0, 0);
                server.overworld().setChunkForced(CHUNK.x, CHUNK.z, true);
            }
            if (!phase().equals("initialize")) preload(server, run);
            if (run.ticks < 60 || !server.overworld().isPositionEntityTicking(POSITION)) return;
            if (phase().equals("initialize")) initialize(server, run);
            else if (phase().equals("exercise")) exercise(server, run);
            else continuation(server, run);
        } catch (Exception failure) {
            run.done = true;
            try { fact(server, run, "failure", false, failure.toString()); }
            catch (Exception evidence) { failure.addSuppressed(evidence); }
            org.slf4j.LoggerFactory.getLogger(DeathRecoveryScenario.class).error("Death recovery scenario failed", failure);
            server.halt(false);
        }
    }
    private static void initialize(MinecraftServer server, Run run) throws Exception {
        state(!Files.exists(world(server).resolve(MANIFEST)), "Existing fixture cannot be replaced");
        var level = server.overworld();
        // One native entity chunk: enclosing walls keep the genuine random vanilla drop motion inside it.
        for (int x = ORIGIN; x < ORIGIN + 16; x++) for (int z = 0; z < 16; z++) {
            level.setBlock(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 64; y <= 68; y++) level.setBlock(new BlockPos(x, y, z),
                    (x == ORIGIN || x == ORIGIN + 15 || z == 0 || z == 15) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
        }
        run.manifest = new CompoundTag();
        run.manifest.putString("scenario", scenario()); run.manifest.putUUID("owner", OWNER);
        run.manifest.putUUID("colony", uuid(command(server, run, "colonyloom colony create DeathRecoveryFixture " + ORIGIN + " 64 0 " + (ORIGIN + 15) + " 64 15"), "colony"));
        String created = command(server, run, "colonyloom citizen create " + id(run, "colony") + " " + (ORIGIN + 8) + " 64 8");
        run.manifest.putUUID("citizen", uuid(created, "citizen")); run.manifest.putUUID("entity", uuid(created, "entity"));
        CitizenEntity npc = entity(server, run);
        run.manifest.putLong("epoch", npc.bindingEpoch());
        npc.inventory().setItem(0, new ItemStack(Items.OAK_STAIRS, 4));
        npc.inventory().setItem(1, new ItemStack(Items.BREAD, 3)); npc.inventory().setChanged();
        require(server, run, counts(server, run).equals(new Counts(4, 3, 0, 0))
                && run.runtime.core().registry().citizen(id(run, "citizen")).assignedWorkId() == null,
                "initial_exact_seven_property", "public original citizen; four existing stairs plus three existing bread; no assigned work/reward effect");
        write(world(server).resolve(MANIFEST), run.manifest);
        finish(server, run);
    }
    private static void exercise(MinecraftServer server, Run run) throws Exception {
        if (!run.killed) {
            // The vetoed hit still sets vanilla hurt cooldown; wait for native ticks, do not reset it.
            if (run.vetoChecked && run.ticks - run.vetoTick <= 20) return;
            CitizenEntity npc = entity(server, run);
            identity(server, run);
            require(server, run, npc.isAlive() && !npc.isQuarantined() && counts(server, run).equals(new Counts(4, 3, 0, 0)),
                    "original_alive_native_source", "same UUID/epoch and exact seven before genuine native hurt");
            // Save the pre-hurt source. LivingDeathEvent runs after health reaches zero, so saving there
            // would incorrectly conflate BEFORE_EFFECT with a durably dead native source.
            RecoveryNativeState.saveBlocks(server); RecoveryNativeState.saveEntities(server);
            require(server, run, durableCounts(server, run).equals(new Counts(4, 3, 0, 0))
                    && RecoveryNativeState.entity(server, id(run, "entity"), CHUNK).getFloat("Health") > 0,
                    "native_alive_baseline_flushed", "raw original UUID MCA inventory stairs4 bread3; no native drops");
            if (!run.vetoChecked) {
                java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingDeathEvent> lateVeto=event -> {if(event.getEntity()==npc){npc.setHealth(20);event.setCanceled(true);}};
                NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,false,net.neoforged.neoforge.event.entity.living.LivingDeathEvent.class,lateVeto);
                try {
                    npc.hurt(npc.damageSources().genericKill(),Float.MAX_VALUE);
                    require(server,run,npc.isAlive()&&!npc.isQuarantined()&&run.runtime.core().registry().citizen(id(run,"citizen")).lifecycle()==CitizenRecord.Lifecycle.ALIVE
                            && run.runtime.core().registry().effects().snapshots().stream().noneMatch(value -> value.kind()==ActionContext.Kind.DEATH && value.citizenId().equals(id(run,"citizen")))
                            && counts(server,run).equals(new Counts(4,3,0,0)),"late_lowest_veto_preserves_alive_authority_and_cargo","genuine dispatch cancelled after all earlier listeners; no death effect/tombstone/removal/drop");
                } finally {NeoForge.EVENT_BUS.unregister(lateVeto);}
                run.vetoChecked = true;
                run.vetoTick = run.ticks;
                return;
            }
            run.killed = true;
            state(npc.hurt(npc.damageSources().genericKill(), Float.MAX_VALUE), "Native genericKill hurt was refused");
            state(clean(), "Configured production death fault did not halt at its named boundary");
            EffectRecord effect = deathEffect(run);
            run.manifest.putUUID("operation", effect.operationId());
            require(server, run, effect.state() == EffectRecord.State.OBSERVED && effect.countBefore() == 7 && effect.countAfter() == 0
                    && counts(server, run).equals(new Counts(0, 0, 4, 3)), "clean_actual_death_drop_exact_seven", effect.toString());
            rememberDrops(server, run);
        }
        state(counts(server, run).equals(new Counts(0, 0, 4, 3)), "Clean death physical property changed");
        identity(server, run); dropIdentities(server, run);
        if (++run.stableTicks < 40) return;
        state(server.overworld().getEntity(id(run, "entity")) == null, "Vanilla original corpse not removed");
        write(world(server).resolve(MANIFEST), run.manifest);
        finish(server, run);
    }
    private void fault(MinecraftServer server, Run run, CitizenEntity.DeathFaultPoint point, ActionContext context) {
        try {
            guard(server);
            state(Boolean.getBoolean("colonyloom.testFaults") && phase().equals("exercise") && !clean(), "Death destructive gate disabled");
            preload(server, run);
            if (!context.colonyId().equals(id(run, "colony")) || !context.citizenId().equals(id(run, "citizen"))) return;
            state(context.actionKind() == ActionContext.Kind.DEATH && context.target().x() == POSITION.getX(), "Wrong original death context");
            EffectRecord effect = deathEffect(run);
            if (point == CitizenEntity.DeathFaultPoint.BEFORE_EFFECT) {
                state(!run.prepared && effect.state() == EffectRecord.State.PREPARED && effect.countBefore() == 7 && effect.countAfter() == 7, "Original death must prepare exactly seven once");
                run.prepared = true; run.manifest.putUUID("operation", effect.operationId());
                identity(server, run);
                dirty(server, run);
                run.runtime.persistence().persistSnapshot();
                require(server, run, savedEffect(server, run).getString("state").equals("PREPARED")
                        && savedRecord(server, "citizens", "citizenId", id(run, "citizen")).getString("lifecycle").equals("DEAD")
                        && savedRecord(server, "tombstones", "citizenId", id(run, "citizen")).getString("lifecycle").equals("DEAD"),
                        "durable_original_prepared_death_and_tombstone", "existing logical death precedes drop; native baseline independently retains original living source7");
            }
            state(run.prepared && effect.operationId().equals(id(run, "operation")), "Missing original prepared death");
            if (!point.name().toLowerCase(java.util.Locale.ROOT).equals(scenario().substring("death_".length()))) return;
            // There are two nonempty slots. The source-only crash is the second removal, before
            // spawnAtLocation for bread; stairs are still NeoForge-captured, not world-published.
            if (point == CitizenEntity.DeathFaultPoint.AFTER_SOURCE_CHANGE && counts(server, run).source() != 0) return;
            Counts expected = point == CitizenEntity.DeathFaultPoint.BEFORE_EFFECT ? new Counts(4, 3, 0, 0)
                    : point == CitizenEntity.DeathFaultPoint.AFTER_SOURCE_CHANGE ? new Counts(0, 0, 0, 0) : new Counts(0, 0, 4, 3);
            require(server, run, counts(server, run).equals(expected), "named_boundary_actual_native_counts", point + " existing property; captured drops are not published destinations");
            if (point != CitizenEntity.DeathFaultPoint.BEFORE_EFFECT) RecoveryNativeState.saveEntities(server);
            if (point == CitizenEntity.DeathFaultPoint.AFTER_FACT_BEFORE_NOTIFY) run.runtime.persistence().persistSnapshot();
            rememberDrops(server, run);
            Counts durable = durableCounts(server, run);
            require(server, run, durable.equals(expected), "independent_native_source_and_drop_flush", "raw original UUID/item UUID MCA counts=" + durable + "; source-only loss is not compensated");
            CompoundTag original = savedEffect(server, run);
            require(server, run, original.getString("state").equals(point == CitizenEntity.DeathFaultPoint.AFTER_FACT_BEFORE_NOTIFY ? "OBSERVED" : "PREPARED")
                    && original.getInt("countBefore") == 7 && original.getInt("countAfter") == (point == CitizenEntity.DeathFaultPoint.AFTER_FACT_BEFORE_NOTIFY ? 0 : 7),
                    "durable_original_effect_fact", original.toString());
            run.manifest.put("durableEffect", original.copy());
            run.manifest.putInt("durableSourceStairs", durable.sourceStairs()); run.manifest.putInt("durableSourceBread", durable.sourceBread());
            run.manifest.putInt("durableDropStairs", durable.dropStairs()); run.manifest.putInt("durableDropBread", durable.dropBread());
            write(world(server).resolve(MANIFEST), run.manifest);
            dirty(server, run);
            fact(server, run, "expected_fault", true, "genuine production death " + point + " expectedExit=97; independently durable native=" + durable);
            fact(server, run, "exercise_complete", true, "native genericKill; original death witness and explicit physical sides, not synthetic drops");
            guard(server);
            state(Boolean.getBoolean("colonyloom.testFaults") && phase().equals("exercise")
                    && point.name().toLowerCase(java.util.Locale.ROOT).equals(scenario().substring("death_".length())), "Final destructive gate changed");
            Runtime.getRuntime().halt(97);
        } catch (Exception failure) { throw new IllegalStateException("Death crash evidence failed", failure); }
    }
    private static void continuation(MinecraftServer server, Run run) throws Exception {
        identity(server, run);
        Counts expected = clean() ? new Counts(0, 0, 4, 3) : expected(run);
        state(counts(server, run).equals(expected), "Native death source/drop property changed or replayed");
        dropIdentities(server, run);
        var colony = run.runtime.core().registry().colony(id(run, "colony"));
        EffectRecord effect = deathEffect(run);
        state(effect.operationId().equals(id(run, "operation")) && effect.countBefore() == 7 && effect.countAfter() == (clean() ? 0 : run.manifest.getCompound("durableEffect").getInt("countAfter")), "Original seven-property witness changed");
        if (clean()) {
            state(!colony.recoveryBlocked() && effect.state() == EffectRecord.State.OBSERVED, "Clean original death was quarantined or rewritten");
            if (++run.stableTicks < 100) return;
            require(server, run, server.overworld().getEntity(id(run, "entity")) == null && counts(server, run).equals(expected),
                    "clean_restart_exact_seven_no_respawn", "original DEAD+tombstone, same native drop UUID stairs4 bread3; hundred ticks no replay");
            finish(server, run); return;
        }
        if (phase().equals("verify")) {
            state(colony.recoveryBlocked(), "Dirty death restart must remain quarantined");
            state(effect.state().name().equals(run.manifest.getCompound("durableEffect").getString("state")), "Original death fact rewritten before acceptance");
            if (!run.verified) {
                String status = command(server, run, "colonyloom status " + id(run, "colony"));
                require(server, run, colony.recoveryBlocked()&&effect!=null&&run.runtime.core().registry().bindings().activeEntity(id(run,"citizen")).isEmpty(), "crash_restart_ambiguous", status);
                require(server, run, savedEffect(server, run).equals(run.manifest.getCompound("durableEffect"))
                        && durableCounts(server, run).equals(expected), "restart_original_fact_and_native_uuid_counts", "retained original witness; raw MCA native=" + expected);
                inspect(server, run); run.verified = true;
            }
            if (++run.stableTicks < 100) return;
            require(server, run, true, "hundred_tick_no_automatic_death_replay", "original DEAD authority, surviving old ALIVE embodiment remains quarantined; no source compensation/drop duplication");
            run.manifest.putBoolean("verified", true); write(world(server).resolve(MANIFEST), run.manifest);
            finish(server, run); return;
        }
        state(run.manifest.getBoolean("verified"), "Separate verify phase must precede acceptance");
        if (!run.accepted) {
            state(colony.recoveryBlocked(), "Acceptance requires original recovery quarantine");
            inspect(server, run);
            if(!drops(server).isEmpty()) {
                ItemEntity drop=drops(server).getFirst();ItemStack original=drop.getItem().copy();
                var capture=new Capture();int refused;
                drop.setItem(new ItemStack(Items.DIRT,original.getCount()));
                try {
                    refused=server.getCommands().getDispatcher().execute("colonyloom recovery accept-world "+id(run,"colony")+" "+colony.recoveryCheckpointId(),run.actor.createCommandSourceStack().withPermission(2).withSource(capture));
                } finally {drop.setItem(original);}
                require(server,run,refused==0&&colony.recoveryBlocked()&&String.join("\n",capture.messages).contains("changed"),"changed_death_drop_rejects_inspect_accept","same UUID changed item descriptor; no acceptance/replay/compensation; response="+String.join("\n",capture.messages));
                inspect(server,run);
                // Restore the same object before property logging, even if the public command throws.
                drop.discard();capture=new Capture();
                try {
                    refused=server.getCommands().getDispatcher().execute("colonyloom recovery accept-world "+id(run,"colony")+" "+colony.recoveryCheckpointId(),run.actor.createCommandSourceStack().withPermission(2).withSource(capture));
                } finally {
                    drop.revive();state(server.overworld().addFreshEntity(drop),"Fixture could not restore the original physical object after removal regression");
                }
                require(server,run,refused==0&&colony.recoveryBlocked(),"removed_death_drop_rejects_inspect_accept","published UUID removed after inspect; UNKNOWN cannot equal unchanged destination; response="+String.join("\n",capture.messages));
                inspect(server,run);
            }
            String accepted = command(server, run, "colonyloom recovery accept-world " + id(run, "colony") + " " + colony.recoveryCheckpointId());
            require(server, run, accepted.contains("without item creation/removal/compensation") && counts(server, run).equals(expected), "accept_world_preserves_actual_property", accepted);
            run.accepted = true;
        }
        state(!run.runtime.core().registry().colony(id(run, "colony")).recoveryBlocked() && deathEffect(run).state() == EffectRecord.State.ACCEPTED, "Acceptance did not retain accepted death witness");
        if (++run.stableTicks < 100) return;
        require(server, run, counts(server, run).equals(expected), "accepted_hundred_tick_no_replay", "same original DEAD/tombstone and drop UUIDs; source-only loss not compensated; historical native ALIVE source never reactivated");
        require(server, run, savedEffect(server, run).getString("state").equals("ACCEPTED"), "durable_accepted_original_death", savedEffect(server, run).toString());
        finish(server, run);
    }
    private static void inspect(MinecraftServer server, Run run) throws Exception {
        String inspected = command(server, run, "colonyloom recovery inspect " + id(run, "colony"));
        require(server, run, inspected.contains("actualPhysical=") && inspected.contains("cargoBefore=") && inspected.contains("publication=") && inspected.contains(id(run, "operation").toString()), "public_inspect_original_death", inspected);
    }
    private static void identity(MinecraftServer server, Run run) {
        var registry = run.runtime.core().registry(); var record = registry.citizen(id(run, "citizen"));
        state(registry.citizens(id(run, "colony")).size() == 1 && record.entityId().equals(id(run, "entity"))
                && record.bindingEpoch() == run.manifest.getLong("epoch") && record.assignedWorkId() == null, "Original citizen identity/work changed or replacement admitted");
        CitizenEntity npc = entityOrNull(server, run);
        if (npc != null) state(npc.citizenId().equals(id(run, "citizen")) && npc.bindingEpoch() == record.bindingEpoch(), "Original physical binding changed");
        if (!run.killed && phase().equals("exercise")) return;
        state(record.lifecycle() == CitizenRecord.Lifecycle.DEAD && registry.tombstones().stream().anyMatch(t -> t.citizenId().equals(id(run, "citizen"))
                && t.bindingEpoch() == record.bindingEpoch() && t.lifecycle() == CitizenRecord.Lifecycle.DEAD), "Original DEAD/tombstone missing");
        state(registry.bindings().activeEntity(id(run, "citizen")).isEmpty() && (npc == null || npc.isQuarantined()), "Historical original embodiment was reactivated");
    }
    private static EffectRecord deathEffect(Run run) {
        var effects = run.runtime.core().registry().effects().snapshots().stream().filter(e -> e.kind() == ActionContext.Kind.DEATH && e.citizenId().equals(id(run, "citizen"))).toList();
        state(effects.size() == 1 && effects.getFirst().bindingEpoch() == run.manifest.getLong("epoch") && effects.getFirst().workId() == null, "Missing/duplicate/replacement original death effect");
        return effects.getFirst();
    }
    private static List<ItemEntity> drops(MinecraftServer server) {
        var drops = new ArrayList<ItemEntity>();
        for (var entity : server.overworld().getAllEntities()) if (entity instanceof ItemEntity item && item.chunkPosition().equals(CHUNK)) drops.add(item);
        return drops;
    }
    private static Counts counts(MinecraftServer server, Run run) {
        int sourceStairs = 0, sourceBread = 0, dropStairs = 0, dropBread = 0;
        CitizenEntity npc = entityOrNull(server, run);
        if (npc != null) for (int slot = 0; slot < CitizenEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getItem(slot); property(stack);
            if (stack.is(Items.OAK_STAIRS)) sourceStairs += stack.getCount(); else if (stack.is(Items.BREAD)) sourceBread += stack.getCount();
        }
        for (ItemEntity item : drops(server)) { ItemStack stack = item.getItem(); property(stack);
            if (stack.is(Items.OAK_STAIRS)) dropStairs += stack.getCount(); else if (stack.is(Items.BREAD)) dropBread += stack.getCount(); }
        return new Counts(sourceStairs, sourceBread, dropStairs, dropBread);
    }
    private static void property(ItemStack stack) { state(stack.isEmpty() || stack.is(Items.OAK_STAIRS) || stack.is(Items.BREAD), "Unexpected native fixture property " + stack); }
    private static void rememberDrops(MinecraftServer server, Run run) {
        var list = new ListTag();
        for (ItemEntity item : drops(server)) { var tag = new CompoundTag(); tag.putUUID("UUID", item.getUUID()); tag.put("Item", item.getItem().save(server.registryAccess())); list.add(tag); }
        run.manifest.put("drops", list);
    }
    private static void dropIdentities(MinecraftServer server, Run run) {
        ListTag expected = run.manifest.getList("drops", Tag.TAG_COMPOUND); List<ItemEntity> actual = drops(server);
        state(actual.size() == expected.size(), "Native drop UUID cardinality changed");
        for (Tag raw : expected) { var original = (CompoundTag)raw;
            ItemEntity item = actual.stream().filter(value -> value.getUUID().equals(original.getUUID("UUID"))).findFirst().orElseThrow();
            state(item.getItem().save(server.registryAccess()).equals(original.getCompound("Item")), "Native original drop UUID/item/count changed"); }
    }
    private static Counts durableCounts(MinecraftServer server, Run run) throws Exception {
        int sourceStairs = 0, sourceBread = 0, dropStairs = 0, dropBread = 0, nativeDropEntities = 0;
        CompoundTag original = RecoveryNativeState.entityOrNull(server, id(run, "entity"), CHUNK);
        if (original != null) {
            CompoundTag identity = original.getCompound("Colonyloom");
            state(identity.hasUUID("citizenId") && identity.getUUID("citizenId").equals(id(run, "citizen")) && identity.getLong("bindingEpoch") == run.manifest.getLong("epoch"), "Durable original native binding changed");
            sourceStairs = RecoveryNativeState.inventoryCount(server, identity, Items.OAK_STAIRS);
            sourceBread = RecoveryNativeState.inventoryCount(server, identity, Items.BREAD);
        }
        ListTag recorded = run.manifest.getList("drops", Tag.TAG_COMPOUND);
        for (Tag raw : RecoveryNativeState.entities(server, CHUNK)) {
            CompoundTag tag = (CompoundTag)raw;
            if (!tag.getString("id").equals("minecraft:item")) continue;
            nativeDropEntities++;
            CompoundTag matching = null;
            for (Tag saved : recorded) if (((CompoundTag)saved).getUUID("UUID").equals(tag.getUUID("UUID"))) matching = (CompoundTag)saved;
            state(matching != null && matching.getCompound("Item").equals(tag.getCompound("Item")), "Raw native drop UUID/item/count differs from actual published drop");
            ItemStack stack = ItemStack.parseOptional(server.registryAccess(), tag.getCompound("Item")); property(stack);
            if (stack.is(Items.OAK_STAIRS)) dropStairs += stack.getCount(); else if (stack.is(Items.BREAD)) dropBread += stack.getCount();
        }
        state(nativeDropEntities == recorded.size(), "Durable native drop UUID cardinality changed");
        return new Counts(sourceStairs, sourceBread, dropStairs, dropBread);
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
            if (run.done && run.manifest != null) {
                Counts expected = phase().equals("initialize") ? new Counts(4, 3, 0, 0) : clean() ? new Counts(0, 0, 4, 3) : expected(run);
                require(server, run, durableCounts(server, run).equals(expected), "clean_checkpoint_native_uuid_counts", "independent raw original source/drop UUID counts=" + expected);
                if (!phase().equals("initialize")) require(server, run, savedRecord(server, "citizens", "citizenId", id(run, "citizen")).getString("lifecycle").equals("DEAD")
                        && savedRecord(server, "tombstones", "citizenId", id(run, "citizen")).getString("lifecycle").equals("DEAD"), "durable_original_dead_and_tombstone", "no replacement citizen/death/reward");
                if (phase().equals("verify") && !clean()) require(server, run, savedRecord(server, "colonies", "colonyId", id(run, "colony")).getBoolean("recoveryBlocked"), "verify_checkpoint_remains_blocked", "original death ambiguity retained");
            }
        } catch (Exception failure) {
            try { fact(server, run, "clean_checkpoint_failure", false, failure.toString()); }
            catch (Exception evidence) { failure.addSuppressed(evidence); }
            org.slf4j.LoggerFactory.getLogger(DeathRecoveryScenario.class).error("Death recovery clean checkpoint evidence failed", failure);
        }
    }
    private static void preload(MinecraftServer server, Run run) throws Exception {
        if (run.manifest != null) return;
        run.manifest = read(world(server).resolve(MANIFEST));
        state(run.manifest.getString("scenario").equals(scenario()) && run.manifest.getUUID("owner").equals(OWNER), "Manifest scenario/owner mismatch");
        for (String key : List.of("colony", "citizen", "entity")) id(run, key);
    }
    private static Counts expected(Run run) { return new Counts(run.manifest.getInt("durableSourceStairs"), run.manifest.getInt("durableSourceBread"), run.manifest.getInt("durableDropStairs"), run.manifest.getInt("durableDropBread")); }
    private static CitizenEntity entityOrNull(MinecraftServer server, Run run) { var entity = server.overworld().getEntity(id(run, "entity")); return entity instanceof CitizenEntity citizen ? citizen : null; }
    private static CitizenEntity entity(MinecraftServer server, Run run) { var entity = entityOrNull(server, run); state(entity != null, "Original native entity not loaded"); return entity; }
    private static UUID id(Run run, String key) { state(run.manifest != null && run.manifest.hasUUID(key), "Missing manifest identity " + key); return run.manifest.getUUID(key); }
    private static void dirty(MinecraftServer server, Run run) throws Exception { CompoundTag marker = read(world(server).resolve("data/colonyloom-session.nbt")); require(server, run, !marker.getBoolean("clean") && marker.getUUID("checkpointId").equals(run.runtime.persistence().checkpointId()), "dirty_marker_before_halt", marker.toString()); }
    private static CompoundTag saved(MinecraftServer server) throws Exception { return read(world(server).resolve("data/colonyloom.dat")).getCompound("data"); }
    private static CompoundTag savedEffect(MinecraftServer server, Run run) throws Exception { return savedRecord(server, "evidence", "operationId", id(run, "operation")); }
    private static CompoundTag savedRecord(MinecraftServer server, String list, String key, UUID id) throws Exception { for (Tag raw : saved(server).getList(list, Tag.TAG_COMPOUND)) { var tag = (CompoundTag)raw; if (tag.hasUUID(key) && tag.getUUID(key).equals(id)) return tag; } throw new IllegalStateException("Durable original record missing " + list + " " + id); }
    private static CompoundTag read(Path path) throws Exception { state(Files.isRegularFile(path) && Files.size(path) <= 64L * 1024 * 1024, "Missing/oversized evidence " + path); return NbtIo.readCompressed(path, NbtAccounter.create(64L * 1024 * 1024)); }
    private static void write(Path path, CompoundTag tag) throws Exception { NbtIo.writeCompressed(tag, path); try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); } state(read(path).equals(tag), "Manifest durability mismatch"); }
    private static UUID uuid(String output, String key) { var matcher = Pattern.compile("(?:^|[\\s\\[,])" + Pattern.quote(key) + "=([0-9a-fA-F-]{36})(?=[\\s\\],]|$)").matcher(output); state(matcher.find(), "Missing " + key + " in " + output); return UUID.fromString(matcher.group(1)); }
    private static String command(MinecraftServer server, Run run, String text) throws Exception { var capture = new Capture(); int code = server.getCommands().getDispatcher().execute(text, run.actor.createCommandSourceStack().withPermission(2).withSource(capture)); String output = String.join("\n", capture.messages); state(code == 1, "Public command refused " + text + ": " + output); return output; }
    private static final class Capture implements CommandSource { final List<String> messages = new ArrayList<>(); public void sendSystemMessage(Component component) { messages.add(component.getString()); } public boolean acceptsSuccess() { return true; } public boolean acceptsFailure() { return true; } public boolean shouldInformAdmins() { return false; } }
    private static void finish(MinecraftServer server, Run run) throws Exception { fact(server, run, phase() + "_complete", true, "real public original citizen; production native genericKill; same-world native UUID proofs and no replay"); run.done = true; server.halt(false); }
    private static void state(boolean passed, String detail) { if (!passed) throw new IllegalStateException(detail); }
    private static void require(MinecraftServer server, Run run, boolean passed, String check, String detail) throws Exception { fact(server, run, check, passed, detail); state(passed, check + ": " + detail); }
    private static void fact(MinecraftServer server, Run run, String check, boolean passed, String detail) throws Exception {
        var json = new JsonObject(); json.addProperty("scenario", scenario()); json.addProperty("phase", phase()); json.addProperty("tick", server.getTickCount());
        json.addProperty("check", check); json.addProperty("passed", passed); json.addProperty("detail", detail);
        json.addProperty("initialSource", 7); json.addProperty("initialStairs", 4); json.addProperty("initialBread", 3); json.addProperty("initialDrops", 0);
        var observedCounts = new JsonObject(); observedCounts.addProperty("initialSource", 7); observedCounts.addProperty("initialDrops", 0); json.add("counts", observedCounts);
        if (run != null && run.manifest != null && run.manifest.hasUUID("entity")) {
            Counts actual = counts(server, run); json.addProperty("currentSource", actual.source()); json.addProperty("currentDrops", actual.drops());
            json.addProperty("currentSourceStairs", actual.sourceStairs()); json.addProperty("currentSourceBread", actual.sourceBread());
            json.addProperty("currentDropStairs", actual.dropStairs()); json.addProperty("currentDropBread", actual.dropBread()); json.addProperty("currentPhysicalTotal", actual.source() + actual.drops());
            observedCounts.addProperty("sourceStairs", actual.sourceStairs()); observedCounts.addProperty("sourceBread", actual.sourceBread());
            observedCounts.addProperty("dropStairs", actual.dropStairs()); observedCounts.addProperty("dropBread", actual.dropBread()); observedCounts.addProperty("physicalTotal", actual.source() + actual.drops());
            for (String key : List.of("colony", "citizen", "entity", "operation")) if (run.manifest.hasUUID(key)) json.addProperty(key, id(run, key).toString());
            json.addProperty("bindingEpoch", run.manifest.getLong("epoch"));
            var uuids = new com.google.gson.JsonArray(); for (ItemEntity item : drops(server)) uuids.add(item.getUUID().toString()); json.add("nativeDropUUIDs", uuids);
            for (String key : List.of("durableSourceStairs", "durableSourceBread", "durableDropStairs", "durableDropBread")) if (run.manifest.contains(key)) json.addProperty(key, run.manifest.getInt(key));
        }
        Path path = world(server).resolve("colonyloom-recovery-observations.jsonl");
        Files.writeString(path, json + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
    }
}
