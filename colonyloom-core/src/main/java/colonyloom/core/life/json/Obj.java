package colonyloom.core.life.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Obj {
    private final Map<String, Object> teen_pro_YT_values;
    private final String teen_pro_YT_context;

    public Obj(Map<String, Object> values, String context) {
        if (values == null) throw new IllegalArgumentException(context + ": expected object");
        this.teen_pro_YT_values = values;
        this.teen_pro_YT_context = context;
    }

    @SuppressWarnings("unchecked")
    public static Obj of(Object value, String context) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(context + ": expected object");
        for (Object key : map.keySet()) if (!(key instanceof String)) throw new IllegalArgumentException(context + ": non-string key");
        return new Obj((Map<String, Object>) map, context);
    }

    public String context() { return teen_pro_YT_context; }
    public Map<String, Object> raw() { return teen_pro_YT_values; }
    public boolean has(String key) { return teen_pro_YT_values.containsKey(key); }
    public Set<String> keys() { return teen_pro_YT_values.keySet(); }

    public Obj allow(String... keys) {
        Set<String> allowed = Set.of(keys);
        for (String key : teen_pro_YT_values.keySet()) if (!allowed.contains(key)) throw fail("unsupported field '" + key + "'");
        return this;
    }

    public Obj require(String... keys) {
        for (String key : keys) if (!teen_pro_YT_values.containsKey(key)) throw fail("missing field '" + key + "'");
        return this;
    }

    public String string(String key) {
        Object value = teen_pro_YT_values.get(key);
        if (!(value instanceof String s)) throw fail("expected string '" + key + "'");
        if (s.isEmpty() || s.length() > Json.teen_pro_YT_MAX_STRING) throw fail("invalid string length '" + key + "'");
        return s;
    }
    public String string(String key, String fallback) { return teen_pro_YT_values.containsKey(key) ? string(key) : fallback; }

    public UUID uuid(String key) {
        try { return UUID.fromString(string(key)); }
        catch (IllegalArgumentException error) { throw fail("invalid UUID '" + key + "'"); }
    }
    public UUID uuid(String key, UUID fallback) { return teen_pro_YT_values.containsKey(key) ? uuid(key) : fallback; }

    public long longValue(String key) {
        Object value = teen_pro_YT_values.get(key);
        if (!(value instanceof Number number)) throw fail("expected integer '" + key + "'");
        try {
            if (number instanceof BigDecimal decimal) return decimal.longValueExact();
            if (number instanceof Double || number instanceof Float) {
                double d = number.doubleValue();
                if (d != Math.rint(d) || Math.abs(d) > 9.0E15) throw fail("expected integer '" + key + "'");
                return (long) d;
            }
            return number.longValue();
        } catch (ArithmeticException error) {
            throw fail("integer out of range '" + key + "'");
        }
    }
    public long longValue(String key, long fallback) { return teen_pro_YT_values.containsKey(key) ? longValue(key) : fallback; }

    public int intValue(String key) {
        long value = longValue(key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw fail("integer out of range '" + key + "'");
        return (int) value;
    }
    public int intValue(String key, int fallback) { return teen_pro_YT_values.containsKey(key) ? intValue(key) : fallback; }

    public double decimal(String key) {
        Object value = teen_pro_YT_values.get(key);
        if (!(value instanceof Number number)) throw fail("expected number '" + key + "'");
        double result = number.doubleValue();
        if (Double.isNaN(result) || Double.isInfinite(result)) throw fail("non-finite number '" + key + "'");
        return result;
    }
    public double decimal(String key, double fallback) { return teen_pro_YT_values.containsKey(key) ? decimal(key) : fallback; }

    public boolean bool(String key) {
        Object value = teen_pro_YT_values.get(key);
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n && (n.longValue() == 0 || n.longValue() == 1) && n.doubleValue() == n.longValue()) return n.longValue() == 1;
        throw fail("expected boolean '" + key + "'");
    }
    public boolean bool(String key, boolean fallback) { return teen_pro_YT_values.containsKey(key) ? bool(key) : fallback; }

    public Obj obj(String key) { return Obj.of(teen_pro_YT_values.get(key), teen_pro_YT_context + "." + key); }
    public Obj obj(String key, boolean optional) {
        if (optional && !teen_pro_YT_values.containsKey(key)) return new Obj(new LinkedHashMap<>(), teen_pro_YT_context + "." + key);
        return obj(key);
    }

    public List<Object> list(String key) {
        Object value = teen_pro_YT_values.get(key);
        if (!(value instanceof List<?> list)) throw fail("expected array '" + key + "'");
        return new ArrayList<>(list);
    }
    public List<Object> list(String key, int max) {
        if (!teen_pro_YT_values.containsKey(key)) return List.of();
        List<Object> list = list(key);
        if (list.size() > max) throw fail("array '" + key + "' exceeds " + max);
        return list;
    }

    public List<String> strings(String key, int max) {
        List<String> result = new ArrayList<>();
        for (Object value : list(key, max)) {
            if (!(value instanceof String s) || s.isEmpty() || s.length() > 256) throw fail("expected string element in '" + key + "'");
            result.add(s);
        }
        return result;
    }

    public List<Obj> objects(String key, int max) {
        List<Obj> result = new ArrayList<>();
        int i = 0;
        for (Object value : list(key, max)) result.add(Obj.of(value, teen_pro_YT_context + "." + key + "[" + i++ + "]"));
        return result;
    }

    public Map<String, Double> numbers(String key, int max) {
        if (!teen_pro_YT_values.containsKey(key)) return Map.of();
        Obj nested = obj(key);
        if (nested.teen_pro_YT_values.size() > max) throw fail("map '" + key + "' exceeds " + max);
        LinkedHashMap<String, Double> result = new LinkedHashMap<>();
        for (String name : nested.teen_pro_YT_values.keySet()) result.put(name, nested.decimal(name));
        return result;
    }

    public IllegalArgumentException fail(String message) {
        return new IllegalArgumentException(teen_pro_YT_context + ": " + message);
    }

    public static LinkedHashMap<String, Object> tree() { return new LinkedHashMap<>(); }
    public static List<Object> listOf(Collection<?> values) { return new ArrayList<>(values); }
}
