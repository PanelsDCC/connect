package cc.panelsd.connect.core;

import java.io.Closeable;
import java.io.IOException;

/**
 * Programming interface bound to a single logical command station connection.
 * <p>
 * This is intended to wrap JMRI programmer classes such as
 * {@code jmri.jmrix.lenz.XNetProgrammer} or
 * {@code jmri.jmrix.dccpp.DCCppProgrammer}.
 */
public interface ProgrammerSession extends Closeable {

    /**
     * @return the owning logical connection id.
     */
    String getConnectionId();

    /**
     * Read a configuration variable.
     */
    int readCv(int cv) throws IOException;

    /**
     * Write a configuration variable.
     */
    void writeCv(int cv, int value) throws IOException;

    @Override
    void close();
}


