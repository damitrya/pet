package com.damitrya.mimio.data

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detects sharp braking whatever the phone mount (flat, upright in a holder, landscape).
 *
 * Linear acceleration is reduced to its horizontal part (perpendicular to gravity). The car's
 * forward direction in phone coordinates is learned from GPS: while the speed clearly rises or
 * falls, the mean horizontal acceleration points forward or backward. Once learned, braking is
 * the acceleration along that axis dropping below [threshold] — reported instantly.
 *
 * The axis is dropped when the phone is moved: gravity in phone coordinates turning by more than
 * [REORIENT_DEG] for [REORIENT_MS] (taken out of the holder, tilted, turned over), or GPS twice in a
 * row showing speed changes along a clearly different direction (the holder swivelled around the
 * vertical, which gravity cannot see).
 *
 * Before the axis is learned, a strong horizontal jolt at speed is reported only if GPS confirms
 * within [CONFIRM_MS] that the car really slowed down. Without any GPS data the legacy check of
 * the phone's X/Y axes is used (works when the phone lies flat).
 *
 * Pure logic, no Android dependencies; times are in milliseconds of one monotonic clock.
 */
class BrakeDetector(var threshold: Float = SettingsRepository.DEFAULT_ALERT_BRAKING_THRESHOLD) {

    private val gravity = FloatArray(3)
    private var hasGravity = false
    private val filtered = FloatArray(3)

    /** Learned forward axis (unit vector, phone coordinates) and how many GPS intervals taught it. */
    private val forward = FloatArray(3)
    private var forwardSamples = 0

    /** Gravity direction (unit) while the axis was learned, and since when it has differed. */
    private val axisGravity = FloatArray(3)
    private var reorientSince = 0L

    /** Consecutive GPS lessons that contradicted the learned axis. */
    private var disagreements = 0

    /** Horizontal acceleration summed over the current GPS interval (for learning). */
    private val sumH = FloatArray(3)
    private var countH = 0

    private var lastSpeedKmh = -1f
    private var lastSpeedAt = 0L

    private var wasBraking = false
    private var wasBelowX = false
    private var wasBelowY = false

    /** Unconfirmed jolt waiting for GPS (0 = none); a new one needs the previous jolt to end. */
    private var pendingAt = 0L
    private var joltActive = false
    private var pendingSpeedKmh = 0f

    val isAxisLearned: Boolean get() = forwardSamples >= MIN_LEARN_SAMPLES

    /** Which comparison the last acceleration sample went through (diagnostics; detection never reads it). */
    enum class Check {
        /** No GPS speed yet: the phone's X/Y axes against [threshold] (works when the phone lies flat). */
        LEGACY,

        /** Learned forward axis: the acceleration along it against [threshold]. */
        AXIS,

        /** Axis not learned yet: the horizontal jolt against |[threshold]|, then GPS must confirm. */
        JOLT
    }

    /** How far learning the forward axis is (diagnostics). */
    enum class Calibration { NO_GPS, LEARNING, READY }

    var lastCheck: Check = Check.LEGACY
        private set

    /** The value [lastCheck] compared: LEGACY the lower of X/Y, AXIS the acceleration along the axis, JOLT its magnitude. */
    var lastValue: Float = 0f
        private set

    val calibration: Calibration
        get() = when {
            lastSpeedAt == 0L -> Calibration.NO_GPS
            isAxisLearned -> Calibration.READY
            else -> Calibration.LEARNING
        }

    /** GPS intervals that have taught the current axis so far (READY at [MIN_LEARN_SAMPLES]). */
    val learnSamples: Int get() = forwardSamples

    /** Last GPS speed given to [onSpeed], or -1 before the first one. */
    val speedKmh: Float get() = lastSpeedKmh

    /** A jolt waits for GPS to confirm it as braking. */
    val isConfirmationPending: Boolean get() = pendingAt != 0L

    fun reset() {
        filtered.fill(0f)
        sumH.fill(0f)
        countH = 0
        wasBraking = false
        wasBelowX = false
        wasBelowY = false
        pendingAt = 0L
        joltActive = false
        lastSpeedKmh = -1f
        lastSpeedAt = 0L
        // The learned axis is kept (the phone usually stays in the same holder); if it was moved,
        // onGravity() and learn() notice and drop the axis.
    }

    /** Forget the learned forward axis; it is learned again on the next speed changes. */
    fun forgetAxis() {
        forwardSamples = 0
        disagreements = 0
        reorientSince = 0L
        wasBraking = false
    }

    fun onGravity(x: Float, y: Float, z: Float, now: Long) {
        gravity[0] = x; gravity[1] = y; gravity[2] = z
        hasGravity = true
        if (forwardSamples == 0) return
        val g = norm(gravity)
        if (g < 1f) return
        if (norm(axisGravity) < 0.5f) {
            rememberGravity()   // the axis was learned before the first gravity reading
            return
        }
        val cos = (x * axisGravity[0] + y * axisGravity[1] + z * axisGravity[2]) / g
        if (cos < REORIENT_COS) {
            if (reorientSince == 0L) reorientSince = now
            if (now - reorientSince >= REORIENT_MS) forgetAxis()
        } else {
            reorientSince = 0L
        }
    }

    /** Linear acceleration sample (gravity excluded). Returns true when sharp braking starts. */
    fun onLinearAcceleration(x: Float, y: Float, z: Float, now: Long): Boolean {
        filtered[0] = filtered[0] * 0.8f + x * 0.2f
        filtered[1] = filtered[1] * 0.8f + y * 0.2f
        filtered[2] = filtered[2] * 0.8f + z * 0.2f

        if (lastSpeedAt == 0L) return legacyCheck()

        val h = horizontal()
        for (i in 0..2) sumH[i] += h[i]
        countH++

        if (isAxisLearned) {
            val along = h[0] * forward[0] + h[1] * forward[1] + h[2] * forward[2]
            lastCheck = Check.AXIS
            lastValue = along
            val braking = along < threshold && lastSpeedKmh >= MIN_SPEED_KMH
            val started = braking && !wasBraking
            wasBraking = braking
            return started
        }

        // Not learned yet: remember a strong jolt and wait for GPS to confirm deceleration.
        val joltNorm = norm(h)
        lastCheck = Check.JOLT
        lastValue = joltNorm
        val jolt = joltNorm > abs(threshold)
        if (jolt && !joltActive && pendingAt == 0L && lastSpeedKmh >= MIN_SPEED_KMH) {
            pendingAt = now
            pendingSpeedKmh = lastSpeedKmh
        }
        joltActive = jolt
        return false
    }

    /** GPS speed update. Returns true when it confirms a pending jolt as braking. */
    fun onSpeed(kmh: Float, now: Long): Boolean {
        var confirmed = false
        // Repeated reports between GPS fixes (other sensor changes re-send the same speed)
        // must not cut the learning interval short.
        if (lastSpeedAt != 0L && now - lastSpeedAt < MIN_SPEED_INTERVAL_MS) return false
        if (lastSpeedAt != 0L && countH > 0) {
            val dt = (now - lastSpeedAt) / 1000f
            if (dt in 0.3f..3f) learn((kmh - lastSpeedKmh) / 3.6f / dt)
        }
        if (pendingAt != 0L) {
            if (pendingSpeedKmh - kmh >= CONFIRM_DROP_KMH) {
                confirmed = true
                pendingAt = 0L
            } else if (now - pendingAt > CONFIRM_MS) {
                pendingAt = 0L
            }
        }
        sumH.fill(0f)
        countH = 0
        lastSpeedKmh = kmh
        lastSpeedAt = now
        return confirmed
    }

    private fun learn(dv: Float) {
        if (abs(dv) < LEARN_MIN_DV) return
        val mean = FloatArray(3) { sumH[it] / countH }
        val n = norm(mean)
        if (n < LEARN_MIN_ACCEL) return
        val sign = if (dv > 0) 1f else -1f
        val dir = FloatArray(3) { sign * mean[it] / n }

        if (forwardSamples > 0) {
            val agree = dir[0] * forward[0] + dir[1] * forward[1] + dir[2] * forward[2]
            if (agree < DISAGREE_COS) {
                // One odd lesson can be a turn while braking; two in a row mean the phone moved.
                if (++disagreements < MAX_DISAGREEMENTS) return
                forgetAxis()
            } else {
                disagreements = 0
            }
        }

        for (i in 0..2) {
            forward[i] = if (forwardSamples == 0) dir[i] else forward[i] * 0.8f + dir[i] * 0.2f
        }
        val fn = norm(forward)
        if (fn > 1e-3f) for (i in 0..2) forward[i] /= fn
        forwardSamples++
        rememberGravity()
    }

    private fun rememberGravity() {
        val g = norm(gravity)
        if (!hasGravity || g < 1f) return
        for (i in 0..2) axisGravity[i] = gravity[i] / g
        reorientSince = 0L
    }

    private fun horizontal(): FloatArray {
        if (!hasGravity) return floatArrayOf(filtered[0], filtered[1], 0f)
        val g = norm(gravity)
        if (g < 1f) return floatArrayOf(filtered[0], filtered[1], 0f)
        val dot = (filtered[0] * gravity[0] + filtered[1] * gravity[1] + filtered[2] * gravity[2]) / (g * g)
        return FloatArray(3) { filtered[it] - dot * gravity[it] }
    }

    private fun legacyCheck(): Boolean {
        lastCheck = Check.LEGACY
        lastValue = minOf(filtered[0], filtered[1])
        val belowX = filtered[0] < threshold
        val belowY = filtered[1] < threshold
        val started = (belowX && !wasBelowX) || (belowY && !wasBelowY)
        wasBelowX = belowX
        wasBelowY = belowY
        return started
    }

    private fun norm(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    companion object {
        /** GPS intervals with clear speed change needed before the forward axis is trusted. */
        const val MIN_LEARN_SAMPLES = 3

        /** Speed change (m/s²) and mean horizontal acceleration (m/s²) that teach the axis. */
        private const val LEARN_MIN_DV = 0.7f
        private const val LEARN_MIN_ACCEL = 0.5f

        /** Braking below this speed is ignored (handling the phone at a standstill). */
        const val MIN_SPEED_KMH = 8f

        /** Fallback confirmation: the speed must drop this much within this time. */
        private const val CONFIRM_DROP_KMH = 4f
        private const val CONFIRM_MS = 2500L

        /** Gravity turning more than this (degrees) for this long means the phone was moved. */
        private const val REORIENT_DEG = 30.0
        private val REORIENT_COS = kotlin.math.cos(Math.toRadians(REORIENT_DEG)).toFloat()
        private const val REORIENT_MS = 1000L

        /** A GPS lesson more than 60° off the learned axis contradicts it; two in a row reset it. */
        private const val DISAGREE_COS = 0.5f
        private const val MAX_DISAGREEMENTS = 2

        /** GPS fixes come once a second; closer speed reports are duplicates. */
        private const val MIN_SPEED_INTERVAL_MS = 500L
    }
}
