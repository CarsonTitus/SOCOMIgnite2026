# PSYOP-VISR Architecture

```mermaid
flowchart TD
    subgraph GLASS["G2 Smart Glasses"]
        G2D["Waveguide Display"]
        TP["Touchpad"]
        MIC["Microphone"]
    end

    subgraph BLE["BLE Transport — aa-12 Envelope"]
        BLEM["EvenG2NativeBleManager\nGATT 6200 / 6201 / 6202 / 6402"]
    end

    subgraph SVC["EvenG2TacticalService (Foreground Service)"]
        SESS["Session Manager\nAppLaunch · Heartbeat · Re-assertion"]
        ROUTE["sid Router\n0xe0 gestures  0x09 battery  0x01 session"]
        HUD["HUD Renderer\nCmd=7 RebuildText protobuf"]
        BAT["Battery Monitor"]
    end

    subgraph INPUT["Input Layer"]
        TPR["TouchpadRouter\nClickEvent field3  TextEvent field2\nGestureType enum"]
        VMC["VisionModeController\nState machine\nIDLE → MENU → SCAN → RESULT"]
        VOICE["VoiceCommandEngine\nML Kit hotword → mode activation"]
        COMPASS["CompassEngine\nIMU heading string"]
    end

    subgraph VISION["On-Device ML  — Vision Layer"]
        CAM["CameraX\n640x480 back camera"]
        TIE["TacticalInferenceEngine\nImageAnalysis.Analyzer\nmode-gated frame dispatch"]
        FACE["FaceDetectionEngine\nMediaPipe ShortRange\nFaceNet embedding + faces_db.json"]
        SYM["SymbolRecognitionEngine\nEfficientDet TFLite\nPersonClassifier  CulturalContext lookup"]
        ROOM["RoomAnalysisEngine\nARCore Depth API\nspatial summary"]
        WHISPER["WhisperTranscriber\nggml-tiny.bin\nLC3 decode → speech-to-text"]
    end

    subgraph DATA["Data Layer"]
        CULT["CulturalContextEngine\nRoom SQLite DB\nsymbol → context + alert level"]
        FACEDB["faces_db.json\nknown-face embeddings"]
    end

    TP -->|"BLE notify sid=0xe0"| BLEM
    MIC -->|"BLE notify 6402 LC3"| BLEM
    BLEM --> ROUTE
    ROUTE -->|"sid=0xe0"| TPR
    ROUTE -->|"sid=0x09"| BAT
    BAT --> HUD
    TPR --> VMC
    VOICE --> VMC
    COMPASS --> HUD
    VMC -->|"activateMode"| TIE
    CAM --> TIE
    TIE --> FACE
    TIE --> SYM
    TIE --> ROOM
    BLEM -->|"audio frames"| WHISPER
    FACE -->|"ScanResult"| VMC
    SYM -->|"ScanResult"| VMC
    ROOM -->|"ScanResult"| VMC
    WHISPER -->|"transcript"| HUD
    SYM <-->|"label lookup"| CULT
    FACE <-->|"embedding match"| FACEDB
    VMC -->|"pushHudContent"| HUD
    SESS --> HUD
    HUD -->|"BLE write sid=0xe0 Cmd=7"| BLEM
    BLEM -->|"BLE GATT write"| G2D
```

## Layer Summary

| Layer | Components | Responsibility |
|-------|-----------|---------------|
| **Transport** | `EvenG2NativeBleManager` | Raw BLE GATT, aa-12 envelope framing, CRC, fragmentation |
| **Service** | `EvenG2TacticalService` | Session lifecycle, sid routing, HUD protobuf rendering, battery |
| **Input** | `TouchpadRouter`, `VisionModeController`, `VoiceCommandEngine`, `CompassEngine` | Gesture parsing, mode state machine, voice shortcuts, heading |
| **Vision** | `TacticalInferenceEngine`, `FaceDetectionEngine`, `SymbolRecognitionEngine`, `RoomAnalysisEngine`, `WhisperTranscriber` | On-device ML, frame dispatch, audio transcription |
| **Data** | `CulturalContextEngine`, `faces_db.json` | Tactical context DB, known-face embeddings |
| **Display** | G2 waveguide | Renders HUD pushed via BLE Cmd=7 RebuildText |

## Data Flow — Tap to Scan Result

```
User tap on G2 touchpad
  → BLE notify sid=0xe0 (ClickEvent field3, clickType=1)
  → TouchpadRouter.parseEvenHubEvent() → GestureType.TAP
  → VisionModeController.onGestureEvent(TAP)
  → [IDLE] enterMenu() → pushHudContent(menu)
  → [MENU] TAP → startCountdown(selectedMode) → isScanActive=true
  → TacticalInferenceEngine dispatches frames to selected engine
  → Engine returns ScanResult (FaceResult / SymbolResult / RoomResult)
  → VisionModeController.onScanFrame(result) → showResult() → pushHudContent(result)
  → EvenG2TacticalService.pushHudContent() → Cmd=7 RebuildText → BLE write → G2 display
```
