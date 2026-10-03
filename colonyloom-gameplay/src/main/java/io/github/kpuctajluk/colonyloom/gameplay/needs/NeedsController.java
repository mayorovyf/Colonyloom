package io.github.kpuctajluk.colonyloom.gameplay.needs;

import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.supply.Demand;
import io.github.kpuctajluk.colonyloom.core.supply.ItemMatcher;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Food clocks belong to CitizenRecord; this controller owns only admitted critical consumers. */
public final class NeedsController {
    @FunctionalInterface public interface Port {
        /** Return true only once current physical cargo has reached safe storage. */
        boolean requestPreemption(CitizenRecord citizen);
    }
    private final ColonyRegistry registry;
    private final Port port;
    private final Map<UUID, UUID> works = new HashMap<>();
    private final Map<UUID, WorkOrder.Reason> blocked = new HashMap<>();
    public NeedsController(ColonyRegistry registry, Port port) {
        this.registry = Objects.requireNonNull(registry); this.port = Objects.requireNonNull(port);
        rebuild();
    }
    public void rebuild() {
        registry.requireOwner(); works.clear(); blocked.clear();
        for (var work : registry.workBoard().works()) if (WorkOrder.FOOD.equals(work.typeId()) && !work.terminal()) {
            if (works.putIfAbsent(work.subjectId(), work.id()) != null) throw new IllegalArgumentException("Duplicate live food consumer");
        }
    }
    public WorkOrder workForCitizen(UUID citizenId) {
        registry.requireOwner(); UUID id = works.get(citizenId);
        return id == null ? null : registry.workBoard().work(id);
    }
    public CitizenRecord subject(UUID workId) {
        WorkOrder work = registry.workBoard().work(workId);
        if (!WorkOrder.FOOD.equals(work.typeId())) throw new IllegalArgumentException("Not food work");
        return registry.citizen(work.subjectId());
    }
    public Demand demandForWork(UUID workId) {
        registry.requireOwner();
        for (var demand : registry.supply().demands()) if (demand.snapshot().ownerId().equals(workId)) return demand;
        return null;
    }
    public WorkOrder.Reason reason(UUID citizenId) {
        registry.requireOwner();
        if (registry.admission().normalAdmissionBlocked()) return WorkOrder.Reason.CRITICAL_CAPACITY;
        for (var delivery : registry.supply().deliveries()) if (delivery.workId() != null && !delivery.terminal()) {
            WorkOrder route = registry.workBoard().work(delivery.workId());
            if (citizenId.equals(route.subjectId()) && route.waitingReason() != WorkOrder.Reason.NONE) return route.waitingReason();
        }
        if (blocked.containsKey(citizenId)) return blocked.get(citizenId);
        WorkOrder work = workForCitizen(citizenId);
        return work == null ? WorkOrder.Reason.NONE : work.waitingReason();
    }
    public void tick(long tick) {
        registry.requireOwner(); if (tick < 0) throw new IllegalArgumentException("Negative tick");
        for (CitizenRecord initial : registry.citizensView()) {
            if (initial.lifecycle() != CitizenRecord.Lifecycle.ALIVE) {
                WorkOrder old = workForCitizen(initial.citizenId());
                if (old != null && !old.terminal()) { var demand = demandForWork(old.id()); if (demand != null) registry.supply().cancel(demand.id()); registry.workBoard().cancel(old.id()); }
                works.remove(initial.citizenId()); blocked.remove(initial.citizenId()); continue;
            }
            if (initial.admission() != CitizenRecord.Admission.ACTIVE || initial.readiness() != CitizenRecord.Readiness.READY
                    || !registry.colony(initial.colonyId()).available() || initial.food() > 6) continue;
            WorkOrder work = workForCitizen(initial.citizenId());
            if (work != null && work.terminal()) { works.remove(initial.citizenId()); work = null; }
            try {
                boolean safe = true;
                CitizenRecord current = registry.citizen(initial.citizenId());
                if (current.assignedWorkId() != null && registry.workBoard().work(current.assignedWorkId()).subjectId() == null)
                    safe = port.requestPreemption(current);
                if (work == null) {
                    work = registry.workBoard().createFood(UUID.randomUUID(), initial.citizenId());
                    works.put(initial.citizenId(), work.id());
                }
                Demand demand = demandForWork(work.id());
                if (demand == null) demand = registry.supply().request(demandId(work.id()), initial.colonyId(), work.id(),
                        new ItemMatcher("minecraft:bread", null), 1, Demand.GoalKind.CONSUMPTION, work.target(), AdmissionLedger.Lane.CRITICAL, 10, tick);
                blocked.remove(initial.citizenId());
                if (!safe) continue;
                if (demand.snapshot().allocated() == 0) {
                    if (work.assignee() == null) registry.workBoard().transition(work.id(), WorkOrder.State.WAITING,
                            registry.admission().normalAdmissionBlocked() ? WorkOrder.Reason.CRITICAL_CAPACITY : WorkOrder.Reason.MATERIALS, "food");
                } else registry.workBoard().invalidate(work.id());
            } catch (AdmissionLedger.AdmissionException full) {
                blocked.put(initial.citizenId(), WorkOrder.Reason.CRITICAL_CAPACITY);
                if (work != null && !work.terminal() && work.assignee() == null) registry.workBoard().transition(work.id(),
                        WorkOrder.State.WAITING, WorkOrder.Reason.CRITICAL_CAPACITY, "food");
            }
        }
    }
    private static UUID demandId(UUID workId) {
        return UUID.nameUUIDFromBytes((workId + ":food").getBytes(StandardCharsets.UTF_8));
    }
    /** Called only after one observed physical bread consumption has fulfilled the exact demand. */
    public void consumed(UUID workId) {
        WorkOrder work = registry.workBoard().work(workId); CitizenRecord citizen = subject(workId);
        Demand demand = demandForWork(workId);
        if (work.terminal() || !citizen.citizenId().equals(work.assignee()) || citizen.lifecycle() != CitizenRecord.Lifecycle.ALIVE
                || demand == null || demand.snapshot().fulfilled() != 1 || demand.snapshot().status() != Demand.Status.COMPLETED)
            throw new IllegalStateException("Food requires its bound citizen and one confirmed consumption");
        registry.updateCitizen(citizen.withFood(Math.min(20, citizen.food() + 5)));
        registry.workBoard().transition(workId, WorkOrder.State.COMPLETED, WorkOrder.Reason.NONE, "consumed");
        works.remove(citizen.citizenId()); blocked.remove(citizen.citizenId());
    }
}
