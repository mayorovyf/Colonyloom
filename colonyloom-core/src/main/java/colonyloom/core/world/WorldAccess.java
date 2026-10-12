package colonyloom.core.world;

import colonyloom.core.action.ActionContext;
import colonyloom.core.colony.WorldPosition;
import colonyloom.core.content.BlockDescriptor;

/** Consumer-owned physical construction boundary; no mutable world or inventory values cross it. */
public interface WorldAccess {
    enum Placement { PLACED, ALREADY_PRESENT, MATERIALS, PERMISSION_DENIED, OBSTRUCTED, AMBIGUOUS, UNAVAILABLE }

    boolean matches(WorldPosition target, BlockDescriptor expected);

    Placement place(ActionContext context, long bindingEpoch, BlockDescriptor expected, int sourceSlot);
}
