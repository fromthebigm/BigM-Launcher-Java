package core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Pure-Java replacement for the icon-rendering leg of bigm_identity.dylib.
 *
 * The dylib used NSWorkspace under forced NSAppearances to produce two
 * 512x512 PNGs (light/dark) from a picked .app or file. This class uses only
 * system command-line tools that ship with macOS (python3 plist parsing,
 * sips, qlmanage) — no custom native library, no JNI/FFM, no temp dylib.
 * Themed dark/light variants cannot be forced through those tools, so both
 * outputs are the same rendered image; mode selection still works because
 * the consumer picks a file by mode, and children receive the env contract.
 */
public final class JavaIconRenderer {

    private JavaIconRenderer() {}

    /** Render source (an .app bundle or any file) into light + dark PNGs. */
    public static boolean render(String source, Path lightOut, Path darkOut) {
        if (source == null || source.isBlank()) return false;
        Path src = Path.of(source);
        if (!Files.exists(src)) return false;

        Path tmp;
        try {
            tmp = Files.createTempDirectory("bigm-icon-");
        } catch (IOException e) {
            return false;
        }
        try {
            Path iconSource = resolveIconSource(src, tmp);
            if (iconSource == null) return false;

            Path png = renderPng(iconSource, src, tmp);
            if (png == null || !Files.isRegularFile(png) || Files.size(png) < 100) return false;

            Files.createDirectories(lightOut.getParent());
            Files.createDirectories(darkOut.getParent());
            Files.copy(png, lightOut, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(png, darkOut, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        } finally {
            deleteTree(tmp);
        }
    }

    /** For .app bundles, find CFBundleIconFile (or CFBundleIconName) and
     *  return the .icns inside Contents/Resources; otherwise return source. */
    private static Path resolveIconSource(Path src, Path tmp) {
        if (!Files.isDirectory(src) || !src.toString().toLowerCase(Locale.ROOT).endsWith(".app")) {
            return src;
        }
        Path info = src.resolve("Contents/Info.plist");
        if (!Files.isRegularFile(info)) return src;

        String iconName = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "/usr/bin/python3", "-c",
                    "import plistlib,sys;" +
                    "d=plistlib.load(open(sys.argv[1],'rb'));" +
                    "print(d.get('CFBundleIconFile') or d.get('CFBundleIconName') or '')",
                    info.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (!out.isBlank()) iconName = out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        if (iconName == null || iconName.isBlank()) return src;
        if (!iconName.toLowerCase(Locale.ROOT).endsWith(".icns")) iconName += ".icns";
        Path candidate = src.resolve("Contents/Resources").resolve(iconName);
        return Files.isRegularFile(candidate) ? candidate : src;
    }

    private static Path renderPng(Path iconSource, Path original, Path tmp)
            throws IOException, InterruptedException {
        Path out = tmp.resolve("icon.png");
        if (Files.isRegularFile(iconSource)) {
            ProcessBuilder sips = new ProcessBuilder(
                    "/usr/bin/sips", "-s", "format", "png", "-Z", "512",
                    iconSource.toString(), "--out", out.toString());
            sips.redirectErrorStream(true);
            Process p = sips.start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() == 0 && Files.isRegularFile(out) && Files.size(out) > 100) return out;
        }
        // Fallback: QuickLook thumbnail generator (system tool, no custom dylib).
        Path qlDir = tmp.resolve("ql");
        Files.createDirectories(qlDir);
        ProcessBuilder ql = new ProcessBuilder(
                "/usr/bin/qlmanage", "-t", "-s", "512", "-o", qlDir.toString(),
                original.toString());
        ql.redirectErrorStream(true);
        Process q = ql.start();
        q.getInputStream().readAllBytes();
        q.waitFor();
        try (Stream<Path> files = Files.list(qlDir)) {
            return files.filter(f -> f.toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .filter(f -> {
                        try { return Files.size(f) > 100; } catch (IOException e) { return false; }
                    })
                    .findFirst().orElse(null);
        }
    }

    private static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {
        }
    }
}
