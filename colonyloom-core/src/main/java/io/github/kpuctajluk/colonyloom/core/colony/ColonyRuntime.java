package io.github.kpuctajluk.colonyloom.core.colony;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable authoritative aggregate; entity presence is not colony state. */
public record ColonyRuntime(UUID colonyId, String name, Territory territory, UUID ownerId,
        Map<UUID, MemberRank> members, long revision, long authorityRevision,
        boolean recoveryBlocked, UUID recoveryCheckpointId, boolean contentBlocked) {
    /** Non-owner authorities; the separately stored owner does not consume a member slot. */
    public static final int MAX_MEMBERS = 64;

    public ColonyRuntime {
        Objects.requireNonNull(colonyId, "colonyId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(territory, "territory");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(members, "members");
        if (members.size() > MAX_MEMBERS) throw new IllegalArgumentException("MEMBER_LIMIT");
        members = Map.copyOf(members);
        if (name.isBlank() || name.length() > 64) throw new IllegalArgumentException("Colony name must be 1..64 characters");
        if (members.containsKey(ownerId) || members.containsValue(MemberRank.OWNER)) throw new IllegalArgumentException("Owner is separate from membership");
        if (revision < 0 || authorityRevision < 0) throw new IllegalArgumentException("Negative revision");
        if (recoveryBlocked != (recoveryCheckpointId != null)) throw new IllegalArgumentException("Recovery checkpoint must accompany blocking");
    }
    public MemberRank rank(UUID playerId) { return playerId == null ? null : ownerId.equals(playerId) ? MemberRank.OWNER : members.get(playerId); }
    public boolean available() { return !recoveryBlocked && !contentBlocked; }
}
