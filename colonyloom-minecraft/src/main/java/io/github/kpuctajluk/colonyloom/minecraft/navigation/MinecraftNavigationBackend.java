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
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** One server's synchronous vanilla ground backend; never asks the world to load a search chunk. */
public final class MinecraftNavigationBackend implements NavigationService.Backend {
    private final MinecraftServer server;
    private final ColonyRegistry registry;
    private final ChunkDemandManager chunks;
    private final NavigationService.GoalAuthority goals;
    private final Map<UUID, Moving> moving=new HashMap<>();
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
    private record VanillaRoute(UUID requestId,UUID entityId,long epoch,long goalRevision,Path path,Guard guard) implements Route {
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
    @Override public Route search(Request request,List<ChunkKey> admittedRegion) {
        CitizenEntity entity=entity(request);
        if (entity==null || !chunks.admitted(request.workId()) || !chunks.ready(request.workId()) || admittedRegion.isEmpty() || admittedRegion.size()>81) return null;
        if (entity.distanceToSqr(request.target().x()+0.5,request.target().y(),request.target().z()+0.5)>4096) return null;
        ServerLevel level=(ServerLevel)entity.level();
        Guard guard=new Guard(request,level,admittedRegion);
        if (!guard.allReady()) return null;
        int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for (ChunkKey key:admittedRegion) { minX=Math.min(minX,key.x());minZ=Math.min(minZ,key.z());maxX=Math.max(maxX,key.x());maxZ=Math.max(maxZ,key.z()); }
        // Constructor calls getChunk: check its entire rectangular footprint first, with no yield.
        for (int x=minX;x<=maxX;x++) for (int z=minZ;z<=maxZ;z++) if (!guard.allowedChunk(x,z)) return null;
        GuardedRegion region=new GuardedRegion(level,new BlockPos(minX<<4,level.getMinBuildHeight(),minZ<<4),
                new BlockPos((maxX<<4)+15,level.getMaxBuildHeight()-1,(maxZ<<4)+15),guard);
        GuardedEvaluator evaluator=new GuardedEvaluator(guard);
        evaluator.setCanPassDoors(true);
        Path path;
        try {
            path=new PathFinder(evaluator,NavigationService.SEARCH_NODES).findPath(region,entity,
                    Set.of(new BlockPos(request.target().x(),request.target().y(),request.target().z())),64.0F,0,1.0F);
        } catch (UnsafeSearch denied) { evaluator.done(); return null; }
        if (path==null || !path.canReach() || path.getNodeCount()>NavigationService.SEARCH_NODES || !validPath(path,guard,entity)) return null;
        return new VanillaRoute(request.id(),entity.getUUID(),request.epoch(),request.goalRevision(),path,guard);
    }
    private static boolean validPath(Path path,Guard guard,CitizenEntity entity) {
        for (int i=0;i<path.getNodeCount();i++) {
            Node node=path.getNode(i);
            if (!guard.node(node.x,node.y,node.z,entity.getBbWidth(),entity.getBbHeight())) return false;
        }
        return true;
    }
    @Override public boolean apply(Request request,Route route,BooleanSupplier stillCurrent) {
        CitizenEntity entity=entity(request);
        if (!(route instanceof VanillaRoute vanilla) || entity==null || !request.id().equals(vanilla.requestId())
                || !entity.getUUID().equals(vanilla.entityId()) || request.epoch()!=vanilla.epoch()
                || request.goalRevision()!=vanilla.goalRevision() || !stillCurrent.getAsBoolean() || !vanilla.guard().allReady()
                || !validPath(vanilla.path(),vanilla.guard(),entity)) return false;
        stop(request);
        GroundPathNavigation navigation=new ControlledNavigation(entity,entity.level());
        if (!navigation.moveTo(vanilla.path(),1.0)) return false;
        Moving current=new Moving(request,entity,vanilla.guard(),navigation,stillCurrent);
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
        if (entity.distanceToSqr(request.target().x()+0.5,request.target().y(),request.target().z()+0.5)<=0.64
                && Math.abs(entity.getY()-request.target().y())<=0.5) { stop(request); return Motion.ARRIVED; }
        Path path=current.navigation.getPath();
        if (path==null || current.navigation.isDone() || current.navigation.isStuck()
                || !current.guard.box(entity.getBoundingBox().inflate(1.0))) { stop(request); return Motion.OBSTRUCTED; }
        BlockPos next=path.getNextNodePos();
        if (!current.guard.node(next.getX(),next.getY(),next.getZ(),entity.getBbWidth(),entity.getBbHeight())) { stop(request); return Motion.UNAVAILABLE; }
        AABB body=entity.getBoundingBox().move(next.getX()+0.5-entity.getX(),next.getY()-entity.getY(),next.getZ()+0.5-entity.getZ());
        if (!current.guard.box(body.inflate(1.0))) { stop(request); return Motion.UNAVAILABLE; }
        if (!entity.level().noCollision(entity,body)) { stop(request); return Motion.OBSTRUCTED; }
        Vec3 position=entity.position();
        if (position.distanceToSqr(current.lastPosition)<0.0001) current.stalled++; else current.stalled=0;
        current.lastPosition=position;
        if (current.stalled>=20) { stop(request); return Motion.OBSTRUCTED; }
        current.navigation.tick();
        return Motion.MOVING;
    }
    private void safetyCheck(Moving current) {
        if (moving.get(current.request.workId())!=current || current.stopped!=null) return;
        CitizenEntity entity=current.entity;
        if (!current.validity.getAsBoolean() || entity(current.request)!=entity
                || !current.guard.box(entity.getBoundingBox().expandTowards(entity.getDeltaMovement()).inflate(1.0))) {
            halt(current,Motion.UNAVAILABLE); return;
        }
        Path path=current.navigation.getPath();
        if (path==null || path.isDone()) {
            if (entity.distanceToSqr(current.request.target().x()+0.5,current.request.target().y(),current.request.target().z()+0.5)>0.64) {
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
        if (!entity.level().noCollision(entity,body)) halt(current,Motion.OBSTRUCTED);
    }
    private void halt(Moving current,Motion reason) {
        current.stopped=reason; current.navigation.stop(); current.entity.managedMovementGuard(null); current.entity.stopInPlace();
        current.entity.getMoveControl().setWantedPosition(current.entity.getX(),current.entity.getY(),current.entity.getZ(),0);
        current.entity.getMoveControl().tick(); current.entity.setZza(0);
        current.entity.setDeltaMovement(current.entity.getDeltaMovement().multiply(0,1,0));
    }
    @Override public void stop(Request request) {
        owner(); Moving current=moving.get(request.workId());
        if (current==null || !current.request.id().equals(request.id())) return;
        moving.remove(request.workId()); halt(current,Motion.UNAVAILABLE);
    }
    /** The entity's default navigator stays idle. Only budgeted polls advance this vanilla controller. */
    private static final class ControlledNavigation extends GroundPathNavigation {
        ControlledNavigation(Mob mob,Level level) { super(mob,level); }
        @Override public void recomputePath() { stop(); }
        @Override protected Path createPath(Set<BlockPos> targets,int accuracy,boolean offset,int range,float distance) { return null; }
        @Override protected void trimPath() { /* Search already validates the exact target; do not re-read arbitrary sky columns. */ }
    }
    private static final class UnsafeSearch extends RuntimeException {
        UnsafeSearch() { super(null,null,false,false); }
    }
    private final class Guard {
        final Request request;
        final ServerLevel level;
        final List<ChunkKey> region;
        final BlockPos.MutableBlockPos readinessPosition=new BlockPos.MutableBlockPos();
        Guard(Request request,ServerLevel level,List<ChunkKey> region) { this.request=request;this.level=level;this.region=region; }
        boolean allowedChunk(int x,int z) {
            ChunkKey selected=null;
            for (ChunkKey key:region) if (key.x()==x && key.z()==z && key.dimension().equals(request.target().dimension())) { selected=key;break; }
            return selected!=null && chunks.admitted(request.workId()) && chunks.admitted(selected)
                    && chunks.ready(selected,ChunkDemandManager.Readiness.ENTITY_TICKING)
                    && level.getChunkSource().getChunkNow(x,z)!=null && level.isPositionEntityTicking(readinessPosition.set(x<<4,request.target().y(),z<<4));
        }
        boolean allReady() { for (ChunkKey key:region) if (!allowedChunk(key.x(),key.z())) return false;return true; }
        boolean box(AABB box) { return area(box.minX,box.maxX,box.minZ,box.maxZ); }
        boolean area(double fromX,double toX,double fromZ,double toZ) {
            int minX=Mth.floor(fromX)>>4,maxX=Mth.floor(toX)>>4,minZ=Mth.floor(fromZ)>>4,maxZ=Mth.floor(toZ)>>4;
            if ((long)(maxX-minX+1)*(maxZ-minZ+1)>81) return false;
            for (int x=minX;x<=maxX;x++) for (int z=minZ;z<=maxZ;z++) if (!allowedChunk(x,z)) return false;
            return true;
        }
        boolean node(int x,int y,int z,float width,float height) {
            double half=width*0.5;
            return y>=level.getMinBuildHeight()+1 && y+height<level.getMaxBuildHeight()
                    && area(x+0.5-half-1,x+0.5+half+1,z+0.5-half-1,z+0.5+half+1);
        }
        LevelChunk chunk(BlockPos pos) {
            if (!allowedChunk(pos.getX()>>4,pos.getZ()>>4)) throw new UnsafeSearch();
            return level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
        }
    }
    /** Reject candidate coordinates and their body/neighbor footprint before vanilla reads anything. */
    private static final class GuardedEvaluator extends WalkNodeEvaluator {
        final Guard guard;
        GuardedEvaluator(Guard guard) { this.guard=guard; }
        @Override protected Node getNode(int x,int y,int z) {
            if (nodes.size()>=NavigationService.SEARCH_NODES && !nodes.containsKey(Node.createHash(x,y,z))) throw new UnsafeSearch();
            return super.getNode(x,y,z);
        }
        @Override public void prepare(PathNavigationRegion region,Mob mob) {
            super.prepare(region,mob);
            // Guard cache hits too; the base method updates loader-visible currentEvalPos.
            currentContext=new PathfindingContext(region,mob) {
                @Override public PathType getPathTypeFromState(int x,int y,int z) {
                    guard.chunk(new BlockPos(x,y,z));
                    return super.getPathTypeFromState(x,y,z);
                }
            };
        }
        @Override protected Node findAcceptedNode(int x,int y,int z,int step,double floor,net.minecraft.core.Direction direction,PathType previous) {
            if (!guard.node(x,y,z,mob.getBbWidth(),mob.getBbHeight())) return null;
            return super.findAcceptedNode(x,y,z,step,floor,direction,previous);
        }
        @Override public PathType getPathTypeOfMob(PathfindingContext context,int x,int y,int z,Mob mob) {
            return guard.node(x,y,z,mob.getBbWidth(),mob.getBbHeight()) ? super.getPathTypeOfMob(context,x,y,z,mob) : PathType.BLOCKED;
        }
    }
    private static final class GuardedRegion extends PathNavigationRegion {
        final Guard guard;
        GuardedRegion(ServerLevel level,BlockPos from,BlockPos to,Guard guard) { super(level,from,to);this.guard=guard; }
        @Override public BlockState getBlockState(BlockPos pos) { return guard.chunk(pos).getBlockState(pos); }
        @Override public FluidState getFluidState(BlockPos pos) { return guard.chunk(pos).getFluidState(pos); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return guard.chunk(pos).getBlockEntity(pos); }
        @Override public BlockGetter getChunkForCollisions(int x,int z) {
            if (!guard.allowedChunk(x,z)) throw new UnsafeSearch();
            return guard.level.getChunkSource().getChunkNow(x,z);
        }
        @Override public List<VoxelShape> getEntityCollisions(Entity entity,AABB box) {
            if (!guard.box(box.inflate(1.0))) throw new UnsafeSearch();
            return super.getEntityCollisions(entity,box);
        }
    }
}
