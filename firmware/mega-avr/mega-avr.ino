/*
 * PanelsDCC Node — Arduino Mega (AVR)
 *
 * Silk-screen labels match the Mega printing (A0–A15, D0–D53).
 * Default sensors: optical A0–A14 (A15 = ambient ref), digital D22–D37.
 * D2–D13 left free for PWM/servo outputs (configured via CFG without reflash).
 *
 * Shared runtime: NodeEngine (CFG / EEPROM / output engines).
 */

#include "NodeEngine.h"

const char* BOARD_PREFIX = "A";
const char* BOARD_TYPE = "mega-avr";

const char* boardPrefix() { return BOARD_PREFIX; }
const char* boardType() { return BOARD_TYPE; }
Stream* boardLink() { return &Serial; }

int boardSilkToGpio(const char* silk) {
  if (!silk || !silk[0]) return -1;
  char c0 = silk[0];
  if (c0 >= 'a' && c0 <= 'z') c0 -= 32;
  int n = atoi(silk + 1);
  if (c0 == 'A') {
    if (n < 0 || n > 15) return -1;
    return A0 + n;
  }
  if (c0 == 'D') {
    if (n < 0 || n > 53) return -1;
    return n;
  }
  return -1;
}

int boardAmbientRefGpio() { return A15; }

void boardLoadDefaultPinRoles(NodePinRoleEntry* roles, uint8_t* count, int* refGpio) {
  *count = 0;
  *refGpio = A15;
  for (int i = 0; i <= 14 && *count < NODE_MAX_PIN_ROLES; i++) {
    NodePinRoleEntry* e = &roles[(*count)++];
    e->label[0] = 'A';
    if (i < 10) {
      e->label[1] = (char)('0' + i);
      e->label[2] = '\0';
    } else {
      e->label[1] = '1';
      e->label[2] = (char)('0' + (i - 10));
      e->label[3] = '\0';
    }
    e->role = ROLE_OPTICAL;
    e->sensitivityPct = 0;
    e->gpio = A0 + i;
    e->refGpio = -1;
  }
  for (int d = 22; d <= 37 && *count < NODE_MAX_PIN_ROLES; d++) {
    NodePinRoleEntry* e = &roles[(*count)++];
    e->label[0] = 'D';
    e->label[1] = (char)('0' + (d / 10));
    e->label[2] = (char)('0' + (d % 10));
    e->label[3] = '\0';
    e->role = ROLE_DIGITAL_IN;
    e->sensitivityPct = 0;
    e->gpio = d;
    e->refGpio = -1;
  }
}

void setup() {
  Serial.begin(9600);
  delay(500);
  nodeEngineBegin();
}

void loop() {
  nodeEngineLoop();
  delay(20);
}
