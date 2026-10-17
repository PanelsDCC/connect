#pragma once
/*
 * PanelsDCC node runtime: CFG parse/persist, pin roles, sensor scan, output engines.
 * Board sketches provide silk→GPIO mapping via boardSilkToGpio() and defaults.
 */

#include <Arduino.h>
#include <Servo.h>

/* Limits: Nano AVR has 2KB SRAM / 1KB EEPROM — keep tight there. */
#if defined(__AVR_ATmega328P__) || defined(__AVR_ATmega168__)
#ifndef NODE_MAX_CFG
#define NODE_MAX_CFG 900
#endif
#ifndef NODE_MAX_LINE
#define NODE_MAX_LINE 960
#endif
#ifndef NODE_MAX_PIN_ROLES
#define NODE_MAX_PIN_ROLES 24
#endif
#ifndef NODE_MAX_SENSORS
#define NODE_MAX_SENSORS 18
#endif
#ifndef NODE_MAX_OUTPUTS
#define NODE_MAX_OUTPUTS 4
#endif
#ifndef NODE_MAX_OUT_PINS
#define NODE_MAX_OUT_PINS 6
#endif
#ifndef NODE_MAX_TRIGGERS
#define NODE_MAX_TRIGGERS 4
#endif
#ifndef NODE_MAX_SERVOS
#define NODE_MAX_SERVOS 4
#endif
#ifndef NODE_MAX_DISTRICTS
#define NODE_MAX_DISTRICTS 4
#endif
#else
#ifndef NODE_MAX_CFG
#define NODE_MAX_CFG 1400
#endif
#ifndef NODE_MAX_LINE
#define NODE_MAX_LINE 1500
#endif
#ifndef NODE_MAX_PIN_ROLES
#define NODE_MAX_PIN_ROLES 48
#endif
#ifndef NODE_MAX_SENSORS
#define NODE_MAX_SENSORS 32
#endif
#ifndef NODE_MAX_OUTPUTS
#define NODE_MAX_OUTPUTS 8
#endif
#ifndef NODE_MAX_OUT_PINS
#define NODE_MAX_OUT_PINS 8
#endif
#ifndef NODE_MAX_TRIGGERS
#define NODE_MAX_TRIGGERS 8
#endif
#ifndef NODE_MAX_SERVOS
#define NODE_MAX_SERVOS 12
#endif
#ifndef NODE_MAX_DISTRICTS
#define NODE_MAX_DISTRICTS 8
#endif
#endif

#ifndef NODE_DEFAULT_SENSITIVITY_PCT
#define NODE_DEFAULT_SENSITIVITY_PCT 15
#endif

enum NodePinRole : uint8_t {
  ROLE_FREE = 0,
  ROLE_OPTICAL,
  ROLE_DIGITAL_IN,
  ROLE_OUTPUT,
  ROLE_REF
};

enum NodeOutType : uint8_t {
  OUT_ON_OFF = 0,
  OUT_PULSE,
  OUT_BLINK,
  OUT_ALT_PAIR,
  OUT_FADE,
  OUT_SERVO,
  OUT_LEVEL_CROSSING,
  OUT_DIGITAL_SIGNAL,
  OUT_UNKNOWN
};

enum LcPhase : uint8_t {
  LC_IDLE_OPEN = 0,
  LC_WARNING,
  LC_WIGWAG,
  LC_BARRIERS_CLOSING,
  LC_CLOSED,
  LC_BARRIERS_OPENING,
  LC_CLEARING
};

struct NodePinRoleEntry {
  char label[5]; // "D22", "A15"
  NodePinRole role;
  int gpio;
};

struct NodeSensor {
  char label[5];
  int gpio;
  bool optical; // else digital_in
  int channel;  // A-index for optical silk number; D silk number for digital
  int refGpio;  // optical: district reference pin; -1 for digital
  uint8_t sensitivityPct; // optical: % of ref reading
  bool reportedActive;
  unsigned long clearSinceMs;
};

struct NodeTrigger {
  int dcc;
  bool wireOn; // Forward/ON → true, Reverse/OFF → false
  char state[12];
  int deg;
  int brightness;
  uint8_t onMask;       // digital_signal: bit i → pin i on
  uint8_t flashMask;    // digital_signal: bit i → pin i flashing
  bool hasDeg;
  bool hasBrightness;
};

struct NodeOutput {
  NodeOutType type;
  uint8_t pinCount;
  int gpio[NODE_MAX_OUT_PINS];
  char pinLabel[NODE_MAX_OUT_PINS][5];
  int8_t servoIx[NODE_MAX_OUT_PINS]; // -1 if unused
  uint8_t triggerCount;
  NodeTrigger triggers[NODE_MAX_TRIGGERS];
  // params
  int pulseMs;
  int onMs;
  int offMs;
  int fadeMs;
  int travelMs;
  int warningMs;
  int wigwagOnMs;
  int wigwagOffMs;
  int barrierTravelMs;
  int barrierOpenDeg;
  int barrierClosedDeg;
  int flashMs;
  // runtime
  bool active; // blinking / on / pulse running / etc
  bool phaseHigh;
  unsigned long nextToggleMs;
  unsigned long pulseUntilMs;
  int currentDeg;
  int targetDeg;
  int currentBright;
  int targetBright;
  unsigned long fadeStartMs;
  int fadeFromBright;
  LcPhase lcPhase;
  unsigned long phaseStartedMs;
  unsigned long phaseUntilMs;
  bool wigwagLeftOn;
  uint8_t signalOnMask;
  uint8_t signalFlashMask;
  bool signalFlashHigh;
};

/** Board must implement: silk "D9"/"A3" → MCU pin, or -1. */
int boardSilkToGpio(const char* silk);

/** Fill default optical/digital_in roles when EEPROM empty / CFG has no pins. */
void boardLoadDefaultPinRoles(NodePinRoleEntry* roles, uint8_t* count, int* refGpio);

/** Serial (or BT) stream for commands and sensor lines. */
Stream* boardLink();

/** Identity strings from the sketch. */
const char* boardPrefix();
const char* boardType();

void nodeEngineBegin();
/** Poll boardLink(), then scan sensors and tick outputs (USB boards). */
void nodeEngineLoop();
/** Poll one stream for CFG / accessory / TEST lines (ESP32 may poll USB + BT). */
void nodeEnginePollStream(Stream* s);
/** Sensors + output engines only (no serial). */
void nodeEngineTick();

/** Persist / apply helpers exposed for tests if needed. */
bool nodeApplyCfgJson(const char* json);
void nodeReplyCfg();
