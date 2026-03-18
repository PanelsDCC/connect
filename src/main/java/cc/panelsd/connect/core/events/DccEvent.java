package cc.panelsd.connect.core.events;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple immutable event envelope used inside the daemon; the HTTP/WebSocket
 * layer can serialize this to JSON for external clients.
 */
public final class DccEvent {

    private final DccEventType type;
    private final String connectionId;
    private final Map<String, Object> payload;

    public DccEvent(DccEventType type, String connectionId, Map<String, Object> payload) {
        this.type = type;
        this.connectionId = connectionId;
        this.payload = payload == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(payload));
    }

    public DccEventType getType() {
        return type;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}


