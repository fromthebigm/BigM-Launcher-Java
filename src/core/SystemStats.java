package core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * System-wide and per-process resource monitoring.
 *
 * Uses the real macOS `vm_stat` + `sysctl` for memory (the JVM's
 * OperatingSystemMXBean reports "free" as nearly-zero because macOS
 * aggressively caches — that's the 100% RAM bug). CPU comes from
 * `ps aux` for per-process, and `top -l 1` for system-wide (the JVM's
 * getSystemCpuLoad() is unreliable on macOS). Battery from `pmset`.
 */
public final class SystemStats {

    public static final class Machine {
        public double cpuPercent;       // real system CPU (0..100)
        public double memUsedGB;       // real used RAM (wired + active + compressed)
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
        public double memMB;           // real RSS from ps
        public long timestamp = System.currentTimeMillis();
    }

    /** BigM's own process snapshot. */
    public static final class Self {
        public double cpuPercent;
        public double memMB;
        public long timestamp = System.currentTimeMillis();
    }

    private static volatile Machine lastMachine;
    private static volatile Self lastSelf;
    private static final Map<String, App> appCache = new ConcurrentHashMap<>();

    // ── System-wide ───────────────────────────────────────────────────

    public static Machine machine() {
        if (lastMachine != null && System.currentTimeMillis() - lastMachine.timestamp < 3000) {
            return lastMachine;
        }
        Machine m = new Machine();
        try {
            // Real memory: `vm_stat` gives page counts. Page size is 16384
            // on Apple Silicon (query via sysctl hw.pagesize or just parse).
            java.lang.Process p = new java.lang.ProcessBuilder("/usr/sbin/sysctl", "hw.memsize", "hw.pagesize")
                    .redirectErrorStream(true).start();
            String sysctlOut = new String(p.getInputStream().readAllBytes());
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);

            long totalBytes = 0;
            long pageSize = 16384;
            for (String line : sysctlOut.split("\n")) {
                if (line.startsWith("hw.memsize:")) {
                    totalBytes = Long.parseLong(line.split(":")[1].trim());
                } else if (line.startsWith("hw.pagesize:")) {
                    pageSize = Long.parseLong(line.split(":")[1].trim());
                }
            }
            m.memTotalGB = totalBytes / (1024.0 * 1024 * 1024);

            // vm_stat: count pages that are genuinely in use (wired + active + compressed + speculative partially)
            java.lang.Process vp = new java.lang.ProcessBuilder("/usr/bin/vm_stat")
                    .redirectErrorStream(true).start();
            String vmOut = new String(vp.getInputStream().readAllBytes());
            vp.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);

            long usedPages = 0;
            long freePages = 0;
            for (String line : vmOut.split("\n")) {
                String clean = line.replaceAll("[().]", " ").trim();
                if (clean.contains("Pages wired down") || clean.contains("Pages active")
                        || clean.contains("Pages occupied by compressor")) {
                    usedPages += extractPageCount(clean);
                } else if (clean.contains("Pages free")) {
                    freePages += extractPageCount(clean);
                }
            }
            // "Pages inactive" counts as available-ish on macOS — don't add to used
            long usedBytes = usedPages * pageSize;
            m.memUsedGB = usedBytes / (1024.0 * 1024 * 1024);
            // Cap: if vm_stat parsing failed, fall back to JVM estimate
            if (m.memUsedGB <= 0 || m.memUsedGB > m.memTotalGB) {
                var osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                if (osBean instanceof com.sun.management.OperatingSystemMXBean sun) {
                    m.memUsedGB = (sun.getTotalMemorySize() - sun.getFreeMemorySize()) / (1024.0 * 1024 * 1024);
                }
            }
            m.memPercent = m.memTotalGB > 0 ? (m.memUsedGB / m.memTotalGB * 100.0) : 0;

            // Real CPU: `top -l 1` gives accurate system CPU on macOS
            java.lang.Process tp = new java.lang.ProcessBuilder("/usr/bin/top", "-l", "1", "-n", "0")
                    .redirectErrorStream(true).start();
            String topOut = new String(tp.getInputStream().readAllBytes());
            tp.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            for (String line : topOut.split("\n")) {
                if (line.contains("CPU usage:")) {
                    // e.g. "CPU usage: 12.34% user, 3.21% sys, 84.45% idle"
                    String[] parts = line.split(",");
                    double user = parseDoubleSafe(parts[0].replaceAll("[^0-9.]", ""));
                    double sys = parts.length > 1 ? parseDoubleSafe(parts[1].replaceAll("[^0-9.]", "")) : 0;
                    m.cpuPercent = user + sys;
                    break;
                }
            }
            if (m.cpuPercent <= 0) {
                // Fallback: JVM's getSystemCpuLoad (less accurate on macOS)
                var osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                if (osBean instanceof com.sun.management.OperatingSystemMXBean sun) {
                    m.cpuPercent = Math.max(0, sun.getCpuLoad() * 100.0);
                }
            }
        } catch (Exception ignored) {
        }
        try {
            m.loadAvg = java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
            if (m.loadAvg < 0) m.loadAvg = 0;
            m.threadCount = java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount();
        } catch (Exception ignored) {}
        lastMachine = m;
        return m;
    }

    private static long extractPageCount(String line) {
        try {
            // "Pages wired down:  123456."
            String[] parts = line.split(":");
            if (parts.length > 1) {
                return Long.parseLong(parts[1].trim().replace(".", ""));
            }
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    private static double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s.trim()); }
        catch (Exception e) { return 0; }
    }

    // ── BigM's own process ─────────────────────────────────────────────

    public static Self self() {
        if (lastSelf != null && System.currentTimeMillis() - lastSelf.timestamp < 3000) {
            return lastSelf;
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
                s.cpuPercent = parseDoubleSafe(fields[0]);
                s.memMB = parseDoubleSafe(fields[1]) / 1024.0;
            }
        } catch (Exception ignored) {}
        s.timestamp = System.currentTimeMillis();
        lastSelf = s;
        return s;
    }

    // ── Per-app ───────────────────────────────────────────────────────

    public static App app(String appId, Path installDir) {
        App cached = appCache.get(appId);
        if (cached != null && System.currentTimeMillis() - cached.timestamp < 3000) {
            return cached;
        }
        App snapshot = new App();
        snapshot.appId = appId;
        try {
            String nameHint = installDir != null
                    ? installDir.getFileName().toString() : appId;
            java.lang.Process p = new java.lang.ProcessBuilder(
                    "/bin/sh", "-c",
                    "ps aux | grep -i '" + nameHint + "' | grep -v grep | head -1"
            ).redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!output.isBlank()) {
                String[] fields = output.trim().split("\\s+");
                if (fields.length >= 6) {
                    try { snapshot.cpuPercent = Double.parseDouble(fields[2]); } catch (NumberFormatException ignored) {}
                    try { snapshot.memMB = Double.parseDouble(fields[5]) / 1024.0; } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception ignored) {}
        snapshot.timestamp = System.currentTimeMillis();
        appCache.put(appId, snapshot);
        return snapshot;
    }

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

    // ── Machine info ──────────────────────────────────────────────────

    public static String uptime() {
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
                if (days > 0) return days + "d " + hours + "h";
                if (hours > 0) return hours + "h " + mins + "m";
                return mins + "m";
            }
        } catch (Exception ignored) {}
        return "—";
    }

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

    public static int batteryPercent() {
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
                return Integer.parseInt(output.substring(start + 1, pctIdx));
            }
        } catch (Exception ignored) {}
        return -1;
    }

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
