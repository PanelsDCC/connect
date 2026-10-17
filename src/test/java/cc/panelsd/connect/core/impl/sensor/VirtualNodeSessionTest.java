package cc.panelsd.connect.core.impl.sensor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualNodeSessionTest {

    @Test
    void publishesSimulatedEdgesAndFiltersBroadcast() {
        List<JsonObject> published = new ArrayList<>();
        VirtualNodeSession session = new VirtualNodeSession("B", BoardProfile.NANO_AVR, () -> published::add);
        session.start();
        assertTrue(session.isRunning());
        assertEquals(SensorBoardSupport.TRANSPORT_SHIM, session.getTransport());

        session.simulateByLabel("D2", 1);
        assertEquals(1, session.getChannelStates().get("B-D2"));
        assertTrue(published.stream().anyMatch(m ->
                "sensorHardware".equals(m.get("type").getAsString())
                        && "B-D2".equals(m.getAsJsonObject("data").get("id").getAsString())));

        JsonObject cfg = new JsonObject();
        JsonArray outs = new JsonArray();
        JsonObject out = new JsonObject();
        out.addProperty("name", "Platform servo");
        out.addProperty("type", "servo");
        JsonArray pins = new JsonArray();
        pins.add("D9");
        out.add("pins", pins);
        JsonObject params = new JsonObject();
        params.addProperty("travelMs", 1200);
        out.add("params", params);
        JsonArray triggers = new JsonArray();
        JsonObject t1 = new JsonObject();
        t1.addProperty("label", "Pos A");
        t1.addProperty("dcc", 42);
        t1.addProperty("direction", "Forward");
        t1.addProperty("deg", 0);
        triggers.add(t1);
        JsonObject t2 = new JsonObject();
        t2.addProperty("label", "Pos B");
        t2.addProperty("dcc", 42);
        t2.addProperty("direction", "Reverse");
        t2.addProperty("deg", 90);
        triggers.add(t2);
        out.add("triggers", triggers);
        outs.add(out);
        cfg.add("outputs", outs);
        session.sendCommand("CFG " + cfg);

        session.sendCommand("99 ON");
        // not owned — lastHandled should not update from 99
        session.sendCommand("TEST 42 ON");
        assertEquals("TEST 42 ON", session.getLastHandledOut());

        session.sendCommand("42 ON");
        assertEquals("42 ON", session.getLastHandledOut());

        session.sendCommand("TEST SERVO D9 45");
        assertEquals("TEST SERVO D9 45", session.getLastHandledOut());

        session.sendCommand("TEST PWM D5 40");
        assertEquals("TEST PWM D5 40", session.getLastHandledOut());
    }
}
