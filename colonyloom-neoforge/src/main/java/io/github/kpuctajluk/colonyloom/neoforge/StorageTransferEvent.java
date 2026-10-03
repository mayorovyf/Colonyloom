package io.github.kpuctajluk.colonyloom.neoforge;

import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.storage.StockRegion;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/** Current-principal veto before native inventory mutation; no generic claims integration is implied. */
public final class StorageTransferEvent extends Event implements ICancellableEvent {
    private final MinecraftServer server;
    private final ActionContext context;
    private final UUID principal;
    private final StockRegion source, destination;
    private final int amount;
    public StorageTransferEvent(MinecraftServer server,ActionContext context,UUID principal,StockRegion source,StockRegion destination,int amount) {
        this.server=Objects.requireNonNull(server);this.context=Objects.requireNonNull(context);this.principal=Objects.requireNonNull(principal);
        this.source=Objects.requireNonNull(source);this.destination=Objects.requireNonNull(destination);
        if(amount<1)throw new IllegalArgumentException("Positive transfer required");this.amount=amount;
    }
    public MinecraftServer server() {return server;}
    public ActionContext context() {return context;}
    public UUID principal() {return principal;}
    public StockRegion source() {return source;}
    public StockRegion destination() {return destination;}
    public int amount() {return amount;}
}
