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
                SensorBoardSession.parseOccupancyLine("A-A3: DETECTED");
        assertTrue(detected.isPresent());
        assertEquals("A-A3", detected.get().hardwareId);
        assertEquals(1, detected.get().state);

        Optional<SensorBoardSession.SensorEdge> clear =
                SensorBoardSession.parseOccupancyLine("AB-A0: CLEAR");
        assertTrue(clear.isPresent());
        assertEquals("AB-A0", clear.get().hardwareId);
        assertEquals(0, clear.get().state);

        Optional<SensorBoardSession.SensorEdge> digital =
                SensorBoardSession.parseOccupancyLine("ZZ-D12: DETECTED");
        assertTrue(digital.isPresent());
        assertEquals("ZZ-D12", digital.get().hardwareId);
        assertEquals(1, digital.get().state);
    }

    @Test
    void parseIgnoresReadyAndNoise() {
        assertFalse(SensorBoardSession.parseOccupancyLine("PanelsDCC Sensor Board A READY").isPresent());
        assertFalse(SensorBoardSession.parseOccupancyLine("").isPresent());
        assertFalse(SensorBoardSession.parseOccupancyLine(null).isPresent());
        assertFalse(SensorBoardSession.parseOccupancyLine("A3 occupied").isPresent());
    }
}
