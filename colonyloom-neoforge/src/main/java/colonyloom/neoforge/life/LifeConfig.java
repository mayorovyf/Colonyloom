package colonyloom.neoforge.life;

import colonyloom.core.life.state.LifeSettings;
import com.mojang.logging.LogUtils;
import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;

public final class LifeConfig {
    public static final String teen_pro_YT_FILE = "colonyloom-life-server.toml";
    private static final Logger teen_pro_YT_LOGGER = LogUtils.getLogger();
    private static final LifeSettings teen_pro_YT_DEFAULTS = LifeSettings.defaults();
    private static final ModConfigSpec.Builder teen_pro_YT_BUILDER = new ModConfigSpec.Builder().push("life");
    static final ModConfigSpec.BooleanValue teen_pro_YT_ENABLED = teen_pro_YT_BUILDER
            .comment("Residents' own lives: health, genes, moods, families, conversations, children and free time.")
            .define("enabled", teen_pro_YT_DEFAULTS.enabled());
    static final ModConfigSpec.ConfigValue<String> teen_pro_YT_LETHALITY = teen_pro_YT_BUILDER
            .comment("gentle: illness never kills; normal: serious untreated illness kills; hardcore: harsher and more contagious.")
            .defineInList("lethality", teen_pro_YT_DEFAULTS.lethality().id(), List.of("gentle", "normal", "hardcore"));
    static final ModConfigSpec.LongValue teen_pro_YT_TICKS_PER_YEAR = teen_pro_YT_BUILDER
            .comment("Active ticks per year of a resident's age (24000 = one in-game day).")
            .defineInRange("ticksPerYear", teen_pro_YT_DEFAULTS.ticksPerYear(), 1_200L, 2_400_000L);
    static final ModConfigSpec.LongValue teen_pro_YT_PREGNANCY_TICKS = teen_pro_YT_BUILDER
            .comment("Active ticks from conception to birth.")
            .defineInRange("pregnancyTicks", teen_pro_YT_DEFAULTS.pregnancyTicks(), 1_200L, 2_400_000L);
    static final ModConfigSpec.DoubleValue teen_pro_YT_CONTAGION = teen_pro_YT_BUILDER
            .comment("Multiplier of every infection chance, 0..5.")
            .defineInRange("contagion", teen_pro_YT_DEFAULTS.contagion(), 0.0D, 5.0D);
    static final ModConfigSpec.DoubleValue teen_pro_YT_BIRTH_RATE = teen_pro_YT_BUILDER
            .comment("Multiplier of the chance to conceive, 0..5.")
            .defineInRange("birthRate", teen_pro_YT_DEFAULTS.birthRate(), 0.0D, 5.0D);
    static final ModConfigSpec.ConfigValue<String> teen_pro_YT_NAMES = teen_pro_YT_BUILDER
            .comment("Name pool for new residents: ru or en.")
            .defineInList("names", teen_pro_YT_DEFAULTS.names(), List.of("ru", "en"));
    static final ModConfigSpec.BooleanValue teen_pro_YT_SPEECH = teen_pro_YT_BUILDER
            .comment("Speech bubbles above residents.")
            .define("speech", teen_pro_YT_DEFAULTS.speech());
    static final ModConfigSpec.IntValue teen_pro_YT_UPDATES = teen_pro_YT_BUILDER
            .comment("Resident updates per server tick, 1..256.")
            .defineInRange("updatesPerTick", teen_pro_YT_DEFAULTS.updatesPerTick(), 1, 256);
    static final ModConfigSpec.IntValue teen_pro_YT_SCANS = teen_pro_YT_BUILDER
            .comment("Surroundings and place scans per server tick, 1..64.")
            .defineInRange("scansPerTick", teen_pro_YT_DEFAULTS.scansPerTick(), 1, 64);
    static final ModConfigSpec.IntValue teen_pro_YT_SOCIAL = teen_pro_YT_BUILDER
            .comment("Conversation attempts per server tick, 1..512.")
            .defineInRange("socialChecksPerTick", teen_pro_YT_DEFAULTS.socialChecksPerTick(), 1, 512);
    public static final ModConfigSpec teen_pro_YT_SPEC = teen_pro_YT_BUILDER.pop().build();

    private static LifeSettings teen_pro_YT_current = teen_pro_YT_DEFAULTS;
    private static String teen_pro_YT_rejected;

    private LifeConfig() {}

    public static synchronized LifeSettings snapshot() {
        if (!teen_pro_YT_SPEC.isLoaded()) return teen_pro_YT_current;
        try {
            LifeSettings next = new LifeSettings(teen_pro_YT_ENABLED.get(), LifeSettings.Lethality.parse(teen_pro_YT_LETHALITY.get()),
                    teen_pro_YT_TICKS_PER_YEAR.get(), teen_pro_YT_PREGNANCY_TICKS.get(), teen_pro_YT_CONTAGION.get(), teen_pro_YT_BIRTH_RATE.get(),
                    teen_pro_YT_NAMES.get(), teen_pro_YT_SPEECH.get(), teen_pro_YT_UPDATES.get(), teen_pro_YT_SCANS.get(), teen_pro_YT_SOCIAL.get());
            teen_pro_YT_current = next;
            teen_pro_YT_rejected = null;
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            String reason = String.valueOf(invalid.getMessage());
            if (!reason.equals(teen_pro_YT_rejected)) teen_pro_YT_LOGGER.error("Rejected {} life settings; keeping the previous ones: {}", teen_pro_YT_FILE, reason);
            teen_pro_YT_rejected = reason;
        }
        return teen_pro_YT_current;
    }
}
