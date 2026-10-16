package core;

/**
 * Minimal JSON writer for the Java→SwiftUI state bridge. We need exactly
 * what JsonTiny's parser doesn't do: faithful UTF-8 escaping of arbitrary
 * display strings. Deterministic key order comes from the callers.
 */
public final class JsonOut {

    private final StringBuilder out = new StringBuilder();

    public static JsonOut begin() { return new JsonOut().open(); }

    private JsonOut open() { out.append('{'); return this; }

    public JsonOut end() {
        if (out.length() > 0 && out.charAt(out.length() - 1) == ',') {
            out.setLength(out.length() - 1);
        }
        out.append('}');
        return this;
    }

    public JsonOut key(String name) {
        string(name);
        out.append(':');
        return this;
    }

    public JsonOut field(String name, String value) {
        return appendComma().key(name).string(value);
    }

    /** Nullable string: emits null when value is null. */
    public JsonOut fieldOpt(String name, String value) {
        appendComma().key(name);
        if (value == null) out.append("null"); else string(value);
        return this;
    }

    public JsonOut field(String name, boolean value) {
        return appendComma().key(name).raw(value ? "true" : "false");
    }

    public JsonOut field(String name, long value) {
        return appendComma().key(name).raw(Long.toString(value));
    }

    public JsonOut field(String name, double value) {
        return appendComma().key(name).raw(Double.toString(value));
    }

    /** Nullable double (progress fractions): emits null when null. */
    public JsonOut fieldOpt(String name, Double value) {
        appendComma().key(name);
        out.append(value == null ? "null" : Double.toString(value));
        return this;
    }

    public JsonOut object(String name) {
        appendComma().key(name);
        out.append('{');
        return this;
    }

    public JsonOut closeObject() {
        trimComma();
        out.append('}');
        return this;
    }

    public JsonOut array(String name) {
        appendComma().key(name);
        out.append('[');
        return this;
    }

    public JsonOut beginElement() { appendComma(); out.append('{'); return this; }

    /** A bare string element inside an array (["a","b"]). */
    public JsonOut stringElement(String value) {
        appendComma();
        string(value);
        return this;
    }

    public JsonOut closeArray() {
        trimComma();
        out.append(']');
        return this;
    }

    public String finish() {
        trimComma();
        out.append('}');
        return out.toString();
    }

    private JsonOut appendComma() {
        char last = out.length() == 0 ? '{' : out.charAt(out.length() - 1);
        if (last != '{' && last != '[' && last != ',' && last != ':') out.append(',');
        return this;
    }

    private void trimComma() {
        if (out.length() > 0 && out.charAt(out.length() - 1) == ',') {
            out.setLength(out.length() - 1);
        }
    }

    private JsonOut raw(String token) { out.append(token); return this; }

    private JsonOut string(String value) {
        if (value == null) { out.append("null"); return this; }
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
        return this;
    }
}
