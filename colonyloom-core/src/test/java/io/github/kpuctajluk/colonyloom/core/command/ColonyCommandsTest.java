package io.github.kpuctajluk.colonyloom.core.command;

import io.github.kpuctajluk.colonyloom.core.citizen.BindingRegistry;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.citizen.ProfessionDefinition;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.MemberRank;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.*;
import io.github.kpuctajluk.colonyloom.core.management.ManagementSession;
import io.github.kpuctajluk.colonyloom.core.persistence.RegistrySnapshot;
import io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ColonyCommandsTest {
    private static final ColonyCommands.PhysicalChecks CHECKS = new ColonyCommands.PhysicalChecks() {
        public void validateTerritory(Territory territory) {}
        public void validateCitizenPosition(ColonyRuntime colony, WorldPosition position) {}
        public void validateRecovery(ColonyRuntime colony, List<CitizenRecord> citizens, List<BindingRegistry.Observation> observations) {}
    };
    private final UUID owner = UUID.randomUUID();
    private final List<CitizenRecord> spawned = new ArrayList<>();
    private ServerRuntime runtime() {
        ServerRuntime runtime = ServerRuntime.start(Thread.currentThread());
        runtime.configureCommands(() -> {}, List.of(new ProfessionDefinition("colonyloom:builder", 1, Set.of("colonyloom:construction"), Set.of())));
        return runtime;
    }
    private ColonyCommands.CommandContext context(UUID player, boolean operator) { return new ColonyCommands.CommandContext(player, operator, CHECKS); }
    private ColonyRuntime colony(ServerRuntime runtime, int x) {
        return runtime.commands().createColony(context(owner, false), UUID.randomUUID(), "Shared name", new Territory("minecraft:overworld", x, 0, x + 31, 31));
    }
    private CitizenRecord citizen(ServerRuntime runtime, ColonyRuntime colony) {
        CitizenRecord citizen = runtime.commands().createCitizen(context(owner, true), colony.colonyId(), UUID.randomUUID(), UUID.randomUUID(), new WorldPosition("minecraft:overworld", colony.territory().minX(), 64, 0), spawned::add);
        runtime.bindings().observe(citizen.citizenId(), citizen.entityId(), citizen.bindingEpoch());
        return citizen;
    }
    @Test void historyOverflowIsScopedAndRetiredIdentitiesStayDurable() {
        var runtime=runtime();var first=colony(runtime,0);var second=colony(runtime,64);
        var resident=citizen(runtime,first);var independent=citizen(runtime,second);
        for(int i=1;i<BindingRegistry.MAX_OBSERVATIONS_PER_COLONY;i++) {
            runtime.bindings().observe(resident.citizenId(),UUID.randomUUID(),resident.bindingEpoch());
            runtime.commands().bind(context(owner,true),resident.citizenId(),resident.entityId());
            resident=runtime.registry().citizen(resident.citizenId());
        }
        var citizenId=resident.citizenId();var before=runtime.registry().snapshot();
        var overflow=assertThrows(BindingRegistry.HistoryOverflow.class,() -> runtime.bindings().observe(citizenId,UUID.randomUUID(),1));
        assertEquals(first.colonyId(),overflow.colonyId());assertEquals(before,runtime.registry().snapshot());
        runtime.registry().markRecoveryBlocked(first.colonyId(),UUID.randomUUID());
        assertEquals(independent.entityId(),runtime.bindings().activeEntity(independent.citizenId()).orElseThrow());
        assertTrue(runtime.registry().colony(second.colonyId()).available());
        var restored=runtime();restored.registry().restore(runtime.registry().snapshot());
        var retired=restored.bindings().observations(citizenId).stream().filter(BindingRegistry.Observation::retired).findFirst().orElseThrow();
        restored.bindings().observe(citizenId,retired.entityId(),retired.bindingEpoch());
        assertTrue(restored.bindings().observations(citizenId).stream().filter(value -> value.entityId().equals(retired.entityId())).findFirst().orElseThrow().retired());
        restored.bindings().observe(independent.citizenId(),independent.entityId(),independent.bindingEpoch());
        assertEquals(independent.entityId(),restored.bindings().activeEntity(independent.citizenId()).orElseThrow());
        var invalid=new java.util.ArrayList<>(restored.bindings().observations());invalid.add(new BindingRegistry.Observation(citizenId,UUID.randomUUID(),1,false,true,true));
        var current=restored.registry().snapshot();var oversized=new RegistrySnapshot(current.colonies(),current.citizens(),current.buildings(),current.tombstones(),invalid,current.works(),current.targetClaims(),current.effects(),current.constructionSites(),current.pinnedBlueprints(),current.storage(),current.supply());
        assertThrows(IllegalArgumentException.class,() -> restored.registry().restore(oversized));assertEquals(current,restored.registry().snapshot());
    }
    @Test void clockAndPositionDoNotStaleAssignmentButControlChangesDo() {
        var runtime=runtime();var colony=colony(runtime,0);var initial=citizen(runtime,colony);
        var current=new CitizenRecord[]{initial};
        var backend=new ManagementSession.Backend() {
            public ManagementSession.Authority authorize(UUID id) {
                return new ManagementSession.Authority(true,1);
            }
            public long targetRevision(Command command) {return current[0].revision();}
            public Result execute(Command command) {
                current[0]=runtime.commands().assignProfession(context(owner,false),initial.citizenId(),"colonyloom:builder");
                return new Result(command.sequence(),Status.ACCEPTED,"ACCEPTED",initial.citizenId(),current[0].revision());
            }
            public ViewData view(Subscription subscription) {throw new UnsupportedOperationException();}
        };
        var transport=new ManagementSession.Transport() {
            public boolean writable(){return true;}
            public void sendResult(Result result){}
            public void sendSnapshot(UUID id,ViewData data){}
            public void sendDelta(UUID id,long base,ViewData data){}
            public void closeView(UUID id,String reason){}
        };
        var session=new ManagementSession(backend,transport);
        current[0]=initial.withActiveTime(10).withPosition(new WorldPosition("minecraft:overworld",3,64,3));runtime.registry().updateCitizen(current[0]);
        var first=session.command(new Command(session.sessionId(),0,colony.colonyId(),initial.revision(),new AssignProfession(initial.citizenId(),"colonyloom:builder")));
        assertEquals(Status.ACCEPTED,first.status());
        assertEquals("colonyloom:builder",runtime.registry().citizen(initial.citizenId()).professionId());
        var duplicateEditor=session.command(new Command(session.sessionId(),1,colony.colonyId(),initial.revision(),new AssignProfession(initial.citizenId(),"colonyloom:builder")));
        assertEquals(Status.STALE,duplicateEditor.status());
        assertTrue(current[0].withActiveTime(1200).revision()>current[0].revision());
    }
    @Test void schedulerInvalidationsDoNotStaleWorkControlsButCompetingEditorsDo() {
        var runtime = runtime();
        var colony = colony(runtime, 0);
        var work = runtime.commands().createTimerWork(context(owner, false), UUID.randomUUID(),
                colony.colonyId(), new WorldPosition("minecraft:overworld", 8, 64, 8), null, 0, 20);
        var backend = new ManagementSession.Backend() {
            public ManagementSession.Authority authorize(UUID colonyId) { return new ManagementSession.Authority(true, 0); }
            public long targetRevision(Command command) { return work.commandRevision(); }
            public Result execute(Command command) {
                var priority = (PrioritizeWork) command.body();
                runtime.commands().prioritizeWork(context(owner, false), priority.workId(), priority.priority());
                return new Result(command.sequence(), Status.ACCEPTED, "ACCEPTED", work.id(), work.commandRevision());
            }
            public ViewData view(Subscription subscription) { throw new AssertionError("No subscription"); }
        };
        var transport = new ManagementSession.Transport() {
            public boolean writable() { return true; }
            public void sendResult(Result result) {}
            public void sendSnapshot(UUID id, ViewData data) {}
            public void sendDelta(UUID id, long base, ViewData data) {}
            public void closeView(UUID id, String reason) {}
        };
        long observed = work.commandRevision();
        for (int tick = 0; tick < 100; tick++) runtime.workBoard().invalidate(work.id());
        var first = new ManagementSession(backend, transport);
        var second = new ManagementSession(backend, transport);
        assertEquals(Status.ACCEPTED, first.command(new Command(first.sessionId(), 0, colony.colonyId(),
                observed, new PrioritizeWork(work.id(), 7))).status());
        assertEquals(7, work.priority());
        assertEquals(Status.STALE, second.command(new Command(second.sessionId(), 0, colony.colonyId(),
                observed, new PrioritizeWork(work.id(), 9))).status());
        assertEquals(7, work.priority());
        long beforeCancel = work.commandRevision();
        runtime.commands().cancelWork(context(owner, false), work.id());
        assertTrue(work.commandRevision() > beforeCancel);
        var restored = runtime();
        restored.registry().restore(runtime.registry().snapshot());
        assertEquals(work.commandRevision(), restored.workBoard().work(work.id()).commandRevision());
    }

    @Test void workplaceAssignmentRequiresCurrentManagerAndLocalRegisteredWorkshop() {
        ServerRuntime runtime=runtime();var local=colony(runtime,0);var foreign=colony(runtime,64);var citizen=citizen(runtime,local);
        var stock=runtime.registry().storage();var a=new WorldPosition("minecraft:overworld",8,64,8);var b=new WorldPosition("minecraft:overworld",72,64,8);
        var localId=new io.github.kpuctajluk.colonyloom.core.storage.StorageId(a.dimension(),UUID.randomUUID(),0);
        var foreignId=new io.github.kpuctajluk.colonyloom.core.storage.StorageId(b.dimension(),UUID.randomUUID(),0);
        var localRegistration=stock.register(local.colonyId(),a,"workshop",List.of(localId),List.of(new io.github.kpuctajluk.colonyloom.core.storage.StockRegion(localId,0)),List.of(a));
        var foreignRegistration=stock.register(foreign.colonyId(),b,"workshop",List.of(foreignId),List.of(new io.github.kpuctajluk.colonyloom.core.storage.StockRegion(foreignId,0)),List.of(b));
        var localWorkshop=stock.registerWorkshop(local.colonyId(),a,localRegistration.id());var foreignWorkshop=stock.registerWorkshop(foreign.colonyId(),b,foreignRegistration.id());
        UUID viewer=UUID.randomUUID();runtime.commands().setMember(context(owner,false),local.colonyId(),viewer,MemberRank.VIEWER);
        var before=runtime.registry().snapshot();
        assertThrows(SecurityException.class,() -> runtime.commands().assignWorkplace(context(viewer,true),citizen.citizenId(),localWorkshop.id()));
        assertThrows(SecurityException.class,() -> runtime.commands().assignWorkplace(context(owner,false),citizen.citizenId(),foreignWorkshop.id()));
        assertEquals(before,runtime.registry().snapshot());
        runtime.commands().assignWorkplace(context(owner,false),citizen.citizenId(),localWorkshop.id());
        var restored=runtime();restored.registry().restore(runtime.registry().snapshot());assertEquals(localWorkshop.id(),restored.registry().citizen(citizen.citizenId()).workplaceId());
    }
    @Test void scaleResidentsRetainIdentityAndCompetingEmbodimentAcrossRestore() {
        ServerRuntime runtime=runtime();
        runtime.registry().admission().updateLimits(runtime.registry().admission().limits().scale300Capacity());
        ColonyRuntime colony=colony(runtime,0);
        List<CitizenRecord> residents=new ArrayList<>();
        for(int i=0;i<300;i++) residents.add(citizen(runtime,colony));
        CitizenRecord first=residents.getFirst();UUID competitor=new UUID(1,1);
        runtime.bindings().observe(first.citizenId(),competitor,first.bindingEpoch());
        assertTrue(runtime.bindings().activeEntity(first.citizenId()).isEmpty());
        ServerRuntime restored=runtime();
        restored.registry().admission().updateLimits(restored.registry().admission().limits().scale300Capacity());
        restored.registry().restore(runtime.registry().snapshot());
        for(CitizenRecord resident:residents) {
            restored.bindings().observe(resident.citizenId(),resident.entityId(),resident.bindingEpoch());
            if(!resident.citizenId().equals(first.citizenId())) assertEquals(resident.entityId(),restored.bindings().activeEntity(resident.citizenId()).orElseThrow());
        }
        assertTrue(restored.bindings().activeEntity(first.citizenId()).isEmpty());
        assertTrue(restored.bindings().observations(first.citizenId()).stream().allMatch(BindingRegistry.Observation::quarantined));
    }
    @Test void validInactiveEmbodimentCanReconcileWithoutAdmissionCatchup() {
        ServerRuntime runtime = runtime(); ColonyRuntime colony = colony(runtime,0); CitizenRecord citizen = citizen(runtime,colony);
        runtime.commands().updateCitizenReadiness(citizen.citizenId(),CitizenRecord.Readiness.READY);
        runtime.commands().updateCitizenAdmission(citizen.citizenId(),CitizenRecord.Admission.INACTIVE);
        runtime.bindings().unload(citizen.entityId());
        runtime.commands().updateCitizenReadiness(citizen.citizenId(),CitizenRecord.Readiness.UNKNOWN);
        runtime.bindings().observe(citizen.citizenId(),citizen.entityId(),citizen.bindingEpoch());
        runtime.commands().updateCitizenReadiness(citizen.citizenId(),CitizenRecord.Readiness.READY);
        assertEquals(citizen.entityId(),runtime.bindings().activeEntity(citizen.citizenId()).orElseThrow());
        assertEquals(CitizenRecord.Admission.INACTIVE,runtime.registry().citizen(citizen.citizenId()).admission());
        runtime.commands().updateCitizenAdmission(citizen.citizenId(),CitizenRecord.Admission.ACTIVE);
        CitizenRecord active = runtime.registry().citizen(citizen.citizenId());
        assertEquals(citizen.activeTimeTicks(),active.activeTimeTicks());assertEquals(citizen.remainingTimers(),active.remainingTimers());
    }
    @Test
    void workAuthorityAndCitizenAdmissionRejectBeforePhysicalEffects() {
        ServerRuntime runtime = runtime();
        ColonyRuntime colony = colony(runtime, 0);
        UUID viewer = UUID.randomUUID();
        runtime.commands().setMember(context(owner, false), colony.colonyId(), viewer, MemberRank.VIEWER);
        WorldPosition target = new WorldPosition("minecraft:overworld", 1, 64, 1);
        assertThrows(SecurityException.class, () -> runtime.commands().createTimerWork(context(viewer, true), UUID.randomUUID(), colony.colonyId(), target, null, 0, 20));
        var work = runtime.commands().createTimerWork(context(owner, false), UUID.randomUUID(), colony.colonyId(), target, null, 0, 20);
        assertThrows(SecurityException.class, () -> runtime.commands().cancelWork(context(viewer, true), work.id()));
        runtime.registry().markRecoveryBlocked(UUID.randomUUID());
        runtime.commands().cancelWork(context(owner, false), work.id());
        assertEquals(io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.CANCELLED, work.state());
        ServerRuntime capped = runtime();
        ColonyRuntime other = colony(capped, 64);
        capped.updateLimits(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.development()
                .withResource(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.CITIZENS, 1));
        citizen(capped, other);
        int physicalCount = spawned.size();
        assertThrows(IllegalStateException.class, () -> citizen(capped, other));
        assertEquals(physicalCount, spawned.size());
        assertEquals(1, capped.registry().citizens().size());
    }


    @Test
    void membershipAndOperatorPrivilegesNeverCrossColonyAuthority() {
        ServerRuntime runtime = runtime();
        ColonyRuntime colony = colony(runtime, 0);
        CitizenRecord citizen = citizen(runtime, colony);
        UUID manager = UUID.randomUUID(); UUID viewer = UUID.randomUUID(); UUID outsider = UUID.randomUUID();
        runtime.commands().setMember(context(owner, false), colony.colonyId(), manager, MemberRank.MANAGER);
        runtime.commands().setMember(context(owner, false), colony.colonyId(), viewer, MemberRank.VIEWER);
        assertEquals(colony.colonyId(), runtime.commands().status(context(viewer, false), colony.colonyId()).colonyId());
        assertThrows(SecurityException.class, () -> runtime.commands().status(context(outsider, true), colony.colonyId()));
        RegistrySnapshot before = runtime.registry().snapshot();
        assertThrows(SecurityException.class, () -> runtime.commands().assignProfession(context(viewer, true), citizen.citizenId(), "colonyloom:builder"));
        assertThrows(SecurityException.class, () -> runtime.commands().setOwner(context(manager, true), colony.colonyId(), manager));
        assertThrows(IllegalArgumentException.class, () -> runtime.commands().assignProfession(context(manager, false), citizen.citizenId(), "colonyloom:missing"));
        assertEquals(before, runtime.registry().snapshot());
        runtime.commands().assignProfession(context(manager, false), citizen.citizenId(), "colonyloom:builder");
        assertEquals("colonyloom:builder", runtime.registry().citizen(citizen.citizenId()).professionId());
        runtime.commands().setOwner(context(owner, false), colony.colonyId(), manager);
        assertEquals(MemberRank.OWNER, runtime.registry().colony(colony.colonyId()).rank(manager));
        assertThrows(SecurityException.class, () -> runtime.commands().setMember(context(owner, false), colony.colonyId(), viewer, null));
    }

    @Test void memberLimitRejectsGrowthAndOutsiderOwnershipBeforeMutationButAllowsReplacement() {
        ServerRuntime runtime = runtime(); ColonyRuntime colony = colony(runtime, 0);
        List<UUID> members = new ArrayList<>();
        for (int index = 0; index < ColonyRuntime.MAX_MEMBERS; index++) {
            UUID member = UUID.randomUUID(); members.add(member);
            runtime.commands().setMember(context(owner, false), colony.colonyId(), member, MemberRank.VIEWER);
        }
        int[] gates = {0}; runtime.configureCommands(() -> gates[0]++, List.of());
        RegistrySnapshot before = runtime.registry().snapshot(); UUID outsider = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> runtime.commands().setMember(context(owner, false), colony.colonyId(), outsider, MemberRank.MANAGER));
        assertThrows(IllegalStateException.class, () -> runtime.commands().setOwner(context(owner, false), colony.colonyId(), outsider));
        assertEquals(before, runtime.registry().snapshot()); assertEquals(0, gates[0]);
        assertSame(runtime.registry().colony(colony.colonyId()), runtime.commands().setOwner(context(owner, false), colony.colonyId(), owner));
        runtime.commands().setMember(context(owner, false), colony.colonyId(), members.getFirst(), MemberRank.MANAGER);
        ColonyRuntime transferred = runtime.commands().setOwner(context(owner, false), colony.colonyId(), members.getFirst());
        assertEquals(ColonyRuntime.MAX_MEMBERS, transferred.members().size());
        assertEquals(MemberRank.OWNER, transferred.rank(members.getFirst()));
        assertEquals(MemberRank.MANAGER, transferred.rank(owner));
        runtime.commands().setMember(context(members.getFirst(), false), colony.colonyId(), members.getLast(), null);
        runtime.commands().setMember(context(members.getFirst(), false), colony.colonyId(), outsider, MemberRank.VIEWER);
        assertEquals(ColonyRuntime.MAX_MEMBERS, runtime.registry().colony(colony.colonyId()).members().size());
        ServerRuntime restored = runtime(); restored.registry().restore(runtime.registry().snapshot());
        assertEquals(runtime.registry().colony(colony.colonyId()), restored.registry().colony(colony.colonyId()));
    }

    @Test void outsiderOwnerTransferChargesExactlyOneMemberSlotForPreviousOwner() {
        ServerRuntime runtime = runtime(); ColonyRuntime colony = colony(runtime, 0);
        for (int index = 0; index < ColonyRuntime.MAX_MEMBERS - 1; index++)
            runtime.commands().setMember(context(owner, false), colony.colonyId(), UUID.randomUUID(), MemberRank.VIEWER);
        UUID outsider = UUID.randomUUID(); long revision = runtime.registry().colony(colony.colonyId()).authorityRevision();
        ColonyRuntime changed = runtime.commands().setOwner(context(owner, false), colony.colonyId(), outsider);
        assertEquals(ColonyRuntime.MAX_MEMBERS, changed.members().size());
        assertEquals(MemberRank.MANAGER, changed.rank(owner)); assertEquals(MemberRank.OWNER, changed.rank(outsider));
        assertEquals(revision + 1, changed.authorityRevision());
    }

    @Test void aggregateRejectsOversizedMembersBeforeCopyingTheirEntries() {
        var tooLarge = new java.util.AbstractMap<UUID, MemberRank>() {
            @Override public int size() { return ColonyRuntime.MAX_MEMBERS + 1; }
            @Override public Set<java.util.Map.Entry<UUID, MemberRank>> entrySet() {
                throw new AssertionError("Oversized membership was copied before admission");
            }
        };
        var error = assertThrows(IllegalArgumentException.class, () -> new ColonyRuntime(UUID.randomUUID(), "Bounded",
                new Territory("minecraft:overworld", 0, 0, 15, 15), owner, tooLarge, 1, 1, false, null, false));
        assertEquals("MEMBER_LIMIT", error.getMessage());
    }

    @Test void foundingCapacityIsCheckedBeforePhysicalValidationAndSessionMutation() {
        ServerRuntime runtime = runtime(); colony(runtime, 0); colony(runtime, 32); colony(runtime, 64);
        int[] scans = {0}, gates = {0}; runtime.configureCommands(() -> gates[0]++, List.of());
        var checks = new ColonyCommands.PhysicalChecks() {
            public void validateTerritory(Territory territory) { scans[0]++; }
            public void validateCitizenPosition(ColonyRuntime colony, WorldPosition position) {}
            public void validateRecovery(ColonyRuntime colony, List<CitizenRecord> citizens, List<BindingRegistry.Observation> observations) {}
        };
        RegistrySnapshot before = runtime.registry().snapshot();
        long rejected = runtime.admission().rejected(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.COLONIES);
        assertThrows(IllegalStateException.class, () -> runtime.commands().createColony(new ColonyCommands.CommandContext(owner, false, checks),
                UUID.randomUUID(), "No capacity", new Territory("minecraft:overworld", 96, 0, 223, 127)));
        assertEquals(0, scans[0]); assertEquals(0, gates[0]); assertEquals(before, runtime.registry().snapshot());
        assertEquals(rejected + 1, runtime.admission().rejected(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.COLONIES));
    }

    @Test void physicalFoundingFailureDoesNotPublishIdentityOrConsumeColonyCapacity() {
        ServerRuntime runtime = runtime(); int[] gates = {0}; runtime.configureCommands(() -> gates[0]++, List.of());
        var checks = new ColonyCommands.PhysicalChecks() {
            public void validateTerritory(Territory territory) { throw new SecurityException("Interior protected"); }
            public void validateCitizenPosition(ColonyRuntime colony, WorldPosition position) {}
            public void validateRecovery(ColonyRuntime colony, List<CitizenRecord> citizens, List<BindingRegistry.Observation> observations) {}
        };
        RegistrySnapshot before = runtime.registry().snapshot(); UUID rejected = UUID.randomUUID();
        assertThrows(SecurityException.class, () -> runtime.commands().createColony(new ColonyCommands.CommandContext(owner, false, checks),
                rejected, "Protected", new Territory("minecraft:overworld", 0, 0, 127, 127)));
        assertEquals(before, runtime.registry().snapshot()); assertFalse(runtime.registry().usedId(rejected)); assertEquals(0, gates[0]);
        assertEquals(0, runtime.admission().used(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.COLONIES));
        assertNotNull(colony(runtime, 0));
    }

    @Test
    void territoryBoundariesAndFailedSpawnLeaveNoPartialIdentity() {
        ServerRuntime runtime = runtime(); ColonyRuntime colony = colony(runtime, 0);
        assertThrows(IllegalArgumentException.class, () -> new Territory("minecraft:overworld", 0, 0, 14, 15));
        assertThrows(IllegalArgumentException.class, () -> new Territory("minecraft:overworld", 0, 0, 128, 15));
        new Territory("minecraft:overworld", Integer.MAX_VALUE - 15, 0, Integer.MAX_VALUE, 15);
        RegistrySnapshot before = runtime.registry().snapshot();
        assertThrows(IllegalArgumentException.class, () -> runtime.commands().createColony(context(owner, false), UUID.randomUUID(), "Overlap", new Territory("minecraft:overworld", 31, 0, 46, 15)));
        assertThrows(IllegalStateException.class, () -> runtime.commands().createCitizen(context(owner, true), colony.colonyId(), UUID.randomUUID(), UUID.randomUUID(), new WorldPosition("minecraft:overworld", 1, 64, 1), proposed -> { throw new IllegalStateException("Spawn veto"); }));
        assertEquals(before, runtime.registry().snapshot());
        UUID unknownColony = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> runtime.commands().createCitizen(context(owner, true), unknownColony, UUID.randomUUID(), UUID.randomUUID(), new WorldPosition("minecraft:overworld", 1, 64, 1), spawned::add));
        assertEquals(List.of(), spawned);
        colony(runtime, 32);
        colony(runtime, 64);
        assertThrows(IllegalStateException.class, () -> colony(runtime, 96));
        assertEquals(3, runtime.registry().colonies().size());
    }

    @Test
    void twoColoniesThreeCitizensRestoreIdentityRightsAndTimersWithoutCrossWorldLeak() {
        ServerRuntime original = runtime(); ColonyRuntime first = colony(original, 0); ColonyRuntime second = colony(original, 64);
        CitizenRecord a = citizen(original, first); CitizenRecord b = citizen(original, first); CitizenRecord c = citizen(original, second);
        UUID viewer = UUID.randomUUID(); original.commands().setMember(context(owner, false), first.colonyId(), viewer, MemberRank.VIEWER);
        original.commands().assignProfession(context(owner, false), b.citizenId(), "colonyloom:builder");
        RegistrySnapshot saved = original.registry().snapshot();
        original.beginStopping(); original.stop();
        ServerRuntime restored = runtime(); restored.registry().restore(saved);
        assertEquals(saved.colonies(), restored.registry().colonies());
        assertEquals(saved.citizens(), restored.registry().citizens());
        assertEquals(MemberRank.VIEWER, restored.registry().colony(first.colonyId()).rank(viewer));
        for (CitizenRecord citizen : List.of(a, b, c)) {
            assertTrue(restored.bindings().activeEntity(citizen.citizenId()).isEmpty());
            restored.bindings().observe(citizen.citizenId(), citizen.entityId(), citizen.bindingEpoch());
            assertEquals(citizen.entityId(), restored.bindings().activeEntity(citizen.citizenId()).orElseThrow());
            assertEquals(1200L, restored.registry().citizen(citizen.citizenId()).remainingTimers().get("food"));
        }
        assertTrue(runtime().registry().citizens().isEmpty());
    }

    @Test
    void duplicateAndStaleEpochQuarantineOriginalAcrossUnloadAndRestartUntilExplicitBind() {
        ServerRuntime runtime = runtime(); ColonyRuntime colony = colony(runtime, 0); CitizenRecord citizen = citizen(runtime, colony);
        runtime.bindings().unload(citizen.entityId());
        assertEquals(CitizenRecord.Lifecycle.ALIVE, runtime.registry().citizen(citizen.citizenId()).lifecycle());
        assertEquals(1, runtime.registry().citizens().size());
        runtime.bindings().observe(citizen.citizenId(), citizen.entityId(), 1);
        UUID duplicate = UUID.randomUUID(); runtime.bindings().observe(citizen.citizenId(), duplicate, 0);
        assertTrue(runtime.bindings().activeEntity(citizen.citizenId()).isEmpty());
        assertTrue(runtime.bindings().observations(citizen.citizenId()).stream().allMatch(BindingRegistry.Observation::quarantined));
        runtime.bindings().unload(duplicate);
        assertThrows(IllegalStateException.class, () -> runtime.commands().bind(context(owner, true), citizen.citizenId(), citizen.entityId()));
        ServerRuntime restored = runtime(); restored.registry().restore(runtime.registry().snapshot());
        restored.bindings().observe(citizen.citizenId(), citizen.entityId(), 1);
        assertTrue(restored.bindings().activeEntity(citizen.citizenId()).isEmpty());
        assertThrows(IllegalStateException.class, () -> restored.commands().bind(context(owner, true), citizen.citizenId(), citizen.entityId()));
        restored.bindings().observe(citizen.citizenId(), duplicate, 0);
        CitizenRecord bound = restored.commands().bind(context(owner, true), citizen.citizenId(), citizen.entityId());
        assertEquals(2, bound.bindingEpoch());
        assertEquals(citizen.entityId(), restored.bindings().activeEntity(citizen.citizenId()).orElseThrow());
        assertTrue(restored.bindings().observations(citizen.citizenId()).stream().filter(value -> value.entityId().equals(duplicate)).allMatch(value -> value.retired() && value.quarantined()));
        restored.bindings().unload(duplicate);
        restored.bindings().observe(citizen.citizenId(), duplicate, 0);
        assertEquals(citizen.entityId(), restored.bindings().activeEntity(citizen.citizenId()).orElseThrow());
        UUID orphan = UUID.randomUUID(); restored.bindings().observe(orphan, UUID.randomUUID(), 1);
        assertTrue(restored.registry().findCitizen(orphan).isEmpty());
        assertEquals(1, restored.registry().citizens().size());
    }

    @Test
    void crashBlockSurvivesCleanStopAndAcceptRequiresFreshLoadedNonconflictingInspection() {
        ServerRuntime runtime = runtime(); ColonyRuntime colony = colony(runtime, 0); CitizenRecord citizen = citizen(runtime, colony);
        UUID checkpoint = UUID.randomUUID(); runtime.registry().markRecoveryBlocked(checkpoint);
        RegistrySnapshot blocked = runtime.registry().snapshot(); runtime.beginStopping(); runtime.stop();
        ServerRuntime restored = runtime(); restored.registry().restore(blocked);
        assertTrue(restored.registry().colony(colony.colonyId()).recoveryBlocked());
        ColonyCommands.RecoveryInspection unknown = restored.commands().inspect(context(owner, true), colony.colonyId());
        assertFalse(unknown.ready());
        assertThrows(IllegalStateException.class, () -> restored.commands().acceptWorld(context(owner, true), colony.colonyId(), checkpoint, unknown));
        restored.bindings().observe(citizen.citizenId(), citizen.entityId(), 1);
        ColonyCommands.RecoveryInspection ready = restored.commands().inspect(context(owner, true), colony.colonyId());
        assertTrue(ready.ready());
        assertThrows(IllegalArgumentException.class, () -> restored.commands().acceptWorld(context(owner, true), colony.colonyId(), UUID.randomUUID(), ready));
        restored.bindings().unload(citizen.entityId());
        assertThrows(IllegalStateException.class, () -> restored.commands().acceptWorld(context(owner, true), colony.colonyId(), checkpoint, ready));
        assertTrue(restored.registry().colony(colony.colonyId()).recoveryBlocked());
        restored.bindings().observe(citizen.citizenId(), citizen.entityId(), 1);
        ColonyCommands.RecoveryInspection current = restored.commands().inspect(context(owner, true), colony.colonyId());
        restored.commands().acceptWorld(context(owner, true), colony.colonyId(), checkpoint, current);
        assertFalse(restored.registry().colony(colony.colonyId()).recoveryBlocked());
        assertNull(restored.registry().colony(colony.colonyId()).recoveryCheckpointId());
        assertEquals(CitizenRecord.Readiness.READY, restored.registry().citizen(citizen.citizenId()).readiness());
    }

    @Test void deathReleasesAssignedPhysicalWorkWithoutReclassifyingItsStage() {
        ServerRuntime runtime=runtime(); ColonyRuntime colony=colony(runtime,0); CitizenRecord citizen=citizen(runtime,colony);
        runtime.commands().updateCitizenReadiness(citizen.citizenId(),CitizenRecord.Readiness.READY);
        var work=runtime.commands().createMoveWork(context(owner,false),UUID.randomUUID(),colony.colonyId(),new WorldPosition("minecraft:overworld",4,64,0));
        runtime.workBoard().transition(work.id(),io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.READY,io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.NONE,"move");
        assertTrue(runtime.workBoard().assign(work.id(),citizen.citizenId()));
        runtime.commands().markDeath(citizen.citizenId());
        assertNull(work.assignee()); assertNull(runtime.registry().citizen(citizen.citizenId()).assignedWorkId());
        assertEquals("move",work.stage()); assertEquals(io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.WAITING,work.state());
        assertEquals(io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.RECONCILING,work.waitingReason());
        assertTrue(runtime.bindings().activeEntity(citizen.citizenId()).isEmpty());
    }

    @Test
    void persistedTombstoneRejectsLateIncarnationAndRestoreIsAllOrNothing() {
        ServerRuntime runtime = runtime(); ColonyRuntime colony = colony(runtime, 0); CitizenRecord citizen = citizen(runtime, colony);
        runtime.commands().markDeath(citizen.citizenId());
        ServerRuntime restored = runtime(); restored.registry().restore(runtime.registry().snapshot());
        restored.bindings().observe(citizen.citizenId(), citizen.entityId(), 1);
        assertEquals(CitizenRecord.Lifecycle.DEAD, restored.registry().citizen(citizen.citizenId()).lifecycle());
        assertEquals(CitizenRecord.Admission.INACTIVE, restored.registry().citizen(citizen.citizenId()).admission());
        assertTrue(restored.bindings().activeEntity(citizen.citizenId()).isEmpty());
        assertThrows(IllegalStateException.class, () -> restored.commands().bind(context(owner, true), citizen.citizenId(), citizen.entityId()));
        RegistrySnapshot before = restored.registry().snapshot();
        RegistrySnapshot invalid = new RegistrySnapshot(before.colonies(),List.of(citizen, citizen),before.buildings(),before.tombstones(),before.observations(),before.works(),before.targetClaims(),java.util.List.of(),java.util.List.of(),java.util.List.of(),io.github.kpuctajluk.colonyloom.core.storage.StorageSnapshot.empty(),io.github.kpuctajluk.colonyloom.core.supply.SupplySnapshot.empty());
        assertThrows(IllegalArgumentException.class, () -> restored.registry().restore(invalid));
        assertEquals(before, restored.registry().snapshot());
    }

    @Test void begunBatchRejectsProducerCutoversUntilPhysicalCommit() {
        var runtime=runtime();
        runtime.configureCommands(() -> {},List.of(new ProfessionDefinition("colonyloom:builder",1,Set.of(),Set.of()),
                new ProfessionDefinition("colonyloom:carpenter",1,Set.of("colonyloom:production"),Set.of("minecraft:crafting_table"))));
        var colony=colony(runtime,0);var citizen=citizen(runtime,colony);var registry=runtime.registry();
        var barrel=new WorldPosition("minecraft:overworld",4,64,4);var table=new WorldPosition("minecraft:overworld",5,64,4);
        var id=new io.github.kpuctajluk.colonyloom.core.storage.StorageId(barrel.dimension(),UUID.randomUUID(),0);
        var input=new io.github.kpuctajluk.colonyloom.core.storage.StockRegion(id,0);var output=new io.github.kpuctajluk.colonyloom.core.storage.StockRegion(id,1);
        var registration=registry.storage().register(colony.colonyId(),barrel,"workshop",List.of(id),List.of(input,output),List.of(barrel));
        var workshop=registry.storage().registerWorkshop(colony.colonyId(),table,registration.id());
        var alternative=registry.storage().registerWorkshop(colony.colonyId(),new WorldPosition("minecraft:overworld",6,64,4),registration.id());
        runtime.commands().assignProfession(context(owner,false),citizen.citizenId(),"colonyloom:carpenter");
        runtime.commands().assignWorkplace(context(owner,false),citizen.citizenId(),workshop.id());
        runtime.commands().updateCitizenReadiness(citizen.citizenId(),CitizenRecord.Readiness.READY);
        var logs=new io.github.kpuctajluk.colonyloom.core.storage.ItemDescriptor("minecraft:oak_log",new byte[0]);
        var planks=new io.github.kpuctajluk.colonyloom.core.storage.ItemDescriptor("minecraft:oak_planks",new byte[0]);
        var matcher=new io.github.kpuctajluk.colonyloom.core.supply.ItemMatcher(planks.itemId(),planks);
        var demand=registry.supply().request(UUID.randomUUID(),colony.colonyId(),owner,matcher,4,
                io.github.kpuctajluk.colonyloom.core.supply.Demand.GoalKind.CONSUMPTION,barrel,
                io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.NORMAL,0,0);
        var recipe=io.github.kpuctajluk.colonyloom.core.supply.RecipeDefinition.create("colonyloom:oak_planks",1,"colonyloom:carpenter","minecraft:crafting_table",
                List.of(new io.github.kpuctajluk.colonyloom.core.supply.RecipeDefinition.Ingredient(new io.github.kpuctajluk.colonyloom.core.supply.ItemMatcher(logs.itemId(),logs),1)),planks,4,20);
        var production=registry.supply().promiseProduction(demand.id(),recipe,1);var child=registry.supply().ingredientDemand(production,0,1,0);
        registry.storage().index().observe(input,logs,1,0);registry.storage().index().observe(output,null,0,0);
        registry.supply().allocateStock(child.id(),input,logs,1,0);
        var work=registry.workBoard().createProduction(UUID.randomUUID(),colony.colonyId(),table,"colonyloom:carpenter",0,production.lane());
        registry.supply().assignProductionWork(production.id(),work.id());
        registry.workBoard().transition(work.id(),io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.READY,io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.NONE,"production");
        assertTrue(registry.workBoard().assign(work.id(),citizen.citizenId()));
        registry.workBoard().transition(work.id(),io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.RUNNING,io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.NONE,"production");
        registry.supply().startProduction(production.id(),citizen.citizenId(),work.id());
        var before=registry.snapshot();
        assertThrows(IllegalStateException.class,() -> runtime.commands().assignProfession(context(owner,false),citizen.citizenId(),"colonyloom:builder"));
        assertThrows(IllegalStateException.class,() -> runtime.commands().assignWorkplace(context(owner,false),citizen.citizenId(),alternative.id()));
        assertThrows(IllegalStateException.class,() -> runtime.commands().removeCitizen(context(owner,false),citizen.citizenId()));
        assertEquals(before,registry.snapshot());
        registry.supply().advanceProduction(production.id(),20);
        try(var prepared=registry.supply().prepareProduction(production.id(),List.of(new io.github.kpuctajluk.colonyloom.core.supply.SupplyRegistry.OutputPortion(output,4)),0)) {
            registry.storage().index().observe(output,planks,4,0);prepared.commit();
        }
        registry.storage().index().observe(input,null,0,0);
        runtime.commands().assignProfession(context(owner,false),citizen.citizenId(),"colonyloom:builder");
        assertEquals("colonyloom:builder",registry.citizen(citizen.citizenId()).professionId());
        assertEquals(4,demand.snapshot().allocated());assertEquals(1,child.snapshot().fulfilled());
    }

    @Test
    void sessionGateFailureAndStoppingRejectCommandsBeforeLogicalOrPhysicalChanges() {
        ServerRuntime runtime = runtime(); ColonyRuntime colony = colony(runtime, 0); RegistrySnapshot before = runtime.registry().snapshot();
        runtime.configureCommands(() -> { throw new IllegalStateException("Marker flush failed"); }, List.of());
        assertThrows(IllegalStateException.class, () -> citizen(runtime, colony));
        assertTrue(spawned.isEmpty()); assertEquals(before, runtime.registry().snapshot());
        runtime.configureCommands(() -> {}, List.of()); runtime.beginStopping();
        assertThrows(IllegalStateException.class, () -> citizen(runtime, colony));
        assertTrue(spawned.isEmpty()); assertEquals(before, runtime.registry().snapshot());
    }
}
