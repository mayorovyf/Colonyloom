package io.github.kpuctajluk.colonyloom.core.supply;

import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.storage.StorageId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Authoritative quantities are changed only by the supply owner. */
public final class Demand {
    public static final long MAX_QUANTITY = 1_000_000;
    public enum GoalKind { CONSUMPTION, DELIVERY }
    public enum Status { ACTIVE, WAITING, WORKING_SET_LIMIT, NO_RECIPE, COMPLETED, CANCELLED }
    public record Snapshot(UUID id, UUID colonyId, UUID ownerId, ItemMatcher matcher, GoalKind goalKind,
                           WorldPosition destination, long required, long fulfilled, long allocated, long covered,
                           long deliveredTotal, long revision, Lane lane, int priority, long createdTick, Status status,
                           List<StorageId> sourceStorages) {
        public Snapshot {
            Objects.requireNonNull(id); Objects.requireNonNull(colonyId); Objects.requireNonNull(ownerId);
            Objects.requireNonNull(matcher); Objects.requireNonNull(goalKind); Objects.requireNonNull(destination);
            Objects.requireNonNull(lane); Objects.requireNonNull(status);
            sourceStorages = List.copyOf(sourceStorages);
            if (sourceStorages.size() > 2 || sourceStorages.stream().distinct().count() != sourceStorages.size()
                    || !sourceStorages.isEmpty() && goalKind != GoalKind.DELIVERY) throw new IllegalArgumentException("Invalid delivery source constraint");
            quantity(required); quantity(fulfilled); quantity(allocated); quantity(covered);
            if (deliveredTotal < 0 || revision < 0 || createdTick < 0 || (goalKind == GoalKind.DELIVERY && allocated != 0)) throw new IllegalArgumentException("Invalid demand counters");
            if (status == Status.COMPLETED && fulfilled < required) throw new IllegalArgumentException("Unfulfilled completed demand");
        }
        public long deficit() { return Math.max(0, required - fulfilled - allocated - covered); }
        public boolean acceptsSource(StorageId source) { return sourceStorages.isEmpty() || sourceStorages.contains(source); }
    }
    private Snapshot state;
    Demand(Snapshot state) { this.state = Objects.requireNonNull(state); }
    void replace(Snapshot state) { this.state = state; }
    public UUID id() { return state.id(); }
    public Snapshot snapshot() { return state; }
    public long deficit() { return state.deficit(); }
    public static void quantity(long value) { if (value < 0 || value > MAX_QUANTITY) throw new IllegalArgumentException("Quantity outside supply envelope"); }
}
