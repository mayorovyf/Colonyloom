package io.github.kpuctajluk.colonyloom.core.navigation;

import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Owner-thread navigation; derived routes and bounded negative caches are never persisted. */
public final class NavigationService implements AutoCloseable {
    public enum State { WAITING, MOVING, ARRIVED, CANCELLED }
    public enum Motion { MOVING, ARRIVED, OBSTRUCTED, UNAVAILABLE }
    public record Request(UUID id, UUID workId, UUID colonyId, UUID citizenId, long epoch,
            long goalRevision, WorldPosition target, Lane lane, int priority) {
        public Request {
            Objects.requireNonNull(id); Objects.requireNonNull(workId); Objects.requireNonNull(colonyId);
            Objects.requireNonNull(citizenId); Objects.requireNonNull(target); Objects.requireNonNull(lane);
            if (epoch < 1 || goalRevision < 0 || priority < 0 || priority > 10) throw new IllegalArgumentException("Invalid navigation request");
        }
    }
    public sealed interface SearchResult permits Route, SearchOutcome { }
    public non-sealed interface Route extends SearchResult { int nodeCount(); }
    public enum SearchOutcome implements SearchResult {
        PENDING, CAPACITY_WAIT, UNREACHABLE, EXHAUSTED, UNAVAILABLE, WORKING_SET_LIMIT
    }
    public interface Backend {
        WorldPosition position(Request request);
        /** Advances one bounded portion, returning a route or an explicit search outcome. */
        SearchResult search(Request request, List<ChunkKey> admittedRegion);
        boolean apply(Request request, Route route, BooleanSupplier stillCurrent);
        Motion poll(Request request);
        void stop(Request request);
    }
    public interface GoalAuthority { boolean current(WorkOrder work,Request request); }
    public static boolean moveGoalCurrent(WorkOrder work,Request request) {
        return request.target().equals(work.target());
    }
    private static List<TreeSet<Entry>> startQueues() {
        return List.of(new TreeSet<>(ORDER), new TreeSet<>(ORDER), new TreeSet<>(ORDER));
    }
    private static final int[] RETRY = {20, 40, 80, 160, 200};
    private static final Comparator<Entry> ORDER = Comparator.<Entry,Boolean>comparing(e -> e.capacityWaiting)
            .thenComparing(Comparator.<Entry>comparingInt(e -> e.request.priority()).reversed())
            .thenComparingLong(e -> e.startOrder).thenComparingLong(e -> e.created).thenComparing(e -> e.request.id());
    private final ColonyRegistry registry;
    private final GlobalWorkBudgets budgets;
    private final ChunkDemandManager chunks;
    private final Backend backend;
    private final GoalAuthority goals;
    private final Map<UUID, Entry> requests = new HashMap<>();
    private final Map<ChunkKey, Bucket> index = new HashMap<>();
    private final List<TreeSet<Entry>> starts = startQueues(), deferredStarts = startQueues();
    private Entry head;
    private final Entry[] laneHeads=new Entry[3], laneCursors=new Entry[3];
    private long now;
    private long startOrder;
    private long lastSearchNanos, maxSearchNanos;
    private long searchCount, staleResults, failedSearches;
    private static final class Bucket { long revision; int references; }
    private static final class Entry {
        final Request request;
        final long created;
        final AdmissionLedger.Lease lease;
        AdmissionLedger.Lease routeLease, domainLease;
        List<ChunkKey> region = List.of();
        Bucket[] buckets = new Bucket[0];
        long[] revisions = new long[0];
        Entry previous, next, lanePrevious, laneNext;
        long polledTick=Long.MIN_VALUE;
        long searchedTick=Long.MIN_VALUE, startOrder;
        State state = State.WAITING;
        Reason reason = Reason.CHUNK_NOT_READY;
        boolean queued, deferred, capacityWaiting, domainReleased,cancelling,backendOwned,backendCall,stopPending,releasePending;
        int failures;
        long retryAt;
        BooleanSupplier validity;
        Entry(Request request, long created, AdmissionLedger.Lease lease) {
            this.request=request; this.created=created; this.lease=lease;
        }
    }
    public NavigationService(ColonyRegistry registry, GlobalWorkBudgets budgets, ChunkDemandManager chunks, Backend backend) {
        this(registry,budgets,chunks,backend,NavigationService::moveGoalCurrent);
    }
    public NavigationService(ColonyRegistry registry,GlobalWorkBudgets budgets,ChunkDemandManager chunks,Backend backend,GoalAuthority goals) {
        this.registry=Objects.requireNonNull(registry); this.budgets=Objects.requireNonNull(budgets);
        this.chunks=Objects.requireNonNull(chunks); this.backend=Objects.requireNonNull(backend); this.goals=Objects.requireNonNull(goals);
    }
    public UUID request(UUID workId, UUID colonyId, UUID citizenId, long epoch, long goalRevision,
            WorldPosition target, Lane lane, int priority) {
        registry.requireOwner(); Entry existing=requests.get(workId);
        if (existing!=null) {
            Request current=existing.request;
            if (current.colonyId().equals(colonyId) && current.citizenId().equals(citizenId) && current.epoch()==epoch
                    && current.goalRevision()==goalRevision && current.target().equals(target) && current.lane()==lane && current.priority()==priority) {
                if(authoritative(current)&&requests.get(workId)==existing)return current.id();
                if(requests.get(workId)==existing)cancel(workId);
                return null;
            }
        }
        return request(new Request(UUID.randomUUID(),workId,colonyId,citizenId,epoch,goalRevision,target,lane,priority));
    }
    public UUID request(Request request) {
        registry.requireOwner();
        Entry old=requests.get(request.workId());
        if (!authoritative(request)) { if (old!=null && requests.get(request.workId())==old) cancel(request.workId()); return null; }
        if(requests.get(request.workId())!=old)return null;
        if(old!=null&&sameGoal(old.request,request))return old.request.id();
        WorldPosition start=backend.position(request);
        if(!authoritative(request)||requests.get(request.workId())!=old)return null;
        if(start==null)start=registry.citizen(request.citizenId()).lastKnownPosition();
        List<ChunkKey> region=region(start,request.target());
        if(old!=null)cancel(request.workId());
        if(!authoritative(request)||requests.containsKey(request.workId()))return null;
        AdmissionLedger.Lease lease=registry.admission().reserve(request.workId(),request.lane(),Map.of(Resource.CACHE_ENTRIES,1,Resource.CACHE_ENTRIES_PER_OWNER,1));
        Entry entry=new Entry(request,now,lease);entry.validity=() -> valid(entry)&&authoritative(request)
                &&chunks.admitted(request.workId())&&chunks.ready(request.workId())&&valid(entry)&&authoritative(request);
        requests.put(request.workId(),entry);
        if(head==null){head=entry;entry.next=entry;entry.previous=entry;}
        else{entry.previous=head.previous;entry.next=head;head.previous.next=entry;head.previous=entry;}
        int lane=request.lane().ordinal();Entry laneHead=laneHeads[lane];
        if(laneHead==null){laneHeads[lane]=entry;laneCursors[lane]=entry;entry.laneNext=entry;entry.lanePrevious=entry;}
        else{entry.lanePrevious=laneHead.lanePrevious;entry.laneNext=laneHead;laneHead.lanePrevious.laneNext=entry;laneHead.lanePrevious=entry;}
        try{admitDomain(entry,region);}
        catch(RuntimeException|Error failure) {
            try {if(current(entry))cancel(request.workId());}
            catch(RuntimeException|Error cleanup) {failure.addSuppressed(cleanup);}
            throw failure;
        }
        if(!owned(entry))return null;
        if(region.isEmpty())entry.reason=Reason.WORKING_SET_LIMIT;
        return request.id();
    }
    private static boolean sameGoal(Request a,Request b) {
        return a.workId().equals(b.workId()) && a.colonyId().equals(b.colonyId()) && a.citizenId().equals(b.citizenId())
                && a.epoch()==b.epoch() && a.goalRevision()==b.goalRevision() && a.target().equals(b.target())
                && a.lane()==b.lane() && a.priority()==b.priority();
    }
    private static List<ChunkKey> region(WorldPosition start,WorldPosition target) {
        long dx=(long)start.x()-target.x(), dy=(long)start.y()-target.y(), dz=(long)start.z()-target.z();
        if (!start.dimension().equals(target.dimension()) || Math.abs(dx)>64 || Math.abs(dy)>64 || Math.abs(dz)>64
                || dx*dx+dy*dy+dz*dz>4096) return List.of();
        int minX=(Math.min(start.x(),target.x())-2)>>4, maxX=(Math.max(start.x(),target.x())+2)>>4;
        int minZ=(Math.min(start.z(),target.z())-2)>>4, maxZ=(Math.max(start.z(),target.z())+2)>>4;
        if ((long)(maxX-minX+1)*(maxZ-minZ+1)>81) return List.of();
        ArrayList<ChunkKey> keys=new ArrayList<>((maxX-minX+1)*(maxZ-minZ+1));
        for (int x=minX;x<=maxX;x++) for (int z=minZ;z<=maxZ;z++) keys.add(new ChunkKey(target.dimension(),x,z));
        return List.copyOf(keys);
    }
    private boolean rebuildDisplacedDomain(Entry entry) {
        WorldPosition position;
        Throwable failure=null;entry.backendCall=true;
        try {position=backend.position(entry.request);}
        catch(RuntimeException|Error thrown) {failure=thrown;throw thrown;}
        finally {finishBackendCall(entry,failure);}
        if(!owned(entry))return true;
        if(position==null)return false;
        WorldPosition target=entry.request.target();
        long dx=(long)position.x()-target.x(),dy=(long)position.y()-target.y(),dz=(long)position.z()-target.z();
        int minX=(Math.min(position.x(),target.x())-2)>>4,maxX=(Math.max(position.x(),target.x())+2)>>4;
        int minZ=(Math.min(position.z(),target.z())-2)>>4,maxZ=(Math.max(position.z(),target.z())+2)>>4;
        boolean bounded=position.dimension().equals(target.dimension()) && Math.abs(dx)<=64 && Math.abs(dy)<=64
                && Math.abs(dz)<=64 && dx*dx+dy*dy+dz*dz<=4096 && (long)(maxX-minX+1)*(maxZ-minZ+1)<=81;
        if (bounded ? !entry.region.isEmpty() && entry.region.getFirst().x()==minX && entry.region.getFirst().z()==minZ
                && entry.region.getLast().x()==maxX && entry.region.getLast().z()==maxZ : entry.region.isEmpty()) return false;
        // A ready charged envelope still covers an inward displacement; replacing it would
        // discard a valid grant and force the same continuation through admission again.
        if(bounded&&!entry.region.isEmpty()&&!entry.domainReleased&&chunks.admitted(entry.request.workId())&&chunks.ready(entry.request.workId())
                &&entry.region.getFirst().x()<=minX&&entry.region.getFirst().z()<=minZ
                &&entry.region.getLast().x()>=maxX&&entry.region.getLast().z()>=maxZ)return false;
        stop(entry);
        if(!owned(entry))return true;
        releaseDomain(entry);
        if(!owned(entry))return true;
        try { admitDomain(entry,region(position,target)); }
        catch (AdmissionLedger.AdmissionException denied) {
            if(current(entry))entry.reason=denied.reason()==AdmissionLedger.Reason.CRITICAL_CAPACITY?Reason.CRITICAL_CAPACITY:Reason.STATE_LIMIT;
        }
        return true;
    }
    private void admitDomain(Entry entry,List<ChunkKey> region) {
        if (region.isEmpty()) { entry.reason=Reason.WORKING_SET_LIMIT; return; }
        entry.domainLease=registry.admission().reserve(entry.request.workId(),entry.request.lane(),
                Map.of(Resource.CACHE_ENTRIES,region.size()*2,Resource.CACHE_ENTRIES_PER_OWNER,region.size()*2));
        entry.region=region;entry.buckets=new Bucket[region.size()];entry.revisions=new long[region.size()];
        for (int i=0;i<region.size();i++) {
            Bucket bucket=index.computeIfAbsent(region.get(i), ignored -> new Bucket());
            bucket.references++;entry.buckets[i]=bucket;entry.revisions[i]=bucket.revision;
        }
        entry.domainReleased=true;
        try {
            chunks.request(entry.request.workId(),entry.request.colonyId(),region,ChunkDemandManager.Readiness.ENTITY_TICKING,
                    entry.request.lane(),entry.request.priority(),true);
            if(!owned(entry))return;
            entry.domainReleased=false;entry.reason=Reason.CHUNK_NOT_READY;
        } catch (AdmissionLedger.AdmissionException denied) {
            if(current(entry))entry.reason=denied.reason()==AdmissionLedger.Reason.CRITICAL_CAPACITY?Reason.CRITICAL_CAPACITY:Reason.STATE_LIMIT;
            else return;
        }
    }
    private void releaseDomain(Entry entry) {
        List<ChunkKey> region=entry.region;Bucket[] buckets=entry.buckets;AdmissionLedger.Lease lease=entry.domainLease;
        entry.domainReleased=true;entry.region=List.of();entry.buckets=new Bucket[0];entry.revisions=new long[0];entry.domainLease=null;
        boolean releasedCurrent=requests.get(entry.request.workId())==entry;
        try {if(releasedCurrent)chunks.release(entry.request.workId());}
        finally {
            for(int i=0;i<region.size();i++)if(--buckets[i].references==0)index.remove(region.get(i),buckets[i]);
            if(lease!=null)lease.close();
        }
    }
    private boolean authoritative(Request request) {
        CitizenRecord citizen=registry.findCitizen(request.citizenId()).orElse(null);
        if (citizen==null || !citizen.colonyId().equals(request.colonyId()) || citizen.bindingEpoch()!=request.epoch()
                || citizen.lifecycle()!=CitizenRecord.Lifecycle.ALIVE || citizen.admission()!=CitizenRecord.Admission.ACTIVE
                || citizen.readiness()!=CitizenRecord.Readiness.READY || !request.workId().equals(citizen.assignedWorkId())
                || !registry.colony(request.colonyId()).available()
                || !registry.bindings().activeEntity(citizen.citizenId()).filter(citizen.entityId()::equals).isPresent()) return false;
        WorkOrder work=registry.workBoard().work(request.workId());
        return !work.terminal() && request.citizenId().equals(work.assignee()) && goals.current(work,request)
                && request.colonyId().equals(work.colonyId());
    }
    public void tick(long tick) {
        registry.requireOwner();
        boolean advanced=now!=tick; now=tick;
        // A yielded portion is eligible only on a later tick; its queue age rotates among peers.
        if (advanced) for (int lane=0;lane<starts.size();lane++) {
            TreeSet<Entry> deferred=deferredStarts.get(lane);
            while (!deferred.isEmpty()) {
                Entry entry=deferred.pollFirst(); entry.deferred=false; starts.get(lane).add(entry);
            }
        }
        int portions=Math.max(1,budgets.limits().budget(Budget.DIRTY_RESCAN_OBJECTS)/4);
        while (portions-->0 && budgets.timeAvailable()) {
            Lane lane=budgets.chooseLane(Budget.DIRTY_RESCAN_OBJECTS,pollable(1),pollable(2),pollable(0));
            if (lane==null || !budgets.tryConsume(Budget.DIRTY_RESCAN_OBJECTS,lane)) break;
            int laneIndex=lane.ordinal(); Entry entry=laneCursors[laneIndex];
            laneCursors[laneIndex]=entry.laneNext; entry.polledTick=now;
            if (poll(entry)) enqueue(entry);
        }
        while (budgets.timeAvailable()) {
            boolean queued=!starts.get(0).isEmpty() || !starts.get(1).isEmpty() || !starts.get(2).isEmpty();
            Lane lane=budgets.chooseLane(Budget.NAVIGATION_STARTS,
                    queued ? !starts.get(1).isEmpty() : pollable(1),
                    queued ? !starts.get(2).isEmpty() : pollable(2),
                    queued ? !starts.get(0).isEmpty() : pollable(0));
            if (lane==null || !budgets.tryConsume(Budget.NAVIGATION_STARTS,lane)) break;
            long unitStart=System.nanoTime();
            try {
                Entry entry;
                if (queued) {
                    entry=starts.get(lane.ordinal()).pollFirst(); entry.queued=false;
                } else {
                    // An idle navigation quantum pays for exactly one discovery or motion poll.
                    // Shared service cursors cannot bury ready requests behind dirty-only discovery.
                    entry=laneCursors[lane.ordinal()]; laneCursors[lane.ordinal()]=entry.laneNext; entry.polledTick=now;
                    if (!poll(entry)) continue;
                }
                search(entry);
            } finally { registry.metrics().record(Timer.NAVIGATION_UNIT,System.nanoTime()-unitStart); }
        }
    }
    /** One paid request continuation; only a ready waiting request proceeds to a search. */
    private boolean poll(Entry entry) {
        if(!current(entry)||entry.state==State.ARRIVED)return false;
        if(!authoritative(entry.request)) {cancel(entry.request.workId());return false;}
        refreshRevisions(entry);
        if(!current(entry))return false;
        if(entry.state==State.WAITING&&rebuildDisplacedDomain(entry))return false;
        if(!current(entry)||entry.region.isEmpty())return false;
        if(entry.domainReleased) {
            if(now<entry.retryAt)return false;
            try {
                chunks.request(entry.request.workId(),entry.request.colonyId(),entry.region,ChunkDemandManager.Readiness.ENTITY_TICKING,
                        entry.request.lane(),entry.request.priority(),true);
                if(!owned(entry))return false;
                entry.domainReleased=false;
            } catch(AdmissionLedger.AdmissionException denied) {
                if(current(entry))entry.reason=denied.reason()==AdmissionLedger.Reason.CRITICAL_CAPACITY?Reason.CRITICAL_CAPACITY:Reason.STATE_LIMIT;
                return false;
            }
        }
        if(!current(entry))return false;
        if(!chunks.admitted(entry.request.workId())||!chunks.ready(entry.request.workId())) {
            stop(entry);if(!current(entry))return false;
            entry.reason=chunks.state(entry.request.workId())==ChunkDemandManager.State.BLOCKED?Reason.WORKING_SET_LIMIT:Reason.CHUNK_NOT_READY;
            return false;
        }
        if(!owned(entry))return false;
        if(entry.state==State.WAITING)return now>=entry.retryAt;
        long pollStart=System.nanoTime();Motion motion;
        Throwable failure=null;entry.backendCall=true;
        try {motion=backend.poll(entry.request);}
        catch(RuntimeException|Error thrown) {failure=thrown;throw thrown;}
        finally {
            registry.metrics().record(Timer.NAVIGATION_POLL,System.nanoTime()-pollStart);
            finishBackendCall(entry,failure);
        }
        if(!owned(entry))return false;
        if(!chunks.admitted(entry.request.workId())||!chunks.ready(entry.request.workId())) {
            stop(entry);if(!current(entry))return false;
            entry.reason=chunks.state(entry.request.workId())==ChunkDemandManager.State.BLOCKED?Reason.WORKING_SET_LIMIT:Reason.CHUNK_NOT_READY;
            return false;
        }
        if(!owned(entry))return false;
        if(motion==Motion.ARRIVED) {
            stop(entry);if(!current(entry))return false;
            entry.state=State.ARRIVED;entry.reason=Reason.NONE;
            chunks.release(entry.request.workId());if(!owned(entry))return false;
            entry.domainReleased=true;removePollEntry(entry);
        } else if(motion==Motion.OBSTRUCTED)retry(entry,Reason.RECONCILING);
        else if(motion==Motion.UNAVAILABLE) {
            stop(entry);if(!current(entry))return false;
            entry.reason=Reason.RECONCILING;rebuildDisplacedDomain(entry);
        } else {
            chunks.useful(entry.request.workId());
            if(!owned(entry))return false;
        }
        return false;
    }
    private void search(Entry entry) {
        if(!current(entry)||entry.searchedTick==now)return;
        if(!authoritative(entry.request)){cancel(entry.request.workId());return;}
        if(rebuildDisplacedDomain(entry)||!current(entry))return;
        refreshRevisions(entry);if(!current(entry))return;
        if(!chunks.admitted(entry.request.workId())||!chunks.ready(entry.request.workId())) {
            stop(entry);if(!current(entry))return;
            entry.reason=chunks.state(entry.request.workId())==ChunkDemandManager.State.BLOCKED?Reason.WORKING_SET_LIMIT:Reason.CHUNK_NOT_READY;
            return;
        }
        if(!owned(entry))return;
        SearchResult result;entry.searchedTick=now;entry.polledTick=now;
        long begin=System.nanoTime();searchCount++;
        Throwable failure=null;entry.backendCall=true;entry.backendOwned=true;
        try {result=Objects.requireNonNull(backend.search(entry.request,entry.region));}
        catch(RuntimeException|Error thrown) {failure=thrown;throw thrown;}
        finally {
            lastSearchNanos=System.nanoTime()-begin;maxSearchNanos=Math.max(maxSearchNanos,lastSearchNanos);
            registry.metrics().record(Timer.NAVIGATION_EXTERNAL,lastSearchNanos);
            finishBackendCall(entry,failure);
        }
        if(!owned(entry))return;
        boolean stale=!authoritative(entry.request)||!chunks.admitted(entry.request.workId())
                ||!chunks.ready(entry.request.workId())||!valid(entry);
        if(!owned(entry))return;
        if(stale) {
            staleResults++;
            stop(entry);
            if(!current(entry))return;
            entry.retryAt=now;
            if(!authoritative(entry.request))cancel(entry.request.workId());
            return;
        }
        if(result==SearchOutcome.PENDING||result==SearchOutcome.CAPACITY_WAIT) {
            entry.capacityWaiting=result==SearchOutcome.CAPACITY_WAIT;enqueue(entry);return;
        }
        entry.capacityWaiting=false;
        if(result instanceof SearchOutcome outcome) {
            failedSearches++;
            Reason reason=switch(outcome) {
                case UNREACHABLE -> Reason.UNREACHABLE;
                case EXHAUSTED -> Reason.SEARCH_EXHAUSTED;
                case UNAVAILABLE -> Reason.RECONCILING;
                case WORKING_SET_LIMIT -> Reason.WORKING_SET_LIMIT;
                default -> throw new IllegalStateException("Nonterminal search outcome");
            };
            retry(entry,reason);return;
        }
        Route route=(Route)result;
        if(route.nodeCount()<1||route.nodeCount()+1+entry.region.size()*2>registry.admission().limits().resource(Resource.CACHE_ENTRIES_PER_OWNER)) {
            stop(entry);if(current(entry)){entry.reason=Reason.WORKING_SET_LIMIT;entry.retryAt=Long.MAX_VALUE;}return;
        }
        try {entry.routeLease=registry.admission().reserve(entry.request.workId(),entry.request.lane(),Map.of(Resource.CACHE_ENTRIES,route.nodeCount(),Resource.CACHE_ENTRIES_PER_OWNER,route.nodeCount()));}
        catch(AdmissionLedger.AdmissionException denied) {stop(entry);if(current(entry))entry.reason=Reason.STATE_LIMIT;return;}
        if(!current(entry))return;
        entry.backendCall=true;entry.backendOwned=true;failure=null;
        boolean applied;
        try {applied=backend.apply(entry.request,route,entry.validity);}
        catch(RuntimeException|Error thrown) {failure=thrown;throw thrown;}
        finally {finishBackendCall(entry,failure);}
        if(!owned(entry))return;
        if(!entry.validity.getAsBoolean()) {stop(entry);if(owned(entry))entry.reason=Reason.CHUNK_NOT_READY;return;}
        if(!applied) {retry(entry,Reason.RECONCILING);return;}
        entry.state=State.MOVING;entry.reason=Reason.NONE;
        chunks.setProtection(entry.request.workId(),true,false,entry.request.lane()==Lane.CRITICAL);
        if(owned(entry)) {
            if(!entry.validity.getAsBoolean()) {stop(entry);return;}
            chunks.useful(entry.request.workId());
            owned(entry);
        }
    }
    private boolean pollable(int lane) { return laneCursors[lane]!=null && laneCursors[lane].polledTick!=now; }
    private boolean current(Entry entry) { return requests.get(entry.request.workId())==entry; }
    private boolean owned(Entry entry) {
        if(!current(entry))return false;
        if(!authoritative(entry.request)) {if(current(entry))cancel(entry.request.workId());return false;}
        return current(entry);
    }
    private boolean valid(Entry entry) {
        if (!current(entry)) return false;
        for (int i=0;i<entry.buckets.length;i++) if (entry.revisions[i]!=entry.buckets[i].revision) return false;
        return true;
    }
    private void refreshRevisions(Entry entry) {
        for (int i=0;i<entry.buckets.length;i++) if (entry.revisions[i]!=entry.buckets[i].revision) {
            stop(entry);if(!current(entry))return;
            for (int j=0;j<entry.buckets.length;j++) entry.revisions[j]=entry.buckets[j].revision;
            entry.failures=0; entry.retryAt=now; return;
        }
    }
    private void enqueue(Entry entry) {
        if (entry.queued) return;
        entry.startOrder=++startOrder; entry.deferred=entry.searchedTick==now;
        (entry.deferred ? deferredStarts : starts).get(entry.request.lane().ordinal()).add(entry);
        entry.queued=true; entry.reason=Reason.BUDGET;
    }
    private void stop(Entry entry) {
        if(!current(entry))return;
        boolean wasOwned=entry.backendOwned;
        entry.backendOwned=false;
        entry.state=State.WAITING;
        if(entry.routeLease!=null){entry.routeLease.close();entry.routeLease=null;}
        if(entry.queued){(entry.deferred?deferredStarts:starts).get(entry.request.lane().ordinal()).remove(entry);entry.queued=false;entry.deferred=false;}
        entry.capacityWaiting=false;
        Throwable failure=null;
        try {
            if(wasOwned) {
                if(entry.backendCall)entry.stopPending=true;
                else stopBackend(entry);
            }
        } catch(RuntimeException|Error thrown) {failure=thrown;throw thrown;}
        finally {
            try {
                if(failure!=null) {if(current(entry))cancel(entry.request.workId());}
                else if(owned(entry)) {
                    chunks.setProtection(entry.request.workId(),false,false,entry.request.lane()==Lane.CRITICAL);
                    owned(entry);
                }
            }
            catch(RuntimeException|Error cleanup) {if(failure!=null)failure.addSuppressed(cleanup);else throw cleanup;}
        }
    }
    private void stopBackend(Entry entry) {
        backend.stop(entry.request);
        owned(entry);
    }
    private void finishBackendCall(Entry entry,Throwable primary) {
        Throwable failure=primary;
        if(primary!=null&&current(entry)) {
            try {cancel(entry.request.workId());}
            catch(RuntimeException|Error cleanup) {failure=appendFailure(failure,cleanup);}
        }
        entry.backendCall=false;
        try {
            if(entry.stopPending) {entry.stopPending=false;entry.backendOwned=false;backend.stop(entry.request);}
        } catch(RuntimeException|Error cleanup) {failure=appendFailure(failure,cleanup);}
        finally {
            if(entry.releasePending) {
                entry.releasePending=false;
                try {if(!requests.containsKey(entry.request.workId()))chunks.release(entry.request.workId());}
                catch(RuntimeException|Error cleanup) {failure=appendFailure(failure,cleanup);}
            }
        }
        if(primary==null&&failure!=null) {
            try {if(current(entry))cancel(entry.request.workId());}
            catch(RuntimeException|Error cleanup) {failure=appendFailure(failure,cleanup);}
            throwFailure(failure);
        }
    }
    private static Throwable appendFailure(Throwable first,Throwable next) {
        if(first==null)return next;
        if(first!=next)first.addSuppressed(next);
        return first;
    }
    private static void throwFailure(Throwable failure) {
        if(failure instanceof RuntimeException runtime)throw runtime;
        throw (Error)failure;
    }
    private void retry(Entry entry,Reason reason) {
        stop(entry);if(!current(entry))return;entry.reason=reason;
        // An unsuccessful search owns no movement. Exhaustion is not a negative route proof;
        // retain its distinct reason while releasing the idle domain for bounded backoff.
        chunks.release(entry.request.workId());if(!owned(entry))return;entry.domainReleased=true;
        entry.retryAt=now+RETRY[Math.min(entry.failures,RETRY.length-1)];
        if (entry.failures<RETRY.length-1) entry.failures++;
    }
    public void cancel(UUID workId) {
        registry.requireOwner(); Entry entry=requests.get(workId); if(entry==null)return;
        requests.remove(workId,entry);entry.cancelling=true;
        removePollEntry(entry);
        if(entry.next==entry)head=null;
        else{entry.previous.next=entry.next;entry.next.previous=entry.previous;if(head==entry)head=entry.next;}
        if(entry.queued){(entry.deferred?deferredStarts:starts).get(entry.request.lane().ordinal()).remove(entry);entry.queued=false;entry.deferred=false;}
        AdmissionLedger.Lease routeLease=entry.routeLease;entry.routeLease=null;
        if(routeLease!=null)routeLease.close();
        AdmissionLedger.Lease domainLease=entry.domainLease;entry.domainLease=null;
        List<ChunkKey> region=entry.region;Bucket[] buckets=entry.buckets;
        entry.region=List.of();entry.buckets=new Bucket[0];entry.revisions=new long[0];entry.domainReleased=true;
        boolean backendOwned=entry.backendOwned;entry.backendOwned=false;
        Throwable failure=null;
        try {
            if(entry.backendCall) {entry.stopPending=backendOwned||entry.stopPending;entry.releasePending=true;}
            else {
                try {if(backendOwned)backend.stop(entry.request);}
                catch(RuntimeException|Error thrown) {failure=thrown;}
                try {if(!requests.containsKey(workId))chunks.release(workId);}
                catch(RuntimeException|Error cleanup) {failure=appendFailure(failure,cleanup);}
            }
        } finally {
            for(int i=0;i<region.size();i++)if(--buckets[i].references==0)index.remove(region.get(i),buckets[i]);
            if(domainLease!=null)domainLease.close();
            entry.lease.close();
        }
        if(failure!=null)throwFailure(failure);
    }
    private void removePollEntry(Entry entry) {
        if (entry.laneNext==null) return;
        int lane=entry.request.lane().ordinal();
        if (entry.laneNext==entry) { laneHeads[lane]=null; laneCursors[lane]=null; }
        else {
            entry.lanePrevious.laneNext=entry.laneNext; entry.laneNext.lanePrevious=entry.lanePrevious;
            if (laneHeads[lane]==entry) laneHeads[lane]=entry.laneNext;
            if (laneCursors[lane]==entry) laneCursors[lane]=entry.laneNext;
        }
        entry.lanePrevious=null; entry.laneNext=null;
    }
    /** O(1) revision invalidation; each affected request observes it in its bounded polling portion. */
    public void invalidate(ChunkKey key) { registry.requireOwner(); Bucket bucket=index.get(key); if (bucket!=null) bucket.revision=Math.incrementExact(bucket.revision); }
    public State state(UUID workId) { registry.requireOwner(); Entry entry=requests.get(workId); return entry==null ? State.CANCELLED : entry.state; }
    public Reason reason(UUID workId) { registry.requireOwner(); Entry entry=requests.get(workId); return entry==null ? Reason.NONE : entry.reason; }
    public boolean atTarget(UUID workId) { return state(workId)==State.ARRIVED; }
    public long lastSearchNanos() { return lastSearchNanos; }
    public long maxSearchNanos() { return maxSearchNanos; }
    public Map<String,Object> diagnostics() {
        registry.requireOwner();
        return Map.of("searchCount",searchCount,"staleResults",staleResults,"failedSearches",failedSearches,
                "lastSearchNanos",lastSearchNanos,"maxSearchNanos",maxSearchNanos,"requests",requests.size(),
                "queuedStarts",starts.get(0).size()+starts.get(1).size()+starts.get(2).size()
                        +deferredStarts.get(0).size()+deferredStarts.get(1).size()+deferredStarts.get(2).size());
    }
    @Override public void close() { registry.requireOwner(); while (head!=null) cancel(head.request.workId()); }
}
