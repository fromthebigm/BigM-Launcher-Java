package core;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Universal product-key gate, ported from BigM's AccessControl: one-time
 * activation per machine. The marker story is deliberately prompt-free:
 *
 * - Reads at boot never touch the keychain (any /usr/bin/security read of
 *   an item ACL'd to another process pops macOS's "unlock / allow access"
 *   sheet — THE recurring password dialog this gate kept producing). Boot
 *   reads Support/activation-receipt instead: HMAC-SHA256 of this Mac's
 *   IOPlatformSerialNumber under the factory material. A portable folder
 *   copied to another machine carries a receipt that fails locally, so
 *   activation remains per-machine — the same barrier, zero dialogs.
 *
 * - activate() verifies the key, writes the receipt (0600), and ALSO drops
 *   the legacy login-keychain marker best-effort (parity with the Swift
 *   launcher's machine story; failures here never block activation).
 */
public final class ProductGate {

    private static final String SERVICE = "com.frombigm.product-activation";
    private static final String ACCOUNT = "universal";

    // The universal factory key material (PBKDF2-HMAC-SHA256, 600k rounds) —
    // same constants every FromBigM launcher shares, so one key unlocks all.
    private static final byte[] FACTORY_SALT =
            Base64.getDecoder().decode("EHJhQkWAEfhypNC5zyqBJA==");
    private static final byte[] FACTORY_HASH =
            Base64.getDecoder().decode("atFbDlniDszy/I5hPOFnMrTB9GsU379uUbvpI3u/ACg=");
    private static final int FACTORY_ITERATIONS = 600_000;

    private static final Path RECEIPT =
            Paths.support().resolve("activation-receipt");

    /** Boot path — reads ONLY the local receipt file. Never pops a dialog,
     *  never blocks. If the receipt is absent, migrate the legacy keychain
     *  marker (one silent read through the default search list; a foreign-
     *  ACL'd item can't prompt under -T discipline, and the only write is
     *  at the user's explicit activation). */
    public static boolean isActivated() {
        try {
            if (Files.isRegularFile(RECEIPT)) {
                String stored = Files.readString(RECEIPT, StandardCharsets.UTF_8).trim();
                String expected = receipt();
                if (expected != null && MessageDigest.isEqual(
                        stored.getBytes(StandardCharsets.UTF_8),
                        expected.getBytes(StandardCharsets.UTF_8))) {
                    return true;
                }
                return false; // stale receipt (copied folder) — re-key
            }
        } catch (IOException failure) {
            return false;
        }
        return migrateLegacyKeychainMarker();
    }

    /** One silent probe for the legacy login-keychain marker; on hit, write
     *  the receipt (activation upgrades itself locally, no dialog, no key).
     *  On lock/ACL/absence: quietly not-activated, key prompt handles it. */
    private static boolean migrateLegacyKeychainMarker() {
        try {
            ProcessBuilder builder = new ProcessBuilder("/usr/bin/security",
                    "find-generic-password", "-s", SERVICE, "-a", ACCOUNT, "-w");
            builder.environment().put("HOME", Paths.realHome().toString());
            Process process = builder.redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            if (process.exitValue() != 0) return false;
            String receipt = receipt();
            if (receipt == null) return true; // found marker; can't bind yet
            try {
                Files.createDirectories(RECEIPT.getParent());
                Files.writeString(RECEIPT, receipt, StandardCharsets.UTF_8);
                Files.setPosixFilePermissions(RECEIPT,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (IOException | UnsupportedOperationException ignored) {
            }
            return true;
        } catch (IOException | InterruptedException failure) {
            return false;
        }
    }

    /** Verify a candidate key against the factory material. */
    public static boolean verify(String keyCandidate) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    keyCandidate.toCharArray(), FACTORY_SALT,
                    FACTORY_ITERATIONS, FACTORY_HASH.length * 8);
            byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            spec.clearPassword();
            return MessageDigest.isEqual(derived, FACTORY_HASH);
        } catch (Exception failure) {
            return false;
        }
    }

    /** Persist activation. Returns null on success; on failure the message
     *  names what actually happened instead of dying silently. */
    public static String activate(String keyCandidate) {
        if (!verify(keyCandidate)) return "That key did not verify. Try again.";
        String receipt = receipt();
        if (receipt == null) {
            return "Could not bind activation to this Mac (no platform serial). "
                    + "Connect the hardware serial or try again after a restart.";
        }
        try {
            Files.createDirectories(RECEIPT.getParent());
            Files.writeString(RECEIPT, receipt, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(RECEIPT,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
            }
        } catch (IOException failure) {
            return "Could not save activation: " + failure.getMessage();
        }
        writeKeychainMarkerBestEffort();
        return null;
    }

    // ── Receipt internals ────────────────────────────────────────────────

    /** HMAC-SHA256(factory hash, platform serial) in base64. */
    private static String receipt() {
        try {
            String serial = platformSerial();
            if (serial == null || serial.isBlank()) return null;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(FACTORY_HASH, "HmacSHA256"));
            byte[] out = mac.doFinal(("bigm-activation|" + serial)
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception failure) {
            return null;
        }
    }

    /** IOPlatformSerialNumber via ioreg — read-only system tool, no
     *  entitlements, no prompts, stable per physical Mac. */
    private static String platformSerial() {
        try {
            Process process = new ProcessBuilder("/usr/sbin/ioreg",
                    "-rd1", "-c", "IOPlatformExpertDevice")
                    .redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            for (String line : out.split("\n")) {
                int idx = line.indexOf("IOPlatformSerialNumber");
                if (idx < 0) continue;
                int q1 = line.indexOf('"', line.indexOf('=', idx));
                int q2 = q1 < 0 ? -1 : line.indexOf('"', q1 + 1);
                if (q2 > q1) return line.substring(q1 + 1, q2);
            }
            return null;
        } catch (IOException | InterruptedException failure) {
            return null;
        }
    }

    // ── Legacy keychain marker (best-effort parity with the Swift gate) ──

    private static void writeKeychainMarkerBestEffort() {
        try {
            ProcessBuilder builder = new ProcessBuilder("/usr/bin/security",
                    "add-generic-password",
                    "-s", SERVICE, "-a", ACCOUNT, "-w", "1", "-U",
                    "-T", "/usr/bin/security");
            builder.environment().put("HOME", Paths.realHome().toString());
            Process process = builder.redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    // ── Manual keychain care (Settings sheet only; never on boot path) ───

    /** Machine keychain sanely answerable? Returns null when fine, else the
     *  raw `security` answer. Only call from user-initiated Settings UI. */
    public static String keychainProblem() {
        try {
            Process process = new ProcessBuilder("/usr/bin/security",
                    "show-keychain-info", defaultLoginKeychain().toString())
                    .redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "keychain query timed out (locked?)";
            }
            if (process.exitValue() != 0) return out.isBlank() ? "exit " + process.exitValue() : out.strip();
            return null;
        } catch (IOException | InterruptedException failure) {
            return failure.getMessage();
        }
    }

    /** Attempt to unlock the login keychain with the user's password —
     *  only ever run when the user types it into the Settings sheet. */
    public static String tryUnlock(String password) {
        try {
            Process process = new ProcessBuilder("/usr/bin/security", "unlock-keychain",
                    "-p", password, defaultLoginKeychain().toString())
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "unlock timed out";
            }
            return process.exitValue() == 0 ? null : "unlock failed";
        } catch (IOException | InterruptedException failure) {
            return failure.getMessage();
        }
    }

    private static Path defaultLoginKeychain() {
        return Paths.realHome().resolve("Library/Keychains/login.keychain-db");
    }

    private ProductGate() {}
}
