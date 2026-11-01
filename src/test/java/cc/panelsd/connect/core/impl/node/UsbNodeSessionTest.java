package cc.panelsd.connect.core.impl.node;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsbNodeSessionTest {

    @Test
    void parseDetectedAndClear() {
        Optional<UsbNodeSession.SensorEdge> detected =
                UsbNodeSession.parseOccupancyLine("A-A3: DETECTED");
        assertTrue(detected.isPresent());
        assertEquals("A-A3", detected.get().hardwareId);
        assertEquals(1, detected.get().state);

        Optional<UsbNodeSession.SensorEdge> clear =
                UsbNodeSession.parseOccupancyLine("AB-A0: CLEAR");
        assertTrue(clear.isPresent());
        assertEquals("AB-A0", clear.get().hardwareId);
        assertEquals(0, clear.get().state);

        Optional<UsbNodeSession.SensorEdge> digital =
                UsbNodeSession.parseOccupancyLine("ZZ-D12: DETECTED");
        assertTrue(digital.isPresent());
        assertEquals("ZZ-D12", digital.get().hardwareId);
        assertEquals(1, digital.get().state);
    }

    @Test
    void parseIgnoresReadyAndNoise() {
        assertFalse(UsbNodeSession.parseOccupancyLine("PanelsDCC Node A READY").isPresent());
        assertFalse(UsbNodeSession.parseOccupancyLine("").isPresent());
        assertFalse(UsbNodeSession.parseOccupancyLine(null).isPresent());
        assertFalse(UsbNodeSession.parseOccupancyLine("A3 occupied").isPresent());
    }

    @Test
    void cfgReplyIsReturnedToTheWaiter() throws Exception {
        UsbNodeSession session = new UsbNodeSession(
                "/dev/cu.test", "B", null, () -> null);
        AtomicReference<String> reply = new AtomicReference<>();
        Thread waiter = new Thread(() -> reply.set(session.sendCommandAwaitReply("CFG?", "CFG", 1000L)));
        waiter.start();
        Thread.sleep(50);
        session.handleLine("A-A0: DETECTED");
        session.handleLine("CFG {\"pins\":[{\"label\":\"A0\",\"role\":\"optical\"}]}");
        waiter.join(1500);
        assertEquals("CFG {\"pins\":[{\"label\":\"A0\",\"role\":\"optical\"}]}", reply.get());
    }
}
