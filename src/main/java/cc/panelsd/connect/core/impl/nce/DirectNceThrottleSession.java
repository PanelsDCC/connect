package cc.panelsd.connect.core.impl.nce;

import cc.panelsd.connect.core.ThrottleSession;

import jmri.DccThrottle;
import jmri.jmrix.nce.NceBinaryCommand;
import jmri.jmrix.nce.NceMessage;
import jmri.jmrix.nce.NceTrafficController;

import java.io.IOException;

/**
 * Direct NCE throttle session using the jmrix layer only for speed/direction:
 * {@link NceBinaryCommand#nceLocoCmd} + {@link NceMessage#createBinaryMessage}
 * and {@link NceTrafficController#sendNceMessage}. Follows the same pattern
 * as {@link cc.panelsd.connect.core.impl.xnet.elite.DirectXNetThrottleSession}.
 * Functions use the JMRI throttle.
 */
public class DirectNceThrottleSession implements ThrottleSession {

    private final String connectionId;
    private final int address;
    private final boolean longAddress;
    private final NceTrafficController trafficController;
    private final DccThrottle jmriThrottle;

    private volatile float currentSpeed = 0.0f;
    private volatile boolean currentDirection = true;

    public DirectNceThrottleSession(String connectionId, int address, boolean longAddress,
                                   NceTrafficController trafficController, DccThrottle jmriThrottle) {
        this.connectionId = connectionId;
        this.address = address;
        this.longAddress = longAddress;
        this.trafficController = trafficController;
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

    private int getLocoAddr() {
        int locoAddr = address;
        if (longAddress) {
            locoAddr += 0xC000;
        }
        return locoAddr;
    }

    @Override
    public void setSpeed(float speed) throws IOException {
        if (speed < 0 || speed > 1) {
            throw new IllegalArgumentException("Speed must be between 0.0 and 1.0");
        }
        int locoAddr = getLocoAddr();
        byte[] bl;
        int value = Math.round((127 - 1) * speed);
        if (speed > 0 && value == 0) value = 1;
        if (value > 126) value = 126;
        if (speed < 0) {
            bl = NceBinaryCommand.nceLocoCmd(locoAddr,
                    currentDirection ? NceMessage.LOCO_CMD_FWD_ESTOP : NceMessage.LOCO_CMD_REV_ESTOP,
                    (byte) 0);
        } else {
            bl = NceBinaryCommand.nceLocoCmd(locoAddr,
                    currentDirection ? NceMessage.LOCO_CMD_FWD_128SPEED : NceMessage.LOCO_CMD_REV_128SPEED,
                    (byte) value);
        }
        if (bl == null) {
            throw new IOException("NCE loco command not supported");
        }
        NceMessage m = NceMessage.createBinaryMessage(trafficController, bl);
        if (m == null) {
            throw new IOException("NCE binary message not supported");
        }
        trafficController.sendNceMessage(m, null);
        currentSpeed = speed;
    }

    @Override
    public void setDirection(boolean forward) throws IOException {
        if (currentDirection == forward) return;
        currentDirection = forward;
        int locoAddr = getLocoAddr();
        int value = Math.round(currentSpeed * 126);
        if (currentSpeed > 0 && value == 0) value = 1;
        if (value > 126) value = 126;
        byte[] bl = NceBinaryCommand.nceLocoCmd(locoAddr,
                currentDirection ? NceMessage.LOCO_CMD_FWD_128SPEED : NceMessage.LOCO_CMD_REV_128SPEED,
                (byte) value);
        if (bl == null) {
            throw new IOException("NCE loco command not supported");
        }
        NceMessage m = NceMessage.createBinaryMessage(trafficController, bl);
        if (m == null) {
            throw new IOException("NCE binary message not supported");
        }
        trafficController.sendNceMessage(m, null);
    }

    @Override
    public void setFunction(int functionNumber, boolean on) throws IOException {
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
        if (jmriThrottle != null) {
            return jmriThrottle.getFunction(functionNumber);
        }
        return false;
    }

    @Override
    public void close() {
        if (jmriThrottle != null) {
            jmriThrottle.release(null);
        }
    }
}
