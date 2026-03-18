package cc.panelsd.connect.core;

import java.io.Closeable;
import java.io.IOException;

/**
 * Accessory operations (turnouts, routes, signals, etc.) bound to a single
 * logical command station connection.
 * <p>
 * Implementations are expected to delegate to JMRI turnout / light /
 * signal managers, e.g. {@code XNetTurnoutManager}, {@code DCCppTurnoutManager}.
 */
public interface AccessoryController extends Closeable {

    /**
     * @return the owning logical connection id.
     */
    String getConnectionId();

    /**
     * Set turnout state.
     *
     * @param address turnout address in the command station's numbering scheme
     * @param closed  true for CLOSED, false for THROWN
     */
    void setTurnout(int address, boolean closed) throws IOException;

    @Override
    void close();
}


