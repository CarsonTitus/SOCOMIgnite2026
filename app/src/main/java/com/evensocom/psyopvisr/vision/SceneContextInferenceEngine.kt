package com.evensocom.psyopvisr.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageProxy
import com.evensocom.psyopvisr.db.CulturalContextEngine
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * Inference engine for [VisionMode.CULTURAL_CONTEXT].
 *
 * Signal pipeline:
 *   1. YoloV8Detector religious/ideological symbol detection (Roboflow hosted API)
 *   2. EfficientDet generic object inventory (persons, vehicles, bags, etc.)
 *   3. OCR sign text inventory (ML Kit)
 *   4. MobileCLIP scene classification
 *   5. CulturalIntelligenceEngine compound assessment → [ScanResult.SymbolResult]
 *
 * The result is far richer than simple label lookup: it identifies factions,
 * cultural norms, behavioral protocols, and tactical implications derived from
 * the combination of all detected signals.
 */
class SceneContextInferenceEngine(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") culturalDb: CulturalContextEngine
) {

    companion object {
        private const val TAG = "SceneContextEngine"
        private const val EFFICIENTDET_ASSET = "efficientdet.tflite"
        private const val OBJ_CONF_THRESHOLD = 0.30f
        private const val OBJ_MAX_RESULTS    = 10

        private const val SIGN_MIN_CHARS  = 2
        private const val SIGN_MAX_CHARS  = 30
        private const val SIGN_MAX_WORDS  = 5
        private const val SIGN_MIN_UPPER  = 0.50f
        private const val SIGN_MIN_AREA   = 0.005f
    }

    // ── dependencies ────────────────────────────────────────────────────────

    private val symbolDetector  = YoloV8Detector()          // Roboflow hosted API
    private val mobileCLIP      = MobileCLIPClassifier(context)
    private val textRecognizer  = TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    private var objectDetector: ObjectDetector? = null

    init {
        try {
            objectDetector = ObjectDetector.createFromOptions(
                context,
                ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(EFFICIENTDET_ASSET).build())
                    .setMaxResults(OBJ_MAX_RESULTS)
                    .setScoreThreshold(OBJ_CONF_THRESHOLD)
                    .build()
            )
            Log.i(TAG, "EfficientDet object detector initialized")
        } catch (e: Exception) {
            Log.w(TAG, "EfficientDet unavailable ($EFFICIENTDET_ASSET missing): $e")
        }
    }

    // ── public API ───────────────────────────────────────────────────────────

    fun analyze(imageProxy: ImageProxy): ScanResult {
        val bitmap: Bitmap = try {
            imageProxy.toBitmap()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to convert ImageProxy to Bitmap: $e")
            return ScanResult.ErrorResult("CONTEXT ERROR\nCould not read camera frame")
        }

        val imageWidth  = bitmap.width
        val imageHeight = bitmap.height
        val rotDeg      = imageProxy.imageInfo.rotationDegrees

        return try {
            // ── Step 1: Religious symbol detection (Roboflow) ─────────────────
            val symbolBoxes: List<DetectionBox> = symbolDetector.detect(bitmap)
            val detectedSymbols: List<String> = symbolBoxes.map { it.className }

            // ── Step 2: Generic object inventory (EfficientDet) ───────────────
            val objectLabels: List<String> = runObjectDetector(bitmap)

            // ── Step 3: Sign text inventory (OCR) ────────────────────────────
            val signTexts: List<String> = runSignOcr(bitmap, rotDeg)

            // ── Step 4: Scene classification (MobileCLIP) ─────────────────────
            val clipResult    = mobileCLIP.classifyImage(bitmap, useScenePrompts = true)
            val sceneCategory = if (clipResult.label == "UNAVAILABLE") "unknown" else clipResult.label
            val clipConf      = if (clipResult.label == "UNAVAILABLE") 0.5f else clipResult.confidence

            // ── Step 5: CulturalIntelligenceEngine compound assessment ─────────
            val assessment = CulturalIntelligenceEngine.assess(
                detectedSymbols = detectedSymbols,
                sceneCategory   = sceneCategory,
                detectedObjects = objectLabels,
                signTexts       = signTexts,
                clipConfidence  = clipConf
            )

            // ── Step 6: Build VisualizationBus frame ──────────────────────────
            val vizDetections: List<SymbolDetection> = buildVizDetections(
                symbolBoxes, objectLabels, assessment
            )

            VisualizationBus.postFrame(
                VisualizationFrame.CulturalFrame(
                    imageWidth      = imageWidth,
                    imageHeight     = imageHeight,
                    rotationDegrees = rotDeg,
                    detections      = vizDetections,
                    contextSummary  = "${assessment.faction}\n${assessment.hudDetail.take(50)}"
                )
            )

            // ── Step 7: Build ScanResult ──────────────────────────────────────
            val facts = buildList {
                if (detectedSymbols.isNotEmpty())
                    add("Symbols: ${detectedSymbols.joinToString(", ").take(30)}")
                if (objectLabels.isNotEmpty())
                    add("Objects: ${objectLabels.distinct().take(3).joinToString(", ")}")
                if (signTexts.isNotEmpty())
                    add("Signs: ${signTexts.take(2).joinToString(" | ").take(30)}")
            }

            ScanResult.SymbolResult(
                label      = assessment.hudSummary.take(30),
                position   = sceneCategory.uppercase().take(20),
                context    = assessment.hudDetail,
                alertLevel = assessment.alertLevel,
                confidence = assessment.confidence,
                extras     = buildExtras(assessment, facts)
            )

        } catch (e: Exception) {
            Log.e(TAG, "SceneContextInferenceEngine failed: $e")
            ScanResult.ErrorResult("CONTEXT ERROR\n${e.message?.take(40) ?: "Unknown error"}")
        }
    }

    fun close() { mobileCLIP.close() }

    // ── viz helpers ──────────────────────────────────────────────────────────

    private fun buildVizDetections(
        symbolBoxes: List<DetectionBox>,
        objectLabels: List<String>,
        assessment: CulturalIntelligenceEngine.CulturalAssessment
    ): List<SymbolDetection> {
        val fromSymbols = symbolBoxes.take(4).map { box ->
            SymbolDetection(
                bbox        = box.bbox,
                label       = box.className.uppercase().replace("_", " "),
                confidence  = box.confidence,
                alertLevel  = assessment.alertLevel,
                contextText = assessment.hudDetail
            )
        }
        // If no symbol boxes but we have an assessment, show a centred placeholder box
        return fromSymbols.ifEmpty { emptyList() }
    }

    private fun buildExtras(
        assessment: CulturalIntelligenceEngine.CulturalAssessment,
        facts: List<String>
    ): List<String> {
        val lines = mutableListOf<String>()
        assessment.behavioralProtocols.firstOrNull()?.let { lines += it.take(35) }
        assessment.tacticalImplications.firstOrNull()?.let { lines += it.take(35) }
        if (lines.isEmpty()) lines += facts.take(2)
        return lines.take(2)
    }

    // ── Step 2: EfficientDet ─────────────────────────────────────────────────

    private data class ObjectItem(val label: String, val bbox: RectF, val confidence: Float)

    private fun runObjectDetector(bitmap: Bitmap): List<String> {
        val detector = objectDetector ?: return emptyList()
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            (detector.detect(mpImage).detections() ?: emptyList()).mapNotNull { det ->
                det.categories()?.firstOrNull()?.categoryName()
            }
        } catch (e: Exception) {
            Log.d(TAG, "EfficientDet skipped: $e"); emptyList()
        }
    }

    // ── Step 3: Sign OCR ─────────────────────────────────────────────────────

    private fun runSignOcr(bitmap: Bitmap, rotDeg: Int): List<String> {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, rotDeg)
            val result = Tasks.await(
                textRecognizer.process(inputImage), 2, TimeUnit.SECONDS
            )
            val imageArea = bitmap.width.toFloat() * bitmap.height.toFloat()
            result.textBlocks
                .filter { block -> looksLikeSign(block.text.trim(), block.boundingBox, imageArea) }
                .map { it.text.trim().uppercase() }
        } catch (e: Exception) {
            Log.d(TAG, "OCR skipped: $e"); emptyList()
        }
    }

    private fun looksLikeSign(text: String, bbox: android.graphics.Rect?, imageArea: Float): Boolean {
        if (text.length < SIGN_MIN_CHARS || text.length > SIGN_MAX_CHARS) return false
        if (text.trim().split(Regex("\\s+")).size > SIGN_MAX_WORDS) return false
        val letters = text.count { it.isLetter() }
        val uppers  = text.count { it.isUpperCase() }
        if (letters > 0 && uppers.toFloat() / letters < SIGN_MIN_UPPER) return false
        if (bbox != null) {
            val blockArea = bbox.width().toFloat() * bbox.height().toFloat()
            if (blockArea / imageArea < SIGN_MIN_AREA) return false
        }
        return true
    }
}
