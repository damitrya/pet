package com.damitrya.mimio.data

import java.util.Locale
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
    RESET,

    /**
     * Wave 8: the phone is still again with the same tilt, but the gyroscope saw it (or the car, which it cannot tell
     * apart) turn around the vertical while it was handled. The axis is kept, the reactions stay paused until a GPS
     * lesson confirms it ([AXIS_CONFIRMED]) or shows the holder was swivelled ([HOLDER_ROTATED]).
     */
    AXIS_UNCONFIRMED,

    /** Wave 8: the first GPS lesson after [AXIS_UNCONFIRMED] agrees with the kept axis. */
    AXIS_CONFIRMED
}

/** What the calibration knew when it changed (diagnostics: the conditions of the transition, not only its name). */
data class CalibrationTransition(
    val from: CalibrationState,
    val to: CalibrationState,
    val reason: CalibrationReason,
    val quality: Float,
    val lessons: Int,
    /** How long the calibration had been in [from] (ms), -1 when unknown. */
    val timeInStateMs: Long,
    /** Angle (°) between the phone's tilt now and the one the axis was learned with; NaN without an axis. */
    val orientationChangeDeg: Float,
    /** The concrete condition (counts, ratios, the rotation while handled…); empty when the reason says it all. */
    val detail: String
)

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
 * Wave 8 (the drive of 7 November: 23 × READY → SUSPENDED SENSOR_DATA_INCONSISTENT, the axis often lost):
 * - "straight" also means little centripetal acceleration for the speed ([LESSON_MAX_LATERAL_SHARE]): 0.12 rad/s
 *   at 90 km/h is a 3 m/s² highway bend, and such an interval taught a direction tens of degrees off;
 * - the GPS speed lags the accelerometer by up to a fix: an interval whose own acceleration does not fit is
 *   compared with the previous interval too before it counts as a mismatch;
 * - mismatches count only in a row, within [MISMATCH_WINDOW_MS] (before: three in the whole drive were enough);
 * - handling the phone while there is no axis changes no state (it used to log
 *   UNCALIBRATED → SUSPENDED → UNCALIBRATED "PHONE_STABLE_SAME_ORIENTATION" each time);
 * - a rotation around the vertical seen while the phone was handled no longer erases the axis (the gyroscope sees
 *   the car's own turns too): the axis is kept but unconfirmed ([CalibrationReason.AXIS_UNCONFIRMED]) until the
 *   next GPS lesson decides; a real tilt change (> [SAME_ORIENTATION_DEG]) still relearns it at once;
 * - data found inconsistent before the phone was handled stay inconsistent after it.
 *
 * Handling the phone ([suspendForPhoneMotion] / [onPhoneStable]) only suspends: once the phone is still in the
 * orientation it had when the axis was learned (within [SAME_ORIENTATION_DEG]) the axis is used again, otherwise it
 * is learned anew. A stop, GPS loss or missing lessons never erase the axis.
 *
 * Pure logic, main thread; times in milliseconds of one monotonic clock.
 */
class VehicleFrameEstimator {

    /**
     * Told every state change, and a change of the suspension's reason while SUSPENDED (from == to); the conditions
     * are in [lastTransition].
     */
    var onTransition: ((from: CalibrationState, to: CalibrationState, reason: CalibrationReason) -> Unit)? = null

    var state: CalibrationState = CalibrationState.UNCALIBRATED
        private set

    var lastReason: CalibrationReason = CalibrationReason.RESET
        private set

    /** The last transition with its conditions (diagnostics). */
    var lastTransition: CalibrationTransition? = null
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

    /** The data were inconsistent when the phone was picked up: they stay so until a consistent lesson. */
    private var inconsistent = false

    /** The axis is kept after a rotation around the vertical while handled: the next lesson must confirm it. */
    private var unverified = false

    /** Contradicting lessons in a row (directions), and accelerations that did not fit the GPS. */
    private val contradictions = ArrayList<FloatArray>()
    private var mismatches = 0
    private var lastMismatchAt = 0L
    private var lastMismatchRatio = Float.NaN

    /** Horizontal acceleration and |yaw rate| summed over the current GPS interval. */
    private val sumH = FloatArray(3)
    private var countH = 0
    private var sumYaw = 0f
    private var countYaw = 0

    /** Mean horizontal acceleration and |yaw rate| of the previous GPS interval (the GPS speed lags). */
    private val prevMean = FloatArray(3)
    private var prevYaw = 0f
    private var hasPrev = false

    private var lastSpeedMps = -1f
    private var lastSpeedAt = 0L

    /** Latest time seen, and since when the calibration is in its state. */
    private var nowMs = 0L
    private var stateSince = 0L

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
        lastMismatchAt = 0L
        phoneMoving = false
        inconsistent = false
        unverified = false
        suspendReason = null
        clearInterval()
        hasPrev = false
        lastSpeedMps = -1f
        lastSpeedAt = 0L
        state = CalibrationState.UNCALIBRATED
        lastReason = CalibrationReason.RESET
        if (from != state) notify(from, CalibrationReason.RESET, "")
        stateSince = nowMs
    }

    /** GPS gone for a while: the next fix starts a new interval (the axis is kept). */
    fun onGpsGap() {
        lastSpeedAt = 0L
        clearInterval()
        hasPrev = false
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

    /** Angle (°) between the tilt now and the one the axis was learned with; NaN without an axis or gravity. */
    fun orientationChangeDeg(): Float {
        if (!hasAxis || !hasGravity) return Float.NaN
        val cos = dot(up, axisUp).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(cos).toDouble()).toFloat()
    }

    /**
     * A new usable GPS speed. The interval since the previous one becomes a lesson when the speed clearly changed
     * while driving straight and the phone stood still in its holder.
     */
    fun onSpeed(kmh: Float, now: Long) {
        nowMs = maxOf(nowMs, now)
        val v = kmh / 3.6f
        lastGpsAccel = Float.NaN
        val interval = lastSpeedAt != 0L && countH >= MIN_INTERVAL_SAMPLES &&
            ((now - lastSpeedAt) / 1000f) in MIN_INTERVAL_S..MAX_INTERVAL_S
        if (interval) {
            val a = (v - lastSpeedMps) / ((now - lastSpeedAt) / 1000f)
            lastGpsAccel = a
            if (!phoneMoving) lesson(a, maxOf(v, lastSpeedMps), now)
            for (i in 0..2) prevMean[i] = sumH[i] / countH
            prevYaw = if (countYaw > 0) sumYaw / countYaw else 0f
            hasPrev = !phoneMoving
        } else {
            hasPrev = false
        }
        clearInterval()
        lastSpeedMps = v
        lastSpeedAt = now
    }

    private fun lesson(gpsAccel: Float, speedMps: Float, now: Long) {
        if (abs(gpsAccel) < LESSON_MIN_GPS_ACCEL || speedMps < LESSON_MIN_SPEED_MPS) return
        val yaw = if (countYaw > 0) sumYaw / countYaw else 0f
        if (isBend(yaw, speedMps, gpsAccel)) return      // a bend: centripetal acceleration mixes in
        val cur = FloatArray(3) { sumH[it] / countH }
        // The GPS speed lags: when this interval's acceleration does not fit, the previous one may (the end of a
        // braking: the car already rolls, the GPS still slows down).
        val mean = when {
            fits(cur, gpsAccel) -> cur
            hasPrev && !isBend(prevYaw, speedMps, gpsAccel) && fits(prevMean, gpsAccel) -> prevMean.copyOf()
            else -> {
                lastMismatchRatio = norm(cur) / abs(gpsAccel)
                mismatch(now)
                return
            }
        }
        mismatches = 0                                     // the sizes fit: the data are consistent
        val n = norm(mean)
        val sign = if (gpsAccel > 0) 1f else -1f
        val dir = FloatArray(3) { sign * mean[it] / n }
        val weight = abs(gpsAccel)

        if (!hasAxis || state == CalibrationState.LEARNING || state == CalibrationState.UNCALIBRATED) {
            if (unverified && hasAxis) {
                unverified = false
                val axis = FloatArray(3)
                if (axis(axis) && dot(dir, axis) < AGREE_COS) {
                    // Turned in the holder while it was handled: start over from this lesson.
                    dropAxis(CalibrationReason.HOLDER_ROTATED, "lesson ${angleDeg(dir, axis)}° off the kept axis")
                }
            }
            learn(dir, weight)
            return
        }
        val axis = FloatArray(3)
        if (!axis(axis)) {
            dropAxis(CalibrationReason.ORIENTATION_CHANGED, "axis no longer horizontal")
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
            if (state == CalibrationState.SUSPENDED && !phoneMoving) {
                when {
                    unverified -> {
                        unverified = false
                        resume(CalibrationReason.AXIS_CONFIRMED, "lesson ${angleDeg(dir, axis)}° from the kept axis")
                    }
                    suspendReason == CalibrationReason.SENSOR_DATA_INCONSISTENT -> {
                        inconsistent = false
                        resume(CalibrationReason.CONSISTENT_AGAIN, "lesson ${angleDeg(dir, axis)}° from the axis")
                    }
                }
            }
            return
        }
        if (unverified) {
            // The kept axis is contradicted by the first lesson after the rotation: the holder was turned.
            unverified = false
            dropAxis(CalibrationReason.HOLDER_ROTATED, "lesson ${angleDeg(dir, axis)}° off the kept axis")
            learn(dir, weight)
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
            dropAxis(CalibrationReason.HOLDER_ROTATED, "$MAX_CONTRADICTIONS lessons agree ${angleDeg(mean3, axis)}° off the axis")
            for (c in lessonsFrom) learn(c, 1f)
        } else {
            val spread = contradictions.maxOf { angleDeg(it, mean3) }
            contradictions.clear()
            suspend(CalibrationReason.SENSOR_DATA_INCONSISTENT, "$MAX_CONTRADICTIONS contradicting lessons, up to ${spread}° from their mean")
        }
    }

    /** Too much turning for a lesson: the mean |yaw rate|, or the centripetal acceleration it gives at this speed. */
    private fun isBend(yaw: Float, speedMps: Float, gpsAccel: Float) =
        yaw > LESSON_MAX_YAW || yaw * speedMps > LESSON_MAX_LATERAL_SHARE * abs(gpsAccel)

    /** The accelerometer's mean is big enough and its size fits the GPS speed change. */
    private fun fits(mean: FloatArray, gpsAccel: Float): Boolean {
        val n = norm(mean)
        if (n < LESSON_MIN_ACCEL) return false
        val ratio = n / abs(gpsAccel)
        return ratio in MIN_RATIO..MAX_RATIO
    }

    /** The accelerometer and the GPS speed change did not fit together (neither this interval nor the one before). */
    private fun mismatch(now: Long) {
        if (state != CalibrationState.READY) return
        if (lastMismatchAt != 0L && now - lastMismatchAt > MISMATCH_WINDOW_MS) mismatches = 0
        lastMismatchAt = now
        if (++mismatches >= MAX_MISMATCHES) {
            mismatches = 0
            suspend(
                CalibrationReason.SENSOR_DATA_INCONSISTENT,
                "$MAX_MISMATCHES GPS intervals in a row did not fit the accelerometer (last ratio ${lastMismatchRatio.fmt()})"
            )
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
                if (!phoneMoving) move(CalibrationState.LEARNING, CalibrationReason.SENSOR_DATA_INCONSISTENT, "quality ${quality.fmt()} after ${MIN_LESSONS * 3} lessons")
            }
            !phoneMoving && state != CalibrationState.LEARNING -> move(CalibrationState.LEARNING, CalibrationReason.LEARNING_STARTED)
        }
    }

    /**
     * The phone is probably being moved: pause the reactions, keep the axis. Without an axis there is nothing to
     * pause (the lessons wait for the phone anyway): the state stays as it is.
     */
    fun suspendForPhoneMotion(now: Long = nowMs, detail: String = "") {
        nowMs = maxOf(nowMs, now)
        phoneMoving = true
        clearInterval()
        hasPrev = false
        if (hasAxis) suspend(CalibrationReason.PHONE_MOVING, detail)
    }

    /**
     * The phone is still again. Same orientation as when the axis was learned: the axis is used again; a tilt
     * changed by more than [SAME_ORIENTATION_DEG]: it is learned anew. [turnedAroundVertical]: the gyroscope saw a
     * rotation around the vertical while the phone was handled — the phone turned in the holder, or just the car
     * turning meanwhile, which the gyroscope cannot tell apart; the axis is kept but must be confirmed by the next GPS
     * lesson before the reactions come back.
     */
    fun onPhoneStable(turnedAroundVertical: Boolean = false, now: Long = nowMs, detail: String = "") {
        nowMs = maxOf(nowMs, now)
        if (!phoneMoving) return
        phoneMoving = false
        clearInterval()
        hasPrev = false
        lastSpeedAt = 0L          // the interval spanning the handling is no lesson
        if (state != CalibrationState.SUSPENDED) {
            // Nothing was paused (no axis then, or it was learned meanwhile). A swivel still needs confirming.
            if (hasAxis && turnedAroundVertical) unverified = true
            return
        }
        val change = orientationChangeDeg()
        when {
            !hasAxis -> resume(CalibrationReason.PHONE_STABLE_SAME_ORIENTATION, detail)
            !hasGravity || change.isNaN() || change > SAME_ORIENTATION_DEG ->
                dropAxis(CalibrationReason.ORIENTATION_CHANGED, join("tilt changed by ${change.fmt0()}°", detail))
            turnedAroundVertical -> {
                unverified = true
                if (beforeSuspend == CalibrationState.READY) {
                    suspendReason = CalibrationReason.AXIS_UNCONFIRMED
                    lastReason = CalibrationReason.AXIS_UNCONFIRMED
                    notify(state, CalibrationReason.AXIS_UNCONFIRMED, join("tilt ${change.fmt0()}°", detail))
                } else {
                    resume(CalibrationReason.AXIS_UNCONFIRMED, join("tilt ${change.fmt0()}°", detail))
                }
            }
            inconsistent -> {
                suspendReason = CalibrationReason.SENSOR_DATA_INCONSISTENT
                lastReason = CalibrationReason.SENSOR_DATA_INCONSISTENT
                notify(state, CalibrationReason.SENSOR_DATA_INCONSISTENT, join("still inconsistent; tilt ${change.fmt0()}°", detail))
            }
            else -> resume(CalibrationReason.PHONE_STABLE_SAME_ORIENTATION, join("tilt ${change.fmt0()}°", detail))
        }
    }

    val isPhoneMoving: Boolean get() = phoneMoving

    /** The axis is kept after a rotation while handled and waits for a GPS lesson to confirm it. */
    val isAxisUnconfirmed: Boolean get() = unverified

    private fun suspend(reason: CalibrationReason, detail: String) {
        if (reason == CalibrationReason.SENSOR_DATA_INCONSISTENT) inconsistent = true
        suspendReason = reason
        if (state == CalibrationState.SUSPENDED) {
            lastReason = reason
            return
        }
        beforeSuspend = state
        move(CalibrationState.SUSPENDED, reason, detail)
    }

    private fun resume(reason: CalibrationReason, detail: String = "") {
        if (state != CalibrationState.SUSPENDED) return
        if (phoneMoving) return
        suspendReason = null
        move(beforeSuspend, reason, detail)
    }

    private fun dropAxis(reason: CalibrationReason, detail: String = "") {
        hasAxis = false
        lessons = 0
        quality = 0f
        lessonSum.fill(0f)
        lessonWeight = 0f
        contradictions.clear()
        mismatches = 0
        inconsistent = false
        unverified = false
        suspendReason = null
        if (phoneMoving) {
            beforeSuspend = CalibrationState.UNCALIBRATED
            lastReason = reason
        } else {
            move(CalibrationState.UNCALIBRATED, reason, detail)
        }
    }

    private fun move(to: CalibrationState, reason: CalibrationReason, detail: String = "") {
        lastReason = reason
        val from = state
        if (from == to) return
        state = to
        notify(from, reason, detail)
        stateSince = nowMs
    }

    /** Tells [onTransition] about the change from [from] to the current state, with its conditions. */
    private fun notify(from: CalibrationState, reason: CalibrationReason, detail: String) {
        lastTransition = CalibrationTransition(
            from, state, reason, quality, lessons,
            if (stateSince == 0L || nowMs < stateSince) -1L else nowMs - stateSince,
            orientationChangeDeg(), detail
        )
        onTransition?.invoke(from, state, reason)
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

    private fun angleDeg(a: FloatArray, b: FloatArray): Int {
        val na = norm(a)
        val nb = norm(b)
        if (na < 1e-6f || nb < 1e-6f) return 0
        return Math.toDegrees(kotlin.math.acos((dot(a, b) / (na * nb)).coerceIn(-1f, 1f).toDouble())).toInt()
    }

    private fun Float.fmt() = if (isNaN()) "n/a" else String.format(Locale.US, "%.2f", this)
    private fun Float.fmt0() = if (isNaN()) "n/a" else String.format(Locale.US, "%.0f", this)
    private fun join(a: String, b: String) = if (b.isEmpty()) a else "$a; $b"

    companion object {
        /** Consistent lessons needed before READY, and how well they must agree. */
        const val MIN_LESSONS = 3
        const val READY_QUALITY = 0.8f

        /** A lesson: the speed changed at least this much (m/s²) above this speed, driving straight. */
        private const val LESSON_MIN_GPS_ACCEL = 0.6f
        private const val LESSON_MIN_SPEED_MPS = 5f / 3.6f
        private const val LESSON_MAX_YAW = 0.12f            // rad/s, mean over the interval

        /**
         * Wave 8: "straight" also at speed — the centripetal acceleration (speed × mean |yaw rate|) may be at most this
         * share of the GPS acceleration, so it turns the lesson's direction by less than ≈ 27°.
         */
        const val LESSON_MAX_LATERAL_SHARE = 0.5f

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

        /** Mismatches in a row (no fitting interval between them, each within [MISMATCH_WINDOW_MS]) that suspend. */
        private const val MAX_MISMATCHES = 3
        const val MISMATCH_WINDOW_MS = 30_000L

        /** The phone still within this angle of the learned orientation keeps the axis. */
        const val SAME_ORIENTATION_DEG = 15.0

        /** The axis projected on the horizontal plane must keep this much of its length (tilt < ~45°). */
        private const val MIN_PROJECTION = 0.7f
    }
}
