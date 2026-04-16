# Even Realities G2 Protocol Knowledge Base

This document serves as the definitive technical reference for the Even Realities G2 (EVEN Hub) BLE protocol, as reverse-engineered from the `g2-kit-unofficial` and firmware observation.

## 1. BLE Connectivity

The G2 glasses expose multiple services. For HUD control and command traffic, the proprietary **Service 5450** is the primary channel.

| Component | UUID |
| :--- | :--- |
| **Service (Command/Content)** | `00002760-08c2-11e1-9073-0e8ac72e5450` |
| **Characteristic (Write)** | `00002760-08c2-11e1-9073-0e8ac72e5401` (Props: WRITE) |
| **Characteristic (Notify)** | `00002760-08c2-11e1-9073-0e8ac72e5402` (Props: NOTIFY) |
| **Service (Render/Audio)** | `00002760-08c2-11e1-9073-0e8ac72e6450` |
| **Characteristic (Render Notify)** | `00002760-08c2-11e1-9073-0e8ac72e6402` |

> [!IMPORTANT]
> Some versions of the firmware or generic drivers may alias these to the Nordic UART Service (NUS) `6e40...`, but the `2760-5450` service is the direct vendor implementation.

---

## 2. Envelope Framing (The "aa 21" Protocol)

Every command sent to the glasses must be wrapped in an 8-byte proprietary envelope. 

### THE BREAKTHROUGH: 8-Byte vs. 11-Byte
Initially, the protocol was suspected to use an 11-byte header. Testing confirmed that the G2 firmware expects a **strict 8-byte header** with the following layout:

`TX: aa 21 <seq> <len> <totalFrags> <fragIdx> <sid> <flag> <pb_payload...> [crc_le]`

1.  **[0] Sync Byte 1**: `0xAA`
2.  **[1] Sync Byte 2**: `0x21`
3.  **[2] Sequence**: A unique identifier for the request group (used to match ACKs).
4.  **[3] Chunk Length**: Length of the payload in *this* fragment.
5.  **[4] Total Fragments**: Total number of chunks the payload is split into.
6.  **[5] Fragment Index**: 1-indexed count of the current chunk.
7.  **[6] SID (Service ID)**:
    *   `0x01`: App / Session Management
    *   `0xE0`: HUD Rendering / UI Commands
    *   `0x09`: Settings / Battery Status
8.  **[7] Flag**: **`0x20`** (Indicates a **REQUEST**). Using `0x00` here will often result in the glasses ignoring the command.

### CRC-16 Calculation
*   **Algorithm**: `CRC-16/CCITT-FALSE` (Poly: 0x1021, Init: 0xFFFF).
*   **Scope**: Calculated over the **Protobuf payload only** (excluding the 8-byte header).
*   **Placement**: Appended as 2 bytes in **Little-Endian (LE)** format.
*   **Constraint**: The CRC must **only** be appended to the **very last fragment** of the message. If the message fits in one fragment, it follows the payload immediately.

---

## 3. Session Handshake Sequence

To render content, the app must follow a rigid initialization flow.

### Step 1: Session Prelude (AppLaunch)
The app must send an `AppLaunch` message (`sid=0x01`) before the glasses will allow HUD commands.
*   **Payload**: Protobuf message representing `AppLaunch { type: 2 }`.
*   **Magic Number**: Must be in the range `100 - 255`.

### Step 2: Create HUD Container
The glasses use a "Container/Widget" model. You must create a page before updating it.
*   **Command**: `sid=0xE0`, Cmd=0 (`CreateStartUpPage`).
*   **Structure**: Contains a `ListObject` with a unique `ContainerName`.

### Step 3: Heartbeat Loop
Once the HUD is active, a heartbeat must be sent every ~5 seconds.
*   **Command**: `sid=0xE0`, Cmd=12 (`HeartBeatPacket`).
*   **Failure**: If heartbeats stop, the glasses will eventually time out and revert to the default clock/Even Hub screen.

---

## 4. UI Rendering (EvenHub Protobuf)

HUD content updates use `Cmd=7` (`RebuildPageContainer`).

*   **Field 1 (Cmd)**: `7`
*   **Field 2 (Magic)**: Incremental counter.
*   **Field 7 (RebuildContainer)**:
    *   `ContainerName`: Must match the name used in the `Create` command.
    *   `TextObject`: Field 12 carries the string content.

---

## 5. Known Magic Constants & Constraints
*   **Magic Random**: The firmware silently ignores any "MagicRandom" value $\ge 256$. Always keep your counter in the `100-255` range to avoid silent drops.
*   **MTU**: Negotiated MTU is typically 247 bytes. This allows for a maximum chunk payload of roughly 232 bytes (MTU - 3 GATT overhead - 8 Envelope overhead - 2 CRC).
*   **Left vs. Right**: Generally, the RIGHT temple is the primary command receiver. The LEFT temple acts as a mirror/receiver for specific streaming data (like audio).

---

## 6. Tactical Engine Architecture

The `psyopvisr` implementation extends the base protocol with several tactical features.

### A. Dashboard UI Layout
Content is rendered using a "Rebuild" command (`sid=0xE0, Cmd=7`) with a specific text layout:
```text
HDG: 180° S   BAT: 85%
────────────────────────
SCAN: [Person: Civilian]
> Hostile movement detected.
```
*   **Separator**: The dash line `────────────────────────` acts as a visual break between hardware status and tactical alerts.

### B. Compass/Heading
*   **Source**: Phone `SensorManager` (Accelerometer + Magnetometer).
*   **Optimization**: Updates are capped to a >2° change threshold to prevent BLE congestion.

### C. Battery Monitoring
*   **Query**: Sent via `sid=0x09` (G2SettingPackage) using `DeviceReceiveRequest`.
*   **Parsing**: The response is a length-delimited Protobuf. The battery percentage is found in `field 10` (deviceReceiveRequestFromApp) -> `field 1` (u32 battery).

### D. Visual Inference (CameraX)
*   **Implementation**: Utilizes `LifecycleService` to bind a background `ImageAnalysis` use case.
*   **Pipeline**: Camera Frame (640x480) -> MediaPipe ObjectDetector (EfficientDet-Lite0) -> Cultural Context DB -> HUD Update.
*   **Latency**: The pipeline is asynchronous; detection results don't block the BLE heartbeat or Compass loops.
