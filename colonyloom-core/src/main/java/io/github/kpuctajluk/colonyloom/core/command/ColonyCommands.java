package io.github.kpuctajluk.colonyloom.core.command;

import io.github.kpuctajluk.colonyloom.core.citizen.BindingRegistry;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.citizen.ProfessionDefinition;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.MemberRank;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.persistence.Tombstone;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import java.util.function.Consumer;
/** Shared command owner; platform adapters supply physical observations, never business rules. */
public final class ColonyCommands {
    public interface PhysicalChecks {
        void validateTerritory(Territory territory);
        void validateCitizenPosition(ColonyRuntime colony, WorldPosition position);
        void validateRecovery(ColonyRuntime colony, List<CitizenRecord> citizens, List<BindingRegistry.Observation> observations);
    }
    public record CommandContext(UUID actorId, boolean operator, PhysicalChecks checks) {
        public CommandContext { Objects.requireNonNull(checks, "checks"); }
    }
    public record RecoveryInspection(UUID colonyId, UUID checkpointId, long colonyRevision,
            long bindingRevision, List<CitizenRecord> citizens, List<BindingRegistry.Observation> observations,
            List<io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Snapshot> works, boolean ready) {
        public RecoveryInspection { citizens = List.copyOf(citizens); observations = List.copyOf(observations); works = List.copyOf(works); }
    }
    private final ColonyRegistry registry;
    private Map<String, ProfessionDefinition> professions = Map.of();

    public ColonyCommands(ColonyRegistry registry) { this.registry = Objects.requireNonNull(registry, "registry"); }
    public void setProfessions(Collection<ProfessionDefinition> definitions) {
        registry.requireOwner();
        Map<String, ProfessionDefinition> checked = new LinkedHashMap<>();
        for (ProfessionDefinition definition : definitions) if (checked.putIfAbsent(definition.id(), definition) != null) throw new IllegalArgumentException("Duplicate profession ID");
        professions = Map.copyOf(checked);
    }
    public Collection<ProfessionDefinition> professions() { registry.requireOwner(); return professions.values(); }

    public ColonyRuntime createColony(CommandContext context, UUID colonyId, String name, Territory territory) {
        registry.requireOwner();
        if (context.actorId() == null) throw new SecurityException("Colony founding requires a player");
        ColonyRuntime created = new ColonyRuntime(colonyId, name, territory, context.actorId(), Map.of(), 1, 1, false, null, false);
        if (registry.usedId(colonyId)) throw new IllegalStateException("Colony identity unavailable");
        if (registry.colonies().stream().anyMatch(value -> territory.overlaps(value.territory()))) throw new IllegalArgumentException("Colony territories overlap");
        context.checks().validateTerritory(territory);
        registry.beforeMutation();
        registry.addColony(created);
        return created;
    }
    public ColonyRuntime setMember(CommandContext context, UUID colonyId, UUID playerId, MemberRank rank) {
        ColonyRuntime colony = requireRank(context, colonyId, MemberRank.OWNER);
        Objects.requireNonNull(playerId, "playerId");
        if (playerId.equals(colony.ownerId()) || rank == MemberRank.OWNER) throw new IllegalArgumentException("Use owner transfer for ownership");
        if (Objects.equals(colony.members().get(playerId), rank)) return colony;
        Map<UUID, MemberRank> members = new HashMap<>(colony.members());
        if (rank == null) members.remove(playerId); else members.put(playerId, rank);
        ColonyRuntime changed = revised(colony, colony.ownerId(), members, true, colony.recoveryBlocked(), colony.recoveryCheckpointId());
        registry.beforeMutation(); registry.updateColony(changed);
        return changed;
    }
    public ColonyRuntime setOwner(CommandContext context, UUID colonyId, UUID playerId) {
        ColonyRuntime colony = requireRank(context, colonyId, MemberRank.OWNER);
        Objects.requireNonNull(playerId, "playerId");
        if (playerId.equals(colony.ownerId())) return colony;
        Map<UUID, MemberRank> members = new HashMap<>(colony.members());
        members.remove(playerId);
        members.put(colony.ownerId(), MemberRank.MANAGER);
        ColonyRuntime changed = revised(colony, playerId, members, true, colony.recoveryBlocked(), colony.recoveryCheckpointId());
        registry.beforeMutation(); registry.updateColony(changed);
        return changed;
    }

    /** The platform performs the prevalidated spawn after the session gate, before committing identity. */
    public CitizenRecord createCitizen(CommandContext context, UUID colonyId, UUID citizenId, UUID entityId, WorldPosition position, Consumer<CitizenRecord> spawn) {
        Objects.requireNonNull(spawn, "spawn");
        requireOperator(context);
        ColonyRuntime colony = registry.colony(colonyId);
        requireAvailable(colony);
        Objects.requireNonNull(citizenId, "citizenId"); Objects.requireNonNull(entityId, "entityId");
        if (!colony.territory().contains(position)) throw new IllegalArgumentException("Citizen position outside colony");
        if (registry.usedId(citizenId) || registry.entityIdUsed(entityId)) throw new IllegalStateException("Citizen identity unavailable");
        if (registry.bindings().observations().size() >= BindingRegistry.MAX_OBSERVATIONS) throw new IllegalStateException("Binding observation capacity unavailable");
        CitizenRecord created = new CitizenRecord(citizenId, colonyId, entityId, 1, null, null, null, null, Map.of(), Map.of("food", 20), CitizenRecord.Lifecycle.ALIVE, CitizenRecord.Admission.ACTIVE, CitizenRecord.Readiness.UNKNOWN, 0, Map.of("food", 1200L), position, 1);
        ColonyRuntime changed = revised(colony, colony.ownerId(), colony.members(), false, false, null);
        context.checks().validateCitizenPosition(colony, position);
        registry.beforeMutation();
        registry.addCitizen(created, spawn); registry.updateColony(changed);
        return created;
    }
    public CitizenRecord assignProfession(CommandContext context, UUID citizenId, String professionId) {
        CitizenRecord citizen = registry.citizen(citizenId);
        ColonyRuntime colony = requireRank(context, citizen.colonyId(), MemberRank.MANAGER);
        requireAvailable(colony);
        if (citizen.lifecycle() != CitizenRecord.Lifecycle.ALIVE) throw new IllegalStateException("Citizen is not alive");
        if (!professions.containsKey(professionId)) throw new IllegalArgumentException("Unknown profession ID");
        if (professionId.equals(citizen.professionId())) return citizen;
        CitizenRecord changed = citizen.withProfession(professionId);
        ColonyRuntime changedColony = revised(colony, colony.ownerId(), colony.members(), false, false, null);
        registry.beforeMutation(); registry.updateCitizen(changed); registry.updateColony(changedColony);
        return changed;
    }
    public ColonyRuntime status(CommandContext context, UUID colonyId) { return requireRank(context, colonyId, MemberRank.VIEWER); }

    public io.github.kpuctajluk.colonyloom.core.work.WorkOrder createTimerWork(CommandContext context, UUID workId, UUID colonyId, WorldPosition target, String professionId, int priority, long activeTicks) {
        ColonyRuntime colony = requireRank(context, colonyId, MemberRank.MANAGER);
        requireAvailable(colony);
        if (professionId != null && !professions.containsKey(professionId)) throw new IllegalArgumentException("Unknown profession ID");
        return registry.workBoard().createTimer(workId, colonyId, target, professionId, priority,
                io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.NORMAL, activeTicks);
    }
    public io.github.kpuctajluk.colonyloom.core.work.WorkOrder cancelWork(CommandContext context, UUID workId) {
        var work = registry.workBoard().work(workId);
        requireRank(context, work.colonyId(), MemberRank.MANAGER);
        // Cancellation is service work: recovery/unavailable colonies must still release obligations.
        registry.workBoard().cancel(workId);
        return work;
    }
    public io.github.kpuctajluk.colonyloom.core.work.WorkOrder prioritizeWork(CommandContext context, UUID workId, int priority) {
        var work = registry.workBoard().work(workId);
        requireAvailable(requireRank(context, work.colonyId(), MemberRank.MANAGER));
        registry.workBoard().priority(workId, priority);
        return work;
    }

    public RecoveryInspection inspect(CommandContext context, UUID colonyId) {
        requireOperator(context);
        ColonyRuntime colony = registry.colony(colonyId);
        List<CitizenRecord> citizens = registry.citizens(colonyId);
        List<BindingRegistry.Observation> observations = colonyObservations(citizens);
        boolean ready = !colony.contentBlocked() && citizens.stream().allMatch(value -> registry.bindings().recoveryReady(value.citizenId()));
        long colonyRevision = colony.revision(); long bindingRevision = registry.bindings().revision();
        return new RecoveryInspection(colonyId, colony.recoveryCheckpointId(), colonyRevision, bindingRevision, citizens, observations, colonyWorks(colonyId), ready);
    }
    public ColonyRuntime acceptWorld(CommandContext context, UUID colonyId, UUID checkpointId, RecoveryInspection inspection) {
        requireOperator(context);
        ColonyRuntime colony = registry.colony(colonyId);
        if (!colony.recoveryBlocked() || !Objects.equals(checkpointId, colony.recoveryCheckpointId()) || inspection == null || !colonyId.equals(inspection.colonyId()) || !Objects.equals(checkpointId, inspection.checkpointId())) throw new IllegalArgumentException("Recovery checkpoint mismatch");
        List<CitizenRecord> citizens = registry.citizens(colonyId);
        List<BindingRegistry.Observation> observations = colonyObservations(citizens);
        if (!inspection.ready() || colony.contentBlocked() || inspection.colonyRevision() != colony.revision() || inspection.bindingRevision() != registry.bindings().revision() || !inspection.citizens().equals(citizens) || !inspection.observations().equals(observations) || citizens.stream().anyMatch(value -> !registry.bindings().recoveryReady(value.citizenId()))) throw new IllegalStateException("Recovery incomplete or stale; inspect again");
        if (!sameWorkRevisions(inspection.works(), colonyWorks(colonyId))) throw new IllegalStateException("Work state changed; inspect again");
        context.checks().validateRecovery(colony, citizens, observations);
        if (inspection.colonyRevision() != registry.colony(colonyId).revision() || inspection.bindingRevision() != registry.bindings().revision() || !citizens.equals(registry.citizens(colonyId))) throw new IllegalStateException("Recovery changed during verification");
        if (!sameWorkRevisions(inspection.works(), colonyWorks(colonyId))) throw new IllegalStateException("Work state changed during verification");
        ColonyRuntime changed = revised(colony, colony.ownerId(), colony.members(), false, false, null);
        registry.beforeMutation();
        for (var work : registry.workBoard().works()) if (work.colonyId().equals(colonyId) && !work.terminal()) registry.workBoard().cancel(work.id());
        for (CitizenRecord citizen : registry.citizens(colonyId)) registry.updateCitizen(citizen.reconciled());
        registry.updateColony(changed);
        return changed;
    }
    public CitizenRecord bind(CommandContext context, UUID citizenId, UUID chosenEntityId) {
        requireOperator(context);
        CitizenRecord citizen = registry.citizen(citizenId);
        ColonyRuntime colony = registry.colony(citizen.colonyId());
        if (colony.contentBlocked()) throw new IllegalStateException("Content unavailable");
        registry.bindings().validateBind(citizenId, chosenEntityId);
        long epoch = Math.incrementExact(Math.max(citizen.bindingEpoch(), registry.bindings().observations(citizenId).stream().mapToLong(BindingRegistry.Observation::bindingEpoch).max().orElse(citizen.bindingEpoch())));
        CitizenRecord changed = citizen.withBinding(chosenEntityId, epoch);
        ColonyRuntime changedColony = revised(colony, colony.ownerId(), colony.members(), false, colony.recoveryBlocked(), colony.recoveryCheckpointId());
        context.checks().validateRecovery(colony, List.of(citizen), registry.bindings().observations(citizenId));
        if (!citizen.equals(registry.citizen(citizenId))) throw new IllegalStateException("Identity changed during physical verification");
        registry.bindings().validateBind(citizenId, chosenEntityId);
        registry.beforeMutation();
        if (citizen.assignedWorkId() != null) registry.workBoard().transition(citizen.assignedWorkId(), io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.WAITING, io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.RECONCILING, "timer");
        changed = registry.citizen(citizenId).withBinding(chosenEntityId, epoch);
        registry.updateCitizen(changed); registry.bindings().applyBind(citizenId, chosenEntityId, epoch); registry.updateColony(changedColony);
        return changed;
    }

    /** Physical death is not inferred from unload, absence, or a recovery scan. */
    public CitizenRecord markDeath(UUID citizenId) { return finishLifecycle(citizenId, CitizenRecord.Lifecycle.DEAD); }
    public CitizenRecord removeCitizen(CommandContext context, UUID citizenId) {
        CitizenRecord citizen = registry.citizen(citizenId);
        requireRank(context, citizen.colonyId(), MemberRank.OWNER);
        return finishLifecycle(citizenId, CitizenRecord.Lifecycle.REMOVED);
    }
    private CitizenRecord finishLifecycle(UUID citizenId, CitizenRecord.Lifecycle lifecycle) {
        CitizenRecord citizen = registry.citizen(citizenId);
        if (citizen.lifecycle() == lifecycle) return citizen;
        if (citizen.lifecycle() != CitizenRecord.Lifecycle.ALIVE && lifecycle != CitizenRecord.Lifecycle.REMOVED) throw new IllegalStateException("Invalid lifecycle transition");
        CitizenRecord changed = citizen.withLifecycle(lifecycle);
        ColonyRuntime colony = registry.colony(citizen.colonyId());
        ColonyRuntime changedColony = revised(colony, colony.ownerId(), colony.members(), false, colony.recoveryBlocked(), colony.recoveryCheckpointId());
        Tombstone tombstone = new Tombstone(citizenId, citizen.colonyId(), citizen.bindingEpoch(), lifecycle);
        Math.incrementExact(registry.bindings().revision());
        registry.beforeMutation();
        if (citizen.assignedWorkId() != null) registry.workBoard().transition(citizen.assignedWorkId(), io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.WAITING, io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.RECONCILING, "timer");
        changed = registry.citizen(citizenId).withLifecycle(lifecycle);
        registry.updateCitizen(changed); registry.addTombstone(tombstone); registry.bindings().quarantine(citizenId); registry.updateColony(changedColony);
        return changed;
    }
    public CitizenRecord updateCitizenReadiness(UUID citizenId, CitizenRecord.Readiness readiness) {
        CitizenRecord citizen = registry.citizen(citizenId);
        Objects.requireNonNull(readiness, "readiness");
        if (citizen.readiness() == readiness) return citizen;
        if (readiness == CitizenRecord.Readiness.READY && registry.bindings().activeEntity(citizenId).isEmpty()) throw new IllegalStateException("Citizen has no active observed incarnation");
        CitizenRecord changed = new CitizenRecord(citizen.citizenId(), citizen.colonyId(), citizen.entityId(), citizen.bindingEpoch(), citizen.homeId(), citizen.workplaceId(), citizen.assignedWorkId(), citizen.professionId(), citizen.skills(), citizen.needs(), citizen.lifecycle(), citizen.admission(), readiness, citizen.activeTimeTicks(), citizen.remainingTimers(), citizen.lastKnownPosition(), Math.incrementExact(citizen.revision()));
        registry.beforeMutation(); registry.updateCitizen(changed);
        return changed;
    }
    private List<BindingRegistry.Observation> colonyObservations(List<CitizenRecord> citizens) { return citizens.stream().flatMap(value -> registry.bindings().observations(value.citizenId()).stream()).toList(); }
    private List<io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Snapshot> colonyWorks(UUID colonyId) {
        return registry.workBoard().snapshots().stream().filter(work -> work.colonyId().equals(colonyId)).toList();
    }
    private static boolean sameWorkRevisions(List<io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Snapshot> expected, List<io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Snapshot> actual) {
        if (expected.size() != actual.size()) return false;
        for (int i = 0; i < expected.size(); i++) if (!expected.get(i).id().equals(actual.get(i).id()) || expected.get(i).revision() != actual.get(i).revision()) return false;
        return true;
    }
    private ColonyRuntime requireRank(CommandContext context, UUID colonyId, MemberRank required) {
        ColonyRuntime colony = registry.colony(colonyId);
        MemberRank actual = colony.rank(context.actorId());
        if (actual == null || actual.ordinal() > required.ordinal()) throw new SecurityException("Colony authority denied");
        return colony;
    }
    private void requireOperator(CommandContext context) { registry.requireOwner(); if (!context.operator()) throw new SecurityException("Operator permission required"); }
    private static void requireAvailable(ColonyRuntime colony) { if (!colony.available()) throw new IllegalStateException(colony.contentBlocked() ? "CONTENT_UNAVAILABLE" : "RECOVERY_AMBIGUOUS"); }
    private static ColonyRuntime revised(ColonyRuntime colony, UUID owner, Map<UUID, MemberRank> members, boolean authority, boolean recoveryBlocked, UUID checkpointId) {
        return new ColonyRuntime(colony.colonyId(), colony.name(), colony.territory(), owner, members, Math.incrementExact(colony.revision()), authority ? Math.incrementExact(colony.authorityRevision()) : colony.authorityRevision(), recoveryBlocked, checkpointId, colony.contentBlocked());
    }
}
