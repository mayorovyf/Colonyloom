package colonyloom.minecraft.life;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

final class LifeNbt {
    static final int teen_pro_YT_MAX_DEPTH = 24;

    private LifeNbt() {}

    static CompoundTag tag(Map<String, Object> tree) {
        return (CompoundTag) encode(tree, 0);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> tree(CompoundTag tag) {
        return (Map<String, Object>) decode(tag, 0);
    }

    private static Tag encode(Object value, int depth) {
        if (depth > teen_pro_YT_MAX_DEPTH) throw new IllegalArgumentException("Life record nesting too deep");
        if (value instanceof Map<?, ?> map) {
            CompoundTag tag = new CompoundTag();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Life record key must be text");
                if (entry.getValue() != null) tag.put(key, encode(entry.getValue(), depth + 1));
            }
            return tag;
        }
        if (value instanceof List<?> items) {
            ListTag tag = new ListTag();
            for (Object item : items) {
                Tag element = encode(item, depth + 1);
                if (!tag.isEmpty() && tag.getElementType() != element.getId()) throw new IllegalArgumentException("Life record list mixes types");
                tag.add(element);
            }
            return tag;
        }
        if (value instanceof String text) return StringTag.valueOf(text);
        if (value instanceof Boolean flag) return ByteTag.valueOf(flag);
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) return LongTag.valueOf(((Number) value).longValue());
        if (value instanceof Double || value instanceof Float) return DoubleTag.valueOf(((Number) value).doubleValue());
        throw new IllegalArgumentException("Unsupported life value " + value.getClass().getSimpleName());
    }

    private static Object decode(Tag tag, int depth) {
        if (depth > teen_pro_YT_MAX_DEPTH) throw new IllegalArgumentException("Life record nesting too deep");
        return switch (tag.getId()) {
            case Tag.TAG_COMPOUND -> {
                CompoundTag compound = (CompoundTag) tag;
                LinkedHashMap<String, Object> map = new LinkedHashMap<>();
                for (String key : compound.getAllKeys()) map.put(key, decode(compound.get(key), depth + 1));
                yield map;
            }
            case Tag.TAG_LIST -> {
                List<Object> items = new ArrayList<>();
                for (Tag item : (ListTag) tag) items.add(decode(item, depth + 1));
                yield items;
            }
            case Tag.TAG_STRING -> tag.getAsString();
            case Tag.TAG_BYTE -> {
                byte flag = ((NumericTag) tag).getAsByte();
                if (flag != 0 && flag != 1) throw new IllegalArgumentException("Invalid life flag");
                yield flag == 1;
            }
            case Tag.TAG_SHORT, Tag.TAG_INT, Tag.TAG_LONG -> ((NumericTag) tag).getAsLong();
            case Tag.TAG_FLOAT, Tag.TAG_DOUBLE -> ((NumericTag) tag).getAsDouble();
            default -> throw new IllegalArgumentException("Unsupported life tag " + tag.getId());
        };
    }
}
