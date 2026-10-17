#include "NodeEngine.h"
#include <math.h>
#include <string.h>
#include <stdlib.h>

#if defined(ESP32)
#include <Preferences.h>
static Preferences gPrefs;
#else
#include <EEPROM.h>
#endif

// ---- globals ----
// CFG JSON is persisted to EEPROM/NVS; not kept in RAM (Nano SRAM budget).
static uint16_t gCfgLen = 0;
static bool gCfgValid = false;
static char gLine[NODE_MAX_LINE];
static uint16_t gLineLen = 0;

static NodePinRoleEntry gRoles[NODE_MAX_PIN_ROLES];
static uint8_t gRoleCount = 0;
static int gRefGpio = -1;

static NodeSensor gSensors[NODE_MAX_SENSORS];
static uint8_t gSensorCount = 0;

static NodeOutput gOutputs[NODE_MAX_OUTPUTS];
static uint8_t gOutputCount = 0;

static Servo gServos[NODE_MAX_SERVOS];
static uint8_t gServoCount = 0;

static const unsigned long CLEAR_HOLD_MS = 1000;
static const unsigned long LC_CLEARING_MS = 2000;

/** Per-optical-sensor district meta applied after rebuildSensorsFromRoles. */
struct OpticalMeta {
  char label[5];
  int refGpio;
  uint8_t sensitivityPct;
};
static OpticalMeta gOpticalMeta[NODE_MAX_SENSORS];
static uint8_t gOpticalMetaCount = 0;

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

// ---- pin / sensor rebuild ----
static void clearRuntime() {
  detachAllServos();
  gRoleCount = 0;
  gSensorCount = 0;
  gOutputCount = 0;
  gRefGpio = -1;
  gOpticalMetaCount = 0;
  memset(gOutputs, 0, sizeof(gOutputs));
  memset(gSensors, 0, sizeof(gSensors));
  memset(gRoles, 0, sizeof(gRoles));
  memset(gOpticalMeta, 0, sizeof(gOpticalMeta));
}

static void addOpticalMeta(const char* label, int refGpio, uint8_t pct) {
  if (gOpticalMetaCount >= NODE_MAX_SENSORS) return;
  OpticalMeta* m = &gOpticalMeta[gOpticalMetaCount++];
  normalizeSilk(m->label, sizeof(m->label), label);
  m->refGpio = refGpio;
  if (pct < 1) pct = NODE_DEFAULT_SENSITIVITY_PCT;
  if (pct > 100) pct = 100;
  m->sensitivityPct = pct;
}

static void applyOpticalMetaToSensors() {
  for (uint8_t i = 0; i < gSensorCount; i++) {
    NodeSensor* s = &gSensors[i];
    if (!s->optical) {
      s->refGpio = -1;
      s->sensitivityPct = 0;
      continue;
    }
    s->refGpio = gRefGpio;
    s->sensitivityPct = NODE_DEFAULT_SENSITIVITY_PCT;
    for (uint8_t m = 0; m < gOpticalMetaCount; m++) {
      if (streqi(gOpticalMeta[m].label, s->label)) {
        s->refGpio = gOpticalMeta[m].refGpio;
        s->sensitivityPct = gOpticalMeta[m].sensitivityPct;
        break;
      }
    }
  }
}

static bool opticalActive(const NodeSensor* sens) {
  if (!sens->optical || sens->gpio < 0) return false;
  int refGpio = sens->refGpio >= 0 ? sens->refGpio : gRefGpio;
  int ref = (refGpio >= 0) ? analogRead(refGpio) : 0;
  int val = analogRead(sens->gpio);
  int delta = val - ref;
  if (delta <= 0) return false;
  uint8_t pct = sens->sensitivityPct ? sens->sensitivityPct : NODE_DEFAULT_SENSITIVITY_PCT;
  long need = ((long)ref * (long)pct) / 100L;
  if (need < 1) need = 1;
  return delta > (int)need;
}

static void addRole(const char* label, NodePinRole role) {
  if (gRoleCount >= NODE_MAX_PIN_ROLES) return;
  char silk[5];
  normalizeSilk(silk, sizeof(silk), label);
  int gpio = boardSilkToGpio(silk);
  if (gpio < 0 && role != ROLE_FREE) return;
  NodePinRoleEntry* e = &gRoles[gRoleCount++];
  strCopyCap(e->label, sizeof(e->label), silk);
  e->role = role;
  e->gpio = gpio;
  if (role == ROLE_REF) gRefGpio = gpio;
}

static void rebuildSensorsFromRoles() {
  gSensorCount = 0;
  for (uint8_t i = 0; i < gRoleCount; i++) {
    NodePinRoleEntry* e = &gRoles[i];
    if (e->role != ROLE_OPTICAL && e->role != ROLE_DIGITAL_IN) continue;
    if (gSensorCount >= NODE_MAX_SENSORS) break;
    NodeSensor* s = &gSensors[gSensorCount++];
    strCopyCap(s->label, sizeof(s->label), e->label);
    s->gpio = e->gpio;
    s->optical = (e->role == ROLE_OPTICAL);
    s->channel = silkNumber(e->label);
    s->refGpio = -1;
    s->sensitivityPct = NODE_DEFAULT_SENSITIVITY_PCT;
    s->reportedActive = false;
    s->clearSinceMs = 0;
    if (!s->optical) {
      pinMode(s->gpio, INPUT_PULLUP);
    }
  }
  applyOpticalMetaToSensors();
}

static void applyOutputPinModes(NodeOutput* o) {
  for (uint8_t i = 0; i < o->pinCount; i++) {
    int g = o->gpio[i];
    if (g < 0) continue;
    if (o->type == OUT_SERVO || (o->type == OUT_LEVEL_CROSSING && i >= 3)) {
      o->servoIx[i] = allocServo(g);
    } else {
      pinMode(g, OUTPUT);
      writeDigital(g, false);
      o->servoIx[i] = -1;
    }
  }
}

static void parseParams(NodeOutput* o, const char* paramsObj) {
  o->pulseMs = 250;
  o->onMs = 500;
  o->offMs = 500;
  o->fadeMs = 800;
  o->travelMs = 1000;
  o->warningMs = 3000;
  o->wigwagOnMs = 400;
  o->wigwagOffMs = 400;
  o->barrierTravelMs = 2500;
  o->barrierOpenDeg = 90;
  o->barrierClosedDeg = 0;
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
  if ((v = findKey(paramsObj, "barrierTravelMs")) && parseIntVal(v, &n)) o->barrierTravelMs = n;
  if ((v = findKey(paramsObj, "barrierOpen_deg")) && parseIntVal(v, &n)) o->barrierOpenDeg = n;
  if ((v = findKey(paramsObj, "barrierClosed_deg")) && parseIntVal(v, &n)) o->barrierClosedDeg = n;
  if ((v = findKey(paramsObj, "flashMs")) && parseIntVal(v, &n)) o->flashMs = n;
}

static void parseTriggerArray(NodeOutput* o, const char* arr) {
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
    // digital_signal on / flashing arrays of pin indices
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
    NodeOutput* o = &gOutputs[gOutputCount];
    memset(o, 0, sizeof(*o));
    for (uint8_t i = 0; i < NODE_MAX_OUT_PINS; i++) o->servoIx[i] = -1;
    char typeBuf[24] = {0};
    const char* v;
    if ((v = findKey(p, "type"))) parseStringVal(v, typeBuf, sizeof(typeBuf));
    o->type = parseOutType(typeBuf);
    parseParams(o, findKey(p, "params"));
    // pins: ["D9","D10"]
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
    // legacy single dcc
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
      applyOutputPinModes(o);
      if (o->type == OUT_SERVO) {
        o->currentDeg = 0;
        o->targetDeg = 0;
        if (o->servoIx[0] >= 0) writeServoDeg(o->servoIx[0], o->currentDeg);
      } else if (o->type == OUT_LEVEL_CROSSING) {
        o->lcPhase = LC_IDLE_OPEN;
        o->currentDeg = o->barrierOpenDeg;
        o->targetDeg = o->barrierOpenDeg;
        for (uint8_t i = 3; i < o->pinCount && i < 5; i++) {
          if (o->servoIx[i] >= 0) writeServoDeg(o->servoIx[i], o->barrierOpenDeg);
        }
      }
      gOutputCount++;
    }
    p = skipValue(p);
    p = skipWs(p);
    if (*p == ',') p++;
  }
}

/** Returns true if at least one district with a ref was applied. */
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
    if (refGpio >= 0) {
      gRefGpio = refGpio; // last district ref as fallback
    }
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
    if (gRoles[i].role == ROLE_OPTICAL || gRoles[i].role == ROLE_REF) continue;
    if (w != i) gRoles[w] = gRoles[i];
    w++;
  }
  gRoleCount = w;
  gRefGpio = -1;
  gOpticalMetaCount = 0;
}

static void rebuildFromCfgBuffer(const char* json) {
  clearRuntime();
  if (!json || !json[0]) {
    boardLoadDefaultPinRoles(gRoles, &gRoleCount, &gRefGpio);
    rebuildSensorsFromRoles();
    return;
  }
  const char* root = skipWs(json);
  const char* pins = findKey(root, "pins");
  if (pins && *skipWs(pins) == '[') {
    parsePinsArray(pins);
  } else {
    boardLoadDefaultPinRoles(gRoles, &gRoleCount, &gRefGpio);
  }

  const char* districts = findKey(root, "opticalDistricts");
  bool usedDistricts = false;
  if (districts && *skipWs(districts) == '[') {
    const char* after = skipWs(skipWs(districts) + 1);
    bool emptyArr = after && *after == ']';
    if (!emptyArr) {
      // Prefer districts for optical/ref; keep digital_in/output from pins
      stripOpticalAndRefRoles();
      usedDistricts = parseOpticalDistricts(districts);
    }
  }

  if (!usedDistricts) {
    // Ensure ambient ref if optical sensors present
    if (gRefGpio < 0) {
      for (uint8_t i = 0; i < gRoleCount; i++) {
        if (gRoles[i].role == ROLE_OPTICAL) {
          NodePinRoleEntry tmp[NODE_MAX_PIN_ROLES];
          uint8_t tc = 0;
          int ref = -1;
          boardLoadDefaultPinRoles(tmp, &tc, &ref);
          gRefGpio = ref;
          break;
        }
      }
    }
    // Default meta: all optical share board ref at 15%
    for (uint8_t i = 0; i < gRoleCount; i++) {
      if (gRoles[i].role == ROLE_OPTICAL) {
        addOpticalMeta(gRoles[i].label, gRefGpio, NODE_DEFAULT_SENSITIVITY_PCT);
      }
    }
  }

  rebuildSensorsFromRoles();
  const char* outs = findKey(root, "outputs");
  if (outs) parseOutputsArray(outs);
}

bool nodeApplyCfgJson(const char* json) {
  if (!json) return false;
  // Skip leading whitespace
  while (*json == ' ' || *json == '\t') json++;
  size_t n = strlen(json);
  if (n == 0 || n >= NODE_MAX_CFG || n + 1 > NODE_MAX_LINE) return false;
  // Parse from the live buffer (usually gLine+4); copy only if needed for persist.
  rebuildFromCfgBuffer(json);
  saveCfgToStorage(json, (uint16_t)n);
  return true;
}

void nodeReplyCfg() {
  Stream* s = boardLink();
  if (!s) return;
  s->print("CFG ");
  uint16_t len = 0;
  if (gCfgValid && loadCfgIntoBuffer(gLine, NODE_MAX_LINE, &len) && len > 0) {
    s->println(gLine);
    gLineLen = 0;
  } else {
    s->println("{}");
  }
}

// ---- emit sensors ----
static void emitSensor(const NodeSensor* sens, bool active) {
  Stream* s = boardLink();
  if (!s) return;
  s->print(boardPrefix());
  s->print('-');
  s->print(sens->label);
  s->println(active ? ": DETECTED" : ": CLEAR");
}

static void snapshotSensors() {
  unsigned long now = millis();
  for (uint8_t i = 0; i < gSensorCount; i++) {
    NodeSensor* sens = &gSensors[i];
    bool raw = sens->optical ? opticalActive(sens) : (digitalRead(sens->gpio) == LOW);
    sens->reportedActive = raw;
    sens->clearSinceMs = now;
    emitSensor(sens, raw);
  }
}

static void scanSensors() {
  unsigned long now = millis();
  for (uint8_t i = 0; i < gSensorCount; i++) {
    NodeSensor* sens = &gSensors[i];
    bool raw = sens->optical ? opticalActive(sens) : (digitalRead(sens->gpio) == LOW);
    if (raw) {
      sens->clearSinceMs = 0;
      if (!sens->reportedActive) {
        emitSensor(sens, true);
        sens->reportedActive = true;
      }
    } else if (sens->reportedActive) {
      if (sens->clearSinceMs == 0) {
        sens->clearSinceMs = now;
      } else if (now - sens->clearSinceMs >= CLEAR_HOLD_MS) {
        emitSensor(sens, false);
        sens->reportedActive = false;
        sens->clearSinceMs = 0;
      }
    }
  }
}

// ---- output engines ----
static bool addressOwned(int addr) {
  for (uint8_t i = 0; i < gOutputCount; i++) {
    for (uint8_t t = 0; t < gOutputs[i].triggerCount; t++) {
      if (gOutputs[i].triggers[t].dcc == addr) return true;
    }
  }
  return false;
}

static void setOutDigital(NodeOutput* o, uint8_t pinIx, bool on) {
  if (pinIx >= o->pinCount) return;
  writeDigital(o->gpio[pinIx], on);
}

static void lcSetBarriers(NodeOutput* o, int deg) {
  o->currentDeg = deg;
  for (uint8_t i = 3; i < o->pinCount && i < 5; i++) {
    if (o->servoIx[i] >= 0) writeServoDeg(o->servoIx[i], deg);
  }
}

static void lcApplyLights(NodeOutput* o, unsigned long now) {
  // pin0 warning, pin1 left, pin2 right
  bool warn = (o->lcPhase == LC_WARNING);
  bool ww = (o->lcPhase == LC_WIGWAG || o->lcPhase == LC_BARRIERS_CLOSING
      || o->lcPhase == LC_CLOSED || o->lcPhase == LC_BARRIERS_OPENING
      || o->lcPhase == LC_CLEARING);
  setOutDigital(o, 0, warn);
  if (!ww) {
    setOutDigital(o, 1, false);
    setOutDigital(o, 2, false);
    o->nextToggleMs = 0;
    return;
  }
  if (o->nextToggleMs == 0) {
    o->wigwagLeftOn = true;
    o->nextToggleMs = now + (unsigned long)o->wigwagOnMs;
  }
  if (now >= o->nextToggleMs) {
    o->wigwagLeftOn = !o->wigwagLeftOn;
    o->nextToggleMs = now + (unsigned long)(o->wigwagLeftOn ? o->wigwagOnMs : o->wigwagOffMs);
  }
  setOutDigital(o, 1, o->wigwagLeftOn);
  setOutDigital(o, 2, !o->wigwagLeftOn);
}

static void lcUpdateBarrierMotion(NodeOutput* o, unsigned long now) {
  int open = o->barrierOpenDeg;
  int closed = o->barrierClosedDeg;
  int travel = o->barrierTravelMs > 0 ? o->barrierTravelMs : 1;
  if (o->lcPhase == LC_BARRIERS_CLOSING) {
    float t = (float)(now - o->phaseStartedMs) / (float)travel;
    if (t < 0) t = 0;
    if (t > 1) t = 1;
    int deg = (int)lround(open + (closed - open) * t);
    lcSetBarriers(o, deg);
  } else if (o->lcPhase == LC_BARRIERS_OPENING) {
    float t = (float)(now - o->phaseStartedMs) / (float)travel;
    if (t < 0) t = 0;
    if (t > 1) t = 1;
    int deg = (int)lround(closed + (open - closed) * t);
    lcSetBarriers(o, deg);
  } else if (o->lcPhase == LC_CLOSED) {
    lcSetBarriers(o, closed);
  } else if (o->lcPhase == LC_IDLE_OPEN || o->lcPhase == LC_WARNING || o->lcPhase == LC_WIGWAG
      || o->lcPhase == LC_CLEARING) {
    lcSetBarriers(o, open);
  }
}

static void lcAdvance(NodeOutput* o, unsigned long now) {
  if (o->phaseUntilMs == 0 || now < o->phaseUntilMs) return;
  if (o->lcPhase == LC_WARNING) {
    o->lcPhase = LC_WIGWAG;
    o->phaseStartedMs = now;
    int settle = o->wigwagOnMs * 2;
    if (settle < 800) settle = 800;
    o->phaseUntilMs = now + (unsigned long)settle;
  } else if (o->lcPhase == LC_WIGWAG) {
    o->lcPhase = LC_BARRIERS_CLOSING;
    o->phaseStartedMs = now;
    o->phaseUntilMs = now + (unsigned long)o->barrierTravelMs;
  } else if (o->lcPhase == LC_BARRIERS_CLOSING) {
    o->lcPhase = LC_CLOSED;
    o->phaseUntilMs = 0;
    lcSetBarriers(o, o->barrierClosedDeg);
  } else if (o->lcPhase == LC_BARRIERS_OPENING) {
    o->lcPhase = LC_CLEARING;
    o->phaseStartedMs = now;
    o->phaseUntilMs = now + LC_CLEARING_MS;
    lcSetBarriers(o, o->barrierOpenDeg);
  } else if (o->lcPhase == LC_CLEARING) {
    o->lcPhase = LC_IDLE_OPEN;
    o->phaseUntilMs = 0;
    o->active = false;
    setOutDigital(o, 0, false);
    setOutDigital(o, 1, false);
    setOutDigital(o, 2, false);
  }
}

static void lcStartClose(NodeOutput* o, unsigned long now) {
  o->active = true;
  o->lcPhase = LC_WARNING;
  o->phaseStartedMs = now;
  o->phaseUntilMs = now + (unsigned long)o->warningMs;
  o->wigwagLeftOn = true;
  o->nextToggleMs = 0;
  lcSetBarriers(o, o->barrierOpenDeg);
}

static void lcStartOpen(NodeOutput* o, unsigned long now) {
  if (o->lcPhase == LC_IDLE_OPEN) {
    lcSetBarriers(o, o->barrierOpenDeg);
    return;
  }
  o->active = true;
  o->lcPhase = LC_BARRIERS_OPENING;
  o->phaseStartedMs = now;
  o->phaseUntilMs = now + (unsigned long)o->barrierTravelMs;
  if (o->nextToggleMs == 0) {
    o->wigwagLeftOn = true;
    o->nextToggleMs = now + (unsigned long)o->wigwagOnMs;
  }
}

static void applyTrigger(NodeOutput* o, const NodeTrigger* t, unsigned long now) {
  switch (o->type) {
    case OUT_ON_OFF: {
      bool on = triggerIsOnish(t);
      o->active = on;
      setOutDigital(o, 0, on);
      break;
    }
    case OUT_PULSE: {
      o->active = true;
      setOutDigital(o, 0, true);
      o->pulseUntilMs = now + (unsigned long)o->pulseMs;
      break;
    }
    case OUT_BLINK: {
      bool start = triggerIsOnish(t);
      o->active = start;
      o->phaseHigh = start;
      setOutDigital(o, 0, start);
      o->nextToggleMs = start ? now + (unsigned long)o->onMs : 0;
      break;
    }
    case OUT_ALT_PAIR: {
      bool start = triggerIsOnish(t);
      o->active = start;
      o->phaseHigh = true;
      if (start) {
        setOutDigital(o, 0, true);
        setOutDigital(o, 1, false);
        o->nextToggleMs = now + (unsigned long)o->onMs;
      } else {
        setOutDigital(o, 0, false);
        setOutDigital(o, 1, false);
        o->nextToggleMs = 0;
      }
      break;
    }
    case OUT_FADE: {
      o->targetBright = t->hasBrightness ? t->brightness : (triggerIsOnish(t) ? 100 : 0);
      o->fadeFromBright = o->currentBright;
      o->fadeStartMs = now;
      o->active = true;
      break;
    }
    case OUT_SERVO: {
      o->targetDeg = t->hasDeg ? t->deg : (triggerIsOnish(t) ? 90 : 0);
      o->fadeStartMs = now;
      o->fadeFromBright = o->currentDeg; // reuse as from-deg
      o->active = true;
      break;
    }
    case OUT_LEVEL_CROSSING: {
      if (triggerIsOpenish(t)) lcStartOpen(o, now);
      else lcStartClose(o, now);
      break;
    }
    case OUT_DIGITAL_SIGNAL: {
      o->signalOnMask = t->onMask;
      o->signalFlashMask = t->flashMask;
      o->signalFlashHigh = true;
      o->active = (t->flashMask != 0);
      o->nextToggleMs = o->active ? now + (unsigned long)o->flashMs : 0;
      for (uint8_t i = 0; i < o->pinCount; i++) {
        bool on = (o->signalOnMask & (1u << i)) != 0;
        bool flash = (o->signalFlashMask & (1u << i)) != 0;
        setOutDigital(o, i, on && (!flash || o->signalFlashHigh));
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
    NodeOutput* o = &gOutputs[i];
    for (uint8_t ti = 0; ti < o->triggerCount; ti++) {
      NodeTrigger* t = &o->triggers[ti];
      if (t->dcc != addr) continue;
      if (t->wireOn != wireOn) continue;
      applyTrigger(o, t, now);
      return;
    }
  }
}

static void tickOutputs() {
  unsigned long now = millis();
  for (uint8_t i = 0; i < gOutputCount; i++) {
    NodeOutput* o = &gOutputs[i];
    switch (o->type) {
      case OUT_PULSE:
        if (o->pulseUntilMs > 0 && now >= o->pulseUntilMs) {
          setOutDigital(o, 0, false);
          o->active = false;
          o->pulseUntilMs = 0;
        }
        break;
      case OUT_BLINK:
        if (o->active && o->nextToggleMs > 0 && now >= o->nextToggleMs) {
          o->phaseHigh = !o->phaseHigh;
          setOutDigital(o, 0, o->phaseHigh);
          o->nextToggleMs = now + (unsigned long)(o->phaseHigh ? o->onMs : o->offMs);
        }
        break;
      case OUT_ALT_PAIR:
        if (o->active && o->nextToggleMs > 0 && now >= o->nextToggleMs) {
          o->phaseHigh = !o->phaseHigh;
          setOutDigital(o, 0, o->phaseHigh);
          setOutDigital(o, 1, !o->phaseHigh);
          o->nextToggleMs = now + (unsigned long)(o->phaseHigh ? o->onMs : o->offMs);
        }
        break;
      case OUT_FADE: {
        int dur = o->fadeMs > 0 ? o->fadeMs : 1;
        float t = (float)(now - o->fadeStartMs) / (float)dur;
        if (t >= 1.0f) {
          o->currentBright = o->targetBright;
          o->active = false;
        } else if (t > 0) {
          o->currentBright = (int)lround(o->fadeFromBright + (o->targetBright - o->fadeFromBright) * t);
        }
        writePwmPercent(o->gpio[0], o->currentBright);
        break;
      }
      case OUT_SERVO: {
        int dur = o->travelMs > 0 ? o->travelMs : 1;
        // full 180° travel time scaled by delta
        int from = o->fadeFromBright;
        int delta = abs(o->targetDeg - from);
        int need = (int)((long)dur * delta / 180L);
        if (need < 1) need = 1;
        float t = (float)(now - o->fadeStartMs) / (float)need;
        if (t >= 1.0f) {
          o->currentDeg = o->targetDeg;
          o->active = false;
        } else if (t > 0) {
          o->currentDeg = (int)lround(from + (o->targetDeg - from) * t);
        }
        if (o->servoIx[0] >= 0) writeServoDeg(o->servoIx[0], o->currentDeg);
        break;
      }
      case OUT_LEVEL_CROSSING:
        lcAdvance(o, now);
        lcUpdateBarrierMotion(o, now);
        lcApplyLights(o, now);
        break;
      case OUT_DIGITAL_SIGNAL:
        if (o->signalFlashMask && o->nextToggleMs > 0 && now >= o->nextToggleMs) {
          o->signalFlashHigh = !o->signalFlashHigh;
          o->nextToggleMs = now + (unsigned long)o->flashMs;
          for (uint8_t p = 0; p < o->pinCount; p++) {
            bool on = (o->signalOnMask & (1u << p)) != 0;
            bool flash = (o->signalFlashMask & (1u << p)) != 0;
            setOutDigital(o, p, on && (!flash || o->signalFlashHigh));
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
  // SAMPLE A15 [A0]
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
  // TEST SERVO D9 45 | TEST PWM D5 40 | TEST SAMPLE … | TEST 42 ON|OFF
  char buf[64];
  strCopyCap(buf, sizeof(buf), rest);
  // trim
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
    int gpio = findGpioBySilk(p);
    if (gpio < 0) return;
    // find or attach temp servo
    int8_t ix = -1;
    for (uint8_t i = 0; i < gOutputCount; i++) {
      for (uint8_t j = 0; j < gOutputs[i].pinCount; j++) {
        if (gOutputs[i].gpio[j] == gpio && gOutputs[i].servoIx[j] >= 0) {
          ix = gOutputs[i].servoIx[j];
        }
      }
    }
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
  // TEST {addr} ON|OFF
  int addr = atoi(p);
  char* sp = strchr(p, ' ');
  if (!sp || addr <= 0) return;
  while (*sp == ' ') sp++;
  bool on = startsWithi(sp, "ON");
  if (!addressOwned(addr) && gOutputCount > 0) return;
  handleAccessory(addr, on);
}

static void handleLine(char* line) {
  // strip CR
  size_t n = strlen(line);
  while (n > 0 && (line[n - 1] == '\r' || line[n - 1] == '\n')) {
    line[--n] = '\0';
  }
  if (n == 0) return;

  if (startsWithi(line, "CFG ")) {
    nodeApplyCfgJson(line + 4);
    return;
  }
  if (streqi(line, "CFG?")) {
    nodeReplyCfg();
    return;
  }
  if (startsWithi(line, "TEST ")) {
    handleTest(line + 5);
    return;
  }
  if (startsWithi(line, "OUT ")) {
    // legacy: OUT {addr} ON|OFF
    handleTest(line + 4);
    return;
  }
  // {addr} ON|OFF
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
  gCfgLen = 0;
  gCfgValid = false;
  gLineLen = 0;
  clearRuntime();
  uint16_t len = 0;
  if (loadCfgIntoBuffer(gLine, NODE_MAX_LINE, &len) && len > 0) {
    gCfgLen = len;
    gCfgValid = true;
    rebuildFromCfgBuffer(gLine);
  } else {
    boardLoadDefaultPinRoles(gRoles, &gRoleCount, &gRefGpio);
    rebuildSensorsFromRoles();
  }
  gLineLen = 0;

  Stream* s = boardLink();
  if (s) {
    s->print("PanelsDCC Node ");
    s->print(boardPrefix());
    s->print(' ');
    s->print(boardType());
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
