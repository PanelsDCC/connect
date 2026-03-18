package cc.panelsd.connect.core.impl.common;

import cc.panelsd.connect.core.AccessoryController;
import cc.panelsd.connect.core.CommandStationConnection;
import cc.panelsd.connect.core.ProgrammerSession;
import cc.panelsd.connect.core.SystemConfig;
import cc.panelsd.connect.core.ThrottleSession;
import cc.panelsd.connect.core.events.DccEvent;
import cc.panelsd.connect.core.events.DccEventBus;
import cc.panelsd.connect.core.events.DccEventType;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Common base for concrete JMRI-backed connections.
 */
public abstract class BaseCommandStationConnection implements CommandStationConnection {

    protected final String id;
    protected final SystemConfig config;
    protected final DccEventBus eventBus;

    protected volatile boolean connected;

    protected BaseCommandStationConnection(SystemConfig config, DccEventBus eventBus) {
        this.id = config.getId();
        this.config = config;
        this.eventBus = eventBus;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getSystemType() {
        return config.getSystemType();
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    protected void publishConnectionState() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("connected", connected);
        eventBus.publish(new DccEvent(DccEventType.CONNECTION_STATE_CHANGED, id, payload));
    }
    
    /**
     * Format a message using JMRI's built-in formatters if available.
     * This works generically across all JMRI message types.
     * 
     * @param msg The message to format
     * @return Formatted string, or toString() if formatting not available
     */
    protected String formatMessage(jmri.jmrix.AbstractMessage msg) {
        if (msg == null) return "";
        try {
            // Try toMonitorString() if available (via reflection to be protocol-agnostic)
            java.lang.reflect.Method toMonitor = msg.getClass().getMethod("toMonitorString");
            String formatted = (String) toMonitor.invoke(msg);
            if (formatted != null && !formatted.equals(msg.toString())) {
                return formatted;
            }
        } catch (Exception e) {
            // Method doesn't exist or failed - use toString()
        }
        return msg.toString();
    }

    @Override
    public abstract void connect() throws IOException;

    @Override
    public abstract ThrottleSession openThrottle(int address, boolean longAddress) throws IOException;

    @Override
    public abstract ProgrammerSession getProgrammer();

    @Override
    public abstract AccessoryController getAccessoryController();

    @Override
    public java.util.Map<String, String> getCommandStationInfo() {
        // Default implementation returns null - subclasses should override
        return null;
    }

    @Override
    public String getPowerStatus() {
        // Default implementation returns UNKNOWN - subclasses should override
        return "UNKNOWN";
    }

    @Override
    public void setPower(String powerState) throws IOException {
        // Default implementation: power control is protocol-specific
        // Subclasses should override if they support power control
        if (!connected) {
            throw new IOException("Not connected");
        }
        throw new IOException("Power control not supported for this connection type");
    }

    @Override
    public void requestVersion() throws IOException {
        // Default implementation: version requests are protocol-specific
        // Subclasses should override if they support version requests
        if (!connected) {
            throw new IOException("Not connected");
        }
        throw new IOException("Version request not supported for this connection type");
    }

    @Override
    public abstract void close();
}


