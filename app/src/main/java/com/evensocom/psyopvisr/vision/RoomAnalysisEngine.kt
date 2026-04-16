package com.evensocom.psyopvisr.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlin.math.sqrt

/**
 * Performs a single-frame room/spatial analysis using the existing EfficientDet model.
 *
 * Analysis steps:
 *  1. Run ObjectDetector on the frame.
 *  2. Build a 3×3 spatial grid, tallying detections per cell.
 *  3. Estimate object distances from bounding box size.
 *  4. Attempt ARCore depth via Frame.acquireDepthImage16Bits() — graceful fallback if unavailable.
 *  5. Derive exit direction heuristics from clear regions.
 *  6. Return a formatted ROOM ANALYSIS summary.
 */
class RoomAnalysisEngine(private val context: Context) {

    companion object {
        private const val TAG = "RoomAnalysisEngine"
        private const val MODEL_ASSET = "efficientdet.tflite"
        private const val CONFIDENCE_THRESHOLD = 0.4f
        private const val MAX_RESULTS = 20
    }

    private var objectDetector: ObjectDetector? = null

    init {
        initDetector()
    }

    private fun initDetector() {
        try {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
                )
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(CONFIDENCE_THRESHOLD)
                .build()
            objectDetector = ObjectDetector.createFromOptions(context, options)
            Log.i(TAG, "RoomAnalysisEngine: ObjectDetector initialized")
        } catch (e: Exception) {
            Log.w(TAG, "RoomAnalysisEngine: model load failed ($MODEL_ASSET missing): $e")
            objectDetector = null
        }
    }

    // ──────────────────── Analysis ────────────────────

    /**
     * Analyze a single camera frame and return a [ScanResult.RoomResult].
     * The ImageProxy is NOT closed here.
     */
    fun analyze(imageProxy: ImageProxy): ScanResult {
        val detector = objectDetector
            ?: return ScanResult.RoomResult(
                "ROOM ANALYSIS:\nMODEL NOT FOUND\nPlace efficientdet.tflite in assets/"
            )

        val bitmap: Bitmap = try {
            imageProxy.toBitmap()
        } catch (e: Exception) {
            Log.w(TAG, "Frame conversion failed: $e")
            return ScanResult.RoomResult("ROOM ANALYSIS:\nFRAME ERROR")
        }

        val frameW = bitmap.width.toFloat()
        val frameH = bitmap.height.toFloat()
        val frameArea = frameW * frameH

        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val detections = detector.detect(mpImage).detections() ?: emptyList()

            // 3×3 grid: grid[row][col] = list of label strings detected in that cell
            val grid = Array(3) { Array(3) { mutableListOf<String>() } }

            var personCount = 0
            val personPositions = mutableListOf<String>()
            val distanceEstimates = mutableListOf<Float>() // in meters (rough heuristic)

            for (detection in detections) {
                val label = detection.categories()?.firstOrNull()?.categoryName() ?: continue
                val bbox = detection.boundingBox()
                val centerX = (bbox.left + bbox.right) / 2f
                val centerY = (bbox.top + bbox.bottom) / 2f

                val col = ((centerX / frameW) * 3).toInt().coerceIn(0, 2)
                val row = ((centerY / frameH) * 3).toInt().coerceIn(0, 2)
                grid[row][col].add(label)

                // Rough distance: assume a person bbox at full height (1.7m subject) fills ~0.3 frame
                // d ≈ referenceSize / observedFraction
                val observedFraction = sqrt((bbox.width() * bbox.height()) / frameArea)
                val distMeters = if (observedFraction > 0.01f) (0.15f / observedFraction) * 3f else 99f
                distanceEstimates.add(distMeters)

                if (label.equals("person", ignoreCase = true)) {
                    personCount++
                    personPositions.add(xSector(col))
                }
            }

            // Estimate room depth from average distance
            val avgDist = if (distanceEstimates.isNotEmpty()) distanceEstimates.average().toFloat() else 0f
            val spaceEstimate = when {
                avgDist < 2f -> "~1-2m space est."
                avgDist < 5f -> "~3-5m space est."
                avgDist < 10f -> "~5-10m space est."
                else -> ">10m space est."
            }

            // ARCore depth — best-effort, wrap in try/catch
            val arCoreInfo = tryAcquireArCoreDepth()

            // Exit heuristic: bottom-center (col=1, row=2) or top-center (col=1, row=0) clear
            val exitDirection = determineExitDirection(grid)

            // Build summary
            val summary = buildString {
                appendLine("ROOM ANALYSIS:")
                if (arCoreInfo != null) appendLine(arCoreInfo) else appendLine(spaceEstimate)
                if (personCount > 0) {
                    appendLine("Persons: $personCount (${personPositions.joinToString(", ")})")
                } else {
                    appendLine("Persons: 0 detected")
                }
                appendLine("Objects: ${detections.size} detected")
                append("Exits: $exitDirection")
            }

            ScanResult.RoomResult(summary.trimEnd())

        } catch (e: Exception) {
            Log.e(TAG, "Room analysis failed: $e")
            ScanResult.RoomResult("ROOM ANALYSIS:\nERROR: ${e.message?.take(50) ?: "Unknown"}")
        }
    }

    // ──────────────────── ARCore ────────────────────

    /**
     * Attempt to get depth info from ARCore. Returns a human-readable distance string
     * or null if ARCore is not active/available.
     */
    private fun tryAcquireArCoreDepth(): String? {
        return try {
            // ARCore Frame is not directly accessible from a camera-only service context.
            // When ARCore is integrated, inject the Frame reference and call:
            //   val depthImage = arFrame.acquireDepthImage16Bits()
            //   val centerDepth = readCenterDepthMm(depthImage) / 1000f
            //   depthImage.close()
            //   "Depth: %.1fm (ARCore)".format(centerDepth)
            null // graceful no-op until ARCore session is wired in
        } catch (e: Exception) {
            Log.d(TAG, "ARCore depth not available: $e")
            null
        }
    }

    // ──────────────────── Helpers ────────────────────

    private fun xSector(col: Int) = when (col) {
        0 -> "LEFT"
        1 -> "CENTER"
        else -> "RIGHT"
    }

    /**
     * Check bottom-center and top-center grid cells for exit directions.
     * A cell is "clear" if it contains no large objects.
     */
    private fun determineExitDirection(grid: Array<Array<MutableList<String>>>): String {
        val exits = mutableListOf<String>()
        // Bottom-center (row=2, col=1) → FRONT direction
        if (grid[2][1].isEmpty()) exits.add("FRONT possible")
        // Top-center (row=0, col=1) → REAR direction
        if (grid[0][1].isEmpty()) exits.add("REAR possible")
        // Left side clear (all of col=0)
        if ((0..2).all { row -> grid[row][0].isEmpty() }) exits.add("LEFT clear")
        // Right side clear (all of col=2)
        if ((0..2).all { row -> grid[row][2].isEmpty() }) exits.add("RIGHT clear")

        return if (exits.isEmpty()) "No clear exits detected" else exits.joinToString(", ")
    }
}
