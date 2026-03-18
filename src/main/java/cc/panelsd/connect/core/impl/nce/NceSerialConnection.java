package cc.panelsd.connect.core.impl.nce;

import cc.panelsd.connect.core.AccessoryController;
import cc.panelsd.connect.core.ProgrammerSession;
import cc.panelsd.connect.core.SystemConfig;
import cc.panelsd.connect.core.ThrottleSession;
import cc.panelsd.connect.core.events.DccEvent;
import cc.panelsd.connect.core.events.DccEventBus;
import cc.panelsd.connect.core.events.DccEventType;
import cc.panelsd.connect.core.impl.common.BaseCommandStationConnection;
import cc.panelsd.connect.core.impl.common.JmriAccessoryController;
import cc.panelsd.connect.core.impl.common.JmriProgrammerSession;
import cc.panelsd.connect.core.impl.common.JmriThrottleSession;
import cc.panelsd.connect.core.impl.nce.DirectNceAccessoryController;
import cc.panelsd.connect.core.impl.nce.DirectNceThrottleSession;

import jmri.DccThrottle;
import jmri.GlobalProgrammerManager;
import jmri.InstanceManager;
import jmri.PowerManager;
import jmri.ThrottleListener;
import jmri.ThrottleManager;
import jmri.TurnoutManager;
import jmri.jmrix.SystemConnectionMemoManager;
import jmri.jmrix.nce.NceSystemConnectionMemo;
import jmri.jmrix.nce.NceTrafficController;
import jmri.jmrix.nce.serialdriver.SerialDriverAdapter;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * CommandStationConnection for NCE PowerCab over serial/USB, backed by JMRI NCE stack.
 */
public final class NceSerialConnection extends BaseCommandStationConnection {

    private final SerialDriverAdapter adapter;
    private final NceSystemConnectionMemo memo;

    private JmriAccessoryController accessoryController;
    private DirectNceAccessoryController directAccessoryController;
    private JmriProgrammerSession programmerSession;

    private final PropertyChangeListener powerListener = this::onPowerChange;

    public NceSerialConnection(SystemConfig config, DccEventBus eventBus) {
        super(config, eventBus);
        this.memo = new NceSystemConnectionMemo();
        this.adapter = new SerialDriverAdapter();
        this.adapter.setSystemConnectionMemo(memo);
        memo.setUserName(config.getUserName());
        memo.setSystemPrefix(config.getSystemPrefix());
        SystemConnectionMemoManager.getDefault().register(memo);
    }

    @Override
    public void connect() throws IOException {
        if (connected) {
            return;
        }
        String portName = config.getOption("portName");
        if (portName == null) {
            throw new IOException("Missing 'portName' option for NCE serial connection");
        }
        String error = adapter.openPort(portName, "dcc-io-daemon");
        if (error != null) {
            throw new IOException("Failed to open NCE port: " + error);
        }
        adapter.configure();
        connected = true;
        attachManagersAndListeners();
        publishConnectionState();
    }

    private void attachManagersAndListeners() {
        NceTrafficController tc = memo.getNceTrafficController();
        if (tc == null) {
            throw new IllegalStateException("NCE TrafficController not initialized");
        }

        TurnoutManager turnoutManager = memo.getTurnoutManager();
        if (turnoutManager != null) {
            accessoryController = new JmriAccessoryController(id, turnoutManager);
        }
        directAccessoryController = new DirectNceAccessoryController(id, tc);
        GlobalProgrammerManager gpm = InstanceManager.getNullableDefault(GlobalProgrammerManager.class);
        if (gpm != null) {
            programmerSession = new JmriProgrammerSession(id, gpm);
        }

        PowerManager pm = memo.getPowerManager();
        if (pm != null) {
            pm.addPropertyChangeListener(powerListener);
        }
    }

    @Override
    public ThrottleSession openThrottle(int address, boolean longAddress) throws IOException {
        ThrottleManager tm = memo.getThrottleManager();
        if (tm == null) {
            throw new IOException("No ThrottleManager available on NCE connection");
        }
        NceTrafficController tc = memo.getNceTrafficController();
        if (tc == null) {
            throw new IOException("NCE TrafficController not available");
        }
        final DccThrottle[] holder = new DccThrottle[1];
        final IOException[] error = new IOException[1];
        final Object lock = new Object();
        ThrottleListener listener = new ThrottleListener() {
            @Override
            public void notifyThrottleFound(DccThrottle t) {
                synchronized (lock) {
                    holder[0] = t;
                    lock.notifyAll();
                }
            }

            @Override
            public void notifyFailedThrottleRequest(jmri.LocoAddress addr, String reason) {
                synchronized (lock) {
                    error[0] = new IOException("Throttle request failed: " + reason);
                    lock.notifyAll();
                }
            }

            @Override
            public void notifyDecisionRequired(jmri.LocoAddress addr, ThrottleListener.DecisionType question) {
                synchronized (lock) {
                    error[0] = new IOException("Throttle address " + addr + " is in use, decision required: " + question);
                    lock.notifyAll();
                }
            }
        };
        tm.requestThrottle(address, longAddress, listener, false);
        synchronized (lock) {
            try {
                long timeout = 5000;
                long start = System.currentTimeMillis();
                while (holder[0] == null && error[0] == null) {
                    long remaining = timeout - (System.currentTimeMillis() - start);
                    if (remaining <= 0) {
                        throw new IOException("Timeout waiting for throttle for address " + address);
                    }
                    lock.wait(remaining);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for throttle", e);
            }
        }
        if (error[0] != null) {
            throw error[0];
        }
        if (holder[0] == null) {
            throw new IOException("Throttle not granted for address " + address);
        }
        DirectNceThrottleSession session = new DirectNceThrottleSession(id, address, longAddress, tc, holder[0]);
        try {
            session.setSpeed(0.0f);
            session.setDirection(true);
        } catch (Exception e) {
            // Ignore initial command errors
        }
        return session;
    }

    @Override
    public ProgrammerSession getProgrammer() {
        return programmerSession;
    }

    @Override
    public AccessoryController getAccessoryController() {
        if (directAccessoryController != null) {
            return directAccessoryController;
        }
        return accessoryController;
    }

    @Override
    public java.util.Map<String, String> getCommandStationInfo() {
        Map<String, String> info = new HashMap<>();
        info.put("manufacturer", "NCE");
        info.put("model", "PowerCab");
        // NCE doesn't provide version info in the same way, but we can identify it
        return info;
    }

    @Override
    public String getPowerStatus() {
        PowerManager pm = memo.getPowerManager();
        if (pm != null) {
            int power = pm.getPower();
            switch (power) {
                case PowerManager.ON:
                    return "ON";
                case PowerManager.OFF:
                    return "OFF";
                case PowerManager.IDLE:
                    return "IDLE";
                default:
                    return "UNKNOWN";
            }
        }
        return "UNKNOWN";
    }

    @Override
    public void setPower(String powerState) throws IOException {
        if (!connected) {
            throw new IOException("Not connected");
        }
        PowerManager pm = memo.getPowerManager();
        if (pm == null) {
            throw new IOException("PowerManager not available");
        }
        
        int powerValue;
        switch (powerState.toUpperCase()) {
            case "ON":
                powerValue = PowerManager.ON;
                break;
            case "OFF":
                powerValue = PowerManager.OFF;
                break;
            case "IDLE":
                powerValue = PowerManager.IDLE;
                break;
            default:
                throw new IllegalArgumentException("Invalid power state: " + powerState + ". Must be ON, OFF, or IDLE");
        }
        
        try {
            pm.setPower(powerValue);
        } catch (jmri.JmriException e) {
            throw new IOException("Failed to set power: " + e.getMessage(), e);
        }
    }

    private void onPowerChange(PropertyChangeEvent evt) {
        if (PowerManager.POWER.equals(evt.getPropertyName())) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("old", evt.getOldValue());
            payload.put("new", evt.getNewValue());
            eventBus.publish(new DccEvent(DccEventType.POWER_CHANGED, id, payload));
        }
    }

    @Override
    public void close() {
        connected = false;
        publishConnectionState();
        
        // Remove listeners first
        PowerManager pm = memo.getPowerManager();
        if (pm != null) {
            pm.removePropertyChangeListener(powerListener);
        }
        
        // Terminate traffic controller threads before closing port
        NceTrafficController tc = memo.getNceTrafficController();
        if (tc != null) {
            try {
                tc.terminateThreads();
            } catch (Exception e) {
                // Ignore errors during shutdown
            }
        }
        
        // Dispose adapter and memo (dispose() will close the port)
        if (adapter != null) {
            adapter.dispose();
        }
        if (memo != null) {
            memo.dispose();
        }
    }
}

