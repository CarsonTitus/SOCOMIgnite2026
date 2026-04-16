package com.evensocom.psyopvisr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.evensocom.psyopvisr.service.EvenG2TacticalService

/**
 * Entry point: requests runtime permissions and starts [EvenG2TacticalService].
 *
 * Camera binding, CameraX lifecycle, and TacticalInferenceEngine are all owned
 * exclusively by EvenG2TacticalService. MainActivity does NOT create any duplicate
 * camera session or inference engine.
 */
class MainActivity : AppCompatActivity() {

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
        val allGranted = REQUIRED_PERMISSIONS.all { permissions[it] == true }
        if (allGranted) {
            startTacticalService()
        } else {
            Toast.makeText(
                baseContext,
                "Required permissions not granted — service cannot start.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (allPermissionsGranted()) {
            startTacticalService()
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private fun startTacticalService() {
        val intent = Intent(this, EvenG2TacticalService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
}
