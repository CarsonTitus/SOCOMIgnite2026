package com.evensocom.psyopvisr.vision

import android.util.Log
import com.evensocom.psyopvisr.service.EvenG2TacticalService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Note: VisualizationBus is in the same package (vision), no additional import needed.

enum class VisionMode {
    IDLE, MENU, FACE_SCAN, SYMBOL_SCAN, CULTURAL_CONTEXT, ROOM_ANALYSIS, TRANSLATION
}

/**
 * Sealed result type that each scan engine returns. Holds a pre-formatted HUD string.
 */
sealed class ScanResult {
    data class FaceResult(
        val position: String,
        val distance: String,
        val identity: String,
        val confidence: Float,
        val personType: PersonClassifier.PersonType = PersonClassifier.PersonType.UNKNOWN
    ) : ScanResult() {
        override fun toHudString() =
            "FACE: $position / $distance\nTYPE: ${personType.name}\nID: $identity (${(confidence * 100).toInt()}%)"
    }

    data class SymbolResult(
        val label: String,
        val position: String,
        val context: String,
        val alertLevel: Int,
        val confidence: Float,
        val extras: List<String> = emptyList()   // additional detections (label + conf%)
    ) : ScanResult() {
        override fun toHudString(): String {
            val alert = if (alertLevel >= 2) "⚠ " else ""
            val sb = StringBuilder()
            sb.append("${alert}${label}  ${(confidence * 100).toInt()}%\n")
            sb.append("[$position] ${context.take(28)}")
            if (extras.isNotEmpty()) sb.append("\n").append(extras.take(2).joinToString("  "))
            return sb.toString()
        }
    }

    data class RoomResult(val summary: String) : ScanResult() {
        override fun toHudString() = summary
    }

    data class ErrorResult(val message: String) : ScanResult() {
        override fun toHudString() = message
    }

    abstract fun toHudString(): String
}

/**
 * State machine controlling vision scanning modes, HUD display, and gesture routing.
 *
 * The companion object singleton pattern mirrors EvenG2TacticalService so that
 * TacticalInferenceEngine can call [instance] without a hard reference to the service.
 */
class VisionModeController(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "VisionModeController"

        var instance: VisionModeController? = null
            private set

        fun initialize(coroutineScope: CoroutineScope): VisionModeController {
            val ctrl = VisionModeController(coroutineScope)
            instance = ctrl
            return ctrl
        }
    }

    // ──────────────────── State ────────────────────

    private val _modeFlow = MutableStateFlow(VisionMode.IDLE)
    val modeFlow: StateFlow<VisionMode> = _modeFlow.asStateFlow()

    @Volatile var currentMode: VisionMode = VisionMode.IDLE
        private set(value) {
            field = value
            _modeFlow.value = value
        }

    /** True while the countdown is running and frames should be processed. */
    @Volatile var isScanActive: Boolean = false
        private set

    private val menuItems = listOf(
        VisionMode.FACE_SCAN,
        VisionMode.SYMBOL_SCAN,
        VisionMode.CULTURAL_CONTEXT,
        VisionMode.ROOM_ANALYSIS,
        VisionMode.TRANSLATION
    )
    private var menuIndex = 0

    // Optional callback invoked whenever a new scan starts, so callers can reset engine state
    var onScanStarted: (() -> Unit)? = null

    /** Called when ROOM_ANALYSIS mode activates — signals TacticalInferenceEngine to start ARCore */
    var onEnterRoomMode: (() -> Unit)? = null

    /** Called when ROOM_ANALYSIS scan ends (tap or auto-timer) — signals to generate final summary */
    var onRoomScanEnd: (() -> Unit)? = null

    @Volatile var roomScanEndMode: String = "auto"           // "tap" or "auto"
    @Volatile var roomAutoScanDurationMs: Long = 30_000L     // 30 seconds

    private var roomAutoTimerJob: Job? = null

    // Software double-tap detection: G2 sends two rapid TAPs rather than a DOUBLE_TAP packet
    @Volatile private var lastTapMs = 0L
    private val DOUBLE_TAP_WINDOW_MS = 700L   // 700ms window — generous for BLE latency

    // HUD throttle: glasses display updates at most once every 5 seconds during active scan
    @Volatile private var lastHudUpdateMs = 0L
    private val HUD_UPDATE_INTERVAL_MS = 5_000L

    // ──────────────────── Gesture entry point ────────────────────

    fun onGestureEvent(gesture: GestureType) {
        Log.d(TAG, "Gesture: $gesture in mode: $currentMode")
        when (currentMode) {
            VisionMode.IDLE -> handleIdleGesture(gesture)
            VisionMode.MENU -> handleMenuGesture(gesture)
            else -> handleScanGesture(gesture)
        }
    }

    private fun handleScanGesture(gesture: GestureType) {
        // If a scan has already ended (e.g. room summary shown), treat any tap/double-tap
        // as an immediate return to menu instead of re-triggering end handlers.
        if (!isScanActive) {
            when (gesture) {
                GestureType.TAP, GestureType.DOUBLE_TAP, GestureType.LONG_PRESS -> {
                    cancelAndReturnToMenu()
                    return
                }
                else -> return
            }
        }

        when (gesture) {
            GestureType.DOUBLE_TAP -> {
                lastTapMs = 0L
                if (currentMode == VisionMode.ROOM_ANALYSIS && roomScanEndMode == "tap") {
                    roomAutoTimerJob?.cancel()
                    endRoomScan()
                } else {
                    cancelAndReturnToMenu()
                }
            }
            GestureType.TAP -> {
                if (currentMode == VisionMode.ROOM_ANALYSIS && roomScanEndMode == "tap") {
                    // In tap-end mode, a single tap should end the scan immediately.
                    lastTapMs = 0L
                    roomAutoTimerJob?.cancel()
                    endRoomScan()
                    return
                }
                val now = System.currentTimeMillis()
                val prev = lastTapMs
                if (now - prev < DOUBLE_TAP_WINDOW_MS) {
                    // Two rapid taps — treat as double-tap
                    lastTapMs = 0L
                    Log.d(TAG, "Software double-tap detected (${now - prev}ms between taps)")
                    if (currentMode == VisionMode.ROOM_ANALYSIS && roomScanEndMode == "tap") {
                        roomAutoTimerJob?.cancel()
                        endRoomScan()
                    } else {
                        cancelAndReturnToMenu()
                    }
                } else {
                    lastTapMs = now
                }
            }
            else -> { /* swipes ignored during scan */ }
        }
    }

    private fun handleIdleGesture(gesture: GestureType) {
        // Any gesture in IDLE opens the scan menu — user taps to wake, hold to confirm
        enterMenu()
    }

    private fun handleMenuGesture(gesture: GestureType) {
        when (gesture) {
            GestureType.SWIPE_FORWARD -> {
                menuIndex = (menuIndex + 1) % menuItems.size
                renderMenu()
            }
            GestureType.SWIPE_BACKWARD -> {
                menuIndex = (menuIndex - 1 + menuItems.size) % menuItems.size
                renderMenu()
            }
            GestureType.TAP -> {
                val selected = menuItems[menuIndex]
                startScan(selected)
            }
            GestureType.DOUBLE_TAP -> cancelAndReturnToIdle()
            GestureType.LONG_PRESS -> cancelAndReturnToIdle()
        }
    }

    // ──────────────────── Mode activation (voice shortcut) ────────────────────

    /** Called directly by VoiceCommandEngine, bypassing the menu. */
    fun activateMode(mode: VisionMode) {
        if (mode == VisionMode.IDLE) {
            cancelAndReturnToIdle()
            return
        }
        Log.i(TAG, "Voice activation: $mode")
        startScan(mode)
    }

    // ──────────────────── Scan frame entry point ────────────────────

    /**
     * Called by TacticalInferenceEngine once per analysis frame during an active scan.
     * Scanning is continuous. HUD updates are throttled to [HUD_UPDATE_INTERVAL_MS] (5s)
     * to avoid flickering. The Android phone overlay updates every frame regardless
     * (driven by VisualizationBus directly from the engines).
     */
    fun onScanFrame(result: ScanResult) {
        if (!isScanActive) return
        val now = System.currentTimeMillis()
        if (now - lastHudUpdateMs >= HUD_UPDATE_INTERVAL_MS) {
            lastHudUpdateMs = now
            pushHud(result.toHudString())
        }
    }

    // ──────────────────── State transitions ────────────────────

    private fun enterMenu() {
        currentMode = VisionMode.MENU
        menuIndex = 0
        renderMenu()
        Log.i(TAG, "Entered MENU")
    }

    private fun startScan(mode: VisionMode) {
        currentMode = mode
        isScanActive = true
        lastTapMs = 0L
        lastHudUpdateMs = 0L  // allow first real result to push immediately
        onScanStarted?.invoke()
        if (mode == VisionMode.ROOM_ANALYSIS) {
            onEnterRoomMode?.invoke()
            startRoomAutoTimerIfNeeded()
        }
        val modeLine = if (mode == VisionMode.TRANSLATION) "LISTENING..." else "SCANNING..."
        pushHud("${modeLabel(mode)}\n$modeLine")
        Log.i(TAG, "Started continuous scan for $mode")
    }

    private fun cancelAndReturnToMenu() {
        roomAutoTimerJob?.cancel()
        isScanActive = false
        lastTapMs = 0L
        VisualizationBus.postFrame(null)
        enterMenu()
        Log.i(TAG, "Scan cancelled — returned to MENU")
    }

    private fun cancelAndReturnToIdle() {
        roomAutoTimerJob?.cancel()
        isScanActive = false
        lastTapMs = 0L
        returnToIdle()
        Log.i(TAG, "Cancelled — returned to IDLE")
    }

    private fun returnToIdle() {
        currentMode = VisionMode.IDLE
        isScanActive = false
        VisualizationBus.postFrame(null)
        renderIdle()
    }

    // ──────────────────── HUD rendering ────────────────────

    private fun renderIdle() {
        pushHud("[TAP] Scan Menu")
    }

    private fun renderMenu() {
        val sb = StringBuilder()
        menuItems.forEachIndexed { idx, mode ->
            val prefix = if (idx == menuIndex) "> " else "  "
            sb.appendLine("$prefix${modeLabel(mode)}")
        }
        pushHud(sb.toString().trimEnd())
    }

    private fun pushHud(contentLines: String) {
        EvenG2TacticalService.instance?.pushHudContent(contentLines)
            ?: Log.w(TAG, "Service not available, dropping HUD update")
    }

    private fun startRoomAutoTimerIfNeeded() {
        if (roomScanEndMode != "auto") return
        roomAutoTimerJob?.cancel()
        roomAutoTimerJob = scope.launch {
            delay(roomAutoScanDurationMs)
            if (currentMode == VisionMode.ROOM_ANALYSIS && isScanActive) {
                Log.i(TAG, "Room auto-timer expired — ending scan")
                endRoomScan()
            }
        }
    }

    private fun endRoomScan() {
        isScanActive = false
        onRoomScanEnd?.invoke()
        pushHud("ROOM SCAN\nCOMPLETE\nTap to exit")
    }

    /**
     * Called when room analysis cannot start or loses ARCore session.
     * Exits active scan state and returns to menu with an explicit failure HUD.
     */
    fun failRoomScan(message: String) {
        roomAutoTimerJob?.cancel()
        isScanActive = false
        lastTapMs = 0L
        currentMode = VisionMode.MENU
        menuIndex = 0
        VisualizationBus.postFrame(null)
        Log.w(TAG, "ROOM_ANALYSIS aborted: $message")
        pushHud("ROOM ANALYSIS\n$message\n[TAP] Retry")
    }

    // ──────────────────── Helpers ────────────────────

    private fun modeLabel(mode: VisionMode) = when (mode) {
        VisionMode.FACE_SCAN -> "FACE SCAN"
        VisionMode.SYMBOL_SCAN -> "SYMBOL SCAN"
        VisionMode.CULTURAL_CONTEXT -> "CULTURAL CTX"
        VisionMode.ROOM_ANALYSIS -> "ROOM ANALYSIS"
        VisionMode.TRANSLATION -> "TRANSLATION"
        VisionMode.IDLE -> "IDLE"
        VisionMode.MENU -> "MENU"
    }

}
