package core;

import net.GitHubHttp;
import net.GitHubUpdates;
import net.TransportLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The install/update engine: detect the newest GitHub release for an app,
 * download its portable zip, unpack, and rsync the payload over the live
 * install folder, skipping preserved user-data folders. Mirrors BigM's
 * staged-swap discipline: snapshot for rollback, stamp version.txt from the
 * release tag, verify the catalog marker, relaunch via run scripts.
 */
public final class InstallEngine {

    public record Identity(String tag, String builtAt) {}

    /** What the install folder believes it is. Empty tag = unknown/missing. */
    public static Identity installedIdentity(Path installDir) {
        Path stamp = installDir.resolve("version.txt");
        if (!Files.isRegularFile(stamp)) return new Identity("", "");
        try {
            List<String> lines = Files.readAllLines(stamp, StandardCharsets.UTF_8);
            String tag = lines.isEmpty() ? "" : lines.get(0).trim();
            String builtAt = lines.size() > 1 ? lines.get(1).trim() : "";
            return new Identity(tag, builtAt);
        } catch (IOException ignored) {
            return new Identity("", "");
        }
    }

    /** Newest release on the app's update repository, or null when the app
     *  carries no update metadata. */
    public static GitHubUpdates.Release latestFor(Catalog.AppDef app, String token)
            throws IOException, InterruptedException {
        if (app.repository() == null || app.repository().isBlank()) return null;
        return GitHubUpdates.latestRelease(app.repository(), token);
    }

    /** Asset matching the catalog's assetPattern (NSPredicate `LIKE`
     *  wildcard semantics: `*` and `?`). */
    public static GitHubUpdates.Asset findAsset(GitHubUpdates.Release release, String pattern) {
        for (GitHubUpdates.Asset asset : release.assets()) {
            if (shellMatch(asset.name(), pattern)) return asset;
        }
        return null;
    }

    /** Full install pipeline for one app. Preserved folders survive the
     *  swap; the marker file proves the layout. */
    public static void installUpdate(Catalog.AppDef app, Path installDir, String token,
                                     GitHubUpdates.DownloadProgress progress)
            throws IOException, InterruptedException {
        var release = latestFor(app, token);
        if (release == null) throw new IOException(app.id() + " has no update metadata");
        var asset = findAsset(release, app.assetPattern());
        if (asset == null) {
            throw new IOException("release " + release.tag() + " has no asset matching "
                    + app.assetPattern());
        }
        TransportLog.log("install " + app.id() + ": " + release.tag() + " via " + asset.name());

        // Stage: download into a per-install staging dir so a half-written
        // payload never lands in the live folder.
        Path staging = Paths.portableData().resolve("staging")
                .resolve(app.id() + "-" + release.tag());
        Path downloadDir = staging.resolve("download");
        Path extractDir = staging.resolve("payload");
        Files.createDirectories(downloadDir);
        Files.createDirectories(extractDir);
        Path zip = downloadDir.resolve(asset.name());

        GitHubUpdates.downloadAsset(asset, token, zip, progress);

        // ditto for real symlink/permission fidelity — java.util.zip cannot
        // preserve symlinks, and portable JDKs die without them.
        run(List.of("/usr/bin/ditto", "-x", "-k", zip.toString(), extractDir.toString()),
                null, 10 * 60);

        Path payload = locatePayload(extractDir, app.marker());
        if (payload == null) {
            throw new IOException("no folder containing marker " + app.marker()
                    + " under " + extractDir);
        }

        // Rollback snapshot of the live install (minus preserved data).
        Path backupDir = null;
        if (Files.isDirectory(installDir)) {
            backupDir = backingSnapshot(app, installDir);
        }

        Files.createDirectories(installDir);
        rsyncPayloadOverInstall(payload, installDir, app.preserve());
        writeStamp(installDir, release.tag(), release.publishedAt());

        // Verify: marker executable, stamp present.
        if (!Files.isRegularFile(installDir.resolve(app.marker()))) {
            throw new IOException("marker " + app.marker() + " missing after install");
        }
        Identity landed = installedIdentity(installDir);
        if (!landed.tag().equals(release.tag())) {
            // Landed stamp mismatches the release tag: roll back rather than
            // leave an identity the next check would re-offer forever.
            if (backupDir != null) {
                restoreSnapshot(backupDir, installDir, app.preserve());
            }
            throw new IOException("landed stamp " + landed.tag() + " != release tag "
                    + release.tag() + " — rolled back");
        }
        try {
            deleteRecursively(staging);
        } catch (IOException ignored) {
        }
        TransportLog.log("install " + app.id() + ": landed at " + release.tag());
    }

    /** Remove-stamp drift never makes a loop: after install the folder reads
     *  exactly the release tag, so the next check reports up-to-date. */
    public static boolean updateAvailable(Catalog.AppDef app, Path installDir,
                                          GitHubUpdates.Release release) {
        if (release == null) return false;
        Identity landed = installedIdentity(installDir);
        return !landed.tag().equals(release.tag());
    }

    // ── Internals ──────────────────────────────────────────────────────────

    /** Wildcard matcher compatible with the catalog's `LIKE` patterns. */
    static boolean shellMatch(String name, String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        return name.matches(regex.toString());
    }

    private static Path locatePayload(Path extractDir, String marker) throws IOException {
        try (var stream = Files.walk(extractDir, 4)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve(marker)))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static Path backingSnapshot(Catalog.AppDef app, Path installDir)
            throws IOException, InterruptedException {
        Path backup = Paths.portableData().resolve("rollback")
                .resolve(app.id() + "-" + System.currentTimeMillis());
        Files.createDirectories(backup);
        List<String> command = new ArrayList<>(List.of("/usr/bin/rsync", "-a"));
        for (String kept : app.preserve()) {
            command.add("--exclude=" + kept);
        }
        command.add(installDir.toString() + "/");
        command.add(backup.toString() + "/");
        run(command, null, 10 * 60);
        return backup;
    }

    private static void rsyncPayloadOverInstall(Path payload, Path installDir,
                                                List<String> preserve)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("/usr/bin/rsync", "-a", "--delete"));
        for (String kept : preserve) command.add("--exclude=" + kept);
        command.add(payload.toString() + "/");
        command.add(installDir.toString() + "/");
        run(command, null, 10 * 60);
    }

    private static void restoreSnapshot(Path backupDir, Path installDir, List<String> preserve)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("/usr/bin/rsync", "-a", "--delete"));
        for (String kept : preserve) command.add("--exclude=" + kept);
        command.add(backupDir.toString() + "/");
        command.add(installDir.toString() + "/");
        run(command, null, 10 * 60);
    }

    private static void writeStamp(Path installDir, String tag, String publishedAt)
            throws IOException {
        String date = publishedAt != null && publishedAt.length() >= 10
                ? publishedAt.substring(0, 10) : "";
        Files.writeString(installDir.resolve("version.txt"),
                tag + "\n" + date + "\n", StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void run(List<String> command, Path cwd, int timeoutSeconds)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        if (cwd != null) builder.directory(cwd.toFile());
        Process process = builder.start();
        byte[] output;
        try (var in = process.getInputStream()) {
            output = in.readAllBytes();
        }
        boolean finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException(command.get(0) + " timed out after " + timeoutSeconds + "s");
        }
        if (process.exitValue() != 0) {
            throw new IOException(command.get(0) + " exited " + process.exitValue()
                    + ": " + new String(output, StandardCharsets.UTF_8).strip());
        }
    }

    private InstallEngine() {}
}
