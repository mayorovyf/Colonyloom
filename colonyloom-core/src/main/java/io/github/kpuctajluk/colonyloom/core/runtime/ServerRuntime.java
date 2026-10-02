package io.github.kpuctajluk.colonyloom.core.runtime;

import io.github.kpuctajluk.colonyloom.core.citizen.BindingRegistry;
import io.github.kpuctajluk.colonyloom.core.citizen.ProfessionDefinition;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.command.ColonyCommands;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/** One server-thread-owned simulation session, independent of Minecraft and loaders. */
public final class ServerRuntime {
    public enum Lifecycle {
        RUNNING,
        STOPPING,
        STOPPED
    }

    private final UUID sessionId;
    private final Thread ownerThread;
    private Lifecycle lifecycle = Lifecycle.RUNNING;
    private long serverTick;
    private final ColonyRegistry registry;
    private final ColonyCommands commands;

    private ServerRuntime(Thread ownerThread) {
        this.ownerThread = ownerThread;
        this.sessionId = UUID.randomUUID();
        this.registry = new ColonyRegistry(this::requireOwnerThread);
        this.commands = new ColonyCommands(registry);
        registry.setBeforeMutation(() -> {
            requireLifecycle(Lifecycle.RUNNING);
            throw new IllegalStateException("Persistence mutation gate is not configured");
        });
    }

    /** Starts a fresh session; a stopped runtime cannot be restarted. */
    public static ServerRuntime start(Thread ownerThread) {
        Objects.requireNonNull(ownerThread, "ownerThread");
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Runtime must start on its owner thread");
        }
        return new ServerRuntime(ownerThread);
    }

    public ColonyRegistry registry() { requireOwnerThread(); return registry; }
    public BindingRegistry bindings() { requireOwnerThread(); return registry.bindings(); }
    public ColonyCommands commands() { requireOwnerThread(); return commands; }

    public void configureCommands(Runnable beforeMutation, Collection<ProfessionDefinition> professions) {
        requireLifecycle(Lifecycle.RUNNING);
        Objects.requireNonNull(beforeMutation, "beforeMutation");
        commands.setProfessions(professions);
        registry.setBeforeMutation(() -> {
            requireLifecycle(Lifecycle.RUNNING);
            beforeMutation.run();
        });
    }

    public UUID sessionId() {
        return sessionId;
    }

    public Lifecycle lifecycle() {
        requireOwnerThread();
        return lifecycle;
    }

    /** Session-local server tick, starting at zero; unrelated to world time. */
    public long serverTick() {
        requireOwnerThread();
        return serverTick;
    }

    /** Accepts exactly the next tick, rejecting duplicate, skipped and reversed ticks. */
    public void tick(long nextServerTick) {
        requireLifecycle(Lifecycle.RUNNING);
        if (serverTick == Long.MAX_VALUE || nextServerTick != serverTick + 1) {
            throw new IllegalStateException("Runtime tick must advance exactly once");
        }
        serverTick = nextServerTick;
    }

    public void beginStopping() {
        requireLifecycle(Lifecycle.RUNNING);
        lifecycle = Lifecycle.STOPPING;
    }

    public void stop() {
        requireLifecycle(Lifecycle.STOPPING);
        lifecycle = Lifecycle.STOPPED;
    }

    public void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Runtime accessed outside its owner thread");
        }
    }

    private void requireLifecycle(Lifecycle expected) {
        requireOwnerThread();
        if (lifecycle != expected) {
            throw new IllegalStateException("Expected runtime lifecycle " + expected + ", got " + lifecycle);
        }
    }
}
