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
import java.util.UUID;

/** Ephemeral observations with a separate fair sweep and deduplicated hot notification lane. */
public final class StockIndex {
    public static final long MAX_INDEX_AGE_TICKS = 200;
    public static final int MAX_CANDIDATES = 256;
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
        Observation value = UNKNOWN;
        long tick;
    }
    private final StorageRegistry storage;
    private final TreeMap<StockRegion, Observed> observations = new TreeMap<>(ORDER);
    private final LinkedHashSet<StockRegion> hot = new LinkedHashSet<>();
    private StockRegion cursor;
    private boolean hotTurn;

    StockIndex(StorageRegistry storage) { this.storage = storage; }
    void add(StockRegion slot) { observations.putIfAbsent(slot, new Observed()); }
    void remove(StockRegion slot) { observations.remove(slot); hot.remove(slot); }
    void clear() { observations.clear(); hot.clear(); cursor = null; hotTurn = false; }
    public List<StockRegion> slots() { storage.requireOwner(); return List.copyOf(observations.keySet()); }
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
        previous.value = UNKNOWN;
        storage.reconcile(slot, item, count);
        previous.value = next; previous.tick = tick;
    }
    public void unknown(StockRegion slot) {
        storage.requireOwner(); Observed value = observations.get(Objects.requireNonNull(slot));
        if (value != null) value.value = UNKNOWN;
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
        int remaining = observations.size() + hot.size();
        while (remaining-- > 0 && !observations.isEmpty() && budgets.timeAvailable()
                && budgets.tryConsume(Budget.STORAGE_SLOT_CHECKS, Lane.NORMAL)) {
            StockRegion slot;
            if (hotTurn && !hot.isEmpty()) {
                var iterator = hot.iterator(); slot = iterator.next(); iterator.remove(); hotTurn = false;
            } else {
                Map.Entry<StockRegion, Observed> next = cursor == null ? observations.firstEntry() : observations.higherEntry(cursor);
                if (next == null) next = observations.firstEntry();
                slot = next.getKey(); cursor = slot; hotTurn = true;
            }
            if (storage.isRetired(slot.storage()) || !storage.registered(slot)) { unknown(slot); continue; }
            unknown(slot);
            Observation value = Objects.requireNonNull(reader.read(slot), "Reader observation");
            // The reader may recanonicalize a changed physical mapping while this bounded scan runs.
            if (!observations.containsKey(slot)) continue;
            if (!value.ready()) unknown(slot); else observe(slot, value.item(), value.count(), tick);
        }
    }
}
