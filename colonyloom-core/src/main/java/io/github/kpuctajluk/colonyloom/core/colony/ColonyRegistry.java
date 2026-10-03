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
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Server-thread-owned persistent identity registry. Commands own ordinary mutations. */
public final class ColonyRegistry {
    private AdmissionLedger admission;
    private final WorkBoard workBoard;
    private final io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets budgets;
    private final io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics metrics;
    private final io.github.kpuctajluk.colonyloom.core.spatial.TargetClaimRegistry targetClaims;
    private final io.github.kpuctajluk.colonyloom.core.action.EffectRegistry effects;
    private final io.github.kpuctajluk.colonyloom.core.construction.ConstructionRegistry construction;
    private final io.github.kpuctajluk.colonyloom.core.storage.StorageRegistry storage;
    private final io.github.kpuctajluk.colonyloom.core.supply.SupplyRegistry supply;
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
        metrics = new io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics(ownerCheck);
        admission = new AdmissionLedger(SimulationLimits.development(), ownerCheck);
        workBoard = new WorkBoard(this, admission);
        budgets = new io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets(SimulationLimits.development());
        targetClaims = new io.github.kpuctajluk.colonyloom.core.spatial.TargetClaimRegistry(this, budgets);
        effects = new io.github.kpuctajluk.colonyloom.core.action.EffectRegistry(this);
        construction = new io.github.kpuctajluk.colonyloom.core.construction.ConstructionRegistry(this);
        bindings = new BindingRegistry(this);
        storage = new io.github.kpuctajluk.colonyloom.core.storage.StorageRegistry(this);
        supply = new io.github.kpuctajluk.colonyloom.core.supply.SupplyRegistry(this);
    }
    public void requireOwner() { ownerCheck.run(); }
    public void setBeforeMutation(Runnable hook) { requireOwner(); beforeMutation = Objects.requireNonNull(hook, "hook"); }
    public void beforeMutation() { requireOwner(); beforeMutation.run(); }
    public void setAfterRestore(Runnable hook) { requireOwner(); afterRestore = Objects.requireNonNull(hook); }
    public BindingRegistry bindings() { requireOwner(); return bindings; }
    public AdmissionLedger admission() { requireOwner(); return admission; }
    public WorkBoard workBoard() { requireOwner(); return workBoard; }
    public io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets budgets() { requireOwner(); return budgets; }
    public io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics metrics() { requireOwner(); return metrics; }
    public io.github.kpuctajluk.colonyloom.core.spatial.TargetClaimRegistry targetClaims() { requireOwner(); return targetClaims; }
    public io.github.kpuctajluk.colonyloom.core.action.EffectRegistry effects() { requireOwner(); return effects; }
    public io.github.kpuctajluk.colonyloom.core.construction.ConstructionRegistry construction() { requireOwner(); return construction; }
    public io.github.kpuctajluk.colonyloom.core.storage.StorageRegistry storage() { requireOwner(); return storage; }
    public io.github.kpuctajluk.colonyloom.core.supply.SupplyRegistry supply() { requireOwner(); return supply; }
    public Collection<CitizenRecord> citizensView() { requireOwner(); return Collections.unmodifiableCollection(citizens.values()); }
    public List<ColonyRuntime> colonies() { requireOwner(); return List.copyOf(colonies.values()); }
    public List<CitizenRecord> citizens() { requireOwner(); return List.copyOf(citizens.values()); }
    public List<BuildingRecord> buildings() { requireOwner(); return List.copyOf(buildings.values()); }
    public List<Tombstone> tombstones() { requireOwner(); return List.copyOf(tombstones.values()); }
    public ColonyRuntime colony(UUID id) { requireOwner(); return required(colonies, id, "colony"); }
    public CitizenRecord citizen(UUID id) { requireOwner(); return required(citizens, id, "citizen"); }
    public Optional<CitizenRecord> findCitizen(UUID id) { requireOwner(); return Optional.ofNullable(citizens.get(id)); }
    public List<CitizenRecord> citizens(UUID colonyId) { colony(colonyId); return citizens.values().stream().filter(value -> colonyId.equals(value.colonyId())).toList(); }
    public boolean usedId(UUID id) { requireOwner(); return colonies.containsKey(id) || citizens.containsKey(id) || buildings.containsKey(id) || tombstones.containsKey(id) || storage.usedId(id) || supply.usedId(id) || effects.get(id)!=null || workBoard.works().stream().anyMatch(work -> work.id().equals(id)); }
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
        validateCitizen(citizen, colonies, buildings,storage.workshops());
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
        validateCitizen(citizen, colonies, buildings,storage.workshops());
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
        return new RegistrySnapshot(colonies(), citizens(), buildings(), tombstones(), bindings.observations(), workBoard.snapshots(), targetClaims.snapshots(), effects.snapshots(), construction.snapshots(), construction.definitions(), storage.snapshot(), supply.snapshot());
    }

    /** Validates the whole DTO before replacing any authoritative state. */
    public void restore(RegistrySnapshot snapshot) {
        requireOwner();
        SimulationLimits limits = admission.limits();
        if (snapshot.colonies().size() > 3 || snapshot.citizens().size() > 300 || snapshot.buildings().size() > 512 || snapshot.works().size() > 8192 || snapshot.targetClaims().size() > 512 || snapshot.tombstones().size() > 65536 || snapshot.observations().size() > BindingRegistry.MAX_OBSERVATIONS) throw new IllegalArgumentException("Snapshot exceeds bounded persistence envelope");
        var savedStock=snapshot.storage();
        if(savedStock.registrations().size()>io.github.kpuctajluk.colonyloom.core.storage.StorageRegistry.MAX_REGISTRATIONS
                || savedStock.workshops().size()>io.github.kpuctajluk.colonyloom.core.storage.StorageRegistry.MAX_WORKSHOPS
                || (long)savedStock.reservations().size()+savedStock.allocations().size()>io.github.kpuctajluk.colonyloom.core.storage.StorageRegistry.MAX_OBLIGATIONS
                || savedStock.retiredIdentities().size()>io.github.kpuctajluk.colonyloom.core.storage.StorageRegistry.MAX_RETIRED_IDENTITIES)
            throw new IllegalArgumentException("Snapshot exceeds bounded stock persistence envelope");
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
            validateCitizen(value, newColonies, newBuildings,snapshot.storage().workshops());
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
        Set<UUID> foodSubjects = new HashSet<>();
        for (WorkOrder.Snapshot work : snapshot.works()) {
            ColonyRuntime colony = required(newColonies, work.colonyId(), "work colony");
            if (!objectIds.add(work.id()) || !(WorkOrder.ACTIVE_WAIT.equals(work.typeId()) || WorkOrder.MOVE.equals(work.typeId()) || WorkOrder.CONSTRUCTION.equals(work.typeId()) || WorkOrder.DELIVERY.equals(work.typeId()) || WorkOrder.PRODUCTION.equals(work.typeId()) || WorkOrder.FOOD.equals(work.typeId())) || !colony.territory().contains(work.target())) throw new IllegalArgumentException("Invalid work identity/type/target");
            newWorks.put(work.id(), work);
            if (work.subjectId() != null && !required(newCitizens, work.subjectId(), "work subject").colonyId().equals(work.colonyId())) throw new IllegalArgumentException("Foreign work subject");
            if (WorkOrder.FOOD.equals(work.typeId()) && work.state() != WorkOrder.State.COMPLETED && work.state() != WorkOrder.State.CANCELLED && work.state() != WorkOrder.State.FAILED
                    && !foodSubjects.add(work.subjectId())) throw new IllegalArgumentException("Duplicate live food subject");
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
        Map<UUID,io.github.kpuctajluk.colonyloom.core.spatial.TargetClaimRegistry.Snapshot> constructionClaims=new HashMap<>();
        for (var claim : snapshot.targetClaims()) {
            required(newColonies, claim.colonyId(), "target colony");
            if (!claimIds.add(claim.ownerId())) throw new IllegalArgumentException("Duplicate target owner");
            constructionClaims.put(claim.ownerId(),claim);
            if (claim.buildingId() != null && !required(newBuildings, claim.buildingId(), "target building").colonyId().equals(claim.colonyId())) throw new IllegalArgumentException("Foreign target building");
        }
        Set<UUID> siteWorks = new HashSet<>();
        for (var site : snapshot.constructionSites()) {
            var work = required(newWorks,site.workId(),"construction work");
            if (!siteWorks.add(site.workId()) || !work.colonyId().equals(site.colonyId()) || !WorkOrder.CONSTRUCTION.equals(work.typeId())) throw new IllegalArgumentException("Invalid construction work reference");
            required(newColonies,site.colonyId(),"construction colony");
            boolean terminal=work.state()==WorkOrder.State.COMPLETED || work.state()==WorkOrder.State.CANCELLED || work.state()==WorkOrder.State.FAILED;
            if(site.closed()!=terminal) throw new IllegalArgumentException("Construction site/work closure mismatch");
            if(!site.closed()) {
                var claim=constructionClaims.get(site.workId());
                if(claim==null || !claim.colonyId().equals(site.colonyId()) || claim.revision()!=site.claimRevision()
                        || !claim.dimension().equals(site.origin().dimension()) || claim.buildingId()!=null) throw new IllegalArgumentException("Construction missing authoritative target claim");
            }
        }
        for (var work : newWorks.values()) if (WorkOrder.CONSTRUCTION.equals(work.typeId()) && !siteWorks.contains(work.id())) throw new IllegalArgumentException("Construction work missing site");
        for (var effect : snapshot.effects()) {
            var citizen = required(newCitizens,effect.citizenId(),"effect citizen");
            if (!objectIds.add(effect.operationId()) || !citizen.colonyId().equals(effect.colonyId()) || citizen.bindingEpoch()<effect.bindingEpoch()) throw new IllegalArgumentException("Invalid effect identity/binding");
            if (effect.workId()!=null && !required(newWorks,effect.workId(),"effect work").colonyId().equals(effect.colonyId())) throw new IllegalArgumentException("Foreign effect work");
        }
        var stockSlots=new java.util.HashSet<io.github.kpuctajluk.colonyloom.core.storage.StockRegion>();
        for(var registration:snapshot.storage().registrations()) {
            if(!objectIds.add(registration.id())) throw new IllegalArgumentException("Duplicate stock registration identity");
            stockSlots.addAll(registration.slots());
        }
        for(var workshop:snapshot.storage().workshops()) if(!objectIds.add(workshop.id())) throw new IllegalArgumentException("Duplicate stock workshop identity");
        for(var reservation:snapshot.storage().reservations()) {
            if(!objectIds.add(reservation.id())) throw new IllegalArgumentException("Duplicate reservation identity");
            stockSlots.add(reservation.slot());
        }
        for(var allocation:snapshot.storage().allocations()) {
            if(!objectIds.add(allocation.id())) throw new IllegalArgumentException("Duplicate allocation identity");
            stockSlots.add(allocation.slot());
        }
        for(var demand:snapshot.supply().demands()) if(!objectIds.add(demand.id())) throw new IllegalArgumentException("Duplicate demand identity");
        Set<UUID> foodDemandOwners = new HashSet<>();
        for (var demand : snapshot.supply().demands()) {
            var work = newWorks.get(demand.ownerId());
            if (work == null || !WorkOrder.FOOD.equals(work.typeId())) continue;
            if (!foodDemandOwners.add(work.id()) || !demand.colonyId().equals(work.colonyId()) || demand.lane() != AdmissionLedger.Lane.CRITICAL
                    || demand.goalKind() != io.github.kpuctajluk.colonyloom.core.supply.Demand.GoalKind.CONSUMPTION || demand.required() != 1
                    || !demand.matcher().itemId().equals("minecraft:bread")) throw new IllegalArgumentException("Invalid food demand");
        }
        for (var effect : snapshot.effects()) if (effect.food() != null) {
            var work = required(newWorks, effect.workId(), "food effect work");
            var demand = snapshot.supply().demands().stream().filter(d -> d.id().equals(effect.food().demandId())).findFirst().orElseThrow(() -> new IllegalArgumentException("Missing food evidence demand"));
            var share = snapshot.supply().shares().stream().filter(s -> s.id().equals(effect.food().shareId())).findFirst().orElse(null);
            if (!WorkOrder.FOOD.equals(work.typeId()) || !effect.citizenId().equals(work.subjectId()) || !demand.ownerId().equals(work.id())
                    || share == null && effect.state() != io.github.kpuctajluk.colonyloom.core.action.EffectRecord.State.ACCEPTED
                    || share != null && (!share.demandId().equals(demand.id()) || !share.item().equals(effect.food().item()))) throw new IllegalArgumentException("Food evidence owner mismatch");
        }
        for(var share:snapshot.supply().shares()) if(!objectIds.add(share.id())) throw new IllegalArgumentException("Duplicate coverage identity");
        for(var order:snapshot.supply().productionOrders()) if(!objectIds.add(order.id())) throw new IllegalArgumentException("Duplicate production identity");
        for(var order:snapshot.supply().deliveries()) if(!objectIds.add(order.id())) throw new IllegalArgumentException("Duplicate delivery identity");
        Set<UUID> productionWorks = new HashSet<>();
        for(var order:snapshot.supply().productionOrders()) {
            if(order.workId()!=null) {
                if(!productionWorks.add(order.workId())) throw new IllegalArgumentException("Production work counted twice");
                var work=required(newWorks,order.workId(),"production work");
                if(!work.colonyId().equals(order.colonyId()) || !WorkOrder.PRODUCTION.equals(work.typeId()) || !work.target().equals(order.equipmentPosition())
                        || !Objects.equals(work.professionId(),order.recipe().professionId()) || work.assignee()!=null && order.batchStarted() && !work.assignee().equals(order.citizenId())) throw new IllegalArgumentException("Invalid production work binding");
                if(!order.terminal() && work.state()==WorkOrder.State.COMPLETED || !order.terminal() && work.state()==WorkOrder.State.CANCELLED || !order.terminal() && work.state()==WorkOrder.State.FAILED) throw new IllegalArgumentException("Live production references terminal work");
            }
            if(order.citizenId()!=null) {
                var citizen=required(newCitizens,order.citizenId(),"producer");
                if(!citizen.colonyId().equals(order.colonyId()) || !order.workshopId().equals(citizen.workplaceId()) || !order.recipe().professionId().equals(citizen.professionId())) throw new IllegalArgumentException("Invalid producer workplace");
            }
        }
        Set<UUID> deliveryWorks = new HashSet<>();
        for(var order:snapshot.supply().deliveries()) {
            if (order.workId() != null) {
                if (!deliveryWorks.add(order.workId())) throw new IllegalArgumentException("Delivery work counted twice");
                var work = required(newWorks, order.workId(), "delivery work");
                if (!work.colonyId().equals(order.colonyId()) || !WorkOrder.DELIVERY.equals(work.typeId())
                        || work.assignee() != null && !work.assignee().equals(order.citizenId())) throw new IllegalArgumentException("Invalid delivery work binding");
            }
            if(order.citizenId()!=null&&!required(newCitizens,order.citizenId(),"courier").colonyId().equals(order.colonyId()))throw new IllegalArgumentException("Foreign courier");
            for (var share : snapshot.supply().shares()) if (order.id().equals(share.sourceOrderId())
                    && share.stage() == io.github.kpuctajluk.colonyloom.core.supply.CoverageShare.Stage.IN_TRANSIT) {
                if (order.citizenId() == null || order.workId() == null) throw new IllegalArgumentException("Cargo lacks bound courier work");
                var courier = required(newCitizens, order.citizenId(), "cargo courier");
                if (!share.slot().storage().identity().equals(courier.citizenId()) || share.slot().storage().bindingEpoch() <= 0
                        || share.slot().storage().bindingEpoch() > courier.bindingEpoch()) throw new IllegalArgumentException("Invalid canonical cargo identity/epoch");
            }
        }
        // Restore already accepted state, then drain at the configured limits without eviction.
        int restorationRoots = Math.max(1, snapshot.works().size());
        SimulationLimits restorationLimits = limits
                .withResource(SimulationLimits.Resource.COLONIES, Math.max(limits.resource(SimulationLimits.Resource.COLONIES), snapshot.colonies().size()))
                .withResource(SimulationLimits.Resource.CITIZENS, Math.max(limits.resource(SimulationLimits.Resource.CITIZENS), snapshot.citizens().size()))
                .withResource(SimulationLimits.Resource.TOMBSTONES, Math.max(limits.resource(SimulationLimits.Resource.TOMBSTONES), Math.addExact(snapshot.citizens().size(), snapshot.tombstones().size())))
                .withResource(SimulationLimits.Resource.PHYSICAL_TARGETS, Math.max(limits.resource(SimulationLimits.Resource.PHYSICAL_TARGETS), Math.multiplyExact(4,snapshot.buildings().size()+snapshot.targetClaims().size()+snapshot.storage().registrations().size()+snapshot.storage().workshops().size())))
                .withResource(SimulationLimits.Resource.SPATIAL_INDEX_LINKS, Math.max(limits.resource(SimulationLimits.Resource.SPATIAL_INDEX_LINKS), Math.multiplyExact(snapshot.targetClaims().size(), 128)))
                .withResource(SimulationLimits.Resource.WORKS, Math.max(limits.resource(SimulationLimits.Resource.WORKS), Math.multiplyExact(restorationRoots, 16)))
                .withResource(SimulationLimits.Resource.STORAGE_SLOTS,Math.max(limits.resource(SimulationLimits.Resource.STORAGE_SLOTS),Math.multiplyExact(4,stockSlots.size())))
                .withResource(SimulationLimits.Resource.RESERVATIONS_AND_ALLOCATIONS,Math.max(limits.resource(SimulationLimits.Resource.RESERVATIONS_AND_ALLOCATIONS),Math.multiplyExact(4,snapshot.storage().reservations().size()+snapshot.storage().allocations().size())))
                .withResource(SimulationLimits.Resource.EVIDENCE, Math.max(limits.resource(SimulationLimits.Resource.EVIDENCE), Math.multiplyExact(4,snapshot.citizens().size()+snapshot.observations().size()+snapshot.effects().size()+snapshot.constructionSites().size()+snapshot.storage().retiredIdentities().size())))
                .withResource(SimulationLimits.Resource.DEMANDS, Math.max(limits.resource(SimulationLimits.Resource.DEMANDS), Math.multiplyExact(8,snapshot.supply().demands().size())))
                .withResource(SimulationLimits.Resource.COVERAGE_SHARES, Math.max(limits.resource(SimulationLimits.Resource.COVERAGE_SHARES), Math.multiplyExact(8,snapshot.supply().shares().size())))
                .withResource(SimulationLimits.Resource.DELIVERIES_AND_PRODUCTION_ORDERS, Math.max(limits.resource(SimulationLimits.Resource.DELIVERIES_AND_PRODUCTION_ORDERS), Math.multiplyExact(8,snapshot.supply().productionOrders().size()+snapshot.supply().deliveries().size())))
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
        io.github.kpuctajluk.colonyloom.core.supply.SupplyRegistry.validatePins(snapshot.pinnedBlueprints(),snapshot.supply().productionOrders().stream().map(io.github.kpuctajluk.colonyloom.core.production.ProductionOrder::recipe).toList(),null);
        try (var preparedClaims = targetClaims.prepareRestore(snapshot.targetClaims(), replacement);
                var preparedEffects = effects.prepareRestore(snapshot.effects(),replacement);
                var preparedConstruction = construction.prepareRestore(snapshot.constructionSites(),snapshot.pinnedBlueprints(),replacement);
                var preparedStorage = storage.prepareRestore(snapshot.storage(),replacement,newColonies.values());
                var preparedSupply = supply.prepareRestore(snapshot.supply(),snapshot.storage(),replacement,newColonies.values())) {
            workBoard.restoreValidated(snapshot.works(), replacement);
            preparedClaims.commit(); preparedEffects.commit(); preparedConstruction.commit(); preparedStorage.commit(); preparedSupply.commit();
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
    public void markRecoveryBlocked(UUID colonyId,UUID checkpointId) {
        requireOwner(); Objects.requireNonNull(checkpointId); var colony=colony(colonyId); if(colony.recoveryBlocked()) return;
        beforeMutation(); updateColony(new ColonyRuntime(colony.colonyId(),colony.name(),colony.territory(),colony.ownerId(),colony.members(),colony.revision()+1,colony.authorityRevision(),true,checkpointId,colony.contentBlocked()));
    }
    public void markContentBlocked(Set<UUID> ids) {
        requireOwner();
        List<ColonyRuntime> updates = ids.stream().map(this::colony).filter(value -> !value.contentBlocked()).map(value -> new ColonyRuntime(value.colonyId(), value.name(), value.territory(), value.ownerId(), value.members(), Math.incrementExact(value.revision()), value.authorityRevision(), value.recoveryBlocked(), value.recoveryCheckpointId(), true)).toList();
        if (updates.isEmpty()) return;
        beforeMutation();
        updates.forEach(this::updateColony);
    }
    private static void validateCitizen(CitizenRecord citizen, Map<UUID, ColonyRuntime> colonies, Map<UUID, BuildingRecord> buildings,
            java.util.Collection<io.github.kpuctajluk.colonyloom.core.storage.StorageRegistry.Workshop> workshops) {
        ColonyRuntime colony = required(colonies, citizen.colonyId(), "citizen colony");
        if(citizen.homeId()!=null) {
            BuildingRecord home=buildings.get(citizen.homeId());
            if((home==null && !colony.contentBlocked()) || home!=null && !home.colonyId().equals(citizen.colonyId())) throw new IllegalArgumentException("Citizen references unknown/foreign home");
        }
        if(citizen.workplaceId()!=null) {
            BuildingRecord building=buildings.get(citizen.workplaceId());
            var workshop=workshops.stream().filter(value -> value.id().equals(citizen.workplaceId())).findFirst().orElse(null);
            if(building!=null && !building.colonyId().equals(citizen.colonyId()) || workshop!=null && !workshop.colonyId().equals(citizen.colonyId())
                    || building==null && workshop==null && !colony.contentBlocked()) throw new IllegalArgumentException("Citizen references unknown/foreign workplace");
        }
        if (citizen.assignedWorkId() != null && citizen.lifecycle() != CitizenRecord.Lifecycle.ALIVE) throw new IllegalArgumentException("Nonliving citizen has work assignment");
    }
    private static <T> T required(Map<UUID, T> values, UUID id, String kind) {
        T value = values.get(id);
        if (value == null) throw new IllegalArgumentException("Unknown " + kind + " UUID: " + id);
        return value;
    }
}
