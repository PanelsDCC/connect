package cc.panelsd.connect.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies ambiguous USB-UART candidates (e.g. CH340) by opening the serial port
 * and probing for a PanelsDCC Node banner @ 9600, then DCC-EX/DCC++ @ 115200.
 */
public final class UsbSerialProbe {

    public static final int NODE_BAUD = 9600;
    public static final int DCCPP_BAUD = 115200;

    /**
     * {@code PanelsDCC Node A nano-avr READY}, optional {@code v2026-10-27} / {@code v1.0.4},
     * or legacy {@code PanelsDCC Node A READY}.
     */
    private static final Pattern NODE_READY =
            Pattern.compile("^PanelsDCC Node (\\S+)(?: (\\S+?))?(?: v(\\d{4}-\\d{2}-\\d{2}|\\d+(?:\\.\\d+)*))? READY\\s*$");
    /** Nano boot after DTR can exceed 1.5s after host reboot / USB re-enum. */
    static final long NODE_BOOT_WAIT_MS = 4000L;
    private static final long NODE_RETRY_SETTLE_MS = 500L;
    private static final long DCCPP_REPLY_WAIT_MS = 1000L;

    private final SerialPortOpsFactory portFactory;

    public UsbSerialProbe() {
        this(JSerialCommPortOps::new);
    }

    public UsbSerialProbe(SerialPortOpsFactory portFactory) {
        this.portFactory = Objects.requireNonNull(portFactory, "portFactory");
    }

    /**
     * Classify a candidate tty. Prefer sensor banner first, then DCC++ identity.
     */
    public ProbeResult classify(String portName) {
        ProbeResult sensor = tryNode(portName);
        if (sensor.type == DeviceType.NODE) {
            return sensor;
        }
        if (sensor.portBusy) {
            return sensor;
        }
        ProbeResult dcc = tryDccpp(portName);
        if (dcc.type == DeviceType.DCCPP) {
            return dcc;
        }
        if (dcc.portBusy) {
            return dcc;
        }
        return ProbeResult.unknown();
    }

    /**
     * Quick verify for a cached sensor classification: open @ 9600 and look for READY.
     */
    public ProbeResult verifyNode(String portName) {
        return tryNode(portName);
    }

    /**
     * Quick verify for a cached DCC++ classification: open @ 115200 and query {@code <s>}.
     */
    public ProbeResult verifyDccpp(String portName) {
        return tryDccpp(portName);
    }

    private ProbeResult tryNode(String portName) {
        ProbeResult first = tryNodeOnce(portName);
        if (first.type == DeviceType.NODE || first.portBusy) {
            return first;
        }
        // Second chance: first open often resets the Nano mid-enumeration after reboot.
        sleepQuietly(NODE_RETRY_SETTLE_MS);
        return tryNodeOnce(portName);
    }

    /** Live sensor report, e.g. {@code A-A3: DETECTED}. Prefix is group 1. */
    private static final Pattern SENSOR_EDGE =
            Pattern.compile("^([A-Z]{1,2})-[A-Z]\\d+: (DETECTED|CLEAR)$");
    /** On-demand identity: {@code ID B nano-esp32} or {@code ID B nano-esp32 2026-10-27}. */
    private static final Pattern SENSOR_ID =
            Pattern.compile("^ID ([A-Za-z]{1,2}) (\\S+)(?: (\\d{4}-\\d{2}-\\d{2}|\\d+(?:\\.\\d+)*))?\\s*$");

    private ProbeResult tryNodeOnce(String portName) {
        SerialPortOps port = portFactory.create();
        try {
            port.open(portName, NODE_BAUD);
            // Opening often toggles DTR and resets AVR Nanos — wait for boot banner.
            List<String> lines = port.readLines(NODE_BOOT_WAIT_MS);
            ProbeResult ready = readyFrom(lines);
            if (ready != null) {
                return ready;
            }
            // Native-USB boards (Arduino Nano ESP32) do not reset when the port
            // opens, so the boot banner is not repeated. Ask for identity, then
            // fall back to CFG? for images flashed before ID? existed.
            port.write("ID?\n".getBytes(StandardCharsets.UTF_8));
            lines.addAll(port.readLines(800));
            ProbeResult identified = idFrom(lines);
            if (identified != null) {
                return identified;
            }
            ready = readyFrom(lines);
            if (ready != null) {
                return ready;
            }
            port.write("CFG?\n".getBytes(StandardCharsets.UTF_8));
            lines.addAll(port.readLines(800));
            identified = idFrom(lines);
            if (identified != null) {
                return identified;
            }
            ready = readyFrom(lines);
            if (ready != null) {
                return ready;
            }
            if (looksLikeLiveNode(lines)) {
                // Prefix stays unknown unless a sensor line names it. Callers keep
                // the prefix chosen at flash time instead of assuming A.
                return ProbeResult.node(prefixFromSensorLines(lines), null);
            }
            return ProbeResult.unknown();
        } catch (Exception e) {
            System.err.println("USB node probe open/read failed on " + portName + ": " + e.getMessage());
            return ProbeResult.portBusy();
        } finally {
            port.closeQuietly();
        }
    }

    private static ProbeResult idFrom(List<String> lines) {
        for (String line : lines) {
            Matcher m = SENSOR_ID.matcher(line.trim());
            if (m.matches()) {
                return ProbeResult.node(m.group(1).toUpperCase(), m.group(2), m.group(3));
            }
        }
        return null;
    }

    private static ProbeResult readyFrom(List<String> lines) {
        for (String line : lines) {
            Matcher m = NODE_READY.matcher(line.trim());
            if (m.matches()) {
                return ProbeResult.node(m.group(1), m.group(2), m.group(3));
            }
        }
        return null;
    }

    private static boolean looksLikeLiveNode(List<String> lines) {
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("CFG ") || SENSOR_EDGE.matcher(trimmed).matches()) {
                return true;
            }
        }
        return false;
    }

    private static String prefixFromSensorLines(List<String> lines) {
        for (String line : lines) {
            Matcher m = SENSOR_EDGE.matcher(line.trim());
            if (m.matches()) {
                return m.group(1);
            }
        }
        return null;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ProbeResult tryDccpp(String portName) {
        SerialPortOps port = portFactory.create();
        try {
            port.open(portName, DCCPP_BAUD);
            port.write("<s>\n".getBytes(StandardCharsets.UTF_8));
            List<String> lines = port.readLines(DCCPP_REPLY_WAIT_MS);
            for (String line : lines) {
                if (looksLikeDccppIdentity(line)) {
                    return ProbeResult.dccpp();
                }
            }
            return ProbeResult.unknown();
        } catch (Exception e) {
            System.err.println("USB DCC++ probe open/read failed on " + portName + ": " + e.getMessage());
            return ProbeResult.portBusy();
        } finally {
            port.closeQuietly();
        }
    }

    static boolean looksLikeDccppIdentity(String line) {
        if (line == null) {
            return false;
        }
        String t = line.trim();
        if (t.isEmpty()) {
            return false;
        }
        String lower = t.toLowerCase();
        return t.startsWith("<i")
                || lower.contains("dcc-ex")
                || lower.contains("dcc++")
                || lower.contains("dccpp")
                || lower.contains("cs version");
    }

    /**
     * Parse a sensor READY banner line; returns the board prefix or null.
     */
    public static String parseNodeReadyPrefix(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = NODE_READY.matcher(line.trim());
        return m.matches() ? m.group(1) : null;
    }

    /** Optional boardType from READY banner (null for legacy Sensor Board lines). */
    public static String parseNodeReadyBoardType(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = NODE_READY.matcher(line.trim());
        return m.matches() ? m.group(2) : null;
    }

    /** Optional image version from READY banner ({@code v2026-10-27}). */
    public static String parseNodeReadyVersion(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = NODE_READY.matcher(line.trim());
        return m.matches() ? m.group(3) : null;
    }

    public enum DeviceType {
        NODE,
        DCCPP,
        UNKNOWN
    }

    public static final class ProbeResult {
        public final DeviceType type;
        /** Board prefix when {@link DeviceType#NODE}, else null. */
        public final String prefix;
        /** Board type id when present on READY banner (e.g. nano-avr). */
        public final String boardType;
        /** Catalog image version when present on READY / ID reply (e.g. {@code 2026-10-27}). */
        public final String firmwareVersion;
        /** True when the serial port could not be opened/read (retry sooner). */
        public final boolean portBusy;

        private ProbeResult(DeviceType type, String prefix, boolean portBusy) {
            this(type, prefix, null, null, portBusy);
        }

        private ProbeResult(DeviceType type, String prefix, String boardType, boolean portBusy) {
            this(type, prefix, boardType, null, portBusy);
        }

        private ProbeResult(
                DeviceType type, String prefix, String boardType, String firmwareVersion, boolean portBusy) {
            this.type = type;
            this.prefix = prefix;
            this.boardType = boardType;
            this.firmwareVersion = firmwareVersion;
            this.portBusy = portBusy;
        }

        public static ProbeResult node(String prefix) {
            return new ProbeResult(DeviceType.NODE, prefix, null, null, false);
        }

        public static ProbeResult node(String prefix, String boardType) {
            return new ProbeResult(DeviceType.NODE, prefix, boardType, null, false);
        }

        public static ProbeResult node(String prefix, String boardType, String firmwareVersion) {
            return new ProbeResult(DeviceType.NODE, prefix, boardType, firmwareVersion, false);
        }

        public static ProbeResult dccpp() {
            return new ProbeResult(DeviceType.DCCPP, null, false);
        }

        public static ProbeResult unknown() {
            return new ProbeResult(DeviceType.UNKNOWN, null, false);
        }

        public static ProbeResult portBusy() {
            return new ProbeResult(DeviceType.UNKNOWN, null, true);
        }

        public String systemType() {
            switch (type) {
                case NODE:
                    return DeviceDiscoveryService.SYSTEM_TYPE_NODE;
                case DCCPP:
                    return DeviceDiscoveryService.SYSTEM_TYPE_DCCPP;
                default:
                    return "unknown";
            }
        }
    }

    @FunctionalInterface
    public interface SerialPortOpsFactory {
        SerialPortOps create();
    }

    /**
     * Minimal serial port operations used by the probe (and tests).
     */
    public interface SerialPortOps {
        void open(String portName, int baudRate) throws IOException;

        void write(byte[] data) throws IOException;

        /**
         * Read available data for up to {@code timeoutMs}, split into lines.
         */
        List<String> readLines(long timeoutMs) throws IOException;

        void close() throws IOException;

        default void closeQuietly() {
            try {
                close();
            } catch (Exception ignore) {
                // best-effort
            }
        }
    }

    /**
     * Reflective jSerialComm implementation (JMRI runtime classpath).
     */
    public static final class JSerialCommPortOps implements SerialPortOps {
        private Object port;
        private InputStream in;
        private OutputStream out;
        private final StringBuilder lineCarry = new StringBuilder();

        @Override
        public void open(String portName, int baudRate) throws IOException {
            lineCarry.setLength(0);
            try {
                Class<?> serialPortClass = Class.forName("com.fazecast.jSerialComm.SerialPort");
                java.lang.reflect.Method getCommPorts = serialPortClass.getMethod("getCommPorts");
                Object[] ports = (Object[]) getCommPorts.invoke(null);
                Object match = null;
                String bare = portName;
                if (bare != null && bare.startsWith("/dev/")) {
                    bare = bare.substring(5);
                }
                for (Object p : ports) {
                    String name = (String) p.getClass().getMethod("getSystemPortName").invoke(p);
                    if (portName.equals(name) || bare.equals(name)
                            || ("/dev/" + bare).equals(name)
                            || (name != null && name.endsWith("/" + bare))) {
                        match = p;
                        break;
                    }
                }
                if (match == null) {
                    throw new IOException("Serial port not found: " + portName);
                }
                match.getClass().getMethod("setBaudRate", int.class).invoke(match, baudRate);
                match.getClass().getMethod("setNumDataBits", int.class).invoke(match, 8);
                match.getClass().getMethod("setNumStopBits", int.class).invoke(match, 1);
                match.getClass().getMethod("setParity", int.class).invoke(match, 0);
                // TIMEOUT_READ_SEMI_BLOCKING = 1
                match.getClass().getMethod("setComPortTimeouts", int.class, int.class, int.class)
                        .invoke(match, 1, 100, 0);
                boolean opened = (Boolean) match.getClass().getMethod("openPort").invoke(match);
                if (!opened) {
                    throw new IOException("Failed to open serial port: " + portName);
                }
                this.port = match;
                this.in = (InputStream) match.getClass().getMethod("getInputStream").invoke(match);
                this.out = (OutputStream) match.getClass().getMethod("getOutputStream").invoke(match);
            } catch (IOException e) {
                closeQuietly();
                throw e;
            } catch (Exception e) {
                closeQuietly();
                throw new IOException("jSerialComm open failed: " + e.getMessage(), e);
            }
        }

        @Override
        public void write(byte[] data) throws IOException {
            if (out == null) {
                throw new IOException("Port not open");
            }
            out.write(data);
            out.flush();
        }

        @Override
        public List<String> readLines(long timeoutMs) throws IOException {
            if (in == null) {
                throw new IOException("Port not open");
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[256];
            // available() stays 0 on this CDC device even when bytes are waiting.
            // A blocking read drains them; otherwise the Nano fills its TX buffer
            // and stops reading commands while the UI still looks live.
            while (System.nanoTime() < deadline) {
                int n;
                try {
                    n = in.read(chunk);
                } catch (IOException e) {
                    // Semi-blocking reads throw when the 100ms slice ends with no bytes.
                    String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                    if (msg.contains("timed out")) {
                        continue;
                    }
                    throw e;
                }
                if (n > 0) {
                    buf.write(chunk, 0, n);
                } else if (n < 0) {
                    break;
                }
            }
            String text = buf.toString(StandardCharsets.UTF_8.name());
            List<String> lines = new ArrayList<>();
            if (!text.isEmpty()) {
                lineCarry.append(text);
            }
            int idx;
            while ((idx = indexOfNewline(lineCarry)) >= 0) {
                String line = lineCarry.substring(0, idx).replace("\r", "");
                lineCarry.delete(0, idx + 1);
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
            return lines;
        }

        private static int indexOfNewline(StringBuilder sb) {
            for (int i = 0; i < sb.length(); i++) {
                char c = sb.charAt(i);
                if (c == '\n') {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public void close() throws IOException {
            try {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Exception ignore) {
                    }
                }
                if (out != null) {
                    try {
                        out.close();
                    } catch (Exception ignore) {
                    }
                }
                if (port != null) {
                    try {
                        port.getClass().getMethod("closePort").invoke(port);
                    } catch (Exception ignore) {
                    }
                }
            } finally {
                in = null;
                out = null;
                port = null;
                lineCarry.setLength(0);
            }
        }
    }
}
