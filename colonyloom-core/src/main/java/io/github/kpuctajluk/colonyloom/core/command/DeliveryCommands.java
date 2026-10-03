package io.github.kpuctajluk.colonyloom.core.command;

import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.supply.Demand;
import java.util.UUID;

/** One command owner for physical delivery goals, shared by CLI and future network handlers. */
public interface DeliveryCommands {
    Demand request(ColonyCommands.CommandContext context, UUID demandId, UUID colonyId,
            WorldPosition source, WorldPosition destination, io.github.kpuctajluk.colonyloom.core.storage.ItemDescriptor item, long count);
    void cancel(ColonyCommands.CommandContext context, UUID demandId);
}
