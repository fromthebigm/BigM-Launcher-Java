package net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal DNS-over-HTTPS resolver: Cloudflare (1.1.1.1) first, Google
 * (8.8.8.8) second, both contacted by resolver IP so no bootstrap system DNS
 * lookup is needed (a poisoned resolver would otherwise answer the DoH
 * bootstrap itself). JSON flavour (application/dns-json); only A records are
 * collected — BigM needs a routable IPv4 for the direct-IP tiers.
 *
 * <p>Deliberately dependency-free: the whole point of this port is that the
 * launcher network never consults macOS system proxy settings and never
 * inherits the system trust store's locally-added CAs. java.net.http's
 * default ProxySelector returns no proxies unless -Djava.net.useSystemProxies
 * is set, which this launcher never sets.</p>
 */
public final class DoH {

    private static final String[] ENDPOINTS = {
        "https://1.1.1.1/dns-query",
        "https://1.0.0.1/dns-query",
        "https://8.8.8.8/dns-query",
        "https://8.8.4.4/dns-query",
    };

    private static final Duration TIMEOUT = Duration.ofSeconds(6);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** Resolves {@code host} to IPv4 literals, empty when every DoH endpoint
     *  failed (caller then falls back to the system resolver or fails). */
    public static List<String> resolveIPv4(String host) {
        List<String> merged = new ArrayList<>();
        for (String endpoint : ENDPOINTS) {
            try {
                List<String> answers = query(endpoint, host);
                merged.addAll(answers);
                if (!merged.isEmpty()) return merged;
            } catch (Exception ignored) {
                // Next endpoint; DoH failure must never break the caller.
            }
        }
        return merged;
    }

    private static List<String> query(String endpoint, String host) throws IOException, InterruptedException {
        URI uri = URI.create(endpoint + "?name=" + host + "&type=A");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Accept", "application/dns-json")
                .GET()
                .build();
        HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("DoH " + endpoint + " answered HTTP " + response.statusCode());
        }
        return parseAnswers(response.body());
    }

    /** Extracts type-A answer addresses from an application/dns-json body
     *  without a JSON library — the answers we care about are flat
     *  {"type":1,"data":"a.b.c.d"} objects inside the Answer array. */
    static List<String> parseAnswers(byte[] body) {
        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        int searchFrom = findKey(text, "Answer");
        if (searchFrom < 0) return out;
        int cursor = searchFrom;
        while (true) {
            int typeKey = findKey(text, "type", cursor);
            int dataKey = findKey(text, "data", cursor);
            if (typeKey < 0 || dataKey < 0) return out;
            // pair them within the same object: either order is legal JSON.
            int typeValue = readIntValue(text, typeKey);
            String dataValue = readStringValue(text, dataKey);
            if (typeValue == 1 && dataValue != null && isIPv4Literal(dataValue)) {
                out.add(dataValue);
            }
            cursor = Math.max(typeKey, dataKey) + 1;
        }
    }

    private static int findKey(String text, String key) { return findKey(text, key, 0); }

    private static int findKey(String text, String key, int from) {
        String needle = "\"" + key + "\"";
        return text.indexOf(needle, from);
    }

    private static int readIntValue(String text, int keyIndex) {
        int colon = text.indexOf(':', keyIndex);
        if (colon < 0) return -1;
        int cursor = colon + 1;
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++;
        int end = cursor;
        while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '-')) end++;
        if (end == cursor) return -1;
        try {
            return Integer.parseInt(text.substring(cursor, end));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String readStringValue(String text, int keyIndex) {
        int colon = text.indexOf(':', keyIndex);
        if (colon < 0) return null;
        int cursor = colon + 1;
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++;
        if (cursor >= text.length() || text.charAt(cursor) != '"') return null;
        int end = text.indexOf('"', cursor + 1);
        if (end < 0) return null;
        return text.substring(cursor + 1, end);
    }

    static boolean isIPv4Literal(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) return false;
            }
            int n = Integer.parseInt(part);
            if (n > 255) return false;
        }
        return true;
    }

    private DoH() {}
}
