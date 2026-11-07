package com.damitrya.mimio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 8: the calibration of the car's axis keeps what it learned through ordinary driving. Built after the drive of
 * 7 November (Motorola Edge 70, Android 16): 23 × READY → SUSPENDED SENSOR_DATA_INCONSISTENT, 97 × SUSPENDED →
 * UNCALIBRATED "PHONE_STABLE_SAME_ORIENTATION", 118 PHONE_MOVING episodes.
 */
class CalibrationStabilityTest {

    // ---- VehicleFrameEstimator, one GPS interval at a time (upright phone: up = phone +y, forward = phone −z)

    private class Frame {
        val f = VehicleFrameEstimator()
        val transitions = mutableListOf<Triple<CalibrationState, CalibrationState, CalibrationReason>>()
        var t = 1_000L
        var kmh = 20f

        init {
            f.onGravity(0f, 9.81f, 0f)
            f.onTransition = { from, to, r -> transitions += Triple(from, to, r) }
            f.onSpeed(kmh, t)
        }

        /** One second of [h] (phone coordinates) at yaw rate [yaw], the GPS speed changing by [dKmh]. */
        fun interval(h: FloatArray, dKmh: Float, yaw: Float = 0f) {
            repeat(50) { f.onSample(h, yaw) }
            t += 1_000
            kmh += dKmh
            f.onSpeed(kmh, t)
        }

        fun learn(): Frame {
            repeat(3) { interval(SPEEDING_UP, 5.4f) }        // speeding up at 1.5 m/s²
            assertEquals(CalibrationState.READY, f.state)
            transitions.clear()
            return this
        }

        fun axis() = FloatArray(3).also { assertTrue(f.axis(it)) }
    }

    @Test
    fun aBendAtHighwaySpeedTeachesNothing() {
        // 0.10 rad/s is "straight" by the yaw limit alone, but at 90 km/h it is 2.5 m/s² of centripetal acceleration:
        // each of these intervals used to teach a direction 68° off the axis, and three of them suspended READY.
        val fr = Frame().learn()
        val lessons = fr.f.lessons
        fr.f.onGpsGap()
        fr.kmh = 90f
        fr.f.onSpeed(fr.kmh, fr.t)
        for (side in listOf(1f, -1f, 1f, -1f)) {
            fr.interval(floatArrayOf(2.5f * side, 0f, 1.0f), -3.6f, yaw = 0.10f)   // braking 1 m/s² in a bend
        }
        assertEquals(CalibrationState.READY, fr.f.state)
        assertEquals(lessons, fr.f.lessons)
        assertTrue(fr.transitions.toString(), fr.transitions.isEmpty())
    }

    @Test
    fun theGpsLaggingByAFixIsNoMismatch() {
        // The accelerometer feels the braking one interval before the GPS speed shows it.
        val fr = Frame().learn()
        val lessons = fr.f.lessons
        repeat(3) {
            fr.interval(BRAKING, -0.7f)                 // −0.2 m/s² by GPS: no lesson yet
            fr.interval(floatArrayOf(0f, 0f, 0f), -5.4f) // the GPS now says −1.5 m/s², the car already rolls
            fr.interval(floatArrayOf(0f, 0f, 0f), 0f)
        }
        assertEquals(CalibrationState.READY, fr.f.state)
        assertEquals("each lag tail was a lesson", lessons + 3, fr.f.lessons)
        assertTrue(fr.transitions.toString(), fr.transitions.isEmpty())
    }

    @Test
    fun scatteredMismatchesDoNotAddUpOverTheDrive() {
        val fr = Frame().learn()
        repeat(4) {
            fr.interval(floatArrayOf(0f, 0f, 9f), -5.4f)          // one interval that does not fit (9 vs 1.5 m/s²)
            repeat(40) { fr.interval(floatArrayOf(0f, 0f, 0f), 0f) }   // then 40 s of steady driving
        }
        assertEquals(CalibrationState.READY, fr.f.state)
        assertTrue(fr.transitions.toString(), fr.transitions.isEmpty())
    }

    @Test
    fun aRealDisagreementStillSuspendsAndAConsistentLessonResumes() {
        val fr = Frame().learn()
        repeat(4) { fr.interval(floatArrayOf(0f, 0f, 9f), -5.4f) }       // 9 m/s² felt, 1.5 m/s² driven, in a row
        assertEquals(CalibrationState.SUSPENDED, fr.f.state)
        assertEquals(CalibrationReason.SENSOR_DATA_INCONSISTENT, fr.f.suspendReason)
        val t = fr.f.lastTransition
        assertNotNull(t)
        assertTrue(t!!.detail, t.detail.contains("did not fit"))
        assertTrue("time in READY is known", t.timeInStateMs > 0L)
        fr.interval(floatArrayOf(0f, 0f, 0f), 0f)
        fr.interval(BRAKING, -5.4f)
        assertEquals(CalibrationState.READY, fr.f.state)
        assertEquals(CalibrationReason.CONSISTENT_AGAIN, fr.f.lastReason)
    }

    @Test
    fun inconsistentDataStayInconsistentAfterThePhoneWasHandled() {
        val fr = Frame().learn()
        repeat(4) { fr.interval(floatArrayOf(0f, 0f, 9f), -5.4f) }
        assertEquals(CalibrationReason.SENSOR_DATA_INCONSISTENT, fr.f.suspendReason)
        fr.f.suspendForPhoneMotion(fr.t)
        fr.f.onPhoneStable(now = fr.t + 2_000)
        assertEquals("not READY just because the phone is still", CalibrationState.SUSPENDED, fr.f.state)
        assertEquals(CalibrationReason.SENSOR_DATA_INCONSISTENT, fr.f.suspendReason)
        fr.t += 2_000
        fr.f.onSpeed(fr.kmh, fr.t)
        fr.interval(BRAKING, -5.4f)
        assertEquals(CalibrationState.READY, fr.f.state)
    }

    @Test
    fun handlingThePhoneWithoutAnAxisChangesNoState() {
        // Before: UNCALIBRATED → SUSPENDED (PHONE_MOVING) → UNCALIBRATED (PHONE_STABLE_SAME_ORIENTATION), 97 times.
        val fr = Frame()
        fr.f.suspendForPhoneMotion(fr.t)
        assertEquals(CalibrationState.UNCALIBRATED, fr.f.state)
        assertTrue(fr.f.isPhoneMoving)
        fr.interval(SPEEDING_UP, 5.4f)                     // no lesson while the phone moves
        assertEquals(0, fr.f.lessons)
        fr.f.onPhoneStable(now = fr.t)
        assertEquals(CalibrationState.UNCALIBRATED, fr.f.state)
        assertTrue(fr.transitions.toString(), fr.transitions.isEmpty())
        // ...and learning goes on afterwards
        fr.f.onSpeed(fr.kmh, fr.t + 1_000); fr.t += 1_000
        repeat(3) { fr.interval(SPEEDING_UP, 5.4f) }
        assertEquals(CalibrationState.READY, fr.f.state)
    }

    @Test
    fun aRotationSeenWhileHandledKeepsTheAxisUntilTheGpsDecides() {
        // Confirmed: the car turned while the phone was picked up (the gyroscope cannot tell the two apart).
        val fr = Frame().learn()
        val lessons = fr.f.lessons
        val before = fr.axis()
        fr.f.suspendForPhoneMotion(fr.t)
        fr.f.onPhoneStable(turnedAroundVertical = true, now = fr.t + 2_000)
        assertEquals(CalibrationState.SUSPENDED, fr.f.state)
        assertEquals(CalibrationReason.AXIS_UNCONFIRMED, fr.f.suspendReason)
        assertEquals(lessons, fr.f.lessons)
        fr.t += 2_000
        fr.f.onSpeed(fr.kmh, fr.t)
        fr.interval(BRAKING, -5.4f)
        assertEquals(CalibrationState.READY, fr.f.state)
        assertEquals(CalibrationReason.AXIS_CONFIRMED, fr.f.lastReason)
        assertTrue("the axis was kept", dot(before, fr.axis()) > 0.95f)
        assertTrue(fr.f.lessons > lessons)

        // Refuted: the holder really was swivelled by 90° — the first lesson says so, and the axis is learned anew.
        val sw = Frame().learn()
        sw.f.suspendForPhoneMotion(sw.t)
        sw.f.onPhoneStable(turnedAroundVertical = true, now = sw.t + 2_000)
        sw.t += 2_000
        sw.f.onSpeed(sw.kmh, sw.t)
        sw.interval(floatArrayOf(1.5f, 0f, 0f), 5.4f)                  // forward is now phone +x
        assertTrue(sw.transitions.toString(), sw.transitions.any { it.third == CalibrationReason.HOLDER_ROTATED })
        assertFalse("no reactions on the old axis", sw.f.isReady)
        repeat(2) { sw.interval(floatArrayOf(1.5f, 0f, 0f), 5.4f) }
        assertEquals(CalibrationState.READY, sw.f.state)
        assertTrue(sw.axis()[0] > 0.9f)
    }

    @Test
    fun aRealTiltChangeStillRelearnsAtOnce() {
        val fr = Frame().learn()
        fr.f.suspendForPhoneMotion(fr.t)
        fr.f.onGravity(9.81f, 0f, 0f)                                  // now landscape
        fr.f.onPhoneStable(now = fr.t + 2_000)
        assertEquals(CalibrationState.UNCALIBRATED, fr.f.state)
        assertEquals(CalibrationReason.ORIENTATION_CHANGED, fr.f.lastReason)
        val t = fr.f.lastTransition!!
        assertTrue(t.detail, t.detail.contains("tilt changed by 90°"))
    }

    // ---- whole traces

    private fun sim(mount: Array<FloatArray> = Mounts.UPRIGHT, seed: Int = 7): CarSim {
        val tracker = CarMotionTracker()
        return CarSim(tracker, mount, BrakeDetector(-4f, tracker.frame), seed)
    }

    private fun CarSim.record(): MutableList<Pair<CalibrationState, CalibrationReason>> {
        val list = mutableListOf<Pair<CalibrationState, CalibrationReason>>()
        tracker.frame.onTransition = { _, to, r -> list += to to r }
        return list
    }

    @Test
    fun aStillPhoneCalibratesOnTheFirstStraightSpeedChanges() {
        for ((name, mount) in listOf("upright" to Mounts.UPRIGHT, "landscape" to Mounts.LANDSCAPE, "flat" to Mounts.FLAT)) {
            val s = sim(mount)
            val transitions = s.record()
            s.calibrateOnStraightRoad()
            assertEquals(name, CalibrationState.READY, s.tracker.frame.state)
            assertEquals(
                name,
                listOf(CalibrationState.LEARNING to CalibrationReason.LEARNING_STARTED, CalibrationState.READY to CalibrationReason.LEARNED),
                transitions
            )
            assertTrue(s.tracker.frame.quality >= VehicleFrameEstimator.READY_QUALITY)
        }
    }

    @Test
    fun aHighwayWithGentleBendsKeepsTheCalibration() {
        val s = sim()
        s.calibrateOnStraightRoad()
        val transitions = s.record()
        s.changeSpeed(85f, 1.2f)
        repeat(6) { i ->
            val side = if (i % 2 == 0) 1f else -1f
            s.drive(4_000L, -0.9f, 0.09f * side)        // easing off in a bend: ≈ 2 m/s² of centripetal acceleration
            s.drive(4_000L, 0.9f, -0.09f * side)
            s.cruise(5_000L, 85f)
        }
        assertEquals(transitions.toString(), CalibrationState.READY, s.tracker.frame.state)
        assertFalse(transitions.toString(), transitions.any { it.second == CalibrationReason.SENSOR_DATA_INCONSISTENT })
    }

    @Test
    fun pickedUpWhileTheCarTurnsKeepsTheAxis() {
        // A turn while the phone is handled used to count as the phone swivelling (> 20° around the vertical):
        // ORIENTATION_CHANGED, the axis erased, three new lessons needed.
        val s = sim()
        s.calibrateOnStraightRoad()
        s.cruise(2_000L, 30f, yaw = 0.3f)
        val transitions = s.record()
        val lessons = s.tracker.frame.lessons
        s.handle(800L, floatArrayOf(1f, 0f, 0f), 1.2f) { 0f to 0.3f }
        s.handle(800L, floatArrayOf(1f, 0f, 0f), -1.2f) { 0f to 0.3f }
        s.cruise(2_500L, 30f, yaw = 0.3f)
        s.cruise(2_000L, 30f)
        assertTrue(transitions.toString(), transitions.contains(CalibrationState.SUSPENDED to CalibrationReason.AXIS_UNCONFIRMED))
        assertFalse(transitions.toString(), transitions.any { it.second == CalibrationReason.ORIENTATION_CHANGED })
        assertFalse(transitions.toString(), transitions.any { it.first == CalibrationState.UNCALIBRATED })
        s.events.clear()
        s.changeSpeed(15f, 1.6f)                              // the first braking confirms the kept axis...
        assertEquals(CalibrationState.READY, s.tracker.frame.state)
        assertEquals(CalibrationReason.AXIS_CONFIRMED, transitions.last { it.first == CalibrationState.READY }.second)
        assertTrue(s.tracker.frame.lessons > lessons)
        s.events.clear()
        s.cruise(2_000L, 15f)
        s.changeSpeed(40f, 1.6f)                              // ...and the leans are back
        s.cruise(1_000L, 40f)
        assertEquals(listOf(InertiaKind.ACCELERATE), s.events.map { it.kind })
    }

    @Test
    fun aPhoneSwivelledInTheHolderRelearnsWithoutWrongLeans() {
        val s = sim()
        s.calibrateOnStraightRoad()
        s.cruise(2_000L, 40f)
        val transitions = s.record()
        s.handle(1_000L, floatArrayOf(0f, 1f, 0f), 1.57f)    // 90° around the vertical (phone y is up)
        s.cruise(3_000L, 40f)
        assertTrue(transitions.toString(), transitions.contains(CalibrationState.SUSPENDED to CalibrationReason.AXIS_UNCONFIRMED))
        repeat(3) {
            s.changeSpeed(60f, 1.6f)
            s.cruise(1_000L, 60f)
            s.changeSpeed(40f, 1.5f)
            s.cruise(1_000L, 40f)
        }
        assertTrue(transitions.toString(), transitions.any { it.second == CalibrationReason.HOLDER_ROTATED })
        assertEquals(CalibrationState.READY, s.tracker.frame.state)
        val beforeReady = s.outcomes.filter { it.accepted && (it.kind == InertiaKind.BRAKE || it.kind == InertiaKind.ACCELERATE) }
        // every longitudinal lean that played went the right way (none on the stale axis)
        s.events.clear()
        s.changeSpeed(20f, 1.8f)
        s.cruise(1_000L, 20f)
        assertEquals(listOf(InertiaKind.BRAKE), s.events.map { it.kind })
        assertTrue(beforeReady.toString(), beforeReady.all { it.calibration == CalibrationState.READY })
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private companion object {
        /** Speeding up at 1.5 m/s² as an upright phone feels it: the car's forward is phone −z... */
        val SPEEDING_UP = floatArrayOf(0f, 0f, -1.5f)

        /** ...and braking: the acceleration points backward, phone +z. */
        val BRAKING = floatArrayOf(0f, 0f, 1.5f)
    }
}
