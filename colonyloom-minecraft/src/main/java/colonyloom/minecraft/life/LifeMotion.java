package colonyloom.minecraft.life;

import colonyloom.core.colony.Territory;
import colonyloom.core.colony.WorldPosition;
import colonyloom.core.life.content.ActivityDefinition;
import colonyloom.core.life.content.ActivityDefinition.Place;
import colonyloom.core.life.state.LifeState;
import colonyloom.core.life.state.Records.Activity;
import colonyloom.gameplay.life.Body;
import colonyloom.minecraft.life.entity.ResidentChildEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.levelgen.Heightmap;

final class LifeMotion {
    static final int teen_pro_YT_PATH_EVERY = 40;
    static final int teen_pro_YT_WANDER_EVERY = 80;
    static final double teen_pro_YT_ARRIVAL = 1.8D;
    static final double teen_pro_YT_COMPANY = 3.0D;
    static final double teen_pro_YT_PARTNER_RANGE = 48.0D;
    static final double teen_pro_YT_SPEED = 0.8D;
    static final int teen_pro_YT_GROUNDING = 6;
    static final int teen_pro_YT_MAX_ENGAGED = 512;

    static final class Engaged {
        long teen_pro_YT_nextPath;
        boolean teen_pro_YT_moving, teen_pro_YT_lying;
        WorldPosition teen_pro_YT_waypoint;
        UUID teen_pro_YT_look;
        int teen_pro_YT_lookTicks;
    }

    private final MinecraftLifeService teen_pro_YT_service;
    private final RandomGenerator teen_pro_YT_random;
    private final Map<UUID, Engaged> teen_pro_YT_engaged = new HashMap<>();
    private final LinkedHashSet<UUID> teen_pro_YT_pending = new LinkedHashSet<>();

    LifeMotion(MinecraftLifeService service, RandomGenerator random) {
        teen_pro_YT_service = Objects.requireNonNull(service);
        teen_pro_YT_random = Objects.requireNonNull(random);
    }

    void changed(UUID lifeId) { teen_pro_YT_pending.add(lifeId); }

    void look(UUID lifeId, UUID other, int ticks) {
        if (lifeId == null || other == null || lifeId.equals(other)) return;
        Engaged engaged = teen_pro_YT_engaged.get(lifeId);
        if (engaged == null) {
            if (teen_pro_YT_engaged.size() >= teen_pro_YT_MAX_ENGAGED) return;
            engaged = new Engaged();
            teen_pro_YT_engaged.put(lifeId, engaged);
        }
        engaged.teen_pro_YT_look = other;
        engaged.teen_pro_YT_lookTicks = Math.max(engaged.teen_pro_YT_lookTicks, ticks);
    }

    void forget(UUID lifeId, Entity entity) {
        Engaged engaged = teen_pro_YT_engaged.remove(lifeId);
        if (engaged != null && entity instanceof Mob mob) release(mob, engaged, false);
    }

    void tick(long tick) {
        for (UUID id : List.copyOf(teen_pro_YT_pending)) {
            teen_pro_YT_pending.remove(id);
            LifeState state = teen_pro_YT_service.life().state(id);
            if (state != null && state.activity() != null && !teen_pro_YT_engaged.containsKey(id) && teen_pro_YT_engaged.size() < teen_pro_YT_MAX_ENGAGED)
                teen_pro_YT_engaged.put(id, new Engaged());
        }
        List<UUID> done = new ArrayList<>();
        for (Map.Entry<UUID, Engaged> entry : teen_pro_YT_engaged.entrySet()) {
            UUID id = entry.getKey();
            Engaged engaged = entry.getValue();
            Entity entity = teen_pro_YT_service.entity(id);
            if (!(entity instanceof Mob mob)) { done.add(id); continue; }
            LifeState state = teen_pro_YT_service.life().state(id);
            Body body = teen_pro_YT_service.body(id);
            if (state == null || body == null || !body.present() || state.deceased() || !teen_pro_YT_service.active()) {
                release(mob, engaged, body != null && body.busy());
                done.add(id);
                continue;
            }
            if (body.busy()) {
                release(mob, engaged, true);
                done.add(id);
                continue;
            }
            Activity activity = state.activity();
            if (engaged.teen_pro_YT_lookTicks > 0) {
                engaged.teen_pro_YT_lookTicks--;
                Entity target = engaged.teen_pro_YT_look == null ? null : teen_pro_YT_service.entity(engaged.teen_pro_YT_look);
                if (target != null && target.level() == mob.level() && target.distanceToSqr(mob) < 256.0D) mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
            }
            if (activity == null) {
                release(mob, engaged, false);
                if (engaged.teen_pro_YT_lookTicks <= 0) done.add(id);
                continue;
            }
            ActivityDefinition definition = teen_pro_YT_service.life().content().activity(activity.activityId());
            if (definition == null) { release(mob, engaged, false); done.add(id); continue; }
            if (mob instanceof ResidentChildEntity child) child.managed(true);
            if (!activity.performing()) travel(id, mob, engaged, state, activity, tick);
            else perform(mob, engaged, state, activity, definition, tick);
        }
        for (UUID id : done) teen_pro_YT_engaged.remove(id);
    }

    private void travel(UUID id, Mob mob, Engaged engaged, LifeState state, Activity activity, long tick) {
        stand(mob, engaged);
        WorldPosition target = activity.target();
        double reach = teen_pro_YT_ARRIVAL;
        if (activity.partner() != null) {
            Entity partner = teen_pro_YT_service.entity(activity.partner());
            if (partner != null && partner.level() == mob.level() && distance(partner, target) <= teen_pro_YT_PARTNER_RANGE) {
                target = position(partner);
                reach = teen_pro_YT_COMPANY;
            }
        }
        if (distance(mob, target) <= reach) {
            if (engaged.teen_pro_YT_moving) mob.getNavigation().stop();
            engaged.teen_pro_YT_moving = false;
            teen_pro_YT_service.controller().arrived(id);
            return;
        }
        if (tick >= engaged.teen_pro_YT_nextPath) {
            engaged.teen_pro_YT_nextPath = tick + teen_pro_YT_PATH_EVERY + teen_pro_YT_random.nextInt(10);
            engaged.teen_pro_YT_moving = mob.getNavigation().moveTo(target.x() + 0.5D, target.y(), target.z() + 0.5D, teen_pro_YT_SPEED) || engaged.teen_pro_YT_moving;
        }
    }

    private void perform(Mob mob, Engaged engaged, LifeState state, Activity activity, ActivityDefinition definition, long tick) {
        boolean lie = definition.pose() == ActivityDefinition.Pose.LIE;
        if (lie) {
            if (engaged.teen_pro_YT_moving) { mob.getNavigation().stop(); engaged.teen_pro_YT_moving = false; }
            if (mob.getPose() != Pose.SLEEPING) mob.setPose(Pose.SLEEPING);
            engaged.teen_pro_YT_lying = true;
            return;
        }
        stand(mob, engaged);
        if ((definition.place() == Place.WANDER || definition.place() == Place.AWAY) && activity.remaining() > 200) {
            WorldPosition waypoint = engaged.teen_pro_YT_waypoint;
            if (waypoint == null || distance(mob, waypoint) <= teen_pro_YT_ARRIVAL || tick >= engaged.teen_pro_YT_nextPath) {
                waypoint = wander(state, activity, definition, mob);
                engaged.teen_pro_YT_waypoint = waypoint;
                engaged.teen_pro_YT_nextPath = tick + teen_pro_YT_WANDER_EVERY + teen_pro_YT_random.nextInt(40);
                if (waypoint != null) engaged.teen_pro_YT_moving = mob.getNavigation().moveTo(waypoint.x() + 0.5D, waypoint.y(), waypoint.z() + 0.5D, 0.6D) || engaged.teen_pro_YT_moving;
            }
            return;
        }
        if (engaged.teen_pro_YT_moving && mob.getNavigation().isDone()) engaged.teen_pro_YT_moving = false;
        if (activity.partner() != null && engaged.teen_pro_YT_lookTicks <= 0) {
            Entity partner = teen_pro_YT_service.entity(activity.partner());
            if (partner != null && partner.level() == mob.level() && partner.distanceToSqr(mob) < 64.0D) mob.getLookControl().setLookAt(partner, 30.0F, 30.0F);
        }
        animate(mob, definition.animation(), tick);
    }

    private void animate(Mob mob, ActivityDefinition.Animation animation, long tick) {
        double yaw = Math.toRadians(mob.getYRot());
        double ahead = 3.0D;
        double x = mob.getX() - Math.sin(yaw) * ahead, z = mob.getZ() + Math.cos(yaw) * ahead;
        switch (animation) {
            case LOOK_UP -> mob.getLookControl().setLookAt(x, mob.getEyeY() + 5.0D, z);
            case LOOK_AROUND -> {
                if (tick % 40 == 0) mob.setYRot(mob.getYRot() + (teen_pro_YT_random.nextFloat() - 0.5F) * 140.0F);
                mob.getLookControl().setLookAt(x, mob.getEyeY(), z);
            }
            case SWING -> { if (tick % 25 == 0) mob.swing(InteractionHand.MAIN_HAND); }
            case JUMP -> { if (mob.onGround() && tick % 16 == 0) mob.getJumpControl().jump(); }
            case SPIN -> { mob.setYRot(mob.getYRot() + 15.0F); mob.yBodyRot = mob.getYRot(); mob.yHeadRot = mob.getYRot(); }
            case NOD -> mob.getLookControl().setLookAt(x, mob.getEyeY() + (tick % 20 < 10 ? -1.5D : 0.5D), z);
            case SHAKE -> mob.yHeadRot = mob.yBodyRot + (tick % 8 < 4 ? 25.0F : -25.0F);
            default -> {}
        }
    }

    private WorldPosition wander(LifeState state, Activity activity, ActivityDefinition definition, Mob mob) {
        Territory territory = teen_pro_YT_service.territory(state.colonyId());
        if (territory == null) return null;
        int radius = Math.max(2, definition.place() == Place.AWAY ? definition.radius() * 2 : definition.radius());
        WorldPosition center = activity.target();
        int x = Math.max(territory.minX(), Math.min(territory.maxX(), center.x() + teen_pro_YT_random.nextInt(2 * radius + 1) - radius));
        int z = Math.max(territory.minZ(), Math.min(territory.maxZ(), center.z() + teen_pro_YT_random.nextInt(2 * radius + 1) - radius));
        return grounded((ServerLevel) mob.level(), new WorldPosition(center.dimension(), x, center.y(), z));
    }

    private static WorldPosition grounded(ServerLevel level, WorldPosition position) {
        if (!level.isLoaded(new BlockPos(position.x(), position.y(), position.z()))) return position;
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, position.x(), position.z());
        return Math.abs(surface - position.y()) <= teen_pro_YT_GROUNDING ? new WorldPosition(position.dimension(), position.x(), surface, position.z()) : position;
    }

    private void stand(Mob mob, Engaged engaged) {
        if (engaged.teen_pro_YT_lying || mob.getPose() == Pose.SLEEPING) {
            mob.setPose(Pose.STANDING);
            engaged.teen_pro_YT_lying = false;
        }
    }

    private void release(Mob mob, Engaged engaged, boolean busy) {
        if (!busy && engaged.teen_pro_YT_moving) mob.getNavigation().stop();
        engaged.teen_pro_YT_moving = false;
        engaged.teen_pro_YT_waypoint = null;
        if (engaged.teen_pro_YT_lying || mob.getPose() == Pose.SLEEPING) { mob.setPose(Pose.STANDING); engaged.teen_pro_YT_lying = false; }
        if (mob instanceof ResidentChildEntity child) child.managed(false);
    }

    static WorldPosition position(Entity entity) {
        BlockPos pos = entity.blockPosition();
        return new WorldPosition(entity.level().dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ());
    }

    static double distance(Entity entity, WorldPosition target) {
        if (!entity.level().dimension().location().toString().equals(target.dimension())) return Double.MAX_VALUE;
        double dx = entity.getX() - (target.x() + 0.5D), dy = entity.getY() - target.y(), dz = entity.getZ() - (target.z() + 0.5D);
        return Math.sqrt(dx * dx + dy * dy * 0.25D + dz * dz);
    }
}
