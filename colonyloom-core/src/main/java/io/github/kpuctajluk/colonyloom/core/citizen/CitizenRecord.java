package io.github.kpuctajluk.colonyloom.core.citizen;

import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;

public record CitizenRecord(UUID citizenId, UUID colonyId, UUID entityId, long bindingEpoch,
        UUID homeId, UUID workplaceId, UUID assignedWorkId, String professionId,
        Map<String, Integer> skills, Map<String, Integer> needs, Lifecycle lifecycle,
        Admission admission, Readiness readiness, long activeTimeTicks,
        Map<String, Long> remainingTimers, WorldPosition lastKnownPosition, long revision) {
    // Control-state revision excludes clock/position telemetry; transport pages have their own state revision.
    public enum Lifecycle { ALIVE, DEAD, REMOVED }
    public enum Admission { ACTIVE, INACTIVE }
    public enum Readiness { UNKNOWN, RECONCILING, READY, BLOCKED }
    public static final String FOOD_TIMER = "food";
    public static final long FOOD_INTERVAL = 1200;
    public CitizenRecord {
        Objects.requireNonNull(citizenId, "citizenId");
        Objects.requireNonNull(colonyId, "colonyId");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(readiness, "readiness");
        Objects.requireNonNull(lastKnownPosition, "lastKnownPosition");
        if (bindingEpoch < 1 || activeTimeTicks < 0 || revision < 0) throw new IllegalArgumentException("Invalid citizen epoch/time/revision");
        if (professionId != null) ProfessionDefinition.validateId(professionId);
        skills = Map.copyOf(skills);
        needs = Map.copyOf(needs);
        remainingTimers = Map.copyOf(remainingTimers);
        for (int value : skills.values()) if (value < 0) throw new IllegalArgumentException("Negative citizen skill");
        for (int value : needs.values()) if (value < 0) throw new IllegalArgumentException("Negative citizen need");
        for (long value : remainingTimers.values()) if (value < 0) throw new IllegalArgumentException("Negative citizen timer");
        if (!needs.containsKey("food") || needs.get("food") > 20) throw new IllegalArgumentException("Citizen food must be 0..20");
    }
    public CitizenRecord withAssignment(UUID workId) {
        return new CitizenRecord(citizenId, colonyId, entityId, bindingEpoch, homeId, workplaceId, workId, professionId, skills, needs, lifecycle, admission, readiness, activeTimeTicks, remainingTimers, lastKnownPosition, Math.incrementExact(revision));
    }
    public CitizenRecord withActiveTime(long ticks) {
        if (ticks < activeTimeTicks) throw new IllegalArgumentException("Active time cannot reverse");
        long elapsed = ticks - activeTimeTicks;
        if (elapsed == 0) return this;
        long remaining = foodDecayTicks();
        long losses = 0;
        if (elapsed > 0 && elapsed >= remaining) {
            long afterFirst = elapsed - remaining;
            losses = 1 + afterFirst / FOOD_INTERVAL;
            remaining = FOOD_INTERVAL - afterFirst % FOOD_INTERVAL;
        } else remaining -= elapsed;
        Map<String, Integer> nextNeeds = needs;
        if (losses != 0) {
            var changedNeeds = new HashMap<>(needs);
            changedNeeds.put("food", (int)Math.max(0, food() - losses));
            nextNeeds = changedNeeds;
        }
        Map<String, Long> timers;
        if (remainingTimers.size() == 1 && remainingTimers.containsKey(FOOD_TIMER) || remainingTimers.isEmpty()) {
            timers = Map.of(FOOD_TIMER, remaining);
        } else {
            var changedTimers = new HashMap<>(remainingTimers);
            changedTimers.put(FOOD_TIMER, remaining);
            timers = changedTimers;
        }
        return new CitizenRecord(citizenId, colonyId, entityId, bindingEpoch, homeId, workplaceId, assignedWorkId, professionId, skills, nextNeeds, lifecycle, admission, readiness, ticks, timers, lastKnownPosition, losses == 0 ? revision : Math.incrementExact(revision));
    }
    public int food() { return needs.get("food"); }
    public long foodDecayTicks() { return remainingTimers.getOrDefault(FOOD_TIMER, FOOD_INTERVAL); }
    public CitizenRecord withFood(int food) {
        if (food < 0 || food > 20) throw new IllegalArgumentException("Citizen food must be 0..20");
        var nextNeeds = new HashMap<>(needs); nextNeeds.put("food", food);
        return new CitizenRecord(citizenId, colonyId, entityId, bindingEpoch, homeId, workplaceId, assignedWorkId, professionId, skills, nextNeeds, lifecycle, admission, readiness, activeTimeTicks, remainingTimers, lastKnownPosition, Math.incrementExact(revision));
    }
    public CitizenRecord withAdmission(Admission value) {
        return new CitizenRecord(citizenId, colonyId, entityId, bindingEpoch, homeId, workplaceId, assignedWorkId, professionId, skills, needs, lifecycle, value, readiness, activeTimeTicks, remainingTimers, lastKnownPosition, Math.incrementExact(revision));
    }
    public CitizenRecord withPosition(WorldPosition value) {
        if (lastKnownPosition.equals(value)) return this;
        return new CitizenRecord(citizenId, colonyId, entityId, bindingEpoch, homeId, workplaceId, assignedWorkId, professionId, skills, needs, lifecycle, admission, readiness, activeTimeTicks, remainingTimers, value, revision);
    }
    public CitizenRecord withProfession(String id) {
        return new CitizenRecord(citizenId, colonyId, entityId, bindingEpoch, homeId, workplaceId, assignedWorkId, id, skills, needs, lifecycle, admission, readiness, activeTimeTicks, remainingTimers, lastKnownPosition, Math.incrementExact(revision));
    }
    public CitizenRecord withBinding(UUID entity, long epoch) {
        return new CitizenRecord(citizenId, colonyId, entity, epoch, homeId, workplaceId, null, professionId, skills, needs, lifecycle, admission, Readiness.UNKNOWN, activeTimeTicks, remainingTimers, lastKnownPosition, Math.incrementExact(revision));
    }
    public CitizenRecord reconciled() {
        return new CitizenRecord(citizenId, colonyId, entityId, bindingEpoch, homeId, workplaceId, null, professionId, skills, needs, lifecycle, admission, lifecycle == Lifecycle.ALIVE ? Readiness.READY : Readiness.BLOCKED, activeTimeTicks, remainingTimers, lastKnownPosition, Math.incrementExact(revision));
    }
    public CitizenRecord withLifecycle(Lifecycle state) {
        return new CitizenRecord(citizenId, colonyId, entityId, bindingEpoch, homeId, workplaceId, null, professionId, skills, needs, state, Admission.INACTIVE, Readiness.BLOCKED, activeTimeTicks, remainingTimers, lastKnownPosition, Math.incrementExact(revision));
    }
}
