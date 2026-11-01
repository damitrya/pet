package com.damitrya.mimio.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why a car event did not lead to a reaction. Each value names a check that exists in the code:
 *
 * - [GPS_MISSING]: no GPS data at all, or no usable fix since the AUTO profile started (StateMachine).
 * - [GPS_STALE]: the fix is older than [com.damitrya.mimio.data.GpsFixGate.MAX_AGE_MS], or the last usable speed
 *   is older than the state machine's GPS_STALE_MS.
 * - [LOCATION_INACCURATE]: horizontal accuracy worse than [com.damitrya.mimio.data.GpsFixGate.MAX_ACCURACY_M].
 * - [SPEED_UNAVAILABLE]: the fix carries no speed (`Location.hasSpeed()` false). The code does not check this —
 *   such a fix is accepted with speed 0 — so it is only a warning on GPS_FIX, never a rejection.
 * - [SPEED_BELOW_THRESHOLD]: the speed is under the check's minimum (braking, turns and leans: 8 km/h).
 * - [SENSOR_UNAVAILABLE]: the device has no such sensor, or registering its listener failed.
 * - [CALIBRATION_PENDING]: the braking detector has not learned the car's forward axis yet; a jolt waits for
 *   GPS to confirm the car slowed down.
 * - [ACCELERATION_BELOW_THRESHOLD]: the measured acceleration did not reach the detector's threshold.
 * - [DURATION_BELOW_THRESHOLD]: a turn exceeded the lateral threshold, but not for the required 300 ms.
 * - [COOLDOWN_ACTIVE]: the reaction played too recently (ALERT 3 s, turns 4 s).
 * - [STATE_BLOCKED]: the character's current state does not let the reaction start.
 * - [ANIMATION_NOT_CONFIGURED]: nothing reacts to the event, or no animation file exists for the state.
 * - [OVERLAY_HIDDEN]: the state changed, but the overlay is hidden, so nothing is visible.
 *
 * Wave 7 (car physics, see [com.damitrya.mimio.data.CarMotionTracker]):
 * - [PHONE_MOVING]: the phone is probably being moved relative to the car (handled, turned, taken out of the holder).
 * - [CALIBRATION_UNRELIABLE]: the car's forward axis is not known well enough for a longitudinal lean.
 * - [INERTIA_COOLDOWN]: a lean played less than 4 s ago.
 * - [EVENT_TOO_SHORT]: a manoeuvre crossed its threshold, but not for the required 300 ms.
 * - [SENSOR_DATA_INCONSISTENT]: accelerometer and GPS speed changes keep contradicting each other.
 * - [REACTIONS_DISABLED]: the user switched the leans and sharp-turn reactions off (settings → AUTO).
 */
enum class DiagnosticReason {
    GPS_MISSING,
    GPS_STALE,
    LOCATION_INACCURATE,
    SPEED_UNAVAILABLE,
    SPEED_BELOW_THRESHOLD,
    SENSOR_UNAVAILABLE,
    CALIBRATION_PENDING,
    ACCELERATION_BELOW_THRESHOLD,
    DURATION_BELOW_THRESHOLD,
    COOLDOWN_ACTIVE,
    STATE_BLOCKED,
    ANIMATION_NOT_CONFIGURED,
    OVERLAY_HIDDEN,
    PHONE_MOVING,
    CALIBRATION_UNRELIABLE,
    INERTIA_COOLDOWN,
    EVENT_TOO_SHORT,
    SENSOR_DATA_INCONSISTENT,
    REACTIONS_DISABLED
}

/** What became of a request for a car reaction. */
enum class RequestResult {
    STARTED,
    REJECTED,

    /** Blocked for now; the state machine tries again on the next GPS updates. */
    DEFERRED,

    /** A due reaction was dropped (the car moved again, or the retry window ran out). */
    CANCELLED
}

/** One sensor (or location) listener registration at the start of the car providers. */
data class SensorRegistration(val owner: String, val sensor: String, val available: Boolean, val registered: Boolean)

/** Typed diagnostic event; [format] gives the block written to the journal. */
data class DrivingDiagnosticsEvent(
    val type: Type,
    val wallTimeMs: Long,
    val fields: List<Pair<String, String>> = emptyList(),
    val accepted: Boolean? = null,
    val reason: DiagnosticReason? = null
) {

    enum class Type {
        SESSION_START,
        SESSION_RESUMED,
        SESSION_PAUSED,
        SESSION_END,
        PROFILE,
        SENSOR_REGISTRATION,
        GPS_FIX,
        GPS_LOST,
        SENSOR_STATS,
        CALIBRATION,
        MOVEMENT_START,
        MOVEMENT_STOP,
        TRIP_DEPARTED,
        TRIP_ARRIVED,
        BRAKE_CANDIDATE,
        TURN_CANDIDATE,
        ACCEL_CANDIDATE,
        INERTIA_CANDIDATE,
        PHONE_MOTION,
        INERTIA_START,
        INERTIA_END,
        ANIMATION_REQUEST,
        STATE_CHANGE,
        PLAYBACK,
        LIMIT_REACHED
    }

    /**
     * ```
     * [12:04:15] GPS_FIX
     * speed=54.2 km/h
     * accepted=false
     * reason=LOCATION_INACCURATE
     * ```
     * followed by an empty line.
     */
    fun format(timeFormat: SimpleDateFormat = newTimeFormat()): String {
        val sb = StringBuilder(64 + fields.size * 24)
        sb.append('[').append(timeFormat.format(Date(wallTimeMs))).append("] ").append(type.name).append('\n')
        for ((k, v) in fields) sb.append(k).append('=').append(v).append('\n')
        accepted?.let { sb.append("accepted=").append(it).append('\n') }
        reason?.let { sb.append("reason=").append(it.name).append('\n') }
        sb.append('\n')
        return sb.toString()
    }

    /** One line for the screen: "12:04:18 TURN_CANDIDATE direction=LEFT …". */
    fun shortText(timeFormat: SimpleDateFormat = newTimeFormat()): String {
        val head = fields.take(3).joinToString(" ") { "${it.first}=${it.second}" }
        return "${timeFormat.format(Date(wallTimeMs))} ${type.name}" + if (head.isEmpty()) "" else " $head"
    }

    companion object {
        fun newTimeFormat() = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}

/** Numbers in the journal always use a dot, whatever the phone's language. */
internal fun Float.f1(): String = String.format(Locale.US, "%.1f", this)
internal fun Float.f2(): String = String.format(Locale.US, "%.2f", this)
