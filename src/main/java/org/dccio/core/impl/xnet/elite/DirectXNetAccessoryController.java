package org.dccio.core.impl.xnet.elite;

import org.dccio.core.AccessoryController;

import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetTrafficController;

import java.io.IOException;

/**
 * Direct XpressNet accessory controller that uses the jmrix layer only:
 * message building via {@link XNetMessage#getTurnoutCommandMsg} and sending
 * via {@link XNetTrafficController}. This bypasses JMRI's turnout bean layer
 * (TurnoutManager / XNetTurnout) for reliability, while reusing JMRI's
 * protocol encoding so we don't maintain protocol details ourselves.
 *
 * Same pattern can be used for other systems: use jmri.jmrix.&lt;proto&gt;
 * message factories and traffic controller to send, skip the bean managers.
 */
public class DirectXNetAccessoryController implements AccessoryController {

    private final String connectionId;
    private final XNetTrafficController trafficController;

    public DirectXNetAccessoryController(String connectionId, XNetTrafficController trafficController) {
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
            throw new IOException("XNetTrafficController not available for accessories");
        }
        if (address < 1) {
            throw new IllegalArgumentException("Accessory address must be >= 1");
        }

        // Use JMRI's jmrix message builder (XNetMessage) so we don't encode the
        // protocol ourselves. pNumber is 1-based; Elite has an off-by-one so
        // we pass address+1 to match EliteXNetTurnout behaviour. pOn=true for
        // normal accessory line on.
        int pNumber = address + 1;
        XNetMessage msg = XNetMessage.getTurnoutCommandMsg(
                pNumber,
                closed,
                !closed,
                true);
        trafficController.sendXNetMessage(msg, null);
    }

    @Override
    public void close() {
        // Nothing to dispose; traffic controller is owned by the connection.
    }
}

