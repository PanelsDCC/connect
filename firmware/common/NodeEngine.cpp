#include "NodeEngine.h"
#if defined(__has_include)
#  if __has_include("ImageVersion.h")
#    include "ImageVersion.h"
#  endif
#endif
#ifndef IMAGE_VERSION_STR
#define IMAGE_VERSION_STR "1970-01-01"
#endif
#include <math.h>
#include <string.h>
#include <stdlib.h>
#include <stddef.h>

#if defined(ESP32)
#include <Preferences.h>
#include <esp_system.h>
#if defined(CONFIG_IDF_TARGET_ESP32S3) || defined(CONFIG_IDF_TARGET_ESP32S2)
#include "soc/rtc_cntl_reg.h"
#endif
static Preferences gPrefs;
#else
#include <EEPROM.h>
#include <avr/eeprom.h>
#endif

// ---- globals ----
// A baked Nano image keeps pin and output configuration in program storage.
// These arrays are only the values that change while the node is running.
static uint16_t gCfgLen = 0;
static bool gCfgValid = false;
static char gLine[NODE_MAX_LINE];
static uint16_t gLineLen = 0;

#if !defined(NODE_BAKED_CONFIG)
static NodePinConfig gPinCfg[NODE_MAX_PIN_ROLES];
static NodeOutputConfig gOutCfg[NODE_MAX_OUTPUTS];
#endif
static NodePinRuntime gPinRt[NODE_MAX_PIN_ROLES];
static uint8_t gRoleCount = 0;
static int gRefGpio = -1;
static NodeOutputRuntime gOutRt[NODE_MAX_OUTPUTS];
static uint8_t gOutputCount = 0;

static Servo gServos[NODE_MAX_SERVOS];
static uint8_t gServoCount = 0;

static const unsigned long CLEAR_HOLD_MS = 1000;

// PREFIX override: 1–2 letters. Not stored inside the CFG blob (that starts at 0).
// AVR uses the last four EEPROM bytes: 'P' 'X' c0 c1 (c1 is 0 for a single letter).
// ESP32 uses NVS namespace "panelsdcc", key "prefix".
static char gRuntimePrefix[3];

static bool isPrefixLetter(char c) {
  return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
}

static void setRuntimePrefix(char a, char b) {
  if (a >= 'a' && a <= 'z') a = (char)(a - 32);
  if (b >= 'a' && b <= 'z') b = (char)(b - 32);
  gRuntimePrefix[0] = a;
  gRuntimePrefix[1] = b;
  gRuntimePrefix[2] = '\0';
}

static void persistRuntimePrefix() {
#if defined(ESP32)
  if (!gPrefs.begin("panelsdcc", false)) return;
  gPrefs.putString("prefix", gRuntimePrefix);
  gPrefs.end();
#else
  int base = (int)E2END - 3;
  if (base < 0) return;
  EEPROM.update(base, 'P');
  EEPROM.update(base + 1, 'X');
  EEPROM.update(base + 2, (uint8_t)gRuntimePrefix[0]);
  EEPROM.update(base + 3, gRuntimePrefix[1] ? (uint8_t)gRuntimePrefix[1] : 0);
#endif
}

static void loadRuntimePrefix() {
  gRuntimePrefix[0] = '\0';
  gRuntimePrefix[1] = '\0';
  gRuntimePrefix[2] = '\0';
#if defined(ESP32)
  if (!gPrefs.begin("panelsdcc", true)) return;
  String stored = gPrefs.getString("prefix", "");
  gPrefs.end();
  if (stored.length() < 1 || !isPrefixLetter(stored.charAt(0))) return;
  char b = 0;
  if (stored.length() >= 2 && isPrefixLetter(stored.charAt(1))) b = stored.charAt(1);
  setRuntimePrefix(stored.charAt(0), b);
#else
  int base = (int)E2END - 3;
  if (base < 0) return;
  if (EEPROM.read(base) != 'P' || EEPROM.read(base + 1) != 'X') return;
  char a = (char)EEPROM.read(base + 2);
  char b = (char)EEPROM.read(base + 3);
  if (!isPrefixLetter(a)) return;
  if (b != 0 && !isPrefixLetter(b)) b = 0;
  setRuntimePrefix(a, b);
#endif
}

void nodeLoadIdentity() {
  loadRuntimePrefix();
}

const char* nodeEffectivePrefix() {
  if (gRuntimePrefix[0]) return gRuntimePrefix;
  const char* compiled = boardPrefix();
  return (compiled && compiled[0]) ? compiled : "A";
}

static bool handlePrefix(const char* rest) {
  while (*rest == ' ') rest++;
  if (!isPrefixLetter(rest[0])) return false;
  char b = 0;
  if (isPrefixLetter(rest[1])) {
    b = rest[1];
    if (rest[2] != '\0' && rest[2] != ' ' && rest[2] != '\r') return false;
  } else if (rest[1] != '\0' && rest[1] != ' ' && rest[1] != '\r') {
    return false;
  }
  setRuntimePrefix(rest[0], b);
  persistRuntimePrefix();
  nodeReplyId();
  return true;
}
static const unsigned long LC_CLEARING_MS = 2000;

static const char EEPROM_MAGIC[4] = {'P', 'D', 'C', 'C'};
static const uint8_t CFG_VERSION = 1;

// ---- string utils ----
static void strCopyCap(char* dst, size_t cap, const char* src) {
  if (cap == 0) return;
  size_t i = 0;
  if (src) {
    while (src[i] && i + 1 < cap) {
      dst[i] = src[i];
      i++;
    }
  }
  dst[i] = '\0';
}

static bool streqi(const char* a, const char* b) {
  if (!a || !b) return false;
  while (*a && *b) {
    char ca = *a, cb = *b;
    if (ca >= 'A' && ca <= 'Z') ca += 32;
    if (cb >= 'A' && cb <= 'Z') cb += 32;
    if (ca != cb) return false;
    a++;
    b++;
  }
  return *a == *b;
}

static bool startsWithi(const char* s, const char* prefix) {
  if (!s || !prefix) return false;
  while (*prefix) {
    char ca = *s, cb = *prefix;
    if (ca >= 'A' && ca <= 'Z') ca += 32;
    if (cb >= 'A' && cb <= 'Z') cb += 32;
    if (ca != cb) return false;
    s++;
    prefix++;
  }
  return true;
}

static void normalizeSilk(char* out, size_t cap, const char* in) {
  if (cap < 2) return;
  size_t i = 0;
  while (in && in[i] && i + 1 < cap) {
    char c = in[i];
    if (c >= 'a' && c <= 'z') c -= 32;
    out[i] = c;
    i++;
  }
  out[i] = '\0';
}

static int silkNumber(const char* silk) {
  if (!silk || !silk[0]) return -1;
  const char* p = silk;
  if (*p == 'A' || *p == 'D' || *p == 'a' || *p == 'd') p++;
  return atoi(p);
}

// ---- JSON helpers (compact Gson-style, no spaces required) ----
static const char* skipWs(const char* p) {
  while (p && (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n')) p++;
  return p;
}

static const char* skipString(const char* p) {
  if (!p || *p != '"') return p;
  p++;
  while (*p && *p != '"') {
    if (*p == '\\' && p[1]) p += 2;
    else p++;
  }
  if (*p == '"') p++;
  return p;
}

static const char* skipValue(const char* p);

static const char* skipObjectOrArray(const char* p, char open, char close) {
  if (!p || *p != open) return p;
  p++;
  int depth = 1;
  while (*p && depth > 0) {
    if (*p == '"') {
      p = skipString(p);
      continue;
    }
    if (*p == open) depth++;
    else if (*p == close) depth--;
    p++;
  }
  return p;
}

static const char* skipValue(const char* p) {
  p = skipWs(p);
  if (!p || !*p) return p;
  if (*p == '"') return skipString(p);
  if (*p == '{') return skipObjectOrArray(p, '{', '}');
  if (*p == '[') return skipObjectOrArray(p, '[', ']');
  if (*p == 't' || *p == 'f' || *p == 'n') {
    while (*p && *p != ',' && *p != '}' && *p != ']') p++;
    return p;
  }
  // number
  if (*p == '-' || (*p >= '0' && *p <= '9')) {
    p++;
    while (*p && ((*p >= '0' && *p <= '9') || *p == '.' || *p == 'e' || *p == 'E' || *p == '+' || *p == '-')) p++;
    return p;
  }
  return p;
}

/** Find "key" in object starting at '{'; returns pointer after ':' value start, or null. */
static const char* findKey(const char* obj, const char* key) {
  obj = skipWs(obj);
  if (!obj || *obj != '{') return nullptr;
  const char* p = obj + 1;
  size_t klen = strlen(key);
  while (*p && *p != '}') {
    p = skipWs(p);
    if (*p != '"') {
      p = skipValue(p);
      p = skipWs(p);
      if (*p == ',') p++;
      continue;
    }
    const char* ks = p + 1;
    const char* ke = skipString(p);
    size_t len = (ke > ks + 1) ? (size_t)(ke - ks - 1) : 0;
    bool match = (len == klen && strncmp(ks, key, klen) == 0);
    p = skipWs(ke);
    if (*p == ':') p++;
    p = skipWs(p);
    if (match) return p;
    p = skipValue(p);
    p = skipWs(p);
    if (*p == ',') p++;
  }
  return nullptr;
}

static bool parseStringVal(const char* p, char* out, size_t cap) {
  p = skipWs(p);
  if (!p || *p != '"' || cap == 0) return false;
  p++;
  size_t i = 0;
  while (*p && *p != '"' && i + 1 < cap) {
    if (*p == '\\' && p[1]) {
      out[i++] = p[1];
      p += 2;
    } else {
      out[i++] = *p++;
    }
  }
  out[i] = '\0';
  return true;
}

static bool parseIntVal(const char* p, int* out) {
  p = skipWs(p);
  if (!p) return false;
  char* end = nullptr;
  long v = strtol(p, &end, 10);
  if (end == p) return false;
  *out = (int)v;
  return true;
}

static NodeOutType parseOutType(const char* s) {
  if (streqi(s, "on_off")) return OUT_ON_OFF;
  if (streqi(s, "pulse")) return OUT_PULSE;
  if (streqi(s, "blink")) return OUT_BLINK;
  if (streqi(s, "alt_pair")) return OUT_ALT_PAIR;
  if (streqi(s, "fade")) return OUT_FADE;
  if (streqi(s, "servo")) return OUT_SERVO;
  if (streqi(s, "level_crossing")) return OUT_LEVEL_CROSSING;
  if (streqi(s, "digital_signal")) return OUT_DIGITAL_SIGNAL;
  return OUT_UNKNOWN;
}

static NodePinRole parseRole(const char* s) {
  if (streqi(s, "optical")) return ROLE_OPTICAL;
  if (streqi(s, "digital_in")) return ROLE_DIGITAL_IN;
  if (streqi(s, "output")) return ROLE_OUTPUT;
  if (streqi(s, "ref")) return ROLE_REF;
  return ROLE_FREE;
}

static bool directionWireOn(const char* direction) {
  if (!direction || !direction[0]) return true;
  if (startsWithi(direction, "rev")) return false;
  if (streqi(direction, "thrown") || streqi(direction, "off")) return false;
  return true;
}

static bool triggerIsOnish(const NodeTrigger* t) {
  if (t->state[0]) {
    if (streqi(t->state, "off") || streqi(t->state, "closed") || streqi(t->state, "stop")) return false;
    if (streqi(t->state, "on") || streqi(t->state, "open") || streqi(t->state, "pulse")) return true;
    if (startsWithi(t->state, "start")) return true;
  }
  return t->wireOn;
}

static bool triggerIsOpenish(const NodeTrigger* t) {
  if (t->state[0]) {
    if (streqi(t->state, "open")) return true;
    if (streqi(t->state, "closed") || streqi(t->state, "close")) return false;
  }
  return t->wireOn;
}

// ---- HAL helpers ----
static bool pinSupportsPwm(int gpio) {
#if defined(ESP32)
  (void)gpio;
  return true;
#elif defined(__AVR_ATmega2560__) || defined(__AVR_ATmega1280__)
  return gpio == 2 || gpio == 3 || gpio == 4 || gpio == 5 || gpio == 6 || gpio == 7
      || gpio == 8 || gpio == 9 || gpio == 10 || gpio == 11 || gpio == 12
      || gpio == 44 || gpio == 45 || gpio == 46;
#else
  // Nano / Uno PWM
  return gpio == 3 || gpio == 5 || gpio == 6 || gpio == 9 || gpio == 10 || gpio == 11;
#endif
}

static void writeDigital(int gpio, bool on) {
  if (gpio < 0) return;
  digitalWrite(gpio, on ? HIGH : LOW);
}

static void writePwmPercent(int gpio, int pct) {
  if (gpio < 0) return;
  if (pct < 0) pct = 0;
  if (pct > 100) pct = 100;
#if defined(ESP32)
  // 8-bit duty via analogWrite if available
  analogWrite(gpio, (pct * 255) / 100);
#else
  if (pinSupportsPwm(gpio)) {
    analogWrite(gpio, (pct * 255) / 100);
  } else {
    digitalWrite(gpio, pct >= 50 ? HIGH : LOW);
  }
#endif
}

static int8_t allocServo(int gpio) {
  if (gpio < 0 || gServoCount >= NODE_MAX_SERVOS) return -1;
  for (uint8_t i = 0; i < gServoCount; i++) {
    // reuse if same pin already attached later
  }
  int8_t ix = (int8_t)gServoCount++;
  gServos[ix].attach(gpio);
  return ix;
}

static void writeServoDeg(int8_t servoIx, int deg) {
  if (servoIx < 0 || servoIx >= (int)gServoCount) return;
  if (deg < 0) deg = 0;
  if (deg > 180) deg = 180;
  gServos[servoIx].write(deg);
}

static void detachAllServos() {
  for (uint8_t i = 0; i < gServoCount; i++) {
    gServos[i].detach();
  }
  gServoCount = 0;
}

/** Angles preserved across CFG rebuild so editing a position does not slam servos to 0°. */
static int gSnapGpio[NODE_MAX_SERVOS];
static int gSnapDeg[NODE_MAX_SERVOS];
static uint8_t gSnapCount = 0;

// ---- persist (JSON body only; parse buffer is caller-provided) ----
static bool loadCfgIntoBuffer(char* buf, size_t cap, uint16_t* outLen) {
  *outLen = 0;
  if (cap < 2) return false;
#if defined(ESP32)
  if (!gPrefs.begin("panelsdcc", true)) return false;
  String s = gPrefs.getString("cfg", "");
  gPrefs.end();
  if (s.length() == 0 || (size_t)s.length() + 1 > cap || s.length() >= NODE_MAX_CFG) return false;
  strCopyCap(buf, cap, s.c_str());
  *outLen = (uint16_t)strlen(buf);
  return *outLen > 0;
#else
  if (EEPROM.read(0) != EEPROM_MAGIC[0] || EEPROM.read(1) != EEPROM_MAGIC[1]
      || EEPROM.read(2) != EEPROM_MAGIC[2] || EEPROM.read(3) != EEPROM_MAGIC[3]) {
    return false;
  }
  if (EEPROM.read(4) != CFG_VERSION) return false;
  uint16_t len = (uint16_t)EEPROM.read(5) | ((uint16_t)EEPROM.read(6) << 8);
  if (len == 0 || len >= NODE_MAX_CFG || len + 1 > cap) return false;
  uint8_t sum = 0;
  for (uint16_t i = 0; i < len; i++) {
    uint8_t b = EEPROM.read(7 + i);
    buf[i] = (char)b;
    sum ^= b;
  }
  buf[len] = '\0';
  uint8_t expect = EEPROM.read(7 + len);
  if (sum != expect) {
    buf[0] = '\0';
    return false;
  }
  *outLen = len;
  return true;
#endif
}

static void saveCfgToStorage(const char* json, uint16_t len) {
  if (!json || len == 0 || len >= NODE_MAX_CFG) return;
#if defined(ESP32)
  if (!gPrefs.begin("panelsdcc", false)) return;
  gPrefs.putString("cfg", json);
  gPrefs.end();
#else
  EEPROM.update(0, EEPROM_MAGIC[0]);
  EEPROM.update(1, EEPROM_MAGIC[1]);
  EEPROM.update(2, EEPROM_MAGIC[2]);
  EEPROM.update(3, EEPROM_MAGIC[3]);
  EEPROM.update(4, CFG_VERSION);
  EEPROM.update(5, (uint8_t)(len & 0xFF));
  EEPROM.update(6, (uint8_t)((len >> 8) & 0xFF));
  uint8_t sum = 0;
  for (uint16_t i = 0; i < len; i++) {
    uint8_t b = (uint8_t)json[i];
    EEPROM.update(7 + i, b);
    sum ^= b;
  }
  EEPROM.update(7 + len, sum);
#endif
  gCfgLen = len;
  gCfgValid = true;
}

static void clearCfgStorage() {
#if defined(ESP32)
  if (!gPrefs.begin("panelsdcc", false)) return;
  gPrefs.remove("cfg");
  gPrefs.end();
#else
  EEPROM.update(0, 0);
  EEPROM.update(1, 0);
  EEPROM.update(2, 0);
  EEPROM.update(3, 0);
#endif
  gCfgLen = 0;
  gCfgValid = false;
}

const char* nodeImageVersion() {
  return IMAGE_VERSION_STR;
}

// ---- configuration access ----
// Baked images read these fields from program storage. Generic images read SRAM copies
// that the CFG parser fills. Callers never copy a whole output (the trigger list is large).

static int outInt(uint8_t i, size_t off) {
  int v = 0;
#if defined(NODE_BAKED_CONFIG)
  memcpy_P(&v, ((const uint8_t*)&gBakedOutputs[i]) + off, sizeof(v));
#else
  memcpy(&v, ((const uint8_t*)&gOutCfg[i]) + off, sizeof(v));
#endif
  return v;
}

static NodePinRole pinRoleAt(uint8_t i) {
#if defined(NODE_BAKED_CONFIG)
  return (NodePinRole)pgm_read_byte(&gBakedPins[i].role);
#else
  return gPinCfg[i].role;
#endif
}

static uint8_t pinPctAt(uint8_t i) {
#if defined(NODE_BAKED_CONFIG)
  return pgm_read_byte(&gBakedPins[i].sensitivityPct);
#else
  return gPinCfg[i].sensitivityPct;
#endif
}

static int pinGpioAt(uint8_t i) {
  int v = 0;
#if defined(NODE_BAKED_CONFIG)
  memcpy_P(&v, &gBakedPins[i].gpio, sizeof(v));
#else
  v = gPinCfg[i].gpio;
#endif
  return v;
}

static int pinRefAt(uint8_t i) {
  int v = 0;
#if defined(NODE_BAKED_CONFIG)
  memcpy_P(&v, &gBakedPins[i].refGpio, sizeof(v));
#else
  v = gPinCfg[i].refGpio;
#endif
  return v;
}

static void pinLabelAt(uint8_t i, char* dest) {
#if defined(NODE_BAKED_CONFIG)
  memcpy_P(dest, gBakedPins[i].label, 5);
#else
  memcpy(dest, gPinCfg[i].label, 5);
#endif
  dest[4] = '\0';
}

static NodeOutType outTypeAt(uint8_t i) {
#if defined(NODE_BAKED_CONFIG)
  return (NodeOutType)pgm_read_byte(&gBakedOutputs[i].type);
#else
  return gOutCfg[i].type;
#endif
}

static uint8_t outPinCountAt(uint8_t i) {
#if defined(NODE_BAKED_CONFIG)
  return pgm_read_byte(&gBakedOutputs[i].pinCount);
#else
  return gOutCfg[i].pinCount;
#endif
}

static uint8_t outTrigCountAt(uint8_t i) {
#if defined(NODE_BAKED_CONFIG)
  return pgm_read_byte(&gBakedOutputs[i].triggerCount);
#else
  return gOutCfg[i].triggerCount;
#endif
}

static int outGpioAt(uint8_t i, uint8_t p) {
  return outInt(i, offsetof(NodeOutputConfig, gpio) + (size_t)p * sizeof(int));
}

static void outTrigAt(uint8_t i, uint8_t t, NodeTrigger* dest) {
#if defined(NODE_BAKED_CONFIG)
  memcpy_P(dest, &gBakedOutputs[i].triggers[t], sizeof(NodeTrigger));
#else
  *dest = gOutCfg[i].triggers[t];
#endif
}

#define OI(i, field) outInt((i), offsetof(NodeOutputConfig, field))

static void snapshotServoAngles() {
  gSnapCount = 0;
  for (uint8_t i = 0; i < gOutputCount && gSnapCount < NODE_MAX_SERVOS; i++) {
    NodeOutType type = outTypeAt(i);
    if (type != OUT_SERVO && type != OUT_LEVEL_CROSSING) continue;
    uint8_t n = outPinCountAt(i);
    uint8_t j0 = (type == OUT_LEVEL_CROSSING) ? 3 : 0;
    for (uint8_t j = j0; j < n && gSnapCount < NODE_MAX_SERVOS; j++) {
      if (gOutRt[i].servoIx[j] < 0) continue;
      int g = outGpioAt(i, j);
      if (g < 0) continue;
      gSnapGpio[gSnapCount] = g;
      if (type == OUT_LEVEL_CROSSING && j >= 4) {
        gSnapDeg[gSnapCount] = gOutRt[i].barrier2CurrentDeg;
      } else {
        gSnapDeg[gSnapCount] = gOutRt[i].currentDeg;
      }
      gSnapCount++;
    }
  }
}

static int snappedDegForGpio(int gpio) {
  for (uint8_t i = 0; i < gSnapCount; i++) {
    if (gSnapGpio[i] == gpio) return gSnapDeg[i];
  }
  return -1;
}

/** Hold a servo at deg so tickOutputs does not animate it away. */
static void holdServoOutput(uint8_t oi, int deg) {
  if (deg < 0) deg = 0;
  if (deg > 180) deg = 180;
  NodeOutputRuntime* r = &gOutRt[oi];
  r->currentDeg = deg;
  r->targetDeg = deg;
  r->fadeFromBright = deg;
  r->fadeStartMs = millis();
  r->active = false;
}

/** Move toward deg at configured travel speed (same path as DCC servo triggers). */
static void startServoTravel(uint8_t oi, int deg) {
  if (deg < 0) deg = 0;
  if (deg > 180) deg = 180;
  NodeOutputRuntime* r = &gOutRt[oi];
  r->targetDeg = deg;
  r->fadeFromBright = r->currentDeg;
  r->fadeStartMs = millis();
  r->active = true;
}

static void tickServoTravel(uint8_t oi, unsigned long now, int travelMs) {
  NodeOutputRuntime* r = &gOutRt[oi];
  int dur = travelMs;
  if (dur < 1) dur = 1;
  int from = r->fadeFromBright;
  int delta = abs(r->targetDeg - from);
  int need = (int)((long)dur * delta / 180L);
  if (need < 1) need = 1;
  float t = (float)(now - r->fadeStartMs) / (float)need;
  if (t >= 1.0f) {
    r->currentDeg = r->targetDeg;
    r->active = false;
  } else if (t > 0) {
    r->currentDeg = (int)lround(from + (r->targetDeg - from) * t);
  }
}

static void clearRuntime() {
  detachAllServos();
  gRoleCount = 0;
  gOutputCount = 0;
  gRefGpio = -1;
  memset(gPinRt, 0, sizeof(gPinRt));
  memset(gOutRt, 0, sizeof(gOutRt));
  for (uint8_t i = 0; i < NODE_MAX_OUTPUTS; i++) {
    for (uint8_t p = 0; p < NODE_MAX_OUT_PINS; p++) gOutRt[i].servoIx[p] = -1;
  }
#if !defined(NODE_BAKED_CONFIG)
  memset(gPinCfg, 0, sizeof(gPinCfg));
  memset(gOutCfg, 0, sizeof(gOutCfg));
#endif
}

static bool roleIsSensorAt(uint8_t i) {
  NodePinRole role = pinRoleAt(i);
  return role == ROLE_OPTICAL || role == ROLE_DIGITAL_IN;
}

static bool opticalActiveAt(uint8_t i) {
  if (pinRoleAt(i) != ROLE_OPTICAL) return false;
  int gpio = pinGpioAt(i);
  if (gpio < 0) return false;
  int refPin = pinRefAt(i);
  int refGpio = refPin >= 0 ? refPin : gRefGpio;
  int ref = (refGpio >= 0) ? analogRead(refGpio) : 0;
  int val = analogRead(gpio);
  int delta = val - ref;
  if (delta <= 0) return false;
  uint8_t pct = pinPctAt(i);
  if (!pct) pct = NODE_DEFAULT_SENSITIVITY_PCT;
  long need = ((long)ref * (long)pct) / 100L;
  if (need < 1) need = 1;
  return delta > (int)need;
}

static void applySensorPinModes() {
  for (uint8_t i = 0; i < gRoleCount; i++) {
    if (pinRoleAt(i) == ROLE_DIGITAL_IN) {
      int gpio = pinGpioAt(i);
      if (gpio >= 0) pinMode(gpio, INPUT_PULLUP);
    }
  }
}

static void applyOutputPinModes(uint8_t oi) {
  NodeOutputRuntime* r = &gOutRt[oi];
  NodeOutType type = outTypeAt(oi);
  uint8_t n = outPinCountAt(oi);
  for (uint8_t i = 0; i < n; i++) {
    int g = outGpioAt(oi, i);
    if (g < 0) continue;
    if (type == OUT_SERVO || (type == OUT_LEVEL_CROSSING && i >= 3)) {
      r->servoIx[i] = allocServo(g);
    } else {
      pinMode(g, OUTPUT);
      writeDigital(g, false);
      r->servoIx[i] = -1;
    }
  }
}

static void finishOutput(uint8_t oi) {
  NodeOutputRuntime* r = &gOutRt[oi];
  applyOutputPinModes(oi);
  if (outTypeAt(oi) == OUT_SERVO) {
    int g = outGpioAt(oi, 0);
    int deg = snappedDegForGpio(g);
    if (deg < 0) deg = 90;
    holdServoOutput(oi, deg);
    if (r->servoIx[0] >= 0) writeServoDeg(r->servoIx[0], deg);
  } else if (outTypeAt(oi) == OUT_LEVEL_CROSSING) {
    r->lcPhase = LC_IDLE_OPEN;
    r->lcManualWhich = -1;
    int snap1 = snappedDegForGpio(outGpioAt(oi, 3));
    int snap2 = snappedDegForGpio(outGpioAt(oi, 4));
    r->currentDeg = snap1 >= 0 ? snap1 : OI(oi, barrier1OpenDeg);
    r->targetDeg = r->currentDeg;
    r->fadeFromBright = r->currentDeg;
    r->barrier2CurrentDeg = snap2 >= 0 ? snap2 : OI(oi, barrier2OpenDeg);
    r->barrier2TargetDeg = r->barrier2CurrentDeg;
    r->barrier2FadeFrom = r->barrier2CurrentDeg;
    r->active = false;
    if (r->servoIx[3] >= 0) writeServoDeg(r->servoIx[3], r->currentDeg);
    if (outPinCountAt(oi) > 4 && r->servoIx[4] >= 0) writeServoDeg(r->servoIx[4], r->barrier2CurrentDeg);
  }
}

#if !defined(NODE_BAKED_CONFIG)

static NodePinConfig* findRoleByLabel(const char* silk) {
  for (uint8_t i = 0; i < gRoleCount; i++) {
    if (streqi(gPinCfg[i].label, silk)) return &gPinCfg[i];
  }
  return nullptr;
}

static void addOpticalMeta(const char* label, int refGpio, uint8_t pct) {
  char silk[5];
  normalizeSilk(silk, sizeof(silk), label);
  NodePinConfig* r = findRoleByLabel(silk);
  if (!r) return;
  if (pct < 1) pct = NODE_DEFAULT_SENSITIVITY_PCT;
  if (pct > 100) pct = 100;
  r->sensitivityPct = pct;
  r->refGpio = refGpio;
}

static void addRole(const char* label, NodePinRole role) {
  if (gRoleCount >= NODE_MAX_PIN_ROLES) return;
  char silk[5];
  normalizeSilk(silk, sizeof(silk), label);
  int gpio = boardSilkToGpio(silk);
  if (gpio < 0 && role != ROLE_FREE) return;
  NodePinConfig* e = &gPinCfg[gRoleCount];
  NodePinRuntime* rt = &gPinRt[gRoleCount];
  gRoleCount++;
  strCopyCap(e->label, sizeof(e->label), silk);
  e->role = role;
  e->sensitivityPct = 0;
  e->gpio = gpio;
  e->refGpio = -1;
  rt->reportedActive = false;
  rt->clearSinceMs = 0;
  if (role == ROLE_REF) gRefGpio = gpio;
}

static void parseParams(NodeOutputConfig* o, const char* paramsObj) {
  o->pulseMs = 250;
  o->onMs = 500;
  o->offMs = 500;
  o->fadeMs = 800;
  o->travelMs = 1000;
  o->warningMs = 3000;
  o->wigwagOnMs = 400;
  o->wigwagOffMs = 400;
  o->barrier1TravelMs = 2500;
  o->barrier1OpenDeg = 90;
  o->barrier1ClosedDeg = 0;
  o->barrier2TravelMs = 2500;
  o->barrier2OpenDeg = 90;
  o->barrier2ClosedDeg = 0;
  o->flashMs = 500;
  if (!paramsObj) return;
  const char* v;
  int n;
  if ((v = findKey(paramsObj, "pulseMs")) && parseIntVal(v, &n)) o->pulseMs = n;
  if ((v = findKey(paramsObj, "onMs")) && parseIntVal(v, &n)) o->onMs = n;
  if ((v = findKey(paramsObj, "offMs")) && parseIntVal(v, &n)) o->offMs = n;
  if ((v = findKey(paramsObj, "fadeMs")) && parseIntVal(v, &n)) o->fadeMs = n;
  if ((v = findKey(paramsObj, "travelMs")) && parseIntVal(v, &n)) o->travelMs = n;
  if ((v = findKey(paramsObj, "warningMs")) && parseIntVal(v, &n)) o->warningMs = n;
  if ((v = findKey(paramsObj, "wigwagOnMs")) && parseIntVal(v, &n)) o->wigwagOnMs = n;
  if ((v = findKey(paramsObj, "wigwagOffMs")) && parseIntVal(v, &n)) o->wigwagOffMs = n;
  // Legacy shared keys apply to both barriers; per-barrier keys override.
  if ((v = findKey(paramsObj, "barrierTravelMs")) && parseIntVal(v, &n)) {
    o->barrier1TravelMs = n;
    o->barrier2TravelMs = n;
  }
  if ((v = findKey(paramsObj, "barrierOpen_deg")) && parseIntVal(v, &n)) {
    o->barrier1OpenDeg = n;
    o->barrier2OpenDeg = n;
  }
  if ((v = findKey(paramsObj, "barrierClosed_deg")) && parseIntVal(v, &n)) {
    o->barrier1ClosedDeg = n;
    o->barrier2ClosedDeg = n;
  }
  if ((v = findKey(paramsObj, "barrier1TravelMs")) && parseIntVal(v, &n)) o->barrier1TravelMs = n;
  if ((v = findKey(paramsObj, "barrier1Open_deg")) && parseIntVal(v, &n)) o->barrier1OpenDeg = n;
  if ((v = findKey(paramsObj, "barrier1Closed_deg")) && parseIntVal(v, &n)) o->barrier1ClosedDeg = n;
  if ((v = findKey(paramsObj, "barrier2TravelMs")) && parseIntVal(v, &n)) o->barrier2TravelMs = n;
  if ((v = findKey(paramsObj, "barrier2Open_deg")) && parseIntVal(v, &n)) o->barrier2OpenDeg = n;
  if ((v = findKey(paramsObj, "barrier2Closed_deg")) && parseIntVal(v, &n)) o->barrier2ClosedDeg = n;
  if ((v = findKey(paramsObj, "flashMs")) && parseIntVal(v, &n)) o->flashMs = n;
}

static void parseTriggerArray(NodeOutputConfig* o, const char* arr) {
  o->triggerCount = 0;
  arr = skipWs(arr);
  if (!arr || *arr != '[') return;
  const char* p = arr + 1;
  while (*p && *p != ']' && o->triggerCount < NODE_MAX_TRIGGERS) {
    p = skipWs(p);
    if (*p != '{') {
      p = skipValue(p);
      p = skipWs(p);
      if (*p == ',') p++;
      continue;
    }
    NodeTrigger* t = &o->triggers[o->triggerCount];
    memset(t, 0, sizeof(*t));
    t->wireOn = true;
    t->deg = 0;
    t->brightness = 0;
    const char* v;
    int n;
    char buf[16];
    if ((v = findKey(p, "dcc")) && parseIntVal(v, &n)) t->dcc = n;
    if ((v = findKey(p, "direction")) && parseStringVal(v, buf, sizeof(buf))) {
      t->wireOn = directionWireOn(buf);
    }
    if ((v = findKey(p, "state")) && parseStringVal(v, t->state, sizeof(t->state))) {
      // keep
    }
    if ((v = findKey(p, "deg")) && parseIntVal(v, &n)) {
      t->deg = n;
      t->hasDeg = true;
    }
    if ((v = findKey(p, "brightness")) && parseIntVal(v, &n)) {
      t->brightness = n;
      t->hasBrightness = true;
    }
    if ((v = findKey(p, "on")) && *skipWs(v) == '[') {
      const char* a = skipWs(v) + 1;
      while (*a && *a != ']') {
        a = skipWs(a);
        if ((*a >= '0' && *a <= '9') || *a == '-') {
          int idx = 0;
          parseIntVal(a, &idx);
          if (idx >= 0 && idx < 8) t->onMask |= (uint8_t)(1u << idx);
          while (*a && *a != ',' && *a != ']') a++;
        }
        if (*a == ',') a++;
      }
    }
    if ((v = findKey(p, "flashing")) && *skipWs(v) == '[') {
      const char* a = skipWs(v) + 1;
      while (*a && *a != ']') {
        a = skipWs(a);
        if ((*a >= '0' && *a <= '9') || *a == '-') {
          int idx = 0;
          parseIntVal(a, &idx);
          if (idx >= 0 && idx < 8) {
            t->flashMask |= (uint8_t)(1u << idx);
            t->onMask |= (uint8_t)(1u << idx);
          }
          while (*a && *a != ',' && *a != ']') a++;
        }
        if (*a == ',') a++;
      }
    }
    o->triggerCount++;
    p = skipValue(p);
    p = skipWs(p);
    if (*p == ',') p++;
  }
}

static void parsePinsArray(const char* arr) {
  arr = skipWs(arr);
  if (!arr || *arr != '[') return;
  const char* p = arr + 1;
  while (*p && *p != ']') {
    p = skipWs(p);
    if (*p != '{') {
      p = skipValue(p);
      p = skipWs(p);
      if (*p == ',') p++;
      continue;
    }
    char label[8] = {0};
    char role[16] = {0};
    const char* v;
    if ((v = findKey(p, "label"))) parseStringVal(v, label, sizeof(label));
    if ((v = findKey(p, "role"))) parseStringVal(v, role, sizeof(role));
    if (label[0]) addRole(label, parseRole(role));
    p = skipValue(p);
    p = skipWs(p);
    if (*p == ',') p++;
  }
}

static void parseOutputsArray(const char* arr) {
  arr = skipWs(arr);
  if (!arr || *arr != '[') return;
  const char* p = arr + 1;
  while (*p && *p != ']' && gOutputCount < NODE_MAX_OUTPUTS) {
    p = skipWs(p);
    if (*p != '{') {
      p = skipValue(p);
      p = skipWs(p);
      if (*p == ',') p++;
      continue;
    }
    NodeOutputConfig* o = &gOutCfg[gOutputCount];
    NodeOutputRuntime* rt = &gOutRt[gOutputCount];
    memset(o, 0, sizeof(*o));
    memset(rt, 0, sizeof(*rt));
    for (uint8_t i = 0; i < NODE_MAX_OUT_PINS; i++) rt->servoIx[i] = -1;
    char typeBuf[24] = {0};
    const char* v;
    if ((v = findKey(p, "type"))) parseStringVal(v, typeBuf, sizeof(typeBuf));
    o->type = parseOutType(typeBuf);
    parseParams(o, findKey(p, "params"));
    if ((v = findKey(p, "pins")) && *skipWs(v) == '[') {
      const char* a = skipWs(v) + 1;
      while (*a && *a != ']' && o->pinCount < NODE_MAX_OUT_PINS) {
        a = skipWs(a);
        if (*a == '"') {
          char lab[8];
          parseStringVal(a, lab, sizeof(lab));
          normalizeSilk(o->pinLabel[o->pinCount], sizeof(o->pinLabel[0]), lab);
          o->gpio[o->pinCount] = boardSilkToGpio(o->pinLabel[o->pinCount]);
          o->pinCount++;
          a = skipString(a);
        } else {
          a = skipValue(a);
        }
        a = skipWs(a);
        if (*a == ',') a++;
      }
    }
    parseTriggerArray(o, findKey(p, "triggers"));
    if (o->triggerCount == 0) {
      int dcc = 0;
      if ((v = findKey(p, "dcc")) && parseIntVal(v, &dcc) && dcc > 0) {
        o->triggers[0].dcc = dcc;
        o->triggers[0].wireOn = true;
        o->triggers[1].dcc = dcc;
        o->triggers[1].wireOn = false;
        strCopyCap(o->triggers[0].state, sizeof(o->triggers[0].state), "on");
        strCopyCap(o->triggers[1].state, sizeof(o->triggers[1].state), "off");
        o->triggerCount = 2;
      }
    }
    if (o->type != OUT_UNKNOWN && o->pinCount > 0) {
      applyOutputPinModes(gOutputCount);
      if (o->type == OUT_SERVO) {
        int deg = snappedDegForGpio(o->gpio[0]);
        if (deg < 0) {
          deg = 90;
          for (uint8_t ti = 0; ti < o->triggerCount; ti++) {
            if (o->triggers[ti].hasDeg) {
              deg = o->triggers[ti].deg;
              break;
            }
          }
        }
        holdServoOutput(gOutputCount, deg);
        if (rt->servoIx[0] >= 0) writeServoDeg(rt->servoIx[0], deg);
      } else if (o->type == OUT_LEVEL_CROSSING) {
        int snap1 = o->pinCount > 3 ? snappedDegForGpio(o->gpio[3]) : -1;
        int snap2 = o->pinCount > 4 ? snappedDegForGpio(o->gpio[4]) : -1;
        rt->lcPhase = LC_IDLE_OPEN;
        rt->lcManualWhich = -1;
        rt->currentDeg = snap1 >= 0 ? snap1 : o->barrier1OpenDeg;
        rt->targetDeg = rt->currentDeg;
        rt->fadeFromBright = rt->currentDeg;
        rt->barrier2CurrentDeg = snap2 >= 0 ? snap2 : o->barrier2OpenDeg;
        rt->barrier2TargetDeg = rt->barrier2CurrentDeg;
        rt->barrier2FadeFrom = rt->barrier2CurrentDeg;
        rt->active = false;
        if (rt->servoIx[3] >= 0) writeServoDeg(rt->servoIx[3], rt->currentDeg);
        if (o->pinCount > 4 && rt->servoIx[4] >= 0) writeServoDeg(rt->servoIx[4], rt->barrier2CurrentDeg);
      }
      gOutputCount++;
    }
    p = skipValue(p);
    p = skipWs(p);
    if (*p == ',') p++;
  }
}

static bool parseOpticalDistricts(const char* arr) {
  arr = skipWs(arr);
  if (!arr || *arr != '[') return false;
  const char* p = arr + 1;
  uint8_t districtCount = 0;
  bool any = false;
  while (*p && *p != ']' && districtCount < NODE_MAX_DISTRICTS) {
    p = skipWs(p);
    if (*p != '{') {
      p = skipValue(p);
      p = skipWs(p);
      if (*p == ',') p++;
      continue;
    }
    char refLab[8] = {0};
    int pct = NODE_DEFAULT_SENSITIVITY_PCT;
    const char* v;
    int n;
    if ((v = findKey(p, "ref"))) parseStringVal(v, refLab, sizeof(refLab));
    if ((v = findKey(p, "sensitivityPct")) && parseIntVal(v, &n)) pct = n;
    if (pct < 1) pct = NODE_DEFAULT_SENSITIVITY_PCT;
    if (pct > 100) pct = 100;
    if (!refLab[0]) {
      p = skipValue(p);
      p = skipWs(p);
      if (*p == ',') p++;
      continue;
    }
    char refNorm[5];
    normalizeSilk(refNorm, sizeof(refNorm), refLab);
    int refGpio = boardSilkToGpio(refNorm);
    addRole(refNorm, ROLE_REF);
    if (refGpio >= 0) gRefGpio = refGpio;
    if ((v = findKey(p, "sensors")) && *skipWs(v) == '[') {
      const char* a = skipWs(v) + 1;
      while (*a && *a != ']') {
        a = skipWs(a);
        if (*a == '"') {
          char lab[8];
          parseStringVal(a, lab, sizeof(lab));
          char sn[5];
          normalizeSilk(sn, sizeof(sn), lab);
          if (sn[0] && !streqi(sn, refNorm)) {
            addRole(sn, ROLE_OPTICAL);
            addOpticalMeta(sn, refGpio, (uint8_t)pct);
            any = true;
          }
          a = skipString(a);
        } else {
          a = skipValue(a);
        }
        a = skipWs(a);
        if (*a == ',') a++;
      }
    }
    districtCount++;
    p = skipValue(p);
    p = skipWs(p);
    if (*p == ',') p++;
  }
  return any || districtCount > 0;
}

static void stripOpticalAndRefRoles() {
  uint8_t w = 0;
  for (uint8_t i = 0; i < gRoleCount; i++) {
    if (gPinCfg[i].role == ROLE_OPTICAL || gPinCfg[i].role == ROLE_REF) continue;
    if (w != i) {
      gPinCfg[w] = gPinCfg[i];
      gPinRt[w] = gPinRt[i];
    }
    w++;
  }
  gRoleCount = w;
  gRefGpio = -1;
}

static void rebuildFromCfgBuffer(const char* json) {
  snapshotServoAngles();
  clearRuntime();
  if (!json || !json[0]) {
    boardLoadDefaultPinRoles(gPinCfg, &gRoleCount, &gRefGpio);
    applySensorPinModes();
    return;
  }
  const char* root = skipWs(json);
  const char* pins = findKey(root, "pins");
  if (pins && *skipWs(pins) == '[') {
    parsePinsArray(pins);
  } else {
    boardLoadDefaultPinRoles(gPinCfg, &gRoleCount, &gRefGpio);
  }

  const char* districts = findKey(root, "opticalDistricts");
  bool usedDistricts = false;
  if (districts && *skipWs(districts) == '[') {
    const char* after = skipWs(skipWs(districts) + 1);
    bool emptyArr = after && *after == ']';
    if (!emptyArr) {
      stripOpticalAndRefRoles();
      usedDistricts = parseOpticalDistricts(districts);
    }
  }

  if (!usedDistricts) {
    if (gRefGpio < 0) {
      for (uint8_t i = 0; i < gRoleCount; i++) {
        if (gPinCfg[i].role == ROLE_OPTICAL) {
          gRefGpio = boardAmbientRefGpio();
          break;
        }
      }
    }
    for (uint8_t i = 0; i < gRoleCount; i++) {
      if (gPinCfg[i].role == ROLE_OPTICAL) {
        addOpticalMeta(gPinCfg[i].label, gRefGpio, NODE_DEFAULT_SENSITIVITY_PCT);
      }
    }
  }

  applySensorPinModes();
  const char* outs = findKey(root, "outputs");
  if (outs) parseOutputsArray(outs);
}

#endif

bool nodeApplyCfgJson(const char* json) {
#if defined(NODE_BAKED_CONFIG)
  (void)json;
  return false;
#else
  if (!json) return false;
  while (*json == ' ' || *json == '\t') json++;
  size_t n = strlen(json);
  if (n == 0 || n >= NODE_MAX_CFG || n + 1 > NODE_MAX_LINE) return false;
  rebuildFromCfgBuffer(json);
  saveCfgToStorage(json, (uint16_t)n);
  return true;
#endif
}

#if defined(NODE_BAKED_CONFIG)

static void printFlashJson(Stream* s, const char* flash, uint8_t cap) {
  s->print('"');
  for (uint8_t i = 0; i < cap; i++) {
    char c = (char)pgm_read_byte(flash + i);
    if (c == '\0') break;
    if (c == '"' || c == '\\') s->print('\\');
    s->print(c);
  }
  s->print('"');
}

static bool flashBlank(const char* flash) {
  return pgm_read_byte(flash) == 0;
}

static void printSilkJson(Stream* s, const char* ram) {
  s->print('"');
  s->print(ram);
  s->print('"');
}

static void printIntField(Stream* s, bool* first, const __FlashStringHelper* key, int value) {
  if (!*first) s->print(',');
  *first = false;
  s->print('"');
  s->print(key);
  s->print(F("\":"));
  s->print(value);
}

static void printOutType(Stream* s, NodeOutType type) {
  switch (type) {
    case OUT_ON_OFF: s->print(F("on_off")); break;
    case OUT_PULSE: s->print(F("pulse")); break;
    case OUT_BLINK: s->print(F("blink")); break;
    case OUT_ALT_PAIR: s->print(F("alt_pair")); break;
    case OUT_FADE: s->print(F("fade")); break;
    case OUT_SERVO: s->print(F("servo")); break;
    case OUT_LEVEL_CROSSING: s->print(F("level_crossing")); break;
    case OUT_DIGITAL_SIGNAL: s->print(F("digital_signal")); break;
    default: s->print(F("unknown")); break;
  }
}

static void printRole(Stream* s, NodePinRole role) {
  switch (role) {
    case ROLE_OPTICAL: s->print(F("optical")); break;
    case ROLE_DIGITAL_IN: s->print(F("digital_in")); break;
    case ROLE_OUTPUT: s->print(F("output")); break;
    case ROLE_REF: s->print(F("ref")); break;
    default: s->print(F("free")); break;
  }
}

static void printBakedParams(Stream* s, uint8_t oi, NodeOutType type) {
  bool first = true;
  if (type == OUT_PULSE) printIntField(s, &first, F("pulseMs"), OI(oi, pulseMs));
  if (type == OUT_BLINK || type == OUT_ALT_PAIR) {
    printIntField(s, &first, F("onMs"), OI(oi, onMs));
    printIntField(s, &first, F("offMs"), OI(oi, offMs));
  }
  if (type == OUT_FADE) printIntField(s, &first, F("fadeMs"), OI(oi, fadeMs));
  if (type == OUT_SERVO) printIntField(s, &first, F("travelMs"), OI(oi, travelMs));
  if (type == OUT_DIGITAL_SIGNAL) printIntField(s, &first, F("flashMs"), OI(oi, flashMs));
  if (type == OUT_LEVEL_CROSSING) {
    printIntField(s, &first, F("warningMs"), OI(oi, warningMs));
    printIntField(s, &first, F("wigwagOnMs"), OI(oi, wigwagOnMs));
    printIntField(s, &first, F("wigwagOffMs"), OI(oi, wigwagOffMs));
    printIntField(s, &first, F("barrier1TravelMs"), OI(oi, barrier1TravelMs));
    printIntField(s, &first, F("barrier1Open_deg"), OI(oi, barrier1OpenDeg));
    printIntField(s, &first, F("barrier1Closed_deg"), OI(oi, barrier1ClosedDeg));
    printIntField(s, &first, F("barrier2TravelMs"), OI(oi, barrier2TravelMs));
    printIntField(s, &first, F("barrier2Open_deg"), OI(oi, barrier2OpenDeg));
    printIntField(s, &first, F("barrier2Closed_deg"), OI(oi, barrier2ClosedDeg));
    if (!first) s->print(',');
    first = false;
    s->print(F("\"feedbackVirtual\":"));
    printFlashJson(s, gBakedMeta.feedbackVirtual[oi], 7);
    int active = 1;
    memcpy_P(&active, &gBakedMeta.feedbackActiveState[oi], sizeof(active));
    printIntField(s, &first, F("feedbackActiveState"), active);
  }
}

static void printMaskList(Stream* s, uint8_t mask, uint8_t skip, uint8_t n) {
  s->print('[');
  bool first = true;
  uint8_t limit = n < 8 ? n : 8;
  for (uint8_t i = 0; i < limit; i++) {
    if ((mask & (1u << i)) == 0) continue;
    if ((skip & (1u << i)) != 0) continue;
    if (!first) s->print(',');
    first = false;
    s->print((int)i);
  }
  s->print(']');
}

static void printBakedTrigger(Stream* s, uint8_t oi, uint8_t ti, const NodeTrigger* tr, NodeOutType type, uint8_t pinCount) {
  s->print(F("{\"dcc\":"));
  s->print(tr->dcc);
  s->print(F(",\"direction\":"));
  s->print(tr->wireOn ? F("\"Forward\"") : F("\"Reverse\""));
  if (!flashBlank(gBakedMeta.triggerLabel[oi][ti])) {
    s->print(F(",\"label\":"));
    printFlashJson(s, gBakedMeta.triggerLabel[oi][ti], 40);
  }
  if (tr->state[0]) {
    s->print(F(",\"state\":\""));
    s->print(tr->state);
    s->print('"');
  }
  if (tr->hasDeg) {
    s->print(F(",\"deg\":"));
    s->print(tr->deg);
  }
  if (tr->hasBrightness) {
    s->print(F(",\"brightness\":"));
    s->print(tr->brightness);
  }
  if (type == OUT_DIGITAL_SIGNAL) {
    s->print(F(",\"on\":"));
    printMaskList(s, tr->onMask, tr->flashMask, pinCount);
    s->print(F(",\"flashing\":"));
    printMaskList(s, tr->flashMask, 0, pinCount);
  }
  s->print('}');
}

static void printBakedOutput(Stream* s, uint8_t oi) {
  NodeOutType type = outTypeAt(oi);
  uint8_t n = outPinCountAt(oi);
  s->print(F("{\"name\":"));
  if (flashBlank(gBakedMeta.outputName[oi])) {
    s->print(F("\"Output "));
    s->print((int)oi + 1);
    s->print('"');
  } else {
    printFlashJson(s, gBakedMeta.outputName[oi], 48);
  }
  s->print(F(",\"type\":\""));
  printOutType(s, type);
  s->print(F("\",\"pins\":["));
  for (uint8_t p = 0; p < n; p++) {
    if (p) s->print(',');
    char lab[5];
    memcpy_P(lab, gBakedOutputs[oi].pinLabel[p], 5);
    lab[4] = '\0';
    printSilkJson(s, lab);
  }
  s->print(']');
  if (type == OUT_DIGITAL_SIGNAL) {
    s->print(F(",\"pinNames\":["));
    for (uint8_t p = 0; p < n; p++) {
      if (p) s->print(',');
      printFlashJson(s, gBakedMeta.pinName[oi][p], 24);
    }
    s->print(']');
  }
  s->print(F(",\"params\":{"));
  printBakedParams(s, oi, type);
  s->print(F("},\"triggers\":["));
  uint8_t tn = outTrigCountAt(oi);
  for (uint8_t t = 0; t < tn; t++) {
    if (t) s->print(',');
    NodeTrigger tr;
    outTrigAt(oi, t, &tr);
    printBakedTrigger(s, oi, t, &tr, type, n);
  }
  s->print(F("]}"));
}

static void printBakedCfg(Stream* s) {
  s->print('{');
  s->print(F("\"boardType\":\""));
  const char* type = boardType();
  if (type) s->print(type);
  s->print('"');

  s->print(F(",\"pins\":["));
  for (uint8_t i = 0; i < gRoleCount; i++) {
    if (i) s->print(',');
    char lab[5];
    pinLabelAt(i, lab);
    s->print(F("{\"label\":"));
    printSilkJson(s, lab);
    s->print(F(",\"role\":\""));
    printRole(s, pinRoleAt(i));
    s->print(F("\"}"));
  }
  s->print(']');

  s->print(F(",\"opticalDistricts\":["));
  bool firstDistrict = true;
  uint8_t districtIx = 0;
  for (uint8_t i = 0; i < gRoleCount; i++) {
    if (pinRoleAt(i) != ROLE_REF) continue;
    if (districtIx >= NODE_MAX_DISTRICTS) break;
    if (!firstDistrict) s->print(',');
    firstDistrict = false;
    int refG = pinGpioAt(i);
    uint8_t pct = 0;
    for (uint8_t j = 0; j < gRoleCount; j++) {
      if (pinRoleAt(j) == ROLE_OPTICAL && pinRefAt(j) == refG) {
        pct = pinPctAt(j);
        break;
      }
    }
    if (!pct) pct = NODE_DEFAULT_SENSITIVITY_PCT;
    char refLab[5];
    pinLabelAt(i, refLab);
    s->print(F("{\"name\":"));
    if (flashBlank(gBakedMeta.districtName[districtIx])) {
      s->print(F("\"District "));
      s->print((int)districtIx + 1);
      s->print('"');
    } else {
      printFlashJson(s, gBakedMeta.districtName[districtIx], 40);
    }
    s->print(F(",\"ref\":"));
    printSilkJson(s, refLab);
    s->print(F(",\"sensors\":["));
    bool firstSensor = true;
    for (uint8_t j = 0; j < gRoleCount; j++) {
      if (pinRoleAt(j) != ROLE_OPTICAL || pinRefAt(j) != refG) continue;
      if (!firstSensor) s->print(',');
      firstSensor = false;
      char slab[5];
      pinLabelAt(j, slab);
      printSilkJson(s, slab);
    }
    s->print(F("],\"sensitivityPct\":"));
    s->print((int)pct);
    s->print('}');
    districtIx++;
  }
  s->print(']');

  s->print(F(",\"outputs\":["));
  for (uint8_t oi = 0; oi < gOutputCount; oi++) {
    if (oi) s->print(',');
    printBakedOutput(s, oi);
  }
  s->print(F("]}"));
}

#endif

void nodeReplyCfg() {
  Stream* s = boardLink();
  if (!s) return;
  s->print(F("CFG "));
#if defined(NODE_BAKED_CONFIG)
  printBakedCfg(s);
  s->println();
#else
  uint16_t len = 0;
  if (gCfgValid && loadCfgIntoBuffer(gLine, NODE_MAX_LINE, &len) && len > 0) {
    s->println(gLine);
    gLineLen = 0;
  } else {
    s->println(F("{}"));
  }
#endif
}

void nodeReplyId() {
  Stream* s = boardLink();
  if (!s) return;
  const char* prefix = nodeEffectivePrefix();
  const char* type = boardType();
  s->print("ID ");
  s->print(prefix ? prefix : "?");
  s->print(' ');
  s->print(type ? type : "?");
  s->print(' ');
  s->println(nodeImageVersion() ? nodeImageVersion() : "0.0.0");
}

static void emitSensorAt(uint8_t i, bool active) {
  Stream* s = boardLink();
  if (!s) return;
  char label[5];
  pinLabelAt(i, label);
  s->print(nodeEffectivePrefix());
  s->print('-');
  s->print(label);
  s->println(active ? ": DETECTED" : ": CLEAR");
}

static void snapshotSensors() {
  unsigned long now = millis();
  for (uint8_t i = 0; i < gRoleCount; i++) {
    if (!roleIsSensorAt(i)) continue;
    bool raw = pinRoleAt(i) == ROLE_OPTICAL ? opticalActiveAt(i) : (digitalRead(pinGpioAt(i)) == LOW);
    gPinRt[i].reportedActive = raw;
    gPinRt[i].clearSinceMs = now;
    emitSensorAt(i, raw);
  }
}

void nodeClearCfg() {
#if defined(NODE_BAKED_CONFIG)
  Stream* s = boardLink();
  if (s) s->println(F("CFG {}"));
#else
  clearCfgStorage();
  clearRuntime();
  boardLoadDefaultPinRoles(gPinCfg, &gRoleCount, &gRefGpio);
  applySensorPinModes();
  snapshotSensors();
  Stream* s = boardLink();
  if (s) s->println(F("CFG {}"));
#endif
}

static void scanSensors() {
  unsigned long now = millis();
  for (uint8_t i = 0; i < gRoleCount; i++) {
    if (!roleIsSensorAt(i)) continue;
    bool raw = pinRoleAt(i) == ROLE_OPTICAL ? opticalActiveAt(i) : (digitalRead(pinGpioAt(i)) == LOW);
    if (raw) {
      gPinRt[i].clearSinceMs = 0;
      if (!gPinRt[i].reportedActive) {
        emitSensorAt(i, true);
        gPinRt[i].reportedActive = true;
      }
    } else if (gPinRt[i].reportedActive) {
      if (gPinRt[i].clearSinceMs == 0) {
        gPinRt[i].clearSinceMs = now;
      } else if (now - gPinRt[i].clearSinceMs >= CLEAR_HOLD_MS) {
        emitSensorAt(i, false);
        gPinRt[i].reportedActive = false;
        gPinRt[i].clearSinceMs = 0;
      }
    }
  }
}

static bool addressOwned(int addr) {
  for (uint8_t i = 0; i < gOutputCount; i++) {
    uint8_t n = outTrigCountAt(i);
    for (uint8_t t = 0; t < n; t++) {
      NodeTrigger tr;
      outTrigAt(i, t, &tr);
      if (tr.dcc == addr) return true;
    }
  }
  return false;
}

static void setOutDigital(uint8_t oi, uint8_t pinIx, bool on) {
  if (pinIx >= outPinCountAt(oi)) return;
  writeDigital(outGpioAt(oi, pinIx), on);
}

static int lcBarrierTravel(uint8_t oi, uint8_t which) {
  int t = which ? OI(oi, barrier2TravelMs) : OI(oi, barrier1TravelMs);
  return t < 1 ? 1 : t;
}

static int lcBarrierOpen(uint8_t oi, uint8_t which) {
  return which ? OI(oi, barrier2OpenDeg) : OI(oi, barrier1OpenDeg);
}

static int lcBarrierClosed(uint8_t oi, uint8_t which) {
  return which ? OI(oi, barrier2ClosedDeg) : OI(oi, barrier1ClosedDeg);
}

static int lcMaxBarrierTravel(uint8_t oi) {
  int a = lcBarrierTravel(oi, 0);
  int b = lcBarrierTravel(oi, 1);
  return a > b ? a : b;
}

static void lcWriteBarrier(uint8_t oi, uint8_t which, int deg) {
  if (deg < 0) deg = 0;
  if (deg > 180) deg = 180;
  NodeOutputRuntime* r = &gOutRt[oi];
  uint8_t pinIx = (uint8_t)(3 + which);
  if (which == 0) r->currentDeg = deg;
  else r->barrier2CurrentDeg = deg;
  if (pinIx < outPinCountAt(oi) && r->servoIx[pinIx] >= 0) {
    writeServoDeg(r->servoIx[pinIx], deg);
  }
}

static void lcHoldBarriersOpen(uint8_t oi) {
  lcWriteBarrier(oi, 0, lcBarrierOpen(oi, 0));
  lcWriteBarrier(oi, 1, lcBarrierOpen(oi, 1));
}

static void lcHoldBarriersClosed(uint8_t oi) {
  lcWriteBarrier(oi, 0, lcBarrierClosed(oi, 0));
  lcWriteBarrier(oi, 1, lcBarrierClosed(oi, 1));
}

static void startBarrier2Travel(uint8_t oi, int deg) {
  if (deg < 0) deg = 0;
  if (deg > 180) deg = 180;
  NodeOutputRuntime* r = &gOutRt[oi];
  r->barrier2TargetDeg = deg;
  r->barrier2FadeFrom = r->barrier2CurrentDeg;
  r->barrier2FadeStartMs = millis();
  r->active = true;
}

static void tickBarrier2Travel(uint8_t oi, unsigned long now, int travelMs) {
  NodeOutputRuntime* r = &gOutRt[oi];
  int dur = travelMs;
  if (dur < 1) dur = 1;
  int from = r->barrier2FadeFrom;
  int delta = abs(r->barrier2TargetDeg - from);
  int need = (int)((long)dur * delta / 180L);
  if (need < 1) need = 1;
  float t = (float)(now - r->barrier2FadeStartMs) / (float)need;
  if (t >= 1.0f) {
    r->barrier2CurrentDeg = r->barrier2TargetDeg;
    r->active = false;
  } else if (t > 0) {
    r->barrier2CurrentDeg = (int)lround(from + (r->barrier2TargetDeg - from) * t);
  }
}

static void lcApplyLights(uint8_t oi, unsigned long now) {
  NodeOutputRuntime* r = &gOutRt[oi];
  bool warn = (r->lcPhase == LC_WARNING);
  bool ww = (r->lcPhase == LC_WIGWAG || r->lcPhase == LC_BARRIERS_CLOSING
      || r->lcPhase == LC_CLOSED || r->lcPhase == LC_BARRIERS_OPENING
      || r->lcPhase == LC_CLEARING);
  setOutDigital(oi, 0, warn);
  if (!ww) {
    setOutDigital(oi, 1, false);
    setOutDigital(oi, 2, false);
    r->nextToggleMs = 0;
    return;
  }
  if (r->nextToggleMs == 0) {
    r->wigwagLeftOn = true;
    r->nextToggleMs = now + (unsigned long)OI(oi, wigwagOnMs);
  }
  if (now >= r->nextToggleMs) {
    r->wigwagLeftOn = !r->wigwagLeftOn;
    r->nextToggleMs = now + (unsigned long)(r->wigwagLeftOn ? OI(oi, wigwagOnMs) : OI(oi, wigwagOffMs));
  }
  setOutDigital(oi, 1, r->wigwagLeftOn);
  setOutDigital(oi, 2, !r->wigwagLeftOn);
}

static void lcUpdateBarrierMotion(uint8_t oi, unsigned long now) {
  NodeOutputRuntime* r = &gOutRt[oi];
  if (r->lcPhase == LC_BARRIERS_CLOSING || r->lcPhase == LC_BARRIERS_OPENING) {
    for (uint8_t which = 0; which < 2; which++) {
      int open = lcBarrierOpen(oi, which);
      int closed = lcBarrierClosed(oi, which);
      int travel = lcBarrierTravel(oi, which);
      float t = (float)(now - r->phaseStartedMs) / (float)travel;
      if (t < 0) t = 0;
      if (t > 1) t = 1;
      int deg = (r->lcPhase == LC_BARRIERS_CLOSING)
          ? (int)lround(open + (closed - open) * t)
          : (int)lround(closed + (open - closed) * t);
      lcWriteBarrier(oi, which, deg);
    }
  } else if (r->lcPhase == LC_MANUAL) {
    if (r->lcManualWhich == 0) {
      tickServoTravel(oi, now, lcBarrierTravel(oi, 0));
      lcWriteBarrier(oi, 0, r->currentDeg);
    } else if (r->lcManualWhich == 1) {
      tickBarrier2Travel(oi, now, lcBarrierTravel(oi, 1));
      lcWriteBarrier(oi, 1, r->barrier2CurrentDeg);
    }
  } else if (r->lcPhase == LC_CLOSED) {
    lcHoldBarriersClosed(oi);
  } else if (r->lcPhase == LC_IDLE_OPEN || r->lcPhase == LC_WARNING || r->lcPhase == LC_WIGWAG
      || r->lcPhase == LC_CLEARING) {
    lcHoldBarriersOpen(oi);
  }
}

static void lcAdvance(uint8_t oi, unsigned long now) {
  NodeOutputRuntime* r = &gOutRt[oi];
  if (r->phaseUntilMs == 0 || now < r->phaseUntilMs) return;
  if (r->lcPhase == LC_WARNING) {
    r->lcPhase = LC_WIGWAG;
    r->phaseStartedMs = now;
    int settle = OI(oi, wigwagOnMs) * 2;
    if (settle < 800) settle = 800;
    r->phaseUntilMs = now + (unsigned long)settle;
  } else if (r->lcPhase == LC_WIGWAG) {
    r->lcPhase = LC_BARRIERS_CLOSING;
    r->lcManualWhich = -1;
    r->phaseStartedMs = now;
    r->phaseUntilMs = now + (unsigned long)lcMaxBarrierTravel(oi);
  } else if (r->lcPhase == LC_BARRIERS_CLOSING) {
    r->lcPhase = LC_CLOSED;
    r->phaseUntilMs = 0;
    lcHoldBarriersClosed(oi);
  } else if (r->lcPhase == LC_BARRIERS_OPENING) {
    r->lcPhase = LC_CLEARING;
    r->phaseStartedMs = now;
    r->phaseUntilMs = now + LC_CLEARING_MS;
    lcHoldBarriersOpen(oi);
  } else if (r->lcPhase == LC_CLEARING) {
    r->lcPhase = LC_IDLE_OPEN;
    r->phaseUntilMs = 0;
    r->active = false;
    setOutDigital(oi, 0, false);
    setOutDigital(oi, 1, false);
    setOutDigital(oi, 2, false);
  }
}

static void lcStartClose(uint8_t oi, unsigned long now) {
  NodeOutputRuntime* r = &gOutRt[oi];
  r->active = true;
  r->lcPhase = LC_WARNING;
  r->lcManualWhich = -1;
  r->phaseStartedMs = now;
  r->phaseUntilMs = now + (unsigned long)OI(oi, warningMs);
  r->wigwagLeftOn = true;
  r->nextToggleMs = 0;
  lcHoldBarriersOpen(oi);
}

static void lcStartOpen(uint8_t oi, unsigned long now) {
  NodeOutputRuntime* r = &gOutRt[oi];
  if (r->lcPhase == LC_IDLE_OPEN) {
    lcHoldBarriersOpen(oi);
    return;
  }
  r->active = true;
  r->lcPhase = LC_BARRIERS_OPENING;
  r->lcManualWhich = -1;
  r->phaseStartedMs = now;
  r->phaseUntilMs = now + (unsigned long)lcMaxBarrierTravel(oi);
  if (r->nextToggleMs == 0) {
    r->wigwagLeftOn = true;
    r->nextToggleMs = now + (unsigned long)OI(oi, wigwagOnMs);
  }
}

static void applyTrigger(uint8_t oi, const NodeTrigger* t, unsigned long now) {
  NodeOutputRuntime* r = &gOutRt[oi];
  switch (outTypeAt(oi)) {
    case OUT_ON_OFF: {
      bool on = triggerIsOnish(t);
      r->active = on;
      setOutDigital(oi, 0, on);
      break;
    }
    case OUT_PULSE: {
      r->active = true;
      setOutDigital(oi, 0, true);
      r->pulseUntilMs = now + (unsigned long)OI(oi, pulseMs);
      break;
    }
    case OUT_BLINK: {
      bool start = triggerIsOnish(t);
      r->active = start;
      r->phaseHigh = start;
      setOutDigital(oi, 0, start);
      r->nextToggleMs = start ? now + (unsigned long)OI(oi, onMs) : 0;
      break;
    }
    case OUT_ALT_PAIR: {
      bool start = triggerIsOnish(t);
      r->active = start;
      r->phaseHigh = true;
      if (start) {
        setOutDigital(oi, 0, true);
        setOutDigital(oi, 1, false);
        r->nextToggleMs = now + (unsigned long)OI(oi, onMs);
      } else {
        setOutDigital(oi, 0, false);
        setOutDigital(oi, 1, false);
        r->nextToggleMs = 0;
      }
      break;
    }
    case OUT_FADE: {
      r->targetBright = t->hasBrightness ? t->brightness : (triggerIsOnish(t) ? 100 : 0);
      r->fadeFromBright = r->currentBright;
      r->fadeStartMs = now;
      r->active = true;
      break;
    }
    case OUT_SERVO: {
      r->targetDeg = t->hasDeg ? t->deg : (triggerIsOnish(t) ? 90 : 0);
      r->fadeStartMs = now;
      r->fadeFromBright = r->currentDeg;
      r->active = true;
      break;
    }
    case OUT_LEVEL_CROSSING: {
      if (triggerIsOpenish(t)) lcStartOpen(oi, now);
      else lcStartClose(oi, now);
      break;
    }
    case OUT_DIGITAL_SIGNAL: {
      r->signalOnMask = t->onMask;
      r->signalFlashMask = t->flashMask;
      r->signalFlashHigh = true;
      r->active = (t->flashMask != 0);
      r->nextToggleMs = r->active ? now + (unsigned long)OI(oi, flashMs) : 0;
      uint8_t n = outPinCountAt(oi);
      for (uint8_t i = 0; i < n; i++) {
        bool on = (r->signalOnMask & (1u << i)) != 0;
        bool flash = (r->signalFlashMask & (1u << i)) != 0;
        setOutDigital(oi, i, on && (!flash || r->signalFlashHigh));
      }
      break;
    }
    default:
      break;
  }
}

static void handleAccessory(int addr, bool wireOn) {
  unsigned long now = millis();
  for (uint8_t i = 0; i < gOutputCount; i++) {
    uint8_t n = outTrigCountAt(i);
    for (uint8_t ti = 0; ti < n; ti++) {
      NodeTrigger tr;
      outTrigAt(i, ti, &tr);
      if (tr.dcc != addr) continue;
      if (tr.wireOn != wireOn) continue;
      applyTrigger(i, &tr, now);
      return;
    }
  }
}

static void tickOutputs() {
  unsigned long now = millis();
  for (uint8_t i = 0; i < gOutputCount; i++) {
    NodeOutputRuntime* r = &gOutRt[i];
    switch (outTypeAt(i)) {
      case OUT_PULSE:
        if (r->pulseUntilMs > 0 && now >= r->pulseUntilMs) {
          setOutDigital(i, 0, false);
          r->active = false;
          r->pulseUntilMs = 0;
        }
        break;
      case OUT_BLINK:
        if (r->active && r->nextToggleMs > 0 && now >= r->nextToggleMs) {
          r->phaseHigh = !r->phaseHigh;
          setOutDigital(i, 0, r->phaseHigh);
          r->nextToggleMs = now + (unsigned long)(r->phaseHigh ? OI(i, onMs) : OI(i, offMs));
        }
        break;
      case OUT_ALT_PAIR:
        if (r->active && r->nextToggleMs > 0 && now >= r->nextToggleMs) {
          r->phaseHigh = !r->phaseHigh;
          setOutDigital(i, 0, r->phaseHigh);
          setOutDigital(i, 1, !r->phaseHigh);
          r->nextToggleMs = now + (unsigned long)(r->phaseHigh ? OI(i, onMs) : OI(i, offMs));
        }
        break;
      case OUT_FADE: {
        int dur = OI(i, fadeMs);
        if (dur < 1) dur = 1;
        float t = (float)(now - r->fadeStartMs) / (float)dur;
        if (t >= 1.0f) {
          r->currentBright = r->targetBright;
          r->active = false;
        } else if (t > 0) {
          r->currentBright = (int)lround(r->fadeFromBright + (r->targetBright - r->fadeFromBright) * t);
        }
        writePwmPercent(outGpioAt(i, 0), r->currentBright);
        break;
      }
      case OUT_SERVO: {
        tickServoTravel(i, now, OI(i, travelMs));
        if (r->servoIx[0] >= 0) writeServoDeg(r->servoIx[0], r->currentDeg);
        break;
      }
      case OUT_LEVEL_CROSSING:
        lcAdvance(i, now);
        lcUpdateBarrierMotion(i, now);
        lcApplyLights(i, now);
        break;
      case OUT_DIGITAL_SIGNAL:
        if (r->signalFlashMask && r->nextToggleMs > 0 && now >= r->nextToggleMs) {
          r->signalFlashHigh = !r->signalFlashHigh;
          r->nextToggleMs = now + (unsigned long)OI(i, flashMs);
          uint8_t n = outPinCountAt(i);
          for (uint8_t p = 0; p < n; p++) {
            bool on = (r->signalOnMask & (1u << p)) != 0;
            bool flash = (r->signalFlashMask & (1u << p)) != 0;
            setOutDigital(i, p, on && (!flash || r->signalFlashHigh));
          }
        }
        break;
      default:
        break;
    }
  }
}

static int findGpioBySilk(const char* silk) {
  char norm[5];
  normalizeSilk(norm, sizeof(norm), silk);
  return boardSilkToGpio(norm);
}

static void handleTestSample(char* p) {
  while (*p == ' ') p++;
  if (!*p) return;
  char* sp = strchr(p, ' ');
  char refLab[8] = {0};
  char sensLab[8] = {0};
  if (sp) {
    *sp = '\0';
    strCopyCap(refLab, sizeof(refLab), p);
    sp++;
    while (*sp == ' ') sp++;
    strCopyCap(sensLab, sizeof(sensLab), sp);
  } else {
    strCopyCap(refLab, sizeof(refLab), p);
  }
  char refNorm[5];
  normalizeSilk(refNorm, sizeof(refNorm), refLab);
  int refGpio = findGpioBySilk(refNorm);
  if (refGpio < 0) return;
  int refVal = analogRead(refGpio);
  Stream* s = boardLink();
  if (!s) return;
  if (!sensLab[0]) {
    s->print("SAMPLE ");
    s->print(refNorm);
    s->print('=');
    s->println(refVal);
    return;
  }
  char sensNorm[5];
  normalizeSilk(sensNorm, sizeof(sensNorm), sensLab);
  int sensGpio = findGpioBySilk(sensNorm);
  if (sensGpio < 0) return;
  int sensVal = analogRead(sensGpio);
  int delta = sensVal - refVal;
  int pct = 0;
  if (delta > 0 && refVal > 0) {
    pct = (int)(((long)delta * 100L) / (long)refVal);
    if (pct > 100) pct = 100;
  } else if (delta > 0) {
    pct = 100;
  }
  s->print("SAMPLE ");
  s->print(refNorm);
  s->print('=');
  s->print(refVal);
  s->print(' ');
  s->print(sensNorm);
  s->print('=');
  s->print(sensVal);
  s->print(" delta=");
  s->print(delta);
  s->print(" pct=");
  s->println(pct);
}

static void handleTest(const char* rest) {
  char buf[64];
  strCopyCap(buf, sizeof(buf), rest);
  char* p = buf;
  while (*p == ' ') p++;
  if (startsWithi(p, "SAMPLE")) {
    p += 6;
    handleTestSample(p);
    return;
  }
  if (startsWithi(p, "SERVO ")) {
    p += 6;
    while (*p == ' ') p++;
    char* sp = strchr(p, ' ');
    if (!sp) return;
    *sp = '\0';
    int deg = atoi(sp + 1);
    if (deg < 0) deg = 0;
    if (deg > 180) deg = 180;
    int gpio = findGpioBySilk(p);
    if (gpio < 0) return;
    int8_t ix = -1;
    bool moved = false;
    for (uint8_t i = 0; i < gOutputCount; i++) {
      uint8_t n = outPinCountAt(i);
      for (uint8_t j = 0; j < n; j++) {
        if (outGpioAt(i, j) != gpio || gOutRt[i].servoIx[j] < 0) continue;
        ix = gOutRt[i].servoIx[j];
        NodeOutType type = outTypeAt(i);
        if (type == OUT_SERVO) {
          // Pace at travelMs like DCC triggers — not an instant jump.
          startServoTravel(i, deg);
          moved = true;
        } else if (type == OUT_LEVEL_CROSSING) {
          uint8_t which = (j >= 4) ? 1 : 0;
          gOutRt[i].lcPhase = LC_MANUAL;
          gOutRt[i].lcManualWhich = (int8_t)which;
          gOutRt[i].phaseUntilMs = 0;
          if (which == 0) startServoTravel(i, deg);
          else startBarrier2Travel(i, deg);
          moved = true;
        } else {
          holdServoOutput(i, deg);
        }
      }
    }
    if (moved) return;
    if (ix < 0) {
      pinMode(gpio, OUTPUT);
      ix = allocServo(gpio);
    }
    writeServoDeg(ix, deg);
    return;
  }
  if (startsWithi(p, "PWM ")) {
    p += 4;
    while (*p == ' ') p++;
    char* sp = strchr(p, ' ');
    if (!sp) return;
    *sp = '\0';
    int pct = atoi(sp + 1);
    int gpio = findGpioBySilk(p);
    if (gpio < 0) return;
    pinMode(gpio, OUTPUT);
    writePwmPercent(gpio, pct);
    return;
  }
  int addr = atoi(p);
  char* sp = strchr(p, ' ');
  if (!sp || addr <= 0) return;
  while (*sp == ' ') sp++;
  bool on = startsWithi(sp, "ON");
  if (!addressOwned(addr) && gOutputCount > 0) return;
  handleAccessory(addr, on);
}

static void handleLine(char* line) {
  size_t n = strlen(line);
  while (n > 0 && (line[n - 1] == '\r' || line[n - 1] == '\n')) {
    line[--n] = '\0';
  }
  if (n == 0) return;

  if (streqi(line, "CFG CLEAR")) {
    nodeClearCfg();
    return;
  }
  if (streqi(line, "BOOTLOADER")) {
    // Soft enter ROM download mode (USB-CDC ESP32-S3 / S2). Classic ESP32 uses UART DTR/RTS instead.
    Stream* s = boardLink();
#if defined(CONFIG_IDF_TARGET_ESP32S3) || defined(CONFIG_IDF_TARGET_ESP32S2)
    if (s) {
      s->println(F("OK"));
      s->flush();
    }
    delay(30);
    REG_WRITE(RTC_CNTL_OPTION1_REG, RTC_CNTL_FORCE_DOWNLOAD_BOOT);
    esp_restart();
#elif defined(ESP32)
    if (s) s->println(F("ERR uart-reset"));
#else
    if (s) s->println(F("ERR unsupported"));
#endif
    return;
  }
  if (startsWithi(line, "CFG ")) {
    nodeApplyCfgJson(line + 4);
    return;
  }
  if (streqi(line, "CFG?")) {
    nodeReplyCfg();
    return;
  }
  if (streqi(line, "ID?")) {
    nodeReplyId();
    return;
  }
  if (startsWithi(line, "PREFIX ")) {
    handlePrefix(line + 7);
    return;
  }
  if (startsWithi(line, "TEST ")) {
    handleTest(line + 5);
    return;
  }
  if (startsWithi(line, "OUT ")) {
    handleTest(line + 4);
    return;
  }
  if (line[0] >= '0' && line[0] <= '9') {
    int addr = atoi(line);
    char* sp = strchr(line, ' ');
    if (!sp || addr <= 0) return;
    while (*sp == ' ') sp++;
    bool on;
    if (startsWithi(sp, "ON") || startsWithi(sp, "FWD") || startsWithi(sp, "FORWARD")) on = true;
    else if (startsWithi(sp, "OFF") || startsWithi(sp, "REV") || startsWithi(sp, "REVERSE")
        || startsWithi(sp, "THROWN") || startsWithi(sp, "CLOSED")) on = false;
    else return;
    if (!addressOwned(addr)) return;
    handleAccessory(addr, on);
  }
}

void nodeEnginePollStream(Stream* s) {
  if (!s) return;
  while (s->available() > 0) {
    char c = (char)s->read();
    if (c == '\n') {
      gLine[gLineLen < NODE_MAX_LINE ? gLineLen : NODE_MAX_LINE - 1] = '\0';
      handleLine(gLine);
      gLineLen = 0;
    } else if (c != '\r') {
      if (gLineLen + 1 < NODE_MAX_LINE) {
        gLine[gLineLen++] = c;
      }
    }
  }
}

void nodeEngineBegin() {
  nodeLoadIdentity();
  gCfgLen = 0;
  gCfgValid = false;
  gLineLen = 0;
  clearRuntime();
#if defined(NODE_BAKED_CONFIG)
  gRoleCount = gBakedPinCount;
  if (gRoleCount > NODE_MAX_PIN_ROLES) gRoleCount = NODE_MAX_PIN_ROLES;
  gRefGpio = gBakedRefGpio;
  gOutputCount = gBakedOutputCount;
  if (gOutputCount > NODE_MAX_OUTPUTS) gOutputCount = NODE_MAX_OUTPUTS;
  applySensorPinModes();
  for (uint8_t i = 0; i < gOutputCount; i++) finishOutput(i);
#else
  uint16_t len = 0;
  if (loadCfgIntoBuffer(gLine, NODE_MAX_LINE, &len) && len > 0) {
    gCfgLen = len;
    gCfgValid = true;
    rebuildFromCfgBuffer(gLine);
  } else {
    boardLoadDefaultPinRoles(gPinCfg, &gRoleCount, &gRefGpio);
    applySensorPinModes();
  }
#endif
  gLineLen = 0;

  Stream* s = boardLink();
  if (s) {
    s->print("PanelsDCC Node ");
    s->print(nodeEffectivePrefix());
    s->print(' ');
    s->print(boardType());
    s->print(" v");
    s->print(nodeImageVersion());
    s->println(" READY");
  }
  snapshotSensors();
}

void nodeEngineTick() {
  scanSensors();
  tickOutputs();
}

void nodeEngineLoop() {
  nodeEnginePollStream(boardLink());
  nodeEngineTick();
}
