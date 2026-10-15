import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// NIGHTWOOD GRAND — the launcher world's painter.
//
// A realistic, layered forest-mountain landscape rendered entirely through
// SwiftUI Canvas primitives. One static render per size/theme/variant —
// zero idle cost, zero battery drain. The scene rasterizes once into a
// Metal texture via .drawingGroup() and never repaints until something
// actually changes (window resize, theme switch, day↔night transition).
//
// Layer stack (painted back→front):
//   sky gradient · stars/moon or sun/birds · grand massif · mist ·
//   value-noise far ridge · treeline · winding river with glints ·
//   near shore with reeds · dense conifer forest · cabin + trail ·
//   hero pines framing the edges · fireflies/pollen · vignette
// ─────────────────────────────────────────────────────────────────────────────

struct NightwoodScene: View {
    let palette: ThemePalette
    let variant: SceneVariant

    var body: some View {
        Canvas { context, size in
            var ctx = context
            Painter(palette: palette, variant: variant, size: size)
                .render(into: &ctx)
        }
        .drawingGroup()
        .accessibilityHidden(true)
    }
}

// MARK: - Painter

private struct Painter {
    let palette: ThemePalette
    let variant: SceneVariant
    let w: Double
    let h: Double
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

    func render(into ctx: inout GraphicsContext) {
        var rng = SplitMix64(seed: seed)
        sky(&ctx, &rng)
        celestial(&ctx, &rng)
        massif(&ctx)
        mist(&ctx, &rng, at: horizonY * 1.06, alpha: 0.06)
        farRidge(&ctx)
        treeline(&ctx, &rng)
        mist(&ctx, &rng, at: horizonY * 1.14, alpha: 0.045)
        river(&ctx, &rng)
        shoreline(&ctx, &rng)
        forest(&ctx, &rng)
        cabin(&ctx)
        trail(&ctx, &rng)
        heroes(&ctx, &rng)
        airborne(&ctx, &rng)
        vignette(&ctx)
    }

    // MARK: Sky

    private func sky(_ ctx: inout GraphicsContext, _ rng: inout SplitMix64) {
        let glow = variant == .day
            ? CM.mix(s.skyHorizon, palette.accent, 0.30)
            : CM.mix(s.skyHorizon, palette.accent, 0.14)
        let stops: [Gradient.Stop] = [
            .init(color: s.skyTop, location: 0.0),
            .init(color: CM.mix(s.skyTop, s.skyHorizon, 0.34), location: 0.50),
            .init(color: CM.mix(s.skyTop, s.skyHorizon, 0.80), location: 0.83),
            .init(color: s.skyHorizon, location: 0.945),
            .init(color: glow, location: 1.0),
        ]
        ctx.fill(
            Path(R(0, 0, w, horizonY * 1.005)),
            with: .linearGradient(Gradient(stops: stops),
                                  startPoint: Pt(0, 0),
                                  endPoint: Pt(0, horizonY)))

        if variant == .night {
            // Stars: three depth classes, density thinning toward horizon
            let dust = CM.withOpacity(CM.mix(palette.ink, s.skyHorizon, 0.30), 0.55)
            var placed = 0
            while placed < 200 {
                let f = rng.unit()
                guard rng.chance(1.0 - f * f * 0.75) else { continue }
                ctx.fill(Path(R(rng.range(0, w), f * horizonY * 0.92, 1, 1)),
                         with: .color(dust))
                placed += 1
            }
            let mid = CM.withOpacity(palette.ink, 0.85)
            for _ in 0..<65 {
                let f = rng.unit()
                guard rng.chance(1.0 - f * 0.8) else { continue }
                let x = rng.range(0, w), y = f * horizonY * 0.86
                ctx.fill(Path(ellipseIn: R(x - 1, y - 1, 2, 2)), with: .color(mid))
            }
            let anchor = CM.withOpacity(palette.ink, 0.95)
            for _ in 0..<9 {
                let x = rng.range(w * 0.04, w * 0.96)
                let y = rng.range(h * 0.02, horizonY * 0.5)
                ctx.fill(Path(ellipseIn: R(x - 1.5, y - 1.5, 3, 3)), with: .color(anchor))
                ctx.opacity = 0.40
                ctx.fill(Path(R(x - 4.5, y - 0.5, 9, 1)), with: .color(anchor))
                ctx.fill(Path(R(x - 0.5, y - 4.5, 1, 9)), with: .color(anchor))
                ctx.opacity = 1
            }
        } else {
            // Day: high cirrus wisps
            let wisp = CM.withOpacity(CM.mix(s.skyHorizon, palette.ink, 0.45), 1)
            for _ in 0..<3 {
                ctx.opacity = rng.range(0.05, 0.09)
                ctx.fill(Path(ellipseIn: R(w * rng.range(0.05, 0.55),
                                           h * rng.range(0.05, 0.20),
                                           w * rng.range(0.2, 0.4), max(3, h / 170))),
                         with: .color(wisp))
            }
            ctx.opacity = 1
            // Day: cumulus with shaded undersides
            let cTop = CM.mix(palette.ink, s.skyHorizon, 0.30)
            let cBelly = CM.mix(s.skyHorizon, CM.scale(s.shadow, 1.0), 0.42)
            for (cx, cy, sc) in [(0.22, 0.11, 1.0), (0.58, 0.075, 1.2), (0.84, 0.15, 0.8)] {
                let px = cx * w, py = cy * h
                let puffs: [(Double, Double, Double)] = [
                    (0.0, 0.0, 0.34), (-0.28, 0.01, 0.25), (0.28, 0.012, 0.27),
                    (-0.12, -0.026, 0.23), (0.12, -0.03, 0.21), (-0.45, 0.02, 0.17)]
                for (ox, oy, r) in puffs {
                    let rr = w * 0.055 * sc * r * 2.2
                    ctx.fill(Path(ellipseIn: R(px + ox * rr * 2 - rr, py + oy * rr + rr * 0.18,
                                                rr * 2, rr * 1.08)), with: .color(cBelly))
                }
                for (ox, oy, r) in puffs {
                    let rr = w * 0.055 * sc * r * 2.2
                    ctx.fill(Path(ellipseIn: R(px + ox * rr * 2 - rr, py + oy * rr,
                                                rr * 2, rr * 0.96)), with: .color(cTop))
                }
            }
        }
    }

    // MARK: Celestial

    private func celestial(_ ctx: inout GraphicsContext, _ rng: inout SplitMix64) {
        let cx = w * 0.74, cy = h * 0.165
        if variant == .night {
            let disc = max(h * 0.052, 18)
            let halo = CM.mix(palette.accentPair, palette.ink, 0.6)
            for ring in stride(from: 6, through: 1, by: -1) {
                ctx.opacity = 0.022 + Double(6 - ring) * 0.006
                let d = disc * (1.4 + Double(ring) * 0.55)
                ctx.fill(Path(ellipseIn: R(cx - d / 2, cy - d / 2, d, d)), with: .color(halo))
            }
            ctx.opacity = 1
            let face = CM.mix(palette.ink, palette.accent, 0.08)
            ctx.fill(Path(ellipseIn: R(cx - disc / 2, cy - disc / 2, disc, disc)),
                     with: .color(face))
            let mare = CM.withOpacity(CM.scale(face, 0.7), 0.22)
            for (ox, oy, r) in [(-0.16, -0.10, 0.115), (0.07, 0.10, 0.085), (-0.06, 0.15, 0.055)] {
                let rr = disc * r
                ctx.fill(Path(ellipseIn: R(cx + ox * disc - rr, cy + oy * disc - rr, rr * 2, rr * 2)),
                         with: .color(mare))
            }
        } else {
            let disc = max(h * 0.04, 15)
            let glow = CM.mix(palette.accentPair, palette.ink, 0.4)
            for ring in stride(from: 9, through: 1, by: -1) {
                ctx.opacity = 0.018 + Double(9 - ring) * 0.005
                let d = disc * (1.6 + Double(ring) * 0.55)
                ctx.fill(Path(ellipseIn: R(cx - d / 2, cy - d / 2, d, d)), with: .color(glow))
            }
            ctx.opacity = 1
            ctx.fill(Path(ellipseIn: R(cx - disc / 2, cy - disc / 2, disc, disc)),
                     with: .color(CM.mix(palette.ink, palette.accent, 0.18)))
            // Slow birds
            let bird = CM.withOpacity(s.ridgeNear, 0.75)
            for _ in 0..<6 {
                let bx = rng.range(w * 0.30, w * 0.66)
                let by = rng.range(h * 0.24, horizonY * 0.72)
                let flap = rng.unit()
                var b = Path()
                b.move(to: Pt(bx - 5, by + (1 - flap) * 2))
                b.addQuadCurve(to: Pt(bx, by), control: Pt(bx - 2, by - 2 - flap * 3))
                b.addQuadCurve(to: Pt(bx + 5, by + (1 - flap) * 2),
                               control: Pt(bx + 2, by - 2 - flap * 3))
                ctx.opacity = 0.5
                ctx.stroke(b, with: .color(bird), lineWidth: 1.2)
                ctx.opacity = 1
            }
        }
    }

    // MARK: Grand massif

    private func massif(_ ctx: inout GraphicsContext) {
        let baseY = horizonY + h * 0.03
        let crest: [(Double, Double)] = [
            (0.06, 1.00), (0.10, 0.66), (0.15, 0.50), (0.185, 0.34),
            (0.21, 0.40), (0.235, 0.43),
            (0.26, 0.345), (0.28, 0.295), (0.30, 0.235),
            (0.318, 0.160),
            (0.34, 0.205), (0.355, 0.260),
            (0.385, 0.155), (0.40, 0.125),
            (0.418, 0.185), (0.44, 0.24), (0.465, 0.33),
            (0.49, 0.385), (0.515, 0.47), (0.545, 0.56),
            (0.58, 0.70), (0.62, 0.82), (0.665, 1.0),
        ]
        func px(_ fx: Double, _ fy: Double) -> CGPoint {
            Pt(w * fx, baseY - h * fy)
        }

        // Shadow face (whole body)
        var body = Path()
        body.move(to: px(crest[0].0, crest[0].1))
        for (fx, fy) in crest.dropFirst() { body.addLine(to: px(fx, fy)) }
        body.addLine(to: Pt(w * 0.70, baseY + h * 0.02))
        body.addLine(to: Pt(w * 0.04, baseY + h * 0.02))
        body.closeSubpath()
        let shadowFace = CM.mix(s.ridgeFar, s.shadow, 0.55)
        ctx.fill(body, with: .color(shadowFace))

        // Lit face (SE of apex)
        let apexIdx = 13
        var lit = Path()
        lit.move(to: px(crest[apexIdx].0, crest[apexIdx].1))
        for i in (apexIdx + 1)..<crest.count { lit.addLine(to: px(crest[i].0, crest[i].1)) }
        lit.addLine(to: Pt(w * 0.70, baseY + h * 0.02))
        lit.addLine(to: px(crest[apexIdx].0, crest[apexIdx].1))
        lit.closeSubpath()
        let litFace = variant == .day
            ? CM.mix(s.ridgeFar, s.skyHorizon, 0.42)
            : CM.mix(s.ridgeFar, s.skyHorizon, 0.28)
        ctx.fill(lit, with: .color(litFace))

        // Snow caps
        let snow = CM.mix(palette.ink, palette.accent, 0.10)
        var cap = Path()
        cap.move(to: px(0.318, 0.16))
        cap.addLine(to: px(0.34, 0.205))
        cap.addLine(to: px(0.355, 0.26))
        cap.addLine(to: px(0.385, 0.155))
        cap.addLine(to: px(0.40, 0.125))
        cap.addLine(to: px(0.418, 0.185))
        cap.addLine(to: px(0.44, 0.24))
        cap.addLine(to: px(0.418, 0.26))
        cap.addLine(to: px(0.385, 0.24))
        cap.addLine(to: px(0.355, 0.32))
        cap.addLine(to: px(0.34, 0.27))
        cap.addLine(to: px(0.318, 0.22))
        cap.closeSubpath()
        ctx.fill(cap, with: .color(CM.withOpacity(snow, 0.9)))

        var westCap = Path()
        westCap.move(to: px(0.26, 0.345))
        westCap.addLine(to: px(0.28, 0.295))
        westCap.addLine(to: px(0.30, 0.235))
        westCap.addLine(to: px(0.29, 0.30))
        westCap.closeSubpath()
        ctx.fill(westCap, with: .color(CM.withOpacity(snow, 0.8)))

        // Rock band streaks on lit face
        let streak = CM.mix(s.ridgeFar, s.shadow, 0.4)
        for i in 0..<4 {
            let t = Double(i) / 3.0
            let x1 = w * (0.37 + t * 0.13)
            let y1 = baseY - h * (0.30 + t * 0.14)
            let x2 = w * (0.41 + t * 0.10)
            let y2 = baseY - h * (0.42 + t * 0.10)
            var rock = Path()
            rock.move(to: Pt(x1, y1))
            rock.addLine(to: Pt(x2, y2))
            rock.addLine(to: Pt(x2 + w * 0.006, y2 + h * 0.008))
            rock.addLine(to: Pt(x1 + w * 0.006, y1 + h * 0.008))
            rock.closeSubpath()
            ctx.opacity = 0.4
            ctx.fill(rock, with: .color(streak))
        }
        ctx.opacity = 1
    }

    // MARK: Mist

    private func mist(_ ctx: inout GraphicsContext, _ rng: inout SplitMix64,
                      at centerY: Double, alpha: Double) {
        let mistColor = CM.mix(s.skyHorizon, palette.ink, 0.24)
        ctx.opacity = alpha
        ctx.fill(Path(ellipseIn: R(-w * 0.12, centerY - h / 32,
                                   w * 1.24, h / 16)), with: .color(mistColor))
        ctx.opacity = alpha * 0.6
        ctx.fill(Path(ellipseIn: R(-w * 0.06, centerY - h / 48,
                                   w * 1.12, h / 24)), with: .color(mistColor))
        ctx.opacity = 1
    }

    // MARK: Far ridge

    private func farRidge(_ ctx: inout GraphicsContext) {
        let baseY = horizonY + h * 0.04
        let farTone = CM.mix(s.ridgeFar, s.skyHorizon, 0.55)
        var ridge = Path()
        ridge.move(to: Pt(-16, baseY + 16))
        for x in stride(from: -16.0, through: w + 16, by: 9) {
            let t = x / max(1, w)
            let n = pow(ValueNoise.noise(t * 2.4, seed: seed &+ 101, octaves: 4), 1.22)
            let spine = 0.55 + 0.45 * sin(t * .pi)
            let y = baseY - (n * 0.70 + spine * 0.30) * h * 0.16
            ridge.addLine(to: Pt(x, y))
        }
        ridge.addLine(to: Pt(w + 16, baseY + 16))
        ridge.closeSubpath()
        ctx.fill(ridge, with: .color(farTone))
    }

    // MARK: Treeline (distant forest)

    private func treeline(_ ctx: inout GraphicsContext, _ rng: inout SplitMix64) {
        let baseY = horizonY + h * 0.07
        let treeColor = CM.mix(s.ridgeMid, s.ridgeFar, 0.35)
        var band = Path()
        band.move(to: Pt(0, baseY))
        for x in stride(from: 0.0, through: w, by: 5) {
            let t = x / max(1, w)
            let n = ValueNoise.noise(t * 6.0, seed: seed &+ 202, octaves: 3)
            let y = baseY - n * h * 0.045 - h * 0.02
            band.addLine(to: Pt(x, y))
            // tree spikes
            if rng.chance(0.3) {
                let spikeH = h * rng.range(0.008, 0.020)
                band.addLine(to: Pt(x + 2, y - spikeH))
                band.addLine(to: Pt(x + 4, y))
            }
        }
        band.addLine(to: Pt(w, baseY + h * 0.08))
        band.addLine(to: Pt(0, baseY + h * 0.08))
        band.closeSubpath()
        ctx.fill(band, with: .color(treeColor))
    }

    // MARK: River

    private func river(_ ctx: inout GraphicsContext, _ rng: inout SplitMix64) {
        let topY = horizonY * 1.10
        let bottomY = h * 1.02

        // Centerline: S-curve from valley to bottom edge
        let centerline: [(Double, Double)] = [
            (0.52, 0.68), (0.56, 0.72), (0.48, 0.76), (0.55, 0.81),
            (0.47, 0.86), (0.52, 0.91), (0.44, 0.96), (0.47, 1.02),
        ]

        func riverX(at t: Double) -> Double {
            let idx = t * Double(centerline.count - 1)
            let i0 = Int(idx)
            let i1 = min(i0 + 1, centerline.count - 1)
            let frac = idx - Double(i0)
            let x0 = centerline[i0].0, x1 = centerline[i1].0
            return (x0 + (x1 - x0) * frac) * w
        }
        func riverY(at t: Double) -> Double {
            topY + (bottomY - topY) * t
        }
        func riverHalfWidth(at t: Double) -> Double {
            max(4, h * 0.012 + t * h * 0.045)
        }

        // Banks (darker soil)
        var bank = Path()
        let steps = 40
        for i in 0...steps {
            let t = Double(i) / Double(steps)
            let cx = riverX(at: t), cy = riverY(at: t)
            let hw = riverHalfWidth(at: t) + w * 0.006
            bank.addLine(to: Pt(cx - hw, cy))
        }
        for i in stride(from: steps, through: 0, by: -1) {
            let t = Double(i) / Double(steps)
            let cx = riverX(at: t), cy = riverY(at: t)
            let hw = riverHalfWidth(at: t) + w * 0.006
            bank.addLine(to: Pt(cx + hw, cy))
        }
        bank.closeSubpath()
        let bankColor = CM.mix(s.ground, s.shadow, 0.3)
        ctx.fill(bank, with: .color(CM.withOpacity(bankColor, 0.6)))

        // Water surface
        var water = Path()
        for i in 0...steps {
            let t = Double(i) / Double(steps)
            let cx = riverX(at: t), cy = riverY(at: t)
            water.addLine(to: Pt(cx - riverHalfWidth(at: t), cy))
        }
        for i in stride(from: steps, through: 0, by: -1) {
            let t = Double(i) / Double(steps)
            let cx = riverX(at: t), cy = riverY(at: t)
            water.addLine(to: Pt(cx + riverHalfWidth(at: t), cy))
        }
        water.closeSubpath()

        let waterTop = variant == .day
            ? CM.mix(s.lakeGlint, s.lakeDeep, 0.3)
            : CM.mix(s.lakeDeep, palette.accent, 0.12)
        let waterBottom = s.lakeDeep
        let waterStops: [Gradient.Stop] = [
            .init(color: waterTop, location: 0.0),
            .init(color: CM.mix(waterTop, waterBottom, 0.5), location: 0.4),
            .init(color: waterBottom, location: 1.0),
        ]
        ctx.fill(water, with: .linearGradient(Gradient(stops: waterStops),
                                              startPoint: Pt(0, topY),
                                              endPoint: Pt(0, bottomY)))

        // Glints — bright dashes on the water surface
        ctx.opacity = variant == .day ? 0.5 : 0.35
        let glintColor = CM.mix(s.lakeGlint, palette.ink, 0.2)
        for _ in 0..<35 {
            let t = rng.unit()
            let cx = riverX(at: t) + rng.range(-0.4, 0.4) * riverHalfWidth(at: t)
            let cy = riverY(at: t) + rng.range(-2, 2)
            let len = rng.range(3, 10)
            ctx.fill(Path(R(cx - len / 2, cy, len, 1.2)), with: .color(glintColor))
        }
        ctx.opacity = 1
    }

    // MARK: Shoreline

    private func shoreline(_ ctx: inout GraphicsContext, _ rng: inout SplitMix64) {
        let shoreY = horizonY * 1.10
        let shoreColor = CM.mix(s.ground, s.ridgeNear, 0.3)
        var shore = Path()
        shore.move(to: Pt(0, shoreY))
        for x in stride(from: 0.0, through: w, by: 12) {
            let t = x / max(1, w)
            let n = ValueNoise.noise(t * 3.0, seed: seed &+ 303, octaves: 3)
            shore.addLine(to: Pt(x, shoreY + n * h * 0.015))
        }
        shore.addLine(to: Pt(w, h))
        shore.addLine(to: Pt(0, h))
        shore.closeSubpath()
        ctx.fill(shore, with: .color(shoreColor))

        // Reeds along the bank
        let reedColor = CM.mix(s.foliage, s.shadow, 0.2)
        for _ in 0..<24 {
            let x = rng.range(0, w)
            let n = ValueNoise.noise(x / w * 3.0, seed: seed &+ 303, octaves: 3)
            let y = shoreY + n * h * 0.015
            let hgt = rng.range(h * 0.008, h * 0.018)
            var reed = Path()
            reed.move(to: Pt(x, y))
            reed.addLine(to: Pt(x + rng.range(-2, 2), y - hgt))
            ctx.opacity = 0.6
            ctx.stroke(reed, with: .color(reedColor), lineWidth: 1)
            // seed head
            ctx.fill(Path(ellipseIn: R(x - 1, y - hgt - 2, 2, 4)), with: .color(reedColor))
        }
        ctx.opacity = 1
    }

    // MARK: Forest (dense conifers)

    private func forest(_ ctx: inout GraphicsContext, _ rng: inout SplitMix64) {
        let baseY = h * 0.88
        let groundColor = s.ground
        var ground = Path()
        ground.move(to: Pt(0, baseY))
        for x in stride(from: 0.0, through: w, by: 10) {
            let t = x / max(1, w)
            let n = ValueNoise.noise(t * 4.4, seed: seed &+ 404, octaves: 4)
            let spine = 0.55 + 0.45 * sin(t * .pi)
            let y = baseY - (n * 0.70 + spine * 0.30) * h * 0.22
            ground.addLine(to: Pt(x, y))
        }
        ground.addLine(to: Pt(w, h))
        ground.addLine(to: Pt(0, h))
        ground.closeSubpath()
        ctx.fill(ground, with: .color(groundColor))

        // Conifer rows along the crest
        let treeColor = s.foliage
        let treeDark = CM.scale(treeColor, 0.6)
        let treeLit = CM.mix(treeColor, palette.ink, 0.15)

        // Skip a clearing where the cabin sits
        let clearingX0 = w * 0.55, clearingX1 = w * 0.70

        for x in stride(from: -8.0, through: w + 8, by: 6) {
            if x > clearingX0 && x < clearingX1 { continue }
            let t = x / max(1, w)
            let n = pow(ValueNoise.noise(t * 4.4, seed: seed &+ 404, octaves: 4), 1.22)
            let spine = 0.55 + 0.45 * sin(t * .pi)
            let y = baseY - (n * 0.70 + spine * 0.30) * h * 0.22

            guard rng.chance(0.65) else { continue }
            let th = h * rng.range(0.018, 0.038)
            fir(&ctx, x: x, baseY: y + 1, th: th,
                color: treeColor, dark: treeDark, lit: treeLit, shadow: true)
        }
    }

    /// One conifer: trunk + three tiered foliage triangles + cast shadow.
    private func fir(_ ctx: inout GraphicsContext, x: Double, baseY: Double,
                     th: Double, color: Color, dark: Color, lit: Color, shadow: Bool) {
        let half = max(2, th * 0.30)
        if shadow {
            // Cast shadow — light comes from upper right, shadow falls left
            let shadowLen = th * 0.7
            var sh = Path()
            sh.move(to: Pt(x - 1, baseY))
            sh.addLine(to: Pt(x + 1, baseY))
            sh.addLine(to: Pt(x - shadowLen, baseY + shadowLen * 0.18))
            sh.addLine(to: Pt(x - shadowLen - 3, baseY + shadowLen * 0.18 - 2))
            sh.closeSubpath()
            ctx.opacity = 0.18
            ctx.fill(sh, with: .color(s.shadow))
            ctx.opacity = 1
        }
        // Trunk
        let trunkW = max(1, th * 0.06)
        ctx.fill(Path(R(x - trunkW / 2, baseY - th * 0.15, trunkW, th * 0.15)),
                 with: .color(dark))
        // Tier 1 (top)
        ctx.fill(poly([(x, baseY - th), (x - half * 0.5, baseY - th * 0.62),
                       (x + half * 0.5, baseY - th * 0.62)]), with: .color(lit))
        // Tier 2
        ctx.fill(poly([(x, baseY - th * 0.80), (x - half * 0.75, baseY - th * 0.32),
                       (x + half * 0.75, baseY - th * 0.32)]), with: .color(color))
        // Tier 3 (base)
        ctx.fill(poly([(x, baseY - th * 0.55), (x - half, baseY),
                       (x + half, baseY)]), with: .color(dark))
    }

    @inline(__always) private func poly(_ pts: [(Double, Double)]) -> Path {
        var p = Path()
        p.move(to: Pt(pts[0].0, pts[0].1))
        for (x, y) in pts.dropFirst() { p.addLine(to: Pt(x, y)) }
        p.closeSubpath()
        return p
    }

    // MARK: Cabin + Trail

    private func cabin(_ ctx: inout GraphicsContext) {
        let cx = w * 0.615, cy = horizonY * 1.18
        let cw = w * 0.035, ch = h * 0.025

        // Cast shadow
        var sh = Path()
        sh.move(to: Pt(cx - cw, cy + ch * 0.3))
        sh.addLine(to: Pt(cx + cw, cy + ch * 0.3))
        sh.addLine(to: Pt(cx - cw * 1.2, cy + ch * 0.6))
        sh.closeSubpath()
        ctx.opacity = 0.25
        ctx.fill(sh, with: .color(s.shadow))
        ctx.opacity = 1

        // Walls
        let wall = CM.mix(s.ground, palette.ink, 0.12)
        ctx.fill(Path(R(cx - cw, cy, cw * 2, ch)), with: .color(wall))

        // Roof (triangle)
        var roof = Path()
        roof.move(to: Pt(cx - cw * 1.15, cy))
        roof.addLine(to: Pt(cx, cy - ch * 0.6))
        roof.addLine(to: Pt(cx + cw * 1.15, cy))
        roof.closeSubpath()
        let roofColor = CM.mix(wall, s.shadow, 0.4)
        ctx.fill(roof, with: .color(roofColor))

        // Window — warm glow at night, neutral at day
        let winColor = variant == .night
            ? CM.mix(palette.accent, palette.ink, 0.3)
            : CM.mix(palette.ink, s.skyHorizon, 0.5)
        let winW = cw * 0.30, winH = ch * 0.45
        let winX = cx - winW / 2, winY = cy + ch * 0.25
        ctx.fill(Path(R(winX, winY, winW, winH)), with: .color(winColor))
        if variant == .night {
            // Window glow halo
            ctx.opacity = 0.15
            let glowR = cw * 1.2
            ctx.fill(Path(ellipseIn: R(winX + winW / 2 - glowR,
                                       winY + winH / 2 - glowR, glowR * 2, glowR * 2)),
                     with: .color(palette.accent))
            ctx.opacity = 1
        }

        // Chimney
        let chimW = cw * 0.12, chimH = ch * 0.5
        ctx.fill(Path(R(cx + cw * 0.5, cy - chimH - ch * 0.3, chimW, chimH)),
                 with: .color(CM.scale(wall, 0.7)))
    }

    private func trail(_ ctx: inout GraphicsContext, _ rng: inout SplitMix64) {
        let startX = w * 0.42, endX = w * 0.585
        let startY = h * 1.02, endY = horizonY * 1.18

        var path = Path()
        let pts: [(Double, Double)] = [
            (startX, startY), (startX + w * 0.05, startY - h * 0.06),
            (startX - w * 0.02, startY - h * 0.12), (endX - w * 0.03, endY + h * 0.04),
            (endX, endY),
        ]
        path.move(to: Pt(pts[0].0, pts[0].1))
        for (px, py) in pts.dropFirst() { path.addLine(to: Pt(px, py)) }

        let trailColor = CM.mix(s.ground, palette.ink, 0.28)
        ctx.opacity = 0.5
        ctx.stroke(path, with: .color(trailColor), lineWidth: max(2, w * 0.004))
        ctx.opacity = 1

        // Trail stones
        for (px, py) in pts {
            if rng.chance(0.7) {
                let stoneR = rng.range(1, 3)
                ctx.opacity = 0.4
                ctx.fill(Path(ellipseIn: R(px + rng.range(-5, 5), py + rng.range(-3, 3),
                                           stoneR, stoneR * 0.7)),
                         with: .color(CM.mix(trailColor, s.shadow, 0.5)))
                ctx.opacity = 1
            }
        }
    }

    // MARK: Hero pines (edge framing)

    private func heroes(_ ctx: inout GraphicsContext, _ rng: inout SplitMix64) {
        let heroColor = CM.mix(s.ridgeNear, s.shadow, 0.35)
        let heroDark = CM.scale(heroColor, 0.5)
        let heroLit = CM.mix(heroColor, palette.ink, 0.08)

        // Left edge: two large pines
        fir(&ctx, x: w * 0.025, baseY: h * 1.02, th: h * 0.32,
            color: heroColor, dark: heroDark, lit: heroLit, shadow: true)
        fir(&ctx, x: w * 0.09, baseY: h * 1.04, th: h * 0.22,
            color: heroColor, dark: heroDark, lit: heroLit, shadow: true)

        // Right edge: one large pine
        fir(&ctx, x: w * 0.965, baseY: h * 1.03, th: h * 0.28,
            color: heroColor, dark: heroDark, lit: heroLit, shadow: true)
        fir(&ctx, x: w * 0.915, baseY: h * 1.05, th: h * 0.19,
            color: heroColor, dark: heroDark, lit: heroLit, shadow: true)
    }

    // MARK: Airborne life (fireflies / pollen)

    private func airborne(_ ctx: inout GraphicsContext, _ rng: inout SplitMix64) {
        if variant == .night {
            // Fireflies: warm glowing dots near the river and meadow
            let fireflyColor = CM.mix(palette.accent, palette.ink, 0.3)
            for _ in 0..<14 {
                let x = rng.range(w * 0.15, w * 0.85)
                let y = rng.range(h * 0.70, h * 0.90)
                let size = rng.range(1.5, 3.0)
                // Glow
                ctx.opacity = 0.10
                ctx.fill(Path(ellipseIn: R(x - size * 2, y - size * 2, size * 4, size * 4)),
                         with: .color(fireflyColor))
                // Core
                ctx.opacity = 0.75
                ctx.fill(Path(ellipseIn: R(x - size / 2, y - size / 2, size, size)),
                         with: .color(fireflyColor))
            }
            ctx.opacity = 1
        } else {
            // Pollen motes drifting in the light
            let moteColor = CM.withOpacity(palette.ink, 0.25)
            for _ in 0..<12 {
                let x = rng.range(w * 0.1, w * 0.9)
                let y = rng.range(h * 0.3, h * 0.8)
                let size = rng.range(1, 2.5)
                ctx.fill(Path(ellipseIn: R(x, y, size, size)), with: .color(moteColor))
            }
        }
    }

    // MARK: Vignette

    private func vignette(_ ctx: inout GraphicsContext) {
        let dark = CM.withOpacity(s.shadow, 1.0)
        // Bottom
        ctx.opacity = 0.35
        ctx.fill(Path(R(0, h * 0.88, w, h * 0.12)),
                 with: .linearGradient(Gradient(stops: [
                     .init(color: CM.withOpacity(dark, 0), location: 0),
                     .init(color: dark, location: 1)]),
                     startPoint: Pt(0, h * 0.88), endPoint: Pt(0, h)))
        // Sides
        ctx.opacity = 0.15
        ctx.fill(Path(R(0, 0, w * 0.03, h)),
                 with: .linearGradient(Gradient(stops: [
                     .init(color: dark, location: 0),
                     .init(color: CM.withOpacity(dark, 0), location: 1)]),
                     startPoint: Pt(0, 0), endPoint: Pt(w * 0.03, 0)))
        ctx.fill(Path(R(w * 0.97, 0, w * 0.03, h)),
                 with: .linearGradient(Gradient(stops: [
                     .init(color: CM.withOpacity(dark, 0), location: 0),
                     .init(color: dark, location: 1)]),
                     startPoint: Pt(w * 0.97, 0), endPoint: Pt(w, 0)))
        ctx.opacity = 1
    }

    // MARK: - Sugar

    @inline(__always) private func Pt(_ x: Double, _ y: Double) -> CGPoint {
        CGPoint(x: CGFloat(x), y: CGFloat(y))
    }
    @inline(__always) private func R(_ x: Double, _ y: Double,
                                     _ w: Double, _ h: Double) -> CGRect {
        CGRect(x: CGFloat(x), y: CGFloat(y), width: CGFloat(w), height: CGFloat(h))
    }
}
