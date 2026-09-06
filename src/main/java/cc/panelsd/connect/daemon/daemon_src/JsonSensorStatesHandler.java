package cc.panelsd.connect.daemon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * Pass-through broadcast for hardware sensor state.
 *
 * Control/Pi owns the sensor logic; the gateway only rebroadcasts so the browser UI
 * can render per-sensor highlights.
 */
public class JsonSensorStatesHandler implements JsonMessageHandler.TypeHandler {

    private JsonBroadcaster broadcaster;

    public void setBroadcaster(JsonBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public JsonObject handle(String method, JsonObject data) {
        String m = method == null ? "get" : method.toLowerCase(Locale.ROOT);
        if (!"patch".equals(m)) {
            // No GET/LIST needed; Control only pushes patches.
            throw new IllegalArgumentException("Unsupported method '" + method + "' for sensorStates");
        }

        JsonObject payload = new JsonObject();

        if (data == null) {
            throw new IllegalArgumentException("sensorStates patch requires data");
        }

        // Required: id and state
        String id = requireString(data, "id");
        int state = requireInt01(data, "state");

        payload.addProperty("id", id);
        payload.addProperty("state", state);

        // slotIds: array of slot ids (optional)
        if (data.has("slotIds") && data.get("slotIds") != null && !data.get("slotIds").isJsonNull() && data.get("slotIds").isJsonArray()) {
            JsonArray slotIds = data.getAsJsonArray("slotIds");
            payload.add("slotIds", slotIds);
        } else {
            payload.add("slotIds", new JsonArray());
        }

        // berthKey: string|null (optional)
        if (data.has("berthKey") && data.get("berthKey") != null && !data.get("berthKey").isJsonNull()) {
            payload.addProperty("berthKey", data.get("berthKey").getAsString());
        } else {
            payload.add("berthKey", JsonNull.INSTANCE);
        }

        if (broadcaster != null) {
            JsonObject delta = new JsonObject();
            delta.addProperty("type", "sensorStates");
            delta.addProperty("method", "patch");
            delta.add("data", payload);
            broadcaster.broadcast(delta);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "sensorStates");
        response.add("data", responseOk());
        return response;
    }

    private JsonObject responseOk() {
        JsonObject ok = new JsonObject();
        ok.addProperty("ok", true);
        return ok;
    }

    private static String requireString(JsonObject obj, String field) {
        if (obj == null || !obj.has(field) || obj.get(field) == null || obj.get(field) instanceof JsonNull) {
            throw new IllegalArgumentException("Field '" + field + "' is required");
        }
        JsonElement el = obj.get(field);
        if (!el.isJsonPrimitive()) {
            throw new IllegalArgumentException("Field '" + field + "' must be a string");
        }
        return el.getAsString();
    }

    private static int requireInt01(JsonObject obj, String field) {
        if (obj == null || !obj.has(field) || obj.get(field) == null || obj.get(field) instanceof JsonNull) {
            throw new IllegalArgumentException("Field '" + field + "' is required");
        }
        JsonElement el = obj.get(field);
        try {
            int v;
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
                v = el.getAsInt();
            } else if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()) {
                v = el.getAsBoolean() ? 1 : 0;
            } else if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                String s = el.getAsString().trim();
                v = Integer.parseInt(s);
            } else {
                throw new IllegalArgumentException("Field '" + field + "' must be 0/1");
            }
            return v == 1 ? 1 : 0;
        } catch (Exception e) {
            throw new IllegalArgumentException("Field '" + field + "' must be 0 or 1");
        }
    }
}

