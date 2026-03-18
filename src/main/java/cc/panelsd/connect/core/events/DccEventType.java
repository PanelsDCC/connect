package cc.panelsd.connect.core.events;

/**
 * High-level event types the daemon can emit outward. These correspond to
 * state changes reported by the underlying command station via JMRI.
 */
public enum DccEventType {
    POWER_CHANGED,
    EMERGENCY_STOP,
    THROTTLE_UPDATED,
    TURNOUT_UPDATED,
    CONNECTION_STATE_CHANGED,
    COMMUNICATION_ERROR,
    MESSAGE_RECEIVED,
    MESSAGE_SENT
}


