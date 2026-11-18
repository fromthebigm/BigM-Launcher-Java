package ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/**
 * Base for the modal sheets (Updates, Get Apps, Customize, Settings…):
 * a dark dialog on the launcher palette with the aurora backdrop, a card
 * factory matching the main window's surfaces, and a scrolled content body.
 */
public abstract class SheetShell {

    protected final LauncherWindow window;
    protected final UiPalette palette;
    protected final Shell sheet;

    protected SheetShell(LauncherWindow window, String title, int width, int height) {
        this.window = window;
        this.palette = window.palette();
        // Borderless, like SwiftUI .sheet(): no native NSWindow gray frame.
        sheet = new Shell(window.shell(), SWT.NO_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        sheet.setText(title);
        sheet.setSize(width, height);
        sheet.setBackgroundMode(SWT.INHERIT_DEFAULT);
        sheet.setBackground(palette.chromeDeep);
        sheet.addPaintListener(e -> {
            Rectangle b = sheet.getClientArea();
            int h = Math.max(1, b.height - 4);
            e.gc.setAntialias(SWT.ON);
            e.gc.setAlpha(55);
            e.gc.setBackground(e.gc.getDevice().getSystemColor(SWT.COLOR_BLACK));
            e.gc.fillRoundRectangle(b.x, b.y + 4, b.width, h, 28, 28);
            e.gc.setAlpha(255);
            e.gc.setBackground(palette.chromeDeep);
            e.gc.fillRoundRectangle(b.x, b.y, b.width, h, 28, 28);
            e.gc.setForeground(palette.separator);
            e.gc.drawRoundRectangle(b.x, b.y, b.width - 1, h - 1, 28, 28);
        });
        sheet.addListener(SWT.Resize, e -> {
            Rectangle b = sheet.getClientArea();
            if (!sheet.isDisposed())
                sheet.setRegion(Glass.roundedRegion(sheet.getDisplay(), 0, 0,
                        Math.max(1, b.width), Math.max(1, b.height - 4), 18));
        });
    }

    public void open() {
        fill(sheet);
        sheet.open();
        ObjCCapture.apply();
        System.err.println("[bigm] sheet capture: " + ObjCCapture.status());
        // Apply immediately; a 1-second SWT timer also re-applies to any
        // window that appears later.
        // Content laid out before the window exists measures at the sheet's
        // pre-open size; re-measure now that the real client area is known.
        org.eclipse.swt.widgets.Display.getCurrent().asyncExec(() -> {
            if (sheet.isDisposed()) return;
            for (org.eclipse.swt.widgets.Control c : sheet.getChildren()) {
                if (c instanceof Composite composite) layoutRecursively(composite);
            }
        });
    }

    private static void layoutRecursively(Composite composite) {
        Object resize = composite.getData("resize");
        if (resize instanceof Runnable r) r.run();
        for (org.eclipse.swt.widgets.Control child : composite.getChildren()) {
            if (child instanceof Composite cc) layoutRecursively(cc);
        }
    }

    protected abstract void fill(Shell sheet);

    /** Scrolled vertical content body. The sheet bug class: ScrolledComposite
     *  content kept its initial (0-height) bounds — every section existed in
     *  the dump yet rendered as nothing. Fix: compute + setSize explicitly on
     *  content change AND on resize, and let the mouse wheel scroll it
     *  (ScrolledComposite doesn't grab wheel events on its own here). */
    protected Composite scrolledBody(Shell parent) {
        Composite holder = new Composite(parent, SWT.NO_BACKGROUND);
        holder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        holder.setLayout(new GridLayout(1, false));
        ScrolledComposite scroller = new ScrolledComposite(holder, SWT.V_SCROLL | SWT.NO_BACKGROUND);
        scroller.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scroller.setExpandHorizontal(true);
        clearNativeScrollerPlate(scroller);
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
            Point size = body.computeSize(width, SWT.DEFAULT);
            scroller.setMinSize(size);
            body.setSize(size);
        };
        body.addListener(SWT.Resize, e -> resize.run());
        scroller.addListener(SWT.Resize, e -> resize.run());
        Glass.addWheelScroll(scroller);
        body.setData("scroller", scroller);
        body.setData("resize", resize);
        return body;
    }

    /** Clear the AppKit gray plate behind NSScrollView/NSClipView. SWT's
     *  NO_BACKGROUND does not stop NSClipView from painting its document plate. */
    private static void clearNativeScrollerPlate(ScrolledComposite sc) {
        try {
            if (sc.view == null || sc.view.id == 0) return;
            long nsColor = org.eclipse.swt.internal.cocoa.OS.objc_msgSend(
                    org.eclipse.swt.internal.cocoa.OS.objc_getClass("NSColor"),
                    org.eclipse.swt.internal.cocoa.OS.sel_registerName("clearColor"));
            org.eclipse.swt.internal.cocoa.OS.objc_msgSend(sc.view.id,
                    org.eclipse.swt.internal.cocoa.OS.sel_registerName("setBackgroundColor:"), nsColor);
            long clipView = org.eclipse.swt.internal.cocoa.OS.objc_msgSend(sc.view.id,
                    org.eclipse.swt.internal.cocoa.OS.sel_registerName("contentView"));
            if (clipView != 0)
                org.eclipse.swt.internal.cocoa.OS.objc_msgSend(clipView,
                        org.eclipse.swt.internal.cocoa.OS.sel_registerName("setBackgroundColor:"), nsColor);
        } catch (Throwable ignored) {
        }
    }

    /** Glass card with a section header (vector glyph + tinted title). */
    protected Composite card(Composite parent, String title, Glass.Glyph glyph, Color tint) {
        Composite card = new Composite(parent, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        card.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        card.addPaintListener(e -> Glass.paintCard(e.gc, palette, card.getClientArea(),
                16, tint, false, false));
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 16;
        layout.marginHeight = 12;
        layout.verticalSpacing = 10;
        card.setLayout(layout);
        if (title != null) {
            Composite header = new Composite(card, SWT.NO_BACKGROUND);
            header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            GridLayout hl = new GridLayout(2, false);
            hl.marginWidth = 0;
            hl.marginHeight = 0;
            hl.horizontalSpacing = 8;
            header.setLayout(hl);
            if (glyph != null) {
                new Glass.GlyphView(header, 15, glyph,
                        tint == null ? palette.textPrimary : tint);
            }
            Label text = new Label(header, SWT.NO_BACKGROUND);
            text.setText(title);
            text.setFont(palette.headline);
            text.setForeground(tint == null ? palette.textPrimary : tint);
        }
        return card;
    }

    protected Label bodyText(Composite parent, String text) {
        Label label = new Label(parent, SWT.WRAP | SWT.NO_BACKGROUND);
        label.setText(text);
        label.setFont(palette.caption);
        label.setForeground(palette.textMuted);
        label.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        return label;
    }
}
