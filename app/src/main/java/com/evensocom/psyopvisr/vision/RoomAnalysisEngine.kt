package com.evensocom.psyopvisr.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class RoomAnalysisEngine(private val context: Context) {

    private val arCoreAnalyzer = ArCoreRoomAnalyzer(context)
    private val spatialAccumulator = SpatialAccumulator()
    private var objectDetector: ObjectDetector? = null
    private val prefs = context.getSharedPreferences("psyop_room_prefs", Context.MODE_PRIVATE)
    private var consecutiveArFrameFailures: Int = 0
    private var lastFrameWidth: Int = 640
    private var lastFrameHeight: Int = 480
    private var lastFrameRotationDegrees: Int = 0
    private var originPoseX: Float? = null
    private var originPoseZ: Float? = null
    private var lastPoseX: Float? = null
    private var lastPoseZ: Float? = null
    private var totalTravelMeters: Float = 0f
    private var maxRadiusMeters: Float = 0f
    private var cameraFallbackActive: Boolean = false
    private var cameraFallbackReason: String = ""
    private var fallbackFrameCounter: Int = 0
    private var fallbackHeadingDeg: Float = 0f
    private var fallbackRadiusMeters: Float = 0.4f
    private var fallbackBasePoseX: Float = 0f
    private var fallbackBasePoseZ: Float = 0f
    private val reusableRawDetections = ArrayList<RawDetection>(MAX_RESULTS)

    companion object {
        private const val TAG = "RoomAnalysisEngine"
        private const val MODEL_ASSET = "efficientdet.tflite"
        private const val CONFIDENCE_THRESHOLD = 0.35f
        private const val MAX_RESULTS = 15
        private const val PREFS_SCAN_END_MODE = "room_scan_end_mode"
        private const val DEFAULT_FRAME_WIDTH = 640
        private const val DEFAULT_FRAME_HEIGHT = 480
        private const val MOTION_PROGRESS_SCALE = 0.6f
        private const val FALLBACK_HEADING_STEP_DEG = 9f
        private const val FALLBACK_RADIUS_GROWTH_M = 0.025f
        private const val FALLBACK_MAX_RADIUS_M = 2.6f
    }

    init {
        try {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(CONFIDENCE_THRESHOLD)
                .build()
            objectDetector = ObjectDetector.createFromOptions(context, options)
            Log.i(TAG, "ObjectDetector initialized")
        } catch (e: Exception) {
            Log.w(TAG, "ObjectDetector init failed (model missing?): $e")
            objectDetector = null
        }
    }

    @Synchronized
    fun startSession() {
        spatialAccumulator.reset()
        consecutiveArFrameFailures = 0
        lastFrameWidth = DEFAULT_FRAME_WIDTH
        lastFrameHeight = DEFAULT_FRAME_HEIGHT
        lastFrameRotationDegrees = 0
        resetMotionTracking()
        resetFallbackPose()
        cameraFallbackActive = false
        cameraFallbackReason = ""
        arCoreAnalyzer.start()
        if (arCoreAnalyzer.isRunning) {
            Log.i(TAG, "Room analysis session started")
        } else {
            Log.w(TAG, "Room analysis session failed to start (ARCore unavailable)")
        }
    }

    @Synchronized
    fun stopSession() {
        arCoreAnalyzer.stop()
        consecutiveArFrameFailures = 0
        cameraFallbackActive = false
        cameraFallbackReason = ""
        resetFallbackPose()
        Log.i(TAG, "Room analysis session stopped")
    }

    @Synchronized
    fun activateCameraFallback(reason: String) {
        if (cameraFallbackActive && cameraFallbackReason == reason) return
        cameraFallbackActive = true
        cameraFallbackReason = reason
        consecutiveArFrameFailures = 0
        fallbackBasePoseX = lastPoseX ?: 0f
        fallbackBasePoseZ = lastPoseZ ?: 0f
        fallbackFrameCounter = 0
        fallbackHeadingDeg = 0f
        fallbackRadiusMeters = 0.4f
        if (arCoreAnalyzer.isRunning) {
            arCoreAnalyzer.stop()
        }
        Log.w(TAG, "CameraX fallback enabled for ROOM_ANALYSIS: $reason")
    }

    @Synchronized
    fun isCameraFallbackActive(): Boolean = cameraFallbackActive

    @Synchronized
    fun analyzeFrame(): ScanResult {
        if (cameraFallbackActive) {
            val spatialObjects = spatialAccumulator.getAccumulatedObjects()
            val roomEstimate = buildAdaptiveRoomEstimate(
                baseEstimate = spatialAccumulator.getRoomEstimate(),
                spatialObjects = spatialObjects
            )
            publishRoomFrame(spatialObjects, roomEstimate, isScanning = true)
            return ScanResult.RoomResult("ROOM SCAN\nCamera fallback active\nAwaiting CameraX frames")
        }

        if (!arCoreAnalyzer.isRunning) {
            return ScanResult.RoomResult("ROOM SCAN\nSession not started")
        }

        val arFrame = arCoreAnalyzer.acquireFrame()
            ?: run {
                consecutiveArFrameFailures++
                val spatialObjects = spatialAccumulator.getAccumulatedObjects()
                val roomEstimate = buildAdaptiveRoomEstimate(
                    baseEstimate = spatialAccumulator.getRoomEstimate(),
                    spatialObjects = spatialObjects
                )
                publishRoomFrame(spatialObjects, roomEstimate, isScanning = true)
                val status = if (consecutiveArFrameFailures > 12) {
                    "Tracking unstable\nMove slowly and scan wider"
                } else {
                    "Initializing ARCore..."
                }
                return ScanResult.RoomResult("ROOM SCAN\n$status")
            }
        consecutiveArFrameFailures = 0
        return analyzeBitmapFrame(
            bitmap = arFrame.bitmap,
            rotationDegrees = 0,
            poseX = arFrame.poseX,
            poseZ = arFrame.poseZ,
            headingDeg = arFrame.headingDeg,
            depthMeters = arFrame.depthMeters,
            fallbackMode = false
        )
    }

    @Synchronized
    fun analyzeCameraFallbackFrame(imageProxy: ImageProxy): ScanResult {
        if (!cameraFallbackActive) {
            return ScanResult.RoomResult("ROOM SCAN\nARCore active")
        }

        val bitmap: Bitmap = try {
            imageProxy.toBitmap()
        } catch (e: Exception) {
            Log.w(TAG, "Camera fallback frame conversion failed: $e")
            return ScanResult.RoomResult("ROOM SCAN\nCamera frame unavailable")
        }

        val fallbackPose = nextFallbackPose()
        return analyzeBitmapFrame(
            bitmap = bitmap,
            rotationDegrees = imageProxy.imageInfo.rotationDegrees,
            poseX = fallbackPose.poseX,
            poseZ = fallbackPose.poseZ,
            headingDeg = fallbackPose.headingDeg,
            depthMeters = null,
            fallbackMode = true
        )
    }

    @Synchronized
    fun generateFinalSummary(): ScanResult {
        val spatialObjects = spatialAccumulator.getAccumulatedObjects()
        val roomEstimate = buildAdaptiveRoomEstimate(
            baseEstimate = spatialAccumulator.getRoomEstimate(),
            spatialObjects = spatialObjects
        )
        val frameCount = spatialAccumulator.getFrameCount()
        publishRoomFrame(spatialObjects, roomEstimate, isScanning = false)

        if (frameCount < 5) {
            val fallbackHint = if (cameraFallbackActive) "\nCamera fallback received too few frames" else ""
            return ScanResult.RoomResult(
                "ROOM ANALYSIS FAILED\nInsufficient room tracking data$fallbackHint\nTry again with slower movement"
            )
        }

        val summary = buildString {
            appendLine("ROOM ANALYSIS COMPLETE")
            if (cameraFallbackActive) {
                appendLine("CAMERA FALLBACK (NO DEPTH)")
            }
            appendLine(roomEstimate.sizeCategory.uppercase())
            if (roomEstimate.personCount > 0) appendLine("${roomEstimate.personCount} person(s) detected")
            appendLine(roomEstimate.exitSummary)
            if (spatialObjects.isEmpty()) {
                append("Object map sparse; size inferred from movement")
            } else {
                append("${spatialObjects.size} objects mapped")
            }
        }

        return ScanResult.RoomResult(summary.trimEnd())
    }

    fun getScanProgress(): Float = spatialAccumulator.getRoomEstimate().scanProgress

    fun getScanEndMode(): String = prefs.getString(PREFS_SCAN_END_MODE, "auto") ?: "auto"

    fun setScanEndMode(mode: String) {
        require(mode == "tap" || mode == "auto") { "mode must be 'tap' or 'auto'" }
        prefs.edit().putString(PREFS_SCAN_END_MODE, mode).apply()
    }

    fun getAutoScanDurationMs(): Long = 30_000L

    fun isSessionRunning(): Boolean = arCoreAnalyzer.isRunning

    @Synchronized
    fun getConsecutiveArFrameFailures(): Int = consecutiveArFrameFailures

    private data class FallbackPose(
        val poseX: Float,
        val poseZ: Float,
        val headingDeg: Float
    )

    private fun nextFallbackPose(): FallbackPose {
        fallbackFrameCounter++
        fallbackHeadingDeg = (fallbackHeadingDeg + FALLBACK_HEADING_STEP_DEG) % 360f
        fallbackRadiusMeters = min(
            FALLBACK_MAX_RADIUS_M,
            fallbackRadiusMeters + FALLBACK_RADIUS_GROWTH_M
        )
        val headingRad = Math.toRadians(fallbackHeadingDeg.toDouble())
        val poseX = fallbackBasePoseX + (sin(headingRad) * fallbackRadiusMeters).toFloat()
        val poseZ = fallbackBasePoseZ + (cos(headingRad) * fallbackRadiusMeters).toFloat()
        return FallbackPose(
            poseX = poseX,
            poseZ = poseZ,
            headingDeg = fallbackHeadingDeg
        )
    }

    private fun analyzeBitmapFrame(
        bitmap: Bitmap,
        rotationDegrees: Int,
        poseX: Float,
        poseZ: Float,
        headingDeg: Float,
        depthMeters: Float?,
        fallbackMode: Boolean
    ): ScanResult {
        lastFrameWidth = bitmap.width
        lastFrameHeight = bitmap.height
        lastFrameRotationDegrees = rotationDegrees
        updateMotionTracking(poseX, poseZ)

        val detections = runObjectDetection(bitmap)
        spatialAccumulator.addFrame(
            detections = detections,
            devicePoseX = poseX,
            devicePoseZ = poseZ,
            headingDeg = headingDeg,
            depthMeters = depthMeters
        )

        val spatialObjects = spatialAccumulator.getAccumulatedObjects()
        val roomEstimate = buildAdaptiveRoomEstimate(
            baseEstimate = spatialAccumulator.getRoomEstimate(),
            spatialObjects = spatialObjects
        )
        publishRoomFrame(spatialObjects, roomEstimate, isScanning = true)

        val progressPct = (roomEstimate.scanProgress * 100).toInt()
        val summary = buildString {
            if (fallbackMode) appendLine("CAMERA FALLBACK (NO DEPTH)")
            appendLine(roomEstimate.sizeCategory.uppercase())
            if (roomEstimate.personCount > 0) {
                appendLine("Persons: ${roomEstimate.personCount}")
            } else if (spatialObjects.isEmpty()) {
                appendLine("Objects: none mapped yet")
            } else {
                appendLine("Objects: ${spatialObjects.size} mapped")
            }
            appendLine(roomEstimate.exitSummary)
            append("SCAN: $progressPct% coverage")
        }
        return ScanResult.RoomResult(summary.trimEnd())
    }

    private fun runObjectDetection(bitmap: Bitmap): List<RawDetection> {
        reusableRawDetections.clear()
        val detector = objectDetector ?: return reusableRawDetections
        val frameW = bitmap.width.toFloat()
        val frameH = bitmap.height.toFloat()
        val frameArea = frameW * frameH

        if (frameArea <= 0f) return reusableRawDetections

        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val detections = detector.detect(mpImage).detections() ?: emptyList()
            for (det in detections) {
                val category = det.categories()?.firstOrNull() ?: continue
                val label = category.categoryName() ?: continue
                val conf = category.score()
                val bbox = det.boundingBox()
                val centerXNorm = (((bbox.left + bbox.right) / 2f) / frameW).coerceIn(0f, 1f)
                val centerYNorm = (((bbox.top + bbox.bottom) / 2f) / frameH).coerceIn(0f, 1f)
                val bboxFrac = sqrt((bbox.width() * bbox.height()) / frameArea)
                val estimatedDistM = if (bboxFrac > 0.01f) (0.18f / bboxFrac) * 2.5f else 8f
                reusableRawDetections.add(
                    RawDetection(
                        label = label,
                        centerXNorm = centerXNorm,
                        centerYNorm = centerYNorm,
                        estimatedDistanceM = estimatedDistM,
                        confidence = conf
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "EfficientDet inference failed: $e")
        }

        return reusableRawDetections
    }

    private fun publishRoomFrame(
        spatialObjects: List<SpatialObject>,
        roomEstimate: RoomEstimate,
        isScanning: Boolean
    ) {
        VisualizationBus.postFrame(
            VisualizationFrame.RoomSpatialFrame(
                imageWidth = lastFrameWidth,
                imageHeight = lastFrameHeight,
                rotationDegrees = lastFrameRotationDegrees,
                spatialObjects = spatialObjects,
                roomEstimate = roomEstimate,
                isScanning = isScanning
            )
        )
    }

    private fun resetMotionTracking() {
        originPoseX = null
        originPoseZ = null
        lastPoseX = null
        lastPoseZ = null
        totalTravelMeters = 0f
        maxRadiusMeters = 0f
    }

    private fun resetFallbackPose() {
        fallbackFrameCounter = 0
        fallbackHeadingDeg = 0f
        fallbackRadiusMeters = 0.4f
        fallbackBasePoseX = 0f
        fallbackBasePoseZ = 0f
    }

    private fun updateMotionTracking(poseX: Float, poseZ: Float) {
        if (originPoseX == null) {
            originPoseX = poseX
            originPoseZ = poseZ
        }

        val previousX = lastPoseX
        val previousZ = lastPoseZ
        if (previousX != null && previousZ != null) {
            totalTravelMeters += hypot(
                (poseX - previousX).toDouble(),
                (poseZ - previousZ).toDouble()
            ).toFloat()
        }

        val originX = originPoseX ?: poseX
        val originZ = originPoseZ ?: poseZ
        val radius = hypot(
            (poseX - originX).toDouble(),
            (poseZ - originZ).toDouble()
        ).toFloat()
        maxRadiusMeters = max(maxRadiusMeters, radius)

        lastPoseX = poseX
        lastPoseZ = poseZ
    }

    private fun buildAdaptiveRoomEstimate(
        baseEstimate: RoomEstimate,
        spatialObjects: List<SpatialObject>
    ): RoomEstimate {
        if (spatialObjects.isNotEmpty()) return baseEstimate

        val motionBasedSize = inferMotionSizeCategory()
        val confidenceCap = when {
            maxRadiusMeters >= 4f -> 0.75f
            maxRadiusMeters >= 2f -> 0.6f
            maxRadiusMeters >= 1f -> 0.45f
            else -> 0.3f
        }
        return baseEstimate.copy(
            sizeCategory = motionBasedSize,
            confidence = min(baseEstimate.confidence, confidenceCap)
        )
    }

    private fun inferMotionSizeCategory(): String {
        val observedSpanMeters = max(maxRadiusMeters * 2f, totalTravelMeters * MOTION_PROGRESS_SCALE)
        return when {
            observedSpanMeters < 2f -> "small enclosed room"
            observedSpanMeters < 5f -> "medium room"
            observedSpanMeters < 10f -> "large open area"
            else -> "wide open space"
        }
    }
}
