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
        final BitSet missingTargets=new BitSet(PORTION_BLOCKS);
        final Inspection carried=new Inspection(),pickup=new Inspection();
        WorkOrder.Reason carriedReason=WorkOrder.Reason.NONE;
        final Map<String,Integer> missing=new LinkedHashMap<>();
        final Map<String,Demand> demands=new LinkedHashMap<>();
    }
    private static final class Inspection {
        UUID registration,demand,source;
        long registrationRevision=-1,sourceRevision=-1;
        int cursor,capacity=-1;
        boolean complete;
        void reset() { registration=null;demand=null;source=null;cursor=0;capacity=-1;complete=false; }
        // Goal coverage/status churn is not a topology change. Source revisions and native reads still validate property.
        void bind(StorageRegistry.Registration inventory,Demand goal,CoverageShare share) {
            if(!inventory.id().equals(registration)||inventory.revision()!=registrationRevision
                    ||!goal.id().equals(demand)
                    ||share!=null&&(!share.id().equals(source)||share.revision()!=sourceRevision))reset();
            registration=inventory.id();registrationRevision=inventory.revision();
            demand=goal.id();
            if(share!=null) {source=share.id();sourceRevision=share.revision();}
        }
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
            portion.start=start;portion.end=Math.min(layout.targets().size(),start+PORTION_BLOCKS);portion.cursor=start;
            portion.siteRevision=site.revision();portion.complete=false;portion.missing.clear();portion.demands.clear();
            portion.missingTargets.clear();portion.carried.reset();portion.pickup.reset();
        }
        if(portion.complete)return true;
        while(portion.cursor<portion.end) {
            if(!registry.budgets().tryConsume(Budget.BLUEPRINT_COMPARISONS,work.lane()))return false;
            int index=portion.cursor++;var target=layout.targets().get(index);
            portion.missing.putIfAbsent(target.expected().itemId(),0);
            if(index>=site.cursor()&&!placement.matches(target.position(),target.expected())) {
                portion.missingTargets.set(index-start);portion.missing.merge(target.expected().itemId(),1,Math::addExact);
            }
        }
        for(var entry:portion.missing.entrySet()) {
            UUID id=UUID.nameUUIDFromBytes((work.id()+":materials:"+start+":"+entry.getKey()).getBytes(StandardCharsets.UTF_8));
            Demand demand=registry.supply().demands().stream().filter(d -> d.id().equals(id)).findFirst().orElse(null);
            if(demand==null)demand=registry.supply().request(id,work.colonyId(),work.id(),new ItemMatcher(entry.getKey(),material(entry.getKey())),entry.getValue(),Demand.GoalKind.CONSUMPTION,layout.deliveryBuffer(),work.lane(),work.priority(),Math.max(0,registry.budgets().tick()));
            else {
                long required=Math.addExact(demand.snapshot().fulfilled(),entry.getValue());
                if(required!=demand.snapshot().required())registry.supply().updateRequired(id,required);
            }
            portion.demands.put(entry.getKey(),demand);
        }
        portion.complete=true;return true;
    }
    void requireMaterial(Portion portion,int index,String itemId) {
        int offset=index-portion.start;
        if(portion.missingTargets.get(offset))return;
        portion.missingTargets.set(offset);portion.missing.merge(itemId,1,Math::addExact);
        updateRemaining(portion,itemId);
    }
    // Only this service's verified advance retains future observations; revisit/unexpected revisions reanalyze.
    void confirmedAdvance(Portion portion,ConstructionSnapshot before,String itemId) {
        if(!portion.complete||portion.siteRevision!=before.revision()||before.cursor()<portion.start||before.cursor()>=portion.end)return;
        int offset=before.cursor()-portion.start;
        if(portion.missingTargets.get(offset)) {
            portion.missingTargets.clear(offset);portion.missing.compute(itemId,(id,count) -> Math.subtractExact(count,1));
            updateRemaining(portion,itemId);
        }
        portion.siteRevision=Math.incrementExact(before.revision());
    }
    private void updateRemaining(Portion portion,String itemId) {
        var demand=portion.demands.get(itemId);
        long required=Math.addExact(demand.snapshot().fulfilled(),portion.missing.get(itemId));
        if(required!=demand.snapshot().required())registry.supply().updateRequired(demand.id(),required);
    }
    void retryCarried(Portion portion) { portion.carried.reset(); }
    // Assignment pauses do not change inventory topology. bind() checks identity/epoch via the
    // registration and source revision before resuming; pickup still pays its fresh native read.
    void suspend(Portion portion) {portion.carriedReason=WorkOrder.Reason.NONE;}
    private ItemDescriptor material(String id) {
        return materials.computeIfAbsent(id,key -> NativeItemDescriptor.describe(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(key))),server.registryAccess()));
    }
    CoverageShare carried(WorkOrder work,Portion portion,String itemId) {
        portion.carriedReason=WorkOrder.Reason.MATERIALS;
        var citizen=registry.citizen(work.assignee());var demand=portion.demands.get(itemId);
        if(demand==null)return null;
        var inventory=registry.storage().registrations(work.colonyId()).stream().filter(r -> r.storages().stream().anyMatch(s -> s.identity().equals(citizen.citizenId())&&s.bindingEpoch()==citizen.bindingEpoch())).findFirst().orElse(null);
        if(inventory==null)inventory=storage.registerCitizen(work.colonyId(),citizen.citizenId(),"construction");
        var scan=portion.carried;scan.bind(inventory,demand,null);
        var slots=inventory.slots();
        var descriptor=material(itemId);
        // Canonical allocation chooses the slot; even a retained share needs a paid fresh native read.
        var allocated=registry.supply().demandShares(demand.id()).stream().filter(s -> s.stage()==CoverageShare.Stage.ALLOCATED&&slots.contains(s.slot())).findFirst().orElse(null);
        if(allocated!=null) {scan.cursor=slots.indexOf(allocated.slot());scan.complete=false;}
        while(!scan.complete&&scan.cursor<inventory.slots().size()) {
            if(!registry.budgets().tryConsume(Budget.STORAGE_SLOT_CHECKS,work.lane())) {portion.carriedReason=WorkOrder.Reason.BUDGET;return null;}
            var slot=inventory.slots().get(scan.cursor++);var observed=storage.readFresh(slot);
            if(observed.ready())registry.storage().index().observe(slot,observed.item(),observed.count(),registry.budgets().tick());
            else registry.storage().index().unknown(slot);
            long free=registry.storage().index().free(slot,registry.budgets().tick());
            if(demand.deficit()>0&&descriptor.equals(observed.item())&&free>0)registry.supply().allocateStock(demand.id(),slot,descriptor,Math.min(free,demand.deficit()),registry.budgets().tick());
            var share=registry.supply().demandShares(demand.id()).stream().filter(s -> s.stage()==CoverageShare.Stage.ALLOCATED&&slot.equals(s.slot())).findFirst().orElse(null);
            if(observed.ready()&&descriptor.equals(observed.item())&&share!=null&&observed.count()>=share.quantity()) {portion.carriedReason=WorkOrder.Reason.NONE;return share;}
        }
        scan.complete=true;
        return null;
    }
    CoverageShare buffered(WorkOrder work,Portion portion,String itemId) {
        var demand=portion.demands.get(itemId);if(demand==null)return null;
        return registry.supply().demandShares(demand.id()).stream().filter(s -> s.stage()==CoverageShare.Stage.ALLOCATED&&s.slot().storage().bindingEpoch()==0).findFirst().orElse(null);
    }
    WorkOrder.Reason pickup(WorkOrder work,Portion portion,CoverageShare source) {
        var citizen=registry.citizen(work.assignee());
        var inventory=registry.storage().registrations(work.colonyId()).stream().filter(r -> r.storages().stream().anyMatch(s -> s.identity().equals(citizen.citizenId())&&s.bindingEpoch()==citizen.bindingEpoch())).findFirst().orElse(null);
        if(inventory==null)inventory=storage.registerCitizen(work.colonyId(),citizen.citizenId(),"construction");
        var scan=portion.pickup;scan.bind(inventory,registry.supply().demand(source.demandId()),source);
        while(scan.cursor<inventory.slots().size()) {
            var slot=inventory.slots().get(scan.cursor);
            if(scan.capacity<0) {
                if(!registry.budgets().tryConsume(Budget.STORAGE_SLOT_CHECKS,work.lane()))return WorkOrder.Reason.BUDGET;
                int capacity=storage.capacity(slot,source.item());
                if(capacity<1) {scan.cursor++;continue;}
                scan.capacity=capacity;
            }
            if(!registry.budgets().tryConsume(Budget.STORAGE_SLOT_CHECKS,work.lane()))return WorkOrder.Reason.BUDGET;
            // Retained capacity is only an upper bound: the paid fresh read and native transfer recheck reality.
            var observed=storage.readFresh(slot);
            if(!observed.ready()) {registry.storage().index().unknown(slot);scan.cursor++;scan.capacity=-1;continue;}
            registry.storage().index().observe(slot,observed.item(),observed.count(),registry.budgets().tick());
            if(observed.count()>0&&!source.item().equals(observed.item())) {scan.cursor++;scan.capacity=-1;continue;}
            var location=storage.locate(source.slot().storage());if(location==null)return WorkOrder.Reason.RECONCILING;
            try(var prepared=registry.supply().prepareAllocationMove(source.id(),slot,(int)Math.min(source.quantity(),scan.capacity),registry.budgets().tick())) {
                var colony=registry.colony(work.colonyId());
                var context=new ActionContext(work.colonyId(),citizen.citizenId(),ActionContext.Kind.STORAGE_TRANSFER,location,ActionContext.AuthorityMode.COLONY,null,colony.authorityRevision());
                var result=transfer.transfer(context,citizen.bindingEpoch(),prepared.source(),prepared.destination(),prepared.item(),prepared.maximum(),prepared::commit);
                scan.reset();portion.carried.reset();
                return result.moved()>0&&!result.ambiguous()?WorkOrder.Reason.NONE:result.reason()==WorkOrder.Reason.NONE?WorkOrder.Reason.RECONCILING:result.reason();
            }
        }
        scan.reset();portion.carried.reset();
        return WorkOrder.Reason.CAPACITY;
    }
    void close(UUID workId) {
        for(var demand:registry.supply().demands())if(demand.snapshot().ownerId().equals(workId)&&demand.snapshot().status()!=Demand.Status.CANCELLED)registry.supply().cancel(demand.id());
    }
}
