package com.drivecompanion.state

import com.drivecompanion.data.AppProfile
import com.drivecompanion.data.DrivingData
import com.drivecompanion.data.MediaData
import com.drivecompanion.data.PhoneCallData
import com.drivecompanion.data.SettingsRepository
import com.drivecompanion.data.TurnDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * State machine + director + a stand-in for CharacterView, run together: the curled-up sleep
 * sequence (curl_up → sleep_curled → wake_curled) and everything that can interrupt it.
 *
 * The invariant: a curled-up animation (sleep_curled, wake_curled) only plays when the fox really
 * lies curled up — right after curl_up reached its last frame, or after sleep_curled.
 */
class SleepSequenceIntegrationTest {

    private val clock = FakeClock()
    private val scheduler = FakeScheduler(clock)
    private val mood = CharacterMood(MemoryMoodStore(StoredMood(MoodSnapshot(10f, 50f, 50f), clock.now)), clock)
    private val director = AnimationDirector(mood, { sm.playContext() }, Random(3), clock)
    private lateinit var sm: StateMachine

    /** Which files the selected character has. Without fall_asleep, dozing off is always curl_up. */
    private var available: (AnimationVariant) -> Boolean = FOX_CURLING
    private val played = mutableListOf<Pair<CompanionState, String>>()

    /** For each entry of [played]: whether the character had the curled-up files at that moment. */
    private val curledFiles = mutableListOf<Boolean>()

    /** The pending "animation reached its last frame" event of the view, replaced on every state change. */
    private var ending: Runnable? = null

    @Before
    fun setUp() {
        sm = StateMachine(SettingsRepository(InMemoryPrefs()), clock, scheduler, Random(9), mood)
        sm.idleBusy = { director.isSceneRunning() }
        sm.setListener(object : StateMachine.Listener {
            override fun onStateChanged(newState: CompanionState, previousState: CompanionState) = show(newState)
            override fun onWeatherModifierChanged(modifier: WeatherModifier) {}
        })
        sm.setProfile(AppProfile.DAILY)
    }

    /** What CharacterView does: pick, play, report the end (one-shots) or pick again (CALM/MUSIC cycle). */
    private fun show(state: CompanionState) {
        ending?.let { scheduler.cancel(it) }
        val v = director.pick(state, available)!!
        played += state to v.resName
        curledFiles += available(AnimationVariant("wake_curled"))
        val cycles = state.isLooping && AnimationPools.forState(state).variants.size > 1
        if (!state.isLooping || cycles) {
            val end = Runnable {
                director.onFinished(state, v)
                if (state.isLooping) show(state) else sm.onOneShotCompleted(state)
            }
            ending = end
            scheduler.postDelayed(end, DURATION_MS[v.resName] ?: 2_000L)
        } else {
            ending = null
        }
    }

    private val state get() = sm.getCurrentState()

    private fun lastPlayed() = played.last().second

    /** Lets time pass until [target] starts (fails after an hour of simulated time). */
    private fun runUntil(target: CompanionState) {
        var steps = 0
        while (state != target) {
            scheduler.advance(250)
            check(++steps < 4 * 3600) { "$target never reached; played: ${played.takeLast(5)}" }
        }
    }

    private fun sleepCurledUp() {
        runUntil(CompanionState.SLEEP)
        assertEquals("sleep_curled", lastPlayed())
        assertCurledOnlyWhenCurled()
    }

    /** sleep_curled / wake_curled only follow a finished curl_up or sleep_curled. */
    private fun assertCurledOnlyWhenCurled() {
        played.forEachIndexed { i, (_, name) ->
            if (name == "sleep_curled" || name == "wake_curled") {
                val before = played.getOrNull(i - 1)?.second
                assertTrue("$name after $before: ${played.takeLast(6)}", before == "curl_up" || before == "sleep_curled")
            }
        }
        for (i in 1 until played.size) {
            if (played[i - 1].second == "sleep_curled" && played[i].first == CompanionState.WAKE && curledFiles[i]) assertEquals("woke up from the curled pose with the sitting animation", "wake_curled", played[i].second)
        }
    }

    @Test
    fun fullSequenceThenTap() {
        sleepCurledUp()
        sm.onTap()
        assertEquals(CompanionState.WAKE, state)
        assertEquals("wake_curled", lastPlayed())
        scheduler.advance(2_500)
        assertEquals(CompanionState.CALM, state)
        assertCurledOnlyWhenCurled()
    }

    @Test
    fun tapWhileLyingDownUsesTheUsualWake() {
        runUntil(CompanionState.FALL_ASLEEP)
        assertEquals("curl_up", lastPlayed())
        scheduler.advance(1_000)                 // still lying down
        sm.onTap()
        assertEquals(CompanionState.WAKE, state)
        assertEquals("wake", lastPlayed())
        scheduler.advance(2_500)
        assertEquals(CompanionState.CALM, state)
        assertCurledOnlyWhenCurled()
    }

    @Test
    fun musicWakesUpFromTheCurledPose() {
        sleepCurledUp()
        sm.updateMediaData(MediaData(isPlaying = true))
        assertEquals("wake_curled", lastPlayed())
        scheduler.advance(2_500)
        assertEquals(CompanionState.MUSIC, state)
        assertCurledOnlyWhenCurled()
    }

    @Test
    fun musicWhileCurlingUpWakesUpAfterwards() {
        runUntil(CompanionState.FALL_ASLEEP)
        scheduler.advance(1_000)
        sm.updateMediaData(MediaData(isPlaying = true))
        scheduler.advance(3_100)                 // curl_up reaches its last frame
        assertEquals(CompanionState.WAKE, state)
        assertEquals("wake_curled", lastPlayed())
        scheduler.advance(2_500)
        assertEquals(CompanionState.MUSIC, state)
        assertCurledOnlyWhenCurled()
    }

    @Test
    fun phoneCallWakesUpFromTheCurledPose() {
        sleepCurledUp()
        sm.updatePhoneCallData(PhoneCallData(isInCall = true))
        assertEquals("wake_curled", lastPlayed())
        scheduler.advance(2_500)
        assertEquals(CompanionState.PHONE_CALL, state)
        sm.updatePhoneCallData(PhoneCallData(isInCall = false))
        assertEquals(CompanionState.CALM, state)
        assertCurledOnlyWhenCurled()
    }

    @Test
    fun brakingDropsTheSequence() {
        sleepCurledUp()
        sm.onAlertTriggered()
        assertEquals(CompanionState.ALERT, state)
        scheduler.advance(2_100)
        assertEquals(CompanionState.RELIEF, state)
        scheduler.advance(2_100)
        assertEquals(CompanionState.CALM, state)
        // Falls asleep again later: a fresh sequence, never a leftover curled wake.
        sleepCurledUp()
        sm.onTap()
        assertEquals("wake_curled", lastPlayed())
        assertCurledOnlyWhenCurled()
        assertEquals(1, played.count { it.second == "wake_curled" })
    }

    @Test
    fun sharpTurnDropsTheSequence() {
        sm.setProfile(AppProfile.AUTO)
        sm.updateDrivingData(DrivingData(speedKmh = 0f, hasGpsFix = true))
        sleepCurledUp()
        sm.updateDrivingData(DrivingData(speedKmh = 30f, hasGpsFix = true))
        sm.updateDrivingData(DrivingData(speedKmh = 30f, isTurning = true, turnDirection = TurnDirection.LEFT, hasGpsFix = true))
        assertTrue(state == CompanionState.TURN_LEFT || state == CompanionState.TRIP_START)
        scheduler.advance(10_000)
        assertFalse(played.drop(1).any { it.second == "wake_curled" })
        assertCurledOnlyWhenCurled()
    }

    @Test
    fun switchingToTheSlimeWhileCurledUp() {
        sleepCurledUp()
        available = AnimationDirectorTest.SLIME
        sm.onTap()
        assertEquals(CompanionState.WAKE, state)
        assertEquals("wake", lastPlayed())          // the slime's WAKE (its file falls back to blink)
        scheduler.advance(2_500)
        assertEquals(CompanionState.CALM, state)
    }

    @Test
    fun profileSwitchWhileCurledUp() {
        sleepCurledUp()
        director.reset()                            // what CompanionService does on a profile switch
        sm.setProfile(AppProfile.AUTO)
        assertEquals(CompanionState.CALM, state)
        assertFalse(sm.isBusy())
        sm.onTap()
        assertEquals(CompanionState.TAP, state)
        assertEquals("wake", director.pick(CompanionState.WAKE, available)!!.resName)
    }

    @Test
    fun longSessionNeverPlaysCurledAnimationsOutOfPlace() {
        // Three hours with random interruptions of every kind, now and then (≈ every 7 minutes),
        // so the fox also gets to fall asleep in between.
        val rnd = Random(21)
        repeat(180) { minute ->
            repeat(4) {
                scheduler.advance(15_000)
                when (rnd.nextInt(28)) {
                    0 -> sm.onTap()
                    1 -> sm.updateMediaData(MediaData(isPlaying = rnd.nextBoolean()))
                    2 -> sm.updatePhoneCallData(PhoneCallData(isInCall = rnd.nextBoolean()))
                    3 -> { clock.advance(4_000); sm.onAlertTriggered() }
                    4 -> available = if (rnd.nextBoolean()) FOX_CURLING else AnimationDirectorTest.SLIME
                }
            }
            if (minute % 30 == 29) {
                sm.updateMediaData(MediaData(isPlaying = false))
                sm.updatePhoneCallData(PhoneCallData(isInCall = false))
            }
        }
        assertTrue("the sequence was exercised", played.count { it.second == "sleep_curled" } >= 3)
        assertTrue("and left in more than one way", played.zipWithNext()
            .filter { (a, _) -> a.second == "sleep_curled" }.map { (_, b) -> b.first }.toSet().size >= 2)
        assertCurledOnlyWhenCurled()
    }

    companion object {
        /** The fox, minus fall_asleep: dozing off always curls up. */
        val FOX_CURLING: (AnimationVariant) -> Boolean = { it.resName != "fall_asleep" }

        /** Lengths of the real files (see AnimationResourcesTest); 2 s for everything else. */
        val DURATION_MS = mapOf("curl_up" to 4_000L, "wake_curled" to 2_400L, "butterfly" to 5_000L, "grooming" to 4_000L)
    }
}
