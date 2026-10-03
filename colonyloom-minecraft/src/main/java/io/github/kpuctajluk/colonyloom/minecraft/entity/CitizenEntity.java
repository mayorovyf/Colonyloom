package io.github.kpuctajluk.colonyloom.minecraft.entity;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Physical embodiment only: identity authority and work execution belong to the server runtime. */
public final class CitizenEntity extends PathfinderMob {
    public static final int INVENTORY_SIZE = 9;
    private static final String DATA_TAG = "Colonyloom";
    private static final String CITIZEN_ID_TAG = "citizenId";
    private static final String BINDING_EPOCH_TAG = "bindingEpoch";

    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);
    private UUID citizenId;
    private long bindingEpoch;
    private boolean quarantined = true;
    private Runnable managedMovementGuard;
    private io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics metrics;
    public void runtimeMetrics(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics value) { requireServerThread(); metrics=Objects.requireNonNull(value); }
    private java.util.function.IntConsumer deathInventoryObserver;
    public void observeDeathInventory(java.util.function.IntConsumer observer) { requireServerThread(); deathInventoryObserver=Objects.requireNonNull(observer); }

    public CitizenEntity(EntityType<? extends CitizenEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setCanPickUpLoot(false);
    }

    public static AttributeSupplier.Builder attributes() {
        return createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.FOLLOW_RANGE, 32.0D)
                .add(Attributes.STEP_HEIGHT, 1.0D);
    }

    @Override
    protected void registerGoals() {
        // Navigation is driven explicitly by the bounded server backend, never by wandering goals.
    }

    /** Used for explicit creation or operator rebinding, never to replace an unloaded embodiment. */
    public void initializeIdentity(UUID id, long epoch) {
        requireServerThread();
        Objects.requireNonNull(id, "id");
        if (epoch <= 0) {
            throw new IllegalArgumentException("Binding epoch must be positive");
        }
        if (citizenId != null && !citizenId.equals(id)) {
            throw new IllegalStateException("An embodiment cannot change citizen identity");
        }
        if (epoch < bindingEpoch) {
            throw new IllegalArgumentException("Binding epoch cannot move backwards");
        }
        if (!id.equals(citizenId) || epoch != bindingEpoch) {
            setQuarantined(true);
            citizenId = id;
            bindingEpoch = epoch;
        }
    }

    /** Nullable until initialized, including entities loaded without valid identity data. */
    public UUID citizenId() {
        return citizenId;
    }

    public long bindingEpoch() {
        return bindingEpoch;
    }

    /** Actual Minecraft inventory; callers must validate the current authoritative binding first. */
    public SimpleContainer inventory() {
        return inventory;
    }

    public boolean isQuarantined() {
        return quarantined;
    }
    /** Backend-owned guarded native locomotion; path search stays in global managed budgets. */
    public void managedMovementGuard(Runnable guard) { requireServerThread(); managedMovementGuard = guard; }
    @Override public void tick() {
        long start=metrics==null ? 0 : System.nanoTime();
        try {
            if (!level().isClientSide && managedMovementGuard != null) managedMovementGuard.run();
            super.tick();
        } finally { if(metrics!=null) metrics.record(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer.ENTITY_TICK,System.nanoTime()-start); }
    }
    @Override public void move(net.minecraft.world.entity.MoverType type,net.minecraft.world.phys.Vec3 movement) {
        long start=metrics==null ? 0 : System.nanoTime();
        try { super.move(type,movement); }
        finally { if(metrics!=null) metrics.record(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer.MOVEMENT,System.nanoTime()-start); }
    }
    @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source,float amount) {
        long start=metrics==null ? 0 : System.nanoTime();
        try { return super.hurt(source,amount); }
        finally { if(metrics!=null) metrics.record(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer.DAMAGE,System.nanoTime()-start); }
    }

    /** Runtime-only decision. Never loaded from NBT or accepted from a client. */
    public void setQuarantined(boolean value) {
        requireServerThread();
        if (!value && (citizenId == null || bindingEpoch <= 0 || !isAlive())) {
            throw new IllegalStateException("An unidentified or dead citizen cannot leave quarantine");
        }
        quarantined = value;
        if (value) {
            getNavigation().stop();
            getMoveControl().setWantedPosition(getX(), getY(), getZ(), 0.0D);
            setTarget(null);
            setSpeed(0.0F);
            setXxa(0.0F);
            setZza(0.0F);
        }
    }

    /**
     * Explicit server integration hook: no default interaction grants inventory access.
     * The caller supplies a live permission/binding check and opens only after its mutation gate.
     */
    public boolean openInventory(ServerPlayer player, Predicate<ServerPlayer> authorization) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(authorization, "authorization");
        if (!canOpenInventory(player) || !authorization.test(player)) {
            return false;
        }
        return player.openMenu(new SimpleMenuProvider(
                (containerId, playerInventory, menuPlayer) ->
                        new CitizenInventoryMenu(containerId, playerInventory, this, authorization),
                getDisplayName())).isPresent();
    }

    boolean canOpenInventory(ServerPlayer player) {
        return !quarantined && citizenId != null && bindingEpoch > 0
                && isAlive() && !isRemoved() && player.isAlive() && !player.isSpectator()
                && player.level() == level() && player.distanceToSqr(this) <= 64.0D;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        CompoundTag data = new CompoundTag();
        if (citizenId != null) {
            data.putUUID(CITIZEN_ID_TAG, citizenId);
        }
        data.putLong(BINDING_EPOCH_TAG, bindingEpoch);
        ContainerHelper.saveAllItems(data, inventory.getItems(), registryAccess());
        tag.put(DATA_TAG, data);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        CompoundTag data = tag.getCompound(DATA_TAG);
        citizenId = data.hasUUID(CITIZEN_ID_TAG) ? data.getUUID(CITIZEN_ID_TAG) : null;
        bindingEpoch = data.getLong(BINDING_EPOCH_TAG);
        inventory.clearContent();
        ContainerHelper.loadAllItems(data, inventory.getItems(), registryAccess());
        inventory.setChanged();
        quarantined = true;
        managedMovementGuard = null;
        setPersistenceRequired();
        setCanPickUpLoot(false);
    }

    @Override
    protected void dropEquipment() {
        super.dropEquipment();
        // Cargo is existing property, not generated mob loot: it drops even when doMobLoot is false.
        // spawnAtLocation uses the vanilla death-drop path, including the loader's drop event capture.
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.removeItemNoUpdate(slot);
            if (!stack.isEmpty()) {
                spawnAtLocation(stack);
            }
        }
        inventory.setChanged();
        var observer=deathInventoryObserver; deathInventoryObserver=null;
        if(observer!=null) { int remaining=0; for(int slot=0;slot<INVENTORY_SIZE;slot++) remaining+=inventory.getItem(slot).getCount(); observer.accept(remaining); }
    }

    private void requireServerThread() {
        if (!(level() instanceof ServerLevel serverLevel) || !serverLevel.getServer().isSameThread()) {
            throw new IllegalStateException("Citizen identity and inventory access require the server thread");
        }
    }
}
