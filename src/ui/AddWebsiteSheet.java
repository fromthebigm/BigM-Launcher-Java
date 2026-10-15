package ui;

import core.Catalog;
import core.Paths;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pin a website as a first-class library card (the Swift WebsiteAppSheet):
 * the entry launches the Rigel browser straight at the page, and lives in
 * the user's config layer like any custom app.
 */
public final class AddWebsiteSheet extends SheetShell {

    private static final String[] RIGEL_CANDIDATES = {
            Paths.realHome() + "/Downloads/rigel/run.sh",
            Paths.realHome() + "/Downloads/Rigel/run.sh",
    };

    public AddWebsiteSheet(LauncherWindow window) {
        super(window, "Add Website", 480, 340);
    }

    @Override protected void fill(Shell sheet) {
        sheet.setLayout(new GridLayout(1, false));
        Composite body = new Composite(sheet, SWT.NO_BACKGROUND);
        body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 22;
        layout.marginHeight = 18;
        layout.verticalSpacing = 12;
        body.setLayout(layout);

        Label sub = new Label(body, SWT.WRAP | SWT.NO_BACKGROUND);
        sub.setText("Pinned sites open in the Rigel browser as their own window. The card behaves "
                + "like any other app.");
        sub.setFont(palette.caption);
        sub.setForeground(palette.textMuted);
        sub.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Composite form = card(body, null, null, palette.accent());
        form.setLayout(new GridLayout(2, false));

        Label nameLabel = new Label(form, SWT.NO_BACKGROUND);
        nameLabel.setText("Name");
        nameLabel.setFont(palette.body);
        nameLabel.setForeground(palette.textPrimary);
        Text name = darkField(form);

        Label urlLabel = new Label(form, SWT.NO_BACKGROUND);
        urlLabel.setText("URL");
        urlLabel.setFont(palette.body);
        urlLabel.setForeground(palette.textPrimary);
        Text url = darkField(form);

        Composite buttons = new Composite(body, SWT.NO_BACKGROUND);
        buttons.setLayoutData(new GridData(SWT.RIGHT, SWT.BOTTOM, true, false));
        GridLayout bl = new GridLayout(2, false);
        bl.marginWidth = 0;
        bl.horizontalSpacing = 8;
        buttons.setLayout(bl);
        GlassButton cancel = new GlassButton(buttons, palette, "Cancel", GlassButton.Style.TOOL);
        cancel.addListener(SWT.Selection, e -> sheet.close());
        GlassButton add = new GlassButton(buttons, palette, "Add Website", GlassButton.Style.PRIMARY);
        add.addListener(SWT.Selection, e -> {
            String cleanName = name.getText().trim();
            String cleanURL = url.getText().trim();
            if (cleanName.isEmpty() || cleanURL.isEmpty()) return;
            if (!cleanURL.startsWith("http://") && !cleanURL.startsWith("https://")) {
                cleanURL = "https://" + cleanURL;
            }
            String slug = cleanName.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
            String id = "web." + (slug.isEmpty() ? "site" : slug) + "."
                    + UUID.randomUUID().toString().substring(0, 8).toLowerCase();
            String rigel = RIGEL_CANDIDATES[0];
            for (String candidate : RIGEL_CANDIDATES) {
                if (java.nio.file.Files.exists(java.nio.file.Path.of(candidate))) {
                    rigel = candidate;
                    break;
                }
            }
            Catalog.AppDef app = new Catalog.AppDef(
                    id, "", "", "", List.of(), cleanURL, cleanName, "browser", "#4A90D9", "globe");
            Catalog.Profile profile = new Catalog.Profile(
                    "default", "Open", "script", rigel,
                    List.of(RIGEL_CANDIDATES), List.of(cleanURL), Map.of(), null);
            window.config().addApp(app, profile);
            window.refreshDiscovery();
            sheet.close();
        });
    }

    private Text darkField(Composite parent) {
        Text field = new Text(parent, SWT.SINGLE | SWT.BORDER);
        field.setFont(palette.body);
        field.setBackground(palette.card);
        field.setForeground(palette.textPrimary);
        field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return field;
    }
}
