package core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * User configuration lives at Support/config.properties (a simple Java
 * properties file, atomically written). Keeps per-app install-dir overrides,
 * any user-added custom apps (the Add App sheet's writes), the default
 * install folder, and the UI appearance choices (accent, background style).
 */
public final class LauncherConfig {

    private static final Path FILE = Paths.support().resolve("config.properties");
    private static final int MAX_CUSTOM_APPS = 64;

    private final Properties props = new Properties();

    public LauncherConfig() { load(); }

    private void load() {
        props.clear();
        if (!Files.isRegularFile(FILE)) return;
        try {
            if (Files.size(FILE) > 4L * 1024 * 1024) return;
            try (InputStream in = Files.newInputStream(FILE)) { props.load(in); }
        } catch (IOException | IllegalArgumentException failure) {
            props.clear();
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                props.store(out, "bigm-java configuration");
            }
            Files.move(tmp, FILE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            System.err.println("[bigm] could not save config: " + failure.getMessage());
        }
    }

    // ── Install locations ────────────────────────────────────────────────

    /** Per-app install dir override (set through Locate… / auto-detect). */
    public String installDir(String appId) {
        String stored = props.getProperty("installDir." + appId, "");
        return stored.isBlank() ? null : stored;
    }

    public void setInstallDir(String appId, String dir) {
        if (dir == null || dir.isBlank()) {
            props.remove("installDir." + appId);
        } else {
            props.setProperty("installDir." + appId, dir);
        }
        save();
    }

    /** The folder fresh installs land in (<folder>/<app id>); chosen on
     *  first install or in the Get Apps sheet. */
    public String defaultInstallFolder() {
        String stored = props.getProperty("installFolder", "");
        return stored.isBlank() ? null : stored;
    }

    public void setDefaultInstallFolder(String dir) {
        if (dir == null || dir.isBlank()) props.remove("installFolder");
        else props.setProperty("installFolder", dir);
        save();
    }

    // ── Appearance ───────────────────────────────────────────────────────

    /** The scenery theme: pine | summit | dusk | glacier | ember. (The key
     *  stays ui.accent for config continuity from the accent era.) Pine is
     *  the default: the forest-mountain identity. */
    public String uiAccent() { return props.getProperty("ui.accent", "pine"); }
    public void setUiAccent(String name) { props.setProperty("ui.accent", name); save(); }

    /** glass | aurora | solid */
    public String backgroundStyle() { return props.getProperty("ui.backgroundStyle", "glass"); }
    public void setBackgroundStyle(String style) { props.setProperty("ui.backgroundStyle", style); save(); }

    /** auto | day | night — the scenery light regime. Auto follows the
     *  host clock (day 07:00–19:00, night otherwise); day/night pin it. */
    public String uiVariant() { return props.getProperty("ui.variant", "auto"); }
    public void setUiVariant(String variant) { props.setProperty("ui.variant", variant); save(); }

    /** Per-app screenshot/screen-share protection (default ON, matching
     *  every hardened launcher's built-in default). */
    public boolean captureProtection(String appId) {
        return !"0".equals(props.getProperty("captureProtection." + appId, "1"));
    }

    public void setCaptureProtection(String appId, boolean on) {
        if (on) props.remove("captureProtection." + appId);
        else props.setProperty("captureProtection." + appId, "0");
        save();
    }

    /** Per-app custom-icon presentation mode: system | light | dark. */
    public String iconMode(String appId) {
        return props.getProperty("iconMode." + appId, "system");
    }

    public void setIconMode(String appId, String mode) {
        if (mode == null || mode.isBlank() || "system".equals(mode)) {
            props.remove("iconMode." + appId);
        } else {
            props.setProperty("iconMode." + appId, mode);
        }
        save();
    }

    // ── Custom apps (Add App / Add Website sheets) ───────────────────────

    /** Custom apps added by the user. Profiles are reconstructed separately
     *  through additionalProfilesFor. */
    public List<Catalog.AppDef> additionalApps() {
        List<Catalog.AppDef> out = new ArrayList<>();
        int count = readInt("additionalApps.count", 0);
        for (int i = 0; i < count && i < MAX_CUSTOM_APPS; i++) {
            String id = props.getProperty("additionalApps." + i + ".id", "");
            if (id.isBlank()) continue;
            out.add(new Catalog.AppDef(
                    id,
                    props.getProperty("additionalApps." + i + ".repository", ""),
                    props.getProperty("additionalApps." + i + ".assetPattern", ""),
                    props.getProperty("additionalApps." + i + ".marker", ""),
                    strings(props.getProperty("additionalApps." + i + ".preserve", "")),
                    props.getProperty("additionalApps." + i + ".summary", ""),
                    props.getProperty("additionalApps." + i + ".displayName", id),
                    props.getProperty("additionalApps." + i + ".category", "games"),
                    props.getProperty("additionalApps." + i + ".tintHex", "#6C7CFF"),
                    props.getProperty("additionalApps." + i + ".symbol", "app.fill")));
        }
        return out;
    }

    /** The single launch profile stored for a user-added app, if any. */
    public List<Catalog.Profile> additionalProfilesFor(String appId) {
        int count = readInt("additionalApps.count", 0);
        for (int i = 0; i < count && i < MAX_CUSTOM_APPS; i++) {
            if (!appId.equals(props.getProperty("additionalApps." + i + ".id", ""))) continue;
            String target = props.getProperty("additionalApps." + i + ".target", "");
            if (target.isBlank()) return List.of();
            return List.of(new Catalog.Profile(
                    "default", "Default",
                    props.getProperty("additionalApps." + i + ".backend", "script"),
                    target,
                    List.of(),
                    lines(props.getProperty("additionalApps." + i + ".args", "")),
                    Map.of(),
                    null));
        }
        return List.of();
    }

    public void addApp(Catalog.AppDef app, Catalog.Profile profile) {
        int count = readInt("additionalApps.count", 0);
        if (count >= MAX_CUSTOM_APPS) return;
        String prefix = "additionalApps." + count + ".";
        props.setProperty(prefix + "id", app.id());
        props.setProperty(prefix + "repository", app.repository() == null ? "" : app.repository());
        props.setProperty(prefix + "assetPattern", app.assetPattern() == null ? "" : app.assetPattern());
        props.setProperty(prefix + "marker", app.marker() == null ? "" : app.marker());
        props.setProperty(prefix + "preserve", String.join(",", app.preserve()));
        props.setProperty(prefix + "summary", app.summary() == null ? "" : app.summary());
        props.setProperty(prefix + "displayName", app.displayName() == null ? app.id() : app.displayName());
        props.setProperty(prefix + "category", app.category() == null ? "games" : app.category());
        props.setProperty(prefix + "tintHex", app.tintHex() == null ? "#6C7CFF" : app.tintHex());
        props.setProperty(prefix + "symbol", app.symbol() == null ? "app.fill" : app.symbol());
        if (profile != null) {
            props.setProperty(prefix + "backend", profile.backend() == null ? "script" : profile.backend());
            props.setProperty(prefix + "target", profile.target() == null ? "" : profile.target());
            props.setProperty(prefix + "args", String.join("\n", profile.args()));
        }
        props.setProperty("additionalApps.count", Integer.toString(count + 1));
        save();
    }

    private int readInt(String key, int fallback) {
        try { return Integer.parseInt(props.getProperty(key, String.valueOf(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static List<String> strings(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            String clean = part.trim();
            if (!clean.isEmpty()) out.add(clean);
        }
        return out;
    }

    /** Lines survive Properties round-trips (stored escaped as \n). */
    private static List<String> lines(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split("\n")) {
            String clean = part.trim();
            if (!clean.isEmpty()) out.add(clean);
        }
        return out;
    }

    // ── Favorites (pin apps to top with a star) ─────────────────────────

    public boolean isFavorite(String appId) {
        return "true".equals(props.getProperty("app." + appId + ".favorite"));
    }

    public void setFavorite(String appId, boolean fav) {
        if (fav) props.setProperty("app." + appId + ".favorite", "true");
        else props.remove("app." + appId + ".favorite");
        save();
    }

    // ── App notes (user-attached tag shown on card) ─────────────────────

    public String appNote(String appId) {
        String note = props.getProperty("app." + appId + ".note");
        return note == null || note.isBlank() ? null : note;
    }

    public void setAppNote(String appId, String note) {
        if (note == null || note.isBlank()) props.remove("app." + appId + ".note");
        else props.setProperty("app." + appId + ".note", note);
        save();
    }
}
