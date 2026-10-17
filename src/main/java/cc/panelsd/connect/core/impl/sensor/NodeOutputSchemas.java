package cc.panelsd.connect.core.impl.sensor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Param / trigger schemas for node outputs (UI + CFG).
 * <p>
 * DCC accessories are activated by <strong>number + direction</strong>
 * ({@code Forward}/{@code Reverse}), matching panels-modular accessories.
 * Wire broadcast maps Forward→ON, Reverse→OFF.
 */
public final class NodeOutputSchemas {

    public static final String ON_OFF = "on_off";
    public static final String PULSE = "pulse";
    public static final String BLINK = "blink";
    public static final String ALT_PAIR = "alt_pair";
    public static final String FADE = "fade";
    public static final String SERVO = "servo";
    public static final String LEVEL_CROSSING = "level_crossing";
    public static final String DIGITAL_SIGNAL = "digital_signal";

    public static final String MODE_FIXED = "fixed_states";
    public static final String MODE_PULSE = "pulse";
    public static final String MODE_POSITIONS = "positions";
    /** DCC+direction → named multi-output combination (on / flashing per pin). */
    public static final String MODE_PATTERNS = "patterns";

    private static final Map<String, OutputType> TYPES = new LinkedHashMap<>();

    static {
        register(onOff());
        register(pulse());
        register(blink());
        register(altPair());
        register(fade());
        register(servo());
        register(levelCrossing());
        register(digitalSignal());
    }

    private NodeOutputSchemas() {
    }

    private static void register(OutputType t) {
        TYPES.put(t.id, t);
    }

    public static OutputType get(String id) {
        if (id == null) {
            return null;
        }
        return TYPES.get(id.trim().toLowerCase(Locale.ROOT));
    }

    public static List<OutputType> all() {
        return List.copyOf(TYPES.values());
    }

    public static JsonObject schemasJson() {
        JsonArray arr = new JsonArray();
        for (OutputType t : TYPES.values()) {
            arr.add(t.toJson());
        }
        JsonObject root = new JsonObject();
        root.add("types", arr);
        return root;
    }

    /** Map accessory direction to layout wire token. */
    public static String directionToWire(String direction) {
        if (direction == null) {
            return "ON";
        }
        String d = direction.trim().toLowerCase(Locale.ROOT);
        if (d.startsWith("rev") || "thrown".equals(d) || "off".equals(d)) {
            return "OFF";
        }
        return "ON";
    }

    private static OutputType onOff() {
        JsonObject params = new JsonObject();
        JsonArray fixed = new JsonArray();
        fixed.add(fixedState("on", "On"));
        fixed.add(fixedState("off", "Off"));
        return new OutputType(
                ON_OFF, "On/off", 1, List.of("digital_out"), MODE_FIXED,
                2, 2, fixed, dccDirectionFields(false),
                List.of(),
                params,
                null);
    }

    private static OutputType pulse() {
        JsonObject params = new JsonObject();
        params.addProperty("pulseMs", 250);
        JsonArray fixed = new JsonArray();
        fixed.add(fixedState("on", "Pulse"));
        return new OutputType(
                PULSE, "Timed pulse", 1, List.of("digital_out"), MODE_PULSE,
                1, 1, fixed, dccDirectionFields(false),
                List.of(param("pulseMs", "integer", "Pulse length (ms)", 250)),
                params,
                null);
    }

    private static OutputType blink() {
        JsonObject params = new JsonObject();
        params.addProperty("onMs", 500);
        params.addProperty("offMs", 500);
        JsonArray fixed = new JsonArray();
        fixed.add(fixedState("on", "Start / On"));
        fixed.add(fixedState("off", "Stop / Off"));
        return new OutputType(
                BLINK, "Blink", 1, List.of("digital_out"), MODE_FIXED,
                2, 2, fixed, dccDirectionFields(false),
                List.of(
                        param("onMs", "integer", "On time (ms)", 500),
                        param("offMs", "integer", "Off time (ms)", 500)),
                params,
                null);
    }

    private static OutputType altPair() {
        JsonObject params = new JsonObject();
        params.addProperty("onMs", 500);
        params.addProperty("offMs", 500);
        JsonArray fixed = new JsonArray();
        fixed.add(fixedState("on", "Start / On"));
        fixed.add(fixedState("off", "Stop / Off"));
        return new OutputType(
                ALT_PAIR, "Alternating pair", 2, List.of("digital_out"), MODE_FIXED,
                2, 2, fixed, dccDirectionFields(false),
                List.of(
                        param("onMs", "integer", "On time (ms)", 500),
                        param("offMs", "integer", "Off time (ms)", 500)),
                params,
                null);
    }

    private static OutputType fade() {
        // Multi-level dimmer: each DCC number + direction fades to a brightness.
        JsonObject params = new JsonObject();
        params.addProperty("fadeMs", 800);
        JsonArray defaults = new JsonArray();
        defaults.add(level("Level 1", 1, "Forward", 100));
        defaults.add(level("Level 2", 1, "Reverse", 0));
        JsonArray fields = dccDirectionFields(true);
        fields.add(field("brightness", "integer", "Brightness (%)", true, 0, 100, true));
        return new OutputType(
                FADE, "Fade / dimmable", 1, List.of("pwm"), MODE_POSITIONS,
                1, 16, defaults, fields,
                List.of(param("fadeMs", "integer", "Fade time (ms)", 800)),
                params,
                null);
    }

    private static OutputType servo() {
        JsonObject params = new JsonObject();
        params.addProperty("travelMs", 1000);
        JsonArray defaults = new JsonArray();
        defaults.add(position("Position 1", 1, "Forward", 0));
        defaults.add(position("Position 2", 1, "Reverse", 90));
        JsonArray fields = dccDirectionFields(true);
        fields.add(field("deg", "integer", "Position (°)", true, 0, 180, true));
        return new OutputType(
                SERVO, "Servo", 1, List.of("servo"), MODE_POSITIONS,
                1, 16, defaults, fields,
                List.of(param("travelMs", "integer", "Travel speed (ms full travel)", 1000)),
                params,
                null);
    }

    private static OutputType levelCrossing() {
        // UK crossing hardware: amber warning, left/right wig-wag (alt pair), two barrier servos.
        JsonObject params = new JsonObject();
        params.addProperty("warningMs", 3000);
        params.addProperty("wigwagOnMs", 400);
        params.addProperty("wigwagOffMs", 400);
        params.addProperty("barrierTravelMs", 2500);
        params.addProperty("barrierOpen_deg", 90);
        params.addProperty("barrierClosed_deg", 0);
        params.addProperty("feedbackVirtual", "");
        params.addProperty("feedbackActiveState", 1);
        JsonArray fixed = new JsonArray();
        fixed.add(fixedState("open", "Open"));
        fixed.add(fixedState("closed", "Closed"));
        JsonArray pinSlots = new JsonArray();
        pinSlots.add(pinSlot("warning", "Warning light", List.of("digital_out", "pwm")));
        pinSlots.add(pinSlot("wigwag_left", "Left wig-wag", List.of("digital_out")));
        pinSlots.add(pinSlot("wigwag_right", "Right wig-wag", List.of("digital_out")));
        pinSlots.add(pinSlot("barrier1", "Barrier 1", List.of("servo")));
        pinSlots.add(pinSlot("barrier2", "Barrier 2", List.of("servo")));
        return new OutputType(
                LEVEL_CROSSING, "Level crossing (UK)", 5, List.of("servo", "digital_out", "pwm"), MODE_FIXED,
                2, 2, fixed, dccDirectionFields(false),
                List.of(
                        param("warningMs", "integer", "Warning light time (ms)", 3000),
                        param("wigwagOnMs", "integer", "Wig-wag on (ms)", 400),
                        param("wigwagOffMs", "integer", "Wig-wag off (ms)", 400),
                        param("barrierTravelMs", "integer", "Barrier travel (ms)", 2500),
                        paramTestable("barrierOpen_deg", "integer", "Barrier open (°)", 90),
                        paramTestable("barrierClosed_deg", "integer", "Barrier closed (°)", 0),
                        param("feedbackVirtual", "string", "Virtual feedback (V1–V12)", ""),
                        param("feedbackActiveState", "integer", "Feedback active state (1=high when closed)", 1)),
                params,
                "Virtual feedback id is {prefix}-Vn — use as accessory feedbackHardwareId.",
                pinSlots);
    }

    private static OutputType digitalSignal() {
        // Multi-output digital controller: named pins + DCC configs (on / flashing per pin).
        JsonObject params = new JsonObject();
        params.addProperty("flashMs", 500);
        JsonArray defaults = new JsonArray();
        defaults.add(multiOutConfig(1));
        return new OutputType(
                DIGITAL_SIGNAL, "Digital signal", 0, List.of("digital_out"), MODE_PATTERNS,
                1, 24, defaults, dccDirectionFields(true),
                List.of(param("flashMs", "integer", "Flash period (ms)", 500)),
                params,
                "Pick any digital pins, name each output, then map DCC configs to on/flashing.",
                null,
                true);
    }

    /** Single default config row for a multi-output controller. */
    public static JsonObject multiOutConfig(int num) {
        JsonObject o = new JsonObject();
        o.addProperty("state", "config_" + num);
        o.addProperty("label", "Config #" + num);
        o.add("on", new JsonArray());
        o.add("flashing", new JsonArray());
        o.addProperty("dcc", 1);
        o.addProperty("direction", "Forward");
        return o;
    }

    private static JsonObject fixedState(String state, String label) {
        JsonObject o = new JsonObject();
        o.addProperty("state", state);
        o.addProperty("label", label);
        o.addProperty("dcc", 1);
        o.addProperty("direction", "on".equals(state) || "open".equals(state) ? "Forward" : "Reverse");
        return o;
    }

    private static JsonObject position(String label, int dcc, String direction, int deg) {
        JsonObject o = new JsonObject();
        o.addProperty("label", label);
        o.addProperty("dcc", dcc);
        o.addProperty("direction", direction);
        o.addProperty("deg", deg);
        return o;
    }

    private static JsonObject level(String label, int dcc, String direction, int brightness) {
        JsonObject o = new JsonObject();
        o.addProperty("label", label);
        o.addProperty("dcc", dcc);
        o.addProperty("direction", direction);
        o.addProperty("brightness", brightness);
        return o;
    }

    private static JsonArray dccDirectionFields(boolean includeLabel) {
        JsonArray fields = new JsonArray();
        if (includeLabel) {
            fields.add(field("label", "string", "Name", false, null, null, false));
        }
        fields.add(field("dcc", "integer", "DCC number", true, 1, 9999, false));
        JsonObject dir = field("direction", "enum", "Direction", true, null, null, false);
        JsonArray en = new JsonArray();
        en.add("Forward");
        en.add("Reverse");
        dir.add("enum", en);
        fields.add(dir);
        return fields;
    }

    private static JsonObject field(
            String id, String type, String title, boolean required,
            Integer min, Integer max, boolean testable) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("type", type);
        o.addProperty("title", title);
        o.addProperty("required", required);
        if (min != null) {
            o.addProperty("minimum", min);
        }
        if (max != null) {
            o.addProperty("maximum", max);
        }
        if (testable) {
            o.addProperty("testable", true);
        }
        return o;
    }

    private static JsonObject param(String id, String type, String title, Object defaultValue) {
        return param(id, type, title, defaultValue, false);
    }

    private static JsonObject paramTestable(String id, String type, String title, Object defaultValue) {
        return param(id, type, title, defaultValue, true);
    }

    private static JsonObject param(
            String id, String type, String title, Object defaultValue, boolean testable) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("type", type);
        o.addProperty("title", title);
        if (defaultValue instanceof Number) {
            o.addProperty("default", (Number) defaultValue);
        } else if (defaultValue != null) {
            o.addProperty("default", String.valueOf(defaultValue));
        }
        if (testable) {
            o.addProperty("testable", true);
        }
        return o;
    }

    private static JsonObject pinSlot(String key, String label, List<String> caps) {
        JsonObject o = new JsonObject();
        o.addProperty("key", key);
        o.addProperty("label", label);
        JsonArray arr = new JsonArray();
        for (String c : caps) {
            arr.add(c);
        }
        o.add("caps", arr);
        return o;
    }

    public static final class OutputType {
        public final String id;
        public final String label;
        public final int pinCount;
        public final List<String> requiredCapabilities;
        public final String triggerMode;
        public final int minTriggers;
        public final int maxTriggers;
        public final JsonArray defaultTriggers;
        public final JsonArray triggerFields;
        public final List<JsonObject> paramFields;
        public final JsonObject defaultParams;
        public final String notes;
        public final JsonArray pinSlots;
        /** When true, pin count is chosen by the user (open-ended pick). */
        public final boolean variablePinSlots;

        public OutputType(
                String id,
                String label,
                int pinCount,
                List<String> requiredCapabilities,
                String triggerMode,
                int minTriggers,
                int maxTriggers,
                JsonArray defaultTriggers,
                JsonArray triggerFields,
                List<JsonObject> paramFields,
                JsonObject defaultParams,
                String notes) {
            this(id, label, pinCount, requiredCapabilities, triggerMode, minTriggers, maxTriggers,
                    defaultTriggers, triggerFields, paramFields, defaultParams, notes, null, false);
        }

        public OutputType(
                String id,
                String label,
                int pinCount,
                List<String> requiredCapabilities,
                String triggerMode,
                int minTriggers,
                int maxTriggers,
                JsonArray defaultTriggers,
                JsonArray triggerFields,
                List<JsonObject> paramFields,
                JsonObject defaultParams,
                String notes,
                JsonArray pinSlots) {
            this(id, label, pinCount, requiredCapabilities, triggerMode, minTriggers, maxTriggers,
                    defaultTriggers, triggerFields, paramFields, defaultParams, notes, pinSlots, false);
        }

        public OutputType(
                String id,
                String label,
                int pinCount,
                List<String> requiredCapabilities,
                String triggerMode,
                int minTriggers,
                int maxTriggers,
                JsonArray defaultTriggers,
                JsonArray triggerFields,
                List<JsonObject> paramFields,
                JsonObject defaultParams,
                String notes,
                JsonArray pinSlots,
                boolean variablePinSlots) {
            this.id = id;
            this.label = label;
            this.pinCount = pinCount;
            this.requiredCapabilities = requiredCapabilities;
            this.triggerMode = triggerMode;
            this.minTriggers = minTriggers;
            this.maxTriggers = maxTriggers;
            this.defaultTriggers = defaultTriggers;
            this.triggerFields = triggerFields;
            this.paramFields = paramFields;
            this.defaultParams = defaultParams;
            this.notes = notes;
            this.pinSlots = pinSlots;
            this.variablePinSlots = variablePinSlots;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("label", label);
            o.addProperty("pinCount", pinCount);
            JsonArray caps = new JsonArray();
            for (String c : requiredCapabilities) {
                caps.add(c);
            }
            o.add("requiredCapabilities", caps);
            o.addProperty("triggerMode", triggerMode);
            o.addProperty("minTriggers", minTriggers);
            o.addProperty("maxTriggers", maxTriggers);
            o.add("defaultTriggers", defaultTriggers.deepCopy());
            o.add("triggerFields", triggerFields.deepCopy());
            JsonArray pFields = new JsonArray();
            for (JsonObject pf : paramFields) {
                pFields.add(pf.deepCopy());
            }
            o.add("paramFields", pFields);
            o.add("defaultParams", defaultParams.deepCopy());
            if (notes != null) {
                o.addProperty("notes", notes);
            }
            if (pinSlots != null) {
                o.add("pinSlots", pinSlots.deepCopy());
            }
            if (variablePinSlots) {
                o.addProperty("variablePinSlots", true);
            }
            return o;
        }
    }
}
