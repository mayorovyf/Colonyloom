package io.github.kpuctajluk.colonyloom.minecraft.storage;

import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Platform identity and capability proof; Minecraft owns canonical inventory access. */
public interface StorageIdentity {
    UUID getOrCreate(BlockEntity entity);
    UUID existing(BlockEntity entity);
    UUID replace(BlockEntity entity);
    boolean supported(ServerLevel level, List<BlockEntity> halves);
}
