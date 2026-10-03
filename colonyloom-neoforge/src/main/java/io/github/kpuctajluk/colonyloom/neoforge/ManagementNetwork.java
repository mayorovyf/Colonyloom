package io.github.kpuctajluk.colonyloom.neoforge;

import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.*;
import io.github.kpuctajluk.colonyloom.core.management.ManagementSession;
import io.github.kpuctajluk.colonyloom.minecraft.network.ManagementPayloads.*;
import io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Each connection owns one session; dedicated registration contains no client screen references. */
final class ManagementNetwork {
    private final String buildVersion;
    private final Function<MinecraftServer,MinecraftServerRuntime> runtime;
    private final Function<MinecraftServer,IdentityPlatform> identity;
    private final Function<MinecraftServer,List<String>> blueprints;
    private final Map<ServerGamePacketListenerImpl,ManagementSession> sessions=new LinkedHashMap<>();
    ManagementNetwork(String buildVersion,Function<MinecraftServer,MinecraftServerRuntime> runtime,Function<MinecraftServer,IdentityPlatform> identity,Function<MinecraftServer,List<String>> blueprints) {
        this.buildVersion=buildVersion;this.runtime=runtime;this.identity=identity;this.blueprints=blueprints;
    }
    void register(RegisterPayloadHandlersEvent event) {
        var registrar=event.registrar(ManagementProtocol.PROTOCOL_VERSION);
        registrar.playToServer(Hello.TYPE,Hello.CODEC,this::hello);
        registrar.playToServer(CommandPayload.TYPE,CommandPayload.CODEC,(payload,ctx) -> session(ctx).command(payload.command()));
        registrar.playToServer(Subscribe.TYPE,Subscribe.CODEC,(payload,ctx) -> session(ctx).subscribe(payload.subscription()));
        registrar.playToServer(Unsubscribe.TYPE,Unsubscribe.CODEC,(payload,ctx) -> session(ctx).unsubscribe(payload.sessionId(),payload.subscriptionId()));
        registrar.playToServer(ViewAck.TYPE,ViewAck.CODEC,(payload,ctx) -> session(ctx).acknowledge(payload.sessionId(),payload.subscriptionId(),payload.authorityRevision(),payload.stateRevision()));
        registrar.playToClient(Welcome.TYPE,Welcome.CODEC,ManagementNetwork::client);
        registrar.playToClient(CommandResult.TYPE,CommandResult.CODEC,ManagementNetwork::client);
        registrar.playToClient(ViewSnapshot.TYPE,ViewSnapshot.CODEC,ManagementNetwork::client);
        registrar.playToClient(ViewDelta.TYPE,ViewDelta.CODEC,ManagementNetwork::client);
        registrar.playToClient(ViewClosed.TYPE,ViewClosed.CODEC,ManagementNetwork::client);
        registrar.playToClient(OpenScreen.TYPE,OpenScreen.CODEC,ManagementNetwork::client);
    }
    private static void client(CustomPacketPayload payload,IPayloadContext context) {NeoForge.EVENT_BUS.post(new ManagementClientPayloadEvent(payload));}
    private void hello(Hello payload,IPayloadContext context) {
        var player=(ServerPlayer)context.player();
        if(!payload.protocol().equals(ManagementProtocol.PROTOCOL_VERSION)||!payload.buildVersion().equals(buildVersion)) {
            context.disconnect(Component.translatable("colonyloom.reason.protocol_mismatch"));return;
        }
        var listener=player.connection;
        var existing=sessions.get(listener);
        if(existing!=null) {context.reply(new Welcome(existing.sessionId(),existing.nextSequence()));return;}
        var server=player.getServer();var bridge=runtime.apply(server);
        var session=new ManagementSession(new ManagementBackend(bridge,identity.apply(server),() -> listener.player,() -> blueprints.apply(server)),new ManagementSession.Transport() {
            public boolean writable(){return listener.getConnection().isConnected()&&!listener.player.hasDisconnected()&&listener.getConnection().channel().isWritable();}
            public void sendResult(Result result){send(new CommandResult(result));}
            public void sendSnapshot(UUID id,ViewData data){send(new ViewSnapshot(id,data));}
            public void sendDelta(UUID id,long base,ViewData data){send(new ViewDelta(id,base,data));}
            public void closeView(UUID id,String reason){if(writable())send(new ViewClosed(id,reason));}
            private void send(CustomPacketPayload payload){PacketDistributor.sendToPlayer(listener.player,payload);}
        });
        sessions.put(listener,session);context.reply(new Welcome(session.sessionId(),session.nextSequence()));
    }
    private ManagementSession session(IPayloadContext context) {
        var value=sessions.get(((ServerPlayer)context.player()).connection);
        if(value==null){context.disconnect(Component.translatable("colonyloom.reason.session_mismatch"));throw new IllegalStateException("SESSION_MISMATCH");}
        return value;
    }
    void tick(MinecraftServer server,long tick) {
        ServerGamePacketListenerImpl first=null;
        for(var iterator=sessions.entrySet().iterator();iterator.hasNext();) {
            var entry=iterator.next();var listener=entry.getKey();if(listener.player.getServer()!=server)continue;
            if(!listener.getConnection().isConnected()||listener.player.hasDisconnected()){entry.getValue().close();iterator.remove();}
            else {if(first==null)first=listener;entry.getValue().tick(tick);}
        }
        if(first!=null) {
            var session=sessions.remove(first);
            if(session!=null)sessions.put(first,session);
        }
    }
    void stopped(MinecraftServer server) {
        for(var iterator=sessions.entrySet().iterator();iterator.hasNext();) {var entry=iterator.next();if(entry.getKey().player.getServer()==server){entry.getValue().close();iterator.remove();}}
    }
    void open(ServerPlayer player,UUID colonyId) {
        var listener=player.connection;
        player=listener.player;
        var session=sessions.get(listener);
        if(session==null)throw new IllegalStateException("NOT_READY");
        if(colonyId!=null) {
            var colony=runtime.apply(player.getServer()).core().registry().colony(colonyId);
            if(colony.rank(player.getUUID())==null)throw new SecurityException("NO_ACCESS");
        }
        if(!listener.getConnection().isConnected()||player.hasDisconnected()||!listener.getConnection().channel().isWritable())throw new IllegalStateException("NOT_READY");
        PacketDistributor.sendToPlayer(player,new OpenScreen(colonyId));
    }
}
