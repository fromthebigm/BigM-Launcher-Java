package ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.Region;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

/**
 * The glass look, hand-painted: rounded surfaces with a top-lit hairline,
 * tinted washes, and the slow aurora backdrop field. SWT has no materials,
 * so translucency is faked with layered alpha fills over the deep chrome —
 * visually the same family the SwiftUI regularMaterial produces on macOS.
 */
public final class Glass {

    private Glass() {}

    // ── Rounded silhouettes ─────────────────────────────────────────────

    /** Polygon approximation of a rounded rect (8 points per corner arc),
     *  for Region clipping where SWT's Path has no rounded-rect verb. */
    public static int[] roundedPolygon(int x, int y, int w, int h, int arc) {
        java.util.List<Integer> pts = new java.util.ArrayList<>();
        int r = Math.min(arc / 2, Math.min(w, h) / 2);
        int[][] corners = {
            {x + w - r, y + r, 270, 360},   // top-right
            {x + w - r, y + h - r, 0, 90},  // bottom-right
            {x + r, y + h - r, 90, 180},    // bottom-left
            {x + r, y + r, 180, 270},       // top-left
        };
        for (int[] c : corners) {
            for (int a = c[2]; a <= c[3]; a += 15) {
                double rad = Math.toRadians(a);
                pts.add(c[0] + (int) (r * Math.cos(rad)));
                pts.add(c[1] + (int) (r * Math.sin(rad)));
            }
        }
        return pts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Region from a visual radius (callers think in corners, not arcs). */
    public static Region roundedRegion(Display display, int x, int y, int w, int h, int visualRadius) {
        Region region = new Region(display);
        region.add(roundedPolygon(x, y, w, h, visualRadius * 2));
        return region;
    }

    // ── Card / surface painting ──────────────────────────────────────────

    /** The standard card: base fill, optional tint wash, top-lit hairline.
     *  `tintColor` may be null (neutral card). */
    /** SWT arcs are the corner ellipse's full width/height, so a visual
     *  radius of R needs arc=2R. All callers here pass VISUAL radii; we
     *  double at the boundary. */
    private static int arc(int visualRadius) { return visualRadius * 2; }

    public static void paintCard(GC gc, UiPalette palette, Rectangle bounds,
            int radius, Color tintColor, boolean hover, boolean emphasized) {
        gc.setAntialias(SWT.ON);
        // Cards breathe: translucent enough that the ridgelines dimly show
        // through; emphasized surfaces stay nearer-solid for legibility.
        int alphaBase = emphasized ? 240 : 186;
        gc.setAlpha(alphaBase);
        gc.setBackground(palette.card);
        gc.fillRoundRectangle(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1,
                arc(radius), arc(radius));
        if (tintColor != null && emphasized) {
            gc.setAlpha(14);
            gc.setBackground(tintColor);
            gc.fillRoundRectangle(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1,
                    arc(radius), arc(radius));
        }
        // top sheen: brightest at the top edge, dying quickly
        if (hover) {
            gc.setAlpha(16);
            gc.setBackground(palette.textPrimary);
            gc.fillRoundRectangle(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1,
                    arc(radius), arc(radius));
        }
        gc.setAlpha(255);
        hairline(gc, palette, bounds, radius);
        // Soft outer glow in the card's own tint: the "at home in the scene"
        // cue instead of a floating gray slab.
        if (tintColor != null) {
            gc.setAlpha(26);
            gc.setForeground(tintColor);
            gc.setLineWidth(1);
            gc.drawRoundRectangle(bounds.x - 1, bounds.y - 1, bounds.width + 1, bounds.height + 1,
                    arc(radius) + 4, arc(radius) + 4);
        }
    }

    /** The physicality cue: bright top edge fading down the sides. */
    public static void hairline(GC gc, UiPalette palette, Rectangle bounds, int radius) {
        gc.setAntialias(SWT.ON);
        gc.setForeground(palette.separator);
        gc.drawRoundRectangle(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1,
                arc(radius), arc(radius));
        // bright sliver across the top
        gc.setForeground(palette.textPrimary);
        gc.setAlpha(34);
        gc.drawLine(bounds.x + radius, bounds.y, bounds.x + bounds.width - radius, bounds.y);
        gc.setAlpha(255);
    }

    /**
     * The launcher backdrop: "Nightwood" — a realistic-but-indie mountain
     * forest at dusk. A 64-band sky with a warm dusk kiss on the horizon,
     * three star classes that thin toward the haze (atmospheric
     * extinction), thin cloud wisps, a mottled moon with a calm halo, four
     * value-noise ridgelines paling with distance, mist pooled in the
     * saddles, conifer silhouettes combed along the three nearest ridges,
     * fireflies over the treeline, hero pines framing the corners, and a
     * quiet vignette. Deterministic from window size; painted only on
     * damage. Sheet backdrops and theme thumbnails run the same painter.
     */
    public static void paintBackdrop(GC gc, UiPalette palette, Rectangle area,
            String backgroundStyle) {
        gc.setAdvanced(true);
        gc.setAntialias(SWT.ON);
        gc.setBackground(palette.chromeDeep);
        gc.fillRectangle(area);
        if ("solid".equals(backgroundStyle)) return;
        paintScene(gc, area, new SceneColors(palette.skyTop(), palette.skyHorizon(),
                palette.ridgeFar(), palette.ridgeMid(), palette.ridgeNear(),
                palette.accent(), palette.textPrimary),
                seedOf(Math.max(1, area.width), Math.max(1, area.height), 3));
    }

    /** Full-scene color contract so the window (live palette) and theme
     *  thumbnails (packed theme values) render through ONE painter. */
    private record SceneColors(Color skyTop, Color skyHorizon, Color ridgeFar,
            Color ridgeMid, Color ridgeNear, Color accent, Color ink) {}

    /** Deterministic lattice hash — the ridge generator's entropy. */
    private static double latticeHash(long i, long seed) {
        long x = i * 374761393L + seed * 668265263L;
        x = (x ^ (x >>> 13)) * 1274126177L;
        x = x ^ (x >>> 16);
        return (x & 0xFFFFFFL) / (double) 0x1000000L;
    }

    /** Smoothstep-interpolated multi-octave value noise in [0,1]. */
    private static double valueNoise(double t, long seed, int octaves) {
        double sum = 0, amp = 1, freq = 1, norm = 0;
        for (int o = 0; o < octaves; o++) {
            double tt = t * freq;
            long i0 = (long) Math.floor(tt);
            double frac = tt - i0;
            double s = frac * frac * (3 - 2 * frac);
            double a = latticeHash(i0, seed + o * 1013L);
            double b = latticeHash(i0 + 1, seed + o * 1013L);
            sum += (a + (b - a) * s) * amp;
            norm += amp;
            amp *= 0.5;
            freq *= 2.03;
        }
        return sum / norm;
    }


    private static long seedOf(int w, int h, int salt) {
        return 20261013L * (w + salt) + 77L * (h + salt);
    }

    /** The whole scene, layered far→near; area-offset so the shell window,
     *  sheets and thumbnails all run the exact same painting. */
    private static void paintScene(GC gc, Rectangle area, SceneColors colors,
            long seed) {
        gc.setAdvanced(true);
        gc.setAntialias(SWT.ON);
        java.util.Random rng = new java.util.Random(seed);
        int x0 = area.x, y0 = area.y, w = area.width, h = area.height;
        int bottom = y0 + h;
        int skyBottom = y0 + (int) (h * 0.70);
        Color black = colors.ink().getDevice().getSystemColor(SWT.COLOR_BLACK);

        // ── Sky: 64-band smooth gradient, zenith-deepened ──
        int bands = 64;
        Color previous = null;
        for (int i = 0; i < bands; i++) {
            double f = (double) i / (bands - 1);
            Color c = mix(colors.skyTop(), colors.skyHorizon(), Math.pow(f, 1.35));
            gc.setBackground(c);
            int y = y0 + i * (skyBottom - y0) / bands;
            gc.fillRectangle(x0, y, w, (skyBottom - y0) / bands + 2);
            if (previous != null) previous.dispose();   // keep one alive at a time
            previous = c;
        }
        if (previous != null) previous.dispose();

        // ── Cloud wisps: long, thin, barely-there drift ──
        Color wisp = mix(colors.skyHorizon(), colors.ink(), 0.35);
        gc.setBackground(wisp);
        for (int i = 0; i < 3; i++) {
            int wy = y0 + (int) (h * (0.09 + 0.06 * rng.nextDouble()) + i * h * 0.045);
            int wx = x0 + (int) (w * rng.nextDouble() * 0.5);
            int ww = (int) (w * (0.22 + rng.nextDouble() * 0.22));
            gc.setAlpha(9 + rng.nextInt(8));
            gc.fillOval(wx, wy, Math.max(20, ww), Math.max(3, h / 160));
        }
        gc.setAlpha(255);
        wisp.dispose();

        // ── Stars: dust, points, and a few crossed anchors; density thins
        //     toward the horizon line ──
        Color star = mix(colors.ink(), colors.accent(), 0.16);
        gc.setBackground(star);
        int skyH = Math.max(1, skyBottom - y0);
        int dust = Math.max(80, w * skyH / 8000);
        for (int i = 0; i < dust; i++) {
            double f = rng.nextDouble();
            if (rng.nextDouble() > 1.0 - f * f * 0.85) continue;
            gc.setAlpha(36 + rng.nextInt(56));
            gc.fillRectangle(x0 + rng.nextInt(Math.max(1, w)),
                    y0 + (int) (f * (skyH - 40)), 1, 1);
        }
        int points = Math.max(28, w * skyH / 24000);
        for (int i = 0; i < points; i++) {
            double f = rng.nextDouble();
            if (rng.nextDouble() > 1.0 - f * 0.8) continue;
            gc.setAlpha(110 + rng.nextInt(96));
            gc.fillOval(x0 + rng.nextInt(Math.max(1, w)),
                    y0 + (int) (f * (skyH - 48)), 2, 2);
        }
        int anchors = Math.max(4, w / 380);
        for (int i = 0; i < anchors; i++) {
            double f = rng.nextDouble() * 0.66;
            int sx = x0 + rng.nextInt(Math.max(1, w));
            int sy = y0 + (int) (f * (skyH - 64));
            int a = 190 + rng.nextInt(65);
            gc.setAlpha(a);
            gc.fillOval(sx - 2, sy - 2, 4, 4);
            gc.setAlpha(a / 3);
            gc.fillRectangle(sx - 5, sy, 11, 1);
            gc.fillRectangle(sx, sy - 5, 1, 11);
        }
        gc.setAlpha(255);
        star.dispose();

        // ── Moon: accent-kissed disc, calm halo rings, faint mare ──
        int disc = Math.max(20, h / 26);
        int cx = x0 + (int) (w * 0.80), cy = y0 + (int) ((skyBottom - y0) * 0.24);
        gc.setBackground(colors.accent());
        for (int r = 5; r >= 1; r--) {
            gc.setAlpha(7);
            gc.fillOval(cx - disc * r / 2, cy - disc * r / 2, disc * r, disc * r);
        }
        gc.setAlpha(244);
        gc.fillOval(cx - disc / 2, cy - disc / 2, disc, disc);
        Color mare = mix(colors.accent(), colors.ridgeNear(), 0.45);
        gc.setBackground(mare);
        gc.setAlpha(34);
        gc.fillOval(cx - disc / 4, cy - disc / 5, disc / 3, disc / 4);
        gc.fillOval(cx + disc / 9, cy + disc / 10, disc / 4, disc / 5);
        gc.fillOval(cx - disc / 12, cy + disc / 6, disc / 6, disc / 6);
        gc.setAlpha(255);
        mare.dispose();

        // ── Ridges far→near — the far plane heavily desaturated into the
        //     sky (aerial perspective), no forest on the hazed far plane ──
        Color farTone = mix(colors.ridgeFar(), colors.skyHorizon(), 0.55);
        Color farMid = mix(colors.ridgeFar(), colors.ridgeMid(), 0.52);
        paintRidge(gc, area, farTone, 0.70, 0.34, 2.4,
                seedOf(w, h, 101), rng, 0, 0, 0.0);
        farTone.dispose();

        // ── Dusk kiss: warm global glow hugging the horizon, laid OVER
        //     the far plane so both sky and the distant slope catch it ──
        Color kiss = mix(colors.accent(), colors.skyHorizon(), 0.40);
        gc.setBackground(kiss);
        int kissBands = 9;
        int kissH = Math.max(2, h / 110);
        for (int i = 0; i < kissBands; i++) {
            gc.setAlpha(3 + (kissBands - 1 - i) * 2);
            gc.fillRectangle(x0, skyBottom - kissH * (i + 1), w, kissH + 1);
        }
        gc.setAlpha(255);
        kiss.dispose();

        mistBand(gc, area, colors, y0 + (int) (h * 0.715), h / 16, 13);
        paintRidge(gc, area, farMid, 0.75, 0.30, 3.0,
                seedOf(w, h, 113), rng, 3, 7, 0.34);
        paintRidge(gc, area, colors.ridgeMid(), 0.80, 0.27, 3.6,
                seedOf(w, h, 131), rng, 7, 13, 0.52);
        mistBand(gc, area, colors, y0 + (int) (h * 0.80), h / 13, 10);
        farMid.dispose();
        paintRidge(gc, area, colors.ridgeNear(), 0.86, 0.26, 4.4,
                seedOf(w, h, 149), rng, 11, 22, 0.66);

        // ── Fireflies over the near treeline ──
        gc.setBackground(colors.accent());
        int flies = 6 + rng.nextInt(4);
        for (int i = 0; i < flies; i++) {
            int fx = x0 + (int) (w * (0.14 + 0.72 * rng.nextDouble()));
            int fy = y0 + (int) (h * (0.66 + 0.16 * rng.nextDouble()));
            gc.setAlpha(24);
            gc.fillOval(fx - 4, fy - 4, 8, 8);
            gc.setAlpha(200 + rng.nextInt(55));
            gc.fillOval(fx - 1, fy - 1, 3, 3);
        }
        gc.setAlpha(255);

        // ── Hero pines framing the foreground corners, cropped by the
        //     window floor — the indie-poster silhouette ──
        if (h >= 480 && w >= 700) {
            Color pine = mix(colors.ridgeNear(), black, 0.35);
            gc.setBackground(pine);
            paintFir(gc, x0 + (int) (w * 0.035), bottom + (int) (h * 0.02), (int) (h * 0.30));
            paintFir(gc, x0 + (int) (w * 0.105), bottom + (int) (h * 0.04), (int) (h * 0.22));
            paintFir(gc, x0 + (int) (w * 0.965), bottom + (int) (h * 0.03), (int) (h * 0.27));
            paintFir(gc, x0 + (int) (w * 0.905), bottom + (int) (h * 0.05), (int) (h * 0.19));
            pine.dispose();
        }

        // ── Vignette: quiet edges so the glass cards glow ──
        gc.setBackground(black);
        gc.setAlpha(22);
        gc.fillRectangle(x0, bottom - h / 14, w, h / 14);
        gc.setAlpha(10);
        gc.fillRectangle(x0, y0, w / 16, h);
        gc.fillRectangle(x0 + w - w / 16, y0, w / 16, h);
        gc.setAlpha(12);
        gc.fillRectangle(x0, y0, w, h / 18);
        gc.setAlpha(255);
    }

    /** One ridge: a value-noise silhouette filled crest→bottom (nearer
     *  ridges cover farther valleys), then conifers combed along its crest
     *  samples at the given size/density. */
    private static void paintRidge(GC gc, Rectangle area, Color color,
            double baseFrac, double ampFrac, double jag, long seed,
            java.util.Random rng, int treeMinH, int treeMaxH, double density) {
        int w = area.width, h = area.height;
        int x0 = area.x, y0 = area.y, bottom = y0 + h;
        int step = Math.max(5, w / 240);
        java.util.List<Integer> pts = new java.util.ArrayList<>();
        java.util.List<int[]> crest = new java.util.ArrayList<>();
        pts.add(x0 - 16);
        pts.add(bottom + 16);
        double base = y0 + h * baseFrac;
        double amp = h * ampFrac;
        for (int x = -16; x <= w + 16; x += step) {
            double t = x / (double) Math.max(1, w);
            double n = Math.pow(valueNoise(t * jag, seed, 4), 1.22);
            double spine = 0.55 + 0.45 * Math.sin(t * Math.PI);   // stand tall mid-frame
            int y = (int) (base - (n * 0.70 + spine * 0.30) * amp);
            pts.add(x0 + x);
            pts.add(y);
            crest.add(new int[]{x0 + x, y});
        }
        pts.add(x0 + w + 16);
        pts.add(bottom + 16);
        gc.setAlpha(255);
        gc.setBackground(color);
        gc.fillPolygon(pts.stream().mapToInt(Integer::intValue).toArray());
        if (density <= 0) return;
        for (int[] p : crest) {
            if (rng.nextDouble() > density) continue;
            int th = treeMinH + rng.nextInt(Math.max(1, treeMaxH - treeMinH + 1));
            paintFir(gc, p[0], p[1] + 1, th);
        }
    }

    /** A conifer: three overlapped tiers (apex→skirt), no trunk — it sits
     *  on its ridge, where a trunk would paint against itself. */
    private static void paintFir(GC gc, int tx, int baseY, int th) {
        int half = Math.max(2, th / 3);
        gc.fillPolygon(new int[]{tx, baseY - th,
                tx - half * 3 / 5, baseY - th * 5 / 8,
                tx + half * 3 / 5, baseY - th * 5 / 8});
        gc.fillPolygon(new int[]{tx, baseY - th * 13 / 16,
                tx - half * 4 / 5, baseY - th * 5 / 16,
                tx + half * 4 / 5, baseY - th * 5 / 16});
        gc.fillPolygon(new int[]{tx, baseY - th * 5 / 8,
                tx - half, baseY,
                tx + half, baseY});
    }

    /** A soft valley wash pooled between ridge planes — a whispered streak
     *  of haze, not a slab. */
    private static void mistBand(GC gc, Rectangle area, SceneColors colors,
            int centerY, int height, int alpha) {
        Color mist = mix(colors.skyHorizon(), colors.ink(), 0.24);
        gc.setBackground(mist);
        gc.setAlpha(alpha);
        gc.fillOval(area.x - area.width / 8, centerY - height / 2,
                area.width + area.width / 4, height);
        gc.setAlpha(255);
        mist.dispose();
    }

    /** Miniature of the full scene in a preview frame — the SAME painter
     *  the window runs, seeded for a fixed poster shot and clipped to the
     *  frame. Packed theme colors, never the live palette. */
    public static void paintThumbnail(GC gc, Display display, Rectangle area,
            UiPalette.UiTheme theme) {
        Color skyTop = new Color(display, UiPalette.rgbOf(theme.skyTop));
        Color skyHorizon = new Color(display, UiPalette.rgbOf(theme.skyHorizon));
        Color ridgeFar = new Color(display, UiPalette.rgbOf(theme.ridgeFar));
        Color ridgeMid = new Color(display, UiPalette.rgbOf(theme.ridgeMid));
        Color ridgeNear = new Color(display, UiPalette.rgbOf(theme.ridgeNear));
        Color accent = new Color(display, UiPalette.rgbOf(theme.accent));
        Color ink = new Color(display, 0xF5, 0xF3, 0xE7);
        Rectangle oldClip = gc.getClipping();
        gc.setClipping(area);
        try {
            paintScene(gc, area, new SceneColors(skyTop, skyHorizon, ridgeFar,
                    ridgeMid, ridgeNear, accent, ink), 97L);
        } finally {
            gc.setClipping(oldClip);
            skyTop.dispose(); skyHorizon.dispose(); ridgeFar.dispose();
            ridgeMid.dispose(); ridgeNear.dispose(); accent.dispose(); ink.dispose();
        }
    }

    private static Color mix(Color a, Color b, double f) {
        RGB ra = a.getRGB(), rb = b.getRGB();
        return new Color(a.getDevice(),
                (int) (ra.red + (rb.red - ra.red) * f),
                (int) (ra.green + (rb.green - ra.green) * f),
                (int) (ra.blue + (rb.blue - ra.blue) * f));
    }


    // ── Icon chip painting (vector-crisp at any display zoom) ────────────

    /** Draw the tinted rounded-square + initials chip directly into a GC.
     *  Everything scales from `size`, so callers painting at Retina zoom
     *  (or through ImageGcDrawer) get vector-sharp chips. */
    public static void paintIconChip(GC gc, Display display, int x, int y, int size,
            RGB rgb, String letters) {
        gc.setAntialias(SWT.ON);
        gc.setAdvanced(true);
        int arc = Math.max(8, size / 4);
        Color top = new Color(display, UiPalette.scale(rgb, 1.12));
        Color bottom = new Color(display, UiPalette.scale(rgb, 0.84));
        try {
            var clip = roundedRegion(display, x, y, size - 1, size - 1, arc / 2);
            gc.setClipping(clip);
            gc.setForeground(top);
            gc.setBackground(bottom);
            gc.fillGradientRectangle(x, y, size, size, true);
            gc.setClipping((Region) null);
            clip.dispose();
        } finally {
            top.dispose();
            bottom.dispose();
        }
        Color white = new Color(display, 255, 255, 255);
        var font = new org.eclipse.swt.graphics.Font(display,
                display.getSystemFont().getFontData()[0].getName(),
                Math.max(9, size * 7 / 20), SWT.BOLD);
        gc.setForeground(white);
        gc.setFont(font);
        String mark = letters == null || letters.isBlank() ? "?" : letters;
        var extent = gc.textExtent(mark);
        gc.drawText(mark, x + (size - extent.x) / 2, y + (size - extent.y) / 2 - size / 40, true);
        font.dispose();
        white.dispose();
    }

    /** Category emblem chip: tinted rounded square with the category's
     *  line glyph in white. The default app artwork when nothing real
     *  renders — deliberate, thematic, no letters. */
    public static void paintCategoryChip(GC gc, Display display, int x, int y, int size,
            RGB tint, Glyph glyph) {
        gc.setAntialias(SWT.ON);
        gc.setAdvanced(true);
        int r = Math.max(10, size / 4);
        Color top = new Color(display, UiPalette.scale(tint, 1.06));
        Color bottom = new Color(display, UiPalette.scale(tint, 0.72));
        Color white = new Color(display, 0xFF, 0xFD, 0xF7);
        try {
            var clip = roundedRegion(display, x, y, size - 1, size - 1, r);
            gc.setClipping(clip);
            gc.setForeground(top);
            gc.setBackground(bottom);
            gc.fillGradientRectangle(x, y, size, size, true);
            // soft inner top edge
            gc.setClipping((Region) null);
            clip.dispose();
            gc.setAlpha(34);
            gc.setForeground(white);
            gc.drawLine(x + r, y + 1, x + size - r, y + 1);
            gc.setAlpha(255);
            int g = size * 11 / 20;
            paintGlyph(gc, display, glyph, x + (size - g) / 2, y + (size - g) / 2, g, white);
        } finally {
            top.dispose();
            bottom.dispose();
            white.dispose();
        }
    }

    // ── Vector glyph set ─────────────────────────────────────────────────
    //
    // Emoji/unicode stand-ins (📶 🌐 ⛏ …) render colored, off-baseline, and
    // mushy at 2x. These are tiny GC-drawn line glyphs in the SF Symbols
    // spirit: monochrome, tintable, crisp at any zoom. All coordinates are
    // fractions of the box.

    public enum Glyph {
        GRID, PLAY, RADIO, GLOBE, CUBE, GAMEPAD, WINE, BRACES,
        DOWNLOAD, RELOAD, SEARCH, CHEVRON, BRUSH, PLUS, LOCK, KEY, INFO,
        FOLDER, ELLIPSIS, CHECK, STOP, LIST;
    }

    private static int[] f(double[] xs, double[] ys, int x, int y, int size) {
        int[] pts = new int[xs.length * 2];
        for (int i = 0; i < xs.length; i++) {
            pts[2 * i] = x + (int) (xs[i] * size);
            pts[2 * i + 1] = y + (int) (ys[i] * size);
        }
        return pts;
    }

    /** Draw glyph centered in the given box; caller sets antialias. */
    public static void paintGlyph(GC gc, Display display, Glyph glyph,
            int x, int y, int size, Color color) {
        gc.setAntialias(SWT.ON);
        gc.setAdvanced(true);
        gc.setLineCap(SWT.CAP_ROUND);
        gc.setLineJoin(SWT.JOIN_ROUND);
        int lw = Math.max(1, size / 11);
        gc.setLineWidth(lw);
        gc.setForeground(color);
        gc.setBackground(color);
        switch (glyph) {
            case GRID -> {
                int s = size / 2 - 1;
                int r = Math.max(2, size / 8);
                for (int[] o : new int[][]{{0, 0}, {s + 2, 0}, {0, s + 2}, {s + 2, s + 2}}) {
                    gc.fillRoundRectangle(x + o[0], y + o[1], s, s, r, r);
                }
            }
            case PLAY -> gc.fillPolygon(f(new double[]{.2, .2, .85}, new double[]{.12, .9, .5}, x, y, size));
            case RADIO -> {
                int d = Math.max(2, size / 5);
                gc.fillOval(x + (size - d) / 2, y + (size - d) / 2, d, d);
                gc.setLineWidth(Math.max(1, lw));
                for (int i = 1; i <= 2; i++) {
                    int pad = size / 2 - i * size / 4;
                    gc.drawArc(x + pad, y + pad, size - 2 * pad, size - 2 * pad, 135, 55);
                    gc.drawArc(x + pad, y + pad, size - 2 * pad, size - 2 * pad, 315, 55);
                }
            }
            case GLOBE -> {
                int pad = size / 8;
                gc.drawOval(x + pad, y + pad, size - 2 * pad, size - 2 * pad);
                gc.drawArc(x + size / 3, y + pad, size / 3, size - 2 * pad, 90, 360);
                gc.drawLine(x + pad, y + size / 2, x + size - pad, y + size / 2);
            }
            case CUBE -> {
                int top = y + size / 5, mid = y + size / 2, l = x + size / 6, r = x + size - size / 6,
                        cx = x + size / 2, bot = y + size - size / 6;
                gc.drawPolyline(new int[]{l, top + size / 8, cx, top, r, top + size / 8, r, mid + size / 4,
                        cx, bot, l, mid + size / 4, l, top + size / 8});
                gc.drawLine(l, top + size / 8, cx, mid);
                gc.drawLine(cx, mid, r, top + size / 8);
                gc.drawLine(cx, mid, cx, bot);
            }
            case GAMEPAD -> {
                int w = size - 2, h = size / 2;
                gc.drawRoundRectangle(x + 1, y + (size - h) / 2, w, h, h, h);
                gc.drawLine(x + w / 3, y + size / 2 - h / 6, x + w / 3, y + size / 2 + h / 6);
                gc.drawLine(x + w / 3 - h / 6, y + size / 2, x + w / 3 + h / 6, y + size / 2);
                gc.fillOval(x + 2 * w / 3 - 2, y + size / 2 - h / 8 - 1, 3, 3);
                gc.fillOval(x + 2 * w / 3 + 2, y + size / 2 + 1, 3, 3);
            }
            case WINE -> {
                int cx = x + size / 2;
                gc.drawPolyline(new int[]{x + size / 4, y + size / 8, x + size / 4, y + size / 2,
                        cx, y + size / 2 + size / 8, x + 3 * size / 4, y + size / 2,
                        x + 3 * size / 4, y + size / 8, x + size / 4, y + size / 8});
                gc.drawLine(cx, y + size / 2 + size / 8, cx, y + size - size / 8);
                gc.drawLine(x + size / 3, y + size - size / 8, x + size - size / 3, y + size - size / 8);
            }
            case BRACES -> {
                int l = x + size / 4, r = x + size - size / 4, m = y + size / 2;
                gc.drawPolyline(new int[]{l + size / 8, y + 2, l, y + size / 3, l, m - 2,
                        l - size / 8, m, l, m + 2, l, y + size - size / 3, l + size / 8, y + size - 2});
                gc.drawPolyline(new int[]{r - size / 8, y + 2, r, y + size / 3, r, m - 2,
                        r + size / 8, m, r, m + 2, r, y + size - size / 3, r - size / 8, y + size - 2});
            }
            case DOWNLOAD -> {
                int cx = x + size / 2;
                gc.drawLine(cx, y + size / 8, cx, y + size - size / 4);
                gc.drawPolyline(new int[]{x + size / 4, y + size / 2, cx, y + size - size / 9,
                        x + size - size / 4, y + size / 2});
                gc.drawLine(x + size / 6, y + size - 2, x + size - size / 6, y + size - 2);
            }
            case RELOAD -> {
                int pad = size / 7;
                gc.drawArc(x + pad, y + pad, size - 2 * pad, size - 2 * pad, 70, 270);
                gc.fillPolygon(f(new double[]{.12, .34, .5}, new double[]{.06, .18, .0}, x, y, size));
            }
            case SEARCH -> {
                int d = size / 2;
                gc.drawOval(x + size / 8, y + size / 8, d, d);
                gc.drawLine(x + size / 8 + d - 2, y + size / 8 + d - 2, x + size - size / 8, y + size - size / 8);
            }
            case CHEVRON -> gc.drawPolyline(new int[]{x + size / 3, y + size / 5,
                    x + 2 * size / 3, y + size / 2, x + size / 3, y + size - size / 5});
            case BRUSH -> {
                gc.fillOval(x + size / 9, y + size / 9, size / 2, size / 2);
                gc.drawLine(x + size / 2, y + size / 2, x + size - size / 5, y + size - size / 5);
                gc.fillPolygon(f(new double[]{.75, .95, .85}, new double[]{.85, .95, .75}, x, y, size));
            }
            case PLUS -> {
                gc.drawLine(x + size / 2, y + size / 6, x + size / 2, y + size - size / 6);
                gc.drawLine(x + size / 6, y + size / 2, x + size - size / 6, y + size / 2);
            }
            case LOCK -> {
                int w = size - size / 3;
                gc.drawRoundRectangle(x + size / 6, y + size / 2 - size / 12, w, size / 2 - size / 12, 4, 4);
                gc.drawArc(x + size / 6, y, w, size / 2, 0, 180);
            }
            case KEY -> {
                int d = size / 3;
                gc.drawOval(x + size / 8, y + (size - d) / 2 + size / 16, d, d);
                gc.drawLine(x + size / 8 + d - 2, y + size / 2 + size / 16 + d / 2,
                        x + size - size / 8, y + size / 2 + size / 16 + d / 2);
                gc.drawLine(x + size - size / 4, y + size / 2 + size / 16 + d / 2,
                        x + size - size / 4, y + size / 2 + size / 16 + d / 2 - size / 8);
                gc.drawLine(x + size - size / 8, y + size / 2 + size / 16 + d / 2,
                        x + size - size / 8, y + size / 2 + size / 16 + d / 2 - size / 6);
            }
            case INFO -> {
                gc.drawOval(x + size / 7, y + size / 7, size - size / 7 * 2, size - size / 7 * 2);
                gc.drawLine(x + size / 2, y + size / 2, x + size / 2, y + size - size / 4);
                gc.fillOval(x + size / 2 - 2, y + size / 4, 4, 4);
            }
            case FOLDER -> {
                gc.drawRoundRectangle(x + size / 8, y + size / 4, size - size / 4,
                        size / 2 + size / 7, 4, 4);
                gc.drawLine(x + size / 8, y + size / 3, x + size / 2, y + size / 3);
            }
            case ELLIPSIS -> {
                for (int i = 0; i < 3; i++) {
                    int d = Math.max(3, size / 7);
                    gc.fillOval(x + size / 8 + i * (size / 3) , y + (size - d) / 2, d, d);
                }
            }
            case CHECK -> gc.drawPolyline(new int[]{x + size / 5, y + size / 2,
                    x + size / 3 + size / 12, y + 3 * size / 4, x + size - size / 5, y + size / 4});
            case STOP -> gc.fillRoundRectangle(x + size / 5, y + size / 5,
                    size - size / 5 * 2, size - size / 5 * 2, size / 8, size / 8);
            case LIST -> {
                for (int i = 0; i < 3; i++) {
                    int yy = y + size / 4 + i * size / 4;
                    gc.fillOval(x + size / 9, yy, 3, 3);
                    gc.drawLine(x + size / 3, yy + 1, x + size - size / 7, yy + 1);
                }
            }
        }
    }

    /** Small Glyph widget: a fixed-size canvas painting a tintable glyph. */
    public static final class GlyphView extends Composite {
        private Glyph glyph;
        private Color color;
        private final int size;
        public GlyphView(Composite parent, int size, Glyph glyph, Color color) {
            super(parent, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
            this.size = size;
            this.glyph = glyph;
            this.color = color;
            org.eclipse.swt.layout.GridData data =
                    new org.eclipse.swt.layout.GridData(SWT.CENTER, SWT.CENTER, false, false);
            data.widthHint = size;
            data.heightHint = size;
            setLayoutData(data);
            addPaintListener(e -> paintGlyph(e.gc, getDisplay(), glyph, 0, 0, size,
                    color == null ? getForeground() : color));
        }
        public void setGlyph(Glyph glyph, Color color) {
            this.glyph = glyph;
            this.color = color;
            redraw();
        }
    }

    /** Trackpad/wheel scrolling for ScrolledComposite: SWT on Cocoa doesn't
     *  translate wheel events into setOrigin for us, so content reads as
     *  unscrollable on Mac trackpads. count sign is direction-correct. */
    public static void addWheelScroll(org.eclipse.swt.custom.ScrolledComposite scroller) {
        scroller.addListener(SWT.MouseWheel, e -> {
            org.eclipse.swt.graphics.Point origin = scroller.getOrigin();
            int max = Math.max(0, scroller.getContent().getSize().y
                    - scroller.getClientArea().height);
            int next = Math.max(0, Math.min(max, origin.y - e.count * 3));
            scroller.setOrigin(origin.x, next);
        });
    }

    // ── Bits ────────────────────────────────────────────────────────────

    /** Per-frame color cache so cards don't leak one Color per repaint. */
    public static final class TintCache {
        private final Display display;
        private final java.util.Map<String, Color> byHex = new java.util.HashMap<>();
        public TintCache(Display display) { this.display = display; }
        public Color get(String hexValue) {
            String key = hexValue == null ? "" : hexValue.trim().toLowerCase();
            return byHex.computeIfAbsent(key,
                    k -> new Color(display, UiPalette.hex(k)));
        }
        public void dispose() {
            for (Color c : byHex.values()) if (!c.isDisposed()) c.dispose();
        }
    }
}
