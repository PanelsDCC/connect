package cc.panelsd.connect.core.impl.sensor;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensorBoardSessionTest {

    @Test
    void parseDetectedAndClear() {
        Optional<SensorBoardSession.SensorEdge> detected =
                SensorBoardSession.parseOccupancyLine("A3: DETECTED");
        assertTrue(detected.isPresent());
        assertEquals("A3", detected.get().hardwareId);
        assertEquals(1, detected.get().state);

        Optional<SensorBoardSession.SensorEdge> clear =
                SensorBoardSession.parseOccupancyLine("AB0: CLEAR");
        assertTrue(clear.isPresent());
        assertEquals("AB0", clear.get().hardwareId);
        assertEquals(0, clear.get().state);
    }

    @Test
    void parseIgnoresReadyAndNoise() {
        assertFalse(SensorBoardSession.parseOccupancyLine("PanelsDCC Sensor Board A READY").isPresent());
        assertFalse(SensorBoardSession.parseOccupancyLine("").isPresent());
        assertFalse(SensorBoardSession.parseOccupancyLine(null).isPresent());
        assertFalse(SensorBoardSession.parseOccupancyLine("A3 occupied").isPresent());
    }
}
