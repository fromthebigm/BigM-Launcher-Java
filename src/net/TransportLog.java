package net;

import core.Paths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * One line per transport event appended to Support/update-transport.log. On
 * an intercepted machine this file is the report: which tier answered, what
 * answered (status, forged chain, HTML block page), instead of a bare "the
 * data isn't in the correct format". Capped small; never logs tokens.
 */
public final class TransportLog {

    private static final long MAX_BYTES = 256 * 1024;

    public static synchronized void log(String message) {
        try {
            Path file = Paths.transportLog();
            String line = Instant.now() + " " + message + "\n";
            byte[] bytes = line.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            long size = Files.exists(file) ? Files.size(file) : 0;
            if (size + bytes.length > MAX_BYTES && Files.exists(file)) {
                byte[] existing = Files.readAllBytes(file);
                int keepFrom = Math.max(0, existing.length - (int) (MAX_BYTES / 2));
                Files.write(file, java.util.Arrays.copyOfRange(existing, keepFrom, existing.length));
            }
            Files.write(file, bytes,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Logging must never break the transport it documents.
        }
    }

    private TransportLog() {}
}
