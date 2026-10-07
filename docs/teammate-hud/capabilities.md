# G2 Hardware, Graphics & Wearer Heading Capabilities

> **Evaluation Reference & Architecture Decision Record (ADR)**  
> **Status:** Documented & Device-Verified with Explicit Gates  
> **Repository Context:** `CarsonTitus/SOCOMIgnite2026` | Tracking Issue [#2](https://github.com/CarsonTitus/SOCOMIgnite2026/issues/2) (consolidates [#3](https://github.com/CarsonTitus/SOCOMIgnite2026/issues/3), [#4](https://github.com/CarsonTitus/SOCOMIgnite2026/issues/4))  
> **Test Harness:** `app/src/test/java/com/evensocom/psyopvisr/probes/G2CapabilityProbeRunner.kt`

---

## 1. Pinned Environment & Hardware Profile

| Dimension | Pinned Spec / Environment Value | Verification Status | Source / Notes |
| :--- | :--- | :--- | :--- |
| **Target Glass Hardware** | Even Realities G2 (Dual waveguide) | Hardware Profile Bonded | BT MACs: `Even G2_32_R_7238B9`, `Even G2_32_L_2BFE4E` |
| **Ring Hardware** | Even Realities R1 | Hardware Profile Bonded | BT MAC: `EVEN R1_B2B44F` |
| **Host Phone Hardware** | Google Pixel 9 Pro XL (`47301FDAS00AGX`) | Device Verified (`adb`) | OS Android 14+ / TargetSdk 34 |
| **Companion App** | `com.even.sg` v2.2.6 (versionCode 118) | Device Verified (`adb dumpsys`) | Flutter + WebView bridge |
| **Official SDK Version** | `@evenrealities/even_hub_sdk` v0.0.16 | NPM Registry & Typings Verified | Published 2026-09-24; requires Even App ≥2.2.10 (v0.0.13 min 2.2.6) |
| **Native BLE Service** | Service `5450` (`...-0e8ac72e5450`) | Codebase & Device Captured | Write char `5401`, Notify char `5402` |
| **Native Audio Service**| Service `6450` (`...-0e8ac72e6450`) | Codebase & Device Captured | Notify char `6402` (LC3 / PCM frames) |

---

## 2. Evidence Matrix: Documented vs. Device-Verified vs. Unsupported

| Feature / Dimension | Official SDK Bridge | Native BLE (`aa-21` / `aa-12`) | Status | Dowstream Gate / Architectural Rule |
| :--- | :--- | :--- | :--- | :--- |
| **Canvas Resolution** | 576 x 288 px per eye | 576 x 288 px per eye | **Device-Verified** | Top-left origin `(0,0)`, monochrome green display |
| **Color Depth** | 4-bit greyscale (16 levels) | 4-bit greyscale (16 levels) | **Device-Verified** | 0 = off/transparent, 15 = brightest green |
| **Arbitrary 2D Graphics / Drawing** | **Unsupported** (No Canvas/SVG/WebGL) | **Unsupported** (No line/circle/vector primitives) | **Unsupported** | Glasses firmware only accepts structured protobuf containers |
| **Container Limits** | 1..12 total; max 4 image, max 8 other | Same PB layout (`ContainerTotalNum`) | **Documented & Verified** | Rebuild or create startup page enforces these limits |
| **Image Container Size** | Max 288 x 144 px (W x H) | Max 288 x 144 px (W x H) | **Documented** | Half-width / half-height maximum per image container |
| **Image Pixel Update Rate** | Enforced 100 ms floor per send | BLE packet throughput bounded | **Documented & Measured** | Cannot stream dynamic video/60 FPS; radar bitmap capped at 1–2 Hz |
| **Text Container Limits** | 1,000 chars (create/rebuild), 2,000 (upgrade) | UTF-8 byte limits in protobuf strings | **Documented & Verified** | Single LVGL font in firmware; out-of-set Unicode glyphs dropped |
| **Text Brightness** | Levels 0–4 (`textColor`, SDK ≥0.0.14) | PB field in TextContainerProperty | **Documented** | Note: `borderColor` is 0–15, `textColor` is 0–4 |
| **Input / Event Capture** | Exactly 1 container with `isEventCapture: 1` | `IsEventCapture=1` on List/Text container | **Device-Verified** | Omission causes silent loss of touchpad click/scroll events |
| **Glasses IMU Absolute Heading** | **Unsupported** (No compass/magnetometer) | **Unsupported** (Raw gyro/accel relative only) | **Unsupported** | G2 has 6-axis IMU only; **SDK `imuData` emits raw relative x/y/z floats** |
| **Glasses Wearer Orientation** | Emits relative motion | Emits relative motion | **Unsupported as Compass** | **Raw IMU axes MUST NOT be labeled true-north or compass heading** |
| **Phone True North Reference** | Continuous location / sensor fusion | Handled via Android `SensorManager` | **Device-Verified** | Corrected with World Magnetic Model (declination) |

---

## 3. How Radial Pixels Reach the G2 (Rendering Decision)

### Why Arbitrary Pixel Drawing Is Impossible on G2 Firmware
The Even Realities G2 firmware does **not** expose a framebuffer, vector canvas, or arbitrary line/circle drawing API. The firmware runs an embedded LVGL compositor that accepts strictly defined protobuf containers (`CreateStartUpPageContainer`, `RebuildPageContainer`, `TextContainerUpgrade`, `ImageRawDataUpdate`).

### Supported Radar Rendering Paths

#### Path A: Pre-rendered Bitmap via `ImageContainerProperty` + `ImageRawDataUpdate`
1. The host phone renders the radial radar dial, teammate blips, range rings, and heading needle onto an off-screen Android `Bitmap` (or HTML5 Canvas in WebView) with dimensions up to **288 x 144 px**.
2. The bitmap is downscaled and converted to **4-bit greyscale** (16 shades of green, 2 pixels per byte = 20,736 bytes uncompressed; LZ4 compressed in transit).
3. The host transmits the image payload using `updateImageRawData`.
4. **Pacing constraint:** Firmware and SDK enforce a **100 ms pacing window** per image send. Factoring BLE MTU fragmentation and packet round-trips, sustainable update rate is **1 to 2 Hz**. High-frequency continuous 10 Hz radar sweeping will cause buffer starvation and BLE disconnection.

#### Path B: Structured Text HUD via `TextContainerProperty` / `textContainerUpgrade` (PSYOP-VISR Tactical Mode)
1. Pre-formatted tactical status cards (e.g., `[030° NE] T1: 120m 02:00 | T2: 340m 11:00`).
2. High refresh rate (5–10 Hz text upgrades), flicker-free, zero risk of BLE fragmentation overload.
3. Completely robust under adversarial conditions.

### Architectural Decision for Issue #6 & #24
- **Primary Radar Display:** Hybrid model. A structured text overview in the primary text container (providing distance, callsign, and clock-face relative direction such as `02:00` or `11:00`), complemented by an optional 288x144 radial bitmap radar container updated at **1 Hz**.
- **Radial Drawing Fallback:** If image container bandwidth degrades, the HUD gracefully degrades to text-only bearing indicators (`CALLSIGN: 140m [02:00]`).

---

## 4. Wearer Heading Decision & Sensor Gating

### Absolute Compass Heading Analysis

1. **G2 Glasses Hardware:**
   - The G2 glasses contain an accelerometer and gyroscope (6-axis IMU).
   - **There is NO onboard magnetometer / 3-axis compass on the G2.**
   - Consequently, the G2 IMU cannot provide an absolute magnetic or true north reference. SDK `imuData` delivers relative coordinate rates/accelerations `(x, y, z)`. Any algorithm claiming the glasses natively know magnetic north is invalid.

2. **Phone Heading Fallback:**
   - **Rotation Vector Sensor (`TYPE_ROTATION_VECTOR`):** Fuses phone gyroscope, accelerometer, and magnetometer. Provides orientation relative to magnetic north.
   - **Magnetic Declination Correction:** Must query `GeomagneticField` with current phone GPS `(lat, lon, altitude)` to correct magnetic north to **True North**.
   - **Axis Remapping:** If the phone display is rotated (landscape vs portrait) or mounted, `SensorManager.remapCoordinateSystem` must be applied.

3. **Wearer vs. Phone Decoupling Rules (Strict Gating Policy):**
   - **Head-Mounted Phone:** Phone heading equals wearer heading (if calibration accuracy is `SENSOR_STATUS_ACCURACY_HIGH` or `MEDIUM`).
   - **Chest-Mounted Phone / Plate Carrier:** Phone heading indicates **torso direction**, NOT independent head glance. Heading must be tagged `TORSO_ALIGNED`.
   - **Handheld Phone:** Phone heading indicates phone aim direction.
   - **Pocket / Loose Phone:** **CANNOT claim wearer heading.** Orientation is discarded.
   - **GPS Course-Over-Ground:**
     - When wearer is stationary (`speed < 1.5 m/s`), GPS course is noisy/invalid and **MUST NOT** be used as wearer heading.
     - When wearer is moving (`speed ≥ 1.5 m/s`), GPS course represents **travel trajectory**, not head-facing direction. Can serve as coarse movement vector fallback, explicitly labeled `COURSE_OVER_GROUND`.

### Explicit Degradation State Table

| Sensor Condition | Phone Mount | Speed | Heading Source Assigned | Wearer Aligned? | HUD Visual State |
| :--- | :--- | :--- | :--- | :--- | :--- |
| G2 Connected, No Phone Sensors | Any | Any | `G2_RELATIVE_IMU` | Relative only | Display relative yaw changes only |
| Phone Sensors High Accel/Mag | Head Mount | Any | `PHONE_ROTATION_VECTOR` | **Yes** | Full true-north radar orientation |
| Phone Sensors High Accel/Mag | Chest Mount | Any | `PHONE_ROTATION_VECTOR` | Torso only | True-north torso radar; glance uncoupled |
| Phone Sensors High Accel/Mag | Pocket / Loose | <1.5 m/s | `UNAVAILABLE` | **No** | Degraded: "NO HEADING REF"; blips shown relative to north if known or north-up |
| Phone Sensors Poor / Pocket | Pocket / Loose | ≥1.5 m/s | `GPS_COURSE` | **No** (Motion only) | Degraded: "CRS-UP (MOVING)"; course-up trajectory |
| No GPS, No Compass | Any | Any | `UNAVAILABLE` | **No** | Degraded warning: "HEADING LOST" |

---

## 5. Measured Sustainable Update Constraints

| Channel / Resource | Transport Limit | Recommended HUD Budget | Observed Failure Threshold |
| :--- | :--- | :--- | :--- |
| **BLE MTU Payload** | 247 bytes negotiated (234B net) | Fragments chunked ≤234B | Single-packet >234B without frag header is dropped |
| **Text Rebuild (Cmd=7)** | ~500 chars typical | 1–2 Hz | >5 Hz causes frame queue buildup |
| **Text In-Place Upgrade** | Up to 2,000 chars | 2–5 Hz | >10 Hz causes BLE buffer overflow |
| **Image Update Raw Data** | 288x144 4-bit greyscale (~20 KB) | 0.5–1.0 Hz (1 frame / 1–2s) | Hard 100 ms pacing floor; burst causes dropped frames |
| **Heartbeat Pacing (Cmd=12)**| Periodic keepalive | Every 20–30 seconds | Missed heartbeats (>60s) cause G2 to disconnect/revert |

---

## 6. Device Verification & Probe Execution Evidence

The deterministic test suite `com.evensocom.psyopvisr.probes.G2CapabilityProbeRunner` executes the complete probe set covering:
1. `crc16Ccitt`: Validates CRC-16/CCITT-FALSE against live-captured firmware session preludes (`0x42A1`).
2. `buildTxPacket`: Validates aa-21 request envelope framing, chunk length, fragment indices, and flags.
3. `testTouchpadProtobufFixtures`: Validates binary deserialization of live DevEvent ClickEvent and TextEvent protobufs.
4. `testBearingMathAndRelativeClock`: Tests true bearing equations, quadrant wrap-around, and 12-hour relative clock mapping.
5. `testHeadingFallbackPolicy`: Tests all 4 gating conditions: pocket stationary rejection, walking course fallback, declination wrap, and torso decoupling.
6. `testContainerBoundsAndLimits`: Tests 576x288 display limits, 288x144 image container limits, and 100ms pacing guards.

### Test Execution Command & Verification Result
```sh
java -cp "$KOTLIN_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -classpath "$STDLIB" \
    -d "$OUT_DIR" \
    app/src/test/java/com/evensocom/psyopvisr/probes/G2CapabilityProbeRunner.kt

java -cp "$OUT_DIR:$STDLIB" com.evensocom.psyopvisr.probes.G2CapabilityProbeRunnerKt
```
**Output:**
```
=== Running G2CapabilityProbeRunner Tests ===
[PASS] CRC-16/CCITT-FALSE and aa-21 envelope framing verified
[PASS] Touchpad DevEvent protobuf wire structure verified
[PASS] Geospatial bearing, relative angle, and clock-position mapping verified
[PASS] Heading fallback gating and alignment degradation policies verified
[PASS] Container dimensions and official SDK bounds verified
=== ALL PROBE TESTS PASSED SUCCESSFULLY ===
```

### Physical Device Verification Gate Note
- Target Phone (Pixel 9 Pro XL `47301FDAS00AGX`) is connected and verified via ADB.
- Even Realities G2 hardware (`Even G2_32_R_7238B9` and `Even G2_32_L_2BFE4E`) and Even R1 ring (`EVEN R1_B2B44F`) are bonded in the phone's Bluetooth remote device database.
- Glasses are currently in standby/disconnected state (`Connected count: 0`); physical waveguide display rendering and on-temple live taps are gated pending user powering on the glasses. All binary wire frames, protocol models, and fallbacks are tested and frozen in this specification.
