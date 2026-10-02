package io.github.kpuctajluk.colonyloom.core.persistence;

import io.github.kpuctajluk.colonyloom.core.building.BuildingRecord;
import io.github.kpuctajluk.colonyloom.core.citizen.BindingRegistry;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import java.util.List;

public record RegistrySnapshot(List<ColonyRuntime> colonies, List<CitizenRecord> citizens,
        List<BuildingRecord> buildings, List<Tombstone> tombstones, List<BindingRegistry.Observation> observations) {
    public RegistrySnapshot {
        colonies = List.copyOf(colonies);
        citizens = List.copyOf(citizens);
        buildings = List.copyOf(buildings);
        tombstones = List.copyOf(tombstones);
        observations = List.copyOf(observations);
    }
}
