package io.github.kpuctajluk.colonyloom.minecraft.runtime;

import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
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

/** Explicit admission is independent of physical entity ticking caused by a nearby player. */
public final class CitizenAdmissionService implements AutoCloseable {
    private static final class Entry {
        final UUID id;
        ChunkKey center;
        long observedActive;
        boolean eligible;
        Entry(UUID id) { this.id = id; }
    }
    private final MinecraftServer server;
    private final ServerRuntime runtime;
    private final ColonyRegistry registry;
    private final ChunkDemandManager chunks;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final Map<UUID, Entry> index = new HashMap<>();
    private int cursor;
    public CitizenAdmissionService(MinecraftServer server, ServerRuntime runtime, ChunkDemandManager chunks) {
        this.server = server; this.runtime = runtime; this.registry = runtime.registry(); this.chunks = chunks;
        for (CitizenRecord citizen : registry.citizensView()) observe(citizen.citizenId());
    }
    public void observe(UUID citizenId) {
        registry.requireOwner();
        Entry entry = index.get(citizenId);
        if (entry == null) { entry = new Entry(citizenId); index.put(citizenId, entry); entries.add(entry); }
        reconcile(entry);
    }
    public void tick() {
        int remaining = Math.min(entries.size(), Math.max(1, runtime.budgets().limits().budget(Budget.DIRTY_RESCAN_OBJECTS) / 4));
        while (remaining-- > 0 && runtime.budgets().timeAvailable()
                && runtime.budgets().tryConsume(Budget.DIRTY_RESCAN_OBJECTS, Lane.SERVICE)) {
            if (cursor >= entries.size()) cursor = 0;
            long start=System.nanoTime();
            try { reconcile(entries.get(cursor++)); }
            finally { runtime.metrics().record(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer.DIRTY_RESCAN_UNIT,System.nanoTime()-start); }
        }
    }
    private void reconcile(Entry entry) {
        CitizenRecord citizen = registry.citizen(entry.id);
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(citizen.lastKnownPosition().dimension())));
        CitizenEntity entity = level != null && level.getEntity(citizen.entityId()) instanceof CitizenEntity found ? found : null;
        boolean bound = entity != null && entity.isAlive() && !entity.isQuarantined()
                && citizen.citizenId().equals(entity.citizenId()) && citizen.bindingEpoch() == entity.bindingEpoch()
                && citizen.lifecycle() == CitizenRecord.Lifecycle.ALIVE && citizen.readiness() == CitizenRecord.Readiness.READY
                && registry.colony(citizen.colonyId()).available();
        long active = entry.center == null ? 0 : chunks.activeTicks(entry.id);
        if (entry.eligible && bound && citizen.admission() == CitizenRecord.Admission.ACTIVE && active > entry.observedActive) {
            registry.beforeMutation(); registry.updateCitizen(citizen.withActiveTime(Math.addExact(citizen.activeTimeTicks(), active - entry.observedActive)));
            citizen = registry.citizen(entry.id);
        }
        entry.observedActive = active;
        if (!bound) {
            chunks.release(entry.id); entry.center = null; entry.observedActive = 0; entry.eligible = false;
            runtime.commands().updateCitizenAdmission(entry.id, CitizenRecord.Admission.INACTIVE); return;
        }
        WorldPosition position = new WorldPosition(level.dimension().location().toString(), entity.blockPosition().getX(), entity.blockPosition().getY(), entity.blockPosition().getZ());
        ChunkKey center = new ChunkKey(position.dimension(), Math.floorDiv(position.x(), 16), Math.floorDiv(position.z(), 16));
        if (!center.equals(entry.center)) {
            // The protected navigation domain covers rollover; no tick/physical step occurs in this gap.
            chunks.setProtection(entry.id, false, false, false);
            try {
                chunks.request(entry.id, citizen.colonyId(), List.of(center), ChunkDemandManager.Readiness.ENTITY_TICKING, Lane.NORMAL, 0, false);
            } catch (io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.AdmissionException denied) {
                entry.eligible = false;
                runtime.commands().updateCitizenAdmission(entry.id, CitizenRecord.Admission.INACTIVE);
                return;
            }
            entry.center = center; entry.observedActive = chunks.activeTicks(entry.id);
        }
        // A rollover can be covered by an already charged construction/navigation domain while
        // this citizen's replacement demand awaits its cursor. Do not tear down that live domain.
        boolean covered = chunks.admitted(center) && chunks.ready(center, ChunkDemandManager.Readiness.ENTITY_TICKING);
        boolean ready = chunks.admitted(entry.id) && chunks.ready(entry.id) || covered;
        // A moving worker protects its occupied center until navigation has safely stopped.
        chunks.setProtection(entry.id, ready && citizen.assignedWorkId() != null, false, false);
        runtime.commands().updateCitizenAdmission(entry.id, ready ? CitizenRecord.Admission.ACTIVE : CitizenRecord.Admission.INACTIVE);
        entry.eligible = ready;
        if (ready) chunks.useful(entry.id);
        if (!citizen.lastKnownPosition().equals(position)) {
            registry.beforeMutation(); registry.updateCitizen(registry.citizen(entry.id).withPosition(position));
        }
    }
    @Override public void close() {
        for (Entry entry : entries) chunks.release(entry.id);
        entries.clear(); index.clear();
    }
}
