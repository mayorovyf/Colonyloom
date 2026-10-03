package io.github.kpuctajluk.colonyloom.core.supply;

import io.github.kpuctajluk.colonyloom.core.production.ProductionOrder;
import io.github.kpuctajluk.colonyloom.core.logistics.DeliveryOrder;
import java.util.List;

public record SupplySnapshot(List<Demand.Snapshot> demands, List<CoverageShare> shares,
                             List<ProductionOrder> productionOrders, List<DeliveryOrder> deliveries) {
    public SupplySnapshot {
        demands = List.copyOf(demands); shares = List.copyOf(shares);
        productionOrders = List.copyOf(productionOrders); deliveries = List.copyOf(deliveries);
    }
    public static SupplySnapshot empty() { return new SupplySnapshot(List.of(), List.of(), List.of(), List.of()); }
}
