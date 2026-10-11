package net;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One-shot HTTPS exchange over a DoH-resolved IP: raw {@code SSLSocket} with
 * the real SNI, the chain evaluated against the bundled GitHub anchors (the
 * keychain — and any locally-installed filter CA — is never consulted), then
 * minimal HTTP/1.1. Exists because java.net.http has no public "connect to
 * this IP for this hostname" knob, and HttpURLConnection's Host-header
 * override breaks SNI/HTTP2 semantics we need.
 */
final class RawExchange implements AutoCloseable {

    final SSLSocket socket;
    private final InputStream in;
    private final OutputStream out;

    private RawExchange(SSLSocket socket, InputStream in, OutputStream out) {
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    static RawExchange open(String host, String ipLiteral, Duration timeout) throws IOException {
        SSLSocket socket = (SSLSocket) GitHubTrust.pinnedContext()
                .getSocketFactory().createSocket();
        try {
            socket.connect(new InetSocketAddress(ipLiteral, 443),
                    Math.toIntExact(timeout.toMillis()));
            socket.setSoTimeout(Math.toIntExact(timeout.toMillis()));
            socket.setTcpNoDelay(true);
            SSLParameters params = socket.getSSLParameters();
            params.setServerNames(List.of(new SNIHostName(host)));
            socket.setSSLParameters(params);
            socket.startHandshake();
            // Hostname verification against the REAL name (the connection
            // went to an IP literal): check the leaf's SANs cover the host.
            requireHostnameCovers(socket, host);
            return new RawExchange(socket, socket.getInputStream(), socket.getOutputStream());
        } catch (Throwable failure) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw failure instanceof IOException ? (IOException) failure
                    : new IOException("pinned handshake to " + host + " failed", failure);
        }
    }

    /** The presented leaf must name the expected host (dNSName SAN or CN
     *  fallback, wildcard-aware). The anchor-pinned chain check already ran;
     *  this binds the cert to the requested hostname so a valid-for-another
     *  -host chain can never pass. */
    private static void requireHostnameCovers(SSLSocket socket, String host) throws IOException {
        X509Certificate leaf;
        try {
            leaf = (X509Certificate) socket.getSession().getPeerCertificates()[0];
        } catch (Exception failure) {
            throw new IOException("no peer certificate for " + host, failure);
        }
        java.util.Collection<java.util.List<?>> sans;
        try {
            sans = leaf.getSubjectAlternativeNames();
        } catch (CertificateParsingException failure) {
            throw new IOException("unreadable SANs for " + host, failure);
        }
        boolean matches = false;
        if (sans != null) {
            for (java.util.List<?> san : sans) {
                if (san.size() < 2 || !(san.get(1) instanceof String)) continue;
                Integer type = (Integer) san.get(0);
                String name = ((String) san.get(1)).toLowerCase(Locale.ROOT);
                if (type == 2 && hostnameMatches(name, host)) { matches = true; break; }
            }
        }
        if (!matches) {
            // CN fallback for certs without SANs.
            String dn = leaf.getSubjectX500Principal().getName();
            for (String part : dn.split(",")) {
                String trimmed = part.trim();
                if (trimmed.regionMatches(true, 0, "CN=", 0, 3)
                        && hostnameMatches(trimmed.substring(3).toLowerCase(Locale.ROOT), host)) {
                    matches = true;
                    break;
                }
            }
        }
        if (!matches) {
            throw new IOException("presented certificate does not cover " + host);
        }
    }

    /** RFC 6125 wildcard match: "*.github.com" covers "api.github.com" but
     *  not "one.two.github.com" (single leftmost label only). */
    static boolean hostnameMatches(String pattern, String host) {
        pattern = pattern.toLowerCase(Locale.ROOT);
        host = host.toLowerCase(Locale.ROOT);
        if (pattern.equals(host)) return true;
        if (!pattern.startsWith("*.")) return false;
        String suffix = pattern.substring(2);
        return host.endsWith(suffix)
                && host.length() > suffix.length() + 1
                && host.indexOf('.', host.length() - suffix.length() - 1) == host.length() - suffix.length() - 1;
    }

    void writeRequest(String method, String target, String host, String token,
                      String accept, String rangeHeader) throws IOException {
        StringBuilder request = new StringBuilder();
        request.append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(host).append("\r\n");
        request.append("User-Agent: bigm-java\r\n");
        request.append("Accept: ").append(accept).append("\r\n");
        request.append("X-GitHub-Api-Version: 2022-11-28\r\n");
        if (token != null && !token.isEmpty()) {
            request.append("Authorization: Bearer ").append(token).append("\r\n");
        }
        if (rangeHeader != null) request.append("Range: ").append(rangeHeader).append("\r\n");
        request.append("Connection: close\r\n\r\n");
        out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    record Response(int status, Map<String, List<String>> headers, byte[] body) {}

    Response readResponse() throws IOException, InterruptedException {
        String statusLine = readAsciiLine(in);
        int status = parseStatus(statusLine);
        Map<String, List<String>> headers = readHeaders(in);
        ByteArrayOutputStream body = new ByteArrayOutputStream(64 * 1024);
        readBody(body, headers);
        return new Response(status, headers, body.toByteArray());
    }

    /** Reads headers + full body. When {@code sink} is non-null the body
     *  streams to it with progress callbacks; otherwise buffered. */
    long readBodyInto(OutputStream sink, GitHubHttp.Progress progress,
                      GitHubHttp.Cancelled cancelled) throws IOException, InterruptedException {
        String statusLine = readAsciiLine(in);
        int status = parseStatus(statusLine);
        Map<String, List<String>> headers = readHeaders(in);
        if (!isSuccessStatus(status)) {
            // Read/drain the error body so the failure message can name it,
            // then surface as a transport-grade failure for the tier loop.
            ByteArrayOutputStream errorBody = new ByteArrayOutputStream(4096);
            readBody(errorBody, headers);
            throw new IOException("pinned exchange answered HTTP " + status
                    + ": " + abbreviate(errorBody.toString(StandardCharsets.UTF_8), 120));
        }
        long total = contentLength(headers);
        if (isChunked(headers)) {
            long done = 0;
            while (true) {
                long chunkSize = readChunkSize(in);
                if (chunkSize == 0) { readTrailers(in); break; }
                done += copyExact(in, sink, chunkSize, progress, total, cancelled);
            }
            return done;
        }
        if (total >= 0) {
            long done = copyExact(in, sink, total, progress, total, cancelled);
            drainUntilClose(in);
            return done;
        }
        return copyUntilClose(in, sink, progress, cancelled);
    }

    private void readBody(ByteArrayOutputStream body, Map<String, List<String>> headers)
            throws IOException, InterruptedException {
        if (isChunked(headers)) {
            while (true) {
                long chunkSize = readChunkSize(in);
                if (chunkSize == 0) { readTrailers(in); break; }
                copyExact(in, body, chunkSize, null, -1, null);
            }
            return;
        }
        long total = contentLength(headers);
        if (total >= 0) {
            copyExact(in, body, total, null, total, null);
            return;
        }
        copyUntilClose(in, body, null, null);
    }

    private static boolean isSuccessStatus(int status) {
        return status >= 200 && status < 400;
    }

    private static String abbreviate(String text, int max) {
        String clean = text.replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }

    private static int parseStatus(String statusLine) throws IOException {
        // "HTTP/1.1 200 OK"
        int firstSpace = statusLine.indexOf(' ');
        if (firstSpace < 0) throw new IOException("bad status line: " + statusLine);
        int secondSpace = statusLine.indexOf(' ', firstSpace + 1);
        String code = secondSpace < 0
                ? statusLine.substring(firstSpace + 1)
                : statusLine.substring(firstSpace + 1, secondSpace);
        try {
            return Integer.parseInt(code.trim());
        } catch (NumberFormatException failure) {
            throw new IOException("bad status code in: " + statusLine);
        }
    }

    private static Map<String, List<String>> readHeaders(InputStream in) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String line;
        while (!(line = readAsciiLine(in)).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(value);
        }
        return headers;
    }

    private static long contentLength(Map<String, List<String>> headers) {
        List<String> values = headers.get("content-length");
        if (values == null || values.isEmpty()) return -1;
        try {
            return Long.parseLong(values.get(0).trim());
        } catch (NumberFormatException failure) {
            return -1;
        }
    }

    private static boolean isChunked(Map<String, List<String>> headers) {
        List<String> values = headers.get("transfer-encoding");
        return values != null && values.stream()
                .anyMatch(v -> v.toLowerCase(Locale.ROOT).contains("chunked"));
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        int previous = -1;
        while (true) {
            int value = in.read();
            if (value < 0) throw new EOFException("stream ended mid-header");
            if (value == '\n') {
                if (previous == '\r') {
                    byte[] bytes = line.toByteArray();
                    return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
                }
                return line.toString(StandardCharsets.ISO_8859_1).stripTrailing();
            }
            line.write(value);
            previous = value;
        }
    }

    private static long readChunkSize(InputStream in) throws IOException {
        String line = readAsciiLine(in);
        int semicolon = line.indexOf(';');
        if (semicolon >= 0) line = line.substring(0, semicolon);
        try {
            return Long.parseLong(line.trim(), 16);
        } catch (NumberFormatException failure) {
            throw new IOException("bad chunk size: " + line);
        }
    }

    private static void readTrailers(InputStream in) throws IOException {
        String line;
        while (!(line = readAsciiLine(in)).isEmpty()) { /* discard */ }
    }

    private static long copyExact(InputStream in, OutputStream sink, long count,
                                  GitHubHttp.Progress progress, long total,
                                  GitHubHttp.Cancelled cancelled)
            throws IOException, InterruptedException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = count;
        long doneTotal = 0;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new EOFException("stream closed early");
            if (sink != null) sink.write(buffer, 0, read);
            doneTotal += read;
            remaining -= read;
            if (progress != null) progress.progress(doneTotal, total);
            if (cancelled != null && cancelled.cancelled()) throw new IOException("cancelled");
        }
        return doneTotal;
    }

    private static void drainUntilClose(InputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        while (in.read(buffer) >= 0) { }
    }

    private static long copyUntilClose(InputStream in, OutputStream sink,
                                       GitHubHttp.Progress progress,
                                       GitHubHttp.Cancelled cancelled)
            throws IOException, InterruptedException {
        byte[] buffer = new byte[64 * 1024];
        long doneTotal = 0;
        for (int read; (read = in.read(buffer)) >= 0; ) {
            if (sink != null) sink.write(buffer, 0, read);
            doneTotal += read;
            if (progress != null) progress.progress(doneTotal, -1);
            if (cancelled != null && cancelled.cancelled()) throw new IOException("cancelled");
        }
        return doneTotal;
    }

    @Override public void close() throws IOException {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
