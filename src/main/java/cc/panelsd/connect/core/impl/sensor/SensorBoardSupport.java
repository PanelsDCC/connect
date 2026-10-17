package cc.panelsd.connect.core.impl.sensor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared constants and validation for PanelsDCC Sensor / Node boards.
 */
public final class SensorBoardSupport {

    public static final String TRANSPORT_USB = "usb";
    public static final String TRANSPORT_WIFI = "wifi";
    public static final String TRANSPORT_BLE = "ble";
    public static final String TRANSPORT_SHIM = "shim";

    public static final String STATUS_LISTENING = "listening";
    public static final String STATUS_RELEASED = "released";
    public static final String STATUS_FLASHING = "flashing";

    /** @deprecated use {@link BoardProfile#fqbn} via {@link BoardProfile#get(String)} */
    @Deprecated
    public static final String FQBN = "arduino:avr:nano";

    /** Packaged firmware root on installed systems. */
    public static final String DEFAULT_FIRMWARE_ROOT =
            "/usr/share/panelsdcc-connect/firmware";

    /** Legacy Nano sketch path (kept for packaging / docs). */
    public static final String DEFAULT_SKETCH_DIR =
            DEFAULT_FIRMWARE_ROOT + "/panelsdcc-sensor-board";

    public static final String DEFAULT_CLI =
            "/usr/lib/panelsdcc-connect/bin/arduino-cli";

    public static final String DEFAULT_ARDUINO_DATA =
            "/var/lib/panelsdcc-connect/arduino";

    /** @deprecated prefer {@link BoardProfile} defaults */
    @Deprecated
    public static final int ANALOG_CHANNEL_COUNT = 7;

    /** @deprecated prefer {@link BoardProfile} defaults */
    @Deprecated
    public static final int[] DIGITAL_PINS = { 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 };

    private static final Pattern PREFIX = Pattern.compile("^[A-Z]{1,2}$");
    /** Analogue (A), digital (D), or virtual feedback (V). */
    private static final Pattern HARDWARE_ID = Pattern.compile("^[A-Z]{1,2}-[ADV]\\d+$");
    /** Max virtual feedback channels per board prefix. */
    public static final int MAX_VIRTUAL_CHANNELS = 12;

    private SensorBoardSupport() {
    }

    public static boolean isValidPrefix(String prefix) {
        return prefix != null && PREFIX.matcher(prefix).matches();
    }

    public static String normalizePrefix(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.trim().toUpperCase();
    }

    public static String analogHardwareId(String prefix, int channel) {
        return prefix + "-A" + channel;
    }

    public static String digitalHardwareId(String prefix, int pin) {
        return prefix + "-D" + pin;
    }

    /** Virtual feedback channel id, e.g. {@code A-V3}. */
    public static String virtualHardwareId(String prefix, int channel) {
        return prefix + "-V" + channel;
    }

    /**
     * Normalise a short virtual label ({@code V3} / {@code 3}) or full id to {@code Vn}.
     * @return {@code Vn} or null if invalid
     */
    public static String normalizeVirtualLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.matches("^[A-Z]{1,2}-V\\d+$")) {
            s = s.substring(s.indexOf('-') + 1);
        }
        if (s.matches("^\\d+$")) {
            s = "V" + s;
        }
        if (!s.matches("^V\\d+$")) {
            return null;
        }
        int n = Integer.parseInt(s.substring(1));
        if (n < 1 || n > MAX_VIRTUAL_CHANNELS) {
            return null;
        }
        return "V" + n;
    }

    public static boolean isValidHardwareId(String id) {
        return id != null && HARDWARE_ID.matcher(id).matches();
    }

    /**
     * Virtual feedback channels referenced by level-crossing outputs on a board.
     */
    public static List<ChannelDef> virtualChannelDefs(String prefix, JsonArray outputs) {
        Set<Integer> nums = new LinkedHashSet<>();
        if (outputs != null) {
            for (JsonElement el : outputs) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                if (!o.has("type") || !"level_crossing".equals(o.get("type").getAsString())) {
                    continue;
                }
                if (!o.has("params") || !o.get("params").isJsonObject()) {
                    continue;
                }
                JsonObject params = o.getAsJsonObject("params");
                if (!params.has("feedbackVirtual")) {
                    continue;
                }
                String label = normalizeVirtualLabel(params.get("feedbackVirtual").getAsString());
                if (label != null) {
                    nums.add(Integer.parseInt(label.substring(1)));
                }
            }
        }
        List<ChannelDef> list = new ArrayList<>();
        String p = normalizePrefix(prefix);
        if (p == null) {
            return list;
        }
        for (Integer n : nums) {
            list.add(new ChannelDef(virtualHardwareId(p, n), "V" + n, "virtual"));
        }
        return list;
    }

    /** First free Vn (1–12) not present in {@code usedLabels} ({@code V1}…); null if exhausted. */
    public static String nextFreeVirtualLabel(Set<String> usedLabels) {
        Set<String> used = usedLabels != null ? usedLabels : Set.of();
        for (int n = 1; n <= MAX_VIRTUAL_CHANNELS; n++) {
            String label = "V" + n;
            if (!used.contains(label)) {
                return label;
            }
        }
        return null;
    }

    /**
     * Default Nano channel list (back-compat). Prefer
     * {@link #channelDefs(String, String)}.
     */
    public static List<ChannelDef> channelDefs(String prefix) {
        return channelDefs(prefix, BoardProfile.NANO_AVR);
    }

    public static List<ChannelDef> channelDefs(String prefix, String boardType) {
        BoardProfile profile = BoardProfile.get(boardType);
        if (profile == null) {
            profile = BoardProfile.require(BoardProfile.NANO_AVR);
        }
        return profile.defaultChannelDefs(prefix);
    }

    /**
     * Resolve sketch source directory for a board type (dev tree or packaged).
     */
    public static Path resolveSketchDir(String boardType) {
        BoardProfile profile = BoardProfile.require(BoardProfile.normalize(boardType));
        List<Path> candidates = new ArrayList<>();
        // Dev: connect/firmware/<sketchDir>
        Path cwd = Paths.get("").toAbsolutePath();
        candidates.add(cwd.resolve("firmware").resolve(profile.sketchDirName));
        candidates.add(cwd.resolve("connect/firmware").resolve(profile.sketchDirName));
        // Packaged
        candidates.add(Paths.get(DEFAULT_FIRMWARE_ROOT, profile.sketchDirName));
        // Legacy absolute Nano path
        if (BoardProfile.NANO_AVR.equals(profile.id)) {
            candidates.add(Paths.get(DEFAULT_SKETCH_DIR));
        }
        for (Path p : candidates) {
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return Paths.get(DEFAULT_FIRMWARE_ROOT, profile.sketchDirName);
    }

    public static final class ChannelDef {
        public final String id;
        public final String label;
        public final String kind;

        public ChannelDef(String id, String label, String kind) {
            this.id = id;
            this.label = label;
            this.kind = kind;
        }
    }

    public static String sessionIdForPort(String portName) {
        String bare = portName == null ? "" : portName;
        if (bare.startsWith("/dev/")) {
            bare = bare.substring(5);
        }
        return "sensor_" + bare.replace("/", "_").replace("\\", "_");
    }

    public static String sessionIdForShim(String prefix) {
        return "shim_" + normalizePrefix(prefix);
    }

    public static String barePort(String portName) {
        if (portName == null) {
            return null;
        }
        return portName.startsWith("/dev/") ? portName.substring(5) : portName;
    }

    public static String devicePath(String portName) {
        String bare = barePort(portName);
        if (bare == null) {
            return null;
        }
        return bare.startsWith("COM") ? bare : "/dev/" + bare;
    }

    /** Next free spreadsheet prefix given already-used prefixes. */
    public static String nextFreePrefix(Iterable<String> used) {
        java.util.HashSet<String> set = new java.util.HashSet<>();
        if (used != null) {
            for (String u : used) {
                String n = normalizePrefix(u);
                if (n != null) {
                    set.add(n);
                }
            }
        }
        for (char c = 'A'; c <= 'Z'; c++) {
            String p = String.valueOf(c);
            if (!set.contains(p)) {
                return p;
            }
        }
        for (char a = 'A'; a <= 'Z'; a++) {
            for (char b = 'A'; b <= 'Z'; b++) {
                String p = "" + a + b;
                if (!set.contains(p)) {
                    return p;
                }
            }
        }
        return "ZZ";
    }
}
