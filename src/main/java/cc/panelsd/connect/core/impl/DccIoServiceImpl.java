package cc.panelsd.connect.core.impl;

import cc.panelsd.connect.core.CommandStationConnection;
import cc.panelsd.connect.core.ControllerRole;
import cc.panelsd.connect.core.DccIoService;
import cc.panelsd.connect.core.DeviceDiscoveryService;
import cc.panelsd.connect.core.SystemConfig;
import cc.panelsd.connect.core.ThrottleSession;
import cc.panelsd.connect.core.events.DccEventBus;
import cc.panelsd.connect.core.impl.dccpp.DccppConnection;
import cc.panelsd.connect.core.impl.nce.NceSerialConnection;
import cc.panelsd.connect.core.impl.nce.NceUsbConnection;
import cc.panelsd.connect.core.impl.xnet.elite.XNetEliteConnection;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-memory implementation of {@link DccIoService} that manages JMRI-backed
 * connections and shares a single {@link DccEventBus} for outbound events.
 */
public final class DccIoServiceImpl implements DccIoService {

    private final Map<String, CommandStationConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, ThrottleSession> throttles = new ConcurrentHashMap<>();
    private final DccEventBus eventBus = new DccEventBus();
    private final DeviceDiscoveryService discoveryService = new DeviceDiscoveryService();
    
    // Track which controllers are assigned to which roles
    private volatile String throttleControllerId = null;  // Controller ID for throttles
    private volatile String accessoryControllerId = null; // Controller ID for accessories
    
    // Track connected device ports to detect new devices
    private final Set<String> connectedPorts = ConcurrentHashMap.newKeySet();
    
    // Map port to connection ID for cleanup when port disappears
    private final Map<String, String> portToConnectionId = new ConcurrentHashMap<>();
    
    // Background thread for continuous device monitoring
    private final ScheduledExecutorService deviceMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DeviceMonitor");
        t.setDaemon(true);
        return t;
    });
    
    /**
     * Start continuous device monitoring and auto-connect.
     */
    public void startDeviceMonitoring() {
        // Initial scan
        autoConnectDevices();
        
        // Schedule periodic scans (every 5 seconds)
        deviceMonitor.scheduleWithFixedDelay(() -> {
            try {
                autoConnectDevices();
            } catch (Exception e) {
                System.err.println("Error in device monitoring: " + e.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);
    }
    
    /**
     * Auto-discover and connect to detected controllers.
     * This will scan for devices and automatically create connections for any detected controllers.
     * Also checks for ports that have disappeared and removes those connections.
     */
    private void autoConnectDevices() {
        // First, check for ports that have disappeared and remove those connections
        checkForDisconnectedPorts();
        
        // Then, discover and connect to new devices
        java.util.List<DeviceDiscoveryService.DetectedDevice> devices = discoveryService.discoverDevices();
        for (DeviceDiscoveryService.DetectedDevice device : devices) {
            // Skip if already connected
            if (connectedPorts.contains(device.port)) {
                continue;
            }
            
            // Generate a connection ID from device name and port
            String connectionId = device.systemType.replace("-", "") + "_" + device.port.replace("/", "_").replace("\\", "_");
            
            // Skip if connection already exists
            if (connections.containsKey(connectionId)) {
                connectedPorts.add(device.port);
                portToConnectionId.put(device.port, connectionId);
                continue;
            }
            
            try {
                // Build SystemConfig with device settings
                SystemConfig.Builder builder = SystemConfig.builder(connectionId, device.systemType)
                        .userName(device.name)
                        .systemPrefix(connectionId);
                
                // Add port
                builder.option("portName", device.port);
                
                // Add config options from device config
                for (Map.Entry<String, String> entry : device.config.entrySet()) {
                    builder.option(entry.getKey(), entry.getValue());
                }
                
                SystemConfig config = builder.build();
                CommandStationConnection conn = createConnection(config);
                
                // Auto-connect
                conn.connect();
                connectedPorts.add(device.port);
                portToConnectionId.put(device.port, connectionId);
                System.out.println("Auto-connected to " + device.name + " on " + device.port);
                
                // Auto-assign roles if not already assigned
                assignControllerRoles(connectionId);
                
            } catch (Exception e) {
                System.err.println("Failed to auto-connect to " + device.name + " on " + device.port + ": " + e.getMessage());
            }
        }
    }
    
    /**
     * Check for ports that have disappeared and remove connections using those ports.
     */
    private void checkForDisconnectedPorts() {
        // Create a copy of the port set to avoid concurrent modification
        Set<String> portsToCheck = new HashSet<>(connectedPorts);
        
        for (String port : portsToCheck) {
            // Check if port still exists
            if (!discoveryService.isPortPresent(port)) {
                String connectionId = portToConnectionId.get(port);
                if (connectionId != null) {
                    System.out.println("Port " + port + " no longer available, removing connection " + connectionId);
                    removeConnection(connectionId);
                } else {
                    // Port was tracked but no connection ID found - just clean up
                    connectedPorts.remove(port);
                }
            }
        }
    }
    
    /**
     * Auto-assign controller roles based on current assignments.
     * If only one controller, assign it to both roles.
     * If multiple controllers, assign first detected to both roles until user configures.
     */
    private void assignControllerRoles(String connectionId) {
        synchronized (this) {
            // If this is the first/only controller, assign it to both roles
            if (throttleControllerId == null && accessoryControllerId == null) {
                throttleControllerId = connectionId;
                accessoryControllerId = connectionId;
                System.out.println("Assigned " + connectionId + " to both throttles and accessories (only controller)");
            } else if (throttleControllerId == null) {
                // No throttle controller yet, but we have an accessory controller
                throttleControllerId = connectionId;
                System.out.println("Assigned " + connectionId + " to throttles");
            } else if (accessoryControllerId == null) {
                // No accessory controller yet, but we have a throttle controller
                accessoryControllerId = connectionId;
                System.out.println("Assigned " + connectionId + " to accessories");
            }
            // If both are already assigned, don't auto-assign (user must configure)
        }
    }
    
    /**
     * Set controller role assignment.
     */
    public void setControllerRole(String connectionId, ControllerRole role, boolean enabled) {
        synchronized (this) {
            if (enabled) {
                // Check if another controller already has this role
                if (role == ControllerRole.THROTTLES && throttleControllerId != null && !throttleControllerId.equals(connectionId)) {
                    throw new IllegalArgumentException("Another controller (" + throttleControllerId + ") is already assigned to throttles");
                }
                if (role == ControllerRole.ACCESSORIES && accessoryControllerId != null && !accessoryControllerId.equals(connectionId)) {
                    throw new IllegalArgumentException("Another controller (" + accessoryControllerId + ") is already assigned to accessories");
                }
                
                if (role == ControllerRole.THROTTLES) {
                    throttleControllerId = connectionId;
                } else {
                    accessoryControllerId = connectionId;
                }
            } else {
                // Disable role
                if (role == ControllerRole.THROTTLES && throttleControllerId != null && throttleControllerId.equals(connectionId)) {
                    throttleControllerId = null;
                }
                if (role == ControllerRole.ACCESSORIES && accessoryControllerId != null && accessoryControllerId.equals(connectionId)) {
                    accessoryControllerId = null;
                }
            }
        }
    }
    
    /**
     * Get controller ID for throttles.
     */
    public String getThrottleControllerId() {
        return throttleControllerId;
    }
    
    /**
     * Get controller ID for accessories.
     */
    public String getAccessoryControllerId() {
        return accessoryControllerId;
    }
    
    /**
     * Get controller for throttles (or first available if none assigned).
     */
    public CommandStationConnection getThrottleController() {
        if (throttleControllerId != null) {
            CommandStationConnection conn = connections.get(throttleControllerId);
            if (conn != null && conn.isConnected()) {
                return conn;
            }
        }
        // Fallback to first connected controller
        for (CommandStationConnection conn : connections.values()) {
            if (conn.isConnected()) {
                return conn;
            }
        }
        return null;
    }
    
    /**
     * Get controller for accessories (or first available if none assigned).
     */
    public CommandStationConnection getAccessoryController() {
        if (accessoryControllerId != null) {
            CommandStationConnection conn = connections.get(accessoryControllerId);
            if (conn != null && conn.isConnected()) {
                return conn;
            }
        }
        // Fallback to first connected controller
        for (CommandStationConnection conn : connections.values()) {
            if (conn.isConnected()) {
                return conn;
            }
        }
        return null;
    }
    
    public DeviceDiscoveryService getDiscoveryService() {
        return discoveryService;
    }

    @Override
    public CommandStationConnection createConnection(SystemConfig config) {
        CommandStationConnection existing = connections.get(config.getId());
        if (existing != null) {
            return existing;
        }
        CommandStationConnection conn;
        switch (config.getSystemType()) {
            case "xnet-elite":
                conn = new XNetEliteConnection(config, eventBus);
                break;
            case "dccpp":
            case "dccpp-ethernet":
                conn = new DccppConnection(config, eventBus);
                break;
            case "nce-serial":
                conn = new NceSerialConnection(config, eventBus);
                break;
            case "nce-usb":
                conn = new NceUsbConnection(config, eventBus);
                break;
            default:
                throw new IllegalArgumentException("Unsupported systemType: " + config.getSystemType());
        }
        connections.put(config.getId(), conn);
        
        // Track port if this connection uses a serial port
        String portName = config.getOption("portName");
        if (portName != null && !portName.isEmpty()) {
            connectedPorts.add(portName);
            portToConnectionId.put(portName, config.getId());
        }
        
        return conn;
    }

    @Override
    public CommandStationConnection getConnection(String id) {
        return connections.get(id);
    }

    @Override
    public Collection<CommandStationConnection> getConnections() {
        return Collections.unmodifiableCollection(connections.values());
    }

    @Override
    public void removeConnection(String id) {
        CommandStationConnection conn = connections.remove(id);
        if (conn != null) {
            // First, close and remove all throttles associated with this connection.
            // This ensures JMRI throttle allocations are released when a connection
            // is removed (e.g., device unplugged), so they don't hold locks or
            // point at a dead connection when the controller returns.
            throttles.entrySet().removeIf(entry -> {
                ThrottleSession throttle = entry.getValue();
                if (id.equals(throttle.getConnectionId())) {
                    try {
                        throttle.close();
                    } catch (Exception e) {
                        // Ignore errors during cleanup
                    }
                    return true; // remove this throttle from the map
                }
                return false;
            });

            try {
                conn.close();
            } catch (Exception ignore) {
                // ignore
            }
            // Clear role assignments if this connection had any
            synchronized (this) {
                if (id.equals(throttleControllerId)) {
                    throttleControllerId = null;
                }
                if (id.equals(accessoryControllerId)) {
                    accessoryControllerId = null;
                }
            }
            // Remove from connected ports and port mapping
            // Find the port for this connection ID
            String portToRemove = null;
            for (Map.Entry<String, String> entry : portToConnectionId.entrySet()) {
                if (entry.getValue().equals(id)) {
                    portToRemove = entry.getKey();
                    break;
                }
            }
            if (portToRemove != null) {
                connectedPorts.remove(portToRemove);
                portToConnectionId.remove(portToRemove);
            }
        }
    }

    public DccEventBus getEventBus() {
        return eventBus;
    }
    
    /**
     * Open a throttle session.
     * @param connectionId The connection ID
     * @param address The locomotive address
     * @param longAddress Whether this is a long address
     * @return The throttle session ID (format: "connectionId:address:longAddress")
     */
    public String openThrottle(String connectionId, int address, boolean longAddress) throws IOException {
        // If connectionId is null, use the assigned throttle controller
        CommandStationConnection conn;
        if (connectionId == null || connectionId.isEmpty()) {
            conn = getThrottleController();
            if (conn == null) {
                throw new IOException("No throttle controller available");
            }
            connectionId = conn.getId();
        } else {
            conn = connections.get(connectionId);
            if (conn == null) {
                throw new IOException("Connection not found: " + connectionId);
            }
        }
        if (!conn.isConnected()) {
            throw new IOException("Connection not connected: " + connectionId);
        }
        String throttleId = connectionId + ":" + address + ":" + longAddress;
        ThrottleSession existing = throttles.get(throttleId);
        if (existing != null) {
            return throttleId; // Already open
        }
        ThrottleSession throttle = conn.openThrottle(address, longAddress);
        throttles.put(throttleId, throttle);
        return throttleId;
    }
    
    /**
     * Get a throttle session by ID.
     */
    public ThrottleSession getThrottle(String throttleId) {
        return throttles.get(throttleId);
    }
    
    /**
     * Get all active throttle sessions.
     */
    public Collection<ThrottleSession> getThrottles() {
        // Clean up any throttles whose owning connection is no longer present
        // or no longer connected. This prevents reusing "zombie" throttle
        // sessions that still point at a dead JMRI connection when a controller
        // is unplugged and later reconnected.
        throttles.entrySet().removeIf(entry -> {
            ThrottleSession throttle = entry.getValue();
            CommandStationConnection conn = connections.get(throttle.getConnectionId());
            if (conn == null || !conn.isConnected()) {
                try {
                    throttle.close();
                } catch (Exception e) {
                    // Ignore errors during cleanup
                }
                return true; // remove stale throttle
            }
            return false;
        });

        return Collections.unmodifiableCollection(throttles.values());
    }
    
    /**
     * Close a throttle session.
     */
    public void closeThrottle(String throttleId) {
        ThrottleSession throttle = throttles.remove(throttleId);
        if (throttle != null) {
            try {
                throttle.close();
            } catch (Exception e) {
                // Ignore errors during cleanup
            }
        }
    }

    @Override
    public void close() {
        // Stop device monitoring
        deviceMonitor.shutdown();
        try {
            if (!deviceMonitor.awaitTermination(2, TimeUnit.SECONDS)) {
                deviceMonitor.shutdownNow();
            }
        } catch (InterruptedException e) {
            deviceMonitor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Close all throttles first
        for (ThrottleSession throttle : throttles.values()) {
            try {
                throttle.close();
            } catch (Exception ignore) {
                // ignore
            }
        }
        throttles.clear();
        // Then close connections
        for (CommandStationConnection conn : connections.values()) {
            conn.close();
        }
        connections.clear();
        connectedPorts.clear();
        portToConnectionId.clear();
    }
}


