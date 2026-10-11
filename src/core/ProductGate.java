package core;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Universal product-key gate, ported from BigM's AccessControl: one-time
 * activation per machine account. The marker lives in the login keychain
 * (service com.frombigm.product-activation, account universal); a copied
 * portable folder can never carry a machine's activation, which is the
 * point. Keychain access goes through the `security` CLI — no native code
 * needed for read/write of a generic password in the user's own login
 * keychain.
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

    public static boolean isActivated() {
        try {
            Process process = new ProcessBuilder(
                    "/usr/bin/security", "find-generic-password",
                    "-s", SERVICE, "-a", ACCOUNT, "-w")
                    .redirectErrorStream(true).start();
            // The CLI can wait on a GUI approval prompt for an existing-but
            // -ACL-locked item; a hung keychain call must never hang us.
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            process.getInputStream().readAllBytes();
            return process.exitValue() == 0;
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

    /** Persist activation: the marker nonces live in the login keychain so
     *  copying the folder elsewhere cannot port a machine's activation. */
    public static boolean activate(String keyCandidate) {
        if (!verify(keyCandidate)) return false;
        try {
            new ProcessBuilder("/usr/bin/security", "add-generic-password",
                    "-s", SERVICE, "-a", ACCOUNT, "-w", "1", "-U")
                    .redirectErrorStream(true).start().waitFor();
            return true;
        } catch (IOException | InterruptedException failure) {
            return false;
        }
    }

    private ProductGate() {}
}
