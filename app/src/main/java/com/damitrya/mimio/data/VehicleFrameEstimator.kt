package com.damitrya.mimio.data

import kotlin.math.abs
import kotlin.math.sqrt

/** How far the car's forward axis is known (see [VehicleFrameEstimator]). */
enum class CalibrationState {
    /** Nothing learned yet. */
    UNCALIBRATED,

    /** Some GPS lessons, not enough to trust the axis. */
    LEARNING,

    /** The forward axis is known: longitudinal leans may play. */
    READY,

    /** Reactions paused (the phone is being moved, or the data contradict each other); the axis is kept. */
    SUSPENDED
}

/** Why the calibration changed state (diagnostics). */
enum class CalibrationReason {
    /** First GPS lesson. */
    LEARNING_STARTED,

    /** Enough consistent GPS lessons. */
    LEARNED,

    /** The phone is probably being moved (handled, taken out of the holder). */
    PHONE_MOVING,

    /** The phone is still again in the orientation the axis was learned in: the axis is kept. */
    PHONE_STABLE_SAME_ORIENTATION,

    /** The phone is still again, but tilted differently: the axis is learned anew. */
    ORIENTATION_CHANGED,

    /** Several GPS lessons in a row agree on a different direction: the holder was swivelled. */
    HOLDER_ROTATED,

    /** GPS speed changes and the accelerometer disagree several times in a row. */
    SENSOR_DATA_INCONSISTENT,

    /** A consistent GPS lesson after a contradiction. */
    CONSISTENT_AGAIN,

    /** Profile switch / service start. */
    RESET
}

/**
 * The car's axes in phone coordinates, whatever the mount (upright, landscape, sideways in a holder, flat).
 *
 * 1. Up is the gravity direction ([onGravity]); linear acceleration (gravity already excluded by the sensor) is
 *    reduced to its horizontal part ([horizontal]).
 * 2. Forward is learned from GPS ([onSpeed]): over each interval between two usable fixes where the speed
 *    clearly changed while driving straight, the mean horizontal acceleration points forward (speeding up) or
 *    backward (slowing down). The phone's top is never assumed to point forward.
 * 3. [quality] (0–1) says how well the lessons agree; READY needs [MIN_LESSONS] lessons and [READY_QUALITY].
 *
 * READY is robust: one contradicting lesson (braking in a bend, a pothole) is only counted. Three contradictions
 * in a row that agree among themselves mean the holder was swivelled around the vertical (gravity cannot see that):
 * the axis is replaced by theirs. Contradictions that do not agree, or accelerations whose size does not fit the
 * GPS speed change, suspend the reactions (SENSOR_DATA_INCONSISTENT) until a consistent lesson comes.
 *
 * Handling the phone ([suspendForPhoneMotion] / [onPhoneStable]) only suspends: once the phone is still in the
 * orientation it had when the axis was learned (within [SAME_ORIENTATION_DEG]) the axis is used again, otherwise it
 * is learned anew. A stop, GPS loss or missing lessons never erase the axis.
 *
 * Pure logic, main thread; times in milliseconds of one monotonic clock.
 */
class VehicleFrameEstimator {

    /** Told every state change. */
    var onTransition: ((from: CalibrationState, to: CalibrationState, reason: CalibrationReason) -> Unit)? = null

    var state: CalibrationState = CalibrationState.UNCALIBRATED
        private set

    var lastReason: CalibrationReason = CalibrationReason.RESET
        private set

    /** Agreement of the GPS lessons with the axis, 0–1. */
    var quality: Float = 0f
        private set

    /** Lessons behind the current axis. */
    var lessons: Int = 0
        private set

    /** Why the reactions are suspended (meaningful in SUSPENDED). */
    var suspendReason: CalibrationReason? = null
        private set

    private val up = FloatArray(3)
    var hasGravity = false
        private set

    /** Learned forward axis (unit, phone coordinates) and the up direction it was learned with. */
    private val forward = FloatArray(3)
    private val axisUp = FloatArray(3)
    private var hasAxis = false

    /** Learning: weighted sum of lesson directions and of their weights. */
    private val lessonSum = FloatArray(3)
    private var lessonWeight = 0f

    /** State to return to after a suspension. */
    private var beforeSuspend = CalibrationState.UNCALIBRATED
    private var phoneMoving = false

    /** Contradicting lessons in a row (directions), and accelerations that did not fit the GPS. */
    private val contradictions = ArrayList<FloatArray>()
    private var mismatches = 0

    /** Horizontal acceleration and |yaw rate| summed over the current GPS interval. */
    private val sumH = FloatArray(3)
    private var countH = 0
    private var sumYaw = 0f
    private var countYaw = 0

    private var lastSpeedMps = -1f
    private var lastSpeedAt = 0L

    /** Result of the last GPS interval (diagnostics): its acceleration from GPS, NaN when none. */
    var lastGpsAccel: Float = Float.NaN
        private set

    /** Forgets everything (profile switch to AUTO). */
    fun reset() {
        val from = state
        hasAxis = false
        lessons = 0
        quality = 0f
        lessonSum.fill(0f)
        lessonWeight = 0f
        contradictions.clear()
        mismatches = 0
        phoneMoving = false
        suspendReason = null
        clearInterval()
        lastSpeedMps = -1f
        lastSpeedAt = 0L
        state = CalibrationState.UNCALIBRATED
        lastReason = CalibrationReason.RESET
        if (from != state) onTransition?.invoke(from, state, CalibrationReason.RESET)
    }

    /** GPS gone for a while: the next fix starts a new interval (the axis is kept). */
    fun onGpsGap() {
        lastSpeedAt = 0L
        clearInterval()
    }

    fun onGravity(x: Float, y: Float, z: Float) {
        val n = sqrt(x * x + y * y + z * z)
        if (n < 1f) return
        up[0] = x / n; up[1] = y / n; up[2] = z / n
        hasGravity = true
    }

    /** Unit up vector (phone coordinates); valid once [hasGravity]. */
    fun up(): FloatArray = up

    /** Horizontal part of a linear-acceleration sample (without gravity data: the phone is assumed flat). */
    fun horizontal(x: Float, y: Float, z: Float, out: FloatArray) {
        if (!hasGravity) {
            out[0] = x; out[1] = y; out[2] = 0f
            return
        }
        val d = x * up[0] + y * up[1] + z * up[2]
        out[0] = x - d * up[0]; out[1] = y - d * up[1]; out[2] = z - d * up[2]
    }

    /** One horizontal acceleration sample and the yaw rate at that moment (learning material). */
    fun onSample(h: FloatArray, yawRate: Float) {
        for (i in 0..2) sumH[i] += h[i]
        countH++
        sumYaw += abs(yawRate)
        countYaw++
    }

    /** One yaw-rate sample (without an acceleration sample). */
    fun onYaw(yawRate: Float) {
        sumYaw += abs(yawRate)
        countYaw++
    }

    /**
     * Forward axis projected onto the current horizontal plane, or null when there is none or the phone is tilted
     * so far from where it was learned that the projection says little.
     */
    fun axis(out: FloatArray): Boolean {
        if (!hasAxis) return false
        if (!hasGravity) {
            forward.copyInto(out)
            return true
        }
        val d = forward[0] * up[0] + forward[1] * up[1] + forward[2] * up[2]
        for (i in 0..2) out[i] = forward[i] - d * up[i]
        val n = norm(out)
        if (n < MIN_PROJECTION) return false
        for (i in 0..2) out[i] /= n
        return true
    }

    /** True when longitudinal values may drive reactions. */
    val isReady: Boolean get() = state == CalibrationState.READY

    /** Speed (m/s) of the last usable fix, -1 = none. */
    val speedMps: Float get() = lastSpeedMps

    /**
     * A new usable GPS speed. The interval since the previous one becomes a lesson when the speed clearly changed
     * while driving straight and the phone stood still in its holder.
     */
    fun onSpeed(kmh: Float, now: Long) {
        val v = kmh / 3.6f
        lastGpsAccel = Float.NaN
        if (lastSpeedAt != 0L && countH >= MIN_INTERVAL_SAMPLES) {
            val dt = (now - lastSpeedAt) / 1000f
            if (dt in MIN_INTERVAL_S..MAX_INTERVAL_S) {
                val a = (v - lastSpeedMps) / dt
                lastGpsAccel = a
                if (!phoneMoving) lesson(a, maxOf(v, lastSpeedMps))
            }
        }
        clearInterval()
        lastSpeedMps = v
        lastSpeedAt = now
    }

    private fun lesson(gpsAccel: Float, speedMps: Float) {
        if (abs(gpsAccel) < LESSON_MIN_GPS_ACCEL || speedMps < LESSON_MIN_SPEED_MPS) return
        if (countYaw > 0 && sumYaw / countYaw > LESSON_MAX_YAW) return      // a bend: centripetal acceleration mixes in
        val mean = FloatArray(3) { sumH[it] / countH }
        val n = norm(mean)
        if (n < LESSON_MIN_ACCEL) {
            mismatch()
            return
        }
        val ratio = n / abs(gpsAccel)
        if (ratio < MIN_RATIO || ratio > MAX_RATIO) {
            mismatch()
            return
        }
        val sign = if (gpsAccel > 0) 1f else -1f
        val dir = FloatArray(3) { sign * mean[it] / n }
        val weight = abs(gpsAccel)

        if (!hasAxis || state == CalibrationState.LEARNING || state == CalibrationState.UNCALIBRATED) {
            learn(dir, weight)
            return
        }
        val axis = FloatArray(3)
        if (!axis(axis)) {
            dropAxis(CalibrationReason.ORIENTATION_CHANGED)
            learn(dir, weight)
            return
        }
        val agree = dot(dir, axis)
        if (agree >= AGREE_COS) {
            for (i in 0..2) forward[i] = forward[i] * (1 - MERGE) + dir[i] * MERGE
            normalize(forward)
            rememberUp()
            lessons++
            quality = quality * (1 - QUALITY_EMA) + agree * QUALITY_EMA
            contradictions.clear()
            mismatches = 0
            if (state == CalibrationState.SUSPENDED && suspendReason == CalibrationReason.SENSOR_DATA_INCONSISTENT) {
                resume(CalibrationReason.CONSISTENT_AGAIN)
            }
            return
        }
        quality = quality * (1 - QUALITY_EMA) + maxOf(agree, 0f) * QUALITY_EMA
        contradictions += dir
        if (contradictions.size < MAX_CONTRADICTIONS) return
        val mean3 = FloatArray(3)
        for (c in contradictions) for (i in 0..2) mean3[i] += c[i]
        normalize(mean3)
        if (contradictions.all { dot(it, mean3) >= SELF_AGREE_COS }) {
            // They agree with each other: the holder was swivelled. Their direction is the new axis.
            val lessonsFrom = contradictions.toList()
            dropAxis(CalibrationReason.HOLDER_ROTATED)
            for (c in lessonsFrom) learn(c, 1f)
        } else {
            contradictions.clear()
            suspend(CalibrationReason.SENSOR_DATA_INCONSISTENT)
        }
    }

    /** The accelerometer and the GPS speed change did not fit together. */
    private fun mismatch() {
        if (state != CalibrationState.READY) return
        if (++mismatches >= MAX_MISMATCHES) {
            mismatches = 0
            suspend(CalibrationReason.SENSOR_DATA_INCONSISTENT)
        }
    }

    private fun learn(dir: FloatArray, weight: Float) {
        for (i in 0..2) lessonSum[i] += dir[i] * weight
        lessonWeight += weight
        lessons++
        val n = norm(lessonSum)
        quality = if (lessonWeight > 0f) n / lessonWeight else 0f
        if (n > 1e-3f) for (i in 0..2) forward[i] = lessonSum[i] / n
        hasAxis = true
        rememberUp()
        when {
            lessons >= MIN_LESSONS && quality >= READY_QUALITY -> {
                contradictions.clear()
                mismatches = 0
                if (phoneMoving) beforeSuspend = CalibrationState.READY else move(CalibrationState.READY, CalibrationReason.LEARNED)
            }
            lessons >= MIN_LESSONS * 3 -> {
                // The lessons keep disagreeing: start over rather than average noise forever.
                lessonSum.fill(0f)
                lessonWeight = 0f
                lessons = 0
                hasAxis = false
                if (!phoneMoving) move(CalibrationState.LEARNING, CalibrationReason.SENSOR_DATA_INCONSISTENT)
            }
            !phoneMoving && state != CalibrationState.LEARNING -> move(CalibrationState.LEARNING, CalibrationReason.LEARNING_STARTED)
        }
    }

    /** The phone is probably being moved: pause the reactions, keep the axis. */
    fun suspendForPhoneMotion() {
        phoneMoving = true
        clearInterval()
        suspend(CalibrationReason.PHONE_MOVING)
    }

    /**
     * The phone is still again. Same orientation as when the axis was learned: the axis is used again; otherwise
     * it is learned anew. [turnedAroundVertical]: the gyroscope saw the phone turn around the vertical while it was
     * handled (gravity cannot see that), so the old axis cannot be trusted even if the tilt is the same.
     */
    fun onPhoneStable(turnedAroundVertical: Boolean = false) {
        if (!phoneMoving) return
        phoneMoving = false
        clearInterval()
        lastSpeedAt = 0L          // the interval spanning the handling is no lesson
        if (hasAxis && hasGravity && !turnedAroundVertical && dot(up, axisUp) >= SAME_ORIENTATION_COS) {
            resume(CalibrationReason.PHONE_STABLE_SAME_ORIENTATION)
        } else if (hasAxis) {
            dropAxis(CalibrationReason.ORIENTATION_CHANGED)
        } else {
            resume(CalibrationReason.PHONE_STABLE_SAME_ORIENTATION)
        }
    }

    val isPhoneMoving: Boolean get() = phoneMoving

    private fun suspend(reason: CalibrationReason) {
        suspendReason = reason
        if (state == CalibrationState.SUSPENDED) {
            lastReason = reason
            return
        }
        beforeSuspend = state
        move(CalibrationState.SUSPENDED, reason)
    }

    private fun resume(reason: CalibrationReason) {
        if (state != CalibrationState.SUSPENDED) return
        if (phoneMoving) return
        suspendReason = null
        move(beforeSuspend, reason)
    }

    private fun dropAxis(reason: CalibrationReason) {
        hasAxis = false
        lessons = 0
        quality = 0f
        lessonSum.fill(0f)
        lessonWeight = 0f
        contradictions.clear()
        mismatches = 0
        suspendReason = null
        if (phoneMoving) {
            beforeSuspend = CalibrationState.UNCALIBRATED
            lastReason = reason
        } else {
            move(CalibrationState.UNCALIBRATED, reason)
        }
    }

    private fun move(to: CalibrationState, reason: CalibrationReason) {
        lastReason = reason
        val from = state
        if (from == to) return
        state = to
        onTransition?.invoke(from, to, reason)
    }

    private fun rememberUp() {
        if (hasGravity) up.copyInto(axisUp)
    }

    private fun clearInterval() {
        sumH.fill(0f)
        countH = 0
        sumYaw = 0f
        countYaw = 0
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norm(v: FloatArray) = sqrt(dot(v, v))
    private fun normalize(v: FloatArray) {
        val n = norm(v)
        if (n > 1e-6f) for (i in 0..2) v[i] /= n
    }

    companion object {
        /** Consistent lessons needed before READY, and how well they must agree. */
        const val MIN_LESSONS = 3
        const val READY_QUALITY = 0.8f

        /** A lesson: the speed changed at least this much (m/s²) above this speed, driving straight. */
        private const val LESSON_MIN_GPS_ACCEL = 0.6f
        private const val LESSON_MIN_SPEED_MPS = 5f / 3.6f
        private const val LESSON_MAX_YAW = 0.12f            // rad/s, mean over the interval

        /** Mean horizontal acceleration below this says nothing about the direction. */
        private const val LESSON_MIN_ACCEL = 0.35f

        /** Accelerometer / GPS size ratio that still fits (GPS speed lags and is smoothed). */
        private const val MIN_RATIO = 0.3f
        private const val MAX_RATIO = 3.5f

        /** GPS intervals and samples that make a lesson. */
        private const val MIN_INTERVAL_S = 0.5f
        private const val MAX_INTERVAL_S = 3f
        private const val MIN_INTERVAL_SAMPLES = 5

        /** A lesson within 60° of the axis agrees and refines it a little. */
        private const val AGREE_COS = 0.5f
        private const val MERGE = 0.15f
        private const val QUALITY_EMA = 0.2f

        /** Contradictions in a row that replace (agreeing among themselves) or suspend the axis. */
        private const val MAX_CONTRADICTIONS = 3
        private const val SELF_AGREE_COS = 0.8f
        private const val MAX_MISMATCHES = 3

        /** The phone still within this angle of the learned orientation keeps the axis. */
        const val SAME_ORIENTATION_DEG = 15.0
        private val SAME_ORIENTATION_COS = kotlin.math.cos(Math.toRadians(SAME_ORIENTATION_DEG)).toFloat()

        /** The axis projected on the horizontal plane must keep this much of its length (tilt < ~45°). */
        private const val MIN_PROJECTION = 0.7f
    }
}
