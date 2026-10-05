package io.github.kpuctajluk.colonyloom.minecraft.storage;

import io.github.kpuctajluk.colonyloom.core.colony.*;
import io.github.kpuctajluk.colonyloom.core.storage.*;
import io.github.kpuctajluk.colonyloom.core.supply.*;
import java.util.*;

/** One item-index cursor: at most sixteen slot/registration checks per planning portion. */
public final class MinecraftSupplyAccess implements SupplyPlanner.PhysicalAccess {
    private record Ranked(KitAllocator.Candidate candidate,int rank,long distance) {}
    private static final Comparator<Ranked> ORDER=Comparator.comparingInt(Ranked::rank).thenComparingLong(Ranked::distance)
            .thenComparing(value -> value.candidate().slot().storage().dimension())
            .thenComparing(value -> value.candidate().slot().storage().identity())
            .thenComparingLong(value -> value.candidate().slot().storage().bindingEpoch()).thenComparingInt(value -> value.candidate().slot().slot());
    private final ColonyRegistry registry;
    private final StorageService physical;
    private Demand.Snapshot root;
    private ItemMatcher target;
    private final ArrayList<Ranked> selected=new ArrayList<>(16);
    private StockRegion scanAfter,pendingSlot;
    private List<StorageRegistry.Registration> pendingRegistrations=List.of();
    private List<StockRegion> consumerSlots=List.of();
    private int pendingRegistrationIndex;
    private Ranked pendingBest,after;
    private boolean pageComplete;
    public MinecraftSupplyAccess(ColonyRegistry registry,StorageService physical) {this.registry=Objects.requireNonNull(registry);this.physical=Objects.requireNonNull(physical);}
    @Override public KitAllocator.Observation read(StockRegion slot) {
        var observed=physical.readFresh(slot);
        return new KitAllocator.Observation(observed.item(),observed.count(),physical.observationRevision(slot),observed.ready());
    }
    @Override public SupplyPlanner.CandidatePage candidates(Demand.Snapshot demand,ItemMatcher matcher,int offset,int limit) {
        registry.requireOwner();if(limit<1||limit>16||offset<0)throw new IllegalArgumentException("Candidate page bounds");
        if(offset==0&&pageComplete||root==null||!root.id().equals(demand.id())||root.revision()!=demand.revision()||!matcher.equals(target)) {
            root=demand;target=matcher;scanAfter=pendingSlot=null;pendingRegistrations=List.of();
            pendingRegistrationIndex=0;pendingBest=null;selected.clear();after=null;
            pageComplete=false;
            consumerSlots=List.of();
            if(registry.supply().foodConsumer(demand.id())) {
                var citizen=registry.citizen(registry.workBoard().work(demand.ownerId()).subjectId());
                var id=new StorageId(citizen.lastKnownPosition().dimension(),citizen.citizenId(),citizen.bindingEpoch());
                var local=registry.storage().registrations(demand.colonyId(),new StockRegion(id,0));
                var inventory=local.isEmpty()?physical.registerCitizen(demand.colonyId(),citizen.citizenId(),"return"):local.getFirst();
                consumerSlots=inventory.slots();
            }
        }
        int examined=0;
        // Observe the bounded subject inventory before promising a lower-ranked warehouse source.
        for(var slot:consumerSlots) {
            examined++;
            if(!registry.storage().index().observation(slot).ready())return new SupplyPlanner.CandidatePage(List.of(),false);
        }
        boolean complete=false;
        while(examined<16) {
            if(pendingSlot==null) {
                StockRegion slot=registry.storage().index().nextMatchingSlot(matcher.itemId(),scanAfter);
                if(slot==null){complete=true;break;}
                scanAfter=slot;examined++;
                if(!demand.sourceStorages().isEmpty()&&!demand.sourceStorages().contains(slot.storage()))continue;
                if(!registry.storage().authorized(demand.colonyId(),slot)||registry.storage().isRetired(slot.storage()))continue;
                var observation=registry.storage().index().observation(slot);
                if(!observation.ready()||!matcher.matches(observation.item())||registry.storage().index().free(slot,registry.budgets().tick())==0)continue;
                pendingSlot=slot;pendingRegistrations=registry.storage().registrations(demand.colonyId(),slot);
                pendingRegistrationIndex=0;pendingBest=null;
            }
            var current=registry.storage().registrations(demand.colonyId(),pendingSlot);
            if(current!=pendingRegistrations){pendingRegistrations=current;pendingRegistrationIndex=0;pendingBest=null;}
            if(pendingRegistrationIndex<pendingRegistrations.size()) {
                if(examined==16)break;
                var registration=pendingRegistrations.get(pendingRegistrationIndex++);examined++;
                int rank=rank(demand,registration,pendingSlot);
                if(rank>=0) {
                    var observation=registry.storage().index().observation(pendingSlot);
                    long available=registry.storage().index().free(pendingSlot,registry.budgets().tick());
                    if(observation.ready()&&matcher.matches(observation.item())&&available>0&&!registry.storage().isRetired(pendingSlot.storage())) {
                        Ranked candidate=new Ranked(new KitAllocator.Candidate(pendingSlot,observation.item(),available,physical.observationRevision(pendingSlot)),rank,distance(demand.destination(),registration.address()));
                        if(pendingBest==null||ORDER.compare(candidate,pendingBest)<0)pendingBest=candidate;
                    }
                }
                if(pendingRegistrationIndex<pendingRegistrations.size())continue;
            }
            if(pendingBest!=null) {
                var observation=registry.storage().index().observation(pendingSlot);
                long available=registry.storage().index().free(pendingSlot,registry.budgets().tick());
                if(!observation.ready()||!matcher.matches(observation.item())||available==0
                        ||!registry.storage().authorized(demand.colonyId(),pendingSlot)||registry.storage().isRetired(pendingSlot.storage()))pendingBest=null;
                else pendingBest=new Ranked(new KitAllocator.Candidate(pendingSlot,observation.item(),available,physical.observationRevision(pendingSlot)),pendingBest.rank(),pendingBest.distance());
            }
            if(pendingBest!=null&&(after==null||ORDER.compare(pendingBest,after)>0)) {
                int position=Collections.binarySearch(selected,pendingBest,ORDER);if(position<0)position=-position-1;
                if(position<limit){selected.add(position,pendingBest);if(selected.size()>limit)selected.removeLast();}
            }
            pendingSlot=null;pendingRegistrations=List.of();pendingBest=null;
        }
        if(!complete)return new SupplyPlanner.CandidatePage(List.of(),false);
        var result=selected.stream().map(Ranked::candidate).toList();boolean exhausted=selected.size()<limit;
        if(!selected.isEmpty())after=selected.getLast();selected.clear();scanAfter=null;
        pageComplete=true;
        return new SupplyPlanner.CandidatePage(result,exhausted);
    }
    private int rank(Demand.Snapshot demand,StorageRegistry.Registration registration,StockRegion slot) {
        if(slot.storage().bindingEpoch()>0) {
            UUID consumer=demand.ownerId();
            if(registry.supply().foodConsumer(demand.id()))consumer=registry.workBoard().work(consumer).subjectId();
            var citizen=registry.findCitizen(consumer).orElse(null);
            return citizen!=null&&slot.storage().identity().equals(citizen.citizenId())
                    &&slot.storage().bindingEpoch()==citizen.bindingEpoch()?0:-1;
        }
        if(registration.role().equals("warehouse"))return 2;
        if(registration.role().equals("construction")||registration.role().equals("workshop")||registration.role().equals("return"))return 1;
        return -1;
    }
    private static long distance(WorldPosition a,WorldPosition b) {
        if(!a.dimension().equals(b.dimension()))return Long.MAX_VALUE;
        long x=(long)a.x()-b.x(),y=(long)a.y()-b.y(),z=(long)a.z()-b.z();
        double squared=(double)x*x+(double)y*y+(double)z*z;return squared>=Long.MAX_VALUE?Long.MAX_VALUE:(long)squared;
    }
}
