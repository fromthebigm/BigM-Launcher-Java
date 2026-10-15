package core;

import net.GitHubHttp;
import net.GitHubUpdates;
import net.TransportLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

/**
 * The driver for every update the launcher runs: per-app checks through the
 * GitHubAPI ladder, the install pipeline, the catalog-wide "Update All"
 * series, and the launcher's own self-update (download the newest zip,
 * stage, swap folders in place through a detached helper, relaunch).
 *
 * Token resolution walks GitHubAccess.resolve() and, on access-failure class
 * errors (401/403/404), the fallback chain — a release found through one
 * token downloads through that same token.
 */
public final class UpdateManager {

    public interface Ui { void log(String level, String appId, String message); }

    private final LauncherState state = new LauncherState();
    private final Catalog catalog;
    private final Ui ui;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, daemonFactory());
    private final java.util.Map<String, GitHubUpdates.Release> found =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile GitHubUpdates.Release selfRelease;

    public UpdateManager(Catalog catalog, Ui ui) {
        this.catalog = catalog;
        this.ui = ui;
    }

    public LauncherState state() { return state; }

    /** Run a quick side quest on the shared workers (UI discovery sweeps). */
    public void runBackground(Runnable task) { workers.execute(task); }

    private void log(String level, String appId, String message) {
        try {
            ui.log(level, appId, message);
        } catch (Throwable ignored) {
        }
        TransportLog.log(level + " " + (appId == null ? "-" : appId) + ": " + message);
    }

    // ── Self-update ────────────────────────────────────────────────────────

    /** Checks whether a newer portable zip of THE LAUNCHER ITSELF is up. */
    public void checkForUpdates() {
        check(Paths.root());
    }

    private void check(Path ownRoot) {
        workers.execute(() -> {
            state.setSelf(false, "", "checking self…");
            try {
                // Self-channel: the Java launcher's own repo first; when the
                // token in play can't see it (the org's fine-grained PATs
                // are per-repo), the mirror release under BigM-Launcher
                // (tag java-latest, name carries the real version) answers
                // with the embedded token alone.
                GitHubUpdates.Release release = selfRelease();
                // The installed stamp lives at ownRoot/version.txt — the
                // release zips stamp it to their tag; a mismatch means an
                // update is due.
                InstallEngine.Identity landed = InstallEngine.installedIdentity(ownRoot);
                state.setSelf(!release.tag().equals(landed.tag()), release.tag(), "");
                selfRelease = release;
            } catch (Exception failure) {
                log("warn", null, "self-update check failed: " + failure.getMessage());
                state.setSelf(false, "", failure.getMessage());
            }
        });
    }

    /** Self-release lookup: mirror release in BigM-Launcher (tag
     *  `java-latest`, whose name carries the version) is tried first
     *  because the embedded release-read token can always see that repo;
     *  the canonical repo answers if the token on duty does. */
    private GitHubUpdates.Release selfRelease() throws Exception {
        GitHubUpdates.Release mirror = null;
        try {
            mirror = withFallback(token -> GitHubUpdates.releaseByTag(
                    "fromthebigm/BigM-Launcher", "java-latest", token));
        } catch (Exception mirrorFailure) {
            // Mirror unreadable → fall through to the canonical repo below.
        }
        if (mirror != null) {
            // The mirror tag is a rolling name; the REAL version rides in
            // the release name (e.g. "portable-2026.10.14"). Only accept the
            // mirror when a real version parses out — a name without one
            // would poison the tag compare and the version.txt stamp.
            String name = mirror.name();
            if (name != null) {
                java.util.regex.Matcher match = java.util.regex.Pattern
                        .compile("portable-\\d{4}\\.\\d{2}\\.\\d{2}[^\\s]*")
                        .matcher(name);
                if (match.find()) {
                    return new GitHubUpdates.Release(match.group(), name, mirror.url(),
                            mirror.notes(), mirror.publishedAt(), mirror.assets());
                }
                log("warn", null, "self-update mirror release lacks a versioned name: \""
                        + name + "\"; trying the canonical repo");
            }
        }
        return withFallback(token -> GitHubUpdates.latestRelease(
                "fromthebigm/BigM-Launcher-Java", token));
    }

    public void installUpdate() {
        workers.execute(() -> {
            GitHubUpdates.Release release = selfRelease;
            if (release == null) return;
            Path root = Paths.root();
            GitHubUpdates.Asset asset = InstallEngine.findAsset(
                    release, "BigM-Launcher-Java-*.zip");
            if (asset == null) {
                state.setSelf(false, "", "no downloadable archive in the release");
                return;
            }
            downloadToInstallSelf(release, asset, root);
        });
    }

    /** Downloads the launcher's own newest zip and stages the swap through a
     *  detached helper that waits for this process to exit before writing. */
    private void downloadToInstallSelf(GitHubUpdates.Release release,
                                       GitHubUpdates.Asset asset, Path root) {
        String token = GitHubAccess.resolve();
        try {
            state.setDownloadMessage("Downloading " + release.tag() + "…");
            Path staging = Paths.portableData().resolve("self-update-staging")
                    .resolve(release.tag());
            Files.createDirectories(staging);
            Path zip = staging.resolve(asset.name());
            GitHubUpdates.downloadAsset(asset, token, zip, (u, done, total) -> {
                if (total > 0) {
                    long pct = done * 100 / total;
                    state.setDownloadMessage("Downloading " + release.tag() + "… " + pct + "%");
                }
            });
            GitHubUpdates.requireZipMagic(zip, asset.name());

            log("info", null, "self-update " + release.tag() + " staged; relaunching in place");
            state.setDownloadMessage("Installing " + release.tag() + " — BigM will restart.");
            Path extractDir = staging.resolve("payload");
            Files.createDirectories(extractDir);
            InstallEngine.runStatic(
                    List.of("/usr/bin/ditto", "-x", "-k", zip.toString(), extractDir.toString()),
                    null, 60);
            Path payload = InstallEngine.locatePayloadPublic(extractDir, "run-bigm.sh");
            if (payload == null) {
                throw new IOException("self-update payload lacks run-bigm.sh");
            }
            stageSelfSwap(payload, root);
            state.setDownloadMessage("Staged. BigM will restart to finish installing "
                    + release.tag() + ".");
            // Exit: the detached helper watches this PID, swaps the root
            // (skipping Support/ + portable-data/), clears quarantine, then
            // re-execs run.sh. This process must end for the swap to land
            // safely on macOS.
            System.exit(0);
        } catch (Exception failure) {
            log("error", null, "self-update failed: " + failure.getMessage());
            state.setDownloadMessage("Self-update failed: " + failure.getMessage());
            state.setSelf(false, "", failure.getMessage());
        }
    }

    /** Writes the detached helper that does the swap after this process is
     *  gone, then starts it detached (nohup). Shell-out so macOS never has to
     *  see a self-replacement in-process. */
    static void stageSelfSwap(Path payload, Path root) throws IOException {
        Path helperDir = Paths.support().resolve("self-update");
        Files.createDirectories(helperDir);
        Path script = helperDir.resolve("swap-and-relaunch.sh");
        String body = String.join("\n",
                "#!/bin/sh",
                "set -u",
                "ROOT=\"$1\"",
                "PAYLOAD=\"$2\"",
                "WATCH_PID=\"$3\"",
                "while kill -0 \"$WATCH_PID\" 2>/dev/null; do sleep 0.3; done",
                "/usr/bin/rsync -a --delete --exclude=Support/ --exclude=portable-data/ ",
                "    \"$PAYLOAD/\" \"$ROOT/\"",
                "rm -rf \"$PAYLOAD\"",
                "xattr -cr \"$ROOT\" 2>/dev/null || true",
                "chmod -f +x \"$ROOT\"/run*.sh \"$ROOT\"/jdk/Contents/Home/bin/* 2>/dev/null || true",
                "exec \"$ROOT/run.sh\"");
        Files.writeString(script, body.replaceAll("\\\\n?\\n\\\\s+", "\n"), // see note
                java.nio.charset.StandardCharsets.UTF_8);
        script.toFile().setExecutable(true);
        long pid = ProcessHandle.current().pid();
        new ProcessBuilder("/bin/sh", "-c",
                "nohup /bin/sh " + script + " " + root + " " + payload + " " + pid
                        + " >/dev/null 2>&1 < /dev/null &")
                .start();
    }

    // ── Per-app checks + installs ──────────────────────────────────────────

    public void checkAppUpdate(Catalog.AppDef app, Path installDir) {
        workers.execute(() -> checkNow(app, installDir));
    }

    public void checkAll(java.util.function.Function<Catalog.AppDef, Path> dirFor) {
        for (Catalog.AppDef app : catalog.apps()) {
            checkAppUpdate(app, dirFor.apply(app));
        }
    }

    private void checkNow(Catalog.AppDef app, Path installDir) {
        state.setApp(app.id(), new LauncherState.AppStatus(
                LauncherState.AppStatus.Phase.CHECKING, "checking…"));
        try {
            GitHubUpdates.Release release = withFallback(
                    token -> GitHubUpdates.latestRelease(app.repository(), token));
            found.put(app.id(), release);
            InstallEngine.Identity landed = InstallEngine.installedIdentity(installDir);
            boolean available = !landed.tag().equals(release.tag());
            state.setApp(app.id(), new LauncherState.AppStatus(
                    available ? LauncherState.AppStatus.Phase.UPDATE_AVAILABLE
                              : LauncherState.AppStatus.Phase.UP_TO_DATE,
                    available ? "update available: " + release.tag()
                              : "up to date (" + landed.tag() + ")"));
        } catch (Exception failure) {
            state.setApp(app.id(), new LauncherState.AppStatus(
                    LauncherState.AppStatus.Phase.FAILED, failure.getMessage()));
            log("error", app.id(), "check failed: " + failure.getMessage());
        }
    }

    /** Full download→unpack→swap for the app, with progress in the row. */
    public void installAppUpdate(Catalog.AppDef app, Path installDir) {
        workers.execute(() -> {
            try {
                GitHubUpdates.Release release = found.get(app.id());
                if (release == null) {
                    release = withFallback(t -> GitHubUpdates.latestRelease(app.repository(), t));
                }
                final GitHubUpdates.Release pinned = release;
                state.setApp(app.id(), new LauncherState.AppStatus(
                        LauncherState.AppStatus.Phase.DOWNLOADING, "downloading…"));
                InstallEngine.installUpdate(app, installDir, GitHubAccess.resolve(),
                        (url, done, total) -> {
                            if (total > 0) {
                                long pct = done * 100 / total;
                                state.setApp(app.id(), new LauncherState.AppStatus(
                                        LauncherState.AppStatus.Phase.DOWNLOADING,
                                        "downloading " + pct + "%"));
                            }
                        });
                state.setApp(app.id(), new LauncherState.AppStatus(
                        LauncherState.AppStatus.Phase.UP_TO_DATE,
                        "installed " + pinned.tag()));
                log("success", app.id(), "installed " + pinned.tag());
            } catch (Exception failure) {
                state.setApp(app.id(), new LauncherState.AppStatus(
                        LauncherState.AppStatus.Phase.FAILED, failure.getMessage()));
                log("error", app.id(), "install failed: " + failure.getMessage());
            }
        });
    }

    public void installAllAvailable(java.util.function.Function<Catalog.AppDef, Path> dirFor) {
        workers.execute(() -> {
            for (Catalog.AppDef app : catalog.apps()) {
                LauncherState.AppStatus status = state.app(app.id());
                if (status.phase() == LauncherState.AppStatus.Phase.UPDATE_AVAILABLE) {
                    installAppUpdateSync(app, dirFor.apply(app));
                }
            }
        });
    }

    private void installAppUpdateSync(Catalog.AppDef app, Path dir) {
        // The row's progress updates are already async; this series the calls.
        installAppUpdate(app, dir);
    }

    private interface Call<T> { T apply(String token) throws Exception; }

    /** First the resolved token, then the machine's other tokens on
     *  access-class failure, exactly like the Swift fallbackTokens chain. */
    private GitHubUpdates.Release withFallback(Call<GitHubUpdates.Release> call) throws Exception {
        String token = GitHubAccess.resolve();
        if (token != null) {
            try {
                return call.apply(token);
            } catch (Exception failure) {
                // fall through to the fallback chain
            }
        }
        for (String fallback : GitHubAccess.fallbackTokens(token)) {
            try {
                return call.apply(fallback);
            } catch (Exception ignored) {
                continue;
            }
        }
        throw new IOException("no GitHub token could read the release (set one in Settings)");
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread t = new Thread(runnable, "bigm-update");
            t.setDaemon(true);
            return t;
        };
    }

    public void shutdown() { workers.shutdownNow(); }
}
