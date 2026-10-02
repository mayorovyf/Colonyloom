package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.core.citizen.BindingRegistry;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.MemberRank;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime;
import io.github.kpuctajluk.colonyloom.core.persistence.RegistrySnapshot;
import io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonySavedData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("colonyloom")
@PrefixGameTestTemplate(false)
public final class PersistenceGameTests {
    @GameTest(template="identity_empty")
    public static void activeTimerRestoresResidualWithoutOfflineProgress(GameTestHelper helper) throws Exception {
        ServerRuntime source = fixture();
        UUID workId = id(120);
        var work = source.workBoard().createTimer(workId, id(1), source.registry().citizen(id(20)).lastKnownPosition(),
                null, 4, io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.NORMAL, 200);
        source.commands().updateCitizenReadiness(id(20), CitizenRecord.Readiness.READY);
        for (int tick = 0; tick < 60; tick++) source.tick(source.serverTick() + 1);
        RegistrySnapshot snapshot = source.registry().snapshot();
        long residual = snapshot.works().stream().filter(value -> value.id().equals(workId)).findFirst().orElseThrow().remainingActiveTicks();
        helper.assertTrue(residual > 0 && residual < 200, "Timer did not make partial active progress");
        ColonySavedData data = ColonySavedData.empty(snapshot);
        try (CompressedState file = new CompressedState(data.save(new CompoundTag(), helper.getLevel().registryAccess()))) {
            ColonySavedData loaded = ColonySavedData.preflight(file.path, helper.getLevel().registryAccess());
            ServerRuntime restored = ServerRuntime.start(Thread.currentThread());
            restored.configureCommands(() -> {}, List.of());
            restored.registry().restore(loaded.snapshot());
            restored.scheduler().rebuild();
            var resumed = restored.workBoard().work(workId);
            helper.assertTrue(resumed.remainingActiveTicks() == residual, "Compressed reload reset remaining active time");
            for (int tick = 0; tick < 40; tick++) restored.tick(restored.serverTick() + 1);
            helper.assertTrue(resumed.remainingActiveTicks() == residual, "Unreconciled/offline citizen advanced timer");
            CitizenRecord citizen = restored.registry().citizen(id(20));
            restored.bindings().observe(citizen.citizenId(), citizen.entityId(), citizen.bindingEpoch());
            restored.commands().updateCitizenReadiness(citizen.citizenId(), CitizenRecord.Readiness.READY);
            for (int tick = 0; tick < 1000 && !resumed.terminal(); tick++) restored.tick(restored.serverTick() + 1);
            helper.assertTrue(resumed.state() == io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.COMPLETED && resumed.remainingActiveTicks() == 0, "Residual timer failed completion");
            long completedRevision = resumed.revision();
            for (int tick = 0; tick < 40; tick++) restored.tick(restored.serverTick() + 1);
            helper.assertTrue(resumed.revision() == completedRevision && restored.registry().citizen(citizen.citizenId()).assignedWorkId() == null, "Timer executed twice or retained assignee");
        }
        helper.succeed();
    }
    @GameTest(template="identity_empty")
    public static void targetClaimsAndMovementSurviveExplicitCodec(GameTestHelper helper) throws Exception {
        ServerRuntime source = fixture();
        var claims = source.registry().targetClaims();
        claims.propose(new io.github.kpuctajluk.colonyloom.core.spatial.TargetClaimRegistry.Snapshot(id(150),id(1),null,"minecraft:overworld",10,64,10,13,65,13,2));
        claims.propose(new io.github.kpuctajluk.colonyloom.core.spatial.TargetClaimRegistry.Snapshot(id(151),id(2),null,"minecraft:overworld",12,64,12,15,65,15,3));
        var move = source.workBoard().createMove(id(152),id(1),new WorldPosition("minecraft:overworld",15,64,15),0,io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.NORMAL);
        ColonySavedData saved = ColonySavedData.empty(source.registry().snapshot());
        try (CompressedState file = new CompressedState(saved.save(new CompoundTag(),helper.getLevel().registryAccess()))) {
            ColonySavedData loaded = ColonySavedData.preflight(file.path,helper.getLevel().registryAccess());
            ServerRuntime restored = ServerRuntime.start(Thread.currentThread()); restored.configureCommands(() -> {},List.of());
            restored.registry().restore(loaded.snapshot());
            var conflict = io.github.kpuctajluk.colonyloom.core.spatial.TargetClaimRegistry.State.CONFLICT;
            helper.assertTrue(restored.registry().targetClaims().state(id(150))==conflict && restored.registry().targetClaims().state(id(151))==conflict,"Saved conflict picked an arbitrary winner");
            helper.assertTrue(!restored.registry().targetClaims().owns(id(150),2) && !restored.registry().targetClaims().owns(id(151),3),"Conflicted persisted target has a grant");
            helper.assertTrue(restored.workBoard().work(move.id()).typeId().equals(io.github.kpuctajluk.colonyloom.core.work.WorkOrder.MOVE),"Move executor type became unknown");
            var before = restored.registry().snapshot();
            var invalid = new RegistrySnapshot(before.colonies(), before.citizens(), before.buildings(), before.tombstones(), before.observations(), before.works(), List.of(before.targetClaims().get(0),before.targetClaims().get(0)), java.util.List.of(), java.util.List.of(), java.util.List.of());
            try { restored.registry().restore(invalid); helper.fail("Duplicate target restore accepted"); } catch (IllegalArgumentException expected) { }
            helper.assertTrue(restored.registry().snapshot().equals(before),"Rejected target restore damaged authoritative state");
        }
        helper.succeed();
    }

    @GameTest(template="identity_empty")
    public static void corruptedAndFutureStateAreNotReplaced(GameTestHelper helper) throws Exception {
        Path directory=Files.createTempDirectory("colonyloom-preflight-test-");
        try {
            Path corrupt=directory.resolve("corrupt.dat");
            byte[] damaged={1,2,3,4}; Files.write(corrupt,damaged);
            try { ColonySavedData.preflight(corrupt,helper.getLevel().registryAccess()); helper.fail("Corrupt file accepted"); }
            catch(java.io.IOException expected) { }
            helper.assertTrue(Arrays.equals(damaged,Files.readAllBytes(corrupt)),"Corrupt file overwritten");
            CompoundTag root=ColonySavedData.empty(new RegistrySnapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of()))
                    .save(new CompoundTag(),helper.getLevel().registryAccess());
            root.putInt("schemaVersion",2);
            CompoundTag envelope=new CompoundTag(); envelope.put("data",root);
            Path future=directory.resolve("future.dat"); NbtIo.writeCompressed(envelope,future);
            byte[] original=Files.readAllBytes(future);
            try { ColonySavedData.preflight(future,helper.getLevel().registryAccess()); helper.fail("Future schema accepted"); }
            catch(java.io.IOException expected) { }
            helper.assertTrue(Arrays.equals(original,Files.readAllBytes(future)),"Future schema overwritten");
            helper.succeed();
        } finally {
            Files.deleteIfExists(directory.resolve("corrupt.dat")); Files.deleteIfExists(directory.resolve("future.dat")); Files.deleteIfExists(directory);
        }
    }

    @GameTest(template="identity_empty")
    public static void populatedIdentitySurvivesCompressedRoundTrip(GameTestHelper helper) throws Exception {
        ServerRuntime source = fixture();
        RegistrySnapshot before = source.registry().snapshot();
        ColonySavedData data = ColonySavedData.empty(before);
        data.beginCheckpoint(id(90), before);
        try (CompressedState file = new CompressedState(data.save(new CompoundTag(), helper.getLevel().registryAccess()))) {
            byte[] original = Files.readAllBytes(file.path);
            ColonySavedData loaded = ColonySavedData.preflight(file.path, helper.getLevel().registryAccess());
            ColonyRegistry restored = new ColonyRegistry(() -> {});
            restored.restore(loaded.snapshot());
            helper.assertTrue(id(90).equals(loaded.checkpointId()), "Checkpoint identity changed");
            helper.assertTrue(before.colonies().equals(restored.colonies()), "Colony UUIDs, ownership or membership changed");
            for (CitizenRecord citizen : before.citizens()) {
                CitizenRecord expected = new CitizenRecord(citizen.citizenId(), citizen.colonyId(), citizen.entityId(), citizen.bindingEpoch(),
                        citizen.homeId(), citizen.workplaceId(), citizen.assignedWorkId(), citizen.professionId(), citizen.skills(), citizen.needs(),
                        citizen.lifecycle(), citizen.admission(), CitizenRecord.Readiness.UNKNOWN, citizen.activeTimeTicks(),
                        citizen.remainingTimers(), citizen.lastKnownPosition(), citizen.revision());
                helper.assertTrue(expected.equals(restored.citizen(citizen.citizenId())), "Citizen identity, epoch, needs or remaining active time changed");
                helper.assertTrue(restored.bindings().activeEntity(citizen.citizenId()).isEmpty(), "Saved observation became an active entity before world reconciliation");
            }
            List<BindingRegistry.Observation> expectedObservations = before.observations().stream().map(value ->
                    new BindingRegistry.Observation(value.citizenId(), value.entityId(), value.bindingEpoch(), false, value.quarantined(), value.retired())).toList();
            helper.assertTrue(expectedObservations.equals(restored.bindings().observations()), "Unloaded identity or quarantine evidence changed");
            helper.assertTrue(restored.colony(id(1)).rank(id(10)) == MemberRank.OWNER
                    && restored.colony(id(1)).rank(id(12)) == MemberRank.MANAGER
                    && restored.colony(id(2)).rank(id(13)) == MemberRank.VIEWER
                    && restored.colony(id(2)).rank(id(12)) == null, "Restored rights leaked between colonies");
            helper.assertTrue(Arrays.equals(original, Files.readAllBytes(file.path)), "Read-only roundtrip mutated compressed state");
        }
        helper.succeed();
    }

    @GameTest(template="identity_empty")
    public static void unknownWorkRetainsItsOriginalNBT(GameTestHelper helper) throws Exception {
        CompoundTag root = fixtureRoot(helper);
        CompoundTag unknown = unknownWork(id(1));
        ListTag works = new ListTag(); works.add(unknown); root.put("works", works);
        try (CompressedState file = new CompressedState(root)) {
            ColonySavedData loaded = ColonySavedData.preflight(file.path, helper.getLevel().registryAccess());
            helper.assertTrue(Set.of(id(1)).equals(loaded.contentBlockedColonies()), "Unknown work did not isolate its owning colony");
            ColonyRegistry restored = new ColonyRegistry(() -> {});
            restored.restore(loaded.snapshot());
            restored.markContentBlocked(loaded.contentBlockedColonies());
            helper.assertTrue(!restored.colony(id(1)).available() && restored.colony(id(2)).available(), "Unrelated colony was blocked by unknown work");
            CompoundTag roundTrip = loaded.save(new CompoundTag(), helper.getLevel().registryAccess());
            helper.assertTrue(unknown.equals(roundTrip.getList("works", Tag.TAG_COMPOUND).getCompound(0)), "Unknown work data was discarded or rewritten");
            helper.assertTrue(restored.citizens().size() == 3, "Independent citizen identities were discarded");
        }
        helper.succeed();
    }

    @GameTest(template="identity_empty")
    public static void citizenDependingOnUnknownWorkRemainsOpaque(GameTestHelper helper) throws Exception {
        CompoundTag root = fixtureRoot(helper);
        CompoundTag unknown = unknownWork(id(2));
        ListTag works = new ListTag(); works.add(unknown); root.put("works", works);
        CompoundTag dependent = root.getList("citizens", Tag.TAG_COMPOUND).getCompound(0);
        dependent.putUUID("assignedWorkId", id(80));
        CompoundTag originalCitizen = dependent.copy();
        try (CompressedState file = new CompressedState(root)) {
            ColonySavedData loaded = ColonySavedData.preflight(file.path, helper.getLevel().registryAccess());
            helper.assertTrue(Set.of(id(1), id(2)).equals(loaded.contentBlockedColonies()), "Dependent citizen colony was not blocked independently of work ownership");
            ColonyRegistry restored = new ColonyRegistry(() -> {});
            restored.restore(loaded.snapshot());
            helper.assertTrue(restored.findCitizen(id(20)).isEmpty() && restored.findCitizen(id(21)).isPresent()
                    && restored.findCitizen(id(22)).isPresent(), "Opaque dependency was activated or independent citizens lost");
            CompoundTag roundTrip = loaded.save(new CompoundTag(), helper.getLevel().registryAccess());
            CompoundTag retainedCitizen = null;
            for (Tag element : roundTrip.getList("citizens", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) element;
                if (entry.getUUID("citizenId").equals(id(20))) retainedCitizen = entry;
            }
            helper.assertTrue(originalCitizen.equals(retainedCitizen), "Known citizen with unavailable work lost its original NBT");
            helper.assertTrue(unknown.equals(roundTrip.getList("works", Tag.TAG_COMPOUND).getCompound(0)), "Dependent work lost its original NBT");
        }
        helper.succeed();
    }

    @GameTest(template="identity_empty")
    public static void malformedListsAndIdentitiesRejectWithoutMutation(GameTestHelper helper) throws Exception {
        CompoundTag missing = fixtureRoot(helper); missing.remove("works");
        assertRejected(helper, missing, false);
        CompoundTag duplicateDependent = fixtureRoot(helper);
        ListTag opaqueWorks = new ListTag(); opaqueWorks.add(unknownWork(id(1))); duplicateDependent.put("works", opaqueWorks);
        ListTag dependentCitizens = duplicateDependent.getList("citizens", Tag.TAG_COMPOUND);
        dependentCitizens.getCompound(0).putUUID("assignedWorkId", id(80));
        dependentCitizens.add(dependentCitizens.getCompound(0).copy());
        assertRejected(helper, duplicateDependent, false);
        CompoundTag wrongList = fixtureRoot(helper); wrongList.putString("citizens", "not a list");
        assertRejected(helper, wrongList, false);
        CompoundTag wrongElement = fixtureRoot(helper);
        ListTag strings = new ListTag(); strings.add(StringTag.valueOf("not a compound")); wrongElement.put("colonies", strings);
        assertRejected(helper, wrongElement, false);
        CompoundTag wrongField = fixtureRoot(helper);
        wrongField.getList("citizens", Tag.TAG_COMPOUND).getCompound(0).putInt("bindingEpoch", 7);
        assertRejected(helper, wrongField, false);
        CompoundTag duplicateColony = fixtureRoot(helper);
        ListTag colonies = duplicateColony.getList("colonies", Tag.TAG_COMPOUND); colonies.add(colonies.getCompound(0).copy());
        assertRejected(helper, duplicateColony, false);
        CompoundTag duplicateCitizen = fixtureRoot(helper);
        ListTag citizens = duplicateCitizen.getList("citizens", Tag.TAG_COMPOUND); citizens.add(citizens.getCompound(0).copy());
        assertRejected(helper, duplicateCitizen, false);
        CompoundTag missingColony = fixtureRoot(helper);
        missingColony.getList("citizens", Tag.TAG_COMPOUND).getCompound(0).putUUID("colonyId", id(99));
        assertRejected(helper, missingColony, false);
        CompoundTag missingWork = fixtureRoot(helper);
        missingWork.getList("citizens", Tag.TAG_COMPOUND).getCompound(0).putUUID("assignedWorkId", id(99));
        assertRejected(helper, missingWork, false);
        CompoundTag duplicateEntity = fixtureRoot(helper);
        duplicateEntity.getList("citizens", Tag.TAG_COMPOUND).getCompound(1).putUUID("entityId", id(30));
        assertRejected(helper, duplicateEntity, true);
        CompoundTag crossKindId = fixtureRoot(helper);
        crossKindId.getList("citizens", Tag.TAG_COMPOUND).getCompound(0).putUUID("citizenId", id(1));
        assertRejected(helper, crossKindId, true);
        CompoundTag foreignHome = fixtureRoot(helper);
        CompoundTag building = new CompoundTag(); building.putString("typeId", "colonyloom:building");
        building.putUUID("buildingId", id(70)); building.putUUID("colonyId", id(2));
        building.putString("buildingTypeId", "colonyloom:workshop"); building.putLong("revision", 1);
        building.put("position", foreignHome.getList("citizens", Tag.TAG_COMPOUND).getCompound(2).getCompound("lastKnownPosition").copy());
        foreignHome.getList("buildings", Tag.TAG_COMPOUND).add(building);
        foreignHome.getList("citizens", Tag.TAG_COMPOUND).getCompound(0).putUUID("homeId", id(70));
        assertRejected(helper, foreignHome, true);
        helper.succeed();
    }

    private static void assertRejected(GameTestHelper helper, CompoundTag root, boolean validateRegistry) throws Exception {
        try (CompressedState file = new CompressedState(root)) {
            byte[] original = Files.readAllBytes(file.path);
            ColonyRegistry registry = fixture().registry();
            RegistrySnapshot before = registry.snapshot();
            boolean rejected = false;
            try {
                ColonySavedData loaded = ColonySavedData.preflight(file.path, helper.getLevel().registryAccess());
                // Core cross-reference checks belong to restore, not the public read-only codec preflight.
                if (validateRegistry) registry.restore(loaded.snapshot());
            } catch (java.io.IOException | IllegalArgumentException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "Malformed persisted state was accepted");
            helper.assertTrue(before.equals(registry.snapshot()), "Rejected restore partially replaced authoritative state");
            helper.assertTrue(Arrays.equals(original, Files.readAllBytes(file.path)), "Rejected persisted state was overwritten");
        }
    }

    private static CompoundTag fixtureRoot(GameTestHelper helper) {
        ColonySavedData data = ColonySavedData.empty(fixture().registry().snapshot());
        data.beginCheckpoint(id(90), data.snapshot());
        return data.save(new CompoundTag(), helper.getLevel().registryAccess());
    }

    private static ServerRuntime fixture() {
        ServerRuntime runtime = ServerRuntime.start(Thread.currentThread());
        Thread owner = Thread.currentThread();
        runtime.configureCommands(() -> {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Fixture mutation outside owner thread");
        }, List.of());
        ColonyRuntime first = new ColonyRuntime(id(1), "First", new Territory("minecraft:overworld", 0, 0, 31, 31),
                id(10), Map.of(id(12), MemberRank.MANAGER, id(13), MemberRank.VIEWER), 8, 4, false, null, false);
        ColonyRuntime second = new ColonyRuntime(id(2), "Second", new Territory("minecraft:overworld", 64, 0, 95, 31),
                id(11), Map.of(id(13), MemberRank.VIEWER), 5, 2, false, null, false);
        List<CitizenRecord> citizens = List.of(citizen(20, 1, 30, 1, 4), citizen(21, 1, 31, 7, 8), citizen(22, 2, 32, 3, 68));
        runtime.registry().restore(new RegistrySnapshot(List.of(first, second), citizens, List.of(), List.of(), List.of(), List.of(), List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of()));
        for (CitizenRecord citizen : citizens) runtime.bindings().observe(citizen.citizenId(), citizen.entityId(), citizen.bindingEpoch());
        runtime.bindings().unload(id(31));
        runtime.bindings().observe(id(22), id(33), 2);
        return runtime;
    }

    private static CitizenRecord citizen(long citizen, long colony, long entity, long epoch, int x) {
        return new CitizenRecord(id(citizen), id(colony), id(entity), epoch, null, null, null, "colonyloom:builder",
                Map.of("building", 3), Map.of("food", 11), CitizenRecord.Lifecycle.ALIVE, CitizenRecord.Admission.ACTIVE,
                CitizenRecord.Readiness.UNKNOWN, 2456, Map.of("food", 173L, "assignment", 41L),
                new WorldPosition("minecraft:overworld", x, 64, 4), 9);
    }

    private static CompoundTag unknownWork(UUID colonyId) {
        CompoundTag unknown = new CompoundTag(); unknown.putString("typeId", "unknown_pack:future_work");
        unknown.putUUID("workId", id(80)); unknown.putUUID("colonyId", colonyId);
        unknown.putString("opaque", "keep this exact data");
        CompoundTag nested = new CompoundTag(); nested.putLong("futureCounter", Long.MAX_VALUE); unknown.put("future", nested);
        return unknown;
    }

    private static UUID id(long value) { return new UUID(0, value); }

    private static final class CompressedState implements AutoCloseable {
        private final Path directory;
        private final Path path;

        private CompressedState(CompoundTag root) throws java.io.IOException {
            directory = Files.createTempDirectory("colonyloom-populated-preflight-");
            path = directory.resolve("colonyloom.dat");
            CompoundTag envelope = new CompoundTag(); envelope.put("data", root);
            try {
                NbtIo.writeCompressed(envelope, path);
            } catch (java.io.IOException | RuntimeException failure) {
                Files.deleteIfExists(path); Files.deleteIfExists(directory);
                throw failure;
            }
        }

        @Override
        public void close() throws java.io.IOException {
            Files.deleteIfExists(path);
            Files.deleteIfExists(directory);
        }
    }
}
