package com.damitrya.mimio.state

import com.damitrya.mimio.data.AppProfile
import com.damitrya.mimio.data.DrivingData
import com.damitrya.mimio.data.MediaData
import com.damitrya.mimio.data.SettingsRepository
import com.damitrya.mimio.data.TurnDirection
import com.damitrya.mimio.diagnostics.DirectExecutor
import com.damitrya.mimio.diagnostics.DrivingDiagnostics
import com.damitrya.mimio.diagnostics.DrivingDiagnosticsRecorder
import com.damitrya.mimio.diagnostics.MemoryDiagnosticsStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The state machine's diagnostic hooks: real reasons for every car reaction, and no change in behaviour. */
class DrivingDiagnosticsHooksTest {

    private val clock = FakeClock()
    private var scheduler = FakeScheduler(clock)
    private val states = mutableListOf<CompanionState>()
    private val storage = MemoryDiagnosticsStorage()
    private val recorder = DrivingDiagnosticsRecorder(storage, DirectExecutor, clock, clock)

    @After
    fun tearDown() {
        DrivingDiagnostics.sink = null
    }

    private fun machine(): StateMachine {
        states.clear()
        scheduler = FakeScheduler(clock)          // nothing left over from an earlier machine
        lateinit var sm: StateMachine
        sm = StateMachine(SettingsRepository(InMemoryPrefs()), clock, scheduler, Random(7), CharacterMood(MemoryMoodStore(), clock))
        sm.setListener(object : StateMachine.Listener {
            override fun onStateChanged(newState: CompanionState, previousState: CompanionState) {
                states += newState
                DrivingDiagnostics.sink?.onStateChanged(newState, previousState, shown = true)
                if (!newState.isLooping) scheduler.postDelayed(Runnable { sm.onOneShotCompleted(newState) }, 2000L)
            }
            override fun onWeatherModifierChanged(modifier: WeatherModifier) {}
        })
        sm.setProfile(AppProfile.AUTO)
        return sm
    }

    private fun record(): StateMachine {
        recorder.begin(null, "AUTO", -4f)
        DrivingDiagnostics.sink = recorder
        return machine()
    }

    private fun requests(key: String) = recorder.snapshot().requests[key] ?: 0L

    @Test
    fun aTurnWithoutUsableGpsIsGpsMissing() {
        val sm = record()
        // The real case: Android delivers fixes, but with 124.55 m accuracy the gate rejects them all.
        val inaccurate = gpsFix(60f, accuracyM = 124.55f)
        sm.updateDrivingData(inaccurate)
        sm.updateDrivingData(sensorEvent(inaccurate, isTurning = true, turnDirection = TurnDirection.LEFT))
        assertFalse(states.contains(CompanionState.TURN_LEFT))
        assertEquals(1L, requests("TURN_LEFT REJECTED GPS_MISSING"))
        assertTrue(storage.journal.contains("detail=no usable GPS fix yet"))
    }

    @Test
    fun turnReasonsFollowTheChecks() {
        val sm = record()
        fun turn(base: DrivingData, dir: TurnDirection = TurnDirection.LEFT) {
            sm.updateDrivingData(sensorEvent(base, isTurning = true, turnDirection = dir))
            sm.updateDrivingData(sensorEvent(base, isTurning = false))
        }
        val slow = gpsFix(6f)          // under the 8 km/h of wave 7 (was 20 km/h)
        sm.updateDrivingData(slow)
        turn(slow)
        assertEquals(1L, requests("TURN_LEFT REJECTED SPEED_BELOW_THRESHOLD"))

        val fast = gpsFix(50f)
        sm.updateDrivingData(fast)
        scheduler.advance(3000)
        turn(fast)
        assertEquals(1L, requests("TURN_LEFT STARTED"))
        assertTrue(states.contains(CompanionState.TURN_LEFT))

        scheduler.advance(2500)              // the turn ended, its 4 s cooldown has not
        turn(fast, TurnDirection.RIGHT)
        assertEquals(1L, requests("TURN_RIGHT REJECTED COOLDOWN_ACTIVE"))

        sm.onLongPress()                     // PET is not interruptible by a turn
        clock.advance(5000)
        turn(fast)
        assertEquals(1L, requests("TURN_LEFT REJECTED STATE_BLOCKED"))
        sm.onLongPressCancelled()

        clock.advance(20_000)                // no fixes since: the speed is stale
        turn(fast)
        assertEquals(1L, requests("TURN_LEFT REJECTED GPS_STALE"))
    }

    @Test
    fun alertReasonsFollowTheChecks() {
        val sm = record()
        sm.onAlertTriggered()
        sm.onAlertTriggered()
        assertEquals(1L, requests("ALERT STARTED"))
        assertEquals(1L, requests("ALERT REJECTED STATE_BLOCKED"))
        scheduler.advance(2100)              // ALERT ends, RELIEF plays, the 3 s cooldown runs
        sm.onAlertTriggered()
        assertEquals(1L, requests("ALERT REJECTED COOLDOWN_ACTIVE"))
        assertEquals(1L, recorder.snapshot().reactionsPlayed)
    }

    @Test
    fun movementAndTripEvents() {
        val sm = record()
        sm.updateDrivingData(gpsFix(0f))
        sm.updateDrivingData(gpsFix(30f))
        val s = recorder.snapshot()
        assertEquals(1L, s.movementStarts)
        assertEquals(1L, s.tripDepartures)
        assertEquals(1L, requests("TRIP_START STARTED"))
        sm.updateDrivingData(gpsFix(0f))
        assertEquals("one standing fix is no stop yet (wave 7 hysteresis)", 0L, recorder.snapshot().movementStops)
        sm.updateDrivingData(gpsFix(0f))
        assertEquals(1L, recorder.snapshot().movementStops)
        assertTrue(storage.journal.contains("MOVEMENT_START\nspeed=30.0 km/h\nparkedBefore=true"))
    }

    /** One busy drive: fixes good and bad, turns, braking, taps, music, time passing. */
    private fun drive(sm: StateMachine) {
        var last = gpsFix(0f)
        sm.updateDrivingData(last)
        for (i in 0 until 400) {
            last = when (i % 7) {
                0 -> gpsFix((i % 90).toFloat(), accuracyM = if (i % 3 == 0) 124.55f else 8f)
                1 -> sensorEvent(last, isTurning = true, turnDirection = if (i % 2 == 0) TurnDirection.LEFT else TurnDirection.RIGHT)
                2 -> sensorEvent(last, isTurning = false)
                else -> gpsFix(40f + i % 30, ageMs = if (i % 11 == 0) 20_000L else 0L)
            }
            sm.updateDrivingData(last)
            if (i % 13 == 0) sm.onAlertTriggered()
            if (i % 29 == 0) sm.onTap()
            if (i % 50 == 0) sm.updateMediaData(MediaData(isPlaying = i % 100 == 0))
            scheduler.advance(700L + (i % 5) * 400L)
        }
    }

    @Test
    fun recordingDoesNotChangeTheCharactersBehaviour() {
        val start = clock.now
        DrivingDiagnostics.sink = null
        drive(machine())
        val without = states.toList()

        clock.now = start
        drive(record())
        val with = states.toList()

        assertTrue(without.size > 50)
        assertEquals(without, with)
        assertTrue("the drive was recorded", recorder.snapshot().requests.isNotEmpty())
    }
}
