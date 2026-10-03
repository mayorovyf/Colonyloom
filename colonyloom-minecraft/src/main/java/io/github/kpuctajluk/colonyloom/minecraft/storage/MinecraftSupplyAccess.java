package io.github.kpuctajluk.colonyloom.minecraft.storage;

import io.github.kpuctajluk.colonyloom.core.colony.*;
import io.github.kpuctajluk.colonyloom.core.storage.*;
import io.github.kpuctajluk.colonyloom.core.supply.*;
import java.util.*;

/** One bounded scan cursor: at most sixteen indexed slots examined per planning portion. */
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
    private List<StorageRegistry.Registration> registrations=List.of();
    private final ArrayList<Ranked> selected=new ArrayList<>(16);
    private int registrationIndex,slotIndex;
    private Ranked after;
    public MinecraftSupplyAccess(ColonyRegistry registry,StorageService physical) {this.registry=Objects.requireNonNull(registry);this.physical=Objects.requireNonNull(physical);}
    @Override public KitAllocator.Observation read(StockRegion slot) {
        var observed=physical.readFresh(slot);
        return new KitAllocator.Observation(observed.item(),observed.count(),physical.observationRevision(slot),observed.ready());
    }
    @Override public SupplyPlanner.CandidatePage candidates(Demand.Snapshot demand,ItemMatcher matcher,int offset,int limit) {
        registry.requireOwner();if(limit<1||limit>16||offset<0)throw new IllegalArgumentException("Candidate page bounds");
        if(offset==0||root==null||!root.id().equals(demand.id())||root.revision()!=demand.revision()||!matcher.equals(target)) {
            root=demand;target=matcher;registrations=registry.storage().registrations(demand.colonyId());
            if(!demand.sourceStorages().isEmpty())registrations=registrations.stream().filter(value -> value.storages().stream().anyMatch(demand.sourceStorages()::contains)).toList();
            registrationIndex=slotIndex=0;selected.clear();after=null;
        }
        int examined=0;
        while(registrationIndex<registrations.size()&&examined<16) {
            var registration=registrations.get(registrationIndex);
            if(slotIndex>=registration.slots().size()){registrationIndex++;slotIndex=0;continue;}
            StockRegion slot=registration.slots().get(slotIndex++);examined++;
            if(!demand.sourceStorages().isEmpty()&&!demand.sourceStorages().contains(slot.storage()))continue;
            var observation=registry.storage().index().observation(slot);
            long available=registry.storage().index().free(slot,registry.budgets().tick());
            if(!observation.ready()||!matcher.matches(observation.item())||available==0)continue;
            int rank=rank(demand,registration,slot);if(rank<0)continue;
            Ranked candidate=new Ranked(new KitAllocator.Candidate(slot,observation.item(),available,physical.observationRevision(slot)),rank,distance(demand.destination(),registration.address()));
            if(after!=null&&ORDER.compare(candidate,after)<=0)continue;
            boolean duplicate=false;for(var previous:selected)if(previous.candidate().slot().equals(slot)){duplicate=true;break;}if(duplicate)continue;
            int position=Collections.binarySearch(selected,candidate,ORDER);if(position<0)position=-position-1;
            if(position<limit){selected.add(position,candidate);if(selected.size()>limit)selected.removeLast();}
        }
        if(registrationIndex<registrations.size())return new SupplyPlanner.CandidatePage(List.of(),false);
        var result=selected.stream().map(Ranked::candidate).toList();boolean exhausted=selected.size()<limit;
        if(!selected.isEmpty())after=selected.getLast();selected.clear();registrationIndex=slotIndex=0;
        return new SupplyPlanner.CandidatePage(result,exhausted);
    }
    private static int rank(Demand.Snapshot demand,StorageRegistry.Registration registration,StockRegion slot) {
        if(slot.storage().bindingEpoch()>0)return slot.storage().identity().equals(demand.ownerId())?0:-1;
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
