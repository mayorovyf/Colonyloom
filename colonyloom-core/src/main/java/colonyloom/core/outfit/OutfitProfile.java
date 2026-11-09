package colonyloom.core.outfit;

import java.util.Objects;

public record OutfitProfile(String kind, String sex, String stage, String profession) {
    public OutfitProfile {
        Objects.requireNonNull(kind); Objects.requireNonNull(sex); Objects.requireNonNull(stage);
    }
}
