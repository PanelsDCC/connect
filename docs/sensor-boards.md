# Connect Nodes (sensor + accessory boards)

USB / Bluetooth / **virtual** PanelsDCC nodes are managed from the Connect web UI
under **Nodes** (`#sensors`).

## What Connect does

1. **Probe** CH340 ports (`1a86:7523`) — node READY @ 9600 vs DCC-EX @ 115200 (see [usb-device-probe.md](./usb-device-probe.md)).
2. **Listen** on node ports @ 9600; publish `sensorHardware` patches for Control.
3. **Release / resume** a port so it can be flashed without stopping the whole service.
4. **Flash** the sketch for a chosen **board type** + prefix (`A`…`Z`, `AA`…).
5. **Virtual (shim) boards** for UI/CFG/Test without hardware (`POST /api/sensors/shim`).
6. **Broadcast** accessory address/state lines to **all** nodes (DCC-decoder style); boards filter via CFG.
7. **Re-push CFG** from inventory when a USB node reconnects (after boot delay).
8. **Bluetooth** (ESP32 SPP) on Linux/BlueZ — scan/pair APIs; desktop uses shims.

## Board types

| Id | Hardware | Flash FQBN |
|----|----------|------------|
| `nano-avr` | Arduino Nano 5 V | `arduino:avr:nano` |
| `mega-avr` | Arduino Mega 5 V | `arduino:avr:mega` |
| `nano-esp32` | ESP32 Nano-class 3.3 V | `esp32:esp32:esp32` |
| `mega-esp32` | ESP32 Mega-class 3.3 V | `esp32:esp32:esp32` |

Silk-screen labels in the UI match the PCB (`D6` = D6). See bundled `SENSOR-BOARD.md`.

## Inventory

`~/.dcc-io/nodes.json` stores prefix, boardType, transport, pins/outputs/CFG (including `opticalDistricts[]` for analogue sensor groups with a Reference pin and `sensitivityPct`).

## Flash from the UI

1. Open Connect → **Nodes**.
2. Pick port, **board type**, and prefix (or add a Virtual board).
3. **Flash board** (Connect releases the port, runs `arduino-cli`, resumes).

## Toolchain

```bash
sudo panelsdcc-connect-arduino-install
```

Installs `arduino-cli`, **`arduino:avr`**, **`esp32:esp32`**, and the **Servo** library into `/var/lib/panelsdcc-connect/arduino`.

| Item | Path |
|------|------|
| CLI | `/usr/lib/panelsdcc-connect/bin/arduino-cli` |
| Data | `/var/lib/panelsdcc-connect/arduino` |
| Sketches | `/usr/share/panelsdcc-connect/firmware/panelsdcc-sensor-board[-mega|-esp32]` |
| Shared engine | `/usr/share/panelsdcc-connect/firmware/common` (copied into flash workdir) |

## API (selected)

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/sensors` | Boards, candidates, profiles, transports |
| POST | `/api/sensors/shim` | `{ prefix?, boardType }` virtual board |
| POST | `/api/sensors/flash` | `{ port, prefix, boardType }` |
| POST | `/api/sensors/{id}/config` | Push CFG JSON |
| POST | `/api/sensors/{id}/test` | Unicast TEST command |
| POST | `/api/sensors/{id}/simulate` | Shim channel flip |
| POST | `/api/sensors/bluetooth/scan` | BlueZ scan (Pi) |
| POST | `/api/sensors/bluetooth/pair` | `{ address, prefix }` |

## Serial contract

See `firmware/.../SENSOR-BOARD.md`.
