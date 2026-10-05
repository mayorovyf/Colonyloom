package io.github.kpuctajluk.colonyloom.minecraft.persistence;

import io.github.kpuctajluk.colonyloom.core.persistence.RegistrySnapshot;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.Tag;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.saveddata.SavedData;

/** Minecraft persistence adapter; ServerRuntime remains the authoritative registry owner. */
public final class ColonySavedData extends SavedData {
    public static final String NAME = "colonyloom";
    public static final int SCHEMA_VERSION = 2;
    private final Map<String, List<CompoundTag>> retained;
    private final Set<UUID> contentBlockedColonies;
    private RegistrySnapshot snapshot;
    private UUID checkpointId;
    private java.util.function.Supplier<RegistrySnapshot> snapshotSource;
    private io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics metrics;
    public void metrics(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics value) { metrics=java.util.Objects.requireNonNull(value); }

    private ColonySavedData(RegistrySnapshot snapshot, UUID checkpointId,
            Map<String, List<CompoundTag>> retained, Set<UUID> contentBlockedColonies) {
        this.snapshot = snapshot;
        this.checkpointId = checkpointId;
        this.retained = retained;
        this.contentBlockedColonies = Set.copyOf(contentBlockedColonies);
    }

    public static ColonySavedData empty(RegistrySnapshot snapshot) {
        return new ColonySavedData(snapshot, UUID.randomUUID(), Map.of(), Set.of());
    }

    public static ColonySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryNbt.Decoded decoded = RegistryNbt.decode(tag);
        return new ColonySavedData(decoded.snapshot(), RegistryNbt.uuid(tag, "checkpointId"),
                decoded.retained(), decoded.blockedColonies());
    }
    /** Bounded preflight; keeps the original compressed checkpoint before a supported migration. */
    public static ColonySavedData preflight(Path path, HolderLookup.Provider registries) throws IOException {
        try {
            var tag = RegistryNbt.compound(DurableNbt.read(path, DurableNbt.STATE_LIMIT), "data");
            var loaded = load(tag, registries);
            boolean migration=RegistryNbt.integer(tag,"schemaVersion")==1;
            var production = tag.getList("productionOrders", Tag.TAG_COMPOUND);
            if (production.size() > io.github.kpuctajluk.colonyloom.core.supply.SupplyRegistry.MAX_ORDERS) throw new IllegalArgumentException("Production migration envelope exceeded");
            for (int i = 0; i < production.size(); i++) {
                var order = production.getCompound(i);
                if (order.getString("typeId").equals(SupplyNbt.PRODUCTION) && order.getInt("schemaVersion") == 1) migration=true;
            }
            if(migration) DurableNbt.immutableBackup(path,path.resolveSibling("colonyloom-backups").resolve(loaded.checkpointId()+"-v"+RegistryNbt.integer(tag,"schemaVersion")+".dat"));
            return loaded;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Colonyloom state: " + path, exception);
        }
    }


    public UUID checkpointId() {
        return checkpointId;
    }

    public RegistrySnapshot snapshot() {
        return snapshot;
    }

    public Set<UUID> contentBlockedColonies() {
        return contentBlockedColonies;
    }

    public int retainedRecordCount() {
        return retained.values().stream().mapToInt(List::size).sum();
    }
    /** Opaque citizen identity cannot be republished as an unknown live binding. */
    public boolean retainsCitizen(UUID citizenId) {
        for(var entry:retained.getOrDefault("citizens",List.of()))
            if(entry.hasUUID("citizenId")&&entry.getUUID("citizenId").equals(citizenId)) return true;
        return false;
    }
    /** Bounded metadata only. Opaque payloads never leave the persistence adapter. */
    public record RetainedDiagnostic(String list,String type,String schema,String objectId,String dependency,String reason) {}
    public record RetainedReport(int opaqueCount,List<RetainedDiagnostic> records,boolean truncated) {}
    public RetainedReport retainedReport(UUID colonyId) {
        var result=new java.util.ArrayList<RetainedDiagnostic>();int count=0;
        for(var group:retained.entrySet())for(var entry:group.getValue()) {
            if(entry.hasUUID("colonyId") && !entry.getUUID("colonyId").equals(colonyId))continue;
            count++;
            if(result.size()>=32)continue;
            String object="UNKNOWN",dependency="UNKNOWN";
            for(String key:List.of("operationId","citizenId","workId","buildingId","colonyId","demandId","obligationId"))if(entry.hasUUID(key)){object=key+"="+entry.getUUID(key);break;}
            for(String key:List.of("assignedWorkId","productionId","registrationId","blueprintDigest","recipeDigest")) {
                if(entry.hasUUID(key)){dependency=key+"="+entry.getUUID(key);break;}
                if(entry.contains(key,Tag.TAG_STRING)){dependency=key+"="+boundedMetadata(entry.getString(key));break;}
            }
            String type=boundedMetadata(entry.getString("typeId"));
            String schema=entry.contains("schemaVersion",Tag.TAG_INT)?Integer.toString(entry.getInt("schemaVersion")):"UNVERSIONED";
            for(String key:List.of("death","food","craft","transfer"))if(entry.contains(key,Tag.TAG_COMPOUND)) {
                var nested=entry.getCompound(key);schema+=" "+key+"="+(nested.contains("schemaVersion",Tag.TAG_INT)?nested.getInt("schemaVersion"):"UNVERSIONED");
            }
            result.add(new RetainedDiagnostic(group.getKey(),type,schema,object,dependency,"unsupported type/schema or unavailable dependency; retained unchanged, colony unavailable"));
        }
        return new RetainedReport(count,List.copyOf(result),count>result.size());
    }
    private static String boundedMetadata(String value) {return value.length()<=256?value:value.substring(0,256);}

    public void bindSnapshotSource(java.util.function.Supplier<RegistrySnapshot> source) {
        snapshotSource = java.util.Objects.requireNonNull(source);
    }
    public void capture(RegistrySnapshot snapshot) {
        this.snapshot = snapshot;
        setDirty();
    }

    public void beginCheckpoint(UUID checkpointId, RegistrySnapshot snapshot) {
        this.checkpointId = checkpointId;
        capture(snapshot);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        long start=metrics==null ? 0 : System.nanoTime();
        try {
            // Vanilla autosave must capture current owner-thread authority, not a previous checkpoint.
            if (snapshotSource != null) snapshot = snapshotSource.get();
            return RegistryNbt.encode(tag, snapshot, checkpointId, retained);
        } finally { if(metrics!=null) metrics.record(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer.SAVE_ENCODE,System.nanoTime()-start); }
    }

    /** Encodes the explicitly captured DTO without querying its live source a second time. */
    CompoundTag diskEnvelope() {
        long start=metrics==null ? 0 : System.nanoTime();
        try {
            CompoundTag envelope = new CompoundTag();
            envelope.put("data", RegistryNbt.encode(new CompoundTag(), snapshot, checkpointId, retained));
            NbtUtils.addCurrentDataVersion(envelope);
            return envelope;
        } finally { if(metrics!=null) metrics.record(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer.SAVE_ENCODE,System.nanoTime()-start); }
    }
}
