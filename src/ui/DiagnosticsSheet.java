package ui;

import core.Paths;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

import java.io.IOException;

/**
 * Launch diagnostics (the Swift DiagnosticsView): the session's launcher
 * events list, plus buttons for the transport log and the Support folder.
 * Arguments and credentials are never written here — the token field never
 * lands in either stream.
 */
public final class DiagnosticsSheet extends SheetShell {

    public DiagnosticsSheet(LauncherWindow window) {
        super(window, "Diagnostics", 700, 480);
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

        Composite header = new Composite(body, SWT.NO_BACKGROUND);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout hl = new GridLayout(4, false);
        hl.marginWidth = 0;
        hl.marginHeight = 0;
        header.setLayout(hl);
        Composite titles = new Composite(header, SWT.NO_BACKGROUND);
        GridLayout tl = new GridLayout(1, false);
        tl.marginWidth = 0;
        tl.marginHeight = 0;
        titles.setLayout(tl);
        titles.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Label title = new Label(titles, SWT.NO_BACKGROUND);
        title.setText("Launch Diagnostics");
        title.setFont(palette.libTitle);
        title.setForeground(palette.textPrimary);
        Label sub = new Label(titles, SWT.NO_BACKGROUND);
        sub.setText("Arguments and credentials are never written here.");
        sub.setFont(palette.caption);
        sub.setForeground(palette.textMuted);
        GlassButton openLog = new GlassButton(header, palette, "Transport Log", GlassButton.Style.TOOL);
        openLog.addListener(SWT.Selection, e -> {
            try {
                new ProcessBuilder("/usr/bin/open", "-R", Paths.transportLog().toString()).start();
            } catch (IOException ignored) {
            }
        });
        GlassButton support = new GlassButton(header, palette, "Support Folder", GlassButton.Style.TOOL);
        support.addListener(SWT.Selection, e -> {
            try {
                new ProcessBuilder("/usr/bin/open", Paths.support().toString()).start();
            } catch (IOException ignored) {
            }
        });
        GlassButton done = new GlassButton(header, palette, "Done", GlassButton.Style.TOOL);
        done.addListener(SWT.Selection, e -> sheet.close());

        Composite listCard = card(body, "Launcher Events", Glass.Glyph.LIST, palette.infoFg);
        listCard.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Text list = new Text(listCard, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.NO_FOCUS);
        list.setFont(palette.mono);
        list.setBackground(palette.chromeDeep);
        list.setForeground(palette.textPrimary);
        list.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 1, 1));
        refreshList(list);
        // Event-driven: the launcher fires its event hook on each logged
        // line, we repaint. No idle timer while the sheet sits open.
        Runnable refresh = () -> window.swtDisplay().asyncExec(() -> {
            if (!list.isDisposed()) refreshList(list);
        });
        window.addEventHook(refresh);
        list.addDisposeListener(e -> window.removeEventHook(refresh));
    }

    private void refreshList(Text list) {
        java.util.List<String> events = window.events();
        String text = events.isEmpty()
                ? "No events yet this session. Update checks, downloads, launches and failures appear here.\n"
                : String.join("\n", events.subList(Math.max(0, events.size() - 300), events.size()));
        if (!text.equals(list.getText())) {
            list.setText(text);
            list.setTopIndex(list.getLineCount() - 1);
        }
    }
}
