package core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.management.ManagementFactory;

/**
 * System-wide and per-app resource monitoring. Uses pure-Java APIs where
 * possible (com.sun.management.OperatingSystemMXBean) and the macOS `ps`
 * command for per-process CPU/memory (no native deps needed).
 *
 * Stats are polled lazily on a background thread; the controller pushes
 * them into the state JSON so cards and the System sheet can render them.
 */
public final class SystemStats {

    // ── System-wide snapshot ────────────────────────────────────────────

    public static final class Machine {
        public double cpuPercent;       // 0..100
        public double memUsedGB;
        public double memTotalGB;
        public double memPercent;
        public double loadAvg;
        public int threadCount;
        public long timestamp = System.currentTimeMillis();
    }

    /** One app's resource snapshot. */
    public static final class App {
        public String appId;
        public double cpuPercent;
        public double memMB;
        public long timestamp = System.currentTimeMillis();
    }

    private static final Map<String, App> appCache = new ConcurrentHashMap<>();

    /**
     * Take a system-wide snapshot. Cheap — one MXBean read + one `ps` call.
     */
    public static Machine machine() {
        Machine m = new Machine();
        try {
            var osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sun) {
                double load = sun.getCpuLoad();
                m.cpuPercent = Math.max(0, load * 100.0);
                long totalMem = sun.getTotalMemorySize();
                long freeMem = sun.getFreeMemorySize();
                m.memTotalGB = totalMem / (1024.0 * 1024 * 1024);
                m.memUsedGB = (totalMem - freeMem) / (1024.0 * 1024 * 1024);
                m.memPercent = totalMem > 0 ? ((totalMem - freeMem) * 100.0 / totalMem) : 0;
            }
            m.loadAvg = osBean.getSystemLoadAverage();
            if (m.loadAvg < 0) m.loadAvg = 0;
            m.threadCount = ManagementFactory.getThreadMXBean().getThreadCount();
        } catch (Throwable ignored) {
        }
        return m;
    }

    /**
     * Snapshot one app's resource usage. Uses `ps` to find the process by
     * matching the app's install directory name against running commands.
     * Returns a cached snapshot when the process isn't found (app not running).
     */
    public static App app(String appId, Path installDir) {
        App cached = appCache.get(appId);
        if (cached != null && System.currentTimeMillis() - cached.timestamp < 3000) {
            return cached;   // 3-second cache — ps is expensive per-app
        }
        App snapshot = new App();
        snapshot.appId = appId;
        try {
            // Find the process by matching install dir basename in the command line
            String nameHint = installDir != null
                    ? installDir.getFileName().toString() : appId;
            java.lang.Process p = new java.lang.ProcessBuilder(
                    "/bin/sh", "-c",
                    "ps aux | grep -i '" + nameHint + "' | grep -v grep | head -1"
            ).redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!output.isBlank()) {
                // ps aux format: USER PID %CPU %MEM VSZ RSS TT STAT STARTED TIME COMMAND
                String[] fields = output.trim().split("\\s+");
                if (fields.length >= 6) {
                    try {
                        snapshot.cpuPercent = Double.parseDouble(fields[2]);
                    } catch (NumberFormatException ignored) {}
                    try {
                        // RSS is in KB in ps aux
                        double rssKB = Double.parseDouble(fields[5]);
                        snapshot.memMB = rssKB / 1024.0;
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception ignored) {
        }
        snapshot.timestamp = System.currentTimeMillis();
        appCache.put(appId, snapshot);
        return snapshot;
    }

    /**
     * Snapshot ALL running apps tracked by the launcher. Returns only apps
     * that currently have a running process (cpu > 0 or mem > 0).
     */
    public static List<App> runningApps(Map<String, Path> installDirs) {
        List<App> running = new ArrayList<>();
        for (Map.Entry<String, Path> entry : installDirs.entrySet()) {
            App snapshot = app(entry.getKey(), entry.getValue());
            if (snapshot.cpuPercent > 0.1 || snapshot.memMB > 10) {
                running.add(snapshot);
            }
        }
        return running;
    }

    /**
     * The Mac's total uptime as a human-readable string.
     */
    public static String uptime() {
        try {
            java.lang.Process p = new java.lang.ProcessBuilder("/usr/sbin/sysctl", "-n", "kern.boottime")
                    .redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            // Output: { sec = 1697116800, usec = 123456 } Thu Oct 12 08:00:00 2026
            int secIdx = output.indexOf("sec = ") + 6;
            if (secIdx > 6) {
                String secStr = output.substring(secIdx, output.indexOf(",", secIdx)).trim();
                long bootSec = Long.parseLong(secStr);
                long uptimeSec = System.currentTimeMillis() / 1000 - bootSec;
                long days = uptimeSec / 86400;
                long hours = (uptimeSec % 86400) / 3600;
                long mins = (uptimeSec % 3600) / 60;
                if (days > 0) return days + "d " + hours + "h";
                if (hours > 0) return hours + "h " + mins + "m";
                return mins + "m";
            }
        } catch (Exception ignored) {}
        return "—";
    }

    /**
     * The Mac's model name (e.g. "MacBook Pro 14-inch, 2021").
     */
    public static String modelName() {
        try {
            java.lang.Process p = new java.lang.ProcessBuilder(
                    "/usr/sbin/sysctl", "-n", "hw.model")
                    .redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            return output.isBlank() ? "Mac" : output;
        } catch (Exception ignored) {}
        return "Mac";
    }

    /**
     * Battery level (0-100) or -1 when no battery (desktop Mac).
     */
    public static int batteryPercent() {
        try {
            java.lang.Process p = new java.lang.ProcessBuilder(
                    "/usr/bin/pmset", "-g", "batt")
                    .redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            // Output: "Currently charged; 100%" or " discharging; 45%"
            int pctIdx = output.indexOf("%");
            if (pctIdx > 0) {
                int start = pctIdx - 1;
                while (start >= 0 && Character.isDigit(output.charAt(start))) start--;
                return Integer.parseInt(output.substring(start + 1, pctIdx));
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * Battery charging state: "charging", "discharging", "charged", or "—".
     */
    public static String batteryState() {
        try {
            java.lang.Process p = new java.lang.ProcessBuilder(
                    "/usr/bin/pmset", "-g", "batt")
                    .redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            String lower = output.toLowerCase();
            if (lower.contains("charging") && !lower.contains("discharging")) return "charging";
            if (lower.contains("discharging")) return "discharging";
            if (lower.contains("charged")) return "charged";
        } catch (Exception ignored) {}
        return "—";
    }

    private SystemStats() {}
}
