package io.github.kpuctajluk.colonyloom.minecraft.persistence;

import io.github.kpuctajluk.colonyloom.core.supply.*;
import io.github.kpuctajluk.colonyloom.core.production.ProductionOrder;
import io.github.kpuctajluk.colonyloom.core.logistics.DeliveryOrder;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import java.util.*;
import net.minecraft.nbt.*;

/** Explicit schema-one supply DTOs. Recipe snapshots are shared by digest, never re-resolved on load. */
final class SupplyNbt {
    static final String DEMAND="colonyloom:demand",SHARE="colonyloom:coverage_share",PRODUCTION="colonyloom:production_order",DELIVERY="colonyloom:delivery_order",PIN="colonyloom:recipe_snapshot";
    private SupplyNbt() {}
    static boolean known(String key,String type) {
        return key.equals("demands") && type.equals(DEMAND) || key.equals("evidence") && type.equals(SHARE)
                || key.equals("productionOrders") && type.equals(PRODUCTION) || key.equals("deliveries") && type.equals(DELIVERY)
                || key.equals("pinnedDefinitions") && type.equals(PIN);
    }
    static Set<UUID> opaqueColonies(Map<String,List<CompoundTag>> retained) {
        Set<UUID> result=new HashSet<>();
        for(String key:List.of("demands","productionOrders","deliveries","evidence")) for(var tag:retained.get(key)) {
            if((!key.equals("evidence") || tag.hasUUID("demandId") || tag.hasUUID("ownerDemandId") || tag.hasUUID("productionId")) && tag.hasUUID("colonyId")) result.add(colony(tag));
        }
        return result;
    }
    static SupplySnapshot decode(Map<String,List<CompoundTag>> known,Map<String,List<CompoundTag>> retained,Set<UUID> colonies,Set<UUID> unknownColonies,Set<UUID> works,Set<UUID> unknownWorks,io.github.kpuctajluk.colonyloom.core.storage.StorageSnapshot storage,Set<UUID> blocked,Set<UUID> identities) {
        Map<String,RecipeDefinition> pins=new LinkedHashMap<>();Set<String> unknownPins=new HashSet<>();
        for(var tag:retained.get("pinnedDefinitions")) if(tag.contains("digest",Tag.TAG_STRING)) unknownPins.add(text(tag,"digest"));
        for(var tag:known.get("pinnedDefinitions")) if(text(tag,"typeId").equals(PIN)) {var recipe=recipe(tag);if(unknownPins.contains(recipe.digest())||pins.putIfAbsent(recipe.digest(),recipe)!=null)throw new IllegalArgumentException("Duplicate recipe pin");}
        Set<UUID> opaque=opaqueColonies(retained),demandIds=new HashSet<>(),orderIds=new HashSet<>(),obligations=new HashSet<>();
        for(var entry:storage.reservations())obligations.add(entry.id());for(var entry:storage.allocations())obligations.add(entry.id());
        Set<UUID> opaqueObligations=new HashSet<>();for(String key:List.of("reservations","allocations"))for(var tag:retained.get(key))if(tag.hasUUID("obligationId"))opaqueObligations.add(RegistryNbt.uuid(tag,"obligationId"));
        for(var tag:known.get("demands"))demandIds.add(id(tag));
        for(String key:List.of("productionOrders","deliveries"))for(var tag:known.get(key))orderIds.add(id(tag));
        Set<UUID> opaqueDemands=new HashSet<>(),opaqueOrders=new HashSet<>();
        for(var tag:retained.get("demands"))if(tag.hasUUID("id"))opaqueDemands.add(id(tag));
        for(String key:List.of("productionOrders","deliveries"))for(var tag:retained.get(key))if(tag.hasUUID("id"))opaqueOrders.add(id(tag));
        for(String key:List.of("demands","productionOrders","deliveries","evidence"))for(var tag:retained.get(key))if(tag.hasUUID("id")&&!identities.add(id(tag)))throw new IllegalArgumentException("Duplicate opaque supply identity");
        for(String key:List.of("demands","productionOrders","deliveries","evidence"))for(var tag:known.get(key)) {
            if(key.equals("evidence")&&!text(tag,"typeId").equals(SHARE))continue;
            if(!identities.add(id(tag)))throw new IllegalArgumentException("Duplicate supply identity");
            UUID colony=colony(tag);if(!colonies.contains(colony)){if(!unknownColonies.contains(colony))throw new IllegalArgumentException("Supply references missing colony");opaque.add(colony);}
            if(tag.hasUUID("ownerDemandId"))reference(tag,"ownerDemandId",demandIds,opaqueDemands,opaque);
            if(tag.hasUUID("demandId"))reference(tag,"demandId",demandIds,opaqueDemands,opaque);
            if(tag.hasUUID("sourceOrderId"))reference(tag,"sourceOrderId",orderIds,opaqueOrders,opaque);
            if(tag.hasUUID("productionOrderId"))reference(tag,"productionOrderId",orderIds,opaqueOrders,opaque);
            if(tag.hasUUID("workId"))reference(tag,"workId",works,unknownWorks,opaque);
            if(tag.hasUUID("obligationId"))reference(tag,"obligationId",obligations,opaqueObligations,opaque);
            if(tag.contains("recipeDigest")) {String digest=text(tag,"recipeDigest");if(!pins.containsKey(digest)){if(!unknownPins.contains(digest))throw new IllegalArgumentException("Missing production recipe snapshot");opaque.add(colony);}}
        }
        Set<String> opaqueDigests=new HashSet<>();for(var tag:retained.get("productionOrders"))if(tag.contains("recipeDigest"))opaqueDigests.add(text(tag,"recipeDigest"));
        boolean changed;
        do {
            changed=false;
            for(var tag:known.get("productionOrders"))if(opaque.contains(colony(tag)))changed|=opaqueDigests.add(text(tag,"recipeDigest"));
            for(var tag:known.get("productionOrders"))if(opaqueDigests.contains(text(tag,"recipeDigest")))changed|=opaque.add(colony(tag));
        }while(changed);
        var demands=new ArrayList<Demand.Snapshot>();var shares=new ArrayList<CoverageShare>();var productions=new ArrayList<ProductionOrder>();var deliveries=new ArrayList<DeliveryOrder>();Set<String> retainedPins=new HashSet<>();
        for(var tag:retained.get("productionOrders"))if(tag.contains("recipeDigest")){String digest=text(tag,"recipeDigest");if(!pins.containsKey(digest)&&!unknownPins.contains(digest))throw new IllegalArgumentException("Opaque order references missing recipe snapshot");retainedPins.add(digest);}
        for(String key:List.of("demands","productionOrders","deliveries","evidence"))for(var tag:known.get(key)) {
            if(key.equals("evidence")&&!text(tag,"typeId").equals(SHARE))continue;
            if(opaque.contains(colony(tag))) {retained.get(key).add(tag.copy());blocked.add(colony(tag));if(tag.contains("recipeDigest"))retainedPins.add(text(tag,"recipeDigest"));continue;}
            switch(key){case "demands" -> demands.add(demand(tag));case "productionOrders" -> productions.add(production(tag,pins));case "deliveries" -> deliveries.add(delivery(tag));case "evidence" -> shares.add(share(tag));default -> throw new IllegalStateException();}
        }
        for(var tag:known.get("pinnedDefinitions"))if(text(tag,"typeId").equals(PIN)&&retainedPins.contains(text(tag,"digest")))retained.get("pinnedDefinitions").add(tag.copy());
        Set<String> usedPins=new HashSet<>(retainedPins);for(var order:productions)usedPins.add(order.recipe().digest());
        for(var tag:known.get("pinnedDefinitions"))if(text(tag,"typeId").equals(PIN)&&!usedPins.contains(text(tag,"digest")))retained.get("pinnedDefinitions").add(tag.copy());
        return new SupplySnapshot(demands,shares,productions,deliveries);
    }
    private static void reference(CompoundTag tag,String key,Set<UUID> known,Set<UUID> unknown,Set<UUID> opaque) {
        UUID reference=RegistryNbt.uuid(tag,key);if(known.contains(reference))return;if(!unknown.contains(reference))throw new IllegalArgumentException("Supply references missing "+key);opaque.add(colony(tag));
    }
    static boolean supported(CompoundTag tag) {
        String type=text(tag,"typeId");
        int schema=RegistryNbt.integer(tag,"schemaVersion"); if (type.equals(PRODUCTION) ? schema!=1 && schema!=2 : schema!=1) return false;
        return switch(type) {
            case DEMAND -> member(Demand.Status.values(),text(tag,"status")) && member(Demand.GoalKind.values(),text(tag,"goalKind")) && member(Lane.values(),text(tag,"lane"));
            case SHARE -> member(CoverageShare.Stage.values(),text(tag,"stage"));
            case PRODUCTION -> member(ProductionOrder.State.values(),text(tag,"state")) && member(Lane.values(),text(tag,"lane"));
            case DELIVERY -> member(DeliveryOrder.State.values(),text(tag,"state")) && member(Lane.values(),text(tag,"lane"));
            case PIN -> true;
            default -> false;
        };
    }
    private static boolean member(Enum<?>[] values,String name) {for(var value:values) if(value.name().equals(name))return true;return false;}
    static CompoundTag demand(Demand.Snapshot value) {
        var tag=typed(DEMAND,value.id(),value.colonyId());tag.putUUID("ownerId",value.ownerId());tag.put("matcher",matcher(value.matcher()));tag.putString("goalKind",value.goalKind().name());tag.put("destination",StorageNbt.position(value.destination()));
        tag.putLong("required",value.required());tag.putLong("fulfilled",value.fulfilled());tag.putLong("allocated",value.allocated());tag.putLong("covered",value.covered());tag.putLong("deliveredTotal",value.deliveredTotal());tag.putLong("revision",value.revision());tag.putString("lane",value.lane().name());tag.putInt("priority",value.priority());tag.putLong("createdTick",value.createdTick());tag.putString("status",value.status().name());
        var sources=new ListTag();for(var source:value.sourceStorages())sources.add(StorageNbt.storage(source));tag.put("sourceStorages",sources);return tag;
    }
    static Demand.Snapshot demand(CompoundTag tag) {
        var sources=new ArrayList<io.github.kpuctajluk.colonyloom.core.storage.StorageId>();
        if(tag.contains("sourceStorages")) {
            if(!tag.contains("sourceStorages",Tag.TAG_LIST))throw new IllegalArgumentException("Invalid delivery sources");
            var list=(ListTag)tag.get("sourceStorages");if(list.size()>2||!list.isEmpty()&&list.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("Invalid delivery source envelope");
            for(int index=0;index<list.size();index++)sources.add(StorageNbt.storage(list.getCompound(index)));
        }
        return new Demand.Snapshot(id(tag),colony(tag),RegistryNbt.uuid(tag,"ownerId"),matcher(compound(tag,"matcher")),Demand.GoalKind.valueOf(text(tag,"goalKind")),StorageNbt.position(compound(tag,"destination")),number(tag,"required"),number(tag,"fulfilled"),number(tag,"allocated"),number(tag,"covered"),number(tag,"deliveredTotal"),number(tag,"revision"),Lane.valueOf(text(tag,"lane")),RegistryNbt.integer(tag,"priority"),number(tag,"createdTick"),Demand.Status.valueOf(text(tag,"status")),sources);
    }
    static CompoundTag share(CoverageShare value) {
        var tag=typed(SHARE,value.id(),value.colonyId());tag.putUUID("demandId",value.demandId());optional(tag,"sourceOrderId",value.sourceOrderId());optional(tag,"productionOrderId",value.productionOrderId());optional(tag,"obligationId",value.obligationId());if(value.slot()!=null)tag.put("slot",StorageNbt.slot(value.slot()));tag.put("item",StorageNbt.item(value.item()));tag.putLong("quantity",value.quantity());tag.putLong("revision",value.revision());tag.putString("stage",value.stage().name());return tag;
    }
    static CoverageShare share(CompoundTag tag) {
        return new CoverageShare(id(tag),colony(tag),RegistryNbt.uuid(tag,"demandId"),optional(tag,"sourceOrderId"),optional(tag,"productionOrderId"),optional(tag,"obligationId"),tag.contains("slot")?StorageNbt.slot(compound(tag,"slot")):null,StorageNbt.item(compound(tag,"item")),number(tag,"quantity"),number(tag,"revision"),CoverageShare.Stage.valueOf(text(tag,"stage")));
    }
    static CompoundTag production(ProductionOrder value) {
        var tag=typed(PRODUCTION,value.id(),value.colonyId());tag.putInt("schemaVersion",2);tag.putUUID("ownerDemandId",value.ownerDemandId());tag.putString("recipeDigest",value.recipe().digest());tag.putLong("batches",value.batches());tag.putLong("remainingActiveTicks",value.remainingActiveTicks());tag.putLong("completedBatches",value.completedBatches());tag.putBoolean("batchStarted",value.batchStarted());tag.putBoolean("pinned",value.pinned());if(value.pinned()){tag.putUUID("workshopId",value.workshopId());tag.put("equipmentPosition",StorageNbt.position(value.equipmentPosition()));tag.put("workshopStorage",StorageNbt.storage(value.workshopStorage()));}order(tag,value.revision(),value.workId(),value.citizenId(),value.state().name(),value.lane(),value.priority());return tag;
    }
    static ProductionOrder production(CompoundTag tag,Map<String,RecipeDefinition> pins) {
        var recipe=pins.get(text(tag,"recipeDigest"));if(recipe==null)throw new IllegalArgumentException("Missing pinned recipe");
        if(RegistryNbt.integer(tag,"schemaVersion")==1) {
            long batches=number(tag,"batches");
            if(number(tag,"remainingActiveTicks")!=Math.multiplyExact(batches,recipe.activeTicks()) || optional(tag,"workId")!=null || optional(tag,"citizenId")!=null) throw new IllegalArgumentException("Legacy production contains unsupported physical progress");
            var state=ProductionOrder.State.valueOf(text(tag,"state"));
            return new ProductionOrder(id(tag),colony(tag),RegistryNbt.uuid(tag,"ownerDemandId"),recipe,batches,recipe.activeTicks(),number(tag,"revision"),null,null,state,Lane.valueOf(text(tag,"lane")),RegistryNbt.integer(tag,"priority"),null,null,null,0,false);
        }
        if (!tag.contains("batchStarted",Tag.TAG_BYTE) || !tag.contains("pinned",Tag.TAG_BYTE)) throw new IllegalArgumentException("Missing production batch phase/pin");
        boolean pinned=tag.getBoolean("pinned");
        return new ProductionOrder(id(tag),colony(tag),RegistryNbt.uuid(tag,"ownerDemandId"),recipe,number(tag,"batches"),number(tag,"remainingActiveTicks"),number(tag,"revision"),optional(tag,"workId"),optional(tag,"citizenId"),ProductionOrder.State.valueOf(text(tag,"state")),Lane.valueOf(text(tag,"lane")),RegistryNbt.integer(tag,"priority"),pinned?RegistryNbt.uuid(tag,"workshopId"):null,pinned?StorageNbt.position(compound(tag,"equipmentPosition")):null,pinned?StorageNbt.storage(compound(tag,"workshopStorage")):null,number(tag,"completedBatches"),tag.getBoolean("batchStarted"));
    }
    static CompoundTag delivery(DeliveryOrder value) {
        var tag=typed(DELIVERY,value.id(),value.colonyId());tag.putUUID("ownerDemandId",value.ownerDemandId());tag.put("source",StorageNbt.slot(value.source()));tag.put("destination",StorageNbt.position(value.destination()));tag.put("item",StorageNbt.item(value.item()));tag.putLong("quantity",value.quantity());tag.putLong("transferred",value.transferred());order(tag,value.revision(),value.workId(),value.citizenId(),value.state().name(),value.lane(),value.priority());return tag;
    }
    static DeliveryOrder delivery(CompoundTag tag) {
        return new DeliveryOrder(id(tag),colony(tag),RegistryNbt.uuid(tag,"ownerDemandId"),StorageNbt.slot(compound(tag,"source")),StorageNbt.position(compound(tag,"destination")),StorageNbt.item(compound(tag,"item")),number(tag,"quantity"),number(tag,"transferred"),number(tag,"revision"),optional(tag,"citizenId"),optional(tag,"workId"),DeliveryOrder.State.valueOf(text(tag,"state")),Lane.valueOf(text(tag,"lane")),RegistryNbt.integer(tag,"priority"));
    }
    static CompoundTag recipe(RecipeDefinition value) {
        var tag=new CompoundTag();tag.putString("typeId",PIN);tag.putInt("schemaVersion",1);tag.putString("id",value.id());tag.putInt("version",value.version());tag.putString("profession",value.professionId());tag.putString("equipment",value.equipmentId());tag.putString("digest",value.digest());tag.put("output",StorageNbt.item(value.output()));tag.putLong("outputCount",value.outputCount());tag.putLong("activeTicks",value.activeTicks());var inputs=new ListTag();for(var input:value.ingredients()){var entry=new CompoundTag();entry.put("matcher",matcher(input.matcher()));entry.putLong("count",input.count());inputs.add(entry);}tag.put("ingredients",inputs);return tag;
    }
    static RecipeDefinition recipe(CompoundTag tag) {
        ListTag inputs=RegistryNbt.list(tag,"ingredients");if(inputs.isEmpty()||inputs.size()>8)throw new IllegalArgumentException("Pinned recipe ingredient envelope");var ingredients=new ArrayList<RecipeDefinition.Ingredient>(inputs.size());for(Tag raw:inputs){var entry=(CompoundTag)raw;ingredients.add(new RecipeDefinition.Ingredient(matcher(compound(entry,"matcher")),number(entry,"count")));}
        return new RecipeDefinition(text(tag,"id"),RegistryNbt.integer(tag,"version"),text(tag,"profession"),text(tag,"equipment"),ingredients,StorageNbt.item(compound(tag,"output")),number(tag,"outputCount"),number(tag,"activeTicks"),text(tag,"digest"));
    }
    private static CompoundTag matcher(ItemMatcher value) {var tag=new CompoundTag();tag.putString("itemId",value.itemId());if(value.exact()!=null)tag.put("exact",StorageNbt.item(value.exact()));return tag;}
    private static ItemMatcher matcher(CompoundTag tag) {return new ItemMatcher(text(tag,"itemId"),tag.contains("exact")?StorageNbt.item(compound(tag,"exact")):null);}
    private static void order(CompoundTag tag,long revision,UUID work,UUID citizen,String state,Lane lane,int priority) {tag.putLong("revision",revision);optional(tag,"workId",work);optional(tag,"citizenId",citizen);tag.putString("state",state);tag.putString("lane",lane.name());tag.putInt("priority",priority);}
    private static CompoundTag typed(String type,UUID id,UUID colony) {var tag=new CompoundTag();tag.putString("typeId",type);tag.putInt("schemaVersion",1);tag.putUUID("id",id);tag.putUUID("colonyId",colony);return tag;}
    private static UUID id(CompoundTag tag) {return RegistryNbt.uuid(tag,"id");}
    private static UUID colony(CompoundTag tag) {return RegistryNbt.uuid(tag,"colonyId");}
    private static UUID optional(CompoundTag tag,String key) {return tag.contains(key)?RegistryNbt.uuid(tag,key):null;}
    private static void optional(CompoundTag tag,String key,UUID value) {if(value!=null)tag.putUUID(key,value);}
    private static CompoundTag compound(CompoundTag tag,String key) {return RegistryNbt.compound(tag,key);}
    private static String text(CompoundTag tag,String key) {String value=RegistryNbt.string(tag,key);if(value.isEmpty()||value.length()>256)throw new IllegalArgumentException("Invalid supply "+key);return value;}
    private static long number(CompoundTag tag,String key) {return RegistryNbt.number(tag,key);}
}
