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
        AdmissionLedger.Lease routeLease;
        final List<ChunkKey> region;
        final Bucket[] buckets;
        final long[] revisions;
        Entry previous, next, lanePrevious, laneNext;
        long polledTick=Long.MIN_VALUE;
        long searchedTick=Long.MIN_VALUE, startOrder;
        State state = State.WAITING;
        Reason reason = Reason.CHUNK_NOT_READY;
        boolean queued, deferred, capacityWaiting, domainReleased;
        int failures;
        long retryAt;
        BooleanSupplier validity;
        Entry(Request request, long created, AdmissionLedger.Lease lease, List<ChunkKey> region) {
            this.request=request; this.created=created; this.lease=lease; this.region=region;
            buckets=new Bucket[region.size()]; revisions=new long[region.size()];
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
                if (authoritative(current)) return current.id();
                cancel(workId); return null;
            }
        }
        return request(new Request(UUID.randomUUID(),workId,colonyId,citizenId,epoch,goalRevision,target,lane,priority));
    }
    public UUID request(Request request) {
        registry.requireOwner();
        Entry old=requests.get(request.workId());
        if (!authoritative(request)) { if (old!=null) cancel(request.workId()); return null; }
        if (old!=null && sameGoal(old.request,request)) return old.request.id();
        WorldPosition start=backend.position(request);
        if (start==null) start=registry.citizen(request.citizenId()).lastKnownPosition();
        List<ChunkKey> region=region(start,request.target());
        if (old!=null) cancel(request.workId());
        AdmissionLedger.Lease lease=registry.admission().reserve(request.workId(),request.lane(),
                Map.of(Resource.CACHE_ENTRIES,1+region.size()*2,Resource.CACHE_ENTRIES_PER_OWNER,1+region.size()*2));
        try {
            if (!region.isEmpty()) chunks.request(request.workId(),request.colonyId(),region,ChunkDemandManager.Readiness.ENTITY_TICKING,
                    request.lane(),request.priority(),true);
        } catch (RuntimeException failure) { lease.close(); chunks.release(request.workId()); throw failure; }
        Entry entry=new Entry(request,now,lease,region);
        entry.validity=() -> valid(entry);
        for (int i=0;i<region.size();i++) {
            Bucket bucket=index.computeIfAbsent(region.get(i), ignored -> new Bucket());
            bucket.references++; entry.buckets[i]=bucket; entry.revisions[i]=bucket.revision;
        }
        requests.put(request.workId(),entry);
        if (head==null) { head=entry; entry.next=entry; entry.previous=entry; }
        else { entry.previous=head.previous; entry.next=head; head.previous.next=entry; head.previous=entry; }
        int lane=request.lane().ordinal(); Entry laneHead=laneHeads[lane];
        if (laneHead==null) { laneHeads[lane]=entry; laneCursors[lane]=entry; entry.laneNext=entry; entry.lanePrevious=entry; }
        else { entry.lanePrevious=laneHead.lanePrevious; entry.laneNext=laneHead; laneHead.lanePrevious.laneNext=entry; laneHead.lanePrevious=entry; }
        if (region.isEmpty()) entry.reason=Reason.WORKING_SET_LIMIT;
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
        WorldPosition position=backend.position(entry.request);
        if (position==null) return false;
        WorldPosition target=entry.request.target();
        long dx=(long)position.x()-target.x(),dy=(long)position.y()-target.y(),dz=(long)position.z()-target.z();
        int minX=(Math.min(position.x(),target.x())-2)>>4,maxX=(Math.max(position.x(),target.x())+2)>>4;
        int minZ=(Math.min(position.z(),target.z())-2)>>4,maxZ=(Math.max(position.z(),target.z())+2)>>4;
        boolean bounded=position.dimension().equals(target.dimension()) && Math.abs(dx)<=64 && Math.abs(dy)<=64
                && Math.abs(dz)<=64 && dx*dx+dy*dy+dz*dz<=4096 && (long)(maxX-minX+1)*(maxZ-minZ+1)<=81;
        if (bounded ? !entry.region.isEmpty() && entry.region.getFirst().x()==minX && entry.region.getFirst().z()==minZ
                && entry.region.getLast().x()==maxX && entry.region.getLast().z()==maxZ : entry.region.isEmpty()) return false;
        Request request=entry.request;
        cancel(request.workId());request(request);
        return true;
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
        if (entry.state==State.ARRIVED) return false;
        if (!authoritative(entry.request)) { cancel(entry.request.workId()); return false; }
        refreshRevisions(entry);
        // Waiting/backoff owns no motion; displacement invalidates its old search domain too.
        if (entry.state==State.WAITING && rebuildDisplacedDomain(entry)) return false;
        if (entry.region.isEmpty()) return false;
        if(entry.domainReleased) {
            if(now<entry.retryAt)return false;
            try {
                chunks.request(entry.request.workId(),entry.request.colonyId(),entry.region,ChunkDemandManager.Readiness.ENTITY_TICKING,
                        entry.request.lane(),entry.request.priority(),true);
                entry.domainReleased=false;
            } catch(AdmissionLedger.AdmissionException denied) {
                entry.reason=denied.reason()==AdmissionLedger.Reason.CRITICAL_CAPACITY?Reason.CRITICAL_CAPACITY:Reason.STATE_LIMIT;return false;
            }
        }
        if (!chunks.admitted(entry.request.workId()) || !chunks.ready(entry.request.workId())) {
            stop(entry);
            entry.reason=chunks.state(entry.request.workId())==ChunkDemandManager.State.BLOCKED
                    ? Reason.WORKING_SET_LIMIT : Reason.CHUNK_NOT_READY; return false;
        }
        if (entry.state!=State.MOVING) return now>=entry.retryAt && entry.searchedTick!=now;
        long pollStart=System.nanoTime();
        Motion motion;
        try { motion=backend.poll(entry.request); }
        finally { registry.metrics().record(Timer.NAVIGATION_POLL,System.nanoTime()-pollStart); }
        if (motion==Motion.ARRIVED) {
            stop(entry); entry.state=State.ARRIVED; entry.reason=Reason.NONE; chunks.release(entry.request.workId());
            removePollEntry(entry);
        } else if (motion==Motion.OBSTRUCTED) retry(entry,Reason.RECONCILING);
        else if (motion==Motion.UNAVAILABLE) {
            stop(entry); entry.reason=Reason.RECONCILING;
            rebuildDisplacedDomain(entry);
        }
        else chunks.useful(entry.request.workId());
        return false;
    }
    private void search(Entry entry) {
        if (!current(entry) || entry.searchedTick==now) return;
        if (!authoritative(entry.request)) { cancel(entry.request.workId()); return; }
        if (rebuildDisplacedDomain(entry)) return;
        refreshRevisions(entry);
        if (!chunks.admitted(entry.request.workId()) || !chunks.ready(entry.request.workId())) {
            stop(entry);
            entry.reason=chunks.state(entry.request.workId())==ChunkDemandManager.State.BLOCKED
                    ? Reason.WORKING_SET_LIMIT : Reason.CHUNK_NOT_READY; return;
        }
        SearchResult result;
        entry.searchedTick=now;
        entry.polledTick=now;
        long begin=System.nanoTime();
        searchCount++;
        try { result=Objects.requireNonNull(backend.search(entry.request,entry.region)); }
        finally {
            lastSearchNanos=System.nanoTime()-begin; maxSearchNanos=Math.max(maxSearchNanos,lastSearchNanos);
            registry.metrics().record(Timer.NAVIGATION_EXTERNAL,lastSearchNanos);
        }
        boolean stale=!current(entry) || !authoritative(entry.request) || !chunks.admitted(entry.request.workId())
                || !chunks.ready(entry.request.workId()) || !valid(entry);
        if (stale) {
            staleResults++;
            if (current(entry)) {
                stop(entry); entry.retryAt=now;
                if (!authoritative(entry.request)) cancel(entry.request.workId());
            }
            return;
        }
        if (result==SearchOutcome.PENDING || result==SearchOutcome.CAPACITY_WAIT) {
            entry.capacityWaiting=result==SearchOutcome.CAPACITY_WAIT; enqueue(entry); return;
        }
        entry.capacityWaiting=false;
        if (result instanceof SearchOutcome outcome) {
            failedSearches++;
            Reason reason=switch (outcome) {
                case UNREACHABLE -> Reason.UNREACHABLE;
                case EXHAUSTED -> Reason.SEARCH_EXHAUSTED;
                case UNAVAILABLE -> Reason.RECONCILING;
                case WORKING_SET_LIMIT -> Reason.WORKING_SET_LIMIT;
                default -> throw new IllegalStateException("Nonterminal search outcome");
            };
            retry(entry,reason); return;
        }
        Route route=(Route)result;
        if (route.nodeCount()<1 || route.nodeCount()+1+entry.region.size()*2>registry.admission().limits().resource(Resource.CACHE_ENTRIES_PER_OWNER)) {
            stop(entry); entry.reason=Reason.WORKING_SET_LIMIT; entry.retryAt=Long.MAX_VALUE; return;
        }
        try { entry.routeLease=registry.admission().reserve(entry.request.workId(),entry.request.lane(),Map.of(Resource.CACHE_ENTRIES,route.nodeCount(),Resource.CACHE_ENTRIES_PER_OWNER,route.nodeCount())); }
        catch (AdmissionLedger.AdmissionException denied) { stop(entry); entry.reason=Reason.STATE_LIMIT; return; }
        if (!backend.apply(entry.request,route,entry.validity)) { retry(entry,Reason.RECONCILING); return; }
        entry.state=State.MOVING; entry.reason=Reason.NONE;
        chunks.setProtection(entry.request.workId(),true,false,entry.request.lane()==Lane.CRITICAL);
        chunks.useful(entry.request.workId());
    }
    private boolean pollable(int lane) { return laneCursors[lane]!=null && laneCursors[lane].polledTick!=now; }
    private boolean current(Entry entry) { return requests.get(entry.request.workId())==entry; }
    private boolean valid(Entry entry) {
        if (!current(entry)) return false;
        for (int i=0;i<entry.buckets.length;i++) if (entry.revisions[i]!=entry.buckets[i].revision) return false;
        return true;
    }
    private void refreshRevisions(Entry entry) {
        for (int i=0;i<entry.buckets.length;i++) if (entry.revisions[i]!=entry.buckets[i].revision) {
            stop(entry);
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
        backend.stop(entry.request);
        if (entry.routeLease!=null) { entry.routeLease.close(); entry.routeLease=null; }
        if (entry.queued) {
            (entry.deferred ? deferredStarts : starts).get(entry.request.lane().ordinal()).remove(entry);
            entry.queued=false; entry.deferred=false;
        }
        entry.capacityWaiting=false;
        entry.state=State.WAITING;
        chunks.setProtection(entry.request.workId(),false,false,entry.request.lane()==Lane.CRITICAL);
    }
    private void retry(Entry entry,Reason reason) {
        stop(entry); entry.reason=reason;
        // An unsuccessful search owns no movement. Exhaustion is not a negative route proof;
        // retain its distinct reason while releasing the idle domain for bounded backoff.
        chunks.release(entry.request.workId());entry.domainReleased=true;
        entry.retryAt=now+RETRY[Math.min(entry.failures,RETRY.length-1)];
        if (entry.failures<RETRY.length-1) entry.failures++;
    }
    public void cancel(UUID workId) {
        registry.requireOwner(); Entry entry=requests.remove(workId); if (entry==null) return;
        stop(entry); chunks.release(workId); entry.lease.close();
        for (int i=0;i<entry.region.size();i++) if (--entry.buckets[i].references==0) index.remove(entry.region.get(i));
        if (entry.next==entry) head=null;
        else { entry.previous.next=entry.next; entry.next.previous=entry.previous; if (head==entry) head=entry.next; }
        removePollEntry(entry);
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
