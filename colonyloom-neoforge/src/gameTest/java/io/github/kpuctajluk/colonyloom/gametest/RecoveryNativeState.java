package io.github.kpuctajluk.colonyloom.gametest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.storage.LevelResource;

/** Dev-only independent native saves and direct disk reads; never executes an economic effect. */
final class RecoveryNativeState {
    private RecoveryNativeState() {}

    static void saveBlocks(MinecraftServer server) {
        server.overworld().getChunkSource().save(true);
        server.overworld().getChunkSource().chunkMap.flushWorker();
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
    }

    static void saveEntities(MinecraftServer server) throws ReflectiveOperationException {
        // ServerLevel.save also saves SavedData. Invoke only vanilla's native entity store instead.
        var field = ServerLevel.class.getDeclaredField("entityManager");
        field.setAccessible(true);
        ((PersistentEntitySectionManager<?>)field.get(server.overworld())).saveAll();
        // saveAll ends with EntityPersistentStorage.flush(true), including the entity region worker.
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
    }

    static CompoundTag blockEntity(MinecraftServer server, BlockPos pos) throws Exception {
        for (Tag raw : region(server, "region", new ChunkPos(pos)).getList("block_entities", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag)raw;
            if (tag.getInt("x") == pos.getX() && tag.getInt("y") == pos.getY() && tag.getInt("z") == pos.getZ()) return tag;
        }
        throw new IllegalStateException("Durable native block entity missing " + pos);
    }

    static CompoundTag entity(MinecraftServer server, UUID entityId, ChunkPos chunk) throws Exception {
        CompoundTag tag=entityOrNull(server,entityId,chunk);
        if(tag!=null)return tag;
        throw new IllegalStateException("Durable original native entity missing " + entityId + " chunk=" + chunk);
    }

    static net.minecraft.nbt.ListTag entities(MinecraftServer server,ChunkPos chunk)throws Exception {
        return region(server,"entities",chunk).getList("Entities",Tag.TAG_COMPOUND);
    }

    static CompoundTag entityOrNull(MinecraftServer server,UUID entityId,ChunkPos chunk)throws Exception {
        for (Tag raw : entities(server,chunk)) {
            CompoundTag tag = (CompoundTag)raw;
            if (tag.hasUUID("UUID") && entityId.equals(tag.getUUID("UUID"))) return tag;
        }
        return null;
    }

    static int inventoryCount(MinecraftServer server, CompoundTag inventory, Item item) {
        int result = 0;
        for (Tag raw : inventory.getList("Items", Tag.TAG_COMPOUND)) {
            ItemStack stack = ItemStack.parseOptional(server.registryAccess(), (CompoundTag)raw);
            if (stack.is(item)) result += stack.getCount();
        }
        return result;
    }

    static String blockName(MinecraftServer server,BlockPos pos)throws Exception {
        var chunk=region(server,"region",new ChunkPos(pos));
        for(Tag raw:chunk.getList("sections",Tag.TAG_COMPOUND)) {
            var section=(CompoundTag)raw;if(section.getByte("Y")!=Math.floorDiv(pos.getY(),16))continue;
            var states=section.getCompound("block_states");var palette=states.getList("palette",Tag.TAG_COMPOUND);
            if(palette.isEmpty())throw new IllegalStateException("Durable block palette missing "+pos);
            if(palette.size()==1)return palette.getCompound(0).getString("Name");
            int bits=Math.max(4,32-Integer.numberOfLeadingZeros(palette.size()-1)),perLong=64/bits;
            int index=((pos.getY()&15)<<8)|((pos.getZ()&15)<<4)|(pos.getX()&15);var data=states.getLongArray("data");
            if(index/perLong>=data.length)throw new IllegalStateException("Durable block data missing "+pos);
            int selected=(int)((data[index/perLong]>>>((index%perLong)*bits))&((1L<<bits)-1));
            if(selected>=palette.size())throw new IllegalStateException("Durable block palette index invalid "+pos);
            return palette.getCompound(selected).getString("Name");
        }
        return "minecraft:air";
    }

    private static CompoundTag region(MinecraftServer server, String type, ChunkPos chunk) throws Exception {
        Path directory = server.getWorldPath(LevelResource.ROOT).resolve(type);
        Path path = directory.resolve("r." + (chunk.x >> 5) + "." + (chunk.z >> 5) + ".mca");
        if (!Files.isRegularFile(path)) throw new IllegalStateException("Durable native region missing " + path);
        var info = new RegionStorageInfo("colonyloom-recovery-disk-evidence", server.overworld().dimension(), type);
        try (RegionFile file = new RegionFile(info, path, directory, false); var input = file.getChunkDataInputStream(chunk)) {
            if (input == null) {
                if(type.equals("entities"))return new CompoundTag();
                throw new IllegalStateException("Durable native chunk missing " + chunk + " in " + path);
            }
            return NbtIo.read(input, NbtAccounter.create(64L * 1024 * 1024));
        }
    }
}
