# Prebuilt node images

Connect on the Pi flashes these files. It does not compile them.

| Id | File | Upload |
|----|------|--------|
| `mega-avr` | `mega-avr.hex` | `avrdude -p atmega2560 -c wiring -b 115200` |
| `nano-esp32` | `nano-esp32.*.bin` | `esptool` chip `esp32s3`, 16MB flash, bootloader at `0x0` |
| `mega-esp32` | `mega-esp32.*.bin` | `esptool` chip `esp32`, bootloader at `0x1000` |

`manifest.json` records a build-date `version` (`YYYY-MM-DD`), tool, MCU or chip, and SHA-256. After upload, Connect sends `PREFIX {letter}` (the compiled default letter is `A`).

Nano AVR is not in this set. Flash one by uploading the panelsDCC-Nodes zip on the Nodes screen. That zip’s manifest is also stamped with the build date. Connect stores it under `~/.dcc-io/firmware-images/` and reads the pin layout from the board with `CFG?`.

## Newer images

For Mega and ESP32, a cache entry whose build date is later than the shipped one is used instead. Upload that zip from the Nodes screen, or place the `manifest.json` and image files in `~/.dcc-io/firmware-images/`.

## Where the files come from

These bytes are a copy of the catalog in `panelsdcc-nodes/catalog`. Rebuild them there, then copy the catalog into this directory so the Pi and panelsDCC-Nodes ship the same images.

```bash
cd panelsdcc-nodes
./scripts/build-board.sh
```

That script needs `arduino-cli`, the AVR core, and the ESP32 2.0.17 core. The Pi does not compile.
