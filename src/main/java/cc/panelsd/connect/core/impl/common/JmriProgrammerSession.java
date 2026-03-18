package cc.panelsd.connect.core.impl.common;

import cc.panelsd.connect.core.ProgrammerSession;

import jmri.GlobalProgrammerManager;
import jmri.ProgListener;
import jmri.Programmer;
import jmri.ProgrammerException;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Simple synchronous wrapper over a JMRI {@link Programmer}. This is not
 * optimized for throughput, but keeps the public abstraction simple; the
 * daemon can later expose async operations if needed.
 */
public class JmriProgrammerSession implements ProgrammerSession {

    private final String connectionId;
    private final Programmer programmer;

    public JmriProgrammerSession(String connectionId, GlobalProgrammerManager gpm) {
        this.connectionId = connectionId;
        this.programmer = gpm != null ? gpm.getGlobalProgrammer() : null;
    }

    @Override
    public String getConnectionId() {
        return connectionId;
    }

    @Override
    public int readCv(int cv) throws IOException {
        ensureProgrammer();
        CompletableFuture<Integer> future = new CompletableFuture<>();
        ProgListener listener = (value, status) -> {
            if (status == ProgListener.OK) {
                future.complete(value);
            } else {
                future.completeExceptionally(
                        new IOException("Read CV failed, status=" + status));
            }
        };
        try {
            programmer.readCV(Integer.toString(cv), listener);
            try {
                return future.get(90, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IOException("Read CV timeout", e);
            }
        } catch (ProgrammerException e) {
            throw new IOException("Programmer error", e);
        }
    }

    @Override
    public void writeCv(int cv, int value) throws IOException {
        ensureProgrammer();
        CompletableFuture<Void> future = new CompletableFuture<>();
        ProgListener listener = (v, status) -> {
            if (status == ProgListener.OK) {
                future.complete(null);
            } else {
                future.completeExceptionally(
                        new IOException("Write CV failed, status=" + status));
            }
        };
        try {
            programmer.writeCV(Integer.toString(cv), value, listener);
            try {
                future.get(90, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IOException("Write CV timeout", e);
            }
        } catch (ProgrammerException e) {
            throw new IOException("Programmer error", e);
        }
    }

    private void ensureProgrammer() throws IOException {
        if (programmer == null) {
            throw new IOException("No programmer available on this connection");
        }
    }

    @Override
    public void close() {
        // nothing to dispose; programmer is owned by JMRI
    }
}


