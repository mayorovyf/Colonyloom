package io.github.kpuctajluk.colonyloom.neoforge;

import io.github.kpuctajluk.colonyloom.minecraft.storage.StorageIdentity;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Only proven native vanilla containers are accepted; generic handler aliases are not stock. */
public final class NeoForgeStorageIdentity implements StorageIdentity {
    private static final DeferredRegister<AttachmentType<?>> TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, ColonyloomMod.MOD_ID);
    public static final java.util.function.Supplier<AttachmentType<UUID>> STORAGE_ID = TYPES.register("storage_identity", () -> AttachmentType.builder(UUID::randomUUID).serialize(UUIDUtil.CODEC).build());
    // Transient, block-entity-owned caches cannot retain unloaded inventories through a service map.
    private static final java.util.function.Supplier<AttachmentType<CapabilitySnapshot>> CAPABILITIES = TYPES.register("storage_capabilities", () -> AttachmentType.builder(CapabilitySnapshot::new).build());
    private static final Direction[] SIDES = Direction.values();
    public static void register(IEventBus bus) { TYPES.register(bus); }

    @Override public UUID existing(BlockEntity entity) { return entity.getExistingDataOrNull(STORAGE_ID); }
    @Override public UUID getOrCreate(BlockEntity entity) {
        if (!entity.hasData(STORAGE_ID)) {
            entity.setData(STORAGE_ID, UUID.randomUUID());
            entity.setChanged();
        }
        return entity.getData(STORAGE_ID);
    }
    @Override public UUID replace(BlockEntity entity) {
        UUID identity = UUID.randomUUID();
        entity.setData(STORAGE_ID, identity);
        entity.setChanged();
        return identity;
    }

    @Override public boolean supported(ServerLevel level, List<BlockEntity> halves) {
        int size = halves.size();
        if (size == 0 || size > 2) return false;
        for (int i = 0; i < size; i++) {
            BlockEntity half = halves.get(i);
            var pos = half.getBlockPos();
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (half.isRemoved() || chunk == null || chunk.getBlockEntity(pos) != half) return false;
            var state = chunk.getBlockState(pos);
            var block = state.getBlock();
            if (block != Blocks.CHEST && block != Blocks.TRAPPED_CHEST && block != Blocks.BARREL) return false;
            if (!(half instanceof Container container) || container.getContainerSize() != 27) return false;
            CapabilitySnapshot snapshot = half.getData(CAPABILITIES);
            if (snapshot.level != level || snapshot.state != state) snapshot.reset(level, half, state);
            for (int context = 0; context < snapshot.contexts.size(); context++) {
                if (!proof(snapshot.contexts.get(context).getCapability(), half, halves)) return false;
            }
            // A provider may run arbitrary code; do not accept replacement during resolution.
            if (half.isRemoved() || chunk.getBlockState(pos) != state || chunk.getBlockEntity(pos) != half) return false;
        }
        // The second half's provider must not replace the first half after its proof.
        for (int i = 0; i < size; i++) {
            BlockEntity half = halves.get(i);
            var pos = half.getBlockPos();
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            CapabilitySnapshot snapshot = half.getData(CAPABILITIES);
            if (half.isRemoved() || chunk == null || chunk.getBlockEntity(pos) != half
                    || chunk.getBlockState(pos) != snapshot.state) return false;
        }
        return true;
    }

    private static boolean proof(IItemHandler handler, BlockEntity half, List<BlockEntity> halves) {
        // Cache resolution, not authority: every context still proves the exact current native mapping.
        if (handler == null || handler.getClass() != InvWrapper.class) return false;
        Container inventory = ((InvWrapper)handler).getInv();
        if (halves.size() == 1) return inventory == half && handler.getSlots() == 27;
        return inventory instanceof CompoundContainer compound && handler.getSlots() == 54
                && compound.contains((Container)halves.get(0)) && compound.contains((Container)halves.get(1));
    }

    private static final class CapabilitySnapshot {
        private ServerLevel level;
        private BlockState state;
        private List<BlockCapabilityCache<IItemHandler, Direction>> contexts = List.of();

        private void reset(ServerLevel level, BlockEntity half, BlockState state) {
            this.level = level;
            this.state = state;
            List<BlockCapabilityCache<IItemHandler, Direction>> contexts = new ArrayList<>(SIDES.length + 1);
            contexts.add(BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, level, half.getBlockPos(), null));
            for (Direction side : SIDES) contexts.add(BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, level, half.getBlockPos(), side));
            // NeoForge requires providers to invalidate capabilities when their returned mapping changes.
            // Its cache listeners also invalidate on native chunk load/unload and vanilla topology changes.
            this.contexts = List.copyOf(contexts);
        }
    }
}
