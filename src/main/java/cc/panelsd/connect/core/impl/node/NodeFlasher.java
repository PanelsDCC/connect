package cc.panelsd.connect.core.impl.node;

import cc.panelsd.connect.core.UsbSerialProbe;
import cc.panelsd.connect.core.UsbSerialProbe.SerialPortOps;
import cc.panelsd.connect.core.UsbSerialProbe.SerialPortOpsFactory;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Uploads a prebuilt node image with avrdude or esptool, then sends
 * {@code PREFIX {id}} so the board stores its letter without a recompile.
 */
public final class NodeFlasher {

    public static final String DETECT = "detect";

    public enum State {
        IDLE, PREPARING, UPLOADING, SUCCESS, FAILED
    }

    @FunctionalInterface
    interface CommandRunner {
        int run(List<String> command, Consumer<String> log) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface PortLister {
        List<String> list() throws IOException;
    }

    private volatile FirmwareImageCatalog catalog;
    private volatile String stage = "idle";
    private boolean notedSyncMiss;
    private final String avrdude;
    private final String esptool;
    private final SerialPortOpsFactory portFactory;
    private final CommandRunner runner;
    private final long prefixSettleMs;
    private PortLister portLister = NodeFlasher::listCalloutPorts;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NodeFlash");
        t.setDaemon(true);
        return t;
    });

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final List<String> log = new CopyOnWriteArrayList<>();
    private volatile String port;
    private volatile String prefix;
    private volatile String boardType = BoardProfile.NANO_AVR;
    private volatile boolean clearConfig;
    private volatile String error;
    private volatile Consumer<Boolean> onComplete;

    public NodeFlasher() {
        this(FirmwareImageCatalog.loadDefault(),
                resolveTool("avrdude"),
                resolveTool("esptool", "esptool.py"),
                UsbSerialProbe.JSerialCommPortOps::new,
                NodeFlasher::runProcess,
                1500L);
    }

    NodeFlasher(
            FirmwareImageCatalog catalog,
            String avrdude,
            String esptool,
            SerialPortOpsFactory portFactory,
            CommandRunner runner,
            long prefixSettleMs) {
        this.catalog = catalog;
        this.avrdude = avrdude;
        this.esptool = esptool;
        this.portFactory = portFactory;
        this.runner = runner;
        this.prefixSettleMs = prefixSettleMs;
    }

    void setPortLister(PortLister portLister) {
        this.portLister = portLister;
    }

    public static boolean isDetect(String boardTypeId) {
        return boardTypeId != null && DETECT.equalsIgnoreCase(boardTypeId.trim());
    }

    /**
     * Read the chip on {@code portName} without writing. Used so the UI can show
     * Nano vs Mega vs ESP32 before the user confirms flash.
     */
    public synchronized JsonObject identifyPort(String portName) {
        JsonObject out = new JsonObject();
        out.addProperty("port", portName == null ? "" : NodeSupport.barePort(portName));
        if (portName == null || portName.isBlank()) {
            out.addProperty("ok", false);
            out.addProperty("error", "port required");
            return out;
        }
        State current = state.get();
        if (current == State.PREPARING || current == State.UPLOADING) {
            out.addProperty("ok", false);
            out.addProperty("error", "Flash is already running");
            return out;
        }
        String device = NodeSupport.devicePath(portName);
        try {
            if (toolExists(esptool)) {
                Probe probe = identifyEsp(device);
                if (probe != null && probe.chip != null) {
                    ImageChoice choice = chooseImage(null, probe.chip, probe.flashSize, catalog.selectable());
                    out.addProperty("ok", choice.image != null);
                    out.addProperty("family", "esp");
                    out.addProperty("chip", prettyChip(probe.chip));
                    if (probe.flashSize != null) {
                        out.addProperty("flashSize", probe.flashSize);
                    }
                    if (choice.image != null) {
                        out.addProperty("boardType", choice.image.id);
                        BoardProfile profile = BoardProfile.get(choice.image.id);
                        out.addProperty("label", profile == null ? choice.image.id : profile.label);
                    } else {
                        out.addProperty("error", choice.message != null ? choice.message
                                : "No image matches this Espressif chip");
                    }
                    if (probe.bootloader) {
                        restartBoard(probe.port);
                    }
                    return out;
                }
            }
            if (toolExists(avrdude)) {
                String avrId = readAvrBoardQuiet(device);
                if (avrId != null) {
                    out.addProperty("ok", true);
                    out.addProperty("family", "avr");
                    out.addProperty("boardType", avrId);
                    BoardProfile profile = BoardProfile.get(avrId);
                    out.addProperty("label", profile == null ? avrId : profile.label);
                    FirmwareImageCatalog.Image image = catalog.select(avrId).orElse(null);
                    out.addProperty("imagePresent", image != null && image.filesPresent());
                    if (BoardProfile.NANO_AVR.equals(avrId) && (image == null || !image.filesPresent())) {
                        out.addProperty("needsUpload", true);
                    }
                    return out;
                }
            }
            out.addProperty("ok", false);
            out.addProperty("error", "Could not read a chip on this port");
            return out;
        } catch (Exception e) {
            out.addProperty("ok", false);
            out.addProperty("error", e.getMessage() != null ? e.getMessage() : "identify failed");
            return out;
        }
    }

    /** Like {@link #readAvrBoard} but does not mark the flasher failed. */
    private String readAvrBoardQuiet(String devicePath) throws IOException, InterruptedException {
        String[][] attempts = {
                {"arduino", "atmega328p", "115200"},
                {"arduino", "atmega328p", "57600"},
                {"wiring", "atmega2560", "115200"}
        };
        for (String[] attempt : attempts) {
            List<String> cmd = new ArrayList<>();
            cmd.add(avrdude);
            cmd.add("-c");
            cmd.add(attempt[0]);
            cmd.add("-p");
            cmd.add(attempt[1]);
            cmd.add("-P");
            cmd.add(devicePath);
            cmd.add("-b");
            cmd.add(attempt[2]);
            cmd.add("-n");
            StringBuilder captured = new StringBuilder();
            runner.run(cmd, line -> captured.append(line).append('\n'));
            String signature = parseDeviceSignature(captured.toString());
            if (signature == null) {
                continue;
            }
            return boardForSignature(signature);
        }
        return null;
    }

    /** Board chosen for the flash in progress, including one picked by reading the chip. */
    public String currentBoardType() {
        return boardType;
    }

    public FirmwareImageCatalog catalog() {
        return catalog;
    }

    /** @deprecated use {@link #startFlash(String, String, String, Consumer)} */
    @Deprecated
    public synchronized boolean startFlash(String portName, String boardPrefix, Consumer<Boolean> onComplete) {
        return startFlash(portName, boardPrefix, BoardProfile.NANO_AVR, false, onComplete);
    }

    public synchronized boolean startFlash(
            String portName, String boardPrefix, String boardTypeId, Consumer<Boolean> onComplete) {
        return startFlash(portName, boardPrefix, boardTypeId, false, onComplete);
    }

    public synchronized boolean startFlash(
            String portName,
            String boardPrefix,
            String boardTypeId,
            boolean clearConfig,
            Consumer<Boolean> onComplete) {
        if (isBusy()) {
            return false;
        }
        String normalized = NodeSupport.normalizePrefix(boardPrefix);
        if (!NodeSupport.isValidPrefix(normalized)) {
            this.error = "Invalid board prefix: " + boardPrefix;
            this.state.set(State.FAILED);
            return false;
        }
        this.port = NodeSupport.barePort(portName);
        this.prefix = normalized;
        this.boardType = isDetect(boardTypeId) ? DETECT : BoardProfile.normalize(boardTypeId);
        this.clearConfig = clearConfig;
        this.error = null;
        this.stage = "preparing";
        this.notedSyncMiss = false;
        this.log.clear();
        this.onComplete = onComplete;
        this.state.set(State.PREPARING);
        executor.submit(this::runFlash);
        return true;
    }

    /** Simulated flash for shim / UI rehearsal (no file upload). */
    public synchronized boolean startShimFlash(String prefix, String boardTypeId, Consumer<Boolean> onComplete) {
        if (isBusy()) {
            return false;
        }
        this.port = "virtual";
        this.prefix = NodeSupport.normalizePrefix(prefix);
        this.boardType = BoardProfile.normalize(boardTypeId);
        this.error = null;
        this.log.clear();
        this.onComplete = onComplete;
        this.state.set(State.PREPARING);
        executor.submit(() -> {
            boolean ok = false;
            try {
                appendLog("Shim flash for " + boardType + " prefix=" + this.prefix);
                state.set(State.UPLOADING);
                appendLog("Simulating upload (no image file)");
                Thread.sleep(200);
                appendLog("Shim flash succeeded");
                state.set(State.SUCCESS);
                ok = true;
            } catch (Exception e) {
                fail(e.getMessage() != null ? e.getMessage() : e.toString());
            } finally {
                finish(ok);
            }
        });
        return true;
    }

    /** Synchronous flash used by unit tests (no background thread). */
    void runFlashForTest(String portName, String boardPrefix, String boardTypeId) {
        this.port = NodeSupport.barePort(portName);
        this.prefix = NodeSupport.normalizePrefix(boardPrefix);
        this.boardType = isDetect(boardTypeId) ? DETECT : BoardProfile.normalize(boardTypeId);
        this.error = null;
        this.stage = "preparing";
        this.notedSyncMiss = false;
        this.log.clear();
        this.onComplete = null;
        runFlash();
    }

    private void runFlash() {
        boolean ok = false;
        try {
            stage = "preparing";
            state.set(State.PREPARING);
            String sessionPort = NodeSupport.devicePath(port);
            String device = sessionPort;
            boolean alreadyInBootloader = false;
            FirmwareImageCatalog.Image image;
            if (DETECT.equals(boardType)) {
                Resolved resolved = detectBoard(sessionPort);
                if (resolved == null) {
                    return;
                }
                image = resolved.image;
                device = resolved.device;
                alreadyInBootloader = resolved.bootloader;
            } else {
                image = catalog.select(boardType).orElse(null);
                if (image == null) {
                    if (BoardProfile.NANO_AVR.equals(BoardProfile.normalize(boardType))) {
                        fail("Nano AVR has no built-in image. Upload a builder zip first.");
                    } else {
                        fail("No firmware image for " + boardType
                                + " in " + catalog.shippedDir());
                    }
                    return;
                }
                appendLog("Checking image " + image.id + " version " + image.version + " (" + image.source + ").");
                if (image.configFile != null) {
                    appendLog("Config sidecar present (not applied): " + image.configFile.getFileName());
                }
                image.verifyHashes();
                String tool = "esptool".equals(image.tool) ? esptool : avrdude;
                if (!toolExists(tool)) {
                    fail(tool + " not found — install the distro package");
                    return;
                }
                if ("esptool".equals(image.tool)) {
                    appendLog("Reading the chip before anything is written.");
                    Probe probe = identifyEsp(sessionPort);
                    if (probe == null || probe.chip == null) {
                        if (probe != null && probe.bootloader) {
                            restartBoard(probe.port);
                        }
                        fail(espIdentifyFailureMessage(sessionPort, probe));
                        return;
                    }
                    String sizeNote = probe.flashSize == null ? "" : " with " + probe.flashSize + " of flash";
                    appendLog("This board is " + prettyChip(probe.chip) + sizeNote + ".");
                    ImageChoice choice = chooseImage(image, probe.chip, probe.flashSize, catalog.selectable());
                    if (choice.image == null) {
                        if (probe.bootloader) {
                            restartBoard(probe.port);
                        }
                        fail(choice.message);
                        return;
                    }
                    if (!choice.image.id.equals(image.id)) {
                        appendLog(choice.message);
                        choice.image.verifyHashes();
                        image = choice.image;
                        boardType = image.id;
                    }
                    device = probe.port;
                    alreadyInBootloader = probe.bootloader;
                }
            }
            final FirmwareImageCatalog.Image flashing = image;
            final int writeBaud = flashing.baud;
            List<String> cmd = alreadyInBootloader
                    ? flashing.uploadCommand(avrdude, esptool, device, writeBaud, "no_reset", "watchdog_reset")
                    : flashing.uploadCommand(avrdude, esptool, device);
            boolean altBaud = flashing.altBaud != null && flashing.altBaud != writeBaud && "avrdude".equals(flashing.tool);
            boolean replug = !alreadyInBootloader
                    && "esptool".equals(flashing.tool)
                    && flashing.chip != null
                    && flashing.chip.startsWith("esp32s");
            Set<String> portsBefore = replug ? new HashSet<>(portLister.list()) : Set.of();
            state.set(State.UPLOADING);
            stage = "writing";
            appendLog("Writing the image to " + device + " at " + writeBaud + " baud.");
            if (altBaud) {
                appendLog("This board answers at either " + writeBaud + " or " + flashing.altBaud
                        + ". A run of “not in sync” lines means that rate is the wrong one, and the image has not been written yet.");
            }
            notedSyncMiss = false;
            int rc = runner.run(cmd, line -> appendProgrammerLine(line, writeBaud, altBaud));
            if (rc != 0 && replug) {
                String next = waitForNewPort(portsBefore, 8000L);
                if (next != null) {
                    stage = "retrying";
                    appendLog("The board left " + device + " and came back as " + next
                            + ". That is the bootloader port. Writing there.");
                    notedSyncMiss = false;
                    List<String> retry = flashing.uploadCommand(
                            avrdude, esptool, next, writeBaud, "no_reset", "watchdog_reset");
                    rc = runner.run(retry, line -> appendProgrammerLine(line, writeBaud, false));
                    if (rc == 0) {
                        device = next;
                    }
                }
            }
            if (rc != 0 && altBaud) {
                stage = "retrying";
                appendLog("No answer at " + writeBaud + " baud. Writing again at " + flashing.altBaud + " baud.");
                notedSyncMiss = false;
                List<String> retry = flashing.uploadCommand(avrdude, esptool, device, flashing.altBaud);
                rc = runner.run(retry, line -> appendProgrammerLine(line, flashing.altBaud, false));
            }
            if (rc != 0) {
                fail("Upload failed (exit " + rc + ")");
                return;
            }
            if ("esptool".equals(flashing.tool) && !sessionPort.equals(device)) {
                appendLog("Waiting for the board to restart on " + sessionPort + ".");
                String back = waitForPort(sessionPort, 8000L);
                if (back != null) {
                    device = back;
                }
            }
            stage = "prefix";
            appendLog("Image verified. Sending prefix " + prefix + ".");
            applyPrefix(device, prefix);
            // Baked Nano AVR images have no mutable CFG store to wipe.
            if (clearConfig && !BoardProfile.NANO_AVR.equals(BoardProfile.normalize(boardType))) {
                stage = "clearing";
                appendLog("Clearing saved layout on the board.");
                applyClearConfig(device);
            } else if (clearConfig) {
                appendLog("Skipping clear layout — Nano AVR images are baked in.");
            }
            stage = "success";
            appendLog("Flash finished.");
            state.set(State.SUCCESS);
            ok = true;
        } catch (Exception e) {
            fail(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            finish(ok);
        }
    }

    static final class ChipReport {
        final String chip;
        final String flashSize;

        ChipReport(String chip, String flashSize) {
            this.chip = chip;
            this.flashSize = flashSize;
        }
    }

    static final class ImageChoice {
        final FirmwareImageCatalog.Image image;
        final String message;

        ImageChoice(FirmwareImageCatalog.Image image, String message) {
            this.image = image;
            this.message = message;
        }
    }

    private static final class Probe {
        String chip;
        String flashSize;
        String port;
        boolean bootloader;
        boolean portBusy;
        boolean connectFailed;
    }

    /**
     * The selected image is used when its chip and flash size match.
     * One other matching image may be substituted. Anything else is refused.
     */
    static ImageChoice chooseImage(
            FirmwareImageCatalog.Image selected,
            String detectedChip,
            String detectedSize,
            List<FirmwareImageCatalog.Image> available) {
        List<FirmwareImageCatalog.Image> matches = new ArrayList<>();
        if (available != null) {
            for (FirmwareImageCatalog.Image candidate : available) {
                if (candidate != null && imageMatches(candidate, detectedChip, detectedSize)) {
                    matches.add(candidate);
                }
            }
        }
        if (selected != null && matches.stream().anyMatch(match -> match.id.equals(selected.id))) {
            return new ImageChoice(selected, null);
        }
        if (matches.size() == 1) {
            FirmwareImageCatalog.Image only = matches.get(0);
            return new ImageChoice(only, "Writing the " + boardName(only) + " image, because that is the one for this chip.");
        }
        String described = prettyChip(normalizeChip(detectedChip));
        if (detectedSize != null && !detectedSize.isBlank()) {
            described = described + " with " + FirmwareImageCatalog.Image.normalizeFlashSize(detectedSize) + " of flash";
        }
        if (matches.isEmpty()) {
            return new ImageChoice(null, "This board is " + described
                    + ". None of the installed images match it. Nothing was written.");
        }
        StringBuilder names = new StringBuilder();
        for (FirmwareImageCatalog.Image match : matches) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(boardName(match));
        }
        return new ImageChoice(null, "This board is " + described
                + " and matches more than one image (" + names + "). Choose the board in Connect. Nothing was written.");
    }

    static ChipReport parseFlashId(String text) {
        String chip = null;
        String size = null;
        if (text != null) {
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (lower.startsWith("chip type:")) {
                    chip = normalizeChip(trimmed.substring("chip type:".length()));
                } else if (lower.startsWith("chip is ")) {
                    chip = normalizeChip(trimmed.substring("chip is ".length()));
                } else if (chip == null && lower.startsWith("detecting chip type...")) {
                    String rest = trimmed.substring("detecting chip type...".length()).trim();
                    if (!rest.isEmpty()) {
                        chip = normalizeChip(rest);
                    }
                }
                int sizeAt = lower.indexOf("detected flash size:");
                if (sizeAt >= 0) {
                    size = FirmwareImageCatalog.Image.normalizeFlashSize(trimmed.substring(sizeAt + "detected flash size:".length()));
                }
            }
        }
        return new ChipReport(chip, size);
    }

    private static boolean imageMatches(FirmwareImageCatalog.Image image, String detectedChip, String detectedSize) {
        if (!"esptool".equals(image.tool) || !sameChip(image.chip, detectedChip)) {
            return false;
        }
        String required = image.requiredFlashSize();
        if (required == null) {
            return true;
        }
        String found = FirmwareImageCatalog.Image.normalizeFlashSize(detectedSize);
        return required.equals(found);
    }

    private static boolean sameChip(String imageChip, String detectedChip) {
        return normalizeChip(imageChip).equals(normalizeChip(detectedChip));
    }

    static String normalizeChip(String raw) {
        if (raw == null) {
            return "";
        }
        String chip = raw.trim().toLowerCase(Locale.ROOT);
        int space = chip.indexOf(' ');
        if (space > 0) {
            chip = chip.substring(0, space);
        }
        int paren = chip.indexOf('(');
        if (paren > 0) {
            chip = chip.substring(0, paren);
        }
        return chip.replace("-", "");
    }

    static String prettyChip(String normalized) {
        String chip = normalizeChip(normalized);
        if (chip.isEmpty()) {
            return "an unknown chip";
        }
        if ("esp32".equals(chip)) {
            return "an ESP32";
        }
        if (chip.startsWith("esp32") && chip.length() > 5) {
            return "an ESP32-" + chip.substring(5).toUpperCase(Locale.ROOT);
        }
        return "a " + chip;
    }

    private static String boardName(FirmwareImageCatalog.Image image) {
        BoardProfile profile = BoardProfile.get(image.id);
        return profile == null ? image.id : profile.label;
    }

    private static final class Resolved {
        final FirmwareImageCatalog.Image image;
        final String device;
        final boolean bootloader;

        Resolved(FirmwareImageCatalog.Image image, String device, boolean bootloader) {
            this.image = image;
            this.device = device;
            this.bootloader = bootloader;
        }
    }

    /** Read the chip on the port and pick the one image that matches. Nothing is written here. */
    private Resolved detectBoard(String sessionPort) throws IOException, InterruptedException {
        appendLog("Reading the chip on this port. Nothing is written until it matches an image.");
        Probe probe = null;
        if (toolExists(esptool)) {
            probe = identifyEsp(sessionPort);
        }
        if (probe != null && probe.chip != null) {
            String sizeNote = probe.flashSize == null ? "" : " with " + probe.flashSize + " of flash";
            appendLog("This board is " + prettyChip(probe.chip) + sizeNote + ".");
            ImageChoice choice = chooseImage(null, probe.chip, probe.flashSize, catalog.selectable());
            if (choice.image == null) {
                if (probe.bootloader) {
                    restartBoard(probe.port);
                }
                fail(choice.message);
                return null;
            }
            if (choice.message != null) {
                appendLog(choice.message);
            }
            choice.image.verifyHashes();
            boardType = choice.image.id;
            appendLog("Checking image " + choice.image.id + " version " + choice.image.version
                    + " (" + choice.image.source + ").");
            return new Resolved(choice.image, probe.port, probe.bootloader);
        }
        if (probe != null && probe.portBusy && probe.chip == null) {
            fail("This port is still open, so nothing was written.");
            return null;
        }
        // Native-USB ESP32 (Nano ESP32) needs the bootloader; CH340 AVR Nanos get the same
        // esptool silence, so fall through to avrdude instead of the double-tap advice.
        boolean nativeEspPort = nativeUsbAppPort(sessionPort) || likelyBootloaderPort(sessionPort)
                || (probe != null && (nativeUsbAppPort(probe.port) || likelyBootloaderPort(probe.port)));
        if (probe != null && probe.connectFailed && probe.chip == null && nativeEspPort) {
            if (probe.bootloader) {
                restartBoard(probe.port);
                fail("Could not read the chip on this board, so nothing was written.");
            } else {
                fail("This board is running its program, so the flasher got no answer. "
                        + "Double-tap the reset button. The port name changes. "
                        + "Choose that port and flash again. Nothing was written.");
            }
            return null;
        }
        if (probe != null && probe.connectFailed && probe.chip == null && !nativeEspPort) {
            appendLog("No Espressif chip on this port. Checking for an AVR board.");
        }
        if (probe != null && probe.bootloader && probe.chip == null && nativeEspPort) {
            restartBoard(probe.port);
            fail("Could not read the chip on this board, so nothing was written.");
            return null;
        }
        if (!toolExists(avrdude)) {
            fail("Could not read the chip on this board, so nothing was written.");
            return null;
        }
        String avrId = readAvrBoard(sessionPort);
        if (avrId == null) {
            fail("Could not recognise this board, so nothing was written.");
            return null;
        }
        if (avrId.isEmpty()) {
            return null;
        }
        FirmwareImageCatalog.Image image = catalog.select(avrId).orElse(null);
        if (image == null) {
            if (BoardProfile.NANO_AVR.equals(avrId)) {
                fail("This is an Arduino Nano. Upload its image zip from panelsDCC-Nodes first. Nothing was written.");
            } else {
                fail("No image is installed for " + avrId + ". Nothing was written.");
            }
            return null;
        }
        image.verifyHashes();
        boardType = image.id;
        BoardProfile profile = BoardProfile.get(image.id);
        appendLog("This board is " + (profile == null ? image.id : profile.label) + ".");
        appendLog("Checking image " + image.id + " version " + image.version + " (" + image.source + ").");
        return new Resolved(image, sessionPort, false);
    }

    /**
     * @return the board id, {@code ""} when the chip was read but is not supported (failure already recorded),
     *         or null when the port did not answer
     */
    private String readAvrBoard(String devicePath) throws IOException, InterruptedException {
        String[][] attempts = {
                {"arduino", "atmega328p", "115200"},
                {"arduino", "atmega328p", "57600"},
                {"wiring", "atmega2560", "115200"}
        };
        for (String[] attempt : attempts) {
            List<String> cmd = new ArrayList<>();
            cmd.add(avrdude);
            cmd.add("-c");
            cmd.add(attempt[0]);
            cmd.add("-p");
            cmd.add(attempt[1]);
            cmd.add("-P");
            cmd.add(devicePath);
            cmd.add("-b");
            cmd.add(attempt[2]);
            cmd.add("-n");
            StringBuilder captured = new StringBuilder();
            runner.run(cmd, line -> {
                captured.append(line).append('\n');
                appendLog(line);
            });
            String signature = parseDeviceSignature(captured.toString());
            if (signature == null) {
                continue;
            }
            String id = boardForSignature(signature);
            if (id == null) {
                fail("This chip (signature " + signature + ") is not one Connect can program. Nothing was written.");
                return "";
            }
            return id;
        }
        return null;
    }

    static String parseDeviceSignature(String text) {
        if (text == null) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int at = lower.indexOf("device signature");
        if (at < 0) {
            return null;
        }
        int eq = text.indexOf('=', at);
        if (eq < 0) {
            return null;
        }
        String rest = text.substring(eq + 1).trim();
        int end = 0;
        while (end < rest.length()) {
            char c = rest.charAt(end);
            if (Character.isWhitespace(c) || c == '(') {
                break;
            }
            end++;
        }
        String signature = rest.substring(0, end).replace("0x", "").replace("0X", "").replace(" ", "");
        return signature.isEmpty() ? null : signature.toLowerCase(Locale.ROOT);
    }

    static String boardForSignature(String signature) {
        String hex = signature == null ? "" : signature.toLowerCase(Locale.ROOT).replace("0x", "").replace(" ", "");
        if ("1e950f".equals(hex)) {
            return BoardProfile.NANO_AVR;
        }
        if ("1e9801".equals(hex)) {
            return BoardProfile.MEGA_AVR;
        }
        return null;
    }

    private String waitForBootPort(Set<String> before, String original, long timeoutMs)
            throws InterruptedException, IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long stayedSince = -1L;
        while (true) {
            List<String> now = portLister.list();
            for (String candidate : now) {
                if (!before.contains(candidate)) {
                    return candidate;
                }
            }
            long time = System.currentTimeMillis();
            if (original != null && now.contains(original)) {
                if (stayedSince < 0L) {
                    stayedSince = time;
                }
                if (time - stayedSince >= 1200L) {
                    return null;
                }
            } else {
                stayedSince = -1L;
            }
            if (time >= deadline) {
                return null;
            }
            Thread.sleep(200L);
        }
    }

    private Probe identifyEsp(String sessionPort) throws IOException, InterruptedException {
        Probe probe = new Probe();
        probe.port = sessionPort;
        if (likelyBootloaderPort(sessionPort)) {
            StringBuilder captured = new StringBuilder();
            runIdentify(sessionPort, "no_reset", captured, probe);
            if (probe.chip != null) {
                probe.bootloader = true;
                return probe;
            }
        }
        // Soft BOOTLOADER (or prior reset) may already have dropped the app port.
        if (nativeUsbAppPort(sessionPort) && !portPresent(sessionPort)) {
            appendLog("The board left " + sessionPort + ". Looking for the bootloader port.");
            String boot = waitForAnyBootloaderPort(12000L);
            if (boot != null) {
                return identifyOnBootloader(probe, boot);
            }
            probe.connectFailed = true;
            return probe;
        }
        if (nativeUsbAppPort(sessionPort)) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                appendLog(attempt == 1
                        ? "Restarting the board into the bootloader."
                        : "Retrying 1200 baud reset (" + attempt + "/3)…");
                Set<String> before = new HashSet<>(portLister.list());
                if (!touch1200(sessionPort)) {
                    probe.portBusy = true;
                    probe.connectFailed = true;
                    return probe;
                }
                String boot = waitForBootloaderPort(sessionPort, before);
                if (boot != null) {
                    return identifyOnBootloader(probe, boot);
                }
            }
            // Running USB-CDC firmware almost never answers default_reset — don't spam esptool on it.
            probe.connectFailed = true;
            return probe;
        }
        StringBuilder captured = new StringBuilder();
        runIdentify(sessionPort, "default_reset", captured, probe);
        if (probe.chip == null && portOpenFailed(captured.toString())) {
            appendLog("The port was busy. Waiting a moment and trying again.");
            Thread.sleep(400L);
            captured.setLength(0);
            runIdentify(probe.port, probe.bootloader ? "no_reset" : "default_reset", captured, probe);
            if (probe.chip == null && portOpenFailed(captured.toString())) {
                probe.portBusy = true;
            }
        }
        if (probe.chip == null && espressifConnectFailed(captured.toString())) {
            probe.connectFailed = true;
        }
        return probe;
    }

    private Probe identifyOnBootloader(Probe probe, String boot) throws IOException, InterruptedException {
        probe.port = boot;
        probe.bootloader = true;
        Thread.sleep(400L);
        StringBuilder captured = new StringBuilder();
        runIdentify(boot, "no_reset", captured, probe);
        if (probe.chip == null) {
            probe.connectFailed = true;
            if (espressifConnectFailed(captured.toString())) {
                appendLog("esptool got no answer on the bootloader port.");
            }
        }
        return probe;
    }

    private boolean portPresent(String port) throws IOException {
        String path = NodeSupport.devicePath(port);
        String bare = NodeSupport.barePort(port);
        for (String p : portLister.list()) {
            if (p.equals(path) || bare.equals(NodeSupport.barePort(p))) {
                return true;
            }
        }
        return false;
    }

    /** After soft BOOTLOADER the app name is gone — accept any short usbmodem (or brand-new) port. */
    private String waitForAnyBootloaderPort(long timeoutMs) throws InterruptedException, IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (String candidate : portLister.list()) {
                if (likelyBootloaderPort(candidate)) {
                    appendLog("Found bootloader port " + candidate + ".");
                    return candidate;
                }
            }
            Thread.sleep(200L);
        }
        appendLog("No bootloader port appeared.");
        return null;
    }

    /**
     * After a 1200 baud touch, wait for the Nano ESP32 bootloader port.
     * Do not abort early if the app port lingers — USB re-enumeration often takes several seconds.
     */
    private String waitForBootloaderPort(String sessionPort, Set<String> before)
            throws InterruptedException, IOException {
        String original = NodeSupport.devicePath(sessionPort);
        String originalBare = NodeSupport.barePort(sessionPort);
        // Close triggers the reset; give the stack a beat before watching ports.
        Thread.sleep(500L);
        long deadline = System.currentTimeMillis() + 12000L;
        boolean sawLeave = false;
        while (System.currentTimeMillis() < deadline) {
            List<String> now = portLister.list();
            for (String candidate : now) {
                if (!before.contains(candidate)) {
                    appendLog("The board left " + sessionPort + " and came back as " + candidate
                            + ". That is the bootloader port.");
                    return candidate;
                }
            }
            boolean present = false;
            for (String p : now) {
                if (p.equals(original) || originalBare.equals(NodeSupport.barePort(p))) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                sawLeave = true;
            } else if (sawLeave) {
                appendLog("The board came back on " + sessionPort + " after reset (bootloader).");
                return original;
            }
            Thread.sleep(200L);
        }
        appendLog("The board did not switch to a bootloader port after the 1200 baud reset.");
        return null;
    }

    /** @return false when the port could not be opened for the 1200 baud touch */
    private boolean touch1200(String port) throws InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= 6; attempt++) {
            SerialPortOps serial = portFactory.create();
            try {
                serial.open(port, 1200);
                Thread.sleep(150L);
                return true;
            } catch (Exception e) {
                last = e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
                appendLog("Waiting for the port to free before reset (" + attempt + "/6)…");
                Thread.sleep(350L);
            } finally {
                serial.closeQuietly();
            }
        }
        appendLog("Could not ask the board to restart: "
                + (last != null && last.getMessage() != null ? last.getMessage() : "port busy"));
        return false;
    }

    private static String espIdentifyFailureMessage(String sessionPort, Probe probe) {
        if (probe != null && probe.portBusy) {
            return "This port is still open, so nothing was written.";
        }
        if (nativeUsbAppPort(sessionPort) || (probe != null && nativeUsbAppPort(probe.port))) {
            return "Could not put this board into the bootloader, so nothing was written. "
                    + "Double-tap the reset button until the port name changes, choose that port, and flash again.";
        }
        return "Could not read the chip on this board, so nothing was written.";
    }

    /** Short name the ROM bootloader uses, such as {@code cu.usbmodem1401}. */
    static boolean likelyBootloaderPort(String port) {
        String bare = NodeSupport.barePort(port);
        if (bare == null) {
            return false;
        }
        return bare.matches("(?i)(?:cu|tty)\\.usbmodem\\d{2,6}");
    }

    /** App port of a native-USB board, such as {@code cu.usbmodem28848546E0682}. A short bootloader name is left alone. */
    static boolean nativeUsbAppPort(String port) {
        String bare = NodeSupport.barePort(port);
        if (bare == null) {
            return false;
        }
        return bare.matches("(?i)(?:cu|tty)\\.usbmodem[0-9A-F]{8,}.*");
    }

    static boolean espressifConnectFailed(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("no serial data received")
                || lower.contains("failed to connect to espressif");
    }

    private int runIdentify(String sessionPort, String beforeReset, StringBuilder captured, Probe probe)
            throws IOException, InterruptedException {
        Set<String> before = new HashSet<>(portLister.list());
        int rc = runner.run(identifyCommand(sessionPort, beforeReset), line -> captureIdentify(captured, line));
        if ((rc != 0 || parseFlashId(captured.toString()).chip == null) && !portOpenFailed(captured.toString())) {
            String next = waitForBootPort(before, sessionPort, 8000L);
            if (next != null) {
                appendLog("The board left " + sessionPort + " and came back as " + next
                        + ". That is the bootloader port.");
                captured.setLength(0);
                rc = runner.run(identifyCommand(next, "no_reset"), line -> captureIdentify(captured, line));
                probe.port = next;
                probe.bootloader = true;
            }
        }
        ChipReport report = parseFlashId(captured.toString());
        if (rc == 0) {
            probe.chip = report.chip;
            probe.flashSize = report.flashSize;
        }
        return rc;
    }

    static boolean portOpenFailed(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("port is busy")
                || lower.contains("could not open")
                || lower.contains("resource temporarily unavailable");
    }

    private void captureIdentify(StringBuilder captured, String line) {
        captured.append(line).append('\n');
        appendLog(line);
    }

    private List<String> identifyCommand(String devicePath, String beforeReset) {
        List<String> cmd = new ArrayList<>();
        cmd.add(esptool);
        cmd.add("--port");
        cmd.add(devicePath);
        cmd.add("--before");
        cmd.add(beforeReset);
        cmd.add("--after");
        cmd.add("no_reset");
        cmd.add("flash_id");
        return cmd;
    }

    private void restartBoard(String devicePath) {
        appendLog("Leaving the bootloader so the program already on the board can start again.");
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(esptool);
            cmd.add("--port");
            cmd.add(devicePath);
            cmd.add("--before");
            cmd.add("no_reset");
            cmd.add("--after");
            cmd.add("watchdog_reset");
            cmd.add("run");
            runner.run(cmd, this::appendLog);
        } catch (Exception e) {
            appendLog(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    static List<String> listCalloutPorts() throws IOException {
        List<String> out = new ArrayList<>();
        Path dev = Paths.get("/dev");
        if (!Files.isDirectory(dev)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dev)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.startsWith("cu.usb") || name.startsWith("ttyUSB") || name.startsWith("ttyACM")) {
                    out.add(entry.toString());
                }
            }
        }
        return out;
    }

    private String waitForNewPort(Set<String> before, long timeoutMs) throws InterruptedException, IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            for (String candidate : portLister.list()) {
                if (!before.contains(candidate)) {
                    return candidate;
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                return null;
            }
            Thread.sleep(200L);
        }
    }

    private String waitForPort(String device, long timeoutMs) throws InterruptedException, IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (portLister.list().contains(device)) {
                return device;
            }
            if (System.currentTimeMillis() >= deadline) {
                return null;
            }
            Thread.sleep(200L);
        }
    }

    private void applyPrefix(String device, String boardPrefix) throws IOException, InterruptedException {
        if (prefixSettleMs > 0) {
            Thread.sleep(prefixSettleMs);
        }
        SerialPortOps serial = openNodeSerial(device);
        try {
            List<String> boot = serial.readLines(4000L);
            for (String line : boot) {
                appendLog(line);
            }
            String cmd = prefixCommand(boardPrefix);
            appendLog(">> " + cmd.trim());
            serial.write(cmd.getBytes(StandardCharsets.UTF_8));
            if (!confirmed(serial, boardPrefix)) {
                serial.write("ID?\n".getBytes(StandardCharsets.UTF_8));
                appendLog(">> ID?");
                if (!confirmed(serial, boardPrefix)) {
                    throw new IOException("Board did not confirm prefix " + boardPrefix);
                }
            }
        } finally {
            serial.closeQuietly();
        }
    }

    private void applyClearConfig(String device) throws IOException, InterruptedException {
        SerialPortOps serial = openNodeSerial(device);
        try {
            List<String> boot = serial.readLines(1500L);
            for (String line : boot) {
                appendLog(line);
            }
            appendLog(">> CFG CLEAR");
            serial.write("CFG CLEAR\n".getBytes(StandardCharsets.UTF_8));
            List<String> reply = serial.readLines(2000L);
            boolean ok = false;
            for (String line : reply) {
                appendLog(line);
                String trimmed = line.trim();
                if (trimmed.equalsIgnoreCase("CFG {}") || trimmed.equalsIgnoreCase("OK")) {
                    ok = true;
                }
            }
            if (!ok) {
                throw new IOException("Board did not confirm CFG CLEAR");
            }
        } finally {
            serial.closeQuietly();
        }
    }

    private SerialPortOps openNodeSerial(String device) throws IOException, InterruptedException {
        SerialPortOps serial = portFactory.create();
        IOException last = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                serial.open(device, UsbSerialProbe.NODE_BAUD);
                return serial;
            } catch (IOException e) {
                last = e;
                serial.closeQuietly();
                serial = portFactory.create();
                Thread.sleep(400L);
            }
        }
        throw last != null ? last : new IOException("Could not open " + device);
    }

    private boolean confirmed(SerialPortOps serial, String boardPrefix) throws IOException {
        List<String> reply = serial.readLines(2000L);
        boolean ok = false;
        for (String line : reply) {
            appendLog(line);
            if (idConfirms(line, boardPrefix)) {
                ok = true;
            }
        }
        return ok;
    }

    /** Line written after a successful upload. */
    public static String prefixCommand(String boardPrefix) {
        return "PREFIX " + boardPrefix + "\n";
    }

    /** {@code ID B nano-avr} confirms prefix {@code B}; {@code ID AB} does not. */
    public static boolean idConfirms(String line, String boardPrefix) {
        if (line == null || boardPrefix == null) {
            return false;
        }
        String trimmed = line.trim();
        if (trimmed.length() < 4 || !trimmed.regionMatches(true, 0, "ID ", 0, 3)) {
            return false;
        }
        String rest = trimmed.substring(3).trim();
        int sp = rest.indexOf(' ');
        String got = sp < 0 ? rest : rest.substring(0, sp);
        return boardPrefix.equalsIgnoreCase(got);
    }

    private static int runProcess(List<String> command, Consumer<String> log) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.accept(line);
            }
        }
        return p.waitFor();
    }

    static String resolveTool(String... names) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            pathEnv = "";
        }
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            Path direct = Paths.get(name);
            if (direct.isAbsolute() && Files.isExecutable(direct)) {
                return direct.toString();
            }
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                if (dir.isEmpty()) {
                    continue;
                }
                Path candidate = Paths.get(dir, name);
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return names.length == 0 ? "" : names[0];
    }

    private static boolean toolExists(String tool) {
        if (tool == null || tool.isBlank()) {
            return false;
        }
        Path p = Paths.get(tool);
        if (p.isAbsolute()) {
            return Files.isExecutable(p);
        }
        return Files.isExecutable(Paths.get(resolveTool(tool)));
    }

    private void finish(boolean ok) {
        Consumer<Boolean> cb = onComplete;
        onComplete = null;
        if (cb != null) {
            try {
                cb.accept(ok);
            } catch (Exception ignore) {
            }
        }
    }

    /**
     * avrdude prints ten “not in sync” warnings when the bootloader baud is wrong.
     * Keep one explanation and the real progress lines.
     */
    private void appendProgrammerLine(String line, int baud, boolean allowRetry) {
        if (line == null) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.startsWith("Warning: attempt ") && trimmed.contains("not in sync")) {
            if (!notedSyncMiss) {
                notedSyncMiss = true;
                appendLog("Bootloader did not answer at " + baud
                        + " baud. The “not in sync” warnings are that mismatch.");
            }
            return;
        }
        if (allowRetry && trimmed.startsWith("Error:") && trimmed.contains("unable to open port")) {
            return;
        }
        if (allowRetry && notedSyncMiss && trimmed.startsWith("Avrdude done")) {
            return;
        }
        appendLog(line);
    }

    private void fail(String message) {
        this.error = message;
        this.stage = "failed";
        appendLog("ERROR: " + message);
        state.set(State.FAILED);
    }

    private void appendLog(String line) {
        if (line == null) {
            return;
        }
        log.add(line);
        while (log.size() > 400) {
            log.remove(0);
        }
    }

    public JsonObject statusJson() {
        JsonObject o = new JsonObject();
        o.addProperty("state", state.get().name().toLowerCase());
        o.addProperty("stage", stage);
        if (port != null) {
            o.addProperty("port", port);
        }
        if (prefix != null) {
            o.addProperty("prefix", prefix);
        }
        if (boardType != null) {
            o.addProperty("boardType", boardType);
        }
        o.addProperty("clearConfig", clearConfig);
        if (error != null) {
            o.addProperty("error", error);
        }
        JsonArray arr = new JsonArray();
        for (String line : log) {
            arr.add(line);
        }
        o.add("log", arr);
        return o;
    }

    public JsonObject toolchainJson() {
        JsonObject o = new JsonObject();
        o.addProperty("flashMode", "prebuilt");
        o.addProperty("imagesDir", catalog.shippedDir().toString());
        o.addProperty("cacheDir", catalog.cacheDir().toString());
        boolean avrdudeOk = toolExists(avrdude);
        boolean esptoolOk = toolExists(esptool);
        o.addProperty("avrdude", avrdudeOk);
        o.addProperty("esptool", esptoolOk);
        o.addProperty("avrdudePath", avrdude);
        o.addProperty("esptoolPath", esptool);
        JsonArray profiles = new JsonArray();
        for (BoardProfile p : BoardProfile.all()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", p.id);
            row.addProperty("label", p.label);
            row.addProperty("defaultTransport", p.defaultTransport);
            FirmwareImageCatalog.Image image = catalog.select(p.id).orElse(null);
            row.addProperty("requiresUpload", BoardProfile.NANO_AVR.equals(p.id));
            row.addProperty("imagePresent", image != null && image.filesPresent());
            if (image != null) {
                row.addProperty("version", image.version);
                row.addProperty("tool", image.tool);
                row.addProperty("source", image.source);
                row.addProperty("configPresent", image.configFile != null && Files.isRegularFile(image.configFile));
            }
            profiles.add(row);
        }
        o.add("profiles", profiles);
        return o;
    }

    /** Store a builder zip in the image cache and use it for the next flash. */
    public synchronized JsonObject installImageZip(byte[] zipBytes) throws IOException {
        FirmwareImageZip.Installed installed = FirmwareImageZip.install(catalog, zipBytes);
        catalog = installed.catalog;
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        JsonArray images = new JsonArray();
        for (String id : installed.ids) {
            FirmwareImageCatalog.Image image = catalog.select(id).orElse(null);
            if (image == null) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("id", image.id);
            row.addProperty("version", image.version);
            row.addProperty("source", image.source);
            images.add(row);
        }
        out.add("images", images);
        return out;
    }

    public boolean isBusy() {
        State s = state.get();
        return s == State.PREPARING || s == State.UPLOADING;
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
