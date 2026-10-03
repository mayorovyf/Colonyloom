package io.github.kpuctajluk.colonyloom.gameplay.logistics;

import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.MemberRank;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.command.ColonyCommands;
import io.github.kpuctajluk.colonyloom.core.command.DeliveryCommands;
import io.github.kpuctajluk.colonyloom.core.storage.ItemDescriptor;
import io.github.kpuctajluk.colonyloom.core.storage.StorageId;
import io.github.kpuctajluk.colonyloom.core.supply.Demand;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Request rules only: the existing scheduler owns courier assignment and physical steps. */
public final class DeliveryController implements DeliveryCommands {
    public record Source(List<StorageId> storages) {
        public Source {
            storages = List.copyOf(storages);
            if (storages.isEmpty() || storages.size() > 2 || storages.stream().distinct().count() != storages.size())
                throw new IllegalArgumentException("Invalid selected canonical source");
        }
    }
    public interface Port {
        Source source(UUID colonyId, WorldPosition address);
        void validateDestination(UUID colonyId, WorldPosition destination);
        void validateReturnBuffer(UUID colonyId);
        long tick();
    }
    private final ColonyRegistry registry;
    private final Port port;
    public DeliveryController(ColonyRegistry registry, Port port) {
        this.registry = Objects.requireNonNull(registry); this.port = Objects.requireNonNull(port);
    }
    @Override public Demand request(ColonyCommands.CommandContext context, UUID demandId, UUID colonyId,
                                   WorldPosition source, WorldPosition destination, ItemDescriptor item, long count) {
        manager(context, colonyId);
        if (!registry.colony(colonyId).available()) throw new IllegalStateException("Colony unavailable");
        Demand.quantity(count); if (count == 0) throw new IllegalArgumentException("Empty delivery");
        if (!registry.colony(colonyId).territory().contains(source) || !registry.colony(colonyId).territory().contains(destination)
                || source.equals(destination)) throw new IllegalArgumentException("Invalid delivery endpoints");
        Source selected = port.source(colonyId, source);
        port.validateDestination(colonyId, destination); port.validateReturnBuffer(colonyId);
        return registry.supply().requestDelivery(demandId, colonyId, context.actorId(), item, count, destination, selected.storages(), port.tick());
    }
    @Override public void cancel(ColonyCommands.CommandContext context, UUID demandId) {
        var demand = registry.supply().demand(demandId).snapshot(); manager(context, demand.colonyId());
        if (demand.goalKind() != Demand.GoalKind.DELIVERY) throw new IllegalArgumentException("Not a delivery demand");
        registry.supply().cancel(demandId);
        for (var order : registry.supply().deliveries()) if (order.ownerDemandId().equals(demandId) && order.workId() != null)
            registry.workBoard().cancel(order.workId());
    }
    private void manager(ColonyCommands.CommandContext context, UUID colonyId) {
        registry.requireOwner(); MemberRank rank = registry.colony(colonyId).rank(context.actorId());
        if (rank != MemberRank.OWNER && rank != MemberRank.MANAGER) throw new SecurityException("Delivery requires colony manager");
    }
}
