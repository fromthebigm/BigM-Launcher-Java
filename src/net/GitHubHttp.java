package net;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GitHub HTTP transport for the Java launcher, in two tiers.
 *
 * <p><b>Tier A</b> — {@link java.net.http.HttpClient} with an explicitly
 * empty {@link java.net.ProxySelector}: Java's reachable-from-everywhere
 * property for this machine is that it does NOT inherit macOS System
 * Settings proxies (a system-configured filter proxy is invisible to this
 * process unless we pass {@code -Djava.net.useSystemProxies=true}, which we
 * never do). The bundled JDK's own cacerts truststore also ignores
 * locally-installed filter CAs — the two properties that make Rigel immune
 * to this Mac's filter.</p>
 *
 * <p><b>Tier B</b> — the hard case: DoH-resolve the host (fixed resolver
 * IPs, no bootstrap lookup), open a raw {@code SSLSocket} to the answer,
 * set TLS SNI to the real hostname, validate the chain against the bundled
 * GitHub anchors ONLY, then speak minimal HTTP/1.1 by hand (content-length
 * or chunked). A keychain-trusted MITM fails the anchor check; a DNS
 * sinkhole fails the DoH.</p>
 *
 * <p>Escalation trigger matches the Swift ladder: a transport failure
 * advances to the next tier; an answered-with-non-JSON body (a middlebox's
 * block page) advances too. Authoritative GitHub JSON answers (4xx
 * included) stop the ladder.</p>
 */
public final class GitHubHttp {

    public interface Progress { void progress(long done, long total); }
    public interface Cancelled { boolean cancelled(); }

    /** Status + headers + a small in-memory body. Used for JSON metadata. */
    public record Answer(int status, Map<String, List<String>> headers, byte[] body) {
        public String first(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values != null && !values.isEmpty() ? values.get(0) : null;
        }
    }

    /** Fetch a small body as bytes with the tier ladder. */
    public static byte[] fetch(String url, String token) throws IOException, InterruptedException {
        Answer answer = exchange(url, token, Duration.ofSeconds(20));
        return answer.body();
    }

    public static Answer exchange(String url, String token, Duration timeout)
            throws IOException, InterruptedException {
        // Tier A: stock HttpClient, direct connections only.
        try {
            Answer viaHttpClient = httpClientGet(url, token, timeout);
            if (isAcceptable(viaHttpClient.body(), viaHttpClient.status())) {
                TransportLog.log("fetch " + url + " tier httpclient: HTTP " + viaHttpClient.status()
                        + " (" + viaHttpClient.body().length + " bytes)");
                return viaHttpClient;
            }
            TransportLog.log("fetch " + url + " tier httpclient: intercepted-looking answer ("
                    + viaHttpClient.status() + ", " + viaHttpClient.body().length + " bytes)");
        } catch (IOException | InterruptedException | RuntimeException failure) {
            TransportLog.log("fetch " + url + " tier httpclient: transport failure: "
                    + failure.getMessage());
        }
        // Tier B: DoH + raw pinned TLS.
        TransportLog.log("fetch " + url + ": escalating to doh+pinned tier");
        return pinnedGet(url, token, timeout);
    }

    /** Stream a large body to a temp file, tier A then tier B. */
    public static void downloadToFile(String url, Path destination, String token,
                                      Progress progress, Cancelled cancelled)
            throws IOException, InterruptedException {
        try {
            httpClientDownload(url, destination, token, progress, cancelled);
            TransportLog.log("download " + url + " tier httpclient: complete ("
                    + Files.size(destination) + " bytes)");
            return;
        } catch (IOException | InterruptedException | RuntimeException failure) {
            TransportLog.log("download " + url + " tier httpclient: " + failure.getMessage());
            Files.deleteIfExists(destination);
        }
        TransportLog.log("download " + url + ": escalating to doh+pinned tier");
        pinnedDownload(url, destination, token, progress, cancelled);
        TransportLog.log("download " + url + " tier pinned: complete ("
                + Files.size(destination) + " bytes)");
    }

    // ── Tier A ─────────────────────────────────────────────────────────────

    private static final java.net.ProxySelector DIRECT = new java.net.ProxySelector() {
        @Override public List<java.net.Proxy> select(URI uri) { return List.of(); }
        @Override public void connectFailed(URI uri, java.net.SocketAddress sa, IOException ioe) {}
    };

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .proxy(DIRECT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private static Answer httpClientGet(String url, String token, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", "bigm-java")
                .GET().build();
        HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new Answer(response.statusCode(), response.headers().map(), response.body());
    }

    /** Direct probe without redirect-following, asking for octet-stream. */
    private static Answer httpClientProbe(String url, String token, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/octet-stream")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", "bigm-java")
                .GET().build();
        // Probe semantics: following the redirect would hand the token (or
        // the substituted page) to whatever answers the Location; the probe
        // exists to READ the Location, never to visit it from here.
        HttpClient noFollow = HttpClient.newBuilder()
                .proxy(DIRECT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build();
        HttpResponse<byte[]> response = noFollow.send(
                request, HttpResponse.BodyHandlers.ofByteArray());
        return new Answer(response.statusCode(), response.headers().map(), response.body());
    }

    private static void httpClientDownload(String url, Path destination, String token,
                                           Progress progress, Cancelled cancelled)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("User-Agent", "bigm-java")
                .GET().build();
        HttpResponse<InputStream> response = CLIENT.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        try (InputStream body = response.body();
             OutputStream out = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            long done = 0;
            for (int read; (read = body.read(buffer)) >= 0; ) {
                out.write(buffer, 0, read);
                done += read;
                if (progress != null) progress.progress(done, total);
                if (cancelled != null && cancelled.cancelled()) {
                    throw new IOException("cancelled");
                }
            }
        }
    }

    // ── Tier B ─────────────────────────────────────────────────────────────

    /** The asset redirect probe: must NOT follow the redirect (the Bearer
     *  token stays off the CDN), and must ask for octet-stream — the only
     *  Accept value GitHub answers 302+Location for. */
    public static Answer redirectProbe(String url, String token, Duration timeout)
            throws IOException, InterruptedException {
        try {
            Answer viaHttpClient = httpClientProbe(url, token, timeout);
            if (viaHttpClient.status() >= 300 && viaHttpClient.status() < 400) {
                TransportLog.log("redirect probe " + url + " tier httpclient: HTTP "
                        + viaHttpClient.status());
                return viaHttpClient;
            }
            // 200-with-JSON is a definitive GitHub answer (auth/404 errors);
            // anything else is middlebox noise and escalates.
            if (JsonTiny.isObject(viaHttpClient.body())) {
                TransportLog.log("redirect probe " + url + " tier httpclient: JSON answer ("
                        + viaHttpClient.status() + ")");
                return viaHttpClient;
            }
            TransportLog.log("redirect probe " + url + " tier httpclient: intercepted-looking ("
                    + viaHttpClient.status() + ")");
        } catch (IOException | InterruptedException | RuntimeException failure) {
            TransportLog.log("redirect probe " + url + " tier httpclient: " + failure.getMessage());
        }
        TransportLog.log("redirect probe " + url + ": escalating to doh+pinned tier");
        return rawProbe(url, token, timeout);
    }

    /** Authoritative-answer check: GitHub always returns a JSON object for
     *  these endpoints; only a middlebox's block page is non-JSON. */
    static boolean isAcceptable(byte[] body, int status) {
        if (body == null || body.length == 0) return status < 400; // empty 3xx body is fine
        return JsonTiny.isObject(body);
    }

    private static Answer pinnedGet(String url, String token, Duration timeout)
            throws IOException, InterruptedException {
        URI uri = URI.create(url);
        String host = uri.getHost();
        List<String> ips = DoH.resolveIPv4(host);
        if (ips.isEmpty()) {
            throw new IOException("DoH could not resolve " + host);
        }
        IOException last = null;
        for (String ip : ips) {
            try {
                return rawGet(uri, host, ip, token, timeout);
            } catch (IOException failure) {
                last = failure;
            }
        }
        throw last != null ? last : new IOException("pinned exchange failed for " + host);
    }

    private static void pinnedDownload(String url, Path destination, String token,
                                       Progress progress, Cancelled cancelled)
            throws IOException, InterruptedException {
        URI uri = URI.create(url);
        String host = uri.getHost();
        List<String> ips = DoH.resolveIPv4(host);
        if (ips.isEmpty()) {
            throw new IOException("DoH could not resolve " + host);
        }
        IOException last = null;
        for (String ip : ips) {
            try {
                rawDownload(uri, host, ip, token, destination, progress, cancelled);
                TransportLog.log("pinned download " + host + " <- " + ip + ": complete ("
                        + Files.size(destination) + " bytes)");
                return;
            } catch (IOException failure) {
                last = failure;
            }
        }
        throw last != null ? last : new IOException("pinned download failed for " + host);
    }

    /** Raw HTTPS GET to a DoH-resolved IP with SNI and anchor-pinned trust. */
    private static Answer rawGet(URI uri, String host, String ip, String token, Duration timeout)
            throws IOException, InterruptedException {
        try (RawExchange exchange = RawExchange.open(host, ip, timeout)) {
            String target = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            exchange.writeRequest("GET", target, host, token,
                    "application/vnd.github+json", null);
            RawExchange.Response response = exchange.readResponse();
            return new Answer(response.status(), response.headers(), response.body());
        }
    }

    /** Raw HTTPS download: whole body into the destination file with
     *  per-chunk progress. No resume across tiers — a tier's partial is
     *  treated as suspect content and always rewritten. (Resume matters only
     *  for mid-transfer drops; the ladder covers those with a fresh try.) */
    private static void rawDownload(URI uri, String host, String ip, String token,
                                    Path destination, Progress progress, Cancelled cancelled)
            throws IOException, InterruptedException {
        String target = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        try (RawExchange exchange = RawExchange.open(host, ip, Duration.ofSeconds(30))) {
            exchange.writeRequest("GET", target, host, token, "application/octet-stream", null);
            try (OutputStream out = Files.newOutputStream(destination,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                exchange.readBodyInto(out, progress, cancelled);
            }
        }
    }

    /** Pinned redirect probe: raw exchange with an octet-stream Accept. */
    private static Answer rawProbe(String url, String token, Duration timeout)
            throws IOException, InterruptedException {
        URI uri = URI.create(url);
        String host = uri.getHost();
        List<String> ips = DoH.resolveIPv4(host);
        if (ips.isEmpty()) {
            throw new IOException("DoH could not resolve " + host);
        }
        IOException last = null;
        for (String ip : ips) {
            try {
                try (RawExchange exchange = RawExchange.open(host, ip, timeout)) {
                    String target = uri.getRawPath()
                            + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                    exchange.writeRequest("GET", target, host, token,
                            "application/octet-stream", null);
                    RawExchange.Response response = exchange.readResponse();
                    return new Answer(response.status(), response.headers(), response.body());
                }
            } catch (IOException failure) {
                last = failure;
            }
        }
        throw last != null ? last : new IOException("pinned redirect probe failed for " + host);
    }

    private GitHubHttp() {}
}
