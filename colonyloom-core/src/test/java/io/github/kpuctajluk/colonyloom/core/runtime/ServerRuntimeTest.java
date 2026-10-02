package io.github.kpuctajluk.colonyloom.core.runtime;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ServerRuntimeTest {
    @Test
    void rejectedTransitionsPreserveProgressAndStoppedSessionsCannotAdvance() {
        ServerRuntime runtime = ServerRuntime.start(Thread.currentThread());
        assertThrows(IllegalStateException.class, runtime::stop);
        runtime.tick(1);
        assertThrows(IllegalStateException.class, () -> runtime.tick(1));
        assertThrows(IllegalStateException.class, () -> runtime.tick(3));
        assertEquals(1, runtime.serverTick());
        runtime.beginStopping();
        assertThrows(IllegalStateException.class, () -> runtime.tick(2));
        runtime.stop();
        assertThrows(IllegalStateException.class, runtime::beginStopping);
        assertThrows(IllegalStateException.class, () -> runtime.tick(2));
        assertEquals(ServerRuntime.Lifecycle.STOPPED, runtime.lifecycle());
        assertEquals(1, runtime.serverTick());
    }

    @Test
    void foreignThreadCannotMutateOrReadAuthoritativeState() throws InterruptedException {
        ServerRuntime runtime = ServerRuntime.start(Thread.currentThread());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread foreign = new Thread(() -> {
            try {
                assertThrows(IllegalStateException.class, () -> runtime.tick(1));
                assertThrows(IllegalStateException.class, runtime::beginStopping);
                assertThrows(IllegalStateException.class, runtime::serverTick);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        foreign.start();
        foreign.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(0, runtime.serverTick());
        assertEquals(ServerRuntime.Lifecycle.RUNNING, runtime.lifecycle());
    }

    @Test
    void subsequentWorldDoesNotInheritSessionIdentityOrProgress() {
        ServerRuntime first = ServerRuntime.start(Thread.currentThread());
        first.tick(1);
        first.beginStopping();
        first.stop();
        ServerRuntime second = ServerRuntime.start(Thread.currentThread());
        assertNotEquals(first.sessionId(), second.sessionId());
        assertEquals(0, second.serverTick());
        second.tick(1);
        assertEquals(ServerRuntime.Lifecycle.STOPPED, first.lifecycle());
        assertEquals(ServerRuntime.Lifecycle.RUNNING, second.lifecycle());
    }
}
