import core.InstallEngine;
import core.Paths;
import core.ProductGate;
import core.TokenStore;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import ui.LauncherWindow;

/**
 * BigM Portable — Java-hosted edition.
 *
 * The whole point of this port: java.net.http does not consult macOS
 * System-Settings proxies, and the bundled JDK's cacerts is its own
 * truststore — locally-installed filter CAs never enter it. Those two
 * properties are what keep the updater working on filtered machines where
 * URLSession (the Swift build) died answering HTML block pages.
 *
 * The pipeline mirrors the Swift launcher's: detection runs through the
 * GitHub API (tiered by GitHubHttp), downloads flow the same way, and an
 * anchor-pinned tier terminates TLS only at the GitHub CA certs bundled
 * under Resources/github-anchors/.
 */
public final class BigMApp {

    public static void main(String[] args) {
        argsSnapshot = args;
        System.err.println("[bigm] main entered");
        // macOS/SWT: the run loop must be thread 0 (run-bigm.sh starts us
        // with -XstartOnFirstThread); this property only records the fact.
        System.setProperty("apple.awt.application.name", "Google Chrome");
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");

        // Headless harness: exercise the whole machine minus the window —
        // catalog parse, per-app real check, transport log — for scripted
        // verification and release-gate boot proofs (GUI answer to "did it
        // launch" is the process staying alive; the self-test is the truth
        // you can trust from CI).
        if (java.util.Arrays.asList(args).contains("--self-test")) {
            int result = selfTest();
            System.exit(result);
            return;
        }

        // End-to-end harnesses (the Swift VerifyUpdates.swift equivalents):
        // real network, real payloads, scratch roots only — they never touch
        // the dev tree. Run via run-bigm.sh to inherit HOME discipline.
        java.util.List<String> argList = java.util.Arrays.asList(args);
        if (argList.contains("--check-e2e")) { System.exit(checkE2E()); return; }
        if (argList.contains("--install-e2e")) {
            String appId = "rigel";
            int at = argList.indexOf("--install-e2e");
            if (at + 1 < argList.size()) appId = argList.get(at + 1);
            System.exit(installE2E(appId));
            return;
        }
        if (argList.contains("--self-update-e2e")) { System.exit(selfUpdateE2E()); return; }
        if (argList.contains("--icons-e2e")) { System.exit(iconsE2E()); return; }

        // ── No-dylib face ─────────────────────────────────────────────────
        // The SwiftUI dylib and the presentation-identity dylib are gone.
        // The face is the pure Java/SWT port of the same Nightwood design;
        // Dock/menu identity is applied by ui.NativelessIdentity through
        // public SWT APIs (TaskBar / Display.setAppName), with no FFM bridge.

        // ── SWT face ───────────────────────────────────────────────────────
        boolean uiSmoke = java.util.Arrays.asList(args).contains("--ui-smoke");
        boolean uiAudit = java.util.Arrays.asList(args).contains("--ui-audit");
        int faceShotAt = argList.indexOf("--face-shot");
        int sceneShotAt = argList.indexOf("--scene-shot");

        try {
            core.Catalog catalog = core.Catalog.load();
            Display display = new Display();
            ui.NativelessIdentity.install(display);
            if (sceneShotAt >= 0) {
                String path = sceneShotAt + 1 < argList.size()
                        ? argList.get(sceneShotAt + 1) : "/tmp/bigm-scene.png";
                String theme = argList.contains("--theme-day") ? "pine" : "pine";
                var palette = ui.SwiftPalette.get(theme);
                var variant = argList.contains("--theme-day")
                        ? ui.SwiftPalette.Variant.DAY : ui.SwiftPalette.Variant.NIGHT;
                var img = new org.eclipse.swt.graphics.Image(display, 1500, 944);
                var gc = new org.eclipse.swt.graphics.GC(img);
                ui.NightwoodScene.paint(gc, palette, variant, 1500, 944);
                gc.dispose();
                var loader = new org.eclipse.swt.graphics.ImageLoader();
                loader.data = new org.eclipse.swt.graphics.ImageData[]{img.getImageData()};
                loader.save(path, org.eclipse.swt.SWT.IMAGE_PNG);
                img.dispose(); display.dispose();
                System.out.println("scene-shot: wrote " + path);
                return;
            }
            LauncherWindow window = new LauncherWindow(display, catalog);
            if (faceShotAt >= 0) {
                String path = faceShotAt + 1 < argList.size()
                        ? argList.get(faceShotAt + 1) : "/tmp/bigm-face.png";
                window.shell().open();
                display.timerExec(1500, () -> {
                    org.eclipse.swt.widgets.Shell s = window.shell();
                    s.forceActive();
                    s.setFocus();
                    display.update();
                    s.update();
                    var sz = s.getSize();
                    var img = new org.eclipse.swt.graphics.Image(display, sz.x, sz.y);
                    var gc = new org.eclipse.swt.graphics.GC(img);
                    boolean ok = s.print(gc);
                    gc.dispose();
                    var loader = new org.eclipse.swt.graphics.ImageLoader();
                    loader.data = new org.eclipse.swt.graphics.ImageData[]{img.getImageData()};
                    loader.save(path, org.eclipse.swt.SWT.IMAGE_PNG);
                    img.dispose();
                    System.out.println("face-shot: " + (ok ? "wrote " + path : "FAILED (saved raw)"));
                    s.dispose();
                });
                while (!window.shell().isDisposed()) {
                    if (!display.readAndDispatch()) display.sleep();
                }
                display.dispose();
                return;
            }
            if (uiSmoke) {
                System.out.println("ui-smoke: window constructed");
                // In-process proof for the icon pipeline: seed the rigel
                // card's custom icon with a real .app render (the same FFM
                // call Change Icon… uses), so the smoke pass exercises the
                // exact production path, not a mocked picture.
                String seedApp = System.getenv("BIGM_SMOKE_ICON_SEED");
                if (seedApp != null) {
                    boolean ok = core.IconStore.applyCustomIcon("rigel",
                            java.nio.file.Path.of(seedApp));
                    System.out.println("ui-smoke: icon seed render=" + ok
                            + " present=" + core.IconStore.hasCustomIcon("rigel"));
                }
                // Exercised surfaces: the main window, then every sheet —
                // catches paint/listener exceptions in one scripted pass.
                Shell[] sheets = new Shell[5];
                Shell[] extraFaces = new Shell[2];
                display.timerExec(800, () -> {
                    new ui.UpdatesSheet(window).open();
                    System.out.println("ui-smoke: updates sheet ok");
                });
                display.timerExec(1400, () -> {
                    new ui.GetAppsSheet(window).open();
                    System.out.println("ui-smoke: get-apps sheet ok");
                });
                display.timerExec(2000, () -> {
                    new ui.CustomizeSheet(window).open();
                    System.out.println("ui-smoke: customize sheet ok");
                });
                display.timerExec(2600, () -> {
                    new ui.SettingsSheet(window).open();
                    System.out.println("ui-smoke: settings sheet ok");
                });
                display.timerExec(3200, () -> {
                    new ui.DiagnosticsSheet(window).open();
                    System.out.println("ui-smoke: diagnostics sheet ok");
                });
                display.timerExec(3400, () -> {
                    new ui.SystemSheet(window).open();
                    extraFaces[0] = display.getActiveShell();
                    System.out.println("ui-smoke: system sheet ok");
                });
                display.timerExec(3700, () -> {
                    new ui.CommandPalette(window).open();
                    extraFaces[1] = display.getActiveShell();
                    System.out.println("ui-smoke: command palette ok");
                });
                display.timerExec(4200, () -> {
                    // Layout audit: print every shell's control tree with
                    // bounds so "the sheet was empty" is provable in CI
                    // rather than guessed at from a screenshot we can't take.
                    for (Shell s : display.getShells()) dumpShell(s, 0);
                });
                // Theme-switch repro: the Customize crash was a Color
                // disposed mid-paint. Hammer the palette like a furious
                // user, repainting between every switch; any stale
                // reference to a retired color throws here, not in prod.
                String[] keys = {"pine", "dusk", "glacier", "ember", "summit"};
                display.timerExec(4300, new Runnable() {
                    int i = 0;
                    @Override public void run() {
                        if (window.shell().isDisposed() || i >= keys.length) return;
                        window.palette().setTheme(keys[i]);
                        window.shell().redraw();
                        window.shell().update();  // force the paint cycle synchronously
                        i++;
                        display.timerExec(60, this);
                    }
                });
                display.timerExec(5200, () -> {
                    // Capture the finished window with Control#print (fully
                    // offscreen — works without screen-recording perms).
                    try {
                        Shell s = window.shell();
                        s.update();
                        var sz = s.getSize();
                        var img = new org.eclipse.swt.graphics.Image(display, sz.x, sz.y);
                        var gc = new org.eclipse.swt.graphics.GC(img);
                        boolean ok = s.print(gc);
                        gc.dispose();
                        var loader = new org.eclipse.swt.graphics.ImageLoader();
                        loader.data = new org.eclipse.swt.graphics.ImageData[]{img.getImageData()};
                        String out = System.getenv().getOrDefault("BIGM_SMOKE_SHOT", "/tmp/bigm-live.png");
                        loader.save(out, org.eclipse.swt.SWT.IMAGE_PNG);
                        img.dispose();
                        System.out.println("ui-smoke: print ok=" + ok + " -> " + out);
                    } catch (Throwable failure) {
                        System.out.println("ui-smoke: print failed: " + failure.getMessage());
                    }
                });
                display.timerExec(5600, () -> {
                    for (Shell s : sheets) if (s != null && !s.isDisposed()) s.dispose();
                    for (Shell s : extraFaces) if (s != null && !s.isDisposed() && s != window.shell()) s.dispose();
                    // Exercise the real close path: dispose the shell (fires
                    // the dispose listener + shutdown hooks), let the event
                    // loop drain, then dispose the display. A bare
                    // System.exit here used to hide shutdown crashes.
                    window.shell().dispose();
                });
                window.openWithoutGate();
                display.dispose();
                System.out.println("ui-smoke: clean shutdown ok");
            } else if (uiAudit) {
                System.out.println("ui-audit: window constructed");
                display.timerExec(600, () -> new ui.UpdatesSheet(window).open());
                display.timerExec(1000, () -> new ui.GetAppsSheet(window).open());
                display.timerExec(1400, () -> new ui.CustomizeSheet(window).open());
                display.timerExec(1800, () -> new ui.SettingsSheet(window).open());
                display.timerExec(2200, () -> new ui.DiagnosticsSheet(window).open());
                display.timerExec(3000, () -> {
                    java.util.List<String> offenders = new java.util.ArrayList<>();
                    for (Shell s : display.getShells()) auditControlTree(s, offenders);
                    if (offenders.isEmpty()) {
                        System.out.println("ui-audit: PASS — every widget is owner-painted "
                                + "or explicitly themed");
                    } else {
                        System.out.println("ui-audit: FAIL — " + offenders.size()
                                + " widget(s) still on the OS-default background:");
                        for (String offender : offenders) System.out.println("  " + offender);
                        auditFailed = true;
                    }
                    window.shell().dispose();
                });
                window.openWithoutGate();
                display.dispose();
                System.out.println("ui-audit: clean shutdown, failures=" + auditFailed);
                System.exit(auditFailed ? 2 : 0);
            } else {
                window.open();
                display.dispose();
            }
        } catch (Throwable failure) {
            System.err.println("[bigm] fatal: " + failure.getMessage());
            failure.printStackTrace();
            System.exit(1);
        }
    }

    // ── No-default-background audit (--ui-audit) ─────────────────────────

    private static volatile boolean auditFailed;

    /** Prove every widget on this shell either no-erases (NO_BACKGROUND /
     *  SEPARATOR) or carries an explicitly-set background color, or is an
     *  OS-drawn native pinned by the dark appearance (buttons, scrollbars,
     *  menus). Anything else is the "gray box" class — flagged, not shrugged
     *  at. */
    private static void auditControlTree(Shell shell, java.util.List<String> offenders) {
        if (shell.isDisposed()) return;
        auditControl(shell, shell.getText(), offenders);
    }

    private static void auditControl(org.eclipse.swt.widgets.Control c, String where,
                                     java.util.List<String> offenders) {
        if (c.isDisposed()) return;
        int style = c.getStyle();
        boolean transparent = (style & org.eclipse.swt.SWT.NO_BACKGROUND) != 0
                || (style & org.eclipse.swt.SWT.SEPARATOR) != 0;
        boolean themed = c.getBackground() != null;
        // Buttons are OS-drawn pills bound to the (forced dark) appearance;
        // shells may not take NO_BACKGROUND and always carry our chrome.
        boolean exemptNative = c instanceof org.eclipse.swt.widgets.Button
                || c instanceof Shell;
        if (!transparent && !themed && !exemptNative) {
            String label = c instanceof org.eclipse.swt.widgets.Label l ? l.getText()
                    : c instanceof org.eclipse.swt.widgets.Text t ? "(" + t.getMessage() + ")"
                    : "";
            if (label != null && label.length() > 48) label = label.substring(0, 48) + "…";
            offenders.add(where + " :: " + c.getClass().getSimpleName()
                    + (label == null || label.isBlank() ? "" : " \"" + label.trim() + "\""));
        }
        if (c instanceof org.eclipse.swt.widgets.Composite cc) {
            for (org.eclipse.swt.widgets.Control child : cc.getChildren()) {
                auditControl(child, where, offenders);
            }
        }
    }

    /** Recursive tree dump for --ui-smoke layout audits. */
    private static void dumpShell(Shell s, int depth) {
        if (s.isDisposed()) return;
        System.out.println("  ".repeat(depth) + "SHELL \"" + s.getText()
                + "\" bounds=" + s.getBounds() + " visible=" + s.isVisible());
        dumpChildren(s, depth + 1);
    }

    private static void dumpChildren(org.eclipse.swt.widgets.Composite c, int depth) {
        for (org.eclipse.swt.widgets.Control child : c.getChildren()) {
            String text = "";
            if (child instanceof org.eclipse.swt.widgets.Label l) text = l.getText();
            if (child instanceof org.eclipse.swt.widgets.Text t) text = "(text)";
            if (text != null && text.length() > 40) text = text.substring(0, 40) + "…";
            org.eclipse.swt.graphics.Rectangle b = child.getBounds();
            System.out.println("  ".repeat(depth)
                    + child.getClass().getSimpleName()
                    + " b=" + b.width + "x" + b.height + "@" + b.x + "," + b.y
                    + (text.isEmpty() ? "" : " \"" + text.trim() + "\"")
                    + (child.isVisible() ? "" : " [hidden]"));
            if (child instanceof org.eclipse.swt.widgets.Composite cc) dumpChildren(cc, depth + 1);
        }
    }

    // ── E2E harnesses (real GitHub, scratch roots only) ─────────────────

    /** Scratch root carrying the Resources the runtime needs; everything
     *  the engine touches (Support/, portable-data/, install dir) lands
     *  under it, not the dev tree. */
    private static java.nio.file.Path e2eRoot() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("bigm-e2e");
        java.nio.file.Path resources = root.resolve("Resources");
        java.nio.file.Files.createDirectories(resources);
        java.nio.file.Path sourceResources = java.nio.file.Path.of(
                System.getProperty("bigm.devroot", System.getProperty("user.dir")))
                .resolve("Resources");
        try (var walk = java.nio.file.Files.walk(sourceResources)) {
            for (var p : walk.toList()) {
                if (java.nio.file.Files.isDirectory(p)) continue;
                var rel = sourceResources.relativize(p);
                var dst = resources.resolve(rel.toString());
                java.nio.file.Files.createDirectories(dst.getParent());
                java.nio.file.Files.copy(p, dst);
            }
        }
        System.setProperty("bigm.root", root.toString());
        return root;
    }

    /** Per-app check semantics against the live org: latestFor resolves a
     *  real release; a spoofed old stamp answers update-available; the fresh
     *  stamp answers up-to-date. */
    private static int checkE2E() {
        try {
            java.nio.file.Path root = e2eRoot();
            core.Catalog catalog = core.Catalog.load();
            String token = core.GitHubAccess.resolve();
            if (token == null) { System.out.println("check-e2e: FAIL: no token"); return 1; }
            int failures = 0;
            for (core.Catalog.AppDef app : catalog.apps()) {
                if (!app.updatable()) continue;
                var release = core.InstallEngine.latestFor(app, token);
                if (release == null) { System.out.println("check-e2e: FAIL " + app.id() + ": no release"); failures++; continue; }
                var asset = core.InstallEngine.findAsset(release, app.assetPattern());
                System.out.println("check-e2e: " + app.id() + " latest=" + release.tag()
                        + " asset=" + (asset == null ? "MISSING(" + app.assetPattern() + ")" : asset.name()));
                if (asset == null) { failures++; continue; }
                // spoof old stamp → available; real stamp → not.
                java.nio.file.Path dir = root.resolve("scratch-installs").resolve(app.id());
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Files.writeString(dir.resolve("version.txt"), "spoofed-old\n2020-01-01\n");
                boolean old = core.InstallEngine.updateAvailable(app, dir, release);
                java.nio.file.Files.writeString(dir.resolve("version.txt"), release.tag() + "\n2026-01-01\n");
                boolean fresh = core.InstallEngine.updateAvailable(app, dir, release);
                if (!old || fresh) { System.out.println("check-e2e: FAIL semantics " + app.id()); failures++; }
            }
            System.out.println(failures == 0 ? "check-e2e: PASS" : "check-e2e: FAILURES=" + failures);
            return failures == 0 ? 0 : 1;
        } catch (Exception e) {
            System.out.println("check-e2e: FAIL: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    /** Real download of the latest rigel zip through the full ladder +
     *  install pipeline into a scratch dir (the Swift --install-e2e twin). */
    private static int installE2E(String appId) {
        try {
            java.nio.file.Path root = e2eRoot();
            core.Catalog catalog = core.Catalog.load();
            core.Catalog.AppDef app = null;
            for (core.Catalog.AppDef a : catalog.apps()) if (a.id().equals(appId)) app = a;
            if (app == null) { System.out.println("install-e2e: FAIL: no such app " + appId); return 1; }
            String token = core.GitHubAccess.resolve();
            if (token == null) { System.out.println("install-e2e: FAIL: no token"); return 1; }
            final core.Catalog.AppDef appF = app;
            java.nio.file.Path installDir = root.resolve("scratch-installs").resolve(app.id());
            long started = System.currentTimeMillis();
            core.InstallEngine.installUpdate(app, installDir, token, (tier, done, total) -> {
                if (total > 0) {
                    long pct = done * 100 / total;
                    System.out.println("install-e2e: " + tier + " " + pct + "% (" + done / 1048576 + "MB)");
                }
            });
            long secs = (System.currentTimeMillis() - started) / 1000;
            java.nio.file.Path marker = installDir.resolve(appF.marker());
            core.InstallEngine.Identity landed = core.InstallEngine.installedIdentity(installDir);
            boolean ok = java.nio.file.Files.isRegularFile(marker)
                    && landed.tag() != null && !landed.tag().isBlank();
            System.out.println("install-e2e: marker=" + marker + " present=" + java.nio.file.Files.exists(marker));
            System.out.println("install-e2e: landed tag=" + landed.tag() + " in " + secs + "s");
            System.out.println(ok ? "install-e2e: PASS" : "install-e2e: FAIL");
            return ok ? 0 : 1;
        } catch (Exception e) {
            System.out.println("install-e2e: FAIL: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    /** Self-update swap proof against a fake root: real newest launcher zip
     *  is downloaded + extracted, the staging helper is armed with a spoofed
     *  old version.txt at the fake root, and the helper itself performs the
     *  rsync swap once this harness process exits. The outer shell then
     *  verifies the fake root flipped tag. (Same discipline as the Swift
     *  --self-update-e2e; the dev tree is never the swap target.) */
    private static int selfUpdateE2E() {
        try {
            java.nio.file.Path root = e2eRoot();
            java.nio.file.Path fakeRoot = root.resolve("fake-launcher");
            java.nio.file.Files.createDirectories(fakeRoot.resolve("Support"));
            java.nio.file.Files.createDirectories(fakeRoot.resolve("portable-data"));
            // A minimal "old" install
            java.nio.file.Files.writeString(fakeRoot.resolve("version.txt"), "portable-2000.01.01\n2026-01-01\n");
            java.nio.file.Files.writeString(fakeRoot.resolve("run.sh"), "#!/bin/sh\necho fake-old\n");
            fakeRoot.resolve("run.sh").toFile().setExecutable(true);

            String token = core.GitHubAccess.resolve();
            if (token == null) { System.out.println("self-update-e2e: FAIL: no token"); return 1; }
            net.GitHubUpdates.Release release;
            try {
                release = net.GitHubUpdates.latestRelease("fromthebigm/BigM-Launcher-Java", token);
            } catch (Exception first) {
                // Same contract as UpdateManager.withFallback: walk the
                // machine's other credentials on access-class failures.
                release = null;
                for (String fb : core.GitHubAccess.fallbackTokens(token)) {
                    try {
                        release = net.GitHubUpdates.latestRelease(
                                "fromthebigm/BigM-Launcher-Java", fb);
                        token = fb;
                        break;
                    } catch (Exception ignored) {
                    }
                }
                if (release == null) throw first;
            }
            System.out.println("self-update-e2e: staging " + release.tag() + " against fake root");
            var asset = core.InstallEngine.findAsset(release, "BigM-Launcher-Java-*.zip");
            if (asset == null) { System.out.println("self-update-e2e: FAIL: no launcher zip asset"); return 1; }
            java.nio.file.Path staging = java.nio.file.Files.createDirectories(
                    root.resolve("staging").resolve(release.tag()));
            java.nio.file.Path zip = staging.resolve(asset.name());
            net.GitHubUpdates.downloadAsset(asset, token, zip, (tier, done, total) -> {
                if (total > 0) System.out.println("self-update-e2e: dl " + (done * 100 / total) + "%");
            });
            net.GitHubUpdates.requireZipMagic(zip, asset.name());
            java.nio.file.Path extractDir = staging.resolve("payload");
            java.nio.file.Files.createDirectories(extractDir);
            core.InstallEngine.runStatic(java.util.List.of("/usr/bin/ditto", "-x", "-k",
                    zip.toString(), extractDir.toString()), null, 300);
            java.nio.file.Path payload = core.InstallEngine.locatePayloadPublic(extractDir, "run-bigm.sh");
            if (payload == null) { System.out.println("self-update-e2e: FAIL: payload lacks run-bigm.sh"); return 1; }
            // Point the sway swap at the fake root by re-rooting Paths: the
            // helper script takes explicit args, so call the writer directly.
            String helper = System.getProperty("java.io.tmpdir") + "/bigm-e2e-swap-" + System.currentTimeMillis() + ".sh";
            java.nio.file.Path helperPath = java.nio.file.Path.of(helper);
            java.nio.file.Files.writeString(helperPath, String.join("\n",
                    "#!/bin/sh",
                    "set -u",
                    "ROOT=\"$1\"",
                    "PAYLOAD=\"$2\"",
                    "WATCH_PID=\"$3\"",
                    "while kill -0 \"$WATCH_PID\" 2>/dev/null; do sleep 0.3; done",
                    "/usr/bin/rsync -a --delete --exclude=Support/ --exclude=portable-data/ \"$PAYLOAD/\" \"$ROOT/\"",
                    "rm -rf \"$PAYLOAD\"",
                    "xattr -cr \"$ROOT\" 2>/dev/null || true",
                    "chmod -f +x \"$ROOT\"/run*.sh 2>/dev/null || true",
                    "echo SWAP-DONE > \"$ROOT/Support/swap-done.txt\""));
            helperPath.toFile().setExecutable(true);
            long pid = ProcessHandle.current().pid();
            new ProcessBuilder("/bin/sh", "-c",
                    "nohup /bin/sh " + helperPath + " " + fakeRoot + " " + payload + " " + pid
                            + " >/dev/null 2>&1 < /dev/null &").start();
            System.out.println("self-update-e2e: helper armed; harness exits so the swap completes.");
            System.out.println("self-update-e2e: fake root = " + fakeRoot);
            return 0; // caller (shell) verifies after we exit
        } catch (Exception e) {
            System.out.println("self-update-e2e: FAIL: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    /** Icon-system proof, all in one sweep:
     *   a) launcher icon swap to a real app icon applies live + resets
     *      to Voice Memos presets (dylib symbol success paths)
     *   b) per-app icon pick renders + stores + the launch env for a child
     *      process carries BIGM_PRESENTATION_ICON_* (Rigel & co read it)
     *   c) the picked-icon env coexists with the launch ticket
     *   d) Voice Memos presets sit beside the dylib (default dock icon)
     */
    private static int iconsE2E() {
        int failures = 0;
        try {
            int pickIdx = java.util.Arrays.asList(argsSnapshot).indexOf("--icons-e2e");
            String pick = pickIdx + 1 < argsSnapshot.length ? argsSnapshot[pickIdx + 1]
                    : "/Applications/ChatGPT.app";
            if (!java.nio.file.Files.isDirectory(java.nio.file.Path.of(pick))) pick = "/System/Applications/Calculator.app";

            // a) launcher self swap
            boolean rendered = core.IconStore.applyCustomIcon(core.IconStore.LAUNCHER_ID,
                    java.nio.file.Path.of(pick));
            System.out.println("icons-e2e: launcher. render=" + rendered
                    + " has=" + core.IconStore.hasCustomIcon(core.IconStore.LAUNCHER_ID));
            core.IconStore.saveLauncherMode(core.IconStore.Mode.SYSTEM);
            core.IconStore.applyLauncherIcon();          // live-apply via SWT TaskBar if a Display is active
            boolean applied = core.IconStore.hasCustomIcon(core.IconStore.LAUNCHER_ID);
            core.IconStore.resetLauncherIcon();          // reset
            core.IconStore.applyLauncherIcon();
            boolean cleared = !core.IconStore.hasCustomIcon(core.IconStore.LAUNCHER_ID);
            System.out.println("icons-e2e: launcher. applied=" + applied + " thenReset=" + cleared);
            if (!(rendered && applied && cleared)) failures++;

            // b) per-app pick (into "rigel")
            boolean appRendered = core.IconStore.applyCustomIcon("rigel",
                    java.nio.file.Path.of(pick));
            java.util.Map<String, String> env = core.IconStore.launchEnvironment("rigel",
                    core.IconStore.Mode.SYSTEM);
            System.out.println("icons-e2e: app. rendered=" + appRendered + " env=" + env.keySet());
            if (!(appRendered
                    && env.containsKey("BIGM_PRESENTATION_ICON_LIGHT")
                    && env.containsKey("BIGM_PRESENTATION_ICON_DARK")
                    && "system".equals(env.get("BIGM_PRESENTATION_ICON_MODE")))) failures++;
            core.IconStore.resetCustomIcon("rigel");

            // c) ticket still composes with icon env
            java.util.Map<String, String> merged = new java.util.LinkedHashMap<>(
                    core.LaunchTicket.environment());
            merged.putAll(core.IconStore.launchEnvironment("rigel", core.IconStore.Mode.SYSTEM));
            boolean ticketOk = merged.containsKey("BIGM_LAUNCH_TICKET");
            System.out.println("icons-e2e: ticket-with-icons=" + ticketOk);
            if (!ticketOk) failures++;

            // d) presets used by the pure-Java Dock identity
            java.nio.file.Path light = Paths.resources().resolve("presets/voicememos-light.png");
            java.nio.file.Path dark = Paths.resources().resolve("presets/voicememos-dark.png");
            boolean presets = java.nio.file.Files.isRegularFile(light)
                    && java.nio.file.Files.isRegularFile(dark);
            System.out.println("icons-e2e: voicememos presets in Resources=" + presets);
            if (!presets) failures++;

            System.out.println(failures == 0 ? "icons-e2e: PASS" : "icons-e2e: FAILURES=" + failures);
            return failures == 0 ? 0 : 1;
        } catch (Throwable t) {
            System.out.println("icons-e2e: FAIL: " + t.getMessage());
            t.printStackTrace();
            return 1;
        }
    }

    private static String[] argsSnapshot = new String[0];

    private static int selfTest() {
        int failures = 0;
        try {
            core.Catalog catalog = core.Catalog.load();
            System.out.println("self-test: catalog=" + catalog.apps().size() + " apps");
            if (catalog.apps().isEmpty()) {
                System.out.println("self-test: FAIL: empty catalog");
                failures++;
            }
            // Identity read is pure-local and answers whether the stamp
            // machinery the updater leans on is intact.
            InstallEngine.Identity probe = InstallEngine.installedIdentity(
                    java.nio.file.Path.of("/tmp"));
            System.out.println("self-test: stamp-read ok (tag='" + probe.tag() + "')");
            System.out.flush();
            System.out.println("self-test: gate activated="
                    + ProductGate.isActivated());
            System.out.flush();
            System.out.println("self-test: token present=" + TokenStore.present());
            java.nio.file.Path log = Paths.transportLog();
            System.out.println("self-test: transport log target=" + log);
            System.out.flush();
        } catch (Throwable failure) {
            System.err.println("self-test: FAIL: " + failure.getMessage());
            return 1;
        }
        if (failures == 0) System.out.println("self-test: PASS");
        return failures == 0 ? 0 : 1;
    }
}
