package cc.panelsd.connect.updater;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectUpdaterTest {

    @Test
    void normalizeDebianVersionToSemver_stripsPackagingRevision() {
        assertEquals("0.3.0", ConnectUpdater.normalizeDebianVersionToSemver("0.3.0-1"));
        assertEquals("0.3.0", ConnectUpdater.normalizeDebianVersionToSemver("1:0.3.0-1"));
    }

    @Test
    void isNewerVersion_comparesSemver() {
        assertTrue(ConnectUpdater.isNewerVersion("0.4.0", "0.3.0"));
        assertFalse(ConnectUpdater.isNewerVersion("0.3.0", "0.3.0"));
        assertFalse(ConnectUpdater.isNewerVersion("0.2.0", "0.3.0"));
    }
}
