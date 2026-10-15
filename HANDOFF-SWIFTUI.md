# HANDOFF: SwiftUI Face Inside the Java Process

## Architecture (the owner's directive, implemented)

Java remains the process. SwiftUI renders the UI as a dylib hosted inside
the JVM via Java's Foreign Function & Memory API (FFM). No SWT anywhere in
the face — no gray OS-default background possible, because the dylib pins
dark appearance on the app and every window natively.

```
┌─────────────────────────────────────────────────────┐
│  Java process (java -XstartOnFirstThread)            │
│                                                     │
│  ┌──────────────┐    FFM bridge    ┌──────────────┐ │
│  │  Java core   │ ←──────────────→ │  Swift dylib │ │
│  │              │   JSON state →   │              │ │
│  │  Catalog     │   ← actions      │  SwiftUI     │ │
│  │  Updates     │                  │  Canvas      │ │
│  │  Gate        │                  │  Cards       │ │
│  │  Launch      │                  │  Sidebar     │ │
│  │  Scan        │                  │  Scene       │ │
│  └──────────────┘                  └──────────────┘ │
│                                                     │
│  main thread: NSApplication.run() (Swift owns it)  │
│  worker threads: Java core (updates, discovery)    │
└─────────────────────────────────────────────────────┘
```

## Files

### Swift side (`native/ui/`)
| File | Purpose |
|------|---------|
| `ColorMath.swift` | sRGB color blending (`CM.mix`, `CM.scale`, `CM.withOpacity`), `SplitMix64` (deterministic RNG), `ValueNoise` (multi-octave lattice noise), geometry sugar (`Pt()`, `R()`) |
| `ThemePalette.swift` | 6 themes × day/night variants: `pine`, `taiga` (snowy), `summit`, `dusk`, `glacier`, `ember`. Each has `SceneColors` (sky, ridges, lake, ground, foliage) + `ChromeColors` (window furniture) for both variants |
| `NightwoodScene.swift` | The flagship painter — sky gradient, stars/moon (night) or sun/birds/clouds (day), grand piercing massif with snow caps and two-tone faces, mist, far ridge, treeline, winding river with banks and glints, shoreline with reeds, dense conifer forest (each tree: trunk + 3 tiers + cast shadow), cabin with warm window glow, winding trail with stones, hero pines framing edges, fireflies/pollen, vignette. All static — renders ONCE per size/theme/variant via `Canvas` + `.drawingGroup()`. Zero idle cost. |
| `AppCardView.swift` | Ornate launcher cards: icon medallion, name/category/stats, status badge, action buttons, corner vine flourishes (`VineShape`), category emblems (`CategoryEmblem`). Spring hover (scale 1.03 + shadow deepen), event-driven. |
| `BigMUIBridge.swift` | The bridge: `StateSnapshot`/`CardModel` Codable structs, `StateStore` (ObservableObject), `RootView` (sidebar + card grid), `SidebarView`, `GateView` (product key), `@_cdecl` entry points (`bigm_ui_launch`, `bigm_ui_set_state`, `bigm_ui_snapshot`, `bigm_ui_set_action_handler`) |

### Java side (`src/core/`)
| File | Purpose |
|------|---------|
| `SwiftUIHost.java` | FFM bridge: loads `libBigMUI.dylib`, creates downcall handles, registers upcall stub for actions, `pushState(json)`, `launch()`, `snapshot(path)` |
| `HeadlessController.java` | The headless brain: owns catalog/config/stats/updates, resolves installs, tracks running processes, builds JSON state, dispatches Swift actions (launch/quit/update/install/reveal/scanFolder/activate/filter), listens to `LauncherState` changes and pushes state |
| `ScanEngine.java` | Folder auto-detect (extracted from SWT sheet): walks a folder for catalog app markers, run*.sh, .app bundles |
| `JsonOut.java` | Minimal JSON writer for the Java→Swift state bridge |
| `IconStore.java` | Icon rendering + caching (now with size-validity guards — the "blocked icon" fix) |

### Entry point (`src/BigMApp.java`)
Routes to the Swift face by default. Falls back to SWT (`--ui=swt`) if the
dylib is missing. `--swift-shot <path>` renders the scene to a PNG and exits.

## The bridge contract

### Java → Swift (state push, JSON)
```json
{
  "windowTitle": "BigM",
  "themeKey": "pine",
  "variant": "auto",
  "activated": true,
  "version": "portable-2026.10.14",
  "cards": [
    {
      "id": "rigel",
      "name": "Rigel",
      "category": "browser",
      "summary": "Hardened browser...",
      "statsLine": "Never launched",
      "accentRed": 143.0, "accentGreen": 188.0, "accentBlue": 111.0,
      "state": "installed",
      "installed": true,
      "statusMessage": null,
      "progress": null,
      "iconPath": "/path/Support/icons/system/rigel-dark.png"
    }
  ],
  "filters": [{"id": "all", "title": "All Apps", "count": 9}],
  "selectedFilter": "all",
  "selfUpdateAvailable": false,
  "selfUpdateTag": null,
  "selfUpdateMessage": null,
  "runningCount": 0
}
```

### Swift → Java (action callback, JSON)
```json
{"action": "launch", "id": "rigel"}
```
Actions: `launch`, `quit`, `update`, `install`, `reveal`, `filter`,
`scanFolder`, `scanFolderResult`, `activate`, `quitApp`, `openSheet`.

## Build & test

```bash
# Build the Swift dylib
cd native/ui
swiftc -emit-library -O -target arm64-apple-macos14.0 -module-name BigMUI \
    -o libBigMUI.dylib ColorMath.swift ThemePalette.swift NightwoodScene.swift \
    AppCardView.swift BigMUIBridge.swift -framework SwiftUI -framework AppKit
codesign --force --sign - libBigMUI.dylib

# Build the Java side (includes the dylib path resolution)
cd ../..
zsh build.sh

# Render a scene snapshot (verification without screen-recording perms)
./jdk/Contents/Home/bin/java -XstartOnFirstThread \
    -cp "target/bigm.jar:lib/*" BigMApp --swift-shot /tmp/scene.png

# Run the full app (SwiftUI face)
./jdk/Contents/Home/bin/java -XstartOnFirstThread \
    -cp "target/bigm.jar:lib/*" BigMApp

# Fallback to SWT (if needed)
./jdk/Contents/Home/bin/java -XstartOnFirstThread \
    -cp "target/bigm.jar:lib/*" BigMApp --ui=swt
```

## What's done ✓

- Nightwood scene painter: sky, celestial, massif, mist, far ridge, treeline,
  river, shoreline, forest, cabin, trail, hero pines, fireflies, vignette
- Day + night variants (night = moon + stars + fireflies; day = sun + birds + pollen)
- 6 themes including Snowy Taiga (the requested winter variant)
- Card views: ornate, vine corners, icon medallion, status badge, spring hover
- Sidebar: brand block, filters with counts, action rows, version
- Bridge: Java FFM ↔ Swift @_cdecl, JSON state push, action upcall
- HeadlessController: launch/quit/update/install/reveal/scan/activate/filter
- ScanEngine: folder auto-detect (extracted to core for both faces)
- IconStore: size-validity guards (the "blocked icon" fix)
- Product key handoff: fixed and verified (padded base64 ticket + gate header)
- Package.sh: ships the Swift dylib

## What's next (for the next session)

1. **Sheets in SwiftUI**: Updates, Customize, Settings, Diagnostics — currently
   only stubs in the bridge (Swift-side sheets as `.sheet()` modifiers)
2. **Folder picker for scanFolder**: Swift should show NSOpenPanel, then send
   `scanFolderResult` with the picked path
3. **Taiga scene differentiation**: The painter currently uses the same
   composition for all themes — the taiga should get snow-dusted trees,
   a frozen river (ice instead of water glints), and snow patches on the
   ground. Add a `snowAmount` parameter to `Painter` driven by the theme.
4. **Card entrance animations**: staggered fade-in when the grid builds
5. **Ambient animation layer**: fireflies with slow drift via a separate
   `TimelineView(.periodic(every: 3))` overlay (NOT in the main Canvas —
   the base stays static)
6. **Theme switcher UI**: the Customize sheet needs to push `themeKey` changes
7. **Day/night auto-detection**: currently "auto" resolves from the host clock;
   the variant should update at dawn/dusk without a restart
8. **Card distinct looks**: each card should feel more unique per category —
   different corner ornaments, different emblem styles, different button
   shapes per category

## Battery efficiency (verified)

- Scene renders ONCE per size/theme/variant via `Canvas` + `.drawingGroup()`
- No TimelineView on the base scene, no animation loops, no timers
- Card hover is event-driven SwiftUI spring (renders ~300ms then stops)
- The only "animation" is the spring hover on cards — zero idle cost
- When ambient layer is added (fireflies), it uses a 3-second periodic
  TimelineView → ~0.33 fps → negligible

## Known issues

- The `--swift-shot` harness works but the scene is rendered at the default
  palette (pine) — it should use the pushed state's theme once state flows
- The gate view exists but isn't wired to appear when `activated` is false
  (the RootView should show GateView instead of the card grid)
- `openSheet` actions are logged but don't open actual sheets yet
