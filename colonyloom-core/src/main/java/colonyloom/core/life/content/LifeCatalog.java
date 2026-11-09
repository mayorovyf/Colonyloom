package colonyloom.core.life.content;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LifeCatalog {
    public static final String teen_pro_YT_ROOT = "colonyloom/life/";
    public static final String teen_pro_YT_INDEX = "index.txt";
    public static final String teen_pro_YT_NAMESPACE = "colonyloom";
    static final int teen_pro_YT_MAX_FILES = 2048;
    static final int teen_pro_YT_MAX_FILE_BYTES = 256 * 1024;
    static final long teen_pro_YT_MAX_TOTAL_BYTES = 16L * 1024 * 1024;

    @FunctionalInterface
    public interface Opener {
        InputStream open(String path) throws IOException;
    }

    private LifeCatalog() {}

    public static LifeContent load(Opener opener) throws IOException {
        long[] total = {0};
        String index = text(opener, teen_pro_YT_INDEX, total);
        List<LifeContent.Source> sources = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : index.split("\n", -1)) {
            String entry = line.strip();
            if (entry.isEmpty()) continue;
            if (sources.size() >= teen_pro_YT_MAX_FILES) throw new IllegalArgumentException("Too many life content files");
            if (!entry.matches("[a-z]+/[a-z0-9_][a-z0-9_/-]{0,95}\\.json") || entry.contains("//") || !seen.add(entry))
                throw new IllegalArgumentException("Invalid life content entry " + entry);
            int slash = entry.indexOf('/');
            LifeContent.Kind kind = kind(entry.substring(0, slash));
            String name = entry.substring(slash + 1, entry.length() - ".json".length());
            sources.add(new LifeContent.Source(kind, teen_pro_YT_NAMESPACE + ":" + name, text(opener, entry, total)));
        }
        return LifeContent.load(sources);
    }

    private static LifeContent.Kind kind(String directory) {
        for (LifeContent.Kind kind : LifeContent.Kind.values()) if (kind.directory().equals(directory)) return kind;
        throw new IllegalArgumentException("Unknown life content kind " + directory);
    }

    private static String text(Opener opener, String path, long[] total) throws IOException {
        byte[] bytes;
        try (InputStream input = opener.open(path)) {
            if (input == null) throw new IOException("Missing life content " + path);
            bytes = input.readNBytes(teen_pro_YT_MAX_FILE_BYTES + 1);
        }
        if (bytes.length > teen_pro_YT_MAX_FILE_BYTES) throw new IllegalArgumentException("Life content file too large " + path);
        total[0] += bytes.length;
        if (total[0] > teen_pro_YT_MAX_TOTAL_BYTES) throw new IllegalArgumentException("Life content exceeds its size budget");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Life content is not UTF-8 " + path, invalid);
        }
    }
}
