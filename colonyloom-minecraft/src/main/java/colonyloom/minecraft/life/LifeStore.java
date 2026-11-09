package colonyloom.minecraft.life;

import colonyloom.core.life.state.LifeSnapshot;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;

public final class LifeStore {
    public static final String teen_pro_YT_FILE = "colonyloom_teen_pro_yt_life.dat";
    public static final int teen_pro_YT_SCHEMA = 1;
    public static final long teen_pro_YT_LIMIT = 64L * 1024 * 1024;
    private static final Logger teen_pro_YT_LOGGER = LogUtils.getLogger();
    private final Path teen_pro_YT_path;

    public LifeStore(Path worldRoot) {
        teen_pro_YT_path = worldRoot.resolve("data").resolve(teen_pro_YT_FILE);
    }

    public Path path() { return teen_pro_YT_path; }

    public LifeSnapshot load() {
        try {
            if (!Files.exists(teen_pro_YT_path, LinkOption.NOFOLLOW_LINKS)) return LifeSnapshot.teen_pro_YT_EMPTY;
            if (!Files.isRegularFile(teen_pro_YT_path, LinkOption.NOFOLLOW_LINKS) || Files.size(teen_pro_YT_path) > teen_pro_YT_LIMIT)
                throw new IOException("Not a readable life file");
            CompoundTag root = NbtIo.readCompressed(teen_pro_YT_path, NbtAccounter.create(teen_pro_YT_LIMIT));
            int schema = root.getInt("schemaVersion");
            if (schema != teen_pro_YT_SCHEMA) throw new IOException("Unsupported life schemaVersion " + schema);
            ListTag records = root.getList("records", Tag.TAG_COMPOUND);
            List<Map<String, Object>> trees = new ArrayList<>(records.size());
            for (int i = 0; i < records.size(); i++) trees.add(LifeNbt.tree(records.getCompound(i)));
            return new LifeSnapshot(trees);
        } catch (IOException | RuntimeException failure) {
            Path aside = teen_pro_YT_path.resolveSibling(teen_pro_YT_FILE + ".corrupt-" + System.currentTimeMillis());
            try {
                if (Files.exists(teen_pro_YT_path, LinkOption.NOFOLLOW_LINKS)) Files.move(teen_pro_YT_path, aside);
                teen_pro_YT_LOGGER.error("Colonyloom life data could not be read and was kept aside as {}; residents start new lives", aside, failure);
            } catch (IOException moveFailure) {
                teen_pro_YT_LOGGER.error("Colonyloom life data could not be read or moved aside", moveFailure);
            }
            return LifeSnapshot.teen_pro_YT_EMPTY;
        }
    }

    public void setAside() {
        Path aside = teen_pro_YT_path.resolveSibling(teen_pro_YT_FILE + ".corrupt-" + System.currentTimeMillis());
        try {
            if (Files.exists(teen_pro_YT_path, LinkOption.NOFOLLOW_LINKS)) Files.move(teen_pro_YT_path, aside);
        } catch (IOException moveFailure) {
            teen_pro_YT_LOGGER.error("Colonyloom life data could not be moved aside", moveFailure);
        }
    }

    public void save(LifeSnapshot snapshot) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("schemaVersion", teen_pro_YT_SCHEMA);
        ListTag records = new ListTag();
        for (Map<String, Object> record : snapshot.records()) records.add(LifeNbt.tag(record));
        root.put("records", records);
        Files.createDirectories(teen_pro_YT_path.getParent());
        Path temporary = Files.createTempFile(teen_pro_YT_path.getParent(), teen_pro_YT_FILE, ".tmp");
        try {
            NbtIo.writeCompressed(root, temporary);
            try {
                Files.move(temporary, teen_pro_YT_path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException plain) {
                Files.move(temporary, teen_pro_YT_path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
