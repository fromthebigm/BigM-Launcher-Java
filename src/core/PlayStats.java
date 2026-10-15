package core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** PlayStats: per-app launch count + last-played stamp, at
 *  Support/play-stats.properties. Displayed on each card. */
public final class PlayStats {

    private static final Path FILE = Paths.support().resolve("play-stats.properties");
    private static final int MAX_ENTRIES = 256;

    private final Properties props = new Properties();

    public PlayStats() { load(); }

    private void load() {
        props.clear();
        if (!Files.isRegularFile(FILE)) return;
        try {
            if (Files.size(FILE) > 2L * 1024 * 1024) return;
            try (var in = Files.newInputStream(FILE)) { props.load(in); }
        } catch (IOException ignored) {
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            try (var out = Files.newOutputStream(tmp)) {
                props.store(out, "bigm-java play stats");
            }
            Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    public int launches(String appId) {
        try { return Integer.parseInt(props.getProperty(appId + ".launches", "0")); }
        catch (NumberFormatException e) { return 0; }
    }

    public String lastPlayed(String appId) {
        return props.getProperty(appId + ".lastPlayed", "");
    }

    public long lastPlayedMillis(String appId) {
        try { return Long.parseLong(props.getProperty(appId + ".lastPlayedAt", "0")); }
        catch (NumberFormatException e) { return 0; }
    }

    public long totalSeconds(String appId) {
        try { return Long.parseLong(props.getProperty(appId + ".totalSeconds", "0")); }
        catch (NumberFormatException e) { return 0; }
    }

    /** Adds one finished session's runtime; called when a launched process
     *  exits so Most Time sorting and the stats line stay truthful. */
    public synchronized void addSessionSeconds(String appId, long seconds) {
        if (seconds <= 0) return;
        props.setProperty(appId + ".totalSeconds",
                Long.toString(totalSeconds(appId) + seconds));
        save();
    }

    /** The card's one-line play summary, mirroring the Swift launcher's
     *  "2.4h played · 12 launches · 3 days ago" line (null when never run). */
    public String summaryLine(String appId) {
        int count = launches(appId);
        if (count <= 0) return null;
        List<String> parts = new ArrayList<>();
        long total = totalSeconds(appId);
        if (total >= 60) {
            if (total >= 3600) parts.add(String.format("%.1fh played", total / 3600.0));
            else parts.add((total / 60) + "m played");
        }
        parts.add(count + (count == 1 ? " launch" : " launches"));
        long at = lastPlayedMillis(appId);
        if (at > 0) parts.add(relative(at));
        return String.join(" · ", parts);
    }

    private static String relative(long epochMillis) {
        long delta = System.currentTimeMillis() - epochMillis;
        if (delta < 60_000) return "just now";
        if (delta < 3_600_000) return (delta / 60_000) + " min ago";
        if (delta < 86_400_000) return (delta / 3_600_000) + " hr ago";
        return (delta / 86_400_000) + " days ago";
    }

    public synchronized void recordLaunch(String appId) {
        // Bound the file: evict a random oldest entry past the cap.
        if (props.size() > MAX_ENTRIES * 2) {
            String oldest = null;
            long oldestStamp = Long.MAX_VALUE;
            for (String key : props.stringPropertyNames()) {
                if (!key.endsWith(".lastPlayedAt")) continue;
                long at;
                try { at = Long.parseLong(props.getProperty(key, "0")); }
                catch (NumberFormatException e) { at = 0; }
                if (at < oldestStamp) { oldestStamp = at; oldest = key; }
            }
            if (oldest != null) {
                String id = oldest.substring(0, oldest.length() - ".lastPlayedAt".length());
                props.remove(id + ".launches");
                props.remove(id + ".lastPlayed");
                props.remove(id + ".lastPlayedAt");
            }
        }
        int count = launches(appId) + 1;
        props.setProperty(appId + ".launches", Integer.toString(count));
        props.setProperty(appId + ".lastPlayed",
                java.time.LocalDate.now().toString());
        props.setProperty(appId + ".lastPlayedAt",
                Long.toString(System.currentTimeMillis()));
        save();
    }

    public Map<String, Integer> allCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            if (!key.endsWith(".launches")) continue;
            String id = key.substring(0, key.length() - ".launches".length());
            try { out.put(id, Integer.parseInt(props.getProperty(key, "0"))); }
            catch (NumberFormatException ignored) {}
        }
        return out;
    }
}
