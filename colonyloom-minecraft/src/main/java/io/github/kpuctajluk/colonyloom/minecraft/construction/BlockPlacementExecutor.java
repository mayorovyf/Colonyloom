package io.github.kpuctajluk.colonyloom.minecraft.construction;

import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.MemberRank;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.content.BlockDescriptor;
import io.github.kpuctajluk.colonyloom.core.world.WorldAccess;
import io.github.kpuctajluk.colonyloom.minecraft.content.ContentLoader;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Checks current authority and actual NPC property immediately before one ordinary item use. */
public final class BlockPlacementExecutor implements WorldAccess {
    @FunctionalInterface
    public interface ItemInteraction {
        InteractionResult use(ServerLevel level, CitizenEntity citizen, UUID principal, BlockPos target, ItemStack source);
    }
    public enum FaultPoint { BEFORE_BLOCK_CHANGE, AFTER_BLOCK_CHANGE, BEFORE_EFFECT_COMMIT }
    @FunctionalInterface
    public interface FaultObserver {
        void observe(FaultPoint point, ActionContext context);
    }

    private final MinecraftServer server;
    private final ColonyRegistry registry;
    private final ItemInteraction interaction;
    private final FaultObserver observer;

    public BlockPlacementExecutor(MinecraftServer server, ColonyRegistry registry, ItemInteraction interaction) {
        this(server, registry, interaction, null);
    }
    public BlockPlacementExecutor(MinecraftServer server, ColonyRegistry registry, ItemInteraction interaction, FaultObserver observer) {
        this.server = Objects.requireNonNull(server);
        this.registry = Objects.requireNonNull(registry);
        this.interaction = Objects.requireNonNull(interaction);
        this.observer = observer;
    }
    /** Test-only callback; deliberately performs neither a world save nor a logical commit. */
    public void observe(FaultPoint point, ActionContext context) {
        if (observer != null) observer.observe(point, context);
    }
    private void owner() {
        if (!server.isSameThread()) throw new IllegalStateException("Placement requires the server thread");
        registry.requireOwner();
    }
    private ServerLevel level(String dimension) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension)));
    }
    private static BlockPos position(WorldPosition target) {
        return new BlockPos(target.x(), target.y(), target.z());
    }
    private static boolean ready(ServerLevel level, BlockPos pos) {
        return level != null && !level.isOutsideBuildHeight(pos)
                && level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null
                && level.isPositionEntityTicking(pos);
    }
    // Stairs read immediate neighbors and update their shapes; prove the entire footprint without loading it.
    private static boolean footprintReady(ServerLevel level, BlockPos pos) {
        for (int x = -2; x <= 2; x += 2) for (int z = -2; z <= 2; z += 2) {
            if (!ready(level, pos.offset(x, 0, z))) return false;
        }
        return ready(level, pos.below()) && ready(level, pos.above());
    }
    private CitizenEntity entity(CitizenRecord record, long epoch, ServerLevel level) {
        if (record == null || level == null || record.bindingEpoch() != epoch
                || record.lifecycle() != CitizenRecord.Lifecycle.ALIVE
                || record.admission() != CitizenRecord.Admission.ACTIVE
                || record.readiness() != CitizenRecord.Readiness.READY
                || !registry.bindings().activeEntity(record.citizenId()).filter(record.entityId()::equals).isPresent()) return null;
        Entity physical = level.getEntity(record.entityId());
        return physical instanceof CitizenEntity citizen && citizen.isAlive() && !citizen.isRemoved()
                && !citizen.isQuarantined() && !citizen.isPassenger()
                && record.citizenId().equals(citizen.citizenId()) && citizen.bindingEpoch() == epoch
                && ready(level, citizen.blockPosition()) ? citizen : null;
    }
    /** Actual nine-slot total for effect evidence; unknown embodiments are not an observed zero. */
    public int materialCount(UUID citizenId, long epoch, String itemId) {
        owner();
        CitizenRecord record = registry.findCitizen(citizenId).orElse(null);
        CitizenEntity citizen = record == null ? null : entity(record, epoch, level(record.lastKnownPosition().dimension()));
        if (citizen == null) return -1;
        ResourceLocation id = ResourceLocation.parse(itemId);
        int count = 0;
        for (int slot = 0; slot < CitizenEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = citizen.inventory().getItem(slot);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id)) count = Math.addExact(count, stack.getCount());
        }
        return count;
    }
    @Override public boolean matches(WorldPosition target, BlockDescriptor expected) {
        owner();
        ServerLevel level = level(target.dimension());
        BlockPos pos = position(target);
        return ready(level, pos) && level.getBlockState(pos).equals(ContentLoader.decodeBlockState(expected));
    }
    @Override public Placement place(ActionContext context, long bindingEpoch, BlockDescriptor expected) {
        owner();
        if (context.actionKind() != ActionContext.Kind.BLOCK_PLACE) return Placement.PERMISSION_DENIED;
        CitizenRecord record = registry.findCitizen(context.citizenId()).orElse(null);
        if (record == null || !record.colonyId().equals(context.colonyId())) return Placement.UNAVAILABLE;
        ColonyRuntime colony = registry.colony(record.colonyId());
        if (colony == null || !colony.available()) return Placement.UNAVAILABLE;
        if (colony.authorityRevision() != context.authorityRevision() || !colony.territory().contains(context.target())) return Placement.PERMISSION_DENIED;
        UUID principal = colony.ownerId();
        if (context.authorityMode() == ActionContext.AuthorityMode.INDIVIDUAL) {
            MemberRank rank = colony.rank(context.initiatorId());
            if (rank != MemberRank.OWNER && rank != MemberRank.MANAGER) return Placement.PERMISSION_DENIED;
            principal = context.initiatorId();
        }
        ServerLevel level = level(context.target().dimension());
        CitizenEntity citizen = entity(record, bindingEpoch, level);
        BlockPos pos = position(context.target());
        if (citizen == null || !footprintReady(level, pos)) return Placement.UNAVAILABLE;
        if (citizen.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5) > 4.5 * 4.5) return Placement.UNAVAILABLE;
        BlockState desired = ContentLoader.decodeBlockState(expected);
        BlockState beforeState = level.getBlockState(pos);
        if (beforeState.equals(desired)) return Placement.ALREADY_PRESENT;
        if (!level.getWorldBorder().isWithinBounds(pos)) return Placement.PERMISSION_DENIED;
        if ((!beforeState.isAir() && !beforeState.canBeReplaced()) || !beforeState.getFluidState().isEmpty()
                || beforeState.hasBlockEntity() || level.getBlockEntity(pos) != null) return Placement.OBSTRUCTED;
        if (!(desired.is(Blocks.OAK_PLANKS) || desired.is(Blocks.OAK_STAIRS)) || desired.hasBlockEntity()) return Placement.OBSTRUCTED;
        if (desired.is(Blocks.OAK_STAIRS) && (desired.getValue(StairBlock.HALF) != Half.BOTTOM
                || desired.getValue(StairBlock.SHAPE) != StairsShape.STRAIGHT || desired.getValue(StairBlock.WATERLOGGED))) return Placement.OBSTRUCTED;
        BlockPos support = pos.below();
        if (!level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)
                || !desired.canSurvive(level, pos) || !level.isUnobstructed(desired, pos, CollisionContext.empty())) return Placement.OBSTRUCTED;
        ResourceLocation material = ResourceLocation.parse(expected.itemId());
        int sourceSlot = -1;
        ItemStack source = ItemStack.EMPTY;
        for (int slot = 0; slot < CitizenEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = citizen.inventory().getItem(slot);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(material)
                    && stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == desired.getBlock()
                    && stack.getComponentsPatch().isEmpty()) {
                sourceSlot = slot; source = stack; break;
            }
        }
        if (sourceSlot < 0) return Placement.MATERIALS;
        Direction facing = desired.is(Blocks.OAK_STAIRS) ? desired.getValue(StairBlock.FACING) : Direction.NORTH;
        BlockState predicted = desired.getBlock().getStateForPlacement(new DirectionalPlaceContext(level, pos, facing, source, Direction.UP));
        if (!desired.equals(predicted)) return Placement.OBSTRUCTED;
        int before = source.getCount();
        observe(FaultPoint.BEFORE_BLOCK_CHANGE, context);
        float oldYaw = citizen.getYRot();
        if (desired.is(Blocks.OAK_STAIRS)) citizen.setYRot(desired.getValue(StairBlock.FACING).toYRot());
        InteractionResult result;
        try {
            result = interaction.use(level, citizen, principal, pos, source);
        } catch (RuntimeException failure) {
            // A listener/item may have changed independently saved sides before throwing. Never compensate.
            if (!level.getBlockState(pos).equals(beforeState)) observe(FaultPoint.AFTER_BLOCK_CHANGE, context);
            citizen.inventory().setChanged();
            return Placement.AMBIGUOUS;
        } finally {
            citizen.setYRot(oldYaw);
        }
        citizen.inventory().setChanged();
        BlockState afterState = level.getBlockState(pos);
        if (!afterState.equals(beforeState)) observe(FaultPoint.AFTER_BLOCK_CHANGE, context);
        if (citizen.inventory().getItem(sourceSlot) != source || result == null
                || !source.isEmpty() && !source.getComponentsPatch().isEmpty()) return Placement.AMBIGUOUS;
        int spent = before - source.getCount();
        if (result.consumesAction() && spent == 1 && afterState.equals(desired)) return Placement.PLACED;
        if (spent != 0 || !afterState.equals(beforeState) || result.consumesAction()) return Placement.AMBIGUOUS;
        return result == InteractionResult.FAIL ? Placement.PERMISSION_DENIED : Placement.OBSTRUCTED;
    }
}
