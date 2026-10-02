package io.github.kpuctajluk.colonyloom.core.persistence;

import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import java.util.Objects;
import java.util.UUID;

public record Tombstone(UUID citizenId, UUID colonyId, long bindingEpoch, CitizenRecord.Lifecycle lifecycle) {
    public Tombstone {
        Objects.requireNonNull(citizenId, "citizenId");
        Objects.requireNonNull(colonyId, "colonyId");
        Objects.requireNonNull(lifecycle, "lifecycle");
        if (bindingEpoch < 1 || lifecycle == CitizenRecord.Lifecycle.ALIVE) throw new IllegalArgumentException("Invalid tombstone");
    }
}
