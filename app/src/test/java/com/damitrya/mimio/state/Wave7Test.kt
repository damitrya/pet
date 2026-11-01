package com.damitrya.mimio.state

import com.damitrya.mimio.data.AppProfile
import com.damitrya.mimio.data.BrakeDetector
import com.damitrya.mimio.data.CarMotionEvent
import com.damitrya.mimio.data.CarMotionTracker
import com.damitrya.mimio.data.CarSim
import com.damitrya.mimio.data.DrivingData
import com.damitrya.mimio.data.InertiaKind
import com.damitrya.mimio.data.InertiaLevel
import com.damitrya.mimio.data.MediaData
import com.damitrya.mimio.data.Mounts
import com.damitrya.mimio.data.PhoneCallData
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

/** Wave 7: the leans in the state machine, and whole drives from sensor traces to the character's states. */
class Wave7Test {

    private val clock = FakeClock()
    private var scheduler = FakeScheduler(clock)
    private val prefs = InMemoryPrefs()
    private val settings = SettingsRepository(prefs)
    private val states = mutableListOf<CompanionState>()

    /** One-shots end after this long, like the 2.4 s WebP of a lean (null: never end). */
    private var oneShotMs: Long? = 2_400L

    @After
    fun tearDown() {
        DrivingDiagnostics.sink = null
    }

    private fun machine(): StateMachine {
        states.clear()
        scheduler = FakeScheduler(clock)
        lateinit var sm: StateMachine
        sm = StateMachine(settings, clock, scheduler, Random(3), CharacterMood(MemoryMoodStore(), clock))
        sm.setListener(object : StateMachine.Listener {
            override fun onStateChanged(newState: CompanionState, previousState: CompanionState) {
                states += newState
                DrivingDiagnostics.sink?.onStateChanged(newState, previousState, shown = true)
                // CharacterView reports the file it picked: here the state's own file.
                DrivingDiagnostics.sink?.onPlayback(newState, newState.rawVideoResName)
                val ms = oneShotMs
                if (!newState.isLooping && ms != null) scheduler.postDelayed(Runnable { sm.onOneShotCompleted(newState) }, ms)
            }
            override fun onWeatherModifierChanged(modifier: WeatherModifier) {}
        })
        sm.setProfile(AppProfile.AUTO)
        scheduler.advance(3_000L)              // TRIP_PREPARE etc. are over
        return sm
    }

    private fun StateMachine.driving(kmh: Float = 40f): StateMachine {
        updateDrivingData(gpsFix(kmh))
        updateDrivingData(gpsFix(kmh))
        return this
    }

    private fun event(kind: InertiaKind, level: InertiaLevel = InertiaLevel.SOFT) = CarMotionEvent(kind, level, 2f, 40f)

    private val leans get() = states.filter { it in CompanionState.INERTIA }

    // ---- mapping, return, priorities

    @Test
    fun eachManoeuvreLeansTheRightWayAndReturns() {
        val sm = machine().driving()
        val expected = mapOf(
            InertiaKind.TURN_LEFT to CompanionState.INERTIA_RIGHT,     // thrown to the outside of the turn
            InertiaKind.TURN_RIGHT to CompanionState.INERTIA_LEFT,
            InertiaKind.BRAKE to CompanionState.INERTIA_FORWARD,
            InertiaKind.ACCELERATE to CompanionState.INERTIA_BACK
        )
        for ((kind, state) in expected) {
            sm.onCarMotion(event(kind))
            assertEquals(kind.name, state, sm.getCurrentState())
            assertEquals(kind.name, state.name.lowercase(), state.rawVideoResName)
            scheduler.advance(2_400L)
            assertEquals("back to CALM after $kind", CompanionState.CALM, sm.getCurrentState())
            scheduler.advance(2_000L)          // past the 4 s pause
        }
    }

    @Test
    fun aLeanWhoseEndIsNeverReportedEndsByTheWatchdog() {
        oneShotMs = null
        val sm = machine().driving()
        sm.onCarMotion(event(InertiaKind.BRAKE))
        assertEquals(CompanionState.INERTIA_FORWARD, sm.getCurrentState())
        scheduler.advance(StateMachine.ONE_SHOT_TIMEOUT_MS)
        assertEquals(CompanionState.CALM, sm.getCurrentState())
    }

    @Test
    fun musicKeepsPlayingAroundALean() {
        val sm = machine().driving()
        sm.updateMediaData(MediaData(isPlaying = true))
        assertEquals(CompanionState.MUSIC, sm.getCurrentState())
        sm.onCarMotion(event(InertiaKind.TURN_LEFT))
        assertEquals(CompanionState.INERTIA_RIGHT, sm.getCurrentState())
        scheduler.advance(2_400L)
        assertEquals(CompanionState.MUSIC, sm.getCurrentState())
        // the music stops during the next lean: it comes back to CALM
        scheduler.advance(4_000L)
        sm.onCarMotion(event(InertiaKind.BRAKE))
        sm.updateMediaData(MediaData(isPlaying = false))
        scheduler.advance(2_400L)
        assertEquals(CompanionState.CALM, sm.getCurrentState())
    }

    @Test
    fun aCallStaysTheAmbientState() {
        val sm = machine().driving()
        sm.updatePhoneCallData(PhoneCallData(isInCall = true))
        sm.onCarMotion(event(InertiaKind.ACCELERATE))
        assertEquals(CompanionState.INERTIA_BACK, sm.getCurrentState())
        scheduler.advance(2_400L)
        assertEquals(CompanionState.PHONE_CALL, sm.getCurrentState())
    }

    @Test
    fun alertIsNeverInterruptedAndInterruptsALean() {
        oneShotMs = null
        val sm = machine().driving()
        sm.onAlertTriggered()
        sm.onCarMotion(event(InertiaKind.BRAKE, InertiaLevel.STRONG))
        assertEquals(CompanionState.ALERT, sm.getCurrentState())
        sm.onOneShotCompleted(CompanionState.ALERT)
        sm.onOneShotCompleted(CompanionState.RELIEF)
        scheduler.advance(5_000L)
        sm.onCarMotion(event(InertiaKind.TURN_RIGHT))
        assertEquals(CompanionState.INERTIA_LEFT, sm.getCurrentState())
        sm.onAlertTriggered()
        assertEquals(CompanionState.ALERT, sm.getCurrentState())
    }

    @Test
    fun userAndTripReactionsWin() {
        oneShotMs = null
        val sm = machine()
        sm.updateDrivingData(gpsFix(0f))
        sm.updateDrivingData(gpsFix(30f))
        assertEquals(CompanionState.TRIP_START, sm.getCurrentState())
        sm.onCarMotion(event(InertiaKind.ACCELERATE, InertiaLevel.STRONG))
        assertEquals("trip events beat leans", CompanionState.TRIP_START, sm.getCurrentState())
        sm.onOneShotCompleted(CompanionState.TRIP_START)

        sm.onLongPress()
        sm.onCarMotion(event(InertiaKind.TURN_LEFT, InertiaLevel.STRONG))
        assertEquals(CompanionState.PET, sm.getCurrentState())
        sm.onLongPressReleased()
        sm.onCarMotion(event(InertiaKind.TURN_LEFT))
        assertEquals("HEARTS is not cut by a lean", CompanionState.HEARTS, sm.getCurrentState())
        sm.onOneShotCompleted(CompanionState.HEARTS)

        sm.onCarMotion(event(InertiaKind.TURN_LEFT))
        assertEquals(CompanionState.INERTIA_RIGHT, sm.getCurrentState())
        sm.onTap()
        assertEquals("a tap does not wait for the lean", CompanionState.TAP, sm.getCurrentState())
    }

    @Test
    fun aSharpTurnReplacesALeanAndALeanDoesNotCutATurn() {
        oneShotMs = null
        val sm = machine()
        var last = gpsFix(40f)
        sm.updateDrivingData(last)
        last = gpsFix(40f)
        sm.updateDrivingData(last)
        sm.onCarMotion(event(InertiaKind.TURN_LEFT))
        assertEquals(CompanionState.INERTIA_RIGHT, sm.getCurrentState())
        sm.updateDrivingData(sensorEvent(last, isTurning = true, turnDirection = TurnDirection.LEFT))
        assertEquals(CompanionState.TURN_LEFT, sm.getCurrentState())
        sm.onCarMotion(event(InertiaKind.BRAKE, InertiaLevel.STRONG))
        assertEquals(CompanionState.TURN_LEFT, sm.getCurrentState())
    }

    @Test
    fun aLongManoeuvreIsNotRestartedAndLeansKeepTheirPause() {
        oneShotMs = null
        val sm = machine().driving()
        sm.onCarMotion(event(InertiaKind.BRAKE))
        sm.onCarMotion(event(InertiaKind.BRAKE, InertiaLevel.STRONG))     // the same braking got harder
        assertEquals(listOf(CompanionState.INERTIA_FORWARD), leans)
        sm.onOneShotCompleted(CompanionState.INERTIA_FORWARD)
        clock.advance(1_000L)
        sm.onCarMotion(event(InertiaKind.TURN_RIGHT))
        assertEquals("ordinary leans 4 s apart", CompanionState.CALM, sm.getCurrentState())
        clock.advance(3_100L)
        sm.onCarMotion(event(InertiaKind.TURN_RIGHT))
        assertEquals(CompanionState.INERTIA_LEFT, sm.getCurrentState())
        // an intensive manoeuvre of another kind may replace an ordinary lean at once
        sm.onCarMotion(event(InertiaKind.ACCELERATE, InertiaLevel.STRONG))
        assertEquals(CompanionState.INERTIA_BACK, sm.getCurrentState())
    }

    @Test
    fun theSwitchTurnsLeansAndSharpTurnsOff() {
        settings.sensorReactionEnabled = false
        val sm = machine()
        var last = gpsFix(40f)
        sm.updateDrivingData(last)
        last = gpsFix(40f)
        sm.updateDrivingData(last)
        sm.onCarMotion(event(InertiaKind.TURN_LEFT))
        sm.updateDrivingData(sensorEvent(last, isTurning = true, turnDirection = TurnDirection.LEFT))
        assertEquals(CompanionState.CALM, sm.getCurrentState())
        sm.onAlertTriggered()
        assertEquals("emergency braking always warns", CompanionState.ALERT, sm.getCurrentState())
    }

    @Test
    fun noLeansInTheDailyProfile() {
        val sm = machine()
        sm.setProfile(AppProfile.DAILY)
        sm.onCarMotion(event(InertiaKind.TURN_LEFT))
        assertEquals(CompanionState.CALM, sm.getCurrentState())
    }

    @Test
    fun switchingAutoAndDailyQuicklyLeavesNothingStuck() {
        val sm = machine()
        val tracker = CarMotionTracker()
        repeat(20) { i ->
            sm.setProfile(if (i % 2 == 0) AppProfile.DAILY else AppProfile.AUTO)
            tracker.reset()
            sm.onCarMotion(event(InertiaKind.values()[i % 4], InertiaLevel.values()[i % 2]))
            scheduler.advance(150L)
        }
        sm.setProfile(AppProfile.AUTO)
        scheduler.advance(StateMachine.ONE_SHOT_TIMEOUT_MS)
        assertEquals(CompanionState.CALM, sm.getCurrentState())
        sm.driving().onCarMotion(event(InertiaKind.BRAKE))
        assertEquals(CompanionState.INERTIA_FORWARD, sm.getCurrentState())
    }

    // ---- movement hysteresis

    @Test
    fun aJamAt3KmhNoLongerFlipsMovement() {
        val storage = MemoryDiagnosticsStorage()
        val recorder = DrivingDiagnosticsRecorder(storage, DirectExecutor, clock, clock)
        recorder.begin(null, "AUTO", -4f)
        DrivingDiagnostics.sink = recorder
        val sm = machine()
        sm.driving(30f)
        for (kmh in listOf(4f, 2.5f, 3.5f, 1.5f, 4.5f, 3f, 2f, 4f, 3f, 1f, 4f, 2.5f, 3f)) {   // 0–5 km/h jam
            clock.advance(1_000L)
            sm.updateDrivingData(gpsFix(kmh))
        }
        val s = recorder.snapshot()
        assertEquals(1L, s.movementStarts)
        assertEquals("one standing fix under 2 km/h is not a stop", 0L, s.movementStops)
        // a red light: two fixes standing, then off again
        sm.updateDrivingData(gpsFix(0f))
        sm.updateDrivingData(gpsFix(0f))
        clock.advance(40_000L)
        sm.updateDrivingData(gpsFix(0f))
        sm.updateDrivingData(gpsFix(20f))
        assertEquals(1L, recorder.snapshot().movementStops)
        assertEquals(2L, recorder.snapshot().movementStarts)
        assertTrue(storage.journal.contains("stop=TRAFFIC_LIGHT"))
    }

    // ---- whole drives: sensor traces → car physics → state machine

    /** Drives [sim] into [sm] the way the service wires them (GPS fixes, leans, sharp turns, ALERT). */
    private fun connect(sim: CarSim, sm: StateMachine) {
        clock.now = sim.now
        var last: DrivingData = gpsFix(0f)
        sim.onStep = { t -> if (t > clock.now) scheduler.advance(t - clock.now) }
        sim.onFix = { kmh -> last = gpsFix(kmh); sm.updateDrivingData(last) }
        sim.onEvent = { sm.onCarMotion(it) }
        sim.onSharpTurn = { turning, dir -> sm.updateDrivingData(sensorEvent(last, isTurning = turning, turnDirection = dir)) }
        sim.onAlert = { sm.onAlertTriggered() }
    }

    private fun newSim(mount: Array<FloatArray>, seed: Int = 11): CarSim {
        val tracker = CarMotionTracker()
        return CarSim(tracker, mount, BrakeDetector(-4f, tracker.frame), seed)
    }

    /** A city drive: a lean for each manoeuvre, a sharp turn, the phone handled, emergency braking. */
    private fun cityDrive(sim: CarSim) {
        sim.calibrateOnStraightRoad()
        sim.cruise(5_000L, 30f)
        states.clear()
        sim.turn(4_000L, 1.8f)                       // ordinary left turn
        sim.cruise(5_000L, 30f)
        sim.changeSpeed(50f, 1.8f)                   // ordinary start
        sim.cruise(5_000L, 50f)
        sim.changeSpeed(25f, 1.9f)                   // ordinary braking
        sim.cruise(5_000L, 25f)
        sim.turn(3_000L, -3.6f, rampMs = 250L)       // sharp right turn
        sim.cruise(5_000L, 25f)
        sim.handle(2_000L, floatArrayOf(1f, 0.4f, 0f), 1.6f)   // picked up...
        sim.handle(2_000L, floatArrayOf(1f, 0.4f, 0f), -1.6f)  // ...and put back
        sim.cruise(5_000L, 25f)
        sim.changeSpeed(60f, 1.7f)
        sim.cruise(5_000L, 60f)
        sim.changeSpeed(5f, 6.5f)                    // emergency braking
        sim.cruise(8_000L, 5f)
    }

    @Test
    fun aCityDriveInEveryMount() {
        for ((name, mount) in listOf("upright" to Mounts.UPRIGHT, "landscape" to Mounts.LANDSCAPE, "sideways" to Mounts.SIDEWAYS)) {
            val sm = machine()
            val sim = newSim(mount)
            connect(sim, sm)
            cityDrive(sim)
            val reactions = states.filter { it in CompanionState.INERTIA || it in setOf(CompanionState.TURN_LEFT, CompanionState.TURN_RIGHT, CompanionState.ALERT) }
            assertEquals(
                name,
                listOf(
                    CompanionState.INERTIA_RIGHT, CompanionState.INERTIA_BACK, CompanionState.INERTIA_FORWARD,
                    CompanionState.TURN_RIGHT, CompanionState.INERTIA_BACK, CompanionState.ALERT
                ),
                reactions
            )
            scheduler.advance(10_000L)
            assertTrue(name, sm.getCurrentState() == CompanionState.CALM || sm.getCurrentState() == CompanionState.BLINK)
        }
    }

    @Test
    fun diagnosticsSwitchedOnAndOffMidDriveChangeNothing() {
        fun run(withDiagnostics: Boolean): Pair<List<CompanionState>, String> {
            val storage = MemoryDiagnosticsStorage()
            val recorder = DrivingDiagnosticsRecorder(storage, DirectExecutor, clock, clock)
            val sm = machine()
            val sim = newSim(Mounts.UPRIGHT, seed = 5)
            connect(sim, sm)
            var step = 0
            val inner = sim.onStep
            sim.onStep = { t ->
                inner?.invoke(t)
                step++
                if (withDiagnostics && step == 200) {
                    recorder.begin(null, "AUTO", -4f)
                    DrivingDiagnostics.sink = recorder
                }
                if (withDiagnostics && step == 4_000) {
                    recorder.stop()
                    DrivingDiagnostics.sink = null
                }
            }
            cityDrive(sim)
            DrivingDiagnostics.sink = null
            return states.toList() to storage.journal.toString()
        }
        clock.now = 1_000_000L
        val (plain, _) = run(false)
        clock.now = 1_000_000L
        val (recorded, journal) = run(true)
        assertEquals(plain, recorded)
        assertTrue(journal.contains("CALIBRATION\nvehicleAxis="))
        assertTrue(journal.contains("INERTIA_CANDIDATE\nkind="))
        assertTrue(journal.contains("SESSION_END"))
    }

    @Test
    fun leansAreRecordedWithTheirFileAndEnd() {
        val storage = MemoryDiagnosticsStorage()
        val recorder = DrivingDiagnosticsRecorder(storage, DirectExecutor, clock, clock)
        recorder.begin(null, "AUTO", -4f)
        DrivingDiagnostics.sink = recorder
        val sm = machine().driving()
        sm.onCarMotion(event(InertiaKind.TURN_LEFT))
        scheduler.advance(2_400L)
        clock.advance(1_000L)
        sm.onCarMotion(event(InertiaKind.BRAKE))
        val j = storage.journal.toString()
        assertTrue(j.contains("INERTIA_START\nstate=INERTIA_RIGHT\nfile=inertia_right\naccepted=true"))
        assertTrue(j.contains("INERTIA_END\nstate=INERTIA_RIGHT\nto=CALM\nafter=2400 ms"))
        assertTrue(j.contains("state=INERTIA_FORWARD\nresult=REJECTED\ndetail=BRAKE SOFT: "))
        assertTrue(j.contains("reason=INERTIA_COOLDOWN"))
        val s = recorder.snapshot()
        assertEquals(1L, s.inertiaPlayed["inertia_right"])
        assertEquals(1L, s.requests["INERTIA_FORWARD REJECTED INERTIA_COOLDOWN"])
    }

    @Test
    fun aStillPhoneInAParkedCarChangesNothing() {
        val sm = machine()
        val sim = newSim(Mounts.UPRIGHT)
        connect(sim, sm)
        sim.vibration = 0.05f
        sim.drive(5 * 60_000L)
        assertTrue(states.none { it in CompanionState.INERTIA || it == CompanionState.ALERT || it == CompanionState.TURN_LEFT || it == CompanionState.TURN_RIGHT })
        assertFalse(sim.tracker.phone.isMoving)
    }
}
