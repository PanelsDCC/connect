package cc.panelsd.connect.core.impl.sensor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeOutputMirrorTest {

    @Test
    void onOffMirrorsAccessoryBroadcast() {
        NodeOutputMirror mirror = new NodeOutputMirror();
        mirror.syncConfig("A", outs(
                output("Lamp", "on_off", pins("D2"),
                        trig("on", 5, "Forward"),
                        trig("off", 5, "Reverse"))));

        mirror.onAccessory(5, true);
        JsonObject st = mirror.statesJson("A").get(0).getAsJsonObject();
        assertEquals("on", st.get("state").getAsString());
        assertEquals("on", st.getAsJsonObject("pins").get("D2").getAsString());

        mirror.onAccessory(5, false);
        st = mirror.statesJson("A").get(0).getAsJsonObject();
        assertEquals("off", st.get("state").getAsString());
        assertEquals("off", st.getAsJsonObject("pins").get("D2").getAsString());
    }

    @Test
    void legacySingleDccOnOffMirrors() {
        NodeOutputMirror mirror = new NodeOutputMirror();
        JsonObject o = new JsonObject();
        o.addProperty("type", "on_off");
        o.addProperty("dcc", 1);
        JsonArray pins = new JsonArray();
        pins.add("D2");
        o.add("pins", pins);
        mirror.syncConfig("C", outs(o));

        mirror.onAccessory(1, true);
        assertEquals("on", mirror.statesJson("C").get(0).getAsJsonObject().get("state").getAsString());
        mirror.onAccessory(1, false);
        assertEquals("off", mirror.statesJson("C").get(0).getAsJsonObject().get("state").getAsString());
    }

    @Test
    void levelCrossingStartsCloseSequence() {
        NodeOutputMirror mirror = new NodeOutputMirror();
        JsonObject params = new JsonObject();
        params.addProperty("warningMs", 5000);
        params.addProperty("wigwagOnMs", 400);
        params.addProperty("wigwagOffMs", 400);
        params.addProperty("barrierTravelMs", 2500);
        JsonObject o = output("XC", "level_crossing",
                pins("D2", "D3", "D4", "D5", "D6"),
                trig("open", 9, "Forward"),
                trig("closed", 9, "Reverse"));
        o.add("params", params);
        JsonArray roles = new JsonArray();
        roles.add("warning");
        roles.add("wigwag_left");
        roles.add("wigwag_right");
        roles.add("barrier1");
        roles.add("barrier2");
        o.add("pinRoles", roles);
        mirror.syncConfig("B", outs(o));

        mirror.onAccessory(9, false);
        JsonObject st = mirror.statesJson("B").get(0).getAsJsonObject();
        assertEquals("closing", st.get("state").getAsString());
        assertEquals("warning", st.get("phase").getAsString());
        assertTrue(mirror.isAnimating("B"));
        assertEquals("on", st.getAsJsonObject("pins").get("D2").getAsString());
        assertEquals("off", st.getAsJsonObject("pins").get("D3").getAsString());
        assertTrue(mirror.anyAnimating());
    }

    @Test
    void digitalSignalMultiOutOnAndFlashing() {
        NodeOutputMirror mirror = new NodeOutputMirror();
        JsonObject o = multiOutSignal();
        mirror.syncConfig("S", outs(o));

        mirror.onAccessory(10, true); // Config #1 — pin0 on, pin1 flashing
        JsonObject st = mirror.statesJson("S").get(0).getAsJsonObject();
        assertEquals("on", st.getAsJsonObject("pins").get("D2").getAsString());
        assertEquals("blinking", st.getAsJsonObject("pins").get("D3").getAsString());
        assertEquals("off", st.getAsJsonObject("pins").get("D4").getAsString());
        assertTrue(mirror.isAnimating("S"));
        assertEquals(2, st.getAsJsonArray("on").size());
        assertEquals(1, st.getAsJsonArray("flashing").size());

        mirror.onAccessory(10, false); // Config #2 — pin2 on only
        st = mirror.statesJson("S").get(0).getAsJsonObject();
        assertEquals("off", st.getAsJsonObject("pins").get("D2").getAsString());
        assertEquals("off", st.getAsJsonObject("pins").get("D3").getAsString());
        assertEquals("on", st.getAsJsonObject("pins").get("D4").getAsString());
        assertFalse(mirror.isAnimating("S"));
    }

    @Test
    void digitalSignalLegacyLampsMigrates() {
        NodeOutputMirror mirror = new NodeOutputMirror();
        JsonObject params = new JsonObject();
        params.addProperty("flashMs", 500);
        JsonObject o = output("Legacy", "digital_signal", pins("D2", "D3"),
                legacyLampsTrig("config_1", "Config #1", ints(0, 1), true, 20, "Forward"));
        o.add("params", params);
        mirror.syncConfig("L", outs(o));

        mirror.onAccessory(20, true);
        JsonObject st = mirror.statesJson("L").get(0).getAsJsonObject();
        assertEquals("blinking", st.getAsJsonObject("pins").get("D2").getAsString());
        assertEquals("blinking", st.getAsJsonObject("pins").get("D3").getAsString());
    }

    @Test
    void levelCrossingPublishesVirtualFeedbackOnComplete() throws Exception {
        NodeOutputMirror mirror = new NodeOutputMirror();
        List<JsonObject> published = new CopyOnWriteArrayList<>();
        mirror.setSensorHardwarePublisher(published::add);

        JsonObject params = new JsonObject();
        params.addProperty("warningMs", 50);
        params.addProperty("wigwagOnMs", 50);
        params.addProperty("wigwagOffMs", 50);
        params.addProperty("barrierTravelMs", 50);
        params.addProperty("feedbackVirtual", "V2");
        params.addProperty("feedbackActiveState", 1);
        JsonObject o = output("XC", "level_crossing",
                pins("D2", "D3", "D4", "D5", "D6"),
                trig("open", 9, "Forward"),
                trig("closed", 9, "Reverse"));
        o.add("params", params);
        JsonArray roles = new JsonArray();
        roles.add("warning");
        roles.add("wigwag_left");
        roles.add("wigwag_right");
        roles.add("barrier1");
        roles.add("barrier2");
        o.add("pinRoles", roles);

        mirror.syncConfig("B", outs(o));
        // Seeded open → inactive
        assertEquals(1, published.size());
        assertEquals("B-V2", published.get(0).getAsJsonObject("data").get("id").getAsString());
        assertEquals(0, published.get(0).getAsJsonObject("data").get("state").getAsInt());

        mirror.onAccessory(9, false); // close
        assertTrue(awaitState(mirror, "B", "closed", 3000));
        JsonObject closedEdge = lastEdge(published, "B-V2");
        assertEquals(1, closedEdge.get("state").getAsInt());

        mirror.onAccessory(9, true); // open
        assertTrue(awaitState(mirror, "B", "open", 3000));
        JsonObject openEdge = lastEdge(published, "B-V2");
        assertEquals(0, openEdge.get("state").getAsInt());
    }

    private static boolean awaitState(NodeOutputMirror mirror, String prefix, String want, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            JsonArray arr = mirror.statesJson(prefix);
            if (arr.size() > 0 && want.equals(arr.get(0).getAsJsonObject().get("state").getAsString())) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static JsonObject lastEdge(List<JsonObject> published, String hardwareId) {
        List<JsonObject> hits = new ArrayList<>();
        for (JsonObject msg : published) {
            if (!msg.has("data") || !msg.get("data").isJsonObject()) {
                continue;
            }
            JsonObject data = msg.getAsJsonObject("data");
            if (hardwareId.equals(data.get("id").getAsString())) {
                hits.add(data);
            }
        }
        assertFalse(hits.isEmpty(), "no edges for " + hardwareId);
        return hits.get(hits.size() - 1);
    }

    private static JsonObject multiOutSignal() {
        JsonObject params = new JsonObject();
        params.addProperty("flashMs", 500);
        JsonObject o = output("Sig", "digital_signal",
                pins("D2", "D3", "D4"),
                multiTrig("config_1", "Config #1", ints(0, 1), ints(1), 10, "Forward"),
                multiTrig("config_2", "Config #2", ints(2), ints(), 10, "Reverse"));
        o.add("params", params);
        JsonArray names = new JsonArray();
        names.add("Red");
        names.add("Amber");
        names.add("Green");
        o.add("pinNames", names);
        return o;
    }

    private static JsonArray ints(int... vals) {
        JsonArray a = new JsonArray();
        for (int v : vals) {
            a.add(v);
        }
        return a;
    }

    private static JsonObject multiTrig(
            String state, String label, JsonArray on, JsonArray flashing,
            int dcc, String direction) {
        JsonObject t = new JsonObject();
        t.addProperty("state", state);
        t.addProperty("label", label);
        t.add("on", on);
        t.add("flashing", flashing);
        t.addProperty("dcc", dcc);
        t.addProperty("direction", direction);
        return t;
    }

    private static JsonObject legacyLampsTrig(
            String state, String label, JsonArray lamps, boolean flashing,
            int dcc, String direction) {
        JsonObject t = new JsonObject();
        t.addProperty("state", state);
        t.addProperty("label", label);
        t.add("lamps", lamps);
        t.addProperty("flashing", flashing ? "true" : "false");
        t.addProperty("dcc", dcc);
        t.addProperty("direction", direction);
        return t;
    }

    private static JsonArray outs(JsonObject... items) {
        JsonArray a = new JsonArray();
        for (JsonObject o : items) {
            a.add(o);
        }
        return a;
    }

    private static JsonObject output(String name, String type, JsonArray pins, JsonObject... triggers) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("type", type);
        o.add("pins", pins);
        JsonArray tr = new JsonArray();
        for (JsonObject t : triggers) {
            tr.add(t);
        }
        o.add("triggers", tr);
        return o;
    }

    private static JsonArray pins(String... labels) {
        JsonArray a = new JsonArray();
        for (String p : labels) {
            a.add(p);
        }
        return a;
    }

    private static JsonObject trig(String state, int dcc, String direction) {
        JsonObject t = new JsonObject();
        t.addProperty("state", state);
        t.addProperty("dcc", dcc);
        t.addProperty("direction", direction);
        t.addProperty("label", state.substring(0, 1).toUpperCase() + state.substring(1));
        return t;
    }
}
