package core;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The FFM bridge to the SwiftUI face. Java remains the process — the Swift
 * dylib hosts the window and renders; this class is the only contract:
 *
 *   • launch() hands the main thread to NSApplication.run() inside the dylib
 *   • pushState(json) sends the full library state to Swift for rendering
 *   • Swift sends actions back through an upcall stub (launch/quit/update/…)
 *
 * No SWT anywhere. No gray OS-default background — the dylib pins dark
 * appearance on the app and every window natively.
 */
public final class SwiftUIHost {

    private static MethodHandle launchHandle;
    private static MethodHandle setStateHandle;
    private static MethodHandle snapshotHandle;
    private static MethodHandle pumpHandle;
    private static MethodHandle setHandlerCall;
    private static volatile ActionHandler actionHandler;

    /** Called by Swift when a card action fires: {"action":"launch","id":"rigel"} */
    public interface ActionHandler {
        void onAction(String action, String appId);
    }

    /**
     * Loads the dylib and registers the action callback. Returns true when
     * the Swift face is available (dylib found and symbols resolved).
     */
    public static synchronized boolean install(ActionHandler handler) {
        if (launchHandle != null) return true;
        actionHandler = handler;

        Path root = Paths.root();
        Path[] candidates = {
                root.resolve("native/ui/libBigMUI.dylib"),
                root.resolve("libBigMUI.dylib"),
        };
        for (Path dylib : candidates) {
            if (!Files.isRegularFile(dylib)) continue;
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup lookup = SymbolLookup.libraryLookup(dylib, Arena.ofShared());

                var launchSeg = lookup.find("bigm_ui_launch").orElse(null);
                var setStateSeg = lookup.find("bigm_ui_set_state").orElse(null);
                var snapshotSeg = lookup.find("bigm_ui_snapshot").orElse(null);
                var pumpSeg = lookup.find("bigm_ui_pump").orElse(null);
                var setHandlerSeg = lookup.find("bigm_ui_set_action_handler").orElse(null);
                if (launchSeg == null || setStateSeg == null) continue;

                launchHandle = linker.downcallHandle(launchSeg,
                        FunctionDescriptor.of(ValueLayout.JAVA_INT));
                setStateHandle = linker.downcallHandle(setStateSeg,
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                if (snapshotSeg != null) {
                    snapshotHandle = linker.downcallHandle(snapshotSeg,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                }
                if (pumpSeg != null) {
                    pumpHandle = linker.downcallHandle(pumpSeg,
                            FunctionDescriptor.ofVoid(ValueLayout.JAVA_DOUBLE));
                }
                if (setHandlerSeg != null) {
                    setHandlerCall = linker.downcallHandle(setHandlerSeg,
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                }

                // Register the Java upcall: Swift → Java(action JSON)
                if (setHandlerCall != null) {
                    MethodHandle onSwiftAction = MethodHandles.lookup().findStatic(
                            SwiftUIHost.class, "swiftActionReceived",
                            MethodType.methodType(void.class, MemorySegment.class));
                    MemorySegment stub = linker.upcallStub(
                            onSwiftAction,
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
                            Arena.ofShared());
                    try (Arena args = Arena.ofConfined()) {
                        setHandlerCall.invoke(stub);
                    }
                }
                return true;
            } catch (Throwable failure) {
                System.err.println("[bigm] SwiftUI dylib load failed from " + dylib
                        + ": " + failure.getMessage());
                return false;
            }
        }
        return false;
    }

    /**
     * Push the full library state (JSON) to the Swift face. Thread-safe;
     * Swift hops to the main queue internally.
     */
    public static void pushState(String json) {
        if (setStateHandle == null || json == null) return;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cString = arena.allocateFrom(json, StandardCharsets.UTF_8);
            setStateHandle.invoke(cString);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Pumps the AppKit main run loop for N seconds — lets queued state
     * pushes land without starting the full NSApplication.run loop (used
     * by the snapshot harness; harmless elsewhere).
     */
    public static void pump(double seconds) {
        if (pumpHandle == null) return;
        try {
            pumpHandle.invoke(seconds);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Render the current face to a PNG for verification (offscreen, no
     * screen-recording perms needed). fullUI=true renders the whole face
     * (sidebar + cards); false renders the bare scene. Returns true on
     * success.
     */
    public static boolean snapshot(String path, boolean fullUI) {
        if (snapshotHandle == null) return false;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cString = arena.allocateFrom(path, StandardCharsets.UTF_8);
            int result = (int) snapshotHandle.invoke(cString, fullUI ? 1 : 0);
            return result == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Take over the main thread and run the SwiftUI app. NEVER RETURNS —
     * the Swift side runs NSApplication.run() and owns the run loop until
     * the user quits. All Java workers (updates, sweeps) keep running on
     * daemon threads.
     */
    public static void launch() {
        if (launchHandle == null) {
            throw new IllegalStateException("SwiftUIHost.install() must succeed first");
        }
        try {
            launchHandle.invoke();
        } catch (Throwable failure) {
            System.err.println("[bigm] SwiftUI launch failed: " + failure.getMessage());
        }
    }

    // ── Upcall entry (called by Swift with a C-string action JSON) ────────

    private static void swiftActionReceived(MemorySegment jsonPtr) {
        try {
            if (jsonPtr.address() == 0) return;
            String json = jsonPtr.reinterpret(Long.MAX_VALUE).getString(0);
            // Parse {"action":"launch","id":"rigel"} — JsonTiny is available
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            var parsed = net.JsonTiny.parseObject(bytes);
            String action = net.JsonTiny.str(parsed, "action");
            String id = net.JsonTiny.str(parsed, "id");
            if (action != null && actionHandler != null) {
                actionHandler.onAction(action, id == null ? "" : id);
            }
        } catch (Throwable ignored) {
        }
    }

    private SwiftUIHost() {}
}
