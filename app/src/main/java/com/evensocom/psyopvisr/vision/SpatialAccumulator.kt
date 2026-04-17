package com.evensocom.psyopvisr.vision

import android.util.Log
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Accumulates spatial detections across multiple ARCore frames to build a bird's-eye
 * map of a room as the user walks around.
 *
 * Threading: all public methods should be called from a single analysis thread.
 */
class SpatialAccumulator {

    companion object {
        private const val TAG = "SpatialAccumulator"

        /** Horizontal field-of-view for the ultrawide camera (degrees). */
        private const val H_FOV_DEG = 120f

        /** Vertical field-of-view approximation (degrees). */
        private const val V_FOV_DEG = 90f

        /** Two objects with the same label closer than this are merged (meters). */
        private const val MERGE_RADIUS_M = 0.8f

        /** Cap on how many unique objects we keep in the accumulator. */
        private const val MAX_OBJECTS = 30

        /** Frame count at which scanProgress reaches 1.0. */
        private const val FULL_SCAN_FRAMES = 60

        /** Objects beyond this distance from the origin are ignored for exit detection. */
        private const val EXIT_CLEAR_RADIUS_M = 3f
    }

    // ──────────────────── Internal accumulation state ────────────────────

    /**
     * Internal mutable wrapper that mirrors [SpatialObject] but also tracks a merge weight.
     * Converted to [SpatialObject] on read.
     */
    private data class AccEntry(
        var label: String,
        var relX: Float,
        var relZ: Float,
        var distanceMeters: Float,
        var confidence: Float,
        var weight: Int = 1
    )

    private val entries = mutableListOf<AccEntry>()
    private var frameCount = 0

    /** Device pose at the very first frame — used as the session origin. */
    private var originX: Float? = null
    private var originZ: Float? = null

    // ──────────────────── Public API ────────────────────

    /** Clears all accumulated data. Call when entering ROOM_ANALYSIS mode. */
    fun reset() {
        entries.clear()
        frameCount = 0
        originX = null
        originZ = null
        Log.d(TAG, "reset()")
    }

    /**
     * Projects [detections] into world space using the device pose and accumulates them
     * into the internal object map, merging nearby objects with the same label.
     *
     * @param detections   Raw EfficientDet detections for this frame.
     * @param devicePoseX  ARCore camera world X (meters).
     * @param devicePoseZ  ARCore camera world Z (meters).
     * @param headingDeg   Device heading in degrees (0 = north / +Z, 90 = east / +X).
     * @param depthMeters  Center pixel depth from ARCore, or null if unavailable.
     */
    fun addFrame(
        detections: List<RawDetection>,
        devicePoseX: Float,
        devicePoseZ: Float,
        headingDeg: Float,
        depthMeters: Float?
    ) {
        frameCount++

        // Latch session origin on the first frame
        if (originX == null) {
            originX = devicePoseX
            originZ = devicePoseZ
            Log.d(TAG, "Session origin latched at ($devicePoseX, $devicePoseZ)")
        }

        val headingRad = Math.toRadians(headingDeg.toDouble()).toFloat()

        for (det in detections) {
            // Convert normalised bbox centre to a horizontal angle offset from the camera heading.
            val hAngleRad = ((det.centerXNorm - 0.5f) *
                    Math.toRadians(H_FOV_DEG.toDouble())).toFloat()

            // Estimated distance: if ARCore depth is available and the object is near the frame
            // centre, prefer the depth measurement; otherwise use the per-detection heuristic.
            val distM: Float = if (
                depthMeters != null &&
                abs(det.centerXNorm - 0.5f) < 0.2f &&
                abs(det.centerYNorm - 0.5f) < 0.2f
            ) {
                depthMeters
            } else {
                det.estimatedDistanceM
            }

            // Project into world XZ plane.
            // Bearing = headingRad + horizontal angle offset (right = +bearing).
            val objectBearing = headingRad + hAngleRad
            val worldX = devicePoseX + distM * sin(objectBearing)
            val worldZ = devicePoseZ + distM * cos(objectBearing)

            // Express relative to session origin
            val relX = worldX - (originX ?: 0f)
            val relZ = worldZ - (originZ ?: 0f)

            mergeOrAdd(
                AccEntry(
                    label = det.label,
                    relX = relX,
                    relZ = relZ,
                    distanceMeters = distM,
                    confidence = det.confidence
                )
            )
        }
    }

    /**
     * Returns all accumulated unique objects (merged nearby ones), capped at [MAX_OBJECTS].
     * Converts internal [AccEntry] records to the canonical [SpatialObject] type.
     */
    fun getAccumulatedObjects(): List<SpatialObject> =
        entries.map { e ->
            SpatialObject(
                label = e.label,
                relX = e.relX,
                relZ = e.relZ,
                distanceMeters = e.distanceMeters,
                confidence = e.confidence
            )
        }

    /**
     * Derives a [RoomEstimate] from the accumulated data:
     * - sizeCategory from maximum observed object distance
     * - confidence 0..1 from frame count (low < 10 frames, medium < 30, high >= 30)
     * - scanProgress 0..1 (reaches 1.0 at [FULL_SCAN_FRAMES] frames)
     * - exitCount from empty quadrants within [EXIT_CLEAR_RADIUS_M]
     * - exitSummary description
     * - personCount from accumulated person detections
     */
    fun getRoomEstimate(): RoomEstimate {
        val maxDist = entries
            .maxOfOrNull { sqrt(it.relX * it.relX + it.relZ * it.relZ) } ?: 0f

        val sizeCategory = when {
            maxDist < 2f -> "small enclosed room"
            maxDist < 5f -> "medium room"
            maxDist < 10f -> "large open area"
            else -> "wide open space"
        }

        // Encode confidence as a 0..1 float: low=0.25, medium=0.6, high=1.0
        val confidence: Float = when {
            frameCount < 10 -> 0.25f
            frameCount < 30 -> 0.6f
            else -> 1.0f
        }

        val scanProgress = (frameCount.toFloat() / FULL_SCAN_FRAMES).coerceAtMost(1.0f)

        val personCount = entries.count { it.label.equals("person", ignoreCase = true) }

        // Exit detection: check 4 cardinal quadrants (front/rear/left/right from session origin).
        // A quadrant is "clear" if no accumulated object within EXIT_CLEAR_RADIUS_M falls in it.
        val quadrantNames = listOf("FRONT", "RIGHT", "REAR", "LEFT")
        val clearQuadrants = mutableListOf<String>()

        for (i in quadrantNames.indices) {
            val quadrantClear = entries.none { obj ->
                val dist = sqrt(obj.relX * obj.relX + obj.relZ * obj.relZ)
                if (dist >= EXIT_CLEAR_RADIUS_M) return@none false
                // Angle from origin: atan2(relX, relZ) gives bearing where 0=+Z (front)
                val angleDeg = Math.toDegrees(atan2(obj.relX.toDouble(), obj.relZ.toDouble()))
                val normalised = ((angleDeg % 360) + 360) % 360   // 0..360
                val quadrant = ((normalised + 45) / 90).toInt() % 4
                quadrant == i
            }
            if (quadrantClear) clearQuadrants.add(quadrantNames[i])
        }

        val exitSummary = if (clearQuadrants.isEmpty()) {
            "No clear exits detected"
        } else {
            clearQuadrants.joinToString(", ") { "$it possible" }
        }

        Log.d(
            TAG,
            "RoomEstimate: size=$sizeCategory conf=$confidence " +
                    "progress=${"%.2f".format(scanProgress)} exits=$exitSummary persons=$personCount"
        )

        return RoomEstimate(
            sizeCategory = sizeCategory,
            confidence = confidence,
            scanProgress = scanProgress,
            exitCount = clearQuadrants.size,
            exitSummary = exitSummary,
            personCount = personCount
        )
    }

    /** Returns the total number of frames processed since the last [reset]. */
    fun getFrameCount(): Int = frameCount

    // ──────────────────── Internal helpers ────────────────────

    /**
     * Merges [candidate] into an existing entry if one with the same label is within
     * [MERGE_RADIUS_M]; otherwise appends it (up to [MAX_OBJECTS]).
     */
    private fun mergeOrAdd(candidate: AccEntry) {
        val existing = entries.firstOrNull { e ->
            e.label.equals(candidate.label, ignoreCase = true) &&
                    distance2D(e.relX, e.relZ, candidate.relX, candidate.relZ) < MERGE_RADIUS_M
        }

        if (existing != null) {
            // Weighted running average of position and distance
            val w = existing.weight.toFloat()
            existing.relX = (existing.relX * w + candidate.relX) / (w + 1f)
            existing.relZ = (existing.relZ * w + candidate.relZ) / (w + 1f)
            existing.distanceMeters =
                (existing.distanceMeters * w + candidate.distanceMeters) / (w + 1f)
            existing.confidence = maxOf(existing.confidence, candidate.confidence)
            existing.weight++
        } else {
            if (entries.size < MAX_OBJECTS) {
                entries.add(candidate)
            }
        }
    }

    private fun distance2D(x1: Float, z1: Float, x2: Float, z2: Float): Float {
        val dx = x2 - x1
        val dz = z2 - z1
        return sqrt(dx * dx + dz * dz)
    }
}

// ──────────────────── Supporting data class ────────────────────

/**
 * A single raw detection from EfficientDet on an ARCore frame.
 *
 * @param label               COCO/EfficientDet category name.
 * @param centerXNorm         Bounding-box horizontal centre, normalised 0..1.
 * @param centerYNorm         Bounding-box vertical centre, normalised 0..1.
 * @param estimatedDistanceM  Distance estimate from bounding-box heuristic, meters.
 * @param confidence          Detection score 0..1.
 */
data class RawDetection(
    val label: String,
    val centerXNorm: Float,
    val centerYNorm: Float,
    val estimatedDistanceM: Float,
    val confidence: Float
)
