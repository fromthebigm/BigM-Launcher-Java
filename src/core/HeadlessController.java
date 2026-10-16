package core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The headless library controller — everything LauncherWindow did for the
 * SWT face, rebuilt for the SwiftUI bridge: resolves installs, tracks
 * running apps, builds the state snapshot as JSON, pushes it to Swift, and
 * dispatches Swift actions back into the Java core (launch, quit, update…).
 *
 * State changes marshal to a single pushState() — Swift re-renders on the
 * main queue. No polling anywhere; everything is event-driven.
 */
public final class HeadlessController implements LauncherState.Listener, UpdateManager.Ui {

    private final Catalog catalog;
    private final LauncherConfig config;
    private final PlayStats stats;
    private final UpdateManager updates;
    private final Path root;

    private final Map<String, Path> installDirs = new ConcurrentHashMap<>();
    private final Map<String, Process> running = new ConcurrentHashMap<>();
    private final Map<String, String> profileChoice = new ConcurrentHashMap<>();
    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();
    private final List<String> events = new CopyOnWriteArrayList<>();

    private volatile String selectedFilter = "all";
    private volatile boolean activated;

    public HeadlessController(Catalog catalog) {
        this.catalog = catalog;
        this.config = new LauncherConfig();
        this.stats = new PlayStats();
        this.root = Paths.root();
        this.updates = new UpdateManager(catalog, this);
        this.activated = ProductGate.isActivated();
        updates.state().add(this);
    }

    public void start() {
        // Background install discovery
        updates.runBackground(() -> {
            for (Catalog.AppDef app : visibleApps()) {
                Path dir = installDirFor(app);
                if (dir != null) installDirs.put(app.id(), dir);
                else installDirs.remove(app.id());
            }
            pushState();
            // Update sweep for installed apps
            for (Catalog.AppDef app : visibleApps()) {
                if (!app.updatable()) continue;
                Path dir = installDirs.get(app.id());
                if (dir != null) updates.checkAppUpdate(app, dir);
            }
            updates.checkForUpdates();
        });

        // Self-check on activation (15 min gate) + periodic 30 min
        Thread selfCheck = new Thread(() -> {
            updates.checkForUpdates();
            while (true) {
                try { Thread.sleep(30 * 60 * 1000); } catch (InterruptedException e) { return; }
                updates.checkForUpdates();
            }
        }, "bigm-selfcheck");
        selfCheck.setDaemon(true);
        selfCheck.start();
    }

    // ── State snapshot ───────────────────────────────────────────────────

    private void pushState() {
        SwiftUIHost.pushState(buildStateJson());
    }

    private String buildStateJson() {
        JsonOut json = JsonOut.begin();
        InstallEngine.Identity self = InstallEngine.installedIdentity(root);
        json.field("windowTitle", "BigM");
        json.field("themeKey", config.uiAccent());
        json.field("variant", config.uiVariant());
        json.field("activated", activated);
        json.field("version", self.tag() == null || self.tag().isBlank()
                ? "dev build" : self.tag());

        // Cards
        json.array("cards");
        for (Catalog.AppDef app : visibleApps()) {
            Path dir = installDirs.get(app.id());
            boolean installed = dir != null && Files.isDirectory(dir);
            boolean isRunning = running.containsKey(app.id());
            LauncherState.AppStatus status = updates.state().app(app.id());

            String state;
            if (isRunning) state = "running";
            else switch (status.phase()) {
                case CHECKING -> state = "checking";
                case DOWNLOADING, INSTALLING -> state = "downloading";
                case UPDATE_AVAILABLE -> state = "updateAvailable";
                case FAILED -> state = "failed";
                default -> state = installed ? "installed" : "missing";
            };

            String statsText = stats.summaryLine(app.id());
            if (statsText == null) statsText = "Never launched";

            String iconPath = null;
            if (IconStore.hasCustomIcon(app.id())) {
                iconPath = IconStore.icon(app.id(), true).toString();
            } else if (IconStore.hasSystemIcon(app.id())) {
                iconPath = IconStore.systemIcon(app.id(), true).toString();
            }

            RGB accent = parseHex(app.tintHex());

            json.beginElement();
            json.field("id", app.id());
            json.field("name", app.displayName());
            json.field("category", app.category() == null ? "" : app.category());
            json.field("summary", app.summary());
            json.field("statsLine", statsText);
            json.field("accentRed", accent.r);
            json.field("accentGreen", accent.g);
            json.field("accentBlue", accent.b);
            json.field("state", state);
            json.field("installed", installed);
            json.fieldOpt("statusMessage", status.message());
            json.fieldOpt("progress", parseProgress(status.message()));
            json.fieldOpt("iconPath", iconPath);
            json.closeObject();
        }
        json.closeArray();

        // Filters (installed-only per category, hide empties)
        json.array("filters");
        json.beginElement();
        json.field("id", "all");
        json.field("title", "All Apps");
        json.field("count", visibleApps().size());
        json.closeObject();
        for (String cat : new String[]{"streaming", "remote-play", "browser",
                "minecraft", "emulator", "games", "development"}) {
            long count = visibleApps().stream()
                    .filter(a -> cat.equals(a.category()) && isInstalled(a.id()))
                    .count();
            if (count == 0 && !cat.equals(selectedFilter)) continue;
            json.beginElement();
            json.field("id", cat);
            json.field("title", categoryName(cat));
            json.field("count", (int) count);
            json.closeObject();
        }
        json.closeArray();

        json.field("selectedFilter", selectedFilter);
        json.field("selfUpdateAvailable", updates.state().selfAvailable());
        json.fieldOpt("selfUpdateTag", updates.state().selfTag());
        json.fieldOpt("selfUpdateMessage", updates.state().downloadMessage());
        json.field("runningCount", running.size());

        // Settings the sheets read (theme, variant, install folder…)
        json.object("settings");
        json.field("themeKey", config.uiAccent());
        json.field("variant", config.uiVariant());
        json.fieldOpt("defaultInstallFolder", config.defaultInstallFolder());
        json.closeObject();

        // Diagnostics events (the sheet reads the last 100)
        json.array("events");
        List<String> snapshot = new ArrayList<>(events);
        for (int i = Math.max(0, snapshot.size() - 100); i < snapshot.size(); i++) {
            json.stringElement(snapshot.get(i));
        }
        json.closeArray();

        return json.finish();
    }

    // ── Action dispatch (from Swift) ─────────────────────────────────────

    public void handleAction(String action, String id) {
        switch (action) {
            case "launch" -> launchApp(id);
            case "quit" -> quitApp(id);
            case "update" -> {
                Catalog.AppDef app = findApp(id);
                Path dir = installDirs.get(id);
                if (app != null && dir != null) updates.installAppUpdate(app, dir);
            }
            case "install" -> installLatest(id);
            case "reveal" -> revealApp(id);
            case "filter" -> {
                selectedFilter = id;
                pushState();
            }
            case "scanFolderResult" -> scanFolder(id);
            case "activate" -> activate(id);
            case "quitApp" -> System.exit(0);
            case "checkOne" -> {
                Catalog.AppDef app = findApp(id);
                Path dir = installDirs.get(id);
                if (app != null) updates.checkAppUpdate(app, dir);
            }
            case "checkAll", "updateAll" -> {
                if ("checkAll".equals(action)) {
                    updates.checkAll(installDirs::get);
                } else {
                    updates.installAllAvailable(installDirs::get);
                }
            }
            case "installSelf" -> updates.installUpdate();
            case "setTheme" -> {
                config.setUiAccent(id);
                pushState();
            }
            case "setVariant" -> {
                // Actually store the preference — the old handler ignored
                // the requested value and pushed "auto" every time, so the
                // night scene never appeared.
                if (id != null && !id.isBlank()) {
                    config.setUiVariant(id);
                    log("info", null, "scenery variant: " + id);
                }
                pushState();
            }
            case "setDefaultFolder" -> {
                if (id != null && !id.isBlank()) {
                    config.setDefaultInstallFolder(id);
                    log("info", null, "default install folder: " + id);
                }
                pushState();
            }
            case "revealFile" -> revealFile(id);
            case "setLauncherIcon" -> setLauncherIcon(id);
            case "resetLauncherIcon" -> {
                IconStore.resetLauncherIcon();
                log("info", null, "launcher icon reset to the bundled preset");
            }
            case "showGate" -> pushState();
            case "addCustomApp" -> log("info", null, "add custom app: form pending");
            case "addWebsite" -> log("info", null, "add website: form pending");
            default -> log("warn", null, "unknown action: " + action);
        }
    }

    private void revealFile(String which) {
        Path target = switch (which == null ? "" : which) {
            case "transportLog" -> Paths.transportLog();
            case "config" -> Paths.support().resolve("config.properties");
            default -> Paths.support();
        };
        try {
            if ("config".equals(which) && !Files.exists(target)) {
                new ProcessBuilder("/usr/bin/open", "-R", Paths.support().toString()).start();
            } else if (Files.exists(target)) {
                new ProcessBuilder("/usr/bin/open", "-R", target.toString()).start();
            } else {
                new ProcessBuilder("/usr/bin/open", Paths.support().toString()).start();
            }
        } catch (IOException failure) {
            log("error", null, "reveal failed: " + failure.getMessage());
        }
    }

    private void setLauncherIcon(String appBundlePath) {
        if (appBundlePath == null || appBundlePath.isBlank()) return;
        boolean ok = IconStore.applyCustomIcon(IconStore.LAUNCHER_ID, Path.of(appBundlePath));
        if (ok) {
            IconStore.applyLauncherIcon();
            log("success", null, "launcher dock icon set from " + Path.of(appBundlePath).getFileName());
        } else {
            log("error", null, "could not render an icon from " + appBundlePath);
        }
    }

    private void launchApp(String appId) {
        Catalog.AppDef app = findApp(appId);
        if (app == null) return;
        List<Catalog.Profile> profiles = profilesFor(app);
        if (profiles.isEmpty()) return;

        String wanted = profileChoice.get(appId);
        Catalog.Profile profile = profiles.stream()
                .filter(p -> p.id().equals(wanted)).findFirst().orElse(profiles.get(0));

        Path dir = installDirs.get(appId);
        String dirText = dir == null ? "" : dir.toString();

        // Resolve target (same logic as the SWT face)
        String target = null;
        if (dir != null && app.marker() != null && !app.marker().isBlank()) {
            Path markerPath = dir.resolve(app.marker());
            if (Files.exists(markerPath)) target = markerPath.toString();
        }
        if (target == null) {
            List<String> guesses = new ArrayList<>(profile.candidates());
            if (profile.target() != null) guesses.add(profile.target());
            for (String guess : guesses) {
                String expanded = expand(guess);
                if (expanded != null && Files.exists(Path.of(expanded.replace("{targetDir}", dirText)))) {
                    target = expanded;
                    break;
                }
            }
        }
        if (target == null) {
            String raw = expand(profile.target());
            if (raw == null || raw.isBlank()) {
                log("error", appId, "no launch target for " + appId);
                return;
            }
            target = raw;
        }
        final String resolved = target.replace("{targetDir}", dirText);

        List<String> argv = new ArrayList<>();
        String backend = profile.backend() == null ? "script" : profile.backend();
        if ("python".equals(backend)) {
            argv.add("/usr/bin/python3");
            argv.add(resolved);
        } else if ("application".equals(backend)) {
            argv.add("/usr/bin/open");
            argv.add("-W");
            argv.add(resolved);
        } else {
            argv.add(resolved);
        }
        if ("application".equals(backend) && !profile.args().isEmpty()) argv.add("--args");
        for (String arg : profile.args()) {
            argv.add(arg.replace("{targetDir}", dirText));
        }

        ProcessBuilder builder = new ProcessBuilder(argv);
        if (profile.cwd() != null && !profile.cwd().isBlank()) {
            String cwd = expand(profile.cwd().replace("{targetDir}", dirText));
            if (cwd != null && Files.isDirectory(Path.of(cwd))) {
                builder.directory(Path.of(cwd).toFile());
            }
        }

        Map<String, String> env = builder.environment();
        env.remove("GH_TOKEN");
        env.remove("GITHUB_TOKEN");
        for (Map.Entry<String, String> entry : profile.env().entrySet()) {
            env.put(entry.getKey(), entry.getValue().replace("{targetDir}", dirText));
        }
        env.putAll(LaunchTicket.environment());
        env.putAll(IconStore.launchEnvironment(appId,
                IconStore.Mode.parse(config.iconMode(appId))));
        if (!config.captureProtection(appId)) env.put("BIGM_CAPTURE_PROTECTION", "0");

        try {
            Path logsDir = Paths.support().resolve("logs");
            Files.createDirectories(logsDir);
            Path logFile = logsDir.resolve(appId + "-" + (System.currentTimeMillis() / 1000) + ".log");
            builder.redirectErrorStream(true);
            builder.redirectOutput(logFile.toFile());

            Process process = builder.start();
            stats.recordLaunch(appId);
            running.put(appId, process);
            startedAt.put(appId, System.currentTimeMillis());
            log("success", appId, "launched " + app.displayName());

            Thread watcher = new Thread(() -> {
                int exitCode = 0;
                try { exitCode = process.waitFor(); }
                catch (InterruptedException ignored) {}
                long seconds = (System.currentTimeMillis() - startedAt.getOrDefault(appId, 0L)) / 1000;
                running.remove(appId, process);
                startedAt.remove(appId);
                stats.addSessionSeconds(appId, seconds);
                if (exitCode != 0) {
                    log("error", appId, "exited " + exitCode + "; log at " + logFile);
                } else {
                    try { Files.deleteIfExists(logFile); } catch (IOException ignored) {}
                }
                pushState();
            }, "bigm-watch-" + appId);
            watcher.setDaemon(true);
            watcher.start();
            pushState();
        } catch (IOException failure) {
            log("error", appId, "launch failed: " + failure.getMessage());
        }
    }

    private void quitApp(String appId) {
        Process process = running.remove(appId);
        if (process != null) {
            process.destroy();
            log("info", appId, "quit " + appId);
        }
        pushState();
    }

    private void installLatest(String appId) {
        Catalog.AppDef app = findApp(appId);
        if (app == null) return;
        String folder = config.defaultInstallFolder();
        if (folder == null) {
            folder = root.getParent() != null ? root.getParent().toString() : root.toString();
            config.setDefaultInstallFolder(folder);
        }
        Path destination = Path.of(folder).resolve(appId);
        config.setInstallDir(appId, destination.toString());
        installDirs.put(appId, destination);
        updates.installAppUpdate(app, destination);
        pushState();
    }

    private void revealApp(String appId) {
        Path dir = installDirs.get(appId);
        if (dir == null) return;
        try {
            new ProcessBuilder("/usr/bin/open", dir.toString()).start();
        } catch (IOException failure) {
            log("error", appId, "reveal failed: " + failure.getMessage());
        }
    }

    private void scanFolder(String folderPath) {
        if (folderPath == null || folderPath.isBlank()) return;
        updates.runBackground(() -> {
            Path root = Path.of(folderPath);
            if (!Files.isDirectory(root)) return;
            var hits = ScanEngine.scan(root, visibleApps(),
                    app -> catalog.profilesFor(app.id()),
                    id -> installDirs.containsKey(id));
            for (ScanEngine.Hit hit : hits) {
                config.setInstallDir(hit.appId(), hit.installDir().toString());
                installDirs.put(hit.appId(), hit.installDir());
                log("success", hit.appId(), "auto-detected at " + hit.installDir());
            }
            pushState();
        });
    }

    private void activate(String key) {
        String error = ProductGate.activate(key);
        if (error == null) {
            activated = true;
            log("success", null, "activated");
        } else {
            log("error", null, "activation failed: " + error);
        }
        pushState();
    }

    // ── LauncherState.Listener ───────────────────────────────────────────

    @Override
    public void appStatus(String appId, LauncherState.AppStatus status) {
        pushState();
    }

    @Override
    public void downloadMessage(String message) {
        pushState();
    }

    @Override
    public void selfState(boolean available, String tag, String message) {
        pushState();
    }

    // ── UpdateManager.Ui ─────────────────────────────────────────────────

    @Override
    public void log(String level, String appId, String message) {
        String stamp = java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        events.add(stamp + "  " + level.toUpperCase() + "  "
                + (appId == null ? "-" : appId) + "  " + message);
        while (events.size() > 400) events.remove(0);
    }

    public List<String> events() { return events; }

    // ── Helpers ──────────────────────────────────────────────────────────

    private List<Catalog.AppDef> visibleApps() {
        List<Catalog.AppDef> all = new ArrayList<>(catalog.apps());
        all.addAll(config.additionalApps());
        return all;
    }

    private Catalog.AppDef findApp(String id) {
        return visibleApps().stream().filter(a -> a.id().equals(id)).findFirst().orElse(null);
    }

    private List<Catalog.Profile> profilesFor(Catalog.AppDef app) {
        List<Catalog.Profile> profiles = catalog.profilesFor(app.id());
        if (!profiles.isEmpty()) return profiles;
        return config.additionalProfilesFor(app.id());
    }

    private boolean isInstalled(String appId) {
        return installDirs.containsKey(appId);
    }

    Path installDirFor(Catalog.AppDef app) {
        String override = config.installDir(app.id());
        if (override != null && Files.isDirectory(Path.of(override))) return Path.of(override);
        for (Catalog.Profile profile : profilesFor(app)) {
            List<String> paths = new ArrayList<>(profile.candidates());
            if (profile.target() != null) paths.add(profile.target());
            for (String candidate : paths) {
                String expanded = expand(candidate);
                if (expanded == null) continue;
                Path p = Path.of(expanded);
                if (Files.exists(p)) return p.toAbsolutePath().normalize().getParent();
            }
        }
        return null;
    }

    private String expand(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return raw.replace("${HOME}", Paths.realHome().toString());
    }

    private Double parseProgress(String message) {
        if (message == null) return null;
        int pct = message.lastIndexOf('%');
        if (pct < 0) return null;
        int start = pct - 1;
        while (start >= 0 && Character.isDigit(message.charAt(start))) start--;
        if (start == pct - 1) return null;
        try {
            return Integer.parseInt(message.substring(start + 1, pct)) / 100.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record RGB(double r, double g, double b) {}

    private RGB parseHex(String hex) {
        String clean = hex == null ? "" : hex.trim();
        if (clean.startsWith("#")) clean = clean.substring(1);
        if (clean.length() != 6) return new RGB(143, 188, 111);
        try {
            return new RGB(
                    Integer.parseInt(clean.substring(0, 2), 16),
                    Integer.parseInt(clean.substring(2, 4), 16),
                    Integer.parseInt(clean.substring(4, 6), 16));
        } catch (NumberFormatException e) {
            return new RGB(143, 188, 111);
        }
    }

    private String categoryName(String raw) {
        return switch (raw == null ? "" : raw) {
            case "streaming" -> "Streaming";
            case "remote-play" -> "Remote Play";
            case "browser" -> "Browser";
            case "minecraft" -> "Minecraft";
            case "emulator" -> "Emulators";
            case "games" -> "Games";
            case "development" -> "Development";
            default -> raw == null || raw.isBlank() ? "Apps" : raw;
        };
    }

    // Test-support helpers (invoked via reflection by the harness)
    void populateInstallDirs() {
        for (Catalog.AppDef app : visibleApps()) {
            Path dir = installDirFor(app);
            if (dir != null) installDirs.put(app.id(), dir);
        }
    }
    void putInstallDir(String id, Path dir) { installDirs.put(id, dir); }

    Path getInstallDir(String appId) { return installDirs.get(appId); }
    boolean isAppRunning(String appId) { return running.containsKey(appId); }
}
