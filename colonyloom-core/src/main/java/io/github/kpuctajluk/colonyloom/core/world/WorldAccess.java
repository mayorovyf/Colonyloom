package io.github.kpuctajluk.colonyloom.core.world;

import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.content.BlockDescriptor;

/** Consumer-owned physical construction boundary; no mutable world or inventory values cross it. */
public interface WorldAccess {
    enum Placement { PLACED, ALREADY_PRESENT, MATERIALS, PERMISSION_DENIED, OBSTRUCTED, AMBIGUOUS, UNAVAILABLE }

    boolean matches(WorldPosition target, BlockDescriptor expected);

    Placement place(ActionContext context, long bindingEpoch, BlockDescriptor expected, int sourceSlot);
}
