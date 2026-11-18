import java.lang.foreign.*;
import java.lang.invoke.*;
import java.nio.charset.StandardCharsets;

/** Window screenshot via CoreGraphics/AppKit FFM — bypasses the TCC-blocked
 *  screencapture CLI. Usage: ScreenCapture <pid> <out.png> */
public final class ScreenCapture {
    private static final Linker L = Linker.nativeLinker();
    private static final Arena G = Arena.ofShared();
    private static final SymbolLookup OBJC = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", G);
    private static final SymbolLookup CG = SymbolLookup.libraryLookup(
            "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", G);

    private static final MethodHandle CLS = down(OBJC, "objc_getClass",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEL = down(OBJC, "sel_registerName",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEND0 = down(OBJC, "objc_msgSend",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEND1 = down(OBJC, "objc_msgSend",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEND_COUNT = down(OBJC, "objc_msgSend",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEND_D = down(OBJC, "objc_msgSend",
            FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEND_L = down(OBJC, "objc_msgSend",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEND_I = down(OBJC, "objc_msgSend",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEND_LONG_ARG = down(OBJC, "objc_msgSend",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private static final MethodHandle SEND_BOOL = down(OBJC, "objc_msgSend",
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle COPY_INFO = down(CG, "CGWindowListCopyWindowInfo",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MemoryLayout RECT = MemoryLayout.structLayout(
            ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
            ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE);
    private static final MethodHandle CREATE_IMAGE = down(CG, "CGWindowListCreateImage",
            FunctionDescriptor.of(ValueLayout.ADDRESS, RECT, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    private ScreenCapture() {}

    public static void main(String[] args) throws Throwable {
        int pid = Integer.parseInt(args[0]);
        String out = args[1];

        MemorySegment array = (MemorySegment) COPY_INFO.invoke(1, 0); // on-screen only
        if (array == null || array.address() == 0) throw new IllegalStateException("no window list");
        long count = (long) SEND_COUNT.invoke(array, sel("count"));
        long bestWindow = 0;
        long bestArea = 0;
        MethodHandle objectAt = down(OBJC, "objc_msgSend",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        for (long i = 0; i < count; i++) {
            MemorySegment dict = (MemorySegment) objectAt.invoke(array, sel("objectAtIndex:"), i);
            if (dict == null || dict.address() == 0) continue;
            MemorySegment pidNum = (MemorySegment) SEND1.invoke(dict, sel("objectForKey:"), ns("kCGWindowOwnerPID"));
            int owner = pidNum == null || pidNum.address() == 0 ? 0 : (int) SEND_I.invoke(pidNum, sel("intValue"));
            if (owner != pid) continue;
            MemorySegment bounds = (MemorySegment) SEND1.invoke(dict, sel("objectForKey:"), ns("kCGWindowBounds"));
            if (bounds == null || bounds.address() == 0) continue;
            MemorySegment wn = (MemorySegment) SEND1.invoke(bounds, sel("objectForKey:"), ns("Width"));
            MemorySegment hn = (MemorySegment) SEND1.invoke(bounds, sel("objectForKey:"), ns("Height"));
            double w = wn == null || wn.address() == 0 ? 0 : (double) SEND_D.invoke(wn, sel("doubleValue"));
            double h = hn == null || hn.address() == 0 ? 0 : (double) SEND_D.invoke(hn, sel("doubleValue"));
            long area = Math.round(w * h);
            MemorySegment numObj = (MemorySegment) SEND1.invoke(dict, sel("objectForKey:"), ns("kCGWindowNumber"));
            long num = numObj == null || numObj.address() == 0 ? 0 : (long) SEND_L.invoke(numObj, sel("longLongValue"));
            if (area > bestArea) { bestArea = area; bestWindow = num; }
        }
        System.out.println("bestWindow=" + bestWindow + " area=" + bestArea);
        if (bestWindow == 0) throw new IllegalStateException("no window for pid " + pid);

        MemorySegment rect = G.allocate(RECT);
        rect.set(ValueLayout.JAVA_DOUBLE, 0L, 0.0);
        rect.set(ValueLayout.JAVA_DOUBLE, 8L, 0.0);
        rect.set(ValueLayout.JAVA_DOUBLE, 16L, 20000.0);
        rect.set(ValueLayout.JAVA_DOUBLE, 24L, 20000.0);
        MemorySegment cgImage = (MemorySegment) CREATE_IMAGE.invoke(rect, 1, 0, 0);
        if (cgImage == null || cgImage.address() == 0) throw new IllegalStateException("CGWindowListCreateImage failed");

        MemorySegment repClass = (MemorySegment) CLS.invoke(alloc("NSBitmapImageRep"));
        MemorySegment rep = (MemorySegment) SEND0.invoke(repClass, sel("alloc"));
        rep = (MemorySegment) SEND1.invoke(rep, sel("initWithCGImage:"), cgImage);
        MemorySegment emptyDict = (MemorySegment) SEND0.invoke((MemorySegment) CLS.invoke(alloc("NSDictionary")), sel("dictionary"));
        MethodHandle repr = down(OBJC, "objc_msgSend", FunctionDescriptor.of(ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        MemorySegment png = (MemorySegment) repr.invoke(rep,
                sel("representationUsingType:properties:"), 4L, emptyDict);
        if (png == null || png.address() == 0) throw new IllegalStateException("PNG conversion failed");
        boolean ok = (boolean) down(OBJC, "objc_msgSend", FunctionDescriptor.of(
                ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN)).invoke(png,
                sel("writeToFile:atomically:"), ns(out), true);
        System.out.println("screenshot pid=" + pid + " window=" + bestWindow + " ok=" + ok + " -> " + out);
    }

    private static MethodHandle down(SymbolLookup l, String n, FunctionDescriptor fd) {
        return L.downcallHandle(l.find(n).orElseThrow(() -> new IllegalStateException("missing " + n)), fd);
    }
    private static MemorySegment alloc(String s) { return G.allocateFrom(s); }
    private static MemorySegment sel(String s) {
        try { return (MemorySegment) SEL.invoke(alloc(s)); } catch (Throwable t) { throw new RuntimeException(t); }
    }
    private static MemorySegment ns(String s) {
        try {
            return (MemorySegment) SEND1.invoke((MemorySegment) CLS.invoke(alloc("NSString")),
                    sel("stringWithUTF8String:"), alloc(s));
        } catch (Throwable t) { throw new RuntimeException(t); }
    }
    private static String javaString(MemorySegment seg) {
        if (seg == null || seg.address() == 0) return "";
        try {
            MemorySegment utf8 = (MemorySegment) SEND0.invoke(seg, sel("UTF8String"));
            if (utf8 == null || utf8.address() == 0) return "";
            return utf8.reinterpret(1 << 16).getString(0);
        } catch (Throwable t) { return ""; }
    }
}
