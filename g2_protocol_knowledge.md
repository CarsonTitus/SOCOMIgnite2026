# Even Realities G2 Protocol Knowledge Base

This document is the definitive technical reference for the Even Realities G2 (EVEN Hub) BLE protocol, as reverse-engineered from `g2-kit-unofficial`, firmware observation, and live packet capture during PSYOP-VISR development.

---

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
> Some firmware versions alias these to the Nordic UART Service (NUS) `6e40...`, but the `2760-5450` service is the direct vendor implementation. Always connect to `5450`.

---

## 2. Envelope Framing

### TX Envelope (Phone → G2): Sync `aa 21`

Every command sent to the glasses must be wrapped in an 8-byte proprietary header:

`aa 21 <seq> <len> <totalFrags> <fragIdx> <sid> <flag> <pb_payload...> [crc_le]`

| Byte | Field | Notes |
|------|-------|-------|
| 0 | Sync 1 | `0xAA` always |
| 1 | Sync 2 | `0x21` always |
| 2 | Sequence | Unique request group ID — used to match ACKs |
| 3 | Chunk Length | Length of payload in **this** fragment |
| 4 | Total Fragments | Total chunks this message is split into |
| 5 | Fragment Index | 1-indexed |
| 6 | SID | Service ID — see table below |
| 7 | Flag | `0x20` = REQUEST. Using `0x00` causes silent ignoring |

### RX Envelope (G2 → Phone): Sync `aa 12`

Notifications arriving from the G2 use a **different sync byte** (`0x12`, not `0x21`):

`aa 12 <seq> <len> <totalFrags> <fragIdx> <sid> <flag> <pb_payload...> [crc_le]`

The header layout is identical — only the second sync byte differs. When parsing RX packets:
- Check `data[0] == 0xAA && data[1] == 0x12`
- `sid = data[6]`
- `flag = data[7]`
- Protobuf payload = `data[8 .. 8 + chunkLen - 2]` (strip the 2-byte CRC at the end)

### CRC-16

- **Algorithm**: CRC-16/CCITT-FALSE (Poly: `0x1021`, Init: `0xFFFF`)
- **Scope**: Calculated over the **protobuf payload only** (not the 8-byte header)
- **Placement**: Appended as 2 bytes **little-endian** at the end of the **last fragment only**

---

## 3. SID Reference Table

| SID | Direction | Name | Purpose |
|-----|-----------|------|---------|
| `0x01` | TX/RX | AppMgmt | Session handshake (AppLaunch, AppConnect steal detection) |
| `0x09` | TX/RX | G2Settings | Battery query and spontaneous settings push |
| `0x0d` | RX | TouchpadRaw | Raw physical touchpad state-change events (Channel A) |
| `0x80` | RX | UxSettings | ACK responses to 0x09 queries — does NOT carry battery data |
| `0xe0` | TX/RX | EvenHub | All HUD rendering + gesture events (authoritative channel) |

### Flag values observed on RX

| Flag | Meaning |
|------|---------|
| `0x01` | EvenHub gesture event (tap, swipe) — `IsEventCapture` fire |
| `0x06` | EvenHub event variant (observed on some gesture acks) |
| `0x00` | ACK / heartbeat ack / settings response |

---

## 4. Session Handshake

The G2 requires a strict initialization sequence before it will accept HUD commands.

### Step 1 — AppLaunch (`sid=0x01`)

Send this exact protobuf payload (captured from firmware — do NOT reconstruct from schema):
```
08 02 10 9c 01 22 0a 1a 08 12 06 12 04 08 00 10 00
```
Wrap in an `aa 21` envelope with `sid=0x01`, `flag=0x20`.

### Step 2 — CreateStartUpPage (`sid=0xe0`, Cmd=0)

Creates the HUD container. Key fields in the `ListContainerProperty`:

```
evenhub_main_msg_ctx {
  Cmd: 0                    // field 1
  MagicRandom: <magic>      // field 2
  CreateMessage {           // field 3
    ContainerTotalNum: 1    // field 1
    ListObject {            // field 2
      Width: 280            // field 3
      Height: 130           // field 4
      ContainerID: 1        // field 9
      ContainerName: "visr" // field 10  ← ≤14 chars
      ItemContainer {       // field 11
        ItemCount: 1        // field 1
        IsItemSelectBorderEn: 1  // field 3
        ItemName: ["..."]   // field 4
      }
      IsEventCapture: 1     // field 12  ← REQUIRED for touchpad events
    }
    widgetId: 10000         // field 5
  }
}
```

> [!IMPORTANT]
> `IsEventCapture: 1` **must** be set on both the initial `CreateStartUpPage` (field 12 of ListObject) and every subsequent `RebuildPageContainer` (field 11 of TextObject). Without it, the G2 will not fire gesture events to the app.

### Step 3 — Heartbeat Loop (`sid=0xe0`, Cmd=12)

Send every ~5 seconds or the G2 reverts to its default clock screen:
```
evenhub_main_msg_ctx {
  Cmd: 12           // field 1
  MagicRandom: M    // field 2
  HeartBeatPacket { // field 14
    Cnt: 0          // field 1
  }
}
```

### Session Theft Detection

When the Even Realities companion app (`com.even.sg`) grabs the session, the G2 sends an `AppConnect` packet back to notify all listeners:

- `sid=0x01`, protobuf: `field1 (tag 0x08) = Cmd = 3`
- Re-assert by re-running the full handshake (AppLaunch → CreateStartUpPage)
- Use an `AtomicLong` debounce (3 s) — multiple threads can receive this simultaneously
- Cancel and restart heartbeat/battery loops on re-assertion to prevent duplicates

---

## 5. HUD Rendering (`sid=0xe0`, Cmd=7)

`RebuildPageContainer` replaces the entire content of a named container:

```
evenhub_main_msg_ctx {
  Cmd: 7                      // field 1
  MagicRandom: M              // field 2
  RebuildContainer {          // field 7
    ContainerTotalNum: 1      // field 1
    TextObject {              // field 3
      Width: 576              // field 3
      Height: 288             // field 4
      ContainerID: 1          // field 9
      ContainerName: "visr"   // field 10
      IsEventCapture: 1       // field 11  ← required every time
      Content: "<text>"       // field 12
    }
  }
}
```

- `ContainerName` must exactly match what was used in CreateStartUpPage
- Max content length: ~900 bytes before truncation is safe
- Newlines (`\n`) are supported in `Content`

---

## 6. Magic Number Constraints

- **Range**: `100–255` only. Values ≥ 256 are **silently dropped** by firmware.
- **Cycle**: Wrap back to 100 after 255.
- **Scope**: The magic in the inner protobuf (`field 2`) and the magic used as the envelope sequence byte should be consistent but can differ — the G2 matches ACKs by sequence, not inner magic.
- **Thread safety**: If multiple coroutines call `nextMagic()` concurrently, use `@Volatile` at minimum. A proper `AtomicInteger` is safer.

---

## 7. Touchpad Gesture Protocol

Two channels carry gesture data. **Channel B is authoritative.**

### Channel B — `sid=0xe0`, `flag=0x01`, `Cmd=2` (primary)

Fired by the G2 when a text container with `IsEventCapture=1` receives input.

**Outer structure:**
```
evenhub_main_msg_ctx {
  Cmd: 2        // field 1 — OS_NOITY_EVENT_TO_APP_PACKET
  MagicRandom   // field 2
  DevEvent {    // field 13 (wire type 2)
    ...
  }
}
```

**DevEvent inner structure — two distinct sub-message types:**

#### Swipe events → `DevEvent.field2` = TextEvent
```
TextEvent {
  EventType: <n>   // field 3 (varint) — OsEventTypeList
}
```
OsEventTypeList:
| Value | Meaning | GestureType |
|-------|---------|-------------|
| 0 | CLICK_EVENT | TAP *(but see ClickEvent below — taps use field3 not field2)* |
| 1 | SCROLL_TOP_EVENT | SWIPE_FORWARD |
| 2 | SCROLL_BOTTOM_EVENT | SWIPE_BACKWARD |
| 3 | DOUBLE_CLICK_EVENT | DOUBLE_TAP |

> [!IMPORTANT]
> **Protobuf default omission**: EventType=0 (CLICK_EVENT) is the protobuf default for a varint field and is **never encoded on the wire**. A TextEvent with EventType=0 arrives with NO field3 at all. Treat absent field3 (`parseItemEventType` returns -1) as EventType=0.

#### Tap events → `DevEvent.field3` = ClickEvent ← **confirmed from live capture**
```
ClickEvent {       // field 3 of DevEvent (wire type 2)
  clickType: <n>   // field 2 (varint)
}
```
| clickType | Meaning |
|-----------|---------|
| 1 | Single tap → TAP |
| 2 | Double tap → DOUBLE_TAP *(inferred — not yet confirmed from hardware)* |

**Live tap packet (confirmed):** `08 02 6a 04 1a 02 10 01`
- Decoded: Cmd=2, DevEvent(4B) = ClickEvent.field2=1 → TAP

**Parse strategy:**
1. Find `field1=Cmd` in outer message — must be 2
2. Find `field13=DevEvent` — extract sub-bytes
3. In DevEvent: handle `field2` (TextEvent/swipe) AND `field3` (ClickEvent/tap) separately
4. For field2: extract field3 of TextEvent → OsEventTypeList
5. For field3: extract field2 of ClickEvent → clickType mapping

### Channel A — `sid=0x0d` (fallback/diagnostic only)

Raw physical touchpad state-change events. Not used for swipes — only confirms tap.

Protobuf: `{ field1: contextId, field3: { field1: contextId(0xE0), field2: eventCode } }`

| eventCode | Meaning |
|-----------|---------|
| 0 | Touch start — ignore |
| 34 (0x22) | Single tap — confirmed |
| Others | Unknown — log for calibration, do not map |

---

## 8. Battery / Settings Protocol (`sid=0x09`)

### Query format

```
G2SettingPackage {
  commandId: 2                    // field 1 — DeviceReceiveRequest
  magicRandom: <magic>            // field 2
  deviceReceiveRequestFromApp {   // field 4 (wire 2)  ← NOT field 10
    settingInfoType: 1            // field 1 — APP_REQUIRE_BASIC_SETTING
  }
}
```

### Response format (spontaneous push or query response)

The G2 pushes this spontaneously on connect AND in response to queries:
```
G2SettingPackage {
  commandId: 2
  magicRandom: <echo>
  deviceReceiveRequestFromApp {   // field 4
    ...
    battery: <0-100>              // field 12  ← NOT field 1
    chargingStatus: <0|1>        // field 13
  }
}
```

> [!WARNING]
> Earlier docs incorrectly stated battery at `field10 → field1`. **Correct location: `field4 → field12`.**
> Confirmed from live 43-byte spontaneous push: `60 61` = field12 (tag `0x60` = field12 varint) = `0x61` = 97%.

### `sid=0x80` — UxSettings ACK

The G2 sends ACKs for 0x09 queries on `sid=0x80` with `commandId=6`, empty field5. These do **not** carry battery data — ignore for battery parsing.

---

## 9. Audio (`6402` Characteristic)

- LC3-encoded audio frames stream from the G2 microphone on the **6402 characteristic** (render/audio service)
- Enable with mic-enable command: `sid=0x01`, payload `0x0F`
- Each BLE notification = one LC3 frame
- Decode LC3 → PCM → feed to Whisper (`ggml-tiny.bin`) for speech-to-text

---

## 10. MTU and Fragmentation

- Negotiated MTU: typically **247 bytes**
- Max single-fragment payload: `247 - 3 (GATT overhead) - 8 (envelope header) - 2 (CRC)` = **234 bytes**
- For larger messages: split into fragments, set `totalFrags` and `fragIdx` accordingly
- CRC only appended to the **last** fragment

---

## 11. Left vs. Right Temple

- **Right temple**: primary command receiver — send all TX here
- **Left temple**: mirror/receiver for streaming data (audio, notifications)
- Connect to right arm first; left arm BLE address is typically right +1 or discovered via scan

---

## 12. Known Pitfalls

| Pitfall | Symptom | Fix |
|---------|---------|-----|
| Wrong sync byte on RX | Packets dropped silently | Check `data[1] == 0x12` for RX, `0x21` for TX |
| `IsEventCapture` omitted | No gesture events fired | Set field 12=1 on ListObject, field 11=1 on TextObject, every time |
| `flag=0x00` on TX | G2 silently ignores command | Always use `flag=0x20` for requests |
| Magic ≥ 256 | Packet silently dropped | Keep magic in 100–255, wrap at 255 |
| Battery at wrong field | Always reads 0 or garbage | Use `field4 → field12`, not `field10 → field1` |
| Tap not detected | EventType=0 absent from wire | Treat absent EventType (-1 from parser) as TAP |
| Tap routes to wrong DevEvent field | Null gesture | Taps use DevEvent.field3 (ClickEvent), swipes use DevEvent.field2 (TextEvent) |
| Session theft by Even app | G2 shows default clock | Detect sid=0x01 Cmd=3; re-assert with AppLaunch + CreateStartUpPage |
| Duplicate heartbeat loops on re-assertion | Double BLE traffic, magic counter skips | Save Job references; cancel before re-asserting |
| `com.even.sg` stealing BLE session | HUD reverts mid-use | `killBackgroundProcesses("com.even.sg")` on connect and periodically; or `pm disable-user` |
