package cc.panelsd.connect.core.impl.sensor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persistent node inventory ({@code ~/.dcc-io/nodes.json}).
 */
public final class NodeInventory {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private final CopyOnWriteArrayList<NodeRecord> nodes = new CopyOnWriteArrayList<>();

    public NodeInventory() {
        this(defaultPath());
    }

    public NodeInventory(Path path) {
        this.path = path;
        loadQuiet();
    }

    public static Path defaultPath() {
        String home = System.getProperty("user.home", ".");
        return Paths.get(home, ".dcc-io", "nodes.json");
    }

    public Path getPath() {
        return path;
    }

    public List<NodeRecord> list() {
        return List.copyOf(nodes);
    }

    public NodeRecord findByPrefix(String prefix) {
        String n = SensorBoardSupport.normalizePrefix(prefix);
        for (NodeRecord r : nodes) {
            if (n != null && n.equals(r.prefix)) {
                return r;
            }
        }
        return null;
    }

    public NodeRecord findById(String id) {
        if (id == null) {
            return null;
        }
        for (NodeRecord r : nodes) {
            if (id.equals(r.id)) {
                return r;
            }
        }
        return null;
    }

    public synchronized void upsert(NodeRecord record) {
        if (record == null || record.prefix == null) {
            return;
        }
        nodes.removeIf(r -> record.prefix.equals(r.prefix) || (record.id != null && record.id.equals(r.id)));
        nodes.add(record);
        saveQuiet();
    }

    public synchronized boolean removeById(String id) {
        boolean removed = nodes.removeIf(r -> id != null && id.equals(r.id));
        if (removed) {
            saveQuiet();
        }
        return removed;
    }

    public List<String> usedPrefixes() {
        List<String> out = new ArrayList<>();
        for (NodeRecord r : nodes) {
            if (r.prefix != null) {
                out.add(r.prefix);
            }
        }
        return out;
    }

    public String suggestPrefix() {
        return SensorBoardSupport.nextFreePrefix(usedPrefixes());
    }

    public JsonArray toJsonArray() {
        JsonArray arr = new JsonArray();
        for (NodeRecord r : nodes) {
            arr.add(r.toJson());
        }
        return arr;
    }

    private void loadQuiet() {
        try {
            load();
        } catch (Exception e) {
            System.err.println("[nodes] failed to load " + path + ": " + e.getMessage());
        }
    }

    public void load() throws IOException {
        nodes.clear();
        if (!Files.isRegularFile(path)) {
            return;
        }
        String text = Files.readString(path, StandardCharsets.UTF_8);
        JsonObject root = GSON.fromJson(text, JsonObject.class);
        if (root == null || !root.has("nodes") || !root.get("nodes").isJsonArray()) {
            return;
        }
        for (JsonElement el : root.getAsJsonArray("nodes")) {
            if (el.isJsonObject()) {
                NodeRecord r = NodeRecord.fromJson(el.getAsJsonObject());
                if (r != null) {
                    nodes.add(r);
                }
            }
        }
    }

    private void saveQuiet() {
        try {
            save();
        } catch (Exception e) {
            System.err.println("[nodes] failed to save " + path + ": " + e.getMessage());
        }
    }

    public synchronized void save() throws IOException {
        Files.createDirectories(path.getParent());
        JsonObject root = new JsonObject();
        root.add("nodes", toJsonArray());
        Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
    }

    public static final class NodeRecord {
        public String id;
        public String prefix;
        public String boardType;
        public String transport;
        public String endpoint;
        public String firmwareVersion;
        public JsonArray pins;
        public JsonArray outputs;
        /** Analogue optical districts (ref + sensors + sensitivityPct). */
        public JsonArray opticalDistricts;
        public JsonObject config;

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            if (id != null) {
                o.addProperty("id", id);
            }
            if (prefix != null) {
                o.addProperty("prefix", prefix);
            }
            if (boardType != null) {
                o.addProperty("boardType", boardType);
            }
            if (transport != null) {
                o.addProperty("transport", transport);
            }
            if (endpoint != null) {
                o.addProperty("endpoint", endpoint);
            }
            if (firmwareVersion != null) {
                o.addProperty("firmwareVersion", firmwareVersion);
            }
            if (pins != null) {
                o.add("pins", pins);
            }
            if (outputs != null) {
                o.add("outputs", outputs);
            }
            if (opticalDistricts != null) {
                o.add("opticalDistricts", opticalDistricts);
            }
            if (config != null) {
                o.add("config", config);
            }
            return o;
        }

        public static NodeRecord fromJson(JsonObject o) {
            if (o == null) {
                return null;
            }
            NodeRecord r = new NodeRecord();
            r.id = str(o, "id");
            r.prefix = SensorBoardSupport.normalizePrefix(str(o, "prefix"));
            r.boardType = BoardProfile.normalize(str(o, "boardType"));
            r.transport = str(o, "transport");
            r.endpoint = str(o, "endpoint");
            r.firmwareVersion = str(o, "firmwareVersion");
            if (o.has("pins") && o.get("pins").isJsonArray()) {
                r.pins = o.getAsJsonArray("pins");
            }
            if (o.has("outputs") && o.get("outputs").isJsonArray()) {
                r.outputs = o.getAsJsonArray("outputs");
            }
            if (o.has("opticalDistricts") && o.get("opticalDistricts").isJsonArray()) {
                r.opticalDistricts = o.getAsJsonArray("opticalDistricts");
            }
            if (o.has("config") && o.get("config").isJsonObject()) {
                r.config = o.getAsJsonObject("config");
                if (r.opticalDistricts == null && r.config.has("opticalDistricts")
                        && r.config.get("opticalDistricts").isJsonArray()) {
                    r.opticalDistricts = r.config.getAsJsonArray("opticalDistricts");
                }
            }
            if (r.prefix == null) {
                return null;
            }
            if (r.id == null) {
                r.id = SensorBoardSupport.TRANSPORT_SHIM.equalsIgnoreCase(r.transport)
                        ? SensorBoardSupport.sessionIdForShim(r.prefix)
                        : SensorBoardSupport.sessionIdForPort(r.endpoint != null ? r.endpoint : r.prefix);
            }
            return r;
        }

        private static String str(JsonObject o, String key) {
            if (!o.has(key) || o.get(key).isJsonNull()) {
                return null;
            }
            return o.get(key).getAsString();
        }

        public static NodeRecord shim(String prefix, String boardType) {
            NodeRecord r = new NodeRecord();
            r.prefix = SensorBoardSupport.normalizePrefix(prefix);
            r.boardType = BoardProfile.normalize(boardType);
            r.transport = SensorBoardSupport.TRANSPORT_SHIM;
            r.endpoint = "virtual:" + r.prefix;
            r.id = SensorBoardSupport.sessionIdForShim(r.prefix);
            r.pins = new JsonArray();
            r.outputs = new JsonArray();
            BoardProfile profile = BoardProfile.require(r.boardType);
            for (SensorBoardSupport.ChannelDef ch : profile.defaultChannelDefs(r.prefix)) {
                JsonObject pin = new JsonObject();
                pin.addProperty("label", ch.label);
                pin.addProperty("role", "analog".equals(ch.kind) ? "optical" : "digital_in");
                pin.addProperty("id", ch.id);
                r.pins.add(pin);
            }
            return r;
        }
    }
}
