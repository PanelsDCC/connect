# Connect Sensor Boards

USB **PanelsDCC Sensor Boards** (Nano + CH340) are managed from the Connect web UI
under **Sensors** (`#sensors`).

## What Connect does

1. **Probe** CH340 ports (`1a86:7523`) — sensor banner @ 9600 vs DCC-EX @ 115200 (see [usb-device-probe.md](./usb-device-probe.md)).
2. **Listen** on sensor ports @ 9600; publish `sensorHardware` patches for Control.
3. **Release / resume** a port so it can be flashed without stopping the whole service.
4. **Flash** the bundled sketch with a chosen board prefix (`A`…`Z`, `AA`…).

Wi‑Fi (ESP32) and Bluetooth sensor transports are reserved in the API/UI as **Coming soon**.

## Flash from the UI

1. Open Connect config → **Sensors**.
2. Pick the USB port and a prefix.
3. Click **Flash board** (Connect releases the port, runs `arduino-cli`, then resumes listening).

Channels created:

| Kind | Pins | IDs |
|------|------|-----|
| Optical | A0–A6 | `{prefix}0` … `{prefix}6` |
| Digital | D2–D12 (`INPUT_PULLUP`; D13 LED skipped) | `{prefix}D2` … `{prefix}D12` |

The Sensors page shows each board as an LED status panel (amber = optical DETECTED, green = digital DETECTED).

## Toolchain (headless package install)

On `panelsdcc-connect` install, `postinst` runs:

```bash
sudo panelsdcc-connect-arduino-install
```

That downloads official **arduino-cli** for the host arch and installs the **`arduino:avr`**
core into `/var/lib/panelsdcc-connect/arduino` (owned by `dcc-io`).

Paths:

| Item | Path |
|------|------|
| CLI | `/usr/lib/panelsdcc-connect/bin/arduino-cli` |
| Data | `/var/lib/panelsdcc-connect/arduino` |
| Sketch | `/usr/share/panelsdcc-connect/firmware/panelsdcc-sensor-board/` |

Network is required once at install. If it failed:

```bash
sudo panelsdcc-connect-arduino-install
```

## Serial probe

CH340 ports are probed for the sensor READY banner @ 9600 (with a longer boot wait
and one retry), then DCC-EX @ 115200. Auto-probe starts a few seconds after Connect
boots so USB can settle.

If a board stays missing after reboot, open **Sensors → USB candidates → Probe now**,
or use **Probe now** on the DCC Discover row.

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/sensors` | Boards (`channels[]`), candidates needing probe, transports |
| POST | `/api/sensors/probe` | `{ "port": "ttyUSB0" }` — classify and start listening if sensor |
| GET | `/api/sensors/events` | Recent occupancy edges |
| GET | `/api/sensors/toolchain` | CLI / FQBN readiness |
| POST | `/api/sensors/{id}/release` | Free tty for flash |
| POST | `/api/sensors/{id}/resume` | Resume listening |
| POST | `/api/sensors/flash` | `{ "port", "prefix" }` |
| GET | `/api/sensors/flash/status` | Progress log |

## Serial contract

See bundled `SENSOR-BOARD.md` and [usb-device-probe.md](./usb-device-probe.md).
