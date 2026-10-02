package tsim.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 递归下降 JSON 解析器（UTF-8 文本，支持 Unicode 反斜杠-u 转义，含代理对）。
 *
 * <p>解析结果类型：对象 -> {@link LinkedHashMap}，数组 -> {@link ArrayList}，
 * 字符串 -> {@link String}，整数 -> {@link Long}，小数 -> {@link Double}，
 * 布尔 -> {@link Boolean}，null -> {@code null}。</p>
 *
 * <p>本类不是线程安全的；每次解析新建一个实例。</p>
 */
public final class JsonParser {

    private final String src;
    private int pos;

    /** 用待解析文本构造解析器。 */
    public JsonParser(String text) {
        this.src = text == null ? "" : text;
        this.pos = 0;
    }

    /**
     * 解析一个完整的 JSON 文档（允许首尾空白）。
     *
     * @throws JsonException 语法错误或尾部有多余字符
     */
    public Object parseDocument() {
        skipWs();
        if (pos >= src.length()) {
            throw new JsonException("空输入，不是合法 JSON", pos, src);
        }
        Object v = parseValue();
        skipWs();
        if (pos < src.length()) {
            throw new JsonException("JSON 尾部存在多余字符 '" + src.charAt(pos) + "'", pos, src);
        }
        return v;
    }

    private Object parseValue() {
        skipWs();
        if (pos >= src.length()) {
            throw new JsonException("期待一个 JSON 值，但已到输入末尾", pos, src);
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return parseObjectBody();
            case '[':
                return parseArrayBody();
            case '"':
                return parseString();
            case 't':
                expectLiteral("true");
                return Boolean.TRUE;
            case 'f':
                expectLiteral("false");
                return Boolean.FALSE;
            case 'n':
                expectLiteral("null");
                return null;
            default:
                if (c == '-' || c == '+' || (c >= '0' && c <= '9') || c == '.') {
                    return parseNumber();
                }
                throw new JsonException("非法字符 '" + c + "'", pos, src);
        }
    }

    private Map<String, Object> parseObjectBody() {
        Map<String, Object> m = new LinkedHashMap<>();
        pos++; // '{'
        skipWs();
        if (peek() == '}') {
            pos++;
            return m;
        }
        while (true) {
            skipWs();
            if (peek() != '"') {
                throw new JsonException("对象的键必须是字符串", pos, src);
            }
            String key = parseString();
            skipWs();
            if (peek() != ':') {
                throw new JsonException("键 '" + key + "' 后缺少 ':'", pos, src);
            }
            pos++;
            Object val = parseValue();
            m.put(key, val);
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return m;
            }
            throw new JsonException("对象内期待 ',' 或 '}'，实际是 '" + c + "'", pos, src);
        }
    }

    private List<Object> parseArrayBody() {
        List<Object> l = new ArrayList<>();
        pos++; // '['
        skipWs();
        if (peek() == ']') {
            pos++;
            return l;
        }
        while (true) {
            Object val = parseValue();
            l.add(val);
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return l;
            }
            throw new JsonException("数组内期待 ',' 或 ']'，实际是 '" + c + "'", pos, src);
        }
    }

    private String parseString() {
        pos++; // 开头引号
        StringBuilder sb = new StringBuilder(32);
        while (true) {
            if (pos >= src.length()) {
                throw new JsonException("字符串没有闭合的引号", pos, src);
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                if (c < 0x20) {
                    throw new JsonException("字符串中存在未转义的控制字符 0x" + Integer.toHexString(c), pos - 1, src);
                }
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw new JsonException("转义符后缺少内容", pos, src);
            }
            char e = src.charAt(pos++);
            switch (e) {
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '/':
                    sb.append('/');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    sb.append(parseHex4());
                    break;
                default:
                    throw new JsonException("未知转义 '\\" + e + "'", pos - 1, src);
            }
        }
    }

    private char parseHex4() {
        if (pos + 4 > src.length()) {
            throw new JsonException("\\u 转义不足 4 位十六进制", pos, src);
        }
        int v = 0;
        for (int i = 0; i < 4; i++) {
            int d = Character.digit(src.charAt(pos + i), 16);
            if (d < 0) {
                throw new JsonException("\\u 转义含非法十六进制字符 '" + src.charAt(pos + i) + "'", pos + i, src);
            }
            v = (v << 4) | d;
        }
        pos += 4;
        if (v < 0) {
            throw new JsonException("\\u 转义越界", pos - 4, src);
        }
        return (char) v;
    }

    private Object parseNumber() {
        int start = pos;
        if (peek() == '+' || peek() == '-') {
            pos++;
        }
        boolean isDouble = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                if (c == '.' || c == 'e' || c == 'E') {
                    isDouble = true;
                }
                pos++;
            } else {
                break;
            }
        }
        String tok = src.substring(start, pos);
        if (tok.isEmpty() || "-".equals(tok) || "+".equals(tok)) {
            throw new JsonException("非法数字 '" + tok + "'", start, src);
        }
        if (!isDouble) {
            try {
                return Long.valueOf(Long.parseLong(tok));
            } catch (NumberFormatException ex) {
                // 超出 long 范围时退化为 double
                isDouble = true;
            }
        }
        try {
            return Double.valueOf(Double.parseDouble(tok));
        } catch (NumberFormatException ex) {
            throw new JsonException("非法数字 '" + tok + "'", start, src);
        }
    }

    private void expectLiteral(String lit) {
        if (!src.startsWith(lit, pos)) {
            throw new JsonException("期待字面量 '" + lit + "'", pos, src);
        }
        pos += lit.length();
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new JsonException("输入意外结束", pos, src);
        }
        return src.charAt(pos);
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF') {
                pos++;
            } else {
                break;
            }
        }
    }

    /** JSON 语法错误；{@code message} 为中文，可直接显示。 */
    public static class JsonException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int offset;

        JsonException(String msg, int offset, String src) {
            super(msg + "（位置 " + offset + "）" + context(src, offset));
            this.offset = offset;
        }

        /** 出错字符在原文中的下标。 */
        public int offset() {
            return offset;
        }

        private static String context(String src, int offset) {
            if (src == null || src.isEmpty()) {
                return "";
            }
            int from = Math.max(0, offset - 30);
            int to = Math.min(src.length(), offset + 30);
            String frag = src.substring(from, to).replace('\n', ' ').replace('\r', ' ');
            return "  片段: " + (from > 0 ? "..." : "") + frag + (to < src.length() ? "..." : "");
        }
    }
}
