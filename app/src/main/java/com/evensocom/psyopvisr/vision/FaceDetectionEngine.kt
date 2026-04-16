package com.evensocom.psyopvisr.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import org.json.JSONArray
import java.io.File
import kotlin.math.sqrt

/**
 * Detects faces in a camera frame and returns positional + identity information.
 *
 * Model: face_detection_short_range.tflite (must be placed in assets/).
 * Optional: facenet.tflite for identity matching against faces_db.json in filesDir.
 *
 * If either model file is missing the engine degrades gracefully:
 *  - Missing face_detection_short_range.tflite → returns ScanResult.ErrorResult("MODEL NOT FOUND")
 *  - Missing facenet.tflite → identity field = "UNKNOWN"
 */
class FaceDetectionEngine(private val context: Context) {

    companion object {
        private const val TAG = "FaceDetectionEngine"
        private const val FACE_MODEL_ASSET = "face_detection_short_range.tflite"
        private const val FACENET_MODEL_ASSET = "facenet.tflite"
        private const val FACES_DB_FILE = "faces_db.json"
        private const val COSINE_THRESHOLD = 0.75f
    }

    private var faceDetector: FaceDetector? = null
    private var hasFaceNet = false
    private var enrolledFaces: List<Pair<String, FloatArray>> = emptyList()

    init {
        initDetector()
        initFaceNet()
    }

    private fun initDetector() {
        try {
            val options = FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(FACE_MODEL_ASSET)
                        .build()
                )
                .setMinDetectionConfidence(0.5f)
                .build()
            faceDetector = FaceDetector.createFromOptions(context, options)
            Log.i(TAG, "FaceDetector initialized from $FACE_MODEL_ASSET")
        } catch (e: Exception) {
            Log.w(TAG, "FaceDetector failed to load ($FACE_MODEL_ASSET missing from assets): $e")
            faceDetector = null
        }
    }

    private fun initFaceNet() {
        val assetList = try { context.assets.list("") ?: emptyArray() } catch (_: Exception) { emptyArray() }
        hasFaceNet = FACENET_MODEL_ASSET in assetList
        if (hasFaceNet) {
            Log.i(TAG, "FaceNet model found — identity matching enabled")
            loadEnrolledFaces()
        } else {
            Log.i(TAG, "FaceNet model not found — identity will be UNKNOWN")
        }
    }

    private fun loadEnrolledFaces() {
        try {
            val dbFile = File(context.filesDir, FACES_DB_FILE)
            if (!dbFile.exists()) {
                Log.d(TAG, "faces_db.json not found — no enrolled faces")
                return
            }
            val json = JSONArray(dbFile.readText())
            val list = mutableListOf<Pair<String, FloatArray>>()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val name = obj.getString("name")
                val embArray = obj.getJSONArray("embedding")
                val emb = FloatArray(embArray.length()) { embArray.getDouble(it).toFloat() }
                list.add(name to emb)
            }
            enrolledFaces = list
            Log.i(TAG, "Loaded ${list.size} enrolled faces from faces_db.json")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load faces_db.json: $e")
        }
    }

    // ──────────────────── Detection ────────────────────

    /**
     * Detect faces in the given [ImageProxy] frame.
     * Returns a [ScanResult] for the first (highest-confidence) detection found.
     * The caller is responsible for closing the ImageProxy after this call returns.
     */
    fun detect(imageProxy: ImageProxy): ScanResult {
        val detector = faceDetector
            ?: return ScanResult.ErrorResult("MODEL NOT FOUND\nPlace face_detection_short_range.tflite in assets/")

        val bitmap: Bitmap = try {
            imageProxy.toBitmap()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to convert ImageProxy to Bitmap: $e")
            return ScanResult.ErrorResult("FRAME ERROR\nCould not read camera frame")
        }

        val frameW = bitmap.width.toFloat()
        val frameH = bitmap.height.toFloat()
        val frameArea = frameW * frameH

        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = detector.detect(mpImage)
            val detections = result.detections()

            if (detections.isNullOrEmpty()) {
                return ScanResult.ErrorResult("FACE SCAN\nNo face detected")
            }

            // Use the first (highest-confidence) detection
            val detection = detections[0]
            val bbox = detection.boundingBox()
            val score = detection.categories()?.firstOrNull()?.score() ?: 0f

            val centerX = (bbox.left + bbox.right) / 2f
            val centerY = (bbox.top + bbox.bottom) / 2f
            val bboxArea = bbox.width() * bbox.height()

            val xPos = classifyXPosition(centerX / frameW)
            val yPos = classifyYPosition(centerY / frameH)
            val dist = classifyDistance(sqrt(bboxArea / frameArea))

            val identity = if (hasFaceNet) {
                identifyFace(bitmap, bbox) ?: "UNKNOWN"
            } else {
                "UNKNOWN"
            }

            // Crop the face bounding box from the bitmap and classify person type
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

            ScanResult.FaceResult(
                position = "$xPos / $yPos",
                distance = dist,
                identity = identity,
                confidence = score,
                personType = personType
            )
        } catch (e: Exception) {
            Log.e(TAG, "Face detection inference failed: $e")
            ScanResult.ErrorResult("FACE SCAN ERROR\n${e.message?.take(40) ?: "Unknown error"}")
        }
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

    private fun classifyDistance(sqrtBboxRatio: Float): String = when {
        sqrtBboxRatio > 0.4f -> "CLOSE"
        sqrtBboxRatio >= 0.15f -> "MEDIUM"
        else -> "FAR"
    }

    // ──────────────────── FaceNet identity (stub — requires native interpreter) ────────────────────

    /**
     * Attempts identity matching against [enrolledFaces] using cosine similarity.
     * This is a stub that would require a full TFLite Interpreter integration.
     * Returns null to fall back to "UNKNOWN" when FaceNet is not fully wired.
     */
    private fun identifyFace(bitmap: Bitmap, bbox: android.graphics.RectF): String? {
        // FaceNet embedding extraction requires TFLite Interpreter with facenet.tflite.
        // The Interpreter class from org.tensorflow:tensorflow-lite is a separate dependency
        // not yet added to build.gradle.kts. Return null so the caller shows "UNKNOWN".
        // When tasks-vision exposes a FaceEmbedder API or Interpreter is added, wire it here:
        //
        //   val interpreter = Interpreter(loadModelFile(context, FACENET_MODEL_ASSET))
        //   val croppedFace = Bitmap.createBitmap(bitmap, bbox.left.toInt(), bbox.top.toInt(),
        //                                         bbox.width().toInt(), bbox.height().toInt())
        //   val input = preprocessForFaceNet(croppedFace)  // normalize to [-1,1], 160x160
        //   val output = Array(1) { FloatArray(512) }
        //   interpreter.run(input, output)
        //   val embedding = output[0]
        //   return findBestMatch(embedding)
        Log.d(TAG, "identifyFace: FaceNet stub — returning null (UNKNOWN)")
        return null
    }

    @Suppress("unused")
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom < 1e-9f) 0f else dot / denom
    }

    @Suppress("unused")
    private fun findBestMatch(embedding: FloatArray): String? {
        var bestName: String? = null
        var bestScore = 0f
        for ((name, enrolled) in enrolledFaces) {
            val sim = cosineSimilarity(embedding, enrolled)
            if (sim > bestScore) { bestScore = sim; bestName = name }
        }
        return if (bestScore >= COSINE_THRESHOLD) bestName else null
    }
}
