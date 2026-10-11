package ui;

import core.Catalog;
import core.InstallEngine;
import core.Paths;
import core.ProductGate;
import core.TokenStore;
import net.GitHubUpdates;
import net.TransportLog;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The launcher window: a row per catalog app with its installed tag, status,
 * and Install/Update/Open actions, plus a transport log strip (the answers
 * Support/update-transport.log answers the same questions for a failing
 * machine later).
 */
public final class LauncherWindow {

    private final Display display;
    private final Catalog catalog;
    private final Shell shell;
    private final Composite rowsParent;
    private final Text logView;
    private final ExecutorService workers = Executors.newFixedThreadPool(2,
            daemonFactory());
    private final List<Row> rows = new ArrayList<>();

    public LauncherWindow(Display display, Catalog catalog) {
        this.display = display;
        this.catalog = catalog;
        shell = new Shell(display, SWT.SHELL_TRIM);
        shell.setText("BigM");
        shell.setSize(860, 640);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 14;
        layout.marginHeight = 12;
        layout.verticalSpacing = 10;
        shell.setLayout(layout);

        Composite header = new Composite(shell, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout headerLayout = new GridLayout(4, false);
        headerLayout.marginWidth = 0;
        headerLayout.horizontalSpacing = 10;
        header.setLayout(headerLayout);

        Label title = new Label(header, SWT.NONE);
        title.setText("BigM Portable (Java)");
        title.setFont(new Font(display, display.getSystemFont().getFontData()[0].getName(), 16, SWT.BOLD));
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button checkAll = new Button(header, SWT.PUSH);
        checkAll.setText("Check All");
        checkAll.addListener(SWT.Selection, e -> checkAll());
        Button settings = new Button(header, SWT.PUSH);
        settings.setText("Settings…");
        settings.addListener(SWT.Selection, e -> showSettings());
        Label tokenState = new Label(header, SWT.NONE);
        tokenState.setText(TokenStore.present() ? "token: saved" : "token: missing");
        tokenState.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));

        ScrolledComposite scroller = new ScrolledComposite(shell, SWT.V_SCROLL | SWT.BORDER);
        scroller.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scroller.setExpandHorizontal(true);
        scroller.setExpandVertical(false);
        rowsParent = new Composite(scroller, SWT.NONE);
        rowsParent.setLayout(new GridLayout(1, false));
        scroller.setContent(rowsParent);

        for (Catalog.AppDef app : catalog.apps()) {
            rows.add(buildRow(app));
        }
        rowsParent.layout(true, true);
        scroller.setMinSize(rowsParent.computeSize(SWT.DEFAULT, SWT.DEFAULT));

        Label logHeader = new Label(shell, SWT.NONE);
        logHeader.setText("Transport log");
        logHeader.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        logView = new Text(shell, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.BORDER);
        GridData logData = new GridData(SWT.FILL, SWT.BOTTOM, true, false);
        logData.heightHint = 110;
        logView.setLayoutData(logData);

        shell.addDisposeListener(e -> workers.shutdownNow());
        reloadLog();
    }

    private Row buildRow(Catalog.AppDef app) {
        Composite row = new Composite(rowsParent, SWT.BORDER);
        row.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout rl = new GridLayout(6, false);
        rl.marginWidth = 10;
        rl.marginHeight = 8;
        rl.horizontalSpacing = 10;
        row.setLayout(rl);

        Label name = new Label(row, SWT.NONE);
        name.setText(app.displayName());
        name.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        Label installed = new Label(row, SWT.NONE);
        IdentityHolder id = readIdentity(app);
        installed.setText(id.tag == null || id.tag.isEmpty() ? "not installed" : id.tag);
        installed.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        Label latest = new Label(row, SWT.NONE);
        latest.setText("checking…");
        latest.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label status = new Label(row, SWT.NONE);
        status.setText("");
        status.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        Button check = new Button(row, SWT.PUSH);
        check.setText("Check");
        check.addListener(SWT.Selection, e -> check(app));

        Button action = new Button(row, SWT.PUSH);
        action.setText("Install");
        action.addListener(SWT.Selection, e -> install(app));

        Button open = new Button(row, SWT.PUSH);
        open.setText("Open");
        open.addListener(SWT.Selection, e -> openApp(app));

        Row model = new Row(name, installed, latest, status, check, action, open);
        return model;
    }

    /** Per-row handles the window keeps so worker threads can update it. */
    private static final class Row {
        final Label name;
        final Label installed;
        final Label latest;
        final Label status;
        final Button check;
        final Button action;
        final Button open;
        Row(Label name, Label installed, Label latest, Label status,
            Button check, Button action, Button open) {
            this.name = name; this.installed = installed; this.latest = latest;
            this.status = status; this.check = check; this.action = action; this.open = open;
        }
    }

    private record IdentityHolder(String tag, String builtAt) {}

    private IdentityHolder readIdentity(Catalog.AppDef app) {
        Path dir = installDirFor(app);
        if (dir == null) return new IdentityHolder(null, null);
        InstallEngine.Identity identity = InstallEngine.installedIdentity(dir);
        return new IdentityHolder(identity.tag(), identity.builtAt());
    }

    /** Install dir resolution: the catalog's first candidate path that
     *  exists, else its first candidate (so a fresh Install creates it). */
    private Path installDirFor(Catalog.AppDef app) {
        List<Catalog.Profile> profiles = catalog.profilesFor(app.id());
        if (profiles.isEmpty()) return null;
        Catalog.Profile primary = profiles.get(0);
        String target = primary.target();
        if (target == null || target.isBlank()) {
            if (!primary.candidates().isEmpty()) target = primary.candidates().get(0);
        }
        if (target == null || target.isBlank()) return null;
        String expanded = target.replace("${HOME}",
                System.getProperty("user.home"));
        Path dir = Path.of(expanded).toAbsolutePath().normalize();
        return dir.getParent();
    }

    private void log(String message) {
        if (display.isDisposed()) return;
        display.asyncExec(() -> {
            if (logView.isDisposed()) return;
            logView.append(message + "\n");
            logView.setTopIndex(logView.getLineCount() - 1);
        });
        TransportLog.log(message);
    }

    private void reloadLog() {
        Path file = Paths.transportLog();
        if (!Files.isRegularFile(file)) return;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - 12);
            for (int i = from; i < lines.size(); i++) {
                logView.append(lines.get(i) + "\n");
            }
        } catch (IOException ignored) {
        }
    }

    private void checkAll() {
        for (Catalog.AppDef app : catalog.apps()) check(app);
    }

    private void check(Catalog.AppDef app) {
        workers.execute(() -> {
            String token = TokenStore.get();
            GitHubUpdates.Release release;
            try {
                release = InstallEngine.latestFor(app, token);
            } catch (Exception failure) {
                setRow(app, null, null, "check failed: " + failure.getMessage(), null);
                return;
            }
            if (release == null) {
                setRow(app, null, "no update metadata", "n/a", null);
                return;
            }
            Path installDir = installDirFor(app);
            if (installDir == null || !Files.isDirectory(installDir)) {
                setRow(app, null, release.tag(), "not installed", "Install");
                return;
            }
            boolean available = InstallEngine.updateAvailable(app, installDir, release);
            setRow(app, null, release.tag(), available ? "update available" : "up to date",
                    available ? "Update" : "Open");
        });
    }

    private void install(Catalog.AppDef app) {
        Path installDir = installDirFor(app);
        workers.execute(() -> {
            String token = TokenStore.get();
            try {
                setRow(app, null, null, "downloading…", null);
                InstallEngine.installUpdate(app, installDir, token,
                        (url, done, total) -> {
                            if (total > 0) {
                                long pct = done * 100 / total;
                                    setRow(app, null, null, "downloading " + pct + "%", null);
                            }
                        });
                InstallEngine.Identity landed = InstallEngine.installedIdentity(installDir);
                setRow(app, landed.tag(), null, "installed", "Open");
                log("installed " + app.id());
                check(app);
            } catch (Exception failure) {
                log("install " + app.id() + " failed: " + failure.getMessage());
                setRow(app, null, null, "failed: " + failure.getMessage(), "Retry");
            }
        });
    }

    private void openApp(Catalog.AppDef app) {
        Path installDir = installDirFor(app);
        if (installDir == null) return;
        List<Catalog.Profile> profiles = catalog.profilesFor(app.id());
        if (profiles.isEmpty()) return;
        Catalog.Profile profile = profiles.get(0);
        String target = profile.target();
        if (target == null || target.isBlank()) return;
        target = target.replace("{targetDir}", installDir.toString());
        String command = target.replace("${HOME}", System.getProperty("user.home"));
        ProcessBuilder builder = new ProcessBuilder();
        builder.command().add(command);
        for (String arg : profile.args()) builder.command().add(arg);
        try {
            builder.start();
            log("launched " + app.id());
        } catch (IOException failure) {
            log("launch " + app.id() + " failed: " + failure.getMessage());
            setRow(app, null, null, "launch failed", null);
        }
    }

    private void setRow(Catalog.AppDef app, String installedText, String latest, String status, String actionLabel) {
        if (display.isDisposed()) return;
        display.asyncExec(() -> {
            int index = -1;
            for (int i = 0; i < catalog.apps().size(); i++) {
                if (catalog.apps().get(i).id().equals(app.id())) { index = i; break; }
            }
            if (index < 0 || index >= rows.size()) return;
            Row row = rows.get(index);
            if (installedText != null && !row.installed.isDisposed()) row.installed.setText(installedText);
            if (latest != null && !row.latest.isDisposed()) row.latest.setText(latest);
            if (status != null && !row.status.isDisposed()) row.status.setText(status);
            if (actionLabel != null && !row.action.isDisposed()) row.action.setText(actionLabel);
        });
    }

    private void showSettings() {
        Shell dialog = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dialog.setText("Settings");
        dialog.setSize(520, 360);
        dialog.setLayout(new GridLayout(2, false));

        new Label(dialog, SWT.NONE).setText("GitHub token (Contents: read on private repos):");
        Text tokenField = new Text(dialog, SWT.BORDER | SWT.PASSWORD);
        tokenField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        String existing = TokenStore.get();
        if (existing != null) tokenField.setText(existing);

        new Label(dialog, SWT.NONE).setText("");  // spacer
        Composite buttons = new Composite(dialog, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.RIGHT, SWT.BOTTOM, true, true, 2, 1));
        buttons.setLayout(new GridLayout(2, false));
        Button save = new Button(buttons, SWT.PUSH);
        save.setText("Save");
        Button cancel = new Button(buttons, SWT.PUSH);
        cancel.setText("Cancel");
        save.addListener(SWT.Selection, e -> {
            try {
                TokenStore.set(tokenField.getText());
                log("token saved");
            } catch (Exception failure) {
                log("token save failed: " + failure.getMessage());
            }
            dialog.close();
        });
        cancel.addListener(SWT.Selection, e -> dialog.close());
        dialog.open();
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "bigm-worker");
            thread.setDaemon(true);
            return thread;
        };
    }

    public void open() {
        if (!ProductGate.isActivated()) {
            if (!promptForKey()) {
                return;  // closed without a valid key: exit quietly
            }
        }
        shell.open();
        log("BigM (Java) opened; " + catalog.apps().size() + " catalog apps");
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
    }

    /** One-time activation: prompt for the universal product key, verify
     *  against the factory material, write the keychain marker on success. */
    private boolean promptForKey() {
        Shell gate = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        gate.setText("BigM Activation");
        gate.setSize(460, 220);
        gate.setLayout(new GridLayout(1, false));
        Label prompt = new Label(gate, SWT.WRAP);
        prompt.setText("Enter the universal product key (one-time activation for this Mac):");
        prompt.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        Text key = new Text(gate, SWT.BORDER | SWT.PASSWORD);
        key.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Label feedback = new Label(gate, SWT.WRAP);
        feedback.setText("");
        feedback.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        Composite buttons = new Composite(gate, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.RIGHT, SWT.BOTTOM, true, true));
        buttons.setLayout(new GridLayout(2, false));
        Button activate = new Button(buttons, SWT.PUSH);
        activate.setText("Activate");
        Button cancel = new Button(buttons, SWT.PUSH);
        cancel.setText("Quit");
        boolean[] accepted = { false };
        activate.addListener(SWT.Selection, e -> {
            if (ProductGate.activate(key.getText())) {
                accepted[0] = true;
                gate.close();
            } else {
                feedback.setText("That key did not verify. Try again.");
                key.selectAll();
            }
        });
        cancel.addListener(SWT.Selection, e -> gate.close());
        gate.setDefaultButton(activate);
        gate.open();
        while (!gate.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }
        return accepted[0];
    }
}
