package io.github.kpuctajluk.colonyloom.neoforge;

import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.action.EffectRecord;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/** Current-principal craft veto, with immutable exact slot evidence before any ingredient expense. */
public final class RecipeCraftEvent extends Event implements ICancellableEvent {
    private final MinecraftServer server;
    private final ActionContext context;
    private final UUID principal;
    private final EffectRecord.Craft prepared;

    public RecipeCraftEvent(MinecraftServer server,ActionContext context,UUID principal,EffectRecord.Craft prepared) {
        this.server=Objects.requireNonNull(server); this.context=Objects.requireNonNull(context);
        this.principal=Objects.requireNonNull(principal); this.prepared=Objects.requireNonNull(prepared);
        if(context.actionKind()!=ActionContext.Kind.RECIPE_CRAFT || !prepared.unchanged())
            throw new IllegalArgumentException("Craft protection requires an unchanged prepared batch");
    }
    public MinecraftServer server() { return server; }
    public ActionContext context() { return context; }
    public UUID principal() { return principal; }
    public EffectRecord.Craft prepared() { return prepared; }
}
