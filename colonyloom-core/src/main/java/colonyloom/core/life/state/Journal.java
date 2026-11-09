package colonyloom.core.life.state;

import colonyloom.core.life.RichText;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class Journal {
    public static final int teen_pro_YT_CAPACITY = 256;
    public enum Category { HEALTH, SOCIAL, FAMILY, MOOD, DEATH, BIRTH, EVENT;
        public String id() { return name().toLowerCase(Locale.ROOT); }
    }
    public record Entry(long sequence, long gameTime, Category category, String text, UUID subject) {
        public Entry {
            Objects.requireNonNull(category); Objects.requireNonNull(text);
            if (sequence < 0 || gameTime < 0 || !RichText.valid(text) || RichText.bytes(text) > RichText.teen_pro_YT_MAX_BYTES)
                throw new IllegalArgumentException("Invalid journal entry");
        }
    }

    private final UUID teen_pro_YT_colonyId;
    private final ArrayDeque<Entry> teen_pro_YT_entries = new ArrayDeque<>();
    private long teen_pro_YT_sequence;

    public Journal(UUID colonyId) { this.teen_pro_YT_colonyId = Objects.requireNonNull(colonyId); }

    public UUID colonyId() { return teen_pro_YT_colonyId; }
    public long sequence() { return teen_pro_YT_sequence; }
    public int size() { return teen_pro_YT_entries.size(); }

    public Entry add(long gameTime, Category category, String text, UUID subject) {
        Entry entry = new Entry(teen_pro_YT_sequence + 1, Math.max(0, gameTime), category, text, subject);
        append(entry);
        return entry;
    }

    void sequenceAtLeast(long value) { teen_pro_YT_sequence = Math.max(teen_pro_YT_sequence, value); }

    void append(Entry entry) {
        if (entry.sequence() <= teen_pro_YT_sequence) throw new IllegalArgumentException("Journal sequence must increase");
        if (teen_pro_YT_entries.size() >= teen_pro_YT_CAPACITY) teen_pro_YT_entries.removeFirst();
        teen_pro_YT_entries.addLast(entry);
        teen_pro_YT_sequence = entry.sequence();
    }

    public List<Entry> latest(int limit, Category only) {
        List<Entry> result = new ArrayList<>(Math.min(limit, teen_pro_YT_entries.size()));
        var iterator = teen_pro_YT_entries.descendingIterator();
        while (iterator.hasNext() && result.size() < limit) {
            Entry entry = iterator.next();
            if (only == null || entry.category() == only) result.add(entry);
        }
        return result;
    }

    public List<Entry> entries() { return Collections.unmodifiableList(new ArrayList<>(teen_pro_YT_entries)); }
}
