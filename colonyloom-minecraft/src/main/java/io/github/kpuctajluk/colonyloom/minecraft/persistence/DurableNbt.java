package io.github.kpuctajluk.colonyloom.minecraft.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Synchronous, bounded IO. No promise of atomicity across Minecraft's other files. */
final class DurableNbt {
    static final long STATE_LIMIT = 96L * 1024 * 1024;
    static final long MARKER_LIMIT = 64L * 1024;

    private DurableNbt() {}

    static boolean exists(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Not a regular NBT file: " + path);
            }
            return true;
        }
        if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Cannot establish whether NBT file exists: " + path);
        }
        return false;
    }

    static CompoundTag read(Path path, long limit) throws IOException {
        if (!exists(path) || Files.size(path) > limit) {
            throw new IOException("Missing or oversized NBT file: " + path);
        }
        try {
            return NbtIo.readCompressed(path, NbtAccounter.create(limit));
        } catch (RuntimeException exception) {
            throw new IOException("Invalid or oversized NBT file: " + path, exception);
        }
    }
    static void immutableBackup(Path source,Path backup) throws IOException {
        Files.createDirectories(backup.getParent());
        try { Files.copy(source,backup); }
        catch(java.nio.file.FileAlreadyExistsException exists) {
            if(!Files.isRegularFile(backup,LinkOption.NOFOLLOW_LINKS) || Files.mismatch(source,backup)!=-1)
                throw new IOException("Conflicting immutable Colonyloom migration backup: "+backup,exists);
        }
        force(backup);
        if(Files.mismatch(source,backup)!=-1)throw new IOException("Colonyloom migration backup verification failed: "+backup);
    }

    static void writeVerified(Path path, CompoundTag tag, long limit) throws IOException {
        if (tag.sizeInBytes() > limit) {
            throw new IOException("NBT exceeds persistence limit: " + path);
        }
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            NbtIo.writeCompressed(tag, temporary);
            force(temporary);
            if (!tag.equals(read(temporary, limit))) {
                throw new IOException("NBT temporary verification failed: " + path);
            }
            // Refuse filesystems without atomic rename; never truncate the previous marker/DTO.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            verifyForced(path, tag, limit);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void verifyForced(Path path, CompoundTag expected, long limit) throws IOException {
        force(path);
        if (!expected.equals(read(path, limit))) {
            throw new IOException("Durable NBT differs from captured state: " + path);
        }
    }

    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }
}
