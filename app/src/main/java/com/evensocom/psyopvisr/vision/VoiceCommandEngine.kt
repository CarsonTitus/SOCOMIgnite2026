package com.evensocom.psyopvisr.vision

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.*

/**
 * Listens for voice commands using Android's on-device SpeechRecognizer and routes them
 * directly to [VisionModeController.activateMode] — bypassing the gesture menu.
 *
 * Commands (case-insensitive keyword matching):
 *   "face" / "face scan"         → FACE_SCAN
 *   "symbol" / "symbol scan"     → SYMBOL_SCAN
 *   "cultural" / "context"       → CULTURAL_CONTEXT
 *   "room" / "room analysis" / "map" → ROOM_ANALYSIS
 *   "cancel" / "stop"            → IDLE
 *
 * Listening loops continuously when the controller is in IDLE state.
 * Uses EXTRA_PREFER_OFFLINE = true for air-gapped deployments.
 */
class VoiceCommandEngine(private val context: Context) {

    companion object {
        private const val TAG = "VoiceCommandEngine"
    }

    private var recognizer: SpeechRecognizer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @Volatile private var isListening = false
    @Volatile private var destroyed = false

    // ──────────────────── Public API ────────────────────

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "SpeechRecognizer not available on this device")
            return
        }
        destroyed = false
        scheduleNextListen()
        Log.i(TAG, "Voice command engine started")
    }

    fun stopListening() {
        destroyed = true
        isListening = false
        scope.launch {
            recognizer?.stopListening()
            recognizer?.destroy()
            recognizer = null
        }
        Log.i(TAG, "Voice command engine stopped")
    }

    // ──────────────────── Recognition loop ────────────────────

    private fun scheduleNextListen() {
        if (destroyed) return
        scope.launch {
            delay(500) // brief pause between sessions
            if (!destroyed) beginListenSession()
        }
    }

    private fun beginListenSession() {
        if (destroyed || isListening) return

        // Only listen when not already in a scan mode
        val ctrl = VisionModeController.instance
        if (ctrl != null && ctrl.currentMode != VisionMode.IDLE) {
            scheduleNextListen()
            return
        }

        isListening = true

        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
        }

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    for (phrase in matches) {
                        val mode = matchCommand(phrase)
                        if (mode != null) {
                            Log.i(TAG, "Voice command matched: \"$phrase\" → $mode")
                            VisionModeController.instance?.activateMode(mode)
                            scheduleNextListen()
                            return
                        }
                    }
                    Log.d(TAG, "No command matched in: $matches")
                }
                scheduleNextListen()
            }

            override fun onError(error: Int) {
                isListening = false
                val errorName = speechErrorName(error)
                Log.w(TAG, "SpeechRecognizer error: $errorName ($error)")
                // On network/audio errors, back off a bit longer before retrying
                scope.launch {
                    delay(if (error == SpeechRecognizer.ERROR_NETWORK) 5_000L else 1_500L)
                    scheduleNextListen()
                }
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "End of speech")
            }
        })

        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening threw: $e")
            isListening = false
            scheduleNextListen()
        }
    }

    // ──────────────────── Command matching ────────────────────

    private fun matchCommand(phrase: String): VisionMode? {
        val lower = phrase.lowercase().trim()
        return when {
            lower.contains("cancel") || lower.contains("stop") -> VisionMode.IDLE
            lower.contains("face") -> VisionMode.FACE_SCAN
            lower.contains("symbol") -> VisionMode.SYMBOL_SCAN
            lower.contains("cultural") || lower.contains("context") -> VisionMode.CULTURAL_CONTEXT
            lower.contains("room") || lower.contains("map") -> VisionMode.ROOM_ANALYSIS
            else -> null
        }
    }

    private fun speechErrorName(error: Int) = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        else -> "UNKNOWN"
    }
}
