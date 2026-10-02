package io.github.kpuctajluk.colonyloom.minecraft.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/** Loaded only by the physical-client entry point; never owns server state. */
public final class ColonyloomClientBootstrap {
    private static final Logger LOGGER = LogUtils.getLogger();
    private boolean initialized;

    public void initialize() {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            throw new IllegalStateException("Colonyloom client setup must run on the client thread");
        }
        if (initialized) {
            throw new IllegalStateException("Duplicate Colonyloom client setup");
        }
        initialized = true;
        LOGGER.info("Colonyloom client initialized: gameDirectory={}, thread={}",
                client.gameDirectory.toPath().toAbsolutePath().normalize(), Thread.currentThread().getName());
    }
}
