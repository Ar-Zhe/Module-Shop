package cn.autoforged.server_shop_mod_1789689358.shop.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析/输出 —— **不引第三方依赖**。
 *
 * <p>只支持 HTTP API 需要的那点语法：对象、数组、字符串、数字、true/false/null。
 * 不支持注释、不校验转义嵌套深度（请求体已限制大小）。
 *
 * <p>解析结果类型：{@code Map<String,Object>} / {@code List<Object>} / {@code String} /
 * {@code Long} 或 {@code Double} / {@code Boolean} / {@code null}。
 */
public final class MiniJson {
    private final String text;
    private int pos;

    private MiniJson(String text) {
        this.text = text;
    }

    public static Object parse(String json) {
        MiniJson parser = new MiniJson(json == null ? "" : json);
        parser.skip();
        Object value = parser.value();
        parser.skip();
        return value;
    }

    // ---------------------------------------------------------------- 解析

    private void skip() {
        while (this.pos < this.text.length() && Character.isWhitespace(this.text.charAt(this.pos))) {
            this.pos++;
        }
    }

    private char peek() {
        if (this.pos >= this.text.length()) {
            throw new IllegalArgumentException("JSON 意外结束");
        }
        return this.text.charAt(this.pos);
    }

    private Object value() {
        skip();
        char c = peek();
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Object literal(String word, Object result) {
        if (!this.text.startsWith(word, this.pos)) {
            throw new IllegalArgumentException("非法字面量 @" + this.pos);
        }
        this.pos += word.length();
        return result;
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        this.pos++;                 // {
        skip();
        if (peek() == '}') {
            this.pos++;
            return map;
        }
        while (true) {
            skip();
            String key = string();
            skip();
            if (peek() != ':') {
                throw new IllegalArgumentException("缺少 ':' @" + this.pos);
            }
            this.pos++;
            map.put(key, value());
            skip();
            char c = peek();
            if (c == ',') {
                this.pos++;
                continue;
            }
            if (c == '}') {
                this.pos++;
                return map;
            }
            throw new IllegalArgumentException("对象缺少 , 或 } @" + this.pos);
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        this.pos++;                 // [
        skip();
        if (peek() == ']') {
            this.pos++;
            return list;
        }
        while (true) {
            list.add(value());
            skip();
            char c = peek();
            if (c == ',') {
                this.pos++;
                continue;
            }
            if (c == ']') {
                this.pos++;
                return list;
            }
            throw new IllegalArgumentException("数组缺少 , 或 ] @" + this.pos);
        }
    }

    private String string() {
        if (peek() != '"') {
            throw new IllegalArgumentException("缺少字符串 @" + this.pos);
        }
        this.pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = peek();
            this.pos++;
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char esc = peek();
            this.pos++;
            switch (esc) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    String hex = this.text.substring(this.pos, this.pos + 4);
                    this.pos += 4;
                    sb.append((char) Integer.parseInt(hex, 16));
                }
                default -> throw new IllegalArgumentException("非法转义 \\" + esc);
            }
        }
    }

    private Object number() {
        int start = this.pos;
        while (this.pos < this.text.length()) {
            char c = this.text.charAt(this.pos);
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || Character.isDigit(c)) {
                this.pos++;
            } else {
                break;
            }
        }
        String raw = this.text.substring(start, this.pos);
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("缺少数字 @" + start);
        }
        if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
            return Double.parseDouble(raw);
        }
        return Long.parseLong(raw);
    }

    // ---------------------------------------------------------------- 输出

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                write(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(sb, list.get(i));
            }
            sb.append(']');
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            write(sb, String.valueOf(value));
        }
    }

    // ---------------------------------------------------------------- 便捷读取

    /** 从解析结果里安全取字符串。 */
    public static String str(Object node, String key, String fallback) {
        if (node instanceof Map<?, ?> map) {
            Object value = map.get(key);
            return value == null ? fallback : String.valueOf(value);
        }
        return fallback;
    }

    /** 从解析结果里安全取 long。 */
    public static long num(Object node, String key, long fallback) {
        if (node instanceof Map<?, ?> map) {
            Object value = map.get(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof String s) {
                try {
                    return Long.parseLong(s.trim());
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    /** 从解析结果里安全取列表。 */
    public static List<Object> list(Object node, String key) {
        if (node instanceof Map<?, ?> map) {
            Object value = map.get(key);
            if (value instanceof List<?> items) {
                return new ArrayList<>(items);
            }
        }
        return new ArrayList<>();
    }
}