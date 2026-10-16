package com.socom.d455recorder

import org.junit.Test
import java.io.File

/** Writes a depth PNG with known values to app/build/png16_test.png, to check with OpenCV (see README). */
class Png16Test {
    @Test
    fun writesKnownPattern() {
        val w = 848; val h = 480
        val z = ByteArray(w * h * 2)
        for (y in 0 until h) for (x in 0 until w) {
            val v = if ((x + y) % 97 == 0) 0 else (x * 31 + y * 17 + x * y) % 65536  // incl. no-data zeros, full range
            z[2 * (y * w + x)] = (v and 0xFF).toByte(); z[2 * (y * w + x) + 1] = (v shr 8).toByte()
        }
        val out = File(System.getProperty("png16.out") ?: "build/png16_test.png")
        FrameWriter.writePng16(out, z, w, h)
    }
}
