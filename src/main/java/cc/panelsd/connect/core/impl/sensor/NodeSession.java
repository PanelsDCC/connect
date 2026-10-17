package cc.panelsd.connect.core.impl.sensor;

/**
 * Common face for USB, Bluetooth, and shim node board sessions.
 */
public interface NodeSession extends AutoCloseable {

    String getId();

    String getPrefix();

    String getBoardType();

    String getTransport();

    String getEndpoint();

    boolean isRunning();

    /** Send one ASCII command line (CFG / OUT / TEST). */
    void sendCommand(String line);

    /**
     * Send a command and wait for a reply line starting with {@code replyPrefix}
     * (e.g. {@code SAMPLE}). Default: fire-and-forget, returns null.
     */
    default String sendCommandAwaitReply(String line, String replyPrefix, long timeoutMs) {
        sendCommand(line);
        return null;
    }

    @Override
    void close();
}
