# Shared node engine

`NodeEngine.h` / `NodeEngine.cpp` implement CFG parse + EEPROM/NVS persist, pin roles,
sensor scanning, accessory output engines, and the `PREFIX` letter override.

Image builds copy this folder into the sketch directory before `arduino-cli compile`
(see `../images/README.md`). The packaged Pi install flashes those binaries and does
not ship this source as the flash input.
