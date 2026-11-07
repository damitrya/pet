package com.damitrya.mimio.state

import com.damitrya.mimio.data.AppProfile
import com.damitrya.mimio.data.BrakeDetector
import com.damitrya.mimio.data.CarMotionEvent
import com.damitrya.mimio.data.CarMotionTracker
import com.damitrya.mimio.data.CarSim
import com.damitrya.mimio.data.DrivingData
import com.damitrya.mimio.data.InertiaKind
import com.damitrya.mimio.data.InertiaLevel
import com.damitrya.mimio.data.Mounts
import com.damitrya.mimio.data.SettingsRepository
import com.damitrya.mimio.data.TurnDirection
import com.damitrya.mimio.diagnostics.DiagnosticReason
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

/**
 * Wave 8: what the character finally does when the old detectors (ALERT, the sharp turn) and the car physics (the
 * leans) see the same manoeuvre, and when the data cannot be trusted. Every test checks the state machine's decision,
 * not only the events.
 */
class Wave8Test {

    private val clock = FakeClock()
    private var scheduler = FakeScheduler(clock)
    private val prefs = InMemoryPrefs()
    private val settings = SettingsRepository(prefs)
    private val states = mutableListOf<CompanionState>()
    private var oneShotMs: Long? = 2_400L
    private val storage = MemoryDiagnosticsStorage()
    private val recorder = DrivingDiagnosticsRecorder(storage, DirectExecutor, clock, clock)

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
                val ms = oneShotMs
                if (!newState.isLooping && ms != null) scheduler.postDelayed(Runnable { sm.onOneShotCompleted(newState) }, ms)
            }
            override fun onWeatherModifierChanged(modifier: WeatherModifier) {}
        })
        sm.setProfile(AppProfile.AUTO)
        scheduler.advance(3_000L)
        return sm
    }

    private var lastFix: DrivingData = gpsFix(0f)

    private fun StateMachine.driving(kmh: Float = 40f): StateMachine {
        lastFix = gpsFix(kmh); updateDrivingData(lastFix)
        lastFix = gpsFix(kmh); updateDrivingData(lastFix)
        return this
    }

    private fun StateMachine.sharpTurn(dir: TurnDirection) {
        updateDrivingData(sensorEvent(lastFix, isTurning = true, turnDirection = dir))
        updateDrivingData(sensorEvent(lastFix, isTurning = false))
    }

    private fun event(kind: InertiaKind, level: InertiaLevel = InertiaLevel.SOFT, upgrade: Boolean = false) =
        CarMotionEvent(kind, level, 2f, 40f, upgrade)

    private fun record() {
        recorder.begin(null, "AUTO", -4f)
        DrivingDiagnostics.sink = recorder
    }

    private val journal get() = storage.journal.toString()

    private val reactions
        get() = states.filter {
            it in CompanionState.INERTIA || it == CompanionState.ALERT || it == CompanionState.TURN_LEFT || it == CompanionState.TURN_RIGHT
        }

    // ---- priorities between the old detectors and the leans

    @Test
    fun brakingShownByAlertGetsNoLeanAfterwards() {
        oneShotMs = null
        record()
        val sm = machine().driving()
        sm.onCarMotion(event(InertiaKind.BRAKE))                       // the soft level of the braking...
        assertEquals(CompanionState.INERTIA_FORWARD, sm.getCurrentState())
        sm.onAlertTriggered()                                          // ...turns into an emergency stop
        assertEquals("ALERT cuts the lean", CompanionState.ALERT, sm.getCurrentState())
        sm.onCarMotion(event(InertiaKind.BRAKE, InertiaLevel.STRONG, upgrade = true))
        sm.onOneShotCompleted(CompanionState.ALERT)
        sm.onOneShotCompleted(CompanionState.RELIEF)
        clock.advance(4_500L)                                          // past every lean pause, same braking
        sm.onCarMotion(event(InertiaKind.BRAKE, InertiaLevel.STRONG))
        assertEquals(CompanionState.CALM, sm.getCurrentState())
        assertEquals(listOf(CompanionState.INERTIA_FORWARD, CompanionState.ALERT), reactions)
        assertTrue(journal, journal.contains("reason=ALREADY_SHOWN"))
        // other manoeuvres are not affected, and a later braking leans again
        sm.onCarMotion(event(InertiaKind.TURN_LEFT))
        assertEquals(CompanionState.INERTIA_RIGHT, sm.getCurrentState())
        sm.onOneShotCompleted(CompanionState.INERTIA_RIGHT)
        clock.advance(StateMachine.ALERT_COVERS_BRAKE_MS)
        sm.onCarMotion(event(InertiaKind.BRAKE))
        assertEquals(CompanionState.INERTIA_FORWARD, sm.getCurrentState())
    }

    @Test
    fun emergencyBrakingSeenByBothDetectorsPlaysAlertOnly() {
        val sm = machine()
        val sim = newSim()
        connect(sim, sm)
        sim.calibrateOnStraightRoad()
        sim.changeSpeed(60f, 1.6f)
        sim.cruise(5_000L, 60f)
        states.clear()
        sim.changeSpeed(5f, 6.5f)
        sim.cruise(10_000L, 5f)
        assertTrue(sim.alerts.isNotEmpty())
        assertTrue("the car physics saw it too", sim.outcomes.any { it.kind == InertiaKind.BRAKE })
        assertEquals(listOf(CompanionState.ALERT), reactions)
    }

    @Test
    fun aSharpTurnSeenAsSoftThenStrongPlaysTheTurnOnceAndNoLeanAfterIt() {
        val sm = machine()
        val sim = newSim()
        connect(sim, sm)
        sim.calibrateOnStraightRoad()
        sim.cruise(5_000L, 30f)
        states.clear()
        sim.turn(5_000L, 3.8f, rampMs = 1_800L)          // slowly tightening: soft level first, then sharp
        sim.cruise(6_000L, 30f)
        val r = reactions
        assertEquals(r.toString(), 1, r.count { it == CompanionState.TURN_LEFT })
        assertTrue(r.toString(), r.last() == CompanionState.TURN_LEFT)
        assertTrue(r.toString(), r.all { it == CompanionState.TURN_LEFT || it == CompanionState.INERTIA_RIGHT })
        assertTrue("at most the soft lean before it", r.count { it == CompanionState.INERTIA_RIGHT } <= 1)
    }

    @Test
    fun aQuickSharpTurnIsOneTurnReaction() {
        val sm = machine()
        val sim = newSim()
        connect(sim, sm)
        sim.calibrateOnStraightRoad()
        sim.cruise(5_000L, 30f)
        states.clear()
        sim.turn(3_000L, -4.0f, rampMs = 300L)
        sim.cruise(6_000L, 30f)
        assertEquals(listOf(CompanionState.TURN_RIGHT), reactions)
    }

    @Test
    fun competingRequestsFollowThePriorities() {
        oneShotMs = null
        val sm = machine().driving()
        // a lean, then the sharp turn of the same manoeuvre: the stronger reaction replaces the weaker one
        sm.onCarMotion(event(InertiaKind.TURN_RIGHT))
        assertEquals(CompanionState.INERTIA_LEFT, sm.getCurrentState())
        sm.sharpTurn(TurnDirection.RIGHT)
        assertEquals(CompanionState.TURN_RIGHT, sm.getCurrentState())
        // nothing weaker cuts the turn: neither a lean of any level nor a second sharp turn
        sm.onCarMotion(event(InertiaKind.BRAKE, InertiaLevel.STRONG))
        sm.onCarMotion(event(InertiaKind.ACCELERATE))
        sm.sharpTurn(TurnDirection.LEFT)
        assertEquals(CompanionState.TURN_RIGHT, sm.getCurrentState())
        // ALERT cuts everything
        sm.onAlertTriggered()
        assertEquals(CompanionState.ALERT, sm.getCurrentState())
        sm.onOneShotCompleted(CompanionState.ALERT)
        assertEquals(CompanionState.RELIEF, sm.getCurrentState())
        sm.onCarMotion(event(InertiaKind.ACCELERATE, InertiaLevel.STRONG))
        assertEquals("RELIEF is not cut by a lean", CompanionState.RELIEF, sm.getCurrentState())
        sm.onOneShotCompleted(CompanionState.RELIEF)

        clock.advance(StateMachine.ALERT_COVERS_BRAKE_MS)
        // an ordinary lean does not replace another lean; an intensive one of another kind does; the same lean is
        // never restarted
        sm.onCarMotion(event(InertiaKind.ACCELERATE))
        assertEquals(CompanionState.INERTIA_BACK, sm.getCurrentState())
        sm.onCarMotion(event(InertiaKind.TURN_LEFT))
        assertEquals(CompanionState.INERTIA_BACK, sm.getCurrentState())
        sm.onCarMotion(event(InertiaKind.ACCELERATE, InertiaLevel.STRONG, upgrade = true))
        assertEquals(CompanionState.INERTIA_BACK, sm.getCurrentState())
        sm.onCarMotion(event(InertiaKind.BRAKE, InertiaLevel.STRONG))
        assertEquals(CompanionState.INERTIA_FORWARD, sm.getCurrentState())
        val leans = states.filter { it in CompanionState.INERTIA }
        assertEquals(listOf(CompanionState.INERTIA_LEFT, CompanionState.INERTIA_BACK, CompanionState.INERTIA_FORWARD), leans)
    }

    // ---- leans in the right direction, and none on untrusted data

    @Test
    fun speedingUpAndBrakingLeanTheRightWay() {
        for (mount in listOf(Mounts.UPRIGHT, Mounts.LANDSCAPE, Mounts.FLAT)) {
            val sm = machine()
            val sim = newSim(mount)
            connect(sim, sm)
            sim.calibrateOnStraightRoad()
            sim.cruise(5_000L, 30f)
            states.clear()
            sim.changeSpeed(55f, 1.7f)
            sim.cruise(5_000L, 55f)
            sim.changeSpeed(30f, 1.8f)
            sim.cruise(5_000L, 30f)
            assertEquals(listOf(CompanionState.INERTIA_BACK, CompanionState.INERTIA_FORWARD), reactions)
        }
    }

    @Test
    fun noReactionsBelowTheMinimumSpeedOrOnUntrustedData() {
        // crawling in a car park: turns and braking at 6 km/h
        run {
            val sm = machine()
            val sim = newSim()
            connect(sim, sm)
            sim.calibrateOnStraightRoad()
            sim.changeSpeed(6f, 1.5f)
            sim.cruise(3_000L, 6f)
            states.clear()
            sim.turn(3_000L, 1.5f)
            sim.drive(1_000L, -1.6f)
            sim.cruise(3_000L, sim.speedMps * 3.6f)
            assertTrue(reactions.toString(), reactions.isEmpty())
        }
        // no calibration yet: braking and speeding up do not lean (turns need none)
        run {
            val sm = machine()
            val sim = newSim()
            connect(sim, sm)
            sim.speedMps = 40f / 3.6f
            sim.cruise(3_000L, 40f)
            sim.drive(2_000L, -1.6f)
            sim.cruise(3_000L, sim.speedMps * 3.6f)
            assertFalse(reactions.toString(), CompanionState.INERTIA_FORWARD in reactions)
            assertFalse(sim.tracker.frame.isReady)
            assertTrue(sim.outcomes.toString(), sim.outcomes.filter { it.kind == InertiaKind.BRAKE }.none { it.accepted })
            assertTrue(sim.outcomes.toString(), sim.outcomes.any { it.reason == DiagnosticReason.CALIBRATION_UNRELIABLE })
        }
        // the phone handled while the car brakes and turns
        run {
            val sm = machine()
            val sim = newSim()
            connect(sim, sm)
            sim.calibrateOnStraightRoad()
            sim.cruise(5_000L, 40f)
            states.clear()
            sim.handle(1_500L, floatArrayOf(1f, 0.3f, 0f), 1.6f) { -1.8f to 0.15f }
            sim.handle(1_500L, floatArrayOf(1f, 0.3f, 0f), -1.6f) { -1.0f to 0.15f }
            assertTrue(reactions.toString(), reactions.isEmpty())
        }
        // a tunnel: no GPS, no speed, no lean
        run {
            val sm = machine()
            val sim = newSim()
            connect(sim, sm)
            sim.calibrateOnStraightRoad()
            sim.cruise(5_000L, 40f)
            states.clear()
            sim.gps = false
            sim.cruise(4_000L, 40f)
            sim.turn(3_000L, 1.8f)
            sim.changeSpeed(20f, 1.8f)
            sim.cruise(2_000L, 20f)
            assertTrue(reactions.toString(), reactions.isEmpty())
        }
    }

    @Test
    fun aDriveWithAPhoneMovingAlarmInATurnKeepsLeaning() {
        // The 7 November pattern: a PHONE_MOVING episode in a turn used to erase the axis (ORIENTATION_CHANGED), and
        // the next braking had no lean. Now the axis waits for one GPS lesson, which the braking itself gives.
        val sm = machine()
        val sim = newSim()
        connect(sim, sm)
        sim.calibrateOnStraightRoad()
        sim.cruise(2_000L, 30f, yaw = 0.3f)
        sim.handle(800L, floatArrayOf(1f, 0f, 0f), 1.2f) { 0f to 0.3f }
        sim.handle(800L, floatArrayOf(1f, 0f, 0f), -1.2f) { 0f to 0.3f }
        sim.cruise(3_000L, 30f, yaw = 0.3f)
        sim.cruise(6_000L, 30f)
        sim.changeSpeed(15f, 1.6f)                       // the first lesson of this braking confirms the axis
        sim.cruise(6_000L, 15f)
        states.clear()
        sim.changeSpeed(40f, 1.6f)
        sim.cruise(5_000L, 40f)
        sim.changeSpeed(20f, 1.8f)
        sim.cruise(5_000L, 20f)
        assertEquals(listOf(CompanionState.INERTIA_BACK, CompanionState.INERTIA_FORWARD), reactions)
    }

    // ---- wiring as in the service

    private fun connect(sim: CarSim, sm: StateMachine) {
        clock.now = sim.now
        var last: DrivingData = gpsFix(0f)
        sim.onStep = { t -> if (t > clock.now) scheduler.advance(t - clock.now) }
        sim.onFix = { kmh -> last = gpsFix(kmh); sm.updateDrivingData(last) }
        sim.onEvent = { sm.onCarMotion(it) }
        sim.onSharpTurn = { turning, dir -> sm.updateDrivingData(sensorEvent(last, isTurning = turning, turnDirection = dir)) }
        sim.onAlert = { sm.onAlertTriggered() }
    }

    private fun newSim(mount: Array<FloatArray> = Mounts.UPRIGHT, seed: Int = 11): CarSim {
        val tracker = CarMotionTracker()
        return CarSim(tracker, mount, BrakeDetector(-4f, tracker.frame), seed)
    }
}
