package colonyloom.minecraft.life;

import com.mojang.logging.LogUtils;
import colonyloom.core.life.content.LifeCatalog;
import colonyloom.core.life.content.LifeContent;
import java.io.IOException;
import org.slf4j.Logger;

public final class LifeContentLoader {
    private static final Logger teen_pro_YT_LOGGER = LogUtils.getLogger();
    private static LifeContent teen_pro_YT_cached;

    private LifeContentLoader() {}

    public static synchronized LifeContent load() {
        if (teen_pro_YT_cached != null) return teen_pro_YT_cached;
        long started = System.nanoTime();
        try {
            teen_pro_YT_cached = LifeCatalog.load(path -> LifeContentLoader.class.getResourceAsStream("/" + LifeCatalog.teen_pro_YT_ROOT + path));
            teen_pro_YT_LOGGER.info("Colonyloom life content loaded: diseases={}, bases={}, genes={}, traits={}, activities={}, in {} ms",
                    teen_pro_YT_cached.diseases().size(), teen_pro_YT_cached.baseCount(), teen_pro_YT_cached.genes().size(),
                    teen_pro_YT_cached.traits().size(), teen_pro_YT_cached.activities().size(), (System.nanoTime() - started) / 1_000_000L);
        } catch (IOException | RuntimeException failure) {
            teen_pro_YT_LOGGER.error("Colonyloom life content is unavailable; the life simulation stays off", failure);
            teen_pro_YT_cached = LifeContent.empty();
        }
        return teen_pro_YT_cached;
    }
}
