import SwiftUI

/// The launcher's theme set. Every theme ships a NIGHT and a DAY variant —
/// the painter swaps the sky, the celestial body, light direction and the
/// atmosphere while keeping the same topography (same seeds, same ridges).
/// Themes are forest/summit family by default; warm colorways stay for
/// the people who liked them.

enum SceneVariant: String, Codable {
    case night, day

    /// Resolve "auto" from the host clock (7:00–19:00 = day).
    static func resolve(_ raw: String?, hour: Int = Calendar.current.component(.hour, from: Date())) -> SceneVariant {
        switch raw {
        case "night": return .night
        case "day": return .day
        default: return (hour >= 7 && hour < 19) ? .day : .night
        }
    }
}

struct ThemePalette {
    let key: String
    let title: String

    // Accent & ink (shared across variants within a theme)
    let accent: Color
    let accentPair: Color
    let ink: Color            // warm off-white star/moon tone

    // Scene tones per variant
    let night: SceneColors
    let day: SceneColors

    // Chrome (window furniture) per variant
    let nightChrome: ChromeColors
    let dayChrome: ChromeColors

    func scene(_ variant: SceneVariant) -> SceneColors { variant == .day ? day : night }
    func chrome(_ variant: SceneVariant) -> ChromeColors { variant == .day ? dayChrome : nightChrome }

    struct SceneColors {
        let skyTop: Color
        let skyHorizon: Color
        let ridgeFar: Color
        let ridgeMid: Color
        let ridgeNear: Color
        let lakeDeep: Color
        let lakeGlint: Color
        let ground: Color
        let foliage: Color
        let shadow: Color
    }

    struct ChromeColors {
        let chromeDeep: Color
        let chromeRaised: Color
        let cardFill: Color
        let cardFill2: Color
        let separator: Color
        let textPrimary: Color
        let textMuted: Color
        let textFaint: Color
    }

    static func hex(_ value: UInt32) -> Color {
        Color(.sRGB,
              red: Double((value >> 16) & 0xFF) / 255.0,
              green: Double((value >> 8) & 0xFF) / 255.0,
              blue: Double(value & 0xFF) / 255.0,
              opacity: 1.0)
    }

    // MARK: - Theme catalog

    static let pine = ThemePalette(
        key: "pine", title: "Pinewood",
        accent: hex(0x8FBC6F), accentPair: hex(0xC9E3A8), ink: hex(0xF5F3E7),
        night: SceneColors(
            skyTop: hex(0x07120D), skyHorizon: hex(0x23402F),
            ridgeFar: hex(0x2E5240), ridgeMid: hex(0x1B352A), ridgeNear: hex(0x0F2018),
            lakeDeep: hex(0x0A1C16), lakeGlint: hex(0xBFE6C9),
            ground: hex(0x0A150F), foliage: hex(0x143424), shadow: hex(0x060B08)),
        day: SceneColors(
            skyTop: hex(0x5E93B8), skyHorizon: hex(0xE4D9B4),
            ridgeFar: hex(0x6E8F77), ridgeMid: hex(0x476B52), ridgeNear: hex(0x2C4A34),
            lakeDeep: hex(0x3E6E7E), lakeGlint: hex(0xFFF3D0),
            ground: hex(0x24402C), foliage: hex(0x356043), shadow: hex(0x1B2E20)),
        nightChrome: ChromeColors(
            chromeDeep: hex(0x0A0D0C), chromeRaised: hex(0x121615),
            cardFill: hex(0x15251E), cardFill2: hex(0x1C2E26),
            separator: hex(0x333B38),
            textPrimary: hex(0xF0EDE2), textMuted: hex(0x98A098), textFaint: hex(0x636B64)),
        dayChrome: ChromeColors(
            chromeDeep: hex(0x2A231C), chromeRaised: hex(0x3A3128),
            cardFill: hex(0x31291F), cardFill2: hex(0x3A3126),
            separator: hex(0x4A4438),
            textPrimary: hex(0xFFF8EA), textMuted: hex(0xC9BCA3), textFaint: hex(0x948874))
    )

    static let summit = ThemePalette(
        key: "summit", title: "Summit",
        accent: hex(0xE8A25E), accentPair: hex(0xE8C07D), ink: hex(0xF5F3E7),
        night: SceneColors(
            skyTop: hex(0x0C1318), skyHorizon: hex(0x2A4157),
            ridgeFar: hex(0x2F4E6B), ridgeMid: hex(0x20364B), ridgeNear: hex(0x132030),
            lakeDeep: hex(0x0F1E2B), lakeGlint: hex(0xCBE4F0),
            ground: hex(0x0C1620), foliage: hex(0x17324A), shadow: hex(0x070D12)),
        day: SceneColors(
            skyTop: hex(0x6FA3D8), skyHorizon: hex(0xF2D8A8),
            ridgeFar: hex(0x7C93A8), ridgeMid: hex(0x57708A), ridgeNear: hex(0x394F63),
            lakeDeep: hex(0x4E7E96), lakeGlint: hex(0xFFF6DB),
            ground: hex(0x2E4250), foliage: hex(0x42607A), shadow: hex(0x22313C)),
        nightChrome: pine.nightChrome,
        dayChrome: pine.dayChrome
    )

    static let dusk = ThemePalette(
        key: "dusk", title: "Duskwood",
        accent: hex(0xB583E8), accentPair: hex(0xE8A2C0), ink: hex(0xF5F3E7),
        night: SceneColors(
            skyTop: hex(0x120E1A), skyHorizon: hex(0x33204A),
            ridgeFar: hex(0x3D2A57), ridgeMid: hex(0x291E3D), ridgeNear: hex(0x1A1326),
            lakeDeep: hex(0x171023), lakeGlint: hex(0xE4D2F2),
            ground: hex(0x120C1C), foliage: hex(0x241836), shadow: hex(0x090609)),
        day: SceneColors(
            skyTop: hex(0x8E7FC8), skyHorizon: hex(0xF0CDB4),
            ridgeFar: hex(0x8A7FB0), ridgeMid: hex(0x645A8E), ridgeNear: hex(0x453D66),
            lakeDeep: hex(0x6A5E92), lakeGlint: hex(0xFFF0DC),
            ground: hex(0x352D4E), foliage: hex(0x4D4272), shadow: hex(0x282236)),
        nightChrome: ChromeColors(
            chromeDeep: hex(0x0E0A15), chromeRaised: hex(0x161020),
            cardFill: hex(0x1C1528), cardFill2: hex(0x241B33),
            separator: hex(0x3A3045),
            textPrimary: hex(0xF3EFFA), textMuted: hex(0xA39AB0), textFaint: hex(0x6A6078)),
        dayChrome: pine.dayChrome
    )

    static let glacier = ThemePalette(
        key: "glacier", title: "Glacier",
        accent: hex(0x7FD1E8), accentPair: hex(0xBFA2E8), ink: hex(0xF5F3E7),
        night: SceneColors(
            skyTop: hex(0x0A141C), skyHorizon: hex(0x1E3550),
            ridgeFar: hex(0x29445F), ridgeMid: hex(0x1A2E42), ridgeNear: hex(0x101E2B),
            lakeDeep: hex(0x0D1B27), lakeGlint: hex(0xD4ECF4),
            ground: hex(0x0A141C), foliage: hex(0x153045), shadow: hex(0x060D12)),
        day: SceneColors(
            skyTop: hex(0x7FB4DC), skyHorizon: hex(0xEDEBD8),
            ridgeFar: hex(0x8AA4BC), ridgeMid: hex(0x5F7A96), ridgeNear: hex(0x3D556B),
            lakeDeep: hex(0x54849C), lakeGlint: hex(0xFFFCE8),
            ground: hex(0x30465A), foliage: hex(0x47687F), shadow: hex(0x223242)),
        nightChrome: ChromeColors(
            chromeDeep: hex(0x0A0F14), chromeRaised: hex(0x111820),
            cardFill: hex(0x14202B), cardFill2: hex(0x1A2835),
            separator: hex(0x32404C),
            textPrimary: hex(0xEDF3F8), textMuted: hex(0x98A8B4), textFaint: hex(0x5E6E7A)),
        dayChrome: pine.dayChrome
    )

    static let ember = ThemePalette(
        key: "ember", title: "Emberwild",
        accent: hex(0xE8734C), accentPair: hex(0xE8B25E), ink: hex(0xF5F3E7),
        night: SceneColors(
            skyTop: hex(0x161010), skyHorizon: hex(0x3B1F18),
            ridgeFar: hex(0x4A2A1F), ridgeMid: hex(0x2E1D16), ridgeNear: hex(0x1C1310),
            lakeDeep: hex(0x170F0C), lakeGlint: hex(0xF2D4BC),
            ground: hex(0x140D0A), foliage: hex(0x241714), shadow: hex(0x0A0605)),
        day: SceneColors(
            skyTop: hex(0xB87F5E), skyHorizon: hex(0xF2D3A8),
            ridgeFar: hex(0xA87F66), ridgeMid: hex(0x7A5644), ridgeNear: hex(0x563C30),
            lakeDeep: hex(0x8A6A56), lakeGlint: hex(0xFFF3DC),
            ground: hex(0x443126), foliage: hex(0x5E463A), shadow: hex(0x30221C)),
        nightChrome: ChromeColors(
            chromeDeep: hex(0x110D0B), chromeRaised: hex(0x1A1512),
            cardFill: hex(0x1F1713), cardFill2: hex(0x281E18),
            separator: hex(0x453A31),
            textPrimary: hex(0xF8F0E6), textMuted: hex(0xAD9E8E), textFaint: hex(0x71645A)),
        dayChrome: pine.dayChrome
    )

    static let all: [ThemePalette] = [pine, taiga, summit, dusk, glacier, ember]

    static func forKey(_ key: String?) -> ThemePalette {
        all.first(where: { $0.key == key }) ?? pine
    }

    // ── Snowy Taiga — the user's requested winter variant ─────────────────
    // A frozen river instead of liquid water, snow-dusted conifers, and a
    // colder blue-grey palette that still feels like "home."

    static let taiga = ThemePalette(
        key: "taiga", title: "Snowy Taiga",
        accent: hex(0xA8C8DC), accentPair: hex(0xD8E8F0), ink: hex(0xF5F8FA),
        night: SceneColors(
            skyTop: hex(0x0A1118), skyHorizon: hex(0x2A4258),
            ridgeFar: hex(0x3A5A78), ridgeMid: hex(0x28405A), ridgeNear: hex(0x182838),
            lakeDeep: hex(0x142830), lakeGlint: hex(0xB0D8E8),
            ground: hex(0x1A2830), foliage: hex(0x223C48), shadow: hex(0x0A1218)),
        day: SceneColors(
            skyTop: hex(0x88B4D8), skyHorizon: hex(0xE0ECE8),
            ridgeFar: hex(0x88A0B4), ridgeMid: hex(0x607C94), ridgeNear: hex(0x405870),
            lakeDeep: hex(0x507890), lakeGlint: hex(0xF0F8FC),
            ground: hex(0x384C58), foliage: hex(0x486474), shadow: hex(0x283848)),
        nightChrome: ChromeColors(
            chromeDeep: hex(0x0A1014), chromeRaised: hex(0x141C22),
            cardFill: hex(0x182430), cardFill2: hex(0x20303C),
            separator: hex(0x384850),
            textPrimary: hex(0xE8F0F4), textMuted: hex(0x90A4B0), textFaint: hex(0x5A6E7A)),
        dayChrome: ChromeColors(
            chromeDeep: hex(0x1E2A34), chromeRaised: hex(0x2A3844),
            cardFill: hex(0x243440), cardFill2: hex(0x2C3C48),
            separator: hex(0x445462),
            textPrimary: hex(0xF0F4F8), textMuted: hex(0xB0C0CC), textFaint: hex(0x80909C))
    )
}
