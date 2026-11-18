package ui;

import core.Catalog;
import core.InstallEngine;
import core.LauncherConfig;
import core.LauncherState;
import core.Paths;
import core.PlayStats;
import core.ProductGate;
import core.UpdateManager;
import net.GitHubUpdates;
import net.TransportLog;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The launcher window: dark-glass sidebar + card library, matching the
 * SwiftUI root view (which itself matched the original Python launcher).
 * Left rail holds the brand block, the category filters with counts, and
 * the action rows (Customize / Updates / Get Apps / Diagnostics). The
 * detail pane is the category header with search + sort, the self-update
 * and app-update bars, then the adaptive card grid. All update mechanics
 * live in core.UpdateManager; this class is presentation + launch tracking.
 */
public final class LauncherWindow implements UpdateManager.Ui, LauncherState.Listener {

    // ── Library model ────────────────────────────────────────────────────

    record FilterDef(String key, String title, Glass.Glyph glyph) {}
    static final List<FilterDef> FILTERS = List.of(
            new FilterDef("all", "All Apps", Glass.Glyph.GRID),
            new FilterDef("streaming", "Streaming", Glass.Glyph.PLAY),
            new FilterDef("remote-play", "Remote Play", Glass.Glyph.RADIO),
            new FilterDef("browser", "Browser", Glass.Glyph.GLOBE),
            new FilterDef("minecraft", "Minecraft", Glass.Glyph.CUBE),
            new FilterDef("emulator", "Emulators", Glass.Glyph.GAMEPAD),
            new FilterDef("games", "Games", Glass.Glyph.WINE),
            new FilterDef("development", "Development", Glass.Glyph.BRACES));

    static final String[] SORT_KEYS = {"name", "recent", "mostTime", "mostLaunches"};
    static final String[] SORT_TITLES = {"Name", "Recently Played", "Most Time", "Most Launches"};

    private final Display display;
    private final UiPalette palette;
    private final Glass.TintCache tints;
    private final Catalog catalog;
    private final LauncherConfig config;
    private final PlayStats stats;
    private final UpdateManager updates;

    private final Shell shell;
    private Composite sidebarList;
    private Composite detailPane;
    private Label detailTitle;
    private Label detailSubtitle;
    private Text searchField;

    private ScrolledComposite scroller;
    private Composite cardsParent;
    private ActionRow updatesRow;       // sidebar "Updates" row w/ live count

    // Self-update banner + app-updates bar
    private Composite selfBanner;
    private Label selfBannerTitle;
    private Label selfBannerDetail;
    private Composite appBar;
    private Label appBarText;
    private Label downloadLine;

    private String filter = "all";
    private String sort = "name";
    private String search = "";

    /** appId → live process record (for LIVE badges, Quit, play time). */
    private final Map<String, LaunchedApp> running = new ConcurrentHashMap<>();
    /** appId → resolved install dir cache (recomputed on refresh). */
    private final Map<String, Path> installDirs = new ConcurrentHashMap<>();
    /** appId → selected profile id (multi-profile apps). */
    private final Map<String, String> profileChoice = new ConcurrentHashMap<>();
    /** appId → cached chip image. */
    private final Map<String, Image> chipCache = new ConcurrentHashMap<>();
    /** appId → full-res custom icon PNG (scaled only at paint time). */
    private final Map<String, Image> rawIconCache = new ConcurrentHashMap<>();
    /** Diagnostics sheet ring buffer. */
    private final List<String> events = new CopyOnWriteArrayList<>();

    private record LaunchedApp(Process process, long startedAt, String appId) {}

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    public LauncherWindow(Display display, Catalog catalog) {
        this.display = display;
        this.catalog = catalog;
        this.config = new LauncherConfig();
        this.stats = new PlayStats();
        this.palette = new UiPalette(display);
        this.palette.setTheme(config.uiAccent());
        this.palette.setVariant(config.uiVariant());
        this.tints = new Glass.TintCache(display);
        this.updates = new UpdateManager(catalog, this);
        updates.state().add(this);

        shell = new Shell(display, SWT.SHELL_TRIM);
        shell.setText("BigM");
        shell.setSize(1180, 760);
        shell.setMinimumSize(new Point(900, 560));
        shell.setBackgroundMode(SWT.INHERIT_DEFAULT);
        shell.setBackground(palette.chromeDeep);
        // The aurora field spans the whole window; the sidebar re-paints its
        // own translucent coat on top so the scene still whispers through.
        shell.addPaintListener(e -> {
            Rectangle area = shell.getClientArea();
            Glass.paintBackdrop(e.gc, palette, area, config.backgroundStyle());
        });

        GridLayout rootLayout = new GridLayout(2, false);
        rootLayout.marginWidth = 0;
        rootLayout.marginHeight = 0;
        rootLayout.horizontalSpacing = 0;
        rootLayout.verticalSpacing = 0;
        shell.setLayout(rootLayout);

        buildSidebar();
        buildDetail();
        rebuildFilterList();
        refreshDiscovery();
        rebuildCards();
        installShortcuts();

        // Parity with the Swift 30-minute self-update re-check: one HTTPS
        // call per half hour is energy-noise, but lets a launcher that stays
        // open all day notice its own releases. Activation covers the
        // "came back to the Mac → see the update" case, gated to 15 min.
        periodicSelfCheck = new Runnable() {
            @Override public void run() {
                if (shell.isDisposed()) return;   // stops rescheduling -> timer dies with the window
                updates.checkForUpdates();
                display.timerExec(30 * 60 * 1000, this);
            }
        };
        display.timerExec(30 * 60 * 1000, periodicSelfCheck);
        // Catalog-wide sweep every 6h while the launcher sits open (Swift
        // parity: sweepAppUpdatesIfStale on a cadence, not only on Reload).
        periodicAppSweep = new Runnable() {
            @Override public void run() {
                if (shell.isDisposed()) return;
                updates.runBackground(() -> {
                    for (Catalog.AppDef app : visibleApps()) {
                        if (!app.updatable()) continue;
                        Path dir = installDirs.get(app.id());
                        if (dir != null) updates.checkAppUpdate(app, dir);
                    }
                });
                display.timerExec(6 * 60 * 60 * 1000, this);
            }
        };
        display.timerExec(6 * 60 * 60 * 1000, periodicAppSweep);
        shell.addListener(SWT.Activate, e -> {
            long since = System.currentTimeMillis() - lastSelfCheckAt;
            if (since > 15 * 60 * 1000) updates.checkForUpdates();
        });

        shell.addDisposeListener(e -> shutdown());
    }

    private volatile long lastSelfCheckAt;
    private Runnable periodicSelfCheck;
    private Runnable periodicAppSweep;

    private int sidebarWidth() { return 232; }

    // ── Sidebar ──────────────────────────────────────────────────────────

    /** Cmd-K palette, Cmd-F search focus, Cmd-1..9 launch/quit (Swift parity). */
    private void installShortcuts() {
        shell.addListener(SWT.KeyDown, e -> {
            if ((e.stateMask & SWT.MOD1) == 0) {
                if (e.keyCode == SWT.ESC && searchField != null && !searchField.isDisposed()) {
                    searchField.setText("");
                }
                return;
            }
            if (e.keyCode == 'k' || e.keyCode == 'K') {
                new CommandPalette(this).open();
            } else if (e.keyCode == 'f' || e.keyCode == 'F') {
                if (searchField != null && !searchField.isDisposed()) searchField.setFocus();
            } else if (e.keyCode >= '1' && e.keyCode <= '9') {
                List<Catalog.AppDef> apps = visibleApps();
                int index = e.keyCode - '1';
                if (index < apps.size()) {
                    Catalog.AppDef app = apps.get(index);
                    if (isRunning(app.id())) quitApp(app);
                    else {
                        List<Catalog.Profile> profiles = profilesFor(app);
                        launchApp(app, profiles.isEmpty() ? null : profiles.get(0));
                    }
                }
            }
        });
    }

    private void buildSidebar() {
        Composite sidebar = new Composite(shell, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        GridData sideData = new GridData(SWT.FILL, SWT.FILL, false, true);
        sideData.widthHint = sidebarWidth();
        sidebar.setLayoutData(sideData);
        // Translucent rail: the mountain scene bleeds through just enough
        // to keep depth, but text contrast holds (the shell paints the
        // backdrop across the full window; this coat sits on top).
        sidebar.addPaintListener(e -> {
            Rectangle b = sidebar.getClientArea();
            e.gc.setAdvanced(true);
            e.gc.setAlpha(224);
            e.gc.setBackground(palette.chromeRaised);
            e.gc.fillRectangle(b);
            e.gc.setAlpha(255);
            // hairline divider on the right edge
            e.gc.setForeground(palette.separator);
            e.gc.drawLine(b.width - 1, b.y, b.width - 1, b.y + b.height);
        });
        GridLayout sideLayout = new GridLayout(1, false);
        sideLayout.marginWidth = 0;
        sideLayout.marginHeight = 0;
        sideLayout.verticalSpacing = 0;
        sidebar.setLayout(sideLayout);

        // Brand block
        Composite brand = new Composite(sidebar, SWT.NO_BACKGROUND);
        brand.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout brandLayout = new GridLayout(2, false);
        brandLayout.marginWidth = 16;
        brandLayout.marginTop = 20;
        brandLayout.marginBottom = 10;
        brandLayout.horizontalSpacing = 11;
        brand.setLayout(brandLayout);
        Composite brandChip = new Composite(brand, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED) {
            @Override public Point computeSize(int wHint, int hHint, boolean changed) {
                return new Point(36, 36);
            }
        };
        // Painted straight into the sidebar GC — vector-sharp at any zoom,
        // no 1x-image edge halo (the white outline was the stretched bitmap).
        brandChip.addPaintListener(e -> {
            Rectangle b = brandChip.getClientArea();
            int size = Math.min(b.width, b.height);
            if (core.IconStore.hasCustomIcon(core.IconStore.LAUNCHER_ID)) {
                Image raw = rawIconCache.computeIfAbsent("_launcher", k -> {
                    java.nio.file.Path png = core.IconStore.icon(core.IconStore.LAUNCHER_ID,
                            true);
                    if (!java.nio.file.Files.isRegularFile(png)) {
                        png = core.IconStore.icon(core.IconStore.LAUNCHER_ID, false);
                    }
                    try { return new Image(display, png.toString()); }
                    catch (Throwable failure) { return null; }
                });
                if (raw != null) {
                    e.gc.setAntialias(SWT.ON);
                    e.gc.setInterpolation(SWT.HIGH);
                    var rd = raw.getImageData();
                    e.gc.drawImage(raw, 0, 0, rd.width, rd.height, b.x, b.y, size, size);
                    return;
                }
            }
            e.gc.setAntialias(SWT.ON);
            e.gc.setBackground(palette.accent());
            e.gc.setAlpha(38);
            e.gc.fillOval(b.x, b.y, size, size);
            e.gc.setAlpha(255);
            e.gc.setForeground(palette.accent());
            e.gc.drawOval(b.x, b.y, size - 1, size - 1);
            e.gc.setFont(palette.brand);
            var ext = e.gc.textExtent("BM");
            e.gc.drawString("BM", b.x + (size - ext.x) / 2, b.y + (size - ext.y) / 2, true);
        });
        GridData chipData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        chipData.widthHint = 36;
        chipData.heightHint = 36;
        brandChip.setLayoutData(chipData);
        Composite brandText = new Composite(brand, SWT.NO_BACKGROUND);
        GridLayout btLayout = new GridLayout(1, false);
        btLayout.marginWidth = 0;
        btLayout.marginHeight = 0;
        btLayout.verticalSpacing = 1;
        brandText.setLayout(btLayout);
        Label brandName = new Label(brandText, SWT.NO_BACKGROUND);
        brandName.setText("BigM");
        brandName.setFont(palette.brand);
        brandName.setForeground(palette.textPrimary);
        Label brandSub = new Label(brandText, SWT.NO_BACKGROUND);
        brandSub.setText("L A U N C H E R");
        brandSub.setFont(palette.tiny);
        brandSub.setForeground(palette.textMuted);

        // Caption w/ live dot
        Composite caption = new Composite(sidebar, SWT.NO_BACKGROUND);
        caption.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout capLayout = new GridLayout(2, false);
        capLayout.marginWidth = 18;
        capLayout.marginTop = 0;
        capLayout.marginBottom = 10;
        capLayout.horizontalSpacing = 7;
        caption.setLayout(capLayout);
        Label dot = new Label(caption, SWT.NO_BACKGROUND);
        dot.setText("●");
        dot.setFont(palette.tiny);
        dot.setForeground(palette.okFg);
        dot.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        Label cap = new Label(caption, SWT.NO_BACKGROUND);
        cap.setText("PORTABLE-FIRST LIBRARY");
        cap.setFont(palette.tiny);
        cap.setForeground(palette.textMuted);

        sidebarList = new Composite(sidebar, SWT.NO_BACKGROUND);
        sidebarList.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout listLayout = new GridLayout(1, false);
        listLayout.marginWidth = 10;
        listLayout.marginHeight = 0;
        listLayout.verticalSpacing = 4;
        sidebarList.setLayout(listLayout);

        // Bottom: divider, version pill, action rows
        Composite bottom = new Composite(sidebar, SWT.NO_BACKGROUND);
        bottom.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        GridLayout botLayout = new GridLayout(1, false);
        botLayout.marginWidth = 12;
        botLayout.marginHeight = 10;
        botLayout.verticalSpacing = 3;
        bottom.setLayout(botLayout);

        Label divider = new Label(bottom, SWT.SEPARATOR | SWT.HORIZONTAL);
        divider.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        InstallEngine.Identity self = InstallEngine.installedIdentity(Paths.root());
        Label version = new Label(bottom, SWT.NO_BACKGROUND);
        version.setText("●  " + (self.tag() == null || self.tag().isBlank() ? "dev build" : self.tag()));
        version.setFont(palette.small);
        version.setForeground(self.tag() == null || self.tag().isBlank()
                ? palette.textMuted : palette.okFg);
        GridData versionData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        versionData.verticalIndent = 6;
        version.setLayoutData(versionData);

        updatesRow = addSidebarAction(bottom, "Updates", () -> new UpdatesSheet(this).open());
        addSidebarAction(bottom, "Customize", () -> new CustomizeSheet(this).open());
        addSidebarAction(bottom, "Get Apps", () -> new GetAppsSheet(this).open());
        addSidebarAction(bottom, "Settings", () -> new SettingsSheet(this).open());
        addSidebarAction(bottom, "Diagnostics", () -> new DiagnosticsSheet(this).open());
        addSidebarAction(bottom, "System", () -> new SystemSheet(this).open());
    }

    /** A chevron-suffixed sidebar action row; text() is rewritable live (the
     *  Updates row shows the pending count). */
    private final class ActionRow extends Composite {
        private String text;
        ActionRow(Composite parent, String text, Runnable action) {
            super(parent, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
            this.text = text;
            GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false);
            gd.heightHint = 28;
            setLayoutData(gd);
            setCursor(display.getSystemCursor(SWT.CURSOR_HAND));
            final boolean[] hover = {false};
            final boolean[] alert = {false};
            addListener(SWT.MouseEnter, e -> { hover[0] = true; redraw(); });
            addListener(SWT.MouseExit, e -> { hover[0] = false; redraw(); });
            addListener(SWT.MouseUp, e -> { if (e.button == 1 && action != null) action.run(); });
            addPaintListener(e -> {
                Rectangle b = getClientArea();
                e.gc.setAntialias(SWT.ON);
                if (hover[0]) {
                    e.gc.setAlpha(18);
                    e.gc.setBackground(palette.textPrimary);
                    e.gc.fillRoundRectangle(b.x, b.y, b.width - 1, b.height - 1, 24, 24);
                    e.gc.setAlpha(255);
                }
                e.gc.setFont(palette.body);
                e.gc.setForeground(alert[0] ? palette.warnFg : palette.textPrimary);
                int y = b.y + (b.height - e.gc.textExtent(text).y) / 2;
                e.gc.drawText(text, b.x + 8, y, true);
                Glass.paintGlyph(e.gc, display, Glass.Glyph.CHEVRON,
                        b.x + b.width - 18, b.y + (b.height - 10) / 2, 10,
                        alert[0] ? palette.warnFg : palette.textFaint);
            });
            // expose alert toggling for the updates badge
            setData("alert", alert);
        }
        void setText(String text) { this.text = text; redraw(); }
        void setAlert(boolean on) {
            ((boolean[]) getData("alert"))[0] = on;
            redraw();
        }
    }

    private ActionRow addSidebarAction(Composite parent, String title, Runnable action) {
        return new ActionRow(parent, title, action);
    }

    // ── Filter list ──────────────────────────────────────────────────────

    private void rebuildFilterList() {
        for (Control child : sidebarList.getChildren()) child.dispose();

        Label section = new Label(sidebarList, SWT.NO_BACKGROUND);
        section.setText("L I B R A R Y");
        section.setFont(palette.tiny);
        section.setForeground(palette.textFaint);
        GridData sd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        sd.horizontalIndent = 8;
        sd.verticalIndent = 2;
        section.setLayoutData(sd);

        // Category rows reflect the INSTALLED library (a machine with no
        // game launchers gets no Games tab — owner directive); "All Apps"
        // stays the discovery view and always counts the full catalog.
        java.util.List<FilterDef> shown = new ArrayList<>();
        for (FilterDef def : FILTERS) {
            long count = ("all".equals(def.key())) ? visibleApps().size()
                    : visibleApps().stream()
                            .filter(a -> def.key().equals(a.category()) && isInstalled(a))
                            .count();
            if (!"all".equals(def.key()) && count == 0 && !def.key().equals(filter)) continue;
            shown.add(def);
        }
        for (FilterDef def : shown) {
            long count = "all".equals(def.key()) ? visibleApps().size()
                    : visibleApps().stream()
                            .filter(a -> def.key().equals(a.category()) && isInstalled(a))
                            .count();
            Composite row = new Composite(sidebarList, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
            GridData rd = new GridData(SWT.FILL, SWT.TOP, true, false);
            rd.heightHint = 34;
            row.setLayoutData(rd);
            row.setCursor(display.getSystemCursor(SWT.CURSOR_HAND));
            final FilterDef item = def;
            final boolean[] hover = {false};
            row.addListener(SWT.MouseEnter, e -> { hover[0] = true; row.redraw(); });
            row.addListener(SWT.MouseExit, e -> { hover[0] = false; row.redraw(); });
            row.addListener(SWT.MouseUp, e -> {
                if (e.button != 1) return;
                filter = item.key();
                rebuildFilterList();
                rebuildDetailHeader();
                rebuildCards();
            });
            row.addPaintListener(e -> {
                boolean selected = filter.equals(item.key());
                Rectangle b = row.getClientArea();
                e.gc.setAntialias(SWT.ON);
                if (selected) {
                    e.gc.setAlpha(45);
                    e.gc.setBackground(palette.accent());
                    e.gc.fillRoundRectangle(b.x, b.y, b.width - 1, b.height - 1, 28, 28);
                    e.gc.setAlpha(120);
                    e.gc.setForeground(palette.accent());
                    e.gc.drawRoundRectangle(b.x, b.y, b.width - 1, b.height - 1, 28, 28);
                } else if (hover[0]) {
                    e.gc.setAlpha(16);
                    e.gc.setBackground(palette.textPrimary);
                    e.gc.fillRoundRectangle(b.x, b.y, b.width - 1, b.height - 1, 28, 28);
                }
                e.gc.setAlpha(255);
                // glyph chip + vector icon (SF-Symbols-style line art, not emoji)
                e.gc.setAlpha(selected ? 60 : 14);
                e.gc.setBackground(selected ? palette.accent() : palette.textPrimary);
                e.gc.fillRoundRectangle(b.x + 6, b.y + 4, 26, 26, 16, 16);
                e.gc.setAlpha(255);
                Glass.paintGlyph(e.gc, display, item.glyph(),
                        b.x + 6 + 5, b.y + 4 + 5, 16,
                        selected ? palette.accent() : palette.textMuted);
                // title
                e.gc.setFont(selected ? palette.headline : palette.body);
                e.gc.setForeground(palette.textPrimary);
                int ty = b.y + (b.height - e.gc.textExtent(item.title()).y) / 2;
                e.gc.drawText(item.title(), b.x + 40, ty, true);
                // count pill
                String countText = String.valueOf(count);
                e.gc.setFont(palette.tiny);
                Point ce = e.gc.textExtent(countText);
                int pillW = ce.x + 14;
                int pillH = 16;
                int px = b.x + b.width - pillW - 8;
                int py = b.y + (b.height - pillH) / 2;
                e.gc.setAlpha(26);
                e.gc.setBackground(selected ? palette.accent() : palette.textMuted);
                e.gc.fillRoundRectangle(px, py, pillW, pillH, pillH, pillH);
                e.gc.setAlpha(255);
                e.gc.setForeground(selected ? palette.accent() : palette.textMuted);
                e.gc.drawText(countText, px + (pillW - ce.x) / 2, py + (pillH - ce.y) / 2, true);
            });
        }
        sidebarList.layout(true, true);
    }

    // ── Detail pane ──────────────────────────────────────────────────────

    private void buildDetail() {
        detailPane = new Composite(shell, SWT.NO_BACKGROUND);
        detailPane.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 0;
        detailPane.setLayout(layout);

        buildDetailHeader();
        buildSelfBanner();
        buildAppBar();

        scroller = new ScrolledComposite(detailPane, SWT.V_SCROLL | SWT.NO_BACKGROUND);
        scroller.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scroller.setExpandHorizontal(true);
        scroller.setExpandVertical(false);
        // Clear AppKit's gray NSScrollView/NSClipView document plate; SWT's
        // NO_BACKGROUND alone does not stop native NSClipView painting.
        try {
            if (scroller.view != null && scroller.view.id != 0) {
                long color = org.eclipse.swt.internal.cocoa.OS.objc_msgSend(
                        org.eclipse.swt.internal.cocoa.OS.objc_getClass("NSColor"),
                        org.eclipse.swt.internal.cocoa.OS.sel_registerName("clearColor"));
                org.eclipse.swt.internal.cocoa.OS.objc_msgSend(scroller.view.id,
                        org.eclipse.swt.internal.cocoa.OS.sel_registerName("setBackgroundColor:"), color);
                long clip = org.eclipse.swt.internal.cocoa.OS.objc_msgSend(scroller.view.id,
                        org.eclipse.swt.internal.cocoa.OS.sel_registerName("contentView"));
                if (clip != 0) org.eclipse.swt.internal.cocoa.OS.objc_msgSend(clip,
                        org.eclipse.swt.internal.cocoa.OS.sel_registerName("setBackgroundColor:"), color);
            }
        } catch (Throwable ignored) {
        }
        cardsParent = new Composite(scroller, SWT.NO_BACKGROUND);
        scroller.setContent(cardsParent);
        cardsParent.addListener(SWT.Resize, e -> relayoutGrid());
        Glass.addWheelScroll(scroller);

        reloadCardsMinSize();
    }

    private void buildDetailHeader() {
        Composite header = new Composite(detailPane, SWT.NO_BACKGROUND);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout hl = new GridLayout(4, false);
        hl.marginWidth = 24;
        hl.marginTop = 24;
        hl.marginBottom = 16;
        hl.horizontalSpacing = 10;
        header.setLayout(hl);

        Composite titleBlock = new Composite(header, SWT.NO_BACKGROUND);
        GridLayout tb = new GridLayout(1, false);
        tb.marginWidth = 0;
        tb.marginHeight = 0;
        tb.verticalSpacing = 2;
        titleBlock.setLayout(tb);
        titleBlock.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        detailTitle = new Label(titleBlock, SWT.NO_BACKGROUND);
        detailTitle.setFont(palette.libTitle);
        detailTitle.setForeground(palette.textPrimary);
        detailSubtitle = new Label(titleBlock, SWT.NO_BACKGROUND);
        detailSubtitle.setFont(palette.caption);
        detailSubtitle.setForeground(palette.textMuted);
        rebuildDetailHeaderText();

        // Search pill
        Composite searchBox = new Composite(header, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        GridData searchData = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
        searchData.widthHint = 210;
        searchData.heightHint = 32;
        searchBox.setLayoutData(searchData);
        GridLayout sb = new GridLayout(2, false);
        sb.marginWidth = 10;
        sb.marginHeight = 4;
        sb.horizontalSpacing = 6;
        searchBox.setLayout(sb);
        searchBox.addPaintListener(e -> {
            Rectangle b = searchBox.getClientArea();
            e.gc.setAntialias(SWT.ON);
            e.gc.setAlpha(235);
            e.gc.setBackground(palette.card);
            e.gc.fillRoundRectangle(b.x, b.y, b.width - 1, b.height - 1, 32, 32);
            e.gc.setAlpha(255);
            e.gc.setForeground(palette.separator);
            e.gc.drawRoundRectangle(b.x, b.y, b.width - 1, b.height - 1, 32, 32);
        });
        new Glass.GlyphView(searchBox, 14, Glass.Glyph.SEARCH, palette.textMuted);
        searchField = new Text(searchBox, SWT.SINGLE);
        searchField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, true));
        searchField.setBackground(palette.card);
        searchField.setForeground(palette.textPrimary);
        searchField.setMessage("Search library");
        searchField.setFont(palette.body);
        searchField.addModifyListener(e -> {
            search = searchField.getText().trim();
            rebuildDetailHeaderText();
            rebuildCards();
        });

        // Sort + reload
        GlassButton sortButton = new GlassButton(header, palette, "Sort: " + SORT_TITLES[0],
                GlassButton.Style.TOOL);
        Menu sortMenu = new Menu(sortButton);
        for (int i = 0; i < SORT_KEYS.length; i++) {
            final int idx = i;
            MenuItem item = new MenuItem(sortMenu, SWT.RADIO);
            item.setText(SORT_TITLES[i]);
            item.setSelection(i == 0);
            item.addListener(SWT.Selection, e -> {
                sort = SORT_KEYS[idx];
                sortButton.setText("Sort: " + SORT_TITLES[idx]);
                rebuildCards();
            });
        }
        sortButton.addListener(SWT.Selection, e -> {
            Point p = sortButton.toDisplay(0, sortButton.getSize().y);
            sortMenu.setLocation(p);
            sortMenu.setVisible(true);
        });

        GlassButton reload = new GlassButton(header, palette, "Reload", GlassButton.Style.TOOL);
        reload.addListener(SWT.Selection, e -> refreshDiscovery());
    }

    private void rebuildDetailHeader() {
        rebuildDetailHeaderText();
    }

    private void rebuildDetailHeaderText() {
        if (detailTitle == null || detailTitle.isDisposed()) return;
        String title = FILTERS.stream().filter(f -> f.key().equals(filter)).findFirst()
                .map(FilterDef::title).orElse("All Apps");
        detailTitle.setText(title);
        long count = filteredApps().size();
        long runningCount = running.size();
        String noun = count == 1 ? "profile" : "profiles";
        String run = runningCount == 0 ? "" : " · " + runningCount + " running";
        detailSubtitle.setText(search.isEmpty()
                ? count + " " + noun + " ready to discover and launch" + run
                : count + " matching " + noun + run);
        detailSubtitle.getParent().layout(true, true);
    }

    // ── Bars (self update + app updates) ─────────────────────────────────

    private void buildSelfBanner() {
        selfBanner = new Composite(detailPane, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        GridData data = new GridData(SWT.FILL, SWT.TOP, true, false);
        data.exclude = true;
        selfBanner.setLayoutData(data);
        selfBanner.setVisible(false);
        GridLayout bl = new GridLayout(5, false);
        bl.marginWidth = 14;
        bl.marginHeight = 9;
        bl.horizontalSpacing = 10;
        selfBanner.setLayout(bl);
        data.horizontalIndent = 24;
        selfBanner.addPaintListener(e -> Glass.paintCard(e.gc, palette, selfBanner.getClientArea(),
                18, palette.warnFg, true, false));

        new Glass.GlyphView(selfBanner, 16, Glass.Glyph.DOWNLOAD, palette.warnFg);

        Composite texts = new Composite(selfBanner, SWT.NO_BACKGROUND);
        GridLayout tl = new GridLayout(1, false);
        tl.marginWidth = 0;
        tl.marginHeight = 0;
        tl.verticalSpacing = 1;
        texts.setLayout(tl);
        texts.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        selfBannerTitle = new Label(texts, SWT.NO_BACKGROUND);
        selfBannerTitle.setFont(palette.headline);
        selfBannerTitle.setForeground(palette.textPrimary);
        selfBannerDetail = new Label(texts, SWT.NO_BACKGROUND);
        selfBannerDetail.setFont(palette.caption);
        selfBannerDetail.setForeground(palette.textMuted);

        downloadLine = new Label(selfBanner, SWT.NO_BACKGROUND);
        downloadLine.setFont(palette.caption);
        downloadLine.setForeground(palette.textMuted);
        downloadLine.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        GlassButton install = new GlassButton(selfBanner, palette, "Install & Restart",
                GlassButton.Style.PRIMARY);
        install.setTint(palette.warnFg);
        install.addListener(SWT.Selection, e -> updates.installUpdate());
        GlassButton details = new GlassButton(selfBanner, palette, "Details", GlassButton.Style.TOOL);
        details.addListener(SWT.Selection, e -> new UpdatesSheet(this).open());
    }

    private void updateSelfBanner() {
        LauncherState state = updates.state();
        boolean show = state.selfAvailable();
        selfBannerTitle.setText("BigM " + state.selfTag() + " is available");
        InstallEngine.Identity self = InstallEngine.installedIdentity(Paths.root());
        String current = self.tag() == null || self.tag().isBlank() ? "a dev build" : self.tag();
        selfBannerDetail.setText("You're running " + current);
        downloadLine.setText(state.downloadMessage() == null ? "" : state.downloadMessage());
        GridData data = (GridData) selfBanner.getLayoutData();
        boolean messageOnly = state.downloadMessage() != null && !state.downloadMessage().isBlank();
        boolean visible = show || messageOnly;
        if (visible != !data.exclude) {
            data.exclude = !visible;
            selfBanner.setVisible(visible);
            detailPane.layout(true, true);
        } else {
            selfBanner.layout(true, true);
        }
    }

    private void buildAppBar() {
        appBar = new Composite(detailPane, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        GridData data = new GridData(SWT.FILL, SWT.TOP, true, false);
        data.exclude = true;
        appBar.setLayoutData(data);
        appBar.setVisible(false);
        data.horizontalIndent = 24;
        appBar.addPaintListener(e -> Glass.paintCard(e.gc, palette, appBar.getClientArea(),
                18, palette.accent(), false, false));
        GridLayout al = new GridLayout(4, false);
        al.marginWidth = 14;
        al.marginHeight = 8;
        al.horizontalSpacing = 10;
        appBar.setLayout(al);

        new Glass.GlyphView(appBar, 16, Glass.Glyph.DOWNLOAD, palette.accent());
        appBarText = new Label(appBar, SWT.NO_BACKGROUND);
        appBarText.setFont(palette.headline);
        appBarText.setForeground(palette.textPrimary);
        appBarText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GlassButton all = new GlassButton(appBar, palette, "Update All", GlassButton.Style.PRIMARY);
        all.addListener(SWT.Selection, e ->
                updates.installAllAvailable(this::installDirFor));
        GlassButton review = new GlassButton(appBar, palette, "Review", GlassButton.Style.TOOL);
        review.addListener(SWT.Selection, e -> new UpdatesSheet(this).open());
    }

    private void updateAppBar() {
        long available = visibleApps().stream()
                .filter(a -> updates.state().app(a.id()).phase()
                        == LauncherState.AppStatus.Phase.UPDATE_AVAILABLE)
                .count();
        String text = available == 1 ? "1 app update available"
                : available + " app updates available";
        appBarText.setText(text);
        GridData data = (GridData) appBar.getLayoutData();
        boolean visible = available > 0;
        if (visible != !data.exclude) {
            data.exclude = !visible;
            appBar.setVisible(visible);
            detailPane.layout(true, true);
        }
    }

    // ── Cards ────────────────────────────────────────────────────────────

    /** Catalog apps + the user's custom apps, in filter/search/sort order. */
    List<Catalog.AppDef> visibleApps() {
        List<Catalog.AppDef> all = new ArrayList<>(catalog.apps());
        all.addAll(config.additionalApps());
        return all;
    }

    /** Sidebar-visible apps: for category filters, installed only (the tabs
     *  mirror the installed library); for "All Apps", the full catalog. */
    private List<Catalog.AppDef> sidebarVisibleApps() {
        if ("all".equals(filter)) return visibleApps();
        List<Catalog.AppDef> installed = new ArrayList<>();
        for (Catalog.AppDef app : visibleApps()) {
            if (filter.equals(app.category()) && isInstalled(app)) installed.add(app);
        }
        return installed;
    }

    private List<Catalog.AppDef> filteredApps() {
        List<Catalog.AppDef> base = new ArrayList<>();
        for (Catalog.AppDef app : sidebarVisibleApps()) {
            if (!"all".equals(filter) && !filter.equals(app.category())) continue;
            if (!search.isEmpty()) {
                String q = search.toLowerCase();
                if (!app.displayName().toLowerCase().contains(q)
                        && !app.summary().toLowerCase().contains(q)) continue;
            }
            base.add(app);
        }
        switch (sort) {
            case "recent" -> base.sort((a, b) -> {
                long d = stats.lastPlayedMillis(b.id()) - stats.lastPlayedMillis(a.id());
                return d != 0 ? Long.signum(d) : a.displayName().compareToIgnoreCase(b.displayName());
            });
            case "mostTime" -> base.sort((a, b) -> {
                long d = stats.totalSeconds(b.id()) - stats.totalSeconds(a.id());
                return d != 0 ? Long.signum(d) : a.displayName().compareToIgnoreCase(b.displayName());
            });
            case "mostLaunches" -> base.sort((a, b) -> {
                int d = stats.launches(b.id()) - stats.launches(a.id());
                return d != 0 ? d : a.displayName().compareToIgnoreCase(b.displayName());
            });
            default -> base.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
        }
        return base;
    }

    List<Catalog.Profile> profilesFor(Catalog.AppDef app) {
        List<Catalog.Profile> profiles = catalog.profilesFor(app.id());
        if (!profiles.isEmpty()) return profiles;
        return config.additionalProfilesFor(app.id());
    }

    void rebuildCards() {
        for (Control child : cardsParent.getChildren()) child.dispose();
        List<Catalog.AppDef> apps = filteredApps();
        for (Catalog.AppDef app : apps) {
            new AppCard(this, cardsParent, app);
        }
        relayoutGrid();
        rebuildFilterList();           // counts change with filters
        updateSidebarUpdatesRow();
    }

    /** Re-hint every card cell to its natural height at the current column
     *  width, lay the grid out, and refresh the scroller's min size. Called
     *  on resize/rebuild AND from AppCard.refreshDynamic (content changes
     *  move a card's natural height; without a re-hint the frozen GridData
     *  heightHint clips buttons — the card "shrinks" visually). */
    void relayoutGrid() {
        if (cardsParent.isDisposed()) return;
        int width = scroller.getClientArea().width;
        if (width <= 0) width = 948;   // pre-open: use the shell's own size
        int cardMin = 300;
        int cols = Math.max(1, Math.min(4, width / cardMin));
        GridLayout gl = cardsParent.getLayout() instanceof GridLayout existing ? existing : null;
        if (gl == null || gl.numColumns != cols) {
            gl = new GridLayout(cols, true);
            gl.marginWidth = 24;
            gl.marginHeight = 10;
            gl.horizontalSpacing = 18;
            gl.verticalSpacing = 18;
            cardsParent.setLayout(gl);
        }
        // Uniform card size: measure at the column width and hand
        // GridLayout concrete hints (grid cells came back 0-height without).
        int cellW = (width - 48 - (cols - 1) * 18) / cols;
        for (Control child : cardsParent.getChildren()) {
            if (child instanceof AppCard card) card.setCellWidth(cellW);
        }
        cardsParent.layout(true, true);
        reloadCardsMinSize();
    }

    private void reloadCardsMinSize() {
        if (scroller == null || scroller.isDisposed() || cardsParent == null || cardsParent.isDisposed()) return;
        int width = scroller.getClientArea().width;
        if (width <= 0) width = 948;
        Point size = cardsParent.computeSize(width, SWT.DEFAULT);
        scroller.setMinSize(size);
        // ScrolledComposite only applies the min size on its own resize
        // schedule; set the content's size explicitly or freshly-rebuilt
        // cards keep the stale (possibly zero) height.
        cardsParent.setSize(size);
    }

    /** Background re-resolution of install dirs (called on open + reload). */
    void refreshDiscovery() {
        updates.runBackground(() -> {
            for (Catalog.AppDef app : visibleApps()) {
                Path dir = installDirFor(app);
                if (dir != null) installDirs.put(app.id(), dir); else installDirs.remove(app.id());
            }
            display.asyncExec(() -> {
                if (shell.isDisposed()) return;
                rebuildCards();
                sweepInstalledApps();
                renderSystemIcons();
            });
        });
    }

    /** One passive check sweep per refresh, Swift's sweepAppUpdatesIfStale. */
    void sweepInstalledApps() {
        for (Catalog.AppDef app : visibleApps()) {
            if (!app.updatable()) continue;
            Path dir = installDirs.get(app.id());
            if (dir != null) updates.checkAppUpdate(app, dir);
        }
        updates.checkForUpdates();
    }

    // ── Per-app operations (used by cards and sheets) ────────────────────

    /** Resolved install dir: explicit override, then any existing candidate
     *  target's parent, then the default-folder convention. ${HOME} means
     *  the machine's real home (the portable HOME would hide real installs). */
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
        String folder = config.defaultInstallFolder();
        if (folder != null) {
            Path dir = Path.of(folder).resolve(app.id());
            if (Files.isDirectory(dir)) return dir;
        }
        if (override != null) return Path.of(override); // picked but missing → report intent
        return null;
    }

    boolean isInstalled(Catalog.AppDef app) {
        Path dir = installDirs.get(app.id());
        return dir != null && Files.isDirectory(dir);
    }

    String expand(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return raw.replace("${HOME}", Paths.realHome().toString());
    }

    void launchApp(Catalog.AppDef app, Catalog.Profile profile) {
        Path dir = installDirFor(app);
        String dirText = dir == null ? "" : dir.toString();

        // The catalog's target/candidates are GUESSES that fit the original
        // dev machine; the launcher's own resolved/install dir is the truth.
        // Order: the resolved install dir + marker, then existing candidates,
        // then the raw target — never a hardcoded path that misses the real
        // folder the app was actually installed into.
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
                log("error", app.id(), "no launch target for " + app.id());
                return;
            }
            target = raw;
        }
        final String resolved = target.replace("{targetDir}", dirText);
        // Backend semantics mirror the Swift LaunchCoordinator: python
        // loaders go through /usr/bin/python3 (shebang + x-bit reliance made
        // AirDropped payloads flaky), scripts/executables run directly, and
        // .app bundles go through LaunchServices (/usr/bin/open -W keeps the
        // handle alive so LIVE badges and play time stay truthful). No extra
        // host binaries beyond that — apps then start java / WKWebKit inside
        // their own wrappers exactly like the hand-written launchers do.
        ProcessBuilder builder = new ProcessBuilder();
        java.util.List<String> argv = new java.util.ArrayList<>();
        String backend = profile.backend() == null ? "script" : profile.backend();
        boolean application = "application".equals(backend);
        if ("python".equals(backend)) {
            // scripts/executables run as-is; python loaders run through the
            // system interpreter (parity with Swift, no reliance on shebangs)
            argv.add("/usr/bin/python3");
            argv.add(resolved);
        } else if (application) {
            argv.add("/usr/bin/open");
            argv.add("-W");
            argv.add(resolved);
        } else {
            argv.add(resolved);
        }
        if (application && !profile.args().isEmpty()) argv.add("--args");
        for (String arg : profile.args()) {
            argv.add(arg.replace("{targetDir}", dirText));
        }
        builder.command(argv);
        Path cwd = null;
        if (profile.cwd() != null && !profile.cwd().isBlank()) {
            String expandedCwd = expand(profile.cwd().replace("{targetDir}", dirText));
            if (expandedCwd != null) {
                Path cwdp = Path.of(expandedCwd);
                if (Files.isDirectory(cwdp)) cwd = cwdp;
            }
        }
        if (cwd == null && !"application".equals(backend)) {
            Path parent = Path.of(resolved).toAbsolutePath().getParent();
            if (parent != null && Files.isDirectory(parent)) cwd = parent;
        }
        if (cwd != null) builder.directory(cwd.toFile());

        // Environment: inherit, minus repo credentials (a launched app must
        // never see GitHub tokens), plus the catalog env (expanded), plus the
        // per-launch ticket managed launchers verify via bigm_access_gate.h —
        // Rigel and friends refuse to launch outside a BigM tree without it.
        Map<String, String> env = builder.environment();
        env.remove("GH_TOKEN");
        env.remove("GITHUB_TOKEN");
        for (Map.Entry<String, String> entry : profile.env().entrySet()) {
            env.put(entry.getKey(), entry.getValue().replace("{targetDir}", dirText));
        }
        env.putAll(core.LaunchTicket.environment());
        // A user-picked card icon becomes the launched app's Dock tile via
        // the shared presentation header (reads BIGM_PRESENTATION_ICON_*).
        env.putAll(core.IconStore.launchEnvironment(app.id(),
                core.IconStore.Mode.parse(config.iconMode(app.id()))));
        // Capture protection defaults ON across the family; only an explicit
        // user toggle emits the opt-out env (mirrors preferenceEnvironment).
        if (!config.captureProtection(app.id())) env.put("BIGM_CAPTURE_PROTECTION", "0");

        // Per-launch console capture under Support/logs (kept on non-zero
        // exit, deleted on clean exit — same discipline as Swift).
        Path logFile = null;
        try {
            Path logsDir = Paths.support().resolve("logs");
            Files.createDirectories(logsDir);
            logFile = logsDir.resolve(app.id() + "-" + (System.currentTimeMillis() / 1000) + ".log");
            builder.redirectErrorStream(true);
            builder.redirectOutput(logFile.toFile());
        } catch (IOException ignored) {
            logFile = null;
        }

        try {
            Process process = builder.start();
            stats.recordLaunch(app.id());
            LaunchedApp launched = new LaunchedApp(process, System.currentTimeMillis(), app.id());
            running.put(app.id(), launched);
            log("success", app.id(), "launched " + app.displayName());
            final Path capture = logFile;
            Thread watcher = new Thread(() -> {
                int exitCode = 0;
                try {
                    exitCode = process.waitFor();
                } catch (InterruptedException ignored) {
                }
                long seconds = (System.currentTimeMillis() - launched.startedAt()) / 1000;
                running.remove(app.id(), launched);
                stats.addSessionSeconds(app.id(), seconds);
                if (capture != null) {
                    try {
                        if (exitCode == 0) Files.deleteIfExists(capture);
                        else log("error", app.id(), "exited " + exitCode + "; console capture at " + capture);
                    } catch (IOException ignored) {
                    }
                }
                display.asyncExec(() -> {
                    if (shell.isDisposed()) return;
                    refreshAppCard(app.id());
                    rebuildDetailHeaderText();
                });
            }, "bigm-watch-" + app.id());
            watcher.setDaemon(true);
            watcher.start();
            refreshAppCard(app.id());
            rebuildDetailHeaderText();
        } catch (IOException failure) {
            log("error", app.id(), "launch failed: " + failure.getMessage());
            if (logFile != null) try { Files.deleteIfExists(logFile); } catch (IOException ignored) {}
            MessageBox box = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
            box.setText("BigM Launcher");
            box.setMessage("Could not launch " + app.displayName() + ":\n" + failure.getMessage());
            box.open();
        }
    }

    void quitApp(Catalog.AppDef app) {
        LaunchedApp launched = running.remove(app.id());
        if (launched != null) {
            launched.process().destroy();
            log("info", app.id(), "quit " + app.displayName());
        }
        refreshAppCard(app.id());
        rebuildDetailHeaderText();
    }

    /** Change Icon…: pick any .app; its current icon (light + dark renders)
     *  becomes this card's artwork and, hardened launchers being what they
     *  are, that app's Dock tile at launch. */
    void pickCustomIcon(Catalog.AppDef app) {
        DirectoryDialog dialog = new DirectoryDialog(shell);
        dialog.setText("Choose the app whose icon " + app.displayName() + " should use");
        dialog.setMessage("Pick any app in /Applications or elsewhere — its current icon "
                + "(light and dark variants included) becomes this card's icon.");
        dialog.setFilterPath("/Applications");
        String picked = dialog.open();
        if (picked == null) return;
        if (!core.IconStore.applyCustomIcon(app.id(), Path.of(picked))) {
            MessageBox box = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK);
            box.setText("BigM Launcher");
            box.setMessage("Could not render an icon from " + Path.of(picked).getFileName() + ".");
            box.open();
            return;
        }
        // Presentation mode prompt — mirrors the Swift follow-up alert.
        Shell ask = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        ask.setText("Icon Presentation");
        ask.setLayout(new GridLayout(2, true));
        ask.setBackground(palette.chromeDeep);
        Label prompt = new Label(ask, SWT.WRAP);
        prompt.setText("How should " + app.displayName() + "'s icon be presented?");
        prompt.setFont(palette.body);
        prompt.setForeground(palette.textPrimary);
        prompt.setBackground(palette.chromeDeep);
        prompt.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        final String[] mode = {null};
        for (String[] option : new String[][]{
                {"system", "Follow System"}, {"light", "Always Light"}, {"dark", "Always Dark"}}) {
            Button b = new Button(ask, SWT.PUSH);
            b.setText(option[1]);
            final String value = option[0];
            b.addListener(SWT.Selection, e -> { mode[0] = value; ask.close(); });
        }
        ask.pack();
        ask.open();
        while (!ask.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
        if (mode[0] == null) { core.IconStore.resetCustomIcon(app.id()); return; }
        config.setIconMode(app.id(), mode[0]);
        dropChip(app.id());
        rebuildCards();
    }

    void resetCustomIcon(Catalog.AppDef app) {
        core.IconStore.resetCustomIcon(app.id());
        config.setIconMode(app.id(), null);
        dropChip(app.id());
        rebuildCards();
    }

    private void dropChip(String appId) {
        Image stale = chipCache.remove(appId);
        if (stale != null && !stale.isDisposed()) stale.dispose();
        Image rawStale = rawIconCache.remove(appId);
        if (rawStale != null && !rawStale.isDisposed()) rawStale.dispose();
    }

    void revealApp(Catalog.AppDef app) {
        Path dir = installDirFor(app);
        if (dir == null) return;
        try {
            new ProcessBuilder("/usr/bin/open", dir.toString()).start();
        } catch (IOException failure) {
            log("error", app.id(), "reveal failed: " + failure.getMessage());
        }
    }

    void locateApp(Catalog.AppDef app) {
        List<Catalog.Profile> profiles = profilesFor(app);
        String backend = profiles.isEmpty() ? "script" : profiles.get(0).backend();
        String chosen;
        if ("application".equals(backend)) {
            DirectoryDialog dialog = new DirectoryDialog(shell);
            dialog.setText("Locate " + app.displayName());
            dialog.setMessage("Pick the install folder for " + app.displayName());
            chosen = dialog.open();
        } else {
            FileDialog dialog = new FileDialog(shell, SWT.OPEN);
            dialog.setText("Locate " + app.displayName());
            chosen = dialog.open();
        }
        if (chosen == null) return;
        Path picked = Path.of(chosen);
        Path dir = Files.isDirectory(picked) ? picked : picked.getParent();
        if (dir == null) return;
        config.setInstallDir(app.id(), dir.toString());
        installDirs.put(app.id(), dir);
        refreshAppCard(app.id());
        updates.checkAppUpdate(app, dir);
    }

    /** Fresh install into <default folder>/<app id> (prompting for the
     *  folder on first use), then wire the location so the card flips to
     *  installed the moment the pipeline lands. */
    void installLatest(Catalog.AppDef app) {
        String folder = config.defaultInstallFolder();
        if (folder == null) {
            DirectoryDialog dialog = new DirectoryDialog(shell);
            dialog.setText("Choose the default install folder");
            dialog.setMessage("New apps install into folders inside this location.");
            folder = dialog.open();
            if (folder == null) return;
            config.setDefaultInstallFolder(folder);
        }
        Path destination = Path.of(folder).resolve(app.id());
        config.setInstallDir(app.id(), destination.toString());
        installDirs.put(app.id(), destination);
        updates.installAppUpdate(app, destination);
        refreshAppCard(app.id());
    }

    void refreshAppCard(String appId) {
        if (display.isDisposed()) return;
        Runnable job = () -> {
            if (shell.isDisposed()) return;
            for (Control child : cardsParent.getChildren()) {
                if (child instanceof AppCard card && card.app().id().equals(appId)) {
                    card.refreshDynamic();
                }
            }
        };
        if (Thread.currentThread() == display.getThread()) job.run(); else display.asyncExec(job);
    }

    // ── UpdateManager.Ui + LauncherState.Listener ────────────────────────

    @Override public void log(String level, String appId, String message) {
        String stamp = LocalTime.now().format(CLOCK);
        events.add(stamp + "  " + level.toUpperCase() + "  "
                + (appId == null ? "-" : appId) + "  " + message);
        while (events.size() > 400) events.remove(0);
        // Notify open views (Diagnostics sheet) without any polling.
        for (Runnable hook : eventHooks) hook.run();
    }

    private final List<Runnable> eventHooks = new CopyOnWriteArrayList<>();
    void addEventHook(Runnable hook) { eventHooks.add(hook); }
    void removeEventHook(Runnable hook) { eventHooks.remove(hook); }

    @Override public void appStatus(String appId, LauncherState.AppStatus status) {
        refreshAppCard(appId);
        onUi(() -> {
            updateAppBar();
            updateSidebarUpdatesRow();
        });
    }

    @Override public void downloadMessage(String message) {
        onUi(this::updateSelfBanner);
    }

    @Override public void selfState(boolean available, String tag, String message) {
        lastSelfCheckAt = System.currentTimeMillis();
        onUi(this::updateSelfBanner);
    }

    /** LauncherState callbacks fire on worker threads — always marshal. */
    private void onUi(Runnable job) {
        if (display.isDisposed()) return;
        display.asyncExec(() -> {
            if (shell.isDisposed()) return;
            job.run();
        });
    }

    private void updateSidebarUpdatesRow() {
        if (updatesRow == null || updatesRow.isDisposed()) return;
        long total = availableUpdateCount() + (updates.state().selfAvailable() ? 1 : 0);
        updatesRow.setText(total > 0 ? "Updates — " + total + " available" : "Updates");
        updatesRow.setAlert(total > 0);
    }

    boolean isRunning(String appId) { return running.containsKey(appId); }

    String profileChoice(String appId) { return profileChoice.get(appId); }
    void setProfileChoice(String appId, String profileId) { profileChoice.put(appId, profileId); }

    long availableUpdateCount() {
        return visibleApps().stream()
                .filter(a -> updates.state().app(a.id()).phase()
                        == LauncherState.AppStatus.Phase.UPDATE_AVAILABLE)
                .count();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────



    private void shutdown() {
        // Cancel the periodic self-check by reference (SWT forbids a null
        // runnable here — that IllegalArgumentException was the close-crash).
        // Sheet timers are gone: they refresh via LauncherState listeners now.
        if (periodicSelfCheck != null && !display.isDisposed()) {
            display.timerExec(-1, periodicSelfCheck);
        }
        if (periodicAppSweep != null && !display.isDisposed()) {
            display.timerExec(-1, periodicAppSweep);
        }
        updates.shutdown();
        for (Image image : chipCache.values()) if (!image.isDisposed()) image.dispose();
        for (Image image : rawIconCache.values()) if (!image.isDisposed()) image.dispose();
        for (Image image : systemIconCache.values()) if (!image.isDisposed()) image.dispose();
        tints.dispose();
        palette.dispose();
    }

    Image chip(Catalog.AppDef app) {
        return chipCache.computeIfAbsent(app.id(), id -> new Image(display, (gc, w, h) ->
                Glass.paintCategoryChip(gc, display, 0, 0, w,
                        UiPalette.hex(app.tintHex()), AppCard.categoryGlyph(app.category())),
                58, 58));
    }

    void dropSystemIcon(String appId) {
        Image stale = systemIconCache.remove(appId);
        if (stale != null && !stale.isDisposed()) stale.dispose();
    }

    /** Full-resolution custom icon PNG for the card canvas — NEVER
     *  pre-downscaled; the paint path scales it with high-quality sampling
     *  at the panel's zoom (the 1x-58px bitmap was what read pixelated). */
    Image customIconImage(Catalog.AppDef app) {
        if (!core.IconStore.hasCustomIcon(app.id())) return null;
        return rawIconCache.computeIfAbsent(app.id(), id -> {
            java.nio.file.Path png = core.IconStore.icon(id, true);
            if (!java.nio.file.Files.isRegularFile(png)) png = core.IconStore.icon(id, false);
            try {
                Image raw = new Image(display, png.toString());
                return raw.getImageData() == null ? null : raw;
            } catch (Throwable failure) {
                return null;
            }
        });
    }

    /** The rendered system icon of an installed app (NSWorkspace-quality,
     *  rendered by the native dylib into Support/icons/system). Cached
     *  per-session, full-res; stale renders are droppped on refresh. */
    private final Map<String, Image> systemIconCache = new ConcurrentHashMap<>();
    Image systemIconImage(Catalog.AppDef app) {
        if (!core.IconStore.hasSystemIcon(app.id())) return null;
        return systemIconCache.computeIfAbsent(app.id(), id -> {
            java.nio.file.Path png = core.IconStore.systemIcon(id, true);
            if (!java.nio.file.Files.isRegularFile(png)) png = core.IconStore.systemIcon(id, false);
            try {
                Image raw = new Image(display, png.toString());
                return raw.getImageData() == null ? null : raw;
            } catch (Throwable failure) {
                return null;
            }
        });
    }

    /** Render installed apps' real icons (their .app bundle's icon) once
     *  per session — the cards' default artwork replaces the letter chip. */
    private void renderSystemIcons() {
        updates.runBackground(() -> {
            for (Catalog.AppDef app : visibleApps()) {
                if (core.IconStore.hasCustomIcon(app.id())
                        || core.IconStore.hasSystemIcon(app.id())) continue;
                Path dir = installDirs.get(app.id());
                if (dir == null) continue;
                Path appBundle = findAppBundle(dir, app);
                if (appBundle != null) {
                    core.IconStore.renderSystemIcon(app.id(), appBundle);
                }
            }
            display.asyncExec(() -> { if (!shell.isDisposed()) rebuildCards(); });
        });
    }

    /** Locate the .app bundle to icon-render for this install: Rigel's
     *  Weather.app, Chiaki's Weather.app, etc. Search order: profile target's
     *  own bundle, any direct child *.app, profile env bundles. */
    static Path findAppBundle(Path installDir, Catalog.AppDef app) {
        try (var stream = Files.list(installDir)) {
            for (Path child : stream.filter(p -> p.getFileName().toString().endsWith(".app"))
                    .filter(Files::isDirectory).toList()) {
                return child;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    public void open() {
        if (!ProductGate.isActivated()) {
            if (!promptForKey()) return;
        }
        if (config.defaultInstallFolder() == null) {
            new FirstLaunchPrompt(this).open();
        }
        openWithoutGate();
    }

    /** The open path minus the product gate — used by the --ui-smoke
     *  harness (CI probe) and by open() after a successful activation. */
    public void openWithoutGate() {
        shell.open();
        ObjCCapture.apply();
        log("info", null, "BigM (Java) opened; " + visibleApps().size() + " catalog apps; capture " + ObjCCapture.status());
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
    }

    /** One-time activation: prompt for the universal product key, verify
     *  against the factory material, write the keychain marker on success. */
    private boolean promptForKey() {
        Shell gate = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        gate.setText("BigM Activation");
        gate.setSize(460, 220);
        gate.setLayout(new GridLayout(1, false));
        gate.setBackground(palette.chromeDeep);
        Label prompt = new Label(gate, SWT.WRAP);
        prompt.setText("Enter the universal product key (one-time activation for this Mac):");
        prompt.setFont(palette.body);
        prompt.setForeground(palette.textPrimary);
        prompt.setBackground(palette.chromeDeep);
        prompt.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        Text key = new Text(gate, SWT.BORDER | SWT.PASSWORD);
        key.setFont(palette.body);
        key.setBackground(palette.card);
        key.setForeground(palette.textPrimary);
        key.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Label feedback = new Label(gate, SWT.WRAP);
        feedback.setText("");
        feedback.setFont(palette.caption);
        feedback.setForeground(palette.warnFg);
        feedback.setBackground(palette.chromeDeep);
        feedback.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        Composite buttons = new Composite(gate, SWT.NO_BACKGROUND);
        buttons.setLayoutData(new GridData(SWT.RIGHT, SWT.BOTTOM, true, true));
        buttons.setLayout(new GridLayout(2, false));
        GlassButton activate = new GlassButton(buttons, palette, "Activate", GlassButton.Style.PRIMARY);
        GlassButton cancel = new GlassButton(buttons, palette, "Quit", GlassButton.Style.TOOL);
        boolean[] accepted = {false};
        activate.addListener(SWT.Selection, e -> {
            String error = ProductGate.activate(key.getText());
            if (error == null) {
                accepted[0] = true;
                // Activation unlocks updates on its own: pull the org's
                // current release-read token down the override channel
                // (Swift's launcher-token.txt flow) so no machine-owned
                // GitHub token is ever asked for after the product key.
                new Thread(() -> {
                    try {
                        core.GitHubAccess.refreshRemoteOverride(core.EmbeddedToken.VALUE);
                    } catch (Throwable ignored) {
                    }
                }, "bigm-token-bootstrap").start();
                gate.close();
            } else {
                String problem = ProductGate.keychainProblem();
                feedback.setText(problem != null ? problem : error);
                key.selectAll();
            }
        });
        cancel.addListener(SWT.Selection, e -> gate.close());
        key.addListener(SWT.Traverse, e -> {
            if (e.detail == SWT.TRAVERSE_RETURN) {
                activate.notifyListeners(SWT.Selection, new Event());
            }
        });
        gate.open();
        while (!gate.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
        return accepted[0];
    }

    // Accessors shared with cards + sheets (package-private on purpose).
    public Display swtDisplay() { return display; }
    public Shell shell() { return shell; }
    public UiPalette palette() { return palette; }
    Glass.TintCache tints() { return tints; }
    Catalog catalog() { return catalog; }
    LauncherConfig config() { return config; }
    PlayStats stats() { return stats; }
    UpdateManager updates() { return updates; }
    List<String> events() { return events; }
    Path installDir(String appId) { return installDirs.get(appId); }
    void markInstallDir(String appId, Path dir) { installDirs.put(appId, dir); }
}
