/*
 * PanelsDCC Node — ESP32 (Nano- or Mega-class silk labels)
 *
 * BOARD_TYPE is "nano-esp32" or "mega-esp32". Silk labels match the carrier
 * printing; MCU GPIO mapping is internal (users never see GPIO numbers).
 *
 * Flash over USB; runtime intended over Bluetooth Classic SPP
 * (device name PanelsDCC-{prefix}).
 *
 * Shared runtime: NodeEngine (CFG / NVS / output engines).
 */

#include "NodeEngine.h"

#if defined(ESP32)
#include "BluetoothSerial.h"
BluetoothSerial SerialBT;
#endif

const char* BOARD_PREFIX = "A";
const char* BOARD_TYPE = "nano-esp32";

static Stream* gLink = &Serial;

const char* boardPrefix() { return BOARD_PREFIX; }
const char* boardType() { return BOARD_TYPE; }
Stream* boardLink() { return gLink; }

static bool isMegaProfile() {
  return String(BOARD_TYPE).startsWith("mega");
}

// Nano-class silk → GPIO (illustrative carrier map; adjust per board)
static int nanoSilkToGpio(const char* silk) {
  char c0 = silk[0];
  if (c0 >= 'a' && c0 <= 'z') c0 -= 32;
  int n = atoi(silk + 1);
  if (c0 == 'A') {
    static const int map[] = {36, 39, 34, 35, 32, 33, 25, 26}; // A0..A7
    if (n < 0 || n > 7) return -1;
    return map[n];
  }
  if (c0 == 'D') {
    // D0/D1 UART; D2..D12 GPIOs; D13 LED
    static const int map[] = {
        1, 3, 2, 4, 5, 12, 13, 14, 15, 16, 17, 18, 19, 23 // D0..D13
    };
    if (n < 0 || n > 13) return -1;
    return map[n];
  }
  return -1;
}

// Mega-class silk → GPIO (expanded carrier; A0–A15, D2–D37 subset)
static int megaSilkToGpio(const char* silk) {
  char c0 = silk[0];
  if (c0 >= 'a' && c0 <= 'z') c0 -= 32;
  int n = atoi(silk + 1);
  if (c0 == 'A') {
    // A0–A15 → ADC-capable GPIOs where available; spill to digital GPIOs
    static const int map[] = {
        36, 39, 34, 35, 32, 33, 25, 26, 27, 14, 12, 13, 15, 2, 4, 0 // A0..A15
    };
    if (n < 0 || n > 15) return -1;
    return map[n];
  }
  if (c0 == 'D') {
    // Prefer free GPIOs for D2–D13 outputs; D22–D37 digital bank
    if (n == 0) return 1;
    if (n == 1) return 3;
    if (n >= 2 && n <= 13) {
      static const int d2_13[] = {16, 17, 18, 19, 21, 22, 23, 5, 12, 13, 14, 15};
      return d2_13[n - 2];
    }
    if (n >= 22 && n <= 37) {
      // Map digital bank onto remaining GPIOs (may overlap on limited modules —
      // production carriers should redefine this table).
      static const int bank[] = {
          2, 4, 5, 12, 13, 14, 15, 16, 17, 18, 19, 21, 22, 23, 25, 26
      };
      return bank[n - 22];
    }
    // Other D pins: not mapped on typical ESP32 Mega carriers
    return -1;
  }
  return -1;
}

int boardSilkToGpio(const char* silk) {
  if (!silk || !silk[0]) return -1;
  return isMegaProfile() ? megaSilkToGpio(silk) : nanoSilkToGpio(silk);
}

static void pushRole(NodePinRoleEntry* roles, uint8_t* count, const char* label, NodePinRole role) {
  if (*count >= NODE_MAX_PIN_ROLES) return;
  NodePinRoleEntry* e = &roles[(*count)++];
  size_t i = 0;
  while (label[i] && i + 1 < sizeof(e->label)) {
    char c = label[i];
    if (c >= 'a' && c <= 'z') c -= 32;
    e->label[i] = c;
    i++;
  }
  e->label[i] = '\0';
  e->role = role;
  e->gpio = boardSilkToGpio(e->label);
}

void boardLoadDefaultPinRoles(NodePinRoleEntry* roles, uint8_t* count, int* refGpio) {
  *count = 0;
  if (isMegaProfile()) {
    *refGpio = boardSilkToGpio("A15");
    for (int i = 0; i <= 14; i++) {
      char lab[5];
      if (i < 10) {
        lab[0] = 'A';
        lab[1] = (char)('0' + i);
        lab[2] = '\0';
      } else {
        lab[0] = 'A';
        lab[1] = '1';
        lab[2] = (char)('0' + (i - 10));
        lab[3] = '\0';
      }
      pushRole(roles, count, lab, ROLE_OPTICAL);
    }
    for (int d = 22; d <= 37; d++) {
      char lab[5];
      lab[0] = 'D';
      lab[1] = (char)('0' + (d / 10));
      lab[2] = (char)('0' + (d % 10));
      lab[3] = '\0';
      pushRole(roles, count, lab, ROLE_DIGITAL_IN);
    }
  } else {
    *refGpio = boardSilkToGpio("A7");
    for (int i = 0; i <= 6; i++) {
      char lab[3] = {'A', (char)('0' + i), '\0'};
      pushRole(roles, count, lab, ROLE_OPTICAL);
    }
    for (int d = 2; d <= 12; d++) {
      char lab[5];
      lab[0] = 'D';
      if (d < 10) {
        lab[1] = (char)('0' + d);
        lab[2] = '\0';
      } else {
        lab[1] = '1';
        lab[2] = (char)('0' + (d - 10));
        lab[3] = '\0';
      }
      pushRole(roles, count, lab, ROLE_DIGITAL_IN);
    }
  }
}

void setup() {
  Serial.begin(9600);
  delay(500);
  gLink = &Serial;

#if defined(ESP32)
  String btName = String("PanelsDCC-") + BOARD_PREFIX;
  SerialBT.begin(btName);
#endif

  nodeEngineBegin();
}

void loop() {
  nodeEnginePollStream(&Serial);
#if defined(ESP32)
  nodeEnginePollStream(&SerialBT);
  // Prefer BT as reply channel once paired; USB still accepts CFG during flash/configure.
  if (SerialBT.connected()) {
    gLink = &SerialBT;
  } else {
    gLink = &Serial;
  }
#endif
  nodeEngineTick();
  delay(20);
}
