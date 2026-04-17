package com.evensocom.psyopvisr.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.evensocom.psyopvisr.vision.*

/**
 * Full-screen transparent view drawn on top of [PreviewView].
 *
 * Call [setFrame] from the main thread (or post to the main handler) whenever
 * a new [VisualizationFrame] arrives from [VisualizationBus].
 *
 * Coordinate transform:
 *   1. Rotate bbox from image space to upright display space (using [VisualizationFrame.rotationDegrees]).
 *   2. Scale from upright image dimensions to view dimensions using FILL_CENTER (matches PreviewView default).
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ──────────────────── Mode colours ────────────────────

    private val COLOR_FACE     = Color.parseColor("#00E676")  // bright green
    private val COLOR_SYMBOL   = Color.parseColor("#FF9800")  // amber
    private val COLOR_CULTURAL = Color.parseColor("#CE93D8")  // purple
    private val COLOR_ROOM     = Color.parseColor("#29B6F6")  // light blue

    // ──────────────────── Paints ────────────────────

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = Typeface.MONOSPACE
    }
    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        typeface = Typeface.MONOSPACE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ──────────────────── State ────────────────────

    @Volatile private var currentFrame: VisualizationFrame? = null

    fun setFrame(frame: VisualizationFrame?) {
        currentFrame = frame
        invalidate()
    }

    // ──────────────────── Draw ────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = currentFrame ?: return

        when (frame) {
            is VisualizationFrame.FaceFrame    -> drawFaceFrame(canvas, frame)
            is VisualizationFrame.SymbolFrame  -> drawSymbolFrame(canvas, frame)
            is VisualizationFrame.CulturalFrame -> drawCulturalFrame(canvas, frame)
            is VisualizationFrame.RoomFrame    -> drawRoomFrame(canvas, frame)
            is VisualizationFrame.RoomSpatialFrame -> drawRoomSpatialBanner(canvas, frame)
        }
    }

    // ──────────────────── Mode renderers ────────────────────

    private fun drawFaceFrame(canvas: Canvas, frame: VisualizationFrame.FaceFrame) {
        val color = COLOR_FACE
        boxPaint.color = color
        labelBgPaint.color = Color.argb(180, 0, 77, 38)
        dotPaint.color = color

        for (det in frame.detections) {
            val vBox = mapToView(det.bbox, frame)
            canvas.drawRect(vBox, boxPaint)

            // Corner accent dots
            drawCornerDots(canvas, vBox, color)

            // Label strip above box
            val label = det.identity.let { id ->
                if (id == "UNKNOWN") det.personType.name else id
            }
            val confStr = "${(det.confidence * 100).toInt()}%"
            drawLabelAbove(canvas, vBox, label, confStr, labelBgPaint, labelPaint, subLabelPaint)
        }
    }

    private fun drawSymbolFrame(canvas: Canvas, frame: VisualizationFrame.SymbolFrame) {
        val color = COLOR_SYMBOL
        boxPaint.color = color
        labelBgPaint.color = Color.argb(180, 80, 50, 0)
        dotPaint.color = color

        for (det in frame.detections) {
            val vBox = mapToView(det.bbox, frame)
            val strokeMult = if (det.alertLevel >= 2) 2f else 1f
            boxPaint.strokeWidth = 3f * strokeMult
            canvas.drawRect(vBox, boxPaint)
            boxPaint.strokeWidth = 3f

            val alertPrefix = if (det.alertLevel >= 2) "! " else ""
            drawLabelAbove(canvas, vBox, "$alertPrefix${det.label}",
                "${(det.confidence * 100).toInt()}%", labelBgPaint, labelPaint, subLabelPaint)
        }
    }

    private fun drawCulturalFrame(canvas: Canvas, frame: VisualizationFrame.CulturalFrame) {
        val color = COLOR_CULTURAL
        boxPaint.color = color
        labelBgPaint.color = Color.argb(180, 60, 0, 80)
        dotPaint.color = color

        for (det in frame.detections) {
            val vBox = mapToView(det.bbox, frame)
            canvas.drawRect(vBox, boxPaint)

            // Show label + truncated context below the box
            drawLabelAbove(canvas, vBox, det.label,
                det.contextText.take(28), labelBgPaint, labelPaint, subLabelPaint)
        }
    }

    private fun drawRoomFrame(canvas: Canvas, frame: VisualizationFrame.RoomFrame) {
        val color = COLOR_ROOM
        boxPaint.color = Color.argb(200,
            Color.red(color), Color.green(color), Color.blue(color))
        labelBgPaint.color = Color.argb(160, 0, 40, 70)
        dotPaint.color = color

        for (det in frame.detections) {
            val vBox = mapToView(det.bbox, frame)
            // Dashed stroke for room objects
            boxPaint.pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
            canvas.drawRect(vBox, boxPaint)
            boxPaint.pathEffect = null

            val distStr = if (det.distanceMeters < 90f) "~%.1fm".format(det.distanceMeters) else "far"
            drawLabelAbove(canvas, vBox, det.label.uppercase(), distStr,
                labelBgPaint, labelPaint, subLabelPaint)
        }

        // Draw exit summary text at bottom of overlay if present
        if (frame.exitSummary.isNotBlank()) {
            drawBottomBanner(canvas, frame.exitSummary, Color.argb(160, 0, 40, 70), color)
        }
    }

    private fun drawRoomSpatialBanner(canvas: Canvas, frame: VisualizationFrame.RoomSpatialFrame) {
        val roomColor = Color.parseColor("#29B6F6")
        val bgColor = Color.argb(200, 0, 30, 50)
        val progressPct = (frame.roomEstimate.scanProgress * 100).toInt()

        val bannerH = 72f
        val bgPaint = Paint().apply { color = bgColor }
        canvas.drawRect(0f, 0f, width.toFloat(), bannerH, bgPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = roomColor
            textSize = 30f
            typeface = Typeface.MONOSPACE
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            typeface = Typeface.MONOSPACE
        }

        canvas.drawText("◉ ROOM SCAN — ARCore depth", 16f, 32f, titlePaint)
        canvas.drawText(
            "Coverage: $progressPct%  |  ${frame.roomEstimate.sizeCategory.uppercase()}",
            16f, 60f, subPaint
        )

        val barH = 4f
        val barBgPaint = Paint().apply { color = Color.argb(120, 0, 30, 50) }
        val barFgPaint = Paint().apply { color = roomColor }
        canvas.drawRect(0f, bannerH, width.toFloat(), bannerH + barH, barBgPaint)
        canvas.drawRect(0f, bannerH, width * frame.roomEstimate.scanProgress, bannerH + barH, barFgPaint)

        val objCount = frame.spatialObjects.size
        drawBottomChip(canvas, "OBJ: $objCount", bgColor, Color.WHITE, left = true)

        if (frame.roomEstimate.personCount > 0) {
            drawBottomChip(
                canvas,
                "PERSONS: ${frame.roomEstimate.personCount}",
                Color.argb(200, 0, 77, 38),
                Color.parseColor("#00E676"),
                left = false
            )
        }
    }

    private fun drawBottomChip(
        canvas: Canvas,
        text: String,
        bgColor: Int,
        textColor: Int,
        left: Boolean
    ) {
        val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 26f
            typeface = Typeface.MONOSPACE
        }
        val bgPaint = Paint().apply { color = bgColor }
        val textW = chipPaint.measureText(text)
        val chipH = 44f
        val padding = 12f
        val chipW = textW + padding * 2
        val chipY = height - chipH - 12f

        val chipLeft = if (left) 12f else width - chipW - 12f
        val chipRight = chipLeft + chipW

        canvas.drawRoundRect(chipLeft, chipY, chipRight, chipY + chipH, 6f, 6f, bgPaint)
        canvas.drawText(text, chipLeft + padding, chipY + chipH - 10f, chipPaint)
    }

    // ──────────────────── Drawing helpers ────────────────────

    private fun drawLabelAbove(
        canvas: Canvas,
        box: RectF,
        mainText: String,
        subText: String,
        bgPaint: Paint,
        mainPaint: Paint,
        subPaint: Paint
    ) {
        val padding = 6f
        val mainW = mainPaint.measureText(mainText)
        val subW = subPaint.measureText(subText)
        val bgW = maxOf(mainW, subW) + padding * 2
        val lineH = mainPaint.textSize + subPaint.textSize + padding * 3
        val bgTop = (box.top - lineH).coerceAtLeast(0f)
        val bgLeft = box.left.coerceAtLeast(0f)
        val bgRight = (bgLeft + bgW).coerceAtMost(width.toFloat())

        canvas.drawRoundRect(bgLeft, bgTop, bgRight, box.top, 4f, 4f, bgPaint)
        canvas.drawText(mainText, bgLeft + padding, bgTop + mainPaint.textSize, mainPaint)
        canvas.drawText(subText, bgLeft + padding, bgTop + mainPaint.textSize + subPaint.textSize + padding, subPaint)
    }

    private fun drawCornerDots(canvas: Canvas, box: RectF, color: Int) {
        val r = 6f
        dotPaint.color = color
        canvas.drawCircle(box.left, box.top, r, dotPaint)
        canvas.drawCircle(box.right, box.top, r, dotPaint)
        canvas.drawCircle(box.left, box.bottom, r, dotPaint)
        canvas.drawCircle(box.right, box.bottom, r, dotPaint)
    }

    private fun drawBottomBanner(canvas: Canvas, text: String, bgColor: Int, textColor: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = textColor
            textSize = 28f
            typeface = Typeface.MONOSPACE
        }
        val bgP = Paint().apply { color = bgColor }
        val h = 48f
        val y = height - h
        canvas.drawRect(0f, y, width.toFloat(), height.toFloat(), bgP)
        canvas.drawText(text, 16f, height.toFloat() - 12f, paint)
    }

    // ──────────────────── Coordinate transform ────────────────────

    /**
     * Maps an image-space [RectF] to view (screen) coordinates.
     *
     * Steps:
     *  1. Rotate bbox by [VisualizationFrame.rotationDegrees] to get upright image space.
     *  2. Scale from upright image dimensions to view dimensions using FILL_CENTER logic.
     */
    private fun mapToView(bbox: RectF, frame: VisualizationFrame): RectF {
        val iw = frame.imageWidth.toFloat()
        val ih = frame.imageHeight.toFloat()
        val rot = frame.rotationDegrees
        val vw = width.toFloat()
        val vh = height.toFloat()

        // Step 1 — rotate bbox to upright orientation
        val (uprightW, uprightH, uprightBox) = when (rot) {
            90 -> {
                // CW 90°: point (x,y) in (iw×ih) → (ih - y, x) in (ih×iw)
                val r = RectF(ih - bbox.bottom, bbox.left, ih - bbox.top, bbox.right)
                Triple(ih, iw, r)
            }
            270 -> {
                // CCW 90°: point (x,y) in (iw×ih) → (y, iw - x) in (ih×iw)
                val r = RectF(bbox.top, iw - bbox.right, bbox.bottom, iw - bbox.left)
                Triple(ih, iw, r)
            }
            180 -> {
                val r = RectF(iw - bbox.right, ih - bbox.bottom, iw - bbox.left, ih - bbox.top)
                Triple(iw, ih, r)
            }
            else -> Triple(iw, ih, RectF(bbox))
        }

        // Step 2 — FILL_CENTER scale: scale = max(vw/uw, vh/uh)
        val scale = maxOf(vw / uprightW, vh / uprightH)
        val scaledW = uprightW * scale
        val scaledH = uprightH * scale
        val offsetX = (vw - scaledW) / 2f
        val offsetY = (vh - scaledH) / 2f

        return RectF(
            uprightBox.left  * scale + offsetX,
            uprightBox.top   * scale + offsetY,
            uprightBox.right * scale + offsetX,
            uprightBox.bottom * scale + offsetY
        )
    }
}
