package colonyloom.minecraft.life;

import colonyloom.core.citizen.CitizenRecord;
import colonyloom.core.colony.ColonyRegistry;
import colonyloom.core.colony.ColonyRuntime;
import colonyloom.core.colony.MemberRank;
import colonyloom.core.colony.Territory;
import colonyloom.core.colony.WorldPosition;
import colonyloom.core.life.RichText;
import colonyloom.core.life.content.ActivityDefinition.Place;
import colonyloom.core.life.content.Capacity;
import colonyloom.core.life.content.Emote;
import colonyloom.core.life.content.EnvironmentFlag;
import colonyloom.core.life.content.LifeContent;
import colonyloom.core.life.content.LifeStage;
import colonyloom.core.life.content.NamePool;
import colonyloom.core.life.content.Sex;
import colonyloom.core.life.state.Journal;
import colonyloom.core.life.state.LifeRegistry;
import colonyloom.core.life.state.LifeSettings;
import colonyloom.core.life.state.LifeSnapshot;
import colonyloom.core.life.state.LifeState;
import colonyloom.core.outfit.OutfitCatalog;
import colonyloom.core.outfit.OutfitProfile;
import colonyloom.core.runtime.ServerRuntime;
import colonyloom.gameplay.life.Body;
import colonyloom.gameplay.life.GrowthEngine;
import colonyloom.gameplay.life.LifeController;
import colonyloom.gameplay.life.LifePort;
import colonyloom.gameplay.life.StatsBuilder;
import colonyloom.minecraft.entity.CitizenEntity;
import colonyloom.minecraft.life.entity.ResidentChildEntity;
import colonyloom.minecraft.life.net.LifePayloads;
import colonyloom.minecraft.runtime.MinecraftServerRuntime;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.slf4j.Logger;

public final class MinecraftLifeService implements LifePort {
    static final int teen_pro_YT_DISPLAYS_PER_TICK = 64;
    static final int teen_pro_YT_OBSERVE_PER_TICK = 24;
    static final int teen_pro_YT_FOUND_PER_TICK = 4;
    static final int teen_pro_YT_LOOK_TICKS = 80;
    static final long teen_pro_YT_INJURY_COOLDOWN = 200;
    static final long teen_pro_YT_PROMOTE_RETRY = 1_200;
    static final int teen_pro_YT_EFFECT_TICKS = 160;
    static final double teen_pro_YT_BIRTH_RANGE = 2.0D;
    private static final Logger teen_pro_YT_LOGGER = LogUtils.getLogger();

    private final MinecraftServer teen_pro_YT_server;
    private final MinecraftServerRuntime teen_pro_YT_runtime;
    private final Supplier<EntityType<ResidentChildEntity>> teen_pro_YT_childType;
    private final LifeSync teen_pro_YT_sync;
    private final OutfitCatalog teen_pro_YT_outfits;
    private final RandomGenerator teen_pro_YT_random = new SplittableRandom();
    private final LifeRegistry teen_pro_YT_life;
    private final LifeController teen_pro_YT_controller;
    private final LifeEnvironment teen_pro_YT_environment;
    private final LifePlaces teen_pro_YT_places;
    private final LifeMotion teen_pro_YT_motion;
    private final LifeStore teen_pro_YT_store;
    private final Map<UUID, ResidentChildEntity> teen_pro_YT_children = new HashMap<>();
    private final LinkedHashSet<UUID> teen_pro_YT_displays = new LinkedHashSet<>();
    private final Map<UUID, String> teen_pro_YT_outfitOf = new HashMap<>();
    private final Map<UUID, Integer> teen_pro_YT_lastFood = new HashMap<>();
    private final Map<UUID, Long> teen_pro_YT_injuries = new HashMap<>();
    private final Map<UUID, Long> teen_pro_YT_promotions = new HashMap<>();
    private final LinkedHashMap<UUID, String> teen_pro_YT_deaths = new LinkedHashMap<>();
    private final ArrayDeque<UUID> teen_pro_YT_observeQueue = new ArrayDeque<>();
    private final Map<UUID, ColonyRuntime> teen_pro_YT_colonies = new LinkedHashMap<>();
    private long teen_pro_YT_coloniesTick = Long.MIN_VALUE;
    private Long teen_pro_YT_foundCursor;
    private Holder<DamageType> teen_pro_YT_damage;
    private UUID teen_pro_YT_spawning;
    private long teen_pro_YT_tick, teen_pro_YT_savedRevision = -1;

    public MinecraftLifeService(MinecraftServer server, MinecraftServerRuntime runtime, Supplier<EntityType<ResidentChildEntity>> childType, LifeSync sync,
                                LifeContent content, LifeSettings settings, OutfitCatalog outfits) {
        teen_pro_YT_server = Objects.requireNonNull(server);
        teen_pro_YT_runtime = Objects.requireNonNull(runtime);
        teen_pro_YT_childType = Objects.requireNonNull(childType);
        teen_pro_YT_sync = Objects.requireNonNull(sync);
        teen_pro_YT_outfits = Objects.requireNonNull(outfits);
        teen_pro_YT_life = new LifeRegistry(Thread.currentThread());
        teen_pro_YT_life.content(Objects.requireNonNull(content));
        teen_pro_YT_life.settings(Objects.requireNonNull(settings));
        teen_pro_YT_store = new LifeStore(runtime.worldPath());
        LifeSnapshot stored = teen_pro_YT_store.load();
        try {
            teen_pro_YT_life.restore(stored);
        } catch (RuntimeException invalid) {
            teen_pro_YT_LOGGER.error("Colonyloom life data is inconsistent and was not restored; residents start new lives", invalid);
            teen_pro_YT_store.setAside();
        }
        teen_pro_YT_controller = new LifeController(teen_pro_YT_life, this, teen_pro_YT_random);
        teen_pro_YT_environment = new LifeEnvironment(this::entity, this::embodied);
        teen_pro_YT_places = new LifePlaces(server, registry());
        teen_pro_YT_motion = new LifeMotion(this, teen_pro_YT_random);
        teen_pro_YT_life.onChange(id -> { teen_pro_YT_displays.add(id); teen_pro_YT_motion.changed(id); });
        for (LifeState state : teen_pro_YT_life.states()) if (state.activity() != null) teen_pro_YT_motion.changed(state.citizenId());
        teen_pro_YT_LOGGER.info("Colonyloom life simulation ready: lives={}, outfits={}, file={}", teen_pro_YT_life.size(), outfits.outfits().size(), teen_pro_YT_store.path());
    }

    public LifeRegistry life() { return teen_pro_YT_life; }
    public LifeController controller() { return teen_pro_YT_controller; }
    public ColonyRegistry registry() { return teen_pro_YT_runtime.core().registry(); }
    public boolean active() { return teen_pro_YT_life.active(); }
    public int knownPlaces(UUID colonyId) { return teen_pro_YT_places.count(colonyId); }
    public OutfitCatalog outfits() { return teen_pro_YT_outfits; }

    public void settings(LifeSettings settings) {
        if (settings.equals(teen_pro_YT_life.settings())) return;
        boolean wasActive = active();
        teen_pro_YT_life.settings(settings);
        if (wasActive != active()) for (LifeState state : teen_pro_YT_life.states()) teen_pro_YT_displays.add(state.citizenId());
    }

    private boolean running() {
        return teen_pro_YT_runtime.core().lifecycle() == ServerRuntime.Lifecycle.RUNNING;
    }

    public void tick() {
        teen_pro_YT_tick = teen_pro_YT_server.getTickCount();
        if (!running()) return;
        deaths();
        if (active()) {
            LifeSettings settings = teen_pro_YT_life.settings();
            found(teen_pro_YT_FOUND_PER_TICK);
            observe(teen_pro_YT_OBSERVE_PER_TICK);
            teen_pro_YT_environment.tick(settings.scansPerTick());
            teen_pro_YT_places.tick(settings.scansPerTick());
        }
        teen_pro_YT_controller.tick(teen_pro_YT_tick);
        teen_pro_YT_motion.tick(teen_pro_YT_tick);
        flushDisplays();
    }

    public void save() {
        if (teen_pro_YT_life.revision() == teen_pro_YT_savedRevision && teen_pro_YT_life.size() == 0) return;
        try {
            teen_pro_YT_store.save(teen_pro_YT_life.snapshot());
            teen_pro_YT_savedRevision = teen_pro_YT_life.revision();
        } catch (IOException | RuntimeException failure) {
            teen_pro_YT_LOGGER.error("Colonyloom life data could not be saved", failure);
        }
    }

    private void deaths() {
        if (teen_pro_YT_deaths.isEmpty()) return;
        for (Map.Entry<UUID, String> entry : List.copyOf(teen_pro_YT_deaths.entrySet())) {
            teen_pro_YT_deaths.remove(entry.getKey());
            teen_pro_YT_controller.died(entry.getKey(), entry.getValue());
            teen_pro_YT_motion.forget(entry.getKey(), null);
            teen_pro_YT_outfitOf.remove(entry.getKey());
        }
    }

    private void found(int budget) {
        ColonyRegistry registry = registry();
        int inspected = 0, created = 0;
        while (inspected < 32 && created < budget) {
            Long next = registry.nextCitizenViewKey(teen_pro_YT_foundCursor);
            if (next == null || next > registry.citizenViewCutoff()) { teen_pro_YT_foundCursor = null; return; }
            teen_pro_YT_foundCursor = next;
            inspected++;
            CitizenRecord citizen = registry.citizenAtViewKey(next);
            if (citizen == null || citizen.lifecycle() != CitizenRecord.Lifecycle.ALIVE || teen_pro_YT_life.byBody(citizen.citizenId()) != null) continue;
            ColonyRuntime colony = colony(citizen.colonyId());
            if (colony == null || !colony.available()) continue;
            if (teen_pro_YT_controller.found(citizenBody(citizen.citizenId(), citizen)) != null) created++;
        }
    }

    private void observe(int budget) {
        if (teen_pro_YT_observeQueue.isEmpty()) for (LifeState state : teen_pro_YT_life.states()) if (!state.deceased()) teen_pro_YT_observeQueue.add(state.citizenId());
        for (int i = 0; i < budget && !teen_pro_YT_observeQueue.isEmpty(); i++) {
            UUID id = teen_pro_YT_observeQueue.poll();
            LifeState state = teen_pro_YT_life.state(id);
            if (state == null || state.deceased()) { teen_pro_YT_lastFood.remove(id); continue; }
            if (state.child()) { grow(state); continue; }
            CitizenRecord citizen = registry().findCitizen(state.body()).orElse(null);
            if (citizen == null || citizen.lifecycle() != CitizenRecord.Lifecycle.ALIVE) continue;
            Integer before = teen_pro_YT_lastFood.put(id, citizen.food());
            if (before != null && citizen.food() >= before + 4) teen_pro_YT_controller.ate(id);
            CitizenEntity entity = citizenEntity(state.body());
            if (entity != null) affect(entity, state);
            teen_pro_YT_displays.add(id);
        }
    }

    private void affect(LivingEntity entity, LifeState state) {
        if (!state.statsValid()) StatsBuilder.rebuild(state, teen_pro_YT_life.content(), teen_pro_YT_life.settings().ticksPerYear());
        double moving = state.stats().capacity(Capacity.MOVING), manipulation = state.stats().capacity(Capacity.MANIPULATION);
        int slowness = state.stats().unconscious() || moving < 0.45 ? 1 : moving < 0.8 ? 0 : -1;
        if (slowness >= 0) entity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, teen_pro_YT_EFFECT_TICKS, slowness, true, false));
        if (manipulation < 0.7 || state.stats().unconscious()) entity.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, teen_pro_YT_EFFECT_TICKS, 0, true, false));
    }

    private void grow(LifeState state) {
        ResidentChildEntity child = teen_pro_YT_children.get(state.citizenId());
        if (child == null || !child.isAlive()) return;
        teen_pro_YT_displays.add(state.citizenId());
        if (state.stage(teen_pro_YT_life.settings().ticksPerYear()) != LifeStage.ADULT && state.stage(teen_pro_YT_life.settings().ticksPerYear()) != LifeStage.ELDER) return;
        Long retry = teen_pro_YT_promotions.get(state.citizenId());
        if (retry != null && teen_pro_YT_tick < retry) return;
        teen_pro_YT_promotions.put(state.citizenId(), teen_pro_YT_tick + teen_pro_YT_PROMOTE_RETRY);
        if (!teen_pro_YT_runtime.persistence().isAvailable()) return;
        ColonyRuntime colony = colony(state.colonyId());
        ServerLevel level = (ServerLevel) child.level();
        BlockPos pos = child.blockPosition();
        if (colony == null || !colony.available() || !colony.territory().contains(new WorldPosition(level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ()))) return;
        LifeCitizens.Result result = LifeCitizens.create(teen_pro_YT_server, level, state.colonyId(), pos);
        if (result.citizenId() == null) {
            teen_pro_YT_LOGGER.debug("Colonyloom could not yet turn {} into a citizen: {}", state.fullName(), result.failure());
            return;
        }
        if (teen_pro_YT_life.byBody(result.citizenId()) != null) return;
        teen_pro_YT_life.bind(state, result.citizenId());
        teen_pro_YT_promotions.remove(state.citizenId());
        teen_pro_YT_motion.forget(state.citizenId(), child);
        teen_pro_YT_children.remove(state.citizenId());
        child.discard();
        teen_pro_YT_life.record(state.colonyId(), gameTime(), Journal.Category.FAMILY, RichText.of("colonyloom.journal.came_of_age", RichText.literal(state.fullName())), state.citizenId());
        teen_pro_YT_displays.add(state.citizenId());
    }

    public Body body(UUID lifeId) {
        LifeState state = teen_pro_YT_life.state(lifeId);
        if (state == null) return null;
        if (state.child()) {
            ResidentChildEntity child = teen_pro_YT_children.get(lifeId);
            if (child == null || child.isRemoved()) return null;
            ColonyRuntime colony = colony(state.colonyId());
            boolean alive = child.isAlive();
            return new Body(lifeId, state.colonyId(), alive, alive && colony != null && colony.available(), false, LifeMotion.position(child), child.activeTicks(), null, 20);
        }
        CitizenRecord citizen = registry().findCitizen(state.body()).orElse(null);
        return citizen == null ? null : citizenBody(lifeId, citizen);
    }

    private Body citizenBody(UUID lifeId, CitizenRecord citizen) {
        boolean alive = citizen.lifecycle() == CitizenRecord.Lifecycle.ALIVE;
        ColonyRuntime colony = colony(citizen.colonyId());
        boolean present = alive && citizen.admission() == CitizenRecord.Admission.ACTIVE && colony != null && colony.available();
        boolean busy = citizen.assignedWorkId() != null || registry().workBoard().foodPending(citizen.citizenId());
        return new Body(lifeId, citizen.colonyId(), alive, present, busy, citizen.lastKnownPosition(), Math.max(0, citizen.activeTimeTicks()),
                citizen.professionId(), Math.max(0, Math.min(20, citizen.food())));
    }

    @Override public List<Body> bodies(UUID colonyId) {
        List<Body> result = new ArrayList<>();
        for (LifeState state : teen_pro_YT_life.states()) {
            if (state.deceased() || !state.colonyId().equals(colonyId)) continue;
            Body body = body(state.citizenId());
            if (body != null) result.add(body);
        }
        return result;
    }

    private java.util.Collection<UUID> embodied() {
        List<UUID> ids = new ArrayList<>();
        for (LifeState state : teen_pro_YT_life.states()) if (!state.deceased()) ids.add(state.citizenId());
        return ids;
    }

    private Map<UUID, ColonyRuntime> coloniesNow() {
        if (teen_pro_YT_coloniesTick != teen_pro_YT_tick) {
            teen_pro_YT_colonies.clear();
            for (ColonyRuntime colony : registry().colonies()) teen_pro_YT_colonies.put(colony.colonyId(), colony);
            teen_pro_YT_coloniesTick = teen_pro_YT_tick;
        }
        return teen_pro_YT_colonies;
    }

    public ColonyRuntime colony(UUID colonyId) { return colonyId == null ? null : coloniesNow().get(colonyId); }

    public UUID colonyAt(ServerLevel level, BlockPos pos) {
        WorldPosition here = new WorldPosition(level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ());
        for (ColonyRuntime colony : coloniesNow().values()) if (colony.territory().contains(here)) return colony.colonyId();
        return null;
    }

    public UUID resolve(UUID id) {
        if (id == null) return null;
        if (teen_pro_YT_life.state(id) != null) return id;
        LifeState bound = teen_pro_YT_life.byBody(id);
        if (bound != null) return bound.citizenId();
        for (ServerLevel level : teen_pro_YT_server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) return lifeOf(entity);
        }
        return null;
    }

    @Override public List<UUID> colonies() {
        List<UUID> ids = new ArrayList<>();
        for (ColonyRuntime colony : coloniesNow().values()) if (colony.available()) ids.add(colony.colonyId());
        return ids;
    }

    @Override public Territory territory(UUID colonyId) {
        ColonyRuntime colony = colony(colonyId);
        return colony == null ? null : colony.territory();
    }

    public Entity entity(UUID lifeId) {
        LifeState state = teen_pro_YT_life.state(lifeId);
        if (state == null || state.deceased()) return null;
        if (state.child()) {
            ResidentChildEntity child = teen_pro_YT_children.get(lifeId);
            return child != null && child.isAlive() && !child.isRemoved() ? child : null;
        }
        return citizenEntity(state.body());
    }

    CitizenEntity citizenEntity(UUID citizenId) {
        if (citizenId == null) return null;
        CitizenRecord citizen = registry().findCitizen(citizenId).orElse(null);
        if (citizen == null || citizen.lifecycle() != CitizenRecord.Lifecycle.ALIVE
                || registry().bindings().activeEntity(citizenId).filter(citizen.entityId()::equals).isEmpty()) return null;
        ServerLevel level = level(citizen.lastKnownPosition().dimension());
        if (level == null || !(level.getEntity(citizen.entityId()) instanceof CitizenEntity entity) || !entity.isAlive() || entity.isRemoved()
                || !citizenId.equals(entity.citizenId()) || entity.bindingEpoch() != citizen.bindingEpoch()) return null;
        return entity;
    }

    public UUID lifeOf(Entity entity) {
        if (entity instanceof ResidentChildEntity child) return child.lifeId();
        if (entity instanceof CitizenEntity citizen && citizen.citizenId() != null) {
            LifeState state = teen_pro_YT_life.byBody(citizen.citizenId());
            return state == null ? null : state.citizenId();
        }
        return null;
    }

    public void joined(Entity entity) {
        if (entity instanceof ResidentChildEntity child) {
            UUID id = child.lifeId();
            if (id == null) { child.discard(); return; }
            if (id.equals(teen_pro_YT_spawning)) { teen_pro_YT_children.put(id, child); return; }
            ResidentChildEntity known = teen_pro_YT_children.get(id);
            if (known != null && known != child && known.isAlive() && !known.isRemoved()) { child.discard(); return; }
            LifeState state = teen_pro_YT_life.state(id);
            if (state == null) state = adopt(child);
            if (state == null || !state.child() || state.deceased()) { child.discard(); return; }
            teen_pro_YT_children.put(id, child);
            settle(child, state);
            teen_pro_YT_displays.add(id);
            return;
        }
        UUID id = lifeOf(entity);
        if (id != null) teen_pro_YT_displays.add(id);
    }

    public void left(Entity entity) {
        UUID id = lifeOf(entity);
        if (id == null) return;
        if (entity instanceof ResidentChildEntity && teen_pro_YT_children.get(id) == entity) teen_pro_YT_children.remove(id);
        teen_pro_YT_environment.forget(id);
        teen_pro_YT_injuries.remove(id);
        teen_pro_YT_motion.forget(id, entity);
    }

    private LifeState adopt(ResidentChildEntity child) {
        ColonyRuntime colony = colony(child.colonyId());
        if (colony == null || !teen_pro_YT_life.content().usable() || teen_pro_YT_life.knows(child.lifeId())) return null;
        SplittableRandom random = new SplittableRandom(child.lifeId().getLeastSignificantBits());
        Sex sex = random.nextBoolean() ? Sex.MALE : Sex.FEMALE;
        NamePool pool = teen_pro_YT_life.content().names(teen_pro_YT_life.settings().names());
        String given = pool == null ? "Kid" : pool.given(sex, random.nextInt(Integer.MAX_VALUE));
        String family = pool == null ? "Colony" : pool.family(random.nextInt(Integer.MAX_VALUE)).form(sex);
        long ticksPerYear = teen_pro_YT_life.settings().ticksPerYear();
        LifeState state = new LifeState(child.lifeId(), colony.colonyId(), sex, (4 + random.nextInt(8)) * ticksPerYear, child.activeTicks(), given, family);
        state.genome(colonyloom.gameplay.life.Genetics.founder(teen_pro_YT_life.content(), sex, random));
        for (LifeStage stage : LifeStage.values()) if (stage.ordinal() <= LifeStage.CHILD.ordinal()) state.grantStage(stage);
        try {
            teen_pro_YT_life.add(state);
        } catch (RuntimeException full) {
            return null;
        }
        teen_pro_YT_LOGGER.info("Colonyloom gave the orphaned child {} a new life as {}", child.getUUID(), state.fullName());
        return state;
    }

    private void settle(ResidentChildEntity child, LifeState state) {
        ColonyRuntime colony = colony(state.colonyId());
        if (colony == null) return;
        Territory territory = colony.territory();
        int centerX = (territory.minX() + territory.maxX()) / 2, centerZ = (territory.minZ() + territory.maxZ()) / 2;
        int radius = Math.max(territory.maxX() - territory.minX(), territory.maxZ() - territory.minZ()) / 2;
        child.restrictTo(new BlockPos(centerX, child.blockPosition().getY(), centerZ), Math.max(8, radius));
    }

    public void died(LivingEntity entity, DamageSource source) {
        UUID id = lifeOf(entity);
        if (id == null) return;
        LifeState state = teen_pro_YT_life.state(id);
        if (state == null || state.deceased()) return;
        teen_pro_YT_deaths.put(id, source instanceof LifeDamageSource life && !life.cause().isEmpty() ? life.cause() : null);
    }

    public void injured(LivingEntity entity, DamageSource source) {
        if (!active() || source instanceof LifeDamageSource) return;
        UUID id = lifeOf(entity);
        if (id == null || entity(id) != entity) return;
        long now = gameTime();
        Long last = teen_pro_YT_injuries.get(id);
        if (last != null && now - last < teen_pro_YT_INJURY_COOLDOWN && now >= last) return;
        teen_pro_YT_injuries.put(id, now);
        String kind;
        if (source.is(DamageTypeTags.IS_FALL)) kind = "fall";
        else if (source.is(DamageTypeTags.IS_DROWNING)) kind = "drown";
        else if (source.is(DamageTypeTags.IS_FREEZING)) kind = "freeze";
        else if (source.is(DamageTypeTags.IS_EXPLOSION)) kind = "explosion";
        else if (source.is(DamageTypeTags.IS_FIRE)) kind = "fire";
        else if (source.getEntity() != null || source.getDirectEntity() != null || source.is(DamageTypeTags.IS_PROJECTILE)) kind = "attack";
        else kind = "other";
        teen_pro_YT_controller.injured(id, kind);
    }

    public String outfitOf(Entity entity) {
        UUID id = lifeOf(entity);
        return id == null ? null : teen_pro_YT_outfitOf.get(id);
    }

    private void flushDisplays() {
        int done = 0;
        for (Iterator<UUID> iterator = teen_pro_YT_displays.iterator(); iterator.hasNext() && done < teen_pro_YT_DISPLAYS_PER_TICK; done++) {
            UUID id = iterator.next();
            iterator.remove();
            applyDisplay(id);
        }
    }

    private void applyDisplay(UUID id) {
        Entity entity = entity(id);
        if (entity == null) return;
        LifeState state = teen_pro_YT_life.state(id);
        if (state == null || !active()) {
            if (teen_pro_YT_outfitOf.remove(id) != null) teen_pro_YT_sync.outfit(entity, "");
            return;
        }
        Component name = Component.literal(state.fullName());
        if (!name.equals(entity.getCustomName())) entity.setCustomName(name);
        LifeStage stage = state.stage(teen_pro_YT_life.settings().ticksPerYear());
        if (entity instanceof ResidentChildEntity child) {
            AttributeInstance scale = child.getAttribute(Attributes.SCALE);
            double wanted = scale(state);
            if (scale != null && Math.abs(scale.getBaseValue() - wanted) > 0.01) scale.setBaseValue(wanted);
        }
        String profession = entity instanceof ResidentChildEntity ? null : registry().findCitizen(state.body()).map(CitizenRecord::professionId).orElse(null);
        String outfit = teen_pro_YT_outfits.choose(new OutfitProfile(entity instanceof ResidentChildEntity ? "child" : "citizen", state.sex().id(), stage.id(), profession), id);
        String previous = outfit == null ? teen_pro_YT_outfitOf.remove(id) : teen_pro_YT_outfitOf.put(id, outfit);
        if (!Objects.equals(previous, outfit)) teen_pro_YT_sync.outfit(entity, outfit == null ? "" : outfit);
    }

    double scale(LifeState state) {
        long ticksPerYear = teen_pro_YT_life.settings().ticksPerYear();
        double years = state.ageTicks() / (double) ticksPerYear;
        return Math.max(0.4D, Math.min(0.96D, 0.4D + years * 0.036D));
    }

    public void tracking(ServerPlayer player, Entity entity) {
        String outfit = outfitOf(entity);
        if (outfit != null) teen_pro_YT_sync.outfit(player, entity, outfit);
    }

    public LifePayloads.View view(ServerPlayer player, UUID colonyId, int tab, int page) {
        UUID target = colonyId;
        if (target == null) target = colonyAt(player);
        ColonyRuntime colony = colony(target);
        if (colony == null) return LifeViews.empty("screen.colonyloom.life.no_colony");
        MemberRank rank = colony.rank(player.getUUID());
        if (rank == null && !player.hasPermissions(2)) return LifeViews.empty("screen.colonyloom.life.no_access");
        return LifeViews.page(this, colony, tab, page);
    }

    UUID colonyAt(ServerPlayer player) {
        WorldPosition here = new WorldPosition(player.level().dimension().location().toString(), player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ());
        UUID member = null;
        for (ColonyRuntime colony : coloniesNow().values()) {
            if (colony.territory().contains(here)) return colony.colonyId();
            if (member == null && colony.rank(player.getUUID()) != null) member = colony.colonyId();
        }
        return member;
    }

    private ServerLevel level(String dimension) {
        try {
            return teen_pro_YT_server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension)));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private Holder<DamageType> damage() {
        if (teen_pro_YT_damage == null)
            teen_pro_YT_damage = teen_pro_YT_server.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DamageTypes.GENERIC_KILL);
        return teen_pro_YT_damage;
    }

    @Override public long gameTime() { return teen_pro_YT_server.overworld().getGameTime(); }

    @Override public int dayTime(String dimension) {
        ServerLevel level = level(dimension);
        return (int) Math.floorMod((level == null ? teen_pro_YT_server.overworld() : level).getDayTime(), 24_000L);
    }

    @Override public boolean raining(WorldPosition position) {
        ServerLevel level = level(position.dimension());
        if (level == null) return false;
        BlockPos pos = new BlockPos(position.x(), position.y() + 1, position.z());
        return level.isLoaded(pos) ? level.isRainingAt(pos) : level.isRaining();
    }

    @Override public Set<EnvironmentFlag> environment(UUID lifeId) { return teen_pro_YT_environment.flags(lifeId); }

    @Override public List<WorldPosition> places(UUID colonyId, Place place) { return teen_pro_YT_places.places(colonyId, place); }

    @Override public void speak(UUID lifeId, String spec) {
        if (!teen_pro_YT_life.settings().speech() || spec == null || RichText.bytes(spec) > RichText.teen_pro_YT_MAX_BYTES) return;
        Entity entity = entity(lifeId);
        if (entity != null) teen_pro_YT_sync.speech(entity, spec);
    }

    @Override public void emote(UUID lifeId, Emote emote) {
        Entity entity = entity(lifeId);
        if (entity == null || emote == null || emote == Emote.NONE) return;
        ServerLevel level = (ServerLevel) entity.level();
        double x = entity.getX(), y = entity.getY() + entity.getBbHeight() + 0.4D, z = entity.getZ();
        switch (emote) {
            case HEART -> level.sendParticles(ParticleTypes.HEART, x, y, z, 3, 0.3D, 0.2D, 0.3D, 0.0D);
            case NOTE -> level.sendParticles(ParticleTypes.NOTE, x, y, z, 2, 0.3D, 0.2D, 0.3D, 1.0D);
            case HAPPY -> level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, y - 0.2D, z, 6, 0.4D, 0.3D, 0.4D, 0.0D);
            case ANGRY -> level.sendParticles(ParticleTypes.ANGRY_VILLAGER, x, y, z, 3, 0.3D, 0.2D, 0.3D, 0.0D);
            case SAD -> level.sendParticles(ParticleTypes.FALLING_WATER, x, y - 0.3D, z, 6, 0.2D, 0.1D, 0.2D, 0.0D);
            case SMOKE -> level.sendParticles(ParticleTypes.SMOKE, x, y - 0.5D, z, 4, 0.1D, 0.1D, 0.1D, 0.02D);
            case SPLASH -> level.sendParticles(ParticleTypes.SPLASH, x, y - 0.6D, z, 8, 0.3D, 0.2D, 0.3D, 0.0D);
            case SPARKLE -> level.sendParticles(ParticleTypes.END_ROD, x, y, z, 4, 0.3D, 0.3D, 0.3D, 0.02D);
            case SNEEZE -> level.sendParticles(ParticleTypes.SNEEZE, x, y - 0.5D, z, 5, 0.15D, 0.05D, 0.15D, 0.03D);
            case SLEEP -> level.sendParticles(ParticleTypes.CLOUD, x, y - 0.4D, z, 1, 0.1D, 0.1D, 0.1D, 0.0D);
            case SICK -> level.sendParticles(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0xFF6A9E3A), x, y - 0.5D, z, 5, 0.25D, 0.25D, 0.25D, 1.0D);
            default -> {}
        }
    }

    @Override public void lookAt(UUID lifeId, UUID other) { teen_pro_YT_motion.look(lifeId, other, teen_pro_YT_LOOK_TICKS); }

    @Override public boolean kill(UUID lifeId, String causeSpec) {
        Entity entity = entity(lifeId);
        if (!(entity instanceof LivingEntity living)) return false;
        living.hurt(new LifeDamageSource(damage(), causeSpec), Float.MAX_VALUE);
        return !living.isAlive();
    }

    @Override public boolean birth(UUID motherId) {
        if (!teen_pro_YT_runtime.persistence().isAvailable()) return false;
        Entity mother = entity(motherId);
        LifeState state = teen_pro_YT_life.state(motherId);
        if (!(mother instanceof LivingEntity living) || state == null || !(mother.level() instanceof ServerLevel level)) return false;
        ColonyRuntime colony = colony(state.colonyId());
        BlockPos pos = mother.blockPosition();
        if (colony == null || !colony.available() || !level.isPositionEntityTicking(pos)
                || !colony.territory().contains(new WorldPosition(level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ()))) return false;
        GrowthEngine.BirthPlan plan = teen_pro_YT_controller.beginBirth(motherId);
        if (plan == null) return false;
        ResidentChildEntity baby = teen_pro_YT_childType.get().create(level);
        if (baby == null) return false;
        baby.setUUID(plan.childId());
        baby.bind(plan.childId(), plan.colonyId());
        baby.moveTo(living.getX(), living.getY(), living.getZ(), living.getYRot(), 0.0F);
        AttributeInstance scale = baby.getAttribute(Attributes.SCALE);
        if (scale != null) scale.setBaseValue(0.4D);
        if (!level.noCollision(baby)) return false;
        teen_pro_YT_spawning = plan.childId();
        boolean spawned;
        try {
            spawned = level.addFreshEntity(baby);
        } finally {
            teen_pro_YT_spawning = null;
        }
        if (!spawned) { teen_pro_YT_children.remove(plan.childId()); return false; }
        LifeState child = teen_pro_YT_controller.completeBirth(plan, baby.activeTicks());
        teen_pro_YT_children.put(child.citizenId(), baby);
        settle(baby, child);
        teen_pro_YT_displays.add(child.citizenId());
        teen_pro_YT_displays.add(motherId);
        return true;
    }

    @Override public void display(UUID lifeId) { teen_pro_YT_displays.add(lifeId); }

    @Override public boolean timeAvailable() { return true; }
}
