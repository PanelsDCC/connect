package org.dccio.core.impl.common;

import org.dccio.core.AccessoryController;

import jmri.Turnout;
import jmri.TurnoutManager;

import java.io.IOException;

/**
 * Wraps JMRI turnout operations behind the {@link AccessoryController}
 * abstraction. This currently focuses on turnouts; routes and other
 * accessories can be added later.
 */
public class JmriAccessoryController implements AccessoryController {

    private final String connectionId;
    private final TurnoutManager turnoutManager;

    public JmriAccessoryController(String connectionId, TurnoutManager turnoutManager) {
        this.connectionId = connectionId;
        this.turnoutManager = turnoutManager;
    }

    @Override
    public String getConnectionId() {
        return connectionId;
    }

    @Override
    public void setTurnout(int address, boolean closed) throws IOException {
        if (turnoutManager == null) {
            throw new IOException("No TurnoutManager available on this connection");
        }
        String systemName = turnoutManager.getSystemPrefix() + "T" + address;
        Turnout turnout = turnoutManager.provideTurnout(systemName);
        turnout.setCommandedState(closed ? Turnout.CLOSED : Turnout.THROWN);
    }

    @Override
    public void close() {
        // turnouts are owned by JMRI; nothing to dispose here
    }
}


