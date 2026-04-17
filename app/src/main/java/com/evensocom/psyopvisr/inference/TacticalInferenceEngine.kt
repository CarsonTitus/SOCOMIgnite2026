package com.evensocom.psyopvisr.inference

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.evensocom.psyopvisr.service.EvenG2TacticalService
import com.evensocom.psyopvisr.db.CulturalContextEngine
import com.evensocom.psyopvisr.vision.FaceDetectionEngine
import com.evensocom.psyopvisr.vision.RoomAnalysisEngine
import com.evensocom.psyopvisr.vision.SceneContextInferenceEngine
import com.evensocom.psyopvisr.vision.SymbolRecognitionEngine
import com.evensocom.psyopvisr.vision.VisionMode
import com.evensocom.psyopvisr.vision.VisionModeController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.receiveAsFlow
import java.nio.ByteBuffer

/**
 * ImageAnalysis.Analyzer that dispatches each camera frame to the correct scan engine.
 *
 * Owned by [MainActivity] (which also owns the camera). [EvenG2TacticalService] accesses
 * it via the companion [instance] when it needs to reference engine state.
 *
 * Camera binding is NOT done here — see MainActivity.bindCamera().
 */
class TacticalInferenceEngine(
    private val context: Context,
    private val culturalDb: CulturalContextEngine
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "TacticalInferenceEngine"
        private const val ROOM_SESSION_MISSING_TICKS_LIMIT = 50
        private const val ROOM_FRAME_FAILURE_LIMIT = 45
        private const val ROOM_FALLBACK_FRAME_INTERVAL_MS = 120L
        var instance: TacticalInferenceEngine? = null
            private set
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val lc3Decoder: Lc3Decoder = Lc3Decoder()
    private val translationEngine: ArabicSpeechTranslationEngine = ArabicSpeechTranslationEngine(context)

    // Vision scan engines — lazy to avoid construction on the main thread
    private val faceDetector: FaceDetectionEngine by lazy { FaceDetectionEngine(context) }
    private var _symbolEngine: SymbolRecognitionEngine? = null
    private val symbolEngine: SymbolRecognitionEngine
        get() = _symbolEngine ?: SymbolRecognitionEngine(context, culturalDb).also { _symbolEngine = it }
    private val sceneContextEngine: SceneContextInferenceEngine by lazy {
        SceneContextInferenceEngine(context, culturalDb)
    }
    private val roomEngine: RoomAnalysisEngine by lazy { RoomAnalysisEngine(context) }

    private var roomScanJob: Job? = null
    private var translationInitJob: Job? = null
    @Volatile private var translationReady = false
    private var translationLastHudText = ""
    private var translationLastStatusPushMs = 0L
    private var translationSessionActive = false
    private var translationAudioChunkCount = 0L
    private var translationDecodedBytes = 0L
    private var translationRawChunkCount = 0L
    private var translationRawBytes = 0L
    private var translationLastAudioMs = 0L
    private var translationLastDebugMs = 0L
    @Volatile private var roomCameraFallbackActive = false
    @Volatile private var roomFallbackLastAnalyzeMs = 0L

    /** Called by TacticalInferenceEngine when it needs to unbind CameraX (before ARCore start) */
    var onUnbindCameraX: (() -> Unit)? = null

    /** Called by TacticalInferenceEngine when it needs to rebind CameraX (after ARCore stop) */
    var onRebindCameraX: (() -> Unit)? = null

    init {
        instance = this
        startAudioProcessingLoop()
        setupRoomModeCallbacks()
    }

    private fun setupRoomModeCallbacks() {
        val controller = VisionModeController.instance ?: return

        val roomEngineRef = roomEngine
        controller.roomScanEndMode = roomEngineRef.getScanEndMode()
        controller.roomAutoScanDurationMs = roomEngineRef.getAutoScanDurationMs()

        controller.onEnterRoomMode = {
            roomScanJob?.cancel()
            roomCameraFallbackActive = false
            roomFallbackLastAnalyzeMs = 0L
            Log.i(TAG, "Entering ROOM_ANALYSIS: unbinding CameraX then starting ARCore session")
            scope.launch(Dispatchers.Default) {
                roomEngineRef.stopSession()
                withContext(Dispatchers.Main) {
                    onUnbindCameraX?.invoke()
                }
                // Give camera stack a moment to fully release.
                delay(350)

                var started = false
                repeat(4) { attempt ->
                    roomEngineRef.startSession()
                    if (roomEngineRef.isSessionRunning()) {
                        started = true
                        Log.i(TAG, "ARCore room session started on attempt ${attempt + 1}")
                        return@repeat
                    }
                    Log.w(TAG, "ARCore room session failed to start on attempt ${attempt + 1}, retrying...")
                    delay(400)
                }

                if (started) {
                    roomCameraFallbackActive = false
                    startRoomScanLoop()
                } else {
                    Log.w(TAG, "ARCore room session failed after retries; switching to CameraX fallback")
                    roomEngineRef.activateCameraFallback("ARCore start failed")
                    roomCameraFallbackActive = true
                    roomFallbackLastAnalyzeMs = 0L
                    withContext(Dispatchers.Main) {
                        onRebindCameraX?.invoke()
                    }
                    controller.onScanFrame(
                        com.evensocom.psyopvisr.vision.ScanResult.RoomResult(
                            "ROOM SCAN\nARCore unavailable\nUsing camera fallback"
                        )
                    )
                }
            }
        }

        controller.onRoomScanEnd = {
            roomScanJob?.cancel()
            Log.i(TAG, "Room scan end requested: generating final summary + stopping ARCore")
            scope.launch(Dispatchers.Default) {
                val usedCameraFallback = roomCameraFallbackActive
                val finalResult = roomEngineRef.generateFinalSummary()
                // Scan has already been marked inactive, so push final summary directly.
                EvenG2TacticalService.instance?.pushHudContent(finalResult.toHudString())
                roomEngineRef.stopSession()
                roomCameraFallbackActive = false
                roomFallbackLastAnalyzeMs = 0L
                if (!usedCameraFallback) {
                    withContext(Dispatchers.Main) {
                        kotlinx.coroutines.delay(500)
                        onRebindCameraX?.invoke()
                    }
                }
            }
        }

        controller.onScanStarted = {
            if (controller.currentMode == VisionMode.TRANSLATION) {
                EvenG2TacticalService.instance?.pushHudContent(
                    "TRANSLATION\nPreparing Arabic translator..."
                )
                ensureTranslationReady()
            }
        }
    }

    private fun startRoomScanLoop() {
        roomScanJob?.cancel()
        roomScanJob = scope.launch(Dispatchers.Default) {
            Log.i(TAG, "Room scan loop started")
            var frameCounter = 0
            var missingSessionTicks = 0
            var failed = false
            var shouldCleanupSession = false
            var switchedToFallback = false
            var fallbackReason = ""
            try {
                while (isActive) {
                    val controller = VisionModeController.instance
                    val active = controller?.isScanActive == true
                    val inRoom = controller?.currentMode == VisionMode.ROOM_ANALYSIS
                    if (!active || !inRoom) {
                        // If we leave room mode without calling onRoomScanEnd, cleanup is required.
                        shouldCleanupSession = !inRoom
                        break
                    }

                    if (!roomEngine.isSessionRunning()) {
                        missingSessionTicks++
                        if (missingSessionTicks > ROOM_SESSION_MISSING_TICKS_LIMIT) {
                            Log.w(TAG, "Room scan loop: ARCore session unavailable too long, switching fallback")
                            switchedToFallback = true
                            fallbackReason = "ARCore session lost"
                            shouldCleanupSession = true
                            break
                        }
                        delay(120)
                        continue
                    } else {
                        missingSessionTicks = 0
                    }

                    val result = roomEngine.analyzeFrame()
                    controller?.onScanFrame(result)
                    if (roomEngine.getConsecutiveArFrameFailures() > ROOM_FRAME_FAILURE_LIMIT) {
                        Log.w(TAG, "Room scan loop: ARCore frame acquisition repeatedly failed, switching fallback")
                        switchedToFallback = true
                        fallbackReason = "ARCore tracking unavailable"
                        shouldCleanupSession = true
                        break
                    }
                    frameCounter++
                    if (frameCounter % 10 == 0) {
                        Log.d(TAG, "Room scan frames processed=$frameCounter")
                    }

                    // ~8 FPS update cadence for room mapping/HUD.
                    delay(120)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Room scan loop crashed", e)
                val controller = VisionModeController.instance
                if (controller?.currentMode == VisionMode.ROOM_ANALYSIS && controller.isScanActive) {
                    switchedToFallback = true
                    fallbackReason = "ARCore loop crashed"
                    shouldCleanupSession = true
                } else {
                    failed = true
                    shouldCleanupSession = true
                    controller?.failRoomScan("Room scan crashed")
                }
            }

            if (shouldCleanupSession) {
                if (roomEngine.isSessionRunning()) {
                    roomEngine.stopSession()
                }
                if (switchedToFallback) {
                    roomEngine.activateCameraFallback(fallbackReason)
                    roomCameraFallbackActive = true
                    roomFallbackLastAnalyzeMs = 0L
                    withContext(Dispatchers.Main) {
                        onRebindCameraX?.invoke()
                    }
                    VisionModeController.instance?.onScanFrame(
                        com.evensocom.psyopvisr.vision.ScanResult.RoomResult(
                            "ROOM SCAN\n$fallbackReason\nUsing camera fallback"
                        )
                    )
                } else {
                    roomCameraFallbackActive = false
                    roomFallbackLastAnalyzeMs = 0L
                    withContext(Dispatchers.Main) {
                        onRebindCameraX?.invoke()
                    }
                }
            }
            Log.i(
                TAG,
                "Room scan loop stopped (failed=$failed cleanup=$shouldCleanupSession fallback=$switchedToFallback)"
            )
        }
    }

    private fun startAudioProcessingLoop() {
        scope.launch {
            while (isActive) {
                val audioFlow = EvenG2TacticalService.instance?.audioBufferChannel?.receiveAsFlow()
                if (audioFlow == null) {
                    delay(300)
                    continue
                }

                audioFlow.collect { chunk ->
                    val controller = VisionModeController.instance
                    val translationActive = controller?.currentMode == VisionMode.TRANSLATION &&
                        controller.isScanActive

                    if (!translationActive) {
                        if (translationSessionActive) {
                            translationEngine.resetSession()
                            lc3Decoder.reset()
                            translationSessionActive = false
                            translationLastHudText = ""
                            translationAudioChunkCount = 0L
                            translationDecodedBytes = 0L
                            translationRawChunkCount = 0L
                            translationRawBytes = 0L
                        }
                        return@collect
                    }

                    translationSessionActive = true
                    translationRawChunkCount++
                    translationRawBytes += chunk.size.toLong()
                    val decodedPcm = lc3Decoder.decode(chunk)
                    if (decodedPcm.remaining() <= 0) {
                        val now = System.currentTimeMillis()
                        if (now - translationLastDebugMs > 4_000) {
                            translationLastDebugMs = now
                            Log.w(
                                TAG,
                                "TRANSLATION raw audio present but decode empty: chunks=$translationRawChunkCount bytes=$translationRawBytes"
                            )
                            EvenG2TacticalService.instance?.pushHudContent(
                                "TRANSLATION\nMic stream active.\nDecoder warming up..."
                            )
                        }
                        return@collect
                    }
                    translationLastAudioMs = System.currentTimeMillis()
                    translationAudioChunkCount++
                    translationDecodedBytes += decodedPcm.remaining().toLong()
                    ensureTranslationReady()

                    if (!translationReady) {
                        val now = System.currentTimeMillis()
                        if (now - translationLastStatusPushMs > 3_000) {
                            translationLastStatusPushMs = now
                            EvenG2TacticalService.instance?.pushHudContent(
                                "TRANSLATION\nInitializing Arabic model..."
                            )
                        }
                        return@collect
                    }

                    val hadText = processTranslationAudio(decodedPcm)
                    val now = System.currentTimeMillis()
                    if (!hadText && now - translationLastDebugMs > 4_000) {
                        translationLastDebugMs = now
                        Log.i(
                            TAG,
                            "TRANSLATION audio ok: rawChunks=$translationRawChunkCount rawBytes=$translationRawBytes decodedChunks=$translationAudioChunkCount decodedBytes=$translationDecodedBytes"
                        )
                        if (translationLastHudText.isBlank() && now - translationLastStatusPushMs > 5_000) {
                            translationLastStatusPushMs = now
                            EvenG2TacticalService.instance?.pushHudContent(
                                "TRANSLATION\nListening... (audio stream active)"
                            )
                        }
                    }
                }
            }
        }
    }

    private fun ensureTranslationReady() {
        if (translationReady) return
        if (translationInitJob?.isActive == true) return
        translationInitJob = scope.launch(Dispatchers.IO) {
            try {
                EvenG2TacticalService.instance?.pushHudContent(
                    "TRANSLATION\nDownloading Arabic model..."
                )
                translationEngine.ensureInitialized()
                translationReady = true
                EvenG2TacticalService.instance?.pushHudContent(
                    "TRANSLATION\nReady. Speak Arabic."
                )
            } catch (e: Exception) {
                Log.e(TAG, "Translation engine init failed", e)
                EvenG2TacticalService.instance?.pushHudContent(
                    "TRANSLATION\nInit failed: ${e.message ?: "unknown"}"
                )
            }
        }
    }

    private suspend fun processTranslationAudio(pcmData: ByteBuffer): Boolean {
        try {
            val english = translationEngine.processPcmChunk(pcmData)?.trim().orEmpty()
            if (english.isBlank()) return false

            if (english == translationLastHudText) return false
            translationLastHudText = english

            val hudLine = if (english.length > 240) english.take(240) else english
            EvenG2TacticalService.instance?.pushHudContent(
                "TRANSLATION\n$hudLine"
            )
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Translation chunk processing failed: $e")
            return false
        }
    }

    override fun analyze(imageProxy: ImageProxy) {
        val controller = VisionModeController.instance
        val mode = controller?.currentMode ?: VisionMode.IDLE

        when (mode) {
            VisionMode.FACE_SCAN -> {
                if (controller?.isScanActive == true) {
                    val result = faceDetector.detect(imageProxy)
                    controller.onScanFrame(result)
                }
                imageProxy.close()
            }
            VisionMode.SYMBOL_SCAN -> {
                if (controller?.isScanActive == true) {
                    val result = symbolEngine.detect(imageProxy)
                    controller.onScanFrame(result)
                }
                imageProxy.close()
            }
            VisionMode.CULTURAL_CONTEXT -> {
                if (controller?.isScanActive == true) {
                    val result = sceneContextEngine.analyze(imageProxy)
                    controller.onScanFrame(result)
                }
                imageProxy.close()
            }
            VisionMode.ROOM_ANALYSIS -> {
                try {
                    if (controller?.isScanActive != true) return
                    val fallbackActive = roomCameraFallbackActive || roomEngine.isCameraFallbackActive()
                    if (!fallbackActive) return

                    val now = SystemClock.elapsedRealtime()
                    if (now - roomFallbackLastAnalyzeMs < ROOM_FALLBACK_FRAME_INTERVAL_MS) return
                    roomFallbackLastAnalyzeMs = now

                    val result = roomEngine.analyzeCameraFallbackFrame(imageProxy)
                    controller.onScanFrame(result)
                } finally {
                    imageProxy.close()
                }
            }
            VisionMode.TRANSLATION -> {
                // Translation mode uses G2 audio only.
                imageProxy.close()
            }
            else -> imageProxy.close()
        }
    }

    fun shutdown() {
        roomScanJob?.cancel()
        roomEngine.stopSession()
        roomCameraFallbackActive = false
        roomFallbackLastAnalyzeMs = 0L
        sceneContextEngine.close()
        translationEngine.close()
        lc3Decoder.close()
        scope.cancel()
        instance = null
    }
}
