package cc.panelsd.connect.core.impl.node;

import cc.panelsd.connect.core.UsbSerialProbe.ProbeResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
    void listsOnlyRecognisedArduinosWithType() {
        List<String> probed = new ArrayList<>();
        FlashPortScanner scanner = new FlashPortScanner(port -> {
            probed.add(port);
            if ("cu.usbserial-node".equals(port)) {
                return ProbeResult.node("A", BoardProfile.NANO_AVR);
            }
            return ProbeResult.unknown();
        }, Runnable::run);

        List<FlashPortScanner.RawPort> ports = List.of(
                new FlashPortScanner.RawPort("cu.debug-console", 0, 0, "debug"),
                new FlashPortScanner.RawPort("cu.Bluetooth-Incoming-Port", 0, 0, "Bluetooth"),
                new FlashPortScanner.RawPort("cu.wlan-debug", 0, 0, "wlan"),
                new FlashPortScanner.RawPort("cu.usbmodemSN234567892", 0x291A, 0x1234, "Anker"),
                new FlashPortScanner.RawPort("cu.usbmodem28848546E0682", 0x2341, 0x0070, "Arduino Nano ESP32"),
                new FlashPortScanner.RawPort("cu.usbmodem1401", 0x303A, 0x1001, "USB JTAG/serial debug unit"),
                new FlashPortScanner.RawPort("cu.usbserial-node", 0x1A86, 0x7523, "USB Serial"),
                new FlashPortScanner.RawPort("cu.usbserial-cs", 0x1A86, 0x7523, "USB Serial"));

        List<FlashPortScanner.ListedPort> listed = scanner.list(
                ports, Map.of(), Set.of("cu.usbserial-cs"));

        assertEquals(3, listed.size());
        assertEquals("cu.usbmodem28848546E0682", listed.get(0).port);
        assertEquals(BoardProfile.NANO_ESP32, listed.get(0).boardType);
        assertEquals("Arduino Nano ESP32 (3.3V)", listed.get(0).label);
        assertEquals("cu.usbmodem1401", listed.get(1).port);
        assertEquals("ESP32-S3 bootloader", listed.get(1).label);
        assertEquals("cu.usbserial-node", listed.get(2).port);
        assertEquals(BoardProfile.NANO_AVR, listed.get(2).boardType);
        assertEquals("Arduino Nano (5V AVR)", listed.get(2).label);
        assertEquals(List.of("cu.usbserial-node"), probed);
    }

    @Test
    void doesNotOpenABoardAlreadyNamedByUsb() {
        AtomicInteger calls = new AtomicInteger();
        FlashPortScanner scanner = new FlashPortScanner(port -> {
            calls.incrementAndGet();
            return ProbeResult.node("A", BoardProfile.NANO_ESP32);
        }, Runnable::run);

        List<FlashPortScanner.ListedPort> listed = scanner.list(List.of(
                new FlashPortScanner.RawPort("cu.usbmodem28848546E0682", 0x2341, 0x0070, "Nano ESP32")),
                Map.of(), Set.of());

        assertEquals(1, listed.size());
        assertEquals(0, calls.get());
    }

    @Test
    void usesTheOpenSessionTypeWithoutProbing() {
        AtomicInteger calls = new AtomicInteger();
        FlashPortScanner scanner = new FlashPortScanner(port -> {
            calls.incrementAndGet();
            return ProbeResult.unknown();
        }, Runnable::run);

        List<FlashPortScanner.ListedPort> listed = scanner.list(List.of(
                new FlashPortScanner.RawPort("cu.usbserial-1", 0x1A86, 0x7523, "USB Serial")),
                Map.of("cu.usbserial-1", BoardProfile.MEGA_ESP32), Set.of());

        assertEquals(1, listed.size());
        assertEquals(BoardProfile.MEGA_ESP32, listed.get(0).boardType);
        assertTrue(listed.get(0).label.contains("ESP32"));
        assertEquals(0, calls.get());
    }
}
