package ui;

import core.Paths;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/** SwiftUI FirstLaunchFolderPrompt, ported to SWT. */
final class FirstLaunchPrompt extends SheetShell {

    FirstLaunchPrompt(LauncherWindow window) {
        super(window, "Welcome to BigM", 560, 300);
    }

    @Override protected void fill(Shell sheet) {
        sheet.setLayout(new GridLayout(1, false));
        Label icon = new Label(sheet, SWT.NONE);
        icon.setText("＋");
        icon.setFont(palette.headline);
        icon.setForeground(palette.swift() != null ? palette.accent() : palette.accent());

        Label title = new Label(sheet, SWT.NONE);
        title.setText("Welcome to BigM");
        title.setFont(palette.headline);
        title.setForeground(palette.textPrimary);

        Label body = new Label(sheet, SWT.WRAP);
        body.setText("Choose the folder where your launchers live.\n"
                + "BigM uses this same folder to find existing apps and to install new ones.");
        body.setFont(palette.caption);
        body.setForeground(palette.textMuted);
        body.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        GlassButton choose = new GlassButton(sheet, palette, "Choose Apps Folder…", GlassButton.Style.PRIMARY);
        choose.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, false, false));
        choose.addListener(SWT.Selection, e -> {
            DirectoryDialog dialog = new DirectoryDialog(sheet);
            dialog.setText("Choose a folder");
            String path = dialog.open();
            if (path != null) {
                window.config().setDefaultInstallFolder(path);
                window.refreshDiscovery();
            }
            sheet.dispose();
        });

        GlassButton skip = new GlassButton(sheet, palette, "Skip for now", GlassButton.Style.QUIET);
        skip.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, false, false));
        skip.addListener(SWT.Selection, e -> {
            window.config().setDefaultInstallFolder(Paths.realHome().resolve("Downloads").toString());
            sheet.dispose();
        });
    }
}
