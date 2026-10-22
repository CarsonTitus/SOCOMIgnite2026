package com.socom.d455recorder

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.intel.realsense.librealsense.Align
import com.intel.realsense.librealsense.CameraInfo
import com.intel.realsense.librealsense.Config
import com.intel.realsense.librealsense.DepthSensor
import com.intel.realsense.librealsense.Device
import com.intel.realsense.librealsense.DeviceListener
import com.intel.realsense.librealsense.Extension
import com.intel.realsense.librealsense.Intrinsic
import com.intel.realsense.librealsense.Option
import com.intel.realsense.librealsense.Pipeline
import com.intel.realsense.librealsense.RsContext
import com.intel.realsense.librealsense.StreamFormat
import com.intel.realsense.librealsense.StreamType
import com.intel.realsense.librealsense.VideoStreamProfile
import java.io.File
import kotlin.concurrent.thread

/**
 * D455 recorder: live colour | depth preview, tap REC to start/stop. Same settings and output as
 * room_mapping/capture.py (848x480 depth, 640x480 colour aligned to depth, 30 fps), written to
 * /sdcard/Android/data/com.socom.d455recorder/files/recordings/NNN_YYYYMMDD_HHMMSS/.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var colorView: ImageView
    private lateinit var depthView: ImageView
    private lateinit var statusText: TextView
    private lateinit var recButton: Button

    private var rsContext: RsContext? = null
    private var cameraThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var wantRecording = false
    private lateinit var recordingsRoot: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        recordingsRoot = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }

        colorView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        depthView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        statusText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f }
        recButton = Button(this).apply {
            text = "REC"
            isEnabled = false
            setOnClickListener { wantRecording = !wantRecording; updateButton() }
        }
        val images = LinearLayout(this).apply {
            addView(colorView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(depthView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 8, 24, 8)
            addView(statusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(recButton)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(images, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar)
        })
        setStatus("Connect the D455 (USB 3 cable)")

        RsContext.init(applicationContext)  // once per process, before touching devices
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startIfConnected()
        else setStatus("Camera permission is needed to open the D455")
    }

    override fun onResume() {
        super.onResume()
        rsContext = RsContext().apply {
            setDevicesChangedCallback(object : DeviceListener {
                override fun onDeviceAttach() = runOnUiThread { startIfConnected() }
                override fun onDeviceDetach() = runOnUiThread { stopCamera(); setStatus("D455 disconnected") }
            })
        }
        startIfConnected()
    }

    override fun onPause() {
        super.onPause()
        stopCamera()  // also stops and finishes a recording in progress
        rsContext?.close()
        rsContext = null
    }

    private fun startIfConnected() {
        if (cameraThread != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val n = rsContext?.queryDevices()?.use { it.deviceCount } ?: 0
        if (n == 0) {
            setStatus("Connect the D455 (USB 3 cable)")
            return
        }
        // On USB 2 the D455 can't stream 848x480 depth + colour at 30 fps (librealsense only says "Couldn't
        // resolve requests"), so refuse to start until it's on a USB 3 link
        val usb = usbType()
        if (usb?.startsWith("3") != true) {
            Log.w(TAG, "D455 USB link: $usb")
            setStatus("D455 is on USB ${usb ?: "?"}: use a USB 3.2 cable, then replug")
            return
        }
        running = true
        cameraThread = thread(name = "d455") { cameraLoop() }
    }

    /** The camera's USB link as librealsense reports it ("3.2", "2.1", ...), or null if it can't be read. */
    private fun usbType(): String? = runCatching {
        rsContext?.queryDevices()?.use { list ->
            list.createDevice(0).use { dev ->
                if (dev.supportsInfo(CameraInfo.USB_TYPE_DESCRIPTOR)) dev.getInfo(CameraInfo.USB_TYPE_DESCRIPTOR) else null
            }
        }
    }.getOrNull()

    private fun stopCamera() {
        running = false
        wantRecording = false
        cameraThread?.join()
        cameraThread = null
        updateButton()
    }

    private fun cameraLoop() {
        val pipe = Pipeline()
        val align = Align(StreamType.DEPTH)
        var writer: FrameWriter? = null
        var recStart = 0L
        try {
            setStatus("Starting camera...")
            val depthScale = Config().use { cfg ->
                cfg.enableStream(StreamType.DEPTH, -1, DEPTH_W, DEPTH_H, StreamFormat.Z16, FPS)
                cfg.enableStream(StreamType.COLOR, -1, COLOR_W, COLOR_H, StreamFormat.RGB8, FPS)
                pipe.start(cfg).use { profile -> profile.device.use { setUpDepthSensor(it) } }
            }
            val intr = depthIntrinsics(pipe)
            repeat(WARMUP_FRAMES) { pipe.waitForFrames(5000).close() }  // let auto-exposure settle
            runOnUiThread { recButton.isEnabled = true }

            val depth0 = ByteArray(intr.width * intr.height * 2)
            val color0 = ByteArray(intr.width * intr.height * 3)
            val previewPx = IntArray(intr.width * intr.height)
            var frameIdx = 0L
            var lastNumber = -1

            while (running) {
                if (wantRecording && writer == null) {
                    writer = FrameWriter(FrameWriter.nextRecordingFolder(recordingsRoot), intr, depthScale, FPS)
                    recStart = SystemClock.elapsedRealtime()
                    lastNumber = -1
                    Log.i(TAG, "recording to ${writer.folder}")
                } else if (!wantRecording && writer != null) {
                    finishRecording(writer, recStart)
                    writer = null
                }

                pipe.waitForFrames(5000).use { frames ->
                    frames.applyFilter(align).use { aligned ->
                        val d = aligned.first(StreamType.DEPTH) ?: return@use
                        d.use {
                            val c = aligned.first(StreamType.COLOR) ?: return@use
                            c.use {
                                val rec = writer != null
                                val depth = if (rec) ByteArray(depth0.size) else depth0
                                val color = if (rec) ByteArray(color0.size) else color0
                                d.getData(depth)
                                c.getData(color)
                                writer?.let { w ->
                                    val num = d.number
                                    if (lastNumber >= 0 && num > lastNumber + 1) w.dropped += num - lastNumber - 1
                                    lastNumber = num
                                    w.add(depth, color, d.timestamp)
                                }
                                if (frameIdx++ % PREVIEW_EVERY == 0L) {
                                    showPreview(depth, color, intr, depthScale, previewPx)
                                    setStatus(statusLine(writer, recStart))
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "camera error", e)
            setStatus("Camera error: ${e.message}")
        } finally {
            writer?.let { finishRecording(it, recStart) }
            runCatching { pipe.stop() }
            align.close()
            pipe.close()
            runOnUiThread { recButton.isEnabled = false }
        }
    }

    /** Turns the IR emitter on (dot pattern helps on blank walls) and returns depth units per metre (~1000). */
    private fun setUpDepthSensor(device: Device): Double {
        // The sensors belong to the Device (Device.close() releases them), so don't close them here: double free
        val s = device.querySensors().first { it.`is`(Extension.DEPTH_SENSOR) }.`as`<DepthSensor>(Extension.DEPTH_SENSOR)
        if (s.supports(Option.EMITTER_ENABLED)) s.setValue(Option.EMITTER_ENABLED, 1f)
        return 1.0 / s.depthScale
    }

    private fun depthIntrinsics(pipe: Pipeline): Intrinsics {
        val profiles = pipe.activeStreams
        try {
            val depth = profiles.first { it.type == StreamType.DEPTH }
            val i: Intrinsic = depth.`as`<VideoStreamProfile>(Extension.VIDEO_PROFILE).intrinsic
            // The Java wrapper has no getters for focal length / principal point
            fun f(name: String) = Intrinsic::class.java.getDeclaredField(name).apply { isAccessible = true }.getFloat(i)
            return Intrinsics(i.width, i.height, f("mFx"), f("mFy"), f("mPpx"), f("mPpy"))
        } finally {
            profiles.forEach { it.close() }
        }
    }

    private fun finishRecording(w: FrameWriter, recStart: Long) {
        val secs = (SystemClock.elapsedRealtime() - recStart) / 1000.0
        setStatus("Saving ${w.pending()} queued frames...")
        w.close()
        val mb = w.folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1e6
        setStatus("Saved ${w.folder.name}: ${w.count} frames, %.0f s, %.0f MB, dropped ${w.dropped}".format(secs, mb))
        Thread.sleep(1500)  // leave the summary up for a moment
    }

    private fun statusLine(w: FrameWriter?, recStart: Long): String {
        if (w == null) {
            val freeGb = recordingsRoot.usableSpace / 1e9
            return "READY  %dx%d @ %d fps   %.0f GB free (~2.3 GB per 5 min)".format(DEPTH_W, DEPTH_H, FPS, freeGb)
        }
        val el = (SystemClock.elapsedRealtime() - recStart) / 1000
        return "● REC %02d:%02d   %d frames   dropped %d   queue %d".format(el / 60, el % 60, w.count, w.dropped, w.pending())
    }

    private fun showPreview(depth: ByteArray, color: ByteArray, intr: Intrinsics, depthScale: Double, px: IntArray) {
        FrameWriter.rgbToArgb(color, px)
        val colorBmp = Bitmap.createBitmap(px, intr.width, intr.height, Bitmap.Config.ARGB_8888)
        val lo = PREVIEW_MIN_M * depthScale
        val span = (PREVIEW_MAX_M - PREVIEW_MIN_M) * depthScale
        for (p in px.indices) {
            val z = (depth[2 * p].toInt() and 0xFF) or ((depth[2 * p + 1].toInt() and 0xFF) shl 8)
            px[p] = if (z == 0) Color.BLACK else TURBO[(((z - lo) / span).coerceIn(0.0, 1.0) * 255).toInt()]
        }
        val depthBmp = Bitmap.createBitmap(px, intr.width, intr.height, Bitmap.Config.ARGB_8888)
        runOnUiThread {
            colorView.setImageBitmap(colorBmp)
            depthView.setImageBitmap(depthBmp)
        }
    }

    private fun updateButton() = runOnUiThread {
        recButton.text = if (wantRecording) "STOP" else "REC"
        recButton.setBackgroundColor(if (wantRecording) Color.RED else Color.DKGRAY)
        recButton.setTextColor(Color.WHITE)
    }

    private fun setStatus(s: String) = runOnUiThread { statusText.text = s }

    companion object {
        const val TAG = "D455Recorder"
        const val DEPTH_W = 848   // D455's best-quality depth mode
        const val DEPTH_H = 480
        const val COLOR_W = 640   // reconstruction works at depth resolution, so more colour is wasted
        const val COLOR_H = 480
        const val FPS = 30
        const val WARMUP_FRAMES = 30
        const val PREVIEW_EVERY = 3  // 10 fps preview leaves CPU for align + encoding
        const val PREVIEW_MIN_M = 0.3
        const val PREVIEW_MAX_M = 4.0

        /** Blue (near) to red (far), approximating OpenCV's COLORMAP_TURBO used by capture.py. */
        val TURBO = IntArray(256) { i ->
            val t = i / 255f
            val hsv = floatArrayOf(240f * (1f - t), 1f, 1f)
            Color.HSVToColor(hsv)
        }
    }
}
