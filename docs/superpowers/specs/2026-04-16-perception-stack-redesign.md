# PSYOP-VISR Perception Stack Redesign
**Date:** 2026-04-16  
**Status:** Approved — proceeding to implementation

---

## Goals
Improve symbol/sign detection, room analysis, and cultural context reasoning to produce operationally useful outputs for demo and concept validation.

---

## Architecture — Approach A (Hybrid Camera)

CameraX handles FACE_SCAN, SYMBOL_SCAN, CULTURAL_CONTEXT.  
ARCore (Shared Camera, ultrawide) hot-swaps in for ROOM_ANALYSIS.  
MobileCLIPClassifier is a shared TFLite module used by both SymbolRecognitionEngine and SceneContextInferenceEngine.

### Mode-switch flow (ROOM_ANALYSIS entry)
1. VisionModeController fires onEnterRoomMode
2. TacticalInferenceEngine unbinds CameraX analysis use-case
3. ArCoreRoomAnalyzer starts on ultrawide camera ID
4. SpatialAccumulator resets, begins accumulating
5. User walks — frames + pose + depth accumulate
6. Scan ends via double-tap (user preference B) or auto-timer (user preference C)
7. Summary pushed to G2 HUD; SpatialMapView freezes
8. ArCoreRoomAnalyzer stops, CameraX rebinds

---

## New Files
- `vision/MobileCLIPClassifier.kt` — shared TFLite zero-shot classifier
- `vision/SceneContextInferenceEngine.kt` — replaces cultural context vision layer
- `vision/SpatialAccumulator.kt` — multi-frame spatial data accumulator
- `vision/ArCoreRoomAnalyzer.kt` — ARCore session manager (ultrawide)
- `ui/SpatialMapView.kt` — bird's-eye radar scatter view for room mode

## Modified Files
- `vision/SymbolRecognitionEngine.kt` — 5-stage sign recognition pipeline
- `vision/RoomAnalysisEngine.kt` — integrates ArCoreRoomAnalyzer + SpatialAccumulator
- `vision/VisualizationFrame.kt` — adds RoomSpatialFrame + SpatialObject types
- `ui/OverlayView.kt` — updated per-mode rendering
- `inference/TacticalInferenceEngine.kt` — wires new engines, handles room mode camera swap
- `vision/VisionModeController.kt` — scan-end preference (B/C), room mode signals
- `MainActivity.kt` — adds SpatialMapView, room mode preference toggle
- `app/build.gradle.kts` — no new dependencies needed (ARCore already present)

---

## Symbol Scan Pipeline (5 Stages)
1. EfficientDet region detection (existing model)
2. Per-crop OCR via ML Kit (not full-frame)
3. MobileCLIP zero-shot sign-type classification per crop
4. Signal fusion: EfficientDet label + OCR text + CLIP type + keyword classifier
5. Ranked output → SymbolResult with structured fields

**MobileCLIP sign prompts:**
`["warning sign", "hazard placard", "restricted area sign", "exit sign", "directional sign", "religious symbol", "military insignia", "biohazard symbol", "no entry sign", "informational sign", "security camera", "checkpoint sign"]`

---

## Cultural Context → SceneContextInferenceEngine
1. EfficientDet full-frame object inventory
2. Full-frame OCR sign text inventory
3. MobileCLIP full-scene classification against context categories
4. Heuristic rule engine: object_set + sign_text + scene_category → inference
5. Structured output: observed_facts + inferred_context + suggested_action

**MobileCLIP scene prompts:**
`["religious site", "military facility", "medical area", "commercial market", "residential area", "controlled access facility", "active conflict zone", "transportation hub", "government building", "educational institution"]`

---

## Room Analysis — ARCore + Spatial Accumulator
- ArCoreRoomAnalyzer: manages ARCore session on ultrawide camera, provides Frame (depth + pose + bitmap)
- SpatialAccumulator: accumulates detections across frames with pose correction, builds bird's-eye object map
- RoomAnalysisEngine: orchestrates analyzer + accumulator, derives room estimates
- Scan-end: user preference stored in SharedPreferences (B=double-tap, C=auto 30s)

---

## Android UI
- OverlayView: mode-specific bounding boxes with confidence + explanation overlays
- SpatialMapView: radar-style bird's-eye scatter, dark bg, colored nodes, range rings, exit markers, scan progress bar
- Room mode: SpatialMapView replaces OverlayView when ROOM_ANALYSIS active
- Mode chip already exists; scan state panel shows progress + confidence

---

## G2 HUD Output (concise)
- Symbol: `"WARNING SIGN — AUTH PERSONNEL ONLY"` / `"HAZARD PLACARD [!]"`
- Cultural: `"possible place of worship"` / `"restricted access area"`  
- Room: `"two exits detected"` / `"dense clutter left side"` / `"small enclosed room"`
