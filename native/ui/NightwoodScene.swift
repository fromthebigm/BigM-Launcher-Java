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
        // Ground planes first, then the river CARVED through them (the
        // forest ground used to paint over the river and hide it), then
        // the life on top.
        meadow(&ctx, &rng)
        forestGround(&ctx, &rng)
        river(&ctx, &rng)
        reeds(&ctx, &rng)
        forestTrees(&ctx, &rng)
        manor(&ctx, &rng)
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
            // Day: cumulus with shaded undersides — puffs packed tight so
            // the cluster reads as one cloud, not scattered eggs.
            let cTop = CM.mix(palette.ink, s.skyHorizon, 0.30)
            let cBelly = CM.mix(s.skyHorizon, CM.scale(s.shadow, 1.0), 0.42)
            for (cx, cy, sc) in [(0.22, 0.11, 1.0), (0.58, 0.075, 1.2), (0.84, 0.15, 0.8)] {
                let px0 = cx * w, py0 = cy * h
                let baseW = w * 0.075 * sc
                // Flat-ish bottom slab + 4 overlapping domes on top
                ctx.fill(Path(ellipseIn: R(px0 - baseW, py0, baseW * 2, baseW * 0.55)),
                         with: .color(cBelly))
                let domes: [(Double, Double, Double)] = [
                    (-0.55, 0.02, 0.55), (-0.15, -0.10, 0.72), (0.28, -0.04, 0.62), (0.60, 0.04, 0.45)]
                for (ox, oy, r) in domes {
                    let rr = baseW * r
                    ctx.fill(Path(ellipseIn: R(px0 + ox * baseW - rr, py0 + oy * baseW - rr,
                                                rr * 2, rr * 1.7)), with: .color(cTop))
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
        // Crest polyline — (x fraction of w, y fraction of horizonY: 1.0 =
        // the horizon line itself, 0.125 = near the top of the frame).
        let crest: [(Double, Double)] = [
            (0.06, 1.00), (0.10, 0.80), (0.15, 0.66), (0.185, 0.52),
            (0.21, 0.56), (0.235, 0.58),
            (0.26, 0.50), (0.28, 0.46), (0.30, 0.42),
            (0.318, 0.30),
            (0.34, 0.34), (0.355, 0.38),
            (0.385, 0.28), (0.40, 0.25),
            (0.418, 0.33), (0.44, 0.38), (0.465, 0.46),
            (0.49, 0.50), (0.515, 0.56), (0.545, 0.62),
            (0.58, 0.72), (0.62, 0.82), (0.665, 1.00),
        ]
        // fy is a fraction of the horizon: y = fy * horizonY.
        func px(_ fx: Double, _ fy: Double) -> CGPoint {
            Pt(w * fx, fy * horizonY)
        }

        // Shadow face (whole body first)
        var body = Path()
        body.move(to: px(crest[0].0, crest[0].1))
        for (fx, fy) in crest.dropFirst() { body.addLine(to: px(fx, fy)) }
        body.addLine(to: Pt(w * 0.70, baseY + h * 0.02))
        body.addLine(to: Pt(w * 0.04, baseY + h * 0.02))
        body.closeSubpath()
        let shadowFace = CM.mix(s.ridgeFar, s.shadow, 0.55)
        ctx.fill(body, with: .color(shadowFace))

        // Lit face (right of the apex — light sits upper-right)
        let apexIdx = 13
        var lit = Path()
        lit.move(to: px(crest[apexIdx].0, crest[apexIdx].1))
        for i in (apexIdx + 1)..<crest.count { lit.addLine(to: px(crest[i].0, crest[i].1)) }
        lit.addLine(to: Pt(w * 0.70, baseY + h * 0.02))
        lit.closeSubpath()
        let litFace = variant == .day
            ? CM.mix(s.ridgeFar, s.skyHorizon, 0.30)
            : CM.mix(s.ridgeFar, s.skyHorizon, 0.18)
        ctx.fill(lit, with: .color(litFace))

        // Snow cap on the true apex + west peak, with a ragged hem
        let snow = CM.mix(palette.ink, palette.accent, 0.08)
        var cap = Path()
        cap.move(to: px(0.310, 0.315))
        cap.addLine(to: px(0.318, 0.30))
        cap.addLine(to: px(0.332, 0.335))
        cap.addLine(to: px(0.355, 0.38))
        cap.addLine(to: px(0.372, 0.315))
        cap.addLine(to: px(0.385, 0.28))
        cap.addLine(to: px(0.400, 0.25))
        cap.addLine(to: px(0.412, 0.30))
        cap.addLine(to: px(0.428, 0.355))
        cap.addLine(to: px(0.444, 0.40))
        cap.addLine(to: px(0.428, 0.42))
        cap.addLine(to: px(0.405, 0.375))
        cap.addLine(to: px(0.385, 0.345))
        cap.addLine(to: px(0.362, 0.40))
        cap.addLine(to: px(0.340, 0.365))
        cap.addLine(to: px(0.325, 0.34))
        cap.closeSubpath()
        ctx.fill(cap, with: .color(CM.withOpacity(snow, 0.92)))

        var westCap = Path()
        westCap.move(to: px(0.255, 0.52))
        westCap.addLine(to: px(0.280, 0.46))
        westCap.addLine(to: px(0.302, 0.425))
        westCap.addLine(to: px(0.292, 0.475))
        westCap.addLine(to: px(0.272, 0.52))
        westCap.closeSubpath()
        ctx.fill(westCap, with: .color(CM.withOpacity(snow, 0.82)))

        // Rock gullies cutting through the snow — thin dark streaks that
        // follow the face slope downward from the apex region.
        let gully = CM.mix(s.ridgeFar, s.shadow, 0.45)
        for (gx0, gy0, gx1, gy1, gw) in [(0.345, 0.36, 0.375, 0.55, 0.006),
                                          (0.395, 0.30, 0.430, 0.52, 0.007),
                                          (0.300, 0.34, 0.318, 0.50, 0.005)] {
            var rock = Path()
            rock.move(to: px(gx0, gy0))
            rock.addLine(to: px(gx1, gy1))
            rock.addLine(to: px(gx1 + gw, gy1 + 0.015))
            rock.addLine(to: px(gx0 + gw, gy0 + 0.015))
            rock.closeSubpath()
            ctx.opacity = 0.5
            ctx.fill(rock, with: .color(gully))
        }
        ctx.opacity = 1

        // Crest rim-light along the top silhouette (move FIRST — the old
        // code never moved to the first point, drawing a stray line from
        // the canvas corner).
        var highlight = Path()
        for (i, (fx, fy)) in crest.enumerated() {
            if i == 0 { highlight.move(to: px(fx, fy)) }
            else { highlight.addLine(to: px(fx, fy)) }
        }
        ctx.stroke(highlight,
                   with: .color(CM.mix(s.ridgeFar, palette.ink, 0.35)),
                   lineWidth: 1.2)
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

        // Centerline: a real meander — the river should wander across the
        // valley with wide S-curves, not hug a vertical stripe.
        let centerline: [(Double, Double)] = [
            (0.54, 0.68), (0.63, 0.71), (0.42, 0.75), (0.58, 0.79),
            (0.38, 0.84), (0.52, 0.89), (0.34, 0.95), (0.44, 1.02),
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

        // Banks (darker soil) — move() on the first point, or the polygon
        // drags a line in from the canvas corner.
        var bank = Path()
        let steps = 40
        for i in 0...steps {
            let t = Double(i) / Double(steps)
            let cx = riverX(at: t), cy = riverY(at: t)
            let hw = riverHalfWidth(at: t) + w * 0.006
            if i == 0 { bank.move(to: Pt(cx - hw, cy)) }
            else { bank.addLine(to: Pt(cx - hw, cy)) }
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
            let edge = Pt(cx - riverHalfWidth(at: t), cy)
            if i == 0 { water.move(to: edge) }
            else { water.addLine(to: edge) }
        }
        for i in stride(from: steps, through: 0, by: -1) {
            let t = Double(i) / Double(steps)
            let cx = riverX(at: t), cy = riverY(at: t)
            water.addLine(to: Pt(cx + riverHalfWidth(at: t), cy))
        }
        water.closeSubpath()

        let waterTop = variant == .day
            ? CM.mix(s.lakeGlint, s.lakeDeep, 0.55)
            : CM.mix(s.lakeDeep, palette.accentPair, 0.18)
        let waterBottom = CM.scale(s.lakeDeep, 0.8)
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

    // MARK: Meadow (ground plane under the valley)

    private func meadow(_ ctx: inout GraphicsContext, _ rng: inout SplitMix64) {
        let meadowY = horizonY * 1.10
        let meadowColor = CM.mix(s.ground, s.foliage, 0.25)
        var ground = Path()
        ground.move(to: Pt(0, meadowY))
        for x in stride(from: 0.0, through: w, by: 12) {
            let t = x / max(1, w)
            let n = ValueNoise.noise(t * 3.0, seed: seed &+ 303, octaves: 3)
            ground.addLine(to: Pt(x, meadowY + n * h * 0.012))
        }
        ground.addLine(to: Pt(w, h))
        ground.addLine(to: Pt(0, h))
        ground.closeSubpath()
        ctx.fill(ground, with: .color(meadowColor))
    }

    /// Cattail reeds along the river banks.
    private func reeds(_ ctx: inout GraphicsContext, _ rng: inout SplitMix64) {
        let topY = horizonY * 1.10
        let reedColor = CM.mix(s.foliage, s.shadow, 0.25)
        for _ in 0..<26 {
            let t = rng.unit()
            let side = rng.chance(0.5) ? -1.0 : 1.0
            // hug the river banks: x near the river centerline ± halfwidth
            let rx = w * (0.44 + 0.10 * t) + side * (h * 0.02 + t * h * 0.05)
            let ry = topY + (h * 0.9 - topY) * t
            let hgt = rng.range(h * 0.010, h * 0.022)
            var reed = Path()
            reed.move(to: Pt(rx, ry))
            reed.addQuadCurve(to: Pt(rx + rng.range(-3, 3), ry - hgt),
                              control: Pt(rx + rng.range(-4, 4), ry - hgt * 0.5))
            ctx.opacity = 0.65
            ctx.stroke(reed, with: .color(reedColor), lineWidth: 1.2)
            ctx.fill(Path(ellipseIn: R(rx - 1, ry - hgt - 3, 2.2, 5)), with: .color(reedColor))
        }
        ctx.opacity = 1
    }

    // MARK: Forest (dense conifers)

    /// Shared crest for the forest ground + tree rows.
    private func forestCrestY(_ t: Double) -> Double {
        let n = pow(ValueNoise.noise(t * 4.4, seed: seed &+ 404, octaves: 4), 1.22)
        let spine = 0.55 + 0.45 * sin(t * .pi)
        return h * 0.88 - (n * 0.70 + spine * 0.30) * h * 0.22
    }

    private func forestGround(_ ctx: inout GraphicsContext, _ rng: inout SplitMix64) {
        let groundColor = s.ground
        var ground = Path()
        ground.move(to: Pt(0, h * 0.88))
        for x in stride(from: 0.0, through: w, by: 10) {
            ground.addLine(to: Pt(x, forestCrestY(x / max(1, w))))
        }
        ground.addLine(to: Pt(w, h))
        ground.addLine(to: Pt(0, h))
        ground.closeSubpath()
        ctx.fill(ground, with: .color(groundColor))
    }

    private func forestTrees(_ ctx: inout GraphicsContext, _ rng: inout SplitMix64) {
        let treeColor = s.foliage
        let treeDark = CM.scale(treeColor, 0.6)
        let treeLit = CM.mix(treeColor, palette.ink, 0.15)

        // Skip a clearing where the manor sits (wider for the estate)
        let clearingX0 = w * 0.13, clearingX1 = w * 0.48

        for x in stride(from: -8.0, through: w + 8, by: 6) {
            if x > clearingX0 && x < clearingX1 { continue }
            let y = forestCrestY(x / max(1, w))

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

    // MARK: The Manor — a gothic estate on the hill
    //
    // Roswaal-mansion inspired: grand central block with steep pitched
    // roof and tower spire, flanked by attached side wings (flush — no
    // gaps), cobblestone masonry walls, gothic arched windows, warm light
    // at night. The trail approaches its gate from the bottom-left; the
    // river stays visible on the right.

    private func manor(_ ctx: inout GraphicsContext, _ rng: inout SplitMix64) {
        let mx = w * 0.26, my = horizonY * 1.15
        let mw = w * 0.09           // main wing width
        let mh = h * 0.050           // main wing wall height

        // Hill mound — constrained so it doesn't cover the river
        let hillRight = mx + mw * 1.8   // stays clear of the river path
        let hillColor = CM.mix(s.ground, s.foliage, 0.35)
        var hill = Path()
        hill.move(to: Pt(mx - mw * 2.4, my + mh * 0.8))
        hill.addQuadCurve(to: Pt(mx, my - mh * 0.15),
                          control: Pt(mx - mw * 1.2, my - mh * 0.25))
        hill.addQuadCurve(to: Pt(hillRight, my + mh * 0.8),
                          control: Pt(mx + mw * 1.2, my - mh * 0.25))
        hill.addLine(to: Pt(hillRight, my + mh * 1.5))
        hill.addLine(to: Pt(mx - mw * 2.4, my + mh * 1.5))
        hill.closeSubpath()
        ctx.fill(hill, with: .color(hillColor))

        // Cast shadow — light from upper-right, falls left
        var sh = Path()
        sh.move(to: Pt(mx - mw * 1.4, my + mh))
        sh.addLine(to: Pt(mx + mw * 0.8, my + mh))
        sh.addLine(to: Pt(mx - mw * 2.2, my + mh * 1.4))
        sh.closeSubpath()
        ctx.opacity = 0.25
        ctx.fill(sh, with: .color(s.shadow))
        ctx.opacity = 1

        // Wall + roof colors
        let wallDay = CM.mix(CM.hex(0x9A98A0), s.skyHorizon, 0.30)
        let wallNight = CM.mix(CM.hex(0x2C2E3A), s.skyTop, 0.25)
        let wall = variant == .day ? wallDay : wallNight
        let roofDay = CM.hex(0x3A3542)
        let roofNight = CM.hex(0x1A1822)
        let roof = variant == .day ? roofDay : roofNight
        let mortar = CM.mix(wall, s.shadow, 0.35)

        // ── LEFT WING — flush against the main wing's left wall ──
        let lw = mw * 0.55, lh = mh * 0.65
        let lx = mx - mw / 2 - lw       // ends exactly at main wing's left edge
        let ly = my + mh - lh           // bottom aligned with main wing
        // masonry walls
        paintMasonry(&ctx, x: lx, y: ly, w: lw, h: lh, rng: &rng,
                     base: CM.scale(wall, 0.92), mortar: mortar)
        // roof
        var lRoof = Path()
        lRoof.move(to: Pt(lx - lw * 0.10, ly))
        lRoof.addLine(to: Pt(lx + lw / 2, ly - lh * 0.55))
        lRoof.addLine(to: Pt(lx + lw * 1.10, ly))
        lRoof.closeSubpath()
        ctx.fill(lRoof, with: .color(roof))
        // left wing windows (1 column, inset from the seam)
        paintManorWindows(&ctx, x: lx + lw * 0.15, y: ly + lh * 0.1,
                          w: lw * 0.7, h: lh * 0.8, cols: 2, floorH: lh * 0.45)

        // ── MAIN WING — grand central block ──
        let mainX = mx - mw / 2
        let mainY = my - mh * 0.25
        let mainH = mh * 1.25
        // masonry walls
        paintMasonry(&ctx, x: mainX, y: mainY, w: mw, h: mainH, rng: &rng,
                     base: wall, mortar: mortar)
        // gothic roof (very steep)
        var mRoof = Path()
        mRoof.move(to: Pt(mx - mw * 0.60, mainY))
        mRoof.addLine(to: Pt(mx, mainY - mh * 0.90))
        mRoof.addLine(to: Pt(mx + mw * 0.60, mainY))
        mRoof.closeSubpath()
        ctx.fill(mRoof, with: .color(roof))
        ctx.stroke(mRoof, with: .color(CM.mix(roof, palette.ink, 0.12)),
                   style: StrokeStyle(lineWidth: 1, lineJoin: .round))
        // main wing windows — 3 floors × 3 columns, INSET from both edges
        // so no window touches the wing seams
        let winInset = mw * 0.12
        paintManorWindows(&ctx, x: mainX + winInset, y: mainY + mh * 0.10,
                          w: mw - winInset * 2, h: mainH * 0.75,
                          cols: 3, floorH: mh * 0.36)

        // ── RIGHT WING — flush against the main wing's right wall ──
        let rw = mw * 0.40, rh = mh * 0.50
        let rx = mx + mw / 2             // starts exactly at main wing's right edge
        let ry = my + mh - rh             // bottom aligned
        // masonry walls
        paintMasonry(&ctx, x: rx, y: ry, w: rw, h: rh, rng: &rng,
                     base: CM.scale(wall, 0.88), mortar: mortar)
        // roof
        var rRoof = Path()
        rRoof.move(to: Pt(rx - rw * 0.10, ry))
        rRoof.addLine(to: Pt(rx + rw / 2, ry - rh * 0.50))
        rRoof.addLine(to: Pt(rx + rw * 1.10, ry))
        rRoof.closeSubpath()
        ctx.fill(rRoof, with: .color(roof))
        // right wing windows (1 column, inset from the seam)
        paintManorWindows(&ctx, x: rx + rw * 0.15, y: ry + rh * 0.12,
                          w: rw * 0.7, h: rh * 0.75, cols: 1, floorH: rh * 0.45)

        // ── Wing seams — thin dark lines where wings join the main block ──
        ctx.opacity = 0.5
        ctx.stroke(Path(R(mx - mw / 2, ly, 1, mh)), with: .color(mortar), lineWidth: 1)
        ctx.stroke(Path(R(mx + mw / 2, ry, 1, rh)), with: .color(mortar), lineWidth: 1)
        ctx.opacity = 1

        // ── CENTRAL TOWER + SPIRE — rises from behind the main wing ──
        let tw = mw * 0.25, th = mh * 1.5
        let tx = mx + mw * 0.30
        // tower masonry
        paintMasonry(&ctx, x: tx, y: my - th, w: tw, h: th + mh * 0.3, rng: &rng,
                     base: CM.scale(wall, 0.95), mortar: mortar)
        // spire (pointed gothic)
        var spire = Path()
        spire.move(to: Pt(tx - tw * 0.65, my - th))
        spire.addLine(to: Pt(tx + tw / 2, my - th - mh * 0.70))
        spire.addLine(to: Pt(tx + tw * 1.65, my - th))
        spire.closeSubpath()
        ctx.fill(spire, with: .color(roof))
        // spire finial
        ctx.stroke(Path(R(tx + tw / 2, my - th - mh * 0.70, 1, mh * 0.10)),
                   with: .color(CM.mix(roof, palette.ink, 0.2)), lineWidth: 1.5)
        // tower windows (one per floor, arched, centered)
        for fy in 0..<3 {
            let wy = my - th + Double(fy) * th / 3.5 + th * 0.12
            paintArchedWindow(&ctx, x: tx + tw * 0.25, y: wy,
                              w: tw * 0.50, h: th * 0.13)
        }

        // ── CHIMNEYS — two gothic stacks with caps ──
        for (cx, cw, ch) in [(mx - mw * 0.28, mw * 0.055, mh * 0.50),
                              (mx + mw * 0.22, mw * 0.050, mh * 0.65)] {
            let cyy = my - mh * 0.25 - ch - mh * 0.35
            paintMasonry(&ctx, x: cx, y: cyy, w: cw, h: ch + mh * 0.35, rng: &rng,
                         base: CM.scale(wall, 0.70), mortar: mortar)
            // cap
            ctx.fill(Path(R(cx - cw * 0.25, cyy, cw * 1.5, mh * 0.045)), with: .color(roof))
            // smoke wisp (day only, night barely visible)
            if variant == .day {
                for i in 0..<3 {
                    let smokeX = cx + cw / 2 + Double(i) * 3
                    let smokeY = cyy - Double(i) * 5
                    let smokeR = 3.0 + Double(i) * 1.5
                    ctx.opacity = 0.07 - Double(i) * 0.018
                    ctx.fill(Path(ellipseIn: R(smokeX - smokeR, smokeY - smokeR,
                                               smokeR * 2, smokeR * 2)),
                             with: .color(palette.ink))
                }
                ctx.opacity = 1
            }
        }

        // ── ARCHED FRONT DOOR with cobblestone step ──
        let doorW = mw * 0.14, doorH = mh * 0.35
        let doorX = mx - doorW / 2, doorY = my + mh * 0.65
        // cobblestone step under the door
        ctx.fill(Path(R(doorX - doorW * 0.3, doorY + doorH, doorW * 1.6, mh * 0.06)),
                 with: .color(CM.mix(CM.hex(0x8A8890), s.shadow, 0.3)))
        var door = Path()
        door.move(to: Pt(doorX, doorY + doorH))
        door.addLine(to: Pt(doorX, doorY + doorH * 0.35))
        door.addQuadCurve(to: Pt(doorX + doorW, doorY + doorH * 0.35),
                          control: Pt(doorX + doorW / 2, doorY - doorH * 0.08))
        door.addLine(to: Pt(doorX + doorW, doorY + doorH))
        door.closeSubpath()
        ctx.fill(door, with: .color(CM.mix(roof, s.shadow, 0.3)))
        // door glow at night
        if variant == .night {
            ctx.opacity = 0.30
            ctx.fill(Path(ellipseIn: R(doorX - doorW * 0.4, doorY + doorH * 0.1,
                                        doorW * 1.8, doorH * 1.4)),
                     with: .color(palette.accent))
            ctx.opacity = 1
        }

        // ── Cobblestone path from the door to the trail end ──
        let pathStartX = doorX + doorW / 2
        let pathStartY = doorY + doorH + mh * 0.06
        var cobble = Path()
        cobble.move(to: Pt(pathStartX - mw * 0.12, pathStartY))
        cobble.addQuadCurve(to: Pt(pathStartX - mw * 0.08, pathStartY + mh * 0.35),
                            control: Pt(pathStartX - mw * 0.14, pathStartY + mh * 0.18))
        cobble.addQuadCurve(to: Pt(pathStartX - mw * 0.04, pathStartY + mh * 0.55),
                            control: Pt(pathStartX - mw * 0.02, pathStartY + mh * 0.45))
        cobble.addLine(to: Pt(pathStartX + mw * 0.10, pathStartY + mh * 0.55))
        cobble.addQuadCurve(to: Pt(pathStartX + mw * 0.14, pathStartY + mh * 0.35),
                            control: Pt(pathStartX + mw * 0.10, pathStartY + mh * 0.45))
        cobble.addQuadCurve(to: Pt(pathStartX + mw * 0.12, pathStartY),
                            control: Pt(pathStartX + mw * 0.16, pathStartY + mh * 0.18))
        cobble.closeSubpath()
        let cobbleColor = CM.mix(CM.hex(0x7A7880), s.ground, 0.35)
        ctx.opacity = 0.75
        ctx.fill(cobble, with: .color(cobbleColor))
        // individual cobblestones on the path
        for _ in 0..<12 {
            let px = pathStartX + rng.range(-mw * 0.10, mw * 0.10)
            let py = pathStartY + rng.range(0, mh * 0.5)
            let pr = rng.range(1.0, 2.2)
            ctx.opacity = 0.4
            ctx.fill(Path(ellipseIn: R(px, py, pr, pr * 0.7)),
                     with: .color(CM.scale(cobbleColor, rng.range(0.8, 1.2))))
        }
        ctx.opacity = 1
    }

    /// Cobblestone masonry — horizontal ashlar courses with mortar lines
    /// and per-block tone variation. Gives the manor walls actual stone
    /// texture instead of flat color.
    private func paintMasonry(_ ctx: inout GraphicsContext,
                               x: Double, y: Double, w: Double, h: Double,
                               rng: inout SplitMix64,
                               base: Color, mortar: Color) {
        // Base fill
        ctx.fill(Path(R(x, y, w, h)), with: .color(base))

        // Courses — gothic ashlar: regularly cut blocks in horizontal bands
        let courseH = max(2.5, h * 0.09)
        let rows = max(1, Int(h / courseH))
        let blockW = courseH * 1.8

        for row in 0..<rows {
            let ry = y + Double(row) * courseH
            let rowBottom = min(ry + courseH, y + h)

            // Mortar line between courses
            if row < rows - 1 {
                ctx.opacity = 0.35
                ctx.fill(Path(R(x, rowBottom - 0.6, w, 0.6)), with: .color(mortar))
                ctx.opacity = 1
            }

            // Blocks — running bond (every other row offset by half a block)
            let offset = row % 2 == 0 ? 0.0 : blockW * 0.5
            var bx = x - offset
            while bx < x + w {
                let bw = min(blockW * rng.range(0.8, 1.2), x + w - bx)
                if bw <= 0 { break }
                // Clip to the wall bounds
                let clipX = max(x, bx)
                let clipW = min(bx + bw, x + w) - clipX
                if clipW > 0 && rng.chance(0.7) {
                    // Per-block tone variation — some lighter, some darker
                    let shade = rng.range(-0.06, 0.05)
                    let blockColor = shade >= 0
                        ? CM.mix(base, palette.ink, shade)
                        : CM.mix(base, s.shadow, -shade)
                    ctx.opacity = 0.6
                    ctx.fill(Path(R(clipX, ry, clipW, rowBottom - ry - 0.4)),
                             with: .color(blockColor))
                    ctx.opacity = 1
                }
                bx += bw
            }
        }
    }

    /// A grid of gothic-arched windows for a manor wing.
    private func paintManorWindows(_ ctx: inout GraphicsContext,
                                   x: Double, y: Double, w: Double, h: Double,
                                   cols: Int, floorH: Double) {
        let winW = w / Double(cols) * 0.5
        let gap = w / Double(cols)
        for floor in 0..<max(1, Int(h / floorH)) {
            let fy = y + Double(floor) * floorH + floorH * 0.2
            for c in 0..<cols {
                let wx = x + Double(c) * gap + gap * 0.25
                paintArchedWindow(&ctx, x: wx, y: fy, w: winW, h: floorH * 0.45)
            }
        }
    }

    /// One gothic-arched window — warm amber at night, glass at day.
    private func paintArchedWindow(_ ctx: inout GraphicsContext,
                                    x: Double, y: Double, w: Double, h: Double) {
        var win = Path()
        win.move(to: Pt(x, y + h))
        win.addLine(to: Pt(x, y + h * 0.35))
        win.addQuadCurve(to: Pt(x + w, y + h * 0.35),
                         control: Pt(x + w / 2, y - h * 0.15))
        win.addLine(to: Pt(x + w, y + h))
        win.closeSubpath()
        let lit = variant == .night
        let glass = lit
            ? CM.mix(palette.accent, palette.ink, 0.25)
            : CM.mix(s.skyHorizon, palette.ink, 0.45)
        ctx.fill(win, with: .color(glass))
        if lit {
            // Glow halo around each lit window
            ctx.opacity = 0.12
            ctx.fill(Path(ellipseIn: R(x - w * 0.5, y - h * 0.3, w * 2, h * 2)),
                     with: .color(palette.accent))
            ctx.opacity = 1
        }
    }

    private func trail(_ ctx: inout GraphicsContext, _ rng: inout SplitMix64) {
        // A winding dirt path from the bottom edge up to the manor's front
        // gate — the manor sits on the LEFT bank hill, so the trail
        // approaches from the bottom-left, curving through the meadow.
        let startX = w * 0.34, startY = h * 1.03
        let endX = w * 0.27, endY = horizonY * 1.22

        var path = Path()
        path.move(to: Pt(startX, startY))
        path.addQuadCurve(to: Pt(w * 0.25, h * 0.88),
                          control: Pt(w * 0.30, h * 0.96))
        path.addQuadCurve(to: Pt(w * 0.32, h * 0.80),
                          control: Pt(w * 0.21, h * 0.83))
        path.addQuadCurve(to: Pt(endX, endY),
                          control: Pt(w * 0.29, h * 0.74))

        let trailColor = CM.mix(s.ground, palette.ink, 0.30)
        ctx.opacity = 0.55
        ctx.stroke(path, with: .color(trailColor),
                   style: StrokeStyle(lineWidth: max(3, w * 0.004),
                                      lineCap: .round, lineJoin: .round))
        ctx.opacity = 1

        // Trail stones scattered near the path
        for _ in 0..<10 {
            let t = rng.unit()
            let sx = startX + (endX - startX) * t + rng.range(-w * 0.02, w * 0.02)
            let sy = startY + (endY - startY) * t + rng.range(-h * 0.01, h * 0.01)
            let stoneR = rng.range(1, 2.6)
            ctx.opacity = 0.35
            ctx.fill(Path(ellipseIn: R(sx, sy, stoneR, stoneR * 0.7)),
                     with: .color(CM.mix(trailColor, s.shadow, 0.5)))
        }
        ctx.opacity = 1
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
