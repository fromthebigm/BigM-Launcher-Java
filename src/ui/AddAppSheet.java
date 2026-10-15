package ui;

import core.Catalog;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Add a Custom App (the Swift AddAppView): display name, category, launch
 * backend, target path (with file picker), and one-argument-per-line args.
 * Writes into the user's config layer (Support/config.properties) through
 * LauncherConfig.addApp; the library rebuilds immediately after.
 */
public final class AddAppSheet extends SheetShell {

    private static final String[] CATEGORY_KEYS = {
            "streaming", "remote-play", "browser", "minecraft", "emulator", "games", "development"};
    private static final String[] CATEGORY_NAMES = {
            "Streaming", "Remote Play", "Browser", "Minecraft", "Emulators", "Games", "Development"};
    private static final String[] BACKEND_KEYS = {"python", "script", "executable"};
    private static final String[] BACKEND_NAMES = {
            "Python portable loader", "Portable wrapper script", "Executable"};

    public AddAppSheet(LauncherWindow window) {
        super(window, "Add a Custom App", 560, 520);
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
        sub.setText("Create an adjustable entry in your personal catalog layer.");
        sub.setFont(palette.caption);
        sub.setForeground(palette.textMuted);
        sub.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Composite form = card(body, null, null, palette.accent());
        GridLayout formLayout = new GridLayout(2, false);
        formLayout.marginWidth = 0;
        form.setLayout(formLayout);

        label(form, "Display name");
        Text name = darkField(form);
        label(form, "Category");
        Combo category = new Combo(form, SWT.DROP_DOWN | SWT.READ_ONLY);
        category.setItems(CATEGORY_NAMES);
        category.select(6); // games
        styleCombo(category);
        label(form, "Launch as");
        Combo backend = new Combo(form, SWT.DROP_DOWN | SWT.READ_ONLY);
        backend.setItems(BACKEND_NAMES);
        backend.select(1); // script
        styleCombo(backend);
        label(form, "Target path");
        Composite pathRow = new Composite(form, SWT.NO_BACKGROUND);
        pathRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout pr = new GridLayout(2, false);
        pr.marginWidth = 0;
        pr.marginHeight = 0;
        pr.horizontalSpacing = 8;
        pathRow.setLayout(pr);
        Text path = darkField(pathRow);
        GlassButton choose = new GlassButton(pathRow, palette, "Choose…", GlassButton.Style.TOOL);
        choose.addListener(SWT.Selection, e -> {
            FileDialog dialog = new FileDialog(sheet, SWT.OPEN);
            dialog.setText("Choose Python Loader or Wrapper");
            String picked = dialog.open();
            if (picked == null) return;
            path.setText(picked);
            if (picked.endsWith(".py")) backend.select(0);
            if (name.getText().isBlank()) {
                String base = Path.of(picked).getFileName().toString();
                int dot = base.lastIndexOf('.');
                name.setText(dot > 0 ? base.substring(0, dot) : base);
            }
        });
        label(form, "Arguments (one per line)");
        Text args = new Text(form, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL);
        args.setFont(palette.mono);
        args.setBackground(palette.card);
        args.setForeground(palette.textPrimary);
        GridData ad = new GridData(SWT.FILL, SWT.FILL, true, true);
        ad.heightHint = 90;
        args.setLayoutData(ad);
        Label note = new Label(form, SWT.WRAP | SWT.NO_BACKGROUND);
        note.setText("Arguments are passed as an array and are never evaluated as a shell command.");
        note.setFont(palette.caption);
        note.setForeground(palette.textMuted);
        note.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));

        Composite buttons = new Composite(body, SWT.NO_BACKGROUND);
        buttons.setLayoutData(new GridData(SWT.RIGHT, SWT.BOTTOM, true, false));
        GridLayout bl = new GridLayout(2, false);
        bl.marginWidth = 0;
        bl.marginHeight = 6;
        bl.horizontalSpacing = 8;
        buttons.setLayout(bl);
        GlassButton cancel = new GlassButton(buttons, palette, "Cancel", GlassButton.Style.TOOL);
        cancel.addListener(SWT.Selection, e -> sheet.close());
        GlassButton add = new GlassButton(buttons, palette, "Add App", GlassButton.Style.PRIMARY);
        add.setTint(palette.accent());
        add.addListener(SWT.Selection, e -> {
            String cleanName = name.getText().trim();
            String cleanPath = path.getText().trim();
            if (cleanName.isEmpty() || cleanPath.isEmpty()) return;
            String slug = cleanName.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
            String id = "custom." + (slug.isEmpty() ? "app" : slug) + "."
                    + UUID.randomUUID().toString().substring(0, 8).toLowerCase();
            Catalog.AppDef app = new Catalog.AppDef(
                    id, "", "", "", List.of(), "Custom launch profile", cleanName,
                    CATEGORY_KEYS[category.getSelectionIndex()], "#6C7CFF", "app.fill");
            Catalog.Profile profile = new Catalog.Profile(
                    "default", "Default", BACKEND_KEYS[backend.getSelectionIndex()], cleanPath,
                    List.of(),
                    args.getText().lines().map(String::trim).filter(s -> !s.isEmpty()).toList(),
                    Map.of(), null);
            window.config().addApp(app, profile);
            window.refreshDiscovery();
            sheet.close();
        });
    }

    private Label label(Composite parent, String text) {
        Label label = new Label(parent, SWT.NO_BACKGROUND);
        label.setText(text);
        label.setFont(palette.body);
        label.setForeground(palette.textPrimary);
        GridData data = new GridData(SWT.LEFT, SWT.TOP, false, false);
        data.verticalIndent = 6;
        data.widthHint = 130;
        label.setLayoutData(data);
        return label;
    }

    private Text darkField(Composite parent) {
        Text field = new Text(parent, SWT.SINGLE | SWT.BORDER);
        field.setFont(palette.body);
        field.setBackground(palette.card);
        field.setForeground(palette.textPrimary);
        field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return field;
    }

    private void styleCombo(Combo combo) {
        combo.setFont(palette.body);
        combo.setBackground(palette.card);
        combo.setForeground(palette.textPrimary);
        combo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }
}
