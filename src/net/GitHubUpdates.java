package net;

import core.Paths;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GitHub releases client over {@link GitHubHttp}: latest release metadata,
 * the asset redirect probe, and the download. The API endpoints we hit stay
 * JSON-shaped; anything else is a middlebox answer and escalates the
 * transport ladder.
 */
public final class GitHubUpdates {

    public record Asset(String name, String apiUrl, long size) {}

    public record Release(String tag, String name, String url, String notes,
                          String publishedAt, List<Asset> assets) {}

    public interface DownloadProgress { void progress(String tier, long done, long total); }

    /** Latest release of a repo. Throws on transport/parse failure with the
     *  tier-noted message (never the bare Cocoa parse error this launcher
     *  used to show). */
    public static Release latestRelease(String repo, String token)
            throws IOException, InterruptedException {
        return fetchRelease("https://api.github.com/repos/" + repo + "/releases/latest",
                repo, token);
    }

    /** A release pinned by tag (used by the self-update mirror: the Java
     *  launcher's own repo is private-scoped per token, while the org keeps
     *  a `java-latest` tag release in a repo the embedded token can read). */
    public static Release releaseByTag(String repo, String tag, String token)
            throws IOException, InterruptedException {
        return fetchRelease("https://api.github.com/repos/" + repo + "/releases/tags/" + tag,
                repo, token);
    }

    private static Release fetchRelease(String endpoint, String repo, String token)
            throws IOException, InterruptedException {
        GitHubHttp.Answer answer = GitHubHttp.exchange(endpoint, token, Duration.ofSeconds(20));
        int status = answer.status();
        byte[] body = answer.body();
        if (!JsonTiny.isObject(body)) {
            String preview = preview(body);
            throw new IOException("github-api answered " + status
                    + " with non-JSON content for " + repo + ": \"" + preview + "\""
                    + (preview.isEmpty() ? " (empty body)" : "")
                    + ". A filter product is answering instead of GitHub.");
        }
        if (status == 401 || status == 403 || status == 404) {
            throw new IOException(switch (status) {
                case 401 -> "The GitHub token was rejected (HTTP 401). It may be expired or revoked.";
                case 403 -> "Access denied for " + repo + " (HTTP 403). The token needs Contents read.";
                default -> "github-api answered 404 for " + repo
                        + " (no releases visible to this token).";
            });
        }
        if (status < 200 || status >= 300) {
            throw new IOException("github-api answered HTTP " + status + " for " + repo);
        }
        Map<String, Object> payload = JsonTiny.parseObject(body);
        String tag = JsonTiny.str(payload, "tag_name");
        if (tag == null || tag.isBlank()) {
            throw new IOException("github-api answer for " + repo + " carries no tag_name");
        }
        List<Asset> assets = new ArrayList<>();
        for (Object entry : JsonTiny.array(payload, "assets")) {
            Map<String, Object> asset = JsonTiny.obj(entry);
            String name = JsonTiny.str(asset, "name");
            String url = JsonTiny.str(asset, "url");
            long size = JsonTiny.num(asset, "size", -1);
            if (name != null && url != null) {
                assets.add(new Asset(name, url, size));
            }
        }
        TransportLog.log("release " + repo + ": tag " + tag + " (" + assets.size() + " assets)");
        return new Release(
                tag,
                JsonTiny.str(payload, "name") != null ? JsonTiny.str(payload, "name") : tag,
                JsonTiny.str(payload, "html_url"),
                JsonTiny.str(payload, "body") != null ? JsonTiny.str(payload, "body") : "",
                JsonTiny.str(payload, "published_at"),
                List.copyOf(assets));
    }

    /** The asset API answers `Accept: application/octet-stream` with a 302 to
     *  the signed CDN URL. We take the Location header instead of following
     *  so the Bearer token never crosses to the CDN. */
    public static String resolveAssetDownload(Asset asset, String token)
            throws IOException, InterruptedException {
        GitHubHttp.Answer answer = GitHubHttp.redirectProbe(asset.apiUrl(), token,
                Duration.ofSeconds(20));
        int status = answer.status();
        String location = answer.first("location");
        if (status >= 300 && status < 400 && location != null && !location.isBlank()) {
            return location;
        }
        if (JsonTiny.isObject(answer.body())) {
            // JSON only ever means a definitive GitHub answer here (403/404
            // for visibility problems, or a 200 API error page).
            throw new IOException("github-api answered " + status + " for " + asset.name()
                    + " without a download link.");
        }
        String preview = preview(answer.body());
        throw new IOException("github-api answered " + status + " with non-JSON content for "
                + asset.name() + ": \"" + preview + "\""
                + (preview.isEmpty() ? " (empty body)" : "")
                + ". A filter product is answering instead of GitHub.");
    }

    /** Download the asset's CDN bytes to {@code destination}. Zip-magic
     *  verified post-transfer; a filter-substituted page fails with a named
     *  cause instead of handing whatever ditto would choke on. */
    public static void downloadAsset(Asset asset, String token, Path destination,
                                     DownloadProgress progress)
            throws IOException, InterruptedException {
        String url = resolveAssetDownload(asset, token);
        TransportLog.log("asset " + asset.name() + ": redirect -> " + hostOf(url));
        GitHubHttp.downloadToFile(url, destination, null, (done, total) -> {
            if (progress != null) progress.progress(url, done, total);
        }, null);
        requireZipMagic(destination, asset.name());
        TransportLog.log("asset " + asset.name() + ": downloaded " + Files.size(destination)
                + " bytes");
    }

    public static void requireZipMagic(Path file, String name) throws IOException {
        byte[] head = new byte[4];
        try (var in = Files.newInputStream(file)) {
            int read = in.read(head);
            if (read < 4 || head[0] != 'P' || head[1] != 'K'
                    || (head[2] != 3 || head[3] != 4)
                    && (head[2] != 5 || head[3] != 6)) {
                String preview = preview(Files.readAllBytes(file), 60);
                throw new IOException("download for " + name + " is not a zip archive"
                        + (preview.isEmpty() ? "" : " (starts with " + preview + ")")
                        + " — a network filter may be substituting pages for downloads.");
            }
        }
    }

    private static String preview(byte[] body) { return preview(body, 160); }

    private static String preview(byte[] body, int max) {
        if (body == null) return "";
        String text = new String(body, 0, Math.min(body.length, max),
                java.nio.charset.StandardCharsets.UTF_8);
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String hostOf(String url) {
        try { return URI.create(url).getHost(); } catch (Exception e) { return url; }
    }

    private GitHubUpdates() {}
}
