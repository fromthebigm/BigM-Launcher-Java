import SwiftUI

struct NightwoodScene: View {
    let palette: ThemePalette
    let variant: SceneVariant

    var body: some View {
        Canvas { context, size in
            var gc = context
            var painter = Painter(palette: palette, variant: variant, size: size)
            painter.render(into: &gc)
        }
        .drawingGroup()
        .accessibilityHidden(true)
    }
}

private struct Painter {
    let palette: ThemePalette
    let variant: SceneVariant
    let w: Double, h: Double
    let horizonY: Double
    let s: ThemePalette.SceneColors
    let seed: UInt64

    init(palette: ThemePalette, variant: SceneVariant, size: CGSize) {
        self.palette = palette
        self.variant = variant
        self.w = Double(size.width)
        self.h = Double(size.height)
        self.horizonY = Double(size.height) * 0.60
        self.s = palette.scene(variant)
        self.seed = UInt64(truncatingIfNeeded: Int(size.width)) &* 73431337
                 ^ UInt64(truncatingIfNeeded: Int(size.height)) &* 2654435761
    }

    @inline(__always) func P(_ x: Double, _ y: Double) -> CGPoint {
        CGPoint(x: CGFloat(x), y: CGFloat(y))
    }
    @inline(__always) func R(_ x: Double, _ y: Double, _ w: Double, _ h: Double) -> CGRect {
        CGRect(x: CGFloat(x), y: CGFloat(y), width: CGFloat(w), height: CGFloat(h))
    }

    mutating func render(into gc: inout GraphicsContext) {
        var rng = SplitMix64(seed: seed)
        paintSky(&gc, &rng)
        paintCelestial(&gc, &rng)
        paintMassif(&gc, &rng)
        paintMist(&gc, &rng, at: horizonY * 1.06, alpha: 0.055)
        paintRiver(&gc, &rng)
        paintForest(&gc, &rng)
        paintHeroPines(&gc, &rng)
        paintAirborne(&gc, &rng)
        paintVignette(&gc)
    }

    // ── Sky ──
    private func paintSky(_ gc: inout GraphicsContext, _ rng: inout SplitMix64) {
        let glow = variant == .day ? CM.mix(s.skyHorizon, palette.accent, 0.30)
                                   : CM.mix(s.skyHorizon, palette.accent, 0.14)
        let gradient = Gradient(stops: [
            Gradient.Stop(color: s.skyTop, location: 0.0),
            Gradient.Stop(color: CM.mix(s.skyTop, s.skyHorizon, 0.34), location: 0.50),
            Gradient.Stop(color: CM.mix(s.skyTop, s.skyHorizon, 0.80), location: 0.83),
            Gradient.Stop(color: s.skyHorizon, location: 0.945),
            Gradient.Stop(color: glow, location: 1.0),
        ])
        gc.fill(Path(R(0, 0, w, horizonY * 1.005)),
                with: .linearGradient(gradient,
                                      startPoint: P(0, 0),
                                      endPoint: P(0, horizonY * 1.005)))

        if variant == .night {
            let dust = CM.withOpacity(CM.mix(palette.ink, s.skyHorizon, 0.30), 0.55)
            var i = 0
            while i < 200 {
                let f = rng.unit()
                if rng.chance(1.0 - f * f * 0.75) {
                    gc.fill(Path(R(rng.range(0, w), f * horizonY * 0.92, 1, 1)), with: .color(dust))
                    i += 1
                }
            }
            let anchor = CM.withOpacity(palette.ink, 0.95)
            for _ in 0..<10 {
                let x = rng.range(w * 0.04, w * 0.96)
                let y = rng.range(h * 0.02, horizonY * 0.5)
                gc.fill(Path(R(x - 1.5, y - 1.5, 3, 3)), with: .color(anchor))
                gc.opacity = 0.45
                gc.fill(Path(R(x - 4.5, y - 0.5, 9, 1)), with: .color(anchor))
                gc.fill(Path(R(x - 0.5, y - 4.5, 1, 9)), with: .color(anchor))
                gc.opacity = 1
            }
        } else {
            let wisp = CM.withOpacity(CM.mix(s.skyHorizon, palette.ink, 0.35), 1)
            for _ in 0..<4 {
                gc.opacity = (0.05 + rng.range(0, 0.04))
                gc.fill(Path(R(w * rng.range(0.05, 0.55),
                            h * rng.range(0.05, 0.20),
                            w * rng.range(0.22, 0.42),
                            max(3, h / 160))), with: .color(wisp))
            }
            gc.opacity = 1
            let cloudTop = CM.mix(palette.ink, s.skyHorizon, 0.30)
            let cloudBase = CM.mix(s.skyHorizon, CM.scale(s.shadow, 1.0), 0.42)
            for (cx, cy, sc) in [(0.22, 0.11, 1.0), (0.58, 0.075, 1.20), (0.84, 0.15, 0.8)] {
                let px = cx * w
                let py = cy * h
                for (ox, oy, r) in [(0.0, 0.0, 0.34), (-0.28, 0.01, 0.25), (0.28, 0.012, 0.27),
                                     (-0.12, -0.026, 0.23), (0.12, -0.03, 0.21), (-0.45, 0.02, 0.17)] {
                    let rr = w * 0.055 * sc * r * 2.2
                    gc.fill(Path(R(px + ox * rr * 2 - rr, cy + oy * rr + rr * 0.16,
                                rr * 2, rr * 1.08)), with: .color(cloudBase))
                }
                for (ox, oy, r) in [(0.0, 0.0, 0.34), (-0.28, 0.01, 0.25), (0.28, 0.012, 0.27),
                                    (-0.12, -0.026, 0.23), (0.12, -0.03, 0.21), (-0.45, 0.02, 0.17)] {
                    let rr = w * 0.055 * sc * r * 2.2
                    gc.fill(Path(R(px + ox * rr * 2 - rr, py + oy * rr,
                                rr * 2, rr * 0.96)), with: .color(cloudTop))
                }
            }
        }
    }

    // ── Celestial ──
    private func paintCelestial(_ gc: inout GraphicsContext, _ rng: inout SplitMix64) {
        if variant == .night {
            let disc = max(h * 0.052, 18)
            let cx = w * 0.78, cy = h * 0.24
            let halo = CM.mix(palette.accentPair, palette.ink, 0.6)
            for r in stride(from: 6.0, through: 1.0, by: -1.0) {
                gc.opacity = 0.022 + Double(6.0 - r) * 0.006
                gc.fill(Path(R(cx - disc * r / 2, cy - disc * r / 2, disc * r, disc * r)),
                        with: .color(halo))
            }
            gc.opacity = 1
            let face = CM.mix(palette.ink, palette.accent, 0.08)
            gc.fill(Path(R(cx - disc / 2, cy - disc / 2, disc, disc)),
                    with: .color(face))
            let mare = CM.mix(CM.scale(face, 0.7), s.ridgeNear, 0.45)
            gc.opacity = 0.30
            gc.fill(Path(R(cx - disc / 4, cy - disc / 5, disc / 3, disc / 4)), with: .color(mare))
            gc.fill(Path(R(cx + disc / 9, cy + disc / 10, disc / 4, disc / 5)),
                    with: .color(mare))
            gc.fill(Path(R(cx - disc / 12, cy + disc / 6, disc / 6, disc / 6)),
                    with: .color(mare))
            gc.opacity = 1
        } else {
            let disc = max(h * 0.04, 15)
            let cx = w * 0.80, cy = h * 0.16
            let glow = CM.mix(palette.accentPair, palette.ink, 0.4)
            for r in stride(from: 9.0, through: 1.0, by: -1.0) {
                gc.opacity = 0.018 + Double(9.0 - r) * 0.005
                gc.fill(Path(R(cx - disc * r / 2, cy - disc * r / 2, disc * r, disc * r)),
                        with: .color(glow))
            }
            gc.opacity = 1
            gc.fill(Path(R(cx - disc / 2, cy - disc / 2, disc, disc)),
                    with: .color(CM.mix(palette.ink, palette.accent, 0.18)))
            let bird = CM.withOpacity(s.ridgeNear, 0.72)
            for _ in 0..<6 {
                let bx = w * rng.range(0.30, 0.66)
                let by = h * rng.range(0.24, horizonY * 0.72)
                let flap = rng.unit()
                var b = Path()
                b.move(to: P(bx - 5, by + (1 - flap) * 2))
                b.addQuadCurve(to: P(bx, by), control: P(bx - 2, by - 2 - flap * 3))
                b.addQuadCurve(to: P(bx + 5, by + (1 - flap) * 2),
                               control: P(bx + 2, by - 2 - flap * 3))
                gc.opacity = 0.5
                gc.stroke(b, with: .color(bird), lineWidth: 1.2)
                gc.opacity = 1
            }
        }
    }

    // ── Grand massif ──
    private func paintMassif(_ gc: inout GraphicsContext, _ rng: inout SplitMix64) {
        var pts = Path()
        pts.move(to: P(0, horizonY + h * 0.03))
        for (fx, fy) in crest {
            pts.addLine(to: P(w * fx, horizonY - h * fy))
        }
        pts.addLine(to: P(w, horizonY + h * 0.03))
        pts.addLine(to: P(0, horizonY + h * 0.03))
        pts.closeSubpath()
        gc.fill(pts, with: .color(CM.mix(s.ridgeFar, s.shadow, 0.55)))

        var lit = Path()
        lit.move(to: px(crest[apexIdx].0, crest[apexIdx].1))
        for i in (apexIdx + 1)..<crest.count {
            lit.addLine(to: px(crest[i].0, crest[i].1))
        }
        lit.addLine(to: P(w * crest[apexIdx].0, horizonY + h * 0.03))
        lit.closeSubpath()
        gc.fill(lit, with: .color(CM.mix(s.ridgeFar, s.skyHorizon,
                variant == .day ? 0.42 : 0.28)))

        var cap = Path()
        cap.move(to: px(crest[apexIdx - 3].0, crest[apexIdx - 3].1))
        for i in (apexIdx - 2)...(apexIdx + 2) {
            cap.addLine(to: px(crest[i].0, crest[i].1))
        }
        cap.closeSubpath()
        gc.fill(cap, with: .color(CM.withOpacity(CM.mix(palette.ink, palette.accent, 0.10), 0.92)))

        let streak = CM.mix(s.ridgeFar, s.shadow, 0.4)
        for i in 0..<4 {
            let t = Double(i) / 3.0
            let x1 = w * (0.37 + t * 0.13)
            let y1 = horizonY - h * (0.30 + t * 0.14)
            let x2 = w * (0.40 + t * 0.10)
            let y2 = horizonY - h * (0.42 + t * 0.12)
            var rock = Path()
            rock.move(to: P(x1, y1))
            rock.addLine(to: P(x2, y2))
            rock.addLine(to: P(x2 + w * 0.006, y2 + h * 0.008))
            rock.addLine(to: P(x1 + w * 0.006, y1 + h * 0.008))
            rock.closeSubpath()
            gc.opacity = (0.4)
            gc.fill(rock, with: .color(streak))
        }
        gc.opacity = 1
    }

    // ── Mist band ──
    private func paintMist(_ gc: inout GraphicsContext, _ rng: inout SplitMix64,
                           at centerY: Double, alpha: Double) {
        let mist = CM.mix(s.skyHorizon, palette.ink, 0.24)
        gc.opacity = (alpha)
        gc.fill(Path(R(w * -0.12, centerY - h / 32, w * 1.24, h / 16)), with: .color(mist))
        gc.opacity = alpha * 0.6
        gc.fill(Path(R(w * -0.06, centerY - h / 48, w * 1.12, h / 24)), with: .color(mist))
        gc.opacity = 1
    }

    // ── River ──
    private func paintRiver(_ gc: inout GraphicsContext, _ rng: inout SplitMix64) {
        var path = Path()
        path.move(to: P(w * 0.40, h * 1.02))
        path.addQuadCurve(to: P(w * 0.45, h * 0.92),
                          control: P(w * 0.42, h * 0.97))
        path.addQuadCurve(to: P(w * 0.55, h * 0.80),
                          control: P(w * 0.47, h * 0.86))
        path.addQuadCurve(to: P(w * 0.62, h * 0.72),
                          control: P(w * 0.56, h * 0.78))
        path.addLine(to: P(w * 0.66, h * 0.76))
        path.addLine(to: P(w * 0.56, h * 0.82))
        path.addLine(to: P(w * 0.52, h * 0.84))
        path.addLine(to: P(w * 0.44, h * 0.92))
        path.closeSubpath()
        gc.fill(path, with: .color(CM.mix(s.lakeDeep, palette.accent, 0.28)))
        gc.opacity = 0.45
        for _ in 0..<14 {
            let fx = w * rng.range(0.42, 0.66)
            let fy = horizonY + h * rng.range(0.04, 0.10)
            gc.fill(Path(R(fx - 2.5, fy - 0.75, 5, 1.5)),
                    with: .color(CM.mix(s.lakeGlint, palette.accent, 0.30)))
        }
        gc.opacity = 1
    }

    // ── Forest ──
    private func paintForest(_ gc: inout GraphicsContext, _ rng: inout SplitMix64) {
        let baseY = horizonY + h * 0.08
        let groundColor = CM.mix(s.ground, palette.ink, 0.20)
        gc.opacity = (0.78)
        var ground = Path()
        ground.move(to: P(0, baseY))
        for x in stride(from: 0.0, through: w, by: 10) {
            let t = x / max(1, w)
            let n = ValueNoise.noise(t * 4.4, seed: seed &+ 404, octaves: 4)
            ground.addLine(to: P(x, baseY + n * h * 0.02))
        }
        ground.addLine(to: P(w, h))
        ground.addLine(to: P(0, h))
        ground.closeSubpath()
        gc.fill(ground, with: .color(groundColor))
        gc.opacity = 1

        let treeColor = s.foliage
        let treeDark = CM.scale(treeColor, 0.6)
        let treeLight = CM.mix(treeColor, palette.ink, 0.15)
        let clearingX0 = w * 0.13, clearingX1 = w * 0.48
        for x in stride(from: -8.0, through: w + 8, by: 6) {
            if x > clearingX0 && x < clearingX1 { continue }
            let t = x / max(1, w)
            let n = pow(ValueNoise.noise(t * 4.4, seed: seed &+ 404, octaves: 4), 1.22)
            let spine = 0.55 + 0.45 * sin(t * .pi)
            let y = horizonY - h * (n * 0.70 + spine * 0.30) * 0.22
            guard rng.range(0, 1) > 0.65 else { continue }
            let th = h * rng.range(0.018, 0.038)
            paintFir(&gc, x: x, baseY: y + 1, th: th,
                     color: treeColor, dark: treeDark, light: treeLight,
                     shadow: true)
        }
    }

    private func paintFir(_ gc: inout GraphicsContext, x: Double, baseY: Double,
                          th: Double, color: Color, dark: Color, light: Color,
                          shadow: Bool) {
        let half = max(2, th / 3)
        if shadow {
            let shadowLen = th * 0.7
            var sh = Path()
            sh.move(to: P(x - 1, baseY + 1))
            sh.addLine(to: P(x + 1, baseY + 1))
            sh.addLine(to: P(x - shadowLen, baseY + 1 + shadowLen * 0.18))
            sh.addLine(to: P(x - shadowLen - 3, baseY + 1 + shadowLen * 0.18 - 2))
            sh.closeSubpath()
            gc.opacity = (0.18)
            gc.fill(sh, with: .color(s.shadow))
            gc.opacity = 1
        }
        let trunkW = max(1, th * 0.06)
        let trunkH = th * 0.15
        gc.fill(Path(R(x - trunkW / 2, baseY - trunkH, trunkW, trunkH)),
                with: .color(dark))
        for tier in 0..<3 {
            let tierH = th / 3.0
            let tierW = half * Double(3 - tier) / 3.0
            let tierY = baseY - th + Double(tier) * tierH - tierH * 0.4
            let tierColor = tier == 0 ? light : (tier == 1 ? color : dark)
            gc.fill(Path(R(x - tierW / 2, tierY, tierW, tierH)), with: .color(tierColor))
        }
    }

    // ── Hero pines ──
    private func paintHeroPines(_ gc: inout GraphicsContext, _ rng: inout SplitMix64) {
        let pineColor = CM.mix(s.ridgeNear, s.shadow, 0.35)
        let pineDark = CM.scale(pineColor, 0.5)
        let pineLight = CM.mix(pineColor, palette.ink, 0.08)
        paintFir(&gc, x: w * 0.035, baseY: h * 1.02, th: h * 0.30,
                 color: pineColor, dark: pineDark, light: pineLight, shadow: true)
        paintFir(&gc, x: w * 0.105, baseY: h * 1.04, th: h * 0.22,
                 color: pineColor, dark: pineDark, light: pineLight, shadow: true)
        paintFir(&gc, x: w * 0.965, baseY: h * 1.03, th: h * 0.27,
                 color: pineColor, dark: pineDark, light: pineLight, shadow: true)
        paintFir(&gc, x: w * 0.905, baseY: h * 1.05, th: h * 0.19,
                 color: pineColor, dark: pineDark, light: pineLight, shadow: true)
    }

    // ── Airborne life ──
    private func paintAirborne(_ gc: inout GraphicsContext, _ rng: inout SplitMix64) {
        if variant == .night {
            let firefly = CM.mix(palette.accent, palette.ink, 0.3)
            for _ in 0..<6 {
                let fx = w * rng.range(0.14, 0.72)
                let fy = h * rng.range(0.66, 0.90)
                gc.opacity = 0.10
                gc.fill(Path(ellipseIn: R(fx - 4, fy - 4, 8, 8)), with: .color(firefly))
                gc.opacity = 0.80
                gc.fill(Path(ellipseIn: R(fx - 1.5, fy - 1.5, 3, 3)), with: .color(firefly))
            }
            gc.opacity = 1
        } else {
            let mote = CM.withOpacity(palette.ink, 0.25)
            for _ in 0..<10 {
                let x = rng.range(w * 0.1, w * 0.9)
                let y = rng.range(h * 0.3, h * 0.8)
                let sz = rng.range(1, 2.5)
                gc.fill(Path(ellipseIn: R(x, y, sz, sz)), with: .color(mote))
            }
            gc.opacity = 1
        }
    }

    // ── Vignette ──
    private func paintVignette(_ gc: inout GraphicsContext) {
        let vignette = CM.mix(s.skyTop, palette.chrome(variant).chromeDeep, 0.5)
        gc.opacity = (0.22)
        gc.fill(Path(R(0, h - h / 14, w, h / 14)),
                with: .linearGradient(Gradient(stops: [
                    Gradient.Stop(color: vignette.opacity(0), location: 0),
                    Gradient.Stop(color: vignette, location: 1.0)]),
                                      startPoint: P(0, h - h / 14),
                                      endPoint: P(0, h)))
        gc.opacity = (0.10)
        gc.fill(Path(R(0, 0, w / 16, h)),
                with: .linearGradient(Gradient(stops: [
                    Gradient.Stop(color: vignette.opacity(0), location: 0),
                    Gradient.Stop(color: vignette, location: 1.0)]),
                                      startPoint: P(0, 0), endPoint: P(w / 16, 0)))
        gc.fill(Path(R(w - w / 16, 0, w / 16, h)),
                with: .linearGradient(Gradient(stops: [
                    Gradient.Stop(color: vignette.opacity(0), location: 0),
                    Gradient.Stop(color: vignette, location: 1)]),
                                      startPoint: P(w - w / 16, 0),
                                      endPoint: P(w, 0)))
        gc.opacity = 0.12
        gc.fill(Path(R(0, 0, w, h / 18)),
                with: .linearGradient(Gradient(stops: [
                    Gradient.Stop(color: vignette, location: 0),
                    Gradient.Stop(color: vignette.opacity(0), location: 1)]),
                                      startPoint: P(0, 0), endPoint: P(0, h / 18)))
        gc.opacity = 1
    }

    // ── Missing pieces the other methods reference ──
    private var crest: [(Double, Double)] {
        [(0.06, 1.00), (0.10, 0.66), (0.15, 0.50), (0.185, 0.34),
         (0.21, 0.40), (0.235, 0.43), (0.26, 0.345), (0.28, 0.295),
         (0.30, 0.235), (0.318, 0.160), (0.34, 0.205), (0.355, 0.260),
         (0.385, 0.155), (0.40, 0.125), (0.418, 0.185), (0.44, 0.24),
         (0.465, 0.33), (0.49, 0.385), (0.515, 0.47), (0.545, 0.56),
         (0.58, 0.70), (0.62, 0.82), (0.665, 1.00)]
    }
    private var apexIdx = 13

    @inline(__always) func px(_ fx: Double, _ fy: Double) -> CGPoint {
        P(w * fx, horizonY - h * fy)
    }
}