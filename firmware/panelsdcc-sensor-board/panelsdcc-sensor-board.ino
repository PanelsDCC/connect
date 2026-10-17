/*
 * PanelsDCC Node — Arduino Nano (AVR)
 *
 * Default sensors: optical A0–A6 (A7 = ambient ref), digital D2–D12.
 * Outputs share the same pin budget — assign via CFG (may displace inputs).
 *
 * Shared runtime: NodeEngine (CFG / EEPROM / output engines).
 */

#include "NodeEngine.h"

const char* BOARD_PREFIX = "A";
const char* BOARD_TYPE = "nano-avr";

const char* boardPrefix() { return BOARD_PREFIX; }
const char* boardType() { return BOARD_TYPE; }
Stream* boardLink() { return &Serial; }

int boardSilkToGpio(const char* silk) {
  if (!silk || !silk[0]) return -1;
  char c0 = silk[0];
  if (c0 >= 'a' && c0 <= 'z') c0 -= 32;
  int n = atoi(silk + 1);
  if (c0 == 'A') {
    if (n < 0 || n > 7) return -1;
    return A0 + n;
  }
  if (c0 == 'D') {
    if (n < 0 || n > 13) return -1;
    return n;
  }
  return -1;
}

void boardLoadDefaultPinRoles(NodePinRoleEntry* roles, uint8_t* count, int* refGpio) {
  *count = 0;
  *refGpio = A7;
  for (int i = 0; i <= 6 && *count < NODE_MAX_PIN_ROLES; i++) {
    NodePinRoleEntry* e = &roles[(*count)++];
    e->label[0] = 'A';
    e->label[1] = (char)('0' + i);
    e->label[2] = '\0';
    e->role = ROLE_OPTICAL;
    e->gpio = A0 + i;
  }
  for (int d = 2; d <= 12 && *count < NODE_MAX_PIN_ROLES; d++) {
    NodePinRoleEntry* e = &roles[(*count)++];
    e->label[0] = 'D';
    if (d < 10) {
      e->label[1] = (char)('0' + d);
      e->label[2] = '\0';
    } else {
      e->label[1] = '1';
      e->label[2] = (char)('0' + (d - 10));
      e->label[3] = '\0';
    }
    e->role = ROLE_DIGITAL_IN;
    e->gpio = d;
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
