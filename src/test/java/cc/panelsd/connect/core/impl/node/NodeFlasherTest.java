package cc.panelsd.connect.core.impl.node;

import cc.panelsd.connect.core.UsbSerialProbe.SerialPortOps;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeFlasherTest {

    @TempDir
    Path temp;

    @Test
    void selectsNewerCacheImageAndBuildsUploadCommands() throws Exception {
        Path shipped = temp.resolve("shipped");
        Path cache = temp.resolve("cache");
        Files.createDirectories(shipped);
        Files.createDirectories(cache);
        Path shippedHex = shipped.resolve("nano-avr.hex");
        Path cacheHex = cache.resolve("nano-avr.hex");
        Files.writeString(shippedHex, ":nano-shipped\n", StandardCharsets.US_ASCII);
        Files.writeString(cacheHex, ":nano-cache\n", StandardCharsets.US_ASCII);
        Path espApp = shipped.resolve("nano-esp32.bin");
        Path espBoot = shipped.resolve("nano-esp32.bootloader.bin");
        Files.writeString(espApp, "app", StandardCharsets.US_ASCII);
        Files.writeString(espBoot, "boot", StandardCharsets.US_ASCII);
        Files.writeString(cache.resolve("nano-custom.json"), "{\"pins\":[]}\n", StandardCharsets.UTF_8);

        String shippedManifest = "{\n"
                + "  \"images\": [\n"
                + "    {\n"
                + "      \"id\": \"nano-avr\",\n"
                + "      \"version\": 1,\n"
                + "      \"tool\": \"avrdude\",\n"
                + "      \"filename\": \"nano-avr.hex\",\n"
                + "      \"mcu\": \"atmega328p\",\n"
                + "      \"programmer\": \"arduino\",\n"
                + "      \"baud\": 57600,\n"
                + "      \"altBaud\": 115200,\n"
                + "      \"sha256\": \"" + FirmwareImageCatalog.sha256(shippedHex) + "\"\n"
                + "    },\n"
                + "    {\n"
                + "      \"id\": \"nano-esp32\",\n"
                + "      \"version\": 1,\n"
                + "      \"tool\": \"esptool\",\n"
                + "      \"chip\": \"esp32\",\n"
                + "      \"baud\": 460800,\n"
                + "      \"flashArgs\": [\"-z\", \"--flash_size\", \"detect\"],\n"
                + "      \"parts\": [\n"
                + "        {\"filename\": \"nano-esp32.bootloader.bin\", \"offset\": \"0x1000\", \"sha256\": \""
                + FirmwareImageCatalog.sha256(espBoot) + "\"},\n"
                + "        {\"filename\": \"nano-esp32.bin\", \"offset\": \"0x10000\", \"sha256\": \""
                + FirmwareImageCatalog.sha256(espApp) + "\"}\n"
                + "      ]\n"
                + "    }\n"
                + "  ]\n"
                + "}\n";
        Files.writeString(shipped.resolve("manifest.json"), shippedManifest, StandardCharsets.UTF_8);
        String cacheManifest = "{\n"
                + "  \"images\": [\n"
                + "    {\n"
                + "      \"id\": \"nano-avr\",\n"
                + "      \"version\": 2,\n"
                + "      \"tool\": \"avrdude\",\n"
                + "      \"filename\": \"nano-avr.hex\",\n"
                + "      \"mcu\": \"atmega328p\",\n"
                + "      \"programmer\": \"arduino\",\n"
                + "      \"baud\": 57600,\n"
                + "      \"sha256\": \"" + FirmwareImageCatalog.sha256(cacheHex) + "\",\n"
                + "      \"config\": \"nano-custom.json\"\n"
                + "    }\n"
                + "  ]\n"
                + "}\n";
        Files.writeString(cache.resolve("manifest.json"), cacheManifest, StandardCharsets.UTF_8);

        FirmwareImageCatalog catalog = FirmwareImageCatalog.load(shipped, cache);
        FirmwareImageCatalog.Image nano = catalog.select("nano-avr").orElseThrow();
        assertEquals("2", nano.version);
        assertEquals("cache", nano.source);
        assertEquals("nano-custom.json", nano.configFile.getFileName().toString());
        assertTrue(Files.isRegularFile(nano.configFile));

        List<String> avr = nano.uploadCommand("avrdude", "esptool", "/dev/ttyUSB0");
        assertEquals("avrdude", avr.get(0));
        assertTrue(avr.contains("atmega328p"));
        assertTrue(avr.contains("arduino"));
        assertTrue(avr.contains("57600"));
        assertTrue(avr.contains("/dev/ttyUSB0"));
        assertTrue(avr.get(avr.size() - 1).startsWith("flash:w:"));
        assertTrue(avr.get(avr.size() - 1).contains(cacheHex.toAbsolutePath().toString()));
        assertTrue(avr.get(avr.size() - 1).endsWith(":i"));

        FirmwareImageCatalog.Image esp = catalog.select("nano-esp32").orElseThrow();
        assertEquals("shipped", esp.source);
        List<String> espCmd = esp.uploadCommand("avrdude", "esptool", "/dev/ttyUSB1");
        assertEquals(List.of(
                "esptool",
                "--chip", "esp32",
                "--port", "/dev/ttyUSB1",
                "--baud", "460800",
                "--before", "default_reset",
                "--after", "hard_reset",
                "write_flash",
                "-z", "--flash_size", "detect",
                "0x1000", espBoot.toAbsolutePath().toString(),
                "0x10000", espApp.toAbsolutePath().toString()), espCmd);

        assertEquals("PREFIX B\n", NodeFlasher.prefixCommand("B"));
        assertTrue(NodeFlasher.idConfirms("ID B nano-avr", "B"));
        assertFalse(NodeFlasher.idConfirms("ID AB nano-avr", "A"));
        assertFalse(NodeFlasher.idConfirms("ID A nano-avr", "AB"));
    }

    @Test
    void uploadThenSendsPrefix() throws Exception {
        Path shipped = temp.resolve("images");
        Files.createDirectories(shipped);
        Path hex = shipped.resolve("mega-avr.hex");
        Files.writeString(hex, ":mega\n", StandardCharsets.US_ASCII);
        Files.writeString(shipped.resolve("manifest.json"), "{\n"
                + "  \"images\": [\n"
                + "    {\n"
                + "      \"id\": \"mega-avr\",\n"
                + "      \"version\": 1,\n"
                + "      \"tool\": \"avrdude\",\n"
                + "      \"filename\": \"mega-avr.hex\",\n"
                + "      \"mcu\": \"atmega2560\",\n"
                + "      \"programmer\": \"wiring\",\n"
                + "      \"baud\": 115200,\n"
                + "      \"sha256\": \"" + FirmwareImageCatalog.sha256(hex) + "\"\n"
                + "    }\n"
                + "  ]\n"
                + "}\n", StandardCharsets.UTF_8);

        Path avrdude = temp.resolve("avrdude");
        Path esptool = temp.resolve("esptool");
        Files.writeString(avrdude, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        Files.writeString(esptool, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        assertTrue(avrdude.toFile().setExecutable(true));
        assertTrue(esptool.toFile().setExecutable(true));

        List<List<String>> commands = new ArrayList<>();
        RecordingPort port = new RecordingPort();
        FirmwareImageCatalog catalog = FirmwareImageCatalog.load(shipped, temp.resolve("empty-cache"));
        NodeFlasher flasher = new NodeFlasher(
                catalog,
                avrdude.toString(),
                esptool.toString(),
                () -> port,
                (command, log) -> {
                    commands.add(command);
                    log.accept("upload-ok");
                    return 0;
                },
                0L);
        flasher.runFlashForTest("/dev/ttyUSB0", "B", "mega-avr");

        JsonObject status = flasher.statusJson();
        assertEquals("success", status.get("state").getAsString());
        assertEquals(1, commands.size());
        assertEquals("atmega2560", commands.get(0).get(commands.get(0).indexOf("-p") + 1));
        assertEquals("wiring", commands.get(0).get(commands.get(0).indexOf("-c") + 1));
        assertEquals(List.of("PREFIX B\n"), port.writes);
        String log = status.getAsJsonArray("log").toString();
        assertTrue(log.contains("PREFIX B"));
        assertTrue(log.contains("ID B mega-avr"));
        assertEquals("success", status.get("stage").getAsString());
    }

    @Test
    void explainsABootloaderBaudMissThenRetries() throws Exception {
        Path cache = temp.resolve("cache");
        Files.createDirectories(cache);
        Path hex = cache.resolve("nano-avr.hex");
        Files.writeString(hex, ":020000040000FA\n:00000001FF\n", StandardCharsets.US_ASCII);
        Files.writeString(cache.resolve("manifest.json"), "{\n"
                + "  \"images\": [{\n"
                + "    \"id\": \"nano-avr\", \"version\": 1000, \"tool\": \"avrdude\",\n"
                + "    \"filename\": \"nano-avr.hex\", \"mcu\": \"atmega328p\",\n"
                + "    \"programmer\": \"arduino\", \"baud\": 57600, \"altBaud\": 115200,\n"
                + "    \"sha256\": \"" + FirmwareImageCatalog.sha256(hex) + "\"\n"
                + "  }]\n"
                + "}\n", StandardCharsets.UTF_8);
        Path avrdude = temp.resolve("avrdude-nano");
        Files.writeString(avrdude, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        assertTrue(avrdude.toFile().setExecutable(true));
        List<Integer> bauds = new ArrayList<>();
        List<String> writes = new ArrayList<>();
        SerialPortOps port = new SerialPortOps() {
            @Override
            public void open(String portName, int baudRate) {
            }

            @Override
            public void write(byte[] data) {
                writes.add(new String(data, StandardCharsets.UTF_8));
            }

            @Override
            public List<String> readLines(long timeoutMs) {
                if (writes.isEmpty()) {
                    return List.of("PanelsDCC Node A nano-avr READY");
                }
                return List.of("ID C nano-avr");
            }

            @Override
            public void close() {
            }
        };
        NodeFlasher flasher = new NodeFlasher(
                FirmwareImageCatalog.load(temp.resolve("no-shipped"), cache),
                avrdude.toString(),
                temp.resolve("esptool").toString(),
                () -> port,
                (command, log) -> {
                    int baud = Integer.parseInt(command.get(command.indexOf("-b") + 1));
                    bauds.add(baud);
                    if (baud == 57600) {
                        log.accept("Warning: attempt 1 of 10: not in sync: resp=0x00");
                        log.accept("Warning: attempt 10 of 10: not in sync: resp=0x00");
                        log.accept("Error: unable to open port /dev/cu.usbserial-1140 for programmer arduino");
                        log.accept("Avrdude done.  Thank you.");
                        return 1;
                    }
                    log.accept("16872 bytes of flash verified");
                    return 0;
                },
                0L);
        flasher.runFlashForTest("/dev/cu.usbserial-1140", "C", "nano-avr");
        JsonObject status = flasher.statusJson();
        assertEquals("success", status.get("state").getAsString());
        assertEquals("success", status.get("stage").getAsString());
        assertEquals(List.of(57600, 115200), bauds);
        String log = status.getAsJsonArray("log").toString();
        assertTrue(log.contains("did not answer at 57600"));
        assertTrue(log.contains("Writing again at 115200"));
        assertFalse(log.contains("attempt 10 of 10"));
        assertFalse(log.contains("unable to open port"));
        assertTrue(log.contains("bytes of flash verified"));
        assertEquals(List.of("PREFIX C\n"), writes);
    }

    @Test
    void esp32s3WritesTheBootloaderPortAfterTheFirstPortDrops() throws Exception {
        Path shipped = temp.resolve("s3");
        Files.createDirectories(shipped);
        Path app = shipped.resolve("nano-esp32.bin");
        Files.writeString(app, "app", StandardCharsets.US_ASCII);
        Files.writeString(shipped.resolve("manifest.json"), "{\n"
                + "  \"images\": [{\n"
                + "    \"id\": \"nano-esp32\", \"version\": 3, \"tool\": \"esptool\",\n"
                + "    \"chip\": \"esp32s3\", \"baud\": 460800,\n"
                + "    \"parts\": [{\"filename\": \"nano-esp32.bin\", \"offset\": \"0x10000\", \"sha256\": \""
                + FirmwareImageCatalog.sha256(app) + "\"}]\n"
                + "  }]\n"
                + "}\n", StandardCharsets.UTF_8);
        Path avrdude = temp.resolve("avrdude-s3");
        Path esptool = temp.resolve("esptool-s3");
        Files.writeString(avrdude, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        Files.writeString(esptool, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        assertTrue(avrdude.toFile().setExecutable(true));
        assertTrue(esptool.toFile().setExecutable(true));
        String running = "/dev/cu.usbmodem28848546E0682";
        String boot = "/dev/cu.usbmodem1401";
        // portPresent → before snapshot → wait sees bootloader → later resume on app port
        List<List<String>> listings = List.of(
                List.of(running),
                List.of(running),
                List.of(boot),
                List.of(running));
        int[] calls = {0};
        List<List<String>> commands = new ArrayList<>();
        List<Integer> opens = new ArrayList<>();
        SerialPortOps port = new SerialPortOps() {
            final List<String> writes = new ArrayList<>();

            @Override
            public void open(String portName, int baudRate) {
                opens.add(baudRate);
            }

            @Override
            public void write(byte[] data) {
                writes.add(new String(data, StandardCharsets.UTF_8));
            }

            @Override
            public List<String> readLines(long timeoutMs) {
                if (writes.isEmpty()) {
                    return List.of("PanelsDCC Node A nano-esp32 READY");
                }
                return List.of("ID A nano-esp32");
            }

            @Override
            public void close() {
            }
        };
        NodeFlasher flasher = new NodeFlasher(
                FirmwareImageCatalog.load(shipped, temp.resolve("s3-cache")),
                avrdude.toString(),
                esptool.toString(),
                () -> port,
                (command, log) -> {
                    commands.add(new ArrayList<>(command));
                    if (command.contains("flash_id")) {
                        if (command.contains(boot) && command.contains("no_reset")) {
                            log.accept("Chip type: ESP32-S3 (QFN56)");
                            log.accept("Detected flash size: 16MB");
                            return 0;
                        }
                        return 1;
                    }
                    return 0;
                },
                0L);
        flasher.setPortLister(() -> listings.get(Math.min(calls[0]++, listings.size() - 1)));
        flasher.runFlashForTest(running, "A", "nano-esp32");
        JsonObject status = flasher.statusJson();
        assertEquals("success", status.get("state").getAsString(), status.toString());
        assertTrue(opens.contains(1200));
        assertEquals(2, commands.size(), commands.toString());
        assertTrue(commands.get(0).contains("flash_id"));
        assertTrue(commands.get(0).contains("no_reset"));
        assertTrue(commands.get(0).contains(boot));
        assertTrue(commands.get(1).contains("write_flash"));
        assertTrue(commands.get(1).contains("no_reset"));
        assertTrue(commands.get(1).contains("watchdog_reset"));
        assertTrue(commands.get(1).contains(boot));
        assertTrue(commands.get(1).contains("esp32s3"));
        String log = status.getAsJsonArray("log").toString();
        assertTrue(log.contains("bootloader port"));
        assertTrue(log.contains("nothing was written") == false);
        assertTrue(log.contains("PREFIX A"));
    }

    @Test
    void busyPortStopsBeforeAvrdude() throws Exception {
        Path shipped = temp.resolve("busy-shipped");
        Files.createDirectories(shipped);
        Files.writeString(shipped.resolve("manifest.json"), "{\"images\":[]}\n", StandardCharsets.UTF_8);
        Path esptool = temp.resolve("esptool-busy");
        Path avrdude = temp.resolve("avrdude-busy");
        Files.writeString(esptool, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        Files.writeString(avrdude, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        assertTrue(esptool.toFile().setExecutable(true));
        assertTrue(avrdude.toFile().setExecutable(true));
        List<String> tools = new ArrayList<>();
        NodeFlasher flasher = new NodeFlasher(
                FirmwareImageCatalog.load(shipped, temp.resolve("busy-cache")),
                avrdude.toString(),
                esptool.toString(),
                () -> new SerialPortOps() {
                    @Override
                    public void open(String portName, int baudRate) throws IOException {
                        throw new IOException("Failed to open serial port: " + portName);
                    }

                    @Override
                    public void write(byte[] data) {
                    }

                    @Override
                    public List<String> readLines(long timeoutMs) {
                        return List.of();
                    }

                    @Override
                    public void close() {
                    }
                },
                (command, log) -> {
                    tools.add(command.get(0));
                    return 1;
                },
                0L);
        flasher.setPortLister(() -> List.of("/dev/cu.usbmodem28848546E0682"));
        flasher.runFlashForTest("/dev/cu.usbmodem28848546E0682", "A", NodeFlasher.DETECT);
        JsonObject status = flasher.statusJson();
        assertEquals("failed", status.get("state").getAsString(), status.toString());
        // Native USB Nano ESP32: fail on 1200-baud touch without calling esptool/avrdude.
        assertEquals(List.of(), tools);
        assertTrue(status.get("error").getAsString().contains("still open"), status.get("error").getAsString());
        assertFalse(status.getAsJsonArray("log").toString().contains("avrdude"));
    }

    @Test
    void refusesToWriteWhenNoImageMatchesTheChip() {
        FirmwareImageCatalog.Image mega = espImage("mega-esp32", "esp32", "--flash_size", "detect");
        FirmwareImageCatalog.Image nano = espImage("nano-esp32", "esp32s3", "--flash_size", "16MB");
        NodeFlasher.ImageChoice wrongFamily = NodeFlasher.chooseImage(mega, "esp32c3", "4MB", List.of(mega, nano));
        assertEquals(null, wrongFamily.image);
        assertTrue(wrongFamily.message.contains("Nothing was written"));
        assertTrue(wrongFamily.message.contains("ESP32-C3"));

        NodeFlasher.ImageChoice tooSmall = NodeFlasher.chooseImage(nano, "ESP32-S3", "8MB", List.of(nano));
        assertEquals(null, tooSmall.image);
        assertTrue(tooSmall.message.contains("Nothing was written"));

        NodeFlasher.ImageChoice switched = NodeFlasher.chooseImage(mega, "ESP32-S3", "16MB", List.of(mega, nano));
        assertEquals("nano-esp32", switched.image.id);
        assertTrue(switched.message.contains("Arduino Nano ESP32"));

        NodeFlasher.ImageChoice kept = NodeFlasher.chooseImage(nano, "esp32s3", "16MB", List.of(mega, nano));
        assertEquals("nano-esp32", kept.image.id);
        assertEquals(null, kept.message);

        NodeFlasher.ChipReport report = NodeFlasher.parseFlashId(
                "Detecting chip type... ESP32-S3\nChip type: ESP32-S3 (QFN56)\nDetected flash size: 16MB\n");
        assertEquals("esp32s3", report.chip);
        assertEquals("16MB", report.flashSize);
        assertEquals("esp32", NodeFlasher.normalizeChip("ESP32"));
        assertEquals("nano-avr", NodeFlasher.boardForSignature("0x1e950f"));
        assertEquals("mega-avr", NodeFlasher.boardForSignature("1E 98 01"));
        assertEquals(null, NodeFlasher.boardForSignature("1e9514"));
        assertEquals("1e9801", NodeFlasher.parseDeviceSignature(
                "avrdude: Device signature = 0x1e9801 (probably m2560)\n"));
    }

    private static FirmwareImageCatalog.Image espImage(String id, String chip, String... flashArgs) {
        return new FirmwareImageCatalog.Image(
                id, 1, "esptool", null, null, chip, 460800, null,
                List.of(flashArgs), List.of(), null, "shipped");
    }

    private static final class RecordingPort implements SerialPortOps {
        final List<String> writes = new ArrayList<>();

        @Override
        public void open(String portName, int baudRate) {
        }

        @Override
        public void write(byte[] data) {
            writes.add(new String(data, StandardCharsets.UTF_8));
        }

        @Override
        public List<String> readLines(long timeoutMs) {
            if (writes.isEmpty()) {
                return List.of("PanelsDCC Node A mega-avr READY");
            }
            return List.of("ID B mega-avr");
        }

        @Override
        public void close() {
        }
    }
}
