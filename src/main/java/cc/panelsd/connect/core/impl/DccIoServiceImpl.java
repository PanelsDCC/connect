package cc.panelsd.connect.core.impl;

import cc.panelsd.connect.core.CommandStationConnection;
import cc.panelsd.connect.core.ControllerRole;
import cc.panelsd.connect.core.DccIoService;
import cc.panelsd.connect.core.DeviceDiscoveryService;
import cc.panelsd.connect.core.SystemConfig;
import cc.panelsd.connect.core.ThrottleSession;
import cc.panelsd.connect.core.UsbProbeCache;
import cc.panelsd.connect.core.UsbSerialProbe;
import cc.panelsd.connect.core.UsbSerialProbe.DeviceType;
import cc.panelsd.connect.core.UsbSerialProbe.ProbeResult;
import cc.panelsd.connect.core.events.DccEventBus;
import cc.panelsd.connect.core.impl.dccpp.DccppConnection;
import cc.panelsd.connect.core.impl.nce.NceSerialConnection;
import cc.panelsd.connect.core.impl.nce.NceUsbConnection;
import cc.panelsd.connect.core.impl.sensor.SensorBoardFlasher;
import cc.panelsd.connect.core.impl.sensor.SensorBoardSession;
import cc.panelsd.connect.core.impl.sensor.SensorBoardSupport;
import cc.panelsd.connect.core.impl.xnet.elite.XNetEliteConnection;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * In-memory implementation of {@link DccIoService} that manages JMRI-backed
 * connections and shares a single {@link DccEventBus} for outbound events.
 */
public final class DccIoServiceImpl implements DccIoService {

    private static final long UNKNOWN_PROBE_COOLDOWN_MS = 12_000L;
    private static final long BUSY_PROBE_COOLDOWN_MS = 4_000L;
    private static final long CACHE_MISS_SETTLE_MS = 750L;

    private final Map<String, CommandStationConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, ThrottleSession> throttles = new ConcurrentHashMap<>();
    private final Map<String, SensorBoardSession> sensorSessions = new ConcurrentHashMap<>();
    /** Ports held released for flashing — skip auto-connect until resume. */
    private final Map<String, ReleasedSensorPort> releasedSensorPorts = new ConcurrentHashMap<>();
    private final List<JsonObject> sensorEventRing = Collections.synchronizedList(new ArrayList<>());
    /** Last known state per hardwareId (0/1). */
    private final Map<String, Integer> sensorChannelStates = new ConcurrentHashMap<>();
    private static final int SENSOR_EVENT_RING_MAX = 50;

    private final DccEventBus eventBus = new DccEventBus();
    private final DeviceDiscoveryService discoveryService = new DeviceDiscoveryService();
    private final UsbSerialProbe usbProbe = new UsbSerialProbe();
    private final UsbProbeCache probeCache = new UsbProbeCache();
    private final SensorBoardFlasher sensorFlasher = new SensorBoardFlasher();

    /** Ports that recently classified as unknown — avoid hammering/resetting devices. */
    private final Map<String, Long> probeCooldownUntil = new ConcurrentHashMap<>();

    private volatile Consumer<JsonObject> sensorHardwarePublisher;

    public static final class ReleasedSensorPort {
        public final String port;
        public final String prefix;
        public final String byPath;
        public final String id;

        public ReleasedSensorPort(String id, String port, String prefix, String byPath) {
            this.id = id;
            this.port = port;
            this.prefix = prefix;
            this.byPath = byPath;
        }
    }

    // Track which controllers are assigned to which roles
    private volatile String throttleControllerId = null;
    private volatile String accessoryControllerId = null;

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
     * Publisher for raw sensor edges ({@code sensorHardware} patches). Set after the
     * gateway WebSocket is up (typically {@code JsonBroadcaster::broadcast}).
     */
    public void setSensorHardwarePublisher(Consumer<JsonObject> publisher) {
        this.sensorHardwarePublisher = msg -> {
            recordSensorHardwareEvent(msg);
            if (publisher != null) {
                publisher.accept(msg);
            }
        };
    }

    private void recordSensorHardwareEvent(JsonObject msg) {
        if (msg == null || !msg.has("data") || !msg.get("data").isJsonObject()) {
            return;
        }
        JsonObject data = msg.getAsJsonObject("data");
        JsonObject row = new JsonObject();
        if (data.has("id") && data.has("state")) {
            String id = data.get("id").getAsString();
            int state = data.get("state").getAsInt() == 1 ? 1 : 0;
            sensorChannelStates.put(id, state);
            row.addProperty("id", id);
            row.addProperty("state", state);
        } else {
            return;
        }
        row.addProperty("at", Instant.now().toString());
        synchronized (sensorEventRing) {
            sensorEventRing.add(row);
            while (sensorEventRing.size() > SENSOR_EVENT_RING_MAX) {
                sensorEventRing.remove(0);
            }
        }
    }

    private JsonArray channelsJsonForPrefix(String prefix) {
        JsonArray channels = new JsonArray();
        for (SensorBoardSupport.ChannelDef def : SensorBoardSupport.channelDefs(prefix)) {
            JsonObject ch = new JsonObject();
            ch.addProperty("id", def.id);
            ch.addProperty("label", def.label);
            ch.addProperty("kind", def.kind);
            ch.addProperty("state", sensorChannelStates.getOrDefault(def.id, 0));
            channels.add(ch);
        }
        return channels;
    }

    /**
     * Start continuous device monitoring and auto-connect.
     * First pass is deferred so USB tty nodes settle after host reboot.
     */
    public void startDeviceMonitoring() {
        deviceMonitor.scheduleWithFixedDelay(() -> {
            try {
                autoConnectDevices();
            } catch (Exception e) {
                System.err.println("Error in device monitoring: " + e.getMessage());
            }
        }, 3, 5, TimeUnit.SECONDS);
    }

    /**
     * Auto-discover and connect to detected controllers / sensor boards.
     */
    private void autoConnectDevices() {
        checkForDisconnectedPorts();

        List<DeviceDiscoveryService.DetectedDevice> devices = discoveryService.discoverDevices();
        for (DeviceDiscoveryService.DetectedDevice device : devices) {
            if (connectedPorts.contains(device.port)) {
                continue;
            }
            // Held for flashing / manual release — do not auto-bind
            if (releasedSensorPorts.containsKey(SensorBoardSupport.barePort(device.port))
                    || releasedSensorPorts.containsKey(device.port)) {
                continue;
            }

            Long cooldown = probeCooldownUntil.get(device.port);
            if (cooldown != null && System.currentTimeMillis() < cooldown) {
                continue;
            }

            if (device.needsProbe
                    || DeviceDiscoveryService.SYSTEM_TYPE_USB_SERIAL_CANDIDATE.equals(device.systemType)) {
                if (!classifyCandidate(device)) {
                    continue;
                }
            }

            if (DeviceDiscoveryService.SYSTEM_TYPE_SENSOR_BOARD.equals(device.systemType)) {
                startSensorBoard(device);
                continue;
            }

            // Skip unsupported / unknown types
            if (!isSupportedCommandStation(device.systemType)) {
                continue;
            }

            String connectionId = device.systemType.replace("-", "") + "_"
                    + device.port.replace("/", "_").replace("\\", "_");

            if (connections.containsKey(connectionId)) {
                connectedPorts.add(device.port);
                portToConnectionId.put(device.port, connectionId);
                continue;
            }

            try {
                SystemConfig.Builder builder = SystemConfig.builder(connectionId, device.systemType)
                        .userName(device.name)
                        .systemPrefix(connectionId);

                builder.option("portName", device.port);
                for (Map.Entry<String, String> entry : device.config.entrySet()) {
                    builder.option(entry.getKey(), entry.getValue());
                }

                SystemConfig config = builder.build();
                CommandStationConnection conn = createConnection(config);
                conn.connect();
                connectedPorts.add(device.port);
                portToConnectionId.put(device.port, connectionId);
                System.out.println("Auto-connected to " + device.name + " on " + device.port);
                assignControllerRoles(connectionId);
            } catch (Exception e) {
                System.err.println("Failed to auto-connect to " + device.name + " on " + device.port
                        + ": " + e.getMessage());
                CommandStationConnection failed = connections.remove(connectionId);
                if (failed != null) {
                    try {
                        failed.close();
                    } catch (Exception ignore) {
                    }
                }
                connectedPorts.remove(device.port);
                portToConnectionId.remove(device.port);
            }
        }
    }

    /**
     * Probe (or use by-path cache) to classify an ambiguous USB-serial candidate.
     * Updates {@code device} in place. Returns false if classification failed / unknown.
     */
    private boolean classifyCandidate(DeviceDiscoveryService.DetectedDevice device) {
        ProbeResult result = null;
        boolean cacheMiss = false;

        // Prefer cached type for this physical USB path
        if (device.byPath != null) {
            UsbProbeCache.Entry cached = probeCache.get(device.byPath);
            if (cached != null) {
                if (DeviceDiscoveryService.SYSTEM_TYPE_SENSOR_BOARD.equals(cached.type)) {
                    result = usbProbe.verifySensorBoard(device.port);
                    if (result.type != DeviceType.SENSOR_BOARD) {
                        probeCache.remove(device.byPath);
                        cacheMiss = true;
                        result = null;
                    }
                } else if (DeviceDiscoveryService.SYSTEM_TYPE_DCCPP.equals(cached.type)) {
                    result = usbProbe.verifyDccpp(device.port);
                    if (result.type != DeviceType.DCCPP) {
                        probeCache.remove(device.byPath);
                        cacheMiss = true;
                        result = null;
                    }
                }
            }
        }

        if (result == null) {
            if (cacheMiss) {
                // Failed verify already toggled DTR — let the Nano finish booting.
                sleepQuietly(CACHE_MISS_SETTLE_MS);
            }
            result = usbProbe.classify(device.port);
        }

        if (result.type == DeviceType.UNKNOWN) {
            long cooldown = result.portBusy ? BUSY_PROBE_COOLDOWN_MS : UNKNOWN_PROBE_COOLDOWN_MS;
            probeCooldownUntil.put(device.port, System.currentTimeMillis() + cooldown);
            System.out.println("USB serial probe: unknown device on " + device.port
                    + (result.portBusy ? " (port busy)" : "")
                    + " — leaving alone for " + (cooldown / 1000) + "s");
            return false;
        }

        probeCooldownUntil.remove(device.port);
        applyProbeResult(device, result);

        if (device.byPath != null) {
            probeCache.put(device.byPath, device.systemType,
                    result.type == DeviceType.SENSOR_BOARD ? result.prefix : null);
        }

        System.out.println("USB serial probe: " + device.port + " → " + device.systemType
                + (result.prefix != null ? " prefix=" + result.prefix : ""));
        return true;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void applyProbeResult(DeviceDiscoveryService.DetectedDevice device, ProbeResult result) {
        if (result.type == DeviceType.SENSOR_BOARD) {
            device.systemType = DeviceDiscoveryService.SYSTEM_TYPE_SENSOR_BOARD;
            device.name = "PanelsDCC Sensor Board";
            device.needsProbe = false;
            device.config.put("baudRate", String.valueOf(UsbSerialProbe.SENSOR_BAUD));
            if (result.prefix != null) {
                device.config.put("boardPrefix", result.prefix);
            }
        } else if (result.type == DeviceType.DCCPP) {
            device.systemType = DeviceDiscoveryService.SYSTEM_TYPE_DCCPP;
            device.name = "DCC++ (CH340)";
            device.needsProbe = false;
            device.config.put("baudRate", String.valueOf(UsbSerialProbe.DCCPP_BAUD));
        }
    }

    private void startSensorBoard(DeviceDiscoveryService.DetectedDevice device) {
        String sessionId = "sensor_" + device.port.replace("/", "_").replace("\\", "_");
        if (sensorSessions.containsKey(sessionId)) {
            connectedPorts.add(device.port);
            portToConnectionId.put(device.port, sessionId);
            return;
        }
        String prefix = device.config.get("boardPrefix");
        SensorBoardSession session = new SensorBoardSession(
                device.port,
                prefix,
                device.byPath,
                () -> sensorHardwarePublisher);
        try {
            session.start();
            sensorSessions.put(sessionId, session);
            connectedPorts.add(device.port);
            portToConnectionId.put(device.port, sessionId);
        } catch (Exception e) {
            System.err.println("Failed to start sensor board on " + device.port + ": " + e.getMessage());
            session.close();
        }
    }

    private static boolean isSupportedCommandStation(String systemType) {
        if (systemType == null) {
            return false;
        }
        switch (systemType) {
            case "xnet-elite":
            case "dccpp":
            case "dccpp-ethernet":
            case "nce-serial":
            case "nce-usb":
                return true;
            default:
                return false;
        }
    }

    /**
     * Enrich discovery results with live classifications (probe cache + open sessions)
     * so HTTP discover does not claim CH340 as DCC++ without a probe.
     */
    public List<DeviceDiscoveryService.DetectedDevice> discoverDevicesEnriched() {
        List<DeviceDiscoveryService.DetectedDevice> devices = discoveryService.discoverDevices();
        for (DeviceDiscoveryService.DetectedDevice device : devices) {
            // Live sensor session
            for (SensorBoardSession session : sensorSessions.values()) {
                if (device.port.equals(session.getPortName())) {
                    device.systemType = DeviceDiscoveryService.SYSTEM_TYPE_SENSOR_BOARD;
                    device.name = "PanelsDCC Sensor Board";
                    device.needsProbe = false;
                    if (session.getPrefix() != null) {
                        device.config.put("boardPrefix", session.getPrefix());
                    }
                    device.config.put("baudRate", String.valueOf(UsbSerialProbe.SENSOR_BAUD));
                    break;
                }
            }
            // Live CS connection on this port
            String connId = portToConnectionId.get(device.port);
            if (connId != null && connections.containsKey(connId)) {
                CommandStationConnection conn = connections.get(connId);
                if (conn != null) {
                    device.systemType = conn.getSystemType();
                    device.needsProbe = false;
                    if (DeviceDiscoveryService.SYSTEM_TYPE_DCCPP.equals(conn.getSystemType())) {
                        device.name = "DCC++ (CH340)";
                    }
                }
                continue;
            }
            // Cache hint for candidates not yet connected — keep needsProbe so UI
            // does not pretend the board is live until a session exists.
            if (device.needsProbe && device.byPath != null) {
                UsbProbeCache.Entry cached = probeCache.get(device.byPath);
                if (cached != null) {
                    if (DeviceDiscoveryService.SYSTEM_TYPE_SENSOR_BOARD.equals(cached.type)) {
                        device.name = "PanelsDCC Sensor Board (cached)";
                        if (cached.prefix != null) {
                            device.config.put("boardPrefix", cached.prefix);
                        }
                        device.config.put("baudRate", String.valueOf(UsbSerialProbe.SENSOR_BAUD));
                        device.config.put("cachedType", cached.type);
                    } else if (DeviceDiscoveryService.SYSTEM_TYPE_DCCPP.equals(cached.type)) {
                        device.name = "DCC++ CH340 (cached)";
                        device.config.put("baudRate", String.valueOf(UsbSerialProbe.DCCPP_BAUD));
                        device.config.put("cachedType", cached.type);
                    }
                }
            }
        }
        return devices;
    }

    public Collection<SensorBoardSession> getSensorSessions() {
        return Collections.unmodifiableCollection(sensorSessions.values());
    }

    public SensorBoardFlasher getSensorFlasher() {
        return sensorFlasher;
    }

    public JsonObject listSensorsJson() {
        JsonArray boards = new JsonArray();
        Set<String> seenPorts = new HashSet<>();

        for (Map.Entry<String, SensorBoardSession> e : sensorSessions.entrySet()) {
            SensorBoardSession s = e.getValue();
            JsonObject row = new JsonObject();
            row.addProperty("id", e.getKey());
            row.addProperty("transport", SensorBoardSupport.TRANSPORT_USB);
            row.addProperty("endpoint", s.getPortName());
            if (s.getPrefix() != null) {
                row.addProperty("prefix", s.getPrefix());
            }
            if (s.getByPath() != null) {
                row.addProperty("byPath", s.getByPath());
            }
            row.addProperty("status", SensorBoardSupport.STATUS_LISTENING);
            if (s.getPrefix() != null) {
                row.add("channels", channelsJsonForPrefix(s.getPrefix()));
            }
            boards.add(row);
            seenPorts.add(SensorBoardSupport.barePort(s.getPortName()));
        }

        for (ReleasedSensorPort r : releasedSensorPorts.values()) {
            String bare = SensorBoardSupport.barePort(r.port);
            if (seenPorts.contains(bare)) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("id", r.id);
            row.addProperty("transport", SensorBoardSupport.TRANSPORT_USB);
            row.addProperty("endpoint", r.port);
            if (r.prefix != null) {
                row.addProperty("prefix", r.prefix);
            }
            if (r.byPath != null) {
                row.addProperty("byPath", r.byPath);
            }
            String flashPort = null;
            JsonObject flashStatus = sensorFlasher.statusJson();
            if (flashStatus.has("port")) {
                flashPort = flashStatus.get("port").getAsString();
            }
            String status = sensorFlasher.isBusy() && bare.equals(SensorBoardSupport.barePort(flashPort))
                    ? SensorBoardSupport.STATUS_FLASHING
                    : SensorBoardSupport.STATUS_RELEASED;
            row.addProperty("status", status);
            if (r.prefix != null) {
                row.add("channels", channelsJsonForPrefix(r.prefix));
            }
            boards.add(row);
        }

        JsonArray candidates = new JsonArray();
        for (DeviceDiscoveryService.DetectedDevice device : discoveryService.discoverDevices()) {
            String bare = SensorBoardSupport.barePort(device.port);
            if (seenPorts.contains(bare) || releasedSensorPorts.containsKey(bare)
                    || releasedSensorPorts.containsKey(device.port)) {
                continue;
            }
            if (connectedPorts.contains(device.port) || connectedPorts.contains(bare)) {
                continue;
            }
            boolean needsProbe = device.needsProbe
                    || DeviceDiscoveryService.SYSTEM_TYPE_USB_SERIAL_CANDIDATE.equals(device.systemType);
            if (!needsProbe) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("port", device.port);
            row.addProperty("endpoint", device.port);
            row.addProperty("status", "needs-probe");
            row.addProperty("name", device.name != null ? device.name : "USB serial candidate");
            if (device.byPath != null) {
                row.addProperty("byPath", device.byPath);
            }
            if (device.vendorId != null) {
                row.addProperty("vendorId", device.vendorId);
            }
            if (device.productId != null) {
                row.addProperty("productId", device.productId);
            }
            Long until = probeCooldownUntil.get(device.port);
            if (until != null && until > System.currentTimeMillis()) {
                row.addProperty("cooldownMs", until - System.currentTimeMillis());
            }
            candidates.add(row);
        }

        JsonObject root = new JsonObject();
        root.add("boards", boards);
        root.add("candidates", candidates);
        JsonArray transports = new JsonArray();
        JsonObject usb = new JsonObject();
        usb.addProperty("id", SensorBoardSupport.TRANSPORT_USB);
        usb.addProperty("available", true);
        usb.addProperty("label", "USB (Nano / CH340)");
        transports.add(usb);
        JsonObject wifi = new JsonObject();
        wifi.addProperty("id", SensorBoardSupport.TRANSPORT_WIFI);
        wifi.addProperty("available", false);
        wifi.addProperty("label", "Wi‑Fi (ESP32)");
        wifi.addProperty("note", "Coming soon — Connect will manage Wi‑Fi join later");
        transports.add(wifi);
        JsonObject ble = new JsonObject();
        ble.addProperty("id", SensorBoardSupport.TRANSPORT_BLE);
        ble.addProperty("available", false);
        ble.addProperty("label", "Bluetooth");
        ble.addProperty("note", "Coming soon — Connect will manage pairing later");
        transports.add(ble);
        root.add("transports", transports);
        return root;
    }

    public JsonArray sensorEventsJson() {
        JsonArray arr = new JsonArray();
        synchronized (sensorEventRing) {
            for (JsonObject row : sensorEventRing) {
                arr.add(row.deepCopy());
            }
        }
        return arr;
    }

    /**
     * Manually probe a USB-serial candidate (clears cooldown). Starts a sensor
     * session when classification succeeds as a sensor board.
     */
    public JsonObject probeUsbSerial(String portName) {
        if (portName == null || portName.trim().isEmpty()) {
            throw new IllegalArgumentException("port required");
        }
        String bare = SensorBoardSupport.barePort(portName.trim());
        String path = SensorBoardSupport.devicePath(bare);

        probeCooldownUntil.remove(bare);
        probeCooldownUntil.remove(path);
        probeCooldownUntil.remove(portName.trim());

        DeviceDiscoveryService.DetectedDevice match = null;
        for (DeviceDiscoveryService.DetectedDevice device : discoveryService.discoverDevices()) {
            String dBare = SensorBoardSupport.barePort(device.port);
            if (bare.equals(dBare) || path.equals(device.port) || portName.trim().equals(device.port)) {
                match = device;
                break;
            }
        }
        if (match == null) {
            match = new DeviceDiscoveryService.DetectedDevice();
            match.port = bare;
            match.systemType = DeviceDiscoveryService.SYSTEM_TYPE_USB_SERIAL_CANDIDATE;
            match.needsProbe = true;
            match.name = "USB serial candidate";
        }

        // Force a fresh classify (ignore stale cache verify churn)
        if (match.byPath != null) {
            probeCache.remove(match.byPath);
        }

        JsonObject out = new JsonObject();
        out.addProperty("port", match.port);

        for (SensorBoardSession session : sensorSessions.values()) {
            if (bare.equals(SensorBoardSupport.barePort(session.getPortName()))) {
                out.addProperty("ok", true);
                out.addProperty("systemType", DeviceDiscoveryService.SYSTEM_TYPE_SENSOR_BOARD);
                out.addProperty("listening", true);
                out.addProperty("alreadyListening", true);
                if (session.getPrefix() != null) {
                    out.addProperty("prefix", session.getPrefix());
                }
                return out;
            }
        }

        boolean ok = classifyCandidate(match);
        out.addProperty("ok", ok);
        out.addProperty("systemType", match.systemType != null ? match.systemType : "unknown");
        out.addProperty("needsProbe", match.needsProbe);
        if (match.config != null && match.config.get("boardPrefix") != null) {
            out.addProperty("prefix", match.config.get("boardPrefix"));
        }

        if (ok && DeviceDiscoveryService.SYSTEM_TYPE_SENSOR_BOARD.equals(match.systemType)) {
            releasedSensorPorts.remove(bare);
            releasedSensorPorts.remove(match.port);
            startSensorBoard(match);
            out.addProperty("listening", sensorSessions.containsKey(
                    SensorBoardSupport.sessionIdForPort(match.port)));
        } else if (ok && DeviceDiscoveryService.SYSTEM_TYPE_DCCPP.equals(match.systemType)) {
            out.addProperty("note", "Classified as DCC++ — connect from the DCC page if needed");
        } else {
            out.addProperty("error", "Probe did not match a sensor board or DCC++ identity");
        }
        return out;
    }

    /**
     * Release a sensor session so the tty can be flashed or used externally.
     */
    public void releaseSensorBoard(String sessionId) {
        SensorBoardSession session = sensorSessions.get(sessionId);
        if (session == null) {
            // Allow releasing by port-derived id even if already released
            ReleasedSensorPort existing = findReleasedById(sessionId);
            if (existing != null) {
                return;
            }
            throw new IllegalArgumentException("Sensor board not found: " + sessionId);
        }
        String port = session.getPortName();
        String bare = SensorBoardSupport.barePort(port);
        ReleasedSensorPort held = new ReleasedSensorPort(
                sessionId, bare, session.getPrefix(), session.getByPath());
        removeSensorSession(sessionId);
        releasedSensorPorts.put(bare, held);
        System.out.println("Released sensor board port " + bare + " for flashing/manual use");
    }

    /**
     * Resume listening on a previously released sensor port.
     */
    public void resumeSensorBoard(String sessionId) throws IOException {
        ReleasedSensorPort held = findReleasedById(sessionId);
        if (held == null) {
            if (sensorSessions.containsKey(sessionId)) {
                return; // already listening
            }
            throw new IllegalArgumentException("No released sensor board: " + sessionId);
        }
        String bare = held.port;
        releasedSensorPorts.remove(bare);
        DeviceDiscoveryService.DetectedDevice device = new DeviceDiscoveryService.DetectedDevice();
        device.port = bare;
        device.systemType = DeviceDiscoveryService.SYSTEM_TYPE_SENSOR_BOARD;
        device.name = "PanelsDCC Sensor Board";
        device.byPath = held.byPath;
        if (held.prefix != null) {
            device.config.put("boardPrefix", held.prefix);
        }
        // Prefer live probe to refresh prefix
        UsbSerialProbe.ProbeResult result = usbProbe.verifySensorBoard(bare);
        if (result.type == UsbSerialProbe.DeviceType.SENSOR_BOARD) {
            applyProbeResult(device, result);
            if (device.byPath != null) {
                probeCache.put(device.byPath, device.systemType, result.prefix);
            }
        } else if (held.prefix == null) {
            throw new IOException("Port " + bare + " did not respond as a PanelsDCC Sensor Board");
        }
        startSensorBoard(device);
    }

    public synchronized boolean flashSensorBoard(String portName, String prefix) {
        String bare = SensorBoardSupport.barePort(portName);
        String sessionId = SensorBoardSupport.sessionIdForPort(bare);
        // Release if listening
        if (sensorSessions.containsKey(sessionId)) {
            releaseSensorBoard(sessionId);
        } else if (!releasedSensorPorts.containsKey(bare)) {
            ReleasedSensorPort held = new ReleasedSensorPort(sessionId, bare, prefix, null);
            releasedSensorPorts.put(bare, held);
        } else {
            ReleasedSensorPort prev = releasedSensorPorts.get(bare);
            releasedSensorPorts.put(bare, new ReleasedSensorPort(
                    prev.id, bare, prefix != null ? prefix : prev.prefix, prev.byPath));
        }

        return sensorFlasher.startFlash(bare, prefix, success -> {
            if (success) {
                try {
                    // Update held prefix then resume
                    ReleasedSensorPort held = releasedSensorPorts.get(bare);
                    if (held != null) {
                        releasedSensorPorts.put(bare, new ReleasedSensorPort(
                                held.id, bare, SensorBoardSupport.normalizePrefix(prefix), held.byPath));
                    }
                    resumeSensorBoard(sessionId);
                } catch (Exception e) {
                    System.err.println("Flash succeeded but resume failed on " + bare + ": " + e.getMessage());
                }
            }
        });
    }

    private ReleasedSensorPort findReleasedById(String sessionId) {
        for (ReleasedSensorPort r : releasedSensorPorts.values()) {
            if (r.id.equals(sessionId)) {
                return r;
            }
        }
        // Also match sensor_<port>
        for (Map.Entry<String, ReleasedSensorPort> e : releasedSensorPorts.entrySet()) {
            if (SensorBoardSupport.sessionIdForPort(e.getKey()).equals(sessionId)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Check for ports that have disappeared and remove connections using those ports.
     */
    private void checkForDisconnectedPorts() {
        Set<String> portsToCheck = new HashSet<>(connectedPorts);

        for (String port : portsToCheck) {
            if (!discoveryService.isPortPresent(port)) {
                String connectionId = portToConnectionId.get(port);
                if (connectionId != null) {
                    System.out.println("Port " + port + " no longer available, removing " + connectionId);
                    if (connectionId.startsWith("sensor_")) {
                        removeSensorSession(connectionId);
                    } else {
                        removeConnection(connectionId);
                    }
                } else {
                    connectedPorts.remove(port);
                }
            }
        }
    }

    private void removeSensorSession(String sessionId) {
        SensorBoardSession session = sensorSessions.remove(sessionId);
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignore) {
            }
            String portToRemove = null;
            for (Map.Entry<String, String> entry : portToConnectionId.entrySet()) {
                if (entry.getValue().equals(sessionId)) {
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

    private void assignControllerRoles(String connectionId) {
        synchronized (this) {
            if (throttleControllerId == null && accessoryControllerId == null) {
                throttleControllerId = connectionId;
                accessoryControllerId = connectionId;
                System.out.println("Assigned " + connectionId + " to both throttles and accessories (only controller)");
            } else if (throttleControllerId == null) {
                throttleControllerId = connectionId;
                System.out.println("Assigned " + connectionId + " to throttles");
            } else if (accessoryControllerId == null) {
                accessoryControllerId = connectionId;
                System.out.println("Assigned " + connectionId + " to accessories");
            }
        }
    }

    public void setControllerRole(String connectionId, ControllerRole role, boolean enabled) {
        synchronized (this) {
            if (enabled) {
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
                if (role == ControllerRole.THROTTLES && throttleControllerId != null && throttleControllerId.equals(connectionId)) {
                    throttleControllerId = null;
                }
                if (role == ControllerRole.ACCESSORIES && accessoryControllerId != null && accessoryControllerId.equals(connectionId)) {
                    accessoryControllerId = null;
                }
            }
        }
    }

    public String getThrottleControllerId() {
        return throttleControllerId;
    }

    public String getAccessoryControllerId() {
        return accessoryControllerId;
    }

    public CommandStationConnection getThrottleController() {
        if (throttleControllerId != null) {
            CommandStationConnection conn = connections.get(throttleControllerId);
            if (conn != null && conn.isConnected()) {
                return conn;
            }
        }
        for (CommandStationConnection conn : connections.values()) {
            if (conn.isConnected()) {
                return conn;
            }
        }
        return null;
    }

    public CommandStationConnection getAccessoryController() {
        if (accessoryControllerId != null) {
            CommandStationConnection conn = connections.get(accessoryControllerId);
            if (conn != null && conn.isConnected()) {
                return conn;
            }
        }
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
            throttles.entrySet().removeIf(entry -> {
                ThrottleSession throttle = entry.getValue();
                if (id.equals(throttle.getConnectionId())) {
                    try {
                        throttle.close();
                    } catch (Exception e) {
                        // Ignore errors during cleanup
                    }
                    return true;
                }
                return false;
            });

            try {
                conn.close();
            } catch (Exception ignore) {
            }
            synchronized (this) {
                if (id.equals(throttleControllerId)) {
                    throttleControllerId = null;
                }
                if (id.equals(accessoryControllerId)) {
                    accessoryControllerId = null;
                }
            }
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

    public String openThrottle(String connectionId, int address, boolean longAddress) throws IOException {
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
            return throttleId;
        }
        ThrottleSession throttle = conn.openThrottle(address, longAddress);
        throttles.put(throttleId, throttle);
        return throttleId;
    }

    public ThrottleSession getThrottle(String throttleId) {
        return throttles.get(throttleId);
    }

    public Collection<ThrottleSession> getThrottles() {
        throttles.entrySet().removeIf(entry -> {
            ThrottleSession throttle = entry.getValue();
            CommandStationConnection conn = connections.get(throttle.getConnectionId());
            if (conn == null || !conn.isConnected()) {
                try {
                    throttle.close();
                } catch (Exception e) {
                    // Ignore errors during cleanup
                }
                return true;
            }
            return false;
        });

        return Collections.unmodifiableCollection(throttles.values());
    }

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
        deviceMonitor.shutdown();
        try {
            if (!deviceMonitor.awaitTermination(2, TimeUnit.SECONDS)) {
                deviceMonitor.shutdownNow();
            }
        } catch (InterruptedException e) {
            deviceMonitor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        for (SensorBoardSession session : sensorSessions.values()) {
            try {
                session.close();
            } catch (Exception ignore) {
            }
        }
        sensorSessions.clear();
        releasedSensorPorts.clear();
        sensorFlasher.shutdown();

        for (ThrottleSession throttle : throttles.values()) {
            try {
                throttle.close();
            } catch (Exception ignore) {
            }
        }
        throttles.clear();
        for (CommandStationConnection conn : connections.values()) {
            conn.close();
        }
        connections.clear();
        connectedPorts.clear();
        portToConnectionId.clear();
        probeCooldownUntil.clear();
    }
}
