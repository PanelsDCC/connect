package cc.panelsd.connect.core.impl.sensor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardProfileTest {

    @Test
    void registersFourBoardTypes() {
        assertEquals(4, BoardProfile.all().size());
        assertTrue(BoardProfile.isValid(BoardProfile.NANO_AVR));
        assertTrue(BoardProfile.isValid(BoardProfile.MEGA_AVR));
        assertTrue(BoardProfile.isValid(BoardProfile.NANO_ESP32));
        assertTrue(BoardProfile.isValid(BoardProfile.MEGA_ESP32));
    }

    @Test
    void nanoDefaultsMatchLegacyChannelCount() {
        BoardProfile nano = BoardProfile.require(BoardProfile.NANO_AVR);
        assertEquals("arduino:avr:nano", nano.fqbn);
        assertEquals(7, nano.defaultOpticalLabels.size());
        assertEquals(11, nano.defaultDigitalLabels.size());
        assertEquals("A7", nano.ambientRefLabel);
        assertNotNull(nano.pinByLabel("D6"));
        assertTrue(nano.pinByLabel("D9").hasCapability("servo"));
        var channels = nano.defaultChannelDefs("A");
        assertEquals(18, channels.size());
        assertEquals("A-A0", channels.get(0).id);
        assertEquals("A0", channels.get(0).label);
        assertEquals("A-D12", channels.get(channels.size() - 1).id);
    }

    @Test
    void megaUsesSilkScreenBanks() {
        BoardProfile mega = BoardProfile.require(BoardProfile.MEGA_AVR);
        assertEquals("arduino:avr:mega", mega.fqbn);
        assertEquals(15, mega.defaultOpticalLabels.size());
        assertEquals(16, mega.defaultDigitalLabels.size());
        assertEquals("A15", mega.ambientRefLabel);
        assertEquals("D22", mega.defaultDigitalLabels.get(0));
        assertEquals("D37", mega.defaultDigitalLabels.get(15));
        assertTrue(mega.pinByLabel("D9").hasCapability("servo"));
        assertFalse(mega.pinByLabel("D22").hasCapability("servo"));
    }

    @Test
    void esp32KeepsSilkLabelsNotGpio() {
        BoardProfile esp = BoardProfile.require(BoardProfile.NANO_ESP32);
        assertEquals(SensorBoardSupport.TRANSPORT_BLE, esp.defaultTransport);
        assertEquals("D6", esp.pinByLabel("D6").silkLabel);
        assertTrue(esp.pinByLabel("D6").mcuPin.startsWith("ESP_"));
    }
}
