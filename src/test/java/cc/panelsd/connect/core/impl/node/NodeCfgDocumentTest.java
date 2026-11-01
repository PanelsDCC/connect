package cc.panelsd.connect.core.impl.node;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeCfgDocumentTest {

    @Test
    void parsesABakedReplyIntoInventoryFields() {
        JsonObject cfg = NodeCfgDocument.parse(
                "CFG {\"boardType\":\"nano-avr\",\"pins\":[{\"label\":\"D2\",\"role\":\"digital_in\"}],"
                        + "\"opticalDistricts\":[{\"name\":\"Yard\",\"ref\":\"A7\",\"sensors\":[\"A0\"],\"sensitivityPct\":20}],"
                        + "\"outputs\":[{\"name\":\"Point\",\"type\":\"servo\",\"pins\":[\"D9\"]}]}");
        NodeInventory.NodeRecord rec = new NodeInventory.NodeRecord();
        NodeCfgDocument.copyOnto(rec, cfg);
        assertEquals("nano-avr", rec.boardType);
        assertEquals(1, rec.pins.size());
        assertEquals("D2", rec.pins.get(0).getAsJsonObject().get("label").getAsString());
        assertEquals("Yard", rec.opticalDistricts.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("servo", rec.outputs.get(0).getAsJsonObject().get("type").getAsString());
        assertTrue(rec.config.has("outputs"));
    }

    @Test
    void ignoresAnEmptyReply() {
        assertNull(NodeCfgDocument.parse("CFG {}"));
        assertNull(NodeCfgDocument.parse("{}"));
        assertNull(NodeCfgDocument.parse("CFG not-json"));
        assertNull(NodeCfgDocument.parse(null));
    }
}
