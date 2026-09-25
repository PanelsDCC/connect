package cc.panelsd.connect.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class UsbProbeCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsAndReloadsEntries() {
        Path file = tempDir.resolve("usb-probe-cache.json");
        UsbProbeCache cache = new UsbProbeCache(file);
        cache.put("platform-xhci-hcd.0-usb-0:1.0", DeviceDiscoveryService.SYSTEM_TYPE_SENSOR_BOARD, "A");
        cache.put("platform-xhci-hcd.1-usb-0:1.0", DeviceDiscoveryService.SYSTEM_TYPE_DCCPP, null);

        UsbProbeCache reloaded = new UsbProbeCache(file);
        UsbProbeCache.Entry sensor = reloaded.get("platform-xhci-hcd.0-usb-0:1.0");
        assertNotNull(sensor);
        assertEquals(DeviceDiscoveryService.SYSTEM_TYPE_SENSOR_BOARD, sensor.type);
        assertEquals("A", sensor.prefix);

        UsbProbeCache.Entry dcc = reloaded.get("platform-xhci-hcd.1-usb-0:1.0");
        assertNotNull(dcc);
        assertEquals(DeviceDiscoveryService.SYSTEM_TYPE_DCCPP, dcc.type);
        assertNull(dcc.prefix);
    }
}
