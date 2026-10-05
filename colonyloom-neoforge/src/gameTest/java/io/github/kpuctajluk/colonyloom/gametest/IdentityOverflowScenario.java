package io.github.kpuctajluk.colonyloom.gametest;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.github.kpuctajluk.colonyloom.core.citizen.BindingRegistry;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.persistence.RegistrySnapshot;
import io.github.kpuctajluk.colonyloom.core.persistence.Tombstone;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.neoforge.ConstructionExecutorEvent;
import java.io.IOException;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Dedicated disposable proof for physical platform identity-history overflow and recovery. */
final class IdentityOverflowScenario {
    private static final String SCENARIO = "identity_history_overflow";
    private static final String MANIFEST = "colonyloom-identity-overflow-fixture.nbt";
    private static final long NBT_LIMIT = 8L * 1024 * 1024;
    private static final UUID OWNER = UUID.nameUUIDFromBytes("OfflinePlayer:IdentityOverflowOwner".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final BlockPos A_POSITION = new BlockPos(8, 64, 8);
    private static final BlockPos B_POSITION = new BlockPos(40, 64, 8);
    private final Map<MinecraftServer, Run> runs = new IdentityHashMap<>();

    private static final class Run {
        int ticks;
        boolean done;
        io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime runtime;
        ServerPlayer actor;
        CompoundTag manifest;
    }

    /** Keeps this dev fixture inert in every ordinary game/test run. */
    IdentityOverflowScenario() {
        NeoForge.EVENT_BUS.addListener(this::configure);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, this::stopped);
    }

    private static String phase() { return System.getProperty("colonyloom.test.identityOverflowPhase", ""); }
    private static Path world(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT); }

    private static void guard(MinecraftServer server) throws IOException {
        if (!server.isDedicatedServer() || !List.of("initialize", "exercise", "verify").contains(phase())
                || !Files.isRegularFile(world(server).resolve("colonyloom-test-world"))) {
            throw new IllegalStateException("Identity overflow requires the dedicated disposable marked world and exact phase");
        }
    }

    private void configure(ConstructionExecutorEvent event) {
        if (List.of("initialize", "exercise", "verify").contains(phase())) {
            runs.computeIfAbsent(event.server(), ignored -> new Run()).runtime = event.runtime();
        }
    }

    private void tick(ServerTickEvent.Post event) {
        if (phase().isEmpty()) return;
        MinecraftServer server = event.getServer();
        Run run = runs.computeIfAbsent(server, ignored -> new Run());
        if (run.done) return;
        try {
            guard(server);
            if (++run.ticks > 1200) throw new IllegalStateException("Identity overflow phase exceeded 1200 ticks");
            if (run.actor == null) {
                GameProfile profile = new GameProfile(OWNER, "IdentityOverflowOwner");
                if (server.getProfileCache() == null) throw new IllegalStateException("Dedicated profile cache unavailable");
                server.getProfileCache().add(profile);
                run.actor = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
                GameType.SURVIVAL.updatePlayerAbilities(run.actor.getAbilities());
                run.actor.moveTo(A_POSITION.getX() + 0.5, A_POSITION.getY(), A_POSITION.getZ() + 0.5, 0, 0);
                server.overworld().setChunkForced(0, 0, true);
                server.overworld().setChunkForced(2, 0, true);
            }
            if (run.ticks < 40 || !server.overworld().isPositionEntityTicking(A_POSITION)
                    || !server.overworld().isPositionEntityTicking(B_POSITION)) return;
            if (phase().equals("initialize")) {
                initialize(server, run);
                finish(server, run, "overflow_history_initialized");
                return;
            }
            if (run.manifest == null) run.manifest = read(world(server).resolve(MANIFEST));
            if (!run.manifest.getString("scenario").equals(SCENARIO) || !run.manifest.getUUID("owner").equals(OWNER)) {
                throw new IllegalStateException("Overflow fixture manifest does not match this scenario/owner");
            }
            if (phase().equals("exercise")) exercise(server, run);
            else verify(server, run);
        } catch (Exception failure) {
            run.done = true;
            try { fact(server, "scenario_failure", false, failure.toString()); }
            catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            org.slf4j.LoggerFactory.getLogger(IdentityOverflowScenario.class).error("Identity overflow scenario failed", failure);
            server.halt(false);
        }
    }

    private static void initialize(MinecraftServer server, Run run) throws Exception {
        Path manifestPath = world(server).resolve(MANIFEST);
        require(server, !Files.exists(manifestPath), "fresh_disposable_world", manifestPath.toString());
        var level = server.overworld();
        for (int x = 0; x < 48; x++) for (int z = 0; z < 16; z++) {
            level.setBlock(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 64; y <= 67; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        CompoundTag manifest = new CompoundTag();
        manifest.putInt("schemaVersion", 1);
        manifest.putString("scenario", SCENARIO);
        manifest.putUUID("owner", OWNER);
        String createdColonyA = command(server, run, "colonyloom colony create OverflowA 0 64 0 15 64 15");
        String createdColonyB = command(server, run, "colonyloom colony create OverflowB 32 64 0 47 64 15");
        UUID colonyA = uuid(createdColonyA, "colony");
        UUID colonyB = uuid(createdColonyB, "colony");
        manifest.putUUID("colonyA", colonyA);
        manifest.putUUID("colonyB", colonyB);
        String createdA = command(server, run, "colonyloom citizen create " + colonyA + " " + coordinates(A_POSITION));
        String createdB = command(server, run, "colonyloom citizen create " + colonyB + " " + coordinates(B_POSITION));
        UUID publicCitizenA = uuid(createdA, "citizen");
        UUID publicEntityA = uuid(createdA, "entity");
        UUID publicCitizenB = uuid(createdB, "citizen");
        UUID publicEntityB = uuid(createdB, "entity");
        require(server, publicCitizenA != null && publicEntityA != null && publicCitizenB != null && publicEntityB != null,
                "public_citizen_create_identities", createdA + " / " + createdB);
        // Recover the exact record/entity pairing from the production runtime, not output ordering.
        var registry = run.runtime.core().registry();
        var recordA = registry.citizens(colonyA).getFirst();
        var recordB = registry.citizens(colonyB).getFirst();
        require(server, recordA.citizenId().equals(publicCitizenA) && recordA.entityId().equals(publicEntityA)
                        && recordB.citizenId().equals(publicCitizenB) && recordB.entityId().equals(publicEntityB),
                "public_identity_matches_authoritative_records", createdA + " / " + createdB);
        UUID citizenA = recordA.citizenId();
        UUID citizenB = recordB.citizenId();
        UUID nativeA = recordA.entityId();
        UUID nativeB = recordB.entityId();
        CitizenEntity originalA = entity(server, nativeA);
        CitizenEntity originalB = entity(server, nativeB);
        originalA.inventory().setItem(0, new ItemStack(Items.BREAD, 3));
        originalA.inventory().setChanged();
        originalB.inventory().setItem(0, new ItemStack(Items.OAK_LOG, 5));
        originalB.inventory().setChanged();
        manifest.putUUID("citizenA", citizenA);
        manifest.putUUID("entityA", nativeA);
        manifest.putLong("epochA", originalA.bindingEpoch());
        manifest.putUUID("citizenB", citizenB);
        manifest.putUUID("entityB", nativeB);
        manifest.putLong("epochB", originalB.bindingEpoch());
        manifest.putIntArray("historicalPosition", new int[]{A_POSITION.getX(), A_POSITION.getY(), A_POSITION.getZ()});
        UUID historicalCitizen = stableId("historical-citizen");
        UUID historicalEntity = stableId("historical-entity-0");
        manifest.putUUID("historicalCitizen", historicalCitizen);
        manifest.putUUID("historicalEntity", historicalEntity);

        RegistrySnapshot before = registry.snapshot();
        List<CitizenRecord> citizens = new ArrayList<>(before.citizens());
        WorldPosition historyPosition = new WorldPosition("minecraft:overworld", A_POSITION.getX(), A_POSITION.getY(), A_POSITION.getZ());
        citizens.add(new CitizenRecord(historicalCitizen, colonyA, historicalEntity, 1, null, null, null, null,
                Map.of(), Map.of("food", 20), CitizenRecord.Lifecycle.DEAD, CitizenRecord.Admission.INACTIVE,
                CitizenRecord.Readiness.BLOCKED, 0, Map.of("food", 1200L), historyPosition, 1));
        List<Tombstone> tombstones = new ArrayList<>(before.tombstones());
        tombstones.add(new Tombstone(historicalCitizen, colonyA, 1, CitizenRecord.Lifecycle.DEAD));
        List<BindingRegistry.Observation> observations = new ArrayList<>(before.observations());
        int historicalRows = BindingRegistry.MAX_OBSERVATIONS_PER_COLONY - 1;
        for (int index = 0; index < historicalRows; index++) {
            observations.add(new BindingRegistry.Observation(historicalCitizen, stableId("historical-entity-" + index), 1,
                    false, true, true));
        }
        registry.restore(new RegistrySnapshot(before.colonies(), citizens, before.buildings(), tombstones, observations,
                before.works(), before.targetClaims(), before.effects(), before.constructionSites(), before.pinnedBlueprints(),
                before.storage(), before.supply()));
        // Restore deliberately resets observation load state. Re-observe only the two real, currently loaded
        // canonical NPCs; all 599 retired rows stay unloaded under the existing readiness policy.
        run.runtime.core().bindings().observe(citizenA, nativeA, originalA.bindingEpoch());
        run.runtime.core().bindings().observe(citizenB, nativeB, originalB.bindingEpoch());
        run.runtime.persistence().persistSnapshot();
        write(world(server).resolve(MANIFEST), manifest);
        require(server, run.runtime.core().bindings().observations().size() == historicalRows + 2,
                "admitted_history_count", "A=600 B=1 total=" + run.runtime.core().bindings().observations().size());
        require(server, originalA.isAlive() && originalB.isAlive() && !originalA.isQuarantined() && !originalB.isQuarantined(),
                "original_citizens_admitted", nativeA + "/" + nativeB);
        verifyHistory(saved(server), manifest, server, "initial_durable_history");
    }

    private static void exercise(MinecraftServer server, Run run) throws Exception {
        CompoundTag manifest = run.manifest;
        CitizenEntity originalA = entity(server, manifest.getUUID("entityA"));
        CitizenEntity originalB = entity(server, manifest.getUUID("entityB"));
        requireIdentity(server, originalA, manifest.getUUID("citizenA"), manifest.getLong("epochA"));
        requireIdentity(server, originalB, manifest.getUUID("citizenB"), manifest.getLong("epochB"));
        require(server, originalA.inventory().getItem(0).is(Items.BREAD) && originalA.inventory().getItem(0).getCount() == 3,
                "original_A_physical_property", originalA.inventory().getItem(0).toString());
        require(server, originalB.inventory().getItem(0).is(Items.OAK_LOG) && originalB.inventory().getItem(0).getCount() == 5,
                "original_B_physical_property", originalB.inventory().getItem(0).toString());

        UUID duplicateId = UUID.randomUUID();
        CitizenEntity duplicate = cloneWithUuid(originalA, duplicateId, new ItemStack(Items.BREAD, 2));
        require(server, server.overworld().addFreshEntity(duplicate), "real_duplicate_addFreshEntity", duplicateId.toString());
        manifest.putUUID("duplicateEntity", duplicateId);
        require(server, duplicate.isQuarantined() && originalA.isQuarantined(), "overflow_quarantines_duplicate_and_A", duplicateId.toString());
        require(server, originalB.isAlive() && !originalB.isQuarantined()
                && run.runtime.core().bindings().activeEntity(manifest.getUUID("citizenB")).filter(originalB.getUUID()::equals).isPresent(),
                "overflow_keeps_B_available", originalB.getUUID().toString());
        String blockedStatus = command(server, run, "colonyloom status " + manifest.getUUID("colonyA"));
        String independentStatus = command(server, run, "colonyloom status " + manifest.getUUID("colonyB"));
        require(server, blockedStatus.contains("recoveryBlocked=true") && !run.runtime.core().registry().colony(manifest.getUUID("colonyA")).available(),
                "overflow_blocks_only_A", blockedStatus);
        require(server, !independentStatus.contains("recoveryBlocked=true") && run.runtime.core().registry().colony(manifest.getUUID("colonyB")).available(),
                "overflow_preserves_B_state", independentStatus);
        require(server, historyCount(run.runtime, manifest.getUUID("colonyA")) == BindingRegistry.MAX_OBSERVATIONS_PER_COLONY,
                "overflow_does_not_record_601st_identity", "identityHistory=600/600");

        String inspection = command(server, run, "colonyloom recovery inspect " + manifest.getUUID("colonyA"));
        require(server, inspection.contains("identityHistory=600/600") && inspection.contains("unrecordedEmbodiment=" + duplicateId)
                        && inspection.contains("unrecordedEmbodimentCount=1"),
                "operator_inspect_cap_and_unrecorded_UUID", inspection);
        UUID checkpoint = uuid(blockedStatus, "recoveryCheckpointId");
        String statusBeforeRefusal = command(server, run, "colonyloom status " + manifest.getUUID("colonyA"));
        CommandResult refused = tryCommand(server, run, "colonyloom recovery accept-world " + manifest.getUUID("colonyA") + " " + checkpoint);
        String statusAfterRefusal = command(server, run, "colonyloom status " + manifest.getUUID("colonyA"));
        require(server, refused.code() == 0 && refused.output().contains("Unrecorded embodiment exceeds scoped history")
                        && statusBeforeRefusal.equals(statusAfterRefusal),
                "accept_refuses_present_unrecorded_duplicate", refused.output());
        require(server, duplicate.inventory().getItem(0).is(Items.BREAD) && duplicate.inventory().getItem(0).getCount() == 2,
                "refusal_retains_duplicate_property", duplicate.inventory().getItem(0).toString());

        CompoundTag retainedDuplicate = saveEntity(duplicate);
        duplicate.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        require(server, server.overworld().getEntity(duplicateId) == null && duplicate.isRemoved()
                        && duplicate.inventory().getItem(0).is(Items.BREAD) && duplicate.inventory().getItem(0).getCount() == 2
                        && retainedDuplicate.getCompound("Colonyloom").getUUID("citizenId").equals(manifest.getUUID("citizenA")),
                "fixture_duplicate_unloaded_without_property_mutation", "UNLOADED_TO_CHUNK retained bread=2");
        String afterRemoval = command(server, run, "colonyloom recovery inspect " + manifest.getUUID("colonyA"));
        require(server, afterRemoval.contains("identityHistory=600/600") && afterRemoval.contains("unrecordedEmbodimentCount=0")
                        && !afterRemoval.contains("unrecordedEmbodiment=" + duplicateId) && afterRemoval.contains("ready=true"),
                "fresh_inspect_after_non_destructive_removal", afterRemoval);
        UUID freshCheckpoint = uuid(command(server, run, "colonyloom status " + manifest.getUUID("colonyA")), "recoveryCheckpointId");
        String accepted = command(server, run, "colonyloom recovery accept-world " + manifest.getUUID("colonyA") + " " + freshCheckpoint);
        require(server, accepted.contains("without item creation/removal/compensation"), "accept_after_fresh_inspect", accepted);
        require(server, !run.runtime.core().registry().colony(manifest.getUUID("colonyA")).recoveryBlocked()
                        && run.runtime.core().bindings().activeEntity(manifest.getUUID("citizenA")).filter(originalA.getUUID()::equals).isPresent()
                        && !originalA.isQuarantined(),
                "A_recovers_original_identity_without_history_trim", originalA.getUUID().toString());
        require(server, run.runtime.core().registry().colony(manifest.getUUID("colonyB")).available()
                        && run.runtime.core().bindings().activeEntity(manifest.getUUID("citizenB")).filter(originalB.getUUID()::equals).isPresent()
                        && !originalB.isQuarantined(),
                "B_remains_independently_available_after_accept", originalB.getUUID().toString());
        require(server, historyCount(run.runtime, manifest.getUUID("colonyA")) == BindingRegistry.MAX_OBSERVATIONS_PER_COLONY,
                "accept_retains_full_lifetime_history", "identityHistory=600/600");
        run.runtime.persistence().persistSnapshot();
        write(world(server).resolve(MANIFEST), manifest);
        finish(server, run, "overflow_recovery_accepted_without_compensation");
    }

    private static void verify(MinecraftServer server, Run run) throws Exception {
        CompoundTag manifest = run.manifest;
        CitizenEntity originalA = entity(server, manifest.getUUID("entityA"));
        CitizenEntity originalB = entity(server, manifest.getUUID("entityB"));
        requireIdentity(server, originalA, manifest.getUUID("citizenA"), manifest.getLong("epochA"));
        requireIdentity(server, originalB, manifest.getUUID("citizenB"), manifest.getLong("epochB"));
        require(server, originalA.inventory().getItem(0).is(Items.BREAD) && originalA.inventory().getItem(0).getCount() == 3
                        && originalB.inventory().getItem(0).is(Items.OAK_LOG) && originalB.inventory().getItem(0).getCount() == 5,
                "same_world_restart_preserves_original_property", "A=bread3 B=oak_log5");
        String statusA = command(server, run, "colonyloom status " + manifest.getUUID("colonyA"));
        String statusB = command(server, run, "colonyloom status " + manifest.getUUID("colonyB"));
        require(server, !statusA.contains("recoveryBlocked=true") && !statusB.contains("recoveryBlocked=true")
                        && run.runtime.core().registry().colony(manifest.getUUID("colonyA")).available()
                        && run.runtime.core().registry().colony(manifest.getUUID("colonyB")).available(),
                "same_world_restart_preserves_scoped_authority", "A=" + statusA + " B=" + statusB);
        String inspection = command(server, run, "colonyloom recovery inspect " + manifest.getUUID("colonyA"));
        require(server, inspection.contains("identityHistory=600/600") && inspection.contains("unrecordedEmbodimentCount=0"),
                "same_world_restart_preserves_cap_and_clean_inspection", inspection);
        verifyHistory(saved(server), manifest, server, "same_world_restart_durable_retired_history");

        CitizenEntity late = create(server.overworld());
        late.initializeIdentity(manifest.getUUID("historicalCitizen"), 1);
        late.inventory().setItem(0, new ItemStack(Items.BREAD, 2));
        late.inventory().setChanged();
        CompoundTag lateTag = saveEntity(late);
        lateTag.putUUID("UUID", manifest.getUUID("historicalEntity"));
        CitizenEntity restored = create(server.overworld());
        restored.load(lateTag);
        require(server, server.overworld().getEntity(manifest.getUUID("historicalEntity")) == null
                        && server.overworld().addFreshEntity(restored),
                "late_load_retired_original_UUID", manifest.getUUID("historicalEntity").toString());
        require(server, restored.citizenId().equals(manifest.getUUID("historicalCitizen")) && restored.bindingEpoch() == 1
                        && restored.getUUID().equals(manifest.getUUID("historicalEntity")) && restored.isQuarantined()
                        && restored.inventory().getItem(0).is(Items.BREAD) && restored.inventory().getItem(0).getCount() == 2,
                "late_retired_identity_quarantined_with_property", restored.getUUID().toString());
        require(server, run.runtime.core().bindings().observations(manifest.getUUID("historicalCitizen")).stream()
                        .anyMatch(value -> value.entityId().equals(restored.getUUID()) && value.retired() && value.quarantined())
                        && run.runtime.core().bindings().activeEntity(manifest.getUUID("historicalCitizen")).isEmpty(),
                "retired_history_cannot_reactivate", run.runtime.core().bindings().observations(manifest.getUUID("historicalCitizen")).toString());
        require(server, run.runtime.core().registry().colony(manifest.getUUID("colonyA")).available()
                        && run.runtime.core().registry().colony(manifest.getUUID("colonyB")).available()
                        && run.runtime.core().bindings().activeEntity(manifest.getUUID("citizenA")).filter(originalA.getUUID()::equals).isPresent()
                        && run.runtime.core().bindings().activeEntity(manifest.getUUID("citizenB")).filter(originalB.getUUID()::equals).isPresent(),
                "late_retired_load_does_not_change_original_authority", "A/B canonical bindings remain active");
        require(server, historyCount(run.runtime, manifest.getUUID("colonyA")) == BindingRegistry.MAX_OBSERVATIONS_PER_COLONY,
                "late_retired_load_does_not_grow_history", "identityHistory=600/600");
        run.runtime.persistence().persistSnapshot();
        finish(server, run, "overflow_same_world_restart_and_late_retired_load_complete");
    }

    private void stopped(ServerStoppedEvent event) {
        MinecraftServer server = event.getServer();
        Run run = runs.remove(server);
        if (phase().isEmpty() || run == null) return;
        try {
            guard(server);
            CompoundTag marker = read(world(server).resolve("data/colonyloom-session.nbt"));
            CompoundTag dto = saved(server);
            require(server, marker.getBoolean("clean") && marker.getUUID("checkpointId").equals(dto.getUUID("checkpointId")),
                    "normal_stop_clean_checkpoint", "checkpoint=" + dto.getUUID("checkpointId"));
            verifyHistory(dto, run.manifest == null ? read(world(server).resolve(MANIFEST)) : run.manifest,
                    server, "normal_stop_history_durable");
            if (phase().equals("exercise")) {
                require(server, dto.getList("citizens", Tag.TAG_COMPOUND).size() == 3,
                        "normal_stop_original_and_dead_records", "citizens=" + dto.getList("citizens", Tag.TAG_COMPOUND).size());
                require(server, noEntity(server, run.manifest.getUUID("duplicateEntity")),
                        "normal_stop_overflow_duplicate_absent", run.manifest.getUUID("duplicateEntity").toString());
            }
            if (phase().equals("verify")) {
                UUID lateId = run.manifest.getUUID("historicalEntity");
                int[] latePosition = run.manifest.getIntArray("historicalPosition");
                if (latePosition.length != 3) throw new IllegalStateException("Missing late retired entity position");
                var nativeLate = RecoveryNativeState.entityOrNull(server, lateId,
                        new net.minecraft.world.level.ChunkPos(latePosition[0] >> 4, latePosition[2] >> 4));
                require(server, nativeLate != null && nativeLate.getUUID("UUID").equals(lateId)
                                && nativeLate.getCompound("Colonyloom").getUUID("citizenId").equals(run.manifest.getUUID("historicalCitizen"))
                                && nativeLate.getCompound("Colonyloom").getLong("bindingEpoch") == 1
                                && RecoveryNativeState.inventoryCount(server, nativeLate.getCompound("Colonyloom"), Items.BREAD) == 2,
                        "normal_stop_retired_late_load_physical_custody", String.valueOf(nativeLate));
            }
        } catch (Exception failure) {
            try { fact(server, "normal_stop_checkpoint_failure", false, failure.toString()); }
            catch (Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            org.slf4j.LoggerFactory.getLogger(IdentityOverflowScenario.class).error("Identity overflow checkpoint verification failed", failure);
        }
    }

    private static void verifyHistory(CompoundTag dto, CompoundTag manifest, MinecraftServer server, String check) throws Exception {
        ListTag rows = dto.getList("bindingObservations", Tag.TAG_COMPOUND);
        int scoped = 0, retired = 0, canonicalA = 0, canonicalB = 0;
        for (Tag raw : rows) {
            CompoundTag row = (CompoundTag) raw;
            if (!row.hasUUID("citizenId")) continue;
            UUID id = row.getUUID("citizenId");
            if (id.equals(manifest.getUUID("citizenA")) || id.equals(manifest.getUUID("historicalCitizen"))) {
                scoped++;
                if (row.getBoolean("retired")) retired++;
                if (id.equals(manifest.getUUID("citizenA")) && row.getUUID("entityId").equals(manifest.getUUID("entityA")) && !row.getBoolean("retired")) canonicalA++;
            }
            if (id.equals(manifest.getUUID("citizenB")) && row.getUUID("entityId").equals(manifest.getUUID("entityB")) && !row.getBoolean("retired")) canonicalB++;
        }
        require(server, scoped == BindingRegistry.MAX_OBSERVATIONS_PER_COLONY && retired == BindingRegistry.MAX_OBSERVATIONS_PER_COLONY - 1
                        && canonicalA == 1 && canonicalB == 1,
                check, "A=600 (retired=599, original canonical=1), B canonical=1, encoded observations=" + rows.size());
    }

    private static long historyCount(io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime runtime, UUID colonyId) {
        return runtime.core().bindings().observations().stream().filter(value -> runtime.core().registry().findCitizen(value.citizenId())
                .map(citizen -> citizen.colonyId().equals(colonyId)).orElse(false)).count();
    }

    private static void requireIdentity(MinecraftServer server, CitizenEntity entity, UUID citizen, long epoch) throws Exception {
        require(server, entity.citizenId().equals(citizen) && entity.bindingEpoch() == epoch && entity.isAlive(),
                "original_UUID_and_epoch", "entity=" + entity.getUUID() + " citizen=" + entity.citizenId() + " epoch=" + entity.bindingEpoch());
    }

    private static CitizenEntity cloneWithUuid(CitizenEntity original, UUID entityId, ItemStack property) {
        CompoundTag tag = saveEntity(original);
        tag.putUUID("UUID", entityId);
        tag.getCompound("Colonyloom").putLong("bindingEpoch", original.bindingEpoch());
        CitizenEntity duplicate = create((net.minecraft.server.level.ServerLevel) original.level());
        duplicate.load(tag);
        duplicate.moveTo(original.getX() + 2.5, original.getY(), original.getZ(), original.getYRot(), original.getXRot());
        duplicate.inventory().clearContent();
        duplicate.inventory().setItem(0, property.copy());
        duplicate.inventory().setChanged();
        return duplicate;
    }

    private static CitizenEntity create(net.minecraft.server.level.ServerLevel level) {
        Entity value = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
        if (!(value instanceof CitizenEntity citizen)) throw new IllegalStateException("Citizen entity type unavailable");
        return citizen;
    }

    private static CitizenEntity entity(MinecraftServer server, UUID id) {
        Entity value = server.overworld().getEntity(id);
        if (!(value instanceof CitizenEntity citizen) || !citizen.isAlive() || citizen.isRemoved()) {
            throw new IllegalStateException("Exact original citizen is not loaded/alive; no replacement: " + id);
        }
        return citizen;
    }

    private static CompoundTag saveEntity(CitizenEntity entity) {
        CompoundTag result = new CompoundTag();
        if (!entity.save(result)) throw new IllegalStateException("Cannot preserve fixture entity " + entity.getUUID());
        return result;
    }

    private static boolean noEntity(MinecraftServer server, UUID id) {
        return id == null || server.overworld().getEntity(id) == null;
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes((SCENARIO + ":" + value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static CompoundTag saved(MinecraftServer server) throws Exception { return read(world(server).resolve("data/colonyloom.dat")).getCompound("data"); }

    private static CompoundTag read(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > NBT_LIMIT) throw new IOException("Missing or oversized overflow fixture file " + path);
        return NbtIo.readCompressed(path, NbtAccounter.create(NBT_LIMIT));
    }

    private static void write(Path path, CompoundTag tag) throws IOException {
        NbtIo.writeCompressed(tag, path);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
        if (!read(path).equals(tag)) throw new IOException("Overflow fixture manifest durability mismatch");
    }

    private static UUID uuid(String output, String key) {
        var match = Pattern.compile("(?:^|[\\s\\[,])" + Pattern.quote(key) + "=([0-9a-fA-F-]{36})(?=[\\s\\],]|$)").matcher(output);
        if (!match.find()) throw new IllegalStateException("Command output missing UUID " + key + ": " + output);
        return UUID.fromString(match.group(1));
    }

    private static String coordinates(BlockPos pos) { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }

    private static String command(MinecraftServer server, Run run, String text) throws Exception {
        CommandResult result = tryCommand(server, run, text);
        if (result.code() != 1) throw new IllegalStateException("Public command refused " + text + ": " + result.output());
        return result.output();
    }

    private static CommandResult tryCommand(MinecraftServer server, Run run, String text) throws Exception {
        Capture capture = new Capture();
        int result = server.getCommands().getDispatcher().execute(text,
                run.actor.createCommandSourceStack().withPermission(2).withSource(capture));
        String output = String.join("\n", capture.messages);
        fact(server, "public_command", true, text + " => " + result + ": " + output);
        return new CommandResult(result, output);
    }

    private record CommandResult(int code, String output) {}

    private static final class Capture implements CommandSource {
        final List<String> messages = new ArrayList<>();
        public void sendSystemMessage(Component component) { messages.add(component.getString()); }
        public boolean acceptsSuccess() { return true; }
        public boolean acceptsFailure() { return true; }
        public boolean shouldInformAdmins() { return false; }
    }

    private static void finish(MinecraftServer server, Run run, String check) throws Exception {
        fact(server, check, true, "phase=" + phase());
        run.done = true;
        server.halt(false); // Normal stop performs the real runtime checkpoint and release.
    }

    private static void require(MinecraftServer server, boolean condition, String check, String detail) throws Exception {
        fact(server, check, condition, detail);
        if (!condition) throw new IllegalStateException(check + ": " + detail);
    }

    private static void fact(MinecraftServer server, String check, boolean passed, String detail) throws IOException {
        JsonObject record = new JsonObject();
        record.addProperty("scenario", SCENARIO);
        record.addProperty("phase", phase());
        record.addProperty("tick", server.getTickCount());
        record.addProperty("check", check);
        record.addProperty("passed", passed);
        record.addProperty("detail", detail);
        Path path = world(server).resolve("colonyloom-identity-overflow-observations.jsonl");
        Files.writeString(path, record + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
    }
}
