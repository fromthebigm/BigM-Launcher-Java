package core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * System-wide and per-process resource monitoring — OPTIMIZED.
 *
 * The first version spawned `top -l 1` (1-3 seconds!) and `ps aux` per-app
 * on every poll. That caused the UI lag the owner reported: multiple process
 * spawns every 5 seconds, each blocking a worker thread, each triggering a
 * full SwiftUI re-render. This version:
 *
 *   - CPU: instant from OperatingSystemMXBean (no process spawn)
 *   - Memory: cached vm_stat (re-read at most every 10s, not per push)
 *   - Per-app: ONE batch `ps aux` call that parses ALL processes at once
 *   - BigM's own: ONE `ps -o %cpu=,rss= -p PID` (fast, single process)
 *   - Battery/uptime/model: cached for 60 seconds (they rarely change)
 */
public final class SystemStats {

    public static final class Machine {
        public double cpuPercent;
        public double memUsedGB;
        public double memTotalGB;
        public double memPercent;
        public double loadAvg;
        public int threadCount;
        public long timestamp = System.currentTimeMillis();
    }

    public static final class App {
        public String appId;
        public double cpuPercent;
        public double memMB;
        public long timestamp = System.currentTimeMillis();
    }

    public static final class Self {
        public double cpuPercent;
        public double memMB;
        public long timestamp = System.currentTimeMillis();
    }

    // ── Caches (avoid redundant process spawns) ─────────────────────────

    private static volatile Machine cachedMachine;
    private static volatile Self cachedSelf;
    private static volatile String cachedUptime;
    private static volatile String cachedModel;
    private static volatile int cachedBattery = -2;
    private static volatile String cachedBatteryState;
    private static volatile long lastBatteryRead;
    private static volatile long lastUptimeRead;
    private static volatile long lastModelRead;
    private static volatile long lastMemRead;

    /** Per-app results from the last batch ps (appId → snapshot). */
    private static volatile Map<String, App> batchResults = Map.of();
    private static volatile long lastBatchTime;

    // ── System-wide ───────────────────────────────────────────────────

    public static Machine machine() {
        long now = System.currentTimeMillis();
        if (cachedMachine != null && now - cachedMachine.timestamp < 3000) {
            return cachedMachine;
        }

        Machine m = new Machine();
        try {
            // CPU: instant from the JVM (no process spawn — the old `top -l 1`
            // took 1-3 seconds and was the main lag source)
            var osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sun) {
                m.cpuPercent = Math.max(0, sun.getCpuLoad() * 100.0);
                long total = sun.getTotalMemorySize();
                long free = sun.getFreeMemorySize();
                m.memTotalGB = total / (1024.0 * 1024 * 1024);
                // NOTE: on macOS, "free" is misleadingly low because the OS
                // caches aggressively. We use the JVM numbers for speed and
                // refresh real numbers via vm_stat less frequently.
                m.memUsedGB = (total - free) / (1024.0 * 1024 * 1024);
                m.memPercent = total > 0 ? (m.memUsedGB / m.memTotalGB * 100.0) : 0;
            }

            // Real memory via vm_stat — but only every 10 seconds (it spawns
            // a process, and the numbers barely change between polls)
            if (now - lastMemRead > 10000) {
                lastMemRead = now;
                try {
                    java.lang.Process p = new java.lang.ProcessBuilder("/usr/sbin/sysctl", "hw.memsize")
                            .redirectErrorStream(true).start();
                    String sysctlOut = new String(p.getInputStream().readAllBytes());
                    p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                    long totalBytes = 0;
                    for (String line : sysctlOut.split("\n")) {
                        if (line.startsWith("hw.memsize:")) {
                            totalBytes = Long.parseLong(line.split(":")[1].trim());
                        }
                    }

                    java.lang.Process vp = new java.lang.ProcessBuilder("/usr/bin/vm_stat")
                            .redirectErrorStream(true).start();
                    String vmOut = new String(vp.getInputStream().readAllBytes());
                    vp.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);

                    long pageSize = 16384; // Apple Silicon default
                    long usedPages = 0;
                    for (String line : vmOut.split("\n")) {
                        String clean = line.replaceAll("[().]", " ").trim();
                        if (clean.contains("Pages wired down") || clean.contains("Pages active")
                                || clean.contains("Pages occupied by compressor")) {
                            usedPages += extractPageCount(clean);
                        }
                    }
                    long usedBytes = usedPages * pageSize;
                    if (totalBytes > 0 && usedBytes > 0) {
                        m.memTotalGB = totalBytes / (1024.0 * 1024 * 1024);
                        m.memUsedGB = usedBytes / (1024.0 * 1024 * 1024);
                        m.memPercent = totalBytes > 0 ? (m.memUsedGB / m.memTotalGB * 100.0) : 0;
                    }
                } catch (Exception ignored) {}
            }

            m.loadAvg = osBean.getSystemLoadAverage();
            if (m.loadAvg < 0) m.loadAvg = 0;
            m.threadCount = java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount();
        } catch (Exception ignored) {
        }
        m.timestamp = now;
        cachedMachine = m;
        return m;
    }

    private static long extractPageCount(String line) {
        try {
            String[] parts = line.split(":");
            if (parts.length > 1) {
                return Long.parseLong(parts[1].trim().replace(".", ""));
            }
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    // ── BigM's own process (fast, single ps call) ──────────────────────

    public static Self self() {
        long now = System.currentTimeMillis();
        if (cachedSelf != null && now - cachedSelf.timestamp < 3000) {
            return cachedSelf;
        }
        Self s = new Self();
        try {
            long myPid = ProcessHandle.current().pid();
            java.lang.Process p = new java.lang.ProcessBuilder(
                    "/bin/sh", "-c", "ps -o %cpu=,rss= -p " + myPid)
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            String[] fields = out.trim().split("\\s+");
            if (fields.length >= 2) {
                try { s.cpuPercent = Double.parseDouble(fields[0]); } catch (NumberFormatException ignored) {}
                try { s.memMB = Double.parseDouble(fields[1]) / 1024.0; } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}
        s.timestamp = now;
        cachedSelf = s;
        return s;
    }

    // ── Per-app: ONE batch ps that parses ALL processes ───────────────

    /**
     * Batch snapshot of ALL apps at once — runs ONE `ps aux` command and
     * matches each app by name. The old version ran `ps aux` PER-APP which
     * spawned N processes every poll (N = number of running apps).
     * Returns a map of appId → App resource snapshot.
     */
    public static Map<String, App> batchApps(Map<String, Path> installDirs) {
        long now = System.currentTimeMillis();
        if (now - lastBatchTime < 3000 && !batchResults.isEmpty()) {
            return batchResults;
        }

        Map<String, App> results = new ConcurrentHashMap<>();
        try {
            // ONE ps call for all processes
            java.lang.Process p = new java.lang.ProcessBuilder("/bin/ps", "aux")
                    .redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);

            // Parse all lines: USER PID %CPU %MEM VSZ RSS TT STAT STARTED TIME COMMAND
            for (String line : output.split("\n")) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length < 6) continue;

                String command = line.substring(Math.min(line.length(),
                        indexOfNthWhitespace(line, 10) + 1)).trim().toLowerCase();

                // Match against install dir names
                for (Map.Entry<String, Path> entry : installDirs.entrySet()) {
                    Path dir = entry.getValue();
                    if (dir == null) continue;
                    String nameHint = dir.getFileName().toString().toLowerCase();
                    if (command.contains(nameHint)) {
                        App app = results.get(entry.getKey());
                        if (app == null) {
                            app = new App();
                            app.appId = entry.getKey();
                            results.put(entry.getKey(), app);
                        }
                        // Keep the highest CPU/mem instance (in case of child procs)
                        try {
                            double cpu = Double.parseDouble(fields[2]);
                            if (cpu > app.cpuPercent) app.cpuPercent = cpu;
                        } catch (NumberFormatException ignored) {}
                        try {
                            double rssKB = Double.parseDouble(fields[5]);
                            app.memMB += rssKB / 1024.0;
                        } catch (NumberFormatException ignored) {}
                        break; // Found match for this line, stop checking apps
                    }
                }
            }
        } catch (Exception ignored) {}

        batchResults = results;
        lastBatchTime = now;
        return results;
    }

    /** Find the index of the Nth whitespace character in a string. */
    private static int indexOfNthWhitespace(String s, int n) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                count++;
                if (count == n) return i;
            }
        }
        return s.length();
    }

    /**
     * Per-app convenience — uses the cached batch results.
     */
    public static App app(String appId, Path installDir) {
        App cached = batchResults.get(appId);
        if (cached != null) return cached;
        App empty = new App();
        empty.appId = appId;
        return empty;
    }

    // ── Slow-changing info (cached for 60 seconds) ─────────────────────

    public static String uptime() {
        long now = System.currentTimeMillis();
        if (cachedUptime != null && now - lastUptimeRead < 60000) return cachedUptime;
        try {
            java.lang.Process p = new java.lang.ProcessBuilder("/usr/sbin/sysctl", "-n", "kern.boottime")
                    .redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            int secIdx = output.indexOf("sec = ") + 6;
            if (secIdx > 6) {
                String secStr = output.substring(secIdx, output.indexOf(",", secIdx)).trim();
                long bootSec = Long.parseLong(secStr);
                long uptimeSec = System.currentTimeMillis() / 1000 - bootSec;
                long days = uptimeSec / 86400;
                long hours = (uptimeSec % 86400) / 3600;
                long mins = (uptimeSec % 3600) / 60;
                String result;
                if (days > 0) result = days + "d " + hours + "h";
                else if (hours > 0) result = hours + "h " + mins + "m";
                else result = mins + "m";
                cachedUptime = result;
                lastUptimeRead = now;
                return result;
            }
        } catch (Exception ignored) {}
        return "—";
    }

    public static String modelName() {
        long now = System.currentTimeMillis();
        if (cachedModel != null && now - lastModelRead < 60000) return cachedModel;
        try {
            java.lang.Process p = new java.lang.ProcessBuilder(
                    "/usr/sbin/sysctl", "-n", "hw.model")
                    .redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            cachedModel = output.isBlank() ? "Mac" : output;
            lastModelRead = now;
            return cachedModel;
        } catch (Exception ignored) {}
        return "Mac";
    }

    public static int batteryPercent() {
        long now = System.currentTimeMillis();
        if (cachedBattery != -2 && now - lastBatteryRead < 60000) return cachedBattery;
        try {
            java.lang.Process p = new java.lang.ProcessBuilder(
                    "/usr/bin/pmset", "-g", "batt")
                    .redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            int pctIdx = output.indexOf("%");
            if (pctIdx > 0) {
                int start = pctIdx - 1;
                while (start >= 0 && Character.isDigit(output.charAt(start))) start--;
                cachedBattery = Integer.parseInt(output.substring(start + 1, pctIdx));
                lastBatteryRead = now;
                return cachedBattery;
            }
        } catch (Exception ignored) {}
        return -1;
    }

    public static String batteryState() {
        long now = System.currentTimeMillis();
        if (cachedBatteryState != null && now - lastBatteryRead < 60000) return cachedBatteryState;
        try {
            java.lang.Process p = new java.lang.ProcessBuilder(
                    "/usr/bin/pmset", "-g", "batt")
                    .redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            String lower = output.toLowerCase();
            String state;
            if (lower.contains("charging") && !lower.contains("discharging")) state = "charging";
            else if (lower.contains("discharging")) state = "discharging";
            else if (lower.contains("charged")) state = "charged";
            else state = "—";
            cachedBatteryState = state;
            lastBatteryRead = now;
            return state;
        } catch (Exception ignored) {}
        return "—";
    }

    private SystemStats() {}
}
