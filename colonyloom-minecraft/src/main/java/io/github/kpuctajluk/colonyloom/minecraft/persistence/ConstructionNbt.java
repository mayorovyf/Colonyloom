package io.github.kpuctajluk.colonyloom.minecraft.persistence;

import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.action.EffectRecord;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.construction.ConstructionSnapshot;
import io.github.kpuctajluk.colonyloom.core.content.BlockDescriptor;
import io.github.kpuctajluk.colonyloom.core.content.BlockOffset;
import io.github.kpuctajluk.colonyloom.core.content.BlueprintDefinition;
import java.util.ArrayList;
import io.github.kpuctajluk.colonyloom.minecraft.content.ContentLoader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/** Explicit bounded DTOs; no reflective or runtime-graph serialization. */
final class ConstructionNbt {
    static final String SITE="colonyloom:construction_site", EFFECT="colonyloom:effect", PIN="colonyloom:blueprint_snapshot";
    private ConstructionNbt() {}
    static CompoundTag site(ConstructionSnapshot value) {
        var tag=typed(SITE); tag.putUUID("workId",value.workId()); tag.putUUID("colonyId",value.colonyId());
        tag.putString("blueprintDigest",value.blueprintDigest()); tag.put("origin",position(value.origin())); tag.putInt("rotation",value.rotation());
        if(value.initiatorId()!=null) tag.putUUID("initiatorId",value.initiatorId()); tag.putInt("cursor",value.cursor()); tag.putInt("consumed",value.consumed());
        tag.putLong("claimRevision",value.claimRevision()); tag.putLong("revision",value.revision()); tag.putBoolean("closed",value.closed()); return tag;
    }
    static ConstructionSnapshot site(CompoundTag tag) {
        return new ConstructionSnapshot(uuid(tag,"workId"),uuid(tag,"colonyId"),text(tag,"blueprintDigest",64),position(compound(tag,"origin")),
                integer(tag,"rotation"),tag.contains("initiatorId")?uuid(tag,"initiatorId"):null,integer(tag,"cursor"),integer(tag,"consumed"),number(tag,"claimRevision"),number(tag,"revision"),RegistryNbt.bool(tag,"closed"));
    }
    static CompoundTag effect(EffectRecord value) {
        var tag=typed(EFFECT); tag.putUUID("operationId",value.operationId()); tag.putUUID("colonyId",value.colonyId()); tag.putUUID("citizenId",value.citizenId());
        if(value.workId()!=null) tag.putUUID("workId",value.workId()); tag.putLong("bindingEpoch",value.bindingEpoch()); tag.putString("kind",value.kind().name());
        tag.put("target",position(value.target())); tag.putString("expectedBlock",value.expectedBlock()); tag.putString("itemId",value.itemId());
        tag.putInt("countBefore",value.countBefore()); tag.putInt("countAfter",value.countAfter()); tag.putString("state",value.state().name()); tag.putLong("revision",value.revision());
        if(value.transfer()!=null) tag.put("transfer",transfer(value.transfer()));
        if(value.craft()!=null) {
            tag.put("craft",craft(value.craft())); tag.putUUID("productionId",value.craft().productionId());
            tag.putUUID("buildingId",value.craft().workshopId());
        }
        if(value.food()!=null)tag.put("food",food(value.food()));
        return tag;
    }
    static EffectRecord effect(CompoundTag tag) {
        return new EffectRecord(uuid(tag,"operationId"),uuid(tag,"colonyId"),tag.contains("workId")?uuid(tag,"workId"):null,uuid(tag,"citizenId"),number(tag,"bindingEpoch"),
                ActionContext.Kind.valueOf(text(tag,"kind",64)),position(compound(tag,"target")),boundedText(tag,"expectedBlock",1024),boundedText(tag,"itemId",256),
                integer(tag,"countBefore"),integer(tag,"countAfter"),EffectRecord.State.valueOf(text(tag,"state",64)),number(tag,"revision"),
                tag.contains("transfer")?transfer(compound(tag,"transfer")):null,tag.contains("craft")?craft(compound(tag,"craft")):null,
                tag.contains("food")?food(compound(tag,"food")):null);
    }
    private static CompoundTag transfer(EffectRecord.Transfer value) {
        var tag=new CompoundTag(); tag.putInt("schemaVersion",1);
        tag.put("source",StorageNbt.slot(value.source())); tag.put("destination",StorageNbt.slot(value.destination())); tag.put("item",StorageNbt.item(value.item()));
        tag.putInt("sourceBefore",value.sourceBefore()); tag.putInt("sourceAfter",value.sourceAfter());
        tag.putInt("destinationBefore",value.destinationBefore()); tag.putInt("destinationAfter",value.destinationAfter());
        tag.putInt("maximum",value.maximum()); tag.putInt("extracted",value.extracted()); tag.putInt("inserted",value.inserted()); tag.putInt("returned",value.returned()); return tag;
    }
    private static EffectRecord.Transfer transfer(CompoundTag tag) {
        if(integer(tag,"schemaVersion")!=1) throw new IllegalArgumentException("Unsupported transfer evidence schema");
        return new EffectRecord.Transfer(StorageNbt.slot(compound(tag,"source")),StorageNbt.slot(compound(tag,"destination")),StorageNbt.item(compound(tag,"item")),
                integer(tag,"sourceBefore"),integer(tag,"sourceAfter"),integer(tag,"destinationBefore"),integer(tag,"destinationAfter"),
                integer(tag,"maximum"),integer(tag,"extracted"),integer(tag,"inserted"),integer(tag,"returned"));
    }
    private static CompoundTag craft(EffectRecord.Craft value) {
        var tag=new CompoundTag(); tag.putInt("schemaVersion",1); tag.putUUID("productionId",value.productionId()); tag.putLong("batchOrdinal",value.batchOrdinal());
        tag.putUUID("workshopId",value.workshopId()); tag.putUUID("registrationId",value.registrationId()); tag.putLong("workshopRevision",value.workshopRevision());
        tag.put("table",position(value.table())); tag.put("inventory",position(value.inventory())); tag.putString("recipeId",value.recipeId());
        tag.putInt("recipeVersion",value.recipeVersion()); tag.putString("recipeDigest",value.recipeDigest()); tag.putString("phase",value.phase().name());
        tag.put("output",StorageNbt.item(value.output())); tag.putInt("outputCount",value.outputCount());
        tag.put("inputs",craftSlots(value.inputs())); tag.put("outputs",craftSlots(value.outputs())); return tag;
    }
    private static EffectRecord.Craft craft(CompoundTag tag) {
        if(integer(tag,"schemaVersion")!=1) throw new IllegalArgumentException("Unsupported craft evidence schema");
        return new EffectRecord.Craft(uuid(tag,"productionId"),number(tag,"batchOrdinal"),uuid(tag,"workshopId"),uuid(tag,"registrationId"),number(tag,"workshopRevision"),
                position(compound(tag,"table")),position(compound(tag,"inventory")),text(tag,"recipeId",256),integer(tag,"recipeVersion"),text(tag,"recipeDigest",64),
                craftSlots(RegistryNbt.list(tag,"inputs")),StorageNbt.item(compound(tag,"output")),integer(tag,"outputCount"),
                craftSlots(RegistryNbt.list(tag,"outputs")),EffectRecord.CraftPhase.valueOf(text(tag,"phase",64)));
    }
    private static ListTag craftSlots(java.util.List<EffectRecord.CraftSlot> values) {
        var list=new ListTag();
        for(var value:values) {
            var tag=new CompoundTag(); tag.put("slot",StorageNbt.slot(value.slot())); tag.putInt("beforeCount",value.beforeCount()); tag.putInt("afterCount",value.afterCount());
            tag.putInt("amount",value.amount()); if(value.beforeItem()!=null) tag.put("beforeItem",StorageNbt.item(value.beforeItem()));
            if(value.afterItem()!=null) tag.put("afterItem",StorageNbt.item(value.afterItem())); list.add(tag);
        }
        return list;
    }
    private static java.util.List<EffectRecord.CraftSlot> craftSlots(ListTag values) {
        if(values.isEmpty() || values.size()>EffectRecord.Craft.MAX_SLOTS) throw new IllegalArgumentException("Craft slot envelope exceeded");
        var result=new ArrayList<EffectRecord.CraftSlot>(values.size());
        for(var value:values) {
            var tag=(CompoundTag)value;
            result.add(new EffectRecord.CraftSlot(StorageNbt.slot(compound(tag,"slot")),tag.contains("beforeItem")?StorageNbt.item(compound(tag,"beforeItem")):null,
                    integer(tag,"beforeCount"),tag.contains("afterItem")?StorageNbt.item(compound(tag,"afterItem")):null,integer(tag,"afterCount"),integer(tag,"amount")));
        }
        return result;
    }
    private static CompoundTag food(EffectRecord.Food value) {
        var tag=new CompoundTag();tag.putInt("schemaVersion",1);tag.put("slot",StorageNbt.slot(value.slot()));tag.put("item",StorageNbt.item(value.item()));
        tag.putUUID("shareId",value.shareId());tag.putUUID("demandId",value.demandId());tag.putInt("foodBefore",value.foodBefore());tag.putInt("foodAfter",value.foodAfter());
        tag.putLong("timerBefore",value.timerBefore());tag.putLong("timerAfter",value.timerAfter());return tag;
    }
    private static EffectRecord.Food food(CompoundTag tag) {
        if(integer(tag,"schemaVersion")!=1)throw new IllegalArgumentException("Unsupported food evidence schema");
        return new EffectRecord.Food(StorageNbt.slot(compound(tag,"slot")),StorageNbt.item(compound(tag,"item")),uuid(tag,"shareId"),uuid(tag,"demandId"),
                integer(tag,"foodBefore"),integer(tag,"foodAfter"),number(tag,"timerBefore"),number(tag,"timerAfter"));
    }
    static final int MAX_PALETTE = 512;
    static CompoundTag blueprint(BlueprintDefinition definition) {
        var tag=typed(PIN); tag.putInt("schemaVersion",1); tag.putString("id",definition.id()); tag.putInt("version",definition.version()); tag.putString("digest",definition.digest());
        Map<BlockDescriptor,Integer> indices=new LinkedHashMap<>();
        var palette=new ListTag(); var blocks=new ListTag();
        for(var spec:definition.blocks()) {
            Integer index=indices.get(spec.block());
            if(index==null) {
                if(indices.size()>=MAX_PALETTE) throw new IllegalArgumentException("Pinned palette cap");
                ContentLoader.decodeBlockState(spec.block());
                index=indices.size(); indices.put(spec.block(),index);
                var descriptor=new CompoundTag(); descriptor.putString("blockId",spec.block().blockId()); descriptor.putString("itemId",spec.block().itemId());
                var properties=new CompoundTag(); spec.block().properties().forEach(properties::putString); descriptor.put("properties",properties); palette.add(descriptor);
            }
            var entry=offset(spec.offset()); entry.putInt("state",index); blocks.add(entry);
        }
        tag.put("palette",palette); tag.put("blocks",blocks);
        var markers=new CompoundTag(); definition.markers().forEach((name,value) -> markers.put(name,offset(value))); tag.put("markers",markers); return tag;
    }
    static boolean knownBlueprintSchema(CompoundTag tag) { return integer(tag,"schemaVersion")==1; }
    static boolean knownEffect(CompoundTag tag) {
        String kind=text(tag,"kind",64), state=text(tag,"state",64);
        boolean knownKind=false, knownState=false;
        for(var value:ActionContext.Kind.values()) knownKind |= value.name().equals(kind);
        for(var value:EffectRecord.State.values()) knownState |= value.name().equals(state);
        boolean knownCraft=true;
        if(tag.contains("craft")) {
            var craft=compound(tag,"craft");
            if(integer(craft,"schemaVersion")!=1) return false;
            knownCraft=false;
            for(var phase:EffectRecord.CraftPhase.values()) knownCraft |= phase.name().equals(text(craft,"phase",64));
        }
        return knownKind && knownState && knownCraft && (!tag.contains("transfer") || integer(compound(tag,"transfer"),"schemaVersion")==1)
                &&(!tag.contains("food")||integer(compound(tag,"food"),"schemaVersion")==1);
    }
    static BlueprintDefinition blueprint(CompoundTag tag) {
        if(!knownBlueprintSchema(tag)) throw new IllegalArgumentException("Unsupported pinned blueprint schema");
        var palette=RegistryNbt.list(tag,"palette");
        if(palette.isEmpty() || palette.size()>MAX_PALETTE) throw new IllegalArgumentException("Pinned palette cap");
        var descriptors=new ArrayList<BlockDescriptor>(palette.size());
        for(var value:palette) {
            var entry=(CompoundTag)value; var properties=compound(entry,"properties");
            if(properties.size()>16) throw new IllegalArgumentException("Pinned property cap");
            Map<String,String> map=new LinkedHashMap<>(); for(var key:properties.getAllKeys()) map.put(key,text(properties,key,64));
            var descriptor=new BlockDescriptor(text(entry,"blockId",256),map,text(entry,"itemId",256));
            ContentLoader.decodeBlockState(descriptor); descriptors.add(descriptor);
        }
        var blocks=RegistryNbt.list(tag,"blocks");
        if(blocks.isEmpty() || blocks.size()>BlueprintDefinition.MAX_BLOCKS) throw new IllegalArgumentException("Invalid pinned blocks");
        var decoded=new ArrayList<BlueprintDefinition.BlockSpec>(blocks.size());
        for(var value:blocks) {
            var entry=(CompoundTag)value; int index=integer(entry,"state");
            if(index<0 || index>=descriptors.size()) throw new IllegalArgumentException("Invalid pinned palette index");
            decoded.add(new BlueprintDefinition.BlockSpec(offset(entry),descriptors.get(index)));
        }
        var markers=compound(tag,"markers"); if(markers.size()>16) throw new IllegalArgumentException("Pinned marker cap"); Map<String,BlockOffset> decodedMarkers=new LinkedHashMap<>();
        for(var name:markers.getAllKeys()) decodedMarkers.put(name,offset(compound(markers,name)));
        return new BlueprintDefinition(text(tag,"id",256),integer(tag,"version"),text(tag,"digest",64),decoded,decodedMarkers);
    }
    /** Counts the actual uncompressed NBT wire representation, including opaque future payloads. */
    static void validatePinnedEnvelope(ListTag pins) {
        if(pins.size()>64) throw new IllegalArgumentException("Pinned version capacity exceeded");
        var counter=new OutputStream() {
            private long count;
            @Override public void write(int value) { add(1); }
            @Override public void write(byte[] value,int offset,int length) { add(length); }
            private void add(int length) {
                count+=length;
                if(count>64L*1024*1024) throw new IllegalArgumentException("Pinned NBT byte capacity exceeded");
            }
        };
        try(var output=new DataOutputStream(counter)) {
            for(var pin:pins) NbtIo.write((CompoundTag)pin,output);
        } catch(IOException failure) { throw new IllegalArgumentException("Cannot measure pinned NBT",failure); }
    }
    private static CompoundTag typed(String type) { var tag=new CompoundTag(); tag.putString("typeId",type); return tag; }
    private static CompoundTag offset(BlockOffset value) { var tag=new CompoundTag(); tag.putInt("x",value.x()); tag.putInt("y",value.y()); tag.putInt("z",value.z()); return tag; }
    private static BlockOffset offset(CompoundTag tag) { return new BlockOffset(integer(tag,"x"),integer(tag,"y"),integer(tag,"z")); }
    private static CompoundTag position(WorldPosition value) { var tag=new CompoundTag(); tag.putString("dimension",value.dimension()); tag.putInt("x",value.x()); tag.putInt("y",value.y()); tag.putInt("z",value.z()); return tag; }
    private static WorldPosition position(CompoundTag tag) { return new WorldPosition(text(tag,"dimension",256),integer(tag,"x"),integer(tag,"y"),integer(tag,"z")); }
    private static UUID uuid(CompoundTag tag,String key) { return RegistryNbt.uuid(tag,key); }
    private static CompoundTag compound(CompoundTag tag,String key) { return RegistryNbt.compound(tag,key); }
    private static String text(CompoundTag tag,String key,int maximum) { require(tag,key,Tag.TAG_STRING); var text=tag.getString(key); if(text.isEmpty() || text.length()>maximum) throw new IllegalArgumentException("Invalid "+key); return text; }
    private static String boundedText(CompoundTag tag,String key,int maximum) { require(tag,key,Tag.TAG_STRING); var text=tag.getString(key); if(text.length()>maximum) throw new IllegalArgumentException("Invalid "+key); return text; }
    private static int integer(CompoundTag tag,String key) { require(tag,key,Tag.TAG_INT); return tag.getInt(key); }
    private static long number(CompoundTag tag,String key) { require(tag,key,Tag.TAG_LONG); return tag.getLong(key); }
    private static void require(CompoundTag tag,String key,int type) { if(!tag.contains(key,type)) throw new IllegalArgumentException("Missing/invalid "+key); }
}
