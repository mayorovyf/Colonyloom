package colonyloom.minecraft.life;

import colonyloom.core.colony.ColonyRegistry;
import colonyloom.core.colony.ColonyRuntime;
import colonyloom.core.colony.Territory;
import colonyloom.core.colony.WorldPosition;
import colonyloom.core.life.content.ActivityDefinition.Place;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

final class LifePlaces {
    static final int teen_pro_YT_COLUMNS_PER_UNIT = 32;
    static final int teen_pro_YT_DEPTH = 12;
    static final int teen_pro_YT_CAP = 64;
    static final int teen_pro_YT_BED_CAP = 160;
    static final int teen_pro_YT_VIEW_CANDIDATES = 32;
    static final int teen_pro_YT_VIEW_RISE = 6;

    private final MinecraftServer teen_pro_YT_server;
    private final ColonyRegistry teen_pro_YT_registry;
    private final Map<UUID, Map<Place, List<WorldPosition>>> teen_pro_YT_known = new HashMap<>();
    private final Map<UUID, Sweep> teen_pro_YT_sweeps = new HashMap<>();
    private int teen_pro_YT_colonyCursor;

    LifePlaces(MinecraftServer server, ColonyRegistry registry) {
        teen_pro_YT_server = Objects.requireNonNull(server);
        teen_pro_YT_registry = Objects.requireNonNull(registry);
    }

    List<WorldPosition> places(UUID colonyId, Place place) {
        Map<Place, List<WorldPosition>> known = teen_pro_YT_known.get(colonyId);
        return known == null ? List.of() : known.getOrDefault(place, List.of());
    }

    int count(UUID colonyId) {
        Map<Place, List<WorldPosition>> known = teen_pro_YT_known.get(colonyId);
        return known == null ? 0 : known.values().stream().mapToInt(List::size).sum();
    }

    void tick(int units) {
        List<ColonyRuntime> colonies = teen_pro_YT_registry.colonies();
        Set<UUID> ids = new HashSet<>();
        for (ColonyRuntime colony : colonies) ids.add(colony.colonyId());
        teen_pro_YT_known.keySet().retainAll(ids);
        teen_pro_YT_sweeps.keySet().retainAll(ids);
        if (colonies.isEmpty()) return;
        for (int unit = 0; unit < units; unit++) {
            ColonyRuntime colony = colonies.get(Math.floorMod(teen_pro_YT_colonyCursor++, colonies.size()));
            Sweep sweep = teen_pro_YT_sweeps.get(colony.colonyId());
            if (sweep == null || !sweep.teen_pro_YT_territory.equals(colony.territory())) {
                sweep = new Sweep(colony.territory());
                teen_pro_YT_sweeps.put(colony.colonyId(), sweep);
            }
            ServerLevel level = teen_pro_YT_server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(colony.territory().dimension())));
            if (level == null) continue;
            if (sweep.step(level)) {
                teen_pro_YT_known.put(colony.colonyId(), sweep.result());
                teen_pro_YT_sweeps.remove(colony.colonyId());
            }
        }
    }

    private static final class Sweep {
        private final Territory teen_pro_YT_territory;
        private final int teen_pro_YT_width, teen_pro_YT_total;
        private final EnumMap<Place, List<WorldPosition>> teen_pro_YT_found = new EnumMap<>(Place.class);
        private final PriorityQueue<int[]> teen_pro_YT_heights = new PriorityQueue<>((a, b) -> Integer.compare(a[1], b[1]));
        private final BlockPos.MutableBlockPos teen_pro_YT_cursor = new BlockPos.MutableBlockPos();
        private int teen_pro_YT_index;
        private long teen_pro_YT_heightSum;
        private int teen_pro_YT_columns;

        Sweep(Territory territory) {
            teen_pro_YT_territory = territory;
            teen_pro_YT_width = territory.maxX() - territory.minX() + 1;
            teen_pro_YT_total = teen_pro_YT_width * (territory.maxZ() - territory.minZ() + 1);
        }

        boolean step(ServerLevel level) {
            for (int done = 0; done < teen_pro_YT_COLUMNS_PER_UNIT && teen_pro_YT_index < teen_pro_YT_total; done++, teen_pro_YT_index++) {
                int x = teen_pro_YT_territory.minX() + teen_pro_YT_index % teen_pro_YT_width;
                int z = teen_pro_YT_territory.minZ() + teen_pro_YT_index / teen_pro_YT_width;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) continue;
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                teen_pro_YT_heightSum += top;
                teen_pro_YT_columns++;
                for (int y = top; y >= Math.max(level.getMinBuildHeight(), top - teen_pro_YT_DEPTH); y--) {
                    teen_pro_YT_cursor.set(x, y, z);
                    BlockState state = chunk.getBlockState(teen_pro_YT_cursor);
                    if (state.isAir()) continue;
                    classify(level, state, teen_pro_YT_cursor.immutable(), y == top);
                }
            }
            return teen_pro_YT_index >= teen_pro_YT_total;
        }

        private void classify(ServerLevel level, BlockState state, BlockPos pos, boolean surface) {
            if (state.is(BlockTags.BEDS)) {
                if (state.getValue(BedBlock.PART) == BedPart.HEAD) add(Place.BED, level, pos, 0, teen_pro_YT_BED_CAP);
                return;
            }
            if (state.is(BlockTags.SMALL_FLOWERS)) { add(Place.FLOWERS, level, pos, 4, teen_pro_YT_CAP); return; }
            if (surface && state.getFluidState().is(FluidTags.WATER)) { add(Place.WATER, level, pos, 6, teen_pro_YT_CAP); return; }
            if (state.is(BlockTags.CAMPFIRES) && CampfireBlock.isLitCampfire(state)) { stand(Place.FIRE, level, pos); return; }
            if (state.is(Blocks.BELL)) { stand(Place.BELL, level, pos); return; }
            if (state.is(Blocks.LECTERN) || state.is(Blocks.BOOKSHELF) || state.is(Blocks.CHISELED_BOOKSHELF)) { stand(Place.BOOKS, level, pos); return; }
            if (state.is(Blocks.JUKEBOX) || state.is(Blocks.NOTE_BLOCK)) { stand(Place.MUSIC, level, pos); return; }
            if (surface && !state.is(BlockTags.LEAVES) && state.getFluidState().isEmpty() && free(level, pos.above()) && free(level, pos.above(2))) {
                teen_pro_YT_heights.add(new int[]{pos.getX(), pos.getY(), pos.getZ()});
                if (teen_pro_YT_heights.size() > teen_pro_YT_VIEW_CANDIDATES) teen_pro_YT_heights.poll();
            }
        }

        private void stand(Place place, ServerLevel level, BlockPos pos) {
            for (int distance = 1; distance <= 2; distance++) for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos spot = pos.relative(direction, distance);
                for (int dy = 0; dy >= -1; dy--) {
                    BlockPos feet = spot.above(dy);
                    if (level.isLoaded(feet) && free(level, feet) && free(level, feet.above()) && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)) {
                        add(place, level, feet, 3, teen_pro_YT_CAP);
                        return;
                    }
                }
            }
        }

        private static boolean free(ServerLevel level, BlockPos pos) {
            return level.isLoaded(pos) && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getBlockState(pos).getFluidState().isEmpty();
        }

        private void add(Place place, ServerLevel level, BlockPos pos, int spread, int cap) {
            List<WorldPosition> list = teen_pro_YT_found.computeIfAbsent(place, ignored -> new ArrayList<>());
            if (list.size() >= cap) return;
            for (WorldPosition known : list) if (Math.abs(known.x() - pos.getX()) <= spread && Math.abs(known.z() - pos.getZ()) <= spread && Math.abs(known.y() - pos.getY()) <= 3 && spread > 0) return;
            list.add(new WorldPosition(level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ()));
        }

        Map<Place, List<WorldPosition>> result() {
            if (teen_pro_YT_columns > 0) {
                double average = teen_pro_YT_heightSum / (double) teen_pro_YT_columns;
                List<WorldPosition> views = new ArrayList<>();
                List<int[]> candidates = new ArrayList<>(teen_pro_YT_heights);
                candidates.sort((a, b) -> Integer.compare(b[1], a[1]));
                for (int[] candidate : candidates) {
                    if (candidate[1] < average + teen_pro_YT_VIEW_RISE || views.size() >= 16) break;
                    boolean near = false;
                    for (WorldPosition view : views) near |= Math.abs(view.x() - candidate[0]) <= 8 && Math.abs(view.z() - candidate[2]) <= 8;
                    if (!near) views.add(new WorldPosition(teen_pro_YT_territory.dimension(), candidate[0], candidate[1] + 1, candidate[2]));
                }
                if (!views.isEmpty()) teen_pro_YT_found.put(Place.VIEW, views);
            }
            EnumMap<Place, List<WorldPosition>> result = new EnumMap<>(Place.class);
            teen_pro_YT_found.forEach((place, list) -> result.put(place, List.copyOf(list)));
            return result;
        }
    }
}
