package com.damitrya.mimio.state

import com.damitrya.mimio.data.AppProfile
import com.damitrya.mimio.data.BatteryData
import com.damitrya.mimio.data.DrivingData
import com.damitrya.mimio.data.MediaData
import com.damitrya.mimio.data.PhoneCallData
import com.damitrya.mimio.data.SettingsRepository
import com.damitrya.mimio.data.TimeData
import com.damitrya.mimio.data.TurnDirection
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
        sm.updateDrivingData(gpsFix(50f))
        sm.updateDrivingData(gpsFix(50f, isTurning = true, turnDirection = TurnDirection.LEFT))
        sm.updateDrivingData(gpsFix(50f))
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
        // Default mode "lively": drowsy after 4 minutes, asleep after 5 (calm: 2 / 3, see Wave6Test).
        scheduler.advance(CompanionActivityMode.DEFAULT.sleepAfterMs + 60_000L)
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
            sm.updateDrivingData(gpsFix(60f))
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
        sm.updateDrivingData(gpsFix(0f))
        sm.updateDrivingData(gpsFix(12f))
        assertEquals(1, cuts)
        sm.updateDrivingData(gpsFix(30f))
        assertEquals("only on the start of movement", 1, cuts)

        scene = false
        sm.updateDrivingData(gpsFix(0f))
        sm.updateDrivingData(gpsFix(20f))
        assertEquals("nothing to cut", 1, cuts)
    }

    @Test
    fun startingToDriveEndsADistractingFidget() {
        oneShotMs = null
        sm.distractingScene = { true }
        sm.updateDrivingData(gpsFix(0f))
        sm.updateMediaData(MediaData(isPlaying = true))
        scheduler.advance(41_000)                       // FIDGET break during the music
        assertEquals(CompanionState.FIDGET, state)
        sm.updateDrivingData(gpsFix(10f))   // below the trip-start speed
        assertEquals(CompanionState.MUSIC, state)
    }

    @Test
    fun startingToDriveLeavesReactionsAlone() {
        oneShotMs = null
        var cuts = 0
        sm.distractingScene = { true }
        sm.onSceneCut = { cuts++ }
        sm.updateDrivingData(gpsFix(0f))
        sm.onTap()
        sm.updateDrivingData(gpsFix(10f))
        assertEquals(CompanionState.TAP, state)
        assertEquals(0, cuts)
    }

    @Test
    fun shortStopStillCountsAsDriving() {
        sm.updateDrivingData(gpsFix(40f))
        clock.advance(5_000)
        sm.updateDrivingData(gpsFix(0f))   // traffic light
        clock.advance(30_000)
        sm.updateDrivingData(gpsFix(0f))
        assertTrue("no scenes at a traffic light", sm.playContext().moving)
        clock.advance(31_000)
        sm.updateDrivingData(gpsFix(0f))
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
    // ---- car trip: TRIP_PREPARE, ARRIVAL, play context

    /** GPS fixes once a second at [kmh] for [ms] (blink/fidget timers run meanwhile). */
    private fun gps(kmh: Float, ms: Long) {
        var t = 0L
        while (t < ms) {
            scheduler.advance(1_000)
            t += 1_000
            sm.updateDrivingData(gpsFix(kmh))
        }
    }

    @Test
    fun gettingIntoTheCarBucklesUpAndReturnsToCalm() {
        sm.onCarEntered()
        assertEquals(CompanionState.TRIP_PREPARE, state)
        assertEquals(TripPhase.PREPARING, sm.tripPhase())
        scheduler.advance(2_500)
        assertEquals(CompanionState.CALM, state)
        assertFalse("Bluetooth alone is not driving", sm.playContext().moving)
    }

    @Test
    fun gettingIntoTheCarWaitsForAlertAndPet() {
        sm.onLongPress()
        sm.onCarEntered()
        assertEquals(CompanionState.PET, state)
        sm.onLongPressReleased()
        sm.onAlertTriggered()
        sm.onCarEntered()
        assertEquals(CompanionState.ALERT, state)
    }

    @Test
    fun preparingEndsAtOnceWhenTheCarMoves() {
        oneShotMs = null
        sm.onCarEntered()
        sm.updateDrivingData(gpsFix(0f))
        sm.updateDrivingData(gpsFix(8f))
        assertEquals(CompanionState.CALM, state)
    }

    @Test
    fun longTripEndsWithArrival() {
        sm.onCarEntered()
        gps(0f, 5_000)
        gps(60f, 12 * 60_000L)
        assertEquals(TripPhase.DRIVING, sm.tripPhase())
        assertFalse(states.contains(CompanionState.ARRIVAL))
        gps(0f, TripTracker.ARRIVAL_STOP_MS + 2_000)
        assertTrue(states.contains(CompanionState.ARRIVAL))
        assertEquals(TripPhase.ARRIVED, sm.tripPhase())
        assertEquals(1, states.count { it == CompanionState.ARRIVAL })
        gps(0f, 5 * 60_000L)
        assertEquals("celebrated once", 1, states.count { it == CompanionState.ARRIVAL })
    }

    @Test
    fun trafficLightsAreNotArrivals() {
        sm.onCarEntered()
        gps(60f, 15 * 60_000L)
        repeat(4) {
            gps(0f, 90_000)
            gps(40f, 60_000)
        }
        assertFalse(states.contains(CompanionState.ARRIVAL))
        assertEquals(TripPhase.DRIVING, sm.tripPhase())
    }

    @Test
    fun shortTripHasNoArrival() {
        sm.onCarEntered()
        gps(50f, 4 * 60_000L)
        gps(0f, TripTracker.ARRIVAL_STOP_MS + 5_000)
        assertFalse(states.contains(CompanionState.ARRIVAL))
        assertEquals(TripPhase.PARKED, sm.tripPhase())
    }

    @Test
    fun engineOffAfterALongTripIsAnArrival() {
        sm.onCarEntered()
        gps(60f, 12 * 60_000L)
        gps(0f, 20_000)
        sm.onCarDisconnected()
        assertEquals(CompanionState.ARRIVAL, state)
    }

    @Test
    fun engineOffWhileRollingOrWithoutGpsIsNothing() {
        sm.onCarEntered()
        sm.onCarDisconnected()
        assertFalse("no GPS: no trip events", states.contains(CompanionState.ARRIVAL))
        gps(60f, 12 * 60_000L)
        sm.onCarDisconnected()
        assertFalse(states.contains(CompanionState.ARRIVAL))
    }

    @Test
    fun arrivalWaitsForPetThenPlays() {
        sm.onCarEntered()
        gps(60f, 12 * 60_000L)
        gps(0f, TripTracker.ARRIVAL_STOP_MS - 3_000)
        sm.onLongPress()
        gps(0f, 6_000)                                   // the trip ends while the fox is petted
        assertEquals(CompanionState.PET, state)
        sm.onLongPressReleased()
        gps(0f, 1_000)
        assertTrue(states.contains(CompanionState.ARRIVAL))
    }

    @Test
    fun arrivalEndsWhenTheCarMovesAgain() {
        oneShotMs = null
        sm.onCarEntered()
        gps(60f, 12 * 60_000L)
        gps(0f, TripTracker.ARRIVAL_STOP_MS + 1_000)
        assertEquals(CompanionState.ARRIVAL, state)
        sm.updateDrivingData(gpsFix(6f))
        assertEquals(CompanionState.CALM, state)
    }

    @Test
    fun noCarEventsInTheDailyProfile() {
        sm.setProfile(AppProfile.DAILY)
        gps(60f, 12 * 60_000L)
        gps(0f, 5 * 60_000L)
        sm.onCarDisconnected()
        assertFalse(states.contains(CompanionState.ARRIVAL))
        assertFalse(sm.playContext().car)
        assertFalse(sm.playContext().parked)
    }

    @Test
    fun parkedOnlyAfterAConfirmedStandstill() {
        assertTrue(sm.playContext().car)
        assertFalse("no GPS yet: unknown, not parked", sm.playContext().parked)
        gps(8f, 60_000)                                     // moving off the drive, not a trip yet
        gps(0f, 30_000)
        assertFalse("traffic light", sm.playContext().parked)
        gps(0f, 40_000)
        assertTrue(sm.playContext().parked)
        scheduler.advance(TripTracker.GPS_STALE_MS + 1)
        assertFalse("GPS lost: not confirmed any more", sm.playContext().parked)
    }

    @Test
    fun aLongStopMidTripIsNeitherParkedNorArrived() {
        sm.onCarEntered()
        gps(60f, 20 * 60_000L)
        gps(0f, 5 * 60_000L)                               // level crossing / jam
        assertFalse("no car scenes in a jam", sm.playContext().parked)
        assertFalse("no celebration mid-route", states.contains(CompanionState.ARRIVAL))
        gps(0f, TripTracker.ARRIVAL_STOP_MS - 5 * 60_000L + 2_000)
        assertTrue(states.contains(CompanionState.ARRIVAL))
        assertTrue("now it is parked", sm.playContext().parked)
    }

    @Test
    fun syncProfileOnlyRecordsTheProfile() {
        sm.setProfile(AppProfile.DAILY)
        states.clear()
        sm.syncProfile(AppProfile.AUTO)
        assertTrue(states.isEmpty())
        assertTrue(sm.playContext().car)
        assertEquals(TripPhase.PARKED, sm.tripPhase())
    }
    // ---- only real GPS fixes count

    @Test
    fun sensorEventsDoNotKeepAnOldSpeedFresh() {
        sm.onCarEntered()
        gps(60f, 12 * 60_000L)
        val last = gpsFix(0f)
        sm.updateDrivingData(last)                   // the car stopped, then GPS is lost
        repeat(10 * 60) {                             // braking / turn events keep coming, no new fixes
            scheduler.advance(1_000)
            sm.updateDrivingData(sensorEvent(last, isTurning = it % 2 == 0))
        }
        assertFalse("no arrival without GPS", states.contains(CompanionState.ARRIVAL))
        assertFalse("not a confirmed stop", sm.playContext().parked)
        assertFalse(sm.playContext().moving)
        assertEquals(TripPhase.DRIVING, sm.tripPhase())
    }

    @Test
    fun sensorEventsDoNotAddDrivingTime() {
        sm.onCarEntered()
        gps(60f, 2 * 60_000L)
        val last = gpsFix(60f)
        sm.updateDrivingData(last)
        repeat(15 * 60) {                             // GPS lost in motion, only sensor events
            scheduler.advance(1_000)
            sm.updateDrivingData(sensorEvent(last))
        }
        assertFalse("stale speed is not driving", sm.playContext().moving)
        gps(0f, TripTracker.ARRIVAL_STOP_MS + 5_000)
        assertFalse("2 min of real driving is a short trip", states.contains(CompanionState.ARRIVAL))
    }

    @Test
    fun oldOrInaccurateFixesAreIgnored() {
        repeat(5) {
            scheduler.advance(1_000)
            sm.updateDrivingData(gpsFix(50f, ageMs = TripTracker.GPS_STALE_MS + 5_000))
        }
        assertFalse("cached location", sm.playContext().moving)
        repeat(5) {
            scheduler.advance(1_000)
            sm.updateDrivingData(gpsFix(50f, accuracyM = 300f))
        }
        assertFalse("too inaccurate", sm.playContext().moving)
        sm.updateDrivingData(gpsFix(50f))
        assertTrue(sm.playContext().moving)
    }

    @Test
    fun noSharpTurnReactionOnAStaleSpeed() {
        sm.updateDrivingData(gpsFix(60f))
        val last = gpsFix(60f)
        sm.updateDrivingData(last)
        scheduler.advance(TripTracker.GPS_STALE_MS + 1_000)
        sm.updateDrivingData(sensorEvent(last, isTurning = true, turnDirection = TurnDirection.LEFT))
        assertFalse(states.contains(CompanionState.TURN_LEFT))
    }

    // ---- showing the overlay again keeps the trip

    @Test
    fun overlayShownAgainKeepsTheTrip() {
        sm.onCarEntered()
        gps(60f, 8 * 60_000L)
        // Hidden for a minute and shown again: what CompanionService.setInitialState() does.
        sm.syncProfile(AppProfile.AUTO)
        sm.resetInteraction()
        sm.onOverlayShown()
        assertEquals(TripPhase.DRIVING, sm.tripPhase())
        gps(60f, 4 * 60_000L)
        assertFalse(states.contains(CompanionState.TRIP_START))
        gps(0f, TripTracker.ARRIVAL_STOP_MS + 2_000)
        assertTrue("8 + 4 min of driving add up", states.contains(CompanionState.ARRIVAL))
    }

    @Test
    fun overlayShownAgainStillReleasesStuckReactions() {
        oneShotMs = null
        sm.onLongPress()
        sm.resetInteraction()
        assertFalse(sm.isBusy())
        assertEquals(CompanionState.CALM, state)
    }
}
