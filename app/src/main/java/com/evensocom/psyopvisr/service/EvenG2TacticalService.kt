package com.evensocom.psyopvisr.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import com.evensocom.psyopvisr.sensors.CompassEngine
import com.evensocom.psyopvisr.vision.TouchpadRouter
import com.evensocom.psyopvisr.vision.VisualizationBus
import com.evensocom.psyopvisr.vision.VisionModeController
import com.evensocom.psyopvisr.vision.VoiceCommandEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Foreground service that owns the G2 BLE connection and renders text to the HUD.
 */
class EvenG2TacticalService : LifecycleService() {

    companion object {
        private const val TAG = "PSYOP-VISR"
        private const val CHANNEL_ID = "visr_channel"
        private const val NOTIFICATION_ID = 1
        private const val CONTAINER_NAME = "visr" // ≤14 chars

        var instance: EvenG2TacticalService? = null
            private set

        // Raw session prelude — sid=0x01 AppLaunch { type: 2 }
        // This is a complete aa-21 envelope with CRC, captured from the firmware.
        // It MUST be sent as raw bytes (NOT wrapped in another envelope).
        val PRELUDE_F5872 = byteArrayOf(
            0xaa.toByte(), 0x21, 0x92.toByte(), 0x13, 0x01, 0x01, 0x01, 0x20,
            0x08, 0x02, 0x10, 0x9c.toByte(), 0x01, 0x22, 0x0a, 0x1a, 0x08,
            0x12, 0x06, 0x12, 0x04, 0x08, 0x00, 0x10, 0x00, 0xa1.toByte(), 0x42
        )
    }

    lateinit var bleManager: EvenG2NativeBleManager
    private lateinit var compassEngine: CompassEngine
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val audioBufferChannel = Channel<ByteArray>(Channel.UNLIMITED)

    private var currentHeading = ""
    private var currentBattery = "--%"
    private var lastVisualAlert = ""
    // Last content pushed by VisionModeController — used by compass/battery refresh so
    // they never overwrite the current menu/scan/idle text with a different layout.
    @Volatile private var lastHudContentLines = "[TAP] Scan Menu"

    private var isHubInitialized = false
    private var containerCreated = false

    // Cancellable jobs for loops that must not be duplicated across re-assertions
    private var heartbeatJob: kotlinx.coroutines.Job? = null
    private var batteryJob: kotlinx.coroutines.Job? = null

    // Vision system (camera + inference engine are owned by MainActivity)
    private lateinit var visionController: VisionModeController
    private lateinit var voiceEngine: VoiceCommandEngine

    // BLE connection state exposed for MainActivity's UI
    val connectionStateFlow get() = if (::bleManager.isInitialized) bleManager.connectionState else null

    // Magic counter: cycle 100..255 (firmware uses only low byte)
    @Volatile private var magic = 100
    private fun nextMagic(): Int {
        magic = if (magic >= 255) 100 else magic + 1
        return magic
    }

    // Reassertion guard — AtomicLong prevents the race where multiple threads
    // all pass the debounce check before lastReassertMs is updated.
    private val lastReassertMs = java.util.concurrent.atomic.AtomicLong(0)

    override fun onCreate() {
        super.onCreate()
        instance = this

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "VISR Tactical", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setContentTitle("PSYOP-VISR")
            .setContentText("Tactical link active")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        initializeBridge()
        initializeSensors()

        // VisionModeController: reuse existing instance if MainActivity already initialized it;
        // otherwise initialize here so the service works standalone (e.g. after process restart).
        visionController = VisionModeController.instance
            ?: VisionModeController.initialize(scope)
        voiceEngine = VoiceCommandEngine(this)
        voiceEngine.startListening()
    }

    private fun initializeSensors() {
        compassEngine = CompassEngine(this)
        compassEngine.start()
        
        scope.launch {
            var lastCompassHudMs = 0L
            compassEngine.heading.collect {
                currentHeading = compassEngine.getHeadingString()
                // Refresh HUD at most every 5s — uses lastHudContentLines so the current
                // menu/scan/idle text is preserved; heading is injected at render time.
                val now = System.currentTimeMillis()
                if (now - lastCompassHudMs >= 5_000) {
                    lastCompassHudMs = now
                    pushHudContent(lastHudContentLines)
                }
            }
        }
    }

    private fun initializeBridge() {
        bleManager = EvenG2NativeBleManager(this)

        scope.launch {
            // Retry loop: reconnect after failure or disconnect with 8-second back-off.
            // Status 0x93 (147) = G2 BLE stack rejected us — a brief pause clears it.
            while (isActive) {
                Log.i(TAG, "Connecting to EVEN G2 right arm...")
                bleManager.connectToRightArm()

                // Wait up to 35s for the connection to succeed.
                val connected = withTimeoutOrNull(35_000) {
                    bleManager.connectionState.first { it }
                }

                if (connected == true && !isHubInitialized) {
                    Log.i(TAG, "BLE connected. Running session prelude...")
                    try {
                        runSessionPrelude()
                        startNotificationCollector()
                    } catch (e: Exception) {
                        Log.e(TAG, "Session prelude failed", e)
                    }

                    // Wait for disconnect before retrying
                    bleManager.connectionState.first { !it }
                    isHubInitialized = false
                    containerCreated = false
                    Log.w(TAG, "BLE disconnected — retrying in 8s")
                } else {
                    Log.w(TAG, "BLE connection attempt failed/timed out — retrying in 8s")
                }

                delay(8_000)
            }
        }
    }

    private fun startNotificationCollector() {
        scope.launch {
            bleManager.notifyFlow.collect { data ->
                // RX packets from the G2 use sync bytes aa 12
                if (data.size >= 8 && data[0] == 0xAA.toByte() && data[1] == 0x12.toByte()) {
                    val sid       = data[6].toInt() and 0xFF
                    val chunkLen  = data[3].toInt() and 0xFF  // raw chunk length includes CRC
                    // Strip the 2-byte CRC: protobuf lives in bytes[8 .. 8+chunkLen-2)
                    val pbEnd     = minOf(8 + chunkLen - 2, data.size)
                    val payload   = if (pbEnd > 8) data.copyOfRange(8, pbEnd) else byteArrayOf()

                    val flag = data[7].toInt() and 0xFF

                    when (sid) {
                        0x09 -> parseBatteryResponse(payload)

                        // sid=0xe0 — EvenHub async events (gestures, acks, heartbeats)
                        // Log ALL flags so we can see exactly what arrives on tap.
                        0xe0 -> {
                            val hexDump = payload.joinToString(" ") { "%02x".format(it) }
                            Log.i(TAG, "EvenHub sid=0xe0 flag=0x${flag.toString(16)} pb[${payload.size}B]: $hexDump")
                            val evenHubCmd = if (payload.size >= 2 && payload[0] == 0x08.toByte()) {
                                payload[1].toInt() and 0xFF
                            } else -1
                            if (evenHubCmd == 16) {
                                val audioStat = parseAudioCtrStatus(payload)
                                Log.i(TAG, "AudioCtrRes received: AudioStat=$audioStat")
                            }
                            val gesture = TouchpadRouter.parseEvenHubEvent(payload)
                            Log.i(TAG, "EvenHub event → $gesture")
                            if (gesture != null) {
                                VisionModeController.instance?.onGestureEvent(gesture)
                            }
                        }

                        // sid=0x0d — raw physical touchpad state-change events (diagnostic fallback)
                        // Only tap (eventCode=34) is confirmed here; swipes route via sid=0xe0.
                        0x0d -> {
                            val hexDump = payload.joinToString(" ") { "%02x".format(it) }
                            Log.i(TAG, "Touchpad raw pb[${payload.size}B]: $hexDump")
                            val gesture = TouchpadRouter.parse(payload)
                            Log.i(TAG, "Touchpad parsed → $gesture")
                            if (gesture != null) {
                                VisionModeController.instance?.onGestureEvent(gesture)
                            }
                        }

                        // sid=0x80 UX_DEVICE_SETTINGS — may carry battery ACK or push data
                        0x80 -> {
                            val hexDump = payload.joinToString(" ") { "%02x".format(it) }
                            Log.i(TAG, "UxSettings raw pb[${payload.size}B]: $hexDump")
                            // Try parsing as G2SettingPackage in case it contains battery
                            parseBatteryResponse(payload)
                        }

                        // sid=0x01 AppMgmt: detect Cmd=3 (AppConnect) — Even app re-asserted session
                        0x01 -> {
                            // payload[0] = field-1 varint tag (0x08), payload[1] = cmd value
                            val cmdVal = if (payload.size >= 2 && payload[0] == 0x08.toByte())
                                payload[1].toInt() and 0xFF else -1
                            if (cmdVal == 3) {
                                // Cmd=3 means another app grabbed the session. Relinquish cleanly so
                                // the official Even app can own translation/HUD without us fighting it.
                                Log.i(TAG, "Session ownership transferred to companion app (Cmd=3)")
                                relinquishSessionOwnership("Companion app took session")
                            }
                        }

                        // Catch-all: log any SID we haven't mapped yet (helps identify tap packets)
                        else -> {
                            val hexDump = payload.joinToString(" ") { "%02x".format(it) }
                            Log.i(TAG, "Unknown sid=0x${sid.toString(16)} flag=0x${flag.toString(16)} pb[${payload.size}B]: $hexDump")
                        }
                    }
                }
            }
        }
    }

    /**
     * Re-runs the HUD session handshake after the Even Realities app steals the display.
     * Uses AtomicLong CAS to ensure only one thread triggers a re-assertion within 3 s.
     */
    private fun reassertSession() {
        val now = System.currentTimeMillis()
        val last = lastReassertMs.get()
        if (now - last < 3_000) return
        if (!lastReassertMs.compareAndSet(last, now)) return // another thread beat us
        isHubInitialized = false
        containerCreated = false
        scope.launch {
            try {
                // Cancel existing loops so re-assertion doesn't spawn duplicates
                heartbeatJob?.cancel()
                batteryJob?.cancel()
                delay(200)
                runSessionPrelude()
            } catch (e: Exception) {
                Log.e(TAG, "Session re-assertion failed", e)
            }
        }
    }

    /**
     * Parse a sid=0x09 G2SettingPackage push/response for battery percentage.
     *
     * The G2 pushes this spontaneously and also in response to our query:
     *   G2SettingPackage {
     *     commandId = 2 (field 1, varint)
     *     magicRandom (field 2, varint)
     *     deviceReceiveRequestFromApp (field 4, len-delimited) → DeviceReceiveRequestFromAPP {
     *       battery (field 12, varint) = 0–100
     *       chargingStatus (field 13, varint)
     *     }
     *   }
     */
    private fun parseBatteryResponse(pb: ByteArray) {
        val hexDump = pb.joinToString(" ") { "%02x".format(it) }
        Log.i(TAG, "Battery response pb[${pb.size}B]: $hexDump")
        try {
            var i = 0
            while (i < pb.size) {
                val tag = pb[i].toInt() and 0xFF
                val fieldNum = tag ushr 3
                val wireType = tag and 0x07
                i++

                when {
                    fieldNum == 4 && wireType == 2 -> {
                        // deviceReceiveRequestFromApp
                        var len = 0; var shift = 0
                        while (i < pb.size) {
                            val b = pb[i].toInt() and 0xFF; i++
                            len = len or ((b and 0x7F) shl shift); shift += 7
                            if ((b and 0x80) == 0) break
                        }
                        val childPb = pb.copyOfRange(i, minOf(i + len, pb.size))
                        parseDeviceReceiveRequest(childPb)
                        i += len
                        return // battery found
                    }
                    wireType == 0 -> {
                        while (i < pb.size && (pb[i].toInt() and 0x80) != 0) i++
                        if (i < pb.size) i++
                    }
                    wireType == 2 -> {
                        var len = 0; var shift = 0
                        while (i < pb.size) {
                            val b = pb[i].toInt() and 0xFF; i++
                            len = len or ((b and 0x7F) shl shift); shift += 7
                            if ((b and 0x80) == 0) break
                        }
                        i += len
                    }
                    else -> i++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse battery response", e)
        }
    }

    /** Parse DeviceReceiveRequestFromAPP — field 12 = battery %, field 13 = charging status. */
    private fun parseDeviceReceiveRequest(pb: ByteArray) {
        var i = 0
        while (i < pb.size) {
            val tag = pb[i].toInt() and 0xFF
            val fieldNum = tag ushr 3
            val wireType = tag and 0x07
            i++
            when {
                fieldNum == 12 && wireType == 0 -> {
                    var v = 0; var shift = 0
                    while (i < pb.size) {
                        val b = pb[i].toInt() and 0xFF; i++
                        v = v or ((b and 0x7F) shl shift); shift += 7
                        if ((b and 0x80) == 0) break
                    }
                    currentBattery = "$v%"
                    Log.i(TAG, "Battery updated: $v%")
                    pushHudContent(lastHudContentLines)  // refresh with new battery, keep current content
                    return
                }
                wireType == 0 -> { while (i < pb.size && (pb[i].toInt() and 0x80) != 0) i++; if (i < pb.size) i++ }
                wireType == 2 -> {
                    var len = 0; var shift = 0
                    while (i < pb.size) { val b = pb[i].toInt() and 0xFF; i++; len = len or ((b and 0x7F) shl shift); shift += 7; if ((b and 0x80) == 0) break }
                    i += len
                }
                else -> i++
            }
        }
    }

    /**
     * Mandatory two-step prelude:
     *   Step 1: sid=0x01 AppLaunch protobuf (extracted from PRELUDE_F5872)
     *   Step 2: sid=0xe0 Cmd=0 CreateStartUpPage with a ListObject container
     */
    private suspend fun runSessionPrelude() {
        // ── Step 1: AppLaunch via sid=0x01 ──
        // pb payload from PRELUDE_F5872 bytes[8..24] (17 bytes):
        //   08 02 10 9c 01 22 0a 1a 08 12 06 12 04 08 00 10 00
        val appLaunchPb = byteArrayOf(
            0x08, 0x02, 0x10, 0x9c.toByte(), 0x01, 0x22, 0x0a, 0x1a,
            0x08, 0x12, 0x06, 0x12, 0x04, 0x08, 0x00, 0x10, 0x00
        )
        val m1 = nextMagic()
        bleManager.sendEnvelope(0x01, m1, appLaunchPb)
        Log.i(TAG, "Prelude step 1: AppLaunch sent via envelope (magic=$m1)")
        delay(500)

        // ── Step 2: Cmd=0 CreateStartUpPage ──
        val createPb = buildCreateStartUpPage(CONTAINER_NAME, listOf("PSYOP-VISR"))
        val m = nextMagic()
        bleManager.sendEnvelope(0xE0.toByte(), m, createPb)
        Log.i(TAG, "Prelude step 2: CreateStartUpPage sent (magic=$m)")
        delay(500)

        containerCreated = true
        isHubInitialized = true

        startHeartbeatLoop()
        startBatteryMonitorLoop()
        startAudioCapture()

        delay(200)
        updateHudDisplay("SYSTEM ONLINE", null)
    }

    /**
     * Enable G2 microphone streaming and wire the audio notify flow into the
     * [audioBufferChannel] that [TacticalInferenceEngine] reads from.
     *
     * The glasses begin sending LC3-encoded audio frames on 6402 after receiving
     * the mic-enable command (sid=0x01, payload=0x0F).
     */
    private fun startAudioCapture() {
        sendAudioControl(enable = true)
        // Tell the glasses to start streaming mic audio on the 6402 channel
        bleManager.enableMicrophone()
        // Fallback handshake: some firmware expects mic-enable on the audio write char (6401).
        bleManager.enableMicrophoneViaAudioChannel()
        Log.i(TAG, "Audio capture started — mic enable sent, bridging 6402 → audioBufferChannel")

        scope.launch {
            repeat(6) { attempt ->
                delay(2_000)
                val lastAudio = bleManager.getLastAudioDataMs()
                val hasRecentAudio = lastAudio > 0 && (System.currentTimeMillis() - lastAudio) < 3_000
                if (hasRecentAudio) {
                    Log.i(TAG, "G2 mic stream active on attempt ${attempt + 1}")
                    return@launch
                }
                Log.w(TAG, "No G2 mic frames yet; retrying mic-enable handshake (attempt ${attempt + 1})")
                sendAudioControl(enable = true)
                bleManager.enableMicrophone()
                bleManager.enableMicrophoneViaAudioChannel()
            }
            Log.e(TAG, "G2 mic stream did not start (no 6402 notifications)")
            pushHudContent("TRANSLATION\nG2 mic stream failed.\nReconnect glasses, then retry.")
        }

        scope.launch {
            bleManager.audioNotifyFlow.collect { audioFrame ->
                // Each notification from 6402 is one LC3 frame from the G2 mic.
                // Forward it directly to the channel consumed by TacticalInferenceEngine.
                audioBufferChannel.trySend(audioFrame)
            }
        }
    }

    private fun startBatteryMonitorLoop() {
        batteryJob = scope.launch {
            Log.i(TAG, "Battery monitor loop started")
            while (isActive && isHubInitialized) {
                try {
                    val m = nextMagic()
                    val pb = buildSettingsQuery(m)
                    bleManager.sendEnvelope(0x09.toByte(), m, pb)
                    Log.d(TAG, "Battery query sent (magic=$m, payload=${pb.joinToString(" ") { "%02x".format(it) }})")
                } catch (e: Exception) {
                    Log.e(TAG, "Battery query failed", e)
                }
                delay(60_000)
            }
        }
    }

    // ──────────────────── Heartbeat (Cmd=12) ────────────────────

    private fun startHeartbeatLoop() {
        heartbeatJob = scope.launch {
            Log.i(TAG, "Heartbeat loop started")
            while (isActive && isHubInitialized) {
                delay(5000)
                try {
                    val m = nextMagic()
                    val pb = buildHeartbeat()
                    bleManager.sendEnvelope(0xE0.toByte(), m, pb)
                    Log.d(TAG, "Heartbeat sent (magic=$m)")
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat error", e)
                }
            }
        }
    }

    // ──────────────────── Text rendering (Cmd=7) ────────────────────

    fun updateHudDisplay(transcription: String?, alert: String?, forceRedraw: Boolean = false) {
        if (!isHubInitialized || !containerCreated) return

        val dashboard = buildString {
            // Top Status Line
            append("HDG: $currentHeading   BAT: $currentBattery\n")
            append("────────────────────────\n")
            
            // Interaction/Alert area
            if (!alert.isNullOrBlank()) {
                lastVisualAlert = alert
            }
            
            if (lastVisualAlert.isNotBlank()) {
                append("SCAN: $lastVisualAlert\n")
            }
            
            if (!transcription.isNullOrBlank()) {
                append("> $transcription")
            }
        }.trim()

        if (dashboard.isEmpty()) return

        val truncated = if (dashboard.length > 900) dashboard.substring(0, 900) else dashboard

        scope.launch {
            try {
                val pb = buildRebuildText(CONTAINER_NAME, truncated)
                val m = nextMagic()
                bleManager.sendEnvelope(0xE0.toByte(), m, pb)
                Log.d(TAG, "HUD dashboard updated (magic=$m)")
            } catch (e: Exception) {
                Log.e(TAG, "HUD update failed", e)
            }
        }
    }

    /**
     * Called by [VisionModeController] to push pre-formatted compound HUD content.
     *
     * Composes the status line (heading + battery) with a separator and the
     * supplied [contentLines], then sends via Cmd=7 as a standard text update.
     *
     * @param contentLines  Multi-line string for the content area below the separator.
     *                      VisionModeController builds this for every state (IDLE, MENU,
     *                      COUNTDOWN, RESULT).
     */
    fun pushHudContent(contentLines: String) {
        if (!isHubInitialized || !containerCreated) return
        lastHudContentLines = contentLines   // remember for compass/battery refreshes
        val statusLine = "HDG: $currentHeading   BAT: $currentBattery"
        val full = "$statusLine\n────────────────────────\n$contentLines"
        val truncated = if (full.length > 900) full.substring(0, 900) else full
        VisualizationBus.postHud(truncated)
        scope.launch {
            try {
                val pb = buildRebuildText(CONTAINER_NAME, truncated)
                val m = nextMagic()
                bleManager.sendEnvelope(0xE0.toByte(), m, pb)
                Log.d(TAG, "HUD content pushed (magic=$m)")
            } catch (e: Exception) {
                Log.e(TAG, "pushHudContent failed", e)
            }
        }
    }

    // ──────────────────── Protobuf builders ────────────────────
    // Hand-rolled protobuf. Field tag = (field_number << 3) | wire_type
    // wire_type 0 = varint, 2 = length-delimited

    private fun encodeVarint(value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (v > 0x7F) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
        return out.toByteArray()
    }

    private fun pbVarint(fieldNumber: Int, value: Int): ByteArray {
        val tag = encodeVarint((fieldNumber shl 3) or 0)
        val data = encodeVarint(value)
        return tag + data
    }

    private fun pbBytes(fieldNumber: Int, data: ByteArray): ByteArray {
        val tag = encodeVarint((fieldNumber shl 3) or 2)
        val len = encodeVarint(data.size)
        return tag + len + data
    }

    private fun pbString(fieldNumber: Int, value: String): ByteArray {
        return pbBytes(fieldNumber, value.toByteArray(Charsets.UTF_8))
    }

    /**
     * Cmd=0 CreateStartUpPage.
     *
     * evenhub_main_msg_ctx {
     *   Cmd: 0                              // field 1
     *   MagicRandom: <magic>                // field 2
     *   CreateMessage: {                    // field 3
     *     ContainerTotalNum: 1              // field 1
     *     ListObject: [{                    // field 2 (repeated)
     *       XPosition: 0                   // field 1
     *       YPosition: 0                   // field 2
     *       Width: 280                     // field 3
     *       Height: 130                    // field 4
     *       ContainerID: 1                 // field 9
     *       ContainerName: <name>          // field 10
     *       ItemContainer: {               // field 11
     *         ItemCount: N                 // field 1
     *         IsItemSelectBorderEn: 1      // field 3
     *         ItemName: [<items>]          // field 4 repeated
     *       }
     *       IsEventCapture: 1              // field 12
     *     }]
     *     widgetId: 10000                  // field 5
     *   }
     * }
     */
    private fun buildCreateStartUpPage(name: String, items: List<String>): ByteArray {
        // ItemContainer
        val itemCount = pbVarint(1, items.size)
        val selectBorder = pbVarint(3, 1)
        var itemNames = byteArrayOf()
        for (item in items) {
            itemNames += pbString(4, item)
        }
        val itemContainer = itemCount + selectBorder + itemNames

        // ListContainerProperty (ListObject[0])
        val x = pbVarint(1, 0)
        val y = pbVarint(2, 0)
        val w = pbVarint(3, 280)
        val h = pbVarint(4, 130)
        val cid = pbVarint(9, 1)
        val cname = pbString(10, name)
        val ic = pbBytes(11, itemContainer)
        val capture = pbVarint(12, 1)
        val listObj = x + y + w + h + cid + cname + ic + capture

        // CreateStartUpPageContainer
        val totalNum = pbVarint(1, 1)
        val listObjField = pbBytes(2, listObj) // field 2 = ListObject
        val widgetId = pbVarint(5, 10000)
        val createMsg = totalNum + listObjField + widgetId

        // evenhub_main_msg_ctx
        val cmd = pbVarint(1, 0) // Cmd = 0 (CREATE)
        val magicField = pbVarint(2, nextMagic())
        val createField = pbBytes(3, createMsg)
        return cmd + magicField + createField
    }

    /**
     * Cmd=7 RebuildPageContainer with TextObject.
     *
     * evenhub_main_msg_ctx {
     *   Cmd: 7                              // field 1
     *   MagicRandom: <magic>                // field 2
     *   RebuildContainer: {                 // field 7
     *     ContainerTotalNum: 1              // field 1
     *     TextObject: [{                    // field 3 (repeated)
     *       XPosition: 0                   // field 1
     *       YPosition: 0                   // field 2
     *       Width: 576                     // field 3
     *       Height: 288                    // field 4
     *       ContainerID: 1                 // field 9
     *       ContainerName: <name>          // field 10
     *       IsEventCapture: 0              // field 11
     *       Content: <text>                // field 12
     *     }]
     *   }
     * }
     */
    private fun buildRebuildText(name: String, text: String): ByteArray {
        // TextContainerProperty
        val x = pbVarint(1, 0)
        val y = pbVarint(2, 0)
        val w = pbVarint(3, 576)
        val h = pbVarint(4, 288)
        val cid = pbVarint(9, 1)
        val cname = pbString(10, name)
        val capture = pbVarint(11, 1) // IsEventCapture=1 — required for touchpad events to fire
        val content = pbString(12, text)
        val textObj = x + y + w + h + cid + cname + capture + content

        // RebuildPageContainer
        val totalNum = pbVarint(1, 1)
        val textObjField = pbBytes(3, textObj) // field 3 = TextObject
        val rebuildMsg = totalNum + textObjField

        // evenhub_main_msg_ctx
        val cmd = pbVarint(1, 7) // Cmd = 7 (REBUILD)
        val magicField = pbVarint(2, nextMagic())
        val rebuildField = pbBytes(7, rebuildMsg)
        return cmd + magicField + rebuildField
    }

    /**
     * Cmd=12 Heartbeat.
     *
     * evenhub_main_msg_ctx {
     *   Cmd: 12                             // field 1
     *   MagicRandom: <magic>                // field 2
     *   HeartPacketCmd: {                    // field 14
     *     Cnt: 0                            // field 1
     *   }
     * }
     */
    private fun buildHeartbeat(): ByteArray {
        // HeartBeatPacket { Cnt: 0 }
        val cnt = pbVarint(1, 0)

        // evenhub_main_msg_ctx
        val cmd = pbVarint(1, 12)
        val magicField = pbVarint(2, nextMagic())
        val heartField = pbBytes(14, cnt)
        return cmd + magicField + heartField
    }

    /**
     * Cmd=15 APP_REQUEST_AUDIO_CTR_PACKET.
     *
     * evenhub_main_msg_ctx {
     *   Cmd: 15                            // field 1
     *   MagicRandom: <magic>               // field 2
     *   AudioCtrCommand: {                 // field 18
     *     AudoFuncEn: 1|0                  // field 1 (1=start, 0=stop)
     *   }
     * }
     */
    private fun buildAudioControlCmd(enable: Boolean, magic: Int): ByteArray {
        val cmd = pbVarint(1, 15)
        val magicField = pbVarint(2, magic)
        val audioInner = pbVarint(1, if (enable) 1 else 0)
        val audioField = pbBytes(18, audioInner)
        return cmd + magicField + audioField
    }

    private fun sendAudioControl(enable: Boolean) {
        try {
            val m = nextMagic()
            val pb = buildAudioControlCmd(enable, m)
            bleManager.sendEnvelope(0xE0.toByte(), m, pb)
            Log.i(TAG, "AudioCtrCmd sent (enable=$enable, magic=$m)")
        } catch (e: Exception) {
            Log.w(TAG, "AudioCtrCmd send failed: $e")
        }
    }

    /**
     * Parse EvenHub AudioResCommand (field 19) and return AudioStat (field 1) if present.
     */
    private fun parseAudioCtrStatus(pb: ByteArray): Int? {
        var i = 0
        while (i < pb.size) {
            val tag = pb[i].toInt() and 0xFF
            val fieldNum = tag ushr 3
            val wireType = tag and 0x07
            i++
            when {
                fieldNum == 19 && wireType == 2 -> {
                    var len = 0; var shift = 0
                    while (i < pb.size) {
                        val b = pb[i].toInt() and 0xFF; i++
                        len = len or ((b and 0x7F) shl shift); shift += 7
                        if ((b and 0x80) == 0) break
                    }
                    val end = minOf(i + len, pb.size)
                    var j = i
                    while (j < end) {
                        val ctag = pb[j].toInt() and 0xFF
                        val cField = ctag ushr 3
                        val cWire = ctag and 0x07
                        j++
                        if (cField == 1 && cWire == 0) {
                            var v = 0; var s = 0
                            while (j < end) {
                                val b = pb[j].toInt() and 0xFF; j++
                                v = v or ((b and 0x7F) shl s); s += 7
                                if ((b and 0x80) == 0) break
                            }
                            return v
                        }
                        if (cWire == 0) {
                            while (j < end && (pb[j].toInt() and 0x80) != 0) j++
                            if (j < end) j++
                        } else if (cWire == 2) {
                            var l = 0; var sh = 0
                            while (j < end) {
                                val b = pb[j].toInt() and 0xFF; j++
                                l = l or ((b and 0x7F) shl sh); sh += 7
                                if ((b and 0x80) == 0) break
                            }
                            j += l
                        } else {
                            j++
                        }
                    }
                    i = end
                }
                wireType == 0 -> {
                    while (i < pb.size && (pb[i].toInt() and 0x80) != 0) i++
                    if (i < pb.size) i++
                }
                wireType == 2 -> {
                    var len = 0; var shift = 0
                    while (i < pb.size) {
                        val b = pb[i].toInt() and 0xFF; i++
                        len = len or ((b and 0x7F) shl shift); shift += 7
                        if ((b and 0x80) == 0) break
                    }
                    i += len
                }
                else -> i++
            }
        }
        return null
    }

    /**
     * sid=0x09 G2SettingPackage query for battery/status.
     *
     * G2SettingPackage {
     * Generated-schema format (g2_setting_pb.ts / g2-kit):
     *   G2SettingPackage {
     *     commandId             = 1: 2 (DeviceReceiveRequest)
     *     magicRandom           = 2: magic
     *     deviceReceiveRequestFromApp = 4: { settingInfoType = 1: 1 (APP_REQUIRE_BASIC_SETTING) }
     *   }
     * This triggers the G2 to respond with a full settings snapshot including battery.
     */
    private fun buildSettingsQuery(magic: Int): ByteArray {
        val settingType = pbVarint(1, 1)          // settingInfoType=APP_REQUIRE_BASIC_SETTING
        val reqField = pbBytes(4, settingType)    // field4=deviceReceiveRequestFromApp
        val cmdId = pbVarint(1, 2)                // commandId=DeviceReceiveRequest
        val magicField = pbVarint(2, magic)       // magicRandom=magic
        return cmdId + magicField + reqField
    }

    private fun relinquishSessionOwnership(reason: String) {
        Log.i(TAG, "Relinquishing session ownership: $reason")
        isHubInitialized = false
        containerCreated = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        batteryJob?.cancel()
        batteryJob = null
    }

    /**
     * Called from MainActivity when user wants to switch back to the official Even app.
     */
    fun relinquishToCompanionAndStop() {
        scope.launch {
            relinquishSessionOwnership("User requested handoff to Even app")
            if (::bleManager.isInitialized) {
                bleManager.disconnect()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    // ──────────────────── Lifecycle ────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }

    override fun onDestroy() {
        instance = null
        isHubInitialized = false
        containerCreated = false
        if (::voiceEngine.isInitialized) voiceEngine.stopListening()
        scope.cancel()
        if (::bleManager.isInitialized) bleManager.disconnect()
        if (::compassEngine.isInitialized) compassEngine.stop()
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }
}
