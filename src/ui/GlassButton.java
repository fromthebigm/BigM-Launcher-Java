package ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.widgets.Event;

/**
 * A hand-painted push button, because SWT's native Button can't be themed
 * dark (Apple owns its painting). Three styles mirror the SwiftUI design:
 * PRIMARY (accent gradient capsule, white text), TOOL (thin glass chip),
 * and QUIET (text-only sidebar action with hover wash).
 */
public final class GlassButton extends Composite {

    public enum Style { PRIMARY, TOOL, QUIET }

    private final UiPalette palette;
    private final Style style;
    private String text;
    private Color tintOverride;   // PRIMARY face override (e.g. orange banner CTA)
    private boolean enabled = true;
    private boolean hover, armed;

    public GlassButton(Composite parent, UiPalette palette, String text, Style style) {
        super(parent, SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
        this.palette = palette;
        this.style = style;
        this.text = text;
        setBackgroundMode(SWT.INHERIT_NONE);
        addPaintListener(this::paint);
        addListener(SWT.MouseEnter, e -> { hover = true; redraw(); });
        addListener(SWT.MouseExit, e -> { hover = false; armed = false; redraw(); });
        addListener(SWT.MouseDown, e -> { if (e.button == 1 && enabled) { armed = true; redraw(); } });
        addListener(SWT.MouseUp, e -> {
            boolean fire = armed && e.button == 1 && enabled;
            armed = false;
            redraw();
            if (fire) {
                Event event = new Event();
                event.widget = this;
                notifyListeners(SWT.Selection, event);
            }
        });
        setCursor(getDisplay().getSystemCursor(SWT.CURSOR_HAND));
    }

    public void setText(String text) {
        this.text = text;
        redraw();
        if (getParent() != null) getParent().layout(true, true);
    }

    public String getText() { return text; }

    public void setTint(Color tint) { this.tintOverride = tint; redraw(); }

    @Override public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        setCursor(getDisplay().getSystemCursor(enabled ? SWT.CURSOR_HAND : SWT.CURSOR_ARROW));
        redraw();
    }

    @Override public boolean isEnabled() { return enabled; }

    @Override public Point computeSize(int wHint, int hHint, boolean changed) {
        GC gc = new GC(this);
        gc.setFont(style == Style.QUIET ? palette.body : palette.headline);
        Point extent = gc.textExtent(text == null ? "" : text);
        gc.dispose();
        int padX = switch (style) { case PRIMARY -> 28; case TOOL -> 20; case QUIET -> 16; };
        int height = switch (style) { case PRIMARY -> 34; case TOOL -> 30; case QUIET -> 26; };
        return new Point(Math.max(extent.x + padX, 44), height);
    }

    private void paint(PaintEvent e) {
        GC gc = e.gc;
        Rectangle b = getClientArea();
        int radius = switch (style) { case PRIMARY -> 10; case TOOL -> 9; case QUIET -> 8; };
        gc.setAntialias(SWT.ON);
        Color top = null, bottom = null, white = null;
        try {
        if (style == Style.PRIMARY) {
            Color base = tintOverride != null ? tintOverride : palette.accent();
            RGB rgb = base.getRGB();
            top = new Color(getDisplay(), UiPalette.scale(rgb, armed ? 0.82 : hover ? 1.12 : 1.0));
            bottom = new Color(getDisplay(), UiPalette.scale(rgb, armed ? 0.72 : 0.84));
            var clip = Glass.roundedRegion(getDisplay(), b.x, b.y, b.width - 1, b.height - 1, radius);
            gc.setClipping(clip);
            gc.setForeground(top);
            gc.setBackground(bottom);
            gc.fillGradientRectangle(b.x, b.y, b.width, b.height, true);
            gc.setClipping((org.eclipse.swt.graphics.Region) null);
            clip.dispose();
            // lit-from-above edge
            gc.setAlpha(90);
            white = new Color(getDisplay(), 0xFF, 0xFF, 0xFF);
            gc.setForeground(white);
            gc.drawLine(b.x + radius, b.y, b.x + b.width - radius, b.y);
            gc.setAlpha(255);
            gc.setFont(palette.headline);
        } else if (style == Style.TOOL) {
            gc.setAlpha(armed ? 90 : hover ? 72 : 46);
            gc.setBackground(palette.textPrimary);
            gc.fillRoundRectangle(b.x, b.y, b.width - 1, b.height - 1, radius * 2, radius * 2);
            gc.setAlpha(255);
            gc.setForeground(palette.separator);
            gc.drawRoundRectangle(b.x, b.y, b.width - 1, b.height - 1, radius * 2, radius * 2);
            gc.setFont(palette.body);
            gc.setForeground(palette.textPrimary);
        } else { // QUIET
            if (hover && enabled) {
                gc.setAlpha(20);
                gc.setBackground(palette.textPrimary);
                gc.fillRoundRectangle(b.x, b.y, b.width - 1, b.height - 1, radius * 2, radius * 2);
                gc.setAlpha(255);
            }
            gc.setFont(palette.body);
            gc.setForeground(palette.textPrimary);
        }
        if (style == Style.PRIMARY && white != null) gc.setForeground(white);
        Point extent = gc.textExtent(text == null ? "" : text);
        gc.setAlpha(enabled ? 255 : 120);
        gc.drawText(text, b.x + (b.width - extent.x) / 2, b.y + (b.height - extent.y) / 2, true);
        gc.setAlpha(255);
        } finally {
            if (top != null) top.dispose();
            if (bottom != null) bottom.dispose();
            if (white != null) white.dispose();
        }
    }
}
