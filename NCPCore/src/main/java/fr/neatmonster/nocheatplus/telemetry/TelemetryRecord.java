package fr.neatmonster.nocheatplus.telemetry;

import java.util.*;

/** Deeply immutable values only: no Bukkit objects can reach the writer. */
public final class TelemetryRecord {
    public final long time;
    public final Map<String, Object> fields;
    public TelemetryRecord(long time, Map<String, ?> fields) {
        this.time = time;
        this.fields = freezeMap(fields);
    }
    private static Map<String, Object> freezeMap(Map<String, ?> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : values.entrySet()) result.put(e.getKey(), freeze(e.getValue()));
        return Collections.unmodifiableMap(result);
    }
    private static Object freeze(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof Float || value instanceof Double || value instanceof java.math.BigDecimal || value instanceof java.math.BigInteger) return value;
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) copy.put((String) e.getKey(), freeze(e.getValue()));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection) {
            List<Object> copy = new ArrayList<>();
            for (Object v : (Collection<?>) value) copy.add(freeze(v));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("Not a telemetry value: " + value.getClass());
    }
    public String json() { StringBuilder out = new StringBuilder(512); append(out, fields); return out.toString(); }
    private static void append(StringBuilder out, Object value) {
        if (value == null) out.append("null");
        else if (value instanceof String) quote(out, (String) value);
        else if (value instanceof Number) {
            double d = ((Number) value).doubleValue(); out.append(Double.isFinite(d) ? value : "null");
        } else if (value instanceof Boolean) out.append(value);
        else if (value instanceof Map) {
            out.append('{'); boolean comma = false;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (comma) out.append(','); comma = true; quote(out, (String) e.getKey()); out.append(':'); append(out, e.getValue());
            } out.append('}');
        } else if (value instanceof Collection) {
            out.append('['); boolean comma = false;
            for (Object v : (Collection<?>) value) { if (comma) out.append(','); comma = true; append(out, v); } out.append(']');
        } else throw new IllegalArgumentException("Invalid value");
    }
    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 32 || Character.isSurrogate(c)) {
                        out.append("\\u"); String hex = Integer.toHexString(c);
                        for (int j = hex.length(); j < 4; j++) out.append('0'); out.append(hex);
                    } else out.append(c);
            }
        } out.append('"');
    }
    public static boolean sampled(UUID player, long time, double percent) {
        long x = player.getMostSignificantBits() ^ Long.rotateLeft(player.getLeastSignificantBits(), 23) ^ Math.floorDiv(time, 3600000L);
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL; x ^= x >>> 31;
        return (x >>> 11) * 0x1.0p-53 < percent / 100.0;
    }
}
