package org.dccio.core.impl.xnet.elite;

import org.dccio.core.ThrottleSession;
import org.dccio.core.events.DccEventBus;

import jmri.DccThrottle;
import jmri.SpeedStepMode;
import jmri.jmrix.lenz.XNetMessage;
import jmri.jmrix.lenz.XNetTrafficController;

import java.io.IOException;

/**
 * Direct XpressNet throttle session that uses the jmrix layer only for
 * speed/direction: message building via {@link XNetMessage#getSpeedAndDirectionMsg}
 * and sending via {@link XNetTrafficController}. This bypasses JMRI's throttle
 * bean layer for reliability while reusing JMRI's protocol encoding.
 * Functions still use the JMRI throttle for compatibility.
 */
public class DirectXNetThrottleSession implements ThrottleSession {

    private final String connectionId;
    private final int address;
    private final boolean longAddress;
    private final XNetTrafficController trafficController;
    private final DccEventBus eventBus;
    private final DccThrottle jmriThrottle; // Used for functions only
    
    // Track state internally (for speed/direction sent directly)
    private volatile float currentSpeed = 0.0f;
    private volatile boolean currentDirection = true; // forward

    public DirectXNetThrottleSession(String connectionId, int address, boolean longAddress,
                                     XNetTrafficController trafficController, DccEventBus eventBus,
                                     DccThrottle jmriThrottle) {
        this.connectionId = connectionId;
        this.address = address;
        this.longAddress = longAddress;
        this.trafficController = trafficController;
        this.eventBus = eventBus;
        this.jmriThrottle = jmriThrottle;
    }

    @Override
    public String getConnectionId() {
        return connectionId;
    }

    @Override
    public int getAddress() {
        return address;
    }

    @Override
    public boolean isLongAddress() {
        return longAddress;
    }

    @Override
    public void setSpeed(float speed) throws IOException {
        if (speed < 0 || speed > 1) {
            throw new IllegalArgumentException("Speed must be between 0.0 and 1.0");
        }

        XNetMessage msg = XNetMessage.getSpeedAndDirectionMsg(
                address,
                SpeedStepMode.NMRA_DCC_128,
                speed,
                currentDirection);
        trafficController.sendXNetMessage(msg, null);

        currentSpeed = speed;
    }

    @Override
    public void setDirection(boolean forward) throws IOException {
        if (currentDirection == forward) {
            return;
        }
        currentDirection = forward;

        XNetMessage msg = XNetMessage.getSpeedAndDirectionMsg(
                address,
                SpeedStepMode.NMRA_DCC_128,
                currentSpeed,
                currentDirection);
        trafficController.sendXNetMessage(msg, null);
    }

    @Override
    public void setFunction(int functionNumber, boolean on) throws IOException {
        // Use JMRI throttle for functions (they work fine)
        if (jmriThrottle != null) {
            jmriThrottle.setFunction(functionNumber, on);
        } else {
            throw new IOException("JMRI throttle not available for function control");
        }
    }

    @Override
    public float getSpeed() {
        return currentSpeed;
    }

    @Override
    public boolean getDirection() {
        return currentDirection;
    }

    @Override
    public boolean getFunction(int functionNumber) {
        // Use JMRI throttle for functions
        if (jmriThrottle != null) {
            return jmriThrottle.getFunction(functionNumber);
        }
        return false;
    }

    @Override
    public void close() {
        // Release JMRI throttle (used for functions)
        if (jmriThrottle != null) {
            jmriThrottle.release(null);
        }
    }
}
