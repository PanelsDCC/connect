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
import jmri.jmrix.dccpp.DCCppInterface;
import jmri.jmrix.dccpp.DCCppListener;
import jmri.jmrix.dccpp.DCCppMessage;
import jmri.jmrix.dccpp.DCCppReply;
import jmri.jmrix.dccpp.DCCppSystemConnectionMemo;
import jmri.jmrix.dccpp.DCCppTrafficController;
import jmri.jmrix.dccpp.network.DCCppEthernetAdapter;
import jmri.jmrix.dccpp.serial.DCCppAdapter;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Command station connection for DCC++ / DCC-EX: one implementation, two transports.
 * <ul>
 *   <li><strong>USB / serial:</strong> {@code portName} (e.g. {@code /dev/ttyACM0}) via JMRI
 *       {@link DCCppAdapter}.</li>
 *   <li><strong>Ethernet:</strong> {@code host} and {@code port} (TCP) via {@link DCCppEthernetAdapter}.</li>
 * </ul>
 * Auto-discovery supplies {@code portName} and {@link SystemConfig#getSystemType()} {@code dccpp}.
 */
public final class DccppConnection extends BaseCommandStationConnection {

    private final DCCppSystemConnectionMemo memo;

    private DCCppEthernetAdapter ethernetAdapter;
    private DCCppAdapter serialAdapter;

    private JmriAccessoryController accessoryController;
    private JmriProgrammerSession programmerSession;

    private final PropertyChangeListener powerListener = this::onPowerChange;

    /**
     * DCC-EX often uses named track power replies ({@code <p ...>}) that JMRI's {@code DCCppPowerManager}
     * does not map into {@link PowerManager#getPower()}; we mirror MAIN (or first) district here.
     */
    private final AtomicInteger supplementalTrackPower = new AtomicInteger(PowerManager.UNKNOWN);

    private DCCppListener dccppReplyListener;

    public DccppConnection(SystemConfig config, DccEventBus eventBus) {
        super(config, eventBus);
        this.memo = new DCCppSystemConnectionMemo();
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
        boolean hasPort = portName != null && !portName.isBlank();
        String host = config.getOption("host");
        String portOpt = config.getOption("port");

        if (hasPort) {
            connectSerial(portName.trim());
        } else if (host != null && !host.isBlank() && portOpt != null && !portOpt.isBlank()) {
            connectEthernet(host.trim(), portOpt.trim());
        } else {
            throw new IOException(
                    "DCC++ connection needs either portName (USB/serial) or host and port (Ethernet)");
        }

        connected = true;
        attachManagersAndListeners();
        try {
            requestVersion();
        } catch (IOException e) {
            // same pattern as XNetElite — connection stays up
        }
        publishConnectionState();
    }

    private void connectSerial(String portName) throws IOException {
        serialAdapter = new DCCppAdapter();
        serialAdapter.setSystemConnectionMemo(memo);
        // JMRI normally sets this from Connection Config UI; without it, mBaudRate stays null and
        // openPort() logs "no match to (null) in currentBaudNumber" and never applies a valid baud.
        String baudOpt = config.getOption("baudRate");
        if (baudOpt != null && !baudOpt.isBlank()) {
            serialAdapter.configureBaudRateFromNumber(baudOpt.trim());
        } else {
            serialAdapter.configureBaudRateFromIndex(serialAdapter.defaultBaudIndex());
        }
        String err = serialAdapter.openPort(portName, "dcc-io-daemon");
        if (err != null) {
            serialAdapter = null;
            throw new IOException("Failed to open DCC++ serial port: " + err);
        }
        serialAdapter.configure();
    }

    private void connectEthernet(String host, String portStr) throws IOException {
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid TCP port for DCC++ Ethernet: " + portStr);
        }
        ethernetAdapter = new DCCppEthernetAdapter();
        ethernetAdapter.setSystemConnectionMemo(memo);
        ethernetAdapter.setHostName(host);
        ethernetAdapter.setPort(port);
        ethernetAdapter.connect();
        ethernetAdapter.configure();
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

        DCCppTrafficController tc = memo.getDCCppTrafficController();
        if (tc != null) {
            dccppReplyListener = new DCCppListener() {
                @Override
                public void message(DCCppReply m) {
                    onDccppReply(m);
                }

                @Override
                public void message(DCCppMessage m) {
                }

                @Override
                public void notifyTimeout(DCCppMessage m) {
                }
            };
            tc.addDCCppListener(DCCppInterface.CS_INFO, dccppReplyListener);
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
    public Map<String, String> getCommandStationInfo() {
        DCCppTrafficController tc = memo.getDCCppTrafficController();
        DCCppCommandStation cs = tc != null ? tc.getCommandStation() : null;
        Map<String, String> info = new HashMap<>();
        if (cs != null) {
            String stationType = cs.getStationType();
            String version = cs.getVersion();
            String build = cs.getBuild();
            String versionString = cs.getVersionString();

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

    private void onDccppReply(DCCppReply m) {
        if (m.isNamedPowerReply()) {
            String district = m.getPowerDistrictName();
            String st = m.getPowerDistrictStatus();
            int v = "ON".equals(st) ? PowerManager.ON : "OFF".equals(st) ? PowerManager.OFF : PowerManager.UNKNOWN;
            if (v == PowerManager.UNKNOWN) {
                return;
            }
            boolean mainDistrict = "MAIN".equalsIgnoreCase(district);
            if (!mainDistrict && supplementalTrackPower.get() != PowerManager.UNKNOWN) {
                // After we know a district, only MAIN overrides (typical DCC-EX layout)
                return;
            }
            int prev = supplementalTrackPower.getAndSet(v);
            if (prev != v) {
                Map<String, Object> payload = new HashMap<>();
                payload.put("old", prev);
                payload.put("new", v);
                payload.put("status", powerIntToLabel(v));
                payload.put("track", district);
                eventBus.publish(new DccEvent(DccEventType.POWER_CHANGED, id, payload));
            }
            return;
        }
        if (m.isStatusReply()) {
            // JMRI's PowerManager already ran setCommandStationInfo; push a WS/status refresh
            eventBus.publish(new DccEvent(DccEventType.CONNECTION_STATE_CHANGED, id, new HashMap<>()));
        }
    }

    private static String powerIntToLabel(int power) {
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

    @Override
    public void requestVersion() throws IOException {
        if (!connected) {
            throw new IOException("Not connected");
        }
        DCCppTrafficController tc = memo.getDCCppTrafficController();
        if (tc == null) {
            throw new IOException("DCC++ traffic controller not available");
        }
        tc.sendDCCppMessage(DCCppMessage.makeCSStatusMsg(), null);
    }

    @Override
    public String getPowerStatus() {
        PowerManager pm = memo.getPowerManager();
        if (pm != null) {
            int power = pm.getPower();
            if (power != PowerManager.UNKNOWN) {
                return powerIntToLabel(power);
            }
        }
        int s = supplementalTrackPower.get();
        if (s != PowerManager.UNKNOWN) {
            return powerIntToLabel(s);
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
            Object newVal = evt.getNewValue();
            if (newVal instanceof Integer) {
                payload.put("status", powerIntToLabel((Integer) newVal));
            }
            eventBus.publish(new DccEvent(DccEventType.POWER_CHANGED, id, payload));
        }
    }

    @Override
    public void close() {
        connected = false;
        publishConnectionState();

        PowerManager pm = memo.getPowerManager();
        if (pm != null) {
            pm.removePropertyChangeListener(powerListener);
        }

        DCCppTrafficController tc = memo.getDCCppTrafficController();
        if (tc != null && dccppReplyListener != null) {
            try {
                tc.removeDCCppListener(DCCppInterface.CS_INFO, dccppReplyListener);
            } catch (Exception e) {
                // ignore
            }
            dccppReplyListener = null;
        }
        supplementalTrackPower.set(PowerManager.UNKNOWN);

        if (tc != null) {
            try {
                tc.terminateThreads();
            } catch (Exception e) {
                // ignore shutdown errors
            }
        }

        if (serialAdapter != null) {
            try {
                serialAdapter.dispose();
            } catch (Exception e) {
                // ignore
            }
            serialAdapter = null;
        } else if (ethernetAdapter != null) {
            try {
                ethernetAdapter.dispose();
            } catch (Exception e) {
                // ignore
            }
            ethernetAdapter = null;
        } else if (memo != null) {
            // connect() never completed; memo was not disposed by an adapter
            memo.dispose();
        }
    }
}
