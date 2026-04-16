package com.evensocom.psyopvisr.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.evensocom.psyopvisr.service.EvenG2TacticalService
import com.evensocom.psyopvisr.db.CulturalContextEngine
import com.evensocom.psyopvisr.vision.FaceDetectionEngine
import com.evensocom.psyopvisr.vision.RoomAnalysisEngine
import com.evensocom.psyopvisr.vision.SymbolRecognitionEngine
import com.evensocom.psyopvisr.vision.VisionMode
import com.evensocom.psyopvisr.vision.VisionModeController
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.receiveAsFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TacticalInferenceEngine(
    private val context: Context,
    private val culturalDb: CulturalContextEngine
) : ImageAnalysis.Analyzer {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var objectDetector: ObjectDetector
    private val whisper: WhisperTranscriber = WhisperTranscriber(context)
    private val lc3Decoder: Lc3Decoder = Lc3Decoder()

    // Vision mode scan engines (lazy to avoid construction on the main thread)
    private val faceDetector: FaceDetectionEngine by lazy { FaceDetectionEngine(context) }
    private var _symbolEngine: SymbolRecognitionEngine? = null
    private val symbolEngine: SymbolRecognitionEngine
        get() = _symbolEngine ?: SymbolRecognitionEngine(context, culturalDb).also { _symbolEngine = it }
    private val roomEngine: RoomAnalysisEngine by lazy { RoomAnalysisEngine(context) }

    private var lastTranslation = ""
    private var lastVisualAlert = ""

    init {
        setupVisionModel()
        scope.launch { whisper.init() }
        startAudioProcessingLoop()
        // Register with VisionModeController to reset frame-confirmation state on each new scan.
        // _symbolEngine is only non-null if it has already been lazily constructed; skip if not.
        VisionModeController.instance?.onScanStarted = {
            _symbolEngine?.resetConfirmation()
        }
    }

    private fun setupVisionModel() {
        try {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet.tflite").build())
                .setMaxResults(3)
                .setScoreThreshold(0.5f)
                .build()
            objectDetector = ObjectDetector.createFromOptions(context, options)
        } catch (e: Exception) {
            android.util.Log.e("PSYOP-VISR", "Vision Model failed to load (tflite missing from assets). Proceeding without visual recognition.")
        }
    }

    private fun startAudioProcessingLoop() {
        scope.launch {
            val sampleRate = 16000
            val windowSize = sampleRate * 2 * 2 // 2 seconds of audio
            val buffer = ByteBuffer.allocateDirect(windowSize).order(ByteOrder.LITTLE_ENDIAN)

            EvenG2TacticalService.instance?.audioBufferChannel?.receiveAsFlow()?.collect { chunk ->
                // Decode LC3 chunk immediately to PCM Bytes
                val decodedPcm = lc3Decoder.decode(chunk)

                if (buffer.remaining() >= decodedPcm.remaining()) {
                    buffer.put(decodedPcm)
                } else {
                    // Inference point
                    buffer.flip()
                    processAudioInference(buffer)
                    buffer.clear()

                    // In a real buffer ring we would copy the leftover
                    if (decodedPcm.remaining() <= buffer.capacity()) {
                        buffer.put(decodedPcm)
                    }
                }
            }
        }
    }

    private suspend fun processAudioInference(pcmData: ByteBuffer) {
        // Pixel 9 Tensor G4 optimized Whisper.cpp call
        val transcribedText = whisper.transcribeAudio(pcmData)

        if (transcribedText.isNotBlank()) {
            lastTranslation = transcribedText
            EvenG2TacticalService.instance?.updateHudDisplay(lastTranslation, lastVisualAlert)
        }
    }

    override fun analyze(imageProxy: ImageProxy) {
        val mode = VisionModeController.instance?.currentMode ?: VisionMode.IDLE

        when (mode) {
            VisionMode.FACE_SCAN -> {
                if (VisionModeController.instance?.isScanActive == true) {
                    val result = faceDetector.detect(imageProxy)
                    VisionModeController.instance?.onScanFrame(result)
                }
                imageProxy.close()
            }

            VisionMode.SYMBOL_SCAN, VisionMode.CULTURAL_CONTEXT -> {
                if (VisionModeController.instance?.isScanActive == true) {
                    val result = symbolEngine.detect(imageProxy)
                    VisionModeController.instance?.onScanFrame(result)
                }
                imageProxy.close()
            }

            VisionMode.ROOM_ANALYSIS -> {
                if (VisionModeController.instance?.isScanActive == true) {
                    val result = roomEngine.analyze(imageProxy)
                    VisionModeController.instance?.onScanFrame(result)
                }
                imageProxy.close()
            }

            else -> {
                // IDLE or MENU — drop the frame, do not run inference
                imageProxy.close()
            }
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            image.toBitmap()
        } catch (e: Exception) {
            null
        }
    }
}
