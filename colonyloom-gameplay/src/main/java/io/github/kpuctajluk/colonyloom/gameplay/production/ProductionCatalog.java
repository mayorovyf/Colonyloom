package io.github.kpuctajluk.colonyloom.gameplay.production;

import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.supply.ItemMatcher;
import io.github.kpuctajluk.colonyloom.core.supply.RecipeDefinition;
import io.github.kpuctajluk.colonyloom.core.supply.SupplyPlanner;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Only explicitly registered carpenter workplaces qualify; a loose crafting table is not equipment. */
public final class ProductionCatalog implements SupplyPlanner.RecipeProvider {
    private final ColonyRegistry registry;
    private final Map<String, ProcessDefinition> processes;
    public ProductionCatalog(ColonyRegistry registry, Map<String, ProcessDefinition> processes) {
        this.registry = Objects.requireNonNull(registry); this.processes = Map.copyOf(processes);
        this.processes.forEach((id, process) -> { if (!id.equals(process.id())) throw new IllegalArgumentException("Process key mismatch"); });
    }
    @Override public List<RecipeDefinition> recipes(UUID colony, ItemMatcher target) {
        if (!registry.colony(colony).available()) return List.of();
        return processes.values().stream().map(ProcessDefinition::recipe)
                .filter(recipe -> target.matches(recipe.output()) && available(colony, recipe))
                .sorted(Comparator.comparing(RecipeDefinition::id)).toList();
    }
    private boolean available(UUID colony, RecipeDefinition recipe) {
        if (!recipe.equipmentId().equals("minecraft:crafting_table")) return false;
        for (CitizenRecord citizen : registry.citizens(colony)) {
            if (citizen.lifecycle() != CitizenRecord.Lifecycle.ALIVE || citizen.admission() != CitizenRecord.Admission.ACTIVE
                    || !recipe.professionId().equals(citizen.professionId()) || citizen.workplaceId() == null) continue;
            for (var workshop : registry.storage().workshops()) {
                if (workshop.colonyId().equals(colony) && workshop.id().equals(citizen.workplaceId())
                        && registry.storage().registrations(colony).stream().anyMatch(registration -> registration.id().equals(workshop.registrationId())
                        && registration.role().equals("workshop"))) return true;
            }
        }
        return false;
    }
}
