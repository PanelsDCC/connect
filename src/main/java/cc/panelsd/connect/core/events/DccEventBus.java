package cc.panelsd.connect.core.events;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Very small in-process event bus for DCC events. The daemon's transport
 * layer can subscribe here and forward events to remote clients.
 */
public final class DccEventBus {

    private final List<DccEventListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(DccEventListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(DccEventListener listener) {
        listeners.remove(listener);
    }

    public void publish(DccEvent event) {
        for (DccEventListener listener : listeners) {
            listener.onEvent(event);
        }
    }
}


