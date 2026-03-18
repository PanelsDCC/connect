package cc.panelsd.connect.core.impl.nce;

import cc.panelsd.connect.core.AccessoryController;

import jmri.NmraPacket;
import jmri.jmrix.nce.NceBinaryCommand;
import jmri.jmrix.nce.NceMessage;
import jmri.jmrix.nce.NceTrafficController;

import java.io.IOException;

/**
 * Direct NCE accessory controller using the jmrix layer: binary
 * {@link NceBinaryCommand#accDecoder} + {@link NceMessage#createBinaryMessage}
 * when supported (OPTION_2006 or USB), otherwise NMRA packet +
 * {@link NceMessage#sendPacketMessage}. Same pattern as
 * {@link cc.panelsd.connect.core.impl.xnet.elite.DirectXNetAccessoryController}.
 */
public class DirectNceAccessoryController implements AccessoryController {

    private final String connectionId;
    private final NceTrafficController trafficController;

    public DirectNceAccessoryController(String connectionId, NceTrafficController trafficController) {
        this.connectionId = connectionId;
        this.trafficController = trafficController;
    }

    @Override
    public String getConnectionId() {
        return connectionId;
    }

    @Override
    public void setTurnout(int address, boolean closed) throws IOException {
        if (trafficController == null) {
            throw new IOException("NCE TrafficController not available for accessories");
        }
        if (address < 1) {
            throw new IllegalArgumentException("Accessory address must be >= 1");
        }

        boolean useBinary = trafficController.getCommandOptions() >= NceTrafficController.OPTION_2006
                || trafficController.getUsbSystem() != NceTrafficController.USB_SYSTEM_NONE;

        if (useBinary) {
            byte[] bl = NceBinaryCommand.accDecoder(address, closed);
            if (bl == null) {
                throw new IOException("Invalid NCE accessory address: " + address);
            }
            NceMessage m = NceMessage.createBinaryMessage(trafficController, bl);
            if (m == null) {
                throw new IOException("NCE binary accessory message not supported");
            }
            trafficController.sendNceMessage(m, null);
        } else {
            byte[] bl = NmraPacket.accDecoderPkt(address, closed);
            if (bl == null) {
                throw new IOException("Invalid accessory address: " + address);
            }
            NceMessage m = NceMessage.sendPacketMessage(trafficController, bl);
            if (m == null) {
                throw new IOException("NCE packet message not supported");
            }
            trafficController.sendNceMessage(m, null);
        }
    }

    @Override
    public void close() {
        // Nothing to dispose
    }
}
