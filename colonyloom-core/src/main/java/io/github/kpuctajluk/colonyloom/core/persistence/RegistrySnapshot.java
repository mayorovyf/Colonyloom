package io.github.kpuctajluk.colonyloom.core.persistence;

import io.github.kpuctajluk.colonyloom.core.building.BuildingRecord;
import io.github.kpuctajluk.colonyloom.core.citizen.BindingRegistry;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import java.util.List;

public record RegistrySnapshot(List<ColonyRuntime> colonies, List<CitizenRecord> citizens,
        List<BuildingRecord> buildings, List<Tombstone> tombstones, List<BindingRegistry.Observation> observations,
        List<WorkOrder.Snapshot> works, List<io.github.kpuctajluk.colonyloom.core.spatial.TargetClaimRegistry.Snapshot> targetClaims,
        List<io.github.kpuctajluk.colonyloom.core.action.EffectRecord> effects,
        List<io.github.kpuctajluk.colonyloom.core.construction.ConstructionSnapshot> constructionSites,
        List<io.github.kpuctajluk.colonyloom.core.content.BlueprintDefinition> pinnedBlueprints) {
    public RegistrySnapshot {
        colonies = List.copyOf(colonies);
        citizens = List.copyOf(citizens);
        buildings = List.copyOf(buildings);
        tombstones = List.copyOf(tombstones);
        observations = List.copyOf(observations);
        works = List.copyOf(works);
        targetClaims = List.copyOf(targetClaims);
        effects = List.copyOf(effects);
        constructionSites = List.copyOf(constructionSites);
        pinnedBlueprints = List.copyOf(pinnedBlueprints);
    }
}
