package colonyloom.gameplay.life;

import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.TraitDefinition;
import colonyloom.core.life.state.LifeState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGenerator;

public final class Traits {
    private Traits() {}

    static boolean compatible(LifeContent content, LifeState state, TraitDefinition trait, int years) {
        if (state.hasTrait(trait.id()) || years < trait.minAge()) return false;
        for (String owned : state.traits()) {
            TraitDefinition other = content.traits().get(owned);
            if (other == null) continue;
            if (other.group().equals(trait.group()) || other.conflicts().contains(trait.id()) || trait.conflicts().contains(other.id())) return false;
        }
        return true;
    }

    public static int grant(LifeContent content, LifeState state, int count, boolean orientation, List<LifeState> parents,
                            int years, boolean childhood, RandomGenerator random) {
        Map<String, Double> inclinations = Genetics.traitWeights(content, state);
        int granted = 0;
        for (int i = 0; i < count && state.traits().size() < LifeState.teen_pro_YT_MAX_TRAITS - (orientation && Relations.orientation(content, state) == null ? 1 : 0); i++) {
            TraitDefinition chosen = pick(content, state, years, childhood, false, inclinations, parents, random);
            if (chosen == null) break;
            if (state.addTrait(chosen.id())) granted++;
        }
        if (orientation && Relations.orientation(content, state) == null && state.traits().size() < LifeState.teen_pro_YT_MAX_TRAITS) {
            TraitDefinition chosen = pick(content, state, years, childhood, true, inclinations, parents, random);
            if (chosen != null && state.addTrait(chosen.id())) granted++;
        }
        if (granted > 0) state.invalidateStats();
        return granted;
    }

    private static TraitDefinition pick(LifeContent content, LifeState state, int years, boolean childhood, boolean orientation,
                                        Map<String, Double> inclinations, List<LifeState> parents, RandomGenerator random) {
        List<TraitDefinition> candidates = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (TraitDefinition trait : new TreeMap<>(content.traits()).values()) {
            if ((trait.orientation() != null) != orientation || childhood && !trait.childhood() || !compatible(content, state, trait, years)) continue;
            double weight = LifeRules.fraction(trait.weight()) * (1 + inclinations.getOrDefault(trait.id(), 0.0));
            for (LifeState parent : parents) if (parent.hasTrait(trait.id())) weight *= 2;
            if (weight <= 0) continue;
            candidates.add(trait); weights.add(weight);
        }
        return candidates.isEmpty() ? null : Brain.weightedPick(candidates, weights, random);
    }
}
