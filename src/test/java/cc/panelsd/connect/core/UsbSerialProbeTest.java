package cc.panelsd.connect.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsbSerialProbeTest {

    @Test
    void classifiesSensorBoardFrom9600Banner() {
        ScriptedPortOps ops = new ScriptedPortOps();
        ops.whenOpen(9600, "PanelsDCC Sensor Board A READY\n");
        UsbSerialProbe probe = new UsbSerialProbe(() -> ops);

        UsbSerialProbe.ProbeResult result = probe.classify("ttyUSB0");

        assertEquals(UsbSerialProbe.DeviceType.SENSOR_BOARD, result.type);
        assertEquals("A", result.prefix);
        assertEquals(DeviceDiscoveryService.SYSTEM_TYPE_SENSOR_BOARD, result.systemType());
        assertTrue(ops.openBauds.contains(9600));
        assertTrue(ops.closed);
    }

    @Test
    void classifiesDccExWhenNoSensorBanner() {
        ScriptedPortOps ops = new ScriptedPortOps();
        ops.whenOpen(9600, "");
        ops.whenOpen(115200, "<iDCC-EX V-5.4.19 / ESP32 / EXCSB1>\n");
        UsbSerialProbe probe = new UsbSerialProbe(() -> ops);

        UsbSerialProbe.ProbeResult result = probe.classify("ttyUSB1");

        assertEquals(UsbSerialProbe.DeviceType.DCCPP, result.type);
        assertNull(result.prefix);
        assertTrue(ops.writes.stream().anyMatch(w -> new String(w, StandardCharsets.UTF_8).contains("<s>")));
        assertTrue(ops.closed);
    }

    @Test
    void retriesSensorBannerOnSecondOpen() {
        ScriptedPortOps ops = new ScriptedPortOps();
        ops.whenOpen(9600, "");
        ops.whenOpen(9600, "PanelsDCC Sensor Board B READY\n");
        UsbSerialProbe probe = new UsbSerialProbe(() -> ops);

        UsbSerialProbe.ProbeResult result = probe.classify("ttyUSB3");

        assertEquals(UsbSerialProbe.DeviceType.SENSOR_BOARD, result.type);
        assertEquals("B", result.prefix);
        assertEquals(2, ops.openBauds.stream().filter(b -> b == 9600).count());
    }

    @Test
    void portBusyDoesNotAttemptDccpp() {
        ScriptedPortOps ops = new ScriptedPortOps();
        ops.failOpen = true;
        UsbSerialProbe probe = new UsbSerialProbe(() -> ops);

        UsbSerialProbe.ProbeResult result = probe.classify("ttyUSB4");

        assertEquals(UsbSerialProbe.DeviceType.UNKNOWN, result.type);
        assertTrue(result.portBusy);
        assertTrue(ops.writes.isEmpty());
    }

    @Test
    void parseSensorReadyPrefixAcceptsMultiLetterPrefix() {
        assertEquals("AA", UsbSerialProbe.parseSensorReadyPrefix("PanelsDCC Sensor Board AA READY"));
        assertNull(UsbSerialProbe.parseSensorReadyPrefix("hello"));
    }

    @Test
    void looksLikeDccppIdentityRecognisesVariants() {
        assertTrue(UsbSerialProbe.looksLikeDccppIdentity("<iDCC-EX V-5.4.19 / ESP32 / EXCSB1>"));
        assertTrue(UsbSerialProbe.looksLikeDccppIdentity("CS Version: 5.0"));
    }

    /**
     * Fake port that returns scripted responses per baud. A new instance is created
     * per open cycle via the factory, so we share script state on this object and
     * reset per open.
     */
    static final class ScriptedPortOps implements UsbSerialProbe.SerialPortOps {
        private final List<Integer> openBauds = new CopyOnWriteArrayList<>();
        private final List<byte[]> writes = new CopyOnWriteArrayList<>();
        private final List<BaudScript> scripts = new ArrayList<>();
        private String pendingRead = "";
        private boolean closed = true;
        boolean failOpen = false;

        void whenOpen(int baud, String response) {
            scripts.add(new BaudScript(baud, response));
        }

        @Override
        public void open(String portName, int baudRate) throws java.io.IOException {
            if (failOpen) {
                throw new java.io.IOException("port busy");
            }
            closed = false;
            openBauds.add(baudRate);
            pendingRead = "";
            for (BaudScript s : scripts) {
                if (s.baud == baudRate && !s.consumed) {
                    s.consumed = true;
                    pendingRead = s.response;
                    return;
                }
            }
        }

        @Override
        public void write(byte[] data) {
            writes.add(Arrays.copyOf(data, data.length));
        }

        @Override
        public List<String> readLines(long timeoutMs) {
            String text = pendingRead;
            pendingRead = "";
            List<String> lines = new ArrayList<>();
            if (text.isEmpty()) {
                return lines;
            }
            for (String line : text.split("\\r?\\n")) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
            return lines;
        }

        @Override
        public void close() {
            closed = true;
        }

        static final class BaudScript {
            final int baud;
            final String response;
            boolean consumed;

            BaudScript(int baud, String response) {
                this.baud = baud;
                this.response = response;
            }
        }
    }
}
