package core;

import net.JsonTiny;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The launcher's app catalog: loaded from Resources/default-catalog.json
 * (the same file BigM ships; the schema is bigm's own). Only the fields the
 * launcher acts on are surfaced here; display metadata falls back to the id.
 */
public final class Catalog {

    public record AppDef(String id, String repository, String assetPattern,
                         String marker, List<String> preserve, String summary,
                         String displayName) {}

    public record Profile(String id, String backend, String target,
                          List<String> candidates, List<String> args,
                          Map<String, String> env) {}

    private final List<AppDef> apps;
    private final Map<String, List<Profile>> profilesByApp = new java.util.LinkedHashMap<>();

    public List<AppDef> apps() { return apps; }
    public List<Profile> profilesFor(String appId) {
        return profilesByApp.getOrDefault(appId, List.of());
    }

    public static Catalog load() throws IOException {
        Path file = Paths.resources().resolve("default-catalog.json");
        Map<String, Object> root = JsonTiny.obj(JsonTiny.parse(Files.readAllBytes(file)));
        List<AppDef> apps = new ArrayList<>();
        Map<String, List<Profile>> profilesByApp = new java.util.LinkedHashMap<>();
        for (Object entry : JsonTiny.array(root, "apps")) {
            Map<String, Object> app = JsonTiny.obj(entry);
            String id = JsonTiny.str(app, "id");
            Map<String, Object> update = JsonTiny.obj(app.get("update"));
            AppDef def = new AppDef(
                    id,
                    JsonTiny.str(update, "repository"),
                    JsonTiny.str(update, "assetPattern"),
                    JsonTiny.str(update, "marker"),
                    strings(JsonTiny.obj(update), "preserve"),
                    JsonTiny.str(app, "summary") != null ? JsonTiny.str(app, "summary") : "",
                    JsonTiny.str(app, "displayName") != null ? JsonTiny.str(app, "displayName") : id);
            apps.add(def);
            List<Profile> profiles = new ArrayList<>();
            for (Object p : JsonTiny.array(app, "profiles")) {
                Map<String, Object> pm = JsonTiny.obj(p);
                Map<String, String> env = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, Object> kv : JsonTiny.obj(pm.get("env")).entrySet()) {
                    if (kv.getValue() instanceof String s) env.put(kv.getKey(), s);
                }
                profiles.add(new Profile(
                        JsonTiny.str(pm, "id"),
                        JsonTiny.str(pm, "backend"),
                        JsonTiny.str(pm, "target"),
                        strings(JsonTiny.obj(pm), "candidates"),
                        strings(JsonTiny.obj(pm), "args"),
                        env));
            }
                    profilesByApp.put(id, List.copyOf(profiles));
        }
        return new Catalog(apps, profilesByApp);
    }

    private static List<String> strings(Map<String, Object> map, String key) {
        List<Object> raw = JsonTiny.array(map, key);
        List<String> out = new ArrayList<>();
        for (Object item : raw) if (item instanceof String s) out.add(s);
        return out;
    }

    private Catalog(List<AppDef> apps, Map<String, List<Profile>> profilesByApp) {
        this.apps = List.copyOf(apps);
        this.profilesByApp.putAll(profilesByApp);
    }
}
