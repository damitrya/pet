package com.damitrya.mimio.data

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Estimates whether the phone is being moved relative to the car (taken in the hand, turned, put back). An
 * estimate, never a certainty: each sign alone can also come from the car, so each is chosen to be one the car
 * itself hardly produces.
 *
 * - **Tilt** ([TILT_RAD]): the rotation around horizontal axes, integrated with a leak. A car only rotates around
 *   the vertical (turns); its pitch and roll (bumps, braking) are small and swing back, so they cancel out in the
 *   integral, while picking the phone up tilts it by tens of degrees within a second.
 * - **Orientation jump** ([ORIENTATION_DEG]): gravity in phone coordinates moved quickly away from where it had
 *   been resting. A slow drift (the holder sagging, the fused gravity leaning in a long bend) is followed.
 * - **Implausible yaw**: a rotation around the vertical that the car cannot make at the GPS speed ([plausibleYaw]:
 *   no tighter than [MIN_TURN_RADIUS_M], no more than [MAX_CAR_LATERAL] of lateral acceleration) for
 *   [YAW_SUSTAIN_MS] — the gyroscope checked against the GPS speed, so a sharp turn of the car never counts.
 * - **Shaken while tilting**: a strong linear acceleration together with a smaller tilt.
 *
 * The phone counts as moving from the first sign until no sign was seen for [STABLE_MS].
 * Pure logic; times in milliseconds of one monotonic clock.
 */
class PhoneMotionDetector {

    var isMoving: Boolean = false
        private set

    /** What made it moving the last time (diagnostics). */
    var cause: String = ""
        private set

    /** Net tilt (rad) and the gravity jump (degrees) at the last sample (diagnostics). */
    var tilt: Float = 0f
        private set
    var orientationJumpDeg: Float = 0f
        private set

    private val tiltVec = FloatArray(3)
    private var lastGyroAt = 0L
    private val restUp = FloatArray(3)
    private var hasRest = false
    private var lastGravityAt = 0L
    private var implausibleSince = 0L
    private var lastEvidenceAt = 0L
    private var shake = 0f

    fun reset() {
        isMoving = false
        cause = ""
        tiltVec.fill(0f)
        tilt = 0f
        orientationJumpDeg = 0f
        lastGyroAt = 0L
        hasRest = false
        lastGravityAt = 0L
        implausibleSince = 0L
        lastEvidenceAt = 0L
        shake = 0f
    }

    /**
     * A gyroscope sample. [up] is the unit up vector (null: unknown), [speedKmh] the fresh GPS speed or a negative
     * value when there is none. Returns true when [isMoving] changed.
     */
    fun onGyro(wx: Float, wy: Float, wz: Float, up: FloatArray?, speedKmh: Float, now: Long): Boolean {
        val dt = if (lastGyroAt == 0L) 0f else ((now - lastGyroAt) / 1000f).coerceIn(0f, 0.2f)
        lastGyroAt = now
        val yaw = if (up != null) wx * up[0] + wy * up[1] + wz * up[2] else wz
        if (up != null) {
            val hx = wx - yaw * up[0]; val hy = wy - yaw * up[1]; val hz = wz - yaw * up[2]
            val leak = exp(-dt / TILT_LEAK_S)
            tiltVec[0] = (tiltVec[0] + hx * dt) * leak
            tiltVec[1] = (tiltVec[1] + hy * dt) * leak
            tiltVec[2] = (tiltVec[2] + hz * dt) * leak
            tilt = sqrt(tiltVec[0] * tiltVec[0] + tiltVec[1] * tiltVec[1] + tiltVec[2] * tiltVec[2])
        }
        if (abs(yaw) > plausibleYaw(speedKmh)) {
            if (implausibleSince == 0L) implausibleSince = now
        } else {
            implausibleSince = 0L
        }
        return when {
            tilt >= TILT_RAD -> evidence(now) { "tilt ${deg(tilt)}°" }
            implausibleSince != 0L && now - implausibleSince >= YAW_SUSTAIN_MS -> evidence(now) {
                "yaw ${String.format(Locale.US, "%.2f", abs(yaw))} rad/s at ${if (speedKmh < 0) "no GPS" else "${speedKmh.toInt()} km/h"}"
            }
            shake >= SHAKE_MS2 && tilt >= SHAKE_TILT_RAD -> evidence(now) { "shaken while tilting" }
            else -> settle(now)
        }
    }

    /** A gravity sample (unit up vector). Returns true when [isMoving] changed. */
    fun onGravity(up: FloatArray, now: Long): Boolean {
        val dt = if (lastGravityAt == 0L) 0f else ((now - lastGravityAt) / 1000f).coerceIn(0f, 1f)
        lastGravityAt = now
        if (!hasRest) {
            up.copyInto(restUp)
            hasRest = true
            return false
        }
        val cos = (up[0] * restUp[0] + up[1] * restUp[1] + up[2] * restUp[2]).coerceIn(-1f, 1f)
        orientationJumpDeg = Math.toDegrees(kotlin.math.acos(cos).toDouble()).toFloat()
        // The rest orientation follows slowly while still (sag, fused-gravity drift), quickly while handled.
        val k = 1 - exp(-dt / if (isMoving) FOLLOW_MOVING_S else FOLLOW_STILL_S)
        for (i in 0..2) restUp[i] += (up[i] - restUp[i]) * k
        val n = sqrt(restUp[0] * restUp[0] + restUp[1] * restUp[1] + restUp[2] * restUp[2])
        if (n > 1e-3f) for (i in 0..2) restUp[i] /= n
        return if (orientationJumpDeg >= ORIENTATION_DEG) evidence(now) { "orientation ${orientationJumpDeg.toInt()}°" } else settle(now)
    }

    /** Magnitude of a linear-acceleration sample (all three axes), for the shake sign. */
    fun onLinear(magnitude: Float) {
        shake = shake * 0.7f + magnitude * 0.3f
    }

    /** Re-checks the quiet period without a sample (returns true when it ended). */
    fun tick(now: Long): Boolean = settle(now)

    /** A sign of handling; [what] is only built when it starts an episode. */
    private inline fun evidence(now: Long, what: () -> String): Boolean {
        lastEvidenceAt = now
        if (isMoving) return false
        isMoving = true
        cause = what()
        return true
    }

    private fun settle(now: Long): Boolean {
        if (!isMoving || now - lastEvidenceAt < STABLE_MS) return false
        isMoving = false
        tiltVec.fill(0f)
        tilt = 0f
        return true
    }

    private fun deg(rad: Float) = Math.toDegrees(rad.toDouble()).toInt()

    companion object {
        /** Net tilt that means handling (≈ 17°), and the leak of its integral. */
        const val TILT_RAD = 0.30f
        private const val TILT_LEAK_S = 0.8f

        /** Gravity jumping this far from its rest orientation. */
        const val ORIENTATION_DEG = 25f
        private const val FOLLOW_STILL_S = 5f
        private const val FOLLOW_MOVING_S = 0.4f

        /** Tightest car turn, strongest lateral acceleration of a car on the road, and the yaw-rate limits. */
        private const val MIN_TURN_RADIUS_M = 4.5f
        const val MAX_CAR_LATERAL = 9f
        const val MIN_PLAUSIBLE_YAW = 0.35f
        const val MAX_PLAUSIBLE_YAW = 1.0f

        /** Without GPS the car may still turn at speed (tunnel): a looser limit. */
        private const val NO_GPS_YAW = 0.6f
        private const val YAW_SUSTAIN_MS = 300L

        /** A strong jerk with some tilt. */
        private const val SHAKE_MS2 = 4f
        private const val SHAKE_TILT_RAD = 0.15f

        /** Quiet this long: the phone is still again. */
        const val STABLE_MS = 1_500L

        /** Fastest rotation around the vertical (rad/s) a car can make at [speedKmh] (negative: no GPS). */
        fun plausibleYaw(speedKmh: Float): Float {
            if (speedKmh < 0f) return NO_GPS_YAW
            val v = speedKmh / 3.6f
            val byRadius = v / MIN_TURN_RADIUS_M
            val byGrip = if (v > 0.1f) MAX_CAR_LATERAL / v else MAX_PLAUSIBLE_YAW
            return minOf(byRadius, byGrip).coerceIn(MIN_PLAUSIBLE_YAW, MAX_PLAUSIBLE_YAW)
        }

        /** Tilt (rad) that already makes a turn suspicious: a car turns around the vertical only. */
        const val TURN_TILT_RAD = 0.15f
    }
}
