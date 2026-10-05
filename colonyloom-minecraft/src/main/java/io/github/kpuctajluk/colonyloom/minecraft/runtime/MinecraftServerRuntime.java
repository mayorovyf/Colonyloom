package io.github.kpuctajluk.colonyloom.minecraft.runtime;

import io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime;
import io.github.kpuctajluk.colonyloom.core.citizen.ProfessionDefinition;
import io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonyPersistence;
import java.util.Collection;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Binds a core session to one exact Minecraft server for its whole lifetime. */
public final class MinecraftServerRuntime {
    private final MinecraftServer server;
    private final ServerRuntime runtime;
    private final Path worldPath;
    private int lastMinecraftTick;
    private long lastCompactionAttempt=-1200;
    private final ColonyPersistence persistence;
    private io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager chunks;
    private io.github.kpuctajluk.colonyloom.core.navigation.NavigationService navigation;
    private io.github.kpuctajluk.colonyloom.minecraft.navigation.MinecraftNavigationBackend navigationBackend;
    private CitizenAdmissionService citizens;
    private io.github.kpuctajluk.colonyloom.gameplay.construction.ConstructionController construction;
    private io.github.kpuctajluk.colonyloom.minecraft.construction.MinecraftConstructionService constructionService;
    private io.github.kpuctajluk.colonyloom.minecraft.storage.StorageService storage;
    private io.github.kpuctajluk.colonyloom.core.supply.SupplyPlanner supplyPlanner;
    private io.github.kpuctajluk.colonyloom.minecraft.storage.MinecraftDeliveryService deliveryService;
    private io.github.kpuctajluk.colonyloom.minecraft.production.MinecraftProductionService productionService;
    private io.github.kpuctajluk.colonyloom.gameplay.needs.NeedsController needs;
    private io.github.kpuctajluk.colonyloom.minecraft.needs.MinecraftNeedsService needsService;
    private java.util.Map<String,io.github.kpuctajluk.colonyloom.gameplay.production.ProcessDefinition> processes=java.util.Map.of();
    private long metricsTickStart;
    private int productionRetirementCursor;
    public void metricsTickStarted() { runtime.requireOwnerThread(); metricsTickStart=System.nanoTime(); }
    public void metricsTickFinished() { runtime.requireOwnerThread(); if(metricsTickStart!=0) { metrics().record(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer.MSPT,System.nanoTime()-metricsTickStart); metricsTickStart=0; } }
    public io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics metrics() { return runtime.metrics(); }
    public io.github.kpuctajluk.colonyloom.minecraft.metrics.MinecraftMetrics minecraftMetrics() { runtime.requireOwnerThread(); return new io.github.kpuctajluk.colonyloom.minecraft.metrics.MinecraftMetrics(this); }

    private MinecraftServerRuntime(MinecraftServer server,Runnable flushPendingIo) {
        this.server = server;
        this.worldPath = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        this.lastMinecraftTick = server.getTickCount();
        this.runtime = ServerRuntime.start(server.getRunningThread());
        this.persistence = ColonyPersistence.open(server, runtime,flushPendingIo);
    }

    public static MinecraftServerRuntime start(MinecraftServer server,Runnable flushPendingIo) {
        requireServerThread(server);
        if (server.isStopped()) {
            throw new IllegalStateException("Cannot reuse a stopped Minecraft server");
        }
        return new MinecraftServerRuntime(server,flushPendingIo);
    }

    public UUID sessionId() {
        return runtime.sessionId();
    }

    public Path worldPath() {
        return worldPath;
    }

    public long serverTick() {
        return runtime.serverTick();
    }

    public ServerRuntime core() {
        runtime.requireOwnerThread();
        return runtime;
    }

    public ColonyPersistence persistence() {
        runtime.requireOwnerThread();
        return persistence;
    }

    public void configureContent(io.github.kpuctajluk.colonyloom.minecraft.content.ContentLoader.Content content) {
        runtime.requireOwnerThread();
        if(construction==null) construction=new io.github.kpuctajluk.colonyloom.gameplay.construction.ConstructionController(runtime.registry(),new io.github.kpuctajluk.colonyloom.minecraft.construction.MinecraftConstructionGeometry(server));
        runtime.configureCommands(persistence::ensureSessionDirty, content.professions().values());
        construction.definitions(content.blueprints());
        runtime.commands().construction(construction);
        processes=content.processes();configureSupply();
        runtime.setSimulationEnabled(persistence.isAvailable());
    }
    public void configureStorage(io.github.kpuctajluk.colonyloom.minecraft.storage.StorageIdentity identity) {
        runtime.requireOwnerThread();
        if (storage != null) throw new IllegalStateException("Storage service already configured");
        storage = new io.github.kpuctajluk.colonyloom.minecraft.storage.StorageService(server, runtime.registry(), runtime.budgets(), identity);
        configureSupply();
    }
    public io.github.kpuctajluk.colonyloom.minecraft.storage.StorageService storage() { runtime.requireOwnerThread(); return storage; }
    public io.github.kpuctajluk.colonyloom.gameplay.needs.NeedsController needs() {runtime.requireOwnerThread();return needs;}
    public io.github.kpuctajluk.colonyloom.minecraft.storage.MinecraftDeliveryService deliveryService() { runtime.requireOwnerThread(); return deliveryService; }
    private void configureSupply() {
        if(storage==null)return;
        if(supplyPlanner==null)supplyPlanner=new io.github.kpuctajluk.colonyloom.core.supply.SupplyPlanner(runtime.registry(),runtime.registry().supply(),runtime.budgets());
        supplyPlanner.configure(new io.github.kpuctajluk.colonyloom.gameplay.production.ProductionCatalog(runtime.registry(),processes),new io.github.kpuctajluk.colonyloom.minecraft.storage.MinecraftSupplyAccess(runtime.registry(),storage));
        runtime.commands().delivery(new io.github.kpuctajluk.colonyloom.gameplay.logistics.DeliveryController(runtime.registry(),new io.github.kpuctajluk.colonyloom.minecraft.storage.MinecraftDeliveryAccess(runtime.registry(),storage)));
        runtime.registry().setAfterRestore(() -> {runtime.scheduler().rebuild();supplyPlanner.rebuild();if(needs!=null)needs.rebuild();});
    }
    public void configurePhysical(io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager.ChunkAccess access,
            io.github.kpuctajluk.colonyloom.minecraft.construction.BlockPlacementExecutor.ItemInteraction interaction,
            io.github.kpuctajluk.colonyloom.minecraft.construction.BlockPlacementExecutor.FaultObserver observer,
            io.github.kpuctajluk.colonyloom.minecraft.storage.StorageTransferExecutor.Protection transferProtection,
            io.github.kpuctajluk.colonyloom.minecraft.storage.StorageTransferExecutor.FaultObserver transferObserver,
            io.github.kpuctajluk.colonyloom.minecraft.production.RecipeExecutor.Protection recipeProtection,
            io.github.kpuctajluk.colonyloom.minecraft.production.RecipeExecutor.FaultObserver recipeObserver,
            io.github.kpuctajluk.colonyloom.minecraft.needs.FoodConsumptionExecutor.Protection foodProtection,
            io.github.kpuctajluk.colonyloom.minecraft.needs.FoodConsumptionExecutor.FaultObserver foodObserver) {
        runtime.requireOwnerThread();
        if (chunks != null) throw new IllegalStateException("Physical services already configured");
        chunks = new io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager(runtime.registry(), runtime.budgets(), access);
        var placement=new io.github.kpuctajluk.colonyloom.minecraft.construction.BlockPlacementExecutor(server,runtime.registry(),interaction,observer);
        var transfer=new io.github.kpuctajluk.colonyloom.minecraft.storage.StorageTransferExecutor(server,runtime.registry(),storage,transferProtection,persistence::checkpointId,transferObserver);
        constructionService=new io.github.kpuctajluk.colonyloom.minecraft.construction.MinecraftConstructionService(server,runtime.registry(),construction,chunks,placement,persistence::checkpointId,storage,transfer);
        deliveryService=new io.github.kpuctajluk.colonyloom.minecraft.storage.MinecraftDeliveryService(server,runtime.registry(),storage,transfer,chunks);
        var foodExecutor=new io.github.kpuctajluk.colonyloom.minecraft.needs.FoodConsumptionExecutor(server,runtime.registry(),storage,foodProtection,persistence::checkpointId,foodObserver);
        needsService=new io.github.kpuctajluk.colonyloom.minecraft.needs.MinecraftNeedsService(runtime.registry(),storage,deliveryService,foodExecutor);
        needs=new io.github.kpuctajluk.colonyloom.gameplay.needs.NeedsController(runtime.registry(),needsService);needsService.needs(needs);
        var recipeExecutor=new io.github.kpuctajluk.colonyloom.minecraft.production.RecipeExecutor(server,runtime.registry(),storage,recipeProtection,persistence::checkpointId,recipeObserver);
        productionService=new io.github.kpuctajluk.colonyloom.minecraft.production.MinecraftProductionService(runtime.registry(),storage,recipeExecutor,chunks);
        io.github.kpuctajluk.colonyloom.core.navigation.NavigationService.GoalAuthority goals=(work,request) ->
                (work.criticalService()||io.github.kpuctajluk.colonyloom.core.work.WorkOrder.DELIVERY.equals(work.typeId()))?deliveryService.current(work,request):
                io.github.kpuctajluk.colonyloom.core.work.WorkOrder.PRODUCTION.equals(work.typeId())?productionService.current(work,request):constructionService.current(work,request);
        navigationBackend = new io.github.kpuctajluk.colonyloom.minecraft.navigation.MinecraftNavigationBackend(server, runtime.registry(), chunks,goals);
        navigation = new io.github.kpuctajluk.colonyloom.core.navigation.NavigationService(runtime.registry(), runtime.budgets(), chunks,navigationBackend,goals);
        constructionService.navigation(navigation);
        deliveryService.navigation(navigation);
        productionService.navigation(navigation);
        runtime.scheduler().physicalExecutor(io.github.kpuctajluk.colonyloom.core.work.WorkOrder.PRODUCTION,productionService);
        runtime.scheduler().physicalExecutor(io.github.kpuctajluk.colonyloom.core.work.WorkOrder.DELIVERY,deliveryService);
        runtime.scheduler().physicalExecutor(io.github.kpuctajluk.colonyloom.core.work.WorkOrder.CONSTRUCTION,constructionService);
        runtime.scheduler().physicalExecutor(io.github.kpuctajluk.colonyloom.core.work.WorkOrder.FOOD,needsService);
        citizens = new CitizenAdmissionService(server, runtime, chunks);
        citizens.onFoodNeed(needs::observe);
        runtime.scheduler().beforeWork(tick -> {
            // The stock sweep reserves its proven freshness share before downstream native reads.
            if(storage!=null)storage.tick(tick);
            // Rotate dirty consumers across scheduler-first and platform-first ticks; quota one
            // must still admit delivery/production work rather than only poll resident domains.
            int first=(int)((tick/2)%9);
            for(int offset=0;offset<9;offset++) {
                switch((first+offset)%9) {
                    case 0 -> chunks.tick(tick);
                    case 1 -> citizens.tick();
                    case 2 -> navigation.tick(tick);
                    case 3 -> runtime.registry().targetClaims().tick();
                    case 4 -> runtime.registry().supply().reconcile(tick,runtime.budgets());
                    case 5 -> needs.tick(tick);
                    case 6 -> { if(supplyPlanner!=null)supplyPlanner.tick(tick); }
                    case 7 -> { if(deliveryService!=null)deliveryService.tick(tick); }
                    case 8 -> { if(productionService!=null)productionService.tick(); }
                }
            }
        });
        runtime.scheduler().physicalExecutor(io.github.kpuctajluk.colonyloom.core.work.WorkOrder.MOVE, new io.github.kpuctajluk.colonyloom.core.scheduler.SimulationScheduler.PhysicalExecutor() {
            public void step(io.github.kpuctajluk.colonyloom.core.work.WorkOrder work, long tick) {
                var citizen = runtime.registry().citizen(work.assignee());
                try {
                    if (navigation.request(work.id(), work.colonyId(), citizen.citizenId(), citizen.bindingEpoch(), 0, work.target(), work.lane(), work.priority()) == null) {
                        runtime.registry().workBoard().waitAssigned(work.id(), io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.RECONCILING, "move");
                        return;
                    }
                } catch (io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.AdmissionException denied) {
                    runtime.registry().workBoard().waitAssigned(work.id(), denied.reason() == io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Reason.CRITICAL_CAPACITY
                            ? io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.CRITICAL_CAPACITY : io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.STATE_LIMIT, "move");
                    return;
                }
                if (navigation.atTarget(work.id())) {
                    runtime.registry().workBoard().transition(work.id(), io.github.kpuctajluk.colonyloom.core.work.WorkOrder.State.COMPLETED,
                            io.github.kpuctajluk.colonyloom.core.work.WorkOrder.Reason.NONE, "completed");
                    navigation.cancel(work.id());
                } else if (navigation.state(work.id()) == io.github.kpuctajluk.colonyloom.core.navigation.NavigationService.State.WAITING) {
                    runtime.registry().workBoard().waitAssigned(work.id(), navigation.reason(work.id()), "move");
                }
            }
            public void cancel(UUID workId) { navigation.cancel(workId); }
        });
    }
    public io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager chunks() { runtime.requireOwnerThread(); return chunks; }
    public CitizenAdmissionService citizenAdmission() { runtime.requireOwnerThread(); return citizens; }
    public io.github.kpuctajluk.colonyloom.core.navigation.NavigationService navigation() { runtime.requireOwnerThread(); return navigation; }
    public java.util.Map<String,Object> navigationBackendMetrics() { runtime.requireOwnerThread(); return navigationBackend == null ? java.util.Map.of() : navigationBackend.diagnostics(); }
    public void citizenObserved(UUID id) { runtime.requireOwnerThread(); if (citizens != null) citizens.observe(id); }
    public void physicalLimitsUpdated() { runtime.requireOwnerThread(); if (chunks != null) chunks.limitsUpdated(); }

    public void postTick(MinecraftServer eventServer) {
        requireBoundServer(eventServer);
        int minecraftTick = server.getTickCount();
        // Minecraft's int counter can wrap; the session's long counter must not.
        if (minecraftTick != lastMinecraftTick + 1) {
            throw new IllegalStateException("Duplicate or non-sequential Minecraft post-tick event");
        }
        if (!persistence.isAvailable()) runtime.setSimulationEnabled(false);
        runtime.tick(runtime.serverTick() + 1);
        if (persistence.isAvailable() && runtime.serverTick() - lastCompactionAttempt >= 1200) {
            lastCompactionAttempt = runtime.serverTick();
            compactIfNeeded();
        }
        lastMinecraftTick = minecraftTick;
    }

    private void compactIfNeeded() {
        var registry = runtime.registry();
        if (registry.colonies().stream().anyMatch(colony -> colony.recoveryBlocked() || colony.contentBlocked())) return;
        int productionCount = registry.supply().productionCount();
        boolean needed = registry.effects().size() >= 2048 || registry.construction().size() >= 384
                || registry.supply().deliveries().stream().filter(io.github.kpuctajluk.colonyloom.core.logistics.DeliveryOrder::terminal).limit(32).count() >= 32
                || registry.supply().terminalProductionCount() >= 32
                || runtime.workBoard().works().stream().filter(work -> work.terminal() && io.github.kpuctajluk.colonyloom.core.work.WorkOrder.FOOD.equals(work.typeId())).limit(32).count() >= 32;
        if (!needed) return;
        long started = System.nanoTime();
        try {
            if (!persistence.checkpointForCompaction()) return;
            registry.effects().compactAfterVerifiedCheckpoint();
            registry.construction().compactAfterVerifiedCheckpoint();
            var retained = new java.util.HashSet<UUID>();
            for (var effect : registry.effects().snapshots()) if (effect.workId() != null) retained.add(effect.workId());
            for (var dependency : registry.workBoard().works()) retained.addAll(dependency.dependencies());
            for (var citizen : registry.citizensView()) if (citizen.assignedWorkId() != null) retained.add(citizen.assignedWorkId());
            for (var claim : registry.targetClaims().snapshots()) retained.add(claim.ownerId());
            for (var order : registry.supply().deliveries()) if (order.terminal()) {
                var work = order.workId() == null ? null : registry.workBoard().work(order.workId());
                if (work != null && (!work.terminal() || retained.contains(work.id()))) continue;
                registry.supply().retireDelivery(order.id());
                if (work != null) registry.workBoard().retire(work.id());
            }
            boolean retiredProduction = false;
            int productionChecks = Math.min(32, productionCount);
            for (int i = 0; i < productionChecks && registry.supply().productionCount() > 0; i++) {
                productionRetirementCursor = registry.supply().productionRetirementStart(productionRetirementCursor);
                var order = registry.supply().productionAt(productionRetirementCursor);
                if (!registry.supply().canRetireProduction(order.id())) {
                    productionRetirementCursor++;
                    continue;
                }
                registry.supply().retireProduction(order.id());
                if (order.workId() != null) runtime.workBoard().retire(order.workId());
                retiredProduction = true;
                // Removal swaps the last indexed order into this offset; inspect it next.
            }
            if (retiredProduction && supplyPlanner != null) supplyPlanner.rebuild();
            var deliveryConsumers = new java.util.HashSet<UUID>();
            for (var order : registry.supply().deliveries()) if (order.ownerDemandId() != null) {
                deliveryConsumers.add(registry.supply().demand(order.ownerDemandId()).snapshot().ownerId());
            }
            boolean retiredFood = false;
            for (var work : java.util.List.copyOf(runtime.workBoard().works())) {
                if (work.terminal() && io.github.kpuctajluk.colonyloom.core.work.WorkOrder.FOOD.equals(work.typeId())
                        && !retained.contains(work.id()) && !deliveryConsumers.contains(work.id())) {
                    registry.supply().retireFood(work.id());
                    runtime.workBoard().retire(work.id());
                    retiredFood = true;
                }
            }
            if (retiredFood && supplyPlanner != null) supplyPlanner.rebuild();
            persistence.persistSnapshot();
        } finally {
            metrics().record(io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer.COMPACTION, System.nanoTime() - started);
        }
    }

    public void beginStopping(MinecraftServer eventServer) {
        requireBoundServer(eventServer);
        runtime.beginStopping();
        if (supplyPlanner != null) supplyPlanner.close();
        if (deliveryService != null) deliveryService.close();
        if (productionService != null) productionService.close();
        if (navigation != null) navigation.close();
        if (constructionService != null) constructionService.close();
        if (citizens != null) citizens.close();
        if (chunks != null) chunks.close();
        if (persistence.isAvailable()) persistence.checkpointAndClean();
    }

    public void stop(MinecraftServer eventServer) {
        requireBoundServer(eventServer);
        runtime.stop();
    }

    public static void requireServerThread(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            throw new IllegalStateException("Runtime lifecycle must run on the Minecraft server thread");
        }
    }

    private void requireBoundServer(MinecraftServer eventServer) {
        if (eventServer != server) {
            throw new IllegalStateException("Runtime cannot be reused by another Minecraft server");
        }
        requireServerThread(eventServer);
        runtime.requireOwnerThread();
    }
}
