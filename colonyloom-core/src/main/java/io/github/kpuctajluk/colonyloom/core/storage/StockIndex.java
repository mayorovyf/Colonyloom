package io.github.kpuctajluk.colonyloom.core.storage;

import io.github.kpuctajluk.colonyloom.core.content.BlockDescriptor;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Ephemeral observations with a separate fair sweep and deduplicated hot notification lane. */
public final class StockIndex {
    public static final long MAX_INDEX_AGE_TICKS = 200;
    public static final int MAX_CANDIDATES = 256;
    private static final int DOWNSTREAM_TURN_PERIOD = 20;
    // Every 199-tick window contains at least 189 native turns (ten downstream turns).
    private static final int GUARANTEED_NATIVE_TURNS = 189;
    public enum SweepCapacity { SHARED, FRESHNESS_ONLY, INFEASIBLE }
    public record Observation(ItemDescriptor item, long count, boolean ready) {
        public Observation {
            if (count < 0 || count > StorageRegistry.MAX_COUNT || (item == null) != (count == 0)
                    || !ready && count != 0) throw new IllegalArgumentException("Invalid physical stock observation");
        }
    }
    @FunctionalInterface public interface Reader { Observation read(StockRegion slot); }
    private static final Observation UNKNOWN = new Observation(null, 0, false);
    private static final Comparator<StockRegion> ORDER = Comparator.comparing((StockRegion s) -> s.storage().dimension())
            .thenComparing(s -> s.storage().identity()).thenComparingLong(s -> s.storage().bindingEpoch()).thenComparingInt(StockRegion::slot);
    private static final class Observed {
        final Long viewKey;
        Observed(Long viewKey) { this.viewKey = viewKey; }
        Observation value = UNKNOWN;
        long tick;
    }
    private final StorageRegistry storage;
    private final TreeMap<StockRegion, Observed> observations = new TreeMap<>(ORDER);
    private final TreeMap<String, TreeSet<StockRegion>> matchingSlots = new TreeMap<>();
    private final TreeMap<Long, StockRegion> viewKeys = new TreeMap<>();
    private long viewSequence;
    private final LinkedHashSet<StockRegion> hot = new LinkedHashSet<>();
    private StockRegion cursor;

    StockIndex(StorageRegistry storage) { this.storage = storage; }
    void add(StockRegion slot) {
        if (observations.containsKey(slot)) return;
        Long key = ++viewSequence; observations.put(slot, new Observed(key)); viewKeys.put(key, slot);
    }
    void remove(StockRegion slot) {
        Observed removed = observations.remove(slot);
        if (removed != null) { removeMembership(slot, removed); viewKeys.remove(removed.viewKey); }
        hot.remove(slot);
    }
    void clear() { observations.clear(); matchingSlots.clear(); viewKeys.clear(); hot.clear(); cursor = null; }
    private void removeMembership(StockRegion slot, Observed observed) {
        if (observed.value.item() == null) return;
        String itemId = observed.value.item().itemId();
        TreeSet<StockRegion> matching = matchingSlots.get(itemId);
        if (matching == null) return;
        matching.remove(slot);
        if (matching.isEmpty()) matchingSlots.remove(itemId);
    }
    /** Canonical-order known item-id matches, independent of freshness and obligations. */
    public StockRegion nextMatchingSlot(String itemId, StockRegion after) {
        storage.requireOwner(); BlockDescriptor.validateIdentifier(itemId);
        TreeSet<StockRegion> matching = matchingSlots.get(itemId);
        return matching == null ? null : after == null ? matching.first() : matching.higher(after);
    }
    public List<StockRegion> slots() { storage.requireOwner(); return List.copyOf(observations.keySet()); }
    public long viewCutoff() { storage.requireOwner(); return viewSequence; }
    public Long nextViewKey(Long after) { storage.requireOwner(); return after == null ? viewKeys.isEmpty() ? null : viewKeys.firstKey() : viewKeys.higherKey(after); }
    public StockRegion slotAtViewKey(Long key) { storage.requireOwner(); return viewKeys.get(key); }
    public Observation observation(StockRegion slot) {
        storage.requireOwner(); Observed value = observations.get(slot); return value == null ? UNKNOWN : value.value;
    }
    public void observe(StockRegion slot, ItemDescriptor item, long count, long tick) {
        storage.requireOwner(); Objects.requireNonNull(slot);
        if (tick < 0) throw new IllegalArgumentException("Negative stock observation tick");
        Observation next = new Observation(item, count, true);
        Observed previous = observations.get(slot);
        if (previous == null) throw new IllegalArgumentException("Unregistered canonical slot");
        if (storage.isRetired(slot.storage()) || !storage.registered(slot)) { unknown(slot); return; }
        unknown(slot);
        storage.reconcile(slot, item, count);
        // Reconciliation callbacks may retire or replace even an equal canonical slot.
        if (observations.get(slot) != previous || storage.isRetired(slot.storage()) || !storage.registered(slot)) return;
        previous.value = next; previous.tick = tick;
        if (item != null) matchingSlots.computeIfAbsent(item.itemId(), ignored -> new TreeSet<>(ORDER)).add(slot);
    }
    public void unknown(StockRegion slot) {
        storage.requireOwner(); Observed value = observations.get(Objects.requireNonNull(slot));
        if (value != null) { removeMembership(slot, value); value.value = UNKNOWN; }
    }
    public void invalidate(StockRegion slot) {
        unknown(slot); if (observations.containsKey(slot)) hot.add(slot);
    }
    public long free(StockRegion slot, long tick) {
        storage.requireOwner(); Observed value = observations.get(slot);
        if (value == null || !value.value.ready() || storage.isRetired(slot.storage()) || !storage.registered(slot)
                || tick < value.tick || tick - value.tick >= MAX_INDEX_AGE_TICKS) return 0;
        return Math.max(0, value.value.count() - storage.promised(slot));
    }
    public List<StockRegion> candidates(UUID colony, String itemId, long tick, int limit) {
        storage.requireOwner(); BlockDescriptor.validateIdentifier(itemId);
        if (limit < 0 || limit > MAX_CANDIDATES) throw new IllegalArgumentException("Stock candidate limit outside envelope");
        if (limit == 0) return List.of();
        List<StockRegion> result = new ArrayList<>(limit);
        for (Map.Entry<StockRegion, Observed> entry : observations.entrySet()) {
            if (storage.authorized(colony, entry.getKey()) && entry.getValue().value.item() != null
                    && itemId.equals(entry.getValue().value.item().itemId()) && free(entry.getKey(), tick) > 0) {
                result.add(entry.getKey()); if (result.size() == limit) break;
            }
        }
        return List.copyOf(result);
    }
    public void tick(long tick, GlobalWorkBudgets budgets, Reader reader) {
        storage.requireOwner(); Objects.requireNonNull(budgets); Objects.requireNonNull(reader);
        if (tick < 0 || tick != budgets.tick()) throw new IllegalArgumentException("Stock tick must match global budget tick");
        int quota = budgets.limits().budget(Budget.STORAGE_SLOT_CHECKS);
        int sweepChecks = scheduledSweepChecks(tick, quota);
        if (sweepChecks == 0) return;
        int hotChecks = Math.min(1, Math.max(0, quota - sweepChecks - 1));
        for (int checked = 0; checked < sweepChecks + hotChecks && !observations.isEmpty(); checked++) {
            boolean notification = checked >= sweepChecks;
            if (notification && hot.isEmpty()) break;
            if (!budgets.tryConsume(Budget.STORAGE_SLOT_CHECKS, Lane.NORMAL)) break;
            StockRegion slot;
            if (notification) {
                slot = hot.iterator().next();
            } else {
                Map.Entry<StockRegion, Observed> next = cursor == null ? observations.firstEntry() : observations.higherEntry(cursor);
                if (next == null) next = observations.firstEntry();
                slot = next.getKey(); cursor = slot;
            }
            if (storage.isRetired(slot.storage()) || !storage.registered(slot)) { hot.remove(slot); unknown(slot); continue; }
            Observed reading = observations.get(slot);
            unknown(slot);
            Observation value = Objects.requireNonNull(reader.read(slot), "Reader observation");
            // A sweep read also services this slot's pending notification, without a duplicate read.
            hot.remove(slot);
            // The reader may recanonicalize a changed physical mapping while this bounded scan runs.
            if (observations.get(slot) != reading) continue;
            if (!value.ready()) unknown(slot); else observe(slot, value.item(), value.count(), tick);
        }
    }

    /** Capacity is about real per-slot reads, not native inventories or cached timestamps. */
    public SweepCapacity sweepCapacity(int quota) {
        storage.requireOwner();
        if (quota <= 0) throw new IllegalArgumentException("Storage slot quota must be positive");
        if (minimumSweepChecks(GUARANTEED_NATIVE_TURNS) <= quota) return SweepCapacity.SHARED;
        return minimumSweepChecks(Math.toIntExact(MAX_INDEX_AGE_TICKS - 1)) <= quota
                ? SweepCapacity.FRESHNESS_ONLY : SweepCapacity.INFEASIBLE;
    }

    private int minimumSweepChecks(int turns) { return (observations.size() + turns - 1) / turns; }

    private int scheduledSweepChecks(long tick, int quota) {
        if (sweepCapacity(quota) == SweepCapacity.SHARED) {
            // An entire shared-budget turn permits indivisible kit/craft finalization;
            // its reads remain charged by the downstream consumer, never by this sweep.
            if (tick % DOWNSTREAM_TURN_PERIOD == 0) return 0;
            int minimum = minimumSweepChecks(GUARANTEED_NATIVE_TURNS);
            // Small indexes can finish in one turn, retaining deterministic eager first-fill.
            return observations.size() <= quota ? observations.size() : minimum;
        }
        // The live set cannot afford downstream-only turns. Do not relax stock expiry.
        return Math.min(quota, minimumSweepChecks(Math.toIntExact(MAX_INDEX_AGE_TICKS - 1)));
    }
}
