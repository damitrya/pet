package com.damitrya.mimio.data

import com.damitrya.mimio.diagnostics.DiagnosticReason
import com.damitrya.mimio.state.MovementStabilizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parts of the wave 7 car physics one by one: thresholds, hysteresis, calibration states, plausibility. */
class CarPhysicsPartsTest {

    // ---- thresholds

    @Test
    fun startingThresholdsAreTheAgreedHypotheses() {
        val c = CarMotionConfig.DEFAULT
        assertEquals(8f, c.minSpeedKmh)
        assertEquals(1.2f, c.softTurn)
        assertEquals(3.0f, c.strongTurn)
        assertEquals(1.2f, c.softBrake)
        assertEquals(2.5f, c.strongBrake)
        assertEquals(1.2f, c.softAccel)
        assertEquals(2.5f, c.strongAccel)
        assertTrue(c.sustainMs in 250L..400L)
        assertEquals(4_000L, c.softCooldownMs)
        assertEquals(-4.0f, SettingsRepository.DEFAULT_ALERT_BRAKING_THRESHOLD)
        assertTrue("release below the start", c.releaseOf(c.softTurn) < c.softTurn)
    }

    // ---- ManoeuvreChannel

    private class Run(config: CarMotionConfig = CarMotionConfig.DEFAULT, var gateReason: DiagnosticReason? = null) {
        val channel = ManoeuvreChannel(InertiaKind.ACCELERATE, InertiaKind.BRAKE, 1.2f, 2.5f, 1.2f, 2.5f, config)
        val events = mutableListOf<CarMotionEvent>()
        val outcomes = mutableListOf<InertiaOutcome>()
        var t = 0L

        fun feed(value: Float, ms: Long) {
            val end = t + ms
            while (t < end) {
                channel.onSample(value, t, 40f, { _, _ -> gateReason }, { outcomes += it }, { events += it })
                t += 20
            }
        }
    }

    @Test
    fun aManoeuvreNeedsItsSustainAndFiresOnce() {
        val r = Run()
        r.feed(0f, 200)
        r.feed(-1.5f, 280)
        assertTrue("not yet", r.events.isEmpty())
        r.feed(-1.5f, 3_000)
        r.feed(-1.0f, 2_000)                  // above the release level: still the same braking
        r.feed(-1.5f, 2_000)
        assertEquals(1, r.events.size)
        assertEquals(InertiaKind.BRAKE, r.events[0].kind)
        r.feed(0f, 600)                       // released
        r.feed(-1.5f, 500)
        assertEquals("a new braking", 2, r.events.size)
    }

    @Test
    fun hysteresisKeepsAShortDipInOneManoeuvre() {
        val r = Run()
        r.feed(1.6f, 1_000)
        r.feed(0.5f, 200)                     // below the release level, but shorter than endMs
        r.feed(1.6f, 1_000)
        assertEquals(1, r.events.size)
    }

    @Test
    fun tooShortIsReportedAsSuch() {
        val r = Run()
        r.feed(1.8f, 200)
        r.feed(0f, 1_000)
        assertTrue(r.events.isEmpty())
        assertEquals(DiagnosticReason.EVENT_TOO_SHORT, r.outcomes.single().reason)
    }

    @Test
    fun anOppositeManoeuvreEndsTheCurrentOne() {
        val r = Run()
        r.feed(1.6f, 1_000)
        r.feed(-1.6f, 1_000)                  // braking right after speeding up
        assertEquals(listOf(InertiaKind.ACCELERATE, InertiaKind.BRAKE), r.events.map { it.kind })
    }

    @Test
    fun aQuickStrongManoeuvreIsOneStrongEvent() {
        val r = Run()
        r.feed(-3.0f, 1_000)
        assertEquals(listOf(InertiaLevel.STRONG), r.events.map { it.level })
        assertFalse(r.events[0].upgrade)
    }

    @Test
    fun aRejectedManoeuvreIsReportedOnceAndNeverPlaysLater() {
        val r = Run(gateReason = DiagnosticReason.PHONE_MOVING)
        r.feed(-1.6f, 600)
        r.gateReason = null
        r.feed(-1.6f, 2_000)
        assertTrue(r.events.isEmpty())
        assertEquals(listOf(DiagnosticReason.PHONE_MOVING), r.outcomes.map { it.reason })
    }

    @Test
    fun aSlowStartWaitsForTheSpeed() {
        val r = Run(gateReason = DiagnosticReason.SPEED_BELOW_THRESHOLD)
        r.feed(1.6f, 1_000)
        assertTrue(r.events.isEmpty())
        assertTrue("nothing reported while it may still play", r.outcomes.isEmpty())
        r.gateReason = null
        r.feed(1.6f, 300)
        assertEquals(listOf(InertiaKind.ACCELERATE), r.events.map { it.kind })
        // ...and one that ends before reaching the speed is reported as too slow
        val slow = Run(gateReason = DiagnosticReason.SPEED_BELOW_THRESHOLD)
        slow.feed(1.6f, 1_000)
        slow.feed(0f, 1_000)
        assertEquals(listOf(DiagnosticReason.SPEED_BELOW_THRESHOLD), slow.outcomes.map { it.reason })
    }

    // ---- VehicleFrameEstimator

    /** Feeds one GPS interval: [n] samples of horizontal acceleration [h] (phone coordinates), then the speed. */
    private fun VehicleFrameEstimator.interval(h: FloatArray, toKmh: Float, at: Long, yaw: Float = 0f) {
        repeat(50) { onSample(h, yaw) }
        onSpeed(toKmh, at)
    }

    private fun learned(): Pair<VehicleFrameEstimator, Long> {
        val f = VehicleFrameEstimator()
        f.onGravity(0f, 9.81f, 0f)                       // upright: phone y up
        var t = 1_000L
        f.onSpeed(20f, t)
        var kmh = 20f
        repeat(3) {
            t += 1_000; kmh += 5.4f                       // +1.5 m/s², forward = phone −z
            f.interval(floatArrayOf(0f, 0f, -1.5f), kmh, t)
        }
        assertEquals(CalibrationState.READY, f.state)
        return f to t
    }

    @Test
    fun readySurvivesOneContradictingLesson() {
        var (f, t) = learned()
        var kmh = 36.2f
        t += 1_000; kmh -= 5.4f
        f.interval(floatArrayOf(0f, 0f, -1.5f), kmh, t)    // "slowing down" with a forward push: contradicts
        assertEquals(CalibrationState.READY, f.state)
        t += 1_000; kmh -= 5.4f
        f.interval(floatArrayOf(0f, 0f, 1.5f), kmh, t)     // consistent again
        assertEquals(CalibrationState.READY, f.state)
        val axis = FloatArray(3)
        assertTrue(f.axis(axis))
        assertTrue(axis[2] < -0.9f)
    }

    @Test
    fun threeAgreeingContradictionsMeanTheHolderWasSwivelled() {
        var (f, t) = learned()
        val reasons = mutableListOf<CalibrationReason>()
        f.onTransition = { _, _, r -> reasons += r }
        var kmh = 36.2f
        repeat(3) {
            t += 1_000; kmh += 5.4f
            f.interval(floatArrayOf(1.5f, 0f, 0f), kmh, t)  // forward is now phone +x
        }
        assertTrue(reasons.toString(), CalibrationReason.HOLDER_ROTATED in reasons)
        assertEquals(CalibrationState.READY, f.state)
        val axis = FloatArray(3)
        f.axis(axis)
        assertTrue(axis.toList().toString(), axis[0] > 0.9f)
    }

    @Test
    fun disagreeingContradictionsSuspendUntilAConsistentLesson() {
        var (f, t) = learned()
        var kmh = 36.2f
        for (h in listOf(floatArrayOf(1.5f, 0f, 0f), floatArrayOf(-1.5f, 0f, 0f), floatArrayOf(0f, 0f, 1.5f))) {
            t += 1_000; kmh += 5.4f
            f.interval(h, kmh, t)
        }
        assertEquals(CalibrationState.SUSPENDED, f.state)
        assertEquals(CalibrationReason.SENSOR_DATA_INCONSISTENT, f.suspendReason)
        t += 1_000; kmh += 5.4f
        f.interval(floatArrayOf(0f, 0f, -1.5f), kmh, t)
        assertEquals(CalibrationState.READY, f.state)
        assertEquals(CalibrationReason.CONSISTENT_AGAIN, f.lastReason)
    }

    @Test
    fun accelerationsThatDoNotFitTheGpsSuspend() {
        var (f, t) = learned()
        var kmh = 36.2f
        repeat(3) {
            t += 1_000; kmh += 5.4f
            f.interval(floatArrayOf(0f, 0f, -9f), kmh, t)    // 9 m/s² felt, 1.5 m/s² driven
        }
        assertEquals(CalibrationState.SUSPENDED, f.state)
        assertEquals(CalibrationReason.SENSOR_DATA_INCONSISTENT, f.lastReason)
    }

    @Test
    fun stopsGpsGapsAndBendsTeachNothingAndForgetNothing() {
        var (f, t) = learned()
        val lessons = f.lessons
        t += 1_000
        f.interval(floatArrayOf(0f, 0f, 0f), 36.2f, t)          // steady
        t += 1_000
        f.interval(floatArrayOf(2f, 0f, -1.5f), 41.6f, t, yaw = 0.3f)   // in a bend
        f.onGpsGap()
        t += 60_000
        f.interval(floatArrayOf(0f, 0f, 1.5f), 0f, t)           // after a long gap: no interval
        assertEquals(lessons, f.lessons)
        assertEquals(CalibrationState.READY, f.state)
    }

    @Test
    fun handlingSuspendsAndTheOrientationDecides() {
        val (f, _) = learned()
        f.suspendForPhoneMotion()
        assertEquals(CalibrationState.SUSPENDED, f.state)
        f.onGravity(0.5f, 9.8f, 0.3f)                          // put back almost as it was (≈ 3°)
        f.onPhoneStable()
        assertEquals(CalibrationState.READY, f.state)

        f.suspendForPhoneMotion()
        f.onGravity(9.81f, 0f, 0f)                             // now landscape
        f.onPhoneStable()
        assertEquals(CalibrationState.UNCALIBRATED, f.state)
        assertEquals(CalibrationReason.ORIENTATION_CHANGED, f.lastReason)

        val (g, _) = learned()
        g.suspendForPhoneMotion()
        g.onPhoneStable(turnedAroundVertical = true)
        assertEquals("a swivel gravity cannot see", CalibrationState.UNCALIBRATED, g.state)
    }

    // ---- PhoneMotionDetector

    @Test
    fun plausibleYawFollowsTheSpeed() {
        assertEquals(PhoneMotionDetector.MIN_PLAUSIBLE_YAW, PhoneMotionDetector.plausibleYaw(0f))
        // a sharp car turn at 30 km/h (0.54 rad/s, 4.5 m/s²) is plausible...
        assertTrue(0.54f < PhoneMotionDetector.plausibleYaw(30f))
        // ...the phone spun at 1.5 rad/s at 50 km/h (≈ 21 m/s²) is not
        assertTrue(1.5f > PhoneMotionDetector.plausibleYaw(50f))
        assertTrue(PhoneMotionDetector.plausibleYaw(120f) >= PhoneMotionDetector.MIN_PLAUSIBLE_YAW)
    }

    @Test
    fun thePhoneIsStillAgainAfterAQuietMoment() {
        val p = PhoneMotionDetector()
        val up = floatArrayOf(0f, 1f, 0f)
        var t = 0L
        p.onGravity(up, t)
        repeat(30) { t += 20; p.onGyro(1.5f, 0f, 0f, up, 40f, t) }    // tilting fast for 0.6 s
        assertTrue(p.isMoving)
        repeat(50) { t += 20; p.onGyro(0f, 0f, 0f, up, 40f, t) }
        assertTrue("not still yet", p.isMoving)
        repeat(150) { t += 20; p.onGyro(0f, 0f, 0f, up, 40f, t) }
        assertFalse(p.isMoving)
    }

    // ---- MovementStabilizer

    @Test
    fun movementHasHysteresis() {
        val m = MovementStabilizer()
        assertFalse(m.onFix(3f))
        assertFalse(m.isMoving)
        assertTrue(m.onFix(5f))
        assertFalse(m.onFix(3f))                                  // between: holds
        assertFalse(m.onFix(1f))                                  // one standing fix: holds
        assertTrue(m.isMoving)
        assertFalse(m.onFix(4f))
        assertFalse(m.onFix(1f))
        assertTrue(m.onFix(0f))
        assertFalse(m.isMoving)
    }

    // ---- SensorClock

    @Test
    fun sensorTimestampsMapOntoTheGpsClock() {
        val c = SensorClock()
        // the sensor clock runs 3 s ahead of elapsedRealtime; events arrive 5–30 ms late
        assertEquals(1_000L, c.toElapsedMs(4_000_000_000L, 1_010L) - 10L)
        val a = c.toElapsedMs(4_020_000_000L, 1_025L)
        val b = c.toElapsedMs(4_040_000_000L, 1_075L)              // a late (batched) delivery
        assertEquals("durations keep the sensor's precision", 20L, b - a)
    }
}
