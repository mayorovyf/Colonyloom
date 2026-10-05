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
    public static void missingDtoMarkerPairsNeverInitializeOverProperty(GameTestHelper helper) throws Exception {
        Path directory=Files.createTempDirectory("colonyloom-pairs-");Path state=directory.resolve("colonyloom.dat"),marker=directory.resolve("colonyloom-session.nbt");
        var empty=ServerRuntime.start(Thread.currentThread()).registry().snapshot();
        try {
            var virgin=io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonyPersistence.preflightPair(state,marker,helper.getLevel().registryAccess(),empty);
            helper.assertTrue(virgin.snapshot().colonies().isEmpty()&&!Files.exists(state)&&!Files.exists(marker),"Virgin preflight writes or activates prior state");
            for(boolean clean:List.of(false,true)) {
                var tag=new CompoundTag();tag.putBoolean("clean",clean);tag.putUUID("sessionId",id(900));tag.putUUID("checkpointId",id(901));NbtIo.writeCompressed(tag,marker);
                byte[] original=Files.readAllBytes(marker);
                try {io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonyPersistence.preflightPair(state,marker,helper.getLevel().registryAccess(),empty);helper.fail("Missing DTO with marker accepted");}catch(java.io.IOException expected){}
                helper.assertTrue(!Files.exists(state)&&Arrays.equals(original,Files.readAllBytes(marker)),"Missing DTO replaced or marker mutated");
            }
            Files.write(marker,new byte[]{9,8,7});
            try {io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonyPersistence.preflightPair(state,marker,helper.getLevel().registryAccess(),empty);helper.fail("Missing DTO with corrupt marker accepted");}catch(java.io.IOException expected){}
            Files.write(state,new byte[]{1,2,3});byte[] damaged=Files.readAllBytes(state);
            try {io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonyPersistence.preflightPair(state,marker,helper.getLevel().registryAccess(),empty);helper.fail("Corrupt DTO pair accepted");}catch(java.io.IOException expected){}
            helper.assertTrue(Arrays.equals(damaged,Files.readAllBytes(state)),"Corrupt DTO overwritten");
            Files.delete(marker);
            try {io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonyPersistence.preflightPair(state,marker,helper.getLevel().registryAccess(),empty);helper.fail("Corrupt DTO without marker accepted");}catch(java.io.IOException expected){}
            var envelope=new CompoundTag();envelope.put("data",fixtureRoot(helper));NbtIo.writeCompressed(envelope,state);
            var restored=io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonyPersistence.preflightPair(state,marker,helper.getLevel().registryAccess(),empty);
            helper.assertTrue(restored.snapshot().citizens().size()==3,"Missing marker erased valid DTO");
        } finally {Files.deleteIfExists(state);Files.deleteIfExists(marker);Files.deleteIfExists(directory);}
        helper.succeed();
    }
    @GameTest(template="identity_empty")
    public static void rootOneMigratesWithImmutableOriginalBackup(GameTestHelper helper) throws Exception {
        var root=fixtureRoot(helper);root.putInt("schemaVersion",1);
        try(var file=new CompressedState(root)) {
            byte[] original=Files.readAllBytes(file.path);var loaded=ColonySavedData.preflight(file.path,helper.getLevel().registryAccess());
            Path backup=file.directory.resolve("colonyloom-backups").resolve(loaded.checkpointId()+"-v1.dat");
            helper.assertTrue(Arrays.equals(original,Files.readAllBytes(backup))&&Arrays.equals(original,Files.readAllBytes(file.path)),"Migration backup not exact or original changed");
            helper.assertTrue(loaded.save(new CompoundTag(),helper.getLevel().registryAccess()).getInt("schemaVersion")==2,"Migration did not encode root2");
            ColonySavedData.preflight(file.path,helper.getLevel().registryAccess());
            Files.write(backup,new byte[]{7});
            try {ColonySavedData.preflight(file.path,helper.getLevel().registryAccess());helper.fail("Conflicting immutable backup accepted");}catch(java.io.IOException expected){}
            helper.assertTrue(Arrays.equals(original,Files.readAllBytes(file.path)),"Conflict rewrote original DTO");
        }
        helper.succeed();
    }
    @GameTest(template="identity_empty")
    public static void oversizeMembersRejectBeforeMaterialization(GameTestHelper helper) throws Exception {
        var root=fixtureRoot(helper);var members=new ListTag();
        for(int i=0;i<=ColonyRuntime.MAX_MEMBERS;i++){var member=new CompoundTag();member.putUUID("playerId",id(10000+i));member.putString("rank","VIEWER");members.add(member);}
        root.getList("colonies",Tag.TAG_COMPOUND).getCompound(0).put("members",members);assertRejected(helper,root,false);helper.succeed();
    }
    @GameTest(template="identity_empty")
    public static void threeHundredBindingObservationsSurviveCompressedPreflight(GameTestHelper helper) throws Exception {
        ServerRuntime source=fixture();
        source.admission().updateLimits(source.admission().limits().scale300Capacity());
        ColonyRuntime colony=source.registry().colony(id(1));
        java.util.ArrayList<CitizenRecord> residents=new java.util.ArrayList<>();
        java.util.ArrayList<BindingRegistry.Observation> observations=new java.util.ArrayList<>();
        for(int i=0;i<300;i++) {
            CitizenRecord resident=citizen(1000+i,1,2000+i,1,4);residents.add(resident);
            observations.add(new BindingRegistry.Observation(resident.citizenId(),resident.entityId(),1,false,false,false));
        }
        source.registry().restore(new RegistrySnapshot(List.of(colony),residents,List.of(),List.of(),observations,List.of(),List.of(),List.of(),List.of(),List.of(),io.github.kpuctajluk.colonyloom.core.storage.StorageSnapshot.empty(),io.github.kpuctajluk.colonyloom.core.supply.SupplySnapshot.empty()));
        ColonySavedData data=ColonySavedData.empty(source.registry().snapshot());
        try(CompressedState file=new CompressedState(data.save(new CompoundTag(),helper.getLevel().registryAccess()))) {
            ColonySavedData loaded=ColonySavedData.preflight(file.path,helper.getLevel().registryAccess());
            ServerRuntime restored=ServerRuntime.start(Thread.currentThread());restored.configureCommands(()->{},List.of());
            restored.admission().updateLimits(restored.admission().limits().scale300Capacity());
            restored.registry().restore(loaded.snapshot());
            for(CitizenRecord resident:residents) {
                restored.bindings().observe(resident.citizenId(),resident.entityId(),1);
                helper.assertTrue(restored.bindings().activeEntity(resident.citizenId()).orElseThrow().equals(resident.entityId()),"Scale identity lost or quarantined after compressed reload");
            }
        }
        helper.succeed();
    }
    @GameTest(template="identity_empty")
    public static void opaqueCitizensKeepSeparateFullBindingHistories(GameTestHelper helper) throws Exception {
        assertOpaqueHistoryIsolation(helper, true);
        helper.succeed();
    }

    @GameTest(template="identity_empty")
    public static void opaqueHistoryDoesNotConsumeUnknownIdentityCapacity(GameTestHelper helper) throws Exception {
        assertOpaqueHistoryIsolation(helper, false);
        helper.succeed();
    }

    private static void assertOpaqueHistoryIsolation(GameTestHelper helper, boolean twoOpaqueColonies) throws Exception {
        CompoundTag root=fixtureRoot(helper);
        root.getList("citizens",Tag.TAG_COMPOUND).getCompound(0).putString("typeId","future:citizen");
        if(twoOpaqueColonies) root.getList("citizens",Tag.TAG_COMPOUND).getCompound(2).putString("typeId","future:citizen");
        ListTag observations=new ListTag();
        for(int scope=0;scope<(twoOpaqueColonies?2:1);scope++) for(int i=0;i<BindingRegistry.MAX_OBSERVATIONS_PER_COLONY;i++) {
            CompoundTag entry=new CompoundTag();entry.putString("typeId","colonyloom:binding_observation");
            entry.putUUID("citizenId",id(scope==0?20:22));entry.putUUID("entityId",id(10000+scope*1000+i));
            entry.putLong("bindingEpoch",1);entry.putBoolean("loaded",false);entry.putBoolean("quarantined",true);entry.putBoolean("retired",true);
            observations.add(entry);
        }
        if(!twoOpaqueColonies) {
            CompoundTag unknown=new CompoundTag();unknown.putString("typeId","colonyloom:binding_observation");unknown.putUUID("entityId",id(20000));
            unknown.putLong("bindingEpoch",0);unknown.putBoolean("loaded",false);unknown.putBoolean("quarantined",true);unknown.putBoolean("retired",false);
            observations.add(unknown);
        }
        root.put("bindingObservations",observations);
        try(CompressedState file=new CompressedState(root)) {
            byte[] original=Files.readAllBytes(file.path);
            ColonySavedData loaded=ColonySavedData.preflight(file.path,helper.getLevel().registryAccess());
            ColonyRegistry restored=new ColonyRegistry(()->{});restored.restore(loaded.snapshot());restored.markContentBlocked(loaded.contentBlockedColonies());
            helper.assertTrue(!restored.colony(id(1)).available() && (twoOpaqueColonies?!restored.colony(id(2)).available():restored.colony(id(2)).available()),"Opaque history leaked its block to an unrelated colony");
            helper.assertTrue(restored.bindings().observations().size()==(twoOpaqueColonies?0:1),"Opaque citizen history became unknown live binding authority");
            CompoundTag saved=loaded.save(new CompoundTag(),helper.getLevel().registryAccess());
            helper.assertTrue(saved.getList("bindingObservations",Tag.TAG_COMPOUND).size()==observations.size() && observations.stream().allMatch(saved.getList("bindingObservations",Tag.TAG_COMPOUND)::contains),"Opaque retired UUIDs or flags were lost on save");
            helper.assertTrue(Arrays.equals(original,Files.readAllBytes(file.path)),"Read-only opaque history preflight rewrote original bytes");
            try(CompressedState roundTrip=new CompressedState(saved)) {
                ColonySavedData reloaded=ColonySavedData.preflight(roundTrip.path,helper.getLevel().registryAccess());
                restored.restore(reloaded.snapshot());restored.markContentBlocked(reloaded.contentBlockedColonies());
                helper.assertTrue(twoOpaqueColonies || restored.colony(id(2)).available(),"Repeated restore blocked independent colony");
            }
        }
    }

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
            var invalid = new RegistrySnapshot(before.colonies(),before.citizens(),before.buildings(),before.tombstones(),before.observations(),before.works(),List.of(before.targetClaims().get(0),before.targetClaims().get(0)),java.util.List.of(),java.util.List.of(),java.util.List.of(),io.github.kpuctajluk.colonyloom.core.storage.StorageSnapshot.empty(),io.github.kpuctajluk.colonyloom.core.supply.SupplySnapshot.empty());
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
            CompoundTag root=ColonySavedData.empty(new RegistrySnapshot(List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),java.util.List.of(),java.util.List.of(),java.util.List.of(),io.github.kpuctajluk.colonyloom.core.storage.StorageSnapshot.empty(),io.github.kpuctajluk.colonyloom.core.supply.SupplySnapshot.empty()))
                    .save(new CompoundTag(),helper.getLevel().registryAccess());
            root.putInt("schemaVersion",ColonySavedData.SCHEMA_VERSION+1);
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
    public static void autosaveKeepsCurrentAuthorityCheckpointAndIndependentOpaquePayload(GameTestHelper helper) throws Exception {
        CompoundTag root = fixtureRoot(helper);
        CompoundTag opaque = unknownWork(id(1));
        root.getList("works", Tag.TAG_COMPOUND).add(opaque);
        ColonySavedData data = ColonySavedData.load(root, helper.getLevel().registryAccess());
        ServerRuntime source = fixture();
        var metrics = new io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics();
        data.metrics(metrics);
        data.bindSnapshotSource(source.registry()::snapshot);
        data.beginCheckpoint(id(91), source.registry().snapshot());
        // Control and clock telemetry change after capture; ordinary autosave must not reuse it.
        CitizenRecord changed = source.registry().citizen(id(20)).withFood(17).withActiveTime(2457);
        source.registry().updateCitizen(changed);
        CompoundTag target = new CompoundTag();
        CompoundTag unrelated = new CompoundTag(); unrelated.putString("owner", "external");
        target.put("unrelated", unrelated);
        target.put("citizens", new ListTag());
        CompoundTag saved = data.save(target, helper.getLevel().registryAccess());
        helper.assertTrue(saved == target && saved.getCompound("unrelated").equals(unrelated), "Save replaced its target or unrelated envelope property");
        helper.assertTrue(saved.getUUID("checkpointId").equals(id(91)) && saved.getInt("schemaVersion") == ColonySavedData.SCHEMA_VERSION, "Save changed checkpoint identity or root version");
        CitizenRecord current = ColonySavedData.load(saved, helper.getLevel().registryAccess()).snapshot().citizens().stream().filter(value -> value.citizenId().equals(id(20))).findFirst().orElseThrow();
        helper.assertTrue(current.food() == 17 && current.activeTimeTicks() == 2457 && current.foodDecayTicks() == 172
                && current.entityId().equals(id(30)) && current.bindingEpoch() == 1, "Autosave used stale capture or rewrote current original identity/timer");
        saved.getList("works", Tag.TAG_COMPOUND).getCompound(0).getCompound("future").putLong("futureCounter", 7);
        source.registry().updateCitizen(changed.withFood(19));
        CompoundTag next = data.save(new CompoundTag(), helper.getLevel().registryAccess());
        helper.assertTrue(next.getList("works", Tag.TAG_COMPOUND).getCompound(0).equals(opaque), "Returned save payload mutated retained opaque source");
        var samples = metrics.snapshot();
        helper.assertTrue(samples.get("SAVE_ENCODE").count() == 2 && samples.get("SAVE").count() == 0
                && samples.get("SAVE_WORLD").count() == 0 && samples.get("SAVE_FLUSH").count() == 0
                && samples.get("MANAGED_TICK").count() == 0, "Autosave encoding attributed latency to disk checkpoint, flush or managed tick");
        try (CompressedState file = new CompressedState(next)) {
            ColonySavedData loaded = ColonySavedData.preflight(file.path, helper.getLevel().registryAccess());
            helper.assertTrue(loaded.checkpointId().equals(id(91)) && loaded.snapshot().citizens().stream().anyMatch(value -> value.citizenId().equals(id(20)) && value.food() == 19 && value.foodDecayTicks() == 172), "Repeated compressed autosave cached prior authority or changed checkpoint");
        }
        helper.succeed();
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
            var report=loaded.retainedReport(id(1));
            helper.assertTrue(report.opaqueCount()==1&&report.records().getFirst().type().equals("unknown_pack:future_work")&&report.records().getFirst().objectId().contains(id(80).toString())
                    && !report.toString().contains("keep this exact data")&&!report.toString().contains("futureCounter")&&loaded.retainedReport(id(2)).opaqueCount()==0,"Scoped report leaked raw opaque NBT or lost type/object cause");
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
    public static void opaqueOperatorReportCapsRowsWithoutDroppingState(GameTestHelper helper) {
        var root=fixtureRoot(helper);var works=new ListTag();
        for(int i=0;i<40;i++){var unknown=unknownWork(id(1));unknown.putUUID("workId",id(8000+i));works.add(unknown);}root.put("works",works);
        var loaded=ColonySavedData.load(root,helper.getLevel().registryAccess());var report=loaded.retainedReport(id(1));
        helper.assertTrue(report.opaqueCount()==40&&report.records().size()==32&&report.truncated()&&loaded.retainedRecordCount()==40,"Report bound trimmed retained state or misreported overflow");
        helper.assertTrue(loaded.save(new CompoundTag(),helper.getLevel().registryAccess()).getList("works",Tag.TAG_COMPOUND).equals(works),"Bounded report changed opaque physical obligations");helper.succeed();
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
        runtime.registry().restore(new RegistrySnapshot(List.of(first, second),citizens,List.of(),List.of(),List.of(),List.of(),List.of(),java.util.List.of(),java.util.List.of(),java.util.List.of(),io.github.kpuctajluk.colonyloom.core.storage.StorageSnapshot.empty(),io.github.kpuctajluk.colonyloom.core.supply.SupplySnapshot.empty()));
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
            Path backups=directory.resolve("colonyloom-backups");
            if(Files.exists(backups)){try(var files=Files.list(backups)){for(var backup:files.toList())Files.delete(backup);}Files.delete(backups);}
            Files.deleteIfExists(path);
            Files.deleteIfExists(directory);
        }
    }
}
