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
import cc.panelsd.connect.core.impl.node.BoardProfile;
import cc.panelsd.connect.core.impl.node.BluetoothNodeSupport;
import cc.panelsd.connect.core.impl.node.ImageVersions;
import cc.panelsd.connect.core.impl.node.NodeCfgDocument;
import cc.panelsd.connect.core.impl.node.NodeInventory;
import cc.panelsd.connect.core.impl.node.NodeOutputMirror;
import cc.panelsd.connect.core.impl.node.NodeOutputSchemas;
import cc.panelsd.connect.core.impl.node.NodeSession;
import cc.panelsd.connect.core.impl.node.FlashPortScanner;
import cc.panelsd.connect.core.impl.node.NodeFlasher;
import cc.panelsd.connect.core.impl.node.UsbNodeSession;
import cc.panelsd.connect.core.impl.node.NodeSupport;
import cc.panelsd.connect.core.impl.node.VirtualNodeSession;
import cc.panelsd.connect.core.impl.xnet.elite.XNetEliteConnection;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory implementation of {@link DccIoService} that manages JMRI-backed
 * connections and shares a single {@link DccEventBus} for outbound events.
 */
public final class DccIoServiceImpl implements DccIoService {

    private static final long UNKNOWN_PROBE_COOLDOWN_MS = 12_000L;
    private static final long BUSY_PROBE_COOLDOWN_MS = 4_000L;
    /** Known / inventory node ports retry sooner than true unknowns. */
    private static final long KNOWN_NODE_PROBE_COOLDOWN_MS = 2_000L;
    private static final long CACHE_MISS_SETTLE_MS = 750L;

    private final Map<String, CommandStationConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, ThrottleSession> throttles = new ConcurrentHashMap<>();
    private final Map<String, UsbNodeSession> nodeSessions = new ConcurrentHashMap<>();
    private final Map<String, VirtualNodeSession> shimSessions = new ConcurrentHashMap<>();
    /** Ports held released for flashing — skip auto-connect until resume. */
    private final Map<String, ReleasedSensorPort> releasedSensorPorts = new ConcurrentHashMap<>();
    private final List<JsonObject> sensorEventRing = Collections.synchronizedList(new ArrayList<>());
    /** Last known state per hardwareId (0/1). */
    private final Map<String, Integer> sensorChannelStates = new ConcurrentHashMap<>();
    private static final int SENSOR_EVENT_RING_MAX = 50;

    private final DccEventBus eventBus = new DccEventBus();
    private final DeviceDiscoveryService discoveryService = new DeviceDiscoveryService();
    private final UsbSerialProbe usbProbe = new UsbSerialProbe();
    private final FlashPortScanner flashPortScanner = new FlashPortScanner(usbProbe);
    /** Held while a port is opened for a probe or a node session, so a flash can wait for that open to finish. */
    private final Object usbProbeLock = new Object();
    /** Serial-device keys whose node session must stay closed until flashing finishes. */
    private final Set<String> flashingPortKeys = ConcurrentHashMap.newKeySet();
    private final UsbProbeCache probeCache = new UsbProbeCache();
    private final NodeFlasher nodeFlasher = new NodeFlasher();
    private final NodeInventory nodeInventory = new NodeInventory();
    private final NodeOutputMirror nodeOutputMirror = new NodeOutputMirror();

    /** Ports that recently classified as unknown — avoid hammering/resetting devices. */
    private final Map<String, Long> probeCooldownUntil = new ConcurrentHashMap<>();
    /** Prefixes that must not receive a CFG push after the next USB bind (start fresh). */
    private final Set<String> skipCfgPushPrefixes = ConcurrentHashMap.newKeySet();

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
        nodeOutputMirror.setSensorHardwarePublisher(this.sensorHardwarePublisher);
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
        return channelsJsonForPrefix(prefix, BoardProfile.NANO_AVR);
    }

    private JsonArray channelsJsonForPrefix(String prefix, String boardType) {
        JsonArray channels = new JsonArray();
        for (NodeSupport.ChannelDef def : NodeSupport.channelDefs(prefix, boardType)) {
            JsonObject ch = new JsonObject();
            ch.addProperty("id", def.id);
            ch.addProperty("label", def.label);
            ch.addProperty("kind", def.kind);
            ch.addProperty("state", sensorChannelStates.getOrDefault(def.id, 0));
            channels.add(ch);
        }
        NodeInventory.NodeRecord rec = nodeInventory.findByPrefix(prefix);
        appendVirtualChannels(channels, prefix, rec != null ? rec.outputs : null);
        return channels;
    }

    /** Append LC virtual feedback channels ({@code kind: virtual}) into a channels array. */
    private void appendVirtualChannels(JsonArray channels, String prefix, JsonArray outputs) {
        if (channels == null || prefix == null) {
            return;
        }
        Set<String> existing = new HashSet<>();
        for (JsonElement el : channels) {
            if (el.isJsonObject() && el.getAsJsonObject().has("id")) {
                existing.add(el.getAsJsonObject().get("id").getAsString());
            }
        }
        for (NodeSupport.ChannelDef def : NodeSupport.virtualChannelDefs(prefix, outputs)) {
            if (existing.contains(def.id)) {
                continue;
            }
            JsonObject ch = new JsonObject();
            ch.addProperty("id", def.id);
            ch.addProperty("label", def.label);
            ch.addProperty("kind", def.kind);
            ch.addProperty("state", sensorChannelStates.getOrDefault(def.id, 0));
            channels.add(ch);
            existing.add(def.id);
        }
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
     * Auto-discover and connect to detected controllers / nodes.
     */
    private void autoConnectDevices() {
        checkForDisconnectedPorts();

        List<DeviceDiscoveryService.DetectedDevice> devices = discoveryService.discoverDevices();
        for (DeviceDiscoveryService.DetectedDevice device : devices) {
            if (connectedPorts.contains(device.port)) {
                continue;
            }
            // Held for flashing / manual release — do not auto-bind.
            // cu.* and tty.* are one chip, so a release of either covers both.
            if (sensorPortReleased(device.port) || isFlashingPort(device.port)) {
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

            if (DeviceDiscoveryService.SYSTEM_TYPE_NODE.equals(device.systemType)) {
                startNode(device);
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
        synchronized (usbProbeLock) {
            if (isFlashingPort(device.port)) {
                return false;
            }
            return classifyCandidateHoldingLock(device);
        }
    }

    private boolean classifyCandidateHoldingLock(DeviceDiscoveryService.DetectedDevice device) {
        ProbeResult result = null;
        boolean cacheMiss = false;

        // Prefer cached type for this physical USB path
        if (device.byPath != null) {
            UsbProbeCache.Entry cached = probeCache.get(device.byPath);
            if (cached != null) {
                if (DeviceDiscoveryService.SYSTEM_TYPE_NODE.equals(cached.type)) {
                    result = usbProbe.verifyNode(device.port);
                    if (result.type != DeviceType.NODE) {
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
            // Arduino VID/PID already named a board type (e.g. Nano ESP32) — bind without
            // a serial banner so plug-and-play does not wait for Probe.
            ProbeResult hinted = softBindFromUsbHint(device);
            if (hinted != null && !result.portBusy) {
                result = hinted;
            } else {
                long cooldown = result.portBusy ? BUSY_PROBE_COOLDOWN_MS : UNKNOWN_PROBE_COOLDOWN_MS;
                if (!result.portBusy && isKnownNodePort(device)) {
                    cooldown = KNOWN_NODE_PROBE_COOLDOWN_MS;
                }
                probeCooldownUntil.put(device.port, System.currentTimeMillis() + cooldown);
                System.out.println("USB serial probe: unknown device on " + device.port
                        + (result.portBusy ? " (port busy)" : "")
                        + " — leaving alone for " + (cooldown / 1000) + "s");
                return false;
            }
        }

        probeCooldownUntil.remove(device.port);
        applyProbeResult(device, result);

        if (device.byPath != null) {
            probeCache.put(device.byPath, device.systemType,
                    result.type == DeviceType.NODE ? result.prefix : null);
        }

        System.out.println("USB serial probe: " + device.port + " → " + device.systemType
                + (result.prefix != null ? " prefix=" + result.prefix : ""));
        return true;
    }

    private boolean isKnownNodePort(DeviceDiscoveryService.DetectedDevice device) {
        if (device == null) {
            return false;
        }
        if (nodeInventory.findByEndpoint(device.port) != null) {
            return true;
        }
        String bare = NodeSupport.barePort(device.port);
        return bare != null && nodeInventory.findByEndpoint(bare) != null;
    }

    /**
     * When USB identity already carries a valid boardType, treat the port as a node
     * even if the serial READY/ID probe missed (common on native-USB ESP32).
     */
    private ProbeResult softBindFromUsbHint(DeviceDiscoveryService.DetectedDevice device) {
        if (device == null || device.config == null) {
            return null;
        }
        String hintedType = device.config.get("boardType");
        if (!BoardProfile.isValid(hintedType)) {
            return null;
        }
        String prefix = device.config.get("boardPrefix");
        NodeInventory.NodeRecord known = nodeInventory.findByEndpoint(device.port);
        if (known == null) {
            known = nodeInventory.findByEndpoint(NodeSupport.barePort(device.port));
        }
        if ((prefix == null || prefix.isBlank()) && known != null) {
            prefix = known.prefix;
        }
        String version = known != null ? known.firmwareVersion : null;
        return ProbeResult.node(prefix, BoardProfile.normalize(hintedType), version);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void applyProbeResult(DeviceDiscoveryService.DetectedDevice device, ProbeResult result) {
        if (result.type == DeviceType.NODE) {
            device.systemType = DeviceDiscoveryService.SYSTEM_TYPE_NODE;
            device.name = "PanelsDCC Node";
            device.needsProbe = false;
            device.config.put("baudRate", String.valueOf(UsbSerialProbe.NODE_BAUD));
            if (result.prefix != null) {
                device.config.put("boardPrefix", result.prefix);
            }
            if (result.boardType != null) {
                device.config.put("boardType", BoardProfile.normalize(result.boardType));
            }
            if (result.firmwareVersion != null) {
                device.config.put("firmwareVersion", String.valueOf(result.firmwareVersion));
            }
        } else if (result.type == DeviceType.DCCPP) {
            device.systemType = DeviceDiscoveryService.SYSTEM_TYPE_DCCPP;
            device.name = "DCC++ (CH340)";
            device.needsProbe = false;
            device.config.put("baudRate", String.valueOf(UsbSerialProbe.DCCPP_BAUD));
        }
    }

    /** Keep a flashed prefix and board type when the live probe could not see the boot banner. */
    private void fillIdentityFromInventory(DeviceDiscoveryService.DetectedDevice device) {
        if (device.config == null) {
            device.config = new java.util.HashMap<>();
        }
        NodeInventory.NodeRecord known = nodeInventory.findByEndpoint(device.port);
        if (known == null) {
            return;
        }
        if ((device.config.get("boardPrefix") == null || device.config.get("boardPrefix").isBlank())
                && known.prefix != null) {
            device.config.put("boardPrefix", known.prefix);
        }
        if ((device.config.get("boardType") == null || device.config.get("boardType").isBlank())
                && known.boardType != null) {
            device.config.put("boardType", known.boardType);
        }
    }

    private void startNode(DeviceDiscoveryService.DetectedDevice device) {
        UsbNodeSession session;
        synchronized (usbProbeLock) {
            if (isFlashingPort(device.port) || sensorPortReleased(device.port)) {
                return;
            }
            String sessionId = "sensor_" + device.port.replace("/", "_").replace("\\", "_");
            if (nodeSessions.containsKey(sessionId)) {
                connectedPorts.add(device.port);
                portToConnectionId.put(device.port, sessionId);
                return;
            }
            fillIdentityFromInventory(device);
            String prefix = device.config.get("boardPrefix");
            String boardType = BoardProfile.normalize(device.config.get("boardType"));
            session = new UsbNodeSession(
                    device.port,
                    prefix,
                    device.byPath,
                    boardType,
                    () -> sensorHardwarePublisher);
            try {
                session.start();
                nodeSessions.put(sessionId, session);
                connectedPorts.add(device.port);
                portToConnectionId.put(device.port, sessionId);
            } catch (Exception e) {
                System.err.println("Failed to start node on " + device.port + ": " + e.getMessage());
                session.close();
                return;
            }
        }
        rememberUsbNode(session);
    }

    private void rememberUsbNode(UsbNodeSession session) {
        boolean skipCfgPush = session.getPrefix() != null
                && skipCfgPushPrefixes.remove(session.getPrefix());
        NodeInventory.NodeRecord rec = new NodeInventory.NodeRecord();
        rec.id = session.getId();
        rec.prefix = session.getPrefix();
        rec.boardType = session.getBoardType();
        rec.transport = NodeSupport.TRANSPORT_USB;
        rec.endpoint = session.getEndpoint();
        NodeInventory.NodeRecord existing = nodeInventory.findByPrefix(session.getPrefix());
        if (existing != null) {
            if (!skipCfgPush) {
                rec.pins = existing.pins;
                rec.outputs = existing.outputs;
                rec.opticalDistricts = existing.opticalDistricts;
                rec.config = existing.config;
            } else {
                // Keep the empty layout written by flashNode(clearConfig).
                rec.pins = existing.pins;
                rec.outputs = existing.outputs;
                rec.opticalDistricts = existing.opticalDistricts;
                rec.config = existing.config;
            }
            if (existing.firmwareVersion != null) {
                rec.firmwareVersion = existing.firmwareVersion;
            }
        }
        nodeInventory.upsert(rec);
        // Opening the port resets an AVR Nano. Ask the board for its configuration
        // after boot. A baked image answers from program storage. A generic image
        // with an empty EEPROM still receives the inventory Connect already has.
        final String sessionId = session.getId();
        final String prefix = session.getPrefix();
        final JsonObject savedCfg = (skipCfgPush || rec.config == null) ? null : rec.config.deepCopy();
        final JsonArray savedOuts = (!skipCfgPush && rec.outputs != null)
                ? rec.outputs.deepCopy() : new JsonArray();
        deviceMonitor.schedule(
                () -> pullCfgOrPushSaved(sessionId, prefix, savedCfg, savedOuts),
                2, TimeUnit.SECONDS);
    }

    private void pullCfgOrPushSaved(String sessionId, String prefix, JsonObject savedCfg, JsonArray savedOuts) {
        UsbNodeSession live = nodeSessions.get(sessionId);
        if (live == null || !live.isRunning()) {
            return;
        }
        try {
            refreshFirmwareVersion(live, sessionId, prefix);
            String reply = live.sendCommandAwaitReply("CFG?", "CFG", 8000L);
            JsonObject fromBoard = NodeCfgDocument.parse(reply);
            if (fromBoard != null) {
                NodeInventory.NodeRecord rec = nodeInventory.findById(sessionId);
                if (rec == null) {
                    rec = nodeInventory.findByPrefix(prefix);
                }
                if (rec != null) {
                    NodeCfgDocument.copyOnto(rec, fromBoard);
                    nodeInventory.upsert(rec);
                    nodeOutputMirror.syncConfig(prefix, rec.outputs != null ? rec.outputs : new JsonArray());
                }
                return;
            }
            if (savedCfg != null) {
                live.sendCommand("CFG " + savedCfg);
                nodeOutputMirror.syncConfig(prefix, savedOuts);
            }
        } catch (Exception e) {
            System.err.println("Failed to read CFG from " + sessionId + ": " + e.getMessage());
        }
    }

    private void refreshFirmwareVersion(UsbNodeSession live, String sessionId, String prefix) {
        try {
            String reply = live.sendCommandAwaitReply("ID?", "ID", 3000L);
            if (reply == null) {
                return;
            }
            Matcher m = Pattern.compile("^ID ([A-Za-z]{1,2}) (\\S+)(?: (\\d{4}-\\d{2}-\\d{2}|\\d+(?:\\.\\d+)*))?\\s*$")
                    .matcher(reply.trim());
            if (!m.matches()) {
                return;
            }
            NodeInventory.NodeRecord rec = nodeInventory.findById(sessionId);
            if (rec == null) {
                rec = nodeInventory.findByPrefix(prefix);
            }
            if (rec == null) {
                return;
            }
            if (m.group(2) != null && BoardProfile.isValid(m.group(2))) {
                rec.boardType = BoardProfile.normalize(m.group(2));
            }
            if (m.group(3) != null) {
                rec.firmwareVersion = m.group(3);
            }
            nodeInventory.upsert(rec);
        } catch (Exception e) {
            System.err.println("Failed to read ID from " + sessionId + ": " + e.getMessage());
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
            for (UsbNodeSession session : nodeSessions.values()) {
                if (device.port.equals(session.getPortName())) {
                    device.systemType = DeviceDiscoveryService.SYSTEM_TYPE_NODE;
                    device.name = "PanelsDCC Node";
                    device.needsProbe = false;
                    if (session.getPrefix() != null) {
                        device.config.put("boardPrefix", session.getPrefix());
                    }
                    device.config.put("baudRate", String.valueOf(UsbSerialProbe.NODE_BAUD));
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
                    if (DeviceDiscoveryService.SYSTEM_TYPE_NODE.equals(cached.type)) {
                        device.name = "PanelsDCC Node (cached)";
                        if (cached.prefix != null) {
                            device.config.put("boardPrefix", cached.prefix);
                        }
                        device.config.put("baudRate", String.valueOf(UsbSerialProbe.NODE_BAUD));
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

    public Collection<UsbNodeSession> getNodeSessions() {
        return Collections.unmodifiableCollection(nodeSessions.values());
    }

    public NodeFlasher getNodeFlasher() {
        return nodeFlasher;
    }

    /**
     * Arduinos for the Flash USB port menu: port, board type, and label.
     * Command-station and debug ports stay on {@code /api/ports}.
     */
    public JsonObject flashPortsJson() {
        Map<String, String> sessions = new HashMap<>();
        Set<String> nodePorts = new HashSet<>();
        for (UsbNodeSession session : nodeSessions.values()) {
            String bare = NodeSupport.barePort(session.getPortName());
            if (bare == null) {
                continue;
            }
            nodePorts.add(bare);
            if (BoardProfile.isValid(session.getBoardType())) {
                sessions.put(bare, session.getBoardType());
            }
        }
        Set<String> commandStations = new HashSet<>();
        for (String port : portToConnectionId.keySet()) {
            String bare = NodeSupport.barePort(port);
            if (bare != null && !nodePorts.contains(bare)) {
                commandStations.add(bare);
            }
        }
        commandStations.addAll(flashingPortKeys);
        JsonArray arr = new JsonArray();
        for (FlashPortScanner.ListedPort listed : flashPortScanner.list(
                FlashPortScanner.readLivePorts(), sessions, commandStations)) {
            JsonObject row = new JsonObject();
            row.addProperty("port", listed.port);
            row.addProperty("boardType", listed.boardType);
            row.addProperty("label", listed.label);
            arr.add(row);
        }
        JsonObject root = new JsonObject();
        root.add("ports", arr);
        return root;
    }

    public JsonObject listSensorsJson() {
        JsonArray boards = new JsonArray();
        Set<String> seenPorts = new HashSet<>();

        for (Map.Entry<String, UsbNodeSession> e : nodeSessions.entrySet()) {
            UsbNodeSession s = e.getValue();
            JsonObject row = boardRow(s.getId(), s.getTransport(), s.getEndpoint(), s.getPrefix(),
                    s.getBoardType(), NodeSupport.STATUS_LISTENING, false);
            if (s.getByPath() != null) {
                row.addProperty("byPath", s.getByPath());
            }
            if (s.getPrefix() != null) {
                row.add("channels", channelsJsonForPrefix(s.getPrefix(), s.getBoardType()));
            }
            attachInventory(row, s.getPrefix());
            boards.add(row);
            seenPorts.add(NodeSupport.barePort(s.getPortName()));
        }

        for (VirtualNodeSession shim : shimSessions.values()) {
            JsonObject row = boardRow(shim.getId(), shim.getTransport(), shim.getEndpoint(), shim.getPrefix(),
                    shim.getBoardType(), NodeSupport.STATUS_LISTENING, true);
            row.add("channels", shim.channelsJson());
            attachInventory(row, shim.getPrefix());
            if (row.has("channels") && row.has("outputs")) {
                appendVirtualChannels(row.getAsJsonArray("channels"), shim.getPrefix(),
                        row.getAsJsonArray("outputs"));
            }
            boards.add(row);
        }

        for (ReleasedSensorPort r : releasedSensorPorts.values()) {
            String bare = NodeSupport.barePort(r.port);
            if (seenPorts.contains(bare)) {
                continue;
            }
            String boardType = BoardProfile.NANO_AVR;
            NodeInventory.NodeRecord inv = nodeInventory.findByPrefix(r.prefix);
            if (inv != null && inv.boardType != null) {
                boardType = inv.boardType;
            }
            String flashPort = null;
            JsonObject flashStatus = nodeFlasher.statusJson();
            if (flashStatus.has("port")) {
                flashPort = flashStatus.get("port").getAsString();
            }
            String status = nodeFlasher.isBusy() && bare.equals(NodeSupport.barePort(flashPort))
                    ? NodeSupport.STATUS_FLASHING
                    : NodeSupport.STATUS_RELEASED;
            JsonObject row = boardRow(r.id, NodeSupport.TRANSPORT_USB, r.port, r.prefix,
                    boardType, status, false);
            if (r.byPath != null) {
                row.addProperty("byPath", r.byPath);
            }
            if (r.prefix != null) {
                row.add("channels", channelsJsonForPrefix(r.prefix, boardType));
            }
            attachInventory(row, r.prefix);
            boards.add(row);
        }

        JsonArray candidates = new JsonArray();
        for (DeviceDiscoveryService.DetectedDevice device : discoveryService.discoverDevices()) {
            String bare = NodeSupport.barePort(device.port);
            if (seenPorts.contains(bare) || sensorPortReleased(device.port) || isFlashingPort(device.port)) {
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
        root.addProperty("suggestedPrefix", nodeInventory.suggestPrefix());
        JsonArray profiles = new JsonArray();
        for (BoardProfile p : BoardProfile.all()) {
            JsonObject pr = new JsonObject();
            pr.addProperty("id", p.id);
            pr.addProperty("label", p.label);
            pr.addProperty("fqbn", p.fqbn);
            pr.addProperty("defaultTransport", p.defaultTransport);
            if (p.ambientRefLabel != null) {
                pr.addProperty("ambientRefLabel", p.ambientRefLabel);
            }
            JsonArray defaultOptical = new JsonArray();
            for (String label : p.defaultOpticalLabels) {
                defaultOptical.add(label);
            }
            pr.add("defaultOpticalLabels", defaultOptical);
            JsonArray defaultDigital = new JsonArray();
            for (String label : p.defaultDigitalLabels) {
                defaultDigital.add(label);
            }
            pr.add("defaultDigitalLabels", defaultDigital);
            JsonArray pinArr = new JsonArray();
            for (BoardProfile.PinDef pin : p.pins) {
                JsonObject po = new JsonObject();
                po.addProperty("silkLabel", pin.silkLabel);
                JsonArray caps = new JsonArray();
                for (String c : pin.capabilities) {
                    caps.add(c);
                }
                po.add("capabilities", caps);
                pinArr.add(po);
            }
            pr.add("pins", pinArr);
            profiles.add(pr);
        }
        root.add("boardProfiles", profiles);
        root.add("outputTypes", NodeOutputSchemas.schemasJson().getAsJsonArray("types"));

        JsonArray transports = new JsonArray();
        JsonObject usb = new JsonObject();
        usb.addProperty("id", NodeSupport.TRANSPORT_USB);
        usb.addProperty("available", true);
        usb.addProperty("label", "USB (Nano / Mega AVR)");
        transports.add(usb);
        JsonObject shim = new JsonObject();
        shim.addProperty("id", NodeSupport.TRANSPORT_SHIM);
        shim.addProperty("available", true);
        shim.addProperty("label", "Virtual (UI testing)");
        shim.addProperty("note", "In-process shim boards — no hardware required");
        transports.add(shim);
        JsonObject wifi = new JsonObject();
        wifi.addProperty("id", NodeSupport.TRANSPORT_WIFI);
        wifi.addProperty("available", false);
        wifi.addProperty("label", "Wi‑Fi (ESP32)");
        wifi.addProperty("note", "Later — use Bluetooth SPP for ESP32 nodes");
        transports.add(wifi);
        transports.add(BluetoothNodeSupport.statusJson());
        root.add("transports", transports);
        root.addProperty("outputAnimating", nodeOutputMirror.anyAnimating());
        return root;
    }

    private static JsonObject boardRow(
            String id, String transport, String endpoint, String prefix,
            String boardType, String status, boolean virtual) {
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("transport", transport);
        row.addProperty("endpoint", endpoint);
        if (prefix != null) {
            row.addProperty("prefix", prefix);
        }
        if (boardType != null) {
            row.addProperty("boardType", boardType);
        }
        row.addProperty("status", status);
        row.addProperty("virtual", virtual);
        return row;
    }

    private void attachInventory(JsonObject row, String prefix) {
        NodeInventory.NodeRecord rec = nodeInventory.findByPrefix(prefix);
        if (rec == null) {
            attachImageVersions(row, row.has("boardType") ? row.get("boardType").getAsString() : null, null);
            return;
        }
        if (rec.outputs != null) {
            row.add("outputs", rec.outputs);
            ensureMirrorSynced(prefix, rec.outputs);
        }
        if (rec.pins != null) {
            row.add("pins", rec.pins);
        }
        if (rec.opticalDistricts != null) {
            row.add("opticalDistricts", rec.opticalDistricts);
        } else if (rec.config != null && rec.config.has("opticalDistricts")
                && rec.config.get("opticalDistricts").isJsonArray()) {
            row.add("opticalDistricts", rec.config.getAsJsonArray("opticalDistricts"));
        }
        if (rec.config != null) {
            row.add("config", rec.config);
        }
        JsonArray states = nodeOutputMirror.statesJson(prefix);
        row.add("outputStates", states);
        row.addProperty("outputStatesMirrored", true);
        row.addProperty("outputAnimating", nodeOutputMirror.isAnimating(prefix));
        String boardType = rec.boardType != null
                ? rec.boardType
                : (row.has("boardType") ? row.get("boardType").getAsString() : null);
        attachImageVersions(row, boardType, rec.firmwareVersion);
    }

    private void attachImageVersions(JsonObject row, String boardType, String firmwareVersion) {
        String boardVer = null;
        if (firmwareVersion != null && !firmwareVersion.isBlank()) {
            boardVer = firmwareVersion.trim();
            row.addProperty("firmwareVersion", boardVer);
        }
        if (boardType == null || boardType.isBlank()) {
            return;
        }
        nodeFlasher.catalog().select(boardType).ifPresent(image -> {
            row.addProperty("shippedVersion", image.version);
            row.addProperty("imageSource", image.source);
        });
        if (row.has("shippedVersion") && boardVer != null) {
            row.addProperty("updateAvailable",
                    ImageVersions.compare(boardVer, row.get("shippedVersion").getAsString()) < 0);
        } else if (row.has("shippedVersion") && boardVer == null) {
            // Unknown on-board version (old image without ID version) — offer Update.
            row.addProperty("updateAvailable", true);
        } else if (boardVer != null) {
            row.addProperty("updateAvailable", false);
        }
    }

    private void ensureMirrorSynced(String prefix, JsonArray outputs) {
        if (outputs == null) {
            return;
        }
        // First load, size mismatch, or legacy outputs that had no triggers[] until upgrade.
        if (nodeOutputMirror.needsSync(prefix, outputs)) {
            nodeOutputMirror.syncConfig(prefix, outputs);
        }
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
     * session when classification succeeds as a node.
     */
    public JsonObject probeUsbSerial(String portName) {
        if (portName == null || portName.trim().isEmpty()) {
            throw new IllegalArgumentException("port required");
        }
        String bare = NodeSupport.barePort(portName.trim());
        String path = NodeSupport.devicePath(bare);

        probeCooldownUntil.remove(bare);
        probeCooldownUntil.remove(path);
        probeCooldownUntil.remove(portName.trim());

        DeviceDiscoveryService.DetectedDevice match = null;
        for (DeviceDiscoveryService.DetectedDevice device : discoveryService.discoverDevices()) {
            String dBare = NodeSupport.barePort(device.port);
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

        if (isFlashingPort(bare) || sensorPortReleased(bare)) {
            out.addProperty("ok", false);
            out.addProperty("error", "Port is held for flashing — wait until flash finishes");
            return out;
        }

        for (UsbNodeSession session : nodeSessions.values()) {
            if (bare.equals(NodeSupport.barePort(session.getPortName()))) {
                out.addProperty("ok", true);
                out.addProperty("systemType", DeviceDiscoveryService.SYSTEM_TYPE_NODE);
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

        if (ok && DeviceDiscoveryService.SYSTEM_TYPE_NODE.equals(match.systemType)) {
            releasedSensorPorts.remove(bare);
            releasedSensorPorts.remove(match.port);
            startNode(match);
            out.addProperty("listening", nodeSessions.containsKey(
                    NodeSupport.sessionIdForPort(match.port)));
        } else if (ok && DeviceDiscoveryService.SYSTEM_TYPE_DCCPP.equals(match.systemType)) {
            out.addProperty("note", "Classified as DCC++ — connect from the DCC page if needed");
        } else {
            out.addProperty("error", "Probe did not match a node or DCC++ identity");
        }
        return out;
    }

    /**
     * Release a sensor session so the tty can be flashed or used externally.
     */
    public void releaseNode(String sessionId) {
        UsbNodeSession session = nodeSessions.get(sessionId);
        if (session == null) {
            // Allow releasing by port-derived id even if already released
            ReleasedSensorPort existing = findReleasedById(sessionId);
            if (existing != null) {
                return;
            }
            throw new IllegalArgumentException("Sensor board not found: " + sessionId);
        }
        String port = session.getPortName();
        String bare = NodeSupport.barePort(port);
        ReleasedSensorPort held = new ReleasedSensorPort(
                sessionId, bare, session.getPrefix(), session.getByPath());
        removeSensorSession(sessionId);
        releasedSensorPorts.put(bare, held);
        System.out.println("Released node port " + bare + " for flashing/manual use");
    }

    /**
     * Resume listening on a previously released sensor port.
     */
    public void resumeNode(String sessionId) throws IOException {
        ReleasedSensorPort held = findReleasedById(sessionId);
        if (held == null) {
            if (nodeSessions.containsKey(sessionId)) {
                return; // already listening
            }
            throw new IllegalArgumentException("No released node: " + sessionId);
        }
        String bare = held.port;
        releasedSensorPorts.remove(bare);
        DeviceDiscoveryService.DetectedDevice device = new DeviceDiscoveryService.DetectedDevice();
        device.port = bare;
        device.systemType = DeviceDiscoveryService.SYSTEM_TYPE_NODE;
        device.name = "PanelsDCC Node";
        device.byPath = held.byPath;
        if (held.prefix != null) {
            device.config.put("boardPrefix", held.prefix);
        }
        NodeInventory.NodeRecord known = nodeInventory.findById(sessionId);
        if (known == null && held.prefix != null) {
            known = nodeInventory.findByPrefix(held.prefix);
        }
        if (known != null && known.boardType != null) {
            device.config.put("boardType", known.boardType);
        }
        // Prefer live probe to refresh prefix
        UsbSerialProbe.ProbeResult result = usbProbe.verifyNode(bare);
        if (result.type == UsbSerialProbe.DeviceType.NODE) {
            applyProbeResult(device, result);
            if (device.byPath != null) {
                probeCache.put(device.byPath, device.systemType, result.prefix);
            }
        } else if (held.prefix == null) {
            throw new IOException("Port " + bare + " did not respond as a PanelsDCC Node");
        }
        startNode(device);
    }

    public synchronized boolean flashNode(String portName, String prefix) {
        return flashNode(portName, prefix, BoardProfile.NANO_AVR, false);
    }

    public synchronized boolean flashNode(String portName, String prefix, String boardType) {
        return flashNode(portName, prefix, boardType, false);
    }

    public synchronized boolean flashNode(
            String portName, String prefix, String boardType, boolean clearConfig) {
        String type = NodeFlasher.isDetect(boardType) ? NodeFlasher.DETECT : BoardProfile.normalize(boardType);
        // Baked Nano AVR has no runtime CFG to clear.
        if (clearConfig && BoardProfile.NANO_AVR.equals(type)) {
            clearConfig = false;
        }
        String bare = NodeSupport.barePort(portName);
        String sessionId = NodeSupport.sessionIdForPort(bare);
        String deviceKey = NodeSupport.serialDeviceKey(bare);
        synchronized (usbProbeLock) {
            // Flash hold first: probe / auto-bind must not reopen this USB device.
            if (deviceKey != null) {
                flashingPortKeys.add(deviceKey);
            }
            // Soft enter download mode while we still own the session (buried boards).
            maybeRequestEspBootloader(bare, type);
            releaseSessionsForFlash(bare, prefix, sessionId);
            // USB-CDC needs longer than UART bridges before a follow-up 1200 touch.
            sleepQuietly(800L);
        }

        NodeInventory.NodeRecord previous = nodeInventory.findById(sessionId);
        if (previous == null) {
            previous = nodeInventory.findByPrefix(NodeSupport.normalizePrefix(prefix));
        }
        NodeInventory.NodeRecord rec = new NodeInventory.NodeRecord();
        rec.id = sessionId;
        rec.prefix = NodeSupport.normalizePrefix(prefix);
        rec.boardType = NodeFlasher.DETECT.equals(type)
                ? (previous == null ? null : previous.boardType)
                : type;
        rec.transport = NodeSupport.TRANSPORT_USB;
        rec.endpoint = bare;
        if (previous != null && !clearConfig) {
            rec.pins = previous.pins;
            rec.outputs = previous.outputs;
            rec.opticalDistricts = previous.opticalDistricts;
            rec.config = previous.config;
            rec.firmwareVersion = previous.firmwareVersion;
        } else if (clearConfig) {
            rec.pins = null;
            rec.outputs = null;
            rec.opticalDistricts = null;
            rec.config = null;
            nodeOutputMirror.syncConfig(rec.prefix, new JsonArray());
        }
        nodeInventory.upsert(rec);

        final boolean wipeConfig = clearConfig;
        boolean started = nodeFlasher.startFlash(bare, prefix, type, wipeConfig, success -> {
            if (deviceKey != null) {
                flashingPortKeys.remove(deviceKey);
            }
            try {
                if (success) {
                    String detected = nodeFlasher.currentBoardType();
                    NodeInventory.NodeRecord written = nodeInventory.findById(sessionId);
                    if (written == null) {
                        written = nodeInventory.findByPrefix(NodeSupport.normalizePrefix(prefix));
                    }
                    if (written != null) {
                        if (BoardProfile.isValid(detected)) {
                            written.boardType = detected;
                        }
                        String imageType = written.boardType != null ? written.boardType : detected;
                        if (BoardProfile.isValid(imageType)) {
                            var image = nodeFlasher.catalog().select(imageType);
                            if (image.isPresent()) {
                                written.firmwareVersion = image.get().version;
                            }
                        }
                        nodeInventory.upsert(written);
                    }
                    ReleasedSensorPort held = releasedSensorPorts.get(bare);
                    if (held != null) {
                        releasedSensorPorts.put(bare, new ReleasedSensorPort(
                                held.id, bare, NodeSupport.normalizePrefix(prefix), held.byPath));
                    }
                }
                if (wipeConfig) {
                    skipCfgPushPrefixes.add(NodeSupport.normalizePrefix(prefix));
                }
                resumeNode(sessionId);
            } catch (Exception e) {
                System.err.println((success
                        ? "Flash succeeded but the node did not restart on "
                        : "Flash stopped and the node was not restarted on ")
                        + bare + ": " + e.getMessage());
            }
        });
        if (!started && deviceKey != null) {
            flashingPortKeys.remove(deviceKey);
            try {
                resumeNode(sessionId);
            } catch (Exception e) {
                System.err.println("Could not restart the node on " + bare + ": " + e.getMessage());
            }
        }
        return started;
    }

    /**
     * Ask a live ESP32 node to soft-enter ROM download mode before we drop the session.
     * Older images without {@code BOOTLOADER} are left alone; 1200-baud follows under flash-hold.
     */
    private void maybeRequestEspBootloader(String bare, String boardType) {
        // Soft BOOTLOADER is for native-USB ESP32-S3 (Nano ESP32). Mega ESP32 uses UART DTR/RTS.
        String type = BoardProfile.normalize(boardType);
        String key = NodeSupport.serialDeviceKey(bare);
        UsbNodeSession session = null;
        for (UsbNodeSession s : nodeSessions.values()) {
            if (bare.equals(NodeSupport.barePort(s.getPortName()))
                    || (key != null && key.equals(NodeSupport.serialDeviceKey(s.getPortName())))) {
                session = s;
                break;
            }
        }
        if (session == null) {
            return;
        }
        String sessionType = BoardProfile.normalize(session.getBoardType());
        boolean nanoEsp = BoardProfile.NANO_ESP32.equals(type)
                || BoardProfile.NANO_ESP32.equals(sessionType);
        if (!nanoEsp) {
            return;
        }
        System.out.println("Requesting BOOTLOADER on " + session.getPortName()
                + " (" + sessionType + ") before flash");
        session.sendCommand("BOOTLOADER");
        sleepQuietly(250L);
    }

    /**
     * Close every live session on this USB device, including a session stored under a different id.
     * Marks the port released so monitoring does not open it again while esptool or avrdude runs.
     */
    private void releaseSessionsForFlash(String bare, String prefix, String sessionId) {
        String key = NodeSupport.serialDeviceKey(bare);
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, UsbNodeSession> entry : nodeSessions.entrySet()) {
            UsbNodeSession session = entry.getValue();
            boolean sameDevice = key != null && key.equals(NodeSupport.serialDeviceKey(session.getPortName()));
            if (sameDevice || entry.getKey().equals(sessionId) || sessionId.equals(session.getId())) {
                ids.add(entry.getKey());
            }
        }
        String keptPrefix = prefix;
        String byPath = null;
        String keptId = sessionId;
        for (String id : ids) {
            UsbNodeSession session = nodeSessions.get(id);
            if (session == null) {
                continue;
            }
            if (keptPrefix == null || keptPrefix.isBlank()) {
                keptPrefix = session.getPrefix();
            }
            if (session.getByPath() != null) {
                byPath = session.getByPath();
            }
            keptId = id;
            removeSensorSession(id);
            System.out.println("Released node port " + session.getPortName() + " so it can be flashed");
        }
        ReleasedSensorPort prev = releasedSensorPorts.get(bare);
        if (prev == null) {
            prev = findReleasedById(sessionId);
        }
        if (byPath == null && prev != null) {
            byPath = prev.byPath;
        }
        if ((keptPrefix == null || keptPrefix.isBlank()) && prev != null) {
            keptPrefix = prev.prefix;
        }
        releasedSensorPorts.put(bare, new ReleasedSensorPort(keptId, bare, keptPrefix, byPath));
    }

    private boolean isFlashingPort(String port) {
        String key = NodeSupport.serialDeviceKey(port);
        return key != null && flashingPortKeys.contains(key);
    }

    private boolean sensorPortReleased(String port) {
        String key = NodeSupport.serialDeviceKey(port);
        if (key == null) {
            return false;
        }
        for (String held : releasedSensorPorts.keySet()) {
            if (key.equals(NodeSupport.serialDeviceKey(held))) {
                return true;
            }
        }
        return false;
    }

    private ReleasedSensorPort findReleasedById(String sessionId) {
        for (ReleasedSensorPort r : releasedSensorPorts.values()) {
            if (r.id.equals(sessionId)) {
                return r;
            }
        }
        // Also match sensor_<port>
        for (Map.Entry<String, ReleasedSensorPort> e : releasedSensorPorts.entrySet()) {
            if (NodeSupport.sessionIdForPort(e.getKey()).equals(sessionId)) {
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
        UsbNodeSession session = nodeSessions.remove(sessionId);
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

    public NodeInventory getNodeInventory() {
        return nodeInventory;
    }

    public JsonObject createShimBoard(String prefix, String boardType) {
        String p = prefix != null && !prefix.isBlank()
                ? NodeSupport.normalizePrefix(prefix)
                : nodeInventory.suggestPrefix();
        if (!NodeSupport.isValidPrefix(p)) {
            throw new IllegalArgumentException("Invalid prefix: " + prefix);
        }
        String shimId = NodeSupport.sessionIdForShim(p);
        if (shimSessions.containsKey(shimId)) {
            removeShimBoard(shimId);
        }
        String type = BoardProfile.normalize(boardType);
        VirtualNodeSession session = new VirtualNodeSession(p, type, () -> sensorHardwarePublisher);
        session.start();
        shimSessions.put(session.getId(), session);
        NodeInventory.NodeRecord rec = NodeInventory.NodeRecord.shim(p, type);
        nodeInventory.upsert(rec);
        nodeFlasher.startShimFlash(p, type, ok -> {
        });
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("id", session.getId());
        out.addProperty("prefix", p);
        out.addProperty("boardType", type);
        out.addProperty("transport", NodeSupport.TRANSPORT_SHIM);
        return out;
    }

    public boolean removeShimBoard(String id) {
        VirtualNodeSession session = shimSessions.remove(id);
        if (session != null) {
            nodeOutputMirror.remove(session.getPrefix());
            session.close();
            nodeInventory.removeById(id);
            return true;
        }
        // also try by prefix-derived id
        if (id != null && id.startsWith("shim_")) {
            return false;
        }
        return false;
    }

    public JsonObject simulateShimChannel(String id, String hardwareId, String label, int state) {
        VirtualNodeSession session = shimSessions.get(id);
        if (session == null) {
            throw new IllegalArgumentException("Shim board not found: " + id);
        }
        if (hardwareId != null && !hardwareId.isBlank()) {
            session.simulateChannel(hardwareId, state);
        } else if (label != null) {
            session.simulateByLabel(label, state);
        } else {
            throw new IllegalArgumentException("hardwareId or label required");
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.add("channels", session.channelsJson());
        return out;
    }

    public JsonObject setNodePrefix(String id, String prefix) {
        String next = NodeSupport.normalizePrefix(prefix);
        if (!NodeSupport.isValidPrefix(next)) {
            throw new IllegalArgumentException("Invalid prefix: " + prefix);
        }
        NodeSession session = findNodeSession(id);
        if (session == null) {
            throw new IllegalArgumentException("Node not found: " + id);
        }
        NodeInventory.NodeRecord taken = nodeInventory.findByPrefix(next);
        if (taken != null && taken.id != null && !taken.id.equals(session.getId())) {
            throw new IllegalStateException("Prefix " + next + " is already used");
        }
        String previous = session.getPrefix();
        if (!next.equals(previous)) {
            session.sendCommand(NodeFlasher.prefixCommand(next));
            if (session instanceof UsbNodeSession) {
                ((UsbNodeSession) session).setPrefix(next);
            } else if (session instanceof VirtualNodeSession) {
                ((VirtualNodeSession) session).setPrefix(next);
            }
            if (previous != null && !previous.isBlank()) {
                nodeOutputMirror.remove(previous);
            }
        }
        NodeInventory.NodeRecord rec = nodeInventory.findById(session.getId());
        if (rec == null && previous != null) {
            rec = nodeInventory.findByPrefix(previous);
        }
        if (rec == null) {
            rec = new NodeInventory.NodeRecord();
            rec.id = session.getId();
            rec.boardType = session.getBoardType();
            rec.transport = session.getTransport();
            rec.endpoint = session.getEndpoint();
        }
        rec.prefix = next;
        nodeInventory.upsert(rec);
        nodeOutputMirror.syncConfig(next, rec.outputs != null ? rec.outputs : new JsonArray());
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("prefix", next);
        return out;
    }

    public JsonObject pushNodeConfig(String id, JsonObject config) {
        NodeSession session = findNodeSession(id);
        if (session == null) {
            throw new IllegalArgumentException("Node not found: " + id);
        }
        if (BoardProfile.NANO_AVR.equals(BoardProfile.normalize(session.getBoardType()))
                && !NodeSupport.TRANSPORT_SHIM.equals(session.getTransport())) {
            throw new IllegalStateException(
                    "This board cannot be dynamically configured. Build a new image and re-flash it.");
        }
        String line = "CFG " + config.toString();
        session.sendCommand(line);
        NodeInventory.NodeRecord rec = nodeInventory.findById(id);
        if (rec == null) {
            rec = nodeInventory.findByPrefix(session.getPrefix());
        }
        if (rec == null) {
            rec = new NodeInventory.NodeRecord();
            rec.id = session.getId();
            rec.prefix = session.getPrefix();
            rec.boardType = session.getBoardType();
            rec.transport = session.getTransport();
            rec.endpoint = session.getEndpoint();
        }
        rec.config = config.deepCopy();
        if (config.has("outputs") && config.get("outputs").isJsonArray()) {
            rec.outputs = config.getAsJsonArray("outputs");
        }
        if (config.has("pins") && config.get("pins").isJsonArray()) {
            rec.pins = config.getAsJsonArray("pins");
        }
        if (config.has("opticalDistricts") && config.get("opticalDistricts").isJsonArray()) {
            rec.opticalDistricts = config.getAsJsonArray("opticalDistricts");
        }
        nodeInventory.upsert(rec);
        if (rec.outputs != null) {
            nodeOutputMirror.syncConfig(session.getPrefix(), rec.outputs);
        } else {
            nodeOutputMirror.syncConfig(session.getPrefix(), new JsonArray());
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        return out;
    }

    public JsonObject sendNodeTest(String id, String command) {
        NodeSession session = findNodeSession(id);
        if (session == null) {
            throw new IllegalArgumentException("Node not found: " + id);
        }
        String line = command == null ? "" : command.trim();
        if (!line.toUpperCase().startsWith("TEST")) {
            line = "TEST " + line;
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("sent", line);
        if (line.toUpperCase(java.util.Locale.ROOT).startsWith("TEST SAMPLE")) {
            String reply = session.sendCommandAwaitReply(line, "SAMPLE", 600L);
            if (reply != null) {
                out.addProperty("reply", reply);
                parseSampleReply(reply, out);
            }
            return out;
        }
        session.sendCommand(line);
        nodeOutputMirror.onCommandLine(session.getPrefix(), line);
        return out;
    }

    /** Parse {@code SAMPLE A15=812 A0=1020 delta=208 pct=25} into JSON fields. */
    public static void parseSampleReply(String reply, JsonObject out) {
        if (reply == null || reply.isBlank()) {
            return;
        }
        String[] toks = reply.trim().split("\\s+");
        for (String tok : toks) {
            if (tok.equalsIgnoreCase("SAMPLE")) {
                continue;
            }
            int eq = tok.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = tok.substring(0, eq);
            String val = tok.substring(eq + 1);
            if ("delta".equalsIgnoreCase(key) || "pct".equalsIgnoreCase(key)) {
                try {
                    out.addProperty(key.toLowerCase(java.util.Locale.ROOT), Integer.parseInt(val));
                } catch (NumberFormatException ignored) {
                    out.addProperty(key.toLowerCase(java.util.Locale.ROOT), val);
                }
            } else {
                try {
                    out.addProperty(key.toUpperCase(java.util.Locale.ROOT), Integer.parseInt(val));
                } catch (NumberFormatException ignored) {
                    out.addProperty(key.toUpperCase(java.util.Locale.ROOT), val);
                }
            }
        }
    }

    /**
     * DCC-like fan-out: every connected node receives the accessory command line.
     */
    public void broadcastAccessoryToNodes(int address, boolean closed) {
        String state = closed ? "ON" : "OFF";
        String line = address + " " + state;
        // closed/Forward → ON; thrown/Reverse → OFF — mirror UI state for all boards
        nodeOutputMirror.onAccessory(address, closed);
        for (NodeSession session : allNodeSessions()) {
            try {
                session.sendCommand(line);
            } catch (Exception e) {
                System.err.println("Node broadcast failed on " + session.getId() + ": " + e.getMessage());
            }
        }
    }

    public void broadcastAccessoryOptionToNodes(int address, String optionValue) {
        String line = address + " " + (optionValue != null ? optionValue : "ON");
        ParsedWire wire = parseWireToken(optionValue);
        if (wire != null) {
            nodeOutputMirror.onAccessory(address, wire.on);
        }
        for (NodeSession session : allNodeSessions()) {
            try {
                session.sendCommand(line);
            } catch (Exception e) {
                System.err.println("Node broadcast failed on " + session.getId() + ": " + e.getMessage());
            }
        }
    }

    private static ParsedWire parseWireToken(String optionValue) {
        if (optionValue == null) {
            return new ParsedWire(true);
        }
        String t = optionValue.trim().toUpperCase();
        if ("ON".equals(t) || "CLOSED".equals(t) || "FORWARD".equals(t)) {
            return new ParsedWire(true);
        }
        if ("OFF".equals(t) || "THROWN".equals(t) || "REVERSE".equals(t)) {
            return new ParsedWire(false);
        }
        return null;
    }

    private static final class ParsedWire {
        final boolean on;

        ParsedWire(boolean on) {
            this.on = on;
        }
    }

    private List<NodeSession> allNodeSessions() {
        List<NodeSession> list = new ArrayList<>();
        list.addAll(nodeSessions.values());
        list.addAll(shimSessions.values());
        return list;
    }

    private NodeSession findNodeSession(String id) {
        if (id == null) {
            return null;
        }
        UsbNodeSession usb = nodeSessions.get(id);
        if (usb != null) {
            return usb;
        }
        return shimSessions.get(id);
    }

    public JsonObject bluetoothStatus() {
        return BluetoothNodeSupport.statusJson();
    }

    public JsonArray bluetoothScan(long timeoutSec) {
        return BluetoothNodeSupport.scanDevices(timeoutSec);
    }

    public JsonObject bluetoothPair(String address, String prefix) {
        return BluetoothNodeSupport.pairAndBind(address, prefix);
    }

    public void restoreShimBoardsFromInventory() {
        for (NodeInventory.NodeRecord rec : nodeInventory.list()) {
            if (!NodeSupport.TRANSPORT_SHIM.equalsIgnoreCase(rec.transport)) {
                continue;
            }
            if (shimSessions.containsKey(rec.id)) {
                continue;
            }
            try {
                VirtualNodeSession session = new VirtualNodeSession(rec.prefix, rec.boardType, () -> sensorHardwarePublisher);
                session.start();
                if (rec.config != null) {
                    session.sendCommand("CFG " + rec.config);
                }
                shimSessions.put(session.getId(), session);
            } catch (Exception e) {
                System.err.println("Failed to restore shim " + rec.id + ": " + e.getMessage());
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

        for (UsbNodeSession session : nodeSessions.values()) {
            try {
                session.close();
            } catch (Exception ignore) {
            }
        }
        nodeSessions.clear();
        for (VirtualNodeSession shim : shimSessions.values()) {
            try {
                shim.close();
            } catch (Exception ignore) {
            }
        }
        shimSessions.clear();
        releasedSensorPorts.clear();
        nodeFlasher.shutdown();

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
