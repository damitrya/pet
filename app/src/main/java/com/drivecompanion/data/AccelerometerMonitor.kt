package com.drivecompanion.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper

/**
 * Monitors the accelerometer for sharp braking events on axis X.
 * Applies a low-pass filter and detects negative-edge crossings of the configured threshold.
 * Cooldown and playback-guard logic is handled by [com.drivecompanion.state.StateMachine].
 */
class AccelerometerMonitor(context: Context) : SensorEventListener {

    interface Listener {
        fun onSharpBraking()
    }

    private var listener: Listener? = null
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())

    private var filteredX: Float = 0f
    private var wasBelow: Boolean = false
    private var threshold: Float = SettingsRepository.DEFAULT_ALERT_BRAKING_THRESHOLD

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun setThreshold(threshold: Float) {
        this.threshold = threshold
    }

    fun start() {
        filteredX = 0f
        wasBelow = false
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        accel?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return

        val x = event.values[0]
        // Low-pass filter to remove sensor noise
        filteredX = filteredX * 0.8f + x * 0.2f

        val isBelow = filteredX < threshold
        if (isBelow && !wasBelow) {
            // Rising edge: acceleration just crossed below threshold → sharp braking detected
            handler.post { listener?.onSharpBraking() }
        }
        wasBelow = isBelow
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
