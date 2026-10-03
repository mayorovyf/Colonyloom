package io.github.kpuctajluk.colonyloom.core.work;

import io.github.kpuctajluk.colonyloom.core.citizen.ProfessionDefinition;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Authoritative owner-thread state. Only WorkBoard may mutate it. */
public final class WorkOrder {
    public static final String ACTIVE_WAIT = "colonyloom:active_wait";
    public static final String MOVE = "colonyloom:move";
    public static final String CONSTRUCTION = "colonyloom:construction";
    public static final String DELIVERY = "colonyloom:delivery";
    public static final String PRODUCTION = "colonyloom:production";
    public static final String FOOD = "colonyloom:food";
    public static final int MAX_DEPENDENCIES = 16;
    public enum State { PLANNED, READY, ASSIGNED, RUNNING, WAITING, COMPLETED, CANCELLED, FAILED }
    public enum Reason { NONE, MATERIALS, TOOL, WORKER, CAPACITY, UNREACHABLE, CHUNK_NOT_READY,
        RECONCILING, STATE_LIMIT, WORKING_SET_LIMIT, BUDGET, PERMISSION_DENIED, UNSUPPORTED_STORAGE,
        TARGET_CONFLICT, RECOVERY_AMBIGUOUS, CONTENT_UNAVAILABLE, CRITICAL_CAPACITY, CARGO_LOST }
    public enum StepResult { PROGRESS, COMPLETED, WAITING, INVALIDATED, FAILED }
    public record Snapshot(String typeId, UUID id, UUID colonyId, WorldPosition target,
            String professionId, int priority, Lane lane, State state, UUID assignee,
            String stage, long revision, List<UUID> dependencies, Reason waitingReason,
            long remainingActiveTicks, long ageActiveTicks, UUID subjectId, boolean criticalService) {
        public Snapshot {
            Objects.requireNonNull(typeId, "typeId");
            ProfessionDefinition.validateId(typeId);
            Objects.requireNonNull(id, "id"); Objects.requireNonNull(colonyId, "colonyId");
            Objects.requireNonNull(target, "target"); Objects.requireNonNull(lane, "lane");
            Objects.requireNonNull(state, "state"); Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(waitingReason, "waitingReason");
            if (professionId != null) ProfessionDefinition.validateId(professionId);
            if (priority < 0 || priority > 10 || revision < 0 || remainingActiveTicks < 0 || ageActiveTicks < 0 || stage.length() > 256)
                throw new IllegalArgumentException("Invalid work bounds");
            if (dependencies.size() > MAX_DEPENDENCIES) throw new IllegalArgumentException("Too many work dependencies");
            dependencies = List.copyOf(dependencies);
            for (int i=0;i<dependencies.size();i++) {
                if (dependencies.get(i).equals(id)) throw new IllegalArgumentException("Invalid work dependencies");
                for (int j=0;j<i;j++) if (dependencies.get(i).equals(dependencies.get(j))) throw new IllegalArgumentException("Duplicate work dependency");
            }
            if ((state == State.ASSIGNED || state == State.RUNNING) && assignee == null)
                throw new IllegalArgumentException("Assigned work has no assignee");
            if ((state == State.PLANNED || state == State.READY || terminal(state)) && assignee != null)
                throw new IllegalArgumentException("Work state cannot hold an assignment");
            if (state == State.WAITING && waitingReason == Reason.NONE)
                throw new IllegalArgumentException("Waiting work has no reason");
            if (FOOD.equals(typeId) && (subjectId == null || lane != Lane.CRITICAL || professionId != null)
                    || subjectId != null && assignee != null && !subjectId.equals(assignee)) throw new IllegalArgumentException("Invalid prescribed work subject");
        }
    }
    private final String typeId;
    private final UUID id, colonyId;
    private final WorldPosition target;
    private final String professionId;
    private final Lane lane;
    private final List<UUID> dependencies;
    private final UUID subjectId;
    private boolean criticalService;
    private int priority;
    private State state;
    private UUID assignee;
    private String stage;
    private Reason waitingReason;
    private long revision, remainingActiveTicks, ageActiveTicks, processedRevision = -1;
    private boolean dirty = true;

    private WorkOrder(Snapshot value) {
        typeId = value.typeId(); id = value.id(); colonyId = value.colonyId(); target = value.target();
        professionId = value.professionId(); priority = value.priority(); lane = value.lane(); state = value.state();
        assignee = value.assignee(); stage = value.stage(); revision = value.revision(); dependencies = value.dependencies();
        waitingReason = value.waitingReason(); remainingActiveTicks = value.remainingActiveTicks(); ageActiveTicks = value.ageActiveTicks();
        subjectId = value.subjectId(); criticalService = value.criticalService();
    }
    public static WorkOrder restore(Snapshot value) { return new WorkOrder(Objects.requireNonNull(value)); }
    public Snapshot snapshot() { return snapshot(ageActiveTicks); }
    Snapshot snapshot(long age) { return snapshot(age,remainingActiveTicks); }
    Snapshot snapshot(long age, long remaining) { return new Snapshot(typeId,id,colonyId,target,professionId,priority,lane,state,assignee,stage,revision,dependencies,waitingReason,remaining,age,subjectId,criticalService); }
    public String typeId() { return typeId; } public UUID id() { return id; } public UUID colonyId() { return colonyId; }
    public WorldPosition target() { return target; } public String professionId() { return professionId; }
    public int priority() { return priority; } public Lane lane() { return criticalService ? Lane.CRITICAL : lane; } public State state() { return state; }
    public Lane admissionLane() { return lane; }
    public UUID subjectId() { return subjectId; }
    public boolean criticalService() { return criticalService; }
    public UUID assignee() { return assignee; } public String stage() { return stage; } public long revision() { return revision; }
    public List<UUID> dependencies() { return dependencies; } public Reason waitingReason() { return waitingReason; }
    public long remainingActiveTicks() { return remainingActiveTicks; } public long ageActiveTicks() { return ageActiveTicks; }
    public boolean dirty() { return dirty; } public long processedRevision() { return processedRevision; }
    public boolean terminal() { return terminal(state); }
    private static boolean terminal(State value) { return value == State.COMPLETED || value == State.CANCELLED || value == State.FAILED; }
    void invalidate() { revision = Math.incrementExact(revision); dirty = true; }
    void acknowledge(long value) { processedRevision = Math.max(processedRevision, value); dirty = revision > processedRevision; }
    void priority(int value) { priority = value; invalidate(); }
    void assignment(UUID value) { assignee = value; invalidate(); }
    void criticalService(boolean value) { criticalService = value; invalidate(); }
    void transition(State next, Reason reason, String nextStage) {
        if (terminal() && next != state) throw new IllegalStateException("Terminal work cannot transition");
        boolean allowed = next == state || terminal(next) || switch (state) {
            case PLANNED -> next == State.READY || next == State.WAITING;
            case READY -> next == State.ASSIGNED || next == State.WAITING;
            case ASSIGNED -> next == State.RUNNING || next == State.WAITING || next == State.READY;
            case RUNNING -> next == State.WAITING || next == State.READY;
            case WAITING -> next == State.READY;
            default -> false;
        };
        if (!allowed || next == State.WAITING && reason == Reason.NONE) throw new IllegalStateException("Invalid work transition");
        state = next; waitingReason = reason; stage = nextStage; invalidate();
    }
    void progress(long amount) { remainingActiveTicks -= amount; invalidate(); }
}
