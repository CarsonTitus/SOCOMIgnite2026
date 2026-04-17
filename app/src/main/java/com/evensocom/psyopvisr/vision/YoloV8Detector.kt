package com.evensocom.psyopvisr.vision

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Base64
import android.util.Log
import com.evensocom.psyopvisr.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class DetectionBox(
    val bbox: RectF,        // image-space pixels
    val classId: Int,
    val confidence: Float,
    val className: String
)

/**
 * Roboflow-hosted symbol detector for model `religious-dataset-ghpzb/1`.
 *
 * Calls the Roboflow inference REST API (hosted inference) — no TFLite export needed.
 * Frames are JPEG-compressed and base64-encoded before upload.
 *
 * Throttled to one network call per [THROTTLE_MS] ms; returns cached results between calls
 * so the camera analyzer stays smooth.
 *
 * API key should be moved to BuildConfig / secrets.properties for production.
 */
class YoloV8Detector {

    companion object {
        private const val TAG = "YoloV8Detector"

        private val API_KEY get() = BuildConfig.ROBOFLOW_API_KEY
        private const val MODEL_ID   = "religious-dataset-ghpzb/1"
        private const val BASE_URL   = "https://detect.roboflow.com"
        private const val CONFIDENCE = 30   // percent
        private const val OVERLAP    = 45   // NMS IoU percent

        private const val UPLOAD_SIZE  = 416  // resize before upload to reduce latency
        private const val JPEG_QUALITY = 75
        private const val TIMEOUT_MS   = 4_000
        private const val THROTTLE_MS  = 2_000L  // max 0.5 req/s
    }

    // ── class labels — must match Roboflow model order ──────────────────────
    val classLabels: List<String> = listOf(
        "buddhism",
        "christianity",
        "cresent_and_star",
        "hinduism",
        "judaism",
        "swastika"
    )

    /** Always true — inference is remote; availability depends on network. */
    val isAvailable: Boolean = true

    private var cachedResults: List<DetectionBox> = emptyList()
    private var lastCallMs: Long = 0L

    // ── public API ──────────────────────────────────────────────────────────

    /**
     * Returns detections for [bitmap]. Makes a network call at most once per
     * [THROTTLE_MS] ms; returns the previous result for intermediate frames.
     * Blocking — call from a background thread (CameraX analyzer thread is fine).
     */
    fun detect(bitmap: Bitmap): List<DetectionBox> {
        val now = System.currentTimeMillis()
        if (now - lastCallMs < THROTTLE_MS) return cachedResults
        lastCallMs = now

        val scaled = if (bitmap.width > UPLOAD_SIZE || bitmap.height > UPLOAD_SIZE) {
            val scale = UPLOAD_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else bitmap

        val b64 = encodeJpeg(scaled)
        val imgW = scaled.width.toFloat()
        val imgH = scaled.height.toFloat()
        val srcW = bitmap.width.toFloat()
        val srcH = bitmap.height.toFloat()

        return try {
            val response = postToRoboflow(b64)
            val boxes = parseResponse(response, imgW, imgH, srcW, srcH)
            cachedResults = boxes
            Log.d(TAG, "API returned ${boxes.size} detections")
            boxes
        } catch (e: Exception) {
            Log.w(TAG, "Roboflow API call failed: $e")
            cachedResults  // return stale on error rather than clearing
        }
    }

    fun close() { cachedResults = emptyList() }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private fun postToRoboflow(base64Jpeg: String): String {
        val endpoint = "$BASE_URL/$MODEL_ID" +
            "?api_key=$API_KEY&confidence=$CONFIDENCE&overlap=$OVERLAP"
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod  = "POST"
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout    = TIMEOUT_MS
        conn.doOutput       = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

        val body = base64Jpeg.toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
            throw IllegalStateException("HTTP $code: $err")
        }
        return conn.inputStream.bufferedReader().readText()
    }

    // ── response parsing ────────────────────────────────────────────────────

    /**
     * Roboflow prediction JSON:
     * {
     *   "predictions": [
     *     { "x":cx, "y":cy, "width":w, "height":h, "confidence":0.96,
     *       "class":"cresent_and_star", "class_id":2 }
     *   ],
     *   "image": { "width":416, "height":416 }
     * }
     * x,y,w,h are in pixels of the submitted image.
     */
    private fun parseResponse(
        json: String,
        imgW: Float, imgH: Float,   // submitted image dimensions
        srcW: Float, srcH: Float    // original bitmap dimensions
    ): List<DetectionBox> {
        val root = JSONObject(json)
        val preds = root.optJSONArray("predictions") ?: return emptyList()

        // Use image dimensions from API response if available
        val apiImg  = root.optJSONObject("image")
        val apiW    = apiImg?.optDouble("width",  imgW.toDouble())?.toFloat() ?: imgW
        val apiH    = apiImg?.optDouble("height", imgH.toDouble())?.toFloat() ?: imgH
        val scaleX  = srcW / apiW
        val scaleY  = srcH / apiH

        val boxes = mutableListOf<DetectionBox>()
        for (i in 0 until preds.length()) {
            val pred = preds.getJSONObject(i)
            val cx   = pred.getDouble("x").toFloat()
            val cy   = pred.getDouble("y").toFloat()
            val w    = pred.getDouble("width").toFloat()
            val h    = pred.getDouble("height").toFloat()
            val conf = pred.getDouble("confidence").toFloat()
            val cls  = pred.optString("class", "unknown")
            val cid  = pred.optInt("class_id", classLabels.indexOf(cls).coerceAtLeast(0))

            boxes += DetectionBox(
                bbox = RectF(
                    (cx - w / 2f) * scaleX,
                    (cy - h / 2f) * scaleY,
                    (cx + w / 2f) * scaleX,
                    (cy + h / 2f) * scaleY
                ),
                classId    = cid,
                confidence = conf,
                className  = cls
            )
        }
        return boxes.sortedByDescending { it.confidence }
    }

    // ── encoding ────────────────────────────────────────────────────────────

    private fun encodeJpeg(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }
}
