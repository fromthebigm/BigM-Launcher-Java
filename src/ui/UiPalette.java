package ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;

import java.util.ArrayList;
import java.util.List;

/**
 * The launcher's palette/theme engine. Five mountain-dusk themes (Summit is
 * the default and the original warm-amber identity; the others are forest,
 * twilight, glacier, and campfire colorways). Everything is rebuilt live on
 * theme switch. Retired colors are NOT disposed on switch — controls may
 * hold references across paints, and disposing an in-use Color is a Cocoa
 * crash (that was the Customize crash); they're retired and freed with the
 * window.
 */
public final class UiPalette {

    /** One full colorway. Hex ints are RRGGBB. */
    public enum UiTheme {
        //             key         title      accent    pair      skyTop   skyHor   ridgeFar  ridgeMid  ridgeNear card      raised
        SUMMIT("summit",   "Summit",   0xE8A25E, 0xE8C07D, 0x0C1318, 0x2A4157, 0x2F4E6B, 0x20364B, 0x132030, 0x1A2836, 0x16222E),
        PINE("pine",       "Pine",     0x8FBC6F, 0xC9E3A8, 0x07120D, 0x23402F, 0x2E5240, 0x1B352A, 0x0F2018, 0x15251E, 0x111E19),
        DUSK("dusk",       "Dusk",     0xB583E8, 0xE8A2C0, 0x120E1A, 0x33204A, 0x3D2A57, 0x291E3D, 0x1A1326, 0x201A2C, 0x1B1626),
        GLACIER("glacier", "Glacier",  0x7FD1E8, 0xBFA2E8, 0x0A141C, 0x1E3550, 0x29445F, 0x1A2E42, 0x101E2B, 0x162330, 0x131E29),
        EMBER("ember",     "Ember",    0xE8734C, 0xE8B25E, 0x161010, 0x3B1F18, 0x4A2A1F, 0x2E1D16, 0x1C1310, 0x241A16, 0x1E1612);

        public final String key, title;
        public final int accent, accentPair, skyTop, skyHorizon, ridgeFar, ridgeMid, ridgeNear, card, raised;
        UiTheme(String key, String title, int accent, int accentPair, int skyTop,
                int skyHorizon, int ridgeFar, int ridgeMid, int ridgeNear, int card, int raised) {
            this.key = key; this.title = title;
            this.accent = accent; this.accentPair = accentPair;
            this.skyTop = skyTop; this.skyHorizon = skyHorizon;
            this.ridgeFar = ridgeFar; this.ridgeMid = ridgeMid; this.ridgeNear = ridgeNear;
            this.card = card; this.raised = raised;
        }
        public static final UiTheme DEFAULT = PINE;

        public static UiTheme forKey(String key) {
            for (UiTheme t : values()) if (t.key.equals(key)) return t;
            return DEFAULT;
        }
    }

    // Fixed readability anchors (constant across themes): warm off-white ink
    // + muted grays + the severity ramps that were judged legible on dark.
    public final Color chromeDeep, chromeRaised, separator, card,
            textPrimary, textMuted, textFaint,
            okFg, warnFg, errFg, infoFg, liveFg;

    // Theme-driven (rebuilt on setTheme; old colors retired, not disposed live).
    private final Display display;
    private Color accent, accentPair, skyTop, skyHorizon, ridgeFar, ridgeMid, ridgeNear;
    private String themeKey = UiTheme.DEFAULT.key;

    /** Colors awaiting retirement at shutdown (theme switches). */
    private final List<Color> retired = new ArrayList<>();

    public final Font brand, libTitle, headline, body, caption, small, tiny, mono, chip;

    public UiPalette(Display display) {
        this.display = display;
        chromeDeep   = new Color(display, new RGB(0x0A, 0x0D, 0x0C));
        chromeRaised = new Color(display, new RGB(0x12, 0x16, 0x15));
        separator    = new Color(display, new RGB(0x33, 0x3B, 0x38));
        card         = new Color(display, new RGB(0x18, 0x22, 0x1F));
        textPrimary  = new Color(display, new RGB(0xF0, 0xED, 0xE2));
        textMuted    = new Color(display, new RGB(0x98, 0xA0, 0x98));
        textFaint    = new Color(display, new RGB(0x63, 0x6B, 0x64));
        okFg         = new Color(display, new RGB(0x83, 0xC9, 0x7B));
        warnFg       = new Color(display, new RGB(0xE8, 0xA2, 0x5E));
        errFg        = new Color(display, new RGB(0xE0, 0x6C, 0x5C));
        infoFg       = new Color(display, new RGB(0x6C, 0xA8, 0xE8));
        liveFg       = new Color(display, new RGB(0x4F, 0xD4, 0xC0));

        String sys = display.getSystemFont().getFontData()[0].getName();
        brand    = new Font(display, sys, 17, SWT.BOLD);
        libTitle = new Font(display, sys, 24, SWT.BOLD);
        headline = new Font(display, sys, 13, SWT.BOLD);
        body     = new Font(display, sys, 12, SWT.NORMAL);
        caption  = new Font(display, sys, 11, SWT.NORMAL);
        small    = new Font(display, sys, 10, SWT.BOLD);
        tiny     = new Font(display, sys, 9, SWT.BOLD);
        mono     = new Font(display, "Menlo", 11, SWT.NORMAL);
        chip     = new Font(display, sys, 16, SWT.BOLD);

        apply(UiTheme.DEFAULT);
    }

    public Color accent() { return accent; }
    public Color accentPair() { return accentPair; }
    public Color skyTop() { return skyTop; }
    public Color skyHorizon() { return skyHorizon; }
    public Color ridgeFar() { return ridgeFar; }
    public Color ridgeMid() { return ridgeMid; }
    public Color ridgeNear() { return ridgeNear; }
    public String themeKey() { return themeKey; }
    public UiTheme theme() { return UiTheme.forKey(themeKey); }

    /** Live theme switch; safe to call from any paint-stable point. Old
     *  colors are RETIRED (kept alive for any control still holding them)
     *  and only freed at dispose() — never mid-paint. */
    public synchronized void setTheme(String key) {
        UiTheme t = UiTheme.forKey(key);
        if (t.key.equals(themeKey)) return;
        themeKey = t.key;
        retire(accent); retire(accentPair); retire(skyTop); retire(skyHorizon);
        retire(ridgeFar); retire(ridgeMid); retire(ridgeNear);
        apply(t);
    }

    private void apply(UiTheme t) {
        accent      = new Color(display, rgbOf(t.accent));
        accentPair  = new Color(display, rgbOf(t.accentPair));
        skyTop      = new Color(display, rgbOf(t.skyTop));
        skyHorizon  = new Color(display, rgbOf(t.skyHorizon));
        ridgeFar    = new Color(display, rgbOf(t.ridgeFar));
        ridgeMid    = new Color(display, rgbOf(t.ridgeMid));
        ridgeNear   = new Color(display, rgbOf(t.ridgeNear));
    }

    private void retire(Color c) { if (c != null) retired.add(c); }

    /** Resolve a card tint (per-app tintHex) with the accent as fallback. */
    public Color tint(String hexValue) {
        RGB rgb = hex(hexValue);
        return new Color(display, rgb);
    }

    public void dispose() {
        for (Color c : new Color[]{chromeDeep, chromeRaised, separator, card,
                textPrimary, textMuted, textFaint, okFg, warnFg, errFg, infoFg,
                liveFg, accent, accentPair, skyTop, skyHorizon,
                ridgeFar, ridgeMid, ridgeNear}) {
            if (c != null && !c.isDisposed()) c.dispose();
        }
        for (Color c : retired) if (!c.isDisposed()) c.dispose();
        for (Font f : new Font[]{brand, libTitle, headline, body, caption,
                small, tiny, mono, chip}) {
            if (f != null && !f.isDisposed()) f.dispose();
        }
    }

    /** Tinted icon chip image, rendered through SWT's ImageGcDrawer so it
     *  re-runs the blocky pixel paint at whatever zoom the display asks
     *  for — Retina-sharp, no 1x bitmap that a 2x panel would blur. Chips
     *  are deliberately crisp-edged (no antialiased curves inside the mark)
     *  so the pixel craft reads as intentional at any size. Caller
     *  disposes. For spaces where a control takes no Image, paint directly
     *  with Glass.paintIconChip (zero bitmap roundtrip). */
    public static org.eclipse.swt.graphics.Image iconChip(Display display,
            String tintHex, String letters, int size) {
        RGB rgb = hex(tintHex);
        String mark = letters == null || letters.isBlank() ? "?" : letters;
        return new org.eclipse.swt.graphics.Image(display, (gc, width, height) ->
                Glass.paintIconChip(gc, display, 0, 0, width, rgb, mark),
                size, size);
    }

    /** Up-to-two-letter mark from a display name ("Voice Memos" → "VM"). */
    public static String initials(String displayName) {
        if (displayName == null || displayName.isBlank()) return "?";
        String[] words = displayName.trim().split("\\s+");
        StringBuilder mark = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            mark.append(Character.toUpperCase(word.charAt(0)));
            if (mark.length() == 2) break;
        }
        return mark.length() == 0 ? "?" : mark.toString();
    }

    public static RGB hex(String hex) {
        String clean = hex == null ? "" : hex.trim();
        if (clean.startsWith("#")) clean = clean.substring(1);
        if (clean.length() != 6) return new RGB(0xE8, 0xA2, 0x5E);
        try {
            return new RGB(
                    Integer.parseInt(clean.substring(0, 2), 16),
                    Integer.parseInt(clean.substring(2, 4), 16),
                    Integer.parseInt(clean.substring(4, 6), 16));
        } catch (NumberFormatException e) {
            return new RGB(0xE8, 0xA2, 0x5E);
        }
    }

    public static String hexString(RGB rgb) {
        return String.format("%02X%02X%02X", rgb.red, rgb.green, rgb.blue);
    }

    /** Scale an RGB toward black (factor < 1) or white (> 1, softly). */
    public static RGB scale(RGB rgb, double factor) {
        if (factor <= 1) {
            return new RGB((int) (rgb.red * factor), (int) (rgb.green * factor),
                    (int) (rgb.blue * factor));
        }
        double lift = factor - 1;
        return new RGB(
                (int) (rgb.red + (255 - rgb.red) * lift),
                (int) (rgb.green + (255 - rgb.green) * lift),
                (int) (rgb.blue + (255 - rgb.blue) * lift));
    }

    public static RGB rgbOf(int packed) {
        return new RGB((packed >> 16) & 0xff, (packed >> 8) & 0xff, packed & 0xff);
    }

    /** Legacy Accent shim — old CustomizeSheet used these; theme presets
     *  replaced it. Kept so nothing else imports dead code. */
    public enum Accent { AMBER("amber", "Amber", 0, 0);
        public final String key, title; public final int rgb, pair;
        Accent(String k, String t, int r, int p) { key=k; title=t; rgb=r; pair=p; }
        public static Accent forKey(String key) { return AMBER; }
    }
    public String accentName() { return themeKey; }
}
