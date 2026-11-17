package ui;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.RGB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-SWT port of native/ui/NightwoodScene.swift (the SwiftUI face backdrop).
 * No native bridge: deterministic SplitMix64/ValueNoise, the same color math,
 * the same paint order, and the same geometry. The scene is static — repaint
 * only on size/theme/variant change.
 */
public final class NightwoodScene {

    private NightwoodScene() {}

    // ── deterministic randomness ────────────────────────────────────────

    private static final class Rng {
        private long state;
        Rng(long seed) { this.state = seed; }
        private long next() {
            state += 0x9E3779B97F4A7C15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }
        double unit() { return (next() >>> 11) / (double) (1L << 53); }
        double range(double a, double b) { return a + (b - a) * unit(); }
        boolean chance(double p) { return unit() < p; }
    }

    private static double hash(long i, long seed) {
        long x = i * 374761393L + seed * 668265263L;
        x = (x ^ (x >>> 13)) * 1274126177L;
        x ^= x >>> 16;
        return (x & 0xFFFFFFL) / (double) 0x1000000;
    }

    private static double noise(double t, long seed, int octaves) {
        double sum = 0, amp = 1, freq = 1, norm = 0;
        for (int o = 0; o < octaves; o++) {
            double tt = t * freq;
            long i0 = (long) Math.floor(tt);
            double frac = tt - i0;
            double s = frac * frac * (3 - 2 * frac);
            double a = hash(i0, seed + o * 1013L);
            double b = hash(i0 + 1, seed + o * 1013L);
            sum += (a + (b - a) * s) * amp;
            norm += amp;
            amp *= 0.5;
            freq *= 2.03;
        }
        return sum / norm;
    }

    // ── color helpers ───────────────────────────────────────────────────

    private static final class Colors implements AutoCloseable {
        private final GC gc;
        private final Map<Long, Color> cache = new HashMap<>();
        Colors(GC gc) { this.gc = gc; }
        Color c(RGB rgb) {
            long key = ((long) rgb.red << 16) | ((long) rgb.green << 8) | rgb.blue;
            return cache.computeIfAbsent(key, k -> new Color(gc.getDevice(), rgb));
        }
        Color mix(RGB a, RGB b, double f) { return c(SwiftPalette.mix(a, b, f)); }
        void fill(Color color, int x, int y, int w, int h, int alpha) {
            gc.setAlpha(alpha);
            gc.setBackground(color);
            gc.fillRectangle(x, y, w, h);
            gc.setAlpha(255);
        }
        void poly(Color color, int[] pts, int alpha) {
            if (pts.length < 6) return;
            gc.setAlpha(alpha);
            gc.setBackground(color);
            gc.fillPolygon(pts);
            gc.setAlpha(255);
        }
        @Override public void close() { cache.values().forEach(Color::dispose); cache.clear(); }
    }

    /** Draw a multi-stop linear gradient into a rect. */
    private static void gradient(Colors C, int x, int y, int w, int h,
                                 double sx, double sy, double ex, double ey,
                                 RGB[] stops, double[] fractions, int alpha) {
        int dx = (int) Math.round(ex - sx), dy = (int) Math.round(ey - sy);
        boolean vertical = Math.abs(dy) >= Math.abs(dx);
        int span = Math.max(2, vertical ? Math.abs(dy) : Math.abs(dx));
        span = Math.min(span, Math.max(w, h));
        for (int i = 0; i < span; i++) {
            double t = (double) i / (span - 1);
            RGB rgb = stopAt(stops, fractions, t);
            int cx = vertical ? x : x + (int) (t * w);
            int cy = vertical ? y + (int) (t * h) : y;
            int cw = vertical ? w : (w / span) + 1;
            int ch = vertical ? (h / span) + 1 : h;
            C.fill(C.c(rgb), cx, cy, cw, ch, alpha);
        }
    }

    private static RGB stopAt(RGB[] stops, double[] fractions, double t) {
        if (t <= fractions[0]) return stops[0];
        for (int i = 1; i < fractions.length; i++) {
            if (t <= fractions[i]) {
                double f = (t - fractions[i - 1]) / (fractions[i] - fractions[i - 1]);
                return SwiftPalette.mix(stops[i - 1], stops[i], f);
            }
        }
        return stops[stops.length - 1];
    }

    private static int[] polygon(double... xy) {
        int[] pts = new int[xy.length];
        for (int i = 0; i < xy.length; i++) pts[i] = (int) Math.round(xy[i]);
        return pts;
    }

    /** Approximate a quadratic Bezier by a stroked polyline. */
    private static void quad(GC gc, double x0, double y0, double cx, double cy,
                             double x1, double y1, Color color, int alpha, int width) {
        gc.setAlpha(alpha);
        gc.setForeground(color);
        gc.setLineWidth(width);
        int steps = 16;
        double px = x0, py = y0;
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps, u = 1 - t;
            double x = u * u * x0 + 2 * u * t * cx + t * t * x1;
            double y = u * u * y0 + 2 * u * t * cy + t * t * y1;
            gc.drawLine((int) Math.round(px), (int) Math.round(py),
                    (int) Math.round(x), (int) Math.round(y));
            px = x; py = y;
        }
        gc.setLineWidth(1);
        gc.setAlpha(255);
    }

    // ── main entry ──────────────────────────────────────────────────────

public static void paint(GC gc, SwiftPalette palette, SwiftPalette.Variant variant, int w, int h) {
        if (w <= 0 || h <= 0) return;
        try (Colors C = new Colors(gc)) {
            RGB[] s = palette.scene(variant);
            RGB ink = palette.ink();
            RGB accent = palette.accent();
            RGB accentPair = palette.accentPair();
            double horizon = 0.60 * h;
            long seed = (w * 73431337L) ^ (h * 2654435761L);
            Rng rng = new Rng(seed);

            // 1. sky
            RGB glow = SwiftPalette.mix(s[1], accent, variant == SwiftPalette.Variant.DAY ? 0.30 : 0.14);
            RGB[] skyStops = {s[0], SwiftPalette.mix(s[0], s[1], 0.34),
                    SwiftPalette.mix(s[0], s[1], 0.80), s[1], glow};
            double[] skyFrac = {0, 0.50, 0.83, 0.945, 1};
            gradient(C, 0, 0, w, (int) (horizon * 1.005), 0, 0, 0, horizon * 1.005,
                    skyStops, skyFrac, 255);

            if (variant == SwiftPalette.Variant.NIGHT) paintStars(gc, C, w, h, horizon, ink, s[1], rng);
            else paintDaySky(gc, C, palette, w, h, ink, s[1], s[9], rng);

            // 2. celestial
            if (variant == SwiftPalette.Variant.NIGHT) paintMoon(gc, C, w, h, ink, accent, s[4], accentPair);
            else paintSun(gc, C, w, h, palette, ink, s[4], rng);

            // 3. grand massif
            paintMassif(gc, C, palette, variant, w, h, horizon, accent, ink, s);

            // 4. mist
            RGB mist = SwiftPalette.mix(s[1], ink, 0.24);
            double cy = horizon * 1.06;
            C.fill(C.c(mist), (int) (-0.12 * w), (int) (cy - h / 32.0),
                    (int) (1.24 * w), Math.max(1, h / 16), 14);   // 0.055 * 255
            C.fill(C.c(mist), (int) (-0.06 * w), (int) (cy - h / 48.0),
                    (int) (1.12 * w), Math.max(1, h / 24), 8);    // 0.033 * 255

            // 5. river
            paintRiver(gc, C, palette, w, h, horizon, accent, s, rng);

            // 6. forest
            paintForest(gc, C, palette, w, h, horizon, ink, seed, s, rng);

            // 7. hero pines
            RGB pineColor = SwiftPalette.mix(s[4], s[9], 0.35);
            RGB pineDark = SwiftPalette.scale(pineColor, 0.5);
            RGB pineLight = SwiftPalette.mix(pineColor, ink, 0.08);
            paintFir(gc, C, 0.035 * w, 1.02 * h, 0.30 * h, pineColor, pineDark, pineLight, s[9], true);
            paintFir(gc, C, 0.105 * w, 1.04 * h, 0.22 * h, pineColor, pineDark, pineLight, s[9], true);
            paintFir(gc, C, 0.965 * w, 1.03 * h, 0.27 * h, pineColor, pineDark, pineLight, s[9], true);
            paintFir(gc, C, 0.905 * w, 1.05 * h, 0.19 * h, pineColor, pineDark, pineLight, s[9], true);

            // 8. airborne
            RGB airborne = variant == SwiftPalette.Variant.NIGHT
                    ? SwiftPalette.mix(accent, ink, 0.3) : ink;
            int count = variant == SwiftPalette.Variant.NIGHT ? 6 : 10;
            for (int i = 0; i < count; i++) {
                if (variant == SwiftPalette.Variant.NIGHT) {
                    double fx = w * rng.range(0.14, 0.72), fy = h * rng.range(0.66, 0.90);
                    C.fill(C.c(airborne), (int) (fx - 4), (int) (fy - 4), 8, 8, 26);
                    C.fill(C.c(airborne), (int) (fx - 1.5), (int) (fy - 1.5), 3, 3, 204);
                } else {
                    double x = rng.range(0.1 * w, 0.9 * w), y = rng.range(0.3 * h, 0.8 * h);
                    int sz = Math.max(1, (int) Math.round(rng.range(1, 2.5)));
                    C.fill(C.c(airborne), (int) x, (int) y, sz, sz, 64);
                }
            }

            // 9. vignette
            paintVignette(C, palette, variant, w, h);
        }
    }

    // ── sky details ─────────────────────────────────────────────────────

    private static void paintStars(GC gc, Colors C, int w, int h, double horizon, RGB ink, RGB skyHorizon, Rng rng) {
        RGB dust = SwiftPalette.mix(ink, skyHorizon, 0.30);
        int accepted = 0, guard = 0;
        while (accepted < 200 && guard++ < 20000) {
            double f = rng.unit();
            if (rng.chance(1 - f * f * 0.75)) {
                C.fill(C.c(dust), (int) (w * rng.unit()), (int) (f * horizon * 0.92), 1, 1, 140);
                accepted++;
            }
        }
        for (int i = 0; i < 10; i++) {
            double x = w * rng.range(0.04, 0.96), y = h * rng.range(0.02, 0.5 * horizon);
            C.fill(C.c(ink), (int) (x - 1.5), (int) (y - 1.5), 3, 3, 242);
            C.fill(C.c(ink), (int) (x - 4.5), (int) (y - 0.5), 9, 1, 115);
            C.fill(C.c(ink), (int) (x - 0.5), (int) (y - 4.5), 1, 9, 115);
        }
    }

    private static void paintDaySky(GC gc, Colors C, SwiftPalette p, int w, int h,
                                    RGB ink, RGB skyHorizon, RGB shadow, Rng rng) {
        RGB wisp = SwiftPalette.mix(skyHorizon, ink, 0.35);
        for (int i = 0; i < 4; i++) {
            C.fill(C.c(wisp), (int) (w * rng.range(0.05, 0.55)), (int) (h * rng.range(0.05, 0.20)),
                    (int) (w * rng.range(0.22, 0.42)), Math.max(3, h / 160), (int) ((0.05 + rng.range(0, 0.04)) * 255));
        }
        RGB cloudTop = SwiftPalette.mix(ink, skyHorizon, 0.30);
        RGB cloudBase = SwiftPalette.mix(skyHorizon, shadow, 0.42);
        double[][] clusters = {{0.22, 0.11, 1.0}, {0.58, 0.075, 1.20}, {0.84, 0.15, 0.8}};
        double[][] puffs = {{0, 0, 0.34}, {-0.28, 0.01, 0.25}, {0.28, 0.012, 0.27},
                {-0.12, -0.026, 0.23}, {0.12, -0.03, 0.21}, {-0.45, 0.02, 0.17}};
        for (double[] cluster : clusters) {
            double px = cluster[0] * w, py = cluster[1] * h, sc = cluster[2];
            for (double[] puff : puffs) {
                double rr = w * 0.055 * sc * puff[2] * 2.2;
                int bx = (int) (px + puff[0] * rr * 2 - rr);
                int byBase = (int) (cluster[1] + puff[1] * rr + rr * 0.16);
                int byTop = (int) (py + puff[1] * rr);
                C.fill(C.c(cloudBase), bx, byBase, (int) (2 * rr), (int) (1.08 * rr), 255);
                C.fill(C.c(cloudTop), bx, byTop, (int) (2 * rr), (int) (0.96 * rr), 255);
            }
        }
    }

    private static void paintMoon(GC gc, Colors C, int w, int h, RGB ink, RGB accent, RGB ridgeNear, RGB accentPair) {
        double disc = Math.max(0.052 * h, 18);
        double cx = 0.78 * w, cy = 0.24 * h;
        RGB halo = SwiftPalette.mix(accentPair, ink, 0.6);
        for (int r = 6; r >= 1; r--) {
            double side = disc * r;
            C.fill(C.c(halo), (int) (cx - side / 2), (int) (cy - side / 2),
                    (int) side, (int) side, (int) ((0.022 + (6 - r) * 0.006) * 255));
        }
        RGB face = SwiftPalette.mix(ink, accent, 0.08);
        C.fill(C.c(face), (int) (cx - disc / 2), (int) (cy - disc / 2), (int) disc, (int) disc, 255);
        RGB mare = SwiftPalette.mix(SwiftPalette.scale(face, 0.7), ridgeNear, 0.45);
        C.fill(C.c(mare), (int) (cx - disc / 4), (int) (cy - disc / 5), (int) (disc / 3), (int) (disc / 4), 77);
        C.fill(C.c(mare), (int) (cx + disc / 9), (int) (cy + disc / 10), (int) (disc / 4), (int) (disc / 5), 77);
        C.fill(C.c(mare), (int) (cx - disc / 12), (int) (cy + disc / 6), (int) (disc / 6), (int) (disc / 6), 77);
    }

    private static void paintSun(GC gc, Colors C, int w, int h, SwiftPalette p, RGB ink, RGB ridgeNear, Rng rng) {
        double disc = Math.max(0.04 * h, 15);
        double cx = 0.80 * w, cy = 0.16 * h;
        RGB glow = SwiftPalette.mix(p.accentPair(), ink, 0.4);
        for (int r = 9; r >= 1; r--) {
            double side = disc * r;
            C.fill(C.c(glow), (int) (cx - side / 2), (int) (cy - side / 2),
                    (int) side, (int) side, (int) ((0.018 + (9 - r) * 0.005) * 255));
        }
        C.fill(C.c(SwiftPalette.mix(ink, p.accent(), 0.18)),
                (int) (cx - disc / 2), (int) (cy - disc / 2), (int) disc, (int) disc, 255);
        Color bird = C.c(SwiftPalette.mix(ridgeNear, ink, 0.0));
        // ridgeNear with 0.72 opacity via mix toward sky is approximated; use accent-free dark tone.
        bird = C.c(ridgeNear);
        for (int i = 0; i < 6; i++) {
            double bx = w * rng.range(0.30, 0.66);
            double by = h * rng.range(0.24, 0.60 * h);
            double flap = rng.unit();
            quad(gc, bx - 5, by + (1 - flap) * 2, bx - 2, by - 2 - flap * 3, bx, by, bird, 128, 1);
            quad(gc, bx, by, bx + 2, by - 2 - flap * 3, bx + 5, by + (1 - flap) * 2, bird, 128, 1);
        }
    }

    // ── massif ──────────────────────────────────────────────────────────

    private static final double[][] CREST = {
            {0.06, 1.00}, {0.10, 0.66}, {0.15, 0.50}, {0.185, 0.34},
            {0.21, 0.40}, {0.235, 0.43}, {0.26, 0.345}, {0.28, 0.295},
            {0.30, 0.235}, {0.318, 0.160}, {0.34, 0.205}, {0.355, 0.260},
            {0.385, 0.155}, {0.40, 0.125}, {0.418, 0.185}, {0.44, 0.24},
            {0.465, 0.33}, {0.49, 0.385}, {0.515, 0.47}, {0.545, 0.56},
            {0.58, 0.70}, {0.62, 0.82}, {0.665, 1.00}
    };

    private static double px(double fx, int w) { return w * fx; }
    private static double py(double fy, double horizon, int h) { return horizon - h * fy; }

    private static void paintMassif(GC gc, Colors C, SwiftPalette p, SwiftPalette.Variant v,
                                    int w, int h, double horizon, RGB accent, RGB ink, RGB[] s) {
        List<Double> base = new ArrayList<>();
        base.add(0.0); base.add(horizon + 0.03 * h);
        for (double[] c : CREST) { base.add(px(c[0], w)); base.add(py(c[1], horizon, h)); }
        base.add((double) w); base.add(horizon + 0.03 * h);
        C.poly(C.c(SwiftPalette.mix(s[2], s[9], 0.55)), toInt(base), 255);

        List<Double> lit = new ArrayList<>();
        for (int i = 13; i < CREST.length; i++) {
            lit.add(px(CREST[i][0], w));
            lit.add(py(CREST[i][1], horizon, h));
        }
        lit.add(px(CREST[13][0], w)); lit.add(horizon + 0.03 * h);
        C.poly(C.c(SwiftPalette.mix(s[2], s[1], v == SwiftPalette.Variant.DAY ? 0.42 : 0.28)), toInt(lit), 255);

        List<Double> cap = new ArrayList<>();
        for (int i = 10; i <= 15; i++) { cap.add(px(CREST[i][0], w)); cap.add(py(CREST[i][1], horizon, h)); }
        C.poly(C.c(SwiftPalette.mix(ink, accent, 0.10)), toInt(cap), 235);

        RGB streak = SwiftPalette.mix(s[2], s[9], 0.4);
        for (int i = 0; i < 4; i++) {
            double t = i / 3.0;
            double x1 = w * (0.37 + 0.13 * t), y1 = horizon - h * (0.30 + 0.14 * t);
            double x2 = w * (0.40 + 0.10 * t), y2 = horizon - h * (0.42 + 0.12 * t);
            C.poly(C.c(streak), polygon(x1, y1, x2, y2,
                    x2 + 0.006 * w, y2 + 0.008 * h, x1 + 0.006 * w, y1 + 0.008 * h), 102);
        }
    }

    private static int[] toInt(List<Double> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = (int) Math.round(list.get(i));
        return out;
    }

    // ── river ───────────────────────────────────────────────────────────

    private static void paintRiver(GC gc, Colors C, SwiftPalette p, int w, int h, double horizon,
                                   RGB accent, RGB[] s, Rng rng) {
        Color river = C.c(SwiftPalette.mix(s[5], accent, 0.28));
        gc.setBackground(river);
        int[] pts = polygon(
                0.40 * w, 1.02 * h,
                0.45 * w, 0.92 * h,
                0.55 * w, 0.80 * h,
                0.62 * w, 0.72 * h,
                0.66 * w, 0.76 * h,
                0.56 * w, 0.82 * h,
                0.52 * w, 0.84 * h,
                0.44 * w, 0.92 * h);
        gc.fillPolygon(pts);
        Color glint = C.c(SwiftPalette.mix(s[6], accent, 0.30));
        for (int i = 0; i < 14; i++) {
            double fx = w * rng.range(0.42, 0.66);
            double fy = horizon + h * rng.range(0.04, 0.10);
            C.fill(glint, (int) (fx - 2.5), (int) (fy - 0.75), 5, 2, 115);
        }
    }

    // ── forest ──────────────────────────────────────────────────────────

    private static void paintForest(GC gc, Colors C, SwiftPalette p, int w, int h, double horizon,
                                    RGB ink, long seed, RGB[] s, Rng rng) {
        double baseY = horizon + 0.08 * h;
        List<Double> ground = new ArrayList<>();
        for (int x = 0; x <= w; x += 10) {
            double n = noise((x / (double) w) * 4.4, seed + 404, 4);
            ground.add((double) x); ground.add(baseY + n * 0.02 * h);
        }
        ground.add((double) w); ground.add((double) h);
        ground.add(0.0); ground.add((double) h);
        C.poly(C.c(SwiftPalette.mix(s[7], ink, 0.20)), toInt(ground), 199);

        RGB treeColor = s[8], treeDark = SwiftPalette.scale(s[8], 0.6);
        RGB treeLight = SwiftPalette.mix(s[8], ink, 0.15);
        for (int x = -8; x <= w + 8; x += 6) {
            if (x > 0.13 * w && x < 0.48 * w) continue;
            double t = x / (double) Math.max(1, w);
            double n = Math.pow(noise(t * 4.4, seed + 404, 4), 1.22);
            double spine = 0.55 + 0.45 * Math.sin(t * Math.PI);
            double y = horizon - h * (n * 0.70 + spine * 0.30) * 0.22;
            if (rng.range(0, 1) <= 0.65) continue;
            double th = h * rng.range(0.018, 0.038);
            paintFir(gc, C, x, y + 1, th, treeColor, treeDark, treeLight, s[9], true);
        }
    }

    private static void paintFir(GC gc, Colors C, double x, double baseY, double th,
                                 RGB color, RGB dark, RGB light, RGB shadow, boolean withShadow) {
        double half = Math.max(2, th / 3);
        if (withShadow) {
            double len = 0.7 * th;
            C.poly(C.c(shadow), polygon(
                    x - 1, baseY + 1, x + 1, baseY + 1,
                    x - len, baseY + 1 + 0.18 * len,
                    x - len - 3, baseY + 1 + 0.18 * len - 2), 46);
        }
        double trunkW = Math.max(1, 0.06 * th), trunkH = 0.15 * th;
        C.fill(C.c(dark), (int) (x - trunkW / 2), (int) (baseY - trunkH), (int) trunkW, (int) trunkH, 255);
        double tierH = th / 3;
        for (int tier = 0; tier < 3; tier++) {
            double tierW = half * (3 - tier) / 3.0;
            double tierY = baseY - th + tier * tierH - 0.4 * tierH;
            RGB c = tier == 0 ? light : (tier == 1 ? color : dark);
            C.fill(C.c(c), (int) (x - tierW / 2), (int) tierY, (int) tierW, (int) tierH, 255);
        }
    }

    // ── vignette ────────────────────────────────────────────────────────

    private static void paintVignette(Colors C, SwiftPalette p, SwiftPalette.Variant v, int w, int h) {
        RGB vignette = SwiftPalette.mix(p.scene(v)[0], p.chrome(v)[0], 0.5);
        Color vc = C.c(vignette);
        int top = h - h / 14;
        gradient(C, 0, top, w, h / 14, 0, top, 0, h,
                new RGB[]{new RGB(0, 0, 0), vignette}, new double[]{0, 1}, 56);
        // The gradient helper interpolates RGB including black; overwrite with
        // the intended [transparent -> vignette] feel by painting final edge.
        C.fill(vc, 0, h - 2, w, 2, 56);

        gradient(C, 0, 0, w / 16, h, 0, 0, w / 16, 0,
                new RGB[]{new RGB(0, 0, 0), vignette}, new double[]{0, 1}, 26);
        gradient(C, w - w / 16, 0, w / 16, h, w - w / 16, 0, w, 0,
                new RGB[]{new RGB(0, 0, 0), vignette}, new double[]{0, 1}, 26);
        gradient(C, 0, 0, w, h / 18, 0, 0, 0, h / 18,
                new RGB[]{vignette, new RGB(0, 0, 0)}, new double[]{0, 1}, 31);
    }
}
