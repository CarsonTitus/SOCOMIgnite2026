package com.evensocom.psyopvisr.vision

import android.graphics.Bitmap

/**
 * Classifies a person crop bitmap as MILITARY, CIVILIAN, or UNKNOWN based on
 * HSV color histogram analysis. No external dependencies — uses Android Bitmap APIs only.
 *
 * Military heuristic: dominant colors are in OD green, tan/coyote, desert sand,
 * woodland green, or near-black (tactical) ranges. These are low-saturation
 * earth tones. Civilian clothing tends toward higher saturation and more varied hues.
 */
object PersonClassifier {

    enum class PersonType { MILITARY, CIVILIAN, UNKNOWN }

    private const val MAX_DIMENSION = 100
    private const val MAX_SAMPLES = 500
    private const val MILITARY_FRACTION_THRESHOLD = 0.40f
    private const val MIN_DIMENSION = 20

    /**
     * Classify a person crop as MILITARY or CIVILIAN based on HSV color distribution.
     *
     * Returns UNKNOWN if the crop is too small (< 20x20) to classify reliably.
     */
    fun classify(crop: Bitmap): PersonType {
        if (crop.width < MIN_DIMENSION || crop.height < MIN_DIMENSION) {
            return PersonType.UNKNOWN
        }

        // Scale down to max 100x100 for speed
        val scaled = if (crop.width > MAX_DIMENSION || crop.height > MAX_DIMENSION) {
            val scale = minOf(MAX_DIMENSION.toFloat() / crop.width, MAX_DIMENSION.toFloat() / crop.height)
            val newW = (crop.width * scale).toInt().coerceAtLeast(1)
            val newH = (crop.height * scale).toInt().coerceAtLeast(1)
            try {
                Bitmap.createScaledBitmap(crop, newW, newH, false)
            } catch (e: Exception) {
                return PersonType.UNKNOWN
            }
        } else {
            crop
        }

        val totalPixels = scaled.width * scaled.height
        if (totalPixels == 0) return PersonType.UNKNOWN

        // Stride-based sampling so total samples <= MAX_SAMPLES
        val stride = maxOf(1, totalPixels / MAX_SAMPLES)

        var militaryCount = 0
        var sampledCount = 0

        var pixelIndex = 0
        outer@ for (y in 0 until scaled.height) {
            for (x in 0 until scaled.width) {
                if (pixelIndex % stride == 0) {
                    val pixel = scaled.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val hsv = rgbToHsv(r, g, b)
                    if (isMilitaryColor(hsv[0], hsv[1], hsv[2])) {
                        militaryCount++
                    }
                    sampledCount++
                    if (sampledCount >= MAX_SAMPLES) break@outer
                }
                pixelIndex++
            }
        }

        // Recycle the scaled bitmap only if we created a new one
        if (scaled !== crop) {
            scaled.recycle()
        }

        if (sampledCount == 0) return PersonType.UNKNOWN

        val militaryFraction = militaryCount.toFloat() / sampledCount
        return if (militaryFraction >= MILITARY_FRACTION_THRESHOLD) {
            PersonType.MILITARY
        } else {
            PersonType.CIVILIAN
        }
    }

    /**
     * Convert RGB (0-255 each) to HSV.
     * Returns FloatArray of [H(0-360), S(0-100), V(0-100)].
     */
    private fun rgbToHsv(r: Int, g: Int, b: Int): FloatArray {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f

        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val delta = max - min

        val v = max * 100f
        val s = if (max == 0f) 0f else (delta / max) * 100f

        val h = when {
            delta == 0f -> 0f
            max == rf -> {
                var h = 60f * (((gf - bf) / delta) % 6f)
                if (h < 0f) h += 360f
                h
            }
            max == gf -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }

        return floatArrayOf(h, s, v)
    }

    /**
     * Returns true if the given HSV values fall within any recognized military color range.
     *
     * Military color ranges (HSV):
     *   OD Green:        H=60-140, S=30-80, V=30-70  (dull olive/green)
     *   Tan/Coyote:      H=20-45,  S=20-60, V=50-80  (sandy tan)
     *   Desert/Khaki:    H=25-50,  S=15-40, V=60-85
     *   Dark/Tactical:   H=any,    S<20,    V<30      (near-black)
     *   MultiCam/Camo:   H=50-120, S=20-55, V=35-70  (mixed greens/browns)
     */
    private fun isMilitaryColor(h: Float, s: Float, v: Float): Boolean {
        // OD Green: dull olive/green tones
        if (h in 60f..140f && s in 30f..80f && v in 30f..70f) return true

        // Tan/Coyote: sandy tan tones
        if (h in 20f..45f && s in 20f..60f && v in 50f..80f) return true

        // Desert/Khaki: lighter sandy tones
        if (h in 25f..50f && s in 15f..40f && v in 60f..85f) return true

        // Dark/Tactical: near-black regardless of hue
        if (s < 20f && v < 30f) return true

        // MultiCam/Camo: mixed greens and browns
        if (h in 50f..120f && s in 20f..55f && v in 35f..70f) return true

        return false
    }
}
