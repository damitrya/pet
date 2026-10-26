package com.damitrya.mimio.diagnostics

import com.damitrya.mimio.data.BrakeDetector
import com.damitrya.mimio.data.DrivingData
import com.damitrya.mimio.data.GpsFixGate
import com.damitrya.mimio.state.CompanionState
import com.damitrya.mimio.state.TripEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingDiagnosticsRecorderTest {

    private val storage = MemoryDiagnosticsStorage()
    private val clock = TestClock()
    private var wall = 1_700_000_000_000L
    private var limitHits = 0

    private fun recorder(limit: Long = DrivingDiagnosticsRecorder.LIMIT_BYTES) = DrivingDiagnosticsRecorder(
        storage, DirectExecutor, clock, { wall }, limit, onLimitReached = { limitHits++ }
    )

    private var seq = 0L
    private fun fix(kmh: Float = 50f, accuracyM: Float = 5f, ageMs: Long = 0L, hasSpeed: Boolean = true, speedAcc: Float = 1.2f) =
        DrivingData(
            speedKmh = kmh, hasGpsFix = true, fixSeq = ++seq, fixAgeMs = ageMs, accuracyM = accuracyM,
            hasSpeed = hasSpeed, speedAccuracyMps = speedAcc, rawSpeedKmh = kmh, provider = "gps"
        )

    /** The service's wiring: every new fix goes through a real GpsFixGate, which reports its verdict. */
    private fun gate(rec: DrivingDiagnosticsRecorder) = GpsFixGate().apply { onVerdict = rec::onGpsVerdict }

    private val journal get() = storage.journal.toString()

    @Test
    fun startAndStop() {
        val rec = recorder()
        assertFalse(rec.isRunning)
        rec.begin(null, "AUTO", -4f)
        assertTrue(rec.isRunning)
        assertEquals(DiagnosticsStatus.RECORDING, rec.snapshot().status)
        assertTrue(journal.contains("SESSION_START"))
        assertTrue(journal.contains("profile=AUTO"))
        assertEquals("a new recording clears the old files", 1, storage.clears)

        wall += 60_000
        rec.stop()
        assertFalse(rec.isRunning)
        val saved = storage.savedSummary()
        assertEquals(DiagnosticsStatus.STOPPED, saved.status)
        assertEquals(wall, saved.endedAt)
        assertTrue(journal.trimEnd().endsWith("cause=USER"))

        // Hooks after the stop write nothing.
        val before = journal.length
        gate(rec).isNewUsableFix(fix())
        rec.onTurnSample(1f, 9f, 60f, 3.5f, turning = true, hasGps = true)
        assertEquals(before, journal.length)
    }

    @Test
    fun serviceStopPausesAndTheNextStartContinues() {
        val rec = recorder()
        rec.begin(null, "AUTO", -4f)
        val g = gate(rec)
        repeat(3) { g.isNewUsableFix(fix()) }
        rec.pause()
        val paused = storage.savedSummary()
        assertEquals(DiagnosticsStatus.WAITING, paused.status)
        assertTrue(journal.contains("SESSION_PAUSED"))

        val next = recorder()
        next.begin(paused, "AUTO", -4f)
        assertEquals("nothing cleared on resume", 1, storage.clears)
        assertTrue(journal.contains("SESSION_RESUMED"))
        gate(next).isNewUsableFix(fix())
        assertEquals(4L, next.snapshot().gpsFixes)
        assertEquals(paused.startedAt, next.snapshot().startedAt)
    }

    @Test
    fun aStoppedRecordingIsNotContinued() {
        val rec = recorder()
        rec.begin(null, "AUTO", -4f)
        rec.stop()
        val next = recorder()
        next.begin(storage.savedSummary(), "AUTO", -4f)
        assertEquals(2, storage.clears)
        assertEquals(0L, next.snapshot().gpsFixes)
        assertFalse(journal.contains("SESSION_END"))
    }

    @Test
    fun gpsFixesAreAggregatedWithTheGateVerdicts() {
        val rec = recorder()
        rec.begin(null, "AUTO", -4f)
        val g = gate(rec)
        val good = fix(54.2f, accuracyM = 8f)
        assertTrue(g.isNewUsableFix(good))
        assertFalse("a sensor event repeating the fix", g.isNewUsableFix(good.copy(isTurning = true)))
        assertFalse(g.isNewUsableFix(fix(54f, accuracyM = 124.55f)))
        assertFalse(g.isNewUsableFix(fix(54f, ageMs = GpsFixGate.MAX_AGE_MS + 1)))
        assertTrue(g.isNewUsableFix(fix(40f, accuracyM = 0f)))     // accuracy unknown passes, as in the gate

        val s = rec.snapshot()
        assertEquals(4L, s.gpsFixes)
        assertEquals(2L, s.gpsAccepted)
        assertEquals(2L, s.gpsRejected)
        assertEquals(1L, s.gpsRejectReasons["LOCATION_INACCURATE"])
        assertEquals(1L, s.gpsRejectReasons["GPS_STALE"])
        assertEquals("the stale fix had 5 m", 5f, s.bestAccuracyM, 0.01f)
        assertEquals(124.55f, s.worstAccuracyM, 0.01f)
        assertEquals(40f, s.lastSpeedKmh, 0.01f)
        assertTrue(s.lastAccuracyM.isNaN())

        assertTrue(journal.contains("reason=LOCATION_INACCURATE"))
        assertTrue(journal.contains("reason=GPS_STALE"))
        assertTrue(journal.contains("speed=54.2 km/h"))
        assertTrue(journal.contains("speedAccuracy=1.2 m/s"))
        assertTrue(journal.contains("horizontalAccuracy=124."))
    }

    @Test
    fun acceptedFixesAreSampledRejectedOnesAllWritten() {
        val rec = recorder()
        rec.begin(null, "AUTO", -4f)
        val g = gate(rec)
        repeat(30) {
            g.isNewUsableFix(fix())
            clock.advance(1000)
        }
        repeat(5) {
            g.isNewUsableFix(fix(accuracyM = 80f))
            clock.advance(1000)
        }
        assertEquals(30L, rec.snapshot().gpsAccepted)
        val fixes = Regex("GPS_FIX").findAll(journal).count()
        assertTrue("accepted fixes every 10 s: $fixes", fixes in 3 + 5..5 + 5)
        assertEquals(5, Regex("reason=LOCATION_INACCURATE").findAll(journal).count())
    }

    @Test
    fun aFixWithoutSpeedIsAcceptedWithAWarning() {
        val rec = recorder()
        rec.begin(null, "AUTO", -4f)
        assertTrue(gate(rec).isNewUsableFix(fix(0f, hasSpeed = false, speedAcc = Float.NaN)))
        val s = rec.snapshot()
        assertEquals(1L, s.gpsWithoutSpeed)
        assertEquals(0L, s.gpsWithSpeedAccuracy)
        assertTrue(journal.contains("hasSpeed=false"))
        assertTrue(journal.contains("speedAccuracy=n/a"))
        assertTrue(journal.contains("accepted=true\n"))
        assertTrue(journal.contains("warning=SPEED_UNAVAILABLE"))
        assertFalse(journal.contains("reason=SPEED_UNAVAILABLE"))
    }

    @Test
    fun gpsLossIsReported() {
        val rec = recorder()
        rec.begin(null, "AUTO", -4f)
        rec.onDrivingData(fix())
        rec.onDrivingData(DrivingData(hasGpsFix = false))
        assertEquals(1L, rec.snapshot().gpsLost)
        assertTrue(journal.contains("GPS_LOST"))
        assertTrue(journal.contains("reason=GPS_MISSING"))
    }

    @Test
    fun unavailableSensorsAreReportedAndNothingBreaks() {
        val rec = recorder()
        rec.begin(null, "AUTO", -4f)
        rec.onSensorRegistration(SensorRegistration("SensorDataProvider", "LINEAR_ACCELERATION", available = false, registered = false))
        rec.onSensorRegistration(SensorRegistration("SensorDataProvider", "GYROSCOPE", available = true, registered = true))
        rec.onSensorRegistration(SensorRegistration("SensorDataProvider", "GRAVITY", available = true, registered = false))
        val s = rec.snapshot()
        assertEquals(false, s.sensorAvailable["LINEAR_ACCELERATION"])
        assertEquals(true, s.sensorAvailable["GYROSCOPE"])
        assertEquals(false, s.registrations["SensorDataProvider/GRAVITY"])
        assertEquals(2, Regex("reason=SENSOR_UNAVAILABLE").findAll(journal).count())

        // Only the gyroscope delivers samples: the statistics window says so, nothing crashes.
        repeat(60) {
            rec.onTurnSample(0.01f, 0.1f, 40f, 3.5f, turning = false, hasGps = true)
            clock.advance(200)
        }
        assertTrue(journal.contains("SENSOR_STATS"))
        assertTrue(journal.contains("linearAcceleration=no samples"))
        assertEquals(0L, rec.snapshot().brakeCandidates)
    }

    @Test
    fun brakeCandidateReasons() {
        val rec = recorder()
        rec.begin(null, "AUTO", -4f)
        fun episode(check: BrakeDetector.Check, value: Float, speed: Float, cal: BrakeDetector.Calibration, fired: Boolean = false) {
            rec.onBrakeSample(check, value, -4f, speed, cal, 3, fired)
            clock.advance(20)
            repeat(30) {
                rec.onBrakeSample(check, 0f, -4f, speed, cal, 3, false)
                clock.advance(20)
            }
        }
        episode(BrakeDetector.Check.AXIS, -3f, 50f, BrakeDetector.Calibration.READY)
        episode(BrakeDetector.Check.AXIS, -5f, 5f, BrakeDetector.Calibration.READY)
        episode(BrakeDetector.Check.JOLT, 5f, 50f, BrakeDetector.Calibration.LEARNING)
        episode(BrakeDetector.Check.AXIS, -5f, 50f, BrakeDetector.Calibration.READY, fired = true)
        episode(BrakeDetector.Check.AXIS, -1f, 50f, BrakeDetector.Calibration.READY)    // under 60 %: no candidate

        val s = rec.snapshot()
        assertEquals(4L, s.brakeCandidates)
        assertEquals(1L, s.brakeDetected)
        assertEquals(1L, s.brakeReasons["ACCELERATION_BELOW_THRESHOLD"])
        assertEquals(1L, s.brakeReasons["SPEED_BELOW_THRESHOLD"])
        assertEquals(1L, s.brakeReasons["CALIBRATION_PENDING"])
        assertEquals(-5f, s.peakBrakeAxis, 0.01f)
        assertTrue(journal.contains("acceleration=-5.0 m/s2\nthreshold=-4.0 m/s2\nspeed=50.0 km/h\ncalibration=READY\naccepted=true"))
    }

    @Test
    fun aRealBrakeDetectorFeedsTheRecorder() {
        val rec = recorder()
        rec.begin(null, "AUTO", -4f)
        val detector = BrakeDetector(-4f)
        var t = 0L
        // No GPS speed yet: the legacy phone-axis check; a strong jolt along Y fires.
        repeat(20) {
            val fired = detector.onLinearAcceleration(0f, -8f, 0f, t)
            rec.onBrakeSample(detector, fired)
            t += 20; clock.advance(20)
        }
        assertEquals(1L, rec.snapshot().brakeDetected)
        assertEquals("NO_GPS", rec.snapshot().calibration)

        // GPS speed arrives, the axis is not learned: a jolt waits for GPS confirmation.
        repeat(40) {
            rec.onBrakeSample(detector, detector.onLinearAcceleration(0f, 0f, 0f, t))
            t += 20; clock.advance(20)
        }
        rec.onBrakeSpeed(detector, detector.onSpeed(50f, t))
        assertTrue(rec.snapshot().calibration.startsWith("LEARNING"))
        repeat(20) {
            val fired = detector.onLinearAcceleration(0f, -8f, 0f, t)
            rec.onBrakeSample(detector, fired)
            t += 20; clock.advance(20)
        }
        repeat(30) {
            val fired = detector.onLinearAcceleration(0f, 0f, 0f, t)
            rec.onBrakeSample(detector, fired)
            t += 20; clock.advance(20)
        }
        assertEquals(1L, rec.snapshot().brakeReasons["CALIBRATION_PENDING"])
        // GPS confirms the slowdown: braking after all.
        rec.onBrakeSpeed(detector, detector.onSpeed(40f, t))
        assertEquals(2L, rec.snapshot().brakeDetected)
        assertEquals(1L, rec.snapshot().brakeConfirmedByGps)
        assertTrue(journal.contains("confirmedBy=GPS"))
    }

    @Test
    fun turnCandidateReasons() {
        val rec = recorder()
        rec.begin(null, "AUTO", -4f)
        fun quiet() = repeat(40) { rec.onTurnSample(0f, 0f, 50f, 3.5f, false, true); clock.advance(20) }
        // Yaw rate but too slow for the lateral threshold.
        repeat(10) { rec.onTurnSample(0.3f, 2.8f, 30f, 3.5f, false, true); clock.advance(20) }
        quiet()
        // Above the threshold, but only 100 ms.
        repeat(5) { rec.onTurnSample(-0.3f, 4f, 50f, 3.5f, false, true); clock.advance(20) }
        quiet()
        // Detected.
        repeat(5) { rec.onTurnSample(0.3f, 4f, 50f, 3.5f, false, true); clock.advance(20) }
        repeat(5) { rec.onTurnSample(0.3f, 4f, 50f, 3.5f, true, true); clock.advance(20) }
        quiet()
        // No GPS at all.
        repeat(10) { rec.onTurnSample(0.3f, 0f, 0f, 3.5f, false, false); clock.advance(20) }
        repeat(40) { rec.onTurnSample(0f, 0f, 0f, 3.5f, false, false); clock.advance(20) }

        val s = rec.snapshot()
        assertEquals(4L, s.turnCandidates)
        assertEquals(1L, s.turnDetected)
        assertEquals(1L, s.turnLeft)
        assertEquals(1L, s.turnReasons["ACCELERATION_BELOW_THRESHOLD"])
        assertEquals(1L, s.turnReasons["DURATION_BELOW_THRESHOLD"])
        assertEquals(1L, s.turnReasons["GPS_MISSING"])
        assertTrue(journal.contains("TURN_CANDIDATE\ndirection=LEFT\nlateralAcceleration=2.8 m/s2"))
        assertTrue(journal.contains("direction=RIGHT"))
    }

    @Test
    fun hardAccelerationAlongTheCarAxis() {
        val rec = recorder()
        rec.begin(null, "AUTO", -4f)
        rec.onLongitudinalSample(3f, 2.5f, rapid = true)
        repeat(20) { rec.onLongitudinalSample(0f, 2.5f, false); clock.advance(20) }
        rec.onLongitudinalSample(2f, 2.5f, rapid = false)
        repeat(20) { rec.onLongitudinalSample(0f, 2.5f, false); clock.advance(20) }
        val s = rec.snapshot()
        assertEquals(2L, s.accelCandidates)
        assertEquals(1L, s.accelDetected)
        assertEquals(1L, s.accelReasons["ACCELERATION_BELOW_THRESHOLD"])
        // Wave 7: an intensive start plays INERTIA_BACK, so there is no "not configured" request any more.
        assertTrue(s.requests.keys.none { it.startsWith("HARD_ACCELERATION") })
        assertTrue(journal.contains("acceleration=3.0 m/s2\nthreshold=2.5 m/s2\naxis=vehicle"))
    }

    @Test
    fun requestsStateChangesAndPlayback() {
        val rec = recorder()
        rec.begin(null, "AUTO", -4f)
        rec.onAnimationRequest("ALERT", RequestResult.STARTED, null)
        rec.onStateChanged(CompanionState.ALERT, CompanionState.CALM, shown = true)
        rec.onPlayback(CompanionState.ALERT, "alert")
        rec.onStateChanged(CompanionState.TURN_LEFT, CompanionState.CALM, shown = false)
        rec.onPlayback(CompanionState.TURN_LEFT, null)
        rec.onStateChanged(CompanionState.BLINK, CompanionState.CALM, shown = true)
        rec.onTripEvent(TripEvent.DEPARTED)
        rec.onMovement(true, 20f, parked = true)
        // A blocked reaction retried on every GPS update is written once.
        repeat(10) { rec.onAnimationRequest("TRIP_START", RequestResult.DEFERRED, DiagnosticReason.STATE_BLOCKED, "ALERT playing") }

        val s = rec.snapshot()
        assertEquals(1L, s.reactionsPlayed)
        assertEquals(1L, s.reactionsHidden)
        assertEquals(1L, s.playbackMissing)
        assertEquals(1L, s.stateChanges["BLINK"])
        assertEquals(10L, s.requests["TRIP_START DEFERRED STATE_BLOCKED"])
        assertEquals(1, Regex("state=TRIP_START").findAll(journal).count())
        assertFalse("blinks are only counted", journal.contains("to=BLINK"))
        assertTrue(journal.contains("ANIMATION_REQUEST\nstate=ALERT\nresult=STARTED\n\n"))
        assertTrue(journal.contains("reason=OVERLAY_HIDDEN"))
        assertTrue(s.lastEvent.contains("ANIMATION_REQUEST"))
        assertEquals("STATE_BLOCKED", s.lastReason)
    }

    @Test
    fun theJournalStopsAtItsLimit() {
        val limit = 8 * 1024L
        val rec = recorder(limit)
        rec.begin(null, "AUTO", -4f)
        val g = gate(rec)
        repeat(500) { g.isNewUsableFix(fix(accuracyM = 90f)) }
        assertFalse(rec.isRunning)
        assertEquals(1, limitHits)
        val saved = storage.savedSummary()
        assertEquals(DiagnosticsStatus.LIMIT_REACHED, saved.status)
        assertTrue(saved.endedAt != 0L)
        assertTrue(storage.journalBytes() <= limit)
        assertEquals(storage.journalBytes(), saved.bytes)
        assertTrue(journal.trimEnd().endsWith("limit=8 KB"))
        assertTrue(journal.contains("LIMIT_REACHED"))

        val size = journal.length
        repeat(10) { g.isNewUsableFix(fix(accuracyM = 90f)) }
        rec.stop()
        assertEquals("nothing after the limit", size, journal.length)
        assertEquals(1, limitHits)
    }

    @Test
    fun summarySurvivesSaving() {
        val rec = recorder()
        rec.begin(null, "AUTO", -4f)
        val g = gate(rec)
        g.isNewUsableFix(fix(accuracyM = 73f))
        rec.onAnimationRequest("TURN_LEFT", RequestResult.REJECTED, DiagnosticReason.COOLDOWN_ACTIVE)
        rec.onSensorRegistration(SensorRegistration("AccelerometerMonitor", "GRAVITY", true, true))
        val copy = DrivingDiagnosticsSummary.fromText(rec.snapshot().toText())
        assertEquals(rec.snapshot().toText().lines().filter { !it.startsWith("#") }.sorted(),
            copy.toText().lines().filter { !it.startsWith("#") }.sorted())
        assertEquals(1L, copy.requests["TURN_LEFT REJECTED COOLDOWN_ACTIVE"])
        assertEquals(73f, copy.lastAccuracyM, 0.01f)
        assertNull(DrivingDiagnosticsSummary.fromText("").profiles.firstOrNull())
    }
}
