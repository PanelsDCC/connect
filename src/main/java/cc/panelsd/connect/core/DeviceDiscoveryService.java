package cc.panelsd.connect.core;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Service for auto-discovering DCC controllers via USB device detection.
 * Uses a configuration file to map vendor/product IDs to controller types and settings.
 */
public class DeviceDiscoveryService {
    
    private static final String CONFIG_FILE = "device-discovery-config.json";
    private static final String USER_CONFIG_FILE = System.getProperty("user.home") + "/.dcc-io/device-discovery-config.json";
    
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
    }
    
    /**
     * Load device configuration from file.
     * First tries user config file, then falls back to classpath resource.
     */
    private List<DeviceConfig> loadConfig() {
        List<DeviceConfig> configs = new ArrayList<>();
        Gson gson = new Gson();
        
        // Try user config file first
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
        
        // Fall back to classpath resource
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
     */
    public List<DetectedDevice> discoverDevices() {
        List<DetectedDevice> detected = new ArrayList<>();
        
        try {
            // Use JSerialComm to enumerate ports
            Class<?> jSerialCommClass = Class.forName("com.fazecast.jSerialComm.SerialPort");
            java.lang.reflect.Method getCommPorts = jSerialCommClass.getMethod("getCommPorts");
            Object[] commPorts = (Object[]) getCommPorts.invoke(null);
            List<String> availablePorts = new ArrayList<>();
            
            for (Object port : commPorts) {
                try {
                    java.lang.reflect.Method getSystemPortName = port.getClass().getMethod("getSystemPortName");
                    java.lang.reflect.Method getVendorID = port.getClass().getMethod("getVendorID");
                    java.lang.reflect.Method getProductID = port.getClass().getMethod("getProductID");
                    java.lang.reflect.Method getDescriptivePortName = port.getClass().getMethod("getDescriptivePortName");
                    
                    String portName = (String) getSystemPortName.invoke(port);
                    // Guard against stale devices that jSerialComm still reports after unplug
                    if (!isPortPresent(portName)) {
                        continue;
                    }
                    int vendorId = ((Integer) getVendorID.invoke(port));
                    int productId = ((Integer) getProductID.invoke(port));
                    String description = (String) getDescriptivePortName.invoke(port);
                    availablePorts.add(description != null ? description + " (" + portName + ")" : portName);
                    
                    // Match against config
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
        // Linux/macOS style device paths
        java.nio.file.Path devPath = java.nio.file.Paths.get("/dev", portName);
        if (java.nio.file.Files.exists(devPath)) {
            return true;
        }
        // Windows style (COM) ports don't map to /dev, so we trust enumeration there
        return portName.toUpperCase().startsWith("COM");
    }
    
    /**
     * Find matching device config for given vendor/product IDs and description.
     */
    private DeviceConfig findMatchingConfig(int vendorId, int productId, String description, String portName) {
        String vendorIdStr = String.format("0x%04x", vendorId);
        String productIdStr = String.format("0x%04x", productId);
        String descLower = description != null ? description.toLowerCase() : "";
        
        // First try exact VID:PID match
        for (DeviceConfig config : deviceConfigs) {
            if (config.vendorId.equalsIgnoreCase(vendorIdStr) && 
                config.productId.equalsIgnoreCase(productIdStr)) {
                return config;
            }
        }
        
        // Then try description pattern matching
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
     * Get all device configurations (for UI display).
     */
    public List<DeviceConfig> getDeviceConfigs() {
        return new ArrayList<>(deviceConfigs);
    }
}

