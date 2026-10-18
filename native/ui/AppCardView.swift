import SwiftUI
import AppKit

// ─────────────────────────────────────────────────────────────────────────────
// LAUNCHER CARD — each card is a small work of art: ornamental frame with
// vine flourishes at the corners, a category emblem medallion, the app's
// icon, a nameplate with tracking, stats line, status badge, and action
// buttons. Cards have spring hover physics (scale + shadow deepen) and
// press feedback — all SwiftUI event-driven animations, zero idle cost.
// ─────────────────────────────────────────────────────────────────────────────

struct AppCardView: View {
    let card: CardModel
    let palette: ThemePalette
    let variant: SceneVariant
    let onAction: (String, String) -> Void  // (action, appId)

    @State private var hovered = false
    @State private var showResources = false  // flash → fade

    private var chrome: ThemePalette.ChromeColors { palette.chrome(variant) }
    private var accentR: Double { Double(card.accentRed) / 255.0 }
    private var accentG: Double { Double(card.accentGreen) / 255.0 }
    private var accentB: Double { Double(card.accentBlue) / 255.0 }
    private var accent: Color {
        Color(.sRGB, red: accentR, green: accentG, blue: accentB, opacity: 1.0)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header: emblem medallion + name + badge + resource flash
            HStack(alignment: .top, spacing: 10) {
                // Icon or category emblem
                iconMedallion
                    .frame(width: 52, height: 52)

                VStack(alignment: .leading, spacing: 2) {
                    Text(card.name)
                        .font(.system(size: 14, weight: .semibold, design: .rounded))
                        .foregroundColor(chrome.textPrimary)
                        .lineLimit(1)
                    Text(card.category.uppercased())
                        .font(.system(size: 9, weight: .bold, design: .rounded))
                        .tracking(1.2)
                        .foregroundColor(accent.opacity(0.8))
                    if showResources {
                        // Resource flash: RAM + CPU briefly shown, then fades
                        HStack(spacing: 6) {
                            if let cpu = card.cpuPercent, cpu > 0.1 {
                                Text(String(format: "CPU %.1f%%", cpu))
                                    .font(.system(size: 9, weight: .bold, design: .monospaced))
                                    .foregroundColor(.orange)
                            }
                            if let mem = card.memMB, mem > 10 {
                                Text(String(format: "%.0f MB", mem))
                                    .font(.system(size: 9, weight: .bold, design: .monospaced))
                                    .foregroundColor(.blue)
                            }
                        }
                        .transition(.opacity.combined(with: .move(edge: .top)))
                    } else if card.state == .running, let mins = card.sessionMinutes, mins > 0 {
                        // Session timer on running cards
                        Text(formatSession(mins))
                            .font(.system(size: 10, weight: .medium, design: .rounded))
                            .foregroundColor(.green.opacity(0.8))
                            .lineLimit(1)
                    } else {
                        Text(card.statsLine)
                            .font(.system(size: 10, weight: .regular, design: .rounded))
                            .foregroundColor(chrome.textMuted)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)

                VStack(alignment: .trailing, spacing: 4) {
                    statusBadge
                    // Resource flash button — tap to show RAM/CPU briefly
                    if card.state == .running {
                        Button {
                            flashResources()
                        } label: {
                            Image(systemName: "waveform.path.ecg")
                                .font(.system(size: 11))
                                .foregroundColor(accent.opacity(0.7))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, 14)
            .padding(.top, 14)

            // Summary
            if !card.summary.isEmpty {
                Text(card.summary)
                    .font(.system(size: 11, weight: .regular, design: .rounded))
                    .foregroundColor(chrome.textMuted)
                    .lineLimit(2)
                    .padding(.horizontal, 14)
                    .padding(.top, 6)
            }

            // Status message + progress
            if let msg = card.statusMessage, !msg.isEmpty {
                Text(msg)
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .foregroundColor(card.state == .failed ? Color.red.opacity(0.8) : chrome.textMuted)
                    .lineLimit(1)
                    .padding(.horizontal, 14)
                    .padding(.top, 4)
            }

            // Live resource stats (only when running)
            if card.state == .running {
                if let cpu = card.cpuPercent, cpu > 0.1 {
                    HStack(spacing: 4) {
                        Image(systemName: "cpu")
                            .font(.system(size: 8))
                            .foregroundColor(.orange)
                        Text(String(format: "%.1f%%", cpu))
                            .font(.system(size: 9, weight: .medium, design: .monospaced))
                            .foregroundColor(.orange.opacity(0.9))
                        if let mem = card.memMB, mem > 10 {
                            Image(systemName: "memorychip")
                                .font(.system(size: 8))
                                .foregroundColor(.blue)
                            Text(String(format: "%.0f MB", mem))
                                .font(.system(size: 9, weight: .medium, design: .monospaced))
                                .foregroundColor(.blue.opacity(0.9))
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.top, 3)
                }
            }
            if let progress = card.progress {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        RoundedRectangle(cornerRadius: 2)
                            .fill(chrome.separator.opacity(0.5))
                            .frame(height: 4)
                        RoundedRectangle(cornerRadius: 2)
                            .fill(accent)
                            .frame(width: geo.size.width * progress, height: 4)
                    }
                }
                .frame(height: 4)
                .padding(.horizontal, 14)
                .padding(.top, 4)
            }

            Spacer(minLength: 8)

            // Action buttons
            HStack(spacing: 6) {
                actionButton(card.state == .running ? "Stop" : "Launch",
                             isPrimary: true, action: card.state == .running ? "quit" : "launch")
                if card.state == .updateAvailable {
                    actionButton("Update", isPrimary: false, action: "update")
                }
                if !card.installed {
                    actionButton("Install", isPrimary: false, action: "install")
                }
                actionButton("Reveal", isPrimary: false, action: "reveal")
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 12)
        }
        .frame(minWidth: 240, alignment: .leading)
        .background(
            // Ornate card surface: gradient fill + corner vines
            ZStack {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(LinearGradient(
                        stops: [
                            .init(color: chrome.cardFill, location: 0),
                            .init(color: chrome.cardFill2, location: 1),
                        ],
                        startPoint: .topLeading, endPoint: .bottomTrailing))
                // Corner vine ornaments
                CornerVines(accent: accent, size: 18)
            }
        )
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(
                    LinearGradient(stops: [
                        .init(color: accent.opacity(0.3), location: 0),
                        .init(color: accent.opacity(0.08), location: 0.5),
                        .init(color: accent.opacity(0.15), location: 1),
                    ], startPoint: .topLeading, endPoint: .bottomTrailing),
                    lineWidth: 1)
        )
        .shadow(color: hovered ? accent.opacity(0.15) : Color.black.opacity(0.3),
                radius: hovered ? 12 : 6, x: 0, y: hovered ? 4 : 2)
        .scaleEffect(hovered ? 1.03 : 1.0)
        .animation(.spring(response: 0.3, dampingFraction: 0.7), value: hovered)
        .onHover { hovering in
            hovered = hovering
        }
        .onTapGesture {
            // Card click = launch (or stop if running)
            onAction(card.state == .running ? "quit" : "launch", card.id)
        }
        .contextMenu {
            cardContextMenu
        }
    }

    // MARK: - Context menu

    @ViewBuilder
    private var cardContextMenu: some View {
        Button("Launch") { onAction("launch", card.id) }
        if card.state == .running {
            Button("Quit") { onAction("quit", card.id) }
        }
        Divider()
        if card.state == .updateAvailable {
            Button("Update Now") { onAction("update", card.id) }
        }
        Button("Check for Update") { onAction("checkOne", card.id) }
        if !card.installed {
            Button("Install…") { onAction("install", card.id) }
        }
        Divider()
        Button("Reveal in Finder") { onAction("reveal", card.id) }
        Button("Change Icon…") {
            // Open the native file picker. On macOS, .app bundles are
            // PACKAGES (files), not plain directories — setting only
            // canChooseDirectories=true makes every .app in
            // /Applications greyed out and unselectable. We allow both
            // files and directories so .app bundles are clickable.
            let panel = NSOpenPanel()
            panel.canChooseDirectories = true
            panel.canChooseFiles = true
            panel.allowsMultipleSelection = false
            panel.directoryURL = URL(fileURLWithPath: "/Applications")
            panel.message = "Choose the app whose icon \(card.name) should use"
            panel.begin { response in
                guard response == .OK, let url = panel.url else { return }
                onAction("setAppIcon", "\(card.id)|\(url.path)")
            }
        }
        Divider()
        Button("Copy Name") {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(card.name, forType: .string)
        }
        Divider()
        Button("Delete \(card.name)…", role: .destructive) {
            let alert = NSAlert()
            alert.messageText = "Delete \(card.name)?"
            alert.informativeText = "This removes \(card.name) from your library. The app's files on disk are not deleted."
            alert.addButton(withTitle: "Remove Card")
            alert.addButton(withTitle: "Cancel")
            alert.alertStyle = .warning
            if alert.runModal() == .alertFirstButtonReturn {
                onAction("deleteApp", card.id)
            }
        }
    }

    // MARK: - Resource flash

    private func flashResources() {
        withAnimation(.easeIn(duration: 0.2)) {
            showResources = true
        }
        // Auto-fade after 3 seconds
        DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) {
            withAnimation(.easeOut(duration: 0.8)) {
                showResources = false
            }
        }
    }

    private func formatSession(_ minutes: Double) -> String {
        let totalMin = Int(minutes)
        if totalMin < 60 {
            return "Playing for \(totalMin)m"
        }
        let hours = totalMin / 60
        let mins = totalMin % 60
        if hours < 10 {
            return "Playing for \(hours)h \(mins)m"
        }
        return "Playing for \(hours)h"
    }

    // MARK: - Icon medallion

    @ViewBuilder
    private var iconMedallion: some View {
        ZStack {
            Circle()
                .fill(LinearGradient(stops: [
                    .init(color: chrome.cardFill2, location: 0),
                    .init(color: chrome.cardFill, location: 1),
                ], startPoint: .top, endPoint: .bottom))
            Circle()
                .strokeBorder(accent.opacity(0.35), lineWidth: 1)
            if let iconPath = card.iconPath,
               let nsImage = IconCache.shared.image(for: iconPath) {
                Image(nsImage: nsImage)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .clipShape(Circle())
                    .padding(4)
            } else {
                CategoryEmblem(category: card.category, accent: accent)
                    .padding(8)
            }
        }
    }

    // MARK: - Status badge

    @ViewBuilder
    private var statusBadge: some View {
        let (text, color): (String, Color) = {
            switch card.state {
            case .running: return ("LIVE", .green)
            case .checking: return ("•••", .blue)
            case .downloading, .installing: return ("↓", .blue)
            case .updateAvailable: return ("UPDATE", .orange)
            case .failed: return ("ERROR", .red)
            case .installed: return ("READY", .green)
            case .missing: return ("NOT FOUND", .gray)
            }
        }()
        Text(text)
            .font(.system(size: 8, weight: .heavy, design: .rounded))
            .tracking(0.5)
            .foregroundColor(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(
                Capsule()
                    .fill(color.opacity(0.12))
                    .overlay(Capsule().strokeBorder(color.opacity(0.4), lineWidth: 0.5))
            )
    }

    // MARK: - Action button

    private func actionButton(_ label: String, isPrimary: Bool, action: String) -> some View {
        Button {
            onAction(action, card.id)
        } label: {
            Text(label)
                .font(.system(size: 10, weight: isPrimary ? .bold : .medium, design: .rounded))
                .foregroundColor(isPrimary ? chrome.textPrimary : chrome.textMuted)
                .padding(.horizontal, 12)
                .padding(.vertical, 5)
                .background(
                    Capsule()
                        .fill(isPrimary ? AnyShapeStyle(accent.opacity(0.25))
                                        : AnyShapeStyle(Color.white.opacity(0.04)))
                        .overlay(
                            Capsule().strokeBorder(
                                isPrimary ? accent.opacity(0.4) : chrome.separator,
                                lineWidth: 0.8))
                )
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Corner Vine Ornaments

/// Decorative vine flourishes at each corner — the fantasy/forest detail
/// that makes each card feel hand-crafted rather than template-stamped.
struct CornerVines: View {
    let accent: Color
    let size: Double

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height

            // Top-left vine
            VineFlourish(accent: accent, rotation: .degrees(0))
                .frame(width: size, height: size)
                .position(x: 2, y: 2)

            // Top-right vine
            VineFlourish(accent: accent, rotation: .degrees(90))
                .frame(width: size, height: size)
                .position(x: w - 2, y: 2)

            // Bottom-right vine
            VineFlourish(accent: accent, rotation: .degrees(180))
                .frame(width: size, height: size)
                .position(x: w - 2, y: h - 2)

            // Bottom-left vine
            VineFlourish(accent: accent, rotation: .degrees(270))
                .frame(width: size, height: size)
                .position(x: 2, y: h - 2)
        }
        .allowsHitTesting(false)
    }
}

/// A single vine flourish: a curved stem with small leaves.
/// Rendered as a Shape for crisp vector quality at any scale.
struct VineFlourish: View {
    let accent: Color
    let rotation: Angle

    var body: some View {
        VineShape()
            .stroke(accent.opacity(0.25), style: StrokeStyle(lineWidth: 1, lineCap: .round))
            .rotationEffect(rotation)
            .padding(2)
    }
}

struct VineShape: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        let w = rect.width, h = rect.height

        // Main stem: a gentle curve from the corner toward center
        p.move(to: CGPoint(x: 0, y: h))
        p.addQuadCurve(to: CGPoint(x: w * 0.7, y: h * 0.3),
                       control: CGPoint(x: w * 0.1, y: h * 0.3))

        // Small leaves along the stem
        for (t, size) in [(0.3, 0.25), (0.5, 0.20), (0.7, 0.15)] {
            let lx = w * t * 0.7 + w * 0.05
            let ly = h * (1 - t) * 0.7
            var leaf = Path()
            leaf.move(to: CGPoint(x: lx, y: ly))
            leaf.addQuadCurve(to: CGPoint(x: lx + size * w, y: ly - size * w),
                             control: CGPoint(x: lx + size * w * 0.6, y: ly))
            leaf.addQuadCurve(to: CGPoint(x: lx, y: ly),
                             control: CGPoint(x: lx + size * w * 0.4, y: ly - size * w * 0.4))
            p.addPath(leaf)
        }
        return p
    }
}

// MARK: - Category Emblem (when no icon available)

/// Canvas-drawn category glyphs — the same vocabulary as the old Glass
/// painter, rebuilt in SwiftUI Shape for vector-crisp rendering.
struct CategoryEmblem: View {
    let category: String
    let accent: Color

    var body: some View {
        GeometryReader { geo in
            let s = min(geo.size.width, geo.size.height)
            let cx = geo.size.width / 2, cy = geo.size.height / 2

            Path { p in
                switch category.lowercased() {
                case "streaming":
                    // Play triangle
                    p.move(to: CGPoint(x: cx - s * 0.15, y: cy - s * 0.2))
                    p.addLine(to: CGPoint(x: cx + s * 0.2, y: cy))
                    p.addLine(to: CGPoint(x: cx - s * 0.15, y: cy + s * 0.2))
                    p.closeSubpath()
                case "remote-play":
                    // Radio waves
                    for i in 1...3 {
                        let r = s * 0.08 * Double(i)
                        p.addArc(center: CGPoint(x: cx - s * 0.15, y: cy),
                                 radius: r, startAngle: .degrees(-40), endAngle: .degrees(40),
                                 clockwise: false)
                    }
                    p.addEllipse(in: CGRect(x: cx - s * 0.20, y: cy - s * 0.03,
                                            width: s * 0.06, height: s * 0.06))
                case "browser":
                    // Globe
                    p.addEllipse(in: CGRect(x: cx - s * 0.18, y: cy - s * 0.18,
                                            width: s * 0.36, height: s * 0.36))
                    p.move(to: CGPoint(x: cx - s * 0.18, y: cy))
                    p.addLine(to: CGPoint(x: cx + s * 0.18, y: cy))
                    p.move(to: CGPoint(x: cx, y: cy - s * 0.18))
                    p.addLine(to: CGPoint(x: cx, y: cy + s * 0.18))
                case "minecraft":
                    // Cube (isometric)
                    let hs = s * 0.18
                    p.move(to: CGPoint(x: cx, y: cy - hs))
                    p.addLine(to: CGPoint(x: cx + hs, y: cy - hs * 0.5))
                    p.addLine(to: CGPoint(x: cx + hs, y: cy + hs * 0.5))
                    p.addLine(to: CGPoint(x: cx, y: cy + hs))
                    p.addLine(to: CGPoint(x: cx - hs, y: cy + hs * 0.5))
                    p.addLine(to: CGPoint(x: cx - hs, y: cy - hs * 0.5))
                    p.closeSubpath()
                case "emulator", "games":
                    // Gamepad silhouette
                    let gw = s * 0.32, gh = s * 0.18
                    let r = gh / 3
                    p.addRoundedRect(in: CGRect(x: cx - gw / 2, y: cy - gh / 2,
                                                width: gw, height: gh),
                                    cornerSize: CGSize(width: r, height: r))
                case "development":
                    // Braces < >
                    p.move(to: CGPoint(x: cx - s * 0.12, y: cy - s * 0.1))
                    p.addLine(to: CGPoint(x: cx - s * 0.2, y: cy))
                    p.addLine(to: CGPoint(x: cx - s * 0.12, y: cy + s * 0.1))
                    p.move(to: CGPoint(x: cx + s * 0.12, y: cy - s * 0.1))
                    p.addLine(to: CGPoint(x: cx + s * 0.2, y: cy))
                    p.addLine(to: CGPoint(x: cx + s * 0.12, y: cy + s * 0.1))
                default:
                    // Grid dots
                    for dx in [-1, 0, 1] {
                        for dy in [-1, 0, 1] {
                            p.addEllipse(in: CGRect(x: cx + Double(dx) * s * 0.12 - s * 0.03,
                                                    y: cy + Double(dy) * s * 0.12 - s * 0.03,
                                                    width: s * 0.06, height: s * 0.06))
                        }
                    }
                }
            }
            .stroke(accent.opacity(0.7), style: StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round))
        }
    }
}

// MARK: - Icon Cache (avoid disk reads on every card render)

/// NSImage cache keyed by file path. The old code called
/// `NSImage(contentsOfFile:)` on EVERY SwiftUI body evaluation — for 9 apps
/// with icons, that was 9 disk reads per state push (every 10s), making the
/// app feel sluggish and eating CPU for no reason.
final class IconCache {
    static let shared = IconCache()
    private var cache: [String: NSImage] = [:]
    private let lock = NSLock()

    func image(for path: String) -> NSImage? {
        lock.lock()
        defer { lock.unlock() }
        if let cached = cache[path] { return cached }
        guard let img = NSImage(contentsOfFile: path) else { return nil }
        cache[path] = img
        return img
    }

    func invalidate() {
        lock.lock()
        defer { lock.unlock() }
        cache.removeAll()
    }
}
