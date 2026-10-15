package core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The BigM launch-ticket contract (ported from the Swift AccessControl):
 * a fresh random HMAC key at Support/access.key (0600), and per launch a
 * nonce + HMAC-SHA256(key, nonce) pair exported as BIGM_LAUNCH_NONCE /
 * BIGM_LAUNCH_TICKET / BIGM_LAUNCH_KEY_PATH. Managed launchers (Rigel et
 * al.) verify the ticket through bigm_access_gate.h and refuse to start
 * outside a BigM process tree.
 */
public final class LaunchTicket {

    private static final Path KEY = Paths.support().resolve("access.key");
    private static final SecureRandom RANDOM = new SecureRandom();

    private LaunchTicket() {}

    /** The key file, created lazily and locked to the user. */
    private static byte[] ensureKey() throws IOException {
        if (Files.isRegularFile(KEY) && Files.size(KEY) > 0) {
            return Files.readAllBytes(KEY);
        }
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        Files.createDirectories(KEY.getParent());
        Files.write(KEY, key);
        try {
            Files.setPosixFilePermissions(KEY, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException ignored) {
        }
        return key;
    }

    /** Fresh per-launch ticket environment to merge into the child's env. */
    public static Map<String, String> environment() {
        try {
            byte[] key = ensureKey();
            byte[] nonce = new byte[16];
            RANDOM.nextBytes(nonce);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] ticket = mac.doFinal(nonce);
            // PADDED standard base64: the child gate (bigm_access_gate.h)
            // strict-decodes via NSData initWithBase64EncodedString with
            // options:0, which refuses unpadded input — an unpadded ticket
            // reads as "no ticket" and the child falls straight through to
            // the product-key prompt. (The 16-byte nonce would encode to 22
            // chars, ticket to 43 — both non-canonical lengths.)
            Base64.Encoder b64 = Base64.getEncoder();
            return Map.of(
                    "BIGM_LAUNCH_NONCE", b64.encodeToString(nonce),
                    "BIGM_LAUNCH_TICKET", b64.encodeToString(ticket),
                    "BIGM_LAUNCH_KEY_PATH", KEY.toString());
        } catch (Exception failure) {
            System.err.println("[bigm] could not issue launch ticket: " + failure.getMessage());
            return Map.of();
        }
    }
}
