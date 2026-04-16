package com.evensocom.psyopvisr.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import com.evensocom.psyopvisr.db.CulturalContextEngine
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlinx.coroutines.runBlocking

/**
 * Runs object detection on a single camera frame and enriches results with tactical context
 * from [CulturalContextEngine].
 *
 * Reuses the same efficientdet.tflite model already loaded by TacticalInferenceEngine.
 * Returns the top 3 detections (by confidence) above 50% threshold, each annotated with
 * positional descriptor and CulturalContext text/alert level.
 *
 * If the model asset is missing, returns a [ScanResult.ErrorResult].
 */
class SymbolRecognitionEngine(
    private val context: Context,
    private val culturalDb: CulturalContextEngine
) {

    companion object {
        private const val TAG = "SymbolRecognitionEngine"
        private const val MODEL_ASSET = "efficientdet.tflite"
        private const val CONFIDENCE_THRESHOLD = 0.5f
        private const val PERSON_CONFIDENCE_THRESHOLD = 0.72f
        private const val MAX_RESULTS = 3
        private const val CONFIRMATION_FRAMES = 3
    }

    private var objectDetector: ObjectDetector? = null

    // Frame-confirmation state — prevents flickering false positives
    @Volatile private var consecutiveFrames = 0
    @Volatile private var lastLabel = ""

    init {
        initDetector()
    }

    private fun initDetector() {
        try {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL_ASSET)
                        .build()
                )
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(CONFIDENCE_THRESHOLD)
                .build()
            objectDetector = ObjectDetector.createFromOptions(context, options)
            Log.i(TAG, "SymbolRecognitionEngine: ObjectDetector initialized")
        } catch (e: Exception) {
            Log.w(TAG, "SymbolRecognitionEngine: ObjectDetector failed to load ($MODEL_ASSET missing): $e")
            objectDetector = null
        }
    }

    // ──────────────────── Detection ────────────────────

    /**
     * Detect objects in [imageProxy] and return the best annotated [ScanResult].
     * The ImageProxy is NOT closed here — the caller (TacticalInferenceEngine) closes it.
     *
     * Applies a frame-confirmation buffer: the same top label must appear in
     * [CONFIRMATION_FRAMES] consecutive frames before a result is returned.
     * Progress is reported as an ErrorResult starting with "SCANNING" so that
     * [VisionModeController] can update the HUD without terminating the scan.
     *
     * Person detections use a higher confidence threshold ([PERSON_CONFIDENCE_THRESHOLD])
     * and are classified as MILITARY PERSONNEL or CIVILIAN via [PersonClassifier].
     */
    fun detect(imageProxy: ImageProxy): ScanResult {
        val detector = objectDetector
            ?: return ScanResult.ErrorResult("MODEL NOT FOUND\nPlace efficientdet.tflite in assets/")

        val bitmap: Bitmap = try {
            imageProxy.toBitmap()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to convert ImageProxy to Bitmap: $e")
            return ScanResult.ErrorResult("FRAME ERROR\nCould not read camera frame")
        }

        val frameW = bitmap.width.toFloat()
        val frameH = bitmap.height.toFloat()

        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val results = detector.detect(mpImage)
            val detections = results.detections()

            if (detections.isNullOrEmpty()) {
                resetConfirmation()
                return ScanResult.ErrorResult("SYMBOL SCAN\nNo objects detected")
            }

            // Sort by confidence descending; apply per-label threshold filtering.
            // "person" requires PERSON_CONFIDENCE_THRESHOLD; all others use CONFIDENCE_THRESHOLD.
            val topDetection = detections
                .sortedByDescending { it.categories()?.firstOrNull()?.score() ?: 0f }
                .firstOrNull { det ->
                    val rawLabel = det.categories()?.firstOrNull()?.categoryName()?.lowercase() ?: ""
                    val score = det.categories()?.firstOrNull()?.score() ?: 0f
                    val threshold = if (rawLabel == "person") PERSON_CONFIDENCE_THRESHOLD else CONFIDENCE_THRESHOLD
                    score >= threshold
                }

            val topLabel = topDetection?.categories()?.firstOrNull()?.categoryName() ?: ""

            // Frame-confirmation logic
            if (topLabel.isNotBlank() && topLabel == lastLabel) {
                consecutiveFrames++
            } else {
                consecutiveFrames = 1
                lastLabel = topLabel
            }

            if (consecutiveFrames < CONFIRMATION_FRAMES) {
                return ScanResult.ErrorResult("SCANNING...\n${consecutiveFrames}/$CONFIRMATION_FRAMES frames")
            }

            // Confirmed — reset state and build result
            consecutiveFrames = 0
            lastLabel = ""

            if (topDetection == null) {
                return ScanResult.ErrorResult("SYMBOL SCAN\nNo objects detected")
            }

            val rawLabel = topDetection.categories()?.firstOrNull()?.categoryName() ?: "UNKNOWN"
            val score = topDetection.categories()?.firstOrNull()?.score() ?: 0f
            val bbox = topDetection.boundingBox()
            val centerX = (bbox.left + bbox.right) / 2f
            val centerY = (bbox.top + bbox.bottom) / 2f
            val xPos = classifyXPosition(centerX / frameW)
            val yPos = classifyYPosition(centerY / frameH)

            // For "person" detections: classify as military or civilian
            val displayLabel: String = if (rawLabel.lowercase() == "person") {
                val personType: PersonClassifier.PersonType = try {
                    val left = bbox.left.toInt().coerceIn(0, bitmap.width - 1)
                    val top = bbox.top.toInt().coerceIn(0, bitmap.height - 1)
                    val right = bbox.right.toInt().coerceIn(left + 1, bitmap.width)
                    val bottom = bbox.bottom.toInt().coerceIn(top + 1, bitmap.height)
                    val cropW = right - left
                    val cropH = bottom - top
                    if (cropW > 0 && cropH > 0) {
                        val crop = Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
                        PersonClassifier.classify(crop)
                    } else {
                        PersonClassifier.PersonType.UNKNOWN
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Person crop/classify failed: $e")
                    PersonClassifier.PersonType.UNKNOWN
                }
                when (personType) {
                    PersonClassifier.PersonType.MILITARY -> "MILITARY PERSONNEL"
                    PersonClassifier.PersonType.CIVILIAN -> "CIVILIAN"
                    PersonClassifier.PersonType.UNKNOWN -> "PERSON"
                }
            } else {
                rawLabel.uppercase()
            }

            // Look up cultural context (blocking call is acceptable on the analysis executor thread)
            val ctxEntry = runBlocking { culturalDb.getContextFullEntry(rawLabel) }
            val contextText = ctxEntry?.contextText ?: "No tactical context"
            val alertLevel = ctxEntry?.alertLevel ?: 0

            ScanResult.SymbolResult(
                label = displayLabel,
                position = "$xPos / $yPos",
                context = contextText,
                alertLevel = alertLevel,
                confidence = score
            )
        } catch (e: Exception) {
            Log.e(TAG, "Symbol recognition inference failed: $e")
            ScanResult.ErrorResult("SYMBOL ERROR\n${e.message?.take(40) ?: "Unknown error"}")
        }
    }

    /**
     * Reset frame-confirmation state. Call when starting a new scan to avoid carrying
     * over stale consecutive-frame counts from a previous scan session.
     */
    fun resetConfirmation() {
        consecutiveFrames = 0
        lastLabel = ""
    }

    // ──────────────────── Classification helpers ────────────────────

    private fun classifyXPosition(normalizedX: Float): String = when {
        normalizedX < 0.2f -> "FAR LEFT"
        normalizedX < 0.4f -> "LEFT"
        normalizedX < 0.6f -> "CENTER"
        normalizedX < 0.8f -> "RIGHT"
        else -> "FAR RIGHT"
    }

    private fun classifyYPosition(normalizedY: Float): String = when {
        normalizedY < 0.33f -> "TOP"
        normalizedY < 0.66f -> "MID"
        else -> "BOTTOM"
    }
}
