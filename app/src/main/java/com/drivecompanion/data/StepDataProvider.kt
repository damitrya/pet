package com.drivecompanion.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

data class StepData(
    val stepsToday: Int = 0,
    val stepsPerMinute: Float = 0f,
    val goalReached: Boolean = false,
    val isWalking: Boolean = false,
    val isRunning: Boolean = false
)

class StepDataProvider(private val context: Context, private val settings: SettingsRepository) : SensorEventListener {

    interface Listener {
        fun onStepDataChanged(data: StepData)
    }

    private var listener: Listener? = null
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())

    private var currentData = StepData()
    private var initialStepCount: Int = -1
    private var stepsToday: Int = 0
    private var goalCelebrated = false

    // Step rate tracking
    private val stepTimestamps = mutableListOf<Long>()
    private val stepRateWindowMs = 60_000L // 1 minute window

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun getCurrentData(): StepData = currentData

    fun start() {
        if (!hasPermission()) return

        val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

        stepCounter?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        stepDetector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
    }

    fun resetDailySteps() {
        initialStepCount = -1
        stepsToday = 0
        goalCelebrated = false
        stepTimestamps.clear()
        updateData()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val totalSteps = event.values[0].toInt()
                if (initialStepCount < 0) {
                    initialStepCount = totalSteps
                }
                stepsToday = totalSteps - initialStepCount
                updateData()
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                val now = System.currentTimeMillis()
                stepTimestamps.add(now)
                // Remove timestamps older than the window
                stepTimestamps.removeAll { now - it > stepRateWindowMs }
                updateData()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateData() {
        val now = System.currentTimeMillis()
        stepTimestamps.removeAll { now - it > stepRateWindowMs }

        val stepsPerMinute = if (stepTimestamps.size >= 2) {
            val windowMs = now - stepTimestamps.first()
            if (windowMs > 0) (stepTimestamps.size * 60_000f) / windowMs else 0f
        } else 0f

        val goal = settings.stepGoal
        val goalReached = stepsToday >= goal && !goalCelebrated
        if (goalReached) {
            goalCelebrated = true
        }

        currentData = StepData(
            stepsToday = stepsToday,
            stepsPerMinute = stepsPerMinute,
            goalReached = goalReached,
            isWalking = stepsPerMinute > 30f && stepsPerMinute <= 140f,
            isRunning = stepsPerMinute > 140f
        )
        handler.post { listener?.onStepDataChanged(currentData) }

        // Reset goal celebration after animation
        if (goalReached) {
            handler.postDelayed({
                currentData = currentData.copy(goalReached = false)
                listener?.onStepDataChanged(currentData)
            }, 5000L)
        }
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
