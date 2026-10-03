package io.github.kpuctajluk.colonyloom.minecraft.storage;

import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.*;
import io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets;
import io.github.kpuctajluk.colonyloom.core.storage.*;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Server-thread only, nonloading canonical native inventory observations and guarded access. */
public final class StorageService {
    private record Physical(List<BlockEntity> halves, List<StorageId> identities, List<WorldPosition> positions, List<StockRegion> slots) {}
    private final MinecraftServer server;
    private final ColonyRegistry registry;
    private final StorageRegistry storage;
    private final StorageIdentity identity;
    private final GlobalWorkBudgets budgets;
    private final Map<StorageId, Set<WorldPosition>> addresses = new HashMap<>();
    private final Map<WorldPosition, StorageId> addressIdentities = new HashMap<>();
    private final Map<StorageId, WorldPosition> locators = new HashMap<>();
    private final Map<WorldPosition, Physical> tickPhysical = new HashMap<>();
    private final Map<UUID, StorageRegistry.Registration> remembered = new HashMap<>();
    private final Map<StorageId, Map<UUID, StorageRegistry.Registration>> memberships = new HashMap<>();
    private record Sample(ItemStack stack, StockIndex.Observation observation,long revision) {}
    private final Map<StockRegion, Sample> samples = new HashMap<>();
    private final Map<StorageId, List<StorageRegistry.Registration>> sourceRegistrations = new HashMap<>();
    private final Set<StorageId> blockedIdentities = new HashSet<>();

    public StorageService(MinecraftServer server, ColonyRegistry registry, GlobalWorkBudgets budgets, StorageIdentity identity) {
        this.server = Objects.requireNonNull(server);
        this.registry = Objects.requireNonNull(registry);
        this.storage = registry.storage();
        this.budgets = Objects.requireNonNull(budgets);
        this.identity = Objects.requireNonNull(identity);
        for (var registration : storage.registrations()) remember(registration);
    }

    public StorageRegistry.Registration register(UUID colony, WorldPosition address, String role) {
        owner();
        requirePosition(colony, address);
        try {
            Physical physical = physical(address, true);
            if (physical == null) throw new IllegalStateException("CHUNK_NOT_READY or unsupported native inventory");
            for (WorldPosition position : physical.positions()) requirePosition(colony, position);
            var registration = storage.register(colony, address, role, physical.identities(), physical.slots(), physical.positions());
            remember(registration);
            for (StockRegion slot : physical.slots()) storage.index().invalidate(slot);
            return registration;
        } finally {
            pruneLocators();
        }
    }

    /** Explicit authoritative NPC inventory registration: canonical citizen UUID + binding epoch. */
    public StorageRegistry.Registration registerCitizen(UUID colony, UUID citizenId, String role) {
        owner();
        var citizen = registry.citizen(citizenId);
        if (!citizen.colonyId().equals(colony)) throw new SecurityException("Foreign citizen inventory");
        CitizenEntity entity = citizen(citizenId, citizen.bindingEpoch(), citizen.lastKnownPosition().dimension());
        if (entity == null) throw new IllegalStateException("NPC inventory is not authoritative and ready");
        WorldPosition position = position((ServerLevel)entity.level(), entity.blockPosition());
        requirePosition(colony, position);
        StorageId id = new StorageId(position.dimension(), citizenId, citizen.bindingEpoch());
        List<StockRegion> slots = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) slots.add(new StockRegion(id, i));
        var result = storage.register(colony, position, role, List.of(id), slots, List.of(position));
        remember(result);
        return result;
    }

    public StorageRegistry.Workshop registerWorkshop(UUID colony, WorldPosition table, WorldPosition inventory) {
        owner();
        requirePosition(colony, table);
        ServerLevel level = level(table.dimension());
        BlockPos pos = blockPos(table);
        if (level == null || !level.hasChunkAt(pos)) throw new IllegalStateException("CHUNK_NOT_READY");
        if (!level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) throw new IllegalArgumentException("Workshop requires a real crafting table");
        var registration = storage.registrations(colony).stream().filter(value -> value.address().equals(inventory) && value.role().equals("workshop")).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Register this real inventory with role workshop first"));
        Physical physical = physical(inventory, false);
        if (physical == null || !physical.identities().equals(registration.storages())) throw new IllegalStateException("Workshop storage identity is not current");
        return storage.registerWorkshop(colony, table, registration.id());
    }

    /** Renewing identity never carries old promises; retirement persists across restarts. */
    public UUID reidentify(UUID colony, WorldPosition address) {
        owner();
        requirePosition(colony, address);
        ServerLevel level = level(address.dimension());
        if (level == null || !level.hasChunkAt(blockPos(address))) throw new IllegalStateException("CHUNK_NOT_READY");
        BlockEntity entity = level.getBlockEntity(blockPos(address));
        if (!(entity instanceof ChestBlockEntity || entity instanceof BarrelBlockEntity)) throw new IllegalArgumentException("Unsupported native inventory");
        UUID old = identity.existing(entity);
        if (old != null) storage.retire(new StorageId(address.dimension(), old, 0), colony);
        UUID replacement = identity.replace(entity);
        tickPhysical.clear();
        note(new StorageId(address.dimension(), replacement, 0), address);
        for (var registration : List.copyOf(storage.registrations(colony))) {
            if (registration.positions().contains(address)) recanonicalize(registration);
        }
        pruneLocators();
        return replacement;
    }

    public void tick(long now) {
        owner();
        tickPhysical.clear();
        storage.index().tick(now, budgets, this::read);
    }

    public StockIndex.Observation read(StockRegion slot) {
        long start=System.nanoTime();
        try {return readNative(slot);}
        finally {registry.metrics().record(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer.STORAGE_EXTERNAL,System.nanoTime()-start);}
    }
    private StockIndex.Observation readNative(StockRegion slot) {
        owner();
        StorageId id = slot.storage();
        if (storage.isRetired(id) || !currentAuthority(slot)) return unknown();
        ItemStack stack;
        if (id.bindingEpoch() > 0) {
            CitizenEntity entity = citizen(id.identity(), id.bindingEpoch(), id.dimension());
            if (entity == null || slot.slot() >= 9) return unknown();
            stack = entity.inventory().getItem(slot.slot());
        } else {
            WorldPosition address = locators.get(id);
            if (address == null) return unknown();
            Physical physical = physical(address, true);
            if (physical == null) {
                for (var registration : registrations(id)) recanonicalize(registration);
                return unknown();
            }
            if (!physical.identities().contains(id)) {
                for (var registration : registrations(id)) recanonicalize(registration);
                return unknown();
            }
            // Topology changes revise registrations, not canonical half identity or local slots.
            for (var registration : registrations(id)) {
                if (!registration.storages().equals(physical.identities())) recanonicalize(registration);
            }
            if (conflicted(id) || slot.slot() >= 27) return unknown();
            int half = physical.identities().indexOf(id);
            stack = ((Container)physical.halves().get(half)).getItem(slot.slot());
        }
        Sample previous = samples.get(slot);
        if (previous != null && ItemStack.matches(stack, previous.stack())) return previous.observation();
        try {
            var observation = new StockIndex.Observation(NativeItemDescriptor.describe(stack, server.registryAccess()), stack.isEmpty() ? 0 : stack.getCount(), true);
            if (memberships.containsKey(id)) samples.put(slot, new Sample(stack.copy(), observation,previous==null?1:Math.incrementExact(previous.revision())));
            return observation;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return unknown();
        }
    }
    public long observationRevision(StockRegion slot) {owner();Sample sample=samples.get(slot);return sample==null?0:sample.revision();}
    public StockIndex.Observation readFresh(StockRegion slot) {owner();tickPhysical.clear();return read(slot);}

    /** Known locator only: no lookup loads a chunk, and live citizen locators follow their embodiment. */
    public WorldPosition locate(StorageId id) {
        owner(); Objects.requireNonNull(id);
        if (storage.isRetired(id) || conflicted(id) || !memberships.containsKey(id)) return null;
        if (id.bindingEpoch() > 0) {
            CitizenEntity entity = currentCitizen(id.identity(), id.bindingEpoch(), id.dimension());
            return entity == null ? null : position((ServerLevel)entity.level(), entity.blockPosition());
        }
        return locators.get(id);
    }

    /** Current local slot mapping; never generic item handlers or a stale/replacement block entity. */
    public Container currentContainer(StockRegion slot) {
        return container(slot,false);
    }
    /** Inspection only: blocked colonies remain blocked; native inventory/binding is never synthesized. */
    public Container recoveryContainer(StockRegion slot) {
        return container(slot,true);
    }
    private Container container(StockRegion slot, boolean recovery) {
        owner(); Objects.requireNonNull(slot); tickPhysical.clear();
        StorageId id = slot.storage();
        if (storage.isRetired(id) || conflicted(id) || !currentAuthority(slot,recovery)) return null;
        if (id.bindingEpoch() > 0) {
            CitizenEntity entity = recovery ? recoveryCitizen(id.identity(),id.bindingEpoch(),id.dimension())
                    : currentCitizen(id.identity(),id.bindingEpoch(),id.dimension());
            if (entity == null || slot.slot() >= CitizenEntity.INVENTORY_SIZE) return null;
            WorldPosition live = position((ServerLevel)entity.level(), entity.blockPosition());
            return registry.colony(registry.citizen(id.identity()).colonyId()).territory().contains(live) ? entity.inventory() : null;
        }
        WorldPosition address = locators.get(id);
        if (address == null) return null;
        Physical physical = physical(address, false);
        if (physical == null || slot.slot() >= 27) return null;
        for (WorldPosition location : physical.positions()) if (!ready(level(location.dimension()),blockPos(location))) return null;
        int half = physical.identities().indexOf(id);
        if (half < 0 || conflicted(id) || registrations(id).stream().noneMatch(registration ->
                registration.slots().contains(slot) && registration.storages().equals(physical.identities())
                        && registration.positions().equals(physical.positions()))) return null;
        return (Container)physical.halves().get(half);
    }

    /** Remaining native capacity for this exact component map: -1 unknown, zero incompatible/full. */
    public int capacity(StockRegion slot, ItemDescriptor item) {
        owner(); Objects.requireNonNull(item);
        Container container = currentContainer(slot);
        if (container == null) return -1;
        try { return capacity(container,slot.slot(),NativeItemDescriptor.capacityProbe(item,server.registryAccess())); }
        catch (IllegalArgumentException | IllegalStateException failure) { return -1; }
    }
    static int capacity(Container container, int slot, ItemStack candidate) {
        ItemStack current = container.getItem(slot);
        if (!container.canPlaceItem(slot,candidate) || !current.isEmpty() && !ItemStack.isSameItemSameComponents(current,candidate)) return 0;
        return Math.max(0,Math.min(container.getMaxStackSize(),candidate.getMaxStackSize())-current.getCount());
    }
    long observationTick() { owner(); return Math.max(0,budgets.tick()); }

    /** Rechecks current binding, lifecycle, active admission and entity-ticking readiness. */
    public CitizenEntity currentCitizen(UUID id, long epoch, String dimension) {
        owner();
        var record = registry.findCitizen(id).orElse(null);
        CitizenEntity entity = citizen(id,epoch,dimension);
        return record == null || entity == null || record.admission() != CitizenRecord.Admission.ACTIVE
                || record.readiness() != CitizenRecord.Readiness.READY || entity.isPassenger()
                || !registry.bindings().activeEntity(id).filter(record.entityId()::equals).isPresent()
                || !ready((ServerLevel)entity.level(),entity.blockPosition()) ? null : entity;
    }
    private CitizenEntity recoveryCitizen(UUID id, long epoch, String dimension) {
        var record = registry.findCitizen(id).orElse(null);
        if (record == null || record.bindingEpoch() != epoch || record.lifecycle() != CitizenRecord.Lifecycle.ALIVE
                || !registry.bindings().recoveryReady(id)) return null;
        ServerLevel level = level(dimension);
        if (level == null || !(level.getEntity(record.entityId()) instanceof CitizenEntity entity)
                || !entity.isAlive() || entity.isRemoved() || !id.equals(entity.citizenId()) || entity.bindingEpoch() != epoch
                || !ready(level,entity.blockPosition())) return null;
        return entity;
    }
    private static boolean ready(ServerLevel level, BlockPos pos) {
        return level != null && !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos)
                && level.getChunkSource().getChunkNow(pos.getX() >> 4,pos.getZ() >> 4) != null && level.isPositionEntityTicking(pos);
    }

    /** Public future-executor guard, including native exact components and current scope. */
    public boolean matches(UUID colony, StockRegion slot, ItemDescriptor item, long count) {
        owner();
        if (count <= 0 || count > 1_000_000 || !registry.colony(colony).available()
                || storage.registrations(colony).stream().noneMatch(registration -> registration.slots().contains(slot))) return false;
        tickPhysical.clear();
        var observation = read(slot);
        if (!observation.ready() || observation.count() < count || !Objects.equals(observation.item(), item)) return false;
        ItemStack stack = nativeStack(slot);
        return stack != null && NativeItemDescriptor.matches(stack, item, server.registryAccess());
    }

    public String diagnostics(UUID colony, long now) {
        owner();
        StringBuilder result = new StringBuilder("stock colony=").append(colony);
        Set<StockRegion> seen = new HashSet<>();
        int rows = 0;
        for (var registration : storage.registrations(colony)) {
            result.append("\nregistration=").append(registration.id()).append(" role=").append(registration.role()).append(" address=").append(registration.address());
            for (StockRegion slot : registration.slots()) {
                if (!seen.add(slot)) continue;
                if (++rows > 100) { result.append("\nrows=100 truncated=true"); return result.toString(); }
                var observed = storage.index().observation(slot);
                result.append("\nslot=").append(slot).append(" ready=").append(observed.ready())
                        .append(" item=").append(observed.item()).append(" physical=").append(observed.count())
                        .append(" free=").append(storage.index().free(slot, now)).append(" conflict=").append(conflicted(slot.storage()))
                        .append(" retired=").append(storage.isRetired(slot.storage()));
            }
        }
        return result.toString();
    }

    public void invalidate(WorldPosition address) {
        owner();
        tickPhysical.clear();
        for (var registration : storage.registrations()) if (registration.positions().contains(address)) {
            for (StockRegion slot : registration.slots()) storage.index().invalidate(slot);
        }
    }

    private void recanonicalize(StorageRegistry.Registration old) {
        if (!registry.colony(old.colonyId()).available()) return;
        try { register(old.colonyId(), old.address(), old.role()); }
        catch (IllegalArgumentException | IllegalStateException | SecurityException failure) {
            for (StockRegion slot : old.slots()) storage.index().unknown(slot);
        }
    }

    private Physical physical(WorldPosition address, boolean create) {
        Physical cached = tickPhysical.get(address);
        if (cached != null) return cached;
        ServerLevel level = level(address.dimension());
        BlockPos pos = blockPos(address);
        if (level == null || !level.hasChunkAt(pos)) return null;
        var state = level.getBlockState(pos);
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof ChestBlockEntity || entity instanceof BarrelBlockEntity) || entity.isRemoved()) return null;
        List<BlockEntity> halves = new ArrayList<>(2);
        halves.add(entity);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos otherPos = pos.relative(ChestBlock.getConnectedDirection(state));
            if (!level.hasChunkAt(otherPos)) return null;
            var otherState = level.getBlockState(otherPos);
            BlockEntity other = level.getBlockEntity(otherPos);
            if (!(other instanceof ChestBlockEntity) || other.isRemoved() || otherState.getBlock() != state.getBlock()
                    || otherState.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                    || otherState.getValue(ChestBlock.TYPE) == state.getValue(ChestBlock.TYPE)
                    || !otherPos.relative(ChestBlock.getConnectedDirection(otherState)).equals(pos)) return null;
            halves.add(other);
        }
        halves.sort(Comparator.comparingInt((BlockEntity value) -> value.getBlockPos().getX())
                .thenComparingInt(value -> value.getBlockPos().getY()).thenComparingInt(value -> value.getBlockPos().getZ()));
        for (BlockEntity half : halves) {
            if (half instanceof RandomizableContainerBlockEntity loot && loot.getLootTable() != null) return null;
            if (((Container)half).getContainerSize() != 27) return null;
        }
        if (!identity.supported(level, halves)) return null;
        List<StorageId> ids = new ArrayList<>(halves.size());
        List<WorldPosition> positions = new ArrayList<>(halves.size());
        List<StockRegion> slots = new ArrayList<>(halves.size() * 27);
        for (BlockEntity half : halves) {
            UUID uuid = create ? identity.getOrCreate(half) : identity.existing(half);
            if (uuid == null) return null;
            StorageId id = new StorageId(address.dimension(), uuid, 0);
            WorldPosition location = position(level, half.getBlockPos());
            note(id, location);
            ids.add(id); positions.add(location);
            for (int i = 0; i < 27; i++) slots.add(new StockRegion(id, i));
        }
        Physical result = new Physical(List.copyOf(halves), List.copyOf(ids), List.copyOf(positions), List.copyOf(slots));
        for (WorldPosition location : positions) tickPhysical.put(location, result);
        return result;
    }

    private void remember(StorageRegistry.Registration registration) {
        var previous = remembered.put(registration.id(), registration);
        if (previous != null) for (StorageId id : previous.storages()) {
            var entries = memberships.get(id);
            if (entries != null) {
                entries.remove(previous.id());
                if (entries.isEmpty()) memberships.remove(id);
            }
        }
        for (int i = 0; i < registration.storages().size(); i++) {
            StorageId source = registration.storages().get(i);
            memberships.computeIfAbsent(source, ignored -> new LinkedHashMap<>()).put(registration.id(), registration);
            StorageId id = registration.storages().get(i);
            WorldPosition position = registration.positions().get(i);
            locators.putIfAbsent(id, position);
            if (id.bindingEpoch() == 0) note(id, position);
        }
        if (previous != null) for (StorageId id : previous.storages()) {
            if (!memberships.containsKey(id)) locators.remove(id);
        }
        if (previous != null) for (StorageId id : previous.storages()) updateMemberships(id);
        for (StorageId id : registration.storages()) updateMemberships(id);
    }

    private void note(StorageId id, WorldPosition address) {
        StorageId previous = addressIdentities.put(address, id);
        if (previous != null && !previous.equals(id)) {
            Set<WorldPosition> old = addresses.get(previous);
            if (old != null) old.remove(address);
        }
        addresses.computeIfAbsent(id, ignored -> new HashSet<>()).add(address);
        locators.putIfAbsent(id, address);
        if (conflicted(id) && blockedIdentities.add(id)) {
            samples.keySet().removeIf(slot -> slot.storage().equals(id));
            for (int i = 0; i < 27; i++) storage.index().unknown(new StockRegion(id, i));
        }
    }

    private boolean conflicted(StorageId id) { return blockedIdentities.contains(id) || addresses.getOrDefault(id, Set.of()).size() > 1; }
    private boolean currentAuthority(StockRegion slot) { return currentAuthority(slot,false); }
    private boolean currentAuthority(StockRegion slot, boolean recovery) {
        for (var registration : registrations(slot.storage())) {
            if (!registration.slots().contains(slot)) continue;
            var colony = registry.colony(registration.colonyId());
            if ((recovery || colony.available()) && registration.positions().stream().allMatch(colony.territory()::contains)) return true;
        }
        return false;
    }
    private List<StorageRegistry.Registration> registrations(StorageId id) {
        return sourceRegistrations.getOrDefault(id, List.of());
    }
    private void pruneLocators() {
        Set<WorldPosition> active = new HashSet<>();
        for (var registration : remembered.values()) active.addAll(registration.positions());
        addressIdentities.entrySet().removeIf(entry -> !active.contains(entry.getKey()));
        addresses.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(address -> !active.contains(address));
            return entry.getValue().isEmpty();
        });
        locators.keySet().removeIf(id -> !memberships.containsKey(id));
        samples.keySet().removeIf(slot -> !memberships.containsKey(slot.storage()));
        blockedIdentities.removeIf(id -> !memberships.containsKey(id));
    }
    private void updateMemberships(StorageId id) {
        var entries = memberships.get(id);
        if (entries == null) sourceRegistrations.remove(id);
        else sourceRegistrations.put(id, List.copyOf(entries.values()));
    }
    private CitizenEntity citizen(UUID id, long epoch, String dimension) {
        var record = registry.findCitizen(id).orElse(null);
        if (record == null || record.bindingEpoch() != epoch || record.lifecycle() != CitizenRecord.Lifecycle.ALIVE
                || !registry.colony(record.colonyId()).available() || registry.bindings().activeEntity(id).isEmpty()) return null;
        ServerLevel level = level(dimension);
        if (level == null || !(level.getEntity(record.entityId()) instanceof CitizenEntity entity)
                || !entity.isAlive() || entity.isRemoved() || entity.isQuarantined() || !id.equals(entity.citizenId()) || entity.bindingEpoch() != epoch) return null;
        return entity;
    }
    private ItemStack nativeStack(StockRegion slot) {
        Container container = currentContainer(slot);
        return container == null ? null : container.getItem(slot.slot());
    }
    private void requirePosition(UUID colonyId, WorldPosition position) {
        var colony = registry.colony(colonyId);
        if (!colony.available()) throw new IllegalStateException("Colony recovery/content blocked");
        if (!colony.territory().contains(position)) throw new SecurityException("Storage position is outside colony territory");
        ServerLevel level = level(position.dimension());
        if (level == null || !level.getWorldBorder().isWithinBounds(blockPos(position)) || position.y() < level.getMinBuildHeight() || position.y() >= level.getMaxBuildHeight()) throw new IllegalArgumentException("Storage outside world bounds");
    }
    private void owner() { io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime.requireServerThread(server); }
    private ServerLevel level(String dimension) { return server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension))); }
    private static BlockPos blockPos(WorldPosition position) { return new BlockPos(position.x(), position.y(), position.z()); }
    private static WorldPosition position(ServerLevel level, BlockPos position) { return new WorldPosition(level.dimension().location().toString(), position.getX(), position.getY(), position.getZ()); }
    private static StockIndex.Observation unknown() { return new StockIndex.Observation(null, 0, false); }
}
