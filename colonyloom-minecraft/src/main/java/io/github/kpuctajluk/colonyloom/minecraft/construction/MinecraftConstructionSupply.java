package io.github.kpuctajluk.colonyloom.minecraft.construction;

import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.construction.ConstructionSnapshot;
import io.github.kpuctajluk.colonyloom.core.storage.*;
import io.github.kpuctajluk.colonyloom.core.supply.*;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.gameplay.construction.ConstructionController;
import io.github.kpuctajluk.colonyloom.minecraft.storage.NativeItemDescriptor;
import io.github.kpuctajluk.colonyloom.minecraft.storage.StorageService;
import io.github.kpuctajluk.colonyloom.minecraft.storage.StorageTransferExecutor;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/** A portion owns material goals; inventories, not these counters, own actual material. */
final class MinecraftConstructionSupply {
    static final int PORTION_BLOCKS=64;
    static final class Portion {
        int start=-1,end,cursor;
        long siteRevision=-1;
        boolean complete;
        final Map<String,Integer> missing=new LinkedHashMap<>();
        final Map<String,Demand> demands=new LinkedHashMap<>();
    }
    private final MinecraftServer server;
    private final ColonyRegistry registry;
    private final StorageService storage;
    private final StorageTransferExecutor transfer;
    private final BlockPlacementExecutor placement;
    private final Map<String,ItemDescriptor> materials=new HashMap<>();
    MinecraftConstructionSupply(MinecraftServer server,ColonyRegistry registry,StorageService storage,StorageTransferExecutor transfer,BlockPlacementExecutor placement) {
        this.server=server;this.registry=registry;this.storage=storage;this.transfer=transfer;this.placement=placement;
    }
    boolean analyze(WorkOrder work,ConstructionSnapshot site,ConstructionController.Layout layout,Portion portion) {
        int start=site.cursor()/PORTION_BLOCKS*PORTION_BLOCKS;
        if(portion.start!=start||portion.siteRevision!=site.revision()) {
            portion.start=start;portion.end=Math.min(layout.targets().length,start+PORTION_BLOCKS);portion.cursor=start;
            portion.siteRevision=site.revision();portion.complete=false;portion.missing.clear();portion.demands.clear();
        }
        if(portion.complete)return true;
        while(portion.cursor<portion.end) {
            if(!registry.budgets().tryConsume(Budget.BLUEPRINT_COMPARISONS,work.lane()))return false;
            int index=portion.cursor++;var target=layout.targets()[index];
            portion.missing.putIfAbsent(target.expected().itemId(),0);
            if(index>=site.cursor()&&!placement.matches(target.position(),target.expected()))portion.missing.merge(target.expected().itemId(),1,Math::addExact);
        }
        for(var entry:portion.missing.entrySet()) {
            UUID id=UUID.nameUUIDFromBytes((work.id()+":materials:"+start+":"+entry.getKey()).getBytes(StandardCharsets.UTF_8));
            Demand demand=registry.supply().demands().stream().filter(d -> d.id().equals(id)).findFirst().orElse(null);
            if(demand==null)demand=registry.supply().request(id,work.colonyId(),work.id(),new ItemMatcher(entry.getKey(),material(entry.getKey())),entry.getValue(),Demand.GoalKind.CONSUMPTION,layout.deliveryBuffer(),work.lane(),work.priority(),Math.max(0,registry.budgets().tick()));
            else registry.supply().updateRequired(id,Math.addExact(demand.snapshot().fulfilled(),entry.getValue()));
            portion.demands.put(entry.getKey(),demand);
        }
        portion.complete=true;return true;
    }
    private ItemDescriptor material(String id) {
        return materials.computeIfAbsent(id,key -> NativeItemDescriptor.describe(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(key))),server.registryAccess()));
    }
    CoverageShare carried(WorkOrder work,Portion portion,String itemId) {
        var citizen=registry.citizen(work.assignee());var demand=portion.demands.get(itemId);if(demand==null)return null;
        var inventory=registry.storage().registrations(work.colonyId()).stream().filter(r -> r.storages().stream().anyMatch(s -> s.identity().equals(citizen.citizenId())&&s.bindingEpoch()==citizen.bindingEpoch())).findFirst().orElse(null);
        if(inventory==null)inventory=storage.registerCitizen(work.colonyId(),citizen.citizenId(),"construction");
        var descriptor=material(itemId);
        for(var slot:inventory.slots()) {
            if(!registry.budgets().tryConsume(Budget.STORAGE_SLOT_CHECKS,work.lane()))return null;
            var observed=storage.readFresh(slot);
            if(observed.ready())registry.storage().index().observe(slot,observed.item(),observed.count(),registry.budgets().tick());
            else registry.storage().index().unknown(slot);
            long free=registry.storage().index().free(slot,registry.budgets().tick());
            if(demand.deficit()>0&&descriptor.equals(observed.item())&&free>0)registry.supply().allocateStock(demand.id(),slot,descriptor,Math.min(free,demand.deficit()),registry.budgets().tick());
            var share=registry.supply().demandShares(demand.id()).stream().filter(s -> s.stage()==CoverageShare.Stage.ALLOCATED&&slot.equals(s.slot())).findFirst().orElse(null);
            if(share!=null)return share;
        }
        return null;
    }
    CoverageShare buffered(WorkOrder work,Portion portion,String itemId) {
        var demand=portion.demands.get(itemId);if(demand==null)return null;
        return registry.supply().demandShares(demand.id()).stream().filter(s -> s.stage()==CoverageShare.Stage.ALLOCATED&&s.slot().storage().bindingEpoch()==0).findFirst().orElse(null);
    }
    WorkOrder.Reason pickup(WorkOrder work,CoverageShare source) {
        var citizen=registry.citizen(work.assignee());
        var inventory=registry.storage().registrations(work.colonyId()).stream().filter(r -> r.storages().stream().anyMatch(s -> s.identity().equals(citizen.citizenId())&&s.bindingEpoch()==citizen.bindingEpoch())).findFirst().orElse(null);
        if(inventory==null)inventory=storage.registerCitizen(work.colonyId(),citizen.citizenId(),"construction");
        for(var slot:inventory.slots()) {
            if(!registry.budgets().tryConsume(Budget.STORAGE_SLOT_CHECKS,work.lane()))return WorkOrder.Reason.BUDGET;
            int capacity=storage.capacity(slot,source.item());if(capacity<1)continue;
            var observed=storage.readFresh(slot);if(!observed.ready())continue;
            registry.storage().index().observe(slot,observed.item(),observed.count(),registry.budgets().tick());
            var location=storage.locate(source.slot().storage());if(location==null)return WorkOrder.Reason.RECONCILING;
            try(var prepared=registry.supply().prepareAllocationMove(source.id(),slot,(int)Math.min(source.quantity(),capacity),registry.budgets().tick())) {
                var colony=registry.colony(work.colonyId());
                var context=new ActionContext(work.colonyId(),citizen.citizenId(),ActionContext.Kind.STORAGE_TRANSFER,location,ActionContext.AuthorityMode.COLONY,null,colony.authorityRevision());
                var result=transfer.transfer(context,citizen.bindingEpoch(),prepared.source(),prepared.destination(),prepared.item(),prepared.maximum(),prepared::commit);
                return result.moved()>0&&!result.ambiguous()?WorkOrder.Reason.NONE:result.reason()==WorkOrder.Reason.NONE?WorkOrder.Reason.RECONCILING:result.reason();
            }
        }
        return WorkOrder.Reason.CAPACITY;
    }
    WorldPosition buffer(WorkOrder work,ConstructionController.Layout layout) {
        var buffer=registry.storage().registrations(work.colonyId()).stream().filter(r -> r.role().equals("construction")&&r.address().equals(layout.deliveryBuffer())&&r.storages().stream().allMatch(id -> id.bindingEpoch()==0)).findFirst().orElse(null);
        return buffer==null?null:buffer.address();
    }
    void close(UUID workId) {
        for(var demand:registry.supply().demands())if(demand.snapshot().ownerId().equals(workId)&&demand.snapshot().status()!=Demand.Status.CANCELLED)registry.supply().cancel(demand.id());
    }
}
