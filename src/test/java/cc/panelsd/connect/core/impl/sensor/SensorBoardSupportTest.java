package cc.panelsd.connect.core.impl.sensor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensorBoardSupportTest {

    @Test
    void validatesSpreadsheetStylePrefixes() {
        assertTrue(SensorBoardSupport.isValidPrefix("A"));
        assertTrue(SensorBoardSupport.isValidPrefix("Z"));
        assertTrue(SensorBoardSupport.isValidPrefix("AA"));
        assertTrue(SensorBoardSupport.isValidPrefix("AB"));
        assertFalse(SensorBoardSupport.isValidPrefix(""));
        assertFalse(SensorBoardSupport.isValidPrefix("a"));
        assertFalse(SensorBoardSupport.isValidPrefix("AAA"));
        assertFalse(SensorBoardSupport.isValidPrefix("A1"));
        assertFalse(SensorBoardSupport.isValidPrefix(null));
    }

    @Test
    void normalizesPrefix() {
        assertEquals("AA", SensorBoardSupport.normalizePrefix(" aa "));
    }

    @Test
    void sessionIdAndDevicePath() {
        assertEquals("sensor_ttyUSB0", SensorBoardSupport.sessionIdForPort("ttyUSB0"));
        assertEquals("sensor_ttyUSB0", SensorBoardSupport.sessionIdForPort("/dev/ttyUSB0"));
        assertEquals("/dev/ttyUSB0", SensorBoardSupport.devicePath("ttyUSB0"));
    }

    @Test
    void channelIdsForPrefix() {
        assertEquals("A0", SensorBoardSupport.analogHardwareId("A", 0));
        assertEquals("AD2", SensorBoardSupport.digitalHardwareId("A", 2));
        assertEquals("ABD12", SensorBoardSupport.digitalHardwareId("AB", 12));

        var channels = SensorBoardSupport.channelDefs("A");
        assertEquals(7 + 11, channels.size());
        assertEquals("A0", channels.get(0).id);
        assertEquals("analog", channels.get(0).kind);
        assertEquals("A6", channels.get(6).id);
        assertEquals("AD2", channels.get(7).id);
        assertEquals("digital", channels.get(7).kind);
        assertEquals("AD12", channels.get(channels.size() - 1).id);
    }
}
