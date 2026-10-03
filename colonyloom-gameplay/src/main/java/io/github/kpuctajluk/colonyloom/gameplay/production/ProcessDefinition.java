package io.github.kpuctajluk.colonyloom.gameplay.production;

import io.github.kpuctajluk.colonyloom.core.supply.RecipeDefinition;
import java.util.Objects;

/** Allowed gameplay process; the pinned core recipe never retains native registry objects. */
public record ProcessDefinition(RecipeDefinition recipe) {
    public ProcessDefinition { Objects.requireNonNull(recipe); }
    public String id() { return recipe.id(); }
}
