package io.github.kpuctajluk.colonyloom.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.MemberRank;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.*;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.minecraft.network.ManagementPayloads.*;
import io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime;
import io.github.kpuctajluk.colonyloom.neoforge.ConstructionExecutorEvent;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Real logged-in connections, public fixture commands, and passive wire observations only. */
final class ManagementScenario {
    private static final UUID OWNER = offline("UIOwner"), VIEWER = offline("UIGuest");
    private static final BlockPos ORIGIN = new BlockPos(8,64,8), CITIZEN = new BlockPos(12,64,15);
    private static final BlockPos WAREHOUSE = new BlockPos(6,64,12), BUFFER = new BlockPos(7,64,8);
    private static final BlockPos TABLE = new BlockPos(16,64,12), WORKSHOP = new BlockPos(17,64,12);
    private record Wire(boolean owner, boolean outbound, CustomPacketPayload payload) {}
    private static final class Flight {
        final ViewDelta delta;
        final int tick;
        boolean acknowledged;
        Flight(ViewDelta delta,int tick) { this.delta=delta;this.tick=tick; }
    }
    private static final class Run {
        MinecraftServerRuntime runtime;
        ServerPlayer owner, viewer;
        UUID colony, citizen, otherColony, work;
        ServerPlayer deadOwner, respawnedOwner;
        Connection respawnConnection;
        CitizenEntity protectedCitizen;
        UUID respawnSummary;
        boolean deathScheduled, respawnVerified, respawnSummaryVerified;
        int ownerCommandInputs, deathCommandInputs;
        final Map<Long,Command> ownerCommands = new LinkedHashMap<>();
        boolean priorityAccepted, cancelAccepted;
        int ticks, forcedAt=-1, builtAt=-1;
        boolean done, published, buildVerified, revoked;
        volatile String observerFailure;
        final ConcurrentLinkedQueue<Wire> wire = new ConcurrentLinkedQueue<>();
        final Map<Long,Command> viewerCommands = new LinkedHashMap<>();
        final Map<UUID,UUID> viewerSubscriptions = new LinkedHashMap<>();
        final Map<UUID,Flight> flights = new LinkedHashMap<>();
        final Map<UUID,Integer> snapshotCounts = new LinkedHashMap<>(), deltaCounts = new LinkedHashMap<>();
        int timeoutProofs;
        Command ownerBuild;
        Result ownerBuildResult;
        int buildInputs, buildResults, viewerDenied, guessedDenied, accessClosed;
        int snapshots, deltas, postRevokeData;
        volatile boolean proofFinished;
        long calibrationWarmupStart, calibrationMeasureStart;
        long calibrationWindowStart, calibrationWindowUnits;
        int calibrationWindows;
    }
    private final Map<MinecraftServer,Run> runs = new IdentityHashMap<>();
    ManagementScenario() {
        NeoForge.EVENT_BUS.addListener(this::configure);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,this::tick);
    }
    private static UUID offline(String name) { return UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(StandardCharsets.UTF_8)); }
    private static Path root() { String value=System.getProperty("colonyloom.test.managementRoot","");return value.isEmpty()?null:Path.of(value); }
    private static boolean disposable(MinecraftServer server,Path root) {
        return root!=null && root.isAbsolute() && Files.isRegularFile(root.resolve("colonyloom-management-test"))
                && server.isDedicatedServer() && Files.isRegularFile(server.getWorldPath(LevelResource.ROOT).resolve("colonyloom-test-world"));
    }
    private void configure(ConstructionExecutorEvent event) {
        if(root()!=null) runs.computeIfAbsent(event.server(),ignored -> new Run()).runtime=event.runtime();
    }
    private void tick(ServerTickEvent.Post event) {
        Path root=root();if(root==null)return;
        var server=event.getServer();var run=runs.computeIfAbsent(server,ignored -> new Run());if(run.done)return;
        try {
            if(!disposable(server,root))throw new IllegalStateException("Management smoke requires absolute marked root and marked dedicated disposable world");
            if(++run.ticks>(Boolean.getBoolean("colonyloom.test.managementCalibration") ? 48_000 : 18000))throw new IllegalStateException("Management timeout: colony="+run.colony+" inputs="+run.buildInputs+" results="+run.buildResults+" denied="+run.viewerDenied+" revoked="+run.revoked);
            if(run.observerFailure!=null)throw new IllegalStateException(run.observerFailure);
            if(run.runtime==null)return;
            if (run.proofFinished) {
                calibrateViews(server, root, run);
                return;
            }
            if(run.forcedAt<0) {
                run.owner=server.getPlayerList().getPlayerByName("UIOwner");run.viewer=server.getPlayerList().getPlayerByName("UIGuest");
                if(run.owner==null||run.viewer==null) {run.owner=null;return;}
                require(root,run.owner.getUUID().equals(OWNER)&&run.viewer.getUUID().equals(VIEWER),"real_offline_players","UIOwner="+run.owner.getUUID()+" UIGuest="+run.viewer.getUUID());
                require(root,!server.getPlayerList().isOp(run.owner.getGameProfile())&&!server.getPlayerList().isOp(run.viewer.getGameProfile()),"clients_not_operators","only setup command stacks receive permission2");
                observe(run.owner,true,run);observe(run.viewer,false,run);
                for(int x=0;x<2;x++)for(int z=0;z<2;z++)server.overworld().setChunkForced(x,z,true);
                for(int x=4;x<6;x++)for(int z=4;z<6;z++)server.overworld().setChunkForced(x,z,true);
                run.forcedAt=run.ticks;
            }
            else {
                var owner=server.getPlayerList().getPlayerByName("UIOwner");
                var viewer=server.getPlayerList().getPlayerByName("UIGuest");
                if(owner!=null&&owner!=run.owner) {
                    if(run.deathScheduled&&!run.respawnVerified) {
                        require(root,owner!=run.deadOwner&&owner.isAlive()&&owner.getUUID().equals(OWNER)
                                &&owner.connection==run.deadOwner.connection&&owner.connection.getConnection()==run.respawnConnection
                                &&owner.connection.player==owner&&!run.deadOwner.isAlive(),
                                "actual_owner_respawn_actor","old ServerPlayer="+System.identityHashCode(run.deadOwner)
                                +" new ServerPlayer="+System.identityHashCode(owner)+" persistent Connection="+System.identityHashCode(run.respawnConnection)
                                +" listener.player is fresh active actor");
                        run.respawnedOwner=owner;run.respawnVerified=true;
                        require(root,server.overworld().getEntity(run.protectedCitizen.getUUID())==run.protectedCitizen&&run.protectedCitizen.isAlive(),
                                "respawn_fixture_citizen_unaffected","same accepted physical NPC="+run.protectedCitizen.getUUID());
                        signal(root,"owner-respawned",new JsonObject());
                    } else {
                        require(root,owner.getUUID().equals(OWNER)&&!server.getPlayerList().isOp(owner.getGameProfile()),"reconnected_owner_identity","new live non-operator owner connection="+owner.getUUID());
                        observe(owner,true,run);
                    }
                }
                if(viewer!=null&&viewer!=run.viewer) {
                    require(root,viewer.getUUID().equals(VIEWER)&&!server.getPlayerList().isOp(viewer.getGameProfile()),"reconnected_viewer_identity","new live non-operator viewer connection="+viewer.getUUID());
                    observe(viewer,false,run);
                }
                run.owner=owner;run.viewer=viewer;
            }
            drain(root,run);
            boolean clientsDone=Files.isRegularFile(root.resolve("owner-done"))&&Files.isRegularFile(root.resolve("viewer-done"));
            if((run.owner==null||run.viewer==null)&&!clientsDone)return;
            if(run.ticks-run.forcedAt<40||!server.overworld().isPositionEntityTicking(CITIZEN)||!server.overworld().isPositionEntityTicking(new BlockPos(72,64,72)))return;
            if(run.colony==null)setup(server,root,run);
            if(!run.published) {
                var record=run.runtime.core().registry().citizen(run.citizen);
                if(record.readiness()!=CitizenRecord.Readiness.READY||!(server.overworld().getEntity(record.entityId()) instanceof CitizenEntity))return;
                var fixture=new JsonObject();fixture.addProperty("colony",run.colony.toString());fixture.addProperty("citizen",run.citizen.toString());fixture.addProperty("otherColony",run.otherColony.toString());fixture.addProperty("viewer",VIEWER.toString());
                signal(root,"fixture.json",fixture);run.published=true;
                fact(root,"authoritative_fixture",true,"real ready citizen="+run.citizen+"; public commands created colonies, storage, workshop and viewer membership");
            }
            if(Files.isRegularFile(root.resolve("build-requested"))&&!run.buildVerified) {
                if(run.buildInputs<2||run.buildResults<2)return;
                verifyBuild(server,root,run,false);run.buildVerified=true;run.builtAt=run.ticks;
                signal(root,"build-verified",new JsonObject());
            }
            if(Files.isRegularFile(root.resolve("revoke-request"))&&!run.revoked)revoke(server,root,run);
            if(Files.isRegularFile(root.resolve("owner-death-request"))&&!run.deathScheduled) {
                require(root,run.buildVerified&&run.priorityAccepted&&run.cancelAccepted&&Files.isRegularFile(root.resolve("cancel-requested")),
                        "death_after_accepted_commands","wire observed accepted actual PrioritizeWork and CancelWork before death");
                require(root,disposable(server,root)&&run.owner.getUUID().equals(OWNER)&&run.owner.isAlive(),
                        "death_only_marked_fixture_owner","marked dedicated disposable world; killing only real UIOwner");
                run.deadOwner=run.owner;run.respawnConnection=run.owner.connection.getConnection();
                var record=run.runtime.core().registry().citizen(run.citizen);
                run.protectedCitizen=(CitizenEntity)server.overworld().getEntity(record.entityId());
                require(root,run.protectedCitizen!=null&&run.protectedCitizen.isAlive(),"death_fixture_citizen_alive","accepted physical NPC before owner death");
                run.deathCommandInputs=run.ownerCommandInputs;run.deathScheduled=true;
                command(server,run,"kill UIOwner");
                require(root,run.deadOwner.isDeadOrDying(),"real_owner_death","normal Minecraft /kill killed original ServerPlayer once");
            }
            if(run.deathScheduled&&!Files.isRegularFile(root.resolve("owner-respawn-done"))) {
                require(root,run.ownerCommandInputs==run.deathCommandInputs,"respawn_no_command_replay",
                        "actual owner command inputs="+run.ownerCommandInputs+" before death="+run.deathCommandInputs);
            }
            if(Files.isRegularFile(root.resolve("owner-revoke-form-request"))&&!Files.isRegularFile(root.resolve("owner-form-authority-revoked"))) {
                require(root,disposable(server,root)&&Files.isRegularFile(root.resolve("viewer-proof-done"))&&Files.isRegularFile(root.resolve("owner-respawn-done")),
                        "owner_form_revoke_fixture_boundary","marked dedicated disposable world, original viewer proof complete, owner respawn complete");
                var request=JsonParser.parseString(Files.readString(root.resolve("owner-revoke-form-request"))).getAsJsonObject();
                UUID created=UUID.fromString(request.get("colony").getAsString());
                var colony=run.runtime.core().registry().colony(created);
                require(root,!created.equals(run.colony)&&!created.equals(run.otherColony)&&colony.name().equals("UICreated")&&colony.rank(OWNER)==MemberRank.OWNER,
                        "isolated_created_colony_form","revoking only actual client-created colony="+created+" blueprint="+request.get("blueprint").getAsString());
                command(server,run,"colonyloom owner set "+created+" UIGuest");
                var capture=new Capture();
                int code=server.getCommands().getDispatcher().execute("colonyloom member set "+created+" UIOwner none",
                        run.viewer.createCommandSourceStack().withSource(capture));
                require(root,code==1,"public_owner_form_revoke_command","genuine connected new owner UIGuest public member command: "+String.join("\n",capture.messages));
                colony=run.runtime.core().registry().colony(created);
                require(root,colony.rank(OWNER)==null&&colony.rank(VIEWER)==MemberRank.OWNER,
                        "owner_form_authority_revoked","actual public created-colony ownership handoff and removal of old owner");
                signal(root,"owner-form-authority-revoked",new JsonObject());
            }
            if(run.buildVerified&&run.revoked&&Files.isRegularFile(root.resolve("owner-done"))&&Files.isRegularFile(root.resolve("viewer-done"))) {
                if(run.ticks-run.builtAt<40)return;
                require(root,Files.isRegularFile(root.resolve("cancel-requested")),"ui_cancel_signal","owner finished only after actual accepted cancel");
                require(root,run.deathScheduled&&run.respawnVerified&&run.respawnSummaryVerified&&Files.isRegularFile(root.resolve("owner-respawn-done")),
                        "actual_respawn_completed","normal death/respawn replaced ServerPlayer, retained Connection, refreshed actual summary before reconnect");
                require(root,run.ownerCommandInputs==run.deathCommandInputs,"respawn_reconnect_no_command_replay",
                        "all actual owner CommandPayload inputs remained="+run.ownerCommandInputs+" after death/respawn and reconnect");
                verifyBuild(server,root,run,true);
                require(root,run.viewerDenied>0&&run.guessedDenied>0,"viewer_no_mutation_scope","real denied builds="+run.viewerDenied+" guessed colony closed="+run.guessedDenied+"; only owner construction exists");
                require(root,run.timeoutProofs>0,"server_timeout_probe_completed","independent real bounded outbound timeout proofs="+run.timeoutProofs);
                require(root,run.accessClosed>0&&run.postRevokeData==0,"revoked_no_data","ACCESS_DENIED closures="+run.accessClosed+" post-revoke pages="+run.postRevokeData);
                require(root,run.runtime.core().registry().colony(run.colony).rank(VIEWER)==null&&run.runtime.core().registry().colony(run.otherColony).rank(VIEWER)==null,"authority_revoked","viewer has no rank on either colony");
                var formRequest=JsonParser.parseString(Files.readString(root.resolve("owner-revoke-form-request"))).getAsJsonObject();
                require(root,run.runtime.core().registry().colony(UUID.fromString(formRequest.get("colony").getAsString())).rank(OWNER)==null
                        &&Files.isRegularFile(root.resolve("owner-revoked-form-cleared")),"revoked_owner_form_completed",
                        "old UIOwner has no created-colony rank and actual production build form cleared after ACCESS_DENIED");
                if (Boolean.getBoolean("colonyloom.test.managementCalibration")) {
                    command(server,run,"colonyloom member set " + run.colony + " UIGuest viewer");
                    signal(root,"calibration-authority-ready",new JsonObject());
                    run.proofFinished = true;
                    return;
                }
                var sample = run.runtime.core().metrics().snapshot().get("VIEW_UNIT");
                require(root, sample.count() > 0 && sample.p99Nanos() > 0, "actual_view_unit_measurement",
                        "units=" + sample.count() + " p99=" + sample.p99Nanos() + " max=" + sample.maxNanos());
                int viewRows = (int)Math.min(100L, Math.max(1L, 400_000L / sample.p99Nanos()));
                var calibration = new JsonObject();
                calibration.addProperty("budget", "VIEW_ROWS");
                calibration.addProperty("targetNanos", 400_000);
                calibration.addProperty("measuredP99UnitNanos", sample.p99Nanos());
                calibration.addProperty("maxUnitNanos", sample.maxNanos());
                calibration.addProperty("unitCount", sample.count());
                calibration.addProperty("calculatedRowsPerTick", viewRows);
                calibration.addProperty("fullDurationPlatformBarrierPassed", false);
                calibration.addProperty("productionProfileFrozen", false);
                Files.writeString(root.resolve("view-calibration.json"), calibration.toString(), StandardOpenOption.CREATE_NEW);
                Files.writeString(root.resolve("view-measured.toml"), "# Actual UI smoke measurement; not a frozen full-duration profile\n[budgets]\nviewRowsPerTick=" + viewRows + "\n", StandardOpenOption.CREATE_NEW);
                fact(root,"server-done",true,"real two-client authority, scope, dedup and unacknowledged delta revocation verified; dedicated fixture stopping");
                signal(root,"server-done",new JsonObject());run.done=true;server.halt(false);
            }
        } catch(Exception failure) {
            run.done=true;
            try {fact(root,"failure",false,failure.toString());}catch(Exception evidence){failure.addSuppressed(evidence);}
            org.slf4j.LoggerFactory.getLogger(ManagementScenario.class).error("Management smoke failed",failure);
            // A bad opt-in must never halt an ordinary server, integrated server, or unmarked save.
            if(disposable(server,root))server.halt(false);
        }
    }

    private static void calibrateViews(MinecraftServer server, Path root, Run run) throws Exception {
        if (!Files.isRegularFile(root.resolve("owner-calibration-ready")) || !Files.isRegularFile(root.resolve("viewer-calibration-ready"))) return;
        if (Files.isRegularFile(root.resolve("calibration-done"))) {
            if (!Files.isRegularFile(root.resolve("owner-calibration-finished")) || !Files.isRegularFile(root.resolve("viewer-calibration-finished"))) return;
            fact(root, "server-done", true, "Real two-client full-duration calibration and client completion witnessed");
            signal(root, "server-done", new JsonObject());
            run.done = true;
            server.halt(false);
            return;
        }
        long now = System.nanoTime();
        if (run.calibrationWarmupStart == 0) {
            run.calibrationWarmupStart = now;
            return;
        }
        if (run.calibrationMeasureStart == 0) {
            if (now - run.calibrationWarmupStart < 300_000_000_000L) return;
            run.runtime.metrics().reset();
            run.calibrationWindowStart = now;
            run.calibrationMeasureStart = now;
            return;
        }
        if (server.getPlayerList().getPlayer(run.owner.getUUID()) != run.owner || server.getPlayerList().getPlayer(run.viewer.getUUID()) != run.viewer) {
            throw new IllegalStateException("Full-duration calibration lost a real graphical client");
        }
        if (now - run.calibrationWindowStart < 60_000_000_000L && now - run.calibrationMeasureStart < 600_000_000_000L) return;
        var sample = run.runtime.metrics().snapshot().get("VIEW_UNIT");
        if (now - run.calibrationWindowStart >= 60_000_000_000L) {
            require(root, sample.count() > run.calibrationWindowUnits, "calibration_live_view_window", "window=" + run.calibrationWindows + " actualUnits=" + (sample.count() - run.calibrationWindowUnits));
            run.calibrationWindowUnits = sample.count();
            run.calibrationWindowStart = now;
            run.calibrationWindows++;
        }
        if (now - run.calibrationMeasureStart < 600_000_000_000L) return;
        require(root, sample.count() > 0 && sample.p99Nanos() > 0 && run.calibrationWindows >= 9, "calibration_actual_view_samples", "Full-duration live authorized bounded preparation portions");
        var calibration = new JsonObject();
        calibration.addProperty("budget", "VIEW_ROWS");
        calibration.addProperty("measuredP99UnitNanos", sample.p99Nanos());
        calibration.addProperty("maxUnitNanos", sample.maxNanos());
        calibration.addProperty("unitCount", sample.count());
        calibration.addProperty("warmupSeconds", 300);
        calibration.addProperty("positiveActivityWindows", run.calibrationWindows);
        calibration.addProperty("measurementElapsedNanos", now - run.calibrationMeasureStart);
        calibration.addProperty("measurementScope", "Four actual views per connected client, ordinary production acknowledgments, full-duration observation");
        Files.writeString(root.resolve("view-calibration.json"), calibration.toString(), StandardOpenOption.CREATE_NEW);
        signal(root, "calibration-done", new JsonObject());
    }
    private static void setup(MinecraftServer server,Path root,Run run)throws Exception {
        var level=server.overworld();
        for(int x=0;x<32;x++)for(int z=0;z<32;z++) {
            level.setBlock(new BlockPos(x,63,z),Blocks.STONE.defaultBlockState(),3);
            for(int y=64;y<=67;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
        }
        run.owner.teleportTo(level,12.5,64,20.5,0,0);run.viewer.teleportTo(level,14.5,64,20.5,0,0);
        run.colony=uuid(command(server,run,"colonyloom colony create UIManagement 0 64 0 31 64 31"),"colony");
        run.otherColony=uuid(command(server,run,"colonyloom colony create HiddenManagement 64 64 64 95 64 95"),"colony");
        for(var entry:Map.of(WAREHOUSE,"warehouse",BUFFER,"construction",WORKSHOP,"workshop").entrySet()) {
            level.setBlock(entry.getKey(),Blocks.BARREL.defaultBlockState(),3);
            command(server,run,"colonyloom storage register "+run.colony+" "+coordinates(entry.getKey())+" "+entry.getValue());
            require(root,((Container)level.getBlockEntity(entry.getKey())).isEmpty(),"fixture_no_build_materials",entry.getKey().toString());
        }
        var warehouse=(Container)level.getBlockEntity(WAREHOUSE);
        warehouse.setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG,3));
        warehouse.setItem(1,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD,2));
        fact(root,"fixture_native_warehouse_stock",true,"warehouse="+WAREHOUSE+" slot0=3 minecraft:oak_log slot1=2 minecraft:bread; zero stairs/planks; builder cannot craft");
        level.setBlock(TABLE,Blocks.CRAFTING_TABLE.defaultBlockState(),3);
        command(server,run,"colonyloom building register "+run.colony+" "+coordinates(TABLE)+" "+coordinates(WORKSHOP));
        run.citizen=uuid(command(server,run,"colonyloom citizen create "+run.colony+" "+coordinates(CITIZEN)),"citizen");
        command(server,run,"colonyloom citizen assign "+run.citizen+" colonyloom:builder");
        command(server,run,"colonyloom member set "+run.colony+" UIGuest viewer");
        var registry=run.runtime.core().registry();
        require(root,registry.colony(run.colony).rank(OWNER)==MemberRank.OWNER&&registry.colony(run.colony).rank(VIEWER)==MemberRank.VIEWER&&registry.colony(run.otherColony).rank(VIEWER)==null,"fixture_colony_scope_rank","owner=OWNER viewer=VIEWER foreign colony invisible");
    }
    private static void observe(ServerPlayer player,boolean owner,Run run) {
        var channel=player.connection.getConnection().channel();
        channel.eventLoop().execute(() -> {
            try {
                channel.pipeline().addBefore("packet_handler","colonyloom_management_smoke",new ChannelDuplexHandler() {
                    @Override public void channelRead(ChannelHandlerContext context,Object message)throws Exception {
                        if(!run.proofFinished && message instanceof ServerboundCustomPayloadPacket packet)run.wire.add(new Wire(owner,false,packet.payload()));
                        super.channelRead(context,message);
                    }
                    @Override public void write(ChannelHandlerContext context,Object message,ChannelPromise promise)throws Exception {
                        if(!run.proofFinished && message instanceof ClientboundCustomPayloadPacket packet)run.wire.add(new Wire(owner,true,packet.payload()));
                        super.write(context,message,promise);
                    }
                });
            } catch(Exception failure) {run.observerFailure="Cannot observe actual player connection: "+failure;}
        });
    }
    private static void drain(Path root,Run run)throws Exception {
        Wire wire;
        while((wire=run.wire.poll())!=null) {
            var payload=wire.payload();
            if(!wire.outbound()) {
                if(payload instanceof CommandPayload request) {
                    var command=request.command();
                    if(wire.owner()) {run.ownerCommandInputs++;run.ownerCommands.put(command.sequence(),command);}
                    if(wire.owner()&&command.body() instanceof Build) {
                        if(run.ownerBuild==null)run.ownerBuild=command;
                        require(root,run.ownerBuild.equals(command),"exact_duplicate_build_input","sequence="+command.sequence()+" session="+command.sessionId());run.buildInputs++;
                    } else if(!wire.owner())run.viewerCommands.put(command.sequence(),command);
                } else if(!wire.owner()&&payload instanceof Subscribe request) {
                    run.viewerSubscriptions.put(request.subscription().subscriptionId(),request.subscription().colonyId());
                } else if(wire.owner()&&run.respawnVerified&&Files.isRegularFile(root.resolve("respawn-summary-requested"))
                        &&payload instanceof Subscribe request&&request.subscription().colonyId().equals(run.colony)
                        &&request.subscription().type()==ViewType.SUMMARY) {
                    run.respawnSummary=request.subscription().subscriptionId();
                } else if(!wire.owner()&&payload instanceof ViewAck ack) {
                    var flight=run.flights.get(ack.subscriptionId());
                    if(flight!=null&&flight.delta.data().authorityRevision()==ack.authorityRevision()&&flight.delta.data().stateRevision()==ack.stateRevision())flight.acknowledged=true;
                }
            } else if(wire.owner()) {
                if(payload instanceof CommandResult response) {
                    var command=run.ownerCommands.get(response.result().sequence());
                    if(command!=null&&response.result().status()==Status.ACCEPTED) {
                        if(command.body() instanceof PrioritizeWork)run.priorityAccepted=true;
                        if(command.body() instanceof CancelWork)run.cancelAccepted=true;
                    }
                }
                if(!run.respawnSummaryVerified&&payload instanceof ViewSnapshot snapshot&&snapshot.subscriptionId().equals(run.respawnSummary)) {
                    require(root,run.owner==run.respawnedOwner&&run.owner.connection.player==run.respawnedOwner
                            &&run.owner.connection.getConnection()==run.respawnConnection&&run.owner.isAlive()
                            &&snapshot.data().colonyId().equals(run.colony)&&snapshot.data().rank().equals("owner"),
                            "respawn_fresh_actor_summary","real SUMMARY snapshot="+snapshot.subscriptionId()+" from fresh live listener.player="+System.identityHashCode(run.owner));
                    run.respawnSummaryVerified=true;signal(root,"respawn-summary-verified",new JsonObject());
                }
                if(payload instanceof CommandResult response&&run.ownerBuild!=null&&response.result().sequence()==run.ownerBuild.sequence()) {
                    var result=response.result();
                    require(root,result.status()==Status.ACCEPTED&&result.objectId()!=null,"owner_real_build_accepted",result.toString());
                    if(run.ownerBuildResult==null)run.ownerBuildResult=result;
                    require(root,run.ownerBuildResult.equals(result),"duplicate_result_same_identity",result.toString());run.buildResults++;
                }
            } else viewerOutbound(root,run,payload);
        }
    }
    private static void viewerOutbound(Path root,Run run,CustomPacketPayload payload)throws Exception {
        if(payload instanceof CommandResult response) {
            var command=run.viewerCommands.get(response.result().sequence());
            require(root,response.result().status()!=Status.ACCEPTED,"viewer_wire_no_accepted_mutation",response.result().toString());
            if(command!=null&&command.body() instanceof Build&&response.result().status()==Status.REJECTED&&response.result().reason().equals("ACCESS_DENIED"))run.viewerDenied++;
        } else if(payload instanceof ViewSnapshot page) {
            require(root,run.colony!=null&&page.data().colonyId().equals(run.colony)&&page.data().rank().equals("viewer"),"viewer_snapshot_scope",page.data().colonyId().toString());run.snapshots++;
            run.snapshotCounts.merge(page.subscriptionId(),1,Integer::sum);
            if(run.accessClosed>0)run.postRevokeData++;
        } else if(payload instanceof ViewDelta page) {
            require(root,run.colony!=null&&page.data().colonyId().equals(run.colony)&&page.data().rank().equals("viewer"),"viewer_delta_scope","subscription="+page.subscriptionId()+" revision="+page.data().stateRevision());
            run.flights.put(page.subscriptionId(),new Flight(page,run.ticks));run.deltas++;
            run.deltaCounts.merge(page.subscriptionId(),1,Integer::sum);
            if(run.accessClosed>0)run.postRevokeData++;
        } else if(payload instanceof ViewClosed closed) {
            if(closed.reason().equals("ACK_TIMEOUT")) {
                int snapshots=run.snapshotCounts.getOrDefault(closed.subscriptionId(),0),deltas=run.deltaCounts.getOrDefault(closed.subscriptionId(),0);
                require(root,snapshots==1&&deltas==0,"server_actual_ack_timeout_bounded_pages","subscription="+closed.subscriptionId()+" real outbound snapshots="+snapshots+" deltas="+deltas+"; timeout closes unacknowledged initial page");run.timeoutProofs++;
            } else if(closed.reason().equals("ACCESS_DENIED")) {
                if(run.otherColony!=null&&run.otherColony.equals(run.viewerSubscriptions.get(closed.subscriptionId())))run.guessedDenied++;
                if(run.revoked&&run.colony.equals(run.viewerSubscriptions.get(closed.subscriptionId())))run.accessClosed++;
            }
        }
    }
    private static void verifyBuild(MinecraftServer server,Path root,Run run,boolean finalCheck)throws Exception {
        require(root,run.buildInputs==2&&run.buildResults==2,"exact_two_wire_build_attempts","live inputs="+run.buildInputs+" accepted result deliveries="+run.buildResults);
        var registry=run.runtime.core().registry();var sites=registry.construction().snapshots();
        var builds=registry.workBoard().works().stream().filter(work -> WorkOrder.CONSTRUCTION.equals(work.typeId())).toList();
        require(root,sites.size()==1&&builds.size()==1,"one_real_build_after_duplicate","construction sites="+sites.size()+" construction work="+builds.size()+" other work ignored");
        var site=sites.getFirst();var build=builds.getFirst();var input=(Build)run.ownerBuild.body();
        require(root,site.workId().equals(build.id())&&site.workId().equals(run.ownerBuildResult.objectId())&&site.colonyId().equals(run.colony)
                        &&OWNER.equals(site.initiatorId())&&site.origin().x()==ORIGIN.getX()&&site.origin().y()==ORIGIN.getY()&&site.origin().z()==ORIGIN.getZ()
                        &&site.origin().dimension().equals("minecraft:overworld")&&site.rotation()==0
                        &&registry.construction().definition(site.blueprintDigest()).id().equals("colonyloom:stair_strip")
                        &&run.colony.equals(run.ownerBuild.colonyId())&&input.origin().equals(site.origin())&&input.rotation()==0&&input.blueprintId().equals("colonyloom:stair_strip"),
                "build_matches_live_typed_request","work="+build.id()+" inputs="+run.buildInputs+" accepted results="+run.buildResults);
        boolean cancelled=finalCheck&&Files.isRegularFile(root.resolve("cancel-requested"))&&build.state()==WorkOrder.State.CANCELLED;
        require(root,site.consumed()==0&&site.cursor()==0&&(!site.closed()||cancelled),"no_fixture_material_consumption","cursor="+site.cursor()+" consumed="+site.consumed()+" closed="+site.closed());run.work=build.id();
        if(finalCheck) {
            require(root,cancelled&&site.closed(),"actual_ui_build_cancelled","exact work="+build.id()+" state="+build.state()+" closed="+site.closed());
            boolean allAir=true;
            for(int offset=0;offset<16;offset++)allAir&=server.overworld().getBlockState(ORIGIN.offset(offset,0,0)).isAir();
            require(root,allAir,"cancel_preserves_real_target_blocks","all sixteen native target blocks remain AIR; zero consumed resources");
        }
    }
    private static void revoke(MinecraftServer server,Path root,Run run)throws Exception {
        var requested=JsonParser.parseString(Files.readString(root.resolve("revoke-request"))).getAsJsonObject();
        UUID subscription=UUID.fromString(requested.get("subscriptionId").getAsString());
        long authority=requested.get("authorityRevision").getAsLong(),revision=requested.get("stateRevision").getAsLong();
        var flight=run.flights.get(subscription);
        if(flight==null||flight.delta.data().authorityRevision()!=authority||flight.delta.data().stateRevision()!=revision)return;
        if(run.ticks-flight.tick<20)return;
        require(root,!flight.acknowledged&&run.colony.equals(run.viewerSubscriptions.get(subscription)),"actual_unacked_stalled_delta",
                "subscription="+subscription+" authority="+authority+" revision="+revision+" base="+flight.delta.baseRevision()+" stalledTicks="+(run.ticks-flight.tick)+" realSnapshots="+run.snapshots+" realDeltas="+run.deltas);
        require(root,run.runtime.core().registry().colony(run.colony).rank(VIEWER)==MemberRank.VIEWER,"viewer_rank_before_revoke","public owner revoke while actual delta waits for ACK");
        command(server,run,"colonyloom member set "+run.colony+" UIGuest none");run.revoked=true;
        require(root,run.runtime.core().registry().colony(run.colony).rank(VIEWER)==null,"public_member_revoke_applied","authority="+run.runtime.core().registry().colony(run.colony).authorityRevision());
        var proof=new JsonObject();proof.addProperty("subscriptionId",subscription.toString());proof.addProperty("authorityRevision",authority);proof.addProperty("stateRevision",revision);proof.addProperty("stalledTicks",run.ticks-flight.tick);
        signal(root,"revoked",proof);
    }
    private static String coordinates(BlockPos p) {return p.getX()+" "+p.getY()+" "+p.getZ();}
    private static UUID uuid(String output,String key) {
        var match=Pattern.compile("(?:^|[\\s\\[,])"+Pattern.quote(key)+"=([0-9a-fA-F-]{36})(?=[\\s\\],]|$)").matcher(output);
        if(!match.find())throw new IllegalStateException("Missing "+key+" in "+output);return UUID.fromString(match.group(1));
    }
    private static String command(MinecraftServer server,Run run,String text)throws Exception {
        var capture=new Capture();int code=server.getCommands().getDispatcher().execute(text,run.owner.createCommandSourceStack().withPermission(2).withSource(capture));
        String result=String.join("\n",capture.messages);if(code!=1)throw new IllegalStateException("Public fixture command refused "+text+": "+result);return result;
    }
    private static final class Capture implements CommandSource {
        final List<String> messages=new ArrayList<>();
        public void sendSystemMessage(Component message){messages.add(message.getString());}
        public boolean acceptsSuccess(){return true;}
        public boolean acceptsFailure(){return true;}
        public boolean shouldInformAdmins(){return false;}
    }
    private static void require(Path root,boolean passed,String check,String detail)throws Exception {
        fact(root,check,passed,detail);if(!passed)throw new IllegalStateException(check+": "+detail);
    }
    private static void fact(Path root,String check,boolean passed,String detail)throws Exception {
        var json=new JsonObject();json.addProperty("check",check);json.addProperty("passed",passed);json.addProperty("detail",detail);
        Files.writeString(root.resolve("server-observations.jsonl"),json+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private static void signal(Path root,String name,JsonObject value)throws Exception {
        Path temporary=root.resolve(name+".server-tmp");
        Files.writeString(temporary,value.toString(),StandardOpenOption.CREATE_NEW);
        Files.move(temporary,root.resolve(name),java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
