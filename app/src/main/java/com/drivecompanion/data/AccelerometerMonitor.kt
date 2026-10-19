package com.drivecompanion.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Feeds linear acceleration, gravity and GPS speed into [BrakeDetector] and reports sharp braking.
 * Works for any phone mount; see [BrakeDetector] for how the car's forward axis is learned.
 * Cooldown and playback-guard logic is handled by [com.drivecompanion.state.StateMachine].
 */
class AccelerometerMonitor(context: Context) : SensorEventListener {

    interface Listener {
        fun onSharpBraking()
    }

    private var listener: Listener? = null
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private val detector = BrakeDetector()

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun setThreshold(threshold: Float) {
        detector.threshold = threshold
    }

    fun start() {
        detector.reset()
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
    }

    /** GPS speed from [SensorDataProvider]; teaches the forward axis and confirms braking. */
    fun updateSpeed(speedKmh: Float) {
        if (detector.onSpeed(speedKmh, SystemClock.elapsedRealtime())) notifyBraking()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> detector.onGravity(v[0], v[1], v[2])
            Sensor.TYPE_LINEAR_ACCELERATION ->
                if (detector.onLinearAcceleration(v[0], v[1], v[2], SystemClock.elapsedRealtime())) notifyBraking()
        }
    }

    private fun notifyBraking() {
        handler.post { listener?.onSharpBraking() }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
