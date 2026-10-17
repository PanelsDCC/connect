package cc.panelsd.connect.core.impl.sensor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In-process shim board that speaks the node line protocol without hardware.
 */
public final class VirtualNodeSession implements NodeSession {

    private final String id;
    private final String prefix;
    private final String boardType;
    private final Supplier<Consumer<JsonObject>> publisherSupplier;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, Integer> channelState = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> commandLog = new CopyOnWriteArrayList<>();
    private volatile JsonObject config = new JsonObject();
    private volatile String lastHandledOut;
    private volatile String lastSampleReply;

    public VirtualNodeSession(
            String prefix,
            String boardType,
            Supplier<Consumer<JsonObject>> publisherSupplier) {
        this.prefix = Objects.requireNonNull(SensorBoardSupport.normalizePrefix(prefix));
        this.boardType = BoardProfile.normalize(boardType);
        this.id = SensorBoardSupport.sessionIdForShim(this.prefix);
        this.publisherSupplier = Objects.requireNonNull(publisherSupplier);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        BoardProfile profile = BoardProfile.require(boardType);
        for (SensorBoardSupport.ChannelDef ch : profile.defaultChannelDefs(prefix)) {
            channelState.putIfAbsent(ch.id, 0);
            publishEdge(ch.id, 0);
        }
        System.out.println("Virtual node session started " + id + " (" + boardType + " prefix " + prefix + ")");
    }

    @Override
    public String getId() {
        return id;
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
        return SensorBoardSupport.TRANSPORT_SHIM;
    }

    @Override
    public String getEndpoint() {
        return "virtual:" + prefix;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    public Map<String, Integer> getChannelStates() {
        return Map.copyOf(channelState);
    }

    public java.util.List<String> getCommandLog() {
        return java.util.List.copyOf(commandLog);
    }

    public String getLastHandledOut() {
        return lastHandledOut;
    }

    public JsonObject getConfig() {
        return config.deepCopy();
    }

    /** Flip a channel and publish like a real sensor edge. */
    public void simulateChannel(String hardwareId, int state) {
        int st = state == 1 ? 1 : 0;
        channelState.put(hardwareId, st);
        publishEdge(hardwareId, st);
    }

    public void simulateByLabel(String silkLabel, int state) {
        String label = silkLabel == null ? "" : silkLabel.trim().toUpperCase(Locale.ROOT);
        if (label.startsWith("A")) {
            simulateChannel(SensorBoardSupport.analogHardwareId(prefix, BoardProfile.silkNumber(label)), state);
        } else if (label.startsWith("D")) {
            simulateChannel(SensorBoardSupport.digitalHardwareId(prefix, BoardProfile.silkNumber(label)), state);
        }
    }

    @Override
    public void sendCommand(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String trimmed = line.trim();
        commandLog.add(trimmed);
        while (commandLog.size() > 200) {
            commandLog.remove(0);
        }
        if (trimmed.startsWith("CFG ")) {
            handleCfg(trimmed.substring(4).trim());
            return;
        }
        if ("CFG?".equalsIgnoreCase(trimmed)) {
            // pull — no reply channel yet; config stays in memory
            return;
        }
        if (trimmed.toUpperCase(Locale.ROOT).startsWith("TEST ")) {
            lastHandledOut = trimmed;
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("TEST SAMPLE")) {
                lastSampleReply = synthesiseSampleReply(trimmed.substring("TEST SAMPLE".length()).trim());
            }
            return;
        }
        if (trimmed.toUpperCase(Locale.ROOT).startsWith("OUT ")
                || looksLikeAccessoryBroadcast(trimmed)) {
            if (addressOwned(trimmed)) {
                lastHandledOut = trimmed;
            }
        }
    }

    private boolean looksLikeAccessoryBroadcast(String line) {
        // e.g. "6 ON" / "6 OFF" / "6 CLOSED"
        return line.matches("(?i)^\\d+\\s+\\S+.*");
    }

    private boolean addressOwned(String line) {
        if (config == null || !config.has("outputs") || !config.get("outputs").isJsonArray()) {
            // No CFG yet — accept for Test/sim convenience
            return true;
        }
        Integer addr = parseAddress(line);
        if (addr == null) {
            return true;
        }
        for (JsonElement el : config.getAsJsonArray("outputs")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            // Legacy single-dcc outputs
            if (o.has("dcc") && o.get("dcc").getAsInt() == addr) {
                return true;
            }
            if (o.has("triggers") && o.get("triggers").isJsonArray()) {
                for (JsonElement te : o.getAsJsonArray("triggers")) {
                    if (!te.isJsonObject()) {
                        continue;
                    }
                    JsonObject t = te.getAsJsonObject();
                    if (t.has("dcc") && t.get("dcc").getAsInt() == addr) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Integer parseAddress(String line) {
        String s = line.trim();
        if (s.toUpperCase(Locale.ROOT).startsWith("OUT ")) {
            s = s.substring(4).trim();
        }
        if (s.toUpperCase(Locale.ROOT).startsWith("TEST ")) {
            s = s.substring(5).trim();
        }
        int sp = s.indexOf(' ');
        String tok = sp < 0 ? s : s.substring(0, sp);
        try {
            return Integer.parseInt(tok);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void handleCfg(String jsonOrBody) {
        try {
            JsonElement el = JsonParser.parseString(jsonOrBody);
            if (el.isJsonObject()) {
                config = el.getAsJsonObject();
            }
        } catch (Exception e) {
            System.err.println("[shim] CFG parse failed: " + e.getMessage());
        }
    }

    @Override
    public String sendCommandAwaitReply(String line, String replyPrefix, long timeoutMs) {
        sendCommand(line);
        if (replyPrefix != null && replyPrefix.equalsIgnoreCase("SAMPLE") && lastSampleReply != null) {
            return lastSampleReply;
        }
        return null;
    }

    /**
     * Shim ADC: clear ≈ small delta; simulated DETECTED ≈ large delta vs ref.
     */
    private String synthesiseSampleReply(String args) {
        String[] parts = args.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) {
            return null;
        }
        String ref = parts[0].trim().toUpperCase(Locale.ROOT);
        int refVal = 800;
        if (parts.length == 1) {
            return "SAMPLE " + ref + "=" + refVal;
        }
        String sens = parts[1].trim().toUpperCase(Locale.ROOT);
        String hwId = sens.startsWith("A")
                ? SensorBoardSupport.analogHardwareId(prefix, BoardProfile.silkNumber(sens))
                : SensorBoardSupport.digitalHardwareId(prefix, BoardProfile.silkNumber(sens));
        boolean occupied = channelState.getOrDefault(hwId, 0) == 1;
        int sensVal = occupied ? 1000 : 820;
        int delta = sensVal - refVal;
        int pct = refVal > 0 ? Math.min(100, Math.max(0, (delta * 100) / refVal)) : 0;
        return "SAMPLE " + ref + "=" + refVal + " " + sens + "=" + sensVal
                + " delta=" + delta + " pct=" + pct;
    }

    private void publishEdge(String hardwareId, int state) {
        Consumer<JsonObject> publisher = publisherSupplier.get();
        if (publisher == null) {
            return;
        }
        try {
            JsonObject data = new JsonObject();
            data.addProperty("id", hardwareId);
            data.addProperty("state", state);
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "sensorHardware");
            msg.addProperty("method", "patch");
            msg.add("data", data);
            publisher.accept(msg);
        } catch (Exception e) {
            System.err.println("Failed to broadcast shim sensorHardware: " + e.getMessage());
        }
    }

    public JsonArray channelsJson() {
        JsonArray channels = new JsonArray();
        BoardProfile profile = BoardProfile.require(boardType);
        for (SensorBoardSupport.ChannelDef def : profile.defaultChannelDefs(prefix)) {
            JsonObject ch = new JsonObject();
            ch.addProperty("id", def.id);
            ch.addProperty("label", def.label);
            ch.addProperty("kind", def.kind);
            ch.addProperty("state", channelState.getOrDefault(def.id, 0));
            channels.add(ch);
        }
        return channels;
    }

    @Override
    public void close() {
        running.set(false);
        System.out.println("Virtual node session closed " + id);
    }
}
