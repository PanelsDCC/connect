/*
 * PanelsDCC Sensor Board — optical + digital inputs
 *
 * BOARD_PREFIX is chosen by the person flashing this Nano:
 *   "A", "B", ... "Z", "AA", "AB", "AC", ...
 *
 * Hardware IDs:
 *   Optical (A0..A6):  {BOARD_PREFIX}{pinIndex}     e.g. A0, A3, AA1
 *   Digital (D2..D12): {BOARD_PREFIX}D{pinNumber}   e.g. AD2, BD7, AAD12
 *   A7 = ambient reference only (never reported)
 *   D0/D1 reserved for USB serial — not used as inputs
 *   D13 has the built-in LED — not usable as INPUT_PULLUP
 *
 * Digital pins use INPUT_PULLUP: LOW (to GND) = DETECTED, HIGH = CLEAR.
 *
 * Boot: "PanelsDCC Sensor Board {prefix} READY" then a full state snapshot.
 */

const char* BOARD_PREFIX = "A";

const int REF_PIN = A7;
const int SENSOR_PINS[] = { A0, A1, A2, A3, A4, A5, A6 };
const int SENSOR_COUNT = 7;

const int DIGITAL_PINS[] = { 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 };
const int DIGITAL_COUNT = 11;

const int DELTA_THRESHOLD = 130;
const unsigned long CLEAR_HOLD_MS = 1000;

bool reportedActive[SENSOR_COUNT];
unsigned long clearSinceMs[SENSOR_COUNT];

bool digReportedActive[DIGITAL_COUNT];
unsigned long digClearSinceMs[DIGITAL_COUNT];

void printAnalogId(int channel) {
  Serial.print(BOARD_PREFIX);
  Serial.print(channel);
}

void printDigitalId(int pin) {
  Serial.print(BOARD_PREFIX);
  Serial.print('D');
  Serial.print(pin);
}

void emitAnalog(int channel, bool active) {
  printAnalogId(channel);
  Serial.println(active ? ": DETECTED" : ": CLEAR");
}

void emitDigital(int pin, bool active) {
  printDigitalId(pin);
  Serial.println(active ? ": DETECTED" : ": CLEAR");
}

void setup() {
  Serial.begin(9600);
  delay(500);

  for (int i = 0; i < DIGITAL_COUNT; i++) {
    pinMode(DIGITAL_PINS[i], INPUT_PULLUP);
  }

  Serial.print("PanelsDCC Sensor Board ");
  Serial.print(BOARD_PREFIX);
  Serial.println(" READY");

  int ref = analogRead(REF_PIN);
  unsigned long now = millis();
  for (int i = 0; i < SENSOR_COUNT; i++) {
    reportedActive[i] = (analogRead(SENSOR_PINS[i]) - ref) > DELTA_THRESHOLD;
    clearSinceMs[i] = now;
    emitAnalog(i, reportedActive[i]);
  }
  for (int i = 0; i < DIGITAL_COUNT; i++) {
    // INPUT_PULLUP: LOW = active (to ground)
    digReportedActive[i] = digitalRead(DIGITAL_PINS[i]) == LOW;
    digClearSinceMs[i] = now;
    emitDigital(DIGITAL_PINS[i], digReportedActive[i]);
  }
}

void loop() {
  int ref = analogRead(REF_PIN);
  unsigned long now = millis();

  for (int i = 0; i < SENSOR_COUNT; i++) {
    bool rawActive = (analogRead(SENSOR_PINS[i]) - ref) > DELTA_THRESHOLD;

    if (rawActive) {
      clearSinceMs[i] = 0;
      if (!reportedActive[i]) {
        emitAnalog(i, true);
        reportedActive[i] = true;
      }
    } else if (reportedActive[i]) {
      if (clearSinceMs[i] == 0) {
        clearSinceMs[i] = now;
      } else if (now - clearSinceMs[i] >= CLEAR_HOLD_MS) {
        emitAnalog(i, false);
        reportedActive[i] = false;
        clearSinceMs[i] = 0;
      }
    }
  }

  for (int i = 0; i < DIGITAL_COUNT; i++) {
    bool rawActive = digitalRead(DIGITAL_PINS[i]) == LOW;

    if (rawActive) {
      digClearSinceMs[i] = 0;
      if (!digReportedActive[i]) {
        emitDigital(DIGITAL_PINS[i], true);
        digReportedActive[i] = true;
      }
    } else if (digReportedActive[i]) {
      if (digClearSinceMs[i] == 0) {
        digClearSinceMs[i] = now;
      } else if (now - digClearSinceMs[i] >= CLEAR_HOLD_MS) {
        emitDigital(DIGITAL_PINS[i], false);
        digReportedActive[i] = false;
        digClearSinceMs[i] = 0;
      }
    }
  }

  delay(50);
}
