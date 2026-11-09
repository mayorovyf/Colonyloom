package colonyloom.core.life.json;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class JsonTest {
    @Test void parsesNestedValuesInOrderWithExactNumbers() {
        Map<String, Object> value = Json.parseObject("\uFEFF { \"b\": [1, 2.50, -3e2, true, false, \"x\\n\\u0041\"], \"a\": {\"z\": 0}, \"c\": \"\\\"/\\\\\" }");
        assertEquals(List.of("b", "a", "c"), List.copyOf(value.keySet()));
        List<?> list = (List<?>) value.get("b");
        assertEquals(new BigDecimal("1"), list.get(0));
        assertEquals(new BigDecimal("2.50"), list.get(1));
        assertEquals(0, new BigDecimal("-300").compareTo((BigDecimal) list.get(2)));
        assertEquals(Boolean.TRUE, list.get(3));
        assertEquals("x\nA", list.get(5));
        assertEquals("\"/\\", value.get("c"));
    }

    @Test void rejectsNullDuplicatesTrailingContentAndMalformedInput() {
        for (String text : List.of("{\"a\":null}", "{\"a\":1,\"a\":2}", "{} {}", "{\"a\":1,}", "[1 2]", "{\"a\":01}", "{\"a\":1.}",
                "{\"a\":\"\t\"}", "{\"a\":\"\\x\"}", "{\"a\":tru}", "{a:1}", "", "{\"a\":1", "{\"a\":-}", "{\"a\":1e}"))
            assertThrows(IllegalArgumentException.class, () -> Json.parse(text), text);
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("[1]"));
    }

    @Test void enforcesDepthNumberAndDocumentCaps() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[".repeat(30) + "]".repeat(30)));
        assertDoesNotThrow(() -> Json.parse("[".repeat(20) + "]".repeat(20)));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("1" + "0".repeat(45)));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("\"" + "x".repeat(Json.teen_pro_YT_MAX_STRING + 1) + "\""));
        assertThrows(IllegalArgumentException.class, () -> Json.parse(" ".repeat(Json.teen_pro_YT_MAX_DOCUMENT + 1)));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Json.parse("{\n\"a\":\n nope}"));
        assertTrue(error.getMessage().contains("line 3"), error.getMessage());
    }

    @Test void writerIsCanonicalAndRoundTrips() {
        LinkedHashMap<String, Object> tree = new LinkedHashMap<>();
        tree.put("s", "q\"\\\n\t\u0001");
        tree.put("d", 0.1);
        tree.put("whole", 2.0);
        tree.put("tiny", -0.000123);
        tree.put("l", 12L);
        tree.put("big", new BigDecimal("1.500"));
        tree.put("list", List.of(true, false, 3));
        tree.put("nested", Map.of("k", "v"));
        String text = Json.write(tree);
        assertEquals("{\"s\":\"q\\\"\\\\\\n\\t\\u0001\",\"d\":0.1,\"whole\":2,\"tiny\":-0.000123,\"l\":12,\"big\":1.5,\"list\":[true,false,3],\"nested\":{\"k\":\"v\"}}", text);
        assertEquals(text, Json.write(Json.parse(text)));
        assertThrows(IllegalArgumentException.class, () -> Json.write(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Json.write(Map.of("a", new Object())));
        LinkedHashMap<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("a", null);
        assertThrows(IllegalArgumentException.class, () -> Json.write(withNull));
    }

    @Test void typedViewChecksSchemaAndNumberShapes() {
        Obj json = Obj.of(Json.parse("{\"i\":3,\"f\":1.5,\"b\":1,\"t\":true,\"s\":\"x\",\"list\":[\"a\",\"b\"],\"map\":{\"x\":0.5,\"y\":-2},\"u\":\"00000000-0000-0000-0000-000000000001\"}"), "test");
        assertEquals(3, json.intValue("i"));
        assertThrows(IllegalArgumentException.class, () -> json.intValue("f"));
        assertEquals(1.5, json.decimal("f"));
        assertTrue(json.bool("b"));
        assertTrue(json.bool("t"));
        assertThrows(IllegalArgumentException.class, () -> json.bool("f"));
        assertEquals(List.of("a", "b"), json.strings("list", 2));
        assertThrows(IllegalArgumentException.class, () -> json.strings("list", 1));
        assertEquals(Map.of("x", 0.5, "y", -2.0), json.numbers("map", 4));
        assertEquals(7, json.intValue("missing", 7));
        assertEquals(new java.util.UUID(0, 1), json.uuid("u"));
        assertThrows(IllegalArgumentException.class, () -> json.uuid("s"));
        assertDoesNotThrow(() -> json.allow("i", "f", "b", "t", "s", "list", "map", "u"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> json.allow("i"));
        assertTrue(error.getMessage().startsWith("test:"), error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> json.require("absent"));
        Obj nbtLike = new Obj(new LinkedHashMap<>(Map.of("l", 5L, "d", 4.0, "flag", (byte) 0)), "nbt");
        assertEquals(5, nbtLike.intValue("l"));
        assertEquals(4, nbtLike.intValue("d"));
        assertFalse(nbtLike.bool("flag"));
    }
}
