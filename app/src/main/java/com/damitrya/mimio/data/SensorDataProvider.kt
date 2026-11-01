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
import com.damitrya.mimio.diagnostics.DrivingDiagnostics
import com.damitrya.mimio.diagnostics.SensorRegistration

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
    val accuracyM: Float = 0f,
    /** The fix carried a speed (`Location.hasSpeed()`); without one [speedKmh] averages in 0. */
    val hasSpeed: Boolean = true,
    /** Speed accuracy of the fix in m/s, NaN = not reported. Diagnostics only. */
    val speedAccuracyMps: Float = Float.NaN,
    /** Speed of the fix itself, before the moving average of [speedKmh]. Diagnostics only. */
    val rawSpeedKmh: Float = 0f,
    /** Location provider of the fix ("gps", "network"). Diagnostics only. */
    val provider: String = ""
)

enum class TurnDirection { LEFT, RIGHT, NONE }

/**
 * GPS and the motion sensors of the AUTO profile. Location fixes become [DrivingData]; the samples of the linear
 * accelerometer, the gyroscope and the gravity sensor go to [tracker] ([CarMotionTracker], wave 7), whose sharp turns
 * set [DrivingData.isTurning] and whose leans reach [Listener.onCarMotion]. The usable GPS speeds reach the tracker
 * from the service (through [GpsFixGate], like everything else).
 */
class SensorDataProvider(
    private val context: Context,
    /** The car physics; shared with [AccelerometerMonitor] (its braking detector uses the same car axis). */
    val tracker: CarMotionTracker = CarMotionTracker()
) : SensorEventListener, LocationListener, CarMotionTracker.Listener {

    interface Listener {
        fun onDrivingDataChanged(data: DrivingData)

        /** An ordinary or intensive manoeuvre the character may lean with (see [CarMotionTracker]). */
        fun onCarMotion(event: CarMotionEvent) {}
    }

    private var listener: Listener? = null
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private var currentSpeed: Float = 0f
    private var hasGps: Boolean = false
    private var fixSeq: Long = 0L
    private var fixElapsedMs: Long = 0L
    private var fixAccuracyM: Float = 0f
    private var fixHasSpeed: Boolean = true
    private var fixSpeedAccuracyMps: Float = Float.NaN
    private var fixRawSpeedKmh: Float = 0f
    private var fixProvider: String = ""

    /** What [start] registered (shown by the driving diagnostics). */
    var registrations: List<SensorRegistration> = emptyList()
        private set

    /** Sharp turn of the car (from [tracker]). */
    private var isTurning: Boolean = false
    private var turnDirection: TurnDirection = TurnDirection.NONE

    /** Sensor timestamps on the elapsedRealtime clock of the GPS speeds. */
    private val sensorClock = SensorClock()

    // Smoothing
    private val speedHistory = FloatArray(3)
    private var speedHistoryIndex = 0

    init {
        tracker.listener = this
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun start() {
        tracker.reset()
        sensorClock.reset()
        isTurning = false
        turnDirection = TurnDirection.NONE
        startLocationUpdates()
        startSensorUpdates()
    }

    fun stop() {
        locationManager.removeUpdates(this)
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            report(SensorRegistration(OWNER, "LOCATION_PERMISSION", available = false, registered = false))
            return
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,  // 1 second interval
                0f,     // no minimum distance
                this,
                Looper.getMainLooper()
            )
            report(SensorRegistration(OWNER, "LOCATION_GPS", available = true, registered = true))
        } catch (e: Exception) {
            report(SensorRegistration(OWNER, "LOCATION_GPS", available = false, registered = false))
            // GPS provider not available, try network
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    0f,
                    this,
                    Looper.getMainLooper()
                )
                report(SensorRegistration(OWNER, "LOCATION_NETWORK", available = true, registered = true))
            } catch (_: Exception) {
                // No location provider available
                report(SensorRegistration(OWNER, "LOCATION_NETWORK", available = false, registered = false))
            }
        }
    }

    private fun startSensorUpdates() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

        val accelOk = accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        val gyroOk = gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        val gravityOk = gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        report(SensorRegistration(OWNER, "LINEAR_ACCELERATION", accelerometer != null, accelOk == true))
        report(SensorRegistration(OWNER, "GYROSCOPE", gyroscope != null, gyroOk == true))
        report(SensorRegistration(OWNER, "GRAVITY", gravitySensor != null, gravityOk == true))
    }

    private fun report(registration: SensorRegistration) {
        registrations = registrations.filter { it.sensor != registration.sensor } + registration
        DrivingDiagnostics.sink?.onSensorRegistration(registration)
    }

    // LocationListener
    override fun onLocationChanged(location: Location) {
        hasGps = true
        fixSeq++
        fixElapsedMs = location.elapsedRealtimeNanos / 1_000_000L
        fixAccuracyM = if (location.hasAccuracy()) location.accuracy else 0f
        val rawSpeed = location.speed * 3.6f // m/s to km/h
        fixHasSpeed = location.hasSpeed()
        fixSpeedAccuracyMps = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else Float.NaN
        fixRawSpeedKmh = rawSpeed
        fixProvider = location.provider ?: ""

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
            tracker.onGpsLost()
            notifyListener()
        }
    }

    @Deprecated("Deprecated in API")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    // SensorEventListener: light work only (a few multiplications per sample), no allocation, no I/O.
    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        val at = sensorClock.toElapsedMs(event.timestamp, SystemClock.elapsedRealtime())
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> tracker.onLinearAcceleration(v[0], v[1], v[2], at)
            Sensor.TYPE_GYROSCOPE -> tracker.onGyro(v[0], v[1], v[2], at)
            Sensor.TYPE_GRAVITY -> tracker.onGravity(v[0], v[1], v[2], at)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // CarMotionTracker.Listener (called from onSensorChanged, main thread)
    override fun onCarMotion(event: CarMotionEvent) {
        handler.post { listener?.onCarMotion(event) }
    }

    override fun onSharpTurnChanged(turning: Boolean, direction: TurnDirection) {
        isTurning = turning
        turnDirection = direction
        notifyListener()
    }

    private fun notifyListener() {
        val data = DrivingData(
            speedKmh = currentSpeed,
            isTurning = isTurning,
            turnDirection = turnDirection,
            isRapidAcceleration = tracker.isStrongAcceleration,
            hasGpsFix = hasGps,
            fixSeq = fixSeq,
            fixAgeMs = if (fixSeq > 0) (SystemClock.elapsedRealtime() - fixElapsedMs).coerceAtLeast(0L) else 0L,
            accuracyM = fixAccuracyM,
            hasSpeed = fixHasSpeed,
            speedAccuracyMps = fixSpeedAccuracyMps,
            rawSpeedKmh = fixRawSpeedKmh,
            provider = fixProvider
        )
        handler.post { listener?.onDrivingDataChanged(data) }
    }

    private companion object {
        const val OWNER = "SensorDataProvider"
    }
}

/**
 * Maps sensor event timestamps (nanoseconds, a clock the device chooses) onto elapsedRealtime milliseconds, the clock
 * of the GPS speeds. The offset is the smallest delivery delay seen (events are never delivered before they happen);
 * a jump of more than [RESYNC_MS] (a device whose clock differs) starts over. Durations between samples keep the
 * sensor's own precision even when events arrive in bursts.
 */
internal class SensorClock {
    private var offset = 0L
    private var synced = false

    fun reset() {
        synced = false
    }

    fun toElapsedMs(eventNs: Long, deliveredMs: Long): Long {
        val eventMs = eventNs / 1_000_000L
        val delay = deliveredMs - eventMs
        if (!synced || delay < offset || delay - offset > RESYNC_MS) {
            offset = delay
            synced = true
        }
        return eventMs + offset
    }

    private companion object {
        const val RESYNC_MS = 5_000L
    }
}
