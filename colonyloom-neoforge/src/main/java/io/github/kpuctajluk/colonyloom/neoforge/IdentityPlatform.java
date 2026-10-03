package io.github.kpuctajluk.colonyloom.neoforge;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import io.github.kpuctajluk.colonyloom.core.colony.*;
import io.github.kpuctajluk.colonyloom.core.citizen.*;
import io.github.kpuctajluk.colonyloom.core.command.ColonyCommands;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime;
import java.util.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Server-scoped Minecraft observations and validations around the shared mutation owner. */
final class IdentityPlatform {
    private final MinecraftServer server;
    private final MinecraftServerRuntime bridge;
    private final Map<UUID,CitizenEntity> loaded = new HashMap<>();
    private record Inspection(ColonyCommands.RecoveryInspection state, Map<UUID, net.minecraft.nbt.CompoundTag> inventories,Map<UUID,String> physicalSites,Map<UUID,String> physicalEffects) {}
    private final Map<UUID,Inspection> inspections = new HashMap<>();
    private String identityFailure;
    private UUID provisioningEntity;

    IdentityPlatform(MinecraftServer server,MinecraftServerRuntime bridge) { this.server=server; this.bridge=bridge; }

    void reconcileLoaded() {
        for (ServerLevel level : server.getAllLevels()) for (Entity entity : level.getAllEntities()) join(entity);
    }
    void join(Entity entity) {
        if (!(entity instanceof CitizenEntity citizen)) return;
        citizen.runtimeMetrics(bridge.metrics());
        if (citizen.getUUID().equals(provisioningEntity)) return;
        citizen.setQuarantined(true);
        if (!bridge.persistence().isAvailable() || bridge.core().lifecycle()!=io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime.Lifecycle.RUNNING || citizen.citizenId()==null) return;
        if (identityFailure!=null) return;
        try {
            bridge.persistence().ensureSessionDirty();
            bridge.core().bindings().observe(citizen.citizenId(),citizen.getUUID(),citizen.bindingEpoch());
            loaded.put(citizen.getUUID(),citizen);
            refresh(citizen.citizenId());
            bridge.persistence().capture();
        } catch (RuntimeException error) {
            identityFailure="Identity reconciliation blocked: "+error.getMessage();
            for (CitizenEntity observed:loaded.values()) observed.setQuarantined(true);
            inspections.clear();
            LogUtils.getLogger().error(identityFailure,error);
        }
    }
    void leave(Entity entity) {
        if (!(entity instanceof CitizenEntity citizen) || citizen.citizenId()==null || !bridge.persistence().isAvailable()) return;
        loaded.remove(citizen.getUUID());
        if (identityFailure!=null || bridge.core().lifecycle()!=io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime.Lifecycle.RUNNING) return;
        try {
            bridge.core().bindings().unload(citizen.getUUID());
            refresh(citizen.citizenId());
            bridge.persistence().capture();
        } catch (RuntimeException error) {
            identityFailure="Identity reconciliation blocked: "+error.getMessage();
            for (CitizenEntity observed:loaded.values()) observed.setQuarantined(true);
            inspections.clear();
            LogUtils.getLogger().error(identityFailure,error);
        }
    }
    private void refresh(UUID citizenId) {
        var active=bridge.core().bindings().activeEntity(citizenId);
        for (CitizenEntity entity:loaded.values()) if (citizenId.equals(entity.citizenId())) {
            entity.setQuarantined(!entity.isAlive() || !active.filter(entity.getUUID()::equals).isPresent());
        }
        bridge.core().registry().findCitizen(citizenId).ifPresent(record -> {
            boolean blocked = record.lifecycle()!=CitizenRecord.Lifecycle.ALIVE
                    || !bridge.core().registry().colony(record.colonyId()).available()
                    || bridge.core().bindings().observations(citizenId).stream().anyMatch(observation -> observation.quarantined() && !observation.retired());
            bridge.core().commands().updateCitizenReadiness(citizenId, active.isPresent()
                    ? CitizenRecord.Readiness.READY : blocked ? CitizenRecord.Readiness.BLOCKED : CitizenRecord.Readiness.UNKNOWN);
        });
        if (bridge.core().registry().findCitizen(citizenId).isPresent()) bridge.citizenObserved(citizenId);
        inspections.clear();
    }
    void death(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof CitizenEntity citizen) || citizen.citizenId()==null || !bridge.persistence().isAvailable()) return;
        var record=bridge.core().registry().findCitizen(citizen.citizenId()).orElse(null);
        if (record==null || record.lifecycle()!=CitizenRecord.Lifecycle.ALIVE || !record.entityId().equals(citizen.getUUID()) || record.bindingEpoch()!=citizen.bindingEpoch()) return;
        try {
            bridge.persistence().ensureSessionDirty();
            int before=0; for(int slot=0;slot<CitizenEntity.INVENTORY_SIZE;slot++) before+=citizen.inventory().getItem(slot).getCount();
            var inventory=new net.minecraft.nbt.CompoundTag(); net.minecraft.world.ContainerHelper.saveAllItems(inventory,citizen.inventory().getItems(),server.registryAccess());
            var effect=new io.github.kpuctajluk.colonyloom.core.action.EffectRecord(UUID.randomUUID(),record.colonyId(),record.assignedWorkId(),record.citizenId(),record.bindingEpoch(),
                    io.github.kpuctajluk.colonyloom.core.action.ActionContext.Kind.DEATH,position((ServerLevel)citizen.level(),citizen.blockPosition()),"inventoryHash:"+Integer.toHexString(inventory.hashCode()),"colonyloom:inventory",before,before,
                    io.github.kpuctajluk.colonyloom.core.action.EffectRecord.State.PREPARED,0);
            bridge.core().registry().effects().prepare(effect,io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.NORMAL);
            citizen.observeDeathInventory(remaining -> {
                try {
                    bridge.core().registry().effects().update(effect.observed(remaining,remaining!=0));
                    if(remaining!=0) bridge.core().registry().markRecoveryBlocked(record.colonyId(),bridge.persistence().checkpointId());
                    bridge.persistence().capture();
                } catch(RuntimeException error) {
                    bridge.core().registry().markRecoveryBlocked(record.colonyId(),bridge.persistence().checkpointId());
                    LogUtils.getLogger().error("Death inventory observation blocked; actual drops are not compensated",error);
                }
            });
            bridge.core().commands().markDeath(citizen.citizenId());
            citizen.setQuarantined(true);
            bridge.persistence().capture();
        } catch (RuntimeException error) {
            citizen.setQuarantined(true);
            try {
                bridge.core().commands().markDeath(citizen.citizenId());
                bridge.core().registry().markRecoveryBlocked(record.colonyId(),bridge.persistence().checkpointId());
                bridge.persistence().capture();
            } catch(RuntimeException blockingFailure) { error.addSuppressed(blockingFailure); }
            LogUtils.getLogger().error("Colonyloom cannot persist observed citizen death; physical Minecraft death is not cancelled",error);
        }
    }
    void interact(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof CitizenEntity citizen) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (mayOpen(player,citizen)) {
            bridge.persistence().ensureSessionDirty();
            if (citizen.openInventory(player,p -> mayOpen(p,citizen))) {
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
            }
        }
    }
    private boolean mayOpen(ServerPlayer player,CitizenEntity entity) {
        if (identityFailure!=null || !bridge.persistence().isAvailable() || entity.citizenId()==null || entity.isQuarantined()) return false;
        var citizen=bridge.core().registry().citizen(entity.citizenId());
        var colony=bridge.core().registry().colony(citizen.colonyId());
        MemberRank rank=colony.rank(player.getUUID());
        return colony.available() && (rank==MemberRank.OWNER || rank==MemberRank.MANAGER)
                && bridge.core().bindings().activeEntity(citizen.citizenId()).filter(entity.getUUID()::equals).isPresent();
    }

    String createColony(CommandSourceStack source,String name,BlockPos from,BlockPos to) throws CommandSyntaxException {
        Territory territory=new Territory(source.getLevel().dimension().location().toString(),Math.min(from.getX(),to.getX()),Math.min(from.getZ(),to.getZ()),Math.max(from.getX(),to.getX()),Math.max(from.getZ(),to.getZ()));
        var colony=bridge.core().commands().createColony(context(source),UUID.randomUUID(),name,territory);
        bridge.persistence().capture();
        return "colony="+colony.colonyId()+" revision="+colony.revision();
    }
    String setMember(CommandSourceStack source,UUID colony,UUID player,String rank) throws CommandSyntaxException {
        MemberRank member=switch(rank){case "manager"->MemberRank.MANAGER;case "viewer"->MemberRank.VIEWER;case "none"->null;default->throw new IllegalArgumentException("Rank must be manager|viewer|none");};
        bridge.core().commands().setMember(context(source),colony,player,member); bridge.persistence().capture(); inspections.clear(); return "membership updated colony="+colony;
    }
    String setOwner(CommandSourceStack source,UUID colony,UUID player) throws CommandSyntaxException {
        bridge.core().commands().setOwner(context(source),colony,player); bridge.persistence().capture(); inspections.clear(); return "owner updated colony="+colony;
    }
    String createCitizen(CommandSourceStack source,UUID colony,BlockPos pos) throws CommandSyntaxException {
        requireAvailable();
        CitizenEntity entity=CitizenRegistration.CITIZEN.get().create(source.getLevel());
        if(entity==null)throw new IllegalStateException("Citizen entity factory unavailable");
        entity.moveTo(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5,0,0);
        if(!source.getLevel().noCollision(entity))throw new IllegalArgumentException("Citizen position is obstructed");
        UUID citizenId=UUID.randomUUID();
        var record=bridge.core().commands().createCitizen(context(source),colony,citizenId,entity.getUUID(),position(source.getLevel(),pos), proposed -> {
            entity.initializeIdentity(citizenId,proposed.bindingEpoch());
            provisioningEntity=entity.getUUID();
            try {
                if(!source.getLevel().addFreshEntity(entity))throw new IllegalStateException("Citizen spawn refused without identity mutation");
            } finally {
                provisioningEntity=null;
            }
        });
        join(entity);
        bridge.persistence().capture(); return "citizen="+citizenId+" entity="+entity.getUUID()+" epoch="+record.bindingEpoch();
    }
    String assign(CommandSourceStack source,UUID citizen,String profession) throws CommandSyntaxException {
        bridge.core().commands().assignProfession(context(source),citizen,profession); bridge.persistence().capture(); return "citizen="+citizen+" profession="+profession;
    }
    String createTimer(CommandSourceStack source,UUID colony,long ticks) throws CommandSyntaxException {
        var work=bridge.core().commands().createTimerWork(context(source),UUID.randomUUID(),colony,
                position(source.getLevel(),BlockPos.containing(source.getPosition())),null,0,ticks);
        bridge.persistence().capture();
        return "work="+work.id()+" state="+work.state()+" remainingActiveTicks="+work.remainingActiveTicks();
    }
    String createMove(CommandSourceStack source, UUID colony, BlockPos target) throws CommandSyntaxException {
        var work = bridge.core().commands().createMoveWork(context(source), UUID.randomUUID(), colony, position(source.getLevel(), target));
        bridge.persistence().capture();
        return "work=" + work.id() + " state=" + work.state();
    }
    String build(CommandSourceStack source,UUID colony,String blueprint,BlockPos origin,int rotation) throws CommandSyntaxException {
        var work=bridge.core().commands().build(context(source),UUID.randomUUID(),colony,blueprint,position(source.getLevel(),origin),rotation);
        bridge.persistence().capture(); return "work="+work.id()+" state="+work.state()+" blueprint="+blueprint;
    }
    String cancelWork(CommandSourceStack source,UUID workId) throws CommandSyntaxException {
        var work=bridge.core().commands().cancelWork(context(source),workId);
        bridge.persistence().capture(); return "work="+work.id()+" state="+work.state();
    }
    String priorityWork(CommandSourceStack source,UUID workId,int priority) throws CommandSyntaxException {
        var work=bridge.core().commands().prioritizeWork(context(source),workId,priority);
        bridge.persistence().capture(); return "work="+work.id()+" priority="+work.priority();
    }
    String registerStorage(CommandSourceStack source, UUID colony, BlockPos pos, String role) throws CommandSyntaxException {
        requireStorageManager(source, colony, pos);
        var registration = bridge.storage().register(colony, position(source.getLevel(), pos), role);
        bridge.persistence().capture();
        return "storage=" + registration.id() + " slots=" + registration.slots().size() + " revision=" + registration.revision();
    }
    String registerCitizenStorage(CommandSourceStack source, UUID colony, UUID citizen, String role) throws CommandSyntaxException {
        requireStorageManager(source, colony, null);
        var registration = bridge.storage().registerCitizen(colony, citizen, role);
        bridge.persistence().capture();
        return "storage=" + registration.id() + " slots=" + registration.slots().size();
    }
    String registerBuilding(CommandSourceStack source, UUID colony, BlockPos table, BlockPos inventory) throws CommandSyntaxException {
        requireStorageManager(source, colony, table);
        requireStorageManager(source, colony, inventory);
        var workshop = bridge.storage().registerWorkshop(colony, position(source.getLevel(), table), position(source.getLevel(), inventory));
        bridge.persistence().capture();
        return "workshop=" + workshop.id() + " storage=" + workshop.registrationId();
    }
    String reidentifyStorage(CommandSourceStack source, UUID colony, BlockPos pos) throws CommandSyntaxException {
        if (!source.hasPermission(2)) throw new SecurityException("Storage repair requires operator permission");
        requireStorageManager(source, colony, pos);
        UUID id = bridge.storage().reidentify(colony, position(source.getLevel(), pos));
        bridge.persistence().capture();
        return "storage identity=" + id + " old obligations=UNKNOWN";
    }
    String stock(CommandSourceStack source, UUID colony) throws CommandSyntaxException {
        bridge.core().commands().status(context(source), colony);
        return bridge.storage().diagnostics(colony, bridge.serverTick());
    }
    private void requireStorageManager(CommandSourceStack source, UUID colonyId, BlockPos pos) throws CommandSyntaxException {
        context(source);
        var colony = bridge.core().registry().colony(colonyId);
        UUID actor = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;
        MemberRank rank = colony.rank(actor);
        if (!source.hasPermission(2) && rank != MemberRank.OWNER && rank != MemberRank.MANAGER) throw new SecurityException("Storage changes require owner or manager permission");
        if (!colony.available()) throw new IllegalStateException("Colony recovery/content blocked");
        if (pos != null) {
            if (!colony.territory().contains(position(source.getLevel(), pos))) throw new SecurityException("Storage position outside colony territory");
            if (source.getEntity() instanceof ServerPlayer player && !source.getLevel().mayInteract(player, pos)) throw new SecurityException("Storage world interaction denied");
        }
    }
    String metrics(CommandSourceStack source,UUID colony) throws CommandSyntaxException {
        if(colony==null) { if(!source.hasPermission(2)) throw new SecurityException("Server metrics require operator permission"); }
        else bridge.core().commands().status(context(source),colony);
        return bridge.minecraftMetrics().snapshot(colony).toString();
    }
    String status(CommandSourceStack source,UUID colony) throws CommandSyntaxException {
        var state=bridge.core().commands().status(context(source),colony);
        StringBuilder result=new StringBuilder(state.toString());
        var admission = bridge.core().admission();
        for (var resource : io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource.values()) {
            result.append("\nadmission=").append(resource).append(" used=").append(admission.used(resource))
                    .append(" limit=").append(admission.limits().resource(resource)).append(" overLimit=").append(admission.overLimit(resource));
        }
        if (bridge.chunks() != null) result.append("\nchunkFootprint=").append(bridge.chunks().footprint())
                .append(" blockTicking=").append(bridge.chunks().blockTicking()).append(" entityTicking=").append(bridge.chunks().entityTicking())
                .append(" ticketNanosHighWater=").append(bridge.chunks().ticketNanosHighWater());
        for (var citizen : bridge.core().registry().citizens(colony)) {
            result.append("\ncitizen=").append(citizen.citizenId()).append(" entity=").append(citizen.entityId())
                    .append(" epoch=").append(citizen.bindingEpoch()).append(" lifecycle=").append(citizen.lifecycle())
                    .append(" readiness=").append(citizen.readiness()).append(" citizenAdmission=").append(citizen.admission())
                    .append(" activeTimeTicks=").append(citizen.activeTimeTicks()).append(" profession=").append(citizen.professionId())
                    .append(" food=").append(citizen.needs().get("food"));
        }
        for (var work:bridge.core().workBoard().works()) if(work.colonyId().equals(colony)) {
            result.append("\nwork=").append(work.id()).append(" state=").append(work.state())
                    .append(" reason=").append(work.waitingReason()).append(" assignee=").append(work.assignee())
                    .append(" remainingActiveTicks=").append(work.remainingActiveTicks()).append(" revision=").append(work.revision());
        }
        return result.toString();
    }
    String inspect(CommandSourceStack source,UUID colony) throws CommandSyntaxException {
        var inspection=bridge.core().commands().inspect(context(source),colony);
        inspections.put(colony,new Inspection(inspection,inventorySnapshot(inspection),siteSnapshot(inspection),effectSnapshot(inspection)));
        StringBuilder result=new StringBuilder(inspection.toString());
        for(CitizenEntity entity:loaded.values()) {
            if(entity.citizenId()==null)continue;
            var record=bridge.core().registry().findCitizen(entity.citizenId()).orElse(null);
            if(record==null || !record.colonyId().equals(colony))continue;
            result.append("\nentity=").append(entity.getUUID()).append(" epoch=").append(entity.bindingEpoch()).append(" quarantine=").append(entity.isQuarantined());
            for(int slot=0;slot<9;slot++)result.append(" slot").append(slot).append('=').append(entity.inventory().getItem(slot));
        }
        for(var effect:inspection.effects()) result.append("\neffect=").append(effect.operationId()).append(" state=").append(effect.state()).append(" actualBlock=").append(inspections.get(colony).physicalEffects().get(effect.operationId()));
        for(var site:inspection.constructionSites()) result.append("\nsite=").append(site.workId()).append(" cursor=").append(site.cursor()).append(" actualWorldDigest=").append(inspections.get(colony).physicalSites().get(site.workId()));
        return result.toString();
    }
    String accept(CommandSourceStack source,UUID colony,UUID checkpoint) throws CommandSyntaxException {
        var inspected=inspections.get(colony);
        if(inspected==null)throw new IllegalStateException("Run recovery inspect before accepting world");
        if (!inspected.inventories().equals(inventorySnapshot(inspected.state()))) {
            inspections.remove(colony);
            throw new IllegalStateException("Physical inventory changed; inspect again");
        }
        if(!inspected.physicalSites().equals(siteSnapshot(inspected.state())) || !inspected.physicalEffects().equals(effectSnapshot(inspected.state()))) {
            inspections.remove(colony); throw new IllegalStateException("Physical blocks changed; inspect again");
        }
        bridge.core().commands().acceptWorld(context(source),colony,checkpoint,inspected.state());
        bridge.persistence().persistSnapshot(); inspections.remove(colony);
        for(CitizenEntity entity:loaded.values()) if(entity.citizenId()!=null)refresh(entity.citizenId());
        return "Physical world accepted without item creation/removal/compensation colony="+colony;
    }
    String bind(CommandSourceStack source,UUID citizen,UUID entityId) throws CommandSyntaxException {
        CitizenEntity selected=loaded.get(entityId);
        if(selected==null || !citizen.equals(selected.citizenId()) || !selected.isAlive())throw new IllegalStateException("Selected citizen embodiment is not ready");
        var record=bridge.core().commands().bind(context(source),citizen,entityId);
        selected.initializeIdentity(citizen,record.bindingEpoch());
        refresh(citizen); bridge.persistence().capture(); return "citizen="+citizen+" entity="+entityId+" epoch="+record.bindingEpoch();
    }
    private Map<UUID,net.minecraft.nbt.CompoundTag> inventorySnapshot(ColonyCommands.RecoveryInspection inspection) {
        Map<UUID,net.minecraft.nbt.CompoundTag> inventories=new HashMap<>();
        for (var observation:inspection.observations()) {
            CitizenEntity entity=loaded.get(observation.entityId());
            if (entity==null) continue;
            var inventory=new net.minecraft.nbt.CompoundTag();
            net.minecraft.world.ContainerHelper.saveAllItems(inventory,entity.inventory().getItems(),server.registryAccess());
            inventories.put(entity.getUUID(),inventory);
        }
        return Map.copyOf(inventories);
    }
    private String observedBlock(WorldPosition target) {
        var level=level(target.dimension()); var pos=new BlockPos(target.x(),target.y(),target.z());
        if(level==null || level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null || !level.isPositionEntityTicking(pos)) throw new IllegalStateException("Recovery target CHUNK_NOT_READY: "+target);
        return level.getBlockState(pos).toString();
    }
    private Map<UUID,String> effectSnapshot(ColonyCommands.RecoveryInspection inspection) {
        Map<UUID,String> result=new LinkedHashMap<>();
        for(var effect:inspection.effects()) result.put(effect.operationId(),observedBlock(effect.target()));
        return Map.copyOf(result);
    }
    private Map<UUID,String> siteSnapshot(ColonyCommands.RecoveryInspection inspection) {
        Map<UUID,String> result=new LinkedHashMap<>();
        var geometry=new io.github.kpuctajluk.colonyloom.minecraft.construction.MinecraftConstructionGeometry(server);
        for(var site:inspection.constructionSites()) {
            var layout=geometry.layout(site.workId(),site.colonyId(),bridge.core().registry().construction().definition(site.blueprintDigest()),site.origin(),site.rotation());
            try {
                var digest=java.security.MessageDigest.getInstance("SHA-256");
                for(var target:layout.targets()) { var bytes=observedBlock(target.position()).getBytes(java.nio.charset.StandardCharsets.UTF_8); digest.update((byte)(bytes.length>>8)); digest.update((byte)bytes.length); digest.update(bytes); }
                result.put(site.workId(),java.util.HexFormat.of().formatHex(digest.digest()));
            } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
        return Map.copyOf(result);
    }
    private void requireAvailable(){
        if(identityFailure!=null)throw new IllegalStateException(identityFailure);
        if(!bridge.persistence().isAvailable())throw new IllegalStateException(bridge.persistence().failureReason());
    }
    private ColonyCommands.CommandContext context(CommandSourceStack source) throws CommandSyntaxException {
        requireAvailable();
        UUID actor=source.getEntity() instanceof ServerPlayer player?player.getUUID():null;
        return new ColonyCommands.CommandContext(actor,source.hasPermission(2),new ColonyCommands.PhysicalChecks(){
            public void validateTerritory(Territory territory){
                if(!(source.getEntity() instanceof ServerPlayer player))throw new SecurityException("Colony founding requires a real player");
                ServerLevel level=source.getLevel();
                for(int x=territory.minX();x<=territory.maxX();x++)for(int z=territory.minZ();z<=territory.maxZ();z++){
                    BlockPos pos=new BlockPos(x,player.blockPosition().getY(),z);
                    if(!level.getWorldBorder().isWithinBounds(pos)||!level.mayInteract(player,pos))throw new SecurityException("Territory permission/world border denied");
                }
            }
            public void validateCitizenPosition(ColonyRuntime colony,WorldPosition position){
                ServerLevel level=level(position.dimension()); BlockPos pos=new BlockPos(position.x(),position.y(),position.z());
                if(level==null||!level.hasChunkAt(pos)||!level.isPositionEntityTicking(pos)||!level.getWorldBorder().isWithinBounds(pos))throw new IllegalStateException("CHUNK_NOT_READY or outside world border");
                if(position.y()<level.getMinBuildHeight()||position.y()+2>=level.getMaxBuildHeight())throw new IllegalArgumentException("Citizen position outside build height");
            }
            public void validateRecovery(ColonyRuntime colony,List<CitizenRecord> citizens,List<BindingRegistry.Observation> observations){
                for(CitizenRecord record:citizens){
                    if(record.lifecycle()!=CitizenRecord.Lifecycle.ALIVE)continue;
                    CitizenEntity entity=loaded.get(record.entityId());
                    if(entity==null||!entity.isAlive()||!record.citizenId().equals(entity.citizenId())||record.bindingEpoch()!=entity.bindingEpoch())throw new IllegalStateException("Recovery identity is not fully loaded and alive");
                }
                for(var observation:observations)if(!loaded.containsKey(observation.entityId()))throw new IllegalStateException("Known competing embodiment is not loaded");
            }
        });
    }
    private ServerLevel level(String dimension){return server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(dimension)));}
    private static WorldPosition position(ServerLevel level,BlockPos pos){return new WorldPosition(level.dimension().location().toString(),pos.getX(),pos.getY(),pos.getZ());}
}
