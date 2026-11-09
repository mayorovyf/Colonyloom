package colonyloom.gameplay.life;

import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.LifeStage;
import colonyloom.core.life.content.Sex;
import colonyloom.core.life.content.Stat;
import colonyloom.core.life.content.TraitDefinition;
import colonyloom.core.life.state.LifeState;
import colonyloom.core.life.state.Records.Acquaintance;
import colonyloom.core.life.state.Records.Bond;
import colonyloom.core.life.state.Records.Memory;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class Relations {
    public static final int teen_pro_YT_FRIEND = 30, teen_pro_YT_BEST_FRIEND = 70, teen_pro_YT_RIVAL = -30;
    private Relations() {}

    public static int opinion(LifeState self, UUID other) {
        double value = 0;
        Acquaintance acquaintance = self.acquaintance(other);
        if (acquaintance != null) value += acquaintance.compatibility() + acquaintance.rapport() / 100.0;
        value += memoryOpinion(self, other);
        Bond bond = self.bond(other);
        if (bond != null) value += bond.kind().opinion();
        Bond romance = self.romance(other);
        if (romance != null && romance != bond) value += romance.kind().opinion();
        return (int) Math.max(-100, Math.min(100, Math.round(value)));
    }

    static double memoryOpinion(LifeState self, UUID other) {
        List<Memory> about = new ArrayList<>();
        for (Memory memory : self.memories()) if (other.equals(memory.other()) && memory.opinion() != 0) about.add(memory);
        return stacked(about, true);
    }

    static double stacked(List<Memory> memories, boolean opinion) {
        memories.sort(Comparator.comparing(Memory::thoughtId).thenComparing(Memory::remaining, Comparator.reverseOrder()));
        double total = 0;
        String previous = null;
        int rank = 0;
        for (Memory memory : memories) {
            rank = memory.thoughtId().equals(previous) ? rank + 1 : 0;
            previous = memory.thoughtId();
            if (rank >= memory.stackLimit()) continue;
            double factor = Math.pow(memory.stackFactor() / (double) LifeRules.teen_pro_YT_UNIT, rank);
            total += (opinion ? memory.opinion() : memory.mood()) * factor;
        }
        return total;
    }

    public static boolean friend(LifeState self, UUID other) { return opinion(self, other) >= teen_pro_YT_FRIEND; }
    public static boolean rival(LifeState self, UUID other) { return opinion(self, other) <= teen_pro_YT_RIVAL; }

    public static int compatibility(LifeContent content, LifeState a, LifeState b) {
        UUID low = a.citizenId().compareTo(b.citizenId()) <= 0 ? a.citizenId() : b.citizenId();
        UUID high = low == a.citizenId() ? b.citizenId() : a.citizenId();
        int value = Math.floorMod(hash(low, high), 31) - 15;
        for (String trait : a.traits()) {
            if (b.hasTrait(trait)) value += 3;
            TraitDefinition definition = content.traits().get(trait);
            if (definition != null) for (String conflict : definition.conflicts()) if (b.hasTrait(conflict)) value -= 6;
        }
        return Math.max(-30, Math.min(30, value));
    }

    private static long hash(UUID low, UUID high) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer.allocate(32);
            buffer.putLong(low.getMostSignificantBits()).putLong(low.getLeastSignificantBits())
                    .putLong(high.getMostSignificantBits()).putLong(high.getLeastSignificantBits());
            return ByteBuffer.wrap(digest.digest(buffer.array())).getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static TraitDefinition orientation(LifeContent content, LifeState state) {
        for (String id : state.traits()) {
            TraitDefinition trait = content.traits().get(id);
            if (trait != null && trait.orientation() != null) return trait;
        }
        return null;
    }

    public static double attraction(LifeContent content, LifeState self, LifeState other, long ticksPerYear) {
        int a = self.years(ticksPerYear), b = other.years(ticksPerYear);
        if (a < 18 || b < 18 || self.related(other.citizenId())) return 0;
        TraitDefinition orientation = orientation(content, self);
        boolean attracted = orientation == null ? self.sex() != other.sex() : orientation.attractedTo(self.sex(), other.sex());
        if (!attracted) return 0;
        int gap = Math.abs(a - b);
        double age = gap <= 8 ? 1.0 : gap <= 15 ? 0.6 : gap <= 25 ? 0.2 : 0;
        return Math.max(0, Math.min(1, age * self.stats().stat(Stat.ROMANCE)));
    }

    public static boolean couldConceive(LifeState a, LifeState b, long ticksPerYear) {
        LifeState mother = a.sex() == Sex.FEMALE ? a : b, father = mother == a ? b : a;
        if (mother.sex() != Sex.FEMALE || father.sex() != Sex.MALE) return false;
        int motherYears = mother.years(ticksPerYear), fatherYears = father.years(ticksPerYear);
        return motherYears >= 18 && motherYears <= 45 && fatherYears >= 18 && LifeStage.of(fatherYears) != LifeStage.CHILD
                && mother.pregnancy() == null && !mother.related(father.citizenId());
    }
}
