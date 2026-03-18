package cc.panelsd.connect.daemon;

import cc.panelsd.connect.core.impl.DccIoServiceImpl;
import cc.panelsd.connect.core.ThrottleSession;
import cc.panelsd.connect.core.events.DccEvent;
import cc.panelsd.connect.core.events.DccEventType;
import cc.panelsd.connect.core.events.DccEventListener;
import cc.panelsd.connect.daemon.JsonMessageHandler;
import cc.panelsd.connect.daemon.JsonWebSocketHandler;
import cc.panelsd.connect.daemon.JsonThrottleHandler;
import cc.panelsd.connect.daemon.JsonAccessoriesHandler;
import cc.panelsd.connect.daemon.DccAccessoryService;
import cc.panelsd.connect.daemon.JsonStatusHandler;
import com.google.gson.JsonObject;
import jmri.Throttle;
import java.io.IOException;

/**
 * Entry point for the standalone DCC IO daemon.
 *
 * Usage:
 * <pre>
 *   java -cp ... cc.panelsd.connect.daemon.DccIoDaemon [port]
 * </pre>
 *
 * The daemon will start an embedded HTTP server exposing the minimal
 * management API implemented in {@link DccIoHttpServer}.
 */
public final class DccIoDaemon {

    private DccIoDaemon() {
        // no instances
    }

    public static void main(String[] args) throws Exception {
        int port = 9000;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignore) {
                // use default
            }
        }
        DccIoServiceImpl service = new DccIoServiceImpl();
        
        // Start continuous device monitoring and auto-connect
        System.out.println("Starting device monitoring...");
        service.startDeviceMonitoring();

        JsonMessageHandler messageHandler = new JsonMessageHandler();
        JsonThrottleHandler throttleHandler = new JsonThrottleHandler(new DccThrottleService(service));
        messageHandler.registerTypeHandler("throttles", throttleHandler);
        messageHandler.registerTypeHandler("throttle", throttleHandler);
        JsonAccessoriesHandler accessoriesHandler = new JsonAccessoriesHandler(new DccAccessoryService(service));
        messageHandler.registerTypeHandler("accessories", accessoriesHandler);
        JsonStatusHandler statusHandler = new JsonStatusHandler(new JsonStatusHandler.StatusProvider() {
            @Override
            public java.util.Collection<cc.panelsd.connect.core.CommandStationConnection> getConnections() {
                return service.getConnections();
            }

            @Override
            public String getThrottleControllerId() {
                return service.getThrottleControllerId();
            }

            @Override
            public String getAccessoryControllerId() {
                return service.getAccessoryControllerId();
            }
        });
        messageHandler.registerTypeHandler("status", statusHandler);
        int websocketPort = port + 1; // run WebSocket on adjacent port to avoid HttpServer conflict
        JsonWebSocketHandler webSocketHandler = new JsonWebSocketHandler(websocketPort, "/json", messageHandler);
        JsonBroadcaster broadcaster = webSocketHandler.getBroadcaster();
        throttleHandler.setBroadcaster(broadcaster);
        accessoriesHandler.setBroadcaster(broadcaster);
        statusHandler.setBroadcaster(broadcaster);
        webSocketHandler.start();
        System.out.println("WebSocket JSON API listening on port " + websocketPort + " at /json");
        
        // Subscribe to throttle events from the controller to broadcast via WebSocket
        DccThrottleService throttleService = new DccThrottleService(service);
        service.getEventBus().addListener(new ThrottleEventBroadcaster(broadcaster, throttleService));
        
        // Subscribe to connection and power status changes to broadcast status patches
        service.getEventBus().addListener(new StatusEventBroadcaster(statusHandler));
        
        DccIoHttpServer httpServer = new DccIoHttpServer(service, port);
        httpServer.setStatusHandler(statusHandler);
        httpServer.start();
        System.out.println("DCC IO daemon listening on port " + port);
        System.out.println("Press Ctrl+C to stop the daemon");
        
        // Register shutdown hook for graceful shutdown
        final Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down DCC IO daemon...");
            try {
                // Interrupt main thread to wake it up if it's waiting
                mainThread.interrupt();
                throttleHandler.shutdown();
                webSocketHandler.shutdown();
                // Stop HTTP server (give it 2 seconds to finish current requests)
                httpServer.stop(2);
                // Close all connections
                service.close();
                System.out.println("Daemon stopped successfully");
            } catch (Exception e) {
                System.err.println("Error during shutdown: " + e.getMessage());
                e.printStackTrace();
            }
        }));
        
        // Keep the main thread alive, but make it interruptible
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            // Expected on shutdown
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Listens to throttle events from the controller and broadcasts them via WebSocket.
     */
    private static class ThrottleEventBroadcaster implements DccEventListener {
        private final JsonBroadcaster broadcaster;
        private final DccThrottleService throttleService;
        
        ThrottleEventBroadcaster(JsonBroadcaster broadcaster, DccThrottleService throttleService) {
            this.broadcaster = broadcaster;
            this.throttleService = throttleService;
        }
        
        @Override
        public void onEvent(DccEvent event) {
            if (event.getType() != DccEventType.THROTTLE_UPDATED) {
                return;
            }
            
            var payload = event.getPayload();
            String propertyName = (String) payload.get("property");
            if (propertyName == null) {
                return;
            }
            
            // Get address and longAddress from payload
            Object addressObj = payload.get("address");
            Object longAddressObj = payload.get("longAddress");
            if (addressObj == null) {
                return;
            }
            
            int address = ((Number) addressObj).intValue();
            boolean longAddress = longAddressObj != null && ((Boolean) longAddressObj);
            
            // Find the throttle session to get the throttle ID
            ThrottleSession session = null;
            for (ThrottleSession s : throttleService.getThrottles()) {
                if (s.getAddress() == address && s.isLongAddress() == longAddress) {
                    session = s;
                    break;
                }
            }
            
            // If throttle doesn't exist, try to create it (controller opened a throttle)
            // Note: If the controller is already controlling this throttle, opening it will fail
            // with "in use" error. In that case, we skip tracking it since the controller has control.
            boolean throttleJustCreated = false;
            if (session == null) {
                try {
                    String throttleId = throttleService.openThrottle(null, address, longAddress);
                    session = throttleService.getThrottle(throttleId);
                    if (session == null) {
                        // Failed to create throttle, skip broadcast
                        return;
                    }
                    throttleJustCreated = true;
                } catch (IOException e) {
                    // Throttle creation failed - likely because controller is already controlling it
                    // ("address in use"). We can't track throttles that are directly controlled
                    // by the controller without our session, so skip the broadcast.
                    // The console will still show the controller's messages.
                    return;
                }
            }
            
            // Build throttle ID
            String throttleId = session.getConnectionId() + ":" + address + ":" + longAddress;
            
            // Convert property change to WebSocket patch format
            JsonObject patch = new JsonObject();
            patch.addProperty("type", "throttle");
            patch.addProperty("method", "patch");
            JsonObject data = new JsonObject();
            data.addProperty("throttle", throttleId);
            data.addProperty("address", address);
            data.addProperty("longAddress", longAddress);
            
            // If throttle was just created, include opened flag
            if (throttleJustCreated) {
                data.addProperty("opened", true);
            }
            
            Object newValue = payload.get("newValue");
            
            // Map JMRI property names to WebSocket field names
            if (propertyName.equals(Throttle.SPEEDSETTING)) {
                if (newValue instanceof Number) {
                    data.addProperty("speed", ((Number) newValue).floatValue());
                    // Always include direction when speed is included
                    data.addProperty("forward", session.getDirection());
                }
            } else if (propertyName.equals(Throttle.ISFORWARD)) {
                if (newValue instanceof Boolean) {
                    data.addProperty("forward", ((Boolean) newValue));
                }
            } else if (propertyName.startsWith("F") && !propertyName.endsWith("Momentary")) {
                // Function change (F0, F1, F2, etc.) - send as functions object
                String funcPart = propertyName.substring(1);
                if (funcPart.matches("\\d+")) {
                    try {
                        int funcNum = Integer.parseInt(funcPart);
                        if (newValue instanceof Boolean) {
                            JsonObject functions = new JsonObject();
                            functions.addProperty(String.valueOf(funcNum), ((Boolean) newValue));
                            data.add("functions", functions);
                        }
                    } catch (NumberFormatException e) {
                        // Not a valid function number, skip
                        return;
                    }
                } else {
                    // Not a function property we care about
                    return;
                }
            } else {
                // Not a property we broadcast (e.g., Momentary functions)
                return;
            }
            
            patch.add("data", data);
            broadcaster.broadcast(patch);
        }
    }
    
    /**
     * Listens to connection state and power status events and broadcasts status patches via WebSocket.
     */
    private static class StatusEventBroadcaster implements DccEventListener {
        private final JsonStatusHandler statusHandler;
        private java.util.Map<String, com.google.gson.JsonObject> previousState = new java.util.HashMap<>();
        
        StatusEventBroadcaster(JsonStatusHandler statusHandler) {
            this.statusHandler = statusHandler;
        }
        
        @Override
        public void onEvent(DccEvent event) {
            if (event.getType() == DccEventType.CONNECTION_STATE_CHANGED || 
                event.getType() == DccEventType.POWER_CHANGED) {
                // Build delta patch based on previous state
                statusHandler.broadcastStatusPatch(previousState);
                // Update previous state to current state
                previousState = statusHandler.getCurrentState();
            }
        }
    }
}


