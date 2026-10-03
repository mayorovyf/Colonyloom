package io.github.kpuctajluk.colonyloom.neoforge;

import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.action.EffectRecord;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/** Current-principal veto before one native bread expense; listeners cannot authorize stale inventory. */
public final class FoodConsumeEvent extends Event implements ICancellableEvent {
    private final MinecraftServer server;
    private final ActionContext context;
    private final UUID principal;
    private final EffectRecord.Food prepared;
    public FoodConsumeEvent(MinecraftServer server,ActionContext context,UUID principal,EffectRecord.Food prepared) {
        this.server=Objects.requireNonNull(server);this.context=Objects.requireNonNull(context);
        this.principal=Objects.requireNonNull(principal);this.prepared=Objects.requireNonNull(prepared);
        if(context.actionKind()!=ActionContext.Kind.FOOD_CONSUME||prepared.foodBefore()!=prepared.foodAfter()||prepared.timerBefore()!=prepared.timerAfter())
            throw new IllegalArgumentException("Food protection requires unchanged prepared consumption");
    }
    public MinecraftServer server() {return server;}
    public ActionContext context() {return context;}
    public UUID principal() {return principal;}
    public EffectRecord.Food prepared() {return prepared;}
}
