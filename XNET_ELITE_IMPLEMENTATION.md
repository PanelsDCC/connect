# XpressNet Elite Implementation

## Overview

This document explains the special implementation of XpressNet throttle and accessory control for the Hornby Elite command station and why we bypass JMRI's standard throttle/turnout abstractions for runtime commands.

**General pattern:** For how we interface with JMRI and how to add other controllers, see [docs/JMRI-INTEGRATION.md](docs/JMRI-INTEGRATION.md). This file focuses on Elite-specific behaviour and history.

## The Problem

When using JMRI's standard throttle abstraction with the Hornby Elite, locomotives would not respond to speed/direction commands until the throttle was first "activated" by using the physical Elite controller. The symptoms were:

- Software could send throttle commands and receive "OK" responses
- The web interface would show the train as moving
- **But the actual locomotive would not move** until the physical controller was used first
- Once activated via the physical controller, software control would then work

## Root Cause Analysis

### JMRI's Standard Approach

JMRI's `XNetThrottle` implementation follows this sequence when creating a throttle:

1. **Throttle Creation** - Creates a `XNetThrottle` object
2. **Status Query** - Immediately sends a `0xE3` (LOCO_STATUS_REQ) message to query the command station for current locomotive state
3. **Wait for Response** - Waits for status information before considering the throttle "active"
4. **Speed Commands** - Only after status is received, speed/direction commands (`0xE4 0x13`) are sent

The problem is that the Hornby Elite requires a throttle to be **activated** by sending an actual speed/direction command (`0xE4 0x13`), not just a status query (`0xE3`). The status query doesn't activate the throttle session on the Elite.

### Why Physical Controller Works

When you use the physical Elite controller:
1. The controller sends `0xE4 0x13` (speed/direction command) directly
2. This activates the throttle on the Elite
3. The Elite broadcasts status updates (`0xE5 0xF8` for speed/direction changes)
4. JMRI receives these broadcasts and updates its state
5. Once activated, JMRI can then take control

### Python Implementation Reference

Our Python XpressNet library (which works reliably) sends throttle commands directly without querying status first:

```python
def throttle(self, speed, direction):
    message = bytearray(b'\xE4\x00\x00\x00\x00')
    message[1] = 0x13  # LOCO_SPEED_128
    # ... encode address and speed/direction ...
    send(message)  # Direct send, no query first
```

## Our Solution

We use the **jmrix-only pattern** (see [docs/JMRI-INTEGRATION.md](docs/JMRI-INTEGRATION.md)): build messages with JMRI's jmrix message classes and send via the traffic controller; do not use the throttle/turnout bean layer for runtime commands.

### DirectXNetThrottleSession

The `DirectXNetThrottleSession` class:

1. **Speed/Direction** - Uses `XNetMessage.getSpeedAndDirectionMsg(address, SpeedStepMode.NMRA_DCC_128, speed, forward)` and `XNetTrafficController.sendXNetMessage(msg, null)`. No hand-built bytes; JMRI's jmrix layer encodes the protocol.
2. **Uses JMRI for Functions** - Still uses JMRI's `DccThrottle` for function control (which works fine).
3. **Immediate Activation** - Sends an initial speed 0 command when the throttle is created, activating it on the Elite.
4. **No Event Publishing** - Doesn't publish `THROTTLE_UPDATED` events to avoid infinite loops (events are meant for external changes, not our own commands).

**File:** `src/main/java/org/dccio/core/impl/xnet/elite/DirectXNetThrottleSession.java`

**Key Methods:**
- `setSpeed(float speed)` - Calls `XNetMessage.getSpeedAndDirectionMsg(...)`, sends via traffic controller.
- `setDirection(boolean forward)` - Same message builder with updated direction.
- `setFunction(int, boolean)` - Uses JMRI throttle for functions.
- `close()` - Releases JMRI throttle (used for functions).

### DirectXNetAccessoryController

Accessories had the same kind of flakiness when going through JMRI's TurnoutManager/Turnout. We use:

- `XNetMessage.getTurnoutCommandMsg(pNumber, closed, thrown, true)` for the wire format (with Elite off-by-one: `pNumber = address + 1`).
- `XNetTrafficController.sendXNetMessage(msg, null)` to send.

**File:** `src/main/java/org/dccio/core/impl/xnet/elite/DirectXNetAccessoryController.java`

### Integration Point

**File:** `src/main/java/org/dccio/core/impl/xnet/elite/XNetEliteConnection.java`

- **Throttles:** `openThrottle()` acquires a JMRI throttle (for functions), creates a `DirectXNetThrottleSession` with the traffic controller and that throttle, sends an initial speed 0 command, and returns the direct session.
- **Accessories:** `getAccessoryController()` returns a `DirectXNetAccessoryController` (created in `attachManagersAndListeners`) that uses the same traffic controller and `XNetMessage.getTurnoutCommandMsg`. The JMRI `JmriAccessoryController` is only used as fallback if the direct controller is not available.

## Benefits

1. **Immediate Control** - Trains respond immediately without requiring physical controller activation
2. **Reliable Operation** - Matches the proven Python implementation that works consistently
3. **Function Support** - Still uses JMRI for functions, which work correctly
4. **Reconnection Support** - Works correctly when the controller is unplugged and reconnected

## Trade-offs

1. **Bypasses JMRI Abstraction** - We lose some JMRI features like automatic state synchronization from status queries
2. **No Property Change Events** - We don't publish throttle update events (to avoid infinite loops)
3. **Manual State Tracking** - We track speed/direction state internally rather than relying on JMRI

## Why Not Fix JMRI?

While we could potentially contribute a fix to JMRI, this would require:
1. Understanding JMRI's throttle architecture deeply
2. Ensuring compatibility with all XpressNet command stations (not just Elite)
3. Extensive testing across different systems
4. JMRI project acceptance and review process

Our direct implementation is simpler, works immediately, and matches the proven Python approach.

## References

- JMRI XpressNet Implementation: `JMRI-5.13.6/java/src/jmri/jmrix/lenz/XNetThrottle.java`
- Python XpressNet Library: `https://github.com/davetaz/elite-xpressnet2/blob/main/usr/lib/xpressnet-control/xpressNet.py`
- XpressNet Protocol Documentation: Lenz XpressNet Protocol Specification

## Future Considerations

If JMRI's throttle/turnout abstractions were updated to handle Elite reliably, we could switch back to the bean layer. The current approach (jmrix message builders + traffic controller only) is documented as the standard pattern in [docs/JMRI-INTEGRATION.md](docs/JMRI-INTEGRATION.md) and should be used when adding other controllers.
