package com.evensocom.psyopvisr.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Detects faces in a camera frame and returns positional + identity information.
 *
 * Model: face_detection_short_range.tflite (must be placed in assets/).
 * OpenCV ORB matching is used for "ME" identity if enrolled from a gallery photo.
 */
data class EnrollmentResult(
    val success: Boolean,
    val message: String
)

class FaceDetectionEngine(private val context: Context) {

    companion object {
        private const val TAG = "FaceDetectionEngine"
        private const val FACE_MODEL_ASSET = "face_detection_short_range.tflite"
        private const val ENROLLED_ME_FILE = "face_me_enrolled.png"
        private const val ENROLLED_ME_PROFILE_DIR = "face_me_profile"
        private const val ENROLLED_ME_PREFIX = "face_me_"
        private const val FACE_IMAGE_SIZE = 160
        private const val MAX_ENROLLED_ME_PHOTOS = 5
        private const val MIN_GOOD_MATCHES = 12
        private const val MIN_GOOD_RATIO = 0.14f
        private const val RATIO_TEST_THRESHOLD = 0.78f
        private const val MAX_AVG_GOOD_DISTANCE = 72f
        private const val MATCH_SCORE_THRESHOLD = 0.34f
        private const val MIN_ENROLLMENT_KEYPOINTS = 16

        fun isMeEnrolled(context: Context): Boolean =
            getEnrolledTemplateCount(context) > 0

        fun getEnrolledTemplateCount(context: Context): Int {
            val profileDir = File(context.filesDir, ENROLLED_ME_PROFILE_DIR)
            val profileCount = profileDir.listFiles { file ->
                file.isFile && file.name.endsWith(".png", ignoreCase = true)
            }?.size ?: 0
            if (profileCount > 0) return profileCount
            return if (File(context.filesDir, ENROLLED_ME_FILE).exists()) 1 else 0
        }

        fun enrollMeFromPhoto(
            context: Context,
            bitmap: Bitmap,
            append: Boolean = false
        ): EnrollmentResult {
            val engine = FaceDetectionEngine(context)
            return engine.enrollMe(bitmap, append = append)
        }
    }

    private var faceDetector: FaceDetector? = null
    private var mlKitDetector: com.google.mlkit.vision.face.FaceDetector? = null
    private var openCvReady = false
    private var orb: ORB? = null
    private var matcher: BFMatcher? = null
    private val enrolledTemplates = mutableListOf<EnrolledTemplate>()
    private var enrolledProfileFingerprint: String? = null

    private data class EnrolledTemplate(
        val sourceName: String,
        val gray: Mat,
        val descriptors: Mat
    )

    init {
        initDetector()
        initOpenCv()
        refreshEnrolledFaceIfChanged(force = true)
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
        initMlKitDetector()
    }

    private fun initMlKitDetector() {
        try {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()
            mlKitDetector = FaceDetection.getClient(options)
            Log.i(TAG, "ML Kit face detector initialized")
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit face detector init failed: $e")
            mlKitDetector = null
        }
    }

    private fun initOpenCv() {
        openCvReady = try {
            OpenCVLoader.initDebug()
        } catch (e: Throwable) {
            Log.w(TAG, "OpenCV init failed: $e")
            false
        }
        if (openCvReady) {
            orb = ORB.create()
            matcher = BFMatcher.create(Core.NORM_HAMMING, false)
            Log.i(TAG, "OpenCV ready for enrollment + matching")
        } else {
            Log.w(TAG, "OpenCV unavailable; identity fallback remains UNKNOWN")
        }
    }

    // ──────────────────── Detection ────────────────────

    /**
     * Detect faces in the given [ImageProxy] frame.
     * Returns a [ScanResult] for the first (highest-confidence) detection found.
     * The caller is responsible for closing the ImageProxy after this call returns.
     */
    fun detect(imageProxy: ImageProxy): ScanResult {
        if (faceDetector == null && mlKitDetector == null) {
            return ScanResult.ErrorResult("FACE MODEL UNAVAILABLE\nCould not initialize face detection")
        }

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
            val detections = detectFaces(bitmap)

            if (detections.isEmpty()) {
                VisualizationBus.postFrame(
                    VisualizationFrame.FaceFrame(bitmap.width, bitmap.height,
                        imageProxy.imageInfo.rotationDegrees, emptyList())
                )
                return ScanResult.ErrorResult("FACE SCAN\nNo face detected")
            }

            // Use the first (highest-confidence) detection
            val detection = detections[0]
            val bbox = detection.bbox
            val score = detection.confidence

            val centerX = (bbox.left + bbox.right) / 2f
            val centerY = (bbox.top + bbox.bottom) / 2f
            val bboxArea = bbox.width() * bbox.height()

            val xPos = classifyXPosition(centerX / frameW)
            val yPos = classifyYPosition(centerY / frameH)
            val dist = classifyDistance(sqrt(bboxArea / frameArea))

            refreshEnrolledFaceIfChanged()

            val vizDetections = detections.mapNotNull { det ->
                val s = det.confidence
                val b = det.bbox
                val personType = classifyPersonType(bitmap, b)
                val identity = identifyFace(bitmap, b) ?: "UNKNOWN"
                FaceDetection(bbox = b, identity = identity, confidence = s, personType = personType)
            }
            val firstDet = vizDetections.firstOrNull()
                ?: return ScanResult.ErrorResult("FACE SCAN\nNo face detected")
            VisualizationBus.postFrame(
                VisualizationFrame.FaceFrame(
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                    detections = vizDetections
                )
            )

            ScanResult.FaceResult(
                position = "$xPos / $yPos",
                distance = dist,
                identity = firstDet.identity,
                confidence = score,
                personType = firstDet.personType
            )
        } catch (e: Exception) {
            Log.e(TAG, "Face detection inference failed: $e")
            ScanResult.ErrorResult("FACE SCAN ERROR\n${e.message?.take(40) ?: "Unknown error"}")
        }
    }

    private data class DetectedFace(
        val bbox: RectF,
        val confidence: Float
    )

    private fun detectFaces(bitmap: Bitmap): List<DetectedFace> {
        val mpDetector = faceDetector
        if (mpDetector != null) {
            return try {
                val mpImage = BitmapImageBuilder(bitmap).build()
                val result = mpDetector.detect(mpImage)
                (result.detections() ?: emptyList()).map { det ->
                    val score = det.categories()?.firstOrNull()?.score() ?: 0.8f
                    DetectedFace(RectF(det.boundingBox()), score)
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaPipe face detection failed, falling back to ML Kit: $e")
                detectFacesWithMlKit(bitmap)
            }
        }
        return detectFacesWithMlKit(bitmap)
    }

    private fun detectFacesWithMlKit(bitmap: Bitmap): List<DetectedFace> {
        val detector = mlKitDetector ?: return emptyList()
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = Tasks.await(detector.process(image), 1200, TimeUnit.MILLISECONDS)
            faces.map { face ->
                DetectedFace(RectF(face.boundingBox), 0.8f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit face detection failed: $e")
            emptyList()
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

    // ──────────────────── Enrollment + OpenCV matching ────────────────────

    fun enrollMe(sourceBitmap: Bitmap, append: Boolean = false): EnrollmentResult {
        if (faceDetector == null && mlKitDetector == null) {
            return EnrollmentResult(
                success = false,
                message = "Enrollment failed: face detector unavailable"
            )
        }
        if (!openCvReady) {
            return EnrollmentResult(
                success = false,
                message = "Enrollment failed: OpenCV not initialized"
            )
        }

        return try {
            val bestFace = findLargestFace(sourceBitmap)
                ?: return EnrollmentResult(
                    success = false,
                    message = "Enrollment failed: no face found in selected image"
                )
            val cropRect = clampRect(bestFace, sourceBitmap.width, sourceBitmap.height)
                ?: return EnrollmentResult(
                    success = false,
                    message = "Enrollment failed: invalid face crop"
                )
            val faceCrop = Bitmap.createBitmap(
                sourceBitmap,
                cropRect.left,
                cropRect.top,
                cropRect.width(),
                cropRect.height()
            )
            val features = buildFeatures(faceCrop)
                ?: return EnrollmentResult(
                    success = false,
                    message = "Enrollment failed: face lacks enough detail"
                )
            if (append) {
                migrateLegacyTemplateIfNeeded()
            }
            val existingTemplateCount = listProfileTemplateFiles().size
            if (append && existingTemplateCount >= MAX_ENROLLED_ME_PHOTOS) {
                features.gray.release()
                features.descriptors.release()
                return EnrollmentResult(
                    success = false,
                    message = "Enrollment failed: ME profile already has $MAX_ENROLLED_ME_PHOTOS photos"
                )
            }

            if (!append) {
                clearProfileOnDisk()
            }

            val templateFile = createNewTemplateFile()
            persistTemplate(features.gray, templateFile)
            // Keep legacy single-file profile for backward compatibility.
            persistTemplate(features.gray, File(context.filesDir, ENROLLED_ME_FILE))

            val kpCount = features.keypointCount
            features.gray.release()
            features.descriptors.release()
            refreshEnrolledFaceIfChanged(force = true)

            val enrolledCount = enrolledTemplates.size
            val action = if (append) "added" else "replaced"
            EnrollmentResult(
                success = true,
                message = "ME photo $action ($enrolledCount/$MAX_ENROLLED_ME_PHOTOS, $kpCount keypoints)"
            )
        } catch (e: Exception) {
            Log.e(TAG, "enrollMe failed", e)
            EnrollmentResult(
                success = false,
                message = "Enrollment failed: ${e.message?.take(48) ?: "unknown error"}"
            )
        }
    }

    private fun identifyFace(bitmap: Bitmap, bbox: RectF): String? {
        if (!openCvReady) return null
        if (enrolledTemplates.isEmpty()) return null
        val rect = clampRect(bbox, bitmap.width, bitmap.height) ?: return null
        val crop = try {
            Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        } catch (_: Exception) {
            return null
        }
        val candidate = buildFeatures(crop) ?: return null

        return try {
            val bestMatch = enrolledTemplates
                .map { template -> scoreCandidateAgainstTemplate(candidate, template) }
                .maxByOrNull { result -> result.score }

            if (bestMatch != null && bestMatch.accepted) "ME" else null
        } catch (e: Exception) {
            Log.d(TAG, "OpenCV face match failed: $e")
            null
        } finally {
            candidate.gray.release()
            candidate.descriptors.release()
        }
    }

    private data class MatchResult(
        val sourceName: String,
        val score: Float,
        val accepted: Boolean
    )

    private fun scoreCandidateAgainstTemplate(
        candidate: FaceFeatures,
        template: EnrolledTemplate
    ): MatchResult {
        val bfMatcher = matcher ?: return MatchResult(template.sourceName, score = 0f, accepted = false)
        val knnMatches = mutableListOf<MatOfDMatch>()
        return try {
            bfMatcher.knnMatch(candidate.descriptors, template.descriptors, knnMatches, 2)

            val goodDistances = mutableListOf<Float>()
            for (pair in knnMatches) {
                val matches = pair.toArray()
                if (matches.size < 2) continue
                val best = matches[0]
                val runnerUp = matches[1]
                if (best.distance < (RATIO_TEST_THRESHOLD * runnerUp.distance)) {
                    goodDistances.add(best.distance)
                }
            }

            if (goodDistances.isEmpty()) {
                return MatchResult(template.sourceName, score = 0f, accepted = false)
            }

            val goodMatchCount = goodDistances.size
            val maxComparisons = max(1, min(candidate.descriptors.rows(), template.descriptors.rows()))
            val goodRatio = goodMatchCount.toFloat() / maxComparisons.toFloat()
            val avgGoodDistance = goodDistances.average().toFloat()
            val distanceScore = (1f - (avgGoodDistance / 128f)).coerceIn(0f, 1f)
            val pixelSimilarity = computePixelSimilarity(candidate.gray, template.gray)
            val score = (goodRatio * 0.62f) + (distanceScore * 0.23f) + (pixelSimilarity * 0.15f)

            val accepted = goodMatchCount >= MIN_GOOD_MATCHES &&
                goodRatio >= MIN_GOOD_RATIO &&
                avgGoodDistance <= MAX_AVG_GOOD_DISTANCE &&
                score >= MATCH_SCORE_THRESHOLD

            MatchResult(template.sourceName, score = score, accepted = accepted)
        } finally {
            knnMatches.forEach { match -> match.release() }
        }
    }

    private data class FaceFeatures(
        val gray: Mat,
        val descriptors: Mat,
        val keypointCount: Int
    )

    private fun buildFeatures(faceBitmap: Bitmap): FaceFeatures? {
        val gray = toNormalizedGray(faceBitmap)
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        val mask = Mat()
        val orbEngine = orb
        if (orbEngine == null) {
            gray.release()
            keypoints.release()
            descriptors.release()
            mask.release()
            return null
        }

        return try {
            orbEngine.detectAndCompute(gray, mask, keypoints, descriptors)
            val kpCount = keypoints.toArray().size
            if (kpCount < MIN_ENROLLMENT_KEYPOINTS || descriptors.empty()) {
                gray.release()
                descriptors.release()
                null
            } else {
                FaceFeatures(gray = gray, descriptors = descriptors, keypointCount = kpCount)
            }
        } finally {
            keypoints.release()
            mask.release()
        }
    }

    private fun toNormalizedGray(bitmap: Bitmap): Mat {
        val rgba = Mat()
        val gray = Mat()
        val resized = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        when (rgba.channels()) {
            4 -> Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            3 -> Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGB2GRAY)
            1 -> rgba.copyTo(gray)
            else -> Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        }
        Imgproc.resize(gray, resized, Size(FACE_IMAGE_SIZE.toDouble(), FACE_IMAGE_SIZE.toDouble()))
        Imgproc.equalizeHist(resized, resized)
        rgba.release()
        gray.release()
        return resized
    }

    private fun computePixelSimilarity(candidateGray: Mat, enrolledGray: Mat): Float {
        val diff = Mat()
        return try {
            Core.absdiff(candidateGray, enrolledGray, diff)
            val mean = Core.mean(diff).`val`[0].toFloat()
            (1f - (mean / 255f)).coerceIn(0f, 1f)
        } finally {
            diff.release()
        }
    }

    private fun refreshEnrolledFaceIfChanged(force: Boolean = false) {
        if (!openCvReady) return
        migrateLegacyTemplateIfNeeded()
        val files = listProfileTemplateFiles()
        val fingerprint = files.joinToString("|") { file ->
            "${file.name}:${file.lastModified()}:${file.length()}"
        }
        if (!force && fingerprint == enrolledProfileFingerprint) return

        if (files.isEmpty()) {
            clearProfileInMemory()
            enrolledProfileFingerprint = null
            return
        }

        val loadedTemplates = mutableListOf<EnrolledTemplate>()
        for (file in files) {
            try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    ?: throw IllegalStateException("Failed to decode ${file.name}")
                val features = buildFeatures(bitmap)
                    ?: throw IllegalStateException("No descriptors from ${file.name}")
                loadedTemplates.add(
                    EnrolledTemplate(
                        sourceName = file.name,
                        gray = features.gray,
                        descriptors = features.descriptors
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skipping invalid ME template ${file.name}: $e")
            }
        }

        clearProfileInMemory()
        enrolledTemplates.addAll(loadedTemplates)
        enrolledProfileFingerprint = fingerprint
        Log.i(TAG, "Loaded ${enrolledTemplates.size} ME template(s)")
    }

    private fun persistTemplate(gray: Mat, destinationFile: File) {
        val rgba = Mat()
        try {
            Imgproc.cvtColor(gray, rgba, Imgproc.COLOR_GRAY2RGBA)
            val bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, bitmap)
            destinationFile.parentFile?.mkdirs()
            FileOutputStream(destinationFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } finally {
            rgba.release()
        }
    }

    private fun clearProfileInMemory() {
        enrolledTemplates.forEach { template ->
            template.gray.release()
            template.descriptors.release()
        }
        enrolledTemplates.clear()
    }

    private fun clearProfileOnDisk() {
        listProfileTemplateFiles().forEach { file ->
            runCatching { file.delete() }
                .onFailure { error -> Log.w(TAG, "Failed to delete ${file.name}: $error") }
        }
        File(context.filesDir, ENROLLED_ME_FILE).delete()
    }

    private fun createNewTemplateFile(): File {
        val profileDir = File(context.filesDir, ENROLLED_ME_PROFILE_DIR).apply { mkdirs() }
        return File(profileDir, "$ENROLLED_ME_PREFIX${System.currentTimeMillis()}.png")
    }

    private fun listProfileTemplateFiles(): List<File> {
        val profileDir = File(context.filesDir, ENROLLED_ME_PROFILE_DIR)
        return profileDir
            .listFiles { file -> file.isFile && file.name.endsWith(".png", ignoreCase = true) }
            ?.sortedBy { file -> file.lastModified() }
            ?: emptyList()
    }

    private fun migrateLegacyTemplateIfNeeded() {
        val legacyFile = File(context.filesDir, ENROLLED_ME_FILE)
        if (!legacyFile.exists()) return
        if (listProfileTemplateFiles().isNotEmpty()) return
        runCatching {
            val bitmap = BitmapFactory.decodeFile(legacyFile.absolutePath)
                ?: throw IllegalStateException("Failed to decode legacy template")
            val gray = toNormalizedGray(bitmap)
            try {
                persistTemplate(gray = gray, destinationFile = createNewTemplateFile())
            } finally {
                gray.release()
            }
        }.onFailure { error ->
            Log.w(TAG, "Legacy ME template migration failed: $error")
        }
    }

    private fun findLargestFace(bitmap: Bitmap): RectF? {
        val detections = detectFaces(bitmap)
        return detections
            .map { it.bbox }
            .maxByOrNull { it.width() * it.height() }
    }

    private fun clampRect(bbox: RectF, width: Int, height: Int): Rect? {
        val left = bbox.left.toInt().coerceIn(0, width - 1)
        val top = bbox.top.toInt().coerceIn(0, height - 1)
        val right = bbox.right.toInt().coerceIn(left + 1, width)
        val bottom = bbox.bottom.toInt().coerceIn(top + 1, height)
        return if (right > left && bottom > top) Rect(left, top, right, bottom) else null
    }

    private fun classifyPersonType(bitmap: Bitmap, bbox: RectF): PersonClassifier.PersonType {
        return try {
            val rect = clampRect(bbox, bitmap.width, bitmap.height)
                ?: return PersonClassifier.PersonType.UNKNOWN
            val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
            PersonClassifier.classify(crop)
        } catch (e: Exception) {
            Log.w(TAG, "Person crop/classify failed: $e")
            PersonClassifier.PersonType.UNKNOWN
        }
    }
}
