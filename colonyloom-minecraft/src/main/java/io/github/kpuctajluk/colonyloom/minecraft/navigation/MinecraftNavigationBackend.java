package io.github.kpuctajluk.colonyloom.minecraft.navigation;

import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.navigation.NavigationService;
import io.github.kpuctajluk.colonyloom.core.navigation.NavigationService.Motion;
import io.github.kpuctajluk.colonyloom.core.navigation.NavigationService.Request;
import io.github.kpuctajluk.colonyloom.core.navigation.NavigationService.Route;
import io.github.kpuctajluk.colonyloom.core.navigation.NavigationService.SearchResult;
import io.github.kpuctajluk.colonyloom.core.navigation.NavigationService.SearchOutcome;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Server-thread, portioned ground A*; search reads only admitted, entity-ticking chunks. */
public final class MinecraftNavigationBackend implements NavigationService.Backend {
    private final MinecraftServer server;
    private final ColonyRegistry registry;
    private final ChunkDemandManager chunks;
    private final NavigationService.GoalAuthority goals;
    private final Map<UUID, Moving> moving=new HashMap<>();
    private final Query[] queries=new Query[BoundedGroundSearch.MAX_QUERIES];
    private final Query pathValidation=new Query(false);
    private int concurrentQueries, queryHighWater, nodeHighWater, openHighWater, portionHighWater;
    private long poolRejections, totalExpansions, searchPortions, completedQueries, exhaustedQueries, cancelledQueries, nodeLimitQueries;
    private static final class Moving {
        final Request request;
        final CitizenEntity entity;
        final Guard guard;
        final GroundPathNavigation navigation;
        final BooleanSupplier validity;
        Motion stopped;
        Vec3 lastPosition;
        int stalled;
        Moving(Request request,CitizenEntity entity,Guard guard,GroundPathNavigation navigation,BooleanSupplier validity) {
            this.request=request; this.entity=entity; this.guard=guard; this.navigation=navigation; this.validity=validity; lastPosition=entity.position();
        }
    }
    private record GroundRoute(UUID requestId,UUID entityId,long epoch,long goalRevision,Path path,Guard guard) implements Route {
        @Override public int nodeCount() { return path.getNodeCount(); }
    }
    public MinecraftNavigationBackend(MinecraftServer server,ColonyRegistry registry,ChunkDemandManager chunks) {
        this(server,registry,chunks,NavigationService::moveGoalCurrent);
    }
    public MinecraftNavigationBackend(MinecraftServer server,ColonyRegistry registry,ChunkDemandManager chunks,NavigationService.GoalAuthority goals) {
        this.server=server; this.registry=registry; this.chunks=chunks; this.goals=java.util.Objects.requireNonNull(goals);
    }
    private void owner() { if (!server.isSameThread()) throw new IllegalStateException("Navigation requires the server thread"); }
    private CitizenEntity entity(Request request) {
        owner();
        CitizenRecord record=registry.findCitizen(request.citizenId()).orElse(null);
        if (record==null || record.bindingEpoch()!=request.epoch() || !record.colonyId().equals(request.colonyId())
                || record.lifecycle()!=CitizenRecord.Lifecycle.ALIVE || record.admission()!=CitizenRecord.Admission.ACTIVE
                || record.readiness()!=CitizenRecord.Readiness.READY || !request.workId().equals(record.assignedWorkId())
                || !registry.colony(request.colonyId()).available()
                || !registry.bindings().activeEntity(record.citizenId()).filter(record.entityId()::equals).isPresent()) return null;
        WorkOrder work=registry.workBoard().work(request.workId());
        if (work.terminal() || !request.citizenId().equals(work.assignee()) || !goals.current(work,request)) return null;
        ServerLevel level=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(request.target().dimension())));
        if (level==null) return null;
        Entity physical=level.getEntity(record.entityId());
        return physical instanceof CitizenEntity citizen && citizen.isAlive() && !citizen.isRemoved() && !citizen.isQuarantined()
                && request.citizenId().equals(citizen.citizenId()) && citizen.bindingEpoch()==request.epoch()
                && !citizen.isPassenger() ? citizen : null;
    }
    @Override public WorldPosition position(Request request) {
        CitizenEntity entity=entity(request);
        return entity==null ? null : new WorldPosition(request.target().dimension(),entity.blockPosition().getX(),entity.blockPosition().getY(),entity.blockPosition().getZ());
    }
    @Override public SearchResult search(Request request,List<ChunkKey> admittedRegion) {
        CitizenEntity entity=entity(request);
        Query query=query(request.workId());
        if (query!=null && (!query.request.equals(request) || query.entity!=entity || !query.guard.region.equals(admittedRegion))) {
            release(query); cancelledQueries++; query=null;
        }
        if (entity==null || !chunks.admitted(request.workId()) || !chunks.ready(request.workId()) || admittedRegion.isEmpty() || admittedRegion.size()>81
                || entity.distanceToSqr(request.target().x()+0.5,request.target().y(),request.target().z()+0.5)>4096) {
            if (query!=null) { release(query); cancelledQueries++; }
            return SearchOutcome.UNAVAILABLE;
        }
        try {
            if (query==null) {
                query=acquire();
                if (query==null) { poolRejections++; return SearchOutcome.CAPACITY_WAIT; }
                query.start(request,entity,new Guard(request,(ServerLevel)entity.level(),admittedRegion));
                if (!query.guard.allReady()) {
                    release(query); cancelledQueries++; return SearchOutcome.UNAVAILABLE;
                }
                if (!query.standable(query.startX,query.startY,query.startZ)
                        || !query.standable(request.target().x(),request.target().y(),request.target().z())) {
                    release(query); exhaustedQueries++; return SearchOutcome.UNREACHABLE;
                }
            } else if (!query.guard.allReady() || !query.atStart()) {
                release(query); cancelledQueries++; return SearchOutcome.UNAVAILABLE;
            }
            BoundedGroundSearch.Result result=query.search.advance(query);
            searchPortions++; totalExpansions+=query.search.lastExpansions();
            portionHighWater=Math.max(portionHighWater,query.search.lastExpansions());
            nodeHighWater=Math.max(nodeHighWater,query.search.nodeCount());
            openHighWater=Math.max(openHighWater,query.search.openHighWater());
            if (result==BoundedGroundSearch.Result.PENDING) return SearchOutcome.PENDING;
            if (query.search.nodeLimitHit()) nodeLimitQueries++;
            if (result==BoundedGroundSearch.Result.EXHAUSTED || result==BoundedGroundSearch.Result.UNREACHABLE) {
                release(query); exhaustedQueries++;
                return result==BoundedGroundSearch.Result.EXHAUSTED ? SearchOutcome.EXHAUSTED : SearchOutcome.UNREACHABLE;
            }
            Path path=query.path();
            if (path==null) { release(query); exhaustedQueries++; return SearchOutcome.WORKING_SET_LIMIT; }
            Guard guard=query.guard;
            boolean valid=validPath(path,guard,entity);
            release(query);
            if (!valid) { exhaustedQueries++; return SearchOutcome.UNAVAILABLE; }
            completedQueries++;
            return new GroundRoute(request.id(),entity.getUUID(),request.epoch(),request.goalRevision(),path,guard);
        } catch (UnsafeSearch unavailable) {
            if (query!=null && query.request!=null) { release(query); cancelledQueries++; }
            return SearchOutcome.UNAVAILABLE;
        } catch (RuntimeException | Error failure) {
            if (query!=null && query.request!=null) release(query);
            throw failure;
        }
    }
    private Query query(UUID workId) {
        for (Query query:queries) if (query!=null && query.request!=null && query.request.workId().equals(workId)) return query;
        return null;
    }
    private Query acquire() {
        for (int i=0;i<queries.length;i++) {
            if (queries[i]==null) queries[i]=new Query();
            if (queries[i].request==null) return queries[i];
        }
        return null;
    }
    private void release(Query query) {
        query.request=null; query.entity=null; query.guard=null; query.context=null;
        concurrentQueries--;
    }
    public Map<String,Object> diagnostics() {
        owner(); Map<String,Object> result=new HashMap<>();
        result.put("backend","portioned-ground-a-star");
        result.put("maxExpansionsPerPortion",BoundedGroundSearch.MAX_EXPANSIONS);
        result.put("maxNodesPerQuery",BoundedGroundSearch.MAX_NODES);
        result.put("maxConcurrentQueries",BoundedGroundSearch.MAX_QUERIES);
        result.put("maxPoolNodes",BoundedGroundSearch.MAX_QUERIES*BoundedGroundSearch.MAX_NODES);
        result.put("concurrentQueries",concurrentQueries); result.put("queryHighWater",queryHighWater);
        result.put("poolRejections",poolRejections); result.put("nodeHighWater",nodeHighWater);
        result.put("openHighWater",openHighWater); result.put("portionExpansionHighWater",portionHighWater);
        result.put("searchPortions",searchPortions); result.put("totalExpansions",totalExpansions);
        result.put("completedQueries",completedQueries); result.put("exhaustedQueries",exhaustedQueries);
        result.put("cancelledQueries",cancelledQueries); result.put("nodeLimitQueries",nodeLimitQueries);
        return result;
    }
    private boolean validPath(Path path,Guard guard,CitizenEntity entity) {
        if (!guard.allReady()) return false;
        Query terrain=pathValidation;
        terrain.entity=entity; terrain.guard=guard; terrain.context=CollisionContext.of(entity);
        try {
            for (int i=0;i<path.getNodeCount();i++) {
                Node node=path.getNode(i);
                if (!terrain.standable(node.x,node.y,node.z)) return false;
                if (i>0) {
                    Node previous=path.getNode(i-1);
                    if (Math.abs(previous.x-node.x)+Math.abs(previous.z-node.z)!=1 || Math.abs(previous.y-node.y)>1
                            || !terrain.transition(previous.x,previous.y,previous.z,node.x,node.y,node.z)) return false;
                }
            }
            return true;
        } catch (UnsafeSearch unavailable) { return false; }
        finally { terrain.entity=null; terrain.guard=null; terrain.context=null; }
    }
    @Override public boolean apply(Request request,Route route,BooleanSupplier stillCurrent) {
        CitizenEntity entity=entity(request);
        if (!(route instanceof GroundRoute ground) || entity==null || !request.id().equals(ground.requestId())
                || !entity.getUUID().equals(ground.entityId()) || request.epoch()!=ground.epoch()
                || request.goalRevision()!=ground.goalRevision() || !stillCurrent.getAsBoolean() || !ground.guard().allReady()
                || !validPath(ground.path(),ground.guard(),entity)) return false;
        Node start=ground.path().getNode(0);
        if (entity.blockPosition().getX()!=start.x || entity.blockPosition().getY()!=start.y || entity.blockPosition().getZ()!=start.z) return false;
        pathValidation.departStart(ground.path(),entity,ground.guard());
        stop(request);
        GroundPathNavigation navigation=new ControlledNavigation(entity,entity.level());
        if (!navigation.moveTo(ground.path(),1.0)) return false;
        Moving current=new Moving(request,entity,ground.guard(),navigation,stillCurrent);
        moving.put(request.workId(),current);
        entity.managedMovementGuard(() -> safetyCheck(current));
        return true;
    }
    @Override public Motion poll(Request request) {
        owner();
        Moving saved=moving.get(request.workId());
        if (saved!=null && saved.request.id().equals(request.id()) && saved.stopped!=null) {
            Motion stopped=saved.stopped; stop(request); return stopped;
        }
        owner(); Moving current=moving.get(request.workId()); CitizenEntity entity=entity(request);
        if (current==null || !current.request.id().equals(request.id()) || current.entity!=entity || !current.guard.allReady()) {
            stop(request); return Motion.UNAVAILABLE;
        }
        if (entity.distanceToSqr(request.target().x()+0.5,request.target().y(),request.target().z()+0.5)<=0.01
                && Math.abs(entity.getY()-request.target().y())<=0.5) { stop(request); return Motion.ARRIVED; }
        Path path=current.navigation.getPath();
        if (path==null || current.navigation.isDone() || current.navigation.isStuck()
                || !current.guard.box(entity.getBoundingBox().inflate(1.0))) { stop(request); return Motion.OBSTRUCTED; }
        BlockPos next=path.getNextNodePos();
        if (!current.guard.node(next.getX(),next.getY(),next.getZ(),entity.getBbWidth(),entity.getBbHeight())) { stop(request); return Motion.UNAVAILABLE; }
        AABB body=entity.getBoundingBox().move(next.getX()+0.5-entity.getX(),next.getY()-entity.getY(),next.getZ()+0.5-entity.getZ());
        if (!current.guard.box(body.inflate(1.0))) { stop(request); return Motion.UNAVAILABLE; }
        long collisionStart=System.nanoTime(); boolean collisionFree;
        try { collisionFree=entity.level().noCollision(entity,body); }
        finally { registry.metrics().record(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer.COLLISION,System.nanoTime()-collisionStart); }
        if (!collisionFree || !pathValidation.safeNext(path,entity,current.guard)) { stop(request); return Motion.OBSTRUCTED; }
        Vec3 position=entity.position();
        if (position.distanceToSqr(current.lastPosition)<0.0001) current.stalled++; else current.stalled=0;
        current.lastPosition=position;
        if (current.stalled>=20) { stop(request); return Motion.OBSTRUCTED; }
        return Motion.MOVING;
    }
    private void safetyCheck(Moving current) {
        if (moving.get(current.request.workId())!=current || current.stopped!=null) return;
        CitizenEntity entity=current.entity;
        if (!current.validity.getAsBoolean() || entity(current.request)!=entity
                || !current.guard.box(entity.getBoundingBox().expandTowards(entity.getDeltaMovement()).inflate(1.0))) {
            halt(current,Motion.UNAVAILABLE); return;
        }
        if(entity.distanceToSqr(current.request.target().x()+0.5,current.request.target().y(),current.request.target().z()+0.5)<=0.01) {
            halt(current,Motion.ARRIVED);return;
        }
        Path path=current.navigation.getPath();
        if (path==null || path.isDone()) {
            if (entity.distanceToSqr(current.request.target().x()+0.5,current.request.target().y(),current.request.target().z()+0.5)>0.01) {
                halt(current,Motion.OBSTRUCTED); return;
            }
            entity.stopInPlace();
            entity.getMoveControl().setWantedPosition(entity.getX(),entity.getY(),entity.getZ(),0);
            entity.getMoveControl().tick(); entity.setZza(0);
            entity.setDeltaMovement(entity.getDeltaMovement().multiply(0,1,0)); return;
        }
        BlockPos next=path.getNextNodePos();
        AABB body=entity.getBoundingBox().move(next.getX()+0.5-entity.getX(),next.getY()-entity.getY(),next.getZ()+0.5-entity.getZ());
        if (!current.guard.node(next.getX(),next.getY(),next.getZ(),entity.getBbWidth(),entity.getBbHeight()) || !current.guard.box(body.inflate(1))) {
            halt(current,Motion.UNAVAILABLE); return;
        }
        if (!entity.level().noCollision(entity,body) || !pathValidation.safeNext(path,entity,current.guard)) { halt(current,Motion.OBSTRUCTED); return; }
        // Native locomotion advances with the physical entity, not with scarce dirty/status polling.
        // Search portions and request/status reconciliation remain globally budgeted.
        current.navigation.tick();
    }
    private void halt(Moving current,Motion reason) {
        current.stopped=reason; current.navigation.stop(); current.entity.managedMovementGuard(null); current.entity.stopInPlace();
        current.entity.getMoveControl().setWantedPosition(current.entity.getX(),current.entity.getY(),current.entity.getZ(),0);
        current.entity.getMoveControl().tick(); current.entity.setZza(0);
        current.entity.setDeltaMovement(current.entity.getDeltaMovement().multiply(0,1,0));
    }
    @Override public void stop(Request request) {
        owner(); Query query=query(request.workId());
        if (query!=null && query.request.id().equals(request.id())) { release(query); cancelledQueries++; }
        Moving current=moving.get(request.workId());
        if (current==null || !current.request.id().equals(request.id())) return;
        moving.remove(request.workId()); halt(current,Motion.UNAVAILABLE);
    }
    /** The default navigator stays idle; this controller advances only inside the guarded physical entity tick. */
    private static final class ControlledNavigation extends GroundPathNavigation {
        ControlledNavigation(Mob mob,Level level) { super(mob,level); }
        @Override public void recomputePath() { stop(); }
        @Override protected Path createPath(Set<BlockPos> targets,int accuracy,boolean offset,int range,float distance) { return null; }
        @Override protected void trimPath() { /* Search already validates the exact target; do not re-read arbitrary sky columns. */ }
        @Override public boolean canCutCorner(net.minecraft.world.level.pathfinder.PathType type) { return false; }
        @Override protected double getGroundY(Vec3 target) { return target.y; }
        // Airborne block-coordinate advancement can skip a descending corner before its center.
        @Override protected boolean canUpdatePath() { return true; }
        @Override protected void followThePath() {
            Node next=path.getNextNode();
            // Cardinal search validates center-to-center transitions, not early diagonal turns beside containers.
            if (mob.distanceToSqr(next.x+0.5,next.y,next.z+0.5)<=0.01) {
                path.advance();
                if(!path.isDone()) {
                    Node following=path.getNextNode();
                    if(following.x!=next.x && Math.abs(mob.getDeltaMovement().z)>0 || following.z!=next.z && Math.abs(mob.getDeltaMovement().x)>0)
                        mob.setDeltaMovement(mob.getDeltaMovement().multiply(0,1,0));
                }
            }
            doStuckDetection(getTempMobPos());
        }
    }
    private static final class UnsafeSearch extends RuntimeException {
        UnsafeSearch() { super(null,null,false,false); }
    }
    private final class Guard implements BlockGetter {
        final Request request;
        final ServerLevel level;
        final List<ChunkKey> region;
        final LevelChunk[] physicalChunks;
        final int[] chunkTable=new int[256];
        final BlockPos.MutableBlockPos readinessPosition=new BlockPos.MutableBlockPos();
        Guard(Request request,ServerLevel level,List<ChunkKey> region) {
            this.request=request; this.level=level; this.region=List.copyOf(region);
            physicalChunks=new LevelChunk[region.size()];
            for (int i=0;i<region.size();i++) {
                ChunkKey key=region.get(i);
                physicalChunks[i]=level.getChunkSource().getChunkNow(key.x(),key.z());
                int slot=chunkSlot(key.x(),key.z());
                if (chunkTable[slot]!=0 || !key.dimension().equals(request.target().dimension())) throw new UnsafeSearch();
                chunkTable[slot]=i+1;
            }
        }
        int chunkSlot(int x,int z) {
            int slot=(x*73428767 ^ z*912931 ^ (x>>>16) ^ (z>>>16)) & (chunkTable.length-1);
            while (chunkTable[slot]!=0) {
                ChunkKey key=region.get(chunkTable[slot]-1);
                if (key.x()==x && key.z()==z) break;
                slot=(slot+1) & (chunkTable.length-1);
            }
            return slot;
        }
        boolean allowedChunk(int x,int z) {
            int index=chunkTable[chunkSlot(x,z)]-1;
            if (index<0) return false;
            ChunkKey key=region.get(index);
            return chunks.admitted(request.workId()) && chunks.admitted(key)
                    && chunks.ready(key,ChunkDemandManager.Readiness.ENTITY_TICKING) && physicalChunks[index]!=null
                    && level.getChunkSource().getChunkNow(x,z)==physicalChunks[index]
                    && level.isPositionEntityTicking(readinessPosition.set(x<<4,request.target().y(),z<<4));
        }
        boolean allReady() { for (ChunkKey key:region) if (!allowedChunk(key.x(),key.z())) return false;return true; }
        boolean box(AABB box) {
            return box.minY>=level.getMinBuildHeight() && box.maxY<=level.getMaxBuildHeight()
                    && withinBorder(box.minX,box.maxX,box.minZ,box.maxZ) && area(box.minX,box.maxX,box.minZ,box.maxZ);
        }
        boolean area(double fromX,double toX,double fromZ,double toZ) {
            int minX=Mth.floor(fromX)>>4,maxX=Mth.floor(toX)>>4,minZ=Mth.floor(fromZ)>>4,maxZ=Mth.floor(toZ)>>4;
            if ((long)(maxX-minX+1)*(maxZ-minZ+1)>81) return false;
            for (int x=minX;x<=maxX;x++) for (int z=minZ;z<=maxZ;z++) if (!allowedChunk(x,z)) return false;
            return true;
        }
        // Search portions do not yield: allReady validates admission/readiness before native shape reads.
        boolean searchArea(double fromX,double toX,double fromZ,double toZ) {
            int minX=Mth.floor(fromX)>>4,maxX=Mth.floor(toX)>>4,minZ=Mth.floor(fromZ)>>4,maxZ=Mth.floor(toZ)>>4;
            if ((long)(maxX-minX+1)*(maxZ-minZ+1)>81) return false;
            for (int x=minX;x<=maxX;x++) for (int z=minZ;z<=maxZ;z++) if (chunkTable[chunkSlot(x,z)]==0) return false;
            return true;
        }
        boolean withinBorder(double fromX,double toX,double fromZ,double toZ) {
            return fromX>=level.getWorldBorder().getMinX() && toX<=level.getWorldBorder().getMaxX()
                    && fromZ>=level.getWorldBorder().getMinZ() && toZ<=level.getWorldBorder().getMaxZ();
        }
        boolean node(int x,int y,int z,float width,float height) {
            double half=width*0.5;
            return y>=level.getMinBuildHeight()+1 && y+height<=level.getMaxBuildHeight()
                    && withinBorder(x+0.5-half,x+0.5+half,z+0.5-half,z+0.5+half)
                    && area(x+0.5-half-1,x+0.5+half+1,z+0.5-half-1,z+0.5+half+1);
        }
        LevelChunk chunk(BlockPos pos) {
            int index=chunkTable[chunkSlot(pos.getX()>>4,pos.getZ()>>4)]-1;
            if (index<0 || physicalChunks[index]==null) throw new UnsafeSearch();
            return physicalChunks[index];
        }
        @Override public BlockState getBlockState(BlockPos pos) { return chunk(pos).getBlockState(pos); }
        @Override public FluidState getFluidState(BlockPos pos) { return chunk(pos).getFluidState(pos); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return chunk(pos).getBlockEntity(pos); }
        @Override public int getHeight() { return level.getHeight(); }
        @Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
    }
    private final class Query implements BoundedGroundSearch.Terrain {
        // A clear two-step detour is cheaper than pushing through an idle resident, but a crowd
        // cannot disconnect otherwise walkable terrain. Native movement/collision remains authoritative.
        private static final int OCCUPANCY_COST=4;
        final BoundedGroundSearch search;
        final BlockPos.MutableBlockPos position=new BlockPos.MutableBlockPos();
        final Shapes.DoubleLineConsumer collisions=this::collisionBox;
        final ArrayList<Entity> occupants=new ArrayList<>(1);
        final java.util.function.Predicate<Entity> occupied=other -> other!=this.entity && other.isPushable() && !other.isSpectator();
        Request request;
        CitizenEntity entity;
        Guard guard;
        CollisionContext context;
        int startX,startY,startZ,blockX,blockY,blockZ;
        double minX,minY,minZ,maxX,maxY,maxZ;
        boolean intersects;
        Query() { this(true); }
        Query(boolean allocateSearch) { search=allocateSearch ? new BoundedGroundSearch() : null; }
        void start(Request request,CitizenEntity entity,Guard guard) {
            this.request=request; this.entity=entity; this.guard=guard; context=CollisionContext.of(entity);
            BlockPos start=entity.blockPosition(); startX=start.getX(); startY=start.getY(); startZ=start.getZ();
            concurrentQueries++; queryHighWater=Math.max(queryHighWater,concurrentQueries);
            search.begin(startX,startY,startZ,request.target().x(),request.target().y(),request.target().z());
        }
        boolean atStart() {
            BlockPos current=entity.blockPosition();
            return current.getX()==startX && current.getY()==startY && current.getZ()==startZ
                    && Math.abs(entity.getY()-startY)<0.01;
        }
        @Override public boolean standable(int x,int y,int z) {
            double half=entity.getBbWidth()*0.5;
            if (y<guard.level.getMinBuildHeight()+1 || y+entity.getBbHeight()>guard.level.getMaxBuildHeight()
                    || !guard.withinBorder(x+0.5-half,x+0.5+half,z+0.5-half,z+0.5+half)
                    || !guard.searchArea(x+0.5-half-1,x+0.5+half+1,z+0.5-half-1,z+0.5+half+1)) return false;
            position.set(x,y-1,z);
            BlockState support=guard.getBlockState(position);
            if (!support.getFluidState().isEmpty() || support.is(BlockTags.CLIMBABLE)
                    || !support.isFaceSturdy(guard,position,Direction.UP)) return false;
            VoxelShape shape=support.getCollisionShape(guard,position,context);
            if (shape.isEmpty() || Math.abs(shape.max(Direction.Axis.Y)-1.0)>1.0E-7 || !Block.isFaceFull(shape,Direction.UP)) return false;
            return clear(x+0.5-half,y,z+0.5-half,x+0.5+half,y+entity.getBbHeight(),z+0.5+half);
        }
        @Override public int additionalCost(int x,int y,int z) {
            if(request==null || x==request.target().x() && y==request.target().y() && z==request.target().z()) return 0;
            double half=entity.getBbWidth()*0.5;
            AABB body=new AABB(x+0.5-half,y,z+0.5-half,x+0.5+half,y+entity.getBbHeight(),z+0.5+half);
            occupants.clear();
            guard.level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(Entity.class),body,occupied,occupants,1);
            boolean blocked=!occupants.isEmpty(); occupants.clear();
            return blocked ? OCCUPANCY_COST : 0;
        }
        @Override public boolean transition(int fromX,int fromY,int fromZ,int x,int y,int z) {
            double half=entity.getBbWidth()*0.5, height=entity.getBbHeight();
            int horizontalY=Math.max(fromY,y);
            if (y>fromY && !clear(fromX+0.5-half,fromY,fromZ+0.5-half,fromX+0.5+half,y+height,fromZ+0.5+half)) return false;
            if (!clear(Math.min(fromX,x)+0.5-half,horizontalY,Math.min(fromZ,z)+0.5-half,
                    Math.max(fromX,x)+0.5+half,horizontalY+height,Math.max(fromZ,z)+0.5+half)) return false;
            return y>=fromY || clear(x+0.5-half,y,z+0.5-half,x+0.5+half,fromY+height,z+0.5+half);
        }
        boolean clear(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
            if (minY<guard.level.getMinBuildHeight() || maxY>guard.level.getMaxBuildHeight()
                    || !guard.withinBorder(minX,maxX,minZ,maxZ) || !guard.searchArea(minX-1,maxX+1,minZ-1,maxZ+1)) return false;
            this.minX=minX; this.minY=minY; this.minZ=minZ; this.maxX=maxX; this.maxY=maxY; this.maxZ=maxZ;
            int fromX=Mth.floor(minX-1.0E-7)-1,toX=Mth.floor(maxX+1.0E-7)+1;
            int fromY=Math.max(guard.level.getMinBuildHeight(),Mth.floor(minY-1.0E-7)-1);
            int toY=Math.min(guard.level.getMaxBuildHeight()-1,Mth.floor(maxY+1.0E-7)+1);
            int fromZ=Mth.floor(minZ-1.0E-7)-1,toZ=Mth.floor(maxZ+1.0E-7)+1;
            for (blockX=fromX;blockX<=toX;blockX++) for (blockY=fromY;blockY<=toY;blockY++) for (blockZ=fromZ;blockZ<=toZ;blockZ++) {
                position.set(blockX,blockY,blockZ);
                BlockState state=guard.getBlockState(position);
                boolean bodyCell=blockX<maxX && blockX+1>minX && blockY<maxY && blockY+1>minY && blockZ<maxZ && blockZ+1>minZ;
                if (bodyCell && (!state.getFluidState().isEmpty() || state.is(BlockTags.CLIMBABLE))) return false;
                if (state.isAir()) continue;
                VoxelShape shape=state.getCollisionShape(guard,position,context);
                if (shape.isEmpty()) continue;
                intersects=false; shape.forAllBoxes(collisions);
                if (intersects) return false;
            }
            return true;
        }
        boolean safeNext(Path path,CitizenEntity entity,Guard guard) {
            this.entity=entity; this.guard=guard; context=CollisionContext.of(entity);
            try {
                Node next=path.getNextNode();
                return standable(next.x,next.y,next.z);
            } catch (UnsafeSearch unavailable) { return false; }
            finally { this.entity=null; this.guard=null; context=null; }
        }
        void departStart(Path path,CitizenEntity entity,Guard guard) {
            if(path.getNodeCount()<2) return;
            Node start=path.getNode(0),next=path.getNode(1);
            if(next.y!=start.y || Math.abs(entity.getY()-start.y)>0.01) return;
            this.entity=entity; this.guard=guard; context=CollisionContext.of(entity);
            try {
                double half=entity.getBbWidth()*0.5;
                AABB center=new AABB(start.x+0.5-half,start.y,start.z+0.5-half,start.x+0.5+half,start.y+entity.getBbHeight(),start.z+0.5+half);
                occupants.clear();
                guard.level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(Entity.class),center,occupied,occupants,1);
                boolean blocked=!occupants.isEmpty(); occupants.clear();
                if(!blocked) return;
                AABB actual=entity.getBoundingBox();
                // The start is already physically occupied. Do not require recentering through a neighbour;
                // depart only when the complete actual-to-next envelope has safe native terrain.
                if(clear(Math.min(actual.minX,next.x+0.5-half),actual.minY,Math.min(actual.minZ,next.z+0.5-half),
                        Math.max(actual.maxX,next.x+0.5+half),actual.maxY,Math.max(actual.maxZ,next.z+0.5+half))) path.advance();
            } catch(UnsafeSearch unavailable) { /* Retain the original guarded path when departure cannot be proved. */ }
            finally { occupants.clear(); this.entity=null; this.guard=null; context=null; }
        }
        private void collisionBox(double fromX,double fromY,double fromZ,double toX,double toY,double toZ) {
            if (blockX+toX>minX+1.0E-7 && blockX+fromX<maxX-1.0E-7 && blockY+toY>minY+1.0E-7
                    && blockY+fromY<maxY-1.0E-7 && blockZ+toZ>minZ+1.0E-7 && blockZ+fromZ<maxZ-1.0E-7) intersects=true;
        }
        Path path() {
            int count=0;
            for (int node=search.found();node>=0;node=search.parent(node)) count++;
            int routeCapacity=registry.admission().limits().resource(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.CACHE_ENTRIES_PER_OWNER)
                    -1-guard.region.size()*2;
            if (count>routeCapacity) return null;
            ArrayList<Node> nodes=new ArrayList<>(count);
            for (int i=0;i<count;i++) nodes.add(null);
            int index=count;
            for (int node=search.found();node>=0;node=search.parent(node)) {
                Node step=new Node(search.x(node),search.y(node),search.z(node));
                step.type=net.minecraft.world.level.pathfinder.PathType.WALKABLE;
                nodes.set(--index,step);
            }
            return new Path(nodes,new BlockPos(request.target().x(),request.target().y(),request.target().z()),true);
        }
    }
}
