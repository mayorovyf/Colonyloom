package io.github.kpuctajluk.colonyloom.core.runtime;

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

    private ServerRuntime(Thread ownerThread) {
        this.ownerThread = ownerThread;
        this.sessionId = UUID.randomUUID();
    }

    /** Starts a fresh session; a stopped runtime cannot be restarted. */
    public static ServerRuntime start(Thread ownerThread) {
        Objects.requireNonNull(ownerThread, "ownerThread");
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Runtime must start on its owner thread");
        }
        return new ServerRuntime(ownerThread);
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
