package cc.panelsd.connect.core.impl.node;

import cc.panelsd.connect.core.DeviceDiscoveryService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ports offered on the Flash USB form.
 * <p>
 * Official boards are labelled from USB vendor / product / description when
 * that identity is unambiguous. Generic UART bridges (CH340, FTDI, CP210x) are
 * always listed too — the user must pick the right port. Connect does not hide
 * a port because it might be a command station; flash shows a warning instead.
 * Chip identity for Auto-detect is read later with avrdude / esptool.
 */
public final class FlashPortScanner {

    public FlashPortScanner() {
    }

    /** Kept for callers that previously injected a probe; listing no longer opens ports. */
    public FlashPortScanner(Object ignoredProbe) {
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
     * @param commandStationPorts ignored — flashing is the user's choice; UI warns
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
            out.add(new ListedPort(raw.name, NodeFlasher.DETECT, uartLabel(raw)));
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
     * CH340 and similar bridges stay null — the chip is read at flash time.
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

    /** ROM download mode. The running Nano ESP32 uses a different USB id and a longer port name. */
    static boolean isEspRomBootloader(int vendorId, int productId) {
        return (vendorId & 0xFFFF) == 0x303A && (productId & 0xFFFF) == 0x1001;
    }

    static boolean isUartBridge(int vendorId) {
        int vid = vendorId & 0xFFFF;
        return vid == 0x1A86 || vid == 0x0403 || vid == 0x10C4;
    }

    static String uartLabel(RawPort raw) {
        int vid = raw.vendorId & 0xFFFF;
        String chip = vid == 0x1A86 ? "CH340"
                : vid == 0x0403 ? "FTDI"
                : vid == 0x10C4 ? "CP210x"
                : "USB serial";
        return chip + " — choose carefully";
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
}
