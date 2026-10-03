package io.github.kpuctajluk.colonyloom.minecraft.storage;

import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.logistics.DeliveryOrder;
import io.github.kpuctajluk.colonyloom.core.navigation.NavigationService;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.scheduler.SimulationScheduler;
import io.github.kpuctajluk.colonyloom.core.storage.StockRegion;
import io.github.kpuctajluk.colonyloom.core.storage.StorageRegistry;
import io.github.kpuctajluk.colonyloom.core.supply.CoverageShare;
import io.github.kpuctajluk.colonyloom.core.supply.SupplyRegistry;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/** Bounded native logistics. Surplus cycles unavailable receivers without releasing its physical cargo/share. */
public final class MinecraftDeliveryService implements SimulationScheduler.PhysicalExecutor,NavigationService.GoalAuthority,AutoCloseable {
    private static final class Active {
        final UUID loadOwner=UUID.randomUUID();
        WorldPosition waypoint;
        long generation;
        final Map<UUID,Integer> slotCursors=new HashMap<>();
        final Set<UUID> unknownScans=new HashSet<>();
        final LinkedHashSet<ChunkKey> requiredChunks=new LinkedHashSet<>();
        boolean scanFinished;
        boolean returnToSource;
        int surplusBufferCursor;
        WorldPosition surplusPreferredDestination;
        WorkOrder.Reason surplusFailure=WorkOrder.Reason.CAPACITY;
    }
    private final MinecraftServer server;
    private final ColonyRegistry registry;
    private final StorageService storage;
    private final StorageTransferExecutor transfer;
    private final ChunkDemandManager chunks;
    private final Map<UUID,Active> active=new HashMap<>();
    private NavigationService navigation;
    private int orderCursor;
    private int criticalOrderCursor;
    private final List<net.minecraft.world.entity.Entity> waypointOccupants=new ArrayList<>(1);
    private net.minecraft.world.entity.Entity waypointCitizen;
    private final java.util.function.Predicate<net.minecraft.world.entity.Entity> waypointOccupied=entity -> entity!=waypointCitizen && entity.isPushable() && !entity.isSpectator();
    public MinecraftDeliveryService(MinecraftServer server,ColonyRegistry registry,StorageService storage,StorageTransferExecutor transfer,ChunkDemandManager chunks) {
        this.server=server;this.registry=registry;this.storage=storage;this.transfer=transfer;this.chunks=chunks;
    }
    public void navigation(NavigationService navigation) {this.navigation=Objects.requireNonNull(navigation);}
    public boolean current(WorkOrder work,NavigationService.Request request) {
        var state=active.get(work.id());return state!=null&&state.generation==request.goalRevision()&&request.target().equals(state.waypoint);
    }
    public void tick(long tick) {
        registry.requireOwner();
        var detached=registry.supply().unroutedReservation();
        if(detached!=null&&registry.colony(detached.colonyId()).available()&&registry.budgets().tryConsume(Budget.DIRTY_RESCAN_OBJECTS,registry.supply().demand(detached.demandId()).snapshot().lane())) {
            try {if(!registry.supply().allocateLocalReservation(detached.id()))registry.supply().routeReservedStock(detached.id());}
            catch(AdmissionLedger.AdmissionException full) {criticalFailure(detached.demandId(),full);}
        }
        int critical=registry.supply().criticalDeliveryCount();
        if(critical>0) {if(criticalOrderCursor>=critical)criticalOrderCursor=0;tickOrder(registry.supply().criticalDeliveryAt(criticalOrderCursor++));}
        int count=registry.supply().deliveryCount();if(count==0)return;
        if(orderCursor>=count)orderCursor=0;
        var order=registry.supply().deliveryAt(orderCursor++);
        if(order.lane()!=AdmissionLedger.Lane.CRITICAL)tickOrder(order);
    }
    private void tickOrder(DeliveryOrder order) {
        var assignedWork=order.workId()==null?null:registry.workBoard().work(order.workId());
        var lane=assignedWork==null?order.lane():assignedWork.lane();
        if(!registry.colony(order.colonyId()).available()||!registry.budgets().tryConsume(Budget.DIRTY_RESCAN_OBJECTS,lane))return;
        if(order.terminal()) {
            if(order.workId()!=null) {var work=registry.workBoard().work(order.workId());if(!work.terminal()) {if(order.state()==DeliveryOrder.State.LOST)fail(work,WorkOrder.Reason.CARGO_LOST);else finish(work);}}
            return;
        }
        if(order.citizenId()!=null&&registry.citizen(order.citizenId()).lifecycle()==CitizenRecord.Lifecycle.DEAD) {
            registry.supply().markDeliveryLost(order.id());if(order.workId()!=null)fail(registry.workBoard().work(order.workId()),WorkOrder.Reason.CARGO_LOST);return;
        }
        if(order.citizenId()!=null&&order.workId()!=null&&registry.supply().hasCargo(order.id())) {
            var citizen=registry.citizen(order.citizenId());
            if(citizen.readiness()!=CitizenRecord.Readiness.READY||citizen.admission()!=CitizenRecord.Admission.ACTIVE) {
                var state=active.computeIfAbsent(order.workId(),ignored -> new Active());var position=citizen.lastKnownPosition();
                var key=new ChunkKey(position.dimension(),position.x()>>4,position.z()>>4);
                try {chunks.request(state.loadOwner,order.colonyId(),List.of(key),ChunkDemandManager.Readiness.ENTITY_TICKING,lane,assignedWork==null?order.priority():assignedWork.priority(),true);}
                catch(AdmissionLedger.AdmissionException full) {return;}
                return;
            }
        }
        if(order.workId()==null) {
            var source=storage.locate(order.source().storage());if(source==null)return;
            try {
                UUID ownerId=registry.supply().demand(order.ownerDemandId()).snapshot().ownerId();
                var owner=registry.workBoard().works().stream().filter(value -> value.id().equals(ownerId)).findFirst().orElse(null);
                var work=owner!=null&&WorkOrder.FOOD.equals(owner.typeId())
                        ?registry.workBoard().createCitizenDelivery(UUID.randomUUID(),owner.subjectId(),source,order.priority(),order.lane())
                        :registry.workBoard().createDelivery(UUID.randomUUID(),order.colonyId(),source,order.priority(),order.lane());
                registry.supply().assignDelivery(order.id(),null,work.id());
            }
            catch(AdmissionLedger.AdmissionException full) {criticalFailure(order.ownerDemandId(),full);return;}
        }
    }
    public void step(WorkOrder work,long tick) {
        registry.requireOwner();var supply=registry.supply();var order=supply.deliveryForWork(work.id());
        if(order==null) {fail(work,WorkOrder.Reason.CONTENT_UNAVAILABLE);return;}
        if(order.terminal()) {if(order.state()==DeliveryOrder.State.LOST)fail(work,WorkOrder.Reason.CARGO_LOST);else finish(work);return;}
        var citizen=registry.citizen(work.assignee());
        if(supply.hasCargo(order.id())&&!citizen.citizenId().equals(order.citizenId())) {registry.workBoard().releaseAssignment(work.id());return;}
        if(!citizen.citizenId().equals(order.citizenId())) {supply.assignDelivery(order.id(),citizen.citizenId(),work.id());order=supply.delivery(order.id());}
        var state=active.computeIfAbsent(work.id(),ignored -> new Active());
        if(work.criticalService()&&work.subjectId()==null&&supply.hasCargo(order.id())&&!order.returnRequired()) {
            supply.returnDelivery(order.id());order=supply.delivery(order.id());
        }
        var shares=supply.orderShares(order.id());
        CoverageShare cargo=shares.stream().filter(value -> value.stage()==CoverageShare.Stage.IN_TRANSIT).findFirst().orElse(null);
        if(cargo!=null) {deliver(work,order,cargo,state,tick);return;}
        if(order.returnRequired()) {finish(work);return;}
        var source=shares.stream().filter(value -> value.stage()==CoverageShare.Stage.RESERVED_STOCK).findFirst().orElse(null);
        if(source==null) {supply.returnDelivery(order.id());finish(work);return;}
        if(work.subjectId()!=null) {selfPickup(work,order,source,state,tick);return;}
        boolean surplus=supply.productionSurplus(order.ownerDemandId());
        var destination=surplus?surplusDestination(work,order,state):registration(order.colonyId(),order.destination());
        if(surplus&&destination==null)return;
        if(surplus) {supply.routeProductionSurplus(order.ownerDemandId(),destination.address());order=supply.delivery(order.id());}
        var fallback=surplus?destination:returnBuffer(order.colonyId());
        UUID currentOrder=order.id();
        WorldPosition currentDestination=order.destination();
        if(supply.deliveries().stream().anyMatch(other -> !other.id().equals(currentOrder)&&other.destination().equals(currentDestination)&&other.returnRequired()&&supply.hasCargo(other.id()))) {
            waitFor(work,WorkOrder.Reason.CAPACITY,"preflight-destination");return;
        }
        if(destination==null||fallback==null) {waitFor(work,WorkOrder.Reason.CAPACITY,"preflight-destination");return;}
        String stage=work.stage();
        if(!stage.equals("preflight-return")&&!stage.equals("pickup")) {
            if(!ready(work,state,destination.address())) {if(surplus&&unusableReceiver(work))advanceReturn(work,order,state,false);return;}
            var slot=findSlot(work,state,destination,order.item());
            if(slot==null) {if(state.scanFinished) {waitFor(work,WorkOrder.Reason.CAPACITY,"preflight-destination");if(surplus)advanceReturn(work,order,state,false);}return;}
            if(!move(work,state,destination.address(),"preflight-destination")) {if(surplus&&unusableReceiver(work))advanceReturn(work,order,state,false);return;}
            waitFor(work,WorkOrder.Reason.BUDGET,"preflight-return");return;
        }
        if(stage.equals("preflight-return")) {
            if(!ready(work,state,fallback.address())) {if(surplus&&unusableReceiver(work))advanceReturn(work,order,state,false);return;}
            var slot=findSlot(work,state,fallback,order.item());
            if(slot==null) {if(state.scanFinished) {waitFor(work,WorkOrder.Reason.CAPACITY,"preflight-return");if(surplus)advanceReturn(work,order,state,false);}return;}
            if(!move(work,state,fallback.address(),"preflight-return")) {if(surplus&&unusableReceiver(work))advanceReturn(work,order,state,false);return;}
            waitFor(work,WorkOrder.Reason.BUDGET,"pickup");return;
        }
        var location=storage.locate(source.slot().storage());
        if(location==null) {waitFor(work,WorkOrder.Reason.RECONCILING,"pickup");return;}
        if(!ready(work,state,location)||!move(work,state,location,"pickup"))return;
        if(storage.currentCitizen(citizen.citizenId(),citizen.bindingEpoch(),location.dimension())==null) {waitFor(work,WorkOrder.Reason.RECONCILING,"pickup");return;}
        var inventory=registry.storage().registrations(work.colonyId()).stream().filter(value -> value.storages().stream().anyMatch(id ->
                id.identity().equals(citizen.citizenId())&&id.bindingEpoch()==citizen.bindingEpoch())).findFirst().orElse(null);
        if(inventory==null)try {inventory=storage.registerCitizen(work.colonyId(),citizen.citizenId(),"return");}
        catch(AdmissionLedger.AdmissionException full) {waitFor(work,admissionReason(full),"pickup");return;}
        var cargoSlot=findSlot(work,state,inventory,order.item());
        if(cargoSlot==null) {if(state.scanFinished)waitFor(work,WorkOrder.Reason.CAPACITY,"pickup");return;}
        // The earlier empty-courier route proof is not a capacity promise: recheck both real buffers now.
        var destSlot=findSlot(work,state,destination,order.item());if(destSlot==null){if(state.scanFinished) {waitFor(work,WorkOrder.Reason.CAPACITY,"preflight-destination");if(surplus)advanceReturn(work,order,state,false);}return;}
        var safeSlot=findSlot(work,state,fallback,order.item());if(safeSlot==null){if(state.scanFinished) {waitFor(work,WorkOrder.Reason.CAPACITY,"preflight-return");if(surplus)advanceReturn(work,order,state,false);}return;}
        int maximum=(int)Math.min(source.quantity(),Math.min(storage.capacity(cargoSlot,order.item()),storage.capacity(destSlot,order.item())));
        maximum=Math.min(maximum,storage.capacity(safeSlot,order.item()));
        if(maximum<1) {waitFor(work,maximum<0?WorkOrder.Reason.RECONCILING:WorkOrder.Reason.CAPACITY,"preflight-destination");if(surplus&&maximum==0)advanceReturn(work,order,state,false);return;}
        if(!observeSource(work,source,tick))return;
        try(var prepared=supply.preparePickup(source.id(),order.id(),cargoSlot,maximum,tick)) {
            execute(work,prepared,location,citizen.bindingEpoch());
            if(supply.hasCargo(order.id()))waitFor(work,WorkOrder.Reason.BUDGET,"cargo");
        } catch(AdmissionLedger.AdmissionException full) {waitFor(work,admissionReason(full),"pickup");}
    }
    private void deliver(WorkOrder work,DeliveryOrder order,CoverageShare cargo,Active state,long tick) {
        var supply=registry.supply();boolean returning=order.returnRequired();
        boolean surplus=supply.productionSurplus(cargo.demandId());
        var original=storage.locate(order.source().storage());
        var destination=surplus?surplusDestination(work,order,state):returning?(state.returnToSource?(original==null?null:registration(order.colonyId(),original)):returnBuffer(order.colonyId())):registration(order.colonyId(),order.destination());
        if(surplus&&destination==null)return;
        if(surplus) {supply.routeProductionSurplus(cargo.demandId(),destination.address());order=supply.delivery(order.id());}
        if(destination==null) {advanceReturn(work,order,state,returning);return;}
        if(!ready(work,state,destination.address())) {
            if(work.waitingReason()==WorkOrder.Reason.WORKING_SET_LIMIT)advanceReturn(work,order,state,returning);
            return;
        }
        var slot=findSlot(work,state,destination,order.item());
        if(slot==null) {if(state.scanFinished)advanceReturn(work,order,state,returning);return;}
        if(!move(work,state,destination.address(),returning?"returning":"cargo")) {
            if(work.waitingReason()==WorkOrder.Reason.UNREACHABLE||work.waitingReason()==WorkOrder.Reason.PERMISSION_DENIED||work.waitingReason()==WorkOrder.Reason.WORKING_SET_LIMIT)
                advanceReturn(work,order,state,returning);
            return;
        }
        int maximum=(int)Math.min(cargo.quantity(),storage.capacity(slot,order.item()));
        if(maximum<1) {waitFor(work,maximum<0?WorkOrder.Reason.RECONCILING:WorkOrder.Reason.CAPACITY,returning?"returning":"cargo");if(surplus&&maximum==0)advanceReturn(work,order,state,returning);return;}
        if(!observeSource(work,cargo,tick))return;
        try(var prepared=returning?supply.prepareReturn(cargo.id(),slot,maximum,tick):supply.prepareTransfer(cargo.id(),slot,maximum,tick)) {
            execute(work,prepared,destination.address(),registry.citizen(work.assignee()).bindingEpoch());
        } catch(AdmissionLedger.AdmissionException full) {waitFor(work,admissionReason(full),returning?"returning":"cargo");}
        if(work.waitingReason()==WorkOrder.Reason.PERMISSION_DENIED)advanceReturn(work,order,state,returning);
        if(supply.delivery(order.id()).terminal())finish(work);
    }
    private void advanceReturn(WorkOrder work,DeliveryOrder order,Active state,boolean returning) {
        var cargo=registry.supply().orderShares(order.id()).stream().filter(share -> share.stage()==CoverageShare.Stage.IN_TRANSIT).findFirst().orElse(null);
        if(registry.supply().productionSurplus(cargo==null?order.ownerDemandId():cargo.demandId())) {
            if(unusableReceiver(work))state.surplusFailure=work.waitingReason();
            navigation.cancel(work.id());state.waypoint=null;state.requiredChunks.clear();state.slotCursors.clear();state.unknownScans.clear();chunks.release(state.loadOwner);
            state.surplusBufferCursor++;
            waitFor(work,state.surplusFailure,registry.supply().hasCargo(order.id())?"cargo":"preflight-destination");return;
        }
        if(!returning)registry.supply().returnDelivery(order.id());
        else state.returnToSource=!state.returnToSource;
        navigation.cancel(work.id());state.waypoint=null;state.requiredChunks.clear();state.slotCursors.clear();state.unknownScans.clear();chunks.release(state.loadOwner);
        waitFor(work,WorkOrder.Reason.CAPACITY,"returning");
    }
    private void execute(WorkOrder work,SupplyRegistry.PreparedTransfer prepared,WorldPosition target,long epoch) {
        var colony=registry.colony(work.colonyId());
        var context=new ActionContext(work.colonyId(),work.assignee(),ActionContext.Kind.STORAGE_TRANSFER,target,ActionContext.AuthorityMode.COLONY,null,colony.authorityRevision());
        long started=System.nanoTime();
        var result=transfer.transfer(context,epoch,prepared.source(),prepared.destination(),prepared.item(),prepared.maximum(),prepared::commit);
        registry.metrics().record(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer.PHYSICAL_UNIT,System.nanoTime()-started);
        if(result.moved()==0||result.ambiguous())waitFor(work,result.reason()==WorkOrder.Reason.NONE?WorkOrder.Reason.RECONCILING:result.reason(),work.stage());
    }
    private void selfPickup(WorkOrder work,DeliveryOrder order,CoverageShare source,Active state,long tick) {
        var citizen=registry.citizen(work.assignee());
        if(!citizen.citizenId().equals(work.subjectId())) {waitFor(work,WorkOrder.Reason.WORKER,"pickup");return;}
        var location=storage.locate(source.slot().storage());
        if(location==null) {waitFor(work,WorkOrder.Reason.RECONCILING,"pickup");return;}
        if(!ready(work,state,location)||!move(work,state,location,"pickup"))return;
        var inventory=registry.storage().registrations(work.colonyId()).stream().filter(value -> value.storages().stream().anyMatch(id ->
                id.identity().equals(citizen.citizenId())&&id.bindingEpoch()==citizen.bindingEpoch())).findFirst().orElse(null);
        try {if(inventory==null)inventory=storage.registerCitizen(work.colonyId(),citizen.citizenId(),"return");}
        catch(AdmissionLedger.AdmissionException full) {waitFor(work,admissionReason(full),"pickup");return;}
        var slot=findSlot(work,state,inventory,order.item());
        if(slot==null) {if(state.scanFinished)waitFor(work,WorkOrder.Reason.CAPACITY,"pickup");return;}
        if(!observeSource(work,source,tick))return;
        try(var prepared=registry.supply().prepareSelfPickup(source.id(),order.id(),slot,1,tick)) {
            execute(work,prepared,location,citizen.bindingEpoch());
            if(registry.supply().delivery(order.id()).terminal())finish(work);
        } catch(AdmissionLedger.AdmissionException full) {waitFor(work,admissionReason(full),"pickup");}
    }
    /** Uses the current assignment until every owned physical cargo stack reaches safe registered storage. */
    public boolean requestFoodPreemption(CitizenRecord citizen) {
        if(registry.workBoard().requestFoodPreemption(citizen.citizenId())) {
            if(citizen.assignedWorkId()!=null)cancel(citizen.assignedWorkId());return true;
        }
        var current=registry.citizen(citizen.citizenId());
        if(current.assignedWorkId()==null)return false;
        var work=registry.workBoard().work(current.assignedWorkId());
        var order=registry.supply().deliveryForWork(work.id());
        if(order!=null&&registry.supply().hasCargo(order.id())) {
            if(!order.returnRequired())registry.supply().returnDelivery(order.id());
            return false; // The scheduler services the retained delivery assignment under its critical urgency.
        }
        var cargo=registry.supply().shares().stream().filter(share -> share.stage()==CoverageShare.Stage.ALLOCATED&&share.slot()!=null
                &&share.slot().storage().identity().equals(citizen.citizenId())&&share.slot().storage().bindingEpoch()==citizen.bindingEpoch()).findFirst().orElse(null);
        if(cargo==null)return registry.workBoard().requestFoodPreemption(citizen.citizenId());
        var state=active.computeIfAbsent(work.id(),ignored -> new Active());var buffer=returnBuffer(work.colonyId());
        if(buffer==null) {waitFor(work,WorkOrder.Reason.CAPACITY,"food-unload");return false;}
        if(!ready(work,state,buffer.address())||!move(work,state,buffer.address(),"food-unload"))return false;
        var slot=findSlot(work,state,buffer,cargo.item());
        if(slot==null) {if(state.scanFinished)waitFor(work,WorkOrder.Reason.CAPACITY,"food-unload");return false;}
        int maximum=(int)Math.min(cargo.quantity(),storage.capacity(slot,cargo.item()));
        if(maximum<1||!observeSource(work,cargo,Math.max(0,registry.budgets().tick())))return false;
        if(!registry.budgets().tryConsume(Budget.PHYSICAL_ACTIONS,work.lane())) {waitFor(work,WorkOrder.Reason.BUDGET,"food-unload");return false;}
        try(var prepared=registry.supply().prepareAllocationMove(cargo.id(),slot,maximum,Math.max(0,registry.budgets().tick()))) {
            var colony=registry.colony(work.colonyId());
            var context=new ActionContext(work.colonyId(),citizen.citizenId(),ActionContext.Kind.STORAGE_TRANSFER,buffer.address(),ActionContext.AuthorityMode.COLONY,null,colony.authorityRevision());
            var result=transfer.transfer(context,citizen.bindingEpoch(),prepared.source(),prepared.destination(),prepared.item(),prepared.maximum(),prepared::commit);
            if(result.moved()==0||result.ambiguous())waitFor(work,result.reason(),"food-unload");
        } catch(AdmissionLedger.AdmissionException full) {waitFor(work,admissionReason(full),"food-unload");}
        return false;
    }
    private static WorkOrder.Reason admissionReason(AdmissionLedger.AdmissionException full) {
        return full.reason()==AdmissionLedger.Reason.CRITICAL_CAPACITY?WorkOrder.Reason.CRITICAL_CAPACITY:WorkOrder.Reason.STATE_LIMIT;
    }
    private void criticalFailure(UUID demandId,AdmissionLedger.AdmissionException full) {
        if(full.reason()!=AdmissionLedger.Reason.CRITICAL_CAPACITY)return;
        UUID owner=registry.supply().demand(demandId).snapshot().ownerId();
        var food=registry.workBoard().works().stream().filter(value -> value.id().equals(owner)&&WorkOrder.FOOD.equals(value.typeId())&&!value.terminal()).findFirst().orElse(null);
        if(food!=null&&food.assignee()==null)registry.workBoard().transition(food.id(),WorkOrder.State.WAITING,WorkOrder.Reason.CRITICAL_CAPACITY,"food");
    }
    private StorageRegistry.Registration registration(UUID colony,WorldPosition address) {return registry.storage().registrations(colony).stream().filter(value -> value.address().equals(address)||value.positions().contains(address)).findFirst().orElse(null);}
    private StorageRegistry.Registration returnBuffer(UUID colony) {return registry.storage().registrations(colony).stream().filter(value -> value.role().equals("return")&&value.storages().stream().allMatch(id -> id.bindingEpoch()==0)).min(Comparator.comparing(StorageRegistry.Registration::id)).orElse(null);}
    private static boolean unusableReceiver(WorkOrder work) {
        return work.waitingReason()==WorkOrder.Reason.UNREACHABLE||work.waitingReason()==WorkOrder.Reason.PERMISSION_DENIED||work.waitingReason()==WorkOrder.Reason.WORKING_SET_LIMIT;
    }
    private StorageRegistry.Registration surplusDestination(WorkOrder work,DeliveryOrder order,Active state) {
        if(state.surplusPreferredDestination==null)state.surplusPreferredDestination=order.destination();
        var candidates=registry.storage().registrations(order.colonyId()).stream()
                .filter(r -> registry.supply().surplusBuffer(order.colonyId(),r,order.source().storage().dimension()))
                .sorted(Comparator.comparing((StorageRegistry.Registration r) -> !r.address().equals(state.surplusPreferredDestination))
                        .thenComparing(StorageRegistry.Registration::id)).toList();
        while(state.surplusBufferCursor<candidates.size()) {
            var candidate=candidates.get(state.surplusBufferCursor);
            if(!ready(work,state,candidate.address())) {
                if(!unusableReceiver(work))return null;
                advanceReturn(work,order,state,order.returnRequired());continue;
            }
            if(findSlot(work,state,candidate,order.item())!=null)return candidate;
            if(!state.scanFinished)return null;
            state.surplusBufferCursor++;
        }
        state.surplusBufferCursor=0;
        var reason=state.surplusFailure;state.surplusFailure=WorkOrder.Reason.CAPACITY;
        waitFor(work,reason,registry.supply().hasCargo(order.id())?"cargo":"preflight-destination");
        return null;
    }
    private StockRegion findSlot(WorkOrder work,Active state,StorageRegistry.Registration registration,io.github.kpuctajluk.colonyloom.core.storage.ItemDescriptor item) {
        int cursor=state.slotCursors.getOrDefault(registration.id(),0);state.scanFinished=false;
        for(int checked=0;checked<16&&cursor<registration.slots().size();checked++) {
            if(!registry.budgets().tryConsume(Budget.STORAGE_SLOT_CHECKS,work.lane())) {state.slotCursors.put(registration.id(),cursor);return null;}
            var slot=registration.slots().get(cursor++);int capacity=storage.capacity(slot,item);if(capacity<0)state.unknownScans.add(registration.id());
            if(capacity>0) {
                var observed=storage.readFresh(slot);
                if(!observed.ready()) {state.unknownScans.add(registration.id());continue;}
                registry.storage().index().observe(slot,observed.item(),observed.count(),Math.max(0,registry.budgets().tick()));
                state.slotCursors.remove(registration.id());state.unknownScans.remove(registration.id());return slot;
            }
        }
        if(cursor>=registration.slots().size()) {state.slotCursors.remove(registration.id());state.scanFinished=!state.unknownScans.remove(registration.id());if(!state.scanFinished)waitFor(work,WorkOrder.Reason.RECONCILING,work.stage());}
        else state.slotCursors.put(registration.id(),cursor);return null;
    }
    private boolean observeSource(WorkOrder work,CoverageShare share,long tick) {
        if(!registry.budgets().tryConsume(Budget.STORAGE_SLOT_CHECKS,work.lane()))return false;
        var observed=storage.readFresh(share.slot());
        if(!observed.ready()) {registry.storage().index().unknown(share.slot());waitFor(work,WorkOrder.Reason.RECONCILING,work.stage());return false;}
        registry.storage().index().observe(share.slot(),observed.item(),observed.count(),tick);
        if(!share.item().equals(observed.item())||observed.count()<share.quantity()) {waitFor(work,WorkOrder.Reason.RECONCILING,work.stage());return false;}
        return true;
    }
    private boolean ready(WorkOrder work,Active state,WorldPosition location) {
        var key=new ChunkKey(location.dimension(),location.x()>>4,location.z()>>4);
        if(state.requiredChunks.size()>=81&&!state.requiredChunks.contains(key)) {waitFor(work,WorkOrder.Reason.WORKING_SET_LIMIT,work.stage());return false;}
        state.requiredChunks.add(key);
        try {chunks.request(state.loadOwner,work.colonyId(),List.copyOf(state.requiredChunks),ChunkDemandManager.Readiness.ENTITY_TICKING,work.lane(),work.priority(),false);}
        catch(AdmissionLedger.AdmissionException full){waitFor(work,admissionReason(full),work.stage());return false;}
        if(!chunks.ready(state.loadOwner)){waitFor(work,chunks.state(state.loadOwner)==ChunkDemandManager.State.BLOCKED?WorkOrder.Reason.WORKING_SET_LIMIT:WorkOrder.Reason.CHUNK_NOT_READY,work.stage());return false;}chunks.useful(state.loadOwner);return true;
    }
    private boolean move(WorkOrder work,Active state,WorldPosition target,String stage) {
        var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(target.dimension())));
        if(level==null){waitFor(work,WorkOrder.Reason.CHUNK_NOT_READY,stage);return false;}
        var citizen=registry.citizen(work.assignee());
        var entity=storage.currentCitizen(citizen.citizenId(),citizen.bindingEpoch(),target.dimension());
        if(entity==null) {waitFor(work,WorkOrder.Reason.RECONCILING,stage);return false;}
        WorldPosition waypoint=null;
        for(int[] offset:OFFSETS) {
            var pos=new BlockPos(target.x()+offset[0],target.y(),target.z()+offset[1]);
            if(level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null||!level.isPositionEntityTicking(pos))continue;
            if(level.getBlockState(pos).getCollisionShape(level,pos).isEmpty()&&level.getBlockState(pos.above()).getCollisionShape(level,pos.above()).isEmpty()
                    &&!level.getBlockState(pos.below()).getCollisionShape(level,pos.below()).isEmpty()&&level.getFluidState(pos).isEmpty()) {
                var body=entity.getBoundingBox().move(pos.getX()+0.5-entity.getX(),pos.getY()-entity.getY(),pos.getZ()+0.5-entity.getZ());
                waypointOccupants.clear();waypointCitizen=entity;
                level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(net.minecraft.world.entity.Entity.class),body,waypointOccupied,waypointOccupants,1);
                boolean occupied=!waypointOccupants.isEmpty();waypointOccupants.clear();waypointCitizen=null;
                if(occupied) continue;
                waypoint=new WorldPosition(target.dimension(),pos.getX(),pos.getY(),pos.getZ());break;
            }
        }
        if(waypoint==null){waitFor(work,WorkOrder.Reason.UNREACHABLE,stage);return false;}
        if(!waypoint.equals(state.waypoint)) {navigation.cancel(work.id());state.waypoint=waypoint;state.generation++;}
        try {if(navigation.request(work.id(),work.colonyId(),citizen.citizenId(),citizen.bindingEpoch(),state.generation,waypoint,work.lane(),work.priority())==null){waitFor(work,WorkOrder.Reason.RECONCILING,stage);return false;}}
        catch(AdmissionLedger.AdmissionException full){waitFor(work,admissionReason(full),stage);return false;}
        if(navigation.atTarget(work.id()))return true;
        if(navigation.state(work.id())==NavigationService.State.WAITING)waitFor(work,navigation.reason(work.id()),stage);return false;
    }
    private static final int[][] OFFSETS={{0,1},{1,0},{0,-1},{-1,0}};
    private void waitFor(WorkOrder work,WorkOrder.Reason reason,String stage) {
        var order=registry.supply().deliveryForWork(work.id());
        if(reason==WorkOrder.Reason.CAPACITY&&order!=null&&!registry.supply().hasCargo(order.id())) {
            registry.workBoard().transition(work.id(),WorkOrder.State.WAITING,reason,stage);return;
        }
        registry.workBoard().waitAssigned(work.id(),reason==WorkOrder.Reason.NONE?WorkOrder.Reason.RECONCILING:reason,stage);
    }
    private void finish(WorkOrder work) {registry.workBoard().transition(work.id(),WorkOrder.State.COMPLETED,WorkOrder.Reason.NONE,"completed");}
    private void fail(WorkOrder work,WorkOrder.Reason reason) {registry.workBoard().transition(work.id(),WorkOrder.State.FAILED,reason,"failed");}
    @Override public void cancel(UUID workId) {if(navigation!=null)navigation.cancel(workId);var state=active.remove(workId);if(state!=null)chunks.release(state.loadOwner);}
    @Override public void close() {for(var id:List.copyOf(active.keySet()))cancel(id);}
}
