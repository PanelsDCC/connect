# ESP32 node sketch

See [../panelsdcc-sensor-board/SENSOR-BOARD.md](../panelsdcc-sensor-board/SENSOR-BOARD.md).

Flash over USB. Bluetooth name: `PanelsDCC-{prefix}`. Silk labels match Nano/Mega carriers; GPIO map is internal.

`BOARD_TYPE` `nano-esp32` or `mega-esp32` selects the silk→GPIO table. Uses shared [`../common/NodeEngine`](../common/) (CFG in NVS).
