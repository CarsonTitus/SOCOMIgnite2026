package com.evensocom.psyopvisr.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

class CompassEngine(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)

    private val _heading = MutableStateFlow(0)
    val heading: StateFlow<Int> = _heading

    fun start() {
        val sensorGravity = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val sensorMagnetic = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        sensorManager.registerListener(this, sensorGravity, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, sensorMagnetic, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, gravity, 0, event.values.size)
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, geomagnetic, 0, event.values.size)
        }

        val r = FloatArray(9)
        val i = FloatArray(9)
        if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(r, orientation)
            val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
            val normalizedAzimuth = (azimuth + 360) % 360
            
            // Only update if heading changed significantly to save BLE bandwidth
            val newHeading = normalizedAzimuth.roundToInt()
            if (kotlin.math.abs(_heading.value - newHeading) >= 2) {
                _heading.value = newHeading
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun getHeadingString(): String {
        val h = _heading.value
        val dir = when {
            h >= 337 || h < 22 -> "N"
            h in 22..67 -> "NE"
            h in 68..112 -> "E"
            h in 113..157 -> "SE"
            h in 158..202 -> "S"
            h in 203..247 -> "SW"
            h in 248..292 -> "W"
            else -> "NW"
        }
        return "$h° $dir"
    }
}
