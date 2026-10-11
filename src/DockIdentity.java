import com.sun.jna.Library;
import com.sun.jna.Native;

/**
 * JNA bridge to native/bigm_identity.dylib: installs the presentation
 * identity ("Google Chrome" menu bar name + Voice Memos icon pair,
 * BIGM_PRESENTATION_ICON_* env contract honored by the shared header) and
 * exposes the apply hook the SWT event loop calls on (re)activation.
 *
 * Loaded from jar-adjacent classes/native/ first, then the package root's
 * native/ folder (dev layout). Optional: the app still runs without it,
 * looking exactly like a bare java process — which is the failure mode the
 * other launchers had until their loaders fixed it.
 */
public final class DockIdentity {

    public interface Lib extends Library {
        void bigm_java_install_dock_identity(String packageRoot);
        void bigm_java_presentation_apply();
    }

    private static Lib lib;

    public static synchronized void install() {
        if (lib != null) return;
        java.nio.file.Path root = core.Paths.root();
        String[] candidates = {
                root.resolve("classes/native/bigm_identity.dylib").toString(),
                root.resolve("native/bigm_identity.dylib").toString(),
                root.resolve("bigm_identity.dylib").toString(),
        };
        for (String candidate : candidates) {
            if (java.nio.file.Files.isRegularFile(java.nio.file.Path.of(candidate))) {
                try {
                    lib = Native.load(candidate, Lib.class);
                    lib.bigm_java_install_dock_identity(root.toString());
                    return;
                } catch (Throwable failure) {
                    System.err.println("[bigm] dock identity load failed from " + candidate
                            + ": " + failure.getMessage());
                }
            }
        }
        System.err.println("[bigm] dock identity dylib not found; running with default java identity");
    }

    public static void apply() {
        Lib current = lib;
        if (current != null) {
            try {
                current.bigm_java_presentation_apply();
            } catch (Throwable ignored) {
            }
        }
    }

    private DockIdentity() {}
}
