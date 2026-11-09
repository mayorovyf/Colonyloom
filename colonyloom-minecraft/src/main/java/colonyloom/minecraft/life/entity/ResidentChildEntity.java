package colonyloom.minecraft.life.entity;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public final class ResidentChildEntity extends PathfinderMob {
    public static final String teen_pro_YT_ID = "resident_child";
    private static final String teen_pro_YT_TAG = "ColonyloomLife";
    private UUID teen_pro_YT_lifeId;
    private UUID teen_pro_YT_colonyId;
    private long teen_pro_YT_activeTicks;
    private boolean teen_pro_YT_managed;

    public ResidentChildEntity(EntityType<? extends ResidentChildEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setCanPickUpLoot(false);
    }

    public static AttributeSupplier.Builder attributes() {
        return createMobAttributes()
                .add(Attributes.MAX_HEALTH, 14.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.27D)
                .add(Attributes.FOLLOW_RANGE, 24.0D);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(5, new Wander(this));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 6.0F));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    private static final class Wander extends WaterAvoidingRandomStrollGoal {
        private final ResidentChildEntity teen_pro_YT_child;

        Wander(ResidentChildEntity child) {
            super(child, 0.6D);
            teen_pro_YT_child = child;
        }

        @Override public boolean canUse() { return !teen_pro_YT_child.teen_pro_YT_managed && super.canUse(); }
        @Override public boolean canContinueToUse() { return !teen_pro_YT_child.teen_pro_YT_managed && super.canContinueToUse(); }
    }

    public void bind(UUID lifeId, UUID colonyId) {
        if (teen_pro_YT_lifeId != null && !teen_pro_YT_lifeId.equals(lifeId)) throw new IllegalStateException("A child keeps one life");
        teen_pro_YT_lifeId = lifeId;
        teen_pro_YT_colonyId = colonyId;
    }

    public UUID lifeId() { return teen_pro_YT_lifeId; }
    public UUID colonyId() { return teen_pro_YT_colonyId; }
    public long activeTicks() { return teen_pro_YT_activeTicks; }
    public boolean managed() { return teen_pro_YT_managed; }
    public void managed(boolean value) { teen_pro_YT_managed = value; }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && isAlive() && teen_pro_YT_activeTicks < Long.MAX_VALUE / 2) teen_pro_YT_activeTicks++;
    }

    @Override
    public boolean removeWhenFarAway(double distance) { return false; }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        CompoundTag data = new CompoundTag();
        if (teen_pro_YT_lifeId != null) data.putUUID("lifeId", teen_pro_YT_lifeId);
        if (teen_pro_YT_colonyId != null) data.putUUID("colonyId", teen_pro_YT_colonyId);
        data.putLong("activeTicks", teen_pro_YT_activeTicks);
        tag.put(teen_pro_YT_TAG, data);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        CompoundTag data = tag.getCompound(teen_pro_YT_TAG);
        teen_pro_YT_lifeId = data.hasUUID("lifeId") ? data.getUUID("lifeId") : null;
        teen_pro_YT_colonyId = data.hasUUID("colonyId") ? data.getUUID("colonyId") : null;
        teen_pro_YT_activeTicks = Math.max(0, data.getLong("activeTicks"));
        teen_pro_YT_managed = false;
        setPersistenceRequired();
        setCanPickUpLoot(false);
    }
}
