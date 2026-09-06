package cc.panelsd.connect.daemon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;

/**
 * Stores and broadcasts Pi-owned cab destination plans.
 *
 * - Browser commands are rebroadcast as {method:"post", data:{dcc, action, ...}}
 *   so the Pi can validate/rebuild and then publish an authoritative patch.
 * - Pi authoritative updates are received as {method:"patch", data:{dcc, steps, ...}}
 *   and are stored and rebroadcast to browsers.
 */
public class JsonCabDestinationsHandler implements JsonMessageHandler.TypeHandler {

    private final Map<Integer, JsonObject> plansByDcc = new ConcurrentHashMap<>();
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
                throw new IllegalArgumentException("Unsupported method '" + method + "' for cabDestinations");
        }
    }

    public JsonArray snapshotPlans() {
        JsonArray out = new JsonArray();
        for (JsonObject row : plansByDcc.values()) {
            out.add(row);
        }
        return out;
    }

    private JsonObject list() {
        JsonObject response = new JsonObject();
        response.addProperty("type", "cabDestinations");
        JsonObject payload = new JsonObject();
        payload.add("plans", snapshotPlans());
        response.add("data", payload);
        return response;
    }

    private JsonObject post(JsonObject data) {
        if (data == null) throw new IllegalArgumentException("cabDestinations post requires data");

        // Browser command: action present
        if (data.has("action") && data.get("action") != null && !data.get("action").isJsonNull() && data.get("action").isJsonPrimitive()) {
            if (broadcaster != null) {
                JsonObject delta = new JsonObject();
                delta.addProperty("type", "cabDestinations");
                delta.addProperty("method", "post");
                delta.add("data", data);
                broadcaster.broadcast(delta);
            }

            JsonObject response = new JsonObject();
            response.addProperty("type", "cabDestinations");
            JsonObject ok = new JsonObject();
            ok.addProperty("ok", true);
            response.add("data", ok);
            return response;
        }

        // Non-command post: treat like authoritative update.
        return updateFromRow(data);
    }

    private JsonObject patch(JsonObject data) {
        if (data == null) throw new IllegalArgumentException("cabDestinations patch requires data");
        return updateFromRow(data);
    }

    private JsonObject updateFromRow(JsonObject data) {
        Integer dcc = parseNullableDcc(data);
        if (dcc == null) {
            throw new IllegalArgumentException("cabDestinations update requires numeric dcc");
        }

        boolean clear = parseBoolean(data, "clear", false);
        if (clear) {
            plansByDcc.remove(dcc);

            JsonObject cleared = new JsonObject();
            cleared.addProperty("dcc", dcc);
            cleared.addProperty("clear", true);

            if (broadcaster != null) {
                JsonObject delta = new JsonObject();
                delta.addProperty("type", "cabDestinations");
                delta.addProperty("method", "patch");
                delta.add("data", cleared);
                broadcaster.broadcast(delta);
            }

            JsonObject response = new JsonObject();
            response.addProperty("type", "cabDestinations");
            response.add("data", cleared);
            return response;
        }

        JsonObject stored = data.deepCopy();
        stored.remove("clear");
        plansByDcc.put(dcc, stored);

        if (broadcaster != null) {
            JsonObject delta = new JsonObject();
            delta.addProperty("type", "cabDestinations");
            delta.addProperty("method", "patch");
            delta.add("data", stored);
            broadcaster.broadcast(delta);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "cabDestinations");
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
                return el.getAsInt();
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

