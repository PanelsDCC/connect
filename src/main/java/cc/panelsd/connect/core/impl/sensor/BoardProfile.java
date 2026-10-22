package cc.panelsd.connect.core.impl.sensor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Silk-screen pin inventory and flash metadata for a node board type.
 * UX labels always match PCB printing ({@code D6}, {@code A3}); {@code mcuPin}
 * is firmware-only.
 */
public final class BoardProfile {

    public static final String NANO_AVR = "nano-avr";
    public static final String MEGA_AVR = "mega-avr";
    public static final String NANO_ESP32 = "nano-esp32";
    public static final String MEGA_ESP32 = "mega-esp32";

    private static final Map<String, BoardProfile> BY_ID = new LinkedHashMap<>();

    static {
        register(nanoAvr());
        register(megaAvr());
        register(nanoEsp32());
        register(megaEsp32());
    }

    public final String id;
    public final String label;
    public final String fqbn;
    /** Directory name under packaged firmware/ (and connect/firmware/). */
    public final String sketchDirName;
    public final String defaultTransport;
    public final List<PinDef> pins;
    /** Silk labels used as default optical inputs (excl. ambient ref). */
    public final List<String> defaultOpticalLabels;
    /** Silk label of ambient reference analogue pin (never reported). */
    public final String ambientRefLabel;
    /** Silk labels used as default digital inputs. */
    public final List<String> defaultDigitalLabels;

    private BoardProfile(
            String id,
            String label,
            String fqbn,
            String sketchDirName,
            String defaultTransport,
            List<PinDef> pins,
            List<String> defaultOpticalLabels,
            String ambientRefLabel,
            List<String> defaultDigitalLabels) {
        this.id = id;
        this.label = label;
        this.fqbn = fqbn;
        this.sketchDirName = sketchDirName;
        this.defaultTransport = defaultTransport;
        this.pins = Collections.unmodifiableList(pins);
        this.defaultOpticalLabels = Collections.unmodifiableList(defaultOpticalLabels);
        this.ambientRefLabel = ambientRefLabel;
        this.defaultDigitalLabels = Collections.unmodifiableList(defaultDigitalLabels);
    }

    private static void register(BoardProfile p) {
        BY_ID.put(p.id, p);
    }

    public static BoardProfile get(String id) {
        if (id == null) {
            return null;
        }
        return BY_ID.get(id.trim().toLowerCase(Locale.ROOT));
    }

    public static BoardProfile require(String id) {
        BoardProfile p = get(id);
        if (p == null) {
            throw new IllegalArgumentException("Unknown boardType: " + id);
        }
        return p;
    }

    public static boolean isValid(String id) {
        return get(id) != null;
    }

    public static List<BoardProfile> all() {
        return List.copyOf(BY_ID.values());
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return NANO_AVR;
        }
        String n = raw.trim().toLowerCase(Locale.ROOT);
        return isValid(n) ? n : NANO_AVR;
    }

    public PinDef pinByLabel(String silkLabel) {
        if (silkLabel == null) {
            return null;
        }
        String want = silkLabel.trim().toUpperCase(Locale.ROOT);
        for (PinDef p : pins) {
            if (p.silkLabel.equalsIgnoreCase(want)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Default sensor channel map for a board prefix (optical + digital in).
     */
    public List<SensorBoardSupport.ChannelDef> defaultChannelDefs(String prefix) {
        List<SensorBoardSupport.ChannelDef> list = new ArrayList<>();
        if (prefix == null || prefix.isEmpty()) {
            return list;
        }
        for (String label : defaultOpticalLabels) {
            int n = silkNumber(label);
            list.add(new SensorBoardSupport.ChannelDef(
                    SensorBoardSupport.analogHardwareId(prefix, n), label, "analog"));
        }
        for (String label : defaultDigitalLabels) {
            int n = silkNumber(label);
            list.add(new SensorBoardSupport.ChannelDef(
                    SensorBoardSupport.digitalHardwareId(prefix, n), label, "digital"));
        }
        return list;
    }

    static int silkNumber(String label) {
        String s = label.trim().toUpperCase(Locale.ROOT);
        if (s.startsWith("A") || s.startsWith("D")) {
            return Integer.parseInt(s.substring(1));
        }
        throw new IllegalArgumentException("Bad silk label: " + label);
    }

    public static final class PinDef {
        public final String silkLabel;
        /** Capabilities: optical, digital_in, digital_out, pwm, servo, uart, led, ref */
        public final List<String> capabilities;
        /** Firmware MCU pin token (Arduino constant name or GPIO number as string). */
        public final String mcuPin;

        public PinDef(String silkLabel, List<String> capabilities, String mcuPin) {
            this.silkLabel = Objects.requireNonNull(silkLabel).toUpperCase(Locale.ROOT);
            this.capabilities = Collections.unmodifiableList(new ArrayList<>(capabilities));
            this.mcuPin = mcuPin;
        }

        public boolean hasCapability(String cap) {
            return capabilities.contains(cap);
        }
    }

    private static BoardProfile nanoAvr() {
        List<PinDef> pins = new ArrayList<>();
        for (int i = 0; i <= 7; i++) {
            List<String> caps = new ArrayList<>();
            caps.add("optical");
            if (i == 7) {
                caps.add("ref");
            }
            pins.add(new PinDef("A" + i, caps, "A" + i));
        }
        for (int d = 0; d <= 13; d++) {
            List<String> caps = new ArrayList<>();
            if (d == 0 || d == 1) {
                caps.add("uart");
            } else if (d == 13) {
                caps.add("led");
            } else {
                caps.add("digital_in");
                caps.add("digital_out");
                if (d == 3 || d == 5 || d == 6 || d == 9 || d == 10 || d == 11) {
                    caps.add("pwm");
                    caps.add("servo");
                }
            }
            pins.add(new PinDef("D" + d, caps, String.valueOf(d)));
        }
        List<String> optical = List.of("A0", "A1", "A2", "A3", "A4", "A5", "A6");
        List<String> digital = List.of("D2", "D3", "D4", "D5", "D6", "D7", "D8", "D9", "D10", "D11", "D12");
        return new BoardProfile(
                NANO_AVR,
                "Arduino Nano (5V AVR)",
                "arduino:avr:nano",
                "panelsdcc-sensor-board",
                SensorBoardSupport.TRANSPORT_USB,
                pins,
                optical,
                "A7",
                digital);
    }

    private static BoardProfile megaAvr() {
        List<PinDef> pins = new ArrayList<>();
        for (int i = 0; i <= 15; i++) {
            List<String> caps = new ArrayList<>();
            caps.add("optical");
            if (i == 15) {
                caps.add("ref");
            }
            pins.add(new PinDef("A" + i, caps, "A" + i));
        }
        for (int d = 0; d <= 53; d++) {
            List<String> caps = new ArrayList<>();
            if (d == 0 || d == 1) {
                caps.add("uart");
            } else if (d == 13) {
                caps.add("led");
                caps.add("digital_out");
            } else {
                caps.add("digital_in");
                caps.add("digital_out");
                // Mega PWM-capable silk pins commonly used for servos
                if (d == 2 || d == 3 || d == 4 || d == 5 || d == 6 || d == 7 || d == 8 || d == 9
                        || d == 10 || d == 11 || d == 12 || d == 44 || d == 45 || d == 46) {
                    caps.add("pwm");
                    caps.add("servo");
                }
            }
            pins.add(new PinDef("D" + d, caps, String.valueOf(d)));
        }
        List<String> optical = new ArrayList<>();
        for (int i = 0; i <= 14; i++) {
            optical.add("A" + i);
        }
        // Default digital bank leaves D2–D13 free for PWM/servo outputs
        List<String> digital = new ArrayList<>();
        for (int d = 22; d <= 37; d++) {
            digital.add("D" + d);
        }
        return new BoardProfile(
                MEGA_AVR,
                "Arduino Mega (5V AVR)",
                "arduino:avr:mega",
                "panelsdcc-sensor-board-mega",
                SensorBoardSupport.TRANSPORT_USB,
                pins,
                optical,
                "A15",
                digital);
    }

    /** Nano-class ESP32 carrier — silk matches Nano; MCU pins firmware-mapped. */
    private static BoardProfile nanoEsp32() {
        BoardProfile nano = nanoAvr();
        List<PinDef> pins = new ArrayList<>();
        for (PinDef p : nano.pins) {
            pins.add(new PinDef(p.silkLabel, p.capabilities, "ESP_" + p.silkLabel));
        }
        return new BoardProfile(
                NANO_ESP32,
                "Arduino Nano ESP32 (3.3V)",
                "arduino:esp32:nano_nora",
                "panelsdcc-sensor-board-esp32",
                SensorBoardSupport.TRANSPORT_BLE,
                pins,
                nano.defaultOpticalLabels,
                nano.ambientRefLabel,
                nano.defaultDigitalLabels);
    }

    private static BoardProfile megaEsp32() {
        BoardProfile mega = megaAvr();
        List<PinDef> pins = new ArrayList<>();
        for (PinDef p : mega.pins) {
            pins.add(new PinDef(p.silkLabel, p.capabilities, "ESP_" + p.silkLabel));
        }
        return new BoardProfile(
                MEGA_ESP32,
                "ESP32 Mega-class (3.3V, Bluetooth)",
                "esp32:esp32:esp32",
                "panelsdcc-sensor-board-esp32",
                SensorBoardSupport.TRANSPORT_BLE,
                pins,
                mega.defaultOpticalLabels,
                mega.ambientRefLabel,
                mega.defaultDigitalLabels);
    }
}
