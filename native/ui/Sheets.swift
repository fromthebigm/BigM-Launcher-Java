import SwiftUI
import AppKit

// ─────────────────────────────────────────────────────────────────────────────
// SHEETS — every sidebar action opens a real, working sheet: Updates,
// Customize, Settings, Diagnostics, Get Apps. Folder pickers use NSOpenPanel
// (native, dark). All state flows through the same JSON bridge as the main
// window; actions go back through the same callback.
// ─────────────────────────────────────────────────────────────────────────────

enum SheetKind: String, Identifiable {
    case updates, customize, settings, diagnostics, getApps, system
    var id: String { rawValue }

    var title: String {
        switch self {
        case .updates: return "Updates"
        case .customize: return "Customize"
        case .settings: return "Settings"
        case .diagnostics: return "Diagnostics"
        case .getApps: return "Get Apps"
        case .system: return "System"
        }
    }
}

// MARK: - Shared sheet chrome

struct SheetScaffold<Content: View>: View {
    let title: String
    @EnvironmentObject var store: StateStore
    @Environment(\.dismiss) private var dismiss
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(title)
                    .font(.system(size: 20, weight: .bold, design: .rounded))
                    .foregroundColor(chrome.textPrimary)
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 18))
                        .foregroundColor(chrome.textMuted)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 22)
            .padding(.top, 18)
            .padding(.bottom, 10)

            Divider().overlay(chrome.separator.opacity(0.4))

            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    content
                }
                .padding(22)
            }
        }
        .frame(width: 620, height: 520)
        .background(chrome.chromeDeep)
    }

    private var chrome: ThemePalette.ChromeColors { store.palette.chrome(store.variant) }
}

// MARK: - Card row shared by sheets

struct SheetCard<Content: View>: View {
    let title: String
    let icon: String
    @EnvironmentObject var store: StateStore
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(store.palette.accent)
                Text(title)
                    .font(.system(size: 14, weight: .semibold, design: .rounded))
                    .foregroundColor(chrome.textPrimary)
            }
            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(chrome.cardFill.opacity(0.9))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(chrome.separator.opacity(0.5), lineWidth: 0.8))
        )
    }

    private var chrome: ThemePalette.ChromeColors { store.palette.chrome(store.variant) }
}

// MARK: - Updates Sheet

struct UpdatesSheet: View {
    @EnvironmentObject var store: StateStore

    var body: some View {
        SheetScaffold(title: "Updates") {
            // Manual refresh — one click re-checks the launcher + every app
            HStack {
                Text("Last check refreshes each time this sheet opens")
                    .font(.system(size: 10, design: .rounded))
                    .foregroundColor(chrome.textFaint)
                Spacer()
                Button {
                    store.dispatch("checkAll", id: "")
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 10, weight: .semibold))
                        Text("Check for Updates")
                            .font(.system(size: 11, weight: .semibold, design: .rounded))
                    }
                }
                .buttonStyle(PrimaryButtonStyle(accent: store.palette.accent))
            }

            if let snap = store.snapshot {
                if snap.selfUpdateAvailable {
                    SheetCard(title: "BigM \(snap.selfUpdateTag ?? "") is available",
                              icon: "arrow.down.circle.fill") {
                        Text("You're running \(snap.version)")
                            .font(.system(size: 11, design: .rounded))
                            .foregroundColor(chrome.textMuted)
                        if let msg = snap.selfUpdateMessage, !msg.isEmpty {
                            Text(msg)
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundColor(chrome.textMuted)
                        }
                        HStack {
                            Button("Install & Restart") { store.dispatch("installSelf", id: "") }
                                .buttonStyle(PrimaryButtonStyle(accent: store.palette.accent))
                        }
                    }
                }

                let pending = snap.cards.filter { $0.state == .updateAvailable }
                if !pending.isEmpty {
                    HStack {
                        Text("\(pending.count) app update\(pending.count == 1 ? "" : "s") available")
                            .font(.system(size: 12, weight: .medium, design: .rounded))
                            .foregroundColor(chrome.textPrimary)
                        Spacer()
                        Button("Update All") { store.dispatch("updateAll", id: "") }
                            .buttonStyle(PrimaryButtonStyle(accent: store.palette.accent))
                    }
                }

                ForEach(snap.cards) { card in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(card.name)
                                .font(.system(size: 13, weight: .semibold, design: .rounded))
                                .foregroundColor(chrome.textPrimary)
                            Spacer()
                            Text(statusLabel(card))
                                .font(.system(size: 10, weight: .bold, design: .rounded))
                                .foregroundColor(statusColor(card))
                        }
                        if let msg = card.statusMessage, !msg.isEmpty {
                            Text(msg)
                                .font(.system(size: 10, design: .monospaced))
                                .foregroundColor(chrome.textMuted)
                        }
                        if let progress = card.progress {
                            GeometryReader { geo in
                                ZStack(alignment: .leading) {
                                    RoundedRectangle(cornerRadius: 2)
                                        .fill(chrome.separator.opacity(0.5))
                                        .frame(height: 4)
                                    RoundedRectangle(cornerRadius: 2)
                                        .fill(store.palette.accent)
                                        .frame(width: geo.size.width * progress, height: 4)
                                }
                            }
                            .frame(height: 4)
                        }
                        HStack(spacing: 8) {
                            Button("Check") { store.dispatch("checkOne", id: card.id) }
                                .buttonStyle(GhostButtonStyle(chrome: chrome))
                            if card.state == .updateAvailable {
                                Button("Update") { store.dispatch("update", id: card.id) }
                                    .buttonStyle(PrimaryButtonStyle(accent: store.palette.accent))
                            }
                            if card.installed {
                                Button("Reveal") { store.dispatch("reveal", id: card.id) }
                                    .buttonStyle(GhostButtonStyle(chrome: chrome))
                            }
                        }
                    }
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(Color.white.opacity(0.03)))
                }
            }
        }
    }

    private var chrome: ThemePalette.ChromeColors { store.palette.chrome(store.variant) }

    private func statusLabel(_ card: CardModel) -> String {
        switch card.state {
        case .running: return "LIVE"
        case .checking: return "CHECKING"
        case .downloading, .installing: return "UPDATING"
        case .updateAvailable: return "UPDATE"
        case .failed: return "ERROR"
        case .installed: return "READY"
        case .missing: return "MISSING"
        }
    }
    private func statusColor(_ card: CardModel) -> Color {
        switch card.state {
        case .running, .installed: return .green
        case .checking, .downloading, .installing: return .blue
        case .updateAvailable: return .orange
        case .failed: return .red
        case .missing: return .gray
        }
    }
}

// MARK: - Settings Sheet

struct SettingsSheet: View {
    @EnvironmentObject var store: StateStore

    var body: some View {
        SheetScaffold(title: "Settings") {
            if let settings = store.snapshot?.settings {

                SheetCard(title: "Apps Folder (Scan + Install)", icon: "folder.fill") {
                    Text("The one folder BigM uses for everything: auto-scanning for existing launchers and installing new ones. Apps install into subfolders inside it.")
                        .font(.system(size: 11, design: .rounded))
                        .foregroundColor(chrome.textMuted)
                    HStack {
                        Text(settings.defaultInstallFolder ?? "Not set — the prompt appears on launch")
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundColor(settings.defaultInstallFolder != nil ? chrome.textPrimary : chrome.textFaint)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Spacer()
                        Button("Choose…") {
                            pickFolder { store.dispatch("setDefaultFolder", id: $0) }
                        }
                        .buttonStyle(GhostButtonStyle(chrome: chrome))
                        Button("Rescan") { store.dispatch("rescan", id: "") }
                            .buttonStyle(GhostButtonStyle(chrome: chrome))
                    }
                }

                SheetCard(title: "Account", icon: "person.fill.checkmark") {
                    HStack {
                        Text(store.snapshot?.activated == true
                             ? "Activated on this Mac"
                             : "Not activated — the key prompt appears on launch")
                            .font(.system(size: 12, design: .rounded))
                            .foregroundColor(chrome.textMuted)
                        Spacer()
                        if store.snapshot?.activated != true {
                            Button("Enter Key") { store.dispatch("showGate", id: "") }
                                .buttonStyle(PrimaryButtonStyle(accent: store.palette.accent))
                        }
                    }
                }

                SheetCard(title: "Files", icon: "doc.text.fill") {
                    HStack(spacing: 8) {
                        Button("Transport Log") { store.dispatch("revealFile", id: "transportLog") }
                            .buttonStyle(GhostButtonStyle(chrome: chrome))
                        Button("Support Folder") { store.dispatch("revealFile", id: "support") }
                            .buttonStyle(GhostButtonStyle(chrome: chrome))
                        Button("Config File") { store.dispatch("revealFile", id: "config") }
                            .buttonStyle(GhostButtonStyle(chrome: chrome))
                    }
                }
            }
        }
    }

    private var chrome: ThemePalette.ChromeColors { store.palette.chrome(store.variant) }
}

// MARK: - Customize Sheet

struct CustomizeSheet: View {
    @EnvironmentObject var store: StateStore

    var body: some View {
        SheetScaffold(title: "Customize") {
            Text("Each theme paints the whole world in its own light. Four time-of-day variants — dawn, day, dusk, night — keep the same topography with different lighting.")
                .font(.system(size: 11, design: .rounded))
                .foregroundColor(chrome.textMuted)

            // Time of day (variant) picker — all four options
            SheetCard(title: "Time of Day", icon: "clock.fill") {
                HStack(spacing: 10) {
                    ForEach([("auto", "Auto", "clock"),
                             ("dawn", "Dawn", "sunrise"),
                             ("day", "Day", "sun.max"),
                             ("dusk", "Dusk", "sunset"),
                             ("night", "Night", "moon")], id: \.0) { key, label, icon in
                        Button {
                            store.dispatch("setVariant", id: key)
                        } label: {
                            VStack(spacing: 4) {
                                Image(systemName: icon)
                                    .font(.system(size: 14))
                                Text(label)
                                    .font(.system(size: 10, weight: .medium, design: .rounded))
                            }
                            .foregroundColor(isVariantSelected(key)
                                             ? store.palette.accent
                                             : chrome.textMuted)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                            .background(
                                RoundedRectangle(cornerRadius: 10, style: .continuous)
                                    .fill(isVariantSelected(key)
                                          ? store.palette.accent.opacity(0.12)
                                          : Color.white.opacity(0.03))
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            // Theme grid with live scene previews
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 160), spacing: 12)], spacing: 12) {
                ForEach(ThemePalette.all, id: \.key) { theme in
                    ThemeThumb(theme: theme,
                               selected: store.snapshot?.settings?.themeKey == theme.key) {
                        store.dispatch("setTheme", id: theme.key)
                    }
                }
            }

            // Launcher Dock icon
            SheetCard(title: "Launcher Dock Icon", icon: "app.badge.fill") {
                HStack {
                    Text("Pick any app in /Applications — its icon becomes the launcher's Dock tile.")
                        .font(.system(size: 11, design: .rounded))
                        .foregroundColor(chrome.textMuted)
                    Spacer()
                    Button("Choose…") {
                        pickApp { store.dispatch("setLauncherIcon", id: $0) }
                    }
                    .buttonStyle(GhostButtonStyle(chrome: chrome))
                    Button("Reset") { store.dispatch("resetLauncherIcon", id: "") }
                        .buttonStyle(GhostButtonStyle(chrome: chrome))
                }
            }
        }
    }

    private func isVariantSelected(_ key: String) -> Bool {
        (store.snapshot?.settings?.variant ?? "auto") == key
    }

    private var chrome: ThemePalette.ChromeColors { store.palette.chrome(store.variant) }
}

struct ThemeThumb: View {
    let theme: ThemePalette
    let selected: Bool
    let onTap: () -> Void
    @State private var hovered = false

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 6) {
                NightwoodScene(palette: theme, variant: .day)
                    .frame(height: 80)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .strokeBorder(selected ? theme.accent : Color.clear, lineWidth: 2))
                Text(theme.title)
                    .font(.system(size: 11, weight: selected ? .bold : .medium, design: .rounded))
                    .foregroundColor(selected ? theme.accent : .secondary)
            }
            .scaleEffect(hovered ? 1.03 : 1.0)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: hovered)
        }
        .buttonStyle(.plain)
        .onHover { hovered = $0 }
    }
}

// MARK: - Diagnostics Sheet

struct DiagnosticsSheet: View {
    @EnvironmentObject var store: StateStore

    var body: some View {
        SheetScaffold(title: "Diagnostics") {
            Text("Arguments and credentials are never written here.")
                .font(.system(size: 11, design: .rounded))
                .foregroundColor(chrome.textMuted)
            ForEach(store.snapshot?.events ?? [], id: \.self) { line in
                Text(line)
                    .font(.system(size: 9.5, design: .monospaced))
                    .foregroundColor(line.contains("ERROR") ? .red.opacity(0.85)
                                     : line.contains("SUCCESS") ? .green.opacity(0.85)
                                     : chrome.textMuted)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if (store.snapshot?.events ?? []).isEmpty {
                Text("No events yet this session. Update checks, downloads, launches and failures appear here.")
                    .font(.system(size: 11, design: .rounded))
                    .foregroundColor(chrome.textFaint)
            }
        }
    }

    private var chrome: ThemePalette.ChromeColors { store.palette.chrome(store.variant) }
}

// MARK: - Get Apps Sheet

struct GetAppsSheet: View {
    @EnvironmentObject var store: StateStore

    var body: some View {
        SheetScaffold(title: "Get Apps") {
            SheetCard(title: "Already On This Mac", icon: "magnifyingglass") {
                Text("Point at a folder — every launcher found inside is wired in automatically by its launch file (run-rigel.sh, launch scripts, .app bundles). Your default apps folder is set in Settings; this scan checks any folder you pick.")
                    .font(.system(size: 11, design: .rounded))
                    .foregroundColor(chrome.textMuted)
                Button("Choose a Folder & Scan…") {
                    pickFolder { store.dispatch("scanFolderResult", id: $0) }
                }
                .buttonStyle(PrimaryButtonStyle(accent: store.palette.accent))
            }

            let installable = (store.snapshot?.cards ?? []).filter {
                !$0.installed && $0.category != "browser"
            }
            if !installable.isEmpty {
                SheetCard(title: "Install from Releases", icon: "arrow.down.circle") {
                    ForEach(installable) { card in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(card.name)
                                    .font(.system(size: 12, weight: .semibold, design: .rounded))
                                    .foregroundColor(chrome.textPrimary)
                                Text(card.summary)
                                    .font(.system(size: 10, design: .rounded))
                                    .foregroundColor(chrome.textMuted)
                                    .lineLimit(1)
                            }
                            Spacer()
                            Button("Install…") { store.dispatch("install", id: card.id) }
                                .buttonStyle(PrimaryButtonStyle(accent: store.palette.accent))
                        }
                        .padding(.vertical, 4)
                    }
                }
            }

            SheetCard(title: "Your Own Apps", icon: "plus.circle") {
                HStack(spacing: 8) {
                    Button("Add Custom App…") { store.dispatch("addCustomApp", id: "") }
                        .buttonStyle(GhostButtonStyle(chrome: chrome))
                    Button("Add Website…") { store.dispatch("addWebsite", id: "") }
                        .buttonStyle(GhostButtonStyle(chrome: chrome))
                }
            }
        }
    }

    private var chrome: ThemePalette.ChromeColors { store.palette.chrome(store.variant) }
}

// MARK: - System Sheet (machine-wide resource stats)

struct SystemSheet: View {
    @EnvironmentObject var store: StateStore

    var body: some View {
        SheetScaffold(title: "System") {
            if let sys = store.snapshot?.system {
                // CPU
                SheetCard(title: "CPU", icon: "cpu.fill") {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(String(format: "%.1f%%", sys.cpuPercent))
                                .font(.system(size: 22, weight: .bold, design: .rounded))
                                .foregroundColor(sys.cpuPercent > 80 ? .red
                                                 : sys.cpuPercent > 50 ? .orange
                                                 : .green)
                            Spacer()
                            Text("Load avg: " + String(format: "%.2f", sys.loadAvg))
                                .font(.system(size: 11, design: .rounded))
                                .foregroundColor(chrome.textMuted)
                        }
                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                RoundedRectangle(cornerRadius: 4)
                                    .fill(chrome.separator.opacity(0.3))
                                    .frame(height: 8)
                                RoundedRectangle(cornerRadius: 4)
                                    .fill(sys.cpuPercent > 80 ? Color.red
                                          : sys.cpuPercent > 50 ? Color.orange
                                          : Color.green)
                                    .frame(width: geo.size.width * min(1, sys.cpuPercent / 100),
                                           height: 8)
                            }
                        }
                        .frame(height: 8)
                    }
                }

                // Memory
                SheetCard(title: "Memory", icon: "memorychip") {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(String(format: "%.1f GB / %.1f GB", sys.memUsedGB, sys.memTotalGB))
                                .font(.system(size: 18, weight: .semibold, design: .rounded))
                                .foregroundColor(chrome.textPrimary)
                            Spacer()
                            Text(String(format: "%.0f%%", sys.memPercent))
                                .font(.system(size: 14, weight: .bold, design: .rounded))
                                .foregroundColor(sys.memPercent > 85 ? .red
                                                 : sys.memPercent > 70 ? .orange
                                                 : .green)
                        }
                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                RoundedRectangle(cornerRadius: 4)
                                    .fill(chrome.separator.opacity(0.3))
                                    .frame(height: 8)
                                RoundedRectangle(cornerRadius: 4)
                                    .fill(sys.memPercent > 85 ? Color.red
                                          : sys.memPercent > 70 ? Color.orange
                                          : Color.blue)
                                    .frame(width: geo.size.width * min(1, sys.memPercent / 100),
                                           height: 8)
                            }
                        }
                        .frame(height: 8)
                    }
                }

                // Battery
                if sys.batteryPercent >= 0 {
                    SheetCard(title: "Battery", icon: "battery.100") {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(sys.batteryPercent)%")
                                    .font(.system(size: 18, weight: .bold, design: .rounded))
                                    .foregroundColor(batteryColor(sys.batteryPercent))
                                Text(sys.batteryState)
                                    .font(.system(size: 11, design: .rounded))
                                    .foregroundColor(chrome.textMuted)
                            }
                            Spacer()
                            BatteryBar(percent: sys.batteryPercent)
                        }
                    }
                }

                // Machine info
                SheetCard(title: "Machine", icon: "desktopcomputer") {
                    VStack(spacing: 4) {
                        LabeledRow(label: "Model", value: sys.model)
                        LabeledRow(label: "Uptime", value: sys.uptime)
                        LabeledRow(label: "Threads", value: "\(sys.threadCount)")
                        LabeledRow(label: "BigM Version", value: store.snapshot?.version ?? "dev")
                    }
                }

                // Per-app resource usage
                let runningApps = (store.snapshot?.cards ?? []).filter {
                    ($0.cpuPercent ?? 0) > 0.1 || ($0.memMB ?? 0) > 10
                }
                if !runningApps.isEmpty {
                    SheetCard(title: "Running Apps", icon: "chart.bar.fill") {
                        ForEach(runningApps) { card in
                            HStack {
                                Text(card.name)
                                    .font(.system(size: 12, weight: .medium, design: .rounded))
                                    .foregroundColor(chrome.textPrimary)
                                Spacer()
                                if let cpu = card.cpuPercent, cpu > 0.1 {
                                    Text(String(format: "CPU %.1f%%", cpu))
                                        .font(.system(size: 10, design: .monospaced))
                                        .foregroundColor(.orange)
                                }
                                if let mem = card.memMB, mem > 10 {
                                    Text(String(format: "%.0f MB", mem))
                                        .font(.system(size: 10, design: .monospaced))
                                        .foregroundColor(.blue)
                                }
                            }
                            .padding(.vertical, 2)
                        }
                    }
                }
            } else {
                Text("Gathering system stats…")
                    .font(.system(size: 12, design: .rounded))
                    .foregroundColor(chrome.textMuted)
            }
        }
    }

    private var chrome: ThemePalette.ChromeColors { store.palette.chrome(store.variant) }

    private func batteryColor(_ pct: Int) -> Color {
        if pct > 50 { return .green }
        if pct > 20 { return .orange }
        return .red
    }
}

struct LabeledRow: View {
    let label: String
    let value: String
    @EnvironmentObject var store: StateStore

    var body: some View {
        HStack {
            Text(label)
                .font(.system(size: 11, design: .rounded))
                .foregroundColor(store.palette.chrome(store.variant).textMuted)
            Spacer()
            Text(value)
                .font(.system(size: 11, weight: .medium, design: .monospaced))
                .foregroundColor(store.palette.chrome(store.variant).textPrimary)
        }
        .padding(.vertical, 2)
    }
}

struct BatteryBar: View {
    let percent: Int

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 4)
                    .fill(Color.gray.opacity(0.2))
                    .frame(width: geo.size.width, height: 14)
                RoundedRectangle(cornerRadius: 4)
                    .fill(percent > 50 ? Color.green : percent > 20 ? Color.orange : Color.red)
                    .frame(width: geo.size.width * CGFloat(percent) / 100, height: 14)
            }
        }
        .frame(width: 120, height: 14)
    }
}

func pickFolder(_ onPick: @escaping (String) -> Void) {
    let panel = NSOpenPanel()
    panel.canChooseDirectories = true
    panel.canChooseFiles = false
    panel.allowsMultipleSelection = false
    panel.message = "Choose a folder"
    panel.begin { response in
        if response == .OK, let url = panel.url {
            onPick(url.path)
        }
    }
}

func pickApp(_ onPick: @escaping (String) -> Void) {
    let panel = NSOpenPanel()
    // .app bundles are packages — canChooseFiles must be true or
    // every .app in /Applications is greyed out and unselectable.
    panel.canChooseDirectories = true
    panel.canChooseFiles = true
    panel.allowsMultipleSelection = false
    panel.directoryURL = URL(fileURLWithPath: "/Applications")
    panel.message = "Choose the app whose icon to use"
    panel.begin { response in
        if response == .OK, let url = panel.url {
            onPick(url.path)
        }
    }
}

// MARK: - Button styles

struct PrimaryButtonStyle: ButtonStyle {
    let accent: Color
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 11, weight: .bold, design: .rounded))
            .foregroundColor(.white.opacity(0.95))
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .background(
                Capsule().fill(accent.opacity(configuration.isPressed ? 0.5 : 0.35))
                    .overlay(Capsule().strokeBorder(accent.opacity(0.6), lineWidth: 1))
            )
            .scaleEffect(configuration.isPressed ? 0.96 : 1.0)
            .animation(.spring(response: 0.2, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

struct GhostButtonStyle: ButtonStyle {
    let chrome: ThemePalette.ChromeColors
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 11, weight: .medium, design: .rounded))
            .foregroundColor(chrome.textPrimary.opacity(0.9))
            .padding(.horizontal, 12)
            .padding(.vertical, 5)
            .background(
                Capsule().fill(Color.white.opacity(configuration.isPressed ? 0.08 : 0.04))
                    .overlay(Capsule().strokeBorder(chrome.separator, lineWidth: 0.8))
            )
            .scaleEffect(configuration.isPressed ? 0.96 : 1.0)
            .animation(.spring(response: 0.2, dampingFraction: 0.7), value: configuration.isPressed)
    }
}
