package io.github.kpuctajluk.colonyloom.core.scheduler;

import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lease;
import io.github.kpuctajluk.colonyloom.core.work.WorkBoard;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Owner-thread event scheduler. A root owns one node, one deadline and one coalesced dirty link. */
public final class SimulationScheduler {
    private static final Map<Resource,Integer> READY_COST = Map.of(Resource.READY_ENTRIES,1);
    private final WorkBoard board;
    private final GlobalWorkBudgets budgets;
    private final ArrayList<Lease> freeReady = new ArrayList<>();
    private final WakeupIndex deadlines = new WakeupIndex();
    private final Map<UUID,Node> nodes = new HashMap<>();
    private final Map<UUID,ColonyQueue> colonyIndex = new LinkedHashMap<>();
    private final ArrayList<ColonyQueue> colonies = new ArrayList<>();
    private final Map<UUID,WorkerIndex> workers = new HashMap<>();
    private final Map<UUID,WorkerBucket> citizenBuckets = new HashMap<>();
    private final Map<UUID,Node> assignments = new HashMap<>();
    private final Map<UUID,ArrayList<Node>> dependents = new HashMap<>();
    private int eventCursor;
    private int compare(Node a, Node b) {
        int result = Integer.compare(b.score,a.score);
        if (result == 0) result = Long.compare(a.birthClock,b.birthClock);
        return result != 0 ? result : a.work.id().compareTo(b.work.id());
    }
    private Node dirtyHead, dirtyTail;
    private long tick = -1, clock;
    private final int[] colonyCursor = new int[Lane.values().length];
    private final boolean[] available = new boolean[Lane.values().length];
    private final boolean[] candidateAvailable = new boolean[Lane.values().length];
    private final Lane[] lanes = Lane.values();
    private Node executing;
    private Runnable beforeStep = () -> {};
    public interface PhysicalExecutor {
        void step(WorkOrder work, long tick);
        void cancel(UUID workId);
    }
    private final Map<String,PhysicalExecutor> physical = new HashMap<>();
    public void physicalExecutor(String type,PhysicalExecutor executor) {
        board.registry().requireOwner(); Objects.requireNonNull(executor); io.github.kpuctajluk.colonyloom.core.citizen.ProfessionDefinition.validateId(type);
        if (WorkOrder.ACTIVE_WAIT.equals(type) || physical.putIfAbsent(type,executor)!=null) throw new IllegalArgumentException("Physical executor already registered or reserved type");
    }
    private void cancelPhysical(WorkOrder work) { var executor=physical.get(work.typeId()); if(executor!=null) executor.cancel(work.id()); }
    private java.util.function.LongConsumer beforeWork = tick -> {};
    public void beforeWork(java.util.function.LongConsumer hook) { board.registry().requireOwner(); beforeWork = Objects.requireNonNull(hook); }

    private final class ColonyQueue {
        final UUID id;
        final ArrayList<Node>[] queues;
        long activeClock;
        boolean active;
        long serviceCount, lastServiceTick = -1, maxServiceGap;
        long maxReadyServiceDelay, maxWorkAge;
        long normalReadySince = -1, maxNormalColonyServiceDelay;
        final long[] deficit = new long[lanes.length];
        final ArrayList<Node> roots = new ArrayList<>();
        int wakeCursor;
        boolean wakePending;
        @SuppressWarnings("unchecked") ColonyQueue(UUID id) {
            this.id = id; queues = (ArrayList<Node>[]) new ArrayList<?>[lanes.length];
            for (int i=0;i<queues.length;i++) queues[i] = new ArrayList<>();
            ColonyRuntime value = board.registry().colony(id); active = !value.recoveryBlocked() && !value.contentBlocked();
        }
    }
    private static final class WorkerBucket {
        final ArrayList<UUID> ids = new ArrayList<>();
        int cursor;
    }
    private static final class WorkerIndex {
        final WorkerBucket any = new WorkerBucket();
        final Map<String,WorkerBucket> professions = new HashMap<>();
    }
    private final class Node {
        final WorkOrder work;
        final WakeupIndex.Ticket due;
        final ColonyQueue colony;
        final long birthClock;
        long queuedAt;
        long activeSince = -1, accrued, waitRevision = -1;
        int score, queueIndex = -1;
        boolean queued, linked, changedWhileExecuting;
        boolean externallyInvalidated;
        Lease readyLease;
        UUID assignedCitizen;
        Node previous, next;
        Node(WorkOrder work, ColonyQueue colony) {
            this.work = work; this.colony = colony; due = new WakeupIndex.Ticket(work.id());
            birthClock = clock - work.ageActiveTicks();
        }
    }
    public SimulationScheduler(WorkBoard board, GlobalWorkBudgets budgets) {
        this.board = Objects.requireNonNull(board); this.budgets = Objects.requireNonNull(budgets);
        board.onChange(this::changed); board.onCitizenChange(this::citizenChanged); board.onColonyChange(this::colonyChanged);
        board.onInvalidation(id -> { Node node = nodes.get(id); if (node != null) { node.externallyInvalidated = true; link(node); } });
        board.onAge(this::age); board.onRemaining(this::remaining); board.onRetire(this::retired); rebuild();
    }
    /** Optional scoped instrumentation: production default allocates no per-step observer objects. */
    public void beforeStep(Runnable hook) { board.registry().requireOwner(); beforeStep = Objects.requireNonNull(hook); }
    public void rebuild() {
        board.registry().requireOwner();
        for (Node node : nodes.values()) cancelPhysical(node.work);
        for (Node node : nodes.values()) if (node.readyLease != null) node.readyLease.close();
        for (int i=0;i<freeReady.size();i++) freeReady.get(i).close();
        freeReady.clear();
        deadlines.clear(); nodes.clear(); colonyIndex.clear(); colonies.clear(); workers.clear(); citizenBuckets.clear(); assignments.clear(); dependents.clear();
        dirtyHead = dirtyTail = null;
        eventCursor = 0;
        for (int i=0;i<colonyCursor.length;i++) colonyCursor[i] = 0;
        for (CitizenRecord citizen : board.registry().citizensView()) indexCitizen(citizen);
        for (WorkOrder work : board.works()) add(work);
    }
    public void limitsUpdated() {
        board.registry().requireOwner();
        for (int i=0;i<freeReady.size();i++) freeReady.get(i).close(); freeReady.clear();
        for (Node node : nodes.values()) if (!node.work.terminal()) link(node);
    }
    public void invalidate(UUID work) { board.invalidate(work); }
    public void tick(long monotonicTick) {
        board.registry().requireOwner();
        if (monotonicTick <= tick) throw new IllegalArgumentException("Scheduler tick must increase");
        tick = monotonicTick; clock = Math.incrementExact(clock); budgets.beginTick(monotonicTick);
        long managedStart = System.nanoTime();
        try {
        // A calibrated one-unit quota cannot be monopolized by platform hooks before dirty debt.
        if ((monotonicTick & 1) == 0) {
            for (int phase=0;phase<3;phase++) {
                int selected=(int)((monotonicTick/2+phase)%3);
                if (selected==0 ? rescanOne() : selected==1 ? fanoutOne() : wakeDueOne()) break;
            }
        }
        beforeWork.accept(monotonicTick);
        for (int i=0;i<colonies.size();i++) { ColonyQueue queue = colonies.get(i); if (queue.active) queue.activeClock++; }
        // Dirty debt gets the first managed portion, even when every ready entry is occupied.
        rescanOne();
        fanoutOne();
        while (budgets.timeAvailable()) {
            if (!wakeDueOne()) break;
        }
        boolean mayRescan = true;
        while (budgets.timeAvailable()) {
            boolean serviced = serviceOne();
            boolean rescanned = mayRescan && rescanOne();
            if (rescanned) fanoutOne();
            if (!rescanned) mayRescan = false;
            if (!serviced && !rescanned) break;
        }
        } finally { board.registry().metrics().record(Timer.MANAGED_TICK, System.nanoTime() - managedStart); }
    }
    private boolean wakeDueOne() {
        WakeupIndex.Ticket due=deadlines.peek();
        if (due==null || due.dueTick()>tick || !budgets.tryConsume(Budget.DIRTY_RESCAN_OBJECTS,Lane.SERVICE)) return false;
        deadlines.pollDue(tick);Node node=nodes.get(due.workId());if(node!=null) link(node);
        return true;
    }
    private ColonyQueue colony(UUID id) {
        ColonyQueue queue = colonyIndex.get(id);
        if (queue == null) { queue = new ColonyQueue(id); colonyIndex.put(id,queue); colonies.add(queue); }
        return queue;
    }
    private void add(WorkOrder work) {
        Node node = new Node(work,colony(work.colonyId())); nodes.put(work.id(),node); node.colony.roots.add(node);
        deadlines.ensureCapacity(nodes.size()); freeReady.ensureCapacity(nodes.size());
        for (int i=0;i<node.colony.queues.length;i++) node.colony.queues[i].ensureCapacity(node.colony.roots.size());
        for (int i=0;i<work.dependencies().size();i++) dependents.computeIfAbsent(work.dependencies().get(i),ignored -> new ArrayList<>()).add(node);
        if (work.assignee() != null) { assignments.put(work.assignee(),node); node.assignedCitizen = work.assignee(); }
        if (!work.terminal()) link(node);
    }
    private void changed(UUID id) {
        Node node = nodes.get(id);
        if (node == null) {
            // Only create/retire events use this bounded live lookup, never a tick scan.
            WorkOrder work;
            try { work = board.work(id); } catch (IllegalArgumentException absent) { return; }
            add(work); return;
        }
        if (node.work.terminal()) cancelPhysical(node.work);
        if (node == executing) { node.changedWhileExecuting = true; return; }
        if (node.work.terminal()) {
            unqueue(node); unlink(node); deadlines.remove(node.due); pause(node); removeAssignment(node);
            ArrayList<Node> waiting = dependents.get(id);
            if (waiting != null) wakeDependents(id);
        }
        else { if (!eligible(node)) pause(node); link(node); }
        if (node.assignedCitizen != null && !node.assignedCitizen.equals(node.work.assignee())) cancelPhysical(node.work);
    }
    private void citizenChanged(UUID id) {
        CitizenRecord citizen = board.registry().citizen(id);
        unindexCitizen(id); indexCitizen(citizen);
        Node assigned = assignments.get(id);
        if (assigned != null && assigned != executing) { if (!eligible(assigned)) pause(assigned); link(assigned); }
        if (assigned != null && !eligible(assigned)) cancelPhysical(assigned.work);
        ColonyQueue colony = colonyIndex.get(citizen.colonyId());
        if (colony != null) { if (!colony.wakePending) colony.wakeCursor = 0; colony.wakePending = true; }
    }
    private void colonyChanged(UUID id) {
        ColonyQueue queue = colonyIndex.get(id);
        if (queue == null) return;
        ColonyRuntime value = board.registry().colony(id); queue.active = !value.recoveryBlocked() && !value.contentBlocked();
        if (!queue.wakePending) queue.wakeCursor = 0; queue.wakePending = true;
    }
    private boolean fanoutOne() {
        if (colonies.isEmpty()) return false;
        for (int checked=0;checked<colonies.size();checked++) {
            ColonyQueue queue = colonies.get(eventCursor); eventCursor = (eventCursor+1)%colonies.size();
            if (!queue.wakePending) continue;
            if (!budgets.tryConsume(Budget.DIRTY_RESCAN_OBJECTS,Lane.SERVICE)) return false;
            if (queue.wakeCursor < queue.roots.size()) {
                Node node = queue.roots.get(queue.wakeCursor++);
                if (!node.work.terminal() && node.work.state() == State.WAITING) { if (!eligible(node)) pause(node); link(node); }
            }
            if (queue.wakeCursor >= queue.roots.size()) queue.wakePending = false;
            return true;
        }
        return false;
    }
    private void retired(UUID id) {
        Node node = nodes.remove(id); if (node == null) return;
        cancelPhysical(node.work);
        unqueue(node); unlink(node); deadlines.remove(node.due); removeAssignment(node);
        node.colony.roots.remove(node);
        for (int i=0;i<node.work.dependencies().size();i++) {
            ArrayList<Node> waiting = dependents.get(node.work.dependencies().get(i));
            if (waiting != null) { waiting.remove(node); if (waiting.isEmpty()) dependents.remove(node.work.dependencies().get(i)); }
        }
        dependents.remove(id);
    }
    private void wakeDependents(UUID id) {
        ArrayList<Node> waiting = dependents.get(id);
        if (waiting == null) return;
        for (int i=0;i<colonies.size();i++) {
            ColonyQueue colony = colonies.get(i);
            if (!colony.wakePending) colony.wakeCursor = 0; colony.wakePending = true;
        }
    }
    private void indexCitizen(CitizenRecord citizen) {
        if (!workerAvailable(citizen)) return;
        WorkerIndex index = workers.computeIfAbsent(citizen.colonyId(),ignored -> new WorkerIndex());
        index.any.ids.add(citizen.citizenId());
        WorkerBucket bucket = citizen.professionId() == null ? index.any : index.professions.computeIfAbsent(citizen.professionId(),ignored -> new WorkerBucket());
        if (bucket != index.any) bucket.ids.add(citizen.citizenId());
        citizenBuckets.put(citizen.citizenId(),bucket);
    }
    private void unindexCitizen(UUID id) {
        WorkerBucket bucket = citizenBuckets.remove(id); if (bucket == null) return;
        bucket.ids.remove(id); if (bucket.cursor >= bucket.ids.size()) bucket.cursor = 0;
        CitizenRecord citizen = board.registry().citizen(id);
        WorkerIndex index = workers.get(citizen.colonyId()); if (index != null && bucket != index.any) { index.any.ids.remove(id); if (index.any.cursor >= index.any.ids.size()) index.any.cursor = 0; }
    }
    private static boolean workerAvailable(CitizenRecord citizen) {
        return citizen.lifecycle() == CitizenRecord.Lifecycle.ALIVE && citizen.admission() == CitizenRecord.Admission.ACTIVE && citizen.readiness() == CitizenRecord.Readiness.READY && citizen.assignedWorkId() == null;
    }
    private boolean eligible(Node node) {
        ColonyRuntime colony = board.registry().colony(node.work.colonyId());
        if (colony.recoveryBlocked() || colony.contentBlocked() || node.work.assignee() == null) return false;
        CitizenRecord citizen = board.registry().citizen(node.work.assignee());
        return citizen.lifecycle() == CitizenRecord.Lifecycle.ALIVE && citizen.admission() == CitizenRecord.Admission.ACTIVE && citizen.readiness() == CitizenRecord.Readiness.READY && node.work.id().equals(citizen.assignedWorkId())
            && (node.work.professionId() == null || node.work.professionId().equals(citizen.professionId()));
    }
    private void pause(Node node) { if (node.activeSince >= 0) { node.accrued += node.colony.activeClock-node.activeSince; node.activeSince = -1; } }
    private long age(WorkOrder work) {
        Node node = nodes.get(work.id());
        return node == null ? work.ageActiveTicks() : Math.max(0,clock-node.birthClock);
    }
    private long remaining(WorkOrder work) {
        Node node = nodes.get(work.id());
        long elapsed = node == null ? 0 : node.accrued + (node.activeSince < 0 ? 0 : node.colony.activeClock-node.activeSince);
        return Math.max(0,work.remainingActiveTicks()-elapsed);
    }
    private void link(Node node) {
        if (node.linked || node.work.terminal()) return;
        node.linked = true; node.previous = dirtyTail; node.next = null;
        if (dirtyTail == null) dirtyHead = node; else dirtyTail.next = node;
        dirtyTail = node;
    }
    private void unlink(Node node) {
        if (!node.linked) return;
        if (node.previous == null) dirtyHead = node.next; else node.previous.next = node.next;
        if (node.next == null) dirtyTail = node.previous; else node.next.previous = node.previous;
        node.previous = node.next = null; node.linked = false;
    }
    private boolean rescanOne() {
        Node node = dirtyHead;
        if (node == null || !budgets.tryConsume(Budget.DIRTY_RESCAN_OBJECTS,Lane.SERVICE)) return false;
        long start = System.nanoTime();
        try {
        unlink(node); long revision = node.work.revision();
        if (node.work.terminal()) { unqueue(node); deadlines.remove(node.due); removeAssignment(node); board.acknowledge(node.work.id(),revision); return true; }
        unqueue(node);
        if (!enqueue(node)) link(node);
        // Admission is not processing: acknowledge only after the step.
        return true;
        } finally { board.registry().metrics().record(Timer.DIRTY_RESCAN_UNIT, System.nanoTime() - start); }
    }
    private boolean enqueue(Node node) {
        for (int i=0;i<freeReady.size();i++) {
            Lease lease = freeReady.get(i);
            if (lease.transfer(node.work.colonyId(),node.work.lane())) {
                node.readyLease = lease; freeReady.remove(i); break;
            }
        }
        if (node.readyLease == null) {
            if (!board.ledger().canReserve(node.work.colonyId(),node.work.lane(),READY_COST)) return false;
            node.readyLease = board.ledger().reserve(node.work.colonyId(),node.work.lane(),READY_COST);
        }
        node.score = node.work.priority() + (node.work.lane() == Lane.NORMAL ? (int)Math.min(10,age(node.work)/200) : 0);
        ArrayList<Node> queue = node.colony.queues[node.work.lane().ordinal()];
        if (node.work.lane()==Lane.NORMAL && queue.isEmpty()) node.colony.normalReadySince=tick;
        node.queueIndex = queue.size(); queue.add(node); node.queued = true; queueUp(queue,node.queueIndex);
        node.queuedAt=tick;
        if (!node.due.scheduled()) deadlines.schedule(node.due,tick+200);
        return true;
    }
    private void queueUp(ArrayList<Node> queue, int index) {
        Node value = queue.get(index);
        while (index > 0) { int parent = (index-1)/2; Node above = queue.get(parent); if (compare(value,above) >= 0) break; queue.set(index,above); above.queueIndex = index; index = parent; }
        queue.set(index,value); value.queueIndex = index;
    }
    private void queueDown(ArrayList<Node> queue, int index) {
        Node value = queue.get(index);
        while (index*2+1 < queue.size()) { int child = index*2+1; if (child+1 < queue.size() && compare(queue.get(child+1),queue.get(child)) < 0) child++; Node below = queue.get(child); if (compare(value,below) <= 0) break; queue.set(index,below); below.queueIndex = index; index = child; }
        queue.set(index,value); value.queueIndex = index;
    }
    private void unqueue(Node node) {
        if (node.queued) {
            ArrayList<Node> queue = node.colony.queues[node.work.lane().ordinal()]; int index = node.queueIndex;
            Node last = queue.remove(queue.size()-1);
            if (index < queue.size()) { queue.set(index,last); last.queueIndex = index; if (index > 0 && compare(last,queue.get((index-1)/2)) < 0) queueUp(queue,index); else queueDown(queue,index); }
            node.queued = false; node.queueIndex = -1;
            if (node.work.lane()==Lane.NORMAL && queue.isEmpty()) node.colony.normalReadySince=-1;
        }
        if (node.readyLease != null) { freeReady.add(node.readyLease); node.readyLease = null; }
    }
    private boolean serviceOne() {
        for (int i=0;i<available.length;i++) {
            available[i] = false;
            for (int c=0;c<colonies.size();c++) if (!colonies.get(c).queues[i].isEmpty()) { available[i] = true; break; }
        }
        Lane lane = budgets.chooseLane(Budget.PHYSICAL_ACTIONS,available[Lane.CRITICAL.ordinal()],available[Lane.SERVICE.ordinal()],available[Lane.NORMAL.ordinal()]);
        if (lane == null || !budgets.tryConsume(Budget.PHYSICAL_ACTIONS,lane)) return false;
        int ordinal = lane.ordinal(); Node node = null;
        for (int checked=0;checked<colonies.size();checked++) {
            int c = colonyCursor[ordinal]; colonyCursor[ordinal] = (c+1) % colonies.size(); ColonyQueue colony = colonies.get(c);
            if (colony.queues[ordinal].isEmpty()) continue;
            colony.deficit[ordinal]++;
            if (colony.deficit[ordinal] >= 1) { colony.deficit[ordinal]--; node = colony.queues[ordinal].get(0); break; }
        }
        if (node == null) return false;
        if(node.work.lane()==Lane.NORMAL) node.colony.maxReadyServiceDelay=Math.max(node.colony.maxReadyServiceDelay,tick-node.queuedAt);
        if (lane==Lane.NORMAL && node.colony.normalReadySince>=0) {
            node.colony.maxNormalColonyServiceDelay=Math.max(node.colony.maxNormalColonyServiceDelay,tick-node.colony.normalReadySince);
            node.colony.normalReadySince=tick;
        }
        node.colony.maxWorkAge=Math.max(node.colony.maxWorkAge,age(node.work));
        unqueue(node); deadlines.remove(node.due); process(node); return true;
    }
    private void process(Node node) {
        WorkOrder work = node.work; long revision = work.revision(); executing = node; node.changedWhileExecuting = false; node.externallyInvalidated = false;
        try {
            beforeStep.run();
            if (work.revision() != revision) { link(node); return; }
            if (work.terminal()) return;
            ColonyRuntime colony = board.registry().colony(work.colonyId());
            Reason blocked = colony.recoveryBlocked() ? Reason.RECOVERY_AMBIGUOUS : colony.contentBlocked() || !(WorkOrder.ACTIVE_WAIT.equals(work.typeId()) || physical.containsKey(work.typeId())) ? Reason.CONTENT_UNAVAILABLE : Reason.NONE;
            if (blocked != Reason.NONE) { park(node,blocked); return; }
            for (UUID dependency : work.dependencies()) {
                WorkOrder required = board.work(dependency);
                if (required.state() != State.COMPLETED) { park(node,Reason.RECONCILING); return; }
            }
            if (work.assignee() != null && !eligible(node)) { pause(node); removeAssignment(node); board.transition(work.id(),State.WAITING,Reason.RECONCILING,work.stage()); park(node,Reason.RECONCILING); return; }
            if (work.state() == State.WAITING && work.assignee() != null && physical.containsKey(work.typeId())) board.resumeAssigned(work.id(),work.stage());
            if (work.state() == State.PLANNED || work.state() == State.WAITING) board.transition(work.id(),State.READY,Reason.NONE,work.stage());
            if (work.state() == State.READY) {
                UUID winner = candidate(work);
                if (winner == null) { park(node,assignmentBudgetDenied ? Reason.BUDGET : Reason.WORKER); return; }
                if (!board.assign(work.id(),winner)) { link(node); return; }
                assignments.put(winner,node); node.assignedCitizen = winner;
            }
            if (work.state() == State.ASSIGNED) { board.transition(work.id(),State.RUNNING,Reason.NONE,work.stage()); node.activeSince = WorkOrder.ACTIVE_WAIT.equals(work.typeId()) ? node.colony.activeClock : -1; }
            if (physical.containsKey(work.typeId()) && work.state() == State.RUNNING) {
                physical.get(work.typeId()).step(work, tick);
                serviced(node);
                if (!work.terminal()) deadlines.schedule(node.due, tick + 1);
                else { removeAssignment(node); wakeDependents(work.id()); }
                return;
            }
            if (work.state() == State.RUNNING) {
                if (node.activeSince < 0) node.activeSince = node.colony.activeClock;
                long elapsed = node.accrued + node.colony.activeClock-node.activeSince;
                node.accrued = 0; node.activeSince = node.colony.activeClock;
                board.progressTimer(work.id(),elapsed);
                serviced(node);
                if (work.terminal()) { pause(node); removeAssignment(node); deadlines.remove(node.due); wakeDependents(work.id()); }
                else deadlines.schedule(node.due,tick+1);
            }
        } finally {
            executing = null;
            if (node.externallyInvalidated || work.revision() != revision && node.changedWhileExecuting && node.linked) {
                link(node); board.acknowledge(work.id(),revision);
            } else board.acknowledge(work.id(),work.revision());
            if (work.terminal()) { unqueue(node); unlink(node); }
        }
    }
    private boolean assignmentBudgetDenied;
    private UUID candidate(WorkOrder work) {
        assignmentBudgetDenied = false;
        WorkerIndex index = workers.get(work.colonyId()); if (index == null) return null;
        WorkerBucket bucket = work.professionId() == null ? index.any : index.professions.get(work.professionId());
        if (bucket == null || bucket.ids.isEmpty()) return null;
        for (int lane=0;lane<lanes.length;lane++) {
            candidateAvailable[lane] = work.lane().ordinal() == lane;
            for (int c=0;c<colonies.size() && !candidateAvailable[lane];c++) {
                ArrayList<Node> queue = colonies.get(c).queues[lane];
                if (!queue.isEmpty()) { State state = queue.get(0).work.state(); candidateAvailable[lane] = state == State.READY || state == State.PLANNED || state == State.WAITING; }
            }
        }
        UUID winner = null; long bestDistance = Long.MAX_VALUE;
        int count = Math.min(8,bucket.ids.size());
        for (int i=0;i<count;i++) {
            Lane selected = budgets.chooseLane(Budget.ASSIGNMENT_CANDIDATES,candidateAvailable[Lane.CRITICAL.ordinal()],candidateAvailable[Lane.SERVICE.ordinal()],candidateAvailable[Lane.NORMAL.ordinal()]);
            if (selected != work.lane() || !budgets.tryConsume(Budget.ASSIGNMENT_CANDIDATES,work.lane())) { assignmentBudgetDenied = true; break; }
            if (bucket.cursor >= bucket.ids.size()) bucket.cursor = 0;
            long candidateStart = System.nanoTime();
            try {
                UUID id = bucket.ids.get(bucket.cursor); bucket.cursor = (bucket.cursor+1) % bucket.ids.size(); CitizenRecord citizen = board.registry().citizen(id);
                if (!workerAvailable(citizen) || !work.target().dimension().equals(citizen.lastKnownPosition().dimension())) continue;
                if(WorkOrder.DELIVERY.equals(work.typeId())) {
                    var order=board.registry().supply().deliveryForWork(work.id());
                    if(order!=null&&board.registry().supply().hasCargo(order.id())&&!id.equals(order.citizenId()))continue;
                }
                long dx = (long)work.target().x()-citizen.lastKnownPosition().x(), dz = (long)work.target().z()-citizen.lastKnownPosition().z();
                long distance = Math.abs(dx)+Math.abs(dz)+Math.abs((long)work.target().y()-citizen.lastKnownPosition().y());
                if (winner == null || distance < bestDistance || distance == bestDistance && id.compareTo(winner) < 0) { winner = id; bestDistance = distance; }
            } finally { board.registry().metrics().record(Timer.ASSIGNMENT_UNIT, System.nanoTime() - candidateStart); }
        }
        return winner;
    }
    private void park(Node node, Reason reason) {
        pause(node); removeAssignment(node); board.transition(node.work.id(),State.WAITING,reason,node.work.stage());
        node.waitRevision = node.work.revision();
        // Atomic owner-thread registration plus final revision/condition recheck.
        if (node.work.revision() != node.waitRevision || reason == Reason.WORKER && hasWorker(node.work)) link(node);
        if (reason == Reason.BUDGET) deadlines.schedule(node.due,tick+1);
    }
    private boolean hasWorker(WorkOrder work) {
        WorkerIndex index = workers.get(work.colonyId()); if (index == null) return false;
        WorkerBucket bucket = work.professionId() == null ? index.any : index.professions.get(work.professionId());
        return bucket != null && !bucket.ids.isEmpty();
    }
    private void removeAssignment(Node node) {
        if (node.assignedCitizen != null) { assignments.remove(node.assignedCitizen,node); node.assignedCitizen = null; }
    }
    private void serviced(Node node) {
        ColonyQueue queue = node.colony;
        if (queue.lastServiceTick >= 0) queue.maxServiceGap = Math.max(queue.maxServiceGap, tick - queue.lastServiceTick);
        queue.lastServiceTick = tick; queue.serviceCount++;
    }
    /** Only requested diagnostics scan bounded admitted work; the hot path records service in O(1). */
    public Map<String,Object> diagnostics(UUID onlyColony) {
        board.registry().requireOwner();
        Map<String,Object> result = new LinkedHashMap<>();
        for (ColonyQueue queue : colonies) {
            if (onlyColony != null && !onlyColony.equals(queue.id)) continue;
            long oldest = 0, ready = 0, waiting = 0, dirty = 0, readyDelay = 0;
            for (Node node : queue.roots) {
                if (node.work.terminal()) continue;
                if (node.queued) { ready++; oldest = Math.max(oldest, age(node.work)); if(node.work.lane()==Lane.NORMAL) readyDelay=Math.max(readyDelay,tick-node.queuedAt); }
                if (node.work.state() == State.WAITING) waiting++;
                if (node.linked) dirty++;
            }
            Map<String,Object> data=new LinkedHashMap<>();
            data.put("serviceCount",queue.serviceCount); data.put("lastServiceTick",queue.lastServiceTick);
            data.put("maxServiceGapTicks",queue.maxServiceGap); data.put("currentServiceGapTicks",queue.lastServiceTick<0 ? tick : tick-queue.lastServiceTick);
            data.put("maxWorkAgeTicks",Math.max(oldest,queue.maxWorkAge)); data.put("ready",ready); data.put("waiting",waiting); data.put("dirty",dirty);
            data.put("maxNormalReadyServiceDelayTicks",queue.maxReadyServiceDelay); data.put("currentNormalReadyServiceDelayTicks",readyDelay);
            data.put("maxNormalColonyReadyServiceDelayTicks",queue.maxNormalColonyServiceDelay);
            data.put("currentNormalColonyReadyServiceDelayTicks",queue.normalReadySince<0 ? 0 : tick-queue.normalReadySince);
            result.put(queue.id.toString(),data);
        }
        if(onlyColony!=null) return Map.of("colonies",result);
        return Map.of("colonies", result, "deadlineEntries", deadlines.size());
    }
}
