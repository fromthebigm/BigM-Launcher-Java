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
    public void runBackground(Runnable task) {
        try { workers.execute(task); }
        catch (java.util.concurrent.RejectedExecutionException ignored) {
            // A display timer can fire while the window is disposing and the
            // pool is shutting down; that is a normal shutdown race, not a
            // failure worth killing the app over.
        }
    }

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
            // Self-channel: the mirror release under BigM-Launcher (tag
            // java-latest, whose name carries the real version) answers
            // with the embedded token alone.
            //
            // NOTE: we do NOT clear the banner during re-checks — the old
            // code set selfAvailable=false at the start of every check,
            // which made the banner flicker off and back on during each
            // periodic sweep. The banner holds its last known state and
            // only changes when the check actually completes.
            try {
                GitHubUpdates.Release release = selfRelease();
                if (release == null) {
                    // No token could read any release — keep the last known
                    // state if we have one; otherwise show the failure.
                    if (state.selfTag() == null || state.selfTag().isBlank()) {
                        state.setSelf(false, "", "no GitHub token available (check Settings)");
                    }
                    log("warn", null, "self-update check: no token could read the release");
                    return;
                }
                // The installed stamp lives at ownRoot/version.txt — the
                // release zips stamp it to their tag; a mismatch means an
                // update is due.
                InstallEngine.Identity landed = InstallEngine.installedIdentity(ownRoot);
                state.setSelf(!release.tag().equals(landed.tag()), release.tag(), "");
                selfRelease = release;
            } catch (Exception failure) {
                // Only clear the banner on failure if we had nothing before
                // (first check); a failed re-check keeps the last good state
                if (state.selfTag() == null || state.selfTag().isBlank()) {
                    state.setSelf(false, "", failure.getMessage());
                }
                log("warn", null, "self-update check failed: " + failure.getMessage());
            }
        });
    }

    /** Self-release lookup: mirror release in BigM-Launcher (tag
     *  `java-latest`, whose name carries the version) is tried first
     *  because the embedded release-read token can always see that repo;
     *  the canonical repo answers if the token on duty does.
     *  Returns null when no token could read either repo. */
    private GitHubUpdates.Release selfRelease() {
        GitHubUpdates.Release mirror = withFallback(token -> {
            try {
                return GitHubUpdates.releaseByTag(
                        "fromthebigm/BigM-Launcher", "java-latest", token);
            } catch (Exception e) {
                return null;
            }
        });
        if (mirror != null) {
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
        return withFallback(token -> {
            try {
                return GitHubUpdates.latestRelease(
                        "fromthebigm/BigM-Launcher-Java", token);
            } catch (Exception e) {
                return null;
            }
        });
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
     *  see a self-replacement in-process.
     *
     *  The script LOGS to Support/self-update/swap.log — if the swap ever
     *  fails again, that file is the first place to look. The swap is
     *  transactional: build the new root beside the old one, verify it,
     *  atomically rename old→.old and new→root, and roll back on failure.
     *  Never rsync --delete over the live install. */
    static void stageSelfSwap(Path payload, Path root) throws IOException {
        Path helperDir = Paths.support().resolve("self-update");
        Files.createDirectories(helperDir);
        // Run the swap helper from /tmp, not from inside the root being
        // swapped: otherwise renaming ROOT mid-script can move the script
        // file out from under the running shell.
        Path script = Files.createTempFile("bigm-swap-", ".sh");
        Path logFile = helperDir.resolve("swap.log");

        String body = """
                #!/bin/sh
                # Auto-generated by BigM self-update — do not edit.
                ROOT="$1"
                PAYLOAD="$2"
                WATCH_PID="$3"
                LOG="$4"
                exec >>"$LOG" 2>&1
                echo "── swap started $(date) ──"
                echo "ROOT=$ROOT"
                echo "PAYLOAD=$PAYLOAD"
                echo "WATCH_PID=$WATCH_PID"

                # Wait for the Java process to fully exit
                n=0
                while kill -0 "$WATCH_PID" 2>/dev/null; do
                    sleep 0.3
                    n=$((n+1))
                    if [ "$n" -gt 100 ]; then
                        echo "ERROR: waited 30s for PID $WATCH_PID to exit, giving up"
                        exit 1
                    fi
                done
                echo "process $WATCH_PID exited after $((n * 3 / 10))s"

                # The swap: copy the new payload over the root, preserving
                # user data. Everything the user owns lives under Support/
                # and portable-data/ — both excluded from the overwrite.
                if [ ! -d "$PAYLOAD" ]; then
                    echo "ERROR: payload $PAYLOAD does not exist"
                    exit 1
                fi
                if [ ! -f "$PAYLOAD/run-bigm.sh" ]; then
                    echo "ERROR: payload lacks run-bigm.sh — not a valid BigM zip"
                    ls -la "$PAYLOAD"
                    exit 1
                fi
                echo "transactional swap: build new root beside old root"
                NEW="$ROOT.new"
                OLD="$ROOT.old"
                rm -rf "$NEW" "$OLD"
                if ! /usr/bin/ditto "$PAYLOAD" "$NEW"; then
                    echo "ERROR: ditto copy to $NEW failed — launcher NOT updated"
                    rm -rf "$NEW"
                    exit 1
                fi
                # Preserve user-owned data from the live root.
                if [ -d "$ROOT/Support" ]; then
                    /usr/bin/ditto "$ROOT/Support" "$NEW/Support" || true
                fi
                if [ -d "$ROOT/portable-data" ]; then
                    /usr/bin/ditto "$ROOT/portable-data" "$NEW/portable-data" || true
                fi
                if [ ! -f "$NEW/run-bigm.sh" ] || [ ! -x "$NEW/jdk/Contents/Home/bin/java" ]; then
                    echo "ERROR: staged payload failed verification — launcher NOT updated"
                    rm -rf "$NEW"
                    exit 1
                fi
                mv "$ROOT" "$OLD" || { echo "ERROR: could not move old root aside"; rm -rf "$NEW"; exit 1; }
                if ! mv "$NEW" "$ROOT"; then
                    echo "ERROR: could not promote new root — rolling back"
                    mv "$OLD" "$ROOT" || echo "FATAL: rollback failed; manual recovery needed"
                    exit 1
                fi
                rm -rf "$OLD"
                rm -rf "$PAYLOAD"

                # Clear quarantine (a zip-extracted copy arrives with it)
                xattr -cr "$ROOT" 2>/dev/null || true

                # Re-arm exec bits (ditto sometimes drops them)
                chmod -f +x "$ROOT"/run*.sh 2>/dev/null || true
                if [ -d "$ROOT/jdk/Contents/Home/bin" ]; then
                    chmod -f +x "$ROOT"/jdk/Contents/Home/bin/* 2>/dev/null || true
                fi

                echo "swap complete — relaunching run.sh"
                exec "$ROOT/run.sh"
                """;

        Files.writeString(script, body, java.nio.charset.StandardCharsets.UTF_8);
        script.toFile().setExecutable(true);

        long pid = ProcessHandle.current().pid();
        // Start the helper detached: nohup + background, stdio on the log.
        // The `&` inside `/bin/sh -c` means the outer shell returns
        // immediately; nohup shields the helper from the parent's death.
        String command = String.format(
                "nohup /bin/sh '%s' '%s' '%s' '%d' '%s' &",
                script, root, payload, pid, logFile);
        new ProcessBuilder("/bin/sh", "-c", command).start();
    }

    // ── Per-app checks + installs ──────────────────────────────────────────

    public void checkAppUpdate(Catalog.AppDef app, Path installDir) {
        workers.execute(() -> checkNow(app, installDir));
    }

    public void checkAll(java.util.function.Function<Catalog.AppDef, Path> dirFor) {
        for (Catalog.AppDef app : catalog.apps()) {
            Path dir = dirFor.apply(app);
            if (dir == null) continue;   // not installed — no version to compare
            checkAppUpdate(app, dir);
        }
    }

    private void checkNow(Catalog.AppDef app, Path installDir) {
        state.setApp(app.id(), new LauncherState.AppStatus(
                LauncherState.AppStatus.Phase.CHECKING, "checking…"));
        try {
            GitHubUpdates.Release release = withFallback(
                    token -> {
                        try {
                            return GitHubUpdates.latestRelease(app.repository(), token);
                        } catch (Exception e) {
                            return null;
                        }
                    });
            if (release == null) {
                state.setApp(app.id(), new LauncherState.AppStatus(
                        LauncherState.AppStatus.Phase.FAILED,
                        "no GitHub token available (check Settings)"));
                log("warn", app.id(), "update check: no token could read the release");
                return;
            }
            found.put(app.id(), release);
            // Null installDir = app not installed on this machine (checkAll
            // sweeps every catalog app); show the latest tag without the
            // installed-version compare (which would NPE on null.resolve).
            if (installDir == null) {
                state.setApp(app.id(), new LauncherState.AppStatus(
                        LauncherState.AppStatus.Phase.IDLE,
                        "not installed (latest: " + release.tag() + ")"));
                return;
            }
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
                    release = withFallback(t -> {
                        try {
                            return GitHubUpdates.latestRelease(app.repository(), t);
                        } catch (Exception e) {
                            return null;
                        }
                    });
                }
                if (release == null) {
                    state.setApp(app.id(), new LauncherState.AppStatus(
                            LauncherState.AppStatus.Phase.FAILED,
                            "no GitHub token available (check Settings)"));
                    return;
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
     *  access-class failure. Returns null when no token could read the
     *  release — callers must handle null (the old code THREW here, which
     *  crashed every update check on machines without a configured token). */
    private GitHubUpdates.Release withFallback(Call<GitHubUpdates.Release> call) {
        String token = GitHubAccess.resolve();
        if (token != null) {
            try {
                return call.apply(token);
            } catch (Exception failure) {
                // fall through to the fallback chain
            }
        } else {
            // Repos are public: unauthenticated GitHub API access is enough
            // for release checks and asset downloads (60 req/hour). A token
            // is an optional enhancement, never a requirement.
            try { return call.apply(null); } catch (Exception ignored) { }
        }
        for (String fallback : GitHubAccess.fallbackTokens(token)) {
            if (fallback == null || fallback.isBlank()) continue;
            try {
                return call.apply(fallback);
            } catch (Exception ignored) {
                continue;
            }
        }
        return null;
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
