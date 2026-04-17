package com.evensocom.psyopvisr.vision

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import java.nio.ByteBuffer
import kotlin.math.atan2

/**
 * Manages an ARCore session for room-analysis on the ultrawide camera.
 *
 * Responsibilities:
 *  1. Select the ultrawide camera via Camera2 (shortest focal length).
 *  2. Configure the ARCore session with depth if supported.
 *  3. Acquire frames and convert YUV_420_888 camera images to [Bitmap].
 *  4. Expose pose (X, Z) and heading derived from the ARCore rotation quaternion.
 */
class ArCoreRoomAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "ArCoreRoomAnalyzer"

        /**
         * Ultrawide heuristic: cameras with a physical focal length shorter than this
         * threshold (mm) are treated as ultrawide.
         */
        private const val ULTRAWIDE_FOCAL_LENGTH_THRESHOLD_MM = 2.0f
    }

    // ──────────────────── Public state ────────────────────

    var isRunning: Boolean = false
        private set

    // ──────────────────── Internal state ────────────────────

    private var session: Session? = null
    private var depthSupported: Boolean = false

    /** Camera2 ID of the selected ultrawide (or best-available) back camera. */
    private var selectedCameraId: String? = null

    // ──────────────────── Lifecycle ────────────────────

    /**
     * Initialises the ARCore session. Configures depth if supported by the device.
     * Selects the ultrawide camera by inspecting Camera2 focal-length characteristics.
     * If ARCore is unavailable the method returns with [isRunning] = false.
     */
    fun start() {
        if (isRunning) return

        // 1. Pick the ultrawide (or fallback back-facing) camera.
        selectedCameraId = selectUltrawideCamera()
        Log.i(TAG, "Selected camera ID: $selectedCameraId")

        // 2. Check ARCore availability.
        try {
            val availability = ArCoreApk.getInstance().checkAvailability(context)
            if (!availability.isSupported) {
                Log.w(TAG, "ARCore not supported on this device: $availability")
                isRunning = false
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "ARCore availability check failed: $e")
            isRunning = false
            return
        }

        // 3. Create and configure the ARCore session.
        try {
            val arSession = Session(context)

            val config = Config(arSession)
            config.depthMode = Config.DepthMode.AUTOMATIC
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE

            // Focus mode: fixed for room scanning (avoids refocus stutters)
            config.focusMode = Config.FocusMode.FIXED

            depthSupported = arSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            if (!depthSupported) {
                Log.d(TAG, "Depth not supported — falling back to DISABLED depth mode")
                config.depthMode = Config.DepthMode.DISABLED
            }

            arSession.configure(config)
            session = arSession
            arSession.resume()
            isRunning = true
            Log.i(TAG, "ARCore session started (depth=$depthSupported)")
        } catch (e: UnavailableArcoreNotInstalledException) {
            Log.w(TAG, "ARCore not installed: $e")
            isRunning = false
        } catch (e: UnavailableApkTooOldException) {
            Log.w(TAG, "ARCore APK too old: $e")
            isRunning = false
        } catch (e: UnavailableSdkTooOldException) {
            Log.w(TAG, "ARCore SDK too old: $e")
            isRunning = false
        } catch (e: UnavailableDeviceNotCompatibleException) {
            Log.w(TAG, "Device not compatible with ARCore: $e")
            isRunning = false
        } catch (e: CameraNotAvailableException) {
            Log.w(TAG, "Camera not available for ARCore: $e")
            isRunning = false
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error starting ARCore session: $e")
            isRunning = false
        }
    }

    /** Closes the ARCore session and releases all resources. */
    fun stop() {
        try {
            session?.pause()
            session?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping ARCore session: $e")
        } finally {
            session = null
            isRunning = false
            Log.i(TAG, "ARCore session stopped")
        }
    }

    // ──────────────────── Frame acquisition ────────────────────

    /**
     * Updates the ARCore session and returns an [AnalysisFrame] containing a [Bitmap],
     * optional center-pixel depth, pose translation (X, Z), and heading.
     *
     * Returns null if the session is not running or if the update fails.
     */
    fun acquireFrame(): AnalysisFrame? {
        val arSession = session ?: return null
        if (!isRunning) return null

        return try {
            val frame = arSession.update()

            // ── Camera image → Bitmap ──────────────────────────────────────────────
            val bitmap: Bitmap = frame.acquireCameraImage().use { image ->
                yuvImageToBitmap(image)
            }

            // ── Center-pixel depth ────────────────────────────────────────────────
            val depthMeters: Float? = if (depthSupported) {
                try {
                    frame.acquireDepthImage16Bits().use { depthImage ->
                        val centerX = depthImage.width / 2
                        val centerY = depthImage.height / 2
                        val plane = depthImage.planes[0]
                        val byteIndex = centerY * plane.rowStride + centerX * plane.pixelStride
                        // Depth is stored as unsigned 16-bit millimetres in little-endian order
                        val depthMm = (plane.buffer.get(byteIndex).toInt() and 0xFF) or
                                ((plane.buffer.get(byteIndex + 1).toInt() and 0xFF) shl 8)
                        if (depthMm == 0) null else depthMm / 1000f
                    }
                } catch (e: NotYetAvailableException) {
                    null // Depth not ready yet — normal for first few frames
                } catch (e: Exception) {
                    Log.d(TAG, "Depth image unavailable: $e")
                    null
                }
            } else null

            // ── Pose ──────────────────────────────────────────────────────────────
            val camera = frame.camera
            val translation = camera.pose.translation          // [x, y, z]
            val poseX = translation[0]
            val poseZ = translation[2]

            val quaternion = FloatArray(4)
            camera.pose.getRotationQuaternion(quaternion, 0)   // [qx, qy, qz, qw]
            val headingDeg = quaternionToHeadingDeg(quaternion)

            AnalysisFrame(
                bitmap = bitmap,
                depthMeters = depthMeters,
                poseX = poseX,
                poseZ = poseZ,
                headingDeg = headingDeg
            )
        } catch (e: CameraNotAvailableException) {
            Log.w(TAG, "Camera not available during acquireFrame: $e")
            null
        } catch (e: Exception) {
            Log.w(TAG, "acquireFrame failed: $e")
            null
        }
    }

    /** Returns true if the ARCore session supports depth. */
    fun isDepthSupported(): Boolean = depthSupported

    // ──────────────────── Camera2 ultrawide selection ────────────────────

    /**
     * Iterates all Camera2 camera IDs and returns the ID of the camera with the shortest
     * physical focal length (ultrawide heuristic). Falls back to the first back-facing camera
     * if no camera clearly qualifies as ultrawide (focal length < [ULTRAWIDE_FOCAL_LENGTH_THRESHOLD_MM]).
     */
    private fun selectUltrawideCamera(): String? {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var bestId: String? = null
            var bestFocalLength = Float.MAX_VALUE
            var fallbackBackId: String? = null

            for (cameraId in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                // Use the first back camera as a fallback
                if (fallbackBackId == null) fallbackBackId = cameraId

                val focalLengths =
                    chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        ?: continue
                val shortestFl = focalLengths.minOrNull() ?: continue

                Log.d(TAG, "Camera $cameraId: shortest focal length = ${shortestFl}mm")

                if (shortestFl < bestFocalLength) {
                    bestFocalLength = shortestFl
                    bestId = cameraId
                }
            }

            // Only return the ultrawide candidate if its focal length is below the threshold
            val ultrawide = if (bestFocalLength < ULTRAWIDE_FOCAL_LENGTH_THRESHOLD_MM) bestId else null
            val selected = ultrawide ?: fallbackBackId
            Log.i(
                TAG,
                "Camera selection: ultrawide=$ultrawide fallback=$fallbackBackId chosen=$selected"
            )
            selected
        } catch (e: Exception) {
            Log.w(TAG, "Camera2 ultrawide selection failed: $e")
            null
        }
    }

    // ──────────────────── YUV → Bitmap conversion ────────────────────

    /**
     * Converts an ARCore YUV_420_888 [android.media.Image] to an RGB [Bitmap].
     *
     * The conversion reads the Y, U, and V planes and writes packed ARGB_8888 pixels.
     * Chroma planes are subsampled 2× in both dimensions (standard 4:2:0).
     */
    private fun yuvImageToBitmap(image: android.media.Image): Bitmap {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf: ByteBuffer = yPlane.buffer
        val uBuf: ByteBuffer = uPlane.buffer
        val vBuf: ByteBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val pixels = IntArray(width * height)

        for (row in 0 until height) {
            for (col in 0 until width) {
                val yIndex = row * yRowStride + col
                val uvRow = row / 2
                val uvCol = col / 2
                val uvIndex = uvRow * uvRowStride + uvCol * uvPixelStride

                val y = (yBuf.get(yIndex).toInt() and 0xFF)
                val u = (uBuf.get(uvIndex).toInt() and 0xFF) - 128
                val v = (vBuf.get(uvIndex).toInt() and 0xFF) - 128

                val r = (y + 1.370705f * v).toInt().coerceIn(0, 255)
                val g = (y - 0.698001f * v - 0.337633f * u).toInt().coerceIn(0, 255)
                val b = (y + 1.732446f * u).toInt().coerceIn(0, 255)

                pixels[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    // ──────────────────── Quaternion → heading ────────────────────

    /**
     * Converts an ARCore rotation quaternion [qx, qy, qz, qw] into a heading in degrees.
     *
     * Formula: heading = atan2(2*(qw*qy - qx*qz), 1 - 2*(qy*qy + qz*qz))
     * Result is in [-180, 180]; callers can normalise to [0, 360) if needed.
     */
    private fun quaternionToHeadingDeg(q: FloatArray): Float {
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
        val headingRad = atan2(
            (2.0 * (qw * qy - qx * qz)).toFloat(),
            (1.0 - 2.0 * (qy * qy + qz * qz)).toFloat()
        )
        return Math.toDegrees(headingRad.toDouble()).toFloat()
    }

    // ──────────────────── Frame data class ────────────────────

    /**
     * Snapshot of one ARCore frame, ready for EfficientDet inference and spatial accumulation.
     *
     * @param bitmap       RGB bitmap from the (ultrawide) camera.
     * @param depthMeters  Center-pixel depth in meters, or null if depth is unavailable.
     * @param poseX        ARCore camera world X coordinate (meters).
     * @param poseZ        ARCore camera world Z coordinate (meters).
     * @param headingDeg   Camera heading derived from pose quaternion (degrees, 0 = +Z axis).
     */
    data class AnalysisFrame(
        val bitmap: Bitmap,
        val depthMeters: Float?,
        val poseX: Float,
        val poseZ: Float,
        val headingDeg: Float
    )
}
