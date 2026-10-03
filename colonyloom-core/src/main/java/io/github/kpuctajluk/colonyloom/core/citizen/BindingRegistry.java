package io.github.kpuctajluk.colonyloom.core.citizen;

import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Observes identity only; never owns entities or their inventories. */
public final class BindingRegistry {
    /** Up to 300 canonical embodiments plus bounded competing/retired observations. */
    public static final int MAX_OBSERVATIONS = 600;
    public record Observation(UUID citizenId, UUID entityId, long bindingEpoch, boolean loaded, boolean quarantined, boolean retired) {
        public Observation {
            Objects.requireNonNull(entityId, "entityId");
            if (bindingEpoch < 0) throw new IllegalArgumentException("Negative observed epoch");
            if (retired && !quarantined) throw new IllegalArgumentException("Retired incarnation must be quarantined");
        }
    }

    private final ColonyRegistry registry;
    private final LinkedHashMap<UUID, Observation> observations = new LinkedHashMap<>();
    private long revision;

    public BindingRegistry(ColonyRegistry registry) { this.registry = registry; }
    public long revision() { registry.requireOwner(); return revision; }
    public List<Observation> observations() { registry.requireOwner(); return List.copyOf(observations.values()); }
    public List<Observation> observations(UUID citizenId) {
        registry.requireOwner();
        return observations.values().stream().filter(value -> Objects.equals(citizenId, value.citizenId())).toList();
    }

    public void observe(UUID citizenId, UUID entityId, long epoch) {
        registry.requireOwner();
        Observation previous = observations.get(entityId);
        if (previous != null && !Objects.equals(previous.citizenId(), citizenId)) throw new IllegalArgumentException("Entity identity changed citizen");
        if (previous == null && observations.size() >= MAX_OBSERVATIONS) throw new IllegalStateException("Binding observation limit reached");
        CitizenRecord citizen = registry.findCitizen(citizenId).orElse(null);
        boolean retired = previous != null && previous.retired();
        boolean invalid = citizen == null || citizen.lifecycle() != CitizenRecord.Lifecycle.ALIVE || !entityId.equals(citizen.entityId()) || epoch != citizen.bindingEpoch();
        boolean competing = !retired && observations.values().stream().anyMatch(value -> Objects.equals(citizenId, value.citizenId()) && !value.entityId().equals(entityId) && !value.retired());
        boolean quarantined = retired || invalid || competing || previous != null && previous.quarantined();
        Observation next = new Observation(citizenId, entityId, epoch, true, quarantined, retired);
        if (next.equals(previous) && !competing) return;
        long nextRevision = Math.incrementExact(revision);
        registry.beforeMutation();
        if (previous == null && (citizen == null || !citizen.entityId().equals(entityId))) {
            registry.admission().reserve(citizen == null ? new UUID(0, 0) : citizen.colonyId(),
                    io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.NORMAL,
                    java.util.Map.of(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.EVIDENCE, 1));
        }
        observations.put(entityId, next);
        if (competing) quarantineAll(citizenId);
        revision = nextRevision;
    }

    public void unload(UUID entityId) {
        registry.requireOwner();
        Observation observation = observations.get(entityId);
        if (observation == null || !observation.loaded()) return;
        long nextRevision = Math.incrementExact(revision);
        registry.beforeMutation();
        observations.put(entityId, new Observation(observation.citizenId(), entityId, observation.bindingEpoch(), false, observation.quarantined(), observation.retired()));
        revision = nextRevision;
    }

    public Optional<UUID> activeEntity(UUID citizenId) {
        registry.requireOwner();
        CitizenRecord citizen = registry.findCitizen(citizenId).orElse(null);
        if (citizen == null || citizen.lifecycle() != CitizenRecord.Lifecycle.ALIVE || !registry.colony(citizen.colonyId()).available()) return Optional.empty();
        Observation candidate = observations.get(citizen.entityId());
        if (candidate == null || !citizenId.equals(candidate.citizenId()) || !candidate.loaded() || candidate.quarantined() || candidate.retired() || candidate.bindingEpoch() != citizen.bindingEpoch()) return Optional.empty();
        return Optional.of(candidate.entityId());
    }

    public void validateBind(UUID citizenId, UUID chosenEntityId) {
        registry.requireOwner();
        CitizenRecord citizen = registry.citizen(citizenId);
        if (citizen.lifecycle() != CitizenRecord.Lifecycle.ALIVE) throw new IllegalStateException("Cannot bind dead or removed citizen");
        List<Observation> known = observations(citizenId);
        if (known.isEmpty() || known.stream().anyMatch(value -> !value.loaded())) throw new IllegalStateException("All known incarnations must be loaded");
        Observation chosen = observations.get(chosenEntityId);
        if (chosen == null || !citizenId.equals(chosen.citizenId()) || !chosen.loaded()) throw new IllegalArgumentException("Chosen entity does not match citizen");
        Math.incrementExact(citizen.bindingEpoch());
        Math.incrementExact(revision);
    }

    /** Called by the command owner only after validation and session gate. */
    public void applyBind(UUID citizenId, UUID chosenEntityId, long epoch) {
        registry.requireOwner();
        for (Observation value : new ArrayList<>(observations.values())) {
            if (!citizenId.equals(value.citizenId())) continue;
            boolean chosen = chosenEntityId.equals(value.entityId());
            observations.put(value.entityId(), new Observation(citizenId, value.entityId(), chosen ? epoch : value.bindingEpoch(), value.loaded(), !chosen, !chosen));
        }
        revision = Math.incrementExact(revision);
    }

    public boolean recoveryReady(UUID citizenId) {
        registry.requireOwner();
        CitizenRecord citizen = registry.citizen(citizenId);
        if (citizen.lifecycle() != CitizenRecord.Lifecycle.ALIVE) return true;
        List<Observation> known = observations(citizenId);
        if (known.isEmpty() || known.stream().anyMatch(value -> !value.loaded())) return false;
        return known.stream().filter(value -> !value.retired()).count() == 1 && known.stream().anyMatch(value -> value.entityId().equals(citizen.entityId()) && value.bindingEpoch() == citizen.bindingEpoch() && !value.quarantined() && !value.retired());
    }

    public void quarantine(UUID citizenId) {
        registry.requireOwner();
        quarantineAll(citizenId);
        revision = Math.incrementExact(revision);
    }

    private void quarantineAll(UUID citizenId) {
        observations.replaceAll((id, value) -> Objects.equals(citizenId, value.citizenId()) ? new Observation(value.citizenId(), id, value.bindingEpoch(), value.loaded(), true, value.retired()) : value);
    }

    public void restore(List<Observation> restored) {
        registry.requireOwner();
        if (restored.size() > MAX_OBSERVATIONS) throw new IllegalArgumentException("Too many binding observations");
        LinkedHashMap<UUID, Observation> checked = new LinkedHashMap<>();
        for (Observation value : restored) {
            Observation unloaded = new Observation(value.citizenId(), value.entityId(), value.bindingEpoch(), false, value.quarantined(), value.retired());
            if (checked.putIfAbsent(value.entityId(), unloaded) != null) throw new IllegalArgumentException("Duplicate observed entity UUID");
        }
        observations.clear();
        observations.putAll(checked);
        for (Observation value : restored) {
            CitizenRecord citizen = registry.findCitizen(value.citizenId()).orElse(null);
            if (citizen == null || citizen.lifecycle() != CitizenRecord.Lifecycle.ALIVE || !value.retired() && (!value.entityId().equals(citizen.entityId()) || value.bindingEpoch() != citizen.bindingEpoch())) quarantineAll(value.citizenId());
            if (observations(value.citizenId()).stream().filter(candidate -> !candidate.retired()).count() > 1) quarantineAll(value.citizenId());
        }
        revision = Math.incrementExact(revision);
    }
}
