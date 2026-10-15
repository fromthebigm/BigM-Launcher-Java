package ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

/**
 * Scenery (the Swift Theme Builder, mountain-dusk edition): pick a scene
 * (Summit / Pine / Dusk / Glacier / Ember), each card shows a live painted
 * miniature of its sky + ridges. Applies instantly, persists to
 * config.properties, and never disposes colors that a painter might still
 * be holding (the old accent swap crashed precisely there).
 */
public final class CustomizeSheet extends SheetShell {

    public CustomizeSheet(LauncherWindow window) {
        super(window, "Scenery", 640, 600);
    }

    @Override protected void fill(Shell sheet) {
        sheet.setLayout(new GridLayout(1, false));

        Composite plain = new Composite(sheet, SWT.NO_BACKGROUND);
        plain.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        plain.setLayout(layout);
        Composite body = scrolledBodyInto(plain);

        Composite header = new Composite(body, SWT.NO_BACKGROUND);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout hl = new GridLayout(3, false);
        hl.marginWidth = 0;
        hl.marginHeight = 0;
        header.setLayout(hl);
        Label title = new Label(header, SWT.NO_BACKGROUND);
        title.setText("Scenery");
        title.setFont(palette.libTitle);
        title.setForeground(palette.textPrimary);
        Label spacer = new Label(header, SWT.NO_BACKGROUND);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GlassButton done = new GlassButton(header, palette, "Done", GlassButton.Style.TOOL);
        done.addListener(SWT.Selection, e -> sheet.close());

        Label sub = new Label(body, SWT.WRAP | SWT.NO_BACKGROUND);
        sub.setText("Pick the scene the launcher sits in. It applies instantly and is saved to this launcher's portable folder.");
        sub.setFont(palette.caption);
        sub.setForeground(palette.textMuted);
        sub.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        // ── Scene previews ──
        Composite sceneCard = card(body, "Scenes", Glass.Glyph.BRUSH, palette.accent());
        Composite grid = new Composite(sceneCard, SWT.NO_BACKGROUND);
        grid.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout gl = new GridLayout(3, true);
        gl.horizontalSpacing = 12;
        gl.verticalSpacing = 12;
        gl.marginWidth = 0;
        gl.marginHeight = 4;
        grid.setLayout(gl);
        for (UiPalette.UiTheme theme : UiPalette.UiTheme.values()) {
            buildSceneCell(grid, theme, sheet);
        }

        // ── Background style ── (painted toggle rows, not native radios:
        // Cocoa paints an opaque system-default plate under native Buttons,
        // which read as the gray slab over the scene)
        Composite bgCard = card(body, "Window Background", Glass.Glyph.GRID, palette.accent());
        String current = window.config().backgroundStyle();
        for (String[] pair : new String[][]{
                {"glass", "Mountain Night"}, {"solid", "Solid"}}) {
            final String key = pair[0];
            Composite row = new Composite(bgCard, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
            GridData rd = new GridData(SWT.FILL, SWT.TOP, true, false);
            rd.heightHint = 30;
            row.setLayoutData(rd);
            row.setCursor(display().getSystemCursor(SWT.CURSOR_HAND));
            row.addListener(SWT.MouseUp, e -> {
                window.config().setBackgroundStyle(key);
                repaintAll(sheet);
            });
            row.addPaintListener(e -> {
                var b = row.getClientArea();
                boolean selected = window.config().backgroundStyle().equals(key);
                e.gc.setAntialias(SWT.ON);
                // dot
                e.gc.setForeground(selected ? palette.accent() : palette.textMuted);
                e.gc.drawOval(b.x + 2, b.y + 9, 12, 12);
                if (selected) e.gc.fillOval(b.x + 5, b.y + 12, 6, 6);
                e.gc.setFont(palette.body);
                e.gc.setForeground(selected ? palette.textPrimary : palette.textMuted);
                e.gc.drawText(pair[1], b.x + 22, b.y + 8, true);
            });
        }
        Label note = new Label(bgCard, SWT.WRAP | SWT.NO_BACKGROUND);
        note.setText("Mountain Night paints the scene behind the library; Solid keeps a plain "
                + "dark window (lowest energy).");
        note.setFont(palette.caption);
        note.setForeground(palette.textMuted);
        GridData nd = new GridData(SWT.FILL, SWT.TOP, true, false);
        note.setLayoutData(nd);

        // ── Launcher Icon ──
        Composite iconCard = card(body, "Launcher Icon", Glass.Glyph.CUBE, palette.accent());
        bodyText(iconCard, "BigM's own Dock icon follows the Google Chrome convention. Pick any "
                + "app in /Applications and its icon (light and dark variants are both kept) "
                + "replaces Voice Memos. It's applied live, no relaunch needed.");
        Composite iconRow = new Composite(iconCard, SWT.NO_BACKGROUND);
        iconRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout ir = new GridLayout(2, false);
        ir.marginWidth = 0;
        ir.marginHeight = 0;
        ir.horizontalSpacing = 8;
        iconRow.setLayout(ir);
        Label iconMsg = new Label(iconCard, SWT.WRAP | SWT.NO_BACKGROUND);
        iconMsg.setFont(palette.caption);
        iconMsg.setForeground(palette.warnFg);
        GridData im = new GridData(SWT.FILL, SWT.TOP, true, false);
        im.exclude = true;
        iconMsg.setLayoutData(im);
        GlassButton changeLauncherIcon = new GlassButton(iconRow, palette,
                "Change Launcher Icon…", GlassButton.Style.TOOL);
        GlassButton resetLauncherIcon = new GlassButton(iconRow, palette,
                "Reset to Voice Memos", GlassButton.Style.TOOL);
        resetLauncherIcon.setEnabled(core.IconStore.hasCustomIcon(core.IconStore.LAUNCHER_ID));
        changeLauncherIcon.addListener(SWT.Selection, e -> {
            DirectoryDialog dialog = new DirectoryDialog(sheet);
            dialog.setText("Choose the app whose icon BigM Launcher should use");
            dialog.setFilterPath("/Applications");
            String picked = dialog.open();
            if (picked == null) return;
            boolean ok = core.IconStore.applyCustomIcon(core.IconStore.LAUNCHER_ID,
                    java.nio.file.Path.of(picked));
            if (!ok) {
                iconMsg.setText("Could not render an icon from "
                        + java.nio.file.Path.of(picked).getFileName() + ".");
                ((GridData) iconMsg.getLayoutData()).exclude = false;
                iconMsg.setVisible(true);
                iconCard.layout(true, true);
                markContentDirty(plain);
                return;
            }
            core.IconStore.saveLauncherMode(core.IconStore.Mode.SYSTEM);
            core.IconStore.applyLauncherIcon();
            resetLauncherIcon.setEnabled(true);
            iconMsg.setText("Icon updated and applied live.");
            ((GridData) iconMsg.getLayoutData()).exclude = false;
            iconMsg.setVisible(true);
            iconCard.layout(true, true);
            markContentDirty(plain);
        });
        resetLauncherIcon.addListener(SWT.Selection, e -> {
            core.IconStore.resetLauncherIcon();
            resetLauncherIcon.setEnabled(false);
            iconMsg.setText("Reset to the bundled Voice Memos icon.");
            ((GridData) iconMsg.getLayoutData()).exclude = false;
            iconMsg.setVisible(true);
            iconCard.layout(true, true);
            markContentDirty(plain);
        });

        body.layout(true, true);
    }

    /** Scroll body hosted inside a caller-provided parent composite. */
    private Composite scrolledBodyInto(Composite parent) {
        Composite holder = new Composite(parent, SWT.NO_BACKGROUND);
        holder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        holder.setLayout(new GridLayout(1, false));
        var scroller = new org.eclipse.swt.custom.ScrolledComposite(holder, SWT.V_SCROLL);
        scroller.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scroller.setExpandHorizontal(true);
        Composite body = new Composite(scroller, SWT.NO_BACKGROUND);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 22;
        layout.marginHeight = 18;
        layout.verticalSpacing = 16;
        body.setLayout(layout);
        scroller.setContent(body);
        Runnable resize = () -> {
            if (body.isDisposed() || scroller.isDisposed()) return;
            int width = scroller.getClientArea().width;
            var size = body.computeSize(width, SWT.DEFAULT);
            scroller.setMinSize(size);
            body.setSize(size);
        };
        body.addListener(SWT.Resize, e -> resize.run());
        scroller.addListener(SWT.Resize, e -> resize.run());
        Glass.addWheelScroll(scroller);
        parent.setData("scroller", scroller);
        resize.run();
        return body;
    }

    private void markContentDirty(Composite plain) {
        if (!(plain.getData("scroller") instanceof org.eclipse.swt.custom.ScrolledComposite sc)) {
            return;
        }
        if (!(sc.getContent() instanceof Composite body) || body.isDisposed()) return;
        var size = body.computeSize(sc.getClientArea().width, SWT.DEFAULT);
        sc.setMinSize(size);
        body.setSize(size);
    }

    /** A scene cell: a painted thumbnail (the real backdrop painter at
     *  small scale) + theme title + selected ring. */
    private void buildSceneCell(Composite parent, UiPalette.UiTheme theme, Shell sheet) {
        Composite cell = new Composite(parent, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        GridData data = new GridData(SWT.FILL, SWT.TOP, true, false);
        data.heightHint = 124;
        cell.setLayoutData(data);
        cell.setCursor(display().getSystemCursor(SWT.CURSOR_HAND));
        cell.addListener(SWT.MouseUp, e -> {
            if (e.button != 1) return;
            window.config().setUiAccent(theme.key);
            palette.setTheme(theme.key);
            repaintAll(sheet);
        });
        cell.addPaintListener(e -> {
            var b = cell.getClientArea();
            e.gc.setAntialias(SWT.ON);
            int arc = 14;
            // Miniature of the real scene: paint it straight into the cell.
            var sceneArea = new org.eclipse.swt.graphics.Rectangle(b.x, b.y, b.width - 1, b.height - 58);
            Glass.paintThumbnail(e.gc, display(), sceneArea, theme);
            boolean selected = palette.themeKey().equals(theme.key);
            if (selected) {
                e.gc.setLineWidth(2);
                e.gc.setForeground(palette.accent());
                e.gc.drawRoundRectangle(b.x, b.y, b.width - 1, b.height - 58, arc, arc);
                e.gc.setLineWidth(1);
            }
            e.gc.setFont(palette.body);
            String title = theme.title;
            var te = e.gc.textExtent(title);
            e.gc.setForeground(selected ? palette.accent() : palette.textPrimary);
            e.gc.drawText(title, b.x + (b.width - te.x) / 2, b.y + b.height - 48, true);
        });
    }

    private void repaintAll(Shell sheet) {
        window.shell().redraw();
        redrawDeep(window.shell());
        sheet.redraw();
        redrawDeep(sheet);
    }

    private static void redrawDeep(Composite c) {
        c.redraw();
        for (Control child : c.getChildren()) {
            if (child instanceof Composite cc) redrawDeep(cc); else child.redraw();
        }
    }

    private Display display() { return window.swtDisplay(); }
}
