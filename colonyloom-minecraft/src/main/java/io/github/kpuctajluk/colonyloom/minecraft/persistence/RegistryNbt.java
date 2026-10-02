package io.github.kpuctajluk.colonyloom.minecraft.persistence;

import io.github.kpuctajluk.colonyloom.core.building.BuildingRecord;
import io.github.kpuctajluk.colonyloom.core.citizen.BindingRegistry;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.MemberRank;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.persistence.RegistrySnapshot;
import io.github.kpuctajluk.colonyloom.core.persistence.Tombstone;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Explicit version-one DTO codec; unknown records remain original NBT, never fake objects. */
final class RegistryNbt {
    static final List<String> ROOT_LISTS = List.of("colonies", "citizens", "buildings", "works", "demands",
            "productionOrders", "deliveries", "reservations", "allocations", "evidence", "tombstones", "pinnedDefinitions");
    private static final Map<String, String> KNOWN_TYPES = Map.of("colonies", "colonyloom:colony",
            "citizens", "colonyloom:citizen", "buildings", "colonyloom:building", "tombstones", "colonyloom:tombstone",
            "bindingObservations", "colonyloom:binding_observation", "works", WorkOrder.ACTIVE_WAIT);

    record Decoded(RegistrySnapshot snapshot, Map<String, List<CompoundTag>> retained, Set<UUID> blockedColonies) {}

    private RegistryNbt() {}

    static Decoded decode(CompoundTag root) {
        if (integer(root, "schemaVersion") != 1) {
            throw invalid("Unsupported Colonyloom schemaVersion; only version 1 is supported");
        }
        uuid(root, "checkpointId");
        Map<String, List<CompoundTag>> retained = new LinkedHashMap<>();
        Map<String, List<CompoundTag>> known = new HashMap<>();
        Set<UUID> blocked = new HashSet<>();
        List<String> keys = new ArrayList<>(ROOT_LISTS);
        keys.add("bindingObservations");
        for (String key : keys) {
            ListTag entries = list(root, key);
            int limit = switch (key) {
                case "colonies" -> 3;
                case "citizens" -> 300;
                case "buildings" -> 512;
                case "bindingObservations" -> 256;
                case "works", "productionOrders", "deliveries" -> 8192;
                case "demands" -> 16384;
                case "reservations", "allocations" -> 32768;
                case "evidence" -> 65536;
                case "pinnedDefinitions" -> 64;
                case "tombstones" -> 65536;
                default -> throw invalid("Unsupported root list: " + key);
            };
            if (entries.size() > limit) throw invalid("Bounded persistence envelope exceeded: " + key);
            List<CompoundTag> opaque = new ArrayList<>();
            List<CompoundTag> decoded = new ArrayList<>();
            for (Tag element : entries) {
                CompoundTag entry = (CompoundTag) element;
                String type = string(entry, "typeId");
                if (type.equals(KNOWN_TYPES.get(key))) {
                    decoded.add(entry);
                } else {
                    opaque.add(entry.copy());
                    if (entry.hasUUID("colonyId")) blocked.add(entry.getUUID("colonyId"));
                }
            }
            retained.put(key, opaque);
            known.put(key, decoded);
        }
        List<ColonyRuntime> colonies = new ArrayList<>();
        Set<UUID> colonyIds = new HashSet<>();
        for (CompoundTag entry : known.get("colonies")) {
            ColonyRuntime colony = colony(entry);
            if (!colonyIds.add(colony.colonyId())) throw invalid("Duplicate colonyId");
            colonies.add(colony);
        }
        Set<UUID> unknownColonies = referencedIds(retained.get("colonies"), "colonyId");
        Set<UUID> unknownBuildings = referencedIds(retained.get("buildings"), "buildingId");
        Set<UUID> unknownWorks = referencedIds(retained.get("works"), "workId");
        Set<UUID> objectIds = new HashSet<>(colonyIds);
        for (String key : List.of("colonies", "buildings", "works", "citizens")) {
            String idKey = switch (key) { case "colonies" -> "colonyId"; case "buildings" -> "buildingId"; case "works" -> "workId"; default -> "citizenId"; };
            for (CompoundTag entry : retained.get(key)) if (entry.hasUUID(idKey) && !objectIds.add(entry.getUUID(idKey))) throw invalid("Duplicate retained object identity");
        }
        List<BuildingRecord> buildings = new ArrayList<>();
        Set<UUID> buildingIds = new HashSet<>();
        for (CompoundTag entry : known.get("buildings")) {
            BuildingRecord building = building(entry);
            if (!objectIds.add(building.buildingId())) throw invalid("Duplicate building identity");
            if (!colonyIds.contains(building.colonyId())) {
                if (!unknownColonies.contains(building.colonyId())) throw invalid("Building references missing colony");
                retained.get("buildings").add(entry.copy());
                continue;
            }
            buildingIds.add(building.buildingId());
            buildings.add(building);
        }
        List<WorkOrder.Snapshot> works = new ArrayList<>();
        Set<UUID> workIds = new HashSet<>();
        for (CompoundTag entry : known.get("works")) {
            WorkOrder.Snapshot work = work(entry);
            if (!objectIds.add(work.id())) throw invalid("Duplicate work identity");
            if (!colonyIds.contains(work.colonyId())) {
                if (!unknownColonies.contains(work.colonyId())) throw invalid("Work references missing colony");
                retained.get("works").add(entry.copy());
                unknownWorks.add(work.id());
            } else { works.add(work); workIds.add(work.id()); }
        }
        List<CitizenRecord> citizens = new ArrayList<>();
        Set<UUID> entityIds = new HashSet<>();
        for (CompoundTag entry : known.get("citizens")) {
            CitizenRecord citizen = citizen(entry);
            if (!objectIds.add(citizen.citizenId()) || !entityIds.add(citizen.entityId())) throw invalid("Duplicate citizen/entity identity");
            if (!colonyIds.contains(citizen.colonyId()) ||
                    (citizen.homeId() != null && !buildingIds.contains(citizen.homeId())) ||
                    (citizen.workplaceId() != null && !buildingIds.contains(citizen.workplaceId())) ||
                    (citizen.assignedWorkId() != null && !workIds.contains(citizen.assignedWorkId()))) {
                if (!colonyIds.contains(citizen.colonyId()) && !unknownColonies.contains(citizen.colonyId())) throw invalid("Citizen references missing colony");
                if (citizen.homeId() != null && !buildingIds.contains(citizen.homeId()) && !unknownBuildings.contains(citizen.homeId())) throw invalid("Citizen references missing home");
                if (citizen.workplaceId() != null && !buildingIds.contains(citizen.workplaceId()) && !unknownBuildings.contains(citizen.workplaceId())) throw invalid("Citizen references missing workplace");
                if (citizen.assignedWorkId() != null && !workIds.contains(citizen.assignedWorkId()) && !unknownWorks.contains(citizen.assignedWorkId())) throw invalid("Citizen references missing work");
                retained.get("citizens").add(entry.copy());
                blocked.add(citizen.colonyId());
                continue;
            }
            citizens.add(citizen);
        }
        Map<UUID, CompoundTag> originalWorks = new HashMap<>();
        for (CompoundTag entry : known.get("works")) originalWorks.put(uuid(entry, "workId"), entry);
        Map<UUID, CompoundTag> originalCitizens = new HashMap<>();
        for (CompoundTag entry : known.get("citizens")) originalCitizens.put(uuid(entry, "citizenId"), entry);
        Set<UUID> unknownCitizens = referencedIds(retained.get("citizens"), "citizenId");
        Set<UUID> knownCitizenIds = new HashSet<>();
        for (CitizenRecord citizen : citizens) knownCitizenIds.add(citizen.citizenId());
        boolean changed;
        do {
            changed = false;
            for (var iterator = works.iterator(); iterator.hasNext();) {
                WorkOrder.Snapshot work = iterator.next();
                boolean opaqueDependency = false;
                for (UUID dependency : work.dependencies()) {
                    if (!workIds.contains(dependency) && !unknownWorks.contains(dependency)) throw invalid("Work references missing dependency");
                    opaqueDependency |= unknownWorks.contains(dependency);
                }
                if (work.assignee() != null && !knownCitizenIds.contains(work.assignee()) && !unknownCitizens.contains(work.assignee())) throw invalid("Work references missing citizen");
                if (opaqueDependency || work.assignee() != null && unknownCitizens.contains(work.assignee())) {
                    retained.get("works").add(originalWorks.get(work.id()).copy()); unknownWorks.add(work.id());
                    workIds.remove(work.id()); iterator.remove(); blocked.add(work.colonyId()); changed = true;
                }
            }
            for (var iterator = citizens.iterator(); iterator.hasNext();) {
                CitizenRecord citizen = iterator.next();
                if (citizen.assignedWorkId() != null && unknownWorks.contains(citizen.assignedWorkId())) {
                    retained.get("citizens").add(originalCitizens.get(citizen.citizenId()).copy()); unknownCitizens.add(citizen.citizenId());
                    knownCitizenIds.remove(citizen.citizenId()); iterator.remove(); blocked.add(citizen.colonyId()); changed = true;
                }
            }
        } while (changed);
        List<Tombstone> tombstones = new ArrayList<>();
        Set<UUID> tombstoneIds = new HashSet<>();
        for (CompoundTag entry : known.get("tombstones")) {
            Tombstone tombstone = new Tombstone(uuid(entry, "citizenId"), uuid(entry, "colonyId"), number(entry, "bindingEpoch"),
                    CitizenRecord.Lifecycle.valueOf(string(entry, "lifecycle")));
            if (!tombstoneIds.add(tombstone.citizenId())) throw invalid("Duplicate tombstone identity");
            if (!colonyIds.contains(tombstone.colonyId())) {
                if (!unknownColonies.contains(tombstone.colonyId())) throw invalid("Tombstone references missing colony");
                retained.get("tombstones").add(entry.copy());
            } else tombstones.add(tombstone);
        }
        List<BindingRegistry.Observation> observations = new ArrayList<>();
        Set<UUID> observedEntityIds = new HashSet<>();
        for (CompoundTag entry : known.get("bindingObservations")) {
            bool(entry, "loaded");
            if (!observedEntityIds.add(uuid(entry, "entityId"))) throw invalid("Duplicate binding observation");
            observations.add(new BindingRegistry.Observation(optionalUuid(entry, "citizenId"), uuid(entry, "entityId"),
                    number(entry, "bindingEpoch"), false, bool(entry, "quarantined"), bool(entry, "retired")));
        }
        Set<UUID> unknownObservedCitizens = referencedIds(retained.get("bindingObservations"), "citizenId");
        for (CitizenRecord citizen : citizens) {
            if (unknownObservedCitizens.contains(citizen.citizenId())) blocked.add(citizen.colonyId());
        }
        blocked.retainAll(colonyIds);
        return new Decoded(new RegistrySnapshot(colonies, citizens, buildings, tombstones, observations, works), retained, blocked);
    }

    static CompoundTag encode(RegistrySnapshot snapshot, UUID checkpoint, Map<String, List<CompoundTag>> retained) {
        CompoundTag root = new CompoundTag();
        root.putInt("schemaVersion", 1);
        root.putUUID("checkpointId", checkpoint);
        for (String key : ROOT_LISTS) root.put(key, new ListTag());
        root.put("bindingObservations", new ListTag());
        for (ColonyRuntime value : snapshot.colonies()) root.getList("colonies", Tag.TAG_COMPOUND).add(colony(value));
        for (CitizenRecord value : snapshot.citizens()) root.getList("citizens", Tag.TAG_COMPOUND).add(citizen(value));
        for (WorkOrder.Snapshot value : snapshot.works()) root.getList("works", Tag.TAG_COMPOUND).add(work(value));
        for (BuildingRecord value : snapshot.buildings()) {
            CompoundTag entry = typed("colonyloom:building");
            entry.putUUID("buildingId", value.buildingId());
            entry.putUUID("colonyId", value.colonyId());
            entry.putString("buildingTypeId", value.typeId());
            entry.put("position", position(value.position()));
            entry.putLong("revision", value.revision());
            root.getList("buildings", Tag.TAG_COMPOUND).add(entry);
        }
        for (Tombstone value : snapshot.tombstones()) {
            CompoundTag entry = typed("colonyloom:tombstone");
            entry.putUUID("citizenId", value.citizenId());
            entry.putUUID("colonyId", value.colonyId());
            entry.putLong("bindingEpoch", value.bindingEpoch());
            entry.putString("lifecycle", value.lifecycle().name());
            root.getList("tombstones", Tag.TAG_COMPOUND).add(entry);
        }
        for (BindingRegistry.Observation value : snapshot.observations()) {
            CompoundTag entry = typed("colonyloom:binding_observation");
            optionalUuid(entry, "citizenId", value.citizenId());
            entry.putUUID("entityId", value.entityId());
            entry.putLong("bindingEpoch", value.bindingEpoch());
            entry.putBoolean("loaded", false);
            entry.putBoolean("quarantined", value.quarantined());
            entry.putBoolean("retired", value.retired());
            root.getList("bindingObservations", Tag.TAG_COMPOUND).add(entry);
        }
        retained.forEach((key, entries) -> entries.forEach(entry -> root.getList(key, Tag.TAG_COMPOUND).add(entry.copy())));
        return root;
    }

    private static WorkOrder.Snapshot work(CompoundTag entry) {
        ListTag dependencyTags = list(entry, "dependencies");
        if (dependencyTags.size() > WorkOrder.MAX_DEPENDENCIES) throw invalid("Too many work dependencies");
        List<UUID> dependencies = new ArrayList<>(dependencyTags.size());
        for (Tag tag : dependencyTags) dependencies.add(uuid((CompoundTag) tag, "workId"));
        return new WorkOrder.Snapshot(string(entry, "typeId"), uuid(entry, "workId"), uuid(entry, "colonyId"),
                position(compound(entry, "target")), entry.contains("professionId") ? string(entry, "professionId") : null,
                integer(entry, "priority"), AdmissionLedger.Lane.valueOf(string(entry, "lane")),
                WorkOrder.State.valueOf(string(entry, "state")), optionalUuid(entry, "assignee"), string(entry, "stage"),
                number(entry, "revision"), dependencies, WorkOrder.Reason.valueOf(string(entry, "waitingReason")),
                number(entry, "remainingActiveTicks"), number(entry, "ageActiveTicks"));
    }

    private static CompoundTag work(WorkOrder.Snapshot value) {
        CompoundTag entry = typed(value.typeId());
        entry.putUUID("workId", value.id()); entry.putUUID("colonyId", value.colonyId());
        entry.put("target", position(value.target()));
        if (value.professionId() != null) entry.putString("professionId", value.professionId());
        entry.putInt("priority", value.priority()); entry.putString("lane", value.lane().name());
        entry.putString("state", value.state().name()); optionalUuid(entry, "assignee", value.assignee());
        entry.putString("stage", value.stage()); entry.putLong("revision", value.revision());
        entry.putString("waitingReason", value.waitingReason().name());
        entry.putLong("remainingActiveTicks", value.remainingActiveTicks()); entry.putLong("ageActiveTicks", value.ageActiveTicks());
        ListTag dependencies = new ListTag();
        for (UUID id : value.dependencies()) { CompoundTag reference = new CompoundTag(); reference.putUUID("workId", id); dependencies.add(reference); }
        entry.put("dependencies", dependencies);
        return entry;
    }

    private static ColonyRuntime colony(CompoundTag entry) {
        CompoundTag area = compound(entry, "territory");
        Territory territory = new Territory(string(area, "dimension"), integer(area, "minX"), integer(area, "minZ"), integer(area, "maxX"), integer(area, "maxZ"));
        Map<UUID, MemberRank> members = new LinkedHashMap<>();
        for (Tag tag : list(entry, "members")) {
            CompoundTag member = (CompoundTag) tag;
            if (members.put(uuid(member, "playerId"), MemberRank.valueOf(string(member, "rank"))) != null) throw invalid("Duplicate member");
        }
        return new ColonyRuntime(uuid(entry, "colonyId"), string(entry, "name"), territory, uuid(entry, "ownerId"), members,
                number(entry, "revision"), number(entry, "authorityRevision"), bool(entry, "recoveryBlocked"), optionalUuid(entry, "recoveryCheckpointId"), bool(entry, "contentBlocked"));
    }

    private static CompoundTag colony(ColonyRuntime value) {
        CompoundTag entry = typed("colonyloom:colony");
        entry.putUUID("colonyId", value.colonyId());
        entry.putString("name", value.name());
        entry.putUUID("ownerId", value.ownerId());
        CompoundTag territory = new CompoundTag();
        territory.putString("dimension", value.territory().dimension());
        territory.putInt("minX", value.territory().minX());
        territory.putInt("minZ", value.territory().minZ());
        territory.putInt("maxX", value.territory().maxX());
        territory.putInt("maxZ", value.territory().maxZ());
        entry.put("territory", territory);
        ListTag members = new ListTag();
        value.members().forEach((id, rank) -> {
            CompoundTag member = new CompoundTag();
            member.putUUID("playerId", id);
            member.putString("rank", rank.name());
            members.add(member);
        });
        entry.put("members", members);
        entry.putLong("revision", value.revision());
        entry.putLong("authorityRevision", value.authorityRevision());
        entry.putBoolean("recoveryBlocked", value.recoveryBlocked());
        optionalUuid(entry, "recoveryCheckpointId", value.recoveryCheckpointId());
        entry.putBoolean("contentBlocked", value.contentBlocked());
        return entry;
    }

    private static CitizenRecord citizen(CompoundTag entry) {
        CitizenRecord.Readiness.valueOf(string(entry, "readiness"));
        return new CitizenRecord(uuid(entry, "citizenId"), uuid(entry, "colonyId"), uuid(entry, "entityId"), number(entry, "bindingEpoch"),
                optionalUuid(entry, "homeId"), optionalUuid(entry, "workplaceId"), optionalUuid(entry, "assignedWorkId"), entry.contains("professionId") ? string(entry, "professionId") : null,
                integers(compound(entry, "skills")), integers(compound(entry, "needs")), CitizenRecord.Lifecycle.valueOf(string(entry, "lifecycle")),
                CitizenRecord.Admission.valueOf(string(entry, "admission")), CitizenRecord.Readiness.UNKNOWN, number(entry, "activeTimeTicks"),
                longs(compound(entry, "remainingTimers")), position(compound(entry, "lastKnownPosition")), number(entry, "revision"));
    }

    private static CompoundTag citizen(CitizenRecord value) {
        CompoundTag entry = typed("colonyloom:citizen");
        entry.putUUID("citizenId", value.citizenId());
        entry.putUUID("colonyId", value.colonyId());
        entry.putUUID("entityId", value.entityId());
        entry.putLong("bindingEpoch", value.bindingEpoch());
        optionalUuid(entry, "homeId", value.homeId());
        optionalUuid(entry, "workplaceId", value.workplaceId());
        optionalUuid(entry, "assignedWorkId", value.assignedWorkId());
        if (value.professionId() != null) entry.putString("professionId", value.professionId());
        CompoundTag skills = new CompoundTag();
        value.skills().forEach(skills::putInt);
        entry.put("skills", skills);
        CompoundTag needs = new CompoundTag();
        value.needs().forEach(needs::putInt);
        entry.put("needs", needs);
        entry.putString("lifecycle", value.lifecycle().name());
        entry.putString("admission", value.admission().name());
        entry.putString("readiness", value.readiness().name());
        entry.putLong("activeTimeTicks", value.activeTimeTicks());
        CompoundTag timers = new CompoundTag();
        value.remainingTimers().forEach(timers::putLong);
        entry.put("remainingTimers", timers);
        entry.put("lastKnownPosition", position(value.lastKnownPosition()));
        entry.putLong("revision", value.revision());
        return entry;
    }

    private static BuildingRecord building(CompoundTag entry) {
        return new BuildingRecord(uuid(entry, "buildingId"), uuid(entry, "colonyId"), string(entry, "buildingTypeId"), position(compound(entry, "position")), number(entry, "revision"));
    }

    private static WorldPosition position(CompoundTag entry) {
        return new WorldPosition(string(entry, "dimension"), integer(entry, "x"), integer(entry, "y"), integer(entry, "z"));
    }

    private static CompoundTag position(WorldPosition value) {
        CompoundTag entry = new CompoundTag();
        entry.putString("dimension", value.dimension());
        entry.putInt("x", value.x());
        entry.putInt("y", value.y());
        entry.putInt("z", value.z());
        return entry;
    }

    private static Map<String, Integer> integers(CompoundTag tag) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (String key : tag.getAllKeys()) values.put(key, integer(tag, key));
        return values;
    }

    private static Map<String, Long> longs(CompoundTag tag) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (String key : tag.getAllKeys()) values.put(key, number(tag, key));
        return values;
    }

    private static CompoundTag typed(String type) {
        CompoundTag tag = new CompoundTag();
        tag.putString("typeId", type);
        return tag;
    }

    private static Set<UUID> referencedIds(List<CompoundTag> entries, String key) {
        Set<UUID> ids = new HashSet<>();
        for (CompoundTag entry : entries) if (entry.hasUUID(key)) ids.add(entry.getUUID(key));
        return ids;
    }

    static UUID uuid(CompoundTag tag, String key) {
        if (!tag.hasUUID(key)) throw invalid("Missing or invalid UUID: " + key);
        return tag.getUUID(key);
    }

    private static UUID optionalUuid(CompoundTag tag, String key) {
        return tag.contains(key) ? uuid(tag, key) : null;
    }

    private static void optionalUuid(CompoundTag tag, String key, UUID value) {
        if (value != null) tag.putUUID(key, value);
    }

    private static String string(CompoundTag tag, String key) {
        require(tag, key, Tag.TAG_STRING);
        String value = tag.getString(key);
        if (value.isEmpty() || value.length() > 256) throw invalid("Invalid string: " + key);
        return value;
    }

    private static int integer(CompoundTag tag, String key) {
        require(tag, key, Tag.TAG_INT);
        return tag.getInt(key);
    }

    private static long number(CompoundTag tag, String key) {
        require(tag, key, Tag.TAG_LONG);
        return tag.getLong(key);
    }

    static boolean bool(CompoundTag tag, String key) {
        require(tag, key, Tag.TAG_BYTE);
        byte value = tag.getByte(key);
        if (value != 0 && value != 1) throw invalid("Invalid boolean: " + key);
        return value == 1;
    }

    static CompoundTag compound(CompoundTag tag, String key) {
        require(tag, key, Tag.TAG_COMPOUND);
        return tag.getCompound(key);
    }

    private static ListTag list(CompoundTag tag, String key) {
        require(tag, key, Tag.TAG_LIST);
        ListTag value = (ListTag) tag.get(key);
        if (!value.isEmpty() && value.getElementType() != Tag.TAG_COMPOUND) throw invalid("Invalid list: " + key);
        if (value.size() > 65536) throw invalid("Oversized list: " + key);
        return value;
    }

    private static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw invalid("Missing or invalid tag: " + key);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
