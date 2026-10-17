# Shared node engine

`NodeEngine.h` / `NodeEngine.cpp` implement CFG parse + EEPROM/NVS persist, pin roles,
sensor scanning, and accessory output engines for all board sketches.

Connect’s `SensorBoardSketchPrep` copies this folder into the sketch workdir before
`arduino-cli` compile. Packaged install path:
`/usr/share/panelsdcc-connect/firmware/common/`.
