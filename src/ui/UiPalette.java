package ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Device;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Face palette. Backed by the exact SwiftUI ThemePalette data (theme x
 * variant) so the SWT face and the old Swift face share one source of truth.
 * Existing control code keeps referring to the stable Color fields; changing
 * theme/variant retires the old Colors (never disposes colors still held by
 * widgets) and installs fresh ones.
 */
public final class UiPalette {

    public enum UiTheme {
        PINE("pine", "Pinewood"), TAIGA("taiga", "Snowy Taiga"),
        SUMMIT("summit", "Summit"), DUSK("dusk", "Duskwood"),
        GLACIER("glacier", "Glacier"), EMBER("ember", "Emberwild");

        public final String key;
        public final String title;
        public final int accent, accentPair, skyTop, skyHorizon, ridgeFar, ridgeMid, ridgeNear, card, raised;

        UiTheme(String key, String title) {
            this.key = key;
            this.title = title;
            SwiftPalette p = SwiftPalette.get(key);
            RGB[] scene = p.scene(SwiftPalette.Variant.NIGHT);
            RGB[] chrome = p.chrome(SwiftPalette.Variant.NIGHT);
            this.accent = pack(p.accent());
            this.accentPair = pack(p.accentPair());
            this.skyTop = pack(scene[0]);
            this.skyHorizon = pack(scene[1]);
            this.ridgeFar = pack(scene[2]);
            this.ridgeMid = pack(scene[3]);
            this.ridgeNear = pack(scene[4]);
            this.card = pack(chrome[2]);
            this.raised = pack(chrome[1]);
        }

        static UiTheme forKey(String key) {
            if (key == null) return PINE;
            for (UiTheme t : values()) if (t.key.equalsIgnoreCase(key)) return t;
            return PINE;
        }
    }

    private static int pack(RGB c) { return (c.red << 16) | (c.green << 8) | c.blue; }

    private final Display display;
    private final List<Color> retired = new ArrayList<>();
    private SwiftPalette swift;
    private SwiftPalette.Variant variant;

    public Color chromeDeep, chromeRaised, separator, card, textPrimary, textMuted, textFaint;
    public Color okFg, warnFg, errFg, infoFg, liveFg;
    public Color accentCol, accentPairCol, skyTopCol, skyHorizonCol, ridgeFarCol, ridgeMidCol, ridgeNearCol;

    private Color accent, accentPair, skyTop, skyHorizon, ridgeFar, ridgeMid, ridgeNear, cardCol, raisedCol;

    public final Font brand, libTitle, headline, body, caption, small, tiny, mono, chip;

    public UiPalette(Display display) {
        this.display = display;
        this.swift = SwiftPalette.get("pine");
        this.variant = SwiftPalette.Variant.NIGHT;

        RGB[] ch = swift.chrome(variant);
        RGB[] sc = swift.scene(variant);
        chromeDeep = color(ch[0]); chromeRaised = color(ch[1]);
        card = color(ch[2]); separator = color(ch[4]);
        textPrimary = color(ch[5]); textMuted = color(ch[6]); textFaint = color(ch[7]);
        okFg = color(new RGB(0x83, 0xC9, 0x7B));
        warnFg = color(new RGB(0xE8, 0xA2, 0x5E));
        errFg = color(new RGB(0xE0, 0x6C, 0x5C));
        infoFg = color(new RGB(0x6C, 0xA8, 0xE8));
        liveFg = color(new RGB(0x4F, 0xD4, 0xC0));

        accentCol = color(swift.accent());
        accentPairCol = color(swift.accentPair());
        skyTopCol = color(sc[0]); skyHorizonCol = color(sc[1]);
        ridgeFarCol = color(sc[2]); ridgeMidCol = color(sc[3]); ridgeNearCol = color(sc[4]);
        accent = accentCol; accentPair = accentPairCol;
        skyTop = skyTopCol; skyHorizon = skyHorizonCol;
        ridgeFar = ridgeFarCol; ridgeMid = ridgeMidCol; ridgeNear = ridgeNearCol;
        cardCol = card; raisedCol = chromeRaised;

        brand = font("Avenir Next", 15, SWT.BOLD);
        libTitle = font("Avenir Next", 11, SWT.BOLD);
        headline = font("Avenir Next", 17, SWT.BOLD);
        body = font("Avenir Next", 12, SWT.NORMAL);
        caption = font("Avenir Next", 11, SWT.NORMAL);
        small = font("Avenir Next", 10, SWT.NORMAL);
        tiny = font("Avenir Next", 9, SWT.NORMAL);
        mono = font("Menlo", 10, SWT.NORMAL);
        chip = font("Avenir Next", 9, SWT.BOLD);
    }

    private Color color(RGB rgb) { return new Color(display, rgb); }

    private Font font(String name, int size, int style) {
        return new Font(display, new FontData(name, size, style));
    }

    public SwiftPalette swift() { return swift; }
    public SwiftPalette.Variant variant() { return variant; }
    public String themeKey() { return swift.key; }
    public UiTheme theme() { return UiTheme.forKey(swift.key); }

    public Color accent() { return accent; }
    public Color accentPair() { return accentPair; }
    public Color skyTop() { return skyTop; }
    public Color skyHorizon() { return skyHorizon; }
    public Color ridgeFar() { return ridgeFar; }
    public Color ridgeMid() { return ridgeMid; }
    public Color ridgeNear() { return ridgeNear; }

    public synchronized void setTheme(String key) {
        SwiftPalette next = SwiftPalette.get(key);
        if (next.key.equals(swift.key)) return;
        swift = next;
        refreshDerived();
    }

    public synchronized void setVariant(String raw) {
        SwiftPalette.Variant next = SwiftPalette.Variant.parse(raw);
        if (next == variant) return;
        variant = next;
        refreshDerived();
    }

    /** Rebuild theme/variant-dependent colors. Old colors are retired (not
     *  disposed yet) because widgets may still hold references to them. */
    private void refreshDerived() {
        RGB[] ch = swift.chrome(variant);
        RGB[] sc = swift.scene(variant);
        chromeDeep = replace(chromeDeep, ch[0]);
        chromeRaised = replace(chromeRaised, ch[1]);
        card = replace(card, ch[2]);
        separator = replace(separator, ch[4]);
        textPrimary = replace(textPrimary, ch[5]);
        textMuted = replace(textMuted, ch[6]);
        textFaint = replace(textFaint, ch[7]);
        accent = replace(accent, swift.accent());
        accentPair = replace(accentPair, swift.accentPair());
        skyTop = replace(skyTop, sc[0]);
        skyHorizon = replace(skyHorizon, sc[1]);
        ridgeFar = replace(ridgeFar, sc[2]);
        ridgeMid = replace(ridgeMid, sc[3]);
        ridgeNear = replace(ridgeNear, sc[4]);
    }

    private Color replace(Color old, RGB rgb) {
        Color next = color(rgb);
        if (old != null) retired.add(old);
        return next;
    }

    public Color tint(String hexValue) {
        RGB rgb = hex(hexValue);
        return color(rgb);
    }

    public void dispose() {
        List<Color> all = new ArrayList<>(retired);
        all.addAll(List.of(chromeDeep, chromeRaised, separator, card, textPrimary, textMuted, textFaint,
                okFg, warnFg, errFg, infoFg, liveFg, accentCol, accentPairCol,
                skyTopCol, skyHorizonCol, ridgeFarCol, ridgeMidCol, ridgeNearCol));
        if (accent != null && !all.contains(accent)) all.add(accent);
        if (accentPair != null && !all.contains(accentPair)) all.add(accentPair);
        for (Color c : all) if (c != null && !c.isDisposed()) c.dispose();
        List.of(brand, libTitle, headline, body, caption, small, tiny, mono, chip)
                .forEach(f -> { if (!f.isDisposed()) f.dispose(); });
    }

    // ── retained static helpers (unchanged behavior) ────────────────────

    public static Image iconChip(Display display, String tintHex, String letters, int size) {
        RGB tint = hex(tintHex);
        Image image = new Image(display, size, size);
        org.eclipse.swt.graphics.GC gc = new org.eclipse.swt.graphics.GC(image);
        Color bg = new Color(display, SwiftPalette.mix(tint, new RGB(20, 24, 22), 0.72));
        Color fg = new Color(display, tint);
        gc.setBackground(bg);
        gc.fillRoundRectangle(0, 0, size, size, size / 2, size / 2);
        gc.setForeground(fg);
        Font f = new Font(display, new FontData("Avenir Next", Math.max(8, size / 3), SWT.BOLD));
        gc.setFont(f);
        String label = letters == null ? "" : letters;
        org.eclipse.swt.graphics.Point ext = gc.textExtent(label);
        gc.drawString(label, (size - ext.x) / 2, (size - ext.y) / 2, true);
        f.dispose(); fg.dispose(); bg.dispose(); gc.dispose();
        return image;
    }

    public static String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) if (!p.isEmpty()) sb.append(Character.toUpperCase(p.charAt(0)));
        return sb.length() == 0 ? "?" : sb.substring(0, Math.min(2, sb.length()));
    }

    public static RGB hex(String value) {
        if (value == null || value.isBlank()) return new RGB(143, 188, 111);
        String h = value.trim().replace("#", "");
        if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        if (h.length() != 6) return new RGB(143, 188, 111);
        try {
            return new RGB(Integer.parseInt(h.substring(0, 2), 16),
                    Integer.parseInt(h.substring(2, 4), 16),
                    Integer.parseInt(h.substring(4, 6), 16));
        } catch (NumberFormatException e) {
            return new RGB(143, 188, 111);
        }
    }

    public static String hexString(RGB rgb) {
        return String.format(Locale.ROOT, "%02X%02X%02X", rgb.red, rgb.green, rgb.blue);
    }

    public static RGB scale(RGB rgb, double f) { return SwiftPalette.scale(rgb, f); }
    public static RGB rgbOf(int packed) {
        return new RGB((packed >> 16) & 0xFF, (packed >> 8) & 0xFF, packed & 0xFF);
    }

    /** Legacy shim retained for any old callers. */
    public enum Accent { AMBER }
    public String accentName() { return "pine"; }
}
