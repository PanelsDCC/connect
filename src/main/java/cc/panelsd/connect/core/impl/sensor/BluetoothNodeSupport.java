package cc.panelsd.connect.core.impl.sensor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * BlueZ helpers for ESP32 Classic SPP pairing on Linux (Raspberry Pi).
 * Desktop macOS returns unavailable — use shim boards for UI work.
 */
public final class BluetoothNodeSupport {

    private BluetoothNodeSupport() {
    }

    public static boolean isLinuxBlueZLikely() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("linux");
    }

    public static JsonObject statusJson() {
        JsonObject o = new JsonObject();
        boolean linux = isLinuxBlueZLikely();
        o.addProperty("available", linux);
        o.addProperty("label", "Bluetooth Classic (ESP32 SPP)");
        if (!linux) {
            o.addProperty("note", "Bluetooth pairing runs on the Pi (BlueZ). Use a Virtual board for UI testing on this host.");
            return o;
        }
        o.addProperty("note", "Flash ESP32 over USB first, then Pair. Device name: PanelsDCC-{prefix}");
        o.add("adapters", listAdapters());
        return o;
    }

    public static JsonArray listAdapters() {
        JsonArray arr = new JsonArray();
        if (!isLinuxBlueZLikely()) {
            return arr;
        }
        for (String line : runCapture("bluetoothctl", "list")) {
            // Controller XX:XX:XX:XX:XX:XX Name
            if (line.toLowerCase(Locale.ROOT).contains("controller")) {
                JsonObject a = new JsonObject();
                a.addProperty("raw", line.trim());
                arr.add(a);
            }
        }
        return arr;
    }

    public static JsonArray scanDevices(long timeoutSec) {
        JsonArray arr = new JsonArray();
        if (!isLinuxBlueZLikely()) {
            return arr;
        }
        runCapture("bluetoothctl", "scan", "on");
        try {
            Thread.sleep(Math.min(Math.max(timeoutSec, 2), 15) * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        runCapture("bluetoothctl", "scan", "off");
        for (String line : runCapture("bluetoothctl", "devices")) {
            String t = line.trim();
            if (!t.toUpperCase(Locale.ROOT).startsWith("DEVICE ")) {
                continue;
            }
            // Device AA:BB:CC:DD:EE:FF Name
            String[] parts = t.split("\\s+", 3);
            if (parts.length < 3) {
                continue;
            }
            String name = parts[2];
            if (!name.startsWith("PanelsDCC-")) {
                continue;
            }
            JsonObject d = new JsonObject();
            d.addProperty("address", parts[1]);
            d.addProperty("name", name);
            arr.add(d);
        }
        return arr;
    }

    /**
     * Pair + trust + attempt RFCOMM bind. Returns endpoint hint (rfcomm path or MAC).
     */
    public static JsonObject pairAndBind(String address, String prefix) {
        JsonObject out = new JsonObject();
        out.addProperty("address", address);
        if (!isLinuxBlueZLikely()) {
            out.addProperty("ok", false);
            out.addProperty("error", "Bluetooth pairing is only supported on Linux (BlueZ)");
            return out;
        }
        runCapture("bluetoothctl", "pair", address);
        runCapture("bluetoothctl", "trust", address);
        runCapture("bluetoothctl", "connect", address);
        // rfcomm bind is best-effort; session layer may open /dev/rfcommN later
        String rfcomm = "/dev/rfcomm0";
        List<String> bind = runCapture("rfcomm", "bind", "0", address, "1");
        out.addProperty("ok", true);
        out.addProperty("endpoint", rfcomm);
        out.addProperty("prefix", SensorBoardSupport.normalizePrefix(prefix));
        out.addProperty("bindLog", String.join("\n", bind));
        out.addProperty("btName", "PanelsDCC-" + SensorBoardSupport.normalizePrefix(prefix));
        return out;
    }

    private static List<String> runCapture(String... cmd) {
        List<String> lines = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    lines.add(line);
                }
            }
            p.waitFor(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            lines.add("ERROR: " + e.getMessage());
        }
        return lines;
    }
}
