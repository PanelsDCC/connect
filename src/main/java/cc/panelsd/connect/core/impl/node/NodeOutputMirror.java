package cc.panelsd.connect.core.impl.node;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * In-process mirror of node output state from CFG + DCC accessory broadcasts.
 * Not live Arduino readback — for UI only.
 * Level crossings may also publish virtual sensor feedback ({@code {prefix}-Vn}).
 */
public final class NodeOutputMirror {

    private static final long TICK_MS = 100L;
    private static final long CLEARING_MS = 400L;

    private final Map<String, BoardMirror> byPrefix = new ConcurrentHashMap<>();
    private volatile Consumer<JsonObject> sensorHardwarePublisher;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "NodeOutputMirror");
        t.setDaemon(true);
        return t;
    });

    public NodeOutputMirror() {
        ticker.scheduleAtFixedRate(this::tickAll, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    /** Same publisher used for real/shim {@code sensorHardware} patches. */
    public void setSensorHardwarePublisher(Consumer<JsonObject> publisher) {
        this.sensorHardwarePublisher = publisher;
    }

    /** Replace mirrored outputs for a board from CFG (resets runtime state). */
    public void syncConfig(String prefix, JsonArray outputs) {
        String key = NodeSupport.normalizePrefix(prefix);
        if (key == null) {
            return;
        }
        BoardMirror board = new BoardMirror(key);
        if (outputs != null) {
            int ix = 0;
            for (JsonElement el : outputs) {
                if (!el.isJsonObject()) {
                    continue;
                }
                OutputMirror out = OutputMirror.fromConfig(ix++, el.getAsJsonObject());
                out.boardPrefix = key;
                out.publisherSupplier = () -> sensorHardwarePublisher;
                board.outputs.add(out);
                // LC starts open — seed virtual feedback inactive
                if (NodeOutputSchemas.LEVEL_CROSSING.equals(out.type)) {
                    out.publishVirtualFeedback(false);
                }
            }
        }
        byPrefix.put(key, board);
    }

    public void remove(String prefix) {
        String key = NodeSupport.normalizePrefix(prefix);
        if (key != null) {
            byPrefix.remove(key);
        }
    }

    /** True if mirror missing, size differs, or legacy inventory can supply triggers we lack. */
    public boolean needsSync(String prefix, JsonArray outputs) {
        String key = NodeSupport.normalizePrefix(prefix);
        if (key == null || outputs == null) {
            return false;
        }
        BoardMirror board = byPrefix.get(key);
        if (board == null) {
            return outputs.size() > 0;
        }
        if (board.outputs.size() != outputs.size()) {
            return true;
        }
        for (int i = 0; i < board.outputs.size(); i++) {
            OutputMirror o = board.outputs.get(i);
            if (!o.triggers.isEmpty() || i >= outputs.size() || !outputs.get(i).isJsonObject()) {
                continue;
            }
            JsonObject inv = outputs.get(i).getAsJsonObject();
            boolean invHasTriggers = inv.has("triggers") && inv.get("triggers").isJsonArray()
                    && inv.getAsJsonArray("triggers").size() > 0;
            boolean invHasDcc = inv.has("dcc") && inv.get("dcc").isJsonPrimitive();
            if (invHasTriggers || invHasDcc) {
                return true;
            }
        }
        return false;
    }

    /** Layout accessory: {@code ON} = Forward/closed, {@code OFF} = Reverse/thrown. */
    public void onAccessory(int address, boolean on) {
        String wire = on ? "ON" : "OFF";
        for (BoardMirror board : byPrefix.values()) {
            board.applyAccessory(address, wire);
        }
    }

    /** Unicast test line, e.g. {@code TEST 12 ON} or {@code 12 ON}. */
    public void onCommandLine(String prefix, String line) {
        String key = NodeSupport.normalizePrefix(prefix);
        if (key == null || line == null) {
            return;
        }
        BoardMirror board = byPrefix.get(key);
        if (board == null) {
            return;
        }
        String s = line.trim();
        if (s.toUpperCase(Locale.ROOT).startsWith("TEST ")) {
            s = s.substring(5).trim();
        }
        if (s.toUpperCase(Locale.ROOT).startsWith("SERVO ")) {
            board.applyManualServo(s.substring(6).trim());
            return;
        }
        ParsedAcc acc = parseAccessory(s);
        if (acc != null) {
            board.applyAccessory(acc.address, acc.wire);
        }
    }

    public JsonArray statesJson(String prefix) {
        String key = NodeSupport.normalizePrefix(prefix);
        BoardMirror board = key == null ? null : byPrefix.get(key);
        if (board == null) {
            return new JsonArray();
        }
        return board.toJson();
    }

    public boolean anyAnimating() {
        for (BoardMirror board : byPrefix.values()) {
            if (board.isAnimating()) {
                return true;
            }
        }
        return false;
    }

    public boolean isAnimating(String prefix) {
        String key = NodeSupport.normalizePrefix(prefix);
        BoardMirror board = key == null ? null : byPrefix.get(key);
        return board != null && board.isAnimating();
    }

    private void tickAll() {
        long now = System.currentTimeMillis();
        for (BoardMirror board : byPrefix.values()) {
            board.tick(now);
        }
    }

    private static ParsedAcc parseAccessory(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String[] parts = s.trim().split("\\s+");
        if (parts.length < 2) {
            return null;
        }
        try {
            int addr = Integer.parseInt(parts[0]);
            String tok = parts[1].toUpperCase(Locale.ROOT);
            if ("ON".equals(tok) || "CLOSED".equals(tok) || "FORWARD".equals(tok)) {
                return new ParsedAcc(addr, "ON");
            }
            if ("OFF".equals(tok) || "THROWN".equals(tok) || "REVERSE".equals(tok)) {
                return new ParsedAcc(addr, "OFF");
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    private static final class ParsedAcc {
        final int address;
        final String wire;

        ParsedAcc(int address, String wire) {
            this.address = address;
            this.wire = wire;
        }
    }

    static final class BoardMirror {
        final String prefix;
        final List<OutputMirror> outputs = new ArrayList<>();

        BoardMirror(String prefix) {
            this.prefix = prefix;
        }

        void applyAccessory(int address, String wire) {
            for (OutputMirror o : outputs) {
                o.applyAccessory(address, wire);
            }
        }

        /** Direct {@code SERVO pin deg} move. Not a DCC position. */
        void applyManualServo(String rest) {
            if (rest == null || rest.isBlank()) {
                return;
            }
            String[] parts = rest.trim().split("\\s+");
            if (parts.length < 2) {
                return;
            }
            String pin = parts[0].toUpperCase(Locale.ROOT);
            int deg;
            try {
                deg = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return;
            }
            if (deg < 0) {
                deg = 0;
            } else if (deg > 180) {
                deg = 180;
            }
            for (OutputMirror o : outputs) {
                for (int i = 0; i < o.pins.size(); i++) {
                    String outPin = o.pins.get(i);
                    if (outPin != null && pin.equalsIgnoreCase(outPin.trim())) {
                        o.applyManualServo(i, deg);
                    }
                }
            }
        }

        void tick(long now) {
            for (OutputMirror o : outputs) {
                o.tick(now);
            }
        }

        boolean isAnimating() {
            for (OutputMirror o : outputs) {
                if (o.isAnimating()) {
                    return true;
                }
            }
            return false;
        }

        JsonArray toJson() {
            JsonArray arr = new JsonArray();
            for (OutputMirror o : outputs) {
                arr.add(o.toJson());
            }
            return arr;
        }
    }

    static final class OutputMirror {
        final int ix;
        final String type;
        final String name;
        final List<String> pins = new ArrayList<>();
        final List<String> pinRoles = new ArrayList<>();
        final List<Trigger> triggers = new ArrayList<>();
        final JsonObject params;

        String boardPrefix;
        java.util.function.Supplier<Consumer<JsonObject>> publisherSupplier;

        String state = "off";
        String phase = null;
        String label = "Off";
        Integer deg = null;
        Integer brightness = null;
        /** Pin indices that are on / flashing for the active multi-out config. */
        final List<Integer> onPins = new ArrayList<>();
        final List<Integer> flashingPins = new ArrayList<>();

        long phaseUntilMs = 0L;
        long phaseStartedMs = 0L;
        long nextWigwagToggleMs = 0L;
        boolean wigwagLeftOn = true;
        long pulseUntilMs = 0L;
        int barrier1Deg = 90;
        int barrier2Deg = 90;
        /** Servo / manual barrier travel (mirrors firmware travelMs pacing). */
        Integer travelFromDeg = null;
        Integer travelTargetDeg = null;
        long travelStartMs = 0L;
        /** Level-crossing manual move: 0 = barrier1, 1 = barrier2, -1 = none. */
        int manualBarrierWhich = -1;

        OutputMirror(int ix, String type, String name, JsonObject params) {
            this.ix = ix;
            this.type = type == null ? NodeOutputSchemas.ON_OFF : type;
            this.name = name;
            this.params = params != null ? params : new JsonObject();
            if (NodeOutputSchemas.LEVEL_CROSSING.equals(this.type)) {
                this.state = "open";
                this.phase = "open";
                this.label = "Open";
            } else if (NodeOutputSchemas.DIGITAL_SIGNAL.equals(this.type)) {
                this.state = "off";
                this.label = "Off";
            }
        }

        static OutputMirror fromConfig(int ix, JsonObject o) {
            String type = o.has("type") ? o.get("type").getAsString() : NodeOutputSchemas.ON_OFF;
            String name = o.has("name") ? o.get("name").getAsString() : null;
            JsonObject params = o.has("params") && o.get("params").isJsonObject()
                    ? o.getAsJsonObject("params") : new JsonObject();
            OutputMirror m = new OutputMirror(ix, type, name, params);
            if (o.has("pins") && o.get("pins").isJsonArray()) {
                for (JsonElement p : o.getAsJsonArray("pins")) {
                    m.pins.add(p.getAsString().toUpperCase(Locale.ROOT));
                }
            }
            if (o.has("pinNames") && o.get("pinNames").isJsonArray()) {
                for (JsonElement r : o.getAsJsonArray("pinNames")) {
                    m.pinRoles.add(r.getAsString());
                }
            } else if (o.has("pinRoles") && o.get("pinRoles").isJsonArray()) {
                for (JsonElement r : o.getAsJsonArray("pinRoles")) {
                    m.pinRoles.add(r.getAsString());
                }
            } else if (NodeOutputSchemas.LEVEL_CROSSING.equals(m.type)) {
                m.pinRoles.addAll(List.of("warning", "wigwag_left", "wigwag_right", "barrier1", "barrier2"));
            }
            if (o.has("triggers") && o.get("triggers").isJsonArray()) {
                for (JsonElement te : o.getAsJsonArray("triggers")) {
                    if (!te.isJsonObject()) {
                        continue;
                    }
                    JsonObject t = te.getAsJsonObject();
                    Trigger trig = new Trigger();
                    trig.dcc = t.has("dcc") ? t.get("dcc").getAsInt() : 0;
                    trig.direction = t.has("direction") ? t.get("direction").getAsString() : "Forward";
                    trig.state = t.has("state") ? t.get("state").getAsString() : null;
                    trig.label = t.has("label") ? t.get("label").getAsString() : null;
                    if (t.has("deg")) {
                        trig.deg = t.get("deg").getAsInt();
                    }
                    if (t.has("brightness")) {
                        trig.brightness = t.get("brightness").getAsInt();
                    }
                    if (t.has("on") && t.get("on").isJsonArray()) {
                        for (JsonElement le : t.getAsJsonArray("on")) {
                            try {
                                trig.on.add(le.getAsInt());
                            } catch (Exception ignored) {
                                // skip
                            }
                        }
                    }
                    if (t.has("flashing") && t.get("flashing").isJsonArray()) {
                        for (JsonElement le : t.getAsJsonArray("flashing")) {
                            try {
                                trig.flashing.add(le.getAsInt());
                            } catch (Exception ignored) {
                                // skip
                            }
                        }
                    } else if (t.has("lamps") && t.get("lamps").isJsonArray()) {
                        // Legacy: lamps[] + boolean flashing
                        boolean flashAll = t.has("flashing") && t.get("flashing").isJsonPrimitive()
                                && parseTruthy(t.get("flashing"));
                        for (JsonElement le : t.getAsJsonArray("lamps")) {
                            try {
                                int lamp = le.getAsInt();
                                trig.on.add(lamp);
                                if (flashAll) {
                                    trig.flashing.add(lamp);
                                }
                            } catch (Exception ignored) {
                                // skip
                            }
                        }
                    }
                    m.triggers.add(trig);
                }
            }
            // Legacy single-dcc outputs (no triggers[]) — synthesise On/Off pair
            if (m.triggers.isEmpty() && o.has("dcc") && o.get("dcc").isJsonPrimitive()) {
                int dcc = o.get("dcc").getAsInt();
                if (NodeOutputSchemas.LEVEL_CROSSING.equals(m.type)) {
                    m.triggers.add(Trigger.of(dcc, "Forward", "open", "Open"));
                    m.triggers.add(Trigger.of(dcc, "Reverse", "closed", "Closed"));
                } else if (NodeOutputSchemas.PULSE.equals(m.type)) {
                    m.triggers.add(Trigger.of(dcc, "Forward", "on", "Pulse"));
                } else if (!NodeOutputSchemas.DIGITAL_SIGNAL.equals(m.type)) {
                    m.triggers.add(Trigger.of(dcc, "Forward", "on", "On"));
                    m.triggers.add(Trigger.of(dcc, "Reverse", "off", "Off"));
                }
            }
            if (NodeOutputSchemas.LEVEL_CROSSING.equals(m.type)) {
                m.barrier1Deg = m.barrierOpen(0);
                m.barrier2Deg = m.barrierOpen(1);
            }
            if (NodeOutputSchemas.DIGITAL_SIGNAL.equals(m.type) && m.pinRoles.isEmpty()) {
                for (int i = 0; i < m.pins.size(); i++) {
                    m.pinRoles.add("out" + (i + 1));
                }
            }
            return m;
        }

        void applyAccessory(int address, String wire) {
            for (Trigger t : triggers) {
                if (t.dcc != address) {
                    continue;
                }
                if (!NodeOutputSchemas.directionToWire(t.direction).equalsIgnoreCase(wire)) {
                    continue;
                }
                applyTrigger(t);
                return;
            }
        }

        private void applyTrigger(Trigger t) {
            long now = System.currentTimeMillis();
            if (NodeOutputSchemas.BLINK.equals(type)) {
                boolean start = isOnish(t);
                state = start ? "blinking" : "off";
                label = start ? "Blinking" : "Off";
                phase = null;
            } else if (NodeOutputSchemas.PULSE.equals(type)) {
                state = "on";
                label = t.label != null ? t.label : "Pulse";
                phase = null;
                pulseUntilMs = now + paramInt("pulseMs", 250);
            } else if (NodeOutputSchemas.SERVO.equals(type)) {
                int to = t.deg != null ? t.deg : 0;
                startServoTravel(to, t.label != null ? t.label : (to + "°"));
            } else if (NodeOutputSchemas.FADE.equals(type)) {
                state = "level";
                brightness = t.brightness != null ? t.brightness : 0;
                label = t.label != null ? t.label : (brightness + "%");
                phase = null;
            } else if (NodeOutputSchemas.LEVEL_CROSSING.equals(type)) {
                if (isOpenish(t)) {
                    startOpen(now);
                } else {
                    startClose(now);
                }
            } else if (NodeOutputSchemas.ALT_PAIR.equals(type)) {
                boolean start = isOnish(t);
                state = start ? "blinking" : "off";
                label = start ? "Alternating" : "Off";
                phase = null;
                if (start) {
                    wigwagLeftOn = true;
                    nextWigwagToggleMs = now + paramInt("onMs", 500);
                }
            } else if (NodeOutputSchemas.DIGITAL_SIGNAL.equals(type)) {
                applySignalTrigger(t);
            } else {
                boolean on = isOnish(t);
                state = on ? "on" : "off";
                label = t.label != null ? t.label : (on ? "On" : "Off");
                phase = null;
            }
        }

        void applyManualServo(int pinIx, int toDeg) {
            if (NodeOutputSchemas.SERVO.equals(type)) {
                startServoTravel(toDeg, toDeg + "°");
                return;
            }
            if (!NodeOutputSchemas.LEVEL_CROSSING.equals(type)) {
                return;
            }
            String role = pinIx < pinRoles.size() ? pinRoles.get(pinIx) : null;
            int which = "barrier2".equals(role) || pinIx >= 4 ? 1
                    : ("barrier1".equals(role) || pinIx == 3 ? 0 : -1);
            if (which < 0) {
                return;
            }
            long now = System.currentTimeMillis();
            manualBarrierWhich = which;
            travelFromDeg = which == 0 ? barrier1Deg : barrier2Deg;
            travelTargetDeg = clampDeg(toDeg);
            travelStartMs = now;
            state = "manual";
            phase = "manual";
            phaseUntilMs = 0L;
            label = travelTargetDeg + "°";
        }

        private void startServoTravel(int toDeg, String lbl) {
            long now = System.currentTimeMillis();
            int to = clampDeg(toDeg);
            travelFromDeg = deg != null ? deg : 0;
            travelTargetDeg = to;
            travelStartMs = now;
            state = "position";
            phase = "travel";
            label = lbl;
        }

        private static int clampDeg(int d) {
            if (d < 0) {
                return 0;
            }
            if (d > 180) {
                return 180;
            }
            return d;
        }

        private void applySignalTrigger(Trigger t) {
            onPins.clear();
            flashingPins.clear();
            if (t.on != null) {
                onPins.addAll(t.on);
            }
            if (t.flashing != null) {
                flashingPins.addAll(t.flashing);
                for (Integer f : t.flashing) {
                    if (!onPins.contains(f)) {
                        onPins.add(f);
                    }
                }
            }
            state = t.state != null ? t.state : "config";
            label = t.label != null ? t.label : "Config";
            phase = null;
        }

        private boolean isOnish(Trigger t) {
            if (t.state != null) {
                String s = t.state.toLowerCase(Locale.ROOT);
                if ("off".equals(s) || "closed".equals(s) || "stop".equals(s)) {
                    return false;
                }
                if ("on".equals(s) || "open".equals(s) || "pulse".equals(s) || s.startsWith("start")) {
                    return true;
                }
            }
            return "ON".equalsIgnoreCase(NodeOutputSchemas.directionToWire(t.direction));
        }

        private boolean isOpenish(Trigger t) {
            if (t.state != null) {
                String s = t.state.toLowerCase(Locale.ROOT);
                if ("open".equals(s)) {
                    return true;
                }
                if ("closed".equals(s) || "close".equals(s)) {
                    return false;
                }
            }
            // Open defaults to Forward/ON in schemas
            return "ON".equalsIgnoreCase(NodeOutputSchemas.directionToWire(t.direction));
        }

        private void startClose(long now) {
            state = "closing";
            phase = "warning";
            label = "Closing · warning";
            phaseStartedMs = now;
            phaseUntilMs = now + paramInt("warningMs", 3000);
            manualBarrierWhich = -1;
            holdBarriersOpen();
            wigwagLeftOn = true;
            nextWigwagToggleMs = 0L;
        }

        private void startOpen(long now) {
            // If already open/clearing, stay quiet
            if ("open".equals(phase) || "open".equals(state)) {
                state = "open";
                phase = "open";
                label = "Open";
                phaseUntilMs = 0L;
                manualBarrierWhich = -1;
                holdBarriersOpen();
                return;
            }
            state = "opening";
            phase = "barriers_opening";
            label = "Opening · barriers";
            phaseStartedMs = now;
            phaseUntilMs = now + maxBarrierTravel();
            manualBarrierWhich = -1;
            ensureWigwagRunning(now);
        }

        private void holdBarriersOpen() {
            barrier1Deg = barrierOpen(0);
            barrier2Deg = barrierOpen(1);
        }

        private void holdBarriersClosed() {
            barrier1Deg = barrierClosed(0);
            barrier2Deg = barrierClosed(1);
        }

        private int barrierTravel(int which) {
            String key = which == 0 ? "barrier1TravelMs" : "barrier2TravelMs";
            if (params != null && params.has(key)) {
                return Math.max(1, paramInt(key, 2500));
            }
            return Math.max(1, paramInt("barrierTravelMs", 2500));
        }

        private int barrierOpen(int which) {
            String key = which == 0 ? "barrier1Open_deg" : "barrier2Open_deg";
            if (params != null && params.has(key)) {
                return paramInt(key, 90);
            }
            return paramInt("barrierOpen_deg", 90);
        }

        private int barrierClosed(int which) {
            String key = which == 0 ? "barrier1Closed_deg" : "barrier2Closed_deg";
            if (params != null && params.has(key)) {
                return paramInt(key, 0);
            }
            return paramInt("barrierClosed_deg", 0);
        }

        private int maxBarrierTravel() {
            return Math.max(barrierTravel(0), barrierTravel(1));
        }

        private void ensureWigwagRunning(long now) {
            if (nextWigwagToggleMs <= 0) {
                wigwagLeftOn = true;
                nextWigwagToggleMs = now + paramInt("wigwagOnMs", 400);
            }
        }

        void tick(long now) {
            if (NodeOutputSchemas.PULSE.equals(type) && pulseUntilMs > 0 && now >= pulseUntilMs) {
                state = "off";
                label = "Off";
                pulseUntilMs = 0L;
            }
            if (NodeOutputSchemas.SERVO.equals(type)) {
                tickServoTravel(now);
            }
            if (NodeOutputSchemas.LEVEL_CROSSING.equals(type)) {
                tickCrossing(now);
            }
        }

        private void tickServoTravel(long now) {
            if (travelTargetDeg == null || travelFromDeg == null) {
                return;
            }
            int arrived = interpolateTravel(now, travelFromDeg, travelTargetDeg,
                    paramInt("travelMs", 1000), travelStartMs);
            deg = arrived;
            label = arrived + "°";
            if (arrived == travelTargetDeg) {
                phase = null;
                travelFromDeg = null;
                travelTargetDeg = null;
            }
        }

        /** Same pacing as firmware: travelMs is full 0–180° time, scaled by delta. */
        private static int interpolateTravel(long now, int from, int to, int travelMs, long startMs) {
            int dur = Math.max(1, travelMs);
            int delta = Math.abs(to - from);
            int need = Math.max(1, (int) ((long) dur * delta / 180L));
            double t = (now - startMs) / (double) need;
            if (t >= 1.0) {
                return to;
            }
            if (t <= 0) {
                return from;
            }
            return (int) Math.round(from + (to - from) * t);
        }

        private void tickCrossing(long now) {
            if (phase == null) {
                return;
            }
            updateBarrierDeg(now);
            if (phaseUntilMs > 0 && now >= phaseUntilMs) {
                advanceCrossingPhase(now);
            }
        }

        private void updateBarrierDeg(long now) {
            if ("barriers_closing".equals(phase) || "barriers_opening".equals(phase)) {
                for (int which = 0; which < 2; which++) {
                    int open = barrierOpen(which);
                    int closed = barrierClosed(which);
                    int travel = barrierTravel(which);
                    double t = Math.min(1.0, Math.max(0.0, (now - phaseStartedMs) / (double) travel));
                    int deg = "barriers_closing".equals(phase)
                            ? (int) Math.round(open + (closed - open) * t)
                            : (int) Math.round(closed + (open - closed) * t);
                    if (which == 0) {
                        barrier1Deg = deg;
                    } else {
                        barrier2Deg = deg;
                    }
                }
            } else if ("manual".equals(phase) && travelTargetDeg != null && travelFromDeg != null
                    && manualBarrierWhich >= 0) {
                int travel = barrierTravel(manualBarrierWhich);
                int deg = interpolateTravel(now, travelFromDeg, travelTargetDeg, travel, travelStartMs);
                if (manualBarrierWhich == 0) {
                    barrier1Deg = deg;
                } else {
                    barrier2Deg = deg;
                }
                if (deg == travelTargetDeg) {
                    travelFromDeg = null;
                    travelTargetDeg = null;
                }
            } else if (phaseEquals("closed")) {
                holdBarriersClosed();
            } else if (phaseEquals("open", "clearing", "warning", "wigwag")) {
                holdBarriersOpen();
            }
        }

        private void advanceCrossingPhase(long now) {
            if ("warning".equals(phase)) {
                phase = "wigwag";
                label = "Closing · wig-wag";
                phaseStartedMs = now;
                // Short settle with lights flashing before barriers drop
                phaseUntilMs = now + Math.max(paramInt("wigwagOnMs", 400) * 2, 800);
            } else if ("wigwag".equals(phase)) {
                phase = "barriers_closing";
                label = "Closing · barriers";
                phaseStartedMs = now;
                phaseUntilMs = now + maxBarrierTravel();
            } else if ("barriers_closing".equals(phase)) {
                phase = "closed";
                state = "closed";
                label = "Closed";
                phaseUntilMs = 0L;
                holdBarriersClosed();
                publishVirtualFeedback(true);
            } else if ("barriers_opening".equals(phase)) {
                phase = "clearing";
                label = "Opening · clearing";
                phaseStartedMs = now;
                phaseUntilMs = now + CLEARING_MS;
                holdBarriersOpen();
            } else if ("clearing".equals(phase)) {
                phase = "open";
                state = "open";
                label = "Open";
                phaseUntilMs = 0L;
                holdBarriersOpen();
                publishVirtualFeedback(false);
            }
        }

        /**
         * Broadcast virtual sensor edge for LC feedback.
         * @param closedComplete true → active state (default high); false → inactive
         */
        void publishVirtualFeedback(boolean closedComplete) {
            if (!NodeOutputSchemas.LEVEL_CROSSING.equals(type)) {
                return;
            }
            String vLabel = null;
            if (params.has("feedbackVirtual") && params.get("feedbackVirtual").isJsonPrimitive()) {
                vLabel = NodeSupport.normalizeVirtualLabel(params.get("feedbackVirtual").getAsString());
            }
            if (vLabel == null || boardPrefix == null || boardPrefix.isBlank()) {
                return;
            }
            int active = 1;
            if (params.has("feedbackActiveState") && params.get("feedbackActiveState").isJsonPrimitive()) {
                try {
                    active = params.get("feedbackActiveState").getAsInt() == 0 ? 0 : 1;
                } catch (Exception ignored) {
                    active = 1;
                }
            }
            int stateVal = closedComplete ? active : (1 - active);
            int n = Integer.parseInt(vLabel.substring(1));
            String hardwareId = NodeSupport.virtualHardwareId(boardPrefix, n);
            Consumer<JsonObject> publisher = publisherSupplier != null ? publisherSupplier.get() : null;
            if (publisher == null) {
                return;
            }
            try {
                JsonObject data = new JsonObject();
                data.addProperty("id", hardwareId);
                data.addProperty("state", stateVal);
                JsonObject msg = new JsonObject();
                msg.addProperty("type", "sensorHardware");
                msg.addProperty("method", "patch");
                msg.add("data", data);
                publisher.accept(msg);
            } catch (Exception e) {
                System.err.println("Failed to publish virtual feedback " + hardwareId + ": " + e.getMessage());
            }
        }

        private boolean phaseEquals(String... names) {
            if (phase == null) {
                return false;
            }
            for (String n : names) {
                if (n.equals(phase)) {
                    return true;
                }
            }
            return false;
        }

        boolean isAnimating() {
            if (NodeOutputSchemas.LEVEL_CROSSING.equals(type)) {
                // Include closed: wig-wags keep flashing (CSS-driven; still mark active for UI)
                if (phaseEquals("warning", "wigwag", "barriers_closing", "closed",
                        "barriers_opening", "clearing")) {
                    return true;
                }
                return "manual".equals(phase) && travelTargetDeg != null;
            }
            if (NodeOutputSchemas.SERVO.equals(type)) {
                return travelTargetDeg != null;
            }
            if (NodeOutputSchemas.DIGITAL_SIGNAL.equals(type)) {
                return !flashingPins.isEmpty();
            }
            if (NodeOutputSchemas.PULSE.equals(type)) {
                return pulseUntilMs > 0;
            }
            return "blinking".equals(state);
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("ix", ix);
            o.addProperty("type", type);
            o.addProperty("state", state);
            if (phase != null) {
                o.addProperty("phase", phase);
            }
            o.addProperty("label", label);
            if (deg != null) {
                o.addProperty("deg", deg);
            }
            if (brightness != null) {
                o.addProperty("brightness", brightness);
            }
            if (NodeOutputSchemas.LEVEL_CROSSING.equals(type)) {
                o.addProperty("barrierDeg", barrier1Deg);
                o.addProperty("barrier1Deg", barrier1Deg);
                o.addProperty("barrier2Deg", barrier2Deg);
            }
            if (NodeOutputSchemas.DIGITAL_SIGNAL.equals(type)) {
                JsonArray onJson = new JsonArray();
                for (Integer p : onPins) {
                    onJson.add(p);
                }
                JsonArray flashJson = new JsonArray();
                for (Integer p : flashingPins) {
                    flashJson.add(p);
                }
                o.add("on", onJson);
                o.add("flashing", flashJson);
            }
            o.addProperty("animating", isAnimating());
            int wwMs;
            if (NodeOutputSchemas.LEVEL_CROSSING.equals(type)) {
                wwMs = paramInt("wigwagOnMs", 400) + paramInt("wigwagOffMs", 400);
            } else if (NodeOutputSchemas.DIGITAL_SIGNAL.equals(type)) {
                wwMs = Math.max(200, paramInt("flashMs", 500) * 2);
            } else {
                wwMs = paramInt("onMs", 500) + paramInt("offMs", 500);
            }
            o.addProperty("wigwagPeriodMs", Math.max(200, wwMs));
            JsonObject pinMap = new JsonObject();
            for (int i = 0; i < pins.size(); i++) {
                pinMap.addProperty(pins.get(i), pinLevel(i));
            }
            o.add("pins", pinMap);
            return o;
        }

        private String pinLevel(int pinIx) {
            String role = pinIx < pinRoles.size() ? pinRoles.get(pinIx) : null;
            if (NodeOutputSchemas.LEVEL_CROSSING.equals(type)) {
                return crossingPinLevel(role);
            }
            if (NodeOutputSchemas.DIGITAL_SIGNAL.equals(type)) {
                return signalPinLevel(pinIx, role);
            }
            if (NodeOutputSchemas.BLINK.equals(type)) {
                return "blinking".equals(state) ? "blinking" : "off";
            }
            if (NodeOutputSchemas.ALT_PAIR.equals(type)) {
                if (!"blinking".equals(state)) {
                    return "off";
                }
                // CSS-driven opposite phases — smoother than polled on/off
                return pinIx == 0 ? "wigwag_a" : (pinIx == 1 ? "wigwag_b" : "off");
            }
            if (NodeOutputSchemas.SERVO.equals(type)) {
                return deg != null ? (deg + "°") : "off";
            }
            if (NodeOutputSchemas.FADE.equals(type)) {
                return brightness != null ? (brightness + "%") : "off";
            }
            if (NodeOutputSchemas.PULSE.equals(type)) {
                return "on".equals(state) ? "on" : "off";
            }
            return "on".equals(state) ? "on" : "off";
        }

        private String signalPinLevel(int pinIx, String role) {
            if (flashingPins.contains(pinIx)) {
                return "blinking";
            }
            if (onPins.contains(pinIx)) {
                return "on";
            }
            return "off";
        }

        private String crossingPinLevel(String role) {
            if (role == null) {
                return "off";
            }
            // Amber warning only during the initial warning phase; off once wig-wags start.
            boolean warningOn = phaseEquals("warning");
            boolean wwActive = phaseEquals("wigwag", "barriers_closing", "closed", "barriers_opening");
            boolean barriersMoving = phaseEquals("barriers_closing", "barriers_opening");
            if ("warning".equals(role)) {
                return warningOn ? "on" : "off";
            }
            if ("wigwag_left".equals(role)) {
                return wwActive ? "wigwag_a" : "off";
            }
            if ("wigwag_right".equals(role)) {
                return wwActive ? "wigwag_b" : "off";
            }
            if ("barrier1".equals(role) || "barrier2".equals(role)) {
                int d = "barrier2".equals(role) ? barrier2Deg : barrier1Deg;
                if (barriersMoving) {
                    return "→" + d + "°";
                }
                return d + "°";
            }
            return "off";
        }

        private int paramInt(String key, int def) {
            if (params != null && params.has(key) && params.get(key).isJsonPrimitive()) {
                try {
                    return params.get(key).getAsInt();
                } catch (Exception ignored) {
                    return def;
                }
            }
            return def;
        }

        private static boolean parseTruthy(JsonElement el) {
            if (el == null || !el.isJsonPrimitive()) {
                return false;
            }
            if (el.getAsJsonPrimitive().isBoolean()) {
                return el.getAsBoolean();
            }
            String s = el.getAsString().trim().toLowerCase(Locale.ROOT);
            return "true".equals(s) || "1".equals(s) || "yes".equals(s);
        }
    }

    static final class Trigger {
        int dcc;
        String direction;
        String state;
        String label;
        Integer deg;
        Integer brightness;
        final List<Integer> on = new ArrayList<>();
        final List<Integer> flashing = new ArrayList<>();

        static Trigger of(int dcc, String direction, String state, String label) {
            Trigger t = new Trigger();
            t.dcc = dcc;
            t.direction = direction;
            t.state = state;
            t.label = label;
            return t;
        }
    }
}
