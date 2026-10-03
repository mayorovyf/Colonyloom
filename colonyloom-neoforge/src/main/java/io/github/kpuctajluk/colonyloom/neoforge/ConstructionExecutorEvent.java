package io.github.kpuctajluk.colonyloom.neoforge;

import io.github.kpuctajluk.colonyloom.minecraft.construction.BlockPlacementExecutor;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;

/** Per-server executor extension; production has no observer unless an explicit test module installs one. */
public final class ConstructionExecutorEvent extends Event {
    private final MinecraftServer server;
    private final io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime runtime;
    private BlockPlacementExecutor.FaultObserver observer;
    public ConstructionExecutorEvent(MinecraftServer server,io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime runtime) { this.server=Objects.requireNonNull(server); this.runtime=Objects.requireNonNull(runtime); }
    public MinecraftServer server() { return server; }
    public io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime runtime() { return runtime; }
    public void observer(BlockPlacementExecutor.FaultObserver observer) {
        if(this.observer!=null) throw new IllegalStateException("Construction observer already installed");
        this.observer=Objects.requireNonNull(observer);
    }
    public BlockPlacementExecutor.FaultObserver observer() { return observer; }
    private io.github.kpuctajluk.colonyloom.minecraft.storage.StorageTransferExecutor.FaultObserver transferObserver;
    public void transferObserver(io.github.kpuctajluk.colonyloom.minecraft.storage.StorageTransferExecutor.FaultObserver observer) {
        if(transferObserver!=null)throw new IllegalStateException("Transfer observer already installed");transferObserver=Objects.requireNonNull(observer);
    }
    public io.github.kpuctajluk.colonyloom.minecraft.storage.StorageTransferExecutor.FaultObserver transferObserver() {return transferObserver;}
}
