package cc.panelsd.connect.core.impl.dccpp;

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

import jmri.DccThrottle;
import jmri.GlobalProgrammerManager;
import jmri.InstanceManager;
import jmri.PowerManager;
import jmri.ThrottleListener;
import jmri.ThrottleManager;
import jmri.TurnoutManager;
import jmri.jmrix.SystemConnectionMemoManager;
import jmri.jmrix.dccpp.DCCppCommandStation;
import jmri.jmrix.dccpp.DCCppPacketizer;
import jmri.jmrix.dccpp.DCCppSystemConnectionMemo;
import jmri.jmrix.dccpp.DCCppTrafficController;
import jmri.jmrix.dccpp.network.DCCppEthernetAdapter;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * CommandStationConnection for DCC++, using the Ethernet adapter variant as
 * an example. Other variants can be added by inspecting {@link SystemConfig}
 * options (e.g. serial vs simulator).
 */
public final class DccppConnection extends BaseCommandStationConnection {

    private final DCCppSystemConnectionMemo memo;
    private final DCCppTrafficController trafficController;
    private final DCCppCommandStation commandStation;

    private JmriAccessoryController accessoryController;
    private JmriProgrammerSession programmerSession;

    private final PropertyChangeListener powerListener = this::onPowerChange;

    public DccppConnection(SystemConfig config, DccEventBus eventBus) {
        super(config, eventBus);
        this.commandStation = new DCCppCommandStation(null);
        this.trafficController = new DCCppPacketizer(commandStation);
        this.memo = new DCCppSystemConnectionMemo(trafficController);
        memo.setUserName(config.getUserName());
        memo.setSystemPrefix(config.getSystemPrefix());
        SystemConnectionMemoManager.getDefault().register(memo);
    }

    @Override
    public void connect() throws IOException {
        if (connected) {
            return;
        }
        String host = config.getOption("host");
        String port = config.getOption("port");
        if (host == null || port == null) {
            throw new IOException("Missing 'host' or 'port' option for DCC++ connection");
        }
        DCCppEthernetAdapter adapter = new DCCppEthernetAdapter();
        adapter.setSystemConnectionMemo(memo);
        adapter.setHostName(host);
        adapter.setPort(Integer.parseInt(port));
        trafficController.connectPort(adapter);
        adapter.connect();
        adapter.configure();

        connected = true;
        attachManagersAndListeners();
        publishConnectionState();
    }

    private void attachManagersAndListeners() {
        TurnoutManager turnoutManager = memo.getTurnoutManager();
        if (turnoutManager != null) {
            accessoryController = new JmriAccessoryController(id, turnoutManager);
        }
        GlobalProgrammerManager gpm = memo.getProgrammerManager();
        if (gpm == null) {
            gpm = InstanceManager.getNullableDefault(GlobalProgrammerManager.class);
        }
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
            throw new IOException("No ThrottleManager available on DCC++ connection");
        }
        final DccThrottle[] holder = new DccThrottle[1];
        final IOException[] error = new IOException[1];
        ThrottleListener listener = new ThrottleListener() {
            @Override
            public void notifyThrottleFound(DccThrottle t) {
                holder[0] = t;
            }

            @Override
            public void notifyFailedThrottleRequest(jmri.LocoAddress address, String reason) {
                error[0] = new IOException("Throttle request failed: " + reason);
            }

            @Override
            public void notifyDecisionRequired(jmri.LocoAddress address, ThrottleListener.DecisionType question) {
                // Default behavior: cancel the request if a decision is required
                error[0] = new IOException("Throttle address " + address + " is in use, decision required: " + question);
            }
        };
        tm.requestThrottle(address, longAddress, listener, false);
        if (error[0] != null) {
            throw error[0];
        }
        if (holder[0] == null) {
            throw new IOException("Throttle not granted for address " + address);
        }
        return new JmriThrottleSession(id, address, longAddress, holder[0], eventBus);
    }

    @Override
    public ProgrammerSession getProgrammer() {
        return programmerSession;
    }

    @Override
    public AccessoryController getAccessoryController() {
        return accessoryController;
    }

    @Override
    public java.util.Map<String, String> getCommandStationInfo() {
        Map<String, String> info = new HashMap<>();
        if (commandStation != null) {
            String stationType = commandStation.getStationType();
            String version = commandStation.getVersion();
            String build = commandStation.getBuild();
            String versionString = commandStation.getVersionString();
            
            if (stationType != null && !stationType.equals("Unknown")) {
                info.put("type", stationType);
            }
            if (version != null && !version.equals("0.0.0")) {
                info.put("version", version);
            }
            if (build != null && !build.equals("Unknown")) {
                info.put("build", build);
            }
            if (versionString != null && !versionString.isEmpty()) {
                info.put("versionString", versionString);
            }
            info.put("manufacturer", "DCC++");
        }
        return info.isEmpty() ? null : info;
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
        
        // Terminate traffic controller threads before closing
        if (trafficController != null) {
            try {
                trafficController.terminateThreads();
            } catch (Exception e) {
                // Ignore errors during shutdown
            }
        }
        
        // Dispose memo (which should handle adapter cleanup)
        if (memo != null) {
            memo.dispose();
        }
    }
}


