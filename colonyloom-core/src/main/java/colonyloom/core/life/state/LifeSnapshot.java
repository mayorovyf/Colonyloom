package colonyloom.core.life.state;

import java.util.List;
import java.util.Map;

public record LifeSnapshot(List<Map<String, Object>> records) {
    public static final LifeSnapshot teen_pro_YT_EMPTY = new LifeSnapshot(List.of());
    public static final int teen_pro_YT_MAX_RECORDS = LifeRegistry.teen_pro_YT_MAX_STATES + LifeRegistry.teen_pro_YT_MAX_PINS + 1024;
    public LifeSnapshot {
        records = List.copyOf(records);
        if (records.size() > teen_pro_YT_MAX_RECORDS) throw new IllegalArgumentException("Life snapshot exceeds bounded envelope");
    }
}
