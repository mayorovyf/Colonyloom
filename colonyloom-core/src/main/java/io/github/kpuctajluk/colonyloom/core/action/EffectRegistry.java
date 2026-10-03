package io.github.kpuctajluk.colonyloom.core.action;

import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Each accepted physical step reserves its evidence before touching the world. */
public final class EffectRegistry {
    public static final int MAX_RECORDS=8192;
    private final ColonyRegistry registry;
    private final Map<UUID,EffectRecord> records=new LinkedHashMap<>();
    private final Map<UUID,AdmissionLedger.Lease> leases=new LinkedHashMap<>();
    public EffectRegistry(ColonyRegistry registry) { this.registry=Objects.requireNonNull(registry); }
    public int size() { registry.requireOwner(); return records.size(); }
    public List<EffectRecord> snapshots() { registry.requireOwner(); return List.copyOf(records.values()); }
    public EffectRecord get(UUID id) { registry.requireOwner(); return records.get(id); }
    public void prepare(EffectRecord effect,Lane lane) {
        registry.requireOwner(); registry.colony(effect.colonyId());
        if (records.size()>=MAX_RECORDS || records.containsKey(effect.operationId()) || registry.usedId(effect.operationId()))
            throw new IllegalArgumentException("Physical evidence identity or envelope unavailable");
        if (effect.state()!=EffectRecord.State.PREPARED || effect.revision()!=0 || effect.countBefore()!=effect.countAfter()
                || effect.transfer()!=null && !effect.transfer().unchanged() || effect.craft()!=null && !effect.craft().unchanged())
            throw new IllegalArgumentException("Effect must start prepared and unchanged");
        var citizen=registry.citizen(effect.citizenId());
        if (!citizen.colonyId().equals(effect.colonyId()) || citizen.bindingEpoch()!=effect.bindingEpoch()) throw new IllegalArgumentException("Effect binding mismatch");
        if (effect.workId()!=null && !registry.workBoard().work(effect.workId()).colonyId().equals(effect.colonyId())) throw new IllegalArgumentException("Effect work owner mismatch");
        var lease=registry.admission().reserve(effect.colonyId(),lane,Map.of(Resource.EVIDENCE,1));
        try { registry.beforeMutation(); } catch(RuntimeException failure) { lease.close(); throw failure; }
        records.put(effect.operationId(),effect); leases.put(effect.operationId(),lease);
    }
    public void update(EffectRecord effect) {
        registry.requireOwner(); var old=records.get(effect.operationId());
        if (old==null || !old.colonyId().equals(effect.colonyId()) || !old.citizenId().equals(effect.citizenId())
                || !Objects.equals(old.workId(),effect.workId()) || old.bindingEpoch()!=effect.bindingEpoch()
                || old.kind()!=effect.kind() || !old.target().equals(effect.target()) || old.revision()==Long.MAX_VALUE || old.revision()+1!=effect.revision()
                || !old.expectedBlock().equals(effect.expectedBlock()) || !old.itemId().equals(effect.itemId()) || old.countBefore()!=effect.countBefore()
                || (old.transfer()==null ? effect.transfer()!=null : !old.transfer().sameAttempt(effect.transfer()))
                || (old.craft()==null ? effect.craft()!=null : !old.craft().sameAttempt(effect.craft())
                        || old.craft().phase().ordinal()>effect.craft().phase().ordinal())
                || !validTransition(old,effect))
            throw new IllegalArgumentException("Effect evidence changed identity or revision");
        registry.beforeMutation(); records.put(effect.operationId(),effect);
    }
    private static boolean validTransition(EffectRecord old,EffectRecord next) {
        if (old.state()!=EffectRecord.State.PREPARED && (!Objects.equals(old.transfer(),next.transfer()) || !Objects.equals(old.craft(),next.craft()))) return false;
        return switch(old.state()) {
            case PREPARED -> next.state()==EffectRecord.State.OBSERVED || next.state()==EffectRecord.State.AMBIGUOUS
                    || next.state()==EffectRecord.State.ACCEPTED && old.countAfter()==next.countAfter()
                            && Objects.equals(old.transfer(),next.transfer()) && Objects.equals(old.craft(),next.craft());
            case OBSERVED -> (next.state()==EffectRecord.State.AMBIGUOUS || next.state()==EffectRecord.State.ACCEPTED) && old.countAfter()==next.countAfter();
            case AMBIGUOUS -> next.state()==EffectRecord.State.ACCEPTED && old.countAfter()==next.countAfter();
            case ACCEPTED -> false;
        };
    }
    /** Caller has verified a completed world, entity and DTO checkpoint; never called by autosave. */
    public void compactAfterVerifiedCheckpoint() {
        registry.requireOwner();
        if(registry.colonies().stream().anyMatch(value -> value.recoveryBlocked() || value.contentBlocked())) return;
        boolean mutated=false;
        for(var iterator=records.entrySet().iterator();iterator.hasNext();) {
            var entry=iterator.next(); var state=entry.getValue().state();
            if(state!=EffectRecord.State.OBSERVED && state!=EffectRecord.State.ACCEPTED) continue;
            if(!mutated) { registry.beforeMutation(); mutated=true; }
            leases.remove(entry.getKey()).close(); iterator.remove();
        }
    }
    /** Only proven unchanged attempts can be discarded without a durable world checkpoint. */
    public void discardUnchanged(UUID operationId) {
        registry.requireOwner(); var effect=records.get(operationId);
        if(effect==null || effect.state()!=EffectRecord.State.PREPARED) throw new IllegalStateException("Only unchanged prepared attempts may be discarded");
        registry.beforeMutation(); records.remove(operationId); leases.remove(operationId).close();
    }
    public void blockAmbiguous(UUID colonyId,UUID checkpoint) { registry.markRecoveryBlocked(colonyId,checkpoint); }
    public void accept(UUID colonyId) {
        registry.requireOwner();
        for(var entry:records.entrySet()) if(entry.getValue().colonyId().equals(colonyId) && entry.getValue().state()!=EffectRecord.State.ACCEPTED)
            entry.setValue(entry.getValue().accepted());
    }
    /** Caller validates all references before committing the surrounding registry snapshot. */
    public PreparedRestore prepareRestore(List<EffectRecord> saved,AdmissionLedger replacement) {
        registry.requireOwner(); if(saved.size()>MAX_RECORDS) throw new IllegalArgumentException("Effect envelope exceeded");
        Map<UUID,EffectRecord> staged=new LinkedHashMap<>(); Map<UUID,AdmissionLedger.Lease> admitted=new LinkedHashMap<>();
        try {
            for(var effect:saved) {
                if(staged.putIfAbsent(effect.operationId(),effect)!=null) throw new IllegalArgumentException("Duplicate effect");
                admitted.put(effect.operationId(),replacement.reserve(effect.colonyId(),Lane.NORMAL,Map.of(Resource.EVIDENCE,1)));
            }
        } catch(RuntimeException failure) { admitted.values().forEach(AdmissionLedger.Lease::close); throw failure; }
        return new PreparedRestore(staged,admitted);
    }
    public final class PreparedRestore implements AutoCloseable {
        private Map<UUID,EffectRecord> staged; private Map<UUID,AdmissionLedger.Lease> admitted;
        private PreparedRestore(Map<UUID,EffectRecord> staged,Map<UUID,AdmissionLedger.Lease> admitted) { this.staged=staged; this.admitted=admitted; }
        public void commit() {
            registry.requireOwner(); if(staged==null) throw new IllegalStateException("Restore already closed");
            leases.values().forEach(AdmissionLedger.Lease::close); records.clear(); records.putAll(staged);
            leases.clear(); leases.putAll(admitted); staged=null; admitted=null;
        }
        public void close() { if(admitted!=null) { admitted.values().forEach(AdmissionLedger.Lease::close); admitted=null; staged=null; } }
    }
}
