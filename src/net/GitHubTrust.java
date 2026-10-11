package net;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * The Java-truststore tier for GitHub traffic: an SSLContext whose only
 * trusted anchors are the GitHub CA certificates bundled under
 * Resources/github-anchors/. macOS's keychain (with any locally-installed
 * filter CA) is never consulted — same immunity property as Rigel's bundled
 * runtime, without URLSession/Cocoa in the picture at all.
 *
 * <p>Rotation discipline: the bundle holds GitHub's CURRENT intermediates
 * and roots (Sectigo line for api.github.com, Let's Encrypt/ISRG for the
 * Fastly CDN hosts). Leaf rotation (every few months) is unaffected — only
 * a full CA migration by GitHub breaks this tier, and other tiers keep it
 * non-fatal.</p>
 */
public final class GitHubTrust {

    private static volatile TrustManager[] pinnedManagers;

    /** Trust managers that accept only chains terminating at the bundled
     *  GitHub anchors. Cached after first load. */
    public static synchronized TrustManager[] pinnedTrustManagers() {
        if (pinnedManagers != null) return pinnedManagers;
        List<X509Certificate> anchors = loadAnchors();
        if (anchors.isEmpty()) {
            // No bundle = do NOT open the trapdoor: fail closed and let the
            // caller's error say the anchors were never shipped.
            pinnedManagers = new TrustManager[] { new RejectAllTrustManager() };
            return pinnedManagers;
        }
        KeyStore store;
        try {
            store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int index = 0;
            for (X509Certificate anchor : anchors) {
                store.setCertificateEntry("github-anchor-" + (index++), anchor);
            }
            javax.net.ssl.TrustManagerFactory factory = javax.net.ssl.TrustManagerFactory
                    .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            TrustManager[] managers = factory.getTrustManagers();
            for (TrustManager manager : managers) {
                if (manager instanceof X509TrustManager) {
                    pinnedManagers = new TrustManager[] { manager };
                    return pinnedManagers;
                }
            }
        } catch (GeneralSecurityException | IOException failure) {
            pinnedManagers = new TrustManager[] { new RejectAllTrustManager() };
            return pinnedManagers;
        }
        pinnedManagers = new TrustManager[] { new RejectAllTrustManager() };
        return pinnedManagers;
    }

    /** SSLContext wrapping just the pinned anchors. */
    public static SSLContext pinnedContext() {
        TrustManager[] managers = pinnedTrustManagers();
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers, new SecureRandom());
            return context;
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("no TLS context", failure);
        }
    }

    static List<X509Certificate> loadAnchors() {
        Path dir = anchorsDir();
        if (dir == null) return List.of();
        List<X509Certificate> anchors = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!file.getFileName().toString().endsWith(".der")) continue;
                try {
                    CertificateFactory factory = CertificateFactory.getInstance("X.509");
                    anchors.add((X509Certificate) factory.generateCertificate(
                            new ByteArrayInputStream(Files.readAllBytes(file))));
                } catch (Exception perFile) {
                    System.err.println("[bigm] anchor unreadable: " + file.getFileName()
                            + " (" + perFile.getMessage() + ")");
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        return anchors;
    }

    private static Path anchorsDir() {
        // Resolution must mirror how launchers find Resources: BIGM root from
        // the -Dbigm.root system property first, then the jar's parent dir.
        String rootOverride = System.getProperty("bigm.root");
        List<Path> candidates = new ArrayList<>();
        if (rootOverride != null && !rootOverride.isBlank()) {
            candidates.add(Path.of(rootOverride).resolve("Resources").resolve("github-anchors"));
        }
        try {
            var codeSource = GitHubTrust.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                Path jarPath = Path.of(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
                Path classes = Files.isDirectory(jarPath) ? jarPath : jarPath.getParent();
                if (classes != null) {
                    candidates.add(classes.getParent().resolve("Resources").resolve("github-anchors"));
                    candidates.add(classes.resolve("github-anchors"));
                }
            }
        } catch (Exception ignored) {
        }
        for (Path candidate : candidates) {
            if (candidate != null && Files.isDirectory(candidate)) return candidate;
        }
        return null;
    }

    /** Fail-closed trust manager used when the bundle is missing. */
    private static final class RejectAllTrustManager implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException("bigm: no bundled GitHub anchors");
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException("bigm: no bundled GitHub anchors");
        }

        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    private GitHubTrust() {}
}
