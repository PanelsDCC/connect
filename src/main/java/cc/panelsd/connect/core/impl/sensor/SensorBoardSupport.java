package cc.panelsd.connect.core.impl.sensor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shared constants and validation for PanelsDCC Sensor Boards.
 */
public final class SensorBoardSupport {

    public static final String TRANSPORT_USB = "usb";
    public static final String TRANSPORT_WIFI = "wifi";
    public static final String TRANSPORT_BLE = "ble";

    public static final String STATUS_LISTENING = "listening";
    public static final String STATUS_RELEASED = "released";
    public static final String STATUS_FLASHING = "flashing";

    public static final String FQBN = "arduino:avr:nano";

    /** Packaged sketch directory on installed systems. */
    public static final String DEFAULT_SKETCH_DIR =
            "/usr/share/panelsdcc-connect/firmware/panelsdcc-sensor-board";

    public static final String DEFAULT_CLI =
            "/usr/lib/panelsdcc-connect/bin/arduino-cli";

    public static final String DEFAULT_ARDUINO_DATA =
            "/var/lib/panelsdcc-connect/arduino";

    /** Analog optical channels A0..A6 (indexes 0..6). */
    public static final int ANALOG_CHANNEL_COUNT = 7;

    /** Digital INPUT_PULLUP pins (skip D0/D1 UART and D13 built-in LED). */
    public static final int[] DIGITAL_PINS = { 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 };

    /** Spreadsheet-style columns: A..Z, AA..ZZ */
    private static final Pattern PREFIX = Pattern.compile("^[A-Z]{1,2}$");

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
        return prefix + channel;
    }

    public static String digitalHardwareId(String prefix, int pin) {
        return prefix + "D" + pin;
    }

    /**
     * Build the fixed channel list for a board prefix (states filled by caller).
     * Each entry: id, label, kind (analog|digital).
     */
    public static List<ChannelDef> channelDefs(String prefix) {
        List<ChannelDef> list = new ArrayList<>();
        if (prefix == null || prefix.isEmpty()) {
            return list;
        }
        for (int i = 0; i < ANALOG_CHANNEL_COUNT; i++) {
            list.add(new ChannelDef(analogHardwareId(prefix, i), "A" + i, "analog"));
        }
        for (int pin : DIGITAL_PINS) {
            list.add(new ChannelDef(digitalHardwareId(prefix, pin), "D" + pin, "digital"));
        }
        return list;
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
}
