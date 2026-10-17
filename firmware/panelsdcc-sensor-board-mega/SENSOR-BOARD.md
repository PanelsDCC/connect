# Mega AVR node sketch

See [../panelsdcc-sensor-board/SENSOR-BOARD.md](../panelsdcc-sensor-board/SENSOR-BOARD.md).

Default sensors: optical A0–A14 (A15 reference), digital D22–D37. PWM/servo silk pins D2–D13 left free for CFG outputs.

Uses shared [`../common/NodeEngine`](../common/) for CFG (EEPROM), optical districts (`opticalDistricts[]` + `sensitivityPct`), and accessory engines.
