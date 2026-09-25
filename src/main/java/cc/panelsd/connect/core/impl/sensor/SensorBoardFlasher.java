package cc.panelsd.connect.core.impl.sensor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runs arduino-cli compile/upload for PanelsDCC Sensor Board sketches.
 */
public final class SensorBoardFlasher {

    public enum State {
        IDLE, PREPARING, COMPILING, UPLOADING, SUCCESS, FAILED
    }

    private final Path cliPath;
    private final Path dataDir;
    private final Path sketchSourceDir;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SensorBoardFlash");
        t.setDaemon(true);
        return t;
    });

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final List<String> log = new CopyOnWriteArrayList<>();
    private volatile String port;
    private volatile String prefix;
    private volatile String error;
    private volatile Consumer<Boolean> onComplete;

    public SensorBoardFlasher() {
        this(
                Paths.get(SensorBoardSupport.DEFAULT_CLI),
                Paths.get(SensorBoardSupport.DEFAULT_ARDUINO_DATA),
                Paths.get(SensorBoardSupport.DEFAULT_SKETCH_DIR)
        );
    }

    public SensorBoardFlasher(Path cliPath, Path dataDir, Path sketchSourceDir) {
        this.cliPath = cliPath;
        this.dataDir = dataDir;
        this.sketchSourceDir = sketchSourceDir;
    }

    public synchronized boolean startFlash(String portName, String boardPrefix, Consumer<Boolean> onComplete) {
        if (state.get() == State.PREPARING || state.get() == State.COMPILING || state.get() == State.UPLOADING) {
            return false;
        }
        String normalized = SensorBoardSupport.normalizePrefix(boardPrefix);
        if (!SensorBoardSupport.isValidPrefix(normalized)) {
            this.error = "Invalid board prefix: " + boardPrefix;
            this.state.set(State.FAILED);
            return false;
        }
        this.port = SensorBoardSupport.barePort(portName);
        this.prefix = normalized;
        this.error = null;
        this.log.clear();
        this.onComplete = onComplete;
        this.state.set(State.PREPARING);
        executor.submit(this::runFlash);
        return true;
    }

    private void runFlash() {
        Path tempRoot = null;
        boolean ok = false;
        try {
            if (!Files.isExecutable(cliPath) && !Files.isRegularFile(cliPath)) {
                fail("arduino-cli not found at " + cliPath
                        + " — run: sudo panelsdcc-connect-arduino-install");
                return;
            }
            // arduino-cli requires <sketchDir>/<sketchDir>.ino — keep the packaged folder name
            tempRoot = Files.createTempDirectory("panelsdcc-sensor-flash-");
            Path sketchDir = tempRoot.resolve("panelsdcc-sensor-board");
            appendLog("Preparing sketch with BOARD_PREFIX=" + prefix);
            SensorBoardSketchPrep.prepare(sketchSourceDir, sketchDir, prefix);

            state.set(State.COMPILING);
            appendLog("Compiling " + SensorBoardSupport.FQBN + " ...");
            int compileRc = runCli(sketchDir, "compile", "--fqbn", SensorBoardSupport.FQBN, sketchDir.toString());
            if (compileRc != 0) {
                fail("Compile failed (exit " + compileRc + ")");
                return;
            }

            state.set(State.UPLOADING);
            String device = SensorBoardSupport.devicePath(port);
            appendLog("Uploading to " + device + " ...");
            int uploadRc = runCli(sketchDir, "upload", "-p", device, "--fqbn", SensorBoardSupport.FQBN,
                    sketchDir.toString());
            if (uploadRc != 0) {
                fail("Upload failed (exit " + uploadRc + ")");
                return;
            }

            appendLog("Flash succeeded");
            state.set(State.SUCCESS);
            ok = true;
        } catch (Exception e) {
            fail(e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            if (tempRoot != null) {
                try {
                    deleteRecursive(tempRoot);
                } catch (Exception ignore) {
                }
            }
            Consumer<Boolean> cb = onComplete;
            onComplete = null;
            if (cb != null) {
                try {
                    cb.accept(ok);
                } catch (Exception ignore) {
                }
            }
        }
    }

    private int runCli(Path workDir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(cliPath.toString());
        for (String a : args) {
            cmd.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("ARDUINO_DIRECTORIES_DATA", dataDir.toString());
        env.put("ARDUINO_DIRECTORIES_DOWNLOADS", dataDir.resolve("staging").toString());
        env.put("ARDUINO_DIRECTORIES_USER", dataDir.resolve("user").toString());
        Process p = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendLog(line);
            }
        }
        return p.waitFor();
    }

    private void fail(String message) {
        this.error = message;
        appendLog("ERROR: " + message);
        state.set(State.FAILED);
    }

    private void appendLog(String line) {
        if (line == null) {
            return;
        }
        log.add(line);
        // Cap log size
        while (log.size() > 400) {
            log.remove(0);
        }
    }

    public JsonObject statusJson() {
        JsonObject o = new JsonObject();
        o.addProperty("state", state.get().name().toLowerCase());
        if (port != null) {
            o.addProperty("port", port);
        }
        if (prefix != null) {
            o.addProperty("prefix", prefix);
        }
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
        boolean cliOk = Files.isRegularFile(cliPath);
        o.addProperty("arduinoCli", cliOk);
        o.addProperty("cliPath", cliPath.toString());
        o.addProperty("dataDir", dataDir.toString());
        o.addProperty("sketchPath", sketchSourceDir.toString());
        boolean fqbn = false;
        if (cliOk) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        cliPath.toString(), "board", "details", "-b", SensorBoardSupport.FQBN);
                Map<String, String> env = pb.environment();
                env.put("ARDUINO_DIRECTORIES_DATA", dataDir.toString());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                // Drain
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    while (r.readLine() != null) {
                        // discard
                    }
                }
                fqbn = p.waitFor() == 0;
            } catch (Exception e) {
                fqbn = false;
            }
        }
        o.addProperty("fqbnReady", fqbn);
        o.addProperty("fqbn", SensorBoardSupport.FQBN);
        return o;
    }

    public boolean isBusy() {
        State s = state.get();
        return s == State.PREPARING || s == State.COMPILING || s == State.UPLOADING;
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walk(root)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignore) {
                    }
                });
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
