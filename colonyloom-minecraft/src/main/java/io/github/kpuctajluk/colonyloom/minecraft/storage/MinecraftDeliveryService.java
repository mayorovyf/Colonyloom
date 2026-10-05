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
    private static final AdmissionLedger.Lane[] LANES=AdmissionLedger.Lane.values();
    public record WaitingBuffer(String role, WorldPosition position) {}
    private static final class LoadDomain {
        final UUID owner=UUID.randomUUID();
        final List<ChunkKey> centers;
        final AdmissionLedger.Lane lane;
        final int priority;
        final boolean dependency;
        LoadDomain(List<ChunkKey> centers,WorkOrder work,boolean dependency) {
            this.centers=centers;lane=work.lane();priority=work.priority();this.dependency=dependency;
        }
        boolean matches(List<ChunkKey> centers,WorkOrder work,boolean dependency) {
            return this.centers.equals(centers)&&lane==work.lane()&&priority==work.priority()&&this.dependency==dependency;
        }
    }
    private static final class Active {
        LoadDomain loadDomain,retainedLoadDomain;
        WorldPosition waypoint;
        long generation;
        int waypointCursor;
        final Map<UUID,Integer> slotCursors=new HashMap<>();
        final Set<UUID> unknownScans=new HashSet<>();
        int slotCapacity;
        StockRegion pendingSlot;
        UUID pendingRegistration;
        int pendingCapacity;
        UUID pickupSource,pickupInventory,pickupDestination,pickupFallback;
        long pickupInventoryRevision,pickupDestinationRevision,pickupFallbackRevision;
        StockRegion pickupCargoSlot,pickupDestinationSlot,pickupReturnSlot;
        void clearPickup() {
            pickupSource=null;pickupInventory=null;pickupDestination=null;pickupFallback=null;
            pickupCargoSlot=null;pickupDestinationSlot=null;pickupReturnSlot=null;
        }
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
    private final Map<UUID, WaitingBuffer> waitingBuffers = new HashMap<>();
    private NavigationService navigation;
    private final int[] orderCursors=new int[LANES.length];
    private final DeliveryOrder[] dirtyOrders=new DeliveryOrder[LANES.length];
    private int dirtyLaneCursor;
    private final List<net.minecraft.world.entity.Entity> waypointOccupants=new ArrayList<>(1);
    private net.minecraft.world.entity.Entity waypointCitizen;
    private final java.util.function.Predicate<net.minecraft.world.entity.Entity> waypointOccupied=entity -> entity!=waypointCitizen && entity.isPushable() && !entity.isSpectator();
    public MinecraftDeliveryService(MinecraftServer server,ColonyRegistry registry,StorageService storage,StorageTransferExecutor transfer,ChunkDemandManager chunks) {
        this.server=server;this.registry=registry;this.storage=storage;this.transfer=transfer;this.chunks=chunks;
    }
    public void navigation(NavigationService navigation) {this.navigation=Objects.requireNonNull(navigation);}
    public WaitingBuffer waitingBuffer(UUID workId) {
        registry.requireOwner(); return registry.workBoard().work(workId).waitingReason()==WorkOrder.Reason.CAPACITY?waitingBuffers.get(workId):null;
    }
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
        for(var lane:LANES) {
            int index=lane.ordinal(),count=registry.supply().deliveryCount(lane);
            if(count==0) {dirtyOrders[index]=null;continue;}
            if(orderCursors[index]>=count)orderCursors[index]=0;
            dirtyOrders[index]=registry.supply().deliveryAt(lane,orderCursors[index]);
        }
        for(int serviced=0;serviced<2;serviced++) {
            boolean critical=false,service=false,normal=false;
            for(var order:dirtyOrders)if(order!=null)switch(deliveryLane(order)) {
                case CRITICAL -> critical=true;
                case SERVICE -> service=true;
                case NORMAL -> normal=true;
            }
            var lane=registry.budgets().chooseLane(Budget.DIRTY_RESCAN_OBJECTS,critical,service,normal);
            if(lane==null||!registry.budgets().tryConsume(Budget.DIRTY_RESCAN_OBJECTS,lane))return;
            for(int checked=0;checked<dirtyOrders.length;checked++) {
                int index=(dirtyLaneCursor+checked)%dirtyOrders.length;var order=dirtyOrders[index];
                if(order==null||deliveryLane(order)!=lane)continue;
                // Denied portions retain their exact candidate; wall-clock rotation cannot skip an order.
                orderCursors[index]++;dirtyOrders[index]=null;dirtyLaneCursor=(index+1)%dirtyOrders.length;
                tickOrder(order);break;
            }
        }
    }
    private AdmissionLedger.Lane deliveryLane(DeliveryOrder order) {
        return order.workId()==null?order.lane():registry.workBoard().work(order.workId()).lane();
    }
    private void tickOrder(DeliveryOrder order) {
        var assignedWork=order.workId()==null?null:registry.workBoard().work(order.workId());
        var lane=assignedWork==null?order.lane():assignedWork.lane();
        if(!registry.colony(order.colonyId()).available())return;
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
                try {requestLoadDomain(assignedWork,state,List.of(key),true);}
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
        waitingBuffers.remove(work.id());
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
            waitCapacity(work,destination,"destination","preflight-destination");return;
        }
        if(destination==null||fallback==null) {waitCapacity(work,destination==null?destination:fallback,destination==null?"destination":"return","preflight-destination");return;}
        // Source, destination and return are one stable preflight domain, not a protected
        // owner's incrementally expanding centers as its paid pickup prefix continues.
        prepareDomain(state,storage.locate(source.slot().storage()),destination.address(),fallback.address());
        String stage=work.stage();
        if(!stage.equals("preflight-return")&&!stage.equals("pickup")) {
            if(!ready(work,state,destination.address())) {if(surplus&&unusableReceiver(work))advanceReturn(work,order,state,false);return;}
            var slot=findSlot(work,state,destination,order.item());
            if(slot==null) {if(state.scanFinished) {waitCapacity(work,destination,"destination","preflight-destination");if(surplus)advanceReturn(work,order,state,false);}return;}
            if(!move(work,state,destination.address(),"preflight-destination")) {if(surplus&&unusableReceiver(work))advanceReturn(work,order,state,false);return;}
            waitFor(work,WorkOrder.Reason.BUDGET,"preflight-return");return;
        }
        if(stage.equals("preflight-return")) {
            if(!ready(work,state,fallback.address())) {if(surplus&&unusableReceiver(work))advanceReturn(work,order,state,false);return;}
            var slot=findSlot(work,state,fallback,order.item());
            if(slot==null) {if(state.scanFinished) {waitCapacity(work,fallback,"return","preflight-return");if(surplus)advanceReturn(work,order,state,false);}return;}
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
        if(!source.id().equals(state.pickupSource)||!inventory.id().equals(state.pickupInventory)
                ||inventory.revision()!=state.pickupInventoryRevision||!destination.id().equals(state.pickupDestination)
                ||destination.revision()!=state.pickupDestinationRevision||!fallback.id().equals(state.pickupFallback)
                ||fallback.revision()!=state.pickupFallbackRevision) {
            state.clearPickup();state.pickupSource=source.id();state.pickupInventory=inventory.id();
            state.pickupDestination=destination.id();state.pickupFallback=fallback.id();
            state.pickupInventoryRevision=inventory.revision();state.pickupDestinationRevision=destination.revision();
            state.pickupFallbackRevision=fallback.revision();
        }
        // Completed paid scans select candidates, not promises of future room. Retain this
        // bounded prefix so a partial tick does not repeatedly rescan only the courier.
        if(state.pickupCargoSlot==null) {
            state.pickupCargoSlot=findSlot(work,state,inventory,order.item());
            if(state.pickupCargoSlot==null) {if(state.scanFinished)waitCapacity(work,inventory,"courier","pickup");return;}
        }
        if(state.pickupDestinationSlot==null) {
            state.pickupDestinationSlot=findSlot(work,state,destination,order.item());
            if(state.pickupDestinationSlot==null) {if(state.scanFinished) {waitCapacity(work,destination,"destination","preflight-destination");if(surplus)advanceReturn(work,order,state,false);}return;}
        }
        if(state.pickupReturnSlot==null) {
            state.pickupReturnSlot=findSlot(work,state,fallback,order.item());
            if(state.pickupReturnSlot==null) {if(state.scanFinished) {waitCapacity(work,fallback,"return","preflight-return");if(surplus)advanceReturn(work,order,state,false);}return;}
        }
        // All selected buffers and the source must be checked freshly in one owner-thread
        // portion before extraction; yesterday's capacity never authorizes native cargo.
        if(registry.budgets().limits().budget(Budget.STORAGE_SLOT_CHECKS)-registry.budgets().used(Budget.STORAGE_SLOT_CHECKS)<4)return;
        for(int checked=0;checked<3;checked++)if(!registry.budgets().tryConsume(Budget.STORAGE_SLOT_CHECKS,work.lane()))return;
        int cargoCapacity=storage.capacity(state.pickupCargoSlot,order.item());
        int destinationCapacity=storage.capacity(state.pickupDestinationSlot,order.item());
        int returnCapacity=storage.capacity(state.pickupReturnSlot,order.item());
        int maximum=(int)Math.min(source.quantity(),Math.min(cargoCapacity,Math.min(destinationCapacity,returnCapacity)));
        if(maximum<1) {
            state.clearPickup();
            if(maximum<0)waitFor(work,WorkOrder.Reason.RECONCILING,"preflight-destination");
            else if(cargoCapacity==0)waitCapacity(work,inventory,"courier","pickup");
            else if(destinationCapacity==0)waitCapacity(work,destination,"destination","preflight-destination");
            else waitCapacity(work,fallback,"return","preflight-return");
            if(surplus&&maximum==0)advanceReturn(work,order,state,false);return;
        }
        if(!observeSource(work,source,tick))return;
        var cargoSlot=state.pickupCargoSlot;
        try(var prepared=supply.preparePickup(source.id(),order.id(),cargoSlot,maximum,tick)) {
            execute(work,prepared,location,citizen.bindingEpoch());
            if(supply.hasCargo(order.id()))waitFor(work,WorkOrder.Reason.BUDGET,"cargo");
            state.clearPickup();
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
        prepareDomain(state,original,destination.address(),null);
        if(!ready(work,state,destination.address())) {
            if(work.waitingReason()==WorkOrder.Reason.WORKING_SET_LIMIT)advanceReturn(work,order,state,returning);
            return;
        }
        var slot=findSlot(work,state,destination,order.item());
        if(slot==null) {if(state.scanFinished) {waitCapacity(work,destination,returning?(state.returnToSource?"source":"return"):"destination",returning?"returning":"cargo");advanceReturn(work,order,state,returning);}return;}
        if(!move(work,state,destination.address(),returning?"returning":"cargo")) {
            if(work.waitingReason()==WorkOrder.Reason.UNREACHABLE||work.waitingReason()==WorkOrder.Reason.PERMISSION_DENIED||work.waitingReason()==WorkOrder.Reason.WORKING_SET_LIMIT)
                advanceReturn(work,order,state,returning);
            return;
        }
        int maximum=(int)Math.min(cargo.quantity(),state.slotCapacity);
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
            state.clearPickup();
            navigation.cancel(work.id());state.waypoint=null;state.requiredChunks.clear();state.slotCursors.clear();state.unknownScans.clear();
            if(!registry.supply().hasCargo(order.id()))releaseLoadDomains(state);
            state.surplusBufferCursor++;
            waitFor(work,state.surplusFailure,registry.supply().hasCargo(order.id())?"cargo":"preflight-destination");return;
        }
        if(!returning)registry.supply().returnDelivery(order.id());
        else state.returnToSource=!state.returnToSource;
        state.clearPickup();
        navigation.cancel(work.id());state.waypoint=null;state.requiredChunks.clear();state.slotCursors.clear();state.unknownScans.clear();
        if(!registry.supply().hasCargo(order.id()))releaseLoadDomains(state);
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
        prepareDomain(state,location,null,null);
        if(!ready(work,state,location)||!move(work,state,location,"pickup"))return;
        var inventory=registry.storage().registrations(work.colonyId()).stream().filter(value -> value.storages().stream().anyMatch(id ->
                id.identity().equals(citizen.citizenId())&&id.bindingEpoch()==citizen.bindingEpoch())).findFirst().orElse(null);
        try {if(inventory==null)inventory=storage.registerCitizen(work.colonyId(),citizen.citizenId(),"return");}
        catch(AdmissionLedger.AdmissionException full) {waitFor(work,admissionReason(full),"pickup");return;}
        var slot=findSlot(work,state,inventory,order.item());
        if(slot==null) {if(state.scanFinished)waitCapacity(work,inventory,"courier","pickup");return;}
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
        if(buffer==null) {waitCapacity(work,null,"return","food-unload");return false;}
        prepareDomain(state,storage.locate(cargo.slot().storage()),buffer.address(),null);
        if(!ready(work,state,buffer.address())||!move(work,state,buffer.address(),"food-unload"))return false;
        var slot=findSlot(work,state,buffer,cargo.item());
        if(slot==null) {if(state.scanFinished)waitCapacity(work,buffer,"return","food-unload");return false;}
        int maximum=(int)Math.min(cargo.quantity(),state.slotCapacity);
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
            prepareDomain(state,storage.locate(order.source().storage()),candidate.address(),null);
            if(!ready(work,state,candidate.address())) {
                if(!unusableReceiver(work))return null;
                advanceReturn(work,order,state,order.returnRequired());continue;
            }
            if(findSlot(work,state,candidate,order.item())!=null)return candidate;
            if(!state.scanFinished)return null;
            waitingBuffers.put(work.id(),new WaitingBuffer("destination",candidate.address()));
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
            var slot=registration.slots().get(cursor);
            boolean pending=registration.id().equals(state.pendingRegistration)&&slot.equals(state.pendingSlot);
            int capacity;
            if(pending)capacity=state.pendingCapacity;
            else {
                if(!registry.budgets().tryConsume(Budget.STORAGE_SLOT_CHECKS,work.lane())) {state.slotCursors.put(registration.id(),cursor);return null;}
                capacity=storage.capacity(slot,item);
                if(capacity>0) {state.pendingSlot=slot;state.pendingRegistration=registration.id();state.pendingCapacity=capacity;}
            }
            if(capacity<0)state.unknownScans.add(registration.id());
            if(capacity>0) {
                if(!registry.budgets().tryConsume(Budget.STORAGE_SLOT_CHECKS,work.lane())) {state.slotCursors.put(registration.id(),cursor);return null;}
                var observed=storage.readFresh(slot);state.pendingSlot=null;state.pendingRegistration=null;
                if(!observed.ready())state.unknownScans.add(registration.id());
                else {
                    registry.storage().index().observe(slot,observed.item(),observed.count(),Math.max(0,registry.budgets().tick()));
                    state.slotCapacity=capacity;
                    state.slotCursors.remove(registration.id());state.unknownScans.remove(registration.id());return slot;
                }
            }
            cursor++;
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
    private static ChunkKey chunk(WorldPosition position) {return new ChunkKey(position.dimension(),position.x()>>4,position.z()>>4);}
    private static void prepareDomain(Active state,WorldPosition source,WorldPosition destination,WorldPosition fallback) {
        state.requiredChunks.clear();
        if(source!=null)state.requiredChunks.add(chunk(source));
        if(destination!=null)state.requiredChunks.add(chunk(destination));
        if(fallback!=null)state.requiredChunks.add(chunk(fallback));
    }
    private boolean admittedLoadDomain(Active state) {
        return state.loadDomain!=null&&chunks.admitted(state.loadDomain.owner)
                ||state.retainedLoadDomain!=null&&chunks.admitted(state.retainedLoadDomain.owner);
    }
    private void releaseLoadDomains(Active state) {
        if(state.loadDomain!=null)chunks.release(state.loadDomain.owner);
        if(state.retainedLoadDomain!=null)chunks.release(state.retainedLoadDomain.owner);
        state.loadDomain=null;state.retainedLoadDomain=null;
    }
    private LoadDomain requestLoadDomain(WorkOrder work,Active state,List<ChunkKey> centers,boolean dependency) {
        var domain=state.loadDomain;
        if(domain==null||!domain.matches(centers,work,dependency)) {
            if(state.retainedLoadDomain!=null) {
                // This desired successor has never authorized movement/effects. A changed
                // target may rebuild it, but cannot replace or withdraw the retained owner.
                if(domain!=null)chunks.release(domain.owner);
                state.loadDomain=null;
            } else if(domain!=null) {
                if(chunks.admitted(domain.owner)) {
                    state.retainedLoadDomain=domain;
                    var order=registry.supply().deliveryForWork(work.id());
                    chunks.setProtection(domain.owner,true,order!=null&&registry.supply().hasCargo(order.id()),work.criticalService());
                } else chunks.release(domain.owner);
                state.loadDomain=null;
            }
            domain=new LoadDomain(centers,work,dependency);
            // Admission failure leaves the original charged, protected domain intact.
            chunks.request(domain.owner,work.colonyId(),centers,ChunkDemandManager.Readiness.ENTITY_TICKING,domain.lane,domain.priority,dependency);
            state.loadDomain=domain;
        }
        if(chunks.ready(domain.owner)&&state.retainedLoadDomain!=null) {
            // Every successor includes the current courier center as well as its endpoints.
            chunks.release(state.retainedLoadDomain.owner);state.retainedLoadDomain=null;
        }
        return domain;
    }
    private boolean ready(WorkOrder work,Active state,WorldPosition location) {
        var key=chunk(location);
        // Borrow an actual admitted ticking center, never an incidental external load.
        if(chunks.admitted(key)&&chunks.ready(key,ChunkDemandManager.Readiness.ENTITY_TICKING)
                &&(!admittedLoadDomain(state)||chunks.admitted(work.id())&&chunks.ready(work.id()))) {
            releaseLoadDomains(state);return true;
        }
        state.requiredChunks.add(key);
        state.requiredChunks.add(chunk(registry.citizen(work.assignee()).lastKnownPosition()));
        LoadDomain domain;
        try {domain=requestLoadDomain(work,state,List.copyOf(state.requiredChunks),false);}
        catch(AdmissionLedger.AdmissionException full){waitFor(work,admissionReason(full),work.stage());return false;}
        if(!chunks.ready(domain.owner)){waitFor(work,chunks.state(domain.owner)==ChunkDemandManager.State.BLOCKED?WorkOrder.Reason.WORKING_SET_LIMIT:WorkOrder.Reason.CHUNK_NOT_READY,work.stage());return false;}chunks.useful(domain.owner);return true;
    }
    private boolean move(WorkOrder work,Active state,WorldPosition target,String stage) {
        var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(target.dimension())));
        if(level==null){waitFor(work,WorkOrder.Reason.CHUNK_NOT_READY,stage);return false;}
        var citizen=registry.citizen(work.assignee());
        var entity=storage.currentCitizen(citizen.citizenId(),citizen.bindingEpoch(),target.dimension());
        if(entity==null) {waitFor(work,WorkOrder.Reason.RECONCILING,stage);return false;}
        // The transfer executor rechecks this same native reach, binding and authority before any expense.
        // Requiring a vacant adjacent goal even while already in reach strands dense food queues.
        if(entity.distanceToSqr(target.x()+0.5,target.y()+0.5,target.z()+0.5)<=9) {
            navigation.cancel(work.id());state.waypoint=null;return true;
        }
        if(state.waypoint!=null&&navigation.state(work.id())!=NavigationService.State.CANCELLED
                &&state.waypoint.dimension().equals(target.dimension())&&state.waypoint.y()==target.y()
                &&Math.abs(state.waypoint.x()-target.x())<=2&&Math.abs(state.waypoint.z()-target.z())<=2
                &&navigation.reason(work.id())!=WorkOrder.Reason.UNREACHABLE) {
            try {if(navigation.request(work.id(),work.colonyId(),citizen.citizenId(),citizen.bindingEpoch(),state.generation,state.waypoint,work.lane(),work.priority())==null){waitFor(work,WorkOrder.Reason.RECONCILING,stage);return false;}}
            catch(AdmissionLedger.AdmissionException full){waitFor(work,admissionReason(full),stage);return false;}
            if(navigation.state(work.id())==NavigationService.State.WAITING)waitFor(work,navigation.reason(work.id()),stage);
            return false;
        }
        WorldPosition waypoint=null;
        if(navigation.reason(work.id())==WorkOrder.Reason.UNREACHABLE)state.waypointCursor=(state.waypointCursor+1)%OFFSETS.length;
        for(int checked=0;checked<OFFSETS.length;checked++) {
            int candidate=(state.waypointCursor+checked)%OFFSETS.length;
            int[] offset=OFFSETS[candidate];
            var pos=new BlockPos(target.x()+offset[0],target.y(),target.z()+offset[1]);
            if(level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null||!level.isPositionEntityTicking(pos))continue;
            if(level.getBlockState(pos).getCollisionShape(level,pos).isEmpty()&&level.getBlockState(pos.above()).getCollisionShape(level,pos.above()).isEmpty()
                    &&!level.getBlockState(pos.below()).getCollisionShape(level,pos.below()).isEmpty()&&level.getFluidState(pos).isEmpty()) {
                var body=entity.getBoundingBox().move(pos.getX()+0.5-entity.getX(),pos.getY()-entity.getY(),pos.getZ()+0.5-entity.getZ());
                waypointOccupants.clear();waypointCitizen=entity;
                level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(net.minecraft.world.entity.Entity.class),body,waypointOccupied,waypointOccupants,1);
                boolean occupied=!waypointOccupants.isEmpty();waypointOccupants.clear();waypointCitizen=null;
                if(occupied) continue;
                state.waypointCursor=candidate;waypoint=new WorldPosition(target.dimension(),pos.getX(),pos.getY(),pos.getZ());break;
            }
        }
        if(waypoint==null){
            navigation.cancel(work.id());state.waypoint=null;
            var order=registry.supply().deliveryForWork(work.id());
            if(order!=null&&!registry.supply().hasCargo(order.id())) {releaseLoadDomains(state);state.requiredChunks.clear();}
            waitFor(work,WorkOrder.Reason.UNREACHABLE,stage);return false;
        }
        if(!waypoint.equals(state.waypoint)) {navigation.cancel(work.id());state.waypoint=waypoint;state.generation++;}
        try {if(navigation.request(work.id(),work.colonyId(),citizen.citizenId(),citizen.bindingEpoch(),state.generation,waypoint,work.lane(),work.priority())==null){waitFor(work,WorkOrder.Reason.RECONCILING,stage);return false;}}
        catch(AdmissionLedger.AdmissionException full){waitFor(work,admissionReason(full),stage);return false;}
        if(navigation.atTarget(work.id()))return true;
        if(navigation.state(work.id())==NavigationService.State.WAITING)waitFor(work,navigation.reason(work.id()),stage);return false;
    }
    private static final int[][] OFFSETS={{0,1},{1,0},{0,-1},{-1,0},{1,1},{1,-1},{-1,1},{-1,-1},
            {0,2},{2,0},{0,-2},{-2,0},{1,2},{2,1},{1,-2},{2,-1},{-1,2},{-2,1},{-1,-2},{-2,-1}};
    private void waitCapacity(WorkOrder work,StorageRegistry.Registration buffer,String role,String stage) {
        WorldPosition position=buffer==null?null:buffer.address();
        waitingBuffers.put(work.id(),new WaitingBuffer(role,position));
        waitFor(work,WorkOrder.Reason.CAPACITY,stage);
    }
    private void waitFor(WorkOrder work,WorkOrder.Reason reason,String stage) {
        var order=registry.supply().deliveryForWork(work.id());
        if(reason==WorkOrder.Reason.CAPACITY&&order!=null&&!registry.supply().hasCargo(order.id())) {
            cancel(work.id());registry.workBoard().transition(work.id(),WorkOrder.State.WAITING,reason,stage);return;
        }
        registry.workBoard().waitAssigned(work.id(),reason==WorkOrder.Reason.NONE?WorkOrder.Reason.RECONCILING:reason,stage);
    }
    private void finish(WorkOrder work) {waitingBuffers.remove(work.id());cancel(work.id());registry.workBoard().transition(work.id(),WorkOrder.State.COMPLETED,WorkOrder.Reason.NONE,"completed");}
    private void fail(WorkOrder work,WorkOrder.Reason reason) {waitingBuffers.remove(work.id());cancel(work.id());registry.workBoard().transition(work.id(),WorkOrder.State.FAILED,reason,"failed");}
    @Override public void cancel(UUID workId) {if(navigation!=null)navigation.cancel(workId);var state=active.remove(workId);if(state!=null)releaseLoadDomains(state);}
    @Override public void close() {for(var id:List.copyOf(active.keySet()))cancel(id);waitingBuffers.clear();}
}
