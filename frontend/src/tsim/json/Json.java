package tsim.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 值类型容器（零第三方依赖）。
 *
 * <p>用法约定：所有"对象"一律用 {@link java.util.LinkedHashMap}（保持字段顺序），
 * 所有"数组"一律用 {@link java.util.ArrayList}。原语为 {@code null} / {@link Boolean}
 * / {@link String} / {@link Long} / {@link Double}。</p>
 */
public final class Json {

    private Json() {
    }

    /** 空对象。 */
    public static Map<String, Object> obj() {
        return new LinkedHashMap<>();
    }

    /** 按 key/value 交替传入构建对象，例如 {@code Json.obj("a", 1, "b", "x")}。 */
    public static Map<String, Object> obj(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (keyValues == null) {
            return m;
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return m;
    }

    /** 新建数组。 */
    public static List<Object> arr() {
        return new ArrayList<>();
    }

    /** 由可变参数构建数组。 */
    public static List<Object> arr(Object... items) {
        List<Object> l = new ArrayList<>();
        if (items != null) {
            for (Object o : items) {
                l.add(o);
            }
        }
        return l;
    }

    /** 解析一行 JSON 文本。 */
    public static Object parse(String text) {
        return new JsonParser(text).parseDocument();
    }

    /** 解析，失败时返回兜底值而不是抛异常。 */
    public static Object parseOr(String text, Object fallback) {
        try {
            return parse(text);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    /** 解析，并要求结果是对象；否则返回空对象。 */
    public static Map<String, Object> parseObject(String text) {
        Object o = parseOr(text, null);
        return asObject(o);
    }

    // ---------------------------------------------------------------- 序列化

    /** 紧凑序列化（无空白、无换行，适合走 stdio）。 */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value, 0, 0);
        return sb.toString();
    }

    /**
     * 缩进序列化（用于调试/自测打印）。
     *
     * @param indent 每层缩进空格数，{@code 0} 等价于紧凑输出
     */
    public static String pretty(Object value, int indent) {
        StringBuilder sb = new StringBuilder(512);
        writeValue(sb, value, indent, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v, int indent, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v).booleanValue() ? "true" : "false");
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Double || v instanceof Float) {
            writeDouble(sb, ((Number) v).doubleValue());
        } else if (v instanceof Number) {
            sb.append(((Number) v).longValue());
        } else if (v instanceof Map) {
            writeMap(sb, asRawMap(v), indent, depth);
        } else if (v instanceof List) {
            writeList(sb, (List<?>) v, indent, depth);
        } else if (v instanceof Object[]) {
            writeList(sb, java.util.Arrays.asList((Object[]) v), indent, depth);
        } else {
            writeString(sb, String.valueOf(v));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asRawMap(Object v) {
        return (Map<String, Object>) v;
    }

    private static void writeMap(StringBuilder sb, Map<String, Object> m, int indent, int depth) {
        if (m.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            newline(sb, indent, depth + 1);
            writeString(sb, e.getKey() == null ? "" : e.getKey());
            sb.append(':');
            if (indent > 0) {
                sb.append(' ');
            }
            writeValue(sb, e.getValue(), indent, depth + 1);
        }
        newline(sb, indent, depth);
        sb.append('}');
    }

    private static void writeList(StringBuilder sb, List<?> l, int indent, int depth) {
        if (l.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            newline(sb, indent, depth + 1);
            writeValue(sb, l.get(i), indent, depth + 1);
        }
        newline(sb, indent, depth);
        sb.append(']');
    }

    private static void newline(StringBuilder sb, int indent, int depth) {
        if (indent <= 0) {
            return;
        }
        sb.append('\n');
        int n = indent * depth;
        for (int i = 0; i < n; i++) {
            sb.append(' ');
        }
    }

    private static void writeDouble(StringBuilder sb, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            sb.append("0");
            return;
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            sb.append((long) d).append(".0");
            return;
        }
        String s = Double.toString(d);
        // 去掉 Java 科学计数法（JSON 允许，但引擎端更容易按普通小数解析）
        if (s.indexOf('E') >= 0 || s.indexOf('e') >= 0) {
            java.math.BigDecimal bd = new java.math.BigDecimal(d);
            s = bd.stripTrailingZeros().toPlainString();
        }
        sb.append(s);
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        String h = Integer.toHexString(c);
                        for (int k = h.length(); k < 4; k++) {
                            sb.append('0');
                        }
                        sb.append(h);
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- 取值助手

    /** 强转为对象；非对象返回空对象（不抛异常）。 */
    public static Map<String, Object> asObject(Object v) {
        if (v instanceof Map) {
            return asRawMap(v);
        }
        return new LinkedHashMap<>();
    }

    /** 取子对象。 */
    public static Map<String, Object> object(Map<String, Object> o, String key) {
        return asObject(o == null ? null : o.get(key));
    }

    /** 取数组；非数组返回空列表。 */
    public static List<Object> array(Map<String, Object> o, String key) {
        Object v = o == null ? null : o.get(key);
        if (v instanceof List) {
            return asArrayList(v);
        }
        return new ArrayList<>();
    }

    /** 把任意值当成 list 读取。 */
    public static List<Object> asArray(Object v) {
        if (v instanceof List) {
            return asArrayList(v);
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asArrayList(Object v) {
        return (List<Object>) v;
    }

    /** 取字符串；缺失/null 返回 ""。 */
    public static String str(Map<String, Object> o, String key) {
        return asString(o == null ? null : o.get(key));
    }

    /** 任意值转字符串；null -> ""。 */
    public static String asString(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        return String.valueOf(v);
    }

    /** 取数值；缺失/null/非数字返回 0（永不返回 NaN）。 */
    public static double num(Map<String, Object> o, String key) {
        double d = asDouble(o == null ? null : o.get(key));
        return Double.isNaN(d) ? 0.0 : d;
    }

    /** 取数值，缺失时用默认值。 */
    public static double num(Map<String, Object> o, String key, double def) {
        Object v = o == null ? null : o.get(key);
        if (v == null) {
            return def;
        }
        double d = asDouble(v);
        return Double.isNaN(d) ? def : d;
    }

    /** 任意值转 double；失败返回 {@link Double#NaN}。 */
    public static double asDouble(Object v) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue() ? 1.0 : 0.0;
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble((String) v);
            } catch (NumberFormatException ex) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    /** 取整数（四舍五入）；缺失返回 0。 */
    public static long lng(Map<String, Object> o, String key) {
        return Math.round(num(o, key));
    }

    /** 取整数（四舍五入），缺失用默认值。 */
    public static long lng(Map<String, Object> o, String key, long def) {
        Object v = o == null ? null : o.get(key);
        if (v == null) {
            return def;
        }
        double d = asDouble(v);
        return Double.isNaN(d) ? def : Math.round(d);
    }

    /** 取布尔；缺失/null 返回 false。 */
    public static boolean bool(Map<String, Object> o, String key) {
        Object v = o == null ? null : o.get(key);
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue() != 0.0;
        }
        if (v instanceof String) {
            String s = (String) v;
            return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
        }
        return false;
    }

    /** 取布尔，缺失用默认值。 */
    public static boolean bool(Map<String, Object> o, String key, boolean def) {
        if (o == null || !o.containsKey(key) || o.get(key) == null) {
            return def;
        }
        return bool(o, key);
    }

    /** 协议时间 {@code {"date":"2024-03-05","slot":3}} -> "2024-03-05 3/4"；缺失返回 "-"。 */
    public static String timeText(Map<String, Object> time) {
        if (time == null || time.isEmpty()) {
            return "-";
        }
        String date = str(time, "date");
        if (date.isEmpty()) {
            return "-";
        }
        return date + "  " + (lng(time, "slot") + 1) + "/4";
    }

    /** 从对象里取协议时间对象。 */
    public static Map<String, Object> timeOf(Map<String, Object> o) {
        return object(o, "time");
    }

    /** 深拷贝（仅 Map/List 递归）。 */
    public static Object deepCopy(Object v) {
        if (v instanceof Map) {
            Map<String, Object> src = asRawMap(v);
            Map<String, Object> dst = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : src.entrySet()) {
                dst.put(e.getKey(), deepCopy(e.getValue()));
            }
            return dst;
        }
        if (v instanceof List) {
            List<Object> dst = new ArrayList<>();
            for (Object o : asArrayList(v)) {
                dst.add(deepCopy(o));
            }
            return dst;
        }
        return v;
    }
}
