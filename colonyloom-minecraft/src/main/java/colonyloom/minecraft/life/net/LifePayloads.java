package colonyloom.minecraft.life.net;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public final class LifePayloads {
    public static final String teen_pro_YT_NAMESPACE = "teen_pro_yt";
    public static final String teen_pro_YT_VERSION = "1";
    public static final int teen_pro_YT_TEXT = 2048;
    public static final int teen_pro_YT_ID = 128;
    public static final int teen_pro_YT_ROWS = 50;
    public static final int teen_pro_YT_TABS = 4;

    private LifePayloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(teen_pro_YT_NAMESPACE, name));
    }

    private static <T> StreamCodec<RegistryFriendlyByteBuf, T> codec(int limit, Function<RegistryFriendlyByteBuf, T> decode, BiConsumer<RegistryFriendlyByteBuf, T> encode) {
        return new StreamCodec<>() {
            public T decode(RegistryFriendlyByteBuf buffer) {
                if (buffer.readableBytes() > limit) throw new IllegalArgumentException("PAYLOAD_LIMIT");
                T result = decode.apply(buffer);
                if (buffer.isReadable()) throw new IllegalArgumentException("INVALID_PAYLOAD");
                return result;
            }
            public void encode(RegistryFriendlyByteBuf buffer, T value) {
                int start = buffer.writerIndex();
                encode.accept(buffer, value);
                if (buffer.writerIndex() - start > limit) throw new IllegalArgumentException("PAYLOAD_LIMIT");
            }
        };
    }

    private static UUID optional(RegistryFriendlyByteBuf buffer) { return buffer.readBoolean() ? buffer.readUUID() : null; }

    private static void optional(RegistryFriendlyByteBuf buffer, UUID value) {
        buffer.writeBoolean(value != null);
        if (value != null) buffer.writeUUID(value);
    }

    private static String bounded(String value, int limit) {
        if (value == null) return "";
        return value.length() > limit ? value.substring(0, limit) : value;
    }

    public record Outfit(int entityId, String outfit) implements CustomPacketPayload {
        public static final Type<Outfit> teen_pro_YT_TYPE = LifePayloads.type("outfit");
        public static final StreamCodec<RegistryFriendlyByteBuf, Outfit> teen_pro_YT_CODEC = codec(256,
                buffer -> new Outfit(buffer.readVarInt(), buffer.readUtf(teen_pro_YT_ID)),
                (buffer, value) -> { buffer.writeVarInt(value.entityId); buffer.writeUtf(value.outfit, teen_pro_YT_ID); });
        public Outfit { outfit = bounded(outfit, teen_pro_YT_ID); }
        public Type<Outfit> type() { return teen_pro_YT_TYPE; }
    }

    public record Speech(int entityId, String spec) implements CustomPacketPayload {
        public static final Type<Speech> teen_pro_YT_TYPE = LifePayloads.type("speech");
        public static final StreamCodec<RegistryFriendlyByteBuf, Speech> teen_pro_YT_CODEC = codec(teen_pro_YT_TEXT * 3 + 16,
                buffer -> new Speech(buffer.readVarInt(), buffer.readUtf(teen_pro_YT_TEXT)),
                (buffer, value) -> { buffer.writeVarInt(value.entityId); buffer.writeUtf(value.spec, teen_pro_YT_TEXT); });
        public Speech { spec = bounded(spec, teen_pro_YT_TEXT); }
        public Type<Speech> type() { return teen_pro_YT_TYPE; }
    }

    public record ViewRequest(UUID colony, int tab, int page) implements CustomPacketPayload {
        public static final Type<ViewRequest> teen_pro_YT_TYPE = LifePayloads.type("view_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ViewRequest> teen_pro_YT_CODEC = codec(64,
                buffer -> new ViewRequest(optional(buffer), buffer.readVarInt(), buffer.readVarInt()),
                (buffer, value) -> { optional(buffer, value.colony); buffer.writeVarInt(value.tab); buffer.writeVarInt(value.page); });
        public ViewRequest {
            if (tab < 0 || tab >= teen_pro_YT_TABS || page < 0 || page > 10_000) throw new IllegalArgumentException("INVALID_VIEW_REQUEST");
        }
        public Type<ViewRequest> type() { return teen_pro_YT_TYPE; }
    }

    public record Row(UUID id, String name, String state, String reason, String detail) {
        public Row {
            name = bounded(name, teen_pro_YT_TEXT); state = bounded(state, teen_pro_YT_TEXT);
            reason = bounded(reason, teen_pro_YT_TEXT); detail = bounded(detail, teen_pro_YT_TEXT);
        }
    }

    public record View(UUID colony, String colonyName, int tab, int page, int pages, List<Row> rows, String notice) implements CustomPacketPayload {
        public static final Type<View> teen_pro_YT_TYPE = LifePayloads.type("view");
        public static final StreamCodec<RegistryFriendlyByteBuf, View> teen_pro_YT_CODEC = codec(teen_pro_YT_ROWS * (16 + 4 * (teen_pro_YT_TEXT * 3 + 4)) + 4096,
                buffer -> {
                    UUID colony = optional(buffer);
                    String name = buffer.readUtf(teen_pro_YT_TEXT);
                    int tab = buffer.readVarInt(), page = buffer.readVarInt(), pages = buffer.readVarInt();
                    int count = buffer.readVarInt();
                    if (count < 0 || count > teen_pro_YT_ROWS) throw new IllegalArgumentException("VIEW_LIMIT");
                    List<Row> rows = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) rows.add(new Row(buffer.readUUID(), buffer.readUtf(teen_pro_YT_TEXT), buffer.readUtf(teen_pro_YT_TEXT),
                            buffer.readUtf(teen_pro_YT_TEXT), buffer.readUtf(teen_pro_YT_TEXT)));
                    return new View(colony, name, tab, page, pages, rows, buffer.readUtf(teen_pro_YT_TEXT));
                },
                (buffer, value) -> {
                    optional(buffer, value.colony);
                    buffer.writeUtf(value.colonyName, teen_pro_YT_TEXT);
                    buffer.writeVarInt(value.tab); buffer.writeVarInt(value.page); buffer.writeVarInt(value.pages);
                    buffer.writeVarInt(value.rows.size());
                    for (Row row : value.rows) {
                        buffer.writeUUID(row.id());
                        buffer.writeUtf(row.name(), teen_pro_YT_TEXT); buffer.writeUtf(row.state(), teen_pro_YT_TEXT);
                        buffer.writeUtf(row.reason(), teen_pro_YT_TEXT); buffer.writeUtf(row.detail(), teen_pro_YT_TEXT);
                    }
                    buffer.writeUtf(value.notice, teen_pro_YT_TEXT);
                });
        public View {
            colonyName = bounded(colonyName, teen_pro_YT_TEXT);
            notice = bounded(notice, teen_pro_YT_TEXT);
            rows = List.copyOf(rows);
            if (rows.size() > teen_pro_YT_ROWS || tab < 0 || tab >= teen_pro_YT_TABS || page < 0 || pages < 1) throw new IllegalArgumentException("VIEW_LIMIT");
        }
        public Type<View> type() { return teen_pro_YT_TYPE; }
    }
}
