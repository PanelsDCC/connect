package cc.panelsd.connect.daemon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes JSON messages to registered type handlers.
 */
public class JsonMessageHandler {

    private final Map<String, TypeHandler> typeHandlers = new ConcurrentHashMap<>();

    public JsonMessageHandler() {}

    public void registerTypeHandler(String type, TypeHandler handler) {
        typeHandlers.put(type, handler);
    }

    public JsonObject handle(String jsonPayload) {
        try {
            JsonObject message = JsonParser.parseString(jsonPayload).getAsJsonObject();
            return handle(message);
        } catch (Exception e) {
            return error(400, "Invalid JSON payload: " + e.getMessage(), null);
        }
    }

    public JsonObject handle(JsonObject message) {
        if (message == null) {
            return error(400, "Message is required", null);
        }

        // Extract optional request ID
        String requestId = message.has("id") && message.get("id").isJsonPrimitive()
                ? message.get("id").getAsString()
                : null;

        if (message.has("list")) {
            String listType = message.get("list").getAsString();
            TypeHandler handler = typeHandlers.get(listType);
            if (handler == null) {
                return error(404, "Unknown type '" + listType + "'", requestId);
            }
            try {
                JsonObject response = handler.handle("list", new JsonObject());
                return addIdIfPresent(response, requestId);
            } catch (IllegalArgumentException e) {
                return error(400, e.getMessage(), requestId);
            } catch (java.util.NoSuchElementException e) {
                return error(404, e.getMessage(), requestId);
            } catch (Exception e) {
                return error(500, e.getMessage(), requestId);
            }
        }

        if (!message.has("type")) {
            return error(400, "Message must include 'type' or 'list'", requestId);
        }

        String type = message.get("type").getAsString();
        String method = message.has("method") ? message.get("method").getAsString() : "get";
        JsonObject data = message.has("data") && message.get("data").isJsonObject()
                ? message.getAsJsonObject("data")
                : new JsonObject();
        
        // Extract clientId from message if present
        String clientId = message.has("clientId") && message.get("clientId").isJsonPrimitive()
                ? message.get("clientId").getAsString()
                : null;

        return handleType(type, method, data, requestId, clientId);
    }

    private JsonObject handleType(String type, String method, JsonObject data, String requestId, String clientId) {
        String normalized = method == null ? "get" : method.toLowerCase(Locale.ROOT);
        if (!normalized.equals("get") && !normalized.equals("put") && !normalized.equals("post") && !normalized.equals("list")) {
            return error(400, "Unsupported method '" + method + "'", requestId);
        }

        TypeHandler handler = typeHandlers.get(type);
        if (handler == null) {
            return error(404, "Unknown type '" + type + "'", requestId);
        }

        try {
            JsonObject response;
            if (clientId != null && handler instanceof JsonThrottleHandler) {
                // Pass clientId for throttle handler
                response = handler.handle(normalized, data, clientId);
            } else {
                response = handler.handle(normalized, data);
            }
            return addIdIfPresent(response, requestId);
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage(), requestId);
        } catch (IllegalStateException e) {
            // Throttle busy errors
            return error(409, e.getMessage(), requestId);
        } catch (java.util.NoSuchElementException e) {
            return error(404, e.getMessage(), requestId);
        } catch (Exception e) {
            return error(500, e.getMessage(), requestId);
        }
    }

    private JsonObject addIdIfPresent(JsonObject response, String requestId) {
        if (requestId != null && response != null) {
            response.addProperty("id", requestId);
        }
        return response;
    }

    private JsonObject error(int code, String message, String requestId) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "error");
        JsonObject data = new JsonObject();
        data.addProperty("code", code);
        data.addProperty("message", message);
        error.add("data", data);
        if (requestId != null) {
            error.addProperty("id", requestId);
        }
        return error;
    }

    public interface TypeHandler {
        JsonObject handle(String method, JsonObject data);
        default JsonObject handle(String method, JsonObject data, String clientId) {
            // Default implementation for backward compatibility - add clientId to data if provided
            if (clientId != null && data != null) {
                data.addProperty("clientId", clientId);
            }
            return handle(method, data);
        }
    }
}


