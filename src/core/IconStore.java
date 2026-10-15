package core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Custom icon store (the Swift LauncherIconStore): rendered light + dark
 * PNGs under Support/icons/<appID>-{light,dark}.png, source images pulled
 * from any picked app via the native dylib (NSWorkspace under forced
 * appearances). The launcher's own icon uses appID "launcher"; its files
 * are read by the dylib at install time (and re-pointed live through
 * DockIdentity.setCustomIcon), while per-app icons ride into launched
 * children as BIGM_PRESENTATION_ICON_* env (each hardened launcher reads
 * them through the same shared presentation header).
 */
public final class IconStore {

    public enum Mode { SYSTEM, LIGHT, DARK;
        public String key() { return name().toLowerCase(); }
        public static Mode parse(String raw) {
            if (raw == null) return SYSTEM;
            return switch (raw) { case "light" -> LIGHT; case "dark" -> DARK; default -> SYSTEM; };
        }
    }

    public static final String LAUNCHER_ID = "launcher";

    private IconStore() {}

    public static Path iconsDirectory() {
        Path dir = Paths.support().resolve("icons");
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        return dir;
    }

    public static Path icon(String appId, boolean dark) {
        return iconsDirectory().resolve(appId + (dark ? "-dark.png" : "-light.png"));
    }

    public static boolean hasCustomIcon(String appId) {
        return Files.isRegularFile(icon(appId, false)) && Files.isRegularFile(icon(appId, true));
    }

    /** Render the picked app's current icon into the store. Returns false
     *  when the dylib can't render it. */
    public static boolean applyCustomIcon(String appId, Path appBundle) {
        Path light = icon(appId, false);
        Path dark = icon(appId, true);
        return DockIdentity.renderIconVariants( // core package: same package, direct ref
                appBundle.toString(), light.toString(), dark.toString());
    }

    public static void resetCustomIcon(String appId) {
        try { Files.deleteIfExists(icon(appId, false)); } catch (IOException ignored) {}
        try { Files.deleteIfExists(icon(appId, true)); } catch (IOException ignored) {}
    }

    /** Per-app icon environment for launched children. */
    public static Map<String, String> launchEnvironment(String appId, Mode mode) {
        if (!hasCustomIcon(appId)) return Map.of();
        return Map.of(
                "BIGM_PRESENTATION_ICON_LIGHT", icon(appId, false).toString(),
                "BIGM_PRESENTATION_ICON_DARK", icon(appId, true).toString(),
                "BIGM_PRESENTATION_ICON_MODE", mode.key());
    }

    // ── The launcher's own icon (mode persisted at Support/icons/launcher.mode) ──

    public static Mode launcherMode() {
        try {
            Path file = iconsDirectory().resolve("launcher.mode");
            if (Files.isRegularFile(file)) {
                return Mode.parse(Files.readString(file).trim());
            }
        } catch (IOException ignored) {
        }
        return Mode.SYSTEM;
    }

    public static void saveLauncherMode(Mode mode) {
        try {
            Files.writeString(iconsDirectory().resolve("launcher.mode"), mode.key());
        } catch (IOException ignored) {
        }
    }

    /** Apply (or reset) the running launcher's Dock icon live. */
    public static void applyLauncherIcon() {
        if (hasCustomIcon(LAUNCHER_ID)) {
            DockIdentity.setCustomIcon(icon(LAUNCHER_ID, false).toString(),
                    icon(LAUNCHER_ID, true).toString(), launcherMode().key());
        } else {
            DockIdentity.setCustomIcon(null, null, null);
        }
    }

    public static void resetLauncherIcon() {
        resetCustomIcon(LAUNCHER_ID);
        try { Files.deleteIfExists(iconsDirectory().resolve("launcher.mode")); }
        catch (IOException ignored) {}
        DockIdentity.setCustomIcon(null, null, null);
    }

    // ── System icon rendering for installed apps (cache under Support/icons/system) ──

    private static Path systemIconsDir() {
        Path dir = iconsDirectory().resolve("system");
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        return dir;
    }

    public static Path systemIcon(String appId, boolean dark) {
        return systemIconsDir().resolve(appId + (dark ? "-dark.png" : "-light.png"));
    }

    /** Render the real app icon of a resolved target (bundle or any path —
     *  NSWorkspace handles both; .app bundles yield their true icon) into
     *  the system-icon cache. Returns the dark-variant path when the render
     *  landed AND is non-empty (a zero-byte or missing file is the "blocked
     *  icon" the owner saw — a failed NSWorkspace render that left a stub). */
    public static Path renderSystemIcon(String appId, Path target) {
        if (target == null || !Files.exists(target)) return null;
        Path light = systemIcon(appId, false);
        Path dark = systemIcon(appId, true);
        try {
            Files.deleteIfExists(light);
            Files.deleteIfExists(dark);
        } catch (IOException ignored) {}
        boolean ok = DockIdentity.renderIconVariants(
                target.toString(), light.toString(), dark.toString());
        // Guard: a "successful" render that produced empty files still shows
        // a broken/blocked icon — verify both files exist AND have content.
        if (!ok) return null;
        try {
            if (!Files.isRegularFile(dark) || Files.size(dark) < 100) return null;
            if (!Files.isRegularFile(light) || Files.size(light) < 100) return null;
        } catch (IOException e) {
            return null;
        }
        return dark;
    }

    /** True when a VALID (non-empty) system icon exists for this app. */
    public static boolean hasSystemIcon(String appId) {
        Path dark = systemIcon(appId, true);
        try {
            return Files.isRegularFile(dark) && Files.size(dark) >= 100;
        } catch (IOException e) {
            return false;
        }
    }
}
