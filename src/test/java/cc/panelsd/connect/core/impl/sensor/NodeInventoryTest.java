package cc.panelsd.connect.core.impl.sensor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NodeInventoryTest {

    @TempDir
    Path temp;

    @Test
    void persistsShimRecordsAndSuggestsPrefix() throws Exception {
        Path file = temp.resolve("nodes.json");
        NodeInventory inv = new NodeInventory(file);
        assertEquals("A", inv.suggestPrefix());

        NodeInventory.NodeRecord shim = NodeInventory.NodeRecord.shim("A", BoardProfile.MEGA_AVR);
        inv.upsert(shim);
        assertEquals("B", inv.suggestPrefix());

        NodeInventory reloaded = new NodeInventory(file);
        assertEquals(1, reloaded.list().size());
        NodeInventory.NodeRecord got = reloaded.findByPrefix("A");
        assertNotNull(got);
        assertEquals(BoardProfile.MEGA_AVR, got.boardType);
        assertEquals(SensorBoardSupport.TRANSPORT_SHIM, got.transport);
    }
}
