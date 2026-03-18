# JMRI Integration Pattern

This document describes how dcc-io-daemon interfaces with JMRI when supporting a command station, and the pattern to follow when adding new controllers.

## Summary

- **Use JMRI’s jmrix layer** for connection setup, port handling, and **message building**.
- **Send commands** via the protocol-specific **traffic controller** (e.g. `XNetTrafficController.sendXNetMessage(...)`).
- **Bypass JMRI’s bean layer** (TurnoutManager/Turnout, ThrottleManager/DccThrottle for runtime commands) where it is unreliable or unnecessary; keep it only where it helps (e.g. programmer, power, functions).

We do **not** hand‑encode protocol bytes when JMRI already provides message factories in jmrix. We **do** avoid depending on the higher‑level managers (turnouts, throttles) for time‑sensitive or flaky operations.

---

## JMRI layers (relevant to us)

| Layer | Package / location | What we use it for |
|-------|--------------------|---------------------|
| **jmrix** | `jmri.jmrix.<protocol>` (e.g. `jmri.jmrix.lenz`) | Port opening, memo, **traffic controller**, **message classes** that build wire-format bytes. |
| **Beans / managers** | `jmri.Turnout`, `jmri.Throttle`, `TurnoutManager`, `ThrottleManager`, etc. | Optional: e.g. programmer, power, sometimes functions. We **bypass** for throttle speed/direction and accessory ops on systems where the bean layer is flaky. |

The **traffic controller** is the object that actually sends and receives bytes for a given protocol (e.g. `XNetTrafficController`). The **message class** (e.g. `XNetMessage`) has static factory methods that build the correct packet (opcodes, address encoding, checksum). The bean layer (turnouts, throttles) calls those same factories and then adds state machines, feedback, and UI; we skip that and call the factories ourselves.

---

## Pattern for a new controller

When adding support for a new command station (e.g. another jmrix system):

### 1. Reuse JMRI for connection and transport

- Use the existing **adapter/memo** for that system to open the port and create the **traffic controller** (or equivalent “send message” entry point).
- Our `CommandStationConnection` implementation (e.g. `XNetEliteConnection`) holds the memo and traffic controller and exposes them to our own “direct” classes.

### 2. Locate the message builders in jmrix

- In `jmri/jmrix/<protocol>/` look for the **Message** class (e.g. `XNetMessage`, `DccppMessage`).
- Find static methods that build the packets you need, e.g.:
  - Throttle speed/direction: `getSpeedAndDirectionMsg(...)` or equivalent.
  - Accessory/turnout: `getTurnoutCommandMsg(...)` or equivalent.
- Use those methods; do not hand‑build bytes unless JMRI has no equivalent.

### 3. Implement our interfaces with “direct” classes

- **Throttle:** Implement `ThrottleSession` with a class that:
  - Holds a reference to the **traffic controller** (and optionally a JMRI throttle for functions).
  - For **speed/direction**: call the jmrix message factory, then `trafficController.sendXNetMessage(msg, null)` (or the equivalent for that protocol).
  - For **functions**: either use the same message factories if they exist, or keep using the JMRI throttle if that works.
- **Accessories:** Implement `AccessoryController` with a class that:
  - Holds the **traffic controller**.
  - For each `setTurnout(address, closed)`: call the jmrix turnout/accessory message factory (e.g. `XNetMessage.getTurnoutCommandMsg(...)`), then send via the traffic controller.

### 4. Wire into the connection

- In your `*Connection` class (e.g. `XNetEliteConnection`):
  - In `openThrottle(...)`: build the JMRI throttle if needed for functions, then construct **your** `DirectXxxThrottleSession(connectionId, address, longAddress, trafficController, eventBus, jmriThrottle)` and return it.
  - In `getAccessoryController()`: return an instance of **your** `DirectXxxAccessoryController(connectionId, trafficController)` (and create it when the connection is set up, same as for Elite).

### 5. Keep using JMRI where it helps

- **Programmer / CVs:** Keep using JMRI’s programmer (memo’s programmer or global programmer) if the system supports it.
- **Power:** Keep using the memo’s `PowerManager` for track power if available.
- **Functions:** Use JMRI throttle for F0–F28 (or the protocol’s equivalent) if that path is reliable; otherwise use jmrix function message builders if they exist.

---

## Example: XpressNet / Hornby Elite

- **Connection:** `XNetEliteConnection` creates `EliteXNetSystemConnectionMemo`, `EliteAdapter`, and `XNetTrafficController`; opens the port via the adapter.
- **Accessories:** `DirectXNetAccessoryController` uses `XNetMessage.getTurnoutCommandMsg(pNumber, closed, thrown, true)` and `trafficController.sendXNetMessage(msg, null)`. No `TurnoutManager` or `Turnout`.
- **Throttle speed/direction:** `DirectXNetThrottleSession` uses `XNetMessage.getSpeedAndDirectionMsg(address, SpeedStepMode.NMRA_DCC_128, speed, forward)` and `trafficController.sendXNetMessage(msg, null)`. No speed/direction through JMRI throttle.
- **Throttle functions:** Still use JMRI’s `DccThrottle` (acquired in `openThrottle`) for F0–F28.
- **Elite quirk:** Accessory turnout number is passed as `address + 1` into `getTurnoutCommandMsg` to match Elite’s off‑by‑one (see `EliteXNetTurnout`).

See [../XNET_ELITE_IMPLEMENTATION.md](../XNET_ELITE_IMPLEMENTATION.md) for the original problem and Elite‑specific details.

---

## Example: NCE PowerCab (Serial / USB)

- **Connections:** `NceSerialConnection` and `NceUsbConnection` use `SerialDriverAdapter` / `UsbDriverAdapter`, `NceSystemConnectionMemo`, and `NceTrafficController`.
- **Throttle speed/direction:** `DirectNceThrottleSession` uses `NceBinaryCommand.nceLocoCmd(locoAddr, LOCO_CMD_FWD_128SPEED/REV_128SPEED, value)` and `NceMessage.createBinaryMessage(tc, bl)`, then `trafficController.sendNceMessage(m, null)`. Loco address: DCC number + `0xC000` for long. Functions use the JMRI throttle.
- **Accessories:** `DirectNceAccessoryController` uses `NceBinaryCommand.accDecoder(address, closed)` when binary is supported (OPTION_2006 or USB), else `NmraPacket.accDecoderPkt(address, closed)` and `NceMessage.sendPacketMessage(tc, bl)`.
- **Files:** `cc.panelsd.connect.core.impl.nce.DirectNceThrottleSession`, `DirectNceAccessoryController`; both `NceSerialConnection` and `NceUsbConnection` create and return them.

---

## Checklist for adding another controller

- [ ] Identify the jmrix package: `jmri.jmrix.<protocol>`.
- [ ] Find the **traffic controller** (or equivalent) and how the connection class gets it from the memo.
- [ ] Find the **Message** class and the factory methods for throttle speed/direction and accessory/turnout.
- [ ] Implement `DirectXxxThrottleSession` using those factories and the traffic controller; keep JMRI throttle only for functions if needed.
- [ ] Implement `DirectXxxAccessoryController` using the turnout/accessory factory and the traffic controller.
- [ ] In the connection class: create and return these “direct” implementations from `openThrottle()` and `getAccessoryController()`.
- [ ] Add the connection type to `DccIoServiceImpl.createConnection()` and to device discovery if applicable.
- [ ] Document any protocol-specific quirks (e.g. Elite’s accessory +1) in the implementation or in a small doc like `XNET_ELITE_IMPLEMENTATION.md`.

---

## References

- JMRI jmrix source: e.g. `JMRI-5.13.6/java/src/jmri/jmrix/` (lenz, dccpp, nce, …).
- XNet example: `jmri.jmrix.lenz.XNetMessage` (e.g. `getTurnoutCommandMsg`, `getSpeedAndDirectionMsg`), `XNetTrafficController.sendXNetMessage`.
- Daemon interfaces: `cc.panelsd.connect.core.AccessoryController`, `cc.panelsd.connect.core.ThrottleSession`, `cc.panelsd.connect.core.CommandStationConnection`.
