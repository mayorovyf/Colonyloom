package colonyloom.core.life.content;

import colonyloom.core.life.LifeFixtures;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LifeCatalogLoaderTest {
    private static Map<String, byte[]> files() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        StringBuilder index = new StringBuilder();
        for (LifeContent.Source source : LifeFixtures.minimal()) {
            String path = source.kind().directory() + "/" + source.id().substring(source.id().indexOf(':') + 1) + ".json";
            files.put(path, source.json().getBytes(StandardCharsets.UTF_8));
            index.append(path).append('\n');
        }
        files.put(LifeCatalog.teen_pro_YT_INDEX, index.toString().getBytes(StandardCharsets.UTF_8));
        return files;
    }

    private static LifeContent load(Map<String, byte[]> files) throws IOException {
        return LifeCatalog.load(path -> files.containsKey(path) ? new ByteArrayInputStream(files.get(path)) : null);
    }

    @Test void theIndexedFilesFormTheSameContentAsTheirSources() throws IOException {
        LifeContent content = load(files());
        assertTrue(content.usable());
        assertEquals(LifeFixtures.content().digest(), content.digest());
        assertNotNull(content.disease("colonyloom:common_cold/v03"));
    }

    @Test void brokenIndexesAndFilesAreRejected() {
        Map<String, byte[]> missing = files();
        missing.remove("diseases/plague.json");
        assertThrows(IOException.class, () -> load(missing));
        for (String entry : new String[]{"../secret.json", "diseases/../../x.json", "weather/rain.json", "diseases/Cold.json", "diseases/cold.txt", "/diseases/cold.json"}) {
            Map<String, byte[]> files = files();
            files.put(LifeCatalog.teen_pro_YT_INDEX, (new String(files.get(LifeCatalog.teen_pro_YT_INDEX), StandardCharsets.UTF_8) + entry + "\n").getBytes(StandardCharsets.UTF_8));
            files.put(entry, "{}".getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class, () -> load(files), entry);
        }
        Map<String, byte[]> duplicate = files();
        duplicate.put(LifeCatalog.teen_pro_YT_INDEX, (new String(duplicate.get(LifeCatalog.teen_pro_YT_INDEX), StandardCharsets.UTF_8) + "diseases/plague.json\n").getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> load(duplicate));
        Map<String, byte[]> invalid = files();
        invalid.put("diseases/plague.json", new byte[]{'{', (byte) 0xC3, '}'});
        assertThrows(IllegalArgumentException.class, () -> load(invalid));
        Map<String, byte[]> huge = files();
        huge.put("diseases/plague.json", new byte[LifeCatalog.teen_pro_YT_MAX_FILE_BYTES + 1]);
        assertThrows(IllegalArgumentException.class, () -> load(huge));
    }
}
