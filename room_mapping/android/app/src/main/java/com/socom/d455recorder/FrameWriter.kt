package com.socom.d455recorder

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Pinhole intrinsics of the colour stream (depth is aligned to it). */
private class ImuSample(val t: Double, val sensor: Char, val x: Float, val y: Float, val z: Float)

data class Intrinsics(val width: Int, val height: Int, val fx: Float, val fy: Float, val ppx: Float, val ppy: Float)

/**
 * Writes aligned depth/colour frames to a recording folder on background threads, in the same layout as
 * room_mapping/rgbd_io.py (Open3D's RGB-D dataset format), so reconstruct.py reads phone recordings unchanged:
 *     color/000000.jpg   colour (JPEG 90)
 *     depth/000000.png   16-bit depth in raw units (lossless PNG), aligned to the colour
 *     intrinsic.json     Open3D PinholeCameraIntrinsic (colour camera; meta.json "aligned_to": "color")
 *     meta.json          depth_scale, fps, frame timestamps, dropped-frame count (+ "imu" info if recorded)
 *     imu.csv            optional: t_ms,sensor,x,y,z (g = gyro rad/s, a = accel m/s^2, IMU axes, frame clock)
 */
class FrameWriter(val folder: File, private val intr: Intrinsics, depthScale: Double, fps: Int) {
    private val pool = Executors.newFixedThreadPool(WRITER_THREADS)
    private val pending = AtomicInteger(0)
    private val timestamps = ArrayList<Double>()
    private val meta = JSONObject()
    private val imu = ArrayList<ImuSample>()  // guarded by itself: added from librealsense's motion callback thread
    /** Gyro/accel rates and the IMU-to-depth rotation, as in capture.py's meta["imu"]; null = no IMU. */
    var imuInfo: JSONObject? = null
    var count = 0
        private set
    var dropped = 0

    init {
        File(folder, "color").mkdirs()
        File(folder, "depth").mkdirs()
        val k = JSONArray(listOf(intr.fx, 0, 0, 0, intr.fy, 0, intr.ppx, intr.ppy, 1).map { it.toDouble() })
        File(folder, "intrinsic.json").writeText(
            JSONObject().put("width", intr.width).put("height", intr.height).put("intrinsic_matrix", k).toString())
        meta.put("recorded_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            .put("depth_scale", depthScale).put("fps", fps).put("device", "android").put("aligned_to", "color")
    }

    /** depth: little-endian uint16 (Z16), color: packed RGB8, both intrinsics-sized. False if the queue was full. */
    fun add(depth: ByteArray, colorRgb: ByteArray, timestampMs: Double): Boolean {
        if (pending.get() >= MAX_PENDING) {
            dropped++
            return false
        }
        val i = count++
        timestamps.add(timestampMs)
        pending.incrementAndGet()
        pool.execute {
            try {
                writePng16(File(folder, "depth/%06d.png".format(i)), depth, intr.width, intr.height)
                writeJpeg(File(folder, "color/%06d.jpg".format(i)), colorRgb, intr.width, intr.height)
            } finally {
                pending.decrementAndGet()
            }
        }
        return true
    }

    fun pending() = pending.get()

    /** sensor: 'g' (gyro, rad/s) or 'a' (accel, m/s^2). Safe to call from any thread. */
    fun addImu(timestampMs: Double, sensor: Char, x: Float, y: Float, z: Float) {
        synchronized(imu) { imu.add(ImuSample(timestampMs, sensor, x, y, z)) }
    }

    fun imuCount() = synchronized(imu) { imu.size }

    /** Waits for queued frames to reach storage, then writes meta.json. */
    fun close() {
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.MINUTES)
        meta.put("timestamps_ms", JSONArray(timestamps)).put("dropped", dropped)
        val samples = synchronized(imu) { imu.sortedBy { it.t } }
        imuInfo?.let { info ->
            if (samples.isNotEmpty()) {
                File(folder, "imu.csv").bufferedWriter().use { out ->
                    out.write("t_ms,sensor,x,y,z\n")
                    for (s in samples) out.write("%.3f,%c,%.6f,%.6f,%.6f\n".format(Locale.US, s.t, s.sensor, s.x, s.y, s.z))
                }
                meta.put("imu", JSONObject(info.toString()).put("samples", samples.size))
            }
        }
        File(folder, "meta.json").writeText(meta.toString())
    }

    companion object {
        const val WRITER_THREADS = 3
        const val MAX_PENDING = 90  // ~3 s at 30 fps, ~180 MB of buffered frames
        const val JPEG_QUALITY = 90

        fun nextRecordingFolder(root: File): File {
            val nums = root.listFiles()?.filter { it.isDirectory }
                ?.mapNotNull { it.name.substringBefore('_').toIntOrNull() } ?: emptyList()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            return File(root, "%03d_%s".format((nums.maxOrNull() ?: 0) + 1, stamp))
        }

        fun rgbToArgb(rgb: ByteArray, out: IntArray) {
            for (p in out.indices) {
                val j = p * 3
                out[p] = (0xFF shl 24) or ((rgb[j].toInt() and 0xFF) shl 16) or
                    ((rgb[j + 1].toInt() and 0xFF) shl 8) or (rgb[j + 2].toInt() and 0xFF)
            }
        }

        private fun writeJpeg(file: File, rgb: ByteArray, w: Int, h: Int) {
            val px = IntArray(w * h)
            rgbToArgb(rgb, px)
            val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            BufferedOutputStream(FileOutputStream(file)).use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            bmp.recycle()
        }

        /** Grayscale 16-bit PNG (Android's Bitmap can only write 8-bit). Sub filter + fast deflate, like cv2 level 1. */
        fun writePng16(file: File, z16le: ByteArray, w: Int, h: Int) {
            val stride = w * 2
            val raw = ByteArray(h * (stride + 1))
            for (y in 0 until h) {
                val o = y * (stride + 1)
                raw[o] = 1  // filter type Sub: each byte minus the same byte of the previous pixel
                val s = y * stride
                for (x in 0 until w) {
                    val hi = z16le[s + 2 * x + 1]  // PNG samples are big-endian
                    val lo = z16le[s + 2 * x]
                    if (x == 0) {
                        raw[o + 1] = hi; raw[o + 2] = lo
                    } else {
                        raw[o + 1 + 2 * x] = (hi - z16le[s + 2 * x - 1]).toByte()
                        raw[o + 2 + 2 * x] = (lo - z16le[s + 2 * x - 2]).toByte()
                    }
                }
            }
            val idat = ByteArrayOutputStream(raw.size / 3)
            val deflater = Deflater(1)
            DeflaterOutputStream(idat, deflater, 64 * 1024).use { it.write(raw) }
            deflater.end()

            DataOutputStream(BufferedOutputStream(FileOutputStream(file), 64 * 1024)).use { out ->
                out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
                val ihdr = ByteArrayOutputStream().also {
                    DataOutputStream(it).apply { writeInt(w); writeInt(h); write(byteArrayOf(16, 0, 0, 0, 0)) }
                }.toByteArray()  // bit depth 16, colour type 0 (grayscale)
                chunk(out, "IHDR", ihdr)
                chunk(out, "IDAT", idat.toByteArray())
                chunk(out, "IEND", ByteArray(0))
            }
        }

        private fun chunk(out: DataOutputStream, type: String, data: ByteArray) {
            val t = type.toByteArray(Charsets.US_ASCII)
            out.writeInt(data.size)
            out.write(t)
            out.write(data)
            out.writeInt(CRC32().apply { update(t); update(data) }.value.toInt())
        }
    }
}
