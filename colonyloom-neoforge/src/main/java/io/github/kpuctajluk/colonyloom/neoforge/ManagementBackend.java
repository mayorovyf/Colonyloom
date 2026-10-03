package io.github.kpuctajluk.colonyloom.neoforge;

import io.github.kpuctajluk.colonyloom.core.colony.MemberRank;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.*;
import io.github.kpuctajluk.colonyloom.core.management.ManagementSession;
import io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime;
import io.github.kpuctajluk.colonyloom.minecraft.view.ManagementViews;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** Network command adapter: same authoritative handlers and physical checks as Brigadier. */
final class ManagementBackend implements ManagementSession.Backend {
    private final MinecraftServerRuntime runtime;
    private final IdentityPlatform identity;
    private final Supplier<ServerPlayer> actor;
    private final Supplier<List<String>> blueprints;
    private static final class PreparedView {
        final Subscription subscription;
        ManagementViews.Preparation pending;
        long latestRevision = -1;
        PreparedView(Subscription subscription) { this.subscription = subscription; }
    }
    private final Map<UUID, PreparedView> preparedViews = new HashMap<>();
    ManagementBackend(MinecraftServerRuntime runtime,IdentityPlatform identity,Supplier<ServerPlayer> actor,Supplier<List<String>> blueprints) {
        this.runtime=runtime;this.identity=identity;this.actor=actor;this.blueprints=blueprints;
    }
    public ManagementSession.Authority authorize(UUID colonyId) {
        var colony=runtime.core().registry().colonies().stream().filter(c -> c.colonyId().equals(colonyId)).findFirst().orElse(null);
        return new ManagementSession.Authority(colony!=null&&colony.rank(actor.get().getUUID())!=null,colony==null?0:colony.authorityRevision());
    }
    private void scope(Command command) {
        if(command.body() instanceof CreateColony)return;
        if(!authorize(command.colonyId()).allowed())throw new SecurityException("NO_ACCESS");
        var registry=runtime.core().registry();
        switch(command.body()) {
            case AssignProfession body -> {if(!registry.citizen(body.citizenId()).colonyId().equals(command.colonyId()))throw new SecurityException("NO_ACCESS");}
            case AssignWorkplace body -> {if(!registry.citizen(body.citizenId()).colonyId().equals(command.colonyId()))throw new SecurityException("NO_ACCESS");}
            case CancelWork body -> {if(!registry.workBoard().work(body.workId()).colonyId().equals(command.colonyId()))throw new SecurityException("NO_ACCESS");}
            case PrioritizeWork body -> {if(!registry.workBoard().work(body.workId()).colonyId().equals(command.colonyId()))throw new SecurityException("NO_ACCESS");}
            default -> {}
        }
    }
    public long targetRevision(Command command) {
        scope(command);var registry=runtime.core().registry();
        return switch(command.body()) {
            case CreateColony ignored -> 0;
            case AssignProfession body -> registry.citizen(body.citizenId()).revision();
            case AssignWorkplace body -> registry.citizen(body.citizenId()).revision();
            case CancelWork body -> registry.workBoard().work(body.workId()).commandRevision();
            case PrioritizeWork body -> registry.workBoard().work(body.workId()).commandRevision();
            default -> registry.colony(command.colonyId()).revision();
        };
    }
    public Result execute(Command command) {
        scope(command);
        try {
            var player=actor.get();
            var source=player.createCommandSourceStack().withPermission(0);
            var context=identity.context(source);var commands=runtime.core().commands();
            UUID object;long revision;
            switch(command.body()) {
                case CreateColony body -> {var value=commands.createColony(context,UUID.randomUUID(),body.name(),body.territory());object=value.colonyId();revision=value.revision();}
                case AssignProfession body -> {var value=commands.assignProfession(context,body.citizenId(),body.professionId());object=value.citizenId();revision=value.revision();}
                case AssignWorkplace body -> {var value=commands.assignWorkplace(context,body.citizenId(),body.workshopId());object=value.citizenId();revision=value.revision();}
                case Build body -> {dimension(player,body.origin());var value=commands.build(context,UUID.randomUUID(),command.colonyId(),body.blueprintId(),body.origin(),body.rotation());object=value.id();revision=value.revision();}
                case CancelWork body -> {var value=commands.cancelWork(context,body.workId());object=value.id();revision=value.commandRevision();}
                case PrioritizeWork body -> {var value=commands.prioritizeWork(context,body.workId(),body.priority());object=value.id();revision=value.commandRevision();}
                case SetMember body -> {var rank=switch(body.rank()){case "manager" -> MemberRank.MANAGER;case "viewer" -> MemberRank.VIEWER;default -> null;};var value=commands.setMember(context,command.colonyId(),body.playerId(),rank);object=value.colonyId();revision=value.revision();}
                case SetOwner body -> {var value=commands.setOwner(context,command.colonyId(),body.playerId());object=value.colonyId();revision=value.revision();}
                case RegisterStorage body -> {dimension(player,body.position());identity.requireStorageManager(source,command.colonyId(),block(body.position()));var value=runtime.storage().register(command.colonyId(),body.position(),body.role());object=value.id();revision=value.revision();}
                case RegisterWorkshop body -> {dimension(player,body.table());dimension(player,body.inventory());identity.requireStorageManager(source,command.colonyId(),block(body.table()));identity.requireStorageManager(source,command.colonyId(),block(body.inventory()));var value=runtime.storage().registerWorkshop(command.colonyId(),body.table(),body.inventory());object=value.id();revision=value.revision();}
            }
            runtime.persistence().capture();return new Result(command.sequence(),Status.ACCEPTED,"ACCEPTED",object,revision);
        } catch(com.mojang.brigadier.exceptions.CommandSyntaxException failure) {return new Result(command.sequence(),Status.REJECTED,"INVALID_COMMAND",null,0);}
    }
    public boolean viewReady(Subscription subscription) {
        var prepared = preparedViews.computeIfAbsent(subscription.subscriptionId(), ignored -> new PreparedView(subscription));
        if (!prepared.subscription.equals(subscription)) throw new IllegalArgumentException("INVALID_SUBSCRIPTION");
        var budgets = runtime.core().budgets();
        if (prepared.pending == null) {
            if (prepared.latestRevision >= runtime.serverTick() || !budgets.timeAvailable()
                    || budgets.used(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget.VIEW_ROWS)
                    >= budgets.limits().budget(io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget.VIEW_ROWS)) return false;
            prepared.pending = new ManagementViews.Preparation(runtime.core().registry(), actor.get().getUUID(), subscription,
                    runtime.core().commands().professions().stream().map(p -> p.id()).sorted().toList(), blueprints.get(), runtime.serverTick());
        }
        return prepared.pending.advance(budgets);
    }
    public ViewData view(Subscription subscription) {
        var prepared = preparedViews.get(subscription.subscriptionId());
        if (prepared == null || prepared.pending == null || !prepared.subscription.equals(subscription)) throw new IllegalStateException("VIEW_NOT_READY");
        ViewData result = prepared.pending.result();
        prepared.latestRevision = result.stateRevision();
        prepared.pending = null;
        return result;
    }
    public int preparationBytes(Subscription subscription) {
        var prepared = preparedViews.get(subscription.subscriptionId());
        return prepared != null && prepared.pending != null ? io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.VIEW_BYTES : 0;
    }
    public void cancelView(Subscription subscription) {
        var prepared = preparedViews.remove(subscription.subscriptionId());
        if (prepared != null && prepared.pending != null) prepared.pending.cancel();
    }
    private static void dimension(ServerPlayer player,WorldPosition p) {if(!p.dimension().equals(player.level().dimension().location().toString()))throw new SecurityException("NO_ACCESS");}
    private static BlockPos block(WorldPosition p) {return new BlockPos(p.x(),p.y(),p.z());}
}
