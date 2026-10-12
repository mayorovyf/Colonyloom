package colonyloom.core.command;

import colonyloom.core.colony.WorldPosition;
import colonyloom.core.work.WorkOrder;
import java.util.UUID;

/** Consumer-owned gameplay command port shared by CLI and the eventual typed network handler. */
public interface ConstructionCommands {
    WorkOrder build(ColonyCommands.CommandContext context, UUID workId, UUID colonyId,
            String blueprintId, WorldPosition origin, int rotation);
}
