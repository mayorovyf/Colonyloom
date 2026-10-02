package io.github.kpuctajluk.colonyloom.core.citizen;

import java.util.Objects;
import java.util.Set;

public record ProfessionDefinition(String id, int version, Set<String> allowedWorkTypes, Set<String> equipment) {
    public ProfessionDefinition {
        validateId(id);
        if (version < 1) throw new IllegalArgumentException("Profession version must be positive");
        allowedWorkTypes = Set.copyOf(allowedWorkTypes);
        equipment = Set.copyOf(equipment);
        allowedWorkTypes.forEach(ProfessionDefinition::validateId);
        equipment.forEach(ProfessionDefinition::validateId);
    }
    public static void validateId(String id) {
        Objects.requireNonNull(id, "content ID");
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid content ID: " + id);
    }
}
