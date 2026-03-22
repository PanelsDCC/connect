package cc.panelsd.connect.daemon;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import cc.panelsd.connect.core.CommandStationConnection;
import cc.panelsd.connect.core.SystemConfig;
import cc.panelsd.connect.core.impl.DccIoServiceImpl;
import cc.panelsd.connect.updater.ConnectUpdater;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Minimal HTTP front-end for the DCC IO service.
 *
 * This uses the JDK's built-in {@link HttpServer} to avoid additional
 * dependencies. It provides very small, pragmatic endpoints:
 * <ul>
 *   <li>GET /health - daemon health</li>
 *   <li>GET /connections - list active connections</li>
 *   <li>POST /connections/create - create a connection with query params</li>
 *   <li>GET /api/update - update status JSON (from update-status.json)</li>
 *   <li>POST /api/update/check - refresh status from GitHub</li>
 *   <li>POST /api/update/install - install latest .deb (requires sudo; see sudoers)</li>
 * </ul>
 */
final class DccIoHttpServer {

    private final DccIoServiceImpl service;
    private final cc.panelsd.connect.core.DeviceDiscoveryService discoveryService;
    private final HttpServer server;
    private JsonStatusHandler statusHandler;

    DccIoHttpServer(DccIoServiceImpl service, int port) throws IOException {
        this.service = service;
        this.discoveryService = service.getDiscoveryService();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", new HealthHandler());
        server.createContext("/connections", new ConnectionsHandler());
        server.createContext("/connections/create", new CreateConnectionHandler());
        server.createContext("/connections/requestVersion", new RequestVersionHandler());
        server.createContext("/connections/setRole", new SetRoleHandler());
        server.createContext("/api/ports", new PortsHandler());
        server.createContext("/api/systems", new SystemsHandler());
        server.createContext("/api/discover", new DiscoverHandler());
        server.createContext("/api/events", new EventsHandler()); // SSE endpoint for live events
        // Software update API (single prefix; subpaths routed in handler — HttpServer matches /api/update*)
        server.createContext("/api/update", new UpdateApiHandler());
        server.createContext("/static", new StaticFileHandler()); // Serve static files (CSS, JS)
        server.createContext("/", new WebUIHandler()); // Serve web UI
        server.setExecutor(null); // default executor
    }

    void setStatusHandler(JsonStatusHandler statusHandler) {
        this.statusHandler = statusHandler;
    }

    void start() {
        server.start();
    }

    void stop(int delaySeconds) {
        server.stop(delaySeconds);
    }

    /**
     * Runs the packaged updater script. When the daemon runs as {@code dcc-io}, install uses
     * {@code sudo -n} (requires {@code /etc/sudoers.d/panelsdcc-connect-updater}).
     */
    private static void runPanelsdccUpdaterInstall() throws IOException, InterruptedException {
        String script = "/usr/local/bin/panelsdcc-connect-updater";
        if ("root".equals(System.getProperty("user.name"))) {
            ProcessBuilder pb = new ProcessBuilder(script, "install");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("install failed (exit " + code + "): " + out.trim());
            }
            return;
        }
        ProcessBuilder pb = new ProcessBuilder("sudo", "-n", script, "install");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("install failed (exit " + code + "): " + out.trim()
                    + ". Ensure /etc/sudoers.d/panelsdcc-connect-updater allows dcc-io to run "
                    + script + " install without a password.");
        }
    }

    private abstract class JsonHandler implements HttpHandler {

        @Override
        public final void handle(HttpExchange exchange) throws IOException {
            try {
                handleJson(exchange);
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            }
        }

        protected abstract void handleJson(HttpExchange exchange) throws IOException;

        protected void sendJson(HttpExchange exchange, int status, String body) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        /** Send already-serialized JSON (e.g. from {@link ConnectUpdater#getStatusJsonString()}). */
        protected void sendJsonRaw(HttpExchange exchange, int status, String jsonBody) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        protected Map<String, String> queryParams(URI uri) {
            Map<String, String> params = new HashMap<>();
            String query = uri.getRawQuery();
            if (query == null || query.isEmpty()) {
                return params;
            }
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    String key = decode(pair.substring(0, idx));
                    String value = decode(pair.substring(idx + 1));
                    params.put(key, value);
                }
            }
            return params;
        }

        private String decode(String s) {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        }

        protected String escape(String s) {
            if (s == null) {
                return "";
            }
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    private final class HealthHandler extends JsonHandler {
        @Override
        protected void handleJson(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            sendJson(exchange, 200, "{\"status\":\"ok\"}");
        }
    }

    private final class ConnectionsHandler extends JsonHandler {
        @Override
        protected void handleJson(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            StringJoiner joiner = new StringJoiner(",", "{\"connections\":[", "]}");
            for (CommandStationConnection c : service.getConnections()) {
                StringJoiner connJson = new StringJoiner(",");
                connJson.add("\"id\":\"" + escape(c.getId()) + "\"");
                connJson.add("\"systemType\":\"" + escape(c.getSystemType()) + "\"");
                connJson.add("\"connected\":" + c.isConnected());
                
                // Add command station info if available
                java.util.Map<String, String> csInfo = c.getCommandStationInfo();
                if (csInfo != null && !csInfo.isEmpty()) {
                    StringJoiner infoJson = new StringJoiner(",");
                    for (java.util.Map.Entry<String, String> entry : csInfo.entrySet()) {
                        infoJson.add("\"" + escape(entry.getKey()) + "\":\"" + escape(entry.getValue()) + "\"");
                    }
                    connJson.add("\"commandStation\":{" + infoJson.toString() + "}");
                }
                
                // Add power status
                String powerStatus = c.getPowerStatus();
                if (powerStatus != null) {
                    connJson.add("\"powerStatus\":\"" + escape(powerStatus) + "\"");
                }
                
                // Add role assignments
                String throttleControllerId = ((DccIoServiceImpl) service).getThrottleControllerId();
                String accessoryControllerId = ((DccIoServiceImpl) service).getAccessoryControllerId();
                java.util.List<String> roles = new java.util.ArrayList<>();
                if (c.getId().equals(throttleControllerId)) {
                    roles.add("throttles");
                }
                if (c.getId().equals(accessoryControllerId)) {
                    roles.add("accessories");
                }
                if (!roles.isEmpty()) {
                    StringJoiner rolesJson = new StringJoiner(",");
                    for (String role : roles) {
                        rolesJson.add("\"" + escape(role) + "\"");
                    }
                    connJson.add("\"roles\":[" + rolesJson.toString() + "]");
                }
                
                joiner.add("{" + connJson.toString() + "}");
            }
            sendJson(exchange, 200, joiner.toString());
        }
    }

    private final class RequestVersionHandler extends JsonHandler {
        @Override
        protected void handleJson(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            Map<String, String> q = queryParams(exchange.getRequestURI());
            String connectionId = q.get("id");
            if (connectionId == null || connectionId.isEmpty()) {
                sendJson(exchange, 400, "{\"error\":\"Missing connection id\"}");
                return;
            }
            CommandStationConnection conn = service.getConnection(connectionId);
            if (conn == null) {
                sendJson(exchange, 404, "{\"error\":\"Connection not found\"}");
                return;
            }
            if (!conn.isConnected()) {
                sendJson(exchange, 400, "{\"error\":\"Connection not connected\"}");
                return;
            }
            try {
                conn.requestVersion();
                sendJson(exchange, 200, "{\"status\":\"ok\",\"message\":\"Version request sent\"}");
            } catch (IOException e) {
                sendJson(exchange, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            }
        }
    }

    private final class SetRoleHandler extends JsonHandler {
        @Override
        protected void handleJson(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            Map<String, String> q = queryParams(exchange.getRequestURI());
            String connectionId = q.get("connectionId");
            String roleStr = q.get("role");
            String enabledStr = q.get("enabled");
            
            if (connectionId == null || roleStr == null || enabledStr == null) {
                sendJson(exchange, 400, "{\"error\":\"Missing connectionId, role, or enabled parameter\"}");
                return;
            }
            
            CommandStationConnection conn = service.getConnection(connectionId);
            if (conn == null) {
                sendJson(exchange, 404, "{\"error\":\"Connection not found\"}");
                return;
            }
            
            try {
                cc.panelsd.connect.core.ControllerRole role;
                if ("throttles".equalsIgnoreCase(roleStr)) {
                    role = cc.panelsd.connect.core.ControllerRole.THROTTLES;
                } else if ("accessories".equalsIgnoreCase(roleStr)) {
                    role = cc.panelsd.connect.core.ControllerRole.ACCESSORIES;
                } else {
                    sendJson(exchange, 400, "{\"error\":\"Invalid role. Must be 'throttles' or 'accessories'\"}");
                    return;
                }
                
                boolean enabled = "true".equalsIgnoreCase(enabledStr);
                
                // Get previous state before role change
                java.util.Map<String, com.google.gson.JsonObject> previousState = null;
                if (statusHandler != null) {
                    previousState = statusHandler.getCurrentState();
                }
                
                ((DccIoServiceImpl) service).setControllerRole(connectionId, role, enabled);
                
                // Trigger status patch broadcast for role change
                if (statusHandler != null && previousState != null) {
                    statusHandler.broadcastStatusPatch(previousState);
                }
                
                sendJson(exchange, 200, "{\"status\":\"ok\"}");
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            }
        }
    }


    private final class DiscoverHandler extends JsonHandler {
        @Override
        protected void handleJson(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            
            java.util.List<cc.panelsd.connect.core.DeviceDiscoveryService.DetectedDevice> detected = discoveryService.discoverDevices();
            
            // Format as JSON
            StringJoiner joiner = new StringJoiner(",", "{\"devices\":[", "]}");
            for (cc.panelsd.connect.core.DeviceDiscoveryService.DetectedDevice device : detected) {
                StringJoiner deviceJson = new StringJoiner(",");
                deviceJson.add("\"port\":\"" + escape(device.port) + "\"");
                deviceJson.add("\"systemType\":\"" + escape(device.systemType) + "\"");
                deviceJson.add("\"description\":\"" + escape(device.description) + "\"");
                deviceJson.add("\"vendorId\":\"" + escape(device.vendorId) + "\"");
                deviceJson.add("\"productId\":\"" + escape(device.productId) + "\"");
                deviceJson.add("\"name\":\"" + escape(device.name) + "\"");
                if (!device.config.isEmpty()) {
                    StringJoiner configJson = new StringJoiner(",");
                    for (java.util.Map.Entry<String, String> entry : device.config.entrySet()) {
                        configJson.add("\"" + escape(entry.getKey()) + "\":\"" + escape(entry.getValue()) + "\"");
                    }
                    deviceJson.add("\"config\":{" + configJson.toString() + "}");
                }
                joiner.add("{" + deviceJson.toString() + "}");
            }
            sendJson(exchange, 200, joiner.toString());
        }
    }

    private final class CreateConnectionHandler extends JsonHandler {
        @Override
        protected void handleJson(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            Map<String, String> q = queryParams(exchange.getRequestURI());
            String id = q.get("id");
            String systemType = q.get("systemType");
            String userName = q.getOrDefault("userName", id);
            String systemPrefix = q.getOrDefault("systemPrefix", id);
            if (id == null || systemType == null) {
                sendJson(exchange, 400, "{\"error\":\"Missing id or systemType\"}");
                return;
            }

            SystemConfig.Builder builder = SystemConfig.builder(id, systemType)
                    .userName(userName)
                    .systemPrefix(systemPrefix);
            // pass through any remaining query params as options
            for (Map.Entry<String, String> e : q.entrySet()) {
                String key = e.getKey();
                if (!"id".equals(key) && !"systemType".equals(key)
                        && !"userName".equals(key) && !"systemPrefix".equals(key)) {
                    builder.option(key, e.getValue());
                }
            }
            SystemConfig config = builder.build();
            CommandStationConnection conn = service.createConnection(config);
            try {
                conn.connect();
            } catch (IOException e) {
                sendJson(exchange, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
                return;
            }
            String body = "{\"id\":\"" + escape(conn.getId()) + "\","
                    + "\"systemType\":\"" + escape(conn.getSystemType()) + "\","
                    + "\"connected\":" + conn.isConnected() + "}";
            sendJson(exchange, 201, body);
        }
    }

    private final class PortsHandler extends JsonHandler {
        @Override
        protected void handleJson(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            // Use JMRI's port enumeration - try JSerialComm first, fallback to PureJavaComm
            java.util.List<String> ports = new java.util.ArrayList<>();
            try {
                // Try JSerialComm first (modern, cross-platform)
                Class<?> jSerialCommClass = Class.forName("com.fazecast.jSerialComm.SerialPort");
                java.lang.reflect.Method getCommPorts = jSerialCommClass.getMethod("getCommPorts");
                Object[] commPorts = (Object[]) getCommPorts.invoke(null);
                for (Object port : commPorts) {
                    java.lang.reflect.Method getSystemPortName = port.getClass().getMethod("getSystemPortName");
                    String portName = (String) getSystemPortName.invoke(port);
                    if (isPortPresent(portName)) {
                        ports.add(portName);
                    }
                }
            } catch (Exception e) {
                // Fallback to PureJavaComm if JSerialComm not available
                try {
                    Class<?> commPortIdClass = Class.forName("purejavacomm.CommPortIdentifier");
                    java.lang.reflect.Method getPortIdentifiers = commPortIdClass.getMethod("getPortIdentifiers");
                    java.util.Enumeration<?> identifiers = (java.util.Enumeration<?>) getPortIdentifiers.invoke(null);
                    int PORT_SERIAL = commPortIdClass.getField("PORT_SERIAL").getInt(null);
                    while (identifiers.hasMoreElements()) {
                        Object id = identifiers.nextElement();
                        java.lang.reflect.Method getPortType = id.getClass().getMethod("getPortType");
                        int portType = (Integer) getPortType.invoke(id);
                        if (portType == PORT_SERIAL) {
                            java.lang.reflect.Method getName = id.getClass().getMethod("getName");
                            String portName = (String) getName.invoke(id);
                            if (isPortPresent(portName)) {
                                ports.add(portName);
                            }
                        }
                    }
                } catch (Exception e2) {
                    // If both fail, return empty list
                }
            }
            StringJoiner joiner = new StringJoiner(",", "{\"ports\":[", "]}");
            for (String port : ports) {
                joiner.add("\"" + escape(port) + "\"");
            }
            sendJson(exchange, 200, joiner.toString());
        }

        private boolean isPortPresent(String portName) {
            if (portName == null || portName.isEmpty()) {
                return false;
            }
            java.nio.file.Path devPath = java.nio.file.Paths.get("/dev", portName);
            if (java.nio.file.Files.exists(devPath)) {
                return true;
            }
            return portName.toUpperCase().startsWith("COM");
        }
    }

    private final class SystemsHandler extends JsonHandler {
        @Override
        protected void handleJson(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            // Return list of supported system types
            String json = "{\"systems\":[" +
                    "{\"id\":\"xnet-elite\",\"name\":\"Hornby Elite / XpressNet\",\"connectionTypes\":[\"serial\",\"usb\"]}," +
                    "{\"id\":\"dccpp-ethernet\",\"name\":\"DCC++ (Ethernet)\",\"connectionTypes\":[\"network\"]}," +
                    "{\"id\":\"nce-serial\",\"name\":\"NCE PowerCab (Serial)\",\"connectionTypes\":[\"serial\",\"usb\"]}," +
                    "{\"id\":\"nce-usb\",\"name\":\"NCE PowerCab (USB)\",\"connectionTypes\":[\"usb\"]}" +
                    "]}";
            sendJson(exchange, 200, json);
        }
    }

    private final class EventsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, 0);
                exchange.close();
                return;
            }
            
            // Set up SSE headers
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/event-stream");
            headers.set("Cache-Control", "no-cache");
            headers.set("Connection", "keep-alive");
            headers.set("Access-Control-Allow-Origin", "*");
            
            exchange.sendResponseHeaders(200, 0);
            
            OutputStream os = exchange.getResponseBody();
            final cc.panelsd.connect.core.events.DccEventListener[] listenerRef = new cc.panelsd.connect.core.events.DccEventListener[1];
            
            // Create a listener that writes events to the response stream
            cc.panelsd.connect.core.events.DccEventListener listener = event -> {
                try {
                    // Format as SSE
                    String json = eventToJson(event);
                    synchronized (os) {
                        os.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }
                } catch (IOException e) {
                    // Client disconnected or error - remove listener and close
                    if (listenerRef[0] != null) {
                        service.getEventBus().removeListener(listenerRef[0]);
                        listenerRef[0] = null;
                    }
                    try {
                        exchange.close();
                    } catch (Exception ignore) {}
                }
            };
            
            listenerRef[0] = listener;
            service.getEventBus().addListener(listener);
            
            // Send initial connection message
            try {
                synchronized (os) {
                    os.write(("data: {\"type\":\"connected\"}\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } catch (IOException e) {
                service.getEventBus().removeListener(listener);
                try {
                    exchange.close();
                } catch (Exception ignore) {}
                return;
            }
            
            // Keep connection alive - client will close it when navigating away
            // The listener will be removed when IOException occurs (client disconnect)
        }
        
        private String eventToJson(cc.panelsd.connect.core.events.DccEvent event) {
            StringBuilder json = new StringBuilder();
            json.append("{\"type\":\"").append(event.getType().name()).append("\"");
            json.append(",\"connectionId\":\"").append(escape(event.getConnectionId())).append("\"");
            if (!event.getPayload().isEmpty()) {
                json.append(",\"payload\":{");
                StringJoiner payload = new StringJoiner(",");
                for (Map.Entry<String, Object> entry : event.getPayload().entrySet()) {
                    Object value = entry.getValue();
                    String valueStr;
                    if (value instanceof String) {
                        valueStr = "\"" + escape(value.toString()) + "\"";
                    } else if (value instanceof Number || value instanceof Boolean) {
                        valueStr = value.toString();
                    } else {
                        valueStr = "\"" + escape(value.toString()) + "\"";
                    }
                    payload.add("\"" + escape(entry.getKey()) + "\":" + valueStr);
                }
                json.append(payload.toString());
                json.append("}");
            }
            json.append("}");
            return json.toString();
        }
        
        private String escape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
        }
    }

    private final class UpdateApiHandler extends JsonHandler {
        @Override
        protected void handleJson(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path == null) {
                path = "";
            }
            if (path.endsWith("/install")) {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }
                try (InputStream is = exchange.getRequestBody()) {
                    is.readAllBytes();
                }
                try {
                    runPanelsdccUpdaterInstall();
                    sendJsonRaw(exchange, 200, ConnectUpdater.getStatusJsonString());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sendJson(exchange, 500, "{\"error\":\"Interrupted\"}");
                }
                return;
            }
            if (path.endsWith("/check")) {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }
                try (InputStream is = exchange.getRequestBody()) {
                    is.readAllBytes();
                }
                try {
                    ConnectUpdater.performCheck();
                    sendJsonRaw(exchange, 200, ConnectUpdater.getStatusJsonString());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sendJson(exchange, 500, "{\"error\":\"Interrupted\"}");
                }
                return;
            }
            if (!"/api/update".equals(path) && !"/api/update/".equals(path)) {
                sendJson(exchange, 404, "{\"error\":\"Not found\"}");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            sendJsonRaw(exchange, 200, ConnectUpdater.getStatusJsonString());
        }
    }

    private final class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            // Remove leading /static
            String resourcePath = path.substring("/static".length());
            if (resourcePath.isEmpty() || resourcePath.equals("/")) {
                resourcePath = "/index.html";
            }
            // Map to resources/web/static
            String fullPath = "/web/static" + resourcePath;
            
            try (InputStream is = DccIoHttpServer.class.getResourceAsStream(fullPath)) {
                if (is == null) {
                    // 404 Not Found
                    Headers headers = exchange.getResponseHeaders();
                    headers.set("Content-Type", "text/html; charset=utf-8");
                    String html = "<!DOCTYPE html><html><head><title>404 Not Found</title></head><body><h1>404 Not Found</h1></body></html>";
                    byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(404, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                    return;
                }
                
                // Determine content type
                String contentType = "text/plain";
                if (resourcePath.endsWith(".css")) {
                    contentType = "text/css";
                } else if (resourcePath.endsWith(".js")) {
                    contentType = "application/javascript";
                } else if (resourcePath.endsWith(".html")) {
                    contentType = "text/html";
                } else if (resourcePath.endsWith(".png")) {
                    contentType = "image/png";
                } else if (resourcePath.endsWith(".jpg") || resourcePath.endsWith(".jpeg")) {
                    contentType = "image/jpeg";
                }
                
                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", contentType + "; charset=utf-8");
                byte[] bytes = is.readAllBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        }
    }

    private final class WebUIHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path) || "/index.html".equals(path)) {
                sendHtml(exchange, 200, getWebUIHtml());
            } else {
                sendHtml(exchange, 404, "<!DOCTYPE html><html><head><title>404 Not Found</title></head><body><h1>404 Not Found</h1></body></html>");
            }
        }

        private void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/html; charset=utf-8");
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private String getWebUIHtml() {
            // Load HTML template from resources
            try (InputStream is = DccIoHttpServer.class.getResourceAsStream("/web/index.html")) {
                if (is != null) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                // Fall back to error page
            }
            return "<!DOCTYPE html><html><head><title>Error</title></head><body><h1>Error loading template</h1></body></html>";
        }
    }
}