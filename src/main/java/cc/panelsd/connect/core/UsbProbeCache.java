package cc.panelsd.connect.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Persists last-known USB serial classification keyed by {@code /dev/serial/by-path} basename.
 * Stickiness for faster reconnect — not a permanent identity if the user swaps ports.
 */
public final class UsbProbeCache {

    private static final Path DEFAULT_PATH =
            Paths.get(System.getProperty("user.home"), ".dcc-io", "usb-probe-cache.json");

    private final Path cachePath;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public UsbProbeCache() {
        this(DEFAULT_PATH);
    }

    public UsbProbeCache(Path cachePath) {
        this.cachePath = cachePath;
        load();
    }

    public Entry get(String byPath) {
        if (byPath == null || byPath.isEmpty()) {
            return null;
        }
        return entries.get(byPath);
    }

    public void put(String byPath, String type, String prefix) {
        if (byPath == null || byPath.isEmpty() || type == null) {
            return;
        }
        entries.put(byPath, new Entry(type, prefix));
        save();
    }

    public void remove(String byPath) {
        if (byPath == null) {
            return;
        }
        if (entries.remove(byPath) != null) {
            save();
        }
    }

    public Map<String, Entry> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    private void load() {
        if (!Files.isRegularFile(cachePath)) {
            return;
        }
        try {
            String raw = Files.readString(cachePath, StandardCharsets.UTF_8);
            JsonObject root = gson.fromJson(raw, JsonObject.class);
            if (root == null || !root.has("paths") || !root.get("paths").isJsonObject()) {
                return;
            }
            JsonObject paths = root.getAsJsonObject("paths");
            for (Map.Entry<String, JsonElement> e : paths.entrySet()) {
                if (!e.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject obj = e.getValue().getAsJsonObject();
                if (!obj.has("type")) {
                    continue;
                }
                String type = obj.get("type").getAsString();
                String prefix = obj.has("prefix") && !obj.get("prefix").isJsonNull()
                        ? obj.get("prefix").getAsString()
                        : null;
                entries.put(e.getKey(), new Entry(type, prefix));
            }
        } catch (Exception e) {
            System.err.println("Error loading USB probe cache: " + e.getMessage());
        }
    }

    private void save() {
        try {
            Path parent = cachePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            JsonObject root = new JsonObject();
            JsonObject paths = new JsonObject();
            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("type", e.getValue().type);
                if (e.getValue().prefix != null) {
                    obj.addProperty("prefix", e.getValue().prefix);
                }
                paths.add(e.getKey(), obj);
            }
            root.add("paths", paths);
            Files.writeString(cachePath, gson.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Error saving USB probe cache: " + e.getMessage());
        }
    }

    public static final class Entry {
        public final String type;
        public final String prefix;

        public Entry(String type, String prefix) {
            this.type = type;
            this.prefix = prefix;
        }
    }
}
