package io.github.kpuctajluk.colonyloom.gameplay.production;

import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.production.ProductionOrder;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import java.util.Objects;
import java.util.UUID;

public final class ProductionController {
    private final ColonyRegistry registry;
    private int cursor;
    private long cursorRevision = -1;
    public ProductionController(ColonyRegistry registry) { this.registry = Objects.requireNonNull(registry); }
    public void tick(GlobalWorkBudgets budgets) {
        registry.requireOwner();
        var supply = registry.supply();
        int count = supply.productionCount();
        long revision = supply.productionIndexRevision();
        if (revision != cursorRevision) { cursor = 0; cursorRevision = revision; }
        if (count == 0) { cursor = 0; return; }
        if (cursor >= count) cursor = 0;
        for (int scanned = 0; scanned < count; scanned++) {
            if (cursor >= count) cursor = 0;
            var production = supply.productionAt(cursor);
            if (!budgets.tryConsume(Budget.DIRTY_RESCAN_OBJECTS, production.lane())) return;
            cursor = (cursor + 1) % count;
            if (!registry.colony(production.colonyId()).available()) continue;
            if (production.terminal()) {
                if (production.workId() != null) {
                    var work = registry.workBoard().work(production.workId());
                    if (!work.terminal()) registry.workBoard().transition(work.id(), production.state() == ProductionOrder.State.COMPLETED ? WorkOrder.State.COMPLETED : WorkOrder.State.CANCELLED, WorkOrder.Reason.NONE, "finished");
                }
                continue;
            }
            if (!production.pinned()) { if (!registry.supply().pinProduction(production.id())) continue; production = registry.supply().production(production.id()); }
            boolean ready = !registry.supply().completeProductionKit(production.id()).isEmpty();
            if (production.workId() == null) {
                if (!ready) continue;
                UUID workId;
                do { workId = UUID.randomUUID(); } while (registry.usedId(workId));
                var work = registry.workBoard().createProduction(workId, production.colonyId(), production.equipmentPosition(), production.recipe().professionId(), Math.max(0, Math.min(10, production.priority())), production.lane());
                registry.supply().assignProductionWork(production.id(), work.id());
                registry.workBoard().transition(work.id(), WorkOrder.State.READY, WorkOrder.Reason.NONE, "production");
            } else {
                var work = registry.workBoard().work(production.workId());
                if (!ready && !production.batchStarted() && !work.terminal()) registry.workBoard().transition(work.id(), WorkOrder.State.WAITING, WorkOrder.Reason.MATERIALS, "kit");
                else if (ready && work.state() == WorkOrder.State.WAITING && work.assignee() == null
                        && (work.waitingReason() == WorkOrder.Reason.MATERIALS || work.waitingReason() == WorkOrder.Reason.BUDGET))
                    registry.workBoard().transition(work.id(), WorkOrder.State.READY, WorkOrder.Reason.NONE, "production");
            }
        }
    }
}
