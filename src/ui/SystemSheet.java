package ui;

import core.SystemStats;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/** Swift SystemSheet — CPU / memory / battery / machine / running apps. */
public final class SystemSheet extends SheetShell {

    public SystemSheet(LauncherWindow window) {
        super(window, "System", 620, 520);
    }

    @Override protected void fill(Shell sheet) {
        SystemStats.Machine m = SystemStats.machine();
        Composite header = new Composite(sheet, SWT.NO_BACKGROUND);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        header.setLayout(new GridLayout(2, false));
        Label title = new Label(header, SWT.NONE);
        title.setText("System");
        title.setFont(palette.libTitle);
        title.setForeground(palette.textPrimary);
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        ui.GlassButton done = new ui.GlassButton(header, palette, "Done", ui.GlassButton.Style.TOOL);
        done.addListener(SWT.Selection, e -> sheet.close());
        Composite body = scrolledBody(sheet);

        card(body, "CPU", Glass.Glyph.INFO, palette.accent());
        Composite cpu = lastCard(body);
        bigMetric(cpu, String.format(java.util.Locale.ROOT, "%.1f%%", m.cpuPercent),
                palette.textPrimary);
        bodyText(cpu, "Load avg: " + String.format(java.util.Locale.ROOT, "%.2f", m.loadAvg));
        bar(cpu, Math.min(1, m.cpuPercent / 100), thresholdColor(m.cpuPercent, 50, 80));

        card(body, "Memory", Glass.Glyph.INFO, palette.accent());
        Composite mem = lastCard(body);
        bigMetric(mem, String.format(java.util.Locale.ROOT, "%.1f GB / %.1f GB",
                m.memUsedGB, m.memTotalGB), palette.textPrimary);
        bodyText(mem, String.format(java.util.Locale.ROOT, "%.0f%% used · %d threads",
                m.memPercent, m.threadCount));
        bar(mem, Math.min(1, m.memPercent / 100), thresholdColor(m.memPercent, 70, 85));

        int battery = SystemStats.batteryPercent();
        if (battery >= 0) {
            card(body, "Battery", Glass.Glyph.CHECK, palette.accent());
            Composite bat = lastCard(body);
            bigMetric(bat, battery + "%", thresholdColor(100 - battery, 50, 80));
            bodyText(bat, SystemStats.batteryState());
            bar(bat, battery / 100.0, thresholdColor(100 - battery, 50, 80));
        }

        card(body, "Machine", Glass.Glyph.GLOBE, palette.accent());
        Composite machine = lastCard(body);
        bodyText(machine, "Model: " + SystemStats.modelName());
        bodyText(machine, "Uptime: " + SystemStats.uptime());
        String version = "dev";
        try {
            var rootStamp = core.Paths.root().resolve("version.txt");
            version = java.nio.file.Files.readString(java.nio.file.Files.isRegularFile(rootStamp)
                    ? rootStamp : core.Paths.resources().resolve("version.txt"))
                    .trim().lines().findFirst().orElse("dev");
        } catch (Exception ignored) {}
        bodyText(machine, "BigM version: " + version);

        card(body, "Running Apps", Glass.Glyph.LIST, palette.accent());
        Composite running = lastCard(body);
        for (var app : window.visibleApps()) {
            if (!window.isRunning(app.id())) continue;
            bodyText(running, app.displayName());
        }
    }

    private Composite lastCard(Composite body) {
        int count = body.getChildren().length;
        return (Composite) body.getChildren()[count - 1];
    }

    private void bigMetric(Composite parent, String text, Color color) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
        label.setFont(palette.headline);
        label.setForeground(color);
        label.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
    }

    private void bar(Composite parent, double fraction, Color color) {
        Composite bar = new Composite(parent, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED) {
            @Override public org.eclipse.swt.graphics.Point computeSize(int w, int h, boolean c) {
                return new org.eclipse.swt.graphics.Point(10, 10);
            }
        };
        bar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        bar.addPaintListener(e -> {
            Rectangle b = bar.getClientArea();
            e.gc.setBackground(palette.separator);
            e.gc.fillRoundRectangle(b.x, b.y, b.width, 8, 8, 8);
            e.gc.setBackground(color);
            e.gc.fillRoundRectangle(b.x, b.y, (int) (b.width * Math.max(0, Math.min(1, fraction))), 8, 8, 8);
        });
    }

    private Color thresholdColor(double value, double warn, double bad) {
        return value > bad ? palette.errFg : (value > warn ? palette.warnFg : palette.accent());
    }
}
