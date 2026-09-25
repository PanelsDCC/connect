package cc.panelsd.connect.core.impl.sensor;

import cc.panelsd.connect.core.UsbSerialProbe;
import cc.panelsd.connect.core.UsbSerialProbe.SerialPortOps;
import cc.panelsd.connect.core.UsbSerialProbe.SerialPortOpsFactory;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holds an open PanelsDCC Sensor Board serial session @ 9600 and publishes raw
 * occupancy edges as gateway {@code sensorHardware} patches for Control to process.
 */
public final class SensorBoardSession implements AutoCloseable {

    private static final Pattern OCCUPANCY =
            Pattern.compile("^(\\S+):\\s*(DETECTED|CLEAR)\\s*$", Pattern.CASE_INSENSITIVE);

    private final String portName;
    private final String prefix;
    private final String byPath;
    private final SerialPortOpsFactory portFactory;
    private final Supplier<Consumer<JsonObject>> publisherSupplier;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile SerialPortOps port;
    private Thread readerThread;

    public SensorBoardSession(
            String portName,
            String prefix,
            String byPath,
            Supplier<Consumer<JsonObject>> publisherSupplier) {
        this(portName, prefix, byPath, UsbSerialProbe.JSerialCommPortOps::new, publisherSupplier);
    }

    public SensorBoardSession(
            String portName,
            String prefix,
            String byPath,
            SerialPortOpsFactory portFactory,
            Supplier<Consumer<JsonObject>> publisherSupplier) {
        this.portName = Objects.requireNonNull(portName, "portName");
        this.prefix = prefix;
        this.byPath = byPath;
        this.portFactory = Objects.requireNonNull(portFactory, "portFactory");
        this.publisherSupplier = Objects.requireNonNull(publisherSupplier, "publisherSupplier");
    }

    public String getPortName() {
        return portName;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getByPath() {
        return byPath;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        SerialPortOps opened = portFactory.create();
        try {
            opened.open(portName, UsbSerialProbe.SENSOR_BAUD);
        } catch (IOException e) {
            running.set(false);
            opened.closeQuietly();
            throw e;
        }
        this.port = opened;
        readerThread = new Thread(this::readLoop, "SensorBoard-" + portName);
        readerThread.setDaemon(true);
        readerThread.start();
        System.out.println("PanelsDCC Sensor Board session started on " + portName
                + (prefix != null ? " (prefix " + prefix + ")" : ""));
    }

    private void readLoop() {
        SerialPortOps p = port;
        if (p == null) {
            return;
        }
        try {
            while (running.get()) {
                java.util.List<String> lines;
                try {
                    lines = p.readLines(200);
                } catch (IOException e) {
                    if (running.get()) {
                        throw e;
                    }
                    return;
                }
                for (String line : lines) {
                    handleLine(line);
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                System.err.println("Sensor board reader ended on " + portName + ": " + e.getMessage());
            }
        } finally {
            running.set(false);
        }
    }

    void handleLine(String line) {
        Optional<SensorEdge> edge = parseOccupancyLine(line);
        if (edge.isPresent()) {
            publish(edge.get());
        }
    }

    /**
     * Parse an occupancy line into a hardware edge, or empty if not an occupancy event.
     */
    public static Optional<SensorEdge> parseOccupancyLine(String line) {
        if (line == null) {
            return Optional.empty();
        }
        Matcher m = OCCUPANCY.matcher(line.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        String hardwareId = m.group(1);
        int state = "DETECTED".equalsIgnoreCase(m.group(2)) ? 1 : 0;
        return Optional.of(new SensorEdge(hardwareId, state));
    }

    private void publish(SensorEdge edge) {
        Consumer<JsonObject> publisher = publisherSupplier.get();
        if (publisher == null) {
            return;
        }
        try {
            JsonObject data = new JsonObject();
            data.addProperty("id", edge.hardwareId);
            data.addProperty("state", edge.state);
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "sensorHardware");
            msg.addProperty("method", "patch");
            msg.add("data", data);
            publisher.accept(msg);
        } catch (Exception e) {
            System.err.println("Failed to broadcast sensorHardware for " + edge.hardwareId + ": " + e.getMessage());
        }
    }

    @Override
    public void close() {
        running.set(false);
        SerialPortOps p = port;
        port = null;
        if (p != null) {
            p.closeQuietly();
        }
        Thread t = readerThread;
        if (t != null && t.isAlive()) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        readerThread = null;
        System.out.println("PanelsDCC Sensor Board session closed on " + portName);
    }

    public static final class SensorEdge {
        public final String hardwareId;
        public final int state;

        public SensorEdge(String hardwareId, int state) {
            this.hardwareId = hardwareId;
            this.state = state;
        }
    }
}
