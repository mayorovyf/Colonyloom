package io.github.kpuctajluk.colonyloom.minecraft.runtime;

import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Explicit admission requires charged ready coverage, never incidental player-driven entity ticks.
 * An exact resident's native tick may resume its pending service pause; domain maintenance stays
 * on the globally budgeted cursor, and neither transition nor reconciliation catches up own time.
 */
public final class CitizenAdmissionService implements AutoCloseable {
    /** Read-only evidence of the current native resident's charged loading desire. */
    public record Coverage(UUID demandOwner, ChunkKey desiredCenter, ChunkKey observedCenter,
            ChunkDemandManager.State state, io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason reason,
            boolean pending, boolean admitted, boolean ready) {}
    private static final class Entry {
        final UUID id;
        final UUID rolloverId = UUID.randomUUID();
        UUID demandOwner;
        UUID retainedOwner;
        ChunkKey center, observedCenter, retainedCenter;
        CitizenEntity entity;
        ResourceKey<net.minecraft.world.level.Level> observedDimension;
        boolean eligible, pending, awaitingEmbodiment;
        Entry pendingPrevious, pendingNext;
        Entry observedPrevious, observedNext;
        Entry(UUID id) { this.id = id; demandOwner = id; }
    }
    private final MinecraftServer server;
    private final ServerRuntime runtime;
    private final ColonyRegistry registry;
    private final ChunkDemandManager chunks;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final Map<UUID, Entry> index = new HashMap<>();
    private final Map<ChunkKey, Entry> observedResidents = new HashMap<>();
    private int cursor;
    private Entry pendingHead, pendingTail;
    private boolean pendingTurn;
    private java.util.function.Consumer<UUID> foodNeed = ignored -> {};
    public void onFoodNeed(java.util.function.Consumer<UUID> listener) { registry.requireOwner(); foodNeed = java.util.Objects.requireNonNull(listener); }
    public CitizenAdmissionService(MinecraftServer server, ServerRuntime runtime, ChunkDemandManager chunks) {
        this.server = server; this.runtime = runtime; this.registry = runtime.registry(); this.chunks = chunks;
        chunks.setCoverageLossListener(this::coverageLost);
        for (CitizenRecord citizen : registry.citizensView()) observe(citizen.citizenId());
    }
    private void observeCenter(Entry entry, ChunkKey center) {
        if (java.util.Objects.equals(center, entry.observedCenter)) return;
        if (entry.observedCenter != null) {
            if (entry.observedPrevious != null) entry.observedPrevious.observedNext = entry.observedNext;
            else if (entry.observedNext != null) observedResidents.put(entry.observedCenter, entry.observedNext);
            else observedResidents.remove(entry.observedCenter);
            if (entry.observedNext != null) entry.observedNext.observedPrevious = entry.observedPrevious;
        }
        entry.observedCenter = center;
        entry.observedPrevious = null;
        entry.observedNext = center == null ? null : observedResidents.put(center, entry);
        if (entry.observedNext != null) entry.observedNext.observedPrevious = entry;
    }
    private void coverageLost(ChunkKey center) {
        // No entity lookup, demand replacement, or dirty sweep inside chunk withdrawal.
        // Native ticks alone resume the exact embodiment after charged coverage returns.
        for (Entry entry = observedResidents.get(center); entry != null; entry = entry.observedNext) {
            entry.eligible = false;
            entry.awaitingEmbodiment = true;
            enqueue(entry);
            runtime.commands().updateCitizenAdmission(entry.id, CitizenRecord.Admission.INACTIVE);
        }
    }
    public void observe(UUID citizenId) {
        registry.requireOwner();
        Entry entry = index.get(citizenId);
        if (entry == null) { entry = new Entry(citizenId); index.put(citizenId, entry); entries.add(entry); }
        removePending(entry);
        reconcile(entry);
    }
    public Coverage coverage(UUID citizenId) {
        registry.requireOwner();
        Entry entry = index.get(citizenId);
        if (entry == null) return null;
        ChunkKey center = entry.observedCenter;
        boolean admitted = center != null && chunks.admitted(center);
        boolean ready = admitted && chunks.ready(center, ChunkDemandManager.Readiness.ENTITY_TICKING);
        var citizen = registry.citizen(citizenId);
        var reason = citizen.readiness() == CitizenRecord.Readiness.UNKNOWN
                ? io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.RECONCILING
                : center != null && !center.equals(entry.center)
                        ? io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.STATE_LIMIT : chunks.reason(entry.demandOwner);
        return new Coverage(entry.demandOwner, entry.center, center, chunks.state(entry.demandOwner), reason,
                entry.pending, admitted, ready);
    }
    public void tick() {
        int remaining = Math.min(entries.size(), Math.max(1, runtime.budgets().limits().budget(Budget.DIRTY_RESCAN_OBJECTS) / 4));
        while (remaining-- > 0 && runtime.budgets().timeAvailable()
                && runtime.budgets().tryConsume(Budget.DIRTY_RESCAN_OBJECTS, Lane.SERVICE)) {
            pendingTurn = !pendingTurn;
            Entry entry;
            if (pendingTurn && pendingHead != null) entry = pendingHead;
            else {
                if (cursor >= entries.size()) cursor = 0;
                entry = entries.get(cursor++);
            }
            removePending(entry);
            long start = System.nanoTime();
            try { reconcile(entry); }
            finally { runtime.metrics().record(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer.DIRTY_RESCAN_UNIT, System.nanoTime() - start); }
        }
    }
    private void enqueue(Entry entry) {
        if (entry.pending) return;
        entry.pending = true;
        entry.pendingPrevious = pendingTail;
        if (pendingTail == null) pendingHead = entry;
        else pendingTail.pendingNext = entry;
        pendingTail = entry;
    }
    private void removePending(Entry entry) {
        if (!entry.pending) return;
        if (entry.pendingPrevious == null) pendingHead = entry.pendingNext;
        else entry.pendingPrevious.pendingNext = entry.pendingNext;
        if (entry.pendingNext == null) pendingTail = entry.pendingPrevious;
        else entry.pendingNext.pendingPrevious = entry.pendingPrevious;
        entry.pending = false;
        entry.pendingPrevious = entry.pendingNext = null;
    }
    private boolean embodiment(CitizenRecord citizen, CitizenEntity entity) {
        return entity != null && !entity.isRemoved() && entity.isAlive() && !entity.isQuarantined()
                && citizen.entityId().equals(entity.getUUID()) && citizen.citizenId().equals(entity.citizenId())
                && citizen.bindingEpoch() == entity.bindingEpoch()
                && citizen.lifecycle() == CitizenRecord.Lifecycle.ALIVE
                && registry.colony(citizen.colonyId()).available();
    }
    private boolean bound(CitizenRecord citizen, CitizenEntity entity) {
        return embodiment(citizen, entity) && citizen.readiness() == CitizenRecord.Readiness.READY;
    }
    private void releaseDemands(Entry entry) {
        observeCenter(entry, null);
        chunks.release(entry.id);
        chunks.release(entry.rolloverId);
        entry.demandOwner = entry.id;
        entry.retainedOwner = null;
        entry.center = entry.retainedCenter = null;
        entry.observedDimension = null;
        entry.awaitingEmbodiment = false;
    }
    private boolean retainFits(CitizenRecord citizen, ChunkKey old, ChunkKey replacement) {
        int loaded = 25, block = 9;
        if (old.dimension().equals(replacement.dimension())) {
            long dx = Math.abs((long) old.x() - replacement.x()), dz = Math.abs((long) old.z() - replacement.z());
            loaded -= (int) ((5 - Math.min(5, dx)) * (5 - Math.min(5, dz)));
            block -= (int) ((3 - Math.min(3, dx)) * (3 - Math.min(3, dz)));
        }
        // This upper bound includes the old domain's overlap without assuming other owners survive.
        // The real full-ring grant and native ticket acquisition still belong to chunks.tick.
        return registry.admission().canReserve(citizen.colonyId(), Lane.NORMAL,
                Map.of(Resource.LOADED_FOOTPRINT, loaded, Resource.BLOCK_TICKING, block, Resource.ENTITY_TICKING, 1));
    }
    private void requestCenter(Entry entry, CitizenRecord citizen, ChunkKey center) {
        if (center.equals(entry.center)) return;
        if (entry.center != null) entry.awaitingEmbodiment = true;
        enqueue(entry);
        if (center.equals(entry.retainedCenter) && chunks.admitted(entry.retainedOwner)) {
            chunks.release(entry.demandOwner);
            entry.demandOwner = entry.retainedOwner;
            entry.center = entry.retainedCenter;
            entry.retainedOwner = null;
            entry.retainedCenter = null;
            return;
        }
        UUID keepOwner = entry.demandOwner;
        ChunkKey keepCenter = entry.center;
        if (!chunks.admitted(keepOwner) && entry.retainedOwner != null && chunks.admitted(entry.retainedOwner)) {
            keepOwner = entry.retainedOwner;
            keepCenter = entry.retainedCenter;
        }
        boolean retain = keepCenter != null && chunks.admitted(keepOwner) && retainFits(citizen, keepCenter, center);
        UUID owner = entry.demandOwner;
        if (retain) {
            owner = keepOwner.equals(entry.id) ? entry.rolloverId : entry.id;
            chunks.release(owner);
            entry.demandOwner = keepOwner;
            entry.center = keepCenter;
        } else {
            chunks.release(entry.id);
            chunks.release(entry.rolloverId);
            entry.center = null;
        }
        entry.retainedOwner = null;
        entry.retainedCenter = null;
        try {
            chunks.request(owner, citizen.colonyId(), List.of(center), ChunkDemandManager.Readiness.ENTITY_TICKING, Lane.NORMAL, 0, false);
        } catch (io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.AdmissionException denied) {
            if (!retain || denied.resource() != Resource.CHUNK_DEMANDS) {
                if (retain) chunks.setProtection(entry.demandOwner, true, false, false);
                return;
            }
            // The second desired domain is optional; never exceed its charged state capacity.
            chunks.release(entry.demandOwner);
            entry.center = null;
            retain = false;
            try {
                chunks.request(owner, citizen.colonyId(), List.of(center), ChunkDemandManager.Readiness.ENTITY_TICKING, Lane.NORMAL, 0, false);
            } catch (io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.AdmissionException stillDenied) { return; }
        }
        if (retain) {
            entry.retainedOwner = entry.demandOwner;
            entry.retainedCenter = entry.center;
            chunks.setProtection(entry.retainedOwner, true, false, false);
        }
        entry.demandOwner = owner;
        entry.center = center;
    }
    private void reconcile(Entry entry) {
        CitizenRecord citizen = registry.citizen(entry.id);
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(citizen.lastKnownPosition().dimension())));
        CitizenEntity entity = level != null && level.getEntity(citizen.entityId()) instanceof CitizenEntity found ? found : null;
        if (entity != null && entity.isRemoved()) entity = null;
        // The native visible lookup can hide a still-resident entity while its old ticket is
        // withdrawn. Keep only the already-bound, non-removed embodiment through regrant;
        // otherwise a visibility transition cancels the very demand that would restore it.
        if (entity == null && level != null && entry.entity != null && entry.entity.level() == level
                && embodiment(citizen, entry.entity) && citizen.readiness() != CitizenRecord.Readiness.BLOCKED) entity = entry.entity;
        boolean bound = bound(citizen, entity);
        if(entity!=entry.entity) {
            if(entry.entity!=null)entry.entity.managedActiveTick(null);
            entry.entity=entity;
            if(entity!=null)entity.managedActiveTick(() -> nativeTick(entry));
        }
        if (!bound && embodiment(citizen, entity) && citizen.readiness() == CitizenRecord.Readiness.UNKNOWN
                && entry.center != null) {
            // UNKNOWN is not proof of disappearance of this exact still-loaded embodiment.
            // Keep its charged desire through native binding reconciliation, but never its clock.
            entry.eligible = false;
            runtime.commands().updateCitizenAdmission(entry.id, CitizenRecord.Admission.INACTIVE);
            enqueue(entry);
            return;
        }
        if (!bound && entity == null && entry.awaitingEmbodiment && entry.center != null
                && citizen.lifecycle() == CitizenRecord.Lifecycle.ALIVE
                && citizen.readiness() != CitizenRecord.Readiness.BLOCKED
                && registry.colony(citizen.colonyId()).available()) {
            // Vanilla may unload the old embodiment while a capacity-constrained ring is swapped.
            // Keep its charged loading desire, never synthesize a replacement or advance own time.
            entry.eligible = false;
            runtime.commands().updateCitizenAdmission(entry.id, CitizenRecord.Admission.INACTIVE);
            enqueue(entry);
            return;
        }
        if (!bound) {
            releaseDemands(entry); entry.eligible = false;
            runtime.commands().updateCitizenAdmission(entry.id, CitizenRecord.Admission.INACTIVE); return;
        }
        WorldPosition position = new WorldPosition(level.dimension().location().toString(), entity.blockPosition().getX(), entity.blockPosition().getY(), entity.blockPosition().getZ());
        ChunkKey center = new ChunkKey(position.dimension(), Math.floorDiv(position.x(), 16), Math.floorDiv(position.z(), 16));
        observeCenter(entry, center);
        entry.observedDimension = level.dimension();
        requestCenter(entry, citizen, center);
        if (entry.retainedOwner != null && !chunks.admitted(entry.demandOwner)
                && !retainFits(citizen, entry.retainedCenter, center)) {
            chunks.release(entry.retainedOwner);
            entry.retainedOwner = null;
            entry.retainedCenter = null;
        }
        boolean ownReady = center.equals(entry.center) && chunks.admitted(entry.demandOwner) && chunks.ready(entry.demandOwner);
        if (ownReady && entry.retainedOwner != null) {
            chunks.release(entry.retainedOwner);
            entry.retainedOwner = null;
            entry.retainedCenter = null;
        }
        if (!ownReady || entry.retainedOwner != null) enqueue(entry);
        else removePending(entry);
        // A rollover can be covered by an already charged construction/navigation domain while
        // this citizen's replacement demand awaits its cursor. Do not tear down that live domain.
        boolean covered = chunks.admitted(center) && chunks.ready(center, ChunkDemandManager.Readiness.ENTITY_TICKING);
        boolean ready = ownReady || covered;
        // A denied desired-state replacement keeps its still-admitted old coverage protected too.
        chunks.setProtection(entry.demandOwner, chunks.admitted(entry.demandOwner) && !center.equals(entry.center)
                || ready && citizen.assignedWorkId() != null, false, false);
        runtime.commands().updateCitizenAdmission(entry.id, ready ? CitizenRecord.Admission.ACTIVE : CitizenRecord.Admission.INACTIVE);
        entry.eligible = ready;
        if (ownReady) chunks.useful(entry.demandOwner);
        if (!citizen.lastKnownPosition().equals(position)) {
            registry.beforeMutation(); registry.updateCitizen(registry.citizen(entry.id).withPosition(position));
        }
    }
    private void nativeTick(Entry entry) {
        if (runtime.lifecycle() != ServerRuntime.Lifecycle.RUNNING) return;
        CitizenRecord citizen = registry.citizen(entry.id);
        CitizenEntity entity = entry.entity;
        if (!bound(citizen, entity)) {
            entry.eligible = false;
            enqueue(entry);
            runtime.commands().updateCitizenAdmission(entry.id, CitizenRecord.Admission.INACTIVE);
            return;
        }
        var position = entity.blockPosition();
        ServerLevel level = (ServerLevel) entity.level();
        var dimension = level.dimension();
        ChunkKey center = entry.observedCenter;
        if (center == null || center.x() != (position.getX() >> 4) || center.z() != (position.getZ() >> 4)
                || !dimension.equals(entry.observedDimension)) {
            center = new ChunkKey(dimension.location().toString(), position.getX() >> 4, position.getZ() >> 4);
            observeCenter(entry, center);
            entry.observedDimension = dimension;
            WorldPosition observed = new WorldPosition(center.dimension(), position.getX(), position.getY(), position.getZ());
            registry.beforeMutation(); registry.updateCitizen(citizen.withPosition(observed));
            citizen = registry.citizen(entry.id);
            // Native displacement publishes desired state immediately, even outside ready coverage.
            // No dirty token or physical ticket is consumed off the managed service cursor.
            requestCenter(entry, citizen, center);
        }
        if (!level.isPositionEntityTicking(position) || !chunks.admitted(center)
                || !chunks.ready(center, ChunkDemandManager.Readiness.ENTITY_TICKING)) {
            // The native callback is the last guaranteed observation after an unready crossing.
            // Publish the pause now, rather than leaving ACTIVE until a sparse cursor revisits it.
            entry.eligible = false;
            enqueue(entry);
            runtime.commands().updateCitizenAdmission(entry.id, CitizenRecord.Admission.INACTIVE);
            return;
        }
        if (!entry.eligible) {
            if (!entry.pending) return;
            // The exact resident's actual tick proves its charged current domain became ready.
            // Publish only this service-paused control transition; demand cleanup stays paid.
            // Refresh the authoritative record after publication before advancing one own tick.
            runtime.commands().updateCitizenAdmission(entry.id, CitizenRecord.Admission.ACTIVE);
            citizen = registry.citizen(entry.id);
            entry.eligible = true;
        } else if (citizen.admission() != CitizenRecord.Admission.ACTIVE) return;
        entry.awaitingEmbodiment = false;
        registry.beforeMutation(); registry.updateCitizen(citizen.withActiveTime(Math.incrementExact(citizen.activeTimeTicks())));
        if (registry.citizen(entry.id).food() <= 6) foodNeed.accept(entry.id);
    }
    @Override public void close() {
        chunks.setCoverageLossListener(ignored -> {});
        for (Entry entry : entries) { if(entry.entity!=null)entry.entity.managedActiveTick(null); releaseDemands(entry); }
        entries.clear(); index.clear(); observedResidents.clear(); pendingHead = pendingTail = null;
    }
}
