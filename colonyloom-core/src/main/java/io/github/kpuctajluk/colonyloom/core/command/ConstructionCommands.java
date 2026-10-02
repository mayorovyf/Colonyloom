package io.github.kpuctajluk.colonyloom.core.command;

import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import java.util.UUID;

/** Consumer-owned gameplay command port shared by CLI and the eventual typed network handler. */
public interface ConstructionCommands {
    WorkOrder build(ColonyCommands.CommandContext context, UUID workId, UUID colonyId,
            String blueprintId, WorldPosition origin, int rotation);
}
