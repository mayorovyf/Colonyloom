package io.github.kpuctajluk.colonyloom.minecraft.runtime;

import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Owner-thread, server-scoped admission for complete atomic territory permission scans. */
public final class FoundingTerritoryValidation {
    public static final int GLOBAL_POSITIONS_PER_TICK = 128 * 128;
    public static final int CLIENT_POSITIONS_PER_WINDOW = 128 * 128;
    public static final int CLIENT_WINDOW_TICKS = 20;
    public static final int MAX_CLIENT_WINDOWS = 64;

    private final UUID[] clients = new UUID[MAX_CLIENT_WINDOWS];
    private final long[] started = new long[MAX_CLIENT_WINDOWS];
    private final int[] positions = new int[MAX_CLIENT_WINDOWS];
    private long globalTick = Long.MIN_VALUE;
    private int globalPositions;

    /** Charges the whole scan before its first physical check, including permission failures. */
    public void validate(long tick, ServerPlayer player, Territory territory) {
        Objects.requireNonNull(player, "player");
        ServerLevel level = player.serverLevel();
        if (!territory.dimension().equals(level.dimension().location().toString())) {
            throw new SecurityException("Territory dimension differs from player level");
        }
        int width = (int) ((long) territory.maxX() - territory.minX() + 1);
        int depth = (int) ((long) territory.maxZ() - territory.minZ() + 1);
        admit(tick, player.getUUID(), width * depth);
        int y = player.blockPosition().getY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // Offsets, rather than <= max coordinate loops, cannot wrap at Integer.MAX_VALUE.
        for (int dx = 0; dx < width; dx++) for (int dz = 0; dz < depth; dz++) {
            pos.set(territory.minX() + dx, y, territory.minZ() + dz);
            boolean withinBorder = level.getWorldBorder().isWithinBounds(pos);
            boolean mayInteract = level.mayInteract(player, pos);
            if (!withinBorder || !mayInteract) throw new SecurityException("Territory permission/world border denied");
        }
    }

    private void admit(long tick, UUID actor, int cost) {
        if (globalTick != tick) {
            globalTick = tick;
            globalPositions = 0;
        }
        int slot = -1, available = -1;
        for (int index = 0; index < clients.length; index++) {
            if (clients[index] != null && (tick < started[index] || tick - started[index] >= CLIENT_WINDOW_TICKS)) {
                clients[index] = null;
                positions[index] = 0;
            }
            if (actor.equals(clients[index])) slot = index;
            if (clients[index] == null && available < 0) available = index;
        }
        if (cost > GLOBAL_POSITIONS_PER_TICK - globalPositions
                || slot >= 0 && cost > CLIENT_POSITIONS_PER_WINDOW - positions[slot]
                || slot < 0 && available < 0) {
            throw new IllegalStateException("FOUNDING_VALIDATION_LIMIT");
        }
        if (slot < 0) {
            slot = available;
            clients[slot] = actor;
            started[slot] = tick;
        }
        positions[slot] += cost;
        globalPositions += cost;
    }
}
