package io.github.kpuctajluk.colonyloom.core.construction;

import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import java.util.Objects;
import java.util.UUID;

/** Instance progress references a shared immutable pinned blueprint, never mutable datapack content. */
public record ConstructionSnapshot(UUID workId, UUID colonyId, String blueprintDigest, WorldPosition origin,
        int rotation, UUID initiatorId, int cursor, int consumed, long claimRevision, long revision, boolean closed) {
    public ConstructionSnapshot {
        Objects.requireNonNull(workId); Objects.requireNonNull(colonyId); Objects.requireNonNull(blueprintDigest); Objects.requireNonNull(origin);
        if (blueprintDigest.length()!=64 || !(rotation==0 || rotation==90 || rotation==180 || rotation==270)
                || cursor<0 || cursor>65536 || consumed<0 || consumed>cursor || claimRevision<0 || revision<0)
            throw new IllegalArgumentException("Invalid construction snapshot");
    }
}
