package cc.panelsd.connect.daemon;

import cc.panelsd.connect.core.AccessoryController;
import cc.panelsd.connect.core.CommandStationConnection;
import cc.panelsd.connect.core.impl.DccIoServiceImpl;

import java.io.IOException;

/**
 * Adapter exposing accessory operations from DccIoServiceImpl to JSON handlers.
 */
public class DccAccessoryService implements JsonAccessoriesHandler.AccessoryService {

    private final DccIoServiceImpl service;

    public DccAccessoryService(DccIoServiceImpl service) {
        this.service = service;
    }

    @Override
    public void setTurnout(int address, boolean closed) throws IOException {
        CommandStationConnection conn = service.getAccessoryController();
        if (conn == null) {
            throw new IllegalStateException("No accessory controller available");
        }
        if (!conn.isConnected()) {
            throw new IllegalStateException("Accessory controller not connected");
        }
        AccessoryController controller = conn.getAccessoryController();
        if (controller == null) {
            throw new IllegalStateException("Accessory controller not available");
        }
        controller.setTurnout(address, closed);
    }
}

