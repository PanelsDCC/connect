package cc.panelsd.connect.daemon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles accessories POST/GET operations and stores accessory state by name.
 */
public class JsonAccessoriesHandler implements JsonMessageHandler.TypeHandler {

    private final AccessoryService service;
    private JsonBroadcaster broadcaster;
    private final Map<String, String> accessories = new ConcurrentHashMap<>();

    public JsonAccessoriesHandler(AccessoryService service) {
        this.service = service;
    }

    public void setBroadcaster(JsonBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public JsonObject handle(String method, JsonObject data) {
        switch (method.toLowerCase(Locale.ROOT)) {
            case "post":
                return post(data);
            case "get":
                return get(data);
            case "list":
                return list();
            default:
                throw new IllegalArgumentException("Unsupported method '" + method + "'");
        }
    }

    private JsonObject post(JsonObject data) {
        JsonArray accessoriesArray = data.has("accessories") && data.get("accessories").isJsonArray()
                ? data.getAsJsonArray("accessories")
                : new JsonArray();
        JsonArray commandsArray = data.has("commands") && data.get("commands").isJsonArray()
                ? data.getAsJsonArray("commands")
                : new JsonArray();

        JsonArray changedAccessories = new JsonArray();
        for (JsonElement element : accessoriesArray) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Each accessory must be an object");
            }
            JsonObject obj = element.getAsJsonObject();
            String name = requireString(obj, "name");
            String state = requireString(obj, "state");
            accessories.put(name, state);
            JsonObject changed = new JsonObject();
            changed.addProperty("name", name);
            changed.addProperty("state", state);
            changedAccessories.add(changed);
        }

        JsonArray commandResults = new JsonArray();
        for (JsonElement element : commandsArray) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Each command must be an object");
            }
            JsonObject obj = element.getAsJsonObject();
            int address = requireInt(obj);
            if (address < 1) {
                throw new IllegalArgumentException("Accessory address must be greater than or equal to 1");
            }
            boolean closed = parseClosedState(requireString(obj, "state"));
            try {
                service.setTurnout(address, closed);
            } catch (IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
            JsonObject result = new JsonObject();
            result.addProperty("address", address);
            result.addProperty("closed", closed);
            commandResults.add(result);
        }

        JsonObject payload = new JsonObject();
        payload.add("accessories", changedAccessories);
        payload.add("commands", commandResults);

        JsonObject response = new JsonObject();
        response.addProperty("type", "accessories");
        response.add("data", payload);

        if (broadcaster != null && changedAccessories.size() > 0) {
            JsonObject delta = new JsonObject();
            delta.addProperty("type", "accessories");
            delta.addProperty("method", "patch");
            delta.add("data", changedAccessories);
            broadcaster.broadcast(delta);
        }

        return response;
    }

    private JsonObject get(JsonObject data) {
        if (data.has("name")) {
            String name = data.get("name").getAsString();
            String state = accessories.get(name);
            if (state == null) {
                throw new java.util.NoSuchElementException("Accessory not found: " + name);
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("name", name);
            payload.addProperty("state", state);

            JsonObject response = new JsonObject();
            response.addProperty("type", "accessories");
            response.add("data", payload);
            return response;
        }
        return list();
    }

    private JsonObject list() {
        JsonObject response = new JsonObject();
        response.addProperty("type", "accessories");
        response.add("data", snapshotAccessories());
        return response;
    }

    private JsonArray snapshotAccessories() {
        JsonArray array = new JsonArray();
        for (Map.Entry<String, String> entry : accessories.entrySet()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", entry.getKey());
            obj.addProperty("state", entry.getValue());
            array.add(obj);
        }
        return array;
    }

    private String requireString(JsonObject obj, String field) {
        if (!obj.has(field) || !obj.get(field).isJsonPrimitive()) {
            throw new IllegalArgumentException("Field '" + field + "' is required and must be a string");
        }
        return obj.get(field).getAsString();
    }

    private int requireInt(JsonObject obj) {
        String field = obj.has("number") ? "number" : (obj.has("address") ? "address" : null);
        if (field == null) {
            throw new IllegalArgumentException("Field 'number' or 'address' is required");
        }
        return obj.get(field).getAsInt();
    }

    private boolean parseClosedState(String state) {
        String normalized = state.toLowerCase(Locale.ROOT);
        if ("closed".equals(normalized)) {
            return true;
        }
        if ("thrown".equals(normalized)) {
            return false;
        }
        throw new IllegalArgumentException("State must be 'closed' or 'thrown'");
    }

    public interface AccessoryService {
        void setTurnout(int address, boolean closed) throws IOException;
    }
}

