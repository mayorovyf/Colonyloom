package io.github.kpuctajluk.colonyloom.minecraft.network;

import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Decode is strictly bounded and contains no authoritative registry or economy traversal. */
public final class ManagementPayloads {
    private ManagementPayloads() {}
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("colonyloom",name));
    }
    private static <T> StreamCodec<RegistryFriendlyByteBuf,T> codec(int limit,Function<RegistryFriendlyByteBuf,T> decode,BiConsumer<RegistryFriendlyByteBuf,T> encode) {
        return new StreamCodec<>() {
            public T decode(RegistryFriendlyByteBuf buffer) {
                if(buffer.readableBytes()>limit)throw new IllegalArgumentException("PAYLOAD_LIMIT");
                int start=buffer.readerIndex();T result=decode.apply(buffer);
                if(buffer.readerIndex()-start>limit||buffer.isReadable())throw new IllegalArgumentException("INVALID_PAYLOAD");
                return result;
            }
            public void encode(RegistryFriendlyByteBuf buffer,T value) {
                int start=buffer.writerIndex();encode.accept(buffer,value);
                if(buffer.writerIndex()-start>limit)throw new IllegalArgumentException("PAYLOAD_LIMIT");
            }
        };
    }
    private static String text(RegistryFriendlyByteBuf buffer) {
        String value=buffer.readUtf(ManagementProtocol.STRING_BYTES);return ManagementProtocol.text(value);
    }
    private static void text(RegistryFriendlyByteBuf buffer,String value) {buffer.writeUtf(ManagementProtocol.text(value),ManagementProtocol.STRING_BYTES);}
    private static UUID optional(RegistryFriendlyByteBuf buffer) {return buffer.readBoolean()?buffer.readUUID():null;}
    private static void optional(RegistryFriendlyByteBuf buffer,UUID value) {buffer.writeBoolean(value!=null);if(value!=null)buffer.writeUUID(value);}
    private static WorldPosition position(RegistryFriendlyByteBuf buffer) {return new WorldPosition(text(buffer),buffer.readInt(),buffer.readInt(),buffer.readInt());}
    private static void position(RegistryFriendlyByteBuf buffer,WorldPosition value) {text(buffer,value.dimension());buffer.writeInt(value.x());buffer.writeInt(value.y());buffer.writeInt(value.z());}
    private static <E extends Enum<E>> E enumeration(RegistryFriendlyByteBuf buffer,E[] values) {
        int index=buffer.readUnsignedByte();if(index>=values.length)throw new IllegalArgumentException("INVALID_ENUM");return values[index];
    }
    public record Hello(String protocol,String buildVersion) implements CustomPacketPayload {
        public static final Type<Hello> TYPE=ManagementPayloads.type("hello");
        public static final StreamCodec<RegistryFriendlyByteBuf,Hello> CODEC=codec(1024,b -> new Hello(text(b),text(b)),(b,v) -> {text(b,v.protocol);text(b,v.buildVersion);});
        public Hello {ManagementProtocol.text(protocol);ManagementProtocol.text(buildVersion);}
        public Type<Hello> type(){return TYPE;}
    }
    public record Welcome(UUID sessionId,long nextSequence) implements CustomPacketPayload {
        public static final Type<Welcome> TYPE=ManagementPayloads.type("session");
        public static final StreamCodec<RegistryFriendlyByteBuf,Welcome> CODEC=codec(64,b -> new Welcome(b.readUUID(),b.readLong()),(b,v) -> {b.writeUUID(v.sessionId);b.writeLong(v.nextSequence);});
        public Type<Welcome> type(){return TYPE;}
    }
    public record CommandPayload(Command command) implements CustomPacketPayload {
        public static final Type<CommandPayload> TYPE=ManagementPayloads.type("command");
        public static final StreamCodec<RegistryFriendlyByteBuf,CommandPayload> CODEC=codec(ManagementProtocol.COMMAND_BYTES,b -> {
            UUID session=b.readUUID();long sequence=b.readLong();UUID colony=optional(b);long revision=b.readLong();String type=text(b);
            Body body=switch(type) {
                case "colonyloom:create_colony" -> new CreateColony(text(b),new Territory(text(b),b.readInt(),b.readInt(),b.readInt(),b.readInt()));
                case "colonyloom:assign_profession" -> new AssignProfession(b.readUUID(),text(b));
                case "colonyloom:assign_workplace" -> new AssignWorkplace(b.readUUID(),b.readUUID());
                case "colonyloom:build" -> new Build(text(b),position(b),b.readInt());
                case "colonyloom:cancel_work" -> new CancelWork(b.readUUID());
                case "colonyloom:prioritize_work" -> new PrioritizeWork(b.readUUID(),b.readInt());
                case "colonyloom:set_member" -> new SetMember(b.readUUID(),text(b));
                case "colonyloom:set_owner" -> new SetOwner(b.readUUID());
                case "colonyloom:register_storage" -> new RegisterStorage(position(b),text(b));
                case "colonyloom:register_workshop" -> new RegisterWorkshop(position(b),position(b));
                default -> throw new IllegalArgumentException("UNKNOWN_COMMAND");
            };
            return new CommandPayload(new Command(session,sequence,colony,revision,body));
        },(b,v) -> {
            var command=v.command;b.writeUUID(command.sessionId());b.writeLong(command.sequence());optional(b,command.colonyId());b.writeLong(command.expectedRevision());text(b,command.body().typeId());
            switch(command.body()) {
                case CreateColony body -> {text(b,body.name());var t=body.territory();text(b,t.dimension());b.writeInt(t.minX());b.writeInt(t.minZ());b.writeInt(t.maxX());b.writeInt(t.maxZ());}
                case AssignProfession body -> {b.writeUUID(body.citizenId());text(b,body.professionId());}
                case AssignWorkplace body -> {b.writeUUID(body.citizenId());b.writeUUID(body.workshopId());}
                case Build body -> {text(b,body.blueprintId());position(b,body.origin());b.writeInt(body.rotation());}
                case CancelWork body -> b.writeUUID(body.workId());
                case PrioritizeWork body -> {b.writeUUID(body.workId());b.writeInt(body.priority());}
                case SetMember body -> {b.writeUUID(body.playerId());text(b,body.rank());}
                case SetOwner body -> b.writeUUID(body.playerId());
                case RegisterStorage body -> {position(b,body.position());text(b,body.role());}
                case RegisterWorkshop body -> {position(b,body.table());position(b,body.inventory());}
            }
        });
        public Type<CommandPayload> type(){return TYPE;}
    }
    public record CommandResult(Result result) implements CustomPacketPayload {
        public static final Type<CommandResult> TYPE=ManagementPayloads.type("command_result");
        public static final StreamCodec<RegistryFriendlyByteBuf,CommandResult> CODEC=codec(1024,b -> new CommandResult(new Result(b.readLong(),enumeration(b,Status.values()),text(b),optional(b),b.readLong())),(b,v) -> {var r=v.result;b.writeLong(r.sequence());b.writeByte(r.status().ordinal());text(b,r.reason());optional(b,r.objectId());b.writeLong(r.revision());});
        public Type<CommandResult> type(){return TYPE;}
    }
    public record Subscribe(Subscription subscription) implements CustomPacketPayload {
        public static final Type<Subscribe> TYPE=ManagementPayloads.type("subscribe");
        public static final StreamCodec<RegistryFriendlyByteBuf,Subscribe> CODEC=codec(128,b -> new Subscribe(new Subscription(b.readUUID(),b.readUUID(),b.readUUID(),enumeration(b,ViewType.values()),b.readInt(),b.readBoolean())),(b,v) -> {var s=v.subscription;b.writeUUID(s.sessionId());b.writeUUID(s.subscriptionId());b.writeUUID(s.colonyId());b.writeByte(s.type().ordinal());b.writeInt(s.page());b.writeBoolean(s.resync());});
        public Type<Subscribe> type(){return TYPE;}
    }
    public record Unsubscribe(UUID sessionId,UUID subscriptionId) implements CustomPacketPayload {
        public static final Type<Unsubscribe> TYPE=ManagementPayloads.type("unsubscribe");
        public static final StreamCodec<RegistryFriendlyByteBuf,Unsubscribe> CODEC=codec(64,b -> new Unsubscribe(b.readUUID(),b.readUUID()),(b,v) -> {b.writeUUID(v.sessionId);b.writeUUID(v.subscriptionId);});
        public Type<Unsubscribe> type(){return TYPE;}
    }
    public record ViewSnapshot(UUID subscriptionId,ViewData data) implements CustomPacketPayload {
        public static final Type<ViewSnapshot> TYPE=ManagementPayloads.type("view_snapshot");
        public static final StreamCodec<RegistryFriendlyByteBuf,ViewSnapshot> CODEC=codec(ManagementProtocol.VIEW_BYTES,b -> new ViewSnapshot(b.readUUID(),view(b)),(b,v) -> {b.writeUUID(v.subscriptionId);view(b,v.data);});
        public Type<ViewSnapshot> type(){return TYPE;}
    }
    public record ViewDelta(UUID subscriptionId,long baseRevision,ViewData data) implements CustomPacketPayload {
        public static final Type<ViewDelta> TYPE=ManagementPayloads.type("view_delta");
        public static final StreamCodec<RegistryFriendlyByteBuf,ViewDelta> CODEC=codec(ManagementProtocol.VIEW_BYTES,b -> new ViewDelta(b.readUUID(),b.readLong(),view(b)),(b,v) -> {b.writeUUID(v.subscriptionId);b.writeLong(v.baseRevision);view(b,v.data);});
        public Type<ViewDelta> type(){return TYPE;}
    }
    public record ViewAck(UUID sessionId,UUID subscriptionId,long authorityRevision,long stateRevision) implements CustomPacketPayload {
        public static final Type<ViewAck> TYPE=ManagementPayloads.type("view_ack");
        public static final StreamCodec<RegistryFriendlyByteBuf,ViewAck> CODEC=codec(64,b -> new ViewAck(b.readUUID(),b.readUUID(),b.readLong(),b.readLong()),(b,v) -> {b.writeUUID(v.sessionId);b.writeUUID(v.subscriptionId);b.writeLong(v.authorityRevision);b.writeLong(v.stateRevision);});
        public Type<ViewAck> type(){return TYPE;}
    }
    public record ViewClosed(UUID subscriptionId,String reason) implements CustomPacketPayload {
        public static final Type<ViewClosed> TYPE=ManagementPayloads.type("view_closed");
        public static final StreamCodec<RegistryFriendlyByteBuf,ViewClosed> CODEC=codec(512,b -> new ViewClosed(b.readUUID(),text(b)),(b,v) -> {b.writeUUID(v.subscriptionId);text(b,v.reason);});
        public Type<ViewClosed> type(){return TYPE;}
    }
    public record OpenScreen(UUID colonyId) implements CustomPacketPayload {
        public static final Type<OpenScreen> TYPE=ManagementPayloads.type("open_screen");
        public static final StreamCodec<RegistryFriendlyByteBuf,OpenScreen> CODEC=codec(32,b -> new OpenScreen(optional(b)),(b,v) -> optional(b,v.colonyId));
        public Type<OpenScreen> type(){return TYPE;}
    }
    private static List<String> strings(RegistryFriendlyByteBuf b) {
        int count=b.readVarInt();if(count<0||count>64)throw new IllegalArgumentException("CATALOG_LIMIT");var values=new ArrayList<String>(count);for(int i=0;i<count;i++)values.add(text(b));return values;
    }
    private static void strings(RegistryFriendlyByteBuf b,List<String> values) {b.writeVarInt(values.size());for(var value:values)text(b,value);}
    private static ViewData view(RegistryFriendlyByteBuf b) {
        UUID colony=b.readUUID();String name=text(b),rank=text(b);long authority=b.readLong(),revision=b.readLong();ViewType type=enumeration(b,ViewType.values());int page=b.readInt(),total=b.readInt(),count=b.readVarInt();
        if(count<0||count>ManagementProtocol.PAGE_ROWS)throw new IllegalArgumentException("ROW_LIMIT");var rows=new ArrayList<Row>(count);
        for(int i=0;i<count;i++)rows.add(new Row(b.readUUID(),b.readLong(),text(b),text(b),text(b),text(b),optional(b)));
        return new ViewData(colony,name,rank,authority,revision,type,page,total,rows,strings(b),strings(b));
    }
    private static void view(RegistryFriendlyByteBuf b,ViewData v) {
        b.writeUUID(v.colonyId());text(b,v.colonyName());text(b,v.rank());b.writeLong(v.authorityRevision());b.writeLong(v.stateRevision());b.writeByte(v.type().ordinal());b.writeInt(v.page());b.writeInt(v.totalRows());b.writeVarInt(v.rows().size());
        for(var r:v.rows()) {b.writeUUID(r.id());b.writeLong(r.revision());text(b,r.name());text(b,r.state());text(b,r.reason());text(b,r.detail());optional(b,r.relatedId());}
        strings(b,v.professions());strings(b,v.blueprints());
    }
}
