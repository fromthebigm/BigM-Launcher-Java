package ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.widgets.Composite;

/**
 * The card status pill: colored dot + tracked uppercase label inside a
 * tint-washed capsule (READY / MISSING / UPDATE / LIVE / CHECKING / ERROR).
 */
public final class Badge extends Composite {

    private final UiPalette palette;
    private String text = "";
    private Color color;
    private boolean live;

    public Badge(Composite parent, UiPalette palette) {
        super(parent, SWT.DOUBLE_BUFFERED | SWT.INHERIT_NONE);
        this.palette = palette;
        this.color = palette.okFg;
        setBackgroundMode(SWT.INHERIT_NONE);
        addPaintListener(this::paint);
    }

    public void set(String text, Color color, boolean live) {
        if (text.equals(this.text) && color == this.color && live == this.live) return;
        this.text = text;
        this.color = color;
        this.live = live;
        redraw();
        if (getParent() != null) getParent().layout(true, true);
    }

    @Override public Point computeSize(int wHint, int hHint, boolean changed) {
        GC gc = new GC(this);
        gc.setFont(palette.tiny);
        Point extent = gc.textExtent(text);
        gc.dispose();
        return new Point(extent.x + 8 /*dot*/ + 8 /*pad*/ + 14 /*pad*/, 18);
    }

    private void paint(PaintEvent e) {
        GC gc = e.gc;
        Rectangle b = getClientArea();
        gc.setAntialias(SWT.ON);
        gc.setAlpha(26);
        gc.setBackground(color);
        gc.fillRoundRectangle(b.x, b.y, b.width - 1, b.height - 1, b.height, b.height);
        gc.setAlpha(110);
        gc.setForeground(color);
        gc.drawRoundRectangle(b.x, b.y, b.width - 1, b.height - 1, b.height, b.height);
        gc.setAlpha(255);
        gc.setBackground(color);
        int dotSize = 5;
        gc.fillOval(b.x + 7, b.y + (b.height - dotSize) / 2, dotSize, dotSize);
        gc.setForeground(color);
        gc.setFont(palette.tiny);
        gc.drawText(text, b.x + 16, b.y + (b.height - gc.textExtent(text).y) / 2, true);
    }
}
