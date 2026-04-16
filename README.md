# PSYOP-VISR

Tactical HUD application for the EVEN Realities G2 smart glasses, running on a Pixel 9 Pro XL. Streams live camera, compass, and audio data from the phone to the G2 display via a custom BLE protocol — no Even Realities companion app required.

---

## Hardware Requirements

- **Phone:** Google Pixel 9 Pro XL (Android 14+)
- **Glasses:** EVEN Realities G2
- **Connection:** Native BLE — the phone drives the G2 directly

---

## Development Requirements

| Tool | Version |
|------|---------|
| Android Studio | Hedgehog (2023.1.1) or newer |
| Android SDK | API 34 |
| Kotlin | 1.9.0 |
| Gradle | 8.2.0 |
| ADB | Any recent version |

---

## Project Setup

### 1. Clone the repo

```bash
git clone <repo-url>
cd evenSOCOM
```

### 2. Open in Android Studio

File → Open → select the `evenSOCOM` directory. Let Gradle sync complete.

### 3. Add model assets

The following model files must be placed in `app/src/main/assets/` before building. They are not committed to the repo due to size.

| File | Purpose | Source |
|------|---------|--------|
| `efficientdet.tflite` | Object / symbol detection | [MediaPipe Model Card](https://developers.google.com/mediapipe/solutions/vision/object_detector) |
| `face_detection_short_range.tflite` | Face detection | [MediaPipe Model Card](https://developers.google.com/mediapipe/solutions/vision/face_detector) |
| `facenet.tflite` | Face identity embedding | TFLite model hub / custom trained |
| `faces_db.json` | Known-face database | Hand-curated JSON, schema below |
| `ggml-tiny.bin` | Whisper speech-to-text weights | [whisper.cpp releases](https://github.com/ggerganov/whisper.cpp) |
| `libwhisper.so` | Whisper native library (arm64) | Built from [whisper.cpp](https://github.com/ggerganov/whisper.cpp) |

`faces_db.json` schema:
```json
[
  { "name": "CALLSIGN", "embedding": [0.123, -0.456, ...] }
]
```

> Only `efficientdet.tflite` is required for basic object/symbol detection. All other assets gate their respective scan modes — missing files produce a `MODEL NOT FOUND` result on the HUD rather than crashing.

### 4. Pair the G2

Pair the G2 glasses to the Pixel via Android Bluetooth settings before first run. The app connects using the device name `EVEN G2` — no pairing code is needed after the initial system pair.

### 5. Disable the Even Realities companion app

The Even companion app (`com.even.sg`) will steal the BLE HUD session if it is running.

```bash
adb shell pm disable-user --user 0 com.even.sg
```

To re-enable it later:

```bash
adb shell pm enable com.even.sg
```

---

## Building

### Debug build (recommended for development)

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Install directly to device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Build + install in one step

```bash
./gradlew installDebug
```

---

## Running

### Launch via ADB

```bash
adb shell am start -n com.evensocom.psyopvisr/.MainActivity
```

### Required permissions

The app will prompt for these on first launch:

- **Camera** — live feed for all vision scan modes
- **Bluetooth / Bluetooth Connect / Bluetooth Scan** — G2 BLE link
- **Microphone** — live audio for Whisper transcription
- **Location (coarse)** — required by Android for BLE scanning

Grant all permissions, then restart the service if the G2 does not connect automatically.

### Logcat (development)

```bash
adb logcat -s PSYOP-VISR VisionModeController TouchpadRouter
```

---

## G2 Touchpad Controls

| Gesture | Action |
|---------|--------|
| Tap | Wake from IDLE → open scan menu / confirm selection |
| Swipe forward | Next menu item |
| Swipe backward | Previous menu item |
| Long press | Cancel / return to IDLE |

---

## Scan Modes

| Mode | Model required | Description |
|------|---------------|-------------|
| FACE SCAN | `face_detection_short_range.tflite`, `facenet.tflite`, `faces_db.json` | Detect, classify, and identify faces with distance and position |
| SYMBOL SCAN | `efficientdet.tflite` | Detect objects and annotate with tactical/cultural context |
| CULTURAL CTX | `efficientdet.tflite` | Same as symbol scan, context-focused output |
| ROOM ANALYSIS | ARCore (system) | Depth-map room and summarize spatial layout |

---

## HUD Layout

```
HDG: 247° NW   BAT: 92%
────────────────────────
> FACE SCAN
  SYMBOL SCAN
  CULTURAL CTX
  ROOM ANALYSIS
```

---

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for a full component diagram.

---

## Known Issues / Roadmap

- `face_detection_short_range.tflite`, `facenet.tflite`, and `ggml-tiny.bin` are not yet bundled — those scan modes return `MODEL NOT FOUND`
- Room analysis requires ARCore depth API; full integration pending
- Whisper native library (`libwhisper.so`) not yet linked — transcription falls back to ML Kit
- Double-tap gesture mapping unconfirmed on hardware (may arrive as two single taps)
