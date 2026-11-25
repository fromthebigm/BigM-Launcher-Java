package core;

import net.TransportLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Same resolution order as the Swift build's GitHubAuth:
 *
 *   1. user-saved token        (Support/github-token)
 *   2. owner rotation override (Support/remote-token, refreshed from the
 *      launcher repo's launcher-token.txt whenever the embedded token can
 *      read it)
 *   3. build-time embedded token (EmbeddedToken.java, generated; never in git)
 *   4. GH_TOKEN / GITHUB_TOKEN env
 *   5. an existing `gh` CLI login (hosts.yml + keychain entry)
 *
 * Resolution is cached per process; refreshRemoteOverride() drops the cache
 * so a freshly-written override wins on the next call.
 */
public final class GitHubAccess {

    private static volatile String cached;
    /** In-memory only: a refreshed rotation token is never trusted from disk. */
    private static volatile String remoteOverride;

    public static synchronized String resolve() {
        if (cached != null) return cached.isEmpty() ? null : cached;
        cached = resolveUncached();
        return cached;
    }

    public static synchronized void invalidate() { cached = null; }

    private static String resolveUncached() {
        String stored = TokenStore.get();
        if (stored != null) return stored;
        // Never read Support/remote-token from disk: a network-written file is
        // not trusted. Only an in-memory refresh from a 200 api.github.com
        // response (via an already-trusted token) may set it.
        if (remoteOverride != null && !remoteOverride.isBlank()) return remoteOverride;
        if (!EmbeddedToken.VALUE.isBlank()) return EmbeddedToken.VALUE;
        String env = System.getenv("GH_TOKEN");
        if (env != null && !env.isBlank()) return env.trim();
        env = System.getenv("GITHUB_TOKEN");
        if (env != null && !env.isBlank()) return env.trim();
        return ghCLITokenFromDisk();
    }

    /** Which leg would resolve a token, without exposing the value. */
    public static String resolveSource() {
        if (TokenStore.get() != null) return "stored";
        if (remoteOverride != null && !remoteOverride.isBlank()) return "remote-override";
        if (!EmbeddedToken.VALUE.isBlank()) return "embedded";
        if (System.getenv("GH_TOKEN") != null && !System.getenv("GH_TOKEN").isBlank()) return "env:GH_TOKEN";
        if (System.getenv("GITHUB_TOKEN") != null && !System.getenv("GITHUB_TOKEN").isBlank()) return "env:GITHUB_TOKEN";
        if (ghCLITokenFromDisk() != null) return "gh-cli";
        return "none";
    }

    /** Post-check rescue: another token that can still see the repo. Mirrors
     *  the Swift fallback chain (stored → env → remote-override → embedded →
     *  gh CLI), excluding the one that just failed. */
    public static String[] fallbackTokens(String excluded) {
        java.util.LinkedHashSet<String> chain = new java.util.LinkedHashSet<>();
        String stored = TokenStore.get();
        if (stored != null) chain.add(stored);
        String env = System.getenv("GH_TOKEN");
        if (env == null) env = System.getenv("GITHUB_TOKEN");
        if (env != null && !env.isBlank()) chain.add(env.trim());
        if (remoteOverride != null && !remoteOverride.isBlank()) chain.add(remoteOverride);
        if (!EmbeddedToken.VALUE.isBlank()) chain.add(EmbeddedToken.VALUE);
        String cli = ghCLITokenFromDisk();
        if (cli != null) chain.add(cli);
        chain.remove(excluded);
        return chain.toArray(new String[0]);
    }

    /** Reads the current `gh` login's token: hosts.yml (plain `oauth_token`)
     *  first, then the login keychain's gh:github.com entry (which gh stores
     *  as `go-keyring-base64:<b64>` under the recorded username). */
    static String ghCLITokenFromDisk() {
        String username = null;
        Path hostsFile = Paths.realHome().resolve(".config/gh/hosts.yml");
        try {
            if (Files.isRegularFile(hostsFile)) {
                for (String line : Files.readAllLines(hostsFile, StandardCharsets.UTF_8)) {
                    String[] parts = line.split(":", 2);
                    if (parts.length != 2) continue;
                    String key = parts[0].trim();
                    String value = parts[1].trim();
                    if (key.equals("oauth_token") && !value.isEmpty()) return value;
                    if (key.equals("user") && !value.isEmpty()) username = value;
                }
            }
        } catch (IOException ignored) {
        }
        // Prefer the gh CLI itself with the REAL home, even when hosts.yml has
        // no user line — run.sh redirects HOME to portable-data, so a hosts.yml
        // probe against the redirected HOME would miss the real login.
        String cli = ghCliToken();
        if (cli != null) return cli;
        if (username == null) return null;
        // No dylib: last leg reads the generic password with /usr/bin/security
        // (a system tool, not a shipped native library). An untrusted ACL may
        // still surface a dialog; the embedded token and env/hosts legs above
        // normally make this unnecessary.
        String raw;
        try {
            Process p = new ProcessBuilder("/usr/bin/security", "find-generic-password",
                    "-s", "gh:github.com", "-a", username, "-w")
                    .redirectErrorStream(true).start();
            raw = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor() != 0 || raw.isEmpty()) return null;
        } catch (Exception e) {
            return null;
        }
        // gh stores the secret through go-keyring, which base64-wraps it.
        final String prefix = "go-keyring-base64:";
        if (raw.startsWith(prefix)) {
            try {
                byte[] decoded = Base64.getDecoder()
                        .decode(raw.substring(prefix.length()).trim());
                String token = new String(decoded, StandardCharsets.UTF_8).trim();
                return token.isEmpty() ? null : token;
            } catch (IllegalArgumentException malformed) {
                return null;
            }
        }
        if (!raw.isBlank()) return raw.trim();
        // Final fallback: the gh CLI itself (HOME points at the real machine
        // home before run.sh redirects it). No prompt, no custom dylib.
        try {
            ProcessBuilder pb = new ProcessBuilder("gh", "auth", "token");
            pb.environment().put("HOME", Paths.realHome().toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String token = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor() == 0 && !token.isEmpty() && !token.contains(" ")) return token;
        } catch (Exception ignored) {
        }
        return null;
    }

    /** The owner's rotation token lives in the launcher repo itself, readable
     *  by the embedded token; refreshing pulls any rotation through into
     *  MEMORY ONLY. Never writes the token to disk and never trusts a disk
     *  file; non-200 answers leave the override alone; EMPTY 200 clears it. */
    public static void refreshRemoteOverride(String currentToken) {
        if (currentToken == null || currentToken.isEmpty()) return;
        try {
            // Uses the plain-HTTP tier (tier-1-like): JSON-only matters for
            // release fetches; for a raw file download the same ladder
            // semantics apply but simpler, since the payload is either the
            // token text, empty (clear), or a non-200 (leave alone).
            String endpoint = "https://api.github.com/repos/fromthebigm/BigM-Launcher/contents/launcher-token.txt";
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(endpoint))
                    .header("Accept", "application/vnd.github.raw")
                    .header("Authorization", "Bearer " + currentToken)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .timeout(java.time.Duration.ofSeconds(15))
                    .GET().build();
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .proxy(new java.net.ProxySelector() {
                        @Override public java.util.List<java.net.Proxy> select(java.net.URI uri) { return java.util.List.of(); }
                        @Override public void connectFailed(java.net.URI u, java.net.SocketAddress sa, IOException e) {}
                    })
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                    .connectTimeout(java.time.Duration.ofSeconds(15))
                    .build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) return;
            String text = new String(response.body(), StandardCharsets.UTF_8).trim();
            remoteOverride = text.isEmpty() ? null : text;
            invalidate();
        } catch (Exception ignored) {
            // A failed refresh is not a signal to clear anything.
        }
    }

    private static String ghCliToken() {
        try {
            ProcessBuilder pb = new ProcessBuilder("gh", "auth", "token");
            pb.environment().put("HOME", Paths.realHome().toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String token = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor() == 0 && !token.isEmpty() && !token.contains(" ")) return token;
        } catch (Exception ignored) {
        }
        return null;
    }

    private GitHubAccess() {}
}
