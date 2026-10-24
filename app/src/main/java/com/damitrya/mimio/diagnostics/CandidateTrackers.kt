package com.damitrya.mimio.diagnostics

import com.damitrya.mimio.data.BrakeDetector
import com.damitrya.mimio.data.TurnDirection
import kotlin.math.abs

/**
 * Candidate = an episode where a measured value comes close to a detector's threshold: at least
 * [START_FRACTION] of it. The trackers only watch the values the detectors already computed (they never
 * change a threshold or a decision) and say, once per episode, whether the detector fired — or which of its
 * checks stopped it. Times are milliseconds of one monotonic clock.
 */
internal const val START_FRACTION = 0.6f

/** Braking episodes from [BrakeDetector] samples (its [BrakeDetector.lastCheck] / [BrakeDetector.lastValue]). */
class BrakeCandidateTracker {

    data class Outcome(
        val accepted: Boolean,
        val reason: DiagnosticReason?,
        val check: BrakeDetector.Check,
        /** The compared value at the episode's peak (m/s²). */
        val value: Float,
        val threshold: Float,
        /** GPS speed the detector knew at the peak, -1 = none yet. */
        val speedKmh: Float,
        val calibration: BrakeDetector.Calibration,
        val learnSamples: Int,
        /** True when a pending jolt became braking because GPS confirmed the slowdown. */
        val confirmedByGps: Boolean = false
    )

    private var active = false
    private var reported = false
    private var belowSince = 0L
    private var peak = 0f
    private var peakValue = 0f
    private var peakSpeed = -1f
    private var peakCheck = BrakeDetector.Check.LEGACY
    private var aboveWithLowSpeed = false

    fun onSample(
        check: BrakeDetector.Check, value: Float, threshold: Float, speedKmh: Float,
        calibration: BrakeDetector.Calibration, learnSamples: Int, fired: Boolean, now: Long
    ): Outcome? {
        val intensity = intensity(check, value, threshold)
        if (!active) {
            if (intensity < START_FRACTION && !fired) return null
            active = true
            reported = false
            belowSince = 0L
            peak = Float.NEGATIVE_INFINITY
            aboveWithLowSpeed = false
        }
        if (intensity > peak) {
            peak = intensity
            peakValue = value
            peakSpeed = speedKmh
            peakCheck = check
        }
        if (intensity >= 1f && speedKmh < BrakeDetector.MIN_SPEED_KMH) aboveWithLowSpeed = true

        if (fired && !reported) {
            reported = true
            return Outcome(true, null, check, value, threshold, speedKmh, calibration, learnSamples)
        }
        if (intensity >= START_FRACTION) {
            belowSince = 0L
            return null
        }
        if (belowSince == 0L) belowSince = now
        if (now - belowSince < BRAKE_END_MS) return null
        active = false
        if (reported) return null
        val reason = when {
            peak < 1f -> DiagnosticReason.ACCELERATION_BELOW_THRESHOLD
            // The detector itself ignores braking under BrakeDetector.MIN_SPEED_KMH (not the legacy check).
            aboveWithLowSpeed && peakCheck != BrakeDetector.Check.LEGACY -> DiagnosticReason.SPEED_BELOW_THRESHOLD
            // Not learned yet: a jolt is only remembered until GPS confirms the slowdown.
            peakCheck == BrakeDetector.Check.JOLT -> DiagnosticReason.CALIBRATION_PENDING
            else -> null      // braking was already on before the episode began
        }
        return Outcome(false, reason, peakCheck, peakValue, threshold, peakSpeed, calibration, learnSamples)
    }

    /** How close [value] came to firing: 1 = exactly at the threshold. */
    private fun intensity(check: BrakeDetector.Check, value: Float, threshold: Float): Float {
        if (threshold == 0f) return 0f
        return when (check) {
            // braking = value < threshold (threshold negative)
            BrakeDetector.Check.LEGACY, BrakeDetector.Check.AXIS -> value / threshold
            // jolt = magnitude > |threshold|
            BrakeDetector.Check.JOLT -> value / abs(threshold)
        }
    }

    companion object {
        /** An episode ends after the value stays under the candidate level this long. */
        const val BRAKE_END_MS = 300L
    }
}

/** Sharp-turn episodes from SensorDataProvider's yaw rate and lateral acceleration (speed × yaw rate). */
class TurnCandidateTracker {

    data class Outcome(
        val accepted: Boolean,
        val reason: DiagnosticReason?,
        val direction: TurnDirection,
        val lateral: Float,
        val yawRate: Float,
        val speedKmh: Float,
        val threshold: Float,
        /** Longest time the lateral acceleration stayed above the threshold. */
        val aboveMs: Long
    )

    private var active = false
    private var reported = false
    private var belowSince = 0L
    private var peakLateral = 0f
    private var yawAtPeak = 0f
    private var speedAtPeak = 0f
    private var peakYaw = 0f
    private var aboveSince = 0L
    private var maxAboveMs = 0L
    private var sawGps = false

    fun onSample(
        yawRate: Float, lateral: Float, speedKmh: Float, threshold: Float,
        turning: Boolean, hasGps: Boolean, now: Long
    ): Outcome? {
        val near = abs(yawRate) >= YAW_START_RAD_S || lateral >= threshold * START_FRACTION
        if (!active) {
            if (!near && !turning) return null
            active = true
            reported = false
            belowSince = 0L
            peakLateral = -1f
            peakYaw = 0f
            aboveSince = 0L
            maxAboveMs = 0L
            sawGps = false
        }
        if (hasGps) sawGps = true
        if (lateral > peakLateral) {
            peakLateral = lateral
            yawAtPeak = yawRate
            speedAtPeak = speedKmh
        }
        if (abs(yawRate) > abs(peakYaw)) peakYaw = yawRate
        if (lateral > threshold) {
            if (aboveSince == 0L) aboveSince = now
            maxAboveMs = maxOf(maxAboveMs, now - aboveSince)
        } else {
            aboveSince = 0L
        }

        if (turning && !reported) {
            reported = true
            return Outcome(true, null, direction(yawRate), lateral, yawRate, speedKmh, threshold, maxAboveMs)
        }
        if (near || turning) {
            belowSince = 0L
            return null
        }
        if (belowSince == 0L) belowSince = now
        if (now - belowSince < TURN_END_MS) return null
        active = false
        if (reported) return null
        val reason = when {
            !sawGps -> DiagnosticReason.GPS_MISSING
            peakLateral <= threshold -> DiagnosticReason.ACCELERATION_BELOW_THRESHOLD
            else -> DiagnosticReason.DURATION_BELOW_THRESHOLD
        }
        val yaw = if (peakLateral > 0f) yawAtPeak else peakYaw
        return Outcome(false, reason, direction(yaw), maxOf(peakLateral, 0f), yaw, speedAtPeak, threshold, maxAboveMs)
    }

    /** Same sign rule as SensorDataProvider: positive yaw rate = turning left. */
    private fun direction(yaw: Float) = if (yaw > 0) TurnDirection.LEFT else TurnDirection.RIGHT

    companion object {
        /** A yaw rate from this (≈ 8.6 °/s) is a turn candidate even when the speed makes the lateral value small. */
        const val YAW_START_RAD_S = 0.15f
        const val TURN_END_MS = 500L
    }
}

/** Hard-acceleration episodes from SensorDataProvider's longitudinal value (phone Y axis, low-pass filtered). */
class AccelCandidateTracker {

    data class Outcome(val accepted: Boolean, val reason: DiagnosticReason?, val acceleration: Float, val threshold: Float)

    private var active = false
    private var reported = false
    private var belowSince = 0L
    private var peak = 0f

    /** [phoneY] < -[threshold] is SensorDataProvider's rapid acceleration ([rapid]). */
    fun onSample(phoneY: Float, threshold: Float, rapid: Boolean, now: Long): Outcome? {
        val accel = -phoneY
        val intensity = if (threshold > 0f) accel / threshold else 0f
        if (!active) {
            if (intensity < START_FRACTION && !rapid) return null
            active = true
            reported = false
            belowSince = 0L
            peak = accel
        }
        if (accel > peak) peak = accel
        if (rapid && !reported) {
            reported = true
            return Outcome(true, null, accel, threshold)
        }
        if (intensity >= START_FRACTION || rapid) {
            belowSince = 0L
            return null
        }
        if (belowSince == 0L) belowSince = now
        if (now - belowSince < ACCEL_END_MS) return null
        active = false
        if (reported) return null
        return Outcome(false, DiagnosticReason.ACCELERATION_BELOW_THRESHOLD, peak, threshold)
    }

    companion object {
        const val ACCEL_END_MS = 300L
    }
}
