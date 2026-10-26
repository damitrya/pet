package com.damitrya.mimio.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.damitrya.mimio.diagnostics.DrivingDiagnostics
import com.damitrya.mimio.diagnostics.SensorRegistration

/**
 * Feeds linear acceleration, gravity and GPS speed into [BrakeDetector] and reports sharp braking.
 * Works for any phone mount; see [BrakeDetector] for how the car's forward axis is learned (in the app: the
 * [VehicleFrameEstimator] of the car physics).
 * Cooldown and playback-guard logic is handled by [com.damitrya.mimio.state.StateMachine].
 */
class AccelerometerMonitor(
    context: Context,
    /** The car axis shared with [SensorDataProvider]'s [CarMotionTracker] (null: the detector learns its own). */
    frame: VehicleFrameEstimator? = null
) : SensorEventListener {

    interface Listener {
        fun onSharpBraking()
    }

    private var listener: Listener? = null
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private val detector = BrakeDetector(frame = frame)

    /** What [start] registered (shown by the driving diagnostics). */
    var registrations: List<SensorRegistration> = emptyList()
        private set

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun setThreshold(threshold: Float) {
        detector.threshold = threshold
    }

    fun start() {
        detector.reset()
        val linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val linearOk = linear?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        val gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val gravityOk = gravity?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        registrations = listOf(
            SensorRegistration(OWNER, "LINEAR_ACCELERATION", linear != null, linearOk == true),
            SensorRegistration(OWNER, "GRAVITY", gravity != null, gravityOk == true)
        )
        registrations.forEach { DrivingDiagnostics.sink?.onSensorRegistration(it) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
    }

    /** GPS speed from [SensorDataProvider]; teaches the forward axis and confirms braking. */
    fun updateSpeed(speedKmh: Float) {
        val confirmed = detector.onSpeed(speedKmh, SystemClock.elapsedRealtime())
        DrivingDiagnostics.sink?.onBrakeSpeed(detector, confirmed)
        if (confirmed) notifyBraking()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> detector.onGravity(v[0], v[1], v[2], SystemClock.elapsedRealtime())
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val braking = detector.onLinearAcceleration(v[0], v[1], v[2], SystemClock.elapsedRealtime())
                DrivingDiagnostics.sink?.onBrakeSample(detector, braking)
                if (braking) notifyBraking()
            }
        }
    }

    private fun notifyBraking() {
        handler.post { listener?.onSharpBraking() }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        const val OWNER = "AccelerometerMonitor"
    }
}
