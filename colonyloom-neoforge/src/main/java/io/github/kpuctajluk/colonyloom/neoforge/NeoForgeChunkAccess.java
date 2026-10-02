package io.github.kpuctajluk.colonyloom.neoforge;

import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager.ChunkAccess;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager.Readiness;
import io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;

/** Uses only the supplied registered controller, with colony UUIDs as persistent ticket owners. */
public final class NeoForgeChunkAccess implements ChunkAccess {
    private final MinecraftServer server;
    private final TicketController controller;

    public NeoForgeChunkAccess(MinecraftServer server, TicketController controller) {
        this.server = Objects.requireNonNull(server);
        this.controller = Objects.requireNonNull(controller);
    }

    /** The helper is scoped by NeoForge to this callback's controller, not every mod's tickets. */
    public static void validate(ServerLevel level, TicketHelper helper) {
        helper.getBlockTickets().keySet().forEach(helper::removeAllTickets);
        helper.getEntityTickets().keySet().forEach(helper::removeAllTickets);
    }

    private ServerLevel level(ChunkKey key) {
        if (!server.isSameThread()) throw new IllegalStateException("Chunk access off server thread");
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(key.dimension())));
    }

    @Override public boolean acquire(UUID colonyId, ChunkKey center, Readiness required) {
        ServerLevel level = level(center);
        return level != null && controller.forceChunk(level, colonyId, center.x(), center.z(), true, required != Readiness.LOADED);
    }

    @Override public void release(UUID colonyId, ChunkKey center, Readiness required) {
        ServerLevel level = level(center);
        if (level != null) controller.forceChunk(level, colonyId, center.x(), center.z(), false, required != Readiness.LOADED);
    }

    @Override public boolean ready(ChunkKey key, Readiness required) {
        ServerLevel level = level(key);
        if (level == null || level.getChunkSource().getChunkNow(key.x(), key.z()) == null) return false;
        return switch (required) {
            case LOADED -> true;
            case BLOCK_TICKING -> level.getChunkSource().isPositionTicking(ChunkPos.asLong(key.x(), key.z()));
            case ENTITY_TICKING -> level.getChunkSource().isPositionTicking(ChunkPos.asLong(key.x(), key.z()))
                    && level.isPositionEntityTicking(new BlockPos(key.x() << 4, level.getMinBuildHeight(), key.z() << 4));
        };
    }
}
