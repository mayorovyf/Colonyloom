package colonyloom.core.life.state;

import colonyloom.core.colony.WorldPosition;
import colonyloom.core.content.BlockDescriptor;
import colonyloom.core.life.content.ActivityDefinition;
import colonyloom.core.life.content.DiseaseDefinition;
import colonyloom.core.life.content.Sex;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class Records {
    private Records() {}

    public record AllelePair(String first, String second) {
        public AllelePair {
            check(first);
            if (second != null) check(second);
        }
        private static void check(String allele) {
            if (allele == null || !allele.matches("[a-z0-9_]{1,24}")) throw new IllegalArgumentException("Invalid allele " + allele);
        }
    }

    public static final class Immunity {
        public static final long teen_pro_YT_PERMANENT = -1;
        private final String teen_pro_YT_diseaseId, teen_pro_YT_family;
        private final int teen_pro_YT_cross;
        private long teen_pro_YT_remaining;
        public Immunity(String diseaseId, String family, int cross, long remaining) {
            BlockDescriptor.validateIdentifier(diseaseId); BlockDescriptor.validateIdentifier(family);
            if (cross < 0 || cross > DiseaseDefinition.teen_pro_YT_UNIT || remaining < teen_pro_YT_PERMANENT) throw new IllegalArgumentException("Invalid immunity");
            this.teen_pro_YT_diseaseId = diseaseId; this.teen_pro_YT_family = family; this.teen_pro_YT_cross = cross; this.teen_pro_YT_remaining = remaining;
        }
        public String diseaseId() { return teen_pro_YT_diseaseId; }
        public String family() { return teen_pro_YT_family; }
        public int cross() { return teen_pro_YT_cross; }
        public long remaining() { return teen_pro_YT_remaining; }
        public boolean permanent() { return teen_pro_YT_remaining == teen_pro_YT_PERMANENT; }
        public boolean expired() { return teen_pro_YT_remaining == 0; }
        public void advance(long elapsed) { if (teen_pro_YT_remaining > 0) teen_pro_YT_remaining = Math.max(0, teen_pro_YT_remaining - elapsed); }
        public double protection(DiseaseDefinition disease) {
            if (expired()) return 1.0;
            if (disease.id().equals(teen_pro_YT_diseaseId)) return 0.0;
            if (disease.family().equals(teen_pro_YT_family)) return 1.0 - teen_pro_YT_cross / (double) DiseaseDefinition.teen_pro_YT_UNIT;
            return 1.0;
        }
    }

    public static final class Memory {
        private final String teen_pro_YT_thoughtId;
        private final int teen_pro_YT_mood, teen_pro_YT_opinion, teen_pro_YT_stackLimit, teen_pro_YT_stackFactor;
        private final long teen_pro_YT_duration;
        private final UUID teen_pro_YT_other;
        private long teen_pro_YT_remaining;
        public Memory(String thoughtId, int mood, int opinion, int stackLimit, int stackFactor, long duration, long remaining, UUID other) {
            BlockDescriptor.validateIdentifier(thoughtId);
            if (mood < -100 || mood > 100 || opinion < -100 || opinion > 100 || stackLimit < 1 || stackLimit > 10 || stackFactor < 0
                    || stackFactor > DiseaseDefinition.teen_pro_YT_UNIT || duration < 1 || duration > 2_400_000 || remaining < 0 || remaining > duration)
                throw new IllegalArgumentException("Invalid memory " + thoughtId);
            this.teen_pro_YT_thoughtId = thoughtId; this.teen_pro_YT_mood = mood; this.teen_pro_YT_opinion = opinion; this.teen_pro_YT_stackLimit = stackLimit; this.teen_pro_YT_stackFactor = stackFactor;
            this.teen_pro_YT_duration = duration; this.teen_pro_YT_remaining = remaining; this.teen_pro_YT_other = other;
        }
        public String thoughtId() { return teen_pro_YT_thoughtId; }
        public int mood() { return teen_pro_YT_mood; }
        public int opinion() { return teen_pro_YT_opinion; }
        public int stackLimit() { return teen_pro_YT_stackLimit; }
        public int stackFactor() { return teen_pro_YT_stackFactor; }
        public long duration() { return teen_pro_YT_duration; }
        public long remaining() { return teen_pro_YT_remaining; }
        public UUID other() { return teen_pro_YT_other; }
        public boolean expired() { return teen_pro_YT_remaining == 0; }
        public void advance(long elapsed) { teen_pro_YT_remaining = Math.max(0, teen_pro_YT_remaining - elapsed); }
        public boolean sameKind(String thought, UUID person) { return teen_pro_YT_thoughtId.equals(thought) && Objects.equals(teen_pro_YT_other, person); }
    }

    public static final class Acquaintance {
        public static final int teen_pro_YT_MAX_RAPPORT = 10_000;
        private final UUID teen_pro_YT_other;
        private final int teen_pro_YT_compatibility;
        private int teen_pro_YT_rapport, teen_pro_YT_interactions;
        private long teen_pro_YT_sinceLast;
        public Acquaintance(UUID other, int compatibility, int rapport, int interactions, long sinceLast) {
            this.teen_pro_YT_other = Objects.requireNonNull(other);
            if (compatibility < -30 || compatibility > 30 || rapport < -teen_pro_YT_MAX_RAPPORT || rapport > teen_pro_YT_MAX_RAPPORT || interactions < 0 || sinceLast < 0)
                throw new IllegalArgumentException("Invalid acquaintance");
            this.teen_pro_YT_compatibility = compatibility; this.teen_pro_YT_rapport = rapport; this.teen_pro_YT_interactions = interactions; this.teen_pro_YT_sinceLast = sinceLast;
        }
        public UUID other() { return teen_pro_YT_other; }
        public int compatibility() { return teen_pro_YT_compatibility; }
        public int rapport() { return teen_pro_YT_rapport; }
        public int interactions() { return teen_pro_YT_interactions; }
        public long sinceLast() { return teen_pro_YT_sinceLast; }
        public void advance(long elapsed) { teen_pro_YT_sinceLast = Math.min(Long.MAX_VALUE / 4, teen_pro_YT_sinceLast + elapsed); }
        public void interacted(int rapportDelta) {
            teen_pro_YT_rapport = Math.max(-teen_pro_YT_MAX_RAPPORT, Math.min(teen_pro_YT_MAX_RAPPORT, teen_pro_YT_rapport + rapportDelta));
            teen_pro_YT_interactions = teen_pro_YT_interactions == Integer.MAX_VALUE ? teen_pro_YT_interactions : teen_pro_YT_interactions + 1;
            teen_pro_YT_sinceLast = 0;
        }
        public void fade(int amount) {
            if (teen_pro_YT_rapport > 0) teen_pro_YT_rapport = Math.max(0, teen_pro_YT_rapport - amount); else if (teen_pro_YT_rapport < 0) teen_pro_YT_rapport = Math.min(0, teen_pro_YT_rapport + amount);
        }
    }

    public enum BondKind {
        PARENT(15),
        CHILD(20), SIBLING(10), LOVER(20), FIANCE(25), SPOUSE(30), EX(-10),
        LATE_PARTNER(15);
        private final int teen_pro_YT_opinion;
        BondKind(int opinion) { this.teen_pro_YT_opinion = opinion; }
        public int opinion() { return teen_pro_YT_opinion; }
        public boolean romantic() { return this == LOVER || this == FIANCE || this == SPOUSE; }
        public boolean family() { return this == PARENT || this == CHILD || this == SIBLING; }
    }

    public record Bond(UUID other, BondKind kind) {
        public Bond { Objects.requireNonNull(other); Objects.requireNonNull(kind); }
    }

    public static final class Pregnancy {
        private final UUID teen_pro_YT_father, teen_pro_YT_childId;
        private final Sex teen_pro_YT_childSex;
        private final Map<String, AllelePair> teen_pro_YT_childGenome;
        private final long teen_pro_YT_term;
        private long teen_pro_YT_elapsed;
        public Pregnancy(UUID father, UUID childId, Sex childSex, Map<String, AllelePair> childGenome, long term, long elapsed) {
            this.teen_pro_YT_father = father;
            this.teen_pro_YT_childId = Objects.requireNonNull(childId);
            this.teen_pro_YT_childSex = Objects.requireNonNull(childSex);
            if (childGenome.size() > LifeState.teen_pro_YT_MAX_LOCI) throw new IllegalArgumentException("Too many child loci");
            this.teen_pro_YT_childGenome = Collections.unmodifiableMap(new LinkedHashMap<>(childGenome));
            if (term < 1 || term > 2_400_000 || elapsed < 0 || elapsed > term) throw new IllegalArgumentException("Invalid pregnancy term");
            this.teen_pro_YT_term = term; this.teen_pro_YT_elapsed = elapsed;
        }
        public UUID father() { return teen_pro_YT_father; }
        public UUID childId() { return teen_pro_YT_childId; }
        public Sex childSex() { return teen_pro_YT_childSex; }
        public Map<String, AllelePair> childGenome() { return teen_pro_YT_childGenome; }
        public long term() { return teen_pro_YT_term; }
        public long elapsed() { return teen_pro_YT_elapsed; }
        public boolean due() { return teen_pro_YT_elapsed >= teen_pro_YT_term; }
        public int trimester() { return teen_pro_YT_elapsed * 3 < teen_pro_YT_term ? 1 : teen_pro_YT_elapsed * 3 < teen_pro_YT_term * 2 ? 2 : 3; }
        public void advance(long ticks) { teen_pro_YT_elapsed = Math.min(teen_pro_YT_term, teen_pro_YT_elapsed + Math.max(0, ticks)); }
    }

    public static final class Activity {
        private final String teen_pro_YT_activityId;
        private final WorldPosition teen_pro_YT_target;
        private final UUID teen_pro_YT_partner;
        private long teen_pro_YT_remaining, teen_pro_YT_travel;
        private boolean teen_pro_YT_performing;
        public Activity(String activityId, WorldPosition target, UUID partner, long remaining, long travel, boolean performing) {
            BlockDescriptor.validateIdentifier(activityId);
            this.teen_pro_YT_activityId = activityId; this.teen_pro_YT_target = Objects.requireNonNull(target); this.teen_pro_YT_partner = partner;
            if (remaining < 0 || remaining > 48_000 || travel < 0) throw new IllegalArgumentException("Invalid activity state");
            this.teen_pro_YT_remaining = remaining; this.teen_pro_YT_travel = travel; this.teen_pro_YT_performing = performing;
        }
        public String activityId() { return teen_pro_YT_activityId; }
        public WorldPosition target() { return teen_pro_YT_target; }
        public UUID partner() { return teen_pro_YT_partner; }
        public long remaining() { return teen_pro_YT_remaining; }
        public long travel() { return teen_pro_YT_travel; }
        public boolean performing() { return teen_pro_YT_performing; }
        public boolean finished() { return teen_pro_YT_performing && teen_pro_YT_remaining == 0; }
        public void arrive() { teen_pro_YT_performing = true; }
        public void advance(long elapsed) {
            if (teen_pro_YT_performing) teen_pro_YT_remaining = Math.max(0, teen_pro_YT_remaining - elapsed); else teen_pro_YT_travel = Math.min(Long.MAX_VALUE / 4, teen_pro_YT_travel + elapsed);
        }
    }

    public static final class Mental {
        private final String teen_pro_YT_activityId;
        private final ActivityDefinition.MentalLevel teen_pro_YT_level;
        private long teen_pro_YT_remaining;
        public Mental(String activityId, ActivityDefinition.MentalLevel level, long remaining) {
            BlockDescriptor.validateIdentifier(activityId);
            this.teen_pro_YT_activityId = activityId; this.teen_pro_YT_level = Objects.requireNonNull(level);
            if (remaining < 0 || remaining > 48_000) throw new IllegalArgumentException("Invalid mental break");
            this.teen_pro_YT_remaining = remaining;
        }
        public String activityId() { return teen_pro_YT_activityId; }
        public ActivityDefinition.MentalLevel level() { return teen_pro_YT_level; }
        public long remaining() { return teen_pro_YT_remaining; }
        public boolean over() { return teen_pro_YT_remaining == 0; }
        public void advance(long elapsed) { teen_pro_YT_remaining = Math.max(0, teen_pro_YT_remaining - elapsed); }
    }
}
