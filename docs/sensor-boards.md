# Connect Nodes (sensor + accessory boards)

USB / Bluetooth / **virtual** PanelsDCC nodes are managed from the Connect web UI
under **Nodes** (`#nodes`).

## What Connect does

1. **Probe** CH340 ports (`1a86:7523`) — node READY @ 9600 vs DCC-EX @ 115200 (see [usb-device-probe.md](./usb-device-probe.md)).
2. **Listen** on node ports @ 9600; publish `sensorHardware` patches for Control.
3. **Release / resume** a port so it can be flashed without stopping the whole service.
4. **Flash** a prebuilt image for the chosen **board type**, then set the prefix (`A`…`Z`, `AA`…) with `PREFIX`.
5. **Virtual (shim) boards** for UI/CFG/Test without hardware (`POST /api/nodes/shim`).
6. **Broadcast** accessory address/state lines to **all** nodes (DCC-decoder style); boards filter via CFG.
7. **Re-push CFG** from inventory when a USB node reconnects (after boot delay).
8. **Bluetooth** (ESP32 SPP) on Linux/BlueZ — scan/pair APIs; desktop uses shims.

## Board types

| Id | Hardware | Upload |
|----|----------|--------|
| `nano-avr` | Arduino Nano 5 V | `avrdude` (`atmega328p`, `arduino`, 57600 / 115200) |
| `mega-avr` | Arduino Mega 5 V | `avrdude` (`atmega2560`, `wiring`, 115200) |
| `nano-esp32` | ESP32 Nano-class 3.3 V | `esptool` (`esp32`) |
| `mega-esp32` | ESP32 Mega-class 3.3 V | `esptool` (`esp32`) |

Silk-screen labels in the UI match the PCB (`D6` = D6). See bundled `NODE.md`.

## Inventory

`~/.dcc-io/nodes.json` stores prefix, boardType, transport, pins/outputs/CFG (including `opticalDistricts[]` for analogue sensor groups with a Reference pin and `sensitivityPct`).

## Flash from the UI

1. Open Connect → **Nodes**.
2. Pick port, **board type**, and prefix (or add a Virtual board).
3. **Flash board**. Connect releases the port, checks the image hash, uploads with `avrdude` or `esptool`, sends `PREFIX {letter}`, then resumes.

The Pi does not compile. Images ship in `/usr/share/panelsdcc-connect/images/` (`manifest.json` plus hex/bin). Distro packages `avrdude` and `esptool` are the only upload tools.

A newer image dropped in `~/.dcc-io/firmware-images/` (same manifest shape, higher `version`) is used instead of the shipped file. Connect does not download a catalog. An optional `config` filename on a cache entry is recorded and not applied; a later custom-Nano import can use that JSON as inventory when the board cannot be probed.

Sketch sources stay in the git tree beside the images. `firmware/images/README.md` points at the panelsdcc-nodes catalog, which is where those images are built.

## API (selected)

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/nodes` | Boards, candidates, profiles, transports |
| POST | `/api/nodes/shim` | `{ prefix?, boardType }` virtual board |
| POST | `/api/nodes/flash` | `{ port, prefix, boardType }` |
| POST | `/api/nodes/{id}/config` | Push CFG JSON |
| POST | `/api/nodes/{id}/test` | Unicast TEST command |
| POST | `/api/nodes/{id}/simulate` | Shim channel flip |
| POST | `/api/nodes/bluetooth/scan` | BlueZ scan (Pi) |
| POST | `/api/nodes/bluetooth/pair` | `{ address, prefix }` |

## Serial contract

See `firmware/nano-avr/NODE.md`.
