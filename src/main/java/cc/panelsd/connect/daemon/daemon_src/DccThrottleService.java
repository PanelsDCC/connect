package cc.panelsd.connect.daemon;

import cc.panelsd.connect.core.ThrottleSession;
import cc.panelsd.connect.core.impl.DccIoServiceImpl;

import java.io.IOException;
import java.util.Collection;

/**
 * Adapter exposing throttle operations from DccIoServiceImpl to JSON handlers.
 */
public class DccThrottleService implements JsonThrottleHandler.ThrottleService {

    private final DccIoServiceImpl service;

    public DccThrottleService(DccIoServiceImpl service) {
        this.service = service;
    }

    @Override
    public String openThrottle(String connectionId, int address, boolean longAddress) throws IOException {
        return service.openThrottle(connectionId, address, longAddress);
    }

    @Override
    public ThrottleSession getThrottle(String throttleId) {
        return service.getThrottle(throttleId);
    }

    @Override
    public Collection<ThrottleSession> getThrottles() {
        return service.getThrottles();
    }

    @Override
    public void closeThrottle(String throttleId) {
        service.closeThrottle(throttleId);
    }
}

