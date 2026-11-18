package ui;

import core.Catalog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import java.util.ArrayList;
import java.util.List;

/** SwiftUI CommandPalette (Cmd-K) port, with Cmd-1..9 launch shortcuts. */
public final class CommandPalette extends SheetShell {

    private record Action(String label, String subtitle, Runnable run) {}

    public CommandPalette(LauncherWindow window) {
        super(window, "Command Palette", 500, 420);
    }

    @Override protected void fill(Shell sheet) {
        sheet.setLayout(new GridLayout(1, false));
        Composite body = scrolledBody(sheet);
        Text query = new Text(body, SWT.BORDER);
        query.setMessage("Search apps and actions…");
        query.setFont(palette.body);
        query.setForeground(palette.textPrimary);
        query.setBackground(palette.chromeRaised);
        query.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        org.eclipse.swt.widgets.List resultList = new org.eclipse.swt.widgets.List(body,
                SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
        resultList.setFont(palette.body);
        resultList.setBackground(palette.chromeDeep);
        resultList.setForeground(palette.textPrimary);
        GridData ld = new GridData(SWT.FILL, SWT.FILL, true, true);
        ld.heightHint = 260;
        resultList.setLayoutData(ld);

        List<Action> all = new ArrayList<>();
        for (Catalog.AppDef app : window.visibleApps()) {
            all.add(new Action((window.isRunning(app.id()) ? "Quit " : "Launch ") + app.displayName(),
                    app.summary(), () -> {
                        if (window.isRunning(app.id())) window.quitApp(app);
                        else {
                            var profiles = window.profilesFor(app);
                            window.launchApp(app, profiles.isEmpty() ? null : profiles.get(0));
                        }
                    }));
            all.add(new Action("Reveal " + app.displayName(), "Open in Finder",
                    () -> window.revealApp(app)));
        }
        all.add(new Action("Rescan Apps Folder", "", window::refreshDiscovery));
        all.add(new Action("Open System Stats", "", () -> new SystemSheet(window).open()));

        refresh(resultList, all, "");
        query.addListener(SWT.Modify, e -> refresh(resultList, all, query.getText().trim().toLowerCase()));
        resultList.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetDefaultSelected(SelectionEvent e) {
                int i = resultList.getSelectionIndex();
                if (i >= 0 && i < resultList.getItemCount()) {
                    // labels store index in data
                    Object data = resultList.getData(String.valueOf(i));
                    if (data instanceof Runnable r) { r.run(); sheet.dispose(); }
                }
            }
        });
        resultList.addListener(SWT.MouseDoubleClick, e -> resultList.notifyListeners(SWT.DefaultSelection, new org.eclipse.swt.widgets.Event()));
        query.setFocus();
        sheet.addListener(SWT.KeyDown, e -> {
            if (e.keyCode == SWT.ESC) sheet.dispose();
        });
    }

    private void refresh(org.eclipse.swt.widgets.List list, List<Action> actions, String query) {
        list.removeAll();
        int index = 0;
        for (Action a : actions) {
            if (!query.isEmpty() && !a.label.toLowerCase().contains(query)
                    && !a.subtitle.toLowerCase().contains(query)) continue;
            list.add(a.label + "  —  " + a.subtitle);
            list.setData(String.valueOf(index), a.run());
            index++;
            if (index >= 40) break;
        }
        if (list.getItemCount() > 0) list.select(0);
    }
}
