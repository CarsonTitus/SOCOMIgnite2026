package com.evensocom.psyopvisr.vision

import android.util.Log

enum class GestureType {
    TAP, DOUBLE_TAP, SWIPE_FORWARD, SWIPE_BACKWARD, LONG_PRESS
}

/**
 * Parses BLE touchpad notification payloads into [GestureType] events.
 *
 * Two event channels carry gesture data from the G2:
 *
 * ## Channel A — sid=0x0d (raw physical touchpad events)
 * Protobuf shape: `{ f1=1, f3={f1=contextId(0xE0), [f2=eventCode]} }`
 * Observed eventCode values: 34 (0x22) = tap, swipe values TBD.
 *
 * ## Channel B — sid=0xe0 flag=0x01 Cmd=2 (EvenHub text container events)
 * This is the authoritative gesture channel for text containers with IsEventCapture=1.
 * Protobuf shape (outer EvenHub msg → DevEvent field13 → TextEvent field2 → EventType field3):
 *   OsEventTypeList:
 *     0 = CLICK_EVENT       → TAP
 *     1 = SCROLL_TOP_EVENT  → SWIPE_FORWARD
 *     2 = SCROLL_BOTTOM_EVENT → SWIPE_BACKWARD
 *     3 = DOUBLE_CLICK_EVENT  → DOUBLE_TAP
 *
 * Channel B is preferred; Channel A is kept as a fallback diagnostic.
 */
object TouchpadRouter {

    private const val TAG = "TouchpadRouter"

    // ──────────────────── Channel B — EvenHub sid=0xe0 events ────────────────────

    /**
     * Parse a sid=0xe0 flag=0x01 payload (the aa-12 header and CRC already stripped).
     * Returns the corresponding [GestureType], or null if the packet is not a Cmd=2
     * text gesture event (heartbeat ack, audio ack, etc.).
     */
    fun parseEvenHubEvent(payload: ByteArray): GestureType? {
        return try {
            parseEvenHubInternal(payload)
        } catch (e: Exception) {
            Log.w(TAG, "parseEvenHubEvent failed (${payload.size}B): $e")
            null
        }
    }

    private fun parseEvenHubInternal(pb: ByteArray): GestureType? {
        var i = 0
        var cmd = -1
        var devEventStart = -1
        var devEventLen = 0

        while (i < pb.size) {
            val tag = pb[i].toInt() and 0xFF
            val field = tag ushr 3
            val wire = tag and 0x07
            i++

            when {
                field == 1 && wire == 0 -> {
                    val (v, ni) = readVarint(pb, i); cmd = v; i = ni
                }
                field == 13 && wire == 2 -> {
                    val (len, ni) = readVarint(pb, i)
                    i = ni; devEventStart = i; devEventLen = len; i += len
                }
                else -> i = skipField(pb, i, wire)
            }
        }

        if (cmd != 2 || devEventStart < 0) return null  // Not OS_NOITY_EVENT_TO_APP_PACKET

        val devEvent = pb.copyOfRange(devEventStart, devEventStart + devEventLen)
        return parseDevEvent(devEvent)
    }

    /**
     * Parse SendDeviceEvent:
     *   field1/field2 = ListEvent/TextEvent (scroll/swipe) → EventType at field3 or field5
     *   field3        = ClickEvent (tap/double-tap)        → clickType at field2
     *     clickType 1 → TAP, clickType 2 → DOUBLE_TAP (inferred; adjust if observed otherwise)
     */
    private fun parseDevEvent(pb: ByteArray): GestureType? {
        var i = 0
        while (i < pb.size) {
            val tag = pb[i].toInt() and 0xFF
            val field = tag ushr 3
            val wire = tag and 0x07
            i++

            when {
                (field == 1 || field == 2) && wire == 2 -> {
                    val (len, ni) = readVarint(pb, i)
                    i = ni
                    val itemPb = pb.copyOfRange(i, i + len)
                    i += len
                    val eventType = parseItemEventType(itemPb)
                    if (eventType >= 0) {
                        val hexDump = pb.joinToString(" ") { "%02x".format(it) }
                        Log.i(TAG, "EvenHub DevEvent (field$field) EventType=$eventType [$hexDump]")
                        return mapOsEventType(eventType)
                    }
                }
                field == 3 && wire == 2 -> {
                    // ClickEvent sub-message: field2=clickType (1=single tap, 2=double-tap observed TBD)
                    val (len, ni) = readVarint(pb, i)
                    i = ni
                    val clickPb = pb.copyOfRange(i, i + len)
                    i += len
                    val clickType = parseClickEventType(clickPb)
                    val hexDump = pb.joinToString(" ") { "%02x".format(it) }
                    Log.i(TAG, "EvenHub ClickEvent field3 clickType=$clickType [$hexDump]")
                    return mapClickType(clickType)
                }
                else -> i = skipField(pb, i, wire)
            }
        }
        return null
    }

    /** Extract field2 (varint) from a ClickEvent sub-message. Returns -1 if absent. */
    private fun parseClickEventType(pb: ByteArray): Int {
        var i = 0
        while (i < pb.size) {
            val tag = pb[i].toInt() and 0xFF
            val field = tag ushr 3
            val wire = tag and 0x07
            i++
            if (field == 2 && wire == 0) {
                val (v, _) = readVarint(pb, i)
                return v
            }
            i = skipField(pb, i, wire)
        }
        return -1
    }

    /**
     * Map ClickEvent clickType to GestureType.
     *   -1, 1 → TAP  (1 observed on single tap; -1 = absent default)
     *   2     → DOUBLE_TAP (inferred — update if hardware sends differently)
     */
    private fun mapClickType(v: Int): GestureType? = when (v) {
        -1, 1 -> GestureType.TAP
        2 -> GestureType.DOUBLE_TAP
        else -> { Log.i(TAG, "Unmapped ClickType=$v"); null }
    }

    /**
     * Find EventType in a Text_ItemEvent or List_ItemEvent.
     * In Text_ItemEvent: EventType = field 3.
     * In List_ItemEvent: EventType = field 5.
     * Returns the raw varint value, or -1 if absent.
     */
    private fun parseItemEventType(pb: ByteArray): Int {
        var i = 0
        while (i < pb.size) {
            val tag = pb[i].toInt() and 0xFF
            val field = tag ushr 3
            val wire = tag and 0x07
            i++

            when {
                (field == 3 || field == 5) && wire == 0 -> {
                    val (v, _) = readVarint(pb, i)
                    return v
                }
                else -> i = skipField(pb, i, wire)
            }
        }
        return -1
    }

    /**
     * Map OsEventTypeList varint to [GestureType].
     *   0 = CLICK_EVENT         → TAP
     *   1 = SCROLL_TOP_EVENT    → SWIPE_FORWARD
     *   2 = SCROLL_BOTTOM_EVENT → SWIPE_BACKWARD
     *   3 = DOUBLE_CLICK_EVENT  → DOUBLE_TAP
     */
    private fun mapOsEventType(v: Int): GestureType? = when (v) {
        -1, 0 -> GestureType.TAP  // -1 = field absent (protobuf default omission) = CLICK_EVENT=0
        1 -> GestureType.SWIPE_FORWARD
        2 -> GestureType.SWIPE_BACKWARD
        3 -> GestureType.DOUBLE_TAP
        else -> { Log.i(TAG, "Unmapped OsEventType=$v"); null }
    }

    // ──────────────────── Channel A — sid=0x0d raw events ────────────────────

    /**
     * Parse a sid=0x0d state-change payload (after the aa-12 header and CRC are stripped).
     * Known eventCode values: 34 (0x22) = tap. Swipe values logged but treated as null
     * until confirmed — Channel B is the primary gesture source.
     */
    fun parse(payload: ByteArray): GestureType? {
        return try {
            parseInternal(payload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse touchpad payload (${payload.size}B): $e")
            null
        }
    }

    private fun parseInternal(payload: ByteArray): GestureType? {
        var i = 0
        var gestureTypeValue = -1

        while (i < payload.size) {
            val tagByte = payload[i].toInt() and 0xFF
            val fieldNumber = tagByte ushr 3
            val wireType = tagByte and 0x07
            i++

            when {
                fieldNumber == 1 && wireType == 0 -> {
                    val (_, newIdx) = readVarint(payload, i); i = newIdx
                }
                fieldNumber == 3 && wireType == 2 -> {
                    val (subLen, newIdx) = readVarint(payload, i)
                    i = newIdx
                    val subPayload = payload.copyOfRange(i, minOf(i + subLen, payload.size))
                    gestureTypeValue = parseSubMessage(subPayload)
                    i += subLen
                }
                else -> i = skipField(payload, i, wireType)
            }
        }

        return when (gestureTypeValue) {
            -1, 0 -> null                 // absent or touch start — ignore
            34 -> GestureType.TAP         // confirmed hardware value for single tap
            else -> {
                // Log raw value for future calibration; do NOT fallback to TAP here
                // since Channel B (sid=0xe0) is the authoritative gesture source.
                Log.i(TAG, "sid=0x0d unknown eventCode=$gestureTypeValue (0x${gestureTypeValue.toString(16)}) — logged for calibration")
                null
            }
        }
    }

    private fun parseSubMessage(sub: ByteArray): Int {
        var i = 0
        var gestureType = -1

        while (i < sub.size) {
            val tagByte = sub[i].toInt() and 0xFF
            val fieldNumber = tagByte ushr 3
            val wireType = tagByte and 0x07
            i++

            when {
                fieldNumber == 1 && wireType == 0 -> {
                    val (_, newIdx) = readVarint(sub, i); i = newIdx
                }
                fieldNumber == 2 && wireType == 0 -> {
                    val (value, newIdx) = readVarint(sub, i)
                    gestureType = value; i = newIdx
                }
                else -> i = skipField(sub, i, wireType)
            }
        }

        return gestureType
    }

    // ──────────────────── Protobuf helpers ────────────────────

    private fun readVarint(data: ByteArray, offset: Int): Pair<Int, Int> {
        var result = 0; var shift = 0; var i = offset
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF; i++
            result = result or ((b and 0x7F) shl shift); shift += 7
            if ((b and 0x80) == 0) break
        }
        return Pair(result, i)
    }

    private fun skipField(data: ByteArray, offset: Int, wireType: Int): Int {
        return when (wireType) {
            0 -> { var i = offset; while (i < data.size && (data[i].toInt() and 0x80) != 0) i++; i + 1 }
            1 -> offset + 8
            2 -> { val (len, ni) = readVarint(data, offset); ni + len }
            5 -> offset + 4
            else -> { Log.w(TAG, "Unknown wire type $wireType"); data.size }
        }
    }
}
