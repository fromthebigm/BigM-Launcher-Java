package ui;

import core.IconStore;
import core.Paths;
import org.eclipse.swt.widgets.Display;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pure-Java dock/menu identity for the no-dylib launcher.
 *
 * Replaces bigm_identity.dylib for the parts that matter visually:
 *  - menu bar / app name via Display.setAppName + apple.awt.application.name
 *  - Dock icon via NSApplication.setApplicationIconImage through FFM into
 *    Apple's own libobjc/AppKit only (no shipped custom dylib).
 * Capture exclusion is handled separately by ui.ObjCCapture; the keychain
 * token leg is covered by the embedded token and the /usr/bin/security
 * fallback in ProductGate.
 */
public final class NativelessIdentity {

    private static Display display;
    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena GLOBAL = Arena.ofShared();
    private static final SymbolLookup OBJC = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", GLOBAL);
    private static final MethodHandle SEND0 = down("objc_msgSend",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEND1 = down("objc_msgSend",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SET_ICON = down("objc_msgSend",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private NativelessIdentity() {}

    public static void installBeforeDisplay() { install(null); }

    public static void install(Display d) {
        display = d;
        System.setProperty("apple.awt.application.name", "Google Chrome");
        Display.setAppName("Google Chrome");
        Display.setAppVersion("1.0");
        ObjCCapture.install(d);
        System.err.println("[bigm] capture protection: " + ObjCCapture.status());
        applyLauncherIcon();
    }

    /** Reapply the Dock icon from the icon store / bundled preset. */
    public static void applyLauncherIcon() {
        if (display != null && display.isDisposed()) return;
        Path chosen = null;
        IconStore.Mode mode = IconStore.launcherMode();
        if (IconStore.hasCustomIcon(IconStore.LAUNCHER_ID)) {
            boolean dark = mode == IconStore.Mode.DARK
                    || (mode == IconStore.Mode.SYSTEM && systemAppearanceIsDark());
            chosen = IconStore.icon(IconStore.LAUNCHER_ID, dark);
        }
        if (chosen == null || !Files.isRegularFile(chosen)) {
            boolean dark = systemAppearanceIsDark();
            chosen = Paths.resources().resolve("presets")
                    .resolve(dark ? "voicememos-dark.png" : "voicememos-light.png");
        }
        if (Files.isRegularFile(chosen)) setAppKitIcon(chosen);
    }

    public static boolean systemAppearanceIsDark() {
        try {
            Process p = new ProcessBuilder("/usr/bin/defaults", "read", "-g", "AppleInterfaceStyle")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return "Dark".equalsIgnoreCase(out);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void setAppKitIcon(Path file) {
        try {
            MemorySegment app = (MemorySegment) SEND0.invoke(cls("NSApplication"), sel("sharedApplication"));
            MemorySegment alloc = (MemorySegment) SEND0.invoke(cls("NSImage"), sel("alloc"));
            MemorySegment image = (MemorySegment) SEND1.invoke(alloc, sel("initWithContentsOfFile:"), ns(file.toString()));
            if (image != null && image.address() != 0)
                SET_ICON.invoke(app, sel("setApplicationIconImage:"), image);
            ui.objc.ObjC.callVoidInt(app, "setActivationPolicy:", 2); // regular
            ui.objc.ObjC.callVoidBool(app, "activateIgnoringOtherApps:", true);
        } catch (Throwable ignored) {
        }
    }

    private static MethodHandle down(String name, FunctionDescriptor fd) {
        try { return LINKER.downcallHandle(OBJC.find(name).orElseThrow(), fd); }
        catch (Throwable t) { throw new ExceptionInInitializerError(t); }
    }
    private static MemorySegment cls(String name) {
        try { return (MemorySegment) down("objc_getClass",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)).invoke(GLOBAL.allocateFrom(name)); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }
    private static MemorySegment sel(String name) {
        try { return (MemorySegment) down("sel_registerName",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)).invoke(GLOBAL.allocateFrom(name)); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }
    private static MemorySegment ns(String value) {
        try { return (MemorySegment) SEND1.invoke(cls("NSString"), sel("stringWithUTF8String:"), GLOBAL.allocateFrom(value)); }
        catch (Throwable t) { return MemorySegment.NULL; }
    }

    public static void dispose() { display = null; }
}
