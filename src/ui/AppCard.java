package ui;

import core.Catalog;
import core.LauncherState;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import java.util.List;

/**
 * One library card (the SwiftUI LauncherAppCard): tinted icon chip, name,
 * tracked category + play-stats line, two-line summary, profile pill,
 * status badge, and the Launch / Stop / Install / Locate / Reveal buttons.
 * Update+download state from LauncherState renders as a status line with a
 * thin progress bar, matching the "status chips + progress %" requirement.
 */
public final class AppCard extends Composite {

    private final LauncherWindow window;
    private final Catalog.AppDef app;
    private final UiPalette palette;
    private final Badge badge;
    private final Label statusLine;
    private final ProgressSlice progress;
    private final GlassButton launchButton;
    private final GlassButton stopButton;
    private final GlassButton extraButton;    // Install… / Locate… / Reveal
    private final GlassButton updateButton;   // Update to <tag>
    private final Label statsLine;
    private final boolean[] hover = {false};

    AppCard(LauncherWindow window, Composite parent, Catalog.AppDef app) {
        super(parent, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        this.window = window;
        this.app = app;
        this.palette = window.palette();
        setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        // Bigger radius + translucent fill: the mountain scene breathes
        // through the cards; hover adds the top sheen (static repaint — no
        // animation loops).
        addPaintListener(e -> Glass.paintCard(e.gc, palette, getClientArea(),
                22, window.tints().get(app.tintHex()), hover[0], false));
        addListener(SWT.MouseEnter, e -> { hover[0] = true; redraw(); });
        addListener(SWT.MouseExit, e -> { hover[0] = false; redraw(); });

        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 16;
        layout.marginHeight = 14;
        layout.verticalSpacing = 8;
        setLayout(layout);

        // Row 1: chip | titles | badge
        Composite top = new Composite(this, SWT.NO_BACKGROUND);
        top.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout tl = new GridLayout(3, false);
        tl.marginWidth = 0;
        tl.marginHeight = 0;
        tl.horizontalSpacing = 12;
        top.setLayout(tl);

        Composite icon = new Composite(top, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED) {
            @Override public org.eclipse.swt.graphics.Point computeSize(int w, int h, boolean c) {
                return new org.eclipse.swt.graphics.Point(58, 58);
            }
        };
        // Direct GC paint: custom PNG scales at the panel's zoom with
        // high-quality sampling; the fallback chip is vector-drawn. No 1x
        // bitmap ever hits the Retina panel (that's what read pixelated).
        icon.addPaintListener(e -> {
            Rectangle b = icon.getClientArea();
            int size = Math.min(b.width, b.height);
            org.eclipse.swt.graphics.Image custom = window.customIconImage(app);
            org.eclipse.swt.graphics.Image system = custom == null ? window.systemIconImage(app) : null;
            if (custom != null || system != null) {
                org.eclipse.swt.graphics.Image img = custom != null ? custom : system;
                e.gc.setAntialias(SWT.ON);
                e.gc.setInterpolation(SWT.HIGH);
                var rd = img.getImageData();
                e.gc.drawImage(img, 0, 0, rd.width, rd.height, b.x, b.y, size, size);
            } else {
                // Category emblem, not initials: thematic chip.
                Glass.paintCategoryChip(e.gc, getDisplay(), b.x, b.y, size,
                        UiPalette.hex(app.tintHex()), categoryGlyph(app.category()));
            }
        });
        GridData iconData = new GridData(SWT.LEFT, SWT.TOP, false, false);
        iconData.widthHint = 58;
        iconData.heightHint = 58;
        icon.setLayoutData(iconData);

        Composite titles = new Composite(top, SWT.NO_BACKGROUND);
        GridLayout ttl = new GridLayout(1, false);
        ttl.marginWidth = 0;
        ttl.marginHeight = 0;
        ttl.verticalSpacing = 2;
        titles.setLayout(ttl);
        titles.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Label name = new Label(titles, SWT.NO_BACKGROUND);
        name.setText(app.displayName());
        name.setFont(palette.headline);
        name.setForeground(palette.textPrimary);
        Label category = new Label(titles, SWT.NO_BACKGROUND);
        category.setText(categoryName(app.category()).toUpperCase());
        category.setFont(palette.tiny);
        category.setForeground(window.tints().get(app.tintHex()));
        statsLine = new Label(titles, SWT.NO_BACKGROUND);
        statsLine.setFont(palette.caption);
        statsLine.setForeground(palette.textMuted);

        badge = new Badge(top, palette);
        badge.setLayoutData(new GridData(SWT.RIGHT, SWT.TOP, false, false));

        // Row 2: summary (2-line)
        Label summary = new Label(this, SWT.WRAP | SWT.NO_BACKGROUND);
        summary.setText(app.summary());
        summary.setFont(palette.caption);
        summary.setForeground(palette.textMuted);
        GridData sd = new GridData(SWT.FILL, SWT.TOP, true, false);
        sd.heightHint = 2 * gcLineHeight() + 4;
        summary.setLayoutData(sd);

        // Row 3: profile pill / picker (custom-painted — a native Combo
        //  carries Cocoa's opaque light plate and reads as a gray slab
        //  over the scene)
        List<Catalog.Profile> profiles = window.profilesFor(app);
        if (profiles.size() > 1) {
            ProfilePicker picker = new ProfilePicker(this, profiles);
            picker.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            String wanted = window.profileChoice(app.id());
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i).id().equals(wanted)) picker.select(i);
            }
            picker.addListener(SWT.Selection, e ->
                    window.setProfileChoice(app.id(), profiles.get(picker.index()).id()));
        } else if (!profiles.isEmpty()) {
            Label pill = new Label(this, SWT.NO_BACKGROUND);
            pill.setText("▣  " + profiles.get(0).name());
            pill.setFont(palette.small);
            pill.setForeground(palette.textMuted);
            GridData pd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
            pill.setLayoutData(pd);
        }

        // Row 4: update/status line + progress
        statusLine = new Label(this, SWT.WRAP | SWT.NO_BACKGROUND);
        statusLine.setFont(palette.caption);
        statusLine.setForeground(palette.textMuted);
        GridData st = new GridData(SWT.FILL, SWT.TOP, true, false);
        st.exclude = true;
        statusLine.setLayoutData(st);
        progress = new ProgressSlice(this);
        GridData pg = new GridData(SWT.FILL, SWT.TOP, true, false);
        pg.exclude = true;
        progress.setLayoutData(pg);

        // Row 5: buttons
        Composite buttons = new Composite(this, SWT.NO_BACKGROUND);
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, true));
        GridLayout bl = new GridLayout(5, false);
        bl.marginWidth = 0;
        bl.marginHeight = 0;
        bl.horizontalSpacing = 7;
        buttons.setLayout(bl);

        launchButton = new GlassButton(buttons, palette, "Launch", GlassButton.Style.PRIMARY);
        launchButton.setTint(window.tints().get(app.tintHex()));
        launchButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        launchButton.addListener(SWT.Selection, e -> launch());

        stopButton = new GlassButton(buttons, palette, "Stop", GlassButton.Style.TOOL);
        stopButton.setLayoutData(excluded(false));
        stopButton.addListener(SWT.Selection, e -> window.quitApp(app));

        updateButton = new GlassButton(buttons, palette, "Update", GlassButton.Style.TOOL);
        updateButton.setLayoutData(excluded(true));
        updateButton.addListener(SWT.Selection, e -> {
            var at = window.installDir(app.id());
            if (at != null) window.updates().installAppUpdate(app, at);
        });

        extraButton = new GlassButton(buttons, palette, "Install…", GlassButton.Style.TOOL);
        extraButton.setLayoutData(excluded(false));
        extraButton.addListener(SWT.Selection, e -> {
            String label = extraButton.getText();
            if ("Install…".equals(label)) window.installLatest(app);
            else if ("Locate…".equals(label)) window.locateApp(app);
            else window.revealApp(app);
        });

        GlassButton more = new GlassButton(buttons, palette, "⋯", GlassButton.Style.TOOL);
        Menu menu = cardMenu();
        more.addListener(SWT.Selection, e -> {
            Point p = more.toDisplay(0, more.getSize().y);
            menu.setLocation(p);
            menu.setVisible(true);
        });

        setMenu(menu);
        refreshDynamic();
    }

    /** Grid cell sizing helper: measure the card at the column width and
     *  hand GridLayout concrete hints (a bare FILL/FILL GridData came back
     *  0-height in practice — the whole grid collapsed). */
    void setCellWidth(int width) {
        Point natural = computeSize(width, SWT.DEFAULT);
        GridData data = new GridData(SWT.FILL, SWT.TOP, true, false);
        data.widthHint = width;
        data.heightHint = natural.y;
        setLayoutData(data);
    }

    private GridData excluded(boolean startExcluded) {
        GridData data = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        data.exclude = startExcluded;
        return data;
    }

    private int gcLineHeight() {
        GC gc = new GC(this);
        gc.setFont(palette.caption);
        int h = gc.textExtent("Mg").y;
        gc.dispose();
        return h;
    }

    private Menu cardMenu() {
        Menu menu = new Menu(this);
        menuItem(menu, "Launch", () -> launch());
        menuItem(menu, "Quit", () -> window.quitApp(app));
        new MenuItem(menu, SWT.SEPARATOR);
        if (app.updatable()) {
            menuItem(menu, "Install Latest…", () -> {
                if (window.isInstalled(app)) {
                    var at = window.installDir(app.id());
                    if (at != null) window.updates().installAppUpdate(app, at);
                } else {
                    window.installLatest(app);
                }
            });
        }
        menuItem(menu, "Locate…", () -> window.locateApp(app));
        menuItem(menu, "Reveal", () -> window.revealApp(app));
        new MenuItem(menu, SWT.SEPARATOR);
        menuItem(menu, "Change Icon…", () -> window.pickCustomIcon(app));
        if (core.IconStore.hasCustomIcon(app.id())) {
            menuItem(menu, "Reset Icon", () -> window.resetCustomIcon(app));
        }
        MenuItem protection = new MenuItem(menu, SWT.CHECK);
        protection.setText("Screenshot + Share Protection");
        protection.setSelection(window.config().captureProtection(app.id()));
        protection.addListener(SWT.Selection, e -> window.config()
                .setCaptureProtection(app.id(), protection.getSelection()));
        return menu;
    }

    private static void menuItem(Menu menu, String text, Runnable action) {
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(text);
        item.addListener(SWT.Selection, e -> action.run());
    }

    private void launch() {
        List<Catalog.Profile> profiles = window.profilesFor(app);
        if (profiles.isEmpty()) return;
        String wanted = window.profileChoice(app.id());
        Catalog.Profile chosen = profiles.stream()
                .filter(p -> p.id().equals(wanted)).findFirst().orElse(profiles.get(0));
        window.launchApp(app, chosen);
    }

    /** Recompute badge, status line, buttons from the window's live state.
     *  Called on build, on LauncherState callbacks, and on launch/exit. */
    void refreshDynamic() {
        // Stats line (cheap: props are memory-resident)
        String statsText = window.stats().summaryLine(app.id());
        statsLine.setText(statsText == null ? "Never launched" : statsText);
        statsLine.setVisible(true);

        LauncherState.AppStatus status = window.updates().state().app(app.id());
        boolean running = window.isRunning(app.id());
        boolean installed = window.isInstalled(app);
        boolean downloading = status.phase() == LauncherState.AppStatus.Phase.DOWNLOADING
                || status.phase() == LauncherState.AppStatus.Phase.INSTALLING;

        // Badge
        if (running) badge.set("LIVE", palette.liveFg, true);
        else switch (status.phase()) {
            case CHECKING -> badge.set("CHECKING", palette.infoFg, true);
            case DOWNLOADING, INSTALLING -> badge.set("UPDATING", palette.infoFg, true);
            case UPDATE_AVAILABLE -> badge.set("UPDATE", palette.warnFg, false);
            case FAILED -> badge.set("ERROR", palette.errFg, false);
            default -> badge.set(installed ? "READY" : "MISSING",
                    installed ? palette.okFg : palette.warnFg, false);
        }

        // Status line + progress: only when there is something to say
        String message = status.message();
        boolean hasMessage = message != null && !message.isBlank()
                && status.phase() != LauncherState.AppStatus.Phase.IDLE;
        setExcluded(statusLine, !hasMessage);
        if (hasMessage) {
            statusLine.setText(message);
            statusLine.setForeground(status.phase() == LauncherState.AppStatus.Phase.FAILED
                    ? palette.errFg
                    : status.phase() == LauncherState.AppStatus.Phase.UPDATE_AVAILABLE
                    ? palette.warnFg : palette.textMuted);
        }
        Double fraction = downloading ? parsePercent(message) : null;
        setExcluded(progress, fraction == null);
        if (fraction != null) progress.setFraction(fraction);

        // Buttons
        launchButton.setEnabled(installed && !running && !downloading);
        launchButton.setText(running ? "Running" : "Launch");
        setExcluded(stopButton, !running);
        boolean canUpdate = installed && app.updatable()
                && status.phase() == LauncherState.AppStatus.Phase.UPDATE_AVAILABLE;
        setExcluded(updateButton, !canUpdate);
        showExtra(installed
                ? "Reveal"
                : app.updatable() ? "Install…" : "Locate…");

        // Cells are measured+hinted by the grid (setCellWidth); content
        // growth/shrink (Stop appears, status line folds in) changes the
        // natural height, so the grid must re-hint or buttons get clipped
        // — the "card shrinks and eats its buttons" bug.
        window.relayoutGrid();
    }

    private void showExtra(String label) {
        GridData data = (GridData) extraButton.getLayoutData();
        extraButton.setText(label);
        data.exclude = false;
        extraButton.setVisible(true);
    }

    private static Double parsePercent(String message) {
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

    private static void setExcluded(Control control, boolean exclude) {
        GridData data = (GridData) control.getLayoutData();
        if (data == null || data.exclude == exclude) return;
        data.exclude = exclude;
        control.setVisible(!exclude);
    }

    Catalog.AppDef app() { return app; }

    /** Category → the emblem glyph used on the card chip + get-apps rows. */
    static Glass.Glyph categoryGlyph(String category) {
        return switch (category == null ? "" : category) {
            case "streaming" -> Glass.Glyph.PLAY;
            case "remote-play" -> Glass.Glyph.RADIO;
            case "browser" -> Glass.Glyph.GLOBE;
            case "minecraft" -> Glass.Glyph.CUBE;
            case "emulator" -> Glass.Glyph.GAMEPAD;
            case "games" -> Glass.Glyph.WINE;
            case "development" -> Glass.Glyph.BRACES;
            default -> Glass.Glyph.GRID;
        };
    }

    static String categoryName(String raw) {
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

    /** Custom profile selector: a pill showing the chosen profile with a
     *  popup menu on click — visually integrated, no native Combo plate. */
    private final class ProfilePicker extends Composite {
        private final List<Catalog.Profile> profiles;
        private int index = 0;
        private final Menu menu;
        ProfilePicker(Composite parent, List<Catalog.Profile> profiles) {
            super(parent, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
            this.profiles = profiles;
            menu = new Menu(this);
            for (int i = 0; i < profiles.size(); i++) {
                final int at = i;
                MenuItem item = new MenuItem(menu, SWT.RADIO);
                item.setText(profiles.get(i).name());
                item.addListener(SWT.Selection, e -> select(at));
            }
            addListener(SWT.MouseUp, e -> {
                if (e.button == 1) {
                    for (int i = 0; i < profiles.size(); i++) menu.getItem(i).setSelection(i == index);
                    menu.setLocation(toDisplay(0, getSize().y));
                    menu.setVisible(true);
                }
            });
            setCursor(window.swtDisplay().getSystemCursor(SWT.CURSOR_HAND));
            addPaintListener(e -> {
                var b = getClientArea();
                e.gc.setAntialias(SWT.ON);
                e.gc.setAlpha(40);
                e.gc.setBackground(window.palette().textPrimary);
                e.gc.fillRoundRectangle(b.x, b.y, b.width - 1, b.height - 1, 18, 18);
                e.gc.setAlpha(255);
                Glass.paintGlyph(e.gc, getDisplay(), Glass.Glyph.LIST, b.x + 8,
                        b.y + (b.height - 13) / 2, 13, window.tints().get(app.tintHex()));
                e.gc.setFont(window.palette().caption);
                e.gc.setForeground(window.palette().textPrimary);
                String text = profiles.get(index).name();
                var te = e.gc.textExtent(text);
                e.gc.drawText(text, b.x + 28, b.y + (b.height - te.y) / 2, true);
                Glass.paintGlyph(e.gc, getDisplay(), Glass.Glyph.CHEVRON,
                        b.x + b.width - 20, b.y + (b.height - 10) / 2, 10,
                        window.palette().textMuted);
            });
        }
        void select(int index) {
            this.index = index;
            redraw();
            Event ev = new Event();
            ev.widget = this;
            notifyListeners(SWT.Selection, ev);
        }
        int index() { return index; }
    }

    /** Thin accent progress bar for the download line. */
    private static final class ProgressSlice extends Composite {
        private double fraction;
        ProgressSlice(Composite parent) {
            super(parent, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
            UiPalette palette = ((AppCard) parent).palette;
            addPaintListener(e -> {
                Rectangle b = getClientArea();
                e.gc.setAntialias(SWT.ON);
                e.gc.setAlpha(40);
                e.gc.setBackground(palette.textMuted);
                e.gc.fillRoundRectangle(b.x, b.y + 2, b.width, 4, 4, 4);
                e.gc.setAlpha(255);
                e.gc.setBackground(palette.accent());
                e.gc.fillRoundRectangle(b.x, b.y + 2,
                        (int) (b.width * Math.max(0.02, Math.min(1, fraction))), 4, 4, 4);
            });
        }
        void setFraction(double fraction) {
            this.fraction = fraction;
            redraw();
        }
    }
}
