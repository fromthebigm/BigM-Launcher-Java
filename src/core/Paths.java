package core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Portable-folder resolution for the Java-hosted launcher. Mirrors Rigel's
 * discipline: everything user-owned lives under the package folder itself,
 * never in the real home. `bigm.root` overrides for harness tests.
 */
public final class Paths {

    public static Path root() {
        String override = System.getProperty("bigm.root");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        List<Path> candidates = new ArrayList<>();
        try {
            var codeSource = Paths.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                Path location = Path.of(codeSource.getLocation().toURI())
                        .toAbsolutePath().normalize();
                if (Files.isRegularFile(location)) {
                    // jar at target/bigm.jar → root is target/'s parent.
                    Path parent = location.getParent();
                    if (parent != null && parent.getFileName() != null
                            && "target".equals(parent.getFileName().toString())
                            && parent.getParent() != null) {
                        candidates.add(parent.getParent());
                    }
                    if (parent != null) candidates.add(parent);
                }
                // classes-dir layouts (build/classes, out/classes):
                if (location.getFileName() != null
                        && "classes".equals(location.getFileName().toString())
                        && location.getParent() != null) {
                    candidates.add(location.getParent());
                }
            }
        } catch (Exception ignored) {
        }
        candidates.add(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize());
        for (Path candidate : candidates) {
            if (candidate != null
                    && Files.isRegularFile(candidate.resolve("Resources/default-catalog.json"))) {
                return candidate;
            }
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    public static Path support() {
        Path dir = root().resolve("Support");
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
        }
        return dir;
    }

    public static Path portableData() {
        Path dir = root().resolve("portable-data");
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
        }
        return dir;
    }

    public static Path profile() {
        Path dir = portableData().resolve("profile");
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
        }
        return dir;
    }

    public static Path resources() {
        return root().resolve("Resources");
    }

    public static Path transportLog() {
        return support().resolve("update-transport.log");
    }

    /** Real machine home, captured before run.sh redirects HOME. */
    public static Path realHome() {
        String real = System.getenv("BIGM_JAVA_REAL_HOME");
        if (real != null && !real.isBlank()) return Path.of(real);
        return Path.of(System.getProperty("user.home"));
    }

    private Paths() {}
}
