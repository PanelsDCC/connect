package cc.panelsd.connect.daemon;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import cc.panelsd.connect.core.CommandStationConnection;
import cc.panelsd.connect.core.SystemConfig;
import cc.panelsd.connect.core.impl.DccIoServiceImpl;
import cc.panelsd.connect.updater.ConnectUpdater;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
 *   <li>POST /api/update/install - queue install (requires sudo; see sudoers); returns 202</li>
 *   <li>POST /api/update/upload - accept a local .deb body, save, queue install-local; returns 202</li>
 *   <li>GET /api/wifi - wifi mode/status/hotspot credentials</li>
 *   <li>GET /api/wifi/scan - nearby SSIDs from NetworkManager</li>
 *   <li>POST /api/wifi/connect - join client network</li>
 *   <li>POST /api/wifi/mode - switch hotspot/client mode</li>
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
        server.createContext("/api/sensors", new SensorsApiHandler());
        // Software update API (single prefix; subpaths routed in handler — HttpServer matches /api/update*)
        server.createContext("/api/update", new UpdateApiHandler());
        server.createContext("/api/wifi", new WifiApiHandler());
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
     * Queues the packaged updater via {@code install --background} (transient systemd unit).
     * We do not use {@code systemd-run --wait} from the daemon: when {@code dpkg} restarts
     * this service, systemd SIGTERM's the waiter (exit 143) even if the install succeeds.
     * When the daemon runs as {@code dcc-io}, uses {@code sudo -n} (see sudoers).
     */
    private static void runPanelsdccUpdaterInstall() throws IOException, InterruptedException {
        runPanelsdccUpdaterMode("install");
    }

    /**
     * Queues {@code install-local --background} for a previously uploaded
     * {@link ConnectUpdater#LOCAL_DEB}.
     */
    private static void runPanelsdccUpdaterInstallLocal() throws IOException, InterruptedException {
        runPanelsdccUpdaterMode("install-local");
    }

    private static void runPanelsdccUpdaterMode(String mode) throws IOException, InterruptedException {
        String script = "/usr/local/bin/panelsdcc-connect-updater";
        ProcessBuilder pb;
        if ("root".equals(System.getProperty("user.name"))) {
            pb = new ProcessBuilder(script, mode, "--background");
        } else {
            pb = new ProcessBuilder("sudo", "-n", script, mode, "--background");
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException(mode + " failed (exit " + code + "): " + out.trim()
                    + ". Ensure /etc/sudoers.d/panelsdcc-connect-updater allows dcc-io to run "
                    + script + " " + mode + " --background without a password.");
        }
    }

    private static final long MAX_DEB_UPLOAD_BYTES = 100L * 1024 * 1024;
    private static final byte[] DEB_MAGIC = new byte[]{'!', '<', 'a', 'r', 'c', 'h', '>', '\n'};

    /**
     * Stream a raw {@code .deb} body to {@link ConnectUpdater#LOCAL_DEB}, validating size and
     * Debian archive magic.
     */
    private static void saveUploadedDeb(InputStream body, String filenameHint) throws IOException {
        if (filenameHint != null && !filenameHint.isBlank()) {
            String name = filenameHint.trim().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".deb")) {
                throw new IOException("Filename must end with .deb");
            }
        }
        Path dest = Path.of(ConnectUpdater.LOCAL_DEB);
        Files.createDirectories(dest.getParent());
        Path part = dest.resolveSibling(dest.getFileName().toString() + ".part");
        long written = 0;
        try (OutputStream out = Files.newOutputStream(part,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = body.read(buf)) >= 0) {
                if (n == 0) {
                    continue;
                }
                if (written + n > MAX_DEB_UPLOAD_BYTES) {
                    throw new IOException("Upload exceeds maximum size of 100MB");
                }
                out.write(buf, 0, n);
                written += n;
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // ignore
            }
            throw e;
        }
        if (written == 0) {
            Files.deleteIfExists(part);
            throw new IOException("Empty upload body");
        }
        if (written < DEB_MAGIC.length) {
            Files.deleteIfExists(part);
            throw new IOException("Not a Debian package (missing !<arch> magic)");
        }
        try (InputStream in = Files.newInputStream(part)) {
            byte[] head = in.readNBytes(DEB_MAGIC.length);
            for (int i = 0; i < DEB_MAGIC.length; i++) {
                if (head.length <= i || head[i] != DEB_MAGIC[i]) {
                    Files.deleteIfExists(part);
                    throw new IOException("Not a Debian package (missing !<arch> magic)");
                }
            }
        }
        try {
            Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Runs the packaged WiFi helper script with sudo when daemon user is not root.
     */
    private static String runPanelsdccWifiCommand(String... args) throws IOException, InterruptedException {
        String script = "/usr/local/bin/panelsdcc-connect-wifi";
        List<String> cmd = new ArrayList<>();
        if (!"root".equals(System.getProperty("user.name"))) {
            cmd.add("sudo");
            cmd.add("-n");
        }
        cmd.add(script);
        for (String arg : args) {
            cmd.add(arg);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("wifi command failed (exit " + code + "): " + out
                    + ". Ensure /etc/sudoers.d/panelsdcc-connect-updater includes a NOPASSWD rule for "
                    + script + " commands.");
        }
        if (out.isEmpty()) {
            return "{\"status\":\"ok\"}";
        }
        return out;
    }

    private abstract class JsonHandler implements HttpHandler {

        @Override
        public final void handle(HttpExchange exchange) throws IOException {
            try {
                handleJson(exchange);
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg == null || msg.isEmpty()) {
                    msg = e.toString();
                }
                sendJsonError(exchange, 500, msg);
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

        /** Error body as JSON; safe for arbitrary text (newlines, quotes) from subprocesses. */
        protected void sendJsonError(HttpExchange exchange, int status, String message) throws IOException {
            JsonObject o = new JsonObject();
            o.addProperty("error", message != null ? message : "Unknown error");
            sendJson(exchange, status, new Gson().toJson(o));
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
                sendJsonError(exchange, 500, e.getMessage() != null ? e.getMessage() : e.toString());
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
                sendJsonError(exchange, 400, e.getMessage() != null ? e.getMessage() : e.toString());
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
            
            // Use enriched discovery so CH340 candidates report post-probe / cached type
            java.util.List<cc.panelsd.connect.core.DeviceDiscoveryService.DetectedDevice> detected =
                    service.discoverDevicesEnriched();
            
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
                deviceJson.add("\"needsProbe\":" + device.needsProbe);
                if (device.byPath != null) {
                    deviceJson.add("\"byPath\":\"" + escape(device.byPath) + "\"");
                }
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
                sendJsonError(exchange, 500, e.getMessage() != null ? e.getMessage() : e.toString());
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
                    "{\"id\":\"dccpp\",\"name\":\"DCC++ / DCC-EX\",\"connectionTypes\":[\"network\",\"serial\",\"usb\"]}," +
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
                    JsonObject body = new JsonObject();
                    body.addProperty("accepted", true);
                    body.addProperty("message",
                            "Install started. Poll GET /api/update for progress; the service may restart.");
                    sendJsonRaw(exchange, 202, new Gson().toJson(body));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sendJson(exchange, 500, "{\"error\":\"Interrupted\"}");
                }
                return;
            }
            if (path.endsWith("/upload")) {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }
                Map<String, String> q = queryParams(exchange.getRequestURI());
                String filenameHint = q.get("filename");
                try (InputStream is = exchange.getRequestBody()) {
                    saveUploadedDeb(is, filenameHint);
                } catch (IOException e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "Upload failed";
                    int status = msg.startsWith("Upload exceeds") || msg.startsWith("Empty")
                            || msg.startsWith("Filename") || msg.startsWith("Not a Debian")
                            ? 400 : 500;
                    sendJsonError(exchange, status, msg);
                    return;
                }
                try {
                    runPanelsdccUpdaterInstallLocal();
                    JsonObject body = new JsonObject();
                    body.addProperty("accepted", true);
                    body.addProperty("message",
                            "Local package uploaded. Install started. Poll GET /api/update for progress; the service may restart.");
                    sendJsonRaw(exchange, 202, new Gson().toJson(body));
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

    private final class SensorsApiHandler extends JsonHandler {
        @Override
        protected void handleJson(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            Gson gson = new Gson();

            if ("/api/sensors".equals(path) || "/api/sensors/".equals(path)) {
                if (!"GET".equalsIgnoreCase(method)) {
                    sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }
                sendJsonRaw(exchange, 200, gson.toJson(service.listSensorsJson()));
                return;
            }

            if ("/api/sensors/events".equals(path) || "/api/sensors/events/".equals(path)) {
                if (!"GET".equalsIgnoreCase(method)) {
                    sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }
                JsonObject root = new JsonObject();
                root.add("events", service.sensorEventsJson());
                sendJsonRaw(exchange, 200, gson.toJson(root));
                return;
            }

            if ("/api/sensors/toolchain".equals(path) || "/api/sensors/toolchain/".equals(path)) {
                if (!"GET".equalsIgnoreCase(method)) {
                    sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }
                sendJsonRaw(exchange, 200, gson.toJson(service.getSensorFlasher().toolchainJson()));
                return;
            }

            if ("/api/sensors/flash/status".equals(path) || "/api/sensors/flash/status/".equals(path)) {
                if (!"GET".equalsIgnoreCase(method)) {
                    sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }
                sendJsonRaw(exchange, 200, gson.toJson(service.getSensorFlasher().statusJson()));
                return;
            }

            if ("/api/sensors/flash".equals(path) || "/api/sensors/flash/".equals(path)) {
                if (!"POST".equalsIgnoreCase(method)) {
                    sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonObject req = body.isEmpty() ? new JsonObject() : gson.fromJson(body, JsonObject.class);
                if (req == null || !req.has("port") || !req.has("prefix")) {
                    sendJson(exchange, 400, "{\"error\":\"port and prefix required\"}");
                    return;
                }
                String port = req.get("port").getAsString();
                String prefix = req.get("prefix").getAsString();
                String boardType = req.has("boardType") ? req.get("boardType").getAsString() : "nano-avr";
                boolean started = service.flashSensorBoard(port, prefix, boardType);
                if (!started) {
                    JsonObject err = new JsonObject();
                    err.addProperty("error", "Flash could not start (busy or invalid prefix)");
                    err.add("status", service.getSensorFlasher().statusJson());
                    sendJsonRaw(exchange, 409, gson.toJson(err));
                    return;
                }
                JsonObject ok = new JsonObject();
                ok.addProperty("ok", true);
                ok.add("status", service.getSensorFlasher().statusJson());
                sendJsonRaw(exchange, 202, gson.toJson(ok));
                return;
            }

            if ("/api/sensors/shim".equals(path) || "/api/sensors/shim/".equals(path)) {
                if (!"POST".equalsIgnoreCase(method)) {
                    sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonObject req = body.isEmpty() ? new JsonObject() : gson.fromJson(body, JsonObject.class);
                String prefix = req != null && req.has("prefix") ? req.get("prefix").getAsString() : null;
                String boardType = req != null && req.has("boardType") ? req.get("boardType").getAsString() : "nano-avr";
                try {
                    sendJsonRaw(exchange, 200, gson.toJson(service.createShimBoard(prefix, boardType)));
                } catch (IllegalArgumentException e) {
                    sendJsonError(exchange, 400, e.getMessage());
                }
                return;
            }

            if ("/api/sensors/profiles".equals(path) || "/api/sensors/profiles/".equals(path)) {
                if (!"GET".equalsIgnoreCase(method)) {
                    sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }
                JsonObject root = new JsonObject();
                root.add("profiles", service.listSensorsJson().getAsJsonArray("boardProfiles"));
                root.add("outputTypes", service.listSensorsJson().getAsJsonArray("outputTypes"));
                sendJsonRaw(exchange, 200, gson.toJson(root));
                return;
            }

            if ("/api/sensors/bluetooth".equals(path) || "/api/sensors/bluetooth/".equals(path)) {
                if (!"GET".equalsIgnoreCase(method)) {
                    sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }
                sendJsonRaw(exchange, 200, gson.toJson(service.bluetoothStatus()));
                return;
            }

            if ("/api/sensors/bluetooth/scan".equals(path) || "/api/sensors/bluetooth/scan/".equals(path)) {
                if (!"POST".equalsIgnoreCase(method) && !"GET".equalsIgnoreCase(method)) {
                    sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }
                JsonObject root = new JsonObject();
                root.add("devices", service.bluetoothScan(8));
                sendJsonRaw(exchange, 200, gson.toJson(root));
                return;
            }

            if ("/api/sensors/bluetooth/pair".equals(path) || "/api/sensors/bluetooth/pair/".equals(path)) {
                if (!"POST".equalsIgnoreCase(method)) {
                    sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonObject req = body.isEmpty() ? new JsonObject() : gson.fromJson(body, JsonObject.class);
                if (req == null || !req.has("address")) {
                    sendJson(exchange, 400, "{\"error\":\"address required\"}");
                    return;
                }
                String address = req.get("address").getAsString();
                String prefix = req.has("prefix") ? req.get("prefix").getAsString() : "A";
                sendJsonRaw(exchange, 200, gson.toJson(service.bluetoothPair(address, prefix)));
                return;
            }

            if ("/api/sensors/probe".equals(path) || "/api/sensors/probe/".equals(path)) {
                if (!"POST".equalsIgnoreCase(method)) {
                    sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonObject req = body.isEmpty() ? new JsonObject() : gson.fromJson(body, JsonObject.class);
                if (req == null || !req.has("port")) {
                    sendJson(exchange, 400, "{\"error\":\"port required\"}");
                    return;
                }
                try {
                    JsonObject result = service.probeUsbSerial(req.get("port").getAsString());
                    int code = result.has("ok") && result.get("ok").getAsBoolean() ? 200 : 422;
                    sendJsonRaw(exchange, code, gson.toJson(result));
                } catch (IllegalArgumentException e) {
                    sendJsonError(exchange, 400, e.getMessage());
                } catch (Exception e) {
                    sendJsonError(exchange, 500, e.getMessage() != null ? e.getMessage() : "probe failed");
                }
                return;
            }

            // /api/sensors/{id}/release or /resume
            if (path.startsWith("/api/sensors/")) {
                String rest = path.substring("/api/sensors/".length());
                if (rest.endsWith("/")) {
                    rest = rest.substring(0, rest.length() - 1);
                }
                int slash = rest.lastIndexOf('/');
                if (slash > 0) {
                    String id = rest.substring(0, slash);
                    String action = rest.substring(slash + 1);
                    if ("release".equals(action)) {
                        if (!"POST".equalsIgnoreCase(method)) {
                            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                            return;
                        }
                        try {
                            service.releaseSensorBoard(id);
                            sendJson(exchange, 200, "{\"ok\":true}");
                        } catch (IllegalArgumentException e) {
                            sendJsonError(exchange, 404, e.getMessage());
                        }
                        return;
                    }
                    if ("resume".equals(action)) {
                        if (!"POST".equalsIgnoreCase(method)) {
                            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                            return;
                        }
                        try {
                            service.resumeSensorBoard(id);
                            sendJson(exchange, 200, "{\"ok\":true}");
                        } catch (IllegalArgumentException e) {
                            sendJsonError(exchange, 404, e.getMessage());
                        } catch (IOException e) {
                            sendJsonError(exchange, 500, e.getMessage());
                        }
                        return;
                    }
                    if ("simulate".equals(action)) {
                        if (!"POST".equalsIgnoreCase(method)) {
                            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                            return;
                        }
                        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        JsonObject req = body.isEmpty() ? new JsonObject() : gson.fromJson(body, JsonObject.class);
                        String hardwareId = req != null && req.has("hardwareId") ? req.get("hardwareId").getAsString() : null;
                        String label = req != null && req.has("label") ? req.get("label").getAsString() : null;
                        int state = req != null && req.has("state") && req.get("state").getAsInt() == 1 ? 1 : 0;
                        try {
                            sendJsonRaw(exchange, 200, gson.toJson(service.simulateShimChannel(id, hardwareId, label, state)));
                        } catch (IllegalArgumentException e) {
                            sendJsonError(exchange, 404, e.getMessage());
                        }
                        return;
                    }
                    if ("config".equals(action)) {
                        if (!"POST".equalsIgnoreCase(method)) {
                            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                            return;
                        }
                        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        JsonObject req = body.isEmpty() ? new JsonObject() : gson.fromJson(body, JsonObject.class);
                        if (req == null) {
                            sendJson(exchange, 400, "{\"error\":\"config body required\"}");
                            return;
                        }
                        try {
                            sendJsonRaw(exchange, 200, gson.toJson(service.pushNodeConfig(id, req)));
                        } catch (IllegalArgumentException e) {
                            sendJsonError(exchange, 404, e.getMessage());
                        }
                        return;
                    }
                    if ("test".equals(action)) {
                        if (!"POST".equalsIgnoreCase(method)) {
                            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                            return;
                        }
                        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        JsonObject req = body.isEmpty() ? new JsonObject() : gson.fromJson(body, JsonObject.class);
                        String command = req != null && req.has("command") ? req.get("command").getAsString() : "";
                        try {
                            sendJsonRaw(exchange, 200, gson.toJson(service.sendNodeTest(id, command)));
                        } catch (IllegalArgumentException e) {
                            sendJsonError(exchange, 404, e.getMessage());
                        }
                        return;
                    }
                    if ("remove".equals(action)) {
                        if (!"POST".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method)) {
                            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                            return;
                        }
                        boolean ok = service.removeShimBoard(id);
                        if (!ok) {
                            sendJsonError(exchange, 404, "Shim board not found: " + id);
                            return;
                        }
                        sendJson(exchange, 200, "{\"ok\":true}");
                        return;
                    }
                }
            }

            sendJson(exchange, 404, "{\"error\":\"Not found\"}");
        }
    }

    private final class WifiApiHandler extends JsonHandler {
        @Override
        protected void handleJson(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if (path == null) {
                path = "";
            }

            if (path.endsWith("/scan")) {
                if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
                    sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }
                if ("POST".equalsIgnoreCase(method)) {
                    try (InputStream is = exchange.getRequestBody()) {
                        is.readAllBytes();
                    }
                }
                try {
                    sendJsonRaw(exchange, 200, runPanelsdccWifiCommand("scan"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sendJson(exchange, 500, "{\"error\":\"Interrupted\"}");
                }
                return;
            }

            if (path.endsWith("/connect")) {
                if (!"POST".equalsIgnoreCase(method)) {
                    sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }
                try (InputStream is = exchange.getRequestBody()) {
                    is.readAllBytes();
                }
                Map<String, String> q = queryParams(exchange.getRequestURI());
                String ssid = q.get("ssid");
                String password = q.getOrDefault("password", "");
                if (ssid == null || ssid.isEmpty()) {
                    sendJson(exchange, 400, "{\"error\":\"Missing ssid\"}");
                    return;
                }
                try {
                    sendJsonRaw(exchange, 200, runPanelsdccWifiCommand("connect", ssid, password));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sendJson(exchange, 500, "{\"error\":\"Interrupted\"}");
                }
                return;
            }

            if (path.endsWith("/mode")) {
                if (!"POST".equalsIgnoreCase(method)) {
                    sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }
                try (InputStream is = exchange.getRequestBody()) {
                    is.readAllBytes();
                }
                Map<String, String> q = queryParams(exchange.getRequestURI());
                String mode = q.get("mode");
                if (mode == null || mode.isEmpty()) {
                    sendJson(exchange, 400, "{\"error\":\"Missing mode\"}");
                    return;
                }
                try {
                    sendJsonRaw(exchange, 200, runPanelsdccWifiCommand("set-mode", mode));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sendJson(exchange, 500, "{\"error\":\"Interrupted\"}");
                }
                return;
            }

            if (path.endsWith("/fallback")) {
                if (!"POST".equalsIgnoreCase(method)) {
                    sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }
                try (InputStream is = exchange.getRequestBody()) {
                    is.readAllBytes();
                }
                try {
                    sendJsonRaw(exchange, 200, runPanelsdccWifiCommand("apply-fallback"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sendJson(exchange, 500, "{\"error\":\"Interrupted\"}");
                }
                return;
            }

            if (!"/api/wifi".equals(path) && !"/api/wifi/".equals(path)) {
                sendJson(exchange, 404, "{\"error\":\"Not found\"}");
                return;
            }
            if (!"GET".equalsIgnoreCase(method)) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                sendJsonRaw(exchange, 200, runPanelsdccWifiCommand("status"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendJson(exchange, 500, "{\"error\":\"Interrupted\"}");
            }
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