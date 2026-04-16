package com.evensocom.psyopvisr.vision

import android.util.Log
import com.evensocom.psyopvisr.service.EvenG2TacticalService
import kotlinx.coroutines.*

enum class VisionMode {
    IDLE, MENU, FACE_SCAN, SYMBOL_SCAN, CULTURAL_CONTEXT, ROOM_ANALYSIS
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
        val confidence: Float
    ) : ScanResult() {
        override fun toHudString(): String {
            val prefix = if (alertLevel >= 2) "⚠ CRITICAL:" else ""
            return "SYMBOL: $label / $position\n$prefix $context\nConf: ${(confidence * 100).toInt()}%"
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

    @Volatile var currentMode: VisionMode = VisionMode.IDLE
        private set

    /** True while the countdown is running and frames should be processed. */
    @Volatile var isScanActive: Boolean = false
        private set

    private val menuItems = listOf(
        VisionMode.FACE_SCAN,
        VisionMode.SYMBOL_SCAN,
        VisionMode.CULTURAL_CONTEXT,
        VisionMode.ROOM_ANALYSIS
    )
    private var menuIndex = 0

    private var countdownJob: Job? = null
    private var resultJob: Job? = null

    // Last scan result for the RESULT display
    private var pendingResult: ScanResult? = null

    // Optional callback invoked whenever a new scan starts, so callers can reset engine state
    var onScanStarted: (() -> Unit)? = null

    // ──────────────────── Gesture entry point ────────────────────

    fun onGestureEvent(gesture: GestureType) {
        Log.d(TAG, "Gesture: $gesture in mode: $currentMode")
        when (currentMode) {
            VisionMode.IDLE -> handleIdleGesture(gesture)
            VisionMode.MENU -> handleMenuGesture(gesture)
            else -> { /* during countdown / result display, ignore gestures */ }
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
            // TAP or DOUBLE_TAP = select current item and start scan countdown
            GestureType.TAP, GestureType.DOUBLE_TAP -> {
                val selected = menuItems[menuIndex]
                startCountdown(selected)
            }
            GestureType.LONG_PRESS -> {
                cancelAndReturnToIdle()
            }
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
        startCountdown(mode)
    }

    // ──────────────────── Scan frame entry point ────────────────────

    /**
     * Called by TacticalInferenceEngine once per analysis frame during an active scan.
     *
     * If the result is a frame-confirmation progress report (ErrorResult starting with "SCANNING"),
     * the HUD is updated with progress but the scan stays active. Any other result (real detection
     * or non-progress error) stops the scan and shows the final result.
     */
    fun onScanFrame(result: ScanResult) {
        if (!isScanActive) return
        if (result is ScanResult.ErrorResult && result.message.startsWith("SCANNING")) {
            // Still confirming frames — update HUD with progress but keep scan active
            pushHud(result.toHudString())
            return
        }
        // Real result (or non-progress error) — stop scan and show result
        isScanActive = false
        pendingResult = result
        countdownJob?.cancel()
        showResult(result)
    }

    // ──────────────────── State transitions ────────────────────

    private fun enterMenu() {
        currentMode = VisionMode.MENU
        menuIndex = 0
        renderMenu()
        Log.i(TAG, "Entered MENU")
    }

    private fun startCountdown(mode: VisionMode) {
        currentMode = mode
        countdownJob?.cancel()
        // Notify registered engines to reset frame-confirmation state
        onScanStarted?.invoke()
        countdownJob = scope.launch {
            val totalSeconds = 5
            for (remaining in totalSeconds downTo 1) {
                val bar = buildProgressBar(totalSeconds - remaining, totalSeconds)
                val label = modeLabel(mode)
                pushHud("$label\n$bar ${remaining}s")
                isScanActive = true
                delay(1_000)
            }
            // If we get here with no frame result, timeout
            if (isScanActive) {
                isScanActive = false
                Log.w(TAG, "Scan timeout for $mode — no frame result received")
                showResult(ScanResult.ErrorResult("SCAN TIMEOUT\nNo result obtained"))
            }
        }
        Log.i(TAG, "Started countdown for $mode")
    }

    private fun showResult(result: ScanResult) {
        resultJob?.cancel()
        resultJob = scope.launch {
            pushHud(result.toHudString())
            delay(8_000)
            returnToIdle()
        }
        Log.i(TAG, "Showing result for 8s: ${result.toHudString()}")
    }

    private fun cancelAndReturnToIdle() {
        countdownJob?.cancel()
        resultJob?.cancel()
        isScanActive = false
        returnToIdle()
        Log.i(TAG, "Cancelled — returned to IDLE")
    }

    private fun returnToIdle() {
        currentMode = VisionMode.IDLE
        isScanActive = false
        renderIdle()
    }

    // ──────────────────── HUD rendering ────────────────────

    private fun renderIdle() {
        pushHud("[HOLD] Scan Menu")
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

    // ──────────────────── Helpers ────────────────────

    private fun modeLabel(mode: VisionMode) = when (mode) {
        VisionMode.FACE_SCAN -> "FACE SCAN"
        VisionMode.SYMBOL_SCAN -> "SYMBOL SCAN"
        VisionMode.CULTURAL_CONTEXT -> "CULTURAL CTX"
        VisionMode.ROOM_ANALYSIS -> "ROOM ANALYSIS"
        VisionMode.IDLE -> "IDLE"
        VisionMode.MENU -> "MENU"
    }

    /**
     * Builds a progress bar string like [■■□□□] where filledCount squares are filled.
     * @param elapsedTicks number of elapsed ticks (0 = none filled)
     * @param total total ticks
     */
    private fun buildProgressBar(elapsedTicks: Int, total: Int): String {
        val filled = elapsedTicks.coerceIn(0, total)
        val empty = total - filled
        return "[" + "■".repeat(filled) + "□".repeat(empty) + "]"
    }
}
