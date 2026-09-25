# PanelsDCC Sensor Board

Firmware for Elegoo/Arduino Nano (CH340) boards that report track-side optical
occupancy and digital inputs into the Panels DCC world.

Each physical Nano is identified by a **board prefix** chosen at flash time
(`A`, `B`, … `Z`, then `AA`, `AB`, …).

## Hardware IDs

| Kind | Pins | `hardwareId` | Active when |
|------|------|--------------|-------------|
| Optical | A0…A6 | `{prefix}{n}` e.g. `A0`, `AB3` | `(sensor − A7) > 130` |
| Ambient | A7 | _(not reported)_ | reference only |
| Digital | D2…D12 (not D13 / built-in LED) | `{prefix}D{pin}` e.g. `AD2`, `BD12` | pin **LOW** (INPUT_PULLUP to GND) |
| Serial | D0, D1 | _(reserved)_ | USB UART — not inputs |

## Serial contract

| Item | Value |
|------|--------|
| Baud | 9600 8N1 |
| Boot | `PanelsDCC Sensor Board {prefix} READY` |
| Then | full snapshot of all channels (DETECTED/CLEAR) |
| Edge | `{id}: DETECTED` / `{id}: CLEAR` |
| CLEAR debounce | 1000 ms continuous clear |

## Flash

FQBN `arduino:avr:nano`. Prefer Connect **Sensors** UI flash with prefix.
