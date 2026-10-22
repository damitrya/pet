package com.damitrya.mimio.state

import android.content.ContextWrapper
import com.damitrya.mimio.data.AppProfile
import com.damitrya.mimio.data.ProfileManager
import com.damitrya.mimio.data.ProfileSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Sleep limits by profile (DAILY: the activity mode, AUTO: 15 / 30 minutes), profile switches by hand and by the
 * car's Bluetooth, waking up when the car drives off, and blinks that must not hold self-started behaviour back.
 */
class SleepByProfileTest {

    /** A session wired like CompanionService: ProfileSelector → ProfileManager → director.reset() + setProfile. */
    private inner class Car(mode: CompanionActivityMode = CompanionActivityMode.DEFAULT, var overlayVisible: Boolean = true) {
        val s = ActivitySession(mode, seed = 31)
        val selector = ProfileSelector(s.clock)
        val manager = ProfileManager(ContextWrapper(null), s.settings).apply {
            setListener(object : ProfileManager.Listener {
                override fun onProfileChanged(newProfile: AppProfile, previousProfile: AppProfile) {
                    s.director.reset()
                    s.sm.setProfile(newProfile)
                    if (newProfile == AppProfile.AUTO && overlayVisible) s.sm.onCarEntered()
                }
            })
        }

        /** CompanionService.applyProfileSetting with the given profile mode. */
        fun apply(profileMode: String) {
            s.settings.profileMode = profileMode
            manager.switchProfile(selector.resolve(profileMode))
        }

        fun bluetooth(connected: Boolean) {
            selector.onCarBluetooth(connected)
            apply(ProfileSelector.MODE_AUTO_BLUETOOTH)
        }

        /** [ms] of GPS fixes at [kmh], one a second (as SensorDataProvider delivers them). */
        fun drive(kmh: Float, ms: Long) {
            var left = ms
            while (left > 0) {
                s.sm.updateDrivingData(gpsFix(kmh))
                s.run(minOf(1_000L, left))
                left -= 1_000L
            }
        }

        /** When [state] first started at or after [from] (null: never). */
        fun firstAt(state: CompanionState, from: Long): Long? =
            s.played.firstOrNull { it.first >= from && it.second == state }?.first
    }

    // ---- DAILY

    @Test
    fun dailyFollowsTheActivityMode() {
        for (mode in CompanionActivityMode.values()) {
            val c = Car(mode)
            c.apply(ProfileSelector.MODE_MANUAL_DAILY)
            val start = c.s.clock.now
            c.s.run(mode.sleepAfterMs + MIN)
            val drowsy = c.firstAt(CompanionState.DROWSY, start)!! - start
            val asleep = c.firstAt(CompanionState.FALL_ASLEEP, start)!! - start
            assertTrue("$mode drowsy after ${drowsy / 1000} s", drowsy in mode.drowsyAfterMs..mode.drowsyAfterMs + SLACK)
            assertTrue("$mode asleep after ${asleep / 1000} s", asleep in mode.sleepAfterMs..mode.sleepAfterMs + SLACK)
            assertEquals(CompanionState.SLEEP, c.s.state)
        }
    }

    // ---- AUTO, by hand and by Bluetooth

    private fun assertCarLimits(c: Car, from: Long, label: String) {
        c.s.run(StateMachine.CAR_DROWSY_AFTER_MS - 30_000L)
        assertEquals("$label: not drowsy before 15 min", null, c.firstAt(CompanionState.DROWSY, from))
        c.s.run(StateMachine.CAR_SLEEP_AFTER_MS - StateMachine.CAR_DROWSY_AFTER_MS + 30_000L + SLACK)
        val drowsy = c.firstAt(CompanionState.DROWSY, from)!! - from
        val asleep = c.firstAt(CompanionState.FALL_ASLEEP, from)!! - from
        assertTrue("$label drowsy after ${drowsy / 1000} s", drowsy in StateMachine.CAR_DROWSY_AFTER_MS..StateMachine.CAR_DROWSY_AFTER_MS + SLACK)
        assertTrue("$label asleep after ${asleep / 1000} s", asleep in StateMachine.CAR_SLEEP_AFTER_MS..StateMachine.CAR_SLEEP_AFTER_MS + SLACK)
    }

    @Test
    fun manualAutoUsesTheCarLimitsWhateverTheMode() {
        for (mode in CompanionActivityMode.values()) {
            val c = Car(mode)
            c.apply(ProfileSelector.MODE_MANUAL_AUTO)
            assertEquals(AppProfile.AUTO, c.manager.getCurrentProfile())
            assertCarLimits(c, c.s.clock.now, "manual AUTO, $mode")
        }
    }

    @Test
    fun bluetoothAutoUsesTheCarLimits() {
        val c = Car(CompanionActivityMode.CALM)
        c.apply(ProfileSelector.MODE_AUTO_BLUETOOTH)
        assertEquals(AppProfile.DAILY, c.manager.getCurrentProfile())
        c.bluetooth(connected = true)
        assertEquals("the real profile comes from ProfileManager", AppProfile.AUTO, c.manager.getCurrentProfile())
        assertCarLimits(c, c.s.clock.now, "Bluetooth AUTO")
    }

    @Test
    fun leavingTheCarByBluetoothBringsBackTheDailyLimits() {
        val c = Car(CompanionActivityMode.LIVELY)
        c.apply(ProfileSelector.MODE_AUTO_BLUETOOTH)
        c.bluetooth(connected = true)
        c.s.sm.onTap()
        c.bluetooth(connected = false)
        c.s.run(ProfileSelector.LEAVE_DELAY_MS)
        assertEquals("still AUTO during the leave delay", AppProfile.AUTO, c.manager.getCurrentProfile())
        c.apply(ProfileSelector.MODE_AUTO_BLUETOOTH)                     // the service's re-check after the delay
        assertEquals(AppProfile.DAILY, c.manager.getCurrentProfile())
        val back = c.s.clock.now
        c.s.run(10 * MIN)
        // 2 min since the tap: the daily limits count from it, not from the switch.
        val drowsy = c.firstAt(CompanionState.DROWSY, back)!! - back
        val lively = CompanionActivityMode.LIVELY
        assertTrue("drowsy ${drowsy / 1000} s after the switch",
            drowsy in lively.drowsyAfterMs - ProfileSelector.LEAVE_DELAY_MS..lively.drowsyAfterMs - ProfileSelector.LEAVE_DELAY_MS + SLACK)
    }

    // ---- switches

    @Test
    fun dailyToAutoKeepsTheLastActivity() {
        // The overlay is hidden: getting into the car plays nothing and is no activity of its own.
        val c = Car(CompanionActivityMode.LIVELY, overlayVisible = false)
        c.apply(ProfileSelector.MODE_MANUAL_DAILY)
        val lastTap = c.s.clock.now
        c.s.sm.onTap()
        c.s.run(3 * MIN)                                                  // below LIVELY's 4 / 5 minutes
        c.apply(ProfileSelector.MODE_MANUAL_AUTO)
        c.s.run(20 * MIN)
        assertEquals("no daily dozing off in the car", null, c.firstAt(CompanionState.FALL_ASLEEP, lastTap))
        val drowsy = c.firstAt(CompanionState.DROWSY, lastTap)!! - lastTap
        assertTrue("drowsy ${drowsy / 1000} s after the last tap, not after the switch",
            drowsy in StateMachine.CAR_DROWSY_AFTER_MS..StateMachine.CAR_DROWSY_AFTER_MS + SLACK)
    }

    @Test
    fun gettingIntoTheCarWithTheOverlayShownIsActivity() {
        val c = Car(CompanionActivityMode.CALM)
        c.apply(ProfileSelector.MODE_MANUAL_DAILY)
        c.s.run(150_000)                                                  // nearly asleep in DAILY / CALM
        c.apply(ProfileSelector.MODE_MANUAL_AUTO)                         // TRIP_PREPARE: buckles up
        val enteredAt = c.s.clock.now
        assertEquals(CompanionState.TRIP_PREPARE, c.s.state)
        c.s.run(10 * MIN)
        assertEquals(null, c.firstAt(CompanionState.FALL_ASLEEP, enteredAt))
    }

    @Test
    fun backFromALongTripTheFoxDoesNotDozeOffAtOnce() {
        val c = Car(CompanionActivityMode.LIVELY)
        c.apply(ProfileSelector.MODE_MANUAL_AUTO)
        c.s.run(20 * MIN)                                                 // a quiet car: 20 min without activity
        assertTrue(c.firstAt(CompanionState.DROWSY, 0L) != null)
        c.apply(ProfileSelector.MODE_MANUAL_DAILY)                        // DAILY's 4 / 5 min are long passed
        val back = c.s.clock.now
        c.s.run(MIN)
        assertEquals("not straight to sleep", null, c.firstAt(CompanionState.FALL_ASLEEP, back))
        assertEquals(null, c.firstAt(CompanionState.DROWSY, back))
        c.s.run(6 * MIN)
        val lively = CompanionActivityMode.LIVELY
        val drowsy = c.firstAt(CompanionState.DROWSY, back)!! - back
        val asleep = c.firstAt(CompanionState.FALL_ASLEEP, back)!! - back
        assertTrue("a new wake period: drowsy ${drowsy / 1000} s", drowsy in lively.drowsyAfterMs..lively.drowsyAfterMs + SLACK)
        assertTrue("asleep ${asleep / 1000} s", asleep in lively.sleepAfterMs..lively.sleepAfterMs + SLACK)
    }

    @Test
    fun backBeforeTheDailyLimitCountsFromTheLastActivity() {
        val c = Car(CompanionActivityMode.LIVELY)
        c.apply(ProfileSelector.MODE_MANUAL_AUTO)
        c.s.sm.onTap()
        val lastTap = c.s.clock.now
        c.s.run(2 * MIN)
        c.apply(ProfileSelector.MODE_MANUAL_DAILY)
        c.s.run(8 * MIN)
        val drowsy = c.firstAt(CompanionState.DROWSY, lastTap)!! - lastTap
        val lively = CompanionActivityMode.LIVELY
        assertTrue("no artificial reset: drowsy ${drowsy / 1000} s after the tap", drowsy in lively.drowsyAfterMs..lively.drowsyAfterMs + SLACK)
    }

    // ---- driving

    @Test
    fun neverAsleepWhileTheCarIsMoving() {
        for (mode in CompanionActivityMode.values()) {
            val c = Car(mode)
            c.apply(ProfileSelector.MODE_MANUAL_AUTO)
            val start = c.s.clock.now
            c.drive(60f, 45 * MIN)
            assertEquals("$mode", null, c.firstAt(CompanionState.DROWSY, start))
            assertEquals("$mode", null, c.firstAt(CompanionState.FALL_ASLEEP, start))
            assertFalse(c.s.state == CompanionState.SLEEP)
        }
    }

    @Test
    fun asleepInTheCarWakesUpWhenItDrivesOff() {
        val c = Car(CompanionActivityMode.LIVELY)
        c.apply(ProfileSelector.MODE_MANUAL_AUTO)
        c.drive(60f, 35 * MIN)                                            // a long, quiet drive: awake
        c.drive(0f, 2 * MIN)                                              // a stop: too short for TRIP_START
        assertEquals("the fox dozed off at the stop", CompanionState.SLEEP, c.s.state)
        val off = c.s.clock.now
        c.drive(40f, 3_000L)
        assertTrue(c.s.played.toString().takeLast(300), c.firstAt(CompanionState.WAKE, off) != null)
        c.drive(40f, 10_000L)
        assertEquals(CompanionState.CALM, c.s.state)
        c.drive(60f, 40 * MIN)
        assertEquals("and stays awake while driving", null, c.firstAt(CompanionState.FALL_ASLEEP, off))
    }

    @Test
    fun dozingOffInTheCarIsCutShortWhenItDrivesOff() {
        val c = Car(CompanionActivityMode.LIVELY)
        c.apply(ProfileSelector.MODE_MANUAL_AUTO)
        c.drive(60f, 35 * MIN)
        var steps = 0
        while (c.s.state != CompanionState.FALL_ASLEEP) { c.drive(0f, 250L); check(++steps < 2_000) }
        c.drive(30f, 1_000L)
        assertEquals(CompanionState.WAKE, c.s.state)
    }

    @Test
    fun afterParkingDrivingOffStillPlaysTheTripStart() {
        val c = Car(CompanionActivityMode.LIVELY)
        c.apply(ProfileSelector.MODE_MANUAL_AUTO)
        c.drive(0f, 32 * MIN)                                             // parked for long: asleep
        assertEquals(CompanionState.SLEEP, c.s.state)
        val off = c.s.clock.now
        c.drive(30f, 3_000L)
        assertTrue(c.firstAt(CompanionState.TRIP_START, off) != null)
    }

    @Test
    fun dailyIsNotWokenByGpsNoise() {
        // Movement only wakes the fox in the car profile (DAILY has no GPS consumers; this guards the rule).
        val c = Car(CompanionActivityMode.CALM)
        c.apply(ProfileSelector.MODE_MANUAL_DAILY)
        c.s.run(4 * MIN)
        assertEquals(CompanionState.SLEEP, c.s.state)
        c.s.sm.updateDrivingData(gpsFix(20f))
        assertEquals(CompanionState.SLEEP, c.s.state)
    }

    // ---- blinks and the activity plan

    @Test
    fun aBlinkMovesNoActivityWindow() {
        val a = ActivityScheduler({ CompanionActivityMode.LIVELY }, Random(2))
        val ctx = PlayContext(sinceInteractionMs = 0L)
        val m = MoodSnapshot.NEUTRAL
        var now = 1_000_000L
        a.decide(now, ctx, m)
        a.onPicked("idle_small", ActivityCategory.AMBIENT, ActivityDecision(ActivityCategory.AMBIENT), now, ctx, m)
        repeat(20) {
            now += 1_500L
            val before = a.stats(now)
            a.onBlink(now)                                                // CALM → BLINK
            val after = a.stats(now)
            assertEquals(before.nextMicroInMs, after.nextMicroInMs)
            assertEquals(before.nextSceneInMs, after.nextSceneInMs)
            now += 1_200L
            val d = a.decide(now, ctx, m)                                 // BLINK → CALM: decided as usual
            a.onPicked(if (d.category == ActivityCategory.AMBIENT) "idle_small" else "tail_wag", d.category, d, now, ctx, m)
        }
        assertTrue("windows still open while blinking", a.stats(now).micro > 0)
    }

    @Test
    fun frequentBlinksDoNotPostponeScenes() {
        val mode = CompanionActivityMode.LIVELY
        val s = ActivitySession(mode, seed = 32)
        s.run(mode.drowsyAfterMs - 10_000L)                               // alone, awake, blinking every 3–7 s
        val blinks = s.played.count { it.second == CompanionState.BLINK }
        assertTrue("blinks $blinks", blinks >= 30)
        val calm = AnimationPools.forState(CompanionState.CALM).variants.associateBy { it.resName }
        val actions = s.played.filter { it.second == CompanionState.CALM && calm.getValue(it.third).category != ActivityCategory.AMBIENT }
        val scenes = actions.filter { calm.getValue(it.third).category == ActivityCategory.SCENE }.map { it.first }
        assertTrue("scenes $scenes", scenes.size >= 2)
        // From the start (and between scenes) no longer than the mode's upper bound plus one animation and a blink.
        (listOf(s.played.first().first) + scenes).zipWithNext().forEach { (a, b) ->
            assertTrue("scene ${(b - a) / 1000} s after the previous", b - a <= mode.sceneMaxMs + 8_000L)
        }
        actions.map { it.first }.zipWithNext().forEach { (a, b) ->
            assertTrue("an action ${(b - a) / 1000.0} s after the previous", b - a <= mode.microMaxMs + 8_000L)
        }
    }

    companion object {
        private const val MIN = 60_000L

        /** A limit is checked at the next blink tick (3–7 s) and a scene must end first: a few seconds late is fine. */
        private const val SLACK = 15_000L
    }
}
