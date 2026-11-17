package ui;

import org.eclipse.swt.widgets.Display;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Screenshot / screen-share exclusion with NO shipped dylib.
 *
 * Talks to Apple's own frameworks directly through the JDK Foreign Function
 * & Memory API (libobjc + SkyLight/CoreGraphics, all system-provided):
 *   1. public API: NSWindow.sharingType = NSWindowSharingNone on every window
 *   2. private SPI: SLSSetWindowCaptureExcludeShape with a huge window-local
 *      region, matching the old bigm_identity.dylib behavior.
 * A repeating SWT timer re-applies both to newly created sheet windows.
 */
public final class ObjCCapture {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena GLOBAL = Arena.ofShared();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static MethodHandle msgSendPtr, msgSendVoid, msgSendLong, msgSendLong0, msgSendCount, msgSendObjAt, selRegisterFn;
    private static MemorySegment selSharedApp, selWindows, selCount, selObjectAt, selSetSharing, selSharing;
    private static MethodHandle mainConnectionFn, regionCreateFn, excludeFn;
    private static MemoryLayout cgRect;
    private static volatile String status = "not installed";
    private static volatile int publicWindows, privateWindows, seenWindows;
    private static volatile long firstSharing = -1;
    private static volatile long appAddress = -1, windowsAddress = -1, lastCount = -1;

    private ObjCCapture() {}

    public static String status() { return status + " [app=" + appAddress + " arr=" + windowsAddress + " count=" + lastCount + " seen=" + seenWindows + " public=" + publicWindows + " private=" + privateWindows + " sharing=" + firstSharing + "]"; }

    /** Install once; must be called on the SWT UI thread after Display exists. */
    public static synchronized void install(Display display) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        if ("1".equals(System.getenv("BIGM_DIAGNOSTIC_CAPTURE"))) {
            status = "diagnostic (disabled)";
            return;
        }
        try {
            SymbolLookup libobjc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", GLOBAL);
            MemorySegment objcMsgSend = libobjc.find("objc_msgSend").orElseThrow();
            msgSendPtr = LINKER.downcallHandle(objcMsgSend,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            msgSendVoid = LINKER.downcallHandle(objcMsgSend,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            msgSendLong = LINKER.downcallHandle(objcMsgSend,
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            msgSendLong0 = LINKER.downcallHandle(objcMsgSend,
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            msgSendCount = LINKER.downcallHandle(objcMsgSend,
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            msgSendObjAt = LINKER.downcallHandle(objcMsgSend,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            selRegisterFn = LINKER.downcallHandle(libobjc.find("sel_registerName").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            selSharedApp = sel("sharedApplication");
            selWindows = sel("windows");
            selCount = sel("count");
            selObjectAt = sel("objectAtIndex:");
            selSetSharing = sel("setSharingType:");
            selSharing = sel("sharingType");

            // Private WindowServer exclusion, best effort.
            try {
                SymbolLookup sky = SymbolLookup.libraryLookup(
                        "/System/Library/PrivateFrameworks/SkyLight.framework/SkyLight", GLOBAL);
                var conn = sky.find("SLSMainConnectionID");
                var exclude = sky.find("SLSSetWindowCaptureExcludeShape");
                var region = sky.find("CGRegionCreateWithRect");
                if (conn.isEmpty()) {
                    SymbolLookup cg = SymbolLookup.libraryLookup(
                            "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", GLOBAL);
                    region = cg.find("CGRegionCreateWithRect");
                    exclude = cg.find("CGSSetWindowCaptureExcludeShape");
                }
                if (conn.isPresent() && region.isPresent() && exclude.isPresent()) {
                    mainConnectionFn = LINKER.downcallHandle(conn.get(),
                            FunctionDescriptor.of(ValueLayout.JAVA_INT));
                    cgRect = MemoryLayout.structLayout(
                            ValueLayout.JAVA_DOUBLE.withName("x"),
                            ValueLayout.JAVA_DOUBLE.withName("y"),
                            ValueLayout.JAVA_DOUBLE.withName("w"),
                            ValueLayout.JAVA_DOUBLE.withName("h"));
                    regionCreateFn = LINKER.downcallHandle(region.get(),
                            FunctionDescriptor.of(ValueLayout.ADDRESS, cgRect));
                    excludeFn = LINKER.downcallHandle(exclude.get(),
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
                }
            } catch (Throwable ignored) {
            }
            status = "public=on private=" + (excludeFn != null ? "on" : "off");
        } catch (Throwable failure) {
            status = "unavailable: " + failure.getMessage();
        }
        apply();
        if (display != null && !display.isDisposed()) {
            Runnable refresh = new Runnable() {
                @Override public void run() {
                    if (display.isDisposed()) return;
                    apply();
                    display.timerExec(1000, this);
                }
            };
            display.timerExec(1000, refresh);
        }
    }

    private static MemorySegment sel(String name) {
        try (Arena scope = Arena.ofConfined()) {
            return (MemorySegment) selRegisterFn.invoke(scope.allocateFrom(name));
        } catch (Throwable failure) {
            return GLOBAL.allocateFrom(name);
        }
    }

    /** Apply to all current NSWindows. Safe to call repeatedly. */
    public static void apply() {
        if (msgSendPtr == null) return;
        publicWindows = 0;
        privateWindows = 0;
        seenWindows = 0;
        firstSharing = -1;
        try {
            appAddress = Lookup.CLS_NSAPPLICATION == null ? -2 : Lookup.CLS_NSAPPLICATION.address();
            MemorySegment app = (MemorySegment) msgSendPtr.invoke(
                    Lookup.CLS_NSAPPLICATION, selSharedApp);
            if (app == null || app.address() == 0) return;
            appAddress = app.address();
            MemorySegment windows = (MemorySegment) msgSendPtr.invoke(app, selWindows);
            if (windows == null || windows.address() == 0) return;
            windowsAddress = windows.address();
            long count = (long) msgSendCount.invoke(windows, selCount);
            lastCount = count;
            for (long i = 0; i < Math.min(count, 256); i++) {
                MemorySegment window = (MemorySegment) msgSendObjAt.invoke(windows, selObjectAt, i);
                if (window == null || window.address() == 0) continue;
                msgSendVoid.invoke(window, selSetSharing, 0); // NSWindowSharingNone
                long sharing = (long) msgSendLong0.invoke(window, selSharing);
                seenWindows++;
                if (firstSharing < 0) firstSharing = sharing;
                if (sharing == 0) publicWindows++;
                if (excludeFn != null) applyWindowServerExclusion(window);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void applyWindowServerExclusion(MemorySegment window) {
        try {
            long windowNumber = (long) msgSendCount.invoke(window, sel("windowNumber"));
            if (windowNumber <= 0) return;
            int connection = (int) mainConnectionFn.invoke();
            try (Arena scope = Arena.ofConfined()) {
                MemorySegment rect = scope.allocate(cgRect);
                rect.set(ValueLayout.JAVA_DOUBLE, 0L, 0.0);
                rect.set(ValueLayout.JAVA_DOUBLE, 8L, 0.0);
                rect.set(ValueLayout.JAVA_DOUBLE, 16L, 32768.0);
                rect.set(ValueLayout.JAVA_DOUBLE, 24L, 32768.0);
                MemorySegment region = (MemorySegment) regionCreateFn.invoke(rect);
                if (region == null || region.address() == 0) return;
                int result = (int) excludeFn.invoke(connection, windowNumber, region);
                if (result == 0) privateWindows++;
            }
        } catch (Throwable ignored) {
        }
    }

    private static final class Lookup {
        static final MemorySegment CLS_NSAPPLICATION;
        static {
            MemorySegment cls = null;
            try {
                MemorySegment fn = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", GLOBAL)
                        .find("objc_getClass").orElseThrow();
                MethodHandle getClass = LINKER.downcallHandle(fn,
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                cls = (MemorySegment) getClass.invoke(GLOBAL.allocateFrom("NSApplication"));
            } catch (Throwable ignored) {
            }
            CLS_NSAPPLICATION = cls;
        }
    }
}
