package core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Folder auto-detect, shared by every face (legacy SWT sheet and the
 * SwiftUI library scan): walk a chosen folder and find each catalog app's
 * install by its launch markers, so "point at a directory" wires the
 * library without hand-entry. Old-launcher parity plus loose matches for
 * run*.sh wrappers and .app bundles; never rewrites installs that already
 * resolve.
 */
public final class ScanEngine {

    public record Hit(String appId, Path installDir, String via) {}

    /** Scan {@code root} for every not-already-installed app. */
    public static List<Hit> scan(Path root, List<Catalog.AppDef> apps, ProfileProvider profiles,
                                 InstalledQuery installed) {
        List<Hit> hits = new ArrayList<>();
        for (Catalog.AppDef app : apps) {
            if (installed.isInstalled(app.id())) continue;
            Path found = findMarker(root, app, profiles.profilesFor(app));
            if (found != null) hits.add(new Hit(app.id(), found, found.getFileName() + ""));
        }
        return hits;
    }

    public interface ProfileProvider { List<Catalog.Profile> profilesFor(Catalog.AppDef app); }
    public interface InstalledQuery { boolean isInstalled(String appId); }

    /** Find an app's install inside a picked folder by (a) its marker file,
     *  (b) any candidate's basename (run-rigel.sh, moonlight_launch.py…),
     *  (c) a directory named after the app id containing a run*.sh, or
     *  (d) any "<Id>.app" bundle. Deepest-safe: 5 levels, case-insensitive.
     *  Returns the parent dir to wire as the install location. */
    public static Path findMarker(Path root, Catalog.AppDef app, List<Catalog.Profile> profiles) {
        java.util.Set<String> names = new java.util.HashSet<>();
        addName(names, app.marker());
        for (Catalog.Profile profile : profiles) {
            addName(names, basenameOf(profile.target()));
            for (String candidate : profile.candidates()) addName(names, basenameOf(candidate));
        }
        String idLower = app.id().toLowerCase();
        if (names.isEmpty() && idLower.isBlank()) return null;

        // Pass 1: exact marker/candidate filename match, case-insensitive
        try (Stream<Path> walk = Files.walk(root, 5)) {
            Path hit = walk.filter(Files::isRegularFile)
                    .filter(p -> names.contains(p.getFileName().toString().toLowerCase()))
                    .findFirst()
                    .map(Path::getParent)
                    .orElse(null);
            if (hit != null) return hit;
        } catch (IOException e) {
            // continue to dir-name passes
        }
        // Pass 2: a folder named after the app id (any case) holding a
        // runnable — the way the old Python scanner found launchers.
        try (Stream<Path> walk = Files.walk(root, 4)) {
            Path hitDir = walk.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().toLowerCase().contains(idLower))
                    .filter(p -> containsLauncher(p, idLower))
                    .findFirst()
                    .orElse(null);
            if (hitDir != null) return hitDir;
        } catch (IOException ignored) {
        }
        // Pass 3: "<App>.app" bundle anywhere (case-insensitive contains).
        try (Stream<Path> walk = Files.walk(root, 4)) {
            return walk.filter(Files::isDirectory)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".app") && n.contains(idLower);
                    })
                    .map(Path::getParent)
                    .findFirst()
                    .orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean containsLauncher(Path dir, String idLower) {
        try (Stream<Path> kids = Files.list(dir)) {
            return kids.anyMatch(k -> {
                String n = k.getFileName().toString().toLowerCase();
                return (n.startsWith("run") && n.endsWith(".sh"))
                        || n.equals("run.sh")
                        || n.endsWith(".app")
                        || n.contains(idLower) && n.endsWith(".py");
            });
        } catch (IOException e) {
            return false;
        }
    }

    private static void addName(java.util.Set<String> names, String raw) {
        if (raw != null && !raw.isBlank()) names.add(raw.trim().toLowerCase());
    }

    private static String basenameOf(String path) {
        if (path == null || path.isBlank()) return null;
        int slash = path.replace('\\', '/').lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private ScanEngine() {}
}
