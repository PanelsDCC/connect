package cc.panelsd.connect.core.events;

/**
 * Listener interface for components (e.g. HTTP/WebSocket layer) that want to
 * observe events coming from the underlying command stations.
 */
public interface DccEventListener {

    void onEvent(DccEvent event);
}


