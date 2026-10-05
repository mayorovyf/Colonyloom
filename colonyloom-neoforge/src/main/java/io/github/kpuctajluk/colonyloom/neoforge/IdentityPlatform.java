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
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Server-scoped Minecraft observations and validations around the shared mutation owner. */
final class IdentityPlatform {
    private final MinecraftServer server;
    private final MinecraftServerRuntime bridge;
    private final io.github.kpuctajluk.colonyloom.minecraft.runtime.FoundingTerritoryValidation foundingValidation =
            new io.github.kpuctajluk.colonyloom.minecraft.runtime.FoundingTerritoryValidation();
    private final Map<UUID,CitizenEntity> loaded = new HashMap<>();
    private record Inspection(ColonyCommands.RecoveryInspection state, Map<UUID, net.minecraft.nbt.CompoundTag> inventories,Map<UUID,String> physicalSites,Map<UUID,String> physicalEffects,
            io.github.kpuctajluk.colonyloom.core.supply.SupplySnapshot supply,io.github.kpuctajluk.colonyloom.core.storage.StorageSnapshot storage) {}
    private final Map<UUID,Inspection> inspections = new HashMap<>();
    private String identityFailure;
    private UUID provisioningEntity;

    IdentityPlatform(MinecraftServer server,MinecraftServerRuntime bridge) { this.server=server; this.bridge=bridge; }
    private CitizenEntity.DeathFaultObserver deathObserver;
    void deathObserver(CitizenEntity.DeathFaultObserver observer) {
        if(deathObserver!=null)throw new IllegalStateException("Death observer already installed");
        deathObserver=Objects.requireNonNull(observer);
    }

    void reconcileLoaded() {
        for (ServerLevel level : server.getAllLevels()) for (Entity entity : level.getAllEntities()) join(entity);
    }
    void join(Entity entity) {
        if (!(entity instanceof CitizenEntity citizen)) return;
        citizen.runtimeMetrics(bridge.metrics());
        citizen.deathPreparation(() -> death(citizen));
        if (citizen.getUUID().equals(provisioningEntity)) return;
        citizen.setQuarantined(true);
        if (!bridge.persistence().isAvailable() || bridge.core().lifecycle()!=io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime.Lifecycle.RUNNING || citizen.citizenId()==null) return;
        if (bridge.persistence().retainsCitizen(citizen.citizenId())) return;
        if (identityFailure!=null) return;
        try {
            bridge.persistence().ensureSessionDirty();
            bridge.core().bindings().observe(citizen.citizenId(),citizen.getUUID(),citizen.bindingEpoch());
            loaded.put(citizen.getUUID(),citizen);
            refresh(citizen.citizenId());
            bridge.persistence().capture();
        } catch (BindingRegistry.HistoryOverflow overflow) {
            bridge.core().registry().findCitizen(citizen.citizenId()).ifPresent(record -> {
                bridge.core().registry().markRecoveryBlocked(record.colonyId(),bridge.persistence().checkpointId());
                for(var observed:loaded.values())bridge.core().registry().findCitizen(observed.citizenId()).filter(value -> value.colonyId().equals(record.colonyId())).ifPresent(value -> observed.setQuarantined(true));
                bridge.persistence().capture();
            });
            inspections.clear();
            LogUtils.getLogger().error(overflow.getMessage());
        } catch (RuntimeException error) {
            identityFailure="Identity reconciliation blocked: "+error.getMessage();
            for (CitizenEntity observed:loaded.values()) observed.setQuarantined(true);
            inspections.clear();
            LogUtils.getLogger().error(identityFailure,error);
        }
    }
    void leave(Entity entity) {
        if (!(entity instanceof CitizenEntity citizen) || citizen.citizenId()==null || !bridge.persistence().isAvailable()) return;
        if (bridge.persistence().retainsCitizen(citizen.citizenId())) return;
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
    private void death(CitizenEntity citizen) {
        if (citizen.citizenId()==null || !bridge.persistence().isAvailable()) return;
        var record=bridge.core().registry().findCitizen(citizen.citizenId()).orElse(null);
        if (record==null || record.lifecycle()!=CitizenRecord.Lifecycle.ALIVE || !record.entityId().equals(citizen.getUUID()) || record.bindingEpoch()!=citizen.bindingEpoch()) return;
        try {
            bridge.persistence().ensureSessionDirty();
            var cargo=new ArrayList<io.github.kpuctajluk.colonyloom.core.action.EffectRecord.DeathCargo>();
            for(int slot=0;slot<CitizenEntity.INVENTORY_SIZE;slot++) {
                var stack=citizen.inventory().getItem(slot);
                if(!stack.isEmpty())cargo.add(new io.github.kpuctajluk.colonyloom.core.action.EffectRecord.DeathCargo(slot,io.github.kpuctajluk.colonyloom.minecraft.storage.NativeItemDescriptor.describe(stack,server.registryAccess()),stack.getCount()));
            }
            var death=new io.github.kpuctajluk.colonyloom.core.action.EffectRecord.Death(citizen.getUUID(),cargo,List.of(),false);
            int before=death.cargoCount();
            var effect=new io.github.kpuctajluk.colonyloom.core.action.EffectRecord(UUID.randomUUID(),record.colonyId(),record.assignedWorkId(),record.citizenId(),record.bindingEpoch(),io.github.kpuctajluk.colonyloom.core.action.ActionContext.Kind.DEATH,position((ServerLevel)citizen.level(),citizen.blockPosition()),"native_final_death","colonyloom:inventory",before,before,io.github.kpuctajluk.colonyloom.core.action.EffectRecord.State.PREPARED,0,null,null,null,death);
            bridge.core().registry().effects().prepare(effect,io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.NORMAL);
            var observer=deathObserver;
            var context=observer==null ? null : new io.github.kpuctajluk.colonyloom.core.action.ActionContext(record.colonyId(),record.citizenId(),io.github.kpuctajluk.colonyloom.core.action.ActionContext.Kind.DEATH,effect.target(),io.github.kpuctajluk.colonyloom.core.action.ActionContext.AuthorityMode.COLONY,null,bridge.core().registry().colony(record.colonyId()).revision());
            if(observer!=null) citizen.observeDeathFaults(observer,context);
            citizen.observeDeathInventory(published -> {
                try {
                    int remaining=0;for(int slot=0;slot<CitizenEntity.INVENTORY_SIZE;slot++)remaining+=citizen.inventory().getItem(slot).getCount();
                    var drops=new ArrayList<io.github.kpuctajluk.colonyloom.core.action.EffectRecord.DeathDrop>();
                    for(var item:published)if(!item.getItem().isEmpty())drops.add(new io.github.kpuctajluk.colonyloom.core.action.EffectRecord.DeathDrop(item.getUUID(),io.github.kpuctajluk.colonyloom.minecraft.storage.NativeItemDescriptor.describe(item.getItem(),server.registryAccess()),item.getItem().getCount()));
                    var fact=new io.github.kpuctajluk.colonyloom.core.action.EffectRecord.Death(death.sourceEntityId(),death.cargo(),drops,true);
                    boolean ambiguous=remaining!=0 || !fact.conserved();
                    bridge.core().registry().effects().update(effect.observedDeath(fact,remaining,ambiguous));
                    if(ambiguous) bridge.core().registry().markRecoveryBlocked(record.colonyId(),bridge.persistence().checkpointId());
                    if(observer!=null) observer.observe(CitizenEntity.DeathFaultPoint.AFTER_FACT_BEFORE_NOTIFY,context);
                    bridge.persistence().capture();
                } catch(RuntimeException error) {
                    bridge.core().registry().markRecoveryBlocked(record.colonyId(),bridge.persistence().checkpointId());
                    LogUtils.getLogger().error("Death inventory observation blocked; actual drops are not compensated",error);
                }
            });
            bridge.core().commands().markDeath(citizen.citizenId());
            citizen.setQuarantined(true);
            bridge.persistence().capture();
            // Native dead=true is irrevocable here; the cancellable event has already returned.
            // BEFORE_EFFECT is before cargo removal, not before death event finality.
            if(observer!=null) observer.observe(CitizenEntity.DeathFaultPoint.BEFORE_EFFECT,context);
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
    String workplace(CommandSourceStack source,UUID citizen,UUID building) throws CommandSyntaxException {
        bridge.core().commands().assignWorkplace(context(source),citizen,building);bridge.persistence().capture();return "citizen="+citizen+" workplace="+building;
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
    String requestDelivery(CommandSourceStack source,UUID colony,BlockPos from,BlockPos to,String item,long count) throws CommandSyntaxException {
        var nativeItem=net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.ResourceLocation.parse(item))
                .filter(value -> value!=net.minecraft.world.item.Items.AIR).orElseThrow(() -> new IllegalArgumentException("Unknown delivery item"));
        var descriptor=io.github.kpuctajluk.colonyloom.minecraft.storage.NativeItemDescriptor.describe(new net.minecraft.world.item.ItemStack(nativeItem),server.registryAccess());
        var demand=bridge.core().commands().requestDelivery(context(source),UUID.randomUUID(),colony,
                position(source.getLevel(),from),position(source.getLevel(),to),descriptor,count);
        bridge.persistence().capture();return "demand="+demand.id()+" state="+demand.snapshot().status()+" required="+demand.snapshot().required();
    }
    String cancelDelivery(CommandSourceStack source,UUID demand) throws CommandSyntaxException {
        bridge.core().commands().cancelDelivery(context(source),demand);bridge.persistence().capture();
        return "demand="+demand+" state="+bridge.core().registry().supply().demand(demand).snapshot().status();
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
    void requireStorageManager(CommandSourceStack source, UUID colonyId, BlockPos pos) throws CommandSyntaxException {
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
        inspections.put(colony,new Inspection(inspection,inventorySnapshot(inspection),siteSnapshot(inspection),effectSnapshot(inspection),bridge.core().registry().supply().snapshot(),bridge.core().registry().storage().snapshot()));
        StringBuilder result=new StringBuilder(inspection.toString());
        result.append("\nopaque=").append(bridge.persistence().retainedReport(colony));
        long history=bridge.core().bindings().observations().stream().filter(value -> bridge.core().registry().findCitizen(value.citizenId()).map(record -> record.colonyId().equals(colony)).orElse(false)).count();
        result.append("\nidentityHistory=").append(history).append('/').append(BindingRegistry.MAX_OBSERVATIONS_PER_COLONY).append(" retired UUIDs never trimmed; overflow requires operator inspect/bind/remove conflicting entity then accept-world");
        for(CitizenEntity entity:loaded.values()) {
            if(entity.citizenId()==null)continue;
            var record=bridge.core().registry().findCitizen(entity.citizenId()).orElse(null);
            if(record==null || !record.colonyId().equals(colony))continue;
            result.append("\nentity=").append(entity.getUUID()).append(" epoch=").append(entity.bindingEpoch()).append(" quarantine=").append(entity.isQuarantined());
            for(int slot=0;slot<9;slot++)result.append(" slot").append(slot).append('=').append(entity.inventory().getItem(slot));
        }
        for(var effect:inspection.effects()) result.append("\neffect=").append(effect.operationId()).append(" state=").append(effect.state()).append(" actualPhysical=").append(inspections.get(colony).physicalEffects().get(effect.operationId()));
        for(var site:inspection.constructionSites()) result.append("\nsite=").append(site.workId()).append(" cursor=").append(site.cursor()).append(" actualWorldDigest=").append(inspections.get(colony).physicalSites().get(site.workId()));
        int unrecorded=0;
        for(var level:server.getAllLevels())for(var entity:level.getAllEntities())if(entity instanceof CitizenEntity citizen && citizen.citizenId()!=null) {
            var record=bridge.core().registry().findCitizen(citizen.citizenId()).orElse(null);
            if(record!=null && record.colonyId().equals(colony) && bridge.core().bindings().observations(record.citizenId()).stream().noneMatch(value -> value.entityId().equals(entity.getUUID()))) {
                if(unrecorded++<32)result.append("\nunrecordedEmbodiment=").append(entity.getUUID()).append(" custody=UNKNOWN quarantined; remove conflicting entity before accept-world");
            }
        }
        result.append("\nunrecordedEmbodimentCount=").append(unrecorded);
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
            inspections.remove(colony); throw new IllegalStateException("Physical blocks or death cargo/drops changed; inspect again");
        }
        if(!inspected.supply().equals(bridge.core().registry().supply().snapshot())||!inspected.storage().equals(bridge.core().registry().storage().snapshot())) {
            inspections.remove(colony);throw new IllegalStateException("Economic obligations changed; inspect again");
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
        for(var level:server.getAllLevels())for(var physical:level.getAllEntities())if(physical instanceof CitizenEntity entity && entity.citizenId()!=null) {
            var record=bridge.core().registry().findCitizen(entity.citizenId()).orElse(null);
            if(record==null || !record.colonyId().equals(inspection.colonyId()))continue;
            var inventory=new net.minecraft.nbt.CompoundTag();
            net.minecraft.world.ContainerHelper.saveAllItems(inventory,entity.inventory().getItems(),server.registryAccess());
            inventories.put(entity.getUUID(),inventory);
        }
        var examined=new java.util.HashSet<io.github.kpuctajluk.colonyloom.core.storage.StorageId>();
        for(var registration:bridge.core().registry().storage().registrations(inspection.colonyId()))for(var slot:registration.slots()) {
            if(slot.storage().bindingEpoch()>0||!examined.add(slot.storage()))continue;
            var container=bridge.storage().recoveryContainer(slot);if(container==null)throw new IllegalStateException("Recovery storage CHUNK_NOT_READY or identity conflict: "+slot.storage());
            var inventory=new net.minecraft.nbt.CompoundTag();var stacks=net.minecraft.core.NonNullList.withSize(container.getContainerSize(),net.minecraft.world.item.ItemStack.EMPTY);
            for(int index=0;index<stacks.size();index++)stacks.set(index,container.getItem(index).copy());
            net.minecraft.world.ContainerHelper.saveAllItems(inventory,stacks,server.registryAccess());inventories.put(slot.storage().identity(),inventory);
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
        for(var effect:inspection.effects()) {
            if(effect.kind()==io.github.kpuctajluk.colonyloom.core.action.ActionContext.Kind.DEATH) {
                result.put(effect.operationId(),observedDeath(effect));continue;
            }
            if(effect.craft()!=null) {
                var craft=effect.craft();var physical=new StringBuilder("table=").append(observedBlock(craft.table()));
                for(var input:craft.inputs())physical.append(" input=").append(input.slot()).append(':').append(observedTransferSlot(input.slot()));
                for(var output:craft.outputs())physical.append(" output=").append(output.slot()).append(':').append(observedTransferSlot(output.slot()));
                result.put(effect.operationId(),physical.toString());continue;
            }
            if(effect.transfer()==null) {result.put(effect.operationId(),observedBlock(effect.target()));continue;}
            var transfer=effect.transfer();
            result.put(effect.operationId(),observedTransferSlot(transfer.source())+" -> "+observedTransferSlot(transfer.destination()));
        }
        return Map.copyOf(result);
    }
    private String observedDeath(io.github.kpuctajluk.colonyloom.core.action.EffectRecord effect) {
        // Require the original site loaded, without acquiring new chunks during an operator command.
        observedBlock(effect.target());
        var level=level(effect.target().dimension());var witness=effect.death();
        UUID sourceId=witness==null?bridge.core().registry().citizen(effect.citizenId()).entityId():witness.sourceEntityId();
        var source=level.getEntity(sourceId);var physical=new StringBuilder("death source=").append(sourceId);
        if(source instanceof CitizenEntity citizen) {
            var inventory=new net.minecraft.nbt.CompoundTag();net.minecraft.world.ContainerHelper.saveAllItems(inventory,citizen.inventory().getItems(),server.registryAccess());
            physical.append(" alive=").append(citizen.isAlive()).append(" epoch=").append(citizen.bindingEpoch()).append(" cargoNow=").append(inventory);
        } else physical.append(" source=UNKNOWN(absent/unloaded/removed)");
        physical.append(" cargoBefore=").append(witness==null?"UNKNOWN(legacy hash-only witness)":witness.cargo());
        physical.append(" publication=").append(witness!=null && witness.publicationObserved()?"OBSERVED":"UNKNOWN");
        if(witness!=null)for(var drop:witness.drops()) {
            var entity=level.getEntity(drop.entityId());physical.append(" matchedDrop=").append(drop.entityId()).append(" expected=").append(drop.item()).append('x').append(drop.count());
            physical.append(" actual=").append(entity instanceof net.minecraft.world.entity.item.ItemEntity item && !item.isRemoved()?item.getItem().saveOptional(server.registryAccess()):"UNKNOWN(absent/picked-up/unloaded)");
        }
        // PREPARED/legacy witnesses cannot identify ownership of nearby drops. Expose candidates as
        // UNKNOWN, but pin exact UUID/item state so pickup or modification invalidates acceptance.
        var target=effect.target();var candidates=level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(target.x()-8,target.y()-8,target.z()-8,target.x()+9,target.y()+9,target.z()+9));
        if(candidates.size()>64)throw new IllegalStateException("Death recovery candidate envelope exceeded; unload unrelated drops before inspect");
        candidates.sort(Comparator.comparing(Entity::getUUID));
        for(var item:candidates)physical.append(" nearbyDrop=UNKNOWN-ownership:").append(item.getUUID()).append(':').append(item.getItem().saveOptional(server.registryAccess()));
        return physical.toString();
    }
    private String observedTransferSlot(io.github.kpuctajluk.colonyloom.core.storage.StockRegion slot) {
        var id=slot.storage();
        if(id.bindingEpoch()>0) {
            var citizen=bridge.core().registry().citizen(id.identity());
            if(citizen.lifecycle()!=CitizenRecord.Lifecycle.ALIVE||citizen.bindingEpoch()!=id.bindingEpoch())
                return "historical-custody="+id+" lifecycle="+citizen.lifecycle()+" currentEpoch="+citizen.bindingEpoch()+" inventory=UNKNOWN";
        }
        var container=bridge.storage().recoveryContainer(slot);
        if(container==null)throw new IllegalStateException("Recovery transfer participant not ready: "+slot);
        return container.getItem(slot.slot()).saveOptional(server.registryAccess()).toString();
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
    ColonyCommands.CommandContext context(CommandSourceStack source) throws CommandSyntaxException {
        requireAvailable();
        UUID actor=source.getEntity() instanceof ServerPlayer player?player.getUUID():null;
        return new ColonyCommands.CommandContext(actor,source.hasPermission(2),new ColonyCommands.PhysicalChecks(){
            public void validateTerritory(Territory territory){
                if(!(source.getEntity() instanceof ServerPlayer player))throw new SecurityException("Colony founding requires a real player");
                if(source.getLevel()!=player.serverLevel())throw new SecurityException("Territory level differs from player level");
                foundingValidation.validate(server.getTickCount(),player,territory);
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
                for(var observation:observations)if(bridge.core().registry().citizen(observation.citizenId()).lifecycle()==CitizenRecord.Lifecycle.ALIVE&&!loaded.containsKey(observation.entityId()))throw new IllegalStateException("Known competing embodiment is not loaded");
                for(var level:server.getAllLevels())for(var physical:level.getAllEntities())if(physical instanceof CitizenEntity entity && entity.citizenId()!=null) {
                    var record=bridge.core().registry().findCitizen(entity.citizenId()).orElse(null);
                    if(record!=null && record.colonyId().equals(colony.colonyId()) && bridge.core().bindings().observations(record.citizenId()).stream().noneMatch(value -> value.entityId().equals(entity.getUUID())))
                        throw new IllegalStateException("Unrecorded embodiment exceeds scoped history; remove it from the world before operator accept-world: "+entity.getUUID());
                }
            }
        });
    }
    private ServerLevel level(String dimension){return server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(dimension)));}
    private static WorldPosition position(ServerLevel level,BlockPos pos){return new WorldPosition(level.dimension().location().toString(),pos.getX(),pos.getY(),pos.getZ());}
}
