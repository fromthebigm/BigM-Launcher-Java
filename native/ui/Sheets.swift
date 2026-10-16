import SwiftUI
import AppKit

// ─────────────────────────────────────────────────────────────────────────────
// SHEETS — every sidebar action opens a real, working sheet: Updates,
// Customize, Settings, Diagnostics, Get Apps. Folder pickers use NSOpenPanel
// (native, dark). All state flows through the same JSON bridge as the main
// window; actions go back through the same callback.
// ─────────────────────────────────────────────────────────────────────────────

enum SheetKind: String, Identifiable {
    case updates, customize, settings, diagnostics, getApps
    var id: String { rawValue }

    var title: String {
        switch self {
        case .updates: return "Updates"
        case .customize: return "Customize"
        case .settings: return "Settings"
        case .diagnostics: return "Diagnostics"
        case .getApps: return "Get Apps"
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
                SheetCard(title: "Scenery", icon: "mountain.2.fill") {
                    HStack {
                        Text("Theme")
                            .font(.system(size: 12, design: .rounded))
                            .foregroundColor(chrome.textMuted)
                        Spacer()
                        Picker("", selection: Binding(
                            get: { settings.themeKey },
                            set: { store.dispatch("setTheme", id: $0) })) {
                            ForEach(ThemePalette.all, id: \.key) { theme in
                                Text(theme.title).tag(theme.key)
                            }
                        }
                        .frame(width: 180)
                    }
                    HStack {
                        Text("Time of day")
                            .font(.system(size: 12, design: .rounded))
                            .foregroundColor(chrome.textMuted)
                        Spacer()
                        Picker("", selection: Binding(
                            get: { settings.variant },
                            set: { store.dispatch("setVariant", id: $0) })) {
                            Text("Auto").tag("auto")
                            Text("Day").tag("day")
                            Text("Night").tag("night")
                        }
                        .frame(width: 180)
                    }
                }

                SheetCard(title: "Library", icon: "folder.fill") {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Default install folder")
                                .font(.system(size: 12, design: .rounded))
                                .foregroundColor(chrome.textMuted)
                            Text(settings.defaultInstallFolder ?? "Chosen on first install")
                                .font(.system(size: 10, design: .monospaced))
                                .foregroundColor(chrome.textPrimary)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                        Spacer()
                        Button("Choose…") {
                            pickFolder { store.dispatch("setDefaultFolder", id: $0) }
                        }
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
            Text("Each theme paints the whole world — mountains, river, forest, cabin — in its own light. Day and night variants keep the same topography.")
                .font(.system(size: 11, design: .rounded))
                .foregroundColor(chrome.textMuted)

            LazyVGrid(columns: [GridItem(.adaptive(minimum: 160), spacing: 12)], spacing: 12) {
                ForEach(ThemePalette.all, id: \.key) { theme in
                    ThemeThumb(theme: theme,
                               selected: store.snapshot?.settings?.themeKey == theme.key) {
                        store.dispatch("setTheme", id: theme.key)
                    }
                }
            }

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
                Text("Point at a folder — every launcher found inside is wired in automatically by its launch file (run-rigel.sh, launch scripts, .app bundles).")
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

// MARK: - Native pickers

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
    panel.canChooseDirectories = true
    panel.canChooseFiles = false
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
