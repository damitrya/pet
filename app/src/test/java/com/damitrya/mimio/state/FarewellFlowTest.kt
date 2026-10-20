package com.damitrya.mimio.state

import com.damitrya.mimio.data.AppProfile
import com.damitrya.mimio.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * "Hide" / "Show" around the farewell as the service runs them ([FarewellFlow]), with a stand-in for the
 * overlay and CharacterView: an attached view reports the end of a one-shot after it played; a removed
 * (detached) view never reports anything again — its posted callbacks are lost.
 */
class FarewellFlowTest {

    private val clock = FakeClock()
    private val scheduler = FakeScheduler(clock)
    private val mood = CharacterMood(MemoryMoodStore(), clock)
    private val director = AnimationDirector(mood, { PlayContext() }, Random(3), clock)
    private lateinit var sm: StateMachine
    private lateinit var flow: FarewellFlow

    private var visible = true
    private var viewAlive = true
    private var hides = 0
    private var shows = 0
    private val played = mutableListOf<Pair<CompanionState, String>>()

    @Before
    fun setUp() {
        sm = StateMachine(SettingsRepository(InMemoryPrefs()), clock, scheduler, Random(5), mood)
        sm.setListener(object : StateMachine.Listener {
            override fun onStateChanged(newState: CompanionState, previousState: CompanionState) {
                if (!visible) return                           // hidden: no view to play anything
                val v = director.pick(newState, AnimationDirectorTest.FOX) ?: return
                played += newState to v.resName
                if (!newState.isLooping) {
                    val ms = if (v.resName == AnimationPools.HEART_TRANSFORM) 4_600L else 2_000L
                    val view = Any()
                    currentView = view
                    scheduler.postDelayed(Runnable {
                        // CharacterView.isRealEnd: only the drawable still on an attached view counts
                        if (viewAlive && currentView === view) sm.onOneShotCompleted(newState)
                    }, ms)
                }
            }
            override fun onWeatherModifierChanged(modifier: WeatherModifier) {}
        })
        sm.setProfile(AppProfile.DAILY)
        flow = FarewellFlow(sm, { visible }, { visible = false; hides++ }, {
            visible = true; viewAlive = true; shows++
            sm.resetInteraction()                              // setInitialState()
        })
    }

    private var currentView: Any? = null
    private val state get() = sm.getCurrentState()

    @Test
    fun hidePlaysTheHeartThenHides() {
        flow.onHidePressed()
        assertEquals(CompanionState.FAREWELL to AnimationPools.HEART_TRANSFORM, played.last())
        scheduler.advance(4_000)
        assertTrue("still saying goodbye", visible)
        scheduler.advance(700)
        assertFalse(visible)
        assertEquals(1, hides)
        scheduler.advance(60_000)
        assertEquals(1, hides)
    }

    @Test
    fun hideThenShowKeepsTheFox() {
        flow.onHidePressed()
        scheduler.advance(1_000)
        flow.onShowPressed()
        assertTrue(visible)
        assertTrue(state.isLooping)
        scheduler.advance(60_000)                             // the old heart end and the timeout pass by
        assertTrue("never hidden by the dropped farewell", visible)
        assertEquals(0, hides)
        // and a later Hide works normally again
        flow.onHidePressed()
        scheduler.advance(StateMachine.FAREWELL_TIMEOUT_MS)
        assertFalse(visible)
        assertEquals(1, hides)
    }

    @Test
    fun brakingDuringTheFarewellHidesAtOnce() {
        flow.onHidePressed()
        scheduler.advance(1_000)
        sm.onAlertTriggered()
        assertFalse(visible)
        assertEquals(1, hides)
        assertEquals(CompanionState.ALERT, state)
        scheduler.advance(60_000)
        assertEquals(1, hides)
        // shown again later: everything works, ALERT did not get stuck behind the hidden overlay
        flow.onShowPressed()
        assertTrue(state.isLooping)
    }

    @Test
    fun hidePressedTwiceHidesAtOnce() {
        flow.onHidePressed()
        scheduler.advance(500)
        flow.onHidePressed()
        assertFalse(visible)
        assertEquals(1, hides)
        flow.onHidePressed()                                  // already hidden: nothing
        scheduler.advance(60_000)
        assertEquals(1, hides)
    }

    @Test
    fun overlayDestroyedDuringTheHeartStillHidesInTime() {
        flow.onHidePressed()
        scheduler.advance(1_000)
        viewAlive = false                                     // the view lost its window: no end callback
        scheduler.advance(StateMachine.FAREWELL_TIMEOUT_MS)
        assertEquals("the safety timeout hides it", 1, hides)
        assertFalse(sm.isFarewellRunning())
        assertTrue(state.isLooping)
    }

    @Test
    fun serviceStoppedDuringTheHeartNeverHidesLater() {
        flow.onHidePressed()
        scheduler.advance(1_000)
        flow.release()                                        // onDestroy
        sm.destroy()
        scheduler.advance(60_000)
        assertEquals("no hide / notification after the service is gone", 0, hides)
    }

    @Test
    fun noFarewellDuringBrakingHidesAtOnce() {
        sm.onAlertTriggered()
        flow.onHidePressed()
        assertFalse(visible)
        assertEquals(1, hides)
        assertTrue(played.none { it.first == CompanionState.FAREWELL })
    }
}
