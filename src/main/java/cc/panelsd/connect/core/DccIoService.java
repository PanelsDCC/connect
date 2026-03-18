package cc.panelsd.connect.core;

import java.io.Closeable;
import java.util.Collection;

/**
 * Top-level facade for the DCC IO service.
 * <p>
 * A single {@link DccIoService} instance manages multiple
 * {@link CommandStationConnection}s, which in turn provide throttle,
 * programming and accessory operations.
 */
public interface DccIoService extends Closeable {

    /**
     * Create and register a new logical connection.
     */
    CommandStationConnection createConnection(SystemConfig config);

    /**
     * Obtain an existing connection by id.
     */
    CommandStationConnection getConnection(String id);

    /**
     * All active connections.
     */
    Collection<CommandStationConnection> getConnections();

    /**
     * Remove and dispose of a connection by id.
     */
    void removeConnection(String id);

    @Override
    void close();
}


