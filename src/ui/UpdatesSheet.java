package ui;

import core.Catalog;
import core.InstallEngine;
import core.LauncherState;
import core.Paths;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Updates sheet, mirroring the Swift UpdatesView: "This Install"
 * identity card, the launcher's own update state with Install & Restart,
 * the download progress line, one row per managed app (Check / Update /
 * progress %), the Update All header action, and a short GitHub-access
 * status (full token editing lives in Settings per the owner's sheet map).
 */
public final class UpdatesSheet extends SheetShell {

    private final List<Runnable> refreshHooks = new ArrayList<>();

    public UpdatesSheet(LauncherWindow window) {
        super(window, "Updates", 680, 640);
    }

    @Override protected void fill(Shell sheet) {
        sheet.setLayout(new GridLayout(1, false));
        Composite body = scrolledBody(sheet);

        // Header row
        Composite header = new Composite(body, SWT.NO_BACKGROUND);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout hl = new GridLayout(4, false);
        hl.marginWidth = 0;
        hl.marginHeight = 0;
        header.setLayout(hl);
        Label title = new Label(header, SWT.NO_BACKGROUND);
        title.setText("Updates");
        title.setFont(palette.libTitle);
        title.setForeground(palette.textPrimary);
        Label spacer = new Label(header, SWT.NO_BACKGROUND);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GlassButton checkNow = new GlassButton(header, palette, "Check Now", GlassButton.Style.TOOL);
        checkNow.addListener(SWT.Selection, e -> {
            window.updates().checkForUpdates();
            window.sweepInstalledApps();
            refreshRows();
        });
        GlassButton done = new GlassButton(header, palette, "Done", GlassButton.Style.TOOL);
        done.addListener(SWT.Selection, e -> sheet.close());

        // This Install card
        Composite install = card(body, "This Install", Glass.Glyph.INFO, palette.accent());
        InstallEngine.Identity self = InstallEngine.installedIdentity(Paths.root());
        installRow(install, "Version", self.tag() == null || self.tag().isBlank()
                ? "dev build" : self.tag());
        if (self.builtAt() != null && !self.builtAt().isBlank()) {
            installRow(install, "Built", self.builtAt());
        }
        installRow(install, "Launcher root", Paths.root().toString());

        // Self-update state card
        Composite selfCard = card(body, "Launcher Update", Glass.Glyph.DOWNLOAD, palette.warnFg);
        Label stateLine = bodyText(selfCard, "");
        Composite buttons = new Composite(selfCard, SWT.NO_BACKGROUND);
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout bl = new GridLayout(2, false);
        bl.marginWidth = 0;
        bl.marginHeight = 0;
        bl.horizontalSpacing = 8;
        buttons.setLayout(bl);
        GlassButton installRestart = new GlassButton(buttons, palette, "Install & Restart",
                GlassButton.Style.PRIMARY);
        installRestart.setTint(palette.warnFg);
        installRestart.addListener(SWT.Selection, e -> window.updates().installUpdate());
        GlassButton recheck = new GlassButton(buttons, palette, "Re-check", GlassButton.Style.TOOL);
        recheck.addListener(SWT.Selection, e -> window.updates().checkForUpdates());
        Label download = bodyText(selfCard, "");

        refreshHooks.add(() -> {
            LauncherState state = window.updates().state();
            if (state.selfAvailable()) {
                stateLine.setText(state.selfTag() + " is available — Install & Restart swaps this "
                        + "folder's files and relaunches BigM.");
                stateLine.setForeground(palette.warnFg);
            } else if (state.selfMessage() != null && !state.selfMessage().isBlank()) {
                stateLine.setText("Could not check: " + state.selfMessage());
                stateLine.setForeground(palette.errFg);
            } else {
                stateLine.setText("You're up to date.");
                stateLine.setForeground(palette.okFg);
            }
            installRestart.setEnabled(state.selfAvailable());
            download.setText(state.downloadMessage() == null ? "" : state.downloadMessage());
            selfCard.layout(true, true);
        });

        // Managed apps card
        Composite appsCard = card(body, "Managed Apps", Glass.Glyph.GRID, palette.accent());
        List<Catalog.AppDef> managed = new ArrayList<>();
        for (Catalog.AppDef app : window.visibleApps()) {
            if (app.updatable() && window.installDir(app.id()) != null) managed.add(app);
        }
        if (managed.isEmpty()) {
            bodyText(appsCard, "No managed apps installed yet. Get Apps installs any catalog "
                    + "release, after which it can be updated here (and rolled back by re-installing "
                    + "an older release over it).");
        }
        for (Catalog.AppDef app : managed) {
            buildManagedRow(appsCard, app);
        }

        // Update All in its own row under the header (matches the sheet
        // header action in Swift when several updates are pending).
        if (!managed.isEmpty()) {
            Composite allRow = new Composite(body, SWT.NO_BACKGROUND);
            allRow.setLayoutData(new GridData(SWT.RIGHT, SWT.TOP, true, false));
            allRow.setLayout(new GridLayout(1, false));
            GlassButton updateAll = new GlassButton(allRow, palette, "Update All",
                    GlassButton.Style.PRIMARY);
            updateAll.setTint(palette.warnFg);
            refreshHooks.add(() -> {
                long n = window.availableUpdateCount();
                updateAll.setText(n > 1 ? "Update All (" + n + ")" : "Update All");
                updateAll.setEnabled(n > 0);
            });
            updateAll.addListener(SWT.Selection, e -> {
                for (Catalog.AppDef app : managed) {
                    LauncherState.AppStatus status = window.updates().state().app(app.id());
                    if (status.phase() == LauncherState.AppStatus.Phase.UPDATE_AVAILABLE) {
                        window.updates().installAppUpdate(app, window.installDir(app.id()));
                    }
                }
            });
        }

        // GitHub access status (compact; editing lives in Settings)
        Composite access = card(body, "GitHub Access", Glass.Glyph.KEY, palette.accent());
        String source = !core.TokenStore.present()
                ? "No saved token."
                : "Using the token saved in Support/github-token.";
        bodyText(access, source + (core.GitHubAccess.resolve() != null
                ? " Updates can reach GitHub. Manage tokens in Settings."
                : " No token: public repos work anonymously; some private app repos need one in Settings → GitHub Access."));

        bodyText(body, "Updates come from github.com/fromthebigm over HTTPS. Installs replace app "
                + "files in place — the launcher's Support folder and each app's data folder "
                + "(save states, profiles, browser profiles) are never touched — then the new "
                + "files are dequarantined automatically.");

        refreshRows();
        body.layout(true, true);
        layoutScroller(body);

        // Live rows: event-driven, no polling — LauncherState broadcasts
        // from worker threads; marshal to SWT and refresh. Removed on close.
        LauncherState.Listener listener = new LauncherState.Listener() {
            @Override public void appStatus(String appId, LauncherState.AppStatus status) { pulse(); }
            @Override public void downloadMessage(String message) { pulse(); }
            @Override public void selfState(boolean a, String t, String m) { pulse(); }
            private void pulse() {
                display().asyncExec(() -> { if (!sheet.isDisposed()) refreshRows(); });
            }
        };
        window.updates().state().add(listener);
        sheet.addDisposeListener(e -> window.updates().state().remove(listener));
    }

    private void layoutScroller(Composite body) {
        Object scroller = body.getData("scroller");
        if (scroller instanceof ScrolledComposite sc) {
            sc.setMinSize(body.computeSize(sc.getClientArea().width, SWT.DEFAULT));
        }
    }

    private void installRow(Composite parent, String key, String value) {
        Composite row = new Composite(parent, SWT.NO_BACKGROUND);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout rl = new GridLayout(2, false);
        rl.marginWidth = 0;
        rl.marginHeight = 0;
        row.setLayout(rl);
        Label k = new Label(row, SWT.NO_BACKGROUND);
        k.setText(key);
        k.setFont(palette.caption);
        k.setForeground(palette.textMuted);
        Label v = new Label(row, SWT.NO_BACKGROUND);
        v.setText(value);
        v.setFont(palette.mono);
        v.setForeground(palette.textPrimary);
        v.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
    }

    private void buildManagedRow(Composite parent, Catalog.AppDef app) {
        Composite row = new Composite(parent, SWT.NO_BACKGROUND);
        row.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout rl = new GridLayout(5, false);
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
            Glass.paintCategoryChip(e.gc, display(), b.x, b.y, size,
                    UiPalette.hex(app.tintHex()), AppCard.categoryGlyph(app.category()));
        });
        GridData id = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        id.widthHint = 28;
        id.heightHint = 28;
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
        Label message = new Label(texts, SWT.NO_BACKGROUND);
        message.setFont(palette.caption);
        message.setForeground(palette.textMuted);

        Label pct = new Label(row, SWT.NO_BACKGROUND);
        pct.setFont(palette.mono);
        pct.setForeground(palette.textMuted);

        GlassButton action = new GlassButton(row, palette, "Check", GlassButton.Style.TOOL);
        Path dir = window.installDir(app.id());
        action.addListener(SWT.Selection, e -> {
            LauncherState.AppStatus status = window.updates().state().app(app.id());
            if (status.phase() == LauncherState.AppStatus.Phase.UPDATE_AVAILABLE && dir != null) {
                window.updates().installAppUpdate(app, dir);
            } else {
                window.updates().checkAppUpdate(app, dir);
            }
        });

        // Swift parity: rollback affordance when a snapshot exists.
        java.nio.file.Path backup = core.InstallEngine.latestBackup(app.id());
        if (backup != null) {
            GlassButton restore = new GlassButton(row, palette, "Restore backup",
                    GlassButton.Style.TOOL);
            restore.setTint(palette.warnFg);
            restore.addListener(SWT.Selection, e -> {
                if (dir == null) return;
                window.updates().runBackground(() -> {
                    try {
                        core.InstallEngine.restoreLatestBackup(app, dir);
                        window.log("info", app.id(), "restore backup from " + backup);
                    } catch (Exception failure) {
                        window.log("error", app.id(), "restore failed: " + failure.getMessage());
                    }
                    window.updates().checkAppUpdate(app, dir);
                });
            });
        }

        refreshHooks.add(() -> {
            LauncherState.AppStatus status = window.updates().state().app(app.id());
            String msg = status.message();
            message.setText(msg == null || msg.isBlank() ? describe(status) : msg);
            message.setForeground(status.phase() == LauncherState.AppStatus.Phase.FAILED
                    ? palette.errFg
                    : status.phase() == LauncherState.AppStatus.Phase.UPDATE_AVAILABLE
                    ? palette.warnFg : palette.textMuted);
            action.setText(status.phase() == LauncherState.AppStatus.Phase.UPDATE_AVAILABLE
                    ? "Update" : "Check");
            boolean busy = status.phase() == LauncherState.AppStatus.Phase.CHECKING
                    || status.phase() == LauncherState.AppStatus.Phase.DOWNLOADING
                    || status.phase() == LauncherState.AppStatus.Phase.INSTALLING;
            action.setEnabled(!busy);
            pct.setText(busy && status.phase() == LauncherState.AppStatus.Phase.DOWNLOADING
                    ? extractPct(status.message()) : "");
            row.layout(true, true);
        });
    }

    private static String extractPct(String message) {
        if (message == null) return "";
        int idx = message.lastIndexOf('%');
        if (idx < 0) return "";
        int start = idx - 1;
        while (start >= 0 && Character.isDigit(message.charAt(start))) start--;
        return message.substring(start + 1, idx + 1);
    }

    private static String describe(LauncherState.AppStatus status) {
        return switch (status.phase()) {
            case IDLE -> "Not checked yet";
            case CHECKING -> "Checking…";
            case UPDATE_AVAILABLE -> "Update available";
            case DOWNLOADING, INSTALLING -> "Installing…";
            case UP_TO_DATE -> "Up to date";
            case FAILED -> status.message();
            case SELF_MANAGED -> "Self-updating";
        };
    }

    private void refreshRows() {
        for (Runnable hook : refreshHooks) hook.run();
    }

    private Display display() { return window.swtDisplay(); }
}
