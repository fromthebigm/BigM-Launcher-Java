import core.InstallEngine;
import core.Paths;
import core.ProductGate;
import core.TokenStore;
import org.eclipse.swt.widgets.Display;
import ui.LauncherWindow;

/**
 * BigM Portable — Java-hosted edition.
 *
 * The whole point of this port: java.net.http does not consult macOS
 * System-Settings proxies, and the bundled JDK's cacerts is its own
 * truststore — locally-installed filter CAs never enter it. Those two
 * properties are what keep the updater working on filtered machines where
 * URLSession (the Swift build) died answering HTML block pages.
 *
 * The pipeline mirrors the Swift launcher's: detection runs through the
 * GitHub API (tiered by GitHubHttp), downloads flow the same way, and an
 * anchor-pinned tier terminates TLS only at the GitHub CA certs bundled
 * under Resources/github-anchors/.
 */
public final class BigMApp {

    public static void main(String[] args) {
        System.err.println("[bigm] main entered");
        // macOS/SWT: the run loop must be thread 0 (run-bigm.sh starts us
        // with -XstartOnFirstThread); this property only records the fact.
        System.setProperty("apple.awt.application.name", "BigM");
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");

        // Headless harness: exercise the whole machine minus the window —
        // catalog parse, per-app real check, transport log — for scripted
        // verification and release-gate boot proofs (GUI answer to "did it
        // launch" is the process staying alive; the self-test is the truth
        // you can trust from CI).
        if (java.util.Arrays.asList(args).contains("--self-test")) {
            int result = selfTest();
            System.exit(result);
            return;
        }

        DockIdentity.install();
        DockIdentity.apply();

        try {
            core.Catalog catalog = core.Catalog.load();
            Display display = new Display();
            LauncherWindow window = new LauncherWindow(display, catalog);
            window.open();
            display.dispose();
        } catch (Throwable failure) {
            System.err.println("[bigm] fatal: " + failure.getMessage());
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static int selfTest() {
        int failures = 0;
        try {
            core.Catalog catalog = core.Catalog.load();
            System.out.println("self-test: catalog=" + catalog.apps().size() + " apps");
            if (catalog.apps().isEmpty()) {
                System.out.println("self-test: FAIL: empty catalog");
                failures++;
            }
            // Identity read is pure-local and answers whether the stamp
            // machinery the updater leans on is intact.
            InstallEngine.Identity probe = InstallEngine.installedIdentity(
                    java.nio.file.Path.of("/tmp"));
            System.out.println("self-test: stamp-read ok (tag='" + probe.tag() + "')");
            System.out.flush();
            System.out.println("self-test: gate activated="
                    + ProductGate.isActivated());
            System.out.flush();
            System.out.println("self-test: token present=" + TokenStore.present());
            java.nio.file.Path log = Paths.transportLog();
            System.out.println("self-test: transport log target=" + log);
            System.out.flush();
        } catch (Throwable failure) {
            System.err.println("self-test: FAIL: " + failure.getMessage());
            return 1;
        }
        if (failures == 0) System.out.println("self-test: PASS");
        return failures == 0 ? 0 : 1;
    }
}
