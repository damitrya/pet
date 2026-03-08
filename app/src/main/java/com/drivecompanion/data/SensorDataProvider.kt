package com.drivecompanion.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.sqrt

data class DrivingData(
    val speedKmh: Float = 0f,
    val isBraking: Boolean = false,
    val isTurning: Boolean = false,
    val turnDirection: TurnDirection = TurnDirection.NONE,
    val isRapidAcceleration: Boolean = false,
    val hasGpsFix: Boolean = false
)

enum class TurnDirection { LEFT, RIGHT, NONE }

class SensorDataProvider(private val context: Context) : SensorEventListener, LocationListener {

    interface Listener {
        fun onDrivingDataChanged(data: DrivingData)
    }

    private var listener: Listener? = null
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private var currentSpeed: Float = 0f
    private var previousSpeed: Float = 0f
    private var hasGps: Boolean = false

    // Accelerometer data for braking/acceleration detection
    private var lastAccelX: Float = 0f
    private var lastAccelY: Float = 0f
    private var lastAccelZ: Float = 0f
    private var isBraking: Boolean = false
    private var isRapidAccel: Boolean = false

    // Gyroscope data for turn detection
    private var isTurning: Boolean = false
    private var turnDirection: TurnDirection = TurnDirection.NONE

    // Thresholds
    private var brakingThreshold: Float = 4.0f  // m/s² longitudinal deceleration
    private var accelerationThreshold: Float = 3.5f
    private var turnThreshold: Float = 1.5f  // rad/s

    // Smoothing
    private val speedHistory = FloatArray(3)
    private var speedHistoryIndex = 0

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun start() {
        startLocationUpdates()
        startSensorUpdates()
    }

    fun stop() {
        locationManager.removeUpdates(this)
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
    }

    fun updateThresholds(braking: Float, acceleration: Float, turn: Float) {
        brakingThreshold = braking
        accelerationThreshold = acceleration
        turnThreshold = turn
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,  // 1 second interval
                0f,     // no minimum distance
                this,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            // GPS provider not available, try network
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    0f,
                    this,
                    Looper.getMainLooper()
                )
            } catch (_: Exception) {
                // No location provider available
            }
        }
    }

    private fun startSensorUpdates() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    // LocationListener
    override fun onLocationChanged(location: Location) {
        hasGps = true
        previousSpeed = currentSpeed
        val rawSpeed = location.speed * 3.6f // m/s to km/h

        // Simple moving average
        speedHistory[speedHistoryIndex % speedHistory.size] = rawSpeed
        speedHistoryIndex++
        val count = minOf(speedHistoryIndex, speedHistory.size)
        currentSpeed = speedHistory.take(count).average().toFloat()

        notifyListener()
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            hasGps = false
            currentSpeed = 0f
            notifyListener()
        }
    }

    @Deprecated("Deprecated in API")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    // SensorEventListener
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> handleAccelerometer(event)
            Sensor.TYPE_GYROSCOPE -> handleGyroscope(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun handleAccelerometer(event: SensorEvent) {
        val x = event.values[0]  // lateral
        val y = event.values[1]  // longitudinal (forward/backward)
        val z = event.values[2]  // vertical

        // Low-pass filter
        lastAccelX = lastAccelX * 0.8f + x * 0.2f
        lastAccelY = lastAccelY * 0.8f + y * 0.2f
        lastAccelZ = lastAccelZ * 0.8f + z * 0.2f

        val prevBraking = isBraking
        val prevAccel = isRapidAccel

        // Braking: strong negative longitudinal acceleration
        isBraking = lastAccelY > brakingThreshold
        // Rapid acceleration: strong positive longitudinal acceleration
        isRapidAccel = lastAccelY < -accelerationThreshold

        if (prevBraking != isBraking || prevAccel != isRapidAccel) {
            notifyListener()
        }
    }

    private fun handleGyroscope(event: SensorEvent) {
        val z = event.values[2]  // yaw rate (rotation around vertical axis)

        val prevTurning = isTurning
        val prevDirection = turnDirection

        if (abs(z) > turnThreshold) {
            isTurning = true
            turnDirection = if (z > 0) TurnDirection.LEFT else TurnDirection.RIGHT
        } else {
            isTurning = false
            turnDirection = TurnDirection.NONE
        }

        if (prevTurning != isTurning || prevDirection != turnDirection) {
            notifyListener()
        }
    }

    private fun notifyListener() {
        val data = DrivingData(
            speedKmh = currentSpeed,
            isBraking = isBraking,
            isTurning = isTurning,
            turnDirection = turnDirection,
            isRapidAcceleration = isRapidAccel,
            hasGpsFix = hasGps
        )
        handler.post { listener?.onDrivingDataChanged(data) }
    }
}
