package com.damitrya.mimio.state

import com.damitrya.mimio.data.AppProfile
import com.damitrya.mimio.data.MediaData
import com.damitrya.mimio.data.SettingsRepository
import com.damitrya.mimio.state.AnimationDirectorTest.Companion.FOX
import com.damitrya.mimio.state.AnimationDirectorTest.Companion.SLIME
import com.damitrya.mimio.state.AnimationDirectorTest.Companion.SLIME_FILES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * Wave 4: the heart after petting and as a farewell, the music shows (side dance, microphone, guitar)
 * and turning its back in CALM — state machine and director together, as CharacterView drives them.
 */
class Wave4Test {

    private val clock = FakeClock()
    private val scheduler = FakeScheduler(clock)
    private val mood = CharacterMood(MemoryMoodStore(), clock)
    private var ctx = PlayContext()
    private lateinit var sm: StateMachine
    private lateinit var director: AnimationDirector

    /** (state, file) in the order the view started them. */
    private val played = mutableListOf<Pair<CompanionState, String>>()

    /** One-shots end after this long, like the view reports them; null = they never report an end. */
    private var oneShotMs: Long? = 2_000L
    private var available: (AnimationVariant) -> Boolean = FOX

    @Before
    fun setUp() {
        sm = StateMachine(SettingsRepository(InMemoryPrefs()), clock, scheduler, Random(5), mood)
        director = AnimationDirector(mood, { ctx }, Random(17), clock)
        sm.idleBusy = { director.isSceneRunning() }
        sm.distractingScene = { director.isDistractingSceneRunning() }
        sm.setListener(object : StateMachine.Listener {
            override fun onStateChanged(newState: CompanionState, previousState: CompanionState) {
                val v = director.pick(newState, available) ?: return
                played += newState to v.resName
                val ms = oneShotMs
                if (!newState.isLooping && ms != null) {
                    scheduler.postDelayed(Runnable {
                        director.onFinished(newState, v)
                        sm.onOneShotCompleted(newState)
                    }, ms)
                }
            }
            override fun onWeatherModifierChanged(modifier: WeatherModifier) {}
        })
        sm.setProfile(AppProfile.DAILY)
    }

    private val state get() = sm.getCurrentState()

    /** One petting: hold, release, let the reaction play out. */
    private fun pet() {
        sm.onLongPress()
        scheduler.advance(1_500)
        sm.onLongPressReleased()
    }

    // ---- 1, 2: the heart after petting

    @Test
    fun pettingSometimesEndsAsAHeart() {
        val finales = mutableListOf<String>()
        repeat(60) {
            pet()
            finales += played.last().second
            scheduler.advance(3 * 60_000L)          // past the shy series and the heart's minimum gap
        }
        assertTrue("heart seen: $finales", AnimationPools.HEART_TRANSFORM in finales)
        assertTrue("hearts still the usual finale", finales.count { it == "hearts" } > finales.size / 3)
        // never twice in a row
        finales.zipWithNext().forEach { (a, b) ->
            assertFalse(a == AnimationPools.HEART_TRANSFORM && b == AnimationPools.HEART_TRANSFORM)
        }
    }

    @Test
    fun heartIsNotRepeatedTooOften() {
        val hearts = mutableListOf<Long>()
        repeat(200) {
            pet()
            if (played.last().second == AnimationPools.HEART_TRANSFORM) hearts += clock.now
            scheduler.advance(25_000L)              // petting again and again (shy series in between)
        }
        assertTrue(hearts.size >= 2)
        hearts.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 2 * 60_000L) }
    }

    @Test
    fun oneLongPressGivesOneReaction() {
        oneShotMs = null
        sm.onLongPress()
        played.clear()
        sm.onLongPressReleased()
        sm.onLongPressReleased()                   // ACTION_UP and a late ACTION_CANCEL
        sm.onTap()                                 // a stray tap while the finale plays only queues
        val reactions = played.filter { it.first == CompanionState.HEARTS || it.first == CompanionState.SHY }
        assertEquals(played.toString(), 1, reactions.size)
        assertEquals(1, played.size)
    }

    @Test
    fun heartFromPettingIsAReactionNotABackgroundScene() {
        director.pick(CompanionState.HEARTS) { it.resName == AnimationPools.HEART_TRANSFORM }
        assertFalse(director.isSceneRunning())
        assertFalse(director.isDistractingSceneRunning())
    }

    // ---- 3, 4: the farewell when the user hides the character

    @Test
    fun farewellPlaysTheHeartThenHides() {
        var hidden = 0
        assertTrue(sm.startFarewell { hidden++ })
        assertEquals(CompanionState.FAREWELL, state)
        assertEquals(CompanionState.FAREWELL to AnimationPools.HEART_TRANSFORM, played.last())
        sm.onTap()
        sm.onLongPress()
        assertEquals("touches no longer interrupt the goodbye", CompanionState.FAREWELL, state)
        assertEquals(0, hidden)
        scheduler.advance(2_000)                   // the animation ends
        assertEquals(1, hidden)
        assertEquals(CompanionState.CALM, state)
        assertFalse(sm.isFarewellRunning())
        scheduler.advance(StateMachine.FAREWELL_TIMEOUT_MS)
        assertEquals("hidden only once", 1, hidden)
    }

    @Test
    fun farewellHasASafetyTimeout() {
        oneShotMs = null                           // the end is never reported (decoder stalled…)
        var hidden = 0
        sm.startFarewell { hidden++ }
        scheduler.advance(StateMachine.FAREWELL_TIMEOUT_MS - 1)
        assertEquals(0, hidden)
        scheduler.advance(1)
        assertEquals(1, hidden)
        assertEquals(CompanionState.CALM, state)
        scheduler.advance(StateMachine.ONE_SHOT_TIMEOUT_MS)
        assertEquals(1, hidden)
    }

    @Test
    fun noFarewellDuringACriticalReactionOrSleep() {
        oneShotMs = null
        sm.onAlertTriggered()
        assertFalse("braking: hide at once", sm.startFarewell { })
        assertEquals(CompanionState.ALERT, state)
        sm.resetInteraction()
        var steps = 0
        oneShotMs = 2_000L
        while (state != CompanionState.SLEEP) { scheduler.advance(250); check(++steps < 4000) }
        assertFalse("asleep: hide at once, without waking up", sm.startFarewell { })
        assertEquals(CompanionState.SLEEP, state)
    }

    @Test
    fun brakingDuringTheFarewellHidesAtOnce() {
        oneShotMs = null
        var hidden = 0
        sm.startFarewell { hidden++ }
        sm.onAlertTriggered()
        assertEquals(1, hidden)
        assertEquals(CompanionState.ALERT, state)
        // the late end of the interrupted heart changes nothing
        sm.onOneShotCompleted(CompanionState.FAREWELL)
        scheduler.advance(StateMachine.FAREWELL_TIMEOUT_MS)
        assertEquals(1, hidden)
        assertEquals(CompanionState.ALERT, state)
    }

    @Test
    fun shownAgainDuringTheFarewellStays() {
        var hidden = 0
        sm.startFarewell { hidden++ }
        sm.cancelFarewell()                        // "Show" pressed
        sm.resetInteraction()
        scheduler.advance(StateMachine.FAREWELL_TIMEOUT_MS * 2)
        assertEquals(0, hidden)
        assertTrue(state.isLooping || state == CompanionState.BLINK)
    }

    @Test
    fun farewellDuringMusicReturnsToMusicBehindTheHiddenOverlay() {
        sm.updateMediaData(MediaData(isPlaying = true))
        var hidden = 0
        sm.startFarewell { hidden++ }
        scheduler.advance(2_000)
        assertEquals(1, hidden)
        assertEquals(CompanionState.MUSIC, state)
    }

    @Test
    fun slimeSaysGoodbyeWithItsOwnFile() {
        available = SLIME
        sm.startFarewell { }
        val v = director.pick(CompanionState.FAREWELL, SLIME)!!
        assertTrue(v.resName in SLIME_FILES || v.fallback in SLIME_FILES)
    }

    // ---- 5: the music shows

    private fun musicPicks(minutes: Int): List<AnimationVariant> =
        (1..minutes * 60 / 3).map { clock.advance(3_000); director.pick(CompanionState.MUSIC, available)!! }

    @Test
    fun newMusicVariantsArePickedButStayRare() {
        val picks = musicPicks(120).map { it.resName }
        for (name in listOf("dance_side", "microphone", "guitar")) assertTrue(name, name in picks)
        val shows = picks.count { it == "guitar" || it == "microphone" }.toDouble() / picks.size
        assertTrue("guitar + microphone share $shows", shows < 0.06)
        val usual = picks.count { it in setOf("music", "music2", "music_nod", "music_paws") }.toDouble() / picks.size
        assertTrue("the usual dances stay the norm: $usual", usual > 0.75)
    }

    @Test
    fun musicShowsAreSpacedOut() {
        val times = HashMap<String, MutableList<Long>>()
        repeat(4 * 60 * 60 / 3) {
            clock.advance(3_000)
            val v = director.pick(CompanionState.MUSIC, available)!!
            times.getOrPut(v.resName) { mutableListOf() } += clock.now
        }
        for (name in listOf("guitar", "microphone")) {
            times.getValue(name).zipWithNext().forEach { (a, b) -> assertTrue(name, b - a >= 4 * 60_000L) }
        }
        val shows = (times["guitar"].orEmpty() + times["microphone"].orEmpty()).sorted()
        shows.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= AnimationDirector.SCENE_GAP_MS) }
    }

    @Test
    fun noMusicShowsOrSideDanceWhileDriving() {
        ctx = PlayContext(moving = true, car = true, trip = TripPhase.DRIVING)
        val names = musicPicks(120).map { it.resName }.toSet()
        assertTrue(names.intersect(setOf("dance_side", "microphone", "guitar")).isEmpty())
    }

    @Test
    fun aDanceBreakWaitsForTheShowToEnd() {
        oneShotMs = null
        var show = true
        sm.idleBusy = { show }
        sm.updateMediaData(MediaData(isPlaying = true))
        scheduler.advance(45_000)                  // the FIDGET timer is due (20–40 s)
        assertEquals("not in the middle of the guitar solo", CompanionState.MUSIC, state)
        show = false
        scheduler.advance(1_100)
        assertEquals(CompanionState.FIDGET, state)
    }

    @Test
    fun musicShowCountsAsARunningScene() {
        director.pick(CompanionState.MUSIC) { it.resName == "guitar" }
        assertTrue(director.isSceneRunning())
        assertTrue(director.isDistractingSceneRunning())
        clock.advance(AnimationDirector.MAX_SCENE_MS)
        assertFalse("a lost end callback never blocks", director.isSceneRunning())
        director.pick(CompanionState.MUSIC) { it.resName == "music" }
        assertFalse(director.isSceneRunning())
    }

    @Test
    fun startingToDriveCutsAMusicShow() {
        oneShotMs = null
        var cuts = 0
        sm.onSceneCut = { cuts++ }
        sm.updateDrivingData(gpsFix(0f))
        sm.updateMediaData(MediaData(isPlaying = true))
        director.pick(CompanionState.MUSIC) { it.resName == "microphone" }
        sm.updateDrivingData(gpsFix(12f))
        assertEquals(1, cuts)
    }

    // ---- 6: turning its back in CALM

    @Test
    fun tailBackNeverWhileTheCarMoves() {
        ctx = PlayContext(moving = true, car = true, trip = TripPhase.DRIVING)
        repeat(3_000) {
            clock.advance(3_000)
            assertFalse(director.pick(CompanionState.CALM, available)!!.resName == "tail_back")
        }
        ctx = PlayContext()
        val calm = (1..4_000).map { clock.advance(3_000); director.pick(CompanionState.CALM, available)!!.resName }
        assertTrue("tail_back" in calm)
    }

    @Test
    fun tailBackIsCutWhenTheCarStarts() {
        director.pick(CompanionState.CALM) { it.resName == "tail_back" }
        assertTrue(director.isDistractingSceneRunning())
        var cuts = 0
        sm.onSceneCut = { cuts++ }
        sm.updateDrivingData(gpsFix(0f))
        sm.updateDrivingData(gpsFix(12f))
        assertEquals(1, cuts)
    }

    // ---- 7: the slime has none of the new files

    @Test
    fun slimeSkipsTheNewVariants() {
        val new = setOf("dance_side", "microphone", "guitar", "tail_back", AnimationPools.HEART_TRANSFORM)
        repeat(2_000) {
            clock.advance(3_000)
            for (s in listOf(CompanionState.CALM, CompanionState.MUSIC, CompanionState.HEARTS)) {
                val v = director.pick(s, SLIME)!!
                assertFalse("$s → ${v.resName}", v.resName in new)
            }
        }
    }

    // ---- 8: nothing continues after an interruption

    @Test
    fun lateEndOfAnInterruptedShowChangesNothing() {
        val guitar = director.pick(CompanionState.MUSIC) { it.resName == "guitar" }!!
        director.pick(CompanionState.ALERT, available)        // braking interrupts the show
        director.onFinished(CompanionState.MUSIC, guitar)       // its end arrives late
        assertFalse(director.isSceneRunning())
        assertEquals("alert", director.pick(CompanionState.ALERT, available)!!.resName)
        // and the state machine ignores a late one-shot end of a state that is over
        oneShotMs = null
        sm.onAlertTriggered()
        sm.onOneShotCompleted(CompanionState.HEARTS)
        assertEquals(CompanionState.ALERT, state)
    }

    @Test
    fun alertInterruptsTheNewScenes() {
        oneShotMs = null
        sm.updateMediaData(MediaData(isPlaying = true))
        director.pick(CompanionState.MUSIC) { it.resName == "guitar" }
        sm.onAlertTriggered()
        assertEquals(CompanionState.ALERT, state)
        sm.onLongPress()
        sm.onLongPressReleased()
        assertEquals("petting does not override braking", CompanionState.ALERT, state)
    }
}
