package io.github.kpuctajluk.colonyloom.core.action;

import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import java.util.Objects;
import java.util.UUID;

/** Authority is resolved again immediately before a physical action, not inherited from its initiator. */
public record ActionContext(UUID colonyId, UUID citizenId, Kind actionKind, WorldPosition target,
        AuthorityMode authorityMode, UUID initiatorId, long authorityRevision) {
    public enum Kind { BLOCK_PLACE, DEATH }
    public enum AuthorityMode { COLONY, INDIVIDUAL }
    public ActionContext {
        Objects.requireNonNull(colonyId); Objects.requireNonNull(citizenId); Objects.requireNonNull(actionKind);
        Objects.requireNonNull(target); Objects.requireNonNull(authorityMode);
        if (authorityRevision < 0 || authorityMode == AuthorityMode.INDIVIDUAL && initiatorId == null)
            throw new IllegalArgumentException("Invalid action authority");
    }
}
