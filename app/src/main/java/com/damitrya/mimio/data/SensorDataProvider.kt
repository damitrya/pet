package com.damitrya.mimio.data

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
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.sqrt

data class DrivingData(
    val speedKmh: Float = 0f,
    val isBraking: Boolean = false,
    val isTurning: Boolean = false,
    val turnDirection: TurnDirection = TurnDirection.NONE,
    val isRapidAcceleration: Boolean = false,
    val hasGpsFix: Boolean = false,
    /**
     * Number of the location fix [speedKmh] comes from; it grows with every new fix. Sensor events
     * (braking, turns) repeat the last one, so only a larger number means fresh GPS data.
     */
    val fixSeq: Long = 0L,
    /** How old that fix was when this data was sent (the system may deliver cached locations). */
    val fixAgeMs: Long = 0L,
    /** Horizontal accuracy of the fix in metres, 0 = unknown. */
    val accuracyM: Float = 0f
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
    private var fixSeq: Long = 0L
    private var fixElapsedMs: Long = 0L
    private var fixAccuracyM: Float = 0f

    // Accelerometer data for braking/acceleration detection
    private var lastAccelX: Float = 0f
    private var lastAccelY: Float = 0f
    private var lastAccelZ: Float = 0f
    private var isBraking: Boolean = false
    private var isRapidAccel: Boolean = false

    // Gyroscope data for turn detection
    private var isTurning: Boolean = false
    private var turnDirection: TurnDirection = TurnDirection.NONE

    // Gravity direction (phone coordinates), used to get the yaw rate whatever the phone mount
    private val gravity = FloatArray(3)
    private var hasGravity: Boolean = false
    private var yawRate: Float = 0f            // rad/s, filtered; positive = turning left
    private var turnCandidateSince: Long = 0L  // sensor timestamp (ns) when the threshold was first exceeded

    // Thresholds
    private var brakingThreshold: Float = 4.0f  // m/s² longitudinal deceleration
    private var accelerationThreshold: Float = 3.5f
    private var turnThreshold: Float = 3.5f  // m/s² lateral acceleration (speed × yaw rate)

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
        val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    // LocationListener
    override fun onLocationChanged(location: Location) {
        hasGps = true
        fixSeq++
        fixElapsedMs = location.elapsedRealtimeNanos / 1_000_000L
        fixAccuracyM = if (location.hasAccuracy()) location.accuracy else 0f
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
            Sensor.TYPE_GRAVITY -> {
                event.values.copyInto(gravity, endIndex = 3)
                hasGravity = true
            }
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

    /**
     * A turn is sharp when the lateral acceleration (GPS speed × yaw rate) stays above
     * [turnThreshold] for [TURN_SUSTAIN_NS]. The yaw rate is the rotation around the gravity
     * vector, so it does not depend on how the phone is mounted (flat, upright, landscape).
     */
    private fun handleGyroscope(event: SensorEvent) {
        val rawYaw = if (hasGravity) {
            val g = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
            if (g > 1f) {
                (event.values[0] * gravity[0] + event.values[1] * gravity[1] + event.values[2] * gravity[2]) / g
            } else {
                event.values[2]
            }
        } else {
            event.values[2]  // no gravity sensor: assume the phone lies flat
        }
        yawRate = yawRate * 0.8f + rawYaw * 0.2f

        val prevTurning = isTurning
        val prevDirection = turnDirection

        val lateral = abs(yawRate) * currentSpeed / 3.6f
        if (lateral > turnThreshold) {
            if (turnCandidateSince == 0L) turnCandidateSince = event.timestamp
            if (event.timestamp - turnCandidateSince >= TURN_SUSTAIN_NS) {
                isTurning = true
                turnDirection = if (yawRate > 0) TurnDirection.LEFT else TurnDirection.RIGHT
            }
        } else {
            turnCandidateSince = 0L
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
            hasGpsFix = hasGps,
            fixSeq = fixSeq,
            fixAgeMs = if (fixSeq > 0) (SystemClock.elapsedRealtime() - fixElapsedMs).coerceAtLeast(0L) else 0L,
            accuracyM = fixAccuracyM
        )
        handler.post { listener?.onDrivingDataChanged(data) }
    }

    companion object {
        /** Lateral acceleration must stay above the threshold this long (bumps and jolts are shorter). */
        private const val TURN_SUSTAIN_NS = 300_000_000L
    }
}
