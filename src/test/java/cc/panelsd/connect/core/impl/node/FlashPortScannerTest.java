package cc.panelsd.connect.core.impl.node;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlashPortScannerTest {

    @Test
    void namesOfficialBoardsFromUsbIdentity() {
        assertEquals(BoardProfile.NANO_ESP32,
                FlashPortScanner.boardTypeForIdentity(0x2341, 0x0070, "Arduino Nano ESP32"));
        assertEquals(BoardProfile.MEGA_AVR,
                FlashPortScanner.boardTypeForIdentity(0x2341, 0x0042, "Arduino Mega 2560"));
        assertEquals(BoardProfile.NANO_AVR,
                FlashPortScanner.boardTypeForIdentity(0x0403, 0x6001, "Arduino Nano"));
        assertNull(FlashPortScanner.boardTypeForIdentity(0x2341, 0x0043, "Arduino Uno"));
        assertNull(FlashPortScanner.boardTypeForIdentity(0x1A86, 0x7523, "USB Serial"));
        assertNull(FlashPortScanner.boardTypeForIdentity(0x303A, 0x1001, "USB JTAG/serial debug unit"));
    }

    @Test
    void listsUartBridgesEvenWhenTheyMightBeCommandStations() {
        FlashPortScanner scanner = new FlashPortScanner();

        List<FlashPortScanner.RawPort> ports = List.of(
                new FlashPortScanner.RawPort("cu.debug-console", 0, 0, "debug"),
                new FlashPortScanner.RawPort("cu.Bluetooth-Incoming-Port", 0, 0, "Bluetooth"),
                new FlashPortScanner.RawPort("cu.wlan-debug", 0, 0, "wlan"),
                new FlashPortScanner.RawPort("cu.usbmodemSN234567892", 0x291A, 0x1234, "Anker"),
                new FlashPortScanner.RawPort("cu.usbmodem28848546E0682", 0x2341, 0x0070, "Arduino Nano ESP32"),
                new FlashPortScanner.RawPort("cu.usbmodem1401", 0x303A, 0x1001, "USB JTAG/serial debug unit"),
                new FlashPortScanner.RawPort("cu.usbserial-blank", 0x1A86, 0x7523, "USB Serial"),
                new FlashPortScanner.RawPort("cu.usbserial-cs", 0x1A86, 0x7523, "USB Serial"));

        // commandStationPorts are no longer hidden — user must not flash the wrong device.
        List<FlashPortScanner.ListedPort> listed = scanner.list(
                ports, Map.of(), Set.of("cu.usbserial-cs"));

        assertEquals(4, listed.size());
        assertEquals("cu.usbmodem28848546E0682", listed.get(0).port);
        assertEquals(BoardProfile.NANO_ESP32, listed.get(0).boardType);
        assertEquals("cu.usbmodem1401", listed.get(1).port);
        assertEquals("ESP32-S3 bootloader", listed.get(1).label);
        assertEquals("cu.usbserial-blank", listed.get(2).port);
        assertEquals(NodeFlasher.DETECT, listed.get(2).boardType);
        assertTrue(listed.get(2).label.toLowerCase().contains("ch340"));
        assertEquals("cu.usbserial-cs", listed.get(3).port);
        assertEquals(NodeFlasher.DETECT, listed.get(3).boardType);
    }

    @Test
    void usesTheOpenSessionTypeWithoutUsbGuess() {
        FlashPortScanner scanner = new FlashPortScanner();

        List<FlashPortScanner.ListedPort> listed = scanner.list(List.of(
                new FlashPortScanner.RawPort("cu.usbserial-1", 0x1A86, 0x7523, "USB Serial")),
                Map.of("cu.usbserial-1", BoardProfile.MEGA_ESP32), Set.of());

        assertEquals(1, listed.size());
        assertEquals(BoardProfile.MEGA_ESP32, listed.get(0).boardType);
        assertTrue(listed.get(0).label.contains("ESP32"));
    }
}
