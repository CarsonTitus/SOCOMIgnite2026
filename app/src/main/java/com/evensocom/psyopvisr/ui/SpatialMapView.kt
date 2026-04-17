package com.evensocom.psyopvisr.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.evensocom.psyopvisr.vision.RoomEstimate
import com.evensocom.psyopvisr.vision.SpatialObject
import com.evensocom.psyopvisr.vision.VisualizationFrame
import kotlin.math.min

class SpatialMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val COLOR_BG = Color.parseColor("#E60A0A0F")
    private val COLOR_GRID = Color.parseColor("#0F1A0F")
    private val COLOR_RING = Color.parseColor("#1A2A1A")
    private val COLOR_RING_LABEL = Color.parseColor("#2A4A2A")
    private val COLOR_PERSON = Color.parseColor("#00E676")
    private val COLOR_VEHICLE = Color.parseColor("#FF9800")
    private val COLOR_HAZARD = Color.parseColor("#FF5252")
    private val COLOR_OBJECT = Color.parseColor("#29B6F6")
    private val COLOR_DEVICE = Color.WHITE
    private val COLOR_PROGRESS = Color.parseColor("#00E676")
    private val COLOR_PROGRESS_BG = Color.parseColor("#1A1A2A")
    private val COLOR_TEXT = Color.parseColor("#B0BEC5")
    private val COLOR_TEXT_BRIGHT = Color.WHITE
    private val COLOR_SCAN_DONE = Color.parseColor("#80000000")

    private val bgPaint = Paint().apply { color = COLOR_BG }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GRID
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_RING
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val ringLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_RING_LABEL
        textSize = 22f
        typeface = Typeface.MONOSPACE
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nodeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT_BRIGHT
        textSize = 24f
        typeface = Typeface.MONOSPACE
    }
    private val devicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_DEVICE
        style = Paint.Style.FILL
    }
    private val deviceGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressBgPaint = Paint().apply { color = COLOR_PROGRESS_BG }
    private val progressPaint = Paint().apply { color = COLOR_PROGRESS }
    private val infoTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textSize = 26f
        typeface = Typeface.MONOSPACE
    }
    private val scanDonePaint = Paint().apply { color = COLOR_SCAN_DONE }
    private val scanDoneTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_PROGRESS
        textSize = 40f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val warningTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_HAZARD
        textSize = 18f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }

    private var currentFrame: VisualizationFrame.RoomSpatialFrame? = null
    private var lastFrameUpdateMs: Long = 0L

    fun updateFrame(frame: VisualizationFrame.RoomSpatialFrame?) {
        currentFrame = frame
        if (frame != null) {
            lastFrameUpdateMs = System.currentTimeMillis()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBackground(canvas)
        val frame = currentFrame
        if (frame == null) {
            drawWaitingState(canvas)
            return
        }
        drawGrid(canvas)
        drawRangeRings(canvas)
        drawObjects(canvas, frame.spatialObjects)
        drawDeviceIndicator(canvas)
        drawProgressBar(canvas, frame.roomEstimate)
        drawScanInfo(canvas, frame.roomEstimate)
        drawStaleFeedHintIfNeeded(canvas, frame)
        if (!frame.isScanning) drawScanCompleteOverlay(canvas)
    }

    private fun worldToScreen(relX: Float, relZ: Float): PointF {
        val cx = width / 2f
        val deviceY = height * 0.80f
        val scale = (height * 0.70f) / 10f
        return PointF(
            cx + relX * scale,
            deviceY - relZ * scale
        )
    }

    private fun drawBackground(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
    }

    private fun drawGrid(canvas: Canvas) {
        val cx = width / 2f
        val deviceY = height * 0.80f
        canvas.drawLine(cx, 0f, cx, height.toFloat(), gridPaint)
        canvas.drawLine(0f, deviceY, width.toFloat(), deviceY, gridPaint)
    }

    private fun drawRangeRings(canvas: Canvas) {
        val cx = width / 2f
        val deviceY = height * 0.80f
        val scale = (height * 0.70f) / 10f

        for (radiusM in listOf(2f, 5f, 10f)) {
            val radiusPx = radiusM * scale
            canvas.drawCircle(cx, deviceY, radiusPx, ringPaint)
            canvas.drawText("${radiusM.toInt()}m", cx + radiusPx + 4f, deviceY - 4f, ringLabelPaint)
        }
    }

    private fun drawObjects(canvas: Canvas, objects: List<SpatialObject>) {
        for (obj in objects) {
            val point = worldToScreen(obj.relX, obj.relZ)
            val sx = point.x
            val sy = point.y
            val color = categoryColor(obj.label)
            val radius = 12f + min(1f, obj.confidence) * 8f
            val isHazard = isHazard(obj.label)
            val blink = System.currentTimeMillis() % 800 < 400

            nodeGlowPaint.color = Color.argb(40, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawCircle(sx, sy, radius * 2.5f, nodeGlowPaint)

            nodeGlowPaint.color = Color.argb(80, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawCircle(sx, sy, radius * 1.6f, nodeGlowPaint)

            nodePaint.color = if (isHazard && blink) Color.WHITE else color
            canvas.drawCircle(sx, sy, radius, nodePaint)

            val label = obj.label.take(8).uppercase()
            val textW = labelPaint.measureText(label)
            val chipLeft = sx - textW / 2f - 6f
            val chipRight = sx + textW / 2f + 6f
            val chipTop = sy - radius - 32f
            val chipBottom = sy - radius - 4f
            labelBgPaint.color = Color.argb(160, 0, 20, 40)
            canvas.drawRoundRect(chipLeft, chipTop, chipRight, chipBottom, 4f, 4f, labelBgPaint)
            canvas.drawText(label, sx - textW / 2f, chipBottom - 4f, labelPaint)
        }
    }

    private fun categoryColor(label: String): Int = when {
        label.contains("person", ignoreCase = true) -> COLOR_PERSON
        Regex("car|truck|bus|vehicle|motorcycle", RegexOption.IGNORE_CASE).containsMatchIn(label) -> COLOR_VEHICLE
        Regex("weapon|knife|gun|explosive|bomb|grenade", RegexOption.IGNORE_CASE).containsMatchIn(label) -> COLOR_HAZARD
        Regex("backpack|suitcase|bottle", RegexOption.IGNORE_CASE).containsMatchIn(label) -> COLOR_HAZARD
        else -> COLOR_OBJECT
    }

    private fun isHazard(label: String): Boolean =
        Regex("weapon|knife|gun|explosive|bomb|grenade|backpack|suitcase", RegexOption.IGNORE_CASE)
            .containsMatchIn(label)

    private fun drawDeviceIndicator(canvas: Canvas) {
        val cx = width / 2f
        val deviceY = height * 0.80f

        deviceGlowPaint.color = Color.argb(30, 255, 255, 255)
        canvas.drawCircle(cx, deviceY, 28f, deviceGlowPaint)
        deviceGlowPaint.color = Color.argb(60, 255, 255, 255)
        canvas.drawCircle(cx, deviceY, 18f, deviceGlowPaint)
        deviceGlowPaint.color = Color.argb(120, 255, 255, 255)
        canvas.drawCircle(cx, deviceY, 10f, deviceGlowPaint)

        canvas.drawCircle(cx, deviceY, 6f, devicePaint)

        val triPath = Path().apply {
            moveTo(cx, deviceY - 22f)
            lineTo(cx - 10f, deviceY - 6f)
            lineTo(cx + 10f, deviceY - 6f)
            close()
        }
        canvas.drawPath(triPath, devicePaint)

        infoTextPaint.textAlign = Paint.Align.CENTER
        infoTextPaint.textSize = 20f
        canvas.drawText("YOU", cx, deviceY + 22f, infoTextPaint)
        infoTextPaint.textAlign = Paint.Align.LEFT
        infoTextPaint.textSize = 26f
    }

    private fun drawProgressBar(canvas: Canvas, roomEstimate: RoomEstimate) {
        val barHeight = 16f
        val barY = height - barHeight
        val progress = roomEstimate.scanProgress.coerceIn(0f, 1f)
        val barWidth = width * progress

        canvas.drawRect(0f, barY, width.toFloat(), height.toFloat(), progressBgPaint)
        canvas.drawRect(0f, barY, barWidth, height.toFloat(), progressPaint)
    }

    private fun drawScanInfo(canvas: Canvas, roomEstimate: RoomEstimate) {
        val lines = listOf(
            roomEstimate.sizeCategory.uppercase(),
            "EXITS: ${if (roomEstimate.exitCount > 0) roomEstimate.exitSummary.take(20) else "none clear"}",
            "OBJ: ${currentFrame?.spatialObjects?.size ?: 0}  ${if (roomEstimate.personCount > 0) "PERSONS: ${roomEstimate.personCount}" else ""}",
            "COV: ${(roomEstimate.scanProgress.coerceIn(0f, 1f) * 100).toInt()}%  CONF: ${(roomEstimate.confidence.coerceIn(0f, 1f) * 100).toInt()}%"
        )
        val lineH = 30f
        val panelH = lines.size * lineH + 24f
        val panelW = 320f
        labelBgPaint.color = Color.argb(180, 0, 10, 20)
        canvas.drawRoundRect(12f, 12f, 12f + panelW, 12f + panelH, 8f, 8f, labelBgPaint)
        infoTextPaint.textSize = 22f
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, 24f, 12f + 24f + i * lineH, infoTextPaint)
        }
        infoTextPaint.textSize = 26f
    }

    private fun drawScanCompleteOverlay(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), 80f, scanDonePaint)
        canvas.drawText(
            "◆ SCAN COMPLETE ◆",
            width / 2f,
            52f,
            scanDoneTextPaint
        )
    }

    private fun drawWaitingState(canvas: Canvas) {
        val statusPaint = Paint(infoTextPaint).apply {
            color = COLOR_TEXT_BRIGHT
            textAlign = Paint.Align.CENTER
            textSize = 24f
        }
        canvas.drawText(
            "ROOM ANALYSIS",
            width / 2f,
            height * 0.45f,
            statusPaint
        )
        statusPaint.color = COLOR_TEXT
        statusPaint.textSize = 18f
        canvas.drawText(
            "Acquiring ARCore feed...",
            width / 2f,
            height * 0.45f + 34f,
            statusPaint
        )
        canvas.drawText(
            "If this persists: tap out and retry",
            width / 2f,
            height * 0.45f + 62f,
            statusPaint
        )
    }

    private fun drawStaleFeedHintIfNeeded(canvas: Canvas, frame: VisualizationFrame.RoomSpatialFrame) {
        if (!frame.isScanning) return
        val ageMs = System.currentTimeMillis() - lastFrameUpdateMs
        if (lastFrameUpdateMs <= 0L || ageMs < 1800L) return
        canvas.drawText(
            "AR feed stale - reacquiring...",
            width / 2f,
            height - 24f,
            warningTextPaint
        )
    }
}
