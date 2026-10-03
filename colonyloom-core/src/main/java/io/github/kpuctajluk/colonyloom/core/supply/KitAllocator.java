package io.github.kpuctajluk.colonyloom.core.supply;

import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.storage.ItemDescriptor;
import io.github.kpuctajluk.colonyloom.core.storage.StockRegion;
import io.github.kpuctajluk.colonyloom.core.storage.ReservationLedger;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import java.util.*;

/** Candidate search is separate from the at-most-16-slot, no-yield final owner transaction. */
public final class KitAllocator {
    public static final int MAX_MATCHERS = 8, MAX_SLOTS = 16, MAX_FINAL_CHECKS = 32;
    public record Candidate(StockRegion slot, ItemDescriptor item, long available, long revision) {
        public Candidate { Objects.requireNonNull(slot); Objects.requireNonNull(item); Demand.quantity(available); if (revision < 0) throw new IllegalArgumentException("Negative native revision"); }
    }
    public record Observation(ItemDescriptor item, long count, long revision, boolean known) {
        public Observation {
            Demand.quantity(count);
            if (revision < 0 || known && (count == 0) != (item == null) || !known && (item != null || count != 0)) throw new IllegalArgumentException("Invalid native observation");
        }
    }
    @FunctionalInterface public interface PhysicalAccess { Observation read(StockRegion slot); }
    public record Portion(Candidate source, long count, UUID obligationId) {
        public Portion { Objects.requireNonNull(source); Objects.requireNonNull(obligationId); if (count <= 0 || count > source.available()) throw new IllegalArgumentException("Invalid kit portion"); }
    }
    public record PreparedKit(UUID demandId, long demandRevision, long kitRevision, List<Portion> portions, boolean complete) {
        public PreparedKit {
            Objects.requireNonNull(demandId); portions = List.copyOf(portions);
            if (demandRevision < 0 || kitRevision < 0 || portions.size() > MAX_SLOTS || complete && portions.isEmpty()) throw new IllegalArgumentException("Invalid prepared kit");
            Set<StockRegion> slots = new HashSet<>(); Set<UUID> ids = new HashSet<>();
            for (Portion p : portions) if (!slots.add(p.source().slot()) || !ids.add(p.obligationId())) throw new IllegalArgumentException("Duplicate kit source/obligation");
        }
    }
    public static final class Search {
        private final UUID demandId;
        private final long demandRevision, kitRevision;
        private final List<RecipeDefinition.Ingredient> ingredients;
        private final long[] remaining;
        private final Map<StockRegion, Portion> portions = new LinkedHashMap<>();
        private final Set<StockRegion> seen = new HashSet<>();
        private boolean impossible;
        private Search(Demand.Snapshot demand, long revision, List<RecipeDefinition.Ingredient> ingredients) {
            demandId = demand.id(); demandRevision = demand.revision(); kitRevision = revision;
            this.ingredients = new ArrayList<>(ingredients);
            this.ingredients.sort(Comparator.comparing((RecipeDefinition.Ingredient i) -> i.matcher().exact() == null));
            remaining = new long[ingredients.size()]; for (int i = 0; i < remaining.length; i++) remaining[i] = this.ingredients.get(i).count();
        }
        public boolean complete() { if (impossible) return false; for (long count : remaining) if (count != 0) return false; return true; }
    }
    private final ColonyRegistry registry;
    private final SupplyRegistry supply;
    private final Map<UUID, List<ReservationLedger.Entry>> heldKits = new LinkedHashMap<>();
    public KitAllocator(ColonyRegistry registry, SupplyRegistry supply) { this.registry = Objects.requireNonNull(registry); this.supply = Objects.requireNonNull(supply); }
    public Search begin(UUID demandId, long kitRevision, List<RecipeDefinition.Ingredient> ingredients) {
        var demand = supply.demand(demandId).snapshot();
        if (kitRevision < 0 || ingredients.isEmpty() || ingredients.size() > MAX_MATCHERS || demand.status() == Demand.Status.CANCELLED || demand.status() == Demand.Status.COMPLETED) throw new IllegalArgumentException("Invalid kit search");
        return new Search(demand, kitRevision, List.copyOf(ingredients));
    }
    /** One bounded candidate portion. No stock is held while a search remains incomplete. */
    public PreparedKit search(Search search, List<Candidate> candidates, long tick) {
        registry.requireOwner(); if (candidates.size() > MAX_SLOTS || tick < 0) throw new IllegalArgumentException("Candidate portion exceeds envelope");
        if (supply.demand(search.demandId).snapshot().revision() != search.demandRevision) search.impossible = true;
        for (Candidate candidate : candidates) {
            if (search.impossible || search.complete()) break;
            if (!search.seen.add(candidate.slot())) continue;
            long available = Math.min(candidate.available(), registry.storage().index().free(candidate.slot(), tick)), used = 0;
            if (!candidate.item().equals(registry.storage().index().observation(candidate.slot()).item())) continue;
            for (int i = 0; i < search.remaining.length && available > 0; i++) if (search.ingredients.get(i).matcher().matches(candidate.item())) {
                long count = Math.min(search.remaining[i], available); search.remaining[i] -= count; available -= count; used += count;
            }
            if (used > 0) {
                if (search.portions.size() == MAX_SLOTS) { search.impossible = true; break; }
                search.portions.put(candidate.slot(), new Portion(candidate, used, UUID.randomUUID()));
            }
        }
        return new PreparedKit(search.demandId, search.demandRevision, search.kitRevision, List.copyOf(search.portions.values()), search.complete());
    }
    public PreparedKit prepare(UUID demandId, long kitRevision, List<RecipeDefinition.Ingredient> ingredients, List<Candidate> candidates, long tick) {
        return search(begin(demandId, kitRevision, ingredients), candidates, tick);
    }
    /** Exactly one native read and one canonical availability check per prepared slot; never candidate search. */
    public List<ReservationLedger.Entry> finalizeKit(PreparedKit kit, PhysicalAccess physical, long currentKitRevision, long tick) {
        registry.requireOwner(); Objects.requireNonNull(physical); var demand = supply.demand(kit.demandId()).snapshot();
        if (!kit.complete() || kit.kitRevision() != currentKitRevision || kit.demandRevision() != demand.revision()
                || demand.status() == Demand.Status.CANCELLED || demand.status() == Demand.Status.COMPLETED) return List.of();
        List<ReservationLedger.Entry> entries = new ArrayList<>(kit.portions().size());
        for (Portion portion : kit.portions()) {
            Candidate candidate = portion.source(); Observation read = physical.read(candidate.slot());
            if (!read.known() || read.revision() != candidate.revision() || !candidate.item().equals(read.item()) || read.count() < portion.count()
                    || registry.storage().index().free(candidate.slot(), tick) < portion.count()) return List.of();
            entries.add(new ReservationLedger.Entry(portion.obligationId(), demand.colonyId(), demand.ownerId(), candidate.slot(), candidate.item(), portion.count(), 0, demand.lane()));
        }
        try {
            List<ReservationLedger.Entry> admitted = registry.storage().reserveAll(entries, tick); heldKits.put(kit.demandId(), admitted); return admitted;
        } catch (AdmissionLedger.AdmissionException denied) { return List.of(); }
        catch (IllegalStateException mismatch) { return List.of(); }
    }
    public boolean finalizeProductionKit(PreparedKit kit, PhysicalAccess physical, long currentOrderRevision, long tick, UUID productionId) {
        registry.requireOwner(); Objects.requireNonNull(physical);
        var demand = supply.demand(kit.demandId()).snapshot();
        var order = supply.productionOrders().stream().filter(p -> p.id().equals(productionId)).findFirst().orElse(null);
        if (order == null || order.revision() != currentOrderRevision || kit.kitRevision() != currentOrderRevision || !kit.complete()
                || !demand.ownerId().equals(productionId) || kit.demandRevision() != demand.revision()) return false;
        List<ReservationLedger.Entry> entries = new ArrayList<>(kit.portions().size());
        for (Portion portion : kit.portions()) {
            Candidate candidate = portion.source(); Observation read = physical.read(candidate.slot());
            if (!read.known() || read.revision() != candidate.revision() || !candidate.item().equals(read.item()) || read.count() < portion.count()
                    || registry.storage().index().free(candidate.slot(), tick) < portion.count()) return false;
            entries.add(new ReservationLedger.Entry(portion.obligationId(), order.colonyId(), productionId, candidate.slot(), candidate.item(), portion.count(), 0, order.lane()));
        }
        try { supply.coverProductionKit(productionId, entries, tick); return true; }
        catch (IllegalStateException | IllegalArgumentException invalidated) { return false; }
    }
    /** External loss invalidates the complete unused kit. Already converted allocations/cargo are untouched. */
    public void reconcile() {
        registry.requireOwner();
        for (var kit : List.copyOf(heldKits.entrySet())) {
            boolean lost = false;
            for (var entry : kit.getValue()) { var current = registry.storage().reservations().get(entry.id());
                if (current == null && registry.storage().allocations().get(entry.id()) != null) continue;
                if (current == null || current.count() != entry.count() || !current.slot().equals(entry.slot())) { lost = true; break; } }
            if (!lost) continue;
            Map<UUID, Long> releases = new LinkedHashMap<>();
            for (var entry : kit.getValue()) if (registry.storage().reservations().get(entry.id()) != null) releases.put(entry.id(), 0L);
            registry.storage().reduceObligations(releases); heldKits.remove(kit.getKey());
        }
    }
}
