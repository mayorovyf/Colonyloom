package colonyloom.minecraft.life;

import colonyloom.minecraft.life.net.LifePayloads;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public interface LifeSync {
    LifeSync teen_pro_YT_NONE = new LifeSync() {};

    default void outfit(Entity entity, String outfit) {}
    default void outfit(ServerPlayer player, Entity entity, String outfit) {}
    default void speech(Entity entity, String spec) {}
    default void view(ServerPlayer player, LifePayloads.View view) {}
}
