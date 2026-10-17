package cc.panelsd.connect.core.impl.sensor;

import cc.panelsd.connect.core.impl.DccIoServiceImpl;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpticalDistrictsTest {

    @TempDir
    Path temp;

    @Test
    void shimSampleReplyReflectsSimulatedOccupancy() {
        VirtualNodeSession session = new VirtualNodeSession("A", BoardProfile.MEGA_AVR, () -> null);
        session.start();
        String clear = session.sendCommandAwaitReply("TEST SAMPLE A15 A0", "SAMPLE", 100);
        assertNotNull(clear);
        assertTrue(clear.contains("pct="));
        assertTrue(clear.contains("A15="));
        assertTrue(clear.contains("A0="));

        session.simulateByLabel("A0", 1);
        String occupied = session.sendCommandAwaitReply("TEST SAMPLE A15 A0", "SAMPLE", 100);
        assertNotNull(occupied);
        // Occupied synthesises a larger delta than clear
        int clearPct = pctFrom(clear);
        int occPct = pctFrom(occupied);
        assertTrue(occPct > clearPct, "occupied pct " + occPct + " should exceed clear " + clearPct);
    }

    @Test
    void cfgRoundTripStoresOpticalDistricts() {
        VirtualNodeSession session = new VirtualNodeSession("C", BoardProfile.NANO_AVR, () -> null);
        session.start();
        JsonObject cfg = new JsonObject();
        JsonArray districts = new JsonArray();
        JsonObject d = new JsonObject();
        d.addProperty("name", "Yard");
        d.addProperty("ref", "A7");
        JsonArray sensors = new JsonArray();
        sensors.add("A0");
        sensors.add("A1");
        d.add("sensors", sensors);
        d.addProperty("sensitivityPct", 22);
        districts.add(d);
        cfg.add("opticalDistricts", districts);
        cfg.add("outputs", new JsonArray());
        cfg.add("pins", new JsonArray());
        session.sendCommand("CFG " + cfg);
        assertEquals(22, session.getConfig().getAsJsonArray("opticalDistricts")
                .get(0).getAsJsonObject().get("sensitivityPct").getAsInt());
    }

    @Test
    void inventoryPersistsOpticalDistricts(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("nodes.json");
        NodeInventory inv = new NodeInventory(file);
        NodeInventory.NodeRecord rec = new NodeInventory.NodeRecord();
        rec.id = "shim_Z";
        rec.prefix = "Z";
        rec.boardType = BoardProfile.NANO_AVR;
        rec.transport = SensorBoardSupport.TRANSPORT_SHIM;
        JsonArray districts = new JsonArray();
        JsonObject d = new JsonObject();
        d.addProperty("name", "Main");
        d.addProperty("ref", "A7");
        JsonArray sensors = new JsonArray();
        sensors.add("A0");
        d.add("sensors", sensors);
        d.addProperty("sensitivityPct", 18);
        districts.add(d);
        rec.opticalDistricts = districts;
        JsonObject config = new JsonObject();
        config.add("opticalDistricts", districts);
        rec.config = config;
        inv.upsert(rec);
        inv.save();

        NodeInventory loaded = new NodeInventory(file);
        NodeInventory.NodeRecord again = loaded.findByPrefix("Z");
        assertNotNull(again);
        assertNotNull(again.opticalDistricts);
        assertEquals(1, again.opticalDistricts.size());
        assertEquals("A7", again.opticalDistricts.get(0).getAsJsonObject().get("ref").getAsString());
        assertEquals(18, again.opticalDistricts.get(0).getAsJsonObject().get("sensitivityPct").getAsInt());
    }

    @Test
    void parseSampleReplyExtractsPct() {
        JsonObject out = new JsonObject();
        DccIoServiceImpl.parseSampleReply("SAMPLE A15=812 A0=1020 delta=208 pct=25", out);
        assertEquals(25, out.get("pct").getAsInt());
        assertEquals(208, out.get("delta").getAsInt());
        assertEquals(812, out.get("A15").getAsInt());
        assertEquals(1020, out.get("A0").getAsInt());
    }

    private static int pctFrom(String sample) {
        int i = sample.indexOf("pct=");
        if (i < 0) {
            return -1;
        }
        String rest = sample.substring(i + 4).trim();
        int sp = rest.indexOf(' ');
        return Integer.parseInt(sp < 0 ? rest : rest.substring(0, sp));
    }
}
