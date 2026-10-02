package io.github.kpuctajluk.colonyloom.neoforge;

import com.mojang.authlib.GameProfile;
import io.github.kpuctajluk.colonyloom.minecraft.construction.BlockPlacementExecutor;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import java.util.UUID;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Vanilla permissions plus NeoForge item/placement events; not a generic claims-mod adapter. */
public final class NeoForgeItemInteraction implements BlockPlacementExecutor.ItemInteraction {
    // One nonsaved actor per server adapter, not an unbounded profile/dimension cache.
    private ColonyActor actor;
    private boolean borrowing;
    private final Function<UUID, GameProfile> profiles;

    public NeoForgeItemInteraction() { profiles = null; }
    /** Supplies platform-observed profiles where the server has no profile service (GameTestServer). */
    public NeoForgeItemInteraction(Function<UUID, GameProfile> profiles) { this.profiles = Objects.requireNonNull(profiles); }

    @Override public InteractionResult use(ServerLevel level, CitizenEntity citizen, UUID principal, BlockPos target, ItemStack source) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Item interaction requires the server thread");
        if (level.captureBlockSnapshots || level.restoringBlockSnapshots || !level.capturedBlockSnapshots.isEmpty()) return InteractionResult.FAIL;
        if (borrowing) throw new IllegalStateException("Reentrant borrowed inventory interaction");
        if (citizen.level() != level || citizen.isQuarantined() || !citizen.isAlive() || source.isEmpty()
                || !(source.getItem() instanceof BlockItem blockItem)) return InteractionResult.FAIL;
        boolean actualSlot = false;
        for (int slot = 0; slot < CitizenEntity.INVENTORY_SIZE; slot++) if (citizen.inventory().getItem(slot) == source) { actualSlot = true; break; }
        if (!actualSlot) throw new IllegalArgumentException("Only an actual NPC slot stack may be used");
        ServerPlayer online = level.getServer().getPlayerList().getPlayer(principal);
        GameProfile profile = online != null ? online.getGameProfile() : profiles != null ? profiles.apply(principal)
                : level.getServer().getProfileCache() == null ? null : level.getServer().getProfileCache().get(principal).orElse(null);
        if (profile == null || !principal.equals(profile.getId())) return InteractionResult.FAIL;
        if (actor == null || actor.serverLevel() != level || !principal.equals(actor.getGameProfile().getId())) actor = new ColonyActor(level, profile);
        borrowing = true;
        try {
            if (!actor.gameMode.isSurvival() || actor.isCreative() || actor.isSpectator()) return InteractionResult.FAIL;
            actor.getInventory().clearContent();
            actor.getInventory().selected = 0;
            actor.setItemInHand(InteractionHand.MAIN_HAND, source);
            actor.moveTo(citizen.getX(), citizen.getY(), citizen.getZ(), citizen.getYRot(), 0);
            actor.setShiftKeyDown(true);
            // These are packet-layer checks not supplied by ServerPlayerGameMode.useItemOn itself.
            int spawnRadius = level.getServer().getSpawnProtectionRadius();
            BlockPos spawn = level.getSharedSpawnPos();
            if (level.dimension() == Level.OVERWORLD && !level.getServer().getPlayerList().getOps().isEmpty()
                    && spawnRadius > 0 && Math.max(Math.abs((long) target.getX() - spawn.getX()), Math.abs((long) target.getZ() - spawn.getZ())) <= spawnRadius) return InteractionResult.FAIL;
            if (!level.getWorldBorder().isWithinBounds(target) || !level.mayInteract(actor, target)
                    || !actor.mayInteract(level, target) || !actor.canInteractWithBlock(target, 0)
                    || !actor.mayUseItemAt(target, Direction.UP, source)) return InteractionResult.FAIL;
            BlockHitResult hit = new BlockHitResult(new Vec3(target.getX() + 0.5, target.getY() + 0.25, target.getZ() + 0.5), Direction.UP, target, false);
            BlockPlaceContext placement = new BlockPlaceContext(actor, InteractionHand.MAIN_HAND, source, hit);
            if (!placement.getClickedPos().equals(target) || !placement.canPlace()) return InteractionResult.FAIL;
            BlockState proposed = blockItem.getBlock().getStateForPlacement(placement);
            if (proposed == null || !proposed.canSurvive(level, target)) return InteractionResult.FAIL;
            // Patched vanilla path: RightClickBlock -> UseItemOnBlockEvent -> onPlaceItemIntoWorld
            // captures the block change and honors cancellable EntityPlaceEvent (restoring snapshots on veto).
            InteractionResult result = actor.gameMode.useItemOn(actor, level, source, InteractionHand.MAIN_HAND, hit);
            if (actor.getInventory().getItem(0) != source || actor.getInventory().selected != 0) throw new IllegalStateException("Item use replaced the borrowed stack");
            return result;
        } finally {
            // A throwing item/listener can leave the loader's temporary capture enabled. Close only
            // this invocation's capture; keep the actual world/stack untouched for AMBIGUOUS recovery.
            level.captureBlockSnapshots = false;
            level.capturedBlockSnapshots.clear();
            // Detach first: clearContent must never mutate or retain the NPC's stack instance.
            actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            actor.getInventory().clearContent();
            actor.setShiftKeyDown(false);
            borrowing = false;
        }
    }
    private static final class ColonyActor extends FakePlayer {
        ColonyActor(ServerLevel level, GameProfile profile) {
            super(level, profile);
            gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            GameType.SURVIVAL.updatePlayerAbilities(getAbilities());
        }
        @Override protected int getPermissionLevel() { return 0; }
        @Override public boolean canUseGameMasterBlocks() { return false; }
        @Override public boolean shouldBeSaved() { return false; }
    }
}
