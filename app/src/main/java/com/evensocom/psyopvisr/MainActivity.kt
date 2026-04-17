package com.evensocom.psyopvisr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.evensocom.psyopvisr.db.CulturalContextEngine
import com.evensocom.psyopvisr.inference.TacticalInferenceEngine
import com.evensocom.psyopvisr.service.EvenG2TacticalService
import com.evensocom.psyopvisr.ui.OverlayView
import com.evensocom.psyopvisr.ui.SpatialMapView
import com.evensocom.psyopvisr.vision.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Single activity host for the phone-side demo UI.
 *
 * Responsibilities:
 *  - Permission requests and service start.
 *  - Owns CameraX (Preview + ImageAnalysis bound to this Activity's lifecycle).
 *  - Owns [TacticalInferenceEngine] and [VisionModeController].
 *  - Observes [VisualizationBus] and [VisionModeController.modeFlow] to drive
 *    the live overlay and info panel.
 *
 * [EvenG2TacticalService] handles BLE and HUD rendering only.
 */
class MainActivity : AppCompatActivity() {

    // ──────────────────── Permissions ────────────────────

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (REQUIRED_PERMISSIONS.all { permissions[it] == true }) {
            onPermissionsGranted()
        }
    }

    private val enrollPhotoPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val append = pendingEnrollmentAppend
        pendingEnrollmentAppend = false
        uri?.let { enrollMeFromUri(it, append = append) }
    }

    // ──────────────────── Views ────────────────────

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var spatialMapView: SpatialMapView
    private lateinit var btnScanEndMode: Button
    private lateinit var btnEvenTranslation: Button
    private lateinit var btnEnrollMe: Button
    private lateinit var idleScreen: LinearLayout
    private lateinit var tvIdleStatus: TextView
    private lateinit var tvEnrollStatus: TextView
    private lateinit var tvModeChip: TextView
    private lateinit var infoPanel: LinearLayout
    private lateinit var modeColorDot: View
    private lateinit var tvPanelMode: TextView
    private lateinit var tvScanState: TextView
    private lateinit var tvDetections: TextView
    private lateinit var tvHudOutput: TextView

    // ──────────────────── Engine ownership ────────────────────

    private var inferenceEngine: TacticalInferenceEngine? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraAnalysis: ImageAnalysis? = null
    private var cameraPreview: Preview? = null
    private var pendingEnrollmentAppend: Boolean = false

    // ──────────────────── Mode colours (matches OverlayView) ────────────────────

    private val COLOR_FACE     = Color.parseColor("#00E676")
    private val COLOR_SYMBOL   = Color.parseColor("#FF9800")
    private val COLOR_CULTURAL = Color.parseColor("#CE93D8")
    private val COLOR_ROOM     = Color.parseColor("#29B6F6")

    // ──────────────────── Lifecycle ────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        if (allPermissionsGranted()) onPermissionsGranted() else permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun bindViews() {
        previewView   = findViewById(R.id.previewView)
        overlayView   = findViewById(R.id.overlayView)
        spatialMapView = findViewById(R.id.spatialMapView)
        btnScanEndMode = findViewById(R.id.btnScanEndMode)
        btnEvenTranslation = findViewById(R.id.btnEvenTranslation)
        btnEnrollMe = findViewById(R.id.btnEnrollMe)
        idleScreen    = findViewById(R.id.idleScreen)
        tvIdleStatus  = findViewById(R.id.tvIdleStatus)
        tvEnrollStatus = findViewById(R.id.tvEnrollStatus)
        tvModeChip    = findViewById(R.id.tvModeChip)
        infoPanel     = findViewById(R.id.infoPanel)
        modeColorDot  = findViewById(R.id.modeColorDot)
        tvPanelMode   = findViewById(R.id.tvPanelMode)
        tvScanState   = findViewById(R.id.tvScanState)
        tvDetections  = findViewById(R.id.tvDetections)
        tvHudOutput   = findViewById(R.id.tvHudOutput)
        updateEnrollStatusText()
    }

    private fun onPermissionsGranted() {
        startTacticalService()
        initEngineAndCamera()
        observeFlows()
    }

    override fun onDestroy() {
        inferenceEngine?.shutdown()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    // ──────────────────── Service + Engine setup ────────────────────

    private fun startTacticalService() {
        ContextCompat.startForegroundService(this, Intent(this, EvenG2TacticalService::class.java))
    }

    private fun initEngineAndCamera() {
        val culturalDb = CulturalContextEngine(this)
        lifecycleScope.launch(Dispatchers.IO) { culturalDb.seedIfNeeded() }

        // VisionModeController must exist before TacticalInferenceEngine init
        VisionModeController.instance
            ?: VisionModeController.initialize(lifecycleScope)

        inferenceEngine = TacticalInferenceEngine(this, culturalDb)

        inferenceEngine?.onUnbindCameraX = {
            unbindCameraAnalysis()
        }
        inferenceEngine?.onRebindCameraX = {
            rebindCameraAnalysis()
        }

        val roomEngine = com.evensocom.psyopvisr.vision.RoomAnalysisEngine(this)
        updateScanEndButton(roomEngine.getScanEndMode())
        btnScanEndMode.setOnClickListener {
            val newMode = if (roomEngine.getScanEndMode() == "auto") "tap" else "auto"
            roomEngine.setScanEndMode(newMode)
            VisionModeController.instance?.roomScanEndMode = newMode
            updateScanEndButton(newMode)
        }
        btnEvenTranslation.setOnClickListener {
            launchEvenTranslationApp()
        }
        btnEnrollMe.setOnClickListener {
            promptEnrollModeAndPickPhoto()
        }

        // Debug hooks:
        // adb shell am start -n ... --ez force_translation_mode true
        // adb shell am start -n ... --ez force_room_mode true
        when {
            intent?.getBooleanExtra("force_translation_mode", false) == true -> {
                VisionModeController.instance?.activateMode(VisionMode.TRANSLATION)
            }
            intent?.getBooleanExtra("force_room_mode", false) == true -> {
                VisionModeController.instance?.activateMode(VisionMode.ROOM_ANALYSIS)
            }
        }

        bindCamera()
    }

    // ──────────────────── Camera binding ────────────────────

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val engine = inferenceEngine ?: return@addListener

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            cameraPreview = preview

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, engine) }
            cameraAnalysis = analysis

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                android.util.Log.e("PSYOP-VISR", "CameraX bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindCameraAnalysis() {
        try {
            cameraProvider?.unbindAll()
            android.util.Log.i("MainActivity", "CameraX unbound for ARCore room scan")
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "unbindCameraAnalysis failed: $e")
        }
    }

    private fun rebindCameraAnalysis() {
        android.util.Log.i("MainActivity", "Rebinding CameraX after room scan")
        bindCamera()
    }

    private fun updateScanEndButton(mode: String) {
        btnScanEndMode.text = if (mode == "auto") "AUTO 30s" else "TAP TO END"
    }

    private fun launchEvenTranslationApp() {
        val evenIntent = packageManager.getLaunchIntentForPackage("com.even.sg")
            ?: Intent(Intent.ACTION_MAIN).apply {
                setClassName("com.even.sg", "com.even.sg.MainActivity")
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        val canResolve = evenIntent.resolveActivity(packageManager) != null
        if (!canResolve) {
            Toast.makeText(this, "Even Realities app launch activity not found", Toast.LENGTH_SHORT).show()
            return
        }

        // Release our stack before handing control back to the official app.
        inferenceEngine?.shutdown()
        EvenG2TacticalService.instance?.relinquishToCompanionAndStop()
        stopService(Intent(this, EvenG2TacticalService::class.java))

        try {
            evenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(evenIntent)
            finishAffinity()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to open Even app: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptEnrollModeAndPickPhoto() {
        val enrolledCount = FaceDetectionEngine.getEnrolledTemplateCount(this)
        if (enrolledCount <= 0) {
            pendingEnrollmentAppend = false
            enrollPhotoPicker.launch("image/*")
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.enroll_mode_title)
            .setMessage(
                getString(
                    R.string.enroll_mode_message,
                    enrolledCount
                )
            )
            .setPositiveButton(R.string.enroll_mode_append) { _, _ ->
                pendingEnrollmentAppend = true
                enrollPhotoPicker.launch("image/*")
            }
            .setNegativeButton(R.string.enroll_mode_replace) { _, _ ->
                pendingEnrollmentAppend = false
                enrollPhotoPicker.launch("image/*")
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun enrollMeFromUri(uri: Uri, append: Boolean) {
        tvEnrollStatus.text = getString(R.string.enroll_status_processing)
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = decodeBitmapFromUri(uri)
            val result = if (bitmap != null) {
                FaceDetectionEngine.enrollMeFromPhoto(
                    this@MainActivity,
                    bitmap,
                    append = append
                )
            } else {
                EnrollmentResult(
                    success = false,
                    message = getString(R.string.enroll_status_decode_failed)
                )
            }
            withContext(Dispatchers.Main) {
                updateEnrollStatusText()
                Toast.makeText(
                    this@MainActivity,
                    result.message,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updateEnrollStatusText() {
        val count = FaceDetectionEngine.getEnrolledTemplateCount(this)
        tvEnrollStatus.text = if (count > 0) {
            resources.getQuantityString(R.plurals.enroll_status_enrolled_count, count, count)
        } else {
            getString(R.string.enroll_status_not_enrolled)
        }
    }

    private fun decodeBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Failed to decode selected image: $e")
            null
        }
    }

    // ──────────────────── Flow observers ────────────────────

    private fun observeFlows() {
        // Mode changes → show/hide camera and info panel
        lifecycleScope.launch {
            VisionModeController.instance?.modeFlow?.collectLatest { mode ->
                applyMode(mode)
            }
        }

        // Detection frames → overlay
        lifecycleScope.launch {
            VisualizationBus.frame.collectLatest { frame ->
                overlayView.setFrame(frame)
                if (frame is VisualizationFrame.RoomSpatialFrame) {
                    spatialMapView.updateFrame(frame)
                } else if (frame == null) {
                    spatialMapView.updateFrame(null)
                }
                updateDetectionText(frame)
            }
        }

        // HUD mirror → bottom panel
        lifecycleScope.launch {
            VisualizationBus.hudContent.collectLatest { content ->
                tvHudOutput.text = content
            }
        }

        // BLE connection state → idle status text
        lifecycleScope.launch {
            // Wait for service to be running and bleManager initialized
            var flow = EvenG2TacticalService.instance?.connectionStateFlow
            while (flow == null) {
                kotlinx.coroutines.delay(300)
                flow = EvenG2TacticalService.instance?.connectionStateFlow
            }
            flow.collectLatest { connected ->
                tvIdleStatus.text = if (connected) "G2 CONNECTED" else "CONNECTING..."
            }
        }
    }

    // ──────────────────── UI state ────────────────────

    private fun applyMode(mode: VisionMode) {
        val isScanning = mode !in listOf(VisionMode.IDLE, VisionMode.MENU)
        val isRoomMode = mode == VisionMode.ROOM_ANALYSIS

        idleScreen.visibility  = if (isScanning) View.INVISIBLE else View.VISIBLE
        previewView.visibility = if (isScanning) View.VISIBLE   else View.INVISIBLE
        overlayView.visibility = if (isScanning) View.VISIBLE   else View.INVISIBLE
        tvModeChip.visibility  = if (isScanning) View.VISIBLE   else View.INVISIBLE
        infoPanel.visibility   = if (isScanning) View.VISIBLE   else View.INVISIBLE
        spatialMapView.visibility = if (isRoomMode) View.VISIBLE else View.GONE
        btnScanEndMode.visibility = if (isRoomMode) View.VISIBLE else View.GONE
        if (isRoomMode) {
            updateScanEndButton(VisionModeController.instance?.roomScanEndMode ?: "auto")
        }

        if (isScanning) {
            val (label, color) = modeStyle(mode)
            tvModeChip.text = label
            (tvModeChip.background as? GradientDrawable)?.setColor(color)
            tvModeChip.setTextColor(if (color == COLOR_FACE) Color.BLACK else Color.WHITE)
            tvPanelMode.text = label
            (modeColorDot.background as? GradientDrawable)?.setColor(color)
        }
    }

    private fun updateDetectionText(frame: VisualizationFrame?) {
        if (frame == null) { tvDetections.text = ""; tvScanState.text = ""; return }

        tvScanState.text = when (frame) {
            is VisualizationFrame.FaceFrame    -> "${frame.detections.size} face(s)"
            is VisualizationFrame.SymbolFrame  -> "${frame.detections.size} object(s)"
            is VisualizationFrame.CulturalFrame -> "${frame.detections.size} object(s)"
            is VisualizationFrame.RoomFrame    -> "${frame.detections.size} objects  ${frame.personCount} persons"
            is VisualizationFrame.RoomSpatialFrame -> "${frame.spatialObjects.size} objects mapped"
        }

        tvDetections.text = when (frame) {
            is VisualizationFrame.FaceFrame -> {
                frame.detections.joinToString("\n") { det ->
                    val id = if (det.identity == "UNKNOWN") det.personType.name else det.identity
                    "$id  ${(det.confidence * 100).toInt()}%"
                }.ifEmpty { "No faces detected" }
            }
            is VisualizationFrame.SymbolFrame -> {
                frame.detections.joinToString("\n") { det ->
                    val alert = if (det.alertLevel >= 2) "[!] " else ""
                    "$alert${det.label}  ${(det.confidence * 100).toInt()}%"
                }.ifEmpty { "No objects detected" }
            }
            is VisualizationFrame.CulturalFrame -> {
                frame.detections.joinToString("\n") { det ->
                    "${det.label}  ${(det.confidence * 100).toInt()}%\n  ${det.contextText.take(40)}"
                }.ifEmpty { "No objects detected" }
            }
            is VisualizationFrame.RoomFrame -> {
                val objects = frame.detections.take(4).joinToString("  ") { det ->
                    "${det.label.take(8)} ~%.1fm".format(det.distanceMeters)
                }
                "Exits: ${frame.exitSummary}\n$objects"
            }
            is VisualizationFrame.RoomSpatialFrame -> {
                val estimate = frame.roomEstimate
                buildString {
                    appendLine(estimate.sizeCategory.uppercase())
                    appendLine("Progress: ${(estimate.scanProgress * 100).toInt()}%")
                    appendLine(estimate.exitSummary)
                    if (estimate.personCount > 0) appendLine("Persons: ${estimate.personCount}")
                    append(frame.spatialObjects.take(3).joinToString("  ") { it.label.take(6) })
                }
            }
        }
    }

    private fun modeStyle(mode: VisionMode): Pair<String, Int> = when (mode) {
        VisionMode.FACE_SCAN       -> "FACE SCAN"    to COLOR_FACE
        VisionMode.SYMBOL_SCAN     -> "SYMBOL SCAN"  to COLOR_SYMBOL
        VisionMode.CULTURAL_CONTEXT -> "CULTURAL CTX" to COLOR_CULTURAL
        VisionMode.ROOM_ANALYSIS   -> "ROOM ANALYSIS" to COLOR_ROOM
        VisionMode.TRANSLATION     -> "TRANSLATION" to Color.WHITE
        else                       -> "SCANNING"     to Color.WHITE
    }

    // ──────────────────── Helpers ────────────────────

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
