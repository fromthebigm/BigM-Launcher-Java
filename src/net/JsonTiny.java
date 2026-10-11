package net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader for the GitHub releases payload: objects, arrays,
 * strings (with escapes), numbers, booleans, null. Exists because the JDK
 * has no built-in JSON and this project stays dependency-free beyond SWT +
 * JNA. Enough for release metadata; not a general-purpose parser.
 */
public final class JsonTiny {

    /** Lossy check: does this look like a JSON object body? */
    public static boolean isObject(byte[] body) {
        if (body == null || body.length == 0) return false;
        for (byte b : body) {
            if (b == ' ' || b == '\t' || b == '\r' || b == '\n') continue;
            return b == '{';
        }
        return false;
    }

    public static Object parse(byte[] body) {
        return new Parser(new String(body, java.nio.charset.StandardCharsets.UTF_8)).parseValue();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(byte[] body) {
        Object value = parse(body);
        if (value instanceof Map) return (Map<String, Object>) value;
        throw new IllegalArgumentException("not a JSON object");
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value, String key) {
        Object field = value instanceof Map ? ((Map<String, Object>) value).get(key) : null;
        return field instanceof List ? (List<Object>) field : List.of();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    public static String str(Object value, String key) {
        Object field = value instanceof Map ? ((Map<String, Object>) value).get(key) : null;
        return field instanceof String ? (String) field : null;
    }

    public static long num(Object value, String key, long defaultValue) {
        Object field = value instanceof Map ? ((Map<String, Object>) value).get(key) : null;
        return field instanceof Number ? ((Number) field).longValue() : defaultValue;
    }

    private JsonTiny() {}

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) { this.text = text; }

        Object parseValue() {
            skipWhitespace();
            if (pos >= text.length()) throw error("unexpected end");
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> parseObjectInternal();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> { expect("true"); yield Boolean.TRUE; }
                case 'f' -> { expect("false"); yield Boolean.FALSE; }
                case 'n' -> { expect("null"); yield null; }
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObjectInternal() {
            expect('{');
            Map<String, Object> out = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) { pos++; return out; }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                out.put(key, value);
                skipWhitespace();
                if (peek('}')) { pos++; return out; }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> out = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) { pos++; return out; }
            while (true) {
                out.add(parseValue());
                skipWhitespace();
                if (peek(']')) { pos++; return out; }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') return out.toString();
                if (c == '\\' && pos < text.length()) {
                    char esc = text.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> out.append(esc);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (pos + 4 > text.length()) throw error("bad \\u escape");
                            out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw error("bad escape \\" + esc);
                    }
                } else {
                    out.append(c);
                }
            }
            throw error("unterminated string");
        }

        private Object parseNumber() {
            int start = pos;
            while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
            if (start == pos) throw error("expected a value");
            String raw = text.substring(start, pos);
            try {
                if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) {
                    return Double.parseDouble(raw);
                }
                return Long.parseLong(raw);
            } catch (NumberFormatException failure) {
                throw error("bad number: " + raw);
            }
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
        }

        private boolean peek(char expected) {
            return pos < text.length() && text.charAt(pos) == expected;
        }

        private void expect(char expected) {
            if (!peek(expected)) throw error("expected '" + expected + "'");
            pos++;
        }

        private void expect(String word) {
            if (!text.regionMatches(pos, word, 0, word.length())) throw error("expected " + word);
            pos += word.length();
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at offset " + pos);
        }
    }
}
