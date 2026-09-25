package cc.panelsd.connect.core;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Service for auto-discovering DCC controllers via USB device detection.
 * Uses a configuration file to map vendor/product IDs to controller types and settings.
 * Ambiguous USB-UART bridges (e.g. CH340) are flagged {@code needsProbe} so callers
 * classify them over the serial port before claiming a command-station type.
 */
public class DeviceDiscoveryService {

    public static final String SYSTEM_TYPE_USB_SERIAL_CANDIDATE = "usb-serial-candidate";
    public static final String SYSTEM_TYPE_SENSOR_BOARD = "panelsdcc-sensor-board";
    public static final String SYSTEM_TYPE_DCCPP = "dccpp";

    private static final String CONFIG_FILE = "device-discovery-config.json";
    private static final String USER_CONFIG_FILE = System.getProperty("user.home") + "/.dcc-io/device-discovery-config.json";
    private static final Path BY_PATH_DIR = Paths.get("/dev/serial/by-path");

    private final List<DeviceConfig> deviceConfigs;

    public DeviceDiscoveryService() {
        this.deviceConfigs = loadConfig();
    }

    /**
     * Represents a device configuration from the config file.
     */
    public static class DeviceConfig {
        public String name;
        public String vendorId;
        public String productId;
        public String systemType;
        public String defaultPort;
        public boolean needsProbe;
        public Map<String, String> config = new HashMap<>();
        public List<String> descriptionPatterns = new ArrayList<>();
    }

    /**
     * Represents a detected device.
     */
    public static class DetectedDevice {
        public String port;
        public String systemType;
        public String description;
        public String vendorId;
        public String productId;
        public Map<String, String> config = new HashMap<>();
        public String name;
        public boolean needsProbe;
        /** Stable Linux USB path basename under {@code /dev/serial/by-path}, when available. */
        public String byPath;
    }

    /**
     * Load device configuration from file.
     * First tries user config file, then falls back to classpath resource.
     */
    private List<DeviceConfig> loadConfig() {
        List<DeviceConfig> configs = new ArrayList<>();
        Gson gson = new Gson();

        Path userConfigPath = Paths.get(USER_CONFIG_FILE);
        if (Files.exists(userConfigPath)) {
            try (InputStreamReader reader = new InputStreamReader(
                    Files.newInputStream(userConfigPath), StandardCharsets.UTF_8)) {
                JsonObject root = gson.fromJson(reader, JsonObject.class);
                configs.addAll(parseConfig(root));
            } catch (Exception e) {
                System.err.println("Error loading user config file: " + e.getMessage());
            }
        }

        if (configs.isEmpty()) {
            try (InputStream is = DeviceDiscoveryService.class.getClassLoader().getResourceAsStream(CONFIG_FILE);
                 InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                JsonObject root = gson.fromJson(reader, JsonObject.class);
                configs.addAll(parseConfig(root));
            } catch (Exception e) {
                System.err.println("Error loading default config file: " + e.getMessage());
            }
        }

        return configs;
    }

    private List<DeviceConfig> parseConfig(JsonObject root) {
        List<DeviceConfig> configs = new ArrayList<>();
        JsonArray devices = root.getAsJsonArray("devices");

        for (JsonElement elem : devices) {
            JsonObject device = elem.getAsJsonObject();
            DeviceConfig config = new DeviceConfig();
            config.name = device.get("name").getAsString();
            config.vendorId = device.get("vendorId").getAsString();
            config.productId = device.get("productId").getAsString();
            config.systemType = device.get("systemType").getAsString();
            config.needsProbe = device.has("needsProbe") && device.get("needsProbe").getAsBoolean();
            if (device.has("defaultPort") && !device.get("defaultPort").isJsonNull()) {
                config.defaultPort = device.get("defaultPort").getAsString();
            }

            if (device.has("config")) {
                JsonObject configObj = device.getAsJsonObject("config");
                for (Map.Entry<String, JsonElement> entry : configObj.entrySet()) {
                    config.config.put(entry.getKey(), entry.getValue().getAsString());
                }
            }

            if (device.has("descriptionPatterns")) {
                JsonArray patterns = device.getAsJsonArray("descriptionPatterns");
                for (JsonElement pattern : patterns) {
                    config.descriptionPatterns.add(pattern.getAsString().toLowerCase());
                }
            }

            configs.add(config);
        }

        return configs;
    }

    /**
     * Discover connected devices by scanning serial ports and matching against config.
     * For identical VID/PID entries, prefers the {@code needsProbe} candidate so CH340
     * is never auto-claimed as a command station without a serial probe.
     */
    public List<DetectedDevice> discoverDevices() {
        List<DetectedDevice> detected = new ArrayList<>();

        try {
            Class<?> jSerialCommClass = Class.forName("com.fazecast.jSerialComm.SerialPort");
            java.lang.reflect.Method getCommPorts = jSerialCommClass.getMethod("getCommPorts");
            Object[] commPorts = (Object[]) getCommPorts.invoke(null);

            for (Object port : commPorts) {
                try {
                    java.lang.reflect.Method getSystemPortName = port.getClass().getMethod("getSystemPortName");
                    java.lang.reflect.Method getVendorID = port.getClass().getMethod("getVendorID");
                    java.lang.reflect.Method getProductID = port.getClass().getMethod("getProductID");
                    java.lang.reflect.Method getDescriptivePortName = port.getClass().getMethod("getDescriptivePortName");

                    String portName = (String) getSystemPortName.invoke(port);
                    if (!isPortPresent(portName)) {
                        continue;
                    }
                    int vendorId = ((Integer) getVendorID.invoke(port));
                    int productId = ((Integer) getProductID.invoke(port));
                    String description = (String) getDescriptivePortName.invoke(port);

                    DeviceConfig match = findMatchingConfig(vendorId, productId, description, portName);
                    if (match != null) {
                        DetectedDevice device = new DetectedDevice();
                        device.port = portName;
                        device.systemType = match.systemType;
                        device.description = description != null ? description : portName;
                        device.vendorId = String.format("0x%04x", vendorId);
                        device.productId = String.format("0x%04x", productId);
                        device.config = new HashMap<>(match.config);
                        device.name = match.name;
                        device.needsProbe = match.needsProbe
                                || SYSTEM_TYPE_USB_SERIAL_CANDIDATE.equals(match.systemType);
                        device.byPath = resolveByPath(portName);
                        detected.add(device);
                    }
                } catch (Exception e) {
                    // Skip ports without USB info
                }
            }
        } catch (Exception e) {
            // JSerialComm not available or error
        }

        return detected;
    }

    /**
     * Best-effort check to see if the OS still exposes the port (helps when jSerialComm caches).
     */
    public boolean isPortPresent(String portName) {
        if (portName == null || portName.isEmpty()) {
            return false;
        }
        Path devPath = Paths.get("/dev", portName);
        if (Files.exists(devPath)) {
            return true;
        }
        return portName.toUpperCase().startsWith("COM");
    }

    /**
     * Resolve the basename under {@code /dev/serial/by-path} that points at this tty, if any.
     */
    public String resolveByPath(String portName) {
        if (portName == null || portName.isEmpty() || !Files.isDirectory(BY_PATH_DIR)) {
            return null;
        }
        String bare = portName;
        if (bare.startsWith("/dev/")) {
            bare = bare.substring(5);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(BY_PATH_DIR)) {
            for (Path link : stream) {
                try {
                    Path target = Files.readSymbolicLink(link);
                    Path resolved = link.getParent().resolve(target).normalize();
                    if (bare.equals(resolved.getFileName().toString())
                            || bare.equals(target.getFileName().toString())) {
                        return link.getFileName().toString();
                    }
                } catch (Exception ignore) {
                    // skip broken links
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /**
     * Find matching device config for given vendor/product IDs and description.
     * Prefer exact VID:PID matches that need probing (ambiguous UART bridges) over
     * a second same-VID entry used only for post-probe metadata.
     */
    private DeviceConfig findMatchingConfig(int vendorId, int productId, String description, String portName) {
        String vendorIdStr = String.format("0x%04x", vendorId);
        String productIdStr = String.format("0x%04x", productId);
        String descLower = description != null ? description.toLowerCase() : "";

        DeviceConfig vidPidMatch = null;
        DeviceConfig vidPidProbeMatch = null;
        for (DeviceConfig config : deviceConfigs) {
            if (config.vendorId.equalsIgnoreCase(vendorIdStr)
                    && config.productId.equalsIgnoreCase(productIdStr)) {
                if (config.needsProbe || SYSTEM_TYPE_USB_SERIAL_CANDIDATE.equals(config.systemType)) {
                    vidPidProbeMatch = config;
                } else if (vidPidMatch == null) {
                    vidPidMatch = config;
                }
            }
        }
        if (vidPidProbeMatch != null) {
            return vidPidProbeMatch;
        }
        if (vidPidMatch != null) {
            return vidPidMatch;
        }

        for (DeviceConfig config : deviceConfigs) {
            for (String pattern : config.descriptionPatterns) {
                if (descLower.contains(pattern)) {
                    return config;
                }
            }
        }

        return null;
    }

    /**
     * Look up display metadata for a known system type (e.g. after probe classification).
     */
    public DeviceConfig findConfigBySystemType(String systemType) {
        if (systemType == null) {
            return null;
        }
        for (DeviceConfig config : deviceConfigs) {
            if (systemType.equals(config.systemType) && !config.needsProbe
                    && !SYSTEM_TYPE_USB_SERIAL_CANDIDATE.equals(config.systemType)) {
                return config;
            }
        }
        for (DeviceConfig config : deviceConfigs) {
            if (systemType.equals(config.systemType)) {
                return config;
            }
        }
        return null;
    }

    /**
     * Get all device configurations (for UI display).
     */
    public List<DeviceConfig> getDeviceConfigs() {
        return new ArrayList<>(deviceConfigs);
    }
}
