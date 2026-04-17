package com.evensocom.psyopvisr.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageProxy
import com.evensocom.psyopvisr.db.CulturalContextEngine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * Five-stage symbol recognition pipeline:
 *
 *   Stage 1 — YoloV8Detector region detection (religious_symbols.tflite)
 *              Falls back to OCR-only if model absent.
 *   Stage 2 — Per-crop OCR + full-frame OCR fallback (ML Kit)
 *   Stage 3 — MobileCLIP sign-type confirmation per crop (zero-shot)
 *   Stage 4 — Signal fusion (YOLOv8 label + OCR text + CLIP confirmation + keyword alert)
 *   Stage 5 — Ranked output (alertLevel DESC, confidence DESC), top 3
 *
 * Returns [ScanResult.SymbolResult] with primary detection + up to 2 extras.
 */
class SymbolRecognitionEngine(
    private val context: Context,
    private val culturalDb: CulturalContextEngine
) {

    companion object {
        private const val TAG = "SymbolRecognitionEngine"
        private const val YOLO_CONF_THRESHOLD   = 0.30f
        private const val CLIP_CONFIRM_THRESHOLD = 0.55f
        private const val PIPELINE_MAX_RESULTS  = 3
        private const val MIN_TEXT_LENGTH       = 2
        private const val OCR_TIMEOUT_MS        = 1500L

        // Keywords that elevate sign OCR text to alert ≥ 1
        private val SIGN_KEYWORDS = setOf(
            "sign","warning","danger","stop","exit","restricted","checkpoint",
            "authorized","hazard","biohazard","no entry","keep out","military",
            "police","hospital","medical","mine","ied","explosive","bomb",
            "control","army","force","evacuate","escape","halt"
        )

        // Alert level and tactical context keyed by YoloV8 class name
        private val SYMBOL_ALERT: Map<String, Pair<Int, String>> = mapOf(
            "buddhism"        to (0 to "Buddhist symbol — place of worship"),
            "christianity"    to (0 to "Christian symbol — church/chapel environment"),
            "cresent_and_star" to (0 to "Islamic symbol — mosque / Muslim community"),
            "hinduism"        to (0 to "Hindu symbol — temple / Hindu community"),
            "judaism"         to (0 to "Jewish symbol — synagogue / Jewish community"),
            "swastika"        to (1 to "Swastika — context required: Hindu/Buddhist (sacred) or Neo-Nazi (threat)")
        )

        // Human-readable display label for each class
        private val SYMBOL_DISPLAY: Map<String, String> = mapOf(
            "buddhism"         to "BUDDHIST SYMBOL",
            "christianity"     to "CHRISTIAN CROSS",
            "cresent_and_star" to "CRESCENT & STAR",
            "hinduism"         to "HINDU SYMBOL",
            "judaism"          to "STAR OF DAVID",
            "swastika"         to "SWASTIKA"
        )

        // CLIP label substrings that indicate a religious/ideological symbol match
        val SYMBOL_PROFILES_FOR_CLIP = listOf(
            "swastika","hakenkreuz","star of david","christian cross","crucifix",
            "orthodox cross","crescent","om symbol","dharma wheel","buddha",
            "menorah","religious"
        )
    }

    // ── model init ──────────────────────────────────────────────────────────

    private val yoloDetector = YoloV8Detector()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    private val mobileCLIP: MobileCLIPClassifier by lazy { MobileCLIPClassifier(context) }

    // ── internal structures ─────────────────────────────────────────────────

    private data class Stage1Detection(
        val bbox: RectF,
        val className: String,    // YOLOv8 class label
        val displayLabel: String, // human-readable
        val confidence: Float,
        val alertLevel: Int,
        val baseContext: String
    )

    private data class FusedDetection(
        val bbox: RectF,
        val displayLabel: String,
        val confidence: Float,
        val clipLabel: String,
        val clipConf: Float,
        val ocrText: String,
        val alertLevel: Int,
        val context: String
    )

    // ── public entry point ──────────────────────────────────────────────────

    fun detect(imageProxy: ImageProxy): ScanResult {
        val bitmap: Bitmap = try {
            imageProxy.toBitmap()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to convert ImageProxy to Bitmap: $e")
            return ScanResult.ErrorResult("FRAME ERROR\nCould not read camera frame")
        }

        val rotDeg = imageProxy.imageInfo.rotationDegrees
        val frameW = bitmap.width.toFloat()
        val frameH = bitmap.height.toFloat()

        return try {
            runPipeline(bitmap, rotDeg, frameW, frameH)
        } catch (e: Exception) {
            Log.e(TAG, "Symbol recognition pipeline failed: $e")
            ScanResult.ErrorResult("SYMBOL ERROR\n${e.message?.take(40) ?: "Unknown error"}")
        }
    }

    // ── pipeline ────────────────────────────────────────────────────────────

    private fun runPipeline(bitmap: Bitmap, rotDeg: Int, frameW: Float, frameH: Float): ScanResult {

        // ── Stage 1: YOLOv8 religious symbol detection ───────────────────────
        val stage1: List<Stage1Detection> = if (yoloDetector.isAvailable) {
            yoloDetector.detect(bitmap)
                .filter { box -> box.confidence >= YOLO_CONF_THRESHOLD }
                .map { box ->
                val (alert, ctx) = SYMBOL_ALERT[box.className] ?: (0 to "Symbol detected")
                Stage1Detection(
                    bbox         = box.bbox,
                    className    = box.className,
                    displayLabel = SYMBOL_DISPLAY[box.className] ?: box.className.uppercase(),
                    confidence   = box.confidence,
                    alertLevel   = alert,
                    baseContext  = ctx
                )
            }
        } else {
            Log.w(TAG, "YoloV8Detector unavailable — symbol model not loaded")
            emptyList()
        }

        // ── Stage 2: Per-crop OCR ────────────────────────────────────────────
        data class CropOcr(val stage1: Stage1Detection, val ocrText: String)

        val cropOcrList = stage1.map { det ->
            val text = try {
                val l = det.bbox.left.toInt().coerceIn(0, bitmap.width - 1)
                val t = det.bbox.top.toInt().coerceIn(0, bitmap.height - 1)
                val r = det.bbox.right.toInt().coerceIn(l + 1, bitmap.width)
                val b = det.bbox.bottom.toInt().coerceIn(t + 1, bitmap.height)
                val crop = Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
                runCropOcr(crop)
            } catch (e: Exception) {
                Log.d(TAG, "Crop OCR failed: $e"); ""
            }
            CropOcr(det, text)
        }

        // Full-frame OCR fallback (catches text signs with no bbox overlap)
        val fullFrameOcrDets = runFullFrameOcr(bitmap, rotDeg)

        // ── Stage 3: MobileCLIP confirmation ─────────────────────────────────
        data class ClipResult(val label: String, val conf: Float)

        val clipResults: Map<Stage1Detection, ClipResult> = if (mobileCLIP.isAvailable) {
            stage1.associateWith { det ->
                try {
                    val l = det.bbox.left.toInt().coerceIn(0, bitmap.width - 1)
                    val t = det.bbox.top.toInt().coerceIn(0, bitmap.height - 1)
                    val r = det.bbox.right.toInt().coerceIn(l + 1, bitmap.width)
                    val b = det.bbox.bottom.toInt().coerceIn(t + 1, bitmap.height)
                    val crop = Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
                    val rel = mobileCLIP.classifyReligiousSymbol(crop)
                    if (rel.label != "NO_RELIGIOUS_SYMBOL" && rel.label != "UNAVAILABLE" && rel.confidence > CLIP_CONFIRM_THRESHOLD)
                        ClipResult(rel.label, rel.confidence)
                    else {
                        val gen = mobileCLIP.classifyImage(crop, useScenePrompts = false)
                        ClipResult(gen.label, gen.confidence)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "CLIP failed for ${det.className}: $e"); ClipResult("", 0f)
                }
            }
        } else emptyMap()

        // Full-frame CLIP fallback for iconography that YOLO missed
        val fullFrameClipDets: List<FusedDetection> = if (mobileCLIP.isAvailable) {
            try {
                val rel = mobileCLIP.classifyReligiousSymbol(bitmap)
                val fullClip = if (rel.label != "NO_RELIGIOUS_SYMBOL" && rel.label != "UNAVAILABLE") rel
                               else mobileCLIP.classifyImage(bitmap, useScenePrompts = false)
                val lbl = fullClip.label.lowercase()
                val symbolHit = SYMBOL_PROFILES_FOR_CLIP.any { lbl.contains(it) }
                if (symbolHit && fullClip.confidence > 0.62f && stage1.isEmpty()) {
                    val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
                    val box = RectF(w * 0.2f, h * 0.2f, w * 0.8f, h * 0.8f)
                    val (alert, ctx) = clipLabelToAlertContext(lbl)
                    listOf(FusedDetection(
                        bbox = box, displayLabel = fullClip.label.uppercase(),
                        confidence = fullClip.confidence, clipLabel = fullClip.label,
                        clipConf = fullClip.confidence, ocrText = "",
                        alertLevel = alert, context = ctx
                    ))
                } else emptyList()
            } catch (e: Exception) { emptyList() }
        } else emptyList()

        // ── Stage 4: Signal fusion ────────────────────────────────────────────
        val fusedList = mutableListOf<FusedDetection>()

        for (item in cropOcrList) {
            val det  = item.stage1
            val clip = clipResults[det] ?: ClipResult("", 0f)
            val (kwAlert, kwCtx) = classifySignText(item.ocrText)

            // Display label: prefer YOLO (trained specifically for these symbols)
            // CLIP can override if confidence is very high and YOLO was low confidence
            val displayLabel = if (clip.conf > 0.75f && clip.label.isNotBlank() &&
                                   det.confidence < 0.45f) {
                clip.label.uppercase()
            } else {
                det.displayLabel
            }

            // Alert: take max of YOLO class, CLIP hint, OCR keyword
            val clipAlertBoost = if (clip.conf > 0.7f && clip.label.contains("swastika", ignoreCase = true) ||
                                     clip.label.contains("hakenkreuz", ignoreCase = true)) 2
                                 else 0
            val alertLevel = maxOf(det.alertLevel, kwAlert, clipAlertBoost)

            // Context string
            val contextStr = when {
                item.ocrText.isNotBlank() && kwAlert > 0 ->
                    "$kwCtx (OCR: ${item.ocrText.take(40)})"
                item.ocrText.isNotBlank() -> "${det.displayLabel}: ${item.ocrText.take(40)}"
                clip.conf > CLIP_CONFIRM_THRESHOLD && clip.label.isNotBlank() ->
                    "${det.baseContext} (CLIP: ${clip.label.take(25)})"
                else -> det.baseContext
            }

            val bestConf = maxOf(det.confidence, clip.conf)
            fusedList += FusedDetection(
                bbox = det.bbox, displayLabel = displayLabel,
                confidence = bestConf, clipLabel = clip.label, clipConf = clip.conf,
                ocrText = item.ocrText, alertLevel = alertLevel, context = contextStr
            )
        }

        // Inject full-frame CLIP detections not covered by YOLO
        fusedList += fullFrameClipDets

        // Full-frame OCR detections not covered by any bbox
        for (ocrDet in fullFrameOcrDets) {
            val covered = fusedList.any { f ->
                RectF.intersects(f.bbox, ocrDet.bbox) && ocrDet.bbox.width() < f.bbox.width() * 1.5f
            }
            if (!covered) {
                fusedList += FusedDetection(
                    bbox = ocrDet.bbox, displayLabel = ocrDet.label,
                    confidence = ocrDet.confidence, clipLabel = "", clipConf = 0f,
                    ocrText = ocrDet.label, alertLevel = ocrDet.alertLevel,
                    context = ocrDet.contextText
                )
            }
        }

        // ── Stage 5: Rank and emit ────────────────────────────────────────────
        val ranked = fusedList
            .sortedWith(compareByDescending<FusedDetection> { it.alertLevel }
                .thenByDescending { it.confidence })
            .take(PIPELINE_MAX_RESULTS)

        // Push VisualizationBus frame
        val vizDets = ranked.map { f ->
            SymbolDetection(bbox = f.bbox, label = f.displayLabel,
                confidence = f.confidence, alertLevel = f.alertLevel, contextText = f.context)
        }
        val currentMode = VisionModeController.instance?.currentMode
        val liveFrame = if (currentMode == VisionMode.CULTURAL_CONTEXT) {
            VisualizationFrame.CulturalFrame(
                imageWidth = bitmap.width, imageHeight = bitmap.height,
                rotationDegrees = rotDeg, detections = vizDets,
                contextSummary = ranked.firstOrNull()?.context ?: ""
            )
        } else {
            VisualizationFrame.SymbolFrame(
                imageWidth = bitmap.width, imageHeight = bitmap.height,
                rotationDegrees = rotDeg, detections = vizDets
            )
        }
        VisualizationBus.postFrame(liveFrame)

        // If everything is empty (e.g. YOLO offline, no OCR hits), fall back to full-frame CLIP
        // so the user always gets some result rather than a dead error.
        if (ranked.isEmpty()) {
            return if (mobileCLIP.isAvailable) {
                val clip = try {
                    val rel = mobileCLIP.classifyReligiousSymbol(bitmap)
                    if (rel.label != "NO_RELIGIOUS_SYMBOL" && rel.label != "UNAVAILABLE") rel
                    else mobileCLIP.classifyImage(bitmap, useScenePrompts = false)
                } catch (e: Exception) { null }

                if (clip != null && clip.label != "UNAVAILABLE") {
                    val (alert, ctx) = clipLabelToAlertContext(clip.label.lowercase())
                    ScanResult.SymbolResult(
                        label      = clip.label.uppercase().take(25),
                        position   = "CENTER / MID",
                        context    = ctx,
                        alertLevel = alert,
                        confidence = clip.confidence,
                        extras     = listOf("CLIP fallback — Roboflow offline")
                    )
                } else {
                    ScanResult.ErrorResult("SYMBOL SCAN\nNo network — point at symbol")
                }
            } else {
                ScanResult.ErrorResult("SYMBOL SCAN\nNo network — point at symbol")
            }
        }

        val top = ranked.first()
        val bbox = top.bbox
        val xPos = classifyXPosition(((bbox.left + bbox.right) / 2f) / frameW)
        val yPos = classifyYPosition(((bbox.top + bbox.bottom) / 2f) / frameH)

        val extras = ranked.drop(1).take(2).map { f ->
            "${f.displayLabel}  ${(f.confidence * 100).toInt()}%"
        }

        return ScanResult.SymbolResult(
            label      = top.displayLabel,
            position   = "$xPos / $yPos",
            context    = top.context,
            alertLevel = top.alertLevel,
            confidence = top.confidence,
            extras     = extras
        )
    }

    // ── OCR helpers ─────────────────────────────────────────────────────────

    fun runCropOcr(cropBitmap: Bitmap): String {
        return try {
            val inputImage = InputImage.fromBitmap(cropBitmap, 0)
            val result = Tasks.await(
                textRecognizer.process(inputImage), OCR_TIMEOUT_MS, TimeUnit.MILLISECONDS
            )
            result.textBlocks
                .map { it.text.trim() }
                .filter { it.length >= MIN_TEXT_LENGTH }
                .joinToString(" ")
        } catch (e: Exception) {
            Log.d(TAG, "runCropOcr failed: $e"); ""
        }
    }

    private fun runFullFrameOcr(bitmap: Bitmap, rotDeg: Int): List<SymbolDetection> {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, rotDeg)
            val result = Tasks.await(textRecognizer.process(inputImage), 2, TimeUnit.SECONDS)
            val imageArea = bitmap.width.toFloat() * bitmap.height.toFloat()
            result.textBlocks
                .filter { block -> looksLikeSign(block.text.trim(), block.boundingBox, imageArea) }
                .mapNotNull { block ->
                    val text = block.text.trim().uppercase()
                    val rect = block.boundingBox ?: return@mapNotNull null
                    val (alertLevel, ctx) = classifySignText(text)
                    SymbolDetection(
                        bbox = RectF(rect),
                        label = if (text.length > 20) text.take(20) + "…" else text,
                        confidence = 0.9f,
                        alertLevel = alertLevel,
                        contextText = ctx
                    )
                }
        } catch (e: Exception) {
            Log.d(TAG, "Full-frame OCR skipped: $e"); emptyList()
        }
    }

    // ── sign text / OCR classification ──────────────────────────────────────

    private fun looksLikeSign(text: String, bbox: android.graphics.Rect?, imageArea: Float): Boolean {
        if (text.length < MIN_TEXT_LENGTH || text.length > 25) return false
        val words = text.trim().split(Regex("\\s+"))
        if (words.size > 4) return false
        val letters = text.count { it.isLetter() }
        val uppers  = text.count { it.isUpperCase() }
        if (letters > 0 && uppers.toFloat() / letters < 0.6f) return false
        if (bbox != null) {
            val blockArea = bbox.width().toFloat() * bbox.height().toFloat()
            if (blockArea / imageArea < 0.008f) return false
        }
        return true
    }

    private fun classifySignText(text: String): Pair<Int, String> {
        val upper = text.uppercase()
        return when {
            upper.contains("MINE") || upper.contains("EXPLOSIVE") ||
            upper.contains("IED")  || upper.contains("BOMB")
                -> 2 to "EXPLOSIVE HAZARD — do not approach"
            upper.contains("DANGER") || upper.contains("CRITICAL") ||
            upper.contains("THREAT")  || upper.contains("ARMED")
                -> 2 to "Danger zone indicated"
            upper.contains("WARNING") || upper.contains("RESTRICTED") ||
            upper.contains("AUTHORIZED") || upper.contains("KEEP OUT")
                -> 1 to "Restricted / controlled area"
            upper.contains("CHECKPOINT") || upper.contains("CONTROL")
                -> 1 to "Tactical checkpoint or control point"
            upper.contains("POLICE") || upper.contains("MILITARY") ||
            upper.contains("ARMY")   || upper.contains("FORCE")
                -> 1 to "Law enforcement or military presence"
            upper.contains("HOSPITAL") || upper.contains("MEDICAL") ||
            upper.contains("CLINIC")
                -> 0 to "Medical facility"
            upper.contains("EXIT") || upper.contains("EVACUATE") ||
            upper.contains("ESCAPE")
                -> 0 to "Evacuation route"
            upper.contains("STOP") || upper.contains("HALT")
                -> 0 to "Traffic / movement control"
            else -> 0 to "Sign text detected"
        }
    }

    // ── CLIP label → alert context fallback ─────────────────────────────────

    private fun clipLabelToAlertContext(lbl: String): Pair<Int, String> {
        return when {
            lbl.contains("swastika") || lbl.contains("hakenkreuz") ->
                2 to "Swastika detected — assess Buddhist/Hindu vs Nazi context"
            lbl.contains("star of david") || lbl.contains("menorah") ->
                0 to "Jewish symbol — synagogue or Jewish community"
            lbl.contains("christian") || lbl.contains("cross") || lbl.contains("crucifix") ->
                0 to "Christian symbol — church or Christian community"
            lbl.contains("crescent") || lbl.contains("star") ->
                0 to "Islamic symbol — mosque or Muslim community"
            lbl.contains("om") || lbl.contains("dharma") || lbl.contains("buddha") ->
                0 to "Buddhist/Hindu symbol — religious site"
            else -> 0 to "Religious/cultural symbol detected"
        }
    }

    // ── position helpers ─────────────────────────────────────────────────────

    private fun classifyXPosition(x: Float) = when {
        x < 0.2f -> "FAR LEFT"; x < 0.4f -> "LEFT"; x < 0.6f -> "CENTER"
        x < 0.8f -> "RIGHT"; else -> "FAR RIGHT"
    }

    private fun classifyYPosition(y: Float) = when {
        y < 0.33f -> "TOP"; y < 0.66f -> "MID"; else -> "BOTTOM"
    }

}
