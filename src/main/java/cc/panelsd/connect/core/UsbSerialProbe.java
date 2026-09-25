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
 * and probing for a PanelsDCC Sensor Board banner @ 9600, then DCC-EX/DCC++ @ 115200.
 */
public final class UsbSerialProbe {

    public static final int SENSOR_BAUD = 9600;
    public static final int DCCPP_BAUD = 115200;

    private static final Pattern SENSOR_READY =
            Pattern.compile("^PanelsDCC Sensor Board (\\S+) READY\\s*$");
    /** Nano boot after DTR can exceed 1.5s after host reboot / USB re-enum. */
    static final long SENSOR_BOOT_WAIT_MS = 4000L;
    private static final long SENSOR_RETRY_SETTLE_MS = 500L;
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
        ProbeResult sensor = trySensorBoard(portName);
        if (sensor.type == DeviceType.SENSOR_BOARD) {
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
    public ProbeResult verifySensorBoard(String portName) {
        return trySensorBoard(portName);
    }

    /**
     * Quick verify for a cached DCC++ classification: open @ 115200 and query {@code <s>}.
     */
    public ProbeResult verifyDccpp(String portName) {
        return tryDccpp(portName);
    }

    private ProbeResult trySensorBoard(String portName) {
        ProbeResult first = trySensorBoardOnce(portName);
        if (first.type == DeviceType.SENSOR_BOARD || first.portBusy) {
            return first;
        }
        // Second chance: first open often resets the Nano mid-enumeration after reboot.
        sleepQuietly(SENSOR_RETRY_SETTLE_MS);
        return trySensorBoardOnce(portName);
    }

    private ProbeResult trySensorBoardOnce(String portName) {
        SerialPortOps port = portFactory.create();
        try {
            port.open(portName, SENSOR_BAUD);
            // Opening often toggles DTR and resets AVR Nanos — wait for boot banner.
            List<String> lines = port.readLines(SENSOR_BOOT_WAIT_MS);
            for (String line : lines) {
                Matcher m = SENSOR_READY.matcher(line.trim());
                if (m.matches()) {
                    return ProbeResult.sensorBoard(m.group(1));
                }
            }
            return ProbeResult.unknown();
        } catch (Exception e) {
            System.err.println("USB sensor probe open/read failed on " + portName + ": " + e.getMessage());
            return ProbeResult.portBusy();
        } finally {
            port.closeQuietly();
        }
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
    public static String parseSensorReadyPrefix(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = SENSOR_READY.matcher(line.trim());
        return m.matches() ? m.group(1) : null;
    }

    public enum DeviceType {
        SENSOR_BOARD,
        DCCPP,
        UNKNOWN
    }

    public static final class ProbeResult {
        public final DeviceType type;
        /** Board prefix when {@link DeviceType#SENSOR_BOARD}, else null. */
        public final String prefix;
        /** True when the serial port could not be opened/read (retry sooner). */
        public final boolean portBusy;

        private ProbeResult(DeviceType type, String prefix, boolean portBusy) {
            this.type = type;
            this.prefix = prefix;
            this.portBusy = portBusy;
        }

        public static ProbeResult sensorBoard(String prefix) {
            return new ProbeResult(DeviceType.SENSOR_BOARD, prefix, false);
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
                case SENSOR_BOARD:
                    return DeviceDiscoveryService.SYSTEM_TYPE_SENSOR_BOARD;
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
            while (System.nanoTime() < deadline) {
                int available = in.available();
                if (available > 0) {
                    int n = in.read(chunk, 0, Math.min(chunk.length, available));
                    if (n > 0) {
                        buf.write(chunk, 0, n);
                    }
                } else {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            // Drain anything that arrived at the end
            int available = in.available();
            while (available > 0) {
                int n = in.read(chunk, 0, Math.min(chunk.length, available));
                if (n <= 0) {
                    break;
                }
                buf.write(chunk, 0, n);
                available = in.available();
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
