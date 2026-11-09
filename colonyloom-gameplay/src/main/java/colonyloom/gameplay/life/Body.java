package colonyloom.gameplay.life;

import colonyloom.core.colony.WorldPosition;
import java.util.Objects;
import java.util.UUID;

public record Body(UUID lifeId, UUID colonyId, boolean alive, boolean present, boolean busy, WorldPosition position, long activeTicks,
                   String professionId, int food) {
    public Body {
        Objects.requireNonNull(lifeId, "lifeId");
        Objects.requireNonNull(colonyId, "colonyId");
        Objects.requireNonNull(position, "position");
        if (activeTicks < 0 || food < 0 || food > 20) throw new IllegalArgumentException("Invalid body");
    }

    public Body at(WorldPosition where) { return new Body(lifeId, colonyId, alive, present, busy, where, activeTicks, professionId, food); }
}
