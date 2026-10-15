import SwiftUI
import AppKit

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
}

struct FilterModel: Codable, Identifiable {
    let id: String
    let title: String
    let count: Int
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
}

// MARK: - Action handler (C function pointer, registered by Java)

public typealias ActionCallback = @convention(c) (UnsafePointer<CChar>?) -> Void
private var actionHandler: ActionCallback?

// MARK: - State store (observable, updated from Java)

final class StateStore: ObservableObject {
    @Published var snapshot: StateSnapshot?

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

    var body: some View {
        GeometryReader { geo in
            HStack(spacing: 0) {
                // Sidebar
                SidebarView()
                    .frame(width: 230)
                    .background(
                        sidebarBackground
                    )

                // Main content
                ScrollView(.vertical, showsIndicators: true) {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 260, maximum: 340),
                                                 spacing: 14)],
                              spacing: 14) {
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
            }
        }
        .background(
            // The Nightwood scene — renders ONCE per size/theme/variant
            NightwoodScene(palette: store.palette, variant: store.variant)
                .ignoresSafeArea()
        )
        .navigationTitle(store.snapshot?.windowTitle ?? "BigM")
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

            // Action rows
            VStack(spacing: 2) {
                SidebarActionRow(icon: "arrow.triangle.2.circlepath",
                                 title: updateTitle,
                                 alert: store.snapshot?.selfUpdateAvailable == true,
                                 palette: store.palette, variant: store.variant) {
                    store.dispatch("openSheet", id: "updates")
                }
                SidebarActionRow(icon: "plus.viewfinder",
                                 title: "Scan a Folder",
                                 alert: false,
                                 palette: store.palette, variant: store.variant) {
                    store.dispatch("scanFolder", id: "")
                }
                SidebarActionRow(icon: "paintbrush",
                                 title: "Customize",
                                 alert: false,
                                 palette: store.palette, variant: store.variant) {
                    store.dispatch("openSheet", id: "customize")
                }
                SidebarActionRow(icon: "gearshape",
                                 title: "Settings",
                                 alert: false,
                                 palette: store.palette, variant: store.variant) {
                    store.dispatch("openSheet", id: "settings")
                }
                SidebarActionRow(icon: "stethoscope",
                                 title: "Diagnostics",
                                 alert: false,
                                 palette: store.palette, variant: store.variant) {
                    store.dispatch("openSheet", id: "diagnostics")
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
    DispatchQueue.main.async {
        if let data = json.data(using: .utf8),
           let snapshot = try? JSONDecoder().decode(StateSnapshot.self, from: data) {
            sharedStore.snapshot = snapshot
        }
    }
}

@_cdecl("bigm_ui_launch")
public func bigmUILaunch() -> Int32 {
    // Must be called on the main thread (Java runs with -XstartOnFirstThread)
    DispatchQueue.main.async {
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

        let rootView = RootView().environmentObject(sharedStore)
        window.contentView = NSHostingView(rootView: rootView)
        window.center()
        window.makeKeyAndOrderFront(nil)
        app.activate(ignoringOtherApps: true)
    }
    return 0
}

@_cdecl("bigm_ui_snapshot")
public func bigmUISnapshot(_ pathPtr: UnsafePointer<CChar>?) -> Int32 {
    guard let path = pathPtr.map({ String(cString: $0) }) else { return 1 }
    // We're on the main thread (Java -XstartOnFirstThread) — render directly.
    // ImageRenderer requires MainActor; assumeIsolated is safe here because
    // this function is only ever called from the Java main thread.
    let result: Int32 = MainActor.assumeIsolated {
        let scene = NightwoodScene(palette: sharedStore.palette,
                                    variant: sharedStore.variant)
        let renderer = ImageRenderer(content: scene.frame(width: 1600, height: 1000))
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
