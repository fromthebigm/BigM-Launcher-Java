package ui;

import core.IconStore;
import core.Paths;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.TaskBar;
import org.eclipse.swt.widgets.TaskItem;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pure-Java dock/menu identity for the no-dylib launcher.
 *
 * Replaces bigm_identity.dylib for the parts that matter visually:
 *  - menu bar / app name via Display.setAppName + apple.awt.application.name
 *  - Dock icon via SWT TaskBar (bundled Voice Memos preset or picked PNG)
 * No Objective-C runtime calls, no FFM, no custom dylib. Capture exclusion
 * and the keychain token leg are intentionally gone; the former cannot be
 * expressed through public Java/SWT APIs, the latter is covered by the
 * embedded GitHub token and the /usr/bin/security fallback in ProductGate.
 */
public final class NativelessIdentity {

    private static Display display;
    private static Image dockIcon;

    private NativelessIdentity() {}

    public static void install(Display d) {
        display = d;
        System.setProperty("apple.awt.application.name", "Google Chrome");
        Display.setAppName("Google Chrome");
        Display.setAppVersion("1.0");
        applyLauncherIcon();
    }

    /** Reapply the Dock icon from the icon store / bundled preset. */
    public static void applyLauncherIcon() {
        if (display == null || display.isDisposed()) return;
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
        Image next = null;
        if (Files.isRegularFile(chosen)) {
            try { next = new Image(display, chosen.toString()); } catch (Exception ignored) {}
        }
        if (next != null) {
            TaskBar taskBar = display.getSystemTaskBar();
            TaskItem item = taskBar == null ? null : taskBar.getItem((org.eclipse.swt.widgets.Shell) null);
            if (item != null) item.setImage(next);
            if (dockIcon != null && !dockIcon.isDisposed() && dockIcon != next) dockIcon.dispose();
            dockIcon = next;
        }
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

    public static void dispose() {
        if (dockIcon != null && !dockIcon.isDisposed()) dockIcon.dispose();
        dockIcon = null;
        display = null;
    }
}
