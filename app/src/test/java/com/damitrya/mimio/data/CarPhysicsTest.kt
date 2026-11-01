package com.damitrya.mimio.data

import com.damitrya.mimio.diagnostics.DiagnosticReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 7 car physics on synthetic sensor traces ([CarSim]): which manoeuvres become leans, in which mounts, and what
 * handling the phone, vibrations and GPS gaps do. Numbers from the drive of 26 October (Motorola Edge 70).
 */
class CarPhysicsTest {

    private fun sim(mount: Array<FloatArray> = Mounts.UPRIGHT, withBrake: Boolean = false, seed: Int = 7): CarSim {
        val tracker = CarMotionTracker()
        val brake = if (withBrake) BrakeDetector(-4f, tracker.frame) else null
        return CarSim(tracker, mount, brake, seed).also { it.tracker.phone.reset() }
    }

    private fun CarSim.calibrate() {
        calibrateOnStraightRoad()
        assertEquals("calibrated", CalibrationState.READY, tracker.frame.state)
    }

    private fun CarSim.kinds() = events.map { it.kind to it.level }

    // ---- 1–3: turns

    @Test
    fun smoothCityTurnLeansOnce() {
        val s = sim()
        s.calibrate()
        s.cruise(1_000L, 25f)
        s.turn(4_000L, 1.8f)                 // left turn, 25 km/h, 1.8 m/s²
        s.cruise(2_000L, 25f)
        assertEquals(listOf(InertiaKind.TURN_LEFT to InertiaLevel.SOFT), s.kinds())
        assertTrue(s.sharpTurns.isEmpty())
        s.turn(4_000L, -1.5f)                // right turn
        s.cruise(1_000L, 25f)
        assertEquals(InertiaKind.TURN_RIGHT, s.events.last().kind)
        assertEquals(2, s.events.size)
    }

    @Test
    fun calibrationIsNotNeededForTurns() {
        val s = sim()
        s.cruise(3_000L, 20f)
        assertNotEquals(CalibrationState.READY, s.tracker.frame.state)
        s.turn(3_000L, 1.6f)
        assertEquals(listOf(InertiaKind.TURN_LEFT to InertiaLevel.SOFT), s.kinds())
    }

    @Test
    fun highwayBendWithModerateLateralAcceleration() {
        val s = sim()
        s.calibrate()
        s.cruise(2_000L, 70f)
        s.turn(6_000L, 1.6f, rampMs = 1_500L)  // 70 km/h: only ≈ 0.08 rad/s of yaw
        s.cruise(2_000L, 70f)
        assertEquals(listOf(InertiaKind.TURN_LEFT to InertiaLevel.SOFT), s.kinds())
        assertFalse("a fast car turning is not the phone", s.tracker.phone.isMoving)
    }

    @Test
    fun sharpTurnPlaysTheTurnReactionNotALean() {
        val s = sim()
        s.calibrate()
        s.cruise(1_000L, 40f)
        s.turn(3_000L, -3.8f, rampMs = 250L)  // quick, sharp right turn
        s.cruise(1_000L, 40f)
        assertEquals(listOf(TurnDirection.RIGHT), s.sharpTurns)
        assertTrue("one strong manoeuvre, no soft lean before it: ${s.kinds()}", s.events.isEmpty())
        assertFalse(s.tracker.phone.isMoving)
    }

    @Test
    fun aSlowlyTighteningTurnLeansThenTurnsSharply() {
        val s = sim()
        s.calibrate()
        s.cruise(1_000L, 40f)
        s.turn(5_000L, 3.6f, rampMs = 2_500L)
        assertEquals(listOf(InertiaKind.TURN_LEFT to InertiaLevel.SOFT), s.kinds())
        assertEquals(listOf(TurnDirection.LEFT), s.sharpTurns)
    }

    @Test
    fun aLongBendIsOneReaction() {
        val s = sim()
        s.calibrate()
        s.cruise(1_000L, 50f)
        s.turn(25_000L, 2.0f, rampMs = 1_000L)   // a long motorway ramp
        assertEquals(1, s.events.size)
    }

    @Test
    fun aShortSwerveIsTooShort() {
        val s = sim()
        s.calibrate()
        s.cruise(1_000L, 40f)
        s.outcomes.clear()
        s.turn(450L, 2.4f, rampMs = 100L)   // above 1.2 m/s² for ≈ 250 ms only
        s.cruise(2_000L, 40f)
        assertTrue(s.events.isEmpty())
        assertEquals(listOf(InertiaKind.TURN_LEFT to DiagnosticReason.EVENT_TOO_SHORT), s.outcomes.map { it.kind to it.reason })
    }

    // ---- 4–8: braking and speeding up

    @Test
    fun smoothBrakingLeansForward() {
        val s = sim()
        s.calibrate()
        s.cruise(2_000L, 50f)
        s.changeSpeed(25f, 1.8f)
        s.cruise(2_000L, 25f)
        assertEquals(listOf(InertiaKind.BRAKE to InertiaLevel.SOFT), s.kinds())
    }

    @Test
    fun intensiveBrakingIsStrongButNoAlert() {
        val s = sim(withBrake = true)
        s.calibrate()
        s.cruise(2_000L, 60f)
        s.changeSpeed(30f, 3.0f)
        s.cruise(2_000L, 30f)
        // The braking builds up: the lean starts as an ordinary one and the same manoeuvre turns intensive (once).
        assertEquals(listOf(InertiaKind.BRAKE to InertiaLevel.SOFT, InertiaKind.BRAKE to InertiaLevel.STRONG), s.kinds())
        assertTrue(s.events.last().upgrade)
        assertTrue("−3 m/s² is not an emergency", s.alerts.isEmpty())
    }

    @Test
    fun emergencyBrakingStillRaisesTheAlert() {
        val s = sim(withBrake = true)
        s.calibrate()
        s.cruise(2_000L, 70f)
        s.changeSpeed(10f, 6.0f)
        s.cruise(1_000L, 10f)
        assertTrue("ALERT through the shared car axis", s.alerts.isNotEmpty())
        assertEquals(BrakeDetector.Check.AXIS, s.brake!!.lastCheck)
    }

    @Test
    fun startingOffLeansBackOnceTheCarHasSpeed() {
        val s = sim()
        s.calibrate()
        s.changeSpeed(0f, 1.5f)
        s.drive(20_000L)                      // standing at a light; the axis is kept
        assertEquals(CalibrationState.READY, s.tracker.frame.state)
        s.events.clear()
        s.changeSpeed(40f, 1.8f)
        s.cruise(1_000L, 40f)
        assertEquals(listOf(InertiaKind.ACCELERATE to InertiaLevel.SOFT), s.kinds())
        assertTrue("played above the minimum speed", s.events[0].speedKmh >= 8f)
    }

    @Test
    fun intensiveStartIsStrong() {
        val s = sim()
        s.calibrate()
        s.cruise(1_000L, 20f)
        s.changeSpeed(70f, 3.2f)
        s.cruise(1_000L, 70f)
        assertEquals(listOf(InertiaKind.ACCELERATE to InertiaLevel.STRONG), s.kinds())
    }

    @Test
    fun noLongitudinalLeansBeforeTheAxisIsKnown() {
        val s = sim()
        s.cruise(3_000L, 40f)                 // the app started mid-drive: nothing learned yet
        s.changeSpeed(22f, 1.9f)
        s.cruise(1_000L, 22f)
        assertTrue("no lean without a trusted axis: ${s.kinds()}", s.events.isEmpty())
        assertTrue(s.outcomes.toString(), s.outcomes.all { it.reason == DiagnosticReason.CALIBRATION_UNRELIABLE })
    }

    // ---- 9–11: mounts and moving the phone

    @Test
    fun everyMountGivesTheSameLeans() {
        for ((name, mount) in listOf(
            "upright" to Mounts.UPRIGHT, "landscape" to Mounts.LANDSCAPE, "flat" to Mounts.FLAT,
            "sideways" to Mounts.SIDEWAYS, "tilted back 25°" to Mounts.tilted(Mounts.UPRIGHT, 25.0)
        )) {
            val s = sim(mount)
            s.calibrate()
            s.cruise(1_000L, 40f)
            s.changeSpeed(20f, 1.9f)
            s.cruise(2_000L, 20f)
            s.turn(3_000L, -1.7f)
            s.cruise(1_000L, 20f)
            s.changeSpeed(45f, 1.7f)
            s.cruise(1_000L, 45f)
            assertEquals(
                name,
                listOf(
                    InertiaKind.BRAKE to InertiaLevel.SOFT,
                    InertiaKind.TURN_RIGHT to InertiaLevel.SOFT,
                    InertiaKind.ACCELERATE to InertiaLevel.SOFT
                ),
                s.kinds()
            )
        }
    }

    @Test
    fun phoneTurnedToLandscapeDuringTheDriveRelearns() {
        val s = sim()
        s.calibrate()
        s.cruise(1_000L, 40f)
        val transitions = mutableListOf<Pair<CalibrationState, CalibrationReason>>()
        s.tracker.frame.onTransition = { _, to, reason -> transitions += to to reason }
        s.handle(1_000L, floatArrayOf(0f, 0f, 1f), 1.57f)   // portrait → landscape in the holder
        s.cruise(3_000L, 40f)
        assertTrue(transitions.toString(), transitions.contains(CalibrationState.SUSPENDED to CalibrationReason.PHONE_MOVING))
        assertTrue(transitions.toString(), transitions.any { it.second == CalibrationReason.ORIENTATION_CHANGED })
        assertTrue(s.events.isEmpty())
        // the next speed changes teach the new axis
        s.changeSpeed(60f, 1.6f)
        s.changeSpeed(40f, 1.5f)
        assertEquals(CalibrationState.READY, s.tracker.frame.state)
        s.events.clear()
        s.cruise(1_000L, 40f)
        s.changeSpeed(20f, 1.9f)
        s.cruise(1_000L, 20f)
        assertEquals(listOf(InertiaKind.BRAKE to InertiaLevel.SOFT), s.kinds())
    }

    @Test
    fun pickedUpAndPutBackKeepsTheAxis() {
        val s = sim()
        s.calibrate()
        s.cruise(1_000L, 40f)
        val before = FloatArray(3).also { s.tracker.frame.axis(it) }
        s.pickUpAndPutBack()
        assertEquals(CalibrationState.READY, s.tracker.frame.state)
        assertEquals(CalibrationReason.PHONE_STABLE_SAME_ORIENTATION, s.tracker.frame.lastReason)
        val after = FloatArray(3).also { s.tracker.frame.axis(it) }
        assertTrue((0..2).sumOf { (before[it] * after[it]).toDouble() } > 0.95)
    }

    @Test
    fun holderSwivelledAroundTheVerticalIsFoundByTheGps() {
        // Gravity is the same after the swivel: only the GPS lessons can tell. The axis is replaced, not averaged.
        val tracker = CarMotionTracker()
        val s = CarSim(tracker, Mounts.UPRIGHT)
        s.calibrate()
        val swivelled = CarSim(tracker, Mounts.SIDEWAYS)
        swivelled.speedMps = s.speedMps
        repeat(3) {
            swivelled.changeSpeed(55f, 1.6f)
            swivelled.changeSpeed(30f, 1.5f)
        }
        assertEquals(CalibrationState.READY, tracker.frame.state)
        swivelled.events.clear()
        swivelled.cruise(1_000L, 30f)
        swivelled.changeSpeed(50f, 1.7f)
        swivelled.cruise(1_000L, 50f)
        assertEquals(listOf(InertiaKind.ACCELERATE to InertiaLevel.SOFT), swivelled.kinds())
    }

    // ---- 12–14: the phone in the hand, rough roads

    @Test
    fun phoneInTheHandWhileParkedDoesNothing() {
        val s = sim(withBrake = true)
        s.calibrate()
        s.changeSpeed(0f, 1.5f)
        s.drive(3_000L)
        s.events.clear()
        s.handle(1_500L, floatArrayOf(1f, 0.3f, 0f), 2.0f)
        s.handle(1_500L, floatArrayOf(0f, 0f, 1f), -2.5f)   // spun around the vertical in the hand
        s.handle(1_500L, floatArrayOf(0.2f, 1f, 0f), 1.5f)
        s.drive(3_000L)
        assertTrue(s.events.isEmpty())
        assertTrue(s.sharpTurns.isEmpty())
        assertTrue(s.alerts.isEmpty())
    }

    @Test
    fun phoneInTheHandWhileDrivingSuspendsTheLeans() {
        val s = sim(withBrake = true)
        s.calibrate()
        s.cruise(2_000L, 50f)
        var phoneSeen = false
        s.handle(3_000L, floatArrayOf(1f, 0.5f, 0.2f), 1.8f) {
            if (s.tracker.phone.isMoving) phoneSeen = true
            0f to 0f
        }
        assertTrue(phoneSeen)
        assertEquals(CalibrationState.SUSPENDED, s.tracker.frame.state)
        assertEquals(DiagnosticReason.PHONE_MOVING, s.tracker.gate(InertiaKind.BRAKE, InertiaLevel.SOFT))
        assertTrue("no lean from the hand: ${s.kinds()}", s.events.isEmpty())
        assertTrue(s.sharpTurns.isEmpty())
        assertTrue("no alert from the hand", s.alerts.isEmpty())
    }

    @Test
    fun handlingInAnyMountAndDirectionMakesNoCarReaction() {
        val axes = listOf(floatArrayOf(1f, 0.5f, 0.2f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0.3f, 0.2f, 1f), floatArrayOf(-1f, 1f, 0.5f))
        for ((name, mount) in listOf("upright" to Mounts.UPRIGHT, "landscape" to Mounts.LANDSCAPE, "flat" to Mounts.FLAT)) {
            for ((i, axis) in axes.withIndex()) {
                val s = sim(mount, withBrake = true, seed = i + 3)
                s.calibrate()
                s.cruise(2_000L, 50f)
                s.handle(2_000L, axis, 1.6f)
                s.handle(1_500L, axis, -1.2f)
                s.cruise(1_000L, 50f)
                val what = "$name, axis ${axis.toList()}"
                assertTrue("$what: ${s.kinds()}", s.events.isEmpty())
                assertTrue("$what: ${s.sharpTurns}", s.sharpTurns.isEmpty())
                assertTrue(what, s.alerts.isEmpty())
            }
        }
    }

    @Test
    fun aYawWithoutCentripetalAccelerationIsNoTurn() {
        // The phone swivelled around the vertical in the holder at a speed a car could turn: only the accelerometer
        // can tell that the car itself goes straight on.
        val s = sim()
        s.calibrate()
        s.cruise(2_000L, 50f)
        s.handle(1_500L, floatArrayOf(0f, 1f, 0f), 0.45f) { 0f to 0f }
        assertTrue(s.kinds().toString(), s.events.isEmpty())
        assertTrue(s.sharpTurns.isEmpty())
        assertTrue(s.outcomes.toString(), s.outcomes.any { it.reason == DiagnosticReason.SENSOR_DATA_INCONSISTENT || it.reason == DiagnosticReason.PHONE_MOVING })
    }

    @Test
    fun aSharpCarTurnIsNotThePhone() {
        val s = sim()
        s.calibrate()
        s.cruise(1_000L, 30f)
        s.turn(4_000L, 4.5f, rampMs = 300L)   // 0.54 rad/s at 30 km/h: plausible for a car
        assertFalse(s.tracker.phone.isMoving)
        assertEquals(listOf(TurnDirection.LEFT), s.sharpTurns)
    }

    @Test
    fun roughRoadVibrationsMakeNoLeans() {
        val s = sim()
        s.calibrate()
        s.vibration = 1.6f            // potholes, cobbles: ±1.6 m/s² horizontal, ±4.8 vertical
        s.vibrationGyro = 0.25f
        s.cruise(60_000L, 50f)
        assertTrue(s.kinds().toString(), s.events.isEmpty())
        assertFalse(s.tracker.phone.isMoving)
        assertEquals(CalibrationState.READY, s.tracker.frame.state)
    }

    // ---- 15–18: GPS and stops

    @Test
    fun shortGpsLossKeepsTheCalibration() {
        val s = sim()
        s.calibrate()
        s.cruise(1_000L, 40f)
        s.gps = false
        s.cruise(5_000L, 40f)                 // tunnel
        s.turn(3_000L, 1.8f)
        assertTrue("speed unknown in the tunnel: no lean", s.events.isEmpty())
        s.gps = true
        s.cruise(2_000L, 40f)
        assertEquals(CalibrationState.READY, s.tracker.frame.state)
        s.changeSpeed(20f, 1.9f)
        s.cruise(1_000L, 20f)
        assertEquals(listOf(InertiaKind.BRAKE to InertiaLevel.SOFT), s.kinds())
    }

    @Test
    fun longGpsLossKeepsTheCalibrationToo() {
        val s = sim()
        s.calibrate()
        s.cruise(1_000L, 40f)
        s.gps = false
        s.cruise(120_000L, 40f)
        s.changeSpeed(20f, 1.9f)
        assertTrue(s.events.isEmpty())
        assertEquals(DiagnosticReason.GPS_STALE, s.tracker.gate(InertiaKind.BRAKE, InertiaLevel.SOFT))
        s.gps = true
        s.cruise(2_000L, 20f)
        assertEquals(CalibrationState.READY, s.tracker.frame.state)
    }

    @Test
    fun trafficLightAndJamKeepTheCalibrationAndMakeNoLeans() {
        val s = sim()
        s.calibrate()
        s.changeSpeed(0f, 1.6f)
        s.events.clear()
        s.drive(45_000L)                        // red light
        repeat(20) {                            // jam: 0–5 km/h
            s.changeSpeed(5f, 0.8f)
            s.changeSpeed(1f, 0.8f)
        }
        assertTrue(s.kinds().toString(), s.events.isEmpty())
        assertEquals(CalibrationState.READY, s.tracker.frame.state)
    }

    // ---- 24: nothing happens

    @Test
    fun aStillPhoneInAStandingCarStaysQuiet() {
        val s = sim(withBrake = true)
        s.vibration = 0.05f
        s.drive(10 * 60_000L)
        assertTrue(s.events.isEmpty())
        assertTrue(s.sharpTurns.isEmpty())
        assertTrue(s.alerts.isEmpty())
        assertFalse(s.tracker.phone.isMoving)
        assertEquals(CalibrationState.UNCALIBRATED, s.tracker.frame.state)
    }
}
