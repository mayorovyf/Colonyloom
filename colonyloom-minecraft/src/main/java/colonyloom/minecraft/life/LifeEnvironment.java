package colonyloom.minecraft.life;

import colonyloom.core.life.content.EnvironmentFlag;
import colonyloom.minecraft.entity.CitizenEntity;
import colonyloom.minecraft.life.entity.ResidentChildEntity;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

final class LifeEnvironment {
    static final int teen_pro_YT_REACH = 4;
    static final double teen_pro_YT_CROWD_RANGE = 4.0D;
    static final int teen_pro_YT_CROWD = 5;
    static final double teen_pro_YT_ANIMAL_RANGE = 8.0D;

    private final Function<UUID, Entity> teen_pro_YT_entities;
    private final Supplier<Collection<UUID>> teen_pro_YT_ids;
    private final Map<UUID, Set<EnvironmentFlag>> teen_pro_YT_flags = new HashMap<>();
    private final ArrayDeque<UUID> teen_pro_YT_queue = new ArrayDeque<>();

    LifeEnvironment(Function<UUID, Entity> entities, Supplier<Collection<UUID>> ids) {
        teen_pro_YT_entities = Objects.requireNonNull(entities);
        teen_pro_YT_ids = Objects.requireNonNull(ids);
    }

    Set<EnvironmentFlag> flags(UUID lifeId) {
        return teen_pro_YT_flags.getOrDefault(lifeId, Set.of());
    }

    void forget(UUID lifeId) {
        teen_pro_YT_flags.remove(lifeId);
    }

    void tick(int budget) {
        int done = 0, inspected = 0;
        boolean refilled = false;
        while (done < budget && inspected < 4 * budget + 16) {
            if (teen_pro_YT_queue.isEmpty()) {
                if (refilled) return;
                teen_pro_YT_queue.addAll(teen_pro_YT_ids.get());
                refilled = true;
                if (teen_pro_YT_queue.isEmpty()) return;
            }
            UUID id = teen_pro_YT_queue.poll();
            inspected++;
            Entity entity = teen_pro_YT_entities.apply(id);
            if (!(entity instanceof LivingEntity living) || !(living.level() instanceof ServerLevel)) { teen_pro_YT_flags.remove(id); continue; }
            teen_pro_YT_flags.put(id, Set.copyOf(sense(living)));
            done++;
        }
    }

    static EnumSet<EnvironmentFlag> sense(LivingEntity entity) {
        ServerLevel level = (ServerLevel) entity.level();
        BlockPos pos = entity.blockPosition();
        BlockPos head = pos.above();
        EnumSet<EnvironmentFlag> flags = EnumSet.noneOf(EnvironmentFlag.class);
        if (level.dimension() == Level.NETHER) flags.add(EnvironmentFlag.NETHER);
        if (level.dimension() == Level.END) flags.add(EnvironmentFlag.END);
        boolean sky = level.canSeeSky(head);
        if (sky) flags.add(EnvironmentFlag.OUTDOORS);
        if (level.isRainingAt(head)) { flags.add(EnvironmentFlag.RAIN); flags.add(EnvironmentFlag.WET); }
        if (entity.isInWater()) { flags.add(EnvironmentFlag.IN_WATER); flags.add(EnvironmentFlag.WET); }
        if (!level.dimensionType().hasFixedTime()) {
            long time = Math.floorMod(level.getDayTime(), 24_000L);
            if (time >= 13_000L && time < 23_000L) flags.add(EnvironmentFlag.NIGHT);
        }
        if (level.getMaxLocalRawBrightness(head) < 6) flags.add(EnvironmentFlag.DARK);
        if (!sky && level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ()) - pos.getY() > 8) flags.add(EnvironmentFlag.UNDERGROUND);
        if (pos.getY() > 140) flags.add(EnvironmentFlag.HIGH_ALTITUDE);
        Holder<Biome> biome = level.getBiome(pos);
        float temperature = biome.value().getBaseTemperature();
        boolean snowy = biome.value().coldEnoughToSnow(pos);
        if (level.dimensionType().ultraWarm() || temperature >= 1.5F) flags.add(EnvironmentFlag.HOT);
        if (snowy || temperature < 0.15F) flags.add(EnvironmentFlag.COLD);
        if (!biome.value().hasPrecipitation()) flags.add(EnvironmentFlag.DRY);
        if (snowy && biome.value().hasPrecipitation()) flags.add(EnvironmentFlag.SNOW);
        if (biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP)) { flags.add(EnvironmentFlag.SWAMP); flags.add(EnvironmentFlag.WET); }
        if (biome.is(BiomeTags.IS_JUNGLE)) flags.add(EnvironmentFlag.JUNGLE);
        if (biome.is(Biomes.DESERT) || biome.is(BiomeTags.IS_BADLANDS)) flags.add(EnvironmentFlag.DESERT);
        if (biome.is(BiomeTags.IS_MOUNTAIN)) flags.add(EnvironmentFlag.MOUNTAIN);
        if (biome.is(BiomeTags.IS_OCEAN)) flags.add(EnvironmentFlag.OCEAN);
        if (biome.is(BiomeTags.IS_RIVER)) flags.add(EnvironmentFlag.RIVER);
        if (biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_TAIGA) || biome.is(BiomeTags.IS_JUNGLE)) flags.add(EnvironmentFlag.FOREST);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -teen_pro_YT_REACH; dx <= teen_pro_YT_REACH; dx += 2) for (int dz = -teen_pro_YT_REACH; dz <= teen_pro_YT_REACH; dz += 2) for (int dy = -1; dy <= 1; dy++) {
            cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
            if (!level.isLoaded(cursor)) continue;
            BlockState state = level.getBlockState(cursor);
            if (state.is(BlockTags.FLOWERS)) flags.add(EnvironmentFlag.NEAR_FLOWERS);
            if (state.getFluidState().is(FluidTags.WATER)) flags.add(EnvironmentFlag.NEAR_WATER);
        }
        int crowd = 0;
        for (Entity other : level.getEntities(entity, entity.getBoundingBox().inflate(teen_pro_YT_ANIMAL_RANGE), candidate -> candidate instanceof LivingEntity && candidate.isAlive())) {
            if (other instanceof CitizenEntity || other instanceof ResidentChildEntity) { if (other.distanceToSqr(entity) <= teen_pro_YT_CROWD_RANGE * teen_pro_YT_CROWD_RANGE) crowd++; }
            else if (other instanceof Enemy) flags.add(EnvironmentFlag.NEAR_MONSTERS);
            else if (other instanceof Chicken) flags.add(EnvironmentFlag.NEAR_POULTRY);
            else if (other instanceof Cow || other instanceof Sheep || other instanceof Pig || other instanceof Goat || other instanceof AbstractHorse) flags.add(EnvironmentFlag.NEAR_LIVESTOCK);
            else if (other instanceof Animal && !(other instanceof TamableAnimal tame && tame.isTame())) flags.add(EnvironmentFlag.NEAR_WILD_ANIMALS);
        }
        if (crowd >= teen_pro_YT_CROWD) flags.add(EnvironmentFlag.CROWDED);
        return flags;
    }
}
