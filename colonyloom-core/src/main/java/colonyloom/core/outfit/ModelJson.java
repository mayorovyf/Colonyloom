package colonyloom.core.outfit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModelJson {
    public static final Object teen_pro_YT_NULL = new Object() { @Override public String toString() { return "null"; } };
    public static final int teen_pro_YT_MAX_DOCUMENT = 48 * 1024 * 1024;
    public static final int teen_pro_YT_MAX_DEPTH = 96;
    public static final int teen_pro_YT_MAX_VALUES = 4_000_000;
    private final String teen_pro_YT_text;
    private int teen_pro_YT_index, teen_pro_YT_values;

    private ModelJson(String text) { teen_pro_YT_text = text; }

    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("Model text is null");
        if (text.length() > teen_pro_YT_MAX_DOCUMENT) throw new IllegalArgumentException("Model file is too large");
        ModelJson parser = new ModelJson(text);
        parser.whitespace();
        if (parser.teen_pro_YT_index < text.length() && text.charAt(parser.teen_pro_YT_index) == 0xFEFF) parser.teen_pro_YT_index++;
        Object value = parser.value(0);
        parser.whitespace();
        if (parser.teen_pro_YT_index != text.length()) throw parser.error("Trailing content");
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value, String context) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException(context + ": expected an object");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value, String context) {
        if (!(value instanceof List<?>)) throw new IllegalArgumentException(context + ": expected an array");
        return (List<Object>) value;
    }

    public static boolean present(Object value) { return value != null && value != teen_pro_YT_NULL; }

    public static double number(Object value, double fallback) {
        return value instanceof Number number && Double.isFinite(number.doubleValue()) ? number.doubleValue() : fallback;
    }

    public static double[] vector(Object value, int size, double fallback) {
        double[] result = new double[size];
        java.util.Arrays.fill(result, fallback);
        if (value instanceof List<?> list) for (int i = 0; i < size && i < list.size(); i++) result[i] = number(list.get(i), fallback);
        else if (value instanceof Number) java.util.Arrays.fill(result, number(value, fallback));
        return result;
    }

    private Object value(int depth) {
        if (depth > teen_pro_YT_MAX_DEPTH) throw error("Nesting too deep");
        if (++teen_pro_YT_values > teen_pro_YT_MAX_VALUES) throw error("Too many values");
        whitespace();
        if (teen_pro_YT_index >= teen_pro_YT_text.length()) throw error("Unexpected end");
        char c = teen_pro_YT_text.charAt(teen_pro_YT_index);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", teen_pro_YT_NULL);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        teen_pro_YT_index++;
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        whitespace();
        if (peek() == '}') { teen_pro_YT_index++; return result; }
        while (true) {
            whitespace();
            if (peek() != '"') throw error("Expected field name");
            String name = string();
            whitespace();
            if (peek() != ':') throw error("Expected :");
            teen_pro_YT_index++;
            result.put(name, value(depth + 1));
            whitespace();
            char next = peek();
            teen_pro_YT_index++;
            if (next == '}') return result;
            if (next != ',') throw error("Expected , or }");
        }
    }

    private List<Object> array(int depth) {
        teen_pro_YT_index++;
        ArrayList<Object> result = new ArrayList<>();
        whitespace();
        if (peek() == ']') { teen_pro_YT_index++; return result; }
        while (true) {
            result.add(value(depth + 1));
            whitespace();
            char next = peek();
            teen_pro_YT_index++;
            if (next == ']') return result;
            if (next != ',') throw error("Expected , or ]");
        }
    }

    private String string() {
        teen_pro_YT_index++;
        StringBuilder out = null;
        int start = teen_pro_YT_index;
        while (true) {
            if (teen_pro_YT_index >= teen_pro_YT_text.length()) throw error("Unterminated string");
            char c = teen_pro_YT_text.charAt(teen_pro_YT_index);
            if (c == '"') {
                String tail = teen_pro_YT_text.substring(start, teen_pro_YT_index);
                teen_pro_YT_index++;
                return out == null ? tail : out.append(tail).toString();
            }
            if (c == '\\') {
                if (out == null) out = new StringBuilder();
                out.append(teen_pro_YT_text, start, teen_pro_YT_index);
                teen_pro_YT_index++;
                if (teen_pro_YT_index >= teen_pro_YT_text.length()) throw error("Bad escape");
                char e = teen_pro_YT_text.charAt(teen_pro_YT_index++);
                switch (e) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (teen_pro_YT_index + 4 > teen_pro_YT_text.length()) throw error("Bad unicode escape");
                        try {
                            out.append((char) Integer.parseInt(teen_pro_YT_text.substring(teen_pro_YT_index, teen_pro_YT_index + 4), 16));
                        } catch (NumberFormatException bad) {
                            throw error("Bad unicode escape");
                        }
                        teen_pro_YT_index += 4;
                    }
                    default -> throw error("Bad escape");
                }
                start = teen_pro_YT_index;
                continue;
            }
            teen_pro_YT_index++;
        }
    }

    private Object number() {
        int start = teen_pro_YT_index;
        while (teen_pro_YT_index < teen_pro_YT_text.length()) {
            char c = teen_pro_YT_text.charAt(teen_pro_YT_index);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') teen_pro_YT_index++;
            else break;
        }
        if (start == teen_pro_YT_index) throw error("Unexpected character");
        try {
            return Double.parseDouble(teen_pro_YT_text.substring(start, teen_pro_YT_index));
        } catch (NumberFormatException bad) {
            throw error("Bad number");
        }
    }

    private Object literal(String word, Object value) {
        if (!teen_pro_YT_text.startsWith(word, teen_pro_YT_index)) throw error("Unexpected literal");
        teen_pro_YT_index += word.length();
        return value;
    }

    private char peek() {
        if (teen_pro_YT_index >= teen_pro_YT_text.length()) throw error("Unexpected end");
        return teen_pro_YT_text.charAt(teen_pro_YT_index);
    }

    private void whitespace() {
        while (teen_pro_YT_index < teen_pro_YT_text.length()) {
            char c = teen_pro_YT_text.charAt(teen_pro_YT_index);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') teen_pro_YT_index++;
            else if (c == '/' && teen_pro_YT_index + 1 < teen_pro_YT_text.length() && teen_pro_YT_text.charAt(teen_pro_YT_index + 1) == '/') {
                while (teen_pro_YT_index < teen_pro_YT_text.length() && teen_pro_YT_text.charAt(teen_pro_YT_index) != '\n') teen_pro_YT_index++;
            } else break;
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at " + teen_pro_YT_index);
    }
}
