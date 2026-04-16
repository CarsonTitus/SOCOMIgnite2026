package com.evensocom.psyopvisr.inference

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Interface to the whisper.cpp JNI bindings.
 *
 * The native library (libwhisper.so) and model (ggml-tiny.bin) are not linked
 * in the current build. The class keeps the correct interface shape so the
 * native integration can be dropped in without touching call-sites.
 *
 * Audio contract: 16 kHz, S16LE mono PCM, supplied as a direct [ByteBuffer].
 */
class WhisperTranscriber(private val context: Context) {

    companion object {
        private const val TAG = "WhisperTranscriber"
        private const val MODEL_ASSET_NAME = "ggml-tiny.bin"
        private const val NATIVE_LIB_NAME = "whisper"

        /** Minimum float samples to bother running inference (avoids empty-buffer noise). */
        private const val MIN_INFERENCE_SAMPLES = 3200 // 0.2 s @ 16 kHz
    }

    /** Opaque pointer to the native whisper_context. 0 = not loaded. */
    private var nativeContextPtr: Long = 0L

    /** Path to the model on internal storage, set by [init]. */
    private var modelPath: String? = null

    /** True once [init] has been called (regardless of native availability). */
    private var initAttempted = false

    // ──────────────────── Initialisation ────────────────────

    /**
     * Copy `ggml-tiny.bin` from assets to internal storage and (if the native
     * library is present) load it into a whisper_context.
     *
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    suspend fun init() = withContext(Dispatchers.IO) {
        if (initAttempted) return@withContext
        initAttempted = true

        val destFile = File(context.filesDir, MODEL_ASSET_NAME)

        // Copy model from assets if not already present
        if (!destFile.exists()) {
            try {
                context.assets.open(MODEL_ASSET_NAME).use { src ->
                    destFile.outputStream().use { dst -> src.copyTo(dst) }
                }
                Log.i(TAG, "Model copied to ${destFile.absolutePath} (${destFile.length()} bytes)")
            } catch (e: IOException) {
                Log.w(TAG, "Model asset '$MODEL_ASSET_NAME' not found in assets — running in simulation mode. ($e)")
                return@withContext
            }
        } else {
            Log.i(TAG, "Model already at ${destFile.absolutePath}")
        }

        modelPath = destFile.absolutePath

        // Attempt to load native library
        try {
            System.loadLibrary(NATIVE_LIB_NAME)
            // nativeContextPtr = WhisperLib.initContext(modelPath!!)
            // if (nativeContextPtr == 0L) Log.e(TAG, "whisper_init_from_file returned null")
            // else Log.i(TAG, "whisper_context loaded (ptr=$nativeContextPtr)")
            Log.i(TAG, "Native lib '$NATIVE_LIB_NAME' load attempted (stub — not linked yet)")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native lib '$NATIVE_LIB_NAME' not linked — simulation mode active. ($e)")
        }
    }

    // ──────────────────── Inference ────────────────────

    /**
     * Transcribe [pcmData] (16 kHz S16LE mono) to text.
     *
     * Returns an empty string when:
     *  - The buffer is too short to be meaningful.
     *  - The native context is not loaded (simulation path returns "" unless the
     *    buffer is large enough to represent audible speech).
     *
     * Must be called from a background coroutine; internally dispatches to [Dispatchers.Default].
     */
    @WorkerThread
    suspend fun transcribeAudio(pcmData: ByteBuffer): String = withContext(Dispatchers.Default) {
        val sampleCount = pcmData.remaining() / 2 // S16LE: 2 bytes per sample
        if (sampleCount < MIN_INFERENCE_SAMPLES) {
            return@withContext ""
        }

        // Convert S16LE → float32 normalised to [-1, 1] (whisper.cpp input format)
        val shortArray = ShortArray(sampleCount)
        pcmData.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortArray)
        val floatArray = FloatArray(sampleCount) { i -> shortArray[i].toFloat() / 32768.0f }

        // ── Native path (uncomment when libwhisper.so is linked) ──────────────
        // if (nativeContextPtr != 0L) {
        //     WhisperLib.fullTranscribe(nativeContextPtr, floatArray)
        //     return@withContext WhisperLib.getTextSegment(nativeContextPtr, 0) ?: ""
        // }

        // ── Simulation path ───────────────────────────────────────────────────
        // Return empty string — callers should not receive fake tactical text
        // in production builds. The simulation existed only as a development scaffold.
        ""
    }

    // ──────────────────── Cleanup ────────────────────

    fun release() {
        if (nativeContextPtr != 0L) {
            // WhisperLib.freeContext(nativeContextPtr)
            nativeContextPtr = 0L
        }
        initAttempted = false
        Log.i(TAG, "WhisperTranscriber released")
    }
}
