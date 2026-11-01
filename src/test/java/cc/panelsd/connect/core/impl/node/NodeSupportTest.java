package cc.panelsd.connect.core.impl.node;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeSupportTest {

    @Test
    void macCuAndTtyAreTheSameChip() {
        assertEquals("usbserial-1140", NodeSupport.serialDeviceKey("cu.usbserial-1140"));
        assertEquals("usbserial-1140", NodeSupport.serialDeviceKey("/dev/tty.usbserial-1140"));
        assertEquals("ttyUSB0", NodeSupport.serialDeviceKey("/dev/ttyUSB0"));
    }
}
