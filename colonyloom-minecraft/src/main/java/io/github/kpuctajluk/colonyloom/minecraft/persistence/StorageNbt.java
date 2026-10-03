package io.github.kpuctajluk.colonyloom.minecraft.persistence;

import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.storage.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Additive schema-one stock records; observations deliberately rebuild as UNKNOWN. */
final class StorageNbt {
    static final String REGISTRATION="colonyloom:storage_registration", WORKSHOP="colonyloom:stock_workshop";
    static final String RESERVATION="colonyloom:stock_reservation", ALLOCATION="colonyloom:stock_allocation";
    static final String RETIRED="colonyloom:storage_retired";
    private StorageNbt() {}
    static boolean known(String key,String type) {
        return key.equals("evidence") && (type.equals(REGISTRATION)||type.equals(WORKSHOP)||type.equals(RETIRED))
                || key.equals("reservations") && type.equals(RESERVATION)
                || key.equals("allocations") && type.equals(ALLOCATION);
    }
    static CompoundTag registration(StorageRegistry.Registration value) {
        CompoundTag tag=typed(REGISTRATION);tag.putUUID("registrationId",value.id());tag.putUUID("colonyId",value.colonyId());
        tag.put("address",position(value.address()));tag.putString("role",value.role());tag.putLong("revision",value.revision());
        ListTag sources=new ListTag();
        for(int i=0;i<value.storages().size();i++) {
            CompoundTag source=storage(value.storages().get(i));source.put("position",position(value.positions().get(i)));sources.add(source);
        }
        tag.put("sources",sources);ListTag slots=new ListTag();for(StockRegion slot:value.slots())slots.add(slot(slot));tag.put("slots",slots);return tag;
    }
    static StorageRegistry.Registration registration(CompoundTag tag) {
        ListTag sources=list(tag,"sources",2),slots=list(tag,"slots",54);
        List<StorageId> ids=new ArrayList<>();List<WorldPosition> positions=new ArrayList<>();List<StockRegion> regions=new ArrayList<>();
        for(Tag raw:sources){CompoundTag source=(CompoundTag)raw;ids.add(storage(source));positions.add(position(RegistryNbt.compound(source,"position")));}
        for(Tag raw:slots)regions.add(slot((CompoundTag)raw));
        return new StorageRegistry.Registration(RegistryNbt.uuid(tag,"registrationId"),RegistryNbt.uuid(tag,"colonyId"),position(RegistryNbt.compound(tag,"address")),text(tag,"role"),ids,regions,number(tag,"revision"),positions);
    }
    static CompoundTag workshop(StorageRegistry.Workshop value) {
        CompoundTag tag=typed(WORKSHOP);tag.putUUID("buildingId",value.id());tag.putUUID("colonyId",value.colonyId());tag.put("position",position(value.position()));tag.putUUID("registrationId",value.registrationId());tag.putLong("revision",value.revision());return tag;
    }
    static StorageRegistry.Workshop workshop(CompoundTag tag) {
        return new StorageRegistry.Workshop(RegistryNbt.uuid(tag,"buildingId"),RegistryNbt.uuid(tag,"colonyId"),position(RegistryNbt.compound(tag,"position")),RegistryNbt.uuid(tag,"registrationId"),number(tag,"revision"));
    }
    static CompoundTag retired(StorageRegistry.RetiredIdentity value) {CompoundTag tag=typed(RETIRED);tag.putUUID("colonyId",value.colonyId());tag.put("storage",storage(value.storage()));return tag;}
    static StorageRegistry.RetiredIdentity retired(CompoundTag tag) {return new StorageRegistry.RetiredIdentity(storage(RegistryNbt.compound(tag,"storage")),RegistryNbt.uuid(tag,"colonyId"));}
    static CompoundTag reservation(ReservationLedger.Entry value) {return obligation(RESERVATION,value.id(),value.colonyId(),value.ownerId(),value.slot(),value.item(),value.count(),value.revision(),value.lane());}
    static CompoundTag allocation(AllocationLedger.Entry value) {return obligation(ALLOCATION,value.id(),value.colonyId(),value.ownerId(),value.slot(),value.item(),value.count(),value.revision(),value.lane());}
    static ReservationLedger.Entry reservation(CompoundTag tag) {return new ReservationLedger.Entry(RegistryNbt.uuid(tag,"obligationId"),RegistryNbt.uuid(tag,"colonyId"),RegistryNbt.uuid(tag,"ownerId"),slot(RegistryNbt.compound(tag,"slot")),item(RegistryNbt.compound(tag,"item")),number(tag,"count"),number(tag,"revision"),lane(tag));}
    static AllocationLedger.Entry allocation(CompoundTag tag) {return new AllocationLedger.Entry(RegistryNbt.uuid(tag,"obligationId"),RegistryNbt.uuid(tag,"colonyId"),RegistryNbt.uuid(tag,"ownerId"),slot(RegistryNbt.compound(tag,"slot")),item(RegistryNbt.compound(tag,"item")),number(tag,"count"),number(tag,"revision"),lane(tag));}
    private static io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane lane(CompoundTag tag) {
        return tag.contains("lane") ? io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.valueOf(text(tag,"lane")) : io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.NORMAL;
    }
    private static CompoundTag obligation(String type,java.util.UUID id,java.util.UUID colony,java.util.UUID owner,StockRegion slot,ItemDescriptor item,long count,long revision,io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane lane) {
        CompoundTag tag=typed(type);tag.putUUID("obligationId",id);tag.putUUID("colonyId",colony);tag.putUUID("ownerId",owner);tag.put("slot",slot(slot));tag.put("item",item(item));tag.putLong("count",count);tag.putLong("revision",revision);tag.putString("lane",lane.name());return tag;
    }
    static CompoundTag storage(StorageId value) {CompoundTag tag=new CompoundTag();tag.putString("dimension",value.dimension());tag.putUUID("identity",value.identity());tag.putLong("bindingEpoch",value.bindingEpoch());return tag;}
    static StorageId storage(CompoundTag tag) {return new StorageId(text(tag,"dimension"),RegistryNbt.uuid(tag,"identity"),number(tag,"bindingEpoch"));}
    static CompoundTag slot(StockRegion value) {CompoundTag tag=storage(value.storage());tag.putInt("slot",value.slot());return tag;}
    static StockRegion slot(CompoundTag tag) {require(tag,"slot",Tag.TAG_INT);return new StockRegion(storage(tag),tag.getInt("slot"));}
    static CompoundTag item(ItemDescriptor value) {CompoundTag tag=new CompoundTag();tag.putString("itemId",value.itemId());tag.putByteArray("components",value.canonicalComponents());return tag;}
    static ItemDescriptor item(CompoundTag tag) {require(tag,"components",Tag.TAG_BYTE_ARRAY);byte[] bytes=tag.getByteArray("components");if(bytes.length>8192)throw new IllegalArgumentException("Stock component envelope exceeded");return new ItemDescriptor(text(tag,"itemId"),bytes);}
    static CompoundTag position(WorldPosition value) {CompoundTag tag=new CompoundTag();tag.putString("dimension",value.dimension());tag.putInt("x",value.x());tag.putInt("y",value.y());tag.putInt("z",value.z());return tag;}
    static WorldPosition position(CompoundTag tag) {require(tag,"x",Tag.TAG_INT);require(tag,"y",Tag.TAG_INT);require(tag,"z",Tag.TAG_INT);return new WorldPosition(text(tag,"dimension"),tag.getInt("x"),tag.getInt("y"),tag.getInt("z"));}
    private static CompoundTag typed(String type) {CompoundTag tag=new CompoundTag();tag.putString("typeId",type);return tag;}
    private static String text(CompoundTag tag,String key) {require(tag,key,Tag.TAG_STRING);String value=tag.getString(key);if(value.isEmpty()||value.length()>256)throw new IllegalArgumentException("Invalid stock "+key);return value;}
    private static long number(CompoundTag tag,String key) {require(tag,key,Tag.TAG_LONG);long value=tag.getLong(key);if(value<0)throw new IllegalArgumentException("Negative stock "+key);return value;}
    private static ListTag list(CompoundTag tag,String key,int maximum) {ListTag list=RegistryNbt.list(tag,key);if(list.size()>maximum)throw new IllegalArgumentException("Stock "+key+" cap");return list;}
    private static void require(CompoundTag tag,String key,int type) {if(!tag.contains(key,type))throw new IllegalArgumentException("Missing/invalid stock "+key);}
}
