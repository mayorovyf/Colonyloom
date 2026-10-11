package colonyloom.core.persistence;

import colonyloom.core.building.BuildingRecord;
import colonyloom.core.citizen.BindingRegistry;
import colonyloom.core.citizen.CitizenRecord;
import colonyloom.core.colony.ColonyRuntime;
import colonyloom.core.work.WorkOrder;
import java.util.List;

public record RegistrySnapshot(List<ColonyRuntime> colonies, List<CitizenRecord> citizens,
        List<BuildingRecord> buildings, List<Tombstone> tombstones, List<BindingRegistry.Observation> observations,
        List<WorkOrder.Snapshot> works, List<colonyloom.core.spatial.TargetClaimRegistry.Snapshot> targetClaims,
        List<colonyloom.core.action.EffectRecord> effects,
        List<colonyloom.core.construction.ConstructionSnapshot> constructionSites,
        List<colonyloom.core.content.BlueprintDefinition> pinnedBlueprints,
        colonyloom.core.storage.StorageSnapshot storage,
        colonyloom.core.supply.SupplySnapshot supply) {
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
        storage = java.util.Objects.requireNonNull(storage);
        supply = java.util.Objects.requireNonNull(supply);
    }
}
