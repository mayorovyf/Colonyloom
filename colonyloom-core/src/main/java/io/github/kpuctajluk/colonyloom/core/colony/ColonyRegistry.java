package io.github.kpuctajluk.colonyloom.core.colony;

import io.github.kpuctajluk.colonyloom.core.building.BuildingRecord;
import io.github.kpuctajluk.colonyloom.core.citizen.BindingRegistry;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.persistence.RegistrySnapshot;
import io.github.kpuctajluk.colonyloom.core.persistence.Tombstone;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.work.WorkBoard;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Server-thread-owned persistent identity registry. Commands own ordinary mutations. */
public final class ColonyRegistry {
    private AdmissionLedger admission;
    private final WorkBoard workBoard;
    private final io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets budgets;
    private final io.github.kpuctajluk.colonyloom.core.spatial.TargetClaimRegistry targetClaims;
    private Map<UUID, AdmissionLedger.Lease> colonyLeases = new LinkedHashMap<>();
    private Map<UUID, AdmissionLedger.Lease> citizenLeases = new LinkedHashMap<>();
    private Map<UUID, AdmissionLedger.Lease> tombstoneLeases = new LinkedHashMap<>();
    private final Runnable ownerCheck;
    private Runnable beforeMutation = () -> {};
    private Runnable afterRestore = () -> {};
    private final LinkedHashMap<UUID, ColonyRuntime> colonies = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, CitizenRecord> citizens = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, BuildingRecord> buildings = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Tombstone> tombstones = new LinkedHashMap<>();
    private final BindingRegistry bindings;

    public ColonyRegistry(Runnable ownerCheck) {
        this.ownerCheck = Objects.requireNonNull(ownerCheck, "ownerCheck");
        admission = new AdmissionLedger(SimulationLimits.development(), ownerCheck);
        workBoard = new WorkBoard(this, admission);
        budgets = new io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets(SimulationLimits.development());
        targetClaims = new io.github.kpuctajluk.colonyloom.core.spatial.TargetClaimRegistry(this, budgets);
        bindings = new BindingRegistry(this);
    }
    public void requireOwner() { ownerCheck.run(); }
    public void setBeforeMutation(Runnable hook) { requireOwner(); beforeMutation = Objects.requireNonNull(hook, "hook"); }
    public void beforeMutation() { requireOwner(); beforeMutation.run(); }
    public void setAfterRestore(Runnable hook) { requireOwner(); afterRestore = Objects.requireNonNull(hook); }
    public BindingRegistry bindings() { requireOwner(); return bindings; }
    public AdmissionLedger admission() { requireOwner(); return admission; }
    public WorkBoard workBoard() { requireOwner(); return workBoard; }
    public io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets budgets() { requireOwner(); return budgets; }
    public io.github.kpuctajluk.colonyloom.core.spatial.TargetClaimRegistry targetClaims() { requireOwner(); return targetClaims; }
    public Collection<CitizenRecord> citizensView() { requireOwner(); return Collections.unmodifiableCollection(citizens.values()); }
    public List<ColonyRuntime> colonies() { requireOwner(); return List.copyOf(colonies.values()); }
    public List<CitizenRecord> citizens() { requireOwner(); return List.copyOf(citizens.values()); }
    public List<BuildingRecord> buildings() { requireOwner(); return List.copyOf(buildings.values()); }
    public List<Tombstone> tombstones() { requireOwner(); return List.copyOf(tombstones.values()); }
    public ColonyRuntime colony(UUID id) { requireOwner(); return required(colonies, id, "colony"); }
    public CitizenRecord citizen(UUID id) { requireOwner(); return required(citizens, id, "citizen"); }
    public Optional<CitizenRecord> findCitizen(UUID id) { requireOwner(); return Optional.ofNullable(citizens.get(id)); }
    public List<CitizenRecord> citizens(UUID colonyId) { colony(colonyId); return citizens.values().stream().filter(value -> colonyId.equals(value.colonyId())).toList(); }
    public boolean usedId(UUID id) { requireOwner(); return colonies.containsKey(id) || citizens.containsKey(id) || buildings.containsKey(id) || tombstones.containsKey(id) || workBoard.works().stream().anyMatch(work -> work.id().equals(id)); }
    public boolean entityIdUsed(UUID id) { requireOwner(); return citizens.values().stream().anyMatch(value -> value.entityId().equals(id)) || bindings.observations().stream().anyMatch(value -> value.entityId().equals(id)); }

    public void addColony(ColonyRuntime colony) {
        requireOwner();
        if (usedId(colony.colonyId())) throw new IllegalArgumentException("Colony identity unavailable");
        if (colonies.values().stream().anyMatch(value -> value.territory().overlaps(colony.territory()))) throw new IllegalArgumentException("Colony territories overlap");
        AdmissionLedger.Lease lease = admission.reserve(colony.colonyId(), AdmissionLedger.Lane.NORMAL, Map.of(SimulationLimits.Resource.COLONIES, 1));
        colonyLeases.put(colony.colonyId(), lease);
        colonies.put(colony.colonyId(), colony);
    }
    public void updateColony(ColonyRuntime colony) { colony(colony.colonyId()); colonies.put(colony.colonyId(), colony); workBoard.markColonyChanged(colony.colonyId()); }
    public void addCitizen(CitizenRecord citizen, java.util.function.Consumer<CitizenRecord> spawn) {
        requireOwner();
        colony(citizen.colonyId());
        if (usedId(citizen.citizenId()) || entityIdUsed(citizen.entityId())) throw new IllegalArgumentException("Citizen identity unavailable");
        validateCitizen(citizen, colonies, buildings);
        AdmissionLedger.Lease lease = admission.reserve(citizen.colonyId(), AdmissionLedger.Lane.NORMAL,
                Map.of(SimulationLimits.Resource.CITIZENS, 1, SimulationLimits.Resource.TOMBSTONES, 1, SimulationLimits.Resource.EVIDENCE, 1));
        citizenLeases.put(citizen.citizenId(), lease);
        try { spawn.accept(citizen); }
        catch (RuntimeException | Error failure) { citizenLeases.remove(citizen.citizenId()); lease.close(); throw failure; }
        citizens.put(citizen.citizenId(), citizen);
        workBoard.markCitizenChanged(citizen.citizenId());
    }
    public void updateCitizen(CitizenRecord citizen) {
        CitizenRecord old = citizen(citizen.citizenId());
        if (!old.colonyId().equals(citizen.colonyId())) throw new IllegalArgumentException("Citizen cannot change colony");
        validateCitizen(citizen, colonies, buildings);
        if (old.assignedWorkId() != null && !Objects.equals(old.assignedWorkId(), citizen.assignedWorkId())) workBoard.citizenDetached(old.citizenId());
        citizens.put(citizen.citizenId(), citizen);
        workBoard.markCitizenChanged(citizen.citizenId());
    }
    public void addTombstone(Tombstone tombstone) {
        requireOwner();
        if (!tombstones.containsKey(tombstone.citizenId()) && !citizenLeases.containsKey(tombstone.citizenId())) {
            tombstoneLeases.put(tombstone.citizenId(), admission.reserve(tombstone.colonyId(), AdmissionLedger.Lane.NORMAL, Map.of(SimulationLimits.Resource.TOMBSTONES, 1)));
        }
        tombstones.put(tombstone.citizenId(), tombstone);
    }
    public RegistrySnapshot snapshot() {
        return new RegistrySnapshot(colonies(), citizens(), buildings(), tombstones(), bindings.observations(), workBoard.snapshots(), targetClaims.snapshots());
    }

    /** Validates the whole DTO before replacing any authoritative state. */
    public void restore(RegistrySnapshot snapshot) {
        requireOwner();
        SimulationLimits limits = admission.limits();
        if (snapshot.colonies().size() > 3 || snapshot.citizens().size() > 300 || snapshot.buildings().size() > 512 || snapshot.works().size() > 8192 || snapshot.targetClaims().size() > 512 || snapshot.tombstones().size() > 65536 || snapshot.observations().size() > BindingRegistry.MAX_OBSERVATIONS) throw new IllegalArgumentException("Snapshot exceeds bounded persistence envelope");
        LinkedHashMap<UUID, ColonyRuntime> newColonies = new LinkedHashMap<>();
        LinkedHashMap<UUID, CitizenRecord> newCitizens = new LinkedHashMap<>();
        LinkedHashMap<UUID, BuildingRecord> newBuildings = new LinkedHashMap<>();
        LinkedHashMap<UUID, Tombstone> newTombstones = new LinkedHashMap<>();
        Set<UUID> objectIds = new HashSet<>();
        for (ColonyRuntime value : snapshot.colonies()) {
            if (!objectIds.add(value.colonyId()) || newColonies.values().stream().anyMatch(other -> value.territory().overlaps(other.territory()))) throw new IllegalArgumentException("Duplicate colony identity or territory");
            newColonies.put(value.colonyId(), value);
        }
        for (BuildingRecord value : snapshot.buildings()) {
            ColonyRuntime colony = required(newColonies, value.colonyId(), "building colony");
            if (!objectIds.add(value.buildingId()) || !colony.territory().contains(value.position())) throw new IllegalArgumentException("Invalid building identity/location");
            newBuildings.put(value.buildingId(), value);
        }
        Set<UUID> entityIds = new HashSet<>();
        for (CitizenRecord value : snapshot.citizens()) {
            if (!objectIds.add(value.citizenId()) || !entityIds.add(value.entityId())) throw new IllegalArgumentException("Duplicate citizen/entity identity");
            validateCitizen(value, newColonies, newBuildings);
            // Readiness is derived from the current world's observed incarnation, never a saved promise.
            CitizenRecord.Readiness readiness = required(newColonies, value.colonyId(), "citizen colony").contentBlocked() ? CitizenRecord.Readiness.BLOCKED : CitizenRecord.Readiness.UNKNOWN;
            newCitizens.put(value.citizenId(), new CitizenRecord(value.citizenId(), value.colonyId(), value.entityId(), value.bindingEpoch(), value.homeId(), value.workplaceId(), value.assignedWorkId(), value.professionId(), value.skills(), value.needs(), value.lifecycle(), value.admission(), readiness, value.activeTimeTicks(), value.remainingTimers(), value.lastKnownPosition(), value.revision()));
        }
        for (Tombstone value : snapshot.tombstones()) {
            required(newColonies, value.colonyId(), "tombstone colony");
            CitizenRecord citizen = newCitizens.get(value.citizenId());
            if (newTombstones.putIfAbsent(value.citizenId(), value) != null || citizen == null && !objectIds.add(value.citizenId())) throw new IllegalArgumentException("Duplicate tombstone identity");
            if (citizen != null && (!citizen.colonyId().equals(value.colonyId()) || citizen.lifecycle() != value.lifecycle() || citizen.bindingEpoch() != value.bindingEpoch())) throw new IllegalArgumentException("Tombstone differs from citizen");
        }
        for (CitizenRecord value : newCitizens.values()) if (value.lifecycle() != CitizenRecord.Lifecycle.ALIVE && !newTombstones.containsKey(value.citizenId())) throw new IllegalArgumentException("Nonliving citizen missing tombstone");
        Set<UUID> observedIds = new HashSet<>();
        for (BindingRegistry.Observation value : snapshot.observations()) if (!observedIds.add(value.entityId())) throw new IllegalArgumentException("Duplicate observed entity UUID");
        Map<UUID, WorkOrder.Snapshot> newWorks = new LinkedHashMap<>();
        Set<UUID> assignedCitizens = new HashSet<>();
        for (WorkOrder.Snapshot work : snapshot.works()) {
            ColonyRuntime colony = required(newColonies, work.colonyId(), "work colony");
            if (!objectIds.add(work.id()) || !(WorkOrder.ACTIVE_WAIT.equals(work.typeId()) || WorkOrder.MOVE.equals(work.typeId())) || !colony.territory().contains(work.target())) throw new IllegalArgumentException("Invalid work identity/type/target");
            newWorks.put(work.id(), work);
            if (work.assignee() != null) {
                CitizenRecord citizen = required(newCitizens, work.assignee(), "work assignee");
                if (!assignedCitizens.add(citizen.citizenId()) || !citizen.colonyId().equals(work.colonyId()) || !work.id().equals(citizen.assignedWorkId()) || citizen.lifecycle() != CitizenRecord.Lifecycle.ALIVE) throw new IllegalArgumentException("Invalid work/citizen assignment");
            }
        }
        for (WorkOrder.Snapshot work : snapshot.works()) for (UUID dependency : work.dependencies()) {
            if (!required(newWorks, dependency, "work dependency").colonyId().equals(work.colonyId())) throw new IllegalArgumentException("Foreign work dependency");
        }
        for (CitizenRecord citizen : newCitizens.values()) if (citizen.assignedWorkId() != null && !citizen.citizenId().equals(required(newWorks, citizen.assignedWorkId(), "citizen assignment").assignee())) throw new IllegalArgumentException("Work/citizen assignment mismatch");
        Set<UUID> claimIds = new HashSet<>();
        for (var claim : snapshot.targetClaims()) {
            required(newColonies, claim.colonyId(), "target colony");
            if (!claimIds.add(claim.ownerId())) throw new IllegalArgumentException("Duplicate target owner");
            if (claim.buildingId() != null && !required(newBuildings, claim.buildingId(), "target building").colonyId().equals(claim.colonyId())) throw new IllegalArgumentException("Foreign target building");
        }
        // Restore already accepted state, then drain at the configured limits without eviction.
        int restorationRoots = Math.max(1, snapshot.works().size());
        SimulationLimits restorationLimits = limits
                .withResource(SimulationLimits.Resource.COLONIES, Math.max(limits.resource(SimulationLimits.Resource.COLONIES), snapshot.colonies().size()))
                .withResource(SimulationLimits.Resource.CITIZENS, Math.max(limits.resource(SimulationLimits.Resource.CITIZENS), snapshot.citizens().size()))
                .withResource(SimulationLimits.Resource.TOMBSTONES, Math.max(limits.resource(SimulationLimits.Resource.TOMBSTONES), Math.addExact(snapshot.citizens().size(), snapshot.tombstones().size())))
                .withResource(SimulationLimits.Resource.PHYSICAL_TARGETS, Math.max(limits.resource(SimulationLimits.Resource.PHYSICAL_TARGETS), Math.addExact(snapshot.buildings().size(), snapshot.targetClaims().size())))
                .withResource(SimulationLimits.Resource.SPATIAL_INDEX_LINKS, Math.max(limits.resource(SimulationLimits.Resource.SPATIAL_INDEX_LINKS), Math.multiplyExact(snapshot.targetClaims().size(), 128)))
                .withResource(SimulationLimits.Resource.WORKS, Math.max(limits.resource(SimulationLimits.Resource.WORKS), Math.multiplyExact(restorationRoots, 16)))
                .withResource(SimulationLimits.Resource.EVIDENCE, Math.max(limits.resource(SimulationLimits.Resource.EVIDENCE), Math.addExact(snapshot.citizens().size(), snapshot.observations().size())))
                .withResource(SimulationLimits.Resource.WAIT_REGISTRATIONS, Math.max(limits.resource(SimulationLimits.Resource.WAIT_REGISTRATIONS), Math.multiplyExact(restorationRoots, 144)));
        AdmissionLedger replacement = new AdmissionLedger(restorationLimits, ownerCheck);
        Map<UUID, AdmissionLedger.Lease> newColonyLeases = new LinkedHashMap<>();
        Map<UUID, AdmissionLedger.Lease> newCitizenLeases = new LinkedHashMap<>();
        Map<UUID, AdmissionLedger.Lease> newTombstoneLeases = new LinkedHashMap<>();
        for (ColonyRuntime value : newColonies.values()) newColonyLeases.put(value.colonyId(), replacement.reserve(value.colonyId(), AdmissionLedger.Lane.NORMAL, Map.of(SimulationLimits.Resource.COLONIES, 1)));
        for (CitizenRecord value : newCitizens.values()) newCitizenLeases.put(value.citizenId(), replacement.reserve(value.colonyId(), AdmissionLedger.Lane.NORMAL, Map.of(SimulationLimits.Resource.CITIZENS, 1, SimulationLimits.Resource.TOMBSTONES, 1, SimulationLimits.Resource.EVIDENCE, 1)));
        for (BuildingRecord value : newBuildings.values()) replacement.reserve(value.colonyId(), AdmissionLedger.Lane.NORMAL, Map.of(SimulationLimits.Resource.PHYSICAL_TARGETS, 1));
        for (Tombstone value : newTombstones.values()) if (!newCitizens.containsKey(value.citizenId())) newTombstoneLeases.put(value.citizenId(), replacement.reserve(value.colonyId(), AdmissionLedger.Lane.NORMAL, Map.of(SimulationLimits.Resource.TOMBSTONES, 1)));
        for (BindingRegistry.Observation value : snapshot.observations()) {
            CitizenRecord citizen = newCitizens.get(value.citizenId());
            if (citizen == null || !citizen.entityId().equals(value.entityId())) replacement.reserve(citizen == null ? new UUID(0, 0) : citizen.colonyId(), AdmissionLedger.Lane.NORMAL, Map.of(SimulationLimits.Resource.EVIDENCE, 1));
        }
        try (var preparedClaims = targetClaims.prepareRestore(snapshot.targetClaims(), replacement)) {
            workBoard.restoreValidated(snapshot.works(), replacement);
            preparedClaims.commit();
        }
        replacement.updateLimits(limits);
        replacement.inheritCounters(admission);
        admission = replacement;
        colonyLeases = newColonyLeases; citizenLeases = newCitizenLeases; tombstoneLeases = newTombstoneLeases;
        colonies.clear(); colonies.putAll(newColonies);
        citizens.clear(); citizens.putAll(newCitizens);
        buildings.clear(); buildings.putAll(newBuildings);
        tombstones.clear(); tombstones.putAll(newTombstones);
        bindings.restore(snapshot.observations());
        afterRestore.run();
    }

    public void markRecoveryBlocked(UUID checkpointId) {
        requireOwner();
        Objects.requireNonNull(checkpointId, "checkpointId");
        List<ColonyRuntime> updates = colonies.values().stream().filter(value -> !value.recoveryBlocked()).map(value -> new ColonyRuntime(value.colonyId(), value.name(), value.territory(), value.ownerId(), value.members(), Math.incrementExact(value.revision()), value.authorityRevision(), true, checkpointId, value.contentBlocked())).toList();
        if (updates.isEmpty()) return;
        beforeMutation();
        updates.forEach(this::updateColony);
    }
    public void markContentBlocked(Set<UUID> ids) {
        requireOwner();
        List<ColonyRuntime> updates = ids.stream().map(this::colony).filter(value -> !value.contentBlocked()).map(value -> new ColonyRuntime(value.colonyId(), value.name(), value.territory(), value.ownerId(), value.members(), Math.incrementExact(value.revision()), value.authorityRevision(), value.recoveryBlocked(), value.recoveryCheckpointId(), true)).toList();
        if (updates.isEmpty()) return;
        beforeMutation();
        updates.forEach(this::updateColony);
    }
    private static void validateCitizen(CitizenRecord citizen, Map<UUID, ColonyRuntime> colonies, Map<UUID, BuildingRecord> buildings) {
        ColonyRuntime colony = required(colonies, citizen.colonyId(), "citizen colony");
        for (UUID reference : new UUID[]{citizen.homeId(), citizen.workplaceId()}) {
            if (reference == null) continue;
            BuildingRecord building = buildings.get(reference);
            if (building == null && colony.contentBlocked()) continue;
            if (building == null || !building.colonyId().equals(citizen.colonyId())) throw new IllegalArgumentException("Citizen references unknown/foreign building");
        }
        if (citizen.assignedWorkId() != null && citizen.lifecycle() != CitizenRecord.Lifecycle.ALIVE) throw new IllegalArgumentException("Nonliving citizen has work assignment");
    }
    private static <T> T required(Map<UUID, T> values, UUID id, String kind) {
        T value = values.get(id);
        if (value == null) throw new IllegalArgumentException("Unknown " + kind + " UUID: " + id);
        return value;
    }
}
