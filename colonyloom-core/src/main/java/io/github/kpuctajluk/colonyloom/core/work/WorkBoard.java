package io.github.kpuctajluk.colonyloom.core.work;

import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lease;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/** Accepted roots retain their cleanup capacity until explicit safe retirement. */
public final class WorkBoard {
    private static final Map<Resource,Integer> ROOT = Map.of(Resource.WORKS,1,Resource.WAIT_REGISTRATIONS,1);
    private static final Map<Resource,Integer> TERMINAL = Map.of(Resource.WORKS,1);
    private final ColonyRegistry registry;
    private AdmissionLedger ledger;
    private final LinkedHashMap<UUID,WorkOrder> works = new LinkedHashMap<>();
    private final Map<UUID,Lease> leases = new HashMap<>();
    private final Collection<WorkOrder> view = Collections.unmodifiableCollection(works.values());
    private Consumer<UUID> changed = ignored -> {};
    private Consumer<UUID> citizenChanged = ignored -> {};
    private Consumer<UUID> colonyChanged = ignored -> {};
    private Consumer<UUID> invalidated = ignored -> {};
    private Consumer<UUID> retired = ignored -> {};
    private ToLongFunction<WorkOrder> age = WorkOrder::ageActiveTicks;
    private ToLongFunction<WorkOrder> remaining = WorkOrder::remainingActiveTicks;
    public WorkBoard(ColonyRegistry registry, AdmissionLedger ledger) { this.registry = Objects.requireNonNull(registry); this.ledger = Objects.requireNonNull(ledger); }
    public ColonyRegistry registry() { registry.requireOwner(); return registry; }
    public AdmissionLedger ledger() { registry.requireOwner(); return ledger; }
    public void onChange(Consumer<UUID> listener) { registry.requireOwner(); changed = Objects.requireNonNull(listener); }
    public void onCitizenChange(Consumer<UUID> listener) { registry.requireOwner(); citizenChanged = Objects.requireNonNull(listener); }
    public void onColonyChange(Consumer<UUID> listener) { registry.requireOwner(); colonyChanged = Objects.requireNonNull(listener); }
    public void onInvalidation(Consumer<UUID> listener) { registry.requireOwner(); invalidated = Objects.requireNonNull(listener); }
    public void onRetire(Consumer<UUID> listener) { registry.requireOwner(); retired = Objects.requireNonNull(listener); }
    public void onAge(ToLongFunction<WorkOrder> provider) { registry.requireOwner(); age = Objects.requireNonNull(provider); }
    public void onRemaining(ToLongFunction<WorkOrder> provider) { registry.requireOwner(); remaining = Objects.requireNonNull(provider); }
    public WorkOrder work(UUID id) { registry.requireOwner(); WorkOrder value = works.get(id); if (value == null) throw new IllegalArgumentException("Unknown work " + id); return value; }
    public Collection<WorkOrder> works() { registry.requireOwner(); return view; }
    public WorkOrder createTimer(UUID id, UUID colony, WorldPosition target, String profession, int priority, Lane lane, long duration) {
        registry.requireOwner();
        if (duration < 1 || duration > 1_000_000_000L) throw new IllegalArgumentException("Duration must be 1..1000000000");
        return create(id, colony, target, profession, priority, lane, WorkOrder.ACTIVE_WAIT, "timer", duration);
    }
    public WorkOrder createMove(UUID id, UUID colony, WorldPosition target, int priority, Lane lane) {
        return create(id, colony, target, null, priority, lane, WorkOrder.MOVE, "move", 0);
    }
    public WorkOrder createConstruction(UUID id, UUID colony, WorldPosition target, int priority, Lane lane) {
        return create(id,colony,target,"colonyloom:builder",priority,lane,WorkOrder.CONSTRUCTION,"construction",0);
    }
    public WorkOrder createDelivery(UUID id, UUID colony, WorldPosition target, int priority, Lane lane) {
        return create(id, colony, target, "colonyloom:courier", priority, lane, WorkOrder.DELIVERY, "delivery", 0);
    }
    private WorkOrder create(UUID id, UUID colony, WorldPosition target, String profession, int priority, Lane lane, String type, String stage, long duration) {
        registry.requireOwner();
        if (!registry.colony(colony).territory().contains(target) || works.containsKey(id) || registry.usedId(id)) throw new IllegalArgumentException("Invalid work identity or target");
        if (registry.colony(colony).recoveryBlocked() || registry.colony(colony).contentBlocked()) throw new IllegalStateException("Colony unavailable");
        WorkOrder value = WorkOrder.restore(new WorkOrder.Snapshot(type,id,colony,target,profession,priority,lane,WorkOrder.State.PLANNED,null,stage,0,List.of(),WorkOrder.Reason.NONE,duration,0));
        Lease lease = ledger.reserveRoot(colony,lane,ROOT);
        try { registry.beforeMutation(); } catch (RuntimeException failure) { lease.close(); throw failure; }
        works.put(id,value); leases.put(id,lease); changed.accept(id); return value;
    }
    public List<WorkOrder.Snapshot> snapshots() {
        registry.requireOwner(); ArrayList<WorkOrder.Snapshot> result = new ArrayList<>(works.size());
        for (WorkOrder value : works.values()) {
            result.add(value.snapshot(age.applyAsLong(value),remaining.applyAsLong(value)));
        }
        return List.copyOf(result);
    }
    public void restore(List<WorkOrder.Snapshot> snapshots) {
        registry.requireOwner();
        for (WorkOrder.Snapshot value : snapshots) {
            if (!registry.colony(value.colonyId()).territory().contains(value.target())) throw new IllegalArgumentException("Work outside territory");
            if (value.assignee() != null) {
                CitizenRecord citizen = registry.citizen(value.assignee());
                if (!citizen.colonyId().equals(value.colonyId()) || !value.id().equals(citizen.assignedWorkId())) throw new IllegalArgumentException("Work assignment mismatch");
            }
        }
        restoreValidated(snapshots,ledger);
    }
    /** Parent has validated all cross-aggregate references against its staged registry DTO. */
    public void restoreValidated(List<WorkOrder.Snapshot> snapshots, AdmissionLedger replacement) {
        registry.requireOwner(); Objects.requireNonNull(replacement);
        LinkedHashMap<UUID,WorkOrder> staged = new LinkedHashMap<>(); Map<UUID,Lease> admitted = new HashMap<>(); HashSet<UUID> assignees = new HashSet<>();
        for (WorkOrder.Snapshot value : snapshots) {
            if (staged.putIfAbsent(value.id(),WorkOrder.restore(value)) != null || value.assignee() != null && !assignees.add(value.assignee())) throw new IllegalArgumentException("Duplicate work or executor");
        }
        try {
            for (WorkOrder value : staged.values()) {
                WorkOrder old = works.get(value.id());
                if (replacement == ledger && old != null && old.colonyId().equals(value.colonyId()) && old.lane() == value.lane() && old.dependencies().equals(value.dependencies()) && old.terminal() == value.terminal()) admitted.put(value.id(),leases.get(value.id()));
                else admitted.put(value.id(),value.terminal() ? replacement.reserve(value.colonyId(),value.lane(),TERMINAL) : replacement.reserveRoot(value.colonyId(),value.lane(),value.dependencies().isEmpty() ? ROOT : Map.of(Resource.WORKS,1,Resource.WAIT_REGISTRATIONS,1+value.dependencies().size())));
            }
        } catch (RuntimeException failure) {
            for (Map.Entry<UUID,Lease> entry : admitted.entrySet()) if (entry.getValue() != leases.get(entry.getKey())) entry.getValue().close();
            throw failure;
        }
        for (Map.Entry<UUID,Lease> entry : leases.entrySet()) if (entry.getValue() != admitted.get(entry.getKey())) entry.getValue().close();
        works.clear(); works.putAll(staged); leases.clear(); leases.putAll(admitted); ledger = replacement;
    }
    public void cancel(UUID id) {
        WorkOrder value = work(id); if (value.terminal()) return;
        var delivery = registry.supply().deliveryForWork(id);
        if (delivery != null) {
            registry.supply().cancel(delivery.ownerDemandId());
            if (registry.supply().hasCargo(delivery.id())) {
                registry.supply().returnDelivery(delivery.id());
                if (value.assignee() != null) waitAssigned(id, WorkOrder.Reason.RECONCILING, "returning");
                else transition(id, WorkOrder.State.WAITING, WorkOrder.Reason.RECONCILING, "returning");
                return;
            }
        }
        registry.beforeMutation(); releaseAssignmentInternal(value); value.transition(WorkOrder.State.CANCELLED,WorkOrder.Reason.NONE,"cancelled"); leases.get(id).finishRoot(); changed.accept(id);
    }
    public void priority(UUID id, int priority) {
        if (priority < 0 || priority > 10) throw new IllegalArgumentException("Priority must be 0..10");
        WorkOrder value = work(id); if (value.terminal()) throw new IllegalStateException("Terminal work");
        if (value.priority() == priority) return; registry.beforeMutation(); value.priority(priority); changed.accept(id);
    }
    public void invalidate(UUID id) { WorkOrder value = work(id); if (value.terminal()) return; registry.beforeMutation(); value.invalidate(); changed.accept(id); invalidated.accept(id); }
    public void markCitizenChanged(UUID id) { registry.requireOwner(); citizenChanged.accept(id); }
    public void markColonyChanged(UUID id) { registry.requireOwner(); colonyChanged.accept(id); }
    public void transition(UUID id, WorkOrder.State state, WorkOrder.Reason reason, String stage) {
        WorkOrder value = work(id);
        if (value.state() == state && value.waitingReason() == reason && value.stage().equals(stage)) return;
        if (state == WorkOrder.State.CANCELLED && registry.supply().deliveryForWork(id) != null
                && registry.supply().hasCargo(registry.supply().deliveryForWork(id).id())) { cancel(id); return; }
        if ((state == WorkOrder.State.COMPLETED || state == WorkOrder.State.FAILED)
                && registry.supply().deliveryForWork(id) != null && registry.supply().hasCargo(registry.supply().deliveryForWork(id).id()))
            throw new IllegalStateException("Delivery work retains physical cargo");
        registry.beforeMutation();
        if (state == WorkOrder.State.READY || state == WorkOrder.State.WAITING || state == WorkOrder.State.COMPLETED || state == WorkOrder.State.CANCELLED || state == WorkOrder.State.FAILED) releaseAssignmentInternal(value);
        value.transition(state,reason,stage); if (value.terminal()) leases.get(id).finishRoot(); changed.accept(id);
    }
    /** Physical waits retain their exact worker/cargo rather than silently choosing a substitute. */
    public void waitAssigned(UUID id, WorkOrder.Reason reason, String stage) {
        WorkOrder value = work(id);
        if (value.assignee() == null || value.terminal() || reason == WorkOrder.Reason.NONE) throw new IllegalStateException("Assigned wait requires a live executor and reason");
        if (value.state() == WorkOrder.State.WAITING && value.waitingReason() == reason && value.stage().equals(stage)) return;
        registry.beforeMutation(); value.transition(WorkOrder.State.WAITING, reason, stage); changed.accept(id);
    }
    public void resumeAssigned(UUID id, String stage) {
        WorkOrder value = work(id);
        if (value.state() != WorkOrder.State.WAITING || value.assignee() == null) throw new IllegalStateException("No waiting executor");
        registry.beforeMutation(); value.transition(WorkOrder.State.READY, WorkOrder.Reason.NONE, stage);
        value.transition(WorkOrder.State.ASSIGNED, WorkOrder.Reason.NONE, stage);
        value.transition(WorkOrder.State.RUNNING, WorkOrder.Reason.NONE, stage); changed.accept(id);
    }
    public boolean assign(UUID id, UUID citizenId) {
        WorkOrder value = work(id); CitizenRecord citizen = registry.citizen(citizenId);
        var delivery = registry.supply().deliveryForWork(id);
        if (delivery != null && registry.supply().hasCargo(delivery.id()) && !citizenId.equals(delivery.citizenId())) return false;
        if (value.state() != WorkOrder.State.READY || citizen.assignedWorkId() != null || !citizen.colonyId().equals(value.colonyId()) || citizen.lifecycle() != CitizenRecord.Lifecycle.ALIVE || citizen.admission() != CitizenRecord.Admission.ACTIVE || citizen.readiness() != CitizenRecord.Readiness.READY) return false;
        if (value.professionId() != null && !value.professionId().equals(citizen.professionId())) return false;
        registry.beforeMutation(); value.assignment(citizenId); value.transition(WorkOrder.State.ASSIGNED,WorkOrder.Reason.NONE,value.stage());
        registry.updateCitizen(citizen.withAssignment(id)); changed.accept(id); return true;
    }
    public void releaseAssignment(UUID id) {
        WorkOrder value = work(id); if (value.assignee() == null) return;
        transition(id,WorkOrder.State.WAITING,WorkOrder.Reason.RECONCILING,value.stage());
    }
    /** Called by registry before replacing a citizen that clears its old assignment. No recursive registry write. */
    public void citizenDetached(UUID citizenId) {
        CitizenRecord citizen = registry.citizen(citizenId);
        if (citizen.assignedWorkId() == null) return;
        WorkOrder value = work(citizen.assignedWorkId());
        if (!citizenId.equals(value.assignee())) return;
        registry.beforeMutation();
        value.assignment(null); value.transition(WorkOrder.State.WAITING,WorkOrder.Reason.RECONCILING,value.stage()); changed.accept(value.id());
    }
    private void releaseAssignmentInternal(WorkOrder value) {
        UUID assignee = value.assignee(); if (assignee == null) return;
        CitizenRecord citizen = registry.citizen(assignee); value.assignment(null);
        if (value.id().equals(citizen.assignedWorkId())) registry.updateCitizen(citizen.withAssignment(null));
    }
    public WorkOrder.StepResult progressTimer(UUID id, long elapsed) {
        WorkOrder value = work(id);
        if (!WorkOrder.ACTIVE_WAIT.equals(value.typeId())) throw new IllegalArgumentException("Unknown executor");
        if (value.state() != WorkOrder.State.RUNNING || elapsed < 0) throw new IllegalStateException("Timer not running");
        if (elapsed > 0 && value.remainingActiveTicks() > 0) { registry.beforeMutation(); value.progress(Math.min(elapsed,value.remainingActiveTicks())); changed.accept(id); }
        if (value.remainingActiveTicks() == 0) { transition(id,WorkOrder.State.COMPLETED,WorkOrder.Reason.NONE,"completed"); return WorkOrder.StepResult.COMPLETED; }
        return WorkOrder.StepResult.PROGRESS;
    }
    public void acknowledge(UUID id, long revision) { work(id).acknowledge(revision); }
    /** Concrete obligations must have been checkpoint-compacted before their terminal work. */
    public void retire(UUID id) {
        WorkOrder value = work(id);
        if (!value.terminal() || !(WorkOrder.ACTIVE_WAIT.equals(value.typeId()) || WorkOrder.MOVE.equals(value.typeId())
                || WorkOrder.CONSTRUCTION.equals(value.typeId()) && registry.construction().site(id)==null
                || WorkOrder.DELIVERY.equals(value.typeId()) && !registry.supply().hasDeliveryWork(id))) throw new IllegalStateException("Work has retained obligations");
        for (var effect : registry.effects().snapshots()) if (id.equals(effect.workId())) throw new IllegalStateException("Work has retained witness");
        for (var citizen : registry.citizensView()) if (id.equals(citizen.assignedWorkId())) throw new IllegalStateException("Work has retained assignment");
        for (var claim : registry.targetClaims().snapshots()) if (id.equals(claim.ownerId())) throw new IllegalStateException("Work has retained target");
        for (WorkOrder other : works.values()) if (other.dependencies().contains(id)) throw new IllegalStateException("Work has dependents");
        registry.beforeMutation(); works.remove(id); leases.remove(id).close(); retired.accept(id);
    }
}
