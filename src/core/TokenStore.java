package core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * The GitHub token the updater uses, at Support/github-token (0600). Anyone
 * running the launcher may paste a PAT with Contents-read on the private
 * app repos; without one the private catalog answers 404 for them.
 */
public final class TokenStore {

    public static String get() {
        Path file = Paths.support().resolve("github-token");
        try {
            if (!Files.isRegularFile(file)) return null;
            String text = Files.readString(file, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : text;
        } catch (IOException ignored) {
            return null;
        }
    }

    public static void set(String token) throws IOException {
        Path file = Paths.support().resolve("github-token");
        String trimmed = token == null ? "" : token.trim();
        if (trimmed.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Files.writeString(file, trimmed, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(file,
                    PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX FS: the launcher folder still stands alone.
        }
    }

    public static boolean present() { return get() != null; }

    private TokenStore() {}
}
