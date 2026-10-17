import SwiftUI
import AppKit
import ObjectiveC

// ─────────────────────────────────────────────────────────────────────────────
// THE BRIDGE — Java pushes JSON state; Swift renders it; Swift sends actions
// back through a C function pointer. This is the entire Java↔SwiftUI contract.
// ─────────────────────────────────────────────────────────────────────────────

// MARK: - State models (Codable — decoded from Java's JSON)

enum CardState: String, Codable {
    case running, checking, downloading, installing
    case updateAvailable, failed, installed, missing
}

struct CardModel: Codable, Identifiable {
    let id: String
    let name: String
    let category: String
    let summary: String
    let statsLine: String
    let accentRed: Double
    let accentGreen: Double
    let accentBlue: Double
    let state: CardState
    let installed: Bool
    let statusMessage: String?
    let progress: Double?
    let iconPath: String?
    let cpuPercent: Double?
    let memMB: Double?
    let lastPlayedAt: Double?
    let launches: Int?
    let sessionMinutes: Double?
}

struct FilterModel: Codable, Identifiable {
    let id: String
    let title: String
    let count: Int
}

/// Settings values the sheets read (theme, variant, install folder…).
struct SettingsModel: Codable {
    let themeKey: String
    let variant: String
    let defaultInstallFolder: String?
}

/// System-wide machine stats (CPU, RAM, battery, model, uptime).
struct SystemModel: Codable {
    let cpuPercent: Double
    let memUsedGB: Double
    let memTotalGB: Double
    let memPercent: Double
    let loadAvg: Double
    let threadCount: Int
    let uptime: String
    let model: String
    let batteryPercent: Int
    let batteryState: String
}

/// BigM's own resource usage (real RSS/CPU from ps).
struct SelfStatsModel: Codable {
    let cpuPercent: Double
    let memMB: Double
}

/// One running app's play session (minutes since launch).
struct SessionModel: Codable, Identifiable {
    let appId: String
    let minutes: Double
    var id: String { appId }
}

struct StateSnapshot: Codable {
    let windowTitle: String
    let themeKey: String
    let variant: String
    let activated: Bool
    let version: String
    let cards: [CardModel]
    let filters: [FilterModel]
    let selectedFilter: String
    let selfUpdateAvailable: Bool
    let selfUpdateTag: String?
    let selfUpdateMessage: String?
    let runningCount: Int
    let settings: SettingsModel?
    let events: [String]?
    let needsDefaultFolder: Bool?
    let system: SystemModel?
    let selfStats: SelfStatsModel?
    let sessions: [SessionModel]?
}

// MARK: - Action handler (C function pointer, registered by Java)

public typealias ActionCallback = @convention(c) (UnsafePointer<CChar>?) -> Void
private var actionHandler: ActionCallback?

// MARK: - State store (observable, updated from Java)

final class StateStore: ObservableObject {
    @Published var snapshot: StateSnapshot?
    @Published var activeSheet: SheetKind?
    @Published var showGate: Bool = false

    var palette: ThemePalette {
        ThemePalette.forKey(snapshot?.themeKey)
    }
    var variant: SceneVariant {
        SceneVariant.resolve(snapshot?.variant)
    }

    func dispatch(_ action: String, id: String) {
        let json = "{\"action\":\"\(action)\",\"id\":\"\(id)\"}"
        json.utf8CString.withUnsafeBufferPointer { buf in
            buf.baseAddress.map { ptr in actionHandler?(ptr) }
        }
    }
}

// MARK: - Root View

struct RootView: View {
    @EnvironmentObject var store: StateStore
    @State private var searchText = ""
    @State private var showPalette = false
    @State private var paletteQuery = ""
    @FocusState private var searchFocused: Bool

    var body: some View {
        ZStack {
            NightwoodScene(palette: store.palette, variant: store.variant)
                .ignoresSafeArea()

            if store.showGate {
                GateView()
            } else if store.snapshot?.needsDefaultFolder == true {
                FirstLaunchFolderPrompt()
            } else {
                HStack(spacing: 0) {
                    SidebarView()
                        .frame(width: 230)
                        .background(sidebarBackground)

                    VStack(spacing: 0) {
                        // Header: rescan + BigM self-stats + search
                        HStack {
                            Button {
                                store.dispatch("rescan", id: "")
                            } label: {
                                HStack(spacing: 5) {
                                    Image(systemName: "arrow.clockwise")
                                        .font(.system(size: 10, weight: .semibold))
                                    Text("Rescan")
                                        .font(.system(size: 11, weight: .semibold, design: .rounded))
                                }
                                .foregroundColor(store.palette.accent)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 7)
                                .background(
                                    Capsule()
                                        .fill(store.palette.accent.opacity(0.12))
                                        .overlay(Capsule().strokeBorder(
                                            store.palette.accent.opacity(0.35), lineWidth: 0.8))
                                )
                            }
                            .buttonStyle(.plain)

                            if let ss = store.snapshot?.selfStats {
                                HStack(spacing: 3) {
                                    Image(systemName: "memorychip")
                                        .font(.system(size: 9))
                                        .foregroundColor(.blue.opacity(0.7))
                                    Text(String(format: "%.0f MB", ss.memMB))
                                        .font(.system(size: 9, weight: .medium, design: .monospaced))
                                        .foregroundColor(.blue.opacity(0.8))
                                    if ss.cpuPercent > 1 {
                                        Text(String(format: "· %.0f%%", ss.cpuPercent))
                                            .font(.system(size: 9, weight: .medium, design: .monospaced))
                                            .foregroundColor(.orange.opacity(0.8))
                                    }
                                }
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background(Capsule().fill(Color.white.opacity(0.04)))
                            }

                            Spacer()

                            HStack(spacing: 8) {
                                Image(systemName: "magnifyingglass")
                                    .font(.system(size: 12))
                                    .foregroundColor(store.palette.chrome(store.variant).textMuted)
                                TextField("Search library", text: $searchText)
                                    .textFieldStyle(.plain)
                                    .font(.system(size: 12, design: .rounded))
                                    .foregroundColor(store.palette.chrome(store.variant).textPrimary)
                                    .focused($searchFocused)
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 7)
                            .background(
                                Capsule()
                                    .fill(store.palette.chrome(store.variant).cardFill.opacity(0.95))
                                    .overlay(Capsule().strokeBorder(
                                        store.palette.chrome(store.variant).separator.opacity(0.6),
                                        lineWidth: 0.8))
                            )
                            .frame(width: 230)
                        }
                        .padding(.horizontal, 20)
                        .padding(.top, 14)
                        .padding(.bottom, 6)

                        ScrollView(.vertical, showsIndicators: true) {
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 270, maximum: 360),
                                                         spacing: 16)],
                                      spacing: 16) {
                                ForEach(filteredCards) { card in
                                    AppCardView(card: card,
                                                palette: store.palette,
                                                variant: store.variant) { action, id in
                                        store.dispatch(action, id: id)
                                    }
                                }
                            }
                            .padding(20)
                        }

                        // Activity ticker
                        if let events = store.snapshot?.events,
                           let latest = events.last {
                            HStack(spacing: 6) {
                                Circle().fill(Color.green).frame(width: 5, height: 5)
                                Text(latest)
                                    .font(.system(size: 9, design: .monospaced))
                                    .foregroundColor(store.palette.chrome(store.variant).textMuted)
                                    .lineLimit(1)
                                Spacer()
                            }
                            .padding(.horizontal, 20)
                            .padding(.vertical, 4)
                            .background(Color.black.opacity(0.15))
                        }
                    }
                }
            }

            // Command palette (Cmd+K)
            if showPalette {
                CommandPalette(query: $paletteQuery,
                               cards: store.snapshot?.cards ?? [],
                               onSelect: { action, id in
                                    store.dispatch(action, id: id)
                                    showPalette = false
                                    paletteQuery = ""
                               },
                               onClose: {
                                    showPalette = false
                                    paletteQuery = ""
                               })
                .zIndex(10)
            }
        }
        .navigationTitle(store.snapshot?.windowTitle ?? "BigM")
        .sheet(item: $store.activeSheet) { kind in
            switch kind {
            case .updates: UpdatesSheet()
            case .customize: CustomizeSheet()
            case .settings: SettingsSheet()
            case .diagnostics: DiagnosticsSheet()
            case .getApps: GetAppsSheet()
            case .system: SystemSheet()
            }
        }
        .onAppear {
            NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
                handleKeyEvent(event)
            }
        }
        .onChange(of: store.snapshot?.runningCount) { count in
            updateDockBadge(count ?? 0)
        }
    }

    private func handleKeyEvent(_ event: NSEvent) -> NSEvent? {
        let mods = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        if mods == .command && event.charactersIgnoringModifiers == "k" {
            showPalette.toggle()
            if showPalette { paletteQuery = "" }
            return nil
        }
        if mods == .command && event.charactersIgnoringModifiers == "f" {
            searchFocused = true
            return nil
        }
        if mods == .command,
           let s = event.charactersIgnoringModifiers,
           let n = Int(s), n >= 1 && n <= 9 {
            let cards = filteredCards
            if n <= cards.count {
                let c = cards[n - 1]
                store.dispatch(c.state == .running ? "quit" : "launch", id: c.id)
                return nil
            }
        }
        if event.keyCode == 53 {
            if showPalette { showPalette = false; paletteQuery = ""; return nil }
            if !searchText.isEmpty { searchText = ""; return nil }
        }
        return event
    }

    private func updateDockBadge(_ count: Int) {
        NSApplication.shared.dockTile.badgeLabel = count > 0 ? "\(count)" : nil
    }

    private var filteredCards: [CardModel] {
        guard let snap = store.snapshot else { return [] }
        var cards = snap.cards
        if snap.selectedFilter != "all" {
            cards = cards.filter { $0.category == snap.selectedFilter }
        }
        if !searchText.isEmpty {
            cards = cards.filter {
                $0.name.localizedCaseInsensitiveContains(searchText) ||
                $0.summary.localizedCaseInsensitiveContains(searchText)
            }
        }
        cards.sort { a, b in
            let ap = a.lastPlayedAt ?? 0
            let bp = b.lastPlayedAt ?? 0
            if ap != bp { return ap > bp }
            return a.name.localizedCompare(b.name) == .orderedAscending
        }
        return cards
    }

    private var sidebarBackground: some View {
        Rectangle()
            .fill(LinearGradient(stops: [
                .init(color: store.palette.chrome(store.variant).chromeRaised.opacity(0.9),
                      location: 0),
                .init(color: store.palette.chrome(store.variant).chromeRaised.opacity(0.7),
                      location: 1),
            ], startPoint: .top, endPoint: .bottom))
            .overlay(
                Rectangle()
                    .strokeBorder(
                        store.palette.chrome(store.variant).separator.opacity(0.3),
                        lineWidth: 0.5)
            )
    }
}

// MARK: - Command Palette (Cmd+K)

struct CommandPalette: View {
    @Binding var query: String
    let cards: [CardModel]
    let onSelect: (String, String) -> Void
    let onClose: () -> Void
    @State private var selIdx = 0
    @FocusState private var focused: Bool

    var results: [(String, String, String, String)] {
        var r: [(String, String, String, String)] = []
        let q = query.lowercased()
        for c in cards {
            if q.isEmpty || c.name.lowercased().contains(q) || c.summary.lowercased().contains(q) {
                r.append((c.state == .running ? "quit" : "launch", c.id,
                          c.state == .running ? "Quit \(c.name)" : "Launch \(c.name)", c.summary))
                if c.state == .updateAvailable {
                    r.append(("update", c.id, "Update \(c.name)", "New version available"))
                }
                r.append(("reveal", c.id, "Reveal \(c.name)", "Open in Finder"))
            }
        }
        if q.isEmpty || "rescan".contains(q) { r.append(("rescan", "", "Rescan Apps Folder", "")) }
        if q.isEmpty || "update check".contains(q) { r.append(("checkAll", "", "Check All Updates", "")) }
        if q.isEmpty || "system stats".contains(q) { r.append(("openSystem", "", "Open System Stats", "")) }
        return Array(r.prefix(12))
    }

    var body: some View {
        ZStack {
            Color.black.opacity(0.4)
                .onTapGesture { onClose() }
                .ignoresSafeArea()

            VStack(spacing: 0) {
                HStack(spacing: 10) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 16))
                        .foregroundColor(.white.opacity(0.5))
                    TextField("Search apps and actions…", text: $query)
                        .textFieldStyle(.plain)
                        .font(.system(size: 16, design: .rounded))
                        .foregroundColor(.white)
                        .focused($focused)
                        .onSubmit { submit() }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 14)

                Divider().overlay(Color.white.opacity(0.1))

                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(Array(results.enumerated()), id: \.offset) { idx, item in
                            Button {
                                onSelect(item.0, item.1)
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(item.2)
                                            .font(.system(size: 13, weight: .medium, design: .rounded))
                                            .foregroundColor(.white)
                                        if !item.3.isEmpty {
                                            Text(item.3)
                                                .font(.system(size: 10, design: .rounded))
                                                .foregroundColor(.white.opacity(0.5))
                                                .lineLimit(1)
                                        }
                                    }
                                    Spacer()
                                    if idx == selIdx {
                                        Image(systemName: "return")
                                            .font(.system(size: 10))
                                            .foregroundColor(.white.opacity(0.4))
                                    }
                                }
                                .padding(.horizontal, 18)
                                .padding(.vertical, 8)
                                .background(idx == selIdx ? Color.white.opacity(0.08) : Color.clear)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .frame(maxHeight: 300)

                Divider().overlay(Color.white.opacity(0.1))

                HStack {
                    Text("↩ select").font(.system(size: 9)).foregroundColor(.white.opacity(0.3))
                    Spacer()
                    Text("esc close").font(.system(size: 9)).foregroundColor(.white.opacity(0.3))
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 8)
            }
            .frame(width: 480)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Color.black.opacity(0.85))
                    .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .strokeBorder(Color.white.opacity(0.1), lineWidth: 1))
            )
            .shadow(color: .black.opacity(0.5), radius: 30)
            .padding(.top, 80)
        }
        .onAppear { focused = true }
    }

    private func submit() {
        guard selIdx < results.count else { return }
        let item = results[selIdx]
        onSelect(item.0, item.1)
    }
}

// MARK: - Sidebar

struct SidebarView: View {
    @EnvironmentObject var store: StateStore

    var body: some View {
        VStack(spacing: 0) {
            // Brand block
            HStack(spacing: 10) {
                ZStack {
                    Circle()
                        .fill(store.palette.accent.opacity(0.15))
                        .frame(width: 36, height: 36)
                    Text("BM")
                        .font(.system(size: 14, weight: .heavy, design: .rounded))
                        .foregroundColor(store.palette.accent)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text("BigM")
                        .font(.system(size: 15, weight: .bold, design: .rounded))
                        .foregroundColor(store.palette.chrome(store.variant).textPrimary)
                    Text("L A U N C H E R")
                        .font(.system(size: 7, weight: .bold, design: .rounded))
                        .tracking(2)
                        .foregroundColor(store.palette.chrome(store.variant).textMuted)
                }
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 20)
            .padding(.bottom, 12)

            // Filters
            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: 2) {
                    Text("L I B R A R Y")
                        .font(.system(size: 8, weight: .bold, design: .rounded))
                        .tracking(1.5)
                        .foregroundColor(store.palette.chrome(store.variant).textFaint)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 4)

                    ForEach(store.snapshot?.filters ?? []) { filter in
                        SidebarRow(filter: filter,
                                   isSelected: store.snapshot?.selectedFilter == filter.id,
                                   palette: store.palette,
                                   variant: store.variant) { filterId in
                            store.dispatch("filter", id: filterId)
                        }
                    }
                }
                .padding(.horizontal, 10)
            }

            Spacer()

            // Action rows — every row opens its real sheet
            VStack(spacing: 2) {
                SidebarActionRow(icon: "arrow.triangle.2.circlepath",
                                 title: updateTitle,
                                 alert: store.snapshot?.selfUpdateAvailable == true,
                                 palette: store.palette, variant: store.variant) {
                    store.activeSheet = .updates
                    // Fresh check the moment the sheet opens — self + per-app
                    store.dispatch("checkAll", id: "")
                }
                SidebarActionRow(icon: "plus.viewfinder",
                                 title: "Get Apps",
                                 alert: false,
                                 palette: store.palette, variant: store.variant) {
                    store.activeSheet = .getApps
                }
                SidebarActionRow(icon: "paintbrush",
                                 title: "Customize",
                                 alert: false,
                                 palette: store.palette, variant: store.variant) {
                    store.activeSheet = .customize
                }
                SidebarActionRow(icon: "gearshape",
                                 title: "Settings",
                                 alert: false,
                                 palette: store.palette, variant: store.variant) {
                    store.activeSheet = .settings
                }
                SidebarActionRow(icon: "stethoscope",
                                 title: "Diagnostics",
                                 alert: false,
                                 palette: store.palette, variant: store.variant) {
                    store.activeSheet = .diagnostics
                }
                SidebarActionRow(icon: "chart.bar.fill",
                                 title: "System",
                                 alert: false,
                                 palette: store.palette, variant: store.variant) {
                    store.activeSheet = .system
                }
            }
            .padding(.horizontal, 10)
            .padding(.bottom, 12)

            // Version
            HStack {
                Circle()
                    .fill(store.palette.accent.opacity(0.3))
                    .frame(width: 5, height: 5)
                Text(store.snapshot?.version ?? "dev")
                    .font(.system(size: 9, weight: .medium, design: .monospaced))
                    .foregroundColor(store.palette.chrome(store.variant).textMuted)
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 10)
        }
    }

    private var updateTitle: String {
        guard let snap = store.snapshot else { return "Updates" }
        let updates = snap.cards.filter { $0.state == .updateAvailable }.count
        let selfUpdate = snap.selfUpdateAvailable ? 1 : 0
        let total = updates + selfUpdate
        return total > 0 ? "Updates (\(total))" : "Updates"
    }
}

struct SidebarRow: View {
    let filter: FilterModel
    let isSelected: Bool
    let palette: ThemePalette
    let variant: SceneVariant
    let onTap: (String) -> Void
    @State private var hovered = false

    var body: some View {
        Button {
            onTap(filter.id)
        } label: {
            HStack(spacing: 8) {
                Text(filter.title)
                    .font(.system(size: 12, weight: isSelected ? .semibold : .regular,
                                   design: .rounded))
                    .foregroundColor(isSelected
                                     ? palette.accent
                                     : palette.chrome(variant).textPrimary)
                Spacer()
                Text("\(filter.count)")
                    .font(.system(size: 9, weight: .bold, design: .rounded))
                    .foregroundColor(isSelected
                                     ? palette.accent
                                     : palette.chrome(variant).textMuted)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(
                        Capsule()
                            .fill(isSelected ? palette.accent.opacity(0.15)
                                             : Color.white.opacity(0.04))
                    )
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(isSelected ? palette.accent.opacity(0.1)
                                     : hovered ? Color.white.opacity(0.03) : Color.clear)
            )
        }
        .buttonStyle(.plain)
        .onHover { hovering in hovered = hovering }
    }
}

struct SidebarActionRow: View {
    let icon: String
    let title: String
    let alert: Bool
    let palette: ThemePalette
    let variant: SceneVariant
    let onTap: () -> Void
    @State private var hovered = false

    var body: some View {
        Button {
            onTap()
        } label: {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundColor(alert ? .orange : palette.chrome(variant).textMuted)
                Text(title)
                    .font(.system(size: 12, weight: .regular, design: .rounded))
                    .foregroundColor(palette.chrome(variant).textPrimary)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 9, weight: .medium))
                    .foregroundColor(palette.chrome(variant).textFaint)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(hovered ? Color.white.opacity(0.03) : Color.clear)
            )
        }
        .buttonStyle(.plain)
        .onHover { hovering in hovered = hovering }
    }
}

// MARK: - Gate View (product key entry — shown when not activated)

struct GateView: View {
    @EnvironmentObject var store: StateStore
    @State private var key = ""
    @State private var error: String?

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "key.horizontal.fill")
                .font(.system(size: 32))
                .foregroundColor(store.palette.accent)
            Text("BigM Activation")
                .font(.system(size: 18, weight: .bold, design: .rounded))
                .foregroundColor(store.palette.chrome(.night).textPrimary)
            Text("Enter the universal product key (one-time activation for this Mac):")
                .font(.system(size: 12, design: .rounded))
                .foregroundColor(store.palette.chrome(.night).textMuted)
                .multilineTextAlignment(.center)
            SecureField("Product key", text: $key)
                .textFieldStyle(.roundedBorder)
                .frame(maxWidth: 280)
                .onSubmit { submit() }
            if let err = error {
                Text(err)
                    .font(.system(size: 11, design: .rounded))
                    .foregroundColor(.red)
            }
            HStack(spacing: 12) {
                Button("Quit") { store.dispatch("quitApp", id: "") }
                    .buttonStyle(.plain)
                    .foregroundColor(store.palette.chrome(.night).textMuted)
                Button("Activate") { submit() }
                    .buttonStyle(.plain)
                    .foregroundColor(store.palette.accent)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(store.palette.accent.opacity(0.2)))
            }
        }
        .padding(32)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(store.palette.chrome(.night).chromeRaised)
                .shadow(radius: 20)
        )
    }

    private func submit() {
        guard !key.isEmpty else { return }
        store.dispatch("activate", id: key)
        store.showGate = false
    }
}

// MARK: - First-launch folder prompt

/// Shown when the launcher has no default apps folder yet — one pick sets
/// the folder used for BOTH auto-scanning (detecting existing launchers)
/// and installing (new apps land in subfolders inside it). The same
/// folder lives in Settings afterward, changeable any time.
struct FirstLaunchFolderPrompt: View {
    @EnvironmentObject var store: StateStore

    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "folder.badge.plus")
                .font(.system(size: 36))
                .foregroundColor(store.palette.accent)
            Text("Welcome to BigM")
                .font(.system(size: 20, weight: .bold, design: .rounded))
                .foregroundColor(store.palette.chrome(store.variant).textPrimary)
            Text("Choose the folder where your launchers live.\nBigM uses this same folder to find existing apps and to install new ones.")
                .font(.system(size: 12, design: .rounded))
                .foregroundColor(store.palette.chrome(store.variant).textMuted)
                .multilineTextAlignment(.center)
                .lineSpacing(3)

            Button {
                pickFolder { path in
                    store.dispatch("setDefaultFolder", id: path)
                }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "folder")
                        .font(.system(size: 13))
                    Text("Choose Apps Folder…")
                        .font(.system(size: 14, weight: .semibold, design: .rounded))
                }
                .foregroundColor(.white.opacity(0.95))
                .padding(.horizontal, 24)
                .padding(.vertical, 10)
                .background(
                    Capsule()
                        .fill(store.palette.accent.opacity(0.35))
                        .overlay(Capsule().strokeBorder(store.palette.accent.opacity(0.6), lineWidth: 1))
                )
            }
            .buttonStyle(.plain)

            Button("Skip for now") {
                store.dispatch("setDefaultFolder", id: NSHomeDirectory() + "/Downloads")
            }
            .font(.system(size: 11, design: .rounded))
            .foregroundColor(store.palette.chrome(store.variant).textFaint)
            .buttonStyle(.plain)
        }
        .padding(40)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(store.palette.chrome(store.variant).chromeRaised.opacity(0.95))
                .shadow(color: .black.opacity(0.5), radius: 30)
        )
    }
}

// MARK: - Entry points (@_cdecl — called from Java via FFM)

@_cdecl("bigm_ui_set_action_handler")
public func bigmUISetActionHandler(_ handler: @escaping ActionCallback) {
    actionHandler = handler
}

@_cdecl("bigm_ui_set_state")
public func bigmUISetState(_ jsonPtr: UnsafePointer<CChar>?) {
    guard let ptr = jsonPtr else { return }
    let json = String(cString: ptr)
    // Apply synchronously when already on the main thread (snapshot
    // harness); hop otherwise (Java worker threads in production). A pure
    // async hop starves when no run loop is pumping.
    if Thread.isMainThread {
        applyStateJSON(json)
    } else {
        DispatchQueue.main.async { applyStateJSON(json) }
    }
}

private func applyStateJSON(_ json: String) {
    if let data = json.data(using: .utf8),
       let snapshot = try? JSONDecoder().decode(StateSnapshot.self, from: data) {
        sharedStore.snapshot = snapshot
        sharedStore.showGate = !snapshot.activated
    }
}

/// Pumps the main run loop for N seconds — the snapshot harness uses this
/// so queued state pushes land before rendering. Harmless in production.
@_cdecl("bigm_ui_pump")
public func bigmUIPump(_ seconds: Double) {
    MainActor.assumeIsolated {
        RunLoop.main.run(until: Date().addingTimeInterval(seconds))
    }
}

@_cdecl("bigm_ui_launch")
public func bigmUILaunch() -> Int32 {
    // Java runs with -XstartOnFirstThread, so this call arrives ON the
    // AppKit main thread (thread 0). Set up synchronously, then hand the
    // thread to NSApplication.run() — which blocks forever running the
    // event loop.
    let app = NSApplication.shared
    app.setActivationPolicy(.regular)

    // Dark appearance — no system default gray anywhere
    app.appearance = NSAppearance(named: .darkAqua)

    // Main menu (bare minimum: app name + Quit)
    let mainMenu = NSMenu()
    let appMenuItem = NSMenuItem()
    appMenuItem.submenu = NSMenu()
    appMenuItem.submenu?.addItem(withTitle: "Quit",
                                  action: #selector(NSApplication.terminate(_:)),
                                  keyEquivalent: "q")
    mainMenu.addItem(appMenuItem)
    app.mainMenu = mainMenu

    let window = NSWindow(
        contentRect: NSRect(x: 0, y: 0, width: 1180, height: 760),
        styleMask: [.titled, .closable, .miniaturizable, .resizable],
        backing: .buffered, defer: false)
    window.title = "BigM"
    window.minSize = NSSize(width: 900, height: 560)
    window.backgroundColor = NSColor(srgbRed: 0.039, green: 0.051, blue: 0.047, alpha: 1.0)
    window.appearance = NSAppearance(named: .darkAqua)
    window.isReleasedWhenClosed = false

    // Screenshot + screen-share protection: the window never appears in
    // screenshots, screen recordings, or screen-share picks.
    window.sharingType = .none

    let rootView = RootView().environmentObject(sharedStore)
    window.contentView = NSHostingView(rootView: rootView)

    // X button = quit the whole app (not a zombie process with no windows).
    // The delegate intercepts the close and terminates NSApplication,
    // which ends app.run() and lets the JVM main thread return.
    let closeDelegate = QuitOnCloseDelegate()
    window.delegate = closeDelegate
    objc_setAssociatedObject(window, &CloseDelegateKey.key, closeDelegate,
                             .OBJC_ASSOCIATION_RETAIN)

    window.center()
    window.makeKeyAndOrderFront(nil)
    app.activate(ignoringOtherApps: true)

    // Own the run loop until the user quits. Never returns.
    app.run()
    return 0
}

/// Window delegate that turns the close (X) button into a full app quit.
private final class QuitOnCloseDelegate: NSObject, NSWindowDelegate {
    func windowShouldClose(_ sender: NSWindow) -> Bool {
        NSApplication.shared.terminate(nil)
        return true
    }
}

private enum CloseDelegateKey {
    static var key: UInt8 = 0
}

@_cdecl("bigm_ui_snapshot")
public func bigmUISnapshot(_ pathPtr: UnsafePointer<CChar>?, _ fullUI: Int32) -> Int32 {
    guard let path = pathPtr.map({ String(cString: $0) }) else { return 1 }
    // We're on the main thread (Java -XstartOnFirstThread) — render directly.
    // fullUI != 0 renders the entire face (sidebar + cards over the scene);
    // 0 renders the scene alone.
    let result: Int32 = MainActor.assumeIsolated {
        let content = fullUI != 0
            ? AnyView(RootView().environmentObject(sharedStore))
            : AnyView(NightwoodScene(palette: sharedStore.palette,
                                      variant: sharedStore.variant))
        let renderer = ImageRenderer(content: content.frame(width: 1600, height: 1000))
        renderer.scale = 1.0
        if let image = renderer.nsImage,
           let tiff = image.tiffRepresentation,
           let rep = NSBitmapImageRep(data: tiff),
           let png = rep.representation(using: .png, properties: [:]) {
            try? png.write(to: URL(fileURLWithPath: path))
            return 0
        }
        return 2
    }
    return result
}

// Shared store singleton
private let sharedStore = StateStore()
