package cc.panelsd.connect.core.impl.common;

import cc.panelsd.connect.core.ThrottleSession;
import cc.panelsd.connect.core.events.DccEvent;
import cc.panelsd.connect.core.events.DccEventBus;
import cc.panelsd.connect.core.events.DccEventType;

import jmri.DccThrottle;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Wraps a JMRI {@link DccThrottle} as a {@link ThrottleSession}.
 *
 * This wrapper automatically listens to throttle property changes and publishes
 * them as high-level events (e.g., "Train 3: Speed = 100%").
 */
public class JmriThrottleSession implements ThrottleSession {

    private final String connectionId;
    private final int address;
    private final boolean longAddress;
    private final DccThrottle delegate;
    private final DccEventBus eventBus;
    private final PropertyChangeListener throttleListener;

    public JmriThrottleSession(String connectionId, int address, boolean longAddress, DccThrottle delegate, DccEventBus eventBus) {
        this.connectionId = connectionId;
        this.address = address;
        this.longAddress = longAddress;
        this.delegate = delegate;
        this.eventBus = eventBus;
        
        // Add property change listener to capture throttle state changes
        this.throttleListener = this::onThrottlePropertyChange;
        delegate.addPropertyChangeListener(throttleListener);
    }
    
    private void onThrottlePropertyChange(PropertyChangeEvent evt) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("address", address);
        payload.put("longAddress", longAddress);
        String propertyName = evt.getPropertyName();
        payload.put("property", propertyName);
        payload.put("oldValue", evt.getOldValue());
        payload.put("newValue", evt.getNewValue());
        
        // Create human-readable description
        String description = formatThrottleEvent(propertyName, evt.getOldValue(), evt.getNewValue(), address, longAddress);
        payload.put("description", description);
        
        // Always publish throttle events - this will show up in console
        eventBus.publish(new DccEvent(DccEventType.THROTTLE_UPDATED, connectionId, payload));
    }
    
    private String formatThrottleEvent(String propertyName, Object oldValue, Object newValue, int address, boolean longAddress) {
        if (propertyName.equals(jmri.Throttle.SPEEDSETTING)) {
            float speed = ((Number) newValue).floatValue();
            return String.format("Train %d: Speed = %.1f%%", address, speed * 100);
        } else if (propertyName.equals(jmri.Throttle.ISFORWARD)) {
            boolean forward = (Boolean) newValue;
            return String.format("Train %d: Direction = %s", address, forward ? "Forward" : "Reverse");
        } else if (propertyName.startsWith("F") && propertyName.endsWith("Momentary")) {
            // Momentary function (F0Momentary, F1Momentary, etc.)
            String funcPart = propertyName.substring(1, propertyName.length() - 9);
            try {
                int funcNum = Integer.parseInt(funcPart);
                boolean momentary = (Boolean) newValue;
                return String.format("Train %d: Function F%d Momentary = %s", address, funcNum, momentary ? "ON" : "OFF");
            } catch (NumberFormatException e) {
                // Not a valid function number
            }
        } else if (propertyName.startsWith("F")) {
            // Function change (F0, F1, F2, F13, F28, etc.) - check if rest is all digits
            String funcPart = propertyName.substring(1);
            if (funcPart.matches("\\d+")) {
                try {
                    int funcNum = Integer.parseInt(funcPart);
                    boolean on = (Boolean) newValue;
                    return String.format("Train %d: Function F%d = %s", address, funcNum, on ? "ON" : "OFF");
                } catch (NumberFormatException e) {
                    // Not a valid function number
                }
            }
        }
        return String.format("Train %d: %s = %s", address, propertyName, newValue);
    }

    @Override
    public String getConnectionId() {
        return connectionId;
    }

    @Override
    public int getAddress() {
        return address;
    }

    @Override
    public boolean isLongAddress() {
        return longAddress;
    }

    @Override
    public void setSpeed(float speed) throws IOException {
        // For XpressNet/Elite, the throttle may need to be "activated" before commands are accepted.
        // If this is the first command and the throttle hasn't been activated by the physical controller,
        // we may need to send an explicit activation. However, we'll try the normal command first.
        // The delegate should handle XpressNet-specific activation if needed.
        delegate.setSpeedSetting(speed);
    }

    @Override
    public void setDirection(boolean forward) throws IOException {
        delegate.setIsForward(forward);
    }

    @Override
    public void setFunction(int functionNumber, boolean on) throws IOException {
        delegate.setFunction(functionNumber, on);
    }

    @Override
    public float getSpeed() {
        return delegate.getSpeedSetting();
    }

    @Override
    public boolean getDirection() {
        return delegate.getIsForward();
    }

    @Override
    public boolean getFunction(int functionNumber) {
        return delegate.getFunction(functionNumber);
    }

    @Override
    public void close() {
        // Remove property change listener before releasing
        delegate.removePropertyChangeListener(throttleListener);
        delegate.release(null); // return to the pool (null is acceptable per JMRI API)
    }
}


