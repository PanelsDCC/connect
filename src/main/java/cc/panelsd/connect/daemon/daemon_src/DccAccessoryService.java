package cc.panelsd.connect.daemon;

import cc.panelsd.connect.core.AccessoryController;
import cc.panelsd.connect.core.CommandStationConnection;
import cc.panelsd.connect.core.impl.DccIoServiceImpl;

import java.io.IOException;

/**
 * Adapter exposing accessory operations from DccIoServiceImpl to JSON handlers.
 * DCC track command (when a CS is connected) plus fan-out to all node boards.
 */
public class DccAccessoryService implements JsonAccessoriesHandler.AccessoryService {

    private final DccIoServiceImpl service;

    public DccAccessoryService(DccIoServiceImpl service) {
        this.service = service;
    }

    @Override
    public void setTurnout(int address, boolean closed) throws IOException {
        boolean sentToCommandStation = false;
        CommandStationConnection conn = service.getAccessoryController();
        if (conn != null && conn.isConnected()) {
            AccessoryController controller = conn.getAccessoryController();
            if (controller != null) {
                controller.setTurnout(address, closed);
                sentToCommandStation = true;
            }
        }
        // DCC-like: every node hears the address; boards filter locally via CFG
        service.broadcastAccessoryToNodes(address, closed);
        if (!sentToCommandStation) {
            // Node-only layouts are valid — no CS required when boards are present
            System.out.println("[accessories] No CS; broadcast " + address + " "
                    + (closed ? "ON" : "OFF") + " to nodes only");
        }
    }
}
