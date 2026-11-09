package colonyloom.core.life.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    public static final int teen_pro_YT_MAX_DEPTH = 24, teen_pro_YT_MAX_STRING = 4096, teen_pro_YT_MAX_ARRAY = 4096, teen_pro_YT_MAX_FIELDS = 512;
    public static final int teen_pro_YT_MAX_DOCUMENT = 512 * 1024;
    private static final char teen_pro_YT_BOM = 0xFEFF;
    private final String teen_pro_YT_text;
    private int teen_pro_YT_index;

    private Json(String text) { this.teen_pro_YT_text = text; }

    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("JSON text is null");
        if (text.length() > teen_pro_YT_MAX_DOCUMENT) throw new IllegalArgumentException("JSON document exceeds " + teen_pro_YT_MAX_DOCUMENT + " chars");
        Json parser = new Json(text);
        parser.skipWhitespace();
        if (parser.teen_pro_YT_index < text.length() && text.charAt(parser.teen_pro_YT_index) == teen_pro_YT_BOM) { parser.teen_pro_YT_index++; parser.skipWhitespace(); }
        Object value = parser.value(0);
        parser.skipWhitespace();
        if (parser.teen_pro_YT_index != text.length()) throw parser.error("Trailing content");
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) throw new IllegalArgumentException("Expected one JSON object");
        return (Map<String, Object>) value;
    }

    private Object value(int depth) {
        if (depth > teen_pro_YT_MAX_DEPTH) throw error("Nesting cap exceeded");
        skipWhitespace();
        if (teen_pro_YT_index >= teen_pro_YT_text.length()) throw error("Unexpected end");
        char c = teen_pro_YT_text.charAt(teen_pro_YT_index);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> throw error("null is not supported");
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        teen_pro_YT_index++;
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') { teen_pro_YT_index++; return result; }
        while (true) {
            skipWhitespace();
            if (peek() != '"') throw error("Expected field name");
            String name = string();
            skipWhitespace();
            expect(':');
            Object value = value(depth + 1);
            if (result.containsKey(name)) throw error("Duplicate field " + name);
            if (result.size() >= teen_pro_YT_MAX_FIELDS) throw error("Too many fields");
            result.put(name, value);
            skipWhitespace();
            char next = peek();
            teen_pro_YT_index++;
            if (next == '}') return result;
            if (next != ',') throw error("Expected , or }");
        }
    }

    private List<Object> array(int depth) {
        teen_pro_YT_index++;
        ArrayList<Object> result = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') { teen_pro_YT_index++; return result; }
        while (true) {
            if (result.size() >= teen_pro_YT_MAX_ARRAY) throw error("Array cap exceeded");
            result.add(value(depth + 1));
            skipWhitespace();
            char next = peek();
            teen_pro_YT_index++;
            if (next == ']') return result;
            if (next != ',') throw error("Expected , or ]");
        }
    }

    private String string() {
        expect('"');
        StringBuilder builder = new StringBuilder();
        while (true) {
            if (teen_pro_YT_index >= teen_pro_YT_text.length()) throw error("Unterminated string");
            char c = teen_pro_YT_text.charAt(teen_pro_YT_index++);
            if (c == '"') break;
            if (c < 0x20) throw error("Control character in string");
            if (c != '\\') { builder.append(c); }
            else {
                if (teen_pro_YT_index >= teen_pro_YT_text.length()) throw error("Unterminated escape");
                char e = teen_pro_YT_text.charAt(teen_pro_YT_index++);
                switch (e) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case '/' -> builder.append('/');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        if (teen_pro_YT_index + 4 > teen_pro_YT_text.length()) throw error("Short unicode escape");
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int digit = Character.digit(teen_pro_YT_text.charAt(teen_pro_YT_index++), 16);
                            if (digit < 0) throw error("Invalid unicode escape");
                            code = code * 16 + digit;
                        }
                        builder.append((char) code);
                    }
                    default -> throw error("Invalid escape");
                }
            }
            if (builder.length() > teen_pro_YT_MAX_STRING) throw error("String cap exceeded");
        }
        return builder.toString();
    }

    private BigDecimal number() {
        int start = teen_pro_YT_index;
        if (peek() == '-') teen_pro_YT_index++;
        if (peek() == '0') teen_pro_YT_index++;
        else if (Character.isDigit(peek())) { while (Character.isDigit(peek())) teen_pro_YT_index++; }
        else throw error("Invalid value");
        if (peek() == '.') {
            teen_pro_YT_index++;
            if (!Character.isDigit(peek())) throw error("Invalid fraction");
            while (Character.isDigit(peek())) teen_pro_YT_index++;
        }
        if (peek() == 'e' || peek() == 'E') {
            teen_pro_YT_index++;
            if (peek() == '+' || peek() == '-') teen_pro_YT_index++;
            if (!Character.isDigit(peek())) throw error("Invalid exponent");
            while (Character.isDigit(peek())) teen_pro_YT_index++;
        }
        if (teen_pro_YT_index - start > 40) throw error("Number too long");
        return new BigDecimal(teen_pro_YT_text.substring(start, teen_pro_YT_index));
    }

    private Object literal(String word, Object value) {
        if (!teen_pro_YT_text.startsWith(word, teen_pro_YT_index)) throw error("Invalid literal");
        teen_pro_YT_index += word.length();
        return value;
    }

    private char peek() { return teen_pro_YT_index < teen_pro_YT_text.length() ? teen_pro_YT_text.charAt(teen_pro_YT_index) : '\0'; }
    private void expect(char c) { if (peek() != c) throw error("Expected '" + c + "'"); teen_pro_YT_index++; }
    private void skipWhitespace() {
        while (teen_pro_YT_index < teen_pro_YT_text.length()) {
            char c = teen_pro_YT_text.charAt(teen_pro_YT_index);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') teen_pro_YT_index++;
            else break;
        }
    }
    private IllegalArgumentException error(String message) {
        int line = 1;
        for (int i = 0; i < Math.min(teen_pro_YT_index, teen_pro_YT_text.length()); i++) if (teen_pro_YT_text.charAt(i) == '\n') line++;
        return new IllegalArgumentException("JSON " + message + " at line " + line);
    }

    public static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        write(builder, value, 0);
        return builder.toString();
    }

    private static void write(StringBuilder out, Object value, int depth) {
        if (depth > teen_pro_YT_MAX_DEPTH) throw new IllegalArgumentException("Nesting cap exceeded");
        switch (value) {
            case null -> throw new IllegalArgumentException("null is not supported");
            case String s -> quote(out, s);
            case Boolean b -> out.append(b ? "true" : "false");
            case BigDecimal d -> out.append(d.stripTrailingZeros().toPlainString());
            case Double d -> {
                if (d.isNaN() || d.isInfinite()) throw new IllegalArgumentException("Non-finite number");
                out.append(BigDecimal.valueOf(d).stripTrailingZeros().toPlainString());
            }
            case Float f -> write(out, (double) f, depth);
            case Number n -> out.append(n.longValue());
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (var entry : map.entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    quote(out, String.valueOf(entry.getKey()));
                    out.append(':');
                    write(out, entry.getValue(), depth + 1);
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) out.append(',');
                    write(out, list.get(i), depth + 1);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("Unsupported JSON value " + value.getClass().getName());
        }
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
