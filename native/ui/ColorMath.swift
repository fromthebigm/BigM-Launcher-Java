import SwiftUI
import AppKit

/// sRGB-accurate color blending helpers for the scene painter. The painter
/// blends themed colors *before* handing colors to the Canvas (SwiftUI's
/// `Color.mix` is colorSpace-dependent and muddy); here we resolve through
/// NSColor once per call — plenty fast for one static render per resize.
enum CM {

    /// Linear blend: f=0 → a, f=1 → b.
    static func mix(_ a: Color, _ b: Color, _ f: Double) -> Color {
        let ca = rgba(a), cb = rgba(b)
        return Color(.sRGB,
                     red: ca[0] + (cb[0] - ca[0]) * f,
                     green: ca[1] + (cb[1] - ca[1]) * f,
                     blue: ca[2] + (cb[2] - ca[2]) * f,
                     opacity: ca[3] + (cb[3] - ca[3]) * f)
    }

    /// Toward-black when f<1, toward-white (gently) when f>1.
    static func scale(_ c: Color, _ f: Double) -> Color {
        let v = rgba(c)
        if f <= 1 {
            return Color(.sRGB, red: v[0] * f, green: v[1] * f, blue: v[2] * f, opacity: v[3])
        }
        let lift = f - 1
        return Color(.sRGB,
                     red: v[0] + (1 - v[0]) * lift,
                     green: v[1] + (1 - v[1]) * lift,
                     blue: v[2] + (1 - v[2]) * lift,
                     opacity: v[3])
    }

    static func withOpacity(_ c: Color, _ o: Double) -> Color {
        let v = rgba(c)
        return Color(.sRGB, red: v[0], green: v[1], blue: v[2], opacity: o)
    }

    static func rgba(_ c: Color) -> [Double] {
        let ns = NSColor(c).usingColorSpace(.sRGB) ?? NSColor(c)
        return [Double(ns.redComponent), Double(ns.greenComponent),
                Double(ns.blueComponent), Double(ns.alphaComponent)]
    }
}

/// Deterministic scene RNG (SplitMix64) — same output for the same size
/// seed on every render, so the mountains don't drift between repaints.
struct SplitMix64: RandomNumberGenerator {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
    mutating func unit() -> Double { Double(next() >> 11) / Double(1 << 53) }
    mutating func range(_ lo: Double, _ hi: Double) -> Double { lo + (hi - lo) * unit() }
    mutating func chance(_ p: Double) -> Bool { unit() < p }
}

/// Value-noise field for ridges: hashed lattice + smoothstep, multi-octave.
enum ValueNoise {
    static func hash(_ i: Int64, _ seed: UInt64) -> Double {
        var x = UInt64(bitPattern: i) &* 374761393 &+ seed &* 668265263
        x = (x ^ (x >> 13)) &* 1274126177
        x = x ^ (x >> 16)
        return Double(x & 0xFFFFFF) / Double(0x1000000)
    }

    static func noise(_ t: Double, seed: UInt64, octaves: Int = 4) -> Double {
        var sum = 0.0, amp = 1.0, freq = 1.0, norm = 0.0
        for o in 0..<octaves {
            let tt = t * freq
            let i0 = Int64(floor(tt))
            let frac = tt - Double(i0)
            let s = frac * frac * (3 - 2 * frac)
            let a = hash(i0, seed &+ UInt64(o) &* 1013)
            let b = hash(i0 &+ 1, seed &+ UInt64(o) &* 1013)
            sum += (a + (b - a) * s) * amp
            norm += amp
            amp *= 0.5
            freq *= 2.03
        }
        return sum / norm
    }
}

// ── Geometry sugar (Double-space math, CGFloat at the boundary) ─────────────

@inline(__always) func Pt(_ x: Double, _ y: Double) -> CGPoint {
    CGPoint(x: CGFloat(x), y: CGFloat(y))
}
@inline(__always) func R(_ x: Double, _ y: Double, _ w: Double, _ h: Double) -> CGRect {
    CGRect(x: CGFloat(x), y: CGFloat(y), width: CGFloat(w), height: CGFloat(h))
}
