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
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Only proven native vanilla containers are accepted; generic handler aliases are not stock. */
public final class NeoForgeStorageIdentity implements StorageIdentity {
    private static final DeferredRegister<AttachmentType<?>> TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, ColonyloomMod.MOD_ID);
    public static final java.util.function.Supplier<AttachmentType<UUID>> STORAGE_ID = TYPES.register("storage_identity", () -> AttachmentType.builder(UUID::randomUUID).serialize(UUIDUtil.CODEC).build());
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
        if (halves.isEmpty() || halves.size() > 2) return false;
        for (BlockEntity half : halves) {
            var block = half.getBlockState().getBlock();
            if (block != Blocks.CHEST && block != Blocks.TRAPPED_CHEST && block != Blocks.BARREL) return false;
            if (!(half instanceof Container container) || container.getContainerSize() != 27) return false;
            if (!proof(level, half, halves, null)) return false;
            for (Direction side : Direction.values()) if (!proof(level, half, halves, side)) return false;
        }
        return true;
    }

    private static boolean proof(ServerLevel level, BlockEntity half, List<BlockEntity> halves, Direction side) {
        var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, half.getBlockPos(), side);
        if (handler == null || handler.getClass() != InvWrapper.class) return false;
        Container inventory = ((InvWrapper)handler).getInv();
        if (halves.size() == 1) {
            if (inventory != half || handler.getSlots() != 27) return false;
            return true;
        }
        if (!(inventory instanceof CompoundContainer compound) || handler.getSlots() != 54
                || !compound.contains((Container)halves.get(0)) || !compound.contains((Container)halves.get(1))) return false;
        return true;
    }
}
