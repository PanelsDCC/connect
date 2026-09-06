package cc.panelsd.connect.daemon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;

/**
 * Stores and broadcasts Pi-owned cab strip trips.
 *
 * - Browser "commands" are sent as {method: "post", data: {dcc, action, ...}}
 *   and must be rebroadcast so the Pi can act.
 * - Pi authoritative updates are sent as {method: "patch", data: {...}}
 *   and must be stored and rebroadcast so browsers can render the strip.
 */
public class JsonCabTripsHandler implements JsonMessageHandler.TypeHandler {

    private final Map<Integer, JsonObject> tripsByDcc = new ConcurrentHashMap<>();
    private JsonBroadcaster broadcaster;

    public void setBroadcaster(JsonBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public JsonObject handle(String method, JsonObject data) {
        String m = method == null ? "get" : method.toLowerCase(Locale.ROOT);
        switch (m) {
            case "list":
                return list();
            case "post":
                return post(data);
            case "patch":
                return patch(data);
            default:
                throw new IllegalArgumentException("Unsupported method '" + method + "' for cabTrips");
        }
    }

    public JsonArray snapshotTrips() {
        JsonArray out = new JsonArray();
        for (JsonObject row : tripsByDcc.values()) {
            out.add(row);
        }
        return out;
    }

    private JsonObject list() {
        JsonObject response = new JsonObject();
        response.addProperty("type", "cabTrips");
        JsonObject payload = new JsonObject();
        payload.add("trips", snapshotTrips());
        response.add("data", payload);
        return response;
    }

    private JsonObject post(JsonObject data) {
        if (data == null) throw new IllegalArgumentException("cabTrips post requires data");

        // Browser command: action present
        if (data.has("action") && data.get("action") != null && !data.get("action").isJsonNull() && data.get("action").isJsonPrimitive()) {
            if (broadcaster != null) {
                JsonObject delta = new JsonObject();
                delta.addProperty("type", "cabTrips");
                delta.addProperty("method", "post");
                delta.add("data", data);
                broadcaster.broadcast(delta);
            }

            JsonObject response = new JsonObject();
            response.addProperty("type", "cabTrips");
            JsonObject ok = new JsonObject();
            ok.addProperty("ok", true);
            response.add("data", ok);
            return response;
        }

        // Non-command post: treat like authoritative update.
        return updateFromRow(data);
    }

    private JsonObject patch(JsonObject data) {
        if (data == null) throw new IllegalArgumentException("cabTrips patch requires data");
        // Pi authoritative updates (including clear) arrive as patch.
        return updateFromRow(data);
    }

    private JsonObject updateFromRow(JsonObject data) {
        Integer dcc = parseNullableDcc(data);
        if (dcc == null) {
            throw new IllegalArgumentException("cabTrips update requires numeric dcc");
        }

        boolean clear = parseBoolean(data, "clear", false);
        if (clear) {
            tripsByDcc.remove(dcc);

            JsonObject cleared = new JsonObject();
            cleared.addProperty("dcc", dcc);
            cleared.addProperty("clear", true);

            if (broadcaster != null) {
                JsonObject delta = new JsonObject();
                delta.addProperty("type", "cabTrips");
                delta.addProperty("method", "patch");
                delta.add("data", cleared);
                broadcaster.broadcast(delta);
            }

            JsonObject response = new JsonObject();
            response.addProperty("type", "cabTrips");
            response.add("data", cleared);
            return response;
        }

        // Store full row (including cursor/items/armed/canStart/canNext etc).
        JsonObject stored = data.deepCopy();
        stored.remove("clear"); // keep row clean for clients
        tripsByDcc.put(dcc, stored);

        if (broadcaster != null) {
            JsonObject delta = new JsonObject();
            delta.addProperty("type", "cabTrips");
            delta.addProperty("method", "patch");
            delta.add("data", stored);
            broadcaster.broadcast(delta);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "cabTrips");
        response.add("data", stored);
        return response;
    }

    private static Integer parseNullableDcc(JsonObject data) {
        if (data == null || !data.has("dcc") || data.get("dcc") == null || data.get("dcc") instanceof JsonNull) {
            return null;
        }
        JsonElement el = data.get("dcc");
        try {
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
                int v = el.getAsInt();
                return v;
            }
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                String s = el.getAsString().trim();
                if (s.isEmpty()) return null;
                return Integer.parseInt(s);
            }
        } catch (Exception ignore) {
            return null;
        }
        return null;
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
}

