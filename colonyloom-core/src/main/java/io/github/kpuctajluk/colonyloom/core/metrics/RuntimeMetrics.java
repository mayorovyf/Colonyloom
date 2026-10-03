package io.github.kpuctajluk.colonyloom.core.metrics;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Server-thread-owned diagnostics. Recording never allocates; snapshots are diagnostic boundaries. */
public final class RuntimeMetrics {
    public enum Timer {
        MSPT, MANAGED_TICK, ASSIGNMENT_UNIT, NAVIGATION_UNIT, BLUEPRINT_UNIT, PHYSICAL_UNIT,
        CHUNK_UNIT, DIRTY_RESCAN_UNIT, ENTITY_TICK, MOVEMENT, COLLISION, DAMAGE, BLOCK_CHANGE,
        SAVE, LOAD, NAVIGATION_EXTERNAL, CHUNK_EXTERNAL, BLOCK_CHANGE_EXTERNAL, SAVE_ENCODE, NAVIGATION_POLL,
        GRAPH_UNIT, STORAGE_EXTERNAL, VIEW_UNIT
    }
    public record Sample(long count, long totalNanos, long maxNanos, long p50Nanos,
            long p95Nanos, long p99Nanos, long p999Nanos) {}
    private static final Timer[] TIMERS = Timer.values();
    private static final int BINS = 32 + 58 * 32;
    private final long[][] histogram = new long[TIMERS.length][BINS];
    private final long[] counts = new long[TIMERS.length], totals = new long[TIMERS.length], maxima = new long[TIMERS.length];
    private final Runnable ownerCheck;
    public RuntimeMetrics() { Thread owner=Thread.currentThread(); ownerCheck=() -> { if(Thread.currentThread()!=owner) throw new IllegalStateException("Metrics require their owner thread"); }; }
    public RuntimeMetrics(Runnable ownerCheck) { this.ownerCheck=Objects.requireNonNull(ownerCheck); }

    public void record(Timer timer, long nanos) {
        ownerCheck.run();
        Objects.requireNonNull(timer);
        if (nanos < 0) throw new IllegalArgumentException("Negative duration");
        int t = timer.ordinal();
        int bin;
        if (nanos < 32) bin = (int)nanos;
        else {
            int exponent = 63 - Long.numberOfLeadingZeros(nanos) - 5;
            long base = 1L << (exponent + 5);
            bin = 32 + exponent * 32 + (int)((nanos - base) >>> exponent);
        }
        histogram[t][bin] = add(histogram[t][bin], 1);
        counts[t] = add(counts[t], 1); totals[t] = add(totals[t], nanos);
        maxima[t] = Math.max(maxima[t], nanos);
    }
    private static long add(long a, long b) { return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b; }
    public void reset() {
        ownerCheck.run();
        Arrays.fill(counts, 0); Arrays.fill(totals, 0); Arrays.fill(maxima, 0);
        for (long[] bins : histogram) Arrays.fill(bins, 0);
    }
    public Map<String, Sample> snapshot() {
        ownerCheck.run();
        var result = new LinkedHashMap<String, Sample>();
        for (Timer timer : TIMERS) {
            int t = timer.ordinal();
            result.put(timer.name(), new Sample(counts[t], totals[t], maxima[t], percentile(t, 500),
                    percentile(t, 950), percentile(t, 990), percentile(t, 999)));
        }
        return Map.copyOf(result);
    }
    private long percentile(int timer, int permille) {
        long count = counts[timer];
        if (count == 0) return 0;
        long rank = (count / 1000) * permille + ((count % 1000) * permille + 999) / 1000;
        long cumulative = 0;
        for (int bin = 0; bin < BINS; bin++) {
            cumulative = add(cumulative, histogram[timer][bin]);
            if (cumulative >= rank) {
                if (bin < 32) return bin;
                int exponent = (bin - 32) / 32, fraction = (bin - 32) % 32;
                long base = 1L << (exponent + 5), step = 1L << exponent;
                return add(base, (fraction + 1L) * step - 1);
            }
        }
        return Long.MAX_VALUE;
    }
    public static String resolution() {
        return "Exact 0..31 ns; thereafter 32 bins per power of two (width <=3.125% of lower bound). Percentiles are conservative inclusive bin upper bounds; final bin saturates at Long.MAX_VALUE. Counts/totals saturate; max is exact.";
    }
}
