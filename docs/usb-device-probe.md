# Connect: USB auto-detect must probe (CH340 collision)

**Audience:** Panels DCC Connect developers  
**Related firmware/docs:** PanelsDCC node (`NODE.md`, sketch)

**Product name:** **PanelsDCC Node**  
**Status:** Implemented in Connect (serial probe + sensor sessions)

---

## Problem

Connect historically matched USB devices largely by **VID/PID**. That is **insufficient** for current layout hardware.

Both of these enumerate as the same USB device:

| Role | Hardware | `lsusb` |
|------|----------|---------|
| DCC command station | DCC-EX / EX-CSB1 (ESP32 + CH340) | `1a86:7523` QinHeng CH340 |
| **PanelsDCC Node** | Elegoo Arduino Nano (ATmega328P + CH340) | `1a86:7523` QinHeng CH340 |

USB fields for **both**: `idVendor=1a86`, `idProduct=7523`, product string `USB Serial`, no serial number. `/dev/serial/by-id/` is not unique.

**VID/PID alone cannot classify these devices.**

---

## Decision

Keep USB IDs as a **candidate filter** only (`needsProbe: true` on CH340 in `device-discovery-config.json`), then **probe** each candidate over the serial port:

1. **PanelsDCC Node** — banner @ 9600  
2. **Command station** (DCC-EX / DCC++) — `<s>` @ 115200  
3. **Unknown** — leave alone (~12s cooldown; ~4s if the port was busy)

Successful classifications are cached against `/dev/serial/by-path/…` basenames in `~/.dcc-io/usb-probe-cache.json` for faster reconnect.

Auto-probe is deferred a few seconds after Connect starts. Manual: `POST /api/nodes/probe` or **Probe now** in the UI.

---

## Probe procedure

### A. PanelsDCC Node

1. Open @ **9600 8N1** (DTR often resets AVR Nanos).  
2. Wait ~4 s for boot output; if no banner, settle briefly and retry once.  
3. Match: `PanelsDCC Node <prefix> READY`

→ type `panelsdcc-node`; start `UsbNodeSession` (do **not** open DCC++).

### B. Command station

1. Open @ **115200 8N1**.  
2. Send `<s>`.  
3. Success if reply looks like DCC-EX / DCC++ (`<i…`, `DCC-EX`, etc.).

→ existing `dccpp` connection path.

### C. Ordering / safety

- Never assume every `1a86:7523` is DCC++.  
- Prefer sensor banner first, then DCC-EX.  
- Release the port cleanly after a failed probe step.  
- Only one process may hold a port.

---

## Sensor line contract → Control

Runtime baud **9600**.

| Event | Line |
|-------|------|
| Boot / identify | `PanelsDCC Node {prefix} READY` |
| Occupied | `{prefix}-A{n}: DETECTED` / `{prefix}-D{n}: DETECTED` |
| Clear | `{prefix}-A{n}: CLEAR` / `{prefix}-D{n}: CLEAR` |

Connect publishes raw edges on the gateway WebSocket:

```json
{ "type": "sensorHardware", "method": "patch", "data": { "id": "A-A3", "state": 1 } }
```

Control’s sensor-processor calls `applySensorState`, then broadcasts enriched `sensorStates` for browsers. HTTP `POST/PUT /sensors/:id` remains for scripts; Connect does not use it for board edges.

---

## Flashing a PanelsDCC Node

Use the Connect web UI **Sensors** page (release port + flash with prefix). See [sensor-boards.md](./sensor-boards.md).

Manual alternative:

```bash
# Release the port from the Nodes UI, or temporarily:
sudo systemctl stop panelsdcc-connect
avrdude -p atmega328p -c arduino -P /dev/ttyUSB0 -b 57600 -D \
  -U flash:w:/usr/share/panelsdcc-connect/images/nano-avr.hex:i
# then, at 9600: PREFIX B
sudo systemctl start panelsdcc-connect
```

The package depends on `avrdude` and `esptool`. It does not install `arduino-cli`.

---

## Implementation map

| Piece | Location |
|-------|----------|
| Candidate config | `src/main/resources/device-discovery-config.json` (`needsProbe`) |
| Discovery + by-path | `DeviceDiscoveryService` |
| Probe | `UsbSerialProbe` |
| Cache | `UsbProbeCache` → `~/.dcc-io/usb-probe-cache.json` |
| Auto-connect | `DccIoServiceImpl` |
| Sensor session | `core.impl.sensor.UsbNodeSession` |
| Control ingest | `control/lib/sensor-processor.js` (`sensorHardware`) |

---

## One-sentence summary

**Connect must not bind every CH340 as DCC++; open the port and classify by serial probe (`PanelsDCC Node … READY` @ 9600 vs DCC-EX/DCC++ @ 115200), then keep sensor-board and command-station stacks on the correct tty.**
