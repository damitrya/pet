package com.drivecompanion.state

import com.drivecompanion.data.AppProfile
import com.drivecompanion.data.BatteryData
import com.drivecompanion.data.DrivingData
import com.drivecompanion.data.MediaData
import com.drivecompanion.data.PhoneCallData
import com.drivecompanion.data.SettingsRepository
import com.drivecompanion.data.TimeData
import com.drivecompanion.data.TurnDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

class StateMachineTest {

    private val clock = FakeClock()
    private val scheduler = FakeScheduler(clock)
    private val mood = CharacterMood(MemoryMoodStore(), clock)
    private val states = mutableListOf<CompanionState>()
    private lateinit var sm: StateMachine

    /** Plays one-shots like CharacterView would: they end after [oneShotMs]; null = never end. */
    private var oneShotMs: Long? = 2000L

    @Before
    fun setUp() {
        sm = StateMachine(SettingsRepository(InMemoryPrefs()), clock, scheduler, Random(5), mood)
        sm.setListener(object : StateMachine.Listener {
            override fun onStateChanged(newState: CompanionState, previousState: CompanionState) {
                states += newState
                val ms = oneShotMs
                if (!newState.isLooping && ms != null) {
                    scheduler.postDelayed(Runnable { sm.onOneShotCompleted(newState) }, ms)
                }
            }
            override fun onWeatherModifierChanged(modifier: WeatherModifier) {}
        })
        sm.setProfile(AppProfile.AUTO)
    }

    private val state get() = sm.getCurrentState()

    /** Everything that may happen in the background while a high-priority reaction plays. */
    private fun backgroundNoise() {
        sm.updateBatteryData(BatteryData(level = 5, isLow = true))
        sm.updateTimeData(TimeData(isFirstUnlockOfDay = true))
        sm.updateDrivingData(DrivingData(speedKmh = 50f, hasGpsFix = true))
        sm.updateDrivingData(DrivingData(speedKmh = 50f, isTurning = true, turnDirection = TurnDirection.LEFT, hasGpsFix = true))
        sm.updateDrivingData(DrivingData(speedKmh = 50f, hasGpsFix = true))
        sm.updateMediaData(MediaData(isPlaying = true))
        sm.updateMediaData(MediaData(isPlaying = false))
        sm.onTap()
    }

    @Test
    fun alertIsNotInterruptedByBackgroundEvents() {
        oneShotMs = null
        sm.onAlertTriggered()
        assertEquals(CompanionState.ALERT, state)
        backgroundNoise()
        scheduler.advance(10_000)            // blink / fidget timers must not fire into ALERT
        assertEquals(CompanionState.ALERT, state)
        sm.onLongPress()
        assertEquals(CompanionState.ALERT, state)
        sm.onOneShotCompleted(CompanionState.ALERT)
        assertEquals(CompanionState.RELIEF, state)
    }

    @Test
    fun petIsNotInterruptedByBackgroundEvents() {
        sm.onLongPress()
        assertEquals(CompanionState.PET, state)
        backgroundNoise()
        scheduler.advance(60_000)
        assertEquals(CompanionState.PET, state)
        sm.onLongPressReleased()
        assertEquals(CompanionState.HEARTS, state)
    }

    @Test
    fun alertInterruptsPetAndIdleScenes() {
        sm.idleBusy = { true }
        sm.onLongPress()
        sm.onAlertTriggered()
        assertEquals(CompanionState.ALERT, state)
        assertFalse(states.contains(CompanionState.BLINK))
    }

    @Test
    fun idleSceneDelaysBlinkButNotReactions() {
        var busy = true
        sm.idleBusy = { busy }
        scheduler.advance(20_000)
        assertEquals("no blink while a scene plays", CompanionState.CALM, state)
        sm.onTap()
        assertEquals("user reactions still come at once", CompanionState.TAP, state)
        scheduler.advance(2_500)
        assertEquals(CompanionState.CALM, state)
        busy = false
        scheduler.advance(8_000)
        assertTrue(states.contains(CompanionState.BLINK))
    }

    @Test
    fun stuckOneShotIsEndedByTheWatchdog() {
        oneShotMs = null                     // the end of the animation is never reported
        sm.onTap()
        assertEquals(CompanionState.TAP, state)
        scheduler.advance(StateMachine.ONE_SHOT_TIMEOUT_MS)
        assertEquals(CompanionState.CALM, state)

        sm.onAlertTriggered()
        scheduler.advance(StateMachine.ONE_SHOT_TIMEOUT_MS)
        assertEquals(CompanionState.RELIEF, state)
        scheduler.advance(StateMachine.ONE_SHOT_TIMEOUT_MS)
        assertEquals(CompanionState.CALM, state)
        assertFalse("alert guard released", sm.isBusy())
    }

    @Test
    fun loopingStatesHaveNoWatchdog() {
        oneShotMs = null
        sm.onLongPress()
        scheduler.advance(10 * StateMachine.ONE_SHOT_TIMEOUT_MS)
        assertEquals(CompanionState.PET, state)
    }

    @Test
    fun profileSwitchNeverLeavesTheCharacterStuck() {
        oneShotMs = null
        sm.onLongPress()
        sm.setProfile(AppProfile.DAILY)
        assertEquals(CompanionState.CALM, state)
        assertFalse(sm.isBusy())
        sm.onTap()
        assertEquals(CompanionState.TAP, state)

        sm.onAlertTriggered()
        sm.setProfile(AppProfile.AUTO)
        assertEquals(CompanionState.CALM, state)
        assertFalse(sm.isBusy())
        clock.advance(5_000)
        sm.onAlertTriggered()
        assertEquals(CompanionState.ALERT, state)
    }

    @Test
    fun fallsAsleepAndWakesUpThroughTheWholeSequence() {
        scheduler.advance(4 * 60_000L)
        assertTrue(states.contains(CompanionState.DROWSY))
        assertTrue(states.contains(CompanionState.FALL_ASLEEP))
        assertEquals(CompanionState.SLEEP, state)
        assertTrue(mood.asleep)
        sm.onTap()
        assertEquals(CompanionState.WAKE, state)
        scheduler.advance(2_500)
        assertEquals(CompanionState.CALM, state)
        assertFalse(mood.asleep)
    }

    @Test
    fun doesNotFallAsleepWhileDriving() {
        repeat(60) {
            sm.updateDrivingData(DrivingData(speedKmh = 60f, hasGpsFix = true))
            scheduler.advance(5_000)
        }
        assertFalse(states.contains(CompanionState.FALL_ASLEEP))
        assertTrue(sm.playContext().moving)
    }

    @Test
    fun phoneCallAndMusicStillWork() {
        sm.updateMediaData(MediaData(isPlaying = true))
        assertEquals(CompanionState.MUSIC, state)
        sm.updatePhoneCallData(PhoneCallData(isInCall = true))
        assertEquals(CompanionState.PHONE_CALL, state)
        sm.updatePhoneCallData(PhoneCallData(isInCall = false))
        assertEquals(CompanionState.MUSIC, state)
        assertTrue(sm.playContext().music)
    }

    @Test
    fun startingToDriveCutsADistractingScene() {
        var scene = true
        var cuts = 0
        sm.distractingScene = { scene }
        sm.onSceneCut = { cuts++ }
        sm.updateDrivingData(DrivingData(speedKmh = 0f, hasGpsFix = true))
        sm.updateDrivingData(DrivingData(speedKmh = 12f, hasGpsFix = true))
        assertEquals(1, cuts)
        sm.updateDrivingData(DrivingData(speedKmh = 30f, hasGpsFix = true))
        assertEquals("only on the start of movement", 1, cuts)

        scene = false
        sm.updateDrivingData(DrivingData(speedKmh = 0f, hasGpsFix = true))
        sm.updateDrivingData(DrivingData(speedKmh = 20f, hasGpsFix = true))
        assertEquals("nothing to cut", 1, cuts)
    }

    @Test
    fun startingToDriveEndsADistractingFidget() {
        oneShotMs = null
        sm.distractingScene = { true }
        sm.updateDrivingData(DrivingData(speedKmh = 0f, hasGpsFix = true))
        sm.updateMediaData(MediaData(isPlaying = true))
        scheduler.advance(41_000)                       // FIDGET break during the music
        assertEquals(CompanionState.FIDGET, state)
        sm.updateDrivingData(DrivingData(speedKmh = 10f, hasGpsFix = true))   // below the trip-start speed
        assertEquals(CompanionState.MUSIC, state)
    }

    @Test
    fun startingToDriveLeavesReactionsAlone() {
        oneShotMs = null
        var cuts = 0
        sm.distractingScene = { true }
        sm.onSceneCut = { cuts++ }
        sm.updateDrivingData(DrivingData(speedKmh = 0f, hasGpsFix = true))
        sm.onTap()
        sm.updateDrivingData(DrivingData(speedKmh = 10f, hasGpsFix = true))
        assertEquals(CompanionState.TAP, state)
        assertEquals(0, cuts)
    }

    @Test
    fun shortStopStillCountsAsDriving() {
        sm.updateDrivingData(DrivingData(speedKmh = 40f, hasGpsFix = true))
        clock.advance(5_000)
        sm.updateDrivingData(DrivingData(speedKmh = 0f, hasGpsFix = true))   // traffic light
        clock.advance(30_000)
        sm.updateDrivingData(DrivingData(speedKmh = 0f, hasGpsFix = true))
        assertTrue("no scenes at a traffic light", sm.playContext().moving)
        clock.advance(31_000)
        sm.updateDrivingData(DrivingData(speedKmh = 0f, hasGpsFix = true))
        assertFalse("a longer stop allows scenes again", sm.playContext().moving)
    }

    @Test
    fun musicWhileDozingOffWakesUpFirst() {
        var steps = 0
        while (state != CompanionState.FALL_ASLEEP) { scheduler.advance(250); check(++steps < 4000) }
        sm.updateMediaData(MediaData(isPlaying = true))
        assertEquals("dozing off is not cut short", CompanionState.FALL_ASLEEP, state)
        scheduler.advance(2_100)
        assertEquals(CompanionState.WAKE, state)
        scheduler.advance(2_100)
        assertEquals(CompanionState.MUSIC, state)
    }

    @Test
    fun interactionsFeedTheMood() {
        val before = mood.snapshot().affection
        sm.onLongPress()
        sm.onLongPressReleased()
        assertTrue(mood.snapshot().affection > before)
        assertTrue(sm.playContext().sinceInteractionMs < 1000)
    }
}
