package colonyloom.core.building;

import colonyloom.core.citizen.ProfessionDefinition;
import colonyloom.core.colony.WorldPosition;
import java.util.Objects;
import java.util.UUID;

public record BuildingRecord(UUID buildingId, UUID colonyId, String typeId, WorldPosition position, long revision) {
    public BuildingRecord {
        Objects.requireNonNull(buildingId, "buildingId");
        Objects.requireNonNull(colonyId, "colonyId");
        Objects.requireNonNull(position, "position");
        ProfessionDefinition.validateId(typeId);
        if (revision < 0) throw new IllegalArgumentException("Negative revision");
    }
}
