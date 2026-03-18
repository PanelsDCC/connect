package cc.panelsd.connect.core;

import java.io.Closeable;
import java.io.IOException;

/**
 * Represents an active throttle session for a single locomotive on a given
 * command station connection.
 * <p>
 * Implementations are expected to wrap JMRI throttle implementations such as
 * {@code jmri.jmrix.lenz.XNetThrottle},
 * {@code jmri.jmrix.lenz.hornbyelite.EliteXNetThrottle} or
 * {@code jmri.jmrix.dccpp.DCCppThrottle}.
 */
public interface ThrottleSession extends Closeable {

    /**
     * @return the owning logical connection id.
     */
    String getConnectionId();

    /**
     * @return the DCC address for this session.
     */
    int getAddress();

    /**
     * @return true if this is a long address.
     */
    boolean isLongAddress();

    /**
     * Set speed in the range {@code 0.0 <= speed <= 1.0}.
     *
     * @param speed normalized speed value
     */
    void setSpeed(float speed) throws IOException;

    /**
     * Set direction.
     *
     * @param forward true for forward, false for reverse
     */
    void setDirection(boolean forward) throws IOException;

    /**
     * Set a function state (F0..Fn).
     */
    void setFunction(int functionNumber, boolean on) throws IOException;

    /**
     * @return the current speed setting (0.0 to 1.0).
     */
    float getSpeed();

    /**
     * @return the current direction (true for forward, false for reverse).
     */
    boolean getDirection();

    /**
     * @return the current state of a function (F0..Fn).
     */
    boolean getFunction(int functionNumber);

    /**
     * Release any underlying JMRI throttle allocations but keep the connection
     * itself alive.
     */
    @Override
    void close();
}


