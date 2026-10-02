package io.github.kpuctajluk.colonyloom.core.colony;

import io.github.kpuctajluk.colonyloom.core.building.BuildingRecord;
import io.github.kpuctajluk.colonyloom.core.citizen.BindingRegistry;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.persistence.RegistrySnapshot;
import io.github.kpuctajluk.colonyloom.core.persistence.Tombstone;
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
    public static final int MAX_COLONIES = 3;
    public static final int MAX_CITIZENS = 30;
    public static final int MAX_BUILDINGS = 128;
    public static final int MAX_TOMBSTONES = 65_536;
    private final Runnable ownerCheck;
    private Runnable beforeMutation = () -> {};
    private final LinkedHashMap<UUID, ColonyRuntime> colonies = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, CitizenRecord> citizens = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, BuildingRecord> buildings = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Tombstone> tombstones = new LinkedHashMap<>();
    private final BindingRegistry bindings;

    public ColonyRegistry(Runnable ownerCheck) {
        this.ownerCheck = Objects.requireNonNull(ownerCheck, "ownerCheck");
        bindings = new BindingRegistry(this);
    }
    public void requireOwner() { ownerCheck.run(); }
    public void setBeforeMutation(Runnable hook) { requireOwner(); beforeMutation = Objects.requireNonNull(hook, "hook"); }
    public void beforeMutation() { requireOwner(); beforeMutation.run(); }
    public BindingRegistry bindings() { requireOwner(); return bindings; }
    public List<ColonyRuntime> colonies() { requireOwner(); return List.copyOf(colonies.values()); }
    public List<CitizenRecord> citizens() { requireOwner(); return List.copyOf(citizens.values()); }
    public List<BuildingRecord> buildings() { requireOwner(); return List.copyOf(buildings.values()); }
    public List<Tombstone> tombstones() { requireOwner(); return List.copyOf(tombstones.values()); }
    public ColonyRuntime colony(UUID id) { requireOwner(); return required(colonies, id, "colony"); }
    public CitizenRecord citizen(UUID id) { requireOwner(); return required(citizens, id, "citizen"); }
    public Optional<CitizenRecord> findCitizen(UUID id) { requireOwner(); return Optional.ofNullable(citizens.get(id)); }
    public List<CitizenRecord> citizens(UUID colonyId) { colony(colonyId); return citizens.values().stream().filter(value -> colonyId.equals(value.colonyId())).toList(); }
    public boolean usedId(UUID id) { requireOwner(); return colonies.containsKey(id) || citizens.containsKey(id) || buildings.containsKey(id) || tombstones.containsKey(id); }
    public boolean entityIdUsed(UUID id) { requireOwner(); return citizens.values().stream().anyMatch(value -> value.entityId().equals(id)) || bindings.observations().stream().anyMatch(value -> value.entityId().equals(id)); }

    public void addColony(ColonyRuntime colony) {
        requireOwner();
        if (usedId(colony.colonyId()) || colonies.size() >= MAX_COLONIES) throw new IllegalArgumentException("Colony identity or capacity unavailable");
        if (colonies.values().stream().anyMatch(value -> value.territory().overlaps(colony.territory()))) throw new IllegalArgumentException("Colony territories overlap");
        colonies.put(colony.colonyId(), colony);
    }
    public void updateColony(ColonyRuntime colony) { colony(colony.colonyId()); colonies.put(colony.colonyId(), colony); }
    public void addCitizen(CitizenRecord citizen) {
        requireOwner();
        colony(citizen.colonyId());
        if (usedId(citizen.citizenId()) || entityIdUsed(citizen.entityId()) || citizens.size() >= MAX_CITIZENS || tombstones.size() >= MAX_TOMBSTONES) throw new IllegalArgumentException("Citizen identity or capacity unavailable");
        validateCitizen(citizen, colonies, buildings);
        citizens.put(citizen.citizenId(), citizen);
    }
    public void updateCitizen(CitizenRecord citizen) {
        CitizenRecord old = citizen(citizen.citizenId());
        if (!old.colonyId().equals(citizen.colonyId())) throw new IllegalArgumentException("Citizen cannot change colony");
        validateCitizen(citizen, colonies, buildings);
        citizens.put(citizen.citizenId(), citizen);
    }
    public void addTombstone(Tombstone tombstone) {
        requireOwner();
        if (!tombstones.containsKey(tombstone.citizenId()) && tombstones.size() >= MAX_TOMBSTONES) throw new IllegalStateException("Tombstone capacity reached");
        tombstones.put(tombstone.citizenId(), tombstone);
    }
    public RegistrySnapshot snapshot() {
        return new RegistrySnapshot(colonies(), citizens(), buildings(), tombstones(), bindings.observations());
    }

    /** Validates the whole DTO before replacing any authoritative state. */
    public void restore(RegistrySnapshot snapshot) {
        requireOwner();
        if (snapshot.colonies().size() > MAX_COLONIES || snapshot.citizens().size() > MAX_CITIZENS || snapshot.buildings().size() > MAX_BUILDINGS || snapshot.tombstones().size() > MAX_TOMBSTONES || snapshot.observations().size() > BindingRegistry.MAX_OBSERVATIONS) throw new IllegalArgumentException("Snapshot exceeds development admission limits");
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
        colonies.clear(); colonies.putAll(newColonies);
        citizens.clear(); citizens.putAll(newCitizens);
        buildings.clear(); buildings.putAll(newBuildings);
        tombstones.clear(); tombstones.putAll(newTombstones);
        bindings.restore(snapshot.observations());
    }

    public void markRecoveryBlocked(UUID checkpointId) {
        requireOwner();
        Objects.requireNonNull(checkpointId, "checkpointId");
        List<ColonyRuntime> updates = colonies.values().stream().filter(value -> !value.recoveryBlocked()).map(value -> new ColonyRuntime(value.colonyId(), value.name(), value.territory(), value.ownerId(), value.members(), Math.incrementExact(value.revision()), value.authorityRevision(), true, checkpointId, value.contentBlocked())).toList();
        if (updates.isEmpty()) return;
        beforeMutation();
        updates.forEach(value -> colonies.put(value.colonyId(), value));
    }
    public void markContentBlocked(Set<UUID> ids) {
        requireOwner();
        List<ColonyRuntime> updates = ids.stream().map(this::colony).filter(value -> !value.contentBlocked()).map(value -> new ColonyRuntime(value.colonyId(), value.name(), value.territory(), value.ownerId(), value.members(), Math.incrementExact(value.revision()), value.authorityRevision(), value.recoveryBlocked(), value.recoveryCheckpointId(), true)).toList();
        if (updates.isEmpty()) return;
        beforeMutation();
        updates.forEach(value -> colonies.put(value.colonyId(), value));
    }
    private static void validateCitizen(CitizenRecord citizen, Map<UUID, ColonyRuntime> colonies, Map<UUID, BuildingRecord> buildings) {
        ColonyRuntime colony = required(colonies, citizen.colonyId(), "citizen colony");
        for (UUID reference : new UUID[]{citizen.homeId(), citizen.workplaceId()}) {
            if (reference == null) continue;
            BuildingRecord building = buildings.get(reference);
            if (building == null && colony.contentBlocked()) continue;
            if (building == null || !building.colonyId().equals(citizen.colonyId())) throw new IllegalArgumentException("Citizen references unknown/foreign building");
        }
        if (citizen.assignedWorkId() != null) throw new IllegalArgumentException("Stage02 has no work objects");
    }
    private static <T> T required(Map<UUID, T> values, UUID id, String kind) {
        T value = values.get(id);
        if (value == null) throw new IllegalArgumentException("Unknown " + kind + " UUID: " + id);
        return value;
    }
}
