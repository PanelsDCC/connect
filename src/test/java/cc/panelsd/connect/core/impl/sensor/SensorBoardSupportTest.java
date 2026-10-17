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
        assertEquals("A-A0", SensorBoardSupport.analogHardwareId("A", 0));
        assertEquals("A-A1", SensorBoardSupport.analogHardwareId("A", 1));
        assertEquals("A-D2", SensorBoardSupport.digitalHardwareId("A", 2));
        assertEquals("AB-D12", SensorBoardSupport.digitalHardwareId("AB", 12));
        assertEquals("ZZ-A3", SensorBoardSupport.analogHardwareId("ZZ", 3));

        assertTrue(SensorBoardSupport.isValidHardwareId("A-A1"));
        assertTrue(SensorBoardSupport.isValidHardwareId("AB-D12"));
        assertTrue(SensorBoardSupport.isValidHardwareId("A-V1"));
        assertTrue(SensorBoardSupport.isValidHardwareId("AB-V12"));
        assertEquals("A-V3", SensorBoardSupport.virtualHardwareId("A", 3));
        assertEquals("V3", SensorBoardSupport.normalizeVirtualLabel("v3"));
        assertEquals("V3", SensorBoardSupport.normalizeVirtualLabel("A-V3"));
        assertEquals("V12", SensorBoardSupport.normalizeVirtualLabel("12"));
        assertFalse(SensorBoardSupport.isValidHardwareId("A0"));
        assertFalse(SensorBoardSupport.isValidHardwareId("AD2"));
        assertFalse(SensorBoardSupport.isValidHardwareId("A-X1"));
        assertEquals(null, SensorBoardSupport.normalizeVirtualLabel("V0"));
        assertEquals(null, SensorBoardSupport.normalizeVirtualLabel("V13"));

        var channels = SensorBoardSupport.channelDefs("A");
        assertEquals(7 + 11, channels.size());
        assertEquals("A-A0", channels.get(0).id);
        assertEquals("analog", channels.get(0).kind);
        assertEquals("A-A6", channels.get(6).id);
        assertEquals("A-D2", channels.get(7).id);
        assertEquals("digital", channels.get(7).kind);
        assertEquals("A-D12", channels.get(channels.size() - 1).id);

        var mega = SensorBoardSupport.channelDefs("B", BoardProfile.MEGA_AVR);
        assertEquals(15 + 16, mega.size());
        assertEquals("B-A0", mega.get(0).id);
        assertEquals("B-D22", mega.get(15).id);
        assertEquals("B-D37", mega.get(mega.size() - 1).id);
    }

    @Test
    void nextFreePrefixSkipsUsed() {
        assertEquals("A", SensorBoardSupport.nextFreePrefix(java.util.List.of()));
        assertEquals("C", SensorBoardSupport.nextFreePrefix(java.util.List.of("A", "B")));
    }
}
