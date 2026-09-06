package cc.panelsd.connect.daemon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;

/**
 * Stores and broadcasts panel section berth assignments (manual and sensor-driven).
 *
 * This is Control/Pi domain state, but Control expects it to travel through the DCC
 * gateway WebSocket JSON API.
 */
public class JsonPanelSectionsHandler implements JsonMessageHandler.TypeHandler {

    private final Map<String, JsonObject> assignments = new ConcurrentHashMap<>();
    private JsonBroadcaster broadcaster;

    public void setBroadcaster(JsonBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    private String key(String panelId, String elementId, String logicalSectionId) {
        String lid = (logicalSectionId == null) ? "" : String.valueOf(logicalSectionId).trim();
        return String.valueOf(panelId) + "|" + String.valueOf(elementId) + "|" + lid;
    }

    private static String requireString(JsonObject obj, String field) {
        if (obj == null || !obj.has(field) || obj.get(field) == null || obj.get(field) instanceof JsonNull) {
            throw new IllegalArgumentException("Field '" + field + "' is required");
        }
        JsonElement el = obj.get(field);
        if (el == null || el.isJsonNull()) {
            throw new IllegalArgumentException("Field '" + field + "' is required");
        }
        if (!el.isJsonPrimitive()) {
            throw new IllegalArgumentException("Field '" + field + "' must be a string");
        }
        return el.getAsString();
    }

    private static boolean parseBoolean(JsonObject obj, String field, boolean defaultValue) {
        if (obj == null || !obj.has(field) || obj.get(field) == null || obj.get(field) instanceof JsonNull) {
            return defaultValue;
        }
        JsonElement el = obj.get(field);
        if (el.isJsonPrimitive()) {
            if (el.getAsJsonPrimitive().isBoolean()) return el.getAsBoolean();
            if (el.getAsJsonPrimitive().isNumber()) return el.getAsInt() != 0;
        }
        return defaultValue;
    }

    private static Integer parseNullableInt(JsonObject obj, String field) {
        if (obj == null || !obj.has(field) || obj.get(field) == null || obj.get(field) instanceof JsonNull) {
            return null;
        }
        JsonElement el = obj.get(field);
        if (el == null || el.isJsonNull()) return null;

        try {
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
                int v = el.getAsInt();
                return v;
            }
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                String s = el.getAsString().trim();
                if (s.isEmpty()) return null;
                int v = Integer.parseInt(s);
                return v;
            }
        } catch (Exception ignore) {
            return null;
        }
        return null;
    }

    private static String parseLogicalSectionId(JsonObject obj) {
        if (obj == null || !obj.has("logicalSectionId") || obj.get("logicalSectionId") == null || obj.get("logicalSectionId") instanceof JsonNull) {
            return "";
        }
        JsonElement el = obj.get("logicalSectionId");
        if (el.isJsonPrimitive()) {
            return el.getAsString() == null ? "" : el.getAsString().trim();
        }
        return "";
    }

    private JsonObject normalizeRow(JsonObject data) {
        String panelId = requireString(data, "panelId");
        String elementId = requireString(data, "elementId");

        String logicalSectionId = parseLogicalSectionId(data);
        boolean occupied = parseBoolean(data, "occupied", false);
        String source = data.has("source") && data.get("source") != null && !data.get("source").isJsonNull()
                ? data.get("source").getAsString()
                : "manual";

        Integer dcc = parseNullableInt(data, "dcc");

        JsonObject row = new JsonObject();
        row.addProperty("panelId", panelId);
        row.addProperty("elementId", elementId);
        row.addProperty("logicalSectionId", logicalSectionId);
        row.addProperty("occupied", occupied);
        row.addProperty("source", source);
        if (dcc != null) {
            row.addProperty("dcc", dcc);
        }
        return row;
    }

    /**
     * Snapshot for initial full state dump.
     */
    public JsonArray snapshotAssignments() {
        JsonArray out = new JsonArray();
        for (JsonObject row : assignments.values()) {
            out.add(row);
        }
        return out;
    }

    @Override
    public JsonObject handle(String method, JsonObject data) {
        String m = method == null ? "get" : method.toLowerCase(Locale.ROOT);
        switch (m) {
            case "list":
                JsonObject response = new JsonObject();
                response.addProperty("type", "panelSections");
                JsonObject payload = new JsonObject();
                payload.add("assignments", snapshotAssignments());
                response.add("data", payload);
                return response;
            case "post":
            case "patch":
                return postOrPatch(m, data);
            case "get":
                // No per-row GET API currently used by Control UI.
                throw new IllegalArgumentException("Unsupported method 'get' for panelSections");
            default:
                throw new IllegalArgumentException("Unsupported method '" + method + "' for panelSections");
        }
    }

    private JsonObject postOrPatch(String method, JsonObject data) {
        JsonObject row = normalizeRow(data);
        String k = key(
                row.get("panelId").getAsString(),
                row.get("elementId").getAsString(),
                row.get("logicalSectionId").getAsString()
        );

        boolean occupied = row.get("occupied").getAsBoolean();
        boolean hasDcc = row.has("dcc") && !(row.get("dcc") instanceof JsonNull);
        boolean clearing = !occupied && !hasDcc;

        if (clearing) {
            assignments.remove(k);
        } else {
            assignments.put(k, row);
        }

        // Always broadcast the row so clients can clear even when we delete it locally.
        if (broadcaster != null) {
            JsonObject delta = new JsonObject();
            delta.addProperty("type", "panelSections");
            delta.addProperty("method", "patch");
            delta.add("data", row);
            broadcaster.broadcast(delta);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "panelSections");
        response.add("data", row);
        return response;
    }
}

