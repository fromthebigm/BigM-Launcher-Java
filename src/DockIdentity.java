package core;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * The presentation-identity bridge, on Java's Foreign Function & Memory API
 * (JDK 25 bundling makes this stable; no JNA in the loop). Installs the
 * shared macOS presentation identity — "Google Chrome" in the menu bar and
 * the Voice Memos preset pair — and re-applies on activation. The shared
 * header's BIGM_PRESENTATION_ICON_* env contract (set by the icon picker)
 * keeps working identically for this process, and the Customize sheet's
 * Change Launcher Icon re-points it live through the set/render calls here.
 */
public final class DockIdentity {

    private static MethodHandle installHandle;
    private static MethodHandle applyHandle;
    private static MethodHandle setIconHandle;
    private static MethodHandle renderIconsHandle;
    private static MethodHandle protectHandle;
    private static MethodHandle darkenHandle;
    private static MethodHandle keychainReadHandle;
    private static MethodHandle keychainFreeHandle;

    public static synchronized void install() {
        if (applyHandle != null) return;
    java.nio.file.Path root = core.Paths.root();
        String[] candidates = {
                root.resolve("classes/native/bigm_identity.dylib").toString(),
                root.resolve("native/bigm_identity.dylib").toString(),
                root.resolve("bigm_identity.dylib").toString(),
        };
        for (String candidate : candidates) {
            tryArenaLoad(candidate, root.toString());
            if (applyHandle != null) return;
        }
        System.err.println("[bigm] dock identity dylib not found; running with default java identity");
    }

    private static void tryArenaLoad(String dylibPath, String rootPath) {
        if (applyHandle != null) return;
        // Candidate probing is order-based; a missing candidate isn't an
        // error worth logging (e.g. the build/classes layout only exists
        // in dev), so only shout when a file that EXISTS fails to load.
        if (!java.nio.file.Files.isRegularFile(java.nio.file.Path.of(dylibPath))) return;
        try {
            Linker linker = Linker.nativeLinker();
            // Process-lifetime arena: the downcall handles reference the
            // library's symbol segments for the whole run, so nothing scoped
            // shorter is safe to hand it.
            SymbolLookup lookup = SymbolLookup.libraryLookup(
                    java.nio.file.Path.of(dylibPath), Arena.ofShared());
            var installSeg = lookup.find("bigm_java_install_dock_identity").orElse(null);
            var applySeg = lookup.find("bigm_java_presentation_apply").orElse(null);
            if (installSeg == null || applySeg == null) return;
            installHandle = linker.downcallHandle(installSeg,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            applyHandle = linker.downcallHandle(applySeg,
                    FunctionDescriptor.ofVoid());
            // Optional (newer dylib): live icon swap + app-icon rendering
            // for the Customize sheet / Change Icon menu. Absent symbols
            // just leave the handles null — callers degrade gracefully.
            lookup.find("bigm_java_set_custom_icon").ifPresent(seg ->
                    setIconHandle = linker.downcallHandle(seg,
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS)));
            lookup.find("bigm_java_render_icon_variants").ifPresent(seg ->
                    renderIconsHandle = linker.downcallHandle(seg,
                            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS)));
            lookup.find("bigm_java_capture_protect_all").ifPresent(seg ->
                    protectHandle = linker.downcallHandle(seg, FunctionDescriptor.ofVoid()));
            lookup.find("bigm_java_surface_darken_all").ifPresent(seg ->
                    darkenHandle = linker.downcallHandle(seg, FunctionDescriptor.ofVoid()));
            // Silent keychain read (Security.framework, UI suppressed) — the
            // GitHubAccess token ladder's last leg; absent symbol → the leg
            // simply never yields a token.
            lookup.find("bigm_java_keychain_read_generic").ifPresent(seg ->
                    keychainReadHandle = linker.downcallHandle(seg,
                            FunctionDescriptor.of(ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS, ValueLayout.ADDRESS)));
            lookup.find("bigm_java_free_string").ifPresent(seg ->
                    keychainFreeHandle = linker.downcallHandle(seg,
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)));
            try (Arena argArena = Arena.ofConfined()) {
                MemorySegment cString = argArena.allocateFrom(rootPath);
                installHandle.invoke(cString);
            }
        } catch (Throwable failure) {
            System.err.println("[bigm] dock identity load failed from " + dylibPath
                    + ": " + failure.getMessage());
        }
    }

    public static void apply() {
        try {
            if (applyHandle != null) applyHandle.invoke();
        } catch (Throwable ignored) {
        }
    }

    /** Live-swap the launcher's own Dock icon (null paths → reset to the
     *  bundled default). No-op when the dylib predates the symbol. */
    public static void setCustomIcon(String lightPath, String darkPath, String mode) {
        if (setIconHandle == null) return;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment light = lightPath == null
                    ? MemorySegment.NULL : arena.allocateFrom(lightPath);
            MemorySegment dark = darkPath == null
                    ? MemorySegment.NULL : arena.allocateFrom(darkPath);
            MemorySegment modeSeg = mode == null
                    ? MemorySegment.NULL : arena.allocateFrom(mode);
            setIconHandle.invoke(light, dark, modeSeg);
        } catch (Throwable failure) {
            System.err.println("[bigm] set custom icon failed: " + failure.getMessage());
        }
    }

    /** Apply screenshot + screen-share exclusion to every BigM window NOW
     *  (and once per process: auto-hook any future sheet via notification).
     *  Honors BIGM_DIAGNOSTIC_CAPTURE=1 like the Swift build. */
    public static void applyCaptureProtection() {
        try {
            if (protectHandle != null) protectHandle.invoke();
        } catch (Throwable ignored) {
        }
    }

    /** "No OS-default background anywhere": dark appearance on app+windows,
     *  window backgrounds pinned to the scene floor, and NSScrollViews
     *  stripped of their document plate (the overscroll gray flash). Call
     *  after each shell opens; the native side auto-hooks later windows. */
    public static void applySurfaceDarken() {
        try {
            if (darkenHandle != null) darkenHandle.invoke();
        } catch (Throwable ignored) {
        }
    }

    /** Render an app's icon (light + dark variants) to PNG files. */
    public static boolean renderIconVariants(String appPath, String lightOut, String darkOut) {
        if (renderIconsHandle == null) return false;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment app = arena.allocateFrom(appPath);
            MemorySegment light = arena.allocateFrom(lightOut);
            MemorySegment dark = arena.allocateFrom(darkOut);
            return (boolean) renderIconsHandle.invoke(app, light, dark);
        } catch (Throwable failure) {
            System.err.println("[bigm] render icon variants failed: " + failure.getMessage());
            return false;
        }
    }

    /**
     * Read a generic password from the login keychain through
     * Security.framework with the authentication UI suppressed: when this
     * binary isn't trusted on the item, the read fails quietly instead of
     * raising the "allow access" sheet. Returns null when the item is
     * absent, unreadable by us, or the dylib predates the symbol.
     */
    public static String readKeychainPassword(String service, String account) {
        if (keychainReadHandle == null || service == null || account == null) return null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment serviceSeg = arena.allocateFrom(service);
            MemorySegment accountSeg = arena.allocateFrom(account);
            MemorySegment out = (MemorySegment) keychainReadHandle.invoke(serviceSeg, accountSeg);
            if (out == null || out.address() == 0) return null;
            // Copy onto the Java heap; the native buffer is freed when the
            // arena closes.
            return out.reinterpret(Long.MAX_VALUE, arena, seg -> {
                try {
                    if (keychainFreeHandle != null) keychainFreeHandle.invoke(seg);
                } catch (Throwable ignored) {
                }
            }).getString(0);
        } catch (Throwable failure) {
            return null;
        }
    }

    private DockIdentity() {}
}
