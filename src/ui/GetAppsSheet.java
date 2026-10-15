package ui;

import core.Catalog;
import core.LauncherState;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The Get Apps sheet (the Swift GetAppsView): everything that puts a new
 * app on this machine — install-from-releases rows for catalog apps not
 * usable right now, the default install folder chooser, folder auto-detect
 * (walks a folder for each app's launch file), and the Add Custom App /
 * Add Website sheets.
 */
public final class GetAppsSheet extends SheetShell {

    private volatile boolean scanning;

    public GetAppsSheet(LauncherWindow window) {
        super(window, "Get Apps", 720, 600);
    }

    @Override protected void fill(Shell sheet) {
        sheet.setLayout(new GridLayout(1, false));
        Composite body = scrolledBody(sheet);

        Composite header = new Composite(body, SWT.NO_BACKGROUND);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout hl = new GridLayout(3, false);
        hl.marginWidth = 0;
        hl.marginHeight = 0;
        header.setLayout(hl);
        Composite titles = new Composite(header, SWT.NO_BACKGROUND);
        GridLayout tl = new GridLayout(1, false);
        tl.marginWidth = 0;
        tl.marginHeight = 0;
        titles.setLayout(tl);
        titles.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Label title = new Label(titles, SWT.NO_BACKGROUND);
        title.setText("Get Apps");
        title.setFont(palette.libTitle);
        title.setForeground(palette.textPrimary);
        Label sub = new Label(titles, SWT.NO_BACKGROUND);
        sub.setText("Install launchers, detect existing copies, or add your own.");
        sub.setFont(palette.caption);
        sub.setForeground(palette.textMuted);
        GlassButton done = new GlassButton(header, palette, "Done", GlassButton.Style.TOOL);
        done.addListener(SWT.Selection, e -> sheet.close());

        // ── Install from Releases ──
        List<Catalog.AppDef> installable = new ArrayList<>();
        for (Catalog.AppDef app : window.visibleApps()) {
            if (app.updatable() && !window.isInstalled(app)) installable.add(app);
        }
        if (!installable.isEmpty()) {
            Composite installCard = card(body, "Install from Releases", Glass.Glyph.DOWNLOAD, palette.accent());
            for (Catalog.AppDef app : installable) buildInstallRow(installCard, app);
        }

        // ── Default install folder ──
        Composite folder = card(body, "Default Install Folder", Glass.Glyph.FOLDER, palette.accent());
        bodyText(folder, "Install… downloads each app's latest release into folders inside "
                + "this location. It is chosen the first time you install, or here.");
        Composite folderRow = new Composite(folder, SWT.NO_BACKGROUND);
        folderRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout fr = new GridLayout(2, false);
        fr.marginWidth = 0;
        fr.marginHeight = 0;
        folderRow.setLayout(fr);
        Label path = new Label(folderRow, SWT.NO_BACKGROUND);
        String current = window.config().defaultInstallFolder();
        path.setText(current == null ? "Chosen on first install" : current);
        path.setFont(palette.mono);
        path.setForeground(current == null ? palette.textMuted : palette.textPrimary);
        path.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GlassButton choose = new GlassButton(folderRow, palette, "Choose…", GlassButton.Style.TOOL);
        choose.addListener(SWT.Selection, e -> {
            DirectoryDialog dialog = new DirectoryDialog(sheet);
            dialog.setText("Choose the default install folder");
            dialog.setMessage("New apps install into folders inside this location.");
            String picked = dialog.open();
            if (picked == null) return;
            window.config().setDefaultInstallFolder(picked);
            path.setText(picked);
            path.setForeground(palette.textPrimary);
        });

        // ── Auto-detect ──
        Composite detect = card(body, "Already On This Mac", Glass.Glyph.CHECK, palette.accent());
        bodyText(detect, "Apps already inside a folder you pick are found by their launch file "
                + "(run-rigel.sh, moonlight_launch.py, Chiaki.app…) and wired in automatically. "
                + "Working setups are never rewritten.");
        GlassButton scan = new GlassButton(detect, palette, "Choose a Folder & Scan…",
                GlassButton.Style.TOOL);
        scan.addListener(SWT.Selection, e -> autoDetect(scan));

        // ── Custom apps ──
        Composite custom = card(body, "Your Own Apps", Glass.Glyph.PLUS, palette.accent());
        bodyText(custom, "Add any executable, script, Python entrypoint, or .app as a card in "
                + "the library — or pin a website; it opens in the Rigel browser.");
        Composite customRow = new Composite(custom, SWT.NO_BACKGROUND);
        customRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout cr = new GridLayout(2, false);
        cr.marginWidth = 0;
        cr.marginHeight = 0;
        cr.horizontalSpacing = 8;
        customRow.setLayout(cr);
        GlassButton addApp = new GlassButton(customRow, palette, "Add Custom App…",
                GlassButton.Style.TOOL);
        addApp.addListener(SWT.Selection, e -> new AddAppSheet(window).open());
        GlassButton addSite = new GlassButton(customRow, palette, "Add Website…",
                GlassButton.Style.TOOL);
        addSite.addListener(SWT.Selection, e -> new AddWebsiteSheet(window).open());

        body.layout(true, true);
        Object scroller = body.getData("scroller");
        if (scroller instanceof org.eclipse.swt.custom.ScrolledComposite sc) {
            sc.setMinSize(body.computeSize(sc.getClientArea().width, SWT.DEFAULT));
        }
    }

    private void buildInstallRow(Composite parent, Catalog.AppDef app) {
        Composite row = new Composite(parent, SWT.NO_BACKGROUND);
        row.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout rl = new GridLayout(4, false);
        rl.marginWidth = 0;
        rl.marginHeight = 3;
        rl.horizontalSpacing = 10;
        row.setLayout(rl);

        // Same artwork contract as the library cards: the thematic category
        // emblem chip (vector-drawn, no bitmap letters).
        Composite icon = new Composite(row, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        icon.addPaintListener(e -> {
            var b = icon.getClientArea();
            int size = Math.min(b.width, b.height);
            Glass.paintCategoryChip(e.gc, window.swtDisplay(), b.x, b.y, size,
                    UiPalette.hex(app.tintHex()), AppCard.categoryGlyph(app.category()));
        });
        GridData id = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        id.widthHint = 32;
        id.heightHint = 32;
        icon.setLayoutData(id);

        Composite texts = new Composite(row, SWT.NO_BACKGROUND);
        GridLayout tl = new GridLayout(1, false);
        tl.marginWidth = 0;
        tl.marginHeight = 0;
        texts.setLayout(tl);
        texts.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Label name = new Label(texts, SWT.NO_BACKGROUND);
        name.setText(app.displayName());
        name.setFont(palette.body);
        name.setForeground(palette.textPrimary);
        Label summary = new Label(texts, SWT.WRAP | SWT.NO_BACKGROUND);
        summary.setText(app.summary());
        summary.setFont(palette.caption);
        summary.setForeground(palette.textMuted);
        summary.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        Label status = new Label(texts, SWT.NO_BACKGROUND);
        status.setFont(palette.caption);
        status.setForeground(palette.warnFg);

        GlassButton install = new GlassButton(row, palette, "Install…", GlassButton.Style.TOOL);
        install.addListener(SWT.Selection, e -> window.installLatest(app));

        // Live row: reflect the install worker's status
        Runnable[] refreshRef = new Runnable[1];
        refreshRef[0] = () -> {
            LauncherState.AppStatus state = window.updates().state().app(app.id());
            boolean busy = state.phase() == LauncherState.AppStatus.Phase.DOWNLOADING
                    || state.phase() == LauncherState.AppStatus.Phase.INSTALLING
                    || state.phase() == LauncherState.AppStatus.Phase.CHECKING;
            install.setEnabled(!busy);
            status.setText(state.message() == null ? "" : state.message());
            if (window.isInstalled(app) && !busy
                    && state.phase() == LauncherState.AppStatus.Phase.UP_TO_DATE) {
                install.setEnabled(false);
                status.setForeground(palette.okFg);
            }
            row.layout(true, true);
        };
        Runnable r = refreshRef[0];
        r.run();
        // Event-driven refresh instead of a per-row poll: repaint when
        // install status lands, and when this sheet closes the hook stops.
        core.LauncherState.Listener listener = new core.LauncherState.Listener() {
            @Override public void appStatus(String appId, core.LauncherState.AppStatus status) {
                if (!appId.equals(app.id())) return;
                window.swtDisplay().asyncExec(() -> { if (!row.isDisposed()) r.run(); });
            }
            @Override public void downloadMessage(String message) {}
            @Override public void selfState(boolean a, String t, String m) {}
        };
        window.updates().state().add(listener);
        row.addDisposeListener(e -> window.updates().state().remove(listener));
    }

    /** Walk a chosen folder for each catalog app's launch marker; wire the
     *  parent folders that exist as per-app install dirs. Runs off the UI
     *  thread so a big folder cannot beachball the window. */
    private void autoDetect(GlassButton scan) {
        DirectoryDialog dialog = new DirectoryDialog(sheet);
        dialog.setText("Auto-detect installed apps");
        dialog.setMessage("Pick the folder that holds your apps. Every launcher found inside "
                + "is wired in automatically.");
        String root = dialog.open();
        if (root == null || scanning) return;
        scanning = true;
        scan.setEnabled(false);
        scan.setText("Scanning…");
        Thread worker = new Thread(() -> {
            List<String> applied = new ArrayList<>();
            List<String> already = new ArrayList<>();
            for (Catalog.AppDef app : window.visibleApps()) {
                if (window.isInstalled(app)) {
                    already.add(app.id());
                    continue;
                }
                Path found = findMarker(Path.of(root), app, window.profilesFor(app));
                if (found != null) {
                    window.config().setInstallDir(app.id(), found.toString());
                    applied.add(app.id() + " → " + found);
                }
            }
            scanning = false;
            window.swtDisplay().asyncExec(() -> {
                scan.setEnabled(true);
                scan.setText("Choose a Folder & Scan…");
                window.refreshDiscovery();
                String summary;
                if (applied.isEmpty()) {
                    summary = "No catalog apps were found inside \"" + root + "\"."
                            + (already.isEmpty() ? "" : "\nAlready wired: " + String.join(", ", already));
                } else {
                    summary = "Wired in:\n" + String.join("\n", applied)
                            + (already.isEmpty() ? "" : "\n\nAlready working (left untouched): "
                            + String.join(", ", already));
                }
                MessageBox box = new MessageBox(sheet, SWT.ICON_INFORMATION | SWT.OK);
                box.setText("Auto-detect");
                box.setMessage(summary);
                box.open();
            });
        }, "bigm-scan");
        worker.setDaemon(true);
        worker.start();
    }

    /** Old-launcher parity scan: find an app's install inside a picked
     *  folder by (a) its marker file, (b) any candidate's basename
     *  (run-rigel.sh, moonlight_launch.py…), (c) a directory named after
     *  the app id containing a run*.sh, or (d) any "<Id>.app" bundle.
     *  Deepest-safe: 5 levels, case-insensitive names. Returns the parent
     *  dir to wire as the install location. */
    static Path findMarker(Path root, Catalog.AppDef app, List<Catalog.Profile> profiles) {
        java.util.Set<String> names = new java.util.HashSet<>();
        addName(names, app.marker());
        for (Catalog.Profile profile : profiles) {
            addName(names, basenameOf(profile.target()));
            for (String candidate : profile.candidates()) addName(names, basenameOf(candidate));
        }
        String idLower = app.id().toLowerCase();
        if (names.isEmpty() && idLower.isBlank()) return null;

        // Pass 1: exact marker/candidate filename match, case-insensitive
        try (Stream<Path> walk = Files.walk(root, 5)) {
            Path hit = walk.filter(Files::isRegularFile)
                    .filter(p -> names.contains(p.getFileName().toString().toLowerCase()))
                    .findFirst()
                    .map(Path::getParent)
                    .orElse(null);
            if (hit != null) return hit;
        } catch (IOException e) {
            // continue to dir-name passes
        }
        // Pass 2: a folder named after the app id (any case) holding a
        // runnable — the way the old Python scanner found launchers.
        try (Stream<Path> walk = Files.walk(root, 4)) {
            Path hitDir = walk.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().toLowerCase().contains(idLower))
                    .filter(p -> containsLauncher(p, idLower))
                    .findFirst()
                    .orElse(null);
            if (hitDir != null) return hitDir;
        } catch (IOException ignored) {
        }
        // Pass 3: "<App>.app" bundle anywhere (case-insensitive contains).
        try (Stream<Path> walk = Files.walk(root, 4)) {
            return walk.filter(Files::isDirectory)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".app") && n.contains(idLower);
                    })
                    .map(Path::getParent)
                    .findFirst()
                    .orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean containsLauncher(Path dir, String idLower) {
        try (Stream<Path> kids = Files.list(dir)) {
            return kids.anyMatch(k -> {
                String n = k.getFileName().toString().toLowerCase();
                return (n.startsWith("run") && n.endsWith(".sh"))
                        || n.equals("run.sh")
                        || n.endsWith(".app")
                        || n.contains(idLower) && n.endsWith(".py");
            });
        } catch (IOException e) {
            return false;
        }
    }

    private static void addName(java.util.Set<String> names, String raw) {
        if (raw != null && !raw.isBlank()) names.add(raw.trim().toLowerCase());
    }

    private static String basenameOf(String path) {
        if (path == null || path.isBlank()) return null;
        int slash = path.replace('\\', '/').lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
