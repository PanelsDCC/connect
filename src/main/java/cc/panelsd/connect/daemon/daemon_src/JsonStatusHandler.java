package cc.panelsd.connect.daemon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import cc.panelsd.connect.core.CommandStationConnection;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reports server/connection status over the WebSocket JSON API.
 */
public class JsonStatusHandler implements JsonMessageHandler.TypeHandler {

    private final StatusProvider provider;
    private JsonBroadcaster broadcaster;

    public JsonStatusHandler(StatusProvider provider) {
        this.provider = provider;
    }

    public void setBroadcaster(JsonBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /**
     * Build a connection object with all fields.
     */
    private JsonObject buildConnectionObject(CommandStationConnection c, boolean includeFullDetails) {
        JsonObject conn = new JsonObject();
        conn.addProperty("id", c.getId());
        
        if (includeFullDetails) {
            // Only include systemType and commandStation for new connections
            conn.addProperty("systemType", c.getSystemType());
            
            Map<String, String> csInfo = c.getCommandStationInfo();
            if (csInfo != null && !csInfo.isEmpty()) {
                JsonObject cs = new JsonObject();
                for (Map.Entry<String, String> entry : csInfo.entrySet()) {
                    cs.addProperty(entry.getKey(), entry.getValue());
                }
                conn.add("commandStation", cs);
            }
        }
        
        conn.addProperty("connected", c.isConnected());
        
        String power = c.getPowerStatus();
        if (power != null) {
            conn.addProperty("powerStatus", power);
        }
        
        String throttleId = provider.getThrottleControllerId();
        String accessoryId = provider.getAccessoryControllerId();
        JsonArray roles = new JsonArray();
        if (c.getId().equals(throttleId)) {
            roles.add("throttles");
        }
        if (c.getId().equals(accessoryId)) {
            roles.add("accessories");
        }
        conn.add("roles", roles);
        
        return conn;
    }

    /**
     * Build a delta patch for changed connections.
     * @param previousState Map of connection ID to previous connection JSON object
     * @return JsonObject with delta patch, or null if nothing changed
     */
    public JsonObject buildStatusDelta(Map<String, JsonObject> previousState) {
        JsonArray changedConnections = new JsonArray();
        
        for (CommandStationConnection c : provider.getConnections()) {
            String connId = c.getId();
            JsonObject previous = previousState.get(connId);
            boolean isNew = (previous == null);
            
            // Build current connection state
            JsonObject current = buildConnectionObject(c, isNew);
            
            // Check if anything changed
            boolean changed = false;
            if (isNew) {
                changed = true;
            } else {
                // Compare with previous state
                boolean connectedChanged = current.get("connected").getAsBoolean() != previous.get("connected").getAsBoolean();
                String currentPower = current.has("powerStatus") ? current.get("powerStatus").getAsString() : null;
                String previousPower = previous.has("powerStatus") ? previous.get("powerStatus").getAsString() : null;
                boolean powerChanged = (currentPower == null && previousPower != null) || 
                                      (currentPower != null && !currentPower.equals(previousPower));
                
                // Compare roles
                JsonArray currentRoles = current.getAsJsonArray("roles");
                JsonArray previousRoles = previous.has("roles") ? previous.getAsJsonArray("roles") : new JsonArray();
                boolean rolesChanged = !rolesEqual(currentRoles, previousRoles);
                
                if (connectedChanged || powerChanged || rolesChanged) {
                    changed = true;
                }
            }
            
            if (changed) {
                changedConnections.add(current);
            }
        }
        
        // Also check for disconnected connections (in previous but not current)
        for (String prevId : previousState.keySet()) {
            boolean stillExists = provider.getConnections().stream()
                    .anyMatch(c -> c.getId().equals(prevId));
            if (!stillExists) {
                // Connection was removed - send update with connected: false
                // Include previous powerStatus and roles if available
                JsonObject previous = previousState.get(prevId);
                JsonObject removed = new JsonObject();
                removed.addProperty("id", prevId);
                removed.addProperty("connected", false);
                if (previous != null) {
                    if (previous.has("powerStatus")) {
                        removed.addProperty("powerStatus", previous.get("powerStatus").getAsString());
                    }
                    if (previous.has("roles")) {
                        removed.add("roles", previous.getAsJsonArray("roles"));
                    }
                }
                changedConnections.add(removed);
            }
        }
        
        if (changedConnections.size() == 0) {
            return null; // No changes
        }
        
        JsonObject data = new JsonObject();
        data.addProperty("status", "ok");
        data.add("connections", changedConnections);
        
        JsonObject patch = new JsonObject();
        patch.addProperty("type", "status");
        patch.addProperty("method", "patch");
        patch.add("data", data);
        return patch;
    }

    /**
     * Broadcast a status patch to all connected WebSocket clients.
     * This should be called when connection state or power status changes.
     * @param previousState Map of connection ID to previous connection JSON object for delta calculation
     */
    public void broadcastStatusPatch(Map<String, JsonObject> previousState) {
        if (broadcaster == null) {
            return;
        }
        JsonObject patch = buildStatusDelta(previousState);
        if (patch != null) {
            broadcaster.broadcast(patch);
        }
    }

    @Override
    public JsonObject handle(String method, JsonObject data) {
        String normalized = method == null ? "get" : method.toLowerCase(Locale.ROOT);
        if ("post".equals(normalized)) {
            return handlePost(data);
        }
        if (!"get".equals(normalized) && !"list".equals(normalized)) {
            throw new IllegalArgumentException("Unsupported method '" + method + "'");
        }
        // Don't broadcast status queries - only return to requester
        // Broadcasting should only happen for actual state changes
        return buildStatus();
    }

    private JsonObject handlePost(JsonObject data) {
        // Check for power control command
        if (data.has("power") && data.has("connectionId")) {
            String connectionId = data.get("connectionId").getAsString();
            String powerState = data.get("power").getAsString();
            
            // Find the connection
            CommandStationConnection connection = null;
            for (CommandStationConnection c : provider.getConnections()) {
                if (c.getId().equals(connectionId)) {
                    connection = c;
                    break;
                }
            }
            
            if (connection == null) {
                throw new IllegalArgumentException("Connection not found: " + connectionId);
            }
            
            if (!connection.isConnected()) {
                throw new IllegalStateException("Connection not connected: " + connectionId);
            }
            
            // Get previous state for delta calculation
            Map<String, JsonObject> previousState = getCurrentState();
            
            // Set power
            try {
                connection.setPower(powerState);
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to set power: " + e.getMessage(), e);
            }
            
            // Broadcast status patch with updated power status
            broadcastStatusPatch(previousState);
            
            // Return updated status for this connection
            JsonObject response = new JsonObject();
            response.addProperty("type", "status");
            JsonObject responseData = new JsonObject();
            responseData.addProperty("status", "ok");
            JsonArray connections = new JsonArray();
            connections.add(buildConnectionObject(connection, false));
            responseData.add("connections", connections);
            response.add("data", responseData);
            return response;
        }
        
        throw new IllegalArgumentException("POST to status requires 'connectionId' and 'power' fields");
    }

    public JsonObject buildStatus() {
        JsonArray connections = new JsonArray();
        for (CommandStationConnection c : provider.getConnections()) {
            connections.add(buildConnectionObject(c, true));
        }

        JsonObject data = new JsonObject();
        data.addProperty("status", "ok");
        data.add("connections", connections);

        JsonObject response = new JsonObject();
        response.addProperty("type", "status");
        response.add("data", data);
        return response;
    }
    
    /**
     * Compare two JsonArrays for equality.
     */
    private boolean rolesEqual(JsonArray a1, JsonArray a2) {
        if (a1.size() != a2.size()) {
            return false;
        }
        for (int i = 0; i < a1.size(); i++) {
            if (!a1.get(i).getAsString().equals(a2.get(i).getAsString())) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Get current state as a map for delta tracking.
     */
    public Map<String, JsonObject> getCurrentState() {
        Map<String, JsonObject> state = new HashMap<>();
        for (CommandStationConnection c : provider.getConnections()) {
            state.put(c.getId(), buildConnectionObject(c, true));
        }
        return state;
    }

    public interface StatusProvider {
        Collection<CommandStationConnection> getConnections();
        String getThrottleControllerId();
        String getAccessoryControllerId();
    }
}

