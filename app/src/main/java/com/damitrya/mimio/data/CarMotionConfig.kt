package com.damitrya.mimio.data

/**
 * Thresholds of the car physics (wave 7). Starting values come from the drive of 26 October (Motorola Edge 70,
 * see docs/car-physics.md): ordinary city turns stayed at 1–2.5 m/s² of lateral acceleration, ordinary braking
 * and starting off at 1–2.5 m/s², while the old 3.5 m/s² turn threshold caught only one turn out of 36.
 * They are hypotheses to tune with the next recorded drive, not final constants.
 *
 * Accelerations are magnitudes in m/s²; the sign of an event comes from its kind. Emergency braking (ALERT)
 * keeps its own threshold ([SettingsRepository.alertBrakingThreshold], −4 m/s²) in [BrakeDetector].
 */
data class CarMotionConfig(
    /** Below this GPS speed no lean plays (manoeuvring, handling the phone in a car park). */
    val minSpeedKmh: Float = 8f,

    /** Lateral acceleration (speed × yaw rate) of an ordinary turn and of a sharp one (TURN_LEFT/RIGHT). */
    val softTurn: Float = 1.2f,
    val strongTurn: Float = 3.0f,

    /** Deceleration along the car's axis: ordinary and intensive braking. */
    val softBrake: Float = 1.2f,
    val strongBrake: Float = 2.5f,

    /** Acceleration along the car's axis: ordinary and intensive starting off. */
    val softAccel: Float = 1.2f,
    val strongAccel: Float = 2.5f,

    /** Hysteresis: a manoeuvre ends once the value stays below this share of its soft threshold. */
    val releaseFraction: Float = 0.6f,

    /** A manoeuvre must stay above its threshold this long (bumps and jolts are shorter). */
    val sustainMs: Long = 300L,

    /** ...and below the release level this long to end (a short dip does not start a second reaction). */
    val endMs: Long = 400L,

    /** Minimum pause between two ordinary leans; intensive ones have their own pause of the same length. */
    val softCooldownMs: Long = 4_000L,

    /** The GPS speed used for the turn and the gates is at most this old. */
    val speedFreshMs: Long = 3_000L,

    /** Smoothing of the longitudinal acceleration and of the yaw rate (time constants). */
    val longitudinalTauMs: Long = 250L,
    val yawTauMs: Long = 200L
) {
    fun releaseOf(soft: Float) = soft * releaseFraction

    companion object {
        val DEFAULT = CarMotionConfig()
    }
}
