package cc.panelsd.connect.core.impl.node;

import cc.panelsd.connect.core.DeviceDiscoveryService;
import cc.panelsd.connect.core.UsbSerialProbe;
import cc.panelsd.connect.core.UsbSerialProbe.DeviceType;
import cc.panelsd.connect.core.UsbSerialProbe.ProbeResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Ports worth offering on the Flash USB form.
 * <p>
 * Official boards are named from the USB vendor, product, and description.
 * Generic UART bridges (CH340, FTDI, CP210x) are asked {@code ID?} at 9600,
 * off the request thread, and only listed when they answer as a node.
 * esptool and avrdude are not used here: both reset the board and, on a
 * Nano ESP32, drop the USB port.
 */
public final class FlashPortScanner {

    private static final long NODE_TTL_MS = 15000L;
    private static final long MISS_TTL_MS = 20000L;
    private static final long BUSY_TTL_MS = 2000L;

    private final NodeProbe probe;
    private final Executor executor;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();

    public FlashPortScanner(UsbSerialProbe probe) {
        this(probe::verifyNode, daemonExecutor());
    }

    FlashPortScanner(NodeProbe probe, Executor executor) {
        this.probe = probe;
        this.executor = executor;
    }

    @FunctionalInterface
    interface NodeProbe {
        ProbeResult verify(String portName);
    }

    public static final class ListedPort {
        public final String port;
        public final String boardType;
        public final String label;

        ListedPort(String port, String boardType, String label) {
            this.port = port;
            this.boardType = boardType;
            this.label = label;
        }
    }

    public static final class RawPort {
        public final String name;
        public final int vendorId;
        public final int productId;
        public final String description;

        public RawPort(String name, int vendorId, int productId, String description) {
            this.name = name;
            this.vendorId = vendorId;
            this.productId = productId;
            this.description = description;
        }
    }

    /**
     * @param sessionTypes board type already known because a node session holds the port
     * @param commandStationPorts ports Connect is using as a command station; not probed
     */
    public List<ListedPort> list(List<RawPort> ports, Map<String, String> sessionTypes, Set<String> commandStationPorts) {
        List<ListedPort> out = new ArrayList<>();
        if (ports == null) {
            return out;
        }
        for (RawPort raw : ports) {
            if (raw == null || raw.name == null || raw.name.isBlank()) {
                continue;
            }
            if (isNoisePort(raw.name) || DeviceDiscoveryService.isMacDialinTwin(raw.name)) {
                continue;
            }
            String bare = NodeSupport.barePort(raw.name);
            String sessionType = sessionTypes == null ? null : sessionTypes.get(bare);
            if (BoardProfile.isValid(sessionType)) {
                out.add(listed(raw.name, sessionType));
                continue;
            }
            String usb = boardTypeForIdentity(raw.vendorId, raw.productId, raw.description);
            if (usb != null) {
                out.add(listed(raw.name, usb));
                continue;
            }
            if (isEspRomBootloader(raw.vendorId, raw.productId)) {
                out.add(new ListedPort(raw.name, BoardProfile.NANO_ESP32, "ESP32-S3 bootloader"));
                continue;
            }
            if (!isUartBridge(raw.vendorId)) {
                continue;
            }
            if (skipPort(commandStationPorts, bare)) {
                continue;
            }
            ProbeResult known = fresh(bare);
            if (known == null) {
                schedule(raw.name, bare);
                known = fresh(bare);
            }
            if (known != null && known.type == DeviceType.NODE && BoardProfile.isValid(known.boardType)) {
                out.add(listed(raw.name, known.boardType));
            }
        }
        return out;
    }

    static boolean isNoisePort(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("debug-console")
                || n.contains("wlan-debug")
                || n.contains("bluetooth");
    }

    /**
     * Board id when the USB identity is one we ship an image for, otherwise null.
     * CH340 and similar bridges stay null so a command station is not labelled an Arduino.
     */
    static String boardTypeForIdentity(int vendorId, int productId, String text) {
        int vid = vendorId & 0xFFFF;
        int pid = productId & 0xFFFF;
        String blob = text == null ? "" : text.toLowerCase(Locale.ROOT);
        boolean arduino = vid == 0x2341 || vid == 0x2A03;
        if (blob.contains("nano esp32") || (arduino && pid == 0x0070)) {
            return BoardProfile.NANO_ESP32;
        }
        if (blob.contains("mega") && (arduino || blob.contains("arduino"))) {
            return BoardProfile.MEGA_AVR;
        }
        // Official Mega 2560 R3 is 0x2341:0x0042. Uno R3 is 0x0043 and is not a node image.
        if (arduino && (pid == 0x0042 || pid == 0x0010 || pid == 0x003F) && !blob.contains("uno")) {
            return BoardProfile.MEGA_AVR;
        }
        if (blob.contains("arduino nano") && !blob.contains("esp")) {
            return BoardProfile.NANO_AVR;
        }
        return null;
    }

    private static boolean skipPort(Set<String> skip, String bare) {
        if (skip == null || bare == null) {
            return false;
        }
        if (skip.contains(bare)) {
            return true;
        }
        String key = NodeSupport.serialDeviceKey(bare);
        return key != null && skip.contains(key);
    }

    /** ROM download mode. The running Nano ESP32 uses a different USB id and a longer port name. */
    static boolean isEspRomBootloader(int vendorId, int productId) {
        return (vendorId & 0xFFFF) == 0x303A && (productId & 0xFFFF) == 0x1001;
    }

    static boolean isUartBridge(int vendorId) {
        int vid = vendorId & 0xFFFF;
        return vid == 0x1A86 || vid == 0x0403 || vid == 0x10C4;
    }

    public static List<RawPort> readLivePorts() {
        List<RawPort> ports = new ArrayList<>();
        try {
            Class<?> cls = Class.forName("com.fazecast.jSerialComm.SerialPort");
            Object[] commPorts = (Object[]) cls.getMethod("getCommPorts").invoke(null);
            if (commPorts == null) {
                return ports;
            }
            for (Object port : commPorts) {
                try {
                    String name = (String) port.getClass().getMethod("getSystemPortName").invoke(port);
                    if (!isPresent(name)) {
                        continue;
                    }
                    int vendor = (Integer) port.getClass().getMethod("getVendorID").invoke(port);
                    int product = (Integer) port.getClass().getMethod("getProductID").invoke(port);
                    String descriptive = stringOrEmpty(port, "getDescriptivePortName");
                    String productName = stringOrEmpty(port, "getPortDescription");
                    String description = (descriptive + " " + productName).trim();
                    ports.add(new RawPort(name, vendor, product, description));
                } catch (Exception ignore) {
                    // skip a port we cannot describe
                }
            }
        } catch (Exception e) {
            System.err.println("Flash port scan failed: " + e.getMessage());
        }
        return ports;
    }

    private static boolean isPresent(String portName) {
        if (portName == null || portName.isEmpty()) {
            return false;
        }
        if (portName.toUpperCase(Locale.ROOT).startsWith("COM")) {
            return true;
        }
        Path devPath = Paths.get("/dev", portName);
        return Files.exists(devPath);
    }

    private static String stringOrEmpty(Object port, String method) {
        try {
            Object value = port.getClass().getMethod(method).invoke(port);
            return value == null ? "" : value.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static ListedPort listed(String port, String boardType) {
        BoardProfile profile = BoardProfile.get(boardType);
        String label = profile == null ? boardType : profile.label;
        return new ListedPort(port, boardType, label);
    }

    private ProbeResult fresh(String bare) {
        Cached cached = cache.get(bare);
        if (cached == null) {
            return null;
        }
        long age = System.currentTimeMillis() - cached.at;
        long ttl = cached.result.portBusy
                ? BUSY_TTL_MS
                : (cached.result.type == DeviceType.NODE ? NODE_TTL_MS : MISS_TTL_MS);
        return age <= ttl ? cached.result : null;
    }

    private void schedule(String portName, String bare) {
        if (!inflight.add(bare)) {
            return;
        }
        executor.execute(() -> {
            try {
                ProbeResult result = probe.verify(portName);
                if (result == null) {
                    result = ProbeResult.unknown();
                }
                cache.put(bare, new Cached(result, System.currentTimeMillis()));
            } catch (Exception e) {
                cache.put(bare, new Cached(ProbeResult.unknown(), System.currentTimeMillis()));
            } finally {
                inflight.remove(bare);
            }
        });
    }

    private static Executor daemonExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "flash-port-probe");
            t.setDaemon(true);
            return t;
        });
    }

    private static final class Cached {
        final ProbeResult result;
        final long at;

        Cached(ProbeResult result, long at) {
            this.result = result;
            this.at = at;
        }
    }
}
