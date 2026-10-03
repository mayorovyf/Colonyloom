package io.github.kpuctajluk.colonyloom.minecraft.storage;

import io.github.kpuctajluk.colonyloom.core.storage.ItemDescriptor;
import java.io.*;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;

/** Lossless full component maps, with sorted compound keys and ordered lists. */
public final class NativeItemDescriptor {
    private static final int MAX_BYTES = 8192;
    private NativeItemDescriptor() {}

    public static ItemDescriptor describe(ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty()) return null;
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        Tag tag = DataComponentMap.CODEC.encodeStart(ops, stack.getComponents()).getOrThrow();
        BoundedOutput bytes = new BoundedOutput();
        try {
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(tag.getId());
            writePayload(tag, out, 0);
            ItemDescriptor result = new ItemDescriptor(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), bytes.bytes());
            if (!matches(stack, result, registries)) throw new IllegalArgumentException("Components are not losslessly persistent");
            return result;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Item components exceed canonical limits", failure);
        }
    }

    /** Final physical consumption guard: native full component equality, never just item IDs. */
    public static boolean matches(ItemStack stack, ItemDescriptor descriptor, HolderLookup.Provider registries) {
        if (stack.isEmpty() || descriptor == null || !BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(descriptor.itemId())) return false;
        try {
            return ItemStack.isSameItemSameComponents(stack, capacityProbe(descriptor, registries));
        } catch (RuntimeException failure) {
            return false;
        }
    }
    /** A one-item validation probe only; never published into a physical inventory. */
    static ItemStack capacityProbe(ItemDescriptor descriptor, HolderLookup.Provider registries) {
        try {
            ByteArrayInputStream input = new ByteArrayInputStream(descriptor.canonicalComponents());
            Tag tag = NbtIo.readAnyTag(new DataInputStream(input), new NbtAccounter(65536, 32));
            if (input.available() != 0) throw new IllegalArgumentException("Trailing item components");
            DataComponentMap components = DataComponentMap.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag).getOrThrow();
            var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(descriptor.itemId()))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown native item"));
            ItemStack expected = new ItemStack(item);
            for (var type : Set.copyOf(expected.getComponents().keySet())) if (!components.has(type)) expected.remove(type);
            expected.applyComponents(components);
            if (expected.isEmpty()) throw new IllegalArgumentException("Empty native item probe");
            return expected;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid native item components", failure);
        }
    }

    private static void writePayload(Tag tag, DataOutput out, int depth) throws IOException {
        if (depth > 32) throw new IOException("Component depth exceeds 32");
        if (tag instanceof CompoundTag compound) {
            List<String> keys = new ArrayList<>(compound.getAllKeys());
            Collections.sort(keys);
            for (String key : keys) {
                Tag child = compound.get(key);
                out.writeByte(child.getId());
                out.writeUTF(key);
                writePayload(child, out, depth + 1);
            }
            out.writeByte(Tag.TAG_END);
        } else if (tag instanceof ListTag list) {
            out.writeByte(list.getElementType());
            out.writeInt(list.size());
            for (Tag child : list) writePayload(child, out, depth + 1);
        } else {
            tag.write(out);
        }
    }

    private static final class BoundedOutput extends OutputStream {
        private final byte[] data = new byte[MAX_BYTES];
        private int size;
        @Override public void write(int value) throws IOException {
            if (size == data.length) throw new IOException("Component bytes exceed 8192");
            data[size++] = (byte)value;
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length > data.length - size) throw new IOException("Component bytes exceed 8192");
            System.arraycopy(bytes, offset, data, size, length);
            size += length;
        }
        byte[] bytes() { return Arrays.copyOf(data, size); }
    }
}
