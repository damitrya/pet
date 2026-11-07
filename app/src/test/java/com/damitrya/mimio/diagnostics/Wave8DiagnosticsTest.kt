package com.damitrya.mimio.diagnostics

import com.damitrya.mimio.data.BrakeDetector
import com.damitrya.mimio.data.CarMotionTracker
import com.damitrya.mimio.data.CarSim
import com.damitrya.mimio.data.Mounts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 8: every rejected manoeuvre and every calibration change explains itself in the journal, which stays small. */
class Wave8DiagnosticsTest {

    private val storage = MemoryDiagnosticsStorage()

    @After
    fun tearDown() {
        DrivingDiagnostics.sink = null
    }

    /** A drive recorded the way the service does it (the sink set while the car physics run). */
    private fun recordedSim(): CarSim {
        val tracker = CarMotionTracker()
        val sim = CarSim(tracker, Mounts.UPRIGHT, BrakeDetector(-4f, tracker.frame), seed = 3)
        val clock = { sim.now }
        val rec = DrivingDiagnosticsRecorder(storage, DirectExecutor, clock, { 1_700_000_000_000L + sim.now })
        rec.begin(null, "AUTO", -4f)
        DrivingDiagnostics.sink = rec
        return sim
    }

    private fun blocks(type: String) = storage.journal.toString().split("\n\n").filter { it.contains("] $type\n") }

    @Test
    fun aRejectedManoeuvreCarriesEverythingNeededToUnderstandIt() {
        val sim = recordedSim()
        sim.calibrateOnStraightRoad()
        sim.cruise(2_000L, 40f)
        sim.turn(250L, 2.0f, rampMs = 50L)                  // a swerve: too short
        sim.cruise(3_000L, 40f)
        val c = blocks("INERTIA_CANDIDATE").last { it.contains("reason=") }
        for (key in listOf(
            "kind=TURN_LEFT", "level=SOFT", "peak=", "rawPeak=", "duration=", "speed=", "aboveThreshold=",
            "episode=", "threshold=1.2 m/s2", "endedBy=RELEASED", "calibration=READY quality=", "phoneMoving=false",
            "accepted=false", "reason=EVENT_TOO_SHORT"
        )) assertTrue("$key in\n$c", c.contains(key))
        // the wave 7 keys keep their order at the head of the block
        assertTrue(c, Regex("INERTIA_CANDIDATE\nkind=\\w+\nlevel=\\w+\npeak=[-0-9.]+ m/s2\nduration=\\d+ ms\nspeed=[0-9.]+ km/h\n").containsMatchIn(c))
        val duration = Regex("duration=(\\d+) ms").find(c)!!.groupValues[1].toLong()
        assertTrue("EVENT_TOO_SHORT really was shorter than 300 ms: $duration", duration < 300L)
    }

    @Test
    fun calibrationChangesNameTheirConditions() {
        val sim = recordedSim()
        sim.calibrateOnStraightRoad()
        sim.cruise(2_000L, 40f)
        sim.handle(1_000L, floatArrayOf(0f, 0f, 1f), 1.57f)  // portrait → landscape in the holder
        sim.cruise(3_000L, 40f)
        val j = storage.journal.toString()
        val learned = blocks("CALIBRATION").first { it.contains("transitionReason=LEARNED") }
        assertTrue(learned, learned.contains("timeInState="))
        val suspended = blocks("CALIBRATION").first { it.contains("transitionReason=PHONE_MOVING") }
        assertTrue(suspended, suspended.contains("condition=cause "))
        val changed = blocks("CALIBRATION").first { it.contains("transitionReason=ORIENTATION_CHANGED") }
        assertTrue(changed, changed.contains("condition=tilt changed by "))
        assertTrue(changed, changed.contains("timeInState="))
        val stable = blocks("PHONE_MOTION").first { it.contains("phoneMoving=false") }
        assertTrue(stable, stable.contains("episode=handled "))
        assertTrue(j, j.contains("vehicleAxis=UNCALIBRATED\nfrom=SUSPENDED\ntransitionReason=ORIENTATION_CHANGED\nquality="))
    }

    @Test
    fun theJournalStaysSmall() {
        // Ten minutes of city driving: manoeuvres every few seconds, the phone handled twice.
        val sim = recordedSim()
        sim.calibrateOnStraightRoad()
        repeat(20) { i ->
            sim.cruise(5_000L, 35f)
            sim.turn(3_000L, if (i % 2 == 0) 1.7f else -1.7f)
            sim.changeSpeed(50f, 1.6f)
            sim.cruise(4_000L, 50f)
            sim.changeSpeed(35f, 1.5f)
            if (i % 10 == 5) sim.pickUpAndPutBack()
        }
        val bytes = storage.journal.toString().toByteArray(Charsets.UTF_8).size
        val candidates = blocks("INERTIA_CANDIDATE")
        assertTrue(candidates.size > 40)
        assertTrue("largest INERTIA_CANDIDATE ${candidates.maxOf { it.length }} chars", candidates.all { it.length < 420 })
        // ≈ 10 min of driving: far below the 5 MB limit of a recording (hours of driving fit)
        assertTrue("$bytes bytes", bytes < 150 * 1024)
        assertEquals(DiagnosticsStatus.RECORDING, DrivingDiagnostics.sink!!.snapshot().status)
    }
}
