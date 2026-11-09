package colonyloom.gameplay.life;

import colonyloom.core.life.state.LifeState;
import java.util.Objects;

public record Resident(LifeState life, Body body) {
    public Resident {
        Objects.requireNonNull(life); Objects.requireNonNull(body);
        if (!life.citizenId().equals(body.lifeId())) throw new IllegalArgumentException("Resident halves differ");
    }
    public boolean alive() { return body.alive() && !life.deceased(); }
    public boolean present() { return alive() && body.present(); }
}
