# PanelsDCC Node boards (sensors + outputs)

Firmware for Arduino **Nano** / **Mega** (5 V AVR) and **ESP32** Nano-/Mega-class carriers (3.3 V).
Connect flashes identity (`BOARD_PREFIX`, `BOARD_TYPE`) over USB; ESP32 may then run over Bluetooth Classic SPP.

## Board types

| `BOARD_TYPE` | Sketch dir | FQBN | Default transport |
|---|---|---|---|
| `nano-avr` | `panelsdcc-sensor-board` | `arduino:avr:nano` | USB |
| `mega-avr` | `panelsdcc-sensor-board-mega` | `arduino:avr:mega` | USB |
| `nano-esp32` / `mega-esp32` | `panelsdcc-sensor-board-esp32` | `esp32:esp32:esp32` | USB flash, BT SPP runtime |

Silk-screen pin names in the Connect UI always match the PCB (`D6`, `A3`). ESP GPIO mapping is firmware-internal.

## Hardware IDs

Board prefixes: `A`…`Z`, then `AA`…`ZZ`.

| Kind | Nano default | Mega default | `hardwareId` |
|------|--------------|--------------|--------------|
| Optical | A0…A6 (A7 reference) | A0…A14 (A15 reference) | `{prefix}-A{n}` |
| Digital in | D2…D12 | D22…D37 | `{prefix}-D{n}` |

### Optical districts (CFG)

Analogue sensors are grouped into **districts**. Each district has one **Reference** pin (ambient, not reported), member sensors, and `sensitivityPct` (1–100, default 15): DETECTED when `(sensor − ref) > ref × pct / 100`.

```json
"opticalDistricts": [
  { "name": "Yard", "ref": "A15", "sensors": ["A0","A1"], "sensitivityPct": 15 }
]
```

If omitted or empty, Connect configures no optical sensors until you add districts. Unconfigured firmware (no CFG) still boots with the board’s factory pin map.

## Serial / SPP contract

| Item | Value |
|------|--------|
| Baud | 9600 8N1 |
| Boot | `PanelsDCC Node {prefix} {boardType} READY` (legacy: `PanelsDCC Sensor Board {prefix} READY`) |
| Then | full snapshot (DETECTED/CLEAR) |
| Edge | `{id}: DETECTED` / `{id}: CLEAR` |
| CLEAR debounce | 1000 ms |
| CFG | `CFG {json}` — pin roles, DCC↔output params (EEPROM on AVR / NVS on ESP32) |
| CFG pull | `CFG?` → `CFG {json}` |
| Identity | `ID?` → `ID {prefix} {boardType}` (no reboot required) |
| Layout accessory | `{address} ON` / `{address} OFF` — **broadcast to all nodes**; board ignores if not in CFG. `ON` = accessory Forward, `OFF` = Reverse (same mapping as panels-modular closed/thrown). |
| Test (unicast) | `TEST {address} ON\|OFF`; `TEST SERVO {pin} {deg}` / `TEST PWM {pin} {0-100}`; `TEST SAMPLE {ref} [{sensor}]` → `SAMPLE A15=812 A0=1020 delta=208 pct=25` (calibrate sensitivity) |

## Shared engine

Runtime code lives in [`../common/`](../common/) (`NodeEngine.h` / `NodeEngine.cpp`): CFG parse/persist, pin roles, sensors, and output engines (`on_off`, `pulse`, `blink`, `alt_pair`, `fade`, `servo`, `level_crossing`, `digital_signal`). Connect’s flash prep copies `common/` into the sketch workdir.

## Flash

Prefer Connect **Nodes** UI: pick board type + prefix. Toolchain: `sudo panelsdcc-connect-arduino-install` (AVR + ESP32 cores).

## Virtual (shim) boards

Connect can create `transport: shim` boards with no hardware for UI/CFG/Test development.
