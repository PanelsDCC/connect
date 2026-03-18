package cc.panelsd.connect.core;

import java.io.Closeable;
import java.io.IOException;

/**
 * A logical connection to a single command station / system family.
 * <p>
 * This interface is intentionally system-agnostic; concrete implementations
 * may wrap JMRI jmrix classes such as:
 * <ul>
 *   <li>{@code jmri.jmrix.lenz.XNetSystemConnectionMemo} +
 *       {@code jmri.jmrix.lenz.XNetTrafficController}</li>
 *   <li>{@code jmri.jmrix.dccpp.DCCppSystemConnectionMemo} +
 *       {@code jmri.jmrix.dccpp.DCCppTrafficController}</li>
 * </ul>
 */
public interface CommandStationConnection extends Closeable {

    /**
     * Stable identifier for this connection instance, unique within a single
     * {@link DccIoService}.
     */
    String getId();

    /**
     * Logical system type identifier, e.g. {@code "xnet-elite"} or
     * {@code "dccpp-serial"}.
     */
    String getSystemType();

    /**
     * Open and initialize the underlying transport and protocol stack.
     */
    void connect() throws IOException;

    /**
     * @return true if the underlying transport and protocol stack are ready.
     */
    boolean isConnected();

    /**
     * Obtain a throttle session for a locomotive.
     *
     * @param address     DCC address
     * @param longAddress true if this is a long address
     */
    ThrottleSession openThrottle(int address, boolean longAddress) throws IOException;

    /**
     * Access a programmer session bound to this connection.
     */
    ProgrammerSession getProgrammer();

    /**
     * Access accessory (turnout, route, etc.) control bound to this connection.
     */
    AccessoryController getAccessoryController();

    /**
     * Get command station information (version, model, manufacturer, etc.).
     * This may return null if the information is not yet available or not supported.
     *
     * @return Map containing command station info (keys: "version", "model", "manufacturer", "type", etc.)
     */
    java.util.Map<String, String> getCommandStationInfo();

    /**
     * Get current power status.
     *
     * @return Power status: "ON", "OFF", "IDLE", or "UNKNOWN"
     */
    String getPowerStatus();

    /**
     * Set track power state.
     *
     * @param powerState Power state: "ON", "OFF", or "IDLE"
     * @throws IOException if the power state cannot be set
     * @throws IllegalArgumentException if the power state is invalid
     */
    void setPower(String powerState) throws IOException;

    /**
     * Request command station version information from the command station.
     * This is an asynchronous operation - the version info will be updated
     * when the command station responds.
     *
     * @throws IOException if the request cannot be sent
     */
    void requestVersion() throws IOException;

    /**
     * Close and dispose of the underlying resources.
     */
    @Override
    void close();
}


