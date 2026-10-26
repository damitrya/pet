package com.damitrya.mimio.diagnostics

import com.damitrya.mimio.data.CalibrationReason
import com.damitrya.mimio.data.CalibrationState
import com.damitrya.mimio.data.InertiaKind
import com.damitrya.mimio.data.InertiaLevel
import com.damitrya.mimio.data.InertiaOutcome
import com.damitrya.mimio.state.CompanionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter

/** Wave 7 entries of the driving diagnostics: calibration, the phone, manoeuvres, leans — and still no coordinates. */
class CarPhysicsDiagnosticsTest {

    private val storage = MemoryDiagnosticsStorage()
    private val clock = TestClock()
    private val rec = DrivingDiagnosticsRecorder(storage, DirectExecutor, clock, { 1_700_000_000_000L })
    private val journal get() = storage.journal.toString()

    private fun recordDrive(): DrivingDiagnosticsSummary {
        rec.begin(null, "AUTO", -4f)
        rec.onCalibrationChange(CalibrationState.UNCALIBRATED, CalibrationState.LEARNING, CalibrationReason.LEARNING_STARTED, 1f, 1)
        rec.onCalibrationChange(CalibrationState.LEARNING, CalibrationState.READY, CalibrationReason.LEARNED, 0.97f, 3)
        repeat(30) {
            rec.onMotionSample(-1.9f, -1.6f, 0.4f, 1.9f, CalibrationState.READY, 0.97f, false)
            clock.advance(20)
        }
        rec.onInertiaCandidate(InertiaOutcome(InertiaKind.BRAKE, InertiaLevel.SOFT, true, null, 1.6f, 300, 42f))
        rec.onInertiaCandidate(InertiaOutcome(InertiaKind.TURN_LEFT, InertiaLevel.SOFT, false, DiagnosticReason.EVENT_TOO_SHORT, 1.4f, 180, 30f))
        rec.onPhoneMotion(true, "tilt 34°")
        rec.onCalibrationChange(CalibrationState.READY, CalibrationState.SUSPENDED, CalibrationReason.PHONE_MOVING, 0.97f, 3)
        rec.onInertiaCandidate(InertiaOutcome(InertiaKind.ACCELERATE, InertiaLevel.SOFT, false, DiagnosticReason.PHONE_MOVING, 1.8f, 300, 35f))
        rec.onPhoneMotion(false, "")
        rec.onCalibrationChange(CalibrationState.SUSPENDED, CalibrationState.READY, CalibrationReason.PHONE_STABLE_SAME_ORIENTATION, 0.97f, 3)
        rec.onAnimationRequest("INERTIA_FORWARD", RequestResult.STARTED, null, "BRAKE SOFT")
        rec.onStateChanged(CompanionState.INERTIA_FORWARD, CompanionState.CALM, shown = true)
        rec.onPlayback(CompanionState.INERTIA_FORWARD, "inertia_forward")
        clock.advance(2_400)
        rec.onStateChanged(CompanionState.CALM, CompanionState.INERTIA_FORWARD, shown = true)
        rec.onAnimationRequest("INERTIA_RIGHT", RequestResult.REJECTED, DiagnosticReason.INERTIA_COOLDOWN, "TURN_LEFT SOFT: 1.6 s left")
        clock.advance(DrivingDiagnosticsRecorder.STATS_WINDOW_MS)
        rec.onMotionSample(0f, 0f, 0f, 0f, CalibrationState.READY, 0.97f, false)
        rec.stop()
        return storage.savedSummary()
    }

    @Test
    fun everyStepOfALeanCanBeTraced() {
        val s = recordDrive()
        val j = journal
        assertTrue(j.contains("CALIBRATION\nvehicleAxis=READY\nfrom=LEARNING\ntransitionReason=LEARNED\nquality=0.97\nlessons=3"))
        assertTrue(j.contains("PHONE_MOTION\nphoneMoving=true\ncause=tilt 34°\nreason=PHONE_MOVING"))
        assertTrue(j.contains("INERTIA_CANDIDATE\nkind=BRAKE\nlevel=SOFT\npeak=1.6 m/s2\nduration=300 ms\nspeed=42.0 km/h\naccepted=true"))
        assertTrue(j.contains("reason=EVENT_TOO_SHORT"))
        assertTrue(j.contains("INERTIA_START\nstate=INERTIA_FORWARD\nfile=inertia_forward\naccepted=true"))
        assertTrue(j.contains("INERTIA_END\nstate=INERTIA_FORWARD\nto=CALM\nafter=2400 ms"))
        assertTrue(j.contains("reason=INERTIA_COOLDOWN"))
        // the window keeps raw and smoothed longitudinal and the lateral acceleration
        assertTrue(j.contains("rawLongitudinal=-1.9..0.0 m/s2\nsmoothedLongitudinal=-1.6..0.0 m/s2\nlateralAccelMax=0.4 m/s2"))

        assertEquals("READY", s.vehicleCalibration)
        assertEquals(1L, s.phoneMovingEpisodes)
        assertEquals(1L, s.calibrationTransitions["READY -> SUSPENDED PHONE_MOVING"])
        assertEquals(1L, s.inertiaAccepted["BRAKE SOFT"])
        assertEquals(1L, s.inertiaReasons["ACCELERATE PHONE_MOVING"])
        assertEquals(1L, s.inertiaPlayed["inertia_forward"])
        assertEquals(-1.6f, s.peakLongDecel, 0.01f)
        assertEquals(1L, s.reactionsPlayed)
    }

    @Test
    fun theSummarySurvivesItsTextForm() {
        val s = recordDrive()
        val back = DrivingDiagnosticsSummary.fromText(s.toText())
        assertEquals(s.calibrationTransitions, back.calibrationTransitions)
        assertEquals(s.inertiaCandidates, back.inertiaCandidates)
        assertEquals(s.inertiaReasons, back.inertiaReasons)
        assertEquals(s.inertiaPlayed, back.inertiaPlayed)
        assertEquals(s.vehicleQuality, back.vehicleQuality, 0.001f)
    }

    @Test
    fun theReportExplainsTheNewReasonsAndHasNoCoordinates() {
        val s = recordDrive()
        val bytes = ByteArrayOutputStream()
        OutputStreamWriter(bytes, Charsets.UTF_8).use {
            DrivingDiagnosticsExporter.write(it, ReportInfo("Mimio 2.0.0", "16", "motorola edge 70", 0L), s, storage.journalReader())
        }
        val text = String(bytes.toByteArray(), Charsets.UTF_8)
        assertTrue(text.indexOf("== 7a. Физика автомобиля (wave 7) ==") in text.indexOf("== 7.") until text.indexOf("== 8."))
        for (r in listOf("PHONE_MOVING", "CALIBRATION_UNRELIABLE", "INERTIA_COOLDOWN", "EVENT_TOO_SHORT", "SENSOR_DATA_INCONSISTENT")) {
            assertTrue(r, text.contains("$r: "))
        }
        assertTrue(text.contains("manoeuvre BRAKE SOFT seen=1 accepted=1"))
        assertTrue(text.contains("leanPlayed inertia_forward=1"))
        assertTrue(text.contains("calibrationTransition READY -> SUSPENDED PHONE_MOVING=1"))
        assertFalse(Regex("""-?\d{1,3}\.\d{4,},\s*-?\d{1,3}\.\d{4,}""").containsMatchIn(text))
        assertFalse(text.contains("latitude") || text.contains("longitude"))
    }
}
