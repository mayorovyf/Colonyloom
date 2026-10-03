package io.github.kpuctajluk.colonyloom.core.construction;

import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.content.BlueprintDefinition;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded instance records and shared pinned versions; datapack reload cannot change accepted goals. */
public final class ConstructionRegistry {
    public static final int MAX_SITES=512, MAX_VERSIONS=64;
    public static final long MAX_PINNED_BYTES=64L*1024*1024;
    private final ColonyRegistry registry;
    private final Map<UUID,ConstructionSnapshot> sites=new LinkedHashMap<>();
    private final Map<String,BlueprintDefinition> pins=new LinkedHashMap<>();
    private final Map<UUID,AdmissionLedger.Lease> leases=new LinkedHashMap<>();
    private long pinnedBytes;
    private long pinnedBytesHighWater, rejectedPins;
    private int pinnedVersionsHighWater;
    public Map<String,Object> pinDiagnostics() { registry.requireOwner(); return Map.of("bytes",pinnedBytes,"bytesHighWater",pinnedBytesHighWater,"byteCap",MAX_PINNED_BYTES,"versions",pins.size(),"versionsHighWater",pinnedVersionsHighWater,"versionCap",MAX_VERSIONS,"rejected",rejectedPins,"byteScope","conservative retained definition estimate; not JVM allocation measurement"); }
    private void recordPins() { pinnedBytesHighWater=Math.max(pinnedBytesHighWater,pinnedBytes); pinnedVersionsHighWater=Math.max(pinnedVersionsHighWater,pins.size()); }
    public ConstructionRegistry(ColonyRegistry registry) { this.registry=Objects.requireNonNull(registry); }
    public int size() { registry.requireOwner(); return sites.size(); }
    public ConstructionSnapshot site(UUID workId) { registry.requireOwner(); return sites.get(workId); }
    public BlueprintDefinition definition(String digest) { registry.requireOwner(); var result=pins.get(digest); if(result==null) throw new IllegalArgumentException("Unknown pinned blueprint"); return result; }
    public List<ConstructionSnapshot> snapshots() { registry.requireOwner(); return List.copyOf(sites.values()); }
    public List<BlueprintDefinition> definitions() { registry.requireOwner(); return List.copyOf(pins.values()); }
    public long pinnedBytes() { registry.requireOwner(); return pinnedBytes; }
    public void validateNew(ConstructionSnapshot site,BlueprintDefinition definition) {
        registry.requireOwner();
        if(sites.size()>=MAX_SITES || sites.containsKey(site.workId()) || !site.blueprintDigest().equals(definition.digest())
                || site.cursor()>definition.blocks().size() || !definition.markers().containsKey("work_origin")) throw new IllegalArgumentException("Construction envelope or identity unavailable");
        try {
            validatePin(definition,pins,pinnedBytes);
            var definitions=new java.util.ArrayList<>(pins.values());if(!pins.containsKey(definition.digest()))definitions.add(definition);
            io.github.kpuctajluk.colonyloom.core.supply.SupplyRegistry.validatePins(definitions,registry.supply().productionOrders().stream().map(io.github.kpuctajluk.colonyloom.core.production.ProductionOrder::recipe).toList(),null);
        }
        catch(IllegalArgumentException failure) { rejectedPins++; throw failure; }
    }
    public PreparedNew prepareNew(ConstructionSnapshot site,BlueprintDefinition definition) {
        validateNew(site,definition);
        var lease=registry.admission().reserve(site.colonyId(),Lane.NORMAL,Map.of(Resource.EVIDENCE,1));
        try { registry.beforeMutation(); } catch(RuntimeException failure) { lease.close(); throw failure; }
        return new PreparedNew(site,definition,lease);
    }
    public final class PreparedNew implements AutoCloseable {
        private final ConstructionSnapshot site; private final BlueprintDefinition definition; private AdmissionLedger.Lease lease;
        private PreparedNew(ConstructionSnapshot site,BlueprintDefinition definition,AdmissionLedger.Lease lease) { this.site=site; this.definition=definition; this.lease=lease; }
        public void commit() {
            registry.requireOwner(); if(lease==null) throw new IllegalStateException("Prepared construction already closed");
            validateNew(site,definition);
            var work=registry.workBoard().work(site.workId());
            if(!work.colonyId().equals(site.colonyId()) || !work.typeId().equals(io.github.kpuctajluk.colonyloom.core.work.WorkOrder.CONSTRUCTION)) throw new IllegalArgumentException("Construction work owner/type mismatch");
            if(pins.putIfAbsent(definition.digest(),definition)==null) pinnedBytes+=estimatedBytes(definition);
            recordPins();
            sites.put(site.workId(),site); leases.put(site.workId(),lease); lease=null;
        }
        public void close() { if(lease!=null) { lease.close(); lease=null; } }
    }
    public void add(ConstructionSnapshot site,BlueprintDefinition definition) {
        try(var prepared=prepareNew(site,definition)) { prepared.commit(); }
    }
    public void update(ConstructionSnapshot site) {
        registry.requireOwner(); var old=sites.get(site.workId());
        if(old==null || !old.colonyId().equals(site.colonyId()) || !old.blueprintDigest().equals(site.blueprintDigest())
                || !old.origin().equals(site.origin()) || old.rotation()!=site.rotation() || !Objects.equals(old.initiatorId(),site.initiatorId())
                || old.revision()==Long.MAX_VALUE || site.revision()!=old.revision()+1 || site.cursor()>definition(site.blueprintDigest()).blocks().size()
                || site.cursor()<old.cursor() || site.consumed()<old.consumed() || site.claimRevision()<old.claimRevision()
                || site.consumed()-old.consumed()>site.cursor()-old.cursor() || old.closed())
            throw new IllegalArgumentException("Construction identity or revision mismatch");
        registry.beforeMutation(); sites.put(site.workId(),site);
    }
    public void accept(UUID colonyId) {
        registry.requireOwner();
        for(var entry:sites.entrySet()) { var site=entry.getValue(); if(site.colonyId().equals(colonyId) && !site.closed())
            entry.setValue(new ConstructionSnapshot(site.workId(),site.colonyId(),site.blueprintDigest(),site.origin(),site.rotation(),site.initiatorId(),site.cursor(),site.consumed(),site.claimRevision(),site.revision()+1,true)); }
    }
    /** After verified checkpoint only; retained witnesses keep their work, site and pin alive. */
    public void compactAfterVerifiedCheckpoint() {
        registry.requireOwner(); var witnesses=new HashSet<UUID>();
        if(registry.colonies().stream().anyMatch(value -> value.recoveryBlocked() || value.contentBlocked())) return;
        for(var effect:registry.effects().snapshots()) if(effect.workId()!=null) witnesses.add(effect.workId());
        for(var work:registry.workBoard().works()) witnesses.addAll(work.dependencies());
        for(var citizen:registry.citizensView()) if(citizen.assignedWorkId()!=null) witnesses.add(citizen.assignedWorkId());
        for(var claim:registry.targetClaims().snapshots()) witnesses.add(claim.ownerId());
        boolean mutated=false;
        for(var iterator=sites.entrySet().iterator();iterator.hasNext();) {
            var entry=iterator.next(); var site=entry.getValue();
            if(!site.closed() || !registry.workBoard().work(site.workId()).terminal() || witnesses.contains(site.workId())) continue;
            if(!mutated) { registry.beforeMutation(); mutated=true; }
            iterator.remove(); leases.remove(site.workId()).close(); registry.workBoard().retire(site.workId());
        }
        var referenced=new HashSet<String>(); for(var site:sites.values()) referenced.add(site.blueprintDigest());
        for(var iterator=pins.entrySet().iterator();iterator.hasNext();) {
            var entry=iterator.next(); if(referenced.contains(entry.getKey())) continue;
            if(!mutated) { registry.beforeMutation(); mutated=true; }
            pinnedBytes-=estimatedBytes(entry.getValue()); iterator.remove();
        }
    }
    private static void validatePin(BlueprintDefinition definition,Map<String,BlueprintDefinition> pins,long bytes) {
        var old=pins.get(definition.digest());
        if(old!=null) { if(!old.equals(definition)) throw new IllegalArgumentException("Pinned hash collision"); return; }
        if(pins.size()>=MAX_VERSIONS || estimatedBytes(definition)>MAX_PINNED_BYTES-bytes) throw new IllegalArgumentException("Pinned definition capacity exceeded");
    }
    // Shared palette descriptors are charged once; positions only carry an offset and palette index.
    public static long estimatedBytes(BlueprintDefinition definition) {
        long bytes=1024+definition.id().length()*4L+definition.markers().size()*512L+definition.blocks().size()*64L;
        var palette=new HashSet<io.github.kpuctajluk.colonyloom.core.content.BlockDescriptor>();
        for(var spec:definition.blocks()) if(palette.add(spec.block())) {
            if(palette.size()>512) throw new IllegalArgumentException("Pinned palette cap");
            var block=spec.block(); bytes+=256+4L*(block.blockId().length()+block.itemId().length());
            for(var property:block.properties().entrySet()) bytes+=64+4L*(property.getKey().length()+property.getValue().length());
        }
        return bytes;
    }
    public PreparedRestore prepareRestore(List<ConstructionSnapshot> saved,List<BlueprintDefinition> definitions,AdmissionLedger replacement) {
        registry.requireOwner(); if(saved.size()>MAX_SITES || definitions.size()>MAX_VERSIONS) throw new IllegalArgumentException("Construction envelope exceeded");
        Map<String,BlueprintDefinition> stagedPins=new LinkedHashMap<>(); long bytes=0;
        for(var definition:definitions) { validatePin(definition,stagedPins,bytes); if(stagedPins.putIfAbsent(definition.digest(),definition)!=null) throw new IllegalArgumentException("Duplicate pinned definition"); bytes+=estimatedBytes(definition); }
        Map<UUID,ConstructionSnapshot> staged=new LinkedHashMap<>(); Map<UUID,AdmissionLedger.Lease> admitted=new LinkedHashMap<>();
        try { for(var site:saved) {
            var definition=stagedPins.get(site.blueprintDigest());
            if(definition==null || !definition.markers().containsKey("work_origin") || site.cursor()>definition.blocks().size() || staged.putIfAbsent(site.workId(),site)!=null) throw new IllegalArgumentException("Invalid construction pin or duplicate work");
            admitted.put(site.workId(),replacement.reserve(site.colonyId(),Lane.NORMAL,Map.of(Resource.EVIDENCE,1)));
        } } catch(RuntimeException failure) { admitted.values().forEach(AdmissionLedger.Lease::close); throw failure; }
        return new PreparedRestore(staged,stagedPins,admitted,bytes);
    }
    public final class PreparedRestore implements AutoCloseable {
        private Map<UUID,ConstructionSnapshot> staged; private final Map<String,BlueprintDefinition> stagedPins;
        private Map<UUID,AdmissionLedger.Lease> admitted; private final long bytes;
        private PreparedRestore(Map<UUID,ConstructionSnapshot> staged,Map<String,BlueprintDefinition> pins,Map<UUID,AdmissionLedger.Lease> admitted,long bytes) { this.staged=staged; stagedPins=pins; this.admitted=admitted; this.bytes=bytes; }
        public void commit() { registry.requireOwner(); if(staged==null) throw new IllegalStateException("Restore already closed");
            leases.values().forEach(AdmissionLedger.Lease::close); sites.clear(); sites.putAll(staged); pins.clear(); pins.putAll(stagedPins); leases.clear(); leases.putAll(admitted); pinnedBytes=bytes; recordPins(); staged=null; admitted=null; }
        public void close() { if(admitted!=null) { admitted.values().forEach(AdmissionLedger.Lease::close); admitted=null; staged=null; } }
    }
}
