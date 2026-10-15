package ui;

import core.Paths;
import core.ProductGate;

import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Settings: the owner's sheet map is explicit — GitHub token field,
 * keychain unlock, open transport log. Plus the config-file affordances the
 * Swift library menu carries (reveal + copy path) and the activation state.
 */
public final class SettingsSheet extends SheetShell {

    public SettingsSheet(LauncherWindow window) {
        super(window, "Settings", 620, 560);
    }

    @Override protected void fill(Shell sheet) {
        sheet.setLayout(new GridLayout(1, false));
        Composite body = scrolledBody(sheet);

        Composite header = new Composite(body, SWT.NO_BACKGROUND);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout hl = new GridLayout(2, false);
        hl.marginWidth = 0;
        hl.marginHeight = 0;
        header.setLayout(hl);
        Label title = new Label(header, SWT.NO_BACKGROUND);
        title.setText("Settings");
        title.setFont(palette.libTitle);
        title.setForeground(palette.textPrimary);
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GlassButton done = new GlassButton(header, palette, "Done", GlassButton.Style.TOOL);
        done.addListener(SWT.Selection, e -> sheet.close());

        // ── Activation & keychain ──
        Composite gate = card(body, "Product Activation", Glass.Glyph.LOCK, palette.accent());
        bodyText(gate, ProductGate.isActivated()
                ? "Activated on this Mac (receipt bound to this hardware serial). Managed "
                  + "launchers unlock through the same activation."
                : "Not activated on this Mac — the key prompt appears on next launch.");
        String problem = ProductGate.keychainProblem();
        if (problem != null) {
            Label warn = bodyText(gate, problem);
            warn.setForeground(palette.warnFg);
        }
        Composite unlockRow = new Composite(gate, SWT.NO_BACKGROUND);
        unlockRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout ur = new GridLayout(2, false);
        ur.marginWidth = 0;
        ur.marginHeight = 0;
        ur.horizontalSpacing = 8;
        unlockRow.setLayout(ur);
        Text password = new Text(unlockRow, SWT.SINGLE | SWT.BORDER | SWT.PASSWORD);
        password.setMessage("Login keychain password");
        password.setFont(palette.body);
        password.setBackground(palette.card);
        password.setForeground(palette.textPrimary);
        password.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Label unlockResult = new Label(gate, SWT.WRAP | SWT.NO_BACKGROUND);
        unlockResult.setFont(palette.caption);
        unlockResult.setForeground(palette.textMuted);
        unlockResult.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GlassButton unlock = new GlassButton(unlockRow, palette, "Unlock Keychain",
                GlassButton.Style.TOOL);
        unlock.addListener(SWT.Selection, e -> {
            String result = ProductGate.tryUnlock(password.getText());
            password.setText("");
            unlockResult.setText(result == null ? "Keychain unlocked; activation available."
                    : result);
        });

        // ── Diagnostics & config files ──
        Composite files = card(body, "Files", Glass.Glyph.FOLDER, palette.accent());
        bodyText(files, "The transport log records every update-network tier decision — the "
                + "first thing to grab from a machine that can't reach GitHub.");
        Composite fileButtons = new Composite(files, SWT.NO_BACKGROUND);
        fileButtons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout fb = new GridLayout(2, true);
        fb.marginWidth = 0;
        fb.marginHeight = 0;
        fb.horizontalSpacing = 8;
        fileButtons.setLayout(fb);
        GlassButton openLog = new GlassButton(fileButtons, palette, "Open Transport Log",
                GlassButton.Style.TOOL);
        openLog.addListener(SWT.Selection, e -> reveal(Paths.transportLog()));
        GlassButton revealSupport = new GlassButton(fileButtons, palette, "Reveal Support Folder",
                GlassButton.Style.TOOL);
        revealSupport.addListener(SWT.Selection, e -> {
                try {
                    new ProcessBuilder("/usr/bin/open", Paths.support().toString()).start();
                } catch (IOException ignored) {
                }
            });
        GlassButton revealConfig = new GlassButton(fileButtons, palette, "Reveal Config File",
                GlassButton.Style.TOOL);
        revealConfig.addListener(SWT.Selection,
                e -> reveal(Paths.support().resolve("config.properties")));
        GlassButton copyPath = new GlassButton(fileButtons, palette, "Copy Config Path",
                GlassButton.Style.TOOL);
        copyPath.addListener(SWT.Selection, e -> {
            Clipboard clipboard = new Clipboard(display());
            clipboard.setContents(new Object[]{Paths.support()
                    .resolve("config.properties").toString()},
                    new org.eclipse.swt.dnd.Transfer[]{TextTransfer.getInstance()});
            clipboard.dispose();
        });

        body.layout(true, true);
        Object scroller = body.getData("scroller");
        if (scroller instanceof org.eclipse.swt.custom.ScrolledComposite sc) {
            sc.setMinSize(body.computeSize(sc.getClientArea().width, SWT.DEFAULT));
        }
    }


    private void reveal(Path file) {
        try {
            new ProcessBuilder("/usr/bin/open", "-R", file.toString()).start();
        } catch (IOException ignored) {
        }
    }

    private Display display() { return window.swtDisplay(); }
}
