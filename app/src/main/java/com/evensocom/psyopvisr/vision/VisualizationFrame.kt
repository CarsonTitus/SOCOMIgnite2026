package com.evensocom.psyopvisr.vision

import android.graphics.RectF

// ──────────────────── Per-mode detection data ────────────────────

data class FaceDetection(
    val bbox: RectF,
    val identity: String,
    val confidence: Float,
    val personType: PersonClassifier.PersonType
)

data class SymbolDetection(
    val bbox: RectF,
    val label: String,
    val confidence: Float,
    val alertLevel: Int,
    val contextText: String
)

data class RoomDetection(
    val bbox: RectF,
    val label: String,
    val distanceMeters: Float
)

data class SpatialObject(
    val label: String,
    val relX: Float,       // relative X in bird's-eye space, range -5..5 meters
    val relZ: Float,       // relative Z (forward/back) in bird's-eye space, range 0..10 meters
    val distanceMeters: Float,
    val confidence: Float
)

data class RoomEstimate(
    val sizeCategory: String,    // "small enclosed", "medium room", "large open space", etc.
    val confidence: Float,
    val scanProgress: Float,     // 0.0..1.0
    val exitCount: Int,
    val exitSummary: String,
    val personCount: Int
)

/**
 * Carries the raw detection geometry emitted by each scan engine.
 *
 * [imageWidth] / [imageHeight] are the dimensions of the source bitmap
 * (i.e. the ImageProxy frame, before any rotation is applied).
 * [rotationDegrees] is [ImageProxy.imageInfo.rotationDegrees] — the number
 * of degrees the image must be rotated CW to appear upright on the display.
 * [OverlayView] uses these to transform bbox coordinates into screen space.
 */
sealed class VisualizationFrame(
    val imageWidth: Int,
    val imageHeight: Int,
    val rotationDegrees: Int
) {
    class FaceFrame(
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int,
        val detections: List<FaceDetection>
    ) : VisualizationFrame(imageWidth, imageHeight, rotationDegrees)

    class SymbolFrame(
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int,
        val detections: List<SymbolDetection>
    ) : VisualizationFrame(imageWidth, imageHeight, rotationDegrees)

    class CulturalFrame(
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int,
        val detections: List<SymbolDetection>,
        val contextSummary: String
    ) : VisualizationFrame(imageWidth, imageHeight, rotationDegrees)

    class RoomFrame(
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int,
        val detections: List<RoomDetection>,
        val personCount: Int,
        val exitSummary: String
    ) : VisualizationFrame(imageWidth, imageHeight, rotationDegrees)

    class RoomSpatialFrame(
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int,
        val spatialObjects: List<SpatialObject>,
        val roomEstimate: RoomEstimate,
        val isScanning: Boolean
    ) : VisualizationFrame(imageWidth, imageHeight, rotationDegrees)
}
