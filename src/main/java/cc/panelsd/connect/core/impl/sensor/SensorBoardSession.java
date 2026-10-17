package cc.panelsd.connect.core.impl.sensor;

import cc.panelsd.connect.core.UsbSerialProbe;
import cc.panelsd.connect.core.UsbSerialProbe.SerialPortOps;
import cc.panelsd.connect.core.UsbSerialProbe.SerialPortOpsFactory;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holds an open PanelsDCC Sensor Board serial session @ 9600 and publishes raw
 * occupancy edges as gateway {@code sensorHardware} patches for Control to process.
 */
public final class SensorBoardSession implements NodeSession {

    private static final Pattern OCCUPANCY =
            Pattern.compile("^(\\S+):\\s*(DETECTED|CLEAR)\\s*$", Pattern.CASE_INSENSITIVE);

    private final String portName;
    private final String prefix;
    private final String byPath;
    private final String boardType;
    private final SerialPortOpsFactory portFactory;
    private final Supplier<Consumer<JsonObject>> publisherSupplier;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile SerialPortOps port;
    private Thread readerThread;
    private final AtomicReference<CompletableFuture<String>> sampleWaiter = new AtomicReference<>();

    public SensorBoardSession(
            String portName,
            String prefix,
            String byPath,
            Supplier<Consumer<JsonObject>> publisherSupplier) {
        this(portName, prefix, byPath, BoardProfile.NANO_AVR, UsbSerialProbe.JSerialCommPortOps::new, publisherSupplier);
    }

    public SensorBoardSession(
            String portName,
            String prefix,
            String byPath,
            String boardType,
            Supplier<Consumer<JsonObject>> publisherSupplier) {
        this(portName, prefix, byPath, boardType, UsbSerialProbe.JSerialCommPortOps::new, publisherSupplier);
    }

    public SensorBoardSession(
            String portName,
            String prefix,
            String byPath,
            SerialPortOpsFactory portFactory,
            Supplier<Consumer<JsonObject>> publisherSupplier) {
        this(portName, prefix, byPath, BoardProfile.NANO_AVR, portFactory, publisherSupplier);
    }

    public SensorBoardSession(
            String portName,
            String prefix,
            String byPath,
            String boardType,
            SerialPortOpsFactory portFactory,
            Supplier<Consumer<JsonObject>> publisherSupplier) {
        this.portName = Objects.requireNonNull(portName, "portName");
        this.prefix = prefix;
        this.byPath = byPath;
        this.boardType = BoardProfile.normalize(boardType);
        this.portFactory = Objects.requireNonNull(portFactory, "portFactory");
        this.publisherSupplier = Objects.requireNonNull(publisherSupplier, "publisherSupplier");
    }

    @Override
    public String getId() {
        return SensorBoardSupport.sessionIdForPort(portName);
    }

    public String getPortName() {
        return portName;
    }

    @Override
    public String getPrefix() {
        return prefix;
    }

    @Override
    public String getBoardType() {
        return boardType;
    }

    @Override
    public String getTransport() {
        return SensorBoardSupport.TRANSPORT_USB;
    }

    @Override
    public String getEndpoint() {
        return SensorBoardSupport.barePort(portName);
    }

    public String getByPath() {
        return byPath;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void sendCommand(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        SerialPortOps p = port;
        if (p == null || !running.get()) {
            return;
        }
        try {
            byte[] data = (line.endsWith("\n") ? line : line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            p.write(data);
        } catch (Exception e) {
            System.err.println("Failed to send to sensor board " + portName + ": " + e.getMessage());
        }
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
        if (line != null) {
            String t = line.trim();
            if (t.regionMatches(true, 0, "SAMPLE", 0, 6)) {
                CompletableFuture<String> waiter = sampleWaiter.getAndSet(null);
                if (waiter != null) {
                    waiter.complete(t);
                }
                return;
            }
        }
        Optional<SensorEdge> edge = parseOccupancyLine(line);
        if (edge.isPresent()) {
            publish(edge.get());
        }
    }

    @Override
    public String sendCommandAwaitReply(String line, String replyPrefix, long timeoutMs) {
        if (replyPrefix == null || !replyPrefix.equalsIgnoreCase("SAMPLE")) {
            sendCommand(line);
            return null;
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        sampleWaiter.set(future);
        sendCommand(line);
        try {
            return future.get(Math.max(50L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            sampleWaiter.compareAndSet(future, null);
            return null;
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
